package dev.pocketengine.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Request-only OS facts. Not input-origin provenance, external time verification,
 * a scheduler, authorization or another persisted message history. */
final class RequestClockContext {
    static final int MAX_REFERENCE_BYTES=2048;
    private final Clock clock;private final Supplier<ZoneId> zones;
    RequestClockContext(Clock clock,Supplier<ZoneId> zones){this.clock=Objects.requireNonNull(clock);this.zones=Objects.requireNonNull(zones);}
    static RequestClockContext system(){return new RequestClockContext(Clock.systemUTC(),ZoneId::systemDefault);}
    JSONObject capture()throws Exception {
        JSONObject result=new JSONObject().put("format","pocketworld.request-clock.v1").put("source","native_host_os_clock")
                .put("externalTimeVerified",false).put("inputOriginTimeZones","not_projected")
                .put("sampling","logical_request_construction;transport_retries_keep_snapshot")
                .put("executionAuthorized",false).put("schedulesChanged",false);
        Instant sampled;
        try{sampled=Objects.requireNonNull(clock.instant()).truncatedTo(ChronoUnit.MILLIS);result.put("utcInstant",sampled.toString()).put("epochMillis",sampled.toEpochMilli()).put("clockStatus","available");}
        catch(RuntimeException unavailable){return result.put("clockStatus","unavailable").put("utcInstant",JSONObject.NULL).put("epochMillis",JSONObject.NULL).put("zoneStatus","unavailable");}
        result.put("zoneId",JSONObject.NULL).put("utcOffset",JSONObject.NULL).put("localDate",JSONObject.NULL).put("localDateTime",JSONObject.NULL);
        try {
            ZoneId zone=zones.get();if(zone==null)return result.put("zoneStatus","missing");
            ZonedDateTime local=sampled.atZone(zone);
            return result.put("zoneStatus","available").put("zoneId",zone.getId())
                    .put("zoneIdKind",ZoneId.getAvailableZoneIds().contains(zone.getId())?"iana_region_or_alias":"fixed_offset")
                    .put("utcOffset",local.getOffset().getId()).put("localDate",local.toLocalDate().toString()).put("localDateTime",local.toOffsetDateTime().toString());
        }catch(RuntimeException unavailable){return result.put("zoneStatus","unavailable");}
    }
    String referenceData()throws Exception {
        String reference="本次逻辑请求构建时的宿主OS时钟快照；不是外部校时、各输入来源时区或授权。传输重试沿用该快照，下一回合重采；不改变原预算或已有schedule时区。\n<native_request_clock>\n"+capture()+"\n</native_request_clock>";
        if(reference.getBytes(StandardCharsets.UTF_8).length>MAX_REFERENCE_BYTES)throw new IllegalStateException("native_request_clock_reference_exceeded");
        return reference;
    }
}
