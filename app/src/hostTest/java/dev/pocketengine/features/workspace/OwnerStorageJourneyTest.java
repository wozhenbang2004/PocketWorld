package dev.pocketengine.features.workspace;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import java.nio.file.*;
import java.util.Set;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original Local/managed maintenance gate + native FeatureHost + actual SQL row. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class OwnerStorageJourneyTest {
    @Before public void fixtureVault(){OwnerDataArchiveFixture.installFixtureVault();}
    @After public void releaseVault(){OwnerDataArchiveFixture.removeFixtureVault();}
    private static JSONObject j(String key,Object value)throws Exception{return new JSONObject().put(key,value);}
    private static String checkout(OwnerDataArchiveFixture target)throws Exception{String project=target.workspace.handle("create",j("name","需保留源码")).getString("id");target.workspace.handle("write",j("projectId",project).put("path","main.py").put("content","print('original')\n").put("expectedVersion",0));return target.local.handle("prepare",j("projectId",project)).getString("id");}
    private static Path active(OwnerDataArchiveFixture target,String checkout){return target.android.getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files");}
    private static JSONObject await(OwnerDataArchiveFixture target,String action)throws Exception {long deadline=System.nanoTime()+10_000_000_000L;JSONObject row;do{row=target.tasks.ownerStorageCleanupForOwner(action);if(!row.getString("status").equals("running"))return row;Thread.sleep(10);}while(System.nanoTime()<deadline);return row;}
    @Test public void ownerReviewCleansOnlySelectedDependencyAndBuildTreesAndColdQueryNeverDeletesRegeneratedCache()throws Exception {
        Path root=Files.createTempDirectory("owner-storage-host-");String action,checkout;try(var target=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),root)){
            checkout=checkout(target);Path active=active(target,checkout);Files.createDirectories(active.resolve("node_modules/pkg"));Files.writeString(active.resolve("node_modules/pkg/index.js"),"own cache");Files.createDirectories(active.resolve("dist"));Files.write(active.resolve("dist/generated.bin"),new byte[]{0,1,2});Files.createDirectories(active.resolve("out"));Files.writeString(active.resolve("out/source-not-excluded"),"not a declared generated root");String source=Files.readString(active.resolve("main.py"));JSONObject inventory=target.local.ownerStorageInventoryForHost(new RunControl());assertFalse(inventory.getBoolean("automaticCleanup"));assertTrue(inventory.getBoolean("nestedProjectGroupsOverlap"));assertThrows(Exception.class,()->target.local.reviewOwnerStorageForHost(Set.of("project:"+checkout+":source"),new RunControl()));assertThrows(Exception.class,()->target.local.reviewOwnerStorageForHost(Set.of("project:"+checkout+":out"),new RunControl()));
            var reviewed=target.local.reviewOwnerStorageForHost(Set.of("project:"+checkout+":node_modules","project:"+checkout+":dist"),new RunControl());assertEquals(2,reviewed.metadata().getJSONArray("targets").length());var confirmed=reviewed.confirm();action=confirmed.actionId();target.host.submitOwnerStorageCleanupForOwner(confirmed);JSONObject row=await(target,action);assertEquals(row.toString(),"completed",row.getString("status"));assertFalse(target.tasks.hasUnresolved(action));assertFalse(Files.exists(active.resolve("node_modules")));assertFalse(Files.exists(active.resolve("dist")));assertEquals(source,Files.readString(active.resolve("main.py")));assertEquals("not a declared generated root",Files.readString(active.resolve("out/source-not-excluded")));assertThrows(Exception.class,()->target.tasks.startAttempt(action,"capabilities",5));
        }
        try(var cold=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),root)){
            Path active=active(cold,checkout);Files.createDirectories(active.resolve("node_modules"));Files.writeString(active.resolve("node_modules/new-owner-data"),"new data not selected before");JSONObject observation=cold.host.queryOwnerStorageCleanupForOwner(action,new RunControl());assertEquals("completed",observation.getString("originalTaskStatus"));assertFalse(observation.getBoolean("allTargetsCurrentlyAbsent"));assertEquals("new data not selected before",Files.readString(active.resolve("node_modules/new-owner-data")));assertEquals("completed",cold.tasks.ownerStorageCleanupForOwner(action).getString("status"));assertFalse(cold.tasks.hasUnresolved(action));
        }
    }
    @Test public void targetDriftAfterReviewKeepsBothSourceAndCachesAndUnknownIntentCannotReplay()throws Exception {
        Path root=Files.createTempDirectory("owner-storage-drift-");try(var target=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),root)){
            String checkout=checkout(target);Path active=active(target,checkout);Files.createDirectories(active.resolve("dist"));Files.writeString(active.resolve("dist/a"),"reviewed");var reviewed=target.local.reviewOwnerStorageForHost(Set.of("project:"+checkout+":dist"),new RunControl());var confirmed=reviewed.confirm();Files.writeString(active.resolve("dist/b"),"not in reviewed set");String action=confirmed.actionId();target.host.submitOwnerStorageCleanupForOwner(confirmed);JSONObject row=await(target,action);assertEquals(row.toString(),"reconciling",row.getString("status"));assertTrue(target.tasks.hasUnresolved(action));assertEquals("reviewed",Files.readString(active.resolve("dist/a")));assertEquals("not in reviewed set",Files.readString(active.resolve("dist/b")));assertEquals("print('original')\n",Files.readString(active.resolve("main.py")));var another=target.local.reviewOwnerStorageForHost(Set.of("project:"+checkout+":dist"),new RunControl()).confirm();assertThrows(Exception.class,()->target.tasks.beginOwnerStorageCleanupForOwner(another));target.host.queryOwnerStorageCleanupForOwner(action,new RunControl());assertEquals(2,active.resolve("dist").toFile().list().length);assertThrows(Exception.class,()->target.tasks.startAttempt(action,"capabilities",5));
        }
    }
}
