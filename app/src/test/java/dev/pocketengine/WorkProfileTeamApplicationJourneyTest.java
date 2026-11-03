package dev.pocketengine;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Exact original App -> project selection -> Profile run -> native TeamPlan/owner approval ->
 * original Coordinator/member Runner/SQL/HTTP and frozen draft receipts. Controlled loopback
 * model replies are not provider/model quality, full-project, remote or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileTeamApplicationJourneyTest {
    private EngineApplication app;
    private HttpServer server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile String planRef, planHash, teamRun;
    private String project, teamId;
    private volatile int inheritedCap;
    private final AtomicInteger physicalActive=new AtomicInteger(),physicalMaximum=new AtomicInteger();
    private final CountDownLatch childEntered=new CountDownLatch(1),releaseChild=new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean blockedChild=new java.util.concurrent.atomic.AtomicBoolean();
    private JSONObject checkout, main, member, named, explicit, global;

    private static String id() { return UUID.randomUUID().toString(); }
    private static JSONObject text(String value) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", value);
    }
    private static JSONObject tool(String name, JSONObject args) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", id()).put("type", "function")
                        .put("function", new JSONObject().put("name", name).put("arguments", args.toString()))));
    }
    private static List<JSONObject> receipts(JSONArray history) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.getJSONObject(i);
            if (!"tool".equals(row.optString("role"))) continue;
            try { rows.add(new JSONObject(row.getString("content"))); }
            catch (org.json.JSONException notReceipt) { }
        }
        return rows;
    }

    @Before public void open() throws Exception {
        app = (EngineApplication) RuntimeEnvironment.getApplication();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int active=physicalActive.incrementAndGet();physicalMaximum.accumulateAndGet(active,Math::max);
            try {
                JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(request);
                if(inheritedCap==1&&!"profile-parent".equals(request.getString("model"))&&blockedChild.compareAndSet(false,true)) {
                    childEntered.countDown();assertTrue("Native physical call must be released by fixture",releaseChild.await(15,TimeUnit.SECONDS));
                }
                JSONObject reply = request.getString("model").equals("profile-parent")
                        ? parent(request) : producer(request);
                if(reply.has("tool_calls")) {
                    String returned=reply.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name");
                    JSONArray offered=request.optJSONArray("tools");boolean found=false;
                    for(int t=0;offered!=null&&t<offered.length();t++)if(returned.equals(offered.getJSONObject(t).getJSONObject("function").getString("name")))found=true;
                    assertTrue("Fixture returned unoffered "+returned+" for "+request.getString("model")+" actual tools="+offered,found);
                }
                byte[] body = new JSONObject().put("id", id()).put("object", "chat.completion")
                        .put("model", request.getString("model")).put("choices", new JSONArray().put(
                                new JSONObject().put("index", 0).put("message", reply)
                                        .put("finish_reason", reply.has("tool_calls") ? "tool_calls" : "stop")))
                        .put("usage", new JSONObject().put("prompt_tokens", 3).put("completion_tokens", 2))
                        .toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                try { exchange.sendResponseHeaders(500, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); }
                catch (Exception closed) { }
            } finally { physicalActive.decrementAndGet();exchange.close(); }
        });
        server.start();
        main = connection("Main", "profile-parent");
        member = connection("General member", "profile-member");
        named = connection("Actual review slot", "profile-review");
        explicit = connection("Saved slot exact source", "slot-explicit");
        global = connection("Captured global fallback", "startup-global");
        app.settings.selectApiConnection(global.getString("id"), 1);
        var workspace = app.features.module("workspace");
        project = workspace.handle("create", new JSONObject().put("name", "Profile native original project")).getString("id");
        for (String path : new String[]{"a.txt", "b.txt", "c.txt"}) workspace.handle("write", new JSONObject()
                .put("projectId", project).put("path", path).put("content", "ORIGINAL " + path).put("expectedVersion", 0));
        checkout = ((LocalModule) app.features.module("local")).openProjectForOwner(project);
        JSONArray members = new JSONArray().put(slot("author")).put(slot("review"))
                .put(slot("literal").put("modelTarget", ModelTargetRef.exact(explicit.getString("id"), "slot-explicit").toJson()));
        teamId = app.features.module("teams").handle("save", new JSONObject().put("name", "Exact native local slots")
                .put("mode", "collaborate").put("leaderId", "author").put("members", members)).getString("id");
    }

    private JSONObject connection(String label, String model) throws Exception {
        return app.settings.saveApiConnection("", 0, label, new JSONObject()
                .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .put("provider", "openai_chat").put("model", model).put("sendScreenshots", false)
                .put("maxSteps", 80).put("maxOutputTokens", 512).put("requestTimeoutSeconds", 20));
    }
    private JSONObject slot(String memberId) throws Exception {
        return new JSONObject().put("id", memberId).put("name", memberId).put("role", "Produce only the exact native selected file")
                .put("runner", "model").put("device", "phone");
    }
    private JSONObject proposal(String key, String memberId, String path) throws Exception {
        return new JSONObject().put("key", key).put("title", key).put("memberId", memberId)
                .put("instruction", "Write DELIVERED to your exact original isolated file and freeze the actual artifact")
                .put("acceptance", "Original file remains unchanged until the separate native integration approval")
                .put("paths", new JSONArray().put(path)).put("dependsOn", new JSONArray());
    }
    private JSONObject parent(JSONObject request) throws Exception {
        List<JSONObject> rows = receipts(request.getJSONArray("messages"));
        JSONObject latest = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        if (planRef == null) {
            if (latest != null && latest.has("planRef")) {
                planRef = latest.getString("planRef"); planHash = latest.getString("planHash");
            } else return tool("teams_plan", new JSONObject().put("teamId", teamId).put("goal", "Freeze the native three selected file outputs")
                    .put("modelRequestLimit", 50).put("toolOperationLimit", 32)
                    .put("workItems", new JSONArray().put(proposal("first", "author", "a.txt"))
                            .put(proposal("second", "review", "b.txt")).put(proposal("third", "literal", "c.txt"))));
        }
        if (teamRun == null) {
            if (latest != null && latest.has("teamRunId") && !"approval_required".equals(latest.optString("code"))) teamRun = latest.getString("teamRunId");
            else return tool("teams_start", new JSONObject().put("planRef", planRef).put("planHash", planHash));
        }
        JSONObject state = app.features.module("teams").handle("get", new JSONObject().put("runId", teamRun));
        if ("awaiting_user".equals(state.optString("status"))) return text("The original members are waiting; no successful output is claimed.");
        if ("awaiting_review".equals(state.optString("status"))) return text("The original isolated artifacts are frozen; separate native review and integration have not been approved.");
        return tool("teams_wait", new JSONObject().put("teamRunId", teamRun).put("afterEvent", latest == null ? 0 : latest.optLong("eventCursor", 0)).put("maxWait", 20000));
    }
    private JSONObject producer(JSONObject request) throws Exception {
        assertTrue("Only exact actual selected member sources may be used", Set.of("profile-member", "profile-review", "slot-explicit").contains(request.getString("model")));
        var match = Pattern.compile("原assignmentId：([a-f0-9-]{36})").matcher(request.getJSONArray("messages").toString().replace("\\/", "/"));
        assertTrue("Actual original assignment identity must be in the request", match.find());
        JSONObject task = app.tasks.get(match.group(1), false), binding = task.getJSONObject("teamAssignment");
        String memberId = binding.getString("memberId"), path = binding.getJSONArray("files").getJSONObject(0).getString("path");
        assertEquals(memberId.equals("author") ? "profile-member" : memberId.equals("review") ? "profile-review" : "slot-explicit", request.getString("model"));
        boolean prepared = false, written = false, frozen = false; JSONObject read = null;
        for (JSONObject row : receipts(request.getJSONArray("messages"))) {
            prepared |= row.has("teamDraft"); written |= row.has("draftWrite"); frozen |= row.has("teamArtifacts");
            if (row.has("path") && row.has("version") && row.has("content") && !row.has("artifactId")) read = row;
        }
        if (frozen) return text("Actual native artifact is frozen; original project was not written.");
        if (!prepared) return tool("team_prepare_draft", new JSONObject());
        if (read == null) return tool("team_read_file", new JSONObject().put("path", path));
        if (!written) return tool("team_write_file", new JSONObject().put("path", path).put("content", "DELIVERED " + path)
                .put("expectedVersion", read.getInt("version")));
        return tool("team_freeze_outputs", new JSONObject());
    }

    private NativeWorkProfileHost.Selection select(RunControl original) throws Exception {
        var page = app.nativeWorkProfiles().newOwnerPage(); page.setForeground(true);
        try {
            JSONObject definition = WorkProfile.definition("draft-member", 1, "Native Profile member routes");
            definition.getJSONObject("modelPolicies")
                    .put("MAIN", ModelTargetRef.exact(main.getString("id"), "profile-parent").toJson())
                    .put("SUMMARY", ModelTargetRef.exact(main.getString("id"), "profile-parent").toJson())
                    .put("MEMBER", ModelTargetRef.exact(member.getString("id"), "profile-member").toJson())
                    .put("MEMBER.review", ModelTargetRef.exact(named.getString("id"), "profile-review").toJson())
                    .put("MEMBER.literal", ModelTargetRef.exact(named.getString("id"), "profile-review").toJson());
            if(inheritedCap!=0)definition.getJSONObject("resourcePreferences").put("maxConcurrentModelCalls",inheritedCap);
            definition.put("teamTemplateRef", new JSONObject().put("id", teamId)
                    .put("digest", WorkProfile.fingerprint(app.features.teamDefinition(teamId))).put("optional", false));
            JSONObject saved = page.save("", 0, "Native Profile member routes", definition);
            var review = page.review(saved.getString("profileId"), 1, new RunControl(), app.reviewOwnerWorkProfileProject(project, checkout.getString("id"), "managed_process_v1"));
            assertTrue(review.view().toString(), review.view().getBoolean("ready"));
            return page.select(review, review.digest, original);
        } finally { page.close(); }
    }

    @After public void close() throws Exception {
        releaseChild.countDown();
        if (app != null) { app.onTerminate(); app.features.close(); }
        if (server != null) server.stop(0);
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test public void originalProjectProfileActuallyUsesDefaultNamedAndSavedExplicitMemberSources() throws Exception { journey(0); }
    @Test public void originalParentFrozenConcurrencyOneIsInheritedByActualSiblingPhysicalCalls() throws Exception { journey(1); }
    private void journey(int frozenCap) throws Exception {
        inheritedCap=frozenCap;
        JSONObject first = worker.submit(() -> {
            RunControl original = new RunControl(); var profile = select(original);
            var selectedProject = app.reviewOwnerWorkProfileProject(project, checkout.getString("id"), "managed_process_v1");
            app.settings.selectApiConnection(named.getString("id"), 1);
            return app.runOwnerWorkProfile("Complete the exact native three-slot file plan", "", new JSONObject()
                    .put("deadlineAt", System.currentTimeMillis() + 240000).put("toolLimit", 80).put("modelCallLimit", 80),
                    null, profile, original, selectedProject);
        }).get(35, TimeUnit.SECONDS);
        assertNull(failure.get());
        String parent = first.getString("taskId");
        JSONObject before = app.tasks.get(parent, false);
        assertEquals(checkout.getString("id"), before.getJSONObject("developmentContext").getString("checkoutId"));
        assertNotNull("First result="+first+"; native task="+before+"; requests="+requests.size(),planRef); assertNotNull(planHash); assertNull("No native owner start approval yet", teamRun);
        JSONObject approval = null;
        JSONArray approvals = before.getJSONArray("approvals");
        for (int i = 0; i < approvals.length(); i++) if ("pending".equals(approvals.getJSONObject(i).optString("state"))) approval = approvals.getJSONObject(i);
        assertNotNull(before.toString(), approval);
        JSONObject exact = app.tasks.teamParentPlanForOwner(parent, approval.getString("id")).getJSONObject("plan");
        assertEquals("startup-global", exact.getJSONObject("definition").getJSONObject("nativeWorkProfileModels").getJSONObject("globalModelDefault").getString("model"));
        app.tasks.decideCapabilityApproval(parent, approval.getString("id"), true);
        var continuation = worker.submit(() -> {
            var page = app.nativeWorkProfiles().newOwnerPage(); page.setForeground(true);
            try {
                var review = page.reviewForTask(app.tasks, parent, before.getString("runId"), before.getInt("coordinatorEpoch"), new RunControl());
                RunControl next = new RunControl(); var selected = page.select(review, review.digest, next);
                return app.continueOwnerWorkProfile(parent, null, selected, next);
            } finally { page.close(); }
        });
        if(inheritedCap==1)try {
            assertTrue("Actual first member transport must enter",childEntered.await(15,TimeUnit.SECONDS));
            var field=dev.pocketengine.runtime.ModelGateway.class.getDeclaredField("MODEL_CALLS");field.setAccessible(true);Object gate=field.get(null);
            var count=gate.getClass().getDeclaredMethod("waitingCount");count.setAccessible(true);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int queued=0;
            while((queued=(Integer)count.invoke(gate))<1&&System.nanoTime()<until)Thread.sleep(10);
            assertTrue("Another original sibling must really wait in the existing gate",queued>=1);
            assertEquals("Only one physical family call entered while its siblings queued",1,physicalActive.get());
            assertEquals(1,requests.stream().filter(r->!"profile-parent".equals(r.optString("model"))).count());
            JSONArray liveTasks=app.tasks.list();int actualUsage=0;
            for(int t=0;t<liveTasks.length();t++){
                JSONObject child=app.tasks.get(liveTasks.getJSONObject(t).getString("id"),false);
                if(child.optJSONObject("teamAssignment")!=null)actualUsage+=app.tasks.inferenceUsage(child.getString("id")).getJSONArray("receipts").length();
            }
            assertEquals("Only the first actually dispatched child has a native physical receipt; queued siblings have none",1,actualUsage);
        } finally {releaseChild.countDown();}
        JSONObject continued=continuation.get(45,TimeUnit.SECONDS);
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(parent, continued.getString("taskId")); assertNotNull(teamRun);
        JSONObject state = app.features.module("teams").handle("get", new JSONObject().put("runId", teamRun));
        assertEquals(state.toString(), "awaiting_review", state.getString("status"));
        JSONObject after = app.tasks.get(parent, false);
        for (String key : new String[]{"revision", "deadlineAt", "toolLimit", "modelCallLimit"}) assertEquals(before.getJSONObject("executionScope").get(key), after.getJSONObject("executionScope").get(key));
        assertEquals(50, after.getJSONObject("teamReservationPool").getInt("modelRequestLimit"));
        assertEquals(32, after.getJSONObject("teamReservationPool").getInt("toolOperationLimit"));
        Set<String> used = new java.util.HashSet<>(); int authors = 0;
        JSONArray tasks = app.tasks.list();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = app.tasks.get(tasks.getJSONObject(i).getString("id"), false), origin = task.optJSONObject("origin");
            if (origin == null || !"team_assignment".equals(origin.optString("kind"))) continue;
            authors++;
            assertEquals(task.toString(), "completed", task.getString("status"));
            JSONArray actual = app.tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts");
            assertTrue(actual.length() > 0);
            for (int r = 0; r < actual.length(); r++) {
                JSONObject receipt = actual.getJSONObject(r);
                assertEquals(origin.getString("memberId"), receipt.getJSONObject("context").getString("memberId"));
                used.add(receipt.getJSONObject("route").getString("requestedModel"));
            }
        }
        if(inheritedCap==1)assertEquals("Parent and actual siblings intersect one frozen original budget-owner gate",1,physicalMaximum.get());
        assertEquals(3, authors); assertEquals(Set.of("profile-member", "profile-review", "slot-explicit"), used);
        for (String path : new String[]{"a.txt", "b.txt", "c.txt"}) assertEquals("ORIGINAL " + path,
                app.features.module("workspace").handle("read", new JSONObject().put("projectId", project).put("path", path)).getString("content"));
    }
}
