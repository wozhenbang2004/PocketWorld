package dev.pocketengine.features.memory;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TaskMemoryHistoryTest {
    @Test public void nativeObservationProjectionKeepsRealFactsPixelsAndOriginalAudit()throws Exception{
        String marker="B1_OLD_REFERENCE_741";
        JSONObject audit=new JSONObject().put("ok",true).put("screen",marker+" visible in a real page").put("modelSummary",marker)
                .put("imageBase64","cGl4ZWxz").put("sourceVersion",7).put("memoryContext",new JSONObject().put("format",1).put("memoryDigest","a".repeat(64)))
                .put("memoryReference",new JSONObject().put("requestId","native-request"));
        JSONObject data=TaskMemoryHistory.observationData(audit);assertFalse(data.has("modelSummary"));assertFalse(data.has("memoryContext"));assertFalse(data.has("memoryReference"));
        assertEquals(audit.getString("screen"),data.getString("screen"));assertEquals("cGl4ZWxz",data.getString("imageBase64"));assertEquals(7,data.getInt("sourceVersion"));
        assertEquals(marker,audit.getString("modelSummary"));assertTrue(audit.has("memoryReference"));
    }
    @Test public void legacyPhoneCheckpointLosesOnlyDerivedEnvelopeFieldsAndNeverOwnerInput()throws Exception{
        String marker="B1_OLD_REFERENCE_741";JSONObject data=new JSONObject().put("ok",true).put("screen",marker+" actually observed")
                .put("modelSummary",marker).put("actualImageAttached",false).put("visionNotice","only native page text")
                .put("memoryContext",new JSONObject().put("format",1).put("memoryDigest","a".repeat(64)));
        String original=TaskMemoryHistory.OBSERVATION_PREFIX+data;
        JSONObject legacy=new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",original)));
        JSONObject ownerText=new JSONObject().put("role","user").put("content",original);
        JSONObject ownerAttachment=new JSONObject(legacy.toString()).put("ownerMessageId","actual-owner-message");
        JSONArray rows=new JSONArray().put(legacy).put(ownerText).put(ownerAttachment);assertEquals(1,TaskMemoryHistory.retire(rows,null,true));assertEquals(3,rows.length());
        String updated=legacy.getJSONArray("content").getJSONObject(0).getString("text");JSONObject nativeData=new JSONObject(updated.substring(TaskMemoryHistory.OBSERVATION_PREFIX.length()));
        assertFalse(nativeData.has("modelSummary"));assertFalse(nativeData.has("memoryContext"));assertEquals(data.getString("screen"),nativeData.getString("screen"));
        assertEquals(original,ownerText.getString("content"));assertEquals(original,ownerAttachment.getJSONArray("content").getJSONObject(0).getString("text"));
        legacy.put("hostObservation",true);assertFalse(TaskMemoryHistory.wireMessages(rows).toString().contains("hostObservation"));assertTrue(legacy.has("hostObservation"));
    }
    @Test public void retiredNativeProviderItemsCannotRestoreOldBodyOrHiddenContinuation()throws Exception{
        for(String provider:new String[]{"openai_responses","anthropic","gemini"}){
            String old="RETIRED_NATIVE_REFERENCE_729";JSONArray raw=new JSONArray();
            if(provider.equals("openai_responses"))raw.put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",old))));
            else if(provider.equals("anthropic"))raw.put(new JSONObject().put("type","text").put("text",old)).put(new JSONObject().put("type","thinking").put("thinking",old).put("signature","opaque-original"));
            else raw.put(new JSONObject().put("text",old)).put(new JSONObject().put("text",old).put("thought",true).put("thoughtSignature","opaque-original"));
            JSONObject row=new JSONObject().put("role","assistant").put("content",old).put("provider_data",new JSONObject().put("provider",provider).put("items",raw));JSONArray history=new JSONArray().put(row);
            TaskMemoryHistory.retire(history,null,true);
            ModelConfig config=new ModelConfig("https://example.invalid","","model",false,8,20,provider,1024);
            assertFalse(provider+" restored a retired reference through native provider_data",ProviderAdapters.selected(provider).request(config,history,null).toString().contains(old));
            assertFalse(row.has("provider_data"));
        }
    }
    @Test public void nativeDependencyAndReceiptFieldsStayInJournalAndOutOfStrictAccountWire()throws Exception{
        JSONObject row=new JSONObject().put("role","assistant").put("content","当前回答").put("memoryContext",new JSONObject().put("format",1).put("memoryDigest","a".repeat(64)))
                .put("memoryReference",new JSONObject().put("taskId","native-original-task")).put("memoryContextRetired",false);
        JSONArray journal=new JSONArray().put(row),wire=TaskMemoryHistory.wireMessages(journal);
        assertTrue(row.has("memoryReference"));assertFalse(wire.toString().contains("memoryReference"));assertFalse(wire.toString().contains("memoryContext"));
        ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","native-test-account").put("accountVersion",1).put("modelId","native-test-model"));
        ModelConfig config=new ModelConfig("https://example.invalid","","model",false,8,20).withAccountModel(ref);
        assertNotNull(dev.pocketengine.runtime.providers.AccountResponsesAdapter.request(config,wire,null));
    }
    @Test public void retiredQueriesAndEffectArgumentsLoseWireBodiesWhileOriginalAuditAndPairingSurvive()throws Exception{
        String marker="OLD_SOURCE_IN_DERIVED_ARGUMENTS_729";JSONArray calls=new JSONArray();
        for(String name:new String[]{"memory_recall","workspace_write"})calls.put(new JSONObject().put("id","original-"+name).put("type","function")
                .put("provider_data",new JSONObject().put("cached",marker)).put("thought_signature",marker)
                .put("function",new JSONObject().put("name",name).put("arguments",new JSONObject().put("query",marker).put("nested",new JSONArray().put(marker)).toString()).put("extra_body",marker)));
        JSONObject assistant=new JSONObject().put("role","assistant").put("content",marker).put("memoryContextRetired",true).put("tool_calls",calls)
                .put("reasoning_content",marker).put("extra_body",marker).put("provider_data",new JSONObject().put("items",new JSONArray().put(marker)));
        JSONObject reference=new JSONObject().put("role","tool").put("tool_call_id","original-memory_recall").put("content","{\"code\":\"reference_expired\"}");
        JSONObject effect=new JSONObject().put("role","tool").put("tool_call_id","original-workspace_write").put("content","{\"operationId\":\"native-original-operation\",\"status\":\"unknown\",\"replayAllowed\":false}");
        JSONArray journal=new JSONArray().put(assistant).put(reference).put(effect);String original=journal.toString();
        JSONArray wire=TaskMemoryHistory.wireMessages(journal);assertFalse(wire.toString().contains(marker));assertEquals(original,journal.toString());
        assertEquals(TaskMemoryHistory.RETIRED,wire.getJSONObject(0).getString("content"));assertEquals(effect.toString(),wire.getJSONObject(2).toString());
        for(int i=0;i<2;i++){JSONObject call=wire.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(i);
            assertEquals(calls.getJSONObject(i).getString("id"),call.getString("id"));assertEquals(wire.getJSONObject(i+1).getString("tool_call_id"),call.getString("id"));
            assertEquals(calls.getJSONObject(i).getJSONObject("function").getString("name"),call.getJSONObject("function").getString("name"));assertEquals("{}",call.getJSONObject("function").getString("arguments"));
        }
        for(String provider:new String[]{"openai_chat","openai_responses","anthropic","gemini"}){
            ModelConfig config=new ModelConfig("https://example.invalid","","model",false,8,20,provider,1024);
            JSONObject body=ProviderAdapters.selected(provider).request(config,wire,null);assertFalse(provider,body.toString().contains(marker));assertTrue(body.toString().contains("native-original-operation"));
        }
        ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","native-test-account").put("accountVersion",1).put("modelId","native-test-model"));
        ModelConfig config=new ModelConfig("https://example.invalid","","model",false,8,20).withAccountModel(ref);
        assertFalse(dev.pocketengine.runtime.providers.AccountResponsesAdapter.request(config,wire,null).toString().contains(marker));
    }
}
