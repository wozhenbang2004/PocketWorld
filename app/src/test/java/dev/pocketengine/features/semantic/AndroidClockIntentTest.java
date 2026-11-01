package dev.pocketengine.features.semantic;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Looper;
import android.provider.AlarmClock;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
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

/** Real SDK Intent/PackageManager/service-queue fixtures, not real alarm creation or device consent proof. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=AndroidClockIntentTest.Safety.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidClockIntentTest {
    @Implements(PhoneAccessibilityService.class)
    public static final class Safety {static int checks;static RunControl cancel;@Implementation public String observationSafetyFailure(){if(++checks==3&&cancel!=null)cancel.cancel();return null;}}
    final Context context=RuntimeEnvironment.getApplication();final RunControl control=new RunControl();TimeZone originalZone;
    @Before public void before()throws Exception{originalZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));control.bindHostRun("clock-native-task","clock-native-run",1);Shadows.shadowOf((Application)context).grantPermissions(AndroidSemanticPlatform.CLOCK_PERMISSION);Safety.checks=0;Safety.cancel=null;}
    @After public void after()throws Exception{serviceField().set(null,null);Safety.cancel=null;TimeZone.setDefault(originalZone);control.cancel();}
    private static Field serviceField()throws Exception{Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);return f;}
    AndroidSemanticPlatform platform(boolean connected)throws Exception{PhoneAccessibilityService service=connected?Robolectric.buildService(PhoneAccessibilityService.class).create().get():null;serviceField().set(null,service);return new AndroidSemanticPlatform(context,service,connected?()->{}:null);}
    void install(SemanticRequest request,boolean exported,boolean signed)throws Exception{
        ApplicationInfo app=new ApplicationInfo();app.packageName="clock.native.fixture";app.enabled=true;app.uid=10459;ActivityInfo a=new ActivityInfo();a.packageName=app.packageName;a.name="clock.native.fixture.Main";a.applicationInfo=app;a.enabled=true;a.exported=exported;
        PackageInfo pkg=new PackageInfo();pkg.packageName=app.packageName;pkg.applicationInfo=app;pkg.activities=new ActivityInfo[]{a};pkg.setLongVersionCode(1);pkg.lastUpdateTime=1000;
        if(signed){pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{7,8,9})});}Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(request.action);filter.addCategory(Intent.CATEGORY_DEFAULT);ResolveInfo match=new ResolveInfo();match.activityInfo=a;match.filter=filter;match.isDefault=true;match.nonLocalizedLabel="时钟native fixture";Shadows.shadowOf(context.getPackageManager()).setResolveInfosForIntent(AndroidSemanticPlatform.intent(request),List.of(match));
    }
    JSONObject launch(AndroidSemanticPlatform p,SemanticRequest r,SemanticPlatform.Target t)throws Exception{
        FutureTask<JSONObject> future=new FutureTask<>(()->p.dispatch(r,t,control,control::check));Thread worker=new Thread(future,"clock-native-fixture");worker.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        try{while(!future.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}return future.get(1,TimeUnit.SECONDS);}finally{if(!future.isDone()){control.cancel();worker.interrupt();}worker.join(1000);assertFalse("fixture worker must exit",worker.isAlive());}
    }
    @Test public void alarmUsesOnlyOfficialFixedExtrasAndNeverAnArbitraryUri()throws Exception{Intent i=AndroidSemanticPlatform.intent(SemanticRequest.parse(SemanticClockTest.alarm()));assertEquals(AlarmClock.ACTION_SET_ALARM,i.getAction());assertNull(i.getData());assertNull(i.getType());assertNull(i.getComponent());assertNull(i.getClipData());assertEquals(7,i.getIntExtra(AlarmClock.EXTRA_HOUR,-1));assertEquals(30,i.getIntExtra(AlarmClock.EXTRA_MINUTES,-1));assertEquals("出门",i.getStringExtra(AlarmClock.EXTRA_MESSAGE));assertFalse(i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true));assertEquals(Set.of(AlarmClock.EXTRA_HOUR,AlarmClock.EXTRA_MINUTES,AlarmClock.EXTRA_MESSAGE,AlarmClock.EXTRA_SKIP_UI),i.getExtras().keySet());}
    @Test public void timerUsesSecondsAndRequestsUiButHasNoExtraConfirmationClaim()throws Exception{Intent i=AndroidSemanticPlatform.intent(SemanticRequest.parse(SemanticClockTest.timer()));assertEquals(AlarmClock.ACTION_SET_TIMER,i.getAction());assertEquals(120,i.getIntExtra(AlarmClock.EXTRA_LENGTH,-1));assertFalse(i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true));assertNull(i.getData());assertEquals(Set.of(AlarmClock.EXTRA_LENGTH,AlarmClock.EXTRA_MESSAGE,AlarmClock.EXTRA_SKIP_UI),i.getExtras().keySet());}
    @Test public void realPackageDiscoveryNeedsPermissionExportedAndSignedHandlerAndDoesNotLaunch()throws Exception{SemanticRequest r=SemanticRequest.parse(SemanticClockTest.alarm());AndroidSemanticPlatform p=platform(false);install(r,true,true);assertEquals(1,p.discover(r,control).size());assertEquals("Asia/Shanghai",p.discover(r,control).get(0).identity().getString("deviceTimeZone"));assertNull(Shadows.shadowOf((Application)context).getNextStartedActivity());install(r,false,true);assertTrue(p.discover(r,control).isEmpty());install(r,true,false);assertTrue(p.discover(r,control).isEmpty());Shadows.shadowOf((Application)context).denyPermissions(AndroidSemanticPlatform.CLOCK_PERMISSION);assertThrows(SecurityException.class,()->p.discover(r,control));}
    @Test public void noInstalledVisibleHandlerProducesNoCandidateOrLaunch()throws Exception{assertTrue(platform(false).discover(SemanticRequest.parse(SemanticClockTest.timer()),control).isEmpty());assertNull(Shadows.shadowOf((Application)context).getNextStartedActivity());}
    @Test public void nativeZoneChangeChangesCandidateAndRejectsAlreadyQueuedOldZone()throws Exception{SemanticRequest r=SemanticRequest.parse(SemanticClockTest.alarm());install(r,true,true);AndroidSemanticPlatform p=platform(true);SemanticPlatform.Target old=p.discover(r,control).get(0);TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertNotEquals(old.fingerprint,p.discover(r,control).get(0).fingerprint);assertFalse(launch(p,r,old).getBoolean("accepted"));assertNull(Shadows.shadowOf((Application)context).getNextStartedActivity());}
    @Test public void oneNativeLaunchHasPinnedComponentAndOnlyLaunchReceiptNotAlarmState()throws Exception{SemanticRequest r=SemanticRequest.parse(SemanticClockTest.timer());install(r,true,true);AndroidSemanticPlatform p=platform(true);JSONObject receipt=launch(p,r,p.discover(r,control).get(0));assertTrue(receipt.getBoolean("accepted"));assertEquals("launch_requested",receipt.getString("status"));assertFalse(receipt.has("alarmId"));assertFalse(receipt.has("clockStateVerified"));Intent actual=Shadows.shadowOf((Application)context).getNextStartedActivity();assertNotNull(actual);assertEquals("clock.native.fixture/clock.native.fixture.Main",actual.getComponent().flattenToString());assertEquals(120,actual.getIntExtra(AlarmClock.EXTRA_LENGTH,-1));assertFalse(actual.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true));assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,actual.getFlags());assertNull(Shadows.shadowOf((Application)context).getNextStartedActivity());}
    @Test public void cancellationAtFinalSafetyCheckPreventsNativeLaunch()throws Exception{SemanticRequest r=SemanticRequest.parse(SemanticClockTest.alarm());install(r,true,true);AndroidSemanticPlatform p=platform(true);SemanticPlatform.Target t=p.discover(r,control).get(0);Safety.cancel=control;assertFalse(launch(p,r,t).getBoolean("accepted"));assertNull(Shadows.shadowOf((Application)context).getNextStartedActivity());}
}
