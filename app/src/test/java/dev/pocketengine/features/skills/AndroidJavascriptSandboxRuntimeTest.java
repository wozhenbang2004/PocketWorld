package dev.pocketengine.features.skills;

import com.google.common.util.concurrent.ListenableFuture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Policy/adapter protocol fixtures, not an Android/WebView sandbox or device execution claim. */
public final class AndroidJavascriptSandboxRuntimeTest {
    private static final String PROBE=probe();
    private static String probe(){try{JSONObject value=new JSONObject();for(String name:List.of("fetch","XMLHttpRequest","WebSocket","WebTransport","document","navigator","require","process","Deno","Java","Packages","load","read","readFile","writeFile"))value.put(name,"undefined");return value.toString();}catch(Exception error){throw new AssertionError(error);}}
    private static final class Future implements ListenableFuture<String> {
        final CompletableFuture<String> value=new CompletableFuture<>();
        Future(String result){if(result!=null)value.complete(result);}
        @Override public void addListener(Runnable listener,Executor executor){value.whenComplete((result,error)->executor.execute(listener));}
        @Override public boolean cancel(boolean interrupt){return value.cancel(interrupt);}
        @Override public boolean isCancelled(){return value.isCancelled();}
        @Override public boolean isDone(){return value.isDone();}
        @Override public String get()throws InterruptedException,ExecutionException{return value.get();}
        @Override public String get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException{return value.get(timeout,unit);}
    }
    private static final class Isolate implements AndroidJavascriptSandboxRuntime.Isolate {
        final List<String> sources=new ArrayList<>();
        int closed;
        boolean failClose;
        String probe=PROBE;
        Future initialize=new Future("ready");
        @Override public ListenableFuture<String> evaluate(String source){sources.add(source);return source.startsWith("JSON.stringify({")?new Future(probe):source.startsWith("(()=>{")?initialize:new Future("actual fixture output");}
        @Override public void close(){closed++;if(failClose)throw new IllegalStateException("fixture close acknowledgement lost");}
    }
    private static final class Engine implements AndroidJavascriptSandboxRuntime.Engine {
        final List<Isolate> isolates=new ArrayList<>();
        Set<String> features=Set.of("JS_FEATURE_ISOLATE_TERMINATION","JS_FEATURE_ISOLATE_MAX_HEAP_SIZE","JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT","JS_FEATURE_PROMISE_RETURN");
        int closed,lastLimit;
        long lastHeap;
        String probe=PROBE;
        @Override public boolean supports(String feature){return features.contains(feature);}
        @Override public Isolate isolate(long heap,int maxReturnBytes){lastHeap=heap;lastLimit=maxReturnBytes;Isolate isolate=new Isolate();isolate.probe=probe;isolates.add(isolate);return isolate;}
        @Override public void close(){closed++;}
    }
    private static final class Provider implements AndroidJavascriptSandboxRuntime.Provider {
        String generation="provider-generation-1";
        final Engine engine=new Engine();
        Runnable onFingerprint,onConnect;
        @Override public String generation(){return generation;}
        @Override public AndroidJavascriptSandboxRuntime.Identity fingerprint()throws Exception{String before=generation;if(onFingerprint!=null)onFingerprint.run();return new AndroidJavascriptSandboxRuntime.Identity(before,"fixture-webview-1","a".repeat(64));}
        @Override public Engine connect(){if(onConnect!=null)onConnect.run();return engine;}
    }
    private static final class Fixture {
        final Provider provider=new Provider();
        final AndroidJavascriptSandboxRuntime runtime=new AndroidJavascriptSandboxRuntime(provider);
        Fixture()throws Exception{runtime.connect();}
        Isolate current(){return provider.engine.isolates.get(provider.engine.isolates.size()-1);}
        AndroidJavascriptSandboxRuntime.Session ready(int limit)throws Exception{AndroidJavascriptSandboxRuntime.Session session=runtime.createSession(limit);session.initialize(List.of("a b","');fetch('not-code')"),Map.of("scripts/check.js","'exact';\r\n".getBytes(StandardCharsets.UTF_8))).get();return session;}
    }
    @Test public void productionRuntimeIdentityNeverPretendsToBeAPosixProcess()throws Exception {
        Fixture fixture=new Fixture();JSONObject identity=fixture.runtime.describe().review();
        assertEquals("JAVASCRIPT_EVALUATION",identity.getString("invocationKind"));assertEquals("none",identity.getString("filesystem"));assertEquals("deny",identity.getString("network"));assertFalse(identity.getBoolean("androidBridge"));assertFalse(identity.getBoolean("nodeCompatible"));assertFalse(identity.has("executable"));assertFalse(identity.has("cwd"));assertEquals(4,identity.getJSONArray("requiredFeatures").length());
        assertEquals(1,fixture.provider.engine.isolates.size());assertEquals(1,fixture.current().closed);
    }
    @Test public void everyRequiredFeatureIsCheckedBeforeAnyOwnerScript()throws Exception {
        for(String absent:List.of("JS_FEATURE_ISOLATE_TERMINATION","JS_FEATURE_ISOLATE_MAX_HEAP_SIZE","JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT","JS_FEATURE_PROMISE_RETURN")){
            Provider provider=new Provider();java.util.HashSet<String> features=new java.util.HashSet<>(provider.engine.features);features.remove(absent);provider.engine.features=features;
            AndroidJavascriptSandboxRuntime runtime=new AndroidJavascriptSandboxRuntime(provider);
            assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,runtime::connect);assertEquals(1,provider.engine.closed);assertEquals(0,provider.engine.isolates.size());assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,runtime::describe);
        }
    }
    @Test public void unexpectedHostGlobalsFailClosedAndReleaseTheProbe()throws Exception {
        Provider provider=new Provider();provider.engine.probe=new JSONObject(PROBE).put("fetch","function").toString();AndroidJavascriptSandboxRuntime runtime=new AndroidJavascriptSandboxRuntime(provider);
        assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,runtime::connect);assertEquals(1,provider.engine.closed);assertEquals(1,provider.engine.isolates.get(0).closed);
    }
    @Test public void changingTheInstalledRuntimeDuringWarmupNeverReturnsReady()throws Exception {
        Provider provider=new Provider();provider.onConnect=()->provider.generation="replacement";AndroidJavascriptSandboxRuntime runtime=new AndroidJavascriptSandboxRuntime(provider);
        assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,runtime::connect);assertEquals(1,provider.engine.closed);
        Provider hashing=new Provider();hashing.onFingerprint=()->hashing.generation="replacement";AndroidJavascriptSandboxRuntime second=new AndroidJavascriptSandboxRuntime(hashing);
        assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,second::connect);assertEquals(0,hashing.engine.isolates.size());
    }
    @Test public void changingTheInstalledRuntimeAfterReviewBlocksEvaluation()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.ready(128);int before=fixture.current().sources.size();fixture.provider.generation="replacement";
        assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,fixture.runtime::describe);assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,()->session.evaluateExact("'never sent'".getBytes(StandardCharsets.UTF_8)));assertEquals(before,fixture.current().sources.size());session.close();
    }
    @Test public void exactSourceIsSeparateFromFrozenDataAndEvaluatedOnce()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.ready(128);Isolate isolate=fixture.current();String source="'真实字节😀';\r\n";
        assertEquals("actual fixture output",session.evaluateExact(source.getBytes(StandardCharsets.UTF_8)).get());assertEquals(source,isolate.sources.get(1));assertTrue(isolate.sources.get(0).contains("Object.freeze"));assertFalse(isolate.sources.get(0).contains("addJavascriptInterface"));
        assertThrows(SecurityException.class,()->session.evaluateExact(source.getBytes(StandardCharsets.UTF_8)));assertEquals(2,isolate.sources.size());assertEquals(128,fixture.provider.engine.lastLimit);assertEquals(32L*1024*1024,fixture.provider.engine.lastHeap);session.close();
    }
    @Test public void anIsolateCannotBeReinitializedOrUsedBeforeInitializationCompletes()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.runtime.createSession(1024);fixture.current().initialize=new Future(null);
        session.initialize(List.of(),Map.of());assertThrows(IllegalStateException.class,()->session.evaluateExact("'no'".getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalStateException.class,()->session.initialize(List.of(),Map.of()));fixture.current().initialize.value.completeExceptionally(new IllegalStateException("init failed"));
        assertThrows(ExecutionException.class,()->session.evaluateExact("'no'".getBytes(StandardCharsets.UTF_8)));assertEquals(1,fixture.current().sources.size());session.close();
    }
    @Test public void stoppedIsolateNeverLaunchesAgainAndAnotherSessionGetsNoOldData()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.ready(512);Isolate old=fixture.current();session.close();session.close();assertEquals(1,old.closed);assertThrows(IllegalStateException.class,()->session.evaluateExact("'no'".getBytes(StandardCharsets.UTF_8)));
        AndroidJavascriptSandboxRuntime.Session next=fixture.runtime.createSession(512);assertNotSame(old,fixture.current());assertEquals(0,fixture.current().sources.size());next.close();fixture.runtime.close();assertEquals(1,fixture.provider.engine.closed);
    }
    @Test public void twoSkillSessionsCannotShareLiveSiblingIsolates()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.runtime.createSession(512);assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,()->fixture.runtime.createSession(512));session.close();assertNotNull(fixture.runtime.createSession(512));fixture.runtime.close();assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,fixture.runtime::describe);
    }
    @Test public void unacknowledgedTerminationRetainsBusyFenceAndCanRetryOnlyClose()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.ready(512);Isolate isolate=fixture.current();isolate.failClose=true;
        assertThrows(IllegalStateException.class,session::close);assertThrows(AndroidJavascriptSandboxRuntime.Unavailable.class,()->fixture.runtime.createSession(512));assertThrows(IllegalStateException.class,()->session.evaluateExact("'never'".getBytes(StandardCharsets.UTF_8)));
        isolate.failClose=false;session.close();assertEquals(2,isolate.closed);assertNotNull(fixture.runtime.createSession(512));fixture.runtime.close();assertEquals(1,fixture.provider.engine.closed);
    }
    @Test public void packageAndArgumentsAreEncodedAsDataWithoutMutableReferences()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.runtime.createSession(4096);List<String> args=new ArrayList<>(List.of("');globalThis.fetch='pwn';('"));byte[] bytes="line1\r\n真实😀".getBytes(StandardCharsets.UTF_8);Map<String,byte[]> files=new HashMap<>();files.put("references/input.txt",bytes);session.initialize(args,files).get();String sent=fixture.current().sources.get(0);args.set(0,"mutated");bytes[0]=0;files.clear();assertEquals(sent,fixture.current().sources.get(0));
        int start=sent.indexOf("JSON.parse(")+11,end=sent.indexOf(");const r=");String argumentJson=new JSONArray("["+sent.substring(start,end)+"]").getString(0);assertEquals("');globalThis.fetch='pwn';('",new JSONArray(argumentJson).getString(0));session.close();
    }
    @Test public void malformedSourceAndOverBudgetInputsNeverReachTheEvaluator()throws Exception {
        Fixture fixture=new Fixture();AndroidJavascriptSandboxRuntime.Session session=fixture.ready(128);int before=fixture.current().sources.size();assertThrows(java.nio.charset.CharacterCodingException.class,()->session.evaluateExact(new byte[]{(byte)0xc3,0x28}));assertEquals(before,fixture.current().sources.size());session.close();
        assertThrows(IllegalArgumentException.class,()->AndroidJavascriptSandboxRuntime.bootstrap(List.of("x\n"),Map.of()));assertThrows(java.nio.charset.CharacterCodingException.class,()->AndroidJavascriptSandboxRuntime.bootstrap(List.of("\ud800"),Map.of()));
        assertThrows(IllegalArgumentException.class,()->AndroidJavascriptSandboxRuntime.bootstrap(List.of(),Map.of("../secret",new byte[0])));assertThrows(IllegalArgumentException.class,()->AndroidJavascriptSandboxRuntime.bootstrap(List.of(),Map.of("scripts/huge.js",new byte[1024*1024+1])));
        assertThrows(IllegalArgumentException.class,()->fixture.runtime.createSession(65537));assertThrows(IllegalArgumentException.class,()->fixture.runtime.createSession(0));
    }
}
