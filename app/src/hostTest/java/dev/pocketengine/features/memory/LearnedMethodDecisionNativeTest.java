package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual ordinary (no scripts) learning -> separate owner review -> Host/Runner/local HTTP ->
 * original native workspace operation -> explicit method decision -> native task-page projection.
 * The model responses are isolated fixture data, not evidence of semantic effectiveness. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class LearnedMethodDecisionNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final String GOAL="备份前后核对目录，在本机示例项目保存核对记录";
    private interface Script {JSONObject answer(int index,JSONObject request)throws Exception;}
    private static JSONObject call(String name,JSONObject args,int index)throws Exception{
        return new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","method-call-"+index).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
    }
    private static JSONObject lastReceipt(JSONObject request,String callId)throws Exception{
        JSONArray messages=request.getJSONArray("messages");for(int i=messages.length()-1;i>=0;i--){JSONObject row=messages.getJSONObject(i);if("tool".equals(row.optString("role"))&&callId.equals(row.optString("tool_call_id")))return new JSONObject(row.getString("content"));}throw new AssertionError("missing original tool receipt "+callId);
    }
    /** Preserve every native return/exception; diagnose only this isolated fixture's failing
     * boundary without logging model messages, arguments, task bodies or database contents. */
    private static <T>T traceNativeFailure(Class<T> type,T original,AtomicReference<String> failure){
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            try{return method.invoke(original,args);}
            catch(java.lang.reflect.InvocationTargetException wrapped){
                Throwable cause=wrapped.getCause();String detail=String.valueOf(cause.getMessage());
                failure.compareAndSet(null,type.getSimpleName()+"."+method.getName()+":"+cause.getClass().getSimpleName()+":"+detail.substring(0,Math.min(240,detail.length())));
                throw cause;
            }
        }));
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server;final AtomicInteger count=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();
        Server(Script script)throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
                try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONObject answer=script.answer(count.getAndIncrement(),request);
                    byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable problem){failure.compareAndSet(null,problem);byte[] bytes="fixture assertion failed".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
            });server.start();
        }
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"","fixture-model",false,10,10,"openai_chat",2000);}
        public void close(){server.stop(0);}
    }
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final FeatureHost host;
        final FeatureContext context;
        final MemorySourceBridge bridge;final MemoryModule memory;final AssistantModule assistant;final SkillsModule skills;final WorkspaceModule workspace;
        final SourceRef source=new SourceRef("message",UUID.randomUUID().toString(),1,"owner","private");
        final JSONObject record,skill;final String task,run,project;final int epoch;final RunControl control=new RunControl();final HostToolSupervisor port;
        Fixture()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            // The real model-send lifecycle checks parent inputs through this same original ledger.
            host=new FeatureHost(event->{},tasks,()->false);
            java.io.File files=new java.io.File(android.getFilesDir(),"ordinary-method-"+UUID.randomUUID());assertTrue(files.mkdirs());
            android.content.Context isolated=new android.content.ContextWrapper(android){@Override public java.io.File getFilesDir(){return files;}@Override public android.content.Context getApplicationContext(){return this;}};
            context=new FeatureContext(isolated,new SettingsStore(android),new SecretVault(android),event->{});
            bridge=new MemorySourceBridge(host,tasks);memory=new MemoryModule(context,bridge::resolve);assistant=new AssistantModule(context,memory,null,tasks);skills=new SkillsModule(context,memory::resolveExperience);workspace=new WorkspaceModule(context);
            host.register(memory);host.register(assistant);host.register(skills);host.register(workspace);bridge.bindAssistant(assistant);bridge.bindWorkspace(workspace);host.setMemorySynchronizer(bridge::synchronize);
            var repo=tasks.conversationRepository();repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId","method-source-private").put("title","主人选择的原方法").put("messages",new JSONArray()));repo.saveSource(source.toJson().put("conversationId","method-source-private").put("role","user").put("deleted",false));repo.saveMessage("method-source-private",new JSONObject().put("id",source.sourceId).put("revision",1).put("role","user").put("content","备份前核对目录，保存后再读文件；不符合当前目录就停止核对，不能推广为已验证方法。"));return null;});
            KnowledgeLearningWorkflow.ModelClient draft=(messages,current)->new JSONObject().put("content",new JSONObject().put("layer","procedural").put("text","备份前核对目录，保存后再读文件").put("reason","主人选中原文明确描述此步骤").put("applicability","仅同结构目录；未经独立验证，出现差异时停止核对").put("confidence",.6).toString());
            MemoryHostFence ownerReview=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){return action.run();}}};
            var learning=new KnowledgeLearningWorkflow(memory,skills,bridge::read,draft,ownerReview);JSONObject candidate=learning.proposeLearning(scope(),source,new RunControl()).getJSONObject("candidate");record=memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");
            var instructionOnly=new KnowledgeLearningWorkflow(memory,skills,bridge::read,(messages,current)->new JSONObject().put("content",new JSONObject().put("manifest","---\nname: backup-directory-check\ndescription: 备份前后核对目录和保存文件\n---\n仅用于相同目录结构，未经独立验证。先核对目录，保存后读取核对内容，不匹配就停止并请主人检查。\n").toString()),ownerReview);
            JSONObject preview=instructionOnly.draftExperience(scope(),record.getString("id"),record.getLong("revision"),new RunControl());assertFalse(preview.getJSONObject("draft").has("resources"));
            skill=instructionOnly.importExperience(scope(),record.getString("id"),record.getLong("revision"),preview.getJSONObject("draft"),new JSONObject().put("inspectedDigest",preview.getJSONObject("inspection").getString("inspectedDigest"))).getJSONObject("skill");instructionOnly.approveExperience(scope(),skill.getString("id"),skill.getInt("revision"),skill.getString("digest"));
            JSONObject attempt=tasks.startAttempt(tasks.createCapabilityTask(GOAL,"").getString("id"),"capabilities",10);task=attempt.getString("id");run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
            project=workspace.handle("create",new JSONObject().put("name","隔离示例方法核对")).getString("id");port=new HostToolSupervisor(host,tasks,task,run,epoch,true);
            tasks.authorizeTools(task,run,new JSONArray().put("workspace_write").put("memory_method_decision").put("skills_read").put(TaskStoreMemoryReader.RAW_QUERY).put(TaskStoreMemoryReader.RAW_READ));
        }
        JSONObject decision(String choice,JSONArray steps)throws Exception{return new JSONObject().put("recordId",record.getString("id")).put("revision",record.getLong("revision")).put("decision",choice).put("reason","依据当前原方法条件和实际步骤作出的明确决定，不是效果证明").put("stepOperationIds",steps);}
        JSONObject writeArgs()throws Exception{return new JSONObject().put("projectId",project).put("path","check.txt").put("content","核对记录：原方法条件在本示例满足；仍未证明方法业务有效。\n").put("expectedVersion",0);}
        RunJournal journal(){return tasks.journal(task,run,epoch,control);}
        void approve(JSONObject args)throws Exception{JSONObject approval=tasks.requireCapabilityApproval(task,run,"workspace_write",args,new JSONObject().put("feature","workspace").put("command","write"));tasks.decideCapabilityApproval(task,approval.getString("approvalId"),true);}
        JSONObject page()throws Exception{return new TaskMemoryNativeHost(host,tasks,bridge).handle("readLearnedMethodApplications",new JSONObject().put("recordId",record.getString("id")).put("revision",record.getLong("revision")).put("cursor","").put("limit",20));}
        int decisionRows()throws Exception{try(var rows=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND tool='memory_method_decision'",new String[]{task})){int count=0;while(rows.moveToNext()){assertFalse("a rejected operation cannot acquire a native method fact",new JSONObject(rows.getString(0)).has("nativeMethodDecision"));count++;}return count;}}
        JSONObject execute(String choice,JSONArray steps)throws Exception{return execute(decision(choice,steps));}
        JSONObject execute(JSONObject args)throws Exception{String op=journal().beginOperation("memory_method_decision",args);JSONObject result=port.execute("memory_method_decision",new JSONObject(args.toString()).put("_hostOperationId",op),control);journal().finishOperation(op,result);return result;}
        CapabilityPort raw()throws Exception{
            MemoryHostFence fence=new MemoryHostFence(){public void check()throws Exception{tasks.checkCapabilityCurrent(task,run,epoch,TaskStoreMemoryReader.RAW_QUERY);control.check();}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}};
            var binding=new TaskStoreMemoryReader.RawBinding(task,run,epoch,control,fence,host::memoryContextRevision);binding.bindOutputSources(tasks.toolOutputSources());tasks.bindRawModelExposure(binding);
            return new TaskStoreMemoryReader.RawCapability(port,binding,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject options)throws Exception{return tasks.queryMemoryRaw(b,options);}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject citation)throws Exception{return tasks.readMemoryRaw(b,citation);}});
        }
        public void close(){port.close();host.close();db.close();}
    }
    @Test public void ordinaryReviewedInstructionOnlySkillHasARealRunnerDecisionAndOriginalStepReceiptNotMerelyALoad()throws Exception{
        try(Fixture f=new Fixture()){
            assertTrue("new production audit tool must be actually registered",f.port.supports("memory_method_decision"));assertFalse(f.port.tools().toString().contains("scripts/check.py"));
            f.skills.readForHost(scope(),f.skill.getString("id"),"SKILL.md",0,8000,f.skill.getString("digest"),Set.of());assertEquals(0,f.page().getJSONArray("applications").length());
            JSONObject args=f.writeArgs();f.approve(args);AtomicReference<String> step=new AtomicReference<>(),nativeFailure=new AtomicReference<>();
            List<String> eventStates=new java.util.concurrent.CopyOnWriteArrayList<>();
            try(Server server=new Server((index,request)->{
                if(index==0){assertTrue(request.toString().contains("备份前核对目录"));return call("workspace_write",args,index);}
                if(index==1){assertEquals(1,lastReceipt(request,"method-call-0").getInt("version"));return call(TaskStoreMemoryReader.RAW_QUERY,new JSONObject().put("section","operations").put("query","workspace_write").put("limit",10).put("maxChars",8000),index);}
                if(index==2){JSONArray entries=lastReceipt(request,"method-call-1").getJSONArray("entries");assertTrue(entries.length()>0);step.set(entries.getJSONObject(0).getString("operationId"));return call("memory_method_decision",f.decision("apply",new JSONArray().put(step.get())),index);}
                if(index==3){JSONObject actual=lastReceipt(request,"method-call-2").getJSONObject("methodDecision");assertEquals("model_apply_with_native_steps",actual.getString("applicationState"));assertFalse(actual.getBoolean("methodSemanticUseIndependentlyVerified"));return new JSONObject().put("role","assistant").put("content","已保存原方法决定和原步骤关联；这不是方法有效性或业务成功证明。");}
                throw new AssertionError("unexpected replay/model call "+index);
            })){RunOutcome result=new AgentRunner(null,traceNativeFailure(CapabilityPort.class,f.raw(),nativeFailure)).run(f.task,GOAL,server.config(),f.control,event->{
                if(eventStates.size()<24)eventStates.add(event.optString("type")+":"+event.optString("status")+":"+event.optString("tool"));
                try{f.tasks.event(f.task,f.run,f.epoch,event);}catch(Exception failed){nativeFailure.compareAndSet(null,"TaskStore.event:"+failed.getClass().getSimpleName());throw new IllegalStateException(failed);}
            },traceNativeFailure(RunJournal.class,f.journal(),nativeFailure));
                if(server.failure.get()!=null)throw new AssertionError("actual loopback script failed",server.failure.get());
                assertEquals("original native write/decision receipts do not prove business completion; modelRequests="+server.count.get()+"; summary="+result.summary+"; steps="+result.evidence.optInt("steps",-1)+"; nativeFailure="+nativeFailure.get()+"; eventStates="+eventStates,"needs_confirmation",result.status);assertEquals(4,server.count.get());}
            assertEquals(args.getString("content"),f.workspace.handle("read",new JSONObject().put("projectId",f.project).put("path","check.txt")).getString("content"));
            JSONObject receipt=f.page().getJSONArray("applications").getJSONObject(0);assertEquals("model_apply_with_native_steps",receipt.getString("applicationState"));assertEquals(step.get(),receipt.getJSONArray("stepRefs").getJSONObject(0).getString("operationId"));assertFalse(receipt.getBoolean("methodBusinessSuccessVerified"));assertFalse(receipt.getBoolean("executionAuthorized"));
            JSONObject effect=receipt.getJSONObject("effectEvidence");assertTrue(effect.getBoolean("inputComparable"));assertTrue(effect.getBoolean("environmentComparable"));assertFalse(effect.getBoolean("businessSuccessVerified"));
            assertEquals(1,f.page().getJSONObject("effectComparison").getInt("eligibleApplications"));
            f.control.cancel();TaskStore cold=new TaskStore(f.android);try{JSONObject restored=cold.learnedMethodApplications(f.record.getString("id"),f.record.getLong("revision"),"",20).getJSONArray("applications").getJSONObject(0);assertEquals(receipt.getString("operationId"),restored.getString("operationId"));assertEquals(effect.getString("evidenceFingerprint"),restored.getJSONObject("effectEvidence").getString("evidenceFingerprint"));}finally{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(cold)).close();}
        }
    }
    @Test public void explicitNotApplicableAndNotSelectedKeepReasonsWithoutCreatingExecutionOrCallingThemApplied()throws Exception{
        try(Fixture f=new Fixture()){f.port.beginRun(GOAL,f.control);for(String choice:new String[]{"not_applicable","not_selected"}){JSONObject receipt=f.execute(choice,new JSONArray()).getJSONObject("methodDecision");assertEquals(choice,receipt.getString("applicationState"));assertEquals(0,receipt.getJSONArray("stepRefs").length());assertFalse(receipt.getBoolean("executionAuthorized"));}assertEquals(2,f.page().getJSONArray("applications").length());assertEquals(0,f.workspace.handle("list",new JSONObject().put("projectId",f.project)).getJSONArray("files").length());}
    }
    @Test public void unknownNotDispatchedForeignRunAndForgottenSourcesNeverCreateAnApplicationFact()throws Exception{
        for(JSONObject receipt:new JSONObject[]{new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"),new JSONObject().put("accepted",false).put("code","not_dispatched")})try(Fixture f=new Fixture()){
            f.port.beginRun(GOAL,f.control);String step=f.journal().beginOperation("workspace_write",f.writeArgs());f.journal().finishOperation(step,receipt);Exception refused=assertThrows(Exception.class,()->f.execute("apply",new JSONArray().put(step)));
            if("unknown".equals(receipt.optString("status"))){assertTrue(String.valueOf(refused.getMessage()).contains("有动作结果待核对"));assertEquals("unknown blocks original new-intent admission",0,f.decisionRows());}
            else{assertTrue("not-dispatched must reach the real native SQL step check",String.valueOf(refused.getMessage()).contains("未知、未派发或缺少确定回执"));assertEquals(1,f.decisionRows());}
            assertEquals(0,f.page().getJSONArray("applications").length());
        }
        try(Fixture f=new Fixture()){f.port.beginRun(GOAL,f.control);
            JSONObject another=f.tasks.startAttempt(f.tasks.createCapabilityTask("另一原任务","").getString("id"),"capabilities",2);RunControl other=new RunControl();other.bindHostRun(another.getString("id"),another.getString("runId"),another.getInt("coordinatorEpoch"));f.tasks.authorizeTools(another.getString("id"),another.getString("runId"),new JSONArray().put("workspace_write"));RunJournal foreign=f.tasks.journal(another.getString("id"),another.getString("runId"),another.getInt("coordinatorEpoch"),other);String foreignStep=foreign.beginOperation("workspace_write",f.writeArgs());foreign.finishOperation(foreignStep,new JSONObject().put("ok",true));Exception refused=assertThrows(Exception.class,()->f.execute("apply",new JSONArray().put(foreignStep)));assertTrue(String.valueOf(refused.getMessage()).contains("关联步骤不是同轮"));assertEquals(1,f.decisionRows());
            assertEquals(0,f.page().getJSONArray("applications").length());
        }
        try(Fixture f=new Fixture()){f.port.beginRun(GOAL,f.control);
            f.memory.handle("forget",new JSONObject().put("scope",scope()).put("id",f.record.getString("id")));assertThrows(Exception.class,()->f.execute("not_selected",new JSONArray()));JSONObject page=f.page();assertEquals(0,page.getJSONArray("applications").length());assertTrue(page.getInt("unavailableNativeProofs")>0);assertFalse(page.getBoolean("completeHistory"));
        }
    }
    @Test public void originalOperationTransactionRollbackHasNoApplicationOrReusableDecisionProof()throws Exception{
        try(Fixture f=new Fixture()){f.port.beginRun(GOAL,f.control);f.db.execSQL("CREATE TRIGGER reject_method_decision BEFORE UPDATE OF arguments ON operations WHEN NEW.tool='memory_method_decision' BEGIN SELECT RAISE(ABORT,'fixture method decision atomic write rejected'); END");Exception failure=assertThrows(Exception.class,()->f.execute("not_selected",new JSONArray()));assertTrue("must reach the real SQL rollback, not pass through an earlier unrelated refusal",String.valueOf(failure.getMessage()).contains("fixture method decision atomic write rejected"));assertEquals(0,f.page().getJSONArray("applications").length());assertFalse(f.page().getBoolean("completeHistory"));try(var rows=f.db.rawQuery("SELECT arguments FROM operations WHERE tool='memory_method_decision'",null)){assertTrue(rows.moveToFirst());assertFalse(new JSONObject(rows.getString(0)).has("nativeMethodDecision"));}assertEquals(0,f.workspace.handle("list",new JSONObject().put("projectId",f.project)).getJSONArray("files").length());}
    }

    @Test public void nativeMethodEffectReviewPinsOriginalResultAndDeduplicatesARepeatedDecision()throws Exception{
        try(Fixture f=new Fixture()){
            f.port.beginRun(GOAL,f.control);JSONArray steps=new JSONArray(),receipts=new JSONArray();
            for(int i=0;i<8;i++){
                JSONObject args=f.writeArgs().put("path","界".repeat(235)+i+".txt");assertEquals(240,args.getString("path").length());f.approve(args);
                String originalStep=f.journal().beginOperation("workspace_write",args);
                JSONObject written=f.port.execute("workspace_write",new JSONObject(args.toString()).put("_hostOperationId",originalStep),f.control);
                f.journal().finishOperation(originalStep,written);assertEquals(1,written.getInt("version"));steps.put(originalStep);receipts.put(written);
            }
            String reason="据".repeat(1000);JSONObject first=f.execute(f.decision("apply",steps).put("reason",reason)).getJSONObject("methodDecision");
            try(var bytes=f.db.rawQuery("SELECT length(CAST(arguments AS BLOB)) FROM operations WHERE task_id=? AND operation_id=?",new String[]{f.task,first.getString("operationId")})){
                assertTrue(bytes.moveToFirst());assertTrue("valid eight-step UTF-8 evidence must exercise the former 16KiB read limit",bytes.getLong(0)>16384);assertTrue(bytes.getLong(0)<=32768);
            }
            JSONObject page=f.page(),projection=page.getJSONArray("applications").getJSONObject(0),evidence=projection.getJSONObject("effectEvidence");
            assertEquals(reason,projection.getString("reason"));assertEquals(8,projection.getJSONArray("stepRefs").length());
            assertTrue(evidence.getBoolean("inputComparable"));assertTrue(evidence.getBoolean("environmentComparable"));
            assertFalse(evidence.getBoolean("causalEffectVerified"));assertFalse(evidence.getBoolean("executionAuthorized"));
            assertFalse(evidence.toString().contains(f.writeArgs().getString("content")));assertFalse(evidence.toString().contains(GOAL));
            assertEquals(1,page.getJSONObject("effectComparison").getInt("eligibleApplications"));
            String operation=first.getString("operationId"),note="Owner inspected the exact saved workspace version and content";
            JSONObject quality=f.tasks.reviewAppQuality(f.task,operation,f.record.getString("id"),f.record.getLong("revision"),"verified",note).snapshot();
            assertEquals(evidence.getString("evidenceFingerprint"),quality.getJSONObject("methodEffectEvidence").getString("evidenceFingerprint"));
            assertEquals(quality.toString(),f.tasks.reviewAppQuality(f.task,operation,f.record.getString("id"),f.record.getLong("revision"),"verified",note).snapshot().toString());
            assertThrows(SecurityException.class,()->f.tasks.reviewAppQuality(f.task,operation,f.record.getString("id"),f.record.getLong("revision"),"skill_failed","different judgement"));
            f.execute("apply",steps);JSONObject comparison=f.page().getJSONObject("effectComparison");
            assertEquals(1,comparison.getInt("eligibleApplications"));assertEquals(1,comparison.getInt("duplicateRuns"));
            assertEquals(1,comparison.getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0).getInt("ownerVerifiedResults"));
            assertFalse(comparison.getBoolean("automaticPromotionAllowed"));assertEquals(0,comparison.getInt("confidenceDelta"));
            // The old evidence cannot survive an actual change to the original operation result.
            String step=steps.getString(0);android.content.ContentValues changed=new android.content.ContentValues();changed.put("receipt",new JSONObject(receipts.getJSONObject(0).toString()).put("version",2).toString());
            assertEquals(1,f.db.update("operations",changed,"task_id=? AND operation_id=?",new String[]{f.task,step}));
            JSONObject stale=f.page();assertEquals(0,stale.getJSONArray("applications").length());assertTrue(stale.getInt("unavailableNativeProofs")>=2);
            assertThrows(SecurityException.class,()->f.tasks.reviewAppQuality(f.task,operation,f.record.getString("id"),f.record.getLong("revision"),"verified",note));
        }
    }

    @Test public void historicalFiveFieldStepsAndOwnerFeedbackRemainReadableButNotComparable()throws Exception{
        try(Fixture f=new Fixture()){
            f.port.beginRun(GOAL,f.control);JSONObject args=f.writeArgs();f.approve(args);
            String step=f.journal().beginOperation("workspace_write",args);
            JSONObject written=f.port.execute("workspace_write",new JSONObject(args.toString()).put("_hostOperationId",step),f.control);
            f.journal().finishOperation(step,written);
            String operation=f.execute("apply",new JSONArray().put(step)).getJSONObject("methodDecision").getString("operationId");
            // Emulate the persisted pre-effect schema using an actual completed native operation.
            JSONObject originalArguments,originalReceipt;
            try(var rows=f.db.rawQuery("SELECT arguments,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{f.task,operation})){
                assertTrue(rows.moveToFirst());originalArguments=new JSONObject(rows.getString(0));originalReceipt=new JSONObject(rows.getString(1));
            }
            JSONObject legacy=originalArguments.getJSONObject("nativeMethodDecision");legacy.remove("effectEvidence");
            JSONObject oldStep=legacy.getJSONArray("stepRefs").getJSONObject(0);
            for(String key:List.of("argumentsFingerprint","observationId","workspaceSource","workspaceResult"))oldStep.remove(key);
            assertEquals(5,oldStep.length());originalReceipt.put("methodDecision",new JSONObject(legacy.toString()));
            android.content.ContentValues restored=new android.content.ContentValues();restored.put("arguments",originalArguments.toString());restored.put("receipt",originalReceipt.toString());
            assertEquals(1,f.db.update("operations",restored,"task_id=? AND operation_id=?",new String[]{f.task,operation}));
            JSONObject page=f.page(),projection=page.getJSONArray("applications").getJSONObject(0);
            assertFalse(projection.has("effectEvidence"));assertEquals(5,projection.getJSONArray("stepRefs").getJSONObject(0).length());
            assertEquals(0,page.getJSONObject("effectComparison").getInt("eligibleApplications"));
            assertEquals(1,page.getJSONObject("effectComparison").getInt("excludedIncompleteEvidence"));
            String note="Owner checked the originally saved legacy workspace result";
            JSONObject feedback=f.tasks.reviewAppQuality(f.task,operation,f.record.getString("id"),f.record.getLong("revision"),"verified",note).snapshot();
            assertEquals("legacy_original_steps_only",feedback.getJSONObject("methodEffectEvidence").getString("evaluationCoverage"));
            // Old feedback did not carry a format or input/environment link. A repeat owner click
            // must still use its original timestamp, without rewriting or rejecting that feedback.
            for(String key:List.of("format","methodEffectEvidence","resultVerifiedByOwner","attributionSource","causalEffectVerified","confidenceDelta","replayAllowed"))feedback.remove(key);
            originalArguments.put("appQualityFeedback",feedback);restored.clear();restored.put("arguments",originalArguments.toString());
            assertEquals(1,f.db.update("operations",restored,"task_id=? AND operation_id=?",new String[]{f.task,operation}));
            JSONObject repeat=f.tasks.reviewAppQuality(f.task,operation,f.record.getString("id"),f.record.getLong("revision"),"verified",note).snapshot();
            assertEquals(feedback.getLong("at"),repeat.getLong("at"));assertFalse(repeat.getBoolean("automaticallyVerified"));
            assertFalse(f.page().getJSONArray("applications").getJSONObject(0).getJSONObject("appQualityFeedback").has("methodEffectEvidence"));
        }
    }

    @Test public void aFinalCursorPageDoesNotClaimCompleteHistoryOrAddNotSelectedDecisionsToEffects()throws Exception{
        try(Fixture f=new Fixture()){
            f.port.beginRun(GOAL,f.control);f.execute("not_selected",new JSONArray());f.execute("not_applicable",new JSONArray());
            TaskMemoryNativeHost nativeHost=new TaskMemoryNativeHost(f.host,f.tasks,f.bridge);
            JSONObject options=new JSONObject().put("recordId",f.record.getString("id")).put("revision",f.record.getLong("revision")).put("cursor","").put("limit",1);
            JSONObject first=nativeHost.handle("readLearnedMethodApplications",options);
            assertEquals(1,first.getJSONArray("applications").length());assertFalse(first.getString("nextCursor").isEmpty());assertFalse(first.getBoolean("completeHistory"));
            JSONObject last=nativeHost.handle("readLearnedMethodApplications",new JSONObject(options.toString()).put("cursor",first.getString("nextCursor")));
            assertEquals(1,last.getJSONArray("applications").length());assertEquals("",last.getString("nextCursor"));assertFalse(last.getBoolean("completeHistory"));
            assertNotEquals(first.getJSONArray("applications").getJSONObject(0).getString("operationId"),last.getJSONArray("applications").getJSONObject(0).getString("operationId"));
            JSONObject comparison=last.getJSONObject("effectComparison");assertEquals("bounded_page_only",comparison.getString("coverage"));
            assertEquals(0,comparison.getInt("eligibleApplications"));assertEquals(1,comparison.getInt("excludedUnapplied"));
            assertTrue(f.page().getBoolean("completeHistory"));
        }
    }
}
