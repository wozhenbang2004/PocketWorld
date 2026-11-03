package dev.pocketengine.features.remote;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-private remote delegation on the original Runner journal. No transport, scheduler or task store. */
public final class RemoteTaskCapabilities implements CapabilityPort {
    public static final String START="remote_start_task",READ="remote_read_task";
    private final CapabilityPort existing;
    private final TaskStore tasks;
    private final FeatureHost host;
    private final RemoteModule remote;
    private final String task,run;
    private final int epoch;
    private final RunControl live;
    private final JSONArray definitions;
    private boolean closed;
    public RemoteTaskCapabilities(CapabilityPort existing,TaskStore tasks,FeatureHost host,RemoteModule remote,String task,String run,int epoch,RunControl live)throws Exception {
        this.existing=java.util.Objects.requireNonNull(existing);this.tasks=java.util.Objects.requireNonNull(tasks);this.host=java.util.Objects.requireNonNull(host);this.remote=java.util.Objects.requireNonNull(remote);
        this.task=task;this.run=run;this.epoch=epoch;this.live=java.util.Objects.requireNonNull(live);privateTask();definitions=definitions();
        if(existing.supports(START)||existing.supports(READ))throw new IllegalStateException("远端任务工具已挂载");
    }
    public static boolean eligible(JSONObject task) {
        JSONObject origin=task==null?null:task.optJSONObject("origin");
        return task!=null&&task.optString("kind").matches("assistant|phone")&&task.optString("actorId").equals("owner")&&task.optString("spaceId").equals("private")
                &&(origin==null||!origin.optString("kind").matches("channel|group|team_assignment|team_integration"));
    }
    private void privateTask()throws Exception {
        JSONObject current=tasks.get(task,false);if(!eligible(current))throw new SecurityException("只有原私人主人任务可委派电脑工作，群、渠道与团队不能继承此权限");
    }
    private void current(String name,RunControl control)throws Exception {
        if(closed||control!=live||!live.matchesHostRun(task,run,epoch))throw new SecurityException("远端委派没有原任务实时控制");
        control.check();privateTask();tasks.checkCapability(task,run,epoch,name,control);
    }
    private static boolean own(String name){return name.equals(START)||name.equals(READ);}
    private static JSONObject string(int max)throws Exception{return new JSONObject().put("type","string").put("minLength",1).put("maxLength",max);}
    private static JSONArray definitions()throws Exception {
        JSONObject properties=new JSONObject().put("profileId",string(80)).put("bindingHash",string(64)).put("environmentId",string(80)).put("environmentBinding",string(64))
                .put("worker",new JSONObject().put("type","string").put("enum",new JSONArray().put("codex_app_server").put("claude").put("pi").put("command")))
                .put("prompt",string(12000)).put("model",string(200)).put("readOnly",new JSONObject().put("type","boolean"))
                .put("argv",new JSONObject().put("type","array").put("minItems",1).put("maxItems",80).put("items",new JSONObject().put("type","string").put("maxLength",12000)))
                .put("timeoutSeconds",new JSONObject().put("type","integer").put("minimum",10).put("maximum",3600));
        return new JSONArray().put(definition(START,"在当前私人任务下准备一次全新电脑工作。先用remote_profiles/remote_environments读取真实profile bindingHash及environment bindingHash，再给Codex/Claude目标、Pi无工具对话或明确命令argv。Pi不提供编程工具、逐工具审批或只读沙箱。只在主人对本次环境、权限和参数单次批准后经原TaskStore登记/派发。没有导入thread/resume，不重提未知操作；不是手机本地执行。",properties,new JSONArray().put("profileId").put("bindingHash").put("environmentId").put("environmentBinding").put("worker")))
                .put(definition(READ,"按原子任务编号查询本私人任务已登记的电脑工作和实际output/diff；只查原操作，不提交、重试、续接或代答工具审批。",new JSONObject().put("operationId",string(80)),new JSONArray().put("operationId")));
    }
    private static JSONObject definition(String name,String description,JSONObject properties,JSONArray required)throws Exception {
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false)));
    }
    @Override public JSONArray tools()throws Exception {JSONArray result=new JSONArray(existing.tools().toString());for(int i=0;i<definitions.length();i++)result.put(new JSONObject(definitions.getJSONObject(i).toString()));return result;}
    @Override public boolean supports(String name){return own(name)||existing.supports(name);}
    @Override public boolean sideEffecting(String name){return own(name)?name.equals(START):existing.sideEffecting(name);}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!own(name)){existing.validate(name,args);return;}
        if(args==null)throw new IllegalArgumentException("请提供具体远端任务参数");
        if(name.equals(START)){RemoteTaskRequest.first(args);return;}
        if(args.length()!=1||!(args.opt("operationId") instanceof String)||!args.getString("operationId").matches("[a-f0-9-]{36}"))throw new SecurityException("只能查询同一私人任务的原子操作编号");
    }
    /** Fresh read-side capability evidence, then a native single-use approval for the exact snapshot. */
    private JSONObject selection(JSONObject args)throws Exception {
        JSONObject payload=RemoteTaskRequest.first(args),profile=remote.requireProfileBinding(payload.getString("profileId"),payload.getString("_profileBinding"));
        JSONObject query=new JSONObject().put("profileId",payload.getString("profileId")).put("_profileBinding",payload.getString("_profileBinding"));
        JSONObject environment=RemoteTaskRequest.environment(remote.handle("environments",query),payload.getString("environmentId"),payload.getString("worker"));
        if(!payload.getString("environmentBinding").equals(environment.optString("bindingHash")))throw new SecurityException("工作区或远端实例已变化，未准备旧目标，请重新读取环境");
        JSONObject proof=new JSONObject().put("kind","remote_first_create").put("profileId",profile.getString("id")).put("profileName",profile.optString("name")).put("serviceBase",profile.getString("serviceBase"))
                .put("bindingHash",profile.getString("bindingHash")).put("environment",environment);
        if(!payload.getString("worker").equals("command")){
            JSONObject probeInput=new JSONObject(query.toString()).put("environmentId",payload.getString("environmentId")).put("environmentBinding",payload.getString("environmentBinding")).put("worker",payload.getString("worker"));
            if(payload.has("model"))probeInput.put("model",payload.getString("model"));
            JSONObject probe=remote.handle("probeWorker",probeInput);if(!probe.optBoolean("available"))throw new SecurityException("所选执行器的实际探测未通过，尚未提交电脑任务");
            JSONObject checked=new JSONObject();for(String key:new String[]{"worker","environmentId","available","protocolVerified","cliVersion","cliFlagsVerified","accountConfigured","configReadVerified","selectedModelAvailable","noTools","autoCompactionEnabled","capabilities"})if(probe.has(key))checked.put(key,probe.get(key));proof.put("workerProbe",checked);
        }
        remote.requireProfileBinding(payload.getString("profileId"),payload.getString("_profileBinding"));return proof;
    }
    private void prepared(String operation,JSONObject args)throws Exception {
        if(!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("必须使用原AgentRunner已经登记的operation");
        JSONArray rows=tasks.get(task,false).getJSONArray("operations");JSONObject selected=null;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(operation.equals(row.optString("operationId")))selected=row;else if(row.optString("state").matches("prepared|unknown"))throw new SecurityException("原任务还有未决操作，不能派发另一项电脑工作");}
        if(selected==null||!START.equals(selected.optString("tool"))||!run.equals(selected.optString("runId"))||!"prepared".equals(selected.optString("state"))||!TaskStore.capabilityFingerprint(args).equals(TaskStore.capabilityFingerprint(selected.getJSONObject("arguments"))))throw new SecurityException("电脑工作必须对应原任务本轮精确prepared参数");
    }
    @Override public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception {
        if(!own(name))return existing.execute(name,input,control);current(name,control);
        JSONObject args=new JSONObject(input.toString());String operation=args.optString("_hostOperationId");args.remove("_hostOperationId");validate(name,args);
        if(name.equals(READ))return host.readRemoteForTask(task,run,epoch,args.getString("operationId"),control);
        prepared(operation,args);JSONObject proof=selection(args);current(name,control);
        JSONObject approval=tasks.requireCapabilityApproval(task,run,name,args,proof);
        if(!approval.optBoolean("approved")){
            host.emitApproval(new JSONObject().put("taskId",task).put("runId",run).put("approvalId",approval.getString("approvalId")).put("tool",name).put("capability",proof).put("arguments",args).put("message","请核对本次电脑、工作区、模型/命令和写入边界；尚未派发"));
            return new JSONObject().put("ok",false).put("accepted",false).put("dispatched",false).put("code","approval_required").put("approvalId",approval.getString("approvalId")).put("message","本次电脑工作等待主人单次批准；未提交远端任务");
        }
        current(name,control);prepared(operation,args);
        return host.startRemoteForTask(task,run,epoch,operation,RemoteTaskRequest.first(args),proof,control);
    }
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {current(READ,control);JSONObject result=existing.prepareTurn(query,round,steps,control);JSONArray list=result.optJSONArray("tools");if(list==null)list=existing.tools();list=new JSONArray(list.toString());for(int i=0;i<definitions.length();i++)list.put(new JSONObject(definitions.getJSONObject(i).toString()));return result.put("tools",list);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{current(READ,control);existing.beginRun(goal,control);}
    @Override public void admitCapabilities(CapabilitySnapshot snapshot,RunControl control)throws Exception{current(READ,control);existing.admitCapabilities(snapshot,control);}
    @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{current(READ,control);return existing.responseCurrent(control);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{current(READ,control);return own(name)?null:existing.beforeTool(name,args,control);}
    @Override public void afterTool(String name,JSONObject args,JSONObject result,RunControl control)throws Exception{current(READ,control);existing.afterTool(name,args,result,control);}
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{current(READ,control);return own(name)?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,control);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{current(READ,control);return own(name)?null:existing.takeToolObservation(name,operation,control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{current(READ,control);existing.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{current(READ,control);return existing.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{existing.modelRequestAbandoned(requestId);}
    @Override public void endRun(RunControl control)throws Exception{existing.endRun(control);}
    @Override public void close(){closed=true;existing.close();}
}
