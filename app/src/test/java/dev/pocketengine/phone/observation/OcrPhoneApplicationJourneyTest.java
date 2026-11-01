package dev.pocketengine.phone.observation;

import static org.junit.Assert.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.vision.ocr.OcrPage;
import dev.pocketengine.vision.ocr.TesseractLocalOcrEngine;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** Actual native App/service/Runner/TaskStore/HTTP; OCR/physical-display data are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=EngineApplication.class,shadows={OcrPhoneApplicationJourneyTest.Safety.class,OcrPhoneApplicationJourneyTest.Ocr.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class OcrPhoneApplicationJourneyTest {
    private static final AtomicInteger effects=new AtomicInteger();
    private static volatile boolean wordFixture;
    @Implements(PhoneAccessibilityService.class) public static class Safety {@Implementation public String observationSafetyFailure(){return null;}}
    @Implements(TesseractLocalOcrEngine.class) public static class Ocr {
        @Implementation public boolean available(){return true;}
        @Implementation public String engineVersion(){return "controlled-ocr-fixture-not-jni";}
        @Implementation public OcrPage recognizeImage(byte[] bytes,String format,RunControl c)throws Exception {
            assertEquals("png",format);assertTrue(bytes.length>24);
            if(wordFixture&&effects.get()==0)return new OcrPage(320,160,320,160,List.of(new OcrPage.Line(1,"取消 继续 保存",20,20,280,60)),
                    List.of(new OcrPage.Word(1,1,"取消",20,20,80,60,95),new OcrPage.Word(2,1,"继续",120,20,180,60,84),new OcrPage.Word(3,1,"保存",220,20,280,60,57)),false);
            return new OcrPage(320,160,320,160,List.of(new OcrPage.Line(1,effects.get()==0?"继续":"页面已完成",20,20,140,60)));
        }
    }
    private EngineApplication app;private PhoneRunService service;private HttpServer server;
    private static JSONObject j(Object... values)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o;}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static void await(BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!condition.getAsBoolean()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertTrue("actual phone host did not reach expected state",condition.getAsBoolean());}
    private static boolean held(EngineApplication app,String id){try{return app.control(id).isPaused()&&app.tasks.get(id,false).optString("status").matches("awaiting_user|reconciling");}catch(Exception e){return false;}}
    private final class Physical implements PhonePort,NativeOcrTapPort {
        final String session,pixels;final RunControl original;final boolean loseReceipt;String current;int postObservations,preparedAtEntry;
        Physical(String session,RunControl original,boolean loseReceipt)throws Exception{this.session=session;this.original=original;this.loseReceipt=loseReceipt;Bitmap bitmap=Bitmap.createBitmap(320,160,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes));bitmap.recycle();pixels=java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());}
        public boolean isConnected(){return true;}public void clearReferences(){current=null;}
        public JSONObject execute(String name,JSONObject args,RunControl c){throw new AssertionError("untyped physical operation "+name);}
        public JSONObject observe(boolean image,RunControl c)throws Exception{assertSame(original,c);current=UUID.randomUUID().toString();if(effects.get()>0)postObservations++;
            JSONObject out=j("ok",true,"observationId",current,"displayId",0,"sessionId",session,"observedAtUptimeMs",SystemClock.uptimeMillis(),"pauseRevision",c.pauseRevision(),"screenWidth",320,"screenHeight",160,"packageName","fixture.canvas","nodes",new JSONArray(),"truncated",false);
            if(image)out.put("imageBase64",pixels).put("imageMimeType","image/png").put("imageWidth",320).put("imageHeight",160);
            return ObservationReport.attach(out,false,"no_accessible_root",image,image?"controlled-full-frame":"not_requested");}
        public JSONObject tapOcrText(OcrTextTarget target,RunControl c,NativePhoneEntry.Admission admission)throws Exception {
            assertSame(original,c);assertTrue(target.matches(c,current,c.pauseRevision()));assertEquals(320,target.sourceWidth);assertEquals(160,target.sourceHeight);
            return NativePhoneEntry.call(c,c.pauseRevision(),admission,()->{
                JSONObject task=app.tasks.get(app.activeId(),false),operation=task.getJSONArray("operations").getJSONObject(0);assertEquals("prepared",operation.getString("state"));assertEquals(AdaptivePhonePort.CLICK_TEXT,operation.getString("tool"));assertEquals(target.id,operation.getJSONObject("arguments").getString("textTargetId"));preparedAtEntry++;
                JSONObject point=target.tapArguments(320,160);assertTrue(point.getInt("x")>0&&point.getInt("x")<1000);
                if(wordFixture){assertEquals("保存",target.text);assertEquals("word",target.granularity);assertTrue("must target the third actual box, not the line center",point.getInt("x")>700);}
                effects.incrementAndGet();if(loseReceipt)throw new java.io.IOException("lost receipt after native effect");
                return j("ok",true,"accepted",true,"verified",false,"state","controlled_native_tap_accepted");});
        }
    }
    @After public void close()throws Exception{if(app!=null&&app.activeId()!=null)app.stop(app.activeId());if(service!=null)service.onDestroy();if(server!=null)server.stop(0);if(app!=null&&app.features!=null)app.features.close();effects.set(0);wordFixture=false;Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
    private void journey(boolean loseReceipt)throws Exception {
        journey(loseReceipt,false);
    }
    private void journey(boolean loseReceipt,boolean words)throws Exception {
        wordFixture=words;
        app=(EngineApplication)RuntimeEnvironment.getApplication();PhoneAccessibilityService accessibility=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,accessibility);
        AtomicInteger requests=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try{JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int call=requests.incrementAndGet();String serialized=body.getJSONArray("messages").toString();assertFalse(serialized.contains("data:image/"));assertTrue(serialized.contains("actualImageAttached\\\":false"));
            String name;JSONObject args;
            if(call==1){assertTrue(body.getJSONArray("tools").toString().contains("phone_click_text"));JSONObject observed=null;JSONArray messages=body.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONArray parts=messages.getJSONObject(i).optJSONArray("content");if(parts==null)continue;for(int k=0;k<parts.length();k++){String text=parts.getJSONObject(k).optString("text");int begin=text.indexOf('{');if(begin>=0)try{JSONObject value=new JSONObject(text.substring(begin));if(value.has("ocr"))observed=value;}catch(Exception other){}}}
                assertNotNull("current native OCR observation absent",observed);assertFalse(observed.has("imageBase64"));JSONObject line=observed.getJSONObject("ocr").getJSONArray("lines").getJSONObject(0),selected=line;
                if(words){assertEquals("取消 继续 保存",line.getString("text"));assertFalse(line.has("textTargetId"));JSONArray candidates=observed.getJSONObject("ocr").getJSONArray("words");assertEquals(3,candidates.length());selected=candidates.getJSONObject(2);assertEquals("保存",selected.getString("text"));assertEquals(57d,selected.getDouble("confidence"),0d);}
                else assertEquals("继续",line.getString("text"));name=AdaptivePhonePort.CLICK_TEXT;args=j("observationId",observed.getString("observationId"),"textTargetId",selected.getString("textTargetId"));
            }else{assertFalse("unknown effect must stop before another model request",loseReceipt);assertEquals(2,call);assertTrue(serialized.contains("页面已完成"));name="phone_request_user";args=j("reason","已点击本地OCR文字并重新观察，请主人核对结果");}
            byte[] result=j("choices",new JSONArray().put(j("message",j("role","assistant","tool_calls",new JSONArray().put(j("id","ocr-"+call,"type","function","function",j("name",name,"arguments",args.toString())))),"finish_reason","tool_calls"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        }catch(Throwable problem){failure.set(problem);exchange.sendResponseHeaders(500,0);exchange.close();}});server.start();
        JSONObject task=app.tasks.startAttempt(app.tasks.create("在当前Canvas页点击已识别的继续并观察结果","main_screen").getString("id"),"main_screen",8);String id=task.getString("id"),run=task.getString("runId");RunControl original=new RunControl();original.bindHostRun(id,run,task.getInt("coordinatorEpoch"));
        ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","ocr-text-model",true,8,5);
        set(app,"activeTaskId",id);set(app,"activeControl",original);set(app,"activeMode","main_screen");set(app,"activeConfig",config);
        Physical physical=new Physical(accessibility.observationSessionId(),original,loseReceipt);service=Robolectric.buildService(PhoneRunService.class).create().get();set(service,"heldPort",physical);
        service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.START).putExtra("taskId",id),0,1);Shadows.shadowOf(Looper.getMainLooper()).idleFor(750,TimeUnit.MILLISECONDS);await(()->held(app,id));
        assertNull(String.valueOf(failure.get()),failure.get());JSONObject retained=app.tasks.get(id,false);assertEquals(run,retained.getString("runId"));assertSame(original,app.control(id));assertEquals(1,effects.get());assertEquals(1,physical.preparedAtEntry);assertEquals(1,retained.getJSONArray("operations").length());
        JSONObject operation=retained.getJSONArray("operations").getJSONObject(0),receipt=operation.getJSONObject("receipt");
        if(loseReceipt){assertEquals(1,requests.get());assertEquals("unknown",operation.getString("state"));assertTrue(receipt.isNull("accepted"));assertTrue(app.tasks.hasUnresolved(id));assertEquals(0,physical.postObservations);assertThrows(IllegalStateException.class,()->app.continueTask(id,"",true,"main_screen"));}
        else{assertEquals(2,requests.get());assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertTrue(physical.postObservations>0);assertFalse(app.tasks.hasUnresolved(id));}
        if(words){assertEquals("word",receipt.getJSONObject("ocrTextTarget").getString("granularity"));assertEquals("保存",receipt.getJSONObject("ocrTextTarget").getString("recognizedText"));assertFalse(receipt.getJSONObject("ocrTextTarget").getBoolean("verifiedBusinessSuccess"));}
        System.out.println("OCR_PHONE_JOURNEY wordFixture="+words+" loseReceipt="+loseReceipt+" sameRun=true sameControl=true modelRequests="+requests.get()+" preparedAtEntry="+physical.preparedAtEntry+" nativeEffects="+effects.get()+" postObservations="+physical.postObservations+" rawImagesUploaded=false operationState="+operation.getString("state"));
    }
    @Test public void textOnlyModelUsesNativeOcrTargetThroughOriginalPreparedOperationAndPostObservation()throws Exception{journey(false);}
    @Test public void enteredEffectWithLostReceiptRemainsUnknownAndCannotContinueBlindly()throws Exception{journey(true);}
    @Test public void sameLineWordUsesOriginalModelDeliveryPreparedOperationAndNativeBox()throws Exception{journey(false,true);}
    @Test public void sameLineWordUnknownEffectDoesNotReplayOrReachAnotherModelTurn()throws Exception{journey(true,true);}
}
