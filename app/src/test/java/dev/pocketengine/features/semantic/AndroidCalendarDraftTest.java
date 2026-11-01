package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import android.Manifest;
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
import android.provider.CalendarContract;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
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

/** Public Intent/PackageManager and native launch fixture; does not claim a real calendar save. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=AndroidCalendarDraftTest.SafeService.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidCalendarDraftTest {
    @Implements(PhoneAccessibilityService.class)
    public static final class SafeService {
        static int safetyChecks;static RunControl cancelOnFinalCheck;
        @Implementation public String observationSafetyFailure() {
            if(++safetyChecks==3&&cancelOnFinalCheck!=null)cancelOnFinalCheck.cancel();return null;
        }
    }
    private final Context context=RuntimeEnvironment.getApplication();
    private final RunControl control=new RunControl();
    private SemanticRequest request;
    @Before public void setup() throws Exception {
        request=SemanticRequest.parse(SemanticCalendarRequestTest.draft());control.bindHostRun("original-task","original-run",1);
        SafeService.safetyChecks=0;SafeService.cancelOnFinalCheck=null;
    }
    @After public void disconnect() throws Exception {serviceField().set(null,null);SafeService.cancelOnFinalCheck=null;}
    private static Field serviceField() throws Exception {Field field=PhoneAccessibilityService.class.getDeclaredField("instance");field.setAccessible(true);return field;}
    private AndroidSemanticPlatform platform(boolean service) throws Exception {
        PhoneAccessibilityService phone=service?Robolectric.buildService(PhoneAccessibilityService.class).create().get():null;
        serviceField().set(null,phone);return new AndroidSemanticPlatform(context,phone,service?()->{}:null);
    }
    private void editor(boolean exported,boolean appEnabled,boolean activityEnabled,boolean signed,String permission,int version,int certificate) throws Exception {
        ApplicationInfo app=new ApplicationInfo();app.packageName="example.calendar";app.enabled=appEnabled;app.uid=10457;
        ActivityInfo activity=new ActivityInfo();activity.packageName=app.packageName;activity.name="example.calendar.Editor";
        activity.enabled=activityEnabled;activity.exported=exported;activity.applicationInfo=app;activity.permission=permission;
        PackageInfo pkg=new PackageInfo();pkg.packageName=app.packageName;pkg.applicationInfo=app;pkg.activities=new ActivityInfo[]{activity};pkg.setLongVersionCode(version);pkg.lastUpdateTime=version*1000L;
        if(signed){pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{(byte)certificate})});}
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(Intent.ACTION_INSERT);filter.addCategory(Intent.CATEGORY_DEFAULT);filter.addDataType("vnd.android.cursor.dir/event");
        ResolveInfo resolve=new ResolveInfo();resolve.activityInfo=activity;resolve.filter=filter;resolve.nonLocalizedLabel="日历编辑器fixture";resolve.isDefault=true;
        Shadows.shadowOf(context.getPackageManager()).setResolveInfosForIntent(AndroidSemanticPlatform.intent(request),List.of(resolve));
    }
    private void editor() throws Exception {editor(true,true,true,true,null,1,1);}
    private JSONObject launch(AndroidSemanticPlatform platform,SemanticPlatform.Target target) throws Exception {
        FutureTask<JSONObject> future=new FutureTask<>(()->platform.dispatch(request,target,control,control::check));Thread worker=new Thread(future,"calendar-draft-fixture");worker.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!future.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        JSONObject receipt=future.get(1,TimeUnit.SECONDS);worker.join(1000);return receipt;
    }
    @Test public void publicInsertIntentContainsOnlyFixedCalendarDraftFields() throws Exception {
        Intent intent=AndroidSemanticPlatform.intent(request);JSONObject p=request.parameters();
        assertEquals(Intent.ACTION_INSERT,intent.getAction());assertEquals(CalendarContract.Events.CONTENT_URI,intent.getData());assertEquals("vnd.android.cursor.dir/event",intent.getType());
        assertEquals(p.getString("title"),intent.getStringExtra(CalendarContract.Events.TITLE));
        assertEquals(p.getLong("start"),intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,-1));assertEquals(p.getLong("end"),intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME,-1));
        assertEquals("Asia/Shanghai",intent.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE));assertFalse(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,true));
        assertEquals(Set.of(CalendarContract.Events.TITLE,CalendarContract.EXTRA_EVENT_BEGIN_TIME,CalendarContract.EXTRA_EVENT_END_TIME,CalendarContract.EXTRA_EVENT_ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,CalendarContract.Events.EVENT_END_TIMEZONE,CalendarContract.Events.EVENT_LOCATION,CalendarContract.Events.DESCRIPTION),intent.getExtras().keySet());
        assertNull(intent.getComponent());assertNull(intent.getClipData());assertEquals(0,intent.getFlags());
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.READ_CALENDAR));
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR));
    }
    @Test public void realPmDiscoveryNeedsAccessibleSignedEditorAndDoesNotLaunch() throws Exception {
        editor();List<SemanticPlatform.Target> targets=platform(false).discover(request,control);assertEquals(1,targets.size());assertEquals("official_intent",targets.get(0).route);
        assertEquals("example.calendar/example.calendar.Editor",targets.get(0).component);assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
    @Test public void absentEditorProducesNoCandidate() throws Exception {assertTrue(platform(false).discover(request,control).isEmpty());}
    @Test public void exportedEnabledSignatureAndRequiredPermissionAreRealEligibilityChecks() throws Exception {
        AndroidSemanticPlatform p=platform(false);
        editor(false,true,true,true,null,1,1);assertTrue(p.discover(request,control).isEmpty());
        editor(true,false,true,true,null,1,1);assertTrue(p.discover(request,control).isEmpty());
        editor(true,true,false,true,null,1,1);assertTrue(p.discover(request,control).isEmpty());
        editor(true,true,true,false,null,1,1);assertTrue(p.discover(request,control).isEmpty());
        editor(true,true,true,true,"example.calendar.EDITOR_PERMISSION",1,1);assertTrue(p.discover(request,control).isEmpty());
    }
    @Test public void realPackageVersionAndSignerChangesAlterFixedCandidate() throws Exception {
        AndroidSemanticPlatform p=platform(false);editor();String original=p.discover(request,control).get(0).fingerprint;
        editor(true,true,true,true,null,2,1);assertNotEquals(original,p.discover(request,control).get(0).fingerprint);
        editor(true,true,true,true,null,1,2);assertNotEquals(original,p.discover(request,control).get(0).fingerprint);
    }
    @Test public void nativeWorkerDispatchOpensOnePinnedDraftButDoesNotReportSave() throws Exception {
        editor();AndroidSemanticPlatform p=platform(true);SemanticPlatform.Target target=p.discover(request,control).get(0);
        JSONObject receipt=launch(p,target);assertTrue(receipt.getBoolean("accepted"));assertEquals("launch_requested",receipt.getString("status"));assertFalse(receipt.has("eventId"));
        Intent actual=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();assertNotNull(actual);
        assertEquals("example.calendar/example.calendar.Editor",actual.getComponent().flattenToString());assertEquals(Intent.ACTION_INSERT,actual.getAction());
        assertEquals(CalendarContract.Events.CONTENT_URI,actual.getData());assertEquals(request.parameters().getLong("start"),actual.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,-1));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,actual.getFlags());assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertEquals("main_screen",p.resourceIdentity().getString("executionMode"));assertEquals(0,p.resourceIdentity().getInt("displayId"));
    }
    @Test public void originalCancellationDuringLastSafetyCheckPreventsCalendarLaunch() throws Exception {
        editor();AndroidSemanticPlatform p=platform(true);SemanticPlatform.Target target=p.discover(request,control).get(0);SafeService.cancelOnFinalCheck=control;
        JSONObject receipt=launch(p,target);assertTrue(control.isCancelled());assertFalse(receipt.getBoolean("accepted"));assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
}
