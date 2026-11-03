package dev.pocketengine.phone.setup;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import dev.pocketengine.core.RunControl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLauncherApps;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowUserManager;
import static org.junit.Assert.*;

/** Original LauncherApps consumer with actual SDK35 PM/UM objects; native launch is a recorded service fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows=AndroidAppCopiesNativeTest.LauncherFixture.class)
public final class AndroidAppCopiesNativeTest {
    public static final String PACKAGE="copy.native.fixture";
    private static final String ARCHIVE="/fixture/native-copy/base.apk";
    public record Launch(ComponentName component,UserHandle user,Bundle options) { }
    @Implements(LauncherApps.class)
    public static class LauncherFixture extends ShadowLauncherApps {
        public static final List<Launch> calls=new ArrayList<>();public static boolean loseReply;
        @Implementation protected void startMainActivity(ComponentName component,UserHandle user,Rect bounds,Bundle options){calls.add(new Launch(component,user,options));if(loseReply)throw new IllegalStateException("fixture lost native launch reply");}
    }
    public static Application app(){return RuntimeEnvironment.getApplication();}
    public static UserHandle work(){return UserHandle.getUserHandleForUid(1_010_466);}
    public static RunControl control(){RunControl control=new RunControl();control.bindHostRun("copy-task","copy-run",1);return control;}
    @Before public void setup()throws Exception {installFixture();}
    public static void installFixture()throws Exception {
        LauncherFixture.calls.clear();LauncherFixture.loseReply=false;
        UserManager users=app().getSystemService(UserManager.class);Shadows.shadowOf(users).addProfile(0,10,"work fixture",0x20);
        Shadows.shadowOf(users).setUserState(work(),ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED);Shadows.shadowOf(users).setSerialNumberForUser(work(),910);
        installArchive(1);LauncherApps launcher=app().getSystemService(LauncherApps.class);
        for(int user:new int[]{0,10}) {
            ActivityInfo activity=new ActivityInfo();activity.packageName=PACKAGE;activity.name=PACKAGE+".Main";activity.enabled=true;activity.exported=true;activity.nonLocalizedLabel="同名应用";
            activity.applicationInfo=new ApplicationInfo();activity.applicationInfo.packageName=PACKAGE;activity.applicationInfo.uid=user*100000+10466;activity.applicationInfo.sourceDir=ARCHIVE;activity.applicationInfo.enabled=true;
            UserHandle handle=UserHandle.getUserHandleForUid(activity.applicationInfo.uid);Shadows.shadowOf(launcher).addActivity(handle,info(activity,handle));
        }
    }
    public static void installArchive(int version)throws Exception {
        PackageInfo pkg=new PackageInfo();pkg.packageName=PACKAGE;pkg.setLongVersionCode(version);pkg.firstInstallTime=1000;pkg.lastUpdateTime=2000;
        pkg.applicationInfo=new ApplicationInfo();pkg.applicationInfo.packageName=PACKAGE;pkg.applicationInfo.uid=10466;pkg.applicationInfo.enabled=true;pkg.applicationInfo.sourceDir=ARCHIVE;
        pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{4,7,8})});
        Shadows.shadowOf(app().getPackageManager()).installPackage(pkg);Shadows.shadowOf(app().getPackageManager()).setPackageArchiveInfo(ARCHIVE,pkg);
    }
    private static LauncherActivityInfo info(ActivityInfo activity,UserHandle user)throws Exception {
        // SDK35 hidden framework construction stays reflective in this host fixture, not production.
        Class<?> internal=Class.forName("android.content.pm.LauncherActivityInfoInternal"),incremental=Class.forName("android.content.pm.IncrementalStatesInfo");
        var inside=internal.getDeclaredConstructor(ActivityInfo.class,incremental,UserHandle.class);inside.setAccessible(true);Object value=inside.newInstance(activity,null,user);
        var outside=LauncherActivityInfo.class.getDeclaredConstructor(Context.class,internal);outside.setAccessible(true);return outside.newInstance(app(),value);
    }
    public static JSONObject choose(JSONObject listed,int user)throws Exception {
        JSONArray rows=listed.getJSONArray("apps");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("userId")==user){JSONObject row=rows.getJSONObject(i);return new JSONObject().put("copyRef",row.getString("copyRef")).put("packageName",row.getString("packageName")).put("userId",user);}throw new AssertionError("expected accessible profile absent");
    }
    @Test public void referencesDistinguishSameNamedPersonalAndWorkCopiesWithNativeSignersAndSerials()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject listed=copies.list(control);assertTrue(listed.getBoolean("complete"));assertEquals(2,listed.getJSONArray("apps").length());
            JSONObject owner=copies.require(choose(listed,0),control),work=copies.require(choose(listed,10),control);
            assertEquals(10466,owner.getInt("uid"));assertEquals(1_010_466,work.getInt("uid"));assertEquals(910,work.getLong("profileSerial"));assertEquals(1,work.getLong("versionCode"));assertEquals(1,work.getJSONArray("certificates").length());
            assertTrue(owner.getBoolean("profileInstallTimeVerified"));assertFalse(work.getBoolean("profileInstallTimeVerified"));assertTrue(work.has("packageFirstInstallTime"));assertEquals(0,work.getInt("displayId"));assertTrue(work.getLong("referenceExpiresAtUptimeMs")>android.os.SystemClock.uptimeMillis());
        }finally{copies.clear();}
    }
    @Test public void originalRunDisplayAndExactExpiryAreRequiredEvenForAnUnchangedApp()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject args=choose(copies.list(control),10);assertThrows(SecurityException.class,()->copies.require(args,control()));
            assertThrows(SecurityException.class,()->copies.launch(args,7,control,()->{}));
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60));assertThrows(SecurityException.class,()->copies.require(args,control));assertTrue(LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void pauseResumeAndRefreshInvalidateOldReferencesWithoutNativeLaunch()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject old=choose(copies.list(control),10);control.pause();control.resume();assertThrows(SecurityException.class,()->copies.require(old,control));
            JSONObject fresh=choose(copies.list(control),10);copies.list(control);assertThrows(SecurityException.class,()->copies.require(fresh,control));assertTrue(LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void profileRecreatedOrLockedCannotFallBackToThePersonalCopy()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject args=choose(copies.list(control),10);UserManager users=app().getSystemService(UserManager.class);Shadows.shadowOf(users).setSerialNumberForUser(work(),911);
            assertThrows(SecurityException.class,()->copies.require(args,control));Shadows.shadowOf(users).setSerialNumberForUser(work(),910);Shadows.shadowOf(users).setUserState(work(),ShadowUserManager.UserState.STATE_RUNNING_LOCKED);
            assertThrows(SecurityException.class,()->copies.require(args,control));assertTrue(LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void inaccessibleWorkProfileProducesAPartialListWithoutInventingItsCopy()throws Exception {
        Shadows.shadowOf(app().getSystemService(UserManager.class)).setUserState(work(),ShadowUserManager.UserState.STATE_RUNNING_LOCKED);
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject listed=copies.list(control);assertFalse(listed.getBoolean("complete"));assertEquals(1,listed.getJSONArray("apps").length());assertEquals(0,listed.getJSONArray("apps").getJSONObject(0).getInt("userId"));assertEquals("some_profiles_unavailable",listed.getString("reason"));assertTrue(LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void installChangedDuringAdmissionStopsTheOriginalNativeLaunch()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject args=choose(copies.list(control),10);JSONObject refused=copies.launch(args,0,control,()->installArchive(2));assertFalse(refused.getBoolean("accepted"));assertEquals("app_copy_admission_changed",refused.getString("error"));assertTrue(LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void platformPackageEventRevokesAnOtherwiseMatchingReference()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject args=choose(copies.list(control),0);Shadows.shadowOf(app().getSystemService(LauncherApps.class)).notifyPackageRemoved(PACKAGE);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertThrows(SecurityException.class,()->copies.require(args,control));assertTrue(LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void selectedWorkUserIsTheOnlyNativeEntryAndReceiptStillRequiresOwnerAccountVerification()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject receipt=copies.launch(choose(copies.list(control),10),0,control,control::check);assertEquals(1,LauncherFixture.calls.size());Launch launch=LauncherFixture.calls.get(0);
            assertEquals(work(),launch.user());assertEquals(PACKAGE+"/"+PACKAGE+".Main",launch.component().flattenToString());assertEquals(0,ActivityOptions.fromBundle(launch.options()).getLaunchDisplayId());
            assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("actualUserVerified"));assertTrue(receipt.getBoolean("requiresPause"));assertFalse(receipt.getBoolean("automaticProfileFallback"));
        }finally{copies.clear();}
    }
    @Test public void lostReplyAfterLaunchIsUnknownAndNeverRetriedInsideTheCopyConsumer()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(app(),0);RunControl control=control();try {
            JSONObject args=choose(copies.list(control),10);LauncherFixture.loseReply=true;JSONObject receipt=copies.launch(args,0,control,control::check);
            assertEquals(1,LauncherFixture.calls.size());assertTrue(receipt.isNull("accepted"));assertTrue(receipt.getBoolean("requiresReconciliation"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));
        }finally{copies.clear();}
    }
}
