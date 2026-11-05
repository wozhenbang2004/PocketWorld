package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original coordinator request context. It is never a HostRun, operation, receipt, or replay grant. */
final class TeamCoordinatorCheckpoint {
    static final int MAX_BYTES=4*1024*1024;
    private TeamCoordinatorCheckpoint() { }
    static void createSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS team_coordinator_checkpoints(task_id TEXT NOT NULL,run_id TEXT NOT NULL,checkpoint_id TEXT NOT NULL,execution_turn_id TEXT NOT NULL,context_json TEXT NOT NULL,sources_json TEXT NOT NULL,context_hash TEXT NOT NULL,body_bytes INTEGER NOT NULL,PRIMARY KEY(task_id,run_id,checkpoint_id))");
    }
    static void request(SQLiteDatabase db,String task,String run,String checkpoint,JSONObject context,JSONObject sources)throws Exception{
        String raw=context.toString();int bytes=raw.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>MAX_BYTES)throw new IllegalStateException("original_coordinator_context_exceeds_budget");
        JSONObject existing=read(db,task,run,checkpoint);String hash=TaskMemoryTrace.rawFingerprint(context);
        if(existing!=null){if(!hash.equals(existing.getString("contextHash")))throw new SecurityException("original_coordinator_context_changed");return;}
        ContentValues row=new ContentValues();row.put("task_id",task);row.put("run_id",run);row.put("checkpoint_id",checkpoint);
        row.put("execution_turn_id",context.getString("executionTurnId"));
        row.put("context_json",raw);row.put("sources_json",sources.toString());row.put("context_hash",hash);row.put("body_bytes",bytes);
        db.insertOrThrow("team_coordinator_checkpoints",null,row);
    }
    static void assistant(SQLiteDatabase db,String task,String run,String checkpoint,JSONObject message)throws Exception{
        JSONObject original=read(db,task,run,checkpoint);if(original==null)throw new SecurityException("original_coordinator_context_missing");
        JSONObject context=original.getJSONObject("context");JSONArray responses=context.optJSONArray("responses");if(responses==null)responses=new JSONArray();
        for(int i=0;i<responses.length();i++)if(message.getString("nativeTraceCallId").equals(responses.getJSONObject(i).optString("nativeTraceCallId"))){
            if(!TaskMemoryTrace.rawFingerprint(message).equals(TaskMemoryTrace.rawFingerprint(responses.getJSONObject(i))))throw new SecurityException("original_coordinator_response_changed");return;
        }
        responses.put(message);context.put("responses",responses);String raw=context.toString();int bytes=raw.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>MAX_BYTES)throw new IllegalStateException("original_coordinator_context_exceeds_budget");ContentValues update=new ContentValues();update.put("context_json",raw);update.put("body_bytes",bytes);
        if(db.update("team_coordinator_checkpoints",update,"task_id=? AND run_id=? AND checkpoint_id=?",new String[]{task,run,checkpoint})!=1)throw new SecurityException("original_coordinator_context_missing");
    }
    static JSONObject read(SQLiteDatabase db,String task,String run,String checkpoint)throws Exception{
        if(checkpoint==null||checkpoint.isEmpty())return null;
        try(Cursor row=db.rawQuery("SELECT context_json,sources_json,context_hash FROM team_coordinator_checkpoints WHERE task_id=? AND run_id=? AND checkpoint_id=? AND body_bytes<=?",new String[]{task,run,checkpoint,Integer.toString(MAX_BYTES)})){
            if(!row.moveToFirst())return null;return new JSONObject().put("context",new JSONObject(row.getString(0))).put("sources",new JSONObject(row.getString(1))).put("contextHash",row.getString(2));
        }
    }
    static JSONObject sources(SQLiteDatabase db,String task,String run,String checkpoint)throws Exception{
        if(checkpoint==null||checkpoint.isEmpty())return null;
        try(Cursor row=db.rawQuery("SELECT sources_json FROM team_coordinator_checkpoints WHERE task_id=? AND run_id=? AND checkpoint_id=? AND length(CAST(sources_json AS BLOB))<=262144",new String[]{task,run,checkpoint})){
            return row.moveToFirst()?new JSONObject(row.getString(0)):null;
        }
    }
    static JSONArray messages(SQLiteDatabase db,String task,String run,String checkpoint)throws Exception{
        JSONObject selected=read(db,task,run,checkpoint);if(selected==null)return null;JSONObject context=selected.getJSONObject("context");JSONArray result=new JSONArray();
        for(String key:new String[]{"messages","inputs","responses"}){JSONArray rows=context.optJSONArray(key);for(int i=0;rows!=null&&i<rows.length();i++)result.put(rows.getJSONObject(i));}return result;
    }
    static String checkpoint(JSONObject node){return node.optString("checkpointId");}
    static JSONArray dependencies(SQLiteDatabase db,String task,String run,JSONArray turns,String currentTurn,int ordinal)throws Exception{
        JSONArray result=new JSONArray();for(int i=0;i<turns.length();i++){boolean found=false;String turn=turns.getString(i);
            try(Cursor row=db.rawQuery("SELECT sources_json FROM team_coordinator_checkpoints WHERE task_id=? AND run_id=? AND execution_turn_id=? AND length(CAST(sources_json AS BLOB))<=262144",new String[]{task,run,turn})){while(row.moveToNext()){found=true;result.put(new JSONObject(row.getString(0)));}}
            if(!found&&!(currentTurn.equals(turn)&&ordinal==0))result.put(new JSONObject().put("parents",new JSONArray()).put("materials",new JSONArray()).put("provenanceComplete",false));
        }return result;
    }
}
