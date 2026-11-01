package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentFixtures;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AttachmentDocumentsTest {
    private AttachmentBook book()throws Exception{return new AttachmentBook(Files.createTempDirectory("w02-documents-").toFile());}
    private JSONObject save(AttachmentBook book,byte[] bytes,String name)throws Exception{return book.importBytes(new ByteArrayInputStream(bytes),name,"application/octet-stream",new JSONObject().put("uri","content://private/source"));}
    @Test public void realAttachmentIdentityDigestAndContentAreBoundTogether()throws Exception {
        AttachmentBook book=book();JSONObject item=save(book,DocumentFixtures.spreadsheet(),"wrong-name.pdf");AttachmentDocuments documents=new AttachmentDocuments(book,new DocumentReader(null,null));
        JSONObject result=documents.read(item.getString("id"),item.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());
        assertEquals("xlsx",result.getString("format"));assertEquals("真实单元格",result.getJSONArray("chunks").getJSONObject(0).getString("text"));assertEquals(item.getString("sha256"),result.getString("sha256"));assertEquals(DocumentContent.PARSER_VERSION,result.getString("parserVersion"));assertTrue(result.getBoolean("untrustedAttachmentContent"));assertFalse(result.getBoolean("verifiedBusinessSuccess"));assertFalse(result.getBoolean("cacheUsed"));assertFalse(result.toString().contains("content://"));
        assertFalse(documents.capabilities().getBoolean("imageOcr"));assertFalse(documents.capabilities().getBoolean("pdfText"));
    }
    @Test public void filenamesAndMetadataNeverCountAsOcr()throws Exception {
        AttachmentBook book=book();JSONObject item=save(book,DocumentFixtures.png(),"recognized invoice TOTAL 9999.png");
        JSONObject result=new AttachmentDocuments(book,new DocumentReader(null,null)).read(item.getString("id"),item.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());
        assertEquals("unavailable",result.getString("status"));assertEquals(0,result.getJSONArray("chunks").length());assertFalse(result.toString().contains("9999"));
    }
    @Test public void staleExpectedDigestAndSameLengthByteReplacementRejectContent()throws Exception {
        AttachmentBook book=book();JSONObject item=save(book,"original".getBytes(StandardCharsets.UTF_8),"note");AttachmentDocuments documents=new AttachmentDocuments(book,new DocumentReader(null,null));
        assertEquals("input_changed",documents.read(item.getString("id"),"0".repeat(64),DocumentContent.Request.defaults(),new RunControl()).getString("status"));
        Files.write(book.file(item.getString("id")).toPath(),"replaced".getBytes(StandardCharsets.UTF_8));JSONObject result=documents.read(item.getString("id"),item.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());assertEquals("input_changed",result.getString("status"));assertEquals(0,result.getJSONArray("chunks").length());
    }
    @Test public void replacementDuringParsingDiscardsOldSnapshotResult()throws Exception {
        AttachmentBook book=book();JSONObject item=save(book,DocumentFixtures.pdf(false),"document");String id=item.getString("id");
        DocumentReader.PdfTextPort port=new DocumentReader.PdfTextPort(){
            public boolean available(){return true;}
            public void read(byte[] bytes,DocumentReader.RequestSink sink,RunControl control)throws Exception {
                sink.totalUnits(1);sink.text(1,"pdf_text",DocumentContent.location("page","1"),"must not publish");bytes[bytes.length-1]^=1;Files.write(book.file(id).toPath(),bytes);
            }
        };
        JSONObject result=new AttachmentDocuments(book,new DocumentReader(port,null)).read(id,item.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());assertEquals("input_changed",result.getString("status"));assertEquals(0,result.getJSONArray("chunks").length());
    }
    @Test public void responseUtf8BudgetHasResumableCursorAndNoHiddenTextLoss()throws Exception {
        String text=("中文\\\"".repeat(14000));AttachmentBook book=book();JSONObject item=save(book,text.getBytes(StandardCharsets.UTF_8),"large");AttachmentDocuments documents=new AttachmentDocuments(book,new DocumentReader(null,null));
        StringBuilder restored=new StringBuilder();int unit=1,chunk=0,pages=0;
        do {
            JSONObject result=documents.read(item.getString("id"),item.getString("sha256"),new DocumentContent.Request(unit,chunk,1,65536,256),new RunControl());
            assertTrue(result.toString().getBytes(StandardCharsets.UTF_8).length<=AttachmentDocuments.MAX_RESPONSE_BYTES);
            for(int i=0;i<result.getJSONArray("chunks").length();i++)restored.append(result.getJSONArray("chunks").getJSONObject(i).getString("text"));
            unit=result.getInt("nextUnit");chunk=result.getInt("nextChunk");assertTrue(++pages<20);
        }while(unit>0);
        assertEquals(text,restored.toString());assertTrue(pages>1);
    }
    @Test public void cancelAndArbitraryPathsCannotBypassHostReadAuthority()throws Exception {
        AttachmentBook book=book();JSONObject item=save(book,"hello".getBytes(StandardCharsets.UTF_8),"a");AttachmentDocuments documents=new AttachmentDocuments(book,new DocumentReader(null,null));
        RunControl control=new RunControl();control.cancel();assertThrows(InterruptedException.class,()->documents.read(item.getString("id"),item.getString("sha256"),DocumentContent.Request.defaults(),control));
        assertThrows(IllegalArgumentException.class,()->documents.read("../../tasks.json",item.getString("sha256"),DocumentContent.Request.defaults(),new RunControl()));
        assertThrows(IllegalArgumentException.class,()->documents.read(item.getString("id"),"",DocumentContent.Request.defaults(),new RunControl()));
        assertThrows(IllegalArgumentException.class,()->documents.read(item.getString("id"),item.getString("sha256"),DocumentContent.Request.defaults(),null));
    }
}
