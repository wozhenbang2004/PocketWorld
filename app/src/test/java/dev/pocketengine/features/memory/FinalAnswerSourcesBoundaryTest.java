package dev.pocketengine.features.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Boundary shape only; these checks grant no read permission or original-source proof. */
public final class FinalAnswerSourcesBoundaryTest {
    private static JSONObject frame(String text)throws Exception{return new JSONObject().put("role","user").put("hostObservation",true).put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));}
    private static JSONArray history(JSONObject tail)throws Exception{return new JSONArray().put(new JSONObject().put("role","assistant").put("content","original final answer")).put(tail);}
    @Test public void exactNativeObservationTailUsesTheActualHistoryLength()throws Exception{
        JSONArray messages=history(frame(TaskMemoryHistory.OBSERVATION_PREFIX+new JSONObject().put("ok",true).put("text","original final observation")));
        assertTrue(FinalAnswerSources.nativeFinalBoundaryCurrent(messages,0,2));
        assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(messages,0,1));
        assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(messages,0,3));
        assertTrue(FinalAnswerSources.nativeFinalBoundaryCurrent(new JSONArray().put(messages.getJSONObject(0)),0,1));
    }
    @Test public void newOwnerInputsAndEffectMessagesNeverBecomeNativeObservationTails()throws Exception{
        JSONObject original=frame(TaskMemoryHistory.OBSERVATION_PREFIX+"{}");
        for(String field:new String[]{"ownerMessageId","ownerRecallFeedback","ownerSourceRefs","ownerInputMode"}){
            JSONObject changed=new JSONObject(original.toString()).put(field,"not a host observation");
            assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(history(changed),0,2));
        }
        for(String role:new String[]{"assistant","tool","system"})assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(history(new JSONObject(original.toString()).put("role",role)),0,2));
        assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(history(new JSONObject().put("role","user").put("content","new owner instruction")),0,2));
    }
    @Test public void malformedOrUnknownObservationEnvelopesFailClosed()throws Exception{
        for(String text:new String[]{"unknown tail",TaskMemoryHistory.OBSERVATION_PREFIX+"[]",TaskMemoryHistory.OBSERVATION_PREFIX+"{} trailing",TaskMemoryHistory.OBSERVATION_PREFIX+"{"})
            assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(history(frame(text)),0,2));
        JSONObject missing=frame(TaskMemoryHistory.OBSERVATION_PREFIX+"{}");missing.remove("hostObservation");
        assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(history(missing),0,2));
        assertFalse(FinalAnswerSources.nativeFinalBoundaryCurrent(history(frame(TaskMemoryHistory.OBSERVATION_PREFIX+"{}")),2,2));
    }
}
