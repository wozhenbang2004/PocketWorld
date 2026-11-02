package dev.pocketengine.features.devices;

import org.json.JSONObject;

/** Validated immutable wire data. This is deliberately not a RunControl or an admission. */
public final class DeviceTaskEnvelope {
    private final String encoded;
    DeviceTaskEnvelope(JSONObject data){encoded=DeviceProtocol.canonical(data);}
    public JSONObject json(){try{return new JSONObject(encoded);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    public JSONObject arguments(){return json().optJSONObject("arguments");}
    public String method(){return json().optString("method");}
    public String messageType(){return json().optString("messageType");}
    public String requestId(){return json().optString("requestId");}
    public String operationId(){return json().optString("operationId");}
    public String argumentsDigest(){return json().optString("argumentsDigest");}
    public String originDeviceId(){return json().optString("originDeviceId");}
    public String callerDeviceId(){return json().optString("callerDeviceId");}
    public String targetDeviceId(){return json().optString("targetDeviceId");}
    public String grantId(){return json().optString("grantId");}
    public String rootTaskId(){return json().optString("rootTaskId");}
    public String parentTaskId(){return json().optString("parentTaskId");}
    public String localTaskId(){return json().optString("localTaskId");}
    public String runId(){return json().optString("runId");}
    public int attempt(){return json().optInt("attempt");}
    public long executorEpoch(){return json().optLong("executorEpoch");}
    public String instanceId(){return json().optString("instanceId");}
    public JSONObject budget(){JSONObject value=json().optJSONObject("budget");return value==null?new JSONObject():value;}
    public JSONObject resourceBinding(){JSONObject value=json().optJSONObject("resourceBinding");return value==null?new JSONObject():value;}
    public JSONObject observationRef(){JSONObject value=json().optJSONObject("observationRef");return value==null?new JSONObject():value;}
    public long authorizationEpoch(){return json().optLong("authorizationEpoch");}
    public long deadline(){return json().optLong("deadline");}
    public boolean sideEffecting(){return !"read".equals(DeviceProtocol.effectOf(json()));}
    public ToolInvocation toolInvocation(){
        if(!"tool.invoke".equals(method()))throw new IllegalStateException("Not a tool invocation");
        JSONObject value=arguments();return new ToolInvocation(value.optString("toolName"),value.optString("effect"),value.optJSONObject("arguments"),json().optString("capabilityVersion"),json().optJSONObject("resourceBinding"),json().optJSONObject("observationRef"));
    }
    public GoalSubmission goalSubmission(){
        if(!"task.submitGoal".equals(method()))throw new IllegalStateException("Not a goal submission");
        JSONObject value=arguments();return new GoalSubmission(value.optString("goal"),value.optString("executionMode"),value.optJSONObject("contextSnapshot"),value.optJSONObject("modelSource"));
    }
    public record ToolInvocation(String toolName,String effect,JSONObject arguments,String capabilityVersion,JSONObject resourceBinding,JSONObject observationRef){}
    public record GoalSubmission(String goal,String executionMode,JSONObject contextSnapshot,JSONObject modelSource){}
}
