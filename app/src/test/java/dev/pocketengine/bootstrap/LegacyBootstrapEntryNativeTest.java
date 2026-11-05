package dev.pocketengine.bootstrap;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.MainActivity;
import dev.pocketengine.features.assistant.CompatibilityChatHandoff;
import dev.pocketengine.phone.QuickPanelVoiceHandoff;
import dev.pocketengine.ui.ComposeActivity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowApplication;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real entry Activities with the Application initializer held before readiness.
 * This does not verify SQLite recovery, business import completion or device WebView rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
        shadows=LegacyBootstrapEntryNativeTest.HeldInitialization.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LegacyBootstrapEntryNativeTest {
    @Implements(EngineApplication.class)
    public static final class HeldInitialization extends ShadowApplication {
        @Implementation protected void onCreate() { }
    }

    private EngineApplication app;
    private final List<ActivityController<? extends Activity>> activities=new ArrayList<>();

    @Before public void holdBeforeBusinessOpen() {
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        assertFalse(app.businessReady());
        assertNull(app.settings);assertNull(app.tasks);assertNull(app.features);
    }

    @After public void closeOnlyFixtureResources() {
        for(int i=activities.size()-1;i>=0;i--){ActivityController<? extends Activity> owner=activities.get(i);
            if(!owner.get().isDestroyed())owner.destroy();}
        app.onTerminate();
    }

    private static Object field(Object target,String name)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }

    private ActivityController<ComposeActivity> openContent(Intent request,Bundle restored) {
        ActivityController<ComposeActivity> owner=Robolectric.buildActivity(ComposeActivity.class,request);
        activities.add(owner);owner.create(restored);return owner;
    }

    private static Intent share(String document) {
        return new Intent(Intent.ACTION_SEND).setType("application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM,Uri.parse("content://bootstrap-fixture/"+document));
    }

    private static Intent retained(ComposeActivity owner)throws Exception {
        return (Intent)field(owner,"freshBootstrapContent");
    }

    private void assertBusinessWasNotMounted(ComposeActivity owner)throws Exception {
        assertFalse((Boolean)field(owner,"businessMounted"));assertNull(field(owner,"controller"));
        assertNull(field(owner,"media"));assertNull(app.settings);assertNull(app.tasks);assertNull(app.features);
    }

    @Test public void legacyColdEntryRedirectsBeforeSettingsAndNullResourcesCanClose()throws Exception {
        ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class,
                new Intent(app,MainActivity.class).putExtra("openPanel","settings"));
        activities.add(owner);owner.create();MainActivity activity=owner.get();
        assertTrue(activity.isFinishing());
        Intent redirected=shadowOf(activity).getNextStartedActivity();assertNotNull(redirected);
        assertEquals(ComposeActivity.class.getName(),redirected.getComponent().getClassName());
        assertNotEquals(0,redirected.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TOP);
        assertEquals("settings",redirected.getStringExtra("openPanel"));
        assertFalse(activity.getIntent().hasExtra("openPanel"));
        assertTrue(java.util.Arrays.stream(MainActivity.class.getDeclaredFields()).noneMatch(value ->
                android.webkit.WebView.class.isAssignableFrom(value.getType()) ||
                java.util.concurrent.ExecutorService.class.isAssignableFrom(value.getType()) ||
                dev.pocketengine.media.MediaController.class.isAssignableFrom(value.getType())));
        assertNull(app.settings);
        owner.start().resume();
        assertNull("A finishing legacy entry must not redirect twice",shadowOf(activity).getNextStartedActivity());
        activity.onRequestPermissionsResult(41,new String[0],new int[0]);
        owner.pause().stop().destroy();
        assertNull(app.tasks);assertNull(app.features);
    }

    @Test public void newColdShareKeepsItsSelectionWithoutConstructingBusinessObjects()throws Exception {
        Intent request=share("first.pdf");ComposeActivity activity=openContent(request,null).get();
        Intent pending=retained(activity);assertNotNull(pending);assertNotSame(request,pending);
        assertEquals(Intent.ACTION_SEND,pending.getAction());
        assertEquals(Uri.parse("content://bootstrap-fixture/first.pdf"),pending.getParcelableExtra(Intent.EXTRA_STREAM));
        assertNotEquals(0,pending.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
        request.setAction(null);assertEquals(Intent.ACTION_SEND,pending.getAction());
        assertBusinessWasNotMounted(activity);
    }

    @Test public void recreationDoesNotRestoreTheEarlierShareAsANewSelection()throws Exception {
        Intent request=share("before-recreation.pdf");ActivityController<ComposeActivity> first=openContent(request,null);
        assertNotNull(retained(first.get()));Bundle saved=new Bundle();first.saveInstanceState(saved);first.destroy();
        ComposeActivity restored=openContent(new Intent(request),saved).get();
        assertNull("Saved Activity state is not a new owner content selection",retained(restored));
        assertBusinessWasNotMounted(restored);
    }

    @Test public void historyLaunchCannotRestoreAnOldShareIntoTheFreshSlot()throws Exception {
        Intent old=share("history.pdf").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        ComposeActivity activity=openContent(old,null).get();
        assertNull(retained(activity));assertBusinessWasNotMounted(activity);
    }

    @Test public void newIntentReplacesContentAndNeverRetainsOldVoiceContinueOrNonContentRoutes()throws Exception {
        ActivityController<ComposeActivity> owner=openContent(share("first.pdf"),null);
        Intent next=share("second.pdf");owner.newIntent(next);
        assertEquals(Uri.parse("content://bootstrap-fixture/second.pdf"),retained(owner.get()).getParcelableExtra(Intent.EXTRA_STREAM));
        Intent document=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://bootstrap-fixture/skill.md"),"text/markdown");
        owner.newIntent(document);assertEquals(document.getData(),retained(owner.get()).getData());

        Intent[] excluded={
            new Intent(QuickPanelVoiceHandoff.ACTION).putExtra(QuickPanelVoiceHandoff.TOKEN_EXTRA,"old-voice-token"),
            new Intent(Intent.ACTION_MAIN).putExtra(CompatibilityChatHandoff.EXTRA,"old-continue-token"),
            new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.invalid/old-document")),
            share("old-history.pdf").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        };
        for(Intent previous:excluded){owner.newIntent(next);assertNotNull(retained(owner.get()));owner.newIntent(previous);
            assertNull("Only the latest fresh content selection can remain",retained(owner.get()));}
        assertBusinessWasNotMounted(owner.get());assertNull(shadowOf(app).getNextStartedService());
    }
}
