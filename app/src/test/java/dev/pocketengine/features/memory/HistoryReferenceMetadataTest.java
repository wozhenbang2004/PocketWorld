package dev.pocketengine.features.memory;

import dev.pocketengine.runtime.ModelGateway;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import org.junit.Test;
import static org.junit.Assert.*;

/** Text is always cleaned; only validated native body-free locator fields are restored. */
public final class HistoryReferenceMetadataTest {
    private static final String CALLER="aaaaaaaa-1111-1111-1111-111111111111",CALLER_RUN="aaaaaaaa-2222-2222-2222-222222222222",CONVERSATION="aaaaaaaa-3333-3333-3333-333333333333";
    private static final String TASK="aaaaaaaa-4444-4444-4444-444444444444",RUN="aaaaaaaa-5555-5555-5555-555555555555",OUTPUT="aaaaaaaa-6666-6666-6666-666666666666",SELECTION="aaaaaaaa-7777-7777-7777-777777777777";
    private static final String HASH="sha256:"+"a".repeat(64),MEMBERSHIP="sha256:"+"b".repeat(64);
    private JSONObject envelope()throws Exception{return ConversationHistory.proof(new JSONObject().put("callerTaskId",CALLER).put("callerRunId",CALLER_RUN).put("conversationId",CONVERSATION).put("ownerId","owner").put("spaceId","private").put("status","current"));}
    private JSONObject locator()throws Exception{return new JSONObject().put("sourceKind","task_output").put("sourceTaskId",TASK).put("sourceRunId",RUN).put("sourceEpoch",3).put("sourceId",ToolOutputStore.sourceId(TASK,RUN,OUTPUT)).put("revision",HASH).put("membershipFingerprint",MEMBERSHIP);}
    private JSONObject clean(JSONObject source,String captured)throws Exception{
        JSONObject result=new JSONObject();for(Iterator<String> it=source.keys();it.hasNext();){String key=it.next();Object value=source.get(key);if(value instanceof JSONObject)value=clean((JSONObject)value,captured);else if(value instanceof JSONArray)value=clean((JSONArray)value,captured);else if(value instanceof String)value=ModelGateway.redact((String)value,captured);result.put(key,value);}return result;
    }
    private JSONArray clean(JSONArray source,String captured)throws Exception{JSONArray result=new JSONArray();for(int i=0;i<source.length();i++){Object value=source.get(i);if(value instanceof JSONObject)value=clean((JSONObject)value,captured);else if(value instanceof JSONArray)value=clean((JSONArray)value,captured);else if(value instanceof String)value=ModelGateway.redact((String)value,captured);result.put(value);}return result;}
    private JSONObject outputRead(String body,int offset,int total)throws Exception{
        JSONObject selected=locator();JSONObject result=envelope().put("sourceKind","task_output").put("sourceTaskId",TASK).put("sourceRunId",RUN).put("sourceEpoch",3)
                .put("taskId",TASK).put("runId",RUN).put("sourceId",selected.getString("sourceId")).put("revision",HASH)
                .put("taskSourceRefs",new JSONArray().put(selected)).put("body",body).put("title","a private native title")
                .put("readRange",TaskMemoryTrace.rawRange(offset,offset+body.length(),total))
                .put("sourceJump",TaskMemoryTrace.rawJump(TASK,RUN,selected,offset,body.length()));return result;
    }
    private interface Checked{void run()throws Exception;}
    private void denied(Checked action)throws Exception{try{action.run();fail("invalid native metadata accepted");}catch(SecurityException|IllegalArgumentException expected){}}

