package dev.pocketengine.runtime.usage;

import java.time.Instant;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Content-free AWS observations use actual HTTP status and never become replay authority. */
public final class BedrockFailureTest {
    private static final long AT=Instant.parse("2026-11-04T09:00:00Z").toEpochMilli();
    private static InferenceFailure failure(int status,List<String> types,String body)throws Exception {
        return InferenceFailure.bedrockHttp(status,List.of("2"),List.of(),types,AT,body);
    }
    @Test public void signatureFailureKeepsActual400AndProvidesCredentialGuidance()throws Exception {
        InferenceFailure row=failure(400,List.of("IncompleteSignature"),"{\"message\":\"private provider prose\"}");
        assertEquals(InferenceFailure.Kind.HTTP,row.kind);assertEquals(400,row.httpStatus);
        assertEquals("BEDROCK_SIGNATURE_INVALID",row.nativeCode);
        assertEquals(row.toJson().toString(),InferenceFailure.checked(row.toJson()).toJson().toString());
        assertTrue(InferenceFailurePresentation.fromFailure(row.toJson(),AT).lines().stream().anyMatch(x->x.contains("完整临时凭据")));
        assertFalse(row.toJson().toString().contains("private provider prose"));
    }
    @Test public void smithyQualifiedHeaderAndBodyIdentifiersAgreeWithoutSavingRawFields()throws Exception {
        InferenceFailure row=failure(403,List.of("com.amazonaws.bedrock#AccessDeniedException:http://example.invalid/error"),
                "{\"__type\":\"com.amazonaws.bedrock#AccessDeniedException\",\"code\":\"AccessDeniedException\",\"message\":\"private access details\"}");
        assertEquals("BEDROCK_ACCESS_DENIED",row.nativeCode);assertEquals(403,row.httpStatus);
        String json=row.toJson().toString();assertFalse(json.contains("example.invalid"));assertFalse(json.contains("private access details"));
    }
    @Test public void awsThrottlingNeverClaimsTransientFrequencyOrKnownBalance()throws Exception {
        InferenceFailure row=failure(429,List.of(),"{\"__type\":\"ThrottlingException\"}");
        assertEquals(InferenceFailure.Kind.HTTP,row.kind);assertEquals("BEDROCK_THROTTLING",row.nativeCode);
        assertEquals("VALID",row.retry().getString("status"));assertEquals(AT+2000,row.retry().getLong("estimatedNotBefore"));
        assertFalse(row.toJson().getBoolean("automaticRetry"));assertFalse(row.retry().getBoolean("quotaResetKnown"));
        assertFalse(row.toJson().getBoolean("providerProcessingKnown"));assertFalse(row.toJson().getBoolean("chargeKnown"));
    }
    @Test public void exactQuotaCodeIsDistinctFromGenericThrottling()throws Exception {
        InferenceFailure row=failure(400,List.of(),"{\"code\":\"ServiceQuotaExceededException\"}");
        assertEquals(InferenceFailure.Kind.QUOTA_EXHAUSTED,row.kind);assertEquals(400,row.httpStatus);
        assertEquals("BEDROCK_QUOTA_EXCEEDED",InferenceFailure.checked(row.toJson()).nativeCode);
    }
    @Test public void ambiguousMalformedOrUnknownIdentifiersDoNotBecomeCanonicalFacts()throws Exception {
        for(String body:new String[]{"{\"__type\":\"AccessDeniedException\",\"code\":\"ValidationException\"}",
                "{\"code\":17}","{\"code\":\"UnknownPrivateError\"}","{\"code\":\"AccessDeniedException\",\"code\":\"ValidationException\"}",
                "{'code':'AccessDeniedException'}","{\"message\":\"AccessDeniedException\"}"})
            assertTrue(body,failure(403,List.of(),body).nativeCode.isEmpty());
        assertTrue(failure(403,List.of("AccessDeniedException","AccessDeniedException"),"{}").nativeCode.isEmpty());
        assertTrue(failure(403,List.of("AccessDeniedException"),"{\"code\":\"ValidationException\"}").nativeCode.isEmpty());
        assertTrue(failure(403,List.of("UnknownPrivateError"),"{\"code\":\"AccessDeniedException\"}").nativeCode.isEmpty());
        assertTrue(failure(403,List.of("AccessDeniedException\r\nsecret"),"{}").nativeCode.isEmpty());
    }
    @Test public void binaryServiceErrorsRetainHttp200AndNoRetryHint()throws Exception {
        for(String event:List.of("internalServerException","modelStreamErrorException","validationException",
                "throttlingException","modelTimeoutException","serviceUnavailableException")) {
            InferenceFailure row=InferenceFailure.bedrockStream(event,200,AT).withPartialText(true);
            assertEquals(InferenceFailure.Kind.PROTOCOL,row.kind);assertEquals(200,row.httpStatus);
            assertTrue(row.partialTextReceived);assertEquals("MISSING",row.retry().getString("status"));
            assertEquals(row.nativeCode,InferenceFailure.checked(row.toJson()).nativeCode);
            assertEquals("Bedrock 在事件流中返回错误",InferenceFailurePresentation.fromFailure(row.toJson(),AT).title());
        }
        assertThrows(IllegalArgumentException.class,()->InferenceFailure.bedrockStream("throttlingException",429,AT));
        assertThrows(IllegalArgumentException.class,()->InferenceFailure.bedrockStream("AccessDeniedException",200,AT));
    }
    @Test public void checkedReceiptRejectsInventedRetryClassification()throws Exception {
        JSONObject original=failure(429,List.of("ThrottlingException"),"{}").toJson();
        for(String kind:List.of("RATE_LIMIT","QUOTA_EXHAUSTED","POLICY_REFUSAL","ACCOUNT_GATE"))
            assertThrows(kind,IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(original.toString()).put("kind",kind)));
        JSONObject quota=failure(400,List.of("ServiceQuotaExceededException"),"{}").toJson();
        assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(quota.put("kind","HTTP")));
    }
    @Test public void checkedStreamReceiptCannotInventNon200OrUnrelatedCodes()throws Exception {
        JSONObject row=InferenceFailure.bedrockStream("modelStreamErrorException",200,AT).toJson();
        assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(row.toString()).put("httpStatus",429)));
        assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(row.toString()).put("nativeCode","BEDROCK_SIGNATURE_INVALID")));
        assertThrows(IllegalArgumentException.class,()->InferenceFailure.checked(new JSONObject(row.toString()).put("automaticRetry",true)));
    }
    @Test public void expiredRequestGuidanceDoesNotRefreshSessionOrDeclareConnectionHealthy()throws Exception {
        InferenceFailure row=failure(400,List.of("RequestExpired"),"{\"message\":\"time diagnostic\"}");
        String text=String.join("\n",InferenceFailurePresentation.fromFailure(row.toJson(),AT+3000).lines());
        assertTrue(text.contains("核对设备时钟"));assertTrue(text.contains("没有延长原会话"));
        assertTrue(text.contains("不证明连接恢复"));assertTrue(text.contains("收费或退费仍未知"));
    }
}
