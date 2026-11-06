package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Rebuildable search projection, never an authorization or task-content authority. */
final class TaskIdentityIndex {
    private TaskIdentityIndex() {}

    static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS task_identity_index(task_id TEXT PRIMARY KEY,feature TEXT NOT NULL,external_id TEXT NOT NULL,operation_id TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS task_identity_external ON task_identity_index(feature,external_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS task_identity_operation ON task_identity_index(feature,operation_id)");
        // Any legacy/direct writer invalidates the cache atomically. Absence means
        // unknown and must remain searchable, never 'this task has no identity'.
        db.execSQL("CREATE TRIGGER IF NOT EXISTS task_identity_changed AFTER UPDATE OF document,task_id ON tasks BEGIN DELETE FROM task_identity_index WHERE task_id=OLD.task_id OR task_id=NEW.task_id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS task_identity_deleted AFTER DELETE ON tasks BEGIN DELETE FROM task_identity_index WHERE task_id=OLD.task_id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS task_identity_inserted AFTER INSERT ON tasks BEGIN DELETE FROM task_identity_index WHERE task_id=NEW.task_id; END");
    }

    static void sync(SQLiteDatabase db,String id,JSONObject task) {
        if(!db.inTransaction())throw new IllegalStateException("任务身份投影必须与原正文同事务");
        // Never truncate identifiers to make them indexable. Unusually large
        // identities remain unknown and take the authoritative chunk-read path.
        if(!indexable(task)){db.delete("task_identity_index","task_id=?",new String[]{id});return;}
        ContentValues row=new ContentValues();row.put("task_id",id);row.put("feature",task.optString("feature"));
        row.put("external_id",task.optString("externalId"));row.put("operation_id",task.optString("operationId"));
        if(db.insertWithOnConflict("task_identity_index",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("任务身份投影写入失败");
    }

    private static boolean indexable(JSONObject task) {
        return task.optString("feature").length()<=4096
                &&task.optString("externalId").length()<=4096
                &&task.optString("operationId").length()<=4096;
    }

    /** Only pure identity lookups use this scope; callers' dispatch and permission
     * checks run after it ends. The IMMEDIATE snapshot can reserve the writer
     * for the legacy fallback scan; it is not a lock-free indexed-only lookup.
     * At most 32 visited old rows are populated per call, while all
     * remaining old rows participate in the same query, so partial migration can
     * neither hide a task nor grant authority from an unverified projection. */
    static String find(SQLiteDatabase db,String feature,String identity,boolean operation)throws Exception {
        return find(db,feature,identity,operation,null,null);
    }

    /** Remote job IDs are only meaningful within their original profile/service.
     * The projection narrows candidates, but scope is always checked against the
     * same-snapshot document, never inferred from the unscoped identity index. */
    static String findScopedExternal(SQLiteDatabase db,String feature,String identity,String profile,String service)throws Exception {
        if(profile==null||profile.isEmpty()||service==null)throw new IllegalArgumentException("缺少原远端查询作用域");
        return find(db,feature,identity,false,profile,service);
    }

    private static String find(SQLiteDatabase db,String feature,String identity,boolean operation,String profile,String service)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            Map<String,JSONObject> pending=new LinkedHashMap<>();
            String field=operation?"operation_id":"external_id";
            String result=null;
            // No writes while this selection cursor is alive. Selected identities
            // and chunked bodies share the transaction, including legacy fallback.
            try(Cursor rows=db.rawQuery("SELECT t.task_id,i.task_id IS NULL FROM tasks t LEFT JOIN task_identity_index i ON i.task_id=t.task_id WHERE i.task_id IS NULL OR (i.feature=? AND i."+field+"=?) ORDER BY t.updated_at DESC",new String[]{feature,identity})) {
                while(rows.moveToNext()) {
                    String body=TaskDocumentCursor.readTask(db,rows.getString(0));
                    if(body==null)throw new IllegalStateException("同快照原任务缺失");
                    JSONObject task=new JSONObject(body);
                    if(rows.getInt(1)!=0&&pending.size()<32&&indexable(task))pending.put(rows.getString(0),
                            new JSONObject().put("feature",task.optString("feature"))
                                    .put("externalId",task.optString("externalId"))
                                    .put("operationId",task.optString("operationId")));
                    // Always reread authoritative content; projections never replace
                    // owner/source checks or supply a dispatch target themselves.
                    if(feature.equals(task.optString("feature"))&&identity.equals(task.optString(operation?"operationId":"externalId"))
                            &&matchesScope(task,profile,service)) {result=task.getString("id");break;}
                }
            }
            // Backfill only successfully parsed rows already visited in the original
            // search order. Pre-reading an arbitrary old row could turn an unrelated
            // damaged document into a new blocker for a newer valid match.
            // Do not retain whole documents, and close the selection before writes.
            for(Map.Entry<String,JSONObject> entry:pending.entrySet())sync(db,entry.getKey(),entry.getValue());
            if(owns)db.setTransactionSuccessful();return result;
        } finally {if(owns)db.endTransaction();}
    }

    /** Preserve the original operation-receipt-first identity lookup. Only row
     * metadata enters CursorWindow; receipt text uses the shared chunk transport.
     * This is an internal locator, not authorization to read or control the task.
     * Empty service retains the original profile-only matching convention. */
    static String findRemoteReceipt(SQLiteDatabase db,String identity,String profile,String service)throws Exception {
        boolean owns=!db.inTransaction();if(owns)db.beginTransactionNonExclusive();
        try {
            String result=null;
            // IMMEDIATE pins the selected operation and all receipt chunks; it
            // may reserve the writer for the complete receipt scan. Do not run
            // remote calls, maintenance or the task fallback inside this scope.
            try(Cursor rows=db.rawQuery("SELECT operation_id,task_id FROM operations WHERE tool='remote_job' AND receipt IS NOT NULL ORDER BY created_at DESC",null)){
                while(rows.moveToNext()){
                    String text=TaskStoreTextReader.read(db,TaskStoreTextReader.Column.OPERATION_RECEIPT,rows.getString(0));
                    if(text==null)throw new IllegalStateException("同快照远端回执缺失");
                    JSONObject receipt=new JSONObject(text);
                    if(identity.equals(receipt.optString("id"))
                            &&(profile==null||profile.equals(receipt.optString("profileId"))
                            &&(service.isEmpty()||service.equals(receipt.optString("serviceBase"))))){
                        result=rows.getString(1);break;
                    }
                }
            }
            if(owns)db.setTransactionSuccessful();return result;
        }finally{if(owns)db.endTransaction();}
    }

    private static boolean matchesScope(JSONObject task,String profile,String service) {
        if(profile==null)return true; // Unscoped legacy API keeps its original contract.
        // Empty service deliberately preserves the original profile-only lookup;
        // nonempty service must match the saved serviceBase exactly.
        JSONObject refs=task.optJSONObject("externalRefs"),evidence=task.optJSONObject("evidence");
        return refs!=null&&profile.equals(refs.optString("profileId"))
                &&(service.isEmpty()||evidence!=null&&service.equals(evidence.optString("serviceBase")));
    }
}
