package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DevicePrivateText;
import dev.pocketengine.features.devices.DeviceProtocol;
import org.json.JSONObject;

/** B owner's local reread, never a peer endpoint. The archive chooses the original row;
 * SQL must still prove both the inbound wire and the specific closed native owner review. */
final class ReceivedPhoneReviewArchive {
    private static final int PAGE=1400;
    private ReceivedPhoneReviewArchive(){}
    static JSONObject read(SQLiteDatabase db,JSONObject selection,int page,String stamp)throws Exception{
        return read(db,selection,page,stamp,false,null);
    }
    static JSONObject checkedMetadata(SQLiteDatabase db,JSONObject selection,LocalPhoneGoalSearch search)throws Exception{return read(db,selection,0,"",true,search);}
    private static JSONObject read(SQLiteDatabase db,JSONObject selection,int page,String stamp,boolean metadataOnly,LocalPhoneGoalSearch search)throws Exception{
        String id=selection.getString("taskId"),op=selection.getString("operationId"),caller=selection.getString("sourceId"),
                here=selection.getString("localId"),instance=selection.getString("localInstance");
        if(page<0||page>TaskAuditStore.MAX_CORE_BYTES/PAGE+1||page>0&&stamp.isEmpty())throw new SecurityException("请从本次原结果的页码读取");
        String sql="SELECT i.arguments_digest,i.grant_id,i.authorization_epoch,i.accepted_at,"
                +"CASE WHEN length(CAST(i.envelope_json AS BLOB))<="+DeviceProtocol.MAX_CONTROL_BYTES+" THEN i.envelope_json END,"
                +"CASE WHEN length(CAST(t.document AS BLOB))<="+TaskAuditStore.MAX_CORE_BYTES+" THEN t.document END "
                +"FROM device_inbound i JOIN tasks t ON t.task_id=i.local_task_id WHERE i.caller_device_id=? AND i.remote_operation_id=? AND i.local_task_id=? AND i.method='task.submitGoal'";
        String part=selection.optString("displayPart","result");
        if(!"result".equals(part)&&!("goal".equals(part)&&!stamp.isEmpty()&&!metadataOnly))throw new SecurityException("只能从当时已核对的原结果打开收到的目标");
        JSONObject task;String receivedGoal;
        try(Cursor row=db.rawQuery(sql,new String[]{caller,op,id})){
            if(!row.moveToFirst()||row.isNull(4))throw new SecurityException("原接纳记录不可核对");
            if(row.isNull(5))throw new IllegalStateException("原工作正文超过本机这次读取的上限，未使用通用状态作为成果");
            JSONObject wire=new JSONObject(row.getString(4));task=new JSONObject(row.getString(5));
            JSONObject from=task.getJSONObject("origin");String wireDigest=row.getString(0),parent=wire.getString("parentTaskId");
            if(caller.equals(here)||row.getLong(3)<=0||row.getLong(2)<=0||!here.equals(wire.opt("targetDeviceId"))||!instance.equals(wire.opt("instanceId"))
                    ||!caller.equals(wire.opt("originDeviceId"))||!caller.equals(wire.opt("callerDeviceId"))||!op.equals(wire.opt("operationId"))||!parent.equals(wire.opt("rootTaskId"))
                    ||!(DeviceProtocol.isDirectPhoneGoal(wire,caller,parent,op)||DeviceProtocol.ownerTextRequest(wire))
                    ||!id.equals(DeviceProtocol.receiverTaskId(wire))||!wireDigest.equals(selection.opt("wireDigest"))||!wireDigest.equals(wire.opt("argumentsDigest"))||!wireDigest.equals(DeviceProtocol.argumentsDigest(wire))
                    ||!row.getString(1).equals(wire.opt("grantId"))||row.getLong(2)!=wire.optLong("authorizationEpoch")||!row.getString(1).equals(from.opt("grantId"))||row.getLong(2)!=from.optLong("authorizationEpoch")
                    ||!id.equals(task.opt("id"))||!instance.equals(task.opt("deviceNativeInstanceId"))||!DevicePrivateText.mayKeepOriginalText(task)
                    ||!("paired_device:"+caller).equals(task.opt("actorId"))||!("device:"+caller).equals(task.opt("spaceId"))
                    ||!caller.equals(from.opt("callerDeviceId"))||!here.equals(from.opt("targetDeviceId"))||!op.equals(from.opt("remoteOperationId"))||!parent.equals(from.opt("rootTaskId"))
                    ||!wire.getString("localTaskId").equals(from.opt("remoteLocalTaskId"))||!selection.getString("screenMode").equals(DeviceProtocol.receivedPhoneScopeMode(task))
                    ||!selection.getString("originDigest").equals(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(from))))
                throw new SecurityException("原手机、任务或派单身份不一致，未打开文字");
            // The source text is precisely the bounded goal that was signed and accepted by THIS B.
            // The surrounding envelope, context, attachments and execution evidence are never projected.
            receivedGoal=wire.getJSONObject("arguments").getString("goal");
        }
        JSONObject saved=task.optJSONObject("deviceGoalResult"),evidence=task.optJSONObject("evidence");
        JSONObject check=evidence==null?null:evidence.optJSONObject("nativeDeviceGoalOwnerReview");
        String run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch",-1);
        // A rejected result is eligible ONLY for the receiver's decision index, never for its text reader.
        boolean notConfirmed=metadataOnly && "cancelled".equals(task.opt("status"));
        if(notConfirmed && (saved==null||check==null||!Boolean.FALSE.equals(saved.opt("ownerVerified"))||!Boolean.FALSE.equals(check.opt("completed"))))return null;
        String terminal=notConfirmed?"cancelled":"completed";
        if(!terminal.equals(task.opt("status"))||run.isEmpty()||epoch<=0||saved==null||check==null
                ||!run.equals(saved.opt("runId"))||epoch!=saved.optInt("executorEpoch",-1)||!"needs_confirmation".equals(saved.opt("status"))
                ||!Boolean.TRUE.equals(saved.opt("closed"))||!Boolean.valueOf(!notConfirmed).equals(saved.opt("ownerVerified"))||!Boolean.TRUE.equals(saved.opt("nativeRunnerExited"))||!Boolean.TRUE.equals(saved.opt("nativeDispatchClosed"))
                ||!Boolean.valueOf(!notConfirmed).equals(check.opt("completed"))||!Boolean.TRUE.equals(check.opt("sameRun"))||!(saved.opt("summary") instanceof String)
                ||saved.optLong("recordedAt")<=0||saved.optLong("reviewedAt")<=0)
            throw new IllegalStateException("没有这一次已完成且经本机机主核对的原结果；未用结束状态代替文字");
        try(Cursor seen=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? AND status=?",new String[]{id,run,Integer.toString(epoch),terminal})){
            if(!seen.moveToFirst())throw new IllegalStateException("原执行的本机结束记录不可核对");
        }
        boolean kept=saved.has("ownerOnlyText")||check.has("ownerOnlyText");
        if(kept&&(!Boolean.TRUE.equals(saved.opt("ownerOnlyText"))||!Boolean.TRUE.equals(check.opt("ownerOnlyText"))))throw new SecurityException("原核对中记录的文字去向不一致");
        JSONObject reviewed=new JSONObject(saved.toString());reviewed.put("closed",false);reviewed.remove("ownerVerified");reviewed.remove("reviewedAt");reviewed.remove("ownerOnlyText");
        String digest=TaskStore.capabilityFingerprint(reviewed);
        if(!digest.equals(check.opt("resultDigest")))throw new SecurityException("本机原结果与当时核对的内容不一致");
        if(notConfirmed){
            if(kept)throw new SecurityException("未确认完成的决定不匹配原文字去向");
            // Only the original request can match; neither a result fragment nor a reader reference is issued.
            if(search!=null&&search.active()&&!search.matches(receivedGoal))return null;
            return new JSONObject().put("notConfirmed",true).put("reviewedAt",saved.getLong("reviewedAt"));
        }
        String reference=TaskStore.capabilityFingerprint(new JSONObject().put("task",id).put("operation",op).put("wire",selection.getString("wireDigest"))
                .put("run",run).put("epoch",epoch).put("review",digest).put("at",saved.getLong("reviewedAt")).put("kept",kept));
        if(!stamp.isEmpty()&&!stamp.equals(reference))throw new SecurityException("本机原结果已变化，不可接读另一份文字");
        if(metadataOnly){
            JSONObject meta=new JSONObject().put("hasText",!saved.getString("summary").isEmpty()).put("runId",run).put("epoch",epoch).put("reviewedAt",saved.getLong("reviewedAt")).put("keptOnPhone",kept).put("stamp",reference);
            if(search!=null&&search.active()){
                int goal=search.firstMatchingPage(receivedGoal,PAGE),answer=search.firstMatchingPage(saved.getString("summary"),PAGE);
                if(goal<0&&answer<0)return null;
                meta.put("matchPart",answer>=0?"result":"goal").put("matchPage",answer>=0?answer:goal).put("bothParts",goal>=0&&answer>=0);
            }
            return meta;
        }
        String text="goal".equals(part)?receivedGoal:saved.getString("summary");int count=text.codePointCount(0,text.length()),pages=Math.max(1,(count+PAGE-1)/PAGE);
        if(page>=pages)throw new IllegalArgumentException("不在这份原文字的分页范围");
        int start=text.offsetByCodePoints(0,page*PAGE),end=text.offsetByCodePoints(start,Math.min(PAGE,count-page*PAGE));
        return new JSONObject().put("kind","localReceivedReviewedText").put("readOnly",true).put("taskId",id).put("operationId",op)
                .put("sourceId",caller).put("localId",here).put("localInstance",instance).put("runId",run).put("epoch",epoch).put("reviewedAt",saved.getLong("reviewedAt"))
                .put("keptOnPhone",kept).put("part",part).put("longOriginalGoal",receivedGoal.codePointCount(0,receivedGoal.length())>160).put("page",page).put("pages",pages).put("stamp",reference).put("text",text.substring(start,end));
    }
}
