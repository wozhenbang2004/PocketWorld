package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DirectedPhoneTarget;
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
        return read(db,here,target,instance,parentAfter,operationAfter,searchText,"",null);
    }
    static JSONObject read(SQLiteDatabase db,String here,String target,String instance,String parentAfter,String operationAfter,String searchText,String source,DirectedPhoneTarget originalPhone) throws Exception {
        if((source.isEmpty())!=(originalPhone==null)||originalPhone!=null&&(!target.equals(originalPhone.deviceId())||!instance.equals(originalPhone.instanceId())))
            throw new SecurityException("原私人任务与执行手机身份不相符");
        DeviceProtocol.deviceId(here,"ownerDeviceId");DeviceProtocol.deviceId(target,"targetDeviceId");DeviceProtocol.identifier(instance,"targetInstanceId");
        // A specific private original task has already verified its durable directed target; it must
        // remain readable even when the peer record no longer exists. Only the partner folder uses current pairing.
        LocalPhoneDispatchPin.Identity selected=source.isEmpty()?LocalPhoneDispatchPin.current(db,target):null;
        if(source.isEmpty()&&(selected==null||!instance.equals(selected.instance())))throw new SecurityException("原目标身份不可核对，没有合并其他设备的派单");
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
                +(selected!=null && selected.generation()>1
                    ?"CASE WHEN length(CAST(b.receipt_json AS BLOB))<=67584 THEN b.receipt_json END ":"NULL ")
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
            if(selected!=null&&!selected.owns(row.isNull(6)?null:row.getString(6),parent,op,row.getString(3)))continue;
            JSONObject input=wire.optJSONObject("arguments");String goal=input==null?"":input.optString("goal");
            if(goal.trim().isEmpty()||!search.matches(goal))continue;
            int length=goal.codePointCount(0,goal.length());String preview=length>160?goal.substring(0,goal.offsetByCodePoints(0,160))+"…":goal;
            JSONObject item=new JSONObject().put("taskId",parent).put("operationId",op).put("argumentsDigest",row.getString(3))
                    .put("goal",preview).put("matchedLater",search.beyondPreview(preview)?true:null)
                    .put("screenMode",wire.getJSONObject("resourceBinding").getString("screenMode")).put("textOnly",DeviceProtocol.isAndroidTextReturn(wire)?true:null).put("createdAt",row.getLong(2));
            // Deadline belongs to the original digest-bound request, not a receipt or a remote runtime status.
            Object deadline=wire.opt("deadline");
            if(selected!=null && (deadline instanceof Long || deadline instanceof Integer) && ((Number)deadline).longValue()>0
                    && ((Number)deadline).longValue()<=DeviceProtocol.MAX_SAFE_INTEGER)item.put("requestDeadline",deadline);
            if(originalPhone!=null){
                JSONObject stop=SentPhoneStopReceipt.localFact(db,originalPhone,parent,op,wire);
                if(stop!=null)item.put(stop.has("localPhase")?"stopAttempt":"stopReceipt",stop);
            }
            acceptedFact(db,item,wire,parent,op);
            if(!item.has("targetAcceptedAt"))item.put("sourceSendPhase",SentPhoneDispatchPhase.read(db,wire,parent,op));
            items.put(item);
        }}
        if(selected!=null&&!selected.equals(LocalPhoneDispatchPin.current(db,target)))throw new SecurityException("本机配对记录已更新，请重读原设备");
        return new JSONObject().put("targetFingerprint",selected==null?null:selected.fingerprint()).put("sentPeerEpoch",selected==null?null:selected.localEpoch())
                .put("oldTargetIdentityChanged",selected!=null&&selected.generation()>1?true:null).put("rows",items).put("hasMore",more).put("lastParent",parent).put("lastOperation",op)
                .put("sourceTaskId",source.isEmpty()?null:source).put("targetDeviceId",target).put("instanceId",instance).put("localDeviceId",here).put("readOnly",true);
    }
    /** Project only two facts from the native authenticated acceptance; never expose a stored receipt or peer output. */
    private static void acceptedFact(SQLiteDatabase db,JSONObject item,JSONObject wire,String parent,String operation)throws Exception{
        // Read at most one bounded binding per *matched* original wire. An unrelated task,
        // former device generation or another allocation never enters this projection.
        String sql="SELECT CASE WHEN length(CAST(child_binding AS BLOB))<=32768 THEN child_binding END,arguments_digest "
                +"FROM device_child_budget WHERE parent_task_id=? AND parent_operation_id=? AND target_device_id=? AND allocation_id=?";
        JSONObject binding=new JSONObject(),budget=wire.optJSONObject("budget"); String anchorDigest;
        if(budget==null||!(budget.opt("allocationId") instanceof String allocation)||!allocation.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))return;
        try(Cursor row=db.rawQuery(sql,new String[]{parent,operation,wire.getString("targetDeviceId"),allocation})){
            if(!row.moveToFirst()||row.isNull(0)||row.isNull(1))return;
            anchorDigest=row.getString(1);
            // verifiedReceipt can include a private response body. Skip it rather than parse,
            // return or cache it; the native child link already verified the original acceptance.
            try(JsonReader reader=new JsonReader(new StringReader(row.getString(0)))){
                reader.beginObject();
                while(reader.hasNext()){
                    String key=reader.nextName();
                    if(binding.has(key))return;
                    switch(key){
                        case "accepted" -> {if(reader.peek()!=JsonToken.BOOLEAN)return;binding.put(key,reader.nextBoolean());}
                        case "acceptedAt","executorEpoch" -> {if(reader.peek()!=JsonToken.NUMBER)return;binding.put(key,reader.nextLong());}
                        case "acceptedObjectKind","objectId","localTaskId","parentTaskId","parentOperationId","sourceOperationId","targetOperationId",
                                "executorDeviceId","instanceId","operationArgumentsDigest","argumentsDigest","runId" -> {
                            if(reader.peek()!=JsonToken.STRING)return;String v=reader.nextString();if(v.length()>128)return;binding.put(key,v);
                        }
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)return;
            }catch(java.io.IOException|IllegalStateException|NumberFormatException unreadable){return;}
        }
        String task=DeviceProtocol.receiverTaskId(wire);
        if(!Boolean.TRUE.equals(binding.opt("accepted"))||!"task".equals(binding.opt("acceptedObjectKind"))
                ||!task.equals(binding.opt("objectId"))||!task.equals(binding.opt("localTaskId"))
                ||!parent.equals(binding.opt("parentTaskId"))||!operation.equals(binding.opt("parentOperationId"))
                ||!operation.equals(binding.opt("sourceOperationId"))||!operation.equals(binding.opt("targetOperationId"))
                ||!wire.getString("targetDeviceId").equals(binding.opt("executorDeviceId"))||!wire.getString("instanceId").equals(binding.opt("instanceId"))
                ||!wire.getString("argumentsDigest").equals(binding.opt("operationArgumentsDigest"))||!anchorDigest.equals(binding.opt("argumentsDigest"))
                ||!(binding.opt("acceptedAt") instanceof Number)||binding.optLong("acceptedAt")<=0||binding.optLong("acceptedAt")>DeviceProtocol.MAX_SAFE_INTEGER)return;
        item.put("targetAcceptedAt",binding.getLong("acceptedAt"));
        if(binding.optString("runId").matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")&&binding.opt("executorEpoch") instanceof Number
                &&binding.optLong("executorEpoch")>0&&binding.optLong("executorEpoch")<=DeviceProtocol.MAX_SAFE_INTEGER)item.put("targetRunRecorded",true);
    }
}
