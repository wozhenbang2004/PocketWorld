package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceFailure;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;

/** Bounded recovery of inference only. Never owns a task, tool, route, credential or usage receipt. */
public final class SafeModelRecovery {
    public static final int MAX_RETRIES_PER_TURN=2,MAX_RETRIES_PER_TASK=6;
    public static final long MAX_TOTAL_WAIT_MILLIS=30_000;
    private int retries;
    private long waited;
    /** -1 means keep the original failure. Every retry remains a new physical, attributed call. */
    public long delay(Exception original,int turnRetries,long now)throws Exception {
        if(turnRetries>=MAX_RETRIES_PER_TURN||retries>=MAX_RETRIES_PER_TASK)return -1;
        InferenceFailure failure=original instanceof ModelGateway.GatewayException
                ?((ModelGateway.GatewayException)original).observation():null;
        if(failure==null||!transientFailure(failure))return -1;
        JSONObject retry=failure.retry();
        if("INVALID".equals(retry.optString("status")))return -1;
        long delay=500L*(1L<<turnRetries)+ThreadLocalRandom.current().nextLong(251);
        if("VALID".equals(retry.optString("status")))delay=Math.max(delay,Math.max(0,retry.getLong("estimatedNotBefore")-now));
        if(delay>MAX_TOTAL_WAIT_MILLIS-waited)return -1;
        return delay;
    }
    public void waitBeforeRetry(long delay,RunControl original)throws InterruptedException {
        if(delay<0||delay>MAX_TOTAL_WAIT_MILLIS-waited||retries>=MAX_RETRIES_PER_TASK)throw new IllegalArgumentException("recovery_budget_exhausted");
        // Debit before waiting; pause, cancellation or process loss never refunds a request allowance.
        retries++;waited+=delay;original.sleep(delay);original.checkpoint();
    }
    public int retries(){return retries;}
    public long waitedMillis(){return waited;}
    public static boolean contextLimit(Exception original){
        InferenceFailure failure=original instanceof ModelGateway.GatewayException?((ModelGateway.GatewayException)original).observation():null;
        return failure!=null&&(failure.kind==InferenceFailure.Kind.CONTEXT_LIMIT
                ||failure.kind==InferenceFailure.Kind.ACCOUNT_GATE&&"ACCOUNT_CONTEXT_BUDGET_EXCEEDED".equals(failure.nativeCode));
    }
    private static boolean transientFailure(InferenceFailure failure){
        if(failure.httpStatus==401||failure.httpStatus==403)return false;
        if(failure.kind==InferenceFailure.Kind.NETWORK||failure.kind==InferenceFailure.Kind.TIMEOUT||failure.kind==InferenceFailure.Kind.INCOMPLETE_STREAM)return true;
        if(failure.kind==InferenceFailure.Kind.HTTP)return failure.httpStatus==429||failure.httpStatus==500||failure.httpStatus==502||failure.httpStatus==503||failure.httpStatus==504;
        return failure.kind==InferenceFailure.Kind.ACCOUNT_GATE
                &&java.util.Set.of("PROVIDER_UNAVAILABLE","PROVIDER_RATE_LIMITED","RATE_LIMITED","GATEWAY_BUSY","PROVIDER_NETWORK_ERROR","PROVIDER_STREAM_INTERRUPTED").contains(failure.nativeCode)
                &&(failure.httpStatus==0||failure.httpStatus==429||failure.httpStatus==500||failure.httpStatus==502||failure.httpStatus==503||failure.httpStatus==504);
    }
}
