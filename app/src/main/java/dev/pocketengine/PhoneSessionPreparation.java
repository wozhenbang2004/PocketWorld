package dev.pocketengine;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.phone.DeferredPhoneSession;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.PhoneReadiness;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Transactional preparation owned by the existing service/run; no scheduler or permission source. */
final class PhoneSessionPreparation {
    @FunctionalInterface interface Current {void check()throws Exception;}
    private PhoneSessionPreparation() { }
    /** Preserve a failed creation's release barrier even when no physical binding was returned. */
    static boolean cleanupConfirmed(EngineApplication app,DeferredPhoneSession session,String id,String run,int epoch,RunControl original,JSONObject evidence){
        if(session==null||session.originalControl()!=original||!session.matchesHostScope(id,run,epoch)||!session.cleanupUnconfirmed())return true;
        try{evidence.put("phonePreparation",session.initializationEvidence())
                .put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.pending(id,run,epoch));}
        catch(Exception unavailable){app.error("原手机准备的清理证据暂不可保存，请核对原任务；释放未确认");}
        return false;
    }
    static DeferredPhoneSession create(PhoneRunService service,EngineApplication app,String id,String run,int epoch,
            String goal,String mode,ModelConfig config,RunControl original,Consumer<BackgroundPhonePort> backgroundOwner,
            Current owner,dev.pocketengine.features.teams.TeamPhoneHost.Admission team) {
        return new DeferredPhoneSession(id,run,epoch,mode,original,
                config.localCaptureAllowed&&config.localOcrAllowed&&app.localOcr!=null&&app.localOcr.available(),
                ()->new dev.pocketengine.phone.access.AndroidPhoneAccessStore(service).current().revision(),
                (control,revision,ownerPreparation)->{
                    PhonePort physical=null;DisplayModule preparing=null;
                    try {
                        current(app,id,run,epoch,mode,control,revision,owner,ownerPreparation);
                        PhoneReadiness.requireStart(app.phoneReadiness(),mode);
                        if(!app.phoneReadiness().getJSONObject("taskNotifications").optBoolean("ready"))
                            throw new SecurityException("手机操作需要可见任务通知和停止入口");
                        String session;int display;
                        if("background".equals(mode)) {
                            if(!(app.features.module("display") instanceof DisplayModule module))throw new IllegalStateException("后台助手不可用");
                            preparing=module;
                            if(ownerPreparation==null)module.prepare(control);else module.prepareForOwner(control,revision,ownerPreparation);
                            BackgroundPhonePort background=new BackgroundPhonePort(module,control);physical=background;
                            background.bindLifecycle(service,id,run,epoch,control);backgroundOwner.accept(background);
                            JSONObject identity=background.executionIdentity();session=identity.getString("sessionId");display=identity.getInt("displayId");
                        } else {
                            PhoneAccessibilityService connected=PhoneAccessibilityService.get();
                            if(connected==null)throw new IllegalStateException("主屏读取服务已断开，请重新连接");
                            session=connected.observationSessionId();display=android.view.Display.DEFAULT_DISPLAY;
                            if(app.features.module("display") instanceof DisplayModule module)module.pinOwner(control);
                            physical=new AndroidPhonePort(service,live->dev.pocketengine.phone.access.PhoneAccessBindings.primaryOwners(
                                    app.features.module("display") instanceof DisplayModule module?module:null,live));
                            connected.showControls(id,goal,control);
                        }
                        current(app,id,run,epoch,mode,control,revision,owner,ownerPreparation);
                        if(!physical.isConnected()||!app.attachPort(id,physical,control))throw new SecurityException("原手机物理口未获准入");
                        PhonePort source=app.phoneAgentSource(id,control,physical);
                        if(team!=null){team.checkCurrent();source=team.scopedPhone(source);}
                        AdaptivePhonePort adapter=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope(id,run,epoch,session,display),
                                config.localCaptureAllowed,config.localOcrAllowed,config.sendScreenshots,
                                config.usesAccount()?dev.pocketengine.runtime.ModelGateway.accountSupportsImageInput(config.accountModel):config.supportsImageInput,
                                android.os.SystemClock::uptimeMillis,app.localOcr);
                        current(app,id,run,epoch,mode,control,revision,owner,ownerPreparation);
                        if(team==null)app.retainPhonePractice(id,control,adapter);
                        PhonePort retained=physical;
                        return new DeferredPhoneSession.Binding(physical,adapter,()->abort(app,id,run,epoch,control,retained,backgroundOwner));
                    } catch(Exception failure) {
                        if(physical!=null){
                            JSONObject disposition;
                            try{disposition=abort(app,id,run,epoch,control,physical,backgroundOwner);}
                            catch(Exception cleanup){failure.addSuppressed(cleanup);disposition=new JSONObject().put("releaseConfirmed",false).put("error","preparation_cleanup_unknown");}
                            if(failure instanceof InterruptedException||failure instanceof RunControl.ConditionWaitException){failure.addSuppressed(new DeferredPhoneSession.PreparationFailure(new IllegalStateException("原准备清理状态"),disposition));throw failure;}
                            throw new DeferredPhoneSession.PreparationFailure(failure,disposition);
                        }
                        else if(preparing!=null){
                            JSONObject disposition;
                            try{disposition=abortUnbound(app,preparing,id,run,epoch,control);}
                            catch(Exception cleanup){failure.addSuppressed(cleanup);disposition=new JSONObject().put("releaseConfirmed",false).put("error","preparation_cleanup_receipt_unconfirmed");}
                            if(failure instanceof InterruptedException||failure instanceof RunControl.ConditionWaitException){failure.addSuppressed(new DeferredPhoneSession.PreparationFailure(new IllegalStateException("原准备清理状态"),disposition));throw failure;}
                            throw new DeferredPhoneSession.PreparationFailure(failure,disposition);
                        }
                        throw failure;
                    }
                });
    }
    private static void current(EngineApplication app,String id,String run,int epoch,String mode,RunControl original,long revision,Current owner,
                                dev.pocketengine.phone.observation.NativePhoneEntry.Admission ownerPreparation)throws Exception {
        owner.check();original.check();
        if(ownerPreparation!=null)ownerPreparation.check();
        if(original.isPaused()!=(ownerPreparation!=null)||original.pauseRevision()!=revision||!original.phoneSurfaceActive()||app.control(id)!=original
                ||!original.matchesHostRun(id,run,epoch)||!mode.equals(app.mode(id)))throw new SecurityException("原手机准备身份或暂停代次已变化");
    }
    private static JSONObject abort(EngineApplication app,String id,String run,int epoch,RunControl original,PhonePort physical,Consumer<BackgroundPhonePort> backgroundOwner)throws Exception {
        JSONObject receipt=new JSONObject();
        try {
            if(physical instanceof BackgroundPhonePort background){
                JSONObject release=background.closeSession();receipt.put("displayRelease",release)
                        .put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,run,epoch,release));
            } else if(physical instanceof AndroidPhonePort main){main.closeExtensions();PhoneAccessibilityService service=PhoneAccessibilityService.get();if(service!=null)service.hideControls(original);}
        } catch(Exception unknown) {
            if(physical instanceof BackgroundPhonePort){JSONObject release=new JSONObject().put("releaseConfirmed",false).put("error","cleanup_unknown");
                receipt.put("displayRelease",release).put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,run,epoch,release));}
            throw unknown;
        } finally {
            backgroundOwner.accept(null);
            app.detachPhonePort(id,physical,original);
            if(receipt.has("phoneResources"))app.tasks.event(id,new JSONObject(receipt.toString()).put("type","phone_resource_release")
                    .put("runId",run).put("coordinatorEpoch",epoch).put("phoneSessionCleanup",true));
        }
        JSONObject release=receipt.optJSONObject("displayRelease");return release==null?new JSONObject().put("releaseConfirmed",true):release;
    }
    /** A lost create reply has no invented display id. Retain an explicit original-run barrier. */
    private static JSONObject abortUnbound(EngineApplication app,DisplayModule module,String id,String run,int epoch,RunControl original)throws Exception {
        JSONObject release;
        try {
            JSONObject state=module.session(original);String session=state.optString("sessionId");int display=state.optInt("displayId",-1);
            if(!session.isEmpty()&&display>0)release=module.releaseSession(session,display);
            else release=new JSONObject().put("releaseConfirmed",!state.optBoolean("recoveryRequired"))
                    .put("noDisplayIdentityReturned",true).put("automaticRecreationAllowed",false);
        } catch(Exception unknown) {release=new JSONObject().put("releaseConfirmed",false).put("error","preparation_cleanup_unknown").put("automaticRecreationAllowed",false);}
        JSONObject resources=dev.pocketengine.phone.PhoneResourceDisposition.release(id,run,epoch,release);
        app.tasks.event(id,new JSONObject().put("type","phone_resource_release").put("runId",run).put("coordinatorEpoch",epoch)
                .put("preparationAborted",true).put("displayRelease",release).put("phoneResources",resources));
        return release;
    }
}
