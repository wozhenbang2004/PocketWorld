package dev.pocketengine.features.local.runtime;

import org.json.JSONObject;

/** Execution transport only. The existing host owns Task/Run, approval, launch CAS and retry policy. */
public interface ProjectRuntime {
    interface LiveGate { void check() throws Exception; }
    JSONObject describe() throws Exception;
    RuntimeReceipt dispatch(RuntimeInvocation invocation) throws Exception;
    RuntimeReceipt query(RuntimeExecutionRef original) throws Exception;
    RuntimeReceipt requestCancel(RuntimeExecutionRef original) throws Exception;
    default JSONObject readLog(RuntimeExecutionRef original,long afterSequence,int maxBytes) throws Exception {
        throw new UnsupportedOperationException("runtime_live_log_not_supported");
    }
}
