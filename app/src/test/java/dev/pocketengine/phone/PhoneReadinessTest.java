package dev.pocketengine.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneReadinessTest {
    private static JSONObject nativeFacts() throws Exception {
        JSONObject facts = new JSONObject().put("checkedAtUptimeMs", 1000).put("taskChannelState", "enabled")
                .put("mainServiceSessionId", "main-service").put("backgroundSessionId", "background-session").put("backgroundDisplayId", 9);
        for (String key : new String[]{"serviceEnabled", "serviceConnected", "windowContent", "interactiveWindows", "deviceUnlocked", "screenInteractive", "mainDisplayPresent",
                "screenshotCapability", "gestureCapability", "notificationsAllowed", "notificationPermission", "notificationsNotPaused",
                "ownerControlsVisible", "backgroundSupported", "backgroundConnected", "backgroundSessionReady", "backgroundClean"}) facts.put(key, true);
        return facts;
    }
    private static JSONObject check(JSONObject group, String id) throws Exception {
        JSONArray checks = group.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) if (checks.getJSONObject(i).getString("id").equals(id)) return checks.getJSONObject(i);
        throw new AssertionError("missing native readiness fact: " + id);
    }
    @Test public void appNotificationPermissionDoesNotOverrideABlockedTaskChannel() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("taskChannelState", "disabled"));
        assertFalse(out.getJSONObject("taskNotifications").getBoolean("ready"));
        assertFalse(out.getJSONObject("backgroundPreparation").getBoolean("ready"));
        assertEquals("task_channel_blocked", check(out.getJSONObject("taskNotifications"), "taskChannel").getString("code"));
        assertTrue("the existing native main-screen stop controls remain usable", out.getJSONObject("stopControl").getBoolean("ready"));
        assertTrue(out.getJSONObject("mainExecution").getBoolean("ready"));
    }
    @Test public void unknownNativePermissionsAndMissingStopRoutesNeverBecomeReady() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("windowContent", JSONObject.NULL)
                .put("ownerControlsVisible", false).put("notificationsAllowed", JSONObject.NULL));
        assertEquals("unknown", out.getJSONObject("mainObservation").getString("state"));
        assertFalse(out.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(out.getJSONObject("stopControl").getBoolean("ready"));
        assertEquals("unknown", out.getJSONObject("stopControl").getString("state"));
        assertEquals("unknown", check(out.getJSONObject("mainObservation"), "windowContent").getString("state"));
    }
    @Test public void backgroundUnavailableDoesNotFallBackToAReadyMainDisplay() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("backgroundConnected", false));
        assertTrue(out.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(out.getJSONObject("backgroundPreparation").getBoolean("ready"));
        assertFalse(out.getJSONObject("backgroundExecution").getBoolean("ready"));
        assertFalse(out.getBoolean("mainScreenFallback"));
        assertEquals(0, out.getInt("mainDisplayId")); assertEquals(9, out.getInt("backgroundDisplayId"));
        assertEquals("main-service", out.getString("mainServiceSessionId")); assertEquals("background-session", out.getString("backgroundSessionId"));
    }
    @Test public void screenshotCapabilityIsIndependentFromTextAndBackgroundFrameReadiness() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("screenshotCapability", false));
        assertTrue(out.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(out.getJSONObject("mainScreenshot").getBoolean("ready"));
        assertTrue(out.getJSONObject("backgroundExecution").getBoolean("ready"));
        assertTrue(out.getBoolean("observedOnly")); assertFalse(out.getBoolean("deviceVerified"));
    }
    @Test public void channelCreationReadinessIsSeparateFromAnActuallyAvailableStopNotification() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("taskChannelState", "not_created").put("ownerControlsVisible", false));
        assertTrue(out.getJSONObject("taskNotificationsForStart").getBoolean("ready"));
        assertFalse(out.getJSONObject("taskNotifications").getBoolean("ready")); assertFalse(out.getJSONObject("stopControl").getBoolean("ready"));
        assertTrue(out.getJSONObject("backgroundPreparation").getBoolean("ready")); assertFalse(out.getJSONObject("backgroundExecution").getBoolean("ready"));
    }
    @Test public void revokedRuntimePermissionPausedNotificationsAndUnconfirmedCleanupBlockBackground() throws Exception {
        for (String fact : new String[]{"notificationPermission", "notificationsNotPaused", "backgroundClean"}) {
            JSONObject out = PhoneReadiness.evaluate(nativeFacts().put(fact, false));
            assertFalse(fact, out.getJSONObject("backgroundPreparation").getBoolean("ready"));
            assertFalse(fact, out.getJSONObject("backgroundExecution").getBoolean("ready"));
        }
    }
    @Test public void enabledButDisconnectedServiceIsBlockedWithoutClaimingItWasDisabled() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("serviceConnected", false));
        for (String group : new String[]{"mainObservation", "backgroundPreparation"}) {
            JSONObject service = check(out.getJSONObject(group), "serviceConnected");
            assertFalse(out.getJSONObject(group).getBoolean("ready"));
            assertEquals("blocked", service.getString("state"));
            assertEquals("accessibility_not_connected", service.getString("code"));
            assertTrue(service.getString("message").contains("已开启"));
            assertTrue(service.getString("message").contains("未连接"));
        }
        assertThrows(IllegalStateException.class, () -> PhoneReadiness.requireStart(out, "main_screen"));
        assertThrows(IllegalStateException.class, () -> PhoneReadiness.requireStart(out, "background"));
    }
    @Test public void disabledAndUnknownServiceSettingsRemainDistinctAndNeverBecomeReady() throws Exception {
        JSONObject disabled = PhoneReadiness.evaluate(nativeFacts().put("serviceConnected", false).put("serviceEnabled", false));
        assertEquals("accessibility_disabled", check(disabled.getJSONObject("mainObservation"), "serviceConnected").getString("code"));
        JSONObject unknown = PhoneReadiness.evaluate(nativeFacts().put("serviceConnected", false).put("serviceEnabled", JSONObject.NULL));
        JSONObject service = check(unknown.getJSONObject("mainObservation"), "serviceConnected");
        assertEquals("unknown", service.getString("state"));
        assertEquals("accessibility_state_unknown", service.getString("code"));
        assertFalse(unknown.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(unknown.getJSONObject("backgroundPreparation").getBoolean("ready"));
        assertThrows(IllegalStateException.class, () -> PhoneReadiness.requireStart(unknown, "main_screen"));
        assertThrows(IllegalStateException.class, () -> PhoneReadiness.requireStart(unknown, "background"));
    }
    @Test public void aDisabledSystemSettingCannotInheritAnOldConnectedService() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("serviceEnabled", false));
        assertFalse(out.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(out.getJSONObject("backgroundPreparation").getBoolean("ready"));
        assertEquals("accessibility_disabled", check(out.getJSONObject("mainObservation"), "serviceConnected").getString("code"));
    }
    @Test public void mainPreparationDoesNotRequireControlsThatAreCreatedByStartingTheTask() throws Exception {
        JSONObject facts = nativeFacts().put("ownerControlsVisible", false).put("notificationsAllowed", false).put("taskChannelState", "not_created");
        JSONObject out = PhoneReadiness.evaluate(facts);
        assertTrue(out.getJSONObject("mainPreparation").getBoolean("ready"));
        assertFalse(out.getJSONObject("mainExecution").getBoolean("ready"));
        assertFalse(out.getJSONObject("stopControl").getBoolean("ready"));
        PhoneReadiness.requireStart(out, "main_screen");
        JSONObject revoked = PhoneReadiness.evaluate(facts.put("gestureCapability", false));
        assertTrue(revoked.getJSONObject("mainObservation").getBoolean("ready"));
        assertFalse(revoked.getJSONObject("mainPreparation").getBoolean("ready"));
        assertThrows(IllegalStateException.class, () -> PhoneReadiness.requireStart(revoked, "main_screen"));
    }
    @Test public void backgroundPreparationDoesNotRequireAnExistingTaskSession() throws Exception {
        JSONObject out = PhoneReadiness.evaluate(nativeFacts().put("backgroundSessionReady", false)
                .put("backgroundSessionId", JSONObject.NULL).put("backgroundDisplayId", -1).put("taskChannelState", "not_created"));
        assertTrue(out.getJSONObject("backgroundPreparation").getBoolean("ready"));
        assertFalse(out.getJSONObject("backgroundExecution").getBoolean("ready"));
        PhoneReadiness.requireStart(out, "background");
    }
}
