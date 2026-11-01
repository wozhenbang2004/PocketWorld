package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentFixtures;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Book, byte verification and document parser. No Android, fake source store or caller-supplied extract body. */
public final class AttachmentDocumentExtractTest {
    private static final class Fixture {
        final File root;final AttachmentBook book;final AttachmentDocuments documents;final JSONObject source;
        Fixture(byte[] bytes,DocumentReader reader)throws Exception {
            root=Files.createTempDirectory("document-extract-").toFile();book=new AttachmentBook(root);documents=new AttachmentDocuments(book,reader);
            source=book.importBytes(new ByteArrayInputStream(bytes),"原文件.txt","application/octet-stream",new JSONObject().put("kind","content_uri"));
        }
        Fixture()throws Exception{this("已经查看的第一行\n尚未查看的第二行\n".getBytes(StandardCharsets.UTF_8),new DocumentReader(null,null));}
        String id()throws Exception{return source.getString("id");}String sha()throws Exception{return source.getString("sha256");}
        JSONObject preview()throws Exception{return documents.preview("task-a",id(),sha(),new DocumentContent.Request(1,0,1,2048,8,"text"),new RunControl());}
        JSONObject save(JSONObject read,RunControl control)throws Exception{return documents.savePreview("task-a",id(),sha(),read.getString("readReceiptId"),control);}
        boolean hasPart(){File[] parts=root.listFiles((d,n)->n.endsWith(".part")||n.endsWith(".pending"));return parts!=null&&parts.length>0;}
    }
    @Test public void savesOnlyTheActuallyReadRangeAndReturnedJsonCannotForgeTheText()throws Exception {
        Fixture f=new Fixture();JSONObject read=f.preview();assertEquals(2,read.getInt("nextUnit"));
        read.getJSONArray("chunks").getJSONObject(0).put("text","FORGED CLIENT BODY");read.put("nextUnit",0).put("extraction","ocr");
        JSONObject saved=f.save(read,new RunControl());String text=f.book.readText(saved.getString("id")).getString("text");
        assertTrue(text.contains("已经查看的第一行"));assertFalse(text.contains("尚未查看的第二行"));assertFalse(text.contains("FORGED CLIENT BODY"));assertTrue(text.contains("尚有后续内容"));assertTrue(text.contains("\"line\":\"1\""));assertTrue(text.contains(f.sha()));
        assertEquals("text/plain",saved.getString("mimeType"));assertTrue(saved.getString("name").endsWith(".txt"));JSONObject origin=saved.getJSONObject("source");assertEquals("document_extract",origin.getString("kind"));assertEquals("task-a",origin.getString("taskId"));assertEquals(f.id(),origin.getString("sourceAttachmentId"));assertEquals(1,origin.getJSONObject("readRange").getInt("maxUnits"));assertEquals("text",origin.getString("extraction"));
        assertEquals(saved.getString("id"),f.save(read,new RunControl()).getString("id"));assertEquals(2,f.book.list().length());assertEquals(text,new AttachmentBook(f.root).readText(saved.getString("id")).getString("text"));
    }
    @Test public void unreadOrOtherTaskRangesAndRestartedPreviewReceiptsCannotCreateArtifacts()throws Exception {
        Fixture f=new Fixture();assertThrows(SecurityException.class,()->f.documents.savePreview("task-a",f.id(),f.sha(),java.util.UUID.randomUUID().toString(),new RunControl()));
        JSONObject read=f.preview();assertThrows(SecurityException.class,()->f.documents.savePreview("task-b",f.id(),f.sha(),read.getString("readReceiptId"),new RunControl()));
        assertThrows(SecurityException.class,()->new AttachmentDocuments(f.book,new DocumentReader(null,null)).savePreview("task-a",f.id(),f.sha(),read.getString("readReceiptId"),new RunControl()));assertEquals(1,f.book.list().length());
    }
    @Test public void changedSourceBytesOrMetadataCannotBeSavedFromAnEarlierRead()throws Exception {
        Fixture bytes=new Fixture();JSONObject read=bytes.preview();byte[] changed=Files.readAllBytes(bytes.book.file(bytes.id()).toPath());changed[0]^=1;Files.write(bytes.book.file(bytes.id()).toPath(),changed);
        assertThrows(AttachmentBook.InputChangedException.class,()->bytes.save(read,new RunControl()));assertEquals(1,bytes.book.list().length());
        Fixture metadata=new Fixture();JSONObject before=metadata.preview();JSONObject record=metadata.book.internal(metadata.id()).put("mimeType","text/csv");Files.writeString(new File(metadata.root,metadata.id()+".json").toPath(),record.toString(),StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->metadata.save(before,new RunControl()));assertEquals(1,metadata.book.list().length());
    }
    @Test public void changedExtractionDoesNotSaveUnreviewedContent()throws Exception {
        AtomicInteger calls=new AtomicInteger();DocumentReader.OcrPort changing=new DocumentReader.OcrPort(){
            public boolean available(){return true;}public String engineVersion(){return "changing-test-port";}
            public void recognize(byte[] b,String format,DocumentReader.RequestSink sink,RunControl control)throws Exception{sink.totalUnits(1);sink.text(1,"text",DocumentContent.location("image","1"),"recognized result "+calls.incrementAndGet());}
        };
        Fixture f=new Fixture(DocumentFixtures.png(),new DocumentReader(null,changing));JSONObject read=f.documents.preview("task-a",f.id(),f.sha(),new DocumentContent.Request(1,0,1,2048,8,"ocr"),new RunControl());
        assertThrows(SecurityException.class,()->f.save(read,new RunControl()));assertEquals(1,f.book.list().length());assertEquals(2,calls.get());
    }
    @Test public void ocrExtractPreservesRecognitionWarningsEngineAndLocation()throws Exception {
        DocumentReader.OcrPort ocr=new DocumentReader.OcrPort(){
            public boolean available(){return true;}public String engineVersion(){return "stable-test-ocr-port";}
            public void recognize(byte[] b,String format,DocumentReader.RequestSink sink,RunControl control)throws Exception{assertArrayEquals(DocumentFixtures.png(),b);sink.totalUnits(1);sink.text(1,"text",DocumentContent.location("image","1","boundsPixels","0,0,1,1"),"recognized test text");}
        };
        Fixture f=new Fixture(DocumentFixtures.png(),new DocumentReader(null,ocr));JSONObject read=f.documents.preview("task-a",f.id(),f.sha(),new DocumentContent.Request(1,0,1,2048,8,"ocr"),new RunControl()),saved=f.save(read,new RunControl());String text=f.book.readText(saved.getString("id")).getString("text");
        assertTrue(text.contains("recognized test text"));assertTrue(text.contains("OCR 识别文字可能有误"));assertTrue(text.contains("stable-test-ocr-port"));assertTrue(text.contains("boundsPixels"));assertEquals("ocr",saved.getJSONObject("source").getString("extraction"));
    }
    @Test public void cancellationBeforeSaveAndDuringTheRealBookWriteLeavesNoOutput()throws Exception {
        Fixture f=new Fixture();JSONObject read=f.preview();RunControl before=new RunControl();before.cancel();assertThrows(InterruptedException.class,()->f.save(read,before));
        RunControl writing=new RunControl();AtomicBoolean reached=new AtomicBoolean();writing.setAuthorityCheck(()->{if(f.hasPart()){reached.set(true);writing.cancel();}});
        assertThrows(InterruptedException.class,()->f.save(read,writing));assertTrue(reached.get());assertEquals(1,f.book.list().length());assertFalse(f.hasPart());
    }
    @Test public void aPauseDuringWriteInvalidatesTheOriginalCommitDecision()throws Exception {
        Fixture f=new Fixture();JSONObject read=f.preview();RunControl writing=new RunControl();AtomicBoolean paused=new AtomicBoolean();
        writing.setAuthorityCheck(()->{if(f.hasPart()&&paused.compareAndSet(false,true)){writing.pause();writing.resume();}});
        assertThrows(SecurityException.class,()->f.save(read,writing));assertTrue(paused.get());assertEquals(1,f.book.list().length());assertFalse(f.hasPart());
    }
    @Test public void sourceChangeDuringActualCommitRollsBackTheNewExtract()throws Exception {
        Fixture f=new Fixture();JSONObject read=f.preview();RunControl writing=new RunControl();AtomicBoolean changed=new AtomicBoolean();
        writing.setAuthorityCheck(()->{if(f.hasPart()&&changed.compareAndSet(false,true)){byte[] bytes=Files.readAllBytes(f.book.file(f.id()).toPath());bytes[0]^=1;Files.write(f.book.file(f.id()).toPath(),bytes);}});
        assertThrows(AttachmentBook.InputChangedException.class,()->f.save(read,writing));assertTrue(changed.get());assertEquals(1,f.book.list().length());assertFalse(f.hasPart());
    }
    @Test public void savedExtractTamperingCannotTriggerAnOptimisticDuplicate()throws Exception {
        Fixture f=new Fixture();JSONObject read=f.preview(),saved=f.save(read,new RunControl());byte[] bytes=Files.readAllBytes(f.book.file(saved.getString("id")).toPath());bytes[0]^=1;Files.write(f.book.file(saved.getString("id")).toPath(),bytes);
        assertThrows(AttachmentBook.InputChangedException.class,()->f.save(read,new RunControl()));assertEquals(2,f.book.list().length());
    }
    @Test public void scopedSnapshotsAndUnavailableReadersNeverMintPrivateSaveReceipts()throws Exception {
        Fixture f=new Fixture();byte[] text="group scoped text".getBytes(StandardCharsets.UTF_8);StringBuilder sha=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(text))sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        JSONObject group=f.documents.readVerifiedNativeSnapshot("group-artifact",sha.toString(),text,DocumentContent.Request.defaults(),new RunControl());assertFalse(group.has("readReceiptId"));
        Fixture image=new Fixture(DocumentFixtures.png(),new DocumentReader(null,null));JSONObject unavailable=image.documents.preview("task-a",image.id(),image.sha(),DocumentContent.Request.defaults(),new RunControl());assertEquals("unavailable",unavailable.getString("status"));assertFalse(unavailable.has("readReceiptId"));assertEquals(1,image.book.list().length());
    }
}
