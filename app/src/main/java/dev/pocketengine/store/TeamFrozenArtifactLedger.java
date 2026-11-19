package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads the original two kinds of settled author delivery. Never loads arguments, events, screenshots
 * or unrelated operations, never projects a task for display, never settles a tool or grants access. */
final class TeamFrozenArtifactLedger {
    private TeamFrozenArtifactLedger(){}
    static JSONArray original(SQLiteDatabase db,String task,String frozenRun,RunControl reader)throws Exception{
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try{
            String sql="SELECT operation_id,run_id,tool FROM operations WHERE task_id=? AND state='receipt' "+
                    "AND tool IN ('team_freeze_outputs','team_import_remote_contribution') AND receipt IS NOT NULL";
            String[] args=frozenRun==null?new String[]{task}:new String[]{task,frozenRun};
            JSONArray out=new JSONArray();
            try(Cursor rows=db.rawQuery(sql+(frozenRun==null?"":" AND run_id=?")+" ORDER BY created_at,rowid",args)){
                while(rows.moveToNext()){
                    if(reader!=null)reader.check();
                    String body=TaskStoreTextReader.read(db,TaskStoreTextReader.Column.OPERATION_RECEIPT,rows.getString(0));
                    // The SQL identity and complete chunked body belong to this same short snapshot.
                    out.put(new JSONObject().put("tool",rows.getString(2)).put("runId",rows.getString(1))
                            .put("state","receipt").put("receipt",new JSONObject(body)));
                }
            }
            if(owns)db.setTransactionSuccessful();return out;
        }finally{if(owns)db.endTransaction();}
    }
}
