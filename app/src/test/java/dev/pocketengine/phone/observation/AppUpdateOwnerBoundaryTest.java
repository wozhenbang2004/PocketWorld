package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.PERMISSION_ELEVATION;

/** Native admission and synthetic labels only, not accessibility/OCR or installer device acceptance. */
public class AppUpdateOwnerBoundaryTest {
    @Test public void openUpdatePageBlocksNativeAgentEntryBeforeAnyEffectAndCloseRestoresIt() throws Exception {
        RunControl control = new RunControl(); AtomicInteger effects = new AtomicInteger();
        AutoCloseable page = NativeSensitiveStep.openOwnerUpdateWindow();
        try {
            NativeSensitiveStep.OwnerRequired owner = assertThrows(NativeSensitiveStep.OwnerRequired.class,
                    () -> NativePhoneEntry.call(control, 0, () -> {}, effects::incrementAndGet));
            assertEquals(PERMISSION_ELEVATION, owner.reason); assertEquals(0, effects.get());
        } finally { page.close(); page.close(); }
        assertEquals(Integer.valueOf(1), NativePhoneEntry.call(control, 0, () -> {}, effects::incrementAndGet));
    }
    @Test public void ownerWindowOpenedByFinalAdmissionStillPreventsCoordinateOrNodeDispatch() throws Exception {
        RunControl control = new RunControl(); AtomicInteger effects = new AtomicInteger(); AutoCloseable[] page = {null};
        try {
            assertThrows(NativeSensitiveStep.OwnerRequired.class, () -> NativePhoneEntry.call(control, 0,
                    () -> page[0] = NativeSensitiveStep.openOwnerUpdateWindow(), effects::incrementAndGet));
            assertEquals(0, effects.get());
        } finally { if (page[0] != null) page[0].close(); }
    }
    @Test public void systemInstallAndFixedOwnerControlsAreSensitiveEvenAfterProcessWindowIsGone() {
        for (String label : new String[]{"安装", "安装新版本", "安装更新", "Install", "Install now"}) {
            assertEquals(PERMISSION_ELEVATION, NativeSensitiveStep.classify(label, "", "com.android.packageinstaller", false, false, ""));
            assertEquals(PERMISSION_ELEVATION, NativeSensitiveStep.classify(label, "", "", false, false, ""));
        }
        assertEquals(PERMISSION_ELEVATION, NativeSensitiveStep.classify("", "dev.pocketengine:id/owner_app_update_install", "dev.pocketengine", false, false, ""));
        assertEquals(PERMISSION_ELEVATION, NativeSensitiveStep.classify("", "dev.pocketengine:id/owner_app_update_download", "dev.pocketengine", false, false, ""));
        assertEquals(PERMISSION_ELEVATION, NativeSensitiveStep.classify("", "", "com.android.packageinstaller", false, false, ""));
        assertNull(NativeSensitiveStep.classify("取消", "", "com.android.packageinstaller", false, false, ""));
        assertNull(NativeSensitiveStep.classify("返回", "", "com.android.packageinstaller", false, false, ""));
        assertNull(NativeSensitiveStep.classify("安装教程", "", "documentation", false, false, ""));
    }
    @Test public void imageOnlyInstallerCoordinatesCannotBypassAnEmptySemanticTree() throws Exception {
        java.lang.reflect.Constructor<AccessibilityForest> constructor = AccessibilityForest.class.getDeclaredConstructor();
        constructor.setAccessible(true); AccessibilityForest forest = constructor.newInstance(); forest.packageName = "com.android.packageinstaller";
        NativeSensitiveStep.OwnerRequired owner = assertThrows(NativeSensitiveStep.OwnerRequired.class,
                () -> NativeSensitiveStep.requireCoordinate(forest, 500, 500, 500, 500, false, new RunControl(), 0));
        assertEquals(PERMISSION_ELEVATION, owner.reason);
    }
    @Test public void genuineExistingPausedOwnerPermitDoesNotBecomeAgentAuthority() throws Exception {
        RunControl control = new RunControl(); control.pause(); long revision = control.pauseRevision();
        try (AutoCloseable page = NativeSensitiveStep.openOwnerUpdateWindow()) {
            assertEquals("owner", NativePhoneEntry.call(control, revision, true, () -> {}, () -> "owner"));
            assertThrows(Exception.class, () -> NativePhoneEntry.call(control, revision, () -> {}, () -> "agent"));
        }
    }
    @Test public void leavingPageDoesNotReleaseAnIndependentPendingSystemHandoff() throws Exception {
        RunControl control = new RunControl(); AutoCloseable page = NativeSensitiveStep.openOwnerUpdateWindow();
        AutoCloseable systemHandoff = NativeSensitiveStep.openOwnerUpdateWindow();
        try {
            page.close();
            assertThrows(NativeSensitiveStep.OwnerRequired.class, () -> NativePhoneEntry.call(control, 0, () -> {}, () -> "not entered"));
        } finally { page.close(); systemHandoff.close(); }
        assertEquals("entered", NativePhoneEntry.call(control, 0, () -> {}, () -> "entered"));
    }
}
