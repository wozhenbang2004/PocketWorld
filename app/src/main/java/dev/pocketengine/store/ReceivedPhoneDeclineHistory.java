package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import org.json.JSONObject;

/** Receiving owner's local decision; a generic cancelled/failed status or a missing sender is not a decision. */
final class ReceivedPhoneDeclineHistory {
    static final String EVENT="device_pending_goal_cancelled_by_local_owner";
    private ReceivedPhoneDeclineHistory(){}
    static long checkedAt(SQLiteDatabase db,String task,String caller,String op,String here,String instance,String mode,String wire,long acceptedAt,JSONObject headerOrigin)throws Exception{
        try(Cursor source=db.rawQuery("SELECT CASE WHEN length(CAST(document AS BLOB))<="+TaskAuditStore.MAX_CORE_BYTES+" THEN document END FROM tasks WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM runs WHERE task_id=?)",new String[]{task,task})){
            if(!source.moveToFirst()||source.isNull(0))throw new IllegalStateException("原未启动决定无法完整核对");
            JSONObject full=new JSONObject(source.getString(0)),origin=full.getJSONObject("origin"),scope=full.getJSONObject("deviceExecutionScope"),proof=full.getJSONObject("evidence");
            if(!task.equals(full.opt("id"))||!"cancelled".equals(full.opt("status"))||!Boolean.TRUE.equals(full.opt("cancelRequested"))||
                    !full.optString("runId").isEmpty()||full.optInt("coordinatorEpoch",-1)!=0||scope.getLong("toolsUsed")!=0||scope.getLong("modelCallsUsed")!=0||
                    !mode.equals(DeviceProtocol.receivedPhoneScopeMode(full))||!instance.equals(full.opt("deviceNativeInstanceId"))||!here.equals(origin.opt("targetDeviceId"))||
                    !caller.equals(origin.opt("callerDeviceId"))||!caller.equals(origin.opt("originDeviceId"))||!op.equals(origin.opt("remoteOperationId"))||
                    !DeviceProtocol.canonical(origin).equals(DeviceProtocol.canonical(headerOrigin))||!Boolean.TRUE.equals(proof.opt("nativePendingGoalCancelled"))||
                    !Boolean.TRUE.equals(proof.opt("notDispatched"))||!Boolean.TRUE.equals(proof.opt("localOnly"))||!Boolean.FALSE.equals(proof.opt("quotaRefunded"))||
                    !op.equals(proof.opt("originalOperationId"))||!wire.equals(proof.opt("argumentsDigest"))||acceptedAt!=proof.optLong("acceptedAt",-1))
                throw new IllegalStateException("不能将其他取消或中断作为本机不接手的决定");
        }
        try(Cursor records=db.rawQuery("SELECT CASE WHEN body_bytes BETWEEN 1 AND 4096 AND length(CAST(event_json AS BLOB))<=4096 THEN event_json END FROM task_events WHERE task_id=? AND event_type=? ORDER BY event_order LIMIT 2",new String[]{task,EVENT})){
            if(!records.moveToFirst()||records.isNull(0))throw new IllegalStateException("本机未保存可核对的原决定事件");
            JSONObject e=new JSONObject(records.getString(0));long at=e.optLong("at");
            if(records.moveToNext()||!EVENT.equals(e.opt("type"))||!op.equals(e.opt("originalOperationId"))||!Boolean.FALSE.equals(e.opt("quotaRefunded"))||at<=0)
                throw new IllegalStateException("本机决定事件与原派单不一致");
            return at;
        }
    }
}
