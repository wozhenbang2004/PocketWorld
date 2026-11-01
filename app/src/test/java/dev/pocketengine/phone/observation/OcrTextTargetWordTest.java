package dev.pocketengine.phone.observation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public final class OcrTextTargetWordTest {
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",1);return c;}
    private static OcrPage page(){return new OcrPage(600,120,300,60,List.of(new OcrPage.Line(1,"取消 继续 保存",0,10,300,50)),
            List.of(new OcrPage.Word(1,1,"取消",10,10,70,50,93),new OcrPage.Word(2,1,"继续",120,10,180,50,81),new OcrPage.Word(3,1,"保存",230,10,290,50,49)),false);}
    @Test public void threeButtonsOnOneLineUseThreeActualBoxesIncludingDownsampledMapping()throws Exception {
        OcrPage p=page();RunControl c=control();int[] xs=new int[3];
        for(int i=0;i<3;i++){
            OcrTextTarget target=new OcrTextTarget("frame",0,c,p,p.words.get(i));JSONObject point=target.tapArguments(1201,241);
            xs[i]=point.getInt("x");assertEquals(1,target.lineIndex);assertEquals("word",target.granularity);
            assertEquals(p.words.get(i).text,target.receiptEvidence().getString("recognizedText"));assertFalse(target.receiptEvidence().has("x"));
        }
        assertEquals(133,xs[0]);assertEquals(500,xs[1]);assertEquals(867,xs[2]);assertTrue(xs[0]<xs[1]&&xs[1]<xs[2]);
    }
    @Test public void confidenceDoesNotClaimMeaningClickabilityOrBusinessSuccess()throws Exception {
        OcrPage p=page();JSONObject receipt=new OcrTextTarget("frame",0,control(),p,p.words.get(2)).receiptEvidence();
        assertEquals(49d,receipt.getDouble("confidence"),0d);assertTrue(receipt.getBoolean("confidenceAvailable"));
        assertTrue(receipt.getString("confidenceMeaning").contains("not_semantic_or_clickability"));assertTrue(receipt.getBoolean("recognitionMayBeWrong"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
        JSONObject legacy=new OcrTextTarget("frame",0,control(),p,p.lines.get(0)).receiptEvidence();
        assertFalse(legacy.getBoolean("confidenceAvailable"));assertEquals("line_center_not_a_specific_word",legacy.getString("precision"));
    }
    @Test public void wordDoesNotSurviveDifferentControlFramePauseOrCancellation() {
        OcrPage p=page();RunControl c=control();OcrTextTarget word=new OcrTextTarget("frame",0,c,p,p.words.get(2));
        assertTrue(word.matches(c,"frame",0));assertFalse(word.matches(control(),"frame",0));assertFalse(word.matches(c,"other-frame",0));
        c.pause();c.resume();assertFalse(word.matches(c,"frame",0));
        RunControl other=control();OcrTextTarget cancelled=new OcrTextTarget("frame",0,other,p,p.words.get(0));other.cancel();assertFalse(cancelled.matches(other,"frame",0));
    }
    @Test public void tinyNativeWordStillCannotQuantizeOutsideItsRealBox() {
        OcrPage p=new OcrPage(4000,1000,4000,1000,List.of(new OcrPage.Line(1,"small word",0,0,4000,1000)),List.of(new OcrPage.Word(1,1,"x",1,1,2,2,95)),false);
        OcrTextTarget word=new OcrTextTarget("frame",0,control(),p,p.words.get(0));assertThrows(IllegalArgumentException.class,()->word.tapArguments(4000,1000));
    }
}
