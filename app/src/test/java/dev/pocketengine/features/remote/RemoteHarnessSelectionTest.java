package dev.pocketengine.features.remote;

import dev.pocketengine.features.teams.AgentSlot;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteHarnessSelectionTest {
    static JSONObject selection(String worker)throws Exception{return new JSONObject().put("protocol",RemoteHarnessSelection.PROTOCOL).put("workerId",worker).put("configurationRevision","a".repeat(64)).put("capabilityRevision","b".repeat(64)).put("ownerManagedExecution",true);}
    static JSONObject probe(String worker)throws Exception{return new JSONObject().put("worker",worker).put("available",true).put("protocolVerified",true).put("protocol",RemoteHarnessSelection.wireProtocol(worker)).put("executionPolicy","owner_managed").put("configurationRevision","a".repeat(64)).put("capabilityRevision","b".repeat(64)).put("capabilities",new JSONObject().put("projectProduction",true).put("assignmentWorkspace",true));}
    static JSONObject terminal(String worker)throws Exception{return new JSONObject().put("worker",worker).put("status","completed").put("protocol",RemoteHarnessSelection.wireProtocol(worker)).put("protocolVerified",true).put("promptTerminalConfirmed",true).put("nativeSettled",true).put("sessionId","session-1").put("threadId","session-1").put("promptRequestId","prompt-1").put("resumeSupported",true).put("harnessSelection",selection(worker));}
    @Test public void configuredIdsAreExtensibleButNeverAnExecutionProof()throws Exception {
        assertTrue(RemoteHarnessSelection.isGenericWorker("acp_owner_adapter"));assertTrue(RemoteHarnessSelection.isGenericWorker("muse_msp"));assertFalse(RemoteHarnessSelection.isGenericWorker("acp_../../shell"));assertFalse(RemoteHarnessSelection.isGenericWorker("acp_Kimi"));assertFalse(RemoteHarnessSelection.isGenericWorker("acp_7custom"));assertFalse(RemoteHarnessSelection.isGenericWorker("acp_"+"a".repeat(66)));
        assertFalse(RemoteHarnessSelection.isProductionWorker("pi"));assertEquals("muse_msp_v1",RemoteHarnessSelection.wireProtocol("muse_msp"));
        assertThrows(SecurityException.class,()->RemoteHarnessSelection.fromProbe(probe("acp_owner_adapter").put("protocolVerified",false),"acp_owner_adapter"));
        assertThrows(SecurityException.class,()->RemoteHarnessSelection.checked(selection("acp_owner_adapter").put("command","bash"),"acp_owner_adapter"));
    }
    @Test public void originalSettledSessionAndConfigurationAreRequiredForResume()throws Exception {
        String worker="acp_gemini";RemoteHarnessSelection.requireResume(terminal(worker),selection(worker),worker);
        for(String key:new String[]{"nativeSettled","promptTerminalConfirmed","protocolVerified","resumeSupported"})assertThrows(SecurityException.class,()->RemoteHarnessSelection.requireResume(terminal(worker).put(key,false),selection(worker),worker));
        assertThrows(SecurityException.class,()->RemoteHarnessSelection.requireResume(terminal(worker),selection(worker).put("configurationRevision","c".repeat(64)),worker));
        assertThrows(SecurityException.class,()->RemoteHarnessSelection.requireResume(terminal(worker).put("threadId","another-session"),selection(worker),worker));
    }
    @Test public void ownerRequestCarriesOnlyObservedSelectionAndExplicitActualExecution()throws Exception {
        JSONObject request=new JSONObject().put("profileId","computer").put("bindingHash","c".repeat(64)).put("environmentId","project").put("environmentBinding","d".repeat(64)).put("worker","acp_kimi").put("prompt","Review the project").put("readOnly",false).put("externalExecutionRiskAcknowledged",true).put("harnessSelection",selection("acp_kimi")).put("modeId","default");
        JSONObject result=RemoteTaskRequest.firstForPrivateConnection(request);assertEquals("acp_kimi",result.getString("worker"));assertFalse(result.getBoolean("readOnly"));assertEquals("default",result.getString("modeId"));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.firstForPrivateConnection(new JSONObject(request.toString()).put("readOnly",true)));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.firstForPrivateConnection(new JSONObject(request.toString()).put("externalExecutionRiskAcknowledged",false)));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.firstForPrivateConnection(new JSONObject(request.toString()).put("command","unreviewed")));
        JSONObject member=new JSONObject().put("id","author").put("name","Author").put("role","Implement the selected change").put("runner","acp_kimi").put("modeId","default").put("externalExecutionRiskAcknowledged",true).put("device",new JSONObject().put("profileId","computer").put("environmentId","project"));
        assertEquals("default",AgentSlot.normalize(member,0).getString("modeId"));
    }
    @Test public void approvalCannotCrossASessionOrPrompt()throws Exception {
        JSONObject source=terminal("acp_kimi"),pending=new JSONObject().put("requestId","permission-1").put("requestHash","d".repeat(64)).put("protocol","acp_v1").put("method","acp/session/request_permission").put("sessionId","session-1").put("promptRequestId","prompt-1").put("canAccept",true);
        RemoteHarnessSelection.checkedApproval(source,pending);
        assertThrows(SecurityException.class,()->RemoteHarnessSelection.checkedApproval(source,new JSONObject(pending.toString()).put("promptRequestId","old-prompt")));
        assertThrows(SecurityException.class,()->RemoteHarnessSelection.checkedApproval(source,new JSONObject(pending.toString()).put("method","arbitrary/execute")));
    }
}
