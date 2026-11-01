package dev.pocketengine.features.workspace;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;

/** Real original AtomicFile-style records/cold parsing, no model/base64 admission or fake byte store. */
public final class WorkspaceBytesTest {
    @Test public void binaryVersionCasDeleteTombstoneAndColdHistoricalBytesRemainExact()throws Exception {
        Path directory=Files.createTempDirectory("c2-byte-cas-");VersionedWorkspace first=new VersionedWorkspace(directory.toFile());String project=first.create("Binary changes").getString("id");
        var absent=first.snapshot(project,"asset.bin",0);assertTrue(absent.tombstone);assertEquals(0,absent.version);assertEquals("absent",absent.sha256);
        byte[] original={0,1,2,(byte)0xff,(byte)0x80,10};var one=first.writeBytes(absent,original);original[1]=99;assertEquals(1,one.version);assertEquals("binary",one.kind);assertEquals(1,one.bytes()[1]);byte[] copy=one.bytes();copy[2]=88;assertEquals(2,one.bytes()[2]);
        assertThrows(Exception.class,()->first.writeBytes(project,"asset.bin",new byte[]{5},one.version,"0".repeat(64)));assertArrayEquals(one.bytes(),first.snapshot(project,"asset.bin",0).bytes());
        var two=first.writeBytes(one,new byte[]{0,7,(byte)0xee});assertThrows(Exception.class,()->first.delete(one));assertEquals(2,two.version);
        var removed=first.delete(two);assertTrue(removed.tombstone);assertEquals(3,removed.version);assertEquals("absent",removed.sha256);assertEquals(0,first.list(project).length());
        VersionedWorkspace cold=new VersionedWorkspace(directory.toFile());assertArrayEquals(new byte[]{0,1,2,(byte)0xff,(byte)0x80,10},cold.snapshot(project,"asset.bin",1).bytes());assertTrue(cold.snapshot(project,"asset.bin",3).tombstone);
        assertThrows(Exception.class,()->cold.writeBytes(one,new byte[]{9}));var readded=cold.writeBytes(cold.snapshot(project,"asset.bin",0),new byte[]{0,42});assertEquals(4,readded.version);assertArrayEquals(new byte[]{0,42},new VersionedWorkspace(directory.toFile()).snapshot(project,"asset.bin",4).bytes());
    }
    @Test public void legacyTextShaAndUtf8StayUnchangedWhileBinaryIsNotSilentlyTextExported()throws Exception {
        Path directory=Files.createTempDirectory("c2-byte-text-");VersionedWorkspace w=new VersionedWorkspace(directory.toFile());String p=w.create("Legacy").getString("id");String text="中文 😀\nold line\n";JSONObject saved=w.write(p,"notes.md",text,0,false);var pin=w.snapshot(p,"notes.md",1);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),pin.bytes());assertEquals(dev.pocketengine.features.local.project.ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8)),pin.sha256);assertEquals(text,w.read(p,"notes.md",0).getString("content"));
        w.writeBytes(w.snapshot(p,"image.bin",0),new byte[]{0,(byte)0xff,1});JSONObject export=w.export(p);assertEquals(1,export.getJSONArray("files").length());assertFalse(export.getBoolean("complete"));assertEquals("image.bin",export.getJSONArray("omitted").getJSONObject(0).getString("path"));assertEquals(2,w.snapshotProject(p).files().size());
        assertThrows(Exception.class,()->w.write(p,"notes.md","\ud800",saved.getInt("version"),false));assertEquals(text,w.read(p,"notes.md",0).getString("content"));
    }
    @Test public void originalCatalogFreezesActualBytesNotLatestAndExactReferenceColdExports()throws Exception {
        Path directory=Files.createTempDirectory("c2-byte-artifacts-");VersionedWorkspace w=new VersionedWorkspace(directory.resolve("workspace").toFile());WorkspaceArtifacts artifacts=new WorkspaceArtifacts(directory.resolve("artifacts").toFile(),w);String p=w.create("Artifacts").getString("id");
        var one=w.writeBytes(w.snapshot(p,"image.png",0),new byte[]{(byte)0x89,80,78,71,0,1,(byte)0xff});String op=UUID.randomUUID().toString();var ref=artifacts.freezeFile(one,op,"","");assertEquals(ref.artifactId,artifacts.freezeFile(one,op,"","").artifactId);w.delete(one);
        assertArrayEquals(one.bytes(),artifacts.bytes(ref));WorkspaceArtifacts cold=new WorkspaceArtifacts(directory.resolve("artifacts").toFile(),new VersionedWorkspace(directory.resolve("workspace").toFile()));var selected=cold.reference(ref.artifactId,ref.sha256);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();assertEquals(one.sizeBytes,cold.exportFile(selected,out));assertArrayEquals(one.bytes(),out.toByteArray());assertThrows(Exception.class,()->cold.bytes(ref));assertFalse(cold.read(ref.artifactId).getBoolean("contentAvailable"));assertFalse(selected.json().has("contentBase64"));
        assertThrows(Exception.class,()->cold.reference(ref.artifactId,"0".repeat(64)));
        var deletion=w.snapshot(p,"image.png",0);var tomb=artifacts.freezeFile(deletion,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString());assertTrue(tomb.tombstone);assertEquals("file_tombstone",tomb.kind);assertEquals("absent",tomb.sha256);assertEquals(deletion.revisionId,tomb.revisionId);assertEquals(deletion.version,tomb.version);assertThrows(Exception.class,()->artifacts.bytes(tomb));var coldTomb=cold.reference(tomb.artifactId,"absent");assertTrue(coldTomb.tombstone);assertFalse(cold.read(tomb.artifactId).has("content"));assertThrows(Exception.class,()->cold.exportFile(coldTomb,new java.io.ByteArrayOutputStream()));
    }
    @Test public void originalBoundsAndEightRevisionRetentionRejectRatherThanDeleteOtherData()throws Exception {
        Path directory=Files.createTempDirectory("c2-byte-bounds-");VersionedWorkspace w=new VersionedWorkspace(directory.toFile());String p=w.create("Bounds").getString("id");var absent=w.snapshot(p,"large.bin",0);assertThrows(Exception.class,()->w.writeBytes(absent,new byte[VersionedWorkspace.MAX_FILE_BYTES+1]));assertEquals(0,w.snapshot(p,"large.bin",0).version);
        var pin=absent;for(int n=0;n<9;n++)pin=w.writeBytes(pin,new byte[]{0,(byte)n});assertThrows(Exception.class,()->w.snapshot(p,"large.bin",1));assertArrayEquals(new byte[]{0,8},w.snapshot(p,"large.bin",9).bytes());assertEquals(8,w.history(p,"large.bin").length());
        assertThrows(Exception.class,()->w.writeBytes(p,"../outside.bin",new byte[]{0},0,"absent"));
        var huge=w.snapshot(p,"huge.bin",0);byte[] bytes=new byte[VersionedWorkspace.MAX_FILE_BYTES];new Random(19).nextBytes(bytes);huge=w.writeBytes(huge,bytes);var finalHuge=huge;assertThrows(Exception.class,()->w.writeBytes(finalHuge,bytes));assertArrayEquals(bytes,w.snapshot(p,"huge.bin",0).bytes());
    }
}
