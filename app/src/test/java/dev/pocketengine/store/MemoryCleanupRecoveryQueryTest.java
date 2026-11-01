package dev.pocketengine.store;

import android.app.Application;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class MemoryCleanupRecoveryQueryTest {
    private TaskStore store(){return new TaskStore(RuntimeEnvironment.getApplication());}
    private String cleanup(TaskStore store,String status)throws Exception{
        String id=UUID.randomUUID().toString();store.beginExternal("memory","sourceCleanup",id,new JSONObject());store.status(id,status,"来源派生清理");return id;
    }
    @Test public void unfinishedReceiptOlderThanRecentTwoHundredTasksIsStillRecovered()throws Exception{
        TaskStore store=store();String oldest=cleanup(store,"queued");
        // Control only this fixture's historical sort time. Robolectric uptime
        // advancement does not reliably advance System.currentTimeMillis().
        java.lang.reflect.Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);
        ((android.database.sqlite.SQLiteDatabase)database.get(store)).execSQL("UPDATE tasks SET updated_at=0 WHERE task_id=?",new Object[]{oldest});
        for(int i=0;i<205;i++)store.create("合成近期任务 "+i);
        assertEquals(200,store.list().length());assertFalse(store.list().toString().contains(oldest));
        JSONArray rows=store.memoryCleanupTasks();assertEquals(1,rows.length());assertEquals(oldest,rows.getJSONObject(0).getString("id"));
    }
    @Test public void recoveryReturnsOriginalOperationArgumentsAndReceipts()throws Exception{
        TaskStore store=store();String id=cleanup(store,"running");String operation=store.beginCapabilityOperation(id,id,"memory","sourceCleanup",new JSONObject().put("removedRefs",new JSONArray().put(new JSONObject().put("recordId","original-record").put("revision",1))));
        JSONArray rows=store.memoryCleanupTasks();JSONObject journal=rows.getJSONObject(0).getJSONArray("operations").getJSONObject(0);
        assertEquals(operation,journal.getString("operationId"));assertEquals("original-record",journal.getJSONObject("arguments").getJSONArray("removedRefs").getJSONObject(0).getString("recordId"));assertEquals("prepared",journal.getString("state"));
        store.finishCapabilityOperation(id,operation,new JSONObject().put("status","unknown"));assertEquals("unknown",store.memoryCleanupTasks().getJSONObject(0).getJSONArray("operations").getJSONObject(0).getString("state"));
    }
    @Test public void queryExcludesTerminalAndUnrelatedButRetainsInterruptedOrBlockedTasks()throws Exception{
        TaskStore store=store();for(String status:new String[]{"completed","failed","cancelled"})cleanup(store,status);
        String interrupted=cleanup(store,"interrupted"),blocked=cleanup(store,"reconciling");
        store.beginExternal("skills","sourceCleanup",UUID.randomUUID().toString(),new JSONObject());store.beginExternal("memory","other",UUID.randomUUID().toString(),new JSONObject());
        JSONArray rows=store.memoryCleanupTasks();assertEquals(2,rows.length());assertTrue(rows.toString().contains(interrupted));assertTrue(rows.toString().contains(blocked));
    }
}
