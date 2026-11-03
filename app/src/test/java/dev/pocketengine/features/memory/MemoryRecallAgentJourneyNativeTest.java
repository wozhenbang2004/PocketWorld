package dev.pocketengine.features.memory;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.assistant.AssistantModule;
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

    @Test public void rawHistoryPlanReachesOriginalAgentRequestAndWithdrawalStopsItsLateFollowUp() throws Exception {
        String query = "服务恢复办法";
        String original = "The gateway stalled; restarting the worker restored traffic.";
        try (Fixture f = new Fixture(query, true)) {
            Original target = f.importOriginal(original, new JSONObject().put("layer", "episodic")
                    .put("text", "A stored summary without the original recovery instructions."), EVENT_AT);
            AssistantModule assistant = new AssistantModule(new FeatureContext(f.context, null, null, event -> {}),
                    f.memory, null, f.tasks);
            f.host.register(assistant);
            // The model cannot open this scope; this is the original owner's explicit native setting.
            assertEquals("OWNER_PRIVATE", assistant.setHistoryReadScope(f.conversation, 0, "OWNER_PRIVATE", true).getString("mode"));
            JSONObject arguments = new JSONObject().put("kind", "conversations").put("follow", "messages")
                    .put("query", query).put("queries", new JSONArray().put("restarting the worker"))
                    .put("readTop", 1).put("maxCalls", 8).put("limit", 4).put("maxChars", 4000)
                    .put("createdAfter", EVENT_AT - 1).put("createdBefore", EVENT_AT + 1);
            try (CapabilityPort port = f.historyPort(assistant); Gateway gateway = new Gateway(f, (request, body) -> {
                if (request == 1) {
                    assertFalse(body.toString().contains(original));
                    assertTrue(body.getJSONArray("tools").toString().contains(ConversationHistory.SEARCH));
                    JSONObject call = call("raw-history-plan", arguments);
                    call.getJSONObject("function").put("name", ConversationHistory.SEARCH);
                    return calls(call);
                }
                assertEquals("withdrawal must stop any further model request", 2, request);
                List<JSONObject> results = toolResults(body);assertEquals(1, results.size());
                JSONObject receipt = results.get(0),row = receipt.getJSONArray("entries").getJSONObject(0);
                assertEquals("current", receipt.getString("status"));assertTrue(receipt.getBoolean("ok"));
                assertEquals(1, receipt.getJSONArray("entries").length());assertEquals(original, row.getString("body"));
                assertEquals(target.conversation, row.getString("sourceConversationId"));
                assertEquals(target.ref, SourceRef.fromJson(row.getJSONObject("sourceRef")));
                assertEquals("sha256:" + sha256(original), row.getString("bodyFingerprint"));
                assertTrue(row.getBoolean("exactSourceRead"));assertTrue(row.getBoolean("historicalEvidence"));
                assertFalse(row.getBoolean("currentFactVerified"));assertFalse(receipt.getBoolean("executionAuthorized"));
                JSONObject span = row.getJSONObject("readRange"),jump = row.getJSONObject("sourceJump");
                assertEquals(0, span.getInt("offset"));assertEquals(original.length(), span.getInt("chars"));
                assertEquals(original.length(), span.getInt("totalChars"));assertEquals("UTF-16", span.getString("unit"));
                assertEquals(target.ref, SourceRef.fromJson(jump.getJSONObject("sourceRef")));
                assertEquals(target.conversation, jump.getString("conversationId"));
                assertEquals(row.getString("bodyFingerprint"), jump.getString("bodyFingerprint"));
                assertEquals(span.getInt("offset"), jump.getInt("offset"));assertEquals(span.getInt("chars"), jump.getInt("chars"));
                JSONObject plan = receipt.getJSONObject("queryPlan");
                assertEquals("messages", plan.getString("follow"));assertEquals(2, plan.getInt("queryCount"));
                assertEquals(6, plan.getInt("producerCalls"));assertEquals(1, plan.getInt("exactReadAttempts"));
                assertEquals(0, plan.getInt("extraModelRequests"));assertEquals(0, plan.getInt("extraNetworkRequests"));
                assertTrue(plan.getBoolean("historicalEvidence"));assertFalse(plan.getBoolean("currentFactVerified"));
                assertEquals(target.ref, SourceRef.fromJson(receipt.getJSONArray("messageSourceRefs").getJSONObject(0)));
                assertEquals(1, assistant.retireMemoryContexts(new JSONArray().put(new JSONObject()
                        .put("sourceRefs", new JSONArray().put(target.ref.toJson()))), "owner_forget"));
                // A real source withdrawal occurs while the second HTTP response is outstanding.
                // Its attempted follow-up must never dispatch or turn into a newly provided body.
                JSONObject stale = call("late-raw-follow-up", arguments);
                stale.getJSONObject("function").put("name", ConversationHistory.SEARCH);
                return calls(stale);
            })) {
                RunOutcome outcome = f.run(gateway, port);gateway.assertHealthy();
                assertEquals("cancelled", outcome.status);assertTrue(f.control.isCancelled());
                assertEquals(2, gateway.requests.size());assertEquals("retired", f.originals.state(target.conversation, target.ref).getString("status"));
                JSONObject task = f.tasks.get(f.id, false);
                List<JSONObject> delivered = events(task, "tool").stream().filter(event -> event.has("receipt")).toList();
                assertEquals(1, delivered.size());assertEquals(ConversationHistory.SEARCH, delivered.get(0).getString("tool"));
                assertEquals(target.ref, SourceRef.fromJson(delivered.get(0).getJSONObject("receipt")
                        .getJSONArray("entries").getJSONObject(0).getJSONObject("sourceRef")));
                assertEquals(0, operations(task, ConversationHistory.SEARCH).size());
                JSONArray operations = task.getJSONArray("operations");
                for (int i = 0; i < operations.length(); i++) assertEquals(TaskMemoryJournal.TOOL, operations.getJSONObject(i).getString("tool"));
                assertEquals(0, events(task, "memory_tool_reference_prepared").size());
                assertEquals(2, f.tasks.inferenceUsage(f.id).getInt("attempts"));
            }
        }
    }

    static final class Original {
        final String conversation, text;
        final SourceRef ref;
        final JSONObject record;
        Original(String conversation, String text, SourceRef ref, JSONObject record) {
            this.conversation = conversation; this.text = text; this.ref = ref; this.record = record;
        }
    }

    static final class Fixture implements AutoCloseable {
        final Context context = RuntimeEnvironment.getApplication();
        final TaskStore tasks;
        final SQLiteDatabase database;
        final ConversationRepository originals;
        final FeatureHost host;
        final MemoryModule memory;
        final File memoryFile;
        final String id, run, goal, conversation;
        final int epoch;
        final RunControl control = new RunControl();
        final List<JSONObject> events = new CopyOnWriteArrayList<>();

        Fixture(String goal) throws Exception {
            this(goal, false);
        }

        Fixture(String goal, boolean rawHistory) throws Exception {
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
            }, SQLiteMemoryRetrievalBackend.create());
            System.out.println("MEMORY_AGENT_NATIVE_BACKEND " + memory.snapshot().getJSONObject("retrievalBackend"));
            memoryFile = new File(context.getFilesDir(), "memory/records.json");
            host.register(memory);
            conversation = rawHistory ? UUID.randomUUID().toString() : "";
            if (rawHistory) originals.transaction(() -> {
                originals.saveConversation(new JSONObject().put("conversationId", conversation)
                        .put("title", "Current original Agent session").put("createdAt", EVENT_AT + 100).put("updatedAt", EVENT_AT + 100));
                return null;
            });
            JSONObject created = rawHistory ? tasks.createCapabilityTask(goal, conversation) : tasks.create(goal, "main_screen");
            JSONObject attempt = tasks.startAttempt(created.getString("id"), rawHistory ? "capabilities" : "main_screen", 8);
            id = attempt.getString("id"); run = attempt.getString("runId"); epoch = attempt.getInt("coordinatorEpoch");
            control.bindHostRun(id, run, epoch);
            tasks.authorizeTools(id, run, new JSONArray().put("memory_search").put("memory_recall"));
        }

        Original remember(String text, String entity, String label, String predicate, long at) throws Exception {
            JSONObject assertion = new JSONObject().put("version", 1).put("kind", "told")
                    .put("subjectId", entity).put("subjectType", "entity").put("subjectLabel", label)
                    .put("predicate", predicate).put("object", text).put("eventTime", at).put("assertedAt", at);
            return importOriginal(text, new JSONObject().put("text", text).put("layer", "fact").put("assertion", assertion), at);
        }

        Original rememberSpec(JSONObject spec) throws Exception {
            JSONObject source = spec.getJSONObject("source");
            if (!"message".equals(source.getString("kind")) || !"owner".equals(source.getString("ownerId"))
                    || !"private".equals(source.getString("spaceId"))) throw new IllegalArgumentException("native message fixture scope");
            assertEquals(source.getString("sha256"), sha256(source.getString("originalText")));
            JSONObject value = new JSONObject().put("text", spec.getString("text")).put("layer", spec.getString("layer"));
            if (spec.has("assertion")) value.put("assertion", new JSONObject(spec.getJSONObject("assertion").toString()));
            return importOriginal(source.getString("originalText"), value, spec.getLong("updatedAt"));
        }

        private Original importOriginal(String text, JSONObject value, long at) throws Exception {
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
            value.put("sourceRefs", new JSONArray().put(ref.toJson()));
            if (value.has("assertion")) value.getJSONObject("assertion").put("evidenceSpans", new JSONArray()
                    .put(new JSONObject().put("sourceRef", ref.toJson()).put("offset", 0)
                            .put("chars", text.length()).put("sha256", sha256(text))));
            JSONObject record = memory.handle("importFromSources", new JSONObject().put("scope", scope())
                    .put("record", value)).getJSONObject("record");
            return new Original(conversation, text, ref, record);
        }

        void assertOriginal(Original original) throws Exception {
            JSONObject stored = originals.read(() -> originals.message(original.conversation, original.ref.sourceId));
            assertEquals(original.ref.revision, stored.getLong("revision"));
            String text = stored.getString("content");
            assertEquals(original.text, text);
            assertEquals(original.ref, SourceRef.fromJson(original.record.getJSONArray("sourceRefs").getJSONObject(0)));
            if (original.record.has("assertion")) {
                JSONObject span = original.record.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0);
                assertEquals(original.ref, SourceRef.fromJson(span.getJSONObject("sourceRef")));
                assertEquals(span.getString("sha256"), sha256(text.substring(span.getInt("offset"),
                        span.getInt("offset") + span.getInt("chars"))));
            }
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
            return run(gateway, new HostToolSupervisor(host, tasks, id, run, epoch, false));
        }

        /** Exact native wrappers used by EngineApplication.bindRawTaskEvidence, without a second
         * application or fake history provider. Every read delegates to the original Tasks/Assistant. */
        CapabilityPort historyPort(AssistantModule assistant) throws Exception {
            HostToolSupervisor nativePort = new HostToolSupervisor(host, tasks, id, run, epoch, false);
            MemoryHostFence fence = new MemoryHostFence() {
                public void check() throws Exception { control.check(); tasks.checkExtensionRun(id, run, epoch, control); }
                public JSONObject admit(Admission action) throws Exception { synchronized (tasks) { check(); return action.run(); } }
            };
            TaskStoreMemoryReader.RawBinding raw = new TaskStoreMemoryReader.RawBinding(id, run, epoch, control, fence, host::memoryContextRevision);
            if (tasks.toolOutputSources() != null) raw.bindOutputSources(tasks.toolOutputSources());
            tasks.bindRawModelExposure(raw);
            CapabilityPort rawPort = new TaskStoreMemoryReader.RawCapability(nativePort, raw, new TaskStoreMemoryReader.RawAccess() {
                public JSONObject query(TaskStoreMemoryReader.RawBinding selected, JSONObject options) throws Exception { return tasks.queryMemoryRaw(selected, options); }
                public JSONObject read(TaskStoreMemoryReader.RawBinding selected, JSONObject citation) throws Exception { return tasks.readMemoryRaw(selected, citation); }
            });
            ConversationHistory.HistoryBinding history = new ConversationHistory.HistoryBinding(raw, conversation, new ConversationHistory.Source() {
                public ConversationHistory.Gate open(String id) throws Exception { return assistant.openHistoryConversation(id); }
                public ConversationHistory.ReadScope captureReadScope(String id) throws Exception { return assistant.captureHistoryReadScope(id); }
                public String conversationFor(SourceRef ref) throws Exception { return assistant.historyConversationFor(ref); }
                public JSONObject conversations(ConversationHistory.ReadScope scope, JSONObject options, RunControl original) throws Exception { return assistant.queryHistoryConversations(scope, options, original); }
                public JSONObject query(ConversationHistory.Gate gate, JSONObject options, RunControl original) throws Exception { return assistant.queryRetainedHistory(gate, options, original); }
                public JSONArray inspect(ConversationHistory.Gate gate, List<SourceRef> expected) throws Exception { return assistant.inspectHistorySources(gate, expected); }
                public JSONObject read(ConversationHistory.Gate gate, SourceRef ref, int offset, int chars, RunControl original) throws Exception { return assistant.readHistoryMessage(gate, ref, offset, chars, original); }
                public JSONObject neighbors(ConversationHistory.Gate gate, SourceRef ref, String fingerprint, String direction, JSONObject options, RunControl original) throws Exception { return assistant.historyNeighbors(gate, ref, fingerprint, direction, options, original); }
            });
            raw.bindMessageCurrentness(ConversationHistory.currentness(history,
                    remaining -> tasks.openConversationHistoryProjection(history, remaining), new ConversationHistory.MessageProjection() {
                        public JSONArray project(JSONArray messages, java.util.Set<String> delegated) throws Exception { return assistant.projectMemoryHistory(messages, delegated, conversation); }
                        public JSONArray project(JSONArray messages, java.util.Set<String> delegated, ConversationHistory.SourceBytes bytes) throws Exception { return assistant.projectMemoryHistory(messages, delegated, conversation, bytes); }
                    }));
            CapabilityPort exposed = new ConversationHistory.Capability(rawPort, history, new ConversationHistory.Tasks() {
                public JSONObject query(ConversationHistory.HistoryBinding binding, ConversationHistory.SourceLocator selected, String kind, JSONObject options) throws Exception { return tasks.queryConversationHistory(binding, selected, kind, options); }
                public JSONObject read(ConversationHistory.HistoryBinding binding, ConversationHistory.SourceLocator selected, int offset, int chars) throws Exception { return tasks.readConversationHistoryOperation(binding, selected, offset, chars); }
                public void requireCurrent(ConversationHistory.HistoryBinding binding, List<ConversationHistory.SourceLocator> selected) throws Exception { tasks.requireConversationHistorySources(binding, selected); }
            });
            JSONArray tools = exposed.tools(), names = new JSONArray();
            for (int i = 0; i < tools.length(); i++) names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));
            tasks.authorizeTools(id, run, names);
            return exposed;
        }

        RunOutcome run(Gateway gateway, CapabilityPort nativePort) throws Exception {
            ContextBudgetPolicy.Source budgets = selected -> ContextBudgetPolicy.RouteBudget.forRoute(selected,
                    new ContextWindow.Budget(131072, 2048, 1024, 262144, 64, 0),
                    ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE, 1, 1);
            return new AgentRunner(null, nativePort, budgets).run(id, goal, gateway.config(), control, event -> {
                try {
                    events.add(new JSONObject(event.toString()));
                    tasks.event(id, run, epoch, event);
                } catch (Exception failedAudit) {
                    // EventSink has no checked-exception channel. A native audit failure
                    // fails the fixture immediately; it must never be treated as success.
                    throw new AssertionError("Original TaskStore event could not be recorded", failedAudit);
                }
            }, tasks.journal(id, run, epoch, control));
        }

        @Override public void close() { host.close(); database.close(); }
    }

    interface Script { JSONObject respond(int request, JSONObject body) throws Exception; }

    static final class Gateway implements AutoCloseable {
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

    static JSONObject call(String id, JSONObject arguments) throws Exception {
        return new JSONObject().put("id", id).put("type", "function").put("function", new JSONObject()
                .put("name", "memory_search").put("arguments", arguments.toString()));
    }
    static JSONObject calls(JSONObject... calls) throws Exception {
        JSONArray values = new JSONArray(); for (JSONObject call : calls) values.put(call);
        return response(new JSONObject().put("role", "assistant").put("content", "").put("tool_calls", values), "tool_calls");
    }
    static JSONObject answer(String text) throws Exception {
        return response(new JSONObject().put("role", "assistant").put("content", text), "stop");
    }
    private static JSONObject response(JSONObject message, String finish) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", finish).put("message", message)));
    }
    static List<JSONObject> toolResults(JSONObject request) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        JSONArray messages = request.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if ("tool".equals(message.optString("role"))) result.add(new JSONObject(message.getString("content")));
        }
        return result;
    }
    static List<JSONObject> operations(JSONObject task, String tool) throws Exception {
        List<JSONObject> result = new ArrayList<>(); JSONArray values = task.getJSONArray("operations");
        for (int i = 0; i < values.length(); i++) if (tool.equals(values.getJSONObject(i).optString("tool"))) result.add(values.getJSONObject(i));
        return result;
    }
    static List<JSONObject> events(JSONObject task, String type) throws Exception {
        List<JSONObject> result = new ArrayList<>(); JSONArray values = task.getJSONArray("events");
        for (int i = 0; i < values.length(); i++) if (type.equals(values.getJSONObject(i).optString("type"))) result.add(values.getJSONObject(i));
        return result;
    }
    static JSONObject scope() throws Exception { return new JSONObject().put("ownerId", "owner").put("spaceId", "private"); }
    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(); for (byte b : digest) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
}
