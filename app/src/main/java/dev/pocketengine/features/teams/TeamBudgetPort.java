package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Domain metering around the ORIGINAL capability port. No tool loop, store or permission is added. */
public final class TeamBudgetPort implements CapabilityPort {
    private final CapabilityPort original;private final TaskStore tasks;private final JSONObject binding;
    public TeamBudgetPort(CapabilityPort original,TaskStore tasks,JSONObject binding)throws Exception{this.original=original;this.tasks=tasks;this.binding=new JSONObject(binding.toString());}
    @Override public JSONObject contextSnapshot(String query)throws Exception{return original.contextSnapshot(query);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{original.beginRun(goal,control);}
    @Override public void prepareTurnSurface(RunControl control)throws Exception{original.prepareTurnSurface(control);}
    @Override public void admitCapabilities(CapabilitySnapshot snapshot,RunControl control)throws Exception{original.admitCapabilities(snapshot,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{return original.prepareTurn(query,round,steps,control);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{return original.responseCurrent(control);}
    @Override public void beforeModelRequest(String request,JSONArray messages,RunControl control)throws Exception{original.beforeModelRequest(request,messages,control);}
    @Override public JSONObject afterModelResponse(String request,String answer,RunControl control)throws Exception{return original.afterModelResponse(request,answer,control);}
    @Override public void modelRequestAbandoned(String request)throws Exception{original.modelRequestAbandoned(request);}
    @Override public JSONObject beforeTool(String name,JSONObject arguments,RunControl control)throws Exception{return original.beforeTool(name,arguments,control);}
    @Override public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control)throws Exception{original.afterTool(name,arguments,receipt,control);}
    @Override public void endRun(RunControl control)throws Exception{original.endRun(control);}
    @Override public void close(){original.close();}
    @Override public JSONArray tools()throws Exception{return original.tools();}
    @Override public boolean supports(String name){return original.supports(name);}
    @Override public boolean sideEffecting(String name){return original.sideEffecting(name);}
    @Override public void validate(String name,JSONObject arguments)throws Exception{original.validate(name,arguments);}
    @Override public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception{
        if(original.sideEffecting(name))return original.execute(name,arguments,control); // Original prepared journal already reserves effects.
        original.validate(name,arguments);JSONObject nativeRead=tasks.beginTeamAssignmentRead(binding,name,arguments,control);
        try{JSONObject result=original.execute(name,arguments,control);tasks.finishTeamNativeRead(nativeRead,result,false);return result;}
        catch(Exception failure){tasks.finishTeamNativeRead(nativeRead,new JSONObject().put("status","unknown").put("message","原工具没有确定完成回执；预算与操作保留，不重放"),true);throw failure;}
    }
}
