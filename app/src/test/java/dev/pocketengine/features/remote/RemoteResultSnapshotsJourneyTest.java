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

/** Fresh native SQLite/owner workflow -> production Python Companion/files -> fake Codex pipe. Not a live CLI/device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteResultSnapshotsJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("native-original-result-");Process server;BufferedReader output;
        String base,task;Path workspace;TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        Fixture()throws Exception {
            try {
            Path repo=Path.of(System.getProperty("user.dir")),script=repo.resolve("companion/snapshot_fixture_server.py");
            server=new ProcessBuilder("/usr/bin/python3","-B","-u",script.toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(server.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<deadline)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());JSONObject bootstrap=new JSONObject(output.readLine());base=bootstrap.getString("baseUrl");workspace=Path.of(bootstrap.getString("workspace"));
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};
            remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,address)->"native-snapshot-fixture-token-longer-than-32-characters",path->{});host.register(remote);remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","controlled native result fixture").put("baseUrl",base));owner=new RemoteOwnerWorkflows(host,tasks,sink);
            String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","codex_app_server").put("readOnly",true).put("prompt","controlled read-only fixture")));
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);JSONObject response=null;while(response==null&&System.nanoTime()<deadline){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}assertNotNull(events.toString(),response);task=response.getJSONObject("result").getString("hostTaskId");settled();
            }catch(Exception|Error failed){try{close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        }
        JSONObject open()throws Exception{return owner.handle("open",new JSONObject().put("taskId",task).put("windowId",UUID.randomUUID().toString())).getJSONObject("snapshot");}
        JSONObject action(JSONObject review)throws Exception{return new JSONObject().put("taskId",task).put("windowId",review.getString("windowId")).put("reviewId",review.getString("reviewId"));}
        JSONObject refresh(JSONObject review)throws Exception{return owner.handle("refresh",action(review)).getJSONObject("snapshot");}
        JSONObject capture(JSONObject review)throws Exception{return owner.handle("captureSnapshot",action(review).put("paths",new JSONArray().put("proof.txt")));}
        JSONObject saved()throws Exception{return tasks.get(task,false).getJSONArray("remoteResultSnapshots").getJSONObject(0);}
        int captures()throws Exception{return Files.exists(root.resolve("capture-count"))?Integer.parseInt(Files.readString(root.resolve("capture-count"))):0;}
        void settled()throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){JSONObject target=tasks.remoteOwnerTarget(task);if(!target.optString("jobId").isEmpty()){remote.handle("getJob",new JSONObject(target.toString()));if(tasks.get(task,false).optString("executionStatus").equals("completed")){Thread.sleep(20);return;}}Thread.sleep(10);}throw new AssertionError(tasks.get(task,false));}
        public void close()throws Exception{if(remote!=null)remote.close();if(host!=null)host.close();if(server!=null){server.destroy();if(!server.waitFor(3,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}}if(output!=null)output.close();}
    }
    @Test public void originalJobSnapshotActualBytesViewerThenSameVersionContinueStayOnOneNativeTask()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.open();String originalRun=f.tasks.get(f.task,false).getString("runId");JSONObject captured=f.capture(review);assertEquals("receipt",captured.getJSONObject("operation").getString("state"));assertEquals(1,f.captures());JSONObject saved=f.saved();assertTrue(saved.getBoolean("immutableArtifact"));assertFalse(saved.getBoolean("authorshipVerified"));assertFalse(saved.toString().contains("base64"));
            review=f.refresh(captured.getJSONObject("snapshot"));JSONObject shown=f.owner.handle("readSnapshot",f.action(review).put("snapshotId",saved.getString("snapshotId")).put("path","proof.txt")).getJSONObject("snapshot");JSONObject file=shown.getJSONObject("snapshotFile");assertEquals("ORIGINAL IMMUTABLE NATIVE RESULT\n",file.getString("text"));assertEquals(saved.getString("snapshotVersion"),file.getString("snapshotVersion"));assertTrue(file.getBoolean("immutableArtifact"));
            f.owner.handle("continue",f.action(shown).put("prompt","continue the exact selected snapshot").put("snapshotId",saved.getString("snapshotId")).put("snapshotVersion",saved.getString("snapshotVersion")));f.settled();JSONObject task=f.tasks.get(f.task,false);assertEquals(1,f.tasks.list().length());assertEquals(f.task,task.getString("id"));assertNotEquals(originalRun,task.getString("runId"));assertEquals(saved.getString("snapshotVersion"),task.getJSONObject("evidence").getString("resultSnapshotVersion"));assertTrue(task.getJSONObject("evidence").getBoolean("snapshotPrelaunchVerified"));assertEquals("11",Files.readString(f.workspace.resolve("codex-turn-count")));assertEquals(1,f.captures());
        }
    }
    @Test public void immutableViewSurvivesCurrentEditButOldVersionContinueRefusesBeforeNewNativeRunOrPost()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.open();f.capture(review);JSONObject saved=f.saved();Files.writeString(f.workspace.resolve("proof.txt"),"CHANGED CURRENT WORKSPACE");review=f.refresh(review);JSONObject shown=f.owner.handle("readSnapshot",f.action(review).put("snapshotId",saved.getString("snapshotId")).put("path","proof.txt")).getJSONObject("snapshot");assertEquals("ORIGINAL IMMUTABLE NATIVE RESULT\n",shown.getJSONObject("snapshotFile").getString("text"));String run=f.tasks.get(f.task,false).getString("runId");int operations=f.tasks.get(f.task,false).getJSONArray("operations").length();
            RemoteTransport.HttpFailure refused=assertThrows(RemoteTransport.HttpFailure.class,()->f.owner.handle("continue",f.action(shown).put("prompt","must not run old selected version").put("snapshotId",saved.getString("snapshotId")).put("snapshotVersion",saved.getString("snapshotVersion"))));assertEquals(409,refused.status);assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(operations,f.tasks.get(f.task,false).getJSONArray("operations").length());assertEquals("1",Files.readString(f.workspace.resolve("codex-turn-count")));assertEquals(1,f.captures());
        }
    }
    @Test public void lostCaptureAckRefreshOnlyReadsOriginalOperationAndSettlesWithoutRecapturingOrNewTask()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.open();Files.writeString(f.root.resolve("drop-next-capture-ack"),"drop once after actual durable commit");JSONObject unknown=f.capture(review);assertEquals("unknown",unknown.getJSONObject("operation").getString("state"));assertTrue(f.tasks.hasUnresolved(f.task));assertEquals(1,f.captures());Files.writeString(f.workspace.resolve("proof.txt"),"LATER CONTENT MUST NOT REPLACE CAPTURE");
            JSONObject refreshed=f.refresh(unknown.getJSONObject("snapshot"));assertFalse(f.tasks.hasUnresolved(f.task));assertEquals(1,f.tasks.list().length());assertEquals(1,f.captures());JSONObject saved=f.saved();JSONObject shown=f.owner.handle("readSnapshot",f.action(refreshed).put("snapshotId",saved.getString("snapshotId")).put("path","proof.txt")).getJSONObject("snapshot");assertEquals("ORIGINAL IMMUTABLE NATIVE RESULT\n",shown.getJSONObject("snapshotFile").getString("text"));
        }
    }
}
