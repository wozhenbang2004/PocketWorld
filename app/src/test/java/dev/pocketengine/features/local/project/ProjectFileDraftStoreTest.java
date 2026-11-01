package dev.pocketengine.features.local.project;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ProjectFileDraftStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final class Queued implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        public void execute(Runnable work) { queue.add(work); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    @Test public void navigationRetainsLatestMemoryAndAtomicCacheReopensSameOriginalFile() throws Exception {
        File root = new File(temp.getRoot(), "private-drafts"); Queued io = new Queued(); ProjectFileDraftStore store = new ProjectFileDraftStore(root, io);
        ProjectFileDraft first = ProjectFileDraftTest.original().edit("unsaved G1"); store.retain(first);
        ProjectFileDraft later = first.edit("unsaved G2, not a workspace write"); store.retain(later);
        assertEquals(1, io.queue.size()); assertSame(later, store.open("project", "checkout", "src/main.js", false)); assertFalse(store.durable(later));
        io.drain(); assertTrue(store.durable(later)); assertNull(store.persistenceError(later));
        ProjectFileDraftStore reopened = new ProjectFileDraftStore(root, Runnable::run);
        ProjectFileDraft recovered = reopened.open("project", "checkout", "src/main.js", false);
        assertEquals(later.text, recovered.text); assertEquals(first.baseHash, recovered.baseHash); assertNull(recovered.latestReview());
        assertEquals(List.of("src/main.js"), reopened.dirtyPaths("project", "checkout"));
        assertTrue(reopened.dirtyPaths("project", "another-checkout").isEmpty());
        assertEquals("", reopened.open("other-project", "checkout", "src/main.js", false).text);
        assertFalse(new File(temp.getRoot(), "src/main.js").exists());
    }
    @Test public void diskFailureSurfacesKeepsMemoryAndExplicitRetryDoesNotWriteTheWorkspace() throws Exception {
        File root = new File(temp.getRoot(), "blocked-cache"); Files.writeString(root.toPath(), "fixture obstruction");
        Queued io = new Queued(); ProjectFileDraftStore store = new ProjectFileDraftStore(root, io);
        ProjectFileDraft precious = ProjectFileDraftTest.original().edit("not silently discarded"); store.retain(precious); io.drain();
        assertFalse(store.durable(precious)); assertNotNull(store.persistenceError(precious)); assertSame(precious, store.open("project", "checkout", "src/main.js", false));
        Files.delete(root.toPath()); store.retry(precious); io.drain(); assertTrue(store.durable(precious)); assertNull(store.persistenceError(precious));
        assertEquals(precious.text, new ProjectFileDraftStore(root, Runnable::run).open("project", "checkout", "src/main.js", false).text);
    }
    @Test public void corruptCacheRemainsOnDiskAndNeverFallsBackToEmptyText() throws Exception {
        File root = temp.newFolder("cache"); ProjectFileDraftStore store = new ProjectFileDraftStore(root, Runnable::run);
        store.retain(ProjectFileDraftTest.original().edit("my draft")); File[] files = root.listFiles(); assertNotNull(files); assertEquals(1, files.length);
        Files.writeString(files[0].toPath(), "{broken", StandardCharsets.UTF_8);
        try { new ProjectFileDraftStore(root, Runnable::run).open("project", "checkout", "src/main.js", false); fail("Corrupt draft hidden by empty fallback"); } catch (java.io.IOException expected) {}
        assertEquals("{broken", Files.readString(files[0].toPath()));
    }
    @Test public void olderEditorCannotReplaceNewerTextAndCleanReceiptRemovesOnlyItsOwnCache() throws Exception {
        File root = temp.newFolder("two-drafts"); ProjectFileDraftStore store = new ProjectFileDraftStore(root, Runnable::run);
        ProjectFileDraft base = ProjectFileDraftTest.original(), old = base.edit("old"), newer = old.edit("newer"); store.retain(newer);
        try { store.retain(old); fail("Old editor overwrote new draft"); } catch (IllegalStateException expected) {}
        ProjectFileDraft other = ProjectFileDraft.empty("project", "checkout", "other.js", true).edit("other draft"); store.retain(other);
        ProjectFileDraft clean = newer.edit(newer.baseContent); store.retain(clean); store.release(clean);
        assertEquals(List.of("other.js"), new ProjectFileDraftStore(root, Runnable::run).dirtyPaths("project", "checkout"));
        assertEquals("other draft", store.open("project", "checkout", "other.js", true).text);
    }
    @Test public void dirtyFileLimitRejectsAdditionalDraftWithoutDeletingExistingOnes() throws Exception {
        File root = temp.newFolder("bounded"); ProjectFileDraftStore store = new ProjectFileDraftStore(root, Runnable::run);
        for (int i = 0; i < ProjectFileDraftStore.MAX_DIRTY_FILES; i++) store.retain(ProjectFileDraft.empty("project", "checkout", "file" + i + ".js", true).edit("draft " + i));
        try { store.retain(ProjectFileDraft.empty("project", "checkout", "extra.js", true).edit("new text")); fail("Unbounded dirty file cache"); } catch (IllegalStateException expected) {}
        assertEquals(ProjectFileDraftStore.MAX_DIRTY_FILES, root.listFiles().length);
        assertEquals("draft 0", store.open("project", "checkout", "file0.js", false).text);
    }
}
