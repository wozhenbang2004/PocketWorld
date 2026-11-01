package dev.pocketengine;

import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AdaptivePhoneAdmissionTest {
    @Test public void actualPhoneAdmissionBindsTheExactStartedTaskAttemptBeforeServiceLaunch()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();String id=app.tasks.create("绑定原手机任务","main_screen").getString("id");
        try{
            Method admit=EngineApplication.class.getDeclaredMethod("admit",String.class,String.class,ModelConfig.class);admit.setAccessible(true);admit.invoke(app,id,"main_screen",new ModelConfig("https://model.invalid","","fixture-model",false,4,10));
            JSONObject actual=app.tasks.get(id,false);RunControl control=app.control(id);assertNotNull(control);assertTrue(control.hasHostRun());assertTrue(control.matchesHostRun(id,actual.getString("runId"),actual.getInt("coordinatorEpoch")));assertFalse(control.matchesHostRun(id,"foreign-run",actual.getInt("coordinatorEpoch")));
        }finally{
            app.stop(id);app.features.close();Field field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);ExecutorService workers=(ExecutorService)field.get(app);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
        }
    }
    @Test public void actualServiceRetainsThePhysicalPortForOwnerTakeoverWhenAdaptiveObservationPauses()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();String id=app.tasks.create("原任务等待权限","main_screen").getString("id");ModelConfig config=new ModelConfig("https://model.invalid","","fixture-model",false,4,10);
        ServiceController<PhoneAccessibilityService> phone=Robolectric.buildService(PhoneAccessibilityService.class).create();ServiceController<PhoneRunService> execution=Robolectric.buildService(PhoneRunService.class).create();
        ExecutorService runner=Executors.newSingleThreadExecutor();
        try{
            Method connect=PhoneAccessibilityService.class.getDeclaredMethod("onServiceConnected");connect.setAccessible(true);connect.invoke(phone.get());
            Method admit=EngineApplication.class.getDeclaredMethod("admit",String.class,String.class,ModelConfig.class);admit.setAccessible(true);admit.invoke(app,id,"main_screen",config);JSONObject task=app.tasks.get(id,false);RunControl control=app.control(id);
            for(String name:new String[]{"runningId","runningRunId","ownerControl"}){Field field=PhoneRunService.class.getDeclaredField(name);field.setAccessible(true);field.set(execution.get(),name.equals("runningId")?id:name.equals("runningRunId")?task.getString("runId"):control);}
            Method run=PhoneRunService.class.getDeclaredMethod("run",String.class,String.class,int.class,String.class,ModelConfig.class,RunControl.class,int.class,boolean.class);run.setAccessible(true);Future<?> pending=runner.submit(()->{try{run.invoke(execution.get(),id,task.getString("runId"),task.getInt("coordinatorEpoch"),task.getString("goal"),config,control,1,false);}catch(Exception failure){throw new AssertionError(failure);}});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!pending.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}pending.get(1,TimeUnit.SECONDS);
            Field attached=EngineApplication.class.getDeclaredField("activePort"),held=PhoneRunService.class.getDeclaredField("heldPort");attached.setAccessible(true);held.setAccessible(true);assertTrue(attached.get(app) instanceof AndroidPhonePort);assertSame(attached.get(app),held.get(execution.get()));assertTrue(control.isPaused());assertEquals("awaiting_user",app.tasks.get(id,false).getString("status"));
        }finally{
            runner.shutdownNow();runner.awaitTermination(3,TimeUnit.SECONDS);execution.destroy();phone.destroy();app.features.close();Field field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);ExecutorService workers=(ExecutorService)field.get(app);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
        }
    }
}
