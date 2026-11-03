package dev.pocketengine.features.semantic;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Deterministic interleaving at the real production main-thread callback, not an SSH/device test. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,shadows=NativePauseBoundaryReviewTest.SafetyShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativePauseBoundaryReviewTest {
    static RunControl cancelling;static final AtomicInteger safetyCalls=new AtomicInteger();
    @Implements(PhoneAccessibilityService.class)
    public static final class SafetyShadow {
        @Implementation public String observationSafetyFailure(){
            int call=safetyCalls.incrementAndGet();
            // A real owner cancellation may arrive from another thread during the final
            // service/permission/lock checks. Inject precisely that legal interleaving.
            if(call==3&&cancelling!=null)cancelling.pause();
            return null;
        }
    }
    @After public void clear()throws Exception {cancelling=null;safetyCalls.set(0);Field field=PhoneAccessibilityService.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,null);}
    private JSONObject dispatch(boolean cancel)throws Exception {
        Context app=RuntimeEnvironment.getApplication();PhoneAccessibilityService service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();
        Field field=PhoneAccessibilityService.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,service);
        RunControl control=new RunControl();control.bindHostRun("original-task","original-run",1);cancelling=cancel?control:null;safetyCalls.set(0);
        AndroidSemanticPlatform platform=new AndroidSemanticPlatform(app,service,()->{});
        SemanticRequest request=SemanticRequest.parse(new JSONObject().put("kind","web").put("url","https://example.org/"));
        SemanticPlatform.Target target=new SemanticPlatform.Target(new JSONObject().put("component","example.browser/example.browser.Main").put("packageName","example.browser")
                .put("userId",0).put("profileSerial",RuntimeEnvironment.getApplication().getSystemService(android.os.UserManager.class).getSerialNumberForUser(android.os.Process.myUserHandle())),"fixture","browser");
        FutureTask<JSONObject> future=new FutureTask<>(()->platform.dispatch(request,target,control,control::check));Thread worker=new Thread(future,"a26-review-main-dispatch");worker.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!future.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        JSONObject result=future.get(1,TimeUnit.SECONDS);worker.join(1000);
        Intent started=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        System.out.println("ROOT_PAUSE_REVIEW cancel="+cancel+" safetyCalls="+safetyCalls.get()+" controlPaused="+control.isPaused()+" launchRecorded="+(started!=null)+" receipt="+result);
        if(cancel){assertTrue(control.isPaused());assertNull("A pause completed before gate.enter must win against the Android launch",started);}
        else {assertNotNull("positive control must exercise real production startActivity path",started);assertTrue(result.getBoolean("accepted"));}
        return result;
    }
    @Test public void positiveProductionCallbackLaunchesOnce()throws Exception {dispatch(false);}
    @Test public void pauseBeforeGateEntryMustPreventLaunch()throws Exception {dispatch(true);}
}
