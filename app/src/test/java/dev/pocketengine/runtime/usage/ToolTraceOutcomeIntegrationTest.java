package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ToolTraceOutcomeIntegrationTest {
    private JSONObject finish(JSONObject receipt) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        RunControl control = new RunControl();
        control.bindHostRun("task", "run", 1);
        ToolTraceCapture trace = new ToolTraceCapture("task", control, null, "", "provider", "read", "", "", new JSONObject(), events::add);
        trace.dispatchEntered();
        trace.finish(receipt, "operation", null, control);
        assertEquals(2, events.size());
        assertEquals(receipt.toString(), events.get(1).getJSONObject("output").toString());
        return events.get(1);
    }
    @Test public void canonicalFailureOverridesOptimisticOk() throws Exception {
        assertEquals("FAILED", finish(new JSONObject().put("ok", true).put("status", "failed")).getString("outcome"));
        assertEquals("FAILED", finish(new JSONObject().put("ok", true).put("exitCode", 1)).getString("outcome"));
    }
    @Test public void canonicalUncertaintyOverridesOptimisticAndCancelledFacts() throws Exception {
        assertEquals("UNKNOWN", finish(new JSONObject().put("ok", true).put("reconciliationRequired", true)).getString("outcome"));
        assertEquals("UNKNOWN", finish(new JSONObject().put("ok", true).put("requiresReconciliation", "false")).getString("outcome"));
        assertEquals("UNKNOWN", finish(new JSONObject().put("status", "cancelled").put("accepted", JSONObject.NULL)).getString("outcome"));
    }
    @Test public void ownerInterventionOverridesAdmissionRefusal() throws Exception {
        assertEquals("AWAITING_USER", finish(new JSONObject().put("ok", false).put("accepted", false).put("code", "approval_required")).getString("outcome"));
        assertEquals("AWAITING_USER", finish(new JSONObject().put("ok", true).put("requiresOwnerReturn", true)).getString("outcome"));
    }
    @Test public void measuredReturnedCallDoesNotEstablishExternalCompletion() throws Exception {
        for (String state : new String[]{"accepted", "running", "pending", "queued", "stopping"}) {
            JSONObject event = finish(new JSONObject().put("ok", true).put("status", state));
            assertTrue(event.getBoolean("effectPending"));
            assertEquals("invocation_returned_before_effect_completion", event.getString("completionMeaning"));
            assertTrue(Long.parseLong(event.getString("elapsedNanos")) >= 0);
        }
        assertTrue(finish(new JSONObject().put("ok", true).put("code", "project_stopping")).getBoolean("effectPending"));
    }
}
