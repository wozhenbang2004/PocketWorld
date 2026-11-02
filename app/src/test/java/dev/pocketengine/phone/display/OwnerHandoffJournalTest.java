package dev.pocketengine.phone.display;

import dev.pocketengine.core.*;
import dev.pocketengine.store.TaskStore;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerHandoffJournalTest {
    @Test public void nativeOwnerTransferUsesOriginalRunAndBudgetButIsNotAModelTool()throws Exception {
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());try{String id=store.create("原副屏应用接管","background").getString("id");JSONObject task=store.startAttempt(id,"background",10);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl owner=new RunControl();owner.bindHostRun(id,run,epoch);RunJournal journal=store.journal(id,run,epoch,owner);JSONObject args=new JSONObject().put("action","main").put("handoffId","native-reviewed-pin").put("sessionId","same-display-session").put("displayId",9);
            assertThrows(SecurityException.class,()->journal.beginOperation("native_owner_phone_handoff",args));assertThrows(SecurityException.class,()->store.beginOwnerPhoneHandoff(id,owner,args));owner.pause();String op=store.beginOwnerPhoneHandoff(id,owner,args);assertTrue(store.hasUnresolved(id));assertThrows(SecurityException.class,()->store.beginOwnerPhoneHandoff(id,owner,args));assertThrows(SecurityException.class,()->store.resolveOperation(id,op,"occurred"));journal.finishOperation(op,new JSONObject().put("ok",true).put("accepted",true).put("state","main"));assertFalse(store.hasUnresolved(id));JSONObject after=store.get(id,false);assertEquals(run,after.getString("runId"));assertEquals(epoch,after.getInt("coordinatorEpoch"));assertEquals(1,after.getJSONArray("operations").length());assertEquals("native_owner_phone_handoff",after.getJSONArray("operations").getJSONObject(0).getString("tool"));owner.cancel();assertThrows(InterruptedException.class,()->store.beginOwnerPhoneHandoff(id,owner,args));
        }finally{close(store);}
    }
    @Test public void unboundReplacementControlCannotClaimOriginalPausedTask()throws Exception {TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());try{String id=store.create("原任务","background").getString("id");store.startAttempt(id,"background",10);RunControl other=new RunControl();other.pause();assertThrows(SecurityException.class,()->store.beginOwnerPhoneHandoff(id,other,new JSONObject().put("action","return").put("handoffId","pin")));}finally{close(store);}}
    private static void close(TaskStore store)throws Exception {java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(store)).close();}
}
