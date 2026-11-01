package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import java.time.Instant;
import org.json.JSONObject;
import org.junit.Test;

public final class SemanticCalendarRequestTest {
    static JSONObject draft() throws Exception {
        return new JSONObject().put("kind","calendar_draft").put("title","门诊复查")
                .put("start",Instant.parse("2026-11-01T01:30:00Z").toEpochMilli())
                .put("end",Instant.parse("2026-11-01T02:30:00Z").toEpochMilli()).put("timeZone","Asia/Shanghai");
    }
    @Test public void calendarUsesFixedCollectionAndDefaultsWithoutReadingDeviceZone() throws Exception {
        SemanticRequest request=SemanticRequest.parse(draft());
        assertEquals("android.intent.action.INSERT",request.action);assertEquals("content://com.android.calendar/events",request.data);
        assertEquals("Asia/Shanghai",request.parameters().getString("timeZone"));assertEquals("",request.parameters().getString("location"));
        assertEquals("",request.parameters().getString("description"));assertEquals("owner_completes_in_target_app",request.followup());
    }
    @Test public void utcInstantsRemainExactAcrossDstAndRequestedZoneChange() throws Exception {
        JSONObject d=draft().put("start",Instant.parse("2026-11-01T05:30:00Z").toEpochMilli())
                .put("end",Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()).put("timeZone","America/New_York");
        SemanticRequest first=SemanticRequest.parse(d),second=SemanticRequest.parse(new JSONObject(d.toString()).put("timeZone","UTC"));
        assertEquals(3_600_000L,first.parameters().getLong("end")-first.parameters().getLong("start"));
        assertEquals(first.parameters().getLong("start"),second.parameters().getLong("start"));assertNotEquals(first.digest(),second.digest());
    }
    @Test public void missingTimesAndZoneNeverDefaultToNowOrLocalTimezone() throws Exception {
        for(String key:new String[]{"title","start","end","timeZone"}) {
            JSONObject d=draft();d.remove(key);assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(d));
        }
        for(String zone:new String[]{"","PST","Asia/Imaginary","+08:00"," Asia/Shanghai","UTC\n"})
            assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put("timeZone",zone)));
    }
    @Test public void refusesFractionStringOverflowAndReversedTimes() throws Exception {
        for(Object time:new Object[]{"1793496600000",true,1.5,-1L,253402300800000L,Long.MAX_VALUE,JSONObject.NULL})
            assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put("start",time)));
        JSONObject d=draft();long start=d.getLong("start");
        assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put("end",start)));
        assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put("end",start-1)));
    }
    @Test public void arbitraryUriIdentityInvitationAndCalendarSelectionCannotEnter() throws Exception {
        for(String key:new String[]{"data","uri","component","extras","calendarId","attendees","rrule","allDay","grant","_hostOperationId"})
            assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put(key,"injected")));
    }
    @Test public void boundedTextKeepsNewlineOnlyInDescription() throws Exception {
        assertEquals("一楼\n请携带资料",SemanticRequest.parse(draft().put("description","一楼\n请携带资料")).parameters().getString("description"));
        assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put("title","标题\n其他")));
        assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put("location","一楼\t其他")));
        for(String key:new String[]{"title","location","description"}) {
            int length=key.equals("title")?201:key.equals("location")?501:4001;
            assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(draft().put(key,"x".repeat(length))));
        }
    }
    @Test public void immutableParametersAndDefaultNormalizationBindExactReview() throws Exception {
        JSONObject d=draft();SemanticRequest first=SemanticRequest.parse(d);
        assertEquals(first.digest(),SemanticRequest.parse(draft().put("location","").put("description","")).digest());
        d.put("title","changed");first.parameters().put("title","changed");assertEquals("门诊复查",first.parameters().getString("title"));
        assertNotEquals(first.digest(),SemanticRequest.parse(draft().put("description","new details")).digest());
    }
}
