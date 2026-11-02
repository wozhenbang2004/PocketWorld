package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.*;

public final class ProjectExactRangePatchTest {
    private JSONObject snapshot;private String text;
    @Before public void create()throws Exception{source("same\nsame\n");}
    private void source(String value)throws Exception{text=value;snapshot=new JSONObject().put("files",new JSONArray().put(new JSONObject().put("path","a.txt").put("content",text).put("sha256",ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8)))));}
    private JSONObject hunk(String old,String value,Object offset)throws Exception{return new JSONObject().put("oldText",old).put("newText",value).put("startOffset",offset);}
    private JSONObject edit(JSONArray hunks)throws Exception{return new JSONObject().put("path","a.txt").put("expectedHash",ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8))).put("replacements",hunks);}
    private JSONObject patch(JSONArray hunks)throws Exception{return ProjectExactPatch.prepare(snapshot,new JSONArray().put(edit(hunks))).getJSONObject(0);}
    @Test public void explicitOffsetChoosesTheExactRepeatedOccurrenceInsteadOfFuzzyMatching()throws Exception{
        JSONObject file=patch(new JSONArray().put(hunk("same","chosen",5)));assertEquals("same\nchosen\n",file.getString("content"));JSONObject change=file.getJSONArray("changes").getJSONObject(0);assertEquals(2,change.getInt("line"));assertEquals(5,change.getInt("startOffset"));assertEquals(9,change.getInt("endOffset"));assertEquals("UTF16_code_units",change.getString("offsetUnit"));
        assertThrows(SecurityException.class,()->patch(new JSONArray().put(new JSONObject().put("oldText","same").put("newText","chosen"))));
    }
    @Test public void pinnedEmptyRangeCanInsertAtTheBeginningOrEndButUnanchoredEmptyContextCannot()throws Exception{
        assertEquals("prefix🙂same\nsame\n",patch(new JSONArray().put(hunk("","prefix🙂",0))).getString("content"));assertEquals("same\nsame\ntail",patch(new JSONArray().put(hunk("","tail",10L))).getString("content"));
        assertThrows(IllegalArgumentException.class,()->patch(new JSONArray().put(new JSONObject().put("oldText","").put("newText","unanchored"))));
    }
    @Test public void negativeFractionalStringAndOutOfRangeOffsetsAreNotCoercedOrRelocated()throws Exception{
        for(Object offset:new Object[]{-1,1.5,"5",Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->patch(new JSONArray().put(hunk("same","chosen",offset))));
        assertThrows(SecurityException.class,()->patch(new JSONArray().put(hunk("wrong","chosen",5))));
    }
    @Test public void offsetUsesUtf16ButCannotCutASupplementaryCharacterInHalf()throws Exception{
        source("🙂same");assertEquals("🙂chosen",patch(new JSONArray().put(hunk("same","chosen",2))).getString("content"));
        assertThrows(IllegalArgumentException.class,()->patch(new JSONArray().put(hunk("","x",1))));assertEquals("x🙂same",patch(new JSONArray().put(hunk("","x",0))).getString("content"));
    }
    @Test public void laterOffsetsReferToTheWorkingTextAfterPrecedingHunksAndStillVerifyOldText()throws Exception{
        source("aa aa");JSONObject file=patch(new JSONArray().put(hunk("aa","BBBB",0)).put(hunk("aa","CC",5)));assertEquals("BBBB CC",file.getString("content"));assertEquals("after_preceding_hunks",file.getJSONArray("changes").getJSONObject(1).getString("offsetBasis"));
        assertThrows(SecurityException.class,()->patch(new JSONArray().put(hunk("aa","BBBB",0)).put(hunk("aa","CC",3))));
    }
    @Test public void explicitLocationDoesNotBypassWholeFileShaOrTextQuotas()throws Exception{
        JSONObject edit=edit(new JSONArray().put(hunk("same","chosen",5)));edit.put("expectedHash","a".repeat(64));assertThrows(SecurityException.class,()->ProjectExactPatch.prepare(snapshot,new JSONArray().put(edit)));
        assertThrows(IllegalArgumentException.class,()->patch(new JSONArray().put(hunk("","x".repeat(6001),0))));
    }
}
