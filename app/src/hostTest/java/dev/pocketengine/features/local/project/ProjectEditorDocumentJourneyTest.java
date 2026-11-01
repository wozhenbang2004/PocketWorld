package dev.pocketengine.features.local.project;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
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

/** Original file draft/store/local/workspace chain. Not Compose selection, IME, scroll or device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
public final class ProjectEditorDocumentJourneyTest {
    @Test public void literalFindReplacementColdDraftExternalConflictExplicitCasSaveAndNewPreviewKeepMixedNewlines() throws Exception {
        Context android = RuntimeEnvironment.getApplication(); FeatureContext context = new FeatureContext(android, null, null, event -> {});
        WorkspaceModule workspace = new WorkspaceModule(context);
        String project = workspace.handle("create", new JSONObject().put("name", "代码定位与查替")).getString("id");
        String source = "// 中文 😀 原注释\r\nconst amount = 40.00;\r\n// display 40.00\n";
        for (String[] file : new String[][]{{"index.html", "<h1>金额</h1><script src=app.js></script>"}, {"app.js", source}})
            workspace.handle("write", new JSONObject().put("projectId", project).put("path", file[0]).put("content", file[1]).put("expectedVersion", 0));
        File cache = Files.createTempDirectory("c04-file-editor-draft-").toFile();
        try (LocalModule local = new LocalModule(context, workspace)) {
            String checkout = local.handle("prepare", new JSONObject().put("projectId", project)).getString("id");
            ProjectFileDraftStore store = new ProjectFileDraftStore(cache, Runnable::run);
            ProjectFileDraft draft = store.open(project, checkout, "app.js", false).observe(local.handle("reviewFile", input(checkout)));
            ProjectEditorDocument original = new ProjectEditorDocument(draft.text);
            assertEquals(4, original.lineCount()); int businessLine = original.lineStart(2), commentLine = original.lineStart(3);
            ProjectEditorDocument.Search scoped = original.search("40.00", true, businessLine, commentLine);
            assertEquals(1, scoped.totalMatches); assertEquals(2, original.lineAt(scoped.match(0).start));
            ProjectEditorDocument.Replacement replacement = ProjectEditorDocument.replaceAll(scoped, "45.00", draft.text, () -> {});
            draft = draft.edit(replacement.text); store.retain(draft);
            assertEquals(source, local.handle("readFile", input(checkout)).getString("content"));
            assertTrue(draft.text.contains("const amount = 45.00;\r\n// display 40.00\n"));
            ProjectFileDraft.SaveIntent stale = draft.saveIntent();
            JSONObject oldPreview = preview(local, checkout); var fixed = local.projectPreviewForOwner(oldPreview.getString("previewId"), checkout);
            byte[] before = fixed.resource(fixed.origin + "app.js").bytes(); assertEquals(source, new String(before, StandardCharsets.UTF_8));
            String external = source.replace("40.00", "42.00");
            local.handle("writeFile", input(checkout).put("content", external).put("expectedHash", stale.expectedHash));
            ProjectFileDraft cold = new ProjectFileDraftStore(cache, Runnable::run).open(project, checkout, "app.js", false);
            assertEquals(draft.text, cold.text); assertNull(cold.latestReview());
            try { local.handle("writeFile", stale.input()); fail("Stale editor draft overwrote external file"); } catch (IllegalStateException expected) {}
            cold = cold.observe(local.handle("reviewFile", input(checkout))); assertTrue(cold.conflict()); assertEquals(draft.text, cold.text);
            try { cold.saveIntent(); fail("Read latest silently authorized replacement"); } catch (IllegalStateException expected) {}
            try { ProjectEditorDocument.replace(scoped, 0, "99", external); fail("Old search result edited changed bytes"); } catch (IllegalStateException expected) {}
            cold = cold.keepMineAgainstLatest(); ProjectFileDraft.SaveIntent explicit = cold.saveIntent();
            assertEquals(ProjectFileDraft.digest(external), explicit.expectedHash);
            cold = cold.saved(explicit, local.handle("writeFile", explicit.input())); store.retain(cold); assertFalse(cold.dirty());
            assertEquals(draft.text, local.handle("readFile", input(checkout)).getString("content"));
            assertEquals(source, workspace.handle("read", new JSONObject().put("projectId", project).put("path", "app.js")).getString("content"));
            JSONObject review = local.handle("reviewFile", input(checkout)); local.handle("importToProject", review);
            assertEquals(draft.text, workspace.handle("read", new JSONObject().put("projectId", project).put("path", "app.js")).getString("content"));
            JSONObject newPreview = preview(local, checkout); var changed = local.projectPreviewForOwner(newPreview.getString("previewId"), checkout);
            assertNotEquals(fixed.identity, changed.identity); assertEquals(draft.text, new String(changed.resource(changed.origin + "app.js").bytes(), StandardCharsets.UTF_8));
            assertArrayEquals(before, fixed.resource(fixed.origin + "app.js").bytes());
            assertTrue(new ProjectFileDraftStore(cache, Runnable::run).dirtyPaths(project, checkout).isEmpty());
            System.out.println("C04_EDITOR original file -> Unicode/mixed-newline line lookup -> literal selected-range replacement only draft -> durable cold draft/old fixed preview -> external write/stale CAS rejected -> fresh conflict keeps mine -> explicit baseline/save/import -> new preview; original search stale rejected. Host only; Compose IME/selection/scroll and device not run.");
        } finally { try (var paths = Files.walk(cache.toPath())) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static JSONObject input(String checkout) throws Exception { return new JSONObject().put("checkoutId", checkout).put("path", "app.js"); }
    private static JSONObject preview(LocalModule local, String checkout) throws Exception {
        String source = ProjectSources.describe(local.handle("export", new JSONObject().put("checkoutId", checkout))).getString("sourceDigest");
        return local.handle("project.preview", new JSONObject().put("checkoutId", checkout).put("entryPath", "index.html").put("expectedSourceDigest", source));
    }
}
