package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** Reads one original request bound to its task/run/operation/tool tuple.
 * It supplies evidence for caller checks, never a capability or dispatch grant. */
final class TaskOperationArguments {
    private TaskOperationArguments() {}

    static JSONObject bound(SQLiteDatabase db,String taskId,String runId,String operationId,String tool,
            String missing,String duplicate)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            JSONObject arguments;
            // Pin the bound identity before chunking. Never select by operation ID
            // alone and then trust a JSON-supplied parent/tool. IMMEDIATE can wait
            // or reserve the writer until this single request is materialized.
            try(Cursor row=db.rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool=?",
                    new String[]{taskId,runId,operationId,tool})){
                if(!row.moveToFirst())throw new SecurityException(missing);
                arguments=new JSONObject(TaskStoreTextReader.read(db,TaskStoreTextReader.Column.OPERATION_ARGUMENTS,row.getString(0)));
                // Preserve callers that explicitly reject ambiguous stored evidence.
                if(duplicate!=null&&row.moveToNext())throw new SecurityException(duplicate);
            }
            if(owns)db.setTransactionSuccessful();return arguments;
        }finally{if(owns)db.endTransaction();}
    }
    /** Optional saved evidence with its exact ledger state. Missing stays null;
     * no default request or prepared state is synthesized. Neither state nor
     * arguments may come from a second snapshot after the bound row is chosen. */
    static JSONObject optionalWithState(SQLiteDatabase db,String taskId,String runId,String operationId,String tool)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            JSONObject result=null;
            try(Cursor row=db.rawQuery("SELECT operation_id,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool=?",
                    new String[]{taskId,runId,operationId,tool})){
                if(row.moveToFirst()){
                    JSONObject arguments=new JSONObject(TaskStoreTextReader.read(db,TaskStoreTextReader.Column.OPERATION_ARGUMENTS,row.getString(0)));
                    result=new JSONObject().put("arguments",arguments).put("state",row.getString(1));
                }
            }
            if(owns)db.setTransactionSuccessful();return result;
        }finally{if(owns)db.endTransaction();}
    }

}
