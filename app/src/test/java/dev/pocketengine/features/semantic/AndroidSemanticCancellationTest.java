package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.lang.reflect.Field;
import java.util.Collection;
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

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=AndroidSemanticCancellationTest.SafeService.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidSemanticCancellationTest {
    @Implements(PhoneAccessibilityService.class)
    public static final class SafeService {
        @Implementation public String observationSafetyFailure() {return null;}
    }
    @After public void disconnect() throws Exception {serviceField().set(null,null);}
    private static Field serviceField() throws Exception {Field field=PhoneAccessibilityService.class.getDeclaredField("instance");field.setAccessible(true);return field;}
    private static int registrations(RunControl control) throws Exception {Field field=RunControl.class.getDeclaredField("cancellationActions");field.setAccessible(true);return ((Collection<?>)field.get(control)).size();}
    private static final class Fixture {
        final RunControl control=new RunControl();final AndroidSemanticPlatform platform;final SemanticRequest request;final SemanticPlatform.Target target;
        FutureTask<JSONObject> result;Thread worker;
        Fixture() throws Exception {
            PhoneAccessibilityService service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();serviceField().set(null,service);
            control.bindHostRun("original-task","original-run",1);platform=new AndroidSemanticPlatform(RuntimeEnvironment.getApplication(),service,()->{});
            request=SemanticRequest.parse(new JSONObject().put("kind","web").put("url","https://example.org/"));
            target=new SemanticPlatform.Target(new JSONObject().put("component","example.browser/example.browser.Main").put("packageName","example.browser"),"fixture","browser");
        }
        void start() {result=new FutureTask<>(()->platform.dispatch(request,target,control,control::check));worker=new Thread(result,"semantic-cancel-fixture");worker.start();}
        JSONObject completeOnMain() throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(!result.isDone()&&System.nanoTime()<deadline) {Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
            JSONObject receipt=result.get(1,TimeUnit.SECONDS);worker.join(1000);return receipt;
        }
        Intent started() {return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();}
    }
    @Test public void normalCompletionRemovesOnlyThisDispatchRegistration() throws Exception {
        Fixture f=new Fixture();AtomicInteger otherCancelled=new AtomicInteger();Runnable other=otherCancelled::incrementAndGet;f.control.onCancel(other);
        f.start();assertTrue(f.completeOnMain().getBoolean("accepted"));assertNotNull(f.started());assertNull(f.started());
        assertEquals(1,registrations(f.control));f.control.cancel();assertEquals(1,otherCancelled.get());assertEquals(0,registrations(f.control));
    }
    @Test public void alreadyCancelledControlCannotLaunchOrRetainCallback() throws Exception {
        Fixture f=new Fixture();f.control.cancel();f.start();JSONObject receipt=f.completeOnMain();
        assertFalse(receipt.getBoolean("accepted"));assertNull(f.started());assertEquals(0,registrations(f.control));
    }
    @Test public void queuedCancellationCannotBecomeALateLaunch() throws Exception {
        Fixture f=new Fixture();f.start();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(registrations(f.control)==0&&System.nanoTime()<until)Thread.sleep(5);
        assertEquals(1,registrations(f.control));f.control.cancel();JSONObject receipt=f.result.get(2,TimeUnit.SECONDS);f.worker.join(1000);
        assertFalse(receipt.getBoolean("accepted"));assertEquals(0,registrations(f.control));Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(f.started());
    }
    @Test public void queueTimeoutRemovesCallbackAndPreventsLateLaunch() throws Exception {
        Fixture f=new Fixture();f.start();JSONObject receipt=f.result.get(4,TimeUnit.SECONDS);f.worker.join(1000);
        assertFalse(receipt.getBoolean("accepted"));assertEquals("semantic_queue_cancelled",receipt.getString("code"));assertEquals(0,registrations(f.control));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(f.started());
    }
}
