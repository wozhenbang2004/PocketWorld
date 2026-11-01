package dev.pocketengine.features.groups;

import static org.junit.Assert.*;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual SQLite task/run/approval/operation recovery; native grants and journal inputs are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class GroupRecoveryTaskStoreTest {
    @Test public void sameTaskRecoveryPreservesCheckpointStepsAndSpentTokensAndFencesOldRun()throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("public.answer",8000);f.start();String old=f.attempt.getString("runId");
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","fixture original group history"));f.journal.checkpoint(messages,4,new JSONObject());
        f.store.reserveGroupModelTokens(f.request,old,1,7000,f.authority);f.store.endAttempt(f.request.taskId,old,"interrupted","fixture interrupted",new JSONObject());
        TaskStore restored=new TaskStore(RuntimeEnvironment.getApplication());JSONObject before=restored.groupTask(f.request,f.ownerRead);
        assertThrows(SecurityException.class,()->restored.startGroupAttempt(f.request,f.authority,8));
        JSONObject attempt=restored.recoverGroupAttempt(f.request,old,1,before.getLong("groupStateRevision"),f.authority,8);
        assertEquals(f.request.taskId,attempt.getString("id"));assertNotEquals(old,attempt.getString("runId"));assertEquals(2,attempt.getInt("coordinatorEpoch"));assertEquals(12,attempt.getInt("stepCeiling"));assertEquals(7000,attempt.getLong("groupReservedTokens"));assertEquals(1,restored.list().length());
        RunControl current=new RunControl();current.bindHostRun(f.request.taskId,attempt.getString("runId"),2);current.setAuthorityCheck(()->{f.authority.check();restored.checkGroupTaskControl(f.request,current);});
        RunJournal journal=restored.groupJournal(f.request,attempt.getString("runId"),2,current,f.authority);assertEquals(messages.toString(),journal.restore().getJSONArray("messages").toString());assertEquals(4,journal.restore().getInt("steps"));
        assertThrows(InterruptedException.class,f.journal::restore);assertThrows(IllegalStateException.class,()->restored.reserveGroupModelTokens(f.request,attempt.getString("runId"),2,1001,f.authority));
        restored.reserveGroupModelTokens(f.request,attempt.getString("runId"),2,1000,f.authority);assertEquals(8000,restored.groupTask(f.request,f.ownerRead).getLong("groupReservedTokens"));
    }
    @Test public void changedRevisionTerminalStateAndSavedStopRejectRecoveryAtomically()throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("public.answer",8000);f.start();String run=f.attempt.getString("runId");f.store.endAttempt(f.request.taskId,run,"interrupted","fixture interrupted",new JSONObject());long selected=f.store.get(f.request.taskId,false).getLong("groupStateRevision");
        f.store.status(f.request.taskId,"interrupted","fixture changed receipt");assertThrows(SecurityException.class,()->f.store.recoverGroupAttempt(f.request,run,1,selected,f.authority,8));
        for(String state:new String[]{"completed","cancelled","failed"}){f.store.status(f.request.taskId,state,"fixture terminal");long revision=f.store.get(f.request.taskId,false).getLong("groupStateRevision");assertThrows(SecurityException.class,()->f.store.recoverGroupAttempt(f.request,run,1,revision,f.authority,8));assertEquals(run,f.store.get(f.request.taskId,false).getString("runId"));}
        f.store.status(f.request.taskId,"interrupted","fixture interrupted after saved stop");f.store.cancelGroupTask(f.request,run,1,f.control,f.authority);JSONObject stopped=f.store.get(f.request.taskId,false);assertTrue(stopped.getBoolean("cancelRequested"));assertThrows(SecurityException.class,()->f.store.recoverGroupAttempt(f.request,run,1,stopped.getLong("groupStateRevision"),f.authority,8));
    }
    @Test public void unknownOrAlreadyEstablishedMediaOperationCannotBecomeANewPost()throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("media.image",32000);f.start();String operation=f.journal.beginOperation("group_media_image",new JSONObject());f.journal.finishOperation(operation,new JSONObject().put("code","media_unknown").put("accepted",JSONObject.NULL));
        TaskStore restored=new TaskStore(RuntimeEnvironment.getApplication());JSONObject original=restored.groupTask(f.request,f.ownerRead);assertEquals("reconciling",original.getString("status"));
        assertThrows(SecurityException.class,()->restored.recoverGroupAttempt(f.request,original.getString("runId"),1,original.getLong("groupStateRevision"),f.authority,8));
        restored.resolveOperation(f.request.taskId,operation,"not_occurred");restored.status(f.request.taskId,"interrupted","fixture native settlement");JSONObject settled=restored.groupTask(f.request,f.ownerRead);
        assertThrows(SecurityException.class,()->restored.recoverGroupAttempt(f.request,settled.getString("runId"),1,settled.getLong("groupStateRevision"),f.authority,8));assertEquals(1,settled.getJSONArray("operations").length());
    }
    @Test public void onlyApprovedExactOperationMovesToNewRunAndOldPendingCannotBeApproved()throws Exception {
        JSONObject args=new JSONObject().put("prompt","fixture exact image");GroupTaskStoreTest.Fixture approved=new GroupTaskStoreTest.Fixture("media.image",32000);approved.start();String old=approved.attempt.getString("runId");
        JSONObject permission=approved.store.requireCapabilityApproval(approved.request.taskId,old,"group_media_image",args);approved.store.decideCapabilityApproval(approved.request.taskId,permission.getString("approvalId"),true);approved.store.endAttempt(approved.request.taskId,old,"interrupted","fixture interrupted",new JSONObject());
        JSONObject before=approved.store.groupTask(approved.request,approved.ownerRead);JSONObject next=approved.store.recoverGroupAttempt(approved.request,old,1,before.getLong("groupStateRevision"),approved.authority,8);
        assertTrue(approved.store.requireCapabilityApproval(approved.request.taskId,next.getString("runId"),"group_media_image",args).getBoolean("approved"));
        GroupTaskStoreTest.Fixture pending=new GroupTaskStoreTest.Fixture("media.image",32000);pending.start();String prior=pending.attempt.getString("runId");JSONObject waiting=pending.store.requireCapabilityApproval(pending.request.taskId,prior,"group_media_image",args);pending.store.endAttempt(pending.request.taskId,prior,"interrupted","fixture interrupted",new JSONObject());
        JSONObject target=pending.store.groupTask(pending.request,pending.ownerRead);JSONObject recovered=pending.store.recoverGroupAttempt(pending.request,prior,1,target.getLong("groupStateRevision"),pending.authority,8);
        assertEquals("invalidated",recovered.getJSONArray("approvals").getJSONObject(0).getString("state"));assertThrows(SecurityException.class,()->pending.store.decideCapabilityApproval(pending.request.taskId,waiting.getString("approvalId"),true));
        JSONObject fresh=pending.store.requireCapabilityApproval(pending.request.taskId,recovered.getString("runId"),"group_media_image",args);assertFalse(fresh.getBoolean("approved"));assertNotEquals(waiting.getString("approvalId"),fresh.getString("approvalId"));
    }
    @Test public void originalQueuedTaskWithoutFirstRunRecoversAsItsFirstAttempt()throws Exception {
        GroupTaskStoreTest.Fixture f=new GroupTaskStoreTest.Fixture("public.answer",8000);TaskStore restored=new TaskStore(RuntimeEnvironment.getApplication());JSONObject task=restored.groupTask(f.request,f.ownerRead);assertEquals("interrupted",task.getString("status"));assertEquals("",task.optString("runId"));
        JSONObject first=restored.recoverGroupAttempt(f.request,"",0,task.getLong("groupStateRevision"),f.authority,8);assertEquals(f.request.taskId,first.getString("id"));assertEquals(1,first.getInt("coordinatorEpoch"));assertEquals(1,restored.list().length());
    }
}
