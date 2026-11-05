package dev.pocketengine.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class CapabilityPortReceiptAfterStopTest {
    @Test public void originalStoppedControlCanRecoverItsActualReceiptWithoutResuming() throws Exception {
        RunControl original = new RunControl();
        original.cancel();
        InterruptedException stopped = assertThrows(InterruptedException.class, original::check);
        JSONObject actual = new JSONObject().put("ok", true).put("operationId", "original-operation");
        CapabilityPort.ReceiptAfterStop retained = new CapabilityPort.ReceiptAfterStop(original, actual, stopped);

        assertEquals(ScopedToolCatalog.fingerprint(actual), ScopedToolCatalog.fingerprint(retained.receipt(original)));
        assertSame(stopped, retained.failure(original));
        assertSame(stopped, retained.getCause());
        assertTrue(original.isCancelled());
        assertThrows(InterruptedException.class, original::check);
    }

    @Test public void matchingIdentityCannotReplaceTheExactOriginalControl() throws Exception {
        RunControl original = RunControl.forOriginalRecovery("task", "run", 1);
        RunControl sameIdentity = RunControl.forOriginalRecovery("task", "run", 1);
        assertEquals(original.hostRunIdentity(), sameIdentity.hostRunIdentity());
        CapabilityPort.ReceiptAfterStop retained = new CapabilityPort.ReceiptAfterStop(
                original, new JSONObject().put("ok", true), new SecurityException("authority withdrawn"));

        assertThrows(SecurityException.class, () -> retained.receipt(sameIdentity));
        assertThrows(SecurityException.class, () -> retained.failure(sameIdentity));
        assertThrows(SecurityException.class, () -> retained.receipt(null));
        assertThrows(SecurityException.class, () -> retained.failure(null));
        assertTrue(retained.receipt(original).getBoolean("ok"));
        assertThrows(SecurityException.class, original::requireExecution);
    }

    @Test public void mutatingTheOriginalBodyCannotRewriteTheRetainedEvidence() throws Exception {
        RunControl original = new RunControl();
        JSONObject child = new JSONObject().put("effect", "confirmed");
        JSONArray values = new JSONArray().put(child);
        JSONObject actual = new JSONObject().put("ok", true).put("details", values);
        CapabilityPort.ReceiptAfterStop retained = new CapabilityPort.ReceiptAfterStop(
                original, actual, new InterruptedException("stopped"));
        child.put("effect", "changed");
        values.put("extra");
        actual.put("ok", false);

        JSONObject recovered = retained.receipt(original);
        assertTrue(recovered.getBoolean("ok"));
        assertEquals(1, recovered.getJSONArray("details").length());
        assertEquals("confirmed", recovered.getJSONArray("details").getJSONObject(0).getString("effect"));
    }

    @Test public void eachRecoveryReturnsAnIndependentBodyWhileKeepingTheOriginalFailure() throws Exception {
        RunControl original = new RunControl();
        Exception failure = new SecurityException("authority withdrawn");
        JSONObject actual = new JSONObject().put("ok", true)
                .put("details", new JSONArray().put(new JSONObject().put("effect", "confirmed")));
        CapabilityPort.ReceiptAfterStop retained = new CapabilityPort.ReceiptAfterStop(original, actual, failure);
        JSONObject first = retained.receipt(original);
        first.put("ok", false);
        first.getJSONArray("details").getJSONObject(0).put("effect", "changed");
        JSONObject second = retained.receipt(original);

        assertNotSame(first, second);
        assertEquals(ScopedToolCatalog.fingerprint(actual), ScopedToolCatalog.fingerprint(second));
        assertSame(failure, retained.failure(original));
        assertSame(failure, retained.getCause());
    }

    @Test public void missingOriginalControlReceiptOrFailureIsRejected() {
        RunControl original = new RunControl();
        Exception failure = new InterruptedException("stopped");
        assertThrows(NullPointerException.class, () -> new CapabilityPort.ReceiptAfterStop(null, new JSONObject(), failure));
        assertThrows(NullPointerException.class, () -> new CapabilityPort.ReceiptAfterStop(original, null, failure));
        assertThrows(NullPointerException.class, () -> new CapabilityPort.ReceiptAfterStop(original, new JSONObject(), null));
    }
}
