package dev.pocketengine.runtime.usage;

import java.time.Instant;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InferenceFailureTest {
    private static final long AT=Instant.parse("2026-11-01T10:00:00Z").toEpochMilli();
    private InferenceFailure http(String value)throws Exception{return InferenceFailure.http(429,value==null?List.of():List.of(value),List.of("Sun, 01 Nov 2026 10:00:00 GMT"),AT);}
    @Test public void actualNonnegativeSecondsIncludeZeroAndNeverBecomeHealthOrQuotaProof()throws Exception {
        for(long value:new long[]{0,1,120,31536000}) {
            JSONObject failure=http(Long.toString(value)).toJson(),retry=failure.getJSONObject("retry");assertEquals("VALID",retry.getString("status"));assertEquals(value,retry.getLong("delaySeconds"));assertEquals(AT+value*1000,retry.getLong("estimatedNotBefore"));assertFalse(failure.getBoolean("automaticRetry"));assertFalse(failure.getBoolean("healthVerified"));assertFalse(retry.getBoolean("quotaResetKnown"));assertEquals(failure.toString(),InferenceFailure.checked(failure).toJson().toString());
        }
    }
    @Test public void missingIsUnknownNotZeroOrReady()throws Exception {
        JSONObject retry=http(null).retry();assertEquals("MISSING",retry.getString("status"));assertTrue(retry.isNull("estimatedNotBefore"));assertTrue(retry.isNull("delaySeconds"));
        assertTrue(InferenceFailurePresentation.fromFailure(http(null).toJson(),AT).lines().stream().anyMatch(x->x.contains("不是立即可用")));
    }
    @Test public void negativeFloatingExponentOverflowAndHugeStringsRemainInvalidBoundedly()throws Exception {
        for(String value:new String[]{"-1","1.5","+2","1e100000000","99999999999999999999999999999999999999","31536001","0".repeat(129),"120\r\n", "Sun, 01 Nov 100000000 10:00:00 GMT"}) {
            JSONObject retry=http(value).retry();assertEquals(value,"INVALID",retry.getString("status"));assertTrue(retry.isNull("estimatedNotBefore"));
        }
        JSONObject nearOverflow=InferenceFailure.http(429,List.of("120"),List.of(),Long.MAX_VALUE-1).retry();assertEquals("INVALID",nearOverflow.getString("status"));
    }
    @Test public void duplicateRetryAfterIsNotSilentlyFirstOrCombinedComma()throws Exception {
        JSONObject duplicate=InferenceFailure.http(503,List.of("120","1"),List.of(),AT).retry();assertEquals("INVALID",duplicate.getString("status"));assertEquals("INVALID",http("120, 1").retry().getString("status"));
    }
    @Test public void httpDateUsesResponseDateDifferenceAndExplicitLocalEstimateWithoutTrustedDate()throws Exception {
        JSONObject retry=http("Sun, 01 Nov 2026 10:01:00 GMT").retry();assertEquals("RESPONSE_DATE_ESTIMATE",retry.getString("basis"));assertEquals(AT+60000,retry.getLong("estimatedNotBefore"));
        JSONObject local=InferenceFailure.http(503,List.of("Sun, 01 Nov 2026 10:01:00 GMT"),List.of(),AT).retry();assertEquals("LOCAL_CLOCK_ESTIMATE",local.getString("basis"));assertTrue(local.isNull("responseDateAt"));
        JSONObject duplicate=InferenceFailure.http(503,List.of("Sun, 01 Nov 2026 10:01:00 GMT"),List.of("a","b"),AT).retry();assertEquals("LOCAL_CLOCK_ESTIMATE",duplicate.getString("basis"));
    }
    @Test public void responseClockSkewDoesNotMasqueradeAsExactLocalAbsoluteTime()throws Exception {
        JSONObject retry=InferenceFailure.http(503,List.of("Sun, 01 Nov 2026 12:01:00 GMT"),List.of("Sun, 01 Nov 2026 12:00:00 GMT"),AT).retry();assertEquals(AT+60000,retry.getLong("estimatedNotBefore"));assertEquals(Instant.parse("2026-11-01T12:01:00Z").toEpochMilli(),retry.getLong("reportedDateAt"));
        assertTrue(InferenceFailurePresentation.fromFailure(InferenceFailure.http(503,List.of("Sun, 01 Nov 2026 12:01:00 GMT"),List.of("Sun, 01 Nov 2026 12:00:00 GMT"),AT).toJson(),AT).lines().stream().anyMatch(x->x.contains("估算")));
    }
    @Test public void obsoleteRfc850AndAsctimeDatesAreParsedUsingTheirActualCalendar()throws Exception {
        long old=Instant.parse("1994-11-06T08:49:37Z").toEpochMilli();
        for(String value:new String[]{"Sunday, 06-Nov-94 08:50:00 GMT","Sun Nov  6 08:50:00 1994"}) {
            JSONObject retry=InferenceFailure.http(503,List.of(value),List.of("Sun, 06 Nov 1994 08:49:37 GMT"),old).retry();assertEquals(value,"VALID",retry.getString("status"));assertEquals(old+23000,retry.getLong("estimatedNotBefore"));
        }
    }
    @Test public void pastDatesInvalidCalendarAndRawSecretHeadersAreNotStored()throws Exception {
        for(String value:new String[]{"Sun, 01 Nov 2026 09:59:59 GMT","Sun, 31 Feb 2026 10:00:00 GMT","fixture-private-key"})assertEquals("INVALID",http(value).retry().getString("status"));
        assertFalse(http("fixture-private-key").toJson().toString().contains("fixture-private-key"));
    }
    @Test public void strictReceiptRejectsAuthorityFreeTextTypeTricksAndInconsistentRetry()throws Exception {
        JSONObject original=http("120").toJson();assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(original.toString()).put("healthVerified",true)));assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(original.toString()).put("body","provider echo")));assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(original.toString()).put("observedAt",AT+0.5)));
        JSONObject inconsistent=new JSONObject(original.toString());inconsistent.getJSONObject("retry").put("estimatedNotBefore",AT+1);assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(inconsistent));
    }
    @Test public void partialTextAndNativeAccountCooldownStayContentFreeAndSeparateFromHttpHeader()throws Exception {
        JSONObject account=InferenceFailure.account("ACCOUNT_COOLDOWN",429,AT+30000,AT).withPartialText(true).toJson();assertTrue(account.getBoolean("partialTextReceived"));assertEquals("NATIVE_ACCOUNT_GATE",account.getJSONObject("retry").getString("basis"));assertEquals("ACCOUNT_COOLDOWN",account.getString("nativeCode"));assertFalse(InferenceFailure.account("TOKEN_ECHO_SHOULD_NOT_BE_STORED",0,0,AT).toJson().toString().contains("TOKEN_ECHO"));
        account.put("nativeCode","untrusted-secret");assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(account));
    }
    @Test public void presentationNeverCallsExpiredHintHealthyAndUnknownFailureStaysUnknown()throws Exception {
        InferenceFailurePresentation.View view=InferenceFailurePresentation.fromFailure(http("1").toJson(),AT+2000);assertTrue(view.lines().stream().anyMatch(x->x.contains("等待时间已过")));assertTrue(view.lines().stream().anyMatch(x->x.contains("不证明连接恢复")));assertFalse(view.title().contains("恢复"));
        assertEquals("失败细节暂不可核对",InferenceFailurePresentation.fromFailure(new JSONObject().put("body","anything"),AT).title());
    }
    @Test public void canceledAndLocalFailuresDoNotInventHttpOrFutureDispatchPermission()throws Exception {
        for(InferenceFailure.Kind kind:new InferenceFailure.Kind[]{InferenceFailure.Kind.CANCELLED,InferenceFailure.Kind.LOCAL_REFUSAL,InferenceFailure.Kind.NETWORK,InferenceFailure.Kind.TIMEOUT,InferenceFailure.Kind.PROTOCOL,InferenceFailure.Kind.UNKNOWN}) {
            JSONObject row=InferenceFailure.local(kind,AT).toJson();assertTrue(row.isNull("httpStatus"));assertFalse(row.getBoolean("providerProcessingKnown"));assertFalse(row.getBoolean("chargeKnown"));assertFalse(row.getBoolean("automaticRetry"));assertEquals("MISSING",row.getJSONObject("retry").getString("status"));
        }
        JSONObject protocol=InferenceFailure.local(InferenceFailure.Kind.PROTOCOL,200,AT).toJson();assertEquals(200,InferenceFailure.checked(protocol).httpStatus);assertTrue(InferenceFailurePresentation.fromFailure(protocol,AT).lines().stream().anyMatch(x->x.contains("HTTP 200")));
    }
}
