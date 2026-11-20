package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;
import dev.pocketengine.features.local.project.OwnerRevisionSource;

/** Owner follow-on texts. Neither the immutable model result nor a checkout file is an update target. */
final class PrivateProjectDraftRevisions {
    static final int LIMIT=8,MAX_BYTES=24576;
    private PrivateProjectDraftRevisions(){}
    private static SecurityException changed(){return new SecurityException("这份独立草稿的原件或前一版已变化，没有覆盖任何稿件");}
    private static byte[] bytes(String text)throws Exception{
        if(text==null||text.indexOf(0)>=0||text.length()>MAX_BYTES)throw new IllegalArgumentException("只保留完整的 UTF-8 小稿（最多 24 KiB），没有截断正文");
        var b=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));if(b.remaining()>MAX_BYTES)throw new IllegalArgumentException("完整改稿超过 24 KiB，未保存");
        byte[] out=new byte[b.remaining()];b.get(out);return out;
    }
    static JSONObject base(JSONObject root)throws Exception{
        if(!root.optBoolean("available")||!root.getString("draftId").matches("[a-f0-9]{64}")||(root.getInt("afterBytes")!=bytes(root.getString("after")).length||!root.getString("afterSha256").equals(ProjectSources.sha256(bytes(root.getString("after"))))))throw changed();
        return new JSONObject().put("taskId",root.getString("taskId")).put("draftId",root.getString("draftId")).put("revisionId",root.getString("draftId"))
            .put("path",root.getString("path")).put("ordinal",0).put("bytes",root.getInt("afterBytes")).put("sha256",root.getString("afterSha256"))
            .put("text",root.getString("after")).put("sourceChanged",false).put("executionAuthorized",false);
    }
    static JSONObject next(JSONObject previous,String text)throws Exception{
        int ordinal=previous.getInt("ordinal")+1;if(ordinal>LIMIT)throw new IllegalStateException("这份原稿已保留八个后续版本，没有替换旧稿");
        byte[] payload=bytes(text);String sha=ProjectSources.sha256(payload);if(sha.equals(previous.getString("sha256")))throw new IllegalArgumentException("没有文字变化，未另存重复草稿");
        JSONObject id=identity(previous.getString("taskId"),previous.getString("draftId"),previous.getString("path"),previous.getString("revisionId"),ordinal,payload.length,sha);
        return id.put("revisionId",ProjectSources.digest(id)).put("text",text).put("sourceChanged",false).put("executionAuthorized",false);
    }
    private static JSONObject identity(String task,String draft,String path,String parent,int ordinal,int size,String sha)throws Exception{
        return new JSONObject().put("format","pocketworld.private-project-owner-draft.v1").put("draftId",draft).put("taskId",task)
            .put("path",path).put("parentId",parent).put("ordinal",ordinal).put("bytes",size).put("sha256",sha);
    }
    /** The original revision ID commits its exact hash and length. Confirm the published copy
     * against that persisted locator, without selecting the revision's text column. */
    static void requireRetainedCopy(SQLiteDatabase db,String task,String draft,String path,JSONObject ref)throws Exception{
        String revision=ref.getString("revisionId"),sha=ref.getString("sha256");
        int ordinal=TaskExecutionScope.exactInt(ref,"ordinal",1,LIMIT),size=TaskExecutionScope.exactInt(ref,"bytes",0,MAX_BYTES);
        if(!revision.matches("[a-f0-9]{64}")||revision.equals(draft)||!sha.matches("[a-f0-9]{64}"))throw changed();
        try(Cursor r=db.rawQuery("SELECT parent_id,ordinal FROM private_project_owner_draft_revisions WHERE task_id=? AND revision_id=?",new String[]{task,revision})){
            if(!r.moveToFirst()||r.getInt(1)!=ordinal||!revision.equals(ProjectSources.digest(identity(task,draft,path,r.getString(0),ordinal,size,sha)))||r.moveToNext())throw changed();
        }
    }
    private static void schema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS private_project_owner_draft_revisions(task_id TEXT NOT NULL,ordinal INTEGER NOT NULL,revision_id TEXT NOT NULL,parent_id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(task_id,ordinal),UNIQUE(task_id,revision_id),UNIQUE(task_id,parent_id))");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS retire_private_project_owner_draft_revisions AFTER DELETE ON tasks BEGIN DELETE FROM private_project_owner_draft_revisions WHERE task_id=OLD.task_id; END");
    }
    static JSONObject head(SQLiteDatabase db,JSONObject root)throws Exception{
        return read(db,root,null,null);
    }
    static JSONArray choices(SQLiteDatabase db,JSONObject root)throws Exception{JSONArray out=new JSONArray();read(db,root,null,out);return out;}
    static JSONObject exact(SQLiteDatabase db,JSONObject root,JSONObject requested)throws Exception{return read(db,root,OwnerRevisionSource.pin(requested),null);}
    private static JSONObject read(SQLiteDatabase db,JSONObject root,JSONObject requested,JSONArray choices)throws Exception{
        JSONObject prior=base(root);String task=root.getString("taskId");
        if(requested!=null&&(!task.equals(requested.getString("taskId"))||!root.getString("draftId").equals(requested.getString("draftId"))))throw changed();
        try(Cursor present=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='private_project_owner_draft_revisions'",null)){if(!present.moveToFirst()){if(requested!=null)throw changed();return prior;}}
        try(Cursor rows=db.rawQuery("SELECT ordinal,revision_id,parent_id,CASE WHEN length(CAST(body AS BLOB))<=160000 THEN body END FROM private_project_owner_draft_revisions WHERE task_id=? ORDER BY ordinal ASC LIMIT 9",new String[]{task})){
            while(rows.moveToNext()){
                if(rows.isNull(3)||!prior.getString("revisionId").equals(rows.getString(2))||rows.getInt(0)!=prior.getInt("ordinal")+1||rows.getInt(0)>LIMIT)throw changed();
                JSONObject stored=new JSONObject(rows.getString(3)),actual=next(prior,stored.getString("text"));
                if(!actual.getString("revisionId").equals(rows.getString(1))||!ProjectSources.canonical(actual).equals(ProjectSources.canonical(stored)))throw changed();
                prior=actual;
                if(choices!=null)choices.put(OwnerRevisionSource.metadata(actual));
                if(requested!=null&&requested.getString("revisionId").equals(rows.getString(1))){if(!requested.getString("sha256").equals(actual.getString("sha256")))throw changed();return actual;}
            }
        }
        if(requested!=null)throw changed();return prior;
    }
    static JSONObject keep(SQLiteDatabase db,JSONObject root,String parent,String text)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("独立稿本需在同一原生事务中留存");
        schema(db);JSONObject head=head(db,root);
        if(!parent.equals(head.getString("revisionId"))){
            // An uncertain acknowledgement may be recovered, never used to replace somebody's newer text.
            if(parent.equals(head.opt("parentId"))&&text.equals(head.opt("text")))return head;
            throw changed();
        }
        JSONObject out=next(head,text);ContentValues v=new ContentValues();v.put("task_id",out.getString("taskId"));v.put("ordinal",out.getInt("ordinal"));
        v.put("revision_id",out.getString("revisionId"));v.put("parent_id",parent);v.put("body",out.toString());
        db.insertOrThrow("private_project_owner_draft_revisions",null,v);JSONObject stored=head(db,root);
        if(!out.getString("revisionId").equals(stored.opt("revisionId"))||!text.equals(stored.opt("text")))throw changed();return stored;
    }
}
