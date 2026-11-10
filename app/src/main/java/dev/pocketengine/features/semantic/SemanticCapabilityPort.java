package dev.pocketengine.features.semantic;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** A leaf of the original run, with no planner, task database, retry or implicit GUI fallback. */
public final class SemanticCapabilityPort implements CapabilityPort {
    public static final String DISCOVER="semantic_discover",DISPATCH="semantic_dispatch";
    static final long DISCOVERY_TTL_MS=300_000L;
    public interface Authority {
        void check(String tool,RunControl control) throws Exception;
        void requirePrepared(String operationId,String tool,JSONObject originalArguments) throws Exception;
        default void requirePlanningRead(String operationId,String tool,JSONObject originalArguments,RunControl control)throws Exception {
            throw new SecurityException("此语义发现没有原生只读规划声明");
        }
        JSONObject approve(String tool,JSONObject originalArguments,JSONObject capability) throws Exception;
        default boolean approvedForContinuation(String approvalId,String tool,JSONObject originalArguments,JSONObject capability,RunControl control)throws Exception {return false;}
        default void requireNativeOwnerReview(String tool,JSONObject originalArguments,RunControl control)throws Exception { }
    }
    private final CapabilityPort existing;
    private final RunControl originalControl;
    private final Authority authority;
    private final SemanticPlatform platform;
    private final boolean dispatchEnabled;
    private final LongSupplier clock;
    private final SemanticRunSession session;
    private final boolean ownsSession;
    private volatile boolean closed;
    private volatile long turnPauseRevision;

