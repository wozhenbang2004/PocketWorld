package dev.pocketengine.media;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.media.jobs.ComfyUiTransport;
import dev.pocketengine.media.jobs.MediaJobJournal;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner media admission and original-operation recovery. No model, scheduler or task database. */
public final class MediaWorkflowHost implements AutoCloseable {
    private static final Set<String> SUBMIT=Set.of("submitWorkflow","generateImage","generateVideo","editImage","synthesizeSpeech");
    private static final Set<String> ORIGINAL=Set.of("getOperation","getJob","reconcileOperation","fetchResults","cancelJob");
    private static final Set<String> OWNER_DOWNLOAD=Set.of("reviewResultDownload","confirmResultDownload");
    private final TaskStore tasks;
    // Only revocable in-flight waits live here. All identities, approvals and outcomes live in TaskStore.
    private final ConcurrentHashMap<String,Active> active=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final java.util.concurrent.Executor reads;
    private final ConcurrentHashMap<String,ReadContinuation> continuedReads=new ConcurrentHashMap<>();
    public MediaWorkflowHost(TaskStore tasks){this(tasks,null);}
    public MediaWorkflowHost(TaskStore tasks,java.util.concurrent.Executor originalHostWorkers){this.tasks=java.util.Objects.requireNonNull(tasks);reads=originalHostWorkers;}
    public JSONObject continuationSnapshot()throws Exception{JSONArray operations=new JSONArray();for(ReadContinuation waiting:continuedReads.values())operations.put(new JSONObject().put("taskId",waiting.task).put("runId",waiting.run).put("operationId",waiting.operation).put("providerJobId",waiting.job).put("expiresAt",waiting.until));return new JSONObject().put("applicationAlivePolling",reads!=null).put("activeOriginalReadContinuations",continuedReads.size()).put("operations",operations).put("osBackgroundGuaranteed",false).put("coldAutomaticResume",false).put("readLimit",60).put("maxDurationMillis",300000).put("scope","confirmed_original_comfyui_job_only;original_approval_expiry;no_new_POST");}

    public static boolean isSubmission(String command){return SUBMIT.contains(command);}
    public static boolean isOriginalCommand(String command){return ORIGINAL.contains(command)||OWNER_DOWNLOAD.contains(command);}
    public RunControl currentControl(String taskId){for(Active entry:active.values())if(entry.task.equals(taskId))return entry.control;for(ReadContinuation waiting:continuedReads.values())if(waiting.task.equals(taskId))return waiting.control;return null;}

    /** Routing guard: these tasks must never enter the generic assistant/model resume loop. */
    public boolean ownsTask(String taskId)throws Exception{
        // Routing needs original ownership and operation uniqueness, not every
        // argument/receipt body. The native store still verifies marker bodies;
        // this predicate never replaces preview or dispatch-time authority checks.
        synchronized(tasks){return tasks.ownsMediaTaskForNativeRouting(taskId);}
    }

    /** First owner action: durable preview identity, with no run, grant, operation or HTTP effect. */
    public JSONObject prepareFromOwner(MediaModule module,String command,JSONObject payload)throws Exception{
        requireOpen();JSONObject args=arguments(command,payload,false);String revision=configuration(module,args);
        JSONObject task=tasks.createCapabilityTask(goal(command,args),"");
        String hash=TaskStore.capabilityFingerprint(args);JSONObject preview=new JSONObject().put("type","media_owner_prepared").put("command",command)
                .put("requestHash",hash).put("configurationRevision",revision).put("actorId","owner").put("spaceId","private");
        // Establish ownership before saving draft bytes: a quota/disk failure must not expose a
        // half-prepared media task to generic assistant resume. No HTTP has happened here.
        tasks.event(task.getString("id"),preview);tasks.status(task.getString("id"),"awaiting_user","媒体输入正在保存，尚未请求服务");
        if(command.equals("editImage")){JSONArray sources=module.imageEditInputReferencesForOwner(args,new RunControl());for(int i=0;i<sources.length();i++)tasks.linkOwnerAttachment(task.getString("id"),sources.getJSONObject(i));}
        JSONObject input=module.retainOwnerSubmissionInput(task.getString("id"),command,args,hash);
        tasks.event(task.getString("id"),new JSONObject(preview.toString()).put("inputAttachment",input));
        tasks.status(task.getString("id"),"awaiting_user","媒体输入已准备；等待主人确认提交，尚未请求服务");
        return new JSONObject().put("taskId",task.getString("id")).put("hostTaskId",task.getString("id")).put("submissionCommand",command)
                .put("configurationRevision",revision).put("requestHash",TaskStore.capabilityFingerprint(args))
                .put("status","awaiting_owner_submission").put("dispatched",false).put("executionAuthorized",false);
    }

