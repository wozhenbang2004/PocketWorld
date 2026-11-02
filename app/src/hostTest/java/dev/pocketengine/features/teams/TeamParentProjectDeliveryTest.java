package dev.pocketengine.features.teams;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Real original SQL/workspace/Runner package, observed through the parent tool facade.
 * Controlled HTTP model replies and desktop native commands are not device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamParentProjectDeliveryTest {
    private JSONObject read(TeamProjectRunnerDeliveryJourneyTest.Fixture f,String teamRun)throws Exception {
        TeamParentHost parent=new TeamParentHost(f.tasks,f.files,f.coordinator,f.host,()->f.model,
                teamId->{throw new AssertionError("read cannot select or start a team");},ignored->{},Runnable::run);
        return parent.execute(f.parent,f.parentRun,f.tasks.get(f.parent,false).getInt("coordinatorEpoch"),
                "teams_get","",new JSONObject().put("teamRunId",teamRun).put("view","integration"),f.parentControl);
    }
    @Test public void parentReadsOnlyOriginalApprovedPackageIdentityWithoutReplayingDelivery()throws Exception {
        try(var f=new TeamProjectRunnerDeliveryJourneyTest.Fixture(false)){
            f.startDiagnostics();
            assertThrows(SecurityException.class,()->read(f,f.run));
            // Native host grants only the existing read tool, as the original Runner does.
            f.tasks.authorizeTools(f.parent,f.parentRun,new org.json.JSONArray().put("teams_get"));
            JSONObject before=read(f,f.run);
            assertEquals("not_started",before.getString("status"));
            assertTrue("no package is fabricated before exact owner approval",before.isNull("projectDelivery"));
            assertThrows(Exception.class,()->read(f,"nonexistent-team"));
            JSONObject completed=TeamProjectRunnerDeliveryJourneyTest.runComplete(f,false);
            JSONObject expected=completed.getJSONObject("projectDelivery");
            String task=completed.getString("taskId");
            int operations=f.tasks.get(task,false).getJSONArray("operations").length();
            JSONObject first=read(f,f.run),second=read(f,f.run);
            assertTrue(first.getBoolean("queryOnly"));
            assertFalse(first.getBoolean("businessVerified"));
            assertEquals(TeamWorkFiles.canonical(expected),TeamWorkFiles.canonical(first.getJSONObject("projectDelivery")));
            assertEquals(TeamWorkFiles.canonical(expected),TeamWorkFiles.canonical(second.getJSONObject("projectDelivery")));
            JSONObject pin=first.getJSONObject("projectDelivery").getJSONObject("packageArtifact");
            assertEquals(pin.getString("manifestIdentity"),f.workspace.projectPackage(pin.getString("id"),pin.getString("sha256")).identity);
            assertEquals("query does not execute or regenerate package",operations,f.tasks.get(task,false).getJSONArray("operations").length());
        }
    }
}
