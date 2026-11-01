package dev.pocketengine.phone;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocketengine.core.RunControl;
import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
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

/** Native source scheduling and real port code; an inaccessible root is an explicit platform fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=AndroidMainScreenContextSourceTest.Screen.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidMainScreenContextSourceTest {
    private static final AtomicInteger trees=new AtomicInteger(),screenshots=new AtomicInteger();
    @Implements(PhoneAccessibilityService.class) public static final class Screen {
        @Implementation public String observationSafetyFailure(){return null;}
        @Implementation protected AccessibilityNodeInfo foregroundRoot(){trees.incrementAndGet();return null;}
        @Implementation protected void takeScreenshot(int display,Executor executor,AccessibilityService.TakeScreenshotCallback callback){
            screenshots.incrementAndGet();throw new AssertionError("the source must not request pixels in these cases");
        }
    }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PhoneAccessibilityService service;
    @Before public void connect()throws Exception {
        trees.set(0);screenshots.set(0);
        service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();setInstance(service);
    }
    @After public void close()throws Exception {worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);setInstance(null);trees.set(0);screenshots.set(0);}
    private static void setInstance(PhoneAccessibilityService value)throws Exception {Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,value);}
    private static RunControl original(){RunControl c=new RunControl();c.bindHostRun("original-phone","original-run",3);c.pause();return c;}
    private OwnerScreenContext request(RunControl control)throws Exception {
        return new OwnerScreenContext(new OwnerScreenContext.Target("original-phone","original-run",3,"background","retained-background",17),control,
                (target,live)->assertSame(control,live),new AndroidMainScreenContextSource(RuntimeEnvironment.getApplication()),android.os.SystemClock::uptimeMillis);
    }
    private <T>T complete(Future<T> future)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!future.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}
        return future.get(1,TimeUnit.SECONDS);
    }
    @Test public void pausedNodeOnlyCaptureDoesNotScreenshotOrReplaceTheOriginalExecutionReferences()throws Exception {
        AndroidPhonePort physical=new AndroidPhonePort(RuntimeEnvironment.getApplication());
        Field field=AndroidPhonePort.class.getDeclaredField("reference");field.setAccessible(true);
        @SuppressWarnings("unchecked") AtomicReference<Object> originalReferences=(AtomicReference<Object>)field.get(physical);
        Object retained=new Object();originalReferences.set(retained);
        RunControl original=original();long revision=original.pauseRevision();
        try(OwnerScreenContext request=request(original)) {
            assertEquals(0,trees.get());assertEquals(0,screenshots.get());
            OwnerScreenContext.Preview preview=complete(worker.submit(()->request.capture(false)));
            assertEquals("no_accessible_root",preview.textStatus);assertEquals("",preview.text);
            assertEquals("not_requested",preview.screenshotStatus);assertFalse(preview.hasScreenshot());
            assertEquals(service.observationSessionId(),preview.source.sessionId);assertEquals(0,preview.source.displayId);
            assertEquals(17,preview.target.executionDisplayId);assertEquals("retained-background",preview.target.executionSessionId);
            assertEquals(2,trees.get());assertEquals(0,screenshots.get());assertSame(retained,originalReferences.get());
            assertTrue(original.isPaused());assertEquals(revision,original.pauseRevision());assertFalse(original.isCancelled());
        }
    }
    @Test public void explicitScreenshotWithNoScreenshotCapabilityReportsTheReasonWithoutAttemptingPixels()throws Exception {
        RunControl original=original();try(OwnerScreenContext request=request(original)) {
            OwnerScreenContext.Preview preview=complete(worker.submit(()->request.capture(true)));
            assertEquals("unavailable",preview.screenshotStatus);assertEquals("screenshot_permission_denied",preview.screenshotReason);
            assertFalse(preview.hasScreenshot());assertEquals(0,screenshots.get());assertTrue(original.isPaused());assertFalse(original.isCancelled());
        }
    }
}
