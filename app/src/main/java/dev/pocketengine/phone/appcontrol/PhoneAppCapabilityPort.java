package dev.pocketengine.phone.appcontrol;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.phone.DeferredPhoneSession;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.access.PhoneAppResources;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact app diagnosis and one reviewed cold start through the original Runner and native phone scope. */
public final class PhoneAppCapabilityPort implements CapabilityPort {
    public static final String STATE="phone_app_state",PREPARE="phone_prepare_cold_start",STOP="phone_force_stop_app",LAUNCH="phone_restart_app";
    private static final Set<String> NAMES=Set.of(STATE,PREPARE,STOP,LAUNCH);
    private final CapabilityPort existing;
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final RunControl original;
    private final PhonePort phone;
    private final DisplayModule module;
    private final PhoneAppRunSession session;
    private final TaskStoreCapabilityAuthority authority;
    private final boolean restartEnabled;
    private record Pending(String operation,NativeToolPlan plan){ }
    private final AtomicReference<Pending> pending=new AtomicReference<>();
    private volatile boolean closed;
    private long turnPolicyRevision;
    private long turnPauseRevision;

    private PhoneAppCapabilityPort(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl original,PhonePort phone,DisplayModule module,boolean restartEnabled)throws Exception {
        this.existing=Objects.requireNonNull(existing);this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);this.original=Objects.requireNonNull(original);this.phone=Objects.requireNonNull(phone);this.module=Objects.requireNonNull(module);
        this.restartEnabled=restartEnabled;session=module.appControlSession(original);turnPolicyRevision=DeferredPhoneSession.policyRevision(phone);turnPauseRevision=original.pauseRevision();
        authority=new TaskStoreCapabilityAuthority(tasks,binding,event->{try{tasks.event(binding.taskId,binding.runId,binding.epoch,event);}catch(Exception unavailable){throw new IllegalStateException("原应用审批事件保存失败",unavailable);}});
        for(String name:NAMES)if(existing.supports(name))throw new IllegalArgumentException("duplicate_app_control_tool");
    }
    public static CapabilityPort bindIfAvailable(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl original,PhonePort selected,DisplayModule module)throws Exception {
        if(module==null||!DeferredPhoneSession.adaptive(selected)||!DeferredPhoneSession.matches(selected,binding.taskId,binding.runId,binding.epoch)||!DeferredPhoneSession.declared(selected))return existing;
        JSONObject task=tasks.get(binding.taskId,false);
        if(task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background"))return existing;
        boolean restart=binding.allowWrites&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))
                &&(!task.has("origin")||task.isNull("origin"))&&!task.has("workProfileRef")&&!task.has("workProfileIntent");
        binding.check(original);return new PhoneAppCapabilityPort(existing,tasks,binding,original,selected,module,restart);
    }
    private boolean owns(String name){return STATE.equals(name)||restartEnabled&&Set.of(PREPARE,STOP,LAUNCH).contains(name);}
    /** Wrapper lifecycle must not acquire the deferred phone or require a physical phone lease. */
    private void checkControl(RunControl control)throws Exception {
        if(closed||control!=original)throw new SecurityException("original_app_control_required");binding.check(control);session.requireLive(control);
    }
    private void check(String tool,RunControl control)throws Exception {
        checkControl(control);
        if(!control.phoneSurfaceActive())throw new SecurityException("original_phone_surface_required");
        tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,tool);
        if(!STATE.equals(tool)&&!restartEnabled)throw new SecurityException("original_phone_write_authority_required");
    }
    private void requireStateRead(JSONObject args,Object rawOperation,RunControl control)throws Exception {
        check(STATE,control);
        // PlanningCapabilityPort may remove internal metadata from ordinary leaf schemas.
        // TaskStore resolves only the one original live claim with these exact arguments.
        JSONObject dispatched=new JSONObject(args.toString());
        if(rawOperation!=null){
            if(!(rawOperation instanceof String)||!((String)rawOperation).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new SecurityException("original_planning_read_required");
            if(!tasks.requireExecutionPlanningRead(binding.taskId,binding.runId,binding.epoch,(String)rawOperation,STATE,args,control))throw new SecurityException("original_planning_read_required");
            dispatched.put("_hostOperationId",rawOperation);
        }
        tasks.requireExecutionPlanningAccess(binding.taskId,binding.runId,binding.epoch,STATE,dispatched,control,ExecutionPlanning.Access.READ);
    }
    private AdaptivePhonePort adapter(RunControl control)throws Exception {return DeferredPhoneSession.require(phone,control);}
    private void scopeCurrent(PhoneAppRunSession.Selection selected,RunControl control)throws Exception {
        session.requireLive(control);if(session.find(selected.appRef)!=selected||selected.expires<=android.os.SystemClock.uptimeMillis())throw new SecurityException("original_app_reference_expired");
        JSONObject target=selected.target();adapter(control).requireAppControlScope(selected.scope(),target.getString("packageName"),target.getInt("userId"),control);
    }
    private void approvedCurrent(PhoneAppRunSession.Selection selected,RunControl control)throws Exception {
        check(PREPARE,control);scopeCurrent(selected,control);
        synchronized(session){if(!selected.consumed||selected.approvalId==null||selected.approvalArgs==null||selected.approvalCapability==null)throw new SecurityException("original_cold_start_approval_required");}
        tasks.requireConsumedPhoneAppApproval(binding.taskId,binding.runId,binding.epoch,selected.approvalId,new JSONObject(selected.approvalArgs),new JSONObject(selected.approvalCapability),control);
    }
    private JSONObject helper(String command,JSONObject payload,PhoneAppRunSession.Selection selected,RunControl control,NativePhoneEntry.Admission gate)throws Exception {
        return module.appControl(command,payload,selected.scope(),control,gate);
    }
    private JSONObject helperCheck(PhoneAppRunSession.Selection selected,RunControl control)throws Exception {
        scopeCurrent(selected,control);JSONObject result=helper("app_restart_check",new JSONObject().put("restartRef",selected.helperRef),selected,control,()->scopeCurrent(selected,control));
        scopeCurrent(selected,control);return result;
    }

    @Override public JSONObject execute(String name,JSONObject supplied,RunControl control)throws Exception {
        if(!owns(name))return existing.execute(name,supplied,control);
        JSONObject args=new JSONObject(Objects.requireNonNull(supplied).toString());Object rawOperation=args.remove("_hostOperationId");validate(name,args);check(name,control);
        try(AutoCloseable retained=control.retainAuthorityCheck(()->tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,name))){
            if(STATE.equals(name))return state(args,rawOperation,control);
            if(PREPARE.equals(name)){
                if(!(rawOperation instanceof String)||((String)rawOperation).isEmpty()||((String)rawOperation).length()>200)throw new SecurityException("original_parent_call_required");
                return prepare(args,(String)rawOperation,control);
            }
            if(!(rawOperation instanceof String)||!((String)rawOperation).matches("[0-9a-f-]{36}"))throw new SecurityException("original_prepared_operation_required");
            return stage(name,args,(String)rawOperation,control);
        }
    }
    private JSONObject state(JSONObject args,Object rawOperation,RunControl control)throws Exception {
        ExecutionPlanning planning=tasks.executionPlanningForRun(binding.taskId,binding.runId,binding.epoch,control);
        ExecutionPlanning.Snapshot phase=planning==null?null:planning.capture();
        NativePhoneEntry.Admission read=()->{requireStateRead(args,rawOperation,control);if(planning!=null)planning.requireCurrent(phase);};
        read.check();
        AdaptivePhonePort adapter=adapter(control);String pkg=args.getString("packageName");int user=args.getInt("userId");
        JSONObject scope=adapter.captureAppControlScope(args.getString("observationId"),pkg,user,control);
        if(!module.isConnected()){
            JSONObject result=localState(pkg,user).put("appControlScope",new JSONObject().put("displayId",scope.getInt("displayId")).put("userId",user));
            try{read.check();adapter.requireAppControlScope(scope,pkg,user,control);}catch(Exception stopped){throw new CapabilityPort.ReceiptAfterStop(control,result,stopped);}return result;
        }
        JSONObject payload=new JSONObject().put("packageName",pkg).put("userId",user);if(args.has("component"))payload.put("component",args.getString("component"));
        JSONObject answer=module.appControl("app_state",payload,scope,control,()->{read.check();adapter.requireAppControlScope(scope,pkg,user,control);});
        if(answer.optBoolean("ok")&&(!pkg.equals(answer.getJSONObject("target").getString("packageName"))||user!=answer.getJSONObject("target").getInt("userId")))throw new SecurityException("app_state_target_mismatch");
        JSONObject result=new JSONObject(answer.toString());result.remove("restartRef");
        if(!restartEnabled)result.put("restartAvailable",false).put("restartUnavailableReason","original_private_owner_phone_write_required");
        try{read.check();adapter.requireAppControlScope(scope,pkg,user,control);}catch(Exception stopped){throw new CapabilityPort.ReceiptAfterStop(control,result,stopped);}
        if(!answer.optBoolean("ok"))return result;
        if(restartEnabled&&answer.optBoolean("restartAvailable")){
            PhoneAppRunSession.Selection selected=session.retain(scope,answer);result.put("appRef",selected.appRef).put("expiresInMs",Math.max(0,selected.expires-android.os.SystemClock.uptimeMillis()));
        }
        return result.put("displayId",scope.getInt("displayId")).put("sourceObservationId",args.getString("observationId")).put("executionAuthorized",false);
    }
    private JSONObject localState(String pkg,int user)throws Exception {
        JSONObject result=new JSONObject().put("ok",true).put("readOnly",true).put("source","local_android_framework")
                .put("packageName",pkg).put("userId",user).put("device",dev.pocketengine.displayhelper.PhoneDeviceState.read(module.hostContext()))
                .put("restartAvailable",false).put("restartUnavailableReason","authenticated_helper_required").put("helperRequired",true).put("automaticProfileFallback",false);
        if(user!=android.os.Process.myUid()/100000)return result.put("application",new JSONObject().put("status","unavailable").put("reason","different_profile_requires_authenticated_helper"));
        try{PackageInfo info=module.hostContext().getPackageManager().getPackageInfo(pkg,0);ApplicationInfo app=info.applicationInfo;
            if(app==null||app.uid/100000!=user)throw new SecurityException("app_profile_mismatch");
            return result.put("application",new JSONObject().put("status","observed").put("uid",app.uid).put("enabled",app.enabled).put("stopped",(app.flags&ApplicationInfo.FLAG_STOPPED)!=0)
                    .put("suspended",(app.flags&ApplicationInfo.FLAG_SUSPENDED)!=0).put("versionCode",info.getLongVersionCode()).put("versionName",info.versionName==null?JSONObject.NULL:info.versionName));
        }catch(android.content.pm.PackageManager.NameNotFoundException invisible){return result.put("application",new JSONObject().put("status","unavailable").put("reason","package_not_visible"));}
    }
    private JSONObject prepare(JSONObject args,String operation,RunControl control)throws Exception {
        PhoneAppRunSession.Selection selected=session.find(args.getString("appRef"));
        if(selected==null)return refusal("app_reference_expired","应用引用已过期，请从当前授权现场读取状态。");
        synchronized(session){if(selected.consumed)return refusal("cold_start_already_consumed","本次冷启动已领取；请核对原操作，不能重放。");}
        tasks.requirePhoneAppRestartBudget(binding.taskId,binding.runId,binding.epoch,control,2);
        // Owner review pauses and clears observations. It may renew only this unconsumed
        // preparation after a new read proves the same original physical and app identity.
        // The immutable review object still selects the same exact TaskStore approval.
        JSONObject reviewScope=selected.reviewScope(),scope=new JSONObject(reviewScope.toString()).put("pauseRevision",control.pauseRevision()),target=selected.target();
        NativePhoneEntry.Admission reviewCurrent=()->{
            check(PREPARE,control);if(session.find(selected.appRef)!=selected||selected.expires<=android.os.SystemClock.uptimeMillis())throw new SecurityException("original_app_reference_expired");
            synchronized(session){if(selected.consumed||selected.stopAttempted||selected.launchAttempted)throw new SecurityException("original_app_review_already_consumed");}
            adapter(control).requireAppControlScope(scope,target.getString("packageName"),target.getInt("userId"),control);
        };
        reviewCurrent.check();
        JSONObject checked=module.appControl("app_state",new JSONObject().put("packageName",target.getString("packageName")).put("userId",target.getInt("userId")).put("component",target.getString("component")),scope,control,reviewCurrent);
        reviewCurrent.check();
        if(!checked.optBoolean("ok")||!checked.optBoolean("restartAvailable")||!checked.optBoolean("targetOnOriginalDisplay")||!"prepared".equals(checked.optString("stage"))
                ||!TaskStore.capabilityFingerprint(target).equals(TaskStore.capabilityFingerprint(checked.getJSONObject("target")))
                ||!TaskStore.capabilityFingerprint(new JSONObject().put("tasks",new JSONArray(selected.tasksJson))).equals(TaskStore.capabilityFingerprint(new JSONObject().put("tasks",checked.getJSONArray("tasks")))))
            return refusal("app_target_changed","准确应用安装、用户资料或原显示任务已变化，未开始冷启动。");
        String currentHelperRef=checked.getString("restartRef");
        if(!currentHelperRef.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||checked.getLong("expiresAtUptimeMs")<selected.expires)throw new SecurityException("original_app_reference_invalid");
        JSONObject capability=new JSONObject().put("feature","phone").put("adapter","exact_app_cold_start").put("requiresOwnerConsent",true)
                .put("target",target).put("tasksToStop",new JSONArray(selected.tasksJson)).put("scope",reviewScope)
                .put("reason",args.getString("reason")).put("stages",new JSONArray(new String[]{"force_stop_exact_package_profile","launch_exact_component_on_original_display"}))
                .put("forceStopEffect","将关闭该应用在准确用户资料内的全部任务和进程；未保存编辑可能丢失，后台服务与通知可能中断。不会清除应用数据。")
                .put("stopThenLaunch",true).put("requiresFreshNativeScopeAfterOwnerReview",true).put("unknownEffectStopsPlan",true).put("interruptionMayLeaveAppStopped",true).put("automaticReplayAllowed",false);
        JSONObject approved=tasks.requireCapabilityApproval(binding.taskId,binding.runId,PREPARE,args,capability);
        if(!approved.optBoolean("approved")){
            tasks.event(binding.taskId,binding.runId,binding.epoch,new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("tool",PREPARE)
                    .put("approvalId",approved.optString("approvalId")).put("arguments",args).put("capability",capability)
                    .put("message","请核对准确应用资料、全部将关闭的任务，以及随后在原显示重新启动的动作；尚未强停。"));
            return refusal("approval_required","需要主人确认这一次完整冷启动，尚未强停或启动。")
                    .put("requiresUser",true).put("approvalId",approved.optString("approvalId")).put("appRef",selected.appRef);
        }
        reviewCurrent.check();
        synchronized(session){
            if(selected.consumed)throw new SecurityException("cold_start_reference_already_consumed");
            selected.consumed=true;selected.dispatchScopeJson=scope.toString();selected.helperRef=currentHelperRef;
            selected.approvalId=approved.getString("approvalId");selected.approvalArgs=args.toString();selected.approvalCapability=capability.toString();
        }
        reserve(selected,control);approvedCurrent(selected,control);
        NativeToolPlan.AppIdentity identity=new NativeToolPlan.AppIdentity(binding.taskId,binding.runId,binding.epoch,scope.getInt("displayId"),scope.getString("sessionId"),selected.appRef,target.getString("packageName"),target.getInt("userId"),target.getString("installIdentity"));
        NativeToolPlan plan=NativeToolPlan.captureApp(operation,control,identity,(index,live)->{
            approvedCurrent(selected,live);if(index==0)tasks.requirePhoneAppRestartBudget(binding.taskId,binding.runId,binding.epoch,live,2);
            JSONObject fresh=helperCheck(selected,live);
            if(!fresh.optBoolean("ok")||!fresh.optBoolean("targetCurrent")||!(index==0?"prepared":"stopped").equals(fresh.optString("stage")))throw new SecurityException("cold_start_phase_no_longer_current");
            synchronized(session){
                if(selected.resolvedStage!=index||index==1&&(selected.stopReceipt==null||!Boolean.TRUE.equals(selected.stopReceipt.opt("forceStopConfirmed"))))throw new SecurityException("original_cold_start_stage_required");
                selected.resolvedStage=index+1;
            }
            return new NativeToolPlan.Resolved(index==0?NativeToolPlan.Tool.APP_FORCE_STOP:NativeToolPlan.Tool.APP_RESTART_LAUNCH,new JSONObject().put("appRef",selected.appRef),()->approvedCurrent(selected,live));
        },()->approvedCurrent(selected,control));
        Pending previous=pending.getAndSet(new Pending(operation,plan));if(previous!=null)previous.plan.close();
        return new JSONObject().put("ok",true).put("status","prepared_cold_start").put("appRef",selected.appRef).put("approvalId",selected.approvalId)
                .put("parentCallId",operation).put("steps",2).put("executionStarted",false).put("reviewPauseRevision",reviewScope.getLong("pauseRevision")).put("dispatchPauseRevision",scope.getLong("pauseRevision"))
                .put("perStepOriginalOperationRequired",true).put("automaticReplayAllowed",false);
    }
    private void reserve(PhoneAppRunSession.Selection selected,RunControl control)throws Exception {
        if(!(module.hostContext().getApplicationContext() instanceof dev.pocketengine.EngineApplication app))throw new SecurityException("original_app_resources_required");
        JSONObject target=selected.target();String owner=binding.taskId+"/"+binding.runId+"/"+binding.epoch;
        PhoneAppResources.Result result=app.resources.phoneApps().await(owner,new AppCopyKey(target.getString("packageName"),target.getInt("userId")),15_000,control,(key,holder)->{
            approvedCurrent(selected,control);JSONObject live=helperCheck(selected,control);return live.optBoolean("ok")&&live.optBoolean("targetCurrent")&&"prepared".equals(live.optString("stage"));
        });
        if(result!=PhoneAppResources.Result.ACQUIRED)throw new SecurityException("original_app_resource_unavailable");
    }
    private JSONObject stage(String name,JSONObject args,String operation,RunControl control)throws Exception {
        PhoneAppRunSession.Selection selected=session.find(args.getString("appRef"));if(selected==null)return refusal("app_reference_expired","原冷启动引用已失效，没有继续动作。");
        boolean stop=STOP.equals(name);approvedCurrent(selected,control);authority.requirePrepared(operation,name,args);
        requireResolvedStage(selected,stop);
        NativePhoneEntry.Admission admission=()->{check(name,control);approvedCurrent(selected,control);authority.requirePrepared(operation,name,args);if(stop)tasks.requirePhoneAppRestartBudget(binding.taskId,binding.runId,binding.epoch,control,1);};
        admission.check();
        synchronized(session){if(stop){if(selected.stopAttempted)throw new SecurityException("original_restart_stop_already_attempted");selected.stopAttempted=true;}else{if(selected.launchAttempted||selected.stopReceipt==null||!Boolean.TRUE.equals(selected.stopReceipt.opt("forceStopConfirmed")))throw new SecurityException("original_restart_launch_not_ready");selected.launchAttempted=true;}}
        JSONObject result;
        try{result=helper(stop?"app_restart_stop":"app_restart_launch",new JSONObject().put("restartRef",selected.helperRef),selected,control,admission);}
        catch(NativePhoneEntry.Refused notEntered){
            result=refusal("app_restart_not_dispatched",stop?"原任务在请求写入前变化，强停未派发。":"原强停已确认，重新启动未派发；应用可能仍停止，请核对原应用。")
                    .put("nativeDispatched",false);if(!stop)result.put("launchDispatched",false);
        }
        catch(Exception unknown){
            result=new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","app_restart_transport_unknown").put("requiresReconciliation",true)
                    .put("message",stop?"强停结果未确认，未继续启动；请核对原应用。":"原强停已确认，启动结果未确认；请核对原显示。")
                    .put("forceStopConfirmed",!stop).put("partial",!stop).put("automaticReplayAllowed",false);
            synchronized(session){if(stop)selected.stopReceipt=result;else selected.launchReceipt=result;}
            phone.clearReferences();
            throw new CapabilityPort.ReceiptAfterStop(control,result.put("operationId",operation).put("appRef",selected.appRef),unknown);
        }
        result=new JSONObject(result.toString());result.remove("restartRef");result.put("operationId",operation).put("appRef",selected.appRef).put("automaticReplayAllowed",false);
        if(!stop){result.put("forceStopConfirmed",true).put("stopOperationId",selected.stopReceipt.optString("operationId"));if(!Boolean.TRUE.equals(result.opt("accepted")))result.put("partial",true);}
        synchronized(session){if(stop)selected.stopReceipt=result;else selected.launchReceipt=result;}
        phone.clearReferences();
        try{check(name,control);scopeCurrent(selected,control);}catch(Exception stopped){throw new CapabilityPort.ReceiptAfterStop(control,result,stopped);}
        return result;
    }
    private void requireResolvedStage(PhoneAppRunSession.Selection selected,boolean stop)throws Exception {
        synchronized(session){
            if(selected.resolvedStage!=(stop?1:2)||(stop?selected.stopAttempted:selected.launchAttempted)
                    ||!stop&&(selected.stopReceipt==null||!Boolean.TRUE.equals(selected.stopReceipt.opt("forceStopConfirmed"))))throw new SecurityException("original_native_restart_stage_required");
        }
    }

    private static JSONObject refusal(String code,String message)throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("error",code).put("message",message).put("automaticReplayAllowed",false);}
    private static JSONObject text(int maximum)throws Exception{return new JSONObject().put("type","string").put("minLength",1).put("maxLength",maximum);}
    private static JSONObject schema(JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","object").put("additionalProperties",false).put("properties",properties).put("required",new JSONArray(required));}
    private static void tool(JSONArray out,String name,String description,JSONObject schema)throws Exception{out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",schema)));}
    private JSONArray append(JSONArray source)throws Exception {
        JSONArray out=new JSONArray(source.toString());
        tool(out,STATE,"只读当前已授权应用的准确用户资料、安装/停止状态、任务显示归属及电量/锁屏/省电状态。需要当前observationId和其中准确packageName/userId。可选component用于存在多个实际launcher入口时消歧。有认证助手且目标全部任务仍在原显示时返回5分钟appRef，供单次完整冷启动审阅；此读取不强停、不启动，不授新权限。",schema(new JSONObject().put("observationId",text(100)).put("packageName",text(250)).put("userId",new JSONObject().put("type","integer").put("minimum",0).put("maximum",21474)).put("component",text(500)),"observationId","packageName","userId"));
        if(restartEnabled){
            tool(out,PREPARE,"为卡住的准确应用准备一次冷启动：appRef必须来自原phone_app_state，reason说明原因。主人完整批准该资料全部任务和进程被强停，再在原显示启动固定组件；未保存编辑可能丢失，不清数据。原Runner分两步prepared/回执执行，只有实际stopped标志且原任务全消失才启动；取消可留下已停止状态，未知不重发。每批最多一个原生计划。",schema(new JSONObject().put("appRef",text(36)).put("reason",text(400)),"appRef","reason"));
            tool(out,STOP,"仅由原phone_prepare_cold_start的第一原生步骤调用；没有该准确appRef的完整主人审批、当前显示归属和原prepared操作即拒绝，不可直接请求任意应用强停。",schema(new JSONObject().put("appRef",text(36)),"appRef"));
            tool(out,LAUNCH,"仅由原冷启动的第二原生步骤调用；须前一步原回执已确认准确资料停止，保持同一审批、运行、暂停版本和显示。只启动一次固定组件，不能重放未知结果。",schema(new JSONObject().put("appRef",text(36)),"appRef"));
        }
        return out;
    }
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!owns(name)){existing.validate(name,args);return;}
        Set<String> keys=STATE.equals(name)?Set.of("observationId","packageName","userId","component"):PREPARE.equals(name)?Set.of("appRef","reason"):Set.of("appRef");
        var names=args.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new IllegalArgumentException("unexpected_app_control_argument");
        if(STATE.equals(name)){
            string(args,"observationId",100);String pkg=string(args,"packageName",250);if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("invalid_package_name");
            Object raw=args.opt("userId");if(!(raw instanceof Number))throw new IllegalArgumentException("exact_user_required");int user;try{user=new java.math.BigDecimal(raw.toString()).intValueExact();}catch(RuntimeException invalid){throw new IllegalArgumentException("exact_user_required");}if(user<0||user>21474)throw new IllegalArgumentException("exact_user_required");
            if(args.has("component")){String rawComponent=string(args,"component",500);android.content.ComponentName component=android.content.ComponentName.unflattenFromString(rawComponent);if(component==null||!pkg.equals(component.getPackageName()))throw new IllegalArgumentException("exact_component_required");}
        }else{if(!string(args,"appRef",36).matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("app_reference_required");if(PREPARE.equals(name))string(args,"reason",400);}
    }
    private static String string(JSONObject args,String key,int max)throws Exception{Object value=args.opt(key);if(!(value instanceof String)||((String)value).isBlank()||((String)value).length()>max)throw new IllegalArgumentException("invalid_"+key);return(String)value;}
    @Override public JSONArray tools()throws Exception{return append(existing.tools());}
    @Override public boolean supports(String name){return owns(name)||existing.supports(name);}
    @Override public boolean sideEffecting(String name){return owns(name)?STOP.equals(name)||LAUNCH.equals(name):existing.sideEffecting(name);}
    @Override public ExecutionPlanning.Access planningAccess(String name,JSONObject args)throws Exception{if(!owns(name))return existing.planningAccess(name,args);if(!STATE.equals(name))return ExecutionPlanning.Access.DENY;if(args!=null){JSONObject exact=new JSONObject(args.toString());exact.remove("_hostOperationId");validate(name,exact);}return ExecutionPlanning.Access.READ;}
    @Override public NativeToolPlan takeToolPlan(String name,String operation,RunControl control)throws Exception {if(!PREPARE.equals(name))return existing.takeToolPlan(name,operation,control);check(name,control);Pending value=pending.get();if(value==null)return null;if(!value.operation.equals(operation)||!pending.compareAndSet(value,null))throw new SecurityException("original_app_plan_required");return value.plan;}
    @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{checkControl(control);existing.beginRun(goal,control);}
    @Override public void prepareTurnSurface(RunControl control)throws Exception{checkControl(control);existing.prepareTurnSurface(control);checkControl(control);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control)throws Exception{checkControl(control);existing.admitCapabilities(snapshot,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{checkControl(control);turnPolicyRevision=DeferredPhoneSession.policyRevision(phone);turnPauseRevision=control.pauseRevision();JSONObject turn=existing.prepareTurn(query,round,steps,control);checkControl(control);return turn.put("tools",append(turn.getJSONArray("tools")));}
    @Override public boolean responseCurrent(RunControl control)throws Exception{checkControl(control);return turnPauseRevision==control.pauseRevision()&&turnPolicyRevision==DeferredPhoneSession.policyRevision(phone)&&existing.responseCurrent(control);}
    @Override public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl control)throws Exception{return !owns(name)&&existing.stepReadSourceCurrent(name,receipt,control);}
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{return owns(name)?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,control);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{return owns(name)?null:existing.takeToolObservation(name,operation,control);}
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl control)throws Exception{return existing.nativeReadContextRepair(messages,control);}
    @Override public void beforeModelRequest(String id,JSONArray messages,RunControl control)throws Exception{existing.beforeModelRequest(id,messages,control);}
    @Override public JSONObject afterModelResponse(String id,String answer,RunControl control)throws Exception{return existing.afterModelResponse(id,answer,control);}
    @Override public void modelRequestAbandoned(String id)throws Exception{existing.modelRequestAbandoned(id);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception {
        if(owns(name)){
            validate(name,args);check(name,control);
            if(STOP.equals(name)||LAUNCH.equals(name)){
                PhoneAppRunSession.Selection selected=session.find(args.getString("appRef"));
                if(selected==null)throw new SecurityException("original_app_reference_expired");approvedCurrent(selected,control);requireResolvedStage(selected,STOP.equals(name));
            }
            if(!STATE.equals(name))dev.pocketengine.features.capabilities.ToolApprovalPermit.requireNativeOwnerReview(binding,name,args,control);
        }
        return existing.beforeTool(name,args,control);
    }
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception{existing.afterTool(name,args,receipt,control);}
    @Override public void endRun(RunControl control)throws Exception{Pending value=pending.getAndSet(null);if(value!=null)value.plan.close();existing.endRun(control);}
    @Override public void close(){if(closed)return;closed=true;Pending value=pending.getAndSet(null);if(value!=null)value.plan.close();existing.close();}
}
