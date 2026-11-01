package dev.pocketengine.store;

import android.app.Application;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskExternalStateTest {
    private String create(TaskStore tasks,String feature)throws Exception {String id=UUID.randomUUID().toString();tasks.beginExternal(feature,"start",id,new JSONObject().put("goal","目标"));return id;}
    @Test public void lateAdmissionAndOlderReceiptCannotOverwriteTerminalOrOwnerConfirmation()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=create(tasks,"teams");
        tasks.updateExternal(id,new JSONObject().put("id",id).put("status","completed").put("summary","真实分析结果").put("updatedAt",200));
        tasks.updateExternal(id,new JSONObject().put("id",id).put("status","queued"));
        assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));assertEquals("真实分析结果",tasks.get(id,false).getString("summary"));
        tasks.updateExternal(id,new JSONObject().put("id",id).put("status","failed").put("updatedAt",100));assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));
        tasks.status(id,"completed","主人确认");tasks.updateExternal(id,new JSONObject().put("id",id).put("status","running").put("updatedAt",300));
        assertEquals("completed",tasks.get(id,false).getString("status"));assertEquals("主人确认",tasks.get(id,false).getString("summary"));
    }
    @Test public void remoteNotFoundRemainsUncertainAndEmptyIdentifiersNeverMatchAnotherTask()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String first=create(tasks,"remote"),second=create(tasks,"remote");
        assertNull(tasks.externalTask("remote",""));assertNull(tasks.operationTask("remote",""));assertEquals(first,tasks.operationTask("remote",first));
        tasks.updateExternal(first,new JSONObject().put("operationId",first).put("status","not_found"));
        assertEquals("reconciling",tasks.get(first,false).getString("status"));assertEquals("queued",tasks.get(second,false).getString("status"));
    }
    @Test public void browserTakeoverAndExpiryHaveExplicitNonRunningStates()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String taken=create(tasks,"browser"),expired=create(tasks,"browser");
        tasks.updateExternal(taken,new JSONObject().put("status","taken_over"));assertEquals("cancelled",tasks.get(taken,false).getString("status"));
        tasks.updateExternal(expired,new JSONObject().put("status","expired"));assertEquals("awaiting_user",tasks.get(expired,false).getString("status"));
    }
    @Test public void inactivePluginCancelDoesNotFalsifyAnUnknownRequest()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=create(tasks,"plugins");
        tasks.updateExternal(id,new JSONObject().put("status","unknown"));tasks.updateExternal(id,new JSONObject().put("status","stopping"));tasks.updateExternal(id,new JSONObject().put("status","not_active"));
        assertEquals("reconciling",tasks.get(id,false).getString("status"));
    }
    @Test public void restartUsesExecutorSpecificRecoveryInsteadOfPhoneResumeForEveryRunId()throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(context);
        String remote=create(tasks,"remote"),browser=create(tasks,"browser"),teams=create(tasks,"teams"),plugin=create(tasks,"plugins");
        tasks.updateExternal(remote,new JSONObject().put("status","running"));tasks.updateExternal(teams,new JSONObject().put("status","running"));
        TaskStore recovered=new TaskStore(context);assertEquals("reconciling",recovered.get(remote,false).getString("status"));assertEquals("awaiting_user",recovered.get(browser,false).getString("status"));assertEquals("interrupted",recovered.get(teams,false).getString("status"));assertEquals("reconciling",recovered.get(plugin,false).getString("status"));
        assertFalse(recovered.get(remote,false).getString("summary").contains("可继续原任务"));
    }
    @Test public void browserResumesSameTaskWithFreshRunAndRejectsUnresolvedOrOldRunReceipts()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=create(tasks,"browser");tasks.feedback(id,"保留这个补充");tasks.updateExternal(id,new JSONObject().put("status","running").put("runId",id));
        String operation=tasks.beginCapabilityOperation(id,id,"browser","click",new JSONObject());tasks.finishCapabilityOperation(id,operation,new JSONObject().put("status","unknown"));
        try{tasks.resumeExternalAttempt(id,"browser");fail("unknown side effects require reconciliation before resume");}catch(IllegalStateException expected){}
        tasks.resolveOperation(id,operation,"not_occurred");JSONObject resumed=tasks.resumeExternalAttempt(id,"browser");String fresh=resumed.getString("runId");
        assertEquals(id,resumed.getString("id"));assertNotEquals(id,fresh);assertEquals(1,resumed.getInt("coordinatorEpoch"));assertEquals("目标",resumed.getString("goal"));assertEquals("保留这个补充",resumed.getString("feedback"));assertEquals(1,tasks.list().length());
        tasks.updateExternal(id,new JSONObject().put("status","running").put("runId",fresh));
        tasks.updateExternal(id,new JSONObject().put("status","taken_over").put("runId",id));tasks.finishCapabilityOperation(id,operation,new JSONObject().put("status","unknown"));
        assertEquals("running",tasks.get(id,false).getString("status"));assertEquals("resolved",tasks.get(id,false).getJSONArray("operations").getJSONObject(0).getString("state"));
    }
}