    /** Reopen the same unsent request after navigation/restart. Missing/edited bytes never become a new submission. */
    public JSONObject preparedFromOwner(MediaModule module,String taskId)throws Exception{
        requireOpen();final JSONObject preview;final boolean known;
        synchronized(tasks){JSONObject task=requiredTask(taskId);preview=new JSONObject(preview(task).toString());known=mediaOperation(task)!=null;}
        JSONObject attachment=preview.optJSONObject("inputAttachment");if(attachment==null)throw new IllegalStateException("原媒体输入没有完整保存，不能猜测恢复；没有重新提交服务");
        JSONObject document=module.readOwnerSubmissionInput(taskId,preview.getString("requestHash"),attachment);String command=document.getString("submissionCommand");
        JSONObject args=arguments(command,document.getJSONObject("arguments"),false);verifyPreview(preview,command,args);
        return new JSONObject().put("taskId",taskId).put("hostTaskId",taskId).put("submissionCommand",command).put("arguments",args)
                .put("configurationRevision",preview.getString("configurationRevision")).put("requestHash",preview.getString("requestHash"))
                .put("inputAttachment",attachment).put("knownOperation",known).put("dispatched",known?JSONObject.NULL:Boolean.FALSE).put("executionAuthorized",false).put("status",known?"original_operation_exists":"awaiting_owner_submission");
    }

    /** Second explicit native owner action. An existing operation can only be read, never regenerated. */
    public JSONObject submitFromOwner(MediaModule module,String command,JSONObject payload)throws Exception{
        requireOpen();String taskId=payload.getString("taskId");JSONObject args=arguments(command,payload,true);
        final JSONArray selectedInputs=command.equals("editImage")?module.imageEditInputReferencesForOwner(args,new RunControl()):null;
        final Active entry;final RunJournal journal;final JSONObject input;
        synchronized(this){
            requireOpen();
            synchronized(tasks){
                JSONObject task=requiredTask(taskId);JSONObject preview=preview(task);
                verifyPreview(preview,command,args);
                JSONObject existing=mediaOperation(task);
                if(existing!=null)return originalReceipt(task,existing).put("knownOperation",true);
                if(!active.isEmpty())throw new IllegalStateException("已有媒体协议请求执行中，请先等待或停止原请求");
                configuration(module,args); // Recheck the configuration the owner actually reviewed.
                if(tasks.hasUnresolved(taskId))throw new IllegalStateException("原任务仍有未知操作，不能再次提交媒体请求");
                JSONObject admitted=tasks.startAttempt(taskId,"capabilities",1);String run=admitted.getString("runId");int epoch=admitted.getInt("coordinatorEpoch");
                RunControl control=new RunControl();control.bindHostRun(taskId,run,epoch);String tool=tool(command);
                tasks.authorizeTools(taskId,run,new JSONArray().put(tool));
                JSONObject approvalArgs=new JSONObject().put("command",command).put("appReadContinuation",args.optBoolean("observeUntilComplete")).put("configurationRevision",args.getString("configurationRevision"))
                        .put("requestHash",TaskStore.capabilityFingerprint(args));
                JSONObject capability=new JSONObject().put("kind","media").put("tool",tool).put("configurationRevision",args.getString("configurationRevision"));
                if(args.optBoolean("observeUntilComplete"))capability.put("originalJobReadContinuation",new JSONObject().put("onlyConfirmedJob",true).put("maxReads",60).put("maxDurationMillis",300000).put("osBackgroundGuaranteed",false));
                if(selectedInputs!=null){approvalArgs.put("inputArtifacts",selectedInputs);capability.put("inputArtifacts",selectedInputs).put("uploadsOriginalBytes",true).put("overwritesOriginal",false);}
                JSONObject approval=tasks.requireCapabilityApproval(taskId,run,tool,approvalArgs,capability);
                // This method is reachable only from the owner's explicit submit action, not a model tool.
                if(!approval.optBoolean("approved")){
                    if(!"pending".equals(approval.optString("state")))throw new SecurityException("本次媒体确认已拒绝或失效");
                    tasks.decideCapabilityApproval(taskId,approval.getString("approvalId"),true);
                    approval=tasks.requireCapabilityApproval(taskId,run,tool,approvalArgs,capability);
                }
                if(!approval.optBoolean("approved"))throw new SecurityException("媒体输入和服务配置尚未取得本机确认");
                String approvalId=approval.getString("approvalId");journal=tasks.journal(taskId,run,epoch,control);
                JSONObject intent=new JSONObject(approvalArgs.toString()).put("approvalId",approvalId).put("actorId","owner").put("spaceId","private");
                String operation=journal.beginOperation(tool,intent);
                entry=new Active(taskId,run,epoch,operation,tool,args.getString("configurationRevision"),intent,control,false,task.optBoolean("cancelRequested"));
                active.put(operation,entry);control.setAuthorityCheck(()->check(entry));
                tasks.event(taskId,run,epoch,new JSONObject().put("type","media_owner_submission").put("operationId",operation).put("approvalId",approvalId));
                input=new JSONObject(args.toString());input.remove("configurationRevision");bind(input,entry);
            }
        }
        try{
            entry.control.check();JSONObject receipt=module.handleForHost(command,input,entry.control);
            persist(entry,receipt,true);return withHost(receipt,entry);
        }catch(Exception failure){
            boolean capacityRejected=failure instanceof MediaJobJournal.CapacityExceededException&&entry.operation.equals(((MediaJobJournal.CapacityExceededException)failure).operationId);
            boolean undispatched=capacityRejected||failure instanceof SecurityException||failure instanceof IllegalArgumentException;
            JSONObject notice=new JSONObject().put("operationId",entry.operation).put("taskId",entry.task).put("runId",entry.run)
                    .put("configurationRevision",entry.revision).put("status",undispatched?"failed":"unknown")
                    .put("receiptSource","native_host_failure").put("verified",false).put("message",capacityRejected?"媒体回执已达256个，未派发新生成；原回执保留，本任务不会重新提交":"媒体原操作未取得完整回执；请核对原操作，不会重新提交");
            if(undispatched)notice.put("dispatched",false);
            if(capacityRejected)notice.put("failureReason","receipt_capacity");
            persist(entry,notice,true);throw failure;
        }finally{active.remove(entry.operation,entry);startConfirmedReadContinuation(module,entry.task,entry.operation);}
    }

