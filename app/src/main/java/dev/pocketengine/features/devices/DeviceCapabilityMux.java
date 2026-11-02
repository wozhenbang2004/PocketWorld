package dev.pocketengine.features.devices;

import dev.pocketengine.core.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Joins only already-scoped project and native device ports. Never constructs an owner/private host. */
public final class DeviceCapabilityMux implements CapabilityPort {
    private final CapabilityPort base,devices;
    public DeviceCapabilityMux(CapabilityPort scopedProject,CapabilityPort scopedDevices){base=java.util.Objects.requireNonNull(scopedProject);devices=java.util.Objects.requireNonNull(scopedDevices);}
    private CapabilityPort port(String name){return devices.supports(name)?devices:base;}
    public JSONArray tools()throws Exception{JSONArray result=new JSONArray(),left=base.tools(),right=devices.tools();java.util.Set<String> names=new java.util.HashSet<>();for(int i=0;i<left.length();i++){JSONObject tool=left.getJSONObject(i);names.add(tool.getJSONObject("function").getString("name"));result.put(tool);}for(int i=0;i<right.length();i++){JSONObject tool=right.getJSONObject(i);if(!names.add(tool.getJSONObject("function").getString("name")))throw new SecurityException("闭集project/device工具冲突");result.put(tool);}return result;}
    public boolean supports(String name){return devices.supports(name)||base.supports(name);}public boolean sideEffecting(String name){return port(name).sideEffecting(name);}
    public void validate(String name,JSONObject args)throws Exception{port(name).validate(name,args);}public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{return port(name).execute(name,args,original);}
    public JSONObject contextSnapshot(String query)throws Exception{return base.contextSnapshot(query);}
    public void beginRun(String goal,RunControl original)throws Exception{base.beginRun(goal,original);devices.beginRun(goal,original);}public void prepareTurnSurface(RunControl original)throws Exception{base.prepareTurnSurface(original);devices.prepareTurnSurface(original);}
    public void admitCapabilities(CapabilitySnapshot snapshot,RunControl original)throws Exception{base.admitCapabilities(snapshot,original);devices.admitCapabilities(snapshot,original);}
    public boolean responseCurrent(RunControl original)throws Exception{return base.responseCurrent(original)&&devices.responseCurrent(original);}
    public void beforeModelRequest(String requestId,JSONArray messages,RunControl original)throws Exception{base.beforeModelRequest(requestId,messages,original);devices.beforeModelRequest(requestId,messages,original);}
    public JSONObject afterModelResponse(String requestId,String answerId,RunControl original)throws Exception{JSONObject left=base.afterModelResponse(requestId,answerId,original),right=devices.afterModelResponse(requestId,answerId,original);return left==null?right:right==null?left:new JSONObject().put("project",left).put("device",right);}
    public void modelRequestAbandoned(String requestId)throws Exception{base.modelRequestAbandoned(requestId);devices.modelRequestAbandoned(requestId);}
    public NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl original)throws Exception{return base.nativeReadContextRepair(messages,original);}
    public JSONObject beforeTool(String name,JSONObject args,RunControl original)throws Exception{return port(name).beforeTool(name,args,original);}public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl original)throws Exception{port(name).afterTool(name,args,receipt,original);}
    public NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl original)throws Exception{return port(name).observationRequirement(name,args,original);}public NativeToolObservation takeToolObservation(String name,String operation,RunControl original)throws Exception{return port(name).takeToolObservation(name,operation,original);}public NativeToolPlan takeToolPlan(String name,String operation,RunControl original)throws Exception{return port(name).takeToolPlan(name,operation,original);}
    public void endRun(RunControl original)throws Exception{try{base.endRun(original);}finally{devices.endRun(original);}}public void close(){try{devices.close();}finally{base.close();}}
}
