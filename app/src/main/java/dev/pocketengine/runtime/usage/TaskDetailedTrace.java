package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.EventSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional content-free diagnostic archives. Original journals remain the only execution facts. */
public final class TaskDetailedTrace {
    private static volatile TaskDetailedTrace host;
    public static void install(TaskDetailedTrace nativeHost){host=nativeHost;}
    public static void receiptCommitted(InferenceReceipt receipt){TaskDetailedTrace current=host;if(current==null)return;try{synchronized(current){Session session=current.sessions.get(receipt.context.runId);if(session!=null)session.finished(receipt);}}catch(Exception unavailable){/* Diagnostics never change original accounting or execution. */}}
    private final Path directory;private final boolean supported;private boolean enabled;private long generation,limit=8*1024*1024;
    private final Map<String,Session> sessions=new HashMap<>();
    public TaskDetailedTrace(Path directory,boolean supported)throws Exception {this.directory=directory;this.supported=supported;Files.createDirectories(directory);Path prefs=directory.resolve("settings.json");if(Files.exists(prefs)&&Files.size(prefs)<=1024){JSONObject p=new JSONObject(Files.readString(prefs));enabled=supported&&p.optBoolean("enabled");limit=Math.max(1048576,Math.min(32L*1048576,p.optLong("limitBytes",limit)));}prune();}
    public synchronized JSONObject snapshot()throws Exception {long bytes=0;int count=0;for(Path file:archives()){bytes+=Files.size(file);count++;}return new JSONObject().put("supported",supported).put("enabled",supported&&enabled).put("limitBytes",limit).put("retainedBytes",bytes).put("archives",count).put("bodyIncluded",false).put("automaticUpload",false);}
    public synchronized void configure(boolean on,int limitMiB)throws Exception {
        if(!supported)throw new IllegalStateException("详细诊断只在调试构建提供");if(limitMiB<1||limitMiB>32)throw new IllegalArgumentException("诊断保留上限为1–32MiB");
        long nextLimit=limitMiB*1048576L;
        // Never start collection from an opt-in whose durable write failed. Turning off fences
        // current tickets immediately, even if persistence subsequently reports a storage error.
        if(on)write(directory.resolve("settings.json"),new JSONObject().put("enabled",true).put("limitBytes",nextLimit).toString());
        if(enabled!=on){generation++;enabled=on;if(!on)for(Session session:new ArrayList<>(sessions.values()))session.close();}limit=nextLimit;
        if(!on)write(directory.resolve("settings.json"),new JSONObject().put("enabled",false).put("limitBytes",limit).toString());prune();
    }
    public synchronized long ticket(){return supported&&enabled?generation:-1;}
    public synchronized Session start(long ticket,String task,String run,int epoch)throws Exception {id(task);id(run);if(epoch<1)throw new IllegalArgumentException("trace_epoch_required");if(ticket<0||ticket!=generation||!enabled||!supported)return null;if(sessions.containsKey(run)||Files.exists(directory.resolve(run+".json")))return null;Session session=new Session(task,run,epoch,generation);sessions.put(run,session);return session;}
    public synchronized JSONObject read(String run)throws Exception {id(run);Path path=directory.resolve(run+".json");if(!Files.exists(path))return new JSONObject().put("available",false).put("reason","trace_disabled_not_retained_or_evicted");if(Files.size(path)>524288)throw new IllegalStateException("诊断超过单次读取上限");JSONObject data=new JSONObject(Files.readString(path));if(!run.equals(data.getString("runId"))||!"pocketworld.content-free-trace.v1".equals(data.getString("format")))throw new IllegalStateException("原诊断身份不可核对");return data.put("available",true);}
    private ArrayList<Path> archives()throws Exception {ArrayList<Path> files=new ArrayList<>();try(var list=Files.list(directory)){list.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).forEach(files::add);}files.sort(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception failure){return Long.MIN_VALUE;}}));return files;}
    private void prune()throws Exception {var files=archives();long bytes=0;for(Path file:files)bytes=Math.addExact(bytes,Files.size(file));for(Path file:files){if(bytes<=limit)break;long size=Files.size(file);Files.delete(file);bytes-=size;}}
    private static void write(Path target,String text)throws Exception {Path temp=Files.createTempFile(target.getParent(),"trace-",".tmp");try{Files.writeString(temp,text);Files.move(temp,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    private static void id(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("native_trace_identity_required");}
    public final class Session implements AutoCloseable,InferenceCapture.Sink {
        private final String task,run;private final int epoch;private final long version,start=System.currentTimeMillis(),nano=System.nanoTime();
        private final JSONArray spans=new JSONArray();private final Map<String,Long> openTools=new HashMap<>();private boolean closed,truncated,unavailable;
        private Session(String task,String run,int epoch,long version){this.task=task;this.run=run;this.epoch=epoch;this.version=version;}
        private boolean current(){return !closed&&enabled&&version==generation&&!unavailable;}
        private void span(String name,long at,long elapsed,String status,String correlation){synchronized(TaskDetailedTrace.this){if(!current())return;try{if(spans.length()>=1024){truncated=true;return;}JSONObject span=new JSONObject().put("name",name).put("startedAt",at).put("elapsedNanos",Math.max(0,elapsed)).put("status",status).put("correlation",correlation);if(span.toString().length()>1024){truncated=true;return;}spans.put(span);}catch(Exception failure){unavailable=true;}}}
        public EventSink events(EventSink original){return event->{original.emit(event);try{String type=event.optString("type"),status=event.optString("status"),tool=event.optString("tool");if(!tool.matches("[A-Za-z0-9_.:-]{1,100}"))tool="tool";if(type.equals("tool")){synchronized(TaskDetailedTrace.this){if(!current())return;if(status.equals("running"))openTools.put(tool,System.nanoTime());else{Long at=openTools.remove(tool);span("tool."+tool,event.optLong("at",System.currentTimeMillis()),at==null?0:System.nanoTime()-at,status.matches("completed|failed|awaiting_user|running")?status:"unknown","");}}}else if(type.equals("run")&&status.equals("paused"))span("run.paused",System.currentTimeMillis(),0,"paused","");}catch(Exception failure){unavailable=true;}};}
        public void started(InferenceReceipt receipt){/* Original sink already persisted dispatch; no content is observed. */}
        public void finished(InferenceReceipt receipt){if(!task.equals(receipt.context.taskId)||!run.equals(receipt.context.runId)||epoch!=receipt.context.epoch)return;span("model."+receipt.context.purpose.name(),receipt.startedAt,receipt.elapsedNanos,receipt.outcome.name(),receipt.callId);}
        @Override public void close(){synchronized(TaskDetailedTrace.this){if(closed)return;try{if(!unavailable){spans.put(new JSONObject().put("name","task.run").put("startedAt",start).put("elapsedNanos",System.nanoTime()-nano).put("status","ended").put("correlation",""));JSONObject archive=new JSONObject().put("format","pocketworld.content-free-trace.v1").put("taskId",task).put("runId",run).put("epoch",epoch).put("createdAt",start).put("spans",spans).put("truncated",truncated).put("bodyIncluded",false).put("executionAuthorized",false);String data=archive.toString();if(data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=524288){write(directory.resolve(run+".json"),data);prune();}}}catch(Exception failure){unavailable=true;}finally{closed=true;sessions.remove(run);}}}
    }
}
