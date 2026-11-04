package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.NativeReadContextRepair;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.RunControl;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** A removal-only view of the already-authorized native surface. The original journal owns reads. */
public final class PlanningCapabilityPort implements CapabilityPort {
    /** This legacy context query can physically observe a page but cannot carry an original read claim. */
    public static final class OwnerReturnReviewRequired extends SecurityException {
        public OwnerReturnReviewRequired(){super("规划期间的网页交还观察需要重新核对原只读来源；保留当前规划，不自动重新观察或切换手机。");}
    }
    private final CapabilityPort delegate;
    private final ExecutionPlanning planning;
    private volatile ExecutionPlanning.Snapshot turn;
    private volatile boolean closed;

    private PlanningCapabilityPort(CapabilityPort delegate,ExecutionPlanning planning){
        this.delegate=Objects.requireNonNull(delegate);this.planning=Objects.requireNonNull(planning);
    }
    public static CapabilityPort wrap(CapabilityPort delegate,ExecutionPlanning planning){
        return planning==null?delegate:new PlanningCapabilityPort(delegate,planning);
    }
    private ExecutionPlanning.Snapshot current()throws Exception {
        if(closed)throw new CapabilityPort.Unavailable();
        ExecutionPlanning.Snapshot snapshot=planning.capture();planning.requireCurrent(snapshot);return snapshot;
    }
    private void requireTurn()throws Exception {
        if(closed)throw new CapabilityPort.Unavailable();
        ExecutionPlanning.Snapshot expected=turn;if(expected!=null)planning.requireCurrent(expected);
    }
    private void admit(ExecutionPlanning.Snapshot snapshot,String name,JSONObject arguments)throws Exception {
        requireTurn();planning.requireCurrent(snapshot);
        if(snapshot.planning()&&(!delegate.supports(name)||delegate.planningAccess(name,arguments)!=ExecutionPlanning.Access.READ))
            throw new SecurityException("规划阶段只允许原生核定的读取；执行或计划转换须另行审阅");
        planning.requireCurrent(snapshot);
    }
    private JSONArray project(JSONArray offered,ExecutionPlanning.Snapshot snapshot)throws Exception {
        if(!snapshot.planning())return new JSONArray(offered.toString());
        JSONArray result=new JSONArray();
        for(int i=0;i<offered.length();i++){
            JSONObject tool=offered.getJSONObject(i);String name=tool.getJSONObject("function").getString("name");
            if(delegate.supports(name)&&delegate.planningAccess(name,null)==ExecutionPlanning.Access.READ)result.put(new JSONObject(tool.toString()));
        }
        planning.requireCurrent(snapshot);return result;
    }
    @Override public JSONArray tools()throws Exception {ExecutionPlanning.Snapshot snapshot=current();JSONArray result=project(delegate.tools(),snapshot);planning.requireCurrent(snapshot);return result;}
    @Override public boolean supports(String name){
        try{ExecutionPlanning.Snapshot snapshot=current();boolean supported=delegate.supports(name)&&(!snapshot.planning()||delegate.planningAccess(name,null)==ExecutionPlanning.Access.READ);planning.requireCurrent(snapshot);return supported;}
        catch(Exception unavailable){return false;}
    }
    @Override public boolean sideEffecting(String name){return delegate.sideEffecting(name);}
    @Override public ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception {
        ExecutionPlanning.Snapshot snapshot=current();ExecutionPlanning.Access access=delegate.planningAccess(name,arguments);planning.requireCurrent(snapshot);return access;
    }
    @Override public void validate(String name,JSONObject arguments)throws Exception {
        ExecutionPlanning.Snapshot snapshot=current();admit(snapshot,name,arguments);delegate.validate(name,arguments);planning.requireCurrent(snapshot);
    }
    @Override public JSONObject beforeTool(String name,JSONObject arguments,RunControl original)throws Exception {
        ExecutionPlanning.Snapshot snapshot=current();
        // Runner-owned reads/outcomes still pass through the original interceptors before beginRead.
        // A real delegate route keeps its own classification even if it uses a Runner-looking name.
        if(snapshot.planning()&&!delegate.supports(name)&&ExecutionPlanning.runnerAccess(name)!=ExecutionPlanning.Access.DENY){requireTurn();planning.requireCurrent(snapshot);}
        else admit(snapshot,name,arguments);
        JSONObject refusal=delegate.beforeTool(name,arguments,original);planning.requireCurrent(snapshot);return refusal;
    }
    @Override public JSONObject execute(String name,JSONObject arguments,RunControl original)throws Exception {
        ExecutionPlanning.Snapshot snapshot=current();admit(snapshot,name,arguments);
        JSONObject dispatched=arguments;
        if(snapshot.planning()){
            planning.requireReadDispatch(name,arguments,original);
            JSONObject clean=new JSONObject(arguments.toString());clean.remove("_hostOperationId");
            if(!delegate.sideEffecting(name)&&delegate.observationRequirement(name,clean,original)!=NativeToolObservation.Requirement.IMAGE)dispatched=clean;
        }
        planning.requireCurrent(snapshot);return delegate.execute(name,dispatched,original);
    }
    @Override public JSONObject contextSnapshot(String query)throws Exception{
        // Exact existing BrowserTaskCapabilityPort.OWNER_RETURN_CONTEXT wire value; no new context API.
        if("native.browser.owner-return-observation.v1".equals(query)&&current().planning())throw new OwnerReturnReviewRequired();
        return delegate.contextSnapshot(query);
    }
    @Override public void beginRun(String goal,RunControl original)throws Exception{current();delegate.beginRun(goal,original);}
    @Override public void prepareTurnSurface(RunControl original)throws Exception{ExecutionPlanning.Snapshot snapshot=current();delegate.prepareTurnSurface(original);planning.requireCurrent(snapshot);}
    @Override public void admitCapabilities(CapabilitySnapshot snapshot,RunControl original)throws Exception{delegate.admitCapabilities(snapshot,original);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl original)throws Exception {
        ExecutionPlanning.Snapshot snapshot=current();JSONObject result=new JSONObject(delegate.prepareTurn(query,round,steps,original).toString());
        result.put("tools",project(result.getJSONArray("tools"),snapshot));planning.requireCurrent(snapshot);turn=snapshot;return result;
    }
    @Override public boolean responseCurrent(RunControl original)throws Exception {
        try{requireTurn();ExecutionPlanning.Snapshot expected=current();if(!delegate.responseCurrent(original))return false;planning.requireCurrent(expected);requireTurn();return true;}
        catch(SecurityException unavailable){return false;}
    }
    @Override public boolean stepReadSourceCurrent(String name,JSONObject receipt,RunControl original)throws Exception{return delegate.stepReadSourceCurrent(name,receipt,original);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl original)throws Exception{requireTurn();delegate.beforeModelRequest(requestId,messages,original);requireTurn();}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl original)throws Exception{requireTurn();JSONObject result=delegate.afterModelResponse(requestId,answerId,original);requireTurn();return result;}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{delegate.modelRequestAbandoned(requestId);}
    @Override public NativeToolObservation.Requirement observationRequirement(String name,JSONObject arguments,RunControl original)throws Exception{return delegate.observationRequirement(name,arguments,original);}
    @Override public NativeToolObservation takeToolObservation(String name,String operation,RunControl original)throws Exception{return delegate.takeToolObservation(name,operation,original);}
    @Override public NativeToolPlan takeToolPlan(String name,String operation,RunControl original)throws Exception{return delegate.takeToolPlan(name,operation,original);}
    @Override public NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl original)throws Exception{return delegate.nativeReadContextRepair(messages,original);}
    @Override public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl original)throws Exception{delegate.afterTool(name,arguments,receipt,original);}
    @Override public void endRun(RunControl original)throws Exception{delegate.endRun(original);}
    @Override public void close(){closed=true;try{delegate.close();}finally{planning.close();}}
}
