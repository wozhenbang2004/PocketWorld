package dev.pocketengine.phone.setup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.display.adb.LocalAdbEndpoints;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Native owner setup only. Its commands must never be registered as model, channel or remote tools. */
public final class PhoneSetupModule implements EngineModule {
    static final String SUBMIT = "dev.pocketengine.PAIR_CODE";
    static final String CANCEL = "dev.pocketengine.CANCEL_PAIR_CODE";
    static final String CODE = "pairing_code";
    static final String SESSION = "pairingSessionId";
    static final int NOTICE = 42031;
    private static final String CHANNEL = "phone_pairing_input";
    private static volatile PhoneSetupModule instance;
    private final FeatureContext context;
    private final EngineModule display;
    private final AccessibilityRecovery recovery;
    private final PairingEntrySession pairing;
    private final Handler main;
    private final Executor worker;
    private final Object pairingLock = new Object();
    private final AtomicBoolean recoveryQueued = new AtomicBoolean();
    private final AtomicBoolean recoveryRunning = new AtomicBoolean();
    private final Object recoveryLock = new Object();
    private final Object recoveryCallbacks = new Object();
    private volatile RecoveryWork recoveryWork;
    private volatile long ownerStoppedRevision = -1;
    private final PhoneRecoveryReceiver unlockReceiver = new PhoneRecoveryReceiver();
    private boolean lifecycleSignalsRegistered;
    private PendingIntent replyIntent, cancelIntent;
    private volatile boolean closed;
    private volatile String lastPairingOutcome = "not_requested";
    private record AutoCapture(String id, String sourceIdentity, long expiresAt, long generation) { }
    private volatile AutoCapture autoCapture;
    private volatile long autoCaptureGeneration;
    private volatile String lastConnectionOutcome = "not_requested";

