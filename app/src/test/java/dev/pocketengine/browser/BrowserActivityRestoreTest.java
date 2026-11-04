package dev.pocketengine.browser;

import android.app.Application;
import android.content.Intent;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real A/B Activity callbacks and native profile/lease objects. No device stack or provider rendering claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserActivityRestoreTest {
    private final List<Window> windows=new ArrayList<>();
    private final class Window {
        final ActivityController<BrowserActivity> controller=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();
        final BrowserActivity activity=controller.get();
        boolean started=true,resumed=true,destroyed;
        Window(){windows.add(this);}
        void background(){if(resumed){controller.pause();resumed=false;}if(started){controller.stop();started=false;}}
        void foreground(){if(!started){controller.start();started=true;}if(!resumed){controller.resume();resumed=true;}}
        void destroy(){if(!destroyed){background();controller.destroy();destroyed=true;}}
    }
    @After public void close(){for(int i=windows.size()-1;i>=0;i--)windows.get(i).destroy();BrowserActivity.setEvents(null);BrowserActivity.setOwnerPanelInstaller(null);}
    @Test public void olderActivityRegistersAgainAfterNewerActivityCloses()throws Exception {
        Window a=new Window();a.background();Window b=new Window();assertSame(b.activity,BrowserActivity.current());
        b.destroy();assertNull(BrowserActivity.current());a.foreground();
        assertSame(a.activity,BrowserActivity.current());assertTrue(a.activity.snapshot().getBoolean("open"));
        BrowserModule module=new BrowserModule(new dev.pocketengine.features.FeatureContext(a.activity,null,null,event->{}));
        try{assertEquals(1,module.nativeTabs().getJSONArray("tabs").length());}finally{module.close();}
    }
    @Test public void returningActivityGetsFreshSessionWithoutOldGrantPagesOrMutationReplay()throws Exception {
        Window a=new Window();WebView oldPage=web(a.activity);oldPage.loadUrl("https://example.com/private?original=only");
        BrowserProfileRegistry.Session original=session(a.activity);JSONObject grant=acquire(a.activity);String oldTab=a.activity.tabsForOwner().getJSONArray("tabs").getJSONObject(0).getString("tabId");
        CompletableFuture<JSONObject> dispatched=new CompletableFuture<>();set(a.activity,"pending",dispatched);set(a.activity,"pendingMutation",true);
        a.background();Window b=new Window();assertFalse(original.current());b.destroy();a.foreground();
        assertTrue(session(a.activity).current());assertNotEquals(original.sessionId,session(a.activity).sessionId);assertEquals(original.profileId,session(a.activity).profileId);
        assertFalse(original.current());assertNotSame(oldPage,web(a.activity));assertFalse(a.activity.snapshot().getJSONObject("lease").getBoolean("active"));
        assertEquals("unknown",dispatched.getNow(new JSONObject()).optString("status"));assertFalse(dispatched.get().getBoolean("verified"));
        assertFalse(a.activity.tabsForOwner().toString().contains(oldTab));assertNull(get(a.activity,"taskSpace"));assertNull(get(a.activity,"taskLease"));
        assertEquals("https://www.bing.com",web(a.activity).getUrl());CompletableFuture<JSONObject> stale=new CompletableFuture<>();a.activity.execute("reload",grant,stale);assertTrue(stale.isCompletedExceptionally());
    }
    @Test public void newIntentRestoresRegistrationBeforeResumeAndResumeDoesNotRenewAgain()throws Exception {
        Window a=new Window();BrowserProfileRegistry.Session original=session(a.activity);a.background();Window b=new Window();b.destroy();
        a.controller.newIntent(new Intent(a.activity,BrowserActivity.class).putExtra("showResearch",true));
        assertSame(a.activity,BrowserActivity.current());BrowserProfileRegistry.Session restored=session(a.activity);assertTrue(restored.current());assertNotEquals(original.sessionId,restored.sessionId);
        a.foreground();assertSame(restored,session(a.activity));assertFalse(a.activity.snapshot().getJSONObject("lease").getBoolean("active"));
    }
    @Test public void finishingNewerActivityCannotClearOrEndExplicitlyReopenedOlderActivity()throws Exception {
        Window a=new Window();a.background();Window b=new Window();BrowserProfileRegistry.Session newer=session(b.activity);b.activity.finish();b.background();a.foreground();
        assertTrue(newer.current());assertSame(a.activity,BrowserActivity.current());assertNull(session(a.activity));
        a.activity.openForOwner("");BrowserProfileRegistry.Session restored=session(a.activity);assertTrue(restored.current());assertFalse(newer.current());
        b.destroy();assertSame(a.activity,BrowserActivity.current());assertSame(restored,session(a.activity));assertTrue(restored.current());
    }
    @Test public void normalPauseResumeRetainsTheSameLiveSessionViewAndGrant()throws Exception {
        Window a=new Window();BrowserProfileRegistry.Session original=session(a.activity);WebView page=web(a.activity);JSONObject grant=acquire(a.activity);a.background();a.foreground();
        assertSame(a.activity,BrowserActivity.current());assertSame(original,session(a.activity));assertSame(page,web(a.activity));assertTrue(original.current());assertTrue(a.activity.snapshot().getJSONObject("lease").getBoolean("active"));
        assertEquals(grant.getString("leaseId"),a.activity.snapshot().getJSONObject("lease").getString("leaseId"));
    }
    @Test public void changedSelectedProfileCannotReceiveAnOldPageOrAutomaticSession()throws Exception {
        Window a=new Window();BrowserProfiles profiles=BrowserProfiles.get(a.activity);BrowserProfileRegistry registry=(BrowserProfileRegistry)get(profiles,"registry");BrowserProfileRegistry.Session original=session(a.activity);
        web(a.activity).loadUrl("https://example.com/private?do-not-move=1");a.background();Window b=new Window();
        String other=registry.create("另一个资料",registry.revision(),true).getString("id");registry.select(other,registry.revision(),true);b.destroy();a.foreground();
        assertSame(a.activity,BrowserActivity.current());assertFalse(original.current());assertNull(session(a.activity));assertNull(web(a.activity));assertNull(get(registry,"session"));
        assertEquals(0,a.activity.tabsForOwner().getJSONArray("tabs").length());assertFalse(a.activity.snapshot().getJSONObject("lease").getBoolean("active"));assertFalse(a.activity.snapshot().getString("profileError").isEmpty());
        assertEquals("",get(a.activity,"chromeAddress"));assertEquals("https://www.bing.com",get(a.activity,"recoveryUrl"));
    }
    @Test public void restoringOldActivityDoesNotInvalidateALiveSuccessorsSessionOrGrant()throws Exception {
        Window a=new Window();a.background();Window b=new Window();BrowserProfileRegistry.Session successor=session(b.activity);JSONObject grant=acquire(b.activity);b.background();a.foreground();
        assertTrue(successor.current());assertEquals(grant.getString("leaseId"),b.activity.snapshot().getJSONObject("lease").getString("leaseId"));
        assertSame(a.activity,BrowserActivity.current());assertNull(session(a.activity));assertNull(web(a.activity));assertFalse(a.activity.snapshot().getString("profileError").isEmpty());
        b.destroy();assertSame(a.activity,BrowserActivity.current());
    }
    @Test public void newerDestroyAfterOlderResumeCompletesDeferredRecoveryWithoutStealingItsLiveSession()throws Exception {
        Window a=new Window();BrowserProfileRegistry.Session original=session(a.activity);a.background();Window b=new Window();BrowserProfileRegistry.Session successor=session(b.activity);JSONObject grant=acquire(b.activity);b.background();a.foreground();
        assertSame(a.activity,BrowserActivity.current());assertNull(session(a.activity));assertTrue(successor.current());
        a.background();a.foreground();assertNull(session(a.activity));assertTrue(successor.current());assertEquals(grant.getString("leaseId"),b.activity.snapshot().getJSONObject("lease").getString("leaseId"));
        b.destroy();assertSame(a.activity,BrowserActivity.current());assertNotNull(session(a.activity));assertTrue(session(a.activity).current());
        assertNotEquals(original.sessionId,session(a.activity).sessionId);assertNotEquals(successor.sessionId,session(a.activity).sessionId);assertFalse(a.activity.snapshot().getJSONObject("lease").getBoolean("active"));
    }
    @Test public void newerDestroyAfterOldersIntentWaitsForItsForegroundBeforeRecovering()throws Exception {
        Window a=new Window();BrowserProfileRegistry.Session original=session(a.activity);a.background();Window b=new Window();BrowserProfileRegistry.Session successor=session(b.activity);b.background();
        a.controller.newIntent(new Intent(a.activity,BrowserActivity.class));assertSame(a.activity,BrowserActivity.current());assertNull(session(a.activity));assertTrue(successor.current());
        b.destroy();assertNull(session(a.activity));a.foreground();assertSame(a.activity,BrowserActivity.current());assertNotNull(session(a.activity));assertTrue(session(a.activity).current());assertNotEquals(original.sessionId,session(a.activity).sessionId);
    }
    private static JSONObject acquire(BrowserActivity browser)throws Exception {CompletableFuture<JSONObject> result=new CompletableFuture<>();browser.execute("acquire",session(browser).identity().put("taskId","task-a").put("runId","run-a").put("principalId","owner").put("spaceId","private"),result);return result.get();}
    private static BrowserProfileRegistry.Session session(BrowserActivity browser)throws Exception{return (BrowserProfileRegistry.Session)get(browser,"profileSession");}
    private static WebView web(BrowserActivity browser)throws Exception{return (WebView)get(browser,"web");}
    private static Object get(Object object,String name)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
}
