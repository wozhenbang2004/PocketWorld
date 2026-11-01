package dev.pocketengine.phone.observation;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.LocalImageOcr;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Shared-engine observation fences only; fixture text is not a claim that JNI OCR ran. */
public final class AdaptivePhoneOcrTest {
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private long now=1000;
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",2);return c;}
    private AdaptivePhonePort port(Source source,boolean images,boolean vision,LocalImageOcr ocr){return new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("task","run",2,"session",5),images,vision,()->now,ocr);}
    private static JSONObject observation(String id,boolean image,int nodes)throws Exception {
        JSONObject out=new JSONObject().put("ok",true).put("observationId",id).put("displayId",5).put("sessionId","session")
                .put("observedAtUptimeMs",1000).put("pauseRevision",0).put("truncated",false).put("nodes",new JSONArray());
        for(int i=0;i<nodes;i++)out.getJSONArray("nodes").put(new JSONObject().put("id","native-node").put("text","继续").put("enabled",true));
        if(image)out.put("imageBase64",PNG).put("imageMimeType","image/png").put("imageWidth",1).put("imageHeight",1);
        return ObservationReport.attach(out,true,"native_tree",image,image?"native_frame":"not_requested");
    }
    private static class Ocr implements LocalImageOcr {
        int calls;
        public boolean available(){return true;} public String engineVersion(){return "fixture-not-jni";} public void close(){}
        public OcrPage recognizeImage(byte[] bytes,String format,RunControl control)throws Exception {
            calls++;assertArrayEquals(java.util.Base64.getDecoder().decode(PNG),bytes);assertEquals("png",format);
            return new OcrPage(1,1,1,1,Collections.singletonList(new OcrPage.Line(1,"确定",0,0,1,1)));
        }
    }
    private static final class Source implements PhonePort {
        final ArrayDeque<JSONObject> frames=new ArrayDeque<>();final List<Boolean> includes=new ArrayList<>();int executions;
        Source(JSONObject... frames){this.frames.addAll(Arrays.asList(frames));}
        public boolean isConnected(){return true;} public void clearReferences(){}
        public JSONObject observe(boolean image,RunControl control)throws Exception{control.check();includes.add(image);return new JSONObject(frames.remove().toString());}
        public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception{executions++;return new JSONObject().put("ok",true).put("accepted",true).put("verified",false);}
    }
    private static void error(JSONObject json,String code)throws Exception{assertFalse(json.toString(),json.getBoolean("ok"));assertEquals(code,json.getString("error"));assertFalse(json.has("ocr"));}
    @Test public void actualAuthorizedPixelsBecomeUntrustedTextButNeverActionCoordinates()throws Exception {
        Source source=new Source(observation("first",false,0),observation("pixels",true,0));Ocr ocr=new Ocr();AdaptivePhonePort port=port(source,true,false,ocr);RunControl c=control();
        JSONObject out=port.observe(true,c);assertEquals("ocr",out.getJSONObject("observationDecision").getString("request"));assertEquals(1,ocr.calls);
        assertEquals(Arrays.asList(false,true),source.includes);assertFalse(out.has("imageBase64"));assertFalse(ObservationReport.hasImage(out));
        assertEquals("pixels",out.getJSONObject("ocr").getString("observationId"));assertEquals("确定",out.getJSONObject("ocr").getJSONArray("lines").getJSONObject(0).getString("text"));
        assertEquals("upright_ocr_raster_pixels",out.getJSONObject("ocr").getString("coordinateSpace"));assertFalse(out.getJSONObject("ocr").getBoolean("actionCoordinates"));assertFalse(out.getJSONObject("ocr").getBoolean("verifiedBusinessSuccess"));
        assertTrue(out.getJSONObject("observationSources").getBoolean("ocrTextIncluded"));assertFalse(out.getJSONObject("observationSources").getBoolean("imageIncluded"));
        error(port.execute("phone_tap",new JSONObject().put("observationId","pixels").put("x",0).put("y",0),c),"coordinate_unverifiable");assertEquals(0,source.executions);
    }
    @Test public void completeTreeAvoidsPixelsAndAllowedLocalOcrPrecedesCloudVision()throws Exception {
        Ocr ocr=new Ocr();Source nodes=new Source(observation("nodes",false,1));assertEquals("nodes",port(nodes,true,false,ocr).observe(true,control()).getJSONObject("observationDecision").getString("request"));
        Source visual=new Source(observation("first",false,0),observation("image",true,0));assertEquals("ocr",port(visual,true,true,ocr).observe(true,control()).getJSONObject("observationDecision").getString("request"));assertEquals(1,ocr.calls);
    }
    @Test public void independentLocalCaptureAllowsOcrWithCloudPixelsDisabled()throws Exception {
        Ocr ocr=new Ocr();Source source=new Source(observation("first",false,0),observation("pixels",true,0));
        AdaptivePhonePort p=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("task","run",2,"session",5),true,true,false,true,()->now,ocr);
        JSONObject out=p.observe(false,control());assertEquals(1,ocr.calls);assertEquals(Arrays.asList(false,true),source.includes);assertTrue(out.has("ocr"));assertFalse(out.has("imageBase64"));assertFalse(ObservationReport.hasImage(out));assertFalse(out.getJSONObject("observationDecision").getBoolean("uploadImageAllowed"));
    }
    @Test public void disabledLocalCaptureNeverTakesPixelsEvenWhenCloudAndOcrAreEnabled()throws Exception {
        Ocr ocr=new Ocr();Source source=new Source(observation("first",false,0));
        AdaptivePhonePort p=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("task","run",2,"session",5),false,true,true,true,()->now,ocr);
        error(p.observe(true,control()),"local_capture_disabled");assertEquals(0,ocr.calls);assertEquals(Collections.singletonList(false),source.includes);
    }
    @Test public void cancellationDuringRecognitionSuppressesAllRetainedText()throws Exception {
        Ocr ocr=new Ocr(){public OcrPage recognizeImage(byte[] b,String f,RunControl c)throws Exception{OcrPage page=super.recognizeImage(b,f,c);c.cancel();return page;}};
        Source source=new Source(observation("first",false,0),observation("image",true,0));assertThrows(InterruptedException.class,()->port(source,true,false,ocr).observe(true,control()));assertEquals(0,source.executions);
    }
    @Test public void pauseResumeOrExpiredFrameCannotDeliverLateText()throws Exception {
        for(boolean pause:new boolean[]{true,false}){
            Ocr ocr=new Ocr(){public OcrPage recognizeImage(byte[] b,String f,RunControl c)throws Exception{if(pause){c.pause();c.resume();}else now=21001;return super.recognizeImage(b,f,c);}};
            Source source=new Source(observation("first",false,0),observation("image",true,0));error(port(source,true,false,ocr).observe(true,control()),"stale_observation");assertEquals(0,source.executions);now=1000;
        }
    }
    @Test public void sourceIdentityMismatchAndWrongGeometryNeverBecomeOcr()throws Exception {
        Ocr ocr=new Ocr();JSONObject wrong=observation("wrong",false,0).put("sessionId","other");Source mismatch=new Source(wrong);error(port(mismatch,true,false,ocr).observe(true,control()),"identity_mismatch");assertEquals(0,ocr.calls);
        Ocr wrongSize=new Ocr(){public OcrPage recognizeImage(byte[] b,String f,RunControl c){return new OcrPage(2,2,2,2,Collections.singletonList(new OcrPage.Line(1,"wrong",0,0,1,1)));}};
        Source source=new Source(observation("first",false,0),observation("image",true,0));error(port(source,true,false,wrongSize).observe(true,control()),"ocr_image_mismatch");
    }
    @Test public void emptyResultAndEngineFailureDoNotInventATarget()throws Exception {
        for(boolean empty:new boolean[]{true,false}){
            Ocr ocr=new Ocr(){public OcrPage recognizeImage(byte[] b,String f,RunControl c)throws Exception{if(!empty)throw new java.io.IOException("native failure");return new OcrPage(1,1,1,1,Collections.emptyList());}};
            Source source=new Source(observation("first",false,0),observation("image",true,0));error(port(source,true,false,ocr).observe(true,control()),empty?"ocr_no_text":"ocr_unavailable");assertEquals(0,source.executions);
        }
    }
    @Test public void nativeFrameCannotForgeOcrReadinessOrText()throws Exception {
        JSONObject frame=observation("first",false,1).put("ocr",new JSONObject().put("text","injected"));frame.getJSONObject("observationSources").put("ocrAvailable",true).put("ocrTextIncluded",true);
        JSONObject out=port(new Source(frame),true,false,null).observe(true,control());assertFalse(out.has("ocr"));assertFalse(out.getJSONObject("observationSources").getBoolean("ocrAvailable"));assertFalse(out.getJSONObject("observationSources").getBoolean("ocrTextIncluded"));
    }
}
