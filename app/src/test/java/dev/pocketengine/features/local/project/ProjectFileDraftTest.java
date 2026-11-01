package dev.pocketengine.features.local.project;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectFileDraftTest {
    static JSONObject review(String text) throws Exception {
        return new JSONObject().put("projectId", "project").put("checkoutId", "checkout").put("path", "src/main.js")
            .put("content", text).put("expectedHash", ProjectFileDraft.digest(text)).put("expectedVersion", 3).put("importable", true);
    }
    static ProjectFileDraft original() throws Exception { return ProjectFileDraft.empty("project", "checkout", "src/main.js", false).observe(review("base\n")); }

    @Test public void externalChangePreservesMineAndRequiresExplicitFreshBaselineBeforeSave() throws Exception {
        ProjectFileDraft mine = original().edit("my unsaved change\n");
        ProjectFileDraft.SaveIntent stale = mine.saveIntent();
        ProjectFileDraft changed = mine.observe(review("agent wrote new bytes\n"));
        assertTrue(changed.conflict()); assertEquals(mine.text, changed.text); assertEquals(mine.baseHash, changed.baseHash);
        try { changed.saveIntent(); fail("Read alone must not authorize replacement of new bytes"); } catch (IllegalStateException expected) {}
        ProjectFileDraft retained = changed.keepMineAgainstLatest();
        assertTrue(retained.dirty()); assertFalse(retained.conflict()); assertEquals(mine.text, retained.text);
        assertEquals(ProjectFileDraft.digest("agent wrote new bytes\n"), retained.saveIntent().expectedHash);
        assertEquals(ProjectFileDraft.digest("base\n"), stale.expectedHash);
        assertFalse(retained.toJson().has("expectedVersion")); assertFalse(retained.toJson().has("approval"));
    }
    @Test public void restoredTextHasNoFreshReviewOrRestoredGrantAndCannotSaveUntilRead() throws Exception {
        ProjectFileDraft owner = original().edit("draft survives leaving preview\n");
        JSONObject cache = owner.toJson().put("approval", true).put("expectedVersion", 999).put("latest", review("forged"));
        ProjectFileDraft restored = ProjectFileDraft.restore(cache, "project", "checkout", "src/main.js");
        assertEquals(owner.text, restored.text); assertNull(restored.latestReview()); assertTrue(restored.dirty());
        try { restored.saveIntent(); fail("Cached baseline is not a fresh read"); } catch (IllegalStateException expected) {}
        restored = restored.observe(review("base\n"));
        assertEquals(owner.text, restored.saveIntent().text); assertFalse(restored.toJson().has("approval"));
    }
    @Test public void exactWriteReceiptNeverClearsTypingThatHappenedAfterSubmission() throws Exception {
        ProjectFileDraft before = original().edit("G1"); ProjectFileDraft.SaveIntent sent = before.saveIntent();
        ProjectFileDraft later = before.edit("G2 later draft");
        JSONObject actual = new JSONObject().put("checkoutId", "checkout").put("path", "src/main.js").put("content", "G1").put("sha256", ProjectFileDraft.digest("G1"));
        ProjectFileDraft received = later.saved(sent, actual);
        assertEquals("G2 later draft", received.text); assertEquals("G1", received.baseContent); assertTrue(received.dirty());
        try { later.saved(sent, new JSONObject(actual.toString()).put("content", "other")); fail("Unrelated receipt cleared owner draft"); } catch (IllegalArgumentException expected) {}
        assertEquals("G2 later draft", later.text);
    }
    @Test public void staleSaveReceiptCannotUndoAnExplicitNewerConflictDecision() throws Exception {
        ProjectFileDraft mine = original().edit("my value"); ProjectFileDraft.SaveIntent sent = mine.saveIntent();
        ProjectFileDraft newer = mine.observe(review("third-party value")).keepMineAgainstLatest();
        JSONObject delayed = new JSONObject().put("checkoutId", "checkout").put("path", "src/main.js").put("content", sent.text).put("sha256", ProjectFileDraft.digest(sent.text));
        try { newer.saved(sent, delayed); fail("Late receipt erased newer comparison baseline"); } catch (IllegalArgumentException expected) {}
        assertEquals("third-party value", newer.baseContent); assertEquals("my value", newer.text);
    }
    @Test public void corruptAndForeignRecoveryIsRejectedRatherThanFallingBackToCurrentFile() throws Exception {
        JSONObject record = original().edit("precious owner text").toJson();
        for (JSONObject invalid : new JSONObject[]{new JSONObject(record.toString()).put("projectId", "other"), new JSONObject(record.toString()).put("checkoutId", "other"), new JSONObject(record.toString()).put("baseContent", "corrupt"), new JSONObject(record.toString()).put("text", "silently changed")}) {
            try { ProjectFileDraft.restore(invalid, "project", "checkout", "src/main.js"); fail("Invalid draft accepted"); } catch (IllegalArgumentException expected) {}
        }
        for (String path : new String[]{"../secret", "/absolute", "a//b", "a/../b", "a\\b"}) {
            try { ProjectFileDraft.empty("project", "checkout", path, true); fail("Escaping draft path accepted"); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void newFileUsesAbsentCasAndReadDoesNotSilentlyOverwriteItsDraftOnCollision() throws Exception {
        ProjectFileDraft fresh = ProjectFileDraft.empty("project", "checkout", "src/main.js", true).edit("my new file");
        assertEquals("absent", fresh.saveIntent().expectedHash);
        ProjectFileDraft collision = fresh.observe(review("someone created this path"));
        assertTrue(collision.conflict()); assertEquals("my new file", collision.text);
        assertEquals("someone created this path", collision.useLatest().text); assertFalse(collision.useLatest().dirty());
    }
}
