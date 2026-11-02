package dev.pocketengine.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.local.project.ProjectServiceDrainPending;
import dev.pocketengine.runtime.context.CompactionJournal;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.context.SummaryDraft;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Runner/Gateway/HTTP; fixture native proof only. Actual native job/ACL proof belongs to C2's SQL/runtime suite. */
public final class RunnerOwnedServiceTest {
    private static final String TASK="owned-service-task",RUN="original-run",KEY="fixture-key";
    private interface Answer {JSONObject reply(int request)throws Exception;}
    private static final class Wire implements AutoCloseable {
        final HttpServer server;final List<JSONObject> requests=Collections.synchronizedList(new ArrayList<>());
        Wire(Answer answer)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{
            try{requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));byte[] body=answer.reply(requests.size()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1",KEY,"fixture",false,8,10,"openai_chat",1000);}
        public void close(){server.stop(0);}
    }
    private static JSONObject response(JSONObject message,String finish)throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",finish).put("message",message)));}
    private static JSONObject call(String id,String tool,JSONObject args)throws Exception{return response(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString())))),"tool_calls");}
    private static JSONObject answer()throws Exception{return response(new JSONObject().put("role","assistant").put("content","已读取原服务状态，业务结果仍需核对。"),"stop");}
    private static JSONObject running(String operation)throws Exception{return new JSONObject().put("code","project_running").put("status","running").put("ok",true).put("accepted",true).put("receiptReceived",false).put("terminal",false).put("replayAllowed",false).put("serviceReady",false)
            .put("executionId",operation).put("operationId",operation).put("launchAckIdentity","a".repeat(64)).put("binding",new JSONObject().put("taskId",TASK).put("runId",RUN).put("operationId",operation).put("coordinatorEpoch",1));}
    private static final class Journal implements RunJournal {
        JSONObject saved=new JSONObject();final List<JSONObject> receipts=new ArrayList<>();int begins,proofs,exitProofs;boolean prove=true,exitProof=true;JSONObject exitOverride;java.util.function.BooleanSupplier closed=()->false;
        public JSONObject restore()throws Exception{return new JSONObject(saved.toString());}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{saved=new JSONObject().put("messages",new JSONArray(messages.toString())).put("steps",steps).put("observation",observation);}
        public String beginOperation(String name,JSONObject args){assertEquals("project_execute",name);return "launch-"+(++begins);}
        public void finishOperation(String id,JSONObject receipt)throws Exception{assertEquals("launch-1",id);receipts.add(new JSONObject(receipt.toString()));}
        public boolean hasNativeProjectRunningAck(String id,JSONObject delivered){proofs++;assertFalse(receipts.isEmpty());assertEquals("launch-1",id);return prove;}
        public JSONObject projectServiceExitEvidence(JSONArray ids)throws Exception{exitProofs++;assertTrue("audit before original cleanup",closed.getAsBoolean());assertEquals("launch-1",ids.getString(0));return !exitProof?null:exitOverride==null?exits():new JSONObject(exitOverride.toString());}
    }
    private static class Port implements CapabilityPort {
        final RunControl original;final List<String> order;boolean running,closed,cancelAfterLaunch,observationUnknown;int launches,reads;JSONObject mutation;ProjectServiceDrainPending endPending,closePending;boolean genericCloseFailure;
        Port(RunControl original,List<String> order){this.original=original;this.order=order;}
        public JSONArray tools()throws Exception{JSONArray result=new JSONArray();for(String name:List.of("project_execute","project_status"))result.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","fixture native bound project tool").put("parameters",new JSONObject().put("type","object"))));return result;}
        public boolean supports(String name){return name.equals("project_execute")||name.equals("project_status");}
        public boolean sideEffecting(String name){return name.equals("project_execute");}
        public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{
            assertSame(original,control);control.check();order.add(name);
            if(name.equals("project_execute")){launches++;running=true;JSONObject receipt=RunnerOwnedServiceTest.running(args.getString("_hostOperationId"));if(mutation!=null){java.util.Iterator<String> keys=mutation.keys();while(keys.hasNext()){String key=keys.next();receipt.put(key,mutation.get(key));}}if(cancelAfterLaunch)control.cancel();return receipt;}
            reads++;assertTrue(running);assertEquals("launch-1",args.getString("executionId"));assertFalse(args.has("_hostOperationId"));
            if(observationUnknown)return new JSONObject().put("ok",false).put("code","project_pending_unknown").put("status","unknown").put("accepted",JSONObject.NULL);
            return new JSONObject().put("ok",true).put("code","project_service_observation").put("status","observed").put("accepted",true).put("queryComplete",true).put("serviceStatus","running").put("serviceReady",false).put("processTerminal",false).put("executionId","launch-1").put("operationId","launch-1");
        }
        public void endRun(RunControl control){assertSame(original,control);order.add("end");if(endPending!=null)throw endPending;}
        public void close(){closed=true;order.add("close");if(closePending!=null)throw closePending;if(genericCloseFailure)throw new IllegalStateException("fixture other extension unavailable");running=false;}
    }
    private static RunControl control(){RunControl control=new RunControl();control.bindHostRun(TASK,RUN,1);return control;}
    private static ContextBudgetPolicy.Source budgets(){return config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(100000,1000,1000,1000000,0,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);}
    private static RunOutcome run(Wire wire,Port port,RunJournal journal,List<JSONObject> events)throws Exception{if(journal instanceof Journal)((Journal)journal).closed=()->port.closed;return new AgentRunner(null,port,budgets()).run(TASK,"显式启动原服务并只读核对，不自动修改",wire.config(),port.original,event->{events.add(event);if(event.optString("type").equals("result")){assertTrue("result published before owned cleanup",port.closed);port.order.add("result");}},journal);}
    private static Wire serviceWire()throws Exception{return new Wire(n->n==1?call("launch-call","project_execute",new JSONObject()):n==2?call("read-call","project_status",new JSONObject().put("executionId","launch-1")):answer());}
    private static JSONObject exits()throws Exception{return new JSONObject().put("format","pocketworld.project-service-exits.v1").put("taskId",TASK).put("runId",RUN).put("coordinatorEpoch",1).put("allReleased",true).put("executions",new JSONArray().put(new JSONObject().put("operationId","launch-1").put("executionId","launch-1").put("status","completed").put("exitCode",0).put("terminationSignal",0).put("cancelRequested",true).put("timedOut",false).put("processExited",true).put("processGroupAbsent",true).put("resourcesReleased",true)));}
    private static ProjectServiceDrainPending pending()throws Exception{return new ProjectServiceDrainPending(new JSONObject().put("taskId",TASK).put("runId",RUN).put("coordinatorEpoch",1).put("executionIds",new JSONArray().put("launch-1")).put("groupAbsent",false).put("leaseReleased",false).put("privateDetail",KEY));}

    @Test public void explicitNativeRetentionYieldsWorkerAndResumesSameRunnerWithoutRelaunch()throws Exception {
        RunControl control=control();Journal journal=new Journal();Port port=new Port(control,new ArrayList<>());journal.closed=()->port.closed;
        var retain=new java.util.concurrent.atomic.AtomicBoolean(true);List<JSONObject> events=new ArrayList<>();
        try(Wire wire=serviceWire()){
            AgentRunner runner=new AgentRunner(null,port,budgets());runner.bindDevelopmentContinuation(retain::get);
            RunOutcome held=runner.run(TASK,"keep original service",wire.config(),control,events::add,journal);
            assertEquals("owned_running",held.status);assertTrue(runner.developmentYielded());assertFalse(port.closed);assertTrue(port.running);assertEquals(1,port.launches);assertFalse(events.stream().anyMatch(e->"result".equals(e.optString("type"))));
            retain.set(false);RunOutcome ended=runner.run(TASK,"keep original service",wire.config(),control,events::add,journal);
            assertEquals("needs_confirmation",ended.status);assertFalse(runner.developmentYielded());assertTrue(port.closed);assertEquals("original launch not replayed",1,port.launches);assertTrue(ended.evidence.getBoolean("projectServiceDrainConfirmedAtExit"));
        }
    }
    @Test public void repeatedOwnedTurnsDoNotResetOriginalModelRoundCeiling()throws Exception {
        RunControl control=control();Journal journal=new Journal();Port port=new Port(control,new ArrayList<>());journal.closed=()->port.closed;
        try(Wire wire=serviceWire()){
            AgentRunner runner=new AgentRunner(null,port,budgets());runner.bindDevelopmentContinuation(()->true);
            RunOutcome result=null;for(int turn=0;turn<12;turn++){result=runner.run(TASK,"original bounded service",wire.config(),control,event->{},journal);if(!runner.developmentYielded())break;}
            assertNotNull(result);assertFalse(runner.developmentYielded());assertEquals("needs_confirmation",result.status);assertTrue(result.summary.contains("模型调用上限"));assertEquals("same original model rounds, not a fresh ceiling per turn",9,wire.requests.size());assertEquals(1,port.launches);assertTrue(port.closed);
        }
    }
    @Test public void twoRetainedServicesDoNotOccupyTheTwoModelWorkers()throws Exception {
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);List<Port> ports=new ArrayList<>();List<Wire> wires=new ArrayList<>();
        try{
            List<java.util.concurrent.Future<RunOutcome>> results=new ArrayList<>();
            for(int i=0;i<3;i++){Port port=new Port(control(),new ArrayList<>());ports.add(port);Wire wire=serviceWire();wires.add(wire);Journal journal=new Journal();journal.closed=()->port.closed;AgentRunner runner=new AgentRunner(null,port,budgets());if(i<2)runner.bindDevelopmentContinuation(()->true);results.add(workers.submit(()->runner.run(TASK,"original",wire.config(),port.original,event->{},journal)));}
            assertEquals("owned_running",results.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS).status);assertEquals("owned_running",results.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS).status);assertEquals("needs_confirmation",results.get(2).get(10,java.util.concurrent.TimeUnit.SECONDS).status);assertFalse(ports.get(0).closed);assertFalse(ports.get(1).closed);assertTrue(ports.get(2).closed);
        }finally{for(Port port:ports)port.close();for(Wire wire:wires)wire.close();workers.shutdownNow();}
    }
    @Test public void nativeAckStaysNonterminalAndTheSameRunnerCanReadTheOriginalService()throws Exception {
        RunControl control=control();Journal journal=new Journal();List<JSONObject> events=new ArrayList<>();Port port=new Port(control,new ArrayList<>());
        try(Wire wire=serviceWire()){
            RunOutcome result=run(wire,port,journal,events);assertEquals("needs_confirmation",result.status);assertEquals(3,wire.requests.size());assertEquals(1,port.launches);assertEquals(1,port.reads);assertEquals(1,journal.proofs);assertFalse(port.running);
            JSONArray messages=wire.requests.get(1).getJSONArray("messages");JSONObject launch=null;for(int i=0;i<messages.length();i++)if("launch-call".equals(messages.getJSONObject(i).optString("tool_call_id")))launch=new JSONObject(messages.getJSONObject(i).getString("content"));
            assertNotNull(launch);assertFalse(launch.getBoolean("terminal"));assertFalse(launch.getBoolean("receiptReceived"));assertEquals("launch-1",launch.getString("executionId"));
            assertTrue(events.stream().anyMatch(e->e.optString("type").equals("tool")&&e.optJSONObject("receipt")!=null&&e.optJSONObject("receipt").optString("code").equals("project_running")&&e.optString("status").equals("running")));
            assertFalse(events.stream().anyMatch(e->e.optString("type").equals("tool")&&e.optJSONObject("receipt")!=null&&e.optJSONObject("receipt").optString("code").equals("project_running")&&e.optString("status").equals("completed")));
            assertTrue(result.evidence.getBoolean("projectServiceDrainConfirmedAtExit"));assertEquals(1,journal.exitProofs);assertTrue(result.summary.contains("launch-1"));assertTrue(result.summary.contains("退出码 0"));assertTrue(port.order.indexOf("close")<port.order.indexOf("result"));assertEquals(1,events.stream().filter(e->e.optString("type").equals("result")).count());
        }
    }
    @Test public void aJsonMarkerWithoutOriginalNativeProofCannotTriggerAnotherModelRequest()throws Exception {
        Journal journal=new Journal();journal.prove=false;Port port=new Port(control(),new ArrayList<>());try(Wire wire=serviceWire()){
            RunOutcome result=run(wire,port,journal,new ArrayList<>());assertEquals("awaiting_user",result.status);assertTrue(result.evidence.getBoolean("unverifiedProjectRunningAck"));assertEquals(1,wire.requests.size());assertEquals(0,port.reads);assertTrue(port.closed);
        }
    }
    @Test public void aLegacyJournalDefaultDoesNotPromoteRunningFieldsIntoNativeAuthority()throws Exception {
        Journal nativeRows=new Journal();RunJournal legacy=new RunJournal(){public JSONObject restore()throws Exception{return nativeRows.restore();}public void checkpoint(JSONArray m,int s,JSONObject o)throws Exception{nativeRows.checkpoint(m,s,o);}public String beginOperation(String n,JSONObject a){return nativeRows.beginOperation(n,a);}public void finishOperation(String id,JSONObject r)throws Exception{nativeRows.finishOperation(id,r);}};
        Port port=new Port(control(),new ArrayList<>());try(Wire wire=serviceWire()){RunOutcome result=run(wire,port,legacy,new ArrayList<>());assertEquals("awaiting_user",result.status);assertEquals(0,nativeRows.proofs);assertEquals(1,wire.requests.size());}
    }
    @Test public void contradictoryOrForeignRunningAckCannotReachTheProofOrNextTurn()throws Exception {
        for(JSONObject wrong:List.of(new JSONObject().put("accepted",JSONObject.NULL),new JSONObject().put("terminal",true),new JSONObject().put("operationId","another-operation"),new JSONObject().put("launchAckIdentity","not-a-native-sha"))){
            Journal journal=new Journal();Port port=new Port(control(),new ArrayList<>());port.mutation=wrong;try(Wire wire=serviceWire()){RunOutcome result=run(wire,port,journal,new ArrayList<>());assertEquals("awaiting_user",result.status);assertEquals(0,journal.proofs);assertEquals(1,wire.requests.size());}
        }
    }
    @Test public void realCompactionDecorationForwardsTheSameOriginalJournalProof()throws Exception {
        Journal original=new Journal();CompactionJournal host=new CompactionJournal(){public FrozenPrefix freeze(CompactionPlan p,RunControl c){throw new AssertionError("small fixture must not summarize");}public PublishReceipt publish(FrozenPrefix p,SummaryDraft d,RunControl c){throw new AssertionError();}public boolean current(FrozenPrefix p,RunControl c){return false;}};
        Port port=new Port(control(),new ArrayList<>());original.closed=()->port.closed;try(Wire wire=serviceWire()){RunOutcome result=run(wire,port,original.withCompaction(host),new ArrayList<>());assertEquals("needs_confirmation",result.status);assertEquals(1,original.proofs);assertEquals(3,wire.requests.size());}
    }
    @Test public void ownedServiceDrainPendingIsNotMisreportedAsAnExtensionOrCompletedTask()throws Exception {
        Journal journal=new Journal();journal.exitProof=false;Port port=new Port(control(),new ArrayList<>());port.closePending=pending();List<JSONObject> events=new ArrayList<>();try(Wire wire=new Wire(n->answer())){
            RunOutcome result=run(wire,port,journal,events);assertEquals("awaiting_user",result.status);assertTrue(result.evidence.getBoolean("projectServiceDrainPending"));assertFalse(result.evidence.getBoolean("projectServiceDrainConfirmedAtExit"));assertTrue(result.summary.contains("原开发服务"));assertFalse(result.summary.contains("扩展隔离实例"));assertFalse(result.evidence.toString().contains(KEY));assertEquals("awaiting_user",events.get(events.size()-1).getString("status"));
        }
    }
    @Test public void cancellationCannotHideAnUnconfirmedServiceOrLeaseBehindATerminalCancelledLabel()throws Exception {
        Journal journal=new Journal();journal.exitProof=false;RunControl control=control();Port port=new Port(control,new ArrayList<>());port.cancelAfterLaunch=true;port.closePending=pending();try(Wire wire=serviceWire()){
            RunOutcome result=run(wire,port,journal,new ArrayList<>());assertTrue(control.isCancelled());assertEquals("awaiting_user",result.status);assertTrue(result.evidence.getBoolean("projectServiceDrainPending"));assertEquals(1,wire.requests.size());
        }
    }
    @Test public void onlyOriginalExitAuditCanClearEarlyPendingAndRetainsTheObservation()throws Exception {
        for(boolean proved:new boolean[]{false,true}){
            Journal journal=new Journal();journal.exitProof=proved;Port port=new Port(control(),new ArrayList<>());port.endPending=pending();try(Wire wire=serviceWire()){
                RunOutcome result=run(wire,port,journal,new ArrayList<>());assertEquals(proved?"needs_confirmation":"awaiting_user",result.status);assertEquals(!proved,result.evidence.getBoolean("projectServiceDrainPending"));assertEquals(proved,result.evidence.getBoolean("projectServiceDrainConfirmedAtExit"));assertEquals("launch-1",result.evidence.getJSONObject("projectServiceDrainObservation").getJSONArray("executionIds").getString(0));assertFalse(result.evidence.toString().contains(KEY));
            }
        }
    }
    @Test public void unrelatedExtensionCleanupFailureKeepsTheExistingSeparateMeaning()throws Exception {
        Journal journal=new Journal();Port port=new Port(control(),new ArrayList<>());port.genericCloseFailure=true;try(Wire wire=new Wire(n->answer())){
            RunOutcome result=run(wire,port,journal,new ArrayList<>());assertEquals("awaiting_user",result.status);assertTrue(result.evidence.getBoolean("extensionCleanupPending"));assertFalse(result.evidence.has("projectServiceDrainPending"));assertTrue(result.summary.contains("扩展隔离实例"));
        }
    }
    @Test public void unknownReadonlyObservationStillStopsBeforeAnotherModelRequest()throws Exception {
        Journal journal=new Journal();Port port=new Port(control(),new ArrayList<>());port.observationUnknown=true;try(Wire wire=serviceWire()){
            RunOutcome result=run(wire,port,journal,new ArrayList<>());assertEquals("awaiting_user",result.status);assertEquals(2,wire.requests.size());assertEquals(1,journal.proofs);assertEquals(1,port.reads);assertTrue(result.evidence.getBoolean("requiresReconciliation"));
        }
    }
    @Test public void normalCloseCannotMakeMissingOrForeignExitProofTrueOrRelabelFailedAsBusinessSuccess()throws Exception {
        JSONObject missingGroup=exits();missingGroup.getJSONArray("executions").getJSONObject(0).put("processGroupAbsent",false);
        JSONObject foreignOperation=exits();foreignOperation.getJSONArray("executions").getJSONObject(0).put("operationId","other");
        JSONObject realFailed=exits();realFailed.getJSONArray("executions").getJSONObject(0).put("status","failed").put("exitCode",23);
        for(JSONObject proof:java.util.Arrays.asList(null,exits().put("runId","foreign-run"),missingGroup,foreignOperation,realFailed)){
            Journal journal=new Journal();journal.exitProof=proof!=null;journal.exitOverride=proof;Port port=new Port(control(),new ArrayList<>());
            try(Wire wire=new Wire(n->n==1?call("launch-call","project_execute",new JSONObject()):response(new JSONObject().put("role","assistant").put("content","服务仍在运行，可以继续访问。"),"stop"))){
                RunOutcome result=run(wire,port,journal,new ArrayList<>());boolean proved=proof==realFailed;assertEquals(proved?"needs_confirmation":"awaiting_user",result.status);assertEquals(proved,result.evidence.getBoolean("projectServiceDrainConfirmedAtExit"));assertTrue(result.summary.contains("收尾前观察"));
                if(proved){assertTrue(result.summary.contains("原状态 failed"));assertTrue(result.summary.contains("退出码 23"));assertTrue(result.summary.contains("不代表业务验证成功"));}
                else{assertTrue(result.summary.contains("当前状态待核"));assertFalse(result.summary.contains("已取得真实退出"));}
            }
        }
        Journal journal=new Journal();Port port=new Port(control(),new ArrayList<>());
        port.endPending=new ProjectServiceDrainPending(new JSONObject().put("taskId",TASK).put("runId",RUN).put("coordinatorEpoch",1));
        try(Wire wire=serviceWire()){
            RunOutcome result=run(wire,port,journal,new ArrayList<>());assertEquals("awaiting_user",result.status);assertFalse(result.evidence.getBoolean("projectServiceDrainConfirmedAtExit"));assertEquals("missing pending ids must not prove only a known subset",0,journal.exitProofs);
        }

    }

}
