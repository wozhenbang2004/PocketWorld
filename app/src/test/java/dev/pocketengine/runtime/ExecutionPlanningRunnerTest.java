package dev.pocketengine.runtime;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ExecutionPlan;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.capabilities.PlanningCapabilityPort;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.policy.RunOutcomeTools;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Runner/Gateway over loopback. The explicit native fixture does not claim SQLite/device QA. */
public final class ExecutionPlanningRunnerTest {
    private static JSONObject call(String id,String name,JSONObject arguments)throws Exception {
        return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",arguments.toString()));
    }
    private static JSONObject calls(JSONObject... calls)throws Exception{return new JSONObject().put("role","assistant").put("tool_calls",new JSONArray(calls));}
    private static JSONObject plan()throws Exception{return new JSONObject().put("format",ExecutionPlan.FORMAT).put("goal","Review the original change").put("steps",new JSONArray().put(new JSONObject().put("id","read").put("title","Read original source").put("detail","Ask the owner before changing anything.")));}
    private static String hash(JSONObject value)throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static boolean offered(JSONObject request,String name)throws Exception {
        JSONArray tools=request.getJSONArray("tools");for(int i=0;i<tools.length();i++)if(name.equals(tools.getJSONObject(i).getJSONObject("function").getString("name")))return true;return false;
    }

