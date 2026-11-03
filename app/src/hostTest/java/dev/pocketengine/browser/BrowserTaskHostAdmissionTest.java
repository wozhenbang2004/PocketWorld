package dev.pocketengine.browser;

import android.os.Looper;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real host, original App monitors/control and SQLite. No model, device or WebView renderer claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=BrowserTaskHostAdmissionTest.NoUnrelatedStartup.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserTaskHostAdmissionTest {
    @org.robolectric.annotation.Implements(EngineApplication.class)
    public static class NoUnrelatedStartup extends org.robolectric.shadows.ShadowApplication {
        @org.robolectric.annotation.Implementation protected void onCreate(){}
    }
    interface Checked {void run()throws Exception;}
    static void field(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    static final class F implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final TaskStore tasks=new TaskStore(app);
        final RunControl control=new RunControl();
        final ExecutorService workers=Executors.newFixedThreadPool(2);
        final AtomicInteger reads=new AtomicInteger();
        final CountDownLatch rechecked=new CountDownLatch(1);
        final org.robolectric.android.controller.ActivityController<BrowserActivity> activity;
        final BrowserModule browser;
        final BrowserTaskHost host;
        final String task,run;final int epoch;
        final JSONObject profile;
        volatile Checked onRecheck;
        volatile boolean requireUnlockedControlRead;
        F()throws Exception {
            app.tasks=tasks;
            JSONObject created=tasks.create("原手机任务接入浏览","main_screen");task=created.getString("id");
            JSONObject attempt=tasks.startAttempt(task,"main_screen",40);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");
            control.bindHostRun(task,run,epoch);tasks.status(task,"running","original fixture run");
            field(app,"activeTaskId",task);field(app,"activeControl",control);
            BrowserActivity.setOwnerPanelInstaller(null);
            activity=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();
            activity.get().openForOwner("https://owner.example/original");
            browser=new BrowserModule(new FeatureContext(app,null,null,event->{}));profile=browser.snapshot();
            host=new BrowserTaskHost(browser,tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){
                public void checkModelConfigured(){}
                public void launch(String id){throw new AssertionError("must keep original task/run");}
                public RunControl currentControl(String id){
                    // Fail before taking App's monitor on the old code, rather than leave a deadlocked test JVM.
                    if(requireUnlockedControlRead)assertFalse("host callback must not run under TaskStore monitor",Thread.holdsLock(tasks));
                    if(reads.incrementAndGet()==2){
                        try{if(onRecheck!=null)onRecheck.run();}catch(Exception error){throw new IllegalStateException(error);}
                        finally{rechecked.countDown();}
                    }
                    return app.control(id);
                }
            },event->{});
        }
        Future<JSONObject> consent(){return workers.submit(()->host.allowExistingForOwner(task,profile,true));}
        JSONObject pump(Future<JSONObject> result)throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(!result.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}
            Shadows.shadowOf(Looper.getMainLooper()).idle();return result.get(1,TimeUnit.SECONDS);
        }
        void deny(Future<JSONObject> result)throws Exception {
            ExecutionException error=assertThrows(ExecutionException.class,()->pump(result));
            assertTrue(String.valueOf(error.getCause()),error.getCause() instanceof SecurityException||error.getCause() instanceof InterruptedException);
            assertNoPlan();
        }
        void assertNoPlan()throws Exception {
            Field plans=BrowserTaskHost.class.getDeclaredField("plans");plans.setAccessible(true);assertTrue(((Map<?,?>)plans.get(host)).isEmpty());
            assertFalse(tasks.get(task,false).getJSONArray("events").toString().contains("browser_research_requested"));
        }
        void rewrite(Checked edit)throws Exception {synchronized(tasks){edit.run();}}
        void save(JSONObject row)throws Exception {
            Method write=TaskStore.class.getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(tasks,row);
        }
        public void close()throws Exception {
            control.cancel();workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));
            browser.close();Shadows.shadowOf(Looper.getMainLooper()).idle();activity.pause().stop().destroy();
            BrowserActivity.setEvents(null);BrowserActivity.setOwnerPanelInstaller(null);
            Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();
        }
    }
    interface Prepare {void run(String id,JSONObject choice)throws Exception;}
    interface Launch {void run(String id)throws Exception;}
    static BrowserTaskHost starting(F f,Prepare prepare,Launch launch){
        return new BrowserTaskHost(f.browser,f.tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){
            public void checkModelConfigured(){}
            public void prepareOwnerExecutionScope(String id,JSONObject choice)throws Exception {prepare.run(id,choice);}
            public void launch(String id)throws Exception {launch.run(id);}
            public RunControl currentControl(String id){return null;}
        },event->{});
    }
    static JSONObject event(JSONObject task,String type)throws Exception {
        JSONArray events=task.getJSONArray("events");
        for(int i=0;i<events.length();i++)if(type.equals(events.getJSONObject(i).optString("type")))return events.getJSONObject(i);
        fail("Missing "+type+" in "+events);return null;
    }
    static int planCount(BrowserTaskHost host)throws Exception {
        Field plans=BrowserTaskHost.class.getDeclaredField("plans");plans.setAccessible(true);return ((Map<?,?>)plans.get(host)).size();
    }
    static JSONObject choice(long deadline)throws Exception {return new JSONObject().put("deadlineAt",deadline).put("toolLimit",23).put("modelCallLimit",29);}
    static Future<JSONObject> start(F f,BrowserTaskHost host,JSONObject choice){
        return f.workers.submit(()->host.startForOwner("保留本轮原研究目标",f.profile,true,BrowserReadScope.disabled(),BrowserFrameOrigins.sameOriginOnly(),BrowserActionScope.disabled(),choice));
    }
    @Test public void expiredChoiceSettlesOnlyTheCreatedUnlaunchedTaskAndRetainsItsOriginalChoice()throws Exception {
        try(F f=new F()){
            AtomicReference<String> id=new AtomicReference<>();AtomicInteger launches=new AtomicInteger();
            BrowserTaskHost host=starting(f,(task,scope)->{id.set(task);f.tasks.configureExecutionScope(task,0,scope.getLong("deadlineAt"),scope.getInt("toolLimit"),scope.getInt("modelCallLimit"));},task->launches.incrementAndGet());
            JSONObject scope=choice(System.currentTimeMillis()-1),original=new JSONObject(scope.toString());
            ExecutionException error=assertThrows(ExecutionException.class,()->f.pump(start(f,host,scope)));
            assertNotNull(id.get());assertTrue(error.getCause().getMessage(),error.getCause().getMessage().contains(id.get()));
            JSONObject rejected=f.tasks.get(id.get(),false);assertEquals("failed",rejected.getString("status"));assertEquals("",rejected.optString("runId"));
            assertFalse(rejected.has("executionScope"));assertEquals(0,launches.get());assertEquals(0,planCount(host));
            assertEquals(original.toString(),scope.toString());assertEquals(original.toString(),event(rejected,"browser_research_requested").getJSONObject("executionChoice").toString());
            JSONObject failure=event(rejected,"browser_research_start_failed");assertEquals(id.get(),failure.getString("taskId"));assertEquals("before_launch",failure.getString("phase"));assertFalse(failure.getBoolean("launchEntered"));
            assertEquals("running",f.tasks.get(f.task,false).getString("status"));assertEquals(2,f.tasks.list().length());
            JSONObject state=f.pump(f.workers.submit(host::snapshotForOwner));assertEquals(id.get(),state.getJSONObject("researchTask").getString("id"));assertFalse(state.has("pendingBrowserTask"));
            JSONArray history=host.historyForOwner();assertEquals(1,history.length());assertEquals(id.get(),history.getJSONObject(0).getString("taskId"));assertEquals("failed",history.getJSONObject(0).getString("status"));
        }
    }
    @Test public void preparationCannotMutateTheRetainedOwnerChoiceOrRetryWithDefaults()throws Exception {
        try(F f=new F()){
            AtomicReference<String> id=new AtomicReference<>();AtomicInteger preparations=new AtomicInteger(),launches=new AtomicInteger();
            BrowserTaskHost host=starting(f,(task,scope)->{id.set(task);preparations.incrementAndGet();scope.put("deadlineAt",0L);throw new SecurityException("明确拒绝原范围");},task->launches.incrementAndGet());
            JSONObject scope=choice(System.currentTimeMillis()+60_000),original=new JSONObject(scope.toString());
            assertThrows(ExecutionException.class,()->f.pump(start(f,host,scope)));
            JSONObject rejected=f.tasks.get(id.get(),false);assertEquals("failed",rejected.getString("status"));assertEquals(1,preparations.get());assertEquals(0,launches.get());
            assertEquals(original.toString(),scope.toString());assertEquals(original.toString(),event(rejected,"browser_research_requested").getJSONObject("executionChoice").toString());
            assertEquals(0,planCount(host));assertFalse(rejected.has("executionScope"));
        }
    }
    @Test public void validChoiceReachesTheOriginalLauncherExactlyOnceWithoutRefreshingItsDeadline()throws Exception {
        try(F f=new F()){
            AtomicReference<String> id=new AtomicReference<>();AtomicInteger launches=new AtomicInteger();
            BrowserTaskHost host=starting(f,(task,scope)->{id.set(task);f.tasks.configureExecutionScope(task,0,scope.getLong("deadlineAt"),scope.getInt("toolLimit"),scope.getInt("modelCallLimit"));},task->{launches.incrementAndGet();f.tasks.startAttempt(task,"capabilities",23);f.tasks.status(task,"running","original launcher admitted");});
            JSONObject scope=choice(System.currentTimeMillis()+60_000),started=f.pump(start(f,host,scope));
            assertEquals(id.get(),started.getString("id"));assertEquals("running",started.getString("status"));assertEquals(1,launches.get());assertEquals(1,planCount(host));
            JSONObject accepted=started.getJSONObject("executionScope");assertEquals(scope.getLong("deadlineAt"),accepted.getLong("deadlineAt"));assertEquals(23,accepted.getInt("toolLimit"));assertEquals(29,accepted.getInt("modelCallLimit"));
            assertFalse(started.getJSONArray("events").toString().contains("browser_research_start_failed"));
        }
    }
    @Test public void launcherExceptionAfterAdmissionCannotOverwriteTheOriginalRunOrRemoveItsPlan()throws Exception {
        try(F f=new F()){
            AtomicReference<String> id=new AtomicReference<>(),run=new AtomicReference<>();IOException lost=new IOException("original launch acknowledgement unavailable");
            BrowserTaskHost host=starting(f,(task,scope)->{id.set(task);f.tasks.initializeExecutionScope(task,23);},task->{JSONObject admitted=f.tasks.startAttempt(task,"capabilities",23);run.set(admitted.getString("runId"));f.tasks.status(task,"running","original executor owns this run");throw lost;});
            ExecutionException error=assertThrows(ExecutionException.class,()->f.pump(start(f,host,null)));assertSame(lost,error.getCause());
            JSONObject task=f.tasks.get(id.get(),false);assertEquals("running",task.getString("status"));assertEquals(run.get(),task.getString("runId"));assertEquals(1,planCount(host));
            assertFalse(task.getJSONArray("events").toString().contains("browser_research_start_failed"));
        }
    }
    @Test public void appPauseAndOwnerAdmissionCannotInvertAppAndStoreMonitors()throws Exception {
        try(F f=new F()){
            f.requireUnlockedControlRead=true;
            CountDownLatch appHeld=new CountDownLatch(1);AtomicReference<Future<?>> pause=new AtomicReference<>();
            f.onRecheck=()->{
                pause.set(f.workers.submit(()->{synchronized(f.app){appHeld.countDown();f.app.pause();}}));
                assertTrue("pause took App monitor",appHeld.await(2,TimeUnit.SECONDS));
            };
            f.deny(f.consent());pause.get().get(2,TimeUnit.SECONDS);
            assertTrue(f.control.isPaused());assertEquals("pausing",f.tasks.get(f.task,false).getString("status"));
            assertSame(f.control,f.app.control(f.task));assertTrue(f.reads.get()>=2);
        }
    }
    @Test public void unchangedPrivateOwnerRunRegistersAgainstTheSameControl()throws Exception {
        try(F f=new F()){
            assertEquals("pending_original_turn",f.pump(f.consent()).getString("status"));
            JSONObject task=f.tasks.get(f.task,false);assertEquals(f.run,task.getString("runId"));assertEquals(f.epoch,task.getInt("coordinatorEpoch"));
            assertSame(f.control,f.app.control(f.task));assertEquals(1,f.tasks.list().length());
            assertTrue(task.getJSONArray("events").toString().contains("browser_research_requested"));
        }
    }
    @Test public void replacedHostControlCannotUseTheOldOwnerConsent()throws Exception {
        try(F f=new F()){
            RunControl replacement=new RunControl();replacement.bindHostRun(f.task,f.run,f.epoch);
            f.onRecheck=()->field(f.app,"activeControl",replacement);f.deny(f.consent());
        }
    }
    @Test public void newerSqlRunWhileProfileSelectionWaitsCannotReceiveOldConsent()throws Exception {
        try(F f=new F()){
            Future<JSONObject> result=f.consent();assertTrue(f.rechecked.await(2,TimeUnit.SECONDS));
            f.tasks.endAttempt(f.task,f.run,"paused","fixture original ended",new JSONObject());
            JSONObject newer=f.tasks.startAttempt(f.task,"main_screen",40);f.tasks.status(f.task,"running","fixture newer run");
            f.deny(result);assertEquals(newer.getString("runId"),f.tasks.get(f.task,false).getString("runId"));
        }
    }
    @Test public void ownerAndPrivateSpaceAreRecheckedAfterProfileSelectionWait()throws Exception {
        for(String key:new String[]{"actorId","spaceId"})try(F f=new F()){
            Future<JSONObject> result=f.consent();assertTrue(f.rechecked.await(2,TimeUnit.SECONDS));
            f.rewrite(()->f.save(f.tasks.get(f.task,false).put(key,"another-owner-or-space")));f.deny(result);
        }
    }
    @Test public void pauseThenResumeWhileProfileSelectionWaitsInvalidatesOldConsent()throws Exception {
        try(F f=new F()){
            Future<JSONObject> result=f.consent();assertTrue(f.rechecked.await(2,TimeUnit.SECONDS));
            f.control.pause();f.control.resume();f.deny(result);assertFalse(f.control.isPaused());
        }
    }
}
