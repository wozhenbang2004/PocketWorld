package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original SQL/owner/HTTP/Companion/Popen, with an explicitly synthetic Pi 1.4.0 peer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemotePiOwnerJourneyTest {
    static final String TOKEN="native-pi-no-tools-fixture-token-longer-than-32-characters";
    static String utf8(Path file)throws Exception{return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-pi-native-owner-");final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();final java.util.concurrent.atomic.AtomicBoolean failDispatch=new java.util.concurrent.atomic.AtomicBoolean();Process server;BufferedReader output;String base,task;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture(String mode)throws Exception {try{
            Path repo=Path.of(java.util.Objects.requireNonNull(System.getProperty("pocket.gateway.source"),"pocket.gateway.source is required")).toAbsolutePath().normalize();
            server=new ProcessBuilder(System.getProperty("pocket.gateway.python","python3"),"-B","-u",repo.resolve("companion/pi_rpc_fixture_server.py").toString(),root.toString(),mode).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(utf8(root.resolve("server-stderr.log")),output.ready());base=new JSONObject(output.readLine()).getString("baseUrl");
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};
            remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->TOKEN,path->{if(path.equals("/v1/jobs")&&failDispatch.getAndSet(false))throw new java.io.IOException("controlled unconfirmed native dispatch boundary");});host.register(remote);remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","Pi no-tools fixture").put("baseUrl",base));
            owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);
            JSONObject environment=remote.handle("environments",new JSONObject().put("profileId","computer")).getJSONArray("environments").getJSONObject(0);
            JSONObject probe=remote.handle("probeWorker",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("environmentBinding",environment.getString("bindingHash")).put("worker","pi"));assertTrue(probe.toString(),probe.getBoolean("protocolVerified"));assertTrue(probe.getBoolean("noTools"));assertFalse(probe.getBoolean("modelInferenceVerified"));
            assertFalse(Files.exists(root.resolve("workspace/pi-prompt-count")));
            JSONObject first=RemoteTaskRequest.first(new JSONObject().put("profileId","computer").put("bindingHash",remote.profileBindingHash("computer")).put("environmentId","workspace").put("environmentBinding",environment.getString("bindingHash")).put("worker","pi").put("prompt","Explicit native no-tools conversation").put("model","fixture/model-a").put("timeoutSeconds",30));
            String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",first));JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));task=response.getJSONObject("result").getString("hostTaskId");settled(mode.equals("end-only")?"unknown":"completed");
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        JSONObject source()throws Exception{return tasks.remoteOwnerTarget(task);}
        void settled(String status)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject current=source();if(!current.optString("jobId").isEmpty())remote.handle("getJob",current);JSONObject next=source();if(next.optString("executorStatus").equals(status)&&next.optBoolean("dispatchFenceFinal"))return;Thread.sleep(10);}fail(tasks.get(task,false).toString());}
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject view)throws Exception{return new JSONObject().put("windowId",view.getString("windowId")).put("taskId",task).put("reviewId",view.getString("reviewId"));}
        int count(String name)throws Exception{return Files.exists(root.resolve(name))?Integer.parseInt(utf8(root.resolve(name))):0;}
        public void close()throws Exception{if(owner!=null)owner.setForeground(nativeOwner,false);if(remote!=null)remote.close();if(host!=null)host.close();if(server!=null){server.destroy();if(!server.waitFor(6,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());JSONObject released=new JSONObject(utf8(root.resolve("resource-release.json")));assertTrue(released.getBoolean("listenerClosed"));for(String key:new String[]{"activeProcesses","activeDrivers","resources"})assertEquals(0,released.getInt(key));}if(output!=null)output.close();if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}}
    }
    @Test public void actualNativeFirstCreateAndExplicitOwnerContinuationKeepOneTaskAndExactPiSession()throws Exception {
        try(Fixture f=new Fixture("success")){
            JSONObject view=f.open(),old=view.getJSONObject("source");assertTrue(view.toString(),view.getBoolean("canContinue"));assertTrue(old.getBoolean("noTools"));assertTrue(old.getBoolean("nativeSettled"));assertEquals("pi_rpc_v1",old.getString("protocol"));assertFalse(old.getBoolean("resourcesHeld"));assertFalse(view.getBoolean("canCaptureSnapshot"));assertTrue(old.getJSONObject("processCleanupProof").getBoolean("processGroupStoppedVerified"));assertFalse(old.getJSONObject("controls").getBoolean("approve"));assertFalse(old.getJSONObject("controls").getBoolean("inputSteering"));
            assertFalse(view.toString().contains("HIDDEN"));assertFalse(view.toString().contains("pi-sessions"));assertEquals("confirmed",view.getString("eventReadStatus"));String run=old.getString("runId"),job=old.getString("jobId");int epoch=old.getInt("coordinatorEpoch");assertThrows(SecurityException.class,()->f.tasks.prepareRemoteContinuation(old,"no snapshot mode",new JSONObject()));assertEquals(run,f.source().getString("runId"));
            try{f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","A new explicit owner message, not replay"));}catch(Exception failure){throw new AssertionError(Files.exists(f.root.resolve("last-error.json"))?utf8(f.root.resolve("last-error.json")):f.tasks.get(f.task,false).toString(),failure);}f.settled("completed");JSONObject next=f.source();assertEquals(f.task,next.getString("taskId"));assertNotEquals(run,next.getString("runId"));assertNotEquals(job,next.getString("jobId"));assertEquals(epoch+1,next.getInt("coordinatorEpoch"));assertEquals(old.getString("sessionId"),next.getString("sessionId"));assertEquals(old.getString("threadId"),next.getString("threadId"));assertEquals(2,f.count("submit-count"));assertEquals(1,f.tasks.list().length());assertEquals("11",utf8(f.root.resolve("workspace/pi-prompt-count")));
            assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","stale duplicate")));assertEquals(2,f.count("submit-count"));assertEquals(0,f.count("approval-count"));assertEquals(0,f.count("question-count"));
        }
    }
    @Test public void exitZeroAndAgentEndWithoutSettledRemainUnknownAndCannotStartAnotherRun()throws Exception {
        try(Fixture f=new Fixture("end-only")){
            JSONObject view=f.open(),source=view.getJSONObject("source");assertFalse(view.getBoolean("canContinue"));assertEquals("unknown",source.getString("executorStatus"));String run=source.getString("runId");assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","not a retry license")));assertEquals(run,f.source().getString("runId"));assertEquals(1,f.count("submit-count"));assertEquals("1",utf8(f.root.resolve("workspace/pi-prompt-count")));
        }
    }
    @Test public void sourceJsonCannotForgeSettlementAndChangedPrivateNativeHistoryRemovesContinuation()throws Exception {
        try(Fixture f=new Fixture("success")){
            JSONObject view=f.open(),source=view.getJSONObject("source");for(String key:new String[]{"noTools","nativeSettled"})assertThrows(SecurityException.class,()->f.tasks.checkRemoteContinuationSource(new JSONObject(source.toString()).put(key,false)));
            JSONObject wrong=new JSONObject(source.toString()).put("sessionId","other-session");assertThrows(SecurityException.class,()->f.tasks.checkRemoteContinuationSource(wrong));String run=source.getString("runId");
            Files.write(f.root.resolve("mutate-session"),"controlled fixture mutation".getBytes(StandardCharsets.UTF_8));JSONObject fresh=f.owner.handle(f.nativeOwner,"refresh",f.action(view)).getJSONObject("snapshot");assertFalse(fresh.getBoolean("canContinue"));assertTrue(fresh.getJSONObject("source").getBoolean("nativeSettled"));assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(fresh).put("prompt","changed history is not authority")));assertEquals(run,f.source().getString("runId"));assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void unconfirmedNewContinuationDoesNotRenderOrAcknowledgeThePreviousRunEventCursor()throws Exception {
        try(Fixture f=new Fixture("success")){
            JSONObject view=f.open(),cursor=f.tasks.originalRemoteEventRead(f.task);String run=f.source().getString("runId");f.failDispatch.set(true);
            JSONObject actual=f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","one explicit new message")).getJSONObject("snapshot");
            assertFalse(actual.getBoolean("canContinue"));assertEquals("unconfirmed",actual.getString("eventReadStatus"));assertFalse(actual.has("eventRead"));assertNotEquals(run,f.source().getString("runId"));assertTrue(RemoteJobEvents.same(cursor,f.tasks.originalRemoteEventRead(f.task)));assertEquals(1,f.count("submit-count"));
            assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","no duplicate")));assertEquals(1,f.count("submit-count"));
        }
    }

}
