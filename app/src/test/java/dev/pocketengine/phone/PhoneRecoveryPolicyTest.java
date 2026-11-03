package dev.pocketengine.phone;

import android.app.Application;
import android.content.Context;
import android.os.UserManager;
import android.provider.Settings;
import dev.pocketengine.phone.setup.AccessibilityRecovery;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class PhoneRecoveryPolicyTest {
    @Test public void missingConsentCannotChangeAccessibilityOrWirelessDebugging()throws Exception {Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("phone_connection_recovery",0).edit().clear().commit();Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"example.other/.Reader");Settings.Global.putInt(app.getContentResolver(),"adb_wifi_enabled",0);assertEquals("consent_required",new AccessibilityRecovery(app).restore().getString("lastOutcome"));assertEquals("example.other/.Reader",Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));assertEquals(0,Settings.Global.getInt(app.getContentResolver(),"adb_wifi_enabled"));}
    @Test public void consentDoesNotGrantSecureSettingsPermission()throws Exception {Application app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app.getSystemService(UserManager.class)).setUserUnlocked(true);Shadows.shadowOf(app).denyPermissions("android.permission.WRITE_SECURE_SETTINGS");app.getSharedPreferences("phone_connection_recovery",0).edit().clear().commit();Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"example.other/.Reader");AccessibilityRecovery recovery=new AccessibilityRecovery(app);recovery.setConsent(true,0);assertEquals("secure_settings_permission_missing",recovery.restore().getString("lastOutcome"));assertEquals("example.other/.Reader",Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));assertFalse(recovery.snapshot().getBoolean("secureSettingsGranted"));}
    @Test public void staleConsentCannotOverwriteARevocationFromAnotherOwnerView()throws Exception {Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("phone_connection_recovery",0).edit().clear().commit();AccessibilityRecovery first=new AccessibilityRecovery(app),second=new AccessibilityRecovery(app);first.setConsent(true,0);second.setConsent(false,1);assertThrows(SecurityException.class,()->first.setConsent(true,1));assertFalse(first.snapshot().getBoolean("consent"));assertEquals("consent_required",first.restore().getString("lastOutcome"));}
}

