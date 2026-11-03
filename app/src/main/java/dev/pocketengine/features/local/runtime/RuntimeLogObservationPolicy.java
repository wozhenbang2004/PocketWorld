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
    public static final long MEMORY_MAX_AGE_MILLIS=30000;
    static final String UNKNOWN_MESSAGE="热状态尚不可核对，自动日志暂按每页8KiB展示；不改变原进程、停止权限或显式日志读取上限。";
    /** Original observer generation/object, not a restorable JSON event. */
    public static final class Observation {
        private final RuntimeLogObservationPolicy owner;
        private final long sequence,startedAt,memoryRevision;
        private final boolean memory;
        private Observation(RuntimeLogObservationPolicy owner,long sequence,boolean memory){this.owner=owner;this.sequence=sequence;this.memory=memory;startedAt=owner.elapsedMillis.getAsLong();memoryRevision=owner.memoryRevision;}
    }
    private final LongSupplier elapsedMillis;
    private final String generation=UUID.randomUUID().toString();
    private long issued,applied,observedAt=-1,coldSince=-1;
    private Integer status,lastKnownStatus;
    private boolean reduced=true,closed;
    private String reason="thermal_not_observed";
    private long memoryApplied,memoryRevision,memoryAt=-1,memoryColdSince=-1;
    private Boolean memoryLow;
    private Long memoryAvailable,memoryTotal,memoryThreshold;
    private boolean memoryReduced=true;
    private String memoryReason="memory_not_observed";

    public RuntimeLogObservationPolicy(LongSupplier elapsedMillis){this.elapsedMillis=java.util.Objects.requireNonNull(elapsedMillis);}
    public synchronized Observation beginObservation(){issued=Math.addExact(issued,1L);return new Observation(this,issued,false);}
    public synchronized Observation beginMemoryObservation(){issued=Math.addExact(issued,1L);return new Observation(this,issued,true);}
    /** The platform adapter serializes fresh OS reads. Old callback arguments are
     * not supplied here; a late callback must read the CURRENT native status. */
    public synchronized boolean observe(Observation original,Integer currentStatus){
        if(original==null||original.owner!=this||original.memory||closed||original.sequence<=applied)return false;
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
    /** A current native MemoryInfo snapshot, not a trim-level guess or an App allocation budget. */
    public synchronized boolean observeMemory(Observation original,Boolean low,Long available,Long total,Long threshold){
        if(original==null||original.owner!=this||!original.memory||closed||original.sequence<=memoryApplied||original.memoryRevision!=memoryRevision)return false;
        memoryApplied=original.sequence;long now=elapsedMillis.getAsLong();
        if(original.startedAt<0||now<original.startedAt||memoryAt>=0&&original.startedAt<memoryAt){clearMemory("memory_clock_unavailable");return true;}
        if(memoryAt>=0&&original.startedAt-memoryAt>MEMORY_MAX_AGE_MILLIS){memoryReduced=true;memoryColdSince=-1;}
        memoryAt=original.startedAt;
        if(now-memoryAt>MEMORY_MAX_AGE_MILLIS){clearMemory("memory_observation_stale");return true;}
        if(low==null||available==null||total==null||threshold==null||available<0||total<=0||threshold<0||available>total||threshold>total){clearMemory("memory_status_unknown");return true;}
        memoryLow=low;memoryAvailable=available;memoryTotal=total;memoryThreshold=threshold;
        if(low){memoryReduced=true;memoryColdSince=-1;memoryReason="memory_pressure";}
        else if(!memoryReduced)memoryReason="memory_normal";
        else{
            if(memoryColdSince<0)memoryColdSince=memoryAt;
            // Read latency is not a second fresh observation of recovered memory.
            if(memoryAt-memoryColdSince>=COOLDOWN_MILLIS){memoryReduced=false;memoryColdSince=-1;memoryReason="memory_normal";}
            else memoryReason="memory_cooling";
        }
        return true;
    }
    public synchronized void invalidateMemory(){if(closed)return;memoryRevision=Math.addExact(memoryRevision,1L);clearMemory("memory_observation_invalidated");}
    private void clearMemory(String why){memoryLow=null;memoryAvailable=null;memoryTotal=null;memoryThreshold=null;memoryReduced=true;memoryColdSince=-1;memoryReason=why;}
    private void expireMemory(long now){if(memoryLow!=null&&(now<memoryAt||now-memoryAt>MEMORY_MAX_AGE_MILLIS))clearMemory(now<memoryAt?"memory_clock_unavailable":"memory_observation_stale");}
    public synchronized long freshColdRecheckAfterMillis(){
        if(closed)return -1;long now=elapsedMillis.getAsLong();expireMemory(now);
        long thermal=coldSince<0?-1:now<coldSince?COOLDOWN_MILLIS:Math.max(0,COOLDOWN_MILLIS-(now-coldSince));
        long memory=memoryColdSince<0?-1:Math.max(0,COOLDOWN_MILLIS-(now-memoryColdSince));
        return thermal<0?memory:memory<0?thermal:Math.min(thermal,memory);
    }
    public synchronized JSONObject snapshot()throws Exception {
        long now=elapsedMillis.getAsLong(),remaining=freshColdRecheckAfterMillis();expireMemory(now);
        boolean automaticReduced=reduced||memoryReduced;String actualReason=reduced?reason:memoryReason;
        return new JSONObject().put("format","pocketworld.runtime-log-observation-policy.v1")
                .put("generation",generation).put("observationSequence",applied).put("source","android.os.PowerManager.currentThermalStatus")
                .put("thermalStatus",status==null?JSONObject.NULL:status).put("lastKnownThermalStatus",lastKnownStatus==null?JSONObject.NULL:lastKnownStatus)
                .put("observationAgeMillis",observedAt<0||now<observedAt?JSONObject.NULL:now-observedAt)
                .put("memorySource","android.app.ActivityManager.getMemoryInfo").put("memoryLow",memoryLow==null?JSONObject.NULL:memoryLow)
                .put("memoryAvailableBytes",memoryAvailable==null?JSONObject.NULL:memoryAvailable).put("memoryTotalBytes",memoryTotal==null?JSONObject.NULL:memoryTotal).put("memoryThresholdBytes",memoryThreshold==null?JSONObject.NULL:memoryThreshold)
                .put("memoryObservationAgeMillis",memoryAt<0||now<memoryAt?JSONObject.NULL:now-memoryAt).put("memoryFresh",memoryLow!=null).put("memoryObservationSequence",memoryApplied).put("memoryMaxAgeMillis",MEMORY_MAX_AGE_MILLIS)
                .put("thermalReason",reason).put("memoryReason",memoryReason)
                .put("automaticLogPageBytes",automaticReduced?REDUCED_BYTES:NORMAL_BYTES).put("automaticLogPageReduced",automaticReduced)
                .put("reason",actualReason).put("freshColdRecheckPending",remaining>=0).put("coolingMillisRemaining",remaining<0?0:remaining)
                .put("ownerMessage",ownerMessage(actualReason,automaticReduced))
                .put("cooldownMillis",COOLDOWN_MILLIS).put("explicitLogLimitsChanged",false)
                .put("executionPolicyChanged",false).put("processesStopped",false).put("resourceReleaseProved",false)
                .put("energySavingsMeasured",false).put("temperatureMeasured",false).put("closed",closed);
    }
    private String ownerMessage(String actualReason,boolean automaticReduced){
        if(!automaticReduced)return "";
        if("thermal_pressure".equals(actualReason))return "系统报告热压力，自动日志暂按每页8KiB展示；不改变原进程、停止权限或显式日志读取上限。";
        if("thermal_cooling".equals(actualReason))return "系统报告热状态已回落，待冷却后再次原生读取确认；自动日志暂按每页8KiB展示，不改变原任务的执行范围或进程状态。";
        if(actualReason.startsWith("memory_"))return ("memory_pressure".equals(actualReason)?"系统报告可用内存紧张": "memory_cooling".equals(actualReason)?"系统内存压力已回落，等待新鲜读取确认恢复":"当前内存状态尚不可核对")+"，自动日志暂按每页8KiB展示；不改变原进程、批准、停止权限或显式读取上限。";
        return UNKNOWN_MESSAGE;
    }
    @Override public synchronized void close(){closed=true;status=null;reduced=true;coldSince=-1;reason="thermal_observer_closed";clearMemory("memory_observer_closed");}
}
