package dev.pocketengine.features.attachments;

import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;

public class AttachmentImportRecoveryTest {
    @Test public void abruptImportCannotMakeAnOrphanLookLikeACompleteEmptyIndex() throws Exception {
        File root = Files.createTempDirectory("attachment-abrupt-").toFile();
        AttachmentBook book = new AttachmentBook(root);
        InputStream input = new InputStream() {
            int calls;
            public int read() { throw new AssertionError("use bulk read"); }
            public int read(byte[] buffer, int offset, int length) {
                if (calls++ > 0) throw new LinkageError("simulated process termination before publication");
                buffer[offset] = 42; return 1;
            }
        };
        assertThrows(LinkageError.class, () -> book.importBytes(input, "original.txt", "text/plain", new JSONObject()));
        JSONObject view = new AttachmentBook(root).inspectIndex();
        assertTrue("unindexed bytes must stay visibly unresolved", view.getJSONObject("indexDiagnostics").getBoolean("incomplete"));
        assertEquals(0, view.getJSONArray("attachments").length());
        JSONObject recovery = new AttachmentBook(root).recoverInterruptedImports();
        assertEquals(1, recovery.getInt("discardedUnpublishedImports"));
        assertEquals(0, root.listFiles().length);
        assertFalse(new AttachmentBook(root).inspectIndex().getJSONObject("indexDiagnostics").getBoolean("incomplete"));
    }

