package dev.pocketengine.features.attachments;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.creation.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativePdfDocumentJourneyTest {
    @Test public void realPlatformPdfPaginatesReopensRendersAndPreservesEditableSourceAndOldVersion()throws Exception{
        Path root=Files.createTempDirectory("c2-native-pdf-");var book=new AttachmentBook(root.resolve("book").toFile());var control=new RunControl();var creator=new NativeDocumentCreation(book,root.resolve("cache").toFile(),(id,sha,c)->book.readVerifiedMetadata(id,sha,c));String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();StringBuilder body=new StringBuilder("# Actual page one\nName\tAmount\nNative original\t40.00");for(int i=0;i<100;i++)body.append("\nOriginal paragraph ").append(i).append(" remains in the editable source.");
        var draft=new DocumentDraft(new JSONObject().put("format","pdf").put("title","Native document").put("body",body.toString()));JSONObject first=creator.create(draft,UUID.randomUUID().toString(),task,run,control);assertTrue(first.getJSONObject("formatCheck").getBoolean("pagesReopened"));assertTrue(first.getJSONObject("formatCheck").getInt("pageCount")>1);assertFalse(first.getJSONObject("formatCheck").getBoolean("visualLayoutVerified"));byte[] old=book.readVerifiedSnapshot(first.getString("id"),first.getString("sha256"),control);Path actual=root.resolve("actual.pdf");Files.write(actual,old);
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(actual.toFile(),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(0)){assertEquals(first.getJSONObject("formatCheck").getInt("pageCount"),renderer.getPageCount());Bitmap pixels=Bitmap.createBitmap(page.getWidth(),page.getHeight(),Bitmap.Config.ARGB_8888);try{pixels.eraseColor(Color.WHITE);page.render(pixels,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);boolean ink=false;for(int y=40;y<page.getHeight()-40&&!ink;y+=2)for(int x=40;x<page.getWidth()-40;x+=2)if((pixels.getPixel(x,y)&0x00ffffff)!=0x00ffffff){ink=true;break;}assertTrue("actual PDF renderer must produce non-blank source content",ink);}finally{pixels.recycle();}}
        JSONObject editable=creator.readEditableSource(first.getString("id"),first.getString("sha256"),control);assertEquals(body.toString(),editable.getString("body"));var revised=new DocumentDraft(editable.put("body",body.toString().replace("40.00","45.00")));String operation=UUID.randomUUID().toString();JSONObject second=creator.create(revised,operation,task,run,control);assertNotEquals(first.getString("id"),second.getString("id"));assertEquals(first.getString("id"),second.getJSONObject("source").getJSONObject("parentArtifact").getString("id"));assertArrayEquals(old,book.readVerifiedSnapshot(first.getString("id"),first.getString("sha256"),control));assertEquals(second.getString("id"),creator.create(revised,operation,task,run,control).getString("id"));assertEquals(4,book.list().length());
    }
}
