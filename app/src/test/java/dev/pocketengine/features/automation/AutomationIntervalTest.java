package dev.pocketengine.features.automation;

import java.io.File;
import java.time.Instant;
import java.util.TimeZone;
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
}
