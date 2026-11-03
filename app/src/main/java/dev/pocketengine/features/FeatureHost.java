package dev.pocketengine.features;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
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
import org.json.JSONArray;

/** One native entry point; callers from remote channels never receive this local-owner surface. */
public final class FeatureHost implements AutoCloseable {
    public interface MemorySynchronizer{void synchronize(JSONObject scope)throws Exception;}
    private volatile MemorySynchronizer memorySynchronizer;
    private volatile dev.pocketengine.features.skills.NativeSkillExecutionHost skillExecutionHost;
    /** Application-owned native execution bridge; feature/model JSON cannot register an executor. */
    public void setSkillExecutionHost(dev.pocketengine.features.skills.NativeSkillExecutionHost host){if(skillExecutionHost!=null)throw new IllegalStateException("脚本宿主已经注册");skillExecutionHost=java.util.Objects.requireNonNull(host);}
    public boolean hasSkillExecutionHost(){return skillExecutionHost!=null;}
    /** Owner workbench data only. Not reachable through feature/model commands or channels. */
    public JSONObject ownerConversationWorkbench(String command,JSONObject input)throws Exception {
        EngineModule selected=module("assistant");
        if(!(selected instanceof AssistantModule assistant))throw new IllegalStateException("原私人会话宿主不可用");
        return assistant.ownerConversationWorkbench(command,input);
    }
    public JSONObject executeSkillForTask(String task,String run,int epoch,JSONObject input,java.util.Set<String> capabilities,dev.pocketengine.core.RunControl control)throws Exception {
        var host=skillExecutionHost;if(host==null)throw new IllegalStateException("脚本执行宿主不可用");return host.execute(task,run,epoch,input,capabilities,control);
    }
    public JSONObject executeSkillForTask(String task,String run,int epoch,JSONObject input,java.util.Set<String> capabilities,dev.pocketengine.core.RunControl control,dev.pocketengine.core.CapabilitySnapshot.CurrentCheck current)throws Exception {
        return executeSkillForTask(task,run,epoch,input,capabilities,control,current,null);
    }
    public JSONObject executeSkillForTask(String task,String run,int epoch,JSONObject input,java.util.Set<String> capabilities,dev.pocketengine.core.RunControl control,dev.pocketengine.core.CapabilitySnapshot.CurrentCheck current,dev.pocketengine.features.controlplane.WorkProfileToolSelection.PreparedTool profile)throws Exception {
        var host=skillExecutionHost;if(host==null)throw new IllegalStateException("脚本执行宿主不可用");return host.execute(task,run,epoch,input,capabilities,control,current,profile);
    }
    public void setMemorySynchronizer(MemorySynchronizer synchronizer){memorySynchronizer=synchronizer;}
    public void synchronizeMemory(JSONObject scope)throws Exception{MemorySynchronizer synchronizer=memorySynchronizer;if(synchronizer!=null)synchronizer.synchronize(scope);}
    private final Map<String,EngineModule> modules=new LinkedHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(48));
    private final ExecutorService controls=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor signals=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final EventSink events;
    private final java.util.concurrent.ConcurrentHashMap<dev.pocketengine.features.memory.WorkingMemorySession,JSONObject> workingMemory=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<dev.pocketengine.features.memory.WorkingMemorySession,RunControl> workingMemoryControls=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong memoryContextRevision=new java.util.concurrent.atomic.AtomicLong();
    public void retainWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session,JSONObject scope)throws Exception{workingMemory.put(session,new JSONObject(scope.toString()));}
    public void retainWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session,JSONObject scope,RunControl original,long expectedPause)throws Exception {
        JSONObject captured=new JSONObject(scope.toString());
        synchronized(workingMemory){
            if(original==null||!original.hasHostRun()||!original.matchesHostRun(captured.getString("taskId"),captured.getString("runId"),captured.getInt("epoch"))||original.isCancelled()||original.isPaused()||original.pauseRevision()!=expectedPause){session.close();throw new SecurityException("原工作记忆执行已暂停、停止或变化");}
            workingMemoryControls.put(session,original);workingMemory.put(session,captured);
        }
    }
    public boolean ownsTaskStore(TaskStore expected){return expected!=null&&tasks==expected;}
    /** Only notes registered by this native control are scrubbed; unbound notes remain explicitly unknown. */
    public boolean clearOriginalRunWorkingMemory(String task,String run,int epoch,RunControl original,long pause){
        if(original==null||!original.matchesHostRun(task,run,epoch)||!original.isPaused()||original.pauseRevision()!=pause)return false;
        boolean complete=true;synchronized(workingMemory){
            if(!original.isPaused()||original.pauseRevision()!=pause)return false;
            for(var entry:workingMemory.entrySet()){
                JSONObject identity=entry.getValue();if(!task.equals(identity.optString("taskId")))continue;
                if(workingMemoryControls.get(entry.getKey())==original&&run.equals(identity.optString("runId"))&&epoch==identity.optInt("epoch"))releaseWorkingMemory(entry.getKey());
                else if(!identity.has("runId")||!identity.has("epoch"))complete=false;
            }
        }return complete;
    }
    public boolean hasWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session){return workingMemory.containsKey(session);}
    public void clearTaskWorkingMemory(String taskId){for(var entry:workingMemory.entrySet())if(taskId!=null&&taskId.equals(entry.getValue().optString("taskId"))){if(workingMemory.remove(entry.getKey(),entry.getValue())){workingMemoryControls.remove(entry.getKey());entry.getKey().close();}}}
    public void releaseWorkingMemory(dev.pocketengine.features.memory.WorkingMemorySession session){workingMemory.remove(session);workingMemoryControls.remove(session);session.close();}
    public long memoryContextRevision(){return memoryContextRevision.get();}
    /** Scrub ephemeral notes before source/forget cleanup returns; no TaskStore or model I/O here. */
    public void clearWorkingMemory(JSONObject scope){
        memoryContextRevision.incrementAndGet();for(var entry:workingMemory.entrySet())if(scope.optString("ownerId").equals(entry.getValue().optString("ownerId"))&&scope.optString("spaceId").equals(entry.getValue().optString("spaceId"))){if(workingMemory.remove(entry.getKey(),entry.getValue())){workingMemoryControls.remove(entry.getKey());entry.getKey().close();}}
    }
    private final TaskStore tasks;
    private final dev.pocketengine.media.MediaWorkflowHost mediaWorkflows;
    private volatile AutomationPlanHost automationPlans;
    private volatile dev.pocketengine.features.skills.NativeConversationSkillDraftHost conversationSkillDrafts;
    public void setConversationSkillDrafts(dev.pocketengine.features.skills.NativeConversationSkillDraftHost host){conversationSkillDrafts=java.util.Objects.requireNonNull(host);}
    public boolean hasConversationSkillDrafts(){return conversationSkillDrafts!=null;}
    /** Native owner review only; model callers use the inert original-task methods below. */
    public dev.pocketengine.features.skills.NativeConversationSkillDraftHost conversationSkillDraftsForOwner(){var host=conversationSkillDrafts;if(host==null)throw new IllegalStateException("原对话技能草稿宿主尚未就绪");return host;}
    public JSONObject conversationSkillSourcesForTask(String task,String run,int epoch,RunControl original)throws Exception{return conversationSkillDraftsForOwner().sourcesForTask(task,run,epoch,original);}
    public JSONObject proposeConversationSkillForTask(String task,String run,int epoch,String operation,JSONObject draft,RunControl original)throws Exception{return conversationSkillDraftsForOwner().proposeForTask(task,run,epoch,operation,draft,original);}
    private volatile dev.pocketengine.features.memory.NativeConversationMemoryDraftHost conversationMemoryDrafts;
    public void setConversationMemoryDrafts(dev.pocketengine.features.memory.NativeConversationMemoryDraftHost host){conversationMemoryDrafts=java.util.Objects.requireNonNull(host);}
    public boolean hasConversationMemoryDrafts(){return conversationMemoryDrafts!=null;}
    public dev.pocketengine.features.memory.NativeConversationMemoryDraftHost conversationMemoryDraftsForOwner(){var host=conversationMemoryDrafts;if(host==null)throw new IllegalStateException("原对话记忆草稿宿主尚未就绪");return host;}
    public JSONObject conversationMemorySourcesForTask(String task,String run,int epoch,RunControl original)throws Exception{return conversationMemoryDraftsForOwner().sourcesForTask(task,run,epoch,original);}
    public JSONObject proposeConversationMemoryForTask(String task,String run,int epoch,String operation,JSONObject draft,RunControl original)throws Exception{return conversationMemoryDraftsForOwner().proposeForTask(task,run,epoch,operation,draft,original);}
    private volatile dev.pocketengine.features.assistant.notifications.ImmediateTaskNotifications taskNotices;
    public void setTaskNotices(dev.pocketengine.features.assistant.notifications.ImmediateTaskNotifications notices){taskNotices=java.util.Objects.requireNonNull(notices);}
    public boolean hasTaskNotices(){return taskNotices!=null;}
    public JSONObject postTaskNotice(String task,String run,int epoch,String operation,JSONObject arguments,String approval,RunControl original)throws Exception {
        var notices=taskNotices;if(tasks==null||notices==null)throw new IllegalStateException("原任务通知宿主尚未就绪");
        try{return tasks.postTaskNoticeForHost(task,run,epoch,operation,arguments,approval,original,notices::post);}finally{emitTask(task);}
    }
    private volatile dev.pocketengine.features.local.project.TaskStoreProjectHost projectHost;
    public void setProjectHost(dev.pocketengine.features.local.project.TaskStoreProjectHost host){projectHost=java.util.Objects.requireNonNull(host);}
    private final java.util.concurrent.ConcurrentHashMap<String,RunControl> ownerLocalTerminalControls=new java.util.concurrent.ConcurrentHashMap<>();
    public RunControl localTerminalControl(String id){return id==null?null:ownerLocalTerminalControls.get(id);}
    public dev.pocketengine.features.local.project.OwnerLocalTerminal.Review reviewLocalTerminalForOwner(JSONObject input)throws Exception {
        if(tasks==null||projectHost==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原本机项目宿主未就绪");tasks.requireExecutionEnabled();return dev.pocketengine.features.local.project.OwnerLocalTerminal.review(local,input);
    }
    /** Called once by the native owner confirmation worker; the actual launcher is the existing host. */
    public JSONObject submitLocalTerminalForOwner(dev.pocketengine.features.local.project.OwnerLocalTerminal.Confirmed confirmed)throws Exception {
        if(tasks==null||projectHost==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原本机项目宿主未就绪");confirmed.requireCurrent(local);JSONObject admitted=tasks.beginOwnerLocalTerminal(confirmed);String id=confirmed.requestId();
        if(admitted.optBoolean("knownOperation"))return localTerminalForOwner(id,false).put("knownOperation",true);
        JSONObject plan=confirmed.plan(),binding=new JSONObject().put("taskId",id).put("runId",id).put("coordinatorEpoch",1).put("operationId",id),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.OwnerLocalTerminal.step(plan));RunControl original=new RunControl();
        try{
            original.bindHostRun(id,id,1);original.setAuthorityCheck(()->tasks.requireOwnerLocalTerminalCurrent(id,original));if(ownerLocalTerminalControls.putIfAbsent(id,original)!=null)throw new SecurityException("原终端已经持有控制器");
            confirmed.requireCurrent(local);projectHost.dispatch(binding,exact);
        }catch(dev.pocketengine.features.local.project.ProjectDevelopment.NotDispatched refused){tasks.ownerLocalTerminalPreflightRejected(id,refused.receipt);original.cancel();}
        catch(Exception uncertain){
            // A still-prepared row proves the original host never consumed its launch. Once
            // unknown was committed, only this original native process may be observed/stopped.
            tasks.ownerLocalTerminalPreflightRejected(id,new JSONObject().put("code","owner_terminal_preflight_rejected"));original.cancel();
            try{JSONObject observed=local.projectReceiptForHost(exact,binding,true);tasks.recordProjectOperation(binding,exact,observed);}catch(Exception unavailable){}
            if(tasks.hasUnresolved(id))tasks.endAttempt(id,id,"reconciling","原手动终端派发或输入结果待核对；只查询原编号，不重新启动",new JSONObject().put("nativeOwnerTerminal",true).put("operationId",id));
        }
        tasks.finishOwnerLocalTerminalFromReceipt(binding);finishLocalTerminalIfSettled(id);emitTask(id);sendState();return localTerminalForOwner(id,false).put("knownOperation",false);
    }
    public void finishLocalTerminalIfSettled(String id)throws Exception {
        JSONObject task=tasks.get(id,false);if(task==null||!task.has("ownerLocalTerminal")||!task.optString("status").matches("completed|failed|paused"))return;RunControl original=ownerLocalTerminalControls.remove(id);if(original!=null)original.cancel();
    }
    /** Query/stop uses only the original immutable plan and actual backend ref, including after restart. */
    public JSONObject localTerminalForOwner(String id,boolean cancel)throws Exception {
        JSONObject original=cancel?tasks.cancelOwnerLocalTerminalForOwner(id):tasks.ownerLocalTerminalOriginalForOwner(id),binding=original.getJSONObject("binding"),plan=original.getJSONObject("plan"),operation=original.getJSONObject("operation");
        if(cancel){RunControl control=ownerLocalTerminalControls.get(id);if(control!=null)control.cancel();}
        JSONObject receipt=operation.optJSONObject("receipt");if(receipt!=null&&receipt.optJSONObject("projectExecution")!=null){
            var local=(dev.pocketengine.features.local.LocalModule)module("local");JSONObject exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.OwnerLocalTerminal.step(plan));
            try{JSONObject backend=local.projectReceiptForHost(exact,receipt.getJSONObject("projectExecution"),cancel);tasks.recordProjectOperation(binding,exact,backend);}catch(Exception unavailable){/* An unknown original job never becomes a new launch. */}
        }else if(cancel&&"prepared".equals(operation.optString("state")))tasks.ownerLocalTerminalPreflightRejected(id,new JSONObject().put("code","owner_terminal_cancelled_before_dispatch"));
        tasks.finishOwnerLocalTerminalFromReceipt(binding);finishLocalTerminalIfSettled(id);JSONObject current=tasks.ownerLocalTerminalOriginalForOwner(id),task=current.getJSONObject("task"),row=current.getJSONObject("operation");
        RunControl live=ownerLocalTerminalControls.get(id);boolean input=live!=null&&!live.isCancelled()&&!live.isPaused()&&"project_running".equals(row.getString("state"))&&task.getJSONObject("ownerLocalTerminal").getLong("expiresAt")>System.currentTimeMillis();
        return new JSONObject().put("taskId",id).put("runId",id).put("epoch",1).put("executionId",id).put("status",task.getString("status")).put("operationState",row.getString("state")).put("scope",task.getJSONObject("ownerLocalTerminal")).put("receipt",row.optJSONObject("receipt")).put("inputAvailable",input).put("modelDispatched",false).put("replayAllowed",false).put("viewerOnlyClose",true);
    }
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
    private volatile dev.pocketengine.features.workspace.OwnerBackupWebDavHost ownerBackupUploads;
    /** Native settings only; neither feature JSON nor model tools expose backup credentials or uploads. */
    public synchronized dev.pocketengine.features.workspace.OwnerBackupWebDavHost ownerBackupWebDav(android.content.Context nativeOwner)throws Exception {
        if(ownerBackupUploads==null){if(tasks==null)throw new IllegalStateException("原任务账本不可用");android.content.Context app=nativeOwner.getApplicationContext();ownerBackupUploads=new dev.pocketengine.features.workspace.OwnerBackupWebDavHost(new java.io.File(app.getFilesDir(),"owner-backup-webdav"),ownerDataArchives(app),tasks,new dev.pocketengine.store.SecretVault(app),id->{try{emitTask(id);sendState();}catch(Exception unavailable){events.emit(json("type","error","message","原备份上传状态请在原编号核对"));}});}
        return ownerBackupUploads;
    }
    private volatile dev.pocketengine.features.workspace.OwnerDataArchives ownerArchives;
    private final java.util.concurrent.ConcurrentHashMap<String,dev.pocketengine.core.RunControl> ownerArchiveControls=new java.util.concurrent.ConcurrentHashMap<>();
    /** Native owner settings only; no request()/module/model command can obtain this host. */
    public synchronized dev.pocketengine.features.workspace.OwnerDataArchives ownerDataArchives(android.content.Context nativeOwner)throws Exception {
        if(ownerArchives!=null)return ownerArchives;if(tasks==null)throw new IllegalStateException("原任务账本不可用");var workspace=(dev.pocketengine.features.workspace.WorkspaceModule)module("workspace");var local=(dev.pocketengine.features.local.LocalModule)module("local");var attachments=(dev.pocketengine.features.attachments.AttachmentsModule)module("attachments");var backend=new java.util.LinkedHashMap<String,dev.pocketengine.features.workspace.OwnerDataArchives.SectionBackend>();
        if(module("memory") instanceof dev.pocketengine.features.memory.MemoryModule memory)backend.put("memory",new dev.pocketengine.features.workspace.OwnerDataArchives.SectionBackend(){
            private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
            private dev.pocketengine.features.memory.ProjectMemoryNamespaces projects(){return memory.projectNamespacesForOwner(local,workspace);}
            private boolean project(JSONObject section){return dev.pocketengine.features.memory.ProjectMemoryArchive.FORMAT.equals(section.optString("format"));}
            public JSONObject export(dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();return memory.ownerArchive(scope());}
            public JSONObject exportProject(dev.pocketengine.features.memory.ProjectMemoryNamespaces.Selection selected,dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();return projects().archiveForOwner(selected);}
            public void requireProjectSelection(dev.pocketengine.features.memory.ProjectMemoryNamespaces.Selection selected,dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();projects().requireArchiveSelectionForOwner(selected);}
            public dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview review(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{
                original.checkpoint();boolean project=project(section);if(project)dev.pocketengine.features.memory.ProjectMemoryArchive.checked(section);else dev.pocketengine.features.memory.MemoryArchive.checked(section,scope());
                return new dev.pocketengine.features.workspace.OwnerDataArchives.SectionReview(){
                    public JSONObject metadata()throws Exception{return new JSONObject().put("counts",section.getJSONObject("counts")).put("scopeMode",project?"project_only":"owner_private").put("originalSpaceId",section.getString("spaceId")).put("requiresSettledProjectMap",project).put("activeMemoryRestored",false).put("sourcePermissionsRestored",false).put("readGrantIssued",false).put("consentRestored",false);}
                    public JSONObject restore()throws Exception{if(project)throw new UnsupportedOperationException("项目档案仅沿原确认与已结算项目映射放置；未借用私人范围");original.checkpoint();JSONObject result=memory.restoreOwnerArchive(scope(),section,action);original.checkpoint();return result;}
                    public JSONObject restore(dev.pocketengine.features.workspace.OwnerDataArchives.Confirmed confirmed,dev.pocketengine.store.TaskStore.OwnerArchiveProjectMap map)throws Exception{if(!project)return restore();original.checkpoint();var placement=projects().prepareArchivePlacementForOwner(ownerArchives,confirmed,map,section);JSONObject result=projects().restoreArchiveForOwner(placement);original.checkpoint();return result;}
                };
            }
            public JSONObject query(JSONObject section,String action,dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();if(project(section))throw new UnsupportedOperationException("项目档案只按原包SHA查询，不能由JSON补造原映射");return memory.queryOwnerArchiveRestore(scope(),section.getString("sectionDigest"),action);}
            public JSONObject query(JSONObject section,String action,String archiveSha,dev.pocketengine.core.RunControl original)throws Exception{original.checkpoint();return project(section)?projects().queryArchiveRestoreForOwner(section,archiveSha,action):query(section,action,original);}
        });
        if(module("memory") instanceof dev.pocketengine.features.memory.MemoryModule memory)backend.put("experiences",new dev.pocketengine.features.workspace.OwnerExperienceArchive(tasks,memory));
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
    public dev.pocketengine.features.local.project.ProjectOwnerTerminal.Session openProjectExecutionForOwner(String task,String execution,dev.pocketengine.core.RunControl observation)throws Exception{
        var host=projectHost;if(host==null)throw new IllegalStateException("原项目宿主尚未挂载");return host.ownerTerminals().openObservation(task,execution,observation);
    }
    public void setAutomationPlanHost(AutomationPlanHost plans){automationPlans=java.util.Objects.requireNonNull(plans);}
    public JSONObject proposeScheduleForTask(String task,String run,int epoch,String operation,JSONObject draft,RunControl original)throws Exception {if(automationPlans==null||!(module("automation") instanceof dev.pocketengine.features.automation.AutomationModule selected))throw new SecurityException("原计划宿主尚未挂载");return automationPlans.proposeScheduleForTask(selected,task,run,epoch,operation,draft,original);}
    public JSONObject scheduleProposalsForTask(String task,String run,int epoch,RunControl original)throws Exception {if(automationPlans==null||!(module("automation") instanceof dev.pocketengine.features.automation.AutomationModule selected))throw new SecurityException("原计划宿主尚未挂载");return automationPlans.scheduleProposalsForTask(selected,task,run,epoch,original);}
    public JSONObject mediaReadContinuationStatus()throws Exception{return mediaWorkflows==null?new JSONObject():mediaWorkflows.continuationSnapshot();}
    public dev.pocketengine.core.RunControl mediaControl(String taskId){return mediaWorkflows==null?null:mediaWorkflows.currentControl(taskId);}
    public boolean ownsMediaTask(String taskId)throws Exception{return mediaWorkflows!=null&&mediaWorkflows.ownsTask(taskId);}
    public JSONObject cancelMediaTask(String taskId)throws Exception{return mediaWorkflows==null?null:mediaWorkflows.cancelTask(taskId);}
    public JSONObject cancelOriginalMediaTask(String taskId,String run,int epoch)throws Exception{return mediaWorkflows==null?null:mediaWorkflows.cancelOriginalTask(taskId,run,epoch);}
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

    private final java.util.concurrent.ConcurrentHashMap<String,dev.pocketengine.core.RunControl> ownerMcpDiscoveryControls=new java.util.concurrent.ConcurrentHashMap<>();
    /** Minted only in this original workers.execute rejection catch, never from exception/JSON input. */
    public static final class McpDiscoveryQueueRejected {
        private final dev.pocketengine.features.plugins.McpLocalDiscovery.Confirmed selected;private final java.util.concurrent.atomic.AtomicInteger submission;
        private McpDiscoveryQueueRejected(FeatureHost owner,dev.pocketengine.features.plugins.McpLocalDiscovery.Confirmed selected,java.util.concurrent.atomic.AtomicInteger submission,Runnable rejectedTask,java.util.concurrent.RejectedExecutionException actual){java.util.Objects.requireNonNull(actual);if(owner.workers.getRejectedExecutionHandler().getClass()!=ThreadPoolExecutor.AbortPolicy.class||owner.workers.getQueue().contains(rejectedTask)||!submission.compareAndSet(0,2))throw new SecurityException("原MCP队列拒绝/未开始合同不明，不能证明未派发");this.selected=selected;this.submission=submission;}
        public boolean matches(dev.pocketengine.features.plugins.McpLocalDiscovery.Confirmed actual){return selected==actual&&submission.get()==2;}
    }
    /** Original finite native worker only: explicit one-use owner management, zero model calls. */
    public JSONObject submitMcpDiscoveryForOwner(dev.pocketengine.features.plugins.McpLocalDiscovery.Confirmed confirmed)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local)||!confirmed.runtimeMatchesForOwner(local.managedRuntimeForOwner()))throw new SecurityException("原本机MCP与任务宿主不匹配");
        String id=confirmed.actionId();var original=new dev.pocketengine.core.RunControl();JSONObject admitted=tasks.beginOwnerMcpDiscoveryForOwner(confirmed,original);if(admitted.optBoolean("knownOperation"))return observeMcpDiscoveryForOwner(id,false);ownerMcpDiscoveryControls.put(id,original);var submission=new java.util.concurrent.atomic.AtomicInteger();
        Runnable pending=()->{if(!submission.compareAndSet(0,1))return;try{dev.pocketengine.features.plugins.McpLocalDiscoveryPipe.run(confirmed,tasks,original);}
            catch(dev.pocketengine.features.plugins.McpLocalDiscoveryPipe.NotDispatched refused){try{tasks.recordOwnerMcpDiscoveryNotDispatchedForOwner(confirmed,refused);}catch(Exception unrecorded){}}
            catch(Exception uncertain){try{if(!tasks.ownerMcpDiscoveryForOwner(id).getString("status").matches("completed|failed"))tasks.status(id,"reconciling","原本机MCP执行/清场尚未完整核对；保留原操作，只查询不重放");}catch(Exception unrecorded){}}
            finally{ownerMcpDiscoveryControls.remove(id,original);original.cancel();confirmed.close();try{emitTask(id);sendState();}catch(Exception ignored){}}};
        try{workers.execute(pending);}
        catch(java.util.concurrent.RejectedExecutionException rejected){original.cancel();McpDiscoveryQueueRejected proof=null;try{proof=new McpDiscoveryQueueRejected(this,confirmed,submission,pending,rejected);}catch(SecurityException uncertain){}if(proof!=null){try{tasks.recordOwnerMcpDiscoveryQueueRejectedForOwner(confirmed,proof);}finally{ownerMcpDiscoveryControls.remove(id,original);confirmed.close();}}else tasks.status(id,"reconciling","原MCP提交结果不确定；不能将已进入worker的动作当作未派发");try{emitTask(id);sendState();}catch(Exception ignored){}throw new IllegalStateException("原本机MCP工作队列未确认接受，请只核对原操作："+id,rejected);}
        emitTask(id);sendState();return dev.pocketengine.features.plugins.McpLocalDiscoveryPipe.publicView(tasks.ownerMcpDiscoveryForOwner(id));
    }
    /** Native owner observes/cancels an original SQL ref only; no confirmation/pipe is reconstructed. */
    public JSONObject observeMcpDiscoveryForOwner(String id,boolean cancel)throws Exception {
        if(tasks==null)throw new SecurityException("原任务宿主不可用");JSONObject row=cancel?tasks.cancelOwnerMcpDiscoveryForOwner(id):tasks.ownerMcpDiscoveryForOwner(id);var original=ownerMcpDiscoveryControls.get(id);if(cancel&&original!=null)original.cancel();
        if(original!=null)return dev.pocketengine.features.plugins.McpLocalDiscoveryPipe.publicView(row).put("observation","original_native_control_retained");
        if(row.optJSONObject("nativeJob")!=null&&module("local") instanceof dev.pocketengine.features.local.LocalModule local&&local.managedRuntimeForOwner()!=null){try{JSONObject job=row.getJSONObject("nativeJob");var ref=dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(job.getJSONObject("exact"),job.getJSONObject("binding"));var runtime=local.managedRuntimeForOwner();var observed=cancel?runtime.requestCancel(ref):runtime.query(ref);tasks.recordOwnerMcpDiscoveryForHost(dev.pocketengine.features.plugins.McpLocalDiscovery.observeForHost(row.getJSONObject("scope"),observed));row=tasks.ownerMcpDiscoveryForOwner(id);}catch(Exception unavailable){return dev.pocketengine.features.plugins.McpLocalDiscoveryPipe.publicView(row).put("observation","original_runtime_receipt_unavailable_unknown");}}
        return dev.pocketengine.features.plugins.McpLocalDiscoveryPipe.publicView(row).put("observation",row.optJSONObject("nativeJob")==null?"original_intent_without_runtime_ref":"original_runtime_ref_observed");
    }
    public boolean ownsMcpDiscoveryTask(String id)throws Exception{JSONObject task=tasks==null?null:tasks.get(id,false);return task!=null&&task.has("ownerMcpDiscovery");}
    /** Explicit native owner log read, including stopped/cold original jobs; never dispatched again. */
    public JSONObject localMcpDiagnosticForOwner(String taskId,long cursor,int maxBytes)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local)||local.managedRuntimeForOwner()==null)throw new SecurityException("原本机MCP日志宿主不可用");
        return dev.pocketengine.features.plugins.McpLocalDiagnostics.readDiscovery(tasks,local.managedRuntimeForOwner(),taskId,cursor,maxBytes);
    }

    /** Existing supervisor/Runner worker only, after its concrete original write approval. */
    public JSONObject invokeLocalMcpForTask(String task,String run,int epoch,String hostTool,String operation,JSONObject arguments,JSONObject nativeInput,JSONObject capability,String consumedApproval,dev.pocketengine.core.RunControl original,dev.pocketengine.features.memory.ToolOutputCapture.Factory output)throws Exception {
        if(tasks==null||!(module("plugins") instanceof dev.pocketengine.features.plugins.PluginsModule plugins)||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new SecurityException("原本机MCP任务/程序宿主不可用");
        try(var program=plugins.localMcpProgramForTask(nativeInput,hostTool,original)){var admitted=tasks.admitLocalMcpInvocationForHost(task,run,epoch,hostTool,operation,arguments,capability,consumedApproval,program,original);try{return dev.pocketengine.features.plugins.McpLocalTaskPipe.run(admitted,tasks,local,output);}catch(dev.pocketengine.features.plugins.McpLocalTaskPipe.NotDispatched refused){tasks.recordLocalMcpNotDispatchedForHost(admitted,refused);if(refused.getCause() instanceof dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable blocked)throw blocked;return tasks.nativeMcpPreentryReceiptForHost(admitted);}}
    }
    /** Same original bound refs only, including cold cancellation/late cleanup; no handle/launch. */
    public JSONObject observeLocalMcpInvocationsForOwner(String task,boolean cancel)throws Exception {return observeLocalMcpInvocationsForOwner(task,cancel,null);}
    private JSONObject observeLocalMcpInvocationsForOwner(String task,boolean cancel,String expectedRun)throws Exception {
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local)||local.managedRuntimeForOwner()==null)return new JSONObject().put("taskId",task).put("observationAvailable",false);var runtime=local.managedRuntimeForOwner();JSONArray observed=new JSONArray();for(var record:tasks.localMcpInvocationsForOwner(task)){if(expectedRun!=null&&!expectedRun.equals(record.runId()))continue;JSONObject prior=record.publicReceiptForHost();try{boolean pending=prior==null||prior.optJSONObject("nativeMcp")==null||!prior.getJSONObject("nativeMcp").optBoolean("processSettled");if(cancel&&pending)tasks.requestCancel(task,record.runId());var actual=cancel&&pending?runtime.requestCancel(record.referenceForHost()):runtime.query(record.referenceForHost());tasks.recordLocalMcpInvocationForHost(record,dev.pocketengine.features.plugins.McpLocalInvocation.observeForHost(record.scopeForHost(),actual));observed.put(record.publicReceiptForHost());}catch(Exception unknown){observed.put(new JSONObject().put("operationId",record.operationId()).put("status","unknown").put("originalProcessReceiptUnavailable",true).put("replayAllowed",false));}}
        return new JSONObject().put("taskId",task).put("operations",observed).put("replayAllowed",false).put("queryOnly",true);
    }

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
        if(tasks==null||!(module("local") instanceof dev.pocketengine.features.local.LocalModule local))throw new IllegalStateException("原Git与任务宿主不可用");JSONObject row=cancel?tasks.cancelOwnerGitImportForOwner(id):tasks.ownerGitImportForOwner(id);if(cancel){var control=ownerGitImportControls.get(id);if(control!=null)control.cancel();}if(row.optBoolean("sourceRetired")){JSONObject historical=row.optJSONObject("receipt");if(historical==null)throw new SecurityException("原缓存撤回缺少终态凭证");return new JSONObject(historical.toString()).put("taskId",id).put("originalTaskStatus",row.getString("status")).put("sourceAvailable",false).put("sourceRetired",true).put("code","git_intake_cache_retired_by_owner");}if(row.optJSONObject("nativeJob")==null)return row.put("observation","original_intent_without_readable_native_reference");var observed=local.gitImportsForOwner().observe(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),cancel);if(observed.hasOriginalProcessReceipt()){tasks.recordOwnerGitImportForOwner(id,observed);emitTask(id);sendState();}JSONObject actual=observed.metadata();if("completed".equals(row.getString("status"))&&!"completed".equals(actual.getString("status"))){JSONObject historical=row.optJSONObject("receipt");if(historical==null)throw new SecurityException("原Git完成凭证缺失，不能从缓存猜测成功");actual=new JSONObject(historical.toString()).put("sourceAvailable",false).put("code","git_intake_source_no_longer_available").put("historicalCompletionPreserved",true);}return actual.put("taskId",id).put("originalTaskStatus",tasks.ownerGitImportForOwner(id).getString("status"));
    }
    public boolean ownsGitImportTask(String id)throws Exception{JSONObject task=tasks==null?null:tasks.get(id,false);return task!=null&&(task.has("ownerGitImport")||task.has("ownerGitPublication"));}
    public dev.pocketengine.features.local.project.ProjectGitImport.Result gitImportResultForOwner(String id,dev.pocketengine.core.RunControl observation)throws Exception{JSONObject row=tasks.ownerGitImportForOwner(id);if(row.optBoolean("sourceRetired")||!"completed".equals(row.getString("status")))throw new SecurityException("请先取得未撤回的原Git读取与退出结果，再审阅发布");return ((dev.pocketengine.features.local.LocalModule)module("local")).gitImportsForOwner().result(row.getJSONObject("scope"),row.getJSONObject("nativeJob"),observation);}

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
    public synchronized void register(EngineModule module){if(modules.containsKey(module.id()))throw new IllegalArgumentException("重复模块");modules.put(module.id(),module);if(tasks!=null&&module instanceof dev.pocketengine.features.local.LocalModule local)local.bindNativeExecutionCachesForHost(tasks);mountTeamWrites();mountTeamProjects();}
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
    /** Original parent native read receipts are source metadata, never a second permission store. */
    public void checkTeamResearchParentInputs(String task,String run,int epoch,dev.pocketengine.core.RunControl original)throws Exception {
        if(Thread.holdsLock(tasks))throw new IllegalStateException("研究材料/Book复核不得在Tasks锁内");
        if(!tasks.hasTeamResearchParentInputs(task))return;
        var source=tasks.teamParentOrigin(task,run,epoch,"teams_get","",new JSONObject(),original);org.json.JSONArray refs=tasks.teamResearchParentInputReferences(source);EngineModule selected=module("teams");
        if(!(selected instanceof dev.pocketengine.features.teams.TeamsModule teams))throw new SecurityException("原团队读取宿主不可用，不发送旧来源正文");teams.checkParentResearchInputs(source,refs);source.checkCurrent();
    }
    public JSONObject startTeamForTask(String parent,String parentRun,String operationId,JSONObject args,JSONObject expectedDefinition,dev.pocketengine.core.RunControl control)throws Exception{return startTeamForTask(parent,parentRun,operationId,args,expectedDefinition,control,"");}
    public JSONObject startTeamForTask(String parent,String parentRun,String operationId,JSONObject args,JSONObject expectedDefinition,dev.pocketengine.core.RunControl control,String consumedApprovalId)throws Exception{
        if(tasks==null)throw new IllegalStateException("宿主任务存储不可用");EngineModule module=module("teams");if(module==null)throw new IllegalStateException("团队模块不可用");
        control.check();JSONObject link=tasks.beginRelatedExternal(parent,parentRun,"teams","start",operationId,args);emitTask(parent);emitTask(operationId);
        if(link.getBoolean("knownOperation"))return module.handle("get",json("runId",operationId)).put("hostTaskId",operationId).put("taskId",operationId).put("parentTaskId",parent);
        boolean researchStartEntered=false;
        try{control.check();JSONObject result;synchronized(module){if(!TaskStore.capabilityFingerprint(expectedDefinition).equals(TaskStore.capabilityFingerprint(teamDefinition(args.getString("teamId")))))throw new SecurityException("团队成员或执行位置已变化，请重新确认");JSONObject nativeInput=new JSONObject(args.toString()).put("_taskId",operationId).put("_runId",operationId).put("_operationId",operationId);
            if(expectedDefinition.optString("mode").equals("produce")){
                EngineModule workspace=module("workspace");JSONObject original=tasks.get(parent,false);
                if(!(module instanceof dev.pocketengine.features.teams.TeamsModule)||workspace==null||original==null||!control.matchesHostRun(parent,parentRun,original.getInt("coordinatorEpoch")))throw new SecurityException("文件协作缺少原父任务项目与执行控制");
                var scope=((dev.pocketengine.features.teams.TeamsModule)module).captureWriteScope(parent,parentRun,original.getJSONObject("developmentContext").getString("projectId"),expectedDefinition);
                tasks.bindTeamWriteScope(operationId,scope);control.check();result=((dev.pocketengine.features.teams.TeamsModule)module).startNative(nativeInput,scope);
            }else {
                dev.pocketengine.store.TaskStore.TeamParentOrigin researchSource=null;
                if(module instanceof dev.pocketengine.features.teams.TeamsModule selected&&selected.continuousResearchConfigured()){
                    JSONObject owner=tasks.get(parent,false);researchSource=tasks.teamParentOrigin(parent,parentRun,owner.getInt("coordinatorEpoch"),"teams_start",operationId,args,control);tasks.bindTeamResearchParentStart(researchSource,consumedApprovalId,expectedDefinition);
                }
                researchStartEntered=researchSource!=null;
                if(researchSource!=null){
                    var originalStart=((dev.pocketengine.features.teams.TeamsModule)module).startOriginalResearch(nativeInput);result=originalStart.accepted();
                    control.onCancel(()->nativeOwnerCleanup(()->{try{originalStart.cancel();}catch(Exception unavailable){events.emit(json("type","error","message","原研究团队停止尚未确认，请核对原任务"));}}));
                    tasks.completeTeamResearchParentStart(researchSource);
                }else result=module.handle("start",nativeInput);
            }}updateTask(operationId,result);return result.put("hostTaskId",operationId).put("taskId",operationId).put("parentTaskId",parent);}
        catch(Exception failure){updateTask(operationId,json("status",researchStartEntered?"unknown":failure instanceof IllegalArgumentException||failure instanceof SecurityException?"failed":"unknown","message",safe(failure)));throw failure;}
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
        if(tasks==null)return;try{JSONObject task=tasks.get(parent,false);if(task!=null)cancelRelatedTasks(parent,task.optString("runId"),task.optInt("coordinatorEpoch"));}catch(Exception unknown){events.emit(json("type","error","message","原子任务停止身份尚未确认"));}
    }
    /** Async callers pass the run they already own; never resolve a successor by task ID. */
    public void cancelRelatedTasks(String parent,String expectedRun,int expectedEpoch){
        if(tasks==null)return;
        try{
            JSONObject task=tasks.get(parent,false);
            if(task==null||!expectedRun.equals(task.optString("runId"))||expectedEpoch!=task.optInt("coordinatorEpoch"))return;
            if(tasks.hasLocalMcpInvocationsForHost(parent))observeLocalMcpInvocationsForOwner(parent,true,expectedRun);
            JSONArray links=task.optJSONArray("relatedTasks");
            for(int i=0;links!=null&&i<links.length();i++){
                String id=links.getJSONObject(i).getString("taskId");JSONObject child=tasks.get(id,false);
                if(child==null||!child.optString("status").matches("preparing|queued|running|paused|awaiting_user|stopping"))continue;
                if(!parent.equals(child.optString("parentTaskId"))||!expectedRun.equals(child.optString("parentRunId")))continue;
                String childRun=child.getString("runId");int childEpoch=child.getInt("coordinatorEpoch");
                if("teams".equals(child.optString("feature"))){
                    EngineModule selected=module("teams");if(!(selected instanceof dev.pocketengine.features.teams.TeamsModule team))continue;
                    var original=team.originalCancellation(childRun);
                    controls.execute(()->{try{
                        if(module("teams")!=selected)throw new SecurityException("原团队宿主已变化");
                        original.cancel(()->tasks.requestOriginalRelatedCancel(parent,expectedRun,expectedEpoch,id,childRun,childEpoch));
                    }catch(Exception unavailable){events.emit(json("type","error","message","原团队停止尚未确认，未按编号停止其他代次"));}});
                }else if("remote".equals(child.optString("feature"))&&!child.optString("externalId").isEmpty()){
                    EngineModule selected=module("remote");if(!(selected instanceof dev.pocketengine.features.remote.RemoteModule remote))continue;
                    dev.pocketengine.store.OriginalRemoteStopClaim claim;
                    synchronized(tasks){
                        JSONObject target=tasks.originalRemoteChildStopTarget(parent,expectedRun,id);
                        tasks.requestOriginalRelatedCancel(parent,expectedRun,expectedEpoch,id,childRun,childEpoch);
                        claim=tasks.claimOriginalRemoteStop(id,childRun,childEpoch,target.getString("_profileBinding"));
                    }
                    controls.execute(()->{try{
                        if(module("remote")!=selected)throw new SecurityException("原电脑宿主已变化");
                        remote.cancelOriginalPinnedJob(claim);emitTask(id);
                    }catch(Exception unknown){events.emit(json("type","error","message","原电脑子任务停止结果尚未确认；没有停止新执行，请查询原任务"));}});
                    cancelRelatedTasks(id,childRun,childEpoch);
                }
            }
        }catch(Exception error){events.emit(json("type","error","message","子任务停止状态尚未确认，请查看原任务"));}
    }
    /** Freeze existing native team controllers before recording the parent stop. No ID is resolved later. */
    public Runnable prepareOriginalPhoneTeamStops(String parent,String run,int epoch)throws Exception {
        JSONObject task=tasks.get(parent,false);
        if(task==null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("原父执行已变化");
        EngineModule selected=module("teams");java.util.ArrayList<Runnable> captured=new java.util.ArrayList<>();
        if(selected instanceof dev.pocketengine.features.teams.TeamsModule team){
            JSONArray links=task.optJSONArray("relatedTasks");
            if(links!=null&&links.length()>100)throw new SecurityException("原团队关联范围尚未完整核对");
            for(int i=0;links!=null&&i<links.length();i++){
                JSONObject link=links.getJSONObject(i);String id=link.getString("taskId");JSONObject child=tasks.get(id,false);
                if(child==null||!"teams".equals(child.optString("feature"))||!parent.equals(child.optString("parentTaskId"))||!run.equals(child.optString("parentRunId")))continue;
                String childRun=child.getString("runId");int childEpoch=child.getInt("coordinatorEpoch");
                if(!childRun.equals(link.optString("runId")))throw new SecurityException("原团队关联执行已变化");
                var original=team.originalCancellation(childRun);
                captured.add(()->{try{if(module("teams")!=selected)throw new SecurityException("原团队宿主已变化");original.cancel(()->tasks.requestOriginalRelatedCancel(parent,run,epoch,id,childRun,childEpoch));}catch(Exception unavailable){events.emit(json("type","error","message","原团队停止尚未确认，未按编号停止后继执行"));}});
            }
        }
        return ()->controls.execute(()->captured.forEach(Runnable::run));
    }
    /** Native stop receipt only. Each remote POST consumes its original SQL claim exactly once. */
    public void cancelOriginalPhoneChildren(TaskStore.PhoneStopBatch batch)throws Exception {
        if(tasks==null||batch==null||!batch.newlyCreated())return;
        EngineModule selected=module("remote");java.util.List<String> ids=batch.remoteChildIds();
        if(ids.isEmpty())return;
        if(!(selected instanceof dev.pocketengine.features.remote.RemoteModule remote))throw new SecurityException("原电脑停止宿主不可用");
        controls.execute(()->{for(String child:ids)try{
            if(module("remote")!=remote)throw new SecurityException("原电脑停止宿主已变化");
            var claim=tasks.claimOriginalRemoteChild(batch,child);remote.cancelOriginalPinnedJob(claim);emitTask(child);
        }catch(Exception unknown){events.emit(json("type","error","message","原电脑子任务停止尚未确认；保留原操作，不重新派发"));}});
    }
    /** The global owner stop queues one frozen original job, never a later task/profile lookup. */
    public void cancelOriginalRemoteForOwnerStop(JSONObject target,JSONObject source)throws Exception {
        JSONObject original=new JSONObject(target.toString()),input=new JSONObject(source.toString());
        EngineModule selected=module("remote");
        if(!(selected instanceof dev.pocketengine.features.remote.RemoteModule remote))throw new SecurityException("原远端停止宿主不可用");
        dev.pocketengine.store.OriginalRemoteStopClaim claim;
        synchronized(tasks){
            tasks.requireOriginalExecutionStopTarget(original);
            String id=original.getString("taskId");JSONObject current=tasks.remoteOwnerTarget(id);
            for(String key:new String[]{"profileId","serviceBase","jobId","jobOperationId"})
                if(!current.getString(key).equals(input.getString(key)))throw new SecurityException("原远端停止目标已变化");
            claim=tasks.claimOriginalRemoteStop(id,original.getString("runId"),original.getInt("epoch"),input.getString("_profileBinding"));
        }
        controls.execute(()->{try{
            if(module("remote")!=remote)throw new SecurityException("原远端停止宿主已变化");
            remote.cancelOriginalPinnedJob(claim);emitTask(original.getString("taskId"));
        }catch(Exception unknown){events.emit(json("type","error","message","原电脑停止结果尚未确认；请只核对原操作，不重新提交"));}});
    }
    private JSONObject cancelOriginalRemoteTask(String id,String run,int epoch,String generation)throws Exception {
        EngineModule selected=module("remote");if(tasks==null||!(selected instanceof dev.pocketengine.features.remote.RemoteModule remote))throw new SecurityException("没有原电脑任务的原生停止宿主");
        JSONObject task=tasks.get(id,false);if(task==null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch",-1))throw new SecurityException("原电脑执行已变化");
        String profile=task.getJSONObject("externalRefs").getString("profileId");
        var claim=tasks.claimOriginalRemoteStop(id,run,epoch,generation==null?remote.profileBindingHash(profile):generation);JSONObject actual=remote.cancelOriginalPinnedJob(claim);emitTask(id);return actual;
    }
    private JSONObject cancelOriginalRemoteRequest(JSONObject input)throws Exception {
        String id=tasks.externalTask("remote",input.getString("jobId"),input.getString("profileId"),input.optString("serviceBase"));
        if(id==null)throw new SecurityException("没有唯一的原SQL电脑任务；未发送停止");JSONObject task=tasks.get(id,false);
        if(!input.getString("jobId").equals(task.optString("externalId"))||!input.getString("jobOperationId").equals(task.optString("operationId")))throw new SecurityException("停止请求不是原电脑job/operation；不会改停当前新执行");
        String run=input.has("runId")?input.getString("runId"):task.getString("runId");int epoch=input.has("coordinatorEpoch")?input.getInt("coordinatorEpoch"):task.getInt("coordinatorEpoch");
        return cancelOriginalRemoteTask(id,run,epoch,input.has("_profileBinding")?input.getString("_profileBinding"):null);
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
                    dev.pocketengine.features.memory.ProjectMemoryNamespaces.rejectNamespaceArguments(input);
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
                JSONObject result=tasks!=null&&feature.equals("remote")&&command.equals("cancelJob")?cancelOriginalRemoteRequest(input):module.handle(command,input);
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
        JSONObject target=new JSONObject();for(String key:new String[]{"profileId","jobId","jobOperationId","threadId","messageId"})target.put(key,binding.get(key));
        dev.pocketengine.features.remote.RemoteMessageTarget.copy(binding,target);
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
    @Override public synchronized void close(){for(var original:ownerLocalTerminalControls.values())original.cancel();ownerLocalTerminalControls.clear();if(ownerBackupUploads!=null)ownerBackupUploads.close();for(var control:ownerMcpDiscoveryControls.values())control.cancel();ownerMcpDiscoveryControls.clear();for(var control:ownerGitImportControls.values())control.cancel();ownerGitImportControls.clear();for(var control:ownerGitPublicationControls.values())control.cancel();ownerGitPublicationControls.clear();for(var control:ownerStorageControls.values())control.cancel();ownerStorageControls.clear();for(var control:ownerArchiveControls.values())control.cancel();ownerArchiveControls.clear();for(var session:workingMemory.keySet())releaseWorkingMemory(session);if(mediaWorkflows!=null)mediaWorkflows.close();for(EngineModule module:modules.values())try{module.close();}catch(RuntimeException ignored){}workers.shutdownNow();controls.shutdownNow();signals.shutdownNow();}
}
