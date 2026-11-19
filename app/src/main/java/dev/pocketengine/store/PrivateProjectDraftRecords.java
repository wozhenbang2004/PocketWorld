package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.local.project.ProjectEditDraft;
import org.json.JSONObject;

/** At most one fixed pair of texts per explicit private draft task. Never an operation or a checkout. */
final class PrivateProjectDraftRecords {
    private PrivateProjectDraftRecords(){}
    private static void schema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS private_project_edit_drafts(task_id TEXT PRIMARY KEY,draft_id TEXT NOT NULL,body TEXT NOT NULL)");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS retire_private_project_edit_draft AFTER DELETE ON tasks BEGIN DELETE FROM private_project_edit_drafts WHERE task_id=OLD.task_id; END");
    }
    static JSONObject put(SQLiteDatabase db,JSONObject draft)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("草稿与原任务回执必须同事务保存");schema(db);
        String id=draft.getString("taskId"),pin=draft.getString("draftId");
        try(Cursor row=db.rawQuery("SELECT draft_id FROM private_project_edit_drafts WHERE task_id=?",new String[]{id})){
            if(row.moveToFirst()){if(!pin.equals(row.getString(0)))throw new SecurityException("这项交办已有独立草稿，未替换原结果");return read(db,id,pin);}
        }
        ContentValues values=new ContentValues();values.put("task_id",id);values.put("draft_id",pin);values.put("body",draft.toString());
        db.insertOrThrow("private_project_edit_drafts",null,values);return read(db,id,pin);
    }
    static JSONObject read(SQLiteDatabase db,String task,String pin)throws Exception{
        try(Cursor row=db.rawQuery("SELECT body FROM private_project_edit_drafts WHERE task_id=? AND draft_id=? AND length(CAST(body AS BLOB))<=600000",new String[]{task,pin})){
            if(!row.moveToFirst())throw new SecurityException("原改稿尚无可读取的落地回执");JSONObject draft=ProjectEditDraft.verified(new JSONObject(row.getString(0)));
            if(!task.equals(draft.opt("taskId"))||!pin.equals(draft.opt("draftId")))throw new SecurityException("原交办草稿已变化");return draft;
        }
    }
}
