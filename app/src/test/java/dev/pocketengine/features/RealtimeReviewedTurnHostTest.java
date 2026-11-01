package dev.pocketengine.features;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.*;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class RealtimeReviewedTurnHostTest {
 private RealtimeReviewedTurn turn()throws Exception{
  Constructor<RealtimeReviewedTurn> make=RealtimeReviewedTurn.class.getDeclaredConstructor(String.class,String.class,String.class,long.class,String.class,String.class,String.class,String.class,String.class);make.setAccessible(true);
  return make.newInstance(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),2L,"整理待核对清单","segment","reported-local","track","text_stream");
 }
 @Test public void realApplicationCallbackUsesOnlyItsTaskStoreAndPublishesWaitingTaskWithoutStartingRun()throws Exception{
  EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();app.addObserver(events::add);
  try{
   RealtimeSessionModule module=(RealtimeSessionModule)app.features.module("realtime");Field callback=RealtimeSessionModule.class.getDeclaredField("turnCommitter");callback.setAccessible(true);RealtimeTurnCommitter committer=(RealtimeTurnCommitter)callback.get(module);assertNotNull(committer);
   RealtimeReviewedTurn missing=turn();assertNull(committer.lookup(missing));assertEquals(0,app.tasks.list().length());
   RealtimeReviewedTurn turn=turn();JSONObject saved=committer.create(turn);assertEquals(turn.commitId,saved.getString("id"));assertEquals("awaiting_user",saved.getString("status"));assertEquals(0,saved.getInt("coordinatorEpoch"));assertFalse(saved.has("runId"));assertFalse(saved.getJSONObject("origin").getBoolean("identityVerified"));assertNull(app.activeId());
   assertEquals(turn.commitId,committer.lookup(turn).getString("id"));assertFalse(committer.create(turn).getBoolean("created"));assertEquals(1,app.tasks.list().length());
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(events.stream().anyMatch(e->e.optString("type").equals("task")&&e.optJSONObject("task")!=null&&turn.commitId.equals(e.optJSONObject("task").optString("id"))));
   Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)database.get(app.tasks);for(String table:new String[]{"runs","operations","leases"})try(Cursor cursor=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));}
  }finally{app.features.close();Field field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);ExecutorService workers=(ExecutorService)field.get(app);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();}
 }
}
