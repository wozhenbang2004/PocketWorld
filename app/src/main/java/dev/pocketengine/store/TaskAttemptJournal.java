package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import org.json.JSONObject;

/** Writes endings to the existing run/lease journal, inside TaskStore's original transaction.
 * It does not choose task outcomes, reconcile effects, restore grants or schedule work. */
final class TaskAttemptJournal {
    private TaskAttemptJournal() { }

    record Original(String taskId,String runId,int epoch) {
        String[] arguments(){return new String[]{taskId,runId,Integer.toString(epoch)};}
    }
    private static final String IDENTITY="task_id=? AND run_id=? AND epoch=?";

    /** A task document alone cannot authorize closing a missing or different durable run. */
    static Original requireOriginal(SQLiteDatabase db,String taskId,JSONObject task,String runId)throws Exception {
        requireTransaction(db);
        if(!taskId.equals(task.optString("id"))||!runId.equals(task.optString("runId")))throw new SecurityException("终结回执不是原执行");
        Original original=new Original(taskId,runId,task.getInt("coordinatorEpoch"));
        try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE "+IDENTITY,original.arguments())){
            if(!row.moveToFirst())throw new SecurityException("原终结执行身份已变化");
        }
        return original;
    }

    /** Domain admission already chose this result. A late receipt may refine reconciliation;
     * therefore this path must not impose the cold-recovery running-only predicate. */
    static void finishOriginal(SQLiteDatabase db,Original original,String status,long endedAt) {
        writeEnding(db,original,status,endedAt,false);
        db.delete("leases","run_id=? AND epoch=?",new String[]{original.runId(),Integer.toString(original.epoch())});
    }

    /** All runs lost with the old process, including historical/orphan rows. Task projection
     * deliberately stays separate: e.g. paused task, interrupted run, unresolved effect. */
    static void interruptProcessRuns(SQLiteDatabase db) {
        requireTransaction(db);
        String after=null;
        while(true){
            ArrayList<Original> lost=new ArrayList<>();
            // Keyset on the existing primary key: never rescan every already-ended
            // row from the beginning on each page of a large retained journal.
            String where=after==null?"":"run_id>? AND ";
            try(Cursor rows=db.rawQuery("SELECT task_id,run_id,epoch FROM runs WHERE "+where+"status='running' ORDER BY run_id LIMIT 128",after==null?null:new String[]{after})){
                while(rows.moveToNext())lost.add(new Original(rows.getString(0),rows.getString(1),rows.getInt(2)));
            }
            if(lost.isEmpty())return;
            for(Original original:lost)writeEnding(db,original,"interrupted",null,true);
            after=lost.get(lost.size()-1).runId();
        }
    }

    /** Cold recovery revokes even orphan or already-ended leases, after result freezing. */
    static void revokeProcessLeases(SQLiteDatabase db) {
        requireTransaction(db);
        db.delete("leases",null,null);
    }

    private static void writeEnding(SQLiteDatabase db,Original original,String status,Long endedAt,boolean runningOnly) {
        requireTransaction(db);
        ContentValues values=new ContentValues();values.put("status",status);
        // Preserve recovery's existing timestamp semantics; no fabricated receipt time.
        if(endedAt!=null)values.put("updated_at",endedAt);
        String where=IDENTITY+(runningOnly?" AND status='running'":"");
        if(db.update("runs",values,where,original.arguments())!=1)throw new SecurityException("原执行终结登记已变化");
    }

    private static void requireTransaction(SQLiteDatabase db) {
        if(!db.inTransaction())throw new IllegalStateException("执行终结必须属于原任务事务");
    }
}
