package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.util.ArrayList;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** B's own accepted requests, not the sender's outbox and not authority to execute one. */
final class ReceivedPhoneGoalIndex {
    private ReceivedPhoneGoalIndex() {}
    private enum Shelf{PEER,PENDING,RESULT,CLOSED,STARTED,DECLINED}
    private static final Set<String> OPEN_PHASES=Set.of("preparing","running","pausing","paused","stopping","verifying","awaiting_user","waiting_user",
            "awaiting_approval","waiting_approval","waiting_permission","interrupted","reconciling","reconciliation_required","blocked_unknown");

    static JSONObject read(SQLiteDatabase db,String here,String localInstance,String source,String afterTask,String afterOp)throws Exception {
        return read(db,here,localInstance,source,afterTask,afterOp,"");
    }
    static JSONObject read(SQLiteDatabase db, String here, String localInstance, String source, String afterTask, String afterOp,String searchText) throws Exception {
        return scan(db,here,localInstance,DeviceProtocol.deviceId(source,"callerDeviceId"),afterTask,afterOp,Shelf.PEER,new LocalPhoneGoalSearch(searchText));
    }
    static JSONObject waiting(SQLiteDatabase db,String here,String localInstance,String afterCaller,String afterOp,boolean results)throws Exception{
        return scan(db,here,localInstance,null,afterCaller,afterOp,results?Shelf.RESULT:Shelf.PENDING,new LocalPhoneGoalSearch(""));
    }
    static JSONObject declined(SQLiteDatabase db,String here,String instance,String afterCaller,String afterOp)throws Exception{
        return scan(db,here,instance,null,afterCaller,afterOp,Shelf.DECLINED,new LocalPhoneGoalSearch(""));
    }
    static JSONObject started(SQLiteDatabase db,String here,String instance,String afterCaller,String afterOp)throws Exception{
        return scan(db,here,instance,null,afterCaller,afterOp,Shelf.STARTED,new LocalPhoneGoalSearch(""));
    }
    static JSONObject closed(SQLiteDatabase db,String here,String instance,String afterCaller,String afterOp,String query)throws Exception{
        return scan(db,here,instance,null,afterCaller,afterOp,Shelf.CLOSED,new LocalPhoneGoalSearch(query));
    }
    private static JSONObject scan(SQLiteDatabase db,String here,String localInstance,String issuer,String anchorId,String afterOp,Shelf shelf,LocalPhoneGoalSearch search)throws Exception{
        boolean results=shelf==Shelf.RESULT,closed=shelf==Shelf.CLOSED,started=shelf==Shelf.STARTED,declined=shelf==Shelf.DECLINED;
        int limit=(closed||declined)?6:24;
        DeviceProtocol.deviceId(here,"targetDeviceId"); DeviceProtocol.identifier(localInstance,"instanceId");
        boolean waiting=issuer==null; String where=" WHERE i.method='task.submitGoal'";
        ArrayList<String> args=new ArrayList<>();
        if(closed)where+=" AND EXISTS(SELECT 1 FROM runs r WHERE r.task_id=i.local_task_id AND r.status IN ('completed','cancelled'))";
        else if(declined)where+=" AND EXISTS(SELECT 1 FROM task_events e WHERE e.task_id=i.local_task_id AND e.event_type='"+ReceivedPhoneDeclineHistory.EVENT+"') AND NOT EXISTS(SELECT 1 FROM runs r WHERE r.task_id=i.local_task_id)";
        else if(started)where+=" AND EXISTS(SELECT 1 FROM runs r WHERE r.task_id=i.local_task_id AND r.status NOT IN ('completed','failed','cancelled','canceled','stopped'))";
        else if(waiting) where+=" AND "+(results?"":"NOT ")+"EXISTS(SELECT 1 FROM runs r WHERE r.task_id=i.local_task_id)"; // runs has UNIQUE(task_id,epoch).
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
            + "FROM device_inbound i LEFT JOIN tasks t ON t.task_id=i.local_task_id"+where+" ORDER BY i.accepted_at DESC,i.caller_device_id,i.remote_operation_id LIMIT "+(limit+1);
        JSONArray items = new JSONArray(); String task="",op="",source=""; int scanned=0,limited=0; boolean more=false;
        try (Cursor c = db.rawQuery(sql,args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                if (scanned++ == limit) { more=true; break; }
                task=c.getString(0); op=c.getString(1); source=c.getString(8);
                if (here.equals(source) || c.getLong(2)<=0 || c.getLong(5)<=0) continue;
                if(c.isNull(6)||c.isNull(7)){if(started||declined)limited++;continue;}
                try {
                    JSONObject wire=new JSONObject(c.getString(6)), header=new JSONObject(c.getString(7)); boolean textOnly=DeviceProtocol.ownerTextRequest(wire);
                    if(shelf==Shelf.PENDING && (!header.optString("runId").isEmpty() || header.optInt("coordinatorEpoch",-1)!=0 || !Boolean.FALSE.equals(header.opt("cancelRequested"))
                            || !("queued".equals(header.opt("status"))||"awaiting_user".equals(header.opt("status"))||"interrupted".equals(header.opt("status")))))continue;
                    JSONObject origin=header.getJSONObject("origin"); String parent=wire.getString("parentTaskId"), digest=c.getString(3);
                    if (!here.equals(wire.opt("targetDeviceId")) || !localInstance.equals(wire.opt("instanceId"))
                            || !source.equals(wire.opt("callerDeviceId")) || !source.equals(wire.opt("originDeviceId"))
                            || !(DeviceProtocol.isDirectPhoneGoal(wire,source,parent,op)||textOnly) || !task.equals(DeviceProtocol.receiverTaskId(wire))
                            || !digest.equals(wire.opt("argumentsDigest")) || !digest.equals(DeviceProtocol.argumentsDigest(wire))
                            || !c.getString(4).equals(wire.opt("grantId")) || c.getLong(5)!=wire.getLong("authorizationEpoch")) continue;
                    String mode=wire.getJSONObject("resourceBinding").getString("screenMode");
                    if(textOnly&&!wire.getJSONObject("arguments").getString("textReturnContract").equals(origin.opt("textReturnContract")))continue;
                    boolean zero=wire.getJSONObject("budget").optLong("toolCalls",-1)==0;
                    if (!task.equals(header.opt("id")) || !DeviceProtocol.receivedHostMatches(mode,zero,header)
                            || !("paired_device:"+source).equals(header.opt("actorId")) || !("device:"+source).equals(header.opt("spaceId"))
                            || !"paired_device".equals(origin.opt("kind")) || !source.equals(origin.opt("callerDeviceId")) || !source.equals(origin.opt("originDeviceId"))
                            || !here.equals(origin.opt("targetDeviceId")) || !op.equals(origin.opt("remoteOperationId")) || !parent.equals(origin.opt("rootTaskId"))
                            || !parent.equals(origin.opt("parentTaskId")) || !wire.getString("localTaskId").equals(origin.opt("remoteLocalTaskId"))
                            || !c.getString(4).equals(origin.opt("grantId")) || c.getLong(5)!=origin.getLong("authorizationEpoch")) continue;
                    long declinedAt=0;
                    if(declined){
                        try{declinedAt=ReceivedPhoneDeclineHistory.checkedAt(db,task,source,op,here,localInstance,mode,digest,c.getLong(2),origin);}
                        catch(IllegalStateException|SecurityException|JSONException unavailable){limited++;continue;}
                    }
                    JSONObject startedRun=null;
                    if(started){
                        String phase=header.optString("status"),run=header.optString("runId");int epoch=header.optInt("coordinatorEpoch",-1);
                        if(!OPEN_PHASES.contains(phase)
                                ||run.isEmpty()||epoch<=0||!(header.opt("cancelRequested") instanceof Boolean))continue;
                        try(Cursor original=db.rawQuery("SELECT status,created_at FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{task,run,Integer.toString(epoch)})){
                            if(!original.moveToFirst()||original.getLong(1)<=0){limited++;continue;}
                            String recorded=original.getString(0);
                            if(!OPEN_PHASES.contains(recorded)&&!"needs_confirmation".equals(recorded)){limited++;continue;}
                            // Thread liveness and restartability are NOT implied by either persisted phase.
                            startedRun=new JSONObject().put("runId",run).put("epoch",epoch).put("taskState",phase).put("runState",recorded)
                                    .put("registeredAt",original.getLong(1)).put("stopRequested",header.getBoolean("cancelRequested"));
                        }
                    }
                    JSONObject closedReview=null;
                    if(closed){
                        if(!zero||!("completed".equals(header.opt("status"))||"cancelled".equals(header.opt("status")))||header.optString("runId").isEmpty()||header.optInt("coordinatorEpoch")<=0)continue;
                        JSONObject selected=new JSONObject().put("taskId",task).put("operationId",op).put("localId",here).put("localInstance",localInstance)
                                .put("sourceId",source).put("screenMode",mode).put("wireDigest",digest).put("originDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin)));
                        try{closedReview=ReceivedPhoneReviewArchive.checkedMetadata(db,selected,search);if(closedReview==null)continue;}
                        catch(IllegalStateException|SecurityException|JSONException unavailable){limited++;continue;}
                    }
                    JSONObject review=null;
                    if(results){
                        if(!"needs_confirmation".equals(header.opt("status")))continue;
                        review=header.optJSONObject("receivedPhoneResultSlot");
                        if(review==null)review=legacySlot(db,task,header);
                        if(review!=null&&review.optBoolean("limited")){limited++;continue;}
                        if(review==null || !header.getString("runId").equals(review.opt("runId"))
                                || header.getInt("coordinatorEpoch")!=review.optInt("epoch",-1)||review.optLong("recordedAt")<=0)continue;
                        try(Cursor r=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND epoch=? AND run_id=?",
                                new String[]{task, Integer.toString(review.getInt("epoch")),review.getString("runId")})){if(!r.moveToFirst())continue;}
                    }
                    String goal=wire.getJSONObject("arguments").getString("goal"); if (goal.trim().isEmpty()||!closed&&!search.matches(goal)) continue;
                    if (goal.codePointCount(0,goal.length())>160) goal=goal.substring(0,goal.offsetByCodePoints(0,160))+"…";
                    items.put(new JSONObject().put("taskId",task).put("operationId",op).put("argumentsDigest",digest)
                            .put("sourceDeviceId",source).put("declinedAt",declined?Long.valueOf(declinedAt):null).put("startedRun",startedRun).put("closedReview",closedReview).put("mayCheckLocalText",!waiting&&zero&&"completed".equals(header.opt("status"))&&!header.optString("runId").isEmpty()&&header.optInt("coordinatorEpoch")>0?true:null)
                            .put("textOnly",textOnly||zero&&"assistant".equals(header.opt("kind"))?true:null).put("reviewRun",review==null?null:review.getString("runId"))
                            .put("reviewEpoch",review==null?null:review.getInt("epoch")).put("reviewRecordedAt",review==null?null:review.getLong("recordedAt"))
                            .put("waitingState",declined?"declined_before_start":started?"original_run":results?"result_review":waiting&&!closed?("interrupted".equals(header.opt("status"))?"interrupted_before_start":"not_started"):null).put("originDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(origin))).put("screenMode",mode)
                            .put("goal",goal).put("matchedLater",!closed&&search.beyondPreview(goal)?true:null).put("createdAt",c.getLong(2)));
                } catch (JSONException | DeviceProtocol.ProtocolException invalid) { /* Never replace missing provenance with a nearby task. */ }
            }
        }
        return new JSONObject().put("rows",items).put("hasMore",more).put("lastParent",waiting?source:task).put("lastOperation",op)
                .put("localDeviceId",here).put("localInstanceId",localInstance).put("targetDeviceId",issuer).put("waitingOnly",waiting&&!closed&&!started&&!declined).put("declinedOnly",declined?Boolean.TRUE:null).put("startedOnly",started?Boolean.TRUE:null).put("closedOnly",closed?Boolean.TRUE:null).put("resultOnly",results).put("limitedCandidates",limited).put("readOnly",true);
    }

    /** Non-content marker from the same native task write. No summary/evidence is put in the queue. */
    static JSONObject pendingSlot(JSONObject source)throws JSONException {
        JSONObject result=source.optJSONObject("deviceGoalResult");String run=source.optString("runId");int epoch=source.optInt("coordinatorEpoch");
        JSONObject origin=source.optJSONObject("origin");String caller=origin==null?"":origin.optString("callerDeviceId");
        if(origin==null||!"paired_device".equals(origin.opt("kind"))||caller.isEmpty()||!caller.equals(origin.opt("originDeviceId"))
                ||!origin.optString("rootTaskId").equals(origin.opt("parentTaskId"))||!("paired_device:"+caller).equals(source.opt("actorId"))
                ||!("device:"+caller).equals(source.opt("spaceId"))||DeviceProtocol.receivedPhoneScopeMode(source).isEmpty())return null;
        if(!"needs_confirmation".equals(source.opt("status"))
                ||run.isEmpty()||epoch<1||result==null||!"needs_confirmation".equals(result.opt("status"))
                ||!Boolean.FALSE.equals(result.opt("closed"))||!Boolean.TRUE.equals(result.opt("nativeRunnerExited"))
                ||!Boolean.TRUE.equals(result.opt("nativeDispatchClosed"))||!run.equals(result.opt("runId"))||epoch!=result.optInt("executorEpoch",-1)
                ||result.optLong("recordedAt")<=0)return null;
        return new JSONObject().put("runId",run).put("epoch",epoch).put("recordedAt",result.getLong("recordedAt"));
    }
    private static JSONObject legacySlot(SQLiteDatabase db,String id,JSONObject header)throws Exception{
        // Old installs had no marker. Only inspect one bounded candidate core at
        // a time, after identifying the original source and needs-confirmation status.
        try(Cursor c=db.rawQuery("SELECT CASE WHEN length(CAST(document AS BLOB))<="+TaskAuditStore.MAX_CORE_BYTES+
                " THEN document END FROM tasks WHERE task_id=?",new String[]{id})){
            if(!c.moveToFirst())return null;if(c.isNull(0))return new JSONObject().put("limited",true);JSONObject source=new JSONObject(c.getString(0));
            if(!id.equals(source.opt("id"))||!DeviceProtocol.canonical(header.getJSONObject("origin")).equals(DeviceProtocol.canonical(source.getJSONObject("origin"))))return null;
            return pendingSlot(source);
        }
    }
}
