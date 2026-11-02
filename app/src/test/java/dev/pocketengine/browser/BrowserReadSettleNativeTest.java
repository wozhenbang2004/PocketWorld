package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Actual native scheduler/currentness, explicit JS response fixture. NOT a WebView rendering or CDP proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserReadSettleNativeTest {
    static final class View extends WebView {
        int captures,cleanups;
        View(){super(RuntimeEnvironment.getApplication());}
        @Override public String getUrl(){return "https://read.example/report";}
        @Override public String getTitle(){return "Native settle fixture";}
        @Override public void evaluateJavascript(String code,ValueCallback<String> callback){try{
            if(code.contains("__pw_settle_cleanup")){cleanups++;callback.onReceiveValue("null");return;}
            captures++;Matcher m=Pattern.compile("regionNamespace=\"([0-9a-f-]{36})\"").matcher(code);assertTrue(m.find());JSONObject value=raw("text").put("url",getUrl());JSONObject read=value.getJSONObject("read");read.getJSONObject("facts").put("regionRef",m.group(1)+":r1");read.put("dynamic",j("scope","selected_region_and_nearest_container","busy",captures<3,"contentItems",1,"observerActive",true,"mutationVersion",captures<3?0:1,"pagination",new JSONArray(),"paginationPartial",false,"nodesVisited",3,"networkIdleProven",false,"trust","untrusted_rendered_dom"));callback.onReceiveValue(JSONObject.quote(value.toString()));
        }catch(Exception invalid){throw new AssertionError(invalid);}}
    }
    static CompletableFuture<JSONObject> start(BrowserFrameObserver observer,BrowserFrameObserver.Check check)throws Exception {CompletableFuture<JSONObject> result=new CompletableFuture<>();observer.read(j("mode","text","settle",j("timeoutMs",2000,"stableMs",600)),UUID.randomUUID().toString(),"original_run",check,result);return result;}
    @Test public void sameOriginalObserverWaitsPastBusyAndReturnsOnlyFinalProjection()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){CompletableFuture<JSONObject> result=start(observer,()->{});assertFalse(result.isDone());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));assertTrue(result.isDone());JSONObject out=result.get();assertTrue(out.getJSONObject("read").getJSONObject("settle").getBoolean("conditionMet"));assertEquals(6,view.captures);assertEquals(6,out.getJSONObject("read").getJSONObject("settle").getInt("samples"));assertFalse(out.getJSONObject("read").getJSONObject("settle").getBoolean("networkIdleProven"));}finally{view.destroy();}}
    @Test public void cancellationAndDocumentChangeStopBeforeAnotherCapture()throws Exception {
        for(boolean cancel:new boolean[]{true,false}){View view=new View();AtomicBoolean live=new AtomicBoolean(true);try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){CompletableFuture<JSONObject> result=start(observer,()->{if(!live.get())throw new SecurityException("original run cancelled or owner took over");});if(cancel)live.set(false);else observer.documentStarted();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));assertTrue(result.isCompletedExceptionally());assertEquals(1,view.captures);}finally{view.destroy();}}
    }
    @Test public void cancelledCallerDoesNotKeepPollingOrPublishAnIntermediateRead()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){CompletableFuture<JSONObject> result=start(observer,()->{});result.cancel(false);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));assertTrue(result.isCancelled());assertEquals(1,view.captures);assertEquals(1,view.cleanups);}finally{view.destroy();}}
    @Test public void unpresentedChildIsTypedUnavailableWithoutMainDocumentFallback()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){CompletableFuture<JSONObject> result=new CompletableFuture<>();observer.read(j("mode","text","frameRef",UUID.randomUUID().toString(),"settle",j()),UUID.randomUUID().toString(),"original_run",()->{},result);assertEquals("frame_ref_not_current",result.get().getString("code"));assertEquals(0,view.captures);assertEquals(0,view.cleanups);}finally{view.destroy();}}
}
