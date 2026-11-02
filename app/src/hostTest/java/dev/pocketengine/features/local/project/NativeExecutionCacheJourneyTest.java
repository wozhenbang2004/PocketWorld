package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import java.nio.file.*;
import java.util.Set;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Only original owner ledger + real self-owned Git job + existing storage review.
 * Never creates a model/native=true cleanup authority or deletes another process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class NativeExecutionCacheJourneyTest {
    static JSONObject row(JSONObject inventory,String id)throws Exception {
        JSONArray rows=inventory.getJSONArray("groups");
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);
        throw new AssertionError("Original inventory omitted exact target "+id);
    }
    static JSONObject awaitCleanup(ProjectGitImportNativeJourneyTest.Fixture f,String id)throws Exception {
        long until=System.nanoTime()+10_000_000_000L;JSONObject last=null;
        while(System.nanoTime()<until){last=f.features.ownerStorageCleanupForOwner(id);if(last.optString("status").matches("completed|reconciling|failed"))return last;Thread.sleep(30);}
        throw new AssertionError("Original cleanup did not settle: "+last);
    }
    @Test public void settledPublishedGitCacheNeedsExplicitRetirementAndKeepsOriginalProjectHistory()throws Exception {
        try(var f=new ProjectGitImportNativeJourneyTest.Fixture("plain")){
            var original=f.confirmation(false);String id=original.actionId();f.features.submitGitImportForOwner(original);
            assertEquals("completed",f.awaitFetch(id).getString("status"));
            var source=f.features.gitImportResultForOwner(id,new RunControl());
            var publication=f.local.reviewGitPublicationForOwner(source,new RunControl()).confirm();f.features.submitGitPublicationForOwner(publication);
            JSONObject imported=f.awaitPublication(publication.actionId());assertTrue(imported.getBoolean("found"));
            Path project=f.android.getFilesDir().toPath().resolve("local-development/checkouts/"+imported.getString("id")+"/files");
            byte[] binary=Files.readAllBytes(project.resolve("binary.dat"));String history=f.git(project,"log","--format=%H");
            int requests=f.requests().length();String selected="native_cache:git_intake:"+id;
            assertTrue(row(f.local.ownerStorageInventoryForHost(new RunControl()),selected).getBoolean("cleanable"));
            var review=f.local.reviewOwnerStorageForHost(Set.of(selected),new RunControl());assertTrue(review.metadata().getBoolean("nativeExecutionCacheAuthorityRevokedBeforeDeletion"));
            assertTrue(Files.isDirectory(f.intake(id)));assertTrue(f.features.gitImportForOwner(id,false).getBoolean("sourceAvailable"));
            var confirmed=review.confirm();String cleanup=confirmed.actionId();f.features.submitOwnerStorageCleanupForOwner(confirmed);
            assertEquals("completed",awaitCleanup(f,cleanup).getString("status"));assertFalse(Files.exists(f.intake(id)));
            assertThrows(Exception.class,()->f.features.submitOwnerStorageCleanupForOwner(confirmed));
            JSONObject historical=f.features.gitImportForOwner(id,false);assertEquals("completed",historical.getString("status"));assertTrue(historical.getBoolean("sourceRetired"));assertFalse(historical.getBoolean("sourceAvailable"));
            assertThrows(Exception.class,()->f.features.gitImportResultForOwner(id,new RunControl()));
            assertArrayEquals(binary,Files.readAllBytes(project.resolve("binary.dat")));assertEquals(history,f.git(project,"log","--format=%H"));
            JSONObject cold=f.features.queryOwnerStorageCleanupForOwner(cleanup,new RunControl());assertTrue(cold.getBoolean("allTargetsCurrentlyAbsent"));assertFalse(cold.getBoolean("deletionAttributionProven"));
            assertEquals(requests,f.requests().length());assertFalse(f.tasks.hasUnresolved(cleanup));assertNull(f.callbackFailure.get());
        }
    }
    @Test public void liveAndUnattributedCachesStayProtectedAndPostReviewDriftDoesNotDelete()throws Exception {
        try(var f=new ProjectGitImportNativeJourneyTest.Fixture("slow")){
            var original=f.confirmation(false);String id=original.actionId();f.features.submitGitImportForOwner(original);
            long until=System.nanoTime()+15_000_000_000L;while(!Files.exists(f.serverRoot.resolve("upload-waiting"))&&System.nanoTime()<until)Thread.sleep(30);
            assertTrue("Real original upload-pack never became active",Files.exists(f.serverRoot.resolve("upload-waiting")));
            String selected="native_cache:git_intake:"+id;assertFalse(row(f.local.ownerStorageInventoryForHost(new RunControl()),selected).getBoolean("cleanable"));
            assertThrows(Exception.class,()->f.local.reviewOwnerStorageForHost(Set.of(selected),new RunControl()));
            String unknown=java.util.UUID.randomUUID().toString();Path unknownFolder=f.intake(unknown);Files.createDirectory(unknownFolder);Files.writeString(unknownFolder.resolve("unattributed.txt"),"unknown owner; must not delete");
            assertFalse(row(f.local.ownerStorageInventoryForHost(new RunControl()),"native_cache:git_intake:"+unknown).getBoolean("cleanable"));
            assertThrows(Exception.class,()->f.local.reviewOwnerStorageForHost(Set.of("native_cache:git_intake:"+unknown),new RunControl()));
            f.features.gitImportForOwner(id,true);assertEquals("failed",f.awaitFetch(id).getString("status"));
            var review=f.local.reviewOwnerStorageForHost(Set.of(selected),new RunControl());
            Path changed=f.intake(id).resolve("owner-drift.txt");Files.writeString(changed,"changed after original native review");
            var confirmed=review.confirm();String cleanup=confirmed.actionId();f.features.submitOwnerStorageCleanupForOwner(confirmed);
            JSONObject result=awaitCleanup(f,cleanup);assertEquals("reconciling",result.getString("status"));
            assertTrue(Files.isDirectory(f.intake(id)));assertEquals("changed after original native review",Files.readString(changed));
            assertTrue(Files.exists(unknownFolder.resolve("unattributed.txt")));assertTrue(f.features.gitImportForOwner(id,false).getBoolean("sourceRetired"));
            assertThrows(Exception.class,()->f.features.submitOwnerStorageCleanupForOwner(confirmed));
            JSONObject observed=f.features.queryOwnerStorageCleanupForOwner(cleanup,new RunControl());assertFalse(observed.getBoolean("allTargetsCurrentlyAbsent"));assertEquals("unknown",observed.getString("status"));assertFalse(observed.getBoolean("automaticRetry"));
            assertTrue(f.tasks.hasUnresolved(cleanup));assertNull(f.callbackFailure.get());
        }
    }
}
