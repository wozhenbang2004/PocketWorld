package dev.pocketengine.features;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One native entry point; callers from remote channels never receive this local-owner surface. */
public final class FeatureHost implements AutoCloseable {
    public interface MemorySynchronizer{void synchronize(JSONObject scope)throws Exception;}
    private volatile MemorySynchronizer memorySynchronizer;
    private volatile dev.pocketengine.features.skills.NativeSkillExecutionHost skillExecutionHost;
    /** Application-owned native execution bridge; feature/model JSON cannot register an executor. */
    public void setSkillExecutionHost(dev.pocketengine.features.skills.NativeSkillExecutionHost host){if(skillExecutionHost!=null)throw new IllegalStateException("脚本宿主已经注册");skillExecutionHost=java.util.Objects.requireNonNull(host);}
    public boolean hasSkillExecutionHost(){return skillExecutionHost!=null;}
    public JSONObject executeSkillForTask(String task,String run,int epoch,JSONObject input,java.util.Set<String> capabilities,dev.pocketengine.core.RunControl control)throws Exception {
        var host=skillExecutionHost;if(host==null)throw new IllegalStateException("脚本执行宿主不可用");return host.execute(task,run,epoch,input,capabilities,control);
    }
    public JSONObject executeSkillForTask(String task,String run,int epoch,JSONObject input,java.util.Set<String> capabilities,dev.pocketengine.core.RunControl control,dev.pocketengine.core.CapabilitySnapshot.CurrentCheck current)throws Exception {
        var host=skillExecutionHost;if(host==null)throw new IllegalStateException("脚本执行宿主不可用");return host.execute(task,run,epoch,input,capabilities,control,current);
    }
    public void setMemorySynchronizer(MemorySynchronizer synchronizer){memorySynchronizer=synchronizer;}
    public void synchronizeMemory(JSONObject scope)throws Exception{MemorySynchronizer synchronizer=memorySynchronizer;if(synchronizer!=null)synchronizer.synchronize(scope);}
    private final Map<String,EngineModule> modules=new LinkedHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(48));
    private final ExecutorService controls=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor signals=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final EventSink events;
    private final java.util.concurrent.ConcurrentHashMap<dev.pocketengine.features.memory.WorkingMemorySession,JSONObject> workingMemory=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong memoryContextRevision=new java.util.concurrent.atomic.AtomicLong();
    public void retainWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session,JSONObject scope)throws Exception{workingMemory.put(session,new JSONObject(scope.toString()));}
    public boolean hasWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session){return workingMemory.containsKey(session);}
    public void clearTaskWorkingMemory(String taskId){for(var entry:workingMemory.entrySet())if(taskId!=null&&taskId.equals(entry.getValue().optString("taskId"))){if(workingMemory.remove(entry.getKey(),entry.getValue()))entry.getKey().close();}}
    public void releaseWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session){workingMemory.remove(session);session.close();}
    public long memoryContextRevision(){return memoryContextRevision.get();}
    /** Scrub ephemeral notes before source/forget cleanup returns; no TaskStore or model I/O here. */
    public void clearWorkingMemory(JSONObject scope){
        memoryContextRevision.incrementAndGet();for(var entry:workingMemory.entrySet())if(scope.optString("ownerId").equals(entry.getValue().optString("ownerId"))&&scope.optString("spaceId").equals(entry.getValue().optString("spaceId"))){if(workingMemory.remove(entry.getKey(),entry.getValue()))entry.getKey().close();}
    }
    private final TaskStore tasks;
    private final dev.pocketengine.media.MediaWorkflowHost mediaWorkflows;
    private volatile AutomationPlanHost automationPlans;
    private volatile dev.pocketengine.features.local.project.TaskStoreProjectHost projectHost;
    public void setProjectHost(dev.pocketengine.features.local.project.TaskStoreProjectHost host){projectHost=java.util.Objects.requireNonNull(host);}
    public dev.pocketengine.features.local.project.ProjectOwnerLiveFileSave.Review reviewProjectLiveFileForOwner(String task,dev.pocketengine.features.local.project.ProjectFileDraft.SaveIntent original,dev.pocketengine.core.RunControl observation)throws Exception{
        var host=projectHost;if(host==null)throw new IllegalStateException("原项目宿主尚未挂载");return host.ownerFileSaves().review(task,original,observation);
    }
    public dev.pocketengine.features.local.project.ProjectOwnerLiveFileSave.Observation saveProjectLiveFileForOwner(dev.pocketengine.features.local.project.ProjectOwnerLiveFileSave.Review reviewed,dev.pocketengine.core.RunControl observation,java.util.function.Consumer<JSONObject> beforeDispatch)throws Exception{
        var host=projectHost;if(host==null)throw new IllegalStateException("原项目宿主尚未挂载");try{return host.ownerFileSaves().execute(reviewed,observation,beforeDispatch);}finally{sendState();}
    }
    public dev.pocketengine.features.local.project.ProjectOwnerLiveFileSave.Observation originalProjectLiveFileSaveForOwner(JSONObject locator)throws Exception{
        var host=projectHost;if(host==null)throw new IllegalStateException("原项目宿主尚未挂载");return host.ownerFileSaves().query(locator);
    }
    private final java.util.concurrent.ConcurrentHashMap<String,dev.pocketengine.core.RunControl> ownerStorageControls=new java.util.concurrent.ConcurrentHashMap<>();
    public JSONObject submitOwnerStorageCleanupForOwner(dev.pocketengine.features.local.project.ProjectStorage.Confirmed confirmed)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原存储与任务宿主不可用");JSONObject row=tasks.beginOwnerStorageCleanupForOwner(confirmed);if(row.optBoolean("knownOperation"))return row;String id=confirmed.actionId();var original=confirmed.original();original.bindHostRun(id,id,1);original.setAuthorityCheck(()->tasks.requireOwnerStorageCurrentForOwner(id,original));ownerStorageControls.put(id,original);emitTask(id);sendState();
        try{workers.execute(()->{try{JSONObject result=local.executeOwnerStorageForHost(confirmed,()->tasks.requireOwnerStorageCurrentForOwner(id,original));tasks.recordOwnerStorageCleanupForOwner(id,result);}catch(Exception unknown){try{JSONObject scope=tasks.ownerStorageCleanupForOwner(id).getJSONObject("scope");tasks.recordOwnerStorageCleanupForOwner(id,new JSONObject().put("actionId",id).put("planDigest",scope.getString("planDigest")).put("status","unknown").put("replayAllowed",false));}catch(Exception unrecorded){}}finally{ownerStorageControls.remove(id,original);original.cancel();try{emitTask(id);sendState();}catch(Exception ignored){}}});}catch(java.util.concurrent.RejectedExecutionException rejected){ownerStorageControls.remove(id,original);original.cancel();throw new IllegalStateException("原清理队列未确认；仅查询这个原编号，不自动提交："+id,rejected);}return tasks.ownerStorageCleanupForOwner(id);
    }
    public JSONObject queryOwnerStorageCleanupForOwner(String id,dev.pocketengine.core.RunControl original)throws Exception {JSONObject row=tasks.ownerStorageCleanupForOwner(id);if(ownerStorageControls.containsKey(id))return row.put("observation","original_cleanup_still_running");var local=(dev.pocketengine.features.local.LocalModule)module("local");JSONObject result=local.queryOwnerStorageForHost(row.getJSONObject("scope"),original);if(!"completed".equals(row.getString("status")))tasks.recordOwnerStorageCleanupForOwner(id,result);return result.put("taskId",id).put("originalTaskStatus",tasks.ownerStorageCleanupForOwner(id).getString("status"));}
    public JSONObject ownerStorageCleanupForOwner(String id)throws Exception{return tasks.ownerStorageCleanupForOwner(id);}
    public boolean ownsOwnerStorageTask(String id)throws Exception{JSONObject task=tasks==null?null:tasks.get(id,false);return task!=null&&task.has("ownerStorageCleanup");}
    public JSONObject cancelOwnerStorageCleanupForOwner(String id)throws Exception{JSONObject result=tasks.cancelOwnerStorageCleanupForOwner(id);var control=ownerStorageControls.get(id);if(control!=null)control.cancel();emitTask(id);sendState();return result;}
    private volatile dev.pocketengine.features.workspace.OwnerDataArchives ownerArchives;
    private final java.util.concurrent.ConcurrentHashMap<String,dev.pocketengine.core.RunControl> ownerArchiveControls=new java.util.concurrent.ConcurrentHashMap<>();
    /** Native owner settings only; no request()/module/model command can obtain this host. */
    public synchronized dev.pocketengine.features.workspace.OwnerDataArchives ownerDataArchives(android.content.Context nativeOwner)throws Exception {
        if(ownerArchives!=null)return ownerArchives;if(tasks==null)throw new IllegalStateException("原任务账本不可用");var workspace=(dev.pocketengine.features.workspace.WorkspaceModule)module("workspace");var local=(dev.pocketengine.features.local.LocalModule)module("local");var attachments=(dev.pocketengine.features.attachments.AttachmentsModule)module("attachments");var backend=new java.util.LinkedHashMap<String,dev.pocketengine.features.workspace.OwnerDataArchives.SectionBackend>();
        if(module("memory") instanceof dev.pocketengine.features.memory.MemoryModule memory)backend.put("memory",new dev.pocketengine.features.workspace.OwnerDataArchives.SectionBackend(){private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}public JSONObject export(dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();return memory.ownerArchive(scope());}public dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview review(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();dev.pocketengine.features.memory.MemoryArchive.checked(section,scope());return new dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview(){public JSONObject metadata()throws Exception{return new JSONObject().put("counts",section.getJSONObject("counts")).put("activeMemoryRestored",false).put("sourcePermissionsRestored",false);}public JSONObject restore()throws Exception{original.checkpoint();JSONObject result=memory.restoreOwnerArchive(scope(),section,action);original.checkpoint();return result;}};}public JSONObject query(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();return memory.queryOwnerArchiveRestore(scope(),section.getString("sectionDigest"),action);}});
        if(module("remote") instanceof dev.pocketengine.features.remote.RemoteModule remote)backend.put("remoteConnections",new dev.pocketengine.features.workspace.OwnerDataArchives.SectionBackend(){public JSONObject export(dev.pocketengine.core.RunControl original)throws Exception{return remote.exportConnectionBackupForOwner(original);}public dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview review(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{var exact=remote.reviewConnectionBackupForOwner(section,action,original);return new dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview(){public JSONObject metadata()throws Exception{return exact.metadata();}public JSONObject restore()throws Exception{return remote.restoreConnectionBackupForOwner(exact.confirm(),original);}};}public JSONObject query(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{return remote.queryConnectionBackupForOwner(section,action,original);}});
        if(module("ssh") instanceof dev.pocketengine.features.ssh.SshModule ssh)backend.put("sshConnections",new dev.pocketengine.features.workspace.OwnerDataArchives.SectionBackend(){public JSONObject export(dev.pocketengine.core.RunControl original)throws Exception{return ssh.exportConnectionBackupForOwner(original);}public dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview review(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{var exact=ssh.reviewConnectionBackupForOwner(section,action,original);return new dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview(){public JSONObject metadata()throws Exception{return exact.metadata();}public JSONObject restore()throws Exception{return ssh.restoreConnectionBackupForOwner(exact.confirm(),original);}};}public JSONObject query(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{return ssh.queryConnectionBackupForOwner(section,action,original);}});
        ownerArchives=new dev.pocketengine.features.workspace.OwnerDataArchives(new java.io.File(nativeOwner.getApplicationContext().getFilesDir(),"owner-data-archives"),workspace,local,attachments,backend);return ownerArchives;
    }
    /** Original external task owns one fixed native phase; pages only observe it. */
    public JSONObject submitOwnerArchivePhaseForOwner(dev.pocketengine.features.workspace.OwnerDataArchives.Confirmed confirmed)throws Exception {
        if(ownerArchives==null)throw new IllegalStateException("原备份入口未挂载");ownerArchives.requireConfirmed(confirmed);JSONObject admitted=tasks.beginOwnerArchivePhaseForOwner(confirmed);String id=confirmed.actionId();if(admitted.optBoolean("knownOperation"))return tasks.ownerArchivePhaseForOwner(id);var original=confirmed.original();original.bindHostRun(id,id,1);original.setAuthorityCheck(()->tasks.requireOwnerArchiveCurrentForOwner(id,original));ownerArchiveControls.put(id,original);emitTask(id);sendState();
        try{workers.execute(()->{try{JSONObject result=confirmed.restore();tasks.recordOwnerArchivePhaseForOwner(id,result);}catch(Exception unknown){try{JSONObject scope=tasks.ownerArchivePhaseForOwner(id).getJSONObject("scope");tasks.recordOwnerArchivePhaseForOwner(id,new JSONObject(scope.toString()).put("found",false).put("status","unknown"));}catch(Exception unrecorded){}}finally{ownerArchiveControls.remove(id,original);original.cancel();try{emitTask(id);sendState();}catch(Exception ignored){}}});}
        catch(java.util.concurrent.RejectedExecutionException rejected){ownerArchiveControls.remove(id,original);original.cancel();/* The persisted original intent stays query-only, never silently submitted later. */throw new IllegalStateException("本机任务队列暂满；请只读核对原恢复编号，不自动重派："+id,rejected);}return tasks.ownerArchivePhaseForOwner(id);
    }
    public JSONObject queryOwnerArchivePhaseForOwner(String id,dev.pocketengine.core.RunControl observation)throws Exception {
        if(ownerArchives==null)throw new IllegalStateException("请先打开原备份入口");JSONObject original=tasks.ownerArchivePhaseForOwner(id),scope=original.getJSONObject("scope");if(ownerArchiveControls.containsKey(id))return original.put("observation","original_native_phase_still_running");var selected=ownerArchives.reopen(scope.getString("selectionId"),scope.getString("archiveSha256"),observation);JSONObject actual;try{actual=ownerArchives.query(selected,scope,observation);}catch(Exception unavailable){if(!"completed".equals(original.getString("status")))throw unavailable;return original.put("originalRestorationConfirmed",true).put("currentDataUnavailable",true).put("replayAllowed",false);}if(!"completed".equals(original.getString("status")))tasks.recordOwnerArchivePhaseForOwner(id,actual);emitTask(id);sendState();return actual.put("taskId",id);
    }
    /** Explicit native owner cache removal. Pending original restores fence it;
     * a cache locator is never promoted into permission to delete other data. */
    public JSONObject discardOwnerArchiveCacheForOwner(dev.pocketengine.features.workspace.OwnerDataArchives.Selection selected,dev.pocketengine.core.RunControl original)throws Exception {if(ownerArchives==null)throw new IllegalStateException("原备份入口未就绪");JSONObject metadata=selected.metadata();synchronized(tasks){tasks.requireOwnerArchiveCacheUnusedForOwner(metadata.getString("selectionId"));return ownerArchives.discardLocalCache(selected,original);}}
    public JSONObject ownerArchivePhaseForOwner(String id)throws Exception{return tasks.ownerArchivePhaseForOwner(id);}
    public dev.pocketengine.store.TaskStore.OwnerArchiveProjectMap ownerArchiveProjectMapForOwner(String projectPhase,String archiveSha)throws Exception{return tasks.ownerArchiveProjectMapForOwner(projectPhase,archiveSha);}
    public boolean ownsOwnerArchiveTask(String id)throws Exception {JSONObject task=tasks==null?null:tasks.get(id,false);return task!=null&&task.has("ownerArchivePhase");}
    public JSONObject cancelOwnerArchivePhaseForOwner(String id)throws Exception {JSONObject result=tasks.cancelOwnerArchivePhaseForOwner(id);var original=ownerArchiveControls.get(id);if(original!=null)original.cancel();emitTask(id);sendState();return result;}
    /** Native original-project UI only; never a generic feature/model JSON command. */
    public dev.pocketengine.features.local.project.ProjectOwnerTerminal.Session openProjectTerminalForOwner(String task,String execution,dev.pocketengine.core.RunControl observation)throws Exception{
        var host=projectHost;if(host==null)throw new IllegalStateException("原项目宿主尚未挂载");return host.ownerTerminals().open(task,execution,observation);
    }
    public void setAutomationPlanHost(AutomationPlanHost plans){automationPlans=java.util.Objects.requireNonNull(plans);}
    public JSONObject mediaReadContinuationStatus()throws Exception{return mediaWorkflows==null?new JSONObject():mediaWorkflows.continuationSnapshot();}
    public dev.pocketengine.core.RunControl mediaControl(String taskId){return mediaWorkflows==null?null:mediaWorkflows.currentControl(taskId);}
    public boolean ownsMediaTask(String taskId)throws Exception{return mediaWorkflows!=null&&mediaWorkflows.ownsTask(taskId);}
    public JSONObject cancelMediaTask(String taskId)throws Exception{return mediaWorkflows==null?null:mediaWorkflows.cancelTask(taskId);}
    /** Native attachment/outcome viewer only; the supplied control can stop this original read/fetch. */
    public JSONObject originalMediaForOwner(String command,JSONObject payload,dev.pocketengine.core.RunControl control)throws Exception{
        EngineModule module=module("media");if(mediaWorkflows==null||!(module instanceof dev.pocketengine.media.MediaModule))throw new IllegalStateException("原媒体宿主尚未就绪");
        return mediaWorkflows.handleOriginal((dev.pocketengine.media.MediaModule)module,command,payload,control);
    }
    private final java.util.concurrent.ConcurrentHashMap<String,dev.pocketengine.core.RunControl> ownerGitPublicationControls=new java.util.concurrent.ConcurrentHashMap<>();
    public JSONObject submitGitPublicationForOwner(dev.pocketengine.features.local.LocalModule.GitPublicationConfirmed confirmed)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原项目发布宿主不可用");JSONObject admitted=tasks.beginOwnerGitPublicationForOwner(confirmed);String id=confirmed.actionId();if(admitted.optBoolean("knownOperation"))return gitPublicationForOwner(id,false);var original=confirmed.original();original.bindHostRun(id,id,1);original.setAuthorityCheck(()->tasks.requireOwnerGitPublicationCurrentForOwner(id,original));ownerGitPublicationControls.put(id,original);emitTask(id);sendState();
        try{workers.execute(()->{try{var actual=local.executeGitPublicationForOwner(confirmed);tasks.recordOwnerGitPublicationForOwner(id,actual);}catch(Exception unconfirmed){try{JSONObject row=tasks.ownerGitPublicationForOwner(id);var actual=local.queryGitPublicationForOwner(row.getJSONObject("scope").getJSONObject("plan"));tasks.recordOwnerGitPublicationForOwner(id,actual);}catch(Exception unknown){try{tasks.status(id,"reconciling","原Git项目发布未确认；只查询原动作，不重复创建项目");}catch(Exception unrecorded){}}}finally{ownerGitPublicationControls.remove(id,original);original.cancel();try{emitTask(id);sendState();}catch(Exception ignored){}}});}
        catch(java.util.concurrent.RejectedExecutionException rejected){ownerGitPublicationControls.remove(id,original);original.cancel();/* Original intent remains query-only. No late submission or new confirmation. */throw new IllegalStateException("原项目发布未进入可确认的工作队列；只核对原动作："+id,rejected);}return tasks.ownerGitPublicationForOwner(id);
    }
    public JSONObject gitPublicationForOwner(String id,boolean cancel)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原项目发布宿主不可用");JSONObject row=cancel?tasks.cancelOwnerGitPublicationForOwner(id):tasks.ownerGitPublicationForOwner(id);if(cancel){var original=ownerGitPublicationControls.get(id);if(original!=null)original.cancel();}if(ownerGitPublicationControls.containsKey(id))return row.put("observation","original_native_copy_host_retained");var actual=local.queryGitPublicationForOwner(row.getJSONObject("scope").getJSONObject("plan"));tasks.recordOwnerGitPublicationForOwner(id,actual);emitTask(id);sendState();return actual.metadata().put("taskId",id).put("originalTaskStatus",tasks.ownerGitPublicationForOwner(id).getString("status"));
    }
    public JSONObject gitPublicationForSourceForOwner(String sourceId)throws Exception{return tasks.ownerGitPublicationForSourceForOwner(sourceId);}
    public JSONObject stopGitImportForOwner(String id)throws Exception{JSONObject task=tasks.get(id,false);if(task!=null&&task.has("ownerGitPublication"))return gitPublicationForOwner(id,true);return gitImportForOwner(id,true);}

    private final java.util.concurrent.ConcurrentHashMap<String,dev.pocketengine.core.RunControl> ownerGitImportControls=new java.util.concurrent.ConcurrentHashMap<>();
    /** Exactly one native owner intake on the original finite capability worker.
     * Closing its page drops an observer, not the original job or its authority. */
    public JSONObject submitGitImportForOwner(dev.pocketengine.features.local.project.ProjectGitImport.Confirmed confirmed)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原Git与任务宿主不可用");var intake=local.gitImportsForOwner();intake.requireConfirmed(confirmed);JSONObject admitted=tasks.beginOwnerGitImportForOwner(confirmed);if(admitted.optBoolean("knownOperation"))return gitImportForOwner(confirmed.actionId(),false);String id=confirmed.actionId();var original=new dev.pocketengine.core.RunControl();original.bindHostRun(id,id,1);original.setAuthorityCheck(()->tasks.requireOwnerGitImportCurrentForOwner(id,original));ownerGitImportControls.put(id,original);emitTask(id);sendState();
        try{workers.execute(()->{try{
            intake.execute(confirmed,original,()->tasks.requireOwnerGitImportCurrentForOwner(id,original),(exact,binding)->tasks.bindOwnerGitImportJobForHost(confirmed,exact,binding,original));
            // Read only this already dispatched original job. No fetch retry,
            // credential replay, second Runner or page-owned timer is created.
            long until=System.nanoTime()+610_000_000_000L;while(true){JSONObject row=tasks.ownerGitImportForOwner(id);var actual=intake.observe(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),false);String status=actual.metadata().getString("status");if(!"running".equals(status)||System.nanoTime()>=until){tasks.recordOwnerGitImportForOwner(id,actual);break;}if(original.isCancelled())intake.observe(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),true);Thread.sleep(200);}
        }catch(dev.pocketengine.features.local.project.ProjectGitImport.NotDispatched rejected){try{tasks.recordOwnerGitImportNotDispatchedForOwner(confirmed,rejected);}catch(Exception unrecorded){}}
        catch(Exception uncertain){try{JSONObject row=tasks.ownerGitImportForOwner(id);if(row.optJSONObject("nativeJob")!=null){var actual=intake.observe(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),true);tasks.recordOwnerGitImportForOwner(id,actual);}if(!tasks.ownerGitImportForOwner(id).getString("status").matches("completed|failed"))tasks.status(id,"reconciling","原Git读取结果尚未完整确认；只核对原编号，不重送认证或重新下载");}catch(Exception unobserved){}}
        finally{ownerGitImportControls.remove(id,original);original.cancel();confirmed.close();try{emitTask(id);sendState();}catch(Exception ignored){}}});}
        catch(java.util.concurrent.RejectedExecutionException rejected){ownerGitImportControls.remove(id,original);original.cancel();confirmed.close();tasks.recordOwnerGitImportQueueRejectedForOwner(confirmed,rejected);emitTask(id);sendState();throw new IllegalStateException("原Git队列未接受该动作；未下载或发送认证，请核对原编号："+id,rejected);}return tasks.ownerGitImportForOwner(id);
    }
    /** Read/cancel exact existing ref only. A cold missing ref is unknown, never
     * evidence to reconstruct the old confirmation or send credentials again. */
    public JSONObject gitImportForOwner(String id,boolean cancel)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原Git与任务宿主不可用");JSONObject row=cancel?tasks.cancelOwnerGitImportForOwner(id):tasks.ownerGitImportForOwner(id);if(cancel){var control=ownerGitImportControls.get(id);if(control!=null)control.cancel();}if(row.optJSONObject("nativeJob")==null)return row.put("observation","original_intent_without_readable_native_reference");var observed=local.gitImportsForOwner().observe(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),cancel);if(observed.hasOriginalProcessReceipt()){tasks.recordOwnerGitImportForOwner(id,observed);emitTask(id);sendState();}JSONObject actual=observed.metadata();if("completed".equals(row.getString("status"))&&!"completed".equals(actual.getString("status"))){JSONObject historical=row.optJSONObject("receipt");if(historical==null)throw new SecurityException("原Git完成凭证缺失，不能从缓存猜测成功");actual=new JSONObject(historical.toString()).put("sourceAvailable",false).put("code","git_intake_source_no_longer_available").put("historicalCompletionPreserved",true);}return actual.put("taskId",id).put("originalTaskStatus",tasks.ownerGitImportForOwner(id).getString("status"));
    }
    public boolean ownsGitImportTask(String id)throws Exception{JSONObject task=tasks==null?null:tasks.get(id,false);return task!=null&&(task.has("ownerGitImport")||task.has("ownerGitPublication"));}
    public dev.pocketengine.features.local.project.ProjectGitImport.Result gitImportResultForOwner(String id,dev.pocketengine.core.RunControl observation)throws Exception{JSONObject row=tasks.ownerGitImportForOwner(id);if(!"completed".equals(row.getString("status")))throw new SecurityException("请先取得原Git读取与退出结果，再审阅发布");return ((dev.pocketengine.features.local.LocalModule)module("local")).gitImportsForOwner().result(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),observation);}

    /** Native project UI only: the opaque confirmation is never accepted by request()/feature JSON. */
    public JSONObject submitProjectGitForOwner(dev.pocketengine.features.local.project.ProjectGit.Confirmed selected)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule))throw new IllegalStateException("原本机项目宿主不可用");
        var local=(dev.pocketengine.features.local.LocalModule)module("local");selected.requireUnexpired();JSONObject admitted=tasks.beginOwnerGitTask(selected);String id=selected.requestId();emitTask(id);sendState();
        if(admitted.optBoolean("knownOperation"))return projectGitForOwner(id,false);
        JSONObject binding=new JSONObject().put("taskId",id).put("runId",id).put("operationId",id).put("coordinatorEpoch",1),plan=selected.plan(),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectGit.step(plan));
        var original=new dev.pocketengine.core.RunControl();original.bindHostRun(id,id,1);
        try {
            JSONObject backend=local.executeGitForOwner(selected,binding,original,()->tasks.requireOwnerGitCurrent(id,original));
            tasks.recordProjectOperation(binding,exact,backend);tasks.finishOwnerGitFromReceipt(binding);
        } catch(dev.pocketengine.features.local.project.ProjectGit.NotDispatched notDispatched) {
            tasks.recordOwnerGitNotDispatched(selected,notDispatched);
        } catch(Exception uncertain) {
            // Runtime.dispatch was entered. Keep the exact original ref and ask that job to stop;
            // an exception or a missing local cache is never proof that no Git effect happened.
            try {JSONObject backend=local.projectReceiptForHost(exact,binding,true);tasks.recordProjectOperation(binding,exact,backend);tasks.finishOwnerGitFromReceipt(binding);}catch(Exception unavailable){}
            if(tasks.hasUnresolved(id))tasks.endAttempt(id,id,"reconciling","原Git派发或回执未完整确认；只核对原编号，不重放",new JSONObject().put("nativeOwnerGit",true).put("operationId",id));
        }
        emitTask(id);sendState();return projectGitForOwner(id,false);
    }
    /** Original same-task process/log observation. No new task, approval, run or command. */
    public JSONObject projectGitForOwner(String id,boolean cancel)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule))throw new IllegalStateException("原本机项目宿主不可用");
        JSONObject original=cancel?tasks.cancelOwnerGitForOwner(id):tasks.ownerGitOriginalForOwner(id),plan=original.getJSONObject("plan"),binding=original.getJSONObject("binding"),operation=original.getJSONObject("operation");
        JSONObject git;JSONObject stored=operation.optJSONObject("receipt");
        if("receipt".equals(operation.optString("state"))&&stored!=null&&"project_git_preflight_rejected".equals(stored.optString("code")))git=new JSONObject().put("ok",false).put("code",stored.getString("code")).put("message",stored.optString("message")).put("effectStatus","not_published").put("publicationUnknown",false);
        else {
            try {
                JSONObject actual=((dev.pocketengine.features.local.LocalModule)module("local")).observeGitForOwner(plan,binding,cancel);git=actual.getJSONObject("git");
                JSONObject exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectGit.step(plan));tasks.recordProjectOperation(binding,exact,actual.getJSONObject("backend"));tasks.finishOwnerGitFromReceipt(binding);
            } catch(Exception unobserved) {git=new JSONObject().put("ok",false).put("code","git_original_observation_unavailable").put("publicationUnknown",true).put("message","原Git进程回执暂不可读；原编号保留，不另起执行");}
        }
        emitTask(id);sendState();return new JSONObject().put("hostTaskId",id).put("taskId",id).put("runId",id).put("operationId",id).put("planDigest",plan.getString("planDigest")).put("checkoutId",plan.getString("checkoutId")).put("projectId",plan.getString("projectId")).put("status",tasks.get(id,false).getString("status")).put("git",git).put("replayAllowed",false);
    }
    public JSONObject processFileForOwner(dev.pocketengine.features.attachments.processing.NativeFileProcess.Review review,dev.pocketengine.core.RunControl original,String actionId)throws Exception{
        if(tasks==null||review==null||original==null||original.hasHostRun())throw new SecurityException("缺少原生主人加工审阅和调用控制");EngineModule selected=module("attachments");if(!(selected instanceof dev.pocketengine.features.attachments.AttachmentsModule))throw new IllegalStateException("原附件模块不可用");review.source().requireCurrent(original);JSONObject admitted=tasks.beginOwnerFileProcessForOwner(actionId,review);if(admitted.optBoolean("knownOperation"))return fileProcessResultForOwner(actionId,original);String run=admitted.getString("runId");int epoch=admitted.getInt("coordinatorEpoch");var worker=new dev.pocketengine.core.RunControl();worker.bindHostRun(actionId,run,epoch);worker.setAuthorityCheck(()->{original.check();tasks.requireExternalRun(actionId,run,"attachments",true);});Runnable cancel=worker::cancel;original.onCancel(cancel);emitTask(actionId);
        try{tasks.status(actionId,"running","正在加工原固定文件；原件保留，新产物独立保存");JSONObject result=((dev.pocketengine.features.attachments.AttachmentsModule)selected).executeProcessingForHost(review,actionId,actionId,run,epoch,worker);updateTask(actionId,result);sendState();return result.put("hostTaskId",actionId);}
        catch(Exception unknown){updateTask(actionId,json("status","unknown","accepted",JSONObject.NULL,"operationId",actionId,"code","original_processing_publication_unknown","message","原加工结果尚未完整确认；只核对原动作，不自动重复发布"));sendState();throw new IllegalStateException("原加工动作待核："+actionId,unknown);}
        finally{original.removeCancelAction(cancel);worker.cancel();}
    }
    public JSONObject fileProcessResultForOwner(String id,dev.pocketengine.core.RunControl original)throws Exception{
        JSONObject task=tasks.get(id,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||!"attachments".equals(task.optString("feature"))||!task.optString("command").matches("transformImage|trimMedia"))throw new SecurityException("不是原主人原件加工动作");JSONObject record=task.getJSONObject("externalRefs").getJSONObject("fileProcessing");var attachments=(dev.pocketengine.features.attachments.AttachmentsModule)module("attachments");JSONObject found=attachments.processingResultForHost(task.getString("operationId"),id,task.getString("runId"),record.getString("planDigest"),original);if(found.optBoolean("found")){found.put("status","completed").put("accepted",true).put("receiptReceived",true);updateTask(id,found);sendState();}return found.put("taskId",id).put("replayAllowed",false);
    }
    /** One explicit owner document action on the existing external task ledger, never a Runner. */
    public JSONObject createDocumentForOwner(JSONObject source,dev.pocketengine.core.RunControl original)throws Exception{return createDocumentForOwner(source,original,UUID.randomUUID().toString());}
    public JSONObject createDocumentForOwner(JSONObject source,dev.pocketengine.core.RunControl original,String actionId)throws Exception{
        if(tasks==null||original==null||original.hasHostRun())throw new SecurityException("主人文档动作需要独立原生调用控制");
        var draft=new dev.pocketengine.features.attachments.creation.DocumentDraft(new JSONObject(source.toString()));EngineModule selected=module("attachments");if(!(selected instanceof dev.pocketengine.features.attachments.AttachmentsModule))throw new IllegalStateException("原附件模块不可用");
        String id=actionId;JSONObject input=new JSONObject(source.toString()).put("_nativeDocumentPlanDigest",draft.identity);JSONObject admitted=tasks.beginOwnerDocumentForOwner(id,draft);if(admitted.optBoolean("knownOperation"))return documentResultForOwner(id,original);String run=admitted.getString("runId");int epoch=admitted.getInt("coordinatorEpoch");
        var worker=new dev.pocketengine.core.RunControl();worker.bindHostRun(id,run,epoch);worker.setAuthorityCheck(()->{original.check();tasks.requireExternalRun(id,run,"attachments",true);});Runnable cancel=worker::cancel;original.onCancel(cancel);emitTask(id);
        try{original.check();tasks.status(id,"running","正在生成主人明确选择的固定文档；原可编辑源与成果分别保存");JSONObject result=((dev.pocketengine.features.attachments.AttachmentsModule)selected).createDocumentForHost(input.put("_taskId",id).put("_runId",run).put("_operationId",id).put("_epoch",epoch),worker);updateTask(id,result);sendState();return result.put("hostTaskId",id);}
        catch(Exception unconfirmed){updateTask(id,json("status","unknown","accepted",JSONObject.NULL,"code","document_original_publication_unconfirmed","message","原文档创建未完整确认；请按原任务核对已保存源和成果，不自动重建","operationId",id));sendState();throw new IllegalStateException("文档保存状态待核，原任务："+id+"；未自动重复生成",unconfirmed);}
        finally{original.removeCancelAction(cancel);worker.cancel();}
    }
    public JSONObject documentResultForOwner(String id,dev.pocketengine.core.RunControl original)throws Exception{
        JSONObject task=tasks.get(id,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||!"attachments".equals(task.optString("feature"))||!"createDocument".equals(task.optString("command")))throw new SecurityException("不是原主人文档动作");
        JSONObject record=task.getJSONObject("externalRefs").getJSONObject("documentCreation");var attachments=(dev.pocketengine.features.attachments.AttachmentsModule)module("attachments");
        JSONObject result=attachments.documentResultForHost(task.getString("operationId"),id,task.getString("runId"),record.getString("planDigest"),original);if(result.optBoolean("found")){result.put("status","completed").put("receiptReceived",true).put("accepted",true);updateTask(id,result);sendState();}return result.put("taskId",id).put("replayAllowed",false);
    }
    public JSONObject observeMediaForOwner(String taskId,String operationId,dev.pocketengine.core.RunControl control,dev.pocketengine.media.jobs.ComfyUiProgressObserver.Listener listener)throws Exception{
        EngineModule module=module("media");if(mediaWorkflows==null||!(module instanceof dev.pocketengine.media.MediaModule))throw new IllegalStateException("原媒体宿主尚未就绪");
        return mediaWorkflows.observeOriginal((dev.pocketengine.media.MediaModule)module,taskId,operationId,control,listener);
    }
    private final BooleanSupplier phoneBusy;
    private final ExecutionResources resources;
    public FeatureHost(EventSink events){this(events,null,()->false);}
    public FeatureHost(EventSink events,TaskStore tasks,BooleanSupplier phoneBusy){this(events,tasks,phoneBusy,new ExecutionResources());}
    public FeatureHost(EventSink events,TaskStore tasks,BooleanSupplier phoneBusy,ExecutionResources resources){this.events=events;this.tasks=tasks;this.mediaWorkflows=tasks==null?null:new dev.pocketengine.media.MediaWorkflowHost(tasks,workers);this.phoneBusy=phoneBusy;this.resources=java.util.Objects.requireNonNull(resources);}
    private boolean teamWritesMounted;
    private volatile dev.pocketengine.features.teams.TaskStoreTeamWriteHost nativeTeamWrites;
    public dev.pocketengine.core.RunControl teamControl(String taskId){var host=nativeTeamWrites;return host==null?null:host.currentControl(taskId);}
    public synchronized void register(EngineModule module){if(modules.containsKey(module.id()))throw new IllegalArgumentException("重复模块");modules.put(module.id(),module);mountTeamWrites();mountTeamProjects();}
    private void mountTeamWrites(){
        if(teamWritesMounted||tasks==null)return;EngineModule team=modules.get("teams"),workspace=modules.get("workspace");
        if(team instanceof dev.pocketengine.features.teams.TeamsModule&&workspace!=null){java.util.function.Consumer<String> changed=id->{try{emitTask(id);}catch(Exception failure){throw new IllegalStateException("原团队任务通知未能保存",failure);}};dev.pocketengine.features.teams.TeamWorkFiles.Workspace teamWorkspace=teamWorkspace(workspace);var host=new dev.pocketengine.features.teams.TaskStoreTeamWriteHost(tasks,teamWorkspace,changed);EngineModule remote=modules.get("remote");if(remote instanceof dev.pocketengine.features.remote.RemoteModule)host.setRemoteProduction(new dev.pocketengine.features.teams.TeamRemoteProduction(tasks,(dev.pocketengine.features.remote.RemoteModule)remote,teamWorkspace,signals,changed));host.setParentCleanup(this::nativeOwnerCleanup);((dev.pocketengine.features.teams.TeamsModule)team).setWriteHost(host);nativeTeamWrites=host;teamWritesMounted=true;}
    }
    private boolean teamProjectsMounted;
    private static dev.pocketengine.features.teams.TeamWorkFiles.Workspace teamWorkspace(EngineModule workspace){return workspace instanceof dev.pocketengine.features.workspace.WorkspaceModule?new dev.pocketengine.features.teams.TeamWorkspaceAccess((dev.pocketengine.features.workspace.WorkspaceModule)workspace):workspace::handle;}
    private void mountTeamProjects(){
        if(teamProjectsMounted||nativeTeamWrites==null||tasks==null)return;EngineModule local=modules.get("local"),workspace=modules.get("workspace");if(local instanceof dev.pocketengine.features.local.LocalModule&&workspace!=null){nativeTeamWrites.setProjectProduction(new dev.pocketengine.features.teams.TeamProjectWorkspaces(tasks,(dev.pocketengine.features.local.LocalModule)local,teamWorkspace(workspace),id->{try{emitTask(id);}catch(Exception failure){throw new IllegalStateException("原团队项目通知未保存",failure);}}));teamProjectsMounted=true;}
    }
    public synchronized EngineModule module(String id){return modules.get(id);}
    /** Native Activity disposal must not strand OAuth listeners or MCP previews when its executor closes. */
    public void nativeOwnerCleanup(Runnable cleanup){controls.execute(cleanup);}
    public JSONObject teamDefinition(String id)throws Exception{
        EngineModule module=module("teams");if(module==null)throw new IllegalStateException("团队模块不可用");org.json.JSONArray definitions=module.snapshot().getJSONArray("teams");
        for(int i=0;i<definitions.length();i++)if(id.equals(definitions.getJSONObject(i).optString("id")))return new JSONObject(definitions.getJSONObject(i).toString());throw new IllegalArgumentException("团队配置不存在");
    }
    public JSONObject readTeamForTask(String task,String run,int epoch,JSONObject args,dev.pocketengine.core.RunControl original)throws Exception{var source=tasks.teamParentOrigin(task,run,epoch,"teams_get","",args,original);tasks.teamParentAccess(source,args.getString("teamRunId"),false);return module("teams").handle("get",json("runId",args.getString("teamRunId")));}
    public boolean hasParentTeamOrchestration(){EngineModule module=module("teams");return module instanceof dev.pocketengine.features.teams.TeamsModule&&((dev.pocketengine.features.teams.TeamsModule)module).parentOrchestrationConfigured();}
    public JSONObject teamParentCall(String task,String run,int epoch,String tool,String operation,JSONObject args,dev.pocketengine.core.RunControl original)throws Exception{EngineModule module=module("teams");if(!(module instanceof dev.pocketengine.features.teams.TeamsModule))throw new SecurityException("原团队模块未接入");return ((dev.pocketengine.features.teams.TeamsModule)module).parentCall(task,run,epoch,tool,operation,args,original);}
    public JSONObject startTeamForTask(String parent,String parentRun,String operationId,JSONObject args,JSONObject expectedDefinition,dev.pocketengine.core.RunControl control)throws Exception{
        if(tasks==null)throw new IllegalStateException("宿主任务存储不可用");EngineModule module=module("teams");if(module==null)throw new IllegalStateException("团队模块不可用");
        control.check();JSONObject link=tasks.beginRelatedExternal(parent,parentRun,"teams","start",operationId,args);emitTask(parent);emitTask(operationId);
        if(link.getBoolean("knownOperation"))return module.handle("get",json("runId",operationId)).put("hostTaskId",operationId).put("taskId",operationId).put("parentTaskId",parent);
        try{control.check();JSONObject result;synchronized(module){if(!TaskStore.capabilityFingerprint(expectedDefinition).equals(TaskStore.capabilityFingerprint(teamDefinition(args.getString("teamId")))))throw new SecurityException("团队成员或执行位置已变化，请重新确认");JSONObject nativeInput=new JSONObject(args.toString()).put("_taskId",operationId).put("_runId",operationId).put("_operationId",operationId);
            if(expectedDefinition.optString("mode").equals("produce")){
                EngineModule workspace=module("workspace");JSONObject original=tasks.get(parent,false);
                if(!(module instanceof dev.pocketengine.features.teams.TeamsModule)||workspace==null||original==null||!control.matchesHostRun(parent,parentRun,original.getInt("coordinatorEpoch")))throw new SecurityException("文件协作缺少原父任务项目与执行控制");
                var scope=((dev.pocketengine.features.teams.TeamsModule)module).captureWriteScope(parent,parentRun,original.getJSONObject("developmentContext").getString("projectId"),expectedDefinition);
                tasks.bindTeamWriteScope(operationId,scope);control.check();result=((dev.pocketengine.features.teams.TeamsModule)module).startNative(nativeInput,scope);
            }else result=module.handle("start",nativeInput);}updateTask(operationId,result);return result.put("hostTaskId",operationId).put("taskId",operationId).put("parentTaskId",parent);}
        catch(Exception failure){updateTask(operationId,json("status",failure instanceof IllegalArgumentException||failure instanceof SecurityException?"failed":"unknown","message",safe(failure)));throw failure;}
    }
    /** A private AgentRunner operation becomes one tracked remote child; never a second scheduler. */
    public JSONObject startRemoteForTask(String parent,String parentRun,int epoch,String operationId,JSONObject payload,JSONObject selection,dev.pocketengine.core.RunControl control)throws Exception {
        if(tasks==null||!operationId.matches("[a-f0-9-]{36}"))throw new SecurityException("电脑派发缺少原宿主操作");
        tasks.checkCapability(parent,parentRun,epoch,dev.pocketengine.features.remote.RemoteTaskCapabilities.START,control);
        EngineModule module=module("remote");if(!(module instanceof dev.pocketengine.features.remote.RemoteModule))throw new IllegalStateException("电脑执行器不可用");
        var remote=(dev.pocketengine.features.remote.RemoteModule)module;
        remote.requireProfileBinding(payload.getString("profileId"),payload.getString("_profileBinding"));
        JSONObject original=tasks.get(parent,false);if(!dev.pocketengine.features.remote.RemoteTaskCapabilities.eligible(original))throw new SecurityException("此父任务不是私人主人执行");
        JSONObject link=tasks.prepareRemoteDelegation(parent,parentRun,epoch,operationId,payload,control);String child=link.getString("taskId");emitTask(parent);emitTask(child);
        String service=selection.getString("serviceBase");JSONObject result;
        if(link.getBoolean("knownOperation"))result=remote.handle("reconcileOperation",json("profileId",payload.getString("profileId"),"operationId",child,"serviceBase",service));
        else {
            // Origin is durable before the one POST, including a crash before RemoteModule.reserve.
            tasks.updateExternal(child,json("profileId",payload.getString("profileId"),"serviceBase",service,"operationId",child,"status","submitting"));emitTask(child);
            try {
                control.check();tasks.checkCapability(parent,parentRun,epoch,dev.pocketengine.features.remote.RemoteTaskCapabilities.START,control);
                remote.requireProfileBinding(payload.getString("profileId"),payload.getString("_profileBinding"));
                result=remote.handle("submitJob",new JSONObject(payload.toString()).put("operationId",child).put("_taskId",child).put("_runId",child));
            }catch(Exception uncertain){result=json("profileId",payload.getString("profileId"),"serviceBase",service,"operationId",child,"status","unknown","message","原电脑操作已登记，派发回执尚未确认；只查询原编号，不重新提交");}
        }
        updateTask(child,result);return result.put("hostTaskId",child).put("taskId",child).put("parentOperationId",operationId).put("parentTaskId",parent).put("ok",!result.optString("status").matches("unknown|not_found|failed|blocked|rejected")).put("remoteEffectsVerified",false);
    }
    public JSONObject readRemoteForTask(String parent,String parentRun,int epoch,String child,dev.pocketengine.core.RunControl control)throws Exception {
        tasks.checkCapability(parent,parentRun,epoch,dev.pocketengine.features.remote.RemoteTaskCapabilities.READ,control);
        JSONObject relation=tasks.requirePrivateRemoteChild(child);if(!parent.equals(relation.getString("parentTaskId")))throw new SecurityException("原电脑操作不属于这个私人任务");
        JSONObject target=tasks.remoteOwnerTarget(child),input=json("profileId",target.getString("profileId"),"serviceBase",target.getString("serviceBase"));
        EngineModule module=module("remote");if(!(module instanceof dev.pocketengine.features.remote.RemoteModule))throw new IllegalStateException("电脑执行器不可用");
        JSONObject result=target.optString("jobId").isEmpty()?module.handle("reconcileOperation",input.put("operationId",target.getString("jobOperationId"))):module.handle("getJob",input.put("jobId",target.getString("jobId")).put("jobOperationId",target.getString("jobOperationId")));
        tasks.updateExternal(child,result);emitTask(child);control.check();tasks.checkCapability(parent,parentRun,epoch,dev.pocketengine.features.remote.RemoteTaskCapabilities.READ,control);
        return result.put("hostTaskId",child).put("parentTaskId",parent).put("queryOnly",true);
    }
    public void cancelRelatedTasks(String parent){
        if(tasks==null)return;try{JSONObject task=tasks.get(parent,false);for(int i=0;task!=null&&task.optJSONArray("relatedTasks")!=null&&i<task.getJSONArray("relatedTasks").length();i++){
            String id=task.getJSONArray("relatedTasks").getJSONObject(i).getString("taskId");JSONObject child=tasks.get(id,false);if(child==null||!child.optString("status").matches("preparing|queued|running|paused|awaiting_user|stopping"))continue;
            String feature=child.optString("feature");JSONObject input=new JSONObject();String command;
            if(feature.equals("teams")){command="cancel";input.put("runId",child.getString("runId"));}
            else if(feature.equals("remote")&&!child.optString("externalId").isEmpty()){command="cancelJob";input.put("profileId",child.getJSONObject("externalRefs").getString("profileId")).put("jobId",child.getString("externalId"));}
            else continue;
            tasks.requestCancel(id,child.optString("runId"));request(json("feature",feature,"command",command,"payload",input));cancelRelatedTasks(id);
        }}catch(Exception error){events.emit(json("type","error","message","子任务停止状态尚未确认，请查看原任务"));}
    }
    public void request(JSONObject request){
        String feature=request.optString("feature"),command=request.optString("command"),requestId=request.optString("requestId");
        if(requestId.length()>100||feature.length()>40||command.length()>80){reply(feature,command,requestId,false,null,"请求标识过长");return;}
        Runnable work=()->{
            String tracked=null,trackedRun=null,operation=null,browserTask=null,browserAdmission=null;
            try{EngineModule module=module(feature);if(module==null)throw new IllegalArgumentException("此能力尚未接入");
                JSONObject payload=request.optJSONObject("payload");JSONObject input=payload==null?new JSONObject():new JSONObject(payload.toString());
                if(feature.equals("ssh")&&command.equals("doctor"))throw new SecurityException("请使用当前本机私人环境诊断页；通用能力回执不接收诊断正文");
                if(feature.equals("automation")&&java.util.Set.of("approvePlanExecution","startPlanExecution","setPlanPaused","cancelPlanExecution","editPlanStep","retryPlanStep","preparePlanTask","planTaskRequest","bindPlanTask","refreshPlanTasks","ownerCommand","automationPlan").contains(command))throw new SecurityException("执行计划须从本机目标审阅入口控制，工具参数不能授予执行权限");
                if(feature.equals("remote")&&command.equals("approveJob"))throw new SecurityException("请在本机原任务页审阅并一次确认本次授权");
                if(feature.equals("remote")&&command.equals("submitJob")&&(input.has("threadId")||input.has("resumeJobId")||input.has("jobOperationId")||input.has("sourceServiceBase")))throw new SecurityException("会话续接只能从本机原任务审阅页发起，不能新建任务代替");
                if(feature.equals("remote")&&java.util.Set.of("steerJob","getSteerMessage").contains(command)){
                    JSONObject result=remoteMessageRequest(module,command,input);reply(feature,command,requestId,true,result,null);sendState();return;
                }
                String teamConversationId="";AssistantModule teamConversation=null;
                if(feature.equals("teams")&&command.equals("start")&&!input.optString("conversationId").isEmpty()){
                    if(tasks==null)throw new IllegalStateException("宿主任务存储不可用");
                    EngineModule assistant=module("assistant");if(!(assistant instanceof AssistantModule))throw new SecurityException("私人聊天模块不可用，不能绑定团队任务");
                    teamConversationId=input.getString("conversationId");teamConversation=(AssistantModule)assistant;
                    if(!teamConversationId.equals(teamConversation.handle("get",json("conversationId",teamConversationId)).getString("conversationId")))throw new SecurityException("团队任务的私人对话身份无效");
                }
                if(feature.equals("memory")){
                    if(java.util.Set.of("delete","forget","sourceUpdated","sourceDeleted").contains(command))throw new SecurityException("请到原生记忆学习审核页读取当前版本并明确遗忘；来源变更仅由宿主自动核对，旧入口不能绕过清理回执");
                    input.put("scope",json("ownerId","owner","spaceId","private"));synchronizeMemory(input.getJSONObject("scope"));
                }
                if(feature.equals("automation")&&AutomationPlanHost.handles(command)){
                    AutomationPlanHost plans=automationPlans;
                    if(plans==null||!(module instanceof dev.pocketengine.features.automation.AutomationModule))throw new IllegalStateException("原生计划宿主尚未就绪");
                    JSONObject result=plans.handle((dev.pocketengine.features.automation.AutomationModule)module,command,input);
                    emitPlanTasks(result);reply(feature,command,requestId,true,result,null);sendState();return;
                }
                if(feature.equals("media")&&mediaWorkflows!=null&&module instanceof dev.pocketengine.media.MediaModule){
                    dev.pocketengine.media.MediaModule media=(dev.pocketengine.media.MediaModule)module;JSONObject result=null;
                    if(command.equals("prepareSubmission"))result=mediaWorkflows.prepareFromOwner(media,input.getString("submissionCommand"),input.getJSONObject("arguments"));
                    else if(command.equals("getPreparedSubmission"))result=mediaWorkflows.preparedFromOwner(media,input.getString("taskId"));
                    else if(dev.pocketengine.media.MediaWorkflowHost.isSubmission(command))result=mediaWorkflows.submitFromOwner(media,command,input);
                    else if(dev.pocketengine.media.MediaWorkflowHost.isOriginalCommand(command))result=mediaWorkflows.handleOriginal(media,command,input);
                    else if(command.equals("cancel")){
                        result=mediaWorkflows.cancelLocalWait(input.getString("operationId"));
                        if(result==null)result=json("operationId",input.getString("operationId"),"status","no_local_wait","localWaitCancelled",false,"providerStateConfirmed",false,"verified",false,"message","没有活动的本地等待；未声明远端已停止，请查询原操作");
                    }
                    if(result!=null){String id=result.optString("hostTaskId",result.optString("taskId"));if(!id.isEmpty())emitTask(id);reply(feature,command,requestId,true,result,null);sendState();return;}
                }
                if(feature.equals("display")&&phoneBusy.getAsBoolean())throw new IllegalStateException("手机任务持有后台屏资源，请先停止执行再检查、更改或释放后台助手");
                if(tasks!=null&&tracked(feature,command)){
                    if(feature.equals("ssh")){input.put("_resourceId",sshResource(module,input));requireSshCapturedBinding(module,input);}
                    String id=UUID.randomUUID().toString();
                    if(feature.equals("remote")&&command.equals("submitJob")&&input.has("operationId")){
                        id=input.getString("operationId");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("电脑首创操作身份需为UUID");
                    }
                    if(feature.equals("remote")&&input.has("operationId")){
                        String previous=tasks.operationTask(feature,input.getString("operationId"));
                        if(previous!=null)throw new IllegalStateException("该操作已登记，请按operationId核对结果，不能重复提交");
                    }
                    JSONObject admitted;
                    if(feature.equals("browser")&&!input.optString("taskId").isEmpty()){
                        id=input.getString("taskId");admitted=tasks.resumeExternalAttempt(id,feature);
                    }else admitted=tasks.beginExternal(feature,command,id,input,teamConversationId);
                    tracked=id;trackedRun=admitted.getString("runId");emitTask(id);
                    input.put("_operationId",trackedRun);
                    if(feature.equals("remote")||feature.equals("local")||feature.equals("ssh"))input.put("operationId",trackedRun);
                    input.put("_taskId",id).put("_runId",trackedRun);
                    if(feature.equals("ssh"))input.put("hostTaskId",id).put("principalId","owner").put("spaceId","private");
                    if(feature.equals("browser")){
                        input.put("taskId",id).put("runId",trackedRun).put("principalId","owner").put("spaceId","private");
                        if(!resources.acquireBrowser(trackedRun,System.currentTimeMillis()+600_000L))throw new SecurityException("手机正在执行，不能同时取得浏览器控制");
                        browserAdmission=trackedRun;
                    }
                }
                if(tasks!=null&&feature.equals("browser")&&!java.util.Set.of("acquire","open","status","downloads","download.status","download.cancel").contains(command)){
                    browserTask=input.getString("taskId");JSONObject task=tasks.get(browserTask,false);
                    if(task==null||!feature.equals(task.optString("feature"))||!task.optString("runId").equals(input.optString("runId")))throw new SecurityException("浏览器任务身份无效");
                    if(!command.matches("release|cancel|takeover")){
                        tasks.requireExternalRun(browserTask,input.getString("runId"),feature,true);
                        if(!resources.ownsBrowser(input.getString("runId")))throw new SecurityException("浏览器执行资源已到期或被手机占用，请重新接管任务");
                    }
                    if(command.matches("navigate|click|type|scroll|back|forward|reload"))operation=tasks.beginCapabilityOperation(browserTask,input.getString("runId"),feature,command,input);
                }
                if(tasks!=null&&feature.equals("ssh")&&command.matches("readPty|writePty|resizePty|closePty")){
                    browserTask=input.getString("hostTaskId");tasks.requireExternalRun(browserTask,input.getString("runId"),feature,command.matches("writePty|resizePty"));
                    if(command.matches("writePty|resizePty")){
                        JSONObject audit=json("terminalId",input.getString("terminalId"),"inputSubmitted",command.equals("writePty"));
                        if(command.equals("resizePty"))audit.put("cols",input.optInt("cols")).put("rows",input.optInt("rows"));
                        operation=tasks.beginCapabilityOperation(browserTask,input.getString("runId"),feature,command,audit);
                    }
                }
                String reviewedTeamTask=null;
                if(tasks!=null&&feature.equals("teams")&&command.matches("resume|continueMessages|reviseAssignment|continueOutcome")&&(input.has("expectedRevision")||input.has("expectedGeneration")))reviewedTeamTask=tasks.requireReviewedTeamControl(input);
                JSONObject result=module.handle(command,input);
                if(result==null)result=new JSONObject();
                if(tracked!=null){JSONObject receipt=new JSONObject(result.toString());if(feature.equals("browser")){if(!trackedRun.equals(result.optString("runId"))||!tracked.equals(result.optString("taskId"))||!result.optBoolean("active")||!resources.ownsBrowser(trackedRun))throw new SecurityException("浏览器没有返回本次执行的有效租约");tasks.requireExternalRun(tracked,trackedRun,feature,false);receipt.put("status","running");}updateTask(tracked,receipt);result.put("hostTaskId",tracked);}
                if(teamConversation!=null&&tracked!=null)result.put("conversationId",teamConversationId).put("taskId",tracked).put("conversationLinkStatus",appendTeamTaskLink(teamConversation,teamConversationId,tracked));
                if(operation!=null){result.put("hostOperationId",operation).put("hostTaskId",browserTask);tasks.finishCapabilityOperation(browserTask,operation,result);emitTask(browserTask);}
                if(browserTask!=null&&command.matches("release|cancel|takeover")){resources.releaseBrowser(input.getString("runId"));updateTask(browserTask,result);}
                if(tasks!=null&&feature.equals("ssh")&&command.matches("readPty|closePty|cancel|getOperation")){String id=findTask(feature,result,input);if(id!=null)updateTask(id,result);}
                if(tasks!=null&&((feature.equals("remote")&&command.matches("getJob|cancelJob|reconcileOperation|approveJob"))||(feature.equals("local")&&command.matches("getJob|cancelJob|termux.get|termux.cancel")))){String id=findTask(feature,result,input);if(id!=null)updateTask(id,result);}
                if(tasks!=null&&feature.equals("teams")&&command.matches("get|cancel|pause|resume|feedback|continueMessages|reviseAssignment|continueOutcome|sendMessage")){
                    if(reviewedTeamTask!=null){tasks.updateReviewedTeamControl(reviewedTeamTask,input,result);emitTask(reviewedTeamTask);}
                    else{String id=findTask(feature,result,input);if(id!=null)updateTask(id,result);}
                }
                if(tasks!=null&&feature.equals("plugins")&&command.equals("cancel")){String id=tasks.operationTask(feature,input.optString("operationId"));if(id!=null)updateTask(id,result);}
                reply(feature,command,requestId,true,result==null?new JSONObject():result,null);
                sendState();
            }catch(Exception error){
                if(feature.equals("media")&&tasks!=null){try{JSONObject input=request.optJSONObject("payload");String id=input==null?"":input.optString("taskId");if(id.isEmpty()&&input!=null&&!input.optString("operationId").isEmpty())id=tasks.operationTask("media",input.getString("operationId"));if(id!=null&&!id.isEmpty())emitTask(id);sendState();}catch(Exception unavailable){}}
                if(browserAdmission!=null)resources.releaseBrowser(browserAdmission);
                try{if(tracked!=null&&(!feature.equals("browser")||tasks.get(tracked,false).optString("status").matches("queued|running|preparing")))updateTask(tracked,failedAdmission(feature,trackedRun,error));if(operation!=null){tasks.finishCapabilityOperation(browserTask,operation,json("status","unknown","message","能力操作未取得确定回执，请核对实际结果"));emitTask(browserTask);}}catch(Exception persistence){events.emit(json("type","error","message","执行状态保存失败，请核对已有结果后再继续"));}
                reply(feature,command,requestId,false,null,safe(error));
            }
        };
        try{if(feature.equals("remote")&&java.util.Set.of("steerJob","getSteerMessage").contains(command))signals.execute(work);else if(isControl(command)&&!(feature.equals("media")&&command.equals("cancelJob")))controls.execute(work);else workers.execute(work);}catch(RejectedExecutionException busy){
            if(feature.equals("remote")&&command.equals("steerJob")){
                JSONObject input=request.optJSONObject("payload");reply(feature,command,requestId,true,remotePreflightRejection(input,"补充通道排队已满，本次尚未登记或发送；请稍后重新核对原任务"),null);
            }else reply(feature,command,requestId,false,null,"操作排队过多，请稍后重试");
        }
    }
    /** Native-only signal path; same TaskStore, no additional task/run or automatic resend. */
    private JSONObject remoteMessageRequest(EngineModule module,String command,JSONObject input)throws Exception{
        if(tasks==null)throw new SecurityException("缺少原生任务记录，不能向电脑补充");
        JSONObject binding;
        if(command.equals("getSteerMessage"))binding=tasks.remoteMessage(input.getString("taskId"),input.getString("messageId"));
        else{
            try{binding=tasks.prepareRemoteMessage(input);}
            catch(Exception rejected){
                // A colliding/stale request cannot overwrite an existing original message.
                return remotePreflightRejection(input,safe(rejected));
            }
            emitTask(binding.getString("taskId"));
            if(!binding.optBoolean("knownMessage")){
                try{binding=tasks.claimRemoteMessageDispatch(binding.getString("taskId"),binding.getString("messageId"));}
                catch(Exception denied){
                    JSONObject current=tasks.remoteMessage(binding.getString("taskId"),binding.getString("messageId"));
                    boolean unsent="message_prepared".equals(current.optString("journalState"));
                    JSONObject saved=tasks.recordRemoteMessage(remoteMessageReceipt(binding,unsent?"rejected":"unknown",unsent?Boolean.FALSE:JSONObject.NULL,
                            unsent?"原任务身份或turn已变化，发送前已拒绝，请重新核对":"本次补充的派发阶段尚未确认，只可查询原消息"));emitTask(saved.getString("taskId"));return saved;
                }
                emitTask(binding.getString("taskId"));
                return dispatchRemoteMessage(module,"steerJob",binding,input.getString("text"));
            }
        }
        return dispatchRemoteMessage(module,"getSteerMessage",binding,null);
    }
    /** Typed native queue consumer; it reuses the same original message dispatch/receipt. */
    public JSONObject sendRemoteDraftMessage(TaskStore.RemoteDraftDispatch claim)throws Exception {
        if(tasks==null)throw new SecurityException("缺少原任务账");EngineModule original=module("remote");if(!(original instanceof dev.pocketengine.features.remote.RemoteModule))throw new SecurityException("原远端模块不可用");
        ((dev.pocketengine.features.remote.RemoteModule)original).requireProfileBinding(claim.profileId(),claim.profileGeneration());JSONObject binding=tasks.claimRemoteDraftMessage(claim);binding.put("serviceBase",claim.serviceBase());emitTask(binding.getString("taskId"));
        return dispatchRemoteMessage(original,"steerJob",binding,claim.text(),claim.profileGeneration());
    }
    private JSONObject remotePreflightRejection(JSONObject input,String reason){
        if(input!=null&&tasks!=null)try{return tasks.remoteMessage(input.getString("taskId"),input.getString("messageId")).put("localError",reason).put("receiptSource","native_journal");}catch(Exception absent){}
        return json("taskId",input==null?"":input.optString("taskId"),"messageId",input==null?"":input.optString("messageId"),"status","rejected","dispatched",false,"receiptSource","native_preflight","message",reason);
    }
    private JSONObject dispatchRemoteMessage(EngineModule module,String command,JSONObject binding,String text)throws Exception{return dispatchRemoteMessage(module,command,binding,text,null);}
    private JSONObject dispatchRemoteMessage(EngineModule module,String command,JSONObject binding,String text,String profileGeneration)throws Exception{
        JSONObject target=new JSONObject();for(String key:new String[]{"profileId","jobId","jobOperationId","threadId","expectedTurnId","messageId"})target.put(key,binding.get(key));
        if(text!=null)target.put("text",text);if(profileGeneration!=null)target.put("_profileBinding",profileGeneration).put("serviceBase",binding.getString("serviceBase"));
        JSONObject saved;
        try{saved=tasks.recordRemoteMessage(module.handle(command,target));}
        catch(Exception unknown){saved=tasks.recordRemoteMessage(remoteMessageReceipt(binding,"unknown",JSONObject.NULL,"电脑补充回执尚未确认；仅查询原消息，不自动重发"));}
        emitTask(saved.getString("taskId"));return saved;
    }
    private static JSONObject remoteMessageReceipt(JSONObject binding,String status,Object dispatched,String message)throws Exception{
        JSONObject receipt=new JSONObject(binding.toString());receipt.remove("textHash");receipt.remove("textLength");receipt.remove("receipt");
        return receipt.put("operationId",binding.getString("messageId")).put("status",status).put("dispatched",dispatched).put("message",message);
    }
    private static boolean tracked(String feature,String command){return feature.equals("remote")&&command.equals("submitJob")||feature.equals("teams")&&command.equals("start")||feature.equals("plugins")&&command.equals("invoke")||feature.equals("browser")&&command.equals("acquire")||feature.equals("local")&&command.matches("run|termux.run|termux.prepare|termux.probe|termux.collect")||feature.equals("ssh")&&command.matches("writeFile|exec|openPty|listFiles|readFile|inspectFile|downloadFile|uploadFile");}
    private String appendTeamTaskLink(AssistantModule assistant,String conversationId,String taskId){
        try{assistant.appendTaskResult(conversationId,taskId,"团队任务已启动，可打开任务查看成员进度、讨论记录和结果。");return "saved";}
        catch(Exception failed){try{events.emit(json("type","error","severity","warning","code","conversation_task_link_failed","taskId",taskId,"conversationId",conversationId,"message","团队已启动，但聊天任务入口保存未确认；请在任务列表查看原任务，不要重复启动"));}catch(RuntimeException ignored){}return "unconfirmed";}
    }
    private static String sshResource(EngineModule module,JSONObject input)throws Exception{
        String id=input.getString("profileId");
        org.json.JSONArray profiles=module.snapshot().getJSONArray("profiles");
        for(int i=0;i<profiles.length();i++)if(id.equals(profiles.getJSONObject(i).optString("id"))){
            JSONObject profile=profiles.getJSONObject(i);String fingerprint=profile.optString("hostFingerprint");
            if(!profile.optBoolean("hostKeyTrusted")||fingerprint.isEmpty())throw new SecurityException("请先独立核对并确认SSH主机指纹");
            if(!input.has("_profileBinding"))throw new SecurityException("请在本机重新核对这次SSH连接选择");
            return dev.pocketengine.features.ssh.SshProfile.hash((fingerprint+"\n"+profile.getString("username")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("SSH连接不存在");
    }
    private static void requireSshCapturedBinding(EngineModule module,JSONObject input)throws Exception {
        org.json.JSONArray profiles=module.snapshot().getJSONArray("profiles");for(int i=0;i<profiles.length();i++){JSONObject profile=profiles.getJSONObject(i);if(input.getString("profileId").equals(profile.optString("id"))){if(!input.getString("_profileBinding").equals(profile.getString("bindingHash")))throw new SecurityException("已审阅SSH配置或凭据已变化，请重新核对；没有刷新旧授权");return;}}throw new SecurityException("SSH连接已移除");
    }
    public void recordEvent(JSONObject event){
        if(tasks==null||!"feature_event".equals(event.optString("type")))return;
        String feature=event.optString("feature");JSONObject payload=event.optJSONObject("payload");if(payload==null)return;
        if(feature.equals("browser")&&payload.optString("kind").matches("download|attachments"))return;
        try{
            if(feature.equals("memory")&&payload.optString("event").equals("memory_changed"))clearWorkingMemory(json("ownerId","owner","spaceId","private"));
            if(feature.equals("media")&&mediaWorkflows!=null){
                mediaWorkflows.recordEvent(event);String operation=payload.optString("operationId");
                if(!operation.isEmpty()){String id=tasks.operationTask("media",operation);if(id!=null){emitTask(id);EngineModule original=module("media");if(original instanceof dev.pocketengine.media.MediaModule)mediaWorkflows.startConfirmedReadContinuation((dev.pocketengine.media.MediaModule)original,id,operation);}}return;
            }
            if(feature.equals("remote")&&payload.optString("kind").equals("steer_message")){
                JSONObject saved=tasks.recordRemoteMessage(payload.getJSONObject("receipt"));emitTask(saved.getString("taskId"));return;
            }
            JSONObject receipt=null;for(String envelope:new String[]{"run","job","operation"})if(payload.optJSONObject(envelope)!=null){receipt=payload.getJSONObject(envelope);break;}
            if(receipt==null)receipt=payload;
            if(feature.equals("local")&&receipt.optJSONObject("projectExecution")!=null&&"native_host_operation".equals(receipt.getJSONObject("projectExecution").optString("authority"))){
                var host=projectHost;if(host==null)throw new SecurityException("原项目回执宿主尚未安装");host.recordBackendReceipt(receipt);emitTask(receipt.getJSONObject("projectExecution").getString("taskId"));return;
            }
            String id=findTask(feature,receipt,payload);
            if(id!=null&&receipt.has("status")){
                if(feature.equals("browser")&&receipt.optString("status").matches("taken_over|takeover|cancel|expired|release"))resources.releaseBrowser(receipt.optString("runId",payload.optString("runId")));
                updateTask(id,receipt);
            }
        }catch(Exception error){events.emit(json("type","error","message","能力执行回执保存失败，请核对已有结果"));}
    }
    private String findTask(String feature,JSONObject receipt,JSONObject envelope)throws Exception{
        if(feature.equals("remote")){
            String operation=receipt.optString("operationId",envelope.optString("operationId")),id=tasks.operationTask(feature,operation);
            if(id!=null)return id;
            return tasks.externalTask(feature,receipt.optString("id",envelope.optString("jobId")),receipt.optString("profileId",envelope.optString("profileId")),receipt.optString("serviceBase",envelope.optString("serviceBase")));
        }
        if(feature.equals("browser")||receipt.has("taskId")||envelope.has("taskId")){
            String taskId=receipt.optString("taskId",envelope.optString("taskId"));
            if(!taskId.isEmpty()){
                JSONObject task=tasks.get(taskId,false);String runId=receipt.optString("runId",envelope.optString("runId"));
                if(task!=null&&feature.equals(task.optString("feature"))&&!runId.isEmpty()&&runId.equals(task.optString("runId")))return taskId;
                return null;
            }
            return null;
        }
        String external=receipt.optString("id");if(external.isEmpty())external=envelope.optString("jobId",envelope.optString("runId"));
        String id=tasks.externalTask(feature,external);
        if(id==null)id=tasks.operationTask(feature,receipt.optString("operationId",envelope.optString("operationId",external)));
        return id;
    }
    private void emitPlanTasks(JSONObject result)throws Exception{
        JSONObject plan=result.optJSONObject("plan"),step=result.optJSONObject("commitment");
        if(step!=null&&!step.optString("taskId").isEmpty())emitTask(step.getString("taskId"));
        org.json.JSONArray steps=plan==null?null:plan.optJSONArray("steps");
        for(int i=0;steps!=null&&i<steps.length();i++){String id=steps.getJSONObject(i).optString("taskId");if(!id.isEmpty())emitTask(id);}
    }
    private void updateTask(String id,JSONObject receipt)throws Exception{tasks.updateExternal(id,receipt);emitTask(id);}
    private void emitTask(String id)throws Exception{JSONObject task=tasks.getForUi(id,false);if(task!=null)events.emit(json("type","task","task",task));}
    public void emitApproval(JSONObject approval){JSONObject event=json("type","capability_approval");try{java.util.Iterator<String> keys=approval.keys();while(keys.hasNext()){String key=keys.next();event.put(key,approval.get(key));}events.emit(event);}catch(Exception failure){events.emit(json("type","error","message","审批提示保存失败，请在原任务中查看"));}}
    public void sendState(){try{workers.execute(()->{
        JSONObject snapshots=new JSONObject();Map<String,EngineModule> copy;synchronized(this){copy=new LinkedHashMap<>(modules);}
        for(EngineModule module:copy.values())try{JSONObject actual=module.snapshot();if(module.id().equals("media")&&mediaWorkflows!=null)actual.put("hostReadContinuations",mediaWorkflows.continuationSnapshot());snapshots.put(module.id(),actual);}catch(Exception e){try{snapshots.put(module.id(),new JSONObject().put("available",false).put("message",safe(e)));}catch(Exception ignored){}}
        events.emit(json("type","features_state","features",snapshots));
    });}catch(RejectedExecutionException ignored){}}
    private void reply(String feature,String command,String requestId,boolean ok,JSONObject result,String message){JSONObject event=json("type","feature_result","feature",feature,"command",command,"requestId",requestId,"ok",ok);try{if(ok)event.put("result",feature.equals("ssh")&&command.equals("doctor")?dev.pocketengine.features.ssh.SshPrivacy.doctorMetadata(result):result);else event.put("message",message);}catch(Exception ignored){}events.emit(event);}
    private static JSONObject failedAdmission(String feature,String run,Exception error)throws Exception{
        JSONObject receipt=json("runId",run,"status",error instanceof IllegalArgumentException||error instanceof SecurityException?"failed":"unknown","message",safe(error));
        if(feature.equals("remote"))receipt.put("operationId",run); // Native admission identity, never an inferred remote reply.
        return receipt;
    }
    private static boolean isControl(String command){return command.equals("qq.stop")||command.equals("continueMessages")||command.equals("sendMessage")||command.equals("answerAssignment")||command.equals("pause")||command.equals("resume")||command.equals("feedback")||command.equals("cancel")||command.equals("adb.cancel")||command.equals("termux.cancel")||command.equals("download.cancel")||command.equals("weixin.stop")||command.equals("weixin.login.cancel")||command.equals("outbox.cancel")||command.equals("takeover")||command.equals("ask.cancel")||command.equals("cancelJob")||command.equals("release")||command.equals("reset")||command.equals("stop")||command.equals("server.stop");}
    private static String safe(Exception error){String value=error.getMessage();if(value==null||value.trim().isEmpty())return "操作未完成，请检查配置和连接状态";value=value.replaceAll("(?i)(Bearer\\s+)[^\\s]+","$1[隐藏]").replaceAll("(?i)((?:api[_-]?key|token|secret)=)[^&\\s]+","$1[隐藏]");return value.length()>400?value.substring(0,400):value;}
    private static JSONObject json(Object... pairs){JSONObject j=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)j.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}return j;}
    @Override public synchronized void close(){for(var control:ownerGitImportControls.values())control.cancel();ownerGitImportControls.clear();for(var control:ownerGitPublicationControls.values())control.cancel();ownerGitPublicationControls.clear();for(var control:ownerStorageControls.values())control.cancel();ownerStorageControls.clear();for(var control:ownerArchiveControls.values())control.cancel();ownerArchiveControls.clear();for(var session:workingMemory.keySet())releaseWorkingMemory(session);if(mediaWorkflows!=null)mediaWorkflows.close();for(EngineModule module:modules.values())try{module.close();}catch(RuntimeException ignored){}workers.shutdownNow();controls.shutdownNow();signals.shutdownNow();}
}
