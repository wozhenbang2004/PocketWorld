package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;

public final class NativeDiagnosticCollectorTest {
    static JSONObject facts(NativeDiagnosticCollector.Domain domain)throws Exception {
        JSONObject value=new JSONObject();switch(domain){
            case PLATFORM:return value.put("deviceSdk",35).put("targetSdk",35).put("appVersionCode",3L).put("arm64Advertised",true);
            case RUNTIME:return value.put("androidProfilePresent",true).put("activeGenerationLoaded",false).put("supervisorLoaded",true).put("ptyAvailable",true).put("duplexAvailable",true).put("integrityRechecked",false).put("runtimeIdentityFingerprintSha256","a".repeat(64)).put("catalogFingerprintSha256",JSONObject.NULL);
            case PHONE:for(String key:List.of("mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl"))value.put(key,"unknown");return value.put("backgroundSampled",false);
            case STORAGE:return value.put("homeLogicalBytes",12L).put("temporaryLogicalBytes",0L).put("downloadLogicalBytes",JSONObject.NULL).put("originalLogLogicalBytes",34L).put("incompleteScopes",1L).put("generationScopeCount",2L).put("linkScopeCount",3L).put("logicalCountsNotAdditive",true).put("fileContentsRead",false);
        }throw new AssertionError();
    }
    static Map<NativeDiagnosticCollector.Domain,NativeDiagnosticCollector.Probe> sources(){var values=new EnumMap<NativeDiagnosticCollector.Domain,NativeDiagnosticCollector.Probe>(NativeDiagnosticCollector.Domain.class);for(var domain:NativeDiagnosticCollector.Domain.values())values.put(domain,c->facts(domain));return values;}
    static NativeDiagnosticCollector.Preview preview()throws Exception {try(var c=new NativeDiagnosticCollector(sources(),1000)){return c.collect(new RunControl());}}
    @Test public void completeMetadataIsFrozenAndNeverClaimsTaskOrDeviceSuccess()throws Exception {
        var preview=preview();JSONObject body=new JSONObject(preview.textForOwner());assertEquals("metadata_collected",body.getString("collectionStatus"));assertEquals(4,body.getJSONObject("sources").length());for(String key:List.of("commandsExecuted","trialExecuted","tasksChanged","automaticUpload","userContentIncluded","rawConfigurationIncluded","sessionIdentifiersIncluded","deviceAcceptanceProved","resourceReleaseProved"))assertFalse(body.getBoolean(key));
        ByteArrayOutputStream saved=new ByteArrayOutputStream();JSONObject result=preview.writeOnce(saved,new RunControl());assertEquals(preview.textForOwner(),saved.toString(StandardCharsets.UTF_8.name()));assertEquals(preview.sha256(),dev.pocketengine.features.local.project.ProjectSources.sha256(saved.toByteArray()));assertEquals(preview.size(),result.getInt("bytes"));assertFalse(result.getBoolean("destinationPersistenceVerified"));assertThrows(SecurityException.class,()->preview.writeOnce(new ByteArrayOutputStream(),new RunControl()));
    }
    @Test(timeout=5000) public void aBlockedSourceTimesOutWhileOthersCompleteAndCannotSpawnReplacementReaders()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();var sources=sources();
        sources.put(NativeDiagnosticCollector.Domain.RUNTIME,c->{reads.incrementAndGet();entered.countDown();try{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){/* Explicit own uncooperative read fixture. */}return facts(NativeDiagnosticCollector.Domain.RUNTIME);}finally{returned.countDown();}});
        try(var collector=new NativeDiagnosticCollector(sources,200)){
            JSONObject first=new JSONObject(collector.collect(new RunControl()).textForOwner());assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals("timed_out",first.getJSONObject("sources").getJSONObject("runtime").getString("status"));assertEquals("collected",first.getJSONObject("sources").getJSONObject("platform").getString("status"));assertEquals("collected",first.getJSONObject("sources").getJSONObject("phone").getString("status"));assertEquals("collected",first.getJSONObject("sources").getJSONObject("storage").getString("status"));assertFalse(first.getJSONObject("sources").getJSONObject("runtime").getBoolean("sourceReadReturned"));
            JSONObject next=new JSONObject(collector.collect(new RunControl()).textForOwner());assertEquals("busy_previous_read_unconfirmed",next.getJSONObject("sources").getJSONObject("runtime").getString("status"));assertEquals(1,reads.get());assertEquals("partial_metadata",next.getString("collectionStatus"));assertFalse(next.getJSONObject("sources").getJSONObject("runtime").getBoolean("workerExitInferredFromFuture"));
        }finally{release.countDown();assertTrue(returned.await(1,TimeUnit.SECONDS));}
    }
    @Test public void extraSensitiveFieldsAndExceptionsAreNeverExportedAsBlankSuccess()throws Exception {
        String secret="PRIVATE_TOKEN_no_export_正文";var sources=sources();sources.put(NativeDiagnosticCollector.Domain.RUNTIME,c->facts(NativeDiagnosticCollector.Domain.RUNTIME).put("apiKey",secret).put("prompt",secret).put("sessionId",secret));sources.put(NativeDiagnosticCollector.Domain.PHONE,c->{throw new IOException(secret);});
        try(var collector=new NativeDiagnosticCollector(sources,1000)){var p=collector.collect(new RunControl());String text=p.textForOwner();assertFalse(text.contains(secret));assertFalse(text.contains("apiKey"));JSONObject body=new JSONObject(text);assertEquals("partial_metadata",body.getString("collectionStatus"));for(String key:List.of("runtime","phone")){JSONObject row=body.getJSONObject("sources").getJSONObject(key);assertEquals("read_failed",row.getString("status"));assertTrue(row.isNull("facts"));}}
    }
    @Test public void wrongScalarTypesAreRejectedRatherThanStringCoercedOrCopied()throws Exception {
        for(Object bad:List.of("SECRET_VALUE",-1,1.25,new JSONObject().put("secret","hidden"))){var sources=sources();sources.put(NativeDiagnosticCollector.Domain.PLATFORM,c->facts(NativeDiagnosticCollector.Domain.PLATFORM).put("deviceSdk",bad));try(var collector=new NativeDiagnosticCollector(sources,1000)){var body=new JSONObject(collector.collect(new RunControl()).textForOwner());var row=body.getJSONObject("sources").getJSONObject("platform");assertEquals("read_failed",row.getString("status"));assertTrue(row.isNull("facts"));assertFalse(body.toString().contains("SECRET_VALUE"));assertFalse(body.toString().contains("hidden"));}}
    }
    @Test public void cancellationBeforeCollectionNeverEntersAnySourceAndCloseIsNotAReplayPermission()throws Exception {
        AtomicInteger entered=new AtomicInteger();var sources=sources();for(var domain:NativeDiagnosticCollector.Domain.values())sources.put(domain,c->{entered.incrementAndGet();return facts(domain);});var control=new RunControl();control.cancel();var collector=new NativeDiagnosticCollector(sources,1000);assertThrows(InterruptedException.class,()->collector.collect(control));assertEquals(0,entered.get());collector.close();assertThrows(IllegalStateException.class,()->collector.collect(new RunControl()));assertEquals(0,entered.get());
    }
    @Test public void partialWriteOrCancellationNeverReturnsSuccessOrAutomaticallyReexports()throws Exception {
        var preview=preview();AtomicInteger writes=new AtomicInteger();OutputStream broken=new OutputStream(){@Override public void write(int value)throws IOException{writes.incrementAndGet();throw new IOException("own provider failure");}};assertThrows(IOException.class,()->preview.writeOnce(broken,new RunControl()));assertEquals(1,writes.get());assertTrue(preview.exportEntered());assertThrows(SecurityException.class,()->preview.writeOnce(new ByteArrayOutputStream(),new RunControl()));
        var another=preview();RunControl cancelled=new RunControl();cancelled.cancel();ByteArrayOutputStream untouched=new ByteArrayOutputStream();assertThrows(InterruptedException.class,()->another.writeOnce(untouched,cancelled));assertEquals(0,untouched.size());assertFalse(another.exportEntered());
    }
}
