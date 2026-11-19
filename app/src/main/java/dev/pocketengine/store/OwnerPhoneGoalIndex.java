package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local index of this owner's submitted phone goals, not the peer's task list or a result cache. */
final class OwnerPhoneGoalIndex {
    private OwnerPhoneGoalIndex() {}
    static JSONObject read(SQLiteDatabase db,String here,String target,String instance,String parentAfter,String operationAfter) throws Exception {
        DeviceProtocol.deviceId(here,"ownerDeviceId");DeviceProtocol.deviceId(target,"targetDeviceId");DeviceProtocol.identifier(instance,"targetInstanceId");
        String where=" WHERE b.target_device_id=?";ArrayList<String> args=new ArrayList<>();args.add(target);
        if (!parentAfter.isEmpty() || !operationAfter.isEmpty()) {
            DeviceProtocol.identifier(parentAfter,"parentTaskId");DeviceProtocol.identifier(operationAfter,"operationId");long time;
            try(Cursor anchor=db.rawQuery("SELECT created_at FROM device_child_budget WHERE target_device_id=? AND parent_task_id=? AND parent_operation_id=?",
                    new String[]{target,parentAfter,operationAfter})){
                if(!anchor.moveToFirst())throw new SecurityException("本机的原派发位置已变化，请从最新记录读取");
                time=anchor.getLong(0);
            }
            where+=" AND (b.created_at<? OR (b.created_at=? AND (b.parent_task_id>? OR (b.parent_task_id=? AND b.parent_operation_id>?))))";
            args.add(Long.toString(time));args.add(Long.toString(time));args.add(parentAfter);args.add(parentAfter);args.add(operationAfter);
        }
        String query="SELECT b.parent_task_id,b.parent_operation_id,b.created_at,l.wire_digest,"
                +"CASE WHEN length(CAST(l.wire_json AS BLOB))<="+DeviceProtocol.MAX_CONTROL_BYTES+" THEN l.wire_json END,"
                +"CASE WHEN length(CAST(t.ui_header AS BLOB))<=32768 THEN t.ui_header END "
                +"FROM device_child_budget b LEFT JOIN device_child_links l ON l.parent_task_id=b.parent_task_id AND l.parent_operation_id=b.parent_operation_id AND l.allocation_id=b.allocation_id "
                +"LEFT JOIN tasks t ON t.task_id=b.parent_task_id"+where+" ORDER BY b.created_at DESC,b.parent_task_id,b.parent_operation_id LIMIT 25";
        JSONArray items=new JSONArray();String parent="",op="";int scanned=0;boolean more=false;
        try(Cursor row=db.rawQuery(query,args.toArray(new String[0]))){while(row.moveToNext()){
            if(scanned==24){more=true;break;}scanned++;parent=row.getString(0);op=row.getString(1);
            if(row.isNull(3)||row.isNull(4)||row.isNull(5))continue;
            JSONObject header,wire;
            try { header=new JSONObject(row.getString(5));wire=new JSONObject(row.getString(4)); }
            catch(org.json.JSONException unreadable){continue;}
            if(!parent.equals(header.opt("id"))||!"owner".equals(header.opt("actorId"))||!"private".equals(header.opt("spaceId"))||header.has("origin")
                    ||!target.equals(wire.opt("targetDeviceId"))||!instance.equals(wire.opt("instanceId"))||!row.getString(3).equals(wire.opt("argumentsDigest"))
                    ||!DeviceProtocol.isDirectPhoneGoal(wire,here,parent,op)||!row.getString(3).equals(DeviceProtocol.argumentsDigest(wire)))continue;
            JSONObject input=wire.optJSONObject("arguments");String goal=input==null?"":input.optString("goal");
            if(goal.trim().isEmpty())continue;
            int length=goal.codePointCount(0,goal.length());
            items.put(new JSONObject().put("taskId",parent).put("operationId",op).put("argumentsDigest",row.getString(3))
                    .put("goal",length>160?goal.substring(0,goal.offsetByCodePoints(0,160))+"…":goal)
                    .put("screenMode",wire.getJSONObject("resourceBinding").getString("screenMode")).put("createdAt",row.getLong(2)));
        }}
        return new JSONObject().put("rows",items).put("hasMore",more).put("lastParent",parent).put("lastOperation",op)
                .put("targetDeviceId",target).put("instanceId",instance).put("localDeviceId",here).put("readOnly",true);
    }
}
