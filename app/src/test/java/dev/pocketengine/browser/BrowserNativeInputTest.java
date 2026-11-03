package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/** Android dispatch/cleanup tests. Robolectric does not prove a renderer's event.isTrusted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserNativeInputTest {
    private static JSONObject obj(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static final class View extends WebView {
        final List<Integer> motions=new ArrayList<>(),sources=new ArrayList<>(),keys=new ArrayList<>();final List<Float> xs=new ArrayList<>(),ys=new ArrayList<>();boolean accept=true;Runnable down=()->{};
        View(){super(RuntimeEnvironment.getApplication());measure(MeasureSpec.makeMeasureSpec(400,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(600,MeasureSpec.EXACTLY));layout(0,0,400,600);assertEquals(400,getWidth());assertEquals(600,getHeight());}
        // SDK 35 WebView delegates this hidden hook to its provider. Robolectric's provider is a
        // deep proxy, so use the real framework View frame/layout path, not fabricated dimensions.
        protected boolean setFrame(int left,int top,int right,int bottom){return Shadow.directlyOn(this,android.view.View.class,"setFrame",ClassParameter.from(int.class,left),ClassParameter.from(int.class,top),ClassParameter.from(int.class,right),ClassParameter.from(int.class,bottom));}
        @Override public boolean dispatchTouchEvent(MotionEvent event){motions.add(event.getActionMasked());sources.add(event.getSource());xs.add(event.getX());ys.add(event.getY());if(event.getActionMasked()==MotionEvent.ACTION_DOWN)down.run();return accept;}
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event){motions.add(event.getActionMasked());sources.add(event.getSource());xs.add(event.getX());ys.add(event.getY());return accept;}
        @Override public boolean dispatchKeyEvent(KeyEvent event){keys.add(event.getAction());sources.add(event.getSource());if(event.getAction()==KeyEvent.ACTION_DOWN)down.run();return accept;}
    }
    private static JSONObject geometry()throws Exception {return obj("status","geometry_observed","from",obj("x",50,"y",100),"to",obj("x",150,"y",200),"viewport",obj("width",200,"height",300,"offsetX",0,"offsetY",0));}
    private static JSONObject args(String action)throws Exception {JSONObject out=obj("action",action,"ref","n0");if(action.equals("drag"))out.put("toRef","n1");if(action.equals("key"))out.put("key","Enter");return out;}
    @Test public void dragUsesActualViewScaledNativeEventsAndChecksBetweenSteps()throws Exception {
        View view=new View();AtomicInteger depth=new AtomicInteger(),checks=new AtomicInteger(),probes=new AtomicInteger();CompletableFuture<JSONObject> result=new CompletableFuture<>();
        BrowserNativeInput.dispatch(view,args("drag"),geometry(),checks::incrementAndGet,(ready,failed)->{assertEquals(0,depth.get());probes.incrementAndGet();ready.run();},depth::incrementAndGet,depth::decrementAndGet,result);
        assertEquals(List.of(MotionEvent.ACTION_DOWN),view.motions);assertEquals(0,depth.get());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(result.get().getBoolean("platformDispatchAccepted"));assertFalse(result.get().getBoolean("verified"));assertFalse(result.get().getBoolean("automaticReplayAllowed"));assertEquals(10,view.motions.size());assertEquals(9,probes.get());assertTrue(checks.get()>10);assertEquals(MotionEvent.ACTION_UP,(int)view.motions.get(9));assertEquals(300f,view.xs.get(9),0f);assertEquals(400f,view.ys.get(9),0f);assertTrue(view.sources.stream().allMatch(source->source==InputDevice.SOURCE_TOUCHSCREEN));assertEquals(0,depth.get());view.destroy();
    }
    @Test public void realOwnerChangeBetweenEventsStopsDragAndCancelsPressedPointer()throws Exception {
        View view=new View();AtomicBoolean current=new AtomicBoolean(true);AtomicInteger depth=new AtomicInteger();CompletableFuture<JSONObject> result=new CompletableFuture<>();
        BrowserNativeInput.dispatch(view,args("drag"),geometry(),()->{if(!current.get())throw new SecurityException("owner took over");},(ready,failed)->ready.run(),depth::incrementAndGet,depth::decrementAndGet,result);
        assertEquals(0,depth.get());current.set(false);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL),view.motions);assertEquals("unknown",result.get().getString("status"));assertTrue(result.get().isNull("accepted"));assertEquals(0,depth.get());view.destroy();
    }
    @Test public void cancelledOriginalFutureReleasesPointerAndNeverPostsAnotherDown()throws Exception {
        View view=new View();CompletableFuture<JSONObject> result=new CompletableFuture<>();BrowserNativeInput.dispatch(view,args("tap"),geometry(),()->{},(ready,failed)->ready.run(),()->{},()->{},result);result.cancel(false);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertTrue(result.isCancelled());assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL),view.motions);view.destroy();
    }
    @Test public void freshGeometryIsRequiredAgainBeforeDelayedPointerUp()throws Exception {
        View view=new View();CompletableFuture<JSONObject> result=new CompletableFuture<>();BrowserNativeInput.dispatch(view,args("tap"),geometry(),()->{},(ready,failed)->failed.accept(new SecurityException("covered by overlay")),()->{},()->{},result);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL),view.motions);assertEquals("unknown",result.get().getString("status"));view.destroy();
    }
    @Test public void keysUseNativeKeyboardAndKeyUpIsReleasedAfterFocusRevocation()throws Exception {
        View view=new View();AtomicBoolean current=new AtomicBoolean(true);view.down=()->current.set(false);CompletableFuture<JSONObject> result=new CompletableFuture<>();BrowserNativeInput.dispatch(view,args("key"),geometry(),()->{if(!current.get())throw new SecurityException("focus revoked");},(ready,failed)->ready.run(),()->{},()->{},result);assertEquals(List.of(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP),view.keys);assertEquals(InputDevice.SOURCE_KEYBOARD,(int)view.sources.get(0));assertEquals("unknown",result.get().getString("status"));view.destroy();
    }
    @Test public void hoverUsesMouseAndRejectedDispatchIsUnknownInsteadOfBusinessSuccess()throws Exception {
        View view=new View();view.accept=false;CompletableFuture<JSONObject> result=new CompletableFuture<>();BrowserNativeInput.dispatch(view,args("hover"),geometry(),()->{},(ready,failed)->ready.run(),()->{},()->{},result);assertEquals(List.of(MotionEvent.ACTION_HOVER_MOVE),view.motions);assertEquals(InputDevice.SOURCE_MOUSE,(int)view.sources.get(0));assertEquals("unknown",result.get().getString("status"));assertFalse(result.get().getBoolean("businessOutcomeVerified"));view.destroy();
    }
    @Test public void relativeCoordinatesAndKeysCannotBecomeAnUnrestrictedInputApi()throws Exception {
        assertThrows(Exception.class,()->BrowserNativeInput.validate(obj("action","tap","ref","n0","x",1000,"y",1)));assertThrows(Exception.class,()->BrowserNativeInput.validate(obj("action","drag","ref","n0")));assertThrows(Exception.class,()->BrowserNativeInput.validate(obj("action","key","ref","n0","key","Control+V")));assertThrows(Exception.class,()->BrowserNativeInput.validate(obj("action","hover","ref","n0","toRef","n1")));
        JSONObject target=obj("ref","n0","nodeKey","k1","tag","BUTTON","type","button");String script=BrowserNativeInput.geometryScript("page","https://example.test/","observation",args("tap").put("leaseId","private-native-lease").put("taskId","private-task"),target,null,"readback");assertFalse(script.contains("private-native-lease"));assertFalse(script.contains("private-task"));
    }
    @Test public void readbackMustReferToSameNodeAndNeverConvertsPageWitnessToNativeProof()throws Exception {
        JSONObject dispatched=obj("accepted",true,"platformDispatchAccepted",true),missing=BrowserNativeInput.readback(dispatched,obj("status","unavailable"));assertEquals("unknown",missing.getString("status"));
        JSONObject read=BrowserNativeInput.readback(dispatched,obj("status","observed","sameNode",true,"nodeKey","k1","trustedEventObserved",true,"eventCount",2,"focused",true,"changed",obj("valueChanged",true)));assertEquals("native_input_read_back",read.getString("status"));assertFalse(read.getBoolean("verified"));assertEquals("page_world_untrusted",read.getJSONObject("nativeInputEvidence").getString("evidenceTrust"));JSONObject receipt=BrowserActionReceipt.attach("native_input",args("tap"),null,null,true,read);assertTrue(receipt.getJSONObject("actionReceipt").getJSONObject("dispatch").isNull("trustedInputEvent"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
    }
    @Test public void ownerReviewShowsOriginalTargetsRelativePositionsAndVisibleViewport()throws Exception {
        JSONObject request=args("drag").put("x",250).put("y",750).put("toX",600).put("toY",400);JSONObject pin=obj("pageUrl","https://example.test/original?record=one","nativeInputViewport",obj("width",400,"height",600),"targetPreview",obj("ref","n0","nodeKey","k1","tag","CANVAS","text","原画布","id","canvas"),"toTargetPreview",obj("ref","n1","nodeKey","k2","tag","BUTTON","text","原目标","id","drop"));String review=BrowserNativeInput.approvalSummary(request,pin);for(String expected:new String[]{"https://example.test/original?record=one","原画布","#canvas","n0","原目标","#drop","n1","250 / 1000","750 / 1000","600 / 1000","400 / 1000","400 × 600","触摸拖动","截图不提供坐标权限"})assertTrue(expected,review.contains(expected));
        JSONObject key=args("key");String keyReview=BrowserNativeInput.approvalSummary(key,pin);assertTrue(keyReview.contains("按键 Enter"));assertFalse(keyReview.contains("目标内相对位置"));
    }
    @Test public void originalRunPauseThenResumeStillCannotDispatchFromTheOldDecision()throws Exception {
        View view=new View();dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();long revision=original.pauseRevision();original.pause();original.resume();CompletableFuture<JSONObject> result=new CompletableFuture<>();AtomicInteger entered=new AtomicInteger();BrowserNativeInput.dispatch(view,args("tap"),geometry(),()->{},(ready,failed)->ready.run(),entered::incrementAndGet,()->{},original,revision,result);assertEquals("unavailable",result.get().getString("status"));assertFalse(result.get().getBoolean("accepted"));assertEquals(0,entered.get());assertTrue(view.motions.isEmpty());view.destroy();
    }
    @Test public void originalRunPauseAfterDownCancelsGestureWithoutAnotherMoveOrUp()throws Exception {
        View view=new View();dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();long revision=original.pauseRevision();CompletableFuture<JSONObject> result=new CompletableFuture<>();BrowserNativeInput.dispatch(view,args("drag"),geometry(),()->{},(ready,failed)->ready.run(),()->{},()->{},original,revision,result);original.pause();original.resume();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL),view.motions);assertEquals("unknown",result.get().getString("status"));view.destroy();
    }
}
