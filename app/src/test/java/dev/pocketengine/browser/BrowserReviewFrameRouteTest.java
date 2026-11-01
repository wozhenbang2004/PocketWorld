package dev.pocketengine.browser;
import static org.junit.Assert.*;
import android.app.Application;
import android.webkit.WebView;
import androidx.webkit.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Actual production frame dispatcher; proxy is an explicit platform interface fixture, not a renderer. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserReviewFrameRouteTest {
 static JSONObject j(Object...p)throws Exception{return BrowserReadScopeTest.j(p);}
 static final class Proxy extends JavaScriptReplyProxy {int calls;String code;WebViewOutcomeReceiver<String,JavaScriptExecutionException> callback;public void postMessage(String s){}public void postMessage(byte[] b){}public void executeJavaScript(String s,WebViewOutcomeReceiver<String,JavaScriptExecutionException> c){calls++;code=s;callback=c;}}
 @SuppressWarnings("unchecked") static BrowserFrameReadRegistry<JavaScriptReplyProxy> frames(BrowserFrameObserver o)throws Exception{Field f=BrowserFrameObserver.class.getDeclaredField("frames");f.setAccessible(true);return (BrowserFrameReadRegistry<JavaScriptReplyProxy>)f.get(o);}
 @Test public void exactPresentedChildRoutesOnceAndReplacementCannotRetargetTheCallback()throws Exception {
  WebView v=new WebView(RuntimeEnvironment.getApplication());try(BrowserFrameObserver o=new BrowserFrameObserver(v)){
   var registry=frames(o);String doc=o.documentIdentityForNative();registry.reset(doc,"registration","https://top.example",Set.of("https://top.example","https://child.example"));Proxy main=new Proxy(),child=new Proxy();assertTrue(registry.register(main,"https://top.example",true,"registration","a".repeat(32)));assertTrue(registry.register(child,"https://child.example",false,"registration","b".repeat(32)));registry.presented(registry.catalogue(null),registry.candidates(),android.os.SystemClock.elapsedRealtime());var selected=registry.frames().stream().filter(f->!f.main).findFirst().orElseThrow();JSONObject pin=o.framePinForNative(selected.id);AtomicInteger done=new AtomicInteger(),failed=new AtomicInteger();
   o.evaluateAction(pin,"JSON.stringify({accepted:true})",()->{},x->done.incrementAndGet(),e->failed.incrementAndGet());assertEquals(0,main.calls);assertEquals(1,child.calls);assertTrue(child.code.contains("b".repeat(32)));child.callback.onResult(JSONObject.quote(j("accepted",true).toString()));assertEquals(1,done.get());
   o.evaluateAction(pin,"JSON.stringify({accepted:true})",()->{},x->done.incrementAndGet(),e->failed.incrementAndGet());registry.register(child,"https://child.example",false,"registration","c".repeat(32));child.callback.onResult(JSONObject.quote(j("accepted",true).toString()));assertEquals(1,done.get());assertEquals(1,failed.get());assertThrows(Exception.class,()->o.evaluateAction(pin,"JSON.stringify({accepted:true})",()->{},x->{},e->{}));assertEquals(2,child.calls);
  }finally{v.destroy();}
 }
 @Test public void currentAdmissionFailureMakesZeroPlatformCalls()throws Exception {WebView v=new WebView(RuntimeEnvironment.getApplication());try(BrowserFrameObserver o=new BrowserFrameObserver(v)){AtomicInteger calls=new AtomicInteger();assertThrows(SecurityException.class,()->o.evaluateAction(new JSONObject(),"arbitrary",()->{throw new SecurityException("paused");},x->calls.incrementAndGet(),e->calls.incrementAndGet()));assertEquals(0,calls.get());}finally{v.destroy();}}
 @Test @SuppressWarnings("unchecked") public void targetConditionUsesIssuedChildRegionAndNavigationRejectsLateProbe()throws Exception {
  WebView v=new WebView(RuntimeEnvironment.getApplication());try(BrowserFrameObserver o=new BrowserFrameObserver(v)){
   var registry=frames(o);registry.reset(o.documentIdentityForNative(),"registration","https://top.example",Set.of("https://top.example","https://child.example"));Proxy main=new Proxy(),child=new Proxy();registry.register(main,"https://top.example",true,"registration","a".repeat(32));registry.register(child,"https://child.example",false,"registration","b".repeat(32));registry.presented(registry.catalogue(null),registry.candidates(),android.os.SystemClock.elapsedRealtime());var selected=registry.frames().stream().filter(f->!f.main).findFirst().orElseThrow();String ref=selected.document+":r0";
   // Retained issued-read fixture represents the native projection-delivery boundary, not a new grant.
   Field field=BrowserFrameObserver.class.getDeclaredField("issued");field.setAccessible(true);Class<?> type=Class.forName("dev.pocketengine.browser.BrowserFrameObserver$IssuedRead");Constructor<?> c=type.getDeclaredConstructors()[0];c.setAccessible(true);((Map<String,Object>)field.get(o)).put(ref,c.newInstance("epoch|"+selected.document,android.os.SystemClock.elapsedRealtime(),selected.document,"",""));JSONObject target=j("regionRef",ref,"frameRef",selected.id,"state","present"),raw=j("targetProbe",true,"status","observed","code","target_observed","matched",true,"actual",j("present",true,"instance","1"),"coverage",j("nodesVisited",1,"textCharsVisited",0,"queuedPeak",1,"layoutChecks",1,"partial",false,"reasons",new JSONArray()));
   CompletableFuture<JSONObject> first=new CompletableFuture<>();o.condition(target,"epoch",()->{},first);assertEquals(0,main.calls);assertEquals(1,child.calls);child.callback.onResult(JSONObject.quote(raw.toString()));assertTrue(first.get().getBoolean("matched"));assertEquals(selected.id,first.get().getString("frameId"));
   CompletableFuture<JSONObject> late=new CompletableFuture<>();o.condition(target,"epoch",()->{},late);registry.register(child,"https://child.example",false,"registration","c".repeat(32));child.callback.onResult(JSONObject.quote(raw.toString()));assertTrue(late.isCompletedExceptionally());assertEquals(0,main.calls);
   AtomicInteger checks=new AtomicInteger();assertThrows(SecurityException.class,()->o.condition(target,"epoch",()->{checks.incrementAndGet();throw new SecurityException("cancelled original grant");},new CompletableFuture<>()));assertEquals(2,child.calls);assertEquals(1,checks.get());
  }finally{v.destroy();}
 }
 @Test public void liveExpressionUsesOnlyExactIsolatedFrameAndRejectsLateReplacement()throws Exception {
  WebView v=new WebView(RuntimeEnvironment.getApplication());v.loadUrl("https://top.example/");try(BrowserFrameObserver o=new BrowserFrameObserver(v)){
   var registry=frames(o);registry.reset(o.documentIdentityForNative(),"registration","https://top.example",Set.of("https://top.example","https://child.example"));Proxy main=new Proxy(),child=new Proxy();registry.register(main,"https://top.example",true,"registration","a".repeat(32));registry.register(child,"https://child.example",false,"registration","b".repeat(32));registry.presented(registry.catalogue(null),registry.candidates(),android.os.SystemClock.elapsedRealtime());var selected=registry.frames().stream().filter(f->!f.main).findFirst().orElseThrow();JSONObject request=j("frameRef",selected.id,"expression",j("op","literal","value",42).toString());CompletableFuture<JSONObject> actual=new CompletableFuture<>();o.evaluateLive(request,false,()->{},actual);assertEquals(0,main.calls);assertEquals(1,child.calls);assertTrue(child.code.contains("b".repeat(32)));child.callback.onResult(JSONObject.quote(j("status","observed","url","https://child.example/frame","value",42).toString()));assertEquals(42,actual.get().getInt("value"));assertEquals("isolated_world",actual.get().getString("executionWorld"));assertEquals(selected.document,actual.get().getString("documentId"));
   CompletableFuture<JSONObject> late=new CompletableFuture<>();o.evaluateLive(request,false,()->{},late);registry.register(child,"https://child.example",false,"registration","c".repeat(32));child.callback.onResult(JSONObject.quote(j("status","observed","url","https://child.example/new","value",43).toString()));assertTrue(late.isCompletedExceptionally());assertEquals(0,main.calls);
  }finally{v.destroy();}
 }
 @Test public void noIsolatedRegistrationNeverFallsBackToPageWorld()throws Exception {WebView v=new WebView(RuntimeEnvironment.getApplication());try(BrowserFrameObserver o=new BrowserFrameObserver(v)){CompletableFuture<JSONObject> result=new CompletableFuture<>();o.evaluateLive(j("expression",j("op","literal","value",1).toString()),false,()->{},result);assertEquals("isolated_world_not_registered",result.get().getString("code"));assertNull(Shadows.shadowOf(v).getLastEvaluatedJavascript());}finally{v.destroy();}}
}
