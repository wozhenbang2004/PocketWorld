package dev.pocketengine.features.attachments;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.Test;

/** Actual catalog bytes/deletion versions; NativeSource SQL is explicitly a fixture here. */
public final class TeamWorkspaceBytesOutcomeTest {
    private static String uuid(){return UUID.randomUUID().toString();}
    private static final class Fixture {
        final Path root=Files.createTempDirectory("c2-team-byte-outcomes-");final VersionedWorkspace workspace=new VersionedWorkspace(root.resolve("workspace").toFile());final WorkspaceArtifacts artifacts=new WorkspaceArtifacts(root.resolve("artifacts").toFile(),workspace);
        final String project=workspace.create("Member bytes").getString("id"),target=workspace.create("Owner unchanged").getString("id"),task=uuid(),run=uuid(),team=uuid();final AtomicBoolean allowed=new AtomicBoolean(true);JSONObject receipt;
        Fixture()throws Exception{}
        JSONObject reference(WorkspaceArtifacts.ArtifactRef artifact)throws Exception{return new JSONObject().put("teamRunId",team).put("assignmentId",task).put("memberId","writer").put("taskId",task).put("runId",run).put("artifactId",artifact.artifactId).put("draftProjectId",project).put("targetProjectId",target).put("path",artifact.path).put("baseHash","absent").put("baseVersion",0).put("draftRevisionId",artifact.revisionId).put("draftVersion",artifact.version).put("sha256",artifact.sha256).put("artifactKind",artifact.kind).put("bytes",artifact.sizeBytes).put("mimeType",artifact.mimeType).put("assignmentRevision",1);}
        TeamWorkspaceOutcomeHost host(){return new TeamWorkspaceOutcomeHost(new TeamWorkspaceOutcomeHost.NativeSource(){
            public JSONObject resolveTeamArtifact(String t,String a,String m,String taskId,String frozenRun,String id,RunControl control)throws Exception{control.check();if(!allowed.get()||!team.equals(t)||!task.equals(a)||!task.equals(taskId)||!run.equals(frozenRun)||!receipt.getString("artifactId").equals(id))throw new SecurityException("Original SQL fixture scope denied");return new JSONObject(receipt.toString());}
            public void checkTeamArtifact(JSONObject exact,RunControl control)throws Exception{control.check();if(!allowed.get()||!dev.pocketengine.features.local.project.ProjectSources.canonical(receipt).equals(dev.pocketengine.features.local.project.ProjectSources.canonical(exact)))throw new SecurityException("Original SQL fixture revoked or changed");}
        },new TeamWorkspaceOutcomeHost.FrozenArtifactRead(){public JSONObject read(String id)throws Exception{return artifacts.read(id);}public byte[] bytes(String id,String sha)throws Exception{return artifacts.bytes(artifacts.reference(id,sha));}});}
    }
    @Test public void binaryOriginalAndExactViewerSourceNeverBecomeEmptyTextOrPrivateAttachment()throws Exception{
        Fixture f=new Fixture();byte[] actual={(byte)0x89,80,78,71,0,1,(byte)0xff};var saved=f.workspace.writeBytes(f.workspace.snapshot(f.project,"image.png",0),actual);var artifact=f.artifacts.freezeFile(saved,uuid(),f.task,f.run);f.receipt=f.reference(artifact);var selected=TeamWorkspaceOutcomeHost.Selection.fromManifest(f.receipt);var host=f.host();
        JSONObject preview=host.preview(selected,0,12000,new RunControl());assertFalse(preview.getBoolean("contentAvailable"));assertFalse(preview.has("content"));assertEquals("binary_file",preview.getJSONObject("artifact").getString("kind"));assertArrayEquals(actual,host.verifiedBytes(selected,new RunControl()));
        var source=host.originalFile(selected,new RunControl());assertEquals(artifact.sha256,source.sha256);assertArrayEquals(actual,source.bytes(new RunControl()));assertFalse(source.metadata().has("contentBase64"));f.workspace.delete(saved);assertArrayEquals(actual,source.bytes(new RunControl()));
        assertThrows(Exception.class,()->host.preview(TeamWorkspaceOutcomeHost.Selection.fromManifest(new JSONObject(f.receipt.toString()).put("bytes",99)),0,12000,new RunControl()));f.allowed.set(false);assertThrows(Exception.class,()->source.bytes(new RunControl()));assertEquals(0,f.workspace.list(f.target).length());
    }
    @Test public void actualDeletionArtifactCarriesNoBodyAndCannotBeExportedAsAnEmptyFile()throws Exception{
        Fixture f=new Fixture();var one=f.workspace.writeBytes(f.workspace.snapshot(f.project,"asset.bin",0),new byte[]{0,42});var deleted=f.workspace.delete(one);var artifact=f.artifacts.freezeFile(deleted,uuid(),f.task,f.run);f.receipt=f.reference(artifact);var selected=TeamWorkspaceOutcomeHost.Selection.fromManifest(f.receipt);var host=f.host();var preview=host.preview(selected,0,12000,new RunControl());
        assertTrue(preview.getBoolean("tombstone"));assertFalse(preview.has("content"));assertEquals("absent",selected.sha256());assertThrows(Exception.class,()->host.verifiedBytes(selected,new RunControl()));assertTrue(host.originalFile(selected,new RunControl()).tombstone);
        assertThrows(Exception.class,()->new TeamWorkspaceOutcomeHost.Selection(new JSONObject(selected.toJson().toString()).put("artifactKind","binary_file")));assertThrows(Exception.class,()->new TeamWorkspaceOutcomeHost.Selection(new JSONObject(selected.toJson().toString()).put("bytes",1)));assertEquals(one.revisionId,f.workspace.snapshot(f.project,"asset.bin",1).revisionId);
    }
}