    private record Saved(File root, String id, JSONObject meta, byte[] bytes) {
        File file(String suffix) { return new File(root, id + suffix); }
        void ready() throws Exception {
            Files.writeString(file(".import").toPath(), new JSONObject().put("kind", "ordinary_attachment_import_v1")
                    .put("id", id).put("phase", "ready").put("metadata", meta).toString());
        }
    }
    private Saved saved() throws Exception {
        File root = Files.createTempDirectory("attachment-ready-").toFile();
        byte[] bytes = "original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JSONObject meta = new AttachmentBook(root).importBytes(new ByteArrayInputStream(bytes), "note.txt", "text/plain", new JSONObject().put("kind", "shared_text"));
        return new Saved(root, meta.getString("id"), meta, bytes);
    }
    @Test public void readyPartCompletesUsingOnlySavedBytesAndMetadata() throws Exception {
        Saved saved = saved(); saved.ready();
        Files.delete(saved.file(".json").toPath()); Files.move(saved.file(".blob").toPath(), saved.file(".part").toPath());
        AttachmentBook reopened = new AttachmentBook(saved.root);
        assertEquals(1, reopened.recoverInterruptedImports().getInt("completedImports"));
        assertArrayEquals(saved.bytes, Files.readAllBytes(saved.file(".blob").toPath()));
        assertEquals(saved.meta.toString(), reopened.get(saved.id).toString());
        assertEquals(1, reopened.list().length()); assertEquals(2, saved.root.listFiles().length);
        assertEquals(0, reopened.recoverInterruptedImports().getInt("completedImports"));
    }
    @Test public void blobWithTruncatedPendingIndexCompletesFromDurableIntent() throws Exception {
        Saved saved = saved(); saved.ready(); Files.delete(saved.file(".json").toPath());
        Files.writeString(saved.file(".json.pending").toPath(), "{\"id\":");
        AttachmentBook reopened = new AttachmentBook(saved.root);
        assertEquals(1, reopened.recoverInterruptedImports().getInt("completedImports"));
        assertEquals("original bytes", reopened.readText(saved.id).getString("text"));
        assertFalse(saved.file(".json.pending").exists()); assertFalse(saved.file(".import").exists());
    }
    @Test public void committedIndexOnlyRemovesItsMatchingLeftoverIntent() throws Exception {
        Saved saved = saved(); saved.ready(); byte[] record = Files.readAllBytes(saved.file(".json").toPath());
        assertEquals(1, new AttachmentBook(saved.root).recoverInterruptedImports().getInt("completedImports"));
        assertArrayEquals(record, Files.readAllBytes(saved.file(".json").toPath()));
        assertArrayEquals(saved.bytes, Files.readAllBytes(saved.file(".blob").toPath()));
        assertEquals(2, saved.root.listFiles().length);
    }
    @Test public void changedBytesOrRetiredMetadataArePreservedWithoutPublication() throws Exception {
        Saved changed = saved(); changed.ready(); Files.delete(changed.file(".json").toPath());
        byte[] different = changed.bytes.clone(); different[0] ^= 1; Files.write(changed.file(".blob").toPath(), different);
        AttachmentBook reopened = new AttachmentBook(changed.root);
        assertEquals(1, reopened.recoverInterruptedImports().getInt("retainedImports"));
        assertFalse(changed.file(".json").exists()); assertArrayEquals(different, Files.readAllBytes(changed.file(".blob").toPath()));
        assertNull(reopened.outputSourceEligibility(changed.id, changed.meta.getString("sha256")));
        Saved retired = saved(); retired.ready();
        Files.writeString(retired.file(".json").toPath(), new JSONObject(retired.meta.toString()).put("outputSourceRetired", true).toString());
        assertEquals(1, new AttachmentBook(retired.root).recoverInterruptedImports().getInt("retainedImports"));
        assertTrue(new JSONObject(Files.readString(retired.file(".json").toPath())).getBoolean("outputSourceRetired"));
        assertTrue(retired.file(".import").exists());
    }
    @Test public void fixedNativePublicationAndLegacyOrphansAreNeverReplayed() throws Exception {
        File root = Files.createTempDirectory("attachment-unknown-").toFile();
        String id = UUID.nameUUIDFromBytes(new byte[]{7}).toString();
        Files.writeString(new File(root, id + ".import").toPath(), new JSONObject().put("kind", "ordinary_attachment_import_v1").put("id", id).put("phase", "receiving").toString());
        Files.write(new File(root, id + ".part").toPath(), new byte[]{7});
        Files.write(new File(root, "legacy.blob").toPath(), new byte[]{8});
        AttachmentBook book = new AttachmentBook(root);
        assertEquals(1, book.recoverInterruptedImports().getInt("retainedImports"));
        assertEquals(3, root.listFiles().length);
        assertTrue(book.inspectIndex().getJSONObject("indexDiagnostics").getBoolean("incomplete"));
        assertThrows(IllegalStateException.class, book::list);
    }
    @Test public void recoveryCannotDeleteAnImportOwnedByAnotherBook() throws Exception {
        File root = Files.createTempDirectory("attachment-live-").toFile();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(() -> new AttachmentBook(root).importBytes(new InputStream() {
                int calls;
                public int read() { throw new AssertionError(); }
                public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                    if (calls++ > 0) return -1;
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); }
                    catch (InterruptedException stopped) { throw new java.io.IOException(stopped); }
                    bytes[offset] = 9; return 1;
                }
            }, "active.txt", "text/plain", null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(new AttachmentBook(root).recoverInterruptedImports().getBoolean("deferred"));
            assertEquals(2, root.listFiles().length);
            release.countDown(); JSONObject saved = future.get(5, TimeUnit.SECONDS);
            assertEquals(1, new AttachmentBook(root).list().length()); assertEquals(1, saved.getLong("size"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test public void inspectionAndRecoveryOfAnAbsentRootDoNotCreateFiles() throws Exception {
        File parent = Files.createTempDirectory("attachment-missing-").toFile(), root = new File(parent, "attachments");
        AttachmentBook book = new AttachmentBook(root);
        assertFalse(book.inspectIndex().getJSONObject("indexDiagnostics").getBoolean("incomplete"));
        assertEquals(0, book.recoverInterruptedImports().getInt("completedImports"));
        assertEquals(0, parent.listFiles().length);
    }
    @Test public void ownerRemovalClearsAnImportWhosePublicationCleanupWasInterrupted() throws Exception {
        Saved saved = saved(); saved.ready();
        AttachmentBook book = new AttachmentBook(saved.root); book.remove(saved.id);
        assertEquals(0, saved.root.listFiles().length);
        assertEquals(0, book.recoverInterruptedImports().getInt("retainedImports"));
        assertEquals(0, book.list().length());
    }
    @Test public void interruptedExplicitRemovalCompletesWithoutResurrectingAnImport() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            Saved saved = saved();
            JSONObject retired = new JSONObject(saved.meta.toString()).put("outputSourceRetired", true);
            Files.writeString(saved.file(".import").toPath(), new JSONObject().put("kind", "ordinary_attachment_import_v1")
                    .put("id", saved.id).put("phase", "retired").put("metadata", retired).toString());
            if (stage >= 1) { Files.writeString(saved.file(".json").toPath(), retired.toString()); Files.delete(saved.file(".blob").toPath()); }
            if (stage >= 2) Files.delete(saved.file(".json").toPath());
            AttachmentBook book = new AttachmentBook(saved.root);
            assertEquals(1, book.recoverInterruptedImports().getInt("removedAttachments"));
            assertEquals(0, saved.root.listFiles().length); assertEquals(0, book.list().length());
        }
    }
    @Test public void retiredIntentCannotDeleteAnAttachmentWhoseScopeChanged() throws Exception {
        Saved saved = saved();
        Files.writeString(saved.file(".import").toPath(), new JSONObject().put("kind", "ordinary_attachment_import_v1")
                .put("id", saved.id).put("phase", "retired").put("metadata", new JSONObject(saved.meta.toString()).put("outputSourceRetired", true)).toString());
        JSONObject changed = new JSONObject(saved.meta.toString()).put("source", new JSONObject().put("kind", "group_input").put("groupSpaceId", "original-group"));
        Files.writeString(saved.file(".json").toPath(), changed.toString());
        assertEquals(1, new AttachmentBook(saved.root).recoverInterruptedImports().getInt("retainedImports"));
        assertEquals(changed.toString(), Files.readString(saved.file(".json").toPath()));
        assertArrayEquals(saved.bytes, Files.readAllBytes(saved.file(".blob").toPath()));
    }
    @Test public void initialIntentTemporaryFileIsDiscardedOnlyWithoutPublicationEvidence() throws Exception {
        for (String partial : new String[]{"{", "{\"kind\":\"ordinary_attachment_import_v1\",\"phase\":\"receiving\"}"}) {
            File root = Files.createTempDirectory("attachment-initial-intent-").toFile(); String id = UUID.randomUUID().toString();
            Files.writeString(new File(root, id + ".import.pending").toPath(), partial);
            AttachmentBook book = new AttachmentBook(root);
            assertEquals(1, book.recoverInterruptedImports().getInt("discardedUnpublishedImports"));
            assertEquals(0, root.listFiles().length); assertEquals(0, book.list().length());
            Files.writeString(new File(root, id + ".import.pending").toPath(), partial);
            Files.write(new File(root, id + ".blob").toPath(), new byte[]{1});
            assertEquals(1, book.recoverInterruptedImports().getInt("retainedImports"));
            assertEquals(2, root.listFiles().length);
        }
    }
}
