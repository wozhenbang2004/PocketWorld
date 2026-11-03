package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.phone.diagnostics.AndroidPhoneDiagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Production LocalModule/sources and native consent window, using simulated
 * Android35 facts and a real private host file. Not a physical SAF/OEM test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class NativeDiagnosticSourcesTest {
    private static Context android(){return org.robolectric.RuntimeEnvironment.getApplication();}
    private static void clean(Path root)throws Exception{try(var files=Files.walk(root)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    @Test public void originalLocalModuleMetadataFlowsThroughReviewedFrozenBytesWithoutUserContentOrExecution()throws Exception {
        Path root=Files.createTempDirectory("owner-diagnostic-sources-");AtomicInteger events=new AtomicInteger();
        var original=ManagedProjectRuntime.unconfigured(root.toFile());String identity=original.identity();
        FeatureContext feature=new FeatureContext(android(),null,null,event->events.incrementAndGet());var workspace=new WorkspaceModule(feature);
        try(LocalModule local=new LocalModule(feature,workspace,original)){
            byte[] secret="PRIVATE_HOME_BODY_do_not_read_诊断".getBytes(StandardCharsets.UTF_8);Path home=root.resolve("managed-process/home");Files.write(home.resolve("PRIVATE_FILENAME_not_metadata"),secret);
            var preview=local.diagnosticMetadataForOwner(new RunControl());JSONObject bundle=new JSONObject(preview.textForOwner()),sources=bundle.getJSONObject("sources");
            assertEquals("metadata_collected",bundle.getString("collectionStatus"));for(String name:List.of("platform","runtime","phone","storage"))assertEquals("collected",sources.getJSONObject(name).getString("status"));
            JSONObject runtime=sources.getJSONObject("runtime").getJSONObject("facts"),storage=sources.getJSONObject("storage").getJSONObject("facts"),phone=sources.getJSONObject("phone").getJSONObject("facts");
            assertFalse(runtime.getBoolean("androidProfilePresent"));assertFalse(runtime.getBoolean("activeGenerationLoaded"));assertFalse(runtime.getBoolean("integrityRechecked"));assertEquals(ProjectSources.sha256(identity.getBytes(StandardCharsets.UTF_8)),runtime.getString("runtimeIdentityFingerprintSha256"));assertTrue(runtime.isNull("catalogFingerprintSha256"));
            assertTrue(runtime.isNull("memoryLow"));assertFalse(runtime.getBoolean("memoryFresh"));assertTrue(runtime.isNull("memoryAvailableBytes"));assertTrue(runtime.isNull("memoryObservationAgeMillis"));assertEquals(32768,runtime.getInt("automaticLogPageBytes"));assertFalse(runtime.getBoolean("automaticLogPageReduced"));
            assertEquals(secret.length,storage.getLong("homeLogicalBytes"));assertTrue(storage.isNull("downloadLogicalBytes"));assertFalse(storage.getBoolean("fileContentsRead"));assertTrue(storage.getBoolean("logicalCountsNotAdditive"));
            JSONObject shared=AndroidPhoneDiagnostics.preview(android(),null).readiness();for(String key:List.of("mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl"))assertEquals(shared.getString(key),phone.getString(key));assertFalse(phone.getBoolean("backgroundSampled"));
            String text=preview.textForOwner();for(String forbidden:List.of("PRIVATE_HOME_BODY","PRIVATE_FILENAME",root.toString(),"\"sessionId\"","\"apiKey\"","\"taskId\"","\"runId\""))assertFalse(forbidden,text.contains(forbidden));
            assertEquals(identity,original.identity());assertFalse(original.describe().getBoolean("canExecute"));assertEquals(0,events.get());
            var window=new NativeDiagnosticWindow(()->true);window.open();window.replace(preview);window.review(preview.sha256(),true);var exact=window.prepare();Path destination=root.resolve("selected-by-own-test.json");
            JSONObject written=window.write(exact,()->Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW),new RunControl());byte[] actual=Files.readAllBytes(destination);
            assertEquals(preview.textForOwner(),new String(actual,StandardCharsets.UTF_8));assertEquals(preview.sha256(),ProjectSources.sha256(actual));assertEquals(actual.length,written.getInt("bytes"));assertFalse(written.getBoolean("destinationPersistenceVerified"));assertFalse(bundle.getBoolean("commandsExecuted"));assertFalse(bundle.getBoolean("tasksChanged"));assertFalse(bundle.getBoolean("deviceAcceptanceProved"));assertEquals(0,events.get());
            assertThrows(SecurityException.class,()->window.write(exact,()->{fail("old reviewed bytes reopened a provider");return null;},new RunControl()));assertArrayEquals(secret,Files.readAllBytes(home.resolve("PRIVATE_FILENAME_not_metadata")));
        }finally{workspace.close();original.close();clean(root);}
    }
    @Test public void unavailableRuntimeIsPartialWhileIndependentNativePlatformAndSharedPhoneFactsRemainVisible()throws Exception {
        try(var collector=new NativeDiagnosticCollector(NativeDiagnosticSources.create(android(),null),1500)){
            JSONObject bundle=new JSONObject(collector.collect(new RunControl()).textForOwner()),sources=bundle.getJSONObject("sources");assertEquals("partial_metadata",bundle.getString("collectionStatus"));
            for(String name:List.of("runtime","storage")){assertEquals("read_failed",sources.getJSONObject(name).getString("status"));assertTrue(sources.getJSONObject(name).isNull("facts"));}
            for(String name:List.of("platform","phone"))assertEquals("collected",sources.getJSONObject(name).getString("status"));assertFalse(bundle.getBoolean("resourceReleaseProved"));assertFalse(bundle.getBoolean("trialExecuted"));
        }
    }
    @Test public void taskRunAuthorityAndClosedLocalModuleCannotCreateANativeDiagnosticRead()throws Exception {
        Path root=Files.createTempDirectory("owner-diagnostic-lifetime-");var original=ManagedProjectRuntime.unconfigured(root.toFile());FeatureContext feature=new FeatureContext(android(),null,null,event->fail("read-only metadata emitted a task event"));var workspace=new WorkspaceModule(feature);var local=new LocalModule(feature,workspace,original);
        try{RunControl task=new RunControl();task.bindHostRun("own-task","own-run",1);assertThrows(SecurityException.class,()->local.diagnosticMetadataForOwner(task));local.close();assertThrows(IllegalStateException.class,()->local.diagnosticMetadataForOwner(new RunControl()));}
        finally{local.close();workspace.close();original.close();clean(root);}
    }
}
