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

/** Real SharedPreferences and component identity with external secure-setting changes
 * injected immediately after reads. No fake atomic writer is supplied. */
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
        int enabled = 1;
        Runnable afterRead;
        public boolean unlocked() { return unlocked; }
        public boolean permitted() { return permitted; }
        public boolean connected() { return connected; }
        public String services() {
            String observed=services;Runnable change=afterRead;afterRead=null;
            if(change!=null)change.run();return observed;
        }
        public int enabled() { return enabled; }
    }

    @Before public void setup() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("phone_connection_recovery", 0);
        preferences.edit().clear().commit();
        platform = new Platform();
        recovery = new AccessibilityRecovery(preferences, platform, OWN, 10001);
    }

    @Test public void revokedRevisionNeverRewritesTheListOrCreatesANewCompensation() throws Exception {
        recovery.setConsent(true, 0);
        platform.afterRead = () -> {
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
        assertEquals(1, platform.enabled);
        assertFalse(result.getBoolean("settingsMutation"));
    }

    @Test public void legacyCompensationStaysPendingUntilTheOwnerHasRestoredTheRegistration() throws Exception {
        recovery.setConsent(true, 0);
        preferences.edit().putBoolean("compensationPending",true).putString("compensationComponent",OWN).putInt("compensationUid",10001).commit();
        platform.services=OTHER;
        assertTrue(recovery.snapshot().getBoolean("compensationPending"));
        assertFalse(platform.services.contains(OWN));
        AccessibilityRecovery cold = new AccessibilityRecovery(preferences, platform, OWN, 10001);
        assertEquals("recovery_pending_owner_action", cold.restore().getString("lastOutcome"));
        platform.services += ":example.new/.Reader";
        JSONObject pending=cold.compensate();
        assertEquals("compensation_pending",pending.getString("lastOutcome"));assertTrue(pending.getBoolean("needsOwnerAction"));
        assertEquals(OTHER+":example.new/.Reader",platform.services);assertTrue(preferences.getBoolean("compensationPending",false));
        // The owner changes the system setting, not this recovery module.
        platform.services += ":"+OWN;
        JSONObject restored = cold.compensate();
        assertEquals("original_registration_restored", restored.getString("lastOutcome"));
        assertFalse(restored.getBoolean("compensationPending"));
        assertTrue(platform.services.contains(OWN));
        assertTrue(platform.services.contains("example.new/.Reader"));
        assertFalse(restored.getBoolean("settingsMutation"));
        assertFalse(restored.getBoolean("bindingVerified"));
    }

    @Test public void ownerGlobalDisableIsNotUndoneByRecovery() throws Exception {
        recovery.setConsent(true, 0);
        platform.enabled = 0;
        assertEquals("owner_accessibility_disabled", recovery.restore().getString("lastOutcome"));
        assertEquals(OTHER+":"+OWN, platform.services);
        assertEquals(0, platform.enabled);
    }

    @Test public void neverPreviouslyAuthorizedServiceCannotBeGrantedByRecoveryConsent() throws Exception {
        platform.services = OTHER;
        recovery.setConsent(true, 0);
        assertEquals("initial_accessibility_authorization_required", recovery.restore().getString("lastOutcome"));
        assertEquals(OTHER, platform.services);
    }

    @Test public void laterPerServiceDisableCannotBorrowEarlierRecoveryConsent() throws Exception {
        recovery.setConsent(true, 0);
        platform.services = OTHER; // Other services keep the system-wide switch on.
        assertEquals("owner_accessibility_disabled", recovery.restore().getString("lastOutcome"));
        assertEquals(OTHER, platform.services);
        assertFalse(recovery.snapshot().getBoolean("compensationPending"));
    }

    @Test public void cancellationCannotMutateTheObservedRegistration() throws Exception {
        recovery.setConsent(true, 0);
        AtomicBoolean current = new AtomicBoolean(true);
        platform.afterRead = () -> current.set(false);
        assertEquals("recovery_consent_revoked", recovery.restore(current::get).getString("lastOutcome"));
        assertEquals(OTHER + ":" + OWN, platform.services);
        assertFalse(recovery.snapshot().getBoolean("compensationPending"));
    }

    @Test public void changedOwnerUidCannotReuseSavedConsentOrCompensation() throws Exception {
        recovery.setConsent(true, 0);
        AccessibilityRecovery otherOwner = new AccessibilityRecovery(preferences, platform, OWN, 110001);
        assertEquals("consent_required", otherOwner.restore().getString("lastOutcome"));
        preferences.edit().putBoolean("compensationPending",true).putString("compensationComponent",OWN).putInt("compensationUid",10001).commit();
        assertEquals("compensation_pending",otherOwner.compensate().getString("lastOutcome"));
        assertTrue(preferences.getBoolean("compensationPending",false));
    }

    @Test public void registrationReadbackAndBindingRemainSeparate() throws Exception {
        recovery.setConsent(true, 0);
        JSONObject result = recovery.restore();
        assertEquals("owner_settings_required", result.getString("lastOutcome"));
        assertTrue(result.getBoolean("registrationVerified"));
        assertFalse(result.getBoolean("bindingVerified"));
        assertFalse(result.getBoolean("busy"));
        assertFalse(result.getBoolean("changesWirelessDebugging"));
        assertEquals(OTHER + ":" + OWN, platform.services);
        assertTrue(result.getBoolean("needsOwnerAction"));
        assertFalse(result.getBoolean("automaticRegistrationRestoreSupported"));
    }

    @Test public void externalAdditionOrRemovalAfterTheFinalReadIsNeverOverwritten()throws Exception {
        recovery.setConsent(true,0);
        platform.afterRead=()->platform.services=OTHER+":example.new/.Reader";
        JSONObject result=recovery.restore();
        assertEquals(OTHER+":example.new/.Reader",platform.services);
        assertEquals("owner_settings_required",result.getString("lastOutcome"));
        assertTrue(result.getBoolean("registrationObservationOnly"));assertFalse(result.getBoolean("settingsMutation"));
        assertFalse(preferences.getBoolean("compensationPending",false));
        assertEquals("owner_accessibility_disabled",new AccessibilityRecovery(preferences,platform,OWN,10001).restore().getString("lastOutcome"));
    }

    @Test public void secureSettingsPermissionDoesNotInventAConditionalWriteInterface()throws Exception {
        recovery.setConsent(true,0);platform.permitted=false;
        JSONObject result=recovery.restore();
        assertEquals("owner_settings_required",result.getString("lastOutcome"));assertFalse(result.getBoolean("secureSettingsGranted"));
        assertEquals(OTHER+":"+OWN,platform.services);assertFalse(result.getBoolean("settingsMutation"));
    }
}
