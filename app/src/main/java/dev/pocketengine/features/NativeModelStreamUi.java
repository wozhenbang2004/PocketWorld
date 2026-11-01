package dev.pocketengine.features;
import android.os.Handler;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
/** Ephemeral presentation admission only. No text storage, TaskStore write, status change or tool authority. */
public final class NativeModelStreamUi {
 @FunctionalInterface public interface Current { boolean owns(String taskId,RunControl original); }
 @FunctionalInterface public interface Publish { void emit(JSONObject event); }
 private final TaskStore tasks;private final Handler main;private final Current current;private final Publish publish;
 private final Map<String,Slot> slots=new HashMap<>();private long generation;
 public NativeModelStreamUi(TaskStore tasks,Handler main,Current current,Publish publish){this.tasks=tasks;this.main=main;this.current=current;this.publish=publish;}
 private boolean live(Slot s){try{if(!current.owns(s.task,s.control)||!s.control.matchesHostRun(s.task,s.run,s.epoch)||s.control.isCancelled()||s.control.isPaused()||s.control.pauseRevision()!=s.pause)return false;s.control.check();JSONObject task=tasks.get(s.task,false);return task!=null&&s.run.equals(task.optString("runId"))&&s.epoch==task.optInt("coordinatorEpoch")&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&!task.optBoolean("cancelRequested")&&task.optString("status").matches("preparing|running");}catch(Exception denied){return false;}}
 public void accept(String id,String run,int epoch,RunControl original,JSONObject event){
  try{
   JSONObject input=event.getJSONObject("stream");String kind=input.getString("kind"),request=input.getString("requestId"),provider=input.getString("provider");
   if(!kind.matches("started|text_delta|completed|failed|cancelled")||!request.matches("[A-Za-z0-9_-]{1,160}")||!provider.matches("[A-Za-z0-9_-]{1,48}")||!Boolean.FALSE.equals(input.opt("taskCompleted"))||!(input.opt("sequence") instanceof Number)||!(input.opt("pauseRevision") instanceof Number))return;
   long sequence=input.getLong("sequence"),pause=input.getLong("pauseRevision");if(sequence<1||pause<0)return;boolean discard=kind.equals("cancelled");
   String text=kind.equals("text_delta")?input.getString("text"):"";if(text.length()>2048||!validUnicode(text))return;
   JSONObject stream=new JSONObject().put("kind",kind).put("requestId",request).put("sequence",sequence).put("provider",provider).put("pauseRevision",pause).put("provisional",kind.equals("text_delta")).put("taskCompleted",false);
   if(kind.equals("text_delta"))stream.put("text",text);if(kind.equals("failed"))stream.put("attemptFailed",true);if(discard)stream.put("discardDraft",true).put("code",input.optString("code").replaceAll("[^A-Za-z0-9_]","").substring(0,Math.min(80,input.optString("code").replaceAll("[^A-Za-z0-9_]","").length())));
   Slot candidate=new Slot(id,run,epoch,original,request,provider,pause);if(!discard&&!live(candidate)){clearMatching(id,original,request,"presentation_invalidated");return;}
   Slot slot,retired=null;
   synchronized(slots){
    slot=slots.get(id);
    if(kind.equals("started")){
     if(sequence!=1)return;if(slot!=null&&slot.request.equals(request))return;retired=slot;if(retired!=null&&retired.failed)retired.replacedForRecovery=true;slot=candidate;slot.generation=++generation;slots.put(id,slot);
    }else if(slot==null||slot.control!=original||!slot.run.equals(run)||slot.epoch!=epoch||slot.pause!=pause||!slot.request.equals(request)||!slot.provider.equals(provider))return;
    if(slot.closed||sequence<=slot.accepted)return;slot.accepted=sequence;if(kind.matches("completed|failed|cancelled"))slot.closed=true;if(kind.equals("failed"))slot.failed=true;
   }
   if(retired!=null){retired.control.removeCancelAction(retired.cancel);if(!retired.failed){JSONObject oldClear=clearEvent(retired,"superseded");main.post(()->publish.emit(oldClear));}}
   Slot admitted=slot;if(kind.equals("started")){slot.cancel=()->clear(id,original,"cancelled");original.onCancel(slot.cancel);}
   JSONObject clean=new JSONObject().put("type","model_stream").put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("presentationRevision",slot.generation).put("stream",stream);
   main.post(()->{
    if(!discard&&!live(admitted)){clearExact(admitted,"presentation_invalidated");return;}
    synchronized(slots){if(slots.get(id)!=admitted&&!admitted.replacedForRecovery||sequence<=admitted.delivered)return;admitted.delivered=sequence;}
    publish.emit(clean);
    if(discard)retire(admitted);
   });
  }catch(Exception malformed){/* No unvalidated body enters UI or durable storage. */}
 }
 public void clear(String id,RunControl original,String code){Slot slot;synchronized(slots){slot=slots.get(id);if(slot==null||slot.control!=original)return;}clearExact(slot,code);}
 private void clearMatching(String id,RunControl original,String request,String code){Slot slot;synchronized(slots){slot=slots.get(id);if(slot==null||slot.control!=original||!slot.request.equals(request))return;}clearExact(slot,code);}
 private void clearExact(Slot slot,String code){
  synchronized(slots){if(slots.get(slot.task)!=slot)return;slots.remove(slot.task);slot.closed=true;}
  slot.control.removeCancelAction(slot.cancel);JSONObject clean=clearEvent(slot,code);main.post(()->publish.emit(clean));
 }
 private void retire(Slot slot){synchronized(slots){if(slots.get(slot.task)==slot)slots.remove(slot.task);}slot.control.removeCancelAction(slot.cancel);}
 private static JSONObject clearEvent(Slot s,String code){try{return new JSONObject().put("type","model_stream").put("taskId",s.task).put("runId",s.run).put("coordinatorEpoch",s.epoch).put("presentationRevision",s.generation).put("stream",new JSONObject().put("kind","cancelled").put("requestId",s.request).put("sequence",s.accepted+1).put("provider",s.provider).put("pauseRevision",s.pause).put("provisional",false).put("taskCompleted",false).put("discardDraft",true).put("code",code));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 private static boolean validUnicode(String text){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
 private static final class Slot {final String task,run,request,provider;final int epoch;final RunControl control;final long pause;long generation,accepted,delivered;boolean closed,failed,replacedForRecovery;Runnable cancel=()->{};Slot(String task,String run,int epoch,RunControl control,String request,String provider,long pause){this.task=task;this.run=run;this.epoch=epoch;this.control=control;this.request=request;this.provider=provider;this.pause=pause;}}
}
