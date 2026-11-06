package dev.pocketengine.features.workspace;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
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
    @Test public void committedOriginalTextBytesDeletesAndArtifactContinuationPublishOnlyDeferredMetadata()throws Exception{
        Path root=Files.createTempDirectory("trace-workspace-source-events-");List<Runnable> queued=new ArrayList<>();List<JSONObject> events=new ArrayList<>();
        WorkspaceModule module=new WorkspaceModule(root.toFile(),queued::add,(feature,payload)->{try{events.add(new JSONObject().put("type","feature_event").put("feature",feature).put("payload",payload));}catch(Exception invalid){throw new IllegalStateException(invalid);}});
        String project=module.handle("create",new JSONObject().put("name","Original source events")).getString("id");
        assertTrue(queued.isEmpty());
        JSONObject first=module.handle("write",new JSONObject().put("projectId",project).put("path","source.md").put("content","PRIVATE_ORIGINAL_BODY").put("expectedVersion",0));
        var one=module.fileSnapshotForHost(project,"source.md",0);assertEquals(first.getInt("version"),one.version);assertEquals(1,queued.size());assertTrue(events.isEmpty());
        var artifact=module.freezeFileForHost(one,UUID.randomUUID().toString(),"","");assertEquals(1,queued.size());
        var two=module.writeBytesForHost(one,"PRIVATE_CHANGED_BODY".getBytes(StandardCharsets.UTF_8));
        var continuation=module.continueArtifactForHost(artifact.artifactId,artifact.sha256);assertEquals("PRIVATE_ORIGINAL_BODY",continuation.originalText());
        var three=continuation.saveText("PRIVATE_CONTINUED_BODY");
        var four=module.deleteForHost(three);assertTrue(four.tombstone);
        module.handle("write",new JSONObject().put("projectId",project).put("path","source.md").put("content","PRIVATE_RESTORED_BODY").put("expectedVersion",four.version));
        module.handle("removeFile",new JSONObject().put("projectId",project).put("path","source.md").put("expectedVersion",5));
        assertEquals(6,queued.size());assertTrue(events.isEmpty());
        // Source reads, catalog refreshes and stale owner/typed CAS cannot emit changes.
        module.snapshot();module.refreshFileHeadForHost(project,"source.md");module.fileSnapshotForHost(project,"source.md",1);
        assertThrows(Exception.class,()->module.writeBytesForHost(two,new byte[]{65}));
        assertThrows(Exception.class,()->module.handle("write",new JSONObject().put("projectId",project).put("path","source.md").put("content","UNCOMMITTED_BODY").put("expectedVersion",5)));
        assertEquals(6,queued.size());
        queued.forEach(Runnable::run);assertEquals(6,events.size());Set<String> revisions=new HashSet<>();
        for(int index=0;index<events.size();index++){
            JSONObject event=events.get(index),payload=event.getJSONObject("payload");assertEquals("feature_event",event.getString("type"));assertEquals("workspace",event.getString("feature"));assertEquals("workspace_changed",payload.getString("event"));
            Set<String> fields=new HashSet<>();payload.keys().forEachRemaining(fields::add);
            assertEquals(Set.of("event","projectId","path","version","revisionId","sha256","sizeBytes","deleted"),fields);
            assertEquals(project,payload.getString("projectId"));assertEquals("source.md",payload.getString("path"));assertEquals(index+1,payload.getInt("version"));assertTrue(revisions.add(payload.getString("revisionId")));
            var actual=module.fileSnapshotForHost(project,"source.md",index+1);assertEquals(actual.revisionId,payload.getString("revisionId"));assertEquals(actual.sha256,payload.getString("sha256"));assertEquals(actual.sizeBytes,payload.getLong("sizeBytes"));assertEquals(actual.tombstone,payload.getBoolean("deleted"));assertFalse(event.toString().contains("PRIVATE_"));assertFalse(event.toString().contains("UNCOMMITTED_BODY"));
        }
        assertEquals("PRIVATE_ORIGINAL_BODY",new String(module.fileArtifactBytesForHost(artifact),StandardCharsets.UTF_8));
    }
    @Test public void failedAtomicWriteAndConflictDoNotPublishCommittedSourceMetadata()throws Exception{
        Path root=Files.createTempDirectory("trace-workspace-source-failure-");AtomicBoolean fail=new AtomicBoolean(false);List<JSONObject> changes=new ArrayList<>();
        VersionedWorkspace workspace=new VersionedWorkspace(root.toFile(),(file,value)->{if(fail.get())throw new java.io.IOException("controlled original write failure");JsonFiles.write(file,value);},changes::add);
        String project=workspace.create("Failed source writes").getString("id");var one=workspace.writeBytes(workspace.snapshot(project,"source.md",0),new byte[]{65});assertEquals(1,changes.size());workspace.refreshFileHeadForHost(project,"source.md");
        fail.set(true);Exception failure=assertThrows(Exception.class,()->workspace.writeBytes(one,new byte[]{66}));assertEquals("controlled original write failure",failure.getMessage());assertEquals(1,changes.size());assertFalse(workspace.currentFileHeadForHost(project,"source.md").authorityKnown);
        fail.set(false);assertEquals(one.revisionId,workspace.refreshFileHeadForHost(project,"source.md").revisionId);assertThrows(Exception.class,()->workspace.write(project,"source.md","not committed",0,false));assertEquals(1,changes.size());
    }
    @Test public void failedPresentationCannotTurnCommittedFileIntoFailedWrite()throws Exception{
        VersionedWorkspace workspace=new VersionedWorkspace(Files.createTempDirectory("trace-workspace-presentation-failure-").toFile(),JsonFiles::write,metadata->{throw new IllegalStateException("presentation unavailable");});
        String project=workspace.create("Presentation failure").getString("id");var one=workspace.writeBytes(workspace.snapshot(project,"source.md",0),new byte[]{65});assertEquals(1,one.version);assertArrayEquals(new byte[]{65},workspace.snapshot(project,"source.md",0).bytes());
    }
}
