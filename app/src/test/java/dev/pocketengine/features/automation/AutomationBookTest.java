package dev.pocketengine.features.automation;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationBookTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject once(long at)throws Exception{return new JSONObject().put("title","检查计算器").put("kind","phone_goal").put("goal","打开计算器计算128乘36").put("schedule",new JSONObject().put("type","once").put("at",at));}
    private JSONObject daily(String time,String zone)throws Exception{return new JSONObject().put("title","喝水").put("kind","reminder").put("schedule",new JSONObject().put("type","daily").put("time",time).put("zone",zone));}
    @Test public void onceClaimIsDurableAndCannotExecuteOrReplayPhoneTask()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);book.save(once(2000),1000);
        assertEquals(0,book.claimDue(1999,10).length());JSONArray claimed=book.claimDue(2000,10);assertEquals(1,claimed.length());
        assertEquals("dispatching",claimed.getJSONObject(0).getString("status"));assertEquals("phone_goal",claimed.getJSONObject(0).getString("kind"));
        AutomationBook reopened=new AutomationBook(file);assertEquals(0,reopened.claimDue(5000,10).length());assertEquals(-1,reopened.nextAt());
        reopened.recoverInterrupted(6000);assertEquals("delivery_unknown",reopened.receipts().getJSONObject(0).getString("status"));assertEquals(0,reopened.claimDue(7000,10).length());
    }
    @Test public void concurrentReceiversClaimOneOccurrenceOnly()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");AutomationBook one=new AutomationBook(file),two=new AutomationBook(file);one.save(once(2000),1000);
        ExecutorService threads=Executors.newFixedThreadPool(2);try{Future<Integer>a=threads.submit(()->one.claimDue(3000,10).length());Future<Integer>b=threads.submit(()->two.claimDue(3000,10).length());assertEquals(1,a.get(1,TimeUnit.SECONDS)+b.get(1,TimeUnit.SECONDS));}finally{threads.shutdownNow();}
        assertEquals(1,one.receipts().length());
    }
    @Test public void springGapAdvancesToValidTimeAndFallOverlapOccursOncePerDay()throws Exception {
        JSONObject spring=AutomationBook.validateSchedule(daily("02:30","America/New_York").getJSONObject("schedule"));
        long start=Instant.parse("2027-03-14T05:00:00Z").toEpochMilli();assertEquals(Instant.parse("2027-03-14T07:30:00Z").toEpochMilli(),AutomationBook.nextDaily(spring,start));
        JSONObject fall=AutomationBook.validateSchedule(daily("01:30","America/New_York").getJSONObject("schedule"));long first=AutomationBook.nextDaily(fall,Instant.parse("2027-11-07T04:00:00Z").toEpochMilli());
        assertEquals(Instant.parse("2027-11-07T05:30:00Z").toEpochMilli(),first);
        ZonedDateTime following=Instant.ofEpochMilli(AutomationBook.nextDaily(fall,first+1)).atZone(ZoneId.of("America/New_York"));assertEquals(8,following.getDayOfMonth());
    }
    @Test public void missedDailyChecksCoalesceWithoutReplayingEachMissedDay()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));long created=Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();book.save(daily("09:00","Asia/Shanghai"),created);
        long now=Instant.parse("2026-10-10T04:00:00Z").toEpochMilli();assertEquals(1,book.claimDue(now,10).length());assertTrue(book.nextAt()>now);assertEquals(0,book.claimDue(now,10).length());
    }
    @Test public void pauseStopsDueRulesAndCancelsUnsentDailyReceipt()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));JSONObject saved=book.save(daily("09:00","UTC"),Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
        String id=saved.getString("id");JSONArray due=book.claimDue(Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(),10);assertEquals(1,due.length());
        book.setEnabled(id,false,Instant.parse("2026-10-01T10:01:00Z").toEpochMilli());AtomicInteger sent=new AtomicInteger();
        JSONObject outcome=book.dispatchReceipt(due.getJSONObject(0).getString("id"),receipt->{sent.incrementAndGet();return new JSONObject().put("status","notified").put("message","sent");},Instant.parse("2026-10-01T10:02:00Z").toEpochMilli());
        assertEquals(0,sent.get());assertEquals("cancelled",outcome.getString("status"));assertEquals(-1,book.nextAt());assertEquals(0,book.claimDue(Long.MAX_VALUE/2,10).length());
    }
    @Test public void deleteBeforeDeliveryCannotPostNotification()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));JSONObject rule=book.save(once(2000),1000);String receipt=book.claimDue(3000,10).getJSONObject(0).getString("id");book.delete(rule.getString("id"));
        AtomicInteger sent=new AtomicInteger();JSONObject result=book.dispatchReceipt(receipt,value->{sent.incrementAndGet();return new JSONObject().put("status","notified").put("message","sent");},4000);
        assertTrue(result.getBoolean("deleted"));assertEquals(0,sent.get());assertEquals(0,book.receipts().length());
    }
    @Test public void invalidDailyTimeAndStaleEditAreRejectedWithoutMutation()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));try{book.save(daily("25:00","UTC"),1000);fail();}catch(IllegalArgumentException expected){}
        JSONObject rule=book.save(once(2000),1000);JSONObject stale=new JSONObject(rule.toString());book.save(new JSONObject(rule.toString()).put("title","改了标题"),1500);
        try{book.save(stale,1600);fail();}catch(IllegalStateException expected){}assertEquals("改了标题",book.rules().getJSONObject(0).getString("title"));
    }
    @Test public void editingGoalCancelsAlreadyClaimedOldGoalBeforeDispatch()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));JSONObject rule=book.save(once(2000),1000);
        JSONObject claimed=book.claimDue(3000,10).getJSONObject(0);
        book.save(new JSONObject(rule.toString()).put("goal","新目标").put("enabled",true),3500);
        AtomicInteger sent=new AtomicInteger();JSONObject result=book.dispatchReceipt(claimed.getString("id"),receipt->{sent.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message",receipt.getString("goal"));},4000);
        assertEquals(0,sent.get());assertEquals("cancelled",result.getString("status"));
    }
    @Test public void pausingClaimedOneShotCancelsReceiptEvenThoughRuleWasAutoDisabled()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));JSONObject rule=book.save(once(2000),1000);
        JSONObject receipt=book.claimDue(3000,10).getJSONObject(0);book.setEnabled(rule.getString("id"),false,3500);
        AtomicInteger sent=new AtomicInteger();JSONObject result=book.dispatchReceipt(receipt.getString("id"),r->{sent.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message","old goal");},4000);
        assertEquals(0,sent.get());assertEquals("cancelled",result.getString("status"));
    }
    @Test public void hostTaskRequestUsesDurableGoalAndRevokesEditedOrDeletedOccurrence()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));JSONObject rule=book.save(once(2000),1000);String receipt=book.claimDue(3000,10).getJSONObject(0).getString("id");
        assertThrows(IllegalStateException.class,()->book.taskRequest(receipt,3000));book.delivered(receipt,"awaiting_user","等待主人开始",3100);
        JSONObject request=book.taskRequest(receipt,3200);assertEquals(rule.getString("goal"),request.getString("goal"));assertEquals(receipt,request.getJSONObject("origin").getString("occurrenceId"));assertFalse(request.getBoolean("executionAuthorized"));assertTrue(request.getBoolean("requiresHostAdmission"));
        book.save(new JSONObject(rule.toString()).put("goal","已经改变的目标"),3300);assertThrows(IllegalStateException.class,()->book.taskRequest(receipt,3400));
        book.delete(rule.getString("id"));assertThrows(IllegalArgumentException.class,()->book.taskRequest(receipt,3500));
    }
    @Test public void explicitPauseRevokesAlreadyNotifiedOneShotTaskAdmission()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"));JSONObject rule=book.save(once(2000),1000);String receipt=book.claimDue(3000,10).getJSONObject(0).getString("id");book.delivered(receipt,"awaiting_user","已通知",3100);
        assertNotNull(book.taskRequest(receipt,3200));book.setEnabled(rule.getString("id"),false,3300);assertThrows(IllegalStateException.class,()->book.taskRequest(receipt,3400));
        assertEquals("awaiting_user",book.receipts().getJSONObject(0).getString("status"));
    }
}