    /** Explicit owner recovery: GET/query, import original artifacts or targeted cancellation; never submit. */
    public JSONObject handleOriginal(MediaModule module,String command,JSONObject payload)throws Exception{
        return handleOriginal(module,command,payload,null,true);
    }
    /** The UI control can stop this read/fetch without cancelling a newer execution of the same task. */
    public JSONObject handleOriginal(MediaModule module,String command,JSONObject payload,RunControl caller)throws Exception{
        return handleOriginal(module,command,payload,caller,false);
    }
    private JSONObject handleOriginal(MediaModule module,String command,JSONObject payload,RunControl caller,boolean explicitOwner)throws Exception{
        requireOpen();boolean ownerDownload=OWNER_DOWNLOAD.contains(command);
        if(ownerDownload&&!explicitOwner)throw new SecurityException("一次原结果下载必须从原任务的主人审阅按钮确认，查询或模型入口不能授权");
        if(!ORIGINAL.contains(command)&&!ownerDownload)throw new IllegalArgumentException("不是原媒体操作查询或控制命令");
        String operation=payload.getString("operationId");
        if(command.equals("cancelJob")){JSONObject stopped=cancelLocalWait(operation);if(stopped!=null)return stopped;}
        final Active entry;final JSONObject input;
        synchronized(this){
            if(!active.isEmpty())throw new IllegalStateException("已有媒体协议请求执行中，请等待返回或停止原操作");
            synchronized(tasks){
                String taskId=tasks.operationTask("media",operation);if(taskId==null)throw new SecurityException("原媒体操作不在宿主任务日志中");
                JSONObject task=requiredTask(taskId),op=operation(task,operation),source=tasks.ownerMediaOperationSource(taskId,operation);
                JSONObject binding=new JSONObject(payload.toString());
                if(ownerDownload)binding.remove("downloadReview");
                checkSupplied(binding,task,op,source.getInt("epoch"));
                if(ownerDownload&&command.equals("reviewResultDownload")&&payload.has("downloadReview"))throw new SecurityException("请先读取原结果，不能指定下载审阅内容");
                if(ownerDownload)requireOwnerDownloadApproval(task,op);
                String revision=op.getJSONObject("arguments").getString("configurationRevision");
                RunControl control=RunControl.forOriginalRecovery(taskId,op.getString("runId"),source.getInt("epoch"));
                entry=new Active(taskId,op.getString("runId"),source.getInt("epoch"),operation,op.getString("tool"),revision,op.getJSONObject("arguments"),control,true,task.optBoolean("cancelRequested"));
                active.put(operation,entry);control.retainAuthorityCheck(()->{check(entry);if(ownerDownload)synchronized(tasks){JSONObject actual=current(entry);requireOwnerDownloadApproval(actual,operation(actual,entry.operation));}});input=new JSONObject(payload.toString());bind(input,entry);
            }
        }
        Runnable cancelled=entry.control::cancel;if(caller!=null)caller.onCancel(cancelled);
        try{
            if(caller!=null)caller.check();
            entry.control.check();JSONObject receipt=ownerDownload?module.originalResultDownloadForOwner(command,input,entry.control):module.handleForHost(command,input,entry.control);
            persist(entry,receipt,!command.equals("reviewResultDownload"));return withHost(receipt,entry);
        }finally{if(caller!=null)caller.removeCancelAction(cancelled);active.remove(operation,entry);}
    }

