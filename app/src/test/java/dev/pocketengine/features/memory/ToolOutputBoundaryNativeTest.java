package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

/** Real original Adapter/TaskStore/Book/Runner/Gateway boundaries with controlled native/HTTP
 * fixtures. This is not live SSH, a live model/account, App rendering or a device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ToolOutputBoundaryNativeTest {
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final RunControl control=new RunControl();final String id,run;final int epoch;
        Fixture()throws Exception{context.deleteDatabase("pocket-world.db");tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("有界原输出闭环","").getString("id"),"capabilities",8);id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("ssh_read_file").put("ssh_exec").put("attachments_read_document").put("native_next"));}
        ModelCapabilityAdapters.Binding bound(){return new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,"");}
        ToolOutputCapture.Factory factory(ToolOutputCapture.TextCleaner cleaner){return tasks.toolOutputFactory(id,run,epoch,control,cleaner);}
        RunJournal journal(){return tasks.journal(id,run,epoch,control);}
        JSONObject attach()throws Exception{return AttachmentRepository.get(context).book.importBytes(new ByteArrayInputStream("真实来源附件".getBytes(StandardCharsets.UTF_8)),"source.txt","text/plain",new JSONObject().put("kind","owner_fixture"));}
        JSONObject captureAttachment(JSONObject source)throws Exception{JSONObject safe=new JSONObject().put("attachmentId",source.getString("id")).put("sha256",source.getString("sha256")).put("chunks",new JSONArray().put(new JSONObject().put("unit",1).put("chunk",0).put("text","实际读取返回")));return factory(text->text).begin("attachments_read_document","",ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,control).record(safe,false).metadata();}
        TaskStoreMemoryReader.RawBinding raw()throws Exception{TaskStoreMemoryReader.RawBinding binding=new TaskStoreMemoryReader.RawBinding(id,run,epoch,control,new MemoryHostFence(){public void check()throws Exception{control.check();}public JSONObject admit(Admission action)throws Exception{return action.run();}},()->0);binding.bindOutputSources(tasks.toolOutputSources());return binding;}
        public void close(){db.close();}
    }
    private static final class ControlledSsh implements EngineModule {
        final JSONObject profile;
        JSONObject result;int calls;ControlledSsh(JSONObject result)throws Exception{this.result=result;profile=new JSONObject().put("id","native-profile").put("bindingHash","b".repeat(64)).put("hostFingerprint","SHA256:fixture-owner-approved").put("username","owner").put("hostKeyTrusted",true).put("credentialsConfigured",true);}
        public String id(){return "ssh";}public JSONObject snapshot()throws Exception{return new JSONObject().put("profiles",new JSONArray().put(profile));}
        public JSONObject handle(String command,JSONObject input)throws Exception{throw new AssertionError("generic handle is not a native run entry");}
        JSONObject invoke(String command,JSONObject input,RunControl original)throws Exception{original.check();calls++;return result;}
    }
    private static ModelCapabilityAdapters ssh(Fixture f,ControlledSsh nativePort,ToolOutputCapture.Factory factory)throws Exception{var binding=f.bound();return new ModelCapabilityAdapters(binding,new TaskStoreCapabilityAuthority(f.tasks,binding,event->{}),null,null,new ModelCapabilityAdapters.SshBinding(nativePort,nativePort.profile,()->{},nativePort::invoke),null,null,factory);}
    @Test public void actualAdapterArchivesBeforeModelClipAndRawRecoversItsNativeIssuedReference()throws Exception{
        try(Fixture f=new Fixture()){
            String original="A".repeat(80000)+"DEEP_NATIVE_CAUSE"+"B".repeat(9000);JSONObject nativeData=new JSONObject().put("status","completed").put("content",original+" captured-key-C4").put("outputComplete",true).put("outputTruncated",false).put("files",new JSONArray().put(new JSONObject().put("name","safe.txt").put("token","PRIVATE_NATIVE_TOKEN")));
            ControlledSsh nativePort=new ControlledSsh(nativeData);var adapter=ssh(f,nativePort,f.factory(text->ModelGateway.redact(text,"captured-key-C4")));var binding=f.raw();TaskStoreMemoryReader.RawCapability raw=new TaskStoreMemoryReader.RawCapability(adapter,binding,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject options)throws Exception{return f.tasks.queryMemoryRaw(b,options);}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject citation)throws Exception{return f.tasks.readMemoryRaw(b,citation);}});
            JSONObject readArgs=new JSONObject().put("path","fixture.txt");String operation=f.journal().beginOperation("ssh_read_file",readArgs);JSONObject view=raw.execute("ssh_read_file",new JSONObject(readArgs.toString()).put("_hostOperationId",operation),f.control);f.journal().finishOperation(operation,view);assertEquals(1,nativePort.calls);assertTrue(view.getBoolean("outputTruncated"));assertFalse(view.getJSONObject("data").toString().contains("DEEP_NATIVE_CAUSE"));assertTrue(view.toString().getBytes(StandardCharsets.UTF_8).length<=32768);
            JSONObject ref=view.getJSONObject("outputCapture").getJSONArray("outputRefs").getJSONObject(0),request=new JSONObject().put("sourceId",ref.getString("sourceId")).put("revision",ref.getString("revision")).put("offset",79990).put("maxChars",64);JSONObject expanded=raw.execute(TaskStoreMemoryReader.RAW_READ,request,f.control);assertEquals("current",expanded.getString("status"));assertTrue(expanded.getString("body").contains("DEEP_NATIVE_CAUSE"));
            try(Cursor row=f.db.rawQuery("SELECT body FROM task_output_chunks",null)){while(row.moveToNext()){assertFalse(row.getString(0).contains("PRIVATE_NATIVE_TOKEN"));assertFalse(row.getString(0).contains("captured-key-C4"));}}
        }
    }
    @Test public void archivingFailureDoesNotRewriteRealApprovedNativeEffectReceiptOrRecommendReplay()throws Exception{
        try(Fixture f=new Fixture()){
            ControlledSsh nativePort=new ControlledSsh(new JSONObject().put("status","completed").put("accepted",true).put("stdout","known native execution returned").put("exitCode",0));var adapter=ssh(f,nativePort,f.factory(text->{throw new java.io.IOException("controlled archive failure");}));JSONObject args=new JSONObject().put("command","printf fixture");
            String first=f.journal().beginOperation("ssh_exec",args);JSONObject refusal=adapter.execute("ssh_exec",new JSONObject(args.toString()).put("_hostOperationId",first),f.control);assertEquals("approval_required",refusal.getString("code"));assertEquals(0,nativePort.calls);f.journal().finishOperation(first,refusal);f.tasks.decideCapabilityApproval(f.id,refusal.getString("approvalId"),true);
            String operation=f.journal().beginOperation("ssh_exec",args);JSONObject actual=adapter.execute("ssh_exec",new JSONObject(args.toString()).put("_hostOperationId",operation),f.control);f.journal().finishOperation(operation,actual);assertEquals(1,nativePort.calls);assertEquals("completed",actual.getString("status"));assertTrue(actual.getBoolean("ok"));assertFalse(actual.optBoolean("requiresReconciliation"));assertEquals("unavailable",actual.getJSONObject("outputCapture").getString("status"));assertFalse(actual.getBoolean("automaticReplayAllowed"));f.factory(text->text).checkSources(f.control);
        }
    }
    @Test public void failedDurableAttachmentReserveRejectsBeforeNativeAndDoesNotLeavePendingPoison()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger entered=new AtomicInteger();var binding=f.bound();var adapter=new ModelCapabilityAdapters(binding,new TaskStoreCapabilityAuthority(f.tasks,binding,event->{}),null,null,null,null,(input,control)->{entered.incrementAndGet();return new JSONObject();},f.factory(text->text));
            f.db.execSQL("CREATE TEMP TRIGGER output_fixture_reserve_failure BEFORE INSERT ON meta WHEN NEW.key LIKE 'run.owner_source_context.%' BEGIN SELECT RAISE(ABORT,'controlled reserve failure'); END");JSONObject input=new JSONObject().put("attachmentId",UUID.randomUUID().toString()).put("expectedSha256","a".repeat(64));
            assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->adapter.execute("attachments_read_document",input,f.control));assertEquals(0,entered.get());assertFalse(f.control.isCancelled());f.db.execSQL("DROP TRIGGER output_fixture_reserve_failure");f.factory(text->text).checkSources(f.control);
        }
    }
    @Test public void physicalMainAndSummaryAdmissionRejectRevokedSourceWithZeroHttpAndNoUsageRefund()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.attach();f.captureAttachment(source);AttachmentRepository.get(f.context).book.remove(source.getString("id"));AtomicInteger posts=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{posts.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
            try{ModelConfig config=config(server);JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","source-bound request"));for(InferenceReceipt.Purpose purpose:new InferenceReceipt.Purpose[]{InferenceReceipt.Purpose.MAIN,InferenceReceipt.Purpose.SUMMARY})assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->ModelGateway.completeStreaming(config,messages,null,f.control,event->{},f.journal().inferenceTarget(purpose,"")));assertEquals(0,posts.get());assertEquals(0,f.tasks.inferenceUsage(f.id).getInt("attempts"));assertFalse(f.control.isCancelled());}finally{server.stop(0);}
        }
    }
    @Test public void httpInFlightSourceRemovalBlocksDecisionAndNewToolsWithoutCancellingKnownHistory()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.attach(),retained=f.captureAttachment(source);AtomicInteger posts=new AtomicInteger(),tools=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{try{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();AttachmentRepository.get(f.context).book.remove(source.getString("id"));byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","tool_calls").put("message",new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-native-next").put("type","function").put("function",new JSONObject().put("name","native_next").put("arguments","{}"))))))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
            try{
                CapabilityPort original=new CapabilityPort(){public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","native_next").put("description","controlled next step").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));}public boolean supports(String name){return name.equals("native_next");}public boolean sideEffecting(String name){return true;}public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{tools.incrementAndGet();return new JSONObject().put("ok",true).put("status","completed");}};
                var port=new ToolOutputSourcePort(original,f.factory(text->text),f.control);ContextBudgetPolicy.Source budgets=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(131072,2048,1024,262144,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);var outcome=new AgentRunner(null,port,budgets).run(f.id,"原来源失效后不可派发旧决定",config(server),f.control,event->{},f.journal());
                assertEquals("awaiting_user",outcome.status);assertEquals(1,posts.get());assertEquals(0,tools.get());assertFalse(f.control.isCancelled());assertEquals(1,f.tasks.inferenceUsage(f.id).getInt("attempts"));assertEquals(1,f.tasks.inferenceUsage(f.id).getInt("completed"));assertEquals("stored",retained.getString("status"));
                try(Cursor row=f.db.rawQuery("SELECT count(*) FROM operations WHERE task_id=? AND tool='native_next'",new String[]{f.id})){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
            }finally{server.stop(0);}
        }
    }
    /** Same original bug journey, with the actual native host/exposure binding and Runner terminal
     * contract. The earlier hand-host/completed assertion and its RED remain in Library history. */
    @Test public void unrelatedFormalForgetMustNotPoisonAProvenNoFormalMemorySshTask()throws Exception{
        try(Fixture f=new Fixture();dev.pocketengine.features.FeatureHost features=new dev.pocketengine.features.FeatureHost(event->{},f.tasks,()->false)){
            AtomicInteger posts=new AtomicInteger(),discarded=new AtomicInteger();ControlledSsh nativePort=new ControlledSsh(new JSONObject().put("status","completed").put("content","原生SSH已读，后续不应重放").put("outputComplete",true).put("outputTruncated",false));
            var adapter=ssh(f,nativePort,f.factory(text->text));var rawBinding=new TaskStoreMemoryReader.RawBinding(f.id,f.run,f.epoch,f.control,new MemoryHostFence(){public void check()throws Exception{f.control.check();}public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){return action.run();}}},features::memoryContextRevision);rawBinding.bindOutputSources(f.tasks.toolOutputSources());f.tasks.bindRawModelExposure(rawBinding);
            var raw=new TaskStoreMemoryReader.RawCapability(adapter,rawBinding,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject o)throws Exception{return f.tasks.queryMemoryRaw(b,o);}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject o)throws Exception{return f.tasks.readMemoryRaw(b,o);}});
            try(var extensions=dev.pocketengine.runtime.extensions.ExtensionHost.disabled(f.tasks)){
            CapabilityPort host=new ToolOutputSourcePort(extensions.bind(raw,new dev.pocketengine.runtime.extensions.ExtensionRuntime.Binding(f.id,f.run,f.epoch),f.control),f.factory(text->text),f.control);
            MemoryBook unrelated=new MemoryBook(new java.io.File(f.context.getFilesDir(),"unrelated-memory-"+UUID.randomUUID()+".json"));JSONObject record=unrelated.save(new JSONObject().put("ownerId","owner").put("spaceId","private"),new JSONObject().put("text","没有发给该任务的无关正式记录"),1);
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{try{
                int n=posts.incrementAndGet();exchange.getRequestBody().readAllBytes();if(n==2)unrelated.forgetReviewed(new JSONObject().put("ownerId","owner").put("spaceId","private"),record.getString("id"),record.getLong("revision"),gone->{features.clearWorkingMemory(new JSONObject().put("ownerId","owner").put("spaceId","private"));f.tasks.invalidateMemoryContexts(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),gone);});
                JSONObject answer=n==1?new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-original-ssh").put("type","function").put("function",new JSONObject().put("name","ssh_read_file").put("arguments","{\"path\":\"fixture.txt\"}")))):new JSONObject().put("role","assistant").put("content",n==2?"此旧surface答复必须丢弃":"fresh native surface continued");byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",n==1?"tool_calls":"stop").put("message",answer))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();
            try{ContextBudgetPolicy.Source budgets=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(131072,2048,1024,262144,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);var outcome=new AgentRunner(null,host,budgets).run(f.id,"继续不相关的SSH只读目标",config(server),f.control,event->{if("capability_changed".equals(event.optString("type")))discarded.incrementAndGet();},f.journal());
                System.out.println("C4_BARRIER_DIAGNOSTIC "+new JSONObject().put("status",outcome.status).put("summary",outcome.summary).put("evidence",outcome.evidence).put("httpPosts",posts.get()).put("nativeSshCalls",nativePort.calls).put("nativeHost","ExtensionHost.disabled actual prepareTurnSurface/admit").put("discardedOldAnswers",discarded.get()).put("capturedSourceRevision",rawBinding.sourceRevision).put("currentSourceRevision",features.memoryContextRevision()).put("sourceBarrierCurrent",rawBinding.sourceBarrierCurrent()).put("ownerCancelled",f.control.isCancelled()));
                try(Cursor rows=f.db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"run.owner_source_context."+f.run})){assertTrue(rows.moveToFirst());JSONObject fact=new JSONObject(rows.getString(0));assertTrue(fact.getBoolean("formalMemoryTrackingComplete"));assertEquals(0,fact.getJSONArray("formalMemoryDependencies").length());assertFalse(fact.getBoolean("outputSourcesWithdrawn"));}
                try(Cursor rows=f.db.rawQuery("SELECT count(*) FROM operations WHERE task_id=? AND tool IN ('native_memory_reference','memory_raw_query','memory_raw_read')",new String[]{f.id})){assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));}
                assertEquals("needs_confirmation",outcome.status);assertEquals("fresh native surface continued",outcome.evidence.getString("modelSummary"));assertFalse(outcome.summary.contains("此旧surface答复必须丢弃"));assertEquals(1,discarded.get());assertEquals(3,posts.get());assertEquals(1,nativePort.calls);assertFalse(f.control.isCancelled());assertTrue(rawBinding.readerRetired());assertEquals(0,rawBinding.sourceRevision);assertEquals(1,features.memoryContextRevision());assertFalse(host.supports(TaskStoreMemoryReader.RAW_QUERY));assertThrows(SecurityException.class,rawBinding::check);assertThrows(SecurityException.class,()->raw.execute(TaskStoreMemoryReader.RAW_QUERY,new JSONObject().put("section","outputs").put("query",""),f.control));
            }finally{server.stop(0);host.close();}
            }
        }
    }
    private static ModelConfig config(HttpServer server){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","fixture",false,8,10,"openai_chat",500);}
}
