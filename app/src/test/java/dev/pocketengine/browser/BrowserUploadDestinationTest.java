package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact native picker binding/lifetime tests; file-provider copies are covered by BrowserUploadsTest. */
public final class BrowserUploadDestinationTest {
    static final class Fixture implements AutoCloseable {
        final BrowserProfileRegistry registry=new BrowserProfileRegistry(new File(Files.createTempDirectory("upload-destination-").toFile(),"profiles.json"));final BrowserProfileRegistry.Session profile=registry.openSession(true);final RunControl original=new RunControl();final AtomicBoolean pageCurrent=new AtomicBoolean(true);final String url="https://upload.example/account/submit?document=original&ticket=fixture-only#confirm";
        final JSONObject identity;
        Fixture()throws Exception {String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();original.bindHostRun(task,run,2);original.pause();identity=profile.identity().put("taskId",task).put("runId",run).put("epoch",2).put("browserSpaceId",UUID.randomUUID().toString()).put("tabId",UUID.randomUUID().toString()).put("tabGeneration",31L).put("chooserId",UUID.randomUUID().toString()).put("navigation",8L);JSONObject target=BrowserUploads.destinationIdentity(url);for(var keys=target.keys();keys.hasNext();){String key=keys.next();identity.put(key,target.get(key));}}
        BrowserUploads.Destination destination()throws Exception{return new BrowserUploads.Destination(profile,identity,false,pageCurrent::get,original);}
        public void close(){original.cancel();registry.endSession(profile);}
    }
    @Test public void metadataPinsOriginalTaskRunSpacePageAndExactDestinationWithoutSecrets()throws Exception {try(Fixture f=new Fixture();BrowserUploads.Destination destination=f.destination()){
        for(String key:new String[]{"taskId","runId","epoch","browserSpaceId","tabId","tabGeneration","chooserId","navigation","profileId","profileRevision","browserSessionId"})assertEquals(f.identity.get(key),destination.provenance.get(key));assertEquals("https://upload.example/account/submit",destination.provenance.getString("destinationUrl"));assertTrue(destination.matchesDestination(f.url));assertFalse(destination.provenance.toString().contains("ticket="));assertFalse(destination.provenance.toString().contains("fixture-only"));assertFalse(destination.matchesDestination("https://upload.example/account/submit?document=another&ticket=fixture-only#confirm"));assertFalse(destination.matchesDestination("https://other.example/account/submit?document=original&ticket=fixture-only#confirm"));
    }}
    @Test public void originalTaskCancellationActivelyClosesItsFileCopyExactlyOnce()throws Exception {try(Fixture f=new Fixture();BrowserUploads.Destination destination=f.destination()){
        AtomicInteger closed=new AtomicInteger();try(AutoCloseable watched=destination.watch(closed::incrementAndGet)){assertTrue(destination.current());f.original.cancel();assertFalse(destination.current());assertEquals(1,closed.get());destination.close();assertEquals(1,closed.get());assertThrows(InterruptedException.class,destination::check);}
    }}
    @Test public void pageNavigationOrProfileSwitchCannotReuseOrReopenThePickerBinding()throws Exception {try(Fixture f=new Fixture();BrowserUploads.Destination destination=f.destination()){
        AtomicInteger closed=new AtomicInteger();destination.watch(closed::incrementAndGet);f.pageCurrent.set(false);destination.close();assertEquals(1,closed.get());f.pageCurrent.set(true);assertFalse(destination.current());assertFalse(destination.matchesDestination(f.url));
    }try(Fixture f=new Fixture();BrowserUploads.Destination destination=f.destination()){
        AtomicInteger closed=new AtomicInteger();destination.watch(closed::incrementAndGet);f.registry.select(f.profile.profileId,f.registry.revision(),true);assertFalse(destination.current());assertEquals(1,closed.get());assertThrows(InterruptedException.class,destination::check);
    }}
    @Test public void mismatchedProfileRunOrEpochNeverCreatesATaskUploadDestination()throws Exception {try(Fixture f=new Fixture()){
        JSONObject wrong=new JSONObject(f.identity.toString()).put("browserSessionId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->new BrowserUploads.Destination(f.profile,wrong,false,()->true,f.original));JSONObject changed=new JSONObject(f.identity.toString()).put("epoch",3);assertThrows(SecurityException.class,()->new BrowserUploads.Destination(f.profile,changed,false,()->true,f.original));assertThrows(SecurityException.class,()->new BrowserUploads.Destination(f.profile,f.identity,false,()->true,null));
    }}
}
