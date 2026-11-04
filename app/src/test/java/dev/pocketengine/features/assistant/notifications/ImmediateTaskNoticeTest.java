package dev.pocketengine.features.assistant.notifications;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ImmediateTaskNoticeTest {
    private static final String TASK = "11111111-1111-4111-8111-111111111111";
    private static final String RUN = "22222222-2222-4222-8222-222222222222";
    private static final String OPERATION = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER = "44444444-4444-4444-8444-444444444444";

    private JSONObject task() throws Exception {
        return new JSONObject().put("id", TASK).put("runId", RUN).put("coordinatorEpoch", 2)
                .put("kind", "assistant").put("actorId", "owner").put("spaceId", "private")
                .put("executionMode", "capabilities").put("conversationId", "original-conversation");
    }

    private JSONObject arguments() throws Exception {
        return new JSONObject().put("title", "私密结果标题").put("body", "只有原任务可以显示的内容\n第二行")
                .put("dedupeKey", "research.result:1");
    }

    @Test public void publicProjectionAndReceiptNeverContainOriginalText() throws Exception {
        ImmediateTaskNotice.Notice notice = ImmediateTaskNotice.prepare(task(), OPERATION, arguments());
        JSONObject view = notice.view(), receipt = notice.receipt("posted", "notification_api_accepted");
        for (JSONObject projection : new JSONObject[]{view, receipt}) {
            assertFalse(projection.has("title")); assertFalse(projection.has("body"));
            assertFalse(projection.toString().contains("私密结果标题"));
            assertFalse(projection.toString().contains("只有原任务"));
            assertFalse(projection.getBoolean("bodyIncluded"));
            assertFalse(projection.getBoolean("executionAuthorized"));
        }
        assertEquals("original-conversation", view.getString("conversationId"));
        assertTrue(receipt.getBoolean("notificationApiAccepted"));
        assertFalse(receipt.getBoolean("deliveryConfirmed"));
        assertFalse(receipt.getBoolean("userReadConfirmed"));
    }

    @Test public void tokenBindsEveryOriginalExecutionDimension() throws Exception {
        ImmediateTaskNotice.Notice original = ImmediateTaskNotice.prepare(task(), OPERATION, arguments());
        assertEquals(original.token(), ImmediateTaskNotice.prepare(task(), OPERATION, arguments()).token());
        assertNotEquals(original.token(), ImmediateTaskNotice.prepare(task().put("id", OTHER), OPERATION, arguments()).token());
        assertNotEquals(original.token(), ImmediateTaskNotice.prepare(task().put("runId", OTHER), OPERATION, arguments()).token());
        assertNotEquals(original.token(), ImmediateTaskNotice.prepare(task().put("coordinatorEpoch", 3), OPERATION, arguments()).token());
        assertNotEquals(original.token(), ImmediateTaskNotice.prepare(task(), OTHER, arguments()).token());
        assertNotEquals(original.token(), ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("dedupeKey", "next")).token());
        assertNotEquals(original.token(), ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("body", "changed")).token());
    }

    @Test public void contentDigestRetainsExactApprovedTextAndDistinctBoundaries() throws Exception {
        ImmediateTaskNotice.Notice original = ImmediateTaskNotice.prepare(task(), OPERATION, arguments());
        assertEquals(original.contentDigest(), ImmediateTaskNotice.prepare(task(), OTHER, arguments()).contentDigest());
        assertNotEquals(original.contentDigest(), ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("title", " 私密结果标题")).contentDigest());
        String left = ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("title", "ab").put("body", "c")).contentDigest();
        String right = ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("title", "a").put("body", "bc")).contentDigest();
        assertNotEquals(left, right);
    }

    @Test public void unknownResultCannotClaimAcceptanceOrAuthorizeReplay() throws Exception {
        ImmediateTaskNotice.Notice notice = ImmediateTaskNotice.prepare(task(), OPERATION, arguments());
        for (String phase : new String[]{"posting", "unknown", "unconfirmed"}) {
            JSONObject result = notice.receipt(phase, "notification_api_unconfirmed");
            assertTrue(result.isNull("accepted")); assertTrue(result.isNull("notificationApiAccepted"));
            assertFalse(result.getBoolean("deliveryConfirmed")); assertFalse(result.getBoolean("userReadConfirmed"));
            assertFalse(result.getBoolean("automaticRetry")); assertFalse(result.getBoolean("replayAllowed"));
        }
    }

    @Test public void blockedResultsAreKnownNotSentWithoutPermissionOrExecutionClaims() throws Exception {
        ImmediateTaskNotice.Notice notice = ImmediateTaskNotice.prepare(task(), OPERATION, arguments());
        for (String phase : new String[]{"permission_required", "channel_disabled", "rate_limited", "unavailable", "not_dispatched"}) {
            JSONObject result = notice.receipt(phase, phase);
            assertFalse(result.getBoolean("accepted")); assertFalse(result.getBoolean("notificationApiAccepted"));
            assertFalse(result.getBoolean("replayAllowed")); assertFalse(result.getBoolean("executionAuthorized"));
        }
        rejects(() -> notice.receipt("delivered", "success"));
        rejects(() -> notice.receipt("unconfirmed", "a secret from an exception"));
    }

    @Test public void modelCannotInjectAnIdentityOrChangeOwnerScope() throws Exception {
        for (String key : new String[]{"taskId", "runId", "operationId", "epoch", "spaceId", "conversationId", "channel"})
            rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put(key, OTHER)));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("actorId", "remote"), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("spaceId", "group"), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("kind", "remote"), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("sourceLearningJob", new JSONObject()), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("origin", new JSONObject().put("kind", "automation")), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("origin", JSONObject.NULL), OPERATION, arguments()));
        assertNotNull(ImmediateTaskNotice.prepare(task().put("kind", "phone"), OPERATION, arguments()));
        assertNotNull(ImmediateTaskNotice.prepare(task().put("origin", new JSONObject().put("kind", "owner_reviewed_realtime")), OPERATION, arguments()));
    }

    @Test public void identityValidationDoesNotCoerceMalformedExecutionReferences() throws Exception {
        rejects(() -> ImmediateTaskNotice.prepare(task().put("id", "------------------------------------"), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task().put("runId", "2222"), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.prepare(task(), "injected-operation", arguments()));
        for (Object epoch : new Object[]{0, -1, 2.5, "2", (long) Integer.MAX_VALUE + 1})
            rejects(() -> ImmediateTaskNotice.prepare(task().put("coordinatorEpoch", epoch), OPERATION, arguments()));
        rejects(() -> ImmediateTaskNotice.validateNavigation(TASK, RUN, 2, OPERATION, "untrusted"));
        ImmediateTaskNotice.Notice valid = ImmediateTaskNotice.prepare(task(), OPERATION, arguments());
        ImmediateTaskNotice.validateNavigation(TASK, RUN, 2, OPERATION, valid.token());
    }

    @Test public void textLimitsAndMalformedUnicodeAreRejectedBeforeDispatch() throws Exception {
        rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("title", "x".repeat(81))));
        rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("body", "x".repeat(1001))));
        for (String invalid : new String[]{"", "  \t", "x\ny", "x\u0000y", "x\uD800", "x\uDC00"})
            rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("title", invalid)));
        rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("body", "x\ry")));
        rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("dedupeKey", "../another")));
        rejects(() -> ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("body", 123)));
        assertNotNull(ImmediateTaskNotice.prepare(task(), OPERATION, arguments().put("title", "😀".repeat(80)).put("body", "x\ny\tz")));
    }

    @Test public void callerMutationCannotAlterAnExistingNoticeOrReceipt() throws Exception {
        JSONObject task = task(), args = arguments();
        ImmediateTaskNotice.Notice notice = ImmediateTaskNotice.prepare(task, OPERATION, args);
        String token = notice.token(), digest = notice.contentDigest();
        task.put("runId", OTHER); args.put("body", "changed");
        notice.view().put("taskId", OTHER).put("token", "changed");
        notice.receipt("posted", "notification_api_accepted").put("phase", "unconfirmed");
        assertEquals(TASK, notice.taskId()); assertEquals(RUN, notice.runId());
        assertEquals(token, notice.token()); assertEquals(digest, notice.contentDigest());
        assertEquals(token, notice.view().getString("token"));
        assertEquals("posted", notice.receipt("posted", "notification_api_accepted").getString("phase"));
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked value) throws Exception {
        try { value.run(); fail("Expected the original notification contract to reject this value"); }
        catch (IllegalArgumentException | SecurityException expected) { }
    }
}
