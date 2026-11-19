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
    static JSONObject read(SQLiteDatabase db,String here,String target,String instance,String parentAfter,String operationAfter)throws Exception {
        return read(db,here,target,instance,parentAfter,operationAfter,"");
    }
    static JSONObject read(SQLiteDatabase db,String here,String target,String instance,String parentAfter,String operationAfter,String searchText) throws Exception {
        return read(db,here,target,instance,parentAfter,operationAfter,searchText,"");
    }
    static JSONObject read(SQLiteDatabase db,String here,String target,String instance,String parentAfter,String operationAfter,String searchText,String source) throws Exception {
        DeviceProtocol.deviceId(here,"ownerDeviceId");DeviceProtocol.deviceId(target,"targetDeviceId");DeviceProtocol.identifier(instance,"targetInstanceId");
        LocalPhoneGoalSearch search=new LocalPhoneGoalSearch(searchText);
        String where=" WHERE b.target_device_id=?";ArrayList<String> args=new ArrayList<>();args.add(target);
        if(!source.isEmpty()){
            DeviceProtocol.identifier(source,"sourceTaskId");
            if(!parentAfter.isEmpty()&&!source.equals(parentAfter))throw new SecurityException("原任务翻页位置已变化");
            where+=" AND b.parent_task_id=?";args.add(source);
        }
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
                +"CASE WHEN length(CAST(t.ui_header AS BLOB))<=32768 THEN t.ui_header END,"
                +(source.isEmpty()?"NULL,NULL ":"CASE WHEN length(CAST(b.child_binding AS BLOB))<=32768 THEN b.child_binding END,b.arguments_digest ")
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
            if(goal.trim().isEmpty()||!search.matches(goal))continue;
            int length=goal.codePointCount(0,goal.length());String preview=length>160?goal.substring(0,goal.offsetByCodePoints(0,160))+"…":goal;
            JSONObject item=new JSONObject().put("taskId",parent).put("operationId",op).put("argumentsDigest",row.getString(3))
                    .put("goal",preview).put("matchedLater",search.beyondPreview(preview)?true:null)
                    .put("screenMode",wire.getJSONObject("resourceBinding").getString("screenMode")).put("textOnly",DeviceProtocol.isAndroidTextReturn(wire)?true:null).put("createdAt",row.getLong(2));
            if(!row.isNull(6)&&!row.isNull(7))acceptedFact(item,row.getString(6),row.getString(7),wire,parent,op);
            items.put(item);
        }}
        return new JSONObject().put("rows",items).put("hasMore",more).put("lastParent",parent).put("lastOperation",op)
                .put("sourceTaskId",source.isEmpty()?null:source).put("targetDeviceId",target).put("instanceId",instance).put("localDeviceId",here).put("readOnly",true);
    }
    /** Project only two facts from the native authenticated acceptance; never expose a stored receipt or peer output. */
    private static void acceptedFact(JSONObject item,String raw,String anchorDigest,JSONObject wire,String parent,String operation)throws Exception{
        JSONObject binding;
        try{binding=new JSONObject(raw);}catch(org.json.JSONException unreadable){return;}
        String task=DeviceProtocol.receiverTaskId(wire);
        if(!Boolean.TRUE.equals(binding.opt("accepted"))||!"task".equals(binding.opt("acceptedObjectKind"))
                ||!task.equals(binding.opt("objectId"))||!task.equals(binding.opt("localTaskId"))
                ||!parent.equals(binding.opt("parentTaskId"))||!operation.equals(binding.opt("parentOperationId"))
                ||!operation.equals(binding.opt("sourceOperationId"))||!operation.equals(binding.opt("targetOperationId"))
                ||!wire.getString("targetDeviceId").equals(binding.opt("executorDeviceId"))||!wire.getString("instanceId").equals(binding.opt("instanceId"))
                ||!wire.getString("argumentsDigest").equals(binding.opt("operationArgumentsDigest"))||!anchorDigest.equals(binding.opt("argumentsDigest"))
                ||!(binding.opt("acceptedAt") instanceof Number)||binding.optLong("acceptedAt")<=0)return;
        item.put("targetAcceptedAt",binding.getLong("acceptedAt"));
        if(binding.opt("runId") instanceof String&&!binding.getString("runId").isEmpty()&&binding.opt("executorEpoch") instanceof Number
                &&binding.optLong("executorEpoch")>0)item.put("targetRunRecorded",true);
    }
}
