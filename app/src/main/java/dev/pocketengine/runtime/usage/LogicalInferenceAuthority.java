package dev.pocketengine.runtime.usage;

import org.json.JSONObject;

/** Installed only by the original TaskStore target. No HTTP fields can supply this authority. */
public interface LogicalInferenceAuthority {
    JSONObject begin(String logicalId,String digest,JSONObject routeSnapshot,long requestedDeadline)throws Exception;
    boolean matchesHistoryOrigin(String physicalCallId,dev.pocketengine.core.ModelConfig candidate,JSONObject providerData)throws Exception;
    boolean reserveRetry(String logicalId,String failedCallId,long waitMillis)throws Exception;
    void reserve(String logicalId,String callId,int attemptNo,String candidateId,String retryReason)throws Exception;
    void commit(String logicalId,String callId,long semanticSequence)throws Exception;
    void attemptEnded(String logicalId,String callId,boolean dispatched,String state)throws Exception;
    void finish(String logicalId,String state,String completion,String stream)throws Exception;
}
