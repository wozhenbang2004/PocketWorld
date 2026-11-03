package dev.pocketengine.phone.setup;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowClipboardManager;
import static org.junit.Assert.*;

/** Real Android adapter/ClipboardManager and original recovery, with the OS null-read boundary explicit. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,
        shadows={AndroidClipboardUnreadableNativeTest.FocusRestrictedClipboard.class,AndroidClipboardUnreadableNativeTest.ForegroundProcess.class})
public final class AndroidClipboardUnreadableNativeTest {
    @Implements(ClipboardManager.class)
    public static final class FocusRestrictedClipboard extends ShadowClipboardManager {
        static boolean unreadable;
        static int writes,clears;
        @Implementation public ClipData getPrimaryClip(){return unreadable?null:super.getPrimaryClip();}
        @Implementation public void setPrimaryClip(ClipData clip){writes++;super.setPrimaryClip(clip);}
        @Implementation public void clearPrimaryClip(){clears++;super.clearPrimaryClip();}
    }
    @Implements(ActivityManager.class)
    public static final class ForegroundProcess extends ShadowActivityManager {
        @Implementation public static void getMyMemoryState(ActivityManager.RunningAppProcessInfo info){info.importance=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;}
    }
    @Before public void cleanFixture(){
        FocusRestrictedClipboard.unreadable=false;FocusRestrictedClipboard.writes=0;FocusRestrictedClipboard.clears=0;
        RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).edit().clear().commit();
    }
    private static ClipboardRestoration.Clipboard adapter(ClipboardRestoration recovery)throws Exception {
        Field field=ClipboardRestoration.class.getDeclaredField("clipboard");field.setAccessible(true);
        return (ClipboardRestoration.Clipboard)field.get(recovery);
    }
    @Test public void foregroundWithoutReadableOriginalCannotInventEmptyOrReplaceOwnerClipboard()throws Exception {
        Context context=RuntimeEnvironment.getApplication();ClipboardManager manager=context.getSystemService(ClipboardManager.class);
        manager.setPrimaryClip(ClipData.newPlainText("owner fixture","original owner fixture"));
        FocusRestrictedClipboard.unreadable=true;int writes=FocusRestrictedClipboard.writes;
        ActivityManager.RunningAppProcessInfo process=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(process);
        assertEquals(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,process.importance);
        AndroidClipboardRecovery.setOwnerAllowed(context,true);RunControl control=new RunControl();AtomicInteger pastes=new AtomicInteger();
        ClipboardRestoration recovery=AndroidClipboardRecovery.create(context,control,control.pauseRevision(),()->{});
        assertNull("Android null does not prove an empty clipboard even in a foreground process",adapter(recovery).snapshot());
        JSONObject result=recovery.paste("requested fixture",control,()->{pastes.incrementAndGet();return true;});
        assertFalse(result.getBoolean("accepted"));assertEquals("clipboard_unavailable",result.getString("error"));
        assertEquals(0,pastes.get());assertEquals(writes,FocusRestrictedClipboard.writes);assertEquals(0,FocusRestrictedClipboard.clears);
        assertFalse(recovery.pending());assertTrue(context.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getAll().isEmpty());
        FocusRestrictedClipboard.unreadable=false;
        assertEquals("original owner fixture",manager.getPrimaryClip().getItemAt(0).getText().toString());
    }
    @Test public void nullWithNoProvenReadabilityAlsoRefusesWithoutWritingOrClearing()throws Exception {
        Context context=RuntimeEnvironment.getApplication();RunControl control=new RunControl();AndroidClipboardRecovery.setOwnerAllowed(context,true);
        ClipboardRestoration recovery=AndroidClipboardRecovery.create(context,control,control.pauseRevision(),()->{});
        assertNull(adapter(recovery).snapshot());
        JSONObject result=recovery.paste("fixture",control,()->{fail("unreadable original must not paste");return true;});
        assertFalse(result.getBoolean("accepted"));assertEquals("clipboard_unavailable",result.getString("error"));
        assertEquals(0,FocusRestrictedClipboard.writes);assertEquals(0,FocusRestrictedClipboard.clears);assertFalse(recovery.pending());
    }
    @Test public void actuallyReadableOriginalStillUsesTheRealParcelSnapshotWithoutChangingIt()throws Exception {
        Context context=RuntimeEnvironment.getApplication();ClipboardManager manager=context.getSystemService(ClipboardManager.class);
        manager.setPrimaryClip(ClipData.newPlainText("owner fixture","original 中文 fixture"));
        ClipboardRestoration.Clipboard actual=adapter(AndroidClipboardRecovery.create(context));String snapshot=actual.snapshot();
        assertNotNull(snapshot);assertNotEquals("empty",snapshot);assertEquals(actual.fingerprint(snapshot),actual.fingerprint(actual.snapshot()));
        assertEquals("original 中文 fixture",manager.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(1,FocusRestrictedClipboard.writes);assertEquals(0,FocusRestrictedClipboard.clears);
    }
}
