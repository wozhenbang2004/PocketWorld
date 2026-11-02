package dev.pocketengine.displayhelper;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real directory boundaries only; no virtual display or privileged shell is created by these tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DisplaySessionDirectoryBoundaryTest {
    private DisplaySessionDirectory directory(){return new DisplaySessionDirectory(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),12345);}
    @Test public void statusNeverClaimsOneOtherRunsDisplayAsTheCallersSession()throws Exception {try(DisplaySessionDirectory directory=directory()){JSONObject status=directory.handle(new JSONObject().put("command","status"));assertTrue(status.getBoolean("multiSession"));assertFalse(status.getBoolean("sessionActive"));assertTrue(status.isNull("sessionId"));assertEquals(0,status.getJSONArray("sessions").length());}}
    @Test public void missingNativeOwnerAndUnknownOwnerCannotMutateOrCreateASession()throws Exception {try(DisplaySessionDirectory directory=directory()){assertThrows(SecurityException.class,()->directory.handle(new JSONObject().put("command","create")));assertThrows(DisplayEngine.Failure.class,()->directory.handle(new JSONObject().put("command","release").put("ownerRunToken","11111111-1111-1111-1111-111111111111").put("sessionId","foreign").put("displayId",7)));assertEquals(0,directory.handle(new JSONObject().put("command","status")).getJSONArray("sessions").length());}}
    @Test public void closedDirectoryCannotReviveAnOldOriginalSession()throws Exception {DisplaySessionDirectory directory=directory();directory.close();assertThrows(DisplayEngine.Failure.class,()->directory.handle(new JSONObject().put("command","create").put("ownerRunToken","11111111-1111-1111-1111-111111111111")));assertThrows(SecurityException.class,()->new DisplaySessionDirectory(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),2000));}
}
