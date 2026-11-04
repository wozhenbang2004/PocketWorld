package dev.pocketengine.features.remote;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.store.OriginalRemoteStopClaim;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual native Module, FeatureHost, SQLite and owner workflow against local HTTP, without a real CLI/account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RemoteBackendNativeJourneyTest {
    private static final String ENVIRONMENT="a".repeat(64);
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}

    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final Map<String,JSONObject> jobs=new ConcurrentHashMap<>(),operations=new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<JSONObject> submissions=new CopyOnWriteArrayList<>(),approvals=new CopyOnWriteArrayList<>(),cancellations=new CopyOnWriteArrayList<>();
        final AtomicInteger environmentReads=new AtomicInteger(),probes=new AtomicInteger(),queries=new AtomicInteger();
        volatile boolean writeAllowed=true,workerAvailable=true,expiredProbe,dropSubmit,dropApproval,failQuery;
        volatile String kind="local",initialStatus="completed",badReceipt="";
        Server()throws Exception {
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/",exchange->{try{
                String path=exchange.getRequestURI().getPath();boolean post=exchange.getRequestMethod().equals("POST");
                JSONObject body=post?new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)):new JSONObject(),value;
                if(!post&&failQuery&&path.startsWith("/v1/operations/")){queries.incrementAndGet();exchange.sendResponseHeaders(503,0);return;}
                if(!post&&path.equals("/v1/environments")){
                    environmentReads.incrementAndGet();JSONArray workers=new JSONArray();for(String worker:new String[]{"codex_app_server","claude","pi","gemini_acp"})workers.put(new JSONObject().put("id",worker).put("available",worker.equals("gemini_acp")?workerAvailable:true));
                    value=new JSONObject().put("environments",new JSONArray().put(new JSONObject().put("id","workspace").put("kind",kind).put("bindingHash",ENVIRONMENT).put("writeAllowed",writeAllowed).put("workers",workers)));
                }else if(post&&path.equals("/v1/workers/probe")){
                    probes.incrementAndGet();value=HarnessCapabilityObservationTest.geminiProbe();JSONObject observed=value.getJSONObject("backendObservation");long at=System.currentTimeMillis()-(expiredProbe?300001:0);
                    String model=body.optString("model","");observed.put("observedAt",at).put("expiresAt",at+300000).put("requestedModel",model);observed.getJSONObject("model").put("requested",model);
                }else if(post&&path.equals("/v1/jobs")){
                    submissions.add(copy(body));String job="job-"+submissions.size();value=job(body,job);jobs.put(job,copy(value));operations.put(value.getString("operationId"),copy(value));
                    if(dropSubmit){dropSubmit=false;exchange.close();return;}
                    if(badReceipt.equals("selection"))value.getJSONObject("backendSelection").put("serviceCapabilityRevision","9".repeat(64));
                    if(badReceipt.equals("environmentBinding"))value.remove("environmentBinding");
                }else if(post&&path.endsWith("/approval")){
                    approvals.add(copy(body));String job=path.split("/")[3];value=copy(jobs.get(job));
                    if(dropApproval){dropApproval=false;exchange.close();return;}
                    value.put("pendingApprovals",new JSONArray()).put("approvalReceipts",new JSONArray().put(new JSONObject().put("requestId",body.getString("requestId")).put("decision",body.getString("decision")).put("status","sent"))).put("status","running");jobs.put(job,copy(value));operations.put(value.getString("operationId"),copy(value));
                }else if(post&&path.endsWith("/cancel-original")){
                    cancellations.add(copy(body));String job=path.split("/")[3];value=copy(jobs.get(job));
                    value.put("status","cancelled").put("dispatchFenceFinal",true).put("resourcesHeld",false).put("originalCancellation",new JSONObject().put("protocol",RemoteOriginalCancellation.PROTOCOL).put("jobId",job).put("jobOperationId",body.getString("jobOperationId")).put("identityMatched",true).put("effectsReversed",false));jobs.put(job,copy(value));operations.put(value.getString("operationId"),copy(value));
                }else if(!post&&path.equals("/v1/status")){
                    value=new JSONObject().put("originalJobCancellation",new JSONObject().put("supported",true).put("protocol",RemoteOriginalCancellation.PROTOCOL).put("endpoint","/v1/jobs/{jobId}/cancel-original").put("identityCheck","locked_job_operation_before_mutation").put("scope","job_manager_cli_jobs_only").put("requiredFields",new JSONArray().put("jobOperationId")));
                }else if(!post&&path.startsWith("/v1/jobs/")){
                    queries.incrementAndGet();value=jobs.get(path.substring("/v1/jobs/".length()));
                }else if(!post&&path.startsWith("/v1/operations/")){
                    queries.incrementAndGet();value=operations.get(path.substring("/v1/operations/".length()));
                }else value=null;
                if(value==null)value=new JSONObject().put("status","not_found");byte[] bytes=new JSONObject().put("ok",true).put("result",value).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){exchange.sendResponseHeaders(500,0);}finally{exchange.close();}});
            http.start();
        }
        private JSONObject job(JSONObject body,String id)throws Exception {
            JSONObject value=new JSONObject().put("id",id).put("operationId",body.getString("operationId")).put("environmentId",body.getString("environmentId")).put("environmentBinding",body.getString("environmentBinding")).put("worker",body.getString("worker"))
                .put("backendSelection",copy(body.getJSONObject("backendSelection"))).put("model",body.getString("model")).put("readOnly",false).put("status",initialStatus)
                .put("protocol","gemini_acp_v1").put("protocolVerified",true).put("controlProtocolVerified",true).put("sessionIdentityConfirmed",true).put("sessionId","original-session").put("promptRequestId","original-prompt")
                .put("controls",new JSONObject().put("threadResume",false)).put("pendingApprovals",new JSONArray()).put("approvalReceipts",new JSONArray()).put("remoteEffectsVerified",false);
            if(initialStatus.equals("awaiting_approval"))value.getJSONArray("pendingApprovals").put(HarnessCapabilityObservationTest.geminiApproval());return value;
        }
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort();}
        public void close(){http.stop(0);}
    }

    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final Server server=new Server();final TaskStore tasks;final FeatureHost host;final RemoteModule remote;final RemoteOwnerWorkflows workflow;final Object owner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();final List<TaskStore> stores=new ArrayList<>();
        volatile RemoteModule.DispatchGate before=path->{};
        Fixture()throws Exception {
            app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");tasks=new TaskStore(app);stores.add(tasks);host=new FeatureHost(events::add,tasks,()->false);
            remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{events.add(event);host.recordEvent(event);}),(id,base)->"fixture-token-longer-than-32-characters",path->before.beforeDispatch(path));
            remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","原电脑").put("baseUrl",server.base()));host.register(remote);workflow=new RemoteOwnerWorkflows(host,tasks,events::add);workflow.setForeground(owner,true);
        }
        JSONObject input()throws Exception{return new JSONObject().put("profileId","computer").put("_profileBinding",remote.profileBindingHash("computer")).put("environmentId","workspace").put("environmentBinding",ENVIRONMENT).put("worker","gemini_acp").put("prompt","原私人目标").put("timeoutSeconds",300).put("readOnly",false).put("backendId","gemini_cli_acp_v1").put("modeId","default");}
        JSONObject observed()throws Exception {
            JSONObject input=input();remote.handle("environments",new JSONObject().put("profileId","computer").put("_profileBinding",input.getString("_profileBinding")));
            JSONObject actual=remote.handle("probeWorker",new JSONObject().put("profileId","computer").put("_profileBinding",input.getString("_profileBinding")).put("environmentId","workspace").put("environmentBinding",ENVIRONMENT).put("worker","gemini_acp"));return input.put("capabilityRevision",actual.getString("capabilityRevision"));
        }
        JSONObject prepared()throws Exception{JSONObject input=observed();input.put("backendSelection",remote.prepareBackendSelection(input));return input.put("operationId",UUID.randomUUID().toString());}
        JSONObject request(JSONObject payload,boolean nativeOwner)throws Exception {
            String id=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",id).put("payload",payload);
            if(nativeOwner)host.request(request,()->true);else host.request(request);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){for(JSONObject event:events)if(id.equals(event.optString("requestId")))return event;Thread.sleep(5);}throw new AssertionError("No original feature receipt: "+events);
        }
        String start()throws Exception {JSONObject result=request(observed(),true);assertTrue(result.toString(),result.getBoolean("ok"));return result.getJSONObject("result").getString("hostTaskId");}
        JSONObject open(String task)throws Exception{return workflow.handle(owner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject snapshot)throws Exception{return new JSONObject().put("windowId",snapshot.getString("windowId")).put("taskId",snapshot.getString("taskId")).put("reviewId",snapshot.getString("reviewId"));}
        public void close()throws Exception {
            host.close();remote.close();server.close();Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);for(TaskStore store:stores){SQLiteDatabase db=(SQLiteDatabase)field.get(store);if(db.isOpen())db.close();}
        }
    }

    @Test public void fourWorkerEnvironmentCapturesOnlyActualNativeSelection()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=f.observed();assertEquals(1,f.server.environmentReads.get());assertEquals(1,f.server.probes.get());JSONObject selection=f.remote.prepareBackendSelection(input);
            assertEquals(11,selection.length());assertEquals(f.server.base(),input.getString("serviceBase"));assertEquals(input.getString("capabilityRevision"),selection.getString("capabilityRevision"));assertEquals("",selection.getString("model"));assertFalse(selection.getBoolean("readOnly"));
            selection.put("model","forged");assertEquals("",f.remote.prepareBackendSelection(input).getString("model"));assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());assertEquals(0,f.server.submissions.size());
        }
    }
    @Test public void changedDraftSelectionOrNativeEnvironmentReadCannotConsumeProbe()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.observed();for(String key:new String[]{"_profileBinding","environmentId","environmentBinding","model","capabilityRevision","backendId","modeId","readOnly"}){
                JSONObject changed=copy(original).put(key,key.equals("readOnly")?true:"replacement");assertThrows(key,SecurityException.class,()->f.remote.prepareBackendSelection(changed));
            }
            f.remote.handle("environments",new JSONObject().put("profileId","computer"));assertThrows(SecurityException.class,()->f.remote.prepareBackendSelection(original));assertEquals(0,f.server.submissions.size());
        }
    }
    @Test public void readOnlyRemoteUnavailableOrExpiredObservationNeverEnablesFirstCreate()throws Exception {
        for(String blocked:new String[]{"write","ssh","unavailable","expired"})try(Fixture f=new Fixture()){
            if(blocked.equals("write"))f.server.writeAllowed=false;if(blocked.equals("ssh"))f.server.kind="ssh";if(blocked.equals("unavailable"))f.server.workerAvailable=false;if(blocked.equals("expired"))f.server.expiredProbe=true;
            JSONObject input=f.observed();assertThrows(blocked,SecurityException.class,()->f.remote.prepareBackendSelection(input));assertEquals(0,f.server.submissions.size());assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
        }
    }
    @Test public void firstPostUsesOneNativeFenceAndExistingOperationOnlyQueries()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=f.prepared();AtomicInteger fences=new AtomicInteger();JSONObject first=f.remote.submitBackendJob(input,fences::incrementAndGet);
            assertEquals("completed",first.getString("status"));assertEquals(1,fences.get());assertEquals(1,f.server.submissions.size());JSONObject wire=f.server.submissions.get(0);
            assertEquals(9,wire.length());assertEquals("",wire.getString("model"));assertEquals(ENVIRONMENT,wire.getString("environmentBinding"));assertFalse(wire.has("serviceBase"));assertFalse(wire.has("capabilityRevision"));HarnessCapabilityObservation.requireSameBackendSelection(input.getJSONObject("backendSelection"),wire.getJSONObject("backendSelection"));
            JSONObject again=f.remote.submitBackendJob(input,fences::incrementAndGet);assertEquals(first.getString("id"),again.getString("id"));assertEquals(1,fences.get());assertEquals(1,f.server.submissions.size());assertTrue(f.server.queries.get()>0);
            assertThrows(SecurityException.class,()->f.remote.handle("submitJob",input));assertEquals(1,f.server.submissions.size());
        }
    }
    @Test public void revokedWindowOrChangedProfileAtPhysicalBoundarySendsNothing()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=f.prepared();AtomicInteger fences=new AtomicInteger();assertThrows(SecurityException.class,()->f.remote.submitBackendJob(input,()->{fences.incrementAndGet();throw new SecurityException("Original window closed");}));assertEquals(1,fences.get());assertEquals(0,f.server.submissions.size());
        }
        try(Fixture f=new Fixture()){
            JSONObject input=f.prepared();AtomicInteger fences=new AtomicInteger();f.before=path->{if(path.equals("/v1/jobs"))f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","Replacement generation").put("baseUrl",f.server.base()));};
            assertThrows(SecurityException.class,()->f.remote.submitBackendJob(input,fences::incrementAndGet));assertEquals(0,fences.get());assertEquals(0,f.server.submissions.size());
        }
    }
    @Test public void missingOrChangedReceiptIdentityKeepsTheCapturedUnknownOperation()throws Exception {
        for(String malformed:new String[]{"selection","environmentBinding"})try(Fixture f=new Fixture()){
            JSONObject input=f.prepared();f.server.badReceipt=malformed;JSONObject result=f.remote.submitBackendJob(input,()->{});
            assertEquals("unknown",result.getString("status"));assertEquals(input.getString("operationId"),result.getString("operationId"));assertEquals(ENVIRONMENT,result.getString("environmentBinding"));assertFalse(result.has("jobId"));HarnessCapabilityObservation.requireSameBackendSelection(input.getJSONObject("backendSelection"),result.getJSONObject("backendSelection"));assertEquals(1,f.server.submissions.size());
        }
    }
    @Test public void lostFirstReplyIsQueriedByOriginalOperationWithoutAnotherPostOrProbe()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=f.prepared();f.server.dropSubmit=true;JSONObject result=f.remote.submitBackendJob(input,()->{});assertEquals("unknown",result.getString("status"));assertEquals(ENVIRONMENT,result.getString("environmentBinding"));
            HarnessCapabilityObservation.requireSameBackendSelection(input.getJSONObject("backendSelection"),result.getJSONObject("backendSelection"));
            f.server.failQuery=true;JSONObject unavailable=f.remote.handle("reconcileOperation",new JSONObject().put("profileId","computer").put("operationId",input.getString("operationId")));
            assertEquals("unknown",unavailable.getString("status"));assertEquals(ENVIRONMENT,unavailable.getString("environmentBinding"));HarnessCapabilityObservation.requireSameBackendSelection(input.getJSONObject("backendSelection"),unavailable.getJSONObject("backendSelection"));f.server.failQuery=false;
            JSONObject actual=f.remote.handle("reconcileOperation",new JSONObject().put("profileId","computer").put("operationId",input.getString("operationId")));
            assertEquals("job-1",actual.getString("id"));assertEquals(input.getString("operationId"),actual.getString("operationId"));assertEquals(1,f.server.submissions.size());assertEquals(1,f.server.probes.get());
        }
    }
    @Test public void sqlPreparedBeforeTransportReservationRecoversThroughOriginalGetOnly()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=f.prepared();String id=input.getString("operationId");f.tasks.beginExternal("remote","submitJob",id,input);assertEquals(0,f.remote.snapshot().getJSONArray("operations").length());
            TaskStore recovered=new TaskStore(f.app);f.stores.add(recovered);JSONObject source=recovered.remoteOwnerTarget(id);f.remote.close();
            RemoteModule cold=new RemoteModule(new FeatureContext(f.app,new SettingsStore(f.app),new SecretVault(f.app),event->{}),(profile,base)->"fixture-token-longer-than-32-characters",path->{});
            try{JSONObject actual=cold.readBackendJob(source,()->{});assertEquals("unknown",actual.getString("status"));assertTrue(actual.getBoolean("queryOnly"));assertFalse(actual.getBoolean("resubmitted"));assertEquals(id,actual.getString("operationId"));assertEquals(ENVIRONMENT,actual.getString("environmentBinding"));HarnessCapabilityObservation.requireSameBackendSelection(source.getJSONObject("backendSelection"),actual.getJSONObject("backendSelection"));
                recovered.updateExternal(id,actual);assertEquals(id,recovered.get(id,false).getString("runId"));assertEquals(0,cold.snapshot().getJSONArray("operations").length());assertEquals(0,cold.snapshot().getJSONArray("jobs").length());assertEquals(0,f.server.submissions.size());assertEquals(1,f.server.probes.get());assertEquals(1,f.server.environmentReads.get());
            }finally{cold.close();}
        }
    }
    @Test public void genericHostRequestCannotMintAdmissionButOriginalOwnerPathPersistsBeforePost()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject input=f.observed(),denied=f.request(input,false);assertFalse(denied.toString(),denied.getBoolean("ok"));assertEquals(0,f.tasks.list().length());assertEquals(0,f.server.submissions.size());
            AtomicInteger recorded=new AtomicInteger();f.before=path->{if(path.equals("/v1/jobs")){JSONArray tasks=f.tasks.list();assertEquals(1,tasks.length());JSONObject source=f.tasks.remoteOwnerTarget(tasks.getJSONObject(0).getString("id"));assertEquals("",source.getString("jobId"));assertEquals(input.getString("capabilityRevision"),source.getJSONObject("backendSelection").getString("capabilityRevision"));assertEquals(ENVIRONMENT,source.getString("environmentBinding"));recorded.incrementAndGet();}};
            JSONObject accepted=f.request(input,true);assertTrue(accepted.toString(),accepted.getBoolean("ok"));assertEquals(1,recorded.get());assertEquals(1,f.server.submissions.size());assertEquals(1,f.tasks.list().length());
        }
    }
    @Test public void ownerApprovalUsesExactOriginalBodyAndLostDecisionNeverReplays()throws Exception {
        try(Fixture f=new Fixture()){
            f.server.initialStatus="awaiting_approval";String task=f.start();JSONObject review=f.open(task),source=review.getJSONObject("source"),pending=source.getJSONArray("pendingApprovals").getJSONObject(0);
            assertEquals(HarnessCapabilityObservationTest.geminiApproval().getJSONObject("toolCall").toString(),pending.getJSONObject("toolCall").toString());
            f.server.dropApproval=true;JSONObject action=f.action(review).put("requestId",pending.getString("requestId")).put("decision","accept");f.workflow.handle(f.owner,"approve",action);
            assertEquals(1,f.server.approvals.size());JSONObject wire=f.server.approvals.get(0);assertEquals(7,wire.length());assertEquals(source.getString("jobOperationId"),wire.getString("jobOperationId"));assertEquals(pending.getString("requestHash"),wire.getString("requestHash"));assertEquals(pending.getString("sessionId"),wire.getString("sessionId"));assertEquals(pending.getString("promptRequestId"),wire.getString("promptRequestId"));HarnessCapabilityObservation.requireSameBackendSelection(source.getJSONObject("backendSelection"),wire.getJSONObject("backendSelection"));
            JSONObject next=f.workflow.handle(f.owner,"refresh",new JSONObject().put("windowId",review.getString("windowId")).put("taskId",task)).getJSONObject("snapshot");f.workflow.handle(f.owner,"approve",f.action(next).put("requestId",pending.getString("requestId")).put("decision","accept"));assertEquals(1,f.server.approvals.size());assertEquals(1,f.server.submissions.size());assertTrue(f.tasks.hasUnresolved(task));
            assertThrows(SecurityException.class,()->f.remote.handle("approveJob",new JSONObject(source.toString()).put("approvalRequestId",pending.getString("requestId")).put("decision","accept")));
        }
    }
    @Test public void approvalWindowLossAtDispatchAndUnsupportedQuestionsDoNotSend()throws Exception {
        try(Fixture f=new Fixture()){
            f.server.initialStatus="awaiting_approval";String task=f.start();JSONObject review=f.open(task),pending=review.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0);
            assertThrows(SecurityException.class,()->f.workflow.handle(f.owner,"answerQuestion",f.action(review).put("requestId","not-a-question").put("requestHash","f".repeat(64)).put("answers",new JSONObject())));
            f.before=path->{if(path.endsWith("/approval"))f.workflow.close(review.getString("windowId"));};f.workflow.handle(f.owner,"approve",f.action(review).put("requestId",pending.getString("requestId")).put("decision","accept"));
            assertEquals(0,f.server.approvals.size());assertEquals(1,f.server.submissions.size());assertTrue(f.tasks.hasUnresolved(task));
        }
    }
    @Test public void typedOriginalStopKeepsCapturedSelectionAndOriginalCancelBody()throws Exception {
        try(Fixture f=new Fixture()){
            f.server.initialStatus="running";String task=f.start();JSONObject original=f.tasks.get(task,false),source=f.tasks.remoteOwnerTarget(task);
            assertThrows(SecurityException.class,()->f.remote.handle("cancelJob",source));assertEquals(0,f.server.cancellations.size());
            OriginalRemoteStopClaim claim=f.tasks.claimOriginalRemoteStop(task,original.getString("runId"),original.getInt("coordinatorEpoch"),source.getString("profileBinding"));JSONObject result=f.remote.cancelOriginalPinnedJob(claim);
            assertEquals("cancelled",result.getString("status"));assertEquals(ENVIRONMENT,result.getString("environmentBinding"));HarnessCapabilityObservation.requireSameBackendSelection(source.getJSONObject("backendSelection"),result.getJSONObject("backendSelection"));assertEquals(1,f.server.cancellations.size());JSONObject wire=f.server.cancellations.get(0);assertEquals(1,wire.length());assertEquals(source.getString("jobOperationId"),wire.getString("jobOperationId"));assertEquals(original.getString("runId"),f.tasks.get(task,false).getString("runId"));
        }
    }
}
