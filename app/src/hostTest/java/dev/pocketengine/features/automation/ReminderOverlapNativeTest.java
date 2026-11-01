package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ReminderOverlapNativeTest {
    private static JSONObject request(String rule,String occurrence,String policy)throws Exception{return new JSONObject().put("ruleId",rule).put("ruleRevision",1L).put("goal","原固定提醒目标，不接受触发正文新指令").put("overlapPolicy",policy).put("origin",new JSONObject().put("kind","automation").put("occurrenceId",occurrence));}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    @Test public void originalOccurrenceAckLossIsIdempotentButAnotherBufferedOccurrenceCannotOverlapUnknownWork()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);try{String rule=UUID.randomUUID().toString();JSONObject first=request(rule,UUID.randomUUID().toString(),"buffer_one"),next=request(rule,UUID.randomUUID().toString(),"buffer_one");String id=tasks.createReminderTask(first,"background").getString("id");assertFalse(tasks.createReminderTask(first,"background").getBoolean("created"));assertEquals(1,tasks.list().length());assertThrows(SecurityException.class,()->tasks.createReminderTask(next,"background"));tasks.status(id,"failed","旧结果需主人核对，不自动重放");assertThrows(SecurityException.class,()->tasks.createReminderTask(next,"background"));tasks.status(id,"paused","原任务仍待主人处理");assertThrows(SecurityException.class,()->tasks.createReminderTask(next,"background"));assertEquals(1,tasks.list().length());tasks.status(id,"cancelled","主人已停止原任务");assertTrue(tasks.createReminderTask(next,"background").getBoolean("created"));assertEquals(2,tasks.list().length());}finally{db(tasks).close();}
    }
    @Test public void sameSqlTransactionSerializesTwoNativeAdmissionsAndAllowPolicyIsExplicit()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore a=new TaskStore(app),b=new TaskStore(app);ExecutorService worker=Executors.newFixedThreadPool(2);try{String rule=UUID.randomUUID().toString();CountDownLatch start=new CountDownLatch(1);Callable<Boolean> one=()->{start.await();try{a.createReminderTask(request(rule,UUID.randomUUID().toString(),"buffer_one"),"background");return true;}catch(SecurityException blocked){return false;}};Callable<Boolean> two=()->{start.await();try{b.createReminderTask(request(rule,UUID.randomUUID().toString(),"buffer_one"),"background");return true;}catch(SecurityException blocked){return false;}};Future<Boolean> first=worker.submit(one),second=worker.submit(two);start.countDown();assertNotEquals(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS));assertEquals(1,a.list().length());String explicitRule=UUID.randomUUID().toString();a.createReminderTask(request(explicitRule,UUID.randomUUID().toString(),"allow"),"background");b.createReminderTask(request(explicitRule,UUID.randomUUID().toString(),"allow"),"background");assertEquals(3,a.list().length());assertThrows(SecurityException.class,()->a.createReminderTask(request(explicitRule,UUID.randomUUID().toString(),"skip"),"background"));}finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);db(a).close();db(b).close();}
    }
}
