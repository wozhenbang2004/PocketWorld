package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** Immutable original journal reference; data alone grants no dispatch or source access. */
public final class RuntimeExecutionRef {
    public enum Phase { PROBE,STAGE,COMMAND,COLLECT }
    private final JSONObject exact,binding,link;
    private final Phase phase;

    private RuntimeExecutionRef(JSONObject exact,JSONObject nativeBinding) throws Exception {
        this.exact=ProjectSources.copy(exact);this.binding=new JSONObject();
        if(!"local".equals(this.exact.getString("feature")))throw new SecurityException("runtime_project_feature_required");
        String command=this.exact.getString("command");
        switch(command) {
            case "termux.probe":case "project.runtime.probe":phase=Phase.PROBE;break;
            case "termux.prepare":case "project.runtime.prepare":phase=Phase.STAGE;break;
            case "termux.run":case "project.runtime.run":phase=Phase.COMMAND;break;
            case "termux.collect":case "project.runtime.collect":phase=Phase.COLLECT;break;
            default:throw new SecurityException("runtime_project_command_unsupported");
        }
        for(String name:new String[]{"projectId","checkoutId","planDigest","stepId","intent","sourceDigest","requestDigest"})
            required(this.exact.getString(name),name,160);
        for(String name:new String[]{"sourceDigest","requestDigest","planDigest"})
            if(!this.exact.getString(name).matches("[a-f0-9]{64}"))throw new SecurityException("runtime_digest_required");
        if(!this.exact.getString("checkoutId").matches("[a-f0-9-]{36}"))throw new SecurityException("runtime_checkout_invalid");
        JSONObject payload=this.exact.getJSONObject("payload");
        if(!this.exact.getString("checkoutId").equals(payload.getString("checkoutId"))
                || !ProjectSources.digest(payload).equals(this.exact.getString("requestDigest")))
            throw new SecurityException("runtime_request_digest_mismatch");
        if(phase!=Phase.PROBE&&!this.exact.getString("sourceDigest").equals(payload.getString("expectedSourceDigest")))
            throw new SecurityException("runtime_source_binding_required");
        for(String name:new String[]{"taskId","runId","operationId"}) {
            String value=nativeBinding.getString(name);required(value,name,160);this.binding.put(name,value);
        }
        if(!this.binding.getString("operationId").matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("runtime_operation_invalid");
        Object epoch=nativeBinding.get("coordinatorEpoch");
        if(!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<1
                ||((Number)epoch).longValue()>Integer.MAX_VALUE)throw new SecurityException("runtime_epoch_invalid");
        this.binding.put("coordinatorEpoch",((Number)epoch).intValue());
        link=ProjectSources.copy(this.binding).put("authority","native_host_operation").put("command",command)
                .put("projectId",this.exact.getString("projectId")).put("checkoutId",this.exact.getString("checkoutId"));
        if(command.startsWith("project.runtime.")) {
            String runtime=this.exact.getString("runtimeId");required(runtime,"runtimeId",100);link.put("runtimeId",runtime);
            String identity=this.exact.getString("runtimeIdentity");
            if(!identity.matches("[a-f0-9]{64}"))throw new SecurityException("runtime_environment_identity_required");
            link.put("runtimeIdentity",identity);
        }
        for(String name:new String[]{"planDigest","stepId","intent","sourceDigest","requestDigest"})link.put(name,this.exact.get(name));
    }

    /** Host rebuilds this only from its original journal row, never from a model-supplied receipt. */
    public static RuntimeExecutionRef fromHost(JSONObject exact,JSONObject nativeBinding) throws Exception {
        return new RuntimeExecutionRef(exact,nativeBinding);
    }
    public Phase phase() { return phase; }
    public String operationId() throws Exception { return binding.getString("operationId"); }
    public String checkoutId() throws Exception { return exact.getString("checkoutId"); }
    public String projectId() throws Exception { return exact.getString("projectId"); }
    public String sourceDigest() throws Exception { return exact.getString("sourceDigest"); }
    public JSONObject exact() throws Exception { return ProjectSources.copy(exact); }
    public JSONObject binding() throws Exception { return ProjectSources.copy(binding); }
    public JSONObject executionLink() throws Exception { return ProjectSources.copy(link); }
    public void checkControl(RunControl original) throws Exception {
        if(original==null||!original.hasHostRun()||!original.matchesHostRun(binding.getString("taskId"),
                binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("runtime_original_control_required");
        original.check();
    }
    public void requireReceipt(JSONObject receipt) throws Exception {
        if(!operationId().equals(receipt.getString("operationId"))||!operationId().equals(receipt.getString("id"))
                ||!checkoutId().equals(receipt.getString("checkoutId")))throw new SecurityException("runtime_receipt_identity_mismatch");
        JSONObject actual=receipt.getJSONObject("projectExecution");
        for(String key:new String[]{"authority","command","projectId","checkoutId","taskId","runId","operationId","coordinatorEpoch",
                "planDigest","stepId","intent","sourceDigest","requestDigest"})
            if(!ProjectSources.canonical(link.get(key)).equals(ProjectSources.canonical(actual.get(key))))
                throw new SecurityException("runtime_receipt_original_ref_mismatch");
        if(link.has("runtimeId")&&!link.getString("runtimeId").equals(actual.optString("runtimeId")))
            throw new SecurityException("runtime_receipt_provider_mismatch");
        if(link.has("runtimeIdentity")&&!link.getString("runtimeIdentity").equals(actual.optString("runtimeIdentity")))
            throw new SecurityException("runtime_receipt_environment_mismatch");
        if(phase!=Phase.PROBE&&!sourceDigest().equals(receipt.getString("sourceDigest")))
            throw new SecurityException("runtime_receipt_source_mismatch");
    }
    private static void required(String value,String name,int max) {
        if(value==null||value.isEmpty()||value.length()>max||value.chars().anyMatch(c->c<32))
            throw new IllegalArgumentException("runtime_invalid_"+name);
    }
}
