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
        volatile String environmentBinding=ENV,wrongWorker="",executableIdentity="d".repeat(64),probeIdentity="",piVersion="1.4.0",missingPiField="";
        volatile int identityMode;
        volatile boolean unavailable,missingProtocol,lateChange,blockGet,missingPiWorker,missingCliFlags;
        volatile Object piNoTools=Boolean.TRUE;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Fixture()throws Exception {
            var app=RuntimeEnvironment.getApplication();app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");
            http.createContext("/",exchange->{try{
                assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));JSONObject value;
                if(exchange.getRequestURI().getPath().equals("/v1/environments")){
                    gets.incrementAndGet();entered.countDown();if(blockGet)release.await(5,TimeUnit.SECONDS);
                    JSONArray workers=new JSONArray().put(workerMetadata("codex_app_server")).put(workerMetadata("claude"));if(!missingPiWorker)workers.put(workerMetadata("pi"));
                    value=new JSONObject().put("environments",new JSONArray().put(new JSONObject().put("id","workspace").put("bindingHash",environmentBinding).put("workers",workers)));
                }else if(exchange.getRequestURI().getPath().equals("/v1/workers/probe")){
                    probes.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals(ENV,request.getString("environmentBinding"));
                    assertEquals(3,request.length());for(String key:new String[]{"operationId","prompt","threadId","turnId","tools","model"})assertFalse(request.has(key));
                    String worker=request.getString("worker");value=new JSONObject().put("environmentId","workspace").put("worker",wrongWorker.isEmpty()?worker:wrongWorker).put("available",!unavailable).put("protocolVerified",(worker.equals("codex_app_server")||worker.equals("pi"))&&!missingProtocol).put("configReadVerified",true).put("accountConfigured",true).put("authMode","apiKey").put("cliFlagsVerified",!missingCliFlags).put("cliVersion",worker.equals("pi")?piVersion:"2.1.259").put("secret",TOKEN).put("message","Bearer "+TOKEN);
                    if(worker.equals("pi")){value.put("noTools",piNoTools);if(!missingPiField.isEmpty())value.remove(missingPiField);}
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
        void addIdentity(JSONObject value,String digest)throws Exception {if(identityMode==1)return;value.put("executableIdentity",digest).put("executableIdentityKind",identityMode==4?"script_launcher_stat_v1":"native_file_stat_v1").put("executableIdentityVerified",identityMode==2?"true":true).put("versionContentVerified",identityMode==3).put("executionChainVerified",false);}
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
    @Test public void piPinnedNoToolsProbeAndFastGuardKeepAccountConfigAndRuntimeUnknown()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject ref=f.ref("pi"),facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertTrue(facts.getBoolean("supported"));
            for(String key:new String[]{"kind","profileId","profileBinding","environmentId","environmentBinding","worker"})assertEquals(ref.get(key),facts.get(key));
            JSONObject evidence=facts.getJSONObject("sourceEvidence");assertEquals("source_ready",evidence.getString("sourceState"));assertEquals("1.4.0",evidence.getString("workerVersion"));assertTrue(evidence.getBoolean("protocolVerified"));assertTrue(evidence.getBoolean("cliFlagsVerified"));assertTrue(evidence.getBoolean("noTools"));
            // Even a peer claiming these true cannot turn Pi RPC metadata into account/config proof.
            assertTrue(evidence.isNull("accountConfigured"));assertTrue(evidence.isNull("configReadVerified"));assertEquals("unknown",evidence.getString("accountMode"));
            for(String key:new String[]{"connectionVerified","configurationApplied","runtimeVerified","versionContentVerified","executionChainVerified"})assertFalse(evidence.getBoolean(key));
            assertTrue(evidence.getBoolean("executableIdentityVerified"));assertEquals("native_file_stat_v1",evidence.getString("executableIdentityKind"));assertFalse(facts.has("_entrypointObservation"));assertFalse(facts.toString().contains(TOKEN));
            // Resolver freezes the tuple/evidence, without retaining the original support boolean.
            JSONObject frozen=new JSONObject(facts.toString());frozen.remove("supported");f.remote.checkWorkProfileTargetCurrent(ref,frozen,new RunControl());
            assertEquals(2,f.gets.get());assertEquals(1,f.probes.get());assertEquals(0,f.jobs.get());assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
        }
    }
    @Test public void piMissingWorkerOrRequiredProbeFactsCannotBecomeReady()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject ref=f.ref("pi");f.missingPiWorker=true;assertThrows(SecurityException.class,()->f.remote.workProfileTargetFacts(ref,new RunControl()));assertEquals(0,f.probes.get());f.missingPiWorker=false;
            for(String field:new String[]{"available","cliFlagsVerified","protocolVerified","noTools","cliVersion"}){
                f.missingPiField=field;JSONObject facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertFalse(field,facts.getBoolean("supported"));assertEquals("unavailable",facts.getJSONObject("sourceEvidence").getString("sourceState"));
            }
            assertEquals(0,f.jobs.get());assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
        }
    }
    @Test public void piNoToolsMustBeTrueAndItsVersionFlagsAndProtocolStayPinned()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject ref=f.ref("pi");
            for(Object noTools:new Object[]{Boolean.FALSE,"true",JSONObject.NULL}){f.piNoTools=noTools;JSONObject facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertFalse(facts.getBoolean("supported"));assertEquals("worker_no_tools_unverified",facts.getJSONObject("sourceEvidence").getString("reasonCode"));}
            f.piNoTools=Boolean.TRUE;
            for(String version:new String[]{"1.4.1","1.4","1.4.0-beta","2.1.259","pi 1.4.0"}){f.piVersion=version;JSONObject facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertFalse(version,facts.getBoolean("supported"));assertEquals("worker_version_unsupported",facts.getJSONObject("sourceEvidence").getString("reasonCode"));}
            f.piVersion="1.4.0";f.missingCliFlags=true;assertFalse(f.remote.workProfileTargetFacts(ref,new RunControl()).getBoolean("supported"));f.missingCliFlags=false;
            f.missingProtocol=true;JSONObject failed=f.remote.workProfileTargetFacts(ref,new RunControl());assertFalse(failed.getBoolean("supported"));assertFalse(failed.getJSONObject("sourceEvidence").getBoolean("protocolVerified"));assertTrue(failed.getJSONObject("sourceEvidence").isNull("accountConfigured"));assertTrue(failed.getJSONObject("sourceEvidence").isNull("configReadVerified"));f.missingProtocol=false;
            f.unavailable=true;assertFalse(f.remote.workProfileTargetFacts(ref,new RunControl()).getBoolean("supported"));assertEquals(0,f.jobs.get());assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
        }
    }
    @Test public void piMissingOverclaimedOrChangedEntrypointNeverBecomesVerified()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject ref=f.ref("pi");for(int mode:new int[]{1,2,3,4}){
                f.identityMode=mode;JSONObject facts=f.remote.workProfileTargetFacts(ref,new RunControl()),evidence=facts.getJSONObject("sourceEvidence");assertFalse(facts.getBoolean("supported"));assertFalse(evidence.getBoolean("executableIdentityVerified"));assertTrue(evidence.isNull("executableIdentity"));assertFalse(evidence.getBoolean("versionContentVerified"));assertFalse(evidence.getBoolean("executionChainVerified"));
            }
            f.identityMode=0;f.probeIdentity="e".repeat(64);JSONObject changed=f.remote.workProfileTargetFacts(ref,new RunControl());assertFalse(changed.getBoolean("supported"));assertEquals("worker_entrypoint_identity_unverified_or_changed",changed.getJSONObject("sourceEvidence").getString("reasonCode"));assertFalse(changed.getJSONObject("sourceEvidence").getBoolean("executableIdentityVerified"));assertEquals(0,f.jobs.get());
        }
    }
    @Test public void piFastGuardRejectsChangedIdentityAndWithdrawnCredentialWithoutReprobing()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject ref=f.ref("pi"),facts=f.remote.workProfileTargetFacts(ref,new RunControl());assertTrue(facts.getBoolean("supported"));
            f.executableIdentity="e".repeat(64);assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));assertEquals(2,f.gets.get());assertEquals(1,f.probes.get());f.executableIdentity="d".repeat(64);
            RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",0).edit().clear().commit();assertThrows(SecurityException.class,()->f.remote.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));assertEquals(2,f.gets.get());assertEquals(1,f.probes.get());assertEquals(0,f.jobs.get());assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
        }
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
