package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class CapabilityApprovalRunBoundaryTest {
    private final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());
    private final JSONObject args=new JSONObject().put("path","reviewed.txt").put("content","exact bytes");
    private final JSONObject capability=new JSONObject().put("feature","workspace").put("command","write");
    public CapabilityApprovalRunBoundaryTest()throws Exception{}
    @org.junit.After public void close()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}

    @Test public void oldPrivatePendingCannotBeApprovedOrDeniedAfterANewRun()throws Exception {
        String id=tasks.createCapabilityTask("isolated original approval","").getString("id");
        JSONObject original=tasks.startAttempt(id,"capabilities",4);
        String approval=tasks.requireCapabilityApproval(id,original.getString("runId"),"workspace_write",args,capability).getString("approvalId");
        tasks.endAttempt(id,original.getString("runId"),"awaiting_user","original decision pending",new JSONObject());
        JSONObject successor=tasks.startAttempt(id,"capabilities",4);
        for(boolean allowed:new boolean[]{true,false})assertThrows(SecurityException.class,()->tasks.decideCapabilityApproval(id,approval,allowed));
        JSONObject actual=tasks.get(id,false);
        assertEquals(successor.getString("runId"),actual.getString("runId"));
        assertEquals(successor.getInt("coordinatorEpoch"),actual.getInt("coordinatorEpoch"));
        assertEquals("pending",actual.getJSONArray("approvals").getJSONObject(0).getString("state"));
        assertFalse(tasks.requireCapabilityApproval(id,successor.getString("runId"),"workspace_write",args,capability).getBoolean("approved"));
        assertEquals(2,tasks.get(id,false).getJSONArray("approvals").length());
    }

    @Test public void blankRunCannotBeTreatedAsAnOriginalRun()throws Exception {
        String id=tasks.createCapabilityTask("not started","").getString("id");
        String approval=tasks.requireCapabilityApproval(id,"","workspace_write",args,capability).getString("approvalId");
        assertThrows(SecurityException.class,()->tasks.decideCapabilityApproval(id,approval,true));
        assertThrows(SecurityException.class,()->tasks.decideCapabilityApproval(id,approval,false));
        assertEquals("pending",tasks.get(id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
        tasks.startAttempt(id,"capabilities",4);
        assertThrows(SecurityException.class,()->tasks.decideCapabilityApproval(id,approval,true));
    }

    @Test public void currentDecisionRemainsExactSingleUseAndDoesNotClearUnknownEffects()throws Exception {
        String id=tasks.createCapabilityTask("current exact approval","").getString("id");
        JSONObject original=tasks.startAttempt(id,"capabilities",4);String run=original.getString("runId");
        tasks.authorizeTools(id,run,new org.json.JSONArray().put("workspace_write"));
        RunControl control=new RunControl();control.bindHostRun(id,run,original.getInt("coordinatorEpoch"));
        String operation=tasks.journal(id,run,original.getInt("coordinatorEpoch"),control).beginOperation("workspace_write",args);
        String approval=tasks.requireCapabilityApproval(id,run,"workspace_write",args,capability).getString("approvalId");
        tasks.decideCapabilityApproval(id,approval,true);
        assertTrue(tasks.hasUnresolved(id));
        assertEquals(operation,tasks.get(id,false).getJSONArray("operations").getJSONObject(0).getString("operationId"));
        assertTrue(tasks.requireCapabilityApproval(id,run,"workspace_write",args,capability).getBoolean("approved"));
        assertFalse(tasks.requireCapabilityApproval(id,run,"workspace_write",args,capability).getBoolean("approved"));
        assertThrows(IllegalStateException.class,()->tasks.decideCapabilityApproval(id,approval,true));
        assertTrue(tasks.hasUnresolved(id));
    }
}
