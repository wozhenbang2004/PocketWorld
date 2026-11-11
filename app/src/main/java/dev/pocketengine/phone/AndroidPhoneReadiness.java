package dev.pocketengine.phone;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import dev.pocketengine.phone.display.DisplayModule;
import org.json.JSONObject;

/** Local native permission/connection facts. No permission request, helper connection, root read or screenshot. */
public final class AndroidPhoneReadiness {
    private AndroidPhoneReadiness() { }
    @FunctionalInterface private interface Probe { Object read() throws Exception; }

    public static JSONObject snapshot(Context context, DisplayModule background) throws Exception {
        JSONObject facts = new JSONObject().put("checkedAtUptimeMs", SystemClock.uptimeMillis());
        PhoneAccessibilityService service = PhoneAccessibilityService.get();
        facts.put("serviceConnected", service != null).put("mainServiceSessionId", service == null ? JSONObject.NULL : service.observationSessionId());
        fact(facts, "serviceEnabled", () -> serviceEnabled(context));
        AccessibilityServiceInfo info = null;
        if (service != null) try { info = service.getServiceInfo(); } catch (RuntimeException ignored) { }
        final AccessibilityServiceInfo permissions = info;
        fact(facts, "windowContent", () -> service == null ? false : permissions == null ? null
                : (permissions.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0);
        fact(facts, "interactiveWindows", () -> service == null ? false : permissions == null ? null
                : (permissions.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0);
        fact(facts, "gestureCapability", () -> service == null ? false : permissions == null ? null
                : (permissions.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0);
        fact(facts, "screenshotCapability", () -> service == null ? false : permissions == null ? null
                : (permissions.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0);
        fact(facts, "ownerControlsVisible", () -> service != null && service.hasVisibleOwnerStopControl());
        fact(facts, "deviceUnlocked", () -> {
            KeyguardManager lock = context.getSystemService(KeyguardManager.class);
            return lock == null ? null : !lock.isDeviceLocked() && !lock.isKeyguardLocked();
        });
        fact(facts, "screenInteractive", () -> {
            PowerManager power = context.getSystemService(PowerManager.class); return power == null ? null : power.isInteractive();
        });
        fact(facts, "mainDisplayPresent", () -> {
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            return displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY) != null;
        });
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        fact(facts, "notificationsAllowed", () -> notifications == null ? null : notifications.areNotificationsEnabled());
        fact(facts, "notificationPermission", () -> Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        fact(facts, "notificationsNotPaused", () -> notifications == null ? null : !notifications.areNotificationsPaused());
        fact(facts, "taskChannelState", () -> {
            if (notifications == null) return "unknown";
            NotificationChannel channel = notifications.getNotificationChannel(PhoneReadiness.TASK_CHANNEL);
            if (channel == null) return "not_created";
            if (channel.getImportance() <= NotificationManager.IMPORTANCE_NONE) return "disabled";
            if (channel.getGroup() != null) {
                NotificationChannelGroup group = notifications.getNotificationChannelGroup(channel.getGroup());
                if (group == null) return "unknown";
                if (group.isBlocked()) return "disabled";
            }
            return "enabled";
        });
        facts.put("backgroundSupported", Build.VERSION.SDK_INT >= 34);
        if (background == null) facts.put("backgroundConnected", false).put("backgroundSessionReady", false).put("backgroundClean", true);
        else try {
            JSONObject state = background.snapshot();
            facts.put("backgroundConnected", state.getBoolean("helperConnected"))
                    .put("backgroundSessionReady", state.getBoolean("readyForTask"))
                    .put("backgroundClean", !state.optBoolean("releasePending") && !state.optBoolean("recoveryRequired"))
                    .put("backgroundSessionId", state.opt("sessionId") == null ? JSONObject.NULL : state.opt("sessionId"))
                    .put("backgroundDisplayId", state.optInt("displayId", -1))
                    .put("backgroundInput",state.opt("inputInjectionAvailable")==null?JSONObject.NULL:state.opt("inputInjectionAvailable"))
                    .put("backgroundCapture",state.opt("captureAvailable")==null?JSONObject.NULL:state.opt("captureAvailable"))
                    .put("backgroundIme",state.optString("imeIsolation").equals("available")?Boolean.TRUE:state.optString("imeIsolation").equals("unavailable")?Boolean.FALSE:JSONObject.NULL);
        } catch (Exception unavailable) {
            facts.put("backgroundConnected", JSONObject.NULL).put("backgroundSessionReady", JSONObject.NULL).put("backgroundClean", JSONObject.NULL);
        }
        // A reconnection during the read cannot inherit the old service's positive permission facts.
        if (service != PhoneAccessibilityService.get()) {
            facts.put("serviceConnected", false).put("mainServiceSessionId", JSONObject.NULL)
                    .put("windowContent", JSONObject.NULL).put("interactiveWindows", JSONObject.NULL)
                    .put("gestureCapability", JSONObject.NULL).put("screenshotCapability", JSONObject.NULL).put("ownerControlsVisible", false);
        }
        return PhoneReadiness.evaluate(facts);
    }
    private static Boolean serviceEnabled(Context context) throws Exception {
        int accessibility = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        if (accessibility == 0) return false;
        if (accessibility != 1) return null;
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return null;
        ComponentName own = new ComponentName(context, PhoneAccessibilityService.class);
        for (String entry : enabled.split(":")) if (own.equals(ComponentName.unflattenFromString(entry))) return true;
        return false;
    }
    private static void fact(JSONObject facts, String key, Probe probe) throws Exception {
        Object value; try { value = probe.read(); } catch (Exception unknown) { value = JSONObject.NULL; }
        facts.put(key, value == null ? JSONObject.NULL : value);
    }
}
