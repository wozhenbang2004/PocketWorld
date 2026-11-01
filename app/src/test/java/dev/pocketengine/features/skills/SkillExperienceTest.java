package dev.pocketengine.features.skills;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SkillExperienceTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws org.json.JSONException {return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private ExperienceOrigin.Ref ref(long revision){return new ExperienceOrigin.Ref("procedure-1",revision,"owner","private");}
    private ExperienceOrigin origin(long revision){return new ExperienceOrigin(ref(revision),ExperienceOrigin.DraftAuthor.MODEL);}
    private JSONObject draft()throws org.json.JSONException {return new JSONObject().put("manifest","---\nname: calculator-check\ndescription: calculator task check\nallowed-tools: Bash(*) phone_observe\nmetadata:\n  version: draft-1\n---\nUNVERIFIED_BODY: observe and check the actual result.")
            .put("resources",new JSONObject().put("references/check.md","PRIVATE_REFERENCE").put("scripts/check.sh","never execute this script"));}
    private static final class Resolver implements ExperienceOrigin.ExperienceResolver {
        ExperienceOrigin.State current;boolean fail;Runnable onResolve;
        Resolver(ExperienceOrigin.Ref ref){current=new ExperienceOrigin.State(ref,ExperienceOrigin.Status.ACTIVE,true);}
        public ExperienceOrigin.State resolve(String owner,String space,String id)throws Exception {
            if(onResolve!=null)onResolve.run();if(fail)throw new IOException("private host detail");return current;
        }
    }
    private JSONObject options(SkillBook book,ExperienceOrigin origin,JSONObject draft,JSONObject policy)throws Exception {
        JSONObject inspected=book.inspectExperience(scope(),origin,draft,policy,1000);JSONObject options=new JSONObject(policy.toString()).put("inspectedDigest",inspected.getString("inspectedDigest"));
        JSONObject comparison=inspected.getJSONObject("comparison");if(comparison.has("id"))options.put("revision",comparison.getInt("revision")).put("expectedCurrentDigest",comparison.getString("expectedCurrentDigest"));return options;
    }
    private JSONObject install(SkillBook book)throws Exception{return book.importExperience(scope(),origin(1),draft(),options(book,origin(1),draft(),new JSONObject()),1100);}
    private JSONObject approve(SkillBook book,JSONObject row)throws Exception{return book.approveExperience(scope(),row.getString("id"),row.getInt("revision"),row.getString("digest"),1200);}
    private JSONObject read(SkillBook book,JSONObject row)throws Exception{return book.readForHost(scope(),row.getString("id"),"SKILL.md",0,12000,row.getInt("revision"),row.getString("digest"),Set.of("phone_observe"),1300);}
    private JSONObject discover(SkillBook book)throws Exception{return book.discoverForHost(scope(),"calculator",Set.of("phone_observe"),5,8000,1300);}
    private JSONObject disk(File root)throws Exception{return new JSONObject(new String(Files.readAllBytes(new File(root,"index.json").toPath()),StandardCharsets.UTF_8));}

    @Test public void acceptedExperienceInspectImportApproveReadUsesExistingRegistryAndNeverVerifiesExecution()throws Exception {
        File root=new File(temp.getRoot(),"skills");Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);
        JSONObject inspection=book.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000);
        assertFalse(root.exists());assertFalse(inspection.toString().contains("UNVERIFIED_BODY"));assertEquals("current",inspection.getString("originStatus"));
        assertEquals("unverified",inspection.getJSONObject("origin").getString("validationStatus"));assertTrue(inspection.getJSONObject("origin").getBoolean("generated"));
        JSONObject row=install(book);assertFalse(row.getBoolean("active"));assertThrows(SecurityException.class,()->read(book,row));
        assertThrows(SecurityException.class,()->book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1200));
        assertThrows(SecurityException.class,()->book.approveExperience(scope(),row.getString("id"),2,row.getString("digest"),1200));
        assertThrows(SecurityException.class,()->book.exportPackage(scope(),row.getString("id"),1,row.getString("digest"),new ByteArrayOutputStream(),1200));
        approve(book,row);JSONObject content=read(book,row);assertTrue(content.getString("content").contains("UNVERIFIED_BODY"));assertFalse(content.getBoolean("executable"));
        assertFalse(content.getJSONObject("origin").getBoolean("executionVerified"));assertEquals(1,discover(book).getJSONArray("skills").length());
        assertEquals(1,disk(root).getJSONArray("skills").length());assertEquals(0,disk(root).getJSONArray("experienceCleanup").length());
        assertTrue(read(new SkillBook(root,resolver),row).getBoolean("active"));
    }
    @Test public void sourceChangeBlocksEveryBodyPathAndDiscoveryBeforeReconciliation()throws Exception {
        File root=new File(temp.getRoot(),"skills");Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        resolver.current=new ExperienceOrigin.State(ref(2),ExperienceOrigin.Status.ACTIVE,true);
        assertThrows(SecurityException.class,()->read(book,row));assertThrows(SecurityException.class,()->book.loadForHost(scope(),row.getString("id"),row.getString("digest"),Set.of(),1300));
        assertThrows(SecurityException.class,()->book.read(scope(),row.getString("id"),"SKILL.md",0,100,1300));
        assertThrows(SecurityException.class,()->approve(book,row));assertThrows(SecurityException.class,()->book.exportPackage(scope(),row.getString("id"),1,row.getString("digest"),new ByteArrayOutputStream(),1300));
        assertEquals(0,discover(book).getJSONArray("skills").length());assertEquals(0,book.catalogueForHost(scope(),1300).getJSONArray("skills").length());
        assertEquals("sourceChanged",book.get(scope(),row.getString("id"),1300).getString("originStatus"));
        assertEquals(1,book.reconcileExperiences(scope(),1400).getInt("revoked"));assertFalse(disk(root).getJSONArray("skills").getJSONObject(0).getBoolean("enabled"));
    }
    @Test public void deletionAndForgetRemoveAllVersionsAndTheirMetadataButNotManualSkills()throws Exception {
        for(ExperienceOrigin.Status status:new ExperienceOrigin.Status[]{ExperienceOrigin.Status.DELETED,ExperienceOrigin.Status.FORGOTTEN}){
            File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject first=install(book);approve(book,first);
            JSONObject changed=draft().put("manifest",draft().getString("manifest")+"\nnew draft");JSONObject policy=new JSONObject().put("id",first.getString("id"));
            JSONObject second=book.importExperience(scope(),origin(1),changed,options(book,origin(1),changed,policy),1500);
            JSONObject manual=book.install(scope(),draft().put("source","ordinary owner text"),1500);
            resolver.current=new ExperienceOrigin.State(ref(1),status,true);
            assertThrows(SecurityException.class,()->read(book,second));assertEquals(0,discover(book).getJSONArray("skills").length());
            JSONObject result=book.reconcileExperiences(scope(),1600);assertEquals("complete",result.getString("status"));assertEquals(1,result.getInt("removed"));
            assertFalse(new File(root,first.getString("id")).exists());assertTrue(new File(root,manual.getString("id")).exists());
            assertEquals(1,book.list(scope(),1700).length());assertEquals(0,disk(root).getJSONArray("experienceCleanup").length());
            assertFalse(disk(root).toString().contains("procedure-1"));assertEquals(0,book.reconcileExperiences(scope(),1800).getInt("removed"));
        }
    }
    @Test public void committedNativeForgetCallbackCleansMissingSourceWithoutInventingATombstoneRegistry()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        resolver.current=null;assertEquals("blocked",book.reconcileExperiences(scope(),1300).getString("status"));
        assertThrows(SecurityException.class,()->book.removeExperienceSource(scope(),new ExperienceOrigin.Ref("procedure-1",1,"other","private")));
        SkillBook noResolver=new SkillBook(root);assertEquals("complete",noResolver.removeExperienceSource(scope(),ref(1)).getString("status"));
        assertEquals(0,noResolver.list(scope(),1400).length());assertFalse(new File(root,row.getString("id")).exists());
        assertEquals(0,noResolver.removeExperienceSource(scope(),ref(1)).getInt("removed"));assertEquals(0,disk(root).getJSONArray("experienceCleanup").length());
    }
    @Test public void staleNativeRemovalCallbackCannotDeleteANewerSourceRevision()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject first=install(book);
        resolver.current=new ExperienceOrigin.State(ref(2),ExperienceOrigin.Status.ACTIVE,true);
        JSONObject target=new JSONObject().put("id",first.getString("id"));JSONObject second=book.importExperience(scope(),origin(2),draft(),options(book,origin(2),draft(),target),1200);approve(book,second);
        JSONObject stale=book.removeExperienceSource(scope(),ref(1));assertEquals("blocked",stale.getString("status"));assertEquals(1,stale.getInt("newerSources"));assertEquals(0,stale.getInt("removed"));
        assertTrue(read(book,second).getBoolean("active"));resolver.current=null;
        assertEquals("complete",book.removeExperienceSource(scope(),ref(2)).getString("status"));assertFalse(new File(root,second.getString("id")).exists());
    }
    @Test public void missingResolverFailsClosedOnlyForSourceBackedPackagesAndLeavesHistoricalManualZipWorking()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook writer=new SkillBook(root,resolver);JSONObject row=install(writer);approve(writer,row);SkillBook absent=new SkillBook(root);
        assertThrows(SecurityException.class,()->absent.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000));assertThrows(SecurityException.class,()->read(absent,row));
        assertThrows(SecurityException.class,()->approve(absent,row));assertEquals(0,discover(absent).getJSONArray("skills").length());
        assertEquals("sourceResolverUnavailable",absent.get(scope(),row.getString("id"),1300).getString("originStatus"));assertEquals("blocked",absent.reconcileExperiences(scope(),1400).getString("status"));
        JSONObject manual=absent.install(scope(),draft().put("source","accepted-procedure:free-text-is-not-proof"),1000);assertFalse(manual.has("origin"));
        absent.setEnabled(scope(),manual.getString("id"),true,manual.getString("digest"),true,1100);ByteArrayOutputStream zip=new ByteArrayOutputStream();
        absent.exportPackage(scope(),manual.getString("id"),1,manual.getString("digest"),zip,1200);
        JSONObject inspected=absent.inspectPackage(scope(),new ByteArrayInputStream(zip.toByteArray()),null,1200);
        JSONObject imported=absent.importPackage(scope(),new ByteArrayInputStream(zip.toByteArray()),new JSONObject().put("source","manual ZIP").put("inspectedDigest",inspected.getJSONObject("package").getString("digest")),1300);
        assertFalse(imported.has("origin"));assertFalse(imported.getBoolean("enabled"));assertTrue(read(absent,manual).getBoolean("active"));
    }
    @Test public void scopeRevisionAcceptedProcedureAndResolverIdentityMustMatch()throws Exception {
        Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(temp.newFolder(),resolver);
        ExperienceOrigin wrongScope=new ExperienceOrigin(new ExperienceOrigin.Ref("procedure-1",1,"owner","group"),ExperienceOrigin.DraftAuthor.MODEL);
        assertThrows(SecurityException.class,()->book.inspectExperience(scope(),wrongScope,draft(),new JSONObject(),1000));
        assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(2),draft(),new JSONObject(),1000));
        resolver.current=new ExperienceOrigin.State(new ExperienceOrigin.Ref("different",1,"owner","private"),ExperienceOrigin.Status.ACTIVE,true);
        assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000));
        resolver.current=new ExperienceOrigin.State(ref(1),ExperienceOrigin.Status.ACTIVE,false);
        assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000));
        resolver.current=new ExperienceOrigin.State(ref(1),ExperienceOrigin.Status.INACTIVE,true);
        assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000));
        resolver.current=null;assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000));
        resolver.fail=true;assertFalse(assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(1),draft(),new JSONObject(),1000)).getMessage().contains("private host detail"));
    }
    @Test public void inspectionPinsContentOriginAuthorPolicyAndCurrentTarget()throws Exception {
        Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(temp.newFolder(),resolver);JSONObject input=options(book,origin(1),draft(),new JSONObject());
        JSONObject changed=draft().put("manifest",draft().getString("manifest")+"changed");assertThrows(SecurityException.class,()->book.importExperience(scope(),origin(1),changed,input,1100));
        ExperienceOrigin user=new ExperienceOrigin(ref(1),ExperienceOrigin.DraftAuthor.USER);assertThrows(SecurityException.class,()->book.importExperience(scope(),user,draft(),input,1100));
        JSONObject changedPolicy=new JSONObject(input.toString()).put("requiredCapabilities",new JSONArray().put("phone_observe"));assertThrows(SecurityException.class,()->book.importExperience(scope(),origin(1),draft(),changedPolicy,1100));
        resolver.current=new ExperienceOrigin.State(ref(2),ExperienceOrigin.Status.ACTIVE,true);assertThrows(SecurityException.class,()->book.importExperience(scope(),origin(1),draft(),input,1100));
        assertThrows(SecurityException.class,()->book.importExperience(scope(),origin(2),draft(),input,1100));
        resolver.current=new ExperienceOrigin.State(ref(1),ExperienceOrigin.Status.ACTIVE,true);JSONObject first=install(book);
        JSONObject update=options(book,origin(1),draft(),new JSONObject().put("id",first.getString("id")));book.importExperience(scope(),origin(1),draft(),update,1200);
        assertThrows(SecurityException.class,()->book.importExperience(scope(),origin(1),draft(),update,1300));
    }
    @Test public void modelDraftCannotSmuggleIdentityPolicyApprovalOrVerifiedStatus()throws Exception {
        Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(temp.newFolder(),resolver);
        for(String field:new String[]{"origin","source","sourceRefs","confirmed","requiredCapabilities","validationStatus","ownerId"}){
            JSONObject bad=draft().put(field,"forged");assertThrows(SecurityException.class,()->book.inspectExperience(scope(),origin(1),bad,new JSONObject(),1000));
        }
        assertThrows(IllegalArgumentException.class,()->book.inspectExperience(scope(),origin(1),draft(),new JSONObject().put("confirmed",true),1000));
        JSONObject forged=draft().put("source","manual").put("origin",new JSONObject());assertThrows(SecurityException.class,()->book.install(scope(),forged,1000));
        JSONObject badUnicode=draft().put("resources",new JSONObject().put("bad.txt","\ud800"));assertThrows(IllegalArgumentException.class,()->book.inspectExperience(scope(),origin(1),badUnicode,new JSONObject(),1000));
    }
    @Test public void derivedUpdatesRetainPolicyAndRequireApprovalWithoutCreatingGrants()throws Exception {
        Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(temp.newFolder(),resolver);
        JSONObject policy=new JSONObject().put("requiredCapabilities",new JSONArray().put("phone_observe")).put("expiresAt",9000);
        JSONObject first=book.importExperience(scope(),origin(1),draft(),options(book,origin(1),draft(),policy),1100);approve(book,first);
        assertThrows(SecurityException.class,()->book.loadForHost(scope(),first.getString("id"),first.getString("digest"),Set.of(),1300));
        JSONObject loaded=book.loadForHost(scope(),first.getString("id"),first.getString("digest"),Set.of("phone_observe"),1300);assertEquals(0,loaded.getJSONArray("grantsAdded").length());assertFalse(loaded.getBoolean("scriptsExecutable"));
        resolver.current=new ExperienceOrigin.State(ref(2),ExperienceOrigin.Status.ACTIVE,true);JSONObject target=new JSONObject().put("id",first.getString("id"));
        JSONObject second=book.importExperience(scope(),origin(2),draft(),options(book,origin(2),draft(),target),1400);
        assertEquals(2,second.getInt("revision"));assertEquals(9000,second.getLong("expiresAt"));assertEquals("phone_observe",second.getJSONArray("requiredCapabilities").getString(0));assertFalse(second.getBoolean("active"));
        assertThrows(SecurityException.class,()->read(book,first));approve(book,second);assertTrue(read(book,second).getBoolean("active"));
        assertThrows(SecurityException.class,()->book.install(scope(),draft().put("source","manual").put("id",second.getString("id")).put("revision",2),1500));
    }
    @Test public void sourceChangeDuringImportLeavesNoRegisteredOrReadableDraft()throws Exception {
        File root=new File(temp.getRoot(),"skills");Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject input=options(book,origin(1),draft(),new JSONObject());
        resolver.onResolve=()->{File[] dirs=root.listFiles(File::isDirectory);if(dirs!=null&&dirs.length>0)resolver.current=new ExperienceOrigin.State(ref(2),ExperienceOrigin.Status.ACTIVE,true);};
        assertThrows(SecurityException.class,()->book.importExperience(scope(),origin(1),draft(),input,1100));assertEquals(0,book.list(scope(),1200).length());
        try(var files=Files.walk(root.toPath())){assertFalse(files.anyMatch(path->path.getFileName().toString().equals("SKILL.md")));}
    }
    @Test public void failedInitialIntentSaveCreatesNoPackageAndDoesNotReplaceCurrentMetadata()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        JSONObject input=options(book,origin(1),draft(),new JSONObject().put("id",row.getString("id")));assertTrue(new File(root,"index.pending").mkdir());
        assertThrows(IOException.class,()->book.importExperience(scope(),origin(1),draft(),input,1300));assertTrue(book.get(scope(),row.getString("id"),1400).getBoolean("active"));
        assertFalse(new File(root,row.getString("id")+"/2").exists());assertEquals(1,disk(root).getJSONArray("skills").getJSONObject(0).getInt("revision"));
    }
    @Test public void failedFinalImportCommitRetainsDurableCleanupIntentAndRecoversWithoutMetadataMixing()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        JSONObject changed=draft().put("manifest",draft().getString("manifest")+"\nNEW_PRIVATE_BODY");JSONObject input=options(book,origin(1),changed,new JSONObject().put("id",row.getString("id")));
        File blocker=new File(root,"index.pending"),pendingManifest=new File(root,row.getString("id")+"/2/calculator-check/SKILL.md");resolver.onResolve=()->{if(pendingManifest.exists())blocker.mkdir();};
        assertThrows(IOException.class,()->book.importExperience(scope(),origin(1),changed,input,1300));resolver.onResolve=null;
        JSONObject old=disk(root).getJSONArray("skills").getJSONObject(0);assertEquals(1,old.getInt("revision"));assertEquals(row.getString("digest"),old.getString("digest"));assertTrue(old.getBoolean("enabled"));
        assertEquals(1,disk(root).getJSONArray("experienceCleanup").length());assertTrue(blocker.delete());
        SkillBook reopened=new SkillBook(root,resolver);assertEquals("complete",reopened.reconcileExperiences(scope(),1400).getString("status"));assertEquals(0,disk(root).getJSONArray("experienceCleanup").length());
        assertFalse(new File(root,row.getString("id")+"/2").exists());assertFalse(read(reopened,row).getString("content").contains("NEW_PRIVATE_BODY"));
    }
    @Test public void failedForgetRegistrySaveReportsFailureAndResolverStillPreventsAnyBodyRead()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        resolver.current=new ExperienceOrigin.State(ref(1),ExperienceOrigin.Status.FORGOTTEN,true);File blocker=new File(root,"index.pending");assertTrue(blocker.mkdir());
        assertThrows(IOException.class,()->book.reconcileExperiences(scope(),1300));assertThrows(SecurityException.class,()->read(book,row));assertEquals(0,discover(book).getJSONArray("skills").length());
        assertTrue(blocker.delete());assertEquals("complete",new SkillBook(root,resolver).reconcileExperiences(scope(),1400).getString("status"));assertFalse(new File(root,row.getString("id")).exists());
    }
    @Test public void recoveryRejectsConflictingReceiptInsteadOfDeletingCurrentApprovedContent()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        JSONObject corrupt=disk(root);corrupt.getJSONArray("experienceCleanup").put(new JSONObject().put("id",row.getString("id")).put("revision",1).put("ownerId","owner").put("spaceId","private"));
        Files.write(new File(root,"index.json").toPath(),corrupt.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->new SkillBook(root,resolver).reconcileExperiences(scope(),1300));
        assertTrue(read(book,row).getString("content").contains("UNVERIFIED_BODY"));assertTrue(new File(root,row.getString("id")+"/1/calculator-check/SKILL.md").isFile());
    }
    @Test public void genericDerivedDeleteReportsPendingCleanupAsFailureAndRecoversAfterRestart()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        File locked=new File(root,row.getString("id")+"/1/calculator-check");Set<PosixFilePermission> original=Files.getPosixFilePermissions(locked.toPath());
        Files.setPosixFilePermissions(locked.toPath(),Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));
        try{
            assertThrows(IOException.class,()->book.delete(scope(),row.getString("id")));
            assertEquals(0,book.list(scope(),1300).length());assertEquals(1,disk(root).getJSONArray("experienceCleanup").length());
            assertTrue(new File(locked,"SKILL.md").isFile());assertThrows(IllegalArgumentException.class,()->read(book,row));
        }finally{Files.setPosixFilePermissions(locked.toPath(),original);}
        SkillBook reopened=new SkillBook(root,resolver);assertEquals("complete",reopened.reconcileExperiences(scope(),1400).getString("status"));
        assertFalse(new File(root,row.getString("id")).exists());assertEquals(0,disk(root).getJSONArray("experienceCleanup").length());
    }
    @Test public void failedPackageDeletionKeepsOnlyMinimalReceiptAndRetriesAfterReopen()throws Exception {
        File root=temp.newFolder();Resolver resolver=new Resolver(ref(1));SkillBook book=new SkillBook(root,resolver);JSONObject row=install(book);approve(book,row);
        File locked=new File(root,row.getString("id")+"/1/calculator-check");Set<PosixFilePermission> original=Files.getPosixFilePermissions(locked.toPath());
        Files.setPosixFilePermissions(locked.toPath(),Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));
        try{
            resolver.current=null;JSONObject result=book.removeExperienceSource(scope(),ref(1));
            assertEquals("blocked",result.getString("status"));assertEquals(1,result.getInt("pendingCleanup"));assertEquals(0,book.list(scope(),1400).length());
            assertEquals(0,disk(root).getJSONArray("skills").length());JSONObject receipt=disk(root).getJSONArray("experienceCleanup").getJSONObject(0);
            Set<String> receiptFields=new java.util.HashSet<>();for(java.util.Iterator<String> keys=receipt.keys();keys.hasNext();)receiptFields.add(keys.next());assertEquals(Set.of("id","revision","ownerId","spaceId"),receiptFields);assertFalse(disk(root).toString().contains("calculator"));assertFalse(disk(root).toString().contains("procedure-1"));
            assertThrows(IllegalArgumentException.class,()->read(book,row));
        }finally{Files.setPosixFilePermissions(locked.toPath(),original);}
        SkillBook reopened=new SkillBook(root,resolver);assertEquals("complete",reopened.reconcileExperiences(scope(),1500).getString("status"));assertFalse(new File(root,row.getString("id")).exists());assertEquals(0,disk(root).getJSONArray("experienceCleanup").length());
    }
}