    /** Urgent local control only. In particular this never waits on /history, /queue or /interrupt. */
    public JSONObject observeOriginal(MediaModule module,String taskId,String operationId,RunControl parent,dev.pocketengine.media.jobs.ComfyUiProgressObserver.Listener listener)throws Exception{
        requireOpen();JSONObject source=tasks.ownerMediaOperationSource(taskId,operationId);String revision=source.getJSONObject("arguments").getString("configurationRevision");
        RunControl observer=RunControl.forOriginalRecovery(taskId,source.getString("runId"),source.getInt("epoch"));
        observer.retainAuthorityCheck(()->{requireOpen();parent.check();JSONObject actual=tasks.ownerMediaOperationSource(taskId,operationId);
            for(String key:new String[]{"runId","argumentsHash","tool"})if(!source.getString(key).equals(actual.getString(key)))throw new SecurityException("原媒体观察来源已变化");if(source.getInt("epoch")!=actual.getInt("epoch"))throw new SecurityException("原媒体观察代次已变化");});
        Runnable stop=observer::cancel;parent.onCancel(stop);
        try{return module.observeProgress(new JSONObject().put("operationId",operationId).put("_operationId",operationId).put("_taskId",taskId).put("_runId",source.getString("runId")).put("_epoch",source.getInt("epoch")).put("_configurationRevision",revision).put("maxDurationMs",30000),observer,listener);}
        finally{parent.removeCancelAction(stop);observer.cancel();}
    }
    /** Urgent local control only. In particular this never waits on /history, /queue or /interrupt. */
    public JSONObject cancelLocalWait(String operation)throws Exception{
        ReadContinuation waiting=continuedReads.get(operation);if(waiting!=null){waiting.control.cancel();tasks.requestCancel(waiting.task,waiting.run);}
        Active entry=active.get(operation);if(entry==null)return waiting==null?null:new JSONObject().put("hostTaskId",waiting.task).put("taskId",waiting.task).put("runId",waiting.run).put("operationId",operation).put("status","local_wait_stop_requested").put("localWaitCancelled",true).put("providerStateConfirmed",false).put("message","已停止原job的自动核对，远端仍可能继续；没有重发或声明已撤销");
        synchronized(tasks){
            current(entry);entry.control.cancel();if(!entry.recovery)tasks.requestCancel(entry.task,entry.run);
            JSONObject receipt=new JSONObject().put("operationId",operation).put("taskId",entry.task).put("runId",entry.run).put("epoch",entry.epoch)
                    .put("status","local_wait_stop_requested").put("localWaitCancelled",true).put("providerStateConfirmed",false).put("verified",false)
                    .put("receiptSource","native_local_control").put("message","已停止原操作的本地等待；远端可能继续执行，未声明远端已取消");
            tasks.event(entry.task,entry.run,entry.epoch,new JSONObject().put("type","media_local_wait_stop").put("receipt",receipt));
            return withHost(receipt,entry);
        }
    }
    public JSONObject cancelOriginalTask(String taskId,String run,int epoch)throws Exception {
        synchronized(tasks){tasks.requireOriginalExecutionStopTarget(new JSONObject().put("taskId",taskId).put("runId",run).put("epoch",epoch));return cancelTask(taskId);}
    }
    public JSONObject cancelTask(String taskId)throws Exception{
        ReadContinuation waiting=null;for(ReadContinuation next:continuedReads.values())if(next.task.equals(taskId)){waiting=next;break;}
        if(waiting!=null){waiting.control.cancel();tasks.requestCancel(waiting.task,waiting.run);tasks.event(waiting.task,new JSONObject().put("type","media_read_continuation_stopped").put("operationId",waiting.operation).put("remoteTerminationConfirmed",false));}
        for(Active entry:active.values())if(entry.task.equals(taskId))return cancelLocalWait(entry.operation);
        return waiting==null?null:new JSONObject().put("taskId",waiting.task).put("operationId",waiting.operation).put("status","local_wait_stop_requested").put("remoteTerminationConfirmed",false).put("message","已停止原job的自动状态核对；未声称远端已停止或撤回费用");
    }

    /** Original leaf events retain full provider/artifact evidence without a competing executor state. */
    public void recordEvent(JSONObject event)throws Exception{
        if(!"feature_event".equals(event.optString("type"))||!"media".equals(event.optString("feature")))return;
        JSONObject receipt=event.optJSONObject("payload");if(receipt==null||receipt.optString("operationId").isEmpty()||!receipt.has("status"))return;
        synchronized(tasks){
            String taskId=tasks.operationTask("media",receipt.getString("operationId"));if(taskId==null)return;
            JSONObject task=requiredTask(taskId),op=operation(task,receipt.getString("operationId"));
            Active entry=active.get(receipt.getString("operationId"));
            if(entry==null||!entry.run.equals(task.optString("runId"))||entry.epoch!=task.optInt("coordinatorEpoch")){JSONObject source=tasks.ownerMediaOperationSource(taskId,op.getString("operationId"));RunControl control=new RunControl();entry=new Active(taskId,source.getString("runId"),source.getInt("epoch"),op.getString("operationId"),op.getString("tool"),op.getJSONObject("arguments").getString("configurationRevision"),op.getJSONObject("arguments"),control,true,task.optBoolean("cancelRequested"));}
            persist(entry,receipt,false);
        }
    }

