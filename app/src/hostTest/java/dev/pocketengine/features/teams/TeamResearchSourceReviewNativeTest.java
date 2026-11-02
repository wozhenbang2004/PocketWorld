package dev.pocketengine.features.teams;

import java.lang.reflect.Field;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Exactly two source-review regressions; no16-page seed or former green group is rerun. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamResearchSourceReviewNativeTest {
    @Test public void longCompletedOriginalKeepsSqlHashThroughSummaryAndActualParentRangeRead()throws Exception {
        String full="LONG_RESEARCH_ORIGINAL:"+"x".repeat(17000)+":EXACT_END";
        try(var d=new TeamResearchParentNativeJourneyTest.Domain(false,full)){
            assertEquals("needs_confirmation",d.runParent().status);assertEquals(6,d.stage);d.assertActualOwnerReply("准确回读长成员成果");assertEquals("completed",d.state().getString("status"));JSONObject member=TeamWorkPlan.find(d.state().getJSONArray("members"),"b"),state=member.getJSONArray("roundStates").getJSONObject(0),preview=state.getJSONObject("researchTask");assertTrue(preview.getString("content").length()<=12000);assertTrue(preview.getBoolean("contentTruncated"));assertEquals(full.length(),preview.getInt("contentChars"));assertEquals(TeamWorkFiles.hash(full),preview.getJSONObject("outputReference").getString("sha256"));JSONObject child=d.tasks.get(state.getString("researchTaskId"),false);assertEquals("completed",child.getString("status"));assertEquals(full.length(),child.getJSONObject("teamResearchOutputReference").getInt("chars"));assertFalse(child.getJSONObject("evidence").getBoolean("businessVerified"));
        }
    }
    @Test public void queuedNewTurnAtStepLimitCannotCompleteWithThePreviousAnswerAndCanResumeSameChild()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(false,false,false,false,false,false,false,true)){
            f.waitIdle();JSONObject before=f.child();assertTrue(f.queuedSecond.get());assertEquals(2,f.queuedAnswers.get());assertEquals("awaiting_user",before.getString("status"));assertFalse(before.getJSONObject("evidence").optBoolean("readOnlyResearchCompleted"));assertEquals("INTERIM_NOT_FINAL_FOR_SECOND_INPUT",before.getJSONObject("evidence").getString("modelSummary"));assertEquals(2,f.completedTool("team_materials"));assertFalse(f.tasks.hasUnresolved(before.getString("id")));JSONObject old=f.tasks.teamBudgetSnapshot(f.run);var page=f.research.openBudget(f.coordinator,f.run);page.setForeground(true);var review=page.review(8,4);page.approve(review,review.view().getString("digest"));page.close();assertEquals(old.getInt("modelRequestsReservedOrUsed"),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));f.resumeNative();f.waitIdle();JSONObject after=f.child();assertEquals(before.getString("id"),after.getString("id"));assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));assertEquals("completed",after.getString("status"));assertEquals("SECOND_INPUT_ACTUALLY_FINISHED",after.getJSONObject("evidence").getString("modelSummary"));assertEquals(2,f.completedTool("team_materials"));assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));assertFalse(f.tasks.hasUnresolved(after.getString("id")));
        }
    }
}
