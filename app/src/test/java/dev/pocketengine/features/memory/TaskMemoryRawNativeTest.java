package dev.pocketengine.features.memory;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Production bounded producer on real Android SQLite, synthetic records; no model/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskMemoryRawNativeTest {
    private static final class Fixture implements AutoCloseable {
        final SQLiteDatabase db=SQLiteDatabase.create(null);
        final String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        final RunControl control=new RunControl();final AtomicLong revision=new AtomicLong();
        final TaskStoreMemoryReader.RawBinding binding;
        JSONArray messages=new JSONArray();
        Fixture()throws Exception{
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,epoch INTEGER NOT NULL,status TEXT NOT NULL,checkpoint TEXT NOT NULL,grant_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(task_id,epoch))");
            db.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX operations_task ON operations(task_id,state)");
            saveTask(new JSONObject().put("id",task).put("actorId","owner").put("spaceId","private").put("runId",run).put("coordinatorEpoch",1).put("cancelRequested",false).put("status","running"));
            ContentValues row=new ContentValues();row.put("run_id",run);row.put("task_id",task);row.put("epoch",1);row.put("status","running");row.put("checkpoint","{\"messages\":[]}");row.put("grant_json","{}");row.put("created_at",1);row.put("updated_at",1);db.insertOrThrow("runs",null,row);
            OwnerSourceRunContext.initialize(db,task,run,1,"",false);control.bindHostRun(task,run,1);
            binding=new TaskStoreMemoryReader.RawBinding(task,run,1,control,new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}},revision::get);
        }
        void saveTask(JSONObject task)throws Exception{ContentValues row=new ContentValues();row.put("task_id",this.task);row.put("document",task.toString());row.put("updated_at",1);db.insertWithOnConflict("tasks",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
        JSONObject task()throws Exception{try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{task})){row.moveToFirst();return new JSONObject(row.getString(0));}}
        void messages(JSONArray rows)throws Exception{messages=rows;ContentValues value=new ContentValues();value.put("checkpoint",new JSONObject().put("messages",rows).put("steps",2).toString());db.update("runs",value,"run_id=?",new String[]{run});}
        String operation(int index,String tool,String receipt)throws Exception{
            String id=String.format(java.util.Locale.ROOT,"op-%05d",index);ContentValues row=new ContentValues();row.put("operation_id",id);row.put("task_id",task);row.put("run_id",run);row.put("tool",tool);row.put("arguments","{}");row.put("state","completed");row.put("receipt",receipt);row.put("created_at",index+1);db.insertOrThrow("operations",null,row);return id;
        }
        JSONObject query(String section,String query)throws Exception{return query(new JSONObject().put("section",section).put("query",query));}
        JSONObject query(JSONObject options)throws Exception{return TaskMemoryTrace.checkedRawPage(TaskStoreMemoryQuery.queryRaw(db,binding,options),task,run,options);}
        JSONObject citation(JSONObject entry,int offset,int max)throws Exception{return new JSONObject().put("sourceId",entry.getString("sourceId")).put("revision",entry.getString("revision")).put("offset",offset).put("maxChars",max);}
        JSONObject read(JSONObject citation)throws Exception{return TaskMemoryTrace.checkedRawSource(TaskStoreMemoryQuery.readRaw(db,binding,citation),task,run,citation);}
        String stored()throws Exception{JSONObject all=new JSONObject();for(String table:new String[]{"tasks","runs","operations"}){JSONArray rows=new JSONArray();try(Cursor cursor=db.rawQuery("SELECT * FROM "+table+" ORDER BY 1",null)){while(cursor.moveToNext()){JSONArray row=new JSONArray();for(int i=0;i<cursor.getColumnCount();i++)row.put(cursor.isNull(i)?JSONObject.NULL:cursor.getString(i));rows.put(row);}}all.put(table,rows);}return all.toString();}
        public void close(){db.close();}
    }
    @Test public void originalPrivateRunLiteralQueryHasExactCausalityAndNoInventedMessageTime()throws Exception{
        try(Fixture f=new Fixture()){
            f.messages(new JSONArray().put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","actual-call").put("function",new JSONObject().put("name","workspace_read").put("arguments","{}")))))
                    .put(new JSONObject().put("role","tool").put("tool_call_id","actual-call").put("content","中文失败修复 RAW_CURRENT_61")));
            String before=f.stored();JSONObject page=f.query("messages","失败修复");JSONObject entry=page.getJSONArray("entries").getJSONObject(0);
            assertEquals("matched_tool_call",entry.getString("causalState"));assertEquals("actual-call",entry.getJSONObject("parent").getString("toolCallId"));assertEquals("unknown",entry.getString("timeStatus"));assertTrue(entry.isNull("createdAt"));
            assertEquals("task_raw_source",entry.getJSONObject("sourceJump").getString("kind"));assertEquals(f.run,page.getString("runId"));assertFalse(page.has("snapshot"));assertEquals(2,page.getJSONObject("pageCoverage").getInt("scannedRows"));assertEquals(before,f.stored());
        }
    }
    @Test public void sourceRowBudgetPaginatesNoMatchWithoutClaimingAbsenceAndRejectsForgedCursor()throws Exception{
        try(Fixture f=new Fixture()){
            for(int i=0;i<105;i++)f.operation(i,"workspace_read",new JSONObject().put("text",i==104?"TAIL_MARK_71":"unrelated").toString());
            JSONObject options=new JSONObject().put("section","operations").put("query","TAIL_MARK_71");JSONObject page=f.query(options);
            assertEquals("partial",page.getString("status"));assertEquals(100,page.getJSONObject("pageCoverage").getInt("scannedRows"));assertEquals(0,page.getJSONArray("entries").length());assertEquals("source_row_budget",page.getJSONObject("pageCoverage").getString("partialReason"));assertFalse(page.getString("nextCursor").isEmpty());
            JSONObject next=f.query(new JSONObject(options.toString()).put("cursor",page.getString("nextCursor")));assertEquals(1,next.getJSONArray("entries").length());assertEquals(5,next.getJSONObject("pageCoverage").getInt("scannedRows"));
            try{f.query(new JSONObject(options.toString()).put("cursor",page.getString("nextCursor")+"x"));fail("unissued cursor accepted");}catch(SecurityException expected){}
            try{f.query(new JSONObject(options.toString()).put("query","another query").put("cursor",page.getString("nextCursor")));fail("cursor changed query");}catch(SecurityException expected){}
        }
    }
    @Test public void actualMaterializedSourceBytesAreBoundedSeparatelyFromReturnedCharacters()throws Exception{
        try(Fixture f=new Fixture()){
            for(int i=0;i<5;i++)f.operation(i,"workspace_read",new JSONObject().put("text","z".repeat(300_000)).toString());
            JSONObject options=new JSONObject().put("section","operations").put("query","ABSENT_QUERY").put("maxChars",64);JSONObject page=f.query(options);
            assertEquals("source_byte_budget",page.getJSONObject("pageCoverage").getString("partialReason"));assertEquals(3,page.getJSONObject("pageCoverage").getInt("scannedRows"));assertTrue(page.getJSONObject("pageCoverage").getLong("scannedUtf8Bytes")>900_000);assertTrue(page.getJSONObject("pageCoverage").getLong("scannedUtf8Bytes")<=1_048_576);assertEquals(0,page.getInt("charsReturned"));
            assertEquals(2,f.query(new JSONObject(options.toString()).put("cursor",page.getString("nextCursor"))).getJSONObject("pageCoverage").getInt("scannedRows"));
        }
    }
    @Test public void oversizedRowsAreExplicitGapsAndOversizedAuthoritiesAreNeverLoadedAsSuccess()throws Exception{
        try(Fixture f=new Fixture()){
            f.operation(0,"workspace_read","z".repeat(1_048_577));f.operation(1,"workspace_read","{\"text\":\"RECOVERABLE_51\"}");String before=f.stored();JSONObject page=f.query("operations","RECOVERABLE_51");
            assertEquals(1,page.getJSONArray("gaps").length());assertEquals(1,page.getJSONArray("entries").length());assertTrue(page.getJSONObject("pageCoverage").getLong("scannedUtf8Bytes")<1000);assertEquals(before,f.stored());
            f.messages(new JSONArray().put(new JSONObject().put("role","user").put("content","z".repeat(1_048_577))));assertEquals("read_limited",f.query("messages","").getString("status"));
            JSONObject task=f.task().put("oversizedEvent","z".repeat(1_048_577));f.saveTask(task);JSONObject limited=f.query("operations","");assertEquals("read_limited",limited.getString("status"));assertEquals(0,limited.getJSONObject("pageCoverage").getLong("authorityUtf8Bytes"));
        }
    }
    @Test public void exactOperationFingerprintChangesOnStoredBytesAndDoesNotReadUnrelatedLargeRows()throws Exception{
        try(Fixture f=new Fixture()){
            String id=f.operation(0,"workspace_read"," {\"text\":\"EXACT_OP_41\"} ");JSONObject entry=f.query("operations","EXACT_OP_41").getJSONArray("entries").getJSONObject(0),citation=f.citation(entry,0,64);
            f.operation(1,"workspace_read","z".repeat(1_048_577));JSONObject exact=f.read(citation);assertEquals("current",exact.getString("status"));assertEquals(1,exact.getJSONObject("readBudget").getInt("scannedRows"));
            ContentValues changed=new ContentValues();changed.put("receipt","{\"text\":\"EXACT_OP_41\"}");f.db.update("operations",changed,"operation_id=?",new String[]{id});JSONObject stale=f.read(citation);assertEquals("source_changed",stale.getString("status"));assertFalse(stale.has("body"));assertFalse(stale.has("readRange"));
        }
    }
    @Test public void exactRetainedMessageSupportsUnicodeRangesAndPruningNeverSynthesizesABody()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject message=new JSONObject().put("role","user").put("content","原文🙂".repeat(6000));f.messages(new JSONArray().put(message));JSONObject entry=f.query("messages","").getJSONArray("entries").getJSONObject(0);StringBuilder body=new StringBuilder();int offset=0;
            do{JSONObject page=f.read(f.citation(entry,offset,127));String text=page.getString("body");assertFalse(!text.isEmpty()&&Character.isHighSurrogate(text.charAt(text.length()-1)));body.append(text);offset=page.getInt("nextOffset");}while(offset>=0);
            assertEquals(message.toString(2),body.toString());
            f.messages(new JSONArray().put(new JSONObject(message.toString()).put("content","corrected")));assertEquals("source_changed",f.read(f.citation(entry,0,64)).getString("status"));
            f.messages(new JSONArray());JSONObject unavailable=f.read(f.citation(entry,0,64));assertEquals("unavailable",unavailable.getString("status"));assertFalse(unavailable.has("body"));
        }
    }
    @Test public void sourceBarrierEpochCancelPauseAndForeignScopeCannotProduceLateBodies()throws Exception{
        try(Fixture f=new Fixture()){
            f.messages(new JSONArray().put(new JSONObject().put("role","user").put("content","private-only")));JSONObject entry=f.query("messages","").getJSONArray("entries").getJSONObject(0);f.revision.incrementAndGet();
            try{f.read(f.citation(entry,0,64));fail("forgotten source barrier accepted");}catch(SecurityException expected){}
        }
        for(String change:new String[]{"epoch","cancel","pause","group"})try(Fixture f=new Fixture()){
            if(change.equals("epoch"))f.saveTask(f.task().put("coordinatorEpoch",2));if(change.equals("cancel"))f.control.cancel();if(change.equals("pause"))f.control.pause();if(change.equals("group"))f.saveTask(f.task().put("origin",new JSONObject().put("kind","group")));
            try{f.query("messages","");fail(change+" should refuse");}catch(SecurityException|InterruptedException expected){}
        }
    }
    @Test public void datesApplyOnlyToStoredOperationTimeAndDerivedReferenceReceiptsAreExcluded()throws Exception{
        try(Fixture f=new Fixture()){
            f.operation(0,"workspace_read","{\"text\":\"old failure\"}");f.operation(1,"workspace_read","{\"text\":\"actual repair\"}");f.operation(2,"memory_search","{\"text\":\"FORGOTTEN_DERIVED_91\"}");f.operation(3,"memory_raw_read","{\"text\":\"FORGOTTEN_DERIVED_91\"}");
            JSONObject page=f.query(new JSONObject().put("section","operations").put("query","").put("createdAfter",2));assertEquals(1,page.getJSONArray("entries").length());assertEquals(2,page.getJSONArray("entries").getJSONObject(0).getLong("createdAt"));
            assertEquals(0,f.query("operations","FORGOTTEN_DERIVED_91").getJSONArray("entries").length());
            assertEquals("message_time_not_recorded",f.query(new JSONObject().put("section","messages").put("query","").put("createdAfter",1)).getString("reason"));
        }
    }
    @Test public void modelCapabilityRequiresDiscoveryAndForwardsOriginalLifecycleWithoutGrantingExecution()throws Exception{
        try(Fixture f=new Fixture()){
            f.messages(new JSONArray().put(new JSONObject().put("role","user").put("content","RAW_DISCOVERY_18")).put(new JSONObject().put("role","user").put("content","RAW_OTHER_18")));final boolean[] closed={false};
            CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new IllegalArgumentException();}public void close(){closed[0]=true;}};
            var port=new TaskStoreMemoryReader.RawCapability(existing,f.binding,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject input)throws Exception{return TaskStoreMemoryQuery.queryRaw(f.db,b,input);}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject input)throws Exception{return TaskStoreMemoryQuery.readRaw(f.db,b,input);}});
            assertFalse(port.sideEffecting(TaskStoreMemoryReader.RAW_QUERY));assertEquals(2,port.prepareTurn("query",0,0,f.control).getJSONArray("tools").length());
            JSONObject citation=new JSONObject().put("sourceId",TaskMemoryTrace.rawMessageId(f.task,f.run,0)).put("revision",TaskMemoryTrace.rawFingerprint(f.messages.getJSONObject(0)));
            try{port.validate(TaskStoreMemoryReader.RAW_READ,citation);fail("undiscovered source accepted");}catch(SecurityException expected){}
            JSONObject options=new JSONObject().put("section","messages").put("query","RAW_").put("limit",1);JSONObject beforePause=port.execute(TaskStoreMemoryReader.RAW_QUERY,options,f.control);assertEquals("current",port.execute(TaskStoreMemoryReader.RAW_READ,citation,f.control).getString("status"));
            CapabilitySnapshot first=new CapabilitySnapshot(f.task,f.run,1,f.control,java.util.Set.of(TaskStoreMemoryReader.RAW_QUERY,TaskStoreMemoryReader.RAW_READ),()->{});port.admitCapabilities(first,f.control);first.invalidate();
            CapabilitySnapshot second=new CapabilitySnapshot(f.task,f.run,1,f.control,java.util.Set.of(TaskStoreMemoryReader.RAW_QUERY,TaskStoreMemoryReader.RAW_READ),()->{});port.admitCapabilities(second,f.control);assertTrue(port.execute(TaskStoreMemoryReader.RAW_QUERY,options,f.control).getBoolean("ok"));
            f.control.pause();f.control.resume();
            try{port.prepareTurn("query",1,2,f.control);fail("resume was silently acknowledged without native admission");}catch(SecurityException expected){}
            CapabilitySnapshot resumed=new CapabilitySnapshot(f.task,f.run,1,f.control,java.util.Set.of(TaskStoreMemoryReader.RAW_QUERY,TaskStoreMemoryReader.RAW_READ),()->{});port.admitCapabilities(resumed,f.control);
            try{port.execute(TaskStoreMemoryReader.RAW_QUERY,new JSONObject(options.toString()).put("cursor",beforePause.getString("nextCursor")),f.control);fail("old cursor revived after resume");}catch(SecurityException expected){}
            try{port.validate(TaskStoreMemoryReader.RAW_READ,citation);fail("old discovery grant revived after resume");}catch(SecurityException expected){}
            assertTrue(port.execute(TaskStoreMemoryReader.RAW_QUERY,options,f.control).getBoolean("ok"));assertEquals("current",port.execute(TaskStoreMemoryReader.RAW_READ,citation,f.control).getString("status"));
            try{port.validate(TaskStoreMemoryReader.RAW_QUERY,new JSONObject(options.toString()).put("taskId","another"));fail("model selected task");}catch(IllegalArgumentException expected){}
            f.revision.incrementAndGet();assertFalse(port.responseCurrent(f.control));port.close();assertTrue(closed[0]);
        }
    }
    @Test public void newlyAdmittedResumeNeverMakesAnInflightOldReadCurrent()throws Exception{
        try(Fixture f=new Fixture()){
            try{f.binding.admit(()->{
                JSONObject oldBody=new JSONObject().put("body","BEFORE_PAUSE_17");f.control.pause();f.control.resume();
                f.binding.admitRequest(new CapabilitySnapshot(f.task,f.run,1,f.control,java.util.Set.of(TaskStoreMemoryReader.RAW_QUERY,TaskStoreMemoryReader.RAW_READ),()->{}));
                return oldBody;
            });fail("mutable binding revived a paused in-flight body");}catch(SecurityException expected){}
            assertEquals(f.control.pauseRevision(),f.binding.pauseRevision);assertEquals("current",f.query("messages","").getString("status"));
        }
    }
    @Test public void expiredReadWindowReturnsPartialCursorAndNoUnfinishedSourceBody()throws Exception{
        try(Fixture f=new Fixture()){
            final int[] checks={0};
            var binding=new TaskStoreMemoryReader.RawBinding(f.task,f.run,1,f.control,new MemoryHostFence(){
                public void check()throws Exception{if(++checks[0]==3)Thread.sleep(550);}
                public JSONObject admit(Admission action)throws Exception{return action.run();}
            },f.revision::get);
            JSONObject result=TaskStoreMemoryQuery.queryRaw(f.db,binding,new JSONObject().put("section","messages").put("query",""));
            assertEquals("partial",result.getString("status"));assertEquals("read_deadline",result.getJSONObject("pageCoverage").getString("partialReason"));assertEquals(0,result.getJSONArray("entries").length());assertFalse(result.getString("nextCursor").isEmpty());assertTrue(result.getJSONObject("pageCoverage").getLong("elapsedMillis")>=500);
            // Robolectric's instrumented ProtectionDomain has no CodeSource location; use
            // the same sandbox loader's original .class resource, not a guessed classpath entry.
            java.net.URL origin=TaskMemoryTrace.class.getResource("TaskMemoryTrace.class"),fixture=TaskMemoryRawNativeTest.class.getResource("TaskMemoryRawNativeTest.class");System.out.println("Fresh raw trace resource: "+origin);assertNotNull(origin);assertNotNull(fixture);assertEquals("Product and fixture must come from the same fresh class root",fixture.toString().substring(0,fixture.toString().lastIndexOf('/')),origin.toString().substring(0,origin.toString().lastIndexOf('/')));
        }
    }
    @Test public void forgettingRetiresRawDerivedCheckpointBodiesButKeepsOwnerInputAndActualEffects()throws Exception{
        JSONArray rows=new JSONArray().put(new JSONObject().put("role","user").put("content","OWNER_ORIGINAL_65"));
        JSONArray calls=new JSONArray().put(new JSONObject().put("id","raw").put("function",new JSONObject().put("name","memory_raw_read")))
                .put(new JSONObject().put("id","effect").put("function",new JSONObject().put("name","workspace_write")));
        rows.put(new JSONObject().put("role","assistant").put("tool_calls",calls).put("content","DERIVED_65"))
                .put(new JSONObject().put("role","tool").put("tool_call_id","raw").put("content","DERIVED_65"))
                .put(new JSONObject().put("role","tool").put("tool_call_id","effect").put("content","ACTUAL_EFFECT_65"));
        TaskMemoryHistory.retire(rows,null,true);assertEquals("OWNER_ORIGINAL_65",rows.getJSONObject(0).getString("content"));assertFalse(rows.getJSONObject(2).getString("content").contains("DERIVED_65"));assertEquals("ACTUAL_EFFECT_65",rows.getJSONObject(3).getString("content"));
    }
}
