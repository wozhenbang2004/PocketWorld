package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Domain metering around the ORIGINAL capability port. No tool loop, store or permission is added. */
public final class TeamBudgetPort implements CapabilityPort {
    private final CapabilityPort original;private final TaskStore tasks;private final JSONObject binding;private boolean roundParent;private RunControl roundControl;private java.util.Set<String> allowedRoundTools;
    public TeamBudgetPort(CapabilityPort original,TaskStore tasks,JSONObject binding)throws Exception{this.original=original;this.tasks=tasks;this.binding=new JSONObject(binding.toString());}
    public static CapabilityPort forRoundParent(CapabilityPort original,TaskStore tasks,String task,String run,int epoch,RunControl live)throws Exception {
        if(!tasks.nativeTeamRoundBudget(task,run,epoch))return original;TeamBudgetPort port=new TeamBudgetPort(original,tasks,new JSONObject().put("taskId",task).put("runId",run).put("coordinatorEpoch",epoch));port.roundParent=true;port.roundControl=live;JSONArray allowed=tasks.teamRoundAllowedTools(task,run,epoch);if(allowed!=null){port.allowedRoundTools=new java.util.HashSet<>();for(int i=0;i<allowed.length();i++)port.allowedRoundTools.add(allowed.getString(i));}return port;
    }
    private boolean allowed(String tool){return allowedRoundTools==null||allowedRoundTools.contains(tool);}
    @Override public JSONObject contextSnapshot(String query)throws Exception{return original.contextSnapshot(query);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{original.beginRun(goal,control);}
    @Override public void prepareTurnSurface(RunControl control)throws Exception{original.prepareTurnSurface(control);}
    @Override public void admitCapabilities(CapabilitySnapshot snapshot,RunControl control)throws Exception{original.admitCapabilities(snapshot,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{return original.prepareTurn(query,round,steps,control);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{return original.responseCurrent(control);}
    @Override public boolean stepReadSourceCurrent(String tool,JSONObject receipt,RunControl control)throws Exception{return allowed(tool)&&(!roundParent||control==roundControl)&&original.stepReadSourceCurrent(tool,receipt,control);}
    @Override public void beforeModelRequest(String request,JSONArray messages,RunControl control)throws Exception{original.beforeModelRequest(request,messages,control);}
    @Override public JSONObject afterModelResponse(String request,String answer,RunControl control)throws Exception{return original.afterModelResponse(request,answer,control);}
    @Override public void modelRequestAbandoned(String request)throws Exception{original.modelRequestAbandoned(request);}
    @Override public JSONObject beforeTool(String name,JSONObject arguments,RunControl control)throws Exception{return original.beforeTool(name,arguments,control);}
    @Override public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control)throws Exception{original.afterTool(name,arguments,receipt,control);}
    @Override public void endRun(RunControl control)throws Exception{original.endRun(control);}
    // Preserve typed native consumers on the SAME original port/control; this adds no tool or authority.
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String tool,JSONObject args,RunControl live)throws Exception{return original.observationRequirement(tool,args,live);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String tool,String operation,RunControl live)throws Exception{return original.takeToolObservation(tool,operation,live);}
    @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String tool,String operation,RunControl live)throws Exception{return original.takeToolPlan(tool,operation,live);}
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl live)throws Exception{return original.nativeReadContextRepair(messages,live);}
    @Override public void close(){original.close();}
    @Override public JSONArray tools()throws Exception{JSONArray tools=original.tools();if(allowedRoundTools==null)return tools;JSONArray selected=new JSONArray();for(int i=0;i<tools.length();i++)if(allowed(tools.getJSONObject(i).getJSONObject("function").getString("name")))selected.put(tools.getJSONObject(i));return selected;}
    @Override public boolean supports(String name){return allowed(name)&&original.supports(name);}
    @Override public boolean sideEffecting(String name){return original.sideEffecting(name);}
    @Override public void validate(String name,JSONObject arguments)throws Exception{if(!allowed(name))throw new SecurityException("预算续审不新增工具范围，请在原工具授权另审");original.validate(name,arguments);}
    @Override public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception{
        if(!allowed(name))throw new SecurityException("预算续审不新增工具范围，请在原工具授权另审");if(roundParent&&control!=roundControl)throw new SecurityException("不是本次原round父控制器");
        if(original.sideEffecting(name))return original.execute(name,arguments,control); // Original prepared journal already reserves effects.
        // Runner validates the model arguments before injecting its native visual call ID.
        // Keep that ID only on the same dispatch input; it is not a model/source parameter
        // and must not change the original read's argument hash or budget identity.
        JSONObject readArguments=arguments;
        if("project_view_image".equals(name)&&arguments!=null&&arguments.has("_hostOperationId")){readArguments=new JSONObject(arguments.toString());readArguments.remove("_hostOperationId");}
        original.validate(name,readArguments);if(roundParent){if(name.equals("teams_get")||name.equals("teams_wait"))return original.execute(name,arguments,control);TaskStore.TeamRoundRead read=tasks.beginTeamRoundRead(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),name,readArguments,control);try{JSONObject value=tasks.admitTeamRoundRead(read,()->original.execute(name,arguments,control));tasks.finishTeamRoundRead(read,value,false);return value;}catch(Exception uncertain){tasks.finishTeamRoundRead(read,new JSONObject().put("status","unknown").put("message","本次原native读取没有完整回执；保留原read/预算，不自动重放"),true);throw uncertain;}}
        JSONObject nativeRead=tasks.beginTeamAssignmentRead(binding,name,readArguments,control);
        try{JSONObject result=original.execute(name,arguments,control);tasks.finishTeamNativeRead(nativeRead,result,false);return result;}
        catch(Exception failure){tasks.finishTeamNativeRead(nativeRead,new JSONObject().put("status","unknown").put("message","原工具没有确定完成回执；预算与操作保留，不重放"),true);throw failure;}
    }
}
