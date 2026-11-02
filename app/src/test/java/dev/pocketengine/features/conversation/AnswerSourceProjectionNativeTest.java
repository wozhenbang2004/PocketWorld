package dev.pocketengine.features.conversation;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.usage.*;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AnswerSourceProjectionNativeTest {
 private static SQLiteDatabase db(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
 private static final class Fixture implements AutoCloseable {
  final TaskStore tasks;final String task,run;final int epoch;final JSONObject anchor;
  Fixture()throws Exception {Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);task=tasks.create("PW-EVAL-SOURCE 核对原观察，不操作设备").getString("id");JSONObject attempt=tasks.startAttempt(task,"main_screen",4);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(task,run,epoch);tasks.event(task,run,epoch,new JSONObject().put("type","observation").put("status","completed").put("receipt",new JSONObject().put("ok",true).put("observationId","original-observation").put("text","original retained material-572")));
   InferenceCapture capture=new InferenceCapture(new ModelConfig("https://fixture.invalid/v1","fixture-only-key","fixture-model",false,4,10),control,tasks.journal(task,run,epoch,control).inferenceTarget(InferenceReceipt.Purpose.MAIN,""));capture.dispatch();capture.finish(InferenceReceipt.Outcome.COMPLETED);anchor=AnswerSourceProjection.capture(db(tasks),task,run,epoch,capture.callId,UUID.randomUUID().toString());
  }
  public void close()throws Exception{db(tasks).close();}
 }
 private static long count(SQLiteDatabase db,String table){try(var row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
 @Test public void oldAnswerReadsItsOriginalRunAfterResumeAndDoesNotExecuteOrSubstituteAFrame()throws Exception {try(Fixture f=new Fixture()){assertEquals(1,f.anchor.getJSONArray("entries").length());f.tasks.endAttempt(f.task,f.run,"needs_confirmation","fixture answer",new JSONObject());JSONObject next=f.tasks.startAttempt(f.task,"main_screen",4);f.tasks.event(f.task,next.getString("runId"),next.getInt("coordinatorEpoch"),new JSONObject().put("type","observation").put("status","completed").put("receipt",new JSONObject().put("ok",true).put("observationId","new-observation").put("text","different current material")));long tasks=count(db(f.tasks),"tasks"),runs=count(db(f.tasks),"runs"),operations=count(db(f.tasks),"operations");JSONObject read=AnswerSourceProjection.read(db(f.tasks),f.anchor,0);assertTrue(read.getBoolean("available"));assertTrue(read.getString("body").contains("original retained material-572"));assertFalse(read.getString("body").contains("different current material"));assertEquals("not_retained",read.getString("frameStatus"));assertFalse(read.getBoolean("executionAuthorized"));assertEquals(tasks,count(db(f.tasks),"tasks"));assertEquals(runs,count(db(f.tasks),"runs"));assertEquals(operations,count(db(f.tasks),"operations"));}}
 @Test public void changedOrRemovedExactEventCannotFallBackToLatestSource()throws Exception {try(Fixture f=new Fixture()){long order=f.anchor.getJSONArray("entries").getJSONObject(0).getLong("eventOrder");db(f.tasks).execSQL("UPDATE task_events SET event_json=? WHERE task_id=? AND event_order=?",new Object[]{"{}",f.task,order});assertEquals("source_changed",AnswerSourceProjection.read(db(f.tasks),f.anchor,0).getString("status"));db(f.tasks).execSQL("DELETE FROM task_events WHERE task_id=? AND event_order=?",new Object[]{f.task,order});assertEquals("source_unavailable",AnswerSourceProjection.read(db(f.tasks),f.anchor,0).getString("status"));}}
}
