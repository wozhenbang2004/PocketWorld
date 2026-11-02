package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.*;

/** Copy/append proposals lower to the original reviewed patch, never a second filesystem writer. */
public final class ProjectExactFileOperationsTest {
    private JSONObject snapshot;
    @Before public void create()throws Exception{snapshot=new JSONObject().put("checkout",new JSONObject().put("id","11111111-1111-1111-1111-111111111111").put("projectId","22222222-2222-2222-2222-222222222222")).put("files",new JSONArray());file("source.txt","原文🙂\n");file("target.txt","old target\n");file("empty.txt","");}
    private String sha(String text)throws Exception{return ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private void file(String path,String text)throws Exception{snapshot.getJSONArray("files").put(new JSONObject().put("path",path).put("content",text).put("sha256",sha(text)));}
    private JSONObject copy(String target,String expected)throws Exception{return new JSONObject().put("action","copy").put("fromPath","source.txt").put("expectedSourceHash",sha("原文🙂\n")).put("path",target).put("expectedHash",expected);}
    private JSONObject append(String path,String expected,String text)throws Exception{return new JSONObject().put("action","append").put("path",path).put("expectedHash",expected).put("content",text);}
    private JSONObject prepare(JSONObject edit)throws Exception{return ProjectExactPatch.prepare(snapshot,new JSONArray().put(edit)).getJSONObject(0);}
    @Test public void copyToAbsentTargetUsesExactFrozenSourceAndCreateExclusiveCas()throws Exception{
        String before=snapshot.toString();JSONObject prepared=prepare(copy("nested/new.txt","absent"));assertEquals("create",prepared.getString("action"));assertEquals("absent",prepared.getString("expectedHash"));assertEquals("原文🙂\n",prepared.getString("content"));assertEquals(sha("原文🙂\n"),prepared.getString("afterHash"));
        assertEquals("source.txt",prepared.getJSONObject("copiedFrom").getString("path"));assertEquals(sha("原文🙂\n"),prepared.getJSONObject("copiedFrom").getString("sha256"));assertEquals(before,snapshot.toString());assertEquals("copy",prepared.getJSONArray("changes").getJSONObject(0).getString("kind"));
    }
    @Test public void overwriteCopyRequiresExplicitActualDestinationHash()throws Exception{
        JSONObject prepared=prepare(copy("target.txt",sha("old target\n")));assertEquals("replace",prepared.getString("action"));assertEquals("old target\n",prepared.getString("beforeContent"));
        assertThrows(SecurityException.class,()->prepare(copy("target.txt","absent")));assertThrows(SecurityException.class,()->prepare(copy("target.txt",sha("stale"))));
    }
    @Test public void sourceHashMismatchMissingOrSameSourceIsNeverGuessed()throws Exception{
        assertThrows(SecurityException.class,()->prepare(copy("new.txt","absent").put("expectedSourceHash",sha("other"))));
        assertThrows(IllegalArgumentException.class,()->prepare(copy("new.txt","absent").put("fromPath","missing.txt")));
        assertThrows(IllegalArgumentException.class,()->prepare(copy("source.txt",sha("原文🙂\n"))));
        assertThrows(IllegalArgumentException.class,()->prepare(copy("new.txt","absent").put("fromPath","../outside")));
    }
    @Test public void appendUsesCurrentWholeTargetShaIncludingAnEmptyExistingFile()throws Exception{
        JSONObject changed=prepare(append("source.txt",sha("原文🙂\n"),"追加\n"));assertEquals("replace",changed.getString("action"));assertEquals("原文🙂\n追加\n",changed.getString("content"));assertEquals("append",changed.getJSONArray("changes").getJSONObject(0).getString("kind"));
        assertEquals("🙂",prepare(append("empty.txt",sha(""),"🙂")).getString("content"));assertThrows(SecurityException.class,()->prepare(append("source.txt",sha("stale"),"tail")));
    }
    @Test public void appendDoesNotCreateMissingFilesOrAcceptAnEmptyNoop()throws Exception{
        assertThrows(SecurityException.class,()->prepare(append("missing.txt","absent","tail")));assertThrows(IllegalArgumentException.class,()->prepare(append("empty.txt",sha(""),"")));
    }
    @Test public void mixedInstructionsAndInvalidTextFailBeforeAnyPreparedWrite()throws Exception{
        assertThrows(IllegalArgumentException.class,()->prepare(copy("new.txt","absent").put("content","unrelated")));
        assertThrows(IllegalArgumentException.class,()->prepare(append("empty.txt",sha(""),"tail").put("fromPath","source.txt")));
        assertThrows(IllegalArgumentException.class,()->prepare(append("empty.txt",sha(""),"bad\0text")));assertThrows(Exception.class,()->prepare(append("empty.txt",sha(""),"bad\ud800")));
    }
    @Test public void binaryOrOversizedSourceIsExplicitlyUnsupportedNotDecodedOrTruncated()throws Exception{
        snapshot.getJSONArray("files").put(new JSONObject().put("path","blob.bin").put("binary",true).put("contentBase64","AAE="));
        assertThrows(IllegalArgumentException.class,()->prepare(copy("new.txt","absent").put("fromPath","blob.bin").put("expectedSourceHash",ProjectSources.sha256(new byte[]{0,1}))));
        String large="x".repeat(ProjectExactPatch.MAX_TEXT_BYTES+1);file("large.txt",large);assertThrows(IllegalArgumentException.class,()->prepare(copy("new.txt","absent").put("fromPath","large.txt").put("expectedSourceHash",sha(large))));
    }
    @Test public void everyCopyUsesTheSameOriginalSnapshotNotEarlierEditsInTheBatch()throws Exception{
        JSONArray files=ProjectExactPatch.prepare(snapshot,new JSONArray().put(append("source.txt",sha("原文🙂\n"),"later")).put(copy("new.txt","absent")));
        JSONObject copied=files.getJSONObject(0);assertEquals("new.txt",copied.getString("path"));assertEquals("原文🙂\n",copied.getString("content"));assertEquals("原文🙂\nlater",files.getJSONObject(1).getString("content"));
    }
    @Test public void appendAndCopyRemainConditionalReversalCompatible()throws Exception{
        JSONArray files=ProjectExactPatch.prepare(snapshot,new JSONArray().put(copy("target.txt",sha("old target\n"))));JSONObject copy=files.getJSONObject(0);snapshot.getJSONArray("files").getJSONObject(1).put("content",copy.getString("content")).put("sha256",copy.getString("afterHash"));
        JSONObject receipt=new JSONObject().put("status","completed").put("receiptReceived",true).put("files",new JSONArray().put(new JSONObject().put("path","target.txt").put("state","applied").put("afterHash",copy.getString("afterHash"))));
        assertEquals("old target\n",ProjectExactPatch.reverse(snapshot,files,receipt,"33333333-3333-3333-3333-333333333333").getJSONObject(0).getString("content"));
    }
}
