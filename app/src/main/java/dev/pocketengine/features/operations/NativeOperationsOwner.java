package dev.pocketengine.features.operations;

import android.Manifest;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.local.LocalModule;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import dev.pocketengine.R;

/** Foreground owner maintenance surface; no remote/model entry or automatic startup work. */
public final class NativeOperationsOwner implements AutoCloseable {
    private final EngineApplication app;private final AtomicReference<RunControl> active=new AtomicReference<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"owner-operations");t.setDaemon(true);return t;});
    public NativeOperationsOwner(EngineApplication app){this.app=java.util.Objects.requireNonNull(app);}
    public MinimalDiagnostics.Snapshot diagnostics()throws Exception{
        Map<String,Callable<JSONObject>> sources=new LinkedHashMap<>();
        sources.put("app",()->{var info=app.getPackageManager().getPackageInfo(app.getPackageName(),0);JSONObject facts=new JSONObject().put("versionName",info.versionName).put("versionCode",info.getLongVersionCode()).put("sourceCommit","unavailable").put("sourceTree","unavailable").put("sourceDirty",true).put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS.length==0?"unknown":Build.SUPPORTED_ABIS[0]);
            try(var asset=app.getAssets().open("phone-build-identity.json")){JSONObject build=OperationsEvidence.buildIdentity(asset);for(String key:List.of("sourceCommit","sourceTree","sourceDirty"))facts.put(key,build.get(key));}catch(Exception unavailable){}return facts;});
        sources.put("phone",()->{JSONObject actual=app.phoneReadiness();return new JSONObject().put("connected",actual.optBoolean("backgroundConnected")).put("available",actual.optBoolean("backgroundSupported")).put("permissionGranted",actual.optBoolean("windowContent")).put("status",actual.optBoolean("backgroundSessionReady")?"ready":"not_ready");});
        sources.put("notifications",this::notifications);
        sources.put("runtime",()->{EngineModule module=app.features.module("local");return module instanceof LocalModule local?local.projectRuntimeForHost("managed_process_v1"):new JSONObject().put("status","unavailable");});
        sources.put("devices",()->app.deviceReceiverState());
        sources.put("model",()->new JSONObject().put("configured",!app.settings.get().model.isBlank()));
        sources.put("recent_runs",()->{JSONArray states=new JSONArray(),rows=app.tasks.taskSummaryPage("",5).getJSONArray("tasks");for(int i=0;i<rows.length();i++)states.put(rows.getJSONObject(i).optString("status"));return new JSONObject().put("recentStates",states);});
        return MinimalDiagnostics.capture(sources,System.currentTimeMillis());
    }
    private JSONObject notifications()throws Exception{NotificationManager manager=app.getSystemService(NotificationManager.class);boolean permission=Build.VERSION.SDK_INT<33||app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;return new JSONObject().put("enabled",manager!=null&&manager.areNotificationsEnabled()).put("permissionGranted",permission);}
    public void startChecks(Consumer<JSONObject> progress)throws Exception{
        if(app.activeId()!=null||app.tasks.ownerDiagnosticsBusy())throw new IllegalStateException(LiteralUiText.text(R.string.w8_ops_error_000, "原任务仍在执行，请先完成或停止，再运行兼容性自检"));
        RunControl original=new RunControl();if(!active.compareAndSet(null,original))throw new IllegalStateException(LiteralUiText.text(R.string.w8_ops_error_001, "兼容性自检仍在运行"));
        List<CompatibilityCheckRunner.Check> checks=new ArrayList<>();
        checks.add(new CompatibilityCheckRunner.Check("device",2000,control->{control.check();return new JSONObject().put("status",Build.VERSION.SDK_INT>=30&&Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")?"passed":"partial").put("sdk",Build.VERSION.SDK_INT).put("distribution","android_11_arm64");}));
        checks.add(new CompatibilityCheckRunner.Check("permissions",3000,control->{control.check();JSONObject value=notifications();return value.put("status",value.getBoolean("enabled")&&value.getBoolean("permissionGranted")?"passed":"partial").put("scope","notification_readiness_only");}));
        checks.add(new CompatibilityCheckRunner.Check("phone",5000,control->{control.check();return OperationsEvidence.phonePractice(app.phoneReadiness(),app.phonePracticeResult());}));
        checks.add(new CompatibilityCheckRunner.Check("browser",15000,new IsolatedWebProbe(app)));
        checks.add(new CompatibilityCheckRunner.Check("runtime",13000,control->{EngineModule module=app.features.module("local");if(!(module instanceof OperationsRuntime runtime))throw new IllegalStateException(LiteralUiText.text(R.string.w8_ops_error_002, "原本机诊断宿主未接线"));return runtime.runOwnerCompatibilityProbe(control);}));
        try{worker.execute(()->{try{CompatibilityCheckRunner.run(checks,original,progress);}catch(Exception failure){try{progress.accept(new JSONObject().put("ended",true).put("status","failed").put("reason","selfcheck_interrupted").put("resourcesReleased",false));}catch(Exception ignored){}}finally{active.compareAndSet(original,null);}});}
        catch(RejectedExecutionException closed){active.compareAndSet(original,null);throw new IllegalStateException(LiteralUiText.text(R.string.w8_ops_error_003, "自检页面已关闭"));}
    }
    public void cancelChecks(){RunControl original=active.get();if(original!=null)original.cancel();}
    public boolean checksRunning(){return active.get()!=null;}
    @Override public void close(){cancelChecks();worker.shutdown();}
}
