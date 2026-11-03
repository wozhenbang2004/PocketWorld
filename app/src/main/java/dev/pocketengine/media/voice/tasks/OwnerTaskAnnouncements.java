package dev.pocketengine.media.voice.tasks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional foreground status readout from original task events. No task action or microphone authority. */
public final class OwnerTaskAnnouncements implements AutoCloseable {
    @FunctionalInterface public interface CurrentTask { JSONObject read(String taskId) throws Exception; }
    @FunctionalInterface public interface PlaybackAllowed { boolean allowed(String taskId); }
    private static final int MAX_TRACKED_TASKS=128,MAX_ANNOUNCEMENTS_PER_RUN=128;
    private final Object gate=new Object();
    private final CurrentTask source;
    private final PlaybackAllowed allowed;
    private final VoiceTaskReadout.Audio audio;
    private final Runnable changed;
    private final VoiceTaskReadout readout;
    private final LinkedHashMap<String,Observed> observed=new LinkedHashMap<>();
    private boolean enabled,foreground,closed,starting;
    private long generation;
    private Projection reading;
    private String readingId="";

    private record Projection(String task,String run,int epoch,String status,String key,String text) {
        boolean sameRun(Projection other){return other!=null&&task.equals(other.task)&&run.equals(other.run)&&epoch==other.epoch;}
        boolean sameEvent(Projection other){return sameRun(other)&&key.equals(other.key);}
    }
    private static final class Observed {
        Projection latest;
        final Set<String> consumed=new LinkedHashSet<>();
        Observed(Projection first){latest=first;consumed.add(first.key);}
    }

    public OwnerTaskAnnouncements(CurrentTask source,PlaybackAllowed allowed,VoiceTaskReadout.Audio audio,Runnable changed){
        this.source=Objects.requireNonNull(source);this.allowed=Objects.requireNonNull(allowed);this.audio=Objects.requireNonNull(audio);this.changed=Objects.requireNonNull(changed);
        readout=new VoiceTaskReadout(this::signal);
    }
    public void setEnabled(boolean value){
        String stop="";
        synchronized(gate){if(closed)return;boolean disabling=!value&&enabled;if(enabled!=value){enabled=value;generation++;}if(disabling){reading=null;stop=readingId;readingId="";}}
        if(!stop.isEmpty())readout.invalidate(stop);signal();
    }
    /** Real owner lifecycle only. Returning to the foreground never replays retained task states. */
    public void setForeground(boolean value){
        String stop="";
        synchronized(gate){if(closed)return;boolean leaving=!value&&foreground;if(foreground!=value){foreground=value;generation++;}if(leaving){reading=null;stop=readingId;readingId="";}}
        if(!stop.isEmpty())readout.invalidate(stop);signal();
    }
    public JSONObject snapshot()throws Exception{
        boolean selected,visible;
        synchronized(gate){selected=enabled;visible=foreground&&!closed;}
        return new JSONObject().put("enabled",selected).put("foreground",visible).put("defaultEnabled",false)
                .put("scope","foreground_private_task_status_only").put("includesTaskBody",false).put("microphone",false)
                .put("replaysHistory",false).put("readout",readout.snapshot());
    }

