package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.util.AtomicFile;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.memory.MemoryHostFence;
import dev.pocketengine.features.memory.TaskStoreMemoryReader;
import dev.pocketengine.features.memory.TaskMemoryNativeHost;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicLong;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.memory.ConversationHistory;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryHistory;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** The real original Assistant AtomicFile/source records; no executor/model/network is invoked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantHistorySourceTest {
    private static final class Fixture {
        final Context android=RuntimeEnvironment.getApplication();final String conversation=UUID.randomUUID().toString();final File file=new File(android.getFilesDir(),"history-"+conversation+".json");final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        AssistantModule module;
        Fixture(int count)throws Exception{write(count,new JSONArray());module=new AssistantModule(context,null,null,new AtomicFile(file));}
        void write(int count,JSONArray extras)throws Exception{
            JSONArray messages=new JSONArray(),sources=new JSONArray();for(int i=0;i<count;i++){String id="message-"+i,role=i%2==0?"user":"assistant";JSONObject message=new JSONObject().put("id",id).put("revision",1L).put("role",role).put("content","原始证据🙂 CAUSE-"+i).put("at",100+i);messages.put(message);sources.put(new SourceRef("message",id,1,"owner","private").toJson().put("conversationId",conversation).put("role",role).put("deleted",false));}for(int i=0;i<extras.length();i++)sources.put(extras.getJSONObject(i));
            JSONObject state=new JSONObject().put("version",2).put("conversations",new JSONArray().put(new JSONObject().put("conversationId",conversation).put("title","实际保存的测试会话").put("createdAt",1).put("messages",messages))).put("sourceStates",sources);Files.write(file.toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
        }
        SourceRef ref(int index){return new SourceRef("message","message-"+index,1,"owner","private");}
        JSONObject options(String query)throws Exception{return new JSONObject().put("query",query).put("cursor","").put("limit",20).put("maxChars",16000).put("createdAfter",-1).put("createdBefore",-1);}
        JSONObject query(JSONObject options)throws Exception{return module.queryRetainedHistory(module.openHistoryConversation(conversation),options,new RunControl());}
        JSONObject state(SourceRef expected)throws Exception{return module.inspectHistorySources(module.openHistoryConversation(conversation),List.of(expected)).getJSONObject(0);}
        JSONObject read(SourceRef expected,int offset,int chars)throws Exception{return module.readHistoryMessage(module.openHistoryConversation(conversation),expected,offset,chars,new RunControl());}
        void delete(int index)throws Exception{module.handle("deleteMessage",new JSONObject().put("conversationId",conversation).put("messageId","message-"+index).put("revision",1));}
    }
    @Test public void exactNativeMessageRevisionAndBodyHashStaySeparateAndUnicodeRangesAreReal()throws Exception{
        Fixture f=new Fixture(3);JSONObject row=f.query(f.options("CAUSE-0")).getJSONArray("entries").getJSONObject(0);assertEquals(1,row.getJSONObject("sourceRef").getLong("revision"));assertTrue(row.getString("bodyFingerprint").matches("sha256:[0-9a-f]{64}"));assertEquals("conversation_history_message",row.getJSONObject("sourceJump").getString("kind"));JSONObject exact=f.read(f.ref(0),0,64);assertEquals("原始证据🙂 CAUSE-0",exact.getString("body"));assertTrue(exact.getJSONObject("readRange").getBoolean("complete"));assertFalse(exact.getBoolean("executionAuthorized"));assertThrows(IllegalArgumentException.class,()->f.read(f.ref(0),5,64));
        f.module.handle("editMessage",new JSONObject().put("conversationId",f.conversation).put("messageId","message-0").put("revision",1).put("content","更新后的来源"));JSONObject changed=f.read(f.ref(0),0,64);assertEquals("source_changed",changed.getString("status"));assertFalse(changed.has("body"));
    }
    @Test public void unrelatedNewTailKeepsExistingRefsUsableAndCursorDoesNotAdmitNewTail()throws Exception{
        Fixture f=new Fixture(3);JSONObject first=f.query(f.options("").put("limit",1));String cursor=first.getString("nextCursor");ConversationHistory.Gate old=f.module.openHistoryConversation(f.conversation);f.module.appendTaskResult(f.conversation,"original-task","后来新增但不在旧水位内");assertTrue(old.current());assertEquals("current",f.state(f.ref(0)).getString("status"));assertEquals("current",f.read(f.ref(0),0,64).getString("status"));
        JSONObject second=f.query(f.options("").put("limit",1).put("cursor",cursor));assertEquals("message-1",second.getJSONArray("entries").getJSONObject(0).getJSONObject("sourceRef").getString("sourceId"));JSONObject third=f.query(f.options("").put("limit",20).put("cursor",second.getString("nextCursor")));assertEquals(1,third.getJSONArray("entries").length());assertEquals("message-2",third.getJSONArray("entries").getJSONObject(0).getJSONObject("sourceRef").getString("sourceId"));assertFalse(third.toString().contains("后来新增"));assertTrue(third.getString("nextCursor").isEmpty());
    }
    @Test public void deletedRetiredAndUnknownProvenanceNeverReturnCheckpointReplacementBodies()throws Exception{
        Fixture f=new Fixture(4);f.delete(0);JSONObject deleted=f.read(f.ref(0),0,64);assertEquals("deleted",deleted.getString("status"));assertEquals("user_message_delete",deleted.getString("reason"));assertFalse(deleted.has("body"));f.module.retireMemoryContexts(removedMemoryRecord(f.ref(2)),"owner_forget");JSONObject retired=f.read(f.ref(2),0,64);assertEquals("retired",retired.getString("status"));assertEquals("owner_forget",retired.getString("reason"));assertFalse(retired.has("body"));assertEquals(0,f.query(f.options("CAUSE-2")).getJSONArray("entries").length());JSONObject missing=f.state(new SourceRef("message","not-retained-provenance",1,"owner","private"));assertEquals("provenance_unavailable",missing.getString("status"));assertEquals("unknown",missing.getString("reason"));assertThrows(SecurityException.class,()->f.module.inspectHistorySources(f.module.openHistoryConversation(f.conversation),List.of(new SourceRef("message","message-1",1,"someone","private"))));
    }
    @Test public void capacityTrimIsDistinguishedAndLegacyMissingReasonsStayUnknown()throws Exception{
        Fixture f=new Fixture(80);f.module.appendTaskResult(f.conversation,"task-result","第81条造成原权威容量裁剪");JSONObject trimmed=f.state(f.ref(0));assertEquals("deleted",trimmed.getString("status"));assertEquals("capacity_message_limit",trimmed.getString("reason"));assertEquals(80,f.query(f.options("")).getJSONObject("pageCoverage").getInt("retainedCount"));
        Fixture legacy=new Fixture(1);legacy.write(1,new JSONArray().put(new SourceRef("message","old-tombstone",2,"owner","private").toJson().put("conversationId",legacy.conversation).put("role","user").put("deleted",true)));legacy.module=new AssistantModule(legacy.context,null,null,new AtomicFile(legacy.file));JSONObject unknown=legacy.state(new SourceRef("message","old-tombstone",1,"owner","private"));assertEquals("deleted",unknown.getString("status"));assertEquals("unknown",unknown.getString("reason"));assertFalse(unknown.has("body"));
    }
    @Test public void sourceRetirementStripsNativeHistoryToolAndDerivedAssistantProjectionButNotOriginalAudit()throws Exception{
        Fixture f=new Fixture(3);JSONObject row=f.query(f.options("CAUSE-0")).getJSONArray("entries").getJSONObject(0);JSONObject receipt=new JSONObject().put("conversationId",f.conversation).put("messageSourceRefs",new JSONArray().put(row.getJSONObject("sourceRef"))).put("taskSourceRefs",new JSONArray());JSONArray audit=new JSONArray().put(new JSONObject().put("role","user").put("content","本次主人原始要求"))
            .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","history-call").put("function",new JSONObject().put("name",ConversationHistory.SEARCH).put("arguments","{}")))))
            .put(new JSONObject().put("role","tool").put("tool_call_id","history-call").put("content",receipt.toString())).put(new JSONObject().put("role","assistant").put("content","由旧参考产生的答复"));String before=audit.toString();assertEquals(before,f.module.projectMemoryHistory(audit,Set.of(),f.conversation).toString());f.module.retireMemoryContexts(removedMemoryRecord(f.ref(0)),"owner_forget");JSONArray projected=f.module.projectMemoryHistory(audit,Set.of(),f.conversation);assertEquals(before,audit.toString());assertEquals("本次主人原始要求",projected.getJSONObject(0).getString("content"));assertTrue(projected.getJSONObject(2).getString("content").contains("reference_expired"));assertEquals(TaskMemoryHistory.RETIRED,projected.getJSONObject(3).getString("content"));
    }
    private static final class FailingFile extends AtomicFile {boolean fail;FailingFile(File base){super(base);}@Override public FileOutputStream startWrite()throws IOException{if(fail)throw new IOException("fixture disk failure");return super.startWrite();}}
    @Test public void rollbackAndReloadUseSameOriginalMetadataAndExactCitationRejectsChangedHash()throws Exception{
        Fixture f=new Fixture(2);FailingFile failing=new FailingFile(f.file);f.module=new AssistantModule(f.context,null,null,failing);failing.fail=true;assertThrows(IOException.class,()->f.module.appendTaskResult(f.conversation,"task","not committed"));assertEquals("current",f.state(f.ref(0)).getString("status"));assertEquals(2,f.query(f.options("")).getJSONObject("pageCoverage").getInt("retainedCount"));f.module=new AssistantModule(f.context,null,null,new AtomicFile(f.file));JSONObject row=f.query(f.options("CAUSE-0")).getJSONArray("entries").getJSONObject(0);JSONObject citation=new JSONObject().put("callerTaskId","caller-task").put("callerRunId","caller-run").put("conversationId",f.conversation).put("sourceJump",row.getJSONObject("sourceJump"));JSONObject page=f.read(f.ref(0),0,64);page.put("callerTaskId","caller-task").put("callerRunId","caller-run").put("conversationId",f.conversation);assertEquals(row.getString("body"),ConversationHistory.checkedCitationPage(page,citation).getString("body"));assertThrows(SecurityException.class,()->ConversationHistory.checkedCitationPage(new JSONObject(page.toString()).put("bodyFingerprint","sha256:"+"0".repeat(64)),citation));
    }    @Test public void realNativeTaskReceiptToAssistantSourceViewerAndSourceRetirementCheckAreMounted()throws Exception{
        Fixture f=new Fixture(3);f.android.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(f.android);JSONObject task=tasks.createCapabilityTask("原会话历史读取集成fixture",f.conversation);String id=task.getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",12);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);
        TaskStoreMemoryReader.RawBinding raw=new TaskStoreMemoryReader.RawBinding(id,run,epoch,original,new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){return action.run();}}},new AtomicLong()::get);
        ConversationHistory.HistoryBinding binding=new ConversationHistory.HistoryBinding(raw,f.conversation,new ConversationHistory.Source(){public ConversationHistory.Gate open(String cid)throws Exception{return f.module.openHistoryConversation(cid);}public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl control)throws Exception{return f.module.queryRetainedHistory(gate,options,control);}public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs)throws Exception{return f.module.inspectHistorySources(gate,refs);}public JSONObject read(ConversationHistory.Gate gate,SourceRef ref,int offset,int chars,RunControl control)throws Exception{return f.module.readHistoryMessage(gate,ref,offset,chars,control);}});
        CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError();}};
        try(ConversationHistory.Capability cap=new ConversationHistory.Capability(existing,binding,new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return tasks.queryConversationHistory(b,selected,kind,options);}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int chars)throws Exception{return tasks.readConversationHistoryOperation(b,selected,offset,chars);}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs)throws Exception{tasks.requireConversationHistorySources(b,refs);}});FeatureHost host=new FeatureHost(event->{})){
            host.register(f.module);JSONObject receipt=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("query","CAUSE-0"),original);tasks.event(id,run,epoch,new JSONObject().put("type","tool").put("tool",ConversationHistory.SEARCH).put("receipt",receipt));JSONArray citations=TaskMemoryTrace.historyCitationJumps(tasks.get(id,false));assertEquals(1,citations.length());JSONObject citation=citations.getJSONObject(0);TaskMemoryNativeHost viewer=new TaskMemoryNativeHost(host,tasks,new MemorySourceBridge(host,tasks));JSONObject page=viewer.handle("readConversationHistoryCitation",citation);assertEquals("current",page.getString("status"));assertEquals("原始证据🙂 CAUSE-0",page.getString("body"));assertFalse(page.getBoolean("executionAuthorized"));
            f.module.appendTaskResult(f.conversation,id,"正常无关新尾不撤销旧消息版本");original.check();assertFalse(original.isCancelled());assertEquals("current",viewer.handle("readConversationHistoryCitation",citation).getString("status"));
            f.delete(0);JSONObject unavailable=viewer.handle("readConversationHistoryCitation",citation);assertEquals("deleted",unavailable.getString("status"));assertFalse(unavailable.has("body"));assertThrows(InterruptedException.class,original::check);assertTrue(original.isCancelled());
            String fixtureRoot=AssistantHistorySourceTest.class.getResource("AssistantHistorySourceTest.class").toExternalForm().replace("dev/pocketengine/features/assistant/AssistantHistorySourceTest.class","");
            for(Class<?> actual:List.of(AssistantModule.class,TaskStore.class,ConversationHistory.class,TaskMemoryNativeHost.class,MemorySourceBridge.class,TaskMemoryTrace.class,TaskStoreMemoryReader.class,dev.pocketengine.features.memory.TaskStoreMemoryQuery.class,RunControl.class)){
                String resource=actual.getName().replace('.','/')+".class";java.net.URL location=actual.getResource(actual.getSimpleName()+".class");assertNotNull(location);assertEquals("production class must not come from old support fixture: "+actual,fixtureRoot,location.toExternalForm().replace(resource,""));
            }
        }
    }
    /** Matches MemorySourceBridge's original removed-memory-record contract, not SourceRef[]. */
    private static JSONArray removedMemoryRecord(SourceRef source)throws Exception{return new JSONArray().put(new JSONObject().put("id","fixture-removed-memory").put("revision",1).put("sourceRefs",new JSONArray().put(source.toJson())));}
    private interface DuringProjection {void run()throws Exception;}
    private static final class CurrentnessFixture {
        final Fixture source=new Fixture(3);final TaskStore tasks;final RunControl original=new RunControl();final String task,run;final int epoch;
        final TaskStoreMemoryReader.RawBinding raw;final ConversationHistory.HistoryBinding history;final JSONArray audit;int projections;DuringProjection duringSecond=()->{};
        CurrentnessFixture()throws Exception{
            source.android.deleteDatabase("pocket-world.db");tasks=new TaskStore(source.android);task=tasks.createCapabilityTask("原文currentness gate fixture",source.conversation).getString("id");JSONObject attempt=tasks.startAttempt(task,"capabilities",12);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);
            raw=new TaskStoreMemoryReader.RawBinding(task,run,epoch,original,new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){return action.run();}}},new AtomicLong()::get);
            history=new ConversationHistory.HistoryBinding(raw,source.conversation,new ConversationHistory.Source(){public ConversationHistory.Gate open(String cid)throws Exception{return source.module.openHistoryConversation(cid);}public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl control)throws Exception{return source.module.queryRetainedHistory(gate,options,control);}public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs)throws Exception{return source.module.inspectHistorySources(gate,refs);}public JSONObject read(ConversationHistory.Gate gate,SourceRef ref,int offset,int chars,RunControl control)throws Exception{return source.module.readHistoryMessage(gate,ref,offset,chars,control);}});
            CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError();}};
            JSONObject receipt;
            // Real production history receipt. Closing this fixture scope isolates persistent raw
            // currentness from its separate live provided-source callback; no historical control exists.
            try(ConversationHistory.Capability cap=new ConversationHistory.Capability(existing,history,new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return tasks.queryConversationHistory(b,selected,kind,options);}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int chars)throws Exception{return tasks.readConversationHistoryOperation(b,selected,offset,chars);}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs)throws Exception{tasks.requireConversationHistorySources(b,refs);}})){
                receipt=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("query","CAUSE-0"),original);
            }
            audit=new JSONArray().put(new JSONObject().put("role","user").put("content","当前主人原始要求"))
                .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","native-history-call").put("function",new JSONObject().put("name",ConversationHistory.SEARCH).put("arguments","{}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","native-history-call").put("content",receipt.toString()))
                .put(new JSONObject().put("role","assistant").put("content","HISTORY_DERIVED_EXACT"));
            tasks.journal(task,run,epoch,original).checkpoint(audit,0,new JSONObject());
            raw.bindMessageCurrentness(ConversationHistory.currentness(history,remaining->tasks.openConversationHistoryProjection(history,remaining),(messages,delegated)->{projections++;if(projections%2==0)duringSecond.run();return source.module.projectMemoryHistory(messages,delegated,source.conversation);}));
        }
        JSONObject query(String query)throws Exception{return tasks.queryMemoryRaw(raw,new JSONObject().put("section","messages").put("query",query).put("maxChars",16000));}
        String storedAudit()throws Exception{return tasks.journal(task,run,epoch,original).restore().getJSONArray("messages").toString();}
    }
    @Test public void sharedNativeGateKeepsValidOldOriginalAcrossTailButRefusesDeletedDerivedRawReread()throws Exception{
        CurrentnessFixture f=new CurrentnessFixture();String audit=f.storedAudit();JSONObject first=f.query("HISTORY_DERIVED_EXACT");assertEquals(1,first.getJSONArray("entries").length());JSONObject entry=first.getJSONArray("entries").getJSONObject(0);assertEquals(1,first.getJSONArray("gaps").length());assertEquals("derived_reference_receipt_excluded",first.getJSONArray("gaps").getJSONObject(0).getString("reason"));
        f.source.module.appendTaskResult(f.source.conversation,f.task,"正常新尾，不撤销已选原文");assertEquals(1,f.query("HISTORY_DERIVED_EXACT").getJSONArray("entries").length());assertFalse(f.original.isCancelled());f.source.delete(0);JSONObject retired=f.query("HISTORY_DERIVED_EXACT");assertEquals(0,retired.getJSONArray("entries").length());JSONObject old=new JSONObject().put("sourceId",entry.getString("sourceId")).put("revision",entry.getString("revision"));JSONObject read=f.tasks.readMemoryRaw(f.raw,old);assertEquals("unavailable",read.getString("status"));assertFalse(read.has("body"));assertEquals(audit,f.storedAudit());assertEquals(1,f.query("当前主人原始要求").getJSONArray("entries").length());
    }
    @Test public void sharedSessionRechecksRelatedSourceAtReturnAndNeverPublishesPartialOldBodies()throws Exception{
        CurrentnessFixture f=new CurrentnessFixture();String audit=f.storedAudit();f.duringSecond=()->f.source.module.retireMemoryContexts(removedMemoryRecord(f.source.ref(0)),"owner_forget");JSONObject reply=f.query("HISTORY_DERIVED_EXACT");assertEquals("unavailable",reply.getString("status"));assertEquals(0,reply.getJSONArray("entries").length());assertEquals("native_message_currentness_unavailable",reply.getString("reason"));assertEquals(audit,f.storedAudit());JSONObject budget=reply.getJSONObject("pageCoverage");assertTrue(budget.getLong("scannedUtf8Bytes")<=1_048_576);assertTrue(budget.getJSONObject("currentnessBudget").getInt("projectionMessageMetadataRows")>=f.audit.length()*2);assertEquals(0,budget.getJSONObject("currentnessBudget").getLong("guardSourceUtf8Bytes"));assertFalse(f.original.isCancelled());
    }
    @Test public void currentnessHashesDependenciesOnceUnderOriginalMonitorAndNeverExpandsOneMiBSourceBudget()throws Exception{
        CurrentnessFixture f=new CurrentnessFixture();String receiptText=new JSONObject().put("text","BUDGET_DEPENDENCY_"+"z".repeat(650000)).toString();
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(f.source.android.getDatabasePath("pocket-world.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)){
            android.content.ContentValues row=new android.content.ContentValues();row.put("operation_id","gate-large-operation");row.put("task_id",f.task);row.put("run_id",f.run);row.put("tool","workspace_read");row.put("arguments","{}");row.put("state","completed");row.put("receipt",receiptText);row.put("created_at",1);db.insertOrThrow("operations",null,row);
        }
        CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError();}};JSONObject historyReceipt;
        try(ConversationHistory.Capability cap=new ConversationHistory.Capability(existing,f.history,new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return f.tasks.queryConversationHistory(b,selected,kind,options);}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int chars)throws Exception{return f.tasks.readConversationHistoryOperation(b,selected,offset,chars);}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs)throws Exception{f.tasks.requireConversationHistorySources(b,refs);}})){
            JSONObject tasks=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query",""),f.original);String taskSelection=tasks.getJSONArray("entries").getJSONObject(0).getString("selectionId");JSONObject runs=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query","").put("selectionId",taskSelection),f.original);historyReceipt=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","operations").put("query","BUDGET_DEPENDENCY_").put("selectionId",runs.getJSONArray("entries").getJSONObject(0).getString("selectionId")),f.original);
        }
        assertEquals(1,historyReceipt.getJSONArray("taskSourceRefs").length());f.audit.getJSONObject(2).put("content",historyReceipt.toString());f.tasks.journal(f.task,f.run,f.epoch,f.original).checkpoint(f.audit,0,new JSONObject());JSONObject readable=f.query("HISTORY_DERIVED_EXACT");assertEquals(1,readable.getJSONArray("entries").length());JSONObject budget=readable.getJSONObject("pageCoverage");long guard=budget.getJSONObject("currentnessBudget").getLong("guardSourceUtf8Bytes");assertTrue(guard>650000&&guard<660000);assertTrue(budget.getLong("scannedUtf8Bytes")<=1_048_576);assertEquals(1,budget.getJSONObject("currentnessBudget").getInt("guardUniqueSourceRefs"));
        f.audit.put(new JSONObject().put("role","user").put("content","x".repeat(500000)));f.tasks.journal(f.task,f.run,f.epoch,f.original).checkpoint(f.audit,0,new JSONObject());JSONObject limited=f.query("HISTORY_DERIVED_EXACT");assertEquals(0,limited.getJSONArray("entries").length());JSONObject blocked=limited.getJSONObject("pageCoverage").getJSONObject("currentnessBudget");assertEquals("related_source_byte_budget",blocked.getString("guardUnavailableReason"));assertEquals(0,blocked.getLong("guardSourceUtf8Bytes"));assertTrue(limited.getJSONObject("pageCoverage").getLong("scannedUtf8Bytes")<=1_048_576);assertEquals(1,f.query("当前主人原始要求").getJSONArray("entries").length());
    }

}
