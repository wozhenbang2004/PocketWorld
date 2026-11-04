package dev.pocketengine.features.devices;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Source-side tools for the EXISTING AgentRunner. Targets/grants/envelopes are native closed routes;
 * model JSON cannot create a peer, grant, signature, task ID, operation ID or delivery claim. */
public final class DeviceCapabilityPort implements CapabilityPort {
    public interface NativeRoute {
        String targetDeviceId();
        default void acceptedTransfer(JSONObject originalWire,Reply actual,JSONObject receipt)throws Exception{ }
        default String routeId(){return targetDeviceId();}
        default String parentGoal(){return null;}
        default boolean supportsFunction(String function){return true;}
        default TaskStoreDeviceTasks.OwnerRouteApproval reservationApproval()throws Exception{throw new SecurityException("没有原native任务设备范围");}
        default void validateArguments(String function,JSONObject arguments)throws Exception{ }
        default JSONObject invocationContracts()throws Exception{return new JSONObject();}
        default void requireReplyPeer(DeviceIdentity.AuthenticatedPeer actual,DeviceTaskEnvelope request)throws Exception{receivingGrant().requireTargetPeer(actual);}
        void requireCurrent(long now) throws Exception;
        /** The receiving device's exact tool declaration, not a model-provided effect string. */
        String effect(String toolName) throws Exception;
        DeviceTaskEnvelope request(String method, JSONObject arguments, String originalOperationId,
                int toolBudget, int modelBudget, JSONObject observationRef, RunControl original) throws Exception;
        /** Called only AFTER the original parent allocation was durably reserved. */
        default JSONObject prepare(String method, JSONObject arguments, String originalOperationId, int tools, int models, JSONObject observation, RunControl original) throws Exception {
            throw new SecurityException("设备执行必须由native闭集路由准备，不能先签后预留");
        }
        default DeviceTaskEnvelope finalizeReserved(JSONObject unsigned, TaskStoreDeviceTasks.ChildReservation nativeReservation) throws Exception {
            nativeReservation.requireIntent(unsigned);
            return DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(unsigned));
        }
        default PairingCoordinator.OutgoingGrant receivingGrant()throws Exception{throw new SecurityException("没有目标设备签发的native路由");}
        /** One delivery attempt only. Network failure after possible dispatch MUST NOT retry the business call. */
        Reply exchange(DeviceTaskEnvelope request, RunControl original) throws Exception;
        default Reply exchange(DeviceTaskEnvelope request,TaskStoreDeviceTasks.OutboundSendTicket ticket,RunControl original)throws Exception{throw new SecurityException("原传输未安装native发送票据通道，未派发");}
    }
    @FunctionalInterface public interface VerifiedImages { dev.pocketengine.features.devices.transport.DeviceArtifactFetch.Verified fetch(TaskStoreDeviceTasks.DeviceImageEvidence nativeEvidence)throws Exception; }
    @FunctionalInterface public interface ReceivedFiles { JSONObject publish(TaskStoreDeviceTasks.DeviceFileEvidence nativeEvidence) throws Exception; }
    private final ReceivedFiles files;
    public record Reply(DeviceIdentity.AuthenticatedPeer peer, JSONObject response) { }
    private final TaskStore tasks;
    private final TaskStoreDeviceTasks devices;
    private final DeviceTaskCoordinator coordinator;
    private final RunControl original;
    private final String taskId, runId;
    private final int epoch;
    private final Map<String, NativeRoute> routes = new LinkedHashMap<>();
    private boolean closed;private final DeviceVisualFrames visual;private final DeviceSemanticObservations semantic;
    public DeviceCapabilityPort(TaskStore tasks, DeviceTaskCoordinator coordinator, RunControl original, Iterable<NativeRoute> selectedRoutes) {
        this(tasks,coordinator,original,selectedRoutes,null);
    }
    public DeviceCapabilityPort(TaskStore tasks,DeviceTaskCoordinator coordinator,RunControl original,Iterable<NativeRoute> selectedRoutes,VerifiedImages actualImages){
        this(tasks,coordinator,original,selectedRoutes,actualImages,null);
    }
    public DeviceCapabilityPort(TaskStore tasks,DeviceTaskCoordinator coordinator,RunControl original,Iterable<NativeRoute> selectedRoutes,VerifiedImages actualImages,ReceivedFiles receivedFiles){
        files=receivedFiles;visual=new DeviceVisualFrames(actualImages,original);semantic=new DeviceSemanticObservations(original);this.tasks = java.util.Objects.requireNonNull(tasks); devices = tasks.deviceTasks(); this.coordinator = java.util.Objects.requireNonNull(coordinator);
        this.original = java.util.Objects.requireNonNull(original); RunControl.HostRunIdentity run = original.hostRunIdentity();
        if (run == null) throw new SecurityException("设备调用必须属于原TaskStore执行");
        taskId = run.taskId(); runId = run.runId(); epoch = run.epoch();
        for (NativeRoute route : selectedRoutes) if (routes.putIfAbsent(route.routeId(), route) != null) throw new SecurityException("同设备范围存在冲突native route");
    }
    @Override public JSONArray tools()throws Exception{
        JSONArray result=new JSONArray();for(String function:new String[]{"device_invoke_tool","device_delegate_goal","device_operation_get"}){
            JSONArray targets=new JSONArray(),ids=new JSONArray(),ranges=new JSONArray();String goal=null;for(var entry:routes.entrySet()){NativeRoute route=entry.getValue();if(!route.supportsFunction(function))continue;route.requireCurrent(System.currentTimeMillis());ids.put(entry.getKey());if(!PairingCoordinator.contains(targets,route.targetDeviceId()))targets.put(route.targetDeviceId());JSONObject range=new JSONObject().put("routeId",entry.getKey()).put("targetDeviceId",route.targetDeviceId());try{JSONObject approved=route.reservationApproval().view();JSONArray projects=approved.optJSONArray("projects");for(int p=0;projects!=null&&p<projects.length();p++)projects.getJSONObject(p).remove("manifest");range.put("approvedRange",approved);}catch(SecurityException notConfigured){throw notConfigured;}ranges.put(range);if(route.parentGoal()!=null)goal=route.parentGoal();}
            if(ids.length()==0)continue;JSONObject properties=new JSONObject().put("routeId",new JSONObject().put("type","string").put("enum",ids)).put("targetDeviceId",new JSONObject().put("type","string").put("enum",targets));String description="准确原native范围（不是可改参数）："+ranges.toString()+"。未知只查原operation，不换ID重发。";
            if(function.equals("device_invoke_tool")){
                JSONArray names=new JSONArray(),exact=new JSONArray();StringBuilder contractNotes=new StringBuilder();java.util.Set<String> noted=new java.util.HashSet<>();
                for(var selected:routes.entrySet()){NativeRoute nativeRoute=selected.getValue();if(!nativeRoute.supportsFunction(function))continue;JSONObject contracts=nativeRoute.invocationContracts();for(String name:DeviceProtocol.objectKeys(contracts)){if(!PairingCoordinator.contains(names,name))names.put(name);if(noted.add(name))contractNotes.append(name).append(": ").append(DeviceSourceToolContracts.guidance(name)).append("\n");JSONArray variants=contracts.getJSONArray(name);for(int c=0;c<variants.length();c++){
                    JSONObject shape=new JSONObject(variants.getJSONObject(c).toString());if(name.equals("phone_observe")&&(!visual.installed()||!nativeRoute.reservationApproval().resourceBinding().optBoolean("allowWholeDisplayCapture")))shape.getJSONObject("properties").put("includeImage",new JSONObject().put("type","boolean").put("enum",new JSONArray().put(false)));
                    exact.put(new JSONObject().put("properties",new JSONObject().put("routeId",string().put("enum",new JSONArray().put(selected.getKey()))).put("targetDeviceId",string().put("enum",new JSONArray().put(nativeRoute.targetDeviceId()))).put("toolName",string().put("enum",new JSONArray().put(name))).put("arguments",shape).put("toolBudget",integer(1,400).put("enum",new JSONArray().put(nativeRoute.reservationApproval().toolBudget())))));
                }}}
                if(exact.length()==0)continue;properties.put("toolName",string().put("enum",names)).put("arguments",object()).put("observationRef",object()).put("toolBudget",integer(1,400));JSONObject definition=tool(function,"目标只执行明确工具且零模型请求。参数anyOf来自固定receiver实际parser，不是可改权限。"+description+"\n"+contractNotes,properties,"routeId","targetDeviceId","toolName","arguments","toolBudget");definition.getJSONObject("function").getJSONObject("parameters").put("anyOf",exact);result.put(definition);
            }
            else if(function.equals("device_delegate_goal")){JSONObject selected=string();if(goal!=null)selected.put("enum",new JSONArray().put(goal));properties.put("goal",selected).put("toolBudget",integer(0,400)).put("modelBudget",integer(1,1000));result.put(tool(function,"交给目标原Agent，预算先原SQL预留。仅分享owner已选目标，材料与私记忆为空；接受不等于成功。"+description,properties,"routeId","targetDeviceId","goal","toolBudget","modelBudget"));}
            else{properties.put("operationId",string());result.put(tool(function,"只读原操作，不重提或再分配预算。"+description,properties,"routeId","targetDeviceId","operationId"));}
        }return result;
    }
    private static JSONObject object() throws Exception { return new JSONObject().put("type", "object"); }
    private static JSONObject string() throws Exception { return new JSONObject().put("type", "string").put("maxLength", 12000); }
    private static JSONObject integer(int low, int high) throws Exception { return new JSONObject().put("type", "integer").put("minimum", low).put("maximum", high); }
    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", description)
                .put("parameters", new JSONObject().put("type", "object").put("properties", properties).put("required", new JSONArray(required)).put("additionalProperties", false)));
    }
    @Override public boolean supports(String name) {if(closed||name==null||!name.matches("device_invoke_tool|device_delegate_goal|device_operation_get"))return false;for(var route:routes.values())if(route.supportsFunction(name))return true;return false;}
    @Override public boolean sideEffecting(String name) { return !"device_operation_get".equals(name); }
    @Override public dev.pocketengine.core.ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception{
        // Even operation.get can receive/send files and settle the original child allocation.
        // Its journal flag is not proof of a planning-only read.
        return dev.pocketengine.core.ExecutionPlanning.Access.DENY;
    }
    private NativeRoute route(JSONObject args, RunControl control) throws Exception {
        if (closed || control != original || !control.matchesHostRun(taskId, runId, epoch) || original.isPaused()) throw new SecurityException("原设备调用控制已变化");
        original.check(); NativeRoute route = routes.get(args.optString("routeId"));
        if (route == null||!route.targetDeviceId().equals(args.optString("targetDeviceId"))) throw new SecurityException("目标设备/准确resource route不在本机已授闭集"); route.requireCurrent(System.currentTimeMillis()); return route;
    }
    @Override public void validate(String name, JSONObject args) throws Exception {
        if (!supports(name)) throw new SecurityException("没有该设备能力"); route(args, original);
        JSONArray available = tools(); for (int i = 0; i < available.length(); i++) {
            JSONObject function = available.getJSONObject(i).getJSONObject("function");
            if (name.equals(function.getString("name"))) {JSONObject localShape=new JSONObject(function.getJSONObject("parameters").toString());localShape.remove("anyOf");dev.pocketengine.features.plugins.PluginRules.validateArguments(args,localShape);}
        }
        NativeRoute selected=route(args,original);selected.validateArguments(name,args);if(name.equals("device_invoke_tool")&&requiresDesktopImage(selected,args.optString("toolName"),args.optJSONObject("arguments"),args.optJSONObject("observationRef"))&&!visual.installed())throw new SecurityException("没有真实图像接收/原SQL视觉接缝，桌面视觉动作未就绪");
    }
    @Override public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception{JSONObject result=invoke(name,input,control);return "OperationReceipt".equals(result.optString("messageType"))?localAgentReceipt(result):result;}
    private static JSONObject localAgentReceipt(JSONObject typed)throws Exception{
        JSONObject body=typed.getJSONObject("payload"),actual=body.optJSONObject("receipt");if("awaiting_import_review".equals(body.optString("phase")))return new JSONObject().put("ok",false).put("code","needs_user").put("message","项目包已暂存，尚未导入；请打开原任务的设备协作范围，核对原包清单和新目录后确认，再继续原任务。").put("deviceReceipt",typed).put("operationId",typed.get("operationId")).put("businessVerified",false);boolean ok=typed.optBoolean("accepted")&&"reported".equals(typed.optString("effectState"))&&(typed.optString("status").equals("succeeded")||actual!=null&&Boolean.TRUE.equals(actual.opt("ok"))||Boolean.TRUE.equals(body.opt("ok")));
        return new JSONObject().put("ok",ok).put("accepted",typed.getBoolean("accepted")).put("operationId",typed.get("operationId")).put("status",typed.getString("status")).put("code",ok?"device_operation_reported":typed.optBoolean("accepted")?"device_request_accepted_waiting_result":"device_original_not_found").put("message",ok?"已取得原目标准确操作回执；不等于业务目标已验证":"原请求接受/执行状态请查原ID，不换ID重提").put("deviceReceipt",typed).put("businessVerified",false);
    }
    private JSONObject invoke(String name, JSONObject input, RunControl control) throws Exception {
        JSONObject args = new JSONObject(input.toString()); String hostOperation = args.optString("_hostOperationId"); args.remove("_hostOperationId");
        validate(name, args); NativeRoute route = route(args, control);
        if (name.equals("device_operation_get")) {
            String operation = args.getString("operationId");
            JSONObject originalAllocation=devices.childAllocationForOperation(taskId,operation);JSONObject originalWire=originalAllocation.getJSONObject("boundWire");
            if(!route.targetDeviceId().equals(originalWire.getString("targetDeviceId"))||!DeviceProtocol.resourceEquals(route.reservationApproval().resourceBinding(),originalWire.getJSONObject("resourceBinding")))throw new SecurityException("只准查本原父操作和已选准确资源，不读取别的任务结果");
            DeviceTaskEnvelope query = route.request("operation.get", new JSONObject().put("operationId", operation), operation, 0, 0, null, original);
            try {
                Reply actual = route.exchange(query, original); JSONObject result = reply(route, actual,query);
                if (Boolean.TRUE.equals(result.opt("accepted"))){recordAccepted(route, actual, result, operation, result.optString("argumentsDigest"));captureSemantic(route,operation,result);captureImage(route,actual,operation,name,result);receiveFile(route,actual,operation,result);sendFile(route,actual,operation,result);}
                JSONObject payload=result.getJSONObject("payload");
                if (Boolean.TRUE.equals(payload.opt("terminal")) && Boolean.TRUE.equals(payload.opt("stopped"))&&"GoalSubmission".equals(originalWire.optString("messageType"))&&result.has("localTaskId")&&result.has("runId")) {
                    JSONObject allocation = devices.childAllocationForOperation(taskId, operation);
                    JSONObject terminal = new JSONObject(payload.toString()).put("parentTaskId", taskId).put("parentOperationId", allocation.getString("anchorOperationId")).put("argumentsDigest", allocation.getString("anchorArgumentsDigest"))
                            .put("executorDeviceId", route.targetDeviceId()).put("instanceId", actual.response().getString("instanceId"))
                            .put("childTaskId", result.getString("localTaskId")).put("childRunId", result.getString("runId"));
                    JSONObject settled=devices.settleChild(coordinator.authenticatedChildResult(actual.peer(), terminal));return parentView(result,settled);
                }
                return parentView(result,new JSONObject().put("allocationAuthorizationRetained",true).put("singleOperationIsNotAllocationClosed",true));
            }
            catch (Exception unavailable) { return new JSONObject().put("status", "needs_reconcile").put("operationId", operation).put("readOnly", true).put("retrySubmitted", false); }
        }
        if (hostOperation.isEmpty()) throw new SecurityException("缺少原AgentRunner持久登记的操作身份");
        tasks.checkCapability(taskId, runId, epoch, name, original);
        String method; JSONObject payload; int tools = args.getInt("toolBudget"), models = 0;
        if (name.equals("device_invoke_tool")) {
            method = "tool.invoke"; String toolName = args.getString("toolName");
            payload = new JSONObject().put("toolName", toolName).put("effect", route.effect(toolName)).put("arguments", args.getJSONObject("arguments"));
        } else {
            method = "task.submitGoal"; models = args.getInt("modelBudget");
            payload = new JSONObject().put("goal", args.getString("goal")).put("executionMode", "agent").put("modelSource", new JSONObject().put("kind", "local"));
            if (args.optJSONObject("contextSnapshot") != null) payload.put("contextSnapshot", args.getJSONObject("contextSnapshot"));
        }
        JSONObject intent = route.prepare(method, payload, hostOperation, tools, models, args.optJSONObject("observationRef"), original);
        if (!hostOperation.equals(intent.optString("operationId")) || !route.targetDeviceId().equals(intent.optString("targetDeviceId"))) throw new SecurityException("原操作或本机目标路由身份不一致");
        TaskStoreDeviceTasks.ChildReservation reserved = devices.reserveChildNative(taskId, runId, epoch, original, hostOperation, intent,route.reservationApproval());
        if (reserved.knownOperation()) return new JSONObject().put("status", "needs_reconcile").put("operationId", hostOperation).put("knownOperation", true).put("nextAction", "device_operation_get");
        DeviceTaskEnvelope request = route.finalizeReserved(intent, reserved);
        TaskStoreDeviceTasks.OutboundSendTicket ticket=devices.bindReservedWire(reserved,request,route.receivingGrant());
        try {
            Reply actual = route.exchange(request,ticket, original); JSONObject result = reply(route, actual,request);
            if (Boolean.TRUE.equals(result.opt("accepted"))) {
                recordAccepted(route, actual, result, hostOperation, request.argumentsDigest());captureSemantic(route,hostOperation,result);captureImage(route,actual,hostOperation,name,result);receiveFile(route,actual,hostOperation,result);sendFile(route,actual,hostOperation,result);
                return parentView(result,new JSONObject().put("parentOperationId",hostOperation).put("budgetReserved",true).put("nextAction","device_operation_get"));
            }
            return parentView(result,new JSONObject().put("budgetReserved",true).put("operationId",hostOperation));
        } catch (Exception unknown) {
            return new JSONObject().put("ok", false).put("code", "device_effect_unknown").put("accepted", JSONObject.NULL)
                    .put("operationId", hostOperation).put("budgetReserved", true).put("nextAction", "device_operation_get");
        }
    }
    private static JSONObject parentView(JSONObject verifiedReceipt,JSONObject nativeFacts)throws Exception{JSONObject result=new JSONObject(verifiedReceipt.toString()),payload=new JSONObject(result.getJSONObject("payload").toString());payload.put("nativeParent",nativeFacts);return result.put("payload",payload);}
    private void recordAccepted(NativeRoute route, Reply actual, JSONObject result, String operation, String digest) throws Exception {
        JSONObject allocation = devices.childAllocationForOperation(taskId, operation);
        if (!digest.equals(allocation.optString("wireDigest"))) throw new SecurityException("回执摘要不是该原操作已绑定wire");
        JSONObject wire=allocation.getJSONObject("boundWire"),audience=result.getJSONObject("audience");
        for(String field:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","instanceId","rootTaskId","localTaskId","grantId","authorizationEpoch"})if(!DeviceProtocol.canonical(wire.opt(field)).equals(DeviceProtocol.canonical(audience.opt(field))))throw new SecurityException("接受受众不是该原prepared wire的准确身份/关联");
        if(!DeviceProtocol.resourceEquals(wire.getJSONObject("resourceBinding"),audience.getJSONObject("resourceBinding"))||!wire.getString("runId").equals(audience.optString("requestRunId"))||wire.getLong("executorEpoch")!=audience.optLong("requestExecutorEpoch"))throw new SecurityException("原资源/source run关联被回执替换");
        String kind=result.has("localTaskId")?"task":result.has("jobId")?"job":"operation",object=result.has("localTaskId")?result.getString("localTaskId"):result.has("jobId")?result.getString("jobId"):operation;
        if(kind.equals("task")&&(!DeviceProtocol.receiverTaskId(wire).equals(object)||audience.has("taskId")&&!object.equals(audience.getString("taskId"))))throw new SecurityException("目标nativeTask绑定不正确，确定性ID仅关联不授信");
        JSONObject accepted = new JSONObject().put("accepted", true).put("acceptedAt", result.getLong("acceptedAt"))
                .put("acceptedObjectKind",kind).put("objectId",object).put("targetOperationId",operation).put("instanceId", actual.response().getString("instanceId"))
                .put("runId",result.optString("runId")).put("parentTaskId", taskId).put("parentOperationId", allocation.getString("anchorOperationId"))
                .put("sourceOperationId",operation).put("operationArgumentsDigest",result.getString("argumentsDigest"))
                .put("verifiedOperationReceipt",new JSONObject(result.toString()))
                .put("argumentsDigest", allocation.getString("anchorArgumentsDigest")).put("executorDeviceId", route.targetDeviceId());
        if(result.has("localTaskId"))accepted.put("localTaskId",object);if(result.has("jobId"))accepted.put("jobId",object);if(result.has("executorEpoch"))accepted.put("executorEpoch",result.getLong("executorEpoch"));
        devices.recordChildAccepted(coordinator.authenticatedChildAcceptance(actual.peer(), accepted));
    }
    static JSONObject reply(NativeRoute route, Reply reply,DeviceTaskEnvelope request) throws Exception {
        if (reply == null || reply.peer() == null || !route.targetDeviceId().equals(reply.peer().deviceId()) || reply.response() == null
                || !DeviceProtocol.VERSION.equals(reply.response().optString("protocolVersion")) || !route.targetDeviceId().equals(reply.response().optString("executorDeviceId")))
            throw new SecurityException("回执不是原目标的已认证连接");
        route.requireReplyPeer(reply.peer(),request);DeviceProtocol.validateMessage(reply.response());
        if(!"DeviceResponse".equals(reply.response().optString("messageType"))||!request.requestId().equals(reply.response().optString("requestId"))||!request.method().equals(reply.response().optString("method"))||!route.receivingGrant().targetInstanceId().equals(reply.response().optString("instanceId")))throw new SecurityException("响应不是该原request/method/目标实例");
        JSONObject result = reply.response().optJSONObject("result"); if (result == null||result.length()==0||!(result.opt("accepted") instanceof Boolean)) throw new SecurityException("缺少明确原设备接受/未接受回执；空object不算成功");
        String operation=request.method().equals("operation.get")?request.arguments().getString("operationId"):request.operationId();if(!operation.equals(result.optString("operationId")))throw new SecurityException("回执不是该原operation");
        if(result.getBoolean("accepted")&&(result.optLong("acceptedAt")<1||!result.optString("argumentsDigest").matches("[a-f0-9]{64}")||!request.method().equals("operation.get")&&!request.argumentsDigest().equals(result.optString("argumentsDigest"))))throw new SecurityException("accepted缺少同原操作已SQL持久的完整绑定");
        return new JSONObject(result.toString());
    }
    private void sendFile(NativeRoute route,Reply actual,String operation,JSONObject receipt)throws Exception{
        try{route.acceptedTransfer(devices.childAllocationForOperation(taskId,operation).getJSONObject("boundWire"),actual,receipt);}
        catch(Exception unavailable){receipt.getJSONObject("payload").put("nativeFileSend",new JSONObject().put("queued",false).put("delivered",false).put("operationId",operation).put("nextAction","device_operation_get").put("retrySubmitted",false));}
    }
    private void receiveFile(NativeRoute route,Reply actual,String operation,JSONObject receipt)throws Exception{
        JSONObject wire=devices.childAllocationForOperation(taskId,operation).getJSONObject("boundWire");
        if(!"ToolInvocation".equals(wire.optString("messageType"))||!java.util.Set.of("workspace.export","workspace.project.export").contains(wire.getJSONObject("arguments").optString("toolName")))return;
        JSONObject payload=receipt.getJSONObject("payload");
        try{
            if(files==null)throw new SecurityException("原附件接收器未安装");
            JSONObject published=files.publish(devices.deviceFileEvidence(route.reservationApproval(),original,actual.peer(),operation));
            payload.put("nativeFileDelivery",published);
        }catch(Exception unavailable){
            // Keep the authenticated operation outcome. Retry means read the SAME operation,
            // resume its fixed bytes, and reuse the Book publication identity; never export again.
            payload.put("nativeFileDelivery",new JSONObject().put("attachmentPublished",false).put("bytesVerified",false)
                .put("operationId",operation).put("nextAction","device_operation_get").put("retrySubmitted",false).put("reason","original_file_receive_unavailable"));
        }
    }
    private void captureImage(NativeRoute route,Reply actual,String operation,String function,JSONObject receipt)throws Exception{
        if(!visual.installed())return;
        // Image enrichment is optional. An already authenticated accepted/terminal receipt must
        // remain usable even if its image is absent, malformed, stale or cannot be downloaded.
        JSONObject payload=receipt.getJSONObject("payload");
        try{
            JSONObject wire=devices.childAllocationForOperation(taskId,operation).getJSONObject("boundWire");
            if(!"ToolInvocation".equals(wire.optString("messageType")))return;
            String tool=wire.getJSONObject("arguments").getString("toolName");
            JSONObject observation=tool.equals("desktop.observe")?payload:tool.equals("desktop.act")?payload.optJSONObject("desktopReceipt")==null?null:payload.getJSONObject("desktopReceipt").optJSONObject("afterObservation"):tool.equals("phone_observe")?payload.optJSONObject("receipt"):null;
            if(observation==null||!observation.has(tool.startsWith("desktop.")?"capture":"imageArtifact"))return;
            visual.capture(route.routeId(),function,devices.deviceImageEvidence(route.reservationApproval(),original,actual.peer(),operation));
        }catch(Exception invalid){visual.invalidate(route.routeId());payload.put("nativeImageInput",new JSONObject().put("available",false).put("imageProvidedToModel",false).put("reason","verified_image_unavailable_or_stale"));}
    }
    private boolean requiresDesktopImage(NativeRoute route,String tool,JSONObject arguments,JSONObject ref)throws Exception{
        if(tool.equals("desktop.observe"))return arguments==null||!arguments.optBoolean("discovery")&&!"semantic".equals(arguments.optString("observationMode",arguments.has("windowId")?"semantic":"hybrid"));
        if(tool.equals("desktop.act"))return !semantic.contains(route.routeId(),ref);
        return false;
    }
    private void captureSemantic(NativeRoute route,String operation,JSONObject receipt)throws Exception{
        JSONObject wire=devices.childAllocationForOperation(taskId,operation).getJSONObject("boundWire");
        if(!"ToolInvocation".equals(wire.optString("messageType"))||!wire.getJSONObject("arguments").optString("toolName").matches("desktop\\.(observe|act)"))return;
        final String originalWire=DeviceProtocol.canonical(wire);final long pause=original.pauseRevision();
        try{semantic.capture(route.routeId(),wire,receipt,()->{route.requireCurrent(System.currentTimeMillis());original.check();if(original.isPaused()||pause!=original.pauseRevision()||!originalWire.equals(DeviceProtocol.canonical(devices.childAllocationForOperation(taskId,operation).getJSONObject("boundWire"))))throw new SecurityException("原结构观察任务/范围已变化");});}
        catch(Exception unavailable){semantic.invalidate(route.routeId());receipt.getJSONObject("payload").put("nativeSemanticInput",new JSONObject().put("available",false).put("reason","verified_structure_unavailable_or_stale"));}
    }
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{
        NativeRoute selected=route(args,control);String tool=args.optString("toolName");JSONObject nativeArguments=args.optJSONObject("arguments"),reference=args.optJSONObject("observationRef");if(name.equals("device_operation_get")){JSONObject wire=devices.childAllocationForOperation(taskId,args.getString("operationId")).getJSONObject("boundWire");tool=wire.getJSONObject("arguments").optString("toolName");nativeArguments=wire.getJSONObject("arguments").optJSONObject("arguments");reference=wire.optJSONObject("observationRef");}
        boolean needs=requiresDesktopImage(selected,tool,nativeArguments,reference)||tool.equals("phone_observe")&&nativeArguments!=null&&nativeArguments.optBoolean("includeImage");if(needs&&!visual.installed())throw new SecurityException("真实设备视觉接收器未安装");return needs?dev.pocketengine.core.NativeToolObservation.Requirement.IMAGE:dev.pocketengine.core.NativeToolObservation.Requirement.NONE;
    }
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{
        NativeRoute selected=route(args,control);String id=selected.routeId();if(name.equals("device_invoke_tool")){String tool=args.getString("toolName");if(tool.equals("desktop.act")){if(semantic.contains(id,args.optJSONObject("observationRef")))semantic.requireAction(id,args.optJSONObject("observationRef"));else visual.requireAction(id,args.optJSONObject("observationRef"));}if(tool.equals("desktop.observe")||tool.equals("phone_observe")){visual.invalidate(id);semantic.invalidate(id);}}return null;
    }
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{return visual.take(name,operation,control);}
    @Override public void beforeModelRequest(String request,JSONArray messages,RunControl control)throws Exception{visual.beforeRequest(request,messages,control);semantic.beforeRequest(request,messages,control);}
    @Override public JSONObject afterModelResponse(String request,String answer,RunControl control)throws Exception{JSONObject image=visual.afterResponse(request,control),structure=semantic.afterResponse(request,control);return image==null?structure:structure==null?image:new JSONObject().put("visual",image).put("semantic",structure).put("businessVerified",false);}
    @Override public void modelRequestAbandoned(String request){visual.abandoned(request);semantic.abandoned(request);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{if(closed||control!=original)return false;for(var route:routes.values())try{route.requireCurrent(System.currentTimeMillis());}catch(SecurityException revoked){return false;}return true;}
    @Override public void close() { closed = true;visual.close();semantic.close(); }
}
