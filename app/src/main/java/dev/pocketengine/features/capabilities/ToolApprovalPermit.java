package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONObject;

/** Process-local, current-thread approval of one exact native call. Never an execution grant. */
public final class ToolApprovalPermit {
    public interface Current { void check()throws Exception; }
    private static final ThreadLocal<ToolApprovalPermit> ACTIVE=new ThreadLocal<>();
    private static final ThreadLocal<NativeOwnerReview> NATIVE_REVIEW=new ThreadLocal<>();
    private final Object owner;
    private final String task,run,tool,argumentsHash;
    private final RunControl control;
    private final Current current;
    private final JSONObject proof;
    private String nativeArgumentsHash,capabilityHash;
    private ToolApprovalPermit(Object owner,String task,String run,String tool,JSONObject arguments,RunControl control,Current current,JSONObject proof)throws Exception {
        this.owner=owner;this.task=task;this.run=run;this.tool=tool;this.argumentsHash=TaskStore.capabilityFingerprint(exact(arguments));
        this.control=control;this.current=current;this.proof=new JSONObject(proof.toString());
    }
    static void begin(Object owner,String task,String run,String tool,JSONObject arguments,RunControl control,Current current,JSONObject proof)throws Exception {
        ACTIVE.set(new ToolApprovalPermit(owner,task,run,tool,arguments,control,current,proof));
    }
    static void clear(Object owner){ToolApprovalPermit active=ACTIVE.get();if(active!=null&&active.owner==owner)ACTIVE.remove();}
    static JSONObject exact(JSONObject arguments)throws Exception{JSONObject result=new JSONObject(arguments.toString());result.remove("_hostOperationId");return result;}
    /** One outer beforeTool invocation only. This routing scope never creates an approval permit. */
    static final class NativeOwnerReview implements AutoCloseable {
        private final ModelCapabilityAdapters.Binding binding;
        private final String tool,argumentsHash;
        private final RunControl control;
        private final long pause;
        private boolean requested,closed;
        private NativeOwnerReview(ModelCapabilityAdapters.Binding binding,String tool,JSONObject arguments,RunControl control)throws Exception {
            binding.check(control);this.binding=binding;this.tool=tool;this.control=control;pause=control.pauseRevision();
            argumentsHash=TaskStore.capabilityFingerprint(exact(arguments));
        }
        private void check()throws Exception {
            if(closed||NATIVE_REVIEW.get()!=this)throw new SecurityException("原生主人审批路由已离开原调用");
            binding.check(control);if(control.pauseRevision()!=pause)throw new SecurityException("原生主人审批路由经历暂停，不能继续旧调用");
        }
        boolean requested()throws Exception{check();return requested;}
        @Override public void close(){closed=true;if(NATIVE_REVIEW.get()==this)NATIVE_REVIEW.remove();}
    }
    static NativeOwnerReview nativeOwnerReview(ModelCapabilityAdapters.Binding binding,String tool,JSONObject arguments,RunControl control)throws Exception {
        if(NATIVE_REVIEW.get()!=null)throw new SecurityException("不能嵌套替换原生主人审批路由");
        NativeOwnerReview review=new NativeOwnerReview(binding,tool,arguments,control);NATIVE_REVIEW.set(review);return review;
    }
    /**
     * Native wrappers call only from beforeTool when execute must require an exact protected owner
     * review or recheck the consumed parent review. This is not a model field, approval or grant.
     * Without an outer policy invocation the wrapper simply retains its ordinary native review.
     */
    public static void requireNativeOwnerReview(ModelCapabilityAdapters.Binding binding,String tool,JSONObject arguments,RunControl original)throws Exception {
        binding.check(original);NativeOwnerReview review=NATIVE_REVIEW.get();if(review==null)return;
        review.check();
        if(review.control!=original||!review.binding.taskId.equals(binding.taskId)||!review.binding.runId.equals(binding.runId)
                ||review.binding.epoch!=binding.epoch||!review.tool.equals(tool)
                ||!review.argumentsHash.equals(TaskStore.capabilityFingerprint(exact(arguments))))
            throw new SecurityException("原生主人审批路由不属于当前准确调用");
        review.requested=true;
    }
    /** Only the mounted native descriptor may bind its validated defaults to the exact reviewed call. */
    static JSONObject normalizeArguments(ModelCapabilityAdapters.Binding binding,CapabilityDescriptor route,JSONObject original,RunControl control)throws Exception{
        JSONObject normalized=route.normalize(original);ToolApprovalPermit active=ACTIVE.get();
        if(active==null||active.control!=control||!active.task.equals(binding.taskId)||!active.run.equals(binding.runId)
                ||!active.tool.equals(route.name)||!active.argumentsHash.equals(TaskStore.capabilityFingerprint(exact(original))))return normalized;
        active.current.check();binding.check(control);
        String actual=TaskStore.capabilityFingerprint(normalized);
        if(active.nativeArgumentsHash!=null&&!active.nativeArgumentsHash.equals(actual))throw new SecurityException("同次调用的原生参数已改变，不能沿用审批");
        active.nativeArgumentsHash=actual;return normalized;
    }
    /** Special owner scope/installation decisions keep their original native review path. */
    public static boolean protectedApproval(String tool,JSONObject capability){
        return Set.of("execution_plan_review",dev.pocketengine.features.local.runtime.RuntimeSetupHost.TOOL).contains(tool)
                ||"start_parent_plan".equals(capability.optString("command"))
                ||capability.has("nativeProgram")||capability.has("ownedService")
                ||capability.optBoolean("requiresOwnerConsent")||capability.optBoolean("systemPermission");
    }
    public static JSONObject currentApproval(String task,String run,String tool,JSONObject arguments,JSONObject capability)throws Exception{
        ToolApprovalPermit active=ACTIVE.get();
        if(active==null||!active.task.equals(task)||!active.run.equals(run)||!active.tool.equals(tool)
                ||protectedApproval(tool,capability)||"tool_permission_policy".equals(capability.optString("kind"))
                ||!(active.nativeArgumentsHash==null?active.argumentsHash:active.nativeArgumentsHash).equals(TaskStore.capabilityFingerprint(exact(arguments))))return null;
        active.current.check();active.control.check();
        if(active.control.isPaused()||active.control.isCancelled()||!active.control.matchesHostRun(task,run,active.control.hostRunIdentity().epoch()))
            throw new SecurityException("原工具审批控制已变化");
        String actual=TaskStore.capabilityFingerprint(capability);
        if(active.capabilityHash!=null&&!active.capabilityHash.equals(actual))throw new SecurityException("同次调用的能力身份已改变，不能沿用审批");
        active.capabilityHash=actual;
        return new JSONObject(active.proof.toString()).put("argumentsHash",active.nativeArgumentsHash==null?active.argumentsHash:active.nativeArgumentsHash).put("capabilityHash",actual);
    }
}
