package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Original native journal shape, not the shorter model-delivered receipt shape. */
public final class ProjectJournalReceiptTest {
    private static final String CHECKOUT="12345678-1234-1234-1234-123456789abc";
    private static final class Fixture implements EngineModule {
        final JSONObject source,plan,task,operation,backend;
        Fixture()throws Exception {
            source=new JSONObject().put("checkout",new JSONObject().put("id",CHECKOUT).put("projectId","project"))
                    .put("files",new JSONArray().put(new JSONObject().put("path","main.mjs").put("content","console.log('actual project output');\n")));
            plan=ProjectBuildPlan.create(this,new JSONObject().put("checkoutId",CHECKOUT).put("runtimeId","managed_process_v1").put("intent","run").put("expectedSourceDigest",ProjectSources.describe(source).getString("sourceDigest")));
            JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,"run")),binding=new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",1).put("operationId","operation");
            RuntimeExecutionRef ref=RuntimeExecutionRef.fromHost(exact,binding);JSONObject route=ref.executionLink().put("jobId","operation");
            backend=new JSONObject().put("id","operation").put("operationId","operation").put("checkoutId",CHECKOUT).put("sourceDigest",ref.sourceDigest())
                    .put("kind","command").put("provider","managed_process").put("projectExecution",ref.executionLink())
                    .put("requestedExecutable",exact.getJSONObject("payload").getString("executable")).put("requestedArgv",exact.getJSONObject("payload").getJSONArray("argv"))
                    .put("status","awaiting_receipt").put("receiptReceived",false).put("ownedWorkerLive",true).put("observation","owned_worker_live")
                    .put("sourceCheckStatus","pre_exec_manifest_verified").put("stdout","actual service log\nhttp://127.0.0.1:12345 is only program output\n").put("stderr","actual stderr\n")
                    .put("processExited",false).put("processGroupAbsent",false).put("resourcesReleased",false).put("cancelRequested",false).put("summaryOutputOnly",true).put("summaryOutputTruncated",true).put("logCursor",12);
            operation=new JSONObject().put("operationId","operation").put("runId","run").put("tool","project_execute").put("state","unknown")
                    .put("arguments",new JSONObject().put("plan",plan).put("stepId","run"))
                    .put("receipt",new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("projectExecution",route).put("executionReceipt",backend));
            task=new JSONObject().put("id","task").put("kind","assistant").put("actorId","owner").put("spaceId","private")
                    .put("developmentContext",new JSONObject().put("projectId","project").put("checkoutId",CHECKOUT).put("runtime","managed_process_v1"));
        }
        public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject input)throws Exception {
            switch(command){
                case "export":return ProjectSources.copy(source);
                case "probe":return new JSONObject().put("termux",new JSONObject().put("installed",false));
                case "listJobs":return new JSONObject().put("termuxJobs",new JSONArray());
                case "project.runtime.describe":return new JSONObject().put("identity","a".repeat(64)).put("canExecute",true).put("tools",new JSONArray().put(new JSONObject().put("name","node").put("hashVerified",true)));
                default:throw new AssertionError("Observation must not execute "+command);
            }
        }
    }
    @Test public void nativeEnvelopeExposesActualOutputAndOwnershipWithoutClaimingServiceReadiness()throws Exception {
        Fixture f=new Fixture();JSONObject view=ProjectDevelopment.recordedReceipt(f.task,f.operation);
        assertEquals(f.backend.getString("stdout"),view.getString("stdout"));assertEquals("actual stderr\n",view.getString("stderr"));assertTrue(view.getBoolean("ownedWorkerLive"));
        assertFalse(view.getBoolean("receiptReceived"));assertFalse(view.getBoolean("stopConfirmed"));assertFalse(view.getBoolean("replayAllowed"));assertTrue(view.getBoolean("summaryOutputTruncated"));assertEquals(12,view.getInt("logCursor"));
        assertFalse(view.has("serviceReady"));assertFalse(view.has("verifiedUrl"));assertEquals("task",view.getJSONObject("binding").getString("taskId"));
        JSONObject query=ProjectDevelopment.originalPollRequest(f.task,f.operation);assertEquals("run",query.getString("stepId"));query.getJSONObject("plan").put("sourceDigest","changed");assertNotEquals("changed",f.plan.getString("sourceDigest"));
    }
    @Test public void aStopRequestOnlyBecomesConfirmedAfterActualExitGroupAndLeaseRelease()throws Exception {
        Fixture f=new Fixture();f.backend.put("cancelRequested",true).put("timedOut",true).put("terminationSignal",15).put("status","failed").put("exitCode",-1).put("receiptReceived",true);
        assertFalse(ProjectDevelopment.recordedReceipt(f.task,f.operation).getBoolean("stopConfirmed"));
        f.backend.put("processExited",true).put("processGroupAbsent",true);assertFalse(ProjectDevelopment.recordedReceipt(f.task,f.operation).getBoolean("stopConfirmed"));
        f.backend.put("resourcesReleased",true);JSONObject view=ProjectDevelopment.recordedReceipt(f.task,f.operation);assertTrue(view.getBoolean("stopConfirmed"));assertTrue(view.getBoolean("timedOut"));assertEquals(15,view.getInt("terminationSignal"));
    }
    @Test public void queryRequiresTheOriginalTaskRoutePlanAndBackendBinding()throws Exception {
        Fixture f=new Fixture();
        for(String key:new String[]{"taskId","runId","operationId","checkoutId","sourceDigest","requestDigest","runtimeIdentity"}){
            JSONObject wrong=ProjectSources.copy(f.operation);wrong.getJSONObject("receipt").getJSONObject("projectExecution").put(key,"wrong");
            assertThrows(key,SecurityException.class,()->ProjectDevelopment.originalPollRequest(f.task,wrong));
            JSONObject wrongBackend=ProjectSources.copy(f.operation);wrongBackend.getJSONObject("receipt").getJSONObject("executionReceipt").getJSONObject("projectExecution").put(key,"wrong");
            assertThrows(key,SecurityException.class,()->ProjectDevelopment.recordedReceipt(f.task,wrongBackend));
        }
        JSONObject other=ProjectSources.copy(f.task);other.getJSONObject("developmentContext").put("projectId","another");assertThrows(SecurityException.class,()->ProjectDevelopment.recordedReceipt(other,f.operation));
        JSONObject remote=ProjectSources.copy(f.task).put("origin",new JSONObject().put("kind","channel"));assertThrows(SecurityException.class,()->ProjectDevelopment.originalPollRequest(remote,f.operation));
    }
    @Test public void aDispatchWithoutBackendStillAllowsOnlyAnExactOriginalQuery()throws Exception {
        Fixture f=new Fixture();f.operation.getJSONObject("receipt").remove("executionReceipt");assertNull(ProjectDevelopment.recordedReceipt(f.task,f.operation));
        assertEquals("operation",ProjectDevelopment.originalPollRequest(f.task,f.operation).getJSONObject("binding").getString("operationId"));
    }
}
