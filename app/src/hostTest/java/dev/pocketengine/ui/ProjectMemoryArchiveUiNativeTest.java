package dev.pocketengine.ui;

import android.app.Application;
import android.os.Bundle;
import dev.pocketengine.core.RunControl;
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

/** Existing Controller -> existing backup coordinator -> native original archive, no fake backend.
 * This exercises UI contracts, not Compose visual/SAF/provider or physical phone acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ProjectMemoryArchiveUiNativeTest {
    private static OwnerBackupCoordinator backup(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception{var field=PocketWorldController.class.getDeclaredField("ownerArchives");field.setAccessible(true);return (OwnerBackupCoordinator)field.get(f.controller);}
    private static int caches(ProjectMemoryReviewUiNativeTest.Fixture f)throws Exception{return f.app.features.ownerDataArchives(f.app).localCachesForOwner(new RunControl()).length();}
    private static void restored(ProjectMemoryReviewUiNativeTest.Fixture f,OwnerBackupCoordinator flow)throws Exception{
        f.settle();String action=flow.getState().getOriginalAction();assertNotNull(action);long deadline=System.nanoTime()+10_000_000_000L;JSONObject row;
        do{row=f.app.features.ownerArchivePhaseForOwner(action);if(!"running".equals(row.getString("status")))break;Thread.sleep(5);}while(System.nanoTime()<deadline);assertEquals(row.toString(),"completed",row.getString("status"));do{row=f.app.features.queryOwnerArchivePhaseForOwner(action,new RunControl());if(!row.has("observation"))break;Thread.sleep(5);}while(System.nanoTime()<deadline);assertTrue(row.toString(),row.optBoolean("found"));flow.queryOriginal();f.settle();assertTrue(flow.getState().getResult().getBoolean("found"));
    }
    @Test public void existingMemoryReviewLaunchCapturesOnlyExactSelectedProjectAndKeepsOwnerGlobalSeparate()throws Exception{try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
        JSONObject chosen=f.create("备份项目"),other=f.create("不在范围的项目");f.memory.handle("save",new JSONObject().put("record",new JSONObject().put("text","主人全局不进入项目备份")));f.enter();f.choose(chosen);f.propose(f.file());f.controller.openProjectMemoryArchive();f.settle();var flow=backup(f);assertTrue(flow.getState().getOpen());assertEquals("memory_review",flow.getState().getPresentation());assertTrue(flow.getState().getProjectMemoryCurrent());assertEquals(chosen.getString("name"),flow.getState().getProjectMemoryName());flow.create(Set.of("projects","memory"));f.settle();assertNull(flow.getState().getError());JSONObject preview=flow.getState().getPreview().getJSONObject("review");assertEquals(1,preview.getJSONObject("projects").getInt("projects"));assertEquals(chosen.getString("spaceId"),preview.getJSONObject("memory").getString("originalSpaceId"));assertFalse(preview.getJSONObject("projects").toString().contains(other.getString("projectId")));assertEquals(1,caches(f));flow.reviewPhase("memory");f.settle();assertNull(flow.getState().getReview());assertNotNull(flow.getState().getError());flow.reviewPhase("projects");f.settle();assertNotNull(flow.getState().getReview());flow.confirmPhase();restored(f,flow);assertNotNull(flow.getState().getProjectAction());flow.reviewPhase("memory");f.settle();assertEquals("project_only",flow.getState().getReview().getJSONObject("nativeScope").getString("scopeMode"));flow.confirmPhase();restored(f,flow);JSONObject nativeResult=flow.getState().getResult().getJSONObject("nativeResult");assertEquals(0,nativeResult.getInt("formalMemoryActivated"));assertFalse(nativeResult.getBoolean("consentRestored"));flow.closePage();assertTrue(f.token().metadata().getBoolean("selectionCurrent"));assertEquals("project",f.snapshot().getString("namespaceStatus"));
    }}
    @Test public void switchingOrClosingReviewDropsArchiveHolderAndQueuedOldCaptureCannotFallBackGlobal()throws Exception{try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
        JSONObject first=f.create("甲"),second=f.create("乙");f.enter();f.choose(first);f.controller.openProjectMemoryArchive();f.settle();var flow=backup(f);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.io.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));try{flow.create(Set.of("projects","memory"));f.controller.selectMemoryNamespace(second);}finally{release.countDown();}f.settle();assertFalse(flow.getState().getOpen());assertFalse(flow.getState().getProjectMemoryCurrent());assertEquals(0,caches(f));flow.create(Set.of("projects","memory"));f.settle();assertEquals(0,caches(f));assertNotNull(flow.getState().getError());assertEquals(second.getString("spaceId"),f.snapshot().getJSONObject("scope").getString("spaceId"));
    }}
    @Test public void backgroundAndBundlePositionNeverRestoreProjectCaptureAuthorityOrSilentlyWidenSections()throws Exception{try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
        var choice=f.create("原项目");f.enter();f.choose(choice);f.controller.openProjectMemoryArchive();f.settle();var flow=backup(f);flow.create(Set.of("projects","memory","artifacts"));assertNotNull(flow.getState().getError());assertEquals(0,caches(f));Bundle saved=new Bundle();flow.save(saved);String pin=saved.getString("ownerDataArchivePosition");assertNotNull(pin);assertFalse(pin.contains("projectMemory"));assertFalse(pin.contains("consentId"));assertFalse(pin.contains("spaceId"));flow.foreground(false);flow.foreground(true);flow.create(Set.of("projects","memory"));f.settle();assertFalse(flow.getState().getProjectMemoryCurrent());assertEquals(0,caches(f));flow.restore(saved);flow.create(Set.of("projects","memory"));f.settle();assertEquals(0,caches(f));
    }}
    @Test public void privateOrGroupDomainWithoutExactCurrentProjectChoiceCannotOpenProjectArchive()throws Exception{try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
        var choice=f.create("原项目");f.enter();assertThrows(SecurityException.class,()->f.controller.openProjectMemoryArchive());f.choose(choice);f.controller.leaveOwnerDomain("memoryReview");assertThrows(IllegalStateException.class,()->f.controller.openProjectMemoryArchive());var domain=PocketWorldController.class.getDeclaredField("ownerDomain");domain.setAccessible(true);domain.set(f.controller,"groups");assertThrows(IllegalStateException.class,()->f.controller.openProjectMemoryArchive());domain.set(f.controller,null);assertEquals(0,caches(f));
    }}
}