    private static final class Fixture implements AutoCloseable,RunJournal {
        final RunControl original=new RunControl();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final List<JSONObject> requests=new CopyOnWriteArrayList<>(),replies=new ArrayList<>(),events=new ArrayList<>(),dispatched=new ArrayList<>();
        final NativePlanning planner;
        JSONArray history=new JSONArray();int steps,prepared,effectReceipts,effects,reads,phoneReads,observations,beforeTools,contextReads,afterTools;
        boolean withPhone,exposeHandle=true,changeBeforeModel,changeOnResponse,pauseOnResponse,browserReturn,cancelRead,lateKnownReadAfterCancel,throwRead,unknownRead,nullRead,firstScreenChanged,changeBeforeFinalObservation,changeAfterWait;
        int unconfirmedObservationAt=-1;String unconfirmedObservationCode="empty_observation";
        Fixture()throws Exception {
            original.bindHostRun("original-task","original-run",1);planner=new NativePlanning(original);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);int index=requests.size()-1;
                JSONObject message=index<replies.size()?replies.get(index):new JSONObject().put("role","assistant").put("content","The reviewed facts are ready; no execution was approved.");
                if(changeOnResponse){changeOnResponse=false;planner.scopeRevision++;}
                if(pauseOnResponse){pauseOnResponse=false;original.pause();original.resume();}
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop")))
                        .put("usage",new JSONObject().put("prompt_tokens",8).put("completion_tokens",4).put("total_tokens",12)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        }
        final PhonePort phone=new PhonePort(){
            public boolean isConnected(){return true;}
            public void clearReferences(){}
            public JSONObject observe(boolean pixels,RunControl control)throws Exception {
                assertSame(original,control);control.check();observations++;
                if(firstScreenChanged&&observations==1)return new JSONObject().put("ok",false).put("code","screen_changed");
                if(observations==unconfirmedObservationAt)return new JSONObject().put("ok",false).put("code",unconfirmedObservationCode).put("nativeFailureProof","original-capture-"+observations);
                return new JSONObject().put("ok",true).put("observationId","original-screen-"+observations).put("nodes",new JSONArray());
            }
            public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception {
                assertSame(original,control);assertEquals("phone_list_apps",name);assertFalse(arguments.has("_hostOperationId"));phoneReads++;return new JSONObject().put("ok",true).put("apps",new JSONArray());
            }
        };
        final CapabilityPort port=new CapabilityPort(){
            public JSONArray tools()throws Exception {
                JSONArray result=new JSONArray();for(String name:browserReturn?new String[]{"browser_task_observe"}:new String[]{"fixture_read","fixture_journalled_read","fixture_write"})
                    result.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","native fixture route").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));return result;
            }
            public boolean supports(String name){return browserReturn?"browser_task_observe".equals(name):name.equals("fixture_read")||name.equals("fixture_journalled_read")||name.equals("fixture_write");}
            public boolean sideEffecting(String name){return "fixture_write".equals(name)||"fixture_journalled_read".equals(name);}
            public ExecutionPlanning.Access planningAccess(String name,JSONObject arguments){return supports(name)&&!"fixture_write".equals(name)?ExecutionPlanning.Access.READ:ExecutionPlanning.Access.DENY;}
            public void validate(String name,JSONObject arguments){if(arguments.length()!=0)throw new IllegalArgumentException("Only original empty model arguments are valid");}
            public JSONObject beforeTool(String name,JSONObject arguments,RunControl control){assertSame(original,control);assertFalse(arguments.has("_hostOperationId"));beforeTools++;return null;}
            public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception {
                assertSame(original,control);control.check();dispatched.add(new JSONObject(arguments.toString()));
                if("fixture_write".equals(name)){effects++;throw new AssertionError("No planning effect may enter this route");}
                if(!sideEffecting(name))assertFalse("internal read identity must not alter a pure-read schema",arguments.has("_hostOperationId"));
                else assertEquals(planner.lastRead.operationId,arguments.getString("_hostOperationId"));
                reads++;if(cancelRead){control.cancel();control.check();}if(throwRead)throw new IllegalStateException("original read interrupted");
                if(nullRead)return null;
                JSONObject receipt=new JSONObject().put("ok",true).put("content","Original native source");
                if(lateKnownReadAfterCancel){
                    receipt.put("nativeReceiptMarker","original-read-after-stop");control.cancel();
                    throw new CapabilityPort.ReceiptAfterStop(control,receipt,new InterruptedException("Original read completed before cancellation"));
                }
                if(unknownRead)receipt.put("accepted",JSONObject.NULL);return receipt;
            }
            public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control){afterTools++;}
            public JSONObject contextSnapshot(String query){
                if(dev.pocketengine.browser.BrowserTaskCapabilityPort.OWNER_RETURN_CONTEXT.equals(query)){contextReads++;throw new AssertionError("Planning owner-return must stop before the unmetered context read");}
                return new JSONObject();
            }
        };
        RunOutcome run()throws Exception {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"fixture-only-key","planning-fixture",false,12,10,"openai_chat",1000)
                    .withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            CapabilityPort guarded=PlanningCapabilityPort.wrap(port,exposeHandle?planner:null);
            return new AgentRunner(withPhone?phone:null,guarded,budget).run("original-task","请核对原目标并准备可审阅的计划",config,original,event->{
                if(lateKnownReadAfterCancel&&"tool_trace".equals(event.optString("type"))&&"finished".equals(event.optString("stage"))){
                    String settled=planner.lastRead.operationId;
                    assertEquals("Original read must settle before its trace",ExecutionPlanning.ReadOutcome.RECEIPT,planner.finished.get(settled));
                    assertEquals("original-read-after-stop",planner.actualReceipts.get(settled).optString("nativeReceiptMarker"));
                }
                events.add(event);if(changeBeforeModel&&"context_budget".equals(event.optString("type"))){changeBeforeModel=false;planner.scopeRevision++;}
                if(changeBeforeFinalObservation&&"verification".equals(event.optString("type"))){changeBeforeFinalObservation=false;planner.scopeRevision++;}
                if(changeAfterWait&&"tool".equals(event.optString("type"))&&"phone_wait".equals(event.optString("tool"))&&"completed".equals(event.optString("status"))){changeAfterWait=false;planner.scopeRevision++;}
            },this);
        }
        public ExecutionPlanning planning(){return exposeHandle?planner:null;}
        public JSONObject restore()throws Exception{return new JSONObject().put("executionPlanningControlled",true).put("messages",history).put("steps",steps);}
        public void checkpoint(JSONArray messages,int count,JSONObject observation)throws Exception{history=new JSONArray(messages.toString());steps=count;}
        public String beginOperation(String tool,JSONObject args){prepared++;return UUID.randomUUID().toString();}
        public void finishOperation(String operation,JSONObject receipt){effectReceipts++;}
        public void close(){server.stop(0);}
    }

    /** Private-constructor Snapshot/ReadClaim values are minted by this one original native host. */
    private static final class NativePlanning extends ExecutionPlanning {
        volatile long scopeRevision=1,planRevision;String phase="planning",reference="",planHash="";
        int debits,submissions,phaseRequests;JSONObject submitted;ReadClaim lastRead;
        final Map<String,ReadClaim> live=new LinkedHashMap<>();final Map<String,ReadOutcome> finished=new LinkedHashMap<>();final List<String> readTools=new ArrayList<>();
        final Map<String,JSONObject> actualReceipts=new LinkedHashMap<>();
        NativePlanning(RunControl original){super(original);}
        public Snapshot capture(){return snapshot(scopeRevision,phase,planRevision,reference,planHash);}
        public void requireCurrent(Snapshot expected)throws Exception {
            requireOwned(expected);original().check();
            if(original().isPaused()||expected.pauseRevision!=original().pauseRevision()||expected.scopeRevision!=scopeRevision||!expected.phase.equals(phase)||expected.planRevision!=planRevision||!expected.planArtifactRef.equals(reference)||!expected.planHash.equals(planHash))throw new SecurityException("original planning revision changed");
        }
        public ReadClaim beginRead(Snapshot expected,String tool,JSONObject args,String call,Access access)throws Exception {
            requireCurrent(expected);assertTrue(expected.planning());assertEquals(Access.READ,access);assertFalse(args.has("_hostOperationId"));assertTrue(live.isEmpty());assertFalse(call.isEmpty());
            debits++;ReadClaim claim=readClaim(expected,UUID.randomUUID().toString(),tool,hash(args));live.put(claim.operationId,claim);readTools.add(tool);lastRead=claim;return claim;
        }
        public void finishRead(ReadClaim claim,JSONObject receipt,ReadOutcome outcome)throws Exception{requireOwned(claim);assertSame(claim,live.remove(claim.operationId));assertNotNull(receipt);assertNull(finished.put(claim.operationId,outcome));assertNull(actualReceipts.put(claim.operationId,new JSONObject(receipt.toString())));}
        public void requireReadDispatch(String tool,JSONObject args,RunControl original)throws Exception {
            assertSame(original(),original);ReadClaim claim=live.get(args.optString("_hostOperationId"));assertNotNull("dispatch requires the exact minted read",claim);requireCurrent(claim.snapshot);assertEquals(tool,claim.tool);
            JSONObject originalArguments=new JSONObject(args.toString());originalArguments.remove("_hostOperationId");assertEquals(claim.argumentsHash,hash(originalArguments));
        }
        public JSONObject submitPlan(Snapshot expected,JSONObject proposal,String call)throws Exception {
            requireCurrent(expected);assertTrue(expected.planning());assertTrue(live.isEmpty());submitted=ExecutionPlan.checked(proposal);submissions++;debits++;scopeRevision++;planRevision++;reference=UUID.randomUUID().toString();planHash=ExecutionPlan.hash(submitted);
            return new JSONObject().put("ok",true).put("code","execution_plan_awaiting_review").put("approvalId",UUID.randomUUID().toString()).put("planRevision",planRevision).put("planArtifactRef",reference).put("planHash",planHash).put("scopeRevision",scopeRevision).put("effectsDispatched",false);
        }
        public JSONObject requestPhase(Snapshot expected,String requested,String reason,String call)throws Exception {
            requireCurrent(expected);assertTrue(live.isEmpty());phaseRequests++;debits++;return new JSONObject().put("ok",true).put("code","execution_phase_awaiting_owner").put("requestedPhase",requested).put("executionPhase",phase).put("effectsDispatched",false);
        }
    }

    @Test public void catalogueAndActualDispatchBothRejectForgedWritesWithoutPreparingEffects()throws Exception {
        try(Fixture f=new Fixture()){
            f.replies.add(calls(call("write","fixture_write",new JSONObject()),call("read","fixture_read",new JSONObject())));RunOutcome outcome=f.run();
            assertEquals(outcome.summary,"awaiting_user",outcome.status);assertFalse(offered(f.requests.get(0),"fixture_write"));assertTrue(offered(f.requests.get(0),"fixture_read"));assertTrue(offered(f.requests.get(0),ExecutionPlanning.SUBMIT));
            assertEquals(0,f.effects);assertEquals(1,f.reads);assertEquals(1,f.planner.debits);assertEquals(0,f.prepared);assertEquals(0,f.effectReceipts);assertEquals(ExecutionPlanning.ReadOutcome.RECEIPT,f.planner.finished.get(f.planner.lastRead.operationId));
            assertFalse(outcome.evidence.has("nativeFinalAnswer"));assertFalse(outcome.evidence.getBoolean("executionAuthorized"));assertEquals("planning",f.planner.phase);
        }
    }
    @Test public void modelSuppliedOperationMetadataIsRejectedBeforeAnyNativeReadIsMinted()throws Exception {
        try(Fixture f=new Fixture()){
            f.replies.add(calls(call("forged","fixture_read",new JSONObject().put("_hostOperationId",UUID.randomUUID().toString()))));RunOutcome outcome=f.run();
            assertEquals("awaiting_user",outcome.status);assertEquals(0,f.reads);assertEquals(0,f.planner.debits);assertEquals(0,f.prepared);assertTrue(f.history.toString().contains("invalid_arguments"));
        }
    }
    @Test public void automaticPhoneReadsAndDirectBuiltinsUseTheSameReadLedgerWithoutDoubleChargingExplicitObserve()throws Exception {
        try(Fixture f=new Fixture()){
            f.withPhone=true;f.replies.add(calls(call("observe","phone_observe",new JSONObject()),call("apps","phone_list_apps",new JSONObject()),call("wait","phone_wait",new JSONObject().put("ms",0)),
                    call("obstacle",RunOutcomeTools.OBSTACLE,new JSONObject().put("kind","environment").put("detail","owner context needed")),call("ask",RunOutcomeTools.ASK,new JSONObject().put("question","哪一份原资料需要先核对？"))));
            RunOutcome outcome=f.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(3,f.observations);assertEquals(1,f.phoneReads);assertEquals(7,f.planner.debits);assertEquals(7,f.planner.finished.size());
            assertEquals(3,java.util.Collections.frequency(f.planner.readTools,"phone_observe"));assertEquals(0,f.prepared);assertEquals(1,f.requests.size());assertTrue(f.planner.live.isEmpty());
        }
    }
    @Test public void submittedPlanStopsEveryRemainingOldDecisionAndWaitsForExactOwnerReview()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject proposed=plan();f.replies.add(calls(call("submit",ExecutionPlanning.SUBMIT,new JSONObject().put("plan",proposed)),call("tail-read","fixture_read",new JSONObject()),call("tail-write","fixture_write",new JSONObject())));
            RunOutcome outcome=f.run();assertEquals("awaiting_user",outcome.status);assertEquals(1,f.planner.submissions);assertEquals(ExecutionPlan.hash(proposed),f.planner.planHash);assertEquals(1,f.planner.planRevision);assertEquals(2,f.planner.scopeRevision);
            assertEquals(0,f.beforeTools);assertEquals(0,f.reads);assertEquals(0,f.prepared);assertEquals(1,f.planner.debits);assertEquals(1,f.requests.size());assertEquals("planning",f.planner.phase);
            assertEquals(2,f.history.toString().split("planning_review_required",-1).length-1);assertFalse(outcome.evidence.getBoolean("executionAuthorized"));assertFalse(outcome.evidence.has("nativeFinalAnswer"));
        }
    }
    @Test public void currentCredentialCannotEnterACompletePublicPlanOrPhaseRequest()throws Exception {
        for(String name:new String[]{ExecutionPlanning.SUBMIT,ExecutionPlanning.REQUEST})try(Fixture f=new Fixture()){
            JSONObject arguments=name.equals(ExecutionPlanning.SUBMIT)?new JSONObject().put("plan",plan().put("goal","fixture-only-key")):new JSONObject().put("phase","executing").put("reason","fixture-only-key");
            f.replies.add(calls(call("private-proposal",name,arguments)));RunOutcome outcome=f.run();
            assertEquals("awaiting_user",outcome.status);assertEquals(0,f.planner.submissions);assertEquals(0,f.planner.phaseRequests);assertEquals(0,f.planner.debits);assertTrue(f.history.toString().contains("planning_private_text"));
            assertFalse(f.history.toString().contains("fixture-only-key"));assertFalse(outcome.evidence.toString().contains("fixture-only-key"));assertEquals(0,f.beforeTools);
        }
    }
    @Test public void enterAndExitAreOnlyRequestsAndNeverChangeTheCurrentPhaseOrGrantBudget()throws Exception {
        for(String current:new String[]{"planning","executing"})try(Fixture f=new Fixture()){
            f.planner.phase=current;String requested=current.equals("planning")?"executing":"planning";
            f.replies.add(calls(call("request",ExecutionPlanning.REQUEST,new JSONObject().put("phase",requested).put("reason","Please review this original task")),call("unsent","fixture_read",new JSONObject())));
            RunOutcome outcome=f.run();assertEquals("awaiting_user",outcome.status);assertEquals(current,f.planner.phase);assertEquals(1,f.planner.scopeRevision);assertEquals(0,f.planner.planRevision);assertEquals(1,f.planner.phaseRequests);assertEquals(1,f.planner.debits);assertEquals(0,f.reads);assertEquals(0,f.prepared);assertEquals(0,f.beforeTools);
            assertEquals(current.equals("planning"),offered(f.requests.get(0),ExecutionPlanning.SUBMIT));
        }
    }
    @Test public void controlledRestoreWithoutItsNativeHandleFailsBeforeObservationOrModelRequest()throws Exception {
        try(Fixture f=new Fixture()){
            f.exposeHandle=false;f.withPhone=true;RunOutcome outcome=f.run();assertEquals("failed",outcome.status);assertEquals(0,f.observations);assertEquals(0,f.requests.size());assertEquals(0,f.prepared);assertEquals(0,f.planner.debits);
        }
    }
    @Test public void scopeChangeBeforePhysicalModelDispatchRejectsTheOldProjection()throws Exception {
        try(Fixture f=new Fixture()){
            f.changeBeforeModel=true;RunOutcome outcome=f.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(0,f.requests.size());assertEquals(0,f.reads);assertEquals(0,f.prepared);
        }
    }
    @Test public void scopeChangeDuringTheModelResponseDropsTheWholeOldDecision()throws Exception {
        try(Fixture f=new Fixture()){
            f.changeOnResponse=true;f.replies.add(calls(call("old-read","fixture_read",new JSONObject())));RunOutcome outcome=f.run();assertEquals("awaiting_user",outcome.status);assertEquals(1,f.requests.size());assertEquals(0,f.reads);assertEquals(0,f.planner.debits);assertEquals(0,f.prepared);
        }
    }
    @Test public void cancellationDuringReadKeepsTheOriginalExpenseAndAnUnconfirmedReadWithoutReplay()throws Exception {
        try(Fixture f=new Fixture()){
            f.cancelRead=true;f.replies.add(calls(call("read","fixture_read",new JSONObject())));RunOutcome outcome=f.run();assertEquals("cancelled",outcome.status);assertEquals(1,f.requests.size());assertEquals(1,f.reads);assertEquals(1,f.planner.debits);assertEquals(ExecutionPlanning.ReadOutcome.UNCONFIRMED,f.planner.finished.get(f.planner.lastRead.operationId));assertTrue(f.planner.live.isEmpty());assertEquals(0,f.prepared);
        }
    }
    @Test public void actualReadReceiptAfterCancellationIsSettledOnceWithoutAnotherModelRequestOrDispatch()throws Exception {
        try(Fixture f=new Fixture()){
            f.lateKnownReadAfterCancel=true;f.replies.add(calls(call("read","fixture_read",new JSONObject()),call("tail-read","fixture_read",new JSONObject())));
            RunOutcome outcome=f.run();String operation=f.planner.lastRead.operationId;
            assertEquals("cancelled",outcome.status);assertEquals(1,f.requests.size());assertEquals(1,f.reads);assertEquals(1,f.planner.debits);
            assertEquals(ExecutionPlanning.ReadOutcome.RECEIPT,f.planner.finished.get(operation));
            assertEquals("original-read-after-stop",f.planner.actualReceipts.get(operation).getString("nativeReceiptMarker"));
            assertTrue(f.planner.actualReceipts.get(operation).getBoolean("ok"));assertEquals(1,f.planner.finished.size());
            assertTrue(f.planner.live.isEmpty());assertEquals(0,f.prepared);assertEquals(0,f.effectReceipts);
            assertFalse(f.history.toString().contains("original-read-after-stop"));
            List<JSONObject> finishedTraces=f.events.stream().filter(event->"tool_trace".equals(event.optString("type"))&&"finished".equals(event.optString("stage"))).toList();
            assertEquals("Exactly the issued read has a terminal trace",1,finishedTraces.size());
            JSONObject trace=finishedTraces.get(0);
            assertEquals(operation,trace.optString("operationId"));assertEquals("read",trace.getString("providerToolCallId"));
            assertEquals("fixture_read",trace.getString("tool"));assertEquals("COMPLETED",trace.getString("outcome"));assertTrue(trace.getBoolean("dispatchEntered"));
            assertEquals("original-read-after-stop",trace.getJSONObject("output").getString("nativeReceiptMarker"));
            assertTrue(trace.getJSONObject("output").getBoolean("ok"));assertEquals("Original native source",trace.getJSONObject("output").getString("content"));
            assertEquals(dev.pocketengine.core.ScopedToolCatalog.fingerprint(f.planner.actualReceipts.get(operation)),dev.pocketengine.core.ScopedToolCatalog.fingerprint(trace.getJSONObject("output")));
            assertEquals(1,f.beforeTools);assertEquals(1,f.dispatched.size());assertEquals(0,f.afterTools);
            assertTrue(f.events.stream().noneMatch(event->"tool_trace".equals(event.optString("type"))&&"tail-read".equals(event.optString("providerToolCallId"))));
            assertThrows(InterruptedException.class,()->f.planner.beginRead(f.planner.capture(),"fixture_read",new JSONObject(),"must-not-dispatch",ExecutionPlanning.Access.READ));
            assertEquals(1,f.planner.debits);
        }
    }
    @Test public void thrownUnknownAndEmptyReadResultsRemainUnconfirmedAndDoNotTriggerAnotherModelRequest()throws Exception {
        for(int mode=0;mode<3;mode++)try(Fixture f=new Fixture()){
            f.throwRead=mode==0;f.unknownRead=mode==1;f.nullRead=mode==2;f.replies.add(calls(call("read","fixture_read",new JSONObject())));RunOutcome outcome=f.run();
            assertEquals("mode "+mode+": "+outcome.summary,"awaiting_user",outcome.status);assertEquals(1,f.requests.size());assertEquals(1,f.reads);assertEquals(1,f.planner.debits);assertEquals(0,f.prepared);assertEquals(ExecutionPlanning.ReadOutcome.UNCONFIRMED,f.planner.finished.get(f.planner.lastRead.operationId));assertTrue(f.planner.live.isEmpty());
        }
    }
    @Test public void journalledNativeReadGetsTheSameClaimIdWithoutAnEffectPreparedRecord()throws Exception {
        try(Fixture f=new Fixture()){
            f.replies.add(calls(call("read","fixture_journalled_read",new JSONObject())));RunOutcome outcome=f.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(1,f.reads);assertEquals(f.planner.lastRead.operationId,f.dispatched.get(0).getString("_hostOperationId"));assertEquals(0,f.prepared);assertEquals(0,f.effectReceipts);assertEquals(1,f.planner.finished.size());
        }
    }
    @Test public void planningCaptureFailureDoesNotHideASecondUnchargedObservation()throws Exception {
        try(Fixture f=new Fixture()){
            f.withPhone=true;f.firstScreenChanged=true;RunOutcome outcome=f.run();assertEquals("awaiting_user",outcome.status);assertEquals(1,f.observations);assertEquals(1,f.planner.debits);assertEquals(0,f.requests.size());assertEquals(ExecutionPlanning.ReadOutcome.FAILED,f.planner.finished.get(f.planner.lastRead.operationId));
        }
        try(Fixture f=new Fixture()){
            f.planner.phase="executing";f.withPhone=true;f.firstScreenChanged=true;RunOutcome outcome=f.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(3,f.observations);assertEquals(0,f.planner.debits);assertEquals(1,f.requests.size());
        }
    }
    @Test public void finalVerificationKeepsTheMainTurnScopeAndCannotReadUnderAReplacementScope()throws Exception {
        try(Fixture f=new Fixture()){
            f.withPhone=true;f.changeBeforeFinalObservation=true;RunOutcome outcome=f.run();
            assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(1,f.requests.size());assertEquals(1,f.observations);assertEquals(1,f.planner.debits);assertEquals(1,f.planner.finished.size());assertEquals(0,f.prepared);assertFalse(outcome.evidence.has("nativeFinalAnswer"));
        }
    }
    @Test public void automaticObservationAfterWaitCannotAdoptTheNextScopeForAnOldBatch()throws Exception {
        try(Fixture f=new Fixture()){
            f.withPhone=true;f.changeAfterWait=true;f.replies.add(calls(call("wait","phone_wait",new JSONObject().put("ms",0)),call("old-tail","fixture_read",new JSONObject())));
            RunOutcome outcome=f.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertEquals(1,f.requests.size());assertEquals(1,f.observations);assertEquals(2,f.planner.debits);assertEquals(List.of("phone_observe","phone_wait"),f.planner.readTools);assertEquals(2,f.planner.finished.size());assertEquals(0,f.reads);assertEquals(0,f.prepared);
        }
    }
    @Test public void resumedAutomaticUnknownObservationKeepsItsActualReceiptAndStopsBeforeAnotherMainRequest()throws Exception {
        for(String code:new String[]{"empty_observation","action_timeout"})try(Fixture f=new Fixture()){
            f.withPhone=true;f.pauseOnResponse=true;f.unconfirmedObservationAt=2;f.unconfirmedObservationCode=code;
            RunOutcome outcome=f.run();assertEquals(code+": "+outcome.summary,"awaiting_user",outcome.status);assertEquals(1,f.requests.size());assertEquals(2,f.observations);assertEquals(2,f.planner.debits);
            String operation=f.planner.lastRead.operationId;assertEquals(operation,outcome.evidence.getString("planningReadUnconfirmed"));assertEquals(ExecutionPlanning.ReadOutcome.UNCONFIRMED,f.planner.finished.get(operation));
            assertEquals(2,f.planner.finished.size());assertEquals(2,f.planner.actualReceipts.size());assertEquals(code,f.planner.actualReceipts.get(operation).getString("code"));assertEquals("original-capture-2",f.planner.actualReceipts.get(operation).getString("nativeFailureProof"));
            assertTrue(f.planner.live.isEmpty());assertEquals(0,f.prepared);assertEquals(0,f.reads);assertEquals(0,f.effects);
        }
    }
    @Test public void browserOwnerReturnStopsForReadSourceReviewWithoutAContextReadOrPhoneFallback()throws Exception {
        try(Fixture f=new Fixture()){
            f.browserReturn=true;f.pauseOnResponse=true;RunOutcome outcome=f.run();assertEquals(outcome.summary,"awaiting_user",outcome.status);assertTrue(outcome.evidence.getBoolean("planningReadSourceReviewRequired"));
            assertEquals("planning",f.planner.phase);assertEquals(0,f.contextReads);assertEquals(0,f.observations);assertEquals(0,f.planner.debits);assertEquals(0,f.reads);assertEquals(1,f.requests.size());
        }
    }
    private static dev.pocketengine.runtime.context.CompactionJournal unopenedCompaction(){
        return new dev.pocketengine.runtime.context.CompactionJournal(){
            public FrozenPrefix freeze(dev.pocketengine.runtime.context.CompactionPlan plan,RunControl original){throw new AssertionError("Stale planning may not freeze a summary prefix");}
            public PublishReceipt publish(FrozenPrefix expected,dev.pocketengine.runtime.context.SummaryDraft summary,RunControl original){throw new AssertionError("Stale planning may not publish a checkpoint");}
            public boolean current(FrozenPrefix expected,RunControl original){throw new AssertionError("No prior summary is part of this fixture");}
        };
    }
    @Test public void compactionDecorationKeepsTheSameNativeHandleAndControlledRestore()throws Exception {
        try(Fixture f=new Fixture()){
            RunJournal decorated=f.withCompaction(unopenedCompaction());assertSame(f.planner,decorated.planning());assertTrue(decorated.restore().getBoolean("executionPlanningControlled"));
            assertSame(decorated.planning(),decorated.withCompaction(unopenedCompaction()).planning());assertEquals(0,f.planner.debits);
        }
    }
    @Test public void bothSummaryEntrypointsRefuseAChangedMainTurnBeforeFreezeOrNetwork()throws Exception {
        try(Fixture f=new Fixture()){
            dev.pocketengine.runtime.context.ContextCompactor compactor=new dev.pocketengine.runtime.context.ContextCompactor(f,unopenedCompaction(),selected->null);
            ExecutionPlanning.Snapshot turn=f.planner.capture();f.planner.scopeRevision++;
            ModelConfig config=new ModelConfig("https://example.invalid","fixture-key","fixture-model",false,8,10);
            dev.pocketengine.runtime.context.RunContextBudget budget=new dev.pocketengine.runtime.context.RunContextBudget(selected->null);
            assertThrows(SecurityException.class,()->compactor.request(new JSONArray(),new JSONArray(),List.of(),new JSONArray(),config,f.original,budget,f.planner,turn));
            assertThrows(SecurityException.class,()->compactor.afterContextLimit(new JSONArray(),new JSONArray(),List.of(),new JSONArray(),config,f.original,budget,f.planner,turn));
            assertEquals(0,f.requests.size());assertEquals(0,f.planner.debits);assertEquals(0,f.prepared);
        }
    }
}
