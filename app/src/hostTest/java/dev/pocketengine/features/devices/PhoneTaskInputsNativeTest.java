package dev.pocketengine.features.devices;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQLite + genuine native GoalSession/Runner + loopback mTLS and model fixture.
 * No real phone, paid model, production identity or native GUI is used or verified. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneTaskInputsNativeTest {
    private static final CapabilityPort NO_EFFECTS=new CapabilityPort(){
        public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return true;}
        public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("Fixture cannot perform a phone or external effect");}
    };
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase sql;final DeviceIdentity.AuthenticatedPeer peer;final String target;
        final long deadline=System.currentTimeMillis()+300_000;final JSONObject resource=new JSONObject().put("screenMode","capabilities");
        final PairingCoordinator pairing;final DeviceExecutionAdmission admission;final RunControl original=new RunControl();
        final DeviceGoalSession session;volatile DeviceGoalSession current;final DeviceOwnerInputs inputs;final DeviceTaskCoordinator coordinator;
        final HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicInteger requests=new AtomicInteger(),continuations=new AtomicInteger();
        final List<String> bodies=new CopyOnWriteArrayList<>();final List<JSONObject> events=new CopyOnWriteArrayList<>();final ModelConfig model;final String task,run,allocation;final int epoch;
        final AgentRunner.OwnerInputs mailbox;boolean continueAutomatically=true,observeResultClosingWindow;
        final AtomicInteger resultClosingWindows=new AtomicInteger();
        Fixture(int modelBudget)throws Exception {
            var context=RuntimeEnvironment.getApplication();context.deleteDatabase("pocket-world.db");tasks=new TaskStore(context);
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);sql=(SQLiteDatabase)field.get(tasks);
            TaskStoreDeviceTasks.createSchema(sql);TaskStoreDeviceTrust.createSchema(sql);
            Peers tls=authenticatedPeers();peer=tls.caller;target=tls.target;
            ContentValues known=new ContentValues();known.put("device_id",peer.deviceId());known.put("certificate_der",peer.certificateDer());known.put("fingerprint",peer.certificateFingerprint());known.put("instance_id","input-source");known.put("authorization_epoch",1);known.put("revoked",0);known.put("updated_at",System.currentTimeMillis());sql.insertOrThrow("device_peers",null,known);
            JSONObject grant=new JSONObject().put("grantId","input-grant").put("peerDeviceId",peer.deviceId()).put("localDeviceId",target).put("authorizationEpoch",1).put("issuedAt",System.currentTimeMillis()-1000).put("expiresAt",deadline)
                .put("capabilities",new JSONArray().put("task.submitGoal").put("task.get").put("task.acquireControl").put("task.answerQuestion").put("task.steer").put("task.respond").put("task.requestResume").put("operation.get"))
                .put("toolNames",new JSONArray()).put("resources",new JSONArray().put(resource)).put("allowedOrigins",new JSONArray().put(peer.deviceId())).put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",20).put("modelBudget",20);
            ContentValues saved=new ContentValues();saved.put("grant_id","input-grant");saved.put("peer_device_id",peer.deviceId());saved.put("local_device_id",target);saved.put("authorization_epoch",1);saved.put("expires_at",deadline);saved.put("body_json",grant.toString());saved.put("revoked",0);sql.insertOrThrow("device_grants",null,saved);
            pairing=new PairingCoordinator(new TaskStoreDeviceTrust(sql),target,"input-receiver");
            http.createContext("/",exchange->{try{
                bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int call=requests.incrementAndGet();
                JSONObject message=call==1?new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","native-ask-call").put("type","function").put("function",new JSONObject().put("name","run_ask_user").put("arguments",new JSONObject().put("question","Which exact fixture section?").toString()))))
                    :new JSONObject().put("role","assistant").put("content","ORIGINAL-PAIRED-ANSWER-CONSUMED");
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",call==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});http.start();
            model=new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort(),"","controlled-paired-input",false,8,30);
            String operation="original-input-goal";allocation=DeviceProtocol.allocationId("original-source-task",target,resource,"goal",operation);
            JSONObject goal=base("GoalSubmission","task.submitGoal",operation,new JSONObject().put("goal","Ask a fixture question then use the native answer").put("executionMode","agent").put("modelSource",new JSONObject().put("kind","local")))
                .put("rootTaskId","original-source-task").put("parentTaskId","original-source-task").put("localTaskId","original-source-task").put("runId","source-original-run").put("attempt",1).put("executorEpoch",1).put("capabilityVersion","1.0")
                .put("budget",new JSONObject().put("allocationId",allocation).put("toolCalls",8).put("modelCalls",modelBudget));
            DeviceTaskEnvelope envelope=parse(goal);admission=DeviceExecutionAdmission.issue(pairing.checkPermission(peer,envelope,System.currentTimeMillis()),(request,kind)->{});
            task=tasks.deviceTasks().accept(admission).getString("localTaskId");JSONObject queued=tasks.get(task,false);
            var reviewed=tasks.deviceTasks().reviewGoalModel(task,queued.getLong("deviceStateRevision"),admission,model);var approved=tasks.deviceTasks().confirmGoalModel(reviewed);
            JSONObject started=tasks.deviceTasks().startGoalAttempt(task,admission,original,approved);run=started.getString("runId");epoch=started.getInt("coordinatorEpoch");
            session=new DeviceGoalSession(task,admission,original,approved);current=session;inputs=new DeviceOwnerInputs(tasks,id->id.equals(task)?current:null);
            mailbox=inputs.runnerInputs(session);coordinator=new DeviceTaskCoordinator(tasks,new DeviceTaskCoordinator.NativeHost(){
                public void acceptTools(String id,DeviceExecutionAdmission ignored){throw new AssertionError("No device tools session");}
                public void acceptGoal(String id,DeviceExecutionAdmission ignored){throw new AssertionError("No second task or goal launch");}
                public void controlRequested(String id,String action){if(!"resume_requested".equals(action))throw new AssertionError("Ordinary text cannot use approval/takeover control");}
                public DeviceOwnerInputs ordinaryInputs(){return inputs;}
                public void ordinaryInputAccepted(DeviceOwnerInputs.Scope exact,JSONObject accepted)throws Exception{
                    if(!continueAutomatically)throw new SecurityException("Controlled original dependency unavailable");exact.requireContinuationOutsideTasks();
                    tasks.reopenDeviceOwnerQuestionMailbox(exact,accepted.getString("messageId"));tasks.deviceTasks().resumeGoal(task,admission,original);continuations.incrementAndGet();
                }
            },target,"input-receiver");
        }
        JSONObject base(String type,String method,String operation,JSONObject args)throws Exception{return new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType",type).put("method",method).put("requestId",UUID.randomUUID().toString()).put("operationId",operation)
            .put("originDeviceId",peer.deviceId()).put("callerDeviceId",peer.deviceId()).put("targetDeviceId",target).put("instanceId","input-receiver").put("grantId","input-grant").put("authorizationEpoch",1).put("resourceBinding",resource).put("deadline",deadline)
            .put("visitedDevices",new JSONArray().put(peer.deviceId())).put("maxDelegationDepth",1).put("delegationChain",new JSONArray()).put("arguments",args);}
        DeviceTaskEnvelope parse(JSONObject value)throws Exception{value.put("argumentsDigest",DeviceProtocol.argumentsDigest(value));return DeviceProtocol.validateRequest(DeviceProtocol.canonicalBytes(value),peer,target,System.currentTimeMillis());}
        PairingCoordinator.Permission permission(JSONObject value)throws Exception{return pairing.checkPermission(peer,parse(value),System.currentTimeMillis());}
        JSONObject owner(JSONObject value)throws Exception{
            parse(value);DeviceIngress ingress=new DeviceIngress(target,"input-receiver",pairing,(request,kind)->{throw new AssertionError("Ordinary input never creates execution admission");},new DeviceCapabilityRegistry(target,"input-receiver",()->new JSONObject()),coordinator);
            JSONObject receipt=ingress.receive(peer,DeviceProtocol.canonicalBytes(value)).getJSONObject("result");assertEquals("OperationReceipt",receipt.getString("messageType"));assertEquals(task,receipt.getString("localTaskId"));assertEquals(run,receipt.getString("runId"));assertEquals(epoch,receipt.getInt("executorEpoch"));return receipt.getJSONObject("payload");
        }
        JSONObject request(String method,String operation,JSONObject args)throws Exception{return base("DeviceRequest",method,operation,args);}
        JSONObject acquire(String operation,String previous)throws Exception{
            JSONObject args=new JSONObject().put("taskId",task).put("runId",run).put("executorEpoch",epoch).put("ttlMs",30000);if(previous!=null)args.put("previousLeaseId",previous);
            return coordinator.acquireControl(permission(request("task.acquireControl",operation,args)));
        }
        JSONObject snapshot()throws Exception{return coordinator.task(permission(request("task.get","snapshot-"+UUID.randomUUID(),new JSONObject().put("taskId",task))));}
        void retainedLease(JSONObject lease)throws Exception{
            // Explicit retained-state negative fixture: change only this original
            // task's stored lease, then query through real authenticated admission.
            synchronized(tasks){JSONObject stored;try(Cursor row=sql.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{task})){assertTrue(row.moveToFirst());stored=new JSONObject(row.getString(0));}
                stored.put("deviceControlLease",new JSONObject(lease.toString()));ContentValues update=new ContentValues();update.put("document",stored.toString());assertEquals(1,sql.update("tasks",update,"task_id=?",new String[]{task}));}
        }
        JSONObject input(String method,String operation,String lease,String text)throws Exception{
            JSONObject args=new JSONObject().put("taskId",task).put("runId",run).put("executorEpoch",epoch).put("allocationId",allocation).put("controlLeaseId",lease).put("text",text);
            if(method.equals("task.answerQuestion")){JSONObject question=tasks.get(task,false).getJSONObject("evidence").getJSONObject("ownerQuestion");args.put("originalRequestId",question.getString("requestId")).put("waitRevision",question.getLong("waitRevision"));}
            return request(method,operation,args);
        }
        String pauseAndAcquire()throws Exception{
            original.pause();tasks.status(task,"paused","Explicit native fixture pause before model dispatch");
            return acquire("resume-control",null).getString("leaseId");
        }
        JSONObject resume(String operation,String lease)throws Exception{
            return request("task.requestResume",operation,new JSONObject().put("taskId",task).put("runId",run).put("executorEpoch",epoch)
                    .put("allocationId",allocation).put("controlLeaseId",lease).put("expectedRevision",tasks.get(task,false).getLong("deviceStateRevision")));
        }
        JSONObject decide(JSONObject request,boolean approved)throws Exception{
            return tasks.deviceTasks().decideResumeRequest(task,request.getString("resumeRequestId"),request.getLong("reviewRevision"),approved,admission,original);
        }
        RunOutcome runOriginal()throws Exception {
            NativeInferenceScope audience=NativeInferenceScope.pairedDeviceGoal(session.modelApproval(),original);
            DeviceGoalCapabilityPort guarded=new DeviceGoalCapabilityPort(NO_EFFECTS,admission,original,()->InferenceAudiencePolicy.require(model,audience,original));
            session.runnerStarted();RunOutcome result=new AgentRunner(null,guarded,ContextBudgetPolicy.currentHostSource(),null,mailbox).run(task,tasks.get(task,false).getString("goal"),model,original,event->{try{
                JSONObject selected=new JSONObject();for(String key:List.of("type","status","tool","message","code"))if(event.has(key))selected.put(key,event.get(key));events.add(selected);tasks.event(task,run,epoch,event);
                if(observeResultClosingWindow&&"result".equals(event.optString("type"))&&"needs_confirmation".equals(event.optString("status"))){
                    // This is the actual original result event, before run() has
                    // returned and before the service can receive its Finished.
                    JSONObject closing=tasks.get(task,false);long revision=closing.getLong("deviceStateRevision");String originalGrant=grant();
                    assertFalse(closing.has("deviceGoalResult"));assertFalse(original.isPaused());assertFalse(original.isCancelled());
                    assertEquals(Math.min(new JSONObject(originalGrant).getLong("expiresAt"),admission.deadline()),tasks.deviceTasks().goalSupervisionDeadline(session));
                    assertThrows(SecurityException.class,()->tasks.taskExecutionDeadline(task,run,epoch));
                    assertThrows(SecurityException.class,()->session.modelApproval().checkCurrent(original));
                    assertThrows(SecurityException.class,()->guarded.beforeTool("run_ask_user",new JSONObject(),original));
                    assertEquals(revision,tasks.get(task,false).getLong("deviceStateRevision"));assertEquals(originalGrant,grant());
                    resultClosingWindows.incrementAndGet();
                }
            }catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(task,run,epoch,original));
            if("failed".equals(result.status))System.err.println("Original paired Runner fixture: "+diagnostics(result));
            var finished=session.runnerFinished(result);if(result.status.equals("awaiting_user")){tasks.status(task,"awaiting_user",result.summary);original.pause();tasks.recordDeviceOrdinaryQuestionWait(finished);inputs.waiting(finished);}return result;
        }
        String diagnostics(RunOutcome outcome){try{JSONArray recent=new JSONArray();for(int i=Math.max(0,events.size()-12);i<events.size();i++)recent.put(events.get(i));return new JSONObject().put("status",outcome.status).put("summary",outcome.summary).put("evidence",outcome.evidence).put("httpRequests",requests.get()).put("originalTask",task).put("originalRun",run).put("epoch",epoch).put("recentNativeEvents",recent).toString();}catch(Exception unavailable){return outcome.status+": "+outcome.summary;}}
        long count(String table)throws Exception{try(Cursor row=sql.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        String grant()throws Exception{try(Cursor row=sql.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
        public void close(){original.cancel();inputs.released(task,original);tasks.deviceTasks().release(run,original);http.stop(0);sql.close();}
    }
    @Test public void realQuestionAndSteeringReachOriginalRunnerWithSameRunGrantAndCumulativeBudget()throws Exception{try(Fixture f=new Fixture(4)){
        f.observeResultClosingWindow=true;
        String lease=f.acquire("first-control",null).getString("leaseId"),grant=f.grant();
        JSONObject guidance=f.coordinator.ownerInput(f.permission(f.input("task.steer","original-guidance",lease,"FIXTURE-RUNNING-GUIDANCE")));assertTrue(guidance.getBoolean("inputSaved"));
        RunOutcome waiting=f.runOriginal();assertEquals(f.diagnostics(waiting),"awaiting_user",waiting.status);assertEquals(1,f.requests.get());assertTrue(f.bodies.get(0).contains("FIXTURE-RUNNING-GUIDANCE"));
        JSONObject q=DeviceOwnerInputs.question(waiting.evidence,f.task,f.run,f.epoch);assertNotNull(q);assertTrue(q.getLong("waitRevision")>0);
        JSONObject write=f.input("task.answerQuestion","original-answer",lease,"FIXTURE-PAIRED-SECOND-SECTION");JSONObject saved=f.owner(write);assertTrue(saved.getBoolean("inputSaved"));assertTrue(saved.getBoolean("continuationRequested"));
        assertEquals(1,f.continuations.get());assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));assertEquals(f.epoch,f.tasks.get(f.task,false).getInt("coordinatorEpoch"));assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));
        JSONObject repeated=f.owner(write);assertTrue(repeated.getBoolean("knownOperation"));assertEquals(saved.getString("messageId"),repeated.getString("messageId"));assertEquals(1,f.continuations.get());
        JSONObject changed=new JSONObject(write.toString());changed.getJSONObject("arguments").put("text","CHANGED-AFTER-NATIVE-RESUME");assertThrows(SecurityException.class,()->f.owner(changed));assertEquals(1,f.continuations.get());
        RunOutcome completed=f.runOriginal();assertTrue(f.diagnostics(completed),List.of("completed","needs_confirmation").contains(completed.status));assertEquals(1,f.resultClosingWindows.get());assertEquals(2,f.requests.get());assertTrue(f.bodies.get(1).contains("FIXTURE-PAIRED-SECOND-SECTION"));
        JSONObject task=f.tasks.get(f.task,false);assertEquals(2,task.getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));assertNotEquals("owner",task.getString("actorId"));assertNotEquals("private",task.getString("spaceId"));assertTrue(task.optString("conversationId").isEmpty());
        assertEquals(2,task.getJSONArray("ownerInputs").length());for(int i=0;i<2;i++){JSONObject row=task.getJSONArray("ownerInputs").getJSONObject(i);assertEquals("native_paired_task_input",row.getString("sourceKind"));assertFalse(row.getBoolean("formalSourceRefAvailable"));assertFalse(row.has("userSourceRef"));}
    }}
    @Test public void originalLeaseQuestionAndDigestCannotBeReplacedAndDuplicateIsSingleNativeInput()throws Exception{try(Fixture f=new Fixture(4)){
        RunOutcome waiting=f.runOriginal();assertEquals(f.diagnostics(waiting),"awaiting_user",waiting.status);String old=f.acquire("control-old",null).getString("leaseId"),lease=f.acquire("control-new",old).getString("leaseId");f.continueAutomatically=false;
        JSONObject originalLease=f.tasks.get(f.task,false).getJSONObject("deviceControlLease"),visible=f.snapshot().getJSONObject("result").getJSONObject("controlLease");
        assertEquals(2,visible.length());assertEquals(lease,visible.getString("controlLeaseId"));assertEquals(originalLease.getLong("expiresAt"),visible.getLong("expiresAt"));
        try{
            for(String staleField:new String[]{"callerDeviceId","runId","executorEpoch","authorizationEpoch","expiresAt"}){
                JSONObject stale=new JSONObject(originalLease.toString());Object obsolete=switch(staleField){case "callerDeviceId"->f.target;case "runId"->UUID.randomUUID().toString();case "executorEpoch"->f.epoch+1;case "authorizationEpoch"->originalLease.getLong("authorizationEpoch")+1;default->System.currentTimeMillis()-1;};
                stale.put(staleField,obsolete);f.retainedLease(stale);assertFalse("Stale "+staleField+" exposed a control lease",f.snapshot().getJSONObject("result").has("controlLease"));
            }
        }finally{f.retainedLease(originalLease);}
        assertEquals(lease,f.snapshot().getJSONObject("result").getJSONObject("controlLease").getString("controlLeaseId"));
        assertThrows(SecurityException.class,()->f.coordinator.ownerInput(f.permission(f.input("task.answerQuestion","obsolete-lease",old,"first"))));
        JSONObject wrong=f.input("task.answerQuestion","obsolete-allocation",lease,"first");wrong.getJSONObject("arguments").put("allocationId","wrong-allocation");assertThrows(SecurityException.class,()->f.coordinator.ownerInput(f.permission(wrong)));
        JSONObject answer=f.input("task.answerQuestion","single-answer",lease,"FIRST-NATIVE-ANSWER");JSONObject saved=f.coordinator.ownerInput(f.permission(answer));assertTrue(saved.getBoolean("inputSaved"));assertFalse(saved.getBoolean("continuationRequested"));
        assertTrue(f.coordinator.ownerInput(f.permission(answer)).getBoolean("knownOperation"));JSONObject duplicate=f.coordinator.ownerInput(f.permission(f.input("task.answerQuestion","same-question-new-id",lease,"FIRST-NATIVE-ANSWER")));assertTrue(duplicate.getBoolean("duplicateInput"));assertEquals(saved.getString("messageId"),duplicate.getString("messageId"));
        assertThrows(SecurityException.class,()->f.coordinator.ownerInput(f.permission(f.input("task.answerQuestion","changed-answer",lease,"SECOND-DIFFERENT-ANSWER"))));
        JSONObject changed=new JSONObject(answer.toString());changed.getJSONObject("arguments").put("text","CHANGED-SAME-OPERATION");assertThrows(SecurityException.class,()->f.coordinator.ownerInput(f.permission(changed)));
        assertEquals(1,f.tasks.get(f.task,false).getJSONArray("ownerInputs").length());assertEquals(1,f.requests.get());assertEquals(1,f.count("runs"));assertTrue(f.original.isPaused());
        f.tasks.requestCancel(f.task,f.run);f.original.cancel();assertFalse(f.snapshot().getJSONObject("result").has("controlLease"));
    }}
    @Test public void approvalSmugglingPendingApprovalAndMissingNativeSessionNeverAppendOrdinaryText()throws Exception{try(Fixture f=new Fixture(4)){
        RunOutcome waiting=f.runOriginal();assertEquals(f.diagnostics(waiting),"awaiting_user",waiting.status);String lease=f.acquire("control",null).getString("leaseId");
        for(String field:new String[]{"approval","decision","takeover"}){JSONObject invalid=f.input("task.answerQuestion","smuggle-"+field,lease,"ANSWER");invalid.getJSONObject("arguments").put(field,true);assertThrows(DeviceProtocol.ProtocolException.class,()->f.permission(invalid));}
        JSONObject pending=f.tasks.requireCapabilityApproval(f.task,f.run,"fixture_approval_only",new JSONObject().put("exact",1),new JSONObject().put("fixture",true));
        assertThrows(SecurityException.class,()->f.coordinator.ownerInput(f.permission(f.input("task.answerQuestion","pending-approval",lease,"ANSWER"))));assertEquals(0,f.tasks.get(f.task,false).getJSONArray("ownerInputs").length());
        f.tasks.decideCapabilityApproval(f.task,pending.getString("approvalId"),false);f.current=null;
        assertThrows(SecurityException.class,()->f.coordinator.ownerInput(f.permission(f.input("task.answerQuestion","missing-session",lease,"ANSWER"))));assertEquals(1,f.requests.get());assertEquals(1,f.count("runs"));
    }}
    @Test public void exhaustedOriginalModelBudgetSavesAnswerWithoutGrantRunOrHttpRenewal()throws Exception{try(Fixture f=new Fixture(1)){
        RunOutcome waiting=f.runOriginal();assertEquals(f.diagnostics(waiting),"awaiting_user",waiting.status);String lease=f.acquire("control",null).getString("leaseId"),grant=f.grant();JSONObject saved=f.coordinator.ownerInput(f.permission(f.input("task.answerQuestion","saved-without-budget",lease,"SAVED-NATIVE-ANSWER")));
        assertTrue(saved.getBoolean("inputSaved"));assertFalse(saved.getBoolean("continuationRequested"));assertEquals(0,f.continuations.get());assertEquals(1,f.requests.get());assertEquals(1,f.count("runs"));assertEquals(grant,f.grant());assertTrue(f.original.isPaused());
        assertEquals("awaiting_user",f.tasks.get(f.task,false).getString("status"));assertEquals(1,f.tasks.get(f.task,false).getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));
    }}
    @Test public void nativeInputKeepsThePinnedUnicodeScalarLimitWithoutCreatingAModelCall()throws Exception{try(Fixture f=new Fixture(4)){
        String lease=f.acquire("control",null).getString("leaseId"),text="🙂".repeat(4000),grant=f.grant();JSONObject saved=f.coordinator.ownerInput(f.permission(f.input("task.steer","supplementary-input",lease,text)));assertTrue(saved.getBoolean("inputSaved"));
        assertEquals(text,f.tasks.get(f.task,false).getJSONArray("ownerInputs").getJSONObject(0).getString("content"));assertEquals(0,f.requests.get());assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));
        assertThrows(DeviceProtocol.ProtocolException.class,()->f.permission(f.input("task.steer","over-scalar-bound",lease,text+"🙂")));assertEquals(1,f.tasks.get(f.task,false).getJSONArray("ownerInputs").length());
    }}
    @Test public void kernelOutcomeHooksKeepNativeFenceWithoutGrantingDeviceTools()throws Exception{try(Fixture f=new Fixture(4)){
        AtomicInteger modelFences=new AtomicInteger();long originalOperations=f.count("operations");NativeInferenceScope audience=NativeInferenceScope.pairedDeviceGoal(f.session.modelApproval(),f.original);
        DeviceGoalCapabilityPort port=new DeviceGoalCapabilityPort(NO_EFFECTS,f.admission,f.original,()->{modelFences.incrementAndGet();InferenceAudiencePolicy.require(f.model,audience,f.original);});
        for(String kernel:List.of("run_ask_user","run_end_not_done","run_handle_obstacle")){
            assertFalse(port.supports(kernel));assertNull(port.beforeTool(kernel,new JSONObject(),f.original));port.afterTool(kernel,new JSONObject(),new JSONObject().put("ok",true),f.original);
        }
        assertEquals(6,modelFences.get());assertEquals(0,port.tools().length());assertEquals(0,f.requests.get());assertEquals(originalOperations,f.count("operations"));
        assertThrows(SecurityException.class,()->port.beforeTool("ungranted_device_tool",new JSONObject(),f.original));
        assertThrows(SecurityException.class,()->port.beforeTool("run_ask_user",new JSONObject(),new RunControl()));
        f.original.pause();assertThrows(SecurityException.class,()->port.beforeTool("run_ask_user",new JSONObject(),f.original));assertThrows(SecurityException.class,()->port.afterTool("run_handle_obstacle",new JSONObject(),new JSONObject(),f.original));
    }}
    @Test public void resumeRequestNeedsExactNativeDecisionAndContinuesOnlyTheOriginalBudget()throws Exception{try(Fixture f=new Fixture(4)){
        String lease=f.pauseAndAcquire(),grant=f.grant();JSONObject wire=f.resume("resume-owner-request",lease),request=f.owner(wire);
        assertTrue(request.getBoolean("ownerActionRequired"));assertFalse(request.getBoolean("executionAuthorized"));assertTrue(f.original.isPaused());assertEquals(0,f.requests.get());
        assertEquals(request.getLong("reviewRevision"),f.tasks.get(f.task,false).getLong("deviceStateRevision"));
        assertTrue(f.owner(wire).getBoolean("knownOperation"));assertEquals(request.getLong("reviewRevision"),f.tasks.get(f.task,false).getLong("deviceStateRevision"));
        assertThrows(SecurityException.class,()->f.tasks.deviceTasks().resumeGoal(f.task,f.admission,f.original));
        assertEquals("owner_denied",f.decide(request,false).getString("state"));assertTrue(f.original.isPaused());assertEquals(0,f.requests.get());
        assertThrows(SecurityException.class,()->f.decide(request,true));
        JSONObject next=f.owner(f.resume("resume-owner-second",lease));assertTrue(f.decide(next,true).getBoolean("continuedOriginalRun"));
        assertFalse(f.original.isPaused());assertEquals(grant,f.grant());assertEquals(1,f.count("runs"));assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));
        assertThrows(SecurityException.class,()->f.decide(next,true));
        RunOutcome original=f.runOriginal();assertEquals(f.diagnostics(original),"awaiting_user",original.status);assertEquals(1,f.requests.get());
        assertEquals(1,f.tasks.get(f.task,false).getJSONObject("deviceExecutionScope").getInt("modelCallsUsed"));
    }}
    @Test public void changedSnapshotLeaseAllocationAndPauseGenerationCannotConsumeAResumeReview()throws Exception{try(Fixture f=new Fixture(4)){
        String old=f.pauseAndAcquire();JSONObject stale=f.resume("stale-before-renewal",old);String lease=f.acquire("renew-resume-control",old).getString("leaseId");
        assertThrows(SecurityException.class,()->f.owner(stale));
        JSONObject wrong=f.resume("wrong-resume-allocation",lease);wrong.getJSONObject("arguments").put("allocationId","another-allocation");assertThrows(SecurityException.class,()->f.owner(wrong));
        JSONObject request=f.owner(f.resume("exact-native-review",lease));f.original.pause();
        assertThrows(SecurityException.class,()->f.decide(request,true));assertTrue(f.original.isPaused());assertEquals(0,f.requests.get());
        f.decide(request,false);JSONObject fresh=f.owner(f.resume("revision-native-review",lease));
        f.tasks.status(f.task,"paused","Native state changed after the displayed review");
        assertTrue(TaskStoreDeviceTasks.resumeRequestView(f.tasks.get(f.task,false)).getBoolean("stale"));assertThrows(SecurityException.class,()->f.decide(fresh,true));
        assertEquals("owner_denied",f.decide(fresh,false).getString("state"));assertEquals(1,f.count("runs"));assertEquals(0,f.requests.get());
    }}
    @Test public void revokedSourceCancelledRunAndReleasedNativeControlNeverResumeFromAnOldCallback()throws Exception{
        for(String reason:List.of("revoked","cancelled","released"))try(Fixture f=new Fixture(4)){
            String lease=f.pauseAndAcquire();JSONObject request=f.owner(f.resume("request-before-"+reason,lease));
            if(reason.equals("revoked"))new TaskStoreDeviceTrust(f.sql).revoke(f.peer.deviceId(),System.currentTimeMillis());
            else if(reason.equals("cancelled")){f.tasks.requestCancel(f.task,f.run);f.original.cancel();}
            else f.tasks.deviceTasks().release(f.run,f.original);
            assertThrows(Exception.class,()->f.decide(request,true));assertEquals(0,f.requests.get());assertEquals(1,f.count("runs"));
            assertThrows(SecurityException.class,()->f.tasks.deviceTasks().goalSupervisionDeadline(f.session));
            assertEquals("pending_owner_confirmation",f.tasks.get(f.task,false).getJSONObject("deviceResumeRequest").getString("state"));
        }
    }
    @Test public void unknownEffectAndUnansweredQuestionRequireOriginalResolutionInsteadOfResume()throws Exception{try(Fixture f=new Fixture(4)){
        String lease=f.pauseAndAcquire();JSONObject request=f.owner(f.resume("request-before-unknown",lease));
        // Explicit fixture of an already-dispatched original operation whose effect is unknown.
        ContentValues unknown=new ContentValues();unknown.put("operation_id","unknown-original-effect");unknown.put("task_id",f.task);unknown.put("run_id",f.run);unknown.put("tool","fixture_external_effect");unknown.put("arguments","{}");unknown.put("state","unknown");unknown.put("created_at",System.currentTimeMillis());f.sql.insertOrThrow("operations",null,unknown);
        assertThrows(SecurityException.class,()->f.decide(request,true));assertTrue(f.tasks.hasUnresolved(f.task));assertEquals(0,f.requests.get());assertEquals(1,f.count("runs"));
        try(Cursor row=f.sql.rawQuery("SELECT state FROM operations WHERE operation_id='unknown-original-effect'",null)){assertTrue(row.moveToFirst());assertEquals("unknown",row.getString(0));}
    }
    try(Fixture f=new Fixture(4)){
        RunOutcome waiting=f.runOriginal();assertEquals(f.diagnostics(waiting),"awaiting_user",waiting.status);String lease=f.acquire("question-control",null).getString("leaseId");
        assertThrows(SecurityException.class,()->f.owner(f.resume("not-an-answer",lease)));assertEquals(1,f.requests.get());assertEquals(0,f.continuations.get());assertEquals(1,f.count("runs"));
    }}
    @Test public void persistedOriginalSignedGrantSurvivesUpgradeWithoutExpandingNetworkOrMethodAuthority()throws Exception{
        final String oldProfile="pwp1_e58652456ec50f920a0036626e11782bf0a8805712d018e55f57d2a7c9b97fdb",grantId="retained-before-phone-input-upgrade",instance="retained-peer-instance";
        Peers tls=authenticatedPeers();var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore first=new TaskStore(app);
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase initial=(SQLiteDatabase)field.get(first);
        long now=System.currentTimeMillis(),executionExpiry=now+60000,historyExpiry=now+120000,receivedAt=now-500;
        JSONObject binding=new JSONObject().put("screenMode","capabilities"),grant=new JSONObject().put("grantId",grantId).put("localDeviceId",tls.caller.deviceId()).put("peerDeviceId",tls.target)
            .put("authorizationEpoch",1).put("issuedAt",now-1000).put("expiresAt",executionExpiry)
            .put("capabilities",new JSONArray().put("task.submitGoal").put("tool.invoke").put("operation.cancel").put("operation.get").put("task.get"))
            .put("toolNames",new JSONArray().put("phone_observe")).put("resources",new JSONArray().put(binding)).put("allowedOrigins",new JSONArray().put(tls.target))
            .put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",2).put("modelBudget",2)
            .put("historyRead",new JSONObject().put("expiresAt",historyExpiry).put("methods",new JSONArray().put("operation.get").put("task.get")));
        JSONObject original=signedGrant(tls.source,grant,oldProfile,instance,"PairGrantReceipt",now-1000);
        ContentValues known=new ContentValues();known.put("device_id",tls.caller.deviceId());known.put("certificate_der",tls.caller.certificateDer());known.put("fingerprint",tls.caller.certificateFingerprint());known.put("instance_id",instance);known.put("authorization_epoch",1);known.put("revoked",0);known.put("updated_at",now);
        try{initial.insertOrThrow("device_peers",null,known);new TaskStoreDeviceTrust(initial).putOutgoingGrant(tls.caller.deviceId(),grantId,original,receivedAt);}finally{initial.close();}
        // A real reopen of the same TaskStore file, not an in-memory route cache.
        TaskStore reopened=new TaskStore(app);SQLiteDatabase sql=(SQLiteDatabase)field.get(reopened);
        try{
            TaskStoreDeviceTrust trust=new TaskStoreDeviceTrust(sql);PairingCoordinator pairing=new PairingCoordinator(trust,tls.target,"retained-local-instance");
            DeviceIdentity.LocalIdentity local=localIdentity(tls.receiver,"retained-local-instance");
            PairingCoordinator.OutgoingGrant route=pairing.restoreOutgoingGrant(tls.caller,grantId,now);route.requireTargetPeer(tls.caller,now);
            assertEquals(DeviceProtocol.canonical(grant),DeviceProtocol.canonical(route.grant()));assertEquals(1,route.authorizationEpoch());assertEquals(executionExpiry,route.deadline());
            assertEquals(tls.caller.deviceId(),route.targetDeviceId());assertEquals(instance,route.targetInstanceId());
            var stop=route.operationCancel(local,"original-unknown-operation","a".repeat(64),"original-stop-request",binding,now,1000);stop.requireTargetPeer(tls.caller,now);
            assertEquals("operation.cancel",stop.envelope().method());assertEquals(DeviceProtocol.PROFILE,stop.envelope().json().getString("protocolProfile"));assertFalse(stop.envelope().json().has("budget"));
            var history=pairing.historyRoute(tls.caller,grantId,executionExpiry+1);
            var query=history.historyQuery(local,"operation.get","original-unknown-operation",binding,executionExpiry+1,1000,null);query.requireTargetPeer(tls.caller,executionExpiry+1);
            assertEquals("original-unknown-operation",query.envelope().arguments().getString("operationId"));assertEquals(DeviceProtocol.PROFILE,query.envelope().json().getString("protocolProfile"));assertFalse(query.envelope().json().has("budget"));
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,executionExpiry+1));
            assertThrows(SecurityException.class,()->history.historyQuery(local,"operation.get","original-unknown-operation",binding,historyExpiry+1,1000,null));
            assertEquals(DeviceProtocol.canonical(original),DeviceProtocol.canonical(trust.outgoingGrant(tls.caller.deviceId(),grantId)));
            try(Cursor row=sql.rawQuery("SELECT receipt_json,received_at FROM device_outgoing_grants WHERE target_device_id=? AND grant_id=?",new String[]{tls.caller.deviceId(),grantId})){assertTrue(row.moveToFirst());assertEquals(DeviceProtocol.canonical(original),row.getString(0));assertEquals(receivedAt,row.getLong(1));}
            assertEquals(1,trust.peer(tls.caller.deviceId()).getLong("authorizationEpoch"));
            // Original network parsing stays strict even though this stored record can restore.
            assertThrows(DeviceProtocol.ProtocolException.class,()->pairing.acceptOutgoingGrant(tls.caller,original,now));
            assertThrows(DeviceProtocol.ProtocolException.class,()->pairing.acceptHistoryGrantReceipt(tls.caller,original,now));
            JSONObject oldWire=new JSONObject(query.envelope().json().toString()).put("protocolProfile",oldProfile);assertThrows(DeviceProtocol.ProtocolException.class,()->DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(oldWire)));
            for(String added:List.of("task.answerQuestion","task.steer","task.requestResume","future.ownerControl")){
                trust.putOutgoingGrant(tls.caller.deviceId(),grantId,original,receivedAt);
                assertFalse(PairingCoordinator.contains(route.grant().getJSONArray("capabilities"),added));
                assertThrows(SecurityException.class,()->history.historyQuery(local,added,"original-unknown-operation",binding,now,1000,null));
                JSONObject expanded=new JSONObject(grant.toString());expanded.getJSONArray("capabilities").put(added);
                trust.putOutgoingGrant(tls.caller.deviceId(),grantId,signedGrant(tls.source,expanded,oldProfile,instance,"PairGrantReceipt",now-1000),receivedAt);
                assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));assertThrows(SecurityException.class,()->pairing.historyRoute(tls.caller,grantId,now));
            }
            trust.putOutgoingGrant(tls.caller.deviceId(),grantId,signedGrant(tls.source,grant,"pwp1_"+"0".repeat(64),instance,"PairGrantReceipt",now-1000),receivedAt);
            assertThrows(DeviceProtocol.ProtocolException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));
            JSONObject tampered=new JSONObject(original.toString());tampered.getJSONObject("body").getJSONObject("grant").put("toolBudget",3);trust.putOutgoingGrant(tls.caller.deviceId(),grantId,tampered,receivedAt);
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));
            trust.putOutgoingGrant(tls.caller.deviceId(),grantId,signedGrant(tls.source,grant,oldProfile,instance,"DeviceKeyRotation",now-1000),receivedAt);
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));
            trust.putOutgoingGrant(tls.caller.deviceId(),grantId,signedGrant(tls.receiver,grant,oldProfile,instance,"PairGrantReceipt",now-1000),receivedAt);
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));
            trust.putOutgoingGrant(tls.caller.deviceId(),grantId,signedGrant(tls.source,grant,oldProfile,"different-peer-instance","PairGrantReceipt",now-1000),receivedAt);
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));
            trust.putOutgoingGrant(tls.caller.deviceId(),grantId,original,receivedAt);Peers other=authenticatedPeers();
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(other.caller,grantId,now));assertThrows(SecurityException.class,()->route.requireTargetPeer(other.caller,now));
            ContentValues changed=new ContentValues();changed.put("instance_id","different-current-instance");sql.update("device_peers",changed,"device_id=?",new String[]{tls.caller.deviceId()});
            assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));assertThrows(SecurityException.class,()->route.checkCurrent(now));
            changed.put("instance_id",instance);sql.update("device_peers",changed,"device_id=?",new String[]{tls.caller.deviceId()});
            pairing.revokeLocally(tls.caller.deviceId(),now);assertThrows(SecurityException.class,()->pairing.restoreOutgoingGrant(tls.caller,grantId,now));assertThrows(SecurityException.class,()->pairing.historyRoute(tls.caller,grantId,now));assertThrows(SecurityException.class,()->query.requireTargetPeer(tls.caller,executionExpiry+1));
            assertEquals(DeviceProtocol.canonical(original),DeviceProtocol.canonical(trust.outgoingGrant(tls.caller.deviceId(),grantId)));
        }finally{sql.close();}
    }
    private record Material(KeyPair keys,X509Certificate cert){}
    private record Peers(DeviceIdentity.AuthenticatedPeer caller,String target,Material source,Material receiver){}
    private static JSONObject signedGrant(Material signer,JSONObject grant,String profile,String instance,String type,long issuedAt)throws Exception{
        JSONObject record=new JSONObject().put("protocolVersion",DeviceProtocol.VERSION).put("protocolProfile",profile).put("messageType",type).put("issuerDeviceId",DeviceIdentity.id(signer.cert)).put("issuerCertificateFingerprint",DeviceProtocol.sha256(signer.cert.getEncoded()))
            .put("body",new JSONObject().put("grant",new JSONObject(grant.toString())).put("targetInstanceId",instance).put("issuedAt",issuedAt));
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(signer.keys.getPrivate());signature.update(DeviceProtocol.canonicalBytes(record));return record.put("signature",Base64.getEncoder().encodeToString(signature.sign()));
    }
    private static DeviceIdentity.LocalIdentity localIdentity(Material material,String instance)throws Exception{
        // Existing host-test identity technique: the exact real TLS key/certificate
        // stays in an in-memory KeyStore; no production private key is loaded or saved.
        KeyStore store=KeyStore.getInstance("PKCS12");store.load(null);store.setKeyEntry("pocketworld.device.identity.v1",material.keys.getPrivate(),null,new java.security.cert.Certificate[]{material.cert});
        var constructor=DeviceIdentity.LocalIdentity.class.getDeclaredConstructor(KeyStore.class,X509Certificate.class,String.class);constructor.setAccessible(true);return constructor.newInstance(store,material.cert,instance);
    }
    private static Material certificate()throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));KeyPair keys=generator.generateKeyPair();long now=System.currentTimeMillis();X500Name name=new X500Name("CN=PocketWorld Ordinary Input Synthetic Fixture");
        X509Certificate certificate=new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,new java.math.BigInteger(120,new SecureRandom()).add(java.math.BigInteger.ONE),new Date(now-60000),new Date(now+3600000),name,keys.getPublic()).build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));return new Material(keys,certificate);
    }
    private static SSLContext context(Material local,Material peer)throws Exception{
        // Keep the platform provider used by the original host fixtures. The
        // bounded CI group opens Conscrypt's JDK 17 socket metadata access only;
        // both trust stores still contain the exact synthetic peer certificate.
        KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null);keys.setKeyEntry("fixture",local.keys.getPrivate(),"fixture-only".toCharArray(),new java.security.cert.Certificate[]{local.cert});KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-only".toCharArray());
        KeyStore trusted=KeyStore.getInstance(KeyStore.getDefaultType());trusted.load(null);trusted.setCertificateEntry("fixture-peer",peer.cert);TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trusted);SSLContext context=SSLContext.getInstance("TLSv1.3");context.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());return context;
    }
    private static Peers authenticatedPeers()throws Exception{
        Material source=certificate(),target=certificate();SSLContext serverContext=context(target,source),clientContext=context(source,target);var worker=Executors.newSingleThreadExecutor();
        try(SSLServerSocket server=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())){server.setNeedClientAuth(true);var receiving=worker.submit(()->{try(SSLSocket socket=(SSLSocket)server.accept()){socket.startHandshake();return DeviceIdentity.authenticatedPeer(socket.getSession());}});
            try(SSLSocket client=(SSLSocket)clientContext.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(),server.getLocalPort())){client.startHandshake();return new Peers(receiving.get(10,TimeUnit.SECONDS),DeviceIdentity.id(target.cert),source,target);}
        }finally{worker.shutdownNow();}
    }
}
