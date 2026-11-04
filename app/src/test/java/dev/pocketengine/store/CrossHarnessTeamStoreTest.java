package dev.pocketengine.store;
import android.app.Application;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CrossHarnessTeamStoreTest {
    private final java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean(true);
    private TaskStore opened;
    private TaskStore store(){return opened=new TaskStore(RuntimeEnvironment.getApplication(),ready::get,(stage,version)->{});}
    @org.junit.After public void closeOriginalFixture(){ready.set(false);if(opened!=null)opened.closeForMaintenance();}
    private JSONObject harness(String worker)throws Exception{return new JSONObject().put("protocol","pocketworld.harness-selection.v1").put("workerId",worker).put("configurationRevision","a".repeat(64)).put("capabilityRevision","b".repeat(64)).put("ownerManagedExecution",true);}
    private JSONObject teamContext(String run)throws Exception{return new JSONObject().put("protocol","pocketworld.team-tools.v1").put("runId",run).put("hostTaskId",run).put("memberId","original-member").put("turnId","original-turn").put("round",1).put("generation",0).put("runGeneration",0).put("roster",new org.json.JSONArray().put(new JSONObject().put("id","original-member").put("name","Pi")));}
    @Test public void privateGenericContinuationRetainsOmittedSelectionInsteadOfReportedDefaults()throws Exception {
        TaskStore store=store();String operation=UUID.randomUUID().toString(),worker="acp_fixture";
        JSONObject request=new JSONObject().put("profileId","paired").put("environmentId","workspace").put("worker",worker).put("readOnly",false).put("externalExecutionRiskAcknowledged",true).put("harnessSelection",harness(worker));store.beginExternal("remote","submitJob",operation,request);
        JSONObject receipt=new JSONObject().put("operationId",operation).put("id","generic-job").put("profileId","paired").put("environmentId","workspace").put("environmentBinding","c".repeat(64)).put("worker",worker).put("serviceBase","https://computer.example").put("status","completed").put("protocol","acp_v1").put("protocolVerified",true).put("promptTerminalConfirmed",true).put("nativeSettled",true).put("resumeSupported",true).put("sessionId","generic-session").put("threadId","generic-session").put("promptRequestId","generic-prompt").put("model",JSONObject.NULL).put("reportedModel","vendor-default-model").put("modeId","vendor-default-mode").put("harnessSelection",harness(worker)).put("controls",new JSONObject().put("threadResume",true));store.updateExternal(operation,receipt);
        JSONObject source=store.remoteOwnerTarget(operation),next=store.prepareRemoteContinuation(source,"Continue within the original selection"),claimed=store.claimRemoteContinuation(operation,next.getString("operationId"));assertFalse(claimed.has("model"));assertFalse(claimed.has("modeId"));assertEquals("c".repeat(64),claimed.getString("environmentBinding"));assertEquals("generic-session",claimed.getString("threadId"));
    }
    @Test public void teamOnlyPiRequiresOriginalContextAndActualCleanupBeforeResume()throws Exception {
        TaskStore store=store();String run=UUID.randomUUID().toString(),first=UUID.randomUUID().toString();store.beginExternal("teams","start",run,new JSONObject().put("goal","bounded Pi collaboration"));
        JSONObject binding=new JSONObject().put("worker","pi").put("readOnly",false).put("externalExecutionRiskAcknowledged",true).put("profileId","paired").put("environmentId","workspace");JSONObject request=new JSONObject().put("runId",run).put("hostTaskId",run).put("memberId","original-member").put("round",1).put("generation",0).put("operationId",first).put("teamContext",teamContext(run));store.prepareTeamChild(request,binding);
        JSONObject receipt=new JSONObject().put("operationId",first).put("id","pi-job").put("profileId","paired").put("environmentId","workspace").put("worker","pi").put("serviceBase","https://computer.example").put("status","completed").put("protocol","pi_rpc_v1").put("protocolVerified",true).put("nativeSettled",true).put("sessionId","pi-session").put("threadId","pi-session").put("promptRequestId","pi-prompt").put("noTools",false).put("teamTools",true).put("teamToolsOnly",true).put("codingToolsSupported",false).put("resourcesHeld",false).put("processCleanupProof",new JSONObject().put("processGroupStoppedVerified",true)).put("teamContext",teamContext(run)).put("output","Actual Pi team result");
        assertThrows(SecurityException.class,()->store.updateExternal(first,new JSONObject(receipt.toString()).put("teamContext",teamContext(run).put("turnId","another-turn"))));store.updateExternal(first,receipt);assertTrue(store.remoteOwnerTarget(first).getBoolean("teamToolsOnly"));
        JSONObject source=new JSONObject().put("childTaskId",first).put("jobId","pi-job").put("operationId",first).put("threadId","pi-session").put("serviceBase","https://computer.example").put("profileId","paired").put("environmentId","workspace").put("worker","pi");JSONObject next=new JSONObject(request.toString()).put("operationId",UUID.randomUUID().toString()).put("round",2).put("sessionSource",source).put("teamContext",teamContext(run).put("turnId","next-turn").put("round",2));assertFalse(store.prepareTeamChild(next,binding).getBoolean("knownOperation"));
        String uncertain=UUID.randomUUID().toString();store.prepareTeamChild(new JSONObject(request.toString()).put("operationId",uncertain),binding);store.updateExternal(uncertain,new JSONObject(receipt.toString()).put("operationId",uncertain).put("id","uncertain-job").put("resourcesHeld",true).put("processCleanupProof",new JSONObject().put("processGroupStoppedVerified",false)));
        JSONObject uncertainSource=new JSONObject(source.toString()).put("childTaskId",uncertain).put("jobId","uncertain-job").put("operationId",uncertain);assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("sessionSource",uncertainSource),binding));
    }
    @Test public void genericMemberKeepsOriginalSqlIdentityAndCannotResumeChangedSelectionOrMode()throws Exception {
        TaskStore store=store();String run=UUID.randomUUID().toString(),first=UUID.randomUUID().toString(),worker="acp_fixture";store.beginExternal("teams","start",run,new JSONObject().put("goal","generic original team"));
        JSONObject binding=new JSONObject().put("worker",worker).put("readOnly",false).put("externalExecutionRiskAcknowledged",true).put("profileId","paired").put("environmentId","workspace").put("modeId","review").put("harnessSelection",harness(worker));
        JSONObject request=new JSONObject().put("runId",run).put("hostTaskId",run).put("memberId","original-member").put("round",1).put("generation",0).put("operationId",first);
        assertFalse(store.prepareTeamChild(request,binding).getBoolean("knownOperation"));assertTrue(store.prepareTeamChild(request,binding).getBoolean("knownOperation"));
        JSONObject receipt=new JSONObject().put("operationId",first).put("id","fixture-job").put("profileId","paired").put("environmentId","workspace").put("environmentBinding","c".repeat(64)).put("worker",worker).put("serviceBase","https://computer.example").put("status","completed").put("protocol","acp_v1").put("protocolVerified",true).put("promptTerminalConfirmed",true).put("nativeSettled",true).put("resumeSupported",true).put("threadId","fixture-session").put("sessionId","fixture-session").put("promptRequestId","fixture-prompt").put("harnessSelection",harness(worker)).put("modeId","review").put("readOnly",false).put("output","Actual original fixture result");
        assertThrows(SecurityException.class,()->store.updateExternal(first,new JSONObject(receipt.toString()).put("harnessSelection",harness(worker).put("configurationRevision","d".repeat(64)))));
        assertThrows(SecurityException.class,()->store.updateExternal(first,new JSONObject(receipt.toString()).put("promptTerminalConfirmed",false)));
        store.updateExternal(first,receipt);JSONObject source=store.remoteOwnerTarget(first);assertEquals("review",source.getString("modeId"));assertEquals("c".repeat(64),source.getString("environmentBinding"));assertEquals(first,source.getString("jobOperationId"));
        JSONObject anchor=new JSONObject().put("childTaskId",first).put("jobId","fixture-job").put("operationId",first).put("threadId","fixture-session").put("serviceBase","https://computer.example").put("profileId","paired").put("environmentId","workspace").put("worker",worker);
        JSONObject next=new JSONObject(request.toString()).put("operationId",UUID.randomUUID().toString()).put("round",2).put("sessionSource",anchor);
        assertThrows(SecurityException.class,()->store.prepareTeamChild(next,new JSONObject(binding.toString()).put("modeId","write")));
        assertThrows(SecurityException.class,()->store.prepareTeamChild(next,new JSONObject(binding.toString()).put("harnessSelection",harness(worker).put("capabilityRevision","d".repeat(64)))));
        assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("memberId","another-member"),binding));
        assertFalse(store.prepareTeamChild(next,binding).getBoolean("knownOperation"));assertEquals(first,store.get(next.getString("operationId"),false).getJSONObject("sessionSource").getString("childTaskId"));
    }
    @Test public void onlySameOriginalMemberMayResumeItsActualSessionAndUnknownCannotBecomeAnchor()throws Exception{
        TaskStore store=store();String run=UUID.randomUUID().toString(),first=UUID.randomUUID().toString();store.beginExternal("teams","start",run,new JSONObject().put("goal","mixed harness research"));
        JSONObject binding=new JSONObject().put("worker","claude").put("teamReadOnlyTools",true).put("externalExecutionRiskAcknowledged",true).put("profileId","paired").put("environmentId","workspace");
        JSONObject request=new JSONObject().put("runId",run).put("hostTaskId",run).put("memberId","claude-member").put("round",1).put("generation",0).put("operationId",first);
        store.prepareTeamChild(request,binding);assertTrue(store.prepareTeamChild(request,binding).getBoolean("knownOperation"));
        JSONObject receipt=new JSONObject().put("operationId",first).put("id","first-job").put("profileId","paired").put("environmentId","workspace").put("worker","claude").put("serviceBase","https://computer.example").put("status","completed").put("protocol","claude_stream_json").put("protocolVerified",true).put("sessionIdentityConfirmed",true).put("threadId","session-1").put("sessionId","session-1").put("teamReadOnlyTools",true).put("readOnly",true).put("output","actual member result");
        store.updateExternal(first,receipt);
        JSONObject source=new JSONObject().put("childTaskId",first).put("jobId","first-job").put("operationId",first).put("threadId","session-1").put("serviceBase","https://computer.example").put("profileId","paired").put("environmentId","workspace").put("worker","claude");
        JSONObject next=new JSONObject(request.toString()).put("operationId",UUID.randomUUID().toString()).put("round",2).put("sessionSource",source);
        assertFalse(store.prepareTeamChild(next,binding).getBoolean("knownOperation"));assertEquals(first,store.get(next.getString("operationId"),false).getJSONObject("sessionSource").getString("childTaskId"));
        assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("memberId","other-member"),binding));
        JSONObject wrong=new JSONObject(source.toString()).put("threadId","another-session");assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("sessionSource",wrong),binding));
        String unknown=UUID.randomUUID().toString();store.prepareTeamChild(new JSONObject(request.toString()).put("operationId",unknown),binding);store.updateExternal(unknown,new JSONObject(receipt.toString()).put("operationId",unknown).put("id","unknown-job").put("status","unknown"));
        JSONObject uncertain=new JSONObject(source.toString()).put("childTaskId",unknown).put("operationId",unknown).put("jobId","unknown-job");assertThrows(SecurityException.class,()->store.prepareTeamChild(new JSONObject(next.toString()).put("operationId",UUID.randomUUID().toString()).put("sessionSource",uncertain),binding));
    }
}
