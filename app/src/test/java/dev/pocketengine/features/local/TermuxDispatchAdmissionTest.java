package dev.pocketengine.features.local;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeReceipt;
import dev.pocketengine.features.local.runtime.TermuxProjectRuntime;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real bridge/cache and original runtime references; only Android's service is a host fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TermuxDispatchAdmissionTest {
    private static final String CHECKOUT="12345678-1234-1234-1234-123456789abc";
    private Context app;private RunControl original;private JSONObject source;

    @Before public void setup()throws Exception {
        app=RuntimeEnvironment.getApplication();PackageInfo pkg=new PackageInfo();pkg.packageName="com.termux";pkg.versionName="admission-fixture";
        shadowOf(app.getPackageManager()).installPackage(pkg);shadowOf((Application)app).grantPermissions(TermuxBridge.PERMISSION);
        original=new RunControl();original.bindHostRun("task","run",1);
        source=new JSONObject().put("checkout",new JSONObject().put("id",CHECKOUT).put("projectId","project"))
                .put("files",new JSONArray().put(new JSONObject().put("path","main.py").put("content","print(1)\n")));
    }
    private TermuxBridge bridge(Context context)throws Exception {
        TermuxBridge bridge=new TermuxBridge(new FeatureContext(context,null,null,event->{}));bridge.setEnabled(true);return bridge;
    }
    private File receipt(String operation){return new File(app.getFilesDir(),"termux-receipts/"+operation+".json");}
    private RuntimeInvocation invocation(String operation,String command,ProjectRuntime.LiveGate gate)throws Exception {
        String digest=ProjectSources.describe(source).getString("sourceDigest");JSONObject payload=new JSONObject().put("checkoutId",CHECKOUT);
        if(!command.equals("termux.probe"))payload.put("expectedSourceDigest",digest);
        JSONObject exact=new JSONObject().put("feature","local").put("command",command).put("projectId","project").put("checkoutId",CHECKOUT)
                .put("sourceDigest",digest).put("planDigest","a".repeat(64)).put("stepId","step").put("intent","build")
                .put("payload",payload).put("requestDigest",ProjectSources.digest(payload));
        JSONObject binding=new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",1).put("operationId",operation);
        return new RuntimeInvocation(exact,binding,source,original,gate);
    }
    private void knownNotDispatched(TermuxBridge bridge,TermuxProjectRuntime runtime,RuntimeInvocation invocation,RuntimeReceipt actual)throws Exception {
        JSONObject value=actual.json();assertTrue(actual.terminal());assertEquals("failed",actual.status());
        assertEquals("not_dispatched",value.getString("effectStatus"));assertFalse(value.getBoolean("termuxDispatchEntered"));
        assertFalse(value.getBoolean("accepted"));assertEquals(invocation.executionRef().operationId(),value.getString("id"));
        invocation.executionRef().requireReceipt(value);assertNull(shadowOf((Application)app).getNextStartedService());assertFalse(bridge.busy(CHECKOUT));
        assertEquals(ProjectSources.canonical(value),ProjectSources.canonical(runtime.query(invocation.executionRef()).json()));
        assertEquals(ProjectSources.canonical(value),ProjectSources.canonical(runtime.requestCancel(invocation.executionRef()).json()));
        // Restart/query and the same original operation never turn a local rejection into a replay.
        TermuxBridge cold=bridge(app);assertEquals("not_dispatched",cold.get(value.getString("id")).getString("effectStatus"));
        JSONObject payload=new JSONObject(invocation.exact().getJSONObject("payload").toString()).put("operationId",value.getString("id"));
        JSONObject replay=invocation.executionRef().phase()==RuntimeExecutionRef.Phase.STAGE?cold.stage(source,payload):cold.probe(payload);
        assertEquals("failed",replay.getString("status"));assertNull(shadowOf((Application)app).getNextStartedService());
        RuntimeExecutionRef wrong=RuntimeExecutionRef.fromHost(invocation.exact(),new JSONObject(invocation.binding().toString()).put("runId","other"));
        assertThrows(SecurityException.class,()->runtime.query(wrong));
    }

    @Test public void cancellationAfterRegistrationSettlesOnlyTheOriginalOperation()throws Exception {
        TermuxBridge bridge=bridge(app);TermuxProjectRuntime runtime=new TermuxProjectRuntime(bridge);String operation="cancel_after_registration";
        AtomicBoolean cancelled=new AtomicBoolean();RuntimeInvocation invocation=invocation(operation,"termux.probe",()->{
            if(receipt(operation).isFile()&&cancelled.compareAndSet(false,true))original.cancel();
        });
        RuntimeReceipt actual=runtime.dispatch(invocation);assertTrue(cancelled.get());assertTrue(original.isCancelled());
        knownNotDispatched(bridge,runtime,invocation,actual);
        // A separately chosen operation is no longer blocked by a command that never left this app.
        JSONObject next=bridge.probe(new JSONObject().put("operationId","fresh_owner_probe").put("checkoutId",CHECKOUT));
        assertEquals("awaiting_result",next.getString("status"));assertNotNull(shadowOf((Application)app).getNextStartedService());
    }
    @Test public void pauseAndResumeAfterRegistrationCannotLeaveStagingBusy()throws Exception {
        TermuxBridge bridge=bridge(app);TermuxProjectRuntime runtime=new TermuxProjectRuntime(bridge);String operation="pause_after_registration";
        AtomicBoolean paused=new AtomicBoolean();RuntimeInvocation invocation=invocation(operation,"termux.prepare",()->{
            if(receipt(operation).isFile()&&paused.compareAndSet(false,true)){original.pause();original.resume();}
        });
        RuntimeReceipt actual=runtime.dispatch(invocation);assertTrue(paused.get());knownNotDispatched(bridge,runtime,invocation,actual);
        assertEquals(invocation.executionRef().sourceDigest(),actual.json().getString("sourceDigest"));
    }
    @Test public void revokedFinalAdmissionAlsoSettlesWithoutDispatch()throws Exception {
        TermuxBridge bridge=bridge(app);TermuxProjectRuntime runtime=new TermuxProjectRuntime(bridge);String operation="revoke_after_registration";
        AtomicBoolean revoked=new AtomicBoolean();RuntimeInvocation invocation=invocation(operation,"termux.probe",()->{
            if(receipt(operation).isFile()){revoked.set(true);throw new SecurityException("fixture final admission revoked");}
        });
        RuntimeReceipt actual=runtime.dispatch(invocation);assertTrue(revoked.get());knownNotDispatched(bridge,runtime,invocation,actual);
    }
    @Test public void exceptionAfterServiceEntryRemainsUnknownUntilItsActualCallback()throws Exception {
        AtomicInteger entered=new AtomicInteger();Context service=new ContextWrapper(app){
            @Override public Context getApplicationContext(){return this;}
            @Override public ComponentName startService(Intent command){entered.incrementAndGet();super.startService(command);original.cancel();throw new IllegalStateException("fixture lost acknowledgement");}
        };
        TermuxBridge bridge=bridge(service);TermuxProjectRuntime runtime=new TermuxProjectRuntime(bridge);RuntimeInvocation invocation=invocation("entered_unknown","termux.probe",()->{});
        RuntimeReceipt actual=runtime.dispatch(invocation);assertEquals(1,entered.get());assertFalse(actual.terminal());assertEquals("unknown",actual.status());
        assertTrue(actual.json().getBoolean("termuxDispatchEntered"));assertNotEquals("not_dispatched",actual.json().optString("effectStatus"));assertTrue(bridge.busy(CHECKOUT));
        Intent command=shadowOf((Application)app).getNextStartedService();assertNotNull(command);
        JSONObject again=bridge.probe(new JSONObject(invocation.exact().getJSONObject("payload").toString()).put("operationId",invocation.executionRef().operationId()));
        assertEquals("unknown",again.getString("status"));assertEquals(1,entered.get());assertEquals("unknown",runtime.requestCancel(invocation.executionRef()).status());
        PendingIntent pending=command.getParcelableExtra("com.termux.RUN_COMMAND_PENDING_INTENT");Intent callback=new Intent(shadowOf(pending).getSavedIntent());
        Bundle result=new Bundle();result.putInt("err",-1);result.putInt("exitCode",0);result.putString("stdout","actual callback");callback.putExtra("result",result);
        assertEquals("completed",TermuxBridge.receive(app,callback).getString("status"));assertTrue(runtime.query(invocation.executionRef()).terminal());assertFalse(bridge.busy(CHECKOUT));
    }
    @Test public void nullServiceAcknowledgementAndLegacyUnknownCannotProveNoDispatch()throws Exception {
        AtomicInteger entered=new AtomicInteger();Context service=new ContextWrapper(app){
            @Override public Context getApplicationContext(){return this;}
            @Override public ComponentName startService(Intent command){entered.incrementAndGet();return null;}
        };
        TermuxBridge bridge=bridge(service);TermuxProjectRuntime runtime=new TermuxProjectRuntime(bridge);RuntimeInvocation invocation=invocation("entered_null","termux.probe",()->{});
        RuntimeReceipt actual=runtime.dispatch(invocation);assertEquals(1,entered.get());assertFalse(actual.terminal());assertEquals("unknown",actual.status());
        assertTrue(actual.json().getBoolean("termuxDispatchEntered"));assertTrue(bridge.busy(CHECKOUT));
        String legacyCheckout="abcdefab-1234-1234-1234-123456789abc";JsonFiles.write(receipt("legacy_unknown"),new JSONObject().put("id","legacy_unknown").put("operationId","legacy_unknown").put("checkoutId",legacyCheckout).put("status","unknown"));
        TermuxBridge cold=bridge(app);assertTrue(cold.busy(legacyCheckout));assertEquals("unknown",cold.cancel("legacy_unknown").getString("status"));assertTrue(cold.busy(legacyCheckout));
    }
}
