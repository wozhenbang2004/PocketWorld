package dev.pocketengine.browser;

import android.app.Application;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Real native issuance/callback gates with explicit JS responses. NOT long-DOM/WebView rendering proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserLongPageNativeTest {
    private static final String EPOCH="original-run-page-navigation";
    static final class View extends WebView {
        ValueCallback<String> pending;String code;int captures;
        View(){super(RuntimeEnvironment.getApplication());}
        @Override public String getUrl(){return "https://read.example/long-page";}
        @Override public String getTitle(){return "Native cursor fixture";}
        @Override public void evaluateJavascript(String script,ValueCallback<String> callback){captures++;code=script;pending=callback;}
        void deliver(String id)throws Exception {
            Matcher match=Pattern.compile("regionNamespace=\"([0-9a-f-]{36})\"").matcher(code);assertTrue(match.find());
            JSONObject response=raw("text").put("url",getUrl());JSONObject read=response.getJSONObject("read");read.put("cursor",id+":c0");read.getJSONObject("facts").put("regionRef",match.group(1)+":r1").put("partial",true).put("reasons",new JSONArray().put("scan_continuation_available").put("continuation_available"));
            ValueCallback<String> callback=pending;pending=null;callback.onReceiveValue(JSONObject.quote(response.toString()));
        }
    }
    static JSONObject bound(JSONObject value,String id)throws Exception {return value.put("observationId",id).put("taskId","00000000-0000-0000-0000-000000000001").put("runId","00000000-0000-0000-0000-000000000002").put("epoch",1).put("profileId","test").put("profileRevision",1).put("profileIsolation","default-webview-only").put("browserSessionId","00000000-0000-0000-0000-000000000003").put("browserSpaceId","00000000-0000-0000-0000-000000000004").put("tabId","00000000-0000-0000-0000-000000000005").put("tabGeneration",1).put("navigation",1).put("ok",true);}
    static CompletableFuture<JSONObject> start(BrowserFrameObserver observer,JSONObject request,String id,String epoch,BrowserFrameObserver.Check check)throws Exception {CompletableFuture<JSONObject> result=new CompletableFuture<>();observer.read(request,id,epoch,check,result);return result;}
    static String issue(BrowserFrameObserver observer,View view)throws Exception {
        String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> result=start(observer,j("mode","text"),id,EPOCH,()->{});view.deliver(id);JSONObject read=bound(result.get(1,TimeUnit.SECONDS),id);observer.delivered(observer.presented(read));return id+":c0";
    }
    @Test public void returnedButUndeliveredCursorIsNotAnIssuedContinuation()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
            String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> first=start(observer,j("mode","text"),id,EPOCH,()->{});view.deliver(id);assertEquals("observed",first.get().getString("status"));
            JSONObject denied=start(observer,j("mode","text","cursor",id+":c0"),UUID.randomUUID().toString(),EPOCH,()->{}).get();assertEquals("read_reference_not_current",denied.getString("code"));assertEquals(1,view.captures);
        }finally{view.destroy();}
    }
    @Test public void deliveredCursorKeepsItsNativeRunAndNavigationScope()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
            String cursor=issue(observer,view);int before=view.captures;
            JSONObject wrongRun=start(observer,j("mode","text","cursor",cursor),UUID.randomUUID().toString(),EPOCH+"-replacement",()->{}).get();assertEquals("read_reference_not_current",wrongRun.getString("code"));assertEquals(before,view.captures);
            observer.documentStarted();JSONObject wrongNavigation=start(observer,j("mode","text","cursor",cursor),UUID.randomUUID().toString(),EPOCH,()->{}).get();assertEquals("read_reference_not_current",wrongNavigation.getString("code"));assertEquals(before,view.captures);
        }finally{view.destroy();}
    }
    @Test public void cancelledContinuationNeverPublishesItsLateSuccessOrNextCursor()throws Exception {
        View view=new View();try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
            String cursor=issue(observer,view),id=UUID.randomUUID().toString();CompletableFuture<JSONObject> next=start(observer,j("mode","text","cursor",cursor),id,EPOCH,()->{});next.cancel(false);view.deliver(id);assertTrue(next.isCancelled());
            int before=view.captures;JSONObject denied=start(observer,j("mode","text","cursor",id+":c0"),UUID.randomUUID().toString(),EPOCH,()->{}).get();assertEquals("read_reference_not_current",denied.getString("code"));assertEquals(before,view.captures);
        }finally{view.destroy();}
    }
    @Test public void ownerCancellationAndDocumentReplacementRejectAnAlreadyRunningContinuation()throws Exception {
        for(boolean cancel:new boolean[]{true,false}){View view=new View();AtomicBoolean live=new AtomicBoolean(true);try(BrowserFrameObserver observer=new BrowserFrameObserver(view)){
            String cursor=issue(observer,view),id=UUID.randomUUID().toString();CompletableFuture<JSONObject> next=start(observer,j("mode","text","cursor",cursor),id,EPOCH,()->{if(!live.get())throw new SecurityException("original run cancelled");});
            if(cancel)live.set(false);else observer.documentStarted();view.deliver(id);assertThrows(ExecutionException.class,()->next.get(1,TimeUnit.SECONDS));
            assertThrows(Exception.class,()->observer.presented(bound(raw("text"),id)));
        }finally{view.destroy();}}
    }
}
