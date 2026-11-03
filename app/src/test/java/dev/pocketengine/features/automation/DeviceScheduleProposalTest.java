package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Actual original Book persistence and rule review. Authentication/SQLite is exercised
 * separately by DeviceIngressNativeTest, not inferred from this metadata-only test. */
public final class DeviceScheduleProposalTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static final long NOW=1_800_000_000_000L;
    private JSONObject origin()throws Exception{
        return new JSONObject().put("kind",DeviceScheduleOrigin.KIND).put("ownerId","owner").put("spaceId","private")
                .put("originDeviceId","pw1_"+"a".repeat(64)).put("callerDeviceId","pw1_"+"a".repeat(64)).put("targetDeviceId","pw1_"+"b".repeat(64))
                .put("instanceId","receiver-instance").put("certificateFingerprint","c".repeat(64)).put("grantId","original-grant").put("authorizationEpoch",1)
                .put("grantFingerprint","d".repeat(64)).put("grantExpiresAt",NOW+86_400_000L).put("operationId",UUID.randomUUID().toString()).put("argumentsDigest","e".repeat(64))
                .put("rootTaskId","script-request").put("localTaskId","draft-request").put("requestRunId","source-request").put("requestExecutorEpoch",1);
    }
    private JSONObject draft()throws Exception{return new JSONObject().put("title","桌面脚本提出的提醒").put("kind","phone_goal").put("goal","仅在主人核对后启动原目标").put("schedule",new JSONObject().put("type","once").put("at",NOW+86_400_000L));}

    @Test public void externalDraftIsInertAndColdDuplicateAndQueryDoNotWrite()throws Exception{
        File file=new File(temp.getRoot(),"device-draft.json");AutomationBook book=new AutomationBook(file);JSONObject source=origin(),saved=book.proposeDeviceSchedule(source,draft(),NOW);
        assertEquals("pending",saved.getString("status"));assertFalse(saved.getBoolean("executionAuthorized"));assertEquals(0,book.rules().length());assertEquals(0,book.receipts().length());assertEquals(-1,book.nextAt());
        byte[] before=Files.readAllBytes(file.toPath());AutomationBook cold=new AutomationBook(file);assertEquals(saved.getString("id"),cold.proposeDeviceSchedule(source,draft(),NOW+1).getString("id"));
        // A new query envelope may have different correlation IDs, but its response
        // audience remains the original stored request rather than the query's IDs.
        JSONObject query=new JSONObject(source.toString()).put("rootTaskId","query-root").put("requestRunId","query-run");JSONObject found=cold.deviceScheduleProposal(query);
        assertEquals(saved.getString("id"),found.getString("id"));assertEquals("script-request",found.getJSONObject("origin").getString("rootTaskId"));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        assertNull(cold.deviceScheduleProposal(origin()));assertEquals(1,cold.scheduleProposals(null).length());assertEquals(0,cold.rules().length());
    }
    @Test public void sameOriginalOperationCannotReplaceBodyDigestOrAuthority()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"device-original.json"));JSONObject source=origin();book.proposeDeviceSchedule(source,draft(),NOW);
        assertThrows(SecurityException.class,()->book.proposeDeviceSchedule(source,draft().put("goal","替换目标"),NOW+1));
        JSONObject changedDigest=new JSONObject(source.toString()).put("argumentsDigest","f".repeat(64));assertThrows(SecurityException.class,()->book.proposeDeviceSchedule(changedDigest,draft(),NOW+1));assertThrows(SecurityException.class,()->book.deviceScheduleProposal(changedDigest));
        JSONObject changedGrant=new JSONObject(source.toString()).put("grantId","replacement-grant");assertThrows(SecurityException.class,()->book.proposeDeviceSchedule(changedGrant,draft(),NOW+1));assertThrows(SecurityException.class,()->book.deviceScheduleProposal(changedGrant));
        assertEquals(1,book.scheduleProposals(null).length());assertEquals(0,book.rules().length());
    }
    @Test public void rateLimitSurvivesColdReadAndGrantReplacementButDuplicatesDoNotConsumeIt()throws Exception{
        File file=new File(temp.getRoot(),"device-rate.json");AutomationBook book=new AutomationBook(file);JSONObject first=origin();book.proposeDeviceSchedule(first,draft(),NOW);
        AutomationBook.DeviceDraftRefused tooSoon=assertThrows(AutomationBook.DeviceDraftRefused.class,()->book.proposeDeviceSchedule(origin(),draft(),NOW+29_999));assertEquals("rate_limited",tooSoon.code);
        for(int i=1;i<8;i++)book.proposeDeviceSchedule(origin(),draft(),NOW+i*30_000L);
        AutomationBook cold=new AutomationBook(file);assertEquals(first.getString("operationId"),cold.proposeDeviceSchedule(first,draft(),NOW+240_000).getJSONObject("origin").getString("operationId"));
        JSONObject replacement=origin().put("grantId","new-reviewed-grant").put("authorizationEpoch",2).put("grantFingerprint","f".repeat(64));
        assertEquals("rate_limited",assertThrows(AutomationBook.DeviceDraftRefused.class,()->cold.proposeDeviceSchedule(replacement,draft(),NOW+240_000)).code);assertEquals(8,cold.scheduleProposals(null).length());
        cold.proposeDeviceSchedule(replacement,draft(),NOW+3_600_000L);assertEquals(9,cold.scheduleProposals(null).length());assertEquals(0,cold.rules().length());
    }
    @Test public void onlyExactOwnerReviewCreatesTheExistingRuleAndDeviceOriginStaysDistinct()throws Exception{
        File file=new File(temp.getRoot(),"device-review.json");AutomationBook book=new AutomationBook(file);JSONObject source=origin(),row=book.proposeDeviceSchedule(source,draft(),NOW);
        assertThrows(SecurityException.class,()->book.approveScheduleProposal(row.getString("id"),"0".repeat(64),NOW+1));
        JSONObject changed=new JSONObject(row.toString());changed.getJSONObject("origin").put("requestRunId","another-source-run");assertThrows(SecurityException.class,()->AutomationBook.reviewedProposalForDisplay(changed));
        book.approveScheduleProposal(row.getString("id"),row.getString("digest"),NOW+1);AutomationBook cold=new AutomationBook(file);assertTrue(cold.approveScheduleProposal(row.getString("id"),row.getString("digest"),NOW+2).getBoolean("duplicate"));assertEquals(1,cold.rules().length());
        long due=NOW+86_400_000L;JSONObject occurrence=cold.claimDue(due,10).getJSONObject(0);cold.delivered(occurrence.getString("id"),"awaiting_user","原手机目标仍须主人启动",due);JSONObject request=cold.taskRequest(occurrence.getString("id"),due);
        assertEquals(DeviceScheduleOrigin.KIND,request.getJSONObject("origin").getJSONObject("nativeOrigin").getString("kind"));assertFalse(request.getJSONObject("origin").has("originConversationId"));assertFalse(request.getJSONObject("origin").has("originTaskId"));assertFalse(request.getBoolean("executionAuthorized"));
    }
    @Test public void credentialQuarantinePreservesDraftBodyAndBlocksOriginalApprovalAndDue()throws Exception{
        File file=new File(temp.getRoot(),"device-quarantine.json");AutomationBook book=new AutomationBook(file);JSONObject first=origin(),second=new JSONObject(first.toString()).put("operationId",UUID.randomUUID().toString()),pending=book.proposeDeviceSchedule(first,draft(),NOW),approved=book.proposeDeviceSchedule(second,draft(),NOW+30_000);
        book.approveScheduleProposal(approved.getString("id"),approved.getString("digest"),NOW+30_001);book.nativeScheduleSourcePending(first,true,NOW+30_002);
        AutomationBook cold=new AutomationBook(file);assertEquals(draft().getString("goal"),cold.deviceScheduleProposal(first).getJSONObject("draft").getString("goal"));assertTrue(cold.deviceScheduleProposal(first).getBoolean("sourceCheckPending"));assertFalse(cold.deviceScheduleProposal(first).optBoolean("sourceInvalidated"));
        assertThrows(SecurityException.class,()->cold.approveScheduleProposal(pending.getString("id"),pending.getString("digest"),NOW+30_003));assertEquals(0,cold.claimDue(NOW+86_400_000L,10).length());assertEquals(1,cold.rules().length());
    }
    @Test public void deviceShapeCannotCarryChatAuthorityAndAudienceHasNoReceivingRun()throws Exception{
        JSONObject source=origin();assertThrows(SecurityException.class,()->DeviceScheduleOrigin.checked(new JSONObject(source.toString()).put("conversationId","private-conversation")));
        assertThrows(SecurityException.class,()->DeviceScheduleOrigin.checked(new JSONObject(source.toString()).put("originDeviceId","pw1_"+"f".repeat(64))));assertThrows(SecurityException.class,()->DeviceScheduleOrigin.checked(new JSONObject(source.toString()).put("authorizationEpoch","1")));
        JSONObject audience=DeviceScheduleOrigin.audience(source,NOW);assertEquals("source-request",audience.getString("requestRunId"));assertFalse(audience.has("runId"));assertFalse(audience.has("executorEpoch"));assertEquals(source.getString("operationId"),audience.getString("objectId"));
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"device-no-self-approval.json"));assertThrows(SecurityException.class,()->book.proposeDeviceSchedule(source,draft().put("approved",true),NOW));assertEquals(0,book.scheduleProposals(null).length());
    }
}
