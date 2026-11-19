package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import org.json.JSONObject;

/** Receiver's own decision index for direct, nonzero-tool phone goals. Deliberately NOT a text
 * or evidence reader. Only the private native navigation proof sees the digest. */
public final class ReceivedScreenGoalDecision {
    private ReceivedScreenGoalDecision(){}
    private static SecurityException changed(){return new SecurityException("原屏幕工作或当时的本机核对决定已变化");}
    static JSONObject metadata(SQLiteDatabase db,JSONObject header,JSONObject wire)throws Exception{
        String id=header.getString("id"),run=header.getString("runId"),terminal=header.getString("status");
        int epoch=header.getInt("coordinatorEpoch");
        if(!terminal.matches("completed|cancelled")||epoch<=0||run.isEmpty()||wire.getJSONObject("budget").getLong("toolCalls")<1)return null;
        String sql="SELECT CASE WHEN length(CAST(t.document AS BLOB))<="+TaskAuditStore.MAX_CORE_BYTES+" THEN t.document END,r.status FROM tasks t JOIN runs r ON r.task_id=t.task_id WHERE t.task_id=? AND r.run_id=? AND r.epoch=?";
        try(Cursor row=db.rawQuery(sql,new String[]{id,run,Integer.toString(epoch)})){
            if(!row.moveToFirst()||row.isNull(0)||!terminal.equals(row.getString(1)))throw changed();
            JSONObject t=new JSONObject(row.getString(0));String mode=wire.getJSONObject("resourceBinding").getString("screenMode");
            String caller=wire.getString("callerDeviceId");
            if(!id.equals(t.opt("id"))||!id.equals(DeviceProtocol.receiverTaskId(wire))||!("paired_device:"+caller).equals(t.opt("actorId"))
                ||!("device:"+caller).equals(t.opt("spaceId"))||!"GOAL".equals(t.opt("deviceExecutionKind"))||!wire.getString("instanceId").equals(t.opt("deviceNativeInstanceId"))
                ||!wire.getJSONObject("arguments").getString("goal").equals(t.opt("goal"))||!DeviceProtocol.receivedHostMatches(mode,false,t)
                ||!DeviceProtocol.canonical(header.getJSONObject("origin")).equals(DeviceProtocol.canonical(t.getJSONObject("origin"))))throw changed();
            JSONObject result=t.optJSONObject("deviceGoalResult"),e=t.optJSONObject("evidence"),check=e==null?null:e.optJSONObject("nativeDeviceGoalOwnerReview");
            if(result==null||check==null||!Boolean.TRUE.equals(result.opt("closed")))return null; // Cancelled without an owner result decision is not a review.
            JSONObject proof=new JSONObject().put("screenDecision",true).put("confirmed","completed".equals(terminal))
                    .put("runId",run).put("epoch",epoch).put("sourceRevision",t.getLong("deviceStateRevision")).put("reviewedAt",result.getLong("reviewedAt")).put("decisionDigest",check.getString("resultDigest"));
            requireSame(t,proof);
            // Hash the actual previously closed review ONCE in the bounded worker-side SQL read.
            JSONObject original=new JSONObject(result.toString());original.put("closed",false);original.remove("ownerVerified");original.remove("reviewedAt");
            if(!TaskStore.capabilityFingerprint(original).equals(proof.getString("decisionDigest")))throw changed();
            return proof;
        }
    }
    /** Navigation only: compare the original immutable owner receipt and task revision; the UI
     * may call this more than once, so never reserialize/re-hash the whole result on its thread. */
    public static void requireSame(JSONObject task,JSONObject proof)throws Exception{
        String run=proof.getString("runId");int epoch=proof.getInt("epoch");boolean confirmed=proof.getBoolean("confirmed");
        JSONObject r=task.getJSONObject("deviceGoalResult"),c=task.getJSONObject("evidence").getJSONObject("nativeDeviceGoalOwnerReview");
        long at=proof.getLong("reviewedAt");
        if(!Boolean.TRUE.equals(proof.opt("screenDecision"))||!Boolean.FALSE.equals(task.opt("cancelRequested"))||task.optLong("deviceStateRevision",-1)!=proof.getLong("sourceRevision")||!run.equals(task.opt("runId"))||epoch!=task.optInt("coordinatorEpoch",-1)
                ||!(confirmed?"completed":"cancelled").equals(task.opt("status"))||!run.equals(r.opt("runId"))||epoch!=r.optInt("executorEpoch",-1)
                ||!"needs_confirmation".equals(r.opt("status"))||!Boolean.TRUE.equals(r.opt("closed"))||!Boolean.TRUE.equals(r.opt("nativeRunnerExited"))
                ||!Boolean.TRUE.equals(r.opt("nativeDispatchClosed"))||!Boolean.valueOf(confirmed).equals(r.opt("ownerVerified"))
                ||!Boolean.valueOf(confirmed).equals(c.opt("completed"))||!Boolean.TRUE.equals(c.opt("sameRun"))||!(r.opt("summary") instanceof String)
                ||r.has("ownerOnlyText")||c.has("ownerOnlyText")||r.optLong("recordedAt")<=0||at<r.getLong("recordedAt")||at!=r.optLong("reviewedAt"))throw changed();
        if(!proof.getString("decisionDigest").equals(c.opt("resultDigest")))throw changed();
    }
}
