package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.features.remote.RemoteInputOwnerJourneyTest.Fixture;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original SQL/Book/typed approval/raw HTTP/same session. CLIs remain controlled peers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteInputContinuationOwnerJourneyTest {
    private static JSONObject openContinuation(Fixture f)throws Exception {
        if(f.window!=null)f.owner.close(f.window);f.window=UUID.randomUUID().toString();JSONObject opened=f.owner.handle(f.nativeOwner,"openContinuationInputs",new JSONObject().put("windowId",f.window).put("taskId",f.task)).getJSONObject("snapshot");assertFalse(opened.getBoolean("registered"));assertTrue(opened.getBoolean("continuation"));f.action("setContinuationInput",new JSONObject().put("prompt","Use only these NEW exact files as the new requirement in this original session").put("timeoutSeconds",30));return opened;
    }
    private static void sameTaskResult(Fixture f,JSONObject before,JSONObject file,int expectedHistory)throws Exception {
        f.settled();JSONObject after=f.tasks.remoteOwnerTarget(f.task),scope=f.tasks.originalRemoteInputScope(f.task);assertEquals(before.getString("taskId"),after.getString("taskId"));assertNotEquals(before.getString("runId"),after.getString("runId"));assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));assertNotEquals(before.getString("jobId"),after.getString("jobId"));assertEquals(before.getString("threadId"),after.getString("threadId"));assertEquals(before.getString("jobId"),scope.getJSONObject("request").getString("resumeJobId"));assertEquals(after.getString("jobOperationId"),scope.getString("jobOperationId"));assertEquals(expectedHistory,f.tasks.get(f.task,false).optJSONArray("remoteInputHistory")==null?0:f.tasks.get(f.task,false).getJSONArray("remoteInputHistory").length());
        JSONObject job=f.job();assertEquals(before.getString("jobId"),job.getString("resumeJobId"));assertEquals(file.getString("sha256"),job.getJSONObject("inputState").getJSONArray("files").getJSONObject(0).getString("actualSha256"));assertEquals(RemoteTaskRequest.INPUT_CONTINUATION_PROTOCOL,job.getJSONObject("inputState").getString("continuationProtocol"));
        byte[] actual=Files.readAllBytes(f.workspace.resolve(job.getJSONObject("inputState").getJSONArray("files").getJSONObject(0).getString("path")));assertArrayEquals(f.attachments.book.readVerifiedSnapshot(file.getString("id"),file.getString("sha256"),new dev.pocketengine.core.RunControl()),actual);
    }
    @Test public void newSelectedBytesContinueBothOriginalSessionsAndArchiveOriginalInputScopeWithoutASecondTask()throws Exception {
        try(Fixture f=new Fixture("input_continuation_fixture_server.py")){
            JSONObject first=f.file("最初原件.bin",new byte[]{0,1,(byte)255},new JSONObject().put("kind","native_fixture_private")),next=f.file("后续原件.bin",new byte[]{(byte)128,0,5,(byte)255},new JSONObject().put("kind","native_fixture_private"));
            int tasks=0;
            for(String worker:new String[]{"codex_app_server","claude"}){
                f.open(worker);f.confirm(f.select(first));f.settled();JSONObject before=f.tasks.remoteOwnerTarget(f.task),oldScope=f.tasks.originalRemoteInputScope(f.task);int posts=f.count("submit-count"),puts=f.count("put-count");String oldJob=before.getString("jobId");
                openContinuation(f);JSONObject review=f.select(next);assertEquals(oldJob,f.tasks.remoteOwnerTarget(f.task).getString("jobId"));assertEquals(posts,f.count("submit-count"));f.confirm(review);sameTaskResult(f,before,next,1);assertEquals(++tasks,f.tasks.list().length());assertEquals(posts+1,f.count("submit-count"));assertEquals(puts+1,f.count("put-count"));assertEquals(oldScope.toString(),f.tasks.get(f.task,false).getJSONArray("remoteInputHistory").getJSONObject(0).getJSONObject("scope").toString());
                int settledPosts=f.count("submit-count"),settledPuts=f.count("put-count");f.query();assertEquals(settledPosts,f.count("submit-count"));assertEquals(settledPuts,f.count("put-count"));
            }
        }
    }
    @Test public void ordinaryOriginalTextTaskCanReceiveExplicitFollowupBytesAndRevokedReviewCannotSendThem()throws Exception {
        try(Fixture f=new Fixture("input_continuation_fixture_server.py")){
            JSONObject file=f.file("new-private.bin",new byte[]{9,0,(byte)255},new JSONObject().put("kind","native_fixture_private"));String request=UUID.randomUUID().toString();f.host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","codex_app_server").put("prompt","Original ordinary task").put("timeoutSeconds",30)));JSONObject admitted=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(admitted==null&&System.nanoTime()<until){for(JSONObject event:f.events)if(request.equals(event.optString("requestId")))admitted=event;Thread.sleep(5);}assertNotNull(admitted);assertTrue(admitted.toString(),admitted.getBoolean("ok"));f.task=admitted.getJSONObject("result").getString("hostTaskId");
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject source=f.tasks.remoteOwnerTarget(f.task);f.remote.handle("getJob",source);if(f.tasks.remoteOwnerTarget(f.task).optString("executorStatus").equals("completed"))break;Thread.sleep(5);}JSONObject before=f.tasks.remoteOwnerTarget(f.task);assertEquals("completed",before.getString("executorStatus"));assertFalse(f.tasks.get(f.task,false).has("remoteInputScope"));openContinuation(f);JSONObject review=f.select(file);f.owner.setForeground(f.nativeOwner,false);assertThrows(SecurityException.class,()->f.confirm(review));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("put-count"));f.owner.setForeground(f.nativeOwner,true);f.action("reviewInputs",new JSONObject());JSONObject current=f.action("inspectInputs",new JSONObject());f.confirm(current);sameTaskResult(f,before,file,0);assertEquals(1,f.tasks.list().length());assertEquals(2,f.count("submit-count"));assertEquals(1,f.count("put-count"));
        }
    }
    @Test public void lostFollowupBodyAckQueriesExactSlotWithoutRecreateOrRetransmitAndOldCapabilityCannotBeAssumed()throws Exception {
        try(Fixture f=new Fixture("input_continuation_fixture_server.py")){
            JSONObject first=f.file("first.bin",new byte[]{1,2},new JSONObject().put("kind","native_fixture_private")),next=f.file("next.bin",new byte[]{0,(byte)255,8},new JSONObject().put("kind","native_fixture_private"));f.open("codex_app_server");f.confirm(f.select(first));f.settled();JSONObject before=f.tasks.remoteOwnerTarget(f.task);openContinuation(f);JSONObject review=f.select(next);Files.writeString(f.root.resolve("drop-next-upload-ack"),"controlled lost response");f.confirm(review);f.query();JSONObject state=f.tasks.get(f.task,false).getJSONObject("remoteInputState");assertTrue(state.optString("phase").matches("ready|starting|running|completed"));assertEquals(2,f.count("submit-count"));assertEquals(2,f.count("put-count"));if(!state.optBoolean("workerStarted")){JSONObject again=f.action("reviewInputs",new JSONObject());f.confirm(again);}sameTaskResult(f,before,next,1);assertEquals(2,f.count("submit-count"));assertEquals(2,f.count("put-count"));JSONObject old=new JSONObject(f.remote.handle("environments",new JSONObject().put("profileId","computer")).getJSONArray("environments").getJSONObject(0).toString());old.getJSONObject("privateInputCapabilities").remove("continuationProtocol");assertThrows(SecurityException.class,()->RemoteTaskRequest.requireInputContinuationCapability(old,"codex_app_server"));
        }
    }
}
