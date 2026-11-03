package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original RemoteModule and HTTP transport. A probe is metadata, never a job or turn. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class WorkProfileRemoteTargetFactsNativeTest {
    private static final String ENV="a".repeat(64),TOKEN="native-source-fixture-token-longer-than-32-chars";
    static final class Fixture implements AutoCloseable {
        final HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger gets=new AtomicInteger(),probes=new AtomicInteger(),jobs=new AtomicInteger();
        RemoteModule remote;
        volatile String environmentBinding=ENV,wrongWorker="",executableIdentity="d".repeat(64),probeIdentity="";
        volatile int identityMode;
        volatile boolean unavailable,missingProtocol,lateChange,blockGet;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Fixture()throws Exception {
            var app=RuntimeEnvironment.getApplication();app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");
            http.createContext("/",exchange->{try{
                assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));JSONObject value;
                if(exchange.getRequestURI().getPath().equals("/v1/environments")){
                    gets.incrementAndGet();entered.countDown();if(blockGet)release.await(5,TimeUnit.SECONDS);
                    value=new JSONObject().put("environments",new JSONArray().put(new JSONObject().put("id","workspace").put("bindingHash",environmentBinding).put("workers",new JSONArray().put(workerMetadata("codex_app_server")).put(workerMetadata("claude")))));
                }else if(exchange.getRequestURI().getPath().equals("/v1/workers/probe")){
                    probes.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals(ENV,request.getString("environmentBinding"));
                    assertFalse(request.has("operationId"));assertFalse(request.has("prompt"));assertFalse(request.has("threadId"));
                    String worker=request.getString("worker");value=new JSONObject().put("environmentId","workspace").put("worker",wrongWorker.isEmpty()?worker:wrongWorker).put("available",!unavailable).put("protocolVerified",worker.equals("codex_app_server")&&!missingProtocol).put("configReadVerified",true).put("accountConfigured",true).put("authMode","apiKey").put("cliFlagsVerified",true).put("cliVersion","2.1.259").put("secret",TOKEN).put("message","Bearer "+TOKEN);
                    addIdentity(value,probeIdentity.isEmpty()?executableIdentity:probeIdentity);
                    if(lateChange)remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","changed during metadata read").put("baseUrl",base()));
                }else{jobs.incrementAndGet();value=new JSONObject();}
                byte[] bytes=new JSONObject().put("ok",true).put("result",value).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception cancelled){/* cancellation deliberately closes the original socket */}finally{exchange.close();}});http.start();
            var context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
            remote=new RemoteModule(context,(id,url)->TOKEN,path->{});remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","native source fixture").put("baseUrl",base()));
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(base().getBytes(StandardCharsets.UTF_8));StringBuilder suffix=new StringBuilder();for(int i=0;i<16;i++)suffix.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]));
            // Same original credential-presence guard; no fake credential reader enters production.
            app.getSharedPreferences("feature_secrets",0).edit().putString("remote.token.computer."+suffix,"fixture-ciphertext-presence-only").commit();
        }
        JSONObject workerMetadata(String worker)throws Exception {JSONObject result=new JSONObject().put("id",worker).put("available",true);addIdentity(result,executableIdentity);return result;}
        void addIdentity(JSONObject value,String digest)throws Exception {if(identityMode==1)return;value.put("executableIdentity",digest).put("executableIdentityKind","native_file_stat_v1").put("executableIdentityVerified",identityMode==2?"true":true).put("versionContentVerified",identityMode==3).put("executionChainVerified",false);}
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort();}
        JSONObject ref(String worker)throws Exception{return new JSONObject().put("kind","remote_harness").put("profileId","computer").put("profileBinding",remote.profileBindingHash("computer")).put("environmentId","workspace").put("environmentBinding",ENV).put("worker",worker).put("optional",false);}
        public void close(){release.countDown();remote.close();http.stop(0);}
    }
    @Test public void originalBindingAndProbeProducePublicFactsAndFastGuardNeverProbesAgain()throws Exception {
        try(Fixture f=new Fixture()){JSONObject ref=f.ref("codex_app_server"),facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertTrue(facts.getBoolean("supported"));
            for(String key:new String[]{"kind","profileId","profileBinding","environmentId","environmentBinding","worker"})assertEquals(ref.get(key),facts.get(key));
            JSONObject evidence=facts.getJSONObject("sourceEvidence");assertTrue(evidence.getBoolean("protocolVerified"));assertTrue(evidence.getBoolean("accountConfigured"));assertTrue(evidence.getBoolean("configReadVerified"));assertEquals("2.1.259",evidence.getString("workerVersion"));assertFalse(evidence.getBoolean("runtimeVerified"));assertFalse(evidence.getBoolean("configurationApplied"));assertFalse(evidence.getBoolean("connectionVerified"));assertTrue(evidence.getBoolean("executableIdentityVerified"));assertFalse(evidence.getBoolean("versionContentVerified"));assertFalse(evidence.getBoolean("executionChainVerified"));assertEquals("d".repeat(64),evidence.getString("executableIdentity"));assertFalse(facts.has("_entrypointObservation"));assertFalse(facts.toString().contains(TOKEN));
            f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl());assertEquals(2,f.gets.get());assertEquals(1,f.probes.get());assertEquals(0,f.jobs.get());assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
        }
    }
    @Test public void claudeVersionHelpIsSupportedDataNotAnAccountOrStreamHandshake()throws Exception {
        try(Fixture f=new Fixture()){JSONObject facts=f.remote.workProfileTargetFacts(f.ref("claude"),new RunControl());assertTrue(facts.getBoolean("supported"));JSONObject evidence=facts.getJSONObject("sourceEvidence");assertTrue(evidence.getBoolean("protocolSupported"));assertTrue(evidence.isNull("protocolVerified"));assertTrue(evidence.isNull("accountConfigured"));assertTrue(evidence.isNull("configReadVerified"));assertEquals("unknown",evidence.getString("accountMode"));assertFalse(evidence.getBoolean("runtimeVerified"));assertEquals(0,f.jobs.get());}
    }
    @Test public void malformedForgedUnsupportedAndStaleNativeTargetsCannotDispatch()throws Exception {
        try(Fixture f=new Fixture()){for(String key:new String[]{"taskId","grant","token","path","operationId"})assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(f.ref("codex_app_server").put(key,"forged"),new RunControl()));
            assertFalse(f.remote.workProfileTargetFacts(f.ref("command"),new RunControl()).getBoolean("supported"));JSONObject stale=f.ref("codex_app_server");f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","new native generation").put("baseUrl",f.base()));assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(stale,new RunControl()));assertEquals(0,f.gets.get());assertEquals(0,f.probes.get());}
    }
    @Test public void changedEnvironmentWrongProbeAndLateProfileCannotBecomeAReadyBinding()throws Exception {
        try(Fixture f=new Fixture()){JSONObject ref=f.ref("codex_app_server");f.environmentBinding="b".repeat(64);assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(ref,new RunControl()));assertEquals(0,f.probes.get());
            f.environmentBinding=ENV;f.wrongWorker="claude";assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(ref,new RunControl()));f.wrongWorker="";f.lateChange=true;assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(ref,new RunControl()));assertEquals(0,f.jobs.get());}
    }
    @Test public void cancellationInterruptsOriginalMetadataIoBeforeAnyProbeOrNewJob()throws Exception {
        try(Fixture f=new Fixture()){f.blockGet=true;RunControl original=new RunControl();ExecutorService executor=Executors.newSingleThreadExecutor();try{
            JSONObject ref=f.ref("codex_app_server");Future<?> pending=executor.submit(()->{try{f.remote.workProfileTargetFacts(ref,original);throw new AssertionError("cancelled source admitted");}catch(InterruptedException expected){}catch(Exception failure){throw new RuntimeException(failure);}});
            assertTrue(f.entered.await(2,TimeUnit.SECONDS));original.cancel();pending.get(2,TimeUnit.SECONDS);assertEquals(1,f.gets.get());assertEquals(0,f.probes.get());assertEquals(0,f.jobs.get());
        }finally{f.release.countDown();executor.shutdownNow();}}
    }
    @Test public void failedProtocolObservationDoesNotPromoteUnknownAccountOrRuntimeState()throws Exception {
        try(Fixture f=new Fixture()){f.missingProtocol=true;JSONObject facts=f.remote.workProfileTargetFacts(f.ref("codex_app_server"),new RunControl());assertFalse(facts.getBoolean("supported"));JSONObject evidence=facts.getJSONObject("sourceEvidence");assertFalse(evidence.getBoolean("protocolVerified"));assertTrue(evidence.isNull("accountConfigured"));assertTrue(evidence.isNull("configReadVerified"));assertEquals("unknown",evidence.getString("accountMode"));assertFalse(evidence.getBoolean("runtimeVerified"));assertEquals(0,f.jobs.get());}
    }
    @Test public void legacyMalformedAndOverclaimedIdentityCannotBecomeVerifiedOnANewClient()throws Exception {
        try(Fixture f=new Fixture()){for(int mode:new int[]{1,2,3}){f.identityMode=mode;JSONObject facts=f.remote.workProfileTargetFacts(f.ref("codex_app_server"),new RunControl());assertFalse(facts.getBoolean("supported"));JSONObject evidence=facts.getJSONObject("sourceEvidence");assertTrue(evidence.getBoolean("protocolVerified"));assertFalse(evidence.getBoolean("executableIdentityVerified"));assertTrue(evidence.isNull("executableIdentity"));assertFalse(evidence.getBoolean("versionContentVerified"));assertFalse(evidence.getBoolean("runtimeVerified"));}assertEquals(0,f.jobs.get());}
    }
    @Test public void nativeEntrypointUpgradeAndLegacyDowngradeInvalidateFastGuardWithoutAnotherCliProbe()throws Exception {
        try(Fixture f=new Fixture()){JSONObject ref=f.ref("codex_app_server"),facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertTrue(facts.getBoolean("supported"));
            f.executableIdentity="e".repeat(64);assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));assertEquals(1,f.probes.get());
            f.executableIdentity="d".repeat(64);f.identityMode=1;assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));assertEquals(1,f.probes.get());assertEquals(0,f.jobs.get());}
    }
    @Test public void changedEntrypointDuringProbeDoesNotBindNewFactsToOldMetadata()throws Exception {
        try(Fixture f=new Fixture()){f.probeIdentity="e".repeat(64);JSONObject facts=f.remote.workProfileTargetFacts(f.ref("codex_app_server"),new RunControl());assertFalse(facts.getBoolean("supported"));assertFalse(facts.getJSONObject("sourceEvidence").getBoolean("executableIdentityVerified"));assertEquals(0,f.jobs.get());}
    }
    @Test public void fastGuardRejectsChangedFrozenTupleClosedModuleAndCancelledObservation()throws Exception {
        try(Fixture f=new Fixture()){JSONObject ref=f.ref("codex_app_server"),facts=f.remote.workProfileTargetFacts(ref,new RunControl());int gets=f.gets.get();
            assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,new JSONObject(facts.toString()).put("environmentId","other"),new RunControl()));assertEquals(gets,f.gets.get());
            f.environmentBinding="c".repeat(64);assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));assertEquals(gets+1,f.gets.get());assertEquals(1,f.probes.get());f.environmentBinding=ENV;
            RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",0).edit().clear().commit();assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));assertEquals(gets+1,f.gets.get());
            RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->f.remote.workProfileTargetFacts(ref,cancelled));f.remote.close();assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(ref,new RunControl()));assertEquals(gets+1,f.gets.get());assertEquals(1,f.probes.get());assertEquals(0,f.jobs.get());}
    }
}