    /** Runs on the existing application's native host workers, NOT an OS/background-service
     * guarantee. A page observer may disappear without cancelling this already-reviewed read.
     * The original durable event prevents a cold launch or duplicate receipt from resetting it. */
    public void startConfirmedReadContinuation(MediaModule module,String taskId,String operation){
        if(reads==null||closed||!active.isEmpty()||continuedReads.containsKey(operation))return;
        final ReadContinuation waiting;
        try{
            JSONObject actual=module.originalReceiptForHost(operation);if(!"comfyui".equals(actual.optString("provider"))||!actual.optBoolean("providerJobIdConfirmed")||!actual.optString("status").matches("queued|running|unknown")||actual.optString("providerJobId").isEmpty())return;
            JSONObject source=tasks.ownerMediaOperationSource(taskId,operation),args=source.getJSONObject("arguments");if(!args.optBoolean("appReadContinuation",args.optBoolean("observeUntilComplete")))return;
            long expires=continuationAuthority(taskId,source);waiting=new ReadContinuation(taskId,source.getString("runId"),source.getInt("epoch"),operation,actual.getString("providerJobId"),actual.getString("configurationRevision"),expires,source.getString("argumentsHash"));
            synchronized(this){if(closed||!continuedReads.isEmpty())return;continuedReads.put(operation,waiting);}
            // MediaJobs may emit into Tasks: never enter it while holding the Tasks monitor.
            if(!module.markOriginalReadStartedForHost(operation,taskId,waiting.run,waiting.revision,waiting.until)){continuedReads.remove(operation,waiting);return;}
            continuationAuthority(taskId,source);
            tasks.event(taskId,new JSONObject().put("type","media_read_continuation_started").put("operationId",operation).put("providerJobId",waiting.job).put("configurationRevision",waiting.revision).put("maxReads",60).put("expiresAt",waiting.until).put("osBackgroundGuaranteed",false));
            try{reads.execute(()->continueReads(module,waiting));}catch(java.util.concurrent.RejectedExecutionException unavailable){continuedReads.remove(operation,waiting);waiting.control.cancel();tasks.event(taskId,new JSONObject().put("type","media_read_continuation_waiting_foreground").put("operationId",operation).put("message","原宿主无可用读取执行位，请在原任务明确继续查询；未重发生成"));}
        }catch(Exception noReadAuthority){ReadContinuation failed=continuedReads.remove(operation);if(failed!=null)failed.control.cancel();/* No original confirmed scope: leave the durable job for explicit owner query. */}
    }
    private long continuationAuthority(String taskId,JSONObject source)throws Exception{
        if(!taskId.equals(source.optString("taskId")))throw new SecurityException("原媒体续查来源不同");return Math.min(tasks.ownerMediaReadContinuationExpiry(source),System.currentTimeMillis()+300000);
    }
    private void continueReads(MediaModule module,ReadContinuation waiting){
        String ended="limit_or_authority";int reads=0;
        try{
            waiting.control.retainAuthorityCheck(()->{requireOpen();if(continuedReads.get(waiting.operation)!=waiting||System.currentTimeMillis()>=waiting.until)throw new SecurityException("原job续查已到期");JSONObject source=tasks.ownerMediaOperationSource(waiting.task,waiting.operation);if(!waiting.run.equals(source.getString("runId"))||waiting.epoch!=source.getInt("epoch")||!waiting.argumentHash.equals(source.getString("argumentsHash")))throw new SecurityException("原job来源已换代");continuationAuthority(waiting.task,source);if(!waiting.revision.equals(module.snapshot().getString("configurationRevision")))throw new SecurityException("原服务配置已撤回或变化");});
            for(;reads<60;reads++){
                while(!active.isEmpty()||module.snapshot().optBoolean("busy")){waiting.control.check();Thread.sleep(100);}
                waiting.control.check();JSONObject actual=module.originalReceiptForHost(waiting.operation);if(!actual.optBoolean("providerJobIdConfirmed")||!waiting.job.equals(actual.optString("providerJobId"))||!waiting.revision.equals(actual.optString("configurationRevision")))throw new SecurityException("原已确认job身份改变");
                JSONObject result=handleOriginal(module,"getJob",new JSONObject().put("operationId",waiting.operation).put("taskId",waiting.task).put("runId",waiting.run).put("epoch",waiting.epoch),waiting.control);
                if(result.optString("status").matches("response_received|failed|cancelled")){ended="original_provider_terminal";break;}
                long wake=System.nanoTime()+2_000_000_000L;while(System.nanoTime()<wake){waiting.control.check();Thread.sleep(100);}
            }
        }catch(Exception stopped){ended=waiting.control.isCancelled()?"owner_stopped":"original_read_unavailable_or_authority_ended";}
        finally{continuedReads.remove(waiting.operation,waiting);waiting.control.cancel();try{module.finishOriginalReadContinuationForHost(waiting.operation,ended,reads);}catch(Exception unavailable){}try{tasks.event(waiting.task,new JSONObject().put("type","media_read_continuation_ended").put("operationId",waiting.operation).put("reason",ended).put("reads",reads).put("remoteTerminationConfirmed",false).put("newSubmission",false));}catch(Exception unavailable){}}
    }
    private static final class ReadContinuation{
        final String task,run,operation,job,revision,argumentHash;final int epoch;final long until;final RunControl control;
        ReadContinuation(String task,String run,int epoch,String operation,String job,String revision,long until,String hash){this.task=task;this.run=run;this.epoch=epoch;this.operation=operation;this.job=job;this.revision=revision;this.until=until;argumentHash=hash;control=RunControl.forOriginalRecovery(task,run,epoch);}
    }
    private void check(Active entry)throws Exception{
        if(closed||active.get(entry.operation)!=entry||entry.control.isCancelled()||entry.control.isPaused())throw new SecurityException("原媒体等待控制已停止或被替换");
        synchronized(tasks){
            JSONObject task=current(entry);JSONObject op=operation(task,entry.operation);
            if(!entry.tool.equals(op.optString("tool"))||!TaskStore.capabilityFingerprint(entry.intent).equals(TaskStore.capabilityFingerprint(op.getJSONObject("arguments"))))throw new SecurityException("媒体原操作的确认身份已变化");
            if(!entry.recovery&&entry.intent.has("inputArtifacts")){JSONArray wanted=entry.intent.getJSONArray("inputArtifacts"),selected=tasks.ownerAttachmentReferences(entry.task);for(int i=0;i<wanted.length();i++){JSONObject reference=wanted.getJSONObject(i);boolean found=false;for(int j=0;j<selected.length();j++)if(reference.getString("id").equals(selected.getJSONObject(j).optString("id"))&&reference.getString("sha256").equals(selected.getJSONObject(j).optString("sha256")))found=true;if(!found)throw new SecurityException("原编辑图像或mask已撤回");}}
            if(entry.recovery){if(!entry.cancelAtAdmission&&task.optBoolean("cancelRequested"))throw new SecurityException("媒体查询或导入已被主人停止");return;}
            tasks.checkCapabilityCurrent(entry.task,entry.run,entry.epoch,entry.tool);
            JSONArray operations=task.getJSONArray("operations");
            for(int i=0;i<operations.length();i++){JSONObject other=operations.getJSONObject(i);String state=other.optString("state");if(state.matches("prepared|unknown")&&(!entry.operation.equals(other.optString("operationId"))||!state.equals("prepared")))throw new SecurityException("媒体原任务存在未知操作，不能继续派发");}
            JSONArray approvals=task.optJSONArray("approvals");boolean valid=false;
            for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(entry.intent.getString("approvalId").equals(approval.optString("id"))&&entry.run.equals(approval.optString("runId"))&&entry.tool.equals(approval.optString("tool"))&&"consumed".equals(approval.optString("state"))&&approval.optLong("expiresAt")>System.currentTimeMillis())valid=true;}
            if(!valid)throw new SecurityException("媒体原输入或配置确认已失效");
        }
    }
    private JSONObject current(Active entry)throws Exception{
        JSONObject task=requiredTask(entry.task);
        if(entry.recovery){JSONObject source=tasks.ownerMediaOperationSource(entry.task,entry.operation);
            if(!entry.run.equals(source.getString("runId"))||entry.epoch!=source.getInt("epoch")||!entry.tool.equals(source.getString("tool"))||!TaskStore.capabilityFingerprint(entry.intent).equals(source.getString("argumentsHash")))throw new SecurityException("原媒体读取来源已变化");return task;}
        if(!entry.run.equals(task.optString("runId"))||entry.epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("原媒体执行代次已失效");return task;
    }
    private void persist(Active entry,JSONObject receipt,boolean settle)throws Exception{
        synchronized(tasks){
            JSONObject task=current(entry);validateReceipt(entry,receipt);
            if(entry.recovery){
                tasks.reconcileOwnerMediaReceipt(tasks.ownerMediaOperationSource(entry.task,entry.operation),receipt);
                if(!settle||!ownsTask(entry.task)||!entry.run.equals(task.optString("runId"))||entry.epoch!=task.optInt("coordinatorEpoch")||task.optString("status").equals("completed"))return;
                String raw=receipt.optString("status"),status=raw.matches("unknown|prepared|submitting|not_found")?"reconciling":raw.equals("response_received")?"needs_confirmation":raw.matches("failed|cancelled")?raw:"awaiting_user";
                tasks.endAttempt(entry.task,entry.run,status,receipt.optString("message","原媒体回执已保存，请查看原产物"),new JSONObject(receipt.toString()).put("automaticallyVerified",false));return;
            }
            tasks.journal(entry.task,entry.run,entry.epoch,entry.control).finishOperation(entry.operation,receipt);
            tasks.event(entry.task,entry.run,entry.epoch,new JSONObject().put("type","media_receipt").put("operationId",entry.operation).put("receipt",receipt));
            if(!settle||task.optString("status").equals("completed"))return;
            String raw=receipt.optString("status"),status=raw.matches("unknown|prepared|submitting|not_found")?"reconciling":raw.equals("response_received")?"needs_confirmation":raw.matches("failed|cancelled")?raw:"awaiting_user";
            tasks.endAttempt(entry.task,entry.run,status,receipt.optString("message","原媒体回执已保存；远端活动和本机执行分别显示，请检查原任务"),new JSONObject(receipt.toString()).put("automaticallyVerified",false));
        }
    }
    private static void requireOwnerDownloadApproval(JSONObject task,JSONObject op)throws Exception{
        JSONObject args=op.getJSONObject("arguments"),approvedArgs=new JSONObject(args.toString());
        if(!"owner".equals(args.optString("actorId"))||!"private".equals(args.optString("spaceId"))||!args.optString("command").matches("generateImage|editImage")||args.optString("approvalId").isEmpty())throw new SecurityException("缺少原主人媒体提交的准确授权来源");
        for(String field:new String[]{"approvalId","actorId","spaceId"})approvedArgs.remove(field);
        JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){
            JSONObject approval=approvals.getJSONObject(i),capability=approval.optJSONObject("capability");
            if(args.getString("approvalId").equals(approval.optString("id"))&&op.getString("runId").equals(approval.optString("runId"))&&op.getString("tool").equals(approval.optString("tool"))&&"consumed".equals(approval.optString("state"))&&TaskStore.capabilityFingerprint(approvedArgs).equals(approval.optString("argumentsHash"))&&capability!=null&&args.getString("configurationRevision").equals(capability.optString("configurationRevision")))return;
        }
        throw new SecurityException("原主人媒体提交授权已缺失或被替换，不能授予结果下载");
    }
    private static void validateReceipt(Active entry,JSONObject receipt)throws Exception{
        if(!entry.operation.equals(receipt.optString("operationId"))||!entry.task.equals(receipt.optString("taskId"))||!entry.run.equals(receipt.optString("runId"))||!entry.revision.equals(receipt.optString("configurationRevision")))throw new SecurityException("媒体回执不属于原操作、执行代次或配置");
    }
    private JSONObject originalReceipt(JSONObject task,JSONObject op)throws Exception{
        JSONObject receipt=op.optJSONObject("receipt");
        if(receipt==null)return new JSONObject().put("hostTaskId",task.getString("id")).put("taskId",task.getString("id")).put("operationId",op.getString("operationId"))
                .put("hostOperation",new JSONObject(op.toString())).put("status","host_receipt_unavailable").put("dispatched",JSONObject.NULL)
                .put("message","宿主已登记原操作，但服务回执未确认；未再次生成，请查询原操作");
        if(!task.getString("id").equals(receipt.optString("taskId"))||!op.getString("runId").equals(receipt.optString("runId"))||!op.getJSONObject("arguments").getString("configurationRevision").equals(receipt.optString("configurationRevision")))throw new SecurityException("媒体原回执身份冲突");
        return new JSONObject(receipt.toString()).put("hostTaskId",task.getString("id"));
    }
    private static JSONObject arguments(String command,JSONObject payload,boolean submitting)throws Exception{
        if(!SUBMIT.contains(command))throw new IllegalArgumentException("不支持的媒体提交命令");
        String field=command.equals("submitWorkflow")?"workflow":command.equals("synthesizeSpeech")?"text":"prompt";
        java.util.Iterator<String> keys=payload.keys();while(keys.hasNext()){String key=keys.next();if(!key.equals(field)&&!key.equals("configurationRevision")&&!key.equals("observeUntilComplete")&&!(command.equals("editImage")&&Set.of("imageId","imageSha256","maskId","maskSha256").contains(key))&&!(submitting&&key.equals("taskId")))throw new SecurityException("媒体输入含未授权字段："+key);}
        String revision=payload.getString("configurationRevision");if(revision.codePoints().allMatch(Character::isWhitespace)||revision.length()>128)throw new IllegalArgumentException("请重新读取并确认媒体配置版本");
        JSONObject result=new JSONObject().put("configurationRevision",revision);if(payload.has("observeUntilComplete")){if(!(payload.opt("observeUntilComplete") instanceof Boolean))throw new IllegalArgumentException("原job续查选择必须明确");result.put("observeUntilComplete",payload.getBoolean("observeUntilComplete"));}
        if(command.equals("submitWorkflow"))result.put(field,ComfyUiTransport.workflow(payload.getJSONObject(field)));
        else{String text=payload.getString(field);int maximum=command.equals("synthesizeSpeech")?MediaTransport.MAX_SPEECH_INPUT_CHARS:MediaTransport.MAX_IMAGE_INPUT_CHARS;if(text.codePoints().allMatch(Character::isWhitespace)||text.length()>maximum)throw new IllegalArgumentException("媒体输入应为1–"+maximum+"字");result.put(field,text);}
        if(command.equals("editImage")){for(String key:new String[]{"imageId","imageSha256"})result.put(key,payload.getString(key));if(payload.has("maskId")!=payload.has("maskSha256"))throw new IllegalArgumentException("mask需要准确ID与SHA");for(String key:new String[]{"maskId","maskSha256"})if(payload.has(key))result.put(key,payload.getString(key));for(String key:new String[]{"imageId","maskId"})if(result.has(key)&&!result.getString(key).matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原图或mask身份无效");for(String key:new String[]{"imageSha256","maskSha256"})if(result.has(key)&&!result.getString(key).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原图或mask缺少完整SHA");}
        return result;
    }
    private static String configuration(MediaModule module,JSONObject args)throws Exception{
        JSONObject snapshot=module.snapshot();String revision=snapshot.getString("configurationRevision");
        if(!snapshot.optBoolean("configured")||!revision.equals(args.getString("configurationRevision")))throw new SecurityException("媒体服务未配置或已变化，请刷新后重新确认");return revision;
    }
    private JSONObject requiredTask(String id)throws Exception{JSONObject task=tasks.get(id,false);if(task==null||!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("不是本机主人准备的媒体原任务");return task;}
    private static JSONObject preview(JSONObject task)throws Exception{JSONArray events=task.optJSONArray("events");for(int i=events==null?-1:events.length()-1;i>=0;i--){JSONObject event=events.getJSONObject(i);if("media_owner_prepared".equals(event.optString("type"))&&"owner".equals(event.optString("actorId"))&&"private".equals(event.optString("spaceId")))return event;}throw new SecurityException("缺少本机媒体准备记录");}
    private static void verifyPreview(JSONObject preview,String command,JSONObject args)throws Exception{if(!command.equals(preview.optString("command"))||!args.getString("configurationRevision").equals(preview.optString("configurationRevision"))||!TaskStore.capabilityFingerprint(args).equals(preview.optString("requestHash")))throw new SecurityException("媒体输入或服务配置与主人准备的版本不一致");}
    private static JSONObject mediaOperation(JSONObject task)throws Exception{JSONArray operations=task.getJSONArray("operations");JSONObject result=null;for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(isMediaTool(op.optString("tool"))){if(result!=null)throw new SecurityException("同一媒体任务含多个提交操作，禁止猜测或重提");result=op;}}return result;}
    private static JSONObject operation(JSONObject task,String id)throws Exception{JSONArray operations=task.getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(id.equals(op.optString("operationId"))&&isMediaTool(op.optString("tool")))return op;}throw new SecurityException("媒体操作不属于原任务");}
    private static boolean isMediaTool(String tool){return tool.matches("media_workflow|media_generate_image|media_generate_video|media_edit_image|media_synthesize_speech");}
    private static String tool(String command){return command.equals("submitWorkflow")?"media_workflow":command.equals("generateImage")?"media_generate_image":command.equals("generateVideo")?"media_generate_video":command.equals("editImage")?"media_edit_image":"media_synthesize_speech";}
    private static String goal(String command,JSONObject args){return command.equals("submitWorkflow")?"运行主人确认的媒体工作流":(command.equals("synthesizeSpeech")?"合成语音：":command.equals("editImage")?"编辑原图：":command.equals("generateVideo")?"生成视频：":"生成图片：")+args.optString(command.equals("synthesizeSpeech")?"text":"prompt").substring(0,Math.min(3000,args.optString(command.equals("synthesizeSpeech")?"text":"prompt").length()));}
    private static void bind(JSONObject input,Active entry)throws Exception{input.put("operationId",entry.operation).put("_operationId",entry.operation).put("_taskId",entry.task).put("_runId",entry.run).put("_epoch",entry.epoch).put("_configurationRevision",entry.revision);}
    private static void checkSupplied(JSONObject payload,JSONObject task,JSONObject op,int sourceEpoch)throws Exception{for(String key:new String[]{"taskId","runId","epoch","configurationRevision"})if(payload.has(key)){Object expected=key.equals("taskId")?task.getString("id"):key.equals("runId")?op.getString("runId"):key.equals("epoch")?sourceEpoch:op.getJSONObject("arguments").getString("configurationRevision");if(!String.valueOf(expected).equals(String.valueOf(payload.get(key))))throw new SecurityException("媒体查询不是原任务或配置版本");}java.util.Iterator<String> keys=payload.keys();while(keys.hasNext()){String key=keys.next();if(!Set.of("operationId","taskId","runId","epoch","configurationRevision","providerJobId").contains(key))throw new SecurityException("媒体查询含未授权字段："+key);}}
    private static JSONObject withHost(JSONObject receipt,Active entry)throws Exception{return new JSONObject(receipt.toString()).put("hostTaskId",entry.task).put("hostEpoch",entry.epoch);}
    private void requireOpen(){if(closed)throw new IllegalStateException("媒体宿主已关闭");}
    @Override public void close(){closed=true;for(Active entry:active.values())entry.control.cancel();for(ReadContinuation waiting:continuedReads.values())waiting.control.cancel();}
    private static final class Active{
        final String task,run,operation,tool,revision;final int epoch;final JSONObject intent;final RunControl control;final boolean recovery,cancelAtAdmission;
        Active(String task,String run,int epoch,String operation,String tool,String revision,JSONObject intent,RunControl control,boolean recovery,boolean cancelAtAdmission)throws Exception{this.task=task;this.run=run;this.epoch=epoch;this.operation=operation;this.tool=tool;this.revision=revision;this.intent=new JSONObject(intent.toString());this.control=control;this.recovery=recovery;this.cancelAtAdmission=cancelAtAdmission;}
    }
}
