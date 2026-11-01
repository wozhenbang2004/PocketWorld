package dev.pocketengine.features.workspace;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.junit.Test;

public final class WorkspaceArtifactContinuationTest {
    @Test public void selectedOldBytesSeedNewVersionAndActualLineageNotLatest()throws Exception{
        Path root=Files.createTempDirectory("c2-artifact-continue-");var workspace=new VersionedWorkspace(root.resolve("work").toFile());var artifacts=new WorkspaceArtifacts(root.resolve("artifacts").toFile(),workspace);String project=workspace.create("History").getString("id");
        var one=workspace.writeBytes(workspace.snapshot(project,"invoice.txt",0),"amount=40".getBytes(StandardCharsets.UTF_8));var frozen=artifacts.freezeFile(one,UUID.randomUUID().toString(),"","");var two=workspace.writeBytes(one,"amount=45".getBytes(StandardCharsets.UTF_8));
        var selection=new WorkspaceArtifactContinuation(workspace,artifacts,frozen.artifactId,frozen.sha256);assertEquals("amount=40",selection.originalText());assertEquals("amount=45",selection.currentText());assertEquals(two.version,selection.expected.version);assertEquals(2,workspace.snapshot(project,"invoice.txt",0).version);
        var saved=selection.saveText(selection.originalText()+"; note=old-version");assertEquals(3,saved.version);assertEquals("amount=40; note=old-version",workspace.read(project,"invoice.txt",0).getString("content"));assertEquals(frozen.artifactId,saved.json().getJSONObject("continuedFromArtifact").getString("artifactId"));assertEquals(frozen.sha256,saved.json().getJSONObject("continuedFromArtifact").getString("sha256"));assertEquals("amount=40",new String(artifacts.bytes(frozen),StandardCharsets.UTF_8));
        var cold=new VersionedWorkspace(root.resolve("work").toFile());assertEquals(frozen.sha256,cold.snapshot(project,"invoice.txt",3).json().getJSONObject("continuedFromArtifact").getString("sha256"));
    }
    @Test public void targetDriftAndIncompleteImeNeverOverwriteAndBinaryIsNeverEmptyText()throws Exception{
        Path root=Files.createTempDirectory("c2-artifact-continue-cas-");var workspace=new VersionedWorkspace(root.resolve("work").toFile());var artifacts=new WorkspaceArtifacts(root.resolve("artifacts").toFile(),workspace);String project=workspace.create("History CAS").getString("id");var one=workspace.writeBytes(workspace.snapshot(project,"data.bin",0),new byte[]{0,1,(byte)255});var frozen=artifacts.freezeFile(one,UUID.randomUUID().toString(),"","");var selection=new WorkspaceArtifactContinuation(workspace,artifacts,frozen.artifactId,frozen.sha256);assertArrayEquals(one.bytes(),selection.originalBytes());assertThrows(Exception.class,selection::originalText);var two=workspace.writeBytes(one,new byte[]{4,5});assertThrows(Exception.class,()->selection.saveBytes(new byte[]{6}));assertArrayEquals(two.bytes(),workspace.snapshot(project,"data.bin",0).bytes());
        var text=workspace.writeBytes(workspace.snapshot(project,"text.txt",0),new byte[]{65});var textRef=artifacts.freezeFile(text,UUID.randomUUID().toString(),"","");var textSelection=new WorkspaceArtifactContinuation(workspace,artifacts,textRef.artifactId,textRef.sha256);assertThrows(Exception.class,()->textSelection.saveText("\ud800"));assertEquals(text.version,workspace.snapshot(project,"text.txt",0).version);
    }
}
