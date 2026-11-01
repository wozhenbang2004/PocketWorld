package dev.pocketengine.vision.ocr;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentFixtures;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real platform decoder fixtures under Robolectric native graphics, not a device OCR test. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OcrImageDecoderTest {
    @Test public void existingLegalScanFixtureDecodesActualPixels()throws Exception {
        try(OcrImageDecoder.Raster raster=OcrImageDecoder.decode(DocumentFixtures.actual("scan.png"),"png",new RunControl())){
            assertTrue(raster.bitmap.getWidth()>100);assertTrue(raster.bitmap.getHeight()>100);assertFalse(raster.bitmap.isRecycled());assertEquals(Bitmap.Config.ARGB_8888,raster.bitmap.getConfig());
        }
    }
    @Test public void appliesAllExifRotationsAndReflectionsExactlyOnce()throws Exception {
        byte[] jpeg=jpegQuadrants();
        int[][] expected={{Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW},{Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE},
            {Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED},{Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN},
            {Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW},{Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN},
            {Color.YELLOW,Color.GREEN,Color.BLUE,Color.RED},{Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE}};
        for(int orientation=1;orientation<=8;orientation++)try(OcrImageDecoder.Raster raster=OcrImageDecoder.decode(exif(jpeg,orientation),"jpeg",new RunControl())){
            int w=orientation<5?80:40,h=orientation<5?40:80;
            assertEquals("orientation="+orientation,w,raster.sourceWidth);assertEquals(h,raster.sourceHeight);assertEquals(w,raster.bitmap.getWidth());assertEquals(h,raster.bitmap.getHeight());
            int[][] points={{w/4,h/4},{w*3/4,h/4},{w/4,h*3/4},{w*3/4,h*3/4}};
            for(int i=0;i<4;i++){int color=raster.bitmap.getPixel(points[i][0],points[i][1]),want=expected[orientation-1][i];assertTrue("orientation="+orientation+" corner="+i,colorDistance(color,want)<90);}
        }
    }
    @Test public void transparentImageIsCompositedOnWhiteBeforeRecognition()throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);byte[] bytes=encode(bitmap,Bitmap.CompressFormat.PNG);bitmap.recycle();
        try(OcrImageDecoder.Raster raster=OcrImageDecoder.decode(bytes,"png",new RunControl())){assertEquals(Color.WHITE,raster.bitmap.getPixel(3,3));}
    }
    @Test public void staticWebpDecodesButAnimatedWebpDoesNotSilentlySelectAFrame()throws Exception {
        Bitmap image=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLACK);byte[] bytes=encode(image,Bitmap.CompressFormat.WEBP_LOSSLESS);image.recycle();
        try(OcrImageDecoder.Raster raster=OcrImageDecoder.decode(bytes,"webp",new RunControl())){assertEquals(80,raster.bitmap.getWidth());assertEquals(40,raster.bitmap.getHeight());}
        java.nio.file.Path root=java.nio.file.Path.of("app/src/test/java/dev/pocketengine/vision/ocr/fixtures/animated.webp");
        if(!java.nio.file.Files.isRegularFile(root))root=java.nio.file.Path.of("src/test/java/dev/pocketengine/vision/ocr/fixtures/animated.webp");
        byte[] animated=java.nio.file.Files.readAllBytes(root);
        assertThrows(DocumentReader.InvalidDocumentException.class,()->OcrImageDecoder.decode(animated,"webp",new RunControl()));
    }
    @Test public void boundedDecodeActuallyDownsamplesAndNeverUpscales()throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(4096,2048,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);byte[] bytes=encode(bitmap,Bitmap.CompressFormat.PNG);bitmap.recycle();
        try(OcrImageDecoder.Raster raster=OcrImageDecoder.decode(bytes,"png",new RunControl())){assertEquals(4096,raster.sourceWidth);assertEquals(2048,raster.sourceHeight);assertTrue((long)raster.bitmap.getWidth()*raster.bitmap.getHeight()<=OcrPage.MAX_PIXELS);assertTrue(raster.bitmap.getWidth()<4096);}
    }
    @Test public void wrongDeclaredFormatCorruptionAndCancellationDoNotYieldPartialRaster()throws Exception {
        byte[] png=DocumentFixtures.actual("scan.png");
        assertThrows(DocumentReader.InvalidDocumentException.class,()->OcrImageDecoder.decode(png,"jpeg",new RunControl()));
        assertThrows(DocumentReader.InvalidDocumentException.class,()->OcrImageDecoder.decode(Arrays.copyOf(png,png.length/2),"png",new RunControl()));
        assertThrows(DocumentReader.InvalidDocumentException.class,()->OcrImageDecoder.decode(new byte[]{1,2,3},"png",new RunControl()));
        RunControl control=new RunControl();control.cancel();assertThrows(InterruptedException.class,()->OcrImageDecoder.decode(png,"png",control));
    }
    private static int colorDistance(int a,int b){return Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b));}
    private static byte[] jpegQuadrants()throws Exception{
        Bitmap image=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);Paint paint=new Paint();
        int[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW};for(int i=0;i<4;i++){paint.setColor(colors[i]);canvas.drawRect((i%2)*40,(i/2)*20,(i%2)*40+40,(i/2)*20+20,paint);}
        byte[] bytes=encode(image,Bitmap.CompressFormat.JPEG);image.recycle();return bytes;
    }
    private static byte[] encode(Bitmap image,Bitmap.CompressFormat format)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(image.compress(format,100,out));return out.toByteArray();}
    private static byte[] exif(byte[] jpeg,int orientation)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(jpeg,0,2);out.write(new byte[]{(byte)255,(byte)225,0,34,'E','x','i','f',0,0});
        ByteBuffer tag=ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);tag.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)1).putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)orientation).putShort((short)0).putInt(0);
        out.write(tag.array());out.write(jpeg,2,jpeg.length-2);return out.toByteArray();
    }
}
