package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationIntervalTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject interval(long anchor,long every)throws Exception{return new JSONObject().put("type","interval").put("anchorAt",anchor).put("intervalMillis",every);}
    @Test public void fixedAnchorSurvivesTimezoneChangeAndDoesNotDriftAtALateCheck()throws Exception{
        long anchor=Instant.parse("2027-03-14T06:50:00Z").toEpochMilli(),every=40*60000L;JSONObject schedule=interval(anchor,every);TimeZone previous=TimeZone.getDefault();
        try{TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));assertEquals(anchor,AutomationSchedule.nextAt(schedule,anchor-1));assertEquals(anchor+every,AutomationSchedule.nextAt(schedule,anchor));TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));assertEquals(anchor+4*every,AutomationSchedule.nextAt(schedule,anchor+3*every+12000));}
        finally{TimeZone.setDefault(previous);}
    }
    @Test public void actualClaimAndRestartKeepOneOriginalIntervalOccurrence()throws Exception{
        File file=new File(temp.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);long anchor=100000;
        book.save(new JSONObject().put("title","每40分钟").put("schedule",interval(anchor,2400000)),anchor-1);
        assertEquals(anchor,book.nextAt());String id=book.claimDue(anchor,10).getJSONObject(0).getString("id");
        AutomationBook restored=new AutomationBook(file);restored.recoverInterrupted(anchor+1);assertEquals(0,restored.claimDue(anchor+2,10).length());assertEquals("delivery_unknown",restored.receipts().getJSONObject(0).getString("status"));assertEquals(id,restored.receipts().getJSONObject(0).getString("id"));assertEquals(anchor+2400000,restored.nextAt());
        assertEquals("inexact_best_effort",AutomationSchedule.preview(interval(anchor,2400000),anchor-1,2).getString("timing"));
    }
    @Test public void exhaustedIntervalsNeverOverflowOrProduceDuplicateTimes()throws Exception{
        long max=AutomationPolicy.MAX_TIME;JSONObject schedule=interval(max-60000,60000);assertEquals(max,AutomationSchedule.nextAt(schedule,max-1));assertEquals(-1,AutomationSchedule.nextAt(schedule,max));assertEquals(2,AutomationSchedule.preview(schedule,max-60001,20).getJSONArray("occurrences").length());
    }
    @Test public void malformedIntervalsFailBeforeAnyRuleIsSaved()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"bad.json"));
        for(JSONObject bad:new JSONObject[]{interval(0,60000),interval(1000,59999),interval(1000,366L*86400000+1),interval(1000,60000).put("zone","UTC"),interval(1000,60000).put("intervalMillis",60000.5)}){
            assertThrows(IllegalArgumentException.class,()->book.save(new JSONObject().put("title","无效间隔").put("schedule",bad),500));
        }assertEquals(0,book.rules().length());
    }
    private static long at(String time){return Instant.parse(time).toEpochMilli();}
    private JSONObject cron(String expression,String zone)throws Exception{return new JSONObject().put("type","cron").put("expression",expression).put("zone",zone).put("scheduleVersion",1);}
    private JSONObject rule(JSONObject schedule)throws Exception{return new JSONObject().put("title","原有界日历").put("schedule",schedule);}

    @Test public void cronSubsetNormalizesAndReturnsStrictMinuteSlotsInTheSelectedZone()throws Exception {
        JSONObject schedule=cron("*/15 9-10 * * 1-5","Asia/Shanghai"),normalized=AutomationSchedule.normalize(schedule);
        assertEquals("0,15,30,45 9,10 * * 1,2,3,4,5",normalized.getString("expression"));assertEquals(normalized.toString(),AutomationSchedule.normalize(normalized).toString());assertEquals(1,normalized.getInt("scheduleVersion"));
        long first=at("2027-01-04T01:00:00Z");JSONArray next=AutomationSchedule.preview(schedule,first-1,3).getJSONArray("occurrences");assertEquals(first,next.getJSONObject(0).getLong("at"));assertEquals(first+900000L,next.getJSONObject(1).getLong("at"));assertEquals(first+1800000L,next.getJSONObject(2).getLong("at"));assertEquals(first+900000L,AutomationSchedule.nextAt(schedule,first));
        assertEquals(first+60000L,AutomationSchedule.nextAt(cron("* * * * *","UTC"),first));assertEquals(at("2027-01-10T09:00:00Z"),AutomationSchedule.nextAt(cron("0 9 * * 0","UTC"),first));
    }
    @Test public void invalidCronAndUnknownVersionCannotWriteAnOriginalRule()throws Exception {
        File file=new File(temp.getRoot(),"invalid-cron.json");AutomationBook book=new AutomationBook(file);
        for(String bad:new String[]{"0 0 9 * * *","@daily","0 9 31 2 *","0 9 1 * 1","0 9 * * 7","0 9 * * MON","0 9 ? * *","*/0 * * * *","*/61 * * * *","5-1 * * * *","1/5 * * * *","0,,15 * * * *","0 9 L * *","0 9 * * 1#2","0 9 * 13 *"})assertThrows(bad,IllegalArgumentException.class,()->book.save(rule(cron(bad,"UTC")),1000));
        for(Object version:new Object[]{0,2,"1",1.5,JSONObject.NULL})assertThrows(IllegalArgumentException.class,()->book.save(rule(cron("0 9 * * *","UTC").put("scheduleVersion",version)),1000));
        assertThrows(IllegalArgumentException.class,()->book.save(rule(cron("0 9 * * *","invalid/zone")),1000));assertThrows(IllegalArgumentException.class,()->book.save(rule(cron("0 9 * * *","UTC").put("command","echo no")),1000));assertEquals(0,book.rules().length());assertFalse(file.exists());
    }
    @Test public void cronGapResolutionOrdersInstantsAndDeduplicatesCollidingWallTimes()throws Exception {
        JSONObject schedule=cron("0,30 2-3 * * *","America/New_York");long before=at("2027-03-14T06:50:00Z");JSONArray values=AutomationSchedule.preview(schedule,before,3).getJSONArray("occurrences");
        assertEquals(at("2027-03-14T07:00:00Z"),values.getJSONObject(0).getLong("at"));assertEquals(at("2027-03-14T07:30:00Z"),values.getJSONObject(1).getLong("at"));assertEquals(at("2027-03-15T06:00:00Z"),values.getJSONObject(2).getLong("at"));assertEquals("gap_shifted_forward",values.getJSONObject(0).getString("dstResolution"));assertEquals("2027-03-14T02:00",values.getJSONObject(0).getString("requestedLocal"));
        assertEquals(at("2027-03-15T06:30:00Z"),AutomationSchedule.nextAt(cron("30 2 * * *","America/New_York").put("dstGap","skip"),before));
        assertEquals(at("2027-10-02T15:45:00Z"),AutomationSchedule.nextAt(cron("15 2 * * 0","Australia/Lord_Howe"),at("2027-10-02T13:00:00Z")));
        JSONObject skippedDate=AutomationSchedule.preview(cron("0 9 * * 5","Pacific/Apia"),at("2011-12-30T18:00:00Z"),1).getJSONArray("occurrences").getJSONObject(0);assertEquals("2011-12-30T09:00",skippedDate.getString("requestedLocal"));assertEquals("2011-12-31T09:00",skippedDate.getString("local"));assertEquals(at("2011-12-30T19:00:00Z"),skippedDate.getLong("at"));
    }
    @Test public void cronOverlapSelectsOneOffsetAndLeapCenturyStaysWithinTheDeclaredHorizon()throws Exception {
        JSONObject earlier=cron("30 1 * * *","America/New_York"),later=new JSONObject(earlier.toString()).put("dstOverlap","later");long before=at("2027-11-07T04:00:00Z"),first=at("2027-11-07T05:30:00Z"),second=at("2027-11-07T06:30:00Z");
        assertEquals(first,AutomationSchedule.nextAt(earlier,before));assertEquals(second,AutomationSchedule.nextAt(later,before));assertEquals(at("2027-11-08T06:30:00Z"),AutomationSchedule.nextAt(earlier,first));assertEquals(at("2027-11-08T06:30:00Z"),AutomationSchedule.nextAt(later,second));
        assertEquals(at("2104-02-29T09:00:00Z"),AutomationSchedule.nextAt(cron("0 9 29 2 *","UTC"),at("2096-02-29T09:00:00Z")));
        assertEquals(at("9999-12-31T19:00:00Z"),AutomationSchedule.nextAt(cron("0 9 * * *","Pacific/Kiritimati"),at("9999-12-31T12:00:00Z")));
        assertEquals(-1,AutomationSchedule.nextAt(cron("* * * * *","UTC"),AutomationPolicy.MAX_TIME));AutomationBook book=new AutomationBook(new File(temp.getRoot(),"exhausted-cron.json"));assertThrows(IllegalArgumentException.class,()->book.save(rule(cron("* * * * *","UTC")),AutomationPolicy.MAX_TIME));assertEquals(0,book.rules().length());
    }
    @Test public void cronPreviewActualBookAndDuplicateAlarmsKeepThreeOriginalOccurrences()throws Exception {
        File file=new File(temp.getRoot(),"cron-occurrences.json");JSONObject schedule=cron("*/15 9 * * *","UTC");long before=at("2027-01-04T08:59:00Z");JSONArray preview=AutomationSchedule.preview(schedule,before,3).getJSONArray("occurrences");AutomationBook book=new AutomationBook(file);JSONObject saved=book.save(rule(schedule),before);AtomicInteger effects=new AtomicInteger();
        for(int i=0;i<3;i++){
            long planned=preview.getJSONObject(i).getLong("at"),late=planned+12000;AutomationBook reopened=new AutomationBook(file);assertEquals(planned,reopened.nextAt());JSONObject receipt=reopened.claimDue(late,10).getJSONObject(0);assertEquals(planned,receipt.getLong("occurrenceAt"));assertEquals(saved.getString("id")+":1:"+planned,receipt.getString("instanceKey"));assertEquals(0,new AutomationBook(file).claimDue(late,10).length());
            reopened.dispatchReceipt(receipt.getString("id"),r->{effects.incrementAndGet();return new JSONObject().put("status","notified").put("message","原提醒已提交系统");},late);reopened.dispatchReceipt(receipt.getString("id"),r->{fail("duplicate callback");return null;},late+1);
        }
        assertEquals(3,effects.get());assertEquals(3,book.rules().getJSONObject(0).getLong("claimedCount"));assertEquals(at("2027-01-04T09:45:00Z"),book.nextAt());
    }
    @Test public void cronRestartUnknownAndEditKeepTheOriginalBudgetAndRejectOldCallbacks()throws Exception {
        File file=new File(temp.getRoot(),"cron-unknown-edit.json");AutomationBook book=new AutomationBook(file);long due=at("2027-01-04T09:00:00Z");JSONObject initial=rule(cron("*/15 9 * * *","UTC")).put("kind","phone_goal").put("goal","主人核对后才执行").put("policy",new JSONObject().put("maxOccurrences",1));JSONObject saved=book.save(initial,due-1000),claim=book.claimDue(due,10).getJSONObject(0);
        AutomationBook cold=new AutomationBook(file);cold.recoverInterrupted(due+1);assertEquals("delivery_unknown",cold.receipts().getJSONObject(0).getString("status"));assertEquals(claim.getString("id"),cold.receipts().getJSONObject(0).getString("id"));assertEquals(0,cold.claimDue(due+60000,10).length());
        JSONObject changed=cold.save(new JSONObject(saved.toString()).put("title","只更新文字"),due+60000);assertEquals(2,changed.getInt("revision"));assertEquals(1,changed.getLong("claimedCount"));assertEquals("budget_exhausted",changed.getString("ruleState"));assertEquals("delivery_unknown",cold.dispatchReceipt(claim.getString("id"),r->{fail("unknown cannot replay");return null;},due+60001).getString("status"));assertThrows(IllegalStateException.class,()->cold.taskRequest(claim.getString("id"),due+60001));
        byte[] before=Files.readAllBytes(file.toPath());assertThrows(IllegalStateException.class,()->cold.save(saved,due+60002));assertThrows(IllegalStateException.class,()->cold.setEnabled(saved.getString("id"),false,due+60002,1));assertThrows(IllegalStateException.class,()->cold.delete(saved.getString("id"),1));assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(0,cold.claimDue(due+86400000L,10).length());
    }
    @Test public void cronRevisionCancelsUnsentOldOccurrenceAndOnlyArmsStrictlyFutureTime()throws Exception {
        File file=new File(temp.getRoot(),"cron-new-revision.json");AutomationBook book=new AutomationBook(file);long due=at("2027-01-04T09:00:00Z");JSONObject saved=book.save(rule(cron("*/15 9 * * *","UTC")),due-1000),old=book.claimDue(due,10).getJSONObject(0);
        JSONObject edited=book.save(new JSONObject(saved.toString()).put("schedule",cron("*/20 9 * * *","UTC")),due+120000);assertEquals(at("2027-01-04T09:20:00Z"),edited.getLong("scheduledAt"));assertEquals(1,edited.getLong("claimedCount"));assertEquals("cancelled",book.dispatchReceipt(old.getString("id"),r->{fail("old revision cannot deliver");return null;},due+120001).getString("status"));
        JSONObject pending=book.claimDue(edited.getLong("scheduledAt"),10).getJSONObject(0);assertEquals(2,pending.getInt("revision"));book.setEnabled(edited.getString("id"),false,pending.getLong("occurrenceAt")+1,2);assertEquals("cancelled",book.dispatchReceipt(pending.getString("id"),r->{fail("pause cannot deliver");return null;},pending.getLong("occurrenceAt")+2).getString("status"));assertEquals(-1,book.nextAt());
    }
    @Test public void cronUsesOriginalCoalesceAndBoundedCatchUpWithoutMovingNominalTimes()throws Exception {
        long before=at("2027-01-04T08:59:00Z"),late=at("2027-01-04T09:46:00Z");JSONObject schedule=cron("*/15 9 * * *","UTC");AutomationBook coalesced=new AutomationBook(new File(temp.getRoot(),"cron-coalesce.json"));coalesced.save(rule(schedule).put("policy",new JSONObject().put("misfire","coalesce").put("overlap","allow")),before);
        JSONObject last=coalesced.claimDue(late,20).getJSONObject(0);assertEquals(at("2027-01-04T09:45:00Z"),last.getLong("occurrenceAt"));assertEquals(3,last.getJSONObject("coalescedOccurrences").getLong("count"));assertEquals(4,coalesced.receipts().length());assertEquals(1,coalesced.rules().getJSONObject(0).getLong("claimedCount"));
        AutomationBook catchup=new AutomationBook(new File(temp.getRoot(),"cron-catchup.json"));catchup.save(rule(schedule).put("policy",new JSONObject().put("misfire","catch_up").put("overlap","allow").put("maxCatchUp",2)),before);JSONArray first=catchup.claimDue(late,20);assertEquals(2,first.length());assertEquals(at("2027-01-04T09:00:00Z"),first.getJSONObject(0).getLong("occurrenceAt"));assertEquals(at("2027-01-04T09:30:00Z"),catchup.nextAt());JSONArray rest=catchup.claimDue(late,20);assertEquals(2,rest.length());assertEquals(at("2027-01-04T09:30:00Z"),rest.getJSONObject(0).getLong("occurrenceAt"));assertEquals(0,catchup.claimDue(late,20).length());
    }
    @Test public void unknownStoredScheduleVersionCannotDispatchButStillCanBePaused()throws Exception {
        File file=new File(temp.getRoot(),"future-version.json");AutomationBook book=new AutomationBook(file);long due=at("2027-01-04T09:00:00Z");JSONObject saved=book.save(rule(cron("*/15 9 * * *","UTC")),due-1000),receipt=book.claimDue(due,10).getJSONObject(0);JSONObject stored=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));stored.getJSONArray("rules").getJSONObject(0).getJSONObject("schedule").put("scheduleVersion",2);Files.write(file.toPath(),stored.toString().getBytes(StandardCharsets.UTF_8));byte[] before=Files.readAllBytes(file.toPath());
        assertThrows(IllegalArgumentException.class,()->book.dispatchReceipt(receipt.getString("id"),r->{fail("unknown schema cannot execute");return null;},due+1));assertThrows(IllegalArgumentException.class,()->book.save(saved,due+1));assertArrayEquals(before,Files.readAllBytes(file.toPath()));book.recoverInterrupted(due+2);assertEquals("delivery_unknown",book.receipts().getJSONObject(0).getString("status"));book.setEnabled(saved.getString("id"),false,due+3,1);assertEquals(-1,book.nextAt());assertEquals("delivery_unknown",book.receipts().getJSONObject(0).getString("status"));assertThrows(IllegalArgumentException.class,()->book.setEnabled(saved.getString("id"),true,due+4,2));
    }
}
