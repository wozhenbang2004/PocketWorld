package dev.pocketengine.features.attachments;
import static org.junit.Assert.*;
import android.graphics.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.processing.*;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OriginalImageProcessingTest {
    @Test public void actualPngPixelsCropScaleRotateReopenAndOriginalBookParentRemainExact()throws Exception{
        var root=Files.createTempDirectory("c2-original-image-");AttachmentBook book=new AttachmentBook(root.resolve("book").toFile());RunControl control=new RunControl();Bitmap pixels=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888);for(int y=0;y<2;y++)for(int x=0;x<4;x++)pixels.setPixel(x,y,x<2?Color.RED:Color.BLUE);ByteArrayOutputStream png=new ByteArrayOutputStream();assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,png));pixels.recycle();byte[] original=png.toByteArray();JSONObject parent=book.importBytes(new ByteArrayInputStream(original),"parent.png","image/png",new JSONObject().put("kind","created_text"));String id=parent.getString("id"),sha=parent.getString("sha256");OwnerFilePreviewSource source=new OwnerFilePreviewSource("attachments",parent,c->book.readVerifiedSnapshot(id,sha,c),c->book.readVerifiedMetadata(id,sha,c));var review=NativeFileProcess.image(source,new JSONObject().put("left",2).put("top",0).put("right",4).put("bottom",2).put("width",4).put("height",4).put("rotation",90),control);String op=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();JSONObject saved=new NativePrivateFileProcessing(book,root.resolve("cache").toFile()).execute(review,op,task,run,control);assertEquals("completed",saved.getString("status"));assertEquals(id,saved.getJSONObject("parentArtifact").getString("id"));byte[] transformed=book.readVerifiedSnapshot(saved.getString("id"),saved.getString("sha256"),control);Bitmap actual=BitmapFactory.decodeByteArray(transformed,0,transformed.length);try{assertEquals(4,actual.getWidth());assertEquals(4,actual.getHeight());for(int y=0;y<4;y++)for(int x=0;x<4;x++)assertEquals(Color.BLUE,actual.getPixel(x,y));}finally{actual.recycle();}assertArrayEquals(original,book.readVerifiedSnapshot(id,sha,control));assertEquals(saved.getString("id"),new NativePrivateFileProcessing(book,root.resolve("cache").toFile()).execute(review,op,task,run,control).getString("id"));assertEquals(2,book.list().length());
    }
}
