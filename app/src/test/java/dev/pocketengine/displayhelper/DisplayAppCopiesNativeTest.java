package dev.pocketengine.displayhelper;

import android.app.ActivityOptions;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.setup.AndroidAppCopies;
import dev.pocketengine.phone.setup.AndroidAppCopiesNativeTest;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** App-generated reference reaches the real helper consumer; LauncherApps service is an explicit fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows=AndroidAppCopiesNativeTest.LauncherFixture.class)
public final class DisplayAppCopiesNativeTest {
    @Before public void setup()throws Exception{AndroidAppCopiesNativeTest.installFixture();}
    private static final class Gate implements DisplayTypedIntent.Gate {
        int claims;Runnable duringClaim=()->{};
        public void check(){}
        public void requireAppFree(String pkg,int user){assertEquals(AndroidAppCopiesNativeTest.PACKAGE,pkg);assertEquals(10,user);claims++;duringClaim.run();}
    }
    @Test public void originalRelatedProfileReferenceReachesOnlyItsPinnedBackgroundDisplay()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(AndroidAppCopiesNativeTest.app(),7);RunControl control=AndroidAppCopiesNativeTest.control();try {
            JSONObject exact=copies.require(AndroidAppCopiesNativeTest.choose(copies.list(control),10),control);Gate gate=new Gate();JSONObject receipt=DisplayAppCopies.launch(AndroidAppCopiesNativeTest.app(),7,exact,gate);
            assertEquals(1,gate.claims);assertEquals(1,AndroidAppCopiesNativeTest.LauncherFixture.calls.size());var launch=AndroidAppCopiesNativeTest.LauncherFixture.calls.get(0);
            assertEquals(AndroidAppCopiesNativeTest.work(),launch.user());assertEquals(7,ActivityOptions.fromBundle(launch.options()).getLaunchDisplayId());assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("actualUserVerified"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));
        }finally{copies.clear();}
    }
    @Test public void wrongDisplayOrExpiredReferenceNeverBecomesAnUnqualifiedLaunch()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(AndroidAppCopiesNativeTest.app(),7);RunControl control=AndroidAppCopiesNativeTest.control();try {
            JSONObject exact=copies.require(AndroidAppCopiesNativeTest.choose(copies.list(control),10),control);assertThrows(SecurityException.class,()->DisplayAppCopies.launch(AndroidAppCopiesNativeTest.app(),8,exact,new Gate()));
            exact.put("referenceExpiresAtUptimeMs",android.os.SystemClock.uptimeMillis());assertThrows(SecurityException.class,()->DisplayAppCopies.launch(AndroidAppCopiesNativeTest.app(),7,exact,new Gate()));assertTrue(AndroidAppCopiesNativeTest.LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
    @Test public void changedInstallAfterOwnershipCheckStopsHelperEntry()throws Exception {
        AndroidAppCopies copies=new AndroidAppCopies(AndroidAppCopiesNativeTest.app(),7);RunControl control=AndroidAppCopiesNativeTest.control();try {
            JSONObject exact=copies.require(AndroidAppCopiesNativeTest.choose(copies.list(control),10),control);Gate gate=new Gate();gate.duringClaim=()->{try{AndroidAppCopiesNativeTest.installArchive(2);}catch(Exception e){throw new AssertionError(e);}};
            assertThrows(SecurityException.class,()->DisplayAppCopies.launch(AndroidAppCopiesNativeTest.app(),7,exact,gate));assertEquals(1,gate.claims);assertTrue(AndroidAppCopiesNativeTest.LauncherFixture.calls.isEmpty());
        }finally{copies.clear();}
    }
}
