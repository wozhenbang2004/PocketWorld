package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** The Book primitive proves canonical bytes/version, not task selection or external-upload authorization. */
public final class AttachmentVerifiedMetadataTest {
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    @Test public void typedMetadataAndOwnedSnapshotReferToTheSameActuallyHashedFile()throws Exception {
        AttachmentBook book=new AttachmentBook(directory.newFolder());byte[] original="canonical".getBytes(StandardCharsets.UTF_8);
        JSONObject stored=book.importBytes(new ByteArrayInputStream(original),"原来源.txt","text/plain",new JSONObject());RunControl live=new RunControl();
        AttachmentBook.VerifiedMetadata metadata=book.readVerifiedMetadata(stored.getString("id"),stored.getString("sha256"),live);
        assertEquals(stored.getString("id"),metadata.id);assertEquals(stored.getString("sha256"),metadata.sha256);assertEquals("原来源.txt",metadata.name);assertEquals("text/plain",metadata.mimeType);assertEquals(original.length,metadata.size);
        byte[] snapshot=book.readVerifiedSnapshot(metadata.id,metadata.sha256,live);assertArrayEquals(original,snapshot);snapshot[0]='X';assertArrayEquals(original,book.readVerifiedSnapshot(metadata.id,metadata.sha256,live));
        Files.write(book.file(metadata.id).toPath(),"malformed".getBytes(StandardCharsets.UTF_8));assertEquals(original.length,book.get(metadata.id).getLong("size"));
        assertThrows(AttachmentBook.InputChangedException.class,()->book.readVerifiedMetadata(metadata.id,metadata.sha256,live));assertThrows(AttachmentBook.InputChangedException.class,()->book.readVerifiedSnapshot(metadata.id,metadata.sha256,live));
    }
    @Test public void metadataCannotReturnAfterCancellationOrOriginalLiveAuthorityRevocation()throws Exception {
        AttachmentBook book=new AttachmentBook(directory.newFolder());JSONObject stored=book.importBytes(new ByteArrayInputStream(new byte[70000]),"bounded.bin","application/octet-stream",new JSONObject());
        RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->book.readVerifiedMetadata(stored.getString("id"),stored.getString("sha256"),cancelled));
        RunControl original=new RunControl();AtomicInteger checks=new AtomicInteger();original.setAuthorityCheck(()->{if(checks.incrementAndGet()>=3)throw new SecurityException("Original native task control revoked");});
        assertThrows(InterruptedException.class,()->book.readVerifiedMetadata(stored.getString("id"),stored.getString("sha256"),original));assertTrue(checks.get()>=3);
        assertTrue(book.file(stored.getString("id")).isFile());
    }
}
