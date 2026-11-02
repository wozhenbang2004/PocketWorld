package dev.pocketengine.phone.display;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.observation.ObservationReport;
import dev.pocketengine.phone.observation.AccessibilityForest;
import dev.pocketengine.phone.observation.ObservationSafety;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** A task is pinned to one separately created display; this class has no primary-screen actions. */
public final class BackgroundPhonePort implements PhonePort,dev.pocketengine.phone.observation.NativeOcrTapPort,dev.pocketengine.phone.observation.NativeTreeScrollPort,dev.pocketengine.phone.observation.NativePhoneSignals,dev.pocketengine.phone.observation.NativePhoneExtensions {
    private final BackgroundDisplaySession module;
    private final String sessionId;
    private final int displayId;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Long> DISPATCH_DEADLINE=new ThreadLocal<>();
    private volatile Observation current;
    private volatile JSONObject cached;
    private volatile OwnerPermit ownerPermit;
    private volatile boolean lastIncludeImage;
    private JSONObject landingBaseline;
    private dev.pocketengine.phone.setup.AndroidAppCopies appCopies;
    private dev.pocketengine.phone.access.PhoneAppResources appResources;private String resourceOwner;
    private dev.pocketengine.phone.setup.PhoneAudioRecovery audioRecovery;private android.content.Context lifecycleContext;
    private final dev.pocketengine.phone.setup.PhoneAfterTaskPlan afterTask=new dev.pocketengine.phone.setup.PhoneAfterTaskPlan();
    public BackgroundPhonePort(DisplayModule module)throws Exception {this(BackgroundDisplaySession.legacy(module));}
    public BackgroundPhonePort(BackgroundDisplaySession module)throws Exception {
        this.module=module;JSONObject session=module.session();
        sessionId=session.optString("sessionId");displayId=session.optInt("displayId",-1);
        if(!module.hasSession()||sessionId.isEmpty()||displayId<=0)throw new IllegalStateException("请先连接后台助手并创建独立屏幕");
    }
    @Override public boolean isConnected(){try{return module.hasSession()&&sameSession(module.session());}catch(Exception ignored){return false;}}
    /** Original port identity only. Reading it never captures a frame or replaces a session. */
    public JSONObject executionIdentity()throws Exception{return new JSONObject().put("sessionId",sessionId).put("displayId",displayId);}
    /** Supplied by the original native supervisor, never a model argument. */
    public synchronized void bindAppResources(dev.pocketengine.phone.access.PhoneAppResources resources,String owner){if(appResources!=null&&(appResources!=resources||!java.util.Objects.equals(owner,resourceOwner)))throw new SecurityException("phone_resource_binding_changed");appResources=java.util.Objects.requireNonNull(resources);resourceOwner=java.util.Objects.requireNonNull(owner);}
    public synchronized void bindLifecycle(android.content.Context context)throws Exception {lifecycleContext=context.getApplicationContext();audioRecovery=dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.get(lifecycleContext);}
    public synchronized JSONObject restoreTaskAudio()throws Exception {if(audioRecovery==null)return new JSONObject().put("pending",0).put("status","audio_not_configured");if(!sameSession(module.session()))return audioRecovery.snapshot().put("status","original_session_changed_recovery_pending");return dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withCommands((command,payload)->module.request(command,payload,null),()->audioRecovery.restoreOwner(resourceOwner==null?sessionId:resourceOwner));}
    public synchronized JSONObject prepareAfterTask(RunControl control)throws Exception {return afterTask.prepare(landing(control,control.pauseRevision()),displayId);}
    /** Read-only native cleanup probe, including after cancellation. It grants no movement, close, or business replay. */
    public synchronized JSONObject prepareAfterTaskForCleanup(RunControl original)throws Exception {
        var identity=original==null?null:original.hostRunIdentity();if(identity==null||!module.ownedBy(identity.taskId())||!sameSession(module.session())||!module.isConnected())throw new SecurityException("original_cleanup_display_required");
        JSONObject response=module.request("ownership",new JSONObject(),null);
        if(!response.optBoolean("ok")||response.optInt("displayId",-1)!=displayId||!sessionId.equals(response.optString("sessionId"))||!sameSession(module.session())||!module.ownedBy(identity.taskId()))throw new SecurityException("cleanup_ownership_unknown");
        return afterTask.prepare(response.getJSONObject("ownership"),displayId).put("requiresOriginalReceiptCheck",true);
    }
    private void beforeOpenAudio(String pkg,int user,RunControl control)throws Exception {if(audioRecovery!=null&&lifecycleContext!=null&&dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(lifecycleContext)){long revision=control.pauseRevision();JSONObject receipt=dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withCommands((command,payload)->module.requestAdmitted(command,payload,control,revision,sessionId,displayId,()->{guard(control,revision);if(!dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(lifecycleContext))throw new SecurityException("owner_audio_policy_revoked");}),()->audioRecovery.silence(resourceOwner==null?sessionId:resourceOwner,new dev.pocketengine.phone.access.AppCopyKey(pkg,user),true));if(!"silenced_verified".equals(receipt.optString("outcome")))throw new PhoneFailure("audio_policy_unconfirmed","主人选择了后台静音，但当前原App音频状态尚未证实；没有继续打开目标App，请核对声音与恢复记录。");}}
    public dev.pocketengine.phone.access.PhoneOwnerPackages ownerPackages(RunControl control)throws Exception {var identities=dev.pocketengine.phone.access.PhoneOwnerPackages.fromOwnership(landing(control,control.pauseRevision()),"owned");return dev.pocketengine.phone.access.PhoneWindowPackages.read(PhoneAccessibilityService.get(),displayId,identities,control::check);}
    public synchronized JSONObject dispatchSemantic(JSONObject exact,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        worker();long revision=control.pauseRevision();refreshSession(control,revision);JSONObject target=exact.getJSONObject("target");reserveApp(target.getString("packageName"),exact.getInt("userId"),control);
        JSONObject before=landing(control,revision);guard(control,revision);beforeOpenAudio(target.getString("packageName"),exact.getInt("userId"),control);clearReferences();JSONObject result=module.requestAdmitted("intent",exact,control,revision,sessionId,displayId,admission);
        return Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")?verifyLanding(result,before,control,revision):result;
    }
    private void reserveApp(String pkg,int user,RunControl control)throws Exception {
        if(appResources==null)return;dev.pocketengine.phone.access.AppCopyKey key=new dev.pocketengine.phone.access.AppCopyKey(pkg,user);
        long revision=control.pauseRevision();guard(control,revision);
        var result=appResources.await(resourceOwner,key,15_000,control,(actual,owner)->{JSONObject facts=landing(control,revision);if(!facts.optBoolean("allDisplayIdentitiesKnown"))return false;JSONArray all=facts.optJSONArray("all");if(all==null)return false;for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(actual.packageName().equals(row.optString("packageName"))&&(!row.has("userId")||row.getInt("userId")==actual.userId())&&row.optInt("displayId",-1)!=displayId)return false;}return true;});
        guard(control,revision);
        if(result==dev.pocketengine.phone.access.PhoneAppResources.Result.DEADLOCK)throw new PhoneFailure("app_resource_deadlock","原任务之间发生App/资料等待环路；已退出等待，没有抢占或重复派发。");
        if(result!=dev.pocketengine.phone.access.PhoneAppResources.Result.ACQUIRED)throw new PhoneFailure("app_resource_wait_timeout","目标App/资料仍由其他任务或主屏使用，或实际归属不可核对；15秒有界等待已结束，没有抢占。");
    }
    @Override public synchronized JSONObject extension(String tool,JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        worker();dev.pocketengine.phone.observation.PhoneExtensionTools.validate(tool,args);control.check();long revision=control.pauseRevision();refreshSession(control,revision);
        PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service==null)return failure("service_disconnected","原无障碍服务已断开。");
        if(appCopies==null)appCopies=new dev.pocketengine.phone.setup.AndroidAppCopies(service);
        if(tool.equals("phone_list_app_copies"))return appCopies.list(control);if(admission==null)throw new SecurityException("original_prepared_admission_required");
        if(tool.equals("phone_app_after_task")){admission.check();JSONArray held=landing(control,revision).getJSONArray("owned");boolean found=false;for(int i=0;i<held.length();i++){JSONObject row=held.getJSONObject(i);if(args.getString("packageName").equals(row.optString("packageName"))&&row.has("userId")&&args.getInt("userId")==row.getInt("userId"))found=true;}if(!found)return failure("after_task_app_not_owned","该App资料不是原副屏已证实持有的任务。");return afterTask.mark(new dev.pocketengine.phone.access.AppCopyKey(args.getString("packageName"),args.getInt("userId")),args.getString("action"));}
        JSONObject before=landing(control,revision),result;
        if(tool.equals("phone_launch_app_copy")){
            JSONObject exact=appCopies.require(args);reserveApp(exact.getString("packageName"),exact.getInt("userId"),control);beforeOpenAudio(exact.getString("packageName"),exact.getInt("userId"),control);clearReferences();result=module.requestAdmitted("launch_copy",exact,control,revision,sessionId,displayId,admission);
        }else if(tool.equals("phone_practice_target")){
            if(!service.getPackageName().equals(args.getString("packageName"))||dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle())!=args.getInt("userId"))throw new SecurityException("practice_target_is_fixed");
            clearReferences();result=module.requestAdmitted("practice",new JSONObject().put("packageName",service.getPackageName()).put("userId",args.getInt("userId")),control,revision,sessionId,displayId,admission);if(Boolean.TRUE.equals(result.opt("accepted")))result.put("helperAuthenticated",module.isConnected()).put("imeIsolation",module.session().optString("imeIsolation"));
        }else{
            Observation expected=current;if(expected==null||expected.control!=control||!expected.id.equals(args.optString("observationId"))||expected.revision!=revision||SystemClock.uptimeMillis()-expected.at>120000)return failure("stale_observation","扩展动作需要当前副屏观察。");
            JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;
            if(tool.equals("phone_paste_text")){
                AccessibilityForest.Ref ref=expected.tree.forest.refs.get(args.getString("nodeId"));if(ref==null)return failure("stale_node","原输入目标已变化。");
                result=dev.pocketengine.phone.observation.AccessibilityPasteInput.paste(service,service,displayId,ref,args.getString("text"),control,revision,admission,()->{guard(control,revision);if(current!=expected||!tree().signature.equals(expected.tree.signature))throw new SecurityException("paste_original_tree_changed");},this::clearReferences);
            }else if(tool.equals("phone_gesture")){
                if(!expected.imageIncluded)return failure("coordinate_unverifiable","多指需要当前真实图片。");JSONObject payload=new JSONObject(args.toString()).put("frameGeneration",expected.frame);payload.remove("observationId");clearReferences();result=module.requestAdmitted("gesture",payload,control,revision,sessionId,displayId,admission);
            }else return failure("native_extension_unavailable","当前副屏不支持该扩展。");
        }
        return Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")?verifyLanding(result,before,control,revision):result;
    }
    @Override public void clearReferences(){current=null;}
    @Override public synchronized JSONObject tapOcrText(dev.pocketengine.phone.observation.OcrTextTarget target,RunControl control,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        worker();JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;Observation expected=current;
        if(expected==null||!target.matches(control,expected.id,expected.revision)||!expected.imageIncluded
                ||target.sourceWidth!=expected.width||target.sourceHeight!=expected.height)
            return failure("stale_observation","OCR目标不属于当前完整后台画面，请重新观察。");
        try {
            refreshSession(control,expected.revision);ime=imeUnavailable(control);if(ime!=null)return ime;JSONObject point=target.tapArguments(expected.width,expected.height);
            JSONObject args=new JSONObject().put("x",point.getInt("x")).put("y",point.getInt("y")).put("frameGeneration",expected.frame);
            guard(control,expected.revision);clearReferences();
            JSONObject before=landing(control,expected.revision);JSONObject result=module.requestOcrTap(args,control,expected.revision,sessionId,displayId,admission);return Boolean.TRUE.equals(result.opt("accepted"))?verifyLanding(result,before,control,expected.revision):result;
        }catch(dev.pocketengine.phone.observation.NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(IllegalArgumentException invalid){clearReferences();return failure("coordinate_unverifiable",invalid.getMessage());}
    }
    public void renewTaskLease(String taskId,String runId,int epoch,RunControl control,long taskDeadline)throws Exception {module.renewTaskLease(taskId,runId,epoch,control,sessionId,displayId,taskDeadline);}
    public void noteReleasePending(){module.noteReleasePending(sessionId,displayId);}
    /** Read-only view while the agent runs: no new frame, reference or model observation is made. */
    public synchronized JSONObject cachedObservationForOwner(String taskId)throws Exception {
        if(!module.ownedBy(taskId))throw new SecurityException("副屏不属于当前任务");
        if(cached==null)return new JSONObject().put("ok",false).put("error","no_observation")
                .put("message","任务尚未取得真实副屏画面").put("manualAllowed",false).put("referencesExpired",true);
        return new JSONObject(cached.toString()).put("manualAllowed",false).put("referencesExpired",true).put("taskId",taskId);
    }
    /** The native supervisor validates and journals the current run before using these owner entry points. */
    public synchronized JSONObject observeForOwner(String taskId,RunControl control)throws Exception {
        beginOwner(taskId,control);
        try{JSONObject observed=observe(true,control);return observed.put("manualAllowed",observed.optBoolean("ok")).put("referencesExpired",!observed.optBoolean("ok")).put("taskId",taskId);}
        finally{ownerPermit=null;}
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
            if(observation==null||observation.control!=control||!observation.id.equals(args.optString("observationId"))||SystemClock.uptimeMillis()-observation.at>120_000
                    ||observation.revision!=control.pauseRevision())return failure("stale_observation","输入节点引用已失效，请重新观察");
            refreshSession(control,observation.revision);ime=imeUnavailable(control);if(ime!=null)return ime;
            JSONObject before=landing(control,observation.revision);JSONObject result=nodeAction("phone_type",args,observation,control,text);return Boolean.TRUE.equals(result.opt("accepted"))||result.has("accepted")&&result.isNull("accepted")?verifyLanding(result,before,control,observation.revision):result;
        }catch(InterruptedException cancelled){throw cancelled;}
        catch(PhoneFailure blocked){clearReferences();return failure(blocked.code,blocked.getMessage());}
        catch(Exception unknown){clearReferences();return failure("action_unknown","私密输入结果尚未确认，请由机主核对后继续。").put("accepted",JSONObject.NULL);}
        finally{ownerPermit=null;}
    }
    private void beginOwner(String taskId,RunControl control)throws Exception {
        if(control==null||!module.ownedBy(taskId))throw new SecurityException("副屏不属于当前任务");
        control.check();if(!control.isPaused())throw new SecurityException("先暂停并接管任务后才能操作副屏");
        if(!isConnected())throw new SecurityException("后台会话已变更，请先由机主核对");
        ownerPermit=new OwnerPermit(taskId,control,control.pauseRevision());
    }
    public JSONObject closeSession(){clearReferences();JSONObject audio;try{audio=restoreTaskAudio();}catch(Exception pending){audio=new JSONObject();try{audio.put("status","audio_recovery_pending").put("pending",true);}catch(Exception ignored){}}
        try{JSONObject result=module.releaseSession(sessionId,displayId).put("audioRestore",audio);if(result.optBoolean("releaseConfirmed")&&appResources!=null)appResources.releaseOwner(resourceOwner);return result;}catch(Exception unknown){try{return new JSONObject().put("ok",false).put("releaseConfirmed",false).put("audioRestore",audio).put("error","cleanup_unknown").put("message","后台屏释放未确认，请重新连接核对。");}catch(Exception impossible){return new JSONObject();}}}
    @Override public dev.pocketengine.phone.PhoneUiSignals uiSignals(RunControl control)throws Exception {worker();guard(control,control.pauseRevision());PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service==null)return null;return service.uiSignals();}
    @Override public synchronized JSONObject observe(boolean includeImage,RunControl control)throws Exception {
        worker();control.check();
        if(landingBaseline!=null&&!isOwner(control)){
            try{JSONObject now=landing(control,control.pauseRevision());if(!"observed".equals(now.optString("status"))||!landingBaseline.getJSONArray("primary").toString().equals(now.getJSONArray("primary").toString())){control.pause();clearReferences();return failure("display_ownership_unknown","后台动作后的窗口归属发生变化，已暂停原任务，请主人核对；不会迁移主屏应用。").put("displayLanding",now);}}
            catch(Exception unknown){control.pause();clearReferences();return failure("display_ownership_unknown","后台窗口归属暂不可核对，已暂停原任务。");}
        }
        // Never wait for resume while holding this port's monitor: the paused owner
        // needs that same monitor to inspect and interact with the retained display.
        if(control.isPaused()&&!isOwner(control))return failure("task_paused","任务已暂停，旧观察未派发。");
        current=null;lastIncludeImage=includeImage;
        try{
            long revision=control.pauseRevision();refreshSession(control,revision);
            Tree before=onMain(control,revision,()->tree());
            if(!includeImage&&!module.session().optBoolean("frameMetadataOnly"))
                return failure("metadata_only_unavailable","当前后台助手不支持无图元数据观察，请更新助手；未编码图片，也不会切换主屏。");
            JSONObject frame=request("frame",new JSONObject().put("includeImage",includeImage),control,revision);
            if(!frame.optBoolean("ok")){
                if(!"frame_unavailable".equals(frame.optString("error")))return ObservationReport.annotateFailure(frame);
                // A newly created display may have no frame until an app is launched. This is
                // an actual empty-display observation, not a screenshot or a fabricated page.
                Tree empty=onMain(control,revision,()->tree());JSONObject session=module.session();
                if(!before.signature.equals(empty.signature))return failure("screen_changed","后台页面在观察期间变化，请重新观察。");
                guard(control,revision);Observation observed=new Observation(empty,-1,revision,session.optInt("width",720),session.optInt("height",1280),control,false);
                current=observed;
                JSONObject emptyFrame=new JSONObject().put("ok",true).put("observationId",observed.id).put("displayId",displayId).put("sessionId",sessionId)
                        .put("executionMode","background").put("observedAt",System.currentTimeMillis()).put("screenWidth",observed.width).put("screenHeight",observed.height)
                        .put("packageName",empty.packageName).put("nodes",empty.nodes).put("imageUnavailable",true)
                        .put("screenState","display_has_no_frame").put("truncated",empty.truncated)
                        .put("message","独立显示已建立，尚未产生画面。请先列出并打开应用；目前没有可用于坐标操作的图片。");
                emptyFrame.put("observedAtUptimeMs",observed.at).put("pauseRevision",revision);
                empty.forest.attach(emptyFrame);
                ObservationReport.attach(emptyFrame,empty.available,empty.available?"accessibility_tree":"no_accessible_root",false,"display_has_no_frame");
                cached=new JSONObject(emptyFrame.toString());return emptyFrame;
            }
            if(frame.optInt("displayId",-1)!=displayId||!sessionId.equals(frame.optString("sessionId")))return failure("wrong_display","后台画面身份不匹配。");
            if(frame.optBoolean("framePending"))return failure("frame_pending","后台帧仍在更新，请重新观察。");
            Tree after=onMain(control,revision,()->tree());
            if(!before.signature.equals(after.signature))return failure("screen_changed","后台页面在观察期间变化，请重新观察。");
            JSONObject state=module.session();
            if(frame.optInt("width")<=0||frame.optInt("height")<=0||frame.optLong("frameGeneration",-1)<0
                    ||frame.optInt("width")!=state.optInt("width")||frame.optInt("height")!=state.optInt("height"))return failure("invalid_frame","后台画面尺寸或代次无效。");
            guard(control,revision);
            Observation observed=new Observation(after,frame.optLong("frameGeneration",-1),revision,frame.getInt("width"),frame.getInt("height"),control,includeImage);
            JSONObject out=new JSONObject().put("ok",true).put("observationId",observed.id).put("displayId",displayId).put("sessionId",sessionId)
                    .put("executionMode","background").put("observedAt",System.currentTimeMillis()).put("observedAtUptimeMs",observed.at).put("pauseRevision",revision).put("capturedAtUptimeMs",frame.optLong("capturedAt"))
                    .put("screenWidth",observed.width).put("screenHeight",observed.height)
                    .put("packageName",after.packageName.isEmpty()?frame.optString("packageName"):after.packageName)
                    .put("requestedPackageName",frame.optString("requestedPackageName")).put("frameGeneration",observed.frame)
                    .put("frameMetadataOnly",!includeImage).put("framePending",frame.optBoolean("framePending")).put("frameEmpty",frame.optBoolean("empty")).put("nodes",after.nodes).put("coordinateSpace","0..1000").put("truncated",after.truncated);
            if(frame.optBoolean("empty"))out.put("screenState","display_has_no_frame").put("imageUnavailable",true);
            after.forest.attach(out);
            if(includeImage)out.put("imageBase64",frame.getString("imageBase64")).put("imageMimeType",frame.optString("imageMimeType","image/jpeg"))
                    .put("imageWidth",observed.width).put("imageHeight",observed.height);
            if(includeImage&&!ObservationReport.hasImage(out))return failure("invalid_frame","后台助手没有返回有效的图片字节。");
            ObservationReport.attach(out,after.available,after.available?"accessibility_tree":"no_accessible_root",includeImage,
                    includeImage?"display_surface_frame":"not_requested_frame_generation_only");
            current=observed;cached=new JSONObject(out.toString());return out;
        }catch(InterruptedException cancelled){throw cancelled;}
        catch(ShellBridgeClient.StaleRequestException stale){clearReferences();return failure("stale_observation","任务曾暂停，旧后台观察未派发，请重新观察。");}
        catch(PhoneFailure blocked){clearReferences();return failure(blocked.code,blocked.getMessage());}
        catch(SecurityException denied){clearReferences();return failure("permission_denied","后台或无障碍权限已失效，请恢复后继续。");}
        catch(Exception error){clearReferences();return failure("background_unavailable","后台观察不可用，已停止；不会回落主屏。");}
    }
    @Override public synchronized JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception {
        if(!java.util.Set.of("phone_launch_app","phone_click_node","phone_type","phone_tap","phone_swipe","phone_back").contains(tool))return executeNative(tool,args,control);
        long revision=control.pauseRevision();JSONObject before;
        JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;
        try{before=landing(control,revision);}catch(Exception unavailable){return failure("display_ownership_unknown","无法核对原副屏与主屏任务归属，未派发动作。");}
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
                reserveApp(pkg,dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle()),control);
                beforeOpenAudio(pkg,dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle()),control);
                long revision=control.pauseRevision();guard(control,revision);clearReferences();
                return request("launch",new JSONObject().put("packageName",pkg),control,revision);
            }
            Observation observation=current;
            if(observation==null||observation.control!=control||!observation.id.equals(args.optString("observationId"))||SystemClock.uptimeMillis()-observation.at>120_000
                    ||observation.revision!=control.pauseRevision())return failure("stale_observation","后台页面引用已过期，请重新观察。");
            guard(control,observation.revision);
            if("phone_home".equals(tool))return failure("unsupported_tool","后台模式没有主屏桌面；请直接打开指定应用。");
            if("phone_click_node".equals(tool)||"phone_type".equals(tool))return nodeAction(tool,args,observation,control);
            JSONObject command=new JSONObject().put("frameGeneration",observation.frame);
            if("phone_back".equals(tool)){
                clearReferences();return request("key",command.put("keyCode",android.view.KeyEvent.KEYCODE_BACK),control,observation.revision);
            }
            boolean swipe="phone_swipe".equals(tool);
            if((swipe||"phone_tap".equals(tool))&&!observation.imageIncluded)return failure("coordinate_unverifiable","坐标操作需要已提供真实图片的最新观察；未提交旧坐标。");
            if(!swipe&&!"phone_tap".equals(tool))return failure("unsupported_tool","该后台工具尚不支持。");
            for(String key:swipe?new String[]{"x1","y1","x2","y2"}:new String[]{"x","y"})command.put(key,integer(args,key,0,1000));
            if(swipe)command.put("durationMs",integer(args,"durationMs",100,2000));
            guard(control,observation.revision);clearReferences();return request(swipe?"swipe":"tap",command,control,observation.revision);
        }catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(InterruptedException cancelled){throw cancelled;}
        catch(ShellBridgeClient.StaleRequestException stale){clearReferences();return failure("stale_observation","任务曾暂停，旧后台动作未派发，请重新观察。");}
        catch(PhoneFailure blocked){clearReferences();return failure(blocked.code,blocked.getMessage());}
        catch(IllegalArgumentException invalid){clearReferences();return failure("invalid_arguments","后台工具参数无效。");}
        catch(Exception error){clearReferences();return failure("action_unknown","后台操作通信中断，结果可能未知；请重新观察，勿直接重放。").put("accepted",JSONObject.NULL);}
    }
    private JSONObject imeUnavailable(RunControl control)throws Exception {
        if(module.session().optString("imeIsolation").equals("available"))return null;control.pause();clearReferences();
        return failure("background_ime_unavailable","独立输入法隔离尚未证实；原任务已暂停，不会在主屏使用输入法回落。").put("requiresUser",true);
    }
    private JSONObject landing(RunControl control,long revision)throws Exception {
        JSONObject response=request("ownership",new JSONObject(),control,revision),facts=response.getJSONObject("ownership");
        if(facts.getJSONArray("primary").length()==0)throw new SecurityException("主屏归属不可核对");return facts;
    }
    private JSONObject verifyLanding(JSONObject receipt,JSONObject before,RunControl control,long revision)throws Exception {
        JSONObject evidence;
        try{
            // Bounded read-only settling, then retain the baseline for later observations too.
            Thread.sleep(350);JSONObject after=landing(control,revision);boolean primarySame=before.getJSONArray("primary").toString().equals(after.getJSONArray("primary").toString());
            boolean owned=after.getString("status").equals("observed");
            int expectedUser=receipt.optInt("requestedUserId",receipt.optInt("userId",-1));if(expectedUser>=0){boolean matchingCopy=false;JSONArray landed=after.getJSONArray("owned");for(int i=0;i<landed.length();i++){JSONObject task=landed.getJSONObject(i);if(receipt.optString("packageName").equals(task.optString("packageName"))&&task.has("userId")&&task.getInt("userId")==expectedUser)matchingCopy=true;}owned&=matchingCopy;receipt.put("actualUserVerified",matchingCopy);}
            evidence=new JSONObject().put("before",before).put("after",after).put("status",primarySame&&owned?"owned_display_observed":"unknown_requires_owner").put("taskMigrationPerformed",false).put("automaticReplayAllowed",false);
            if(!primarySame||!owned){control.pause();clearReferences();receipt.put("requiresUser",true).put("requiresPause",true).put("message","动作已请求，但跨应用落点、资料身份或主屏归属不能确认；已暂停原任务，不会移动或代点主屏应用。");}
            else landingBaseline=after;
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}catch(Exception unavailable){control.pause();clearReferences();evidence=new JSONObject().put("status","unknown_requires_owner").put("reason","post_action_task_probe_unavailable").put("automaticReplayAllowed",false);receipt.put("requiresUser",true);}
        return receipt.put("displayLanding",evidence);
    }
    private JSONObject nodeAction(String tool,JSONObject args,Observation expected,RunControl control)throws Exception {
        return nodeAction(tool,args,expected,control,null);
    }
    @Override public synchronized JSONObject scrollNode(JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        worker();JSONObject ime=imeUnavailable(control);if(ime!=null)return ime;dev.pocketengine.phone.observation.NativeTreeScrollPort.validate(args);if(admission==null)return failure("host_authority_required","后台节点滚动须原prepared操作。");control.check();
        try{Observation expected=current;
            if(control.isPaused()||expected==null||expected.control!=control||!expected.id.equals(args.getString("observationId"))||expected.revision!=control.pauseRevision()||SystemClock.uptimeMillis()-expected.at>120_000)return failure("stale_observation","后台滚动需要此原执行当前树引用。");
            guard(control,expected.revision);JSONObject before=landing(control,expected.revision);JSONObject result=nodeAction("phone_scroll_node",args,expected,control,null,admission);return Boolean.TRUE.equals(result.opt("accepted"))?verifyLanding(result,before,control,expected.revision):result;
        }catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(PhoneFailure refused){clearReferences();JSONObject out=failure(refused.code,refused.getMessage());if(ObservationSafety.unknownEffect(refused.code))out.put("accepted",JSONObject.NULL);return out;}
        catch(InterruptedException stopped){clearReferences();throw stopped;}
        catch(Exception unknown){clearReferences();return failure("action_unknown","后台滚动结果未知，请核对；不会自动重试。").put("accepted",JSONObject.NULL);}
    }
    private JSONObject nodeAction(String tool,JSONObject args,Observation expected,RunControl control,String ownerSecret)throws Exception {
        return nodeAction(tool,args,expected,control,ownerSecret,null);
    }
    private JSONObject nodeAction(String tool,JSONObject args,Observation expected,RunControl control,String ownerSecret,NativePhoneEntry.Admission admission)throws Exception {
        boolean privateInput=ownerSecret!=null;String nodeId=args.optString("nodeId");String text=privateInput?ownerSecret:args.optString("text");
        if("phone_type".equals(tool)&&(!privateInput&&!(args.opt("text") instanceof String)||text.length()>12000))return failure("invalid_arguments","输入文字无效。");
        // Accessibility node input does not pass through the helper's frame-generation input gate.
        // Re-read the real frame generation on the worker before node dispatch; no redundant image encoding.
        JSONObject frame;
        try{
            if(!module.session().optBoolean("frameMetadataOnly"))throw new PhoneFailure("metadata_only_unavailable","请更新后台助手后再核对节点操作。");
            frame=request("frame",new JSONObject().put("includeImage",false),control,expected.revision);
        }
        catch(InterruptedException stopped){throw stopped;}
        catch(ShellBridgeClient.StaleRequestException stale){throw stale;}
        catch(PhoneFailure blocked){throw blocked;}
        catch(Exception unavailable){throw new PhoneFailure("background_unavailable","无法核对后台当前画面，未提交节点操作。");}
        if(!frame.optBoolean("ok"))return ObservationReport.annotateFailure(frame);
        if(frame.optBoolean("framePending")||frame.optBoolean("empty")||frame.optInt("displayId",-1)!=displayId||!sessionId.equals(frame.optString("sessionId")))
            return failure("stale_observation","后台帧正在变化或身份已变化，未提交节点操作。");
        String changed=ObservationSafety.frameFailure(expected.frame,expected.width,expected.height,
                frame.optLong("frameGeneration",-1),frame.optInt("width",-1),frame.optInt("height",-1));
        if(changed!=null)return failure(changed,"后台画面或尺寸已变化，未提交节点操作，请重新观察。");
        dev.pocketengine.phone.observation.DelayedInputReadback delayed=new dev.pocketengine.phone.observation.DelayedInputReadback();
        JSONObject actionReceipt=onMain(control,expected.revision,()->{
            guard(control,expected.revision);
            if(current!=expected||expected.control!=control||expected.service!=PhoneAccessibilityService.get())return failure("stale_observation","后台观察属于另一次控制或无障碍会话。");
            Tree actual=tree();
            if(expected.tree.truncated||!actual.signature.equals(expected.tree.signature))return failure("stale_observation","后台节点已变化，请重新观察。");
            AccessibilityForest.Ref ref=expected.tree.forest.refs.get(nodeId);
            if(nodeId.isEmpty()&&"phone_type".equals(tool))for(AccessibilityForest.Ref candidate:expected.tree.forest.refs.values())if(candidate.focused&&candidate.editable){if(ref!=null)return failure("ambiguous_input","请指定后台输入框节点。");ref=candidate;}
            if(ref==null)return failure("stale_node","后台目标节点已失效。");
            AccessibilityNodeInfo target=AccessibilityForest.resolve(PhoneAccessibilityService.get(),displayId,ref);
            if(target==null)return failure("stale_node","后台目标节点已失效。");
            AccessibilityNodeInfo readbackIdentity=null;
            try{
                if(!target.refresh()||!ref.identity.equals(AccessibilityForest.identity(target))||!target.isEnabled()||!target.isVisibleToUser())return failure("stale_node","后台目标节点已变化。");
                if(target.isPassword()&&!privateInput)return failure("requires_user","密码内容请由机主通过私密输入处理。");
                if(privateInput&&!isOwner(control))return failure("requires_user","私密输入需要当前机主接管。");
                if(privateInput){JSONObject rejected=privateInputRejection(target);if(rejected!=null)return rejected;}
                if("phone_type".equals(tool)&&!target.isEditable())return failure("not_editable","该后台节点不可输入。");
                if("phone_click_node".equals(tool)&&!target.isClickable())return failure("not_clickable","请换用可点击节点或画面坐标。");
                int scrollAction="forward".equals(args.optString("direction"))?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                if("phone_scroll_node".equals(tool)&&(!target.isScrollable()||target.getActionList().stream().noneMatch(a->a.getId()==scrollAction)))return failure("not_scrollable","该后台节点未声明此方向滚动。");
                if(!tree().signature.equals(expected.tree.signature))return failure("stale_observation","后台页面已改变。");
                Bundle inputArguments=null;if("phone_type".equals(tool)){inputArguments=new Bundle();inputArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);if(!privateInput)readbackIdentity=AccessibilityNodeInfo.obtain(target);}
                guard(control,expected.revision);final Bundle exactArguments=inputArguments;
                NativePhoneEntry.Admission exact=()->{guard(control,expected.revision);if(admission!=null)admission.check();};
                boolean accepted=NativePhoneEntry.call(control,expected.revision,isOwner(control),exact,()->{clearReferences();return "phone_type".equals(tool)?target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,exactArguments):target.performAction("phone_scroll_node".equals(tool)?scrollAction:AccessibilityNodeInfo.ACTION_CLICK);});
                JSONObject returned=new JSONObject().put("ok",accepted).put("accepted",accepted).put("verified",false).put("displayId",displayId).put("sessionId",sessionId)
                        .put("message",accepted?(privateInput?"主人私密输入请求已接收，请核对目标界面。":"后台节点请求已接收，请重新观察。"):"后台节点未接受请求。");
                if(accepted&&"phone_type".equals(tool))returned.put("nativeTextReadback",privateInput?dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(null,null,true):readTextAfterInput(target,readbackIdentity,ref,text,control,expected.revision));
                if(accepted&&"phone_type".equals(tool)&&!privateInput){final AccessibilityNodeInfo retained=AccessibilityNodeInfo.obtain(target),identity=AccessibilityNodeInfo.obtain(readbackIdentity);final AccessibilityForest.Ref original=ref;
                    delayed.schedule(main,()->readTextAfterInput(retained,identity,original,text,control,expected.revision),()->{retained.recycle();identity.recycle();});}
                return returned;
            }finally{if(readbackIdentity!=null)readbackIdentity.recycle();target.recycle();}
        });
        return delayed.attach(actionReceipt);
    }
    private JSONObject readTextAfterInput(AccessibilityNodeInfo node,AccessibilityNodeInfo identity,AccessibilityForest.Ref ref,String wanted,RunControl control,long revision)throws Exception {
        try{guard(control,revision);if(identity==null||!node.refresh()||!node.equals(identity)||node.getWindowId()!=ref.windowId||!node.isEditable()||!java.util.Objects.equals(node.getViewIdResourceName(),identity.getViewIdResourceName())||!String.valueOf(node.getClassName()).equals(String.valueOf(identity.getClassName()))||!String.valueOf(node.getPackageName()).equals(String.valueOf(identity.getPackageName())))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_native_node_unavailable");
            PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service==null)return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("service_disconnected");AccessibilityNodeInfo scoped=AccessibilityForest.resolve(service,displayId,ref);try{if(scoped==null||!scoped.equals(node))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_display_window_path_unavailable");}finally{if(scoped!=null&&scoped!=node&&scoped!=identity)scoped.recycle();}
            if(node.isPassword())return dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(null,null,true);JSONObject value=dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(node.getText(),wanted,false);guard(control,revision);
            return value.put("phase","immediate_after_dispatch").put("observedAtUptimeMs",SystemClock.uptimeMillis()).put("mechanism","refresh_same_accessibility_node").put("windowId",ref.windowId).put("path",new JSONArray(ref.path)).put("displayId",displayId).put("sessionId",sessionId).put("evidenceOnly",true);
        }catch(Exception unavailable){return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("native_readback_unavailable_after_dispatch");}
    }
    private Tree tree()throws Exception {
        PhoneAccessibilityService service=PhoneAccessibilityService.get();
        if(service==null)throw new PhoneFailure("service_disconnected","无障碍权限已断开，请恢复后继续原任务。");
        JSONObject state=module.session();int width=state.optInt("width"),height=state.optInt("height");
        if(!sameSession(state)||width<=0||height<=0)throw new PhoneFailure("wrong_display","后台显示身份或尺寸已变化。");
        AccessibilityForest forest=AccessibilityForest.read(service,displayId,width,height,()->{
            if(service!=PhoneAccessibilityService.get())throw new PhoneFailure("service_disconnected","无障碍会话已变化。");
            String health=service.observationSafetyFailure();
            if(health!=null)throw new PhoneFailure(health,"手机已锁屏或操作权限已失效，请由机主处理后继续。");
        });
        return new Tree(forest);
    }
    private void session()throws Exception {
        JSONObject state=module.session();
        String failure=ObservationSafety.isolationFailure(displayId,sessionId,state.optInt("displayId",-1),state.optString("sessionId"),
                state.optBoolean("sessionActive"),state.optBoolean("backgroundOnly"),state.optBoolean("focusIsolationRequested"),state.optBoolean("displayFlagsConfirmed"));
        if(failure!=null)throw new PhoneFailure(failure,"后台显示或独立焦点已失效，已阻断操作；不会回落主屏。");
        if(!module.hasSession())throw new PhoneFailure(state.optBoolean("recoveryRequired")?"reconciliation_required":"background_unavailable","后台会话需要机主核对后继续。");
        PhoneAccessibilityService service=PhoneAccessibilityService.get();
        if(service==null)throw new PhoneFailure("service_disconnected","请恢复无障碍权限后继续原任务。");
        String health=service.observationSafetyFailure();if(health!=null)throw new PhoneFailure(health,"手机已锁屏或操作权限已失效，请由机主处理后继续。");
    }
    /** Refresh actual helper flags on the worker before Accessibility actions, which bypass helper input. */
    private void refreshSession(RunControl control,long revision)throws Exception {
        guard(control,revision);
        try{module.connect(isOwner(control)?null:control);}
        catch(InterruptedException stopped){throw stopped;}
        catch(ShellBridgeClient.StaleRequestException stale){throw stale;}
        catch(SecurityException denied){throw new PhoneFailure("permission_denied","后台助手授权已失效，请由机主恢复。");}
        catch(Exception unavailable){throw new PhoneFailure("background_unavailable","无法实时核对后台显示，已阻断操作；不会回落主屏。");}
        guard(control,revision);
    }
    private boolean sameSession(JSONObject state){return displayId==state.optInt("displayId",-1)&&sessionId.equals(state.optString("sessionId"));}
    private void guard(RunControl control,long revision)throws Exception {control.check();Long deadline=DISPATCH_DEADLINE.get();if(deadline!=null&&SystemClock.uptimeMillis()>=deadline)throw new java.util.concurrent.TimeoutException("Background dispatch expired");
        boolean owner=isOwner(control);if(control.isPaused()!=owner||control.pauseRevision()!=revision)throw new PhoneFailure("paused_or_changed","执行控制已变化，请重新观察");
        if(owner){OwnerPermit permit=ownerPermit;if(permit==null||permit.revision!=revision||!module.ownedBy(permit.taskId))throw new SecurityException("人工接管授权已失效");}session();}
    private boolean isOwner(RunControl control){OwnerPermit permit=ownerPermit;return permit!=null&&permit.control==control;}
    private JSONObject request(String command,JSONObject input,RunControl control,long revision)throws Exception {
        guard(control,revision);JSONObject result=module.request(command,input,control,revision,isOwner(control));
        return result.optBoolean("ok")?result:ObservationReport.annotateFailure(result);
    }
    private <T>T onMain(RunControl control,long revision,java.util.concurrent.Callable<T> action)throws Exception {
        long deadline=SystemClock.uptimeMillis()+5000;FutureTask<T> call=new FutureTask<>(()->{DISPATCH_DEADLINE.set(deadline);try{guard(control,revision);return action.call();}finally{DISPATCH_DEADLINE.remove();}});main.post(call);
        try{while(true){control.check();long remaining=deadline-SystemClock.uptimeMillis();if(remaining<=0)throw new java.util.concurrent.TimeoutException("Background UI deadline");try{return call.get(Math.min(100,remaining),TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException waiting){if(remaining<=100)throw waiting;}}}catch(java.util.concurrent.ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new IllegalStateException("Background callback failed",cause);}finally{call.cancel(false);}
    }
    private static int integer(JSONObject input,String key,int min,int max){Object value=input.opt(key);if(!(value instanceof Number))throw new IllegalArgumentException();double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new IllegalArgumentException();return(int)n;}
    static JSONObject privateInputRejection(AccessibilityNodeInfo target)throws Exception {
        return target.isPassword()&&target.isEditable()?null:failure("requires_user","当前私密输入仅支持密码控件；普通明文输入框请由机主自行处理后再继续。");
    }
    private static JSONObject failure(String error,String message)throws Exception{return ObservationReport.annotateFailure(new JSONObject().put("ok",false).put("error",error).put("message",message).put("accepted",false).put("verified",false));}
    private static final class PhoneFailure extends Exception {final String code;PhoneFailure(String code,String message){super(message);this.code=code;}}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("BackgroundPhonePort requires worker thread");}
    private static final class Tree {
        final AccessibilityForest forest;
        final JSONArray nodes;
        final String packageName,signature;
        final boolean truncated,available;
        Tree(AccessibilityForest forest){this.forest=forest;nodes=forest.nodes;packageName=forest.packageName;signature=forest.signature;truncated=forest.truncated;available=forest.available;}
    }
    private static final class Observation {final String id=UUID.randomUUID().toString();final long at=SystemClock.uptimeMillis(),frame,revision;final int width,height;final Tree tree;final RunControl control;final PhoneAccessibilityService service;final boolean imageIncluded;
        Observation(Tree tree,long frame,long revision,int width,int height,RunControl control,boolean imageIncluded){this.tree=tree;this.frame=frame;this.revision=revision;this.width=width;this.height=height;this.control=control;this.service=PhoneAccessibilityService.get();this.imageIncluded=imageIncluded;}}
    private static final class OwnerPermit {final String taskId;final RunControl control;final long revision;OwnerPermit(String taskId,RunControl control,long revision){this.taskId=taskId;this.control=control;this.revision=revision;}}
}
