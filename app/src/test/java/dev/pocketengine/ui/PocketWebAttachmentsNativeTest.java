package dev.pocketengine.ui;

import android.net.Uri;
import android.graphics.Bitmap;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real file coordinator/book/parser and review admission reached through the only workspace bridge. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows={ReadyApplicationFixture.NoUnrelatedStartup.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class PocketWebAttachmentsNativeTest {
    private static JSONObject view(OwnerWebTestFixture f)throws Exception{return f.state().getJSONObject("attachmentWorkspace");}
    private static JSONObject command(OwnerWebTestFixture f,String name,JSONObject args)throws Exception {
        JSONObject v=view(f);return f.call("attachmentWorkspace.action",new JSONObject(args.toString()).put("command",name).put("windowId",v.getString("windowId")).put("frameId",v.getString("frameId")));
    }
    private static void settled(OwnerWebTestFixture f)throws Exception{f.await(()->{JSONObject v=view(f);return v.optBoolean("ready")&&!v.optBoolean("busy");});}
    private static JSONObject source(OwnerWebTestFixture f,String name,String body)throws Exception {
        return AttachmentRepository.get(f.app).book.importBytes(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),name,"text/plain",new JSONObject().put("kind","content_uri"));
    }
    private static void select(OwnerWebTestFixture f,JSONObject file)throws Exception{command(f,"selectFile",new JSONObject().put("fileId",file.getString("id")).put("sha256",file.getString("sha256")));settled(f);}
    @Test public void boundedReadSavesOnlyItsOriginalReceiptAndPreservesSourceBytes()throws Exception {
        try(OwnerWebTestFixture f=new OwnerWebTestFixture()){
            String text="first source line\nsecond source line\nthird source line\nfourth source line\nunread fifth source line\n";
            JSONObject file=source(f,"source.txt",text);f.call("attachmentWorkspace.open",new JSONObject());settled(f);select(f,file);
            command(f,"read",new JSONObject().put("unit",1).put("chunk",0));settled(f);
            JSONObject read=view(f).getJSONObject("document");assertEquals(file.getString("sha256"),read.getString("sha256"));assertTrue(view(f).getBoolean("canSaveRead"));
            command(f,"saveRead",new JSONObject());settled(f);JSONObject saved=view(f).getJSONObject("file");
            assertNotEquals(file.getString("id"),saved.getString("id"));assertEquals("document_extract",saved.getString("sourceKind"));assertEquals(file.getString("sha256"),saved.getString("sourceSha256"));
            var book=AttachmentRepository.get(f.app).book;String extract=new String(book.readVerifiedSnapshot(saved.getString("id"),saved.getString("sha256"),new RunControl()),StandardCharsets.UTF_8);
            assertTrue(extract.contains("first source line"));assertFalse(extract.contains("unread fifth source line"));assertEquals(text,new String(Files.readAllBytes(book.file(file.getString("id")).toPath()),StandardCharsets.UTF_8));
            assertEquals(0,f.app.tasks.list().length());
        }
    }
    @Test public void staleSelectedFileFrameCannotReadOrExportAnotherOriginal()throws Exception {
        try(OwnerWebTestFixture f=new OwnerWebTestFixture()){
            JSONObject a=source(f,"same.txt","first"),b=source(f,"same.txt","second");f.call("attachmentWorkspace.open",new JSONObject());settled(f);select(f,a);JSONObject old=new JSONObject(view(f).toString());
            command(f,"back",new JSONObject());settled(f);select(f,b);
            for(String action:new String[]{"read","export","share"})assertFalse(f.envelope("attachmentWorkspace.action",new JSONObject().put("windowId",old.getString("windowId")).put("frameId",old.getString("frameId")).put("command",action)).getBoolean("ok"));
            assertEquals(b.getString("id"),view(f).getJSONObject("file").getString("id"));assertFalse(view(f).getBoolean("exportPending"));assertFalse(view(f).getBoolean("documentOpen"));assertEquals(0,f.app.tasks.list().length());
        }
    }
    @Test public void revisedDocumentNeedsFreshReviewAndCreatesExactlyOneIndependentResult()throws Exception {
        try(OwnerWebTestFixture f=new OwnerWebTestFixture()){
            f.call("attachmentWorkspace.open",new JSONObject());settled(f);command(f,"newDocument",new JSONObject());
            JSONObject draft=new JSONObject(view(f).getJSONObject("documentDraft").toString()).put("name","new document").put("body","reviewed old text");command(f,"updateDocument",new JSONObject().put("draft",draft));
            JSONObject review=command(f,"reviewDocument",new JSONObject());assertEquals(0,f.app.tasks.list().length());
            draft.put("body","explicitly revised text");command(f,"updateDocument",new JSONObject().put("draft",draft));JSONObject v=view(f);
            assertFalse(f.envelope("attachmentWorkspace.action",new JSONObject().put("windowId",v.getString("windowId")).put("frameId",v.getString("frameId")).put("command","createDocument").put("reviewId",review.getString("reviewId")).put("acknowledge",true)).getBoolean("ok"));assertEquals(0,f.app.tasks.list().length());
            JSONObject fresh=command(f,"reviewDocument",new JSONObject());command(f,"createDocument",new JSONObject().put("reviewId",fresh.getString("reviewId")).put("acknowledge",true));settled(f);
            JSONObject output=view(f).getJSONObject("file");assertNotNull(output.getJSONObject("editableSource"));assertEquals(1,f.app.tasks.list().length());assertEquals(2,AttachmentRepository.get(f.app).book.list().length());
            JSONObject editable=output.getJSONObject("editableSource");String original=new String(AttachmentRepository.get(f.app).book.readVerifiedSnapshot(editable.getString("id"),editable.getString("sha256"),new RunControl()),StandardCharsets.UTF_8);assertTrue(original.contains("explicitly revised text"));
        }
    }
    @Test public void originalReaderReturnsVerifiedTextAndRetiresOnForegroundLoss()throws Exception {
        try(OwnerWebTestFixture f=new OwnerWebTestFixture()){
            JSONObject source=source(f,"original.txt","original file body");f.call("attachmentWorkspace.open",new JSONObject());settled(f);select(f,source);command(f,"openOriginal",new JSONObject());settled(f);
            JSONObject original=view(f).getJSONObject("originalFile"),window=new JSONObject(view(f).toString());
            JSONObject actual=f.call("attachmentWorkspace.viewer",new JSONObject().put("windowId",window.getString("windowId")).put("viewerId",original.getString("id")).put("command","load"));assertEquals("original file body",actual.getString("content"));assertEquals(source.getString("sha256"),actual.getString("sha256"));
            OwnerWebTestFixture.invoke(f.controller,"realtimeForeground",false);f.bridge.foreground(false);OwnerWebTestFixture.invoke(f.controller,"realtimeForeground",true);f.bridge.foreground(true);
            assertFalse(f.envelope("attachmentWorkspace.viewer",new JSONObject().put("windowId",window.getString("windowId")).put("viewerId",original.getString("id")).put("command","read")).getBoolean("ok"));
            assertEquals(403,f.bridge.resource(Uri.parse(original.getString("url")),"GET",java.util.Map.of()).getStatusCode());assertEquals(0,f.app.tasks.list().length());
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void changedProcessingReviewCannotBorrowNewFrameOrForegroundAuthority()throws Exception {
        try(OwnerWebTestFixture f=new OwnerWebTestFixture()){
            Bitmap pixels=Bitmap.createBitmap(8,4,Bitmap.Config.ARGB_8888);pixels.eraseColor(android.graphics.Color.GREEN);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try{assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,bytes));}finally{pixels.recycle();}
            byte[] original=bytes.toByteArray();var book=AttachmentRepository.get(f.app).book;
            JSONObject file=book.importBytes(new ByteArrayInputStream(original),"original.png","image/png",new JSONObject().put("kind","content_uri"));
            f.call("attachmentWorkspace.open",new JSONObject());settled(f);select(f,file);command(f,"openProcessing",new JSONObject());settled(f);
            JSONObject options=new JSONObject().put("left",0).put("top",0).put("right",8).put("bottom",4).put("width",4).put("height",2).put("rotation",0).put("format","png").put("quality",90);
            command(f,"reviewProcessing",new JSONObject().put("options",options));settled(f);String oldReview=view(f).getString("processingReviewId");
            command(f,"reviewProcessing",new JSONObject().put("options",new JSONObject(options.toString()).put("width",2).put("height",1)));settled(f);
            JSONObject current=view(f);String replacement=current.getString("processingReviewId");assertNotEquals(oldReview,replacement);
            assertFalse(f.envelope("attachmentWorkspace.action",new JSONObject().put("windowId",current.getString("windowId")).put("frameId",current.getString("frameId")).put("command","applyProcessing").put("reviewId",oldReview).put("acknowledge",true)).getBoolean("ok"));
            assertEquals(0,f.app.tasks.list().length());assertEquals(1,book.list().length());
            OwnerWebTestFixture.invoke(f.controller,"realtimeForeground",false);f.bridge.foreground(false);OwnerWebTestFixture.invoke(f.controller,"realtimeForeground",true);f.bridge.foreground(true);settled(f);
            current=view(f);assertNotEquals(replacement,current.getString("processingReviewId"));
            assertFalse(f.envelope("attachmentWorkspace.action",new JSONObject().put("windowId",current.getString("windowId")).put("frameId",current.getString("frameId")).put("command","applyProcessing").put("reviewId",replacement).put("acknowledge",true)).getBoolean("ok"));
            assertEquals(0,f.app.tasks.list().length());assertArrayEquals(original,Files.readAllBytes(book.file(file.getString("id")).toPath()));
            command(f,"applyProcessing",new JSONObject().put("reviewId",view(f).getString("processingReviewId")).put("acknowledge",true));settled(f);
            JSONObject output=view(f).getJSONObject("file");assertNotEquals(file.getString("id"),output.getString("id"));assertEquals(1,f.app.tasks.list().length());assertEquals(2,book.list().length());
            assertArrayEquals(original,Files.readAllBytes(book.file(file.getString("id")).toPath()));
        }
    }
}
