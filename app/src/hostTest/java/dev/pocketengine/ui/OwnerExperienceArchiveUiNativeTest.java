package dev.pocketengine.ui;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.SourceRef;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual owner coordinator -> original host/ZIP/repository. These are lifecycle and data
 * checks, not Compose screenshot, SAF, device or live-provider acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class OwnerExperienceArchiveUiNativeTest {
    private static OwnerBackupCoordinator backup(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception{var field=PocketWorldController.class.getDeclaredField("ownerArchives");field.setAccessible(true);return (OwnerBackupCoordinator)field.get(f.controller);}
    private static void message(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception {
        var repo=f.app.tasks.conversationRepository();repo.initializeEmpty();repo.transaction(()->{
            repo.saveConversation(new JSONObject().put("conversationId","archive-ui-conversation").put("title","原经历").put("createdAt",100L).put("updatedAt",100L));SourceRef source=new SourceRef("message","archive-ui-message",1,"owner","private");
            repo.saveSource(new JSONObject(source.toJson().toString()).put("conversationId","archive-ui-conversation").put("role","user").put("deleted",false));repo.saveMessage("archive-ui-conversation",new JSONObject().put("id",source.sourceId).put("revision",1L).put("role","user").put("content","主人页面应直接读到原句").put("at",100L));return null;
        });
    }
    private static CountDownLatch hold(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.io.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));return release;
    }
    @Test public void repeatedCaptureUsesOneOriginalCacheAndDeletedSourceDisappearsFromOwnerPage()throws Exception {
        try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            message(f);OwnerBackupCoordinator flow=backup(f);flow.open();f.settle();int before=f.app.features.ownerDataArchives(f.app).localCachesForOwner(new RunControl()).length();CountDownLatch release=hold(f);
            try{flow.create(Set.of("experiences"));flow.create(Set.of("experiences"));assertTrue(flow.getState().getBusy());}finally{release.countDown();}f.settle();assertNull(flow.getState().getError());assertEquals(before+1,f.app.features.ownerDataArchives(f.app).localCachesForOwner(new RunControl()).length());
            flow.readExperiencePage(0);f.settle();assertNull(flow.getState().getError());assertEquals("主人页面应直接读到原句",flow.getState().getExperiencePage().getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("text"));
            f.app.tasks.conversationRepository().transaction(()->{f.app.tasks.conversationRepository().deleteConversation("archive-ui-conversation","user_conversation_delete");return null;});flow.invalidateExperienceReadings();assertNull(flow.getState().getExperiencePage());flow.readExperiencePage(0);f.settle();JSONObject page=flow.getState().getExperiencePage();assertFalse(page.toString().contains("主人页面应直接读到原句"));assertEquals("blocked_by_known_source_barrier",page.getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("status"));
        }
    }
    @Test public void oldPageCallbacksCannotReappearAfterInvalidationBackgroundOrScopeChange()throws Exception {
        try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            message(f);OwnerBackupCoordinator flow=backup(f);flow.open();f.settle();flow.create(Set.of("experiences"));f.settle();assertNull(flow.getState().getError());
            CountDownLatch release=hold(f);try{flow.readExperiencePage(0);flow.invalidateExperienceReadings();}finally{release.countDown();}f.settle();assertNull(flow.getState().getExperiencePage());assertFalse(flow.getState().getBusy());
            release=hold(f);try{flow.readExperiencePage(0);flow.foreground(false);flow.foreground(true);}finally{release.countDown();}f.settle();assertNull(flow.getState().getExperiencePage());assertFalse(flow.getState().getBusy());
            var domain=PocketWorldController.class.getDeclaredField("ownerDomain");domain.setAccessible(true);domain.set(f.controller,"groups");assertThrows(IllegalStateException.class,()->flow.readExperiencePage(0));assertNull(flow.getState().getExperiencePage());domain.set(f.controller,null);
            flow.readExperiencePage(0);f.settle();assertNotNull(flow.getState().getExperiencePage());flow.closePage();assertFalse(flow.getState().getOpen());
        }
    }
    @Test public void confirmedSettingsStillQueuedWhenPageHidesReleaseSelectionAndNeverRestore()throws Exception {
        try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            assertTrue(f.app.getSharedPreferences("private_settings",0).edit().clear().commit());
            OwnerBackupCoordinator flow=backup(f);flow.open();f.settle();flow.create(Set.of("settings"));f.settle();
            assertNull(flow.getState().getError());flow.reviewPhase("settings");f.settle();assertNotNull(flow.getState().getReview());
            CountDownLatch release=hold(f);String action;
            try{
                flow.confirmPhase();action=flow.getState().getOriginalAction();assertNotNull(action);
                assertTrue(f.app.settings.modelSelection().status().pending());
                flow.foreground(false);
                assertFalse(f.app.settings.modelSelection().status().pending());assertNull(flow.getState().getOriginalAction());
            }finally{release.countDown();}
            f.settle();assertNull(f.app.tasks.get(action,false));
            assertFalse(f.app.getSharedPreferences("private_settings",0).contains("ownerConfigurationArchiveOrigins"));
        }
    }
    @Test public void rejectedSettingsSubmissionReleasesTheOriginalReservation()throws Exception {
        try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            assertTrue(f.app.getSharedPreferences("private_settings",0).edit().clear().commit());
            OwnerBackupCoordinator flow=backup(f);flow.open();f.settle();flow.create(Set.of("settings"));f.settle();
            assertNull(flow.getState().getError());flow.reviewPhase("settings");f.settle();assertNotNull(flow.getState().getReview());
            f.io.shutdown();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,flow::confirmPhase);
            assertFalse(f.app.settings.modelSelection().status().pending());assertNull(flow.getState().getOriginalAction());
            assertFalse(f.app.getSharedPreferences("private_settings",0).contains("ownerConfigurationArchiveOrigins"));
        }
    }
}
