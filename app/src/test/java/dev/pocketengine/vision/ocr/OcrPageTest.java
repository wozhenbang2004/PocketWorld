package dev.pocketengine.vision.ocr;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OcrPageTest {
    @Test public void rasterPlanningBoundsPixelsAndSidesWithoutIntegerOverflowOrUpscale() {
        for (int[] source : new int[][]{{1,1},{1,32768},{32768,1},{10000,10000},{8192,4096},{4000,3000}}) {
            int[] raster = OcrPage.rasterSize(source[0],source[1]);
            assertTrue(raster[0] <= source[0] && raster[1] <= source[1]);
            assertTrue(raster[0] > 0 && raster[1] > 0 && raster[0] <= 4096 && raster[1] <= 4096);
            assertTrue((long)raster[0]*raster[1] <= OcrPage.MAX_PIXELS);
        }
        for (int[] invalid : new int[][]{{0,1},{1,-1},{Integer.MAX_VALUE,Integer.MAX_VALUE},{32769,1},{10001,10000}})
            assertThrows(IllegalArgumentException.class,()->OcrPage.rasterSize(invalid[0],invalid[1]));
    }
    @Test public void immutableImageLocalBoundsNeverClaimScreenCoordinates() {
        ArrayList<OcrPage.Line> lines = new ArrayList<>(); lines.add(new OcrPage.Line(1,"真实 OCR",1,2,4,5));
        OcrPage page = new OcrPage(20,40,10,20,lines); lines.clear();
        assertTrue(page.downsampled()); assertEquals(1,page.lines.size()); assertEquals("1,2,4,5",page.lines.get(0).boundsPixels());
        assertEquals("upright_ocr_raster_pixels",OcrPage.COORDINATE_SPACE);
        assertThrows(UnsupportedOperationException.class,()->page.lines.clear());
    }
    @Test public void rejectsWrongGeometryOrderAndUnboundedText() {
        assertThrows(IllegalArgumentException.class,()->new OcrPage.Line(1,"text",-1,0,2,2));
        assertThrows(IllegalArgumentException.class,()->new OcrPage.Line(1,"",0,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new OcrPage.Line(1,"x".repeat(OcrPage.MAX_TEXT_CHARS+1),0,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new OcrPage(10,10,10,10,Collections.singletonList(new OcrPage.Line(1,"text",0,0,11,2))));
        assertThrows(IllegalArgumentException.class,()->new OcrPage(1,1,2,2,Collections.emptyList()));
    }
}
