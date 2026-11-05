package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.ProviderUsage;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual TeamsModule/coordinator -> native original TaskStore -> HTTP, with no invented SQL runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamInferenceJournalTest {
    private interface Reply {JSONObject answer(JSONObject request)throws Exception;}
    private static final class Rig implements AutoCloseable {
        final java.util.concurrent.atomic.AtomicBoolean hostReady=new java.util.concurrent.atomic.AtomicBoolean(true);
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication(),hostReady::get,(stage,schema)->{});
        final SettingsStore settings=new SettingsStore(RuntimeEnvironment.getApplication());
        final HttpServer http;final TeamsModule module;final AtomicInteger requests=new AtomicInteger();
        final java.util.List<String> wireModels=new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<JSONObject> wireRequests=new java.util.concurrent.CopyOnWriteArrayList<>();
        final String operation=UUID.randomUUID().toString(),definition;
        volatile CountDownLatch arrived,release;volatile boolean invalidPeerCall;volatile Reply reply;
        TaskStoreTeamWriteHost writeHost;
        volatile Runnable duringPendingRead;
        TeamMemberEvidenceHost materials;
        Rig(boolean bind)throws Exception{
            http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/",exchange->{requests.incrementAndGet();try{
                JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                wireModels.add(input.getString("model"));
                wireRequests.add(new JSONObject(input.toString()));
                CountDownLatch wait=release;if(arrived!=null)arrived.countDown();if(wait!=null)wait.await(2,TimeUnit.SECONDS);
                Reply selected=reply;JSONObject answer=selected==null?new JSONObject().put("role","assistant").put("content","actual member output"):selected.answer(input);
                if(invalidPeerCall)answer.put("tool_calls",new JSONArray().put(new JSONObject().put("id","filtered-peer").put("type","function").put("function",new JSONObject().put("name","team_send_message").put("arguments",new JSONObject().put("targetMemberId",input.getString("model").equals("member-model-a")?"member-b":"member-a").put("text","must never be delivered").toString()))));
                byte[] body=new JSONObject().put("model",input.getString("model")).put("choices",new JSONArray().put(new JSONObject()
                        .put("finish_reason",invalidPeerCall?"content_filter":answer.has("tool_calls")?"tool_calls":"stop").put("message",answer)))
                        .put("usage",new JSONObject().put("prompt_tokens",11).put("completion_tokens",3).put("total_tokens",14)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception ignored){}finally{exchange.close();}});http.start();
            settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).put("model","base-model").put("provider","openai_chat").put("sendScreenshots",false).put("maxOutputTokens",512));
            module=new TeamsModule(new FeatureContext(RuntimeEnvironment.getApplication(),settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{
                try{JSONObject payload=event.optJSONObject("payload"),run=payload==null?null:payload.optJSONObject("run");if(run!=null){Runnable hook=duringPendingRead;if(hook!=null&&tasks.hasUnresolved(operation)){duringPendingRead=null;hook.run();}tasks.updateExternal(operation,run);}}catch(Exception failure){throw new RuntimeException(failure);}
            }));
            JSONArray members=new JSONArray().put(new JSONObject().put("id","member-a").put("name","A").put("role","first role").put("model","member-model-a"))
                    .put(new JSONObject().put("id","member-b").put("name","B").put("role","second role").put("model","member-model-b"));
            definition=module.handle("save",new JSONObject().put("name","native team").put("members",members).put("discussionFormat","classic")).getString("id");
            tasks.beginExternal("teams","start",operation,new JSONObject().put("goal","original team goal").put("teamId",definition));
            if(bind)module.setInferenceFactory(tasks::teamInferenceTarget);
        }
        void mountNativeWrites()throws Exception {
            FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{});
            writeHost=new TaskStoreTeamWriteHost(tasks,new TeamWorkspaceAccess(new WorkspaceModule(context)),changed->{});
            module.setWriteHost(writeHost);
        }
        void start()throws Exception{module.handle("start",new JSONObject().put("teamId",definition).put("goal","original team goal").put("rounds",1).put("_operationId",operation));}
        JSONObject run()throws Exception{return module.handle("get",new JSONObject().put("runId",operation));}
        JSONObject done()throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);JSONObject value;
            do{value=run();if(value.optString("status").matches("completed|failed|cancelled"))return value;Thread.sleep(10);}while(System.nanoTime()<end);
            throw new AssertionError("team did not terminate: "+value);
        }
        long nativeRuns()throws Exception{
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
            try(var rows=db.rawQuery("SELECT COUNT(*) FROM runs",null)){assertTrue(rows.moveToFirst());return rows.getLong(0);}
        }
        JSONObject trace()throws Exception{return tasks.ownerExecutionTrace(operation,operation,"",100,new RunControl());}
        JSONObject detail(String node)throws Exception{
            TaskStore.TraceDetailRead selected=tasks.prepareOwnerExecutionTraceDetail(operation,operation,node,new RunControl());
            try{
                if(materials!=null)for(TaskStore.TraceMaterialRead proof:selected.materials())materials.checkTraceMaterialMetadata(proof);
                JSONObject result=tasks.readOwnerExecutionTraceDetail(selected);
                if(materials!=null)for(TaskStore.TraceMaterialRead proof:selected.materials())materials.checkTraceMaterialMetadata(proof);
                return result;
            }catch(TaskStore.TraceSourceUnavailable unavailable){return tasks.unavailableOwnerExecutionTraceDetail(selected,"trace_material_source_unavailable");}
        }
        public void close(){if(release!=null)release.countDown();module.close();http.stop(0);
            try{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);if(db.isOpen())db.close();}catch(Exception failure){throw new RuntimeException(failure);}}
    }
    private static JSONObject answer(String text)throws Exception{return new JSONObject().put("role","assistant").put("content",text);}
    private static JSONObject toolAnswer(String id,String name,JSONObject arguments)throws Exception {
        return answer("").put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function")
                .put("function",new JSONObject().put("name",name).put("arguments",arguments.toString()))));
    }
    private static boolean isSummary(JSONObject request)throws Exception {
        String system=request.getJSONArray("messages").getJSONObject(0).getString("content");
        return system.contains("你是团队协调者")||system.contains("你是团队Leader");
    }
    private static boolean hasToolResult(JSONObject request)throws Exception {
        JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++)if("tool".equals(messages.getJSONObject(i).optString("role")))return true;return false;
    }
    private static JSONArray nodes(JSONObject page,String kind)throws Exception {
        JSONArray result=new JSONArray(),all=page.getJSONArray("nodes");for(int i=0;i<all.length();i++){JSONObject node=all.getJSONObject(i);if(kind.equals(node.optString("kind")))result.put(node);}return result;
    }
    private static JSONObject node(JSONObject page,String id)throws Exception {
        JSONArray all=page.getJSONArray("nodes");for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);throw new AssertionError("Trace node absent: "+id);
    }
    private static void noBody(JSONObject node)throws Exception {
        for(String field:new String[]{"input","output","schema","message","preview","raw"})assertFalse("compact must omit "+field,node.has(field));
    }
    @Test public void realMembersAndSynthesisUseOriginalExternalRunWithoutMintingHostRuns()throws Exception{
        try(Rig rig=new Rig(true)){
            assertEquals(0,rig.nativeRuns());rig.start();assertEquals("completed",rig.done().getString("status"));
            assertEquals(3,rig.requests.get());assertEquals(0,rig.nativeRuns());assertEquals(1,rig.tasks.list().length());
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(3,usage.getInt("attempts"));assertEquals(3,usage.getInt("completed"));
            Set<String> memberIds=new HashSet<>(),models=new HashSet<>();int summaries=0;
            JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){
                JSONObject receipt=receipts.getJSONObject(i),context=receipt.getJSONObject("context");
                assertEquals(rig.operation,context.getString("taskId"));assertEquals(rig.operation,context.getString("runId"));assertEquals(0,context.getInt("epoch"));
                assertEquals("EXTERNAL_TASK_RUN",context.getString("bindingKind"));assertEquals(1,context.getJSONObject("callSite").getInt("round"));
                if(context.getString("purpose").equals("MEMBER"))memberIds.add(context.getString("memberId"));else if(context.getString("purpose").equals("SUMMARY")){
                    summaries++;assertEquals("base-model",receipt.getJSONObject("route").getString("requestedModel"));
                }
                models.add(receipt.getJSONObject("route").getString("requestedModel"));
                assertEquals(11,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));
            }
            JSONArray logical=usage.getJSONArray("logicalRequests");assertEquals(3,logical.length());Set<String> originalMembers=new HashSet<>();for(int i=0;i<logical.length();i++){JSONObject request=logical.getJSONObject(i);assertEquals("SUCCEEDED",request.getString("state"));assertEquals(1,request.getJSONObject("control").getInt("maxAttempts"));JSONObject plan=request.getJSONObject("routeSnapshot");if("MEMBER".equals(plan.getString("purpose")))originalMembers.add(plan.getString("contextMemberId"));}assertEquals(Set.of("member-a","member-b"),originalMembers);
            assertEquals(Set.of("member-a","member-b"),memberIds);assertEquals(1,summaries);assertEquals(Set.of("member-model-a","member-model-b","base-model"),models);
            assertEquals(new HashSet<>(rig.wireModels),models);
            assertFalse(rig.run().getJSONArray("transcript").toString().contains("inference_receipt"));
        }
    }
    @Test public void standaloneCoordinatorWithNativeBudgetPoolKeepsExternalEpochZeroAndSettlesRealCalls()throws Exception {
        try(Rig rig=new Rig(true)){
            FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),rig.settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{});
            var workspace=new dev.pocketengine.features.workspace.WorkspaceModule(context);
            rig.module.setWriteHost(new TaskStoreTeamWriteHost(rig.tasks,new TeamWorkspaceAccess(workspace),changed->{}));
            assertEquals(0,rig.nativeRuns());rig.start();JSONObject result=rig.done();JSONObject pool=rig.tasks.teamBudgetSnapshot(rig.operation);
            System.out.println("STANDALONE_NATIVE_POOL "+new JSONObject().put("status",result.optString("status")).put("httpRequests",rig.requests.get()).put("hostRuns",rig.nativeRuns()).put("pool",pool).put("members",result.getJSONArray("members")));
            assertEquals("completed",result.getString("status"));assertEquals(3,rig.requests.get());assertEquals(0,rig.nativeRuns());
            JSONObject task=rig.tasks.get(rig.operation,false);assertFalse(task.has("executionScope"));assertEquals(rig.operation,task.getString("runId"));assertEquals(0,task.getInt("coordinatorEpoch"));
            assertEquals(3,pool.getInt("modelRequestsReservedOrUsed"));assertEquals(0,pool.getInt("toolOperationsReservedOrUsed"));
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(3,usage.getInt("attempts"));assertEquals(3,usage.getInt("completed"));
            JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){JSONObject original=receipts.getJSONObject(i).getJSONObject("context");assertEquals("EXTERNAL_TASK_RUN",original.getString("bindingKind"));assertEquals(rig.operation,original.getString("taskId"));assertEquals(rig.operation,original.getString("runId"));assertEquals(0,original.getInt("epoch"));}
            JSONArray reservations=task.getJSONObject("teamReservationPool").getJSONArray("reservations");assertEquals(3,reservations.length());for(int i=0;i<reservations.length();i++)assertEquals("settled",reservations.getJSONObject(i).getString("state"));
            JSONObject tokens=task.getJSONObject("tokenBudget");assertEquals(42,tokens.getLong("knownTokens"));assertEquals(0,tokens.getLong("heldTokens"));assertEquals(3,tokens.getLong("calls"));assertFalse(tokens.getBoolean("enabled"));
            for(int i=0;i<receipts.length();i++)assertEquals(rig.operation,receipts.getJSONObject(i).getJSONObject("tokenBudget").getString("taskOwner"));
        }
    }
    @Test public void completedExternalTeamTraceRetainsExactRequestsAndAnswersAcrossDatabaseReopenWithoutHostRuns()throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();
            rig.reply=request->answer(isSummary(request)?"TRACE-SUMMARY-ORIGINAL":request.getString("model").equals("member-model-a")?"TRACE-MEMBER-A-ORIGINAL":"TRACE-MEMBER-B-ORIGINAL");
            rig.start();assertEquals("completed",rig.done().getString("status"));
            int requests=rig.requests.get();assertEquals(3,requests);assertEquals(0,rig.nativeRuns());
            JSONObject page=rig.trace();assertEquals(rig.operation,page.getString("taskId"));assertEquals(rig.operation,page.getString("runId"));assertEquals(0,page.getInt("epoch"));
            assertFalse(page.getBoolean("hasMore"));assertFalse(page.getBoolean("limited"));assertEquals(3,nodes(page,"model").length());
            int inputs=0,contexts=0,assistants=0;String retainedRequest="",retainedInput="";
            JSONArray all=page.getJSONArray("nodes");for(int i=0;i<all.length();i++){
                JSONObject observed=all.getJSONObject(i);noBody(observed);assertEquals(rig.operation,observed.getString("runId"));assertEquals(0,observed.getInt("epoch"));
                String role=observed.optString("role");if(role.equals("input"))inputs++;if(role.equals("context"))contexts++;
                if(role.equals("assistant")){
                    assistants++;JSONObject request=node(page,observed.getString("requestId"));assertEquals("model",request.getString("kind"));
                    JSONObject detail=rig.detail(observed.getString("id"));assertEquals("current",detail.getString("sourceState"));assertTrue(detail.getJSONObject("node").toString().contains("TRACE-"));
                }
                if(!"model".equals(observed.getString("kind")))continue;
                assertEquals(1,observed.getInt("turnIndex"));
                JSONObject detail=rig.detail(observed.getString("id"));assertEquals("current",detail.getString("sourceState"));JSONObject original=detail.getJSONObject("node");
                assertTrue(original.get("input").toString().contains("original team goal"));
                String expected="SUMMARY".equals(observed.optString("purpose"))?"TRACE-SUMMARY-ORIGINAL":observed.optString("memberId").equals("member-a")?"TRACE-MEMBER-A-ORIGINAL":"TRACE-MEMBER-B-ORIGINAL";
                assertTrue(original.get("output").toString().contains(expected));
                if("member-a".equals(observed.optString("memberId"))){retainedRequest=observed.getString("id");retainedInput=original.get("input").toString();}
            }
            assertTrue("Actual request user inputs must have a lane",inputs>0);assertTrue("Actual system context must have a lane",contexts>0);assertEquals(3,assistants);
            assertFalse(retainedRequest.isEmpty());assertEquals(requests,rig.requests.get());assertEquals(3,rig.tasks.inferenceUsage(rig.operation).getInt("attempts"));
            assertEquals(3,rig.tasks.teamBudgetSnapshot(rig.operation).getInt("modelRequestsReservedOrUsed"));assertEquals(0,rig.tasks.teamBudgetSnapshot(rig.operation).getInt("toolOperationsReservedOrUsed"));
            rig.module.close();rig.hostReady.set(false);rig.tasks.closeForMaintenance();
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication(),()->false,(stage,schema)->{});
            try{
                TaskStore.TraceDetailRead selection=reopened.prepareOwnerExecutionTraceDetail(rig.operation,rig.operation,retainedRequest,new RunControl());
                JSONObject detail=reopened.readOwnerExecutionTraceDetail(selection);assertEquals("current",detail.getString("sourceState"));assertEquals(retainedInput,detail.getJSONObject("node").get("input").toString());
                assertEquals(0,reopened.ownerExecutionTrace(rig.operation,rig.operation,"",100,new RunControl()).getInt("epoch"));
                var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);try(var rows=((SQLiteDatabase)field.get(reopened)).rawQuery("SELECT COUNT(*) FROM runs",null)){assertTrue(rows.moveToFirst());assertEquals(0,rows.getLong(0));}
                assertEquals(requests,rig.requests.get());
            }finally{reopened.closeForMaintenance();}
        }
    }
    @Test public void externalMemberAndSummaryToolsKeepActualOfferedSchemaAndProviderCallIdentity()throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();rig.reply=request->{
                if(isSummary(request)&&!hasToolResult(request)){
                    String content=request.getJSONArray("messages").getJSONObject(1).getString("content");JSONArray outputs=new JSONArray(content.substring(content.indexOf('[')));JSONObject first=outputs.getJSONObject(0);
                    String turn=first.has("readRef")?first.getJSONObject("readRef").getString("turnId"):first.getString("turnId");
                    return toolAnswer("trace-summary-read","team_read_output",new JSONObject().put("turnId",turn).put("offset",0).put("length",80));
                }
                if(!isSummary(request)&&request.getString("model").equals("member-model-a")&&!hasToolResult(request))return toolAnswer("trace-peer-message","team_send_message",new JSONObject().put("targetMemberId","member-b").put("text","TRACE-ORIGINAL-PEER-CONTENT"));
                return answer(isSummary(request)?"TRACE-SUMMARY-AFTER-ORIGINAL-READ":"TRACE-MEMBER-ORIGINAL-ANSWER");
            };
            rig.start();assertEquals("completed",rig.done().getString("status"));assertEquals(5,rig.requests.get());
            JSONObject page=rig.trace();JSONArray tools=nodes(page,"tool");assertEquals(2,tools.length());Set<String> names=new HashSet<>();
            int requests=rig.requests.get();JSONObject budget=rig.tasks.teamBudgetSnapshot(rig.operation);
            for(int i=0;i<tools.length();i++){
                JSONObject tool=tools.getJSONObject(i);String name=tool.getString("name");names.add(name);assertEquals(0,tool.getInt("epoch"));assertEquals(rig.operation,tool.getString("runId"));
                JSONObject request=node(page,tool.getString("parentId"));assertEquals("model",request.getString("kind"));assertEquals(request.getString("callId"),tool.getString("parentCallId"));
                String provider=name.equals("team_send_message")?"trace-peer-message":"trace-summary-read";assertEquals(provider,tool.getString("providerToolCallId"));
                assertEquals(name.equals("team_send_message")?"MEMBER":"SUMMARY",request.getString("purpose"));
                JSONObject detail=rig.detail(tool.getString("id"));assertEquals("current",detail.getString("sourceState"));JSONObject original=detail.getJSONObject("node");
                assertFalse(original.getString("operationId").isEmpty());assertTrue(original.getBoolean("timingKnown"));assertFalse(original.isNull("elapsedNanos"));
                JSONObject offered=null;for(JSONObject wire:rig.wireRequests){JSONArray definitions=wire.optJSONArray("tools");for(int n=0;definitions!=null&&n<definitions.length();n++){JSONObject definition=definitions.getJSONObject(n);if(name.equals(definition.getJSONObject("function").getString("name")))offered=definition;}}
                assertNotNull("The actual HTTP request offered this tool",offered);JSONObject schema=original.getJSONObject("schema");assertEquals(name,schema.getJSONObject("function").getString("name"));
                assertEquals(offered.getJSONObject("function").getJSONObject("parameters").toString(),schema.getJSONObject("function").getJSONObject("parameters").toString());
                assertTrue(original.has("input"));assertTrue(original.has("output"));noBody(tool);
                if(name.equals("team_send_message"))assertTrue(original.get("input").toString().contains("TRACE-ORIGINAL-PEER-CONTENT"));
                else assertTrue(original.get("output").toString().contains("TRACE-MEMBER-ORIGINAL-ANSWER"));
            }
            assertEquals(Set.of("team_send_message","team_read_output"),names);assertEquals(5,nodes(page,"model").length());assertEquals(0,rig.nativeRuns());
            assertEquals(requests,rig.requests.get());assertEquals(budget.toString(),rig.tasks.teamBudgetSnapshot(rig.operation).toString());assertEquals(5,rig.tasks.inferenceUsage(rig.operation).getInt("attempts"));
        }
    }
    @Test public void concurrentUnrelatedPendingOperationStillBlocksCoordinatorReadContinuation()throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();rig.reply=request->{
                if(request.getString("model").equals("member-model-a")&&!hasToolResult(request))return toolAnswer("boundary-peer","team_send_message",new JSONObject().put("targetMemberId","member-b").put("text","original peer"));
                return answer("original answer");
            };
            rig.duringPendingRead=()->{try{
                var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(rig.tasks);
                android.content.ContentValues row=new android.content.ContentValues();row.put("operation_id",UUID.randomUUID().toString());row.put("task_id",rig.operation);row.put("run_id",rig.operation);row.put("tool","boundary_unrelated_effect");row.put("arguments","{}");row.put("state","unknown");row.put("receipt","{}");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);
            }catch(Exception failure){throw new RuntimeException(failure);}};
            rig.start();assertEquals("failed",rig.done().getString("status"));assertTrue(rig.tasks.hasUnresolved(rig.operation));
            assertEquals("reconciling",rig.tasks.get(rig.operation,false).getString("status"));assertTrue(rig.requests.get()<=2);assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void unknownReadSettlementCannotAuthorizeAnotherModelRequest()throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();
            var field=TeamsModule.class.getDeclaredField("coordinator");field.setAccessible(true);TeamCoordinator coordinator=(TeamCoordinator)field.get(rig.module);
            TeamCoordinator.BudgetHost nativeHost=rig.writeHost;AtomicInteger boundaryChecks=new AtomicInteger();
            coordinator.setBudgetHost(new TeamCoordinator.BudgetHost(){
                public void initialize(TeamCoordinator.InitialBudgetPlan plan)throws Exception{nativeHost.initialize(plan);}
                public JSONObject reserveInference(TeamCoordinator.InferenceCall call)throws Exception{return nativeHost.reserveInference(call);}
                public void releaseUnstarted(TeamCoordinator.InferenceCall call)throws Exception{nativeHost.releaseUnstarted(call);}
                public JSONObject snapshot(String run)throws Exception{return nativeHost.snapshot(run);}
                public JSONObject beginRead(TeamCoordinator.InferenceCall call,String tool,JSONObject args)throws Exception{return nativeHost.beginRead(call,tool,args);}
                public void finishRead(JSONObject original,JSONObject receipt,boolean unknown)throws Exception{
                    nativeHost.finishRead(original,new JSONObject().put("status","unknown"),true);
                    // Before any later coordinator save/event, the unchanged running projection must not admit a request.
                    assertTrue(rig.tasks.hasUnresolved(rig.operation));boundaryChecks.incrementAndGet();
                    assertThrows(SecurityException.class,()->rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0),new RunControl(),()->{}));
                    throw new java.io.IOException("synthetic original read settlement uncertainty");
                }
            });
            rig.reply=request->{if(request.getString("model").equals("member-model-a")&&!hasToolResult(request))return toolAnswer("unknown-peer","team_send_message",new JSONObject().put("targetMemberId","member-b").put("text","original peer"));return answer("original answer");};
            rig.start();assertEquals("failed",rig.done().getString("status"));assertTrue(boundaryChecks.get()>0);assertTrue(rig.tasks.hasUnresolved(rig.operation));assertTrue(rig.requests.get()<=2);assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void cancellationDuringNativePeerReadCannotReopenOriginalTask()throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();rig.reply=request->{
                if(request.getString("model").equals("member-model-a")&&!hasToolResult(request))return toolAnswer("cancel-peer","team_send_message",new JSONObject().put("targetMemberId","member-b").put("text","original peer"));
                return answer("original answer");
            };
            rig.duringPendingRead=()->{try{rig.module.handle("cancel",new JSONObject().put("runId",rig.operation));}catch(Exception failure){throw new RuntimeException(failure);}};
            rig.start();assertEquals("cancelled",rig.done().getString("status"));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(rig.tasks.inferenceUsage(rig.operation).optInt("completed")+rig.tasks.inferenceUsage(rig.operation).optInt("cancelled")<2&&System.nanoTime()<until)Thread.sleep(10);
            assertFalse("running".equals(rig.tasks.get(rig.operation,false).getString("status")));assertTrue(rig.requests.get()<=2);assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void originalCancellationDoesNotWaitForDefinitionMonitor()throws Exception {cancellationDoesNotWaitForDefinitionMonitor(false);}
    @Test public void reviewedCancellationDoesNotWaitForDefinitionMonitor()throws Exception {cancellationDoesNotWaitForDefinitionMonitor(true);}
    private void cancellationDoesNotWaitForDefinitionMonitor(boolean reviewed)throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();rig.arrived=new CountDownLatch(2);rig.release=new CountDownLatch(1);
            rig.reply=request->{if(!rig.release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Controlled cancellation fixture was not released");return answer("bounded original reply");};
            rig.start();assertTrue(rig.arrived.await(2,TimeUnit.SECONDS));
            JSONObject observed=rig.run();
            CountDownLatch held=new CountDownLatch(1),releaseDefinitions=new CountDownLatch(1);
            var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
            try{
                var definitions=workers.submit(()->{synchronized(rig.module){held.countDown();try{releaseDefinitions.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}});
                assertTrue(held.await(2,TimeUnit.SECONDS));
                JSONObject request=new JSONObject().put("runId",rig.operation);
                if(reviewed){
                    JSONObject stale=new JSONObject(request.toString()).put("expectedRevision",observed.getLong("revision")-1).put("expectedGeneration",observed.getInt("generation"));
                    var rejected=workers.submit(()->{assertThrows(IllegalStateException.class,()->rig.module.handle("cancel",stale));return true;});
                    assertTrue(rejected.get(2,TimeUnit.SECONDS));
                    request.put("expectedRevision",observed.getLong("revision")).put("expectedGeneration",observed.getInt("generation"));
                }
                var cancellation=workers.submit(()->rig.module.handle("cancel",request));
                JSONObject cancelled=cancellation.get(2,TimeUnit.SECONDS);
                assertEquals(rig.operation,cancelled.getString("id"));assertEquals("cancelled",cancelled.getString("status"));
                assertEquals(observed.getInt("generation")+1,cancelled.getInt("generation"));
                assertEquals(1L,releaseDefinitions.getCount());
                releaseDefinitions.countDown();definitions.get(2,TimeUnit.SECONDS);
                assertEquals("cancelled",rig.done().getString("status"));assertEquals(0,rig.nativeRuns());
            }finally{releaseDefinitions.countDown();rig.release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }

    @Test public void externalTraceDetailsWithdrawAfterOriginalMaterialForgetAndScopeRevocation()throws Exception {
        try(Rig rig=new Rig(true)){
            rig.mountNativeWrites();FeatureHost features=new FeatureHost(event->{},rig.tasks,()->false);
            CountDownLatch factoryEntered=new CountDownLatch(2),permitFactory=new CountDownLatch(1);TeamMemberEvidenceHost.Page page=null;
            try{
                FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),rig.settings,new SecretVault(RuntimeEnvironment.getApplication()),features::recordEvent);
                MemoryModule memory=new MemoryModule(context);features.register(memory);features.register(new WorkspaceModule(context));
                JSONObject owner=TeamMemberEvidenceHost.ownerScope();String originalBody="TRACE_SHARED_ORIGINAL_FACT";
                JSONObject shared=memory.handle("save",new JSONObject().put("scope",owner).put("record",new JSONObject().put("text","trace private "+originalBody).put("core",true))).getJSONObject("record");
                rig.materials=new TeamMemberEvidenceHost(features,rig.tasks,expected->{throw new AssertionError("No raw message source was selected");},expected->{throw new AssertionError("No raw source metadata was selected");});
                rig.module.setEvidenceFactory(call->{factoryEntered.countDown();if(!permitFactory.await(8,TimeUnit.SECONDS))throw new IllegalStateException("fixture material selection timed out");return rig.materials.forMember(call);});
                rig.reply=request->{
                    if(!isSummary(request)&&request.getString("model").equals("member-model-a")&&!hasToolResult(request))return toolAnswer("trace-selected-memory","memory_search",new JSONObject().put("query","trace private").put("limit",1));
                    return answer(isSummary(request)?"TRACE-SUMMARY-AFTER-SELECTED-MATERIAL":"TRACE-MEMBER-AFTER-SELECTED-MATERIAL");
                };
                rig.start();assertTrue("Both original member factories reached native selection",factoryEntered.await(5,TimeUnit.SECONDS));
                page=rig.module.newMemberEvidencePage(rig.materials,rig.operation,"member-a");page.setForeground(true);
                JSONObject picked=new JSONObject().put("allowedRecords",new JSONArray().put(new JSONObject().put("id",shared.getString("id")).put("revision",shared.getLong("revision"))))
                        .put("allowedSources",new JSONArray()).put("skills",new JSONArray()).put("resources",new JSONArray());
                TaskStore.TeamMemberMaterialReview review=page.review(picked);page.approve(review,review.view().getString("digest"));permitFactory.countDown();
                assertEquals("completed",rig.done().getString("status"));assertEquals(4,rig.requests.get());assertEquals(0,rig.nativeRuns());
                assertTrue("The provider actually received the selected record through the original tool",rig.wireRequests.stream().anyMatch(value->value.toString().contains(originalBody)));
                JSONObject traced=rig.trace();JSONArray tools=nodes(traced,"tool");assertEquals(1,tools.length());JSONObject tool=tools.getJSONObject(0);assertEquals("memory_search",tool.getString("name"));
                JSONObject loaded=rig.detail(tool.getString("id"));assertEquals("current",loaded.getString("sourceState"));assertTrue(loaded.getJSONObject("node").get("output").toString().contains(originalBody));
                Set<String> affected=new HashSet<>();String independent="";JSONArray requestsBeforeForget=nodes(traced,"model");
                for(int i=0;i<requestsBeforeForget.length();i++){
                    JSONObject request=requestsBeforeForget.getJSONObject(i);String requestId=request.getString("id");
                    if("member-a".equals(request.optString("memberId"))||"SUMMARY".equals(request.optString("purpose"))){
                        affected.add(requestId);JSONArray all=traced.getJSONArray("nodes");for(int n=0;n<all.length();n++){JSONObject candidate=all.getJSONObject(n);if("assistant".equals(candidate.optString("role"))&&requestId.equals(candidate.optString("requestId")))affected.add(candidate.getString("id"));}
                    }else if("member-b".equals(request.optString("memberId")))independent=requestId;
                }
                assertEquals("Both member requests, the summary and their accepted assistants are original dependent details",6,affected.size());assertFalse(independent.isEmpty());
                for(String dependent:affected)assertEquals("current",rig.detail(dependent).getString("sourceState"));
                TaskStore.TraceDetailRead selected=rig.tasks.prepareOwnerExecutionTraceDetail(rig.operation,rig.operation,tool.getString("id"),new RunControl());
                assertFalse("The actual selected material scope must accompany the read",selected.materials().isEmpty());
                for(TaskStore.TraceMaterialRead proof:selected.materials())rig.materials.checkTraceMaterialMetadata(proof);
                int requests=rig.requests.get();JSONObject budget=rig.tasks.teamBudgetSnapshot(rig.operation);
                memory.forgetReviewed(owner,shared.getString("id"),shared.getLong("revision"));
                JSONObject forgotten=rig.detail(tool.getString("id"));assertEquals("unavailable",forgotten.getString("sourceState"));noBody(forgotten.getJSONObject("node"));assertFalse(forgotten.toString().contains(originalBody));
                for(String dependent:affected){JSONObject withdrawn=rig.detail(dependent);assertEquals("unavailable",withdrawn.getString("sourceState"));noBody(withdrawn.getJSONObject("node"));assertFalse(withdrawn.toString().contains(originalBody));}
                assertEquals("An independent member must not inherit another member's private scope", "current",rig.detail(independent).getString("sourceState"));
                page.revoke(page.current().getLong("revision"));
                JSONObject revoked=rig.tasks.readOwnerExecutionTraceDetail(selected);assertEquals("unavailable",revoked.getString("sourceState"));noBody(revoked.getJSONObject("node"));assertFalse(revoked.toString().contains(originalBody));
                assertTrue(revoked.getJSONObject("node").getBoolean("timingKnown"));assertEquals(tool.getString("operationId"),revoked.getJSONObject("node").getString("operationId"));
                assertEquals(requests,rig.requests.get());assertEquals(budget.toString(),rig.tasks.teamBudgetSnapshot(rig.operation).toString());assertEquals(0,rig.nativeRuns());
            }finally{permitFactory.countDown();if(page!=null)page.close();features.close();}
        }
    }
    @Test public void persistentFixtureRootRequiresExplicitBaseMarkerAndNoSymlinks()throws Exception {
        String oldBase=System.getProperty("a25.syntheticFixtureBase"),oldRoot=System.getProperty("a25.budgetUpgradeRoot");
        java.nio.file.Path base=java.nio.file.Files.createTempDirectory("team-owned-base-"),owned=java.nio.file.Files.createDirectory(base.resolve("owned")),outside=java.nio.file.Files.createTempDirectory("team-outside-base-");
        try{
            System.setProperty("a25.budgetUpgradeRoot",owned.toString());System.clearProperty("a25.syntheticFixtureBase");assertThrows(IllegalStateException.class,TeamBudgetPersistentApplication::ownedRoot);
            System.setProperty("a25.syntheticFixtureBase",base.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Files.write(owned.resolve("synthetic-fixture-owned.marker"),"test owned".getBytes(StandardCharsets.UTF_8));assertEquals(owned,TeamBudgetPersistentApplication.ownedRoot());
            java.nio.file.Files.write(outside.resolve("synthetic-fixture-owned.marker"),"not inside approved base".getBytes(StandardCharsets.UTF_8));System.setProperty("a25.budgetUpgradeRoot",outside.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Files.write(base.resolve("synthetic-fixture-owned.marker"),"base is not an owned child".getBytes(StandardCharsets.UTF_8));System.setProperty("a25.budgetUpgradeRoot",base.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Path alias=base.resolve("alias");java.nio.file.Files.createSymbolicLink(alias,owned);System.setProperty("a25.budgetUpgradeRoot",alias.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Files.delete(owned.resolve("synthetic-fixture-owned.marker"));java.nio.file.Files.createSymbolicLink(owned.resolve("synthetic-fixture-owned.marker"),outside.resolve("synthetic-fixture-owned.marker"));System.setProperty("a25.budgetUpgradeRoot",owned.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
        }finally{
            if(oldBase==null)System.clearProperty("a25.syntheticFixtureBase");else System.setProperty("a25.syntheticFixtureBase",oldBase);
            if(oldRoot==null)System.clearProperty("a25.budgetUpgradeRoot");else System.setProperty("a25.budgetUpgradeRoot",oldRoot);
            // Only the two exact directories created above; walk does not follow symlinks.
            for(var root:java.util.List.of(base,outside))try(var paths=java.nio.file.Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
        }
    }
    @Test public void filteredBufferedMemberToolsNeverReachPeersOrSynthesis()throws Exception{
        try(Rig rig=new Rig(true)){
            rig.invalidPeerCall=true;rig.start();JSONObject run=rig.done();assertEquals("failed",run.getString("status"));
            assertEquals(2,rig.requests.get());assertEquals(0,run.getJSONArray("mailbox").length());assertFalse(run.getJSONArray("transcript").toString().contains("must never be delivered"));
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(2,usage.getInt("attempts"));assertEquals(2,usage.getInt("failed"));assertEquals(0,usage.getInt("completed"));
            for(int i=0;i<usage.getJSONArray("receipts").length();i++){
                JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(i);assertEquals("MEMBER",receipt.getJSONObject("context").getString("purpose"));assertEquals("content_filter",receipt.getJSONObject("usage").getString("finishReason"));
                assertEquals(11,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));assertEquals("FAILED",receipt.getString("outcome"));
            }
        }
    }
    @Test public void noFactoryIsExplicitlyUnknownAndCannotFabricateTaskUsage()throws Exception{
        try(Rig rig=new Rig(false)){
            rig.start();JSONObject refused=rig.done();assertEquals("failed",refused.getString("status"));assertEquals(0,rig.requests.get());
            JSONArray members=refused.getJSONArray("members");assertEquals(2,members.length());
            for(int i=0;i<members.length();i++){
                JSONObject phase=members.getJSONObject(i).getJSONArray("roundStates").getJSONObject(0);
                assertEquals("failed",phase.getString("status"));assertEquals("SecurityException",phase.getString("nativeErrorType"));
                assertEquals("模型组需要原TaskStore逻辑请求准入；没有调用未归因通路",phase.getString("nativeErrorMessage"));
            }
            assertFalse(rig.module.snapshot().getBoolean("usageAttributionConfigured"));assertEquals("unknown_unbound",rig.module.snapshot().getString("usageCoverage"));
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(0,usage.getInt("attempts"));
            assertEquals("no_accounting_records_not_zero_actual_usage",usage.getString("emptyMeaning"));assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void missingOriginalTaskAndForeignHostControlCannotBind()throws Exception{
        try(Rig rig=new Rig(false)){
            var site=new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0);
            assertThrows(SecurityException.class,()->rig.tasks.teamInferenceTarget(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MEMBER,"member-a",site,new RunControl(),()->{}));
            RunControl host=new RunControl();host.bindHostRun("foreign-task","foreign-run",1);
            assertThrows(SecurityException.class,()->rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",site,host,()->{}));
            assertEquals(0,rig.tasks.inferenceUsage(rig.operation).getInt("attempts"));assertEquals(0,rig.requests.get());
        }
    }
    @Test public void lateExternalRunUsageOnlyUpdatesItsOriginalStartedReceipt()throws Exception{
        try(Rig rig=new Rig(false)){
            RunControl child=new RunControl();var site=new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0);
            var target=rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",site,child,()->{});
            assertFalse(child.hasHostRun());assertTrue(child.matchesHostRun(rig.operation,"",0));
            var capture=new InferenceCapture(new ModelConfig("https://fixture.example","","model",false,4,10),child,target);capture.dispatch();
            rig.tasks.requestCancel(rig.operation,rig.operation);child.cancel();String before=rig.tasks.get(rig.operation,false).getString("status");
            capture.usage.observe(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",19)),ProviderUsage.Stage.FINAL);capture.finish(InferenceReceipt.Outcome.CANCELLED);
            assertEquals(before,rig.tasks.get(rig.operation,false).getString("status"));assertEquals(1,rig.tasks.inferenceUsage(rig.operation).getInt("cancelled"));assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void nativeCoordinatorAdmissionIsRecheckedAfterRegistration()throws Exception{
        try(Rig rig=new Rig(false)){
            AtomicInteger checks=new AtomicInteger();RunControl child=new RunControl();
            var target=rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0),child,()->{
                checks.incrementAndGet();if(rig.tasks.inferenceUsage(rig.operation).getInt("attempts")>0)throw new InterruptedException("native generation changed after original STARTED receipt");
            });
            try{ModelGateway.complete(rig.settings.get(),new JSONArray().put(new JSONObject().put("role","user").put("content","native invocation")),null,child,target);fail();}catch(InterruptedException stopped){}
            assertEquals(0,rig.requests.get());assertEquals(1,rig.tasks.inferenceUsage(rig.operation).getInt("cancelled"));assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void ownerCancellationStopsBothRealMemberRequestsAndKeepsTheirUnknownAccounting()throws Exception{
        try(Rig rig=new Rig(true)){
            rig.arrived=new CountDownLatch(2);rig.release=new CountDownLatch(1);rig.start();assertTrue(rig.arrived.await(3,TimeUnit.SECONDS));
            rig.module.handle("cancel",new JSONObject().put("runId",rig.operation));rig.release.countDown();assertEquals("cancelled",rig.done().getString("status"));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject usage;
            do{usage=rig.tasks.inferenceUsage(rig.operation);if(usage.optInt("cancelled")==2)break;Thread.sleep(10);}while(System.nanoTime()<end);
            assertEquals(2,rig.requests.get());assertEquals(2,usage.getInt("attempts"));assertEquals(2,usage.getInt("cancelled"));assertEquals(0,rig.nativeRuns());
            for(int i=0;i<usage.getJSONArray("receipts").length();i++){
                JSONObject counters=usage.getJSONArray("receipts").getJSONObject(i).getJSONObject("usage").getJSONObject("counters");
                assertTrue(counters.getJSONObject("INPUT").isNull("value"));assertEquals("unknown",counters.getJSONObject("INPUT").getString("source"));
            }
            assertEquals("cancelled",rig.tasks.get(rig.operation,false).getString("status"));
        }
    }
    @Test public void pauseAndExplicitResumeKeepTheOriginalTaskAndSeparateActualCallGenerations()throws Exception{
        try(Rig rig=new Rig(true)){
            rig.arrived=new CountDownLatch(2);rig.release=new CountDownLatch(1);rig.start();assertTrue(rig.arrived.await(3,TimeUnit.SECONDS));
            rig.module.handle("pause",new JSONObject().put("runId",rig.operation));rig.release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(rig.tasks.inferenceUsage(rig.operation).optInt("cancelled")<2&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals(2,rig.tasks.inferenceUsage(rig.operation).getInt("cancelled"));
            rig.release=null;rig.arrived=null;rig.module.handle("resume",new JSONObject().put("runId",rig.operation));
            assertEquals("completed",rig.done().getString("status"));assertEquals(5,rig.requests.get());
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(5,usage.getInt("attempts"));assertEquals(2,usage.getInt("cancelled"));assertEquals(3,usage.getInt("completed"));
            assertEquals(1,rig.tasks.list().length());assertEquals(0,rig.nativeRuns());
            Set<Integer> generations=new HashSet<>();JSONArray receipts=usage.getJSONArray("receipts");
            for(int i=0;i<receipts.length();i++)generations.add(receipts.getJSONObject(i).getJSONObject("context").getJSONObject("callSite").getInt("runGeneration"));
            assertTrue(generations.contains(0));assertTrue(generations.stream().anyMatch(value->value>0));
        }
    }

    @Test public void logicalCurrentnessNeverCallsCoordinatorWhileOriginalTaskStoreMonitorIsHeld()throws Exception {
        try(Rig rig=new Rig(false)){
            RunControl original=new RunControl();var target=rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0),original,()->assertFalse("coordinator currentness cannot run under TaskStore monitor",Thread.holdsLock(rig.tasks)));
            assertEquals("actual member output",ModelGateway.complete(rig.settings.get(),new JSONArray().put(new JSONObject().put("role","user").put("content","original standalone team")),null,original,target).getString("content"));assertEquals(1,rig.requests.get());assertEquals(0,rig.nativeRuns());assertEquals("SUCCEEDED",rig.tasks.inferenceUsage(rig.operation).getJSONArray("logicalRequests").getJSONObject(0).getString("state"));
        }
    }
}
