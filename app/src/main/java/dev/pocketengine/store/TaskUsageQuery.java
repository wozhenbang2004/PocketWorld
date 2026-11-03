package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.UsageTrendProjection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded owner read views on TaskStore's existing SQLite connection; this class never writes. */
final class TaskUsageQuery {
    static final int MAX_ROWS=8192,MAX_BYTES=8*1024*1024,MAX_RECEIPT_BYTES=65536;
    private static final Set<String> LOCAL_ORIGINS=Set.of("native_inference","owner_reviewed_realtime","native_remote_preview",
            "native_remote_session_branch","native_remote_project_history","native_remote_private_inputs","team_research","automation");
    private TaskUsageQuery() { }
    static boolean privateHeader(JSONObject header){
        if(!"owner".equals(header.optString("actorId"))||!"private".equals(header.optString("spaceId"))||header.optBoolean("metadataLimited"))return false;
        JSONObject origin=header.optJSONObject("origin");
        if(origin==null)return !header.has("origin")||header.isNull("origin");
        return LOCAL_ORIGINS.contains(origin.optString("kind"))&&!"EXTERNAL_GROUP".equals(origin.optString("inferenceAudience"))
                &&!origin.has("groupInputSource")&&!origin.has("externalGroupApiMode");
    }
    /** All retained runs of one original owner task, within the existing diagnostic read budgets. */
    static JSONObject taskUsage(SQLiteDatabase db,String task,RunControl control)throws Exception {
        if(task==null||!task.matches("[a-fA-F0-9-]{36}")||control==null||control.hasHostRun())throw new SecurityException("累计用量只供原生主人页面读取原任务");
        control.check();
        try(Cursor row=db.rawQuery("SELECT ui_header FROM tasks WHERE task_id=?",new String[]{task})){
            if(!row.moveToFirst()||row.isNull(0)||row.getString(0).length()>32768||!privateHeader(new JSONObject(row.getString(0))))throw new SecurityException("原私人任务来源不可核对");
        }
        JSONArray receipts=new JSONArray();int rows=0,bytes=0,oversized=0;boolean limited=false;
        try(Cursor cursor=db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='inference_receipt' ORDER BY event_order DESC LIMIT "+(MAX_ROWS+1),new String[]{task})){
            while(cursor.moveToNext()){
                control.check();if(rows==MAX_ROWS){limited=true;break;}rows++;
                long size=cursor.getLong(1);if(size<0||size>MAX_RECEIPT_BYTES){oversized++;continue;}
                if(bytes+size>MAX_BYTES){limited=true;break;}
                try(Cursor body=db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_order=?",new String[]{task,Long.toString(cursor.getLong(0))})){
                    if(!body.moveToFirst())throw new IllegalStateException("原调用在读取时已变化");String text=body.getString(0);
                    if(text.getBytes(StandardCharsets.UTF_8).length!=size)throw new IllegalStateException("原调用大小无法核对");
                    JSONObject event=new JSONObject(text),identity=event.optJSONObject("context");
                    if(identity==null||!task.equals(identity.optString("taskId")))throw new SecurityException("调用不属于原SQL任务");
                    receipts.put(event);bytes+=size;
                }
            }
        }
        JSONArray chronological=new JSONArray();for(int i=receipts.length()-1;i>=0;i--)chronological.put(receipts.getJSONObject(i));
        control.check();return new JSONObject().put("format","pocketworld.task-inference-usage.v1").put("taskId",task)
                .put("projection",dev.pocketengine.runtime.usage.InferenceProjection.fromEvents(chronological))
                .put("rowsExamined",rows).put("sourceBytes",bytes).put("oversizedRows",oversized).put("queryLimited",limited||oversized>0)
                .put("maxRows",MAX_ROWS).put("maxSourceBytes",MAX_BYTES).put("sourceReadAt",System.currentTimeMillis())
                .put("allRetainedRuns",true).put("readOnly",true).put("executionAuthorized",false);
    }
    static JSONObject trends(SQLiteDatabase db,UsageTrendProjection.Query query,RunControl control)throws Exception {
        control.check();JSONArray receipts=new JSONArray();int rows=0,bytes=0,scopeExcluded=0,oversized=0;boolean limited=false;
        String sql="SELECT e.task_id,e.event_order,e.body_bytes,t.ui_header FROM task_events e JOIN tasks t ON t.task_id=e.task_id WHERE e.event_type='inference_receipt' AND t.updated_at>=? ORDER BY t.updated_at DESC,e.task_id,e.event_order DESC LIMIT "+(MAX_ROWS+1);
        try(Cursor cursor=db.rawQuery(sql,new String[]{Long.toString(query.fromInclusive())})){
            while(cursor.moveToNext()){
                control.check();if(rows==MAX_ROWS){limited=true;break;}rows++;
                String header=cursor.getString(3);if(header==null||header.length()>32768){scopeExcluded++;continue;}
                if(!privateHeader(new JSONObject(header))){scopeExcluded++;continue;}
                long size=cursor.getLong(2);if(size<0||size>MAX_RECEIPT_BYTES){oversized++;continue;}
                if(bytes+size>MAX_BYTES){limited=true;break;}
                try(Cursor body=db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_order=?",new String[]{cursor.getString(0),Long.toString(cursor.getLong(1))})){
                    if(!body.moveToFirst())throw new IllegalStateException("原调用在读取时已变化");String text=body.getString(0);
                    if(text.getBytes(StandardCharsets.UTF_8).length!=size)throw new IllegalStateException("原调用大小无法核对");
                    JSONObject event=new JSONObject(text),context=event.optJSONObject("context");
                    if(context==null||!cursor.getString(0).equals(context.optString("taskId")))throw new SecurityException("调用不属于原SQL任务");
                    receipts.put(event);bytes+=size;
                }
            }
        }
        control.check();return UsageTrendProjection.fromReceipts(receipts,query).put("rowsExamined",rows).put("sourceBytes",bytes)
                .put("scopeExcludedRows",scopeExcluded).put("oversizedRows",oversized).put("queryLimited",limited||oversized>0)
                .put("maxRows",MAX_ROWS).put("maxSourceBytes",MAX_BYTES).put("sourceReadAt",System.currentTimeMillis());
    }
    static JSONObject traceSource(SQLiteDatabase db,String task,RunControl control)throws Exception {
        if(task==null||!task.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("请选择原任务");control.check();
        JSONObject header;long revision;
        try(Cursor row=db.rawQuery("SELECT ui_header,updated_at FROM tasks WHERE task_id=?",new String[]{task})){
            if(!row.moveToFirst()||row.isNull(0)||row.getString(0).length()>32768)throw new SecurityException("原任务来源已不可读");
            header=new JSONObject(row.getString(0));revision=row.getLong(1);
        }
        if(!privateHeader(header))throw new SecurityException("诊断只读取本机主人私人任务，渠道及群任务不在此范围");
        String run=header.optString("runId");int epoch=header.optInt("coordinatorEpoch",-1);
        if(!run.matches("[a-fA-F0-9-]{36}")||epoch<0)throw new SecurityException("原执行身份未知");
        JSONArray receipts=new JSONArray(),operations=new JSONArray();boolean limited=false;int bytes=0;
        StringBuilder source=new StringBuilder(task).append(':').append(run).append(':').append(epoch).append(':').append(revision);
        int examinedCalls=0;
        try(Cursor rows=db.rawQuery("SELECT event_order,body_bytes,row_revision FROM task_events WHERE task_id=? AND event_type='inference_receipt' ORDER BY event_order DESC LIMIT 513",new String[]{task})){
            while(rows.moveToNext()){
                control.check();if(examinedCalls++==512){limited=true;break;}long length=rows.getLong(1);if(length<0||length>MAX_RECEIPT_BYTES||bytes+length>MAX_BYTES){limited=true;continue;}
                try(Cursor body=db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_order=?",new String[]{task,Long.toString(rows.getLong(0))})){
                    if(!body.moveToFirst())throw new SecurityException("原调用已被移除");JSONObject value=new JSONObject(body.getString(0)),identity=value.optJSONObject("context");
                    if(identity==null||!task.equals(identity.optString("taskId")))throw new SecurityException("原调用归属冲突");
                    if(!run.equals(identity.optString("runId"))||epoch!=identity.optInt("epoch",-1))continue;
                    receipts.put(value);bytes+=length;source.append("|call:").append(rows.getLong(0)).append(':').append(rows.getLong(2));
                }
            }
        }
        try(Cursor rows=db.rawQuery("SELECT operation_id,tool,state,created_at,length(CAST(receipt AS BLOB)) FROM operations WHERE task_id=? AND run_id=? ORDER BY created_at,operation_id LIMIT 513",new String[]{task,run})){
            while(rows.moveToNext()){
                control.check();if(operations.length()==512){limited=true;break;}
                String op=rows.getString(0),state=rows.getString(2);JSONObject value=new JSONObject().put("operationId",op).put("tool",rows.getString(1)).put("state",state).put("startedAt",rows.getLong(3));
                String terminal="UNKNOWN";boolean finalReceipt=false;
                if("receipt".equals(state)&&!rows.isNull(4)&&rows.getLong(4)<=MAX_RECEIPT_BYTES){
                    try(Cursor receipt=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{task,run,op})){
                        if(receipt.moveToFirst()&&!receipt.isNull(0)){JSONObject observed=new JSONObject(receipt.getString(0));String status=observed.optString("status");
                            if(Set.of("completed","failed","cancelled","timed_out","rejected").contains(status)){terminal=status.toUpperCase(java.util.Locale.ROOT);finalReceipt=true;}
                            else if(Boolean.TRUE.equals(observed.opt("ok"))){terminal="COMPLETED";finalReceipt=true;}
                            else if(Boolean.FALSE.equals(observed.opt("ok"))){terminal="FAILED";finalReceipt=true;}
                        }
                    }
                }
                source.append("|op:").append(op).append(':').append(state).append(':').append(terminal);
                // Operation table has no authoritative finish timestamp. Do not invent a duration.
                operations.put(value.put("outcome",terminal).put("terminalReceiptObserved",finalReceipt).put("finishedAt",JSONObject.NULL));
            }
        }
        control.check();return new JSONObject().put("format","pocketworld.trace-source.v1").put("taskId",task).put("runId",run)
                .put("epoch",epoch).put("sourceRevision",digest(source.toString())).put("capturedAt",System.currentTimeMillis())
                .put("receipts",receipts).put("operations",operations).put("limited",limited).put("scope","owner/private_supported_local_origins_only")
                .put("readOnly",true).put("executionAuthorized",false);
    }
    private static String digest(String value)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
