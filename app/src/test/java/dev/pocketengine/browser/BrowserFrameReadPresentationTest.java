package dev.pocketengine.browser;

import android.app.Application;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Real native adapter, explicit evaluateJavascript response fixture. No Chromium or provider coverage claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserFrameReadPresentationTest {
    static final class View extends WebView {
        int captures;boolean unavailable;String value="actual revenue fixture 42";
        View(){super(RuntimeEnvironment.getApplication());}
        @Override public String getUrl(){return "https://read.example/report?q=original";}
        @Override public String getTitle(){return "Native interface fixture";}
        @Override public void evaluateJavascript(String code,ValueCallback<String> callback){try{
            captures++;int start=code.indexOf("const request=")+"const request=".length(),end=code.indexOf(",nativeKey=",start);JSONObject request=new JSONObject(code.substring(start,end));Matcher m=Pattern.compile("regionNamespace=\"([0-9a-f-]{36})\"").matcher(code);assertTrue(m.find());String region=m.group(1)+":r1";
            JSONObject response;if(unavailable){unavailable=false;response=j("semanticRead",true,"status","unavailable","code","capture_time_budget");}
            else{String mode=request.getString("mode");response=raw(mode).put("url",getUrl()).put("text",value);JSONObject reading=response.getJSONObject("read");reading.put("baseRevision",request.opt("baseRevision")==null?JSONObject.NULL:request.get("baseRevision"));if(mode.equals("outline"))reading.put("data",new JSONArray().put(j("regionRef",region,"role","article","name","Revenue","preview",value,"readOnlyEvidence",true)));else{reading.put("data",new JSONArray().put(value));reading.getJSONObject("facts").put("regionRef",region);}}
            callback.onReceiveValue(JSONObject.quote(response.toString()));
        }catch(Exception error){throw new AssertionError(error);}}
    }
    static JSONObject read(BrowserFrameObserver observer,JSONObject request)throws Exception {String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> answer=new CompletableFuture<>();observer.read(request,id,"original-profile-run-page-lease-pause",()->{},answer);JSONObject value=answer.get(1,TimeUnit.SECONDS).put("observationId",id);return value.put("taskId","00000000-0000-0000-0000-000000000001").put("runId","00000000-0000-0000-0000-000000000002").put("epoch",1).put("profileId","test").put("profileRevision",1).put("profileIsolation","default-webview-only").put("browserSessionId","00000000-0000-0000-0000-000000000003").put("browserSpaceId","00000000-0000-0000-0000-000000000004").put("tabId","00000000-0000-0000-0000-000000000005").put("tabGeneration",1).put("navigation",1).put("ok",true);}
    static String region(JSONObject outline)throws Exception{return outline.getJSONObject("read").getJSONArray("data").getJSONObject(0).getString("regionRef");}
    static String revision(JSONObject value)throws Exception{return value.getJSONObject("read").getJSONObject("delivery").getString("revision");}
    static JSONObject delivered(BrowserFrameObserver observer,JSONObject captured)throws Exception {JSONObject wire=observer.presented(captured);observer.delivered(wire);return wire;}
    static void modelSaw(BrowserFrameObserver observer,JSONObject... wires)throws Exception {JSONArray messages=new JSONArray();for(JSONObject wire:wires)messages.put(j("role","tool","content",wire.toString()));observer.beforeModelRequest("actual-request",messages);observer.afterModelResponse("actual-request");}
    @Test public void registeredAndStagedButNotActuallyDeliveredReadRefsCannotStartAnotherCapture()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
        JSONObject captured=read(observer,j("mode","outline"));String ref=region(captured);assertEquals(1,view.captures);assertEquals("read_reference_not_current",read(observer,j("mode","text","regionRef",ref)).getString("code"));assertEquals(1,view.captures);
        assertThrows(Exception.class,()->observer.presented(captured));JSONObject fresh=read(observer,j("mode","outline")),wire=observer.presented(fresh);assertEquals(ref,region(fresh));assertEquals("read_reference_not_current",read(observer,j("mode","text","regionRef",ref)).getString("code"));assertEquals(2,view.captures);
        observer.delivered(wire);assertEquals("observed",read(observer,j("mode","text","regionRef",ref)).getString("status"));
    }finally{view.destroy();}}
    @Test public void absentBaselineResynchronizesFullWhileRegionStillMustBeActuallyKnown()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
        JSONObject outline=delivered(observer,read(observer,j("mode","outline")));String ref=region(outline);JSONObject first=delivered(observer,read(observer,j("mode","text","regionRef",ref)));String baseline=revision(first);modelSaw(observer,outline,first);int calls=view.captures;
        JSONObject resync=observer.presented(read(observer,j("mode","text","regionRef",ref,"baseRevision","f".repeat(64))));assertEquals("full",resync.getJSONObject("read").getJSONObject("delivery").getString("format"));assertEquals("full_resync_missing_base",resync.getJSONObject("read").getJSONObject("delivery").getString("comparison"));assertEquals(calls+1,view.captures);
        view.value="actual revised revenue 43";JSONObject refreshed=observer.presented(read(observer,j("mode","text","regionRef",ref,"baseRevision",baseline)));assertEquals("full",refreshed.getJSONObject("read").getJSONObject("delivery").getString("format"));assertEquals(baseline,refreshed.getJSONObject("read").getJSONObject("delivery").getString("baseRevision"));assertNotEquals(baseline,revision(refreshed));assertFalse(refreshed.getJSONObject("read").getJSONObject("delivery").getBoolean("deletionsInferred"));observer.delivered(refreshed);
    }finally{view.destroy();}}
    @Test public void temporaryUnreadableCaptureIsNotADeletionOrAReplacementBaseline()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
        JSONObject outline=delivered(observer,read(observer,j("mode","outline")));String ref=region(outline);JSONObject first=delivered(observer,read(observer,j("mode","text","regionRef",ref)));String baseline=revision(first);modelSaw(observer,outline,first);view.unavailable=true;
        JSONObject missing=read(observer,j("mode","text","regionRef",ref,"baseRevision",baseline));assertEquals("unavailable",missing.getString("status"));assertFalse(missing.has("read"));assertThrows(Exception.class,()->observer.presented(missing));
        JSONObject recovered=observer.presented(read(observer,j("mode","text","regionRef",ref,"baseRevision",baseline)));assertEquals("observed",recovered.getString("status"));assertFalse(recovered.getJSONObject("read").getJSONObject("delivery").getBoolean("deletionsInferred"));observer.delivered(recovered);
    }finally{view.destroy();}}
    @Test public void navigationInvalidatesBothStagedAndActuallyDeliveredReadIdentities()throws Exception {View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
        JSONObject outline=delivered(observer,read(observer,j("mode","outline")));String ref=region(outline);JSONObject pending=read(observer,j("mode","text","regionRef",ref));observer.documentStarted();assertThrows(Exception.class,()->observer.presented(pending));int calls=view.captures;assertEquals("read_reference_not_current",read(observer,j("mode","text","regionRef",ref)).getString("code"));assertEquals(calls,view.captures);
    }finally{view.destroy();}}
}
