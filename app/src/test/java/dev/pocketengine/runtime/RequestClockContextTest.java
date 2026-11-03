package dev.pocketengine.runtime;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;

public final class RequestClockContextTest {
    @Test public void instantZoneAndDateAreOneSampleAndDstUsesThatExactInstant()throws Exception {
        AtomicInteger samples=new AtomicInteger(),zoneReads=new AtomicInteger();Instant instant=Instant.parse("2026-11-02T16:01:02.123456Z");
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){samples.incrementAndGet();return instant;}};
        JSONObject value=new RequestClockContext(clock,()->{zoneReads.incrementAndGet();return ZoneId.of("Asia/Shanghai");}).capture();
        assertEquals(1,samples.get());assertEquals(1,zoneReads.get());assertEquals("2026-11-02T16:01:02.123Z",value.getString("utcInstant"));assertEquals("2026-11-03",value.getString("localDate"));assertEquals("+08:00",value.getString("utcOffset"));assertEquals("Asia/Shanghai",value.getString("zoneId"));assertEquals("iana_region_or_alias",value.getString("zoneIdKind"));
        JSONObject before=new RequestClockContext(Clock.fixed(Instant.parse("2020-03-29T00:59:00Z"),ZoneOffset.UTC),()->ZoneId.of("Europe/Berlin")).capture();
        JSONObject after=new RequestClockContext(Clock.fixed(Instant.parse("2020-03-29T01:01:00Z"),ZoneOffset.UTC),()->ZoneId.of("Europe/Berlin")).capture();
        assertEquals("+01:00",before.getString("utcOffset"));assertEquals("+02:00",after.getString("utcOffset"));assertTrue(before.getString("localDateTime").contains("01:59"));assertTrue(after.getString("localDateTime").contains("03:01"));
    }
    @Test public void missingZoneOrClockIsExplicitAndNeverUsesServerDefaultOrPrivateErrorText()throws Exception {
        Clock clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);JSONObject missing=new RequestClockContext(clock,()->null).capture();assertEquals("missing",missing.getString("zoneStatus"));assertTrue(missing.isNull("zoneId"));assertTrue(missing.isNull("utcOffset"));assertTrue(missing.isNull("localDate"));assertEquals(0,missing.getLong("epochMillis"));
        JSONObject denied=new RequestClockContext(clock,()->{throw new IllegalStateException("PRIVATE_PATH_AND_TOKEN");}).capture();assertEquals("unavailable",denied.getString("zoneStatus"));assertFalse(denied.toString().contains("PRIVATE"));
        Clock unavailable=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){throw new IllegalStateException("PRIVATE_OS_ERROR");}};
        JSONObject noClock=new RequestClockContext(unavailable,()->{throw new AssertionError("No zone probe after clock unavailable");}).capture();assertEquals("unavailable",noClock.getString("clockStatus"));assertTrue(noClock.isNull("utcInstant"));assertFalse(noClock.toString().contains("PRIVATE"));
    }
    @Test public void eachConstructionRefreshesBoundedFactsWithoutGrantingInputZoneOrExecutionAuthority()throws Exception {
        AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-02T23:59:59Z"));Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        RequestClockContext context=new RequestClockContext(clock,()->ZoneId.of("UTC"));JSONObject before=context.capture();now.set(Instant.parse("2026-11-03T00:00:01Z"));JSONObject after=context.capture();
        assertEquals("2026-11-02",before.getString("localDate"));assertEquals("2026-11-03",after.getString("localDate"));assertFalse(after.getBoolean("externalTimeVerified"));assertFalse(after.getBoolean("executionAuthorized"));assertFalse(after.getBoolean("schedulesChanged"));assertEquals("not_projected",after.getString("inputOriginTimeZones"));
        String wire=context.referenceData();assertTrue(wire.getBytes(StandardCharsets.UTF_8).length<=RequestClockContext.MAX_REFERENCE_BYTES);assertTrue(wire.contains("transport_retries_keep_snapshot"));assertTrue(wire.contains("不是外部校时"));
        org.json.JSONArray raw=new org.json.JSONArray().put(new JSONObject().put("role","system").put("content","original policy")).put(new JSONObject().put("role","user").put("content","original owner goal"));
        JSONObject frame=RecoveryProjection.hostReference(wire);org.json.JSONArray request=new org.json.JSONArray().put(raw.getJSONObject(0)).put(frame).put(raw.getJSONObject(1));
        assertEquals(java.util.List.of(0,-1,1),dev.pocketengine.runtime.context.CompactionPlan.sourceMap(raw,raw,request));assertEquals(2,raw.length());assertFalse(frame.has("ownerMessageId"));assertFalse(frame.has("hostObservation"));
    }
}
