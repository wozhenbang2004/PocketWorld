package dev.pocketengine.phone.setup;

import android.app.Application;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real SharedPreferences and component identity with an explicit fault-injection seam for secure settings writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public final class AccessibilityRecoveryMutationTest {
    private static final String OWN = "dev.pocketengine/.phone.PhoneAccessibilityService";
    private static final String OTHER = "example.other/.Reader";
    private SharedPreferences preferences;
    private Platform platform;
    private AccessibilityRecovery recovery;

    private static final class Platform implements AccessibilityRecovery.Platform {
        String services = OTHER + ":" + OWN;
        boolean permitted = true, unlocked = true, connected;
        int enabled = 1, writes, failFrom = Integer.MAX_VALUE;
        Runnable afterFirstWrite = () -> { };
        public boolean unlocked() { return unlocked; }
        public boolean permitted() { return permitted; }
        public boolean connected() { return connected; }
        public String services() { return services; }
        public int enabled() { return enabled; }
        public boolean writeServices(String value) {
            writes++;
            if (writes >= failFrom) throw new IllegalStateException("injected_secure_write_failure");
            services = value;
            if (writes == 1) afterFirstWrite.run();
            return true;
        }
    }

    @Before public void setup() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("phone_connection_recovery", 0);
        preferences.edit().clear().commit();
        platform = new Platform();
        recovery = new AccessibilityRecovery(preferences, platform, OWN, 10001);
    }

    @Test public void revokedRevisionStopsReenableButCompensatesOnlyOriginalRemoval() throws Exception {
        recovery.setConsent(true, 0);
        platform.afterFirstWrite = () -> {
            platform.services += ":example.added/.Reader";
            try { recovery.setConsent(false, 1); } catch (Exception e) { throw new AssertionError(e); }
        };
        JSONObject result = recovery.restore();
        assertEquals("recovery_consent_revoked", result.getString("lastOutcome"));
        assertFalse(result.getBoolean("consent"));
        assertFalse(result.getBoolean("compensationPending"));
        assertTrue(platform.services.contains(OWN));
        assertTrue(platform.services.contains("example.added/.Reader"));
        assertTrue(platform.services.contains(OTHER));
        assertEquals(2, platform.writes);
        assertEquals(1, platform.enabled);
    }

    @Test public void failedCompensationSurvivesColdReadAndRequiresOwnerAction() throws Exception {
        recovery.setConsent(true, 0);
        platform.failFrom = 2;
        assertEquals("compensation_pending", recovery.restore().getString("lastOutcome"));
        assertTrue(recovery.snapshot().getBoolean("compensationPending"));
        assertFalse(platform.services.contains(OWN));
        AccessibilityRecovery cold = new AccessibilityRecovery(preferences, platform, OWN, 10001);
        int previousWrites = platform.writes;
        assertEquals("recovery_pending_owner_action", cold.restore().getString("lastOutcome"));
        assertEquals(previousWrites, platform.writes);
        platform.services += ":example.new/.Reader";
        platform.failFrom = Integer.MAX_VALUE;
        JSONObject restored = cold.compensate();
        assertEquals("original_registration_restored", restored.getString("lastOutcome"));
        assertFalse(restored.getBoolean("compensationPending"));
        assertTrue(platform.services.contains(OWN));
        assertTrue(platform.services.contains("example.new/.Reader"));
    }

    @Test public void ownerGlobalDisableIsNotUndoneByRecovery() throws Exception {
        recovery.setConsent(true, 0);
        platform.enabled = 0;
        assertEquals("owner_accessibility_disabled", recovery.restore().getString("lastOutcome"));
        assertEquals(0, platform.writes);
        assertEquals(0, platform.enabled);
    }

    @Test public void neverPreviouslyAuthorizedServiceCannotBeGrantedByRecoveryConsent() throws Exception {
        platform.services = OTHER;
        recovery.setConsent(true, 0);
        assertEquals("initial_accessibility_authorization_required", recovery.restore().getString("lastOutcome"));
        assertEquals(0, platform.writes);
        assertEquals(OTHER, platform.services);
    }

    @Test public void laterPerServiceDisableCannotBorrowEarlierRecoveryConsent() throws Exception {
        recovery.setConsent(true, 0);
        platform.services = OTHER; // Other services keep the system-wide switch on.
        assertEquals("owner_accessibility_disabled", recovery.restore().getString("lastOutcome"));
        assertEquals(0, platform.writes);
        assertEquals(OTHER, platform.services);
        assertFalse(recovery.snapshot().getBoolean("compensationPending"));
    }

    @Test public void cancellationRestoresOnlyTheRecordedRegistration() throws Exception {
        recovery.setConsent(true, 0);
        AtomicBoolean current = new AtomicBoolean(true);
        platform.afterFirstWrite = () -> current.set(false);
        assertEquals("recovery_consent_revoked", recovery.restore(current::get).getString("lastOutcome"));
        assertEquals(OTHER + ":" + OWN, platform.services);
        assertFalse(recovery.snapshot().getBoolean("compensationPending"));
    }

    @Test public void changedOwnerUidCannotReuseSavedConsentOrCompensation() throws Exception {
        recovery.setConsent(true, 0);
        AccessibilityRecovery otherOwner = new AccessibilityRecovery(preferences, platform, OWN, 110001);
        assertEquals("consent_required", otherOwner.restore().getString("lastOutcome"));
        assertEquals(0, platform.writes);
    }

    @Test public void registrationReadbackAndBindingRemainSeparate() throws Exception {
        recovery.setConsent(true, 0);
        JSONObject result = recovery.restore();
        assertEquals("registered_waiting_for_binding", result.getString("lastOutcome"));
        assertTrue(result.getBoolean("registrationVerified"));
        assertFalse(result.getBoolean("bindingVerified"));
        assertFalse(result.getBoolean("busy"));
        assertFalse(result.getBoolean("changesWirelessDebugging"));
        assertEquals(OTHER + ":" + OWN, platform.services);
    }
}
