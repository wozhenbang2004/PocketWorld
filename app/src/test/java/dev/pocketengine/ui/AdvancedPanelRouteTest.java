package dev.pocketengine.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.MainActivity;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Original component redirects and real Controller navigation; no WebView rendering claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=ReadyApplicationFixture.NoUnrelatedStartup.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AdvancedPanelRouteTest {
    private EngineApplication app;
    @Before public void prepare()throws Exception {
        java.security.Security.addProvider(new java.security.Provider("PanelRouteKeyStore","1","Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();ReadyApplicationFixture.initializeHost(app);
    }
    @After public void close()throws Exception {try{if(app!=null)ReadyApplicationFixture.closeHost(app);}finally{java.security.Security.removeProvider("PanelRouteKeyStore");}}
    private static void set(Object target,String name,Object value)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private final class Workspace implements AutoCloseable {
        final ComposeActivity activity=Robolectric.buildActivity(ComposeActivity.class).get();
        final MediaController media=new MediaController(activity,app::publish);
        final PocketWorldController controller=new PocketWorldController(activity,app);
        Workspace()throws Exception {set(activity,"media",media);set(activity,"controller",controller);set(activity,"businessMounted",true);set(activity,"businessGeneration",app.bootstrapState().getLong("generation"));controller.attach();}
        void deliver(Intent intent)throws Exception {var method=ComposeActivity.class.getDeclaredMethod("onNewIntent",Intent.class);method.setAccessible(true);method.invoke(activity,intent);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        @Override public void close(){controller.detach();media.close();}
    }
    private Intent redirected(Intent incoming) {
        var lifecycle=Robolectric.buildActivity(MainActivity.class,incoming).create();
        try {assertTrue(lifecycle.get().isFinishing());Intent result=Shadows.shadowOf(lifecycle.get()).getNextStartedActivity();assertNotNull(result);assertEquals(ComposeActivity.class.getName(),result.getComponent().getClassName());assertNotEquals(0,result.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TOP);assertNotEquals(0,result.getFlags()&Intent.FLAG_ACTIVITY_SINGLE_TOP);assertNull(Shadows.shadowOf(lifecycle.get()).getNextStartedActivity());return result;}
        finally {lifecycle.destroy();}
    }
    @Test public void coldReadyDeliversEachExactTargetAndConsumesItOnce()throws Exception {
        try(Workspace workspace=new Workspace()) {
            for(String target:new String[]{"channels","plugins","proxy","settings"}) {
                Intent original=new Intent(app,MainActivity.class).putExtra("openPanel",target),next=redirected(original);
                assertFalse(original.hasExtra("openPanel"));assertEquals(1,next.getExtras().size());
                int before=workspace.controller.getState().getRequestedConnectionRevision();workspace.deliver(next);
                assertEquals(MainActivity.sectionForPanel(target),workspace.controller.getState().getRequestedConnectionSection());
                assertEquals(before+1,workspace.controller.getState().getRequestedConnectionRevision());assertFalse(next.hasExtra("openPanel"));
                workspace.deliver(next);assertEquals(before+1,workspace.controller.getState().getRequestedConnectionRevision());
            }
        }
    }
    @Test public void reusedActivityDeliversNewTargetAndRejectsUnknownWithoutEchoingInput()throws Exception {
        try(Workspace workspace=new Workspace()) {
            workspace.deliver(MainActivity.advancedPanelIntent(app,"channels"));workspace.deliver(MainActivity.advancedPanelIntent(app,"proxy"));
            assertEquals("proxy",workspace.controller.getState().getRequestedConnectionSection());int before=workspace.controller.getState().getRequestedConnectionRevision();
            Intent invalid=redirected(new Intent(app,MainActivity.class).putExtra("openPanel","SECRET-invalid-target"));
            assertFalse(invalid.getExtras().toString().contains("SECRET"));workspace.deliver(invalid);
            assertEquals(before,workspace.controller.getState().getRequestedConnectionRevision());assertFalse(invalid.hasExtra("openPanel"));
            assertEquals("不支持的设置页面",workspace.controller.getState().getError());
            assertFalse(String.valueOf(workspace.controller.getState().getError()).contains("SECRET"));
        }
    }
    @Test public void composeControllerLaunchesOnlyKnownUnifiedTargets()throws Exception {
        try(Workspace workspace=new Workspace()) {
            for(String target:new String[]{"channels","plugins","proxy","settings"}) {
                workspace.controller.openAdvanced(target);Intent intent=Shadows.shadowOf(workspace.activity).getNextStartedActivity();
                assertEquals(ComposeActivity.class.getName(),intent.getComponent().getClassName());assertEquals(target,intent.getStringExtra("openPanel"));assertEquals(1,intent.getExtras().size());
            }
            assertThrows(IllegalArgumentException.class,()->workspace.controller.openAdvanced("SECRET-invalid-target"));assertNull(Shadows.shadowOf(workspace.activity).getNextStartedActivity());
        }
    }
    @Test public void retiredEntryForwardsNoSettingsFilesCommandsOrUriGrants() {
        Intent original=new Intent(Intent.ACTION_SEND,Uri.parse("content://fixture/private.txt")).setClass(app,MainActivity.class)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra("openPanel","settings").putExtra("config","SECRET-CONFIG").putExtra("action","startRun")
                .putExtra(Intent.EXTRA_STREAM,Uri.parse("content://fixture/private.txt"));
        Intent next=redirected(original);assertNull(next.getAction());assertNull(next.getData());assertNull(next.getClipData());
        assertEquals(0,next.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));assertEquals(1,next.getExtras().size());
        assertFalse(next.getExtras().toString().contains("SECRET-CONFIG"));
        assertTrue(java.util.Arrays.stream(MainActivity.class.getDeclaredFields()).noneMatch(field->android.webkit.WebView.class.isAssignableFrom(field.getType())));
    }
    @Test public void oldAutomationGoalAndNotificationDeepLinkOnlyOpenTheSameReadOnlyPage()throws Exception {
        try(Workspace workspace=new Workspace()) {
            Intent original=new Intent(app,MainActivity.class).setData(Uri.parse("pocketengine://automation/original-rule")).putExtra("automationGoal","SECRET-OLD-GOAL");
            Intent next=redirected(original);assertEquals("",next.getStringExtra("automationGoal"));assertFalse(next.getExtras().toString().contains("SECRET-OLD-GOAL"));
            workspace.deliver(next);assertEquals("automation",workspace.controller.getState().getRequestedConnectionSection());assertFalse(next.hasExtra("automationGoal"));assertNull(next.getData());
            assertEquals(0,app.tasks.list().length());
        }
    }
}
