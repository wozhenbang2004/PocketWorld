package dev.pocketengine.features.semantic;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;
import android.os.UserManager;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.setup.AndroidAppCopies;
import java.util.Objects;
import org.json.JSONObject;

/** Read-only evidence for the current profile's next alarm, never an alarm creation receipt. */
final class NextAlarmEvidence {
    private static final long WAIT_MS=3_000L,POLL_MS=150L;
    private final Context context;
    private final int userId;
    private final long profileSerial;
    private volatile Snapshot before=Snapshot.unavailable("not_observed");
    private boolean captured;

    static NextAlarmEvidence forRequest(Context context,SemanticRequest request,SemanticPlatform.Target target)throws Exception {
        return "alarm".equals(request.kind)?new NextAlarmEvidence(context,target):null;
    }
    private NextAlarmEvidence(Context context,SemanticPlatform.Target target)throws Exception {
        this.context=context.getApplicationContext();
        JSONObject identity=target.identity();userId=identity.getInt("userId");profileSerial=identity.getLong("profileSerial");
    }
    synchronized void captureBefore() {if(!captured){before=read();captured=true;}}

    /** The native acknowledgement survives every optional observation failure or stop. */
    JSONObject afterDispatch(JSONObject receipt,RunControl control,SemanticPlatform.Admission admission) {
        if(receipt==null||!Boolean.TRUE.equals(receipt.opt("accepted"))||"unknown".equals(receipt.optString("status")))return receipt;
        Snapshot after=Snapshot.unavailable("not_observed");
        long started=SystemClock.uptimeMillis(),pauseRevision=control.pauseRevision();
        String outcome="observation_unavailable";
        try {
            do {
                check(control,pauseRevision,admission);
                Snapshot observed=read();
                check(control,pauseRevision,admission);
                after=observed;
                if(!after.readable){outcome="observation_unavailable";break;}
                if(!before.readable){outcome="baseline_unavailable";break;}
                if(!Objects.equals(before.triggerAtEpochMs,after.triggerAtEpochMs)){outcome="change_observed";break;}
                long remaining=WAIT_MS-(SystemClock.uptimeMillis()-started);
                if(remaining<=0){outcome="deadline_reached";break;}
                Thread.sleep(Math.min(POLL_MS,remaining));
            } while(true);
        } catch(InterruptedException stopped) {
            if(!(stopped instanceof RunControl.ConditionWaitException))Thread.currentThread().interrupt();
            outcome="observation_stopped";
        } catch(Exception unavailable) {outcome="observation_stopped";}
        try {
            boolean comparable=before.readable&&after.readable;
            JSONObject evidence=new JSONObject().put("source","android.app.AlarmManager.getNextAlarmClock")
                    .put("scope","current_android_user").put("userId",userId).put("profileSerial",profileSerial)
                    .put("before",before.json()).put("after",after.json()).put("comparisonField","triggerAtEpochMs")
                    .put("nextAlarmChanged",comparable?!Objects.equals(before.triggerAtEpochMs,after.triggerAtEpochMs):JSONObject.NULL)
                    .put("observationOutcome",outcome).put("observationWindowMs",SystemClock.uptimeMillis()-started)
                    .put("maximumWaitMs",WAIT_MS).put("readOnly",true).put("allAlarmsListed",false)
                    .put("requestedAlarmVerified",false).put("causedByThisRequestVerified",false);
            return SemanticJson.copy(receipt).put("nextAlarmClock",evidence);
        } catch(Exception unavailable) {return receipt;}
    }
    private static void check(RunControl control,long pauseRevision,SemanticPlatform.Admission admission)throws Exception {
        control.check();
        if(control.isPaused()||control.pauseRevision()!=pauseRevision)throw new SecurityException("original_alarm_observation_stopped");
        admission.check();
    }
    private Snapshot read() {
        try {
            if(!scopeCurrent())return Snapshot.unavailable("profile_unavailable");
            AlarmManager manager=context.getSystemService(AlarmManager.class);
            if(manager==null)return Snapshot.unavailable("alarm_service_unavailable");
            AlarmManager.AlarmClockInfo alarm=manager.getNextAlarmClock();
            if(!scopeCurrent())return Snapshot.unavailable("profile_changed");
            return new Snapshot(true,alarm==null?null:alarm.getTriggerTime(),"",SystemClock.uptimeMillis());
        } catch(RuntimeException unavailable) {return Snapshot.unavailable("alarm_read_unavailable");}
    }
    private boolean scopeCurrent() {
        UserManager users=context.getSystemService(UserManager.class);
        KeyguardManager lock=context.getSystemService(KeyguardManager.class);
        return AndroidAppCopies.id(android.os.Process.myUserHandle())==userId&&users!=null&&users.isUserUnlocked()
                &&users.getSerialNumberForUser(android.os.Process.myUserHandle())==profileSerial
                &&lock!=null&&!lock.isDeviceLocked();
    }
    private static final class Snapshot {
        final boolean readable;final Long triggerAtEpochMs;final String unavailableReason;final long observedAtUptimeMs;
        Snapshot(boolean readable,Long triggerAtEpochMs,String unavailableReason,long observedAtUptimeMs){
            this.readable=readable;this.triggerAtEpochMs=triggerAtEpochMs;this.unavailableReason=unavailableReason;this.observedAtUptimeMs=observedAtUptimeMs;
        }
        static Snapshot unavailable(String reason){return new Snapshot(false,null,reason,SystemClock.uptimeMillis());}
        JSONObject json()throws Exception {
            return new JSONObject().put("readable",readable).put("alarmPresent",readable?triggerAtEpochMs!=null:JSONObject.NULL)
                    .put("triggerAtEpochMs",triggerAtEpochMs==null?JSONObject.NULL:triggerAtEpochMs)
                    .put("observedAtUptimeMs",observedAtUptimeMs).put("unavailableReason",unavailableReason);
        }
    }
}
