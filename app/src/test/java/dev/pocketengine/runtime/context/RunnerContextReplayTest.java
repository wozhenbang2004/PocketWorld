package dev.pocketengine.runtime.context;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.pocketengine.runtime.context.ContextWindow.Eviction.COMPLETED_TOOL_BATCH;
import static org.junit.Assert.*;

/** Actual current Runner/Gateway/provider code with local HTTP and a fixture RunJournal, not SQLite. */
public final class RunnerContextReplayTest {
    @Test public void actualRunnerHistoryFailsTheSameWireGateBeforeProjectionAndPassesAfter() throws Exception {
        try (WireFixture wire = new WireFixture()) {
            JournalFixture journal = new JournalFixture();
            AtomicInteger executions = new AtomicInteger();
            CapabilityPort port = port((name, args, control) -> {
                control.check();
                int n = executions.incrementAndGet();
                if (n == 2) journal.feedback = "只读取已有内容，不要发送、覆盖或重复执行历史操作。";
                return new JSONObject().put("ok", true).put("version", n)
                        .put("content", "fixture-output-" + n + "\n" + "x".repeat(16000));
            });
            wire.toolRounds = 4;
            ModelConfig config = config(wire.url());
            RunOutcome result = new AgentRunner(null, port).run("fixture-context", "读取四份已有内容并保留机主的新要求。",
                    config, new RunControl(), event -> { }, journal);
            assertEquals("needs_confirmation", result.status);
            assertEquals(4, executions.get());
            assertEquals(5, wire.requests.size());
            JSONObject actualRequest = wire.requests.get(4);
            JSONArray history = actualRequest.getJSONArray("messages"), tools = actualRequest.getJSONArray("tools");
            String unchanged = history.toString();
            Map<Integer, ContextWindow.Eviction> eligible = batchStarts(history);
            ContextWindow.Budget budget = new ContextWindow.Budget(24000, 1000, 1000, 24000, 256, 0);
            ContextWindow.Plan projection = ContextWindow.plan(history, tools, budget,
                    new ContextWindow.Retention(eligible, Set.of()));
            assertTrue(projection.before.estimatedInputTokens() > budget.inputLimit());
            assertTrue(projection.before.canonicalUtf8Bytes() > budget.maxCanonicalBytes);
            assertTrue(projection.canSend());
            assertEquals(6, projection.removed.size());
            assertTrue(projection.messages().toString().contains("不要发送"));
            assertTrue(projection.messages().toString().contains("fixture-output-4"));
            assertEquals(unchanged, history.toString());

            // A controlled, real HTTP byte gate gives a before/after failure comparison.
            // It is not an actual model's context limit or tokenizer measurement.
            wire.toolRounds = 0;
            wire.byteCeiling = 24000;
            try {
                ModelGateway.complete(config, history, tools, new RunControl());
                fail("Unbounded current history should fail the controlled HTTP gate");
            } catch (ModelGateway.GatewayException expected) {
                assertEquals(1, wire.rejected.get());
            }
            JSONObject accepted = ModelGateway.complete(config, projection.messages(), tools, new RunControl());
            assertEquals("fixture answer", accepted.getString("content"));
            assertEquals(4, executions.get()); // Projection/transport never replayed any tool.
            assertFalse(accepted.has("usage")); // Current Gateway exposes no reported-usage evidence.

            JSONArray nativeSizes = new JSONArray();
            for (String provider : List.of("openai_chat", "openai_responses", "anthropic", "gemini")) {
                ModelConfig nativeConfig = new ModelConfig("https://fixture.invalid/v1", "", "fixture", false, 6, 10, provider, 1000);
                JSONObject body = ProviderAdapters.selected(provider).request(nativeConfig, projection.messages(), tools);
                assertTrue(body.toString().contains("fixture-output-4"));
                assertFalse(body.toString().contains("fixture-output-1"));
                nativeSizes.put(new JSONObject().put("provider", provider)
                        .put("wireUtf8Bytes", body.toString().getBytes(StandardCharsets.UTF_8).length));
            }
            save("runner-request-before.json", actualRequest);
            save("runner-checkpoint.json", journal.saved);
            save("runner-request-after.json", new JSONObject().put("messages", projection.messages()).put("tools", tools));
            save("runner-replay-result.json", projection.diagnostics().put("actualRunnerExecutions", executions.get())
                    .put("rawGatewayHttp413", wire.rejected.get()).put("projectedGatewayAccepted", true)
                    .put("nativeProviderSerialization", nativeSizes).put("sqliteValidated", false)
                    .put("realModelValidated", false));
        }
    }

