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
        JSONObject approve(String tool,JSONObject originalArguments,JSONObject capability) throws Exception;
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
        JSONArray out=new JSONArray().put(definition(DISCOVER,"发现真实可处理入口的App。web需要url；app_link需要准确packageName和HTTPS url，仍核OS App Links或浏览器处理资格，不接受自定义scheme；share_text需要packageName/text，仅传有界纯文本给选定App，接收方可能立即处理，启动不是已发送。两者都需原逐次审批并在一次启动后交主人核验。show_alarms打开闹钟列表；map_search需要query；dial需要number；email_draft需要to；calendar_draft需要title/start/end/timeZone，start/end为UTC毫秒，可含location/description；alarm需要24小时hour和minutes；timer需要seconds（1–86400），可含单行message。闹钟使用实际设备时区，不接受任意Intent/extra。发现不执行；空候选不代表App未安装。",SemanticRequest.discoverySchema()));
        if(dispatchEnabled)out.put(definition(DISPATCH,"请求打开本执行刚发现的固定App入口；需要原TaskStore具体参数审批与原operation。回执仅代表Android接受启动，随后需核验。拨号、邮件和日历交给用户完成；日历不代点保存。闹钟/计时请求可能立即生效，SKIP_UI=false不保证另有确认；仅在同意固定参数后派发一次，主人须核对实际结果，未知不重发。",dispatchSchema()));
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
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control) throws Exception { checkControl(control);if(existing!=null)existing.admitCapabilities(snapshot,control); }
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control) throws Exception {
        checkControl(control);turnPauseRevision=control.pauseRevision();
        if(existing==null)return new JSONObject().put("tools",ownTools()).put("reference",new JSONObject());
        JSONObject turn=SemanticJson.copy(existing.prepareTurn(query,round,steps,control));return turn.put("tools",append(turn.getJSONArray("tools")));
    }
    @Override public boolean responseCurrent(RunControl control) throws Exception {
        checkControl(control);return turnPauseRevision==control.pauseRevision()&&(existing==null||existing.responseCurrent(control));
    }
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{checkControl(control);return own(name)||existing==null?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,control);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{checkControl(control);return own(name)||existing==null?null:existing.takeToolObservation(name,operation,control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{checkControl(control);if(existing!=null)existing.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{checkControl(control);return existing==null?null:existing.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{if(existing!=null)existing.modelRequestAbandoned(requestId);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control) throws Exception { return existing==null?null:existing.beforeTool(name,args,control); }
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
            if(operation!=null)throw new SecurityException("发现入口不能携带operation");
            SemanticRequest request=SemanticRequest.parse(args);check(name,control);return discover(request,control);
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
            requireCurrent(discovery.request,target,control);resource=platform.resourceIdentity();
        } catch(Exception refused) { return refusal("needs_user","原任务、入口或手机资源已变化，尚未派发；请核对后继续").put("requiresPause",true); }
        JSONObject capability=new JSONObject().put("feature","semantic").put("adapter",discovery.request.kind)
                .put("request",discovery.request.parameters()).put("requestDigest",discovery.request.digest())
                .put("target",target.description()).put("targetIdentity",target.identity()).put("resource",resource)
                .put("verifiedBusinessSuccess",false).put("followup",discovery.request.followup());
        if(discovery.request.kind.equals("calendar_draft"))capability.put("receiverMaySaveAutomatically",true)
                .put("requiresOwnerCompletion",true).put("timeZoneIsRequestedHint",true);
        if(discovery.request.clock())capability.put("receiverMayApplyImmediately",true).put("requiresOwnerVerification",true)
                .put("skipUi",false).put("separateConfirmationGuaranteed",false).put("deviceTimeZone",target.identity().getString("deviceTimeZone"));
        if(discovery.request.kind.equals("app_link")||discovery.request.kind.equals("share_text"))capability.put("receiverMayActImmediately",true).put("requiresOwnerCompletion",true).put("automaticContinuation",false);
        JSONObject approval;
        try {approval=authority.approve(name,SemanticJson.copy(args),capability);}
        catch(Exception refused) {return refusal("needs_user","具体入口审批未获确认，尚未派发").put("requiresPause",true);}
        if(!approval.optBoolean("approved"))return refusal("approval_required","需要主人确认固定App、入口参数和当前执行位置，尚未执行")
                .put("approvalId",approval.optString("approvalId"));
        SemanticPlatform.Admission gate=()->{
            check(name,control);
            if(pauseRevision!=control.pauseRevision())throw new SecurityException("执行经历暂停，旧决定失效");
            authority.requirePrepared(operationId,name,SemanticJson.copy(args));
            if(!SemanticJson.hash(resource).equals(SemanticJson.hash(platform.resourceIdentity())))throw new SecurityException("手机入口资源已替换");
            requireCurrent(discovery.request,target,control);
        };
        try { gate.check(); } catch(Exception refused) { return refusal("needs_user","审批后原任务、入口或资源已变化，尚未派发").put("requiresPause",true); }
        try {if(!session.claim(operationId,args.getString("discoveryId"),discovery))return unknown(operationId);}
        catch(Exception stale){return refusal("needs_user","原发现已失效或已派发，请核对原操作").put("requiresPause",true);}
        JSONObject result;
        try { result=platform.dispatch(discovery.request,target,control,gate); }
        catch(Exception uncertain) { session.markUncertain();return unknown(operationId); }
        if(result==null||!(result.opt("accepted") instanceof Boolean)||"unknown".equals(result.optString("status"))) {session.markUncertain();JSONObject out=unknown(operationId);if(result!=null)out.put("nativeResult",SemanticJson.copy(result));return out;}
        boolean accepted=result.getBoolean("accepted");
        JSONObject out=new JSONObject().put("ok",accepted).put("accepted",accepted).put("verified",false).put("verifiedBusinessSuccess",false)
                .put("operationId",operationId).put("status",accepted?"launch_requested":"not_dispatched")
                .put("code",accepted?"semantic_launch_accepted":result.optString("code","semantic_not_dispatched"))
                .put("target",target.description()).put("followup",discovery.request.followup()).put("resource",resource).put("nativeResult",SemanticJson.copy(result))
                .put("nextObservationAction",accepted?"observe":"none").put("requiresPostconditionCheck",accepted)
                .put("automaticFallback",false).put("message",accepted?"Android已接受打开入口；请观察目标App核验实际页面，尚未确认业务完成":"Android入口未派发，请重新检查phone/DOM路径");
        if(result.optBoolean("requiresPause"))out.put("requiresPause",true);
        if(accepted&&discovery.request.kind.equals("calendar_draft"))out.put("requiresPause",true).put("requiresOwnerCompletion",true)
                .put("savedEventVerified",false).put("timeZoneVerified",false).put("requestedTimeZone",discovery.request.parameters().getString("timeZone"))
                .put("message","已请求打开日历编辑器，请主人核对时间、时区和保存结果；部分编辑器退出时也可能保存。尚未确认事件已创建，不会代点保存或自动重开。");
        if(accepted&&discovery.request.clock())out.put("requiresPause",true).put("requiresOwnerVerification",true).put("clockStateVerified",false)
                .put("deviceTimeZone",target.identity().getString("deviceTimeZone")).put("receiverMayApplyImmediately",true)
                .put("message","已发送一次时钟请求，请在目标App核对闹钟或计时器。它可能已立即生效；显示界面不保证另有确认。尚未核实实际设置结果，不会自动重发。");
        if(accepted&&(discovery.request.kind.equals("app_link")||discovery.request.kind.equals("share_text")))out.put("requiresPause",true).put("requiresOwnerCompletion",true).put("receiverMayActImmediately",true)
                .put("externalDeliveryVerified",false).put("automaticContinuation",false)
                .put("message","已请求把固定入口内容交给选定App；接收方可能立即处理，启动不等于业务完成或消息已发送。后续交主人核验，不会自动点发送、重开或回落其他App。");
        try {check(name,control);}catch(Exception invalidated){out.put("requiresPause",true).put("code","needs_user").put("executionInvalidatedAfterDispatch",true);}
        return out;
    }
    private void requireCurrent(SemanticRequest request,SemanticPlatform.Target target,RunControl control) throws Exception {
        for(SemanticPlatform.Target current:platform.discover(request,control))
            if(target.component.equals(current.component)&&target.fingerprint.equals(current.fingerprint))return;
        throw new SecurityException("App版本、签名、入口或App Links批准状态已变化");
    }
    private JSONObject discover(SemanticRequest request,RunControl control) throws Exception {
        List<SemanticPlatform.Target> found=platform.discover(request,control);check(DISCOVER,control);
        JSONArray candidates=new JSONArray();Map<String,SemanticPlatform.Target> targets=new LinkedHashMap<>();
        for(SemanticPlatform.Target target:found) {if(targets.size()>=32)break;if(targets.putIfAbsent(target.fingerprint,target)==null){JSONObject candidate=target.description().put("candidateId",target.fingerprint);if(request.clock())candidate.put("deviceTimeZone",target.identity().getString("deviceTimeZone")).put("receiverMayApplyImmediately",true);candidates.put(candidate);}}
        String id=UUID.randomUUID().toString();
        session.put(id,new Discovery(request,targets,clock.getAsLong()));
        return new JSONObject().put("ok",true).put("status",targets.isEmpty()?"unavailable":"discovered").put("discoveryId",id)
                .put("request",request.parameters()).put("requestDigest",request.digest()).put("expiresInMs",DISCOVERY_TTL_MS)
                .put("candidates",candidates).put("truncated",found.size()>targets.size()).put("scope","visible_matching_exported_activities")
                .put("dispatchAvailable",dispatchEnabled).put("verified",false).put("automaticFallback",false)
                .put("fallback",request.followup()).put("message",!dispatchEnabled?"当前执行仅支持发现入口，不会启动应用或回落抢占手机主屏。":targets.isEmpty()?"未找到可核验的可见入口；可能未安装、不可见或链接未获批准，请检查phone/DOM路径":"入口来自当前Android包查询；标签是外部数据，仍需具体参数审批");
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
        final SemanticRequest request;final Map<String,SemanticPlatform.Target> targets;final long created;boolean consumed;
        Discovery(SemanticRequest request,Map<String,SemanticPlatform.Target> targets,long created){this.request=request;this.targets=targets;this.created=created;}
    }
}
