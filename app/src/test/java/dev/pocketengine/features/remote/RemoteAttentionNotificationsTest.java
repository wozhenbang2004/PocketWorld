package dev.pocketengine.features.remote;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteAttentionNotificationsTest {
    @Test public void nativeCompletionNoticeIsDeduplicatedAndClickOnlyRefreshesOriginalTask()throws Exception {
        try(RemoteOwnerWorkflowIntegrationTest.Fixture f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
            Application app=RuntimeEnvironment.getApplication();app.getApplicationInfo().icon=android.R.drawable.stat_notify_sync;NotificationManager manager=app.getSystemService(NotificationManager.class);
            RemoteAttentionNotifications notices=new RemoteAttentionNotifications(app,f.tasks,android.app.Activity.class);notices.observe(f.task);assertEquals(0,manager.getActiveNotifications().length);
            f.server.approval("job-1");f.remote.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","job-1"));
            f.tasks.setRemoteAttention(f.task,true);notices.observe(f.task);assertEquals(1,manager.getActiveNotifications().length);JSONObject saved=f.tasks.remoteAttentionState(f.task);assertEquals("posted_to_system",saved.getString("postState"));
            String fingerprint=saved.getString("fingerprint");long prepared=saved.getLong("preparedAt");notices.observe(f.task);assertEquals(prepared,f.tasks.remoteAttentionState(f.task).getLong("preparedAt"));assertEquals(1,f.server.submits.get());
            Intent intent=Shadows.shadowOf(manager.getActiveNotifications()[0].getNotification().contentIntent).getSavedIntent();List<JSONObject> opened=new ArrayList<>();assertTrue(RemoteAttentionNotifications.openRequestedTask(f.tasks,new Intent(intent),opened::add));assertEquals(f.task,opened.get(0).getString("taskId"));assertTrue(opened.get(0).getBoolean("currentRequestMustBeReadAgain"));assertEquals(1,f.server.submits.get());
            f.server.jobs.get("job-1").put("status","completed").put("pendingApprovals",new org.json.JSONArray());f.remote.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","job-1"));notices.observe(f.task);assertNotEquals(fingerprint,f.tasks.remoteAttentionState(f.task).getString("fingerprint"));
            List<JSONObject> stale=new ArrayList<>();RemoteAttentionNotifications.openRequestedTask(f.tasks,new Intent(intent),stale::add);assertEquals(1,stale.size());assertNotEquals(stale.get(0).getJSONObject("original").getString("fingerprint"),stale.get(0).getJSONObject("current").getString("fingerprint"));assertEquals(0,f.server.decisions.get());assertEquals(1,f.server.submits.get());
            JSONObject source=f.tasks.remoteOwnerTarget(f.task);assertEquals("completed",source.getString("executorStatus"));assertFalse(manager.getActiveNotifications()[0].getNotification().extras.toString().contains("untrusted tool input"));
            f.tasks.setRemoteAttention(f.task,false);notices.observe(f.task);assertEquals(0,manager.getActiveNotifications().length);
        }
    }
    @Test public void forgedNotificationCannotFindOrApproveARequestAndUnconfirmedPostIsNotAutomaticallyReplayed()throws Exception {
        try(RemoteOwnerWorkflowIntegrationTest.Fixture f=new RemoteOwnerWorkflowIntegrationTest.Fixture("codex_app_server")){
            f.tasks.setRemoteAttention(f.task,true);var claim=f.tasks.claimRemoteAttention(f.task);assertNotNull(claim);assertNull(f.tasks.claimRemoteAttention(f.task));f.tasks.recordRemoteAttention(claim,"unknown");assertNull(f.tasks.claimRemoteAttention(f.task));
            List<JSONObject> opened=new ArrayList<>();Intent forged=new Intent(RemoteAttentionNotifications.ACTION).putExtra("remoteAttentionTask",f.task).putExtra("remoteAttentionFingerprint","a".repeat(64));RemoteAttentionNotifications.openRequestedTask(f.tasks,forged,opened::add);assertTrue(opened.isEmpty());assertEquals(1,f.server.submits.get());assertEquals(0,f.server.decisions.get());
            f.tasks.setRemoteAttention(f.task,true);assertNotNull(f.tasks.claimRemoteAttention(f.task)); // explicit native re-review, not an automatic notification retry
        }
    }
}
