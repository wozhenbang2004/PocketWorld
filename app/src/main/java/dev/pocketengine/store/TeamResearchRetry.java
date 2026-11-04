package dev.pocketengine.store;

import dev.pocketengine.runtime.usage.InferenceFailure;
import java.util.Set;
import org.json.JSONObject;

/** Content-free facts from the original SQL logical/physical receipt. No dispatch or budget authority. */
final class TeamResearchRetry {
    private TeamResearchRetry() { }

    static boolean definiteFailure(JSONObject logical,JSONObject attempt,JSONObject receipt,long waitMillis,long now) {
        try {
            if(logical==null||attempt==null||receipt==null||waitMillis<0||now<1
                    ||logical.getLong("deadlineAt")<=now||waitMillis>=logical.getLong("deadlineAt")-now
                    ||logical.getBoolean("semanticCommitted")||!logical.isNull("chosenCallId"))return false;
            JSONObject control=logical.getJSONObject("control"),snapshot=logical.getJSONObject("routeSnapshot");
            if(control.getInt("maxAttempts")!=2||control.getInt("maxRouteSwitches")!=1
                    ||control.getJSONArray("attempts").length()!=1||attempt.getInt("attemptNo")!=1
                    ||!"failed".equals(attempt.getString("attemptState"))||!"FAILED".equals(attempt.getString("outcome"))
                    ||!"may_have_occurred".equals(attempt.getString("dispatchState"))
                    ||!attempt.getString("callId").equals(attempt.getString("receiptRef")))return false;
            if(!"FAILED".equals(receipt.getString("outcome"))||receipt.getLong("finishedAt")<1
                    ||!attempt.getString("callId").equals(receipt.getString("callId")))return false;
            JSONObject context=receipt.getJSONObject("context"),route=receipt.getJSONObject("route");
            if(!logical.getString("taskId").equals(context.getString("taskId"))
                    ||!logical.getString("runId").equals(context.getString("runId"))
                    ||logical.getInt("epoch")!=context.getInt("epoch")
                    ||!"MEMBER".equals(context.getString("purpose"))
                    ||!snapshot.getString("contextPurpose").equals(context.getString("purpose"))
                    ||!snapshot.getString("contextMemberId").equals(context.getString("memberId"))
                    ||!logical.getString("logicalRequestId").equals(route.getString("logicalRequestId"))
                    ||attempt.getInt("attemptNo")!=route.getInt("attemptNo")
                    ||!attempt.getString("candidateId").equals(route.getString("candidateId")))return false;
            InferenceFailure failure=InferenceFailure.checked(receipt.getJSONObject("failure"));
            if(failure.kind!=InferenceFailure.Kind.HTTP||failure.partialTextReceived
                    ||!Set.of(500,502,503,504).contains(failure.httpStatus))return false;
            JSONObject retry=failure.retry();
            if("INVALID".equals(retry.getString("status")))return false;
            return !"VALID".equals(retry.getString("status"))
                    ||retry.getLong("estimatedNotBefore")<=now
                    ||retry.getLong("estimatedNotBefore")-now<=waitMillis;
        } catch(Exception invalidOriginalEvidence) {
            return false;
        }
    }
}
