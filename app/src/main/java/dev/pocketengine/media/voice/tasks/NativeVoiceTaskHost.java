package dev.pocketengine.media.voice.tasks;

import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original SQLite task records plus the application's existing native admission and task events. */
public final class NativeVoiceTaskHost implements VoiceTaskHostPort {
    public interface Actions {
        JSONObject startOriginal(Command command,Authority authority)throws Exception;
        JSONObject cancelExactRun(Command command,Authority authority)throws Exception;
    }
    public interface Events {Subscription subscribe(String taskId,Runnable changed);}
    private final TaskStore tasks;
    private final Actions actions;
    private final Events events;
    public NativeVoiceTaskHost(TaskStore tasks,Actions actions,Events events){this.tasks=Objects.requireNonNull(tasks);this.actions=Objects.requireNonNull(actions);this.events=Objects.requireNonNull(events);}
    @Override public TaskState inspectOriginal(Reference reference,Authority authority)throws Exception{
        authority.check();JSONObject original=exact(reference);authority.check();return original==null?null:project(original);
    }
    @Override public TaskState startOriginal(Command command,Authority authority)throws Exception{
        authority.check();requireExpected(command,exact(command.reference));
        JSONObject result=actions.startOriginal(command,authority);return projectExact(command.reference,result);
    }
    @Override public TaskState cancelExactRun(Command command,Authority authority)throws Exception{
        authority.check();requireExpected(command,exact(command.reference),true);
        JSONObject result=actions.cancelExactRun(command,authority);return projectExact(command.reference,result);
    }
    @Override public Recovered recoverOriginal(String id,Authority authority)throws Exception{
        authority.check();JSONObject original=tasks.get(id,false);if(original==null)throw new IllegalArgumentException("没有查到原语音任务，不会重新创建");
        Reference reference=new Reference(original);JSONObject checked=exact(reference);authority.check();return new Recovered(reference,project(checked));
    }
    @Override public JSONArray recentOriginals(Authority authority)throws Exception{
        authority.check();JSONArray originals=tasks.reviewedRealtimeTasks(20),result=new JSONArray();
        for(int index=0;index<originals.length();index++){
            JSONObject original=originals.getJSONObject(index);Reference reference=new Reference(original);TaskState state=projectExact(reference,original);
            result.put(state.toJson().put("goal",reference.text));
        }
        authority.check();return result;
    }
    @Override public Subscription observeOriginal(Reference reference,Authority authority,Listener listener)throws Exception{
        authority.check();if(exact(reference)==null)throw new IllegalArgumentException("原任务不存在");AtomicBoolean closed=new AtomicBoolean();
        Subscription owned=events.subscribe(reference.taskId,()->{if(closed.get())return;try{TaskState state=inspectOriginal(reference,authority);if(!closed.get())listener.changed(state);}catch(Exception unavailable){/* Manual refresh remains available; no retry or task execution here. */}});
        return ()->{if(closed.compareAndSet(false,true))owned.close();};
    }
    private JSONObject exact(Reference reference)throws Exception{return tasks.reviewedRealtimeTask(reference.taskId,reference.text,reference.source());}
    private TaskState projectExact(Reference reference,JSONObject result)throws Exception{
        if(result==null||!reference.taskId.equals(result.optString("id")))throw new SecurityException("宿主回执不是原语音任务");
        JSONObject original=exact(reference);if(original==null)throw new IllegalArgumentException("原任务不存在");return project(original);
    }
    public static void requireExpected(Command command,JSONObject task)throws Exception{
        requireExpected(command,task,false);
    }
    private static void requireExpected(Command command,JSONObject task,boolean allowProgress)throws Exception{
        if(task==null||!command.reference.taskId.equals(task.optString("id"))||!command.expectedRunId.equals(task.optString("runId"))||command.expectedEpoch!=task.optInt("coordinatorEpoch")||(allowProgress?command.expectedRevision>revision(task):command.expectedRevision!=revision(task)))throw new SecurityException("原任务记录或执行轮次已变化，请重新核对");
    }
    private static long revision(JSONObject task){return Math.max(1,task.optLong("voiceStateRevision",task.optLong("updatedAt",1)));}
    private static TaskState project(JSONObject original){
        String summary=original.optString("summary");if(summary.length()>2400)summary=summary.substring(0,2395)+"…";
        boolean pending=false;JSONArray approvals=original.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++)if(approvals.optJSONObject(i)!=null&&"pending".equals(approvals.optJSONObject(i).optString("state")))pending=true;
        return new TaskState(original.optString("id"),original.optString("runId"),original.optInt("coordinatorEpoch"),revision(original),original.optString("status"),summary,pending);
    }
}
