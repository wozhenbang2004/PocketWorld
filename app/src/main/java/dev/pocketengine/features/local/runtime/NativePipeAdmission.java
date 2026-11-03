package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** IO adapter of the original TaskStore's typed native approval, not a second
 * permission, launcher or journal. No JSON can restore this object or its handle. */
public final class NativePipeAdmission {
    public static final String DISCOVERY="native_mcp_discovery",INVOKE="native_mcp_invoke";
    public interface Authorization {
        void requireCurrent(RuntimeInvocation original,JSONObject exactBinding)throws Exception;
        /** Persist the original exact/ref and unknown dispatch fence before a worker can start. */
        void claimBeforeDispatch(RuntimeInvocation original,JSONObject exactBinding)throws Exception;
        /** Record only this original native receipt, including late exit after revocation. */
        void recordOriginalReceipt(RuntimeReceipt actual)throws Exception;
    }
    @FunctionalInterface public interface Output {
        /** Must be nonblocking and bounded. False stops/drains this original process. */
        boolean bytes(int stream,byte[] bytes)throws Exception;
    }
    final RuntimeInvocation invocation;final long byteBudget;
    private final ManagedProjectRuntime issuer;private final Authorization authorization;private final Output output;
    private final String binding;private final AtomicBoolean claimed=new AtomicBoolean();
    private ManagedProcessJobs.PipeHandle handle;
    private NativePipeAdmission(ManagedProjectRuntime issuer,RuntimeInvocation original,JSONObject sourceIdentity,Authorization authorization,Output output)throws Exception {
        this.issuer=issuer;invocation=Objects.requireNonNull(original);this.authorization=Objects.requireNonNull(authorization);this.output=Objects.requireNonNull(output);
        JSONObject payload=original.exact().getJSONObject("payload");Object budget=payload.opt("nativePipeWriteBudget");
        if(!(budget instanceof Integer||budget instanceof Long))throw new SecurityException("runtime_pipe_reviewed_byte_budget_required");byteBudget=((Number)budget).longValue();
        JSONObject fixed=bindingForHost(sourceIdentity,original.binding(),original.executionRef().sourceDigest(),byteBudget);
        if(!exact(original.exact())||!original.exact().getString("intent").equals(fixed.getString("purpose"))||!NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT.equals(payload.optString("nativePipeContract"))||!ProjectSources.digest(fixed).equals(payload.optString("nativePipeBindingDigest"))||!issuer.identity().equals(fixed.getString("runtimeGeneration")))throw new SecurityException("runtime_pipe_frozen_native_binding_required");
        binding=fixed.toString();checkCurrent();
    }
    static NativePipeAdmission create(ManagedProjectRuntime issuer,RuntimeInvocation original,JSONObject identity,Authorization authorization,Output output)throws Exception{return new NativePipeAdmission(issuer,original,identity,authorization,output);}
    public static boolean exact(JSONObject exact){return Set.of(DISCOVERY,INVOKE).contains(exact.optString("intent"));}
    /** Pure plan metadata, NOT an admission. Plan/request digests subsequently lock this digest. */
    public static JSONObject bindingForHost(JSONObject identity,JSONObject originalBinding,String sourceDigest,long budget)throws Exception {
        Set<String> fields=new HashSet<>();identity.keys().forEachRemaining(fields::add);String purpose=identity.optString("purpose");
        if(!Set.of(DISCOVERY,INVOKE).contains(purpose)||!identity.optString("configDigest").matches("[a-f0-9]{64}")||!identity.optString("runtimeGeneration").matches("[a-f0-9]{64}")||!sourceDigest.matches("[a-f0-9]{64}")||budget<1||budget>RuntimePipeInput.MAX_TOTAL)throw new IllegalArgumentException("runtime_pipe_source_identity_invalid");
        if(INVOKE.equals(purpose)){
            if(!fields.equals(Set.of("purpose","installationId","stateRevision","configDigest","runtimeGeneration"))||!identity.optString("installationId").matches("[a-f0-9-]{36}")||!revision(identity.opt("stateRevision")))throw new IllegalArgumentException("runtime_pipe_invoke_installation_identity_required");
        }else {
            if(!fields.equals(Set.of("purpose","configurationId","expectedInstallationId","expectedStateRevision","configDigest","runtimeGeneration"))||!identity.optString("configurationId").matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("runtime_pipe_discovery_review_identity_required");
            String expected=identity.getString("expectedInstallationId");
            if("absent".equals(expected)){if(!identity.isNull("expectedStateRevision"))throw new IllegalArgumentException("runtime_pipe_absent_installation_revision_invalid");}
            else if(!expected.matches("[a-f0-9-]{36}")||!revision(identity.opt("expectedStateRevision")))throw new IllegalArgumentException("runtime_pipe_expected_installation_identity_required");
        }
        JSONObject result=ProjectSources.copy(identity);
        for(String key:new String[]{"taskId","runId","operationId"}){String value=originalBinding.getString(key);if(value.isEmpty()||value.length()>160||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("runtime_pipe_original_binding_invalid");result.put(key,value);}
        Object epoch=originalBinding.opt("coordinatorEpoch");if(!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<1||((Number)epoch).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("runtime_pipe_original_epoch_invalid");
        return result.put("coordinatorEpoch",epoch).put("sourceDigest",sourceDigest).put("nativePipeContract",NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT).put("writeByteLimit",budget).put("maxPacketBytes",RuntimePipeInput.MAX_PACKET).put("maxPending",RuntimePipeInput.MAX_PENDING);
    }
    private static boolean revision(Object value){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=0;}
    public JSONObject binding()throws Exception{return new JSONObject(binding);}
    void checkCurrent()throws Exception{invocation.checkCurrent();authorization.requireCurrent(invocation,binding());invocation.checkCurrent();}
    void claim(ManagedProjectRuntime runtime,RuntimeInvocation original)throws Exception {
        if(runtime!=issuer||original!=invocation||!claimed.compareAndSet(false,true))throw new SecurityException("runtime_pipe_original_admission_consumed");
        checkCurrent();authorization.claimBeforeDispatch(invocation,binding());checkCurrent();
    }
    synchronized void attach(ManagedProcessJobs.PipeHandle actual){if(!claimed.get()||handle!=null)throw new SecurityException("runtime_pipe_original_handle_already_bound");handle=Objects.requireNonNull(actual);}
    synchronized ManagedProcessJobs.PipeHandle originalHandle(){if(handle==null)throw new IllegalStateException("runtime_pipe_original_execution_query_only");return handle;}
    boolean bytes(int stream,byte[] bytes)throws Exception{checkCurrent();return output.bytes(stream,bytes.clone());}
    void receipt(RuntimeReceipt actual)throws Exception{invocation.executionRef().requireReceipt(actual.json());authorization.recordOriginalReceipt(actual);}
}
