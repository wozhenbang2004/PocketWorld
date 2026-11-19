package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.io.IOException;
import java.io.StringReader;
import org.json.JSONObject;

/** Two local transport facts, never a peer outcome. The caller already matched the private original and peer generation. */
final class SentPhoneDispatchPhase {
    private SentPhoneDispatchPhase() {}
    private static final String ROW = "SELECT CASE WHEN length(CAST(o.receipt AS BLOB))<=32768 THEN o.receipt END,o.state,l.intent_digest "
            +"FROM operations o JOIN device_child_links l ON l.parent_task_id=o.task_id AND l.parent_operation_id=o.operation_id "
            +"JOIN device_child_budget b ON b.parent_task_id=o.task_id AND b.parent_operation_id=o.operation_id AND b.allocation_id=l.allocation_id AND b.parent_run_id=o.run_id "
            +"JOIN runs r ON r.task_id=o.task_id AND r.run_id=o.run_id AND r.epoch=b.parent_epoch "
            +"WHERE o.task_id=? AND o.operation_id=? AND o.run_id=? AND r.epoch=? AND o.tool='device_delegate_goal' "
            +"AND b.target_device_id=? AND b.allocation_id=? AND l.wire_digest=? AND b.arguments_digest=l.wire_digest AND b.child_binding IS NULL";

    static String read(SQLiteDatabase db,JSONObject wire,String parent,String op)throws Exception {
        JSONObject budget=wire.optJSONObject("budget");
        if(budget==null || !(budget.opt("allocationId") instanceof String allocation) || !(wire.opt("runId") instanceof String run)
                || wire.optLong("executorEpoch")<=0)return null;
        try(Cursor row=db.rawQuery(ROW,new String[]{parent,op,run,Long.toString(wire.getLong("executorEpoch")),wire.getString("targetDeviceId"),allocation,wire.getString("argumentsDigest")})){
            if(!row.moveToFirst()||row.isNull(0)||row.isNull(2))return null;
            JSONObject marker;
            try(JsonReader reader=new JsonReader(new StringReader(row.getString(0)))) {
                reader.beginObject();marker=null;
                while(reader.hasNext()){
                    String key=reader.nextName();
                    if("nativeDeviceAcceptedBinding".equals(key))return null;
                    if(!"nativeDeviceSend".equals(key)){reader.skipValue();continue;}
                    if(marker!=null||reader.peek()!=JsonToken.BEGIN_OBJECT)return null;
                    marker=new JSONObject();reader.beginObject();
                    while(reader.hasNext()){
                        String name=reader.nextName();
                        switch(name){
                            case "format","state","wireSha256","allocationId","intentDigest" -> {
                                if(marker.has(name)||reader.peek()!=JsonToken.STRING)return null;
                                String value=reader.nextString();if(value.length()>128)return null;marker.put(name,value);
                            }
                            case "claimedAt" -> {
                                if(marker.has(name)||reader.peek()!=JsonToken.NUMBER)return null;marker.put(name,reader.nextLong());
                            }
                            default -> reader.skipValue();
                        }
                    }
                    reader.endObject();
                }
                reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)return null;
            }catch(IOException|IllegalStateException|NumberFormatException unreadable){return null;}
            if(marker==null||!"pocketworld.native-device-send.v1".equals(marker.opt("format"))||!allocation.equals(marker.opt("allocationId"))
                    ||!row.getString(2).matches("[a-f0-9]{64}")||!row.getString(2).equals(marker.opt("intentDigest"))
                    ||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(wire)).equals(marker.opt("wireSha256")))return null;
            String journal=row.getString(1);
            if("abandoned_before_write".equals(marker.opt("state"))&&!marker.has("claimedAt")&&("prepared".equals(journal)||"receipt".equals(journal)))return "abandoned_before_write";
            if("write_claimed".equals(marker.opt("state"))&&marker.optLong("claimedAt")>0&&marker.optLong("claimedAt")<=DeviceProtocol.MAX_SAFE_INTEGER
                    &&("unknown".equals(journal)||"receipt".equals(journal)))return "write_claimed";
            return null;
        }
    }
}
