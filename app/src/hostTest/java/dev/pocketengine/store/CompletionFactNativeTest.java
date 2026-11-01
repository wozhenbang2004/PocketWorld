package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.memory.SourceRef;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class CompletionFactNativeTest {
    private static final String H="a".repeat(64),T="b".repeat(64);
    private static final class Flow {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final String task,run;final int epoch;
        Flow(boolean confirmed)throws Exception{
            task=tasks.create("隔离示例：核对原完成事实").getString("id");JSONObject attempt=tasks.startAttempt(task,"main_screen",4);
            run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");
            tasks.endAttempt(task,run,"needs_confirmation","原实际结果摘要",new JSONObject().put("automaticallyVerified",false));
            if(confirmed)tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));
            tasks.status(task,"completed",confirmed?"主人已确认":"仅状态，不应铸造证明");
        }
    }
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static long factEvents(TaskStore tasks,String task)throws Exception{try(Cursor c=db(tasks).rawQuery("SELECT COUNT(*) FROM task_events WHERE task_id=? AND event_type='native_completion_fact'",new String[]{task})){c.moveToFirst();return c.getLong(0);}}

    @Test public void onlyCurrentNativeCompletionCanIssueAndColdRestoreDefensiveFact()throws Exception{
        Flow f=new Flow(true);TaskStore.CompletionFact fact=f.tasks.completionFactForOwner(f.task);JSONObject data=fact.metadata();
        assertEquals(f.run,data.getString("runId"));assertEquals(f.epoch,data.getInt("epoch"));assertEquals("原实际结果摘要",data.getString("summary"));
        assertEquals(0,data.getJSONArray("artifactRefs").length());assertFalse(data.getBoolean("audienceAuthorized"));assertFalse(data.getBoolean("businessSuccessIndependentlyVerified"));
        data.put("summary","forged");data.getJSONObject("completionBasis").put("eventSeq",0);fact.checkCurrent();
        assertEquals(fact.factId,f.tasks.completionFactForOwner(f.task).factId);assertEquals(1,factEvents(f.tasks,f.task));
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());TaskStore.CompletionFact restored=reopened.originalCompletionFact(fact.factId);
        assertEquals(fact.completionFingerprint,restored.completionFingerprint);assertEquals("原实际结果摘要",restored.metadata().getString("summary"));
        assertThrows(SecurityException.class,()->reopened.recordCompletionOutbox(fact,"outbox-1",H,T));
    }
    @Test public void completedLabelAloneAndHistoricalConfirmationCannotAuthorizeDelivery()throws Exception{
        Flow unconfirmed=new Flow(false);assertThrows(SecurityException.class,()->unconfirmed.tasks.completionFactForOwner(unconfirmed.task));assertEquals(0,factEvents(unconfirmed.tasks,unconfirmed.task));
        Flow f=new Flow(true);TaskStore.CompletionFact old=f.tasks.completionFactForOwner(f.task);
        JSONObject next=f.tasks.startAttempt(f.task,"main_screen",4);f.tasks.endAttempt(f.task,next.getString("runId"),"needs_confirmation","新轮尚待确认",new JSONObject());f.tasks.status(f.task,"completed","只有状态");
        assertThrows(SecurityException.class,old::checkCurrent);assertThrows(SecurityException.class,()->f.tasks.completionFactForOwner(f.task));assertNull(f.tasks.completionOutboxLink(old.factId));
    }
    @Test public void forgetOrExactEventDeletionPermanentlyInvalidatesOldCarrier()throws Exception{
        Flow f=new Flow(true);TaskStore.CompletionFact fact=f.tasks.completionFactForOwner(f.task);long verification=fact.metadata().getJSONObject("completionBasis").getLong("eventSeq");
        f.tasks.forgetMemorySourceSnapshot(new SourceRef("task_evidence","task:"+f.task+":verified_result",verification,"owner","private"));
        assertThrows(SecurityException.class,fact::checkCurrent);assertThrows(SecurityException.class,()->f.tasks.originalCompletionFact(fact.factId));
        Flow other=new Flow(true);TaskStore.CompletionFact deleted=other.tasks.completionFactForOwner(other.task);
        assertEquals(1,db(other.tasks).delete("task_events","task_id=? AND event_order=?",new String[]{other.task,Long.toString(deleted.eventSeq)}));
        assertThrows(SecurityException.class,deleted::checkCurrent);assertThrows(SecurityException.class,()->other.tasks.completionFactForOwner(other.task));
    }
    @Test public void onlyDurableOriginalOutboxAssociationIsStoredAndNeverAutoReplaced()throws Exception{
        Flow f=new Flow(true);TaskStore.CompletionFact fact=f.tasks.completionFactForOwner(f.task);
        JSONObject link=f.tasks.recordCompletionOutbox(fact,"original-outbox-1",H,T);assertEquals("original-outbox-1",link.getString("outboxId"));assertFalse(link.has("sent"));assertFalse(link.has("read"));
        assertEquals(link.toString(),f.tasks.recordCompletionOutbox(fact,"original-outbox-1",H,T).toString());
        assertThrows(SecurityException.class,()->f.tasks.recordCompletionOutbox(fact,"other-outbox",H,T));
        assertThrows(SecurityException.class,()->f.tasks.recordCompletionOutbox(fact,"original-outbox-1",T,T));
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());assertEquals("original-outbox-1",reopened.completionOutboxLink(fact.factId).getString("outboxId"));
        assertEquals(0,reopened.get(f.task,false).getJSONArray("operations").length());
    }
    @Test public void failedOriginalPointerTransactionReturnsNoCarrierAndNoOrphanFact()throws Exception{
        Flow f=new Flow(true);SQLiteDatabase sql=db(f.tasks);long before=factEvents(f.tasks,f.task);
        sql.execSQL("CREATE TEMP TRIGGER completion_fact_fixture_abort BEFORE INSERT ON meta WHEN NEW.key LIKE 'completion.fact.%' BEGIN SELECT RAISE(ABORT,'fixture pointer persistence refused'); END");
        try{assertThrows(Exception.class,()->f.tasks.completionFactForOwner(f.task));assertEquals(before,factEvents(f.tasks,f.task));}
        finally{sql.execSQL("DROP TRIGGER completion_fact_fixture_abort");}
        assertNotNull(f.tasks.completionFactForOwner(f.task));assertEquals(before+1,factEvents(f.tasks,f.task));
    }
}
