package dev.pocketengine.features.automation;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Narrow the existing task's capability port to its reviewed project and output file. */
public final class PlanWorkspacePort implements CapabilityPort {
    private static final Set<String> TOOLS=Set.of("workspace_list","workspace_read","workspace_history","workspace_diff","workspace_write","workspace_artifacts","workspace_read_artifact","workspace_diff_artifact","workspace_create_artifact");
    private final CapabilityPort original;private final JSONObject standard;private final PlanArtifactVerifier.Source artifacts;
    public interface PrivateFreezeScope {void prepare(JSONObject arguments)throws Exception;}
    private final PrivateFreezeScope freezeScope;
    public PlanWorkspacePort(CapabilityPort original,JSONObject standard,PlanArtifactVerifier.Source artifacts)throws Exception{
        this(original,standard,artifacts,null);
    }
    public PlanWorkspacePort(CapabilityPort original,JSONObject standard,PlanArtifactVerifier.Source artifacts,PrivateFreezeScope freezeScope)throws Exception{
        this.original=java.util.Objects.requireNonNull(original);this.standard=PlanArtifactVerifier.standard(standard);this.artifacts=java.util.Objects.requireNonNull(artifacts);
        this.freezeScope=freezeScope;
        if(!this.standard.getString("kind").equals("workspace_artifact"))throw new SecurityException("工作区执行没有明确可核验目标文件");
    }
    private JSONArray filter(JSONArray tools)throws Exception{JSONArray result=new JSONArray();for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if(TOOLS.contains(tool.getJSONObject("function").getString("name")))result.put(tool);}return result;}
    @Override public JSONArray tools()throws Exception{return filter(original.tools());}
    @Override public boolean supports(String name){return TOOLS.contains(name)&&original.supports(name);}
    @Override public boolean sideEffecting(String name){return supports(name)&&original.sideEffecting(name);}
    private JSONObject scoped(String name,JSONObject args)throws Exception{
        if(!supports(name))throw new SecurityException("此目标仅准备了所选项目的文件能力");JSONObject checked=new JSONObject(args.toString());checked.remove("_hostOperationId");
        if(name.equals("workspace_read_artifact")||name.equals("workspace_diff_artifact")){
            JSONObject artifact=artifacts.read(checked.getString("artifactId"));if(artifact==null||!standard.getString("projectId").equals(artifact.optString("projectId")))throw new SecurityException("成果不属于目标授权的项目");
        }else{
            if(name.equals("workspace_artifacts")&&!checked.has("projectId"))checked.put("projectId",standard.getString("projectId"));
            if(!standard.getString("projectId").equals(checked.optString("projectId")))throw new SecurityException("不能切换到其他私人项目");
            if(name.matches("workspace_write|workspace_create_artifact")&&!standard.getString("path").equals(checked.optString("path")))throw new SecurityException("写入或冻结只限机主审阅的目标文件");
        }original.validate(name,checked);return checked;
    }
    @Override public void validate(String name,JSONObject args)throws Exception{scoped(name,args);}
    @Override public JSONObject contextSnapshot(String query)throws Exception{
        return new JSONObject().put("context","本目标只授权项目 "+standard.getString("projectId")+" 的文件操作。可读所选项目，写入与冻结仅限 "+standard.getString("path")+"。结果需冻结为本任务的正式文件成果，第一行必须为："+standard.getString("requiredHeading")+"；最少 "+standard.getLong("minBytes")+" UTF-8 字节。冻结成功后结束本次工作，原生宿主会读真实文件核验。具体敏感写入仍使用原任务的审批。").put("verification",new JSONObject(standard.toString()));
    }
    @Override public void beginRun(String goal,RunControl control)throws Exception{original.beginRun(goal,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{JSONObject turn=original.prepareTurn(query,round,steps,control);turn.put("tools",filter(turn.getJSONArray("tools"))).put("reference",contextSnapshot(query));return turn;}
    @Override public boolean responseCurrent(RunControl control)throws Exception{return original.responseCurrent(control);}
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{return supports(name)?original.observationRequirement(name,args,control):dev.pocketengine.core.NativeToolObservation.Requirement.NONE;}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{return supports(name)?original.takeToolObservation(name,operation,control):null;}
    @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String name,String operation,RunControl control)throws Exception{return supports(name)?original.takeToolPlan(name,operation,control):null;}
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl control)throws Exception{return supports("browser_task_observe")?original.nativeReadContextRepair(messages,control):null;}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{scoped(name,args);return original.beforeTool(name,args,control);}
    @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{
        if(control.isPaused()||control.isCancelled()||Thread.currentThread().isInterrupted())return new JSONObject().put("ok",false).put("accepted",false).put("code","plan_control_before_dispatch").put("message","计划已暂停或停止，未进入文件操作");
        JSONObject checked=scoped(name,args);if(name.equals("workspace_create_artifact")&&freezeScope!=null)freezeScope.prepare(checked);
        if(args.has("_hostOperationId"))checked.put("_hostOperationId",args.getString("_hostOperationId"));return original.execute(name,checked,control);
    }
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception{original.afterTool(name,args,receipt,control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{original.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{return original.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{original.modelRequestAbandoned(requestId);}
    @Override public void endRun(RunControl control)throws Exception{original.endRun(control);}
    @Override public void close(){original.close();}
}
