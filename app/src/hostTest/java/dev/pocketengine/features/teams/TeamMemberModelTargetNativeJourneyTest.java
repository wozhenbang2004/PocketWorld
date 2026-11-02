package dev.pocketengine.features.teams;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual native Settings capture + Coordinator + child Runner + SQL/HTTP, no fake model authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamMemberModelTargetNativeJourneyTest {
    private static void budget(TeamResearchAgentNativeJourneyTest.Fixture f)throws Exception {f.waitIdle();var page=f.research.openBudget(f.coordinator,f.run);page.setForeground(true);var review=page.review(8,8);page.approve(review,review.view().getString("digest"));page.close();}
    private static void closeCoordinator(TeamCoordinator coordinator)throws Exception{coordinator.close();for(String name:new String[]{"coordinator","members"}){var field=TeamCoordinator.class.getDeclaredField(name);field.setAccessible(true);assertTrue(((ExecutorService)field.get(coordinator)).awaitTermination(5,TimeUnit.SECONDS));}}
    @Test public void actualMemberGroupAndIndependentSummaryKeepOriginalTargetsAcrossColdResume()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(true,false,false,false,false,true)){
            budget(f);JSONObject before=f.child(),member=f.coordinator.get(f.run).getJSONArray("members").getJSONObject(0),pin=member.getJSONObject("memberModelSelection");assertEquals("MEMBER",pin.getJSONObject("memberRoute").getString("purpose"));assertEquals(1,pin.getJSONObject("memberRoute").getInt("maxAttempts"));assertEquals("summary-only",pin.getJSONObject("summaryRoute").getJSONArray("orderedCandidates").getJSONObject(0).getString("model"));
            f.nativeSettings.selectApiConnection(f.nativeOtherProfile.getString("id"),1);closeCoordinator(f.coordinator);f.coordinator=null;f.openCoordinator(false);f.resumeNative();f.waitIdle();
            assertEquals("completed",f.coordinator.get(f.run).getString("status"));assertEquals(before.getString("id"),f.child().getString("id"));assertEquals(before.getInt("coordinatorEpoch")+1,f.child().getInt("coordinatorEpoch"));assertTrue(f.requestModels.contains("research-member"));assertTrue(f.requestModels.contains("summary-only"));assertFalse(f.requestModels.contains("wrong-global"));assertFalse(f.requestModels.contains("later-global-must-not-run"));assertEquals(5,f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"));assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));assertFalse(f.coordinator.get(f.run).getJSONArray("members").getJSONObject(0).getJSONObject("memberModelSelection").toString().contains("synthetic-native-"));assertFalse(f.tasks.hasUnresolved(f.child().getString("id")));
        }
    }
    @Test public void memberGroupTransientFailureUsesOneOriginalPhysicalSiteAndNoHiddenFallback()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(true,false,false,false,false,true)){
            budget(f);int before=(int)f.requestModels.stream().filter("research-member"::equals).count();f.nativeMemberStatus.set(503);f.resumeNative();f.waitIdle();assertEquals(before+1,f.requestModels.stream().filter("research-member"::equals).count());assertFalse(f.requestModels.contains("later-global-must-not-run"));assertNotEquals("completed",f.child().getString("status"));assertEquals(f.http.get(),f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));assertFalse(f.tasks.hasUnresolved(f.child().getString("id")));
        }
    }
    @Test public void staleNativeGroupRevisionRejectsColdRecaptureWithoutReadingNewDefault()throws Exception {
        try(var f=new TeamResearchAgentNativeJourneyTest.Fixture(true,false,false,false,false,true)){
            budget(f);int before=f.http.get();f.nativeSettings.saveModelGroup(f.nativeMemberGroup.getString("groupId"),f.nativeMemberGroup.getLong("revision"),"Changed group",f.nativeMemberGroup);closeCoordinator(f.coordinator);f.coordinator=null;f.openCoordinator(false);f.resumeNative();f.waitIdle();assertEquals(before,f.http.get());assertFalse(f.requestModels.contains("later-global-must-not-run"));assertNotEquals("completed",f.coordinator.get(f.run).getString("status"));assertEquals(before,f.tasks.teamBudgetSnapshot(f.run).getInt("modelRequestsReservedOrUsed"));
        }
    }
}
