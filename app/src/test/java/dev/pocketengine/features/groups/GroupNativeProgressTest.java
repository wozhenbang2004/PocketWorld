package dev.pocketengine.features.groups;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.features.groups.host.*;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Production host reads real SQLite approval/operation rows; no model or platform request occurs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupNativeProgressTest {
    GroupTaskHost host(GroupTaskStoreTest.Fixture f)throws Exception {
        return new GroupTaskHost(f.store,Runnable::run,new ConcurrentHashMap<>(),()->null,
                new GroupSafeCapabilities.Services(new GroupArtifactStore(Files.createTempDirectory("native-progress-").toFile()),null,null),id->{},error->{});
    }
    @Test public void actualPendingAndDeniedApprovalAreDifferentFromModelSummary()throws Exception {
        var f=new GroupTaskStoreTest.Fixture("media.image",8000);f.start();
        JSONObject required=f.store.requireCapabilityApproval(f.request.taskId,f.attempt.getString("runId"),"group_media_image",new JSONObject().put("prompt","one image"),new JSONObject().put("kind","native_group_media"));
        try(var host=host(f)){
            GroupHostPort.Progress pending=host.inspectProgress(f.request,f.ownerRead);assertEquals("pending",pending.approvalState);assertEquals(f.attempt.getString("runId"),pending.task.runId);assertFalse(pending.unresolved);
            f.store.decideCapabilityApproval(f.request.taskId,required.getString("approvalId"),false);GroupHostPort.Progress denied=host.inspectProgress(f.request,f.ownerRead);assertEquals("denied",denied.approvalState);assertEquals(f.request.taskId,denied.task.taskId);assertTrue(denied.task.revision>pending.task.revision);
        }
    }
    @Test public void actualUnknownEffectAndApprovedResultConfirmationStaySeparate()throws Exception {
        var f=new GroupTaskStoreTest.Fixture("media.image",8000);f.start();JSONObject required=f.store.requireCapabilityApproval(f.request.taskId,f.attempt.getString("runId"),"group_media_image",new JSONObject().put("prompt","one image"),new JSONObject().put("kind","native_group_media"));f.store.decideCapabilityApproval(f.request.taskId,required.getString("approvalId"),true);
        try(var host=host(f)){
            String operation=f.journal.beginOperation("group_media_image",new JSONObject().put("prompt","one image"));assertTrue(host.inspectProgress(f.request,f.ownerRead).unresolved);
            f.journal.finishOperation(operation,new JSONObject().put("ok",true).put("status","response_received"));f.store.endAttempt(f.request.taskId,f.attempt.getString("runId"),"needs_confirmation","actual execution awaits owner verification",new JSONObject());
            GroupHostPort.Progress confirm=host.inspectProgress(f.request,f.ownerRead);assertEquals("approved",confirm.approvalState);assertEquals("needs_confirmation",confirm.task.status);assertFalse(confirm.unresolved);
        }
    }
    @Test public void expiredSavedApprovalCannotBePresentedAsActionablePending()throws Exception {
        var f=new GroupTaskStoreTest.Fixture("media.image",8000);f.start();JSONObject required=f.store.requireCapabilityApproval(f.request.taskId,f.attempt.getString("runId"),"group_media_image",new JSONObject().put("prompt","one image"),new JSONObject().put("kind","native_group_media"));
        // Isolated SQLite time fixture: age only this actual saved approval, never a production grant.
        var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);android.database.sqlite.SQLiteDatabase database=(android.database.sqlite.SQLiteDatabase)field.get(f.store);
        JSONObject saved;try(android.database.Cursor cursor=database.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{f.request.taskId})){assertTrue(cursor.moveToFirst());saved=new JSONObject(cursor.getString(0));}
        saved.getJSONArray("approvals").getJSONObject(0).put("expiresAt",1L);android.content.ContentValues row=new android.content.ContentValues();row.put("document",saved.toString());assertEquals(1,database.update("tasks",row,"task_id=?",new String[]{f.request.taskId}));
        try(var host=host(f)){assertEquals("expired",host.inspectProgress(f.request,f.ownerRead).approvalState);}
        assertThrows(IllegalStateException.class,()->f.store.decideCapabilityApproval(f.request.taskId,required.getString("approvalId"),true));
    }
}
