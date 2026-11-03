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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
