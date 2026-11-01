package dev.pocketengine.features.local.project;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual LocalModule/VersionedWorkspace CAS and immutable preview bytes, not Compose/device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectFileDraftJourneyTest {
    @Test public void originalFileDraftSurvivesPreviewReturnStaleCasReadLatestExplicitSaveAndNewPreview() throws Exception {
        Context android = RuntimeEnvironment.getApplication(); FeatureContext context = new FeatureContext(android, null, null, event -> {});
        WorkspaceModule workspace = new WorkspaceModule(context);
        String project = workspace.handle("create", new JSONObject().put("name", "原项目文件草稿")).getString("id");
        for (String[] source : new String[][]{{"index.html", "<link rel=stylesheet href=style.css><script src=app.js></script><h1>base</h1>"}, {"style.css", "h1{color:black}"}, {"app.js", "globalThis.version='original';"}})
            workspace.handle("write", new JSONObject().put("projectId", project).put("path", source[0]).put("content", source[1]).put("expectedVersion", 0));
        File cache = Files.createTempDirectory("project-file-draft-journey-").toFile();
        try (LocalModule local = new LocalModule(context, workspace)) {
            String checkout = local.handle("prepare", new JSONObject().put("projectId", project)).getString("id");
            JSONObject oldPreviewMeta = preview(local, checkout); ProjectPreviewBundle.Verified oldPreview = local.projectPreviewForOwner(oldPreviewMeta.getString("previewId"), checkout);
            byte[] oldScript = oldPreview.resource(oldPreview.origin + "app.js").bytes();
            ProjectFileDraftStore files = new ProjectFileDraftStore(cache, Runnable::run);
            ProjectFileDraft draft = files.open(project, checkout, "app.js", false).observe(local.handle("reviewFile", input(checkout)));
            draft = draft.edit("globalThis.version='my unsaved file draft';"); files.retain(draft);
            ProjectFileDraft.SaveIntent originalSave = draft.saveIntent();
            // A different original project operation writes current bytes while the owner views the fixed preview.
            JSONObject beforeAgent = local.handle("readFile", input(checkout));
            local.handle("writeFile", input(checkout).put("content", "globalThis.version='other project operation';").put("expectedHash", beforeAgent.getString("sha256")));
            assertArrayEquals(oldScript, oldPreview.resource(oldPreview.origin + "app.js").bytes());
            ProjectFileDraft recovered = new ProjectFileDraftStore(cache, Runnable::run).open(project, checkout, "app.js", false);
            assertEquals(draft.text, recovered.text); assertEquals(originalSave.expectedHash, recovered.baseHash);
            try { local.handle("writeFile", originalSave.input()); fail("Original stale CAS overwrote new active bytes"); } catch (IllegalStateException expected) {}
            assertEquals("globalThis.version='other project operation';", local.handle("readFile", input(checkout)).getString("content"));
            recovered = recovered.observe(local.handle("reviewFile", input(checkout)));
            assertTrue(recovered.conflict()); assertEquals(draft.text, recovered.text);
            try { recovered.saveIntent(); fail("Reading latest silently authorized a write"); } catch (IllegalStateException expected) {}
            assertEquals("globalThis.version='original';", workspace.handle("read", new JSONObject().put("projectId", project).put("path", "app.js")).getString("content"));
            // These are two distinct owner actions: keep mine against the displayed fresh baseline, then save.
            recovered = recovered.keepMineAgainstLatest(); ProjectFileDraft.SaveIntent explicitSave = recovered.saveIntent();
            JSONObject actualWrite = local.handle("writeFile", explicitSave.input()); recovered = recovered.saved(explicitSave, actualWrite);
            files.retain(recovered); assertFalse(recovered.dirty());
            JSONObject review = local.handle("reviewFile", input(checkout)); assertEquals(draft.text, review.getString("content"));
            JSONObject newPreviewMeta = preview(local, checkout); ProjectPreviewBundle.Verified newPreview = local.projectPreviewForOwner(newPreviewMeta.getString("previewId"), checkout);
            assertNotEquals(oldPreview.identity, newPreview.identity); assertEquals(3, newPreview.manifest().length());
            assertEquals(draft.text, new String(newPreview.resource(newPreview.origin + "app.js").bytes(), StandardCharsets.UTF_8));
            assertArrayEquals(oldScript, oldPreview.resource(oldPreview.origin + "app.js").bytes());
            // Saved-project history remains a separate explicit action with the exact reviewed version/hash.
            local.handle("importToProject", review);
            assertEquals(draft.text, workspace.handle("read", new JSONObject().put("projectId", project).put("path", "app.js")).getString("content"));
            assertTrue(new ProjectFileDraftStore(cache, Runnable::run).dirtyPaths(project, checkout).isEmpty());
            System.out.println("PROJECT_FILE_DRAFT_JOURNEY original checkout=" + checkout + "; stale CAS rejected, text retained, explicit latest baseline/save, three-resource preview changed while old bytes fixed; no execution/grant restored");
        } finally { try (var paths = Files.walk(cache.toPath())) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static JSONObject input(String checkout) throws Exception { return new JSONObject().put("checkoutId", checkout).put("path", "app.js"); }
    private static JSONObject preview(LocalModule local, String checkout) throws Exception {
        String source = ProjectSources.describe(local.handle("export", new JSONObject().put("checkoutId", checkout))).getString("sourceDigest");
        return local.handle("project.preview", new JSONObject().put("checkoutId", checkout).put("entryPath", "index.html").put("expectedSourceDigest", source));
    }
}
