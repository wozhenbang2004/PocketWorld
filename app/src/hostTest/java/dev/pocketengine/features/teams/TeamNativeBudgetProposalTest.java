package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import android.app.Application;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Native proposal only: SUMMARY is visible planning allowance, not a larger hidden grant. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamNativeBudgetProposalTest {
    private static JSONObject item(String member,String path)throws Exception {
        return new JSONObject().put("workItemId",UUID.randomUUID().toString()).put("memberId",member)
                .put("title","准确模块 "+path).put("instruction","保留其他模块，只改已审路径")
                .put("acceptance","原模块真实检查与准确冻结").put("paths",new JSONArray().put(path)).put("dependsOn",new JSONArray());
    }
    @Test public void realOwnerProposalSeparatelyShowsSummaryEstimateAndDoesNotSpendOrRefill()throws Exception {
        try(TeamProjectRunnerDeliveryJourneyTest.Fixture fixture=new TeamProjectRunnerDeliveryJourneyTest.Fixture(false);
            TaskStoreTeamWriteHost.WorkPlanPage page=fixture.host.newWorkPlanPage(fixture.coordinator,fixture.run,()->fixture.model)) {
            page.setForeground(true);String before=TeamWorkFiles.canonical(fixture.tasks.teamBudgetSnapshot(fixture.run));int http=fixture.httpCalls.get();
            var review=page.reviewProject(new JSONArray().put(item("a","a.js")).put(item("b","b.js")),fixture.options());JSONObject shown=review.view(),plan=shown.getJSONObject("budgetPlan");
            assertEquals(TeamParentBudget.estimatedProjectSummaryRequests(2,plan.getInt("integratorCommandTemplates")),plan.getInt("estimatedHistorySummaryRequests"));
            assertTrue(plan.getBoolean("historySummaryEstimateNotGuarantee"));assertFalse(plan.getBoolean("complexFlowGuaranteed"));assertFalse(plan.getBoolean("autoRefill"));assertFalse(plan.getBoolean("strictMinimum"));
            assertTrue(plan.getInt("estimatedAdditionalModelRequests")>plan.getInt("estimatedHistorySummaryRequests"));assertTrue(shown.getInt("proposedModelRequestLimit")<=TeamParentBudget.MAX_PROJECT_MODEL_REQUESTS);
            assertEquals(before,TeamWorkFiles.canonical(fixture.tasks.teamBudgetSnapshot(fixture.run)));assertEquals(http,fixture.httpCalls.get());assertFalse(fixture.coordinator.get(fixture.run).has("productionScope"));
        }
    }
}
