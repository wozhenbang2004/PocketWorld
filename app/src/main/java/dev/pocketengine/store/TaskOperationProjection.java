package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-side projection of the original operation ledger, not another ledger.
 * TaskStore keeps responsibility for selecting the task and its presentation.
 * Only this class materializes the operation list; it cannot dispatch or settle. */
final class TaskOperationProjection {
    private TaskOperationProjection() {}

    static JSONArray forTask(SQLiteDatabase db,String taskId)throws Exception {
        // Own only the ledger read. Display callers run copy/maintenance beforehand;
        // journal restore callers deliberately do neither. Never move presentation
        // maintenance into this transaction or make it a prerequisite for recovery.
        // IMMEDIATE may reserve the writer for the whole history, with no row cap.
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            JSONArray operations=new JSONArray();
            try(Cursor rows=db.rawQuery("SELECT operation_id,tool,state,run_id FROM operations WHERE task_id=? ORDER BY created_at",new String[]{taskId})){
                while(rows.moveToNext()){
                    String operationId=rows.getString(0);
                    String arguments=TaskStoreTextReader.read(db,TaskStoreTextReader.Column.OPERATION_ARGUMENTS,operationId);
                    // Preserve the original privacy projection and failure order:
                    // validate/redact arguments before touching receipt content.
                    JSONObject op=new JSONObject().put("operationId",operationId).put("tool",rows.getString(1))
                            .put("arguments",NativeMcpPrivateFields.publicArguments(new JSONObject(arguments)))
                            .put("state",rows.getString(2)).put("runId",rows.getString(3));
                    String receipt=TaskStoreTextReader.read(db,TaskStoreTextReader.Column.OPERATION_RECEIPT,operationId);
                    if(receipt!=null)op.put("receipt",new JSONObject(receipt));
                    // The shared outcome projector remains the sole interpretation
                    // of operation states for this response. Do not invent success.
                    operations.put(dev.pocketengine.core.ToolOutcome.projectOperation(op));
                }
            }
            if(owns)db.setTransactionSuccessful();return operations;
        }finally{if(owns)db.endTransaction();}
    }
}
