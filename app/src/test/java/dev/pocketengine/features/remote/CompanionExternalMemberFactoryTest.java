package dev.pocketengine.features.remote;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.teams.ExternalMemberDriver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompanionExternalMemberFactoryTest {
    private JSONObject slot()throws Exception{return new JSONObject().put("id","member-0").put("name","Code reviewer").put("role","Review code").put("runner","codex_app_server").put("model","fixture-model").put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));}
    private JSONObject request()throws Exception{return new JSONObject().put("hostTaskId","parent-task").put("runId","team-run").put("memberId","member-0").put("round",1).put("generation",0).put("operationId","stable-member-operation").put("prompt","Inspect actual project").put("slot",slot());}
    private JSONObject probe()throws Exception{return new JSONObject().put("available",true).put("protocolVerified",true).put("accountConfigured",true).put("configReadVerified",true).put("capabilities",new JSONObject().put("readOnlySandbox",true));}
    private JSONObject job(String status)throws Exception{return new JSONObject().put("id","actual-job").put("operationId","stable-member-operation").put("status",status).put("protocol","codex_app_server").put("threadId","actual-thread").put("turnId","actual-turn").put("readOnly",true).put("readOnlyConfirmed",true).put("externalToolsDisabled",true).put("output","Actual fixture receipt");}
    private static final class Journal implements ExternalChildJournal {
        boolean prepared,known,failRecord;int writes;JSONObject recorded;
        @Override public JSONObject prepare(JSONObject request,JSONObject binding)throws Exception{
            assertTrue(binding.getBoolean("readOnly"));assertEquals("parent-task",request.getString("hostTaskId"));prepared=true;
            return new JSONObject().put("taskId","child-task").put("operationId","stable-member-operation").put("knownOperation",known);
        }
        @Override public void record(JSONObject link,JSONObject receipt)throws Exception{
            if(failRecord)throw new IOException("Fixture journal unavailable");recorded=new JSONObject(receipt.toString());writes++;
        }
    }
    @Test public void firstDispatchIsJournalBoundProbedAndForcedReadOnly()throws Exception{
        Journal journal=new Journal();List<String> calls=new ArrayList<>();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{
            assertTrue(journal.prepared);calls.add(command);
            if(command.equals("reconcileOperation"))return new JSONObject().put("status","not_found");
            if(command.equals("probeWorker"))return probe();
            assertEquals("submitJob",command);assertTrue(input.getBoolean("readOnly"));assertEquals("fixture-model",input.getString("model"));assertEquals("stable-member-operation",input.getString("operationId"));return job("completed");
        },journal);
        JSONObject result=factory.forSlot(slot()).execute(request(),new RunControl(),event->assertTrue(journal.writes>0));
        assertEquals(List.of("reconcileOperation","probeWorker","submitJob"),calls);assertEquals("completed",result.getString("status"));assertEquals("Actual fixture receipt",result.getString("content"));assertEquals("child-task",result.getJSONObject("external").getString("childTaskId"));
    }
    @Test public void lostSubmissionAckAndMissingReceiptNeverCauseASecondSubmission()throws Exception{
        Journal journal=new Journal();int[] submits={0};
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{
            if(command.equals("reconcileOperation"))return new JSONObject().put("status","not_found");
            if(command.equals("probeWorker"))return probe();
            submits[0]++;throw new IOException("Fixture lost acknowledgement");
        },journal);
        ExternalMemberDriver driver=factory.forSlot(slot());assertEquals("unknown",driver.execute(request(),new RunControl(),null).getString("status"));
        journal.known=true;assertEquals("unknown",driver.execute(request(),new RunControl(),null).getString("status"));assertEquals(1,submits[0]);
    }
    @Test public void stoppingAKnownActiveJobCallsRealCancelWithoutResubmitting()throws Exception{
        Journal journal=new Journal();journal.known=true;List<String> calls=new ArrayList<>();RunControl control=new RunControl();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{calls.add(command);return job(command.equals("cancelJob")?"cancelled":"running");},journal);
        JSONObject result=factory.forSlot(slot()).execute(request(),control,event->{if(event.optString("status").equals("running"))control.cancel();});
        assertEquals("cancelled",result.getString("status"));assertEquals(List.of("reconcileOperation","cancelJob"),calls);
    }
    @Test public void pendingApprovalRemainsExplicitAndIsNeverAutomaticallyApproved()throws Exception{
        Journal journal=new Journal();journal.known=true;List<String> calls=new ArrayList<>();List<String> events=new ArrayList<>();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{calls.add(command);return command.equals("getJob")?job("completed"):job("awaiting_approval").put("pendingApprovals",new JSONArray().put(new JSONObject().put("requestId","approval-1").put("canAccept",true)));},journal);
        JSONObject result=factory.forSlot(slot()).execute(request(),new RunControl(),event->{events.add(event.optString("status"));if(event.optString("status").equals("awaiting_approval"))assertEquals(1,event.optJSONArray("pendingApprovals").length());});
        assertEquals("completed",result.getString("status"));assertTrue(events.contains("awaiting_approval"));assertEquals(List.of("reconcileOperation","getJob"),calls);
    }
    @Test public void pausingAPendingApprovalActuallyCancelsTheOriginalJob()throws Exception{
        Journal journal=new Journal();journal.known=true;List<String> calls=new ArrayList<>();RunControl control=new RunControl();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{calls.add(command);return job(command.equals("cancelJob")?"cancelled":"awaiting_approval");},journal);
        JSONObject result=factory.forSlot(slot()).execute(request(),control,event->{if(event.optString("status").equals("awaiting_approval"))control.pause();});
        assertEquals("cancelled",result.getString("status"));assertEquals(List.of("reconcileOperation","cancelJob"),calls);
    }
    @Test public void journalFailureStopsFurtherReadsOrDispatch()throws Exception{
        Journal journal=new Journal();journal.known=true;journal.failRecord=true;List<String> calls=new ArrayList<>();
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{calls.add(command);return job("running");},journal);
        try{factory.forSlot(slot()).execute(request(),new RunControl(),null);fail("Journal failure was ignored");}catch(IOException expected){}
        assertEquals(List.of("reconcileOperation"),calls);
    }
    @Test public void protocolMismatchOrEmptyTextIsNotPersistedAsSuccessfulMember()throws Exception{
        for(boolean mismatch:new boolean[]{true,false}){
            Journal journal=new Journal();journal.known=true;
            CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->job("completed").put("protocol",mismatch?"process_exit":"codex_app_server").put("output",mismatch?"unverified":""),journal);
            JSONObject result=factory.forSlot(slot()).execute(request(),new RunControl(),null);
            assertEquals(mismatch?"unknown":"failed",result.getString("status"));assertEquals(result.getString("status"),journal.recorded.getString("status"));
        }
    }
    @Test public void unimplementedRunnerAndChangedDeviceAreRejectedBeforeDispatch()throws Exception{
        Journal journal=new Journal();CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{fail("Unexpected transport call");return null;},journal);
        try{factory.forSlot(slot().put("runner","claude"));fail("Unsupported runner was enabled");}catch(UnsupportedOperationException expected){}
        ExternalMemberDriver driver=factory.forSlot(slot());JSONObject changed=request();changed.getJSONObject("slot").getJSONObject("device").put("profileId","other");
        try{driver.execute(changed,new RunControl(),null);fail("Changed binding was accepted");}catch(SecurityException expected){}
        assertFalse(journal.prepared);
    }
}
