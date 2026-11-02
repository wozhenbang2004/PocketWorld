package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original filesystem Book + real HTTP Git protocol. No script process is launched. */
public final class SkillSourceUpdateTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject foreign()throws Exception{return new JSONObject().put("ownerId","other").put("spaceId","private");}
    private final class Fixture implements AutoCloseable {
        final File root=temp.newFolder();final SkillBook book=new SkillBook(root);final SkillGitProtocolTest.Server server=new SkillGitProtocolTest.Server(SkillGitProtocolTest.files("1"));
        Fixture()throws Exception{}
        SkillBook.SourceReview check(String id)throws Exception{return book.inspectGitSource(scope(),id,SkillGitProtocolTest.source(),server.client(),new RunControl(),1000);}
        JSONObject apply(SkillBook.SourceReview review,boolean discard)throws Exception{return book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,discard,2000);}
        JSONObject first()throws Exception{return apply(check(null),false);}
        JSONObject current(JSONObject row)throws Exception{return book.get(scope(),row.getString("id"),2000);}
        File file(JSONObject row,String path)throws Exception{return new File(root,row.getString("id")+"/"+row.getInt("revision")+"/source-check/"+path);}
        void localEdit(JSONObject row,String body)throws Exception{Files.write(file(row,"reference.txt").toPath(),body.getBytes(StandardCharsets.UTF_8));}
        JSONObject zipUpdate(JSONObject row,JSONObject files)throws Exception{
            byte[] zip=SkillArchive.write("source-check",files);JSONObject inspection=book.inspectPackage(scope(),new ByteArrayInputStream(zip),row.getString("id"),2000);
            JSONObject options=new JSONObject().put("source","owner-local-zip").put("id",row.getString("id")).put("revision",row.getInt("revision")).put("expectedCurrentDigest",row.getString("digest")).put("inspectedDigest",inspection.getJSONObject("package").getString("digest"));
            return book.importPackage(scope(),new ByteArrayInputStream(zip),options,2100);
        }
        @Override public void close(){server.close();}
    }
    @Test public void firstGitImportHasNativeCommitBaselineAndIsAlwaysDisabledAndOneShot()throws Exception {
        try(Fixture f=new Fixture()) {
            SkillBook.SourceReview review=f.check(null);JSONObject view=review.view();assertEquals("untracked",view.getString("classification"));assertFalse(view.getBoolean("executionAuthorized"));assertEquals(0,f.book.list(scope(),1000).length());
            JSONObject row=f.apply(review,false);assertEquals(1,row.getInt("revision"));assertEquals(SkillGitProtocolTest.COMMIT,row.getString("sourceVersion"));assertEquals(1,row.getJSONObject("gitSource").getInt("baseRevision"));assertEquals(row.getString("digest"),row.getJSONObject("gitSource").getString("baseDigest"));assertFalse(row.getBoolean("enabled"));assertEquals("",row.getString("approvedDigest"));
            assertThrows(SecurityException.class,()->f.book.loadForHost(scope(),row.getString("id"),row.getString("digest"),Set.of(),2200));
            assertEquals("stale_target",assertThrows(SkillBook.SourceUnavailable.class,()->f.apply(review,false)).code);assertEquals(1,f.book.list(scope(),2200).length());
            assertTrue(new File(f.root,row.getString("id")+"/1/retained.json").isFile());
        }
    }
    @Test public void unchangedThenUpstreamChangedIncludesRealDeletionAndInvalidatesOldExecution()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();assertEquals("current",f.check(first.getString("id")).view().getString("classification"));
            f.book.setEnabled(scope(),first.getString("id"),true,first.getString("digest"),true,2100);SkillBook.ExecutionSnapshot active=f.book.executionSnapshot(scope(),first.getString("id"),1,first.getString("digest"),Set.of(),2200);assertEquals(1,active.binding.getInt("revision"));
            JSONObject upstream=SkillGitProtocolTest.files("2");upstream.remove("reference.txt");f.server.setFiles(upstream);SkillBook.SourceReview review=f.check(first.getString("id"));
            assertEquals("upstream_changed",review.view().getString("classification"));assertEquals("reference.txt",review.view().getJSONObject("comparison").getJSONArray("removed").getString(0));
            JSONObject updated=f.apply(review,false);assertEquals(2,updated.getInt("revision"));assertEquals(f.server.commit,updated.getString("sourceVersion"));assertFalse(updated.getBoolean("enabled"));assertEquals("",updated.getString("approvedDigest"));
            assertThrows(SecurityException.class,()->f.book.executionSnapshot(scope(),first.getString("id"),1,first.getString("digest"),Set.of(),2300));assertThrows(IllegalArgumentException.class,()->f.book.read(scope(),updated.getString("id"),"reference.txt",0,20,2300));
        }
    }
    @Test public void nativeLocalZipChangeAndBothChangedKeepBaseAndRequireExplicitDiscard()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first(),local=f.zipUpdate(first,SkillGitProtocolTest.files("local"));assertEquals(1,local.getJSONObject("gitSource").getInt("baseRevision"));
            SkillBook.SourceReview onlyLocal=f.check(local.getString("id"));assertEquals("local_changed",onlyLocal.view().getString("classification"));
            assertEquals("discard_confirmation_required",assertThrows(SkillBook.SourceUnavailable.class,()->f.apply(onlyLocal,false)).code);assertEquals(2,f.current(local).getInt("revision"));
            f.server.setFiles(SkillGitProtocolTest.files("remote"));SkillBook.SourceReview both=f.check(local.getString("id"));assertEquals("both_changed",both.view().getString("classification"));
            JSONObject applied=f.apply(both,true);assertEquals(3,applied.getInt("revision"));assertEquals(3,applied.getJSONObject("gitSource").getInt("baseRevision"));assertFalse(applied.getBoolean("enabled"));
        }
    }
    @Test public void externalFileEditIsDetectedWithoutBlessingItAsAnApprovedPackage()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();f.localEdit(first,"Owner local change🙂");SkillBook.SourceReview review=f.check(first.getString("id"));assertEquals("local_changed",review.view().getString("classification"));assertFalse(review.view().getJSONObject("comparison").getBoolean("currentContentVerified"));
            assertEquals("invalid",f.current(first).getString("status"));assertThrows(SkillBook.SourceUnavailable.class,()->f.apply(review,false));
            JSONObject fork=f.book.applySourceReview(scope(),review,SkillBook.SourceChoice.FORK,false,2200);assertNotEquals(first.getString("id"),fork.getString("id"));assertFalse(fork.getBoolean("enabled"));assertEquals("Owner local change🙂",new String(Files.readAllBytes(f.file(first,"reference.txt").toPath()),StandardCharsets.UTF_8));
        }
    }
    @Test public void addedLocalFileAndReadableDeletionAreNotSilentlyIgnored()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();Files.write(f.file(first,"owner-notes.txt").toPath(),"Do not overwrite".getBytes(StandardCharsets.UTF_8));
            SkillBook.SourceReview extra=f.check(first.getString("id"));assertEquals("local_changed",extra.view().getString("classification"));assertEquals("owner-notes.txt",extra.view().getJSONObject("comparison").getJSONArray("removed").getString(0));
            Files.delete(f.file(first,"owner-notes.txt").toPath());Files.delete(f.file(first,"reference.txt").toPath());SkillBook.SourceReview missing=f.check(first.getString("id"));assertEquals("local_changed",missing.view().getString("classification"));assertThrows(SkillBook.SourceUnavailable.class,()->f.apply(missing,false));
        }
    }
    @Test public void unknownLocalBytesAndMissingBaseNeverBecomeFreelyOverwritable()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();Files.write(f.file(first,"reference.txt").toPath(),new byte[]{(byte)0x80});int before=f.server.requests.get();assertEquals("local_unknown",assertThrows(SkillBook.SourceUnavailable.class,()->f.check(first.getString("id"))).code);assertEquals(before,f.server.requests.get());assertEquals(1,f.current(first).getInt("revision"));
        }
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();Files.delete(new File(f.root,first.getString("id")+"/1/retained.json").toPath());assertEquals("local_unknown",assertThrows(SkillBook.SourceUnavailable.class,()->f.check(first.getString("id"))).code);assertEquals(1,f.current(first).getInt("revision"));
        }
    }
    @Test public void fetchIsOutsideLockAndConcurrentTargetPolicyChangeRejectsTheCandidate()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();f.server.beforeTree=()->{try{f.book.setEnabled(scope(),first.getString("id"),false,"",false,2100);}catch(Exception failure){throw new RuntimeException(failure);}};
            assertEquals("stale_target",assertThrows(SkillBook.SourceUnavailable.class,()->f.check(first.getString("id"))).code);assertEquals(1,f.current(first).getInt("revision"));
        }
    }
    @Test public void postPreviewFileChangeEnableDisableAndDeletionAllRejectOldApply()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();f.server.setFiles(SkillGitProtocolTest.files("remote"));SkillBook.SourceReview preview=f.check(first.getString("id"));f.localEdit(first,"late edit");assertEquals("stale_target",assertThrows(SkillBook.SourceUnavailable.class,()->f.apply(preview,true)).code);assertEquals("late edit",new String(Files.readAllBytes(f.file(first,"reference.txt").toPath()),StandardCharsets.UTF_8));
        }
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first();SkillBook.SourceReview preview=f.check(first.getString("id"));f.book.setEnabled(scope(),first.getString("id"),true,first.getString("digest"),true,2100);f.book.setEnabled(scope(),first.getString("id"),false,"",false,2200);assertEquals("stale_target",assertThrows(SkillBook.SourceUnavailable.class,()->f.apply(preview,true)).code);
            f.book.delete(scope(),first.getString("id"));assertThrows(IllegalArgumentException.class,()->f.apply(preview,true));assertEquals(0,f.book.list(scope(),2300).length());
        }
    }
    @Test public void exactHistoricalContentAndSourceVersionBecomeNewDisabledRevisionWithCurrentPolicy()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.book.install(scope(),new JSONObject().put("manifest",SkillGitProtocolTest.manifest("manual")).put("source","owner original").put("sourceVersion","known-v1").put("requiredCapabilities",new JSONArray().put("phone_observe")).put("expiresAt",99999),1000);
            SkillBook.SourceReview link=f.check(first.getString("id"));JSONObject git=f.apply(link,true);assertEquals("phone_observe",git.getJSONArray("requiredCapabilities").getString(0));assertEquals(99999,git.getLong("expiresAt"));f.book.setEnabled(scope(),git.getString("id"),true,git.getString("digest"),true,2200);
            SkillBook.SourceReview restore=f.book.inspectRetainedRevision(scope(),git.getString("id"),1,2300);assertEquals("known-v1",restore.view().getString("historicalSourceVersion"));JSONObject restored=f.book.applySourceReview(scope(),restore,SkillBook.SourceChoice.REPLACE,true,2400);
            assertEquals(3,restored.getInt("revision"));assertEquals(first.getString("digest"),restored.getString("digest"));assertEquals("known-v1",restored.getString("sourceVersion"));assertEquals("owner original",restored.getString("source"));assertFalse(restored.has("gitSource"));assertEquals(1,restored.getJSONObject("restoredFrom").getInt("revision"));assertFalse(restored.getBoolean("enabled"));assertEquals("",restored.getString("approvedDigest"));assertEquals("phone_observe",restored.getJSONArray("requiredCapabilities").getString(0));assertEquals(99999,restored.getLong("expiresAt"));
        }
    }
    @Test public void retainedHistoryIsPagedAndUnknownOldMetadataIsNotInventedOnReopen()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.first(),second=f.zipUpdate(first,SkillGitProtocolTest.files("local"));Files.delete(new File(f.root,first.getString("id")+"/1/retained.json").toPath());SkillBook reopened=new SkillBook(f.root);
            JSONObject page=reopened.retainedRevisions(scope(),first.getString("id"),0,1,2300);assertEquals(2,page.getJSONArray("revisions").getJSONObject(0).getInt("revision"));assertEquals(2,page.getInt("beforeRevision"));assertTrue(page.getJSONArray("revisions").getJSONObject(0).getBoolean("available"));
            JSONObject old=reopened.retainedRevisions(scope(),first.getString("id"),2,1,2400).getJSONArray("revisions").getJSONObject(0);assertFalse(old.getBoolean("available"));assertTrue(old.isNull("sourceVersion"));assertTrue(old.isNull("metadataObservedAt"));assertThrows(SkillBook.SourceUnavailable.class,()->reopened.inspectRetainedRevision(scope(),first.getString("id"),1,2400));assertEquals(2,reopened.get(scope(),second.getString("id"),2400).getInt("revision"));
        }
    }
    @Test public void reviewIsBoundToNativeBookScopeLifetimeAndNeverAcceptsJsonAuthority()throws Exception {
        try(Fixture f=new Fixture()) {
            SkillBook.SourceReview review=f.check(null);assertThrows(SkillBook.SourceUnavailable.class,()->f.book.applySourceReview(foreign(),review,SkillBook.SourceChoice.REPLACE,false,2000));assertThrows(SkillBook.SourceUnavailable.class,()->new SkillBook(f.root).applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,false,2000));assertThrows(SkillBook.SourceUnavailable.class,()->f.book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,false,601001));
            assertThrows(SecurityException.class,()->f.book.install(scope(),new JSONObject().put("gitSource",new JSONObject()),2000));
            assertFalse(review.view().toString().contains("准确文本"));String original=review.view().getJSONObject("source").getString("commit");review.view().getJSONObject("source").put("commit","forged");assertEquals(original,review.view().getJSONObject("source").getString("commit"));
        }
    }
    @Test public void experienceSourceCannotBeStrippedByGitOrRetainedRestore()throws Exception {
        try(Fixture f=new Fixture()) {
            ExperienceOrigin.Ref ref=new ExperienceOrigin.Ref("procedure-1",1,"owner","private");SkillBook derived=new SkillBook(f.root,(owner,space,id)->new ExperienceOrigin.State(ref,ExperienceOrigin.Status.ACTIVE,true));ExperienceOrigin origin=new ExperienceOrigin(ref,ExperienceOrigin.DraftAuthor.MODEL);
            JSONObject draft=new JSONObject().put("manifest",SkillGitProtocolTest.manifest("derived"));JSONObject inspected=derived.inspectExperience(scope(),origin,draft,new JSONObject(),1000);JSONObject row=derived.importExperience(scope(),origin,draft,new JSONObject().put("inspectedDigest",inspected.getString("inspectedDigest")),1100);
            assertEquals("origin_bound",assertThrows(SkillBook.SourceUnavailable.class,()->derived.inspectGitSource(scope(),row.getString("id"),SkillGitProtocolTest.source(),f.server.client(),new RunControl(),1200)).code);assertEquals(0,f.server.requests.get());assertThrows(SkillBook.SourceUnavailable.class,()->derived.retainedRevisions(scope(),row.getString("id"),0,10,1200));assertFalse(new File(f.root,row.getString("id")+"/1/retained.json").exists());
        }
    }
    @Test public void candidateReadIsBoundedAndLowTrustWithoutGrowingUiHistory()throws Exception {
        try(Fixture f=new Fixture()) {
            SkillBook.SourceReview review=f.check(null);JSONObject page=f.book.readSourceReview(scope(),review,"reference.txt",0,4,1100);assertEquals(4,page.getInt("nextOffset"));assertTrue(page.getBoolean("hasMore"));assertTrue(page.getBoolean("untrustedReference"));assertFalse(page.getBoolean("executionAuthorized"));assertEquals(0,f.book.list(scope(),1100).length());assertThrows(IllegalArgumentException.class,()->f.book.readSourceReview(scope(),review,"../outside",0,4,1100));assertThrows(IllegalArgumentException.class,()->f.book.readSourceReview(scope(),review,"reference.txt",0,12001,1100));
        }
    }
}
