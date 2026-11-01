package dev.pocketengine.phone.observation;

import static org.junit.Assert.*;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.LocalImageOcr;
import dev.pocketengine.vision.ocr.OcrPage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Actual adapter and native-entry contracts; OCR text and physical driver are explicit fixtures. */
public final class AdaptiveWordTargetsTest {
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAASwAAAEsCAIAAAD2HxkiAAADUklEQVR4nO3TMQEAAAiAMPuX1hgcbgl4mAVSUwfAdyaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIWZCiJkQYiaEmAkhZkKImRBiJoSYCSFmQoiZEGImhJgJIXb0mc7X2VRTSgAAAABJRU5ErkJggg==";
    private long now=1000;
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",1);return c;}
    private static OcrPage threeWords(){return new OcrPage(300,300,300,300,List.of(new OcrPage.Line(1,"取消 继续 保存",0,10,300,50)),
            List.of(new OcrPage.Word(1,1,"取消",10,10,70,50,93),new OcrPage.Word(2,1,"继续",120,10,180,50,81),new OcrPage.Word(3,1,"保存",230,10,290,50,49)),false);}
    private static class Ocr implements LocalImageOcr {
        final OcrPage page;Ocr(OcrPage page){this.page=page;}
        public boolean available(){return true;}public String engineVersion(){return "controlled-word-geometry-not-jni";}public void close(){}
        public OcrPage recognizeImage(byte[] bytes,String format,RunControl control){assertEquals("png",format);assertTrue(bytes.length>24);return page;}
    }
    private static final class Driver implements PhonePort,NativeOcrTapPort {
        String current;int reads,effects;boolean changePixels,loseReceipt;OcrTextTarget entered;
        public boolean isConnected(){return true;}public void clearReferences(){current=null;}
        public JSONObject observe(boolean image,RunControl c)throws Exception {
            current="frame-"+(++reads);JSONObject out=new JSONObject().put("ok",true).put("observationId",current).put("displayId",5).put("sessionId","physical-session")
                    .put("observedAtUptimeMs",1000).put("pauseRevision",c.pauseRevision()).put("nodes",new JSONArray()).put("truncated",false);
            if(image)out.put("imageBase64",PNG).put("imageMimeType","image/png").put("imageWidth",300).put("imageHeight",300);
            return ObservationReport.attach(out,false,"controlled_canvas_no_tree",image,image?"controlled_full_frame":"not_requested");
        }
        public JSONObject execute(String name,JSONObject args,RunControl c){throw new AssertionError("Word targets cannot use a model-coordinate path");}
        public JSONObject tapOcrText(OcrTextTarget target,RunControl c,NativePhoneEntry.Admission admission)throws Exception {
            if(changePixels||!target.matches(c,current,c.pauseRevision()))return new JSONObject().put("ok",false).put("accepted",false).put("error","stale_observation");
            return NativePhoneEntry.call(c,c.pauseRevision(),admission,()->{entered=target;effects++;if(loseReceipt)throw new java.io.IOException("controlled lost receipt after native effect");return new JSONObject().put("ok",true).put("accepted",true).put("verified",false);});
        }
    }
    private AdaptivePhonePort port(PhonePort d,LocalImageOcr ocr){return new AdaptivePhonePort(d,new AdaptivePhonePort.Scope("task","run",1,"physical-session",5),true,false,()->now,ocr);}
    private static JSONObject args(JSONObject observed,JSONObject word)throws Exception{return new JSONObject().put("observationId",observed.getString("observationId")).put("textTargetId",word.getString("textTargetId"));}
    private static Set<String> registered(AdaptivePhonePort p)throws Exception {
        Field field=AdaptivePhonePort.class.getDeclaredField("textTargets");field.setAccessible(true);
        return new HashSet<>(((Map<String,?>)field.get(p)).keySet());
    }
    private static void onlyShownWordsRegistered(AdaptivePhonePort p,JSONObject out)throws Exception {
        Set<String> shown=new HashSet<>();JSONArray words=out.getJSONObject("ocr").getJSONArray("words");
        for(int i=0;i<words.length();i++)shown.add(words.getJSONObject(i).getString("textTargetId"));assertEquals(shown,registered(p));
    }
    @Test public void sameLineSaveUsesItsOwnDisplayedWordNotTheWholeLineCenter()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr(threeWords()));RunControl c=control();JSONObject out=p.observe(true,c),ocr=out.getJSONObject("ocr");
        assertFalse(out.has("imageBase64"));assertFalse(ocr.getJSONArray("lines").getJSONObject(0).has("textTargetId"));assertEquals(3,ocr.getJSONArray("words").length());
        assertEquals("word_boxes",ocr.getString("nativeTargetGranularity"));assertFalse(ocr.getBoolean("actionCoordinates"));assertTrue(ocr.getString("wordConfidenceMeaning").contains("not_calibrated_accuracy"));
        onlyShownWordsRegistered(p,out);AtomicInteger admissions=new AtomicInteger();JSONObject click=args(out,ocr.getJSONArray("words").getJSONObject(2));
        JSONObject receipt=p.tapTextTarget(click,c,admissions::incrementAndGet);assertTrue(receipt.getBoolean("accepted"));assertEquals(1,admissions.get());assertEquals(1,d.effects);
        assertEquals("保存",d.entered.text);assertEquals("word",d.entered.granularity);assertTrue(d.entered.tapArguments(301,301).getInt("x")>850);
        assertEquals("保存",receipt.getJSONObject("ocrTextTarget").getString("recognizedText"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
        assertFalse(p.tapTextTarget(click,c,admissions::incrementAndGet).getBoolean("accepted"));assertEquals(1,d.effects);
    }
    @Test public void actualEngineWithMissingWordBoxesKeepsLinesButIssuesNoFakePreciseTarget()throws Exception {
        OcrPage textOnly=new OcrPage(300,300,300,300,threeWords().lines,List.of(),true);Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr(textOnly));RunControl c=control();JSONObject out=p.observe(true,c),ocr=out.getJSONObject("ocr");
        assertEquals("取消 继续 保存",ocr.getJSONArray("lines").getJSONObject(0).getString("text"));assertFalse(ocr.getJSONArray("lines").getJSONObject(0).has("textTargetId"));
        assertTrue(ocr.getBoolean("wordBoxesAttempted"));assertTrue(ocr.getBoolean("wordBoxesTruncated"));assertFalse(ocr.getBoolean("nativeTextTargetsAvailable"));assertTrue(ocr.getBoolean("truncated"));
        assertEquals("line_text_only_no_precise_target",ocr.getString("nativeTargetGranularity"));assertTrue(registered(p).isEmpty());
        JSONObject fake=new JSONObject().put("observationId",out.getString("observationId")).put("textTargetId",UUID.randomUUID().toString());
        assertEquals("stale_text_target",p.tapTextTarget(fake,c,()->{}).getString("error"));assertEquals(0,d.effects);
    }
    @Test public void unseenLinesAndBudgetOmissionsHaveNoIssuedNativeWordIds()throws Exception {
        List<OcrPage.Line> lines=new ArrayList<>();List<OcrPage.Word> words=new ArrayList<>();
        for(int i=1;i<=129;i++){lines.add(new OcrPage.Line(i,"选项"+i,0,(i-1)*2,100,i*2));words.add(new OcrPage.Word(i,i,"选项"+i,0,(i-1)*2,100,i*2,80));}
        AdaptivePhonePort p=port(new Driver(),new Ocr(new OcrPage(300,300,300,300,lines,words,false)));JSONObject out=p.observe(true,control()),ocr=out.getJSONObject("ocr");
        assertEquals(128,ocr.getJSONArray("lines").length());assertTrue(ocr.getBoolean("truncated"));assertTrue(ocr.getInt("wordTargetsOmitted")>0);
        for(int i=0;i<ocr.getJSONArray("words").length();i++)assertTrue(ocr.getJSONArray("words").getJSONObject(i).getInt("line")<=128);
        assertTrue(ocr.getJSONArray("words").toString().getBytes(StandardCharsets.UTF_8).length<=8000);onlyShownWordsRegistered(p,out);
    }
    @Test public void actualUtf8ArrayBudgetIncludesChineseTextCommasAndBrackets()throws Exception {
        String text="字".repeat(128);List<OcrPage.Word> words=new ArrayList<>();for(int i=1;i<=16;i++)words.add(new OcrPage.Word(i,1,text,(i-1)*15,0,i*15,30,70));
        OcrPage page=new OcrPage(300,300,300,300,List.of(new OcrPage.Line(1,text.repeat(16),0,0,300,30)),words,false);
        AdaptivePhonePort p=port(new Driver(),new Ocr(page));JSONObject out=p.observe(true,control()),ocr=out.getJSONObject("ocr");
        assertTrue(ocr.getJSONArray("words").length()>0);assertTrue(ocr.getInt("wordTargetsOmitted")>0);
        assertTrue(ocr.getJSONArray("words").toString().getBytes(StandardCharsets.UTF_8).length<=8000);onlyShownWordsRegistered(p,out);
    }
    @Test public void geometryAloneDoesNotCreateInputCapabilityOnAReadOnlyDriver()throws Exception {
        Driver actual=new Driver();PhonePort readonly=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){actual.clearReferences();}
            public JSONObject observe(boolean image,RunControl c)throws Exception{return actual.observe(image,c);}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("read only");}};
        AdaptivePhonePort p=port(readonly,new Ocr(threeWords()));JSONObject ocr=p.observe(true,control()).getJSONObject("ocr");
        assertFalse(ocr.getBoolean("nativeTextTargetsAvailable"));for(int i=0;i<ocr.getJSONArray("words").length();i++)assertFalse(ocr.getJSONArray("words").getJSONObject(i).has("textTargetId"));assertTrue(registered(p).isEmpty());
    }
    @Test public void lateWordMetadataCannotPublishTargetsAfterPauseResumeOrExpiry()throws Exception {
        for(boolean pause:new boolean[]{true,false}){
            now=1000;RunControl c=control();AdaptivePhonePort p=port(new Driver(),new Ocr(threeWords()){
                public String engineVersion(){if(pause){c.pause();c.resume();}else now=21001;return super.engineVersion();}});
            JSONObject out=p.observe(true,c);assertEquals("stale_observation",out.getString("error"));assertFalse(out.has("ocr"));assertTrue(registered(p).isEmpty());
        }
    }
    @Test public void changedFrameAndUnknownNativeEffectStillRefuseBlindWordReplay()throws Exception {
        for(boolean unknown:new boolean[]{false,true}){
            Driver d=new Driver();AdaptivePhonePort p=port(d,new Ocr(threeWords()));RunControl c=control();JSONObject out=p.observe(true,c),click=args(out,out.getJSONObject("ocr").getJSONArray("words").getJSONObject(0));
            d.changePixels=!unknown;d.loseReceipt=unknown;JSONObject receipt=p.tapTextTarget(click,c,()->{});
            if(unknown){assertTrue(receipt.isNull("accepted"));assertEquals("reconciliation_required",p.observe(true,c).getString("error"));}
            else{assertFalse(receipt.getBoolean("accepted"));assertEquals("stale_observation",receipt.getString("error"));}
            assertFalse(p.tapTextTarget(click,c,()->{}).optBoolean("accepted"));assertEquals(unknown?1:0,d.effects);
        }
    }
}
