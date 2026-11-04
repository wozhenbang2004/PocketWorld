package dev.pocketengine.features.remote;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.teams.ExternalMemberDriver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real member adapter and journal call order; the remote protocol is a deliberate fixture boundary. */
public class GenericTeamHarnessJourneyTest {
    private JSONObject member()throws Exception{return new JSONObject().put("id","author").put("name","Author").put("role","Inspect selected project").put("runner","acp_fixture").put("externalExecutionRiskAcknowledged",true).put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));}
    private JSONObject context()throws Exception{return new JSONObject().put("protocol","pocketworld.team-tools.v1").put("hostTaskId","team-run").put("runId","team-run").put("memberId","author").put("turnId","original-turn").put("round",1).put("generation",0).put("runGeneration",0).put("roster",new JSONArray().put(new JSONObject().put("id","author").put("name","Author")).put(new JSONObject().put("id","reviewer").put("name","Reviewer")));}
    private JSONObject request()throws Exception{return new JSONObject().put("hostTaskId","team-run").put("runId","team-run").put("memberId","author").put("round",1).put("generation",0).put("operationId","original-operation").put("prompt","Inspect the assigned files").put("slot",member()).put("harnessSelection",RemoteHarnessSelectionTest.selection("acp_fixture")).put("teamContext",context());}
    private JSONObject receipt(String state,boolean pending)throws Exception{return RemoteHarnessSelectionTest.terminal("acp_fixture").put("id","original-job").put("operationId","original-operation").put("environmentId","project").put("serviceBase","http://127.0.0.1:1234").put("status",state).put("output","Original harness result").put("teamContext",context()).put("pendingTeamTools",pending?new JSONArray().put(new JSONObject().put("requestId","peer-call-1").put("name","team_send_message").put("arguments",new JSONObject().put("targetMemberId","reviewer").put("text","The original check is ready"))):new JSONArray());}
    private JSONObject environments()throws Exception{return new JSONObject().put("environments",new JSONArray().put(new JSONObject().put("id","project").put("bindingHash","c".repeat(64)).put("kind","local").put("workers",new JSONArray().put(new JSONObject().put("id","acp_fixture").put("available",true)))));}
    private final class Journal implements ExternalChildJournal {
        int records;boolean known;
        public JSONObject prepare(JSONObject request,JSONObject binding)throws Exception{assertEquals("acp_fixture",binding.getString("worker"));assertFalse(binding.getBoolean("readOnly"));RemoteHarnessSelection.requireSame(RemoteHarnessSelectionTest.selection("acp_fixture"),binding.getJSONObject("harnessSelection"),"acp_fixture");return new JSONObject().put("taskId","child-task").put("operationId","original-operation").put("knownOperation",known);}
        public void record(JSONObject link,JSONObject receipt){records++;}
    }
    @Test public void lostToolReplyRetriesTheSameResultWithoutAnotherMessageOrJob()throws Exception {
        Journal journal=new Journal();AtomicInteger submissions=new AtomicInteger(),answers=new AtomicInteger(),calls=new AtomicInteger(),polls=new AtomicInteger();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{
            if(command.equals("environments"))return environments();if(command.equals("probeWorker"))return RemoteHarnessSelectionTest.probe("acp_fixture");
            if(command.equals("reconcileOperation"))return new JSONObject().put("status","not_found");
            if(command.equals("submitJob")){submissions.incrementAndGet();assertFalse(input.getBoolean("readOnly"));assertEquals("original-turn",input.getJSONObject("teamContext").getString("turnId"));return receipt("running",true);}
            if(command.equals("answerTeamTool")){assertTrue(journal.records>0);assertEquals("original-operation",input.getString("jobOperationId"));assertEquals("queued-message",input.getJSONObject("result").getString("messageId"));if(answers.incrementAndGet()==1)throw new IOException("Lost original reply acknowledgement");return receipt("running",false);}
            assertEquals("getJob",command);return receipt(polls.incrementAndGet()==1?"running":"completed",polls.get()==1);
        },journal);
        ExternalMemberDriver driver=factory.forSlot(member());driver.probe(member(),new RunControl());
        JSONObject result=driver.execute(request(),new RunControl(),null,(requestId,name,args)->{calls.incrementAndGet();assertEquals("peer-call-1",requestId);return new JSONObject().put("messageId","queued-message").put("status","queued");});
        assertEquals("completed",result.getString("status"));assertEquals(1,submissions.get());assertEquals(1,calls.get());assertEquals(2,answers.get());assertTrue(result.has("sessionSource"));
    }
    @Test public void oldTurnPendingToolsNeverAcquireTheNewTurnSender()throws Exception {
        Journal journal=new Journal();journal.known=true;AtomicInteger calls=new AtomicInteger();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{assertFalse(command.equals("submitJob"));assertFalse(command.equals("answerTeamTool"));return receipt(command.equals("getJob")?"completed":"running",!command.equals("getJob")).put("teamContext",context().put("turnId","previous-turn"));},journal);
        JSONObject result=factory.forSlot(member()).execute(request(),new RunControl(),null,(id,name,args)->{calls.incrementAndGet();return new JSONObject();});assertEquals("completed",result.getString("status"));assertEquals(0,calls.get());
    }
    @Test public void completedTextWithoutProtocolSettlementIsNotAMemberCompletion()throws Exception {
        Journal journal=new Journal();journal.known=true;CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->receipt("completed",false).put("promptTerminalConfirmed",false),journal);
        JSONObject result=factory.forSlot(member()).execute(request(),new RunControl(),null);assertEquals("unknown",result.getString("status"));assertFalse(result.has("sessionSource"));
    }
    @Test public void pauseInsideOriginalBridgeCancelsOriginalJobWithoutAnswerOrResubmit()throws Exception {
        Journal journal=new Journal();journal.known=true;AtomicInteger cancelled=new AtomicInteger();RunControl control=new RunControl();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{
            if(command.equals("reconcileOperation"))return receipt("running",true);
            assertEquals("cancelJob",command);assertEquals("original-job",input.getString("jobId"));assertEquals("original-operation",input.getString("jobOperationId"));cancelled.incrementAndGet();return receipt("cancelled",false);
        },journal);
        JSONObject result=factory.forSlot(member()).execute(request(),control,null,(id,name,args)->{control.pause();throw new InterruptedException("Original Turn paused before callback");});assertEquals("cancelled",result.getString("status"));assertEquals(1,cancelled.get());
    }
    private JSONObject piReceipt(String operation)throws Exception{return new JSONObject().put("id","pi-"+operation).put("operationId",operation).put("worker","pi").put("environmentId","project").put("serviceBase","http://127.0.0.1:1234").put("status","completed").put("protocol","pi_rpc_v1").put("protocolVerified",true).put("sessionId","original-pi-session").put("threadId","original-pi-session").put("promptRequestId","pi-prompt-"+operation).put("nativeSettled",true).put("resourcesHeld",false).put("processCleanupProof",new JSONObject().put("processGroupStoppedVerified",true)).put("noTools",false).put("teamTools",true).put("teamToolsOnly",true).put("codingToolsSupported",false).put("teamContext",context()).put("output","Actual bounded Pi team result");}
    @Test public void boundedPiTeamCompletesAndContinuesItsOriginalSessionWithoutCodingTools()throws Exception {
        JSONObject slot=member().put("runner","pi"),first=request().put("slot",slot);first.remove("harnessSelection");AtomicInteger submissions=new AtomicInteger();
        ExternalChildJournal journal=new ExternalChildJournal(){public JSONObject prepare(JSONObject request,JSONObject binding)throws Exception{assertEquals("pi",binding.getString("worker"));assertFalse(binding.getBoolean("readOnly"));return new JSONObject().put("taskId","child-"+request.getString("operationId")).put("operationId",request.getString("operationId")).put("knownOperation",false);}public void record(JSONObject link,JSONObject receipt){}};
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{
            if(command.equals("reconcileOperation"))return new JSONObject().put("status","not_found");if(command.equals("environments"))return environments();
            if(command.equals("probeWorker"))return new JSONObject().put("worker","pi").put("available",true).put("protocolVerified",true).put("noTools",true);
            assertEquals("submitJob",command);if(submissions.incrementAndGet()==2){assertEquals("original-pi-session",input.getString("threadId"));assertEquals("pi-original-operation",input.getString("resumeJobId"));}return piReceipt(input.getString("operationId"));
        },journal);
        JSONObject result=factory.forSlot(slot).execute(first,new RunControl(),null,(id,name,args)->new JSONObject());assertEquals("completed",result.getString("status"));JSONObject source=result.getJSONObject("sessionSource");assertFalse(source.getBoolean("noTools"));assertTrue(source.getBoolean("teamToolsOnly"));assertFalse(source.getBoolean("codingToolsSupported"));
        JSONObject next=new JSONObject(first.toString()).put("operationId","next-operation").put("round",2).put("sessionSource",source);assertEquals("completed",factory.forSlot(slot).execute(next,new RunControl(),null,(id,name,args)->new JSONObject()).getString("status"));assertEquals(2,submissions.get());
        assertFalse(RemoteHarnessSelection.piScopeConfirmed(piReceipt("bad").put("codingToolsSupported",true)));JSONObject unbound=piReceipt("bad");unbound.remove("teamContext");assertFalse(RemoteHarnessSelection.piScopeConfirmed(unbound));
    }
}