    public PhoneSetupModule(FeatureContext context, DisplayModule display) {
        this(context, display, new AccessibilityRecovery(context.android),
                new PairingEntrySession(SystemClock::elapsedRealtime), new Handler(Looper.getMainLooper()),
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4),
                        runnable -> new Thread(runnable, "phone-owner-setup"), new ThreadPoolExecutor.AbortPolicy()));
    }

    PhoneSetupModule(FeatureContext context, EngineModule display, AccessibilityRecovery recovery,
                     PairingEntrySession pairing, Handler main, Executor worker) {
        this.context = Objects.requireNonNull(context);
        this.display = Objects.requireNonNull(display);
        this.recovery = Objects.requireNonNull(recovery);
        this.pairing = Objects.requireNonNull(pairing);
        this.main = Objects.requireNonNull(main);
        this.worker = Objects.requireNonNull(worker);
        instance = this;
        IntentFilter unlock = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        unlock.addAction(Intent.ACTION_USER_PRESENT);
        unlock.addAction(Intent.ACTION_SCREEN_OFF);
        unlock.addAction(Intent.ACTION_USER_BACKGROUND);
        try {
            // USER_UNLOCKED is registered-receiver-only on Android; a manifest filter never receives it.
            if (Build.VERSION.SDK_INT >= 33) context.android.registerReceiver(unlockReceiver, unlock, Context.RECEIVER_NOT_EXPORTED);
            else context.android.registerReceiver(unlockReceiver, unlock);
            lifecycleSignalsRegistered = true;
        } catch (RuntimeException unavailable) { lifecycleSignalsRegistered = false; }
        // A cold process can start after the boot/unlock broadcast. Queue the same consent-
        // checked connection inspection once the module has finished publishing its state.
        main.postAtTime(() -> recoverAfterLifecycle(() -> { }), this, SystemClock.uptimeMillis());
    }

    static PhoneSetupModule current() { return instance; }
    @Override public String id() { return "phone_setup"; }

    @Override public JSONObject snapshot() throws Exception {
        JSONObject audio;
        try { audio=AndroidPhoneAudioRecovery.get(context.android).snapshot().put("available",true); }
        catch(Exception unavailable) { audio=new JSONObject().put("available",false); }
        RecoveryWork activeRecovery = recoveryWork;
        synchronized (pairingLock) {
            JSONObject entry = pairing.snapshot();
            if (!entry.optBoolean("active") && "awaiting_owner_code".equals(lastPairingOutcome)) {
                cancelNotice();
                lastPairingOutcome = "expired";
            }
            AutoCapture capture = autoCapture;
            if (capture != null && SystemClock.elapsedRealtime() >= capture.expiresAt()) {
                autoCapture = null;
                autoCaptureGeneration++;
                lastPairingOutcome = "auto_capture_expired";
                capture = null;
            }
            JSONObject visibleCapture = new JSONObject().put("active", capture != null)
                    .put("remainingMs", capture == null ? 0L : Math.max(0L, capture.expiresAt() - SystemClock.elapsedRealtime()))
                    .put("storesPairingCode", false);
            return new JSONObject().put("pairing", entry.put("lastOutcome", lastPairingOutcome)
                    .put("autoCapture", visibleCapture))
                    .put("recovery", recovery.snapshot().put("connectionOutcome", lastConnectionOutcome)
                            .put("busy", recoveryRunning.get() || recoveryQueued.get())
                            .put("retryScheduled", activeRecovery != null && activeRecovery.scheduled)
                            .put("attemptsStopped", ownerStoppedRevision == recovery.currentRevision())
                            .put("unlockSignalsAvailable", lifecycleSignalsRegistered))
                    .put("audioRecovery",audio).put("modelCommandsAvailable", false);
        }
    }

    @Override public JSONObject handle(String command, JSONObject input) throws Exception {
        if ("recovery.registration.consent".equals(command) || "pairing.observe".equals(command)) throw new SecurityException("owner_recovery_window_required");
        return handleOwner(command, input, () -> true);
    }

    public JSONObject handleForOwner(String command, JSONObject input, BooleanSupplier ownerCurrent) throws Exception {
        if (!ownerCurrent.getAsBoolean()) throw new SecurityException("owner_recovery_window_changed");
        return handleOwner(command, input, ownerCurrent);
    }

    private JSONObject handleOwner(String command, JSONObject input, BooleanSupplier ownerCurrent) throws Exception {
        requireOpen();
        if (input == null) input = new JSONObject();
        switch (command) {
            case "status": return snapshot();
            case "pairing.begin": return begin(input);
            case "pairing.observe":
                if (!ownerCurrent.getAsBoolean()) throw new SecurityException("owner_pairing_window_changed");
                return beginAutoCapture();
            case "pairing.cancel": cancel(input.optString(SESSION, null)); return snapshot();
            case "recovery.consent":
                recovery.setConsent(input.getBoolean("enabled"), input.getLong("expectedRevision"), ownerCurrent);
                stopRecovery();
                return snapshot();
            case "recovery.registration.consent":
                recovery.setRegistrationConsent(input.getBoolean("enabled"), input.getLong("expectedRevision"),
                        input.getString("scope"), ownerCurrent);
                stopRecovery();
                return snapshot();
            case "recovery.restore":
                context.requireExecutionEnabled();
                if (!ownerCurrent.getAsBoolean()) throw new SecurityException("owner_recovery_window_changed");
                ownerStoppedRevision = -1;
                recoverAfterLifecycle(() -> { });
                return snapshot();
            case "recovery.compensate":
                recovery.compensate();
                return snapshot();
            default: throw new IllegalArgumentException("phone_setup_command_unavailable");
        }
    }

    private JSONObject beginAutoCapture() throws Exception {
        synchronized (pairingLock) {
            requireOpen();
            synchronized (display) {
                JSONObject state = display.snapshot();
                requirePairingIdle(state);
                if (state.optJSONObject("adb") != null && state.getJSONObject("adb").optBoolean("identityPresent"))
                    throw new SecurityException("已存在本机无线授权身份，请直接恢复连接");
                AutoCapture current = autoCapture;
                if (current != null && SystemClock.elapsedRealtime() < current.expiresAt()) return snapshot();
                pairing.cancel(); cancelNotice();
                AutoCapture next = new AutoCapture(UUID.randomUUID().toString(),
                        sourceIdentity(state), SystemClock.elapsedRealtime() + PairingEntrySession.WINDOW_MS,
                        ++autoCaptureGeneration);
                autoCapture = next;
                lastPairingOutcome = "watching_system_pairing_window";
                main.postAtTime(() -> expireAutoCapture(next), this,
                        SystemClock.uptimeMillis() + PairingEntrySession.WINDOW_MS);
            }
            emit("system_pairing_capture_started");
            return snapshot();
        }
    }

    private void expireAutoCapture(AutoCapture captured) {
        synchronized (pairingLock) {
            if (autoCapture == captured && SystemClock.elapsedRealtime() >= captured.expiresAt()) {
                autoCapture = null;
                autoCaptureGeneration++;
                lastPairingOutcome = "auto_capture_expired";
                emit("system_pairing_capture_expired");
            }
        }
    }

    /** Called only by the connected accessibility service for a visible, system-owned Settings window. */
    public static boolean systemPairingCaptureActive() {
        PhoneSetupModule active = current();
        AutoCapture capture = active == null ? null : active.autoCapture;
        return capture != null && !active.closed && active.context.executionAllowed()
                && SystemClock.elapsedRealtime() < capture.expiresAt();
    }

    public static void offerVisibleSystemPairing(Context serviceContext, String packageName, List<String> labels) {
        PhoneSetupModule active = current();
        if (active == null || !systemPairingCaptureActive() ||
                !SystemWirelessPairing.trustedSettings(serviceContext, packageName)) return;
        SystemWirelessPairing.Credential observed = SystemWirelessPairing.parseVisibleText(labels);
        if (observed != null) active.submitObservedPairing(observed);
    }

    private void submitObservedPairing(SystemWirelessPairing.Credential observed) {
        final AutoCapture original;
        synchronized (pairingLock) {
            original = autoCapture;
            if (closed || original == null || !context.executionAllowed() ||
                    SystemClock.elapsedRealtime() >= original.expiresAt()) return;
            // Claim once before asynchronous dispatch. Never retry an unknown ADB effect.
            autoCapture = null;
            lastPairingOutcome = "system_pairing_claimed";
        }
        try {
            worker.execute(() -> {
                try {
                    String host = LocalAdbEndpoints.current().check(observed.host(), observed.port());
                    synchronized (pairingLock) {
                        if (closed || original.generation() != autoCaptureGeneration ||
                                !context.executionAllowed() ||
                                SystemClock.elapsedRealtime() >= original.expiresAt()) return;
                    }
                    synchronized (display) {
                        JSONObject state = display.snapshot();
                        requirePairingIdle(state);
                        if (!original.sourceIdentity().equals(sourceIdentity(state)) ||
                                original.generation() != autoCaptureGeneration || closed ||
                                !context.executionAllowed())
                            throw new SecurityException("pairing_source_changed");
                        JSONObject credentials = new JSONObject().put("host", host).put("pairingPort", observed.port())
                                .put("pairingCode", observed.code());
                        try { display.handle("adb.pair", credentials); }
                        finally { credentials.remove("pairingCode"); }
                    }
                    if (original.generation() == autoCaptureGeneration) {
                        lastPairingOutcome = "submitted_check_original_connection";
                        emit("system_pairing_submitted");
                    }
                } catch (Exception unknown) {
                    if (original.generation() == autoCaptureGeneration) {
                        lastPairingOutcome = "system_pairing_not_confirmed";
                        emit("system_pairing_unconfirmed");
                    }
                }
            });
        } catch (RuntimeException unavailable) {
            lastPairingOutcome = "system_pairing_worker_unavailable";
            emit("system_pairing_worker_unavailable");
        }
    }

    private JSONObject begin(JSONObject input) throws Exception {
        synchronized (pairingLock) {
            requireOpen();
            PairingEntrySession.Entry entry;
            synchronized (display) {
                JSONObject state = display.snapshot();
                requirePairingIdle(state);
                autoCapture = null; autoCaptureGeneration++;
                String host = LocalAdbEndpoints.current().check(input.optString("host", "127.0.0.1"), input.getInt("pairingPort"));
                entry = pairing.begin(host, input.getInt("pairingPort"), sourceIdentity(state));
            }
            try {
                show(entry);
                lastPairingOutcome = "awaiting_owner_code";
                long delay = Math.max(0, entry.expiresAt() - SystemClock.elapsedRealtime());
                main.postAtTime(() -> expire(entry.id()), this, SystemClock.uptimeMillis() + delay);
            } catch (Exception unavailable) {
                pairing.cancel(entry.id());
                cancelNotice();
                lastPairingOutcome = "notification_unavailable";
                throw unavailable;
            }
            return snapshot();
        }
    }

    private void submitCode(String session, String code) throws Exception {
        synchronized (pairingLock) {
            requireOpen();
            synchronized (display) {
                JSONObject entry = pairing.snapshot();
                if (!entry.optBoolean("active") || !session.equals(entry.optString("sessionId")))
                    throw new SecurityException("pairing_entry_expired");
                JSONObject state = display.snapshot();
                JSONObject request;
                try {
                    requirePairingIdle(state);
                    request = pairing.submit(session, code, sourceIdentity(state));
                } catch (Exception refused) {
                    // Only the current matching nonce can revoke its notice. Old callbacks leave a new window alone.
                    pairing.cancel(session);
                    cancelNotice();
                    lastPairingOutcome = "source_changed";
                    throw refused;
                }
                cancelNotice();
                try {
                    display.handle("adb.pair", request);
                    // Native ADB owns all asynchronous results. Pair accepted is not an authenticated helper.
                    lastPairingOutcome = "submitted_check_original_connection";
                } catch (Exception unknown) {
                    lastPairingOutcome = "pairing_effect_unknown_check_original";
                    throw unknown;
                } finally { request.remove("pairingCode"); }
            }
        }
    }

    void receiveNotification(String action, String session, String code, Runnable finished) {
        try {
            requireOpen();
            worker.execute(() -> {
                try {
                    if (CANCEL.equals(action)) cancel(session);
                    else if (SUBMIT.equals(action)) submitCode(session, code);
                    emit("pairing_entry_changed");
                } catch (Exception refused) {
                    emit("pairing_entry_refused");
                } finally { finished.run(); }
            });
        } catch (RuntimeException unavailable) { finished.run(); }
    }

    private void cancel(String expected) {
        synchronized (pairingLock) {
            if (expected == null) { autoCapture = null; autoCaptureGeneration++; pairing.cancel(); cancelNotice(); lastPairingOutcome = "cancelled"; }
            else if (pairing.cancel(expected)) { cancelNotice(); lastPairingOutcome = "cancelled"; }
        }
    }

    private void expire(String expected) {
        synchronized (pairingLock) {
            if (pairing.expire(expected)) {
                cancelNotice();
                lastPairingOutcome = "expired";
                emit("pairing_entry_expired");
            }
        }
    }

    private static void requirePairingIdle(JSONObject state) {
        JSONObject adb = state.optJSONObject("adb");
        if (state.optBoolean("taskOwned") || state.optBoolean("bootstrapBusy") || state.optBoolean("sessionActive")
                || state.optBoolean("releasePending") || state.optBoolean("recoveryRequired")
                || adb != null && adb.optBoolean("recoveryRequired"))
            throw new SecurityException("phone_setup_resource_busy");
        if (!state.optBoolean("supportedAndroid")) throw new SecurityException("phone_setup_platform_unsupported");
    }

    private static String sourceIdentity(JSONObject state) throws Exception {
        JSONObject adb = state.getJSONObject("adb");
        return state.getString("authorizationBackend") + ":" + state.getLong("setupRevision") + ":" + adb.getLong("generation")
                + ":" + state.optBoolean("configured") + ":" + state.optBoolean("helperConnected");
    }

    private void show(PairingEntrySession.Entry entry) throws Exception {
        NotificationManager manager = context.android.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) throw new SecurityException("pairing_notification_unavailable");
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "手机配对输码", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            throw new SecurityException("pairing_notification_channel_disabled");
        cancelNotice();
        Intent submit = notificationIntent(SUBMIT, entry.id());
        Intent cancel = notificationIntent(CANCEL, entry.id());
        replyIntent = PendingIntent.getBroadcast(context.android, NOTICE, submit, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
        cancelIntent = PendingIntent.getBroadcast(context.android, NOTICE + 1, cancel, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteInput remoteInput = new RemoteInput.Builder(CODE).setLabel("当前六位配对码").build();
        Notification.Action.Builder reply = new Notification.Action.Builder(android.R.drawable.ic_lock_lock, "输入配对码", replyIntent).addRemoteInput(remoteInput);
        if (Build.VERSION.SDK_INT >= 31) reply.setAuthenticationRequired(true);
        manager.notify(NOTICE, new Notification.Builder(context.android, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("在系统窗口保留配对码")
                .setContentText("在此输入当前六位码；两分钟后失效，配对后仍需核对连接。")
                .setVisibility(Notification.VISIBILITY_SECRET).setTimeoutAfter(Math.max(1, entry.expiresAt() - SystemClock.elapsedRealtime()))
                .setOnlyAlertOnce(true).setDeleteIntent(cancelIntent).addAction(reply.build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "取消输码", cancelIntent).build()).build());
    }

    private Intent notificationIntent(String action, String session) {
        return new Intent(context.android, PhoneSetupReceiver.class).setAction(action)
                .setData(Uri.parse("pocketworld-internal://pair/" + session)).putExtra(SESSION, session);
    }

    private void cancelNotice() {
        if (replyIntent != null) { replyIntent.cancel(); replyIntent = null; }
        if (cancelIntent != null) { cancelIntent.cancel(); cancelIntent = null; }
        NotificationManager manager = context.android.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTICE);
    }

    /** Called after the original accessibility service has invalidated its observations and cancelled its run. */
    public static void onAccessibilityDisconnected() {
        PhoneSetupModule module = current();
        if (module != null) module.recoverAfterLifecycle(() -> { });
    }

    /** A real binding resets an exhausted repair budget through the same native worker. */
    public static void onAccessibilityConnected() {
        PhoneSetupModule module = current();
        if (module != null) module.recoverAfterLifecycle(() -> { });
    }

    void recoverAfterLifecycle(Runnable finished) {
        if (closed || instance != this || !context.executionAllowed()) { finished.run(); return; }
        final long revision = recovery.currentRevision();
        if (ownerStoppedRevision == revision) { finished.run(); return; }
        RecoveryWork original;
        synchronized (recoveryLock) {
            if (closed || recoveryWork != null) { finished.run(); return; }
            original = new RecoveryWork(revision);
            recoveryWork = original;
            original.control.onCancel(() -> {
                synchronized (recoveryLock) {
                    if (recoveryWork == original) {
                        recoveryWork = null;
                        main.removeCallbacksAndMessages(recoveryCallbacks);
                        recoveryQueued.set(false);
                    }
                    if (original.thread != null) original.thread.interrupt();
                }
            });
        }
        enqueueRecovery(original, finished);
    }

    private void enqueueRecovery(RecoveryWork original, Runnable finished) {
        synchronized (recoveryLock) {
            if (recoveryWork != original || original.control.isCancelled() || closed) { finished.run(); return; }
            original.scheduled = false;
            recoveryQueued.set(true);
        }
        try {
            worker.execute(() -> {
                JSONObject result = null;
                synchronized (recoveryLock) {
                    if (recoveryWork == original) recoveryQueued.set(false);
                    original.thread = Thread.currentThread();
                }
                try {
                    if (!recoveryCurrent(original)) return;
                    if (!original.admitted) {
                        context.admitResourceExecution(original.control);
                        original.admitted = true;
                    }
                    original.control.check();
                    original.checks++;
                    recoveryRunning.set(true);
                    result = restoreConnection(original);
                } catch (Exception unavailable) {
                    if (recoveryWork == original) lastConnectionOutcome = "owner_connection_check_required";
                } finally {
                    String outcome = result == null ? "" : result.optString("lastOutcome");
                    boolean eligible = result != null && recoveryCurrent(original) && original.checks < 6
                            && ("registered_waiting_for_binding".equals(outcome) || "recovery_backoff".equals(outcome))
                            && result.optBoolean("registrationRestoreAvailable") && !result.optBoolean("accessibilityConnected")
                            && result.optBoolean("accessibilityEnabled")
                            && result.optInt("rebindAttempts") < result.optInt("rebindAttemptLimit", 3);
                    boolean retry = false;
                    synchronized (recoveryLock) {
                        original.thread = null;
                        recoveryRunning.set(false);
                        if (recoveryWork == original) {
                            retry = eligible && !original.control.isCancelled() && !closed
                                    && ownerStoppedRevision != original.revision && recovery.currentRevision() == original.revision;
                            if (retry) {
                                long delay = Math.max(250L, Math.min(30_000L, result.optLong("retryAfterMillis", 0)));
                                original.scheduled = main.postAtTime(() -> enqueueRecovery(original, () -> { }),
                                        recoveryCallbacks, SystemClock.uptimeMillis() + delay);
                                retry = original.scheduled;
                            }
                            if (!retry) recoveryWork = null;
                        }
                    }
                    if (!retry) original.control.cancel();
                    // The original single setup worker may next handle a pairing request.
                    Thread.interrupted();
                    finished.run();
                    emit("phone_recovery_checked");
                }
            });
        } catch (RuntimeException unavailable) {
            synchronized (recoveryLock) {
                if (recoveryWork == original) { recoveryWork = null; recoveryQueued.set(false); }
            }
            original.control.cancel();
            finished.run();
        }
    }

    private boolean recoveryCurrent(RecoveryWork original) {
        if (recoveryWork != original || ownerStoppedRevision == original.revision
                || closed || instance != this || !context.executionAllowed()
                || !recovery.consentCurrent(original.revision)) return false;
        try { original.control.check(); return true; }
        catch (Exception stopped) { return false; }
    }

    void stopRecovery() {
        RecoveryWork original;
        synchronized (recoveryLock) {
            original = recoveryWork;
            recoveryWork = null;
            recoveryQueued.set(false);
            main.removeCallbacksAndMessages(recoveryCallbacks);
        }
        if (original != null) original.control.cancel();
    }

    /** Stop the captured recovery immediately; persistence follows on the existing owner queue. */
    public void stopRecoveryForOwner(long expectedRevision, BooleanSupplier ownerCurrent) {
        RecoveryWork original;
        synchronized (recoveryLock) {
            if (expectedRevision != recovery.currentRevision() || !ownerCurrent.getAsBoolean()) return;
            ownerStoppedRevision = expectedRevision;
            original = recoveryWork;
        }
        if (original != null && original.revision == expectedRevision) original.control.cancel();
    }

    private JSONObject restoreConnection(RecoveryWork original) throws Exception {
        requireOpen();
        JSONObject result = recovery.restore(() -> recoveryCurrent(original));
        String connection;
        if (Thread.currentThread().isInterrupted()) connection = "recovery_interrupted";
        else if (!recoveryCurrent(original)) connection = "consent_required";
        else if (!result.optBoolean("userUnlocked")) connection = "owner_unlock_required";
        else {
            JSONObject state = display.snapshot();
            if (state.optBoolean("taskOwned") || state.optBoolean("bootstrapBusy"))
                connection = "original_task_owns_connection";
            else if (state.optBoolean("helperConnected"))
                connection = state.optBoolean("recoveryRequired") || state.optBoolean("releasePending")
                        ? "original_session_requires_owner_review" : "original_helper_authenticated";
            else if (!state.optBoolean("configured")) connection = "owner_setup_required";
            else if (!recoveryCurrent(original)) connection = "consent_required";
            else if (state.optBoolean("recoveryRequired") || state.optBoolean("releasePending") ||
                    state.optJSONObject("adb") != null &&
                    state.getJSONObject("adb").optBoolean("recoveryRequired"))
                connection = "original_session_requires_owner_review";
            else {
                try {
                    JSONObject actual = display instanceof DisplayModule nativeDisplay
                            ? nativeDisplay.connect(original.control) : display.handle("connect", new JSONObject());
                    connection = recoveryCurrent(original) && actual.optBoolean("helperConnected")
                            ? "original_helper_authenticated" : "connection_unconfirmed";
                } catch (Exception disconnected) {
                    // An already-authorized helper may have disappeared after reboot. Re-create
                    // that helper under the owner's recovery consent, not any business task.
                    try {
                        if (!recoveryCurrent(original) || !(display instanceof DisplayModule nativeDisplay))
                            throw new SecurityException("原连接恢复权限已失效");
                        JSONObject recovered = nativeDisplay.recoverPreviouslyAuthorized(
                                original.control, () -> recoveryCurrent(original));
                        connection = recoveryCurrent(original) && recovered.optBoolean("helperConnected")
                                ? "original_helper_authenticated" : "connection_unconfirmed";
                    } catch (Exception unavailable) { connection = "owner_connection_check_required"; }
                }
            }
        }
        if (recoveryWork == original) lastConnectionOutcome = connection;
        return result;
    }

    private static final class RecoveryWork {
        final long revision;
        final RunControl control = new RunControl();
        Thread thread;
        boolean admitted;
        volatile boolean scheduled;
        int checks;
        RecoveryWork(long revision) { this.revision = revision; }
    }

    private void emit(String event) {
        JSONObject payload = new JSONObject();
        try { payload.put("event", event).put("storesPairingCode", false); }
        catch (org.json.JSONException impossible) { return; }
        context.emit(id(), payload);
    }

    private void requireOpen() { if (closed) throw new SecurityException("phone_setup_closed"); }
    @Override public void close() {
        closed = true;
        stopRecovery();
        synchronized (pairingLock) {
            autoCapture = null;
            autoCaptureGeneration++;
            pairing.cancel();
            cancelNotice();
            main.removeCallbacksAndMessages(this);
            if (lifecycleSignalsRegistered) {
                try { context.android.unregisterReceiver(unlockReceiver); }
                catch (RuntimeException alreadyRemoved) { }
                lifecycleSignalsRegistered = false;
            }
            if (instance == this) instance = null;
        }
        if (worker instanceof ExecutorService) ((ExecutorService) worker).shutdown();
    }
}
