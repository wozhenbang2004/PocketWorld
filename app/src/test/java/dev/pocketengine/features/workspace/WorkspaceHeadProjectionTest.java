package dev.pocketengine.features.workspace;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Real records, real atomic writer after a controlled IO pause. Projection reads never acquire
 * the original VWS monitor; no Book, SourceBridge or second persistent authority is faked. */
public final class WorkspaceHeadProjectionTest {
    @Test public void originalVersionsShaDeleteAndColdBootstrapAreNativeFactsNotWarmReadGuesses()throws Exception{
        Path root=Files.createTempDirectory("c2-native-heads-");VersionedWorkspace workspace=new VersionedWorkspace(root.toFile());String project=workspace.create("Native source heads").getString("id");var one=workspace.writeBytes(workspace.snapshot(project,"source.md",0),"actual source".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(workspace.currentFileHeadForHost(project,"source.md").authorityKnown);var observed=workspace.refreshFileHeadForHost(project,"source.md");assertTrue(observed.authorityKnown);assertEquals(one.version,observed.version);assertEquals(one.revisionId,observed.revisionId);assertEquals(one.sha256,observed.sha256);assertFalse(observed.deleted);
        var two=workspace.writeBytes(one,"actual new source".getBytes(java.nio.charset.StandardCharsets.UTF_8));var current=workspace.currentFileHeadForHost(project,"source.md");assertEquals(two.version,current.version);assertEquals(two.revisionId,current.revisionId);assertEquals(two.sha256,current.sha256);
        var removed=workspace.delete(two);var deletion=workspace.currentFileHeadForHost(project,"source.md");assertTrue(deletion.authorityKnown);assertTrue(deletion.deleted);assertEquals(removed.version,deletion.version);assertEquals("absent",deletion.sha256);assertEquals(removed.revisionId,deletion.revisionId);
        VersionedWorkspace cold=new VersionedWorkspace(root.toFile());assertFalse(cold.currentFileHeadForHost(project,"source.md").authorityKnown);assertEquals(deletion.revisionId,cold.refreshFileHeadForHost(project,"source.md").revisionId);var absent=cold.refreshFileHeadForHost(project,"never-existed.md");assertTrue(absent.authorityKnown);assertEquals("missing",absent.state);assertEquals(0,absent.version);assertEquals("",absent.revisionId);
        cold.closeNativeHeadsForHost();assertFalse(cold.currentFileHeadForHost(project,"source.md").authorityKnown);assertThrows(Exception.class,()->cold.refreshFileHeadForHost(project,"source.md"));
    }
    @Test public void writeInProgressAndFailedAtomicWriteRemainUnknownWithoutAnyReverseLock()throws Exception{
        Path root=Files.createTempDirectory("c2-native-head-blocking-");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean block=new AtomicBoolean(false),fail=new AtomicBoolean(false);
        VersionedWorkspace workspace=new VersionedWorkspace(root.toFile(),(file,value)->{
            if(!file.getName().equals("projects.json")&&block.get()){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("controlled writer release missing");}
            if(!file.getName().equals("projects.json")&&fail.get())throw new java.io.IOException("controlled real atomic writer entry failure");JsonFiles.write(file,value);
        });
        String project=workspace.create("Blocked source write").getString("id");var one=workspace.writeBytes(workspace.snapshot(project,"source.md",0),new byte[]{65});workspace.refreshFileHeadForHost(project,"source.md");ExecutorService writer=Executors.newSingleThreadExecutor(),reader=Executors.newSingleThreadExecutor();
        try{
            block.set(true);Future<VersionedWorkspace.FileSnapshot> pending=writer.submit(()->workspace.writeBytes(one,new byte[]{66}));assertTrue(entered.await(2,TimeUnit.SECONDS));
            var during=reader.submit(()->workspace.currentFileHeadForHost(project,"source.md")).get(1,TimeUnit.SECONDS);assertFalse(during.authorityKnown);assertEquals(-1,during.version);assertEquals("unknown",during.state);release.countDown();var two=pending.get(3,TimeUnit.SECONDS);assertEquals(two.revisionId,workspace.currentFileHeadForHost(project,"source.md").revisionId);
            block.set(false);fail.set(true);assertThrows(Exception.class,()->workspace.writeBytes(two,new byte[]{67}));assertFalse(workspace.currentFileHeadForHost(project,"source.md").authorityKnown);fail.set(false);
            var repaired=workspace.refreshFileHeadForHost(project,"source.md");assertEquals(two.revisionId,repaired.revisionId);assertEquals(two.sha256,repaired.sha256);
        }finally{release.countDown();writer.shutdownNow();reader.shutdownNow();assertTrue(writer.awaitTermination(3,TimeUnit.SECONDS));assertTrue(reader.awaitTermination(3,TimeUnit.SECONDS));}
    }
}