    /** Pass actual native task events, including while disabled, so enabling establishes no replay queue. */
    public void observe(JSONObject originalTask){
        Projection next=project(originalTask);
        if(next==null){invalidateChangedTask(originalTask==null?"":originalTask.optString("id"));return;}
        // A delayed old-run event cannot replace the new run's baseline or stop its current audio.
        try{if(!next.sameEvent(project(source.read(next.task))))return;}catch(Exception unavailable){return;}
        String stop="";boolean start=false;long acceptedGeneration=0;
        synchronized(gate){
            if(closed)return;
            Observed prior=observed.remove(next.task);
            if(prior==null||!next.sameRun(prior.latest))prior=new Observed(next);
            else {
                boolean repeated=prior.consumed.contains(next.key);
                prior.latest=next;
                if(!next.text.isEmpty()&&!repeated&&prior.consumed.size()<MAX_ANNOUNCEMENTS_PER_RUN){
                    // Consume before invoking external audio. Busy, declined or uncertain playback
                    // is never retried merely because another task event arrived.
                    prior.consumed.add(next.key);start=enabled&&foreground;
                }
            }
            observed.put(next.task,prior);
            while(observed.size()>MAX_TRACKED_TASKS)observed.remove(observed.keySet().iterator().next());
            if(reading!=null&&reading.task.equals(next.task)&&!reading.sameEvent(next)){reading=null;generation++;stop=readingId;readingId="";}
            if(start){acceptedGeneration=generation;}
        }
        if(!stop.isEmpty())readout.invalidate(stop);
        // Do not queue status speech behind a person's current listening, call or readout.
        if(!start||readout.active()||readout.leaseHeld())return;
        final long expected=acceptedGeneration;
        boolean claimed=false;
        try{
            requireCurrent(next,expected);
            synchronized(gate){if(closed||!enabled||!foreground||generation!=expected||starting||readout.active()||readout.leaseHeld())return;reading=next;starting=true;}
            claimed=true;
            readout.start(next.text,()->requireCurrent(next,expected),audio);
            String started=readout.currentId();boolean invalidated;
            synchronized(gate){invalidated=reading!=next||generation!=expected||closed||!enabled||!foreground;if(!invalidated)readingId=started;}
            if(invalidated)readout.invalidate(started);
        }catch(Exception unavailable){
            synchronized(gate){if(reading==next)reading=null;}
            signal();
        }finally{
            if(claimed)synchronized(gate){starting=false;}
        }
    }
    private void requireCurrent(Projection expected,long version)throws Exception{
        synchronized(gate){
            Observed latest=observed.get(expected.task);
            if(closed||!enabled||!foreground||version!=generation||latest==null||!expected.sameEvent(latest.latest))throw new SecurityException("原任务状态朗读已失效");
        }
        if(!allowed.allowed(expected.task)||!expected.sameEvent(project(source.read(expected.task))))throw new SecurityException("原任务或当前可见主人界面已变化");
        synchronized(gate){if(closed||!enabled||!foreground||version!=generation)throw new SecurityException("原任务状态朗读已停止");}
    }
    private void invalidateChangedTask(String task){
        Projection original;
        synchronized(gate){original=reading;if(original==null||!original.task.equals(task))return;}
        try{if(original.sameEvent(project(source.read(task))))return;}catch(Exception unavailable){/* Cannot keep speaking an unverified current record. */}
        String stop="";
        synchronized(gate){if(reading==original){reading=null;generation++;stop=readingId;readingId="";}}
        if(!stop.isEmpty())readout.invalidate(stop);
    }
    public void stop(String expectedReadoutId){readout.stop(expectedReadoutId);}
    @Override public void close(){synchronized(gate){closed=true;enabled=false;foreground=false;generation++;reading=null;readingId="";observed.clear();}readout.close();}
    private void signal(){try{changed.run();}catch(RuntimeException ignored){}}

    /** Status-only projection: task goals, summaries, questions, tool output and credentials never enter TTS. */
    private static Projection project(JSONObject task){
        if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))
                ||!Set.of("phone","assistant").contains(task.optString("kind"))||task.has("sourceLearningJob"))return null;
        JSONObject origin=task.optJSONObject("origin");
        if(origin!=null&&!"owner_reviewed_realtime".equals(origin.optString("kind")))return null;
        String id=task.optString("id"),run=task.optString("runId"),status=task.optString("status");Object rawEpoch=task.opt("coordinatorEpoch");
        if(!id.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||!(rawEpoch instanceof Integer||rawEpoch instanceof Long))return null;
        long epoch=((Number)rawEpoch).longValue();if(epoch<1||epoch>Integer.MAX_VALUE||!status.matches("[a-z_]{1,40}"))return null;
        String key=status,text=switch(status){
            case "completed"->"任务已完成，请查看原任务结果。";
            case "needs_confirmation"->"任务已有结果，请查看并确认。";
            case "awaiting_user","needs_owner"->"任务需要你补充信息，请查看原问题。";
            case "waiting_approval","awaiting_approval"->"任务正在等待你的授权，请查看原操作。";
            case "reconciling"->"有一项任务操作需要核对，请查看已有执行记录。";
            case "failed","interrupted"->"任务已停下，请查看原因和已有结果。";
            default->"";
        };
        if(status.equals("awaiting_user")){
            JSONObject evidence=task.optJSONObject("evidence"),question=evidence==null?null:evidence.optJSONObject("ownerQuestion");
            if(question!=null){
                Object revision=question.opt("waitRevision");String request=question.optString("requestId");
                if(!id.equals(question.optString("taskId"))||!run.equals(question.optString("runId"))||epoch!=question.optLong("coordinatorEpoch")
                        ||!request.matches("[a-fA-F0-9-]{36}")||!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1||question.optBoolean("replyConsumed"))text="";
                else key+="/"+request+"/"+((Number)revision).longValue();
            }
        }
        if(status.equals("waiting_approval")||status.equals("awaiting_approval")){
            JSONArray approvals=task.optJSONArray("approvals");java.util.TreeSet<String> pending=new java.util.TreeSet<>();
            for(int i=0;approvals!=null&&i<Math.min(approvals.length(),128);i++){
                JSONObject row=approvals.optJSONObject(i);if(row==null)continue;String approval=row.optString("id");
                if("pending".equals(row.optString("state"))&&run.equals(row.optString("runId"))&&approval.matches("[a-fA-F0-9-]{36}"))pending.add(approval);
            }
            if(!pending.isEmpty())key+="/"+digest(String.join("/",pending));
        }
        return new Projection(id,run,(int)epoch,status,key,text);
    }
    private static String digest(String value){
        try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
