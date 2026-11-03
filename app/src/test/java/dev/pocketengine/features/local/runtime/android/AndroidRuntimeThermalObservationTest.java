package dev.pocketengine.features.local.runtime.android;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowPowerManager;

/** Actual production adapter/API registration under Robolectric's explicitly
 * simulated PowerManager. Not an OEM callback, real thermometry or benchmark. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class AndroidRuntimeThermalObservationTest {
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static PowerManager power(){return RuntimeEnvironment.getApplication().getSystemService(PowerManager.class);}
    private static PowerManager.OnThermalStatusChangedListener listener(ShadowPowerManager shadow){assertEquals(1,shadow.getThermalStatusListeners().size());return (PowerManager.OnThermalStatusChangedListener)shadow.getThermalStatusListeners().iterator().next();}
    @Test public void originalPlatformListenerAndFreshCooldownReadDriveOnlyAutomaticLogPages()throws Exception {
        PowerManager power=power();ShadowPowerManager shadow=Shadows.shadowOf(power);shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE);AtomicLong now=new AtomicLong(100);
        var original=new AndroidRuntimeThermalObservation(power,new Handler(Looper.getMainLooper()),now::get);
        try{
            idle();assertEquals(1,shadow.getThermalStatusListeners().size());JSONObject hot=original.snapshotForHost();assertTrue(hot.getBoolean("nativeListenerRegistrationConfirmed"));assertEquals(8192,hot.getInt("automaticLogPageBytes"));assertEquals(3,hot.getInt("thermalStatus"));
            shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE);idle();assertEquals("thermal_cooling",original.snapshotForHost().getString("reason"));
            now.addAndGet(29999);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(29999));assertEquals(8192,original.snapshotForHost().getInt("automaticLogPageBytes"));
            now.incrementAndGet();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));JSONObject cold=original.snapshotForHost();assertEquals(32768,cold.getInt("automaticLogPageBytes"));assertEquals(0,cold.getInt("thermalStatus"));assertFalse(cold.getBoolean("executionPolicyChanged"));assertFalse(cold.getBoolean("resourceReleaseProved"));
            shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE);idle();assertEquals(8192,original.snapshotForHost().getInt("automaticLogPageBytes"));
        }finally{original.close();idle();assertTrue(original.snapshotForHost().getBoolean("listenerRemovalConfirmed"));assertEquals(0,shadow.getThermalStatusListeners().size());}
    }
    @Test public void delayedCallbackArgumentIsIgnoredInFavorOfTheCurrentNativeStatus()throws Exception {
        PowerManager power=power();ShadowPowerManager shadow=Shadows.shadowOf(power);shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL);AtomicLong now=new AtomicLong();
        var original=new AndroidRuntimeThermalObservation(power,new Handler(Looper.getMainLooper()),now::get);
        try{idle();var delayed=listener(shadow);delayed.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE);now.addAndGet(120000);idle();JSONObject actual=original.snapshotForHost();assertEquals(4,actual.getInt("thermalStatus"));assertEquals(8192,actual.getInt("automaticLogPageBytes"));assertEquals("thermal_pressure",actual.getString("reason"));}
        finally{original.close();idle();assertEquals(0,shadow.getThermalStatusListeners().size());}
    }
    @Test public void closingOriginalSourceRejectsItsLateCallbacksWithoutRemovingANewerListener()throws Exception {
        PowerManager power=power();ShadowPowerManager shadow=Shadows.shadowOf(power);AtomicLong now=new AtomicLong();
        var old=new AndroidRuntimeThermalObservation(power,new Handler(Looper.getMainLooper()),now::get);idle();var stale=listener(shadow);String oldGeneration=old.snapshotForHost().getString("generation");old.close();
        var newer=new AndroidRuntimeThermalObservation(power,new Handler(Looper.getMainLooper()),now::get);
        try{idle();assertEquals(1,shadow.getThermalStatusListeners().size());String exact=newer.snapshotForHost().toString();stale.onThermalStatusChanged(PowerManager.THERMAL_STATUS_EMERGENCY);idle();assertEquals(exact,newer.snapshotForHost().toString());assertNotEquals(oldGeneration,newer.snapshotForHost().getString("generation"));assertTrue(old.snapshotForHost().getBoolean("closed"));assertTrue(old.snapshotForHost().getBoolean("listenerRemovalConfirmed"));}
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
}
