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

/** Real original SQL/host/Companion/bytes with explicit fake CLI peers; no actual account, model, phone or Compose clicks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteQuestionNativeJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("native-original-question-");final Object nativeOwner=new Object();final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        Process server;BufferedReader output;String base,task;Path workspace;TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture(String worker)throws Exception {try{
            Path repo=Path.of(System.getProperty("user.dir"));server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/interaction_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(server.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());JSONObject bootstrap=new JSONObject(output.readLine());base=bootstrap.getString("baseUrl");workspace=Path.of(bootstrap.getString("workspace"));boot(true);
            String request=UUID.randomUUID().toString();JSONObject payload=new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker",worker).put("prompt","Answer the original normal question; produce the explicit result");if(worker.equals("codex_app_server"))payload.put("readOnly",false);host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",payload));JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));task=response.getJSONObject("result").getString("hostTaskId");pending();
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {var app=RuntimeEnvironment.getApplication();if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");}tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->"native-question-fixture-token-longer-than-32-characters",path->{});host.register(remote);if(first)remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","original controlled question host").put("baseUrl",base));owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);}
        void reopenPhone()throws Exception {remote.close();host.close();boot(false);}
        JSONObject target()throws Exception{return tasks.remoteOwnerTarget(task);}
        void pending()throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject source=target();if(!source.optString("jobId").isEmpty()){remote.handle("getJob",new JSONObject(source.toString()));if(target().optJSONArray("pendingQuestions")!=null&&target().getJSONArray("pendingQuestions").length()>0)return;}Thread.sleep(10);}throw new AssertionError(tasks.get(task,false));}
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject review)throws Exception{return new JSONObject().put("taskId",task).put("windowId",review.getString("windowId")).put("reviewId",review.getString("reviewId"));}
        JSONObject refresh(JSONObject review)throws Exception{return owner.handle(nativeOwner,"refresh",action(review)).getJSONObject("snapshot");}
        JSONObject question(JSONObject review)throws Exception{return review.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0);}
        JSONObject answer(JSONObject review)throws Exception {JSONObject q=question(review),answers=q.getString("protocol").equals("codex_app_server")?new JSONObject().put("style",new JSONArray().put("Brief")):new JSONObject().put("q0",new JSONArray().put("A").put("B")).put("q1",new JSONArray().put("Custom name"));return owner.handle(nativeOwner,"answerQuestion",action(review).put("requestId",q.getString("requestId")).put("requestHash",q.getString("requestHash")).put("answers",answers));}
        JSONObject settled(JSONObject review)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){review=refresh(review);if(review.getJSONObject("source").optString("executorStatus").equals("completed")&&!tasks.hasUnresolved(task))return review;Thread.sleep(10);}throw new AssertionError(tasks.get(task,false));}
        int posts()throws Exception{return Files.exists(root.resolve("question-post-count"))?Integer.parseInt(Files.readString(root.resolve("question-post-count"))):0;}
        public void close()throws Exception {if(remote!=null)remote.close();if(host!=null)host.close();if(server!=null){server.destroy();if(!server.waitFor(3,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}}if(output!=null)output.close();}
    }
    @Test public void originalCodexQuestionActualProgressFrozenBytesThenSameTaskAndSessionContinue()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")){
            JSONObject review=f.open();assertTrue(review.toString(),review.getBoolean("canAnswerQuestions"));assertFalse(review.getJSONObject("result").getJSONObject("progress").getBoolean("liveStreaming"));String originalRun=f.tasks.get(f.task,false).getString("runId"),thread=review.getJSONObject("source").getString("threadId");JSONObject answered=f.answer(review);review=f.settled(answered.getJSONObject("snapshot"));assertEquals(1,f.posts());assertEquals("1",Files.readString(f.workspace.resolve("interaction-answer-count")));assertFalse(f.tasks.hasUnresolved(f.task));assertEquals(1,f.tasks.list().length());
            JSONObject captured=f.owner.handle(f.nativeOwner,"captureSnapshot",f.action(review).put("paths",new JSONArray().put("result.txt")));JSONObject saved=f.tasks.get(f.task,false).getJSONArray("remoteResultSnapshots").getJSONObject(0);review=f.refresh(captured.getJSONObject("snapshot"));JSONObject shown=f.owner.handle(f.nativeOwner,"readSnapshot",f.action(review).put("snapshotId",saved.getString("snapshotId")).put("path","result.txt")).getJSONObject("snapshot");assertEquals("ACTUAL FROZEN BYTES: Brief",shown.getJSONObject("snapshotFile").getString("text"));
            JSONObject continued=f.owner.handle(f.nativeOwner,"continue",f.action(shown).put("prompt","Continue exactly this original version").put("snapshotId",saved.getString("snapshotId")).put("snapshotVersion",saved.getString("snapshotVersion")));f.pending();review=f.refresh(continued.getJSONObject("snapshot"));assertNotEquals(originalRun,f.tasks.get(f.task,false).getString("runId"));assertEquals(thread,review.getJSONObject("source").getString("threadId"));review=f.settled(f.answer(review).getJSONObject("snapshot"));assertEquals(2,f.posts());assertEquals("11",Files.readString(f.workspace.resolve("interaction-turn-count")));assertEquals(1,f.tasks.list().length());
        }
    }
    @Test public void originalClaudeMultiSelectAndFreeTextReturnToExactPromptNotToolPermission()throws Exception {
        try(Fixture f=new Fixture("claude")){JSONObject review=f.open();assertEquals("claude_stream_json",f.question(review).getString("protocol"));assertEquals(0,review.getJSONObject("source").getJSONArray("pendingApprovals").length());review=f.settled(f.answer(review).getJSONObject("snapshot"));assertEquals(1,f.posts());assertEquals("exact_user_message_uuid",review.getJSONObject("source").getString("promptResultIdentity"));assertEquals("Actual fixture answered",review.getJSONObject("result").getString("output"));assertEquals("resolved",review.getJSONObject("source").getJSONArray("questionReceipts").getJSONObject(0).getString("status"));assertEquals("1",Files.readString(f.workspace.resolve("claude-question-answer")));}
    }
    @Test public void backgroundInvalidatesOldQuestionAndLostHttpAckSettlesFromOriginalGetAfterPhoneReopen()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")){JSONObject review=f.open();f.owner.setForeground(f.nativeOwner,false);JSONObject stale=review;assertThrows(SecurityException.class,()->f.answer(stale));assertEquals(0,f.posts());f.owner.setForeground(f.nativeOwner,true);review=f.refresh(review);Files.writeString(f.root.resolve("drop-next-question-ack"),"drop only after production job durable receipt");JSONObject unknown=f.answer(review);assertEquals(1,f.posts());f.owner.close(review.getString("windowId"));f.reopenPhone();JSONObject reopened=f.open();reopened=f.settled(reopened);assertFalse(f.tasks.hasUnresolved(f.task));assertEquals(1,f.posts());assertEquals("1",Files.readString(f.workspace.resolve("interaction-answer-count")));assertEquals(1,f.tasks.list().length());}
    }
    @Test public void originalUnclaimedSqlDispatchFenceCanBeReviewedAgainButForgedQuestionNeverDispatches()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")){JSONObject review=f.open(),source=review.getJSONObject("source"),q=f.question(review),answers=new JSONObject().put("style",new JSONArray().put("Brief"));assertThrows(SecurityException.class,()->f.tasks.claimRemoteQuestionAnswer(source,q.getString("requestId"),"0".repeat(64),answers));JSONObject prepared=f.tasks.claimRemoteQuestionAnswer(source,q.getString("requestId"),q.getString("requestHash"),answers);assertEquals("remote_question_prepared",prepared.getString("state"));assertEquals(0,f.posts());f.owner.close(review.getString("windowId"));f.reopenPhone();assertFalse(f.tasks.hasUnresolved(f.task));JSONObject reopened=f.open();reopened=f.settled(f.answer(reopened).getJSONObject("snapshot"));assertEquals(1,f.posts());assertEquals("1",Files.readString(f.workspace.resolve("interaction-answer-count")));}
    }
}
