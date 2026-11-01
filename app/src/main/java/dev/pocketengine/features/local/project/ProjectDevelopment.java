package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** A facade over existing checkout/job APIs. The host remains the only task/approval authority. */
public final class ProjectDevelopment {
    /** Installed by native composition only, never by a feature request or model-supplied JSON. */
    public interface HostPort {
        Claim claim(JSONObject binding,JSONObject exactRequest)throws Exception;
        /** Must use the existing prepared operation, persist sending before effect and reconcile on failure. */
        void dispatch(JSONObject binding,JSONObject exactRequest)throws Exception;
        /** Read the original operation's native journal link; never create or replay an operation here. */
        JSONObject operation(JSONObject binding)throws Exception;
        default void recordProcessInputReceipt(dev.pocketengine.features.local.runtime.ManagedProcessJobs.ProcessInputReceipt receipt)throws Exception {throw new SecurityException("原项目宿主未提供输入实际回执");}
        default JSONObject originalRequest(JSONObject binding)throws Exception {throw new SecurityException("原项目宿主未提供完整方案引用只读核对");}
        default JSONObject processInput(JSONObject binding,String executionId)throws Exception {throw new SecurityException("原项目宿主未提供受控终端输入");}
        default JSONObject stopService(JSONObject binding,String executionId)throws Exception {throw new SecurityException("原项目宿主未提供受控服务停止");}
        default dev.pocketengine.features.local.runtime.ProjectDevSession.Observed livePreview(JSONObject binding,String executionId,RunControl observation)throws Exception {throw new SecurityException("原项目宿主未提供已核活预览");}
        default void checkLivePreview(dev.pocketengine.features.local.runtime.ProjectDevSession.Observed observed)throws Exception {throw new SecurityException("原活预览宿主不可核对");}
        default void checkInputService(JSONObject binding,String executionId)throws Exception {checkDevService(binding,executionId);}
        default void checkDevService(JSONObject binding,String executionId)throws Exception {throw new SecurityException("原开发服务宿主不可核对");}
    }
    public static final class Claim {
        public final RunControl control;
        public final boolean knownOperation;
        public Claim(RunControl control,boolean knownOperation){this.control=java.util.Objects.requireNonNull(control);this.knownOperation=knownOperation;}
    }
    public static class NotDispatched extends Exception {
        public final JSONObject receipt;
        public NotDispatched(JSONObject receipt)throws Exception {super(receipt.optString("message","项目步骤尚未派发"));this.receipt=new JSONObject(receipt.toString());}
    }
    public static final class ApprovalRequired extends NotDispatched {public ApprovalRequired(JSONObject receipt)throws Exception {super(receipt);}}
    private final EngineModule local;
    private volatile HostPort host;
    public ProjectDevelopment(EngineModule local){this.local=java.util.Objects.requireNonNull(local);}
    public void setHost(HostPort value){host=value;}
    public dev.pocketengine.features.local.runtime.ProjectDevSession.Observed livePreview(JSONObject binding,String executionId,RunControl observation)throws Exception {return requireHost().livePreview(binding(binding),ProjectBuildPlan.identity(executionId),observation);}
    public void checkLivePreview(dev.pocketengine.features.local.runtime.ProjectDevSession.Observed observed)throws Exception {requireHost().checkLivePreview(observed);}
    public void checkInputService(JSONObject binding,String executionId)throws Exception {requireHost().checkInputService(binding,executionId);}
    public JSONObject processInput(JSONObject binding,String executionId)throws Exception {return requireHost().processInput(binding(binding),ProjectBuildPlan.identity(executionId));}
    public void checkDevService(JSONObject binding,String executionId)throws Exception {requireHost().checkDevService(binding(binding),ProjectBuildPlan.identity(executionId));}
    public JSONObject snapshot()throws Exception {
        return new JSONObject().put("available",true).put("manualOwnerExecutionAvailable",true).put("executionAvailable",host!=null)
                .put("hostRequiredForAgent",true).put("manualCommandsTrackedByExistingHost",true).put("binaryArtifacts",true).put("binaryArtifactScope","original_immutable_project_package")
                .put("sourceCheckScope","manifest_files_only").put("atomicSourceFence",false).put("selectedTextArtifacts",true).put("selectedTextArtifactMaxBytes",32000);
    }
    public JSONObject handle(String command,JSONObject input)throws Exception {
        JSONObject result;
        switch(command){
            case "project.inspect":result=ProjectBuildPlan.inspect(local,input.getString("checkoutId"),input.optString("runtimeId","termux"));break;
            case "project.plan":result=ProjectBuildPlan.create(local,input);break;
            case "project.execute":result=execute(input);break;
            case "project.adoptOwnerReceipt":case "project.poll":result=receipt(input);break;
            case "project.cancel":result=cancel(input);break;
            case "project.stopService":result=requireHost().stopService(binding(input.getJSONObject("binding")),ProjectBuildPlan.identity(input.getString("executionId")));break;
            case "project.artifact":result=artifact(input);break;
            case "project.generatedArtifactPlan":result=GeneratedArtifacts.prepare(local,input);break;
            case "project.generatedArtifact":result=GeneratedArtifacts.read(local,input);break;
            case "project.repair":result=repair(input);break;
            default:throw new IllegalArgumentException("未知项目开发操作");
        }
        for(String key:new String[]{"requestId","_requestId"})if(input.has(key))result.put(key,input.get(key));
        return result;
    }
    private JSONObject execute(JSONObject input)throws Exception {
        HostPort port=requireHost();JSONObject plan=ProjectBuildPlan.verified(input.getJSONObject("plan")),step=ProjectBuildPlan.step(plan,input.getString("stepId"));
        JSONObject binding=binding(input.getJSONObject("binding"));JSONObject exact=exact(plan,step);
        Claim claim=port.claim(ProjectSources.copy(binding),ProjectSources.copy(exact));check(claim,binding);
        if(!claim.knownOperation){
            long revision=claim.control.pauseRevision();check(claim,binding);
            if(revision!=claim.control.pauseRevision())throw new SecurityException("项目派发前暂停状态已变化，请重新核对原任务");
            // The native host must recheck its live authority at the actual existing dispatch boundary.
            try{port.dispatch(ProjectSources.copy(binding),ProjectSources.copy(exact));}
            catch(NotDispatched pending){return new JSONObject(pending.receipt.toString());}
        }
        JSONObject link=port.operation(ProjectSources.copy(binding));
        if(link==null||link.optString("jobId").isEmpty())return new JSONObject().put("status","unknown").put("binding",binding)
                .put("planDigest",plan.getString("planDigest")).put("stepId",step.getString("id")).put("intent",step.getString("id"))
                .put("message","原生操作尚未确认job关联；只核对原操作，不重派");
        return automaticReceipt(plan,step,binding,link,input.optJSONObject("cursor"));
    }
    private static void check(Claim claim,JSONObject binding)throws Exception {
        if(!claim.control.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("任务控制不属于此项目执行run");
        claim.control.check();if(claim.control.isPaused())throw new SecurityException("原任务已暂停，未派发项目命令");
    }
    private JSONObject receipt(JSONObject input)throws Exception {
        if(!input.has("plan")&&input.has("planRef")&&input.has("binding")){JSONObject nativeRequest=requireHost().originalRequest(binding(input.getJSONObject("binding")));if(!input.getString("planRef").equals(nativeRequest.optString("planRef"))||!input.getString("planDigest").equals(nativeRequest.optString("planDigest")))throw new SecurityException("只读回执引用与原操作不同");JSONObject resolved=new JSONObject(input.toString()).put("plan",nativeRequest.getJSONObject("plan")).put("stepId",nativeRequest.getString("stepId"));input=resolved;}
        JSONObject plan=ProjectBuildPlan.verified(input.getJSONObject("plan")),step=ProjectBuildPlan.step(plan,input.getString("stepId"));
        if(input.has("binding")){
            HostPort port=requireHost();JSONObject binding=binding(input.getJSONObject("binding"));JSONObject link=port.operation(ProjectSources.copy(binding));
            if(link==null||link.optString("jobId").isEmpty())throw new IllegalStateException("原操作没有已确认job，仅可核对原生journal");
            if(input.has("jobId")&&!link.getString("jobId").equals(input.getString("jobId")))throw new SecurityException("job与原操作不一致");
            return automaticReceipt(plan,step,binding,link,input.optJSONObject("cursor"));
        }
        String id=ProjectBuildPlan.identity(input.getString("jobId"));JSONObject job=job(id),link=job.optJSONObject("projectExecution");
        if(link==null||!"owner_tracked_job".equals(link.optString("authority")))throw new SecurityException("此job没有原生主人tracked项目关联，不能采纳UI回执");
        verifyLink(plan,step,link);
        String task=ProjectBuildPlan.identity(link.getString("hostTaskId")),run=ProjectBuildPlan.identity(link.getString("runId")),operation=ProjectBuildPlan.identity(link.getString("operationId"));
        if(!run.equals(operation)||!id.equals(operation))throw new SecurityException("主人tracked job身份不一致");
        JSONObject bound=new JSONObject().put("authority","owner_tracked_job").put("taskId",task).put("runId",run).put("operationId",operation);
        return normalize(plan,step,job,bound,input.optJSONObject("cursor"));
    }
    private JSONObject automaticReceipt(JSONObject plan,JSONObject step,JSONObject binding,JSONObject link,JSONObject cursor)throws Exception {
        verifyLink(plan,step,link);
        for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"})if(!ProjectSources.canonical(binding.get(key)).equals(ProjectSources.canonical(link.opt(key))))throw new SecurityException("原生操作关联属于其他task/run");
        final JSONObject job;
        try{job=link.optJSONObject("nativeReceipt")!=null?ProjectSources.copy(link.getJSONObject("nativeReceipt")):job(ProjectBuildPlan.identity(link.getString("jobId")));}
        catch(Exception absent){return new JSONObject().put("status","unknown").put("code","project_pending_unknown").put("accepted",JSONObject.NULL).put("binding",binding).put("projectExecution",ProjectSources.copy(link)).put("planDigest",plan.getString("planDigest")).put("stepId",step.getString("id")).put("message","原操作已登记但尚无可核对后端回执；只查询原执行，不重派");}
        if(!binding.getString("operationId").equals(job.optString("operationId")))throw new SecurityException("后端job不属于原操作");
        JSONObject route=ProjectSources.copy(link);route.remove("nativeReceipt");JSONObject result=normalize(plan,step,job,ProjectSources.copy(binding).put("authority","native_host_operation"),cursor).put("projectExecution",route);
        if(ProjectGit.plan(plan)){
            JSONObject git=ProjectGitResult.observe(exact(plan,step),job);result.put("git",ProjectGitResult.summary(git));
            if(git.optBoolean("publicationUnknown"))return result.put("code","project_pending_unknown").put("status","unknown").put("accepted",JSONObject.NULL).put("ok",false).put("reconciliationRequired",true).put("replayAllowed",false).put("message",git.getString("message"));
        }
        boolean owned="owned_service".equals(step.getJSONObject("payload").optString("runMode"))&&"project.runtime.run".equals(step.getString("localCommand"));
        if(owned&&Boolean.TRUE.equals(job.opt("nativeStartObserved"))&&Boolean.TRUE.equals(job.opt("ownedWorkerLive"))&&job.optString("launchAckIdentity").matches("[a-f0-9]{64}")&&job.optString("status").matches("running|awaiting_resource_release")&&Boolean.TRUE.equals(job.opt("processLaunched"))&&!job.optBoolean("resourcesReleased"))
            result.put("code","project_running").put("status","running").put("executionStatus",job.getString("status")).put("processReceiptReceived",job.optBoolean("receiptReceived")).put("receiptReceived",false).put("accepted",true).put("ok",true).put("terminal",false).put("serviceReady",false).put("executionId",job.getString("operationId")).put("reconciliationRequired",false);
        else if(!job.optBoolean("receiptReceived")||!job.optString("status").matches("completed|failed"))result.put("code","project_pending_unknown").put("accepted",JSONObject.NULL).put("reconciliationRequired",true);
        return result;
    }
    private static void verifyLink(JSONObject plan,JSONObject step,JSONObject link)throws Exception {
        if(!plan.getString("planDigest").equals(link.optString("planDigest"))||!step.getString("id").equals(link.optString("stepId"))
                ||!step.getString("id").equals(link.optString("intent"))||!plan.getString("sourceDigest").equals(link.optString("sourceDigest"))
                ||!ProjectSources.digest(step.getJSONObject("payload")).equals(link.optString("requestDigest")))throw new SecurityException("实际job不属于此精确计划步骤");
    }
    /** Read the real native journal envelope, not a model delivery or a second execution cache. */
    public static JSONObject recordedReceipt(JSONObject task,JSONObject operation)throws Exception {
        if(operation.getJSONObject("arguments").has("planRef")&&!operation.getJSONObject("arguments").has("plan"))return referenceReceiptProjection(task,operation);
        JSONObject request=originalPollRequest(task,operation),plan=request.getJSONObject("plan"),step=ProjectBuildPlan.step(plan,request.getString("stepId"));
        JSONObject envelope=operation.getJSONObject("receipt"),backend=envelope.optJSONObject("executionReceipt");
        if(backend==null)return null;
        JSONObject route=envelope.getJSONObject("projectExecution"),actual=backend.getJSONObject("projectExecution");
        for(String key:new String[]{"authority","command","projectId","checkoutId","taskId","runId","operationId","coordinatorEpoch","planDigest","stepId","intent","sourceDigest","requestDigest"})
            if(!ProjectSources.canonical(route.get(key)).equals(ProjectSources.canonical(actual.get(key))))throw new SecurityException("记录中的项目后端回执属于其他执行");
        if(route.has("runtimeId"))for(String key:new String[]{"runtimeId","runtimeIdentity"})if(!route.getString(key).equals(actual.getString(key)))throw new SecurityException("记录中的项目运行位置已变化");
        if(!operation.getString("operationId").equals(backend.getString("operationId"))||!operation.getString("operationId").equals(backend.getString("id")))throw new SecurityException("记录中的项目job编号不一致");
        return normalize(plan,step,backend,new JSONObject(request.getJSONObject("binding").toString()).put("authority","native_host_operation"),null).put("projectExecution",ProjectSources.copy(route));
    }
    /** Owner UI query only. The HostPort rechecks this exact prior row; this never dispatches. */
    public static JSONObject originalPollRequest(JSONObject task,JSONObject operation)throws Exception {
        if(!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||!"project_execute".equals(operation.optString("tool")))throw new SecurityException("不是本机私人原项目任务");
        JSONObject context=task.getJSONObject("developmentContext"),args=operation.getJSONObject("arguments");
        if(args.has("planRef")&&!args.has("plan")){
            JSONObject route=operation.getJSONObject("receipt").getJSONObject("projectExecution");verifyReferenceProjection(task,operation,route);
            return new JSONObject().put("checkoutId",context.getString("checkoutId")).put("planRef",args.getString("planRef")).put("planDigest",args.getString("planDigest")).put("stepId",args.getString("stepId")).put("binding",binding(route));
        }
        JSONObject plan=ProjectBuildPlan.verified(args.getJSONObject("plan")),step=ProjectBuildPlan.step(plan,args.getString("stepId"));
        if(!context.getString("checkoutId").equals(plan.getString("checkoutId"))||!context.getString("projectId").equals(plan.getString("projectId"))||!context.getString("runtime").equals(plan.optString("runtimeId","termux")))throw new SecurityException("原操作不属于选定项目或运行位置");
        JSONObject route=operation.getJSONObject("receipt").getJSONObject("projectExecution");verifyLink(plan,step,route);
        if(!"native_host_operation".equals(route.getString("authority"))||!task.getString("id").equals(route.getString("taskId"))||!operation.getString("runId").equals(route.getString("runId"))||!operation.getString("operationId").equals(route.getString("operationId"))||!operation.getString("operationId").equals(route.getString("jobId")))throw new SecurityException("原项目任务与操作路由不一致");
        JSONObject exact=exact(plan,step);for(String key:new String[]{"command","projectId","checkoutId"})if(!exact.getString(key).equals(route.getString(key)))throw new SecurityException("原项目操作方案与路由不一致");
        if(exact.has("runtimeId"))for(String key:new String[]{"runtimeId","runtimeIdentity"})if(!exact.getString(key).equals(route.getString(key)))throw new SecurityException("原项目操作运行位置不一致");
        JSONObject binding=binding(route);
        JSONObject request=new JSONObject().put("checkoutId",plan.getString("checkoutId")).put("plan",plan).put("stepId",step.getString("id")).put("binding",binding);if(args.has("planRef"))request.put("planRef",args.getString("planRef")).put("planDigest",args.getString("planDigest"));return request;
    }
    /** Owner display/query projection only. Native dispatch always resolves the full original SQL plan again. */
    private static void verifyReferenceProjection(JSONObject task,JSONObject operation,JSONObject route)throws Exception {
        if(!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||!"project_execute".equals(operation.optString("tool")))throw new SecurityException("不是私人原项目操作回执");
        JSONObject context=task.getJSONObject("developmentContext"),args=operation.getJSONObject("arguments");
        if(!args.optString("planRef").matches("[a-f0-9-]{36}")||!args.optString("planDigest").equals(route.optString("planDigest"))||!args.optString("stepId").equals(route.optString("stepId"))||!"native_host_operation".equals(route.optString("authority"))||!task.getString("id").equals(route.optString("taskId"))||!operation.getString("runId").equals(route.optString("runId"))||!operation.getString("operationId").equals(route.optString("operationId"))||!context.getString("checkoutId").equals(route.optString("checkoutId"))||!context.getString("projectId").equals(route.optString("projectId"))||route.has("runtimeId")&&!context.getString("runtime").equals(route.getString("runtimeId")))throw new SecurityException("原项目引用回执投影身份不同");binding(route);
    }
    private static JSONObject referenceReceiptProjection(JSONObject task,JSONObject operation)throws Exception {
        JSONObject envelope=operation.getJSONObject("receipt"),route=envelope.getJSONObject("projectExecution"),actual=envelope.optJSONObject("executionReceipt");verifyReferenceProjection(task,operation,route);if(actual==null)return null;
        JSONObject backend=actual.getJSONObject("projectExecution");for(String key:new String[]{"authority","command","projectId","checkoutId","taskId","runId","operationId","coordinatorEpoch","planDigest","stepId","intent","sourceDigest","requestDigest"})if(!ProjectSources.canonical(route.get(key)).equals(ProjectSources.canonical(backend.opt(key))))throw new SecurityException("原项目引用后端回执身份不同");
        if(!operation.getString("operationId").equals(actual.optString("id"))||!operation.getString("operationId").equals(actual.optString("operationId"))||!route.getString("command").endsWith(".probe")&&!route.getString("sourceDigest").equals(actual.optString("sourceDigest")))throw new SecurityException("原项目后端执行与引用不同");
        JSONObject result=new JSONObject(actual.toString()).put("sourceDigest",route.getString("sourceDigest")).put("jobId",actual.getString("id")).put("intent",route.getString("intent")).put("stepId",route.getString("stepId")).put("planDigest",route.getString("planDigest")).put("projectId",route.getString("projectId")).put("binding",binding(route)).put("receiptProjectionOnly",true).put("authorityAdded",false).put("replayAllowed",false).put("sourceVerifiedBeforeExec","pre_exec_manifest_verified".equals(actual.optString("sourceCheckStatus"))||"native_checkout_verified".equals(actual.optString("sourceCheckStatus")));
        boolean noChild=dev.pocketengine.features.local.runtime.NativeProcessSupervisor.validatesNoChildReceipt(actual);return result.put("stopConfirmed",actual.optBoolean("cancelRequested")&&actual.optBoolean("receiptReceived")&&(actual.optBoolean("processExited")||noChild)&&actual.optBoolean("processGroupAbsent")&&actual.optBoolean("resourcesReleased"));
    }
    private static JSONObject normalize(JSONObject plan,JSONObject step,JSONObject job,JSONObject binding,JSONObject cursor)throws Exception {
        JSONObject payload=step.getJSONObject("payload");String command=step.getString("localCommand").replace("project.runtime.","termux.");
        if(!plan.getString("checkoutId").equals(job.optString("checkoutId")))throw new SecurityException("job来自其他工作副本");
        String kind=command.equals("termux.prepare")?"project_stage":command.equals("termux.probe")?"tool_probe":command.equals("termux.run")?"command":command.equals("termux.collect")?"workspace_collect":command.equals("writeFile")?"local_edit":command.equals("patchFiles")?"local_patch":command.equals("freezeProjectPackage")?"project_package_freeze":command.equals(ProjectManagedOutput.COMMAND)?ProjectManagedOutput.KIND:"";
        if(kind.isEmpty()||!kind.equals(job.optString("kind")))throw new SecurityException("真实job类型与计划不同");
        if(!command.equals("termux.probe")&&!plan.getString("sourceDigest").equals(job.optString("sourceDigest")))throw new SecurityException("真实job源码版本不同");
        if(command.equals("termux.run")){
            if(("command".equals(plan.optString("intent"))||ProjectGit.plan(plan))&&"managed_process_v1".equals(plan.optString("runtimeId")))requireNativeCommandArguments(plan,step,job,binding);
            else if(!payload.getString("executable").equals(job.optString("requestedExecutable"))||!ProjectSources.canonical(payload.getJSONArray("argv")).equals(ProjectSources.canonical(job.optJSONArray("requestedArgv"))))throw new SecurityException("真实job可执行文件或参数不同");
        }
        JSONObject identity=new JSONObject().put("planDigest",plan.getString("planDigest")).put("stepId",step.getString("id")).put("jobId",job.getString("id")).put("binding",binding);
        String receiptIdentity=ProjectSources.digest(identity),stdout=command.equals("termux.collect")?"":job.optString("stdout"),stderr=job.optString("stderr");int out=0,err=0;
        if(cursor!=null){
            if(!receiptIdentity.equals(cursor.getString("receiptIdentity")))throw new SecurityException("日志cursor不属于此执行回执");
            out=offset(cursor,"stdoutOffset",stdout.length());err=offset(cursor,"stderrOffset",stderr.length());
        }
        JSONObject next=new JSONObject().put("receiptIdentity",receiptIdentity).put("stdoutOffset",stdout.length()).put("stderrOffset",stderr.length());
        boolean guarded="pre_exec_manifest_verified".equals(job.optString("sourceCheckStatus"))||java.util.Set.of("writeFile","patchFiles","freezeProjectPackage","collectProjectOutput").contains(command)&&"native_checkout_verified".equals(job.optString("sourceCheckStatus"));
        JSONObject result=new JSONObject().put("receiptIdentity",receiptIdentity).put("planDigest",plan.getString("planDigest")).put("stepId",step.getString("id")).put("intent",step.getString("id"))
                .put("jobId",job.getString("id")).put("operationId",job.optString("operationId")).put("checkoutId",plan.getString("checkoutId")).put("projectId",plan.getString("projectId"))
                .put("sourceDigest",plan.getString("sourceDigest")).put("binding",binding).put("status",job.getString("status")).put("exitCode",job.opt("exitCode")==null?JSONObject.NULL:job.get("exitCode"))
                .put("receiptReceived",Boolean.TRUE.equals(job.opt("receiptReceived"))).put("cancelRequested",job.has("cancelRequested")&&!Boolean.FALSE.equals(job.opt("cancelRequested"))).put("outputTruncated",job.optBoolean("outputTruncated"))
                .put("sourceCheckStatus",job.optString("sourceCheckStatus",command.equals("termux.run")?"unverified":"not_a_command"))
                .put("preparationPhase",job.optString("preparationPhase")).put("completedBytes",job.optLong("completedBytes",0)).put("totalBytes",job.optLong("totalBytes",0))
                .put("preparationError",job.optString("error")).put("effectStatus",job.optString("effectStatus"))
                .put("preparedEnvironmentIdentity",job.optString("preparedEnvironmentIdentity"))
                .put("sourceVerifiedBeforeExec",guarded).put("atomicSourceFence",false).put("sourceCheckScope","manifest_files_only")
                .put("stdout",stdout).put("stderr",stderr).put("log",new JSONObject().put("stdout",stdout.substring(out)).put("stderr",stderr.substring(err)).put("cursor",next).put("streaming",false))
                .put("artifactReady",job.optBoolean("artifactReady")).put("artifactError",job.optString("artifactError")).put("replayAllowed",false).put("reliableCancellation",false).put("message",job.optString("message"));
        // Preserve observed lifecycle facts. A URL in stdout is never a readiness probe,
        // and a stop request alone is not confirmation that an owned process has exited.
        for(String key:new String[]{"processLaunched","processExited","processGroupAbsent","resourcesReleased","outputComplete","timedOut","ownedWorkerLive","nativeStartObserved","nativeStartAt","launchAckIdentity","runMode","summaryOutputOnly","summaryOutputTruncated","logCursor","outputBytes","storedOutputBytes","timeoutMillis","terminationSignal","launchErrno","nativeResultContract","noChildCreated","launchPhase","observation","cancelDelivery","createdAt","finishedAt","devWatch","sourceGeneration","activeSourceDigest","liveEdit","updatedSourceDigest","fileCommitAttempted","files","appliedFiles","allFilesApplied","partialEffects","crossFileAtomic","updatedSourceDigestUnavailable","terminalMode","terminalNativeContract","terminalOutputMerged","processSessionAbsent","ptyLeaderPid","ptyLeaderStartTicks","ptySessionId","inputByteLimit","terminalRows","terminalColumns","lastTerminalInput","cleanupScope","allDescendantsTerminated","unobservedDetachedDescendantsPossible","argvCount","argvDigest","argumentValuesIncluded","workingDirectory","nativeFileOperation","nativePublicationEntered","nativeOperationCode","processExitCode","packageArtifact","automaticallyExported","reconciledFromOriginalCatalog","attachment","generatedArtifact","path","buildJobId","buildReceiptIdentity","artifactPlanDigest","buildOutputAttribution"})
            if(job.has(key))result.put(key,job.get(key));
        boolean noChild=dev.pocketengine.features.local.runtime.NativeProcessSupervisor.validatesNoChildReceipt(job);
        if(job.has("noChildCreated"))result.put("noChildCreated",noChild);
        result.put("stopConfirmed",Boolean.TRUE.equals(job.opt("cancelRequested"))&&Boolean.TRUE.equals(job.opt("receiptReceived"))&&(Boolean.TRUE.equals(job.opt("processExited"))||noChild)&&Boolean.TRUE.equals(job.opt("processGroupAbsent"))&&Boolean.TRUE.equals(job.opt("resourcesReleased")));
        if(job.optJSONObject("preparation")!=null)result.put("preparation",ProjectSources.copy(job.getJSONObject("preparation")));return result;
    }
    /** Redacted command values remain bound to the original native route and exact approved payload.
     * Count/hash attest request identity only, never exec success, input processing or business effect. */
    private static void requireNativeCommandArguments(JSONObject plan,JSONObject step,JSONObject job,JSONObject binding)throws Exception {
        JSONObject payload=step.getJSONObject("payload");if(!ProjectGit.plan(plan))ProjectCommandSpec.fromPayload(payload);else ProjectGit.verify(plan,plan.getJSONArray("steps"));JSONObject route=job.optJSONObject("projectExecution");
        if(route==null||!"native_host_operation".equals(binding.optString("authority"))||!"native_host_operation".equals(route.optString("authority"))||!"managed_process".equals(job.optString("provider"))||!"project.runtime.run".equals(route.optString("command"))||!"managed_process_v1".equals(route.optString("runtimeId"))||!plan.getString("runtimeIdentity").equals(route.optString("runtimeIdentity")))throw new SecurityException("精确命令缺少原native运行位置与回执绑定");
        verifyLink(plan,step,route);for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"})if(!ProjectSources.canonical(binding.get(key)).equals(ProjectSources.canonical(route.opt(key))))throw new SecurityException("精确命令回执属于其他task/run/epoch");
        if(!plan.getString("projectId").equals(route.optString("projectId"))||!plan.getString("checkoutId").equals(route.optString("checkoutId"))||!job.getString("id").equals(binding.getString("operationId"))||!job.optString("operationId").equals(binding.getString("operationId")))throw new SecurityException("精确命令原job身份不一致");
        JSONArray arguments=ProjectGit.plan(plan)?ProjectGit.arguments(payload):payload.getJSONArray("argv");Object count=job.opt("argvCount");String digest=ProjectSources.digest(new JSONObject().put("argv",arguments));
        if(!payload.getString("executable").equals(job.optString("requestedExecutable"))||!Boolean.FALSE.equals(job.opt("argumentValuesIncluded"))||job.has("argv")||job.has("requestedArgv")||!(count instanceof Integer||count instanceof Long)||((Number)count).longValue()!=arguments.length()||!digest.equals(job.optString("argvDigest")))throw new SecurityException("真实job可执行文件或参数摘要不同");
    }
    private JSONObject artifact(JSONObject input)throws Exception {
        JSONObject receipt=receipt(input);requireBuild(receipt);JSONObject plan=ProjectBuildPlan.verified(input.getJSONObject("plan"));String path=VersionedWorkspace.path(input.getString("path"));
        JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",plan.getString("checkoutId"))));
        if(!plan.getString("sourceDigest").equals(source.getString("sourceDigest")))throw new SecurityException("构建后本地源码已变化，不能用旧回执预览新内容");
        String expected="";JSONArray files=plan.getJSONArray("sourceManifest");for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))expected=files.getJSONObject(i).getString("sha256");
        if(expected.isEmpty())throw new IllegalArgumentException("此文件未在本机源码快照中；Termux生成文件需明确回收后再核验，当前不伪造产物");
        JSONObject actual=local.handle("readFile",new JSONObject().put("checkoutId",plan.getString("checkoutId")).put("path",path));String text=actual.getString("content"),sha=ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8));
        if(!expected.equals(sha)||!sha.equals(actual.getString("sha256")))throw new SecurityException("实际文件字节摘要与构建源码不同");
        return new JSONObject().put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest")).put("buildReceiptIdentity",receipt.getString("receiptIdentity"))
                .put("jobId",receipt.getString("jobId")).put("path",path).put("sha256",sha).put("content",text).put("mimeType",mime(path)).put("truncated",false)
                .put("artifactOrigin","checkout_source").put("generatedArtifact",false).put("receipt",receipt);
    }
    private static void requireBuild(JSONObject receipt)throws Exception {
        Integer exit=cliExit(receipt);
        if(!"build".equals(receipt.getString("intent"))||!"completed".equals(receipt.getString("status"))||!receipt.optBoolean("receiptReceived")||exit==null||exit!=0||!receipt.optBoolean("sourceVerifiedBeforeExec")||receipt.optBoolean("cancelRequested"))throw new SecurityException("需要原确切build的真实成功回执与执行前源码核验");
    }
    private JSONObject repair(JSONObject input)throws Exception {
        JSONObject receipt=receipt(input);
        Integer exit=cliExit(receipt);
        if(!"failed".equals(receipt.getString("status"))||!receipt.optBoolean("receiptReceived")||exit==null||exit==0||receipt.optBoolean("cancelRequested"))throw new SecurityException("只将已确认失败反馈给原task；unknown/取消不能自动修复重派");
        JSONObject feedback=new JSONObject().put("binding",receipt.getJSONObject("binding")).put("jobId",receipt.getString("jobId")).put("receiptIdentity",receipt.getString("receiptIdentity"))
                .put("planDigest",receipt.getString("planDigest")).put("sourceDigest",receipt.getString("sourceDigest")).put("intent",receipt.getString("intent"))
                .put("exitCode",receipt.get("exitCode")).put("stdout",receipt.getString("stdout")).put("stderr",receipt.getString("stderr")).put("outputTruncated",receipt.getBoolean("outputTruncated"))
                .put("untrusted",true).put("requiresNewPlan",true).put("automaticRetry",false);
        return new JSONObject().put("receipt",receipt).put("sameTaskRepair",feedback).put("agentDispatchRequired",true).put("newTaskCreated",false);
    }
    private JSONObject cancel(JSONObject input)throws Exception {
        // Query first to reject a forged/cross-project job. The manual owner uses original termux.cancel.
        JSONObject previous=receipt(input);
        if(!input.has("binding"))return new JSONObject().put("receipt",previous).put("hostAction",new JSONObject().put("feature","local").put("command","termux.cancel").put("payload",new JSONObject().put("jobId",previous.getString("jobId"))))
                .put("dispatched",false).put("message","请由主人通过原有任务停止入口登记；Termux无可靠单任务终止回执");
        throw new SecurityException("Agent取消由原生HostPort/RunControl核对原操作；本接口不会绕过原TaskStore停止控制");
    }
    private HostPort requireHost(){HostPort value=host;if(value==null)throw new SecurityException("自动项目执行尚未挂载原生任务批准HostPort；主人可逐步确认计划中的原termux命令");return value;}
    private JSONObject job(String id)throws Exception{return local.handle("getJob",new JSONObject().put("jobId",id).put("termux",true));}
    private static JSONObject binding(JSONObject raw)throws Exception {
        JSONObject result=new JSONObject();for(String key:new String[]{"taskId","runId","operationId"})result.put(key,ProjectBuildPlan.identity(raw.getString(key)));
        Object epoch=raw.get("coordinatorEpoch");if(!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<1||((Number)epoch).longValue()>Integer.MAX_VALUE)throw new SecurityException("原生run epoch无效");return result.put("coordinatorEpoch",((Number)epoch).intValue());
    }
    public static JSONObject exact(JSONObject plan,JSONObject step)throws Exception {
        JSONObject exact=new JSONObject().put("feature","local").put("command",step.getString("localCommand")).put("payload",ProjectSources.copy(step.getJSONObject("payload")))
                .put("planDigest",plan.getString("planDigest")).put("stepId",step.getString("id")).put("intent",step.getString("id"))
                .put("projectId",plan.getString("projectId")).put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest")).put("requestDigest",ProjectSources.digest(step.getJSONObject("payload")));
        if(step.getString("localCommand").startsWith("project.runtime."))exact.put("runtimeId",plan.getString("runtimeId")).put("runtimeIdentity",plan.getString("runtimeIdentity"));return exact;
    }
    private static int offset(JSONObject cursor,String key,int length)throws Exception {Object raw=cursor.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>length)throw new SecurityException("日志cursor偏移无效");return ((Number)raw).intValue();}
    private static Integer cliExit(JSONObject receipt){Object value=receipt.opt("exitCode");return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=Integer.MIN_VALUE&&((Number)value).longValue()<=Integer.MAX_VALUE?((Number)value).intValue():null;}
    private static String mime(String path){String lower=path.toLowerCase(java.util.Locale.ROOT);return lower.endsWith(".html")||lower.endsWith(".htm")?"text/html":lower.endsWith(".css")?"text/css":lower.endsWith(".js")||lower.endsWith(".mjs")?"text/javascript":lower.endsWith(".json")?"application/json":"text/plain";}
}
