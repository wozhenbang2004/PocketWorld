package dev.pocketengine.features.local.runtime;

import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Ephemeral native facts for FUTURE automatic log pages only. No execution,
 * model, grant, process lifetime, persisted task state or explicit read limit is
 * changed. A clock advancing alone is never proof that the device cooled down. */
public final class RuntimeLogObservationPolicy implements AutoCloseable {
    public static final int NORMAL_BYTES=32768,REDUCED_BYTES=8192;
    public static final long COOLDOWN_MILLIS=30000;
    static final String UNKNOWN_MESSAGE="热状态尚不可核对，自动日志暂按每页8KiB展示；不改变原进程、停止权限或显式日志读取上限。";
    /** Original observer generation/object, not a restorable JSON event. */
    public static final class Observation {
        private final RuntimeLogObservationPolicy owner;
        private final long sequence;
        private Observation(RuntimeLogObservationPolicy owner,long sequence){this.owner=owner;this.sequence=sequence;}
    }
    private final LongSupplier elapsedMillis;
    private final String generation=UUID.randomUUID().toString();
    private long issued,applied,observedAt=-1,coldSince=-1;
    private Integer status,lastKnownStatus;
    private boolean reduced=true,closed;
    private String reason="thermal_not_observed";

    public RuntimeLogObservationPolicy(LongSupplier elapsedMillis){this.elapsedMillis=java.util.Objects.requireNonNull(elapsedMillis);}
    public synchronized Observation beginObservation(){issued=Math.addExact(issued,1L);return new Observation(this,issued);}
    /** The platform adapter serializes fresh OS reads. Old callback arguments are
     * not supplied here; a late callback must read the CURRENT native status. */
    public synchronized boolean observe(Observation original,Integer currentStatus){
        if(original==null||original.owner!=this||closed||original.sequence<=applied)return false;
        applied=original.sequence;long now=elapsedMillis.getAsLong();
        boolean monotonic=now>=0&&(observedAt<0||now>=observedAt);observedAt=now;
        if(!monotonic){status=null;reduced=true;coldSince=-1;reason="thermal_clock_unavailable";return true;}
        if(currentStatus==null||currentStatus<0||currentStatus>6){status=null;reduced=true;coldSince=-1;reason="thermal_status_unknown";return true;}
        status=currentStatus;lastKnownStatus=currentStatus;
        if(currentStatus>=2){reduced=true;coldSince=-1;reason="thermal_pressure";}
        else if(!reduced){reason="thermal_normal";}
        else{
            if(coldSince<0)coldSince=now;
            // Only a fresh, accepted native cold observation can restore defaults.
            if(now-coldSince>=COOLDOWN_MILLIS){reduced=false;coldSince=-1;reason="thermal_normal";}
            else reason="thermal_cooling";
        }
        return true;
    }
    public synchronized long freshColdRecheckAfterMillis(){
        if(closed||coldSince<0)return -1;long now=elapsedMillis.getAsLong();
        return now<coldSince?COOLDOWN_MILLIS:Math.max(0,COOLDOWN_MILLIS-(now-coldSince));
    }
    public synchronized JSONObject snapshot()throws Exception {
        long now=elapsedMillis.getAsLong(),remaining=freshColdRecheckAfterMillis();
        return new JSONObject().put("format","pocketworld.runtime-log-observation-policy.v1")
                .put("generation",generation).put("observationSequence",applied).put("source","android.os.PowerManager.currentThermalStatus")
                .put("thermalStatus",status==null?JSONObject.NULL:status).put("lastKnownThermalStatus",lastKnownStatus==null?JSONObject.NULL:lastKnownStatus)
                .put("observationAgeMillis",observedAt<0||now<observedAt?JSONObject.NULL:now-observedAt)
                .put("automaticLogPageBytes",reduced?REDUCED_BYTES:NORMAL_BYTES).put("automaticLogPageReduced",reduced)
                .put("reason",reason).put("freshColdRecheckPending",remaining>=0).put("coolingMillisRemaining",remaining<0?0:remaining)
                .put("ownerMessage",ownerMessage())
                .put("cooldownMillis",COOLDOWN_MILLIS).put("explicitLogLimitsChanged",false)
                .put("executionPolicyChanged",false).put("processesStopped",false).put("resourceReleaseProved",false)
                .put("energySavingsMeasured",false).put("temperatureMeasured",false).put("closed",closed);
    }
    private String ownerMessage(){
        if(!reduced)return "";
        if("thermal_pressure".equals(reason))return "系统报告热压力，自动日志暂按每页8KiB展示；不改变原进程、停止权限或显式日志读取上限。";
        if("thermal_cooling".equals(reason))return "系统报告热状态已回落，待冷却后再次原生读取确认；自动日志暂按每页8KiB展示，不改变原任务的执行范围或进程状态。";
        return UNKNOWN_MESSAGE;
    }
    @Override public synchronized void close(){closed=true;status=null;reduced=true;coldSince=-1;reason="thermal_observer_closed";}
}
