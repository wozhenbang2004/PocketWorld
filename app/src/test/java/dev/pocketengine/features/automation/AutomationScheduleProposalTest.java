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
    private JSONObject origin()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",UUID.randomUUID().toString()).put("conversationId","original-conversation");}
    private JSONObject draft()throws Exception{return new JSONObject().put("title","原对话提出的提醒").put("kind","phone_goal").put("goal","由主人核对后执行").put("schedule",new JSONObject().put("type","once").put("at",100000));}
    @Test public void proposalIsInertAndExactNativeApprovalCreatesOnePersistentRule()throws Exception{
        File file=new File(temp.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();JSONObject proposal=book.proposeSchedule(nativeOrigin,operation,draft(),90000);
        assertEquals(0,book.rules().length());assertEquals(-1,book.nextAt());assertEquals("pending",proposal.getString("status"));assertEquals(proposal.getString("id"),book.proposeSchedule(nativeOrigin,operation,draft(),90001).getString("id"));
        assertThrows(SecurityException.class,()->book.approveScheduleProposal(proposal.getString("id"),"0".repeat(64),90002));book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90003);
        AutomationBook restored=new AutomationBook(file);restored.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90004);assertEquals(1,restored.rules().length());assertEquals(1,restored.rules().getJSONObject(0).getInt("revision"));
    }
    @Test public void occurrencePreservesTheNativeOriginalConversationAndTask()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"origin.json"));JSONObject nativeOrigin=origin(),proposal=book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft(),90000);book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90001);JSONObject occurrence=book.claimDue(100000,10).getJSONObject(0);book.delivered(occurrence.getString("id"),"awaiting_user","原机主核对",100000);JSONObject request=book.taskRequest(occurrence.getString("id"),100000);
        assertEquals("original-conversation",request.getJSONObject("origin").getString("originConversationId"));assertEquals(nativeOrigin.getString("taskId"),request.getJSONObject("origin").getString("originTaskId"));assertFalse(request.getBoolean("executionAuthorized"));
    }
    @Test public void sameOperationCannotReplaceContentOrSupplySelfApproval()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"injection.json"));JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();book.proposeSchedule(nativeOrigin,operation,draft(),90000);
        assertThrows(SecurityException.class,()->book.proposeSchedule(nativeOrigin,operation,draft().put("goal","更换未来目标"),90001));assertThrows(SecurityException.class,()->book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft().put("approved",true),90002));assertEquals(0,book.rules().length());
    }
}
