package dev.pocketengine.phone.setup;

import android.content.ComponentName;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.provider.Settings;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Revocable reconciliation of a previously enabled service. Never grants a system permission
 * or replays a task. Settings.Secure.putString has no conditional-update argument:
 * rewriting its whole value can lose another service's concurrent change. Only the system
 * settings UI may change registrations; this class keeps and reconciles legacy undo evidence. */
public final class AccessibilityRecovery {
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final Object CONSENT_LOCK = new Object();
    private static final AtomicBoolean RESTORING = new AtomicBoolean();
    interface Platform {
        boolean unlocked();
        boolean permitted();
        boolean connected();
        String services();
        int enabled();
    }
    private final SharedPreferences preferences;
    private final Platform platform;
    private final String own;
    private final int ownerUid;

    public AccessibilityRecovery(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences("phone_connection_recovery", Context.MODE_PRIVATE);
        own = new ComponentName(app, PhoneAccessibilityService.class).flattenToString();
        ownerUid = android.os.Process.myUid();
        platform = new Platform() {
            public boolean unlocked() {
                UserManager users = app.getSystemService(UserManager.class);
                KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
                return users != null && users.isUserUnlocked() && keyguard != null && !keyguard.isDeviceLocked();
            }
            public boolean permitted() { return app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED; }
            public boolean connected() { return PhoneAccessibilityService.get() != null; }
            public String services() { return Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
            public int enabled() { return Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0); }
        };
    }

    AccessibilityRecovery(SharedPreferences preferences, Platform platform, String own, int ownerUid) {
        this.preferences = Objects.requireNonNull(preferences);
        this.platform = Objects.requireNonNull(platform);
        this.own = Objects.requireNonNull(own);
        this.ownerUid = ownerUid;
    }

    public JSONObject setConsent(boolean enabled, long expectedRevision) throws Exception {
        synchronized (CONSENT_LOCK) {
            if (expectedRevision != revision()) throw new SecurityException("recovery_consent_changed");
            boolean previouslyEnabled = enabled && (platform.connected() || platform.enabled() == 1 && contains(read(), own));
            if (!preferences.edit().putBoolean("consent", enabled)
                    .putBoolean("accessibilityPreviouslyEnabled", enabled && previouslyEnabled)
                    .putInt("ownerUid", ownerUid).putLong("revision", Math.addExact(expectedRevision, 1)).commit())
                throw new IllegalStateException("recovery_consent_save_failed");
        }
        return snapshot();
    }

    public JSONObject snapshot() throws Exception {
        synchronized (CONSENT_LOCK) {
            return new JSONObject().put("consent", preferences.getBoolean("consent", false))
                    .put("revision", revision()).put("secureSettingsGranted", platform.permitted())
                    .put("userUnlocked", platform.unlocked()).put("accessibilityConnected", platform.connected())
                    .put("accessibilityPreviouslyEnabled", preferences.getBoolean("accessibilityPreviouslyEnabled", false))
                    .put("compensationPending", preferences.getBoolean("compensationPending", false))
                    .put("automaticRegistrationRestoreSupported", false).put("settingsMutation", false)
                    .put("needsOwnerAction", preferences.getBoolean("compensationPending", false) || !platform.connected())
                    .put("ownerAction", "open_system_accessibility_settings")
                    .put("lastOutcome", preferences.getString("lastOutcome", "not_requested"))
                    .put("restoresBusinessActions", false).put("changesWirelessDebugging", false)
                    .put("grantsSystemPermission", false).put("busy", RESTORING.get());
        }
    }

    public boolean consentCurrent(long expectedRevision) {
        synchronized (CONSENT_LOCK) {
            return preferences.getBoolean("consent", false) && revision() == expectedRevision
                    && preferences.getInt("ownerUid", -1) == ownerUid;
        }
    }

    public JSONObject restore() throws Exception { return restore(() -> true); }

    public JSONObject restore(BooleanSupplier current) throws Exception {
        if (!RESTORING.compareAndSet(false, true)) return snapshot().put("lastOutcome", "recovery_busy");
        JSONObject result;
        try { result = restoreAttempt(current); }
        finally { RESTORING.set(false); }
        return result.put("busy", false);
    }

