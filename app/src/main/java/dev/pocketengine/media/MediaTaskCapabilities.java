package dev.pocketengine.media;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Media is a tool of the original admitted goal, with its original TaskStore/AgentRunner journal. No task creation or executor. */
public final class MediaTaskCapabilities implements CapabilityPort {
    public interface OriginalRead {JSONObject read(String command,JSONObject input,RunControl control)throws Exception;}
    private final CapabilityPort existing;private final TaskStore tasks;private final MediaModule media;private final OriginalRead reads;
    private final String task,run,revision;private final int epoch;private final RunControl live;private final JSONArray definitions=new JSONArray();
    private final Set<String> generators=new HashSet<>(),entered=new HashSet<>();private volatile boolean closed;
    public MediaTaskCapabilities(CapabilityPort existing,TaskStore tasks,MediaModule media,OriginalRead reads,String task,String run,int epoch,RunControl live)throws Exception {
        this.existing=java.util.Objects.requireNonNull(existing);this.tasks=java.util.Objects.requireNonNull(tasks);this.media=java.util.Objects.requireNonNull(media);this.reads=java.util.Objects.requireNonNull(reads);this.task=task;this.run=run;this.epoch=epoch;this.live=live;
        JSONObject snapshot=media.snapshot(),config=snapshot.getJSONObject("configuration");revision=snapshot.getString("configurationRevision");
        if(snapshot.optBoolean("configured")){
            boolean compatible=config.optString("provider").equals("openai_compatible"),comfy=config.optString("provider").equals("comfyui");
            if(compatible&&!config.optString("imageModel").isEmpty()||comfy&&config.optBoolean("hasWorkflow"))addGeneration("media_generate_image","prompt","为当前原任务生成图片。服务、模型和工作流由主人配置；执行前核对本次参数，未知效果只查询原操作。 ");
            if(compatible&&!config.optString("ttsModel").isEmpty())addGeneration("media_synthesize_speech","text","为当前原任务生成音频文件。保留原操作与真实附件；不启动实时语音、不宣称消息已发送。 ");
        }
        JSONObject properties=new JSONObject().put("operationId",new JSONObject().put("type","string").put("pattern","^[a-f0-9-]{36}$"))
                .put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("status").put("results")));
        add("media_read_original","只查询或获取同一原任务已登记的媒体操作，不重新提交。下载完成不等于目标或外发完成。",properties,new JSONArray().put("operationId").put("action"));
        for(int i=0;i<definitions.length();i++)if(existing.supports(definitions.getJSONObject(i).getJSONObject("function").getString("name")))throw new IllegalStateException("媒体工具名已被其他宿主挂载");
    }
    private void addGeneration(String name,String field,String description)throws Exception {
        generators.add(name);JSONObject properties=new JSONObject().put(field,new JSONObject().put("type","string").put("minLength",1).put("maxLength",name.equals("media_synthesize_speech")?MediaTransport.MAX_SPEECH_INPUT_CHARS:MediaTransport.MAX_IMAGE_INPUT_CHARS))
                .put("configurationRevision",new JSONObject().put("type","string").put("enum",new JSONArray().put(revision)));
        add(name,description,properties,new JSONArray().put(field).put("configurationRevision"));
    }
    private void add(String name,String description,JSONObject properties,JSONArray required)throws Exception {definitions.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false))));}
    private boolean own(String name){return generators.contains(name)||name.equals("media_read_original");}
    private void current(RunControl control)throws Exception {if(closed||control!=live||!live.matchesHostRun(task,run,epoch))throw new SecurityException("媒体没有原任务的实时控制");control.check();tasks.checkCapability(task,run,epoch,"media_read_original",control);}
    @Override public JSONArray tools()throws Exception {JSONArray result=new JSONArray(existing.tools().toString());for(int i=0;i<definitions.length();i++)result.put(new JSONObject(definitions.getJSONObject(i).toString()));return result;}
    @Override public boolean supports(String name){return own(name)||existing.supports(name);}
    @Override public boolean sideEffecting(String name){return own(name)?generators.contains(name):existing.sideEffecting(name);}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!own(name)){existing.validate(name,args);return;}
        Set<String> allowed=name.equals("media_read_original")?Set.of("operationId","action"):Set.of(name.equals("media_generate_image")?"prompt":"text","configurationRevision");
        if(args==null||args.length()!=allowed.size())throw new IllegalArgumentException("媒体参数不完整");for(String key:allowed)if(!(args.opt(key) instanceof String))throw new IllegalArgumentException("媒体参数必须是明确文字");
        java.util.Iterator<String> keys=args.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new SecurityException("媒体参数不能覆盖服务、凭据、任务或路由");
        if(name.equals("media_read_original")){if(!args.getString("operationId").matches("[a-f0-9-]{36}")||!args.getString("action").matches("status|results"))throw new IllegalArgumentException("请选择原媒体操作及查询动作");}
        else {String text=args.getString(name.equals("media_generate_image")?"prompt":"text");if(text.isBlank()||text.length()>(name.equals("media_synthesize_speech")?MediaTransport.MAX_SPEECH_INPUT_CHARS:MediaTransport.MAX_IMAGE_INPUT_CHARS)||!revision.equals(args.getString("configurationRevision")))throw new SecurityException("媒体内容或原生配置版本已变化");}
    }
    @Override public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception {
        if(!own(name))return existing.execute(name,input,control);current(control);tasks.checkCapability(task,run,epoch,name,control);
        JSONObject args=new JSONObject(input.toString());String operation=args.optString("_hostOperationId");args.remove("_hostOperationId");validate(name,args);
        if(name.equals("media_read_original")){
            if(!operation.isEmpty())throw new SecurityException("读取不能携带新operation");JSONObject source=tasks.ownerMediaOperationSource(task,args.getString("operationId"));
            return reads.read(args.getString("action").equals("results")?"fetchResults":"getJob",new JSONObject().put("taskId",task).put("operationId",source.getString("operationId")).put("runId",source.getString("runId")).put("epoch",source.getInt("epoch")),control);
        }
        if(!revision.equals(media.snapshot().getString("configurationRevision")))throw new SecurityException("媒体服务已变化，请在原任务重新核对");
        JSONObject source=tasks.ownerMediaOperationSource(task,operation);
        if(!source.getString("runId").equals(run)||source.getInt("epoch")!=epoch||!source.getString("tool").equals(name)||!source.getString("argumentsHash").equals(TaskStore.capabilityFingerprint(args))||!source.getString("state").equals("prepared"))throw new SecurityException("媒体必须使用原AgentRunner已准备的准确操作");
        JSONArray operations=tasks.get(task,false).getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject other=operations.getJSONObject(i);if(other.optString("state").matches("prepared|unknown")&&(!operation.equals(other.optString("operationId"))||!other.optString("state").equals("prepared")))throw new SecurityException("原任务还有未决操作，不能派发另一个媒体效果");}
        JSONObject capability=new JSONObject().put("kind","media").put("tool",name).put("configurationRevision",revision),approval=tasks.requireCapabilityApproval(task,run,name,args,capability);
        if(!approval.optBoolean("approved"))return new JSONObject().put("ok",false).put("accepted",false).put("dispatched",false).put("code","approval_required").put("approvalId",approval.getString("approvalId")).put("message","本次媒体内容及服务配置等待主人核对，尚未提交");
        current(control);synchronized(entered){if(entered.size()>=1000)throw new IllegalStateException("本次媒体调用数超限");if(!entered.add(operation))throw new SecurityException("原媒体操作已进入执行，只可查询原编号");}
        JSONObject payload=new JSONObject().put("_operationId",operation).put("_taskId",task).put("_runId",run).put("_epoch",epoch).put("_configurationRevision",revision);
        boolean image=name.equals("media_generate_image");payload.put(image?"prompt":"text",args.getString(image?"prompt":"text"));
        // MediaJobs durably records dispatch before its one POST and preserves uncertain effects.
        return media.handleForHost(image?"generateImage":"synthesizeSpeech",payload,control);
    }
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {current(control);JSONObject result=existing.prepareTurn(query,round,steps,control);if(result==null)result=new JSONObject();JSONArray prepared=result.optJSONArray("tools");if(prepared==null)prepared=existing.tools();prepared=new JSONArray(prepared.toString());for(int i=0;i<definitions.length();i++)prepared.put(new JSONObject(definitions.getJSONObject(i).toString()));return result.put("tools",prepared);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control)throws Exception{current(control);existing.admitCapabilities(snapshot,control);}
    @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{current(control);return existing.beforeTool(name,args,control);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{current(control);existing.beginRun(goal,control);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{current(control);return existing.responseCurrent(control);}
    @Override public void afterTool(String name,JSONObject args,JSONObject result,RunControl control)throws Exception{current(control);existing.afterTool(name,args,result,control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{current(control);existing.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{current(control);return existing.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{existing.modelRequestAbandoned(requestId);}
    @Override public void endRun(RunControl control)throws Exception{existing.endRun(control);}
    @Override public void close(){closed=true;existing.close();}
}
