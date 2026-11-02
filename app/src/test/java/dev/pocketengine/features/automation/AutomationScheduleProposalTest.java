package dev.pocketengine.features.automation;

import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationScheduleProposalTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject origin()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",UUID.randomUUID().toString()).put("conversationId","original-conversation").put("runId",UUID.randomUUID().toString()).put("epoch",1);}
    private JSONObject draft()throws Exception{return new JSONObject().put("title","原对话提出的提醒").put("kind","phone_goal").put("goal","由主人核对后执行").put("schedule",new JSONObject().put("type","once").put("at",100000));}
    @Test public void proposalIsInertAndExactNativeApprovalCreatesOnePersistentRule()throws Exception{
        File file=new File(temp.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();JSONObject proposal=book.proposeSchedule(nativeOrigin,operation,draft(),90000);
        assertEquals(0,book.rules().length());assertEquals(-1,book.nextAt());assertEquals("pending",proposal.getString("status"));assertEquals(proposal.getString("id"),book.proposeSchedule(nativeOrigin,operation,draft(),90001).getString("id"));
        assertThrows(SecurityException.class,()->book.approveScheduleProposal(proposal.getString("id"),"0".repeat(64),90002));book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90003);
        AutomationBook restored=new AutomationBook(file);restored.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90004);assertEquals(1,restored.rules().length());assertEquals(1,restored.rules().getJSONObject(0).getInt("revision"));
    }
    @Test public void occurrencePreservesTheNativeOriginalConversationAndTask()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"origin.json"));JSONObject nativeOrigin=origin(),proposal=book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft(),90000);book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90001);JSONObject occurrence=book.claimDue(100000,10).getJSONObject(0);book.delivered(occurrence.getString("id"),"awaiting_user","原机主核对",100000);JSONObject request=book.taskRequest(occurrence.getString("id"),100000);
        assertEquals("original-conversation",request.getJSONObject("origin").getString("originConversationId"));assertEquals(nativeOrigin.getString("taskId"),request.getJSONObject("origin").getString("originTaskId"));assertEquals(nativeOrigin.getString("runId"),request.getJSONObject("origin").getJSONObject("nativeOrigin").getString("runId"));assertEquals(1,request.getJSONObject("origin").getJSONObject("nativeOrigin").getInt("epoch"));assertFalse(request.getBoolean("executionAuthorized"));
    }
    @Test public void sameOperationCannotReplaceContentOrSupplySelfApproval()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"injection.json"));JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();book.proposeSchedule(nativeOrigin,operation,draft(),90000);
        assertThrows(SecurityException.class,()->book.proposeSchedule(nativeOrigin,operation,draft().put("goal","更换未来目标"),90001));assertThrows(SecurityException.class,()->book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft().put("approved",true),90002));assertEquals(0,book.rules().length());
    }
    @Test public void legacyMissingRunAndChangedRunCannotReviveTheOriginalProposal()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"run-origin.json"));JSONObject nativeOrigin=origin(),legacy=new JSONObject(nativeOrigin.toString());legacy.remove("runId");legacy.remove("epoch");
        assertThrows(SecurityException.class,()->book.proposeSchedule(legacy,UUID.randomUUID().toString(),draft(),90000));
        String operation=UUID.randomUUID().toString();book.proposeSchedule(nativeOrigin,operation,draft(),90000);JSONObject changed=new JSONObject(nativeOrigin.toString()).put("runId",UUID.randomUUID().toString()).put("epoch",2);
        assertThrows(SecurityException.class,()->book.proposeSchedule(changed,operation,draft(),90001));assertEquals(0,book.rules().length());
    }
    @Test public void oldDisplayedDigestCannotApproveAnotherOriginalRunProposal()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"old-digest.json"));JSONObject firstOrigin=origin(),first=book.proposeSchedule(firstOrigin,UUID.randomUUID().toString(),draft(),90000);
        JSONObject nextOrigin=new JSONObject(firstOrigin.toString()).put("runId",UUID.randomUUID().toString()).put("epoch",2),next=book.proposeSchedule(nextOrigin,UUID.randomUUID().toString(),draft(),90001);
        assertNotEquals(first.getString("digest"),next.getString("digest"));assertThrows(SecurityException.class,()->book.approveScheduleProposal(next.getString("id"),first.getString("digest"),90002));assertEquals(0,book.rules().length());
    }
    @Test public void elapsedOnceCannotBeApprovedAsANewFutureArrangement()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"elapsed-draft.json"));JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);
        assertThrows(IllegalStateException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),100001));assertEquals(0,book.rules().length());
    }
}
