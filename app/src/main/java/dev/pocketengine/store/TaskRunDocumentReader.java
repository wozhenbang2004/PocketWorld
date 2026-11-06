package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** Reads original run documents, never a competing checkpoint or grant authority.
 * Selection and byte transfer share one snapshot; callers keep all run authority. */
final class TaskRunDocumentReader {
    private TaskRunDocumentReader() {}

    static JSONObject forRun(SQLiteDatabase db,String runId)throws Exception {
        return read(db,"SELECT run_id FROM runs WHERE run_id=?",runId,TaskStoreTextReader.Column.RUN_CHECKPOINT,true);
    }

    static JSONObject latestForTask(SQLiteDatabase db,String taskId)throws Exception {
        // Preserve the original latest-epoch selection, including its one-row limit.
        return read(db,"SELECT run_id FROM runs WHERE task_id=? ORDER BY epoch DESC LIMIT 1",taskId,TaskStoreTextReader.Column.RUN_CHECKPOINT,true);
    }

    /** Read a saved grant, never renew or authorize it. All original expiry,
     * principal, scope, epoch and remaining-budget checks stay with the caller. */
    static JSONObject grantForRun(SQLiteDatabase db,String runId)throws Exception {
        return read(db,"SELECT run_id FROM runs WHERE run_id=?",runId,TaskStoreTextReader.Column.RUN_GRANT,false);
    }

    /** Read the saved grant only after the exact task/run is still at the
     * expected running epoch. This common storage check is not a dispatch token;
     * caller-specific expiry, scope, budgets and RunControl checks remain required.
     * An owned IMMEDIATE transaction can briefly wait/reserve the writer; it ends
     * before returning the saved JSON and never wraps the caller's external action. */
    static JSONObject runningGrant(SQLiteDatabase db,String taskId,String runId,int epoch,String unavailable)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            JSONObject grant;
            // Pin state and text together; validate metadata before parsing a
            // possibly damaged grant, preserving the caller's original refusal.
            try(Cursor row=db.rawQuery("SELECT status,epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{taskId,runId})){
                if(!row.moveToFirst()||!"running".equals(row.getString(0))||row.getInt(1)!=epoch)
                    throw new SecurityException(unavailable);
                grant=new JSONObject(TaskStoreTextReader.read(db,TaskStoreTextReader.Column.RUN_GRANT,runId));
            }
            if(owns)db.setTransactionSuccessful();return grant;
        }finally{if(owns)db.endTransaction();}
    }

    /** Historical attempt evidence, not a current/running grant. Keep all three
     * identity components in SQL: callers must not fall back to the latest epoch.
     * The WorkProfile caller still verifies the saved definition and scope digest. */
    static JSONObject grantForAttempt(SQLiteDatabase db,String taskId,String runId,int epoch,String unavailable)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            JSONObject grant;
            // IMMEDIATE pins exact-attempt selection and text; it may briefly
            // reserve the writer, but never encloses profile activation or dispatch.
            try(Cursor row=db.rawQuery("SELECT run_id FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{taskId,runId,Integer.toString(epoch)})){
                if(!row.moveToFirst())throw new SecurityException(unavailable);
                grant=new JSONObject(TaskStoreTextReader.read(db,TaskStoreTextReader.Column.RUN_GRANT,row.getString(0)));
            }
            if(owns)db.setTransactionSuccessful();return grant;
        }finally{if(owns)db.endTransaction();}
    }

    private static JSONObject read(SQLiteDatabase db,String selection,String identity,TaskStoreTextReader.Column column,boolean missingIsEmpty)throws Exception {
        // These fixed queries return metadata only. Begin before selecting the run
        // so a new epoch or checkpoint update cannot mix selection and body chunks.
        // IMMEDIATE may wait/reserve the writer; no resume or recovery occurs here.
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            JSONObject document;
            try(Cursor row=db.rawQuery(selection,new String[]{identity})){
                // Missing checkpoint retains its old empty-object contract; missing
                // grant must deny. Present null/damaged text is never a default grant.
                if(row.moveToFirst())document=new JSONObject(TaskStoreTextReader.read(db,column,row.getString(0)));
                else if(missingIsEmpty)document=new JSONObject();
                else throw new SecurityException("没有执行授权");
            }
            if(owns)db.setTransactionSuccessful();return document;
        }finally{if(owns)db.endTransaction();}
    }
}
