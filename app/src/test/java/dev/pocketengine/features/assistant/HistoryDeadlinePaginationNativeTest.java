package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQLite, deterministic last-check deadline; not a real device latency measurement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HistoryDeadlinePaginationNativeTest {
    private static JSONObject options(String cursor,int limit)throws Exception{return new JSONObject().put("query","").put("cursor",cursor).put("limit",limit).put("maxChars",4000);}
    @Test public void lateDeadlineNeverClearsVerifiedHitAndSkipsItInContinuation()throws Exception{
        Context android=RuntimeEnvironment.getApplication();android.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
        try{
            ConversationRepository original=tasks.conversationRepository();original.initializeEmpty();original.transaction(()->{
                original.saveConversation(new JSONObject().put("conversationId","c").put("title","原分页反例").put("messages",new JSONArray()));
                for(int i=1;i<=2;i++){String id="message-"+i;original.saveSource(new SourceRef("message",id,1,"owner","private").toJson().put("conversationId","c").put("role","user").put("deleted",false));original.saveMessage("c",new JSONObject().put("id",id).put("revision",1L).put("role","user").put("content",i==1?"已交付的第一页":"必须交付而不能跳过的第二条"));}return null;
            });
            JSONObject first=original.queryHistory("c",options("",1),new RunControl());assertEquals("message-1",first.getJSONArray("entries").getJSONObject(0).getJSONObject("sourceRef").getString("sourceId"));String entryCursor=first.getString("nextCursor");assertFalse(entryCursor.isEmpty());
            AtomicInteger ticks=new AtomicInteger();ConversationRepository timed=new ConversationRepository(db,tasks,()->ticks.incrementAndGet()<3?0L:600_000_001L);
            JSONObject second=timed.queryHistory("c",options(entryCursor,1),new RunControl());assertEquals("partial",second.getString("status"));assertEquals("read_deadline",second.getString("reason"));assertEquals(1,second.getJSONArray("entries").length());JSONObject hit=second.getJSONArray("entries").getJSONObject(0);assertEquals("message-2",hit.getJSONObject("sourceRef").getString("sourceId"));assertEquals("必须交付而不能跳过的第二条",hit.getString("body"));assertEquals(hit.getString("body").length(),second.getInt("charsReturned"));
            JSONObject continuation=original.queryHistory("c",options(second.getString("nextCursor"),20),new RunControl());assertEquals(0,continuation.getJSONArray("entries").length());assertFalse(continuation.getBoolean("hasMore"));assertEquals(2,first.getJSONArray("entries").length()+second.getJSONArray("entries").length());
            RunControl lateCancel=new RunControl();AtomicInteger cancelTicks=new AtomicInteger();ConversationRepository cancelled=new ConversationRepository(db,tasks,()->{if(cancelTicks.incrementAndGet()<3)return 0L;lateCancel.cancel();return 600_000_001L;});assertThrows(InterruptedException.class,()->cancelled.queryHistory("c",options(entryCursor,1),lateCancel));
        }finally{db.close();}
    }
}
