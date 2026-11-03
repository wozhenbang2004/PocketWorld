package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileSources;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.teams.TeamParentPlan;
import dev.pocketengine.features.teams.TeamsModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.nio.file.Files;
import java.time.Clock;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
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

/** Native planning/source capture only. No App project/Profile entry or approved member dispatch
 * is claimed here: the actual App/approved start journey has its own separate test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileMemberPlanNativeTest {
    private static String id() { return UUID.randomUUID().toString(); }
    private Application app;
    private SettingsStore settings;
    private TaskStore tasks;
    private FeatureHost features;
    private NativePluginHost plugins;
    private WorkspaceModule workspace;
    private TeamsModule teams;
    private NativeWorkProfileHost host;
    private NativeWorkProfileHost.Page page;
    private RunControl original;
    private JSONObject first, second, team, task;
    private TeamParentPlan plan;
    private String project, operation;
    private JSONObject toolArgs,namedReference;
    private dev.pocketengine.features.controlplane.WorkProfileToolSelection policy;

    @Before public void open() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("pocket-world.db");
        tasks = new TaskStore(app);
        settings = new SettingsStore(app.getSharedPreferences("member-plan-" + id(), 0),
                new SettingsStore.ApiSecrets() {
                    final HashMap<String, String> secrets = new HashMap<>();
                    public String get(String slot) { return secrets.getOrDefault(slot, ""); }
                    public void put(String slot, String value) { secrets.put(slot, value); }
                });
        FeatureContext context = new FeatureContext(app, settings, new SecretVault(app), event -> { });
        features = new FeatureHost(event -> { }, tasks, () -> false);
        workspace = new WorkspaceModule(context);
        features.register(workspace);
        teams = new TeamsModule(context);
        features.register(teams);
        PluginsModule registry = new PluginsModule(context);
        features.register(registry);
        plugins = new NativePluginHost(app, registry, tasks,
                (binding, control) -> tasks.checkExtensionRun(binding.taskId, binding.runId, binding.epoch, control),
                changed -> { });
        project = workspace.handle("create", new JSONObject().put("name", "Native member plan source")).getString("id");
        for (String path : new String[]{"a.txt", "b.txt"}) workspace.handle("write", new JSONObject()
                .put("projectId", project).put("path", path).put("content", "Original " + path)
                .put("expectedVersion", 0));
        first = settings.saveApiConnection("", 0, "Profile general member", config("member-default"));
        second = settings.saveApiConnection("", 0, "Profile named member", config("member-review"));
        settings.selectApiConnection(first.getString("id"), 1);
        new ModelConnectionService(settings, Files.createTempDirectory("member-plan-models").resolve("catalog"), Clock.systemUTC()).install();
        host = new NativeWorkProfileHost(settings,
                new NativeWorkProfileSources(settings, features, plugins, Set.of(), null));
        page = host.newOwnerPage();
        page.setForeground(true);
    }

    private JSONObject config(String model) throws Exception {
        // No listener exists: planning must never dispatch this synthetic endpoint.
        return new JSONObject().put("baseUrl", "http://127.0.0.1:1/v1").put("provider", "openai_chat")
                .put("model", model).put("apiKey", "synthetic-member-source-not-a-key")
                .put("sendScreenshots", false);
    }

    private void capture(boolean explicit, boolean template) throws Exception {
        JSONObject beta = new JSONObject().put("id", "review").put("name", "Reviewer")
                .put("role", "Review the selected original file").put("runner", "model").put("device", "phone");
        if (explicit) beta.put("modelTarget", ModelTargetRef.exact(first.getString("id"), "explicit-saved-slot").toJson());
        String teamId = teams.handle("save", new JSONObject().put("name", "Native exact member slots")
                .put("leaderId", "author").put("mode", "collaborate")
                .put("members", new JSONArray().put(new JSONObject().put("id", "author").put("name", "Author")
                        .put("role", "Write only the selected file").put("runner", "model").put("device", "phone"))
                        .put(beta))).getString("id");
        team = features.teamDefinition(teamId);
        JSONObject definition = WorkProfile.definition("member-draft", 1, "Native member defaults");
        JSONObject policies = definition.getJSONObject("modelPolicies");
        policies.put("MAIN", ModelTargetRef.exact(first.getString("id"), "member-default").toJson())
                .put("SUMMARY", ModelTargetRef.exact(first.getString("id"), "member-default").toJson())
                .put("MEMBER", ModelTargetRef.exact(first.getString("id"), "member-default").toJson())
                .put("MEMBER.review", namedReference==null?ModelTargetRef.exact(second.getString("id"), "member-review").toJson():namedReference)
                .put("MEMBER.notSelected", ModelTargetRef.exact(first.getString("id"), "unused-member").toJson());
        if (template) definition.put("teamTemplateRef", new JSONObject().put("id", teamId)
                .put("digest", WorkProfile.fingerprint(team)).put("optional", false));
        JSONObject saved = page.save("", 0, "Native member defaults", definition);
        var review = page.review(saved.getString("profileId"), 1, new RunControl());
        assertTrue(review.view().toString(), review.view().getBoolean("ready"));
        original = new RunControl();
        var selected = page.select(review, review.digest, original);
        task = tasks.createCapabilityTask("Native planning-only member defaults", "");
        tasks.configureExecutionScope(task.getString("id"), 0, System.currentTimeMillis() + 240000, 40, 40);
        task = tasks.startAttemptWithWorkProfile(task.getString("id"), "capabilities", 40, selected, original);
        // Original native SQL binding fixture, not an App/UI project selection or new permission.
        tasks.bindDevelopmentContext(task.getString("id"), task.getString("runId"), task.getInt("coordinatorEpoch"),
                original, new JSONObject().put("format", "pocketworld.development-context.v1")
                        .put("projectId", project).put("checkoutId", id()).put("runtime", "managed_process_v1")
                        .put("initialSourceDigest", "a".repeat(64)).put("clientMessageId", id()));
        tasks.authorizeTools(task.getString("id"), task.getString("runId"), new JSONArray().put("teams_plan"));
        JSONObject args = new JSONObject().put("teamId", teamId).put("goal", "Review the original selected files")
                .put("workItems", new JSONArray().put(item("write", "author", "a.txt"))
                        .put(item("review", "review", "b.txt")));
        toolArgs=new JSONObject(args.toString());
        policy=host.toolSelectionForRun(tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original);
        operation = tasks.journal(task.getString("id"), task.getString("runId"), task.getInt("coordinatorEpoch"), original)
                .beginOperation("teams_plan", args);
        var origin = tasks.teamParentOrigin(task.getString("id"), task.getString("runId"),
                task.getInt("coordinatorEpoch"), "teams_plan", operation, args, original);
        plan = TeamParentPlan.capture(origin, workspace::handle, team, args);
    }

    private JSONObject item(String key, String member, String path) throws Exception {
        return new JSONObject().put("key", key).put("title", key).put("instruction", "Only the original selected file")
                .put("acceptance", "Native planning/source proof only").put("memberId", member)
                .put("paths", new JSONArray().put(path)).put("dependsOn", new JSONArray());
    }

    private NativeWorkProfileHost.MemberPlanModels source() throws Exception {
        return host.memberModelsForPlan(tasks, plan, original);
    }

    @After public void close() throws Exception {
        if (original != null) original.cancel();
        if (page != null) page.close();
        if (plugins != null) plugins.close();
        if (features != null) features.close();
        if (tasks != null) {
            var db = TaskStore.class.getDeclaredField("db");
            db.setAccessible(true);
            ((SQLiteDatabase) db.get(tasks)).close();
        }
    }

    @Test public void preparedProfileRecheckOnlyExcludesItsOwnExactOriginalJournalIntent() throws Exception {
        capture(false,true);
        assertThrows(Exception.class,policy::checkCurrent); // generic new-dispatch fence stays closed
        policy.checkBindingCurrent();
        JSONObject dispatched=new JSONObject(toolArgs.toString()).put("_hostOperationId",operation);
        policy.checkPrepared("teams_plan",dispatched,original);
        assertThrows(SecurityException.class,()->policy.checkPrepared("teams_start",dispatched,original));
        assertThrows(SecurityException.class,()->policy.checkPrepared("teams_plan",new JSONObject(dispatched.toString()).put("goal","different"),original));
        RunControl copied=new RunControl();copied.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        assertThrows(SecurityException.class,()->policy.checkPrepared("teams_plan",dispatched,copied));
        original.pause();assertThrows(SecurityException.class,policy::checkBindingCurrent);
    }

    @Test public void anotherUnknownEffectIsNeverExcludedByTheExactPreparedProfileCheck() throws Exception {
        capture(false,true);
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
        android.content.ContentValues crash=new android.content.ContentValues();
        try(var row=db.rawQuery("SELECT * FROM operations WHERE operation_id=?",new String[]{operation})){
            assertTrue(row.moveToFirst());android.database.DatabaseUtils.cursorRowToContentValues(row,crash);
        }
        crash.put("operation_id",id());crash.put("state","unknown");db.insertOrThrow("operations",null,crash);
        assertThrows(SecurityException.class,()->policy.checkPrepared("teams_plan",new JSONObject(toolArgs.toString()).put("_hostOperationId",operation),original));
    }

    @Test public void completedOrUnknownOperationCannotBeUsedAsAProfileRecheckToken() throws Exception {
        capture(false,true);
        JSONObject dispatched=new JSONObject(toolArgs.toString()).put("_hostOperationId",operation);
        tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original)
            .finishOperation(operation,new JSONObject().put("status","unknown"));
        assertThrows(SecurityException.class,()->policy.checkPrepared("teams_plan",dispatched,original));
    }

    @Test public void exactNativeSlotsChooseNamedThenGeneralProfileDefaultsWithoutDispatch() throws Exception {
        capture(false, true);
        var captured = source();
        assertEquals(Set.of("author", "review"), captured.models().keySet());
        assertEquals("member-default", captured.models().get("author").model);
        assertEquals("member-review", captured.models().get("review").model);
        assertEquals("MEMBER.review", captured.models().get("review").routePlan.purpose);
        JSONObject view = captured.view();
        assertFalse(view.getBoolean("configurationApplied"));
        assertFalse(view.getBoolean("executionAuthorized"));
        assertTrue(view.getBoolean("planningOnly"));
        assertTrue(view.getJSONArray("unusedProfilePurposes").toString().contains("MEMBER.notSelected"));
        assertFalse(view.toString().contains("synthetic-member-source-not-a-key"));
        assertEquals(0, tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
        assertEquals(0, teams.snapshot().getJSONArray("runs").length());
    }

    @Test public void exactNamedGroupRetainsAliasRevisionPurposeAndOnePhysicalAttemptAcrossColdPinCapture() throws Exception {
        JSONObject spec=new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",new JSONArray().put(second.getString("id"))))
            .put("candidates",new JSONArray().put(new JSONObject().put("candidateId","native-review").put("target",ModelTargetRef.exact(second.getString("id"),"member-review").toJson()).put("enabled",true).put("allowedPurposes",new JSONArray().put("MEMBER.review")).put("allowedAudiences",new JSONArray().put("APP_OWNER"))))
            .put("attemptPolicy",new JSONObject().put("maxAttempts",3).put("maxRouteSwitches",1));
        JSONObject group=settings.saveModelGroup("",0,"Only actual review slot",spec);
        namedReference=ModelTargetRef.group(group.getString("groupId"),1).toJson();capture(false,true);
        var captured=source();JSONObject enriched=TeamParentPlan.withWorkProfileModels(plan,captured).view();
        JSONObject member=enriched.getJSONObject("definition").getJSONArray("members").getJSONObject(1);
        var selected=dev.pocketengine.features.teams.TeamMemberModels.capture(member,captured.baseModel(),
            (ref,purpose,global)->settings.captureWorkProfileModel(ref,purpose,"APP_OWNER"));
        var route=selected.config.forPurpose("MEMBER").routePlan;
        assertEquals("MEMBER.review",route.purpose);assertEquals(group.getString("groupId"),route.requestedAlias);assertEquals(1,route.groupRevision);
        assertEquals(1,route.maxAttempts);assertEquals(0,route.maxRouteSwitches);
        dev.pocketengine.features.teams.TeamMemberModels.requireSame(selected,member.getJSONObject("memberModelSelection"));
        JSONObject spoof=new JSONObject(member.toString()).put("id","other");
        assertThrows(SecurityException.class,()->dev.pocketengine.features.teams.TeamMemberModels.capture(spoof,captured.baseModel(),(ref,purpose,global)->settings.captureWorkProfileModel(ref,purpose,"APP_OWNER")));
        settings.saveModelGroup(group.getString("groupId"),1,"Changed actual group",spec);
        assertThrows(Exception.class,()->dev.pocketengine.features.teams.TeamMemberModels.capture(member,captured.baseModel(),(ref,purpose,global)->settings.captureWorkProfileModel(ref,purpose,"APP_OWNER")));
    }

    @Test public void actualSavedSlotRefWinsInsteadOfBeingOverwrittenByProfileDefaults() throws Exception {
        capture(true, false);
        var captured = source();
        assertEquals(Set.of("author"), captured.models().keySet());
        assertTrue(captured.view().getJSONArray("unusedProfilePurposes").toString().contains("MEMBER.review"));
        JSONArray rows = captured.view().getJSONArray("members");
        assertEquals("original_explicit_member", rows.getJSONObject(1).getString("sourceKind"));
    }

    @Test public void nativeTeamRevisionDriftCannotRecaptureAMoreConvenientRoster() throws Exception {
        capture(false, true);
        var captured = source();
        teams.handle("save", new JSONObject(team.toString()).put("name", "Changed native team"));
        assertThrows(SecurityException.class, captured::models);
        assertThrows(SecurityException.class, this::source);
    }

    @Test public void pauseResumeAndCopiedCallerCannotReuseAPlanningSource() throws Exception {
        capture(false, false);
        var captured = source();
        RunControl copy = new RunControl();
        copy.bindHostRun(task.getString("id"), task.getString("runId"), task.getInt("coordinatorEpoch"));
        assertThrows(SecurityException.class, () -> captured.requireOriginal(tasks, plan, copy));
        original.pause();
        original.resume();
        assertThrows(SecurityException.class, captured::view);
    }

    @Test public void revokedExactModelNeverFallsBackToTheCapturedMainConnection() throws Exception {
        capture(false, true);
        var captured = source();
        settings.setApiConnectionEnabled(second.getString("id"), 1, false);
        assertThrows(Exception.class, captured::models);
        assertEquals("member-default", settings.get().model);
    }

    @Test public void startupGlobalIsFrozenSeparatelyInsteadOfBorrowingMainOrTheLaterDefault() throws Exception {
        settings.selectApiConnection(second.getString("id"),1);
        capture(false,false);
        settings.selectApiConnection(first.getString("id"),1);
        var captured=source();
        assertEquals("member-review",captured.baseModel().model);
        assertEquals("member-default",captured.models().get("author").model);
        assertEquals("member-default",settings.get().model);
        JSONObject planned=TeamParentPlan.withWorkProfileModels(plan,captured).view();
        assertEquals("member-review",planned.getJSONObject("definition").getJSONObject("nativeWorkProfileModels").getJSONObject("globalModelDefault").getString("model"));
        assertEquals("MEMBER.review",planned.getJSONObject("definition").getJSONArray("members").getJSONObject(1).getJSONObject("memberModelSelection").getJSONObject("memberRoute").getString("purpose"));
    }

    @Test public void completedPlanningOperationIsNotAnExecutionSourceOrApproval() throws Exception {
        capture(false, true);
        var captured = source();
        tasks.journal(task.getString("id"), task.getString("runId"), task.getInt("coordinatorEpoch"), original)
                .finishOperation(operation, new JSONObject().put("ok", true).put("sourceOnly", true));
        assertThrows(SecurityException.class, captured::models);
        assertEquals(0, teams.snapshot().getJSONArray("runs").length());
    }
}
