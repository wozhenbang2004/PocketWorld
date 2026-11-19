package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local delivery acknowledgements for the original owner draft, never runnable or transferable authority. */
final class DraftPhoneReceipts {
    private static final String TABLE="owner_blank_phone_starts";
    interface Lookup { JSONObject read(String id)throws Exception; }
    private DraftPhoneReceipts(){}
    static String scope(String key)throws Exception {
        if(key==null||!key.startsWith("draft:")||key.length()>226||key.length()==6||key.chars().anyMatch(ch->ch<32))
            throw new SecurityException("请回到发起手机工作的原新对话草稿");
        return hash(key);
    }
    private static String hash(String value)throws Exception{
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static String id(String value){if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("原手机请求身份无效");return value;}
    private static void ensure(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS "+TABLE+"(request_id TEXT PRIMARY KEY,scope_sha TEXT NOT NULL,goal_sha TEXT NOT NULL,mode TEXT NOT NULL,created_ms INTEGER NOT NULL,state TEXT NOT NULL,first_run TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS owner_blank_phone_scope ON "+TABLE+"(scope_sha,state,created_ms DESC)");
    }
    private static boolean exists(SQLiteDatabase db){
        try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{TABLE})){return c.moveToFirst();}
    }
    // Called exclusively inside TaskStore's original creation transaction. A retired request can never be reused.
    static void created(SQLiteDatabase db,String request,String scopeKey,JSONObject task)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("原手机登记缺少事务");
        if(!direct(task,request))throw new SecurityException("只登记当前私人新草稿产生的原手机任务");
        String scope=scope(scopeKey);ensure(db);
        ContentValues record=new ContentValues();record.put("request_id",id(request));record.put("scope_sha",scope);record.put("goal_sha",hash(task.getString("goal")));record.put("mode",task.getString("executionMode"));record.put("created_ms",task.getLong("createdAt"));record.put("state","created");
        db.insertOrThrow(TABLE,null,record);
    }
    // Only after the first, explicitly reviewed start obtained its own host receipt. A later "continue" is never substituted.
    static void admitted(SQLiteDatabase db,String scopeKey,JSONObject ack)throws Exception{
        String task=id(ack.getString("taskId")),run=id(ack.getString("runId"));
        if(ack.getInt("epoch")!=1)throw new SecurityException("这不是新草稿的原首次准入");
        try(Cursor c=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=1",new String[]{task,run})){
            if(!c.moveToFirst())throw new SecurityException("原首次准入尚不可核对");
        }
        ContentValues receipt=new ContentValues();receipt.put("first_run",run);
        if(db.update(TABLE,receipt,"request_id=? AND scope_sha=? AND state='created' AND (first_run IS NULL OR first_run=?)",new String[]{task,scope(scopeKey),run})!=1)
            throw new SecurityException("原草稿的首次启动记录已变化");
    }
    private static boolean direct(JSONObject t,String request){
        return t!=null&&request.equals(t.optString("id"))&&"phone".equals(t.optString("kind"))&&"owner".equals(t.optString("actorId"))&&"private".equals(t.optString("spaceId"))&&!t.has("origin")&&!t.has("parentTaskId")&&!t.has("conversationPhoneHandoff")&&!t.has("conversationId");
    }
    static JSONObject query(SQLiteDatabase db,Lookup lookup,String scopeKey,String request,boolean brief)throws Exception{
        String scope=scope(scopeKey);id(request);
        JSONObject out=new JSONObject().put("draftRequestId",request).put("accepted",false).put("status","not_found").put("queryOnly",true);
        if(!exists(db))return out;
        try(Cursor c=db.rawQuery("SELECT scope_sha,goal_sha,mode,created_ms,state,first_run FROM "+TABLE+" WHERE request_id=?",new String[]{request})){
            if(!c.moveToFirst())return out;
            if(!scope.equals(c.getString(0)))throw new SecurityException("原手机请求不属于当前新对话草稿");
            if("retired".equals(c.getString(4)))return out.put("status","retired");
            JSONObject t=lookup.read(request);
            if(!"created".equals(c.getString(4))||!direct(t,request)||t.optLong("createdAt")!=c.getLong(3)||!hash(t.optString("goal")).equals(c.getString(1)))
                return out.put("status","unavailable");
            out.put("accepted",true).put("taskId",request).put("status","saved").put("createdAt",c.getLong(3)).put("mode",c.getString(2));
            if(brief){String g=t.getString("goal");out.put("goal",g.substring(0,g.offsetByCodePoints(0,Math.min(120,g.codePointCount(0,g.length())))));}
            if(!c.isNull(5))try(Cursor run=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=1",new String[]{request,c.getString(5)})){
                if(run.moveToFirst())return out.put("admitted",true).put("admissionState","admitted").put("runId",c.getString(5)).put("epoch",1).put("status",run.getString(0));
            }
            try(Cursor any=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? LIMIT 1",new String[]{request})){
                if(c.isNull(5)&&!any.moveToFirst()&&t.optString("runId").isEmpty()&&t.optInt("coordinatorEpoch")==0)
                    return out.put("admitted",false).put("admissionState","no_run");
            }
            return out.put("admissionState","unconfirmed");
        }
    }
    /** Explicit discard of an undelivered request number. Does not stop a task that already exists. */
    static JSONObject retireUncreated(SQLiteDatabase db,Lookup lookup,String scopeKey,String request)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("原手机回执缺少事务");
        JSONObject existing=query(db,lookup,scopeKey,request,false);if(!"not_found".equals(existing.getString("status")))return existing;
        if(lookup.read(request)!=null)throw new SecurityException("已有原记录，不能把它标记为未交付");
        ensure(db);ContentValues row=new ContentValues();row.put("request_id",id(request));row.put("scope_sha",scope(scopeKey));row.put("goal_sha","");row.put("mode","");row.put("created_ms",System.currentTimeMillis());row.put("state","retired");db.insertOrThrow(TABLE,null,row);
        return new JSONObject().put("draftRequestId",request).put("status","retired").put("accepted",false);
    }
    static JSONArray recent(SQLiteDatabase db,Lookup lookup,String scopeKey,boolean details)throws Exception{
        String scope=scope(scopeKey);JSONArray rows=new JSONArray();if(!exists(db))return rows;
        try(Cursor c=db.rawQuery("SELECT s.request_id FROM "+TABLE+" s JOIN tasks t ON t.task_id=s.request_id WHERE s.scope_sha=? AND s.state='created' ORDER BY s.created_ms DESC,s.request_id LIMIT 5",new String[]{scope})){
            while(c.moveToNext()){String request=c.getString(0);if(!details){rows.put(request);continue;}JSONObject row=query(db,lookup,scopeKey,request,true);if(row.optBoolean("accepted"))rows.put(row);}
        }return rows;
    }
}
