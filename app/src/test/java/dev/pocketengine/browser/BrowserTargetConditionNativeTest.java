package dev.pocketengine.browser;

import android.app.Application;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import dev.pocketengine.core.RunControl;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Actual native issued-ref adapter; evaluateJavascript explicitly supplies interface fixture data, not Chromium. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserTargetConditionNativeTest {
    static final String EPOCH="original-profile-run-page-lease-pause";
    static final class View extends WebView {
        int targetCaptures;boolean defer;ValueCallback<String> delayed;String delayedValue;
        View(){super(RuntimeEnvironment.getApplication());}
        @Override public String getUrl(){return "https://read.example/async-form";}
        @Override public String getTitle(){return "Explicit native interface fixture";}
        @Override public void evaluateJavascript(String code,ValueCallback<String> callback){try{
            int start=code.indexOf("const request=")+"const request=".length(),end=code.indexOf(",nativeKey=",start);JSONObject request=new JSONObject(code.substring(start,end));JSONObject response;
            if(request.has("state")){targetCaptures++;response=j("targetProbe",true,"status","observed","code","target_state_read","matched",true,"actual",j("present",true,"instance","1","value","已保存","valueLength",3,"valueTruncated",false),"coverage",j("nodesVisited",4,"textCharsVisited",3,"queuedPeak",1,"layoutChecks",1,"partial",false,"reasons",new JSONArray()));if(defer){delayed=callback;delayedValue=JSONObject.quote(response.toString());return;}}
            else{var m=Pattern.compile("regionNamespace=\"([0-9a-f-]{36})\"").matcher(code);assertTrue(m.find());String region=m.group(1)+":r1";response=raw("outline");response.getJSONObject("read").put("data",new JSONArray().put(j("regionRef",region,"role","form","name","保存表单","preview","当前值","readOnlyEvidence",true)));}
            callback.onReceiveValue(JSONObject.quote(response.toString()));
        }catch(Exception invalid){throw new AssertionError(invalid);}}
        void deliver(){assertNotNull(delayed);delayed.onReceiveValue(delayedValue);}
    }
    static JSONObject outline(BrowserFrameObserver observer)throws Exception {String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> future=new CompletableFuture<>();observer.read(j("mode","outline"),id,EPOCH,()->{},future);return future.get(1,TimeUnit.SECONDS).put("observationId",id);}
    static String ref(JSONObject outline)throws Exception{return outline.getJSONObject("read").getJSONArray("data").getJSONObject(0).getString("regionRef");}
    static JSONObject target(String ref)throws Exception{return j("regionRef",ref,"state","value","selector",j("role","textbox","name","方案"),"value","已保存");}
    static CompletableFuture<JSONObject> probe(BrowserFrameObserver observer,JSONObject target,String epoch,BrowserFrameObserver.Check current)throws Exception {CompletableFuture<JSONObject> result=new CompletableFuture<>();observer.condition(target,epoch,current,result);return result;}
    @Test public void unpresentedRegionCannotStartAConditionProbeAndDoesNotDestroyPendingPresentation()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){JSONObject captured=outline(observer),target=target(ref(captured));JSONObject blocked=probe(observer,target,EPOCH,()->{}).get(1,TimeUnit.SECONDS);assertEquals("read_reference_not_current",blocked.getString("code"));assertEquals(0,view.targetCaptures);observer.presented(captured);assertTrue(probe(observer,target,EPOCH,()->{}).get(1,TimeUnit.SECONDS).getBoolean("matched"));assertEquals(1,view.targetCaptures);}finally{view.destroy();}
    }
    @Test public void guessedUnissuedSiblingAndChangedEpochNeverReadHiddenCandidateNodes()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){JSONObject captured=outline(observer);observer.presented(captured);String ref=ref(captured);assertEquals("read_reference_not_current",probe(observer,target(ref.replace(":r1",":r2")),EPOCH,()->{}).get().getString("code"));assertEquals("read_reference_not_current",probe(observer,target(ref),EPOCH+"-new-run",()->{}).get().getString("code"));assertEquals(0,view.targetCaptures);}finally{view.destroy();}
    }
    @Test public void nativeReadTtlAndDocumentChangesCannotReviveOldRegionKnowledge()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){JSONObject captured=outline(observer);observer.presented(captured);String ref=ref(captured);ShadowSystemClock.advanceBy(Duration.ofSeconds(121));assertEquals("read_reference_not_current",probe(observer,target(ref),EPOCH,()->{}).get().getString("code"));observer.documentStarted();assertEquals("read_reference_not_current",probe(observer,target(ref),EPOCH,()->{}).get().getString("code"));assertEquals(0,view.targetCaptures);}finally{view.destroy();}
    }
    @Test public void navigationBeforeCallbackCannotPublishTheOldTargetsValues()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){JSONObject captured=outline(observer);observer.presented(captured);view.defer=true;CompletableFuture<JSONObject> future=probe(observer,target(ref(captured)),EPOCH,()->{});observer.documentStarted();view.deliver();assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS));}finally{view.destroy();}
    }
    @Test public void originalControlCancellationRejectsLateProbeWithoutWaitingForResume()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){JSONObject captured=outline(observer);observer.presented(captured);RunControl original=new RunControl();view.defer=true;CompletableFuture<JSONObject> future=probe(observer,target(ref(captured)),EPOCH,original::check);original.cancel();view.deliver();assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS));}finally{view.destroy();}
    }
    @Test public void completedOrCancelledProbeDoesNotRegisterNewReadOrActionRefs()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){JSONObject captured=outline(observer);observer.presented(captured);String ref=ref(captured);JSONObject actual=probe(observer,target(ref),EPOCH,()->{}).get();assertTrue(actual.getBoolean("readOnlyEvidence"));assertEquals("main",actual.getString("frameId"));assertFalse(actual.getJSONObject("frameCoverage").getBoolean("childFrameContentsRead"));assertFalse(actual.has("observationId"));assertFalse(actual.has("elements"));assertFalse(actual.has("read"));view.defer=true;CompletableFuture<JSONObject> late=probe(observer,target(ref),EPOCH,()->{});late.cancel(false);view.deliver();assertTrue(late.isCancelled());assertEquals("read_reference_not_current",probe(observer,target(ref.replace(":r1",":r2")),EPOCH,()->{}).get().getString("code"));}finally{view.destroy();}
    }
}
