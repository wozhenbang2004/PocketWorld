package dev.pocketengine.phone;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.diagnostics.AndroidPhoneDiagnostics;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AndroidPhoneDiagnosticsNativeTest {
    @Test public void originalMainReadinessIsProjectedWithoutAnyHelperOrPermissionRequest()throws Exception {
        Application app=RuntimeEnvironment.getApplication();JSONObject actual=AndroidPhoneReadiness.snapshot(app,null);var report=AndroidPhoneDiagnostics.preview(app,null);JSONObject out=report.snapshot();assertEquals(35,out.getInt("sdkInt"));assertTrue(out.isNull("sourceCommit"));assertTrue(out.isNull("sourceTree"));assertFalse(out.getBoolean("backgroundSampled"));
        for(String key:new String[]{"mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl"})assertEquals(actual.getJSONObject(key).getString("state"),report.readiness().getString(key));
        assertFalse(report.text().contains("sessionId"));assertFalse(report.text().contains("message"));assertNull(PhoneAccessibilityService.get());
    }
    @Test public void originalUnconnectedDisplayIsReadOnlyAndKeepsUnknownDeviceVerification()throws Exception {
        Application app=RuntimeEnvironment.getApplication();var display=new DisplayModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        try{JSONObject before=display.snapshot();var report=AndroidPhoneDiagnostics.preview(app,display);JSONObject after=display.snapshot();assertTrue(report.snapshot().getBoolean("backgroundSampled"));assertEquals(before.getBoolean("helperConnected"),after.getBoolean("helperConnected"));assertEquals(before.getBoolean("sessionActive"),after.getBoolean("sessionActive"));assertEquals(before.getBoolean("taskOwned"),after.getBoolean("taskOwned"));assertEquals(before.getBoolean("bootstrapBusy"),after.getBoolean("bootstrapBusy"));assertFalse(report.snapshot().getBoolean("deviceVerified"));assertEquals("not_run_by_diagnostics",report.snapshot().getString("selfCheckStatus"));}
        finally{display.close();}
    }
}
