package dev.pocketengine.phone;

import android.Manifest;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.provider.Settings;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native Android permission/channel probes; this is not physical-device notification visibility evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class AndroidPhoneReadinessTest {
    private static JSONObject serviceCheck(JSONObject result) throws Exception {
        return result.getJSONObject("mainObservation").getJSONArray("checks").getJSONObject(0);
    }
    @Test public void enabledAppWithBlockedPhoneChannelHasNoNotificationStopRoute()throws Exception {
        Application app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager=app.getSystemService(NotificationManager.class);Shadows.shadowOf(manager).setNotificationsEnabled(true);
        manager.createNotificationChannel(new NotificationChannel(PhoneReadiness.TASK_CHANNEL,"手机任务",NotificationManager.IMPORTANCE_NONE));
        JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertTrue(manager.areNotificationsEnabled());assertFalse(result.getJSONObject("taskNotifications").getBoolean("ready"));
        assertFalse(result.getJSONObject("stopControl").getBoolean("ready"));assertFalse(result.getBoolean("mainScreenFallback"));
    }
    @Test public void revokedPostNotificationPermissionCannotUseAnEnabledChannel()throws Exception {
        Application app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager=app.getSystemService(NotificationManager.class);Shadows.shadowOf(manager).setNotificationsEnabled(true);
        manager.createNotificationChannel(new NotificationChannel(PhoneReadiness.TASK_CHANNEL,"手机任务",NotificationManager.IMPORTANCE_LOW));
        JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertFalse(result.getJSONObject("taskNotificationsForStart").getBoolean("ready"));assertFalse(result.getJSONObject("taskNotifications").getBoolean("ready"));
    }
    @Test public void readinessDoesNotCreateAChannelOrStartAScreenRead()throws Exception {
        Application app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager=app.getSystemService(NotificationManager.class);Shadows.shadowOf(manager).setNotificationsEnabled(true);
        assertNull(manager.getNotificationChannel(PhoneReadiness.TASK_CHANNEL));JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertNull(manager.getNotificationChannel(PhoneReadiness.TASK_CHANNEL));assertTrue(result.getBoolean("observedOnly"));
        assertFalse(result.getJSONObject("mainObservation").getBoolean("ready"));assertFalse(result.getJSONObject("backgroundPreparation").getBoolean("ready"));
    }
    @Test public void enabledSettingWithoutAConnectedServiceIsReportedWithoutChangingSettings()throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        String ownService=new ComponentName(app,PhoneAccessibilityService.class).flattenToString();
        Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
        Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,ownService);
        JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertEquals("accessibility_not_connected",serviceCheck(result).getString("code"));
        assertFalse(result.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(result.getJSONObject("backgroundPreparation").getBoolean("ready"));
        assertEquals(1,Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED));
        assertEquals(ownService,Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }
    @Test public void anotherEnabledAccessibilityServiceDoesNotEnableOurService()throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
        Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"other.app/.ReaderService");
        JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertEquals("accessibility_disabled",serviceCheck(result).getString("code"));
        assertFalse(result.getJSONObject("mainObservation").getBoolean("ready"));
    }
    @Test public void shortenedOwnComponentAmongOtherServicesIsStillEnabled()throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        Context context=new ContextWrapper(app){@Override public String getPackageName(){return "dev.pocketengine";}};
        String ownService=new ComponentName(context,PhoneAccessibilityService.class).flattenToShortString();
        assertTrue(ownService,ownService.contains("/."));
        Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
        Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"other.app/.ReaderService:"+ownService);
        assertEquals("accessibility_not_connected",serviceCheck(AndroidPhoneReadiness.snapshot(context,null)).getString("code"));
    }
    @Test public void missingSystemSettingRemainsUnknownInsteadOfClaimingTheServiceIsDisabled()throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,null);
        assertNull(PhoneAccessibilityService.get());
        JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertEquals("accessibility_state_unknown",serviceCheck(result).getString("code"));
        assertEquals("unknown",serviceCheck(result).getString("state"));
        assertFalse(result.getJSONObject("mainPreparation").getBoolean("ready"));
    }
    @Test public void disabledGlobalSettingWinsOverARetainedServiceRegistration()throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
        Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,new ComponentName(app,PhoneAccessibilityService.class).flattenToString());
        JSONObject result=AndroidPhoneReadiness.snapshot(app,null);
        assertEquals("accessibility_disabled",serviceCheck(result).getString("code"));
        assertFalse(result.getJSONObject("mainObservation").getBoolean("ready"));
    }
}