    @Test public void currentRunnerRecoveryMarkerAndOwnerReconciliationSurviveWhileOldReadGroupGoes() throws Exception {
        try (WireFixture wire = new WireFixture()) {
            JournalFixture journal = new JournalFixture();
            JSONArray saved = ContextWindowTest.base();
            ContextWindowTest.batch(saved, "old-known-read", 12000, "ok");
            saved.put(new JSONObject().put("role", "assistant").put("tool_calls", new JSONArray()
                    .put(ContextWindowTest.call("historical-no-reply"))));
            journal.saved = new JSONObject().put("messages", saved).put("steps", 1);
            journal.feedback = "机主已核对之前操作发生；不要重发。";
            journal.operationResults = new JSONArray().put(new JSONObject().put("operationId", "fixture-operation")
                    .put("state", "resolved").put("receipt", new JSONObject().put("source", "owner_reconciliation")
                            .put("outcome", "occurred")));
            AtomicInteger effects = new AtomicInteger();
            CapabilityPort port = port((name, args, control) -> { effects.incrementAndGet(); throw new AssertionError("No replay"); });
            RunOutcome result = new AgentRunner(null, port).run("fixture-recovery", "Owner goal", config(wire.url()),
                    new RunControl(), event -> { }, journal);
            assertEquals("needs_confirmation", result.status);
            assertEquals(0, effects.get());
            JSONArray history = wire.requests.get(0).getJSONArray("messages");
            assertTrue(history.toString().contains("not_replayed"));
            assertTrue(history.toString().contains("owner_reconciliation"));
            ContextWindow.Plan projection = ContextWindow.plan(history, null, ContextWindowTest.bytes(6000),
                    new ContextWindow.Retention(batchStarts(history), Set.of()));
            assertTrue(projection.canSend());
            assertEquals(2, projection.removed.size());
            String after = projection.messages().toString();
            assertFalse(after.contains("old-known-read"));
            assertTrue(after.contains("historical-no-reply"));
            assertTrue(after.contains("not_replayed"));
            assertTrue(after.contains("owner_reconciliation"));
            assertTrue(after.contains("不要重发"));
            assertTrue(after.contains("历史观察与节点引用全部失效"));
            save("runner-recovery-before.json", new JSONObject().put("messages", history));
            save("runner-recovery-after.json", new JSONObject().put("messages", projection.messages()));
            save("runner-recovery-result.json", projection.diagnostics().put("toolExecutions", effects.get())
                    .put("journalType", "fixture_RunJournal_not_TaskStore_SQLite"));
        }
    }

    private interface Execute { JSONObject run(String name, JSONObject args, RunControl control) throws Exception; }

    private static CapabilityPort port(Execute execute) {
        return new CapabilityPort() {
            public JSONArray tools() throws Exception {
                return new JSONArray().put(new JSONObject().put("type", "function").put("function", new JSONObject()
                        .put("name", "workspace_read").put("parameters", new JSONObject().put("type", "object"))));
            }
            public boolean supports(String name) { return name.equals("workspace_read"); }
            public boolean sideEffecting(String name) { return false; }
            public JSONObject execute(String name, JSONObject args, RunControl control) throws Exception { return execute.run(name, args, control); }
        };
    }

    private static Map<Integer, ContextWindow.Eviction> batchStarts(JSONArray history) throws Exception {
        Map<Integer, ContextWindow.Eviction> starts = new LinkedHashMap<>();
        for (int i = 0; i < history.length(); i++) if (history.getJSONObject(i).has("tool_calls")) starts.put(i, COMPLETED_TOOL_BATCH);
        return starts;
    }

    private static ModelConfig config(String url) { return new ModelConfig(url, "", "fixture", false, 6, 10); }

    private static void save(String filename, JSONObject body) throws Exception {
        String directory = System.getProperty("pocket.w14.evidence");
        if (directory == null) return;
        Files.writeString(Path.of(directory).resolve(filename), body.toString(2), StandardCharsets.UTF_8);
    }

    private static final class JournalFixture implements RunJournal {
        JSONObject saved = new JSONObject();
        String feedback = "";
        JSONArray operationResults = new JSONArray();
        public JSONObject restore() throws Exception { return new JSONObject(saved.toString()).put("feedback", feedback)
                .put("operationResults", operationResults); }
        public void checkpoint(JSONArray messages, int steps, JSONObject observation) throws Exception {
            saved = new JSONObject().put("messages", new JSONArray(messages.toString())).put("steps", steps)
                    .put("observation", new JSONObject(observation.toString()));
        }
        public String beginOperation(String tool, JSONObject args) { throw new AssertionError("Read-only fixture"); }
        public void finishOperation(String id, JSONObject receipt) { throw new AssertionError("Read-only fixture"); }
    }

    private static final class WireFixture implements AutoCloseable {
        final HttpServer server;
        final List<JSONObject> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger rejected = new AtomicInteger();
        volatile int toolRounds;
        volatile int byteCeiling = Integer.MAX_VALUE;

        WireFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    byte[] payload = exchange.getRequestBody().readAllBytes();
                    JSONObject request = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                    requests.add(request);
                    int status = payload.length > byteCeiling ? 413 : 200;
                    if (status == 413) rejected.incrementAndGet();
                    JSONObject message = new JSONObject().put("role", "assistant").put("content", "fixture answer");
                    if (requests.size() <= toolRounds) message.put("tool_calls", new JSONArray()
                            .put(ContextWindowTest.call("fixture-read-" + requests.size())));
                    JSONObject response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                            .put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop").put("message", message)))
                            .put("usage", new JSONObject().put("prompt_tokens", 17).put("completion_tokens", 3));
                    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception failure) {
                    throw new IOException(failure);
                } finally { exchange.close(); }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        public void close() { server.stop(0); }
    }
}
