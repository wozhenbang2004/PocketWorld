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
        DeviceProtocol.deviceId(here, "targetDeviceId"); DeviceProtocol.deviceId(source, "callerDeviceId");
        DeviceProtocol.identifier(localInstance, "instanceId");
        String where = " WHERE i.caller_device_id=? AND i.method='task.submitGoal'";
        ArrayList<String> args = new ArrayList<>(); args.add(source);
        if (!afterTask.isEmpty() || !afterOp.isEmpty()) {
            DeviceProtocol.identifier(afterTask,"localTaskId"); DeviceProtocol.identifier(afterOp,"remoteOperationId");
            long at;
            try (Cursor cursor = db.rawQuery("SELECT accepted_at FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=? AND local_task_id=? AND method='task.submitGoal'", new String[]{source,afterOp,afterTask})) {
                if (!cursor.moveToFirst()) throw new SecurityException("本机原接纳位置已变化，请重新读取");
                at = cursor.getLong(0);
            }
            where += " AND (i.accepted_at<? OR (i.accepted_at=? AND i.remote_operation_id>?))";
            args.add(Long.toString(at)); args.add(Long.toString(at)); args.add(afterOp);
        }
        String sql = "SELECT i.local_task_id,i.remote_operation_id,i.accepted_at,i.arguments_digest,i.grant_id,i.authorization_epoch,"
            + "CASE WHEN length(CAST(i.envelope_json AS BLOB))<="+DeviceProtocol.MAX_CONTROL_BYTES+" THEN i.envelope_json END,"
            + "CASE WHEN length(CAST(t.ui_header AS BLOB))<=32768 THEN t.ui_header END "
            + "FROM device_inbound i LEFT JOIN tasks t ON t.task_id=i.local_task_id"+where+" ORDER BY i.accepted_at DESC,i.remote_operation_id LIMIT 25";
        JSONArray items = new JSONArray(); String task="",op=""; int scanned=0; boolean more=false;
        try (Cursor c = db.rawQuery(sql,args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                if (scanned++ == 24) { more=true; break; }
                task=c.getString(0); op=c.getString(1);
                if (c.isNull(6) || c.isNull(7) || c.getLong(2)<=0 || c.getLong(5)<=0) continue;
                try {
                    JSONObject wire=new JSONObject(c.getString(6)), header=new JSONObject(c.getString(7));
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
                            .put("originDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin))).put("screenMode",mode)
                            .put("goal",goal).put("createdAt",c.getLong(2)));
                } catch (JSONException | DeviceProtocol.ProtocolException invalid) { /* Never replace missing provenance with a nearby task. */ }
            }
        }
        return new JSONObject().put("rows",items).put("hasMore",more).put("lastParent",task).put("lastOperation",op)
                .put("localDeviceId",here).put("localInstanceId",localInstance).put("targetDeviceId",source).put("readOnly",true);
    }
}
