package dev.pocketengine.features.remote;

import android.app.Application;
import com.sun.net.httpserver.HttpExchange;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original SQL/native owner/RemoteModule/HTTP. Server is an explicit protocol fixture;
 * real configuration files and original process cleanup are covered by Python integration tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class NativeHarnessConfigsTest {
    static final class Fixture implements AutoCloseable {
        final RemoteOwnerWorkflowIntegrationTest.Fixture original=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude");
        final Object owner=new Object();final String window=UUID.randomUUID().toString();
        final AtomicInteger applies=new AtomicInteger();final Map<String,JSONObject> plans=new ConcurrentHashMap<>(),receipts=new ConcurrentHashMap<>();
        volatile boolean dropAck,wrongBinding;
        Fixture()throws Exception {
            // Existing fixture injects its virtual CredentialReader, not an Android keystore.
            // Provision only the corresponding contains-slot marker so the real production
            // profile/credential-presence guard is exercised, never bypassed or mocked.
            java.net.URI origin=java.net.URI.create(original.server.base());byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(("http://127.0.0.1:"+origin.getPort()).getBytes(StandardCharsets.UTF_8));StringBuilder suffix=new StringBuilder();for(int i=0;i<16;i++)suffix.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]));
            RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",0).edit().putString("remote.token.computer."+suffix,"fixture-ciphertext-presence-only").commit();
            original.server.http.createContext("/v1/harnesses/probe",e->{try{JSONObject request=body(e),identity=new JSONObject().put("family","claude").put("executable","/fixture-only/claude").put("version","2.1.259 (Claude Code)").put("configSchemaVersion","claude-settings-v1").put("adapterRevision","pocketworld-config-1").put("executionSite","COMPANION").put("scope","PROJECT_SESSION").put("bindingId",request.getString("bindingId")).put("environmentBinding",request.getString("environmentBinding"));send(e,new JSONObject().put("identity",identity).put("identityDigest","d".repeat(64)).put("configRoot","/fixture-only/.claude").put("capabilities",new JSONObject().put("configWritable",true)));}catch(Exception ex){throw new RuntimeException(ex);}});
            original.server.http.createContext("/v1/harness-config",e->{try{
                String path=e.getRequestURI().getPath();JSONObject request=e.getRequestMethod().equals("POST")?body(e):new JSONObject(),value;
                if(path.equals("/v1/harness-config/plans")){
                    JSONObject probe=new JSONObject().put("family","claude").put("bindingId",request.getString("bindingId")).put("environmentBinding",request.getString("environmentBinding"));String op=request.getString("operationId");
                    JSONObject operation=new JSONObject().put("kind","set_field").put("field","model").put("before",new JSONObject().put("value","old")).put("after",new JSONObject().put("value",request.getString("model")));
                    JSONObject filePlan=new JSONObject().put("pathRef","settings.json").put("operations",new JSONArray().put(operation));
                    value=new JSONObject().put("planId",op).put("operationId",op).put("planDigest","e".repeat(64)).put("identity",probe).put("environmentId",request.getString("environmentId")).put("profileDigest",request.getString("profileDigest")).put("endpointLeaseRef",request.getString("endpointLeaseRef")).put("credentialRefType","GATEWAY_ACCESS").put("gatewayScope",new JSONObject().put("audience","OWNER_PRIVATE").put("purpose","EXTERNAL_RELAY").put("billingBackendVerified",false)).put("writeConsistency","OPTIMISTIC").put("expiresAt",System.currentTimeMillis()+300000).put("runtimeVerified",false).put("conflicts",new JSONArray()).put("scopeDelta",new JSONObject().put("scope","PROJECT_SESSION").put("configRoot","/fixture-only/.claude")).put("filePlans",new JSONArray().put(filePlan));plans.put(op,value);
                }else if(path.endsWith("/apply")){
                    applies.incrementAndGet();String op=request.getString("operationId");value=new JSONObject().put("operationId",op).put("planDigest",request.getString("planDigest")).put("state","COMMITTED").put("configApplied",true).put("effectiveReadback",true).put("runtimeVerified",false);for(String key:new String[]{"environmentId","environmentBinding","bindingId"})value.put(key,request.get(key));if(wrongBinding)value.put("environmentBinding","f".repeat(64));receipts.put(op,value);if(dropAck){dropAck=false;e.close();return;}
                }else{
                    String op=path.substring(path.lastIndexOf('/')+1);value=receipts.get(op);if(value==null)value=new JSONObject().put("operationId",op).put("state",plans.containsKey(op)?"PREPARED":"not_found");if(plans.containsKey(op)&&!receipts.containsKey(op))value.put("plan",plans.get(op));
                }
                send(e,value);
            }catch(Exception ex){throw new RuntimeException(ex);}});
            original.workflow.setForeground(owner,true);
        }
        static JSONObject body(HttpExchange e)throws Exception{return new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));}
        static void send(HttpExchange e,JSONObject value)throws Exception{byte[] bytes=new JSONObject().put("ok",true).put("result",value).toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();}
        JSONObject action()throws Exception{return new JSONObject().put("windowId",window).put("taskId",original.task);}
        JSONObject selection()throws Exception{return new JSONObject().put("bindingId","fixture-claude").put("model","fixture-model").put("profileDigest","a".repeat(64)).put("endpointLeaseRef","fixture-gateway-reference");}
        JSONObject open()throws Exception{return original.workflow.handle(owner,"openHarnessConfig",action().put("selection",selection())).getJSONObject("snapshot");}
        JSONObject preview()throws Exception{open();return original.workflow.handle(owner,"previewHarnessConfig",action()).getJSONObject("snapshot");}
        JSONObject confirm(JSONObject preview)throws Exception{return original.workflow.handle(owner,"confirmHarnessConfig",action().put("acceptedPlanDigest",preview.getJSONObject("plan").getString("planDigest"))).getJSONObject("snapshot");}
        public void close(){original.close();}
    }

    @Test public void originalSqlPreparedExactNativeConfirmationOneApplyAndColdReadbackNoNewRun()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject preview=f.preview();String operation=preview.getString("operationId");
            assertTrue(f.original.tasks.hasUnresolved(f.original.task));assertEquals(0,f.applies.get());assertFalse(preview.getBoolean("executionAuthorized"));
            JSONObject result=f.confirm(preview);assertEquals("COMMITTED",result.getJSONObject("receipt").getString("state"));assertEquals(1,f.applies.get());
            assertFalse(f.original.tasks.hasUnresolved(f.original.task));assertEquals(f.original.run,f.original.tasks.get(f.original.task,false).getString("runId"));assertEquals(1,f.original.tasks.list().length());
            TaskStore cold=new TaskStore(RuntimeEnvironment.getApplication());assertEquals("receipt",cold.harnessConfigOperation(f.original.task,operation).getString("state"));
            f.confirm(preview);assertEquals(1,f.applies.get());assertEquals(1,f.original.server.submits.get());
        }
    }
    @Test public void leavingForegroundAndWrongDigestCannotCreateAnApplyClaim()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject preview=f.preview();assertThrows(SecurityException.class,()->f.original.workflow.handle(f.owner,"confirmHarnessConfig",f.action().put("acceptedPlanDigest","f".repeat(64))));
            f.original.workflow.setForeground(f.owner,false);assertThrows(SecurityException.class,()->f.confirm(preview));assertEquals(0,f.applies.get());
            assertEquals("PREVIEW_ONLY",f.original.tasks.harnessConfigOperation(f.original.task,preview.getString("operationId")).getString("nativeDispatchState"));
        }
    }
    @Test public void lostApplyAckAndReopenedNativePageOnlyQueryOriginalOperation()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject preview=f.preview();String operation=preview.getString("operationId");f.dropAck=true;JSONObject result=f.confirm(preview);
            assertEquals("unknown",result.getJSONObject("receipt").getString("state"));assertTrue(f.original.tasks.hasUnresolved(f.original.task));assertEquals(1,f.applies.get());
            f.original.workflow.close(f.window);TaskStore cold=new TaskStore(RuntimeEnvironment.getApplication());RemoteOwnerWorkflows next=new RemoteOwnerWorkflows(f.original.host,cold,event->{});Object nextOwner=new Object();next.setForeground(nextOwner,true);
            JSONObject recovered=next.handle(nextOwner,"openHarnessConfig",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",f.original.task).put("operationId",operation)).getJSONObject("snapshot");
            assertEquals("COMMITTED",recovered.getJSONObject("receipt").getString("state"));assertFalse(cold.hasUnresolved(f.original.task));assertEquals(1,f.applies.get());assertEquals(1,cold.list().length());
        }
    }
    @Test public void exactTargetMismatchCannotSettleSqlOrClaimRuntimeSuccess()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject preview=f.preview();f.wrongBinding=true;assertThrows(SecurityException.class,()->f.confirm(preview));assertTrue(f.original.tasks.hasUnresolved(f.original.task));assertEquals(1,f.applies.get());
        }
    }
    @Test public void previewCancellationHasOriginalNoDispatchProofAndModelJsonCannotApply()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject preview=f.preview();assertThrows(IllegalArgumentException.class,()->f.original.remote.handle("harnessConfigApply",new JSONObject()));
            assertThrows(SecurityException.class,()->f.original.workflow.handle(f.owner,"queryHarnessConfig",f.action().put("source",new JSONObject())));
            JSONObject cancelled=f.original.workflow.handle(f.owner,"cancelHarnessConfigPreview",f.action()).getJSONObject("snapshot");assertEquals("CANCELLED_PREVIEW",cancelled.getJSONObject("receipt").getString("state"));assertFalse(f.original.tasks.hasUnresolved(f.original.task));assertEquals(0,f.applies.get());
            assertThrows(SecurityException.class,()->f.confirm(preview));
        }
    }
}
