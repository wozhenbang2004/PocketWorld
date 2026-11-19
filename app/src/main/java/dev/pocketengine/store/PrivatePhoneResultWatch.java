package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** One explicitly requested local notice per task, always naming an immutable run. No task/screen text. */
final class PrivatePhoneResultWatch {
    private static final String PREFIX="private_phone_result_watch/";
    private static final long DAY=24L*60*60*1000,GRACE=15L*60*1000;
    private static final Set<String> ENDED=Set.of("completed","needs_confirmation","awaiting_user","failed","cancelled","stopped","interrupted","reconciling","paused");
    private static String key(String task){if(task==null||task.length()>100||task.isEmpty())throw new IllegalArgumentException("无效原任务");return PREFIX+task;}
    private static JSONObject row(SQLiteDatabase db,String id)throws Exception{
        try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key=? AND length(CAST(value AS BLOB))<=1024",new String[]{key(id)})){return c.moveToFirst()&&!c.isNull(0)&&c.getString(0).length()<1024?new JSONObject(c.getString(0)):null;}
    }
    private static boolean same(JSONObject r,String id,String run,int epoch){return r!=null&&id.equals(r.opt("taskId"))&&run.equals(r.opt("runId"))&&epoch==r.optInt("epoch",-1);}
    private static void store(SQLiteDatabase db,String id,JSONObject row)throws Exception{
        ContentValues v=new ContentValues();v.put("key",key(id));v.put("value",row.toString());if(db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("本机提醒选择没有写入原库");
    }
    private static void forget(SQLiteDatabase db,String id){db.delete("meta","key=?",new String[]{key(id)});}
    static List<String> pending(SQLiteDatabase db){
        ArrayList<String> ids=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT key FROM meta WHERE key>=? AND key<? ORDER BY key LIMIT 17",new String[]{PREFIX,"private_phone_result_watch0"})){
            while(c.moveToNext()){String k=c.getString(0);if(k.startsWith(PREFIX)&&k.length()>PREFIX.length())ids.add(k.substring(PREFIX.length()));}}
        return ids;
    }
    static JSONObject choice(SQLiteDatabase db,String id,String run,int epoch,Boolean want,java.util.function.BooleanSupplier current,long now)throws Exception{
        JSONObject prior=row(db,id);boolean exact=same(prior,id,run,epoch),waiting=exact&&"waiting".equals(prior.optString("state"))&&now>=prior.optLong("requestedAt")&&now<prior.optLong("expiresAt");
        if(want!=null){
            if(!current.getAsBoolean())throw new SecurityException("原任务窗口已离开，未更改提醒");
            if(want&&!waiting){
                if(prior!=null&&"waiting".equals(prior.optString("state"))&&now<prior.optLong("expiresAt"))throw new SecurityException("这项工作已有另一轮待交付的本机提醒，不替换它");
                if(prior==null&&pending(db).size()>=16)throw new IllegalStateException("待回报的原任务过多，未登记新的提醒");
                store(db,id,new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch).put("state","waiting").put("requestedAt",now).put("expiresAt",now+DAY));waiting=true;
            }else if(!want&&exact){if(!"waiting".equals(prior.optString("state")))throw new SecurityException("提醒已进入系统交付，不能声称已撤回");forget(db,id);waiting=false;}
        }
        return new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch).put("watching",waiting).put("externalDelivery",false);
    }
    /** Store monitor/transaction is held. Claim is durable before the one OS attempt; never repost after uncertain process death. */
    static JSONObject claim(SQLiteDatabase db,String id,long now)throws Exception{
        JSONObject saved=row(db,id);if(saved==null){forget(db,id);return null;}
        if(!id.equals(saved.opt("taskId"))||!"waiting".equals(saved.optString("state"))||now<saved.optLong("requestedAt")||now>=saved.optLong("expiresAt")){forget(db,id);return null;}
        String run=saved.getString("runId");int epoch=saved.getInt("epoch");
        try(Cursor c=db.rawQuery("SELECT status,updated_at FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,run,String.valueOf(epoch)})){
            if(!c.moveToFirst()){forget(db,id);return null;}
            String status=c.getString(0);long at=c.getLong(1);
            if("running".equals(status))return new JSONObject().put("waiting",true);
            if(c.moveToNext()||!ENDED.contains(status)||at<saved.getLong("requestedAt")||at>now||now-at>GRACE){forget(db,id);return null;}
            // Only after the immutable run actually closed. No task body, evidence, screenshot, model or physical read.
            try(Cursor head=db.rawQuery("SELECT ui_header FROM tasks WHERE task_id=? AND length(CAST(ui_header AS BLOB))<=32768 LIMIT 1",new String[]{id})){
                if(!head.moveToFirst()||head.isNull(0)){forget(db,id);return null;}
                JSONObject h=new JSONObject(head.getString(0));
                if(!id.equals(h.opt("id"))||!"phone".equals(h.opt("kind"))||!"owner".equals(h.opt("actorId"))||!"private".equals(h.opt("spaceId"))||
                        !Boolean.TRUE.equals(h.opt("nativePrivatePhoneResultSource"))){forget(db,id);return null;}
            }
            saved.put("state","claimed");store(db,id,saved);
            return new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch).put("status",status);
        }
    }
    static void retire(SQLiteDatabase db,String id,String run,int epoch)throws Exception{JSONObject live=row(db,id);if(same(live,id,run,epoch)&&"claimed".equals(live.optString("state")))forget(db,id);}
}
