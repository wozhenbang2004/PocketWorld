package dev.pocketengine.phone.observation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** A caller-visible acknowledgement value, never an admission or permission to repeat an action. */
public final class NativeNodeActionReceipt {
    private final AtomicBoolean accepted=new AtomicBoolean();
    private final AtomicReference<String> receipt;

    /** Prepare acknowledgement metadata only, without input arguments or node objects. */
    public NativeNodeActionReceipt(JSONObject original) {
        if(!Boolean.TRUE.equals(original.opt("accepted")))throw new IllegalArgumentException("accepted_receipt_required");
        receipt=new AtomicReference<>(original.toString());
    }

    /** Called immediately after the platform action returns, before any fallible follow-up. */
    public boolean record(boolean platformAccepted) {
        if(platformAccepted)accepted.set(true);
        return platformAccepted;
    }

    /** Publish an immutable copy; the caller never observes a worker-owned JSONObject. */
    public void update(JSONObject value) {
        if(accepted.get()&&Boolean.TRUE.equals(value.opt("accepted")))receipt.set(value.toString());
    }

    public JSONObject afterReadFailure(String reason)throws Exception {
        return accepted.get()?observationUnavailable(new JSONObject(receipt.get()),reason):null;
    }

    /** Keep known dispatch facts while the original runner stops for unavailable follow-up evidence. */
    public static JSONObject observationUnavailable(JSONObject original,String reason)throws Exception {
        if(!Boolean.TRUE.equals(original.opt("accepted")))throw new IllegalArgumentException("accepted_receipt_required");
        return new JSONObject(original.toString()).put("freshObservationAvailable",false).put("requiresPause",true)
                .put("nextObservationAction","pause").put("automaticReplayAllowed",false).put("verifiedBusinessSuccess",false)
                .put("postActionObservation",new JSONObject().put("status","unavailable_after_dispatch").put("reason",reason)
                        .put("dispatchReceiptPreserved",true).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false));
    }
}
