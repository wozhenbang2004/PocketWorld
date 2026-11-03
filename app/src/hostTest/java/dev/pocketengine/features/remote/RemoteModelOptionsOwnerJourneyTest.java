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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original SQL/foreground owner/HTTP/Companion/native process; synthetic CLI/account only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteModelOptionsOwnerJourneyTest {
    static final String TOKEN="native-worker-model-options-fixture-token-longer-than-32-characters";
    static String utf8(Path file)throws Exception{return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-native-model-options-");final Object nativeOwner=new Object();final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        Process server;BufferedReader output;String base,task,worker;TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture(String worker)throws Exception {this.worker=worker;try{
            Path repo=Path.of(java.util.Objects.requireNonNull(System.getProperty("pocket.gateway.source"),"pocket.gateway.source must name the actual checkout")).toAbsolutePath().normalize();
            server=new ProcessBuilder(System.getProperty("pocket.gateway.python","python3"),"-B","-u",repo.resolve("companion/worker_model_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(utf8(root.resolve("server-stderr.log")),output.ready());base=new JSONObject(output.readLine()).getString("baseUrl");
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->TOKEN,path->{});host.register(remote);remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","native model options fixture").put("baseUrl",base));owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);
            JSONObject env=remote.handle("environments",new JSONObject().put("profileId","computer")).getJSONArray("environments").getJSONObject(0);JSONObject probe=remote.handle("probeWorker",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("environmentBinding",env.getString("bindingHash")).put("worker",worker).put("model","fixture-model"));assertTrue(probe.toString(),probe.getBoolean("available"));if(worker.equals("claude"))assertFalse(probe.has("modelOptions"));assertEquals(0,countPrompts());
            JSONObject first=new JSONObject().put("profileId","computer").put("bindingHash",remote.profileBindingHash("computer")).put("environmentId","workspace").put("environmentBinding",env.getString("bindingHash")).put("worker",worker).put("prompt","Explicit original coding task").put("model","fixture-model").put("timeoutSeconds",30);if(worker.equals("codex_app_server"))first.put("modelOptionsDigest",probe.getJSONObject("modelOptions").getString("digest"));
            String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",RemoteTaskRequest.first(first)));JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));task=response.getJSONObject("result").getString("hostTaskId");settled("completed");
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        JSONObject source()throws Exception{return tasks.remoteOwnerTarget(task);}
        void settled(String status)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject current=source();if(!current.optString("jobId").isEmpty())remote.handle("getJob",current);JSONObject next=source();if(next.optString("executorStatus").equals(status)&&next.optBoolean("dispatchFenceFinal"))return;Thread.sleep(10);}fail(tasks.get(task,false).toString());}
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject view)throws Exception{return new JSONObject().put("windowId",view.getString("windowId")).put("taskId",task).put("reviewId",view.getString("reviewId"));}
        JSONObject choice(JSONObject view)throws Exception{return new JSONObject().put("model","fixture-model").put("effort",worker.equals("claude")?"high":"ultra").put("modelOptionsDigest",view.getJSONObject("source").getJSONObject("modelOptions").getString("digest"));}
        int submits()throws Exception{return Files.exists(root.resolve("submit-count"))?Integer.parseInt(utf8(root.resolve("submit-count"))):0;}
        int countPrompts()throws Exception {Path file=root.resolve("workspace/"+(worker.equals("claude")?"claude-prompt-count":"codex-turn-count"));return Files.exists(file)?utf8(file).length():0;}
        public void close()throws Exception{if(owner!=null)owner.setForeground(nativeOwner,false);if(remote!=null)remote.close();if(host!=null)host.close();if(server!=null){server.destroy();if(!server.waitFor(6,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());JSONObject release=new JSONObject(utf8(root.resolve("resource-release.json")));assertTrue(release.getBoolean("listenerClosed"));for(String key:new String[]{"activeProcesses","activeDrivers","resources"})assertEquals(0,release.getInt(key));}if(output!=null)output.close();if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}}
    }
    @Test public void nativeOwnerExplicitNextTurnRevalidatesModelEffortAndKeepsOneTaskOriginalSessionAndBudget()throws Exception {
        for(String worker:new String[]{"claude","codex_app_server"})try(Fixture f=new Fixture(worker)){
            JSONObject view=f.open(),source=view.getJSONObject("source"),options=source.getJSONObject("modelOptions");assertTrue(view.toString(),view.getBoolean("canContinue"));assertEquals(1,f.countPrompts());String run=source.getString("runId"),session=source.getString("threadId");int epoch=source.getInt("coordinatorEpoch");assertFalse(view.toString().contains("NEVER_COPY_ACCOUNT"));
            f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","Owner explicitly chooses the next coding request").put("modelSelection",f.choice(view)));f.settled("completed");JSONObject next=f.source(),runtime=next.getJSONObject("modelRuntime");assertEquals(f.task,next.getString("taskId"));assertNotEquals(run,next.getString("runId"));assertEquals(epoch+1,next.getInt("coordinatorEpoch"));assertEquals(session,next.getString("threadId"));assertEquals(2,f.countPrompts());assertEquals(2,f.submits());assertEquals(1,f.tasks.list().length());assertEquals(f.choice(view).getString("effort"),runtime.getString("requestedEffort"));assertEquals("known",runtime.getJSONObject("contextWindow").getString("status"));
            if(worker.equals("claude")){assertTrue(runtime.isNull("reportedEffort"));assertEquals("unknown_stream_sdk_omits_effective_effort",runtime.getString("effortReadback"));JSONArray argv=new JSONArray(utf8(f.root.resolve("workspace/last-launch-args.json")));assertTrue(argv.toString().contains("--effort"));assertTrue(argv.toString().contains("high"));}
            else {assertEquals("ultra",runtime.getString("reportedEffort"));JSONObject request=new JSONObject(utf8(f.root.resolve("workspace/last-turn-request.json")));assertEquals("ultra",request.getString("effort"));assertEquals("fixture-model",request.getString("model"));}
            assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","no stale second write").put("modelSelection",f.choice(view))));assertEquals(2,f.submits());
        }
    }
    @Test public void forgedStaleOrBackgroundModelChoiceCannotCreateAnEpochOrDispatch()throws Exception {
        try(Fixture f=new Fixture("claude")){
            JSONObject view=f.open(),source=view.getJSONObject("source");String run=source.getString("runId");for(JSONObject invalid:new JSONObject[]{f.choice(view).put("modelOptionsDigest","0".repeat(64)),f.choice(view).put("model","unadvertised"),f.choice(view).put("effort","max"),f.choice(view).put("grant",true)})assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","invalid choice").put("modelSelection",invalid)));
            assertThrows(SecurityException.class,()->f.tasks.checkRemoteContinuationSource(new JSONObject(source.toString()).put("modelOptions",new JSONObject())));assertEquals(run,f.source().getString("runId"));assertEquals(1,f.submits());f.owner.setForeground(f.nativeOwner,false);assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","no background choice").put("modelSelection",f.choice(view))));assertEquals(run,f.source().getString("runId"));assertEquals(1,f.countPrompts());
        }
    }
    @Test public void changedActualCliCapabilitiesAfterOwnerCasBlockBeforeAnyNewPromptAndNeverReplay()throws Exception {
        for(String worker:new String[]{"claude","codex_app_server"})try(Fixture f=new Fixture(worker)){
            JSONObject view=f.open();Files.write(f.root.resolve("change-model-options"),"controlled native CLI capability version change".getBytes(StandardCharsets.UTF_8));f.owner.handle(f.nativeOwner,"continue",f.action(view).put("prompt","do not silently use changed capabilities").put("modelSelection",f.choice(view)));f.settled("blocked");assertEquals(2,f.submits());assertEquals(1,f.countPrompts());assertFalse(f.source().getJSONObject("controls").getBoolean("threadResume"));assertEquals(1,f.tasks.list().length());
        }
    }
}
