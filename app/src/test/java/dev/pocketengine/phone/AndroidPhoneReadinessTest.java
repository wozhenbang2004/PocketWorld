package dev.pocketengine.phone;

import android.Manifest;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
}
