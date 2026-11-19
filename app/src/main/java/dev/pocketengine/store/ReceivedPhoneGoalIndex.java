package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** B's own accepted requests, not the sender's outbox and not authority to execute one. */
final class ReceivedPhoneGoalIndex {
    private ReceivedPhoneGoalIndex() {}

    static JSONObject read(SQLiteDatabase db, String here, String localInstance, String source, String afterTask, String afterOp) throws Exception {
        return scan(db,here,localInstance,DeviceProtocol.deviceId(source,"callerDeviceId"),afterTask,afterOp);
    }
    static JSONObject waiting(SQLiteDatabase db,String here,String localInstance,String afterCaller,String afterOp)throws Exception{
        return scan(db,here,localInstance,null,afterCaller,afterOp);
    }
    private static JSONObject scan(SQLiteDatabase db,String here,String localInstance,String issuer,String anchorId,String afterOp)throws Exception{
        DeviceProtocol.deviceId(here,"targetDeviceId"); DeviceProtocol.identifier(localInstance,"instanceId");
        boolean waiting=issuer==null; String where=" WHERE i.method='task.submitGoal'";
        ArrayList<String> args=new ArrayList<>();
        if(waiting) where+=" AND NOT EXISTS(SELECT 1 FROM runs r WHERE r.task_id=i.local_task_id)"; // runs has UNIQUE(task_id,epoch).
        else {where+=" AND i.caller_device_id=?";args.add(issuer);}
        if(!anchorId.isEmpty()||!afterOp.isEmpty()){
            DeviceProtocol.identifier(afterOp,"operationId"); String caller=waiting?DeviceProtocol.deviceId(anchorId,"callerDeviceId"):issuer;
            String anchor="SELECT accepted_at FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=? AND method='task.submitGoal'";
            String[] params;
            if(waiting)params=new String[]{caller,afterOp};
            else {DeviceProtocol.identifier(anchorId,"localTaskId");anchor+=" AND local_task_id=?";params=new String[]{caller,afterOp,anchorId};}
            long at;try(Cursor c=db.rawQuery(anchor,params)){if(!c.moveToFirst())throw new SecurityException("本机接纳位置已变化，请重新读取");at=c.getLong(0);}
            where+=" AND (i.accepted_at<? OR (i.accepted_at=? AND (i.caller_device_id>? OR (i.caller_device_id=? AND i.remote_operation_id>?))))";
            args.add(Long.toString(at));args.add(Long.toString(at));args.add(caller);args.add(caller);args.add(afterOp);
        }
        String sql = "SELECT i.local_task_id,i.remote_operation_id,i.accepted_at,i.arguments_digest,i.grant_id,i.authorization_epoch,"
            + "CASE WHEN length(CAST(i.envelope_json AS BLOB))<="+DeviceProtocol.MAX_CONTROL_BYTES+" THEN i.envelope_json END,"
            + "CASE WHEN length(CAST(t.ui_header AS BLOB))<=32768 THEN t.ui_header END,i.caller_device_id "
            + "FROM device_inbound i LEFT JOIN tasks t ON t.task_id=i.local_task_id"+where+" ORDER BY i.accepted_at DESC,i.caller_device_id,i.remote_operation_id LIMIT 25";
        JSONArray items = new JSONArray(); String task="",op="",source=""; int scanned=0; boolean more=false;
        try (Cursor c = db.rawQuery(sql,args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                if (scanned++ == 24) { more=true; break; }
                task=c.getString(0); op=c.getString(1); source=c.getString(8);
                if (here.equals(source) || c.isNull(6) || c.isNull(7) || c.getLong(2)<=0 || c.getLong(5)<=0) continue;
                try {
                    JSONObject wire=new JSONObject(c.getString(6)), header=new JSONObject(c.getString(7));
                    if(waiting && (!header.optString("runId").isEmpty() || header.optInt("coordinatorEpoch",-1)!=0 || !Boolean.FALSE.equals(header.opt("cancelRequested"))
                            || !("queued".equals(header.opt("status"))||"awaiting_user".equals(header.opt("status"))||"interrupted".equals(header.opt("status")))))continue;
                    JSONObject origin=header.getJSONObject("origin"); String parent=wire.getString("parentTaskId"), digest=c.getString(3);
                    if (!here.equals(wire.opt("targetDeviceId")) || !localInstance.equals(wire.opt("instanceId"))
                            || !DeviceProtocol.isDirectPhoneGoal(wire,source,parent,op) || !task.equals(DeviceProtocol.receiverTaskId(wire))
                            || !digest.equals(wire.opt("argumentsDigest")) || !digest.equals(DeviceProtocol.argumentsDigest(wire))
                            || !c.getString(4).equals(wire.opt("grantId")) || c.getLong(5)!=wire.getLong("authorizationEpoch")) continue;
                    String mode=wire.getJSONObject("resourceBinding").getString("screenMode");
                    if (!task.equals(header.opt("id")) || !"phone".equals(header.opt("kind")) || !mode.equals(header.opt("executionMode"))
                            || !("paired_device:"+source).equals(header.opt("actorId")) || !("device:"+source).equals(header.opt("spaceId"))
                            || !"paired_device".equals(origin.opt("kind")) || !source.equals(origin.opt("callerDeviceId")) || !source.equals(origin.opt("originDeviceId"))
                            || !here.equals(origin.opt("targetDeviceId")) || !op.equals(origin.opt("remoteOperationId")) || !parent.equals(origin.opt("rootTaskId"))
                            || !parent.equals(origin.opt("parentTaskId")) || !wire.getString("localTaskId").equals(origin.opt("remoteLocalTaskId"))
                            || !c.getString(4).equals(origin.opt("grantId")) || c.getLong(5)!=origin.getLong("authorizationEpoch")) continue;
                    String goal=wire.getJSONObject("arguments").getString("goal"); if (goal.trim().isEmpty()) continue;
                    if (goal.codePointCount(0,goal.length())>160) goal=goal.substring(0,goal.offsetByCodePoints(0,160))+"…";
                    items.put(new JSONObject().put("taskId",task).put("operationId",op).put("argumentsDigest",digest)
                            .put("sourceDeviceId",source).put("waitingState",waiting?("interrupted".equals(header.opt("status"))?"interrupted_before_start":"not_started"):null).put("originDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin))).put("screenMode",mode)
                            .put("goal",goal).put("createdAt",c.getLong(2)));
                } catch (JSONException | DeviceProtocol.ProtocolException invalid) { /* Never replace missing provenance with a nearby task. */ }
            }
        }
        return new JSONObject().put("rows",items).put("hasMore",more).put("lastParent",waiting?source:task).put("lastOperation",op)
                .put("localDeviceId",here).put("localInstanceId",localInstance).put("targetDeviceId",issuer).put("waitingOnly",waiting).put("readOnly",true);
    }
}
