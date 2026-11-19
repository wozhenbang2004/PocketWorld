package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Iterator;
import org.json.JSONObject;

/** A late host teardown may not replace a committed receiver decision. This proves the
 * decision only locally; it is not a result reader, an effect receipt or authority to run. */
final class ClosedReceivingPhoneDecision {
    private ClosedReceivingPhoneDecision(){}

    static boolean matches(SQLiteDatabase db,JSONObject task,String id,String run,String attemptedStatus)throws Exception {
        if(!TaskStoreDeviceTasks.deviceTask(task)||!"GOAL".equals(task.opt("deviceExecutionKind"))
                ||!("cancelled".equals(attemptedStatus)||"interrupted".equals(attemptedStatus)
                     ||"awaiting_user".equals(attemptedStatus)||"paused".equals(attemptedStatus)))return false;
        String decided=task.optString("status");
        if(!("completed".equals(decided)||"cancelled".equals(decided)))return false;
        JSONObject result=task.optJSONObject("deviceGoalResult"),evidence=task.optJSONObject("evidence"),
                check=evidence==null?null:evidence.optJSONObject("nativeDeviceGoalOwnerReview");
        JSONObject origin=task.getJSONObject("origin");
        int epoch=task.optInt("coordinatorEpoch");
        if(result==null||check==null||run.isEmpty()||epoch<1||!id.equals(task.opt("id"))||!run.equals(task.opt("runId"))
                ||task.has("executionScope")||task.optJSONObject("deviceExecutionScope")==null
                ||!("paired_device:"+origin.optString("originDeviceId")).equals(task.opt("actorId"))
                ||!("device:"+origin.optString("callerDeviceId")).equals(task.opt("spaceId"))
                ||!run.equals(result.opt("runId"))||epoch!=result.optInt("executorEpoch")
                ||!"needs_confirmation".equals(result.opt("status"))||!Boolean.TRUE.equals(result.opt("closed"))
                ||!Boolean.TRUE.equals(result.opt("nativeRunnerExited"))||!Boolean.TRUE.equals(result.opt("nativeDispatchClosed"))
                ||!(result.opt("ownerVerified") instanceof Boolean confirmed)||!confirmed.equals(check.opt("completed"))
                ||!Boolean.TRUE.equals(check.opt("sameRun"))||result.optLong("recordedAt")<=0||result.optLong("reviewedAt")<=0
                ||!(check.opt("resultDigest") instanceof String digest)||!digest.matches("[a-f0-9]{64}"))return false;
        if("completed".equals(decided)!=confirmed && !(confirmed&&"cancelled".equals(decided)&&task.optBoolean("cancelRequested")))return false;
        if((result.has("ownerOnlyText")||check.has("ownerOnlyText"))
                &&(!confirmed||!Boolean.TRUE.equals(result.opt("ownerOnlyText"))||!Boolean.TRUE.equals(check.opt("ownerOnlyText"))))return false;
        try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? AND status=?",new String[]{id,run,Integer.toString(epoch),decided})){
            if(!row.moveToFirst())return false;
        }
        // Only a stale teardown of an already ended, reviewed run reaches this digest.
        // Reuse the original review representation without mutating or returning private text.
        JSONObject original=new JSONObject();
        for(Iterator<String> keys=result.keys();keys.hasNext();){
            String key=keys.next();
            if(!key.equals("ownerVerified")&&!key.equals("reviewedAt")&&!key.equals("ownerOnlyText"))
                original.put(key,key.equals("closed")?Boolean.FALSE:result.get(key));
        }
        return digest.equals(TaskStore.capabilityFingerprint(original));
    }
}
