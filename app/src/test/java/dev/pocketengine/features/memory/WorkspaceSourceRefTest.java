package dev.pocketengine.features.memory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkspaceSourceRefTest {
    private static final String PROJECT=UUID.randomUUID().toString(),REVISION=UUID.randomUUID().toString(),SHA="a".repeat(64);
    private static SourceRef source(){return SourceRef.workspaceFile(PROJECT,"资料/项目 说明.md",7,REVISION,SHA,10,24000,"owner","private");}
    @Test public void oldTwoKindsKeepExactFiveFieldSerializationAndHashSemantics()throws Exception{
        for(String kind:new String[]{"message","task_evidence"}){SourceRef r=new SourceRef(kind,"source:old",4,"owner","private");JSONObject json=r.toJson();assertEquals(5,json.length());assertFalse(json.has("workspace"));assertEquals(r,SourceRef.fromJson(json));assertEquals(Objects.hash(kind,"source:old",4L,"owner","private"),r.hashCode());}
        assertThrows(IllegalArgumentException.class,()->new SourceRef("message","x".repeat(241),1,"owner","private"));
    }
    @Test public void nativeWorkspaceAddressRoundTripsUnicodeWithoutTruncationOrPathOverride()throws Exception{
        SourceRef r=source();assertEquals(r,SourceRef.fromJson(r.toJson()));SourceRef.WorkspaceAddress a=SourceRef.workspaceAddress(r.sourceId);
        assertEquals(PROJECT,a.projectId);assertEquals("资料/项目 说明.md",a.path);assertEquals(10,a.offset);assertEquals(24000,a.chars);assertEquals(REVISION,r.workspace.revisionId);
    }
    @Test public void descriptorWithSameIdButDifferentLocatorIsRejected()throws Exception{
        JSONObject raw=source().toJson();raw.getJSONObject("workspace").put("path","different.txt");assertThrows(IllegalArgumentException.class,()->SourceRef.fromJson(raw));
        JSONObject offset=source().toJson();offset.getJSONObject("workspace").put("offset",0);assertThrows(IllegalArgumentException.class,()->SourceRef.fromJson(offset));
    }
    @Test public void locatorClosedKeysAndActualVersionRevisionAndHashAreRequired()throws Exception{
        JSONObject raw=source().toJson();raw.getJSONObject("workspace").put("executionAuthorized",true);assertThrows(IllegalArgumentException.class,()->SourceRef.fromJson(raw));
        assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceFile(PROJECT,"a.txt",0,REVISION,SHA,0,1,"owner","private"));
        assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceFile(PROJECT,"a.txt",1,"not-a-revision",SHA,0,1,"owner","private"));
        assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceFile(PROJECT,"a.txt",1,REVISION,"fake-sha",0,1,"owner","private"));
    }
    @Test public void addressCannotSmuggleParentPathsInvalidUnicodeOrNoncanonicalJson()throws Exception{
        assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceSourceId(PROJECT,"../secret",0,1));assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceSourceId(PROJECT,"bad\ud800",0,1));
        String raw=new JSONArray().put(PROJECT).put("a.txt").put(0).put(20).toString()+" true";
        assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceAddress("vws:"+Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8))));
        String[] pair={"vws:"+Base64.getUrlEncoder().withoutPadding().encodeToString(("[ \""+PROJECT+"\",\"a.txt\",0,20]").getBytes(StandardCharsets.UTF_8))};
        assertThrows(IllegalArgumentException.class,()->SourceRef.workspaceAddress(pair[0]));
    }
    @Test public void memberValidatorPreservesExactWorkspaceLocatorAndCannotBroadenIt()throws Exception{
        JSONObject span=new JSONObject().put("sourceRef",source().toJson()).put("offset",0).put("chars",10).put("unit","UTF-16");
        JSONObject checked=ReadAudience.checkedSources(new JSONArray().put(span),"owner","private").getJSONObject(0);
        assertEquals(source(),SourceRef.fromJson(checked.getJSONObject("sourceRef")));
        JSONObject override=new JSONObject(span.toString());override.getJSONObject("sourceRef").put("path","secret");assertThrows(IllegalArgumentException.class,()->ReadAudience.checkedSources(new JSONArray().put(override),"owner","private"));
    }
    @Test public void realUpdateKeepsAddressButChangesExactSourceIdentity()throws Exception{
        SourceRef before=source(),after=SourceRef.workspaceFile(PROJECT,before.workspace.path,8,UUID.randomUUID().toString(),"b".repeat(64),10,24000,"owner","private");
        assertEquals(before.sourceId,after.sourceId);assertNotEquals(before,after);assertFalse(before.toJson().has("executionAuthorized"));
        SourceRef tombstone=SourceRef.workspaceFile(PROJECT,before.workspace.path,9,UUID.randomUUID().toString(),"absent",10,24000,"owner","private");assertEquals("absent",tombstone.workspace.sha256);assertEquals(tombstone,SourceRef.fromJson(tombstone.toJson()));
    }
}
