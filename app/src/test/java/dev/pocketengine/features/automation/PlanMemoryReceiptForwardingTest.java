package dev.pocketengine.features.automation;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** The scoped workspace adapter must retain the original request/response audit lifecycle. */
public final class PlanMemoryReceiptForwardingTest {
    private static JSONObject standard() throws Exception {
        return new JSONObject().put("kind", "workspace_artifact")
                .put("projectId", "01234567-89ab-cdef-0123-456789abcdef")
                .put("path", "result.txt").put("requiredHeading", "Result").put("minBytes", 1);
    }
    private static final class Original implements CapabilityPort {
        final RunControl live = new RunControl();
        String prepared;
        JSONArray actualMessages;
        int abandoned;
        @Override public JSONArray tools() { return new JSONArray(); }
        @Override public boolean supports(String tool) { return false; }
        @Override public boolean sideEffecting(String tool) { return false; }
        @Override public void validate(String tool, JSONObject args) { throw new AssertionError("No tool was requested"); }
        @Override public JSONObject execute(String tool, JSONObject args, RunControl control) { throw new AssertionError("No effect was requested"); }
        @Override public void beforeModelRequest(String requestId, JSONArray messages, RunControl control) {
            if (control != live) throw new SecurityException("Different original control");
            prepared = requestId; actualMessages = messages;
        }
        @Override public JSONObject afterModelResponse(String requestId, String answerId, RunControl control) throws Exception {
            if (control != live || !requestId.equals(prepared)) throw new SecurityException("Different original request");
            prepared = null;
            return new JSONObject().put("requestId", requestId).put("answerId", answerId).put("executionAuthorized", false);
        }
        @Override public void modelRequestAbandoned(String requestId) {
            if (requestId.equals(prepared)) { abandoned++; prepared = null; }
        }
    }
    @Test public void completedResponseRetainsTheOriginalRequestReceiptAndActualWireMessages() throws Exception {
        Original original = new Original();
        PlanWorkspacePort scoped = new PlanWorkspacePort(original, standard(), id -> null);
        JSONArray wire = new JSONArray().put(new JSONObject().put("role", "user").put("content", "current exact reference"));
        scoped.beforeModelRequest("request-1", wire, original.live);
        assertSame("The final projected messages must reach the original audit path", wire, original.actualMessages);
        JSONObject receipt = scoped.afterModelResponse("request-1", "answer-1", original.live);
        assertNotNull("A scoped workspace must not discard the native completed-request receipt", receipt);
        assertEquals("request-1", receipt.getString("requestId"));
        assertEquals("answer-1", receipt.getString("answerId"));
        assertFalse(receipt.getBoolean("executionAuthorized"));
        assertNull(original.prepared);
    }
    @Test public void abandonedRequestReleasesTheOriginalIntentEvenAfterCancellation() throws Exception {
        Original original = new Original();
        PlanWorkspacePort scoped = new PlanWorkspacePort(original, standard(), id -> null);
        scoped.beforeModelRequest("request-2", new JSONArray(), original.live);
        original.live.cancel();
        scoped.modelRequestAbandoned("request-2");
        assertEquals("Cleanup is required when the response can no longer be consumed", 1, original.abandoned);
        assertNull(original.prepared);
    }
    @Test public void scopedAdapterCannotHideTheOriginalControlRejection() throws Exception {
        Original original = new Original();
        PlanWorkspacePort scoped = new PlanWorkspacePort(original, standard(), id -> null);
        try {
            scoped.beforeModelRequest("request-3", new JSONArray(), new RunControl());
            fail("The original request authority must reject the substituted control");
        } catch (SecurityException expected) { }
        assertNull(original.prepared);
    }
}

