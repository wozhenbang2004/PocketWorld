package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real large evidence through the existing child Runner, original compactor and same native parent pool. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamResearchCompactionNativeJourneyTest {
    private static SQLiteDatabase sql(dev.pocketengine.store.TaskStore tasks)throws Exception{var f=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static void closeCoordinator(TeamCoordinator coordinator)throws Exception {coordinator.close();for(String field:new String[]{"coordinator","members"}){var f=TeamCoordinator.class.getDeclaredField(field);f.setAccessible(true);assertTrue(((ExecutorService)f.get(coordinator)).awaitTermination(5,TimeUnit.SECONDS));}}
    private static void expandNativeBudget(TeamResearchAgentNativeJourneyTest.Fixture f)throws Exception {
        f.waitIdle();TeamResearchHost.Page page=f.research.openBudget(f.coordinator,f.run);page.setForeground(true);var review=page.review(32,24);int before=f.http.get();page.approve(review,review.view().getString("digest"));page.close();assertEquals(before,f.http.get());f.resumeNative();
    }
    private static JSONObject pauseAfterRealPublishedSummary(TeamResearchAgentNativeJourneyTest.Fixture f)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);boolean entered=false;while(System.nanoTime()<until){if(f.compactMainEntered.await(100,TimeUnit.MILLISECONDS)){entered=true;break;}if(f.failure.get()!=null)throw new AssertionError(f.failure.get());if(f.coordinator.parentExecutorState(f.run).optBoolean("resourcesReleased")&&!f.coordinator.get(f.run).optString("status").matches("running|queued"))throw new AssertionError("original compaction stopped before projected MAIN: "+f.child());}assertTrue("real128k route must hit original compactor",entered);JSONObject child=f.child();JSONObject checkpoint;try(var row=sql(f.tasks).rawQuery("SELECT length(CAST(checkpoint AS BLOB)),checkpoint FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{child.getString("id"),child.getString("runId"),String.valueOf(child.getInt("coordinatorEpoch"))})){assertTrue(row.moveToFirst());assertTrue("fixture checkpoint audit is bounded",row.getLong(0)<=1_048_576);checkpoint=new JSONObject(row.getString(1));assertFalse(row.moveToNext());}assertNotNull(checkpoint.optJSONObject("contextCompaction"));assertTrue(f.compactSummaries.get()>0);
        String root=System.getProperty("a25.researchEvidenceDir","");if(!root.isEmpty()){Path dir=Path.of(root,f.run);Files.createDirectories(dir);byte[] bytes=checkpoint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);assertTrue(bytes.length<=1_048_576);Files.write(dir.resolve("original-published-checkpoint-before-pause.json"),bytes);}
        JSONObject view=f.coordinator.get(f.run),member=view.getJSONArray("members").getJSONObject(0),input=new JSONObject().put("runId",f.run).put("memberId","a").put("expectedRevision",view.getLong("revision")).put("expectedGeneration",view.getInt("generation")).put("expectedMemberGeneration",member.getInt("generation"));f.coordinator.reviewedControl("pause",input,f.model,f.materials,null);f.releaseCompactMain.countDown();f.waitIdle();assertFalse(f.tasks.hasUnresolved(child.getString("id")));return child;
    }
    @Test public void actual128kResearchSummaryRawReadAndColdCheckpointContinueUseSameMeteredPool()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(true,false,false,false,true)){
            expandNativeBudget(f);JSONObject before=pauseAfterRealPublishedSummary(f);int tools=f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"),summaries=f.compactSummaries.get();closeCoordinator(f.coordinator);f.coordinator=null;f.openCoordinator(false);f.resumeNative();f.waitIdle();
            JSONObject current=f.child(),pool=f.tasks.teamBudgetSnapshot(f.run);f.trace("compact-final-child",new JSONObject().put("id",current.getString("id")).put("runId",current.getString("runId")).put("epoch",current.getInt("coordinatorEpoch")).put("status",current.optString("status")).put("summary",current.optString("summary")).put("evidence",current.optJSONObject("evidence")));f.trace("compact-final-budget",pool);assertEquals("completed",f.coordinator.get(f.run).getString("status"));assertEquals("completed",current.getString("status"));assertEquals(before.getString("id"),current.getString("id"));assertEquals(before.getInt("coordinatorEpoch")+1,current.getInt("coordinatorEpoch"));assertEquals(16,f.completedTool("team_read_source"));assertEquals(1,f.completedTool("memory_raw_query"));assertEquals(1,f.completedTool("memory_raw_read"));assertTrue(pool.getInt("toolOperationsReservedOrUsed")>=tools);assertEquals(f.http.get(),pool.getInt("modelRequestsReservedOrUsed"));assertEquals(32,pool.getInt("modelRequestLimit"));assertEquals(24,pool.getInt("toolOperationLimit"));assertTrue("old-scope summary must not silently survive native epoch change",f.compactSummaries.get()>summaries);assertFalse(f.tasks.hasUnresolved(current.getString("id")));assertFalse(current.getJSONObject("evidence").getBoolean("businessVerified"));
            int actualSummaries=0;try(var rows=sql(f.tasks).rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{current.getString("id")})){assertTrue(rows.moveToFirst());assertNotNull(rows.getString(0));}try(var rows=sql(f.tasks).rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_type='inference_receipt'",new String[]{current.getString("id")})){while(rows.moveToNext()){JSONObject receipt=new JSONObject(rows.getString(0));if("SUMMARY".equals(receipt.getJSONObject("context").optString("purpose"))){actualSummaries++;assertEquals("COMPLETED",receipt.getString("outcome"));}}}assertEquals(f.compactSummaries.get(),actualSummaries);
        }
    }
    @Test public void formalForgetCannotReviveRawOrSummaryFromTheRetainedResearchCheckpoint()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(true,false,false,false,true)){
            expandNativeBudget(f);pauseAfterRealPublishedSummary(f);int requests=f.http.get(),bodies=f.bodyReads.get();f.memory.forgetReviewed(TeamMemberEvidenceHost.ownerScope(),f.record.getString("id"),f.record.getLong("revision"));closeCoordinator(f.coordinator);f.coordinator=null;f.openCoordinator(false);f.resumeNative();f.waitIdle();assertEquals(requests,f.http.get());assertEquals(bodies,f.bodyReads.get());assertNotEquals("completed",f.child().getString("status"));assertFalse(f.tasks.hasUnresolved(f.child().getString("id")));
        }
    }
}
