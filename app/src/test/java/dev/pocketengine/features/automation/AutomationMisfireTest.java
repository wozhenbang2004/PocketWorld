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

public final class AutomationMisfireTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static long at(String text){return Instant.parse(text).toEpochMilli();}
    private static JSONObject rule(String strategy,String overlap,int maximum,boolean phone)throws Exception{
        return new JSONObject().put("title","原日历补处理").put("kind",phone?"phone_goal":"reminder").put("goal",phone?"核对一次原目标":"")
                .put("schedule",new JSONObject().put("type","daily").put("time","09:00").put("zone","UTC"))
                .put("policy",new JSONObject().put("misfire",strategy).put("overlap",overlap).put("maxCatchUp",maximum));
    }
    private File file(){return new File(temp.getRoot(),"misfire.json");}
    @Test public void skipPersistsEachMissedOccurrenceAndOnlyOffersLatest()throws Exception{
        File file=file();AutomationBook book=new AutomationBook(file);book.save(rule("skip","allow",3,false),at("2026-11-01T00:00:00Z"));
        JSONArray offered=book.claimDue(at("2026-11-05T10:00:00Z"),20);assertEquals(1,offered.length());assertEquals(at("2026-11-05T09:00:00Z"),offered.getJSONObject(0).getLong("occurrenceAt"));
        JSONArray receipts=new AutomationBook(file).receipts();assertEquals(5,receipts.length());for(int i=0;i<4;i++)assertEquals("missed_schedule",receipts.getJSONObject(i).getString("status"));
        assertEquals(0,new AutomationBook(file).claimDue(at("2026-11-05T10:00:00Z"),20).length());
    }
    @Test public void coalescingReportsExactOriginalCountAndRange()throws Exception{
        AutomationBook book=new AutomationBook(file());book.save(rule("coalesce","allow",3,false),at("2026-11-01T00:00:00Z"));
        JSONObject latest=book.claimDue(at("2026-11-05T10:00:00Z"),20).getJSONObject(0),merged=latest.getJSONObject("coalescedOccurrences");
        assertEquals(4,merged.getLong("count"));assertEquals(at("2026-11-01T09:00:00Z"),merged.getLong("firstAt"));assertEquals(at("2026-11-04T09:00:00Z"),merged.getLong("lastAt"));
        assertEquals(5,book.receipts().length());assertFalse(latest.has("taskCompleted"));
    }
    @Test public void catchUpKeepsExactCalendarCursorAcrossColdReopen()throws Exception{
        File file=file();AutomationBook book=new AutomationBook(file);book.save(rule("catch_up","allow",2,false),at("2026-11-01T00:00:00Z"));long now=at("2026-11-05T10:00:00Z");
        JSONArray first=book.claimDue(now,20);assertEquals(2,first.length());assertEquals(at("2026-11-01T09:00:00Z"),first.getJSONObject(0).getLong("occurrenceAt"));assertEquals(at("2026-11-03T09:00:00Z"),book.nextAt());
        JSONArray second=new AutomationBook(file).claimDue(now,20);assertEquals(2,second.length());assertEquals(at("2026-11-03T09:00:00Z"),second.getJSONObject(0).getLong("occurrenceAt"));
        AutomationBook third=new AutomationBook(file);assertEquals(1,third.claimDue(now,20).length());assertTrue(third.nextAt()>now);assertEquals(5,third.receipts().length());
    }
    @Test public void boundedHistoryDoesNotClaimTheUncoveredPastComplete()throws Exception{
        File file=file();AutomationBook book=new AutomationBook(file);book.save(rule("skip","allow",3,false),at("2026-01-01T00:00:00Z"));long now=at("2026-11-05T10:00:00Z");
        assertEquals(0,book.claimDue(now,20).length());JSONObject current=book.rules().getJSONObject(0);assertTrue(current.getBoolean("historyWindowTruncated"));assertFalse(current.getBoolean("historyWindowCoveredToNow"));assertTrue(book.nextAt()<now);assertEquals(64,book.receipts().length());
        int rounds=0;while(book.nextAt()<=now&&rounds++<10)book.claimDue(now,20);assertTrue(book.nextAt()>now);assertTrue(book.rules().getJSONObject(0).getBoolean("historyWindowCoveredToNow"));
    }
    @Test public void unknownOldDeliveryBuffersExactlyOneAndNeverReplays()throws Exception{
        File file=file();AutomationBook book=new AutomationBook(file);book.save(rule("catch_up","buffer_one",3,false),at("2026-11-01T00:00:00Z"));long first=at("2026-11-01T10:00:00Z");
        JSONObject receipt=book.claimDue(first,20).getJSONObject(0);book.delivered(receipt.getString("id"),"delivery_unknown","未知，不自动重发",first);
        assertEquals(0,book.claimDue(at("2026-11-03T10:00:00Z"),20).length());assertEquals("overlap_buffered",book.rules().getJSONObject(0).getString("ruleState"));assertEquals(1,book.receipts().length());
        assertEquals(0,new AutomationBook(file).claimDue(at("2026-11-04T10:00:00Z"),20).length());assertEquals(1,book.receipts().length());
    }
    @Test public void overlapSkipRecordsNewOccurrenceWithoutAssumingOldSucceeded()throws Exception{
        AutomationBook book=new AutomationBook(file());book.save(rule("catch_up","skip",3,false),at("2026-11-01T00:00:00Z"));long now=at("2026-11-02T10:00:00Z");
        JSONArray offered=book.claimDue(now,20);assertEquals(1,offered.length());JSONArray rows=book.receipts();assertEquals(2,rows.length());assertEquals("overlap_skipped",rows.getJSONObject(1).getString("status"));assertEquals("dispatching",rows.getJSONObject(0).getString("status"));
    }
    @Test public void exactOriginalGoalCompletionReleasesBufferWithoutRefreshingItsReceipt()throws Exception{
        Map<String,JSONObject> facts=new HashMap<>();File file=file();AutomationBook book=new AutomationBook(file,id->facts.get(id));book.save(rule("catch_up","buffer_one",1,true),at("2026-11-01T00:00:00Z"));
        JSONObject old=book.claimDue(at("2026-11-01T10:00:00Z"),20).getJSONObject(0);book.delivered(old.getString("id"),"awaiting_user","原目标等待",at("2026-11-01T10:01:00Z"));
        long now=at("2026-11-02T10:00:00Z");assertEquals(0,book.claimDue(now,20).length());
        facts.put(old.getString("id"),new JSONObject().put("occurrenceId",old.getString("id")).put("ruleId",old.getString("ruleId")).put("ruleRevision",old.getInt("revision"))
                .put("goal",old.getString("goal")).put("actorId","owner").put("spaceId","private").put("taskId","native-task").put("status","completed").put("resultVerified",true)
                .put("evidenceRef",new JSONObject().put("kind","task_result").put("id","actual-proof")));
        long wake=book.nextAt();assertEquals(now+AutomationConditions.RECHECK_MILLIS,wake);
        assertEquals("succeeded",book.goalState(old.getString("id")).getString("state"));
        JSONArray next=book.claimDue(wake,20);assertEquals(1,next.length());assertEquals(at("2026-11-02T09:00:00Z"),next.getJSONObject(0).getLong("occurrenceAt"));assertTrue(book.nextAt()>now);
        assertNotEquals(old.getString("id"),next.getJSONObject(0).getString("id"));assertEquals(2,book.receipts().length());assertEquals(old.getLong("claimedAt"),book.receipts().getJSONObject(0).getLong("claimedAt"));
    }
    @Test public void expiredPartialHistoryStopsWithoutPretendingItDeliveredPastWork()throws Exception{
        AutomationBook book=new AutomationBook(file());JSONObject input=rule("skip","allow",3,false);input.getJSONObject("policy").put("expiresAt",at("2026-01-02T00:00:00Z"));book.save(input,at("2026-01-01T00:00:00Z"));
        assertEquals(0,book.claimDue(at("2026-11-05T10:00:00Z"),20).length());assertEquals(-1,book.nextAt());assertFalse(book.rules().getJSONObject(0).getBoolean("enabled"));assertEquals("expired",book.receipts().getJSONObject(0).getString("status"));
    }
}
