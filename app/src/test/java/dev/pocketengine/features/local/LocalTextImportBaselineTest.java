package dev.pocketengine.features.local;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class LocalTextImportBaselineTest {
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final WorkspaceModule projects;final LocalModule local;final String project,checkout;
        final File baseline;byte[] oldBaseline;java.util.Set<java.nio.file.attribute.PosixFilePermission> baselineParentPermissions;
        Fixture()throws Exception {
            FeatureContext feature=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});
            projects=new WorkspaceModule(feature);project=projects.handle("create",new JSONObject().put("name","文字基线恢复")).getString("id");
            writeProject("before",0);local=new LocalModule(feature,projects);checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
            baseline=new File(context.getFilesDir(),"local-development/checkouts/"+checkout+"/checkout.json");
        }
        JSONObject args()throws Exception{return new JSONObject().put("checkoutId",checkout).put("path","main.txt");}
        JSONObject review()throws Exception{return local.handle("reviewFile",args());}
        JSONObject head()throws Exception{return projects.handle("history",new JSONObject().put("projectId",project).put("path","main.txt")).getJSONArray("history").getJSONObject(historyCount()-1);}
        int historyCount()throws Exception{return projects.handle("history",new JSONObject().put("projectId",project).put("path","main.txt")).getJSONArray("history").length();}
        void writeProject(String text,int version)throws Exception{projects.handle("write",new JSONObject().put("projectId",project).put("path","main.txt").put("content",text).put("expectedVersion",version));}
        void edit(String text)throws Exception{JSONObject file=local.handle("readFile",args());local.handle("writeFile",args().put("content",text).put("expectedHash",file.getString("sha256")));}
        void delete()throws Exception{local.handle("deleteFile",args().put("expectedHash",review().getString("expectedHash")));}
        /** Real project writer completes first, then the real baseline writer cannot create its pending file. */
        void failBaselineAfterNextProjectCommit()throws Exception {
            org.junit.Assume.assumeTrue("POSIX private-directory IO fixture",baseline.toPath().getFileSystem().supportedFileAttributeViews().contains("posix"));
            oldBaseline=Files.readAllBytes(baseline.toPath());baselineParentPermissions=Files.getPosixFilePermissions(baseline.getParentFile().toPath());Field workspace=WorkspaceModule.class.getDeclaredField("workspace");workspace.setAccessible(true);Object original=workspace.get(projects);
            Field writer=original.getClass().getDeclaredField("recordWrite");writer.setAccessible(true);
            writer.set(original,Proxy.newProxyInstance(writer.getType().getClassLoader(),new Class<?>[]{writer.getType()},(proxy,method,args)->{
                File target=(File)args[0];JsonFiles.write(target,(JSONObject)args[1]);
                if(target.getParentFile().getName().equals(project))Files.setPosixFilePermissions(baseline.getParentFile().toPath(),java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
                return null;
            }));
        }
        /** Original project IO may fail before persistence or lose its return after persistence. */
        void failOriginalProjectReply(boolean afterCommit)throws Exception {
            Field workspace=WorkspaceModule.class.getDeclaredField("workspace");workspace.setAccessible(true);Object original=workspace.get(projects);
            Field writer=original.getClass().getDeclaredField("recordWrite");writer.setAccessible(true);java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean(true);
            writer.set(original,Proxy.newProxyInstance(writer.getType().getClassLoader(),new Class<?>[]{writer.getType()},(proxy,method,args)->{
                File target=(File)args[0];JSONObject row=(JSONObject)args[1];
                if(target.getParentFile().getName().equals(project)&&once.getAndSet(false)){if(afterCommit)JsonFiles.write(target,row);throw new java.io.IOException("original project result not returned");}
                JsonFiles.write(target,row);return null;
            }));
        }
        void restoreOldBaseline()throws Exception{Files.setPosixFilePermissions(baseline.getParentFile().toPath(),baselineParentPermissions);assertArrayEquals(oldBaseline,Files.readAllBytes(baseline.toPath()));}
        JSONObject baselineOnly(JSONObject review)throws Exception {
            return new JSONObject().put("checkoutId",review.getString("checkoutId")).put("path",review.getString("path")).put("projectId",review.getString("projectId")).put("expectedVersion",review.getInt("expectedVersion"))
                .put("expectedHash",review.getString("expectedHash")).put("expectedTargetHash",review.getString("expectedTargetHash"))
                .put("expectedTargetDeleted",review.getBoolean("targetDeleted")).put("reconcileBaselineOnly",true)
                .put("textReviewContract","pocketworld.local-text-file-review.v1");
        }
        void staleBaselineAtCurrentHead()throws Exception{edit("after");writeProject("after",1);}
        @Override public void close()throws Exception{if(baselineParentPermissions!=null)Files.setPosixFilePermissions(baseline.getParentFile().toPath(),baselineParentPermissions);local.close();projects.close();}
    }
    @Test public void committedTextAndFailedBaselineHaveSeparateReceiptThenExplicitNoWriteRecovery()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("after");JSONObject original=f.review();f.failBaselineAfterNextProjectCommit();
            JSONObject receipt=f.local.handle("importToProject",original);
            assertEquals("project_committed_baseline_unknown",receipt.getString("status"));assertTrue(receipt.getBoolean("projectVersionCommitted"));
            assertFalse(receipt.getBoolean("checkoutBaselineUpdated"));assertFalse(receipt.getBoolean("replayAllowed"));assertEquals(2,f.head().getInt("version"));
            f.restoreOldBaseline();assertThrows(Exception.class,()->f.local.handle("importToProject",original));assertEquals(2,f.historyCount());
            JSONObject fresh=f.review();assertTrue(fresh.getBoolean("same"));assertTrue(fresh.getBoolean("baselineNeedsRefresh"));assertTrue(fresh.getBoolean("importable"));
            JSONObject confirmation=f.baselineOnly(fresh),recovered=f.local.handle("importToProject",confirmation);
            assertEquals("baseline_reconciled",recovered.getString("status"));assertFalse(recovered.getBoolean("projectVersionCommitted"));assertFalse(recovered.getBoolean("contentWritten"));
            assertTrue(recovered.getBoolean("checkoutBaselineUpdated"));assertEquals(2,f.historyCount());assertEquals(2,f.head().getInt("version"));
            assertThrows(Exception.class,()->f.local.handle("importToProject",confirmation));assertEquals(2,f.historyCount());
            assertFalse(f.review().getBoolean("importable"));assertEquals(0,f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout)).getInt("changedCount"));
        }
    }
    @Test public void committedDeletionAndFailedBaselineRecoverWithoutAnotherTombstone()throws Exception {
        try(Fixture f=new Fixture()){
            f.delete();JSONObject original=f.review();f.failBaselineAfterNextProjectCommit();JSONObject receipt=f.local.handle("importToProject",original);
            assertEquals("project_committed_baseline_unknown",receipt.getString("status"));f.restoreOldBaseline();JSONObject fresh=f.review();
            assertTrue(fresh.getBoolean("deleted"));assertTrue(fresh.getBoolean("targetDeleted"));assertTrue(fresh.getBoolean("baselineNeedsRefresh"));
            f.local.handle("importToProject",f.baselineOnly(fresh));assertEquals(2,f.head().getInt("version"));assertEquals(2,f.historyCount());assertFalse(f.review().getBoolean("importable"));
        }
    }
    @Test public void addedFileWithMissingBaselineCanReconcileItsCommittedFirstVersion()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject args=new JSONObject().put("checkoutId",f.checkout).put("path","src/added.txt");
            f.local.handle("writeFile",new JSONObject(args.toString()).put("expectedHash","absent").put("content","added"));JSONObject original=f.local.handle("reviewFile",args);
            f.failBaselineAfterNextProjectCommit();JSONObject receipt=f.local.handle("importToProject",original);assertEquals("project_committed_baseline_unknown",receipt.getString("status"));
            f.restoreOldBaseline();JSONObject fresh=f.local.handle("reviewFile",args);assertTrue(fresh.getBoolean("baselineNeedsRefresh"));
            f.local.handle("importToProject",f.baselineOnly(fresh));assertFalse(f.local.handle("reviewFile",args).getBoolean("importable"));
            assertEquals(1,f.projects.handle("history",new JSONObject().put("projectId",f.project).put("path","src/added.txt")).getJSONArray("history").length());
            assertEquals(0,f.local.handle("changes",new JSONObject().put("checkoutId",f.checkout)).getInt("changedCount"));
        }
    }
    @Test public void unknownProjectReturnAfterRealCommitRequiresObservationNotARepeatedWrite()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("after");JSONObject original=f.review();byte[] baseline=Files.readAllBytes(f.baseline.toPath());f.failOriginalProjectReply(true);
            assertThrows(Exception.class,()->f.local.handle("importToProject",original));assertEquals(2,f.head().getInt("version"));assertArrayEquals(baseline,Files.readAllBytes(f.baseline.toPath()));
            assertThrows(Exception.class,()->f.local.handle("importToProject",original));assertEquals(2,f.historyCount());
            JSONObject fresh=f.review();assertTrue(fresh.getBoolean("baselineNeedsRefresh"));JSONObject reconciled=f.local.handle("importToProject",f.baselineOnly(fresh));
            assertFalse(reconciled.getBoolean("projectVersionCommitted"));assertEquals(2,f.historyCount());assertFalse(f.review().getBoolean("importable"));
        }
    }
    @Test public void unknownProjectReturnBeforeCommitCannotPretendDifferentContentHasBeenImported()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("after");JSONObject original=f.review();byte[] baseline=Files.readAllBytes(f.baseline.toPath());f.failOriginalProjectReply(false);
            assertThrows(Exception.class,()->f.local.handle("importToProject",original));assertEquals(1,f.head().getInt("version"));assertArrayEquals(baseline,Files.readAllBytes(f.baseline.toPath()));
            JSONObject fresh=f.review();assertFalse(fresh.getBoolean("same"));assertFalse(fresh.getBoolean("baselineNeedsRefresh"));
            assertThrows(Exception.class,()->f.local.handle("importToProject",f.baselineOnly(fresh)));assertEquals(1,f.historyCount());assertArrayEquals(baseline,Files.readAllBytes(f.baseline.toPath()));
        }
    }
    @Test public void matchingTextWithoutExplicitBaselineModeCannotCreateDuplicateVersions()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject unchanged=f.review();assertThrows(IllegalStateException.class,()->f.local.handle("importToProject",unchanged));
            assertEquals(1,f.historyCount());f.staleBaselineAtCurrentHead();JSONObject same=f.review();
            assertThrows(IllegalStateException.class,()->f.local.handle("importToProject",same));assertEquals(2,f.historyCount());
        }
    }
    @Test public void successfulTextImportReportsBothFacts()throws Exception {
        try(Fixture f=new Fixture()){
            f.edit("after");JSONObject receipt=f.local.handle("importToProject",f.review());assertEquals("completed",receipt.getString("status"));
            assertTrue(receipt.getBoolean("projectVersionCommitted"));assertTrue(receipt.getBoolean("checkoutBaselineUpdated"));assertFalse(receipt.getBoolean("replayAllowed"));
        }
    }
    @Test public void matchingMaximumTextNeedsNoTruncatedDifferenceToRegisterBaseline()throws Exception {
        try(Fixture f=new Fixture()){
            String text="x".repeat(65536);f.edit(text);f.writeProject(text,1);JSONObject review=f.review();
            assertTrue(review.getBoolean("same"));assertTrue(review.getBoolean("baselineNeedsRefresh"));assertTrue(review.getBoolean("importable"));assertFalse(review.getBoolean("truncated"));
            f.local.handle("importToProject",f.baselineOnly(review));assertEquals(2,f.historyCount());assertFalse(f.review().getBoolean("importable"));
        }
    }
    @Test public void changedLocalHashCannotUseAnEarlierBaselineConfirmation()throws Exception {
        try(Fixture f=new Fixture()){
            f.staleBaselineAtCurrentHead();JSONObject input=f.baselineOnly(f.review());f.edit("newer local");
            assertThrows(Exception.class,()->f.local.handle("importToProject",input));assertEquals(2,f.historyCount());
        }
    }
    @Test public void newerSameTextVersionCannotUseAnEarlierBaselineConfirmation()throws Exception {
        try(Fixture f=new Fixture()){
            f.staleBaselineAtCurrentHead();JSONObject input=f.baselineOnly(f.review());f.writeProject("after",2);
            assertThrows(Exception.class,()->f.local.handle("importToProject",input));assertEquals(3,f.historyCount());assertTrue(f.review().getBoolean("baselineNeedsRefresh"));
        }
    }
    @Test public void targetShaAndExistenceAreRequiredEvenAtTheSameSubmittedVersion()throws Exception {
        try(Fixture f=new Fixture()){
            f.staleBaselineAtCurrentHead();JSONObject review=f.review();
            for(JSONObject invalid:new JSONObject[]{f.baselineOnly(review).put("expectedTargetHash","0".repeat(64)),f.baselineOnly(review).put("expectedTargetDeleted",true),f.baselineOnly(review).put("expectedTargetDeleted","false"),f.baselineOnly(review).put("textReviewContract",""),f.baselineOnly(review).put("reconcileBaselineOnly","true")})
                assertThrows(Exception.class,()->f.local.handle("importToProject",invalid));
            assertEquals(2,f.historyCount());assertTrue(f.review().getBoolean("baselineNeedsRefresh"));
        }
    }
    @Test public void checkoutReboundToOtherProjectCannotConsumeOriginalBaselineConfirmation()throws Exception {
        try(Fixture f=new Fixture()){
            f.staleBaselineAtCurrentHead();JSONObject input=f.baselineOnly(f.review());String other=f.projects.handle("create",new JSONObject().put("name","另一个项目")).getString("id");
            f.projects.handle("write",new JSONObject().put("projectId",other).put("path","main.txt").put("content","before").put("expectedVersion",0));
            f.projects.handle("write",new JSONObject().put("projectId",other).put("path","main.txt").put("content","after").put("expectedVersion",1));
            JsonFiles.write(f.baseline,JsonFiles.read(f.baseline).put("projectId",other));assertThrows(Exception.class,()->f.local.handle("importToProject",input));assertEquals(2,f.historyCount());
        }
    }
}
