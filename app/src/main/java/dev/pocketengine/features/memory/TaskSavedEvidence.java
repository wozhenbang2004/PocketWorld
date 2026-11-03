package dev.pocketengine.features.memory;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.plugins.McpResourceEvidence;
import dev.pocketengine.store.TaskAuditStore;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact routes to already saved native evidence, on the original task_events table. No second
 * source body, current-page substitution, old RunControl, provider revision or model read grant. */
public final class TaskSavedEvidence {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final int MAX_EVENT_BYTES=131072;
    private TaskSavedEvidence(){}
    public static boolean matches(String sourceId){return sourceId!=null&&sourceId.startsWith("task:")&&sourceId.contains(":saved:");}
    public static final class Address {
        public final String taskId,runId,eventSha256;public final long eventSeq;public final int offset,chars;
        private Address(String task,String run,long seq,String sha,int offset,int chars){taskId=task;runId=run;eventSeq=seq;eventSha256=sha;this.offset=offset;this.chars=chars;}
    }
    public static Address address(String sourceId){
        if(sourceId==null||sourceId.length()>240)throw new IllegalArgumentException("已保存来源定位过长或缺失");String[] p=sourceId.split(":",-1);
        if(p.length!=10||!p[0].equals("task")||!p[1].matches(UUID)||!p[2].equals("run")||!p[3].matches(UUID)||!p[4].equals("saved")||!p[5].matches("[1-9][0-9]{0,18}")||!p[6].matches("[0-9a-f]{64}")||!p[7].equals("range")||!p[8].matches("0|[1-9][0-9]{0,9}")||!p[9].matches("[1-9][0-9]{0,4}"))throw new IllegalArgumentException("已保存来源必须是原task/run/事件/hash/范围的准确定位");
        try{long seq=Long.parseLong(p[5]);int offset=Integer.parseInt(p[8]),chars=Integer.parseInt(p[9]);if(chars>24000)throw new IllegalArgumentException("已保存来源范围最多24000 UTF-16字符");return new Address(p[1],p[3],seq,p[6],offset,chars);}catch(NumberFormatException bad){throw new IllegalArgumentException("已保存来源序号或范围超限",bad);}
    }
    private static SourceRef reference(String task,String run,long seq,JSONObject event,int offset,int chars)throws Exception{
        String sourceId="task:"+task+":run:"+run+":saved:"+seq+":"+TaskStore.capabilityFingerprint(event)+":range:"+offset+":"+chars;
        address(sourceId);return new SourceRef("task_evidence",sourceId,seq,"owner","private");
    }
    private static void privateTask(JSONObject task)throws Exception{
        JSONObject origin=task.optJSONObject("origin");
        if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||origin!=null&&Set.of("channel","group").contains(origin.optString("kind")))throw new SecurityException("已保存来源只属于原私人任务，不跨渠道或群来源域");
    }
    private static JSONObject task(SQLiteDatabase db,String task)throws Exception{
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(document AS BLOB))<=1048576 THEN document END FROM tasks WHERE task_id=?",new String[]{task})){
            if(!row.moveToFirst())return null;if(row.isNull(0))throw new IllegalStateException("原task元数据超过有界来源窗口");JSONObject value=new JSONObject(row.getString(0));privateTask(value);return value;
        }
    }
    private static JSONObject event(SQLiteDatabase db,String task,String run,long seq)throws Exception{
        try(Cursor row=db.rawQuery("SELECT e.native_seq,e.event_type,e.body_bytes,CASE WHEN e.body_bytes>=0 AND e.body_bytes<=131072 THEN e.event_json END,r.epoch FROM task_events e JOIN runs r ON r.task_id=e.task_id AND r.run_id=? WHERE e.task_id=? AND e.event_order=? AND e.native_seq=?",new String[]{run,task,Long.toString(seq),Long.toString(seq)})){
            if(!row.moveToFirst())return null;if(row.isNull(3))throw new IllegalStateException("原事件超出本次128KiB读取范围，未加载整张审计表");
            JSONObject event=new JSONObject(row.getString(3));if(seq!=SourceRef.integer(event,"seq",1)||!run.equals(event.optString("runId"))||row.getInt(4)!=event.optInt("epoch")||!row.getString(1).equals(event.optString("type"))
                    ||McpResourceEvidence.EVENT_TYPE.equals(event.optString("type"))&&!task.equals(event.optString("taskId")))throw new SecurityException("原已保存事件的task/run/epoch或序号不一致");return event;
        }
    }
    public static DocumentContent.Request documentRange(JSONObject event)throws Exception{
        if(!"attachment_document_read".equals(event.optString("type"))||!"task_tool".equals(event.optString("readContext"))||!event.optString("attachmentId").matches(UUID)||!event.optString("sha256").matches("[0-9a-f]{64}")||!event.optString("attachmentGeneration").matches("sha256:[0-9a-f]{64}")||!event.optString("documentFingerprint").matches("[0-9a-f]{64}"))throw new IllegalStateException("旧文档回执缺少准确range/hash/原件代次；请从原文档重新读取，不推测旧正文");
        return new DocumentContent.Request(Math.toIntExact(SourceRef.integer(event,"startUnit",1)),Math.toIntExact(SourceRef.integer(event,"startChunk",0)),Math.toIntExact(SourceRef.integer(event,"maxUnits",1)),Math.toIntExact(SourceRef.integer(event,"maxChars",2048)),Math.toIntExact(SourceRef.integer(event,"maxChunks",1)),event.getString("extraction"));
    }
    public static void requireSupported(JSONObject event)throws Exception{
        if("attachment_document_read".equals(event.optString("type"))){documentRange(event);return;}
        if("browser_source_retained".equals(event.optString("type"))){dev.pocketengine.browser.BrowserTaskSpace.savedMemoryProjection(event);return;}
        if("app_learning_evidence".equals(event.optString("type"))){appLearningText(event);return;}
        if(McpResourceEvidence.EVENT_TYPE.equals(event.optString("type"))){McpResourceEvidence.requireIndexEvent(event);return;}
        throw new IllegalStateException("这份原保存事件尚无可核对的安全正文交付定位，不扩大网页保存范围");
    }
    public static String browserText(JSONObject originalEvent)throws Exception{
        JSONObject projection=dev.pocketengine.browser.BrowserTaskSpace.savedMemoryProjection(originalEvent);
        Object excerpt=projection.get("excerpt");if(!(excerpt instanceof String))throw new SecurityException("原已保存网页摘录不是文本");String text=(String)excerpt;
        if(projection.has("structuredProjection"))text+="\n\n原已安全交付并批准保存的结构化范围：\n"+canonical(projection.getJSONObject("structuredProjection"));
        if(text.length()>100000)throw new IllegalStateException("原保存安全范围超出本次有界读取窗口");return text;
    }
    public static String appLearningText(JSONObject event)throws Exception {
        if(!"pocketworld.app-learning-evidence.v1".equals(event.optString("format"))||event.optBoolean("businessVerified",true))throw new SecurityException("不是原生未核实App经过");
        JSONObject context=event.getJSONObject("context"),evidence=event.getJSONObject("evidence"),app=evidence.getJSONObject("app");
        if(!"android_package_manager".equals(app.optString("source"))||!context.getString("packageName").equals(app.getString("packageName"))||!context.getString("appVersion").equals(app.getString("appVersion"))||evidence.getJSONArray("steps").length()<3)throw new SecurityException("缺少原App安装或动作证明");
        String text=canonical(new JSONObject().put("context",context).put("evidence",evidence));if(text.length()>24000)throw new SecurityException("原App证据超出有界窗口");return text;
    }
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    /** Single exact row only. Missing/replaced rows become a tombstone, never the latest event. */
    public static SourceRef.State resolve(SQLiteDatabase db,MemoryBook.Scope scope,String sourceId)throws Exception{
        Address a=address(sourceId);if(!"owner".equals(scope.ownerId)||!"private".equals(scope.spaceId))return null;
        SourceRef ref=new SourceRef("task_evidence",sourceId,a.eventSeq,scope.ownerId,scope.spaceId);JSONObject task=task(db,a.taskId);if(task==null)return new SourceRef.State(ref,true);
        JSONObject original=event(db,a.taskId,a.runId,a.eventSeq);if(original==null||!a.eventSha256.equals(TaskStore.capabilityFingerprint(original)))return new SourceRef.State(ref,true);
        requireSupported(original);
        if("app_learning_evidence".equals(original.optString("type"))&&original.getJSONObject("context").has("outcomeSourceRef")){
            SourceRef outcome=SourceRef.fromJson(original.getJSONObject("context").getJSONObject("outcomeSourceRef"));
            if(!outcome.sourceId.equals("task:"+a.taskId+":verified_result"))throw new SecurityException("App结果来源不是原task");
            try(Cursor verification=db.rawQuery("SELECT native_seq,completed FROM task_events WHERE task_id=? AND event_type='user_verification' ORDER BY event_order DESC LIMIT 1",new String[]{a.taskId})){
                if(!verification.moveToFirst()||verification.getLong(0)!=outcome.revision||verification.getInt(1)!=1)return new SourceRef.State(ref,true);
            }
        }
        return new SourceRef.State(ref,false);
    }
    public static JSONObject readEvent(SQLiteDatabase db,SourceRef expected)throws Exception{
        Address a=address(expected.sourceId);if(!"task_evidence".equals(expected.kind)||expected.revision!=a.eventSeq)throw new SecurityException("来源修订不是原事件本机投影版本");
        MemoryBook.Scope scope=MemoryBook.Scope.from(new JSONObject().put("ownerId",expected.ownerId).put("spaceId",expected.spaceId));SourceRef.State state=resolve(db,scope,expected.sourceId);
        if(state==null||state.deleted||!expected.equals(state.ref))throw new SecurityException("原保存事件已删除、替换或不属于当前来源范围");JSONObject original=event(db,a.taskId,a.runId,a.eventSeq);
        if(original==null||!a.eventSha256.equals(TaskStore.capabilityFingerprint(original)))throw new SecurityException("原保存事件在读取时改变，未交付正文");return original;
    }
    public static SourceRef select(SQLiteDatabase db,String taskId,String runId,long seq,int offset,int chars)throws Exception{
        JSONObject originalTask=task(db,taskId);if(originalTask==null)throw new SecurityException("原task已不可读");JSONObject original=event(db,taskId,runId,seq);if(original==null)throw new IllegalStateException("所选原事件已不可读");requireSupported(original);
        SourceRef ref=reference(taskId,runId,seq,original,offset,chars);TaskAuditStore.pinSequence(db,taskId,seq);return ref;
    }
    public static JSONObject list(SQLiteDatabase db,String taskId,long before,int limit)throws Exception{
        if(taskId==null||!taskId.matches(UUID)||before<0||limit<1||limit>20)throw new IllegalArgumentException("请选择原task与最多20条保存来源");if(task(db,taskId)==null)throw new SecurityException("原私人task已不可读");
        JSONArray entries=new JSONArray();long next=before==0?Long.MAX_VALUE:before,used=0;boolean more=false;
        try(Cursor rows=db.rawQuery("SELECT event_order,native_seq,event_type,body_bytes,CASE WHEN body_bytes>=0 AND body_bytes<=131072 THEN event_json END FROM task_events WHERE task_id=? AND event_order<? AND event_type IN ('attachment_document_read','browser_source_retained','app_learning_evidence','mcp_resource_retained') ORDER BY event_order DESC LIMIT ?",new String[]{taskId,Long.toString(next),Integer.toString(limit+1)})){
            while(rows.moveToNext()){
                if(entries.length()==limit){more=true;break;}long seq=rows.getLong(1),bytes=rows.getLong(3);next=rows.getLong(0);JSONObject item=new JSONObject().put("eventSeq",seq).put("eventOrder",next).put("type",rows.getString(2)).put("originalBodyIncluded",false);
                if(rows.isNull(4)||used+bytes>262144){entries.put(item.put("status","read_limited"));more=true;break;}used+=bytes;JSONObject original=new JSONObject(rows.getString(4));item.put("runId",original.optString("runId")).put("epoch",original.optInt("epoch"));
                try{requireSupported(original);if(seq<1||seq!=next||!original.optString("runId").matches(UUID))throw new IllegalStateException("原事件不是可核native序号");item.put("status","available").put("coverage",original.optString("coverage")).put("title","attachment_document_read".equals(rows.getString(2))?"原已读取文档的准确范围":"app_learning_evidence".equals(rows.getString(2))?"原限定App动作证据（仅候选）":McpResourceEvidence.EVENT_TYPE.equals(rows.getString(2))?"原已保存MCP文本快照（历史参考）":"原已批准保存的网页证据");}
                catch(Exception unavailable){item.put("status","source_unavailable").put("reason","native_exact_saved_delivery_pins_unavailable");}
                entries.put(item);
            }
        }
        return new JSONObject().put("taskId",taskId).put("entries",entries).put("nextEventOrder",more?next:0).put("hasMore",more).put("metadataOnly",true).put("completeHistory",false).put("coverage","original_native_saved_event_metadata_window_only").put("completeSavedMetadataWindow",!more&&before==0).put("executionAuthorized",false);
    }
    public static String excerpt(SourceRef ref,String text)throws Exception{
        Address a=address(ref.sourceId);if(a.offset>=text.length()||a.offset>0&&Character.isLowSurrogate(text.charAt(a.offset))&&Character.isHighSurrogate(text.charAt(a.offset-1)))throw new IllegalArgumentException("所选原保存范围起点无效或没有正文");
        int end=(int)Math.min((long)text.length(),(long)a.offset+a.chars);if(end<text.length()&&end>a.offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;if(end<=a.offset)throw new IllegalStateException("所选原保存范围没有文字");return text.substring(a.offset,end);
    }
}