    @Test public void shortCapturedKeyCannotDestroyOpaqueSelectionsOrHashesAndCannotRestoreFreeText()throws Exception{
        JSONObject original=envelope().put("entries",new JSONArray().put(locator().put("selectionId",SELECTION).put("title","a native title").put("snippet","a excerpt"))).put("nextCursor",SELECTION);
        JSONObject cleaned=clean(original,"a");assertNotEquals(SELECTION,cleaned.getJSONArray("entries").getJSONObject(0).getString("selectionId"));
        JSONObject result=ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.SEARCH,original,cleaned);JSONObject entry=result.getJSONArray("entries").getJSONObject(0);
        assertEquals(CALLER,result.getString("callerTaskId"));assertEquals(SELECTION,result.getString("nextCursor"));assertEquals(SELECTION,entry.getString("selectionId"));assertEquals(HASH,entry.getString("revision"));assertEquals(MEMBERSHIP,entry.getString("membershipFingerprint"));
        assertEquals(cleaned.getJSONArray("entries").getJSONObject(0).getString("title"),entry.getString("title"));assertEquals(cleaned.getJSONArray("entries").getJSONObject(0).getString("snippet"),entry.getString("snippet"));assertFalse(result.getBoolean("executionAuthorized"));
    }
    @Test public void exactOutputCitationIsCanonicalButBodyAndTitleRemainCapturedKeyRedacted()throws Exception{
        JSONObject original=outputRead("a original a body",12,100);JSONObject cleaned=clean(original,"a");JSONObject result=ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.READ,original,cleaned);
        assertEquals(cleaned.getString("body"),result.getString("body"));assertEquals(cleaned.getString("title"),result.getString("title"));assertEquals(HASH,result.getString("revision"));assertEquals(original.getJSONObject("readRange").toString(),result.getJSONObject("readRange").toString());
        JSONObject citation=new JSONObject().put("callerTaskId",result.getString("callerTaskId")).put("callerRunId",result.getString("callerRunId")).put("conversationId",result.getString("conversationId")).put("sourceJump",result.getJSONObject("sourceJump")).put("taskSource",result.getJSONArray("taskSourceRefs").getJSONObject(0));assertNotNull(ConversationHistory.checkedCitation(citation));assertFalse(result.has("executionProof"));
    }
    @Test public void messageSourceKeepsOriginalNativeRevisionAndFingerprintNotCleanedProse()throws Exception{
        JSONObject message=new JSONObject().put("id",SELECTION).put("revision",7).put("content","a original message").put("at",100);
        JSONObject original=ConversationHistory.messagePage(CONVERSATION,message,0,64);JSONObject ids=envelope();for(Iterator<String> it=ids.keys();it.hasNext();){String key=it.next();original.put(key,ids.get(key));}
        JSONObject cleaned=clean(original,"a");JSONObject result=ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.READ,original,cleaned);
        assertEquals(7,result.getJSONObject("sourceRef").getLong("revision"));assertEquals(SELECTION,result.getJSONObject("sourceRef").getString("sourceId"));assertEquals(original.getString("bodyFingerprint"),result.getString("bodyFingerprint"));assertEquals(cleaned.getString("body"),result.getString("body"));assertEquals("a original message",message.getString("content"));
    }
    @Test public void structuralMismatchOrForgedAuthorityCannotBeRestoredAsCurrent()throws Exception{
        JSONObject original=envelope().put("entries",new JSONArray().put(locator().put("selectionId",SELECTION)));
        denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.SEARCH,new JSONObject(original.toString()).put("executionAuthorized",true),clean(original,"a")));
        denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.SEARCH,new JSONObject(original.toString()).put("spaceId","group:foreign"),clean(original,"a")));
        denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.SEARCH,original,new JSONObject().put("entries",new JSONArray())));
        original.getJSONArray("entries").getJSONObject(0).put("selectionId","model-arbitrary-id");denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.SEARCH,original,clean(original,"a")));
    }
    @Test public void mismatchedTaskHashOrRangeCannotBorrowAnotherOriginalCitation()throws Exception{
        JSONObject wrongTask=outputRead("actual body",12,100);wrongTask.getJSONObject("sourceJump").put("taskId",CALLER);denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.READ,wrongTask,clean(wrongTask,"a")));
        JSONObject wrongHash=outputRead("actual body",12,100);wrongHash.put("revision","sha256:"+"c".repeat(64));denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.READ,wrongHash,clean(wrongHash,"a")));
        JSONObject wrongRange=outputRead("actual body",12,100);wrongRange.getJSONObject("readRange").put("chars",12);denied(()->ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.READ,wrongRange,clean(wrongRange,"a")));
    }
    @Test public void zeroLengthEofKeepsNoPositiveCitationAndUnrelatedToolsGetNoRestoration()throws Exception{
        JSONObject eof=outputRead("",100,100);JSONObject result=ConversationHistory.restoreSafeReferenceMetadata(ConversationHistory.READ,eof,clean(eof,"a"));assertEquals(0,result.getJSONObject("readRange").getInt("chars"));assertEquals("",result.getString("body"));assertEquals(0,result.getJSONObject("sourceJump").getInt("chars"));
        JSONObject unrelated=ConversationHistory.restoreSafeReferenceMetadata("unrelated_tool",eof,clean(eof,"a"));assertNotEquals(CALLER,unrelated.getString("callerTaskId"));assertEquals(clean(eof,"a").toString(),unrelated.toString());
    }
}
