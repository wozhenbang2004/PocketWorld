package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Public pre-existing APIs expose the original quiet/window/budget regressions. */
public final class AutomationPolicyRegressionTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File file(){return new File(folder.getRoot(),"rules.json");}
    private long at(String value){return Instant.parse(value).toEpochMilli();}
    private JSONObject once(long at)throws Exception {
        return new JSONObject().put("title","核对原目标").put("kind","phone_goal").put("goal","打开计算器核对128乘36")
                .put("schedule",new JSONObject().put("type","once").put("at",at));
    }
    private JSONObject quiet()throws Exception {
        return new JSONObject().put("zoneId","Asia/Shanghai").put("quietHours",new JSONObject().put("start","22:00").put("end","07:00"));
    }
    private JSONObject sent()throws Exception{return new JSONObject().put("status","awaiting_user").put("message","系统接受了提醒，等待用户");}

    @Test public void quietOccurrenceDefersToActualZonedEndWithoutChangingScheduledIdentity()throws Exception {
        AutomationBook book=new AutomationBook(file());long due=at("2026-10-30T14:30:00Z"),end=at("2026-10-30T23:00:00Z");
        JSONObject rule=book.save(once(due).put("policy",quiet()),due-1000);
        assertEquals(end,book.nextAt());assertEquals(0,book.claimDue(due,10).length());assertEquals(0,book.receipts().length());
        JSONObject receipt=new AutomationBook(file()).claimDue(end,10).getJSONObject(0);
        assertEquals(due,receipt.getLong("occurrenceAt"));assertEquals(rule.getString("id")+":1:"+due,receipt.getString("instanceKey"));
        assertEquals(0,book.claimDue(end+1,10).length());assertEquals(1,book.receipts().length());
    }
    @Test public void quietBoundaryBetweenClaimAndEffectReusesSameDeferredReceiptAndPauseCancelsIt()throws Exception {
        AutomationBook book=new AutomationBook(file());long due=at("2026-10-30T13:59:59Z"),quiet=due+1000,end=at("2026-10-30T23:00:00Z");
        JSONObject rule=book.save(once(due).put("policy",quiet()),due-1000),receipt=book.claimDue(due,10).getJSONObject(0);AtomicInteger effects=new AtomicInteger();
        JSONObject result=book.dispatchReceipt(receipt.getString("id"),value->{effects.incrementAndGet();return sent();},quiet);
        assertEquals("deferred",result.getString("status"));assertEquals(0,effects.get());assertEquals(end,book.nextAt());
        JSONObject resumed=book.claimDue(end,10).getJSONObject(0);assertEquals(receipt.getString("id"),resumed.getString("id"));
        assertEquals(1,book.rules().getJSONObject(0).getLong("claimedCount"));
        book.setEnabled(rule.getString("id"),false,end+1);
        assertEquals("cancelled",book.dispatchReceipt(resumed.getString("id"),value->{effects.incrementAndGet();return sent();},end+2).getString("status"));
        // A caller cannot turn a cancelled claim back into an authorization source.
        assertEquals("cancelled",book.delivered(resumed.getString("id"),"awaiting_user","错误地尝试复活",end+3).getString("status"));
        assertThrows(IllegalStateException.class,()->book.taskRequest(resumed.getString("id"),end+4));assertEquals(0,effects.get());
    }
    @Test public void expiredAndMissedWindowsAreDurableSilentResultsAndCannotPrepareOldGoal()throws Exception {
        for(String field:new String[]{"expiresAt","maxLatenessMillis"}){
            File target=new File(folder.getRoot(),field+".json");AutomationBook book=new AutomationBook(target);
            JSONObject policy=new JSONObject().put(field,field.equals("expiresAt")?3000:1000);
            book.save(once(2000).put("policy",policy),1000);assertEquals(0,book.claimDue(3000,10).length());
            JSONObject receipt=book.receipts().getJSONObject(0);assertEquals(field.equals("expiresAt")?"expired":"missed_window",receipt.getString("status"));
            assertEquals(-1,new AutomationBook(target).nextAt());assertEquals(0,book.claimDue(4000,10).length());
            assertThrows(IllegalStateException.class,()->book.taskRequest(receipt.getString("id"),4000));
        }
        AutomationBook book=new AutomationBook(file());book.save(once(2000).put("policy",new JSONObject().put("expiresAt",4000)),1000);
        String receipt=book.claimDue(2000,10).getJSONObject(0).getString("id");book.delivered(receipt,"awaiting_user","等待用户",2100);
        assertNotNull(book.taskRequest(receipt,3999));assertThrows(IllegalStateException.class,()->book.taskRequest(receipt,4000));
    }
    @Test public void quietDeferralBeyondExpiryWakesOnlyToRecordExpiry()throws Exception {
        AutomationBook book=new AutomationBook(file());long due=at("2026-10-30T15:00:00Z"),expiry=due+60000;
        book.save(once(due).put("policy",quiet().put("expiresAt",expiry)),due-1000);
        assertEquals(expiry,book.nextAt());assertEquals(0,book.claimDue(expiry,10).length());
        assertEquals("expired",book.receipts().getJSONObject(0).getString("status"));assertEquals(-1,book.nextAt());
    }
    @Test public void durableOccurrenceBudgetIncludesUnknownAndCannotResetByEditingOrToggling()throws Exception {
        AutomationBook book=new AutomationBook(file());long now=at("2026-10-30T00:00:00Z");
        JSONObject rule=book.save(new JSONObject().put("title","每日核对").put("kind","reminder")
                .put("schedule",new JSONObject().put("type","daily").put("time","09:00").put("zone","UTC"))
                .put("policy",new JSONObject().put("maxOccurrences",1)),now);
        JSONObject first=book.claimDue(now+10*3600000,10).getJSONObject(0);book.recoverInterrupted(now+11*3600000);
        assertEquals("delivery_unknown",book.receipts().getJSONObject(0).getString("status"));assertEquals(-1,book.nextAt());
        JSONObject latest=book.rules().getJSONObject(0);latest.put("title","仅改名称").put("enabled",true);
        latest=book.save(latest,now+12*3600000);assertEquals(1,latest.getLong("claimedCount"));assertEquals("budget_exhausted",latest.getString("ruleState"));
        book.setEnabled(rule.getString("id"),false,now+13*3600000);book.setEnabled(rule.getString("id"),true,now+14*3600000);
        assertEquals(-1,new AutomationBook(file()).nextAt());assertEquals(0,book.claimDue(now+48*3600000,10).length());
        assertEquals(first.getString("id"),book.receipts().getJSONObject(0).getString("id"));
    }
    @Test public void missedDailyWindowSkipsOneCoalescedOccurrenceAndKeepsFutureSchedule()throws Exception {
        AutomationBook book=new AutomationBook(file());long now=at("2026-10-01T00:00:00Z");
        book.save(new JSONObject().put("title","每日核对").put("schedule",new JSONObject().put("type","daily").put("time","09:00").put("zone","UTC"))
                .put("policy",new JSONObject().put("maxLatenessMillis",60000)),now);
        long later=at("2026-10-10T12:00:00Z");assertEquals(0,book.claimDue(later,10).length());
        assertEquals("missed_window",book.receipts().getJSONObject(0).getString("status"));assertTrue(book.nextAt()>later);
        assertEquals(0,book.rules().getJSONObject(0).getLong("claimedCount"));
    }
    @Test public void currentQuietPolicyIsRecheckedAtExplicitTaskAdmission()throws Exception {
        AutomationBook book=new AutomationBook(file());long due=at("2026-10-30T13:00:00Z");book.save(once(due).put("policy",quiet()),due-1000);
        String receipt=book.claimDue(due,10).getJSONObject(0).getString("id");book.delivered(receipt,"awaiting_user","待用户开始",due+1);
        assertEquals(1,book.taskRequest(receipt,due+1000).getInt("revision"));
        assertThrows(IllegalStateException.class,()->book.taskRequest(receipt,at("2026-10-30T14:00:00Z")));
        assertNotNull(book.taskRequest(receipt,at("2026-10-30T23:00:00Z")));
    }
    @Test public void invalidPolicyDoesNotMutateSavedRule()throws Exception {
        AutomationBook book=new AutomationBook(file());JSONObject saved=book.save(once(2000),1000);byte[] before=Files.readAllBytes(file().toPath());
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("maxOccurrences",1.5),new JSONObject().put("maxOccurrences",-1),new JSONObject().put("maxLatenessMillis",0),
                new JSONObject().put("zoneId","Missing/Zone"),quiet().put("quietHours",new JSONObject().put("start","22:00").put("end","22:00")),new JSONObject().put("executionAuthorized",true)}){
            assertThrows(RuntimeException.class,()->book.save(new JSONObject(saved.toString()).put("policy",invalid),1500));
            assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        }
    }
}
