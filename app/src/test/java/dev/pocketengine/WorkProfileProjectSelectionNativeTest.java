package dev.pocketengine;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectOwnerWorkflows;
import java.nio.file.Files;
import java.util.UUID;
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

/** Actual mounted original LocalModule/checkout and original SQL binding. No model, shell,
 * Team dispatch, new authorization or fake developmentContext JSON is used by this selection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileProjectSelectionNativeTest {
    private EngineApplication app;
    private LocalModule local;
    private ProjectOwnerWorkflows owner;
    private JSONObject checkout;

    @Before public void open() throws Exception {
        app = (EngineApplication) RuntimeEnvironment.getApplication();
        local = (LocalModule) app.features.module("local");
        var field = EngineApplication.class.getDeclaredField("projectOwnerWorkflows");
        field.setAccessible(true);
        owner = (ProjectOwnerWorkflows) field.get(app);
        var workspace = app.features.module("workspace");
        String project = workspace.handle("create", new JSONObject().put("name", "Original Profile project")).getString("id");
        workspace.handle("write", new JSONObject().put("projectId", project).put("path", "a.txt")
                .put("content", "ORIGINAL CHECKOUT SOURCE").put("expectedVersion", 0));
        checkout = local.openProjectForOwner(project);
    }

    private ProjectOwnerWorkflows.ProfileProjectSelection review() throws Exception {
        return owner.reviewWorkProfileProject(checkout.getString("projectId"), checkout.getString("id"), "managed_process_v1");
    }

    private JSONObject attempt(RunControl control) throws Exception {
        JSONObject task = app.tasks.createCapabilityTask("Original native zero-operation admission", "");
        app.tasks.configureExecutionScope(task.getString("id"), 0, System.currentTimeMillis() + 60000, 8, 4);
        JSONObject admitted = app.tasks.startAttempt(task.getString("id"), "capabilities", 8);
        control.bindHostRun(admitted.getString("id"), admitted.getString("runId"), admitted.getInt("coordinatorEpoch"));
        return admitted;
    }

    @After public void close() throws Exception { app.onTerminate(); app.features.close(); }

    @Test public void readOnlySelectionUsesTheRealCheckoutAndBindsOnlyTheOriginalPreparingRun() throws Exception {
        int before = app.tasks.list().length();
        var selected = review();
        JSONObject view = selected.view();
        assertEquals(before, app.tasks.list().length());
        assertFalse(view.getBoolean("executionAuthorized"));
        assertFalse(view.getBoolean("commandsAuthorized"));
        assertFalse(view.getBoolean("authorityAdded"));
        RunControl control = new RunControl();
        JSONObject task = attempt(control);
        JSONObject scope = app.tasks.get(task.getString("id"), false).getJSONObject("executionScope");
        owner.bindWorkProfileProject(selected, task, control);
        JSONObject bound = app.tasks.get(task.getString("id"), false);
        assertEquals(checkout.getString("id"), bound.getJSONObject("developmentContext").getString("checkoutId"));
        assertEquals(view.getString("initialSourceDigest"), bound.getJSONObject("developmentContext").getString("initialSourceDigest"));
        assertEquals(scope.toString(), bound.getJSONObject("executionScope").toString());
        assertEquals(0, bound.getJSONArray("operations").length());
        assertEquals(0, app.tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
        RunControl other = new RunControl();
        JSONObject another = attempt(other);
        assertThrows(SecurityException.class, () -> owner.bindWorkProfileProject(selected, another, other));
        assertFalse(app.tasks.get(another.getString("id"), false).has("developmentContext"));
    }

    @Test public void arbitraryProjectIdentityAndUnknownRuntimeCannotConstructAnOpaqueSelection() throws Exception {
        assertThrows(SecurityException.class, () -> owner.reviewWorkProfileProject(UUID.randomUUID().toString(), checkout.getString("id"), "managed_process_v1"));
        assertThrows(SecurityException.class, () -> owner.reviewWorkProfileProject(checkout.getString("projectId"), checkout.getString("id"), "invented_runtime"));
        assertEquals(0, app.tasks.list().length());
    }

    @Test public void actualCheckoutBytesChangedAfterReviewAreRejectedBeforeAnySqlBinding() throws Exception {
        var selected = review();
        var field = LocalModule.class.getDeclaredField("root");
        field.setAccessible(true);
        var root = ((java.io.File) field.get(local)).toPath();
        Files.write(root.resolve("checkouts").resolve(checkout.getString("id")).resolve("files/a.txt"), "EXTERNAL FIXTURE DRIFT".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RunControl control = new RunControl();
        JSONObject task = attempt(control);
        assertThrows(SecurityException.class, selected::checkCurrent);
        assertThrows(SecurityException.class, () -> owner.bindWorkProfileProject(selected, task, control));
        assertFalse(app.tasks.get(task.getString("id"), false).has("developmentContext"));
        assertEquals(0, app.tasks.get(task.getString("id"), false).getJSONArray("operations").length());
    }
}
