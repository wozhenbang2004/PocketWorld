package dev.pocketengine.features.memory;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/**
 * Original Runner/Gateway/HostToolSupervisor/MemoryModule/TaskStore path, with real SQLite
 * conversation originals in separate sessions. Only the model HTTP response is scripted.
 * This measures native wiring and fencing, not a live model's rewrite or answer quality,
 * a device journey, or proof that providing a memory caused a successful operation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryRecallAgentJourneyNativeTest {
    private static final long EVENT_AT = 1_700_000_000_000L;

    @Test public void crossSessionSemanticToolAndDuplicateReadReachRealNativeReceipts() throws Exception {
        String query = "cheap lodging near terminal";
        String original = "An affordable airport guesthouse accepts late arrivals.";
        try (Fixture f = new Fixture(query)) {
            Original target = f.remember(original, "place:guesthouse", "Guesthouse", "accommodation", EVENT_AT);
            Original distractor = f.remember("The violet workshop closes every Tuesday.",
                    "place:workshop", "Workshop", "closing", EVENT_AT + 1);
            assertNotEquals(target.conversation, distractor.conversation);
            f.assertOriginal(target);
            f.assertOriginal(distractor);
            assertEquals(0, f.memory.contextSnapshot(scope(), query, 4, 9000).getJSONArray("records").length());
            byte[] before = Files.readAllBytes(f.memoryFile.toPath());

            JSONObject arguments = new JSONObject().put("query", query)
                    .put("queries", new JSONArray().put("affordable accommodation airport"))
                    .put("constraints", new JSONObject().put("maxHops", 0)
                            .put("layers", new JSONArray().put("fact"))
                            .put("sourceKinds", new JSONArray().put("message")))
                    .put("limit", 4);
            try (Gateway gateway = new Gateway(f, (request, body) -> request == 1
                    ? calls(call("first-read", arguments), call("duplicate-read", arguments))
                    : answer("The source is relevant; any booking still needs the owner's decision."))) {
                RunOutcome outcome = f.run(gateway);
                gateway.assertHealthy();
                assertEquals("needs_confirmation", outcome.status);
                assertEquals(2, gateway.requests.size());
                assertFalse(gateway.requests.get(0).toString().contains(original));
                List<JSONObject> results = toolResults(gateway.requests.get(1));
                assertEquals(2, results.size());
                String firstDigest = null;
                for (JSONObject result : results) {
                    assertEquals("current", result.getString("status"));
                    assertEquals("prepared", result.getString("phase"));
                    assertTrue(result.getBoolean("ok"));
                    assertFalse(result.getBoolean("modelUseVerified"));
                    assertFalse(result.getBoolean("executionAuthorized"));
                    assertEquals(1, result.getJSONArray("records").length());
                    JSONObject record = result.getJSONArray("records").getJSONObject(0);
                    assertEquals(target.record.getString("id"), record.getString("id"));
                    assertEquals(target.record.getLong("revision"), record.getLong("revision"));
                    assertEquals(target.ref.sourceId, record.getJSONArray("sourceRefs").getJSONObject(0).getString("sourceId"));
                    assertEquals(target.ref.revision, record.getJSONArray("sourceRefs").getJSONObject(0).getLong("revision"));
                    assertTrue(result.getString("context").contains(original));
                    if (firstDigest == null) firstDigest = result.getString("contextDigest");
                    else assertEquals(firstDigest, result.getString("contextDigest"));
                }
                JSONObject task = f.tasks.get(f.id, false);
                // The original Runner journals read-only results as tool events/checkpoints;
                // it deliberately does not reserve an effect operation for a search.
                assertEquals(0, operations(task, "memory_search").size());
                assertEquals(2, events(task, "memory_tool_reference_prepared").size());
                List<JSONObject> toolEvents = events(task, "tool").stream().filter(event -> event.has("receipt")).toList();
                assertEquals(2, toolEvents.size());
                for (JSONObject event : toolEvents) {
                    assertEquals("memory_search", event.getString("tool"));
                    assertEquals("completed", event.getString("status"));
                    assertEquals(target.record.getString("id"), event.getJSONObject("receipt")
                            .getJSONArray("records").getJSONObject(0).getString("id"));
                }
                // Automatic initial-goal recall is empty. Its PROVIDED receipt must not be
                // mislabelled as proof that the semantic tool selection was actually applied.
                List<JSONObject> referenceRows = operations(task, TaskMemoryJournal.TOOL);
                assertEquals(2, referenceRows.size());
                for (JSONObject reference : referenceRows) {
                    assertEquals(TaskMemoryJournal.PROVIDED, reference.getString("state"));
                    assertEquals(0, reference.getJSONObject("arguments").getJSONArray("records").length());
                    assertFalse(reference.getJSONObject("receipt").getBoolean("modelUseVerified"));
                    assertFalse(reference.getJSONObject("receipt").getBoolean("executionAuthorized"));
                }
                assertEquals(2, f.tasks.inferenceUsage(f.id).getInt("attempts"));
                assertEquals(2, f.tasks.inferenceUsage(f.id).getInt("completed"));
                assertArrayEquals(before, Files.readAllBytes(f.memoryFile.toPath()));
                f.assertOriginal(target);
            }
        }
    }

    @Test public void sourceRevisionChangingDuringHttpDiscardsOldCallbackBeforeToolDispatch() throws Exception {
        String original = "The cobalt archive entry opens using an amber latch.";
        try (Fixture f = new Fixture("cobalt archive entry")) {
            Original source = f.remember(original, "place:archive", "Archive", "entry", EVENT_AT);
            JSONObject args = new JSONObject().put("query", "cobalt archive entry")
                    .put("queries", new JSONArray().put("archive entrance"))
                    .put("constraints", new JSONObject().put("maxHops", 0));
            try (Gateway gateway = new Gateway(f, (request, body) -> {
                if (request == 1) {
                    assertTrue(body.toString().contains(original));
                    f.reviseOriginal(source, "The source was corrected; the prior entry instructions were withdrawn.");
                    return calls(call("stale-source-decision", args));
                }
                return answer("The older source changed. The current source requires a fresh review.");
            })) {
                RunOutcome outcome = f.run(gateway);
                gateway.assertHealthy();
                assertEquals("needs_confirmation", outcome.status);
                assertEquals(2, gateway.requests.size());
                assertFalse(gateway.requests.get(1).toString().contains(original));
                assertEquals(1, f.events.stream().filter(e -> "capability_changed".equals(e.optString("type"))).count());
                JSONObject task = f.tasks.get(f.id, false);
                assertEquals(0, operations(task, "memory_search").size());
                assertEquals(0, events(task, "memory_tool_reference_prepared").size());
                boolean abandonedOld = false;
                for (JSONObject reference : operations(task, TaskMemoryJournal.TOOL)) {
                    JSONArray rows = reference.getJSONObject("arguments").getJSONArray("records");
                    if (rows.length() > 0) {
                        assertEquals(TaskMemoryJournal.UNCONFIRMED, reference.getString("state"));
                        assertEquals(source.record.getString("id"), rows.getJSONObject(0).getString("id"));
                        abandonedOld = true;
                    }
                }
                assertTrue(abandonedOld);
                assertEquals(2, f.tasks.inferenceUsage(f.id).getInt("attempts"));
                assertFalse(f.control.isCancelled());
                assertEquals(2, f.originals.resolveMetadata(source.ref.sourceId).state.ref.revision);
            }
        }
    }

    @Test public void ownerCancellationDuringHttpCannotTurnLateRecallIntoProvidedOrAppliedEvidence() throws Exception {
        try (Fixture f = new Fixture("silver packing instruction")) {
            f.remember("The silver packing instruction requires a padded box.",
                    "method:packing", "Packing", "instruction", EVENT_AT);
            try (Gateway gateway = new Gateway(f, (request, body) -> {
                f.tasks.requestCancel(f.id, f.run);
                f.control.cancel();
                return calls(call("late-after-cancel", new JSONObject().put("query", "silver packing instruction")
                        .put("queries", new JSONArray().put("protective packaging"))
                        .put("constraints", new JSONObject().put("maxHops", 0))));
            })) {
                RunOutcome outcome = f.run(gateway);
                gateway.assertHealthy();
                assertEquals("cancelled", outcome.status);
                assertEquals(1, gateway.requests.size());
                JSONObject task = f.tasks.get(f.id, false);
                assertTrue(task.getBoolean("cancelRequested"));
                assertEquals(0, operations(task, "memory_search").size());
                assertEquals(0, events(task, "memory_tool_reference_prepared").size());
                for (JSONObject reference : operations(task, TaskMemoryJournal.TOOL))
                    assertNotEquals(TaskMemoryJournal.PROVIDED, reference.getString("state"));
                assertEquals(1, f.tasks.inferenceUsage(f.id).getInt("attempts"));
                assertTrue(f.control.isCancelled());
            }
        }
    }

    @Test public void semanticToolSourceChangingAfterSendCannotResurrectFromToolHistory() throws Exception {
        String query = "low cost overnight stay beside runway";
        String original = "An affordable airport guesthouse accepts late arrivals.";
        try (Fixture f = new Fixture(query)) {
            Original source = f.remember(original, "place:guesthouse", "Guesthouse", "accommodation", EVENT_AT);
            assertEquals(0, f.memory.contextSnapshot(scope(), query, 4, 9000).getJSONArray("records").length());
            JSONObject arguments = new JSONObject().put("query", query)
                    .put("queries", new JSONArray().put("affordable accommodation airport"))
                    .put("constraints", new JSONObject().put("maxHops", 0));
            try (Gateway gateway = new Gateway(f, (request, body) -> {
                if (request == 1) return calls(call("initial-semantic-read", arguments));
                if (request == 2) {
                    assertTrue(body.toString().contains(original));
                    f.reviseOriginal(source, "The previously recorded accommodation was withdrawn.");
                    return calls(call("stale-semantic-follow-up", arguments));
                }
                assertFalse(body.toString().contains(original));
                return answer("The source changed; review the current original before continuing.");
            })) {
                RunOutcome outcome = f.run(gateway);
                gateway.assertHealthy();
                assertTrue("source change must pause or continue with a freshly checked context",
                        outcome.status.equals("awaiting_user") || outcome.status.equals("needs_confirmation"));
                assertTrue(gateway.requests.size() >= 2 && gateway.requests.size() <= 3);
                for (int i = 2; i < gateway.requests.size(); i++)
                    assertFalse(gateway.requests.get(i).toString().contains(original));
                JSONObject task = f.tasks.get(f.id, false);
                assertEquals(1, events(task, "memory_tool_reference_prepared").size());
                List<JSONObject> resultEvents = events(task, "tool").stream().filter(event -> event.has("receipt")).toList();
                assertEquals(1, resultEvents.size());
                assertEquals(source.record.getString("id"), resultEvents.get(0).getJSONObject("receipt")
                        .getJSONArray("records").getJSONObject(0).getString("id"));
                assertFalse(resultEvents.get(0).getJSONObject("receipt").getBoolean("modelUseVerified"));
                // An attempt already admitted by the original meter is not refunded if a
                // later source fence blocks its physical send; every observed POST is counted.
                int attempts = f.tasks.inferenceUsage(f.id).getInt("attempts");
                assertTrue(attempts >= gateway.requests.size() && attempts <= 3);
                assertEquals(2, f.originals.resolveMetadata(source.ref.sourceId).state.ref.revision);
                assertFalse(f.control.isCancelled());
            }
        }
    }

    private static final class Original {
        final String conversation, text;
        final SourceRef ref;
        final JSONObject record;
        Original(String conversation, String text, SourceRef ref, JSONObject record) {
            this.conversation = conversation; this.text = text; this.ref = ref; this.record = record;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = RuntimeEnvironment.getApplication();
        final TaskStore tasks;
        final SQLiteDatabase database;
        final ConversationRepository originals;
        final FeatureHost host;
        final MemoryModule memory;
        final File memoryFile;
        final String id, run, goal;
        final int epoch;
        final RunControl control = new RunControl();
        final List<JSONObject> events = new CopyOnWriteArrayList<>();

        Fixture(String goal) throws Exception {
            this.goal = goal;
            context.deleteDatabase("pocket-world.db");
            Path memoryDirectory = new File(context.getFilesDir(), "memory").toPath();
            if (Files.exists(memoryDirectory)) try (var paths = Files.walk(memoryDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            tasks = new TaskStore(context);
            var field = TaskStore.class.getDeclaredField("db");
            field.setAccessible(true);
            database = (SQLiteDatabase) field.get(tasks);
            originals = tasks.conversationRepository();
            originals.initializeEmpty();
            host = new FeatureHost(event -> {}, tasks, () -> false);
            memory = new MemoryModule(new FeatureContext(context, null, null, event -> {}), (scope, kind, sourceId) -> {
                if (!"owner".equals(scope.ownerId) || !"private".equals(scope.spaceId)) return null;
                if ("message".equals(kind)) return originals.resolveMetadata(sourceId).state;
                return tasks.resolveMemorySource(scope, kind, sourceId);
            });
            memoryFile = new File(context.getFilesDir(), "memory/records.json");
            host.register(memory);
            JSONObject attempt = tasks.startAttempt(tasks.create(goal, "main_screen").getString("id"), "main_screen", 8);
            id = attempt.getString("id"); run = attempt.getString("runId"); epoch = attempt.getInt("coordinatorEpoch");
            control.bindHostRun(id, run, epoch);
            tasks.authorizeTools(id, run, new JSONArray().put("memory_search").put("memory_recall"));
        }

        Original remember(String text, String entity, String label, String predicate, long at) throws Exception {
            String conversation = UUID.randomUUID().toString();
            SourceRef ref = new SourceRef("message", UUID.randomUUID().toString(), 1, "owner", "private");
            originals.transaction(() -> {
                originals.saveConversation(new JSONObject().put("conversationId", conversation)
                        .put("title", "Synthetic prior session").put("createdAt", at).put("updatedAt", at));
                originals.saveSource(ref.toJson().put("conversationId", conversation).put("role", "user").put("deleted", false));
                originals.saveMessage(conversation, new JSONObject().put("id", ref.sourceId).put("revision", 1)
                        .put("role", "user").put("content", text).put("at", at));
                return null;
            });
            JSONObject assertion = new JSONObject().put("version", 1).put("kind", "told")
                    .put("subjectId", entity).put("subjectType", "entity").put("subjectLabel", label)
                    .put("predicate", predicate).put("object", text).put("eventTime", at).put("assertedAt", at)
                    .put("evidenceSpans", new JSONArray().put(new JSONObject().put("sourceRef", ref.toJson())
                            .put("offset", 0).put("chars", text.length()).put("sha256", sha256(text))));
            JSONObject record = memory.handle("importFromSources", new JSONObject().put("scope", scope())
                    .put("record", new JSONObject().put("text", text).put("layer", "fact")
                            .put("sourceRefs", new JSONArray().put(ref.toJson())).put("assertion", assertion))).getJSONObject("record");
            return new Original(conversation, text, ref, record);
        }

        void assertOriginal(Original original) throws Exception {
            JSONObject stored = originals.read(() -> originals.message(original.conversation, original.ref.sourceId));
            assertEquals(original.ref.revision, stored.getLong("revision"));
            JSONObject span = original.record.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0);
            assertEquals(original.ref, SourceRef.fromJson(span.getJSONObject("sourceRef")));
            String text = stored.getString("content");
            assertEquals(original.text, text);
            assertEquals(span.getString("sha256"), sha256(text.substring(span.getInt("offset"),
                    span.getInt("offset") + span.getInt("chars"))));
        }

        void reviseOriginal(Original original, String revised) throws Exception {
            originals.transaction(() -> {
                SourceRef next = new SourceRef("message", original.ref.sourceId, 2, "owner", "private");
                originals.saveSource(next.toJson().put("conversationId", original.conversation)
                        .put("role", "user").put("deleted", false));
                originals.saveMessage(original.conversation, new JSONObject().put("id", original.ref.sourceId)
                        .put("revision", 2).put("role", "user").put("content", revised).put("at", EVENT_AT + 100));
                return null;
            });
            // No derived-index notification: original live revision checking must win even
            // when invalidation callbacks have not run yet.
        }

        RunOutcome run(Gateway gateway) throws Exception {
            HostToolSupervisor nativePort = new HostToolSupervisor(host, tasks, id, run, epoch, false);
            ContextBudgetPolicy.Source budgets = selected -> ContextBudgetPolicy.RouteBudget.forRoute(selected,
                    new ContextWindow.Budget(131072, 2048, 1024, 262144, 64, 0),
                    ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE, 1, 1);
            return new AgentRunner(null, nativePort, budgets).run(id, goal, gateway.config(), control, event -> {
                events.add(new JSONObject(event.toString()));
                tasks.event(id, run, epoch, event);
            }, tasks.journal(id, run, epoch, control));
        }

        @Override public void close() { host.close(); database.close(); }
    }

    private interface Script { JSONObject respond(int request, JSONObject body) throws Exception; }

    private static final class Gateway implements AutoCloseable {
        final HttpServer server;
        final List<JSONObject> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Gateway(Fixture fixture, Script script) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger count = new AtomicInteger();
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    requests.add(body);
                    JSONObject response;
                    try { response = script.respond(count.incrementAndGet(), body); }
                    catch (Throwable invalidFixture) {
                        failure.compareAndSet(null, invalidFixture);
                        response = answer("Fixture assertion failed; this is not a business success.");
                    }
                    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable stopped) {
                    // An owner cancellation closes the original HTTP connection. A broken
                    // pipe while sending its late fixture response is the expected boundary.
                    if (!fixture.control.isCancelled()) failure.compareAndSet(null, stopped);
                } finally { exchange.close(); }
            });
            server.start();
        }
        ModelConfig config() { return new ModelConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "fixture-key", "fixture", false, 8, 10, "openai_chat", 500); }
        void assertHealthy() { if (failure.get() != null) throw new AssertionError("Native journey fixture failed", failure.get()); }
        @Override public void close() { server.stop(0); }
    }

    private static JSONObject call(String id, JSONObject arguments) throws Exception {
        return new JSONObject().put("id", id).put("type", "function").put("function", new JSONObject()
                .put("name", "memory_search").put("arguments", arguments.toString()));
    }
    private static JSONObject calls(JSONObject... calls) throws Exception {
        JSONArray values = new JSONArray(); for (JSONObject call : calls) values.put(call);
        return response(new JSONObject().put("role", "assistant").put("content", "").put("tool_calls", values), "tool_calls");
    }
    private static JSONObject answer(String text) throws Exception {
        return response(new JSONObject().put("role", "assistant").put("content", text), "stop");
    }
    private static JSONObject response(JSONObject message, String finish) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", finish).put("message", message)));
    }
    private static List<JSONObject> toolResults(JSONObject request) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        JSONArray messages = request.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if ("tool".equals(message.optString("role"))) result.add(new JSONObject(message.getString("content")));
        }
        return result;
    }
    private static List<JSONObject> operations(JSONObject task, String tool) throws Exception {
        List<JSONObject> result = new ArrayList<>(); JSONArray values = task.getJSONArray("operations");
        for (int i = 0; i < values.length(); i++) if (tool.equals(values.getJSONObject(i).optString("tool"))) result.add(values.getJSONObject(i));
        return result;
    }
    private static List<JSONObject> events(JSONObject task, String type) throws Exception {
        List<JSONObject> result = new ArrayList<>(); JSONArray values = task.getJSONArray("events");
        for (int i = 0; i < values.length(); i++) if (type.equals(values.getJSONObject(i).optString("type"))) result.add(values.getJSONObject(i));
        return result;
    }
    private static JSONObject scope() throws Exception { return new JSONObject().put("ownerId", "owner").put("spaceId", "private"); }
    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(); for (byte b : digest) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
}
