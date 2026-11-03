package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner diagnostics only. This is not a feature command, model tool, task store or Runner. */
public final class NativeDiagnosticsHost {
    public static final int MIN_CAP_BYTES=32768,DEFAULT_CAP_BYTES=524288,MAX_CAP_BYTES=2097152;
    private final File file;private final TaskStore tasks;private JSONObject state;
    private final Set<RunControl> active=new CopyOnWriteArraySet<>();
    /** Guarded by this host's monitor. Cap eviction cannot erase a dispatched attempt. */
    private final Set<String> exporting=new java.util.HashSet<>();
    public NativeDiagnosticsHost(File file,TaskStore tasks){this.file=file;this.tasks=tasks;}

    /** Issued and retained only by the real native screen; leaving/backgrounding cancels its work. */
    public final class OwnerWindow implements AutoCloseable {
        private final NativeDiagnosticsHost issuer=NativeDiagnosticsHost.this;
        private final AtomicBoolean foreground=new AtomicBoolean(),closed=new AtomicBoolean();
        private final Set<RunControl> controls=new CopyOnWriteArraySet<>();
        public void setForeground(boolean value){if(!closed.get())foreground.set(value);if(!value)for(RunControl c:controls)c.cancel();}
        public void check(){if(closed.get()||!foreground.get())throw new SecurityException("请返回原本机诊断页面后重新操作");}
        @Override public void close(){closed.set(true);setForeground(false);}
    }
    public OwnerWindow openOwnerWindow(){return new OwnerWindow();}
    private void require(OwnerWindow window){if(window==null||window.issuer!=this)throw new SecurityException("没有原生主人诊断窗口");window.check();}
    private synchronized JSONObject current()throws Exception {
        if(state==null){
            if(!file.isFile())state=new JSONObject().put("format","pocketworld.native-diagnostics.v1").put("generation",1L).put("enabled",false)
                    .put("exportEnabled",false).put("endpoint","").put("storageCapBytes",DEFAULT_CAP_BYTES).put("snapshots",new JSONArray());
            else{
                if(file.length()>MAX_CAP_BYTES)throw new IllegalStateException("诊断文件超出本地上限，未读取或外传");
                JSONObject loaded=JsonFiles.read(file);
                if(!"pocketworld.native-diagnostics.v1".equals(loaded.optString("format"))||loaded.optLong("generation")<1
                        ||!(loaded.opt("enabled") instanceof Boolean)||!(loaded.opt("exportEnabled") instanceof Boolean)
                        ||loaded.optInt("storageCapBytes")<MIN_CAP_BYTES||loaded.optInt("storageCapBytes")>MAX_CAP_BYTES
                        ||loaded.optJSONArray("snapshots")==null||bytes(loaded)>loaded.getInt("storageCapBytes")){
                    throw new IllegalStateException("原诊断配置无法核对，未自动恢复采集或导出");
                }
                if(loaded.getBoolean("exportEnabled"))OtlpTraceExport.endpoint(loaded.getString("endpoint"));state=loaded;
            }
        }
        return state;
    }
    public synchronized JSONObject snapshot(OwnerWindow window)throws Exception{
        require(window);JSONObject value=current(),result=config(value);JSONArray summaries=new JSONArray(),items=value.getJSONArray("snapshots");
        for(int i=items.length()-1;i>=0;i--){JSONObject trace=items.getJSONObject(i),row=new JSONObject();for(String key:new String[]{"snapshotId","taskId","runId","epoch","traceId","capturedAt","spanCount","unknownOutcomes","skippedSpans","sourceLimited","generation","lastExport"})if(trace.has(key))row.put(key,trace.get(key));summaries.put(row);}
        return result.put("snapshots",summaries).put("storedBytes",bytes(value)).put("bodyIncluded",false).put("automaticExport",false);
    }
    public synchronized JSONObject configure(OwnerWindow window,long expectedGeneration,boolean enabled,int cap,boolean exportEnabled,String endpoint)throws Exception{
        require(window);JSONObject prior=current();if(prior.getLong("generation")!=expectedGeneration)throw new SecurityException("诊断配置已变化，请刷新后重选");
        if(cap<MIN_CAP_BYTES||cap>MAX_CAP_BYTES)throw new IllegalArgumentException("诊断存储上限需在32KiB至2MiB之间");
        String selected=endpoint==null?"":endpoint.trim();if(exportEnabled)selected=OtlpTraceExport.endpoint(selected);else if(!selected.isEmpty())selected=OtlpTraceExport.endpoint(selected);
        JSONObject next=new JSONObject(prior.toString()).put("generation",Math.addExact(expectedGeneration,1L)).put("enabled",enabled)
                .put("exportEnabled",exportEnabled).put("endpoint",selected).put("storageCapBytes",cap);
        persist(next);for(RunControl control:active)control.cancel();return snapshot(window);
    }
    public JSONObject trends(OwnerWindow window,UsageTrendProjection.Query query,RunControl control)throws Exception{
        try(var ignored=work(window,control)){control.check();JSONObject result=tasks.ownerInferenceTrends(query,control);require(window);control.check();return result;}
    }
    public synchronized JSONObject clear(OwnerWindow window,long expectedGeneration)throws Exception{
        require(window);JSONObject value=current();if(value.getLong("generation")!=expectedGeneration)throw new SecurityException("诊断配置已变化，请刷新");
        JSONObject next=new JSONObject(value.toString()).put("generation",Math.addExact(expectedGeneration,1L)).put("snapshots",new JSONArray());
        persist(next);for(RunControl control:active)control.cancel();return snapshot(window);
    }
    public JSONObject capture(OwnerWindow window,long generation,String task,RunControl control)throws Exception {
        int limit;synchronized(this){require(window);JSONObject value=current();requireEnabled(value,generation);limit=Math.min(524288,Math.max(16384,value.getInt("storageCapBytes")/2));}
        JSONObject trace;
        try(var ignored=work(window,control)){
            JSONObject source=tasks.ownerDiagnosticTraceSource(task,control);trace=DiagnosticTraceProjection.fromSource(source,limit);control.check();
            synchronized(this){require(window);JSONObject value=current();requireEnabled(value,generation);JSONObject next=new JSONObject(value.toString());JSONArray traces=next.getJSONArray("snapshots");
                for(int i=0;i<traces.length();i++){JSONObject old=traces.getJSONObject(i);if(task.equals(old.optString("taskId"))&&trace.getString("sourceRevision").equals(old.optString("sourceRevision"))&&old.optLong("generation")==generation)return snapshot(window);}
                traces.put(trace.put("generation",generation));persist(next);return snapshot(window);
            }
        }
    }
    public final class ExportReview {
        private final NativeDiagnosticsHost issuer=NativeDiagnosticsHost.this;
        private final OwnerWindow owner;private final long generation;private final String snapshotId,endpoint,sourceRevision,task,payload;
        private final int spanCount;private final AtomicBoolean consumed=new AtomicBoolean();
        private ExportReview(OwnerWindow owner,long generation,JSONObject trace,String endpoint)throws Exception{
            this.owner=owner;this.generation=generation;this.endpoint=endpoint;snapshotId=trace.getString("snapshotId");sourceRevision=trace.getString("sourceRevision");task=trace.getString("taskId");
            spanCount=trace.getInt("spanCount");payload=trace.getJSONObject("otlp").toString();
        }
        public JSONObject view()throws Exception{return new JSONObject().put("snapshotId",snapshotId).put("endpoint",endpoint).put("generation",generation)
                .put("spanCount",spanCount).put("payloadBytes",payload.getBytes(StandardCharsets.UTF_8).length).put("bodyIncluded",false)
                .put("payloadPreview",new JSONObject(payload)).put("automaticRetry",false);}
    }
    public ExportReview prepareExport(OwnerWindow window,long generation,String id,RunControl control)throws Exception {
        JSONObject trace;String endpoint;
        synchronized(this){require(window);JSONObject value=current();requireExport(value,generation);trace=new JSONObject(find(value,id).toString());endpoint=value.getString("endpoint");requireUnsent(trace,endpoint);}
        try(var ignored=work(window,control)){
            JSONObject source=tasks.ownerDiagnosticTraceSource(trace.getString("taskId"),control);
            if(!trace.getString("sourceRevision").equals(source.getString("sourceRevision")))throw new SecurityException("原任务来源已变化或撤回，请重新生成诊断");
            // Rebuild from the original authority; archive JSON is never accepted as an arbitrary export body.
            source.put("capturedAt",trace.getLong("capturedAt"));JSONObject rebuilt=DiagnosticTraceProjection.fromSource(source,Math.min(524288,Math.max(16384,current().getInt("storageCapBytes")/2)));
            if(!trace.getString("snapshotId").equals(rebuilt.getString("snapshotId")))throw new SecurityException("原脱敏快照不能重现，请重新生成诊断");
            synchronized(this){require(window);requireExport(current(),generation);requireUnsent(find(current(),id),endpoint);return new ExportReview(window,generation,rebuilt,endpoint);}
        }
    }
    public JSONObject send(OwnerWindow window,ExportReview review,RunControl control)throws Exception {
        if(review==null||review.issuer!=this||review.owner!=window||!review.consumed.compareAndSet(false,true))throw new SecurityException("这次导出确认已使用或不属于原页面");
        try(var ignored=work(window,control)){
            JSONObject source=tasks.ownerDiagnosticTraceSource(review.task,control);if(!review.sourceRevision.equals(source.getString("sourceRevision")))throw new SecurityException("原诊断来源已变化，旧发送确认失效");
            String attempt=java.util.UUID.randomUUID().toString();
            synchronized(this){require(window);JSONObject value=current();requireExport(value,review.generation);requireUnsent(find(value,review.snapshotId),review.endpoint);control.check();
                JSONObject next=new JSONObject(value.toString());find(next,review.snapshotId).put("lastExport",new JSONObject().put("attemptId",attempt).put("endpointHash",DiagnosticTraceProjection.hash(review.endpoint))
                        .put("outcome","dispatch_unknown").put("startedAt",System.currentTimeMillis()).put("automaticRetry",false));
                exporting.add(review.snapshotId);
                try{persist(next);}catch(Exception notRecorded){exporting.remove(review.snapshotId);throw notRecorded;}
            }
            try{
                JSONObject result;
                try{control.check();synchronized(this){require(window);requireExport(current(),review.generation);}result=OtlpTraceExport.send(review.endpoint,new JSONObject(review.payload),review.spanCount,control);}
                catch(Exception unknown){result=new JSONObject().put("outcome",control.isCancelled()?"unknown_after_cancel":"unknown").put("acceptedSpans",JSONObject.NULL).put("automaticRetry",false);}
                synchronized(this){JSONObject next=new JSONObject(current().toString());JSONObject saved=findOrNull(next,review.snapshotId);
                    // A late transport receipt may settle only its original attempt; it cannot install old settings or a removed trace.
                    if(saved!=null&&saved.optJSONObject("lastExport")!=null&&attempt.equals(saved.getJSONObject("lastExport").optString("attemptId"))){
                        JSONObject receipt=new JSONObject(result.toString()).put("attemptId",attempt).put("endpointHash",DiagnosticTraceProjection.hash(review.endpoint)).put("finishedAt",System.currentTimeMillis());saved.put("lastExport",receipt);persist(next);
                    }
                }
                return result;
            }finally{synchronized(this){exporting.remove(review.snapshotId);}}
        }
    }
    private AutoCloseable work(OwnerWindow window,RunControl control)throws Exception{
        require(window);control.check();window.controls.add(control);active.add(control);
        AutoCloseable authority=control.retainAuthorityCheck(window::check);
        return ()->{authority.close();window.controls.remove(control);active.remove(control);};
    }
    private static void requireEnabled(JSONObject value,long generation)throws Exception{if(value.getLong("generation")!=generation||!value.getBoolean("enabled"))throw new SecurityException("详细诊断未启用或配置代次已变化");}
    private static void requireExport(JSONObject value,long generation)throws Exception{requireEnabled(value,generation);if(!value.getBoolean("exportEnabled"))throw new SecurityException("请先明确启用并配置OTLP导出");OtlpTraceExport.endpoint(value.getString("endpoint"));}
    private static void requireUnsent(JSONObject trace,String endpoint)throws Exception{JSONObject prior=trace.optJSONObject("lastExport");if(prior!=null&&DiagnosticTraceProjection.hash(endpoint).equals(prior.optString("endpointHash")))throw new SecurityException("此快照已经尝试发送；请核对原接收回执，不自动重复上传");}
    private synchronized void persist(JSONObject next)throws Exception{
        JSONArray traces=next.getJSONArray("snapshots");int cap=next.getInt("storageCapBytes");
        while((traces.length()>32||bytes(next)>cap)&&traces.length()>0){
            int removable=-1;for(int i=0;i<traces.length();i++)if(!exporting.contains(traces.getJSONObject(i).getString("snapshotId"))){removable=i;break;}
            if(removable>=0){traces.remove(removable);continue;}
            // A smaller owner cap may prune the derived body, but retains the original unknown
            // attempt identity and response. The reviewed payload is already bounded in memory.
            boolean pruned=false;for(int i=0;i<traces.length();i++){JSONObject trace=traces.getJSONObject(i);
                if(trace.has("otlp")||trace.has("callReferences")){trace.remove("otlp");trace.remove("callReferences");trace.put("payloadPruned",true);pruned=true;break;}}
            if(!pruned)throw new IllegalStateException("正在发送的原回执超过所选上限，请先取消或核对本次发送");
        }
        if(bytes(next)>cap)throw new IllegalStateException("诊断配置超过存储上限");JsonFiles.write(file,next);state=next;
    }
    private static JSONObject find(JSONObject value,String id)throws Exception{JSONObject found=findOrNull(value,id);if(found==null)throw new SecurityException("原诊断快照已删除或超过保留上限");return found;}
    private static JSONObject findOrNull(JSONObject value,String id)throws Exception{JSONArray items=value.getJSONArray("snapshots");for(int i=0;i<items.length();i++){JSONObject trace=items.getJSONObject(i);if(id.equals(trace.optString("snapshotId")))return trace;}return null;}
    private static JSONObject config(JSONObject value)throws Exception{JSONObject out=new JSONObject();for(String key:new String[]{"generation","enabled","exportEnabled","endpoint","storageCapBytes"})out.put(key,value.get(key));return out;}
    private static int bytes(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8).length;}
}
