package dev.pocketengine.runtime;

import dev.pocketengine.core.ToolOutcome;
import dev.pocketengine.features.memory.ToolOutputCapture;
import org.json.JSONArray;
import org.json.JSONObject;

/** Protocol delivery only. Callers retain receipt sanitization, dispatch authority,
 * durable checkpoints and the decision that a batch tail has not been issued. */
final class ToolResultDelivery {
    private ToolResultDelivery() { }

    /** The sanitized body and the outcome of the original receipt are deliberately
     * separate: redaction must not change the actual native outcome. No input is mutated. */
    static JSONObject message(String callId,JSONObject sanitizedReceipt,ToolOutcome outcome)throws Exception {
        JSONObject body=new JSONObject();
        for(var keys=sanitizedReceipt.keys();keys.hasNext();){String key=keys.next();body.put(key,sanitizedReceipt.get(key));}
        body.put(ToolOutcome.KEY,outcome.view());
        JSONObject row=new JSONObject().put("role","tool").put("tool_call_id",callId).put("content",body.toString());
        if(body.has("outputCapture"))row.put("toolOutputCapture",ToolOutputCapture.checkedMetadata(body.getJSONObject("outputCapture")));
        return row;
    }

    /** Complete only the caller-selected, not-yet-dispatched suffix. Preserve native
     * call order and identities; this neither prepares nor retries any operation.
     * This is not a recovery/dedup API: the live Runner owns the batch cursor. */
    static void appendUnissued(JSONArray history,JSONArray calls,int first,JSONObject sanitizedReceipt,ToolOutcome outcome)throws Exception {
        if(first<0||first>calls.length())throw new IllegalArgumentException("Invalid unissued batch cursor");
        for(int index=first;index<calls.length();index++)
            history.put(message(calls.getJSONObject(index).getString("id"),sanitizedReceipt,outcome));
    }
}
