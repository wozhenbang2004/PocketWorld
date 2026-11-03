package dev.pocketengine.phone.setup;

import android.app.Application;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.UserHandle;
import android.os.UserManager;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowUserManager;
import static org.junit.Assert.*;

/** Owner policy selection reads the same SDK35 package/profile fixture; it never creates a run or launches. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class, shadows = OwnerPhoneAppsNativeTest.OwnerLauncherFixture.class)
public final class OwnerPhoneAppsNativeTest {
    @Implements(LauncherApps.class)
    public static class OwnerLauncherFixture extends AndroidAppCopiesNativeTest.LauncherFixture {
        static boolean enabled;
        @Implementation protected boolean isPackageEnabled(String pkg, UserHandle user) {
            return enabled && AndroidAppCopiesNativeTest.PACKAGE.equals(pkg);
        }
    }
    private OwnerPhoneApps picker;
    @Before public void setup() throws Exception {
        AndroidAppCopiesNativeTest.installFixture();
        OwnerLauncherFixture.enabled = true;
        picker = new OwnerPhoneApps(AndroidAppCopiesNativeTest.app());
    }
    private OwnerPhoneApps.Selection work() throws Exception {
        return picker.list().apps().stream().filter(copy -> copy.userId() == 10).findFirst().orElseThrow();
    }

    @Test public void ownerPickerDistinguishesActualProfileAndInstallationWithoutIssuingAnAction() throws Exception {
        OwnerPhoneApps.Listing listed = picker.list();
        assertTrue(listed.complete()); assertEquals(2, listed.apps().size());
        OwnerPhoneApps.Selection selected = work();
        assertEquals(910, selected.profileSerial()); assertEquals(1_010_466, selected.uid());
        assertEquals(1, selected.versionCode()); assertFalse(selected.currentProfile());
        picker.requireCurrent(selected);
        assertTrue(AndroidAppCopiesNativeTest.LauncherFixture.calls.isEmpty());
    }

    @Test public void lockedRecreatedDisabledOrExpiredSelectionCannotBecomeThePersonalCopy() throws Exception {
        OwnerPhoneApps.Selection selected = work(); picker.requireCurrent(selected);
        UserManager users = AndroidAppCopiesNativeTest.app().getSystemService(UserManager.class);
        Shadows.shadowOf(users).setSerialNumberForUser(AndroidAppCopiesNativeTest.work(), 911);
        assertThrows(SecurityException.class, () -> picker.requireCurrent(selected));
        Shadows.shadowOf(users).setSerialNumberForUser(AndroidAppCopiesNativeTest.work(), 910);
        Shadows.shadowOf(users).setUserState(AndroidAppCopiesNativeTest.work(), ShadowUserManager.UserState.STATE_RUNNING_LOCKED);
        assertThrows(SecurityException.class, () -> picker.requireCurrent(selected));
        Shadows.shadowOf(users).setUserState(AndroidAppCopiesNativeTest.work(), ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED);
        OwnerLauncherFixture.enabled = false;
        assertThrows(SecurityException.class, () -> picker.requireCurrent(selected));
        OwnerLauncherFixture.enabled = true; picker.requireCurrent(selected);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(120));
        assertThrows(SecurityException.class, () -> picker.requireCurrent(selected));
        assertTrue(AndroidAppCopiesNativeTest.LauncherFixture.calls.isEmpty());
    }

    @Test public void changedVersionOrSignerIsRejectedEvenWhenPackageAndProfileStayTheSame() throws Exception {
        OwnerPhoneApps.Selection selected = work(); picker.requireCurrent(selected);
        AndroidAppCopiesNativeTest.installArchive(2);
        assertThrows(SecurityException.class, () -> picker.requireCurrent(selected));
        OwnerPhoneApps.Selection updated = work(); picker.requireCurrent(updated);
        var manager = AndroidAppCopiesNativeTest.app().getPackageManager();
        var archive = manager.getPackageArchiveInfo("/fixture/native-copy/base.apk", PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotNull(archive); assertNotNull(archive.signingInfo);
        Shadows.shadowOf(archive.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{6, 1, 9})});
        Shadows.shadowOf(manager).setPackageArchiveInfo("/fixture/native-copy/base.apk", archive);
        assertThrows(SecurityException.class, () -> picker.requireCurrent(updated));
        assertTrue(AndroidAppCopiesNativeTest.LauncherFixture.calls.isEmpty());
    }
}
