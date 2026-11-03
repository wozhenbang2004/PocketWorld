package dev.pocketengine.features.skills;

import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual native Module/Book/files/HTTP; no Settings model call, provider or device assertions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class SkillSourceModuleJourneyTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject importGit(SkillBook book,SkillGitProtocolTest.Server server)throws Exception {
        SkillBook.SourceReview review=book.inspectGitSource(scope(),null,SkillGitProtocolTest.source(),server.client(),new RunControl(),System.currentTimeMillis());
        return book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,false,System.currentTimeMillis());
    }
    @Test public void batchKeepsFailedAndLocallyEditedItemsAndCommitsOtherExactUpdatesDisabled()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);AtomicInteger events=new AtomicInteger();Context app=RuntimeEnvironment.getApplication();
        FeatureContext context=new FeatureContext(app,null,null,event->events.incrementAndGet());
        try(SkillGitProtocolTest.Server server=new SkillGitProtocolTest.Server(SkillGitProtocolTest.files("1"))) {
            JSONObject first=importGit(book,server),second=importGit(book,server),third=importGit(book,server);
            File modified=new File(root,second.getString("id")+"/1/source-check/reference.txt");java.nio.file.Files.write(modified.toPath(),"keep local edit".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SkillsModule module=new SkillsModule(context,book,server.client());server.setFiles(SkillGitProtocolTest.files("upstream"));
            SkillsModule.SourceBatch batch=module.inspectTrackedSources(scope(),List.of(first.getString("id"),second.getString("id"),third.getString("id")),new RunControl());
            assertEquals("upstream_changed",batch.rows().getJSONObject(0).getJSONObject("review").getString("classification"));assertEquals("both_changed",batch.rows().getJSONObject(1).getJSONObject("review").getString("classification"));
            // A later deletion is a per-item failure, not permission to recreate it or rollback others.
            book.delete(scope(),third.getString("id"));JSONObject receipt=module.applyCleanSourceUpdates(scope(),batch,new RunControl());
            assertEquals("imported_disabled",receipt.getJSONArray("items").getJSONObject(0).getString("status"));assertEquals("skipped",receipt.getJSONArray("items").getJSONObject(1).getString("status"));assertEquals("unavailable",receipt.getJSONArray("items").getJSONObject(2).getString("status"));
            JSONObject updated=book.get(scope(),first.getString("id"),System.currentTimeMillis());assertEquals(2,updated.getInt("revision"));assertFalse(updated.getBoolean("enabled"));assertEquals("",updated.getString("approvedDigest"));assertEquals("keep local edit",new String(java.nio.file.Files.readAllBytes(modified.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(1,book.get(scope(),second.getString("id"),System.currentTimeMillis()).getInt("revision"));assertEquals(1,events.get());
            assertFalse(receipt.getBoolean("executionAuthorized"));assertFalse(receipt.getBoolean("automaticRetry"));
            JSONObject replay=module.applyCleanSourceUpdates(scope(),batch,new RunControl());assertEquals("unavailable",replay.getJSONArray("items").getJSONObject(0).getString("status"));assertEquals(2,book.get(scope(),first.getString("id"),System.currentTimeMillis()).getInt("revision"));
        }
    }
    @Test public void modelCommandsCannotStartSourceNetworkOrForgeNativeMetadataAndFailureIsPerItem()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());AtomicInteger events=new AtomicInteger();FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->events.incrementAndGet());
        try(SkillGitProtocolTest.Server server=new SkillGitProtocolTest.Server(SkillGitProtocolTest.files("1"))) {
            SkillsModule module=new SkillsModule(context,book,server.client());assertThrows(IllegalArgumentException.class,()->module.handle("inspectGitSource",new JSONObject().put("source",SkillGitProtocolTest.source().toJson())));assertEquals(0,server.requests.get());
            assertThrows(SecurityException.class,()->module.handle("import",new JSONObject().put("gitSource",new JSONObject())));assertEquals(0,events.get());
            JSONObject first=importGit(book,server);server.status=404;SkillsModule.SourceBatch batch=module.inspectTrackedSources(scope(),List.of(first.getString("id")),new RunControl());assertEquals("unavailable",batch.rows().getJSONObject(0).getString("status"));assertEquals("source_unavailable",batch.rows().getJSONObject(0).getString("reason"));assertEquals(1,book.get(scope(),first.getString("id"),System.currentTimeMillis()).getInt("revision"));
        }
    }
}
