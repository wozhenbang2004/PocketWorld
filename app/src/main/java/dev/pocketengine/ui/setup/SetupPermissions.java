package dev.pocketengine.ui.setup;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.accessibility.AccessibilityManager;
import dev.pocketengine.core.RuntimeDiagnostics;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** System observations and explicit owner actions. It never records audio or enables an app feature. */
final class SetupPermissions {
    private static final int MICROPHONE_REQUEST = 7251;
    private static final int NOTIFICATION_REQUEST = 7252;
    private final Activity activity;
    private final SetupPreferences preferences;

    SetupPermissions(Activity activity, SetupPreferences preferences) {
        this.activity = java.util.Objects.requireNonNull(activity);
        this.preferences = java.util.Objects.requireNonNull(preferences);
    }

    JSONObject read() throws Exception {
        return new JSONObject().put("accessibility", observe(this::accessibility))
                .put("notifications", observe(this::notifications))
                .put("microphone", observe(() -> granted(Manifest.permission.RECORD_AUDIO)))
                .put("batteryExempt", observe(() -> {
                    PowerManager manager = activity.getSystemService(PowerManager.class);
                    if (manager == null) throw new IllegalStateException("power_manager_unavailable");
                    return manager.isIgnoringBatteryOptimizations(activity.getPackageName());
                })).put("vendorBackgroundVisited", preferences.vendorBackgroundVisited());
    }

    JSONObject speech() throws Exception {
        // A registered system recognizer is not evidence of offline operation or language support.
        return new JSONObject().put("available", observe(() -> SpeechRecognizer.isRecognitionAvailable(activity)))
                .put("networkUse", "unknown");
    }

    JSONObject open(String kind) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("请从当前设置页面打开系统授权");
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus())
            throw new IllegalStateException("原设置窗口已离开前台，请返回后重新选择");
        String entry = "system_settings";
        switch (kind) {
            case "accessibility":
                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case "notifications":
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    if (preferences.permissionRequested(kind) && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                    } else {
                        preferences.markPermissionRequested(kind);
                        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                        entry = "permission_request";
                    }
                } else activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                break;
            case "microphone":
                if (!granted(Manifest.permission.RECORD_AUDIO)) {
                    if (preferences.permissionRequested(kind) && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                        openAppDetails(); entry = "app_details";
                    } else {
                        preferences.markPermissionRequested(kind);
                        activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
                        entry = "permission_request";
                    }
                } else { openAppDetails(); entry = "app_details"; }
                break;
            case "battery":
                // The user chooses the exemption in Android's own list; no automatic exemption request.
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                break;
            case "vendorBackground":
                // OEM auto-start APIs are not a stable permission contract. Open the app's real
                // system page and report only that entry visit, never a fabricated grant.
                openAppDetails(); preferences.markVendorBackgroundVisited(); entry = "app_details";
                break;
            default: throw new IllegalArgumentException("不支持这项系统授权入口");
        }
        return new JSONObject().put("kind", kind).put("opened", true).put("entry", entry)
                .put("grantVerified", false)
                .put("message", "vendorBackground".equals(kind)
                        ? "已打开应用详情；请按手机提供的入口核对自启动或后台运行。这里只记录访问，无法代替厂商开关的实际状态。"
                        : "已请求打开系统授权；返回后会重新读取实际状态。授权麦克风不会自动录音。");
    }

    private void openAppDetails() {
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }

    private boolean granted(String permission) { return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    private boolean notifications() {
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("notification_manager_unavailable");
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS));
    }

    private boolean accessibility() {
        AccessibilityManager manager = activity.getSystemService(AccessibilityManager.class);
        if (manager == null) throw new IllegalStateException("accessibility_manager_unavailable");
        ComponentName expected = new ComponentName(activity.getPackageName(), "dev.pocketengine.phone.PhoneAccessibilityService");
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (service == null || service.getResolveInfo() == null || service.getResolveInfo().serviceInfo == null) continue;
            android.content.pm.ServiceInfo info = service.getResolveInfo().serviceInfo;
            if (expected.equals(new ComponentName(info.packageName, info.name))) return true;
        }
        return false;
    }

    private Object observe(BooleanSupplier read) {
        try { return read.getAsBoolean(); }
        catch (RuntimeException unavailable) {
            RuntimeDiagnostics.event("diagnostics", "unconfirmed", "SETUP_PERMISSION_READ_UNAVAILABLE", null, unavailable);
            return JSONObject.NULL;
        }
    }
}
