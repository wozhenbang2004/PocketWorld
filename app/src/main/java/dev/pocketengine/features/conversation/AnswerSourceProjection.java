package dev.pocketengine.features.conversation;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable request boundary plus exact original rows; no latest-run or current-screen fallback. */
public final class AnswerSourceProjection {
    private AnswerSourceProjection() { }
    private static void ids(String task,String run){if(task==null||run==null||!task.matches("[a-f0-9-]{36}")||!run.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原task/run来源定位无效");}
    /** Runner events use coordinatorEpoch; original browser sources use epoch. Conflicts stay unknown. */
    private static int eventEpoch(JSONObject event){
        int current=event.has("coordinatorEpoch")?integerEpoch(event.opt("coordinatorEpoch")):0;
        int browser=event.has("epoch")?integerEpoch(event.opt("epoch")):0;
        return current<0||browser<0||current>0&&browser>0&&current!=browser?-1:current>0?current:browser>0?browser:-1;
    }
    private static int integerEpoch(Object value){if(!(value instanceof Integer||value instanceof Long))return -1;long number=((Number)value).longValue();return number>0&&number<=Integer.MAX_VALUE?(int)number:-1;}
    private static void privateTask(SQLiteDatabase db,String task)throws Exception {try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(ui_header AS BLOB))<=32768 THEN ui_header END FROM tasks WHERE task_id=?",new String[]{task})){if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("原私人任务不可读");JSONObject header=new JSONObject(row.getString(0)),origin=header.optJSONObject("origin");if(!"owner".equals(header.optString("actorId"))||!"private".equals(header.optString("spaceId"))||origin!=null&&java.util.Set.of("channel","group","reviewed_external_group","native_remote_group_inputs").contains(origin.optString("kind")))throw new SecurityException("回答来源只在原私人域读取");}}
    public static JSONObject capture(SQLiteDatabase db,String task,String run,int epoch,String request,String answer)throws Exception {
        ids(task,run);privateTask(db,task);if(epoch<1||!request.matches("[a-f0-9-]{36}")||!answer.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原完成请求/回答身份无效");long boundary;
        try(Cursor row=db.rawQuery("SELECT e.event_order,CASE WHEN e.body_bytes BETWEEN 0 AND 65536 THEN e.event_json END FROM task_events e WHERE e.task_id=? AND e.event_type='inference_receipt' AND e.call_id=? LIMIT 2",new String[]{task,request})){if(!row.moveToFirst()||row.isNull(1))throw new SecurityException("原模型请求不可核对");boundary=row.getLong(0);JSONObject receipt=new JSONObject(row.getString(1)),context=receipt.getJSONObject("context");if(!"COMPLETED".equals(receipt.getString("outcome"))||!request.equals(receipt.optString("callId"))||!task.equals(context.optString("taskId"))||!run.equals(context.getString("runId"))||epoch!=context.getInt("epoch")||row.moveToNext())throw new SecurityException("原模型请求未完成或轮次改变");}
        JSONArray sources=new JSONArray();int scanned=0;boolean limited=false;
        try(Cursor rows=db.rawQuery("SELECT event_order,CASE WHEN body_bytes BETWEEN 0 AND 131072 THEN event_json END FROM task_events WHERE task_id=? AND event_order<? AND event_type IN ('browser_source_retained','observation','tool') ORDER BY event_order DESC LIMIT 301",new String[]{task,Long.toString(boundary)})){
            while(rows.moveToNext()){if(++scanned>300){limited=true;break;}if(rows.isNull(1)){limited=true;continue;}JSONObject event=new JSONObject(rows.getString(1));if(!run.equals(event.optString("runId"))||epoch!=eventEpoch(event))continue;String type=event.optString("type");JSONObject receipt=event.optJSONObject("receipt");boolean source=type.equals("browser_source_retained")||type.equals("observation")&&receipt!=null&&receipt.optBoolean("ok",true)||type.equals("tool")&&jumps(task,run,event).length()>0;if(!source)continue;
                sources.put(new JSONObject().put("eventOrder",rows.getLong(0)).put("eventFingerprint",TaskStore.capabilityFingerprint(event)).put("type",type));if(sources.length()==100){limited=true;break;}}
        }
        JSONObject anchor=new JSONObject().put("format","pocketworld.answer-sources.v1").put("taskId",task).put("runId",run).put("epoch",epoch).put("requestId",request).put("answerId",answer).put("requestEventOrder",boundary).put("entries",sources).put("coverageLimited",limited).put("modelRelianceVerified",false);
        return anchor.put("fingerprint",TaskStore.capabilityFingerprint(anchor));
    }
    private static JSONArray jumps(String task,String run,JSONObject event)throws Exception {JSONObject view=new JSONObject().put("id",task).put("runId",run).put("actorId","owner").put("spaceId","private").put("events",new JSONArray().put(event));JSONArray raw=TaskMemoryTrace.rawCitationJumps(view),outputs=TaskMemoryTrace.outputCitationJumps(view);for(int i=0;i<outputs.length();i++)raw.put(outputs.get(i));return raw;}
    public static JSONObject read(SQLiteDatabase db,JSONObject anchor,int index)throws Exception {
        JSONObject copy=new JSONObject(anchor.toString());String hash=copy.getString("fingerprint");copy.remove("fingerprint");if(!hash.equals(TaskStore.capabilityFingerprint(copy))||!"pocketworld.answer-sources.v1".equals(copy.getString("format")))throw new SecurityException("原回答来源清单改变");String task=copy.getString("taskId"),run=copy.getString("runId");ids(task,run);privateTask(db,task);JSONArray entries=copy.getJSONArray("entries");if(entries.length()>100||index<0||index>=entries.length())throw new IllegalArgumentException("请选择原回答清单中的来源");JSONObject wanted=entries.getJSONObject(index);long order=wanted.getLong("eventOrder");if(order<1||order>=copy.getLong("requestEventOrder"))throw new SecurityException("来源不在原请求之前");
        JSONObject result=new JSONObject().put("taskId",task).put("runId",run).put("answerId",copy.getString("answerId")).put("eventOrder",order).put("executionAuthorized",false).put("available",false);
        try(Cursor request=db.rawQuery("SELECT event_order,CASE WHEN body_bytes BETWEEN 0 AND 65536 THEN event_json END FROM task_events WHERE task_id=? AND event_type='inference_receipt' AND call_id=? LIMIT 2",new String[]{task,copy.getString("requestId")})){
            if(!request.moveToFirst()||request.isNull(1)||request.getLong(0)!=copy.getLong("requestEventOrder"))return result.put("status","request_unavailable");JSONObject original=new JSONObject(request.getString(1)),context=original.getJSONObject("context");if(!"COMPLETED".equals(original.optString("outcome"))||!copy.getString("requestId").equals(original.optString("callId"))||!task.equals(context.optString("taskId"))||!run.equals(context.optString("runId"))||copy.getInt("epoch")!=context.optInt("epoch")||request.moveToNext())return result.put("status","request_changed");
        }
        try(Cursor row=db.rawQuery("SELECT CASE WHEN body_bytes BETWEEN 0 AND 131072 THEN event_json END FROM task_events WHERE task_id=? AND event_order=?",new String[]{task,Long.toString(order)})){
            if(!row.moveToFirst()||row.isNull(0))return result.put("status","source_unavailable");JSONObject event=new JSONObject(row.getString(0));if(!run.equals(event.optString("runId"))||copy.getInt("epoch")!=eventEpoch(event)||!wanted.getString("eventFingerprint").equals(TaskStore.capabilityFingerprint(event)))return result.put("status","source_changed");
            String type=event.getString("type");JSONObject receipt=event.optJSONObject("receipt");
            if(type.equals("browser_source_retained")){
                JSONObject source=event.getJSONObject("source");if(!task.equals(source.optString("taskId"))||!run.equals(source.optString("runId"))||copy.getInt("epoch")!=source.optInt("epoch")||!event.getString("sourceId").equals(source.optString("sourceId"))||!event.getString("sourceFingerprint").equals(TaskStore.capabilityFingerprint(source)))return result.put("status","source_changed");
                String originalUrl=source.optString("privateUrl"),url=source.optString("url"),body=source.getString("excerpt"),title=source.optString("title","原网页摘录");if(!originalUrl.isEmpty()){body=body.replace(originalUrl,url);title=title.replace(originalUrl,url);}
                JSONObject identity=new JSONObject();for(String key:new String[]{"sourceId","observationId","documentId","frameId","profileId","profileRevision","sourceFingerprint","urlQueryOmitted","retainedAt","observedAt"})if(source.has(key))identity.put(key,source.get(key));identity.put("sourceFingerprint",event.getString("sourceFingerprint"));
                if(source.optJSONObject("structuredProjection")!=null)result.put("structuredProjection",new JSONObject(source.getJSONObject("structuredProjection").toString()));
                return result.put("available",true).put("kind","saved_web_observation").put("title",title).put("url",url).put("sourceIdentity",identity).put("body",body).put("range",TaskMemoryTrace.rawRange(0,body.length(),body.length())).put("frameStatus","not_retained").put("savedEvidenceOnly",true).put("modelRelianceVerified",false);
            }
            if(type.equals("observation")&&receipt!=null){JSONObject safe=new JSONObject(receipt.toString());safe.remove("imageBase64");safe.remove("observationId");String body=safe.toString(2);JSONObject frame=receipt.optJSONObject("retainedFrame");return result.put("available",true).put("kind","phone_observation").put("title","原手机观察").put("sourceIdentity",new JSONObject().put("observationId",receipt.optString("observationId"))).put("body",body).put("range",TaskMemoryTrace.rawRange(0,body.length(),body.length())).put("frame",frame==null?JSONObject.NULL:frame).put("frameStatus",frame==null?"not_retained":"exact_reference_available");}
            if(type.equals("tool")&&receipt!=null){JSONArray jumps=jumps(task,run,event);return result.put("available",jumps.length()>0).put("kind","exact_text_references").put("jumps",jumps).put("frameStatus","not_retained");}
            return result.put("status","source_unavailable");
        }
    }
}
