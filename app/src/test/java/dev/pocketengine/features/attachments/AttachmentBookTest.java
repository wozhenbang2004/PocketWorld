package dev.pocketengine.features.attachments;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AttachmentBookTest {
    private AttachmentBook book(long single,long total)throws Exception{return new AttachmentBook(Files.createTempDirectory("pocket-attachment-").toFile(),single,total);}
    @Test public void importedBytesSurviveReopenWithoutExposingSourceUri()throws Exception{
        File root=Files.createTempDirectory("pocket-attachment-").toFile();AttachmentBook first=new AttachmentBook(root);byte[] actual="真实附件\nhello".getBytes(StandardCharsets.UTF_8);
        JSONObject saved=first.importBytes(new ByteArrayInputStream(actual),"../../note.txt","text/plain",new JSONObject().put("kind","content_uri").put("uri","content://secret/path"));
        AttachmentBook reopened=new AttachmentBook(root);ByteArrayOutputStream export=new ByteArrayOutputStream();assertEquals(actual.length,reopened.exportTo(saved.getString("id"),export));assertArrayEquals(actual,export.toByteArray());assertEquals(1,reopened.list().length());assertEquals("真实附件\nhello",reopened.readText(saved.getString("id")).getString("text"));assertFalse(reopened.get(saved.getString("id")).toString().contains("content://secret"));assertFalse(saved.getString("name").contains("/"));assertEquals(64,saved.getString("sha256").length());
    }
    @Test public void lyingOrUnknownLengthSourcesCannotExceedQuota()throws Exception{
        AttachmentBook files=book(4,6);assertThrows(IllegalArgumentException.class,()->files.importBytes(new ByteArrayInputStream(new byte[5]),"a","application/octet-stream",null));assertEquals(0,files.list().length());
        files.importBytes(new ByteArrayInputStream(new byte[4]),"a","application/octet-stream",null);assertThrows(IllegalArgumentException.class,()->files.importBytes(new ByteArrayInputStream(new byte[3]),"b","application/octet-stream",null));assertEquals(1,files.list().length());
    }
    @Test public void failedReadLeavesNoCommittedAttachmentOrPartialFile()throws Exception{
        File root=Files.createTempDirectory("pocket-attachment-").toFile();AttachmentBook files=new AttachmentBook(root);InputStream failing=new InputStream(){int reads;@Override public int read()throws java.io.IOException{if(++reads>3)throw new java.io.IOException("source disconnected");return 1;}};
        assertThrows(java.io.IOException.class,()->files.importBytes(failing,"a","text/plain",null));assertEquals(0,files.list().length());assertEquals(0,root.listFiles().length);
    }
    @Test public void identifiersCannotAddressHostFilesAndBinaryIsNotPreviewed()throws Exception{
        AttachmentBook files=book(64,128);JSONObject saved=files.importBytes(new ByteArrayInputStream(new byte[]{0,(byte)255}),"a","application/octet-stream",null);
        assertThrows(IllegalArgumentException.class,()->files.file("../../tasks.json"));assertThrows(Exception.class,()->files.readText(saved.getString("id")));files.remove(saved.getString("id"));assertEquals(0,files.list().length());assertThrows(IllegalArgumentException.class,()->files.get(saved.getString("id")));
    }
    @Test public void crashOrphansStillCountTowardDiskQuota()throws Exception{
        File root=Files.createTempDirectory("pocket-attachment-").toFile();Files.write(new File(root,"prior.part").toPath(),new byte[5]);AttachmentBook files=new AttachmentBook(root,4,6);
        assertThrows(IllegalArgumentException.class,()->files.importBytes(new ByteArrayInputStream(new byte[2]),"a","text/plain",null));assertThrows(IllegalStateException.class,files::list);assertEquals(1,files.inspectIndex().getJSONObject("indexDiagnostics").getInt("unindexedOriginals"));
    }
}
