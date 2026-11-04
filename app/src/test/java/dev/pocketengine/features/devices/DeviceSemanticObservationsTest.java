package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** The real native delivery gate with already-authenticated receipt fixtures.
 * This is not a Windows, model transport, SQL or device integration test. */
public final class DeviceSemanticObservationsTest {
    private static final String ROUTE = "desktop-window-a";
    private static final String DEVICE = "pw1_" + "a".repeat(64);

    private static RunControl run() {
        RunControl control = new RunControl();
        control.bindHostRun("original-task", "original-run", 1);
        return control;
    }

    private static JSONObject receipt(String operation, String id, long captured) throws Exception {
        JSONObject ref = new JSONObject().put("id", id).put("sessionId", 1).put("displayId", "primary")
                .put("windowId", 10).put("binding", new JSONObject().put("nativeWindow", 10))
                .put("driverGeneration", "b".repeat(32)).put("capturedAt", captured).put("expiresAt", captured + 30000);
        JSONObject state = new JSONObject().put("snapshot_id", id).put("semantic_provider", "agent-desktop")
                .put("elements_complete", true).put("truncated", false).put("identity_unproven", false)
                .put("elements", new JSONArray().put(new JSONObject().put("element_token", "s12345678:0")
                        .put("label", "Save").put("role", "Button").put("enabled", true)
                        .put("actions", new JSONArray().put("invoke"))));
        return new JSONObject().put("messageType", "OperationReceipt").put("operationId", operation)
                .put("executorDeviceId", DEVICE).put("payload", new JSONObject().put("observationMode", "semantic")
                        .put("observationRef", ref).put("state", state));
    }

    private static JSONObject ref(JSONObject receipt) throws Exception {
        return receipt.getJSONObject("payload").getJSONObject("observationRef");
    }

    private static JSONObject wire(JSONObject receipt) throws Exception {
        return new JSONObject().put("operationId", receipt.getString("operationId"))
                .put("targetDeviceId", DEVICE).put("deadline", System.currentTimeMillis() + 60000);
    }

