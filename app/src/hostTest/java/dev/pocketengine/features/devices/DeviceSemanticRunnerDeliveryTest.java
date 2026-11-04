package dev.pocketengine.features.devices;

import dev.pocketengine.testutil.HttpServer;
import android.app.Application;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
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
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class DeviceSemanticRunnerDeliveryTest {
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
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
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
                for (String body : bodies) {
                    JSONArray actualMessages = new JSONObject(body).getJSONArray("messages");
                    for (int i = 0; i < actualMessages.length(); i++) {
                        JSONArray content = actualMessages.getJSONObject(i).optJSONArray("content");
                        if (content == null) continue;
                        for (int j = 0; j < content.length(); j++) {
                            JSONObject part = content.getJSONObject(j);
                            assertFalse("Semantic-only requests cannot carry an actual image part",
                                    "image_url".equals(part.optString("type")) || part.has("image_url"));
                        }
                    }
                }
                assertThrows(SecurityException.class, () -> f.gate.requireAction(ROUTE, reference));
            } finally { http.stop(0); }
        }
    }
}
