package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.ToolOutcome;
import org.json.JSONObject;

/**
 * Post-dispatch receipt settlement, not admission or execution authority.
 * The journal remains the sole durable state machine and idempotency owner.
 * This class cannot dispatch, retry, deliver images, or invoke extensions.
 */
final class ToolReceiptSettlement {
    private ToolReceiptSettlement() { }

    /** Input must already be sanitized by the original Runner policy.
     * Do not check cancellation here: an issued effect must retain its real receipt
     * even when continuation has stopped. Always deliver the journal's canonical
     * result, which may be newer than the synchronous executor return. */
    static JSONObject commit(RunJournal journal,String operationId,JSONObject sanitizedReceipt)throws Exception {
        JSONObject saved=journal.finishOperationReceipt(operationId,sanitizedReceipt);
        if(saved==null)throw new IllegalStateException("Native journal returned no settled receipt");
        return saved;
    }

    /** Interpretation of the committed receipt only; never a new permission. */
    static final class Decision {
        final ToolOutcome outcome;
        final boolean projectRunning,nativeRunning;
        private Decision(ToolOutcome outcome,boolean projectRunning,boolean nativeRunning){
            this.outcome=outcome;this.projectRunning=projectRunning;this.nativeRunning=nativeRunning;
        }
        boolean unverifiedProjectRunning(){return projectRunning&&!nativeRunning;}
        boolean stopBatch(){return outcome.requiresUser()||outcome.requiresReconciliation()||unverifiedProjectRunning();}
        String eventStatus(){return projectRunning?nativeRunning?"running":"awaiting_user":outcome.eventStatus();}
    }

    static Decision inspect(RunJournal journal,String operationId,JSONObject receipt)throws Exception {
        ToolOutcome outcome=ToolOutcome.classify(receipt);
        boolean projectRunning="project_running".equals(receipt.optString("code",receipt.optString("error")));
        return new Decision(outcome,projectRunning,projectRunning&&nativeProjectRunning(journal,operationId,receipt));
    }

    /** Live continuation is separate from receipt persistence. Re-evaluate directly
     * before each callback/image handoff; never cache a grant across extension code. */
    static boolean continuationCurrent(RunControl control,long pauseRevision){
        return !control.isCancelled()&&!control.isPaused()&&pauseRevision==control.pauseRevision();
    }

    /** Wire shape alone is not proof: only the original native row may establish
     * ownership/liveness. Unknown receipts cannot become running acknowledgements. */
    private static boolean nativeProjectRunning(RunJournal journal,String operation,JSONObject receipt)throws Exception {
        if(journal==null||operation==null||!"running".equals(receipt.optString("status"))
                ||!Boolean.TRUE.equals(receipt.opt("ok"))||!Boolean.TRUE.equals(receipt.opt("accepted"))
                ||!Boolean.FALSE.equals(receipt.opt("receiptReceived"))||!Boolean.FALSE.equals(receipt.opt("terminal"))
                ||!Boolean.FALSE.equals(receipt.opt("replayAllowed"))||!Boolean.FALSE.equals(receipt.opt("serviceReady"))
                ||!operation.equals(receipt.optString("operationId"))||!operation.equals(receipt.optString("executionId"))
                ||!receipt.optString("launchAckIdentity").matches("[a-f0-9]{64}")
                ||receipt.optBoolean("requiresReconciliation")||receipt.optBoolean("reconciliationRequired")||receipt.optString("state").contains("unknown"))return false;
        return journal.hasNativeProjectRunningAck(operation,receipt);
    }
}
