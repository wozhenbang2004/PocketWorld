package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** Real original TaskStore/Book/Assistant history/Runner. Native returned JSON and model HTTP are
 * controlled fixtures, not live SSH/provider/device evidence. No historical controller is reused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HistoricalToolOutputJourneyTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final String conversation=UUID.randomUUID().toString();
        TaskStore tasks;SQLiteDatabase db;AttachmentBook book;AssistantModule assistant;FeatureContext context;
        String sourceTask,sourceRun,caller,callerRun;int sourceEpoch,callerEpoch;RunControl sourceControl,control;
        TaskStoreMemoryReader.RawBinding raw;ConversationHistory.HistoryBinding history;ConversationHistory.Capability cap;boolean slowSecondHistoryOperation;int historyOperationProbes;
        Fixture()throws Exception{android.deleteDatabase("pocket-world.db");open();tasks.conversationRepository().initializeEmpty();
            tasks.conversationRepository().transaction(()->{tasks.conversationRepository().saveConversation(new JSONObject().put("conversationId",conversation).put("title","旧任务原输出").put("createdAt",1));return null;});
            JSONObject started=tasks.startAttempt(tasks.createCapabilityTask("历史原件真实来源",conversation).getString("id"),"capabilities",16);sourceTask=started.getString("id");sourceRun=started.getString("runId");sourceEpoch=started.getInt("coordinatorEpoch");sourceControl=new RunControl();sourceControl.bindHostRun(sourceTask,sourceRun,sourceEpoch);
            tasks.authorizeTools(sourceTask,sourceRun,new JSONArray().put("ssh_read_file").put("attachments_read_document"));}
        void open()throws Exception{tasks=new TaskStore(android);var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);db=(SQLiteDatabase)f.get(tasks);book=AttachmentRepository.get(android).book;
            context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});assistant=new AssistantModule(context,null,null,tasks);}
        JSONObject capture(String tool,JSONObject data)throws Exception{
            String operation=tool.equals("ssh_read_file")?tasks.journal(sourceTask,sourceRun,sourceEpoch,sourceControl).beginOperation(tool,new JSONObject().put("path","historical.txt")):"";
            JSONObject captured=tasks.toolOutputFactory(sourceTask,sourceRun,sourceEpoch,sourceControl,text->text).begin(tool,operation,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,sourceControl).record(data,false).metadata();
            if(!operation.isEmpty())tasks.journal(sourceTask,sourceRun,sourceEpoch,sourceControl).finishOperation(operation,new JSONObject().put("status","completed").put("outputCapture",captured));return captured.getJSONArray("outputRefs").getJSONObject(0);
        }
        JSONObject attachment(String text)throws Exception{return book.importBytes(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),"historical.txt","text/plain",new JSONObject());}
        JSONObject document(JSONObject file,String text)throws Exception{return new JSONObject().put("attachmentId",file.getString("id")).put("sha256",file.getString("sha256")).put("text",text);}
        void caller()throws Exception{
            if(cap!=null)cap.close();sourceControl.cancel();tasks.status(sourceTask,"cancelled","原来源run已停止，读取不得复活它");
            JSONObject started=tasks.startAttempt(tasks.createCapabilityTask("只回查本会话旧任务实际原件",conversation).getString("id"),"capabilities",16);
            caller=started.getString("id");callerRun=started.getString("runId");callerEpoch=started.getInt("coordinatorEpoch");control=new RunControl();control.bindHostRun(caller,callerRun,callerEpoch);
            tasks.authorizeTools(caller,callerRun,new JSONArray().put(ConversationHistory.SEARCH).put(ConversationHistory.READ).put(TaskStoreMemoryReader.RAW_QUERY).put(TaskStoreMemoryReader.RAW_READ));
            MemoryHostFence fence=new MemoryHostFence(){public void check()throws Exception{tasks.checkExtensionRun(caller,callerRun,callerEpoch,control);
                if(slowSecondHistoryOperation)for(StackTraceElement frame:Thread.currentThread().getStackTrace())if(frame.getClassName().equals(TaskStoreMemoryQuery.class.getName())&&frame.getMethodName().equals("historyOperation")){if(++historyOperationProbes==2)Thread.sleep(550);break;}
            }public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}};
            raw=new TaskStoreMemoryReader.RawBinding(caller,callerRun,callerEpoch,control,fence,()->0);raw.bindOutputSources(tasks.toolOutputSources());tasks.bindRawModelExposure(raw);
            history=new ConversationHistory.HistoryBinding(raw,conversation,new ConversationHistory.Source(){
                public ConversationHistory.Gate open(String id)throws Exception{return assistant.openHistoryConversation(id);}
                public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl live)throws Exception{return assistant.queryRetainedHistory(gate,options,live);}
                public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs)throws Exception{return assistant.inspectHistorySources(gate,refs);}
                public JSONObject read(ConversationHistory.Gate gate,SourceRef ref,int offset,int chars,RunControl live)throws Exception{return assistant.readHistoryMessage(gate,ref,offset,chars,live);}
            });
            raw.bindMessageCurrentness(ConversationHistory.currentness(history,remaining->tasks.openConversationHistoryProjection(history,remaining),new ConversationHistory.MessageProjection(){
                public JSONArray project(JSONArray messages,java.util.Set<String> ids)throws Exception{return assistant.projectMemoryHistory(messages,ids,conversation);}
                public JSONArray project(JSONArray messages,java.util.Set<String> ids,ConversationHistory.SourceBytes bytes)throws Exception{return assistant.projectMemoryHistory(messages,ids,conversation,bytes);}
            }));
            CapabilityPort base=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl live){throw new AssertionError("unexpected native dispatch");}};
            var rawPort=new TaskStoreMemoryReader.RawCapability(base,raw,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject options)throws Exception{return tasks.queryMemoryRaw(b,options);}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject input)throws Exception{return tasks.readMemoryRaw(b,input);}});
            cap=new ConversationHistory.Capability(rawPort,history,new ConversationHistory.Tasks(){
                public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return tasks.queryConversationHistory(b,selected,kind,options);}
                public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int chars)throws Exception{return tasks.readConversationHistoryOperation(b,selected,offset,chars);}
                public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> provided)throws Exception{tasks.requireConversationHistorySources(b,provided);}
            });
        }
        String runSelection()throws Exception{
            JSONObject found=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query",sourceTask).put("maxChars",16000),control),task=null;
            for(int i=0;i<found.getJSONArray("entries").length();i++){JSONObject row=found.getJSONArray("entries").getJSONObject(i);if(sourceTask.equals(row.optString("sourceTaskId")))task=row;}assertNotNull(task);
            JSONObject runs=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("selectionId",task.getString("selectionId")).put("maxChars",16000),control);
            for(int i=0;i<runs.getJSONArray("entries").length();i++){JSONObject row=runs.getJSONArray("entries").getJSONObject(i);if(sourceRun.equals(row.optString("sourceRunId")))return row.getString("selectionId");}throw new AssertionError("original run missing");
        }
        JSONObject outputs(String selected)throws Exception{return cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","outputs").put("selectionId",selected).put("maxChars",16000),control);}
        JSONObject read(JSONObject selected,int offset,int chars)throws Exception{return cap.execute(ConversationHistory.READ,new JSONObject().put("selectionId",selected.getString("selectionId")).put("offset",offset).put("maxChars",chars),control);}
        void cold()throws Exception{if(cap!=null){cap.close();cap=null;}assistant.close();db.close();var f=AttachmentRepository.class.getDeclaredField("INSTANCES");f.setAccessible(true);((Map<?,?>)f.get(null)).remove(android.getFilesDir().getAbsolutePath());open();}
        public void close(){if(cap!=null)cap.close();assistant.close();db.close();}
    }
    @Test public void selectedStoppedHistoricalRunListsMetadataAndReadsDeep600KiBTailWithoutOldControl()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject ref=f.capture("ssh_read_file",new JSONObject().put("content","A".repeat(600*1024)+" 原始尾部原因🙂 END-HISTORY"));f.caller();JSONObject index=f.outputs(f.runSelection());
            assertEquals(1,index.getJSONArray("entries").length());assertEquals(0,index.getInt("charsReturned"));assertEquals(0,index.getJSONArray("taskSourceRefs").length());assertEquals(0,index.getJSONObject("pageCoverage").getInt("outputChunksRead"));assertEquals(0L,index.getJSONObject("pageCoverage").getLong("outputBodyUtf8Bytes"));
            JSONObject selected=index.getJSONArray("entries").getJSONObject(0);assertFalse(selected.has("body"));assertEquals("task_output",selected.getString("sourceKind"));assertEquals(ref.getString("revision"),selected.getString("revision"));
            int total=ref.getJSONObject("retainedRange").getInt("chars");JSONObject page=f.read(selected,total-64,64);assertEquals("current",page.getString("status"));assertTrue(page.getString("body").contains("END-HISTORY"));assertTrue(page.getString("body").contains("原始尾部原因🙂"));
            assertEquals(f.caller,page.getString("callerTaskId"));assertEquals(f.sourceTask,page.getString("taskId"));assertEquals(f.sourceRun,page.getString("runId"));assertFalse(f.control.matchesHostRun(f.sourceTask,f.sourceRun,f.sourceEpoch));assertTrue(f.sourceControl.isCancelled());assertFalse(f.control.isCancelled());
            assertTrue(page.getJSONObject("readBudget").getLong("outputBodyUtf8Bytes")<=8192);assertFalse(page.getBoolean("executionAuthorized"));assertEquals(1,page.getJSONArray("taskSourceRefs").length());
        }
    }
    @Test public void opaqueSelectionCursorAndMetadataQueryCannotBecomeForeignSourceOrFullBodySearch()throws Exception{
        try(Fixture f=new Fixture()){
            f.capture("ssh_read_file",new JSONObject().put("content","HIDDEN-DEEP-BODY"));f.capture("ssh_read_file",new JSONObject().put("content","SECOND-RETAINED"));f.caller();String run=f.runSelection();
            assertThrows(IllegalArgumentException.class,()->f.cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","outputs").put("selectionId",run).put("taskId",f.sourceTask),f.control));
            JSONObject none=f.cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","outputs").put("selectionId",run).put("query","HIDDEN-DEEP-BODY").put("maxChars",16000),f.control);assertEquals(0,none.getJSONArray("entries").length());
            JSONObject page=f.cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","outputs").put("selectionId",run).put("limit",1).put("maxChars",16000),f.control);assertTrue(page.getBoolean("hasMore"));String cursor=page.getString("nextCursor");assertEquals(36,cursor.length());
            assertThrows(SecurityException.class,()->f.cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","outputs").put("selectionId",run).put("cursor",cursor).put("query","ssh"),f.control));
            JSONObject next=f.cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","outputs").put("selectionId",run).put("cursor",cursor).put("limit",1).put("maxChars",16000),f.control);
            assertNotEquals(page.getJSONArray("entries").getJSONObject(0).getString("sourceId"),next.getJSONArray("entries").getJSONObject(0).getString("sourceId"));
        }
    }
    @Test public void nativeReadInheritsAttachmentDependencyBeforeBodyAndRetirementBlocksModelButPreservesOwnerAudit()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject file=f.attachment("真实附件原文");f.capture("attachments_read_document",f.document(file,"历史原件依赖不能洗掉"));f.caller();JSONObject selected=f.outputs(f.runSelection()).getJSONArray("entries").getJSONObject(0),receipt=f.read(selected,0,4000);
            f.tasks.event(f.caller,f.callerRun,f.callerEpoch,new JSONObject().put("type","tool").put("tool",ConversationHistory.READ).put("receipt",receipt));
            JSONArray citations=TaskMemoryTrace.historyCitationJumps(f.tasks.get(f.caller,false));assertEquals(1,citations.length());
            try(FeatureHost host=new FeatureHost(event->{})){host.register(f.assistant);TaskMemoryNativeHost owner=new TaskMemoryNativeHost(host,f.tasks,new MemorySourceBridge(host,f.tasks));JSONObject visible=owner.handle("readConversationHistoryCitation",citations.getJSONObject(0),new RunControl());assertTrue(visible.getString("body").contains("历史原件依赖不能洗掉"));
                f.book.remove(file.getString("id"));assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.tasks.toolOutputFactory(f.caller,f.callerRun,f.callerEpoch,f.control,text->text).checkSources(f.control));
                assertThrows(Exception.class,()->f.read(selected,0,4000));JSONObject audit=owner.handle("readConversationHistoryCitation",citations.getJSONObject(0),new RunControl());assertTrue(audit.getString("body").contains("历史原件依赖不能洗掉"));assertTrue(audit.getBoolean("auditOnly"));assertFalse(audit.getBoolean("modelSourceAdmission"));
            }
            assertFalse(f.control.isCancelled());
        }
    }
    @Test public void exactHashRangeAndMissingSourceFactFailClosedWithoutChangingOwnerAudit()throws Exception{
        try(Fixture f=new Fixture()){
            f.capture("ssh_read_file",new JSONObject().put("content","012345🙂真实原件"));f.caller();JSONObject selected=f.outputs(f.runSelection()).getJSONArray("entries").getJSONObject(0);
            assertThrows(IllegalArgumentException.class,()->f.cap.execute(ConversationHistory.READ,new JSONObject().put("selectionId",selected.getString("selectionId")).put("revision","sha256:"+"0".repeat(64)),f.control));
            JSONObject first=f.read(selected,0,4000);int low=first.getString("body").indexOf("🙂")+1;assertThrows(Exception.class,()->f.read(selected,low,64));
            f.db.delete("meta","key=?",new String[]{"run.owner_source_context."+f.sourceRun});assertThrows(Exception.class,()->f.read(selected,0,64));
            JSONObject audit=f.tasks.readNativeOutputAudit(MemoryBook.Scope.from(scope()),new JSONObject().put("taskId",f.sourceTask).put("runId",f.sourceRun).put("sourceId",selected.getString("sourceId")).put("revision",selected.getString("revision")).put("offset",0).put("maxChars",4000),new RunControl());assertTrue(audit.getString("body").contains("真实原件"));assertFalse(f.control.isCancelled());
        }
    }
    @Test public void coldBookRevalidatesSelectedHistoricalBytesWithoutScanningOtherRuns()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject file=f.attachment("冷恢复仍是同一份原附件");f.capture("attachments_read_document",f.document(file,"COLD-HISTORICAL-OUTPUT"));f.tasks.status(f.sourceTask,"completed","fixture source complete");f.cold();assertNull(f.book.outputSourceEligibility(file.getString("id"),file.getString("sha256")));
            f.caller();JSONObject result=f.outputs(f.runSelection());assertEquals(1,result.getJSONArray("entries").length());assertEquals(1,result.getJSONObject("sourceRevalidation").getInt("dependenciesChecked"));assertTrue(result.getJSONObject("sourceRevalidation").getLong("revalidatedOriginalSourceBytes")>0);assertEquals(0,result.getJSONObject("pageCoverage").getLong("outputBodyUtf8Bytes"));
            assertTrue(f.read(result.getJSONArray("entries").getJSONObject(0),0,4000).getString("body").contains("COLD-HISTORICAL-OUTPUT"));assertFalse(f.control.isCancelled());
        }
    }
    @Test public void changedColdSourceOrForeignMembershipCannotBePromotedByHistoricalSelection()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject file=f.attachment("原不可变附件");f.capture("attachments_read_document",f.document(file,"不能通过旧输出复活"));f.caller();String run=f.runSelection();
            JSONObject task=f.tasks.get(f.sourceTask,false);task.put("conversationId",UUID.randomUUID().toString());f.db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{task.toString(),f.sourceTask});assertThrows(SecurityException.class,()->f.outputs(run));
            task.put("conversationId",f.conversation);f.db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{task.toString(),f.sourceTask});f.tasks.status(f.sourceTask,"completed","fixture source complete");
            Files.write(new File(f.android.getFilesDir(),"attachments/"+file.getString("id")+".blob").toPath(),"外部改动的字节".getBytes(StandardCharsets.UTF_8));f.cold();f.caller();String newRun=f.runSelection();assertThrows(TaskStoreMemoryReader.RawMessageUnavailable.class,()->f.outputs(newRun));assertNull(f.book.outputSourceEligibility(file.getString("id"),file.getString("sha256")));
        }
    }
    @Test public void formalVersionIntersectionAndReopenKeepTheInheritedHistoricalDependency()throws Exception{
        try(Fixture f=new Fixture()){
            MemoryBook memory=new MemoryBook(new File(f.android.getFilesDir(),"history-memory-"+UUID.randomUUID()+".json"));JSONObject record=memory.save(scope(),new JSONObject().put("text","历史输出真正prepare的正式原版本").put("core",true),1);
            var ref=new TaskMemoryUse.TaskRef(f.sourceTask,f.sourceRun,f.sourceEpoch,"owner","private");var use=new TaskMemoryUse(memory,new TaskStoreMemoryReader(f.tasks,null),null);
            MemoryHostFence fence=new MemoryHostFence(){public void check()throws Exception{f.sourceControl.check();}public JSONObject admit(Admission a)throws Exception{synchronized(f.tasks){check();return a.run();}}};
            var recall=use.prepareTaskRecall(scope(),ref,"",16,12000,fence);f.tasks.prepareMemoryReference(TaskMemoryJournal.prepare(UUID.randomUUID().toString(),ref,"prepared-source",recall),f.sourceControl);
            f.capture("ssh_read_file",new JSONObject().put("content","源于正式依据的原输出"));f.caller();JSONObject selected=f.outputs(f.runSelection()).getJSONArray("entries").getJSONObject(0);f.read(selected,0,4000);
            JSONObject unrelated=memory.save(scope(),new JSONObject().put("text","未提供的无关新正式记录"),2);memory.forgetReviewed(scope(),unrelated.getString("id"),unrelated.getLong("revision"),gone->f.tasks.invalidateMemoryContexts(MemoryBook.Scope.from(scope()),gone));f.tasks.requireToolOutputSources(f.caller,f.callerRun,f.callerEpoch,f.control);
            memory.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"),gone->f.tasks.invalidateMemoryContexts(MemoryBook.Scope.from(scope()),gone));assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.tasks.requireToolOutputSources(f.caller,f.callerRun,f.callerEpoch,f.control));
            String originalCaller=f.caller;f.cold();assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.tasks.warmToolOutputSources(originalCaller,new RunControl()));
        }
    }
    @Test public void actualRunnerReadsOldTailThroughOpaqueStagesAndOnlyThenSendsItAsToolEvidence()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject ref=f.capture("ssh_read_file",new JSONObject().put("content","x".repeat(80000)+" RUNNER-HISTORY-TAIL"));f.caller();int offset=ref.getJSONObject("retainedRange").getInt("chars")-64;
            List<JSONObject> requests=new ArrayList<>();AtomicInteger posts=new AtomicInteger();HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/v1/chat/completions",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));synchronized(requests){requests.add(request);}int n=posts.incrementAndGet();JSONObject args=new JSONObject();String name=ConversationHistory.SEARCH;
                JSONArray messages=request.getJSONArray("messages");JSONObject previous=null;for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){previous=new JSONObject(messages.getJSONObject(i).getString("content"));break;}
                if(n==1)args.put("kind","runs").put("query",f.sourceTask).put("maxChars",16000);
                else if(n==2)args.put("kind","runs").put("selectionId",previous.getJSONArray("entries").getJSONObject(0).getString("selectionId")).put("maxChars",16000);
                else if(n==3)args.put("kind","outputs").put("selectionId",previous.getJSONArray("entries").getJSONObject(0).getString("selectionId")).put("maxChars",16000);
                else if(n==4){name=ConversationHistory.READ;args.put("selectionId",previous.getJSONArray("entries").getJSONObject(0).getString("selectionId")).put("offset",offset).put("maxChars",64);}
                JSONObject answer=new JSONObject().put("role","assistant").put("content",n==5?"已从原保留输出回查尾部，请主人核对":"");
                if(n<5)answer.put("tool_calls",new JSONArray().put(new JSONObject().put("id","history-stage-"+n).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
                byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",n<5?"tool_calls":"stop").put("message",answer))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});http.start();
            try{
                ModelConfig config=new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort()+"/v1","fixture-key","fixture",false,12,10,"openai_chat",1024);
                var factory=f.tasks.toolOutputFactory(f.caller,f.callerRun,f.callerEpoch,f.control,text->text);var port=new ToolOutputSourcePort(f.cap,factory,f.control);
                ContextBudgetPolicy.Source budgets=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(131072,2048,1024,262144,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
                AgentRunner.RequestProjection projection=(history,selected,live)->{
                    JSONArray projected=f.assistant.projectMemoryHistory(history,java.util.Set.of(),f.conversation);
                    projected=f.tasks.projectConversationHistoryReferences(projected,f.conversation);
                    return f.tasks.projectOwnerSourceContext(f.caller,f.callerRun,f.callerEpoch,live,projected);
                };
                var outcome=new AgentRunner(null,port,budgets,projection,null).run(f.caller,"只读旧任务已保存的真实尾部",config,f.control,event->{},f.tasks.journal(f.caller,f.callerRun,f.callerEpoch,f.control));
                assertEquals("needs_confirmation",outcome.status);assertEquals(5,posts.get());for(int i=0;i<4;i++)assertFalse(requests.get(i).toString().contains("RUNNER-HISTORY-TAIL"));
                assertTrue(requests.get(4).toString().contains("RUNNER-HISTORY-TAIL"));assertTrue(f.sourceControl.isCancelled());assertFalse(f.control.isCancelled());
                try(Cursor calls=f.db.rawQuery("SELECT count(*) FROM operations WHERE task_id=? AND tool='ssh_read_file'",new String[]{f.caller})){assertTrue(calls.moveToFirst());assertEquals(0,calls.getInt(0));}
            }finally{http.stop(0);}
        }
    }
    /** This is RED-first: the existing operations catch must not return a body whose dependency
     * inheritance transaction was rolled back. Original 500ms deadline and native gates remain. */
    @Test public void crossRunOperationDeadlineRollbackReturnsNoBodyReferenceOrAdvancedCursor()throws Exception{
        try(Fixture f=new Fixture()){
            MemoryBook memory=new MemoryBook(new File(f.android.getFilesDir(),"op-source-memory-"+UUID.randomUUID()+".json"));JSONObject record=memory.save(scope(),new JSONObject().put("text","这两条原操作真正prepare的正式版本").put("core",true),1);
            var source=new TaskMemoryUse.TaskRef(f.sourceTask,f.sourceRun,f.sourceEpoch,"owner","private");var use=new TaskMemoryUse(memory,new TaskStoreMemoryReader(f.tasks,null),null);
            MemoryHostFence sourceFence=new MemoryHostFence(){public void check()throws Exception{f.sourceControl.check();}public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){check();return action.run();}}};
            var recalled=use.prepareTaskRecall(scope(),source,"",16,12000,sourceFence);f.tasks.prepareMemoryReference(TaskMemoryJournal.prepare(UUID.randomUUID().toString(),source,"native-op-prepare",recalled),f.sourceControl);
            for(int i=0;i<2;i++){var journal=f.tasks.journal(f.sourceTask,f.sourceRun,f.sourceEpoch,f.sourceControl);String operation=journal.beginOperation("ssh_read_file",new JSONObject().put("path","old-"+i));journal.finishOperation(operation,new JSONObject().put("status","completed").put("content","OP-NATIVE-BODY-"+i));}
            f.caller();String run=f.runSelection();f.slowSecondHistoryOperation=true;
            JSONObject result=f.cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","operations").put("selectionId",run).put("maxChars",16000),f.control);
            assertEquals(2,f.historyOperationProbes);assertEquals("read_deadline",result.getString("reason"));
            try(Cursor row=f.db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"run.owner_source_context."+f.callerRun})){assertTrue(row.moveToFirst());assertEquals("failed original transaction must not silently publish dependency facts",0,new JSONObject(row.getString(0)).getJSONArray("formalMemoryDependencies").length());}
            assertEquals("no body may cross an uncommitted inheritance transaction",0,result.getJSONArray("entries").length());assertEquals(0,result.getJSONArray("taskSourceRefs").length());assertEquals("",result.getString("nextCursor"));assertFalse(result.toString().contains("OP-NATIVE-BODY"));assertFalse(f.control.isCancelled());
        }
    }

}
