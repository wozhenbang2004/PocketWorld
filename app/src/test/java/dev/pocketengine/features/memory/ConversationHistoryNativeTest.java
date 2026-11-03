package dev.pocketengine.features.memory;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskAuditStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original bounded producer on real SQLite and synthetic records, not model/device verification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationHistoryNativeTest {
    private static final class Fixture implements AutoCloseable {
        final SQLiteDatabase db=SQLiteDatabase.create(null);final String caller=UUID.randomUUID().toString(),callerRun=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString();
        final String historical=UUID.randomUUID().toString(),pastRun=UUID.randomUUID().toString();final RunControl control=new RunControl();
        final TaskStoreMemoryReader.RawBinding raw;final ConversationHistory.HistoryBinding binding;
        Fixture()throws Exception{
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            TaskAuditStore.createSchema(db);
            db.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,epoch INTEGER NOT NULL,status TEXT NOT NULL,checkpoint TEXT NOT NULL,grant_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(task_id,epoch))");
            db.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");
            saveTask(task(caller,callerRun,1,"running",false,conversation),5);saveTask(task(historical,pastRun,2,"cancelled",true,conversation),4);
            run(caller,callerRun,1,"running");run(historical,pastRun,2,"cancelled");OwnerSourceRunContext.initialize(db,caller,callerRun,1,"",false);OwnerSourceRunContext.initialize(db,historical,pastRun,2,"",false);control.bindHostRun(caller,callerRun,1);
            raw=new TaskStoreMemoryReader.RawBinding(caller,callerRun,1,control,new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}},new AtomicLong()::get);
            binding=new ConversationHistory.HistoryBinding(raw,conversation,new ConversationHistory.Source(){public ConversationHistory.Gate open(String id){return ()->true;}public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl live)throws Exception{return new JSONObject().put("status","current").put("entries",new JSONArray()).put("nextCursor","");}public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> expected){return new JSONArray();}public JSONObject read(ConversationHistory.Gate gate,SourceRef expected,int offset,int chars,RunControl live){throw new AssertionError("SQL fixture never supplies message bodies");}});
        }
        JSONObject task(String id,String run,int epoch,String state,boolean cancel,String conv)throws Exception{return new JSONObject().put("id",id).put("actorId","owner").put("spaceId","private").put("kind","assistant").put("runId",run).put("coordinatorEpoch",epoch).put("cancelRequested",cancel).put("status",state).put("conversationId",conv).put("createdAt",1);}
        void saveTask(JSONObject task,long updated)throws Exception{ContentValues row=new ContentValues();row.put("task_id",task.getString("id"));row.put("document",task.toString());row.put("updated_at",updated);db.insertWithOnConflict("tasks",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
        JSONObject load(String id)throws Exception{try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{id})){row.moveToFirst();return new JSONObject(row.getString(0));}}
        void event(JSONObject event)throws Exception{db.beginTransaction();try{TaskAuditStore.append(db,caller,event);db.setTransactionSuccessful();}finally{db.endTransaction();}}
        void run(String task,String id,int epoch,String state){ContentValues row=new ContentValues();row.put("run_id",id);row.put("task_id",task);row.put("epoch",epoch);row.put("status",state);row.put("checkpoint","{\"messages\":[{\"role\":\"user\",\"content\":\"unversioned old checkpoint must not be revived\"}]}");row.put("grant_json","{}");row.put("created_at",epoch);row.put("updated_at",epoch);db.insertOrThrow("runs",null,row);}
        String operation(int index,String tool,String content){String id="history-op-"+String.format(Locale.ROOT,"%05d",index);ContentValues row=new ContentValues();row.put("operation_id",id);row.put("task_id",historical);row.put("run_id",pastRun);row.put("tool",tool);row.put("arguments","{}");row.put("state","completed");row.put("receipt",content);row.put("created_at",index+1);db.insertOrThrow("operations",null,row);return id;}
        JSONObject options(String query)throws Exception{return new JSONObject().put("query",query).put("cursor","").put("limit",20).put("maxChars",16000).put("createdAfter",-1).put("createdBefore",-1);}
        JSONObject query(ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return TaskStoreMemoryQuery.queryConversationHistory(db,binding,selected,kind,options);}
        ConversationHistory.SourceLocator historicalTask()throws Exception{
            JSONObject options=options("");for(int page=0;page<10;page++){JSONObject result=query(null,"runs",options);JSONArray entries=result.getJSONArray("entries");for(int i=0;i<entries.length();i++)if(historical.equals(entries.getJSONObject(i).optString("sourceTaskId")))return ConversationHistory.SourceLocator.from(entries.getJSONObject(i));String cursor=result.optString("nextCursor");if(cursor.isEmpty())break;options.put("cursor",cursor);}throw new AssertionError("historical task not found within fixture pages");
        }
        ConversationHistory.SourceLocator historicalRun()throws Exception{return ConversationHistory.SourceLocator.from(query(historicalTask(),"runs",options("")).getJSONArray("entries").getJSONObject(0));}
        ConversationHistory.Capability capability()throws Exception{return new ConversationHistory.Capability(new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl live){throw new AssertionError("unexpected existing tool");}},binding,new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return TaskStoreMemoryQuery.queryConversationHistory(db,b,selected,kind,options);}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int chars)throws Exception{return TaskStoreMemoryQuery.readConversationHistoryOperation(db,b,selected,offset,chars);}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs)throws Exception{TaskStoreMemoryQuery.requireConversationHistorySources(db,b,refs);}});}
        public void close(){db.close();}
    }
    @Test public void historicalCancelledSourceReadableWithoutLendingItTheActiveCallerControl()throws Exception{
        try(Fixture f=new Fixture()){f.operation(0,"workspace_read","{\"text\":\"为什么失败 HISTORY_CAUSE\"}");ConversationHistory.SourceLocator run=f.historicalRun();JSONObject page=f.query(run,"operations",f.options("HISTORY_CAUSE"));JSONObject row=page.getJSONArray("entries").getJSONObject(0);ConversationHistory.SourceLocator selected=ConversationHistory.SourceLocator.from(row);
            assertEquals(f.historical,selected.taskId);assertEquals(f.pastRun,selected.runId);assertEquals(2,selected.epoch);assertFalse(f.control.matchesHostRun(f.historical,f.pastRun,2));assertFalse(page.toString().contains("unversioned old checkpoint"));
            JSONObject exact=TaskStoreMemoryQuery.readConversationHistoryOperation(f.db,f.binding,selected,0,16000);assertEquals("current",exact.getString("status"));assertTrue(exact.getString("body").contains("HISTORY_CAUSE"));assertFalse(exact.getBoolean("executionAuthorized"));assertFalse(f.control.isCancelled());
        }
    }
    @Test public void callerConversationAndSourceMembershipAreIndependentAndCrossScopeNeverReturned()throws Exception{
        try(Fixture f=new Fixture()){String foreign=UUID.randomUUID().toString();f.saveTask(f.task(foreign,UUID.randomUUID().toString(),1,"failed",false,UUID.randomUUID().toString()),7);String group=UUID.randomUUID().toString();f.saveTask(f.task(group,UUID.randomUUID().toString(),1,"failed",false,f.conversation).put("origin",new JSONObject().put("kind","group")),6);JSONObject page=f.query(null,"runs",f.options(""));assertFalse(page.toString().contains(foreign));assertFalse(page.toString().contains(group));ConversationHistory.SourceLocator source=f.historicalTask();f.saveTask(f.load(f.historical).put("conversationId",UUID.randomUUID().toString()),4);assertThrows(SecurityException.class,()->f.query(source,"runs",f.options("")));f.saveTask(f.load(f.caller).put("conversationId",UUID.randomUUID().toString()),5);assertThrows(SecurityException.class,()->f.query(null,"runs",f.options("")));}
    }
    @Test public void boundedCandidateAndSourcePagesReportGapsAndNeverLoadOversizedBodies()throws Exception{
        try(Fixture f=new Fixture()){for(int i=0;i<103;i++){String id="foreign-candidate-"+i;f.saveTask(f.task(id,UUID.randomUUID().toString(),1,"failed",false,"other-conversation"),1000+i);}JSONObject first=f.query(null,"runs",f.options(""));assertEquals(100,first.getJSONObject("pageCoverage").getInt("scannedRows"));assertEquals(0,first.getJSONArray("entries").length());assertEquals("partial",first.getString("status"));assertFalse(first.getString("nextCursor").isEmpty());assertFalse(first.toString().contains("foreign-candidate"));JSONObject next=f.query(null,"runs",f.options("").put("cursor",first.getString("nextCursor")));assertEquals(2,next.getJSONArray("entries").length());
            String oversized=f.operation(0,"workspace_read","x".repeat(TaskStoreMemoryQuery.RAW_MAX_BYTES+1)),receipt="{\"text\":\"READABLE\"}";
            String readable=f.operation(1,"workspace_read",receipt);JSONObject ops=f.query(f.historicalRun(),"operations",f.options("READABLE"));
            assertEquals(1,ops.getJSONArray("gaps").length());assertEquals(1,ops.getJSONArray("entries").length());
            JSONObject gap=ops.getJSONArray("gaps").getJSONObject(0),coverage=ops.getJSONObject("pageCoverage");
            assertEquals("task:"+f.historical+":operation:"+oversized,gap.getString("sourceId"));assertEquals("read_limited",gap.getString("status"));
            // The native producer now also charges whole-source-run currentness records.
            // Keep the original tiny-body expectation without pretending those reads were free.
            long operationBytes=(readable+"workspace_read"+"{}"+"completed"+receipt+f.pastRun).getBytes(StandardCharsets.UTF_8).length;
            assertTrue(operationBytes<1000);assertTrue(coverage.getLong("operationContextUtf8Bytes")>0);
            assertEquals(operationBytes,coverage.getLong("scannedUtf8Bytes")-coverage.getLong("operationContextUtf8Bytes")-coverage.getLong("metadataUtf8Bytes"));
            assertTrue(coverage.getLong("scannedUtf8Bytes")<=TaskStoreMemoryQuery.RAW_MAX_BYTES);
            assertEquals(2,coverage.getInt("scannedRows"));assertEquals("operation_receipt",coverage.getJSONArray("sourceKinds").getString(0));
        }
    }
    @Test public void exactFingerprintChangeFailsReadAndRetainedReferenceCheckWithoutNewBody()throws Exception{
        try(Fixture f=new Fixture()){String id=f.operation(0,"workspace_read"," {\"text\":\"old-byte-version\"} ");ConversationHistory.SourceLocator selected=ConversationHistory.SourceLocator.from(f.query(f.historicalRun(),"operations",f.options("")).getJSONArray("entries").getJSONObject(0));TaskStoreMemoryQuery.requireConversationHistorySources(f.db,f.binding,List.of(selected));ContentValues changed=new ContentValues();changed.put("receipt","{\"text\":\"new-byte-version\"}");f.db.update("operations",changed,"operation_id=?",new String[]{id});JSONObject stale=TaskStoreMemoryQuery.readConversationHistoryOperation(f.db,f.binding,selected,0,64);assertEquals("source_changed",stale.getString("status"));assertFalse(stale.has("body"));assertThrows(SecurityException.class,()->TaskStoreMemoryQuery.requireConversationHistorySources(f.db,f.binding,List.of(selected)));}
    }
    @Test public void opaqueNativeSelectionsAndCursorsCannotCrossPauseOrAdmitModelTaskIds()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){for(int i=0;i<103;i++){String id="foreign-candidate-"+i;f.saveTask(f.task(id,UUID.randomUUID().toString(),1,"failed",false,"other-conversation"),1000+i);}JSONObject page=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query",""),f.control);String cursor=page.getString("nextCursor");assertEquals(36,cursor.length());assertFalse(cursor.contains("candidate"));assertThrows(IllegalArgumentException.class,()->cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query","").put("taskId",f.historical),f.control));
            f.control.pause();f.control.resume();CapabilitySnapshot fresh=new CapabilitySnapshot(f.caller,f.callerRun,1,f.control,Set.of(ConversationHistory.SEARCH,ConversationHistory.READ),()->{});f.raw.admitRequest(fresh);cap.admitCapabilities(fresh,f.control);assertThrows(SecurityException.class,()->cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query","").put("cursor",cursor),f.control));JSONObject newPage=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query",""),f.control);assertEquals(36,newPage.getString("nextCursor").length());
        }
    }
    @Test public void nativeReceiptCitationRechecksScopeAndDerivedHistoryToolsAreNotHistoricalOperations()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){f.operation(0,ConversationHistory.SEARCH,"{\"text\":\"DERIVED_NOT_ORIGINAL\"}");f.operation(1,"workspace_read","{\"text\":\"NATIVE_CITATION\"}");JSONObject tasks=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query",""),f.control);JSONObject task=null;for(int i=0;i<tasks.getJSONArray("entries").length();i++){JSONObject row=tasks.getJSONArray("entries").getJSONObject(i);if(f.historical.equals(row.optString("sourceTaskId")))task=row;}assertNotNull(task);
            JSONObject runs=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query","").put("selectionId",task.getString("selectionId")),f.control);JSONObject receipt=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","operations").put("query","").put("selectionId",runs.getJSONArray("entries").getJSONObject(0).getString("selectionId")),f.control);assertEquals(1,receipt.getJSONArray("entries").length());assertFalse(receipt.toString().contains("DERIVED_NOT_ORIGINAL"));JSONObject caller=f.load(f.caller).put("events",new JSONArray().put(new JSONObject().put("type","assistant").put("tool",ConversationHistory.SEARCH).put("receipt",receipt)));assertEquals(0,TaskMemoryTrace.historyCitationJumps(caller).length());
            JSONObject event=new JSONObject().put("type","tool").put("tool",ConversationHistory.SEARCH).put("receipt",receipt);caller.put("events",new JSONArray().put(event));
            JSONArray citations=TaskMemoryTrace.historyCitationJumps(caller);assertEquals(1,citations.length());JSONObject citation=citations.getJSONObject(0);assertEquals(f.caller,citation.getString("callerTaskId"));assertEquals(f.historical,citation.getJSONObject("sourceJump").getString("taskId"));
            // A JSON projection cannot authorize a source jump without its original native audit row.
            f.saveTask(caller,5);assertThrows(SecurityException.class,()->TaskStoreMemoryQuery.conversationHistoryCitation(f.db,citation));
            caller.remove("events");f.saveTask(caller,5);f.event(event);
            TaskStoreMemoryQuery.conversationHistoryCitation(f.db,citation);assertThrows(SecurityException.class,()->TaskStoreMemoryQuery.conversationHistoryCitation(f.db,new JSONObject(citation.toString()).put("callerRunId",UUID.randomUUID().toString())));
        }
    }
    @Test public void plannedTaskRunOperationTraversalReadsOriginalSqlReceiptWithoutReplayingHistoricalActions()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            String operation=f.operation(0,"workspace_read","{\"text\":\"The obsolete timer kept the worker waiting.\"}");
            f.operation(1,ConversationHistory.SEARCH,"{\"text\":\"DERIVED_HISTORY_MUST_NOT_REENTER\"}");
            JSONObject receipt=cap.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","runs").put("query","timer")
                    .put("follow","operations").put("maxCalls",8).put("readTop",1),f.control);
            assertEquals(1,receipt.getJSONArray("entries").length());JSONObject row=receipt.getJSONArray("entries").getJSONObject(0);
            assertEquals("task:"+f.historical+":operation:"+operation,row.getString("sourceId"));assertEquals(f.historical,row.getString("sourceTaskId"));assertEquals(f.pastRun,row.getString("sourceRunId"));
            assertTrue(row.getBoolean("exactSourceRead"));assertTrue(row.getString("body").contains("obsolete timer"));assertFalse(row.getBoolean("currentFactVerified"));
            assertFalse(receipt.toString().contains("DERIVED_HISTORY_MUST_NOT_REENTER"));assertEquals(1,receipt.getJSONArray("taskSourceRefs").length());
            assertTrue(receipt.getJSONObject("queryPlan").getInt("producerCalls")<=8);assertFalse(receipt.getBoolean("executionAuthorized"));
            assertTrue(f.control.matchesHostRun(f.caller,f.callerRun,1));assertFalse(f.control.matchesHostRun(f.historical,f.pastRun,2));
            try(Cursor count=f.db.rawQuery("SELECT count(*) FROM operations",null)){count.moveToFirst();assertEquals(2,count.getInt(0));}
            JSONObject exact=cap.execute(ConversationHistory.READ,new JSONObject().put("selectionId",row.getString("selectionId")).put("maxChars",4000),f.control);
            assertEquals(row.getString("revision"),exact.getString("revision"));assertEquals(row.getString("body"),exact.getString("body"));
        }
    }
    @Test public void onlyLocalHistoryReadsHoldWrapperMonitorAndPauseLateReplyIsDiscarded()throws Exception{
        try(Fixture f=new Fixture()){
            final CapabilityPort[] chain=new CapabilityPort[2];CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl live)throws Exception{assertFalse(Thread.holdsLock(chain[0]));assertFalse(Thread.holdsLock(chain[1]));return new JSONObject().put("ok",true);}};
            chain[0]=new TaskStoreMemoryReader.RawCapability(existing,f.raw,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject args){throw new AssertionError();}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject args){throw new AssertionError();}});
            ConversationHistory.Source late=new ConversationHistory.Source(){public ConversationHistory.Gate open(String id){return ()->true;}public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs){return new JSONArray();}public JSONObject read(ConversationHistory.Gate gate,SourceRef ref,int offset,int chars,RunControl original){throw new AssertionError();}public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl original)throws Exception{original.pause();original.resume();f.raw.admitRequest(new CapabilitySnapshot(f.caller,f.callerRun,1,original,Set.of(ConversationHistory.SEARCH,ConversationHistory.READ),()->{}));return new JSONObject().put("status","current").put("entries",new JSONArray()).put("nextCursor","");}};
            ConversationHistory.HistoryBinding scoped=new ConversationHistory.HistoryBinding(f.raw,f.conversation,late);
            chain[1]=new ConversationHistory.Capability(chain[0],scoped,new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator ref,String kind,JSONObject options){throw new AssertionError();}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator ref,int offset,int chars){throw new AssertionError();}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs){}});
            try{assertTrue(chain[1].execute("workspace_read",new JSONObject(),f.control).getBoolean("ok"));assertThrows(SecurityException.class,()->chain[1].execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("query",""),f.control));}finally{chain[1].close();}
        }
    }

}
