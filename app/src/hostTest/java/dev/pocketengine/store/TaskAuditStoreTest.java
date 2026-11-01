package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Native SQLite; deliberately large audit fixtures are correctness cases, not a device/OOM benchmark. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskAuditStoreTest {
    private SQLiteDatabase db;
    @Before public void open(){db=SQLiteDatabase.create(null);db.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");db.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");TaskAuditStore.createSchema(db);}
    @After public void close(){db.close();}
    private static String id(){return UUID.randomUUID().toString();}
    private JSONObject task(String id,JSONArray events)throws Exception{return new JSONObject().put("id",id).put("goal","原目标🧭").put("status","paused").put("actorId","owner").put("spaceId","private").put("updatedAt",42).put("events",events);}
    private void old(String id,String document){ContentValues row=new ContentValues();row.put("task_id",id);row.put("document",document);row.put("updated_at",42);db.insertOrThrow("tasks",null,row);}
    private long scalar(String sql,String... args){try(Cursor row=db.rawQuery(sql,args)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private JSONObject first(JSONObject page)throws Exception{return page.getJSONArray("entries").getJSONObject(0);}
    @Test public void streamsLargeLegacyDocumentPreservingEveryBodyAndOriginalSeq()throws Exception{
        String task=id(),text="原文🧭".repeat(1800);JSONArray events=new JSONArray();for(int i=0;i<220;i++)events.put(new JSONObject().put("type","tool").put("seq",i+1).put("at",i).put("message",text+":"+i));String original=task(task,events).toString();assertTrue(original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2_000_000);old(task,original);
        TaskAuditStore.migrateLegacy(db);assertEquals(220,scalar("SELECT COUNT(*) FROM task_events WHERE task_id=?",task));assertTrue(scalar("SELECT length(CAST(document AS BLOB)) FROM tasks WHERE task_id=?",task)<1024);
        JSONArray recovered=TaskAuditStore.events(db,task,null);assertEquals(220,recovered.length());for(int i=0;i<220;i++){assertEquals(i+1,recovered.getJSONObject(i).getInt("seq"));assertEquals(text+":"+i,recovered.getJSONObject(i).getString("message"));}
        assertEquals(220,TaskAuditStore.lastSequence(db,task));assertEquals(1,scalar("SELECT audit_format FROM tasks WHERE task_id=?",task));assertEquals(0,scalar("SELECT COUNT(*) FROM tasks WHERE audit_stage IS NOT NULL"));
    }
    @Test public void migrationDoesNotInventNativeSequenceForOldRows()throws Exception{
        String task=id();JSONArray events=new JSONArray().put(new JSONObject().put("type","old").put("message","版本和时间未知")).put(new JSONObject().put("type","user_verification").put("seq",500).put("completed",true));old(task,task(task,events).toString());TaskAuditStore.migrateLegacy(db);
        assertFalse(TaskAuditStore.events(db,task,null).getJSONObject(0).has("seq"));assertEquals(0,scalar("SELECT COUNT(native_seq) FROM task_events WHERE task_id=? AND event_order=1",task));assertEquals(500,TaskAuditStore.lastSequence(db,task));JSONObject next=new JSONObject().put("type","new");assertEquals(501,TaskAuditStore.append(db,task,next));assertEquals(501,next.getLong("seq"));
    }
    @Test public void failedMigrationRollsBackOriginalDocumentAndCanResumeWithoutDuplicating()throws Exception{
        String task=id();String valid=task(task,new JSONArray().put(new JSONObject().put("type","tool").put("seq",7).put("message","原内容"))).toString();String broken=valid.substring(0,valid.length()-1);old(task,broken);
        assertThrows(Exception.class,()->TaskAuditStore.migrateLegacy(db));assertEquals(0,scalar("SELECT COUNT(*) FROM task_events"));assertEquals(0,scalar("SELECT audit_format FROM tasks WHERE task_id=?",task));try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{task})){row.moveToFirst();assertEquals(broken,row.getString(0));}
        ContentValues repaired=new ContentValues();repaired.put("document",valid);db.update("tasks",repaired,"task_id=?",new String[]{task});TaskAuditStore.migrateLegacy(db);TaskAuditStore.migrateLegacy(db);assertEquals(1,scalar("SELECT COUNT(*) FROM task_events"));assertEquals(7,TaskAuditStore.events(db,task,null).getJSONObject(0).getLong("seq"));
    }
    @Test public void eventCursorIsTaskBoundAndTailAppendDoesNotChangeItsFrozenUpper()throws Exception{
        String task=id(),other=id();old(task,task(task,new JSONArray()).toString());old(other,task(other,new JSONArray()).toString());TaskAuditStore.migrateLegacy(db);for(int i=0;i<9;i++)TaskAuditStore.append(db,task,new JSONObject().put("type","step").put("message","m"+i));
        JSONObject first=TaskAuditStore.eventPage(db,task,"",3,4096);String cursor=first.getString("nextCursor");assertFalse(cursor.isEmpty());TaskAuditStore.append(db,task,new JSONObject().put("type","new_tail"));assertThrows(IllegalArgumentException.class,()->TaskAuditStore.eventPage(db,other,cursor,3,4096));
        Set<Long> seen=new HashSet<>();JSONObject page=first;do{for(int i=0;i<page.getJSONArray("entries").length();i++)assertTrue(seen.add(page.getJSONArray("entries").getJSONObject(i).getLong("position")));String next=page.getString("nextCursor");if(next.isEmpty())break;page=TaskAuditStore.eventPage(db,task,next,3,4096);}while(true);assertEquals(9,seen.size());assertFalse(seen.contains(10L));assertEquals(10,first(TaskAuditStore.eventPage(db,task,"",3,4096)).getLong("position"));
    }
    @Test public void oversizedEventRetainsExactAuditButReturnsReadLimitedMetadata()throws Exception{
        String task=id(),body="原文".repeat(30000);old(task,task(task,new JSONArray()).toString());TaskAuditStore.migrateLegacy(db);TaskAuditStore.append(db,task,new JSONObject().put("type","large").put("message",body));JSONObject page=TaskAuditStore.eventPage(db,task,"",4,4096),entry=first(page);assertEquals("read_limited",entry.getString("status"));assertFalse(entry.has("event"));assertTrue(page.getBoolean("readLimited"));assertEquals(0,page.getLong("bodyBytesRead"));assertEquals(body,TaskAuditStore.events(db,task,null).getJSONObject(0).getString("message"));
    }
    @Test public void operationPagePreflightsBodiesWithoutLosingUnknownRows()throws Exception{
        String task=id(),operation=id();old(task,task(task,new JSONArray()).toString());TaskAuditStore.migrateLegacy(db);ContentValues row=new ContentValues();row.put("operation_id",operation);row.put("task_id",task);row.put("run_id",id());row.put("tool","send_message");row.put("arguments",new JSONObject().put("text","x".repeat(90000)).toString());row.put("state","unknown");row.put("created_at",42);db.insertOrThrow("operations",null,row);
        JSONObject page=TaskAuditStore.operationPage(db,task,"",4,4096),entry=first(page);assertEquals(operation,entry.getString("operationId"));assertEquals("unknown",entry.getString("state"));assertEquals("read_limited",entry.getString("status"));assertFalse(entry.has("arguments"));assertEquals(1,scalar("SELECT COUNT(*) FROM operations WHERE task_id=? AND state='unknown'",task));assertFalse(page.getBoolean("executionAuthorized"));
    }
    @Test public void replacingNativeReceiptPreservesPositionSequenceAndOneCall()throws Exception{
        String task=id(),call=id();old(task,task(task,new JSONArray()).toString());TaskAuditStore.migrateLegacy(db);long seq=TaskAuditStore.append(db,task,new JSONObject().put("type","inference_receipt").put("callId",call).put("outcome","STARTED"));JSONObject original=TaskAuditStore.call(db,task,call);original.put("outcome","COMPLETED");TaskAuditStore.replace(db,task,seq,original);assertEquals(seq,TaskAuditStore.call(db,task,call).getLong("seq"));assertEquals(1,scalar("SELECT COUNT(*) FROM task_events WHERE task_id=?",task));assertEquals(2,first(TaskAuditStore.eventPage(db,task,"",1,4096)).getInt("rowRevision"));
    }
    @Test public void archivedConfirmationSearchAndForgetNeverUseARecentWindow()throws Exception{
        String task=id();JSONObject confirmation=new JSONObject().put("type","user_verification").put("seq",55).put("completed",true).put("feedback","古老反馈").put("memorySourceSnapshot",new JSONObject().put("goal","当时目标").put("summary","当时结果").put("version",1));old(task,task(task,new JSONArray().put(confirmation)).toString());TaskAuditStore.migrateLegacy(db);for(int i=0;i<50;i++)TaskAuditStore.append(db,task,new JSONObject().put("type","later"));
        JSONObject core;try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{task})){row.moveToFirst();core=new JSONObject(row.getString(0));}assertEquals(0,core.getJSONArray("events").length());JSONObject projected=TaskAuditStore.searchProjection(db,core);assertEquals("古老反馈",projected.getJSONArray("events").getJSONObject(0).getString("feedback"));assertEquals(1,TaskAuditStore.retireSnapshots(db,task,55,true));JSONObject actual=TaskAuditStore.latest(db,task,"user_verification");assertFalse(actual.has("memorySourceSnapshot"));assertTrue(actual.getBoolean("memorySourceSnapshotForgotten"));assertEquals("古老反馈",actual.getString("feedback"));
    }
    @Test public void summaryPagesContainNoAuditBodiesAndHaveStableTieBreakKeys()throws Exception{
        for(int i=0;i<45;i++){String task=id();old(task,task(task,new JSONArray().put(new JSONObject().put("type","secret_audit_marker").put("message","DO_NOT_TRANSFER_FULL_HISTORY"))).toString());}TaskAuditStore.migrateLegacy(db);JSONObject page=TaskAuditStore.summaryPage(db,"",40);assertTrue(page.getBoolean("hasMore"));assertEquals(40,page.getJSONArray("tasks").length());assertFalse(page.toString().contains("DO_NOT_TRANSFER_FULL_HISTORY"));assertTrue(page.getLong("headerBytesRead")<=TaskAuditStore.MAX_PAGE_BYTES);JSONObject rest=TaskAuditStore.summaryPage(db,page.getString("nextCursor"),40);assertEquals(5,rest.getJSONArray("tasks").length());assertFalse(rest.getBoolean("hasMore"));assertEquals(45,scalar("SELECT COUNT(*) FROM task_events"));
    }
    @Test public void supplementaryUnicodeAcrossSqlChunksIsNotSplitOrReplaced()throws Exception{
        String task=id(),text="abc🧭汉字".repeat(1900);old(task,task(task,new JSONArray().put(new JSONObject().put("type","unicode").put("message",text))).toString());TaskAuditStore.migrateLegacy(db);assertEquals(text,TaskAuditStore.events(db,task,null).getJSONObject(0).getString("message"));
    }
    @Test public void nativeCompleteAuditRetainsOneLargeRowThatUiPreflightsOut()throws Exception{
        String task=id(),body="正文".repeat(220000);old(task,task(task,new JSONArray().put(new JSONObject().put("type","large_legacy").put("message",body))).toString());TaskAuditStore.migrateLegacy(db);assertEquals(body,TaskAuditStore.events(db,task,null).getJSONObject(0).getString("message"));JSONObject page=TaskAuditStore.eventPage(db,task,"",2,4096);assertEquals("read_limited",first(page).getString("status"));assertEquals(0,page.getLong("bodyBytesRead"));
    }
    @Test public void migratedLearningAndConsolidationHeadersKeepTypedMarkersWithoutInputBodies()throws Exception{
        String task=id();JSONObject old=task(task,new JSONArray().put(new JSONObject().put("type","memory_owner_consolidation").put("seq",8)));JSONArray inputs=new JSONArray();for(int i=0;i<20;i++)inputs.put(new JSONObject().put("title","不要放入任务摘要"+i).put("body","SOURCE_BODY_MUST_STAY_OUT_OF_HEADER".repeat(80)));old.put("sourceLearningJob",new JSONObject().put("format","pocketworld.source-learning-job.v1").put("revision",4).put("nextIndex",3).put("reservedModelAttempts",3).put("inputs",inputs));old(task,old.toString());TaskAuditStore.migrateLegacy(db);JSONObject header=TaskAuditStore.summaryPage(db,"",40).getJSONArray("tasks").getJSONObject(0);assertEquals("source_learning",header.getString("nativeTaskKind"));assertTrue(header.getJSONObject("nativeEventTypeFlags").getBoolean("memory_owner_consolidation"));assertEquals(3,header.getJSONObject("sourceLearningJob").getInt("nextIndex"));assertFalse(header.toString().contains("SOURCE_BODY_MUST_STAY_OUT_OF_HEADER"));assertFalse(header.getJSONObject("sourceLearningJob").has("inputs"));assertTrue(header.getJSONObject("sourceLearningJob").getBoolean("headerOnly"));
    }

}