    public SemanticCapabilityPort(CapabilityPort existing,RunControl originalControl,Authority authority,
            SemanticPlatform platform,boolean dispatchEnabled) throws Exception {
        this(existing,originalControl,authority,platform,dispatchEnabled,()->System.nanoTime()/1_000_000L);
    }
    SemanticCapabilityPort(CapabilityPort existing,RunControl originalControl,Authority authority,
            SemanticPlatform platform,boolean dispatchEnabled,LongSupplier clock) throws Exception {
        this(existing,originalControl,authority,platform,dispatchEnabled,clock,null,true);
    }
    SemanticCapabilityPort(CapabilityPort existing,RunControl originalControl,Authority authority,
            SemanticPlatform platform,boolean dispatchEnabled,SemanticRunSession session) throws Exception {
        this(existing,originalControl,authority,platform,dispatchEnabled,session.clock,session,false);
    }
    private SemanticCapabilityPort(CapabilityPort existing,RunControl originalControl,Authority authority,
            SemanticPlatform platform,boolean dispatchEnabled,LongSupplier clock,SemanticRunSession retained,boolean ownsSession) throws Exception {
        this.existing=existing;this.originalControl=Objects.requireNonNull(originalControl);this.authority=Objects.requireNonNull(authority);
        this.platform=Objects.requireNonNull(platform);this.dispatchEnabled=dispatchEnabled;this.clock=Objects.requireNonNull(clock);
        if(!originalControl.hasHostRun())throw new SecurityException("语义工具必须绑定原生任务执行");
        if(existing!=null&&(existing.supports(DISCOVER)||existing.supports(DISPATCH)))throw new IllegalArgumentException("语义工具名称与既有能力冲突");
        this.ownsSession=ownsSession;session=retained==null?new SemanticRunSession(originalControl,clock):retained;session.requireLive(originalControl);
        turnPauseRevision=originalControl.pauseRevision();
    }
    private boolean own(String name) { return DISCOVER.equals(name)||DISPATCH.equals(name)&&dispatchEnabled; }
    @Override public boolean supports(String name) { return own(name)||existing!=null&&existing.supports(name); }
    @Override public boolean sideEffecting(String name) { return own(name)?DISPATCH.equals(name):existing==null||existing.sideEffecting(name); }
    @Override public dev.pocketengine.core.ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception {
        if(!own(name))return existing==null?dev.pocketengine.core.ExecutionPlanning.Access.DENY:existing.planningAccess(name,arguments);
        if(!DISCOVER.equals(name))return dev.pocketengine.core.ExecutionPlanning.Access.DENY;
        if(arguments!=null){JSONObject checked=new JSONObject(arguments.toString());checked.remove("_hostOperationId");validate(name,checked);}
        return dev.pocketengine.core.ExecutionPlanning.Access.READ;
    }
    private JSONArray ownTools() throws Exception {
        JSONArray out=new JSONArray().put(definition(DISCOVER,"发现真实App入口或日历。calendar_list列出可读日历，每页32项，按nextCalendarId继续；accessLevel>=500才可直接写。calendar_events需要准确calendarId及start/end（最多31天、结束不含），按nextEventId继续，每页至多32个事件或系列，返回原任务5分钟内有效且未经历暂停的eventRef。calendar_update需要eventRef、完整event及recurrenceScope；calendar_delete需要eventRef/recurrenceScope。普通事件或已有例外用event，重复主事件用series，不能把实例时间当单次修改授权。calendar_batch需要准确calendarId和1至50个完整events，可有RRULE；日历写操作全部逐参数审批，实际READ/WRITE_CALENDAR均已授予才可写。calendar_list/calendar_events的profile默认host，可显式work查询Android企业日历只读资料，受管理员和系统设置限制；工作资料不生成可写eventRef，写入需走原任务中对应资料的日历App。calendar_draft仍只打开单事件编辑器。日历start/end为UTC毫秒；全天必须UTC午夜，开始包含、结束不包含。web需要url；app_link需要准确packageName和HTTPS url，仍核OS App Links或浏览器资格；share_text需要packageName/text，接收方可能立即处理，启动不是已发送。show_alarms打开闹钟列表；map_search需要query；dial需要number；email_draft需要to。alarm需要24小时hour/minutes，可含days（SUN至SAT）及布尔vibrate；timer需要seconds（1–86400）。时钟可含单行message，使用设备时区。不接受任意Intent/extra。发现不执行写入，空候选不等于App未安装。",SemanticRequest.discoverySchema()));
        if(dispatchEnabled)out.put(definition(DISPATCH,"执行本次刚发现的准确语义请求，需要原TaskStore具体参数审批与原operation。calendar_batch批准固定日历与完整1至50事件，calendar_update/calendar_delete批准准确事件的原内容及变化范围，再调用一次带原版本断言的系统批处理，返回URI及读回。日历approval_required且accepted=false后，主人批准并恢复原run时使用同一discoveryId/candidateId；重传完全相同的discover请求可在原审批仍有效且目标完整版本未变化时返回同一ID，不恢复旧eventRef、不延长有效期。未知、已派发或回执不全不得重发。calendar_list/calendar_events只读不可派发。其他类型打开固定App入口，启动回执不代表业务成功。calendar_draft、拨号和邮件交主人完成；闹钟/计时可能立即生效，SKIP_UI=false不保证另有确认，派发一次后主人核验。",dispatchSchema()));
        return out;
    }
    private static JSONObject definition(String name,String description,JSONObject schema) throws Exception {
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",schema));
    }
    private static JSONObject dispatchSchema() throws Exception {
        return new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("discoveryId",new JSONObject().put("type","string").put("maxLength",36))
                .put("candidateId",new JSONObject().put("type","string").put("maxLength",64)))
                .put("required",new JSONArray(new String[]{"discoveryId","candidateId"})).put("additionalProperties",false);
    }
    private static void validateDispatch(JSONObject args) throws Exception {
        SemanticJson.keys(args,Set.of("discoveryId","candidateId"));SemanticJson.uuid(SemanticJson.text(args,"discoveryId",36,false));
        if(!SemanticJson.text(args,"candidateId",64,false).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("入口候选引用无效");
    }
    @Override public JSONArray tools() throws Exception { return append(existing==null?new JSONArray():existing.tools()); }
    private JSONArray append(JSONArray base) throws Exception {
        JSONArray out=new JSONArray(base.toString()),mine=ownTools();Set<String> seen=new HashSet<>();
        for(int i=0;i<out.length();i++)seen.add(out.getJSONObject(i).getJSONObject("function").getString("name"));
        for(int i=0;i<mine.length();i++) {JSONObject tool=mine.getJSONObject(i);if(!seen.add(tool.getJSONObject("function").getString("name")))throw new IllegalStateException("语义工具快照名称重复");out.put(tool);}
        return out;
    }
    @Override public JSONObject contextSnapshot(String query) throws Exception { return existing==null?new JSONObject():existing.contextSnapshot(query); }
    @Override public void beginRun(String goal,RunControl control) throws Exception { checkControl(control);if(existing!=null)existing.beginRun(goal,control); }
    @Override public void prepareTurnSurface(RunControl control)throws Exception {checkControl(control);if(existing!=null)existing.prepareTurnSurface(control);checkControl(control);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control) throws Exception { checkControl(control);if(existing!=null)existing.admitCapabilities(snapshot,control); }
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control) throws Exception {
        checkControl(control);turnPauseRevision=control.pauseRevision();
        if(existing==null)return new JSONObject().put("tools",ownTools()).put("reference",new JSONObject());
        JSONObject turn=SemanticJson.copy(existing.prepareTurn(query,round,steps,control));return turn.put("tools",append(turn.getJSONArray("tools")));
    }
    @Override public boolean responseCurrent(RunControl control) throws Exception {
        checkControl(control);return turnPauseRevision==control.pauseRevision()&&(existing==null||existing.responseCurrent(control));
    }
    @Override public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl control)throws Exception{checkControl(control);return !own(name)&&existing!=null&&existing.stepReadSourceCurrent(name,receipt,control);}
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{checkControl(control);return own(name)||existing==null?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,control);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{checkControl(control);return own(name)||existing==null?null:existing.takeToolObservation(name,operation,control);}
    @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String name,String operation,RunControl control)throws Exception{checkControl(control);return own(name)||existing==null?null:existing.takeToolPlan(name,operation,control);}
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl control)throws Exception{checkControl(control);return existing==null?null:existing.nativeReadContextRepair(messages,control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{checkControl(control);if(existing!=null)existing.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{checkControl(control);return existing==null?null:existing.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{if(existing!=null)existing.modelRequestAbandoned(requestId);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control) throws Exception {
        if(own(name))check(name,control);
        JSONObject prior=existing==null?null:existing.beforeTool(name,args,control);if(prior!=null)return prior;
        if(DISPATCH.equals(name)&&dispatchEnabled){
            JSONObject exact=SemanticJson.copy(args);exact.remove("_hostOperationId");validateDispatch(exact);
            Discovery discovery=session.find(exact.getString("discoveryId"));
            if(discovery!=null&&!discovery.consumed&&discovery.request.calendarWrite()&&discovery.targets.containsKey(exact.getString("candidateId")))
                authority.requireNativeOwnerReview(name,exact,control);
        }
        return null;
    }
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control) throws Exception { if(existing!=null)existing.afterTool(name,args,receipt,control); }
    @Override public void endRun(RunControl control) throws Exception { if(existing!=null)existing.endRun(control); }
    @Override public void validate(String name,JSONObject args) throws Exception {
        if(DISCOVER.equals(name))SemanticRequest.parse(args);
        else if(DISPATCH.equals(name)&&dispatchEnabled)validateDispatch(args);
        else if(existing!=null)existing.validate(name,args);else throw new SecurityException("语义工具未挂载");
    }
    @Override public synchronized JSONObject execute(String name,JSONObject input,RunControl control) throws Exception {
        if(!own(name)) {if(existing==null)throw new SecurityException("语义工具未挂载");return existing.execute(name,input,control);}
        JSONObject args=SemanticJson.copy(input==null?new JSONObject():input);Object operation=args.opt("_hostOperationId");args.remove("_hostOperationId");
        if(DISCOVER.equals(name)) {
            SemanticRequest request=SemanticRequest.parse(args);check(name,control);
            if(operation!=null){
                if(!(operation instanceof String))throw new SecurityException("发现入口需要原只读规划声明");
                SemanticJson.uuid((String)operation);authority.requirePlanningRead((String)operation,name,SemanticJson.copy(args),control);
            }
            JSONObject result;
            try{result=discover(request,control);}catch(CalendarProviderSemanticPlatform.PermissionRequired missing){result=calendarPermissionRequired(missing);}
            catch(CalendarProviderSemanticPlatform.EventChanged changed){result=refusal("stale_calendar_event",changed.getMessage()).put("nextAction","rediscover_calendar_events_or_use_selected_calendar_app");}
            catch(CalendarProviderSemanticPlatform.Unchanged unchanged){result=new JSONObject().put("ok",true).put("readOnly",true).put("dispatchAvailable",false).put("status","unchanged").put("code","calendar_no_changes").put("message",unchanged.getMessage());}
            catch(CalendarProviderSemanticPlatform.WorkProfileUnavailable unavailable){result=refusal("calendar_work_profile_unavailable",unavailable.getMessage())
                    .put("readOnly",true).put("dispatchAvailable",false).put("writesUnsupported",true).put("scope","managed_profile_enterprise_read_only")
                    .put("failureType",unavailable.failureType).put("nextAction","select_matching_work_profile_calendar_app_in_original_phone_task");}
            try{check(name,control);if(operation!=null)authority.requirePlanningRead((String)operation,name,SemanticJson.copy(args),control);}
            catch(Exception stopped){throw new CapabilityPort.ReceiptAfterStop(control,result,stopped);}
            return result;
        }
        validateDispatch(args);if(!(operation instanceof String))throw new SecurityException("需要原AgentRunner登记的operation");String operationId=(String)operation;SemanticJson.uuid(operationId);
        if(session.uncertain(operationId))return unknown(operationId);
        Discovery discovery=session.find(args.getString("discoveryId"));
        if(discovery==null||discovery.consumed||clock.getAsLong()-discovery.created>=DISCOVERY_TTL_MS)
            return refusal("stale_semantic_discovery","入口引用已失效，请重新发现并检查当前目标");
        SemanticPlatform.Target target=discovery.targets.get(args.getString("candidateId"));
        if(target==null)return refusal("stale_semantic_candidate","候选不属于当前发现，尚未执行");
        final long pauseRevision=control.pauseRevision();final JSONObject resource;
        try {
            check(name,control);authority.requirePrepared(operationId,name,SemanticJson.copy(args));
            requireCurrent(discovery.request,target,control);resource=platform.resourceIdentity(discovery.request);
        } catch(CalendarProviderSemanticPlatform.PermissionRequired missing){return calendarPermissionRequired(missing);}
        catch(Exception refused) { return refusal("needs_user","原任务、入口或资源已变化，尚未派发；请核对后继续").put("requiresPause",true); }
        JSONObject capability=capability(discovery.request,target,resource);
        if(discovery.request.calendarWrite()&&discovery.pauseRevision!=pauseRevision){
            try{rebindApprovedCalendar(args,discovery,capability,control,pauseRevision);}
            catch(Exception stale){return refusal("stale_semantic_discovery","暂停前的日历请求只能在原准确审批仍有效且目标版本未变化时续接；请重新读取事件并确认范围，尚未派发");}
        }
        JSONObject approval;
        try {approval=authority.approve(name,SemanticJson.copy(args),capability);}
        catch(Exception refused) {return refusal("needs_user","具体入口审批未获确认，尚未派发").put("requiresPause",true);}
        if(!approval.optBoolean("approved")){
            if(discovery.request.calendarWrite())session.rememberCalendarApproval(args,discovery,capability,approval);
            return refusal("approval_required",discovery.request.calendarWrite()?"需要主人确认当前资料、准确日历、完整事件及修改或删除范围，尚未写入":"需要主人确认固定App、入口参数和当前执行位置，尚未执行")
                    .put("approvalId",approval.optString("approvalId")).put("approvalState",approval.optString("state","pending"));
        }
        SemanticPlatform.Admission scopeGate=()->{
            check(name,control);
            if(pauseRevision!=control.pauseRevision())throw new SecurityException("执行经历暂停，旧决定失效");
            authority.requirePrepared(operationId,name,SemanticJson.copy(args));
            if(!SemanticJson.hash(resource).equals(SemanticJson.hash(platform.resourceIdentity(discovery.request))))throw new SecurityException("语义入口资源已替换");
        };
        SemanticPlatform.Admission gate=new SemanticPlatform.Admission(){
            @Override public void check()throws Exception{scopeGate.check();requireCurrent(discovery.request,target,control);}
            @Override public void afterDispatch()throws Exception{scopeGate.check();}
        };
        try { gate.check(); } catch(Exception refused) { return refusal("needs_user","审批后原任务、入口或资源已变化，尚未派发").put("requiresPause",true); }
        try {if(!session.claim(operationId,args.getString("discoveryId"),discovery))return unknown(operationId);}
        catch(Exception stale){return refusal("needs_user","原发现已失效或已派发，请核对原操作").put("requiresPause",true);}
        JSONObject result;
        try { result=platform.dispatch(discovery.request,target,control,gate); }
        catch(Exception uncertain) { session.markUncertain();return unknown(operationId); }
        if(result==null||!(result.opt("accepted") instanceof Boolean)||"unknown".equals(result.optString("status"))) {
            session.markUncertain();JSONObject out=unknown(operationId);if(result!=null)out.put("nativeResult",SemanticJson.copy(result));
            if(discovery.request.calendarWrite()){
                out.put("message","日历创建、修改或删除可能已经发生，请核对原请求和已返回的URI；不会自动重发或改走编辑器补做。");
                if(result!=null){if(result.has("items"))out.put("items",new JSONArray(result.getJSONArray("items").toString()));if(result.has("calendar"))out.put("calendar",SemanticJson.copy(result.getJSONObject("calendar")));}
            }
            return out;
        }
        boolean accepted=result.getBoolean("accepted");
        JSONObject out=new JSONObject().put("ok",accepted).put("accepted",accepted).put("verified",false).put("verifiedBusinessSuccess",false)
                .put("operationId",operationId).put("status",accepted?"launch_requested":"not_dispatched")
                .put("code",accepted?"semantic_launch_accepted":result.optString("code","semantic_not_dispatched"))
                .put("target",target.description()).put("followup",discovery.request.followup()).put("resource",resource).put("nativeResult",SemanticJson.copy(result))
                .put("nextObservationAction",accepted?"observe":"none").put("requiresPostconditionCheck",accepted)
                .put("automaticFallback",false).put("message",accepted?"Android已接受打开入口；请观察目标App核验实际页面，尚未确认业务完成":"Android入口未派发，请重新检查phone/DOM路径");
        if(result.optBoolean("requiresPause"))out.put("requiresPause",true);
        if(discovery.request.calendarWrite()){
            out.put("status",result.getString("status")).put("code",result.getString("code")).put("message",result.optString("message"))
                    .put("nextObservationAction","none").put("requiresPostconditionCheck",result.optBoolean("requiresPostconditionCheck"))
                    .put("providerReadbackVerified",result.optBoolean("providerReadbackVerified")).put("cloudSyncVerified",false)
                    .put("items",new JSONArray(result.getJSONArray("items").toString())).put("calendar",SemanticJson.copy(result.getJSONObject("calendar")))
                    .put("automaticReplayAllowed",false).put("scope","current_host_android_profile");
            if(result.has("createdCount"))out.put("createdCount",result.getInt("createdCount"));
            for(String key:new String[]{"changedCount","action","recurrenceScope","before"})if(result.has(key))out.put(key,result.get(key));
            if(result.has("terminal"))out.put("terminal",result.getBoolean("terminal"));
            if(result.optBoolean("requiresOwnerVerification"))out.put("requiresOwnerVerification",true);
        }
        if(accepted&&discovery.request.kind.equals("calendar_draft")){
            boolean allDay=discovery.request.parameters().getBoolean("allDay");
            out.put("requiresPause",true).put("requiresOwnerCompletion",true).put("savedEventVerified",false).put("timeZoneVerified",false)
                    .put("requestedTimeZone",discovery.request.parameters().getString("timeZone")).put("requestedAllDay",allDay)
                    .put("message",allDay?"已请求打开全天日历编辑器，请主人核对开始日期、不计入日程的结束日期和保存结果；日期按UTC午夜传递，部分编辑器退出时也可能保存。尚未确认事件已创建，不会代点保存或自动重开。":"已请求打开日历编辑器，请主人核对时间、时区和保存结果；部分编辑器退出时也可能保存。尚未确认事件已创建，不会代点保存或自动重开。");
            if(allDay)out.put("allDayDatesVerified",false).put("dateBoundary","utc_midnight_end_exclusive");
        }
        if(accepted&&discovery.request.clock())out.put("requiresPause",true).put("requiresOwnerVerification",true).put("clockStateVerified",false)
                .put("deviceTimeZone",target.identity().getString("deviceTimeZone")).put("receiverMayApplyImmediately",true)
                .put("message","已发送一次时钟请求，请在目标App核对闹钟或计时器。它可能已立即生效；显示界面不保证另有确认。尚未核实实际设置结果，不会自动重发。");
        if(accepted&&discovery.request.kind.equals("alarm")&&result.optJSONObject("nextAlarmClock")!=null)
            out.put("nextAlarmClock",SemanticJson.copy(result.getJSONObject("nextAlarmClock")))
                    .put("alarmEvidenceScope","仅观察当前Android用户由系统公开的下一次闹钟；变化不能归因于本请求，未变化也不能证明闹钟未创建，请主人核对具体时间、重复星期和震动设置");
        if(accepted&&(discovery.request.kind.equals("app_link")||discovery.request.kind.equals("share_text")))out.put("requiresPause",true).put("requiresOwnerCompletion",true).put("receiverMayActImmediately",true)
                .put("externalDeliveryVerified",false).put("automaticContinuation",false)
                .put("message","已请求把固定入口内容交给选定App；接收方可能立即处理，启动不等于业务完成或消息已发送。后续交主人核验，不会自动点发送、重开或回落其他App。");
        try {check(name,control);}catch(Exception invalidated){out.put("requiresPause",true).put("code","needs_user").put("executionInvalidatedAfterDispatch",true);}
        return out;
    }
    private void requireCurrent(SemanticRequest request,SemanticPlatform.Target target,RunControl control) throws Exception {
        platform.requireCurrent(request,target,control);
    }
    private static JSONObject capability(SemanticRequest request,SemanticPlatform.Target target,JSONObject resource)throws Exception {
        JSONObject capability=new JSONObject().put("feature","semantic").put("adapter",request.kind)
                .put("request",request.parameters()).put("requestDigest",request.digest())
                .put("target",target.description()).put("targetIdentity",target.identity()).put("resource",resource)
                .put("verifiedBusinessSuccess",false).put("followup",request.followup());
        if(request.kind.equals("calendar_draft")){
            capability.put("receiverMaySaveAutomatically",true).put("requiresOwnerCompletion",true).put("timeZoneIsRequestedHint",true)
                    .put("requestedAllDay",request.parameters().getBoolean("allDay"));
            if(request.parameters().getBoolean("allDay"))capability.put("dateBoundary","utc_midnight_end_exclusive");
        }
        if(request.calendarWrite())capability.put("writesCalendarProvider",true).put("hostProfileOnly",true).put("requiresOwnerConsent",true)
                .put("eventCount",request.kind.equals("calendar_batch")?request.parameters().getJSONArray("events").length():1).put("automaticReplayAllowed",false);
        if(request.clock())capability.put("receiverMayApplyImmediately",true).put("requiresOwnerVerification",true)
                .put("skipUi",false).put("separateConfirmationGuaranteed",false).put("deviceTimeZone",target.identity().getString("deviceTimeZone"));
        if(request.kind.equals("app_link")||request.kind.equals("share_text"))capability.put("receiverMayActImmediately",true).put("requiresOwnerCompletion",true).put("automaticContinuation",false);
        return capability;
    }
    /** Only an already reviewed, never-entered call may cross the owner's approval pause. */
    private void rebindApprovedCalendar(JSONObject args,Discovery discovery,JSONObject capability,RunControl control,long pause)throws Exception {
        PendingCalendarApproval pending=session.calendarApproval(args.getString("discoveryId"),discovery);
        if(pending==null||!pending.argumentsHash.equals(SemanticJson.hash(args))||!pending.capabilityHash.equals(SemanticJson.hash(capability))
                ||!authority.approvedForContinuation(pending.approvalId,DISPATCH,SemanticJson.copy(args),capability,control))
            throw new SecurityException("原日历审批未获准确批准或已失效");
        check(DISPATCH,control);session.rebindCalendar(args.getString("discoveryId"),discovery,pending,pause);
    }
    private JSONObject calendarContinuation(SemanticRequest request,RunControl control)throws Exception {
        Map<String,Discovery> pending=session.calendarContinuations(request);String selectedId=null;Discovery selected=null;SemanticPlatform.Target selectedTarget=null;JSONObject selectedCapability=null;
        long pause=control.pauseRevision();
        for(Map.Entry<String,Discovery> entry:pending.entrySet()){
            Discovery discovery=entry.getValue();PendingCalendarApproval approval=session.calendarApproval(entry.getKey(),discovery);if(approval==null)continue;
            SemanticPlatform.Target target=discovery.targets.get(approval.candidateId);if(target==null)continue;
            JSONObject args=new JSONObject().put("discoveryId",entry.getKey()).put("candidateId",approval.candidateId);
            // This re-reads the provider, calendar, event, reminders, attendees and exceptions.
            try{requireCurrent(request,target,control);}
            catch(CalendarProviderSemanticPlatform.PermissionRequired missing){throw missing;}
            catch(SecurityException changed){check(DISCOVER,control);continue;}
            JSONObject capability=capability(request,target,platform.resourceIdentity(request));
            if(!approval.argumentsHash.equals(SemanticJson.hash(args))||!approval.capabilityHash.equals(SemanticJson.hash(capability))
                    ||!authority.approvedForContinuation(approval.approvalId,DISPATCH,args,capability,control))continue;
            if(selected!=null)throw new CalendarProviderSemanticPlatform.EventChanged("有多个准确日历审批，请使用主人所批原回执的discoveryId/candidateId，不能猜测续接对象");
            selectedId=entry.getKey();selected=discovery;selectedTarget=target;selectedCapability=capability;
        }
        if(selected==null)return null;
        JSONObject args=new JSONObject().put("discoveryId",selectedId).put("candidateId",selectedTarget.fingerprint);
        rebindApprovedCalendar(args,selected,selectedCapability,control,pause);
        return new JSONObject().put("ok",true).put("status","discovered").put("discoveryId",selectedId)
                .put("request",request.parameters()).put("requestDigest",request.digest()).put("candidates",new JSONArray().put(selectedTarget.description().put("candidateId",selectedTarget.fingerprint)))
                .put("expiresInMs",Math.max(0,DISCOVERY_TTL_MS-(clock.getAsLong()-selected.created))).put("dispatchAvailable",dispatchEnabled)
                .put("scope","current_host_android_profile").put("readOnly",true).put("verified",false).put("automaticFallback",false)
                .put("approvalContinuation",true).put("approvalNotConsumed",true).put("eventReferenceRenewed",false)
                .put("message","已重读并核对主人批准的原日历请求，资料、目标及内容版本完全一致。请使用本回执的原discoveryId/candidateId派发一次；未延长有效期，旧eventRef不能用于其他请求。");
    }
    private JSONObject discover(SemanticRequest request,RunControl control) throws Exception {
        if(request.calendarWrite()){JSONObject continuation=calendarContinuation(request,control);if(continuation!=null)return continuation;}
        long pause=control.pauseRevision();
        SemanticPlatform.Target source=null;
        if(request.calendarMutation()){
            try{source=session.calendarEvent(request.parameters().getString("eventRef"));}
            catch(SecurityException stale){throw new CalendarProviderSemanticPlatform.EventChanged(stale.getMessage());}
        }
        List<SemanticPlatform.Target> found=platform.discover(request,source,control);check(DISCOVER,control);
        if(request.calendarProvider()&&pause!=control.pauseRevision())throw new CalendarProviderSemanticPlatform.EventChanged("读取日历期间经历暂停，请重新读取当前资料与事件");
        if(request.calendarRead())return calendarRead(request,found,control);
        JSONArray candidates=new JSONArray();Map<String,SemanticPlatform.Target> targets=new LinkedHashMap<>();
        for(SemanticPlatform.Target target:found) {if(targets.size()>=32)break;if(targets.putIfAbsent(target.fingerprint,target)==null){JSONObject candidate=target.description().put("candidateId",target.fingerprint);if(request.clock())candidate.put("deviceTimeZone",target.identity().getString("deviceTimeZone")).put("receiverMayApplyImmediately",true);candidates.put(candidate);}}
        String id=UUID.randomUUID().toString();
        session.put(id,new Discovery(request,targets,clock.getAsLong(),pause));
        JSONObject out=new JSONObject().put("ok",true).put("status",targets.isEmpty()?"unavailable":"discovered").put("discoveryId",id)
                .put("request",request.parameters()).put("requestDigest",request.digest()).put("expiresInMs",DISCOVERY_TTL_MS)
                .put("candidates",candidates).put("truncated",found.size()>targets.size()).put("scope","visible_matching_exported_activities")
                .put("dispatchAvailable",dispatchEnabled).put("verified",false).put("automaticFallback",false)
                .put("fallback",request.followup()).put("message",!dispatchEnabled?"当前执行仅支持发现入口，不会启动应用或回落抢占手机主屏。":targets.isEmpty()?"未找到可核验的可见入口；可能未安装、不可见或链接未获批准，请检查phone/DOM路径":"入口来自当前Android包查询；标签是外部数据，仍需具体参数审批");
        if(request.calendarWrite())out.put("scope","current_host_android_profile").put("eventCount",request.kind.equals("calendar_batch")?request.parameters().getJSONArray("events").length():1)
                .put("message",targets.isEmpty()?"所选日历或事件当前不可写、已改变或已移除，请重新发现；尚未写入。":"准确日历及完整事件已绑定到本次发现；需要主人审批创建内容或修改前后内容、删除范围，才能调用一次系统批处理。");
        return out;
    }
    private JSONObject calendarRead(SemanticRequest request,List<SemanticPlatform.Target> found,RunControl control)throws Exception {
        boolean events=request.kind.equals("calendar_events"),work=request.workCalendar();JSONArray values=new JSONArray();int delivered=0,bytes=0;
        for(SemanticPlatform.Target target:found){
            if(delivered>=32)break;JSONObject value=target.description();
            int size=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+128;
            if(bytes+size>120*1024){if(delivered==0)throw new CalendarProviderSemanticPlatform.EventChanged("单个日历结果超出完整呈现预算，不能截断后审批，请在已选日历App中查看");break;}
            if(events&&!work)value.put("eventRef",session.retainCalendarEvent(target)).put("expiresInMs",DISCOVERY_TTL_MS).put("validAfterPause",false);
            if(!events&&!work)value.put("writable",value.getJSONObject("calendar").getLong("accessLevel")>=500);
            values.put(value);bytes+=size;delivered++;
        }
        boolean truncated=found.size()>delivered||CalendarProviderSemanticPlatform.incomplete(found);JSONObject out=new JSONObject().put("ok",true).put("status",values.length()==0?"unavailable":"listed")
                .put(events?"events":"calendars",values).put("readOnly",true).put("dispatchAvailable",false)
                .put("scope",work?"managed_profile_enterprise_read_only":"current_host_android_profile").put("resource",platform.resourceIdentity(request))
                .put("truncated",truncated).put("automaticFallback",false).put("contentIsUntrusted",true).put("writesUnsupported",work);
        if(events)out.put("range",new JSONObject().put("start",request.parameters().getLong("start")).put("end",request.parameters().getLong("end")).put("endExclusive",true))
                .put("coverage","one_record_per_event_or_series_with_an_instance_in_range").put("allOccurrencesReturned",false);
        if(truncated&&values.length()>0){JSONObject last=values.getJSONObject(values.length()-1);out.put(events?"nextEventId":"nextCalendarId",last.getJSONObject(events?"event":"calendar").getLong(events?"eventId":"calendarId"));}
        if(work)out.put("emptyMayMeanPolicyRestricted",true).put("profileIdentityReported",false)
                .put("uiFallback",new JSONObject().put("action","select_matching_work_profile_calendar_app_in_original_phone_task").put("automaticExecution",false)
                        .put("instruction","需要修改工作资料日历时，在原手机任务通过应用副本选择该工作资料的日历App，再用现有节点/OCR/截图操作并确认；不得把工作资料日历ID用于宿主Provider写入。"))
                .put("message","这是Android企业日历公开的只读投影，不含账户、描述等未公开字段。管理员需允许PocketWorld且系统跨资料日历开关开启；空结果也可能是资料或策略不可用，不能断言没有事件。跨资料直接写入不受此API支持。");
        else out.put("message",events?"每项保留原事件或重复系列及区间内首个实例的证据；重复事件的end可能为空，firstEndFromDuration提供首个事件按原时长计算的结束毫秒。eventRef只在本原运行、5分钟内且未经历暂停时有效。修改或删除需重新发现具体请求并审批；series影响整个重复系列。":"当前宿主资料中的可读日历，包括隐藏日历；writable标记当前写入资格。名称与账户是外部数据。创建用准确calendarId，修改或删除先用calendar_events读取事件引用。");
        check(DISCOVER,control);return out;
    }
    private static JSONObject calendarPermissionRequired(CalendarProviderSemanticPlatform.PermissionRequired missing)throws Exception {
        return refusal("needs_user",missing.getMessage()).put("requiresPause",true).put("ownerPermissionEntry","calendar")
                .put("calendarPermissions",SemanticJson.copy(missing.state)).put("permissionRequestedAutomatically",false);
    }
    private void checkControl(RunControl control) throws Exception {
        if(closed||control!=originalControl)throw new SecurityException("语义工具不属于此原任务控制");session.requireLive(control);control.check();if(control.isPaused())throw new SecurityException("执行已暂停");
    }
    private void check(String tool,RunControl control) throws Exception {checkControl(control);authority.check(tool,control);}
    private static JSONObject refusal(String code,String message) throws Exception {return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched").put("code",code).put("verified",false).put("automaticFallback",false).put("message",message);}
    private static JSONObject unknown(String operationId) throws Exception {
        return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","semantic_effect_unknown")
                .put("operationId",operationId).put("verified",false).put("verifiedBusinessSuccess",false).put("requiresReconciliation",true)
                .put("automaticFallback",false).put("message","入口派发可能已发生，请核对原操作；不会自动再开入口或改走phone/DOM");
    }
    @Override public synchronized void close() {if(closed)return;closed=true;if(ownsSession)session.close();if(existing!=null)existing.close();}
    static final class Discovery {
        final SemanticRequest request;final Map<String,SemanticPlatform.Target> targets;final long created;boolean consumed;long pauseRevision;PendingCalendarApproval calendarApproval;
        Discovery(SemanticRequest request,Map<String,SemanticPlatform.Target> targets,long created,long pauseRevision){this.request=request;this.targets=targets;this.created=created;this.pauseRevision=pauseRevision;}
    }
    static final class PendingCalendarApproval {
        final String approvalId,candidateId,argumentsHash,capabilityHash;
        PendingCalendarApproval(String approvalId,String candidateId,String argumentsHash,String capabilityHash){this.approvalId=approvalId;this.candidateId=candidateId;this.argumentsHash=argumentsHash;this.capabilityHash=capabilityHash;}
    }
}
