package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real persisted native artifact bytes/identity and the parent output projection.
 * This focused JDK fixture is not a parent Runner/SQL or device acceptance test. */
public final class TeamParentArtifactOutputTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static final class Fixture {
        final Path root=Files.createTempDirectory("team-parent-artifact-");
        final VersionedWorkspace files=new VersionedWorkspace(root.resolve("workspace").toFile());
        final WorkspaceArtifacts artifacts=new WorkspaceArtifacts(root.resolve("artifacts").toFile(),files);
        final String draft=files.create("Original member draft").getString("id"),target=files.create("Unchanged owner project").getString("id"),task=id(),run=id(),team=id();
        final TeamWorkFiles.Workspace workspace=new TeamWorkFiles.Workspace(){
            public JSONObject call(String command,JSONObject args){throw new AssertionError("no legacy or mutating workspace command is allowed");}
            public boolean byteFilesSupported(){return true;}
            public WorkspaceArtifacts.ArtifactRef nativeArtifact(String artifact,String hash)throws Exception{return artifacts.reference(artifact,hash);}
            public byte[] artifactBytes(WorkspaceArtifacts.ArtifactRef selected)throws Exception{return artifacts.bytes(selected);}
        };
        Fixture()throws Exception{files.write(target,"asset.bin","Owner file stays present",0,false);}
        JSONObject freeze(String path,byte[] bytes,boolean deletion)throws Exception {
            var saved=files.writeBytes(files.snapshot(draft,path,0),bytes);
            if(deletion)saved=files.delete(saved);
            var artifact=artifacts.freezeFile(saved,id(),task,run);
            return new JSONObject().put("teamRunId",team).put("assignmentId",task).put("memberId","writer").put("taskId",task).put("runId",run)
                    .put("artifactId",artifact.artifactId).put("draftProjectId",draft).put("targetProjectId",target).put("path",artifact.path)
                    .put("baseHash","absent").put("baseVersion",0).put("draftRevisionId",artifact.revisionId).put("draftVersion",artifact.version)
                    .put("sha256",artifact.sha256).put("artifactKind",artifact.kind).put("bytes",artifact.sizeBytes).put("mimeType",artifact.mimeType);
        }
        JSONObject read(JSONObject ref,int offset,int length)throws Exception{return TeamParentHost.artifactOutput(TeamWorkReview.artifact(workspace,ref),offset,length);}
    }
    private static void noTextPage(JSONObject out)throws Exception {
        assertFalse(out.getBoolean("contentAvailable"));
        for(String key:new String[]{"content","contentBase64","encoding","offset","endOffset","totalChars","complete","hasMore","rangeUnit"})assertFalse(key+" must not imply a decoded text page",out.has(key));
    }
    @Test public void nativeBinaryReportsVerifiedFactsWithoutInventingAnEmptyTextPage()throws Exception {
        Fixture f=new Fixture();byte[] bytes={(byte)0x89,80,78,71,0,1,(byte)0xff};JSONObject ref=f.freeze("image.png",bytes,false);
        JSONObject out=f.read(ref,0,4000);noTextPage(out);
        assertEquals("binary_file",out.getString("kind"));assertFalse(out.getBoolean("tombstone"));assertTrue(out.getBoolean("requiresNativeByteRead"));
        assertEquals(bytes.length,out.getLong("sizeBytes"));assertEquals(ref.getString("mimeType"),out.getString("mimeType"));
        assertArrayEquals(bytes,f.artifacts.bytes(f.artifacts.reference(ref.getString("artifactId"),ref.getString("sha256"))));
        assertTrue(out.getString("message").contains("原生"));assertEquals(1,f.files.snapshot(f.target,"asset.bin",0).version);
    }
    @Test public void nativeTombstoneHasNoBodyAndDoesNotClaimTheOwnerFileWasDeleted()throws Exception {
        Fixture f=new Fixture();JSONObject ref=f.freeze("asset.bin",new byte[]{0,42},true),out=f.read(ref,0,4000);noTextPage(out);
        assertEquals("file_tombstone",out.getString("kind"));assertTrue(out.getBoolean("tombstone"));assertFalse(out.getBoolean("requiresNativeByteRead"));assertEquals(0,out.getLong("sizeBytes"));
        assertEquals("absent",ref.getString("sha256"));assertTrue(out.getString("message").contains("标记"));
        assertEquals("Owner file stays present",f.files.read(f.target,"asset.bin",0).getString("content"));
        assertThrows(IllegalStateException.class,()->f.artifacts.bytes(f.artifacts.reference(ref.getString("artifactId"),ref.getString("sha256"))));
    }
    @Test public void nativeTextKeepsTheExistingBoundedUnicodeRangeContract()throws Exception {
        Fixture f=new Fixture();String text="a\uD83D\uDE03b";JSONObject ref=f.freeze("notes.txt",text.getBytes(java.nio.charset.StandardCharsets.UTF_8),false);
        JSONObject first=f.read(ref,0,2);assertEquals("text_file",first.getString("kind"));assertTrue(first.getBoolean("contentAvailable"));assertEquals("a",first.getString("content"));assertEquals(1,first.getInt("endOffset"));assertTrue(first.getBoolean("hasMore"));
        JSONObject emoji=f.read(ref,1,2);assertEquals("\uD83D\uDE03",emoji.getString("content"));assertEquals(3,emoji.getInt("endOffset"));
        assertThrows(IllegalArgumentException.class,()->f.read(ref,2,1));assertThrows(IllegalArgumentException.class,()->f.read(ref,1,1));assertThrows(IllegalArgumentException.class,()->f.read(ref,0,8001));
    }
    @Test public void actualEmptyTextStaysTextRatherThanBecomingADeletion()throws Exception {
        Fixture f=new Fixture();JSONObject ref=f.freeze("empty.txt",new byte[0],false),out=f.read(ref,0,4000);
        assertEquals("text_file",out.getString("kind"));assertEquals("",out.getString("content"));assertTrue(out.getBoolean("complete"));assertFalse(out.optBoolean("tombstone"));assertNotEquals("absent",ref.getString("sha256"));
    }
    @Test public void nativeLargeTextRetainsItsBinaryArtifactKindInsteadOfBeingDecodedOrTruncated()throws Exception {
        Fixture f=new Fixture();byte[] bytes="x".repeat(65537).getBytes(java.nio.charset.StandardCharsets.UTF_8);JSONObject ref=f.freeze("large.txt",bytes,false),out=f.read(ref,0,8000);
        assertEquals("binary_file",ref.getString("artifactKind"));assertEquals("binary_file",out.getString("kind"));assertEquals(bytes.length,out.getLong("sizeBytes"));noTextPage(out);
    }
    @Test public void changedNativeReferenceKindSizeMimeOrIdentityIsDeniedBeforeProjection()throws Exception {
        Fixture f=new Fixture();JSONObject ref=f.freeze("asset.bin",new byte[]{0,42},false);
        for(String key:new String[]{"artifactKind","bytes","mimeType","runId"}){
            Object changed=key.equals("bytes")?999:key.equals("artifactKind")?"file_tombstone":key.equals("runId")?id():"changed/type";
            JSONObject forged=new JSONObject(ref.toString()).put(key,changed);assertThrows(SecurityException.class,()->f.read(forged,0,4000));
        }
    }
    @Test public void tamperedOriginalBytesAreDeniedBeforeProjection()throws Exception {
        Fixture f=new Fixture();JSONObject ref=f.freeze("asset.bin",new byte[]{0,42},false);Path record=f.root.resolve("artifacts").resolve(ref.getString("artifactId")+".json");
        JSONObject changed=new JSONObject(Files.readString(record)).put("contentBase64",java.util.Base64.getEncoder().encodeToString(new byte[]{0,43}));Files.writeString(record,changed.toString());
        assertThrows(SecurityException.class,()->f.read(ref,0,4000));
    }
    @Test public void unsupportedKindCannotUseAnIncidentalContentFieldAsText()throws Exception {
        assertThrows(SecurityException.class,()->TeamParentHost.artifactOutput(new JSONObject().put("kind","other").put("content","not an authorized text artifact"),0,4000));
    }
}
