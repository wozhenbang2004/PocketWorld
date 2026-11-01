package dev.pocketengine.phone.observation;

import static org.junit.Assert.*;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.LocalImageOcr;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Controlled OCR and native-driver fixtures; not JNI recognition or real-device evidence. */
public final class AdaptiveTextTargetTest {
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private static final class Driver implements PhonePort,NativeOcrTapPort {
        int reads,effects;boolean loseReceipt,changePixels;String current;
        public boolean isConnected(){return true;}public void clearReferences(){current=null;}
        public JSONObject observe(boolean image,RunControl c)throws Exception {
            current="frame-"+(++reads);JSONObject out=new JSONObject().put("ok",true).put("observationId",current).put("displayId",0).put("sessionId","native-session")
                    .put("observedAtUptimeMs",1000).put("pauseRevision",c.pauseRevision()).put("nodes",new JSONArray()).put("truncated",false);
            if(image)out.put("imageBase64",PNG).put("imageMimeType","image/png").put("imageWidth",1).put("imageHeight",1);
            return ObservationReport.attach(out,false,"no_accessible_root",image,image?"native_frame":"not_requested");
        }
        public JSONObject execute(String name,JSONObject args,RunControl c){throw new AssertionError("OCR target must use the typed native path, not model coordinates");}
        public JSONObject tapOcrText(OcrTextTarget target,RunControl c,NativePhoneEntry.Admission admission)throws Exception {
            if(changePixels||!target.matches(c,current,c.pauseRevision()))return new JSONObject().put("ok",false).put("accepted",false).put("error","stale_observation");
            assertEquals(1,target.sourceWidth);assertEquals(500,target.tapArguments(101,101).getInt("x"));
            return NativePhoneEntry.call(c,c.pauseRevision(),admission,()->{effects++;if(loseReceipt)throw new java.io.IOException("lost receipt after effect");return new JSONObject().put("ok",true).put("accepted",true).put("verified",false);});
        }
    }
    private static class Ocr implements LocalImageOcr {
        public boolean available(){return true;}public String engineVersion(){return "controlled-fixture-not-jni";}public void close(){}
        public OcrPage recognizeImage(byte[] bytes,String format,RunControl c){return new OcrPage(1,1,1,1,List.of(new OcrPage.Line(1,"继续",0,0,1,1)));}
    }
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",1);return c;}
    private static AdaptivePhonePort port(Driver d,Ocr ocr){return new AdaptivePhonePort(d,new AdaptivePhonePort.Scope("task","run",1,"native-session",0),true,false,()->1000,ocr);}
    private static JSONObject args(JSONObject observed)throws Exception{return new JSONObject().put("observationId",observed.getString("observationId")).put("textTargetId",observed.getJSONObject("ocr").getJSONArray("lines").getJSONObject(0).getString("textTargetId"));}
    @Test public void currentNativeLineCanActWithoutUploadingPixelsButCannotBeConsumedTwice()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr());RunControl c=control();JSONObject observed=p.observe(true,c),args=args(observed);
        assertFalse(observed.has("imageBase64"));assertFalse(observed.getJSONObject("ocr").getBoolean("actionCoordinates"));assertTrue(observed.getJSONObject("ocr").getBoolean("nativeTextTargetsAvailable"));
        assertTrue(p.tapTextTarget(args,c,()->{}).getBoolean("accepted"));assertEquals(1,d.effects);
        assertFalse(p.tapTextTarget(args,c,()->{}).getBoolean("accepted"));assertEquals(1,d.effects);
    }
    @Test public void rawCoordinatesAndMissingPreparedHostPathRemainRefused()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr());RunControl c=control();JSONObject observed=p.observe(true,c),args=args(observed);
        assertEquals("host_authority_required",p.execute(AdaptivePhonePort.CLICK_TEXT,args,c).getString("error"));
        assertEquals("coordinate_unverifiable",p.execute("phone_tap",new JSONObject().put("observationId",observed.getString("observationId")).put("x",500).put("y",500),c).getString("error"));assertEquals(0,d.effects);
    }
    @Test public void frameChangedAfterOcrNeverBecomesAClick()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr());RunControl c=control();JSONObject args=args(p.observe(true,c));d.changePixels=true;
        assertEquals("stale_observation",p.tapTextTarget(args,c,()->{}).getString("error"));assertEquals(0,d.effects);
    }
    @Test public void lostNativeReceiptBlocksEveryLaterTargetUntilOriginalOperationReconciliation()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr());RunControl c=control();JSONObject args=args(p.observe(true,c));d.loseReceipt=true;
        JSONObject receipt=p.tapTextTarget(args,c,()->{});assertTrue(receipt.isNull("accepted"));assertTrue(receipt.getBoolean("requiresPause"));assertEquals(1,d.effects);
        assertEquals("reconciliation_required",p.observe(true,c).getString("error"));assertEquals("reconciliation_required",p.tapTextTarget(args,c,()->{}).getString("error"));assertEquals(1,d.effects);
    }
    @Test public void reentrantEngineMetadataCallbackCannotPublishLateTargetsAfterPauseResume()throws Exception {
        Driver d=new Driver();RunControl c=control();Ocr ocr=new Ocr(){public String engineVersion(){c.pause();c.resume();return super.engineVersion();}};
        JSONObject out=port(d,ocr).observe(true,c);assertEquals("stale_observation",out.getString("error"));assertFalse(out.has("ocr"));assertEquals(0,d.effects);
    }
}
