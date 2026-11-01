package dev.pocketengine.features.remote;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import androidx.activity.ComponentActivity;
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
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original SQL/native owner/HTTP/worker/files. CLI and account are controlled peers only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteProjectHistoryOwnerJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-native-project-history-");
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        final ActivityController<ComponentActivity> activity=Robolectric.buildActivity(ComponentActivity.class).setup();
        final ComponentActivity nativeOwner=activity.get();
        Process server;BufferedReader output;String base,task,window;TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;volatile boolean cancelBeforePost;
        Fixture()throws Exception {try{
            Path repo=Path.of(System.getProperty("user.dir"));server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/project_history_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());base=new JSONObject(output.readLine()).getString("baseUrl");boot(true);
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {
            var app=RuntimeEnvironment.getApplication();if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");app.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().clear().commit();}
            tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};
            remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->"original-project-history-fixture-token-longer-than-32",path->{if(cancelBeforePost&&path.equals("/v1/jobs")){cancelBeforePost=false;tasks.requestCancel(task,task);}});
            host.register(remote);if(first){remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","controlled native project host").put("baseUrl",base));
                // Test-only presence paired with the injected real HTTP credential reader,
                // exactly as original result/input fixtures. Not AndroidKeyStore evidence.
                var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
                app.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,"computer",base),"fixture-presence-only").commit();}
            owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);
        }
        JSONObject action(String command,JSONObject values)throws Exception {JSONObject input=new JSONObject(values.toString()).put("windowId",window).put("taskId",task);return owner.handle(nativeOwner,command,input).getJSONObject("snapshot");}
        JSONObject first()throws Exception {
            task=UUID.randomUUID().toString();window=UUID.randomUUID().toString();JSONObject env=remote.handle("environments",new JSONObject().put("profileId","computer")).getJSONArray("environments").getJSONObject(0);
            JSONObject request=new JSONObject().put("profileId","computer").put("bindingHash",remote.profileBindingHash("computer")).put("environmentId","workspace").put("environmentBinding",env.getString("bindingHash")).put("worker","codex_app_server").put("readOnly",false).put("prompt","Modify selected public source").put("timeoutSeconds",30);
            return action("project.open",new JSONObject().put("request",request));
        }
        JSONObject reviewStart()throws Exception {first();return action("project.reviewStart",new JSONObject().put("projectRoot",""));}
        JSONObject confirm(JSONObject review)throws Exception{return action("project.confirm",new JSONObject().put("digest",review.getJSONObject("projectHistoryReview").getString("digest")).put("scopeAcknowledged",true));}
        JSONObject start()throws Exception {confirm(reviewStart());return settled();}
        JSONObject settled()throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);JSONObject source=null;
            while(System.nanoTime()<until){source=tasks.remoteOwnerTarget(task);if(!source.optString("jobId").isEmpty())remote.handle("getJob",source);source=tasks.remoteOwnerTarget(task);if(source.optString("executorStatus").equals("completed")&&source.optJSONObject("projectHistory")!=null&&source.getJSONObject("projectHistory").optString("comparisonStatus").matches("available|not_selected"))return source;Thread.sleep(10);}throw new AssertionError("Original job did not settle "+source+Files.readString(root.resolve("server-stderr.log")));
        }
        JSONObject openTask()throws Exception {if(window!=null)owner.close(window);window=UUID.randomUUID().toString();return action("open",new JSONObject());}
        JSONObject refresh()throws Exception{return action("refresh",new JSONObject());}
        JSONObject restoreReview(JSONObject current)throws Exception {
            JSONObject history=current.getJSONObject("projectHistoryPage").getJSONObject("history"),before=history.getJSONObject("baseline");return action("project.reviewRestore",new JSONObject().put("reviewId",current.getString("reviewId")).put("snapshotId",before.getString("snapshotId")).put("snapshotVersion",before.getString("snapshotVersion")).put("path","source.txt"));
        }
        JSONObject operation(JSONObject snapshot)throws Exception{return snapshot.getJSONObject("projectHistoryOperation");}
        int count(String file)throws Exception{return Files.exists(root.resolve(file))?Integer.parseInt(Files.readString(root.resolve(file))):0;}
        void closeNative()throws Exception {if(owner!=null){owner.setForeground(nativeOwner,false);if(window!=null)owner.close(window);}if(remote!=null)remote.close();if(host!=null)host.close();if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();tasks=null;}}
        void cold()throws Exception{closeNative();boot(false);openTask();}
        public void close()throws Exception {
            closeNative();if(server!=null){server.destroy();if(!server.waitFor(6,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());if(Files.exists(root.resolve("resource-release.json"))){JSONObject proof=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertTrue(proof.getBoolean("listenerClosed"));assertEquals(0,proof.getInt("activeProcesses"));assertEquals(0,proof.getInt("activeDrivers"));assertEquals(0,proof.getInt("resources"));}}
            if(output!=null)output.close();activity.pause().stop().destroy();
        }
    }
    @Test public void originalNativeBaselineActualDiffOneFileRestoreFreshSnapshotAndSameSessionContinueKeepsOldVersionReadable()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject source=f.start();assertEquals(1,f.count("submit-count"));assertEquals("worker version two\n",Files.readString(f.root.resolve("workspace/source.txt")));String firstOperation=source.getString("jobOperationId");JSONObject current=f.openTask();current=f.action("project.list",new JSONObject());JSONObject before=current.getJSONObject("projectHistoryPage").getJSONObject("history").getJSONObject("baseline");assertFalse(before.getBoolean("authorshipVerified"));assertEquals(1,before.getInt("fileCount"));
            JSONObject diff=f.action("project.diff",new JSONObject().put("path","source.txt"));assertTrue(diff.getJSONObject("projectHistoryDiff").getString("diff").contains("-owner original source"));
            JSONObject restored=f.confirm(f.restoreReview(f.refresh())); // Refresh preserves the immutable page, not new authority from a cache.
            JSONObject receipt=f.operation(restored).getJSONObject("receipt");assertEquals("completed",receipt.getString("status"));assertFalse(receipt.getBoolean("restoresConversation"));assertEquals(1,f.count("restore-count"));assertEquals("owner original source\n",Files.readString(f.root.resolve("workspace/source.txt")));
            current=f.refresh();JSONObject captured=f.action("captureSnapshot",new JSONObject().put("reviewId",current.getString("reviewId")).put("paths",new JSONArray().put("source.txt")));JSONArray snapshots=captured.getJSONObject("task").getJSONArray("remoteResultSnapshots");JSONObject snapshot=snapshots.getJSONObject(snapshots.length()-1);assertEquals("selected_current_workspace_after_settled_original_job",snapshot.getString("captureSource"));assertEquals(receipt.getString("version"),"sha256:"+snapshot.getJSONArray("files").getJSONObject(0).getString("sha256"));
            current=f.refresh();f.action("continue",new JSONObject().put("reviewId",current.getString("reviewId")).put("prompt","Continue from restored exact current bytes").put("snapshotId",snapshot.getString("snapshotId")).put("snapshotVersion",snapshot.getString("snapshotVersion")));JSONObject next=f.settled();assertEquals(source.getString("threadId"),next.getString("threadId"));assertNotEquals(firstOperation,next.getString("jobOperationId"));assertEquals("owner original source\n",Files.readString(f.root.resolve("protocol/continued-source-bytes")));assertEquals(2,f.count("submit-count"));
            current=f.refresh();JSONObject history=f.action("project.list",new JSONObject().put("operationId",firstOperation));assertEquals(source.getString("jobId"),history.getJSONObject("projectHistoryReadSource").getString("jobId"));assertEquals(TaskStore.capabilityFingerprint(before),TaskStore.capabilityFingerprint(history.getJSONObject("projectHistoryPage").getJSONObject("history").getJSONObject("baseline")));assertEquals(next.getString("jobId"),history.getJSONObject("source").getString("jobId"));assertEquals(1,f.tasks.list().length());
        }
    }
    @Test public void lostFileRestoreAckColdQueriesTheOriginalLedgerWithoutSecondPostAndCurrentDriftReturnsPersistedNoReplacementFailure()throws Exception {
        try(Fixture f=new Fixture()){
            f.start();JSONObject current=f.openTask();current=f.action("project.list",new JSONObject());JSONObject review=f.restoreReview(current);Files.writeString(f.root.resolve("drop-restore-ack"),"controlled lost response after actual original replacement");JSONObject unknown=f.confirm(review);String operation=f.operation(unknown).getString("operationId");assertEquals("unknown",f.operation(unknown).getString("state"));assertEquals(1,f.count("restore-count"));f.cold();JSONObject reconciled=f.action("project.queryRestore",new JSONObject().put("operationId",operation));assertEquals("receipt",f.operation(reconciled).getString("state"));assertEquals("completed",f.operation(reconciled).getJSONObject("receipt").getString("status"));f.action("project.queryRestore",new JSONObject().put("operationId",operation));assertEquals(1,f.count("restore-count"));assertEquals(1,f.count("submit-count"));
            current=f.refresh();current=f.action("project.list",new JSONObject());review=f.restoreReview(current);Files.writeString(f.root.resolve("workspace/source.txt"),"independent actual head\n");JSONObject failed=f.confirm(review);assertEquals("receipt",f.operation(failed).getString("state"));JSONObject noWrite=f.operation(failed).getJSONObject("receipt");assertEquals("failed",noWrite.getString("status"));assertTrue(noWrite.getBoolean("noReplacementDispatchConfirmed"));assertFalse(noWrite.getBoolean("replacementAttempted"));assertEquals("independent actual head\n",Files.readString(f.root.resolve("workspace/source.txt")));assertEquals(2,f.count("restore-count"));
        }
    }
    @Test public void realConversationForkUsesANewOriginalJobAndThreadWithoutRestoringFilesOrReusingOldApproval()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject source=f.start();JSONObject current=f.openTask();Files.writeString(f.root.resolve("workspace/source.txt"),"independent latest bytes before branch\n");JSONObject review=f.action("project.reviewFork",new JSONObject().put("reviewId",current.getString("reviewId")).put("prompt","New explicit branch goal, preserve current files").put("timeoutSeconds",30));JSONObject submitted=f.confirm(review);String branchTask=f.operation(submitted).getString("taskId");assertNotEquals(f.task,branchTask);f.task=branchTask;JSONObject branch=f.settled();assertNotEquals(source.getString("jobId"),branch.getString("jobId"));assertNotEquals(source.getString("threadId"),branch.getString("threadId"));assertEquals("fixture-branch",branch.getString("threadId"));assertFalse(branch.getJSONObject("sessionBranch").getBoolean("filesRestored"));assertFalse(branch.getJSONObject("sessionBranch").getBoolean("oldApprovalOrBudgetInherited"));assertEquals(source.getString("threadId"),branch.getJSONObject("sessionBranch").getString("forkedFromId"));assertEquals("independent latest bytes before branch\n",Files.readString(f.root.resolve("workspace/source.txt")));assertEquals(2,f.count("submit-count"));assertEquals(2,f.tasks.list().length());
        }
    }
    @Test public void oldForegroundReviewGenericJsonAndQueuedNativeCancelNeverAuthorizeAnyNewRemoteRequest()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.reviewStart();String digest=review.getJSONObject("projectHistoryReview").getString("digest");f.owner.setForeground(f.nativeOwner,false);f.owner.setForeground(f.nativeOwner,true);assertThrows(SecurityException.class,()->f.action("project.confirm",new JSONObject().put("digest",digest).put("scopeAcknowledged",true)));assertEquals(0,f.count("submit-count"));assertThrows(SecurityException.class,()->f.tasks.prepareRemoteProjectAction(null));assertThrows(SecurityException.class,()->f.remote.handle("submitJob",new JSONObject().put("projectHistory",new JSONObject())));
            review=f.action("project.reviewStart",new JSONObject().put("projectRoot",""));f.cancelBeforePost=true;JSONObject cancelled=f.confirm(review);JSONObject operation=f.operation(cancelled),actual=operation.getJSONObject("receipt");assertEquals("cancelled",actual.getString("status"));assertTrue(actual.getJSONObject("nativeLocalDispatchProof").getBoolean("originalPreparedClaimNotConsumed"));assertEquals("cancelled",f.tasks.get(f.task,false).getString("status"));assertEquals(0,f.count("submit-count"));assertEquals("cancelled",f.remote.handle("reconcileOperation",new JSONObject().put("profileId","computer").put("operationId",f.task)).getString("status"));assertEquals(0,f.count("submit-count"));
        }
    }
}
