package dev.pocketengine.runtime.usage;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read of original native receipts. No writable usage cache or replacement accounting. */
public final class InferenceTrendQuery {
    public static final int PAGE_ROWS=512,MAX_BYTES=2097152;
    private InferenceTrendQuery() { }
    public static JSONObject read(SQLiteDatabase db,long from,long until,String zone,String cursor)throws Exception {
        range(from,until,zone);String task="";long order=0;
        if(cursor!=null&&!cursor.isEmpty()){if(cursor.length()>400)throw new IllegalArgumentException("用量游标过长");JSONObject c=new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));task=c.getString("taskId");order=c.getLong("order");if(!task.matches("[a-f0-9-]{36}")||order<1)throw new IllegalArgumentException("用量游标无效");}
        JSONArray events=new JSONArray();int scanned=0,unreadable=0;long bytes=0;boolean more=false;String lastTask=task;long lastOrder=order;
        try(Cursor rows=db.rawQuery("SELECT e.task_id,e.event_order,e.body_bytes,CASE WHEN e.body_bytes BETWEEN 0 AND 65536 THEN e.event_json END,CASE WHEN length(CAST(t.ui_header AS BLOB))<=32768 THEN t.ui_header END FROM task_events e JOIN tasks t ON t.task_id=e.task_id WHERE e.event_type='inference_receipt' AND (e.task_id>? OR (e.task_id=? AND e.event_order>?)) ORDER BY e.task_id,e.event_order LIMIT 513",new String[]{task,task,Long.toString(order)})){
            while(rows.moveToNext()){
                if(scanned==PAGE_ROWS||bytes+Math.max(0,rows.getLong(2))>MAX_BYTES){more=true;break;}scanned++;lastTask=rows.getString(0);lastOrder=rows.getLong(1);bytes+=Math.max(0,rows.getLong(2));
                if(rows.isNull(3)||rows.isNull(4)){unreadable++;continue;}JSONObject header=new JSONObject(rows.getString(4)),origin=header.optJSONObject("origin");
                if(!"owner".equals(header.optString("actorId"))||!"private".equals(header.optString("spaceId"))||origin!=null&&java.util.Set.of("channel","group","reviewed_external_group","native_remote_group_inputs").contains(origin.optString("kind")))continue;
                JSONObject event=new JSONObject(rows.getString(3)),context=event.optJSONObject("context");if(context==null||!lastTask.equals(context.optString("taskId"))){unreadable++;continue;}
                long at=event.optLong("startedAt",-1);if(at>=from&&at<until)events.put(event);
            }
        }
        String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString(new JSONObject().put("taskId",lastTask).put("order",lastOrder).toString().getBytes(StandardCharsets.UTF_8)):"";
        return report(events,from,until,zone).put("receipts",events).put("scannedRows",scanned).put("scannedBytes",bytes).put("unreadableRows",unreadable)
                .put("hasMore",more).put("nextCursor",next).put("completeCoverage",(cursor==null||cursor.isEmpty())&&!more&&unreadable==0)
                .put("coverage","bounded_private_owner_original_receipts_not_provider_bill").put("executionAuthorized",false);
    }
    public static JSONObject report(JSONArray events,long from,long until,String zone)throws Exception {
        ZoneId clock=range(from,until,zone);JSONObject total=InferenceProjection.fromEvents(events);JSONArray receipts=total.getJSONArray("receipts");
        Map<String,JSONArray> days=new TreeMap<>(),models=new TreeMap<>(),originals=new java.util.HashMap<>();for(int i=0;i<events.length();i++){JSONObject event=events.optJSONObject(i);if(event!=null)originals.computeIfAbsent(event.optString("callId"),k->new JSONArray()).put(event);}
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);long at=receipt.optLong("startedAt",-1);if(at<from||at>=until)throw new IllegalArgumentException("用量记录不属于所选时间范围");
            String day=Instant.ofEpochMilli(at).atZone(clock).toLocalDate().toString();JSONArray dayEvents=days.computeIfAbsent(day,k->new JSONArray());JSONArray source=originals.get(receipt.getString("callId"));for(int n=0;n<source.length();n++)dayEvents.put(source.get(n));
            JSONObject route=receipt.getJSONObject("route"),context=receipt.getJSONObject("context");String provider=route.optString("connectionId",route.optString("accountId",route.optString("protocol","unknown")));
            String key=new JSONArray().put(provider).put(route.optString("protocol")).put(route.optString("requestedModel")).put(context.optString("purpose")).toString();JSONArray modelEvents=models.computeIfAbsent(key,k->new JSONArray());for(int n=0;n<source.length();n++)modelEvents.put(source.get(n));
        }
        JSONArray daily=new JSONArray(),breakdown=new JSONArray();for(var entry:days.entrySet())daily.put(new JSONObject().put("date",entry.getKey()).put("usage",summary(entry.getValue())));
        for(var entry:models.entrySet()){JSONArray key=new JSONArray(entry.getKey());breakdown.put(new JSONObject().put("historicalProviderId",key.getString(0)).put("protocol",key.getString(1)).put("model",key.getString(2)).put("purpose",key.getString(3)).put("usage",summary(entry.getValue())));}
        total.remove("receipts");return new JSONObject().put("format","pocketworld.inference-trend.v1").put("fromInclusive",from).put("untilExclusive",until).put("zone",zone)
                .put("total",total).put("daily",daily).put("breakdown",breakdown).put("unknownIsZero",false).put("historicalIdentity","receipt_identity_even_if_connection_was_deleted");
    }
    private static JSONObject summary(JSONArray events)throws Exception{JSONObject value=InferenceProjection.fromEvents(events);value.remove("receipts");return value;}
    private static ZoneId range(long from,long until,String zone){if(from<0||until<=from||until-from>366L*86400000)throw new IllegalArgumentException("请选择不超过366天的有效范围");return ZoneId.of(zone);}
}
