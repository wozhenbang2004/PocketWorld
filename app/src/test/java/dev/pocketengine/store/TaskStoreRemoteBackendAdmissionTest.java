package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.remote.HarnessCapabilityObservation;
import java.lang.reflect.Field;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native TaskStore SQL. The synthetic selection is data, not a RemoteModule dispatch grant. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreRemoteBackendAdmissionTest {
    private TaskStore store;
    @Before public void prepare()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");store=new TaskStore(app);
    }
    @After public void close()throws Exception {if(store!=null&&database().isOpen())database().close();}
    private SQLiteDatabase database()throws Exception {Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);}
    private static JSONObject selection()throws Exception {
        JSONObject install=new JSONObject().put("format","pocketworld.remote-install-observation.v1").put("state","observed").put("identity","a".repeat(64)).put("kind","fixed_node_package_bytes_v1").put("packageVersion","0.66.0")
                .put("nodeContentVerified",true).put("packageContentVerified",true).put("executionChainVerified",false).put("osSandboxVerified",false).put("reason","fixed_bytes_observed");
        return new JSONObject().put("format","pocketworld.remote-backend-selection.v1").put("backendId","gemini_cli_acp_v1").put("backendVersion","0.66.0").put("protocolVersion",1).put("installObservation",install)
                .put("configurationRevision","b".repeat(64)).put("serviceCapabilityRevision","c".repeat(64)).put("capabilityRevision","d".repeat(64)).put("model","").put("modeId","default").put("readOnly",false);
    }
    private static JSONObject input()throws Exception {
        return new JSONObject().put("profileId","computer").put("_profileBinding","e".repeat(64)).put("serviceBase","http://127.0.0.1:8911").put("environmentId","workspace").put("environmentBinding","f".repeat(64)).put("worker","gemini_acp")
                .put("prompt","Implement the reviewed private workspace change").put("timeoutSeconds",300).put("readOnly",false).put("backendId","gemini_cli_acp_v1").put("capabilityRevision","d".repeat(64)).put("modeId","default").put("backendSelection",selection());
    }
    private String begin(JSONObject input)throws Exception {String id=UUID.randomUUID().toString();store.beginExternal("remote","submitJob",id,input);return id;}
    private JSONObject row(String id)throws Exception {
        try(Cursor row=database().rawQuery("SELECT run_id,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=? AND tool='remote_job'",new String[]{id,id})){
            assertTrue(row.moveToFirst());JSONObject value=new JSONObject().put("runId",row.getString(0)).put("arguments",new JSONObject(row.getString(1))).put("state",row.getString(2));
            if(!row.isNull(3))value.put("receipt",new JSONObject(row.getString(3)));assertFalse(row.moveToNext());return value;
        }
    }
    private static JSONObject receipt(String id,String status)throws Exception {
        return new JSONObject().put("id","original-job").put("operationId",id).put("profileId","computer").put("serviceBase","http://127.0.0.1:8911").put("environmentId","workspace").put("environmentBinding","f".repeat(64)).put("worker","gemini_acp")
                .put("status",status).put("model","").put("readOnly",false).put("backendSelection",selection()).put("protocol","gemini_acp_v1").put("protocolVerified",true).put("sessionId","original-session").put("promptRequestId","4");
    }
    @Test public void firstCreatePersistsExactNativeTargetAndBodyHashBeforeAnyReceipt()throws Exception {
        JSONObject input=input();String id=begin(input);JSONObject original=row(id),args=original.getJSONObject("arguments"),target=args.getJSONObject("target");
        assertEquals("remote_job_prepared",original.getString("state"));assertFalse(original.has("receipt"));assertFalse(args.has("source"));assertEquals(id,original.getString("runId"));assertEquals(0,args.getInt("coordinatorEpoch"));
        assertEquals(input.getString("_profileBinding"),target.getString("profileBinding"));assertEquals(input.getString("serviceBase"),target.getString("serviceBase"));assertTrue(store.hasUnresolved(id));
        JSONObject body=new JSONObject().put("operationId",id).put("environmentId","workspace").put("environmentBinding","f".repeat(64)).put("worker","gemini_acp").put("prompt",input.getString("prompt")).put("model","").put("timeoutSeconds",300).put("readOnly",false).put("backendSelection",selection());
        assertEquals(TaskStore.capabilityFingerprint(new JSONObject().put("target",target).put("request",body)),args.getString("requestHash"));
        JSONObject source=store.remoteOwnerTarget(id);assertEquals("",source.getString("jobId"));assertEquals(id,source.getString("jobOperationId"));HarnessCapabilityObservation.requireSameBackendSelection(selection(),source.getJSONObject("backendSelection"));
        input.getJSONObject("backendSelection").put("capabilityRevision","9".repeat(64));HarnessCapabilityObservation.requireSameBackendSelection(selection(),row(id).getJSONObject("arguments").getJSONObject("backendSelection"));
        assertThrows(SecurityException.class,()->store.beginExternal("remote","submitJob",id,input()));
        try(Cursor run=database().rawQuery("SELECT epoch,grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{id,id})){assertTrue(run.moveToFirst());assertEquals(0,run.getInt(0));assertEquals(0,new JSONObject(run.getString(1)).getJSONArray("tools").length());}
    }
    @Test public void changedPromptModelSourceOrSelectionCannotConsumeTheOriginalPreparedClaim()throws Exception {
        for(String field:new String[]{"prompt","timeoutSeconds","serviceBase","environmentBinding","model","backendSelection"}){
            JSONObject original=input(),changed=new JSONObject(original.toString());String id=begin(original);
            switch(field){
                case "timeoutSeconds":changed.put(field,301);break;
                case "serviceBase":changed.put(field,"http://127.0.0.1:8912");break;
                case "environmentBinding":changed.put(field,"1".repeat(64));break;
                case "backendSelection":changed.getJSONObject(field).put("serviceCapabilityRevision","2".repeat(64));break;
                default:changed.put(field,"replacement");
            }
            assertThrows(field,SecurityException.class,()->store.claimRemoteBackendDispatch(id,id,changed));assertEquals("remote_job_prepared",row(id).getString("state"));
        }
    }
    @Test public void oneDispatchThenUnknownOnlyAcceptsTheExactOriginalReceiptWithoutAnotherRun()throws Exception {
        JSONObject input=input();String id=begin(input);store.claimRemoteBackendDispatch(id,id,input);assertEquals("remote_job_sending",row(id).getString("state"));
        assertThrows(SecurityException.class,()->store.claimRemoteBackendDispatch(id,id,input));store.remoteBackendAdmissionUnconfirmed(id,id,"HTTP result unknown");
        assertEquals("remote_job_unknown",row(id).getString("state"));assertEquals("reconciling",store.get(id,false).getString("status"));assertTrue(store.hasUnresolved(id));
        JSONObject missing=receipt(id,"running");missing.remove("backendSelection");assertThrows(SecurityException.class,()->store.updateExternal(id,missing));
        assertEquals("remote_job_unknown",row(id).getString("state"));store.updateExternal(id,receipt(id,"running"));assertEquals("remote_job_active",row(id).getString("state"));assertFalse(store.hasUnresolved(id));
        assertEquals("original-job",store.remoteOwnerTarget(id).getString("jobId"));assertEquals(id,store.get(id,false).getString("runId"));
        store.remoteBackendAdmissionUnconfirmed(id,id,"late local callback");assertEquals("remote_job_active",row(id).getString("state"));
        try(Cursor count=database().rawQuery("SELECT count(*) FROM runs WHERE task_id=?",new String[]{id})){assertTrue(count.moveToFirst());assertEquals(1,count.getInt(0));}
    }
    @Test public void unclaimedLocalFailureIsSettledAndLatePeerAdmissionCannotReplaceItsProof()throws Exception {
        String id=begin(input());store.remoteBackendAdmissionUnconfirmed(id,id,"Original owner window closed");JSONObject saved=row(id).getJSONObject("receipt");
        assertEquals("remote_job_receipt",row(id).getString("state"));assertTrue(saved.getJSONObject("nativeLocalDispatchProof").getBoolean("originalPreparedClaimNotConsumed"));assertFalse(saved.getBoolean("processStarted"));assertFalse(store.hasUnresolved(id));assertEquals("failed",store.get(id,false).getString("status"));
        store.updateExternal(id,receipt(id,"running"));assertEquals("failed",store.get(id,false).getString("status"));assertEquals("",store.remoteOwnerTarget(id).getString("jobId"));
        assertThrows(SecurityException.class,()->store.claimRemoteBackendDispatch(id,id,input()));
    }
    @Test public void cancellationAndOwnerExecutionRevocationDenyBeforeTheOnlyPost()throws Exception {
        JSONObject input=input();String cancelled=begin(input);store.requestCancel(cancelled,cancelled);assertThrows(SecurityException.class,()->store.claimRemoteBackendDispatch(cancelled,cancelled,input));
        store.remoteBackendAdmissionUnconfirmed(cancelled,cancelled,"Cancelled before native dispatch");assertEquals("cancelled",store.get(cancelled,false).getString("status"));assertFalse(store.hasUnresolved(cancelled));
        String stopped=begin(input);store.setExecutionEnabledForOwner(store.ownerExecutionState().getLong("revision"),false);assertThrows(SecurityException.class,()->store.claimRemoteBackendDispatch(stopped,stopped,input));assertEquals("remote_job_prepared",row(stopped).getString("state"));
    }
    @Test public void receiptCannotReplaceSelectionOrSourceOrForgeTheLocalNoDispatchProof()throws Exception {
        JSONObject input=input();String id=begin(input);store.claimRemoteBackendDispatch(id,id,input);
        for(String mutation:new String[]{"source","selection","localProof"}){
            JSONObject actual=receipt(id,"running");if(mutation.equals("source"))actual.put("environmentBinding","1".repeat(64));else if(mutation.equals("selection"))actual.getJSONObject("backendSelection").put("configurationRevision","2".repeat(64));else actual.put("nativeLocalDispatchProof",new JSONObject().put("originalPreparedClaimNotConsumed",true));
            assertThrows(SecurityException.class,()->store.updateExternal(id,actual));assertEquals("remote_job_sending",row(id).getString("state"));assertEquals("",store.remoteOwnerTarget(id).getString("jobId"));
        }
    }
    @Test public void permissionsThatWouldBeSilentlySanitizedNeverBecomeOwnerApprovalEvidence()throws Exception {
        for(boolean oversized:new boolean[]{false,true}){
            JSONObject input=input();String id=begin(input);store.claimRemoteBackendDispatch(id,id,input);
            JSONObject permission=new JSONObject().put("requestId",UUID.randomUUID().toString()).put("canAccept",true).put("toolCall",oversized?new JSONObject().put("title","x".repeat(12001)):new JSONObject().put("token","must not disappear from an actionable approval"));
            JSONObject actual=receipt(id,"awaiting_approval").put("pendingApprovals",new JSONArray().put(permission));assertThrows(SecurityException.class,()->store.updateExternal(id,actual));assertFalse(store.remoteOwnerTarget(id).has("pendingApprovals"));assertEquals("remote_job_sending",row(id).getString("state"));
        }
    }
    @Test public void coldRecoveryRetainsOriginalPinsAndCannotReclaimThePreparedOperation()throws Exception {
        JSONObject input=input();String id=begin(input);String hash=row(id).getJSONObject("arguments").getString("requestHash");database().close();store=new TaskStore(RuntimeEnvironment.getApplication());
        assertEquals("remote_job_unknown",row(id).getString("state"));assertEquals(hash,row(id).getJSONObject("arguments").getString("requestHash"));HarnessCapabilityObservation.requireSameBackendSelection(selection(),store.remoteOwnerTarget(id).getJSONObject("backendSelection"));
        assertThrows(SecurityException.class,()->store.claimRemoteBackendDispatch(id,id,input));store.remoteBackendAdmissionUnconfirmed(id,id,"Recovered dispatch unknown");assertFalse(row(id).getJSONObject("receipt").has("nativeLocalDispatchProof"));assertTrue(store.hasUnresolved(id));
    }
}
