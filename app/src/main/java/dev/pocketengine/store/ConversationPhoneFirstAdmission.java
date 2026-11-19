package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Original private chat submission receipt, never a runnable grant. A newer task headline is not its first attempt. */
final class ConversationPhoneFirstAdmission {
    private ConversationPhoneFirstAdmission() {}
    private record First(String id,String status) {}
    private static final Set<String> STATES=Set.of("preparing","running","completed","cancelled","stopped","failed","paused","awaiting_user","needs_confirmation","interrupted","reconciling");
    private static String key(JSONObject link)throws Exception{
        String digest=link.getString("reviewDigest");
        if(!digest.matches("[a-f0-9]{64}"))throw new SecurityException("原手机启动审阅指纹无效");
        return "private_chat_first_phone_service:"+link.getString("requestId")+":"+digest;
    }
    private static First first(SQLiteDatabase db,String id)throws Exception{
        try(Cursor c=db.rawQuery("SELECT run_id,status FROM runs WHERE task_id=? AND epoch=1 LIMIT 2",new String[]{id})){
            if(!c.moveToFirst())return null;
            String run=c.getString(0),raw=c.getString(1);
            if(run==null||!UUID.fromString(run).toString().equals(run)||c.moveToNext())throw new SecurityException("原首次轮次身份不可核对");
            return new First(run,raw!=null&&STATES.contains(raw)?raw:"recorded");
        }
    }
    private static String proof(SQLiteDatabase db,String key){
        try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key=? AND length(CAST(value AS BLOB)) BETWEEN 36 AND 64",new String[]{key})){
            return c.moveToFirst()?c.getString(0):null;
        }
    }
    /** Only the private admission path records direct results of its one Android service-start call. */
    static void serviceOutcome(SQLiteDatabase db,JSONObject task,String conversation,JSONObject receipt,String state)throws Exception{
        if(!Set.of("admitted","service_denied","component_unconfirmed").contains(state))throw new IllegalArgumentException("不是原服务请求的系统回执");
        JSONObject link=ConversationPhoneHandoff.association(task);String id=task.getString("id");
        First first=first(db,id);
        if(first==null||!conversation.equals(link.getString("conversationId"))||!id.equals(receipt.getString("taskId"))||
                receipt.getInt("epoch")!=1||receipt.getInt("coordinatorEpoch")!=1||!first.id().equals(receipt.getString("runId")))
            throw new SecurityException("系统提交回执不属于本次原私聊的首次轮次");
        String key=key(link),value="admitted".equals(state)?first.id():state+":"+first.id();
        db.execSQL("INSERT OR IGNORE INTO meta(key,value) VALUES(?,?)",new Object[]{key,value});
        if(!value.equals(proof(db,key)))throw new SecurityException("原手机服务请求已有不同记录");
    }
    static JSONObject describe(SQLiteDatabase db,JSONObject task,JSONObject ack)throws Exception{
        JSONObject link=ConversationPhoneHandoff.association(task);String id=task.getString("id");First first=first(db,id);
        if(first==null){
            boolean none=false;
            if(task.optString("runId").isEmpty()&&task.optInt("coordinatorEpoch",-1)==0)
                try(Cursor any=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? LIMIT 1",new String[]{id})){none=!any.moveToFirst();}
            if(none)ack.put("admitted",false);
            return ack.put("admissionState",none?"no_run":"unconfirmed");
        }
        String stamp=proof(db,key(link));
        ack.put("runId",first.id()).put("epoch",1).put("coordinatorEpoch",1).put("status",first.status());
        if(first.id().equals(stamp))return ack.put("admitted",true).put("admissionState","admitted");
        if(("service_denied:"+first.id()).equals(stamp))return ack.put("admitted",false).put("admissionState","service_denied");
        if(("component_unconfirmed:"+first.id()).equals(stamp))return ack.put("admissionState","component_unconfirmed");
        return ack.put("admissionState","run_recorded");
    }
}
