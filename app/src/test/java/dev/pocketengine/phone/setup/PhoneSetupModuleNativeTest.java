package dev.pocketengine.phone.setup;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual owner module/notification/receiver lifecycle with a deliberately simulated original display transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class PhoneSetupModuleNativeTest {
    private Application app;
    private Display display;
    private PhoneSetupModule module;
    private AccessibilityRecovery recovery;
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private final List<JSONObject> events = new ArrayList<>();

    private static final class Display implements EngineModule {
        JSONObject state;
        int pairCalls, connectCalls;
        boolean throwAfterPair;
        JSONObject dispatched;
        Display() throws Exception {
            state = new JSONObject().put("supportedAndroid", true).put("taskOwned", false).put("bootstrapBusy", false)
                    .put("sessionActive", false).put("configured", true).put("authorizationBackend", "wireless_adb")
                    .put("setupRevision", 1).put("helperConnected", false)
                    .put("adb", new JSONObject().put("generation", 4).put("recoveryRequired", false));
        }
        public String id() { return "display"; }
        public JSONObject snapshot() throws Exception { return new JSONObject(state.toString()); }
        public JSONObject handle(String command, JSONObject input) throws Exception {
            if ("adb.pair".equals(command)) {
                pairCalls++;
                dispatched = input;
                if (throwAfterPair) throw new IllegalStateException("simulated_transport_reply_lost");
            } else if ("connect".equals(command)) {
                connectCalls++;
                state.put("helperConnected", true);
            } else throw new AssertionError("Unexpected backend mutation: " + command);
            return snapshot();
        }
    }

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("phone_connection_recovery", 0).edit().clear().commit();
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS");
        display = new Display();
        AccessibilityRecovery.Platform platform = new AccessibilityRecovery.Platform() {
            public boolean unlocked() { return true; }
            public boolean permitted() { return false; }
            public boolean connected() { return false; }
            public String services() { return "example.other/.Reader"; }
            public int enabled() { return 1; }
        };
        recovery = new AccessibilityRecovery(app.getSharedPreferences("phone_connection_recovery", 0), platform,
                "dev.pocketengine/.phone.PhoneAccessibilityService", 10001);
        module = new PhoneSetupModule(new FeatureContext(app, null, null, events::add), display, recovery,
                new PairingEntrySession(SystemClock::elapsedRealtime), new Handler(Looper.getMainLooper()), work::add);
    }

    @After public void teardown() { module.close(); drain(); }
    private void drain() { while (!work.isEmpty()) work.remove().run(); }
    private String begin() throws Exception {
        return module.handle("pairing.begin", new JSONObject().put("host", "127.0.0.1").put("pairingPort", 43210))
                .getJSONObject("pairing").getString("sessionId");
    }

    @Test public void systemPairingObservationIsOwnerOnlyAndCancellationCannotPair() throws Exception {
        assertThrows(SecurityException.class, () -> module.handle("pairing.observe", new JSONObject()));
        assertThrows(SecurityException.class,
                () -> module.handleForOwner("pairing.observe", new JSONObject(), () -> false));
        JSONObject armed = module.handleForOwner("pairing.observe", new JSONObject(), () -> true);
        assertTrue(armed.getJSONObject("pairing").getJSONObject("autoCapture").getBoolean("active"));
        assertFalse(armed.toString().contains("pairingCode"));
        assertEquals(0, display.pairCalls);
        module.handle("pairing.cancel", new JSONObject());
        assertFalse(module.snapshot().getJSONObject("pairing").getJSONObject("autoCapture").getBoolean("active"));
        assertEquals(0, display.pairCalls);
        assertFalse(PhoneSetupModule.systemPairingCaptureActive());
    }

    private void offerSystemCode() throws Exception {
        java.lang.reflect.Method method=PhoneSetupModule.class.getDeclaredMethod(
                "submitObservedPairing",SystemWirelessPairing.Credential.class);
        method.setAccessible(true);
        method.invoke(module,new SystemWirelessPairing.Credential("127.0.0.1",43210,"482911"));
    }

    @Test public void queuedSystemPairingHonorsCancellationBeforeNativeWrite() throws Exception {
        module.handleForOwner("pairing.observe",new JSONObject(),()->true);
        offerSystemCode();
        assertEquals(1,work.size());
        module.handle("pairing.cancel",new JSONObject());
        drain();
        assertEquals(0,display.pairCalls);
        assertFalse(module.snapshot().getJSONObject("pairing").getJSONObject("autoCapture").getBoolean("active"));
    }

    @Test public void visibleSystemCodeClaimsOnceAndNeverPersistsTheSecret() throws Exception {
        module.handleForOwner("pairing.observe",new JSONObject(),()->true);
        offerSystemCode();
        offerSystemCode();
        drain();
        assertEquals(1,display.pairCalls);
        assertFalse(display.dispatched.has("pairingCode"));
        assertFalse(module.snapshot().toString().contains("482911"));
        assertFalse(events.toString().contains("482911"));
    }

    @Test public void notificationReplyUsesPrivateReceiverAndOriginalPairOnceWithoutClaimingConnection() throws Exception {
        String session = begin();
        Notification notice = app.getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification();
        assertEquals(Notification.VISIBILITY_SECRET, notice.visibility);
        assertFalse(app.getPackageManager().getReceiverInfo(new ComponentName(app, PhoneSetupReceiver.class), 0).exported);
        Intent reply = new Intent();
        Bundle results = new Bundle(); results.putCharSequence(PhoneSetupModule.CODE, "482911");
        RemoteInput.addResultsToIntent(notice.actions[0].getRemoteInputs(), reply, results);
        notice.actions[0].actionIntent.send(app, 0, reply);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        drain();
        assertEquals(1, display.pairCalls);
        assertFalse(display.dispatched.has("pairingCode"));
        assertFalse(module.snapshot().getJSONObject("pairing").getBoolean("connectionVerified"));
        assertEquals(0, display.connectCalls);
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "482911", () -> { });
        drain(); assertEquals(1, display.pairCalls);
        assertFalse(events.toString().contains("482911"));
    }

    @Test public void cancellationBeforeQueuedReplyPreventsNativeDispatch() throws Exception {
        String session = begin();
        AtomicInteger finished = new AtomicInteger();
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "123456", finished::incrementAndGet);
        module.handle("pairing.cancel", new JSONObject().put(PhoneSetupModule.SESSION, session));
        drain();
        assertEquals(0, display.pairCalls);
        assertEquals(1, finished.get());
        assertEquals(0, app.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }

    @Test public void sourceRevisionAndOccupancyAreRecheckedAtConsumption() throws Exception {
        String session = begin();
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "123456", () -> { });
        display.state.put("setupRevision", 2);
        drain(); assertEquals(0, display.pairCalls);
        assertFalse(module.snapshot().getJSONObject("pairing").getBoolean("active"));
        assertEquals(0, app.getSystemService(NotificationManager.class).getActiveNotifications().length);
        session = begin();
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "123456", () -> { });
        display.state.put("taskOwned", true);
        drain(); assertEquals(0, display.pairCalls);
        assertFalse(module.snapshot().getJSONObject("pairing").getBoolean("active"));
        assertEquals(0, app.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }

    @Test public void oldQueuedCancelAndReplyCannotConsumeOrDismissNewPortNotification() throws Exception {
        String old = begin();
        module.receiveNotification(PhoneSetupModule.CANCEL, old, null, () -> { });
        module.receiveNotification(PhoneSetupModule.SUBMIT, old, "123456", () -> { });
        String current = module.handle("pairing.begin", new JSONObject().put("host", "127.0.0.1").put("pairingPort", 43211))
                .getJSONObject("pairing").getString("sessionId");
        drain(); assertEquals(0, display.pairCalls);
        assertEquals(current, module.snapshot().getJSONObject("pairing").getString("sessionId"));
        assertEquals(1, app.getSystemService(NotificationManager.class).getActiveNotifications().length);
        module.receiveNotification(PhoneSetupModule.SUBMIT, current, "654321", () -> { }); drain();
        assertEquals(1, display.pairCalls); assertEquals(43211, display.dispatched.getInt("pairingPort"));
    }

    @Test public void registeredUnlockSignalsUseSameConsentBoundWorkerAndStopAfterClose() throws Exception {
        recovery.setConsent(true, 0);
        assertTrue(module.snapshot().getJSONObject("recovery").getBoolean("unlockSignalsAvailable"));
        app.sendBroadcast(new Intent(Intent.ACTION_USER_UNLOCKED));
        app.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, work.size()); // The two actual registered deliveries coalesce before transport.
        drain(); assertEquals(1, display.connectCalls); assertEquals(0, display.pairCalls);
        module.close();
        app.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); drain();
        assertEquals(1, display.connectCalls);
        assertFalse(module.snapshot().getJSONObject("recovery").getBoolean("unlockSignalsAvailable"));
    }

    @Test public void unknownNativeReplyCannotBeReplayedAndCodeIsDiscarded() throws Exception {
        String session = begin(); display.throwAfterPair = true;
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "123456", () -> { }); drain();
        assertEquals(1, display.pairCalls); assertFalse(display.dispatched.has("pairingCode"));
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "123456", () -> { }); drain();
        assertEquals(1, display.pairCalls);
    }

    @Test public void disabledNotificationChannelLeavesNoLivePairingAuthority() throws Exception {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("phone_pairing_input", "blocked", NotificationManager.IMPORTANCE_NONE));
        assertThrows(SecurityException.class, this::begin);
        assertFalse(module.snapshot().getJSONObject("pairing").getBoolean("active"));
        assertEquals(0, display.pairCalls);
    }

    @Test public void oldLifecycleCallbackCannotBorrowNewConsentAndRepeatedSignalsCoalesce() throws Exception {
        recovery.setConsent(true, 0);
        AtomicInteger finished = new AtomicInteger();
        module.recoverAfterLifecycle(finished::incrementAndGet);
        module.recoverAfterLifecycle(finished::incrementAndGet);
        assertEquals(1, work.size());
        recovery.setConsent(false, 1); recovery.setConsent(true, 2);
        drain();
        assertEquals(2, finished.get()); assertEquals(0, display.connectCalls);
        module.recoverAfterLifecycle(finished::incrementAndGet); drain();
        assertEquals(1, display.connectCalls);
        assertEquals("initial_accessibility_authorization_required", module.snapshot().getJSONObject("recovery").getString("lastOutcome"));
        assertEquals("original_helper_authenticated", module.snapshot().getJSONObject("recovery").getString("connectionOutcome"));
    }

    @Test public void closeRejectsAlreadyQueuedReplyAndFinishesReceiver() throws Exception {
        String session = begin(); AtomicInteger finished = new AtomicInteger();
        module.receiveNotification(PhoneSetupModule.SUBMIT, session, "123456", finished::incrementAndGet);
        module.close(); drain();
        assertEquals(0, display.pairCalls); assertEquals(1, finished.get());
        assertEquals(0, app.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
}
