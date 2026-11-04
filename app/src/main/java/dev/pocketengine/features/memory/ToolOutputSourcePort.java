package dev.pocketengine.features.memory;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Outermost native source admission only. Delegates retain their original order, grants and effects.
 * This never registers a RunControl authority callback: source expiry is not owner cancellation. */
public final class ToolOutputSourcePort implements CapabilityPort {
    private final CapabilityPort original;
    private final ToolOutputCapture.Factory sources;
    private final RunControl control;
    private volatile boolean closed;private boolean cleanupComplete;
    public ToolOutputSourcePort(CapabilityPort original,ToolOutputCapture.Factory sources,RunControl control){this.original=java.util.Objects.requireNonNull(original);this.sources=java.util.Objects.requireNonNull(sources);this.control=java.util.Objects.requireNonNull(control);}
    private void current(RunControl live)throws Exception{if(live!=control||closed)throw new SecurityException("不能替换原输出来源控制器");control.check();}
    public JSONArray tools()throws Exception{return original.tools();}
    public boolean supports(String name){return original.supports(name);}
    public boolean sideEffecting(String name){return original.sideEffecting(name);}
    @Override public dev.pocketengine.core.ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception{return original.planningAccess(name,arguments);}
    public JSONObject contextSnapshot(String query)throws Exception{return original.contextSnapshot(query);}
    public void validate(String name,JSONObject arguments)throws Exception{original.validate(name,arguments);}
    public void beginRun(String goal,RunControl live)throws Exception{current(live);original.beginRun(goal,live);}
    public void prepareTurnSurface(RunControl live)throws Exception{current(live);original.prepareTurnSurface(live);}
    public void admitCapabilities(CapabilitySnapshot snapshot,RunControl live)throws Exception{current(live);original.admitCapabilities(snapshot,live);}
    public JSONObject prepareTurn(String query,int round,int steps,RunControl live)throws Exception{current(live);JSONObject result=original.prepareTurn(query,round,steps,live);current(live);sources.checkSources(live);return result;}
    // Keep the original capability/route/source authority's actual false/cancel semantics. A typed
    // soft source refusal must not disappear inside a responseCurrent catch-to-false loop.
    public boolean responseCurrent(RunControl live)throws Exception{current(live);return original.responseCurrent(live);}
    public boolean stepReadSourceCurrent(String tool,JSONObject receipt,RunControl live)throws Exception{current(live);sources.checkSources(live);boolean current=original.stepReadSourceCurrent(tool,receipt,live);current(live);return current;}
    public void beforeModelRequest(String request,JSONArray messages,RunControl live)throws Exception{current(live);original.beforeModelRequest(request,messages,live);current(live);sources.checkSources(live);}
    public JSONObject afterModelResponse(String request,String answer,RunControl live)throws Exception{current(live);sources.checkSources(live);JSONObject result=original.afterModelResponse(request,answer,live);current(live);sources.checkSources(live);return result;}
    public void modelRequestAbandoned(String request)throws Exception{original.modelRequestAbandoned(request);}
    public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl live)throws Exception{current(live);sources.checkSources(live);return original.observationRequirement(name,args,live);}
    public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl live)throws Exception{current(live);sources.checkSources(live);return original.takeToolObservation(name,operation,live);}
    public dev.pocketengine.core.NativeToolPlan takeToolPlan(String name,String operation,RunControl live)throws Exception{current(live);sources.checkSources(live);return original.takeToolPlan(name,operation,live);}
    public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl live)throws Exception{current(live);sources.checkSources(live);return original.nativeReadContextRepair(messages,live);}
    public JSONObject beforeTool(String name,JSONObject arguments,RunControl live)throws Exception{current(live);JSONObject refusal=original.beforeTool(name,arguments,live);if(refusal!=null)return refusal;current(live);sources.checkSources(live);return null;}
    public JSONObject execute(String name,JSONObject arguments,RunControl live)throws Exception{
        current(live);
        try{sources.checkSources(live);}catch(ToolOutputCapture.OutputSourceUnavailable unavailable){
            // Only THIS final pre-delegate check is caught. Never relabel a delegate's real effect,
            // receipt loss or unknown exception as not-dispatched after it crossed the native edge.
            control.check();return new JSONObject().put("ok",false).put("status","not_dispatched").put("accepted",false).put("nativeDispatched",false).put("requiresPause",true).put("code","output_source_unavailable").put("message",unavailable.getMessage()).put("executionAuthorized",false);
        }
        return original.execute(name,arguments,live);
    }
    public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl live)throws Exception{original.afterTool(name,arguments,receipt,live);}
    public void endRun(RunControl live)throws Exception{original.endRun(live);}
    public synchronized void close(){if(cleanupComplete)return;closed=true;original.close();cleanupComplete=true;}
}
