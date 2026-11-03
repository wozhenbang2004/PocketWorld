package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationCalendarTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private long at(String value){return Instant.parse(value).toEpochMilli();}
    private JSONObject daily(String time,String zone)throws Exception{return new JSONObject().put("type","daily").put("time",time).put("zone",zone);}
    private JSONObject weekly(String time,String zone,int... days)throws Exception{JSONArray values=new JSONArray();for(int day:days)values.put(day);return daily(time,zone).put("type","weekly").put("daysOfWeek",values);}
    private JSONObject monthly(int day,String shortMonth)throws Exception{return daily("09:00","UTC").put("type","monthly").put("dayOfMonth",day).put("shortMonthPolicy",shortMonth);}
    private JSONObject rule(JSONObject schedule)throws Exception{return new JSONObject().put("title","日历提醒").put("kind","phone_goal").put("goal","由机主核对目标").put("schedule",schedule);}

    @Test public void weeklyUsesIsoDaysAndStrictlyFutureTimesInItsOwnZone()throws Exception {
        JSONObject schedule=weekly("09:00","Asia/Tokyo",5,1,5);JSONObject normalized=AutomationBook.validateSchedule(schedule);assertEquals("[1,5]",normalized.getJSONArray("daysOfWeek").toString());
        long monday=at("2026-11-02T00:00:00Z");assertEquals(monday,AutomationSchedule.nextAt(schedule,at("2026-11-01T23:00:00Z")));assertEquals(at("2026-11-06T00:00:00Z"),AutomationSchedule.nextAt(schedule,monday));
    }
    @Test public void monthlyShortMonthPolicyIsExplicitAndHandlesLeapYears()throws Exception {
        assertEquals(at("2028-03-31T09:00:00Z"),AutomationSchedule.nextAt(monthly(31,"skip"),at("2028-01-31T09:00:00Z")));
        assertEquals(at("2028-02-29T09:00:00Z"),AutomationSchedule.nextAt(monthly(31,"last_day"),at("2028-01-31T09:00:00Z")));
        assertEquals(at("2027-02-28T09:00:00Z"),AutomationSchedule.nextAt(monthly(31,"last_day"),at("2027-01-31T09:00:00Z")));
        assertEquals(at("2028-04-30T09:00:00Z"),AutomationSchedule.nextAt(monthly(31,"last_day"),at("2028-03-31T09:00:00Z")));
    }
    @Test public void springGapPreviewShowsShiftOrSkipAndDoesNotAssumeOneHour()throws Exception {
        JSONObject schedule=weekly("02:30","America/New_York",7);long before=at("2027-03-14T05:00:00Z");JSONObject occurrence=AutomationSchedule.preview(schedule,before,1).getJSONArray("occurrences").getJSONObject(0);
        assertEquals(at("2027-03-14T07:30:00Z"),occurrence.getLong("at"));assertEquals("2027-03-14T02:30",occurrence.getString("requestedLocal"));assertEquals("2027-03-14T03:30",occurrence.getString("local"));assertEquals("gap_shifted_forward",occurrence.getString("dstResolution"));
        assertEquals(at("2027-03-21T06:30:00Z"),AutomationSchedule.nextAt(schedule.put("dstGap","skip"),before));
        JSONObject halfHour=weekly("02:15","Australia/Lord_Howe",7);assertEquals(at("2027-10-02T15:45:00Z"),AutomationSchedule.nextAt(halfHour,at("2027-10-02T13:00:00Z")));
    }
    @Test public void overlapChoosesOneOffsetAndNeverRepeatsTheSameLocalDate()throws Exception {
        long before=at("2027-11-07T04:00:00Z"),first=at("2027-11-07T05:30:00Z"),second=at("2027-11-07T06:30:00Z");JSONObject earlier=weekly("01:30","America/New_York",7),later=new JSONObject(earlier.toString()).put("dstOverlap","later");
        assertEquals(first,AutomationSchedule.nextAt(earlier,before));assertEquals(second,AutomationSchedule.nextAt(later,before));assertEquals(second,AutomationSchedule.nextAt(later,first+1));
        assertEquals(at("2027-11-14T06:30:00Z"),AutomationSchedule.nextAt(earlier,first));assertEquals(at("2027-11-14T06:30:00Z"),AutomationSchedule.nextAt(later,second));
        assertEquals("overlap_later",AutomationSchedule.preview(later,before,1).getJSONArray("occurrences").getJSONObject(0).getString("dstResolution"));
    }
    @Test public void oldDailyShapeAndDstDefaultsArePreserved()throws Exception {
        JSONObject schedule=daily("02:30","America/New_York"),normalized=AutomationBook.validateSchedule(schedule);assertEquals(3,normalized.length());
        assertEquals(at("2027-03-14T07:30:00Z"),AutomationBook.nextDaily(normalized,at("2027-03-14T05:00:00Z")));
        JSONObject fall=daily("01:30","America/New_York");long first=AutomationBook.nextDaily(fall,at("2027-11-07T04:00:00Z"));assertEquals(at("2027-11-08T06:30:00Z"),AutomationBook.nextDaily(fall,first+1));
    }
    @Test public void previewMatchesDurableActualOccurrencesAndRestartWithoutStartingPhoneWork()throws Exception {
        // This fixture verifies independent calendar/notification occurrences, not
        // permission to run phones concurrently. Native task admission remains false.
        File file=new File(temp.getRoot(),"weekly.json");AutomationBook book=new AutomationBook(file);JSONObject schedule=weekly("09:00","UTC",1,5);long start=at("2026-11-01T00:00:00Z");JSONArray preview=AutomationSchedule.preview(schedule,start,3).getJSONArray("occurrences");book.save(rule(schedule).put("policy",new JSONObject().put("overlap","allow")),start);AtomicInteger effects=new AtomicInteger();assertEquals("allow",book.rules().getJSONObject(0).getJSONObject("policy").getString("overlap"));
        for(int i=0;i<preview.length();i++){
            long planned=preview.getJSONObject(i).getLong("at");book=new AutomationBook(file);assertEquals(planned,book.rules().getJSONObject(0).getLong("scheduledAt"));JSONObject receipt=book.claimDue(planned,10).getJSONObject(0);assertEquals(planned,receipt.getLong("occurrenceAt"));
            JSONObject result=book.dispatchReceipt(receipt.getString("id"),r->{effects.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message","机主决定是否执行");},planned);assertEquals("awaiting_user",result.getString("status"));assertFalse(book.taskRequest(receipt.getString("id"),planned).getBoolean("executionAuthorized"));
        }
        assertEquals(3,effects.get());assertEquals(3,book.rules().getJSONObject(0).getLong("claimedCount"));
    }
    @Test public void delayedMonthlySlotDoesNotBurstCatchUpOrMoveOriginalIdentity()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"late.json"));long january=at("2027-01-31T09:00:00Z"),late=at("2027-02-20T09:00:00Z");book.save(rule(monthly(31,"skip")),at("2027-01-01T00:00:00Z"));JSONArray claims=book.claimDue(late,10);assertEquals(1,claims.length());assertEquals(january,claims.getJSONObject(0).getLong("occurrenceAt"));assertEquals(at("2027-03-31T09:00:00Z"),book.nextAt());book.recoverInterrupted(late+1);assertEquals(0,book.claimDue(late+2,10).length());assertEquals("delivery_unknown",book.receipts().getJSONObject(0).getString("status"));
    }
    @Test public void recurringCalendarKeepsQuietExpiryBudgetAndClockRollbackFences()throws Exception {
        AtomicBoolean online=new AtomicBoolean(false);AutomationConditions.Source source=now->new AutomationConditions.Snapshot(now,online.get()?"validated":"disconnected","",true,80,"");File file=new File(temp.getRoot(),"fences.json");AutomationBook book=new AutomationBook(file,null,source);long due=at("2026-11-02T09:00:00Z");
        JSONObject policy=new JSONObject().put("requiresNetwork",true).put("maxOccurrences",1).put("expiresAt",due+60000);JSONObject saved=book.save(rule(weekly("09:00","UTC",1)).put("policy",policy),due-60000);assertEquals(0,book.claimDue(due,10).length());online.set(true);book.recheckConditions(due-30000);assertEquals(due,book.nextAt());assertEquals(0,book.claimDue(due-30000,10).length());
        String id=book.claimDue(due,10).getJSONObject(0).getString("id");book.dispatchReceipt(id,r->new JSONObject().put("status","awaiting_user").put("message","等待机主"),due);assertEquals(1,book.rules().getJSONObject(0).getLong("claimedCount"));assertEquals(-1,book.nextAt());assertThrows(IllegalStateException.class,()->book.taskRequest(id,due+60000));book.setEnabled(saved.getString("id"),false,due+1);assertThrows(IllegalStateException.class,()->book.taskRequest(id,due+2));
        AutomationBook quiet=new AutomationBook(new File(temp.getRoot(),"quiet.json"));quiet.save(rule(weekly("09:00","UTC",1)).put("policy",new JSONObject().put("maxLatenessMillis",30000).put("quietHours",new JSONObject().put("start","08:00").put("end","10:00"))),due-60000);assertEquals(due+30000,quiet.nextAt());assertEquals(0,quiet.claimDue(due+30000,10).length());assertEquals("missed_window",quiet.receipts().getJSONObject(0).getString("status"));assertEquals(0,quiet.rules().getJSONObject(0).getLong("claimedCount"));
    }
    @Test public void invalidCalendarSettingsDoNotMutateBookAndPreviewIsBounded()throws Exception {
        File file=new File(temp.getRoot(),"invalid.json");AutomationBook book=new AutomationBook(file);JSONObject saved=book.save(rule(weekly("09:00","UTC",1)),1000);byte[] before=Files.readAllBytes(file.toPath());
        for(JSONObject bad:new JSONObject[]{weekly("09:00","UTC",0),weekly("09:00","UTC",1).put("daysOfWeek",new JSONArray().put(1.5)),monthly(32,"skip"),monthly(31,"rollover"),daily("24:00","UTC"),daily("09:00","not/a/zone"),weekly("09:00","UTC",1).put("dstOverlap","both"),weekly("09:00","UTC",1).put("unexpected",true)}){
            assertThrows(IllegalArgumentException.class,()->book.save(rule(bad).put("id",saved.getString("id")).put("revision",saved.getInt("revision")),2000));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        }
        assertThrows(IllegalArgumentException.class,()->AutomationSchedule.preview(weekly("09:00","UTC",1),1000,21));assertThrows(IllegalArgumentException.class,()->AutomationSchedule.preview(weekly("09:00","UTC",1),-1,1));
        assertEquals(0,AutomationSchedule.preview(daily("09:00","UTC"),AutomationPolicy.MAX_TIME,20).getJSONArray("occurrences").length());AutomationBook exhausted=new AutomationBook(new File(temp.getRoot(),"exhausted.json"));assertEquals("schedule_exhausted",exhausted.save(rule(daily("09:00","UTC")),AutomationPolicy.MAX_TIME).getString("ruleState"));assertEquals(-1,exhausted.nextAt());
    }
}
