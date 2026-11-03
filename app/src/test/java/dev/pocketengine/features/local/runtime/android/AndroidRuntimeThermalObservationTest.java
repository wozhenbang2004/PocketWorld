package dev.pocketengine.features.local.runtime.android;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowPowerManager;

/** Production adapter/API registration under Robolectric's explicitly simulated
 * PowerManager/ActivityManager; blocked-read fixtures test lifecycle only.
 * Not an OEM callback, physical memory/temperature or benchmark. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class AndroidRuntimeThermalObservationTest {
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static PowerManager power(){return RuntimeEnvironment.getApplication().getSystemService(PowerManager.class);}
    private static void memory(boolean low){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();info.lowMemory=low;info.availMem=low?50:600;info.totalMem=1000;info.threshold=100;Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(ActivityManager.class)).setMemoryInfo(info);}
    private static AndroidRuntimeThermalObservation create(PowerManager power,AtomicLong now){memory(false);return new AndroidRuntimeThermalObservation(RuntimeEnvironment.getApplication(),power,RuntimeEnvironment.getApplication().getSystemService(ActivityManager.class),new Handler(Looper.getMainLooper()),now::get);}
    private static JSONObject memoryReturned(AndroidRuntimeThermalObservation original,long after)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);JSONObject result;
        do{idle();result=original.cachedSnapshotForHost();if(result.getLong("memoryObservationSequence")>after)return result;Thread.sleep(5);}while(System.nanoTime()<until);
        throw new AssertionError("Original bounded memory read did not return: "+result);
    }
    private static PowerManager.OnThermalStatusChangedListener listener(ShadowPowerManager shadow){assertEquals(1,shadow.getThermalStatusListeners().size());return (PowerManager.OnThermalStatusChangedListener)shadow.getThermalStatusListeners().iterator().next();}
    @Test public void originalPlatformListenerAndFreshCooldownReadDriveOnlyAutomaticLogPages()throws Exception {
        PowerManager power=power();ShadowPowerManager shadow=Shadows.shadowOf(power);shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE);AtomicLong now=new AtomicLong(100);
        var original=create(power,now);
        try{
            idle();memoryReturned(original,0);assertEquals(1,shadow.getThermalStatusListeners().size());JSONObject hot=original.snapshotForHost();assertTrue(hot.getBoolean("nativeListenerRegistrationConfirmed"));assertEquals(8192,hot.getInt("automaticLogPageBytes"));assertEquals(3,hot.getInt("thermalStatus"));
            shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE);idle();assertEquals("thermal_cooling",original.snapshotForHost().getString("reason"));
            for(int i=0;i<5;i++){long prior=original.cachedSnapshotForHost().getLong("memoryObservationSequence");now.addAndGet(5000);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));memoryReturned(original,prior);}
            now.addAndGet(4999);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999));assertEquals(8192,original.snapshotForHost().getInt("automaticLogPageBytes"));
            long previous=original.cachedSnapshotForHost().getLong("memoryObservationSequence");now.incrementAndGet();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));JSONObject cold=memoryReturned(original,previous);
            assertEquals(32768,cold.getInt("automaticLogPageBytes"));assertEquals(0,cold.getInt("thermalStatus"));assertFalse(cold.getBoolean("executionPolicyChanged"));assertFalse(cold.getBoolean("resourceReleaseProved"));
            shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE);idle();assertEquals(8192,original.snapshotForHost().getInt("automaticLogPageBytes"));
        }finally{original.close();idle();assertTrue(original.snapshotForHost().getBoolean("listenerRemovalConfirmed"));assertEquals(0,shadow.getThermalStatusListeners().size());}
    }
    @Test public void delayedCallbackArgumentIsIgnoredInFavorOfTheCurrentNativeStatus()throws Exception {
        PowerManager power=power();ShadowPowerManager shadow=Shadows.shadowOf(power);shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL);AtomicLong now=new AtomicLong();
        var original=create(power,now);
        try{idle();var delayed=listener(shadow);delayed.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE);now.addAndGet(120000);idle();JSONObject actual=original.snapshotForHost();assertEquals(4,actual.getInt("thermalStatus"));assertEquals(8192,actual.getInt("automaticLogPageBytes"));assertEquals("thermal_pressure",actual.getString("reason"));}
        finally{original.close();idle();assertEquals(0,shadow.getThermalStatusListeners().size());}
    }
    @Test public void closingOriginalSourceRejectsItsLateCallbacksWithoutRemovingANewerListener()throws Exception {
        PowerManager power=power();ShadowPowerManager shadow=Shadows.shadowOf(power);AtomicLong now=new AtomicLong();
        var old=create(power,now);idle();memoryReturned(old,0);var stale=listener(shadow);String oldGeneration=old.snapshotForHost().getString("generation");old.close();
        var newer=create(power,now);
        try{idle();memoryReturned(newer,0);assertEquals(1,shadow.getThermalStatusListeners().size());String exact=newer.snapshotForHost().toString();stale.onThermalStatusChanged(PowerManager.THERMAL_STATUS_EMERGENCY);idle();assertEquals(exact,newer.snapshotForHost().toString());assertNotEquals(oldGeneration,newer.snapshotForHost().getString("generation"));assertTrue(old.snapshotForHost().getBoolean("closed"));assertTrue(old.snapshotForHost().getBoolean("listenerRemovalConfirmed"));}
        finally{old.close();newer.close();idle();assertEquals(0,shadow.getThermalStatusListeners().size());}
    }
    @Test public void unavailableOptionalPlatformFactsStayUnknownAndCloseWithoutClaimingExecution()throws Exception {
        AtomicLong now=new AtomicLong();var original=new AndroidRuntimeThermalObservation(null,new Handler(Looper.getMainLooper()),now::get);
        try{idle();now.addAndGet(120000);original.snapshotForHost();idle();JSONObject actual=original.snapshotForHost();assertTrue(actual.isNull("thermalStatus"));assertEquals(8192,actual.getInt("automaticLogPageBytes"));assertFalse(actual.getBoolean("nativeListenerRegistrationConfirmed"));assertFalse(actual.getBoolean("executionPolicyChanged"));assertFalse(actual.getBoolean("processesStopped"));}
        finally{original.close();idle();assertTrue(original.snapshotForHost().getBoolean("listenerRemovalConfirmed"));}
    }
    @Test public void originalAndroidFactoryMountsTheObserverWithoutPromotingAnUnavailableRuntime()throws Exception {
        PowerManager power=power();var shadow=Shadows.shadowOf(power);shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE);
        var original=AndroidManagedRuntimeFactory.create(RuntimeEnvironment.getApplication());String identity=original.identity();
        try{idle();JSONObject policy=original.automaticLogObservationPolicyForHost();assertEquals(3,policy.getInt("thermalStatus"));assertEquals(8192,policy.getInt("automaticLogPageBytes"));assertEquals(identity,original.identity());assertFalse(original.describe().getBoolean("canExecute"));assertFalse(original.describe().has("logObservationPolicy"));assertEquals(1,shadow.getThermalStatusListeners().size());}
        finally{original.close();idle();assertEquals(0,shadow.getThermalStatusListeners().size());}
    }
    @Test public void actualMemoryInfoControlsOnlyAutomaticPagesAndUiHiddenIsNotLowMemory()throws Exception {
        PowerManager power=power();Shadows.shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE);AtomicLong now=new AtomicLong();var original=create(power,now);
        try{
            JSONObject first=memoryReturned(original,0);assertFalse(first.getBoolean("memoryLow"));JSONObject healthy=first;for(int i=0;i<6;i++){long prior=healthy.getLong("memoryObservationSequence");now.addAndGet(5000);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));healthy=memoryReturned(original,prior);}assertEquals(32768,healthy.getInt("automaticLogPageBytes"));
            memory(true);now.addAndGet(5000);original.snapshotForHost();JSONObject low=memoryReturned(original,healthy.getLong("memoryObservationSequence"));assertTrue(low.getBoolean("memoryLow"));assertEquals(50,low.getLong("memoryAvailableBytes"));assertEquals("memory_pressure",low.getString("reason"));assertEquals(8192,low.getInt("automaticLogPageBytes"));assertFalse(low.getBoolean("processesStopped"));
            memory(false);RuntimeEnvironment.getApplication().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);assertTrue(original.cachedSnapshotForHost().isNull("memoryLow"));JSONObject reread=memoryReturned(original,low.getLong("memoryObservationSequence"));assertFalse(reread.getBoolean("memoryLow"));assertEquals("memory_cooling",reread.getString("reason"));assertEquals(8192,reread.getInt("automaticLogPageBytes"));
        }finally{original.close();idle();assertTrue(original.cachedSnapshotForHost().getBoolean("memoryListenerRemovalConfirmed"));}
    }
    @Test public void blockedMemoryReadDoesNotBlockCloseOrAdmitReplacementWorkers()throws Exception {
        AtomicLong now=new AtomicLong();AtomicInteger reads=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        var original=new AndroidRuntimeThermalObservation(RuntimeEnvironment.getApplication(),power(),new Handler(Looper.getMainLooper()),now::get,info->{reads.incrementAndGet();entered.countDown();try{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){/* Own uncooperative service-call fixture. */}info.lowMemory=false;info.availMem=600;info.totalMem=1000;info.threshold=100;}finally{returned.countDown();}});
        try{idle();assertTrue(entered.await(2,TimeUnit.SECONDS));for(int i=0;i<10;i++){now.addAndGet(5000);original.snapshotForHost();idle();}assertEquals(1,reads.get());long started=System.nanoTime();original.close();assertTrue("close must not join the OS reader",System.nanoTime()-started<TimeUnit.MILLISECONDS.toNanos(500));assertEquals(1,returned.getCount());release.countDown();assertTrue(returned.await(2,TimeUnit.SECONDS));idle();JSONObject closed=original.cachedSnapshotForHost();assertTrue(closed.getBoolean("closed"));assertTrue(closed.isNull("memoryLow"));assertEquals(8192,closed.getInt("automaticLogPageBytes"));assertEquals(1,reads.get());}
        finally{release.countDown();original.close();assertTrue(returned.await(2,TimeUnit.SECONDS));idle();}
    }
    @Test public void invalidationDuringOriginalReadTriggersOneFreshReadAfterItsReturn()throws Exception {
        AtomicLong now=new AtomicLong();AtomicInteger reads=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        var original=new AndroidRuntimeThermalObservation(RuntimeEnvironment.getApplication(),power(),new Handler(Looper.getMainLooper()),now::get,info->{if(reads.incrementAndGet()==1){entered.countDown();try{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}finally{returned.countDown();}}info.lowMemory=false;info.availMem=600;info.totalMem=1000;info.threshold=100;});
        try{idle();assertTrue(entered.await(2,TimeUnit.SECONDS));RuntimeEnvironment.getApplication().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);idle();assertEquals(1,reads.get());assertFalse(original.cachedSnapshotForHost().getBoolean("memoryFresh"));release.countDown();assertTrue(returned.await(2,TimeUnit.SECONDS));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);JSONObject current;
            do{now.addAndGet(5000);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));current=original.cachedSnapshotForHost();if(current.getBoolean("memoryFresh"))break;Thread.sleep(5);}while(System.nanoTime()<until);
            assertTrue(current.toString(),current.getBoolean("memoryFresh"));assertTrue(reads.get()>=2);assertFalse(current.getBoolean("memoryLow"));assertFalse(current.getBoolean("executionPolicyChanged"));
        }finally{release.countDown();original.close();assertTrue(returned.await(2,TimeUnit.SECONDS));idle();}
    }
}
