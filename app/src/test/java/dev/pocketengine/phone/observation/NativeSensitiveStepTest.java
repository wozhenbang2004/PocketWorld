package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.PhonePort;
import org.json.JSONObject;
import org.json.JSONArray;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.*;

/** Synthetic labels only; these are policy/refusal tests, not device/OCR accuracy claims. */
public final class NativeSensitiveStepTest {
    private static NativeSensitiveStep.Reason risk(String label,String id,boolean input,String context) {
        return NativeSensitiveStep.classify(label,id,"example.fixture",input,input,context);
    }
    @Test public void explicitPaymentTransferAndLocalConfirmationAreHumanOnly() {
        for(String label:new String[]{"Pay now","确认付款","立即支付","Confirm payment"})assertEquals(label,PAYMENT_CONFIRMATION,risk(label,"",false,""));
        for(String label:new String[]{"Confirm transfer","Send money","确认转账"})assertEquals(label,TRANSFER_CONFIRMATION,risk(label,"",false,""));
        assertEquals(PAYMENT_CONFIRMATION,risk("","app:id/pay_button",false,""));
        assertEquals(PAYMENT_CONFIRMATION,risk("Pay $19.99","",false,""));
        assertEquals(TRANSFER_CONFIRMATION,risk("Transfer $100","",false,""));
        assertEquals(TRANSFER_CONFIRMATION,risk("","app:id/btn_confirm_transfer",false,""));
        assertNull(risk("","app:id/payment_methods_button",false,""));
        assertEquals(PAYMENT_CONFIRMATION,risk("Confirm","",false,"Payment confirmation — total due"));
        assertEquals(TRANSFER_CONFIRMATION,risk("确定","",false,"转账金额 收款人"));
        assertEquals(SENSITIVE_TARGET_UNCERTAIN,risk("","",false,"确认付款"));
    }
    @Test public void paymentWordsElsewhereDoNotSealOrdinaryTargetsOrAppNavigation() {
        for(String label:new String[]{"Search","Send","Payment history","Payment methods","Transfer settings","支付记录","支付方式","转账详情","Read more"})assertNull(label,risk(label,"",false,"Payment confirmation 支付金额"));
        assertNull(risk("Confirm","",false,"文章提到支付；payment history"));
        assertNull(risk("amount","",true,"Transfer amount 收款人"));
    }
    @Test public void cleartextOtpUsesNativePurposeNeverDigitsOrTheWordCodeAlone() {
        for(String label:new String[]{"SMS verification code","One-time code","验证码","Authentication code"})assertEquals(label,LOGIN_CODE,risk(label,"",true,""));
        assertEquals(LOGIN_CODE,risk("","app:id/otp_input",true,""));
        assertEquals(LOGIN_CODE,risk("code","",true,"Sign in using SMS verification code"));
        for(String label:new String[]{"Code","Source code editor","Message","Search verification code","123456"})assertNull(label,risk(label,"app:id/ordinary_field",true,"ordinary form"));
        assertNull(risk("Source code editor","app:id/code_editor",true,"documentation for authentication code"));
        assertNull(risk("Help with verification code","",false,""));
    }
    @Test public void permissionGrantNeedsNativeSystemResourceOrRecognizedElevationContext() {
        assertEquals(PERMISSION_ELEVATION,NativeSensitiveStep.classify("Allow","com.android.permissioncontroller:id/permission_allow_button","com.android.permissioncontroller",false,false,""));
        assertEquals(PERMISSION_ELEVATION,risk("启用","",false,"无障碍服务"));
        assertNull(risk("Allow","",false,"ordinary form"));
        for(String label:new String[]{"Deny","Cancel","不允许","返回","Search"})assertNull(label,risk(label,"",false,"permission request"));
    }
    private static RunControl control(){RunControl value=new RunControl();value.bindHostRun("fixture-task","fixture-run",1);return value;}
    @Test public void originalOcrTargetAndAmbiguousRiskCoordinateRefuseWithoutLeakingRecognizedSecrets()throws Exception {
        RunControl c=control();OcrPage.Line title=new OcrPage.Line(1,"Payment confirmation synthetic private amount",0,60,150,80),button=new OcrPage.Line(2,"Confirm",20,100,100,120);
        OcrPage page=new OcrPage(200,200,200,200,List.of(title,button));OcrTextTarget target=new OcrTextTarget("native-image",0,c,page,button);
        var owner=assertThrows(NativeSensitiveStep.OwnerRequired.class,()->target.requireNonSensitive(c,0));assertEquals(PAYMENT_CONFIRMATION,owner.reason);assertFalse(owner.notDispatchedReceipt().toString().contains("private amount"));assertTrue(owner.belongsTo(c,0));assertFalse(owner.belongsTo(control(),0));
        var evidence=NativeSensitiveStep.ocrEvidence(page);assertThrows(NativeSensitiveStep.OwnerRequired.class,()->evidence.requireCoordinate(520,550,false,c,0));evidence.requireCoordinate(900,900,false,c,0);
        c.pause();c.resume();assertFalse(owner.belongsTo(c,0));
    }
    private static JSONObject imageObservation(String id,boolean image)throws Exception {return imageObservation(id,image,1);}
    private static JSONObject imageObservation(String id,boolean image,int side)throws Exception {
        JSONObject out=new JSONObject().put("ok",true).put("observationId",id).put("displayId",5).put("sessionId","session").put("observedAtUptimeMs",1000).put("pauseRevision",0).put("nodes",new JSONArray()).put("truncated",false);
        if(image)out.put("imageBase64",side==1?"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=":"iVBORw0KGgoAAAANSUhEUgAAAMgAAADICAYAAACtWK6eAAACEUlEQVR4nO3TMQEAIAzAsPk3DQrWF45EQZ/OAVbzOgB+ZhAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBYBAIBoFgEAgGgWAQCAaBcAEODrNzcVUZ+gAAAABJRU5ErkJggg==").put("imageMimeType","image/png").put("imageWidth",side).put("imageHeight",side);
        return ObservationReport.attach(out,false,"no_accessible_root",image,image?"synthetic_image":"not_requested");
    }
    @Test public void deliveredVisionImageUsesAuthorizedLocalOcrRiskBeforeCoordinateDispatch()throws Exception {
        for(boolean sensitive:new boolean[]{true,false}){
            java.util.concurrent.atomic.AtomicInteger localReads=new java.util.concurrent.atomic.AtomicInteger(),dispatches=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
            dev.pocketengine.vision.ocr.LocalImageOcr ocr=new dev.pocketengine.vision.ocr.LocalImageOcr(){public boolean available(){return ready.get();}public String engineVersion(){return "synthetic";}public void close(){}public OcrPage recognizeImage(byte[] bytes,String format,RunControl live){localReads.incrementAndGet();return new OcrPage(1,1,1,1,List.of(new OcrPage.Line(1,sensitive?"Pay now":"ordinary game canvas",0,0,1,1)));}};
            PhonePort source=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject observe(boolean image,RunControl live)throws Exception{return imageObservation(image?"pixels":"nodes",image);}public JSONObject execute(String tool,JSONObject args,RunControl live)throws Exception{dispatches.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true);}};
            AdaptivePhonePort p=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("fixture-task","fixture-run",1,"session",5),true,true,()->1000,ocr);RunControl c=control();JSONObject observed=p.observe(true,c);assertEquals("vision",observed.getJSONObject("observationDecision").getString("request"));assertEquals(0,localReads.get());ready.set(true);JSONObject args=new JSONObject().put("observationId","pixels").put("x",200).put("y",300);
            if(sensitive){assertThrows(NativeSensitiveStep.OwnerRequired.class,()->p.execute("phone_tap",args,c));assertEquals(0,dispatches.get());assertFalse(p.execute("phone_tap",args,c).getBoolean("accepted"));}
            else {JSONObject result=p.execute("phone_tap",args,c);assertTrue(result.toString(),result.getBoolean("accepted"));assertEquals(1,dispatches.get());}assertEquals(1,localReads.get());assertFalse(c.isPaused());
        }
    }
    @Test public void pureImageNoRiskAndOrdinaryOcrTargetPreserveExistingInputRatherThanClaimSafety()throws Exception {
        RunControl c=control();OcrPage.Line ordinary=new OcrPage.Line(1,"Search",0,0,100,20);var evidence=NativeSensitiveStep.ocrEvidence(new OcrPage(200,200,200,200,List.of(ordinary)));
        evidence.requireCoordinate(900,900,false,c,0);evidence.requireTarget("Search",c,0);
        var page=NativeSensitiveStep.ocrEvidence(new OcrPage(200,200,200,200,List.of(new OcrPage.Line(1,"Payment confirmation",0,0,150,20),new OcrPage.Line(2,"Search",0,100,100,120))));page.requireTarget("Search",c,0);page.requireCoordinate(900,900,true,c,0);
    }
    @Test public void nativeWordUsesItsOriginalLineNotAnUnrelatedFinancialWordOrAWholePageExemption()throws Exception {
        RunControl c=control();List<OcrPage.Line> lines=List.of(new OcrPage.Line(1,"Transfer history",0,0,150,20),new OcrPage.Line(2,"Payment methods",0,30,150,50),new OcrPage.Line(3,"Do not allow",0,60,150,80),new OcrPage.Line(4,"Confirm payment",0,90,150,110));
        List<OcrPage.Word> words=List.of(new OcrPage.Word(1,1,"Transfer",0,0,70,20,90),new OcrPage.Word(2,2,"Payment",0,30,70,50,90),new OcrPage.Word(3,3,"allow",80,60,150,80,90),new OcrPage.Word(4,4,"Confirm",0,90,70,110,90));
        OcrPage page=new OcrPage(200,200,200,200,lines,words,false);for(int i=0;i<3;i++)new OcrTextTarget("native-word-page",0,c,page,words.get(i)).requireNonSensitive(c,0);
        var owner=assertThrows(NativeSensitiveStep.OwnerRequired.class,()->new OcrTextTarget("native-word-page",0,c,page,words.get(3)).requireNonSensitive(c,0));assertEquals(PAYMENT_CONFIRMATION,owner.reason);
    }
    @Test public void ordinaryWordInsideSensitiveNativeLineCannotBypassItsBusinessStep()throws Exception {
        RunControl c=control();var pay=new OcrPage.Line(7,"Pay now",0,130,100,150);var now=new OcrPage.Word(5,7,"now",60,130,100,150,90);var action=new OcrPage(200,200,200,200,List.of(pay),List.of(now),false);assertThrows(NativeSensitiveStep.OwnerRequired.class,()->new OcrTextTarget("native-word-pay",0,c,action,now).requireNonSensitive(c,0));
        var allow=new OcrPage.Line(8,"Allow access",0,130,100,150);var access=new OcrPage.Word(6,8,"access",40,130,100,150,90);var permission=new OcrPage(200,200,200,200,List.of(allow),List.of(access),false);assertThrows(NativeSensitiveStep.OwnerRequired.class,()->new OcrTextTarget("native-word-allow",0,c,permission,access).requireNonSensitive(c,0));
        var farPay=new OcrPage.Line(1,"Pay now",140,0,190,20);var ordinaryConfirm=new OcrPage.Line(2,"Confirm",0,150,70,170);var unrelated=new OcrPage(200,200,200,200,List.of(farPay,ordinaryConfirm));new OcrTextTarget("unrelated-native-context",0,c,unrelated,ordinaryConfirm).requireNonSensitive(c,0);
        var outside=new OcrPage.Word(7,7,"now",60,150,100,170,90);assertThrows(IllegalArgumentException.class,()->new OcrTextTarget("bad-native-word",0,c,action,outside));
    }
    @Test public void ocrSwipeUsesTheEntireOriginalStrokeButLeavesUnrelatedScrollingAlone()throws Exception {
        for(boolean intersects:new boolean[]{true,false}){var ready=new java.util.concurrent.atomic.AtomicBoolean();var effects=new java.util.concurrent.atomic.AtomicInteger();dev.pocketengine.vision.ocr.LocalImageOcr ocr=new dev.pocketengine.vision.ocr.LocalImageOcr(){public boolean available(){return ready.get();}public String engineVersion(){return "synthetic-slider";}public void close(){}public OcrPage recognizeImage(byte[] bytes,String format,RunControl live){return new OcrPage(200,200,200,200,List.of(new OcrPage.Line(1,"Slide to pay",100,90,180,110)));}};
            PhonePort source=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject observe(boolean image,RunControl live)throws Exception{return imageObservation(image?"pixels":"nodes",image,200);}public JSONObject execute(String tool,JSONObject args,RunControl live)throws Exception{effects.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true);}};
            AdaptivePhonePort port=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("fixture-task","fixture-run",1,"session",5),true,true,()->1000,ocr);RunControl c=control();assertTrue(port.observe(true,c).getBoolean("ok"));ready.set(true);JSONObject args=new JSONObject().put("observationId","pixels").put("x1",100).put("y1",intersects?500:800).put("x2",900).put("y2",intersects?500:800).put("durationMs",500);
            if(intersects){assertThrows(NativeSensitiveStep.OwnerRequired.class,()->port.execute("phone_swipe",args,c));assertEquals(0,effects.get());}else {assertTrue(port.execute("phone_swipe",args,c).getBoolean("accepted"));assertEquals(1,effects.get());}
        }
    }
    @Test public void mixedOriginalLineCannotEraseAnExplicitCommitOrAnExistingChineseSlideRule()throws Exception {
        RunControl c=control();var line=new OcrPage.Line(1,"Payment methods Pay now",0,0,190,20);var word=new OcrPage.Word(1,1,"now",150,0,190,20,90);var page=new OcrPage(200,200,200,200,List.of(line),List.of(word),false);assertThrows(NativeSensitiveStep.OwnerRequired.class,()->new OcrTextTarget("mixed-line",0,c,page,word).requireNonSensitive(c,0));
        assertEquals(SENSITIVE_TARGET_UNCERTAIN,risk("Payment methods Pay now","",false,""));
        for(String text:new String[]{"滑动转账","滑动汇款"}){var evidence=NativeSensitiveStep.ocrEvidence(new OcrPage(200,200,200,200,List.of(new OcrPage.Line(1,text,100,90,180,110))));var blocked=assertThrows(NativeSensitiveStep.OwnerRequired.class,()->evidence.requireCoordinate(100,500,900,500,true,c,0));assertEquals(TRANSFER_CONFIRMATION,blocked.reason);}
    }
    @Test public void blockedCoordinateRiskReadDoesNotHoldControlOrPublishLateEvidence()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);var ready=new java.util.concurrent.atomic.AtomicBoolean();var dispatches=new java.util.concurrent.atomic.AtomicInteger();
        dev.pocketengine.vision.ocr.LocalImageOcr ocr=new dev.pocketengine.vision.ocr.LocalImageOcr(){public boolean available(){return ready.get();}public String engineVersion(){return "synthetic-blocking";}public void close(){}public OcrPage recognizeImage(byte[] bytes,String format,RunControl live)throws Exception{entered.countDown();try{while(true)try{release.await();break;}catch(InterruptedException ignored){}return new OcrPage(1,1,1,1,List.of(new OcrPage.Line(1,"Pay now",0,0,1,1)));}finally{finished.countDown();}}};
        PhonePort source=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject observe(boolean image,RunControl live)throws Exception{return imageObservation(image?"pixels":"nodes",image);}public JSONObject execute(String tool,JSONObject args,RunControl live)throws Exception{dispatches.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true);}};
        AdaptivePhonePort port=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("fixture-task","fixture-run",1,"session",5),true,true,()->1000,ocr);RunControl control=control();port.observe(true,control);ready.set(true);var caller=java.util.concurrent.Executors.newSingleThreadExecutor();JSONObject args=new JSONObject().put("observationId","pixels").put("x",200).put("y",300);
        try{var pending=caller.submit(()->port.execute("phone_tap",args,control));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));control.pause();var stopped=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(1,java.util.concurrent.TimeUnit.SECONDS));assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof InterruptedException);port.clearReferences();control.resume();assertFalse(port.execute("phone_tap",args,control).getBoolean("accepted"));assertEquals(0,dispatches.get());}
        finally{release.countDown();assertTrue(finished.await(2,java.util.concurrent.TimeUnit.SECONDS));caller.shutdownNow();caller.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS);}
        assertEquals(0,dispatches.get());
    }
}
