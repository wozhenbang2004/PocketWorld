package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerSourceMetadataTest {
    private static final String ID="01234567-89ab-cdef-0123-456789abcdef";
    private static ModelConfig config(){return new ModelConfig("","","fixture",false,5,10);}
    private static JSONArray refs()throws Exception{return new JSONArray().put(new JSONObject().put("generation","original-generation").put("sourceConversationId","original-conversation")
        .put("sourceRef",new JSONObject().put("kind","message").put("sourceId","original-message").put("revision",4_294_967_299L).put("ownerId","owner").put("spaceId","private"))
        .put("bodyFingerprint","sha256:"+"a".repeat(64)).put("offset",2).put("chars",8).put("unit","UTF-16"));}
    private static void append(JSONArray history,JSONObject input)throws Exception{Method m=AgentRunner.class.getDeclaredMethod("appendOwnerInputs",JSONArray.class,JSONArray.class,ModelConfig.class);m.setAccessible(true);m.invoke(null,history,new JSONArray().put(input),config());}
    private static JSONArray durable(JSONArray history)throws Exception{Method m=AgentRunner.class.getDeclaredMethod("durableHistory",JSONArray.class,ModelConfig.class);m.setAccessible(true);return (JSONArray)m.invoke(null,history,config());}
    private static JSONObject input()throws Exception{return new JSONObject().put("messageId",ID).put("mode","queue").put("content","仅此是主人的当前要求");}
    private static JSONObject owner(JSONArray messages)throws Exception{for(int i=0;i<messages.length();i++)if(ID.equals(messages.getJSONObject(i).optString("ownerMessageId")))return messages.getJSONObject(i);throw new AssertionError("native owner row missing");}
    @Test public void appendAndCheckpointRetainOnlyTypedMetadataWithoutChangingOwnerInstruction()throws Exception{
        JSONArray history=new JSONArray(),selected=refs();append(history,input().put("ownerSourceRefs",selected));selected.getJSONObject(0).put("offset",90);
        assertEquals("仅此是主人的当前要求",history.getJSONObject(0).getString("content"));assertEquals(2,history.getJSONObject(0).getJSONArray("ownerSourceRefs").getJSONObject(0).getInt("offset"));
        JSONArray saved=durable(history),restored=durable(saved);assertEquals(saved.toString(),restored.toString());assertEquals(4_294_967_299L,owner(restored).getJSONArray("ownerSourceRefs").getJSONObject(0).getJSONObject("sourceRef").getLong("revision"));
        append(history,input().put("ownerSourceRefs",refs()));assertEquals(1,history.length());
    }
    @Test public void malformedOrNonUserSourceMetadataCannotReachCheckpoint()throws Exception{
        JSONArray invalid=refs();invalid.getJSONObject(0).put("body","old prose");JSONArray history=new JSONArray();assertThrows(InvocationTargetException.class,()->append(history,input().put("ownerSourceRefs",invalid)));assertEquals(0,history.length());
        JSONArray assistant=new JSONArray().put(new JSONObject().put("role","assistant").put("content","not owner").put("ownerSourceRefs",refs()));assertThrows(InvocationTargetException.class,()->durable(assistant));
    }
    @Test public void missingReferenceProjectionIsDetectableAndLegacyQueueRemainsReferenceFree()throws Exception{
        Method m=AgentRunner.class.getDeclaredMethod("hasOwnerContentReferences",JSONArray.class);m.setAccessible(true);JSONArray history=new JSONArray();append(history,input());assertEquals(false,m.invoke(null,history));assertFalse(owner(durable(history)).has("ownerSourceRefs"));
        history.getJSONObject(0).put("ownerSourceRefs",refs());assertEquals(true,m.invoke(null,history));
    }
}
