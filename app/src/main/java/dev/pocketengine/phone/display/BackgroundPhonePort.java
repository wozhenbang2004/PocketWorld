package dev.pocketengine.phone.display;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.displayhelper.DisplayGeometry;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.observation.ObservationReport;
import dev.pocketengine.phone.observation.AccessibilityForest;
import dev.pocketengine.phone.observation.ObservationSafety;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import dev.pocketengine.phone.observation.NativeNodeActionReceipt;
import dev.pocketengine.phone.observation.PhoneReadGate;
import dev.pocketengine.phone.observation.NativeSensitiveStep;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A task is pinned to one separately created display; this class has no primary-screen actions. */
public final class BackgroundPhonePort implements PhonePort,dev.pocketengine.phone.observation.NativeOcrTapPort,dev.pocketengine.phone.observation.NativeTreeScrollPort,dev.pocketengine.phone.observation.NativePhoneSignals,dev.pocketengine.phone.observation.NativePhoneExtensions {
    private final DisplayModule module;
    private final String sessionId;
    private final int displayId;
    private final RunControl runOwner;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final PhoneReadGate reads=new PhoneReadGate();
    private final java.util.concurrent.atomic.AtomicBoolean previewInFlight=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Observation current;
    private volatile JSONObject cached;
    private volatile OwnerPermit ownerPermit;
    private volatile boolean lastIncludeImage;
    private final java.util.concurrent.atomic.AtomicLong nativeFrameAfterNanos=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong displayChanges=new java.util.concurrent.atomic.AtomicLong();
    private volatile DisplayManager geometryDisplays;
    private volatile boolean closed;
    private final DisplayManager.DisplayListener geometryListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id) { }
        public void onDisplayChanged(int id){if(id==displayId){displayChanges.incrementAndGet();clearReferences();}}
        public void onDisplayRemoved(int id){if(id==displayId){displayChanges.incrementAndGet();clearReferences();stopGeometryWatch();}}
    };
    private JSONObject landingBaseline;
    private android.content.Context lifecycleContext;
    private dev.pocketengine.phone.setup.PhoneAudioRecovery audioRecovery;
    private OwnerTaskAudio ownerAudio;
    private RunControl lifecycleControl;private String audioTask,audioOwner,lifecycleRun;private int lifecycleEpoch=-1;
    private volatile JSONObject ownerHandoff;
    private dev.pocketengine.phone.setup.AndroidAppCopies appCopies;
    private dev.pocketengine.phone.diagnostics.PhonePracticeTarget practiceTarget;
    private dev.pocketengine.phone.access.PhoneAppResources appResources;private String resourceOwner;
    public boolean ownerHandoffActive(){JSONObject value=ownerHandoff;return value!=null&&value.optString("state").matches("main|unknown");}
    public JSONObject ownerHandoffState()throws Exception{return ownerHandoff==null?null:new JSONObject(ownerHandoff.toString());}
    public BackgroundPhonePort(DisplayModule module)throws Exception {
        this(module,null);
    }
    /** The native host selects a run before construction; there is no last-created display fallback. */
    public BackgroundPhonePort(DisplayModule module,RunControl original)throws Exception {
        this.module=module;this.runOwner=original;
        JSONObject session=original==null?module.session():module.session(original);
        sessionId=session.optString("sessionId");displayId=session.optInt("displayId",-1);
        if(!module.hasSession(sessionId,displayId)||sessionId.isEmpty()||displayId<=0)throw new IllegalStateException("请先连接后台助手并创建独立屏幕");
        ensureGeometryWatch(session);
    }
    @Override public boolean isConnected(){try{return !closed&&module.hasSession(sessionId,displayId)&&sameSession(module.session(sessionId,displayId));}catch(Exception ignored){return false;}}
    /** Original port identity only. Reading it never captures a frame or replaces a session. */
    public JSONObject executionIdentity()throws Exception{
        JSONObject result=new JSONObject().put("sessionId",sessionId).put("displayId",displayId);
        if(runOwner!=null){var identity=runOwner.hostRunIdentity();result.put("taskId",identity.taskId()).put("runId",identity.runId()).put("coordinatorEpoch",identity.epoch());}
        return result;
    }
    /** Owner viewer only: independent of model observations, references, pause and input admission. */
    public JSONObject previewFrameForOwner(PhoneLivePreview.Request selected,RunControl original,NativePhoneEntry.Admission currentOwner)throws Exception {
        worker();
        if(currentOwner==null||selected==null||original==null||original!=runOwner
                ||!original.matchesHostRun(selected.taskId(),selected.runId(),selected.epoch())
                ||selected.sessionId()!=null&&(!sessionId.equals(selected.sessionId())||displayId!=selected.displayId()))
            throw new SecurityException("原观看目标已变化");
        NativePhoneEntry.Admission current=()->{
            currentOwner.check();
            if(closed||original.isCancelled()||ownerHandoffActive()
                    ||!module.hasSession(original)||!module.ownedBy(selected.taskId(),sessionId,displayId))
                throw new SecurityException("原独立屏已停止、交还或移屏");
        };
        if(!previewInFlight.compareAndSet(false,true))throw new IllegalStateException("原独立屏画面正在读取");
        try{return module.previewFrameForOwner(original,sessionId,displayId,selected.afterFrameGeneration(),current);}
        finally{previewInFlight.set(false);}
    }
    /** The native supervisor binds the retained original run, never model JSON. */
    public synchronized void bindLifecycle(android.content.Context context,String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||runOwner!=null&&runOwner!=original||!original.matchesHostRun(task,run,epoch)||!module.ownedBy(task,sessionId,displayId)||!sameSession(module.session(sessionId,displayId)))throw new SecurityException("original_audio_run_required");
        String owner=task+"/"+run+"/"+epoch;if(audioOwner!=null&&(!audioOwner.equals(owner)||lifecycleControl!=original))throw new SecurityException("original_audio_run_changed");
        if(context.getApplicationContext() instanceof dev.pocketengine.EngineApplication app){appResources=app.resources.phoneApps();resourceOwner=owner;}
        lifecycleContext=context.getApplicationContext();audioRecovery=dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.get(lifecycleContext);audioTask=task;audioOwner=owner;lifecycleControl=original;lifecycleRun=run;lifecycleEpoch=epoch;
        if(ownerAudio==null)ownerAudio=new OwnerTaskAudio(owner,displayId,audioRecovery);
    }
    /** Cleanup reconciles only this run's durable audio records. It cannot write system modes. */
    public synchronized JSONObject restoreTaskAudio()throws Exception {
        if(ownerAudio!=null)ownerAudio.invalidate();
        if(audioRecovery==null)return new JSONObject().put("pending",0).put("status","audio_not_configured");
        return dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withObservationCommands((command,payload)->module.audioForTask(command,payload,audioTask,sessionId,displayId,null,-1,null),()->audioRecovery.restoreOwner(audioOwner));
    }
    private void beforeOpenAudio(String pkg,RunControl control,long revision)throws Exception {beforeOpenAudio(pkg,0,control,revision);}
    private void beforeOpenAudio(String pkg,int user,RunControl control,long revision)throws Exception {
        // Manual owner actions must not create another automatic mute.
        if(isOwner(control)||lifecycleContext==null)return;
        if(control!=lifecycleControl||audioRecovery==null)throw new SecurityException("original_audio_controller_required");
        var target=new dev.pocketengine.phone.access.AppCopyKey(pkg,user);
        dev.pocketengine.phone.observation.NativePhoneEntry.Admission cleanup=()->{guard(control,revision);if(control!=lifecycleControl||!module.ownedBy(audioTask,sessionId,displayId))throw new SecurityException("original_audio_controller_required");};
        JSONObject restored=dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withObservationCommands((command,payload)->module.audioForTask(command,payload,audioTask,sessionId,displayId,control,revision,cleanup),()->audioRecovery.restoreOwnerExcept(audioOwner,target));
        if(restored.optLong("pending")!=0)throw new PhoneFailure("audio_policy_unconfirmed","上一目标可能仍静音，自动恢复不能安全覆盖当前声音；未继续切换 App，请明确处理原声音记录。");
        if(!dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(lifecycleContext)||ownerAudio!=null&&!ownerAudio.automaticMuteAllowed(target))return;
        dev.pocketengine.phone.observation.NativePhoneEntry.Admission admitted=()->{cleanup.check();if(!dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(lifecycleContext)||ownerAudio!=null&&!ownerAudio.automaticMuteAllowed(target))throw new SecurityException("owner_audio_policy_revoked");};
        // This retained legacy launch lane is the helper's verified shell-user-0 context;
        // it does not accept a model-provided profile or claim clone/profile support.
        JSONObject receipt=dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withCommands((command,payload)->module.audioForTask(command,payload,audioTask,sessionId,displayId,control,revision,admitted),()->audioRecovery.silence(audioOwner,target,true));
        if(!"silenced_verified".equals(receipt.optString("outcome")))throw new PhoneFailure("audio_policy_unconfirmed","后台静音尚未证实；没有继续打开目标App，请核对声音与恢复记录。");
    }
    /** Native UI only: one current App from helper-owned task identities, with no model-supplied target. */
    public synchronized JSONObject reviewAudioForOwner(RunControl original,NativePhoneEntry.Admission current)throws Exception {
        return ownerAudioForOwner(null,false,original,current);
    }
    public synchronized JSONObject setAudioForOwner(String reviewId,boolean muted,RunControl original,NativePhoneEntry.Admission current)throws Exception {
        if(reviewId==null||reviewId.isBlank())throw new SecurityException("original_audio_review_required");
        return ownerAudioForOwner(reviewId,muted,original,current);
    }
    private JSONObject ownerAudioForOwner(String reviewId,boolean muted,RunControl original,NativePhoneEntry.Admission current)throws Exception {
        worker();if(current==null||original==null||original!=lifecycleControl||ownerAudio==null)throw new SecurityException("original_audio_controller_required");
        long revision=original.pauseRevision();boolean paused=original.isPaused();
        OwnerTaskAudio.Current admitted=new OwnerTaskAudio.Current(){
            public void check()throws Exception {
                current.check();original.check();
                if(original!=lifecycleControl||!original.matchesHostRun(audioTask,lifecycleRun,lifecycleEpoch)||original.pauseRevision()!=revision||original.isPaused()!=paused||ownerHandoffActive())
                    throw new SecurityException("原任务音频身份、暂停或接管状态已变化，请重新核对");
            }
            public JSONObject ownership()throws Exception {
                check();JSONObject response=module.ownerAudioForTask("ownership",new JSONObject(),audioTask,lifecycleRun,lifecycleEpoch,sessionId,displayId,original,revision,paused,this::check);
                if(!response.optBoolean("ok"))throw new SecurityException("原后台 App 归属暂不可读取");
                check();return response.getJSONObject("ownership");
            }
        };
        admitted.check();
        return dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withCommands((command,payload)->{
            admitted.check();
            if("audio.set".equals(command))ownerAudio.requireDispatchTarget(payload,admitted.ownership());
            return module.ownerAudioForTask(command,payload,audioTask,lifecycleRun,lifecycleEpoch,sessionId,displayId,original,revision,paused,admitted::check);
        },()->reviewId==null?ownerAudio.review(revision,paused,admitted):ownerAudio.confirm(reviewId,muted,revision,paused,admitted));
    }
    /** Native metadata is read on the original session; window content is not used for identity. */
    public dev.pocketengine.phone.access.PhoneOwnerPackages ownerPackages(RunControl control)throws Exception {
        long revision=control.pauseRevision();guard(control,revision);
        PhoneAccessibilityService service=PhoneAccessibilityService.get();
        var unknown=new dev.pocketengine.phone.access.PhoneOwnerPackages(java.util.Set.of(),false);
        JSONObject before=ownerPackageFacts(control,revision);if(before==null)return unknown;
        var observed=dev.pocketengine.phone.access.PhoneWindowPackages.readOwnedDisplay(service,displayId,before,()->guard(control,revision));
        if(observed.known()&&observed.owners().isEmpty()){
            // Empty access is a live bootstrap read, never a cached permission for a later app.
            JSONObject after=ownerPackageFacts(control,revision);
            if(!dev.pocketengine.phone.access.PhoneOwnerPackages.provesEmptyOwnedDisplay(after,displayId))return unknown;
        }
        guard(control,revision);
        if(service==null||service!=PhoneAccessibilityService.get()||service.observationSafetyFailure()!=null)return unknown;
        return observed;
    }
    private JSONObject ownerPackageFacts(RunControl control,long revision)throws Exception {
        JSONObject reply=request("ownership",new JSONObject(),control,revision);guard(control,revision);
        Object selected=reply.opt("displayId");JSONObject facts=reply.optJSONObject("ownership");
        JSONArray primary=facts==null?null:facts.optJSONArray("primary");
        if(!Boolean.TRUE.equals(reply.opt("ok"))||!sameSession(reply)||!(selected instanceof Number)
                ||((Number)selected).doubleValue()!=displayId||primary==null||primary.length()==0)return null;
        return facts;
    }
    private boolean appFree(String pkg,int user,RunControl control,long revision)throws Exception {
        JSONObject facts=landing(control,revision),topology=facts.optJSONObject("taskTopology");
        if(topology==null||!"observed".equals(topology.optString("status"))||!topology.optBoolean("complete"))throw new SecurityException("app_ownership_unsupported");
        JSONArray tasks=topology.getJSONArray("tasks");for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);
            if(pkg.equals(task.getString("packageName"))&&user==task.getInt("androidUserId")&&task.getInt("displayId")!=displayId)return false;}
        return true;
    }
    private void reserveApp(String pkg,int user,RunControl control)throws Exception {
        long revision=control.pauseRevision();guard(control,revision);
        if(appResources==null){if(!appFree(pkg,user,control,revision))throw new PhoneFailure("app_user_busy","同一App资料正在其他屏幕使用，没有抢占。");return;}
        var key=new dev.pocketengine.phone.access.AppCopyKey(pkg,user);
        var reserved=appResources.await(resourceOwner,key,15_000,control,(actual,owner)->{guard(control,revision);return appFree(actual.packageName(),actual.userId(),control,revision);});
        guard(control,revision);
        if(reserved==dev.pocketengine.phone.access.PhoneAppResources.Result.DEADLOCK)throw new PhoneFailure("app_resource_deadlock","App资料等待形成环路，已退出等待；没有抢占。");
        if(reserved!=dev.pocketengine.phone.access.PhoneAppResources.Result.ACQUIRED)throw new PhoneFailure("app_resource_busy","同一App资料仍被占用，有界等待已结束；未派发。");
    }
    public synchronized JSONObject dispatchSemantic(JSONObject exact,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        worker();long revision=control.pauseRevision();refreshSession(control,revision);JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;
        JSONObject target=exact.getJSONObject("target");String pkg=target.getString("packageName");int user=exact.getInt("userId");
        reserveApp(pkg,user,control);JSONObject before=beforeLanding(control,revision);beforeOpenAudio(pkg,user,control,revision);guard(control,revision);clearReferences();
        JSONObject result=module.requestAdmitted("intent",exact,control,revision,sessionId,displayId,admission);
        return verifyExactLanding(result,before,pkg,user,target.optString("component"),control,revision);
    }
    private JSONObject verifyExactLanding(JSONObject result,JSONObject before,String pkg,int user,String component,RunControl control,long revision)throws Exception {
        if(!(Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")))return result;
        result=verifyLanding(result,before,control,revision);JSONObject landing=result.optJSONObject("displayLanding"),after=landing==null?null:landing.optJSONObject("after");
        boolean exact=false;JSONArray rows=after==null?null:after.optJSONArray("owned");JSONObject topology=after==null?null:after.optJSONObject("taskTopology");
        JSONArray tasks=topology==null?null:topology.optJSONArray("tasks");
        if(landing!=null&&"owned_display_observed".equals(landing.optString("status"))&&rows!=null&&tasks!=null){
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!pkg.equals(row.optString("packageName"))||row.optInt("userId",-1)!=user)continue;
                for(int j=0;j<tasks.length();j++){JSONObject task=tasks.getJSONObject(j);if(task.getInt("taskId")==row.getInt("taskId")&&task.getInt("displayId")==displayId&&task.getInt("androidUserId")==user&&(component.isEmpty()||component.equals(task.getString("component"))))exact=true;}}
        }
        result.put("actualUserVerified",exact).put("targetLandingVerified",exact).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
        if(!exact){control.pause();clearReferences();result.put("requiresUser",true).put("requiresReconciliation",true).put("targetLanding","unknown_requires_owner");}
        return result;
    }
    @Override public synchronized JSONObject extension(String tool,JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        worker();dev.pocketengine.phone.observation.PhoneExtensionTools.validate(tool,args);control.check();long revision=control.pauseRevision();refreshSession(control,revision);
        PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service==null)return failure("service_disconnected","原无障碍服务已断开。");
        if(appCopies==null)appCopies=new dev.pocketengine.phone.setup.AndroidAppCopies(module.hostContext(),displayId);
        if("phone_list_app_copies".equals(tool))return appCopies.list(control);
        if(admission==null)throw new SecurityException("original_prepared_admission_required");
        JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;
        JSONObject before=beforeLanding(control,revision),result;
        if("phone_launch_app_copy".equals(tool)){
            JSONObject exact=appCopies.require(args,control);String pkg=exact.getString("packageName");int user=exact.getInt("userId");
            reserveApp(pkg,user,control);beforeOpenAudio(pkg,user,control,revision);clearReferences();
            result=module.requestAdmitted("launch_copy",exact,control,revision,sessionId,displayId,()->{admission.check();appCopies.require(args,control);});
            return verifyExactLanding(result,before,pkg,user,exact.optString("component"),control,revision);
        }
        if("phone_practice_target".equals(tool)){
            if(!module.hostContext().getPackageName().equals(args.getString("packageName"))||android.os.Process.myUid()/100000!=args.getInt("userId"))throw new SecurityException("practice_target_is_fixed");
            if(practiceTarget==null)practiceTarget=new dev.pocketengine.phone.diagnostics.PhonePracticeTarget(module.hostContext(),displayId,sessionId);
            clearReferences();result=practiceTarget.launch(control,()->{guard(control,revision);admission.check();});
            if(Boolean.TRUE.equals(result.opt("accepted")))result.put("helperAuthenticated",module.isConnected()).put("imeIsolation",module.session(sessionId,displayId).optString("imeIsolation"));
        }else{
            Observation expected=current;if(expected==null||expected.control!=control||!expected.id.equals(args.optString("observationId"))||expected.revision!=revision)return failure("stale_observation","扩展动作需要当前副屏观察。");
            if("phone_paste_text".equals(tool)){
                AccessibilityForest.Ref ref=expected.tree.forest.refs.get(args.getString("nodeId"));if(ref==null)return failure("stale_node","原输入目标已变化。");
                result=dev.pocketengine.phone.observation.AccessibilityPasteInput.paste(service,service,displayId,ref,args.getString("text"),control,revision,()->{requireObservationGeometry(expected);admission.check();},()->{
                    Tree fresh=validateTarget(expected,ref,control);NativeSensitiveStep.requireNode(fresh.forest,ref,"phone_type",control,revision);
                    AccessibilityNodeInfo node=AccessibilityForest.resolve(service,displayId,ref);
                    try{dev.pocketengine.phone.setup.ClipboardProfileScope.require(ownerPackages(control),node==null?"":String.valueOf(node.getPackageName()),module.hostContext().getApplicationInfo().uid/100000);}
                    finally{if(node!=null)node.recycle();}
                },this::clearReferences,this::noteNativeInput);
            }else if("phone_gesture".equals(tool)){
                if(!expected.imageIncluded)return failure("coordinate_unverifiable","多指需要当前真实图片。");
                JSONArray paths=args.getJSONArray("paths");for(int i=0;i<paths.length();i++){JSONArray path=paths.getJSONArray(i);for(int j=1;j<path.length();j++){JSONArray from=path.getJSONArray(j-1),to=path.getJSONArray(j);checkSensitiveCoordinate(expected,new JSONObject().put("x1",from.getInt(0)).put("y1",from.getInt(1)).put("x2",to.getInt(0)).put("y2",to.getInt(1)),true,control);}}
                JSONObject payload=inputIdentity(new JSONObject(args.toString()),expected);payload.remove("observationId");guard(control,revision);clearReferences();result=module.requestAdmitted("gesture",payload,control,revision,sessionId,displayId,admission);
            }else return failure("native_extension_unavailable","当前副屏不支持该扩展，没有切换执行端。");
        }
        return Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")?verifyLanding(result,before,control,revision):result;
    }
    @Override public void clearReferences(){current=null;}
    /** Record the actual accessibility input boundary, never the time an observation is read. */
    private void noteNativeInput(){long entered=System.nanoTime();nativeFrameAfterNanos.accumulateAndGet(entered,Math::max);clearReferences();}
    @Override public synchronized JSONObject tapOcrText(dev.pocketengine.phone.observation.OcrTextTarget target,RunControl control,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        worker();JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;Observation expected=current;
        if(expected==null||!target.matches(control,expected.id,expected.revision)||!expected.imageIncluded
                ||target.sourceWidth!=expected.width||target.sourceHeight!=expected.height)
            return failure("stale_observation","OCR目标不属于当前完整后台画面，请重新观察。");
        try {
            refreshSession(control,expected.revision);ime=imeUnavailable(control);if(ime!=null)return ime;
            if(!isOwner(control))target.requireNonSensitive(control,expected.revision);JSONObject point=target.tapArguments(expected.width,expected.height);
            checkSensitiveCoordinate(expected,point,false,control);
            JSONObject args=inputIdentity(new JSONObject().put("x",point.getInt("x")).put("y",point.getInt("y")),expected);
            guard(control,expected.revision);clearReferences();
            JSONObject before=beforeLanding(control,expected.revision);JSONObject result=module.requestOcrTap(args,control,expected.revision,sessionId,displayId,admission);return Boolean.TRUE.equals(result.opt("accepted"))?verifyLanding(result,before,control,expected.revision):result;
        }catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(dev.pocketengine.phone.observation.NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(IllegalArgumentException invalid){clearReferences();return failure("coordinate_unverifiable",invalid.getMessage());}
    }
    public void renewTaskLease(String taskId,String runId,int epoch,RunControl control,long taskDeadline)throws Exception {module.renewTaskLease(taskId,runId,epoch,control,sessionId,displayId,taskDeadline);}
    public void noteReleasePending(){module.noteReleasePending(sessionId,displayId);}
    /** An inline preview can only read the lifecycle-bound original port; this creates no new observation. */
    public synchronized void requireOwnerObservationRun(String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||original!=lifecycleControl||!task.equals(audioTask)||!run.equals(lifecycleRun)||epoch!=lifecycleEpoch
                ||!original.matchesHostRun(task,run,epoch)||original.isCancelled()||!module.ownedBy(task,sessionId,displayId)||!isConnected())
            throw new SecurityException("original_phone_preview_run_changed");
    }
    private JSONObject ownerObservationIdentity(String task,JSONObject observation)throws Exception {
        observation.put("taskId",task);
        if(lifecycleControl!=null&&task.equals(audioTask)&&lifecycleControl.matchesHostRun(task,lifecycleRun,lifecycleEpoch))
            observation.put("runId",lifecycleRun).put("coordinatorEpoch",lifecycleEpoch);
        return observation;
    }
    /** Read-only view while the agent runs: no new frame, reference or model observation is made. */
    public synchronized JSONObject cachedObservationForOwner(String taskId)throws Exception {
        if(!module.ownedBy(taskId,sessionId,displayId))throw new SecurityException("副屏不属于当前任务");
        if(ownerHandoffActive())return handoffObservation(taskId);
        if(cached==null)return ownerObservationIdentity(taskId,new JSONObject().put("ok",false).put("error","no_observation")
                .put("message","任务尚未取得真实副屏画面").put("manualAllowed",false).put("referencesExpired",true));
        return ownerObservationIdentity(taskId,new JSONObject(cached.toString()).put("manualAllowed",false).put("referencesExpired",true));
    }
    /** The native supervisor validates and journals the current run before using these owner entry points. */
    public synchronized JSONObject observeForOwner(String taskId,RunControl control)throws Exception {
        beginOwner(taskId,control);
        try{if(ownerHandoffActive())return handoffObservation(taskId);JSONObject observed=observe(true,control);if(ownerHandoff!=null)observed.put("ownerHandoff",ownerHandoffState());return ownerObservationIdentity(taskId,observed.put("manualAllowed",observed.optBoolean("ok")).put("referencesExpired",!observed.optBoolean("ok")));}
        finally{ownerPermit=null;}
    }
    /** Called only by the native owner inspector after original-run admission/journaling. */
    public synchronized JSONObject ownerHandoff(String taskId,String action,String handoffId,RunControl control)throws Exception {
        if(!action.matches("prepare|status|main|return"))throw new SecurityException("未知接管操作");
        JSONObject before=ownerHandoffState();boolean requestEntered=false;
        try{
            beginOwner(taskId,control);clearReferences();
            if(action.equals("main")||action.equals("return")){if(ownerHandoff==null||!handoffId.equals(ownerHandoff.optString("handoffId")))throw new SecurityException("先核对原接管计划");ownerHandoff=new JSONObject(ownerHandoff.toString()).put("state","unknown");}
            requestEntered=true;JSONObject result=module.request("owner_handoff_"+action,new JSONObject().put("handoffId",handoffId).put("sessionId",sessionId).put("displayId",displayId),control,ownerPermit.revision,true);
            if(result.has("state"))ownerHandoff=new JSONObject(result.toString());
            if(result.optString("state").equals("returned"))landingBaseline=null;
            return result;
        }catch(Exception failure){
            if(action.matches("main|return")&&(!requestEntered||failure instanceof ShellBridgeClient.StaleRequestException||failure instanceof ShellBridgeClient.NotDispatchedException)){ownerHandoff=before;return new JSONObject().put("ok",false).put("accepted",false).put("notDispatched",true).put("code","owner_handoff_not_dispatched").put("message","原移屏请求在派发前被拒绝，未移动应用；请重新核对当前状态");}
            throw failure;
        }finally{ownerPermit=null;}
    }
    public synchronized JSONObject manualActionForOwner(String taskId,String tool,JSONObject args,RunControl control)throws Exception {
        if(tool==null||!tool.matches("phone_(launch_app|click_node|tap|type|swipe|back)"))throw new SecurityException("不支持的副屏人工操作");
        if(args==null||args.optInt("displayId",-1)!=displayId||!sessionId.equals(args.optString("sessionId")))throw new SecurityException("人工操作的副屏身份已变化");
        beginOwner(taskId,control);
        try{return execute(tool,args,control);}
        finally{ownerPermit=null;}
    }
    /** The caller journals only redactedArgs; the actual value must never enter an event or model message. */
    public synchronized JSONObject manualSecretInputForOwner(String taskId,JSONObject args,String text,RunControl control)throws Exception {
        if(text==null||text.length()>12000)throw new IllegalArgumentException("私密输入无效");
        if(args==null||args.optString("nodeId").isEmpty()||args.optInt("displayId",-1)!=displayId||!sessionId.equals(args.optString("sessionId")))
            throw new SecurityException("请指定当前副屏的输入节点");
        beginOwner(taskId,control);
        try{
            worker();JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;Observation observation=current;
            if(observation==null||observation.control!=control||!observation.id.equals(args.optString("observationId"))
                    ||observation.revision!=control.pauseRevision())return failure("stale_observation","输入节点引用已失效，请重新观察");
            refreshSession(control,observation.revision);ime=imeUnavailable(control);if(ime!=null)return ime;
            JSONObject before=beforeLanding(control,observation.revision);JSONObject result=nodeAction("phone_type",args,observation,control,text);return Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")?verifyLanding(result,before,control,observation.revision):result;
        }catch(InterruptedException cancelled){throw cancelled;}
        catch(PhoneFailure blocked){clearReferences();JSONObject out=failure(blocked);if(ObservationSafety.unknownEffect(blocked.code))out.put("accepted",JSONObject.NULL);return out;}
        catch(Exception unknown){clearReferences();return failure("action_unknown","私密输入结果尚未确认，请由机主核对后继续。").put("accepted",JSONObject.NULL);}
        finally{ownerPermit=null;}
    }
    private JSONObject handoffObservation(String taskId)throws Exception{return ownerObservationIdentity(taskId,new JSONObject().put("ok",false).put("sessionId",sessionId).put("displayId",displayId).put("manualAllowed",false).put("referencesExpired",true).put("ownerHandoff",ownerHandoffState()).put("message","原应用已交由主人或位置待核对；没有读取主屏画面。请返回本页核对并交还原副屏。"));}
    private void beginOwner(String taskId,RunControl control)throws Exception {
        if(control==null||!module.ownedBy(taskId,sessionId,displayId))throw new SecurityException("副屏不属于当前任务");
        control.check();if(!control.isPaused())throw new SecurityException("先暂停并接管任务后才能操作副屏");
        if(!isConnected())throw new SecurityException("后台会话已变更，请先由机主核对");
        ownerPermit=new OwnerPermit(taskId,control,control.pauseRevision());
    }
    public JSONObject closeSession(){closed=true;clearReferences();stopGeometryWatch();JSONObject audio;try{audio=restoreTaskAudio();}catch(Exception pending){audio=new JSONObject();try{audio.put("status","audio_recovery_pending").put("pending",true);}catch(Exception ignored){}}
        if(practiceTarget!=null)practiceTarget.close();if(appCopies!=null)appCopies.close();
        try{JSONObject released=module.releaseSession(sessionId,displayId).put("audioRestore",audio);if(released.optBoolean("releaseConfirmed")&&appResources!=null&&resourceOwner!=null)appResources.releaseOwner(resourceOwner);return released;}catch(Exception unknown){try{return new JSONObject().put("ok",false).put("releaseConfirmed",false).put("audioRestore",audio).put("error","cleanup_unknown").put("message","后台屏释放未确认，请重新连接核对。");}catch(Exception impossible){return new JSONObject();}}}
    @Override public dev.pocketengine.phone.PhoneUiSignals uiSignals(RunControl control)throws Exception {worker();guard(control,control.pauseRevision());PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service==null)return null;return service.uiSignals(displayId);}
    @Override public synchronized JSONObject observe(boolean includeImage,RunControl control)throws Exception {
        worker();control.check();
        if(ownerHandoffActive()){control.pause();clearReferences();return failure("owner_handoff_active","先由主人交还原应用，再观察原副屏");}
        if(!isOwner(control)){
            try{landingBaseline=beforeLanding(control,control.pauseRevision());}
            catch(InterruptedException cancelled){throw cancelled;}
            catch(PhoneFailure unknown){return failure(unknown);}
            catch(Exception unknown){control.pause();clearReferences();return failure("display_ownership_unknown","后台窗口归属暂不可核对，已暂停原任务。");}
        }
        // Never wait for resume while holding this port's monitor: the paused owner
        // needs that same monitor to inspect and interact with the retained display.
        if(control.isPaused()&&!isOwner(control))return failure("task_paused","任务已暂停，旧观察未派发。");
        current=null;lastIncludeImage=includeImage;
        try{
            long revision=control.pauseRevision();refreshSession(control,revision);
            JSONObject profileBefore=isOwner(control)?landing(control,revision):landingBaseline;
            Tree before=onTreeRead(control,revision,()->tree());
            if(!includeImage&&!module.session(sessionId,displayId).optBoolean("frameMetadataOnly"))
                return failure("metadata_only_unavailable","当前后台助手不支持无图元数据观察，请更新助手；未编码图片，也不会切换主屏。");
            JSONObject frame=request("frame",new JSONObject().put("includeImage",includeImage),control,revision);
            if(!frame.optBoolean("ok")){
                if(!"frame_unavailable".equals(frame.optString("error")))return ObservationReport.annotateFailure(frame);
                // A newly created display may have no frame until an app is launched. This is
                // an actual empty-display observation, not a screenshot or a fabricated page.
                Tree empty=onTreeRead(control,revision,()->tree());JSONObject session=module.session(sessionId,displayId);
                requireTreeGeometry(before,session);requireTreeGeometry(empty,session);
                guard(control,revision);Observation observed=new Observation(empty,-1,revision,session.optInt("width",720),session.optInt("height",1280),control,false);
                checkGeometry(observed.geometry,observed.width,observed.height,observed.displayRevision);current=observed;
                JSONObject emptyFrame=new JSONObject().put("ok",true).put("observationId",observed.id).put("displayId",displayId).put("sessionId",sessionId)
                        .put("executionMode","background").put("observedAt",System.currentTimeMillis()).put("screenWidth",observed.width).put("screenHeight",observed.height)
                        .put("packageName",empty.packageName).put("nodes",empty.nodes).put("imageUnavailable",true).put("imageUnavailableReason","frame_unavailable")
                        .put("changedDuringRead",!before.signature.equals(empty.signature)).put("coordinateActionsAvailable",false)
                        .put("screenState","display_has_no_frame").put("truncated",empty.truncated)
                        .put("message","独立显示已建立，尚未产生画面。请先列出并打开应用；目前没有可用于坐标操作的图片。");
                emptyFrame.put("observedAtUptimeMs",observed.at).put("pauseRevision",revision);
                if(observed.geometry!=null)observed.geometry.writeTo(emptyFrame,true);
                empty.forest.attach(emptyFrame);
                ObservationReport.attach(emptyFrame,empty.available,empty.available?"accessibility_tree":"no_accessible_root",false,"display_has_no_frame");
                attachObservedProfile(emptyFrame,profileBefore,landing(control,revision),displayId);
                cached=new JSONObject(emptyFrame.toString());return emptyFrame;
            }
            if(frame.optInt("displayId",-1)!=displayId||!sessionId.equals(frame.optString("sessionId")))return failure("wrong_display","后台画面身份不匹配。");
            long frameTimestamp=frame.optLong("frameTimestampNanos",0);
            boolean sourceFrameFresh=frame.optBoolean("frameTimestampSupported")&&frameTimestamp>nativeFrameAfterNanos.get()&&frameTimestamp<=System.nanoTime();
            boolean framePending=frame.optBoolean("framePending")||!sourceFrameFresh;
            boolean imageIncluded=includeImage&&frame.optBoolean("imageIncluded")&&!framePending&&!frame.optBoolean("empty");
            Tree after=onTreeRead(control,revision,()->tree());
            boolean changedDuringRead=!before.signature.equals(after.signature);
            if(imageIncluded&&!before.forest.sameWindowLayout(after.forest))return failure("screen_changed","后台窗口在截图期间变化，请重新观察。");
            JSONObject state=module.session(sessionId,displayId);
            requireTreeGeometry(before,frame);requireTreeGeometry(after,frame);requireTreeGeometry(after,state);
            if(frame.optInt("width")<=0||frame.optInt("height")<=0||frame.optLong("frameGeneration",-1)<0
                    ||frame.optInt("width")!=state.optInt("width")||frame.optInt("height")!=state.optInt("height"))return failure("invalid_frame","后台画面尺寸或代次无效。");
            guard(control,revision);
            boolean coordinatesAvailable=imageIncluded&&!changedDuringRead;
            Observation observed=new Observation(after,frame.optLong("frameGeneration",-1),revision,frame.getInt("width"),frame.getInt("height"),control,coordinatesAvailable);
            JSONObject out=new JSONObject().put("ok",true).put("observationId",observed.id).put("displayId",displayId).put("sessionId",sessionId)
                    .put("executionMode","background").put("observedAt",System.currentTimeMillis()).put("observedAtUptimeMs",observed.at).put("pauseRevision",revision).put("capturedAtUptimeMs",frame.optLong("capturedAt"))
                    .put("screenWidth",observed.width).put("screenHeight",observed.height)
                    .put("packageName",after.packageName.isEmpty()?frame.optString("packageName"):after.packageName)
                    .put("requestedPackageName",frame.optString("requestedPackageName")).put("frameGeneration",observed.frame)
                    .put("frameMetadataOnly",!imageIncluded).put("framePending",framePending).put("frameEmpty",frame.optBoolean("empty")).put("nodes",after.nodes).put("coordinateSpace","0..1000").put("truncated",after.truncated)
                    .put("frameTimestampNanos",frameTimestamp).put("frameTimestampSupported",frame.optBoolean("frameTimestampSupported")).put("sourceFrameFresh",sourceFrameFresh&&!framePending)
                    .put("changedDuringRead",changedDuringRead).put("coordinateActionsAvailable",coordinatesAvailable);
            if(observed.geometry!=null)observed.geometry.writeTo(out,true);
            if(frame.optBoolean("empty"))out.put("screenState","display_has_no_frame").put("imageUnavailable",true);
            String imageReason=imageIncluded?"display_surface_frame":includeImage?(framePending?"frame_pending":"frame_unavailable"):"not_requested_frame_generation_only";
            if(includeImage&&!imageIncluded)out.put("imageUnavailable",true).put("imageUnavailableReason",imageReason);
            module.attachInstalledAppEvidence(out);
            after.forest.attach(out);
            if(imageIncluded)out.put("imageBase64",frame.getString("imageBase64")).put("imageMimeType",frame.optString("imageMimeType","image/jpeg"))
                    .put("imageWidth",observed.width).put("imageHeight",observed.height);
            if(imageIncluded&&!ObservationReport.hasImage(out))return failure("invalid_frame","后台助手没有返回有效的图片字节。");
            ObservationReport.attach(out,after.available,after.available?"accessibility_tree":"no_accessible_root",imageIncluded,imageReason);
            attachObservedProfile(out,profileBefore,landing(control,revision),displayId);
            checkGeometry(observed.geometry,observed.width,observed.height,observed.displayRevision);
            current=observed;cached=new JSONObject(out.toString());return out;
        }catch(InterruptedException cancelled){throw cancelled;}
        catch(ShellBridgeClient.StaleRequestException stale){clearReferences();return failure("stale_observation","任务曾暂停，旧后台观察未派发，请重新观察。");}
        catch(PhoneFailure blocked){clearReferences();JSONObject out=failure(blocked);if(ObservationSafety.unknownEffect(blocked.code))out.put("accepted",JSONObject.NULL);return out;}
        catch(SecurityException denied){clearReferences();return failure("permission_denied","后台或无障碍权限已失效，请恢复后继续。");}
        catch(Exception error){clearReferences();return failure("background_unavailable","后台观察不可用，已停止；不会回落主屏。");}
    }
    @Override public synchronized JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception {
        if(!java.util.Set.of("phone_launch_app","phone_click_node","phone_type","phone_tap","phone_swipe","phone_back").contains(tool))return executeNative(tool,args,control);
        long revision=control.pauseRevision();JSONObject before;
        JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;
        try{before=beforeLanding(control,revision);}catch(InterruptedException cancelled){throw cancelled;}
        catch(PhoneFailure unavailable){return failure(unavailable);}
        catch(Exception unavailable){control.pause();clearReferences();return failure("display_ownership_unknown","无法核对原副屏与主屏任务归属，原任务已暂停，未派发动作。");}
        JSONObject receipt=executeNative(tool,args,control);
        if(Boolean.TRUE.equals(receipt.opt("accepted"))||receipt.optBoolean("ok")||receipt.has("accepted")&&receipt.isNull("accepted"))return verifyLanding(receipt,before,control,revision);
        return receipt;
    }
    private JSONObject executeNative(String tool,JSONObject args,RunControl control)throws Exception {
        worker();control.check();
        if(control.isPaused()&&!isOwner(control))return failure("task_paused","任务已暂停，旧动作未派发。");
        try{
            refreshSession(control,control.pauseRevision());if(!tool.equals("phone_observe")&&!tool.equals("phone_list_apps")){JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;}if(args==null)args=new JSONObject();
            if("phone_observe".equals(tool))return observe(lastIncludeImage,control);
            if("phone_list_apps".equals(tool))return request("apps",new JSONObject(),control,control.pauseRevision());
            if("phone_launch_app".equals(tool)){
                String pkg=args.optString("packageName");if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))return failure("invalid_arguments","包名无效。");
                long revision=control.pauseRevision();guard(control,revision);reserveApp(pkg,0,control);beforeOpenAudio(pkg,control,revision);clearReferences();
                return request("launch",new JSONObject().put("packageName",pkg),control,revision);
            }
            Observation observation=current;
            if(observation==null||observation.control!=control||!observation.id.equals(args.optString("observationId"))
                    ||observation.revision!=control.pauseRevision())return failure("stale_observation","后台页面引用已过期，请重新观察。");
            guard(control,observation.revision);
            if("phone_home".equals(tool))return failure("unsupported_tool","后台模式没有主屏桌面；请直接打开指定应用。");
            if("phone_click_node".equals(tool)||"phone_type".equals(tool))return nodeAction(tool,args,observation,control);
            JSONObject command=inputIdentity(new JSONObject(),observation);
            if("phone_back".equals(tool)){
                clearReferences();return request("key",command.put("keyCode",android.view.KeyEvent.KEYCODE_BACK),control,observation.revision);
            }
            boolean swipe="phone_swipe".equals(tool);
            if((swipe||"phone_tap".equals(tool))&&!observation.imageIncluded)return failure("coordinate_unverifiable","坐标操作需要已提供真实图片的最新观察；未提交旧坐标。");
            if(!swipe&&!"phone_tap".equals(tool))return failure("unsupported_tool","该后台工具尚不支持。");
            for(String key:swipe?new String[]{"x1","y1","x2","y2"}:new String[]{"x","y"})command.put(key,integer(args,key,0,1000));
            if(swipe)command.put("durationMs",integer(args,"durationMs",100,2000));
            checkSensitiveCoordinate(observation,args,swipe,control);
            guard(control,observation.revision);clearReferences();return request(swipe?"swipe":"tap",command,control,observation.revision);
        }catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(InterruptedException cancelled){throw cancelled;}
        catch(ShellBridgeClient.StaleRequestException stale){clearReferences();return failure("stale_observation","任务曾暂停，旧后台动作未派发，请重新观察。");}
        catch(PhoneFailure blocked){clearReferences();JSONObject out=failure(blocked);if(ObservationSafety.unknownEffect(blocked.code)||"audio_policy_unconfirmed".equals(blocked.code))out.put("accepted",JSONObject.NULL);if("audio_policy_unconfirmed".equals(blocked.code))out.put("replayAllowed",false).put("launchDispatched",false);return out;}
        catch(IllegalArgumentException invalid){clearReferences();return failure("invalid_arguments","后台工具参数无效。");}
        catch(Exception error){clearReferences();return failure("action_unknown","后台操作通信中断，结果可能未知；请重新观察，勿直接重放。").put("accepted",JSONObject.NULL);}
    }
    private JSONObject imeUnavailable(RunControl control)throws Exception {
        if(module.session(sessionId,displayId).optString("imeIsolation").equals("available"))return null;control.pause();clearReferences();
        return failure("background_ime_unavailable","独立输入法隔离尚未证实；原任务已暂停，不会在主屏使用输入法回落。").put("requiresUser",true);
    }
    private JSONObject landing(RunControl control,long revision)throws Exception {
        JSONObject response=request("ownership",new JSONObject(),control,revision),facts=response.getJSONObject("ownership");
        if(facts.getJSONArray("primary").length()==0)throw new SecurityException("主屏归属不可核对");return facts;
    }
    private JSONObject beforeLanding(RunControl control,long revision)throws Exception {
        JSONObject now=landing(control,revision);String reason=DisplayLandingSafety.failure(landingBaseline,now,displayId);
        if(reason!=null){control.pause();clearReferences();throw new PhoneFailure("display_ownership_unknown","原副屏任务迁移、冲突或归属未知，已暂停；不会迁移或代点主屏应用。",
                new JSONObject().put("status","unknown_requires_owner").put("reason",reason).put("before",landingBaseline==null?JSONObject.NULL:landingBaseline)
                        .put("after",now).put("automaticReplayAllowed",false).put("taskMigrationPerformed",false));}
        return now;
    }
    private JSONObject verifyLanding(JSONObject receipt,JSONObject before,RunControl control,long revision)throws Exception {
        JSONObject evidence;
        try{
            // Bounded read-only settling, then retain the baseline for later observations too.
            Thread.sleep(350);JSONObject after=landing(control,revision);String reason=DisplayLandingSafety.failure(before,after,displayId);
            evidence=new JSONObject().put("before",before).put("after",after).put("status",reason==null?"owned_display_observed":"unknown_requires_owner")
                    .put("reason",reason==null?"owned_display_topology_confirmed":reason).put("taskMigrationPerformed",false).put("automaticReplayAllowed",false);
            if(reason!=null){control.pause();clearReferences();receipt.put("requiresUser",true).put("message","动作已请求，但跨应用落点或主屏归属不能确认；已暂停原任务，不会移动或代点主屏应用。");}
            else landingBaseline=new JSONObject(after.toString());
        }catch(InterruptedException cancelled){
            Thread.currentThread().interrupt();
            if(!Boolean.TRUE.equals(receipt.opt("accepted")))throw cancelled;
            clearReferences();receipt=NativeNodeActionReceipt.observationUnavailable(receipt,"post_action_task_probe_interrupted");
            evidence=new JSONObject().put("status","unavailable_after_dispatch").put("reason","post_action_task_probe_interrupted").put("automaticReplayAllowed",false);
        }catch(Exception unavailable){
            control.pause();clearReferences();evidence=new JSONObject().put("status","unknown_requires_owner").put("reason","post_action_task_probe_unavailable").put("automaticReplayAllowed",false);receipt.put("requiresUser",true);
            if(Boolean.TRUE.equals(receipt.opt("accepted")))receipt=NativeNodeActionReceipt.observationUnavailable(receipt,"post_action_task_probe_unavailable");
        }
        return receipt.put("displayLanding",evidence);
    }
    private JSONObject nodeAction(String tool,JSONObject args,Observation expected,RunControl control)throws Exception {
        return nodeAction(tool,args,expected,control,null);
    }
    @Override public synchronized JSONObject scrollNode(JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        worker();JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;dev.pocketengine.phone.observation.NativeTreeScrollPort.validate(args);if(admission==null)return failure("host_authority_required","后台节点滚动须原prepared操作。");control.check();
        try{Observation expected=current;
            if(control.isPaused()||expected==null||expected.control!=control||!expected.id.equals(args.getString("observationId"))||expected.revision!=control.pauseRevision())return failure("stale_observation","后台滚动需要此原执行当前树引用。");
            guard(control,expected.revision);JSONObject before=beforeLanding(control,expected.revision);JSONObject result=nodeAction("phone_scroll_node",args,expected,control,null,admission);return Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")?verifyLanding(result,before,control,expected.revision):result;
        }catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(PhoneFailure refused){clearReferences();JSONObject out=failure(refused);if(ObservationSafety.unknownEffect(refused.code))out.put("accepted",JSONObject.NULL);return out;}
        catch(InterruptedException stopped){clearReferences();throw stopped;}
        catch(Exception unknown){clearReferences();return failure("action_unknown","后台滚动结果未知，请核对；不会自动重试。").put("accepted",JSONObject.NULL);}
    }
    private JSONObject nodeAction(String tool,JSONObject args,Observation expected,RunControl control,String ownerSecret)throws Exception {
        return nodeAction(tool,args,expected,control,ownerSecret,null);
    }
    private JSONObject nodeAction(String tool,JSONObject args,Observation expected,RunControl control,String ownerSecret,NativePhoneEntry.Admission admission)throws Exception {
        boolean privateInput=ownerSecret!=null;String nodeId=args.optString("nodeId");String text=privateInput?ownerSecret:args.optString("text");
        if("phone_type".equals(tool)&&(!privateInput&&!(args.opt("text") instanceof String)||text.length()>12000))return failure("invalid_arguments","输入文字无效。");
        // Semantic targets are fenced by exact source/path/ancestor/window/occlusion below.
        // Pixel generations remain mandatory for coordinate actions, not for an unrelated ticker.
        JSONObject frame;
        try{
            if(!module.session(sessionId,displayId).optBoolean("frameMetadataOnly"))throw new PhoneFailure("metadata_only_unavailable","请更新后台助手后再核对节点操作。");
            frame=request("frame",new JSONObject().put("includeImage",false),control,expected.revision);
        }
        catch(InterruptedException stopped){throw stopped;}
        catch(ShellBridgeClient.StaleRequestException stale){throw stale;}
        catch(PhoneFailure blocked){throw blocked;}
        catch(Exception unavailable){throw new PhoneFailure("background_unavailable","无法核对后台当前画面，未提交节点操作。");}
        if(!frame.optBoolean("ok"))return ObservationReport.annotateFailure(frame);
        if(frame.optBoolean("empty")||frame.optInt("displayId",-1)!=displayId||!sessionId.equals(frame.optString("sessionId")))
            return failure("stale_observation","后台帧正在变化或身份已变化，未提交节点操作。");
        String changed=ObservationSafety.semanticFrameFailure(expected.frame,expected.width,expected.height,
                frame.optLong("frameGeneration",-1),frame.optInt("width",-1),frame.optInt("height",-1));
        if(changed!=null)return failure(changed,"后台画面或尺寸已变化，未提交节点操作，请重新观察。");
        requireGeometryIdentity(expected.geometry,expected.width,expected.height,frame);
        dev.pocketengine.phone.observation.DelayedInputReadback delayed=new dev.pocketengine.phone.observation.DelayedInputReadback();
        JSONObject base=new JSONObject().put("ok",true).put("accepted",true).put("verified",false).put("displayId",displayId).put("sessionId",sessionId)
                .put("message",privateInput?"主人私密输入请求已接收，请核对目标界面。":"后台节点请求已接收，请重新观察。");
        if("phone_type".equals(tool)){
            base.put("nativeTextReadback",privateInput?dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(null,null,true):dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("native_readback_unavailable_after_dispatch"));
            if(!privateInput)base.put("delayedTextReadback",dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("delayed_native_readback_unavailable"));
        }
        NativeNodeActionReceipt acknowledgement=new NativeNodeActionReceipt(base);
        try{
        JSONObject actionReceipt=onNodeWorker(control,expected.revision,()->{
            guard(control,expected.revision);
            if(current!=expected||expected.control!=control||expected.service!=PhoneAccessibilityService.get())return failure("stale_observation","后台观察属于另一次控制或无障碍会话。");
            AccessibilityForest.Ref ref=expected.tree.forest.refs.get(nodeId);
            if(nodeId.isEmpty()&&"phone_type".equals(tool))for(AccessibilityForest.Ref candidate:expected.tree.forest.refs.values())if(candidate.focused&&candidate.editable){if(ref!=null)return failure("ambiguous_input","请指定后台输入框节点。");ref=candidate;}
            if(ref==null)return failure("stale_node","后台目标节点已失效。");
            AccessibilityForest.Ref requested=ref;
            validateTarget(expected,requested,control);
            if("phone_click_node".equals(tool)) {
                ref=expected.tree.forest.clickTarget(requested);
                if(ref==null)return failure("not_clickable","后台目标及其原父级没有声明点击操作。");
                if(ref!=requested)validateTarget(expected,ref,control);
            }
            AccessibilityNodeInfo target=AccessibilityForest.resolve(PhoneAccessibilityService.get(),displayId,ref);
            if(target==null)return failure("stale_node","后台目标节点已失效。");
            AccessibilityNodeInfo readbackIdentity=null;
            try{
                if(!target.refresh()||!AccessibilityForest.matches(ref,target)||!target.isEnabled()||!target.isVisibleToUser())return failure("stale_node","后台目标节点已变化。");
                if(target.isPassword()&&!privateInput)return failure("requires_user","密码内容请由机主通过私密输入处理。");
                if(privateInput&&!isOwner(control))return failure("requires_user","私密输入需要当前机主接管。");
                if(privateInput){JSONObject rejected=privateInputRejection(target);if(rejected!=null)return rejected;}
                if("phone_type".equals(tool)&&!AccessibilityForest.supportsSetText(target))return failure("not_editable","该后台节点不可输入。");
                if("phone_click_node".equals(tool)&&!AccessibilityForest.supportsClick(target))return failure("not_clickable","请换用可点击节点或画面坐标。");
                int scrollAction="forward".equals(args.optString("direction"))?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                if("phone_scroll_node".equals(tool)&&!AccessibilityForest.supportsScroll(target,scrollAction))return failure("not_scrollable","该后台节点未声明此方向滚动。");
                Tree sensitiveContext=validateTarget(expected,ref,control);
                if(!target.refresh()||!AccessibilityForest.matches(ref,target))return failure("stale_node","后台目标节点已变化。");
                if(requested!=ref) {
                    if(!expected.tree.forest.sameTarget(requested,sensitiveContext.forest))return failure("stale_node","原后台点击文字或图标已变化，请重新观察。");
                    if(!isOwner(control))NativeSensitiveStep.requireNode(sensitiveContext.forest,requested,tool,control,expected.revision);
                }
                if(!isOwner(control))NativeSensitiveStep.requireNode(sensitiveContext.forest,ref,tool,control,expected.revision);
                Bundle inputArguments=null;if("phone_type".equals(tool)){inputArguments=new Bundle();inputArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);if(!privateInput)readbackIdentity=AccessibilityNodeInfo.obtain(target);}
                refreshObservationGeometry(expected,control);
                guard(control,expected.revision);final Bundle exactArguments=inputArguments;
                NativePhoneEntry.Admission exact=()->{guard(control,expected.revision);requireObservationGeometry(expected);if(admission!=null)admission.check();};
                boolean accepted=NativePhoneEntry.call(control,expected.revision,isOwner(control),exact,()->{PhoneReadGate.markEffectEntered();noteNativeInput();return acknowledgement.record("phone_type".equals(tool)?target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,exactArguments):target.performAction("phone_scroll_node".equals(tool)?scrollAction:AccessibilityNodeInfo.ACTION_CLICK));});
                // The Android false result includes a missing remote callback, not only
                // a proven refusal. Keep the original display and stop potential replay.
                if(!accepted)return failure("action_unknown","系统未确认原后台节点操作结果，请核对原副屏；不会自动重试。")
                        .put("accepted",JSONObject.NULL).put("nativeActionResult",false).put("requiresReconciliation",true)
                        .put("displayId",displayId).put("sessionId",sessionId);
                JSONObject returned=new JSONObject().put("ok",true).put("accepted",true).put("verified",false).put("displayId",displayId).put("sessionId",sessionId)
                        .put("message",privateInput?"主人私密输入请求已接收，请核对目标界面。":"后台节点请求已接收，请重新观察。");
                if(accepted&&"phone_type".equals(tool))returned.put("nativeTextReadback",privateInput?dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(null,null,true):readTextAfterInput(target,readbackIdentity,ref,text,expected,control,expected.revision));
                if(accepted&&"phone_type".equals(tool)&&!privateInput){final AccessibilityNodeInfo retained=AccessibilityNodeInfo.obtain(target),identity=AccessibilityNodeInfo.obtain(readbackIdentity);final AccessibilityForest.Ref original=ref;
                    delayed.schedule(main,reads.executor(),()->readTextAfterInput(retained,identity,original,text,expected,control,expected.revision),()->{retained.recycle();identity.recycle();});}
                acknowledgement.update(returned);return returned;
            }finally{if(readbackIdentity!=null)readbackIdentity.recycle();target.recycle();}
        });
        return delayed.attach(actionReceipt);
        }catch(Exception unavailable){
            JSONObject retained=acknowledgement.afterReadFailure("node_followup_unavailable");
            if(retained==null)throw unavailable;
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            clearReferences();return retained;
        }
    }
    private JSONObject readTextAfterInput(AccessibilityNodeInfo node,AccessibilityNodeInfo identity,AccessibilityForest.Ref ref,String wanted,Observation observed,RunControl control,long revision)throws Exception {
        try{guard(control,revision);checkGeometry(observed.geometry,observed.width,observed.height,observed.displayRevision);if(identity==null||!node.refresh()||!node.equals(identity)||node.getWindowId()!=ref.windowId||!AccessibilityForest.supportsSetText(node)||!java.util.Objects.equals(node.getViewIdResourceName(),identity.getViewIdResourceName())||!String.valueOf(node.getClassName()).equals(String.valueOf(identity.getClassName()))||!String.valueOf(node.getPackageName()).equals(String.valueOf(identity.getPackageName())))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_native_node_unavailable");
            PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service==null)return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("service_disconnected");AccessibilityNodeInfo scoped=AccessibilityForest.resolve(service,displayId,ref);try{if(scoped==null||!scoped.equals(node))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_display_window_path_unavailable");}finally{if(scoped!=null&&scoped!=node&&scoped!=identity)scoped.recycle();}
            if(node.isPassword())return dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(null,null,true);JSONObject value=dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(node.getText(),wanted,false);guard(control,revision);
            return value.put("phase","immediate_after_dispatch").put("observedAtUptimeMs",SystemClock.uptimeMillis()).put("mechanism","refresh_same_accessibility_node").put("windowId",ref.windowId).put("path",new JSONArray(ref.path)).put("displayId",displayId).put("sessionId",sessionId).put("evidenceOnly",true);
        }catch(Exception unavailable){return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("native_readback_unavailable_after_dispatch");}
    }
    // Model latency does not revoke the latest ref. The original display,
    // geometry, source node and covering context are re-read before dispatch.
    private Tree validateTarget(Observation expected,AccessibilityForest.Ref ref,RunControl control)throws Exception {
        guard(control,expected.revision);requireObservationGeometry(expected);Tree fresh=tree();
        if(current!=expected||expected.control!=control||expected.service!=PhoneAccessibilityService.get()
                ||!expected.tree.forest.sameTarget(ref,fresh.forest))
            throw new PhoneFailure("stale_node","后台目标、祖先、窗口或遮挡已变化，请重新观察。");
        requireObservationGeometry(expected);guard(control,expected.revision);return fresh;
    }
    private void checkSensitiveCoordinate(Observation expected,JSONObject args,boolean swipe,RunControl control)throws Exception {
        if(isOwner(control))return;
        onNodeWorker(control,expected.revision,()->{
            guard(control,expected.revision);if(current!=expected||expected.service!=PhoneAccessibilityService.get())throw new PhoneFailure("stale_observation","原后台观察已变化。");
            Tree fresh=tree();
            NativeSensitiveStep.requireCoordinate(fresh.forest,integer(args,swipe?"x1":"x",0,1000),integer(args,swipe?"y1":"y",0,1000),swipe?integer(args,"x2",0,1000):integer(args,"x",0,1000),swipe?integer(args,"y2",0,1000):integer(args,"y",0,1000),swipe,control,expected.revision);
            guard(control,expected.revision);return null;
        });
    }
    private Tree tree()throws Exception {
        PhoneReadGate.checkCurrent();PhoneAccessibilityService service=PhoneAccessibilityService.get();
        if(service==null)throw new PhoneFailure("service_disconnected","无障碍权限已断开，请恢复后继续原任务。");
        JSONObject state=module.session(sessionId,displayId);int width=state.optInt("width"),height=state.optInt("height");
        if(!sameSession(state)||width<=0||height<=0)throw new PhoneFailure("wrong_display","后台显示身份或尺寸已变化。");
        DisplayGeometry geometry=DisplayGeometry.read(state);long displayRevision=displayChanges.get();
        checkGeometry(geometry,width,height,displayRevision);
        AccessibilityForest forest=AccessibilityForest.read(service,displayId,width,height,()->{
            PhoneReadGate.checkCurrent();if(service!=PhoneAccessibilityService.get())throw new PhoneFailure("service_disconnected","无障碍会话已变化。");
            String health=service.observationSafetyFailure();
            if(health!=null)throw new PhoneFailure(health,"手机已锁屏或操作权限已失效，请由机主处理后继续。");
        });
        checkGeometry(geometry,width,height,displayRevision);return new Tree(forest,geometry,displayRevision,width,height);
    }
    private void session()throws Exception {
        JSONObject state=module.session(sessionId,displayId);
        if(closed)throw new PhoneFailure("background_unavailable","原副屏会话已关闭。");
        String failure=ObservationSafety.isolationFailure(displayId,sessionId,state.optInt("displayId",-1),state.optString("sessionId"),
                state.optBoolean("sessionActive"),state.optBoolean("backgroundOnly"),state.optBoolean("focusIsolationRequested"),state.optBoolean("displayFlagsConfirmed"));
        if(failure!=null)throw new PhoneFailure(failure,"后台显示或独立焦点已失效，已阻断操作；不会回落主屏。");
        if(!module.hasSession(sessionId,displayId))throw new PhoneFailure(state.optBoolean("recoveryRequired")?"reconciliation_required":"background_unavailable","后台会话需要机主核对后继续。");
        Observation observed=current;
        if(observed!=null)try{requireGeometryIdentity(observed.geometry,observed.width,observed.height,state);}catch(PhoneFailure changed){clearReferences();}
        PhoneAccessibilityService service=PhoneAccessibilityService.get();
        if(service==null)throw new PhoneFailure("service_disconnected","请恢复无障碍权限后继续原任务。");
        String health=service.observationSafetyFailure();if(health!=null)throw new PhoneFailure(health,"手机已锁屏或操作权限已失效，请由机主处理后继续。");
    }
    /** Refresh actual helper flags on the worker before Accessibility actions, which bypass helper input. */
    private void refreshSession(RunControl control,long revision)throws Exception {
        guard(control,revision);
        try{module.refreshSession(sessionId,displayId,control,revision,isOwner(control));}
        catch(InterruptedException stopped){throw stopped;}
        catch(ShellBridgeClient.StaleRequestException stale){throw stale;}
        catch(SecurityException denied){throw new PhoneFailure("permission_denied","后台助手授权已失效，请由机主恢复。");}
        catch(Exception unavailable){throw new PhoneFailure("background_unavailable","无法实时核对后台显示，已阻断操作；不会回落主屏。");}
        ensureGeometryWatch(module.session(sessionId,displayId));guard(control,revision);
    }
    private void ensureGeometryWatch(JSONObject state)throws Exception {
        if(DisplayGeometry.read(state)==null||geometryDisplays!=null)return;
        if(closed)throw new PhoneFailure("background_unavailable","原副屏会话已关闭。");
        DisplayManager manager=module.hostContext().getSystemService(DisplayManager.class);
        if(manager==null)throw new PhoneFailure("geometry_unavailable","无法读取原副屏的实时几何，旧输入已阻断。");
        geometryDisplays=manager;
        try{manager.registerDisplayListener(geometryListener,main);}catch(RuntimeException unavailable){geometryDisplays=null;throw unavailable;}
    }
    private void stopGeometryWatch(){DisplayManager manager=geometryDisplays;geometryDisplays=null;if(manager!=null)manager.unregisterDisplayListener(geometryListener);}
    private void checkGeometry(DisplayGeometry expected,int width,int height,long displayRevision)throws Exception {
        JSONObject state=module.session(sessionId,displayId);requireGeometryIdentity(expected,width,height,state);
        if(expected==null)return; // Old helper has a fixed surface and makes no rotation claim.
        ensureGeometryWatch(state);
        DisplayManager manager=geometryDisplays;Display owned=manager==null?null:manager.getDisplay(displayId);
        if(owned==null||!owned.isValid())throw new PhoneFailure("display_lost","原副屏已失效，未提交旧节点或坐标。");
        Point first=new Point(),second=new Point();DisplayMetrics a=new DisplayMetrics(),b=new DisplayMetrics();
        int rotation=owned.getRotation();owned.getRealSize(first);owned.getRealMetrics(a);
        int afterRotation=owned.getRotation();owned.getRealSize(second);owned.getRealMetrics(b);
        if(displayChanges.get()!=displayRevision||rotation!=afterRotation||!first.equals(second)||a.densityDpi!=b.densityDpi
                ||rotation!=expected.rotation||first.x!=width||first.y!=height||a.densityDpi!=expected.density)
            throw new PhoneFailure("stale_observation","副屏已旋转或改变尺寸，原观察失效，请重新观察。");
    }
    private static void requireGeometryIdentity(DisplayGeometry expected,int width,int height,JSONObject actual)throws PhoneFailure {
        final DisplayGeometry received;
        try{received=DisplayGeometry.read(actual);}catch(IllegalArgumentException malformed){throw new PhoneFailure("geometry_unavailable","副屏几何身份不完整，未继续旧输入。");}
        if(received!=null&&!actual.optBoolean("geometryReady"))throw new PhoneFailure("geometry_unavailable","副屏旋转或尺寸尚未稳定，旧输入已阻断，请重新核对原显示。");
        if(width!=actual.optInt("width",-1)||height!=actual.optInt("height",-1)
                ||expected==null&&received!=null||expected!=null&&(!expected.sameObservation(received)||!actual.optBoolean("geometryReady")))
            throw new PhoneFailure("stale_observation","副屏几何代次已变化，原观察失效，请重新观察。");
    }
    private void requireTreeGeometry(Tree tree,JSONObject actual)throws Exception {
        requireGeometryIdentity(tree.geometry,tree.width,tree.height,actual);
        checkGeometry(tree.geometry,tree.width,tree.height,tree.displayRevision);
    }
    private void requireObservationGeometry(Observation expected)throws Exception {
        if(current!=expected)throw new PhoneFailure("stale_observation","原副屏观察已失效。");
        checkGeometry(expected.geometry,expected.width,expected.height,expected.displayRevision);
    }
    /** Re-read the authenticated helper on the actual node worker, after any queued tree read. */
    private void refreshObservationGeometry(Observation expected,RunControl control)throws Exception {
        requireObservationGeometry(expected);
        if(expected.geometry==null)return;
        JSONObject frame=request("frame",new JSONObject().put("includeImage",false),control,expected.revision);
        if(!frame.optBoolean("ok")||frame.optBoolean("empty")||!sameSession(frame))
            throw new PhoneFailure("stale_observation","原副屏旋转后的画面尚未确认，未提交旧节点。");
        requireGeometryIdentity(expected.geometry,expected.width,expected.height,frame);requireObservationGeometry(expected);
    }
    private static JSONObject inputIdentity(JSONObject input,Observation observed)throws Exception {
        input.put("frameGeneration",observed.frame);
        if(observed.geometry==null)input.remove("geometryGeneration");else input.put("geometryGeneration",observed.geometry.generation);
        return input;
    }
    private boolean sameSession(JSONObject state){return displayId==state.optInt("displayId",-1)&&sessionId.equals(state.optString("sessionId"));}
    private record NativeProfile(int user,java.util.Set<Integer> tasks) { }
    /** A package label never implies Android user zero. Both native samples must agree. */
    static void attachObservedProfile(JSONObject observation,JSONObject before,JSONObject after,int display)throws Exception {
        observation.remove("androidUserId");observation.remove("androidUserIdSource");observation.put("actualUserVerified",false);
        String pkg=observation.optString("packageName");NativeProfile first=profile(before,pkg,display),last=profile(after,pkg,display);
        if(first!=null&&first.equals(last))observation.put("androidUserId",first.user).put("actualUserVerified",true).put("androidUserIdSource","native_task_topology_before_and_after");
    }
    private static NativeProfile profile(JSONObject facts,String pkg,int display)throws Exception {
        if(pkg.isEmpty()||facts==null||!"observed".equals(facts.optString("status")))return null;
        JSONObject topology=facts.optJSONObject("taskTopology");JSONArray owners=facts.optJSONArray("owned");
        if(topology==null||!"observed".equals(topology.optString("status"))||!topology.optBoolean("complete")||owners==null)return null;
        JSONArray tasks=topology.optJSONArray("tasks");if(tasks==null)return null;
        Integer user=null;java.util.Set<Integer> matchedTasks=new java.util.HashSet<>();
        for(int i=0;i<owners.length();i++){
            JSONObject owner=owners.getJSONObject(i);
            if(!pkg.equals(owner.optString("packageName"))||display!=owner.optInt("displayId",-1))continue;
            if(!(owner.opt("userId") instanceof Number)||!(owner.opt("taskId") instanceof Number))return null;
            int actualUser=new java.math.BigDecimal(owner.get("userId").toString()).intValueExact();
            int taskId=new java.math.BigDecimal(owner.get("taskId").toString()).intValueExact();
            if(actualUser<0||taskId<0||user!=null&&user!=actualUser)return null;
            int matches=0;
            for(int j=0;j<tasks.length();j++){JSONObject task=tasks.getJSONObject(j);
                if(taskId==task.optInt("taskId",-1)&&display==task.optInt("displayId",-1)&&actualUser==task.optInt("androidUserId",-1)&&pkg.equals(task.optString("packageName")))matches++;
            }
            if(matches!=1)return null;user=actualUser;matchedTasks.add(taskId);
        }
        return user==null?null:new NativeProfile(user,java.util.Set.copyOf(matchedTasks));
    }
    private void guard(RunControl control,long revision)throws Exception {control.check();PhoneReadGate.checkCurrent();if(runOwner!=null&&control!=runOwner)throw new SecurityException("副屏只接受原运行控制器");if(ownerHandoffActive())throw new SecurityException("原应用尚未交还，副屏输入已停用");
        boolean owner=isOwner(control);if(control.isPaused()!=owner||control.pauseRevision()!=revision)throw new PhoneFailure("paused_or_changed","执行控制已变化，请重新观察");
        if(owner){OwnerPermit permit=ownerPermit;if(permit==null||permit.revision!=revision||!module.ownedBy(permit.taskId,sessionId,displayId))throw new SecurityException("人工接管授权已失效");}session();}
    private boolean isOwner(RunControl control){OwnerPermit permit=ownerPermit;return permit!=null&&permit.control==control;}
    private JSONObject request(String command,JSONObject input,RunControl control,long revision)throws Exception {
        guard(control,revision);JSONObject pinned=new JSONObject(input.toString()).put("sessionId",sessionId).put("displayId",displayId);
        if("frame".equals(command)){long after=nativeFrameAfterNanos.get();if(after>0)pinned.put("afterTimestampNanos",after);}
        JSONObject result=module.request(command,pinned,control,revision,isOwner(control));
        return result.optBoolean("ok")?result:ObservationReport.annotateFailure(result);
    }
    private <T>T onTreeRead(RunControl control,long revision,java.util.concurrent.Callable<T> read)throws Exception {
        try{return reads.call(()->guard(control,revision),read);}
        catch(PhoneReadGate.Unavailable unavailable){throw new PhoneFailure(unavailable.code,"副屏读取超时或仍在等候系统；旧引用未发布，接管和停止仍可使用。");}
    }
    private <T>T onNodeWorker(RunControl control,long revision,java.util.concurrent.Callable<T> action)throws Exception {
        try{return reads.call(()->guard(control,revision),action);}
        catch(PhoneReadGate.Unavailable unavailable){
            if(unavailable.getCause() instanceof InterruptedException)Thread.currentThread().interrupt();
            if("native_effect_unknown".equals(unavailable.code))
                throw new PhoneFailure("action_unknown","副屏已入场动作回执未知，请核对原操作；不会自动重试。");
            throw new PhoneFailure(unavailable.code,"副屏读取尚未完成，未派发动作；请等原读取结束后重新观察。");
        }
    }
    private static int integer(JSONObject input,String key,int min,int max){Object value=input.opt(key);if(!(value instanceof Number))throw new IllegalArgumentException();double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException();return(int)n;}
    static JSONObject privateInputRejection(AccessibilityNodeInfo target)throws Exception {
        return target.isPassword()&&AccessibilityForest.supportsSetText(target)?null:failure("requires_user","当前私密输入仅支持密码控件；普通明文输入框请由机主自行处理后再继续。");
    }
    private static JSONObject failure(String error,String message)throws Exception{return ObservationReport.annotateFailure(new JSONObject().put("ok",false).put("error",error).put("message",message).put("accepted",false).put("verified",false));}
    private static JSONObject failure(PhoneFailure blocked)throws Exception {JSONObject out=failure(blocked.code,blocked.getMessage());if(blocked.evidence!=null)out.put("displayLanding",blocked.evidence).put("requiresUser",true);return out;}
    private static final class PhoneFailure extends Exception {final String code;final JSONObject evidence;PhoneFailure(String code,String message){this(code,message,null);}PhoneFailure(String code,String message,JSONObject evidence){super(message);this.code=code;this.evidence=evidence;}}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("BackgroundPhonePort requires worker thread");}
    private static final class Tree {
        final AccessibilityForest forest;
        final JSONArray nodes;
        final String packageName,signature;
        final boolean truncated,available;
        final DisplayGeometry geometry;final long displayRevision;final int width,height;
        Tree(AccessibilityForest forest,DisplayGeometry geometry,long displayRevision,int width,int height){this.forest=forest;this.geometry=geometry;this.displayRevision=displayRevision;this.width=width;this.height=height;nodes=forest.nodes;packageName=forest.packageName;signature=forest.signature;truncated=forest.truncated;available=forest.available;}
    }
    private static final class Observation {final String id=UUID.randomUUID().toString();final long at=SystemClock.uptimeMillis(),frame,revision,displayRevision;final int width,height;final Tree tree;final RunControl control;final PhoneAccessibilityService service;final boolean imageIncluded;final DisplayGeometry geometry;
        Observation(Tree tree,long frame,long revision,int width,int height,RunControl control,boolean imageIncluded){this.tree=tree;this.frame=frame;this.revision=revision;this.width=width;this.height=height;this.control=control;this.service=PhoneAccessibilityService.get();this.imageIncluded=imageIncluded;this.geometry=tree.geometry;this.displayRevision=tree.displayRevision;}}
    private static final class OwnerPermit {final String taskId;final RunControl control;final long revision;OwnerPermit(String taskId,RunControl control,long revision){this.taskId=taskId;this.control=control;this.revision=revision;}}
}

