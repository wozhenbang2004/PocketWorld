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
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.display.adb.LocalAdbEndpoints;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final PhoneRecoveryReceiver unlockReceiver = new PhoneRecoveryReceiver();
    private boolean lifecycleSignalsRegistered;
    private PendingIntent replyIntent, cancelIntent;
    private volatile boolean closed;
    private volatile String lastPairingOutcome = "not_requested";
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
        try {
            // USER_UNLOCKED is registered-receiver-only on Android; a manifest filter never receives it.
            if (Build.VERSION.SDK_INT >= 33) context.android.registerReceiver(unlockReceiver, unlock, Context.RECEIVER_NOT_EXPORTED);
            else context.android.registerReceiver(unlockReceiver, unlock);
            lifecycleSignalsRegistered = true;
        } catch (RuntimeException unavailable) { lifecycleSignalsRegistered = false; }
    }

    static PhoneSetupModule current() { return instance; }
    @Override public String id() { return "phone_setup"; }

    @Override public JSONObject snapshot() throws Exception {
        synchronized (pairingLock) {
            JSONObject entry = pairing.snapshot();
            if (!entry.optBoolean("active") && "awaiting_owner_code".equals(lastPairingOutcome)) {
                cancelNotice();
                lastPairingOutcome = "expired";
            }
            return new JSONObject().put("pairing", entry.put("lastOutcome", lastPairingOutcome))
                    .put("recovery", recovery.snapshot().put("connectionOutcome", lastConnectionOutcome)
                            .put("busy", recoveryRunning.get()).put("unlockSignalsAvailable", lifecycleSignalsRegistered))
                    .put("modelCommandsAvailable", false);
        }
    }

    @Override public JSONObject handle(String command, JSONObject input) throws Exception {
        requireOpen();
        if (input == null) input = new JSONObject();
        switch (command) {
            case "status": return snapshot();
            case "pairing.begin": return begin(input);
            case "pairing.cancel": cancel(input.optString(SESSION, null)); return snapshot();
            case "recovery.consent":
                recovery.setConsent(input.getBoolean("enabled"), input.getLong("expectedRevision"));
                return snapshot();
            case "recovery.restore": return restoreConnection();
            case "recovery.compensate":
                recovery.compensate();
                return snapshot();
            default: throw new IllegalArgumentException("phone_setup_command_unavailable");
        }
    }

    private JSONObject begin(JSONObject input) throws Exception {
        synchronized (pairingLock) {
            requireOpen();
            PairingEntrySession.Entry entry;
            synchronized (display) {
                JSONObject state = display.snapshot();
                requirePairingIdle(state);
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
            if (expected == null) { pairing.cancel(); cancelNotice(); lastPairingOutcome = "cancelled"; }
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

    void recoverAfterLifecycle(Runnable finished) {
        if (closed || recoveryRunning.get() || !recoveryQueued.compareAndSet(false, true)) { finished.run(); return; }
        final long consentRevision;
        try { consentRevision = recovery.snapshot().getLong("revision"); }
        catch (Exception unavailable) { recoveryQueued.set(false); finished.run(); return; }
        try {
            worker.execute(() -> {
                try { if (!closed && recovery.consentCurrent(consentRevision)) restoreConnection(); }
                catch (Exception unavailable) { emit("phone_recovery_unavailable"); }
                finally { recoveryQueued.set(false); finished.run(); }
            });
        } catch (RuntimeException unavailable) { recoveryQueued.set(false); finished.run(); }
    }

    private JSONObject restoreConnection() throws Exception {
        if (!recoveryRunning.compareAndSet(false, true)) return snapshot();
        try {
            requireOpen();
            JSONObject before = recovery.snapshot();
            long revision = before.getLong("revision");
            recovery.restore(() -> !closed);
            if (!recovery.consentCurrent(revision) || closed) lastConnectionOutcome = "consent_required";
            else if (!recovery.snapshot().optBoolean("userUnlocked")) lastConnectionOutcome = "owner_unlock_required";
            else synchronized (display) {
                JSONObject state = display.snapshot();
                if (state.optBoolean("taskOwned") || state.optBoolean("bootstrapBusy")) lastConnectionOutcome = "original_task_owns_connection";
                else if (!state.optBoolean("configured")) lastConnectionOutcome = "owner_setup_required";
                else if (!recovery.consentCurrent(revision) || closed) lastConnectionOutcome = "consent_required";
                else try {
                    // Only the original selected backend's authenticated status. Never start/grant/switch/release here.
                    JSONObject actual = display.handle("connect", new JSONObject());
                    lastConnectionOutcome = recovery.consentCurrent(revision) && !closed && actual.optBoolean("helperConnected")
                            ? actual.optBoolean("recoveryRequired") || actual.optBoolean("releasePending")
                                ? "original_session_requires_owner_review" : "original_helper_authenticated"
                            : "connection_unconfirmed";
                } catch (Exception unavailable) { lastConnectionOutcome = "owner_connection_check_required"; }
            }
        } finally { recoveryRunning.set(false); }
        emit("phone_recovery_checked");
        return snapshot();
    }

    private void emit(String event) {
        JSONObject payload = new JSONObject();
        try { payload.put("event", event).put("storesPairingCode", false); }
        catch (org.json.JSONException impossible) { return; }
        context.emit(id(), payload);
    }

    private void requireOpen() { if (closed) throw new SecurityException("phone_setup_closed"); }
    @Override public void close() {
        synchronized (pairingLock) {
            closed = true;
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
