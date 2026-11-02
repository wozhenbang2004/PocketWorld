package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Run ONLY with the fixed pre-budget-successor M/N production classpath.
 * The expected original failure seeds actual files for the separate current-code cold test;
 * it does not retroactively turn the original N delivery failure into GREEN. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=TeamBudgetPersistentApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamNativeBudgetUpgradeSeedTest {
    @Test public void original97FailureLeavesActualDrainedSQLiteCatalogAndCheckoutsForUpgrade()throws Exception {
        assertTrue("explicit seed-only launcher flag",Boolean.getBoolean("a25.preserveNativeBudgetSeed"));
        Path root=TeamBudgetPersistentApplication.ownedRoot();assertFalse(Files.exists(root.resolve("native-seed-locators.json")));
        AssertionError original=assertThrows(AssertionError.class,()->new TeamProjectSecondRoundJourneyTest()
                .actualV1ThenProjectV2ThenColdOriginalParentApprovalNativeContinuationUsesOnlyOriginalRemaining());
        assertEquals("cleanup failures cannot count as a valid seed",0,original.getSuppressed().length);
        assertTrue("preserve the original business assertion, not any arbitrary failure",original.getMessage().contains("only native exact owner decisions unblock integration"));
        JSONObject nativeFiles=new JSONObject(Files.readString(root.resolve("native-seed-locators.json")));
        assertTrue(nativeFiles.getBoolean("ready"));assertTrue(nativeFiles.getBoolean("taskDataFromOriginalSQLite"));
        assertFalse(nativeFiles.getBoolean("diagnosticJsonRestoresAuthority"));assertEquals(97,nativeFiles.getInt("requestsReceived"));assertEquals(97,nativeFiles.getInt("responsesWritten"));
        assertTrue(Files.isRegularFile(root.resolve("application/databases/pocket-world.db")));
        assertTrue(Files.isRegularFile(Path.of(nativeFiles.getString("runtimeAndCoordinatorDirectory")).resolve("runs.json")));
        System.out.println("C6_NATIVE_BUDGET_UPGRADE_SEED_READY originalOutcome=failed actualCalls=97 priorCallsAndEffectsRetained=true");
    }
}
