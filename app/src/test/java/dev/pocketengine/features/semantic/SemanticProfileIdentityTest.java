package dev.pocketengine.features.semantic;

import android.app.Application;
import android.content.Intent;
import android.os.UserManager;
import android.provider.AlarmClock;
import dev.pocketengine.core.RunControl;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual typed parser/native package discovery; no physical profile/account acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SemanticProfileIdentityTest {
    @Test public void showAlarmsIsAClosedReadEntryAndNeverAClockMutation()throws Exception {
        SemanticRequest request=SemanticRequest.parse(new JSONObject().put("kind","show_alarms"));Intent intent=AndroidSemanticPlatform.intent(request);
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS,intent.getAction());assertNull(intent.getData());assertNull(intent.getExtras());assertNull(intent.getComponent());assertFalse(request.clock());assertEquals("phone_observe",request.followup());
        assertTrue(SemanticRequest.discoverySchema().getJSONObject("properties").getJSONObject("kind").getJSONArray("enum").toString().contains("show_alarms"));
        assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(new JSONObject().put("kind","show_alarms").put("skipUi",true)));
    }
    @Test public void samePackageVersionCannotReuseDiscoveryAfterNativeProfileSerialChanges()throws Exception {
        Application app=RuntimeEnvironment.getApplication();SemanticRequest request=SemanticRequest.parse(SemanticTypedShareRequestTest.share());AndroidTypedShareIntentTest.install(request,"share.native.fixture",true,true,false,1);
        AndroidSemanticPlatform platform=new AndroidSemanticPlatform(app,null,null);SemanticPlatform.Target before=platform.discover(request,new RunControl()).get(0);
        UserManager users=app.getSystemService(UserManager.class);long serial=users.getSerialNumberForUser(android.os.Process.myUserHandle());Shadows.shadowOf(users).setSerialNumberForUser(android.os.Process.myUserHandle(),serial+19);
        SemanticPlatform.Target after=platform.discover(request,new RunControl()).get(0);assertNotEquals(before.fingerprint,after.fingerprint);assertEquals(serial+19,after.identity().getLong("profileSerial"));assertEquals(android.os.Process.myUid()/100000,after.description().getInt("userId"));
        Shadows.shadowOf(users).setUserUnlocked(false);assertThrows(SecurityException.class,()->platform.discover(request,new RunControl()));assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void nativeUidFromAnotherProfileIsNotOfferedAsThisUsersSemanticTarget()throws Exception {
        Application app=RuntimeEnvironment.getApplication();SemanticRequest request=SemanticRequest.parse(SemanticTypedShareRequestTest.share());AndroidTypedShareIntentTest.install(request,"share.native.fixture",true,true,false,1);
        var matches=app.getPackageManager().queryIntentActivities(AndroidSemanticPlatform.intent(request),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY|android.content.pm.PackageManager.GET_RESOLVED_FILTER);
        matches.get(0).activityInfo.applicationInfo.uid=1_010_460;Shadows.shadowOf(app.getPackageManager()).setResolveInfosForIntent(AndroidSemanticPlatform.intent(request),matches);
        assertTrue(new AndroidSemanticPlatform(app,null,null).discover(request,new RunControl()).isEmpty());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
}
