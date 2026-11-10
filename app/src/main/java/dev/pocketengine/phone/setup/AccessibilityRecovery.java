package dev.pocketengine.phone.setup;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** A separately authorized, bounded repair of this application's existing registration.
 * Registration recovery never grants permission or resumes a business action. */
public final class AccessibilityRecovery {
    public static final String REGISTRATION_SCOPE = "restore_own_accessibility_registration";
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final Object CONSENT_LOCK = new Object();
    private static final AtomicBoolean RESTORING = new AtomicBoolean();
    private static final long CONNECTION_WAIT_MS = 2_500L;
    private static final long REBIND_GAP_MS = 800L;
    private static final int MAX_ATTEMPTS = 3;
    interface Platform {
        boolean unlocked();
        boolean permitted();
        boolean connected();
        String services();
        int enabled();
        default boolean writeServices(String value) { return false; }
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
                PowerManager power = app.getSystemService(PowerManager.class);
                return users != null && users.isUserUnlocked() && keyguard != null
                        && !keyguard.isDeviceLocked() && !keyguard.isKeyguardLocked()
                        && power != null && power.isInteractive();
            }
            public boolean permitted() { return app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED; }
            public boolean connected() { return PhoneAccessibilityService.get() != null; }
            public String services() { return Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
            public int enabled() { return Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0); }
            public boolean writeServices(String value) {
                return Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            }
        };
    }

    AccessibilityRecovery(SharedPreferences preferences, Platform platform, String own, int ownerUid) {
        this.preferences = Objects.requireNonNull(preferences);
        this.platform = Objects.requireNonNull(platform);
        this.own = Objects.requireNonNull(own);
        this.ownerUid = ownerUid;
    }

    public JSONObject setConsent(boolean enabled, long expectedRevision) throws Exception {
        return setConsent(enabled, expectedRevision, () -> true);
    }

    public JSONObject setConsent(boolean enabled, long expectedRevision, BooleanSupplier ownerCurrent) throws Exception {
        synchronized (CONSENT_LOCK) {
            requireOwnerRevision(expectedRevision, ownerCurrent);
            if (enabled && !platform.unlocked()) throw new SecurityException("owner_unlock_required");
            boolean previouslyEnabled = enabled && (platform.connected() || platform.enabled() == 1 && contains(read(), own));
            SharedPreferences.Editor next = preferences.edit().putBoolean("consent", enabled)
                    .putBoolean("accessibilityPreviouslyEnabled", enabled && previouslyEnabled)
                    .putInt("ownerUid", ownerUid).putLong("revision", Math.addExact(expectedRevision, 1))
                    .putInt("rebindAttempts", 0).putLong("nextRebindAt", 0)
                    .putString("lastOutcome", enabled ? "not_requested" : "recovery_consent_revoked");
            if (!enabled) next.putBoolean("registrationRestoreConsent", false).remove("registrationConsentScope");
            requireOwnerRevision(expectedRevision, ownerCurrent);
            commit(next, "recovery_consent_save_failed");
        }
        return snapshot();
    }

    public JSONObject setRegistrationConsent(boolean enabled, long expectedRevision, String scope,
                                             BooleanSupplier ownerCurrent) throws Exception {
        synchronized (CONSENT_LOCK) {
            requireOwnerRevision(expectedRevision, ownerCurrent);
            if (!REGISTRATION_SCOPE.equals(scope)) throw new SecurityException("registration_recovery_scope_required");
            if (enabled) {
                if (!consentCurrent(expectedRevision)) throw new SecurityException("consent_required");
                if (!platform.unlocked()) throw new SecurityException("owner_unlock_required");
                if (!platform.permitted()) throw new SecurityException("secure_settings_permission_missing");
                if (platform.enabled() != 1 || !contains(read(), own)) throw new SecurityException("initial_accessibility_authorization_required");
            }
            requireOwnerRevision(expectedRevision, ownerCurrent);
            commit(preferences.edit().putBoolean("registrationRestoreConsent", enabled)
                    .putString("registrationConsentScope", enabled ? REGISTRATION_SCOPE : "")
                    .putInt("ownerUid", ownerUid).putLong("revision", Math.addExact(expectedRevision, 1))
                    .putInt("rebindAttempts", 0).putLong("nextRebindAt", 0)
                    .putString("lastOutcome", enabled ? "registration_consent_enabled" : "registration_consent_revoked"),
                    "recovery_consent_save_failed");
        }
        return snapshot();
    }

    public JSONObject snapshot() throws Exception {
        synchronized (CONSENT_LOCK) {
            boolean permitted = platform.permitted(), unlocked = platform.unlocked(), connected = platform.connected();
            boolean registrationConsent = registrationConsentCurrent(revision());
            boolean pending = pending(), registered = contains(read(), own);
            String last = preferences.getString("lastOutcome", "not_requested");
            return new JSONObject().put("consent", preferences.getBoolean("consent", false))
                    .put("revision", revision()).put("secureSettingsGranted", permitted)
                    .put("userUnlocked", unlocked).put("accessibilityConnected", connected)
                    .put("systemRegistered", registered).put("accessibilityEnabled", platform.enabled() == 1)
                    .put("accessibilityPreviouslyEnabled", preferences.getBoolean("accessibilityPreviouslyEnabled", false))
                    .put("registrationRestoreConsent", registrationConsent).put("registrationConsentScope", REGISTRATION_SCOPE)
                    .put("automaticRegistrationRestoreSupported", permitted)
                    .put("registrationRestoreAvailable", registrationConsent && permitted && unlocked && registered && !pending)
                    .put("compensationPending", pending).put("registrationRestorePending", registrationPending())
                    .put("registrationPendingPhase", registrationPending() ? preferences.getString("registrationPendingPhase", "unknown") : "none")
                    .put("registrationPendingAt", registrationPending() ? preferences.getLong("registrationPendingAt", 0) : 0)
                    .put("settingsMutation", preferences.getBoolean("lastSettingsMutation", false))
                    .put("settingsUpdateAtomic", false).put("needsOwnerAction", pending || !connected)
                    .put("ownerAction", "open_system_accessibility_settings")
                    .put("lastOutcome", last).put("message", RESTORING.get() && pending
                            ? "正在核对本次服务登记，尚未确认完成。" : message(last, pending))
                    .put("rebindAttempts", preferences.getInt("rebindAttempts", 0)).put("rebindAttemptLimit", MAX_ATTEMPTS)
                    .put("retryAfterMillis", retryDelayLocked())
                    .put("restoresBusinessActions", false).put("changesWirelessDebugging", false)
                    .put("grantsSystemPermission", false).put("busy", RESTORING.get());
        }
    }

    /** SharedPreferences exposes this committed in-memory value without a settings Binder read. */
    public long currentRevision() { return revision(); }

    public boolean consentCurrent(long expectedRevision) {
        synchronized (CONSENT_LOCK) {
            return preferences.getBoolean("consent", false) && revision() == expectedRevision
                    && preferences.getInt("ownerUid", -1) == ownerUid;
        }
    }

    private boolean registrationConsentCurrent(long expectedRevision) {
        return consentCurrent(expectedRevision) && preferences.getBoolean("registrationRestoreConsent", false)
                && REGISTRATION_SCOPE.equals(preferences.getString("registrationConsentScope", ""));
    }

    public JSONObject restore() throws Exception { return restore(() -> true); }

    public JSONObject restore(BooleanSupplier current) throws Exception {
        if (!RESTORING.compareAndSet(false, true)) return snapshot().put("lastOutcome", "recovery_busy");
        Attempt attempt = new Attempt(currentRevision());
        String state;
        try {
            try { state = attempt(attempt, current); }
            catch (Stopped stopped) { state = stopped.state; }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); state = "recovery_interrupted"; }
            catch (Exception unavailable) { state = "platform_refused"; }
            finally { finishRemoval(attempt, current); }
            synchronized (CONSENT_LOCK) {
                if (revision() == attempt.revision) {
                    SharedPreferences.Editor next = preferences.edit().putString("lastOutcome", state);
                    if ("registered_waiting_for_binding".equals(state)) next.putLong("nextRebindAt", System.currentTimeMillis() + backoff());
                    commit(next, "recovery_result_save_failed");
                }
            }
        } finally { RESTORING.set(false); }
        return snapshot().put("attemptOutcome", state).put("registrationVerified", contains(read(), own))
                .put("registrationObservationOnly", !attempt.writeStarted).put("bindingVerified", platform.connected());
    }

    private String attempt(Attempt attempt, BooleanSupplier current) throws Exception {
        long revision = attempt.revision;
        if (!consentCurrent(revision)) return "consent_required";
        requireCurrent(revision, current);
        reconcilePending();
        if (pending()) return "recovery_pending_owner_action";
        if (platform.connected()) { resetAttempts(revision); return "already_connected"; }
        requireRegistered();
        if (waitForBinding(revision, current, false)) { resetAttempts(revision); return "system_reconnected"; }
        if (!registrationConsentCurrent(revision)) return "owner_settings_required";
        if (!platform.permitted()) return "secure_settings_permission_missing";
        synchronized (CONSENT_LOCK) {
            if (preferences.getInt("rebindAttempts", 0) >= MAX_ATTEMPTS) return "recovery_retry_limit";
            if (retryDelayLocked() > 0) return "recovery_backoff";
        }
        removeOwn(attempt, current);
        if (!attempt.removed) return platform.connected() ? "system_reconnected" : "registration_remove_unconfirmed";
        long deadline = SystemClock.elapsedRealtime() + REBIND_GAP_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            requireWriteCurrent(revision, current);
            if (!attempt.without.equals(encoded())) throw new Stopped("registration_settings_changed");
            requireRemovalEnabled(attempt);
            Thread.sleep(Math.min(50L, Math.max(1L, deadline - SystemClock.elapsedRealtime())));
        }
        restoreOwn(attempt, current);
        if (registrationPending()) return "registration_restore_unconfirmed";
        if (waitForBinding(revision, current, true)) { resetAttempts(revision); return "registration_reconnected"; }
        if (platform.enabled() != 1) return "registration_binding_unconfirmed";
        return preferences.getInt("rebindAttempts", 0) >= MAX_ATTEMPTS ? "recovery_retry_limit" : "registered_waiting_for_binding";
    }

    private void removeOwn(Attempt attempt, BooleanSupplier current) throws Exception {
        synchronized (CONSENT_LOCK) {
            requireWriteCurrent(attempt.revision, current);
            requireRegistered();
            if (platform.connected()) return;
            String before = encoded();
            if (!contains(entries(before), own)) throw new Stopped("owner_accessibility_disabled");
            attempt.without = withoutOwn(before);
            // SettingsProvider has no compare-and-set for this key. Compare again immediately
            // before each write, retain every unrelated entry, and never restore an old list.
            if (!before.equals(encoded()) || platform.enabled() != 1) throw new Stopped("registration_settings_changed");
            requireWriteCurrent(attempt.revision, current);
            commit(preferences.edit().putBoolean("registrationPending", true)
                    .putString("registrationPendingComponent", own).putInt("registrationPendingUid", ownerUid)
                    .putLong("registrationPendingRevision", attempt.revision).putLong("registrationPendingAt", System.currentTimeMillis())
                    .putString("registrationPendingPhase", "prepared")
                    .putInt("rebindAttempts", preferences.getInt("rebindAttempts", 0) + 1)
                    .putLong("nextRebindAt", System.currentTimeMillis() + backoff())
                    .putBoolean("lastSettingsMutation", false), "recovery_record_save_failed");
            attempt.recorded = true;
            requireWriteCurrent(attempt.revision, current);
            if (!before.equals(encoded()) || platform.enabled() != 1) throw new Stopped("registration_settings_changed");
            if (platform.connected()) return;
            commit(preferences.edit().putString("registrationPendingPhase", "remove_started").putBoolean("lastSettingsMutation", true), "recovery_record_save_failed");
            requireWriteCurrent(attempt.revision, current);
            if (!before.equals(encoded()) || platform.enabled() != 1) throw new Stopped("registration_settings_changed");
            requireWriteCurrent(attempt.revision, current);
            attempt.writeStarted = true;
            boolean accepted = platform.writeServices(attempt.without);
            String actual = encoded();
            attempt.removed = accepted && attempt.without.equals(actual);
            if (!attempt.removed) throw new Stopped("registration_remove_unconfirmed");
            commit(preferences.edit().putString("registrationPendingPhase", "removed"), "recovery_record_save_failed");
        }
    }

    private void restoreOwn(Attempt attempt, BooleanSupplier current) throws Exception {
        synchronized (CONSENT_LOCK) {
            String now = encoded();
            if (contains(entries(now), own)) { clearRegistrationPending(attempt.revision); return; }
            if (!attempt.removed || attempt.addAttempted) return;
            requireWriteCurrent(attempt.revision, current);
            if (!attempt.without.equals(now)) throw new Stopped("registration_settings_changed");
            // Removing the last service makes Android derive ACCESSIBILITY_ENABLED=0. Only
            // this live, verified removal may add our component back through that transition.
            // A durable pending record is evidence for the owner, never a replacement ticket.
            if (!now.equals(encoded())) throw new Stopped("registration_settings_changed");
            requireRemovalEnabled(attempt);
            requireWriteCurrent(attempt.revision, current);
            commit(preferences.edit().putString("registrationPendingPhase", "readd_started"), "recovery_record_save_failed");
            requireWriteCurrent(attempt.revision, current);
            if (!now.equals(encoded())) throw new Stopped("registration_settings_changed");
            requireRemovalEnabled(attempt);
            requireWriteCurrent(attempt.revision, current);
            attempt.addAttempted = true;
            platform.writeServices(now.isEmpty() ? own : now + ":" + own);
            if (!contains(read(), own)) throw new Stopped("registration_restore_unconfirmed");
            clearRegistrationPending(attempt.revision);
        }
    }

    private void finishRemoval(Attempt attempt, BooleanSupplier current) {
        if (!attempt.recorded) return;
        synchronized (CONSENT_LOCK) {
            try {
                if (!attempt.writeStarted || contains(read(), own)) clearRegistrationPending(attempt.revision);
                else if (attempt.removed && !attempt.addAttempted) restoreOwn(attempt, current);
            } catch (Exception unresolved) {
                // Revocation, locking, changed settings or an unconfirmed write leave the
                // exact record visible. Cleanup cannot recreate authorization or replay it.
            }
        }
    }

    private boolean waitForBinding(long revision, BooleanSupplier current, boolean registrationJustWritten) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + CONNECTION_WAIT_MS;
        do {
            requireCurrent(revision, current);
            if (platform.connected()) return true;
            if (!contains(read(), own) || !registrationJustWritten && platform.enabled() != 1)
                throw new Stopped("owner_accessibility_disabled");
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) return false;
            Thread.sleep(Math.min(100L, remaining));
        } while (true);
    }

    /** Read back a pending removal after the owner has used system settings. Never replays it. */
    public JSONObject compensate() throws Exception {
        if (!RESTORING.compareAndSet(false, true)) return snapshot().put("lastOutcome", "recovery_busy");
        String state;
        try { synchronized (CONSENT_LOCK) {
            if (!pending()) state = "no_compensation_pending";
            else { reconcilePending(); state = pending() ? "compensation_pending" : "original_registration_restored"; }
            commit(preferences.edit().putString("lastOutcome", state), "recovery_result_save_failed");
        } } finally { RESTORING.set(false); }
        return snapshot().put("registrationObservationOnly", true).put("bindingVerified", platform.connected());
    }

    private void reconcilePending() {
        synchronized (CONSENT_LOCK) {
            if (!platform.unlocked() || !contains(read(), own)) return;
            if (registrationPending() && own.equals(preferences.getString("registrationPendingComponent", ""))
                    && ownerUid == preferences.getInt("registrationPendingUid", -1))
                clearRegistrationPending(preferences.getLong("registrationPendingRevision", -1));
            if (preferences.getBoolean("compensationPending", false)
                    && own.equals(preferences.getString("compensationComponent", ""))
                    && ownerUid == preferences.getInt("compensationUid", -1))
                commit(preferences.edit().remove("compensationPending").remove("compensationComponent").remove("compensationUid"), "recovery_record_save_failed");
        }
    }

    private void clearRegistrationPending(long revision) {
        if (!registrationPending() || preferences.getLong("registrationPendingRevision", -1) != revision
                || ownerUid != preferences.getInt("registrationPendingUid", -1)
                || !own.equals(preferences.getString("registrationPendingComponent", ""))) return;
        commit(preferences.edit().remove("registrationPending").remove("registrationPendingComponent")
                .remove("registrationPendingUid").remove("registrationPendingRevision")
                .remove("registrationPendingPhase").remove("registrationPendingAt"), "recovery_record_save_failed");
    }

    private void requireOwnerRevision(long expected, BooleanSupplier current) {
        if (expected < 0 || expected != revision() || !current.getAsBoolean()) throw new SecurityException("recovery_consent_changed");
    }
    private void requireCurrent(long revision, BooleanSupplier current) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("recovery_interrupted");
        if (!current.getAsBoolean()) throw new Stopped("recovery_cancelled");
        if (!consentCurrent(revision)) throw new Stopped("recovery_consent_revoked");
        if (!platform.unlocked()) throw new Stopped("owner_unlock_required");
    }
    private void requireWriteCurrent(long revision, BooleanSupplier current) throws InterruptedException {
        requireCurrent(revision, current);
        if (!registrationConsentCurrent(revision)) throw new Stopped("registration_consent_revoked");
        if (!platform.permitted()) throw new Stopped("secure_settings_permission_missing");
    }
    private void requireRegistered() {
        if (platform.enabled() != 1 || !contains(read(), own)) throw new Stopped("owner_accessibility_disabled");
    }
    private void requireRemovalEnabled(Attempt attempt) {
        int enabled = platform.enabled();
        if (enabled == 1) return;
        if (enabled == 0 && attempt.removed && entries(attempt.without).stream().allMatch(String::isEmpty)) return;
        throw new Stopped("owner_accessibility_disabled");
    }
    private void resetAttempts(long expectedRevision) {
        synchronized (CONSENT_LOCK) {
            if (expectedRevision == revision()) commit(preferences.edit().putInt("rebindAttempts", 0).putLong("nextRebindAt", 0), "recovery_result_save_failed");
        }
    }
    private long backoff() { return preferences.getInt("rebindAttempts", 0) < 2 ? 5_000L : 15_000L; }
    private long retryDelayLocked() { return Math.min(30_000L, Math.max(0L, preferences.getLong("nextRebindAt", 0) - System.currentTimeMillis())); }
    private boolean registrationPending() { return preferences.getBoolean("registrationPending", false); }
    private boolean pending() { return registrationPending() || preferences.getBoolean("compensationPending", false); }
    private long revision() { return preferences.getLong("revision", 0); }
    private String encoded() {
        String value = platform.services();
        if (value != null && value.length() > 65_536) throw new SecurityException("accessibility_registration_unreadable");
        return value == null ? "" : value;
    }
    private List<String> read() { return entries(encoded()); }
    private static List<String> entries(String encoded) {
        List<String> result = new ArrayList<>();
        if (!encoded.isEmpty()) for (String entry : encoded.split(":", -1)) result.add(entry);
        return result;
    }
    private String withoutOwn(String before) {
        List<String> others = new ArrayList<>();
        for (String entry : entries(before)) if (!same(entry, own)) others.add(entry);
        return String.join(":", others);
    }
    private static boolean contains(List<String> entries, String own) { return entries.stream().anyMatch(value -> same(value, own)); }
    private static boolean same(String left, String right) {
        ComponentName a = ComponentName.unflattenFromString(left), b = ComponentName.unflattenFromString(right);
        return a != null && a.equals(b);
    }
    private static void commit(SharedPreferences.Editor editor, String error) { if (!editor.commit()) throw new IllegalStateException(error); }
    private static final class Stopped extends SecurityException {
        final String state;
        Stopped(String state) { super(state); this.state = state; }
    }
    private static final class Attempt {
        final long revision;
        String without;
        boolean recorded, writeStarted, removed, addAttempted;
        Attempt(long revision) { this.revision = revision; }
    }

    private static String message(String state, boolean pending) {
        if (pending) return "本次服务登记复原尚未确认；已停止自动改写，请在系统无障碍设置中核对 PocketWorld，再读取原记录。";
        switch (state) {
            case "registration_consent_enabled": return "已允许在失连时有限重绑定 PocketWorld；只对仍有效的系统登记生效。";
            case "registration_consent_revoked": return "服务登记恢复已关闭，后续系统设置写入已停止。";
            case "registration_reconnected": return "PocketWorld 服务已重新绑定；原任务不会自动重放。";
            case "already_connected": case "system_reconnected": return "界面读取服务当前已连接。";
            case "registered_waiting_for_binding": return "已读回 PocketWorld 服务登记，系统绑定尚未确认，将按有限退避继续核对。";
            case "registration_binding_unconfirmed": return "已读回 PocketWorld 服务登记，系统绑定仍未确认；不会继续改写，请在系统设置中核对。";
            case "recovery_backoff": return "正在等待下一次有限恢复窗口，关闭恢复可立即停止后续尝试。";
            case "recovery_retry_limit": return "本次有限恢复已用尽，请在系统无障碍设置中核对，不会继续循环改写。";
            case "secure_settings_permission_missing": return "未授予安全系统设置权限，自动重绑定不可用；请在系统无障碍设置中手动恢复。";
            case "owner_accessibility_disabled": case "initial_accessibility_authorization_required": return "当前系统登记或启用状态已变化，请在系统无障碍设置中明确启用 PocketWorld。";
            case "owner_unlock_required": return "设备已锁定或息屏，本次恢复已停止；解锁后可重新核对。";
            case "recovery_consent_revoked": case "consent_required": return "原连接恢复同意已关闭或变化，后续尝试已停止。";
            case "recovery_cancelled": case "recovery_interrupted": return "本次恢复已停止，未恢复任何任务。";
            case "registration_settings_changed": return "系统服务列表已变化，本次未覆盖新的选择；请在系统设置中核对。";
            case "owner_settings_required": return "服务登记仍在，但尚未连接；可在系统设置中重新启用，或单独允许服务登记恢复。";
            case "original_registration_restored": return "已读回原服务登记，实际绑定仍需系统确认。";
            case "platform_refused": case "registration_remove_unconfirmed": case "registration_restore_unconfirmed": return "系统未确认本次恢复，请在无障碍设置中核对。";
            default: return "原连接核对和系统登记恢复分别授权，均可随时关闭。";
        }
    }
}
