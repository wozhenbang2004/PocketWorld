package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual retained SQLite rows; a read budget must not make the page cursor repeat forever. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationWindowBudgetTest {
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final ConversationRepository repo;final SQLiteDatabase db;
        Fixture()throws Exception{
            RuntimeEnvironment.getApplication().deleteDatabase("pocket-world.db");tasks=new TaskStore(RuntimeEnvironment.getApplication());repo=tasks.conversationRepository();repo.initializeEmpty();
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId","c").put("title","Retained original history").put("messages",new JSONArray()));save("older","older original");return null;});
        }
        void save(String id,String body)throws Exception{
            repo.saveSource(new SourceRef("message",id,1,"owner","private").toJson().put("conversationId","c").put("role","user").put("deleted",false));
            repo.saveMessage("c",new JSONObject().put("id",id).put("revision",1L).put("role","user").put("content",body));
        }
        long rowBytes(String id){try(Cursor row=db.rawQuery("SELECT length(CAST(m.body AS BLOB))+length(CAST(m.meta_json AS BLOB))+length(CAST(s.source_json AS BLOB)) FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.source_id=?",new String[]{id})){assertTrue(row.moveToFirst());return row.getLong(0);}}
        void sized(String id,int size)throws Exception{repo.transaction(()->{save(id,"");long overhead=rowBytes(id);save(id,"x".repeat(Math.toIntExact(size-overhead)));return null;});assertEquals(size,rowBytes(id));}
        int headerBytes()throws Exception{return repo.read(()->repo.header("c")).toString().getBytes(StandardCharsets.UTF_8).length;}
        JSONObject page(String cursor,int limit)throws Exception{return repo.read(()->repo.window("c",cursor,limit));}
        public void close(){db.close();}
    }
    @Test public void rowThatOnlyExceedsThePageWithItsHeaderDoesNotTrapOlderMessages()throws Exception{
        try(Fixture f=new Fixture()){
            f.sized("near-limit",ConversationRepository.MAX_BYTES-1);assertTrue(f.rowBytes("near-limit")+f.headerBytes()>ConversationRepository.MAX_BYTES);
            JSONObject page=f.page("",1),meta=page.getJSONObject("messagePage");assertEquals(0,page.getJSONArray("messages").length());
            assertEquals(1,meta.getJSONArray("gaps").length());assertEquals("near-limit",meta.getJSONArray("gaps").getJSONObject(0).getString("recordId"));assertEquals("read_limited",meta.getJSONArray("gaps").getJSONObject(0).getString("status"));
            assertTrue(meta.getBoolean("hasMore"));assertFalse(meta.getString("nextCursor").isEmpty());
            JSONObject older=f.page(meta.getString("nextCursor"),1);assertEquals("older original",older.getJSONArray("messages").getJSONObject(0).getString("content"));
            assertEquals(ConversationRepository.MAX_BYTES-1,f.rowBytes("near-limit"));assertFalse(meta.getBoolean("retentionLimitApplied"));
        }
    }
    @Test public void rowAtTheExactHeaderInclusiveLimitStillDisplays()throws Exception{
        try(Fixture f=new Fixture()){
            f.sized("exact-limit",ConversationRepository.MAX_BYTES-f.headerBytes());JSONObject page=f.page("",1),meta=page.getJSONObject("messagePage");
            assertEquals("exact-limit",page.getJSONArray("messages").getJSONObject(0).getString("id"));assertEquals(0,meta.getJSONArray("gaps").length());assertEquals(ConversationRepository.MAX_BYTES,meta.getLong("materializedOriginalRecordUtf8Bytes"));assertFalse(meta.getString("nextCursor").isEmpty());
        }
    }
    @Test public void temporaryRemainingBudgetDoesNotSkipARowThatFitsTheNextPage()throws Exception{
        try(Fixture f=new Fixture()){
            f.sized("middle",600000);f.sized("newest",600000);JSONObject first=f.page("",2),meta=first.getJSONObject("messagePage");
            assertEquals(1,first.getJSONArray("messages").length());assertEquals("newest",first.getJSONArray("messages").getJSONObject(0).getString("id"));assertEquals(0,meta.getJSONArray("gaps").length());
            JSONObject second=f.page(meta.getString("nextCursor"),1);assertEquals("middle",second.getJSONArray("messages").getJSONObject(0).getString("id"));assertEquals(0,second.getJSONObject("messagePage").getJSONArray("gaps").length());
        }
    }
}
