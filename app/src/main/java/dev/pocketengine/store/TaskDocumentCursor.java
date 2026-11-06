package dev.pocketengine.store;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashMap;
import java.util.Map;

/** Bounded body transfer with explicit snapshot ownership.
 * Cursor factories chunk only inside a caller-owned transaction; otherwise they
 * preserve the original SQL. The unique-key readTask materializer may own a short
 * transaction that ends before returning. Neither path creates a transaction
 * spanning caller business side effects. */
public final class TaskDocumentCursor extends CursorWrapper {
    private final SQLiteDatabase db;
    private final int taskColumn,checkpointColumn;
    private final Map<Integer,String> bodies=new HashMap<>();
    private int cachedPosition=-1;
    private boolean closed;

    private TaskDocumentCursor(SQLiteDatabase db,Cursor cursor,int taskColumn,int checkpointColumn){
        super(cursor);this.db=db;this.taskColumn=taskColumn;this.checkpointColumn=checkpointColumn;
    }
    /** chunkSql substitutes task_id AS document only; originalSql preserves the original nontransactional path. */
    public static Cursor tasks(SQLiteDatabase db,String originalSql,String chunkSql,String[] args,int taskColumn){return open(db,originalSql,chunkSql,args,taskColumn,-1);}
    /** Both selected body identities are reread inside the same original transaction. */
    public static Cursor tasksAndCheckpoints(SQLiteDatabase db,String originalSql,String chunkSql,String[] args,int taskColumn,int checkpointColumn){return open(db,originalSql,chunkSql,args,taskColumn,checkpointColumn);}
    private static Cursor open(SQLiteDatabase db,String originalSql,String chunkSql,String[] args,int taskColumn,int checkpointColumn){
        if(!db.inTransaction())return db.rawQuery(originalSql,args);
        return new TaskDocumentCursor(db,db.rawQuery(chunkSql,args),taskColumn,checkpointColumn);
    }
    /** Materialize only one task while selection and chunks share a snapshot.
     * End our read transaction before callers parse JSON or perform maintenance.
     * Existing caller-owned transactions are neither committed nor ended here.
     *
     * Android's non-exclusive transaction is IMMEDIATE: it can briefly reserve
     * the writer and wait for another connection. Start it BEFORE selecting the
     * identity so another writer cannot change/delete the body between chunks.
     * Restrict ownership to this unique-key read, never an arbitrary scan whose
     * caller may maintain screenshots or perform other independently committed work.
     * Chunking bounds CursorWindow transfer, not the final String's heap size. */
    public static String readTask(SQLiteDatabase db,String taskId){
        boolean ownsTransaction=!db.inTransaction();
        if(ownsTransaction)db.beginTransactionNonExclusive();
        try{
            String body;
            try(Cursor row=new TaskDocumentCursor(db,db.rawQuery(
                    "SELECT task_id AS document FROM tasks WHERE task_id=?",
                    new String[]{taskId}),0,-1)){
                body=row.moveToFirst()?row.getString(0):null;
            }
            if(ownsTransaction)db.setTransactionSuccessful();
            // Java runs finally before delivering the return value. If ending the
            // snapshot fails, do not expose a body as a successfully completed read.
            return body;
        }finally{
            if(ownsTransaction)db.endTransaction();
        }
    }
    @Override public String getString(int column){
        if(column!=taskColumn&&column!=checkpointColumn)return super.getString(column);
        int position=getPosition();if(position!=cachedPosition){bodies.clear();cachedPosition=position;}
        if(!bodies.containsKey(column))loadRow();
        return bodies.get(column);
    }
    /** Selection and mapped bodies must remain in the active transaction.
     * This cursor never changes transaction ownership: its factory's caller or
     * readTask controls the lifetime, so maintenance cannot leak into a new scope. */
    private void loadRow(){
        if(!db.inTransaction())throw new IllegalStateException("原正文读取事务已结束，未混读另一快照");
        Map<Integer,String> loaded=new HashMap<>();
        String task=super.getString(taskColumn);loaded.put(taskColumn,task==null?null:readBody(task,true));
        if(checkpointColumn>=0){String run=super.getString(checkpointColumn);loaded.put(checkpointColumn,run==null?null:readBody(run,false));}
        bodies.putAll(loaded);
    }
    @Override public byte[] getBlob(int column){
        if(column!=taskColumn&&column!=checkpointColumn)return super.getBlob(column);
        throw new UnsupportedOperationException("映射正文列只通过getString读取，不返回定位ID或猜测字节编码");
    }
    @Override public void copyStringToBuffer(int column,android.database.CharArrayBuffer buffer){
        if(column!=taskColumn&&column!=checkpointColumn){super.copyStringToBuffer(column,buffer);return;}
        String body=getString(column);if(body==null){buffer.sizeCopied=0;return;}
        if(buffer.data==null||buffer.data.length<body.length())buffer.data=new char[body.length()];
        body.getChars(0,body.length(),buffer.data,0);buffer.sizeCopied=body.length();
    }
    private String readBody(String identity,boolean task){
        return TaskStoreTextReader.read(db,task?TaskStoreTextReader.Column.TASK_DOCUMENT:TaskStoreTextReader.Column.RUN_CHECKPOINT,identity);
    }
    @Override public void close(){if(closed)return;closed=true;try{super.close();}finally{bodies.clear();}}
}
