package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;

/** Original stop-ticket evidence only. Reading a batch never claims, dispatches,
 * acknowledges or replays a child stop and cannot manufacture PhoneStopBatch. */
final class TaskStopBatchReader {
    private TaskStopBatchReader() {}

    static final class Snapshot {
        final String parentId,parentRun;final int parentEpoch;final JSONArray children;
        Snapshot(String parentId,String parentRun,int parentEpoch,JSONArray children){
            this.parentId=parentId;this.parentRun=parentRun;this.parentEpoch=parentEpoch;this.children=children;
        }
    }

    static JSONArray children(SQLiteDatabase db,String id,String unavailable)throws Exception {
        return read(db,id,false,unavailable).children;
    }

    static Snapshot unknown(SQLiteDatabase db,String id,String unavailable)throws Exception {
        return read(db,id,true,unavailable);
    }

    private static Snapshot read(SQLiteDatabase db,String id,boolean requireUnknown,String unavailable)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            Snapshot result;
            // State predicate, parent identity and body chunks must refer to one
            // original ticket snapshot. IMMEDIATE can briefly reserve the writer;
            // all actual claims and parent/child authority checks remain callers'.
            String sql="SELECT parent_id,parent_run,parent_epoch FROM phone_stop_batches WHERE batch_id=?"
                    +(requireUnknown?" AND state='unknown'":"");
            try(Cursor row=db.rawQuery(sql,new String[]{id})){
                if(!row.moveToFirst())throw new SecurityException(unavailable);
                JSONArray children=new JSONArray(TaskStoreTextReader.read(db,TaskStoreTextReader.Column.PHONE_STOP_CHILDREN,id));
                result=new Snapshot(row.getString(0),row.getString(1),row.getInt(2),children);
            }
            if(owns)db.setTransactionSuccessful();return result;
        }finally{if(owns)db.endTransaction();}
    }
}
