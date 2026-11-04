package dev.pocketengine.features.devices;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.AgentRunner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual semantic delivery gate and Runner/HTTP projection. Native PC receipts
 * are explicit fixtures; these tests do not claim mTLS, UIA or physical input. */
public final class DeviceSemanticObservationsTest {
    private static final String ROUTE = "fixture-original-route";
    private static final String DEVICE = "pw1_" + "1".repeat(64);

    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static JSONObject wire(String operation) throws Exception {
        return new JSONObject().put("operationId", operation).put("targetDeviceId", DEVICE)
                .put("deadline", System.currentTimeMillis() + 60000);
    }
    private static JSONObject observation(String id, long captured) throws Exception {
        JSONObject reference = new JSONObject().put("id", id).put("sessionId", 1).put("displayId", "primary")
                .put("windowId", 456).put("binding", "fixture-native-binding").put("driverGeneration", "a".repeat(32))
                .put("capturedAt", captured).put("expiresAt", captured + 30000);
        JSONObject state = new JSONObject().put("pid", 123).put("window_id", 456).put("snapshot_id", "s1234abcd")
                .put("window_title", "Fixture editor").put("semantic_provider", "agent-desktop")
                .put("elements_complete", true).put("elements", new JSONArray().put(new JSONObject()
                        .put("element_token", "s1234abcd:1").put("role", "Edit").put("label", "Document")
                        .put("value", "真实结构 fixture").put("enabled", true).put("actions", new JSONArray().put("set_value"))));
        return new JSONObject().put("observationMode", "semantic").put("observationRef", reference)
                .put("coordinateSpace", "uia_elements").put("state", state).put("businessResultVerified", false);
    }
    private static JSONObject receipt(String operation, JSONObject observation) throws Exception {
        return new JSONObject().put("messageType", "OperationReceipt").put("operationId", operation)
                .put("executorDeviceId", DEVICE).put("state", "succeeded").put("terminal", true)
                .put("payload", observation);
    }
    private static JSONArray messages(String role, Object content) throws Exception {
        return new JSONArray().put(new JSONObject().put("role", role).put("tool_call_id", "fixture-call")
                .put("content", content));
    }
    private static final class Fixture implements AutoCloseable {
        final RunControl control = new RunControl();
        final DeviceSemanticObservations gate = new DeviceSemanticObservations(control);
        final long base = System.currentTimeMillis() - 1000;
        boolean current = true;
        void requireCurrent() throws Exception {
            control.check();
            if (!current || control.isPaused()) throw new SecurityException("Fixture original grant/run revoked");
        }
        JSONObject capture(String operation, String id, long captured) throws Exception {
            JSONObject result = receipt(operation, observation(id, captured));
            gate.capture(ROUTE, wire(operation), result, this::requireCurrent);
            return result;
        }
        JSONObject ref(JSONObject result) throws Exception { return result.getJSONObject("payload").getJSONObject("observationRef"); }
        JSONObject deliver(String request, JSONObject result) throws Exception {
            gate.beforeRequest(request, messages("tool", result.toString()), control);
            return gate.afterResponse(request, control);
        }
        @Override public void close() { gate.close(); }
    }

