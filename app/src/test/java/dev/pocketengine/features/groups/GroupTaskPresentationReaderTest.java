package dev.pocketengine.features.groups;

import static org.junit.Assert.*;

import android.app.Application;
import dev.pocketengine.features.groups.host.GroupArtifactStore;
import dev.pocketengine.features.groups.host.GroupTaskPresentationReader;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual SQLite tasks, approvals and operation journal; native authority/model events are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class GroupTaskPresentationReaderTest {
    @Test public void projectsActualIdentityAndCurrentRunProgressWithoutInventingCompletionFraction() throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("public.answer",8000);
        GroupTaskPresentationReader reader=new GroupTaskPresentationReader(f.store);
        JSONObject queued=reader.read(f.request,f.ownerRead);assertEquals("queued",queued.getString("status"));
        assertEquals(f.request.taskId,queued.getString("taskId"));assertEquals(f.request.spaceId,queued.getString("spaceId"));
        assertFalse(queued.getJSONObject("progress").has("totalSteps"));f.start();String first=f.attempt.getString("runId");
        f.store.event(f.request.taskId,first,1,new JSONObject().put("type","model").put("status","running").put("runId",first).put("coordinatorEpoch",1));
        JSONObject running=reader.read(f.request,f.ownerRead);assertEquals("planning",running.getJSONObject("progress").getString("phase"));
        assertEquals(f.store.get(f.request.taskId,false).getLong("groupStateRevision"),running.getLong("taskRevision"));
        f.store.endAttempt(f.request.taskId,first,"paused","fixture pause",new JSONObject());f.start();
        JSONObject resumed=reader.read(f.request,f.ownerRead);assertEquals(2,resumed.getInt("coordinatorEpoch"));
        assertEquals("",resumed.getJSONObject("progress").getString("phase"));assertFalse(resumed.getJSONObject("progress").has("completedSteps"));
    }
    @Test public void pendingDeniedAndExpiredApprovalRemainDifferentFromResultConfirmation() throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("media.image",32000);f.start();String run=f.attempt.getString("runId");
        JSONObject approval=f.store.requireCapabilityApproval(f.request.taskId,run,"group_media_image",new JSONObject().put("prompt","fixture image"));
        GroupTaskPresentationReader reader=new GroupTaskPresentationReader(f.store);
        assertEquals("pending",reader.read(f.request,f.ownerRead).getJSONObject("approval").getString("state"));
        GroupTaskPresentationReader later=new GroupTaskPresentationReader(f.store,()->System.currentTimeMillis()+360_000);
        assertEquals("required",later.read(f.request,f.ownerRead).getJSONObject("approval").getString("state"));
        f.store.decideCapabilityApproval(f.request.taskId,approval.getString("approvalId"),false);
        assertEquals("denied",reader.read(f.request,f.ownerRead).getJSONObject("approval").getString("state"));
        GroupTaskStoreTest.Fixture plain=new GroupTaskStoreTest.Fixture("public.answer",8000);plain.start();
        plain.store.endAttempt(plain.request.taskId,plain.attempt.getString("runId"),"needs_confirmation","fixture result to verify",new JSONObject());
        JSONObject confirmation=new GroupTaskPresentationReader(plain.store).read(plain.request,plain.ownerRead);
        assertEquals("needs_confirmation",confirmation.getString("status"));assertEquals("not_required",confirmation.getJSONObject("approval").getString("state"));
    }
    @Test public void restartAndUnknownEffectsStayOnOriginalTaskAndCannotOfferExecution() throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("media.image",32000);f.start();
        String operation=f.journal.beginOperation("group_media_image",new JSONObject());
        f.journal.finishOperation(operation,new JSONObject().put("code","media_unknown").put("accepted",JSONObject.NULL));
        TaskStore recovered=new TaskStore(RuntimeEnvironment.getApplication());
        JSONObject value=new GroupTaskPresentationReader(recovered).read(f.request,f.ownerRead);
        assertEquals(f.request.taskId,value.getString("taskId"));assertEquals("reconciling",value.getString("status"));
        assertFalse(value.getJSONObject("recovery").getBoolean("canReviewContinue"));assertEquals(1,recovered.list().length());
    }
    @Test public void artifactSourceRunIsOriginalOperationRunAfterTaskContinues() throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("group.files",16000);f.start();String originalRun=f.attempt.getString("runId");
        String operation=f.journal.beginOperation("group_file_read",new JSONObject());
        GroupArtifactStore artifacts=new GroupArtifactStore(RuntimeEnvironment.getApplication().getFilesDir());
        JSONObject artifact=artifacts.importOutput(f.request,operation,"application/pdf",new ByteArrayInputStream("fixture PDF bytes".getBytes(StandardCharsets.UTF_8)),f.authority);
        f.journal.finishOperation(operation,new JSONObject().put("ok",true).put("groupArtifacts",new JSONArray().put(artifact)));
        f.store.endAttempt(f.request.taskId,originalRun,"paused","fixture pause",new JSONObject());f.start();
        JSONObject projection=new GroupTaskPresentationReader(f.store).read(f.request,f.ownerRead),hint=projection.getJSONArray("artifactHints").getJSONObject(0);
        assertNotEquals(originalRun,projection.getString("runId"));assertEquals(originalRun,hint.getString("sourceTaskRunId"));
        assertEquals(operation,hint.getString("sourceOperationId"));assertEquals(artifact.getString("artifactId"),hint.getString("outputId"));
        assertFalse(hint.has("sourceCoordinatorEpoch"));assertFalse(hint.has("sourceJobId"));
    }
    @Test public void wrongScopeAndReaderRevocationCannotReturnPresentation() throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("public.answer",8000);GroupTaskPresentationReader reader=new GroupTaskPresentationReader(f.store);
        GroupHostPort.Request changed=new GroupHostPort.Request(new JSONObject(f.source.toString()).put("goal","other goal"));
        assertThrows(SecurityException.class,()->reader.read(changed,f.ownerRead));
        AtomicInteger checks=new AtomicInteger();GroupHostPort.Authority revoked=new GroupHostPort.Authority() {
            public void check(){throw new SecurityException("fixture read-only owner");}
            public void checkRead(){if(checks.incrementAndGet()>=4)throw new SecurityException("fixture owner revoked during read");}
        };
        assertThrows(SecurityException.class,()->reader.read(f.request,revoked));assertEquals(4,checks.get());
    }
}