    private JSONObject restoreAttempt(BooleanSupplier current) throws Exception {
        try {
            long consentRevision = revision();
            if (!consentCurrent(consentRevision)) return outcome("consent_required", false);
            if (!platform.unlocked()) return outcome("owner_unlock_required", false);
            requireCurrent(consentRevision, current);
            if (platform.connected()) return outcome("already_connected", true);
            if (preferences.getBoolean("compensationPending", false)) return outcome("recovery_pending_owner_action", false);
            if (!preferences.getBoolean("accessibilityPreviouslyEnabled", false)) return outcome("initial_accessibility_authorization_required", false);
            // A system-wide switch turned off by the owner is not an invitation to turn it on again.
            if (platform.enabled() != 1) return outcome("owner_accessibility_disabled", false);

            List<String> entries = read();
            // An absent entry may be a later owner revocation. Earlier consent cannot distinguish
            // that from an OEM clearing it, so only rebind a registration that is still present.
            if (!contains(entries, own)) return outcome("owner_accessibility_disabled", false);
            requireCurrent(consentRevision, current);
            // A fresh read plus putString is not a conditional system update.
            // Do not remove/re-add our entry, even with WRITE_SECURE_SETTINGS.
            return outcome("owner_settings_required", true);
        } catch (SecurityException revoked) {
            return outcome("recovery_consent_revoked", false);
        } catch (Exception unavailable) {
            return outcome("platform_refused", false);
        }
    }

    /** Reconcile an old release's undo record after the owner has used system settings.
     * A missing entry stays pending; no whole-list write or replay is permitted. */
    public JSONObject compensate() throws Exception {
        if (!preferences.getBoolean("compensationPending", false)) return snapshot().put("lastOutcome", "no_compensation_pending");
        if (!RESTORING.compareAndSet(false, true)) return snapshot().put("lastOutcome", "recovery_busy");
        boolean restored;
        try { restored = compensateOriginal(); }
        finally { RESTORING.set(false); }
        return outcome(restored ? "original_registration_restored" : "compensation_pending", restored);
    }

    private boolean compensateOriginal() {
        synchronized (CONSENT_LOCK) {
            if (!preferences.getBoolean("compensationPending", false)) return true;
            if (!own.equals(preferences.getString("compensationComponent", ""))
                    || ownerUid != preferences.getInt("compensationUid", -1)) return false;
            try {
                if (!platform.unlocked()) return false;
                List<String> current = read();
                if (!contains(current, own)) return false;
                return preferences.edit().remove("compensationPending").remove("compensationComponent").remove("compensationUid").commit();
            } catch (Exception pending) { return false; }
        }
    }

    private void requireCurrent(long revision, BooleanSupplier current) {
        if (!current.getAsBoolean() || !consentCurrent(revision)) throw new SecurityException("recovery_consent_revoked");
        if (!platform.unlocked())
            throw new SecurityException("recovery_platform_changed");
    }

    private long revision() { return preferences.getLong("revision", 0); }
    private List<String> read() {
        String encoded = platform.services();
        List<String> result = new ArrayList<>();
        if (encoded != null && !encoded.isEmpty()) for (String entry : encoded.split(":"))
            if (!entry.isEmpty()) result.add(entry);
        return result;
    }
    private static boolean contains(List<String> entries, String own) { return entries.stream().anyMatch(value -> same(value, own)); }
    private static boolean same(String left, String right) {
        ComponentName a = ComponentName.unflattenFromString(left), b = ComponentName.unflattenFromString(right);
        return a != null && a.equals(b);
    }
    private JSONObject outcome(String state, boolean registered) throws Exception {
        synchronized (CONSENT_LOCK) {
            if (!preferences.edit().putString("lastOutcome", state).commit()) throw new IllegalStateException("recovery_result_save_failed");
        }
        return snapshot().put("registrationVerified", registered).put("registrationObservationOnly", true).put("bindingVerified", platform.connected());
    }
}
