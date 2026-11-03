package dev.pocketengine.phone;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QuickPanelDraftStoreTest {
    private File directory() throws Exception { return Files.createTempDirectory("quick-panel-draft-").toFile(); }
    private QuickPanelDraftStore store(File root) { return new QuickPanelDraftStore(root, "original-task", "original-run", 1); }
    private File record(File root) { File[] files = new File(root, "quick-panel-drafts").listFiles(); assertNotNull(files); assertEquals(1, files.length); return files[0]; }

    @Test public void textSurvivesRecreationOnlyForTheSameOriginalTaskRunAndEpoch() throws Exception {
        File root = directory(); QuickPanelDraftStore first = store(root); first.load();
        first.saveNote("  未发送的中文说明\n保留空格\uD83D\uDE00  ");
        QuickPanelDraftStore.State restored = store(root).load();
        assertEquals("  未发送的中文说明\n保留空格\uD83D\uDE00  ", restored.note);
        assertTrue(restored.pendingClient.isEmpty());
        assertEquals("", new QuickPanelDraftStore(root, "different-task", "original-run", 1).load().note);
        assertEquals("", new QuickPanelDraftStore(root, "original-task", "different-run", 1).load().note);
        assertEquals("", new QuickPanelDraftStore(root, "original-task", "original-run", 2).load().note);
        QuickPanelDraftStore clearing = store(root); clearing.load(); clearing.saveNote("");
        assertEquals("", store(root).load().note);
    }

    @Test public void anotherWindowCannotOverwriteANewerRevisionOrAChangedSameRevision() throws Exception {
        File root = directory(); QuickPanelDraftStore a = store(root), b = store(root); a.load(); b.load();
        a.saveNote("newer");
        try { b.saveNote("old"); fail("stale window overwrote text"); } catch (IllegalStateException expected) { }
        assertEquals("newer", store(root).load().note);
        JSONObject changed = JsonFiles.read(record(root)).put("note", "independent edit at same revision");
        JsonFiles.write(record(root), changed);
        try { a.saveNote("old again"); fail("same-revision changed bytes were overwritten"); } catch (IllegalStateException expected) { }
        assertEquals("independent edit at same revision", store(root).load().note);
    }

    @Test public void unknownSubmissionSurvivesRecreationAndOnlyItsExactAcceptedReceiptClearsIt() throws Exception {
        File root = directory(); QuickPanelDraftStore original = store(root); original.load();
        String client = UUID.randomUUID().toString(); original.prepareSubmission("实际发送的说明", client);
        QuickPanelDraftStore reopened = store(root); QuickPanelDraftStore.State saved = reopened.load();
        assertEquals(client, saved.pendingClient); assertEquals("实际发送的说明", saved.note);
        try { reopened.saveNote("must not implicitly resend"); fail(); } catch (IllegalStateException expected) { }
        try { reopened.prepareSubmission(saved.note, UUID.randomUUID().toString()); fail(); } catch (IllegalStateException expected) { }
        try { reopened.accepted(UUID.randomUUID().toString()); fail(); } catch (IllegalStateException expected) { }
        assertEquals(client, store(root).load().pendingClient);
        reopened.accepted(client);
        QuickPanelDraftStore.State cleared = store(root).load(); assertEquals("", cleared.note); assertEquals("", cleared.pendingClient);
    }

    @Test public void definitePreDispatchRejectionRetainsTheDraftAndOriginalSendLimit() throws Exception {
        File root = directory(); QuickPanelDraftStore draft = store(root); draft.load();
        String client = UUID.randomUUID().toString(); draft.prepareSubmission("改好再发送", client);
        QuickPanelDraftStore.State retained = draft.rejectedBeforeDispatch(client);
        assertEquals("改好再发送", retained.note); assertEquals("", retained.pendingClient);
        draft.saveNote("字".repeat(1501));
        try { draft.prepareSubmission("字".repeat(1501), UUID.randomUUID().toString()); fail("1500-character send limit changed"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(1501, store(root).load().note.length());
    }

    @Test public void corruptOrDeletedOriginalFilesAreNotResetToEmptyDrafts() throws Exception {
        File root = directory(); QuickPanelDraftStore draft = store(root); draft.load(); draft.saveNote("keep");
        File original = record(root); Files.writeString(original.toPath(), "not valid json");
        try { store(root).load(); fail("corrupt file silently reset"); } catch (Exception expected) { }
        assertEquals("not valid json", Files.readString(original.toPath()));
        Files.delete(original.toPath());
        try { draft.saveNote("replacement"); fail("missing original silently replaced"); } catch (IllegalStateException expected) { }
        assertFalse(original.exists());
    }

    @Test public void invalidTextDoesNotReplaceConfirmedTextAndCanBeCorrectedLocally() throws Exception {
        File root = directory(); QuickPanelDraftStore draft = store(root); draft.load(); draft.saveNote("confirmed");
        for (String bad : new String[]{"x".repeat(8193), "broken\uD800"}) {
            try { draft.saveNote(bad); fail("invalid draft saved"); } catch (IllegalArgumentException expected) { }
            assertEquals("confirmed", store(root).load().note);
        }
        draft.saveNote("corrected"); assertEquals("corrected", store(root).load().note);
    }
}
