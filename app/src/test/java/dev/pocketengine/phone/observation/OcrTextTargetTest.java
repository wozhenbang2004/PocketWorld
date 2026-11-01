package dev.pocketengine.phone.observation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public final class OcrTextTargetTest {
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",1);return c;}
    @Test public void downsampledOcrBoxMapsToDisplayWithoutExposingAModelCoordinate()throws Exception {
        OcrPage.Line line=new OcrPage.Line(1,"继续",20,10,60,30);OcrPage page=new OcrPage(400,200,200,100,List.of(line));RunControl c=control();
        OcrTextTarget target=new OcrTextTarget("observation",0,c,page,line);JSONObject args=target.tapArguments(1001,501);
        assertEquals(200,args.getInt("x"));assertEquals(199,args.getInt("y"));assertEquals("observation",args.getString("observationId"));
        assertEquals("upright_ocr_raster_pixels",target.receiptEvidence().getString("coordinateSpace"));assertFalse(target.receiptEvidence().has("x"));
    }
    @Test public void sameIdsDoNotReplaceControlAndPauseResumeDoesNotReviveTarget() {
        RunControl c=control();OcrPage.Line line=new OcrPage.Line(1,"确定",1,1,9,9);OcrTextTarget target=new OcrTextTarget("original",0,c,new OcrPage(10,10,10,10,List.of(line)),line);
        assertTrue(target.matches(c,"original",0));assertFalse(target.matches(control(),"original",0));assertFalse(target.matches(c,"another",0));
        c.pause();c.resume();assertFalse(target.matches(c,"original",0));
    }
    @Test public void coordinateQuantizationCannotMoveTinyTextIntoAnotherRegion() {
        RunControl c=control();OcrPage.Line line=new OcrPage.Line(1,"x",1,1,2,2);OcrTextTarget target=new OcrTextTarget("thin",0,c,new OcrPage(4000,1000,4000,1000,List.of(line)),line);
        assertThrows(IllegalArgumentException.class,()->target.tapArguments(4000,1000));
    }
}
