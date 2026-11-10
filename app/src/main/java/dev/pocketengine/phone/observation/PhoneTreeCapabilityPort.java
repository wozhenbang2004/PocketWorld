package dev.pocketengine.phone.observation;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.phone.DeferredPhoneSession;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native phone tools in the existing capability surface. No operation, grant or scheduler of its own. */
public final class PhoneTreeCapabilityPort implements CapabilityPort {
    private final CapabilityPort existing;
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final RunControl original;
    private final PhonePort phone;
    private volatile boolean closed;
    private volatile long turnAppPolicyRevision;
    private record PreparedSequence(String operation,NativeToolPlan plan) { }
    private final java.util.concurrent.atomic.AtomicReference<PreparedSequence> sequence=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.Set<String> attemptedScrolls=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean scrolling=new java.util.concurrent.atomic.AtomicBoolean();
    private final dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority authority;
    private PhoneTreeCapabilityPort(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl original,PhonePort phone){
        this.existing=Objects.requireNonNull(existing);this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);
        this.original=Objects.requireNonNull(original);this.phone=Objects.requireNonNull(phone);
        turnAppPolicyRevision=DeferredPhoneSession.policyRevision(phone);
        authority=new dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority(tasks,binding,event->{try{tasks.event(binding.taskId,binding.runId,binding.epoch,event);}catch(Exception failed){throw new IllegalStateException("原手机审批事件保存失败",failed);}});
        for(String name:new String[]{PhoneTreeQuery.QUERY,PhoneTreeQuery.WAIT,PhoneTreeExtract.TOOL,PhoneTreeCollection.TOOL,PhoneTextCollection.TOOL,NativeTreeScrollPort.TOOL,PhoneTreeSequence.TOOL,PhoneBoundedOperation.FIND,PhoneBoundedOperation.WHEEL})if(existing.supports(name))throw new IllegalArgumentException("树工具名称重复");
        for(String name:PhoneExtensionTools.NAMES)if(existing.supports(name))throw new IllegalArgumentException("手机扩展工具名称重复");
    }
    public static CapabilityPort bindIfAvailable(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl original,PhonePort selected)throws Exception {
        if(!DeferredPhoneSession.adaptive(selected))return existing;
        JSONObject task=tasks.get(binding.taskId,false);PhonePort phone=selected;
        if(task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background")
                ||!DeferredPhoneSession.matches(phone,binding.taskId,binding.runId,binding.epoch)||!DeferredPhoneSession.declared(phone))return existing;
        original.check();if(original.isPaused()||!original.hasHostRun()||!original.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("树工具需要原手机控制器");
        return new PhoneTreeCapabilityPort(existing,tasks,binding,original,phone);
    }
    private void check(String tool,RunControl control)throws Exception {
        if(closed||control!=original)throw new SecurityException("树工具不属于原控制器");
        if(!control.hasHostRun()||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("原手机执行代次已变化");
        control.check();if(control.isPaused())throw new SecurityException("原手机已暂停");
        tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,tool);
        if(control.isCancelled()||control.isPaused())throw new SecurityException("原手机派发已停止");
        JSONObject task=tasks.get(binding.taskId,false);
        if(task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background"))throw new SecurityException("原手机范围已撤销");
    }
    public static JSONArray appendTools(JSONArray base)throws Exception {
        JSONArray out=new JSONArray(base.toString());JSONObject selector=new JSONObject().put("type","object").put("additionalProperties",false)
                .put("properties",new JSONObject().put("windowId",number(0,Integer.MAX_VALUE)).put("parentId",string(100))
                        .put("text",string(400)).put("resourceId",string(400)).put("role",string(400)));
        JSONObject query=new JSONObject().put("observationId",string(100)).put("selector",selector).put("limit",number(1,40));
        add(out,PhoneTreeQuery.QUERY,"只读查询当前observationId的树。text在原生文字、内容描述、输入提示和关联标签中不区分大小写匹配；resourceId/role精确匹配，parentId限定本观察父域。返回目标及可操作父节点与原ID，歧义不自动选择，不滚动。",query,new JSONArray().put("observationId").put("selector"));
        JSONObject wait=new JSONObject().put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("present").put("absent").put("stable")))
                .put("selector",selector).put("timeoutMs",number(100,60000)).put("stableForMs",number(250,30000)).put("observationId",string(100));
        add(out,PhoneTreeQuery.WAIT,"只读等待当前显示的present/absent/stable条件，最多60秒；轮询随等待延长而降频，暂停和取消立即生效，超时不代表业务失败。parentId需原observationId；父域变化即拒，absent/stable不以残缺树判满足。stable非空selector需至少1个目标且目标集合/子树持续稳定；空selector才等完整场景稳定。",wait,new JSONArray().put("kind").put("selector").put("timeoutMs"));
        JSONObject fieldSelector=new JSONObject().put("type","object").put("additionalProperties",false).put("properties",new JSONObject().put("text",string(400)).put("resourceId",string(400)).put("role",string(400)));
        JSONObject direction=new JSONObject().put("type","string").put("enum",new JSONArray().put("forward").put("backward"));
        add(out,PhoneBoundedOperation.FIND,"在当前明确的可滚动容器里查找selector；找到即返回当前目标，最多30次原生滚动。每一步均由原Runner独立准备、审批、记录和读取，目标/应用/暂停变化或无进展即停止。仅查找，不点击匹配项；未找到不证明整列表不存在。一个batch最多准备一个有界操作。",new JSONObject().put("observationId",string(100)).put("containerId",string(100)).put("selector",fieldSelector).put("direction",direction).put("maxScrolls",number(1,30)),new JSONArray().put("observationId").put("containerId").put("selector").put("direction"));
        add(out,PhoneBoundedOperation.WHEEL,"把当前滚轮选到目标值：优先原生可编辑字段，否则沿明确方向逐步滚动，每次读取实际选中值；原树读不到时可用已授权本地OCR。最多30步，已到目标则不派发动作；歧义、无进展、页面变化即结束。每步经过原Runner独立审批和回执，值可见不等于表单已提交。",new JSONObject().put("observationId",string(100)).put("nodeId",string(100)).put("value",string(100)).put("direction",direction).put("maxSteps",number(1,30)),new JSONArray().put("observationId").put("nodeId").put("value").put("direction"));
        JSONObject field=new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("name").put("selector")).put("properties",new JSONObject().put("name",string(40)).put("selector",fieldSelector).put("attribute",new JSONObject().put("type","string").put("enum",new JSONArray().put("text").put("description").put("label").put("checked").put("selected"))).put("multiple",new JSONObject().put("type","boolean")));
        JSONObject fields=new JSONObject().put("type","array").put("minItems",1).put("maxItems",8).put("items",field);
        JSONObject extract=new JSONObject().put("observationId",string(100)).put("containerId",string(100)).put("rowSelector",fieldSelector).put("fields",fields).put("limit",number(1,80)).put("offset",number(0,2048));
        add(out,PhoneTreeExtract.TOOL,"只读提取当前真实树中明确容器的可见列表行：默认直接孩子为行，rowSelector可明确后代行；字段只在各自行内找，空字段selector仅指行自身；重复/缺失/歧义/密码掩码均显式返回，不猜表格、不滚动。返回行和字段sourceRefs的当前观察；hasMore时以同一observationId和nextOffset继续取页，不证明整列表完整。",extract,new JSONArray().put("observationId").put("containerId").put("fields"));
        JSONObject collect=new JSONObject(extract.toString());collect.remove("limit");collect.put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("start").put("advance").put("read").put("finish"))).put("cursorId",string(100)).put("afterOperationId",string(100)).put("direction",new JSONObject().put("type","string").put("enum",new JSONArray().put("forward").put("backward"))).put("maxPages",number(1,30)).put("maxRows",number(1,1000)).put("offset",number(0,1000)).put("limit",number(1,100)).put("stopSelector",fieldSelector);
        add(out,PhoneTreeCollection.TOOL,"原任务逐屏列表采集：start需当前observationId/containerId/fields/direction；needs_scroll后单独调用phone_scroll_node，原Runner回执后advance带cursorId/afterOperationId。最多30屏/1000行/10分钟，按offset/limit读取已保留结果；不带分页的read只刷新同页引用。仅合并唯一有序重叠，歧义保留未合证据；wholeListComplete始终false，不隐式滚动。",collect,new JSONArray().put("action"));
        JSONObject textCollect=new JSONObject().put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("start").put("advance").put("read").put("finish")))
                .put("observationId",string(100)).put("containerId",string(100)).put("cursorId",string(100)).put("afterOperationId",string(100))
                .put("source",new JSONObject().put("type","string").put("enum",new JSONArray().put("auto").put("tree").put("ocr"))).put("direction",direction)
                .put("maxPages",number(1,30)).put("maxLines",number(1,1000)).put("offset",number(0,1000)).put("limit",number(1,100));
        add(out,PhoneTextCollection.TOOL,"逐屏采集当前容器文字，source=tree/ocr/auto；OCR仅走主人已授权的本地截图识别，不上传新图片。start需observationId/containerId/direction；最多30屏/1000条，保留页来源和重叠，按offset/limit分页读。滚动必须单独调用原工具，advance带其afterOperationId；支持同容器原生滚动或已授权图片内的单指滑动。新观察后先read带observationId刷新游标再滑动。识别可能出错，未读到不代表不存在，不会自动滚动或重放。",textCollect,new JSONArray().put("action"));
        add(out,NativeTreeScrollPort.TOOL,"对当前真实window/path节点执行一次声明支持的forward/backward无障碍滚动：须原prepared操作，拒绝旧ref/密码/不可滚动目标，不改坐标或主屏。随后最多2.5秒读取树，等变化稳定400ms；回执区分accepted和实际树变化，变化不证明业务成功，不能重发未知动作。",new JSONObject().put("observationId",string(100)).put("nodeId",string(100)).put("direction",new JSONObject().put("type","string").put("enum",new JSONArray().put("forward").put("backward"))),new JSONArray().put("observationId").put("nodeId").put("direction"));
        out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",PhoneTreeSequence.TOOL)
                .put("description","只读准备1–4步确定性手机短序列；一个模型batch最多一个。所有动作target须是当前完整树已展示的精确windowId/path，可补resourceId/role/textExact；不猜未来节点、不接受坐标/启动/主页/导航。Runner逐步fresh树核同一真实目标并各自原prepared/精确审批/journal；暂停、取消、换应用/显示/会话、目标变化、未知或拒绝立停，余步未派发。最多20秒，不是一张总许可，不隐式提交或重试。")
                .put("parameters",PhoneTreeSequence.schema())));
        return out;
    }
    private static JSONObject string(int max)throws Exception{return new JSONObject().put("type","string").put("minLength",1).put("maxLength",max);}
    private static JSONObject number(int min,int max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    private static void add(JSONArray out,String name,String description,JSONObject properties,JSONArray required)throws Exception {
        out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description)
                .put("parameters",new JSONObject().put("type","object").put("additionalProperties",false).put("properties",properties).put("required",required))));
    }
    private static boolean owns(String name){return PhoneTreeQuery.supports(name)||PhoneExtensionTools.NAMES.contains(name)||PhoneBoundedOperation.supports(name)||PhoneTextCollection.TOOL.equals(name);}
    @Override public JSONArray tools()throws Exception{return PhoneExtensionTools.append(appendTools(existing.tools()));}
    @Override public boolean supports(String name){return owns(name)||existing.supports(name);}
    @Override public boolean sideEffecting(String name){return PhoneBoundedOperation.supports(name)||PhoneTextCollection.TOOL.equals(name)?false:PhoneExtensionTools.NAMES.contains(name)?PhoneExtensionTools.writes(name):NativeTreeScrollPort.TOOL.equals(name)||!PhoneTreeQuery.supports(name)&&existing.sideEffecting(name);}
    @Override public dev.pocketengine.core.ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception {
        if(!owns(name))return existing.planningAccess(name,arguments);
        if(!java.util.Set.of(PhoneTreeQuery.QUERY,PhoneTreeQuery.WAIT,PhoneTreeExtract.TOOL,"phone_list_app_copies","phone_dialog","phone_event").contains(name))return dev.pocketengine.core.ExecutionPlanning.Access.DENY;
        if(arguments!=null){JSONObject checked=new JSONObject(arguments.toString());checked.remove("_hostOperationId");validate(name,checked);}
        return dev.pocketengine.core.ExecutionPlanning.Access.READ;
    }
    @Override public void validate(String name,JSONObject args)throws Exception{if(PhoneTextCollection.TOOL.equals(name))PhoneTextCollection.validate(args);else if(PhoneBoundedOperation.supports(name))PhoneBoundedOperation.validate(name,args);else if(PhoneExtensionTools.NAMES.contains(name))PhoneExtensionTools.validate(name,args);else if(PhoneTreeQuery.supports(name))PhoneTreeQuery.validate(name,args);else existing.validate(name,args);}
    @Override public JSONObject execute(String name,JSONObject supplied,RunControl control)throws Exception {
        if(!owns(name))return existing.execute(name,supplied,control);
        JSONObject args=new JSONObject(Objects.requireNonNull(supplied).toString());String operation=null;
        if(PhoneTreeSequence.TOOL.equals(name)||PhoneBoundedOperation.supports(name)){Object raw=args.remove("_hostOperationId");if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>200)return PhoneTreeExtract.unavailable("original_parent_call_identity_required");operation=(String)raw;}
        else if(NativeTreeScrollPort.TOOL.equals(name)||PhoneExtensionTools.NAMES.contains(name)&&PhoneExtensionTools.writes(name)){Object raw=args.remove("_hostOperationId");if(!(raw instanceof String)||!raw.toString().matches("[0-9a-f-]{36}"))return PhoneTreeExtract.unavailable("original_prepared_operation_required");operation=(String)raw;}
        validate(name,args);check(name,control);
        try(AutoCloseable retained=control.retainAuthorityCheck(()->tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,name))){
            JSONObject result;
            if(PhoneExtensionTools.NAMES.contains(name)){
                if(PhoneExtensionTools.writes(name)){
                    final String op=operation;authority.requirePrepared(op,name,args);if(!scrolling.compareAndSet(false,true))return PhoneTreeExtract.unavailable("original_phone_action_in_flight");
                    try{if(attemptedScrolls.size()>=1000||!attemptedScrolls.add(op))return PhoneTreeExtract.unavailable("original_phone_action_already_attempted");result=DeferredPhoneSession.require(phone,control).nativeExtension(name,args,control,()->{check(name,control);authority.requirePrepared(op,name,args);});result.put("operationId",op);}finally{scrolling.set(false);}
                }else result=DeferredPhoneSession.require(phone,control).nativeExtension(name,args,control,null);
            }else if(PhoneTreeSequence.TOOL.equals(name)){result=prepareSequence(args,operation,control);}
            else if(PhoneBoundedOperation.supports(name)){result=prepareBounded(name,args,operation,control);}
            else if(NativeTreeScrollPort.TOOL.equals(name)){
                final String op=operation;authority.requirePrepared(op,name,args);
                if(!scrolling.compareAndSet(false,true))return PhoneTreeExtract.unavailable("original_scroll_in_flight");
                try{
                    if(attemptedScrolls.size()>=1000||!attemptedScrolls.add(op))return PhoneTreeExtract.unavailable("original_scroll_already_attempted");
                    result=DeferredPhoneSession.require(phone,control).scrollNode(args,control,()->{check(name,control);authority.requirePrepared(op,name,args);});result.put("operationId",op);
                }finally{scrolling.set(false);}
            }else if(PhoneTreeExtract.TOOL.equals(name))result=DeferredPhoneSession.require(phone,control).extractTree(args,control);
            else if(PhoneTreeCollection.TOOL.equals(name)||PhoneTextCollection.TOOL.equals(name)){
                JSONObject record=null;if("advance".equals(args.optString("action"))){JSONArray operations=tasks.get(binding.taskId,false).getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(args.getString("afterOperationId").equals(op.optString("operationId"))){boolean scroll=NativeTreeScrollPort.TOOL.equals(op.optString("tool"))||PhoneTextCollection.TOOL.equals(name)&&java.util.Set.of("phone_swipe","phone_gesture").contains(op.optString("tool"));if(record!=null||!binding.runId.equals(op.optString("runId"))||!scroll)return PhoneTreeExtract.unavailable("scroll_receipt_not_in_original_run");record=new JSONObject(op.toString());}}if(record==null)return PhoneTreeExtract.unavailable("scroll_receipt_not_in_original_run");}
                result=PhoneTextCollection.TOOL.equals(name)?DeferredPhoneSession.require(phone,control).collectText(args,record,control):DeferredPhoneSession.require(phone,control).collectTree(args,record,control);
            }else result=PhoneTreeQuery.QUERY.equals(name)?DeferredPhoneSession.require(phone,control).queryTree(args,control):DeferredPhoneSession.require(phone,control).conditionWait(args,control);
            try{check(name,control);}catch(Exception stopped){
                if(result==null)throw stopped;
                throw new CapabilityPort.ReceiptAfterStop(control,result,stopped);
            }
            return result;
        }catch(SecurityException stale){if(NativeTreeScrollPort.TOOL.equals(name)||PhoneExtensionTools.NAMES.contains(name)&&PhoneExtensionTools.writes(name))throw stale;return PhoneTreeExtract.unavailable("original_tree_or_scroll_authority_changed");}
    }
    private JSONObject prepareSequence(JSONObject args,String parentOperation,RunControl control)throws Exception {
        PhoneTreeSequence.State state=DeferredPhoneSession.require(phone,control).prepareSequence(args,control);
        android.os.UserHandle user=android.os.Process.myUserHandle();String hostUserIdentity=user.toString();
        // The authoritative display/session tuple is native adapter state, never this task JSON.
        JSONObject anchor=DeferredPhoneSession.require(phone,control).resolveSequenceIdentity(state,control);
        NativeToolPlan.Identity identity=new NativeToolPlan.Identity(binding.taskId,binding.runId,binding.epoch,
                anchor.getInt("displayId"),anchor.getString("sessionId"),hostUserIdentity,state.initialObservation);
        RunControl.AuthorityCheck current=()->{nativeHookControl(control);if(!user.equals(android.os.Process.myUserHandle()))throw new SecurityException("原app进程user身份已变化");DeferredPhoneSession.require(phone,control).requireSequenceCurrent(state,null,control);};
        NativeToolPlan plan=NativeToolPlan.capture(PhoneTreeSequence.TOOL,parentOperation,control,identity,state.size(),(index,originalControl)->{
            current.check();JSONObject actual=DeferredPhoneSession.require(phone,control).resolveSequenceStep(state,index,originalControl);
            JSONObject actualArgs=state.resolve(index,actual,DeferredPhoneSession.require(phone,control).sequenceUptime());String freshId=actual.getString("observationId");
            JSONObject shown=actualArgs.has("nodeId")?PhoneTreeExtract.actionObservation(actual,actualArgs.getString("nodeId")):PhoneTreeQuery.modelObservation(actual);
            return new NativeToolPlan.Resolved(state.tool(index),actualArgs,shown,()->{current.check();DeferredPhoneSession.require(phone,control).requireSequenceCurrent(state,freshId,originalControl);});
        },current);
        PreparedSequence old=sequence.getAndSet(new PreparedSequence(parentOperation,plan));if(old!=null)old.plan.close();
        return PhoneTreeExtract.base().put("ok",true).put("status","prepared_native_sequence").put("steps",state.size())
                .put("parentCallId",parentOperation).put("observationId",state.initialObservation).put("expiresInMs",PhoneTreeSequence.TTL_MS)
                .put("executionStarted",false).put("perStepOriginalApprovalRequired",true).put("hostUserIdentityKind","app_process_opaque_handle_not_foreground_user_proof");
    }
    private void nativeHookControl(RunControl control)throws Exception {
        if(closed||control!=original||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("原树工具native交付身份失效");
        control.check();if(control.isPaused())throw new SecurityException("原树工具已暂停");
    }
    private JSONObject prepareBounded(String tool,JSONObject args,String parentOperation,RunControl control)throws Exception {
        AdaptivePhonePort adapter=DeferredPhoneSession.require(phone,control);PhoneBoundedOperation.State state=adapter.prepareBounded(tool,args,control);
        android.os.UserHandle user=android.os.Process.myUserHandle();JSONObject anchor=adapter.boundedIdentity(state,control);
        NativeToolPlan.Identity identity=new NativeToolPlan.Identity(binding.taskId,binding.runId,binding.epoch,anchor.getInt("displayId"),anchor.getString("sessionId"),user.toString(),state.initialObservation);
        RunControl.AuthorityCheck current=()->{nativeHookControl(control);if(!user.equals(android.os.Process.myUserHandle()))throw new SecurityException("bounded_phone_user_changed");adapter.requireBoundedCurrent(state,null,control);};
        NativeToolPlan plan=NativeToolPlan.capture(tool,parentOperation,control,identity,state.stepLimit(),(index,live)->{
            current.check();JSONObject fresh=adapter.resolveBounded(state,live);String observation=fresh.getString("observationId");
            return state.resolve(index,fresh,adapter.sequenceUptime(),()->{current.check();adapter.requireBoundedCurrent(state,observation,live);});
        },current);
        PreparedSequence old=sequence.getAndSet(new PreparedSequence(parentOperation,plan));if(old!=null)old.plan.close();
        return PhoneTreeExtract.base().put("ok",true).put("status","prepared_bounded_operation").put("parentCallId",parentOperation)
                .put("maxActionSteps",state.stepLimit()-1).put("expiresInMs",PhoneBoundedOperation.TTL_MS).put("executionStarted",false).put("perStepOriginalApprovalRequired",true);
    }
    @Override public NativeToolObservation.Requirement observationRequirement(String tool,JSONObject args,RunControl control)throws Exception {nativeHookControl(control);return existing.observationRequirement(tool,args,control);}
    @Override public NativeToolObservation takeToolObservation(String tool,String operation,RunControl control)throws Exception {nativeHookControl(control);return existing.takeToolObservation(tool,operation,control);}
    @Override public NativeToolPlan takeToolPlan(String tool,String operation,RunControl control)throws Exception {
        nativeHookControl(control);if(!PhoneTreeSequence.TOOL.equals(tool)&&!PhoneBoundedOperation.supports(tool))return existing.takeToolPlan(tool,operation,control);
        PreparedSequence pending=sequence.get();if(pending==null)return null;if(!pending.operation.equals(operation))throw new SecurityException("短序列不属于原父调用");
        if(!sequence.compareAndSet(pending,null))throw new SecurityException("短序列已交付或释放");return pending.plan;
    }
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl control)throws Exception {nativeHookControl(control);return existing.nativeReadContextRepair(messages,control);}
    @Override public boolean stepReadSourceCurrent(String tool,JSONObject receipt,RunControl control)throws Exception {nativeHookControl(control);return !owns(tool)&&existing.stepReadSourceCurrent(tool,receipt,control);}
    @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{check(PhoneTreeQuery.QUERY,control);existing.beginRun(goal,control);}
    @Override public void prepareTurnSurface(RunControl control)throws Exception {check(PhoneTreeQuery.QUERY,control);existing.prepareTurnSurface(control);}
    @Override public void admitCapabilities(CapabilitySnapshot snapshot,RunControl control)throws Exception{existing.admitCapabilities(snapshot,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{check(PhoneTreeQuery.QUERY,control);turnAppPolicyRevision=DeferredPhoneSession.policyRevision(phone);JSONObject turn=existing.prepareTurn(query,round,steps,control);return turn.put("tools",PhoneExtensionTools.append(appendTools(turn.getJSONArray("tools"))));}
    @Override public boolean responseCurrent(RunControl control)throws Exception{check(PhoneTreeQuery.QUERY,control);return turnAppPolicyRevision==DeferredPhoneSession.policyRevision(phone)&&existing.responseCurrent(control);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{if(owns(name))check(name,control);return existing.beforeTool(name,args,control);}
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception{existing.afterTool(name,args,receipt,control);}
    @Override public void beforeModelRequest(String id,JSONArray messages,RunControl control)throws Exception{check(PhoneTreeQuery.QUERY,control);existing.beforeModelRequest(id,messages,control);}
    @Override public JSONObject afterModelResponse(String id,String answer,RunControl control)throws Exception{check(PhoneTreeQuery.QUERY,control);return existing.afterModelResponse(id,answer,control);}
    @Override public void modelRequestAbandoned(String id)throws Exception{existing.modelRequestAbandoned(id);}
    @Override public void endRun(RunControl control)throws Exception{PreparedSequence pending=sequence.getAndSet(null);if(pending!=null)pending.plan.close();existing.endRun(control);}
    @Override public void close(){if(closed)return;closed=true;PreparedSequence pending=sequence.getAndSet(null);if(pending!=null)pending.plan.close();existing.close();}
}
