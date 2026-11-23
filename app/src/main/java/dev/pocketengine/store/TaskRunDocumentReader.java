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

    /** Same latest-epoch selection as startAttempt. Inspect one bounded original only for
     * a readable, stopped private work detail. No message text or grant leaves this read. */
    static String resumeHistoryForUi(SQLiteDatabase db,String task,String run,int epoch) {
        if(Thread.currentThread().isInterrupted())return "unverified";
        try(Cursor row=db.rawQuery("SELECT run_id,epoch,CASE WHEN length(CAST(checkpoint AS BLOB))<=65536 THEN checkpoint END FROM runs WHERE task_id=? ORDER BY epoch DESC LIMIT 1",new String[]{task})){
            if(!row.moveToFirst()||!run.equals(row.getString(0))||epoch!=row.getInt(1)||row.isNull(2))return "unverified";
            JSONObject saved=new JSONObject(row.getString(2));
            if(!saved.has("messages"))return "goal_only";
            org.json.JSONArray messages=saved.optJSONArray("messages");
            return messages==null?"unverified":messages.length()==0?"goal_only":"saved_history";
        }catch(android.database.sqlite.SQLiteException|org.json.JSONException unavailable){return "unverified";}
    }

    /** A later interrupted run must exist beside its immediate predecessor in
     * this same original Work journal. This does not attest dispatch or completion. */
    static JSONObject interruptedOwnerWorkRoundForUi(SQLiteDatabase db,JSONObject task) {
        if(Thread.currentThread().isInterrupted()||!OwnerWorkTask.isOwnerWork(task)
                ||!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))
                ||!"assistant".equals(task.opt("kind"))||!"capabilities".equals(task.opt("executionMode"))
                ||!"interrupted".equals(task.opt("status"))||!Boolean.TRUE.equals(task.opt("contextAvailable"))
                ||!Boolean.FALSE.equals(task.opt("taskCoreReadLimited"))||task.optBoolean("metadataLimited")
                ||task.optBoolean("contextWithdrawn")||task.optBoolean("sourceCurrentReadRequired"))return null;
        for(String key:new String[]{"origin","parentTaskId","teamRunId","teamAssignment","teamWriteScope","memberId",
                "developmentContext","ownerPhoneControl","ownerDeviceWork","deviceExecutionScope","workProfileRef","workProfileIntent",
                "ownerWorkPreparation","ownerResultHandling","ownerWorkTakeover","projectMemoryRead","sourceLearningJob"})
            if(!task.isNull(key))return null;
        String id=task.optString("id"),run=task.optString("runId"),conversation=task.optString("conversationId");
        JSONObject work=task.optJSONObject("ownerWork");
        int epoch=task.optInt("coordinatorEpoch");
        if(id.isBlank()||run.isBlank()||conversation.isBlank()||epoch<2||!id.equals(work.opt("workTaskId"))
                ||!conversation.equals(work.opt("conversationId")))return null;
        // UNIQUE(task_id, epoch) bounds both the predecessor and intervening-run checks.
        String sql="SELECT 1 FROM runs live JOIN runs prior ON prior.task_id=live.task_id AND prior.epoch=live.epoch-1 "+
                "WHERE live.task_id=? AND live.run_id=? AND live.epoch=? AND live.status='interrupted' AND prior.run_id<>live.run_id "+
                "AND NOT EXISTS (SELECT 1 FROM runs later WHERE later.task_id=live.task_id AND later.epoch>live.epoch) LIMIT 1";
        try(Cursor row=db.rawQuery(sql,new String[]{id,run,Integer.toString(epoch)})){
            return row.moveToFirst()?new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch):null;
        }catch(android.database.sqlite.SQLiteException|org.json.JSONException unavailable){return null;}
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
