package dev.pocketengine.features.automation;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original file/claim/delivery paths, with explicit native goal facts. No notification or task is run. */
public final class AutomationOverlapWindowTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static final long FIRST=Instant.parse("2026-01-01T09:00:00Z").toEpochMilli();
    private static final long DAY=86_400_000L;
    private static JSONObject rule(String name,String overlap)throws Exception{
        return new JSONObject().put("title",name).put("kind","phone_goal").put("goal","原固定目标："+name)
                .put("schedule",new JSONObject().put("type","daily").put("time","09:00").put("zone","UTC"))
                .put("policy",new JSONObject().put("misfire","skip").put("overlap",overlap));
    }
    private static JSONObject verified(JSONObject receipt)throws Exception{
        return new JSONObject().put("occurrenceId",receipt.getString("id")).put("ruleId",receipt.getString("ruleId"))
                .put("ruleRevision",receipt.getInt("revision")).put("goal",receipt.getString("goal"))
                .put("actorId","owner").put("spaceId","private").put("taskId","native-task-"+receipt.getString("id"))
                .put("status","completed").put("resultVerified",true)
                .put("evidenceRef",new JSONObject().put("kind","task_result").put("id","original-proof-"+receipt.getString("id")));
    }
    private static void settle(AutomationBook book,JSONObject receipt,Map<String,JSONObject> facts,long now)throws Exception{
        book.delivered(receipt.getString("id"),"awaiting_user","原通知已交主人；任务完成由独立原生事实核对",now);
        facts.put(receipt.getString("id"),verified(receipt));
    }
    @Test public void oneHundredOneSuccessfulOriginalGoalsDoNotPermanentlyBufferTheNextOccurrence()throws Exception{
        Map<String,JSONObject> facts=new HashMap<>();File file=new File(temp.getRoot(),"same-rule.json");
        AutomationBook book=new AutomationBook(file,id->facts.get(id));book.save(rule("连续原目标","buffer_one"),FIRST-3_600_000L);
        for(int day=0;day<101;day++){
            JSONArray offered=book.claimDue(FIRST+day*DAY,20);assertEquals("原第"+(day+1)+"个日历实例",1,offered.length());
            settle(book,offered.getJSONObject(0),facts,FIRST+day*DAY);
        }
        assertEquals(101,book.receipts().length());
        AutomationBook reopened=new AutomationBook(file,id->facts.get(id));
        JSONArray next=reopened.claimDue(FIRST+101*DAY,20);assertEquals("101份旧完成proof必须都核到，不能永久buffer",1,next.length());
        assertEquals(FIRST+101*DAY,next.getJSONObject(0).getLong("occurrenceAt"));
        assertEquals(102,reopened.receipts().length());assertEquals(101,facts.size());
    }
    @Test public void anotherRulesHistoryCannotEvictTheDueRulesOriginalCompletionProofs()throws Exception{
        Map<String,JSONObject> facts=new HashMap<>();AutomationBook book=new AutomationBook(new File(temp.getRoot(),"two-rules.json"),id->facts.get(id));
        JSONObject buffered=book.save(rule("需防重叠的规则","buffer_one"),FIRST-3_600_000L);
        JSONObject independent=book.save(rule("另一条明确允许并行规则","allow"),FIRST-3_600_000L);
        for(int day=0;day<52;day++){
            JSONArray offered=book.claimDue(FIRST+day*DAY,20);assertEquals("异规则历史不得挤掉当前规则proof：day="+day,2,offered.length());
            assertEquals(buffered.getString("id"),offered.getJSONObject(0).getString("ruleId"));
            assertEquals(independent.getString("id"),offered.getJSONObject(1).getString("ruleId"));
            for(int i=0;i<offered.length();i++)settle(book,offered.getJSONObject(i),facts,FIRST+day*DAY);
        }
        assertEquals(104,book.receipts().length());assertEquals(104,facts.size());
    }
    @Test public void missingFailedAndMismatchedOldProofStillFailClosedOutsideTheOldHundredWindow()throws Exception{
        Map<String,JSONObject> facts=new HashMap<>();AutomationBook book=new AutomationBook(new File(temp.getRoot(),"mixed.json"),id->facts.get(id));
        book.save(rule("旧未知仍阻塞","buffer_one"),FIRST-3_600_000L);JSONObject first=null;
        for(int day=0;day<101;day++){
            JSONArray offered=book.claimDue(FIRST+day*DAY,20);assertEquals(1,offered.length());
            JSONObject receipt=offered.getJSONObject(0);if(first==null)first=new JSONObject(receipt.toString());
            settle(book,receipt,facts,FIRST+day*DAY);
        }
        String firstId=first.getString("id");JSONObject original=facts.remove(firstId);long now=FIRST+101*DAY;
        assertEquals(0,book.claimDue(now,20).length());assertEquals(101,book.receipts().length());
        facts.put(firstId,new JSONObject(original.toString()).put("status","failed"));
        now=book.nextAt();assertEquals(0,book.claimDue(now,20).length());assertEquals(101,book.receipts().length());
        facts.put(firstId,new JSONObject(original.toString()).put("ruleId","foreign-rule"));
        now=book.nextAt();assertEquals(0,book.claimDue(now,20).length());assertEquals(101,book.receipts().length());
        facts.put(firstId,original);now=book.nextAt();assertEquals(1,book.claimDue(now,20).length());
        assertEquals(102,book.receipts().length());assertEquals(101,facts.size());
    }
}