    @Test public void structureMustReachCompletedOriginalModelRequestBeforeOneAction() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject result = f.capture("observe-a", "1".repeat(32), f.base);
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
            f.gate.beforeRequest("request-a", messages("tool", result.toString()), f.control);
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
            JSONObject evidence = f.gate.afterResponse("request-a", f.control);
            assertEquals("paired_device_semantic_delivery", evidence.getString("kind"));
            assertEquals(1, evidence.getInt("structuresProvided"));
            assertFalse(evidence.getBoolean("businessVerified"));
            f.gate.requireAction(ROUTE, f.ref(result));
            assertTrue("Mode recognition may remember retired refs without authorizing them", f.gate.contains(ROUTE, f.ref(result)));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
        }
    }

    @Test public void userAssistantSystemAndNestedTextCannotSpoofToolDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject result = f.capture("observe-a", "1".repeat(32), f.base);
            for (String role : new String[] {"user", "assistant", "system"}) {
                f.gate.beforeRequest(role, messages(role, result.toString()), f.control);
                assertNull(f.gate.afterResponse(role, f.control));
                assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
            }
            f.gate.beforeRequest("nested", messages("tool", new JSONObject().put("untrusted", result).toString()), f.control);
            assertNull(f.gate.afterResponse("nested", f.control));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
        }
    }

    @Test public void wrappedToolTextPartsAndPostActionObservationUseSameExactGate() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject observed = observation("1".repeat(32), f.base);
            JSONObject action = receipt("action-a", new JSONObject().put("desktopReceipt", new JSONObject().put("afterObservation", observed)));
            JSONObject wrapped = new JSONObject().put("ok", true).put("deviceReceipt", action);
            f.gate.capture(ROUTE, wire("action-a"), wrapped, f::requireCurrent);
            JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", wrapped.toString()));
            f.gate.beforeRequest("parts", messages("tool", parts), f.control);
            assertEquals(1, f.gate.afterResponse("parts", f.control).getInt("structuresProvided"));
            f.gate.requireAction(ROUTE, observed.getJSONObject("observationRef"));
        }
    }

    @Test public void clippedChangedWrongExecutorOrWrongOperationReceiptNeverGrantsAction() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject result = f.capture("observe-a", "1".repeat(32), f.base);
            JSONObject clipped = copy(result);
            clipped.getJSONObject("payload").getJSONObject("state").put("elements", new JSONArray()).put("adapter_truncated", true);
            JSONObject changedRef = copy(result);
            changedRef.getJSONObject("payload").getJSONObject("observationRef").put("windowId", 999);
            JSONObject changedMode = copy(result);
            changedMode.getJSONObject("payload").put("observationMode", "hybrid");
            for (JSONObject bad : new JSONObject[] {clipped, changedRef, changedMode,
                    copy(result).put("executorDeviceId", "pw1_" + "2".repeat(64)), copy(result).put("operationId", "another-operation")}) {
                f.gate.beforeRequest("bad", messages("tool", bad.toString()), f.control);
                assertNull(f.gate.afterResponse("bad", f.control));
                assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
            }
            assertNotNull("A complete exact copy remains deliverable", f.deliver("exact", copy(result)));
            f.gate.requireAction(ROUTE, f.ref(result));
        }
    }

    @Test public void abandonedWrongRunnerAndUnknownRequestCannotReportDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject result = f.capture("observe-a", "1".repeat(32), f.base);
            assertThrows(SecurityException.class, () -> f.gate.beforeRequest("other", messages("tool", result.toString()), new RunControl()));
            f.gate.beforeRequest("pending", messages("tool", result.toString()), f.control);
            assertThrows(SecurityException.class, () -> f.gate.afterResponse("pending", new RunControl()));
            f.gate.abandoned("pending");
            assertNull(f.gate.afterResponse("pending", f.control));
            assertNull(f.gate.afterResponse("unknown-request", f.control));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
            assertNotNull(f.deliver("new-real-request", result));
            f.gate.requireAction(ROUTE, f.ref(result));
        }
    }

    @Test public void retiredReferenceCannotBorrowLatestDeliveredObservation() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject first = f.capture("observe-a", "1".repeat(32), f.base);
            f.deliver("request-a", first); f.gate.requireAction(ROUTE, f.ref(first));
            JSONObject latest = f.capture("observe-b", "2".repeat(32), f.base + 1);
            f.deliver("request-b", latest);
            assertTrue(f.gate.contains(ROUTE, f.ref(first)));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(first)));
            f.gate.requireAction(ROUTE, f.ref(latest));
        }
    }

    @Test public void consumedUnknownHistoryAndPreviouslyUncachedOlderHistoryCannotReauthorize() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject latest = f.capture("observe-new", "2".repeat(32), f.base + 10);
            f.deliver("request-new", latest); f.gate.requireAction(ROUTE, f.ref(latest));
            JSONObject unknown = copy(latest).put("state", "unknown").put("terminal", false);
            assertThrows(SecurityException.class, () -> f.gate.capture(ROUTE, wire("observe-new"), unknown, f::requireCurrent));
            JSONObject uncachedHistory = receipt("observe-old", observation("1".repeat(32), f.base));
            assertThrows(SecurityException.class, () -> f.gate.capture(ROUTE, wire("observe-old"), uncachedHistory, f::requireCurrent));
            f.gate.beforeRequest("history", messages("tool", unknown.toString()), f.control);
            assertNull(f.gate.afterResponse("history", f.control));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(latest)));
        }
    }

    @Test public void invalidatedOrReplacedObservationCannotBeDeliveredByLateResponse() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject first = f.capture("observe-a", "1".repeat(32), f.base);
            f.gate.beforeRequest("pending-a", messages("tool", first.toString()), f.control);
            JSONObject next = f.capture("observe-b", "2".repeat(32), f.base + 1);
            assertThrows(SecurityException.class, () -> f.gate.afterResponse("pending-a", f.control));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(next)));
            f.deliver("next", next); f.gate.invalidate(ROUTE);
            assertThrows(SecurityException.class, () -> f.gate.capture(ROUTE, wire("observe-b"), next, f::requireCurrent));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(next)));
        }
    }

    @Test public void nativeExpiryWireDeadlineAndThirtySecondMaximumAllNarrowAuthority() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject expired = receipt("observe-expired", observation("1".repeat(32), f.base));
            expired.getJSONObject("payload").getJSONObject("observationRef").put("expiresAt", System.currentTimeMillis() - 1);
            assertThrows(SecurityException.class, () -> f.gate.capture(ROUTE, wire("observe-expired"), expired, f::requireCurrent));
            JSONObject expiredWire = wire("observe-deadline").put("deadline", System.currentTimeMillis() - 1);
            JSONObject validRef = receipt("observe-deadline", observation("2".repeat(32), f.base));
            assertThrows(SecurityException.class, () -> f.gate.capture(ROUTE, expiredWire, validRef, f::requireCurrent));
            JSONObject old = receipt("observe-old", observation("3".repeat(32), System.currentTimeMillis() - 31000));
            old.getJSONObject("payload").getJSONObject("observationRef").put("expiresAt", System.currentTimeMillis() + 60000);
            assertThrows(SecurityException.class, () -> f.gate.capture(ROUTE, wire("observe-old"), old, f::requireCurrent));
        }
    }

    @Test public void liveGrantRevocationBetweenRequestAndResponseOrActionIsRechecked() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject result = f.capture("observe-a", "1".repeat(32), f.base);
            f.gate.beforeRequest("pending", messages("tool", result.toString()), f.control);
            f.current = false;
            assertThrows(SecurityException.class, () -> f.gate.afterResponse("pending", f.control));
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
        }
        try (Fixture f = new Fixture()) {
            JSONObject result = f.capture("observe-a", "1".repeat(32), f.base);
            f.deliver("delivered", result); f.control.pause();
            assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, f.ref(result)));
        }
    }

    @Test public void actualRunnerSendsStructureThroughHttpBeforeDispatchWithImagesDisabled() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject result = receipt("native-observe", observation("1".repeat(32), f.base));
            JSONObject reference = f.ref(result);
            AtomicInteger requests = new AtomicInteger(), inputs = new AtomicInteger(), completedDeliveries = new AtomicInteger();
            AtomicReference<Throwable> httpFailure = new AtomicReference<>();
            List<String> bodies = Collections.synchronizedList(new ArrayList<>());
            CapabilityPort port = new CapabilityPort() {
                public JSONArray tools() throws Exception {
                    JSONArray tools = new JSONArray();
                    for (String name : new String[] {"fixture_semantic_observe", "fixture_semantic_act"}) {
                        JSONObject parameters = new JSONObject().put("type", "object");
                        if (name.endsWith("act")) parameters.put("properties", new JSONObject().put("observationRef", new JSONObject().put("type", "object")));
                        tools.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("parameters", parameters)));
                    }
                    return tools;
                }
                public boolean supports(String name) { return name.equals("fixture_semantic_observe") || name.equals("fixture_semantic_act"); }
                public boolean sideEffecting(String name) { return name.equals("fixture_semantic_act"); }
                public void beforeModelRequest(String request, JSONArray messages, RunControl control) throws Exception { f.gate.beforeRequest(request, messages, control); }
                public JSONObject afterModelResponse(String request, String answer, RunControl control) throws Exception {
                    JSONObject receipt = f.gate.afterResponse(request, control);
                    if (receipt != null) completedDeliveries.incrementAndGet();
                    return receipt;
                }
                public void modelRequestAbandoned(String request) { f.gate.abandoned(request); }
                public JSONObject beforeTool(String name, JSONObject args, RunControl control) throws Exception {
                    if (name.equals("fixture_semantic_act")) f.gate.requireAction(ROUTE, args.getJSONObject("observationRef"));
                    return null;
                }
                public JSONObject execute(String name, JSONObject args, RunControl control) throws Exception {
                    if (name.equals("fixture_semantic_observe")) {
                        f.gate.capture(ROUTE, wire("native-observe"), result, f::requireCurrent);
                        return new JSONObject().put("ok", true).put("deviceReceipt", result);
                    }
                    assertEquals("Action must follow actual completed model delivery", 1, completedDeliveries.get());
                    inputs.incrementAndGet();
                    return new JSONObject().put("ok", true).put("status", "completed").put("businessResultVerified", false);
                }
            };
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                try {
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    int round = requests.incrementAndGet();
                    JSONObject message = new JSONObject().put("role", "assistant");
                    if (round <= 2) {
                        JSONObject args = round == 1 ? new JSONObject() : new JSONObject().put("observationRef", reference);
                        String name = round == 1 ? "fixture_semantic_observe" : "fixture_semantic_act";
                        message.put("content", JSONObject.NULL).put("tool_calls", new JSONArray().put(new JSONObject()
                                .put("id", "fixture-call-" + round).put("type", "function")
                                .put("function", new JSONObject().put("name", name).put("arguments", args.toString()))));
                    } else message.put("content", "Controlled semantic delivery finished");
                    byte[] bytes = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", message)
                            .put("finish_reason", round <= 2 ? "tool_calls" : "stop"))).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable failure) { httpFailure.set(failure); }
                finally { exchange.close(); }
            });
            http.start();
            try {
                ModelConfig config = new ModelConfig("http://127.0.0.1:" + http.getAddress().getPort(), "fixture-only-model-key", "fixture-model", false, 5, 10);
                assertFalse(config.sendScreenshots); assertFalse(config.supportsImageInput);
                var outcome = new AgentRunner(null, port).run("semantic-delivery-fixture", "读取结构并执行一次获准的 fixture 操作", config, f.control, event -> { });
                assertNull(httpFailure.get());
                assertEquals(outcome.summary, 3, requests.get());
                assertEquals(outcome.summary, 1, inputs.get());
                assertEquals(1, completedDeliveries.get());
                JSONObject actualSecondRequest = new JSONObject(bodies.get(1));
                JSONArray projected = actualSecondRequest.getJSONArray("messages");
                boolean delivered = false;
                for (int i = 0; i < projected.length(); i++) {
                    JSONObject row = projected.getJSONObject(i);
                    if ("tool".equals(row.optString("role")) && row.optString("content").contains("s1234abcd:1")) delivered = true;
                }
                assertTrue("Actual outgoing HTTP must contain the exact tool tree", delivered);
                assertFalse(bodies.toString().contains("image_url"));
                assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, reference));
            } finally { http.stop(0); }
        }
    }
}
