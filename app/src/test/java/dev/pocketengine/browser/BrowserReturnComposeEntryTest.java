package dev.pocketengine.browser;

import android.content.Intent;
import android.os.Looper;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.ui.ComposeActivity;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Two actual production Activity classes and newIntent/lifecycle on host Android framework; not a device TaskManager/Compose renderer/real keyboard or latest whole-App integration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserReturnComposeEntryTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("BrowserReturnKeyStore","1","Explicit host-only AndroidKeyStore fixture"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    static String requested(ComposeActivity owner)throws Exception {Field f=ComposeActivity.class.getDeclaredField("controller");f.setAccessible(true);Object controller=f.get(owner);Object state=controller.getClass().getMethod("getState").invoke(controller);return(String)state.getClass().getMethod("getRequestedTaskId").invoke(state);}
    @Test public void actualOwnerActivityConsumesOriginalTaskThenBrowserNewIntentRestoresSamePageWithoutNewRun()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();BrowserActivity.setOwnerPanelInstaller(null);var browserController=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();BrowserActivity browser=browserController.get();org.robolectric.android.controller.ActivityController<ComposeActivity> ownerController=null;
        try {String task=app.tasks.createCapabilityTask("回到这项原任务，再继续看同一页","").getString("id"),other=app.tasks.createCapabilityTask("另一项只读查看目标","").getString("id");browser.openForOwner("https://owner.example/reading?version=1#kept");WebView web=BrowserReturnActivityTest.web(browser);String session=browser.snapshot().getString("browserSessionId");BrowserReturnActivityTest.launcher(browser);browser.showResearchPanelForOwner(task);assertTrue(((android.widget.Button)browser.findViewById(android.R.id.content).findViewWithTag("browser_return_owner")).performClick());Intent returned=Shadows.shadowOf(browser).getNextStartedActivity();assertEquals(task,returned.getStringExtra("quickPanelTaskId"));browserController.pause().stop();
            ownerController=Robolectric.buildActivity(ComposeActivity.class,returned);ownerController.create().start().resume();assertEquals(task,requested(ownerController.get()));assertFalse(ownerController.get().getIntent().hasExtra("quickPanelTaskId"));Intent again=new Intent(returned).putExtra("quickPanelTaskId",other);ownerController.newIntent(again);assertEquals(other,requested(ownerController.get()));assertFalse(ownerController.get().getIntent().hasExtra("quickPanelTaskId"));
            BrowserModule module=(BrowserModule)app.features.module("browser");module.nativeOpenResearchUi(task);Intent reopen=Shadows.shadowOf(app).getNextStartedActivity();assertEquals(task,reopen.getStringExtra("browserResearchTaskId"));assertNotEquals(0,reopen.getFlags()&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);ownerController.pause().stop();browser.onNewIntent(reopen);browserController.restart().start().resume();assertSame(browser,BrowserActivity.current());assertSame(web,BrowserReturnActivityTest.web(browser));assertEquals("https://owner.example/reading?version=1#kept",web.getUrl());assertEquals(session,browser.snapshot().getString("browserSessionId"));assertEquals(task,browser.researchTaskContextForOwner());assertEquals(2,app.tasks.list().length());assertEquals(0,app.tasks.get(task,false).getJSONArray("operations").length());assertEquals(0,app.tasks.get(task,false).optInt("coordinatorEpoch"));assertFalse(browser.isFinishing());
        } finally {if(ownerController!=null){if(!ownerController.get().isDestroyed())ownerController.destroy();}if(!browser.isDestroyed())browserController.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();Field field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);((ExecutorService)field.get(app)).shutdownNow();app.features.close();BrowserActivity.setOwnerPanelInstaller(null);BrowserActivity.setEvents(null);java.security.Security.removeProvider("BrowserReturnKeyStore");}
    }
}
