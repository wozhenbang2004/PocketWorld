package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Original TaskStore event storage and bounded owner projections, on the original SQLite connection.
 * Pages are views, never an authorization, execution receipt, source revision, or replay instruction. */
public final class TaskAuditStore {
    public static final int MAX_PAGE_ROWS=40, MAX_PAGE_BYTES=131072, MAX_CORE_BYTES=262144;
    private static final int MAX_HEADER_BYTES=32768, MAX_NATIVE_EVENT_BYTES=1048576;
    private static final Set<String> HEADER_FIELDS=Set.of("id","goal","kind","executionMode","actorId","spaceId","status","summary","feature","command","conversationId","runId","coordinatorEpoch","createdAt","updatedAt","cancelRequested","groupStateRevision","channelStateRevision","voiceStateRevision","origin","developmentContext","sshSelection","teamAssignment","parentTaskId","parentRunId","nativeExecutionMode","nativeInference","ownerNotification","resourceState");
    private TaskAuditStore(){}

    public static void createSchema(SQLiteDatabase db){
        addColumn(db,"audit_format","INTEGER NOT NULL DEFAULT 0");
        addColumn(db,"ui_header","TEXT");addColumn(db,"audit_stage","TEXT");
        db.execSQL("CREATE TABLE IF NOT EXISTS task_events(task_id TEXT NOT NULL,event_order INTEGER NOT NULL,event_type TEXT NOT NULL,native_seq INTEGER,completed INTEGER,call_id TEXT,event_json TEXT NOT NULL,body_bytes INTEGER NOT NULL DEFAULT 0,row_revision INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(task_id,event_order))");
        db.execSQL("CREATE INDEX IF NOT EXISTS task_events_type ON task_events(task_id,event_type,event_order)");
        db.execSQL("CREATE INDEX IF NOT EXISTS task_events_call ON task_events(task_id,call_id)");
    }
    private static void addColumn(SQLiteDatabase db,String name,String type){
        try(Cursor rows=db.rawQuery("PRAGMA table_info(tasks)",null)){while(rows.moveToNext())if(name.equals(rows.getString(1)))return;}
        db.execSQL("ALTER TABLE tasks ADD COLUMN "+name+" "+type);
    }
    /** Stream old documents by SQLite Unicode character ranges; never load the whole old audit row.
     * SQLiteOpenHelper's upgrade transaction retains the original document on any failure. */
    public static void migrateLegacy(SQLiteDatabase db)throws Exception{
        try(Cursor ids=db.rawQuery("SELECT task_id FROM tasks WHERE audit_format=0 ORDER BY task_id",null)){
            while(ids.moveToNext())migrateOne(db,ids.getString(0));
        }
    }
    private static void migrateOne(SQLiteDatabase db,String task)throws Exception{
        db.beginTransaction();try{
            ContentValues stage=new ContentValues();stage.put("audit_stage","");db.update("tasks",stage,"task_id=? AND audit_format=0",new String[]{task});
            JSONObject headerFields=new JSONObject();SqlWriter sink=new SqlWriter(db,task,0,true);
            try(JsonReader in=new JsonReader(new SqlDocumentReader(db,task));JsonWriter out=new JsonWriter(sink)){
                in.beginObject();out.beginObject();long order=0;
                while(in.hasNext()){
                    String key=in.nextName();out.name(key);
                    if("events".equals(key)&&in.peek()==JsonToken.BEGIN_ARRAY){
                        in.beginArray();out.beginArray();out.endArray();
                        while(in.hasNext()){
                            if(order==Long.MAX_VALUE)throw new IllegalStateException("原事件序号已耗尽");
                            long position=++order;ContentValues row=new ContentValues();row.put("task_id",task);row.put("event_order",position);row.put("event_type","");row.put("event_json","");db.insertOrThrow("task_events",null,row);
                            SqlWriter eventSink=new SqlWriter(db,task,position,false);SmallWriter metadata=new SmallWriter(8192);
                            try(JsonWriter event=new JsonWriter(eventSink);JsonWriter capture=new JsonWriter(metadata)){event.setLenient(true);capture.setLenient(true);copyValue(in,event,capture,0);}
                            JSONObject body=null;if(!metadata.limited)try{body=new JSONObject(metadata.toString());}catch(Exception oldNonObject){/* Retained exactly as JSON; not promoted into native evidence. */}
                            updateMetadata(db,task,position,body,eventSink.bytes,false);
                        }
                        in.endArray();
                    }else if("sourceLearningJob".equals(key)&&in.peek()==JsonToken.BEGIN_OBJECT){
                        JSONObject small=new JSONObject();in.beginObject();out.beginObject();
                        while(in.hasNext()){String field=in.nextName();out.name(field);boolean wanted=Set.of("format","revision","nextIndex","reservedModelAttempts").contains(field);SmallWriter captured=wanted?new SmallWriter(512):null;JsonWriter second=captured==null?null:new JsonWriter(captured);if(second!=null)second.setLenient(true);copyValue(in,out,second,0);if(second!=null){second.close();if(!captured.limited)small.put(field,new JSONTokener(captured.toString()).nextValue());}}
                        in.endObject();out.endObject();headerFields.put("sourceLearningJob",small);
                    }else{
                        SmallWriter captured=HEADER_FIELDS.contains(key)?new SmallWriter(8192):null;
                        JsonWriter second=captured==null?null:new JsonWriter(captured);if(second!=null)second.setLenient(true);copyValue(in,out,second,0);
                        if(second!=null){second.close();if(!captured.limited)headerFields.put(key,new JSONTokener(captured.toString()).nextValue());}
                    }
                }
                in.endObject();out.endObject();if(in.peek()!=JsonToken.END_DOCUMENT)throw new IllegalStateException("旧任务文档含多余正文，未切换原权威");
            }
            // Metadata for unusually large events is read by streaming only their three native fields.
            try(Cursor missing=db.rawQuery("SELECT event_order FROM task_events WHERE task_id=? AND event_type=''",new String[]{task})){
                while(missing.moveToNext())migrateEventMetadata(db,task,missing.getLong(0));
            }
            if(!task.equals(headerFields.optString("id")))throw new IllegalStateException("原任务SQL身份与文档不符，未迁移成猜测身份");JSONObject header=header(db,headerFields);ContentValues finish=new ContentValues();finish.put("ui_header",header.toString());finish.put("audit_format",1);
            db.execSQL("UPDATE tasks SET document=audit_stage,audit_stage=NULL WHERE task_id=? AND audit_format=0",new Object[]{task});
            if(db.update("tasks",finish,"task_id=?",new String[]{task})!=1)throw new IllegalStateException("原任务迁移未保存");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static void migrateEventMetadata(SQLiteDatabase db,String task,long position)throws Exception{
        JSONObject meta=new JSONObject();try(JsonReader in=new JsonReader(new SqlEventReader(db,task,position))){
            if(in.peek()!=JsonToken.BEGIN_OBJECT)return;in.beginObject();while(in.hasNext()){
                String key=in.nextName();JsonToken token=in.peek();
                if((key.equals("type")||key.equals("callId"))&&token==JsonToken.STRING){String value=in.nextString();if(value.length()<=240)meta.put(key,value);}
                else if(key.equals("seq")&&token==JsonToken.NUMBER){String value=in.nextString();try{meta.put(key,new BigDecimal(value).longValueExact());}catch(ArithmeticException ignored){}}
                else if(key.equals("completed")&&token==JsonToken.BOOLEAN)meta.put(key,in.nextBoolean());else in.skipValue();
            }in.endObject();
        }updateMetadata(db,task,position,meta,-1,false);
    }
    private static void copyValue(JsonReader in,JsonWriter out,JsonWriter capture,int depth)throws Exception{
        if(depth>100)throw new IllegalStateException("旧任务嵌套过深；原始数据库未删除");
        switch(in.peek()){
            case BEGIN_OBJECT:
                in.beginObject();out.beginObject();if(capture!=null)capture.beginObject();
                while(in.hasNext()){String key=in.nextName();out.name(key);if(capture!=null)capture.name(key);copyValue(in,out,capture,depth+1);}in.endObject();out.endObject();if(capture!=null)capture.endObject();break;
            case BEGIN_ARRAY:
                in.beginArray();out.beginArray();if(capture!=null)capture.beginArray();while(in.hasNext())copyValue(in,out,capture,depth+1);in.endArray();out.endArray();if(capture!=null)capture.endArray();break;
            case STRING:{String value=in.nextString();out.value(value);if(capture!=null)capture.value(value);break;}
            case NUMBER:{BigDecimal value=new BigDecimal(in.nextString());out.value(value);if(capture!=null)capture.value(value);break;}
            case BOOLEAN:{boolean value=in.nextBoolean();out.value(value);if(capture!=null)capture.value(value);break;}
            case NULL:in.nextNull();out.nullValue();if(capture!=null)capture.nullValue();break;
            default:throw new IllegalStateException("原任务JSON不可读，未用摘要替代");
        }
    }
    /** Called inside the original TaskStore write transaction. Existing event rows are never replaced
     * by a UI snapshot; only first-time native legacy import may introduce its retained old array. */
    public static String coreAndImport(SQLiteDatabase db,JSONObject task)throws Exception{
        String id=task.getString("id");boolean exists;
        try(Cursor row=db.rawQuery("SELECT audit_format FROM tasks WHERE task_id=?",new String[]{id})){exists=row.moveToFirst();if(exists&&row.getInt(0)!=1)throw new IllegalStateException("原任务事件尚未完成迁移");}
        JSONArray events=task.optJSONArray("events");
        if(!exists&&events!=null&&events.length()>0&&lastSequence(db,id)>0)throw new IllegalStateException("旧task事件身份已有退休序号，不能恢复成新的活动来源");
        if(!exists&&events!=null)for(int i=0;i<events.length();i++){
            Object value=events.get(i);String raw=value.toString();long order=i+1L;ContentValues row=new ContentValues();row.put("task_id",id);row.put("event_order",order);row.put("event_type","");row.put("event_json",raw);db.insertOrThrow("task_events",null,row);updateMetadata(db,id,order,value instanceof JSONObject?(JSONObject)value:null,utf8(raw),false);
        }
        JSONObject core=new JSONObject();for(java.util.Iterator<String> keys=task.keys();keys.hasNext();){String key=keys.next();if(!key.equals("events"))core.put(key,task.get(key));}core.put("events",new JSONArray());return core.toString();
    }
    public static JSONObject header(JSONObject task)throws Exception{
        JSONObject out=new JSONObject();for(String key:HEADER_FIELDS){Object value=task.opt(key);if(value==null)continue;
            if(value instanceof String){String text=(String)value;int max=key.equals("goal")?2000:key.equals("summary")?1000:500;out.put(key,clip(text,max));}
            else if(value instanceof Boolean||value instanceof Number||value==JSONObject.NULL)out.put(key,value);
            else if(value instanceof JSONObject&&jsonBytes(value,8192,0)>=0)out.put(key,new JSONObject(value.toString()));
        }
        JSONObject learning=task.optJSONObject("sourceLearningJob");if(learning!=null){JSONObject small=new JSONObject().put("headerOnly",true).put("inputsIncluded",false).put("executionAuthorized",false);for(String field:new String[]{"format","revision","nextIndex","reservedModelAttempts"}){Object value=learning.opt(field);if(value instanceof Number||value instanceof Boolean||value instanceof String&&((String)value).length()<=160)small.put(field,value);}out.put("sourceLearningJob",small);}
        JSONObject phoneLink=conversationPhoneLink(task);if(phoneLink!=null)out.put("conversationPhoneLink",phoneLink);
        out.put("taskProjection","summary").put("auditBodyIncluded",false).put("events",new JSONArray());
        if(utf8(out.toString())>MAX_HEADER_BYTES-2048){for(String key:new String[]{"origin","developmentContext","sshSelection","teamAssignment"})out.remove(key);out.put("metadataLimited",true);}
        return out;
    }
    /** Navigation only: retain the verified identity without copying the review/goal or granting a run.
     * An unavailable association omits this optional view; it never hides or rewrites the original task. */
    static JSONObject conversationPhoneLink(JSONObject task){
        if(!task.has("conversationPhoneHandoff"))return null;
        try{
            JSONObject link=ConversationPhoneHandoff.association(task);String conversation=link.getString("conversationId");
            ConversationPhoneHandoff.nativeId(conversation);
            if(!conversation.equals(link.getJSONObject("source").getString("conversationId")))return null;
            return new JSONObject().put("taskId",link.getString("taskId")).put("conversationId",conversation);
        }catch(Exception unavailable){return null;}
    }
    /** Bounded display classification derives from original native rows, including markers before
     * the current event window. The marker never authorizes a learning run or another tool. */
    public static JSONObject header(SQLiteDatabase db,JSONObject task)throws Exception{
        JSONObject out=header(task),flags=new JSONObject();String id=task.getString("id");
        for(String type:new String[]{"memory_owner_consolidation","media_owner_prepared","browser_research_requested"})try(Cursor row=db.rawQuery("SELECT 1 FROM task_events WHERE task_id=? AND event_type=? LIMIT 1",new String[]{id,type})){flags.put(type,row.moveToFirst());}
        return out.put("nativeEventTypeFlags",flags).put("nativeTaskKind",out.optJSONObject("sourceLearningJob")!=null?"source_learning":flags.optBoolean("memory_owner_consolidation")?"memory_consolidation":flags.optBoolean("media_owner_prepared")?"media":out.optString("kind"));
    }
    public static JSONArray events(SQLiteDatabase db,String task,String type)throws Exception{
        JSONArray out=new JSONArray();String sql="SELECT event_order,body_bytes FROM task_events WHERE task_id=?"+(type==null?"":" AND event_type=?")+" ORDER BY event_order";
        try(Cursor rows=db.rawQuery(sql,type==null?new String[]{task}:new String[]{task,type})){while(rows.moveToNext())out.put(nativeEvent(db,task,rows.getLong(0),rows.getLong(1)));}return out;
    }
    public static JSONObject latest(SQLiteDatabase db,String task,String type)throws Exception{
        try(Cursor row=db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type=? ORDER BY event_order DESC LIMIT 1",new String[]{task,type})){return row.moveToFirst()?nativeEvent(db,task,row.getLong(0),row.getLong(1)):null;}
    }
    public static JSONObject call(SQLiteDatabase db,String task,String call)throws Exception{
        JSONObject result=null;try(Cursor rows=db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='inference_receipt' AND call_id=? ORDER BY event_order",new String[]{task,call})){while(rows.moveToNext()){if(result!=null)throw new IllegalStateException("原调用计量出现重复身份");result=nativeEvent(db,task,rows.getLong(0),rows.getLong(1));}}return result;
    }
    private static JSONObject nativeEvent(SQLiteDatabase db,String task,long position,long bytes)throws Exception{
        if(bytes<0)throw new IllegalStateException("原事件字节数不可核对");
        // Preserve legacy native get/list semantics. Only explicit native complete-audit consumers
        // materialize this large row; UI pages have already rejected it by SQL byte preflight.
        if(bytes>MAX_NATIVE_EVENT_BYTES){StringWriter body=new StringWriter();try(Reader original=new SqlEventReader(db,task,position)){char[] buffer=new char[4096];int count;while((count=original.read(buffer))!=-1)body.write(buffer,0,count);}return new JSONObject(body.toString());}
        try(Cursor row=db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_order=?",new String[]{task,Long.toString(position)})){if(!row.moveToFirst())throw new IllegalStateException("原事件已不可读");return new JSONObject(row.getString(0));}
    }
    private static final String SEQUENCE_FLOOR="task_events.sequence_floor:";
    /** Native negative fence survives removal of the last/all original rows. It contains no body
     * and grants no source access. Existing event numbers are never rewritten by this migration. */
    public static void seedSequenceFloors(SQLiteDatabase db)throws Exception{
        db.beginTransaction();try{
            try(Cursor tasks=db.rawQuery("SELECT task_id FROM task_events GROUP BY task_id",null)){
                while(tasks.moveToNext())pinSequence(db,tasks.getString(0),0);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Read-only and lock-free with respect to TaskStore/Books; SQLite retains its original scope. */
    public static long lastSequence(SQLiteDatabase db,String task){
        long existing;try(Cursor row=db.rawQuery("SELECT MAX(COALESCE(MAX(event_order),0),COALESCE(MAX(native_seq),0)) FROM task_events WHERE task_id=?",new String[]{task})){row.moveToFirst();existing=row.getLong(0);}
        try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{SEQUENCE_FLOOR+task})){
            if(!row.moveToFirst())return existing;String raw=row.getString(0);
            if(raw==null||!raw.matches("0|[1-9][0-9]{0,18}"))throw new IllegalStateException("原事件序号屏障无效，未回绕重用");
            final long floor;try{floor=Long.parseLong(raw);}catch(NumberFormatException bad){throw new IllegalStateException("原事件序号屏障超限，未回绕重用",bad);}
            return Math.max(existing,floor);
        }
    }
    /** Pin an already selected native sequence, in the same original SQL transaction as selection. */
    public static void pinSequence(SQLiteDatabase db,String task,long selected)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("原事件屏障必须随原事务保存");
        if(task==null||task.isEmpty()||task.length()>240||selected<0)throw new IllegalArgumentException("原事件屏障身份无效");
        long floor=Math.max(lastSequence(db,task),selected);
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",new Object[]{SEQUENCE_FLOOR+task,Long.toString(floor)});
    }
    public static int retireSnapshots(SQLiteDatabase db,String task,long through,boolean forgotten)throws Exception{
        int removed=0;try(Cursor rows=db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='user_verification' AND COALESCE(native_seq,0)<=? ORDER BY event_order",new String[]{task,Long.toString(through)})){
            while(rows.moveToNext()){
                long order=rows.getLong(0);JSONObject event=nativeEvent(db,task,order,rows.getLong(1));boolean changed=false;
                if(event.has("memorySourceSnapshot")){event.remove("memorySourceSnapshot");removed++;changed=true;}
                if(forgotten&&!event.optBoolean("memorySourceSnapshotForgotten")){event.put("memorySourceSnapshotForgotten",true);changed=true;}
                if(changed){String raw=event.toString();ContentValues body=new ContentValues();body.put("event_json",raw);db.update("task_events",body,"task_id=? AND event_order=?",new String[]{task,Long.toString(order)});updateMetadata(db,task,order,event,utf8(raw),true);}
            }
        }return removed;
    }
    public static long append(SQLiteDatabase db,String task,JSONObject event)throws Exception{
        long last=lastSequence(db,task);
        if(last==Long.MAX_VALUE)throw new IllegalStateException("原事件序号已耗尽");long next=last+1;event.put("seq",next);
        if(!db.inTransaction())throw new IllegalStateException("原事件必须与序号屏障同事务保存");
        String raw=event.toString();ContentValues row=new ContentValues();row.put("task_id",task);row.put("event_order",next);row.put("event_type",event.optString("type"));row.put("event_json",raw);db.insertOrThrow("task_events",null,row);updateMetadata(db,task,next,event,utf8(raw),false);pinSequence(db,task,next);return next;
    }
    /** Replace only the original native event, preserving its stable storage cursor and native seq. */
    public static void replace(SQLiteDatabase db,String task,long seq,JSONObject event)throws Exception{
        long order;try(Cursor row=db.rawQuery("SELECT event_order FROM task_events WHERE task_id=? AND native_seq=? ORDER BY event_order DESC LIMIT 1",new String[]{task,Long.toString(seq)})){if(!row.moveToFirst())throw new IllegalStateException("原事件不存在");order=row.getLong(0);}
        String raw=event.toString();ContentValues body=new ContentValues();body.put("event_json",raw);if(db.update("task_events",body,"task_id=? AND event_order=?",new String[]{task,Long.toString(order)})!=1)throw new IllegalStateException("原事件替换未保存");updateMetadata(db,task,order,event,utf8(raw),true);
    }
    private static void updateMetadata(SQLiteDatabase db,String task,long order,JSONObject event,long bytes,boolean revised)throws Exception{
        ContentValues meta=new ContentValues();if(bytes>=0)meta.put("body_bytes",bytes);
        if(event!=null){String type=event.optString("type");meta.put("event_type",type.length()<=240?type:"");Object seq=event.opt("seq");if((seq instanceof Integer||seq instanceof Long)&&((Number)seq).longValue()>0)meta.put("native_seq",((Number)seq).longValue());else meta.putNull("native_seq");Object completed=event.opt("completed");if(completed instanceof Boolean)meta.put("completed",(Boolean)completed?1:0);else meta.putNull("completed");String call=event.optString("callId");if(call.length()<=240)meta.put("call_id",call);}
        if(meta.size()>0)db.update("task_events",meta,"task_id=? AND event_order=?",new String[]{task,Long.toString(order)});
        if(revised)db.execSQL("UPDATE task_events SET row_revision=row_revision+1 WHERE task_id=? AND event_order=?",new Object[]{task,order});
    }
    /** Keep the existing memory search's full confirmation text, not a recent UI event window. */
    public static JSONObject searchProjection(SQLiteDatabase db,JSONObject task)throws Exception{
        JSONObject out=new JSONObject(task.toString());try(Cursor row=db.rawQuery("SELECT audit_format FROM tasks WHERE task_id=?",new String[]{task.getString("id")})){if(row.moveToFirst()&&row.getInt(0)==1)out.put("events",events(db,task.getString("id"),"user_verification"));}return out;
    }
    public static JSONObject eventPage(SQLiteDatabase db,String task,String cursor,int limit,int maxBytes)throws Exception{return page(db,task,"events",cursor,limit,maxBytes);}
    public static JSONObject operationPage(SQLiteDatabase db,String task,String cursor,int limit,int maxBytes)throws Exception{return page(db,task,"operations",cursor,limit,maxBytes);}
    /** Native tool-filtered original operation page; a filter is a query constraint, never a grant. */
    public static JSONObject operationPage(SQLiteDatabase db,String task,String cursor,int limit,int maxBytes,Set<String> nativeTools)throws Exception{return page(db,task,"operations",cursor,limit,maxBytes,nativeTools,-1);}
    private static JSONObject page(SQLiteDatabase db,String task,String section,String cursor,int limit,int maxBytes)throws Exception{return page(db,task,section,cursor,limit,maxBytes,null,-1);}
    static String operationToolFilter(Set<String> nativeTools){
        if(nativeTools==null||nativeTools.isEmpty()||nativeTools.size()>24)throw new IllegalArgumentException("原操作工具范围无效");java.util.List<String> names=new java.util.ArrayList<>(nativeTools);java.util.Collections.sort(names);for(String name:names)if(!name.matches("[a-z][a-z0-9_]{0,119}"))throw new IllegalArgumentException("原操作工具名无效");return " AND tool IN ('"+String.join("','",names)+"')";
    }
    public static JSONObject operationPage(SQLiteDatabase db,String task,String cursor,int limit,int maxBytes,Set<String> nativeTools,long nativeUpper)throws Exception{return page(db,task,"operations",cursor,limit,maxBytes,nativeTools,nativeUpper);}
    private static JSONObject page(SQLiteDatabase db,String task,String section,String cursor,int limit,int maxBytes,Set<String> nativeTools,long nativeUpper)throws Exception{
        if(task==null||task.isEmpty()||task.length()>240||cursor==null||cursor.length()>2048||limit<1||limit>MAX_PAGE_ROWS||maxBytes<1024||maxBytes>MAX_PAGE_BYTES)throw new IllegalArgumentException("原任务历史读取范围无效");
        boolean events=section.equals("events");if(events&&nativeTools!=null)throw new IllegalArgumentException("事件页不能改用操作工具范围");String filter=nativeTools==null?"":operationToolFilter(nativeTools);
        String table=events?"task_events":"operations",position=events?"event_order":"rowid";long upper,last;
        if(cursor.isEmpty()){try(Cursor row=db.rawQuery("SELECT COALESCE(MAX("+position+"),0) FROM "+table+" WHERE task_id=?"+filter,new String[]{task})){row.moveToFirst();upper=row.getLong(0);if(nativeUpper>=0)upper=Math.min(upper,nativeUpper);}last=upper;}
        else{JSONObject c=decode(cursor);if(c.length()!=5||c.optInt("v")!=1||!task.equals(c.optString("taskId"))||!section.equals(c.optString("section"))||!(c.opt("upper") instanceof Number)||!(c.opt("last") instanceof Number))throw new IllegalArgumentException("原历史游标不匹配");upper=c.getLong("upper");last=c.getLong("last");if(upper<0||last<1||last>upper||nativeUpper>=0&&upper>nativeUpper)throw new IllegalArgumentException("原历史游标边界无效");}
        JSONArray entries=new JSONArray();long used=0,scanned=0;boolean more=false,limited=false;long next=last;
        String select=events?"event_order,event_type,body_bytes,row_revision,native_seq":"rowid,operation_id,tool,state,run_id,created_at,length(CAST(arguments AS BLOB)),COALESCE(length(CAST(receipt AS BLOB)),0)";
        String boundary=cursor.isEmpty()?"":" AND "+position+"<?";
        String[] args=cursor.isEmpty()?new String[]{task,Long.toString(upper),Integer.toString(limit+1)}:new String[]{task,Long.toString(upper),Long.toString(last),Integer.toString(limit+1)};
        try(Cursor rows=db.rawQuery("SELECT "+select+" FROM "+table+" WHERE task_id=?"+filter+" AND "+position+"<=?"+boundary+" ORDER BY "+position+" DESC LIMIT ?",args)){
            while(rows.moveToNext()){
                if(entries.length()==limit){more=true;break;}scanned++;long at=rows.getLong(0);next=at;JSONObject item=new JSONObject().put("position",at);
                long bytes=events?rows.getLong(2):rows.getLong(6)+rows.getLong(7);
                if(events)item.put("eventKey","task:"+task+":event-order:"+at).put("type",rows.getString(1)).put("rowRevision",rows.getLong(3));
                else item.put("operationId",rows.getString(1)).put("tool",rows.getString(2)).put("state",rows.getString(3)).put("runId",rows.getString(4)).put("createdAt",rows.getLong(5));
                item.put("bodyBytes",bytes);
                if(bytes<0||bytes>maxBytes-used){item.put("status","read_limited").put("reason","original_body_exceeds_remaining_page_bytes");limited=true;}
                else if(events){item.put("status","available").put("event",nativeEvent(db,task,at,bytes));used+=bytes;}
                else{try(Cursor body=db.rawQuery("SELECT arguments,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{task,rows.getString(1)})){if(!body.moveToFirst())throw new IllegalStateException("原操作不可读");item.put("status","available").put("arguments",NativeMcpPrivateFields.publicArguments(new JSONTokener(body.getString(0)).nextValue())).put("receipt",body.isNull(1)?JSONObject.NULL:new JSONTokener(body.getString(1)).nextValue());used+=bytes;}}
                entries.put(events?item:dev.pocketengine.core.ToolOutcome.projectOperation(item));
            }
        }
        String nextCursor=more?encode(new JSONObject().put("v",1).put("taskId",task).put("section",section).put("upper",upper).put("last",next)):"";
        return new JSONObject().put("format","pocketworld.task-audit-page.v1").put("taskId",task).put("section",section).put("entries",entries).put("nextCursor",nextCursor).put("hasMore",more).put("upperPosition",upper).put("bodyBytesRead",used).put("rowsScanned",scanned).put("readLimited",limited).put("completeHistory",!more&&!limited&&cursor.isEmpty()).put("executionAuthorized",false).put("coverage","original_committed_rows_in_cursor_window_not_a_replay_or_execution_snapshot");
    }
    public static JSONObject summaryPage(SQLiteDatabase db,String cursor,int limit)throws Exception{
        if(cursor==null||cursor.length()>2048||limit<1||limit>40)throw new IllegalArgumentException("任务列表范围无效");long lastAt=Long.MAX_VALUE;String lastId="";
        if(!cursor.isEmpty()){JSONObject c=decode(cursor);if(c.length()!=3||c.optInt("v")!=1||!(c.opt("at") instanceof Number))throw new IllegalArgumentException("任务列表游标无效");lastAt=c.getLong("at");lastId=c.getString("id");}
        JSONArray tasks=new JSONArray();boolean more=false;long at=lastAt,used=0;String id=lastId;
        try(Cursor rows=db.rawQuery("SELECT task_id,CASE WHEN length(CAST(ui_header AS BLOB))<=32768 THEN ui_header END,updated_at,length(CAST(ui_header AS BLOB)) FROM tasks WHERE updated_at<? OR (updated_at=? AND task_id<?) ORDER BY updated_at DESC,task_id DESC LIMIT ?",new String[]{Long.toString(lastAt),Long.toString(lastAt),lastId,Integer.toString(limit+1)})){
            while(rows.moveToNext()){if(rows.isNull(1)||rows.getLong(3)>MAX_HEADER_BYTES)throw new IllegalStateException("任务摘要索引不可核对；未回退加载完整audit");if(tasks.length()==limit||rows.getLong(3)>MAX_PAGE_BYTES-used){more=true;break;}id=rows.getString(0);at=rows.getLong(2);used+=rows.getLong(3);tasks.put(new JSONObject(rows.getString(1)));}
        }
        return new JSONObject().put("tasks",tasks).put("nextCursor",more?encode(new JSONObject().put("v",1).put("at",at).put("id",id)):"").put("hasMore",more).put("auditBodyIncluded",false).put("headerBytesRead",used).put("coverage","current_task_headers_keyset_window_updates_require_refresh");
    }
    private static String encode(JSONObject value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject decode(String value)throws Exception{try{return new JSONObject(new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8));}catch(Exception bad){throw new IllegalArgumentException("原历史游标不可读",bad);}}
    private static long jsonBytes(Object value,long remaining,int depth)throws Exception{
        if(remaining<0||depth>40)return -1;
        if(value instanceof JSONObject){remaining-=2;JSONObject object=(JSONObject)value;for(java.util.Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();remaining=jsonBytes(key,remaining-2,depth+1);remaining=jsonBytes(object.get(key),remaining,depth+1);if(remaining<0)return -1;}return remaining;}
        if(value instanceof JSONArray){remaining-=2;JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++){remaining=jsonBytes(array.get(i),remaining-1,depth+1);if(remaining<0)return -1;}return remaining;}
        if(value instanceof String){remaining-=2;String text=(String)value;for(int i=0;i<text.length();){int cp=text.codePointAt(i);i+=Character.charCount(cp);remaining-=cp<32?6:cp=='"'||cp=='\\'?2:cp<128?1:cp<2048?2:cp<65536?3:4;if(remaining<0)return -1;}return remaining;}
        if(value==null||value==JSONObject.NULL)return remaining-4;if(value instanceof Number||value instanceof Boolean)return remaining-value.toString().length();return -1;
    }
    private static long utf8(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    private static String clip(String value,int max){if(value.length()<=max)return value;int end=Character.isHighSurrogate(value.charAt(max-1))?max-1:max;return value.substring(0,end)+"…";}
    private static final class SmallWriter extends Writer{
        final int max;final StringBuilder value=new StringBuilder();boolean limited;
        SmallWriter(int max){this.max=max;}public void write(char[] chars,int offset,int length){if(limited)return;if(length>max-value.length()){limited=true;return;}value.append(chars,offset,length);}public void flush(){}public void close(){}public String toString(){return value.toString();}
    }
    private static final class SqlWriter extends Writer{
        final SQLiteDatabase db;final String task;final long order;final boolean core;final StringBuilder chunk=new StringBuilder();long bytes;
        SqlWriter(SQLiteDatabase db,String task,long order,boolean core){this.db=db;this.task=task;this.order=order;this.core=core;}
        public void write(char[] chars,int offset,int length)throws IOException{chunk.append(chars,offset,length);if(chunk.length()>=4096)flushChunk(false);}
        private void flushChunk(boolean end)throws IOException{
            int count=chunk.length();if(!end&&count>0&&Character.isHighSurrogate(chunk.charAt(count-1)))count--;if(count==0)return;String text=chunk.substring(0,count);chunk.delete(0,count);bytes+=utf8(text);
            try{if(core)db.execSQL("UPDATE tasks SET audit_stage=COALESCE(audit_stage,'')||? WHERE task_id=?",new Object[]{text,task});else db.execSQL("UPDATE task_events SET event_json=event_json||? WHERE task_id=? AND event_order=?",new Object[]{text,task,order});}catch(Exception failed){throw new IOException("原事件迁移写入失败，事务未切换",failed);}
        }
        public void flush()throws IOException{flushChunk(false);}public void close()throws IOException{flushChunk(true);}
    }
    private abstract static class SqlReader extends Reader{
        final SQLiteDatabase db;final String task;long start=1;String chunk="";int offset;boolean ended,inString,escaped;int tokenChars;
        SqlReader(SQLiteDatabase db,String task){this.db=db;this.task=task;}abstract String select();abstract String[] args();
        public int read(char[] target,int off,int len)throws IOException{
            if(len==0)return 0;if(offset==chunk.length()&&!ended)try(Cursor row=db.rawQuery(select(),args())){if(!row.moveToFirst())throw new IOException("原任务迁移行已消失");chunk=row.getString(0);offset=0;if(chunk.isEmpty())ended=true;else{checkTokens(chunk);start+=chunk.codePointCount(0,chunk.length());}}catch(Exception failed){throw new IOException("原任务分块读取失败，未切换权威",failed);}
            if(ended)return -1;int count=Math.min(len,chunk.length()-offset);chunk.getChars(offset,offset+count,target,off);offset+=count;return count;
        }
        private void checkTokens(String value)throws IOException{
            for(int i=0;i<value.length();i++){char c=value.charAt(i);if(inString){if(++tokenChars>MAX_NATIVE_EVENT_BYTES)throw new IOException("旧任务单个JSON值超过迁移上限，原文事务保留");if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"'){inString=false;tokenChars=0;}}else if(c=='"'){inString=true;tokenChars=0;}else if(c==','||c==':'||c=='{'||c=='}'||c=='['||c==']'||Character.isWhitespace(c))tokenChars=0;else if(++tokenChars>MAX_NATIVE_EVENT_BYTES)throw new IOException("旧任务单个JSON token过大，原文事务保留");}
        }
        public void close(){}
    }
    private static final class SqlDocumentReader extends SqlReader{
        SqlDocumentReader(SQLiteDatabase db,String task){super(db,task);}String select(){return "SELECT substr(document,?,4096) FROM tasks WHERE task_id=?";}String[] args(){return new String[]{Long.toString(start),task};}
    }
    private static final class SqlEventReader extends SqlReader{
        final long order;SqlEventReader(SQLiteDatabase db,String task,long order){super(db,task);this.order=order;}String select(){return "SELECT substr(event_json,?,4096) FROM task_events WHERE task_id=? AND event_order=?";}String[] args(){return new String[]{Long.toString(start),task,Long.toString(order)};}
    }
}
