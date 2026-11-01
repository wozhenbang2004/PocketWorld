package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.TermuxBridge;
import org.json.JSONArray;
import org.json.JSONObject;

/** The actual official RUN_COMMAND bridge, shared with the existing native owner workflow. */
public final class TermuxProjectRuntime implements ProjectRuntime {
    private final TermuxBridge bridge;
    public TermuxProjectRuntime(TermuxBridge bridge) { this.bridge=java.util.Objects.requireNonNull(bridge); }
    @Override public JSONObject describe() throws Exception {
        return bridge.status().put("provider","termux_run_command").put("canonicalProjectOwner","existing_workspace")
                .put("requiresHostApproval",true).put("requiresPreparedSource",true).put("createsTasks",false)
                .put("outputDelivery","official_terminal_callback").put("liveStdout",false)
                .put("scopeEnforced",false).put("reliableCancellation",false)
                .put("phases",new JSONArray().put("PROBE").put("STAGE").put("COMMAND"));
    }
    @Override public RuntimeReceipt dispatch(RuntimeInvocation invocation) throws Exception {
        java.util.Objects.requireNonNull(invocation).checkCurrent();return bridge.dispatchForRuntime(invocation);
    }
    @Override public RuntimeReceipt query(RuntimeExecutionRef original) throws Exception {
        return RuntimeReceipt.fromBackend(original,bridge.get(original.operationId()));
    }
    @Override public RuntimeReceipt requestCancel(RuntimeExecutionRef original) throws Exception {
        // Validate original ownership before even recording the stop request. RUN_COMMAND has no kill API.
        original.requireReceipt(bridge.get(original.operationId()));
        return RuntimeReceipt.fromBackend(original,bridge.cancel(original.operationId()));
    }
}
