package dev.pocketengine.features.remote;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Original RemoteModule native route and real loopback IO. Not a phone/TLS/WAN test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteTaskTransportNativeTest {
    private static final String TOKEN="fixture-only-original-remote-token-more-than-32-chars";
    private static RemoteModule module()throws Exception {
        Context android=RuntimeEnvironment.getApplication();android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");
        FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        return new RemoteModule(context,(id,base)->TOKEN,path->{});
    }
    @Test public void allFourExistingTaskReadRoutesCancelTheirOriginalSocket()throws Exception {
        for(String command:new String[]{"environments","listJobs","getJob","readFile"}){
            RunControl original=new RunControl();original.bindHostRun("task","run",1);AtomicReference<Throwable> failure=new AtomicReference<>();
            try(RemoteTransportDeadlineTest.SlowServer server=new RemoteTransportDeadlineTest.SlowServer(false,false,200);RemoteModule remote=module()){
                remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","fixture").put("baseUrl",server.base()));
                JSONObject input=new JSONObject().put("profileId","computer").put("jobId","original-job").put("environmentId","original-workspace").put("path","file.txt");
                Thread worker=new Thread(()->{try{remote.handleForTask(command,input,original);failure.set(new AssertionError("Blocked task read returned a result"));}catch(Throwable stopped){failure.set(stopped);}},"native-remote-control-fixture");worker.setDaemon(true);worker.start();
                assertTrue(command,server.received.await(3,TimeUnit.SECONDS));original.cancel();worker.join(1800);assertFalse(command,worker.isAlive());assertTrue(command,failure.get() instanceof InterruptedException);assertEquals(1,server.requests);assertTrue(server.requestLine.startsWith("GET "));
                assertEquals(0,remote.snapshot().getJSONArray("operations").length());
            }
        }
    }
    @Test public void nativeTaskReadEntryCannotBecomeAnOwnerWriteEntry()throws Exception {
        try(RemoteModule remote=module()){
            for(String command:new String[]{"submitJob","approveJob","cancelJob","writeFile","saveProfile","probeWorker"})assertThrows(command,SecurityException.class,()->remote.handleForTask(command,new JSONObject(),new RunControl()));
            assertEquals(0,remote.snapshot().getJSONArray("operations").length());assertEquals(0,remote.snapshot().getJSONArray("profiles").length());
        }
    }
    @Test public void cancelledOriginalRunCannotOpenAnyTaskReadConnection()throws Exception {
        try(RemoteModule remote=module()){
            RunControl old=new RunControl();old.cancel();for(String command:new String[]{"environments","listJobs","getJob","readFile"})assertThrows(InterruptedException.class,()->remote.handleForTask(command,new JSONObject(),old));
            assertEquals(0,remote.snapshot().getJSONArray("operations").length());
        }
    }
}
