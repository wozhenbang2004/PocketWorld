package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import org.json.JSONObject;

/** Receiver-local fact only. A management request, offline peer, generic cancelled state, and an owner's decline are not proof. */
final class ReceivedPhoneWithdrawal {
    private ReceivedPhoneWithdrawal() {}
    static long recordedAt(SQLiteDatabase db,String task,JSONObject goal,JSONObject headerOrigin,String mode) throws Exception {
        String sender=goal.getString("callerDeviceId"),here=goal.getString("targetDeviceId"),instance=goal.getString("instanceId"),
                original=goal.getString("operationId"),digest=goal.getString("argumentsDigest"),grant=goal.getString("grantId");
        long acceptedEpoch=goal.getLong("authorizationEpoch");
        try(Cursor c=db.rawQuery("SELECT CASE WHEN length(CAST(document AS BLOB))<="+TaskAuditStore.MAX_CORE_BYTES+" THEN document END FROM tasks WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM runs WHERE task_id=?)",new String[]{task,task})){
            if(!c.moveToFirst()||c.isNull(0))throw new IllegalStateException("原任务未启动的状态不可核对");
            JSONObject full=new JSONObject(c.getString(0)),origin=full.getJSONObject("origin"),scope=full.getJSONObject("deviceExecutionScope"),e=full.getJSONObject("evidence");
            if(!task.equals(full.opt("id"))||!"cancelled".equals(full.opt("status"))||!Boolean.TRUE.equals(full.opt("cancelRequested"))
                    ||!full.optString("runId").isEmpty()||full.optInt("coordinatorEpoch",-1)!=0||scope.getLong("toolsUsed")!=0||scope.getLong("modelCallsUsed")!=0
                    ||!mode.equals(DeviceProtocol.receivedPhoneScopeMode(full))||!instance.equals(full.opt("deviceNativeInstanceId"))
                    ||!DeviceProtocol.canonical(origin).equals(DeviceProtocol.canonical(headerOrigin))
                    ||!Boolean.TRUE.equals(e.opt("nativeOperationCancelledBeforeStart"))||e.has("nativePendingGoalCancelled"))
                throw new IllegalStateException("未把机主不接手或运行中停止列为启动前撤回");
        }
        // The original authenticated operation.cancel and actual no-run management operation are committed together.
        // A later repeat is not the first withdrawal. Ordinary grant edits may advance B's epoch; identity rotation may not cross it.
        String sql="SELECT m.remote_operation_id,m.arguments_digest,m.authorization_epoch,m.accepted_at,"
                +"CASE WHEN length(CAST(m.envelope_json AS BLOB))<="+DeviceProtocol.MAX_CONTROL_BYTES+" THEN m.envelope_json END,"
                +"CASE WHEN length(CAST(o.arguments AS BLOB))<=4096 THEN o.arguments END,CASE WHEN length(CAST(o.receipt AS BLOB))<=4096 THEN o.receipt END "
                +"FROM device_inbound m JOIN operations o ON o.operation_id=m.local_operation_id AND o.task_id=m.local_task_id AND o.created_at=m.accepted_at "
                +"WHERE m.local_task_id=? AND m.caller_device_id=? AND m.method='operation.cancel' AND m.grant_id=? AND m.authorization_epoch>=? "
                +"AND o.tool='native_device_operation_cancel' AND o.state='receipt' AND o.run_id='' "
                +"AND NOT EXISTS(SELECT 1 FROM device_identity_rotations r WHERE r.new_device_id=m.caller_device_id AND r.authorization_epoch>? AND r.authorization_epoch<=m.authorization_epoch) "
                +"ORDER BY m.rowid LIMIT 1";
        String epoch=Long.toString(acceptedEpoch);
        try(Cursor c=db.rawQuery(sql,new String[]{task,sender,grant,epoch,epoch})){
            if(!c.moveToFirst()||c.isNull(4)||c.isNull(5)||c.isNull(6)||c.getLong(3)<=0)throw new IllegalStateException("本机没有同一原设备的撤回凭据");
            JSONObject cancel=new JSONObject(c.getString(4)),args=cancel.getJSONObject("arguments");
            if(!"operation.cancel".equals(cancel.opt("method"))||!"DeviceRequest".equals(cancel.opt("messageType"))||!c.getString(0).equals(cancel.opt("operationId"))
                    ||original.equals(c.getString(0))||!grant.equals(cancel.opt("grantId"))||c.getLong(2)!=cancel.getLong("authorizationEpoch")
                    ||!sender.equals(cancel.opt("originDeviceId"))||!sender.equals(cancel.opt("callerDeviceId"))||!here.equals(cancel.opt("targetDeviceId"))
                    ||!instance.equals(cancel.opt("instanceId"))||!DeviceProtocol.resourceEquals(goal.getJSONObject("resourceBinding"),cancel.getJSONObject("resourceBinding"))
                    ||!c.getString(1).equals(cancel.opt("argumentsDigest"))||!c.getString(1).equals(DeviceProtocol.argumentsDigest(cancel))
                    ||args.length()!=2||!original.equals(args.opt("operationId"))||!digest.equals(args.opt("argumentsDigest"))
                    ||!DeviceProtocol.canonical(args).equals(DeviceProtocol.canonical(new JSONObject(c.getString(5))))
                    ||!Boolean.TRUE.equals(new JSONObject(c.getString(6)).opt("managementOnly")))throw new IllegalStateException("撤回并非本机这次原目标的停止凭据");
            return c.getLong(3);
        }
    }
}
