package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original journal headers only, for private owner review after uncertain physical phone actions.
 * No screen, input, coordinates, recipient, package, operation argument or receipt body is read here. */
public final class PhoneActionRecovery {
    public static final String KEY="privatePhoneUnconfirmed",FORMAT="pocketworld.owner-phone-unconfirmed.v1";
    private static final int LIMIT=12;
    private static final Set<String> TOOLS=Set.of("phone_launch_app","phone_click_node","phone_click_text","phone_tap","phone_type",
            "phone_swipe","phone_scroll_node","phone_scroll_region","phone_back","phone_home","phone_gesture","phone_press_key","phone_set_wheel","phone_force_stop_app","phone_restart_app");
    // Fixed native constants, never derived from input or a requested tool.
    private static final String FILTER="o.tool IN ('"+String.join("','",TOOLS)+"')";
    private static final String SQL="SELECT o.operation_id,o.tool,o.state,o.run_id,o.created_at,r.epoch,o.rowid,r.status FROM operations o LEFT JOIN runs r ON r.task_id=o.task_id AND r.run_id=o.run_id WHERE o.task_id=? AND ";
    private PhoneActionRecovery() {}
    public static boolean phoneTool(String name){return TOOLS.contains(name);}
    public static boolean local(JSONObject task){
        return "phone".equals(task.optString("kind"))&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))
                &&task.isNull("origin")&&task.isNull("parentTaskId")&&task.isNull("ownerPhoneControl")&&task.isNull("ownerDeviceWork")
                &&Set.of("main_screen","background").contains(task.optString("executionMode"))&&!task.optString("runId").isEmpty()&&task.optInt("coordinatorEpoch")>0;
    }
    static final String INTERRUPTED="originalInterruptedPhoneAttempt";
    /** Detail-only, from the same run journal recovery settles. Paused task status alone
     * proves neither a surviving controller nor an interrupted run. Never read a checkpoint. */
    static JSONObject interruptedAttempt(SQLiteDatabase db,String id,JSONObject task)throws Exception{
        if(!"paused".equals(task.optString("status"))||!local(task)||!id.equals(task.optString("id"))||task.optBoolean("cancelRequested")
                ||task.has(dev.pocketengine.phone.MainPhoneOwnerStep.KEY)||dev.pocketengine.phone.MainPhoneOwnerStep.needsOwner(task)
                ||!task.isNull("ownerPhoneQuestionHold"))return null;
        String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        try(Cursor original=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",
                new String[]{id,run,Integer.toString(epoch)})){
            if(!original.moveToFirst()||!"interrupted".equals(original.getString(0)))return null;
            return new JSONObject().put("format","pocketworld.private-phone-interrupted.v1").put("taskId",id)
                    .put("runId",run).put("epoch",epoch).put("mode",task.getString("executionMode")).put("runStatus","interrupted");
        }catch(android.database.sqlite.SQLiteException unavailable){return null;}
    }
    private static String safeStatus(String value){return value!=null&&value.matches("running|preparing|paused|completed|cancelled|stopped|failed|awaiting_user|needs_confirmation|interrupted|reconciling")?value:"recorded";}
    private static String safeOperationState(String value){return value!=null&&value.matches("prepared|unknown|receipt|resolved|failed")?value:"recorded";}
    /** Status of the exact retained run for an explicit notification open. No goals, observation data, arguments or receipt bodies. */
    static JSONObject notificationRun(SQLiteDatabase db,JSONObject task,String run,int epoch)throws Exception{
        String id=task.getString("id");boolean current=run.equals(task.optString("runId"))&&epoch==task.optInt("coordinatorEpoch");
        if(!local(task)){
            if(!current||!"phone".equals(task.optString("kind")))throw new SecurityException("原非私人通知轮次不再可打开");
            // Existing group, channel and dedicated controls keep their current-only route.
            return new JSONObject().put("conventional",true).put("taskId",id);
        }
        try(Cursor c=db.rawQuery("SELECT status,created_at,updated_at FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,run,Integer.toString(epoch)})){
            if(!c.moveToFirst()||c.getLong(1)<=0||c.getLong(2)<=0)throw new SecurityException("原通知对应的本机执行记录不可用");
            JSONObject out=new JSONObject().put("format","pocketworld.private-phone-notification.v1").put("taskId",id).put("runId",run).put("epoch",epoch)
                    .put("current",current).put("status",safeStatus(c.getString(0))).put("createdAt",c.getLong(1)).put("updatedAt",c.getLong(2)).put("readAt",System.currentTimeMillis());
            try(Cursor op=db.rawQuery("SELECT o.tool,o.state,o.created_at FROM operations o WHERE o.task_id=? AND o.run_id=? AND "+FILTER+" ORDER BY o.rowid DESC LIMIT 1",new String[]{id,run})){
                if(op.moveToFirst()&&op.getLong(2)>0)out.put("lastAction",new JSONObject().put("tool",op.getString(0)).put("state",safeOperationState(op.getString(1))).put("createdAt",op.getLong(2)));
            }return out;
        }
    }
    private static JSONObject row(Cursor c,JSONObject task)throws Exception{
        String state=c.getString(2),run=c.getString(3);int epoch=c.isNull(5)?0:c.getInt(5);
        return new JSONObject().put("format",FORMAT).put("taskId",task.getString("id")).put("operationId",c.getString(0)).put("tool",c.getString(1))
                .put("state",state).put("runId",run==null?"":run).put("epoch",epoch).put("createdAt",c.getLong(4)).put("order",c.getLong(6))
                .put("canRecord","unknown".equals(state)&&run!=null&&!run.isEmpty()&&epoch>0&&c.getLong(4)>0&&!c.isNull(7)).put("automaticReplay",false);
    }
    private static JSONObject adjacent(SQLiteDatabase db,JSONObject task,JSONObject original,boolean before)throws Exception{
        String comparator=before?"<":">",order=before?"DESC":"ASC";
        try(Cursor c=db.rawQuery(SQL+"o.run_id=? AND o.rowid"+comparator+"? AND "+FILTER+" ORDER BY o.rowid "+order+" LIMIT 1",
                new String[]{task.getString("id"),original.getString("runId"),Long.toString(original.getLong("order"))})){
            if(!c.moveToFirst()||c.isNull(5)||c.getInt(5)!=original.getInt("epoch"))return null;
            // Only neighboring journal checkpoints, not claims that those actions took effect.
            return new JSONObject().put("tool",c.getString(1)).put("createdAt",c.getLong(4)).put("state",c.getString(2));
        }
    }
    static JSONObject page(SQLiteDatabase db,JSONObject task)throws Exception{
        if(!local(task))throw new SecurityException("只在原私人手机工作查看未确认动作");
        boolean own=!db.inTransaction();if(own)db.beginTransactionNonExclusive();
        try{
            JSONArray rows=new JSONArray();boolean more=false;
            // The latest 24 *all* operations may be reads. Select the unsettled original journal directly.
            try(Cursor c=db.rawQuery(SQL+FILTER+" AND o.state IN ('prepared','unknown') ORDER BY CASE o.state WHEN 'unknown' THEN 0 ELSE 1 END,o.rowid LIMIT ?",
                    new String[]{task.getString("id"),Integer.toString(LIMIT+1)})){
                while(c.moveToNext()){
                    if(rows.length()==LIMIT){more=true;break;}
                    JSONObject item=row(c,task);
                    if(item.optBoolean("canRecord"))item.put("before",adjacent(db,task,item,true)).put("after",adjacent(db,task,item,false));
                    rows.put(item);
                }
            }
            JSONObject out=new JSONObject().put("format",FORMAT).put("taskId",task.getString("id")).put("reviewRun",task.getString("runId"))
                    .put("reviewEpoch",task.getInt("coordinatorEpoch")).put("rows",rows).put("hasMore",more).put("automaticReplay",false);
            if(own)db.setTransactionSuccessful();return out;
        }finally{if(own)db.endTransaction();}
    }
    /** Re-read the exact original operation in the caller's UPDATE transaction. No durable read token is a grant. */
    static String checkedRun(SQLiteDatabase db,JSONObject task,String operation,JSONObject selection)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("原动作核对需要同一事务");
        if(!local(task)){if(selection!=null)throw new SecurityException("不是原私人手机工作");return null;}
        try(Cursor c=db.rawQuery(SQL+"o.operation_id=? AND "+FILTER,new String[]{task.getString("id"),operation})){
            if(!c.moveToFirst()){if(selection!=null)throw new SecurityException("原动作归属已变化");return null;}
            JSONObject actual=row(c,task);
            if(selection==null||!actual.getBoolean("canRecord")||!FORMAT.equals(selection.optString("format"))||!task.getString("runId").equals(selection.optString("reviewRun"))||
                    task.getInt("coordinatorEpoch")!=selection.optInt("reviewEpoch")||!task.getString("id").equals(selection.optString("taskId")))
                throw new SecurityException("尚未核对原手机动作，或原回执已更新。请重新打开，不会重放");
            for(String key:new String[]{"operationId","tool","runId","epoch","createdAt","order","state"})
                if(!actual.get(key).toString().equals(selection.optString(key)))throw new SecurityException("这不是刚查看的那笔未知动作，没有记录到其他轮次");
            return actual.getString("runId");
        }
    }
}