    private static JSONArray messages(JSONObject receipt) throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "tool").put("tool_call_id", "native-tool-call")
                .put("content", new JSONObject().put("deviceReceipt", receipt).toString()));
    }

    private static void deliver(DeviceSemanticObservations gate, RunControl run, String request, JSONObject receipt) throws Exception {
        gate.beforeRequest(request, messages(receipt), run);
        assertEquals(1, gate.afterResponse(request, run).getInt("structuresProvided"));
    }

    @Test public void retiredReferenceCannotUseDeliveryOfNewerObservation() throws Exception {
        RunControl control = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            long now = System.currentTimeMillis();
            JSONObject old = receipt("old-operation", "old-observation", now - 1000);
            JSONObject latest = receipt("new-operation", "new-observation", now - 500);
            gate.capture(ROUTE, wire(old), old, () -> { });
            gate.capture(ROUTE, wire(latest), latest, () -> { });
            deliver(gate, control, "current-request", latest);
            assertTrue("Keep retired semantic refs on the semantic rejection path", gate.contains(ROUTE, ref(old)));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(old)));
            gate.requireAction(ROUTE, ref(latest));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(latest)));
        }
    }

    @Test public void compactedRequestCannotReusePreviousResponseDelivery() throws Exception {
        RunControl control = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            deliver(gate, control, "request-with-structure", receipt);
            gate.beforeRequest("compacted-request", new JSONArray().put(new JSONObject().put("role", "system")
                    .put("content", "A summary mentions an earlier Save button.")), control);
            assertNull(gate.afterResponse("compacted-request", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
        }
    }

    @Test public void revokedObservationDoesNotDiscardAnOtherwiseValidModelAnswer() throws Exception {
        RunControl control = run();
        AtomicBoolean current = new AtomicBoolean(true);
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> {
                if (!current.get()) throw new SecurityException("Original desktop route was revoked");
            });
            gate.beforeRequest("request", messages(receipt), control);
            current.set(false);
            assertNull(gate.afterResponse("request", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
        }
    }

    @Test public void truncatedNativeTreeCannotAuthorizeSemanticInput() throws Exception {
        RunControl control = run();
        for (String flag : new String[]{"truncated", "adapter_truncated", "identity_unproven", "hasMore", "elements_complete"}) {
            try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
                JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
                receipt.getJSONObject("payload").getJSONObject("state").put(flag, !flag.equals("elements_complete"));
                assertThrows(flag, SecurityException.class, () -> gate.capture(ROUTE, wire(receipt), receipt, () -> { }));
            }
        }
    }

    @Test public void onlyMatchingCompletedRequestDeliversTheActualStructure() throws Exception {
        RunControl control = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            gate.beforeRequest("original-request", messages(receipt), control);
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            assertNull(gate.afterResponse("unrelated-request", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            JSONObject delivered = gate.afterResponse("original-request", control);
            assertEquals(1, delivered.getInt("structuresProvided"));
            assertFalse(delivered.getBoolean("businessVerified"));
            gate.requireAction(ROUTE, ref(receipt));
            assertNull(gate.afterResponse("original-request", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
        }
    }

    @Test public void textPartsDeliverTheCompleteReceiptButAnotherRunCannot() throws Exception {
        RunControl control = run(), differentControlForSameIds = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", receipt.toString()));
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "tool").put("content", parts));
            assertThrows(SecurityException.class, () -> gate.beforeRequest("other-run", messages, differentControlForSameIds));
            gate.beforeRequest("original", messages, control);
            assertThrows(SecurityException.class, () -> gate.afterResponse("original", differentControlForSameIds));
            assertEquals(1, gate.afterResponse("original", control).getInt("structuresProvided"));
            gate.requireAction(ROUTE, ref(receipt));
        }
    }

    @Test public void summariesOrAlteredReceiptsCannotStandInForTheOriginalTree() throws Exception {
        RunControl control = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            for (String altered : new String[]{"operation", "executor", "mode", "reference", "state", "missing-state", "role", "clipped-json"}) {
                JSONObject changed = new JSONObject(receipt.toString());
                if (altered.equals("operation")) changed.put("operationId", "other-operation");
                if (altered.equals("executor")) changed.put("executorDeviceId", "pw1_" + "c".repeat(64));
                if (altered.equals("mode")) changed.getJSONObject("payload").put("observationMode", "hybrid");
                if (altered.equals("reference")) ref(changed).put("id", "another-observation");
                if (altered.equals("state")) changed.getJSONObject("payload").getJSONObject("state").getJSONArray("elements").remove(0);
                if (altered.equals("missing-state")) changed.getJSONObject("payload").remove("state");
                JSONArray modelMessages = messages(changed);
                if (altered.equals("role")) modelMessages.getJSONObject(0).put("role", "user");
                if (altered.equals("clipped-json")) modelMessages.getJSONObject(0).put("content", changed.toString().substring(0, 100));
                gate.beforeRequest(altered, modelMessages, control);
                assertNull(altered, gate.afterResponse(altered, control));
                assertThrows(altered, SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            }
            deliver(gate, control, "complete-original", receipt);
            gate.requireAction(ROUTE, ref(receipt));
        }
    }

    @Test public void abandonedAndReplacedRequestsCannotRestoreDelivery() throws Exception {
        RunControl control = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            gate.beforeRequest("abandoned", messages(receipt), control);
            gate.abandoned("abandoned");
            assertNull(gate.afterResponse("abandoned", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            gate.beforeRequest("older", messages(receipt), control);
            gate.beforeRequest("current-empty", new JSONArray(), control);
            assertNull(gate.afterResponse("older", control));
            assertNull(gate.afterResponse("current-empty", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            deliver(gate, control, "valid", receipt);
            gate.abandoned("older");
            gate.abandoned("valid");
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            deliver(gate, control, "fresh-request", receipt);
            gate.requireAction(ROUTE, ref(receipt));
        }
    }

    @Test public void aLateResponseForReplacedObservationCannotConsumeTheNewOne() throws Exception {
        RunControl control = run();
        AtomicLong time = new AtomicLong(System.currentTimeMillis());
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control, time::get)) {
            JSONObject old = receipt("old-operation", "old", time.get());
            gate.capture(ROUTE, wire(old), old, () -> { });
            gate.beforeRequest("old-request", messages(old), control);
            JSONObject latest = receipt("new-operation", "new", time.incrementAndGet());
            gate.capture(ROUTE, wire(latest), latest, () -> { });
            assertNull(gate.afterResponse("old-request", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(old)));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(latest)));
            deliver(gate, control, "latest-request", latest);
            gate.requireAction(ROUTE, ref(latest));
        }
    }

    @Test public void expiryWhileWaitingForModelDoesNotRejectItsAnswerOrAuthorizeInput() throws Exception {
        RunControl control = run();
        AtomicLong time = new AtomicLong(System.currentTimeMillis());
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control, time::get)) {
            JSONObject receipt = receipt("operation", "observation", time.get());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            gate.beforeRequest("slow-model", messages(receipt), control);
            time.addAndGet(30000);
            assertNull(gate.afterResponse("slow-model", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            gate.beforeRequest("unrelated-answer", new JSONArray(), control);
            assertNull(gate.afterResponse("unrelated-answer", control));
        }
    }

    @Test public void originalReferenceExpiryAndWireDeadlineBothBoundInput() throws Exception {
        for (boolean referenceExpiresFirst : new boolean[]{true, false}) {
            RunControl control = run();
            AtomicLong time = new AtomicLong(System.currentTimeMillis());
            try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control, time::get)) {
                JSONObject receipt = receipt("operation", "observation", time.get());
                JSONObject wire = wire(receipt);
                if (referenceExpiresFirst) ref(receipt).put("expiresAt", time.get() + 1000);
                else wire.put("deadline", time.get() + 1000);
                gate.capture(ROUTE, wire, receipt, () -> { });
                deliver(gate, control, "request", receipt);
                time.addAndGet(1000);
                assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            }
        }
    }

    @Test public void staleOrFutureCaptureIsRejectedWithoutAClockWait() throws Exception {
        RunControl control = run();
        AtomicLong time = new AtomicLong(System.currentTimeMillis());
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control, time::get)) {
            for (long capture : new long[]{time.get() - 30000, time.get() + 1001}) {
                JSONObject receipt = receipt("operation", "observation", capture);
                assertThrows(SecurityException.class, () -> gate.capture(ROUTE, wire(receipt), receipt, () -> { }));
            }
            JSONObject expiredRef = receipt("expired-operation", "expired", time.get() - 1000);
            ref(expiredRef).put("expiresAt", time.get());
            assertThrows(SecurityException.class, () -> gate.capture(ROUTE, wire(expiredRef), expiredRef, () -> { }));
        }
    }

    @Test public void oldOperationReadCannotReviveAnUnseenPredecessorAfterInvalidation() throws Exception {
        RunControl control = run();
        AtomicLong time = new AtomicLong(System.currentTimeMillis());
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control, time::get)) {
            JSONObject latest = receipt("new-operation", "new", time.get());
            gate.capture(ROUTE, wire(latest), latest, () -> { });
            gate.invalidate(ROUTE);
            JSONObject unseenOld = receipt("historic-operation", "historic", time.get() - 1000);
            assertThrows(SecurityException.class, () -> gate.capture(ROUTE, wire(unseenOld), unseenOld, () -> { }));
            assertThrows(SecurityException.class, () -> gate.capture(ROUTE, wire(latest), latest, () -> { }));
            JSONObject fresh = receipt("fresh-operation", "fresh", time.incrementAndGet());
            gate.capture(ROUTE, wire(fresh), fresh, () -> { });
            deliver(gate, control, "fresh-request", fresh);
            gate.requireAction(ROUTE, ref(fresh));
        }
    }

    @Test public void unrelatedCompactedRequestDoesNotCheckARevokedDesktopRoute() throws Exception {
        RunControl control = run();
        AtomicInteger checks = new AtomicInteger();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> {
                if (checks.incrementAndGet() > 1) throw new SecurityException("Native desktop route is no longer current");
            });
            gate.beforeRequest("answer-without-desktop", new JSONArray(), control);
            assertNull(gate.afterResponse("answer-without-desktop", control));
            assertEquals(1, checks.get());
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
        }
    }

    @Test public void routesCannotBorrowEachOthersDeliveryAndRevokedOneDoesNotBlockAnother() throws Exception {
        RunControl control = run();
        AtomicBoolean currentA = new AtomicBoolean(true);
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            long now = System.currentTimeMillis();
            JSONObject a = receipt("operation-a", "observation-a", now);
            JSONObject b = receipt("operation-b", "observation-b", now);
            gate.capture(ROUTE, wire(a), a, () -> {
                if (!currentA.get()) throw new SecurityException("Route A revoked");
            });
            gate.capture("route-b", wire(b), b, () -> { });
            deliver(gate, control, "only-a", a);
            assertThrows(SecurityException.class, () -> gate.requireAction("route-b", ref(a)));
            assertThrows(SecurityException.class, () -> gate.requireAction("route-b", ref(b)));
            JSONArray both = messages(a).put(messages(b).getJSONObject(0));
            gate.beforeRequest("both", both, control);
            currentA.set(false);
            assertEquals(1, gate.afterResponse("both", control).getInt("structuresProvided"));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(a)));
            gate.requireAction("route-b", ref(b));
        }
    }

    @Test public void pauseResumeAndOwnerSteeringCannotUseAnEarlierResponse() throws Exception {
        RunControl control = run();
        AtomicLong time = new AtomicLong(System.currentTimeMillis());
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control, time::get)) {
            JSONObject receipt = receipt("operation", "observation", time.get());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            gate.beforeRequest("before-pause", messages(receipt), control);
            control.pause();
            control.resume();
            assertNull(gate.afterResponse("before-pause", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            JSONObject fresh = receipt("fresh-operation", "fresh", time.incrementAndGet());
            gate.capture(ROUTE, wire(fresh), fresh, () -> { });
            gate.beforeRequest("before-steer", messages(fresh), control);
            assertTrue(control.requestModelReplan("original-task", "original-run", 1));
            assertNull(gate.afterResponse("before-steer", control));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(fresh)));
            deliver(gate, control, "after-steer", fresh);
            assertTrue(control.requestModelReplan("original-task", "original-run", 1));
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(fresh)));
            deliver(gate, control, "latest-intent", fresh);
            gate.requireAction(ROUTE, ref(fresh));
        }
    }

    @Test public void nativeReceiptIdentityAndRepeatedReferenceContentRemainFixed() throws Exception {
        RunControl control = run();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            JSONObject foreign = new JSONObject(receipt.toString()).put("executorDeviceId", "pw1_" + "c".repeat(64));
            assertThrows(SecurityException.class, () -> gate.capture(ROUTE, wire(foreign), foreign, () -> { }));
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            JSONObject changed = new JSONObject(receipt.toString());
            changed.getJSONObject("payload").getJSONObject("state").getJSONArray("elements").getJSONObject(0).put("label", "Delete");
            assertThrows(SecurityException.class, () -> gate.capture(ROUTE, wire(changed), changed, () -> { }));
            gate.capture(ROUTE, wire(receipt), receipt, () -> { });
            deliver(gate, control, "unchanged-original", receipt);
            gate.requireAction(ROUTE, ref(receipt));
        }
    }

    @Test public void cancellationAndCloseCannotRevivePendingDelivery() throws Exception {
        RunControl control = run();
        DeviceSemanticObservations gate = new DeviceSemanticObservations(control);
        JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
        gate.capture(ROUTE, wire(receipt), receipt, () -> { });
        gate.beforeRequest("pending", messages(receipt), control);
        control.cancel();
        assertThrows(InterruptedException.class, () -> gate.afterResponse("pending", control));
        assertThrows(InterruptedException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
        gate.close();
        assertThrows(SecurityException.class, () -> gate.afterResponse("pending", control));
        assertFalse(gate.contains(ROUTE, ref(receipt)));
    }

    @Test public void actionCannotPassWhenCurrentnessCallbackPausesTheOriginalRun() throws Exception {
        RunControl control = run();
        AtomicBoolean pauseOnCheck = new AtomicBoolean();
        try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
            JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
            gate.capture(ROUTE, wire(receipt), receipt, () -> { if (pauseOnCheck.get()) control.pause(); });
            deliver(gate, control, "request", receipt);
            pauseOnCheck.set(true);
            assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
        }
    }

    @Test public void actionCannotPassWhenCurrentnessCallbackSteersOrCancelsTheOriginalRun() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            RunControl control = run();
            AtomicBoolean changeOnCheck = new AtomicBoolean();
            try (DeviceSemanticObservations gate = new DeviceSemanticObservations(control)) {
                JSONObject receipt = receipt("operation", "observation", System.currentTimeMillis());
                gate.capture(ROUTE, wire(receipt), receipt, () -> {
                    if (changeOnCheck.get()) {
                        if (cancel) control.cancel();
                        else control.requestModelReplan("original-task", "original-run", 1);
                    }
                });
                deliver(gate, control, "request", receipt);
                changeOnCheck.set(true);
                if (cancel) assertThrows(InterruptedException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
                else assertThrows(SecurityException.class, () -> gate.requireAction(ROUTE, ref(receipt)));
            }
        }
    }
}
