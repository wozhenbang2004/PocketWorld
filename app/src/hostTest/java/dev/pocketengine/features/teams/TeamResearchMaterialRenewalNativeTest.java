package dev.pocketengine.features.teams;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Only the new explicit same-material continuation decision, not a broad research regression. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamResearchMaterialRenewalNativeTest {
    @Test public void formalForgetBetweenNativeReviewAndApprovalCannotRenewTheOldCheckpoint()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(true)){
            f.waitIdle();JSONObject child=f.child(),old=f.tasks.teamMemberMaterialScope(f.run,"a"),pool=f.tasks.teamBudgetSnapshot(f.run);int http=f.http.get();
            try(var page=f.materials.open(f.coordinator,f.run,"a")){
                page.setForeground(true);var review=page.reviewRenewal();f.memory.forgetReviewed(TeamMemberEvidenceHost.ownerScope(),f.record.getString("id"),f.record.getLong("revision"));
                try{page.approve(review,review.view().getString("digest"));fail("forgotten formal source cannot be renewed");}catch(SecurityException|IllegalArgumentException expected){assertTrue(String.valueOf(expected.getMessage()).contains("记忆"));}
                assertEquals(old.toString(),page.current().toString());assertEquals(child.getString("id"),f.child().getString("id"));assertEquals(child.getString("runId"),f.child().getString("runId"));assertEquals(pool.toString(),f.tasks.teamBudgetSnapshot(f.run).toString());assertEquals(http,f.http.get());assertFalse(f.tasks.hasUnresolved(child.getString("id")));
            }
        }
    }
}
