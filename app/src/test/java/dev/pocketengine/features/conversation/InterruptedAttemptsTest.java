package dev.pocketengine.features.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InterruptedAttemptsTest {
    private JSONObject event(String run,String call,int offset,String text)throws Exception{return new JSONObject().put("type","model_attempt_interrupted").put("runId",run).put("coordinatorEpoch",1).put("receipt",new JSONObject().put("callId",call).put("offset",offset).put("text",text).put("displayPrefixLimit",32768).put("provisional",true).put("attemptCompleted",false));}
    @Test public void interruptedRetryAndStopKeepExactOrderedPrefixesWithoutInventingTail()throws Exception{
        JSONArray events=new JSONArray().put(event("first","429",3,"DEF")).put(event("first","429",0,"abc")).put(event("second","stop",0,"已看到"));
        String original=events.toString();var projected=InterruptedAttempts.fromEvents(events,false);assertEquals(2,projected.size());assertEquals("abcDEF",projected.get(0).text);assertTrue(projected.get(0).contiguous);assertEquals(6,projected.get(0).retainedChars);assertEquals("second",projected.get(1).runId);
        assertEquals(original,events.toString());
    }
    @Test public void missingChunksConflictsAndHistoricalUnknownIdentityStayExplicit()throws Exception{
        JSONArray events=new JSONArray().put(event("r","c",4,"tail")).put(event("r","c",4,"other"));
        var attempt=InterruptedAttempts.fromEvents(events,true).get(0);assertFalse(attempt.contiguous);assertTrue(attempt.conflicting);assertTrue(attempt.windowPartial);assertTrue(attempt.text.contains("0–4"));assertFalse(attempt.text.contains("other"));
        JSONObject old=event("","",0,"旧片段");assertEquals("",InterruptedAttempts.fromEvents(new JSONArray().put(old),false).get(0).runId);
    }
}
