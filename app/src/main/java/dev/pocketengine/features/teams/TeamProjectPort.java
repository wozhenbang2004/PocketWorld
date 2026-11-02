package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectCapabilityPort;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Filters the original project port to one native team checkout. No second executor or private-origin impersonation. */
final class TeamProjectPort implements CapabilityPort {
    interface Current {void check()throws Exception;}
    private final TaskStore tasks;private final LocalModule local;private final JSONObject binding;private final RunControl original;
    private final CapabilityPort files;private final Current current;private ProjectCapabilityPort project;private volatile boolean closed;
    TeamProjectPort(TaskStore tasks,LocalModule local,JSONObject binding,RunControl original,CapabilityPort files,Current current)throws Exception{this.tasks=tasks;this.local=local;this.binding=new JSONObject(binding.toString());this.original=original;this.files=files;this.current=current;}
    private void check(RunControl control)throws Exception{if(closed||control!=original)throw new SecurityException("原成员项目工具已关闭或控制改变");current.check();tasks.checkTeamAssignmentExecution(binding,original);}
    private void checkRenewedSource(RunControl control)throws Exception {
        check(control);JSONObject pin=tasks.teamIntegratorScopeExecutionFacts(binding,original);if(pin==null)return;
        // Never hold Tasks across checkout IO. Original SQL scope/control is checked again
        // after the exact source/runtime comparison and again by native command dispatch.
        JSONObject source=dev.pocketengine.features.local.project.ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",pin.getString("checkoutId"))));JSONObject runtime=local.projectRuntimeForHost(pin.getString("runtimeId"));
        if(!pin.getString("sourceDigest").equals(source.getString("sourceDigest"))||!pin.getString("runtimeIdentity").equals(runtime.getString("identity")))throw new SecurityException("原整合续审源码/运行环境已变化，停止派发，请重新审阅");tasks.teamIntegratorScopeExecutionFacts(binding,original);
    }
    private boolean fileTool(String name){return name!=null&&name.matches("team_(prepare_draft|list_files|read_dependency|request_input|send_message|freeze_outputs|read_integration|integrate_file|prepare_integrated_project)");}
    private boolean projectTool(String name){return ProjectCapabilityPort.isTeamProjectRead(name)||name!=null&&name.matches("project_(execute|process_input|stop)");}
    @Override public void prepareTurnSurface(RunControl control)throws Exception {
        check(control);if(project==null){JSONObject context=tasks.teamProjectContextIfReady(binding,original);if(context!=null){ModelCapabilityAdapters.Binding nativeBinding=new ModelCapabilityAdapters.Binding(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),original,true,context.getString("projectId"));TaskStoreCapabilityAuthority authority=new TaskStoreCapabilityAuthority(tasks,nativeBinding,event->{try{tasks.event(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),event);}catch(Exception failure){original.cancel();throw new IllegalStateException("原项目审批通知未持久化",failure);}});project=new ProjectCapabilityPort(nativeBinding,authority,original,files,local,tasks,context.getString("checkoutId"),context.getJSONObject("selection").getString("runtimeId"));}}
        if(project==null)files.prepareTurnSurface(control);else project.prepareTurnSurface(control);
    }
    @Override public JSONArray tools()throws Exception {JSONArray input=project==null?files.tools():project.tools(),out=new JSONArray();for(int i=0;i<input.length();i++){JSONObject tool=input.getJSONObject(i);String name=tool.getJSONObject("function").getString("name");if(fileTool(name)||project!=null&&projectTool(name)&&(!name.equals("project_package")||isIntegrator()))out.put(tool);}return out;}
    private boolean isIntegrator()throws Exception{return binding.optString("memberId").equals("integrator");}
    @Override public boolean supports(String name){return fileTool(name)&&files.supports(name)||project!=null&&projectTool(name)&&project.supports(name)&&(!name.equals("project_package")||binding.optString("memberId").equals("integrator"));}
    private CapabilityPort selected(String name)throws Exception {if(!supports(name))throw new SecurityException("没有此成员已挂载项目能力；先准备真实副本，不降级成另写文字文件");return projectTool(name)?project:files;}
    @Override public boolean sideEffecting(String name){return projectTool(name)?project==null||project.sideEffecting(name):files.sideEffecting(name);}
    @Override public void validate(String name,JSONObject args)throws Exception{selected(name).validate(name,args);}
    @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception {check(control);JSONObject result=selected(name).execute(name,args,control);
        if(name.equals("project_plan")&&result.has("planRef")&&result.has("planDigest")){result=new JSONObject(result.toString());result.remove("plan");result.put("inlineCompatibilityCopyOmitted",true).put("fullPlanStoredInOriginalTaskStore",true).put("readFullManifestWith","project_plan_manifest");}
        return result;}
    @Override public JSONObject contextSnapshot(String query)throws Exception {JSONObject result=files.contextSnapshot(query);JSONObject context=tasks.teamProjectContextIfReady(binding,original);return result.put("teamProject",context==null?new JSONObject().put("ready",false).put("message","先准备原工作项的完整副本"):TeamProjectWorkspaces.summary(context.getJSONObject("selection")).put("ready",true).put("checkoutId",context.getString("checkoutId")).put("projectId",context.getString("projectId"))).put("scopeNotice","命令在同应用UID执行，独立目录不是OS权限沙箱；只接受原native模板与当前源码的明确逐方案批准");}
    @Override public void beginRun(String goal,RunControl control)throws Exception{files.beginRun(goal,control);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control)throws Exception{if(project==null)files.admitCapabilities(snapshot,control);else project.admitCapabilities(snapshot,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{prepareTurnSurface(control);JSONObject turn=project==null?files.prepareTurn(query,round,steps,control):project.prepareTurn(query,round,steps,control);JSONObject reference=turn.optJSONObject("reference");if(reference==null)reference=new JSONObject();reference.put("teamProject",contextSnapshot(query).getJSONObject("teamProject"));return turn.put("tools",tools()).put("reference",reference);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{return project==null?files.responseCurrent(control):project.responseCurrent(control);}
    @Override public void beforeModelRequest(String request,JSONArray messages,RunControl control)throws Exception{checkRenewedSource(control);if(project==null)files.beforeModelRequest(request,messages,control);else project.beforeModelRequest(request,messages,control);}
    @Override public JSONObject afterModelResponse(String request,String answer,RunControl control)throws Exception{checkRenewedSource(control);return project==null?files.afterModelResponse(request,answer,control):project.afterModelResponse(request,answer,control);}
    @Override public void modelRequestAbandoned(String request)throws Exception{if(project==null)files.modelRequestAbandoned(request);else project.modelRequestAbandoned(request);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{return project==null?files.beforeTool(name,args,control):project.beforeTool(name,args,control);}
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception{if(project==null)files.afterTool(name,args,receipt,control);else project.afterTool(name,args,receipt,control);}
    @Override public void endRun(RunControl control)throws Exception{if(project==null)files.endRun(control);else project.endRun(control);}
    // Preserve typed native consumers on the SAME original port/control; this adds no tool or authority.
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String tool,JSONObject args,RunControl live)throws Exception{return (project==null?files:project).observationRequirement(tool,args,live);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String tool,String operation,RunControl live)throws Exception{return (project==null?files:project).takeToolObservation(tool,operation,live);}
    @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String tool,String operation,RunControl live)throws Exception{return (project==null?files:project).takeToolPlan(tool,operation,live);}
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl live)throws Exception{return (project==null?files:project).nativeReadContextRepair(messages,live);}
    @Override public void close(){try{if(project!=null)project.close();else files.close();}finally{closed=true;}}
}
