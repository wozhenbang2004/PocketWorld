package dev.pocketengine.features.skills;

import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.*;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** Native owner-reviewed same-UID Skill lane over the ONE original managed
 * runtime/jobs/log/lease and Skill TaskStore operation. It is not an OS sandbox,
 * Companion emulation, downloaded launcher or second execution ledger. */
public final class ManagedSkillBackend implements NativeSkillBackend {
    public static final String BACKEND="phone-managed-skill-v1",INTENT="native_skill",STEP="native_skill_execute";
    public static final int MAX_PACKAGE_BYTES=131072,MAX_PENDING_COPIES=16;
    public static final class Deployment {
        private final JSONObject configuration;final ManagedSkillPolicy.Authority authority;
        private Deployment(JSONObject configuration)throws Exception{this.configuration=ManagedSkillPolicy.checked(configuration);authority=ManagedSkillPolicy.authority(this.configuration);}
        JSONObject configuration()throws Exception{return new JSONObject(configuration.toString());}
        JSONObject metadata()throws Exception{return new JSONObject().put("backendId",BACKEND).put("configuration",configuration()).put("executionLocation","this_phone").put("contract",ManagedSkillPolicy.CONTRACT);}
    }
    static Deployment deployment(JSONObject configuration)throws Exception{return new Deployment(configuration);}
    static Deployment savedDeployment(JSONObject recorded)throws Exception{if(!BACKEND.equals(recorded.optString("backendId"))||!ManagedSkillPolicy.CONTRACT.equals(recorded.optString("contract")))throw new SecurityException("不是原本机Skill执行记录");return deployment(recorded.getJSONObject("configuration"));}
    private final LocalModule local;private final TaskStore tasks;private final Deployment deployment;private final RunControl original;private final String operation;private final File root;private final boolean readOnly;
    private DispatchCheck dispatchCheck;private volatile boolean closed;private ManagedSkillInputs latestInputs;private JSONObject latestTools;private CompanionSkillBackend.ReceiptRef last;
    private final AttachmentBook callbackBook;private final java.util.function.Consumer<String> callbackChanged;private final ManagedScriptCallbackTransport.Factory callbackTransport;
    private volatile ManagedScriptCallbacks callbacks;
    ManagedSkillBackend(Context android,LocalModule local,TaskStore tasks,Deployment deployment,String operation,RunControl original,boolean readOnly)throws Exception {
        this(android,local,tasks,deployment,operation,original,readOnly,ignored->{},new AndroidManagedScriptCallbackTransport());
    }
    ManagedSkillBackend(Context android,LocalModule local,TaskStore tasks,Deployment deployment,String operation,RunControl original,boolean readOnly,
            java.util.function.Consumer<String> callbackChanged,ManagedScriptCallbackTransport.Factory callbackTransport)throws Exception {
        if(operation==null||!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("缺少原Skill操作UUID");this.local=Objects.requireNonNull(local);this.tasks=Objects.requireNonNull(tasks);this.deployment=Objects.requireNonNull(deployment);this.operation=operation;this.original=Objects.requireNonNull(original);this.readOnly=readOnly;root=new File(android.getApplicationContext().getFilesDir(),"local-development/skill-execution-copies").getAbsoluteFile();
        callbackBook=deployment.authority.immediatePrivateArtifacts&&!readOnly?AttachmentRepository.get(android).book:null;this.callbackChanged=Objects.requireNonNull(callbackChanged);this.callbackTransport=Objects.requireNonNull(callbackTransport);
        if(Files.isSymbolicLink(root.toPath())||!root.getParentFile().getCanonicalFile().equals(root.getParentFile()))throw new SecurityException("原本机Skill副本目录已变化");
    }
    @Override public synchronized void bindDispatchCheck(DispatchCheck check){if(dispatchCheck!=null||readOnly)throw new SecurityException("原本机Skill宿主不能重复绑定或从只读记录恢复执行");dispatchCheck=Objects.requireNonNull(check);}
    private void live(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control)throws Exception {if(closed||readOnly||control!=original||dispatchCheck==null||!plan.managedPhone()||plan.nativeManagedAuthority()!=deployment.authority||!operation.equals(grant.operationId))throw new SecurityException("缺少本机主人保留的原Skill执行身份");control.check();if(control.isPaused())throw new SecurityException("原Skill已暂停，不能继续原进程派发或存活检查");dispatchCheck.check(plan,grant,control);}
    private ManagedProjectRuntime runtime()throws Exception{return local.managedSkillRuntimeForHost();}
    private File originalRoot(String operation)throws Exception {if(!operation.matches("[a-f0-9-]{36}")||Files.isSymbolicLink(root.toPath()))throw new SecurityException("原Skill工作副本身份无效");File directory=new File(root,operation);if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))throw new SecurityException("原Skill工作副本不能跳到链接目标");return directory;}
    @Override public synchronized SkillExecution.Environment describe(SkillExecution.Run run,SkillExecution.Request request)throws Exception {
        if(closed||readOnly||!original.matchesHostRun(run.taskId,run.runId,run.epoch))throw new SkillExecution.Unavailable("原本机Skill任务已离开活动运行");original.check();if(original.isPaused())throw new SkillExecution.Unavailable("原Skill任务已暂停");JSONObject q=deployment.configuration();if(!q.getString("runtimeId").equals(request.runtimeId)||!(q.getString("checkoutId").isEmpty()?"native_task_copy":q.getString("checkoutId")).equals(request.workspaceId))throw new SecurityException("本机Skill没有使用主人选定的工作区或解释器");
        JSONObject task=tasks.get(run.taskId,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.has("origin"))throw new SecurityException("本机私人Skill入口不借用群、团队或转交任务权限");
        latestTools=runtime().skillToolsForHost(request.runtimeId);latestInputs=ManagedSkillInputs.capture(local,q.getString("checkoutId"),ManagedSkillPolicy.strings(q.getJSONArray("readPaths")),original);File directory=originalRoot(operation);String guest=latestTools.getString("guestProjectRoot");String base=guest.isEmpty()?directory.getPath():guest;
        return SkillExecution.Environment.managed(deployment.authority,"managed."+latestTools.getString("runtimeIdentity"),request.runtimeId,latestTools.getString("runtimeVersion"),latestTools.getString("runtimeSha256"),ManagedSkillPolicy.strings(latestTools.getJSONArray("interpreterArgv")),request.workspaceId,latestInputs.selectedDigest,base+"/workspace",base+"/skill-package",latestTools.getString("runtimeIdentity"),latestInputs.metadata());
    }
    /** Only native App glue recognizes this exact fixed helper. No feature/model
     * route accepts the intent or chooses cooperative mode by a JSON marker. */
    public static boolean exact(JSONObject exact){return "local".equals(exact.optString("feature"))&&"project.runtime.run".equals(exact.optString("command"))&&INTENT.equals(exact.optString("intent"));}
    public static void requireProgram(JSONObject exact)throws Exception {if(!exact(exact)||!STEP.equals(exact.optString("stepId")))throw new SecurityException("不是原本机Skill执行");JSONObject payload=exact.getJSONObject("payload");JSONArray arguments=payload.getJSONArray("argv");if(!"python3".equals(payload.optString("executable"))||!".".equals(payload.optString("workingDirectory"))||!"pipes".equals(payload.optString("terminalMode"))||!"foreground".equals(payload.optString("runMode"))||!ManagedSkillProgram.identity().equals(payload.optString("managedSkillProgramDigest"))||arguments.length()!=3||!"-I".equals(arguments.getString(0))||!"-c".equals(arguments.getString(1))||!ManagedSkillProgram.source().equals(arguments.getString(2)))throw new SecurityException("本机Skill的固定派发程序或准确argv已变化");}
    @Override public SkillExecution.Receipt submit(SkillExecution.Dispatch dispatch,RunControl control)throws Exception {
        var ref=CompanionSkillBackend.ReceiptRef.from(dispatch.plan,dispatch.grant);last=ref;boolean runtimeEntered=false;try{live(dispatch.plan,dispatch.grant,control);JSONObject review=dispatch.plan.review(),backend=review.getJSONObject("backend"),request=review.getJSONObject("request");ManagedSkillInputs inputs;JSONObject tools; synchronized(this){inputs=latestInputs;tools=new JSONObject(latestTools.toString());}
            if(inputs==null||!inputs.selectedDigest.equals(backend.getString("workspaceRevision"))||!tools.getString("runtimeIdentity").equals(backend.getString("managedRuntimeIdentity")))throw new SecurityException("原本机Skill环境或输入版本已变化");
            Map<String,byte[]> packageFiles=dispatch.packageFiles(),files=new TreeMap<>();int bytes=0;for(var file:packageFiles.entrySet()){SkillBook.path(file.getKey());bytes+=file.getValue().length;if(bytes>MAX_PACKAGE_BYTES)throw new SkillExecution.Unavailable("本机Skill完整包超过128KiB");files.put("skill-package/"+file.getKey(),file.getValue());}
            for(var file:inputs.bytes().entrySet())files.put("workspace/"+file.getKey(),file.getValue());JSONObject dependencies=ManagedSkillDependencies.inspect(packageFiles,request.getString("runtimeId"),deployment.authority.prepareDependencies);if(!SkillExecution.digestJson(dependencies).equals(SkillExecution.digestJson(review.getJSONObject("dependencies"))))throw new SecurityException("实际依赖锁与已批准计划不同");
            File directory=originalRoot(ref.operationId);String executionPath=tools.getString("path");
            if(deployment.authority.immediatePrivateArtifacts){
                ManagedScriptCallbacks session=new ManagedScriptCallbacks(callbackTransport,ref.operationId,control,dispatch.plan.expiresAt,request.getInt("timeoutSeconds"),
                        // Native tool facts only: this is the original Android profile's fixed PRoot -0 view, never request/config JSON.
                        "/workspace".equals(tools.getString("guestProjectRoot")),
                        ()->callbackCurrent(dispatch,ref),path->publishPrivateOutput(dispatch,ref,path));callbacks=session;session.start();
                files.put(".native-skill-callback.json",SkillExecution.canonicalBytes(session.descriptor()));files.put(".native-skill-tools/client.py",ManagedScriptCallbackClient.source());
                files.put(".native-skill-tools/pocketworld",ManagedScriptCallbackClient.command(ManagedSkillPolicy.strings(tools.getJSONArray("bootstrapArgv"))));
                String guest=tools.getString("guestProjectRoot");executionPath=(guest.isEmpty()?directory.getPath():guest)+"/.native-skill-tools:"+executionPath;
            }
            JSONArray checkedFiles=new JSONArray();for(var file:files.entrySet())checkedFiles.put(new JSONObject().put("path",file.getKey()).put("sha256",SkillExecution.sha256(file.getValue())).put("bytes",file.getValue().length));JSONObject job=new JSONObject().put("format","pocketworld.managed-skill-job.v1").put("operationId",ref.operationId).put("planDigest",ref.planDigest).put("taskId",ref.taskId).put("runId",ref.runId).put("epoch",ref.epoch).put("network","possible_same_uid").put("osSandbox",false).put("sourceFiles",checkedFiles).put("request",request).put("runtimeId",request.getString("runtimeId")).put("interpreterArgv",tools.getJSONArray("interpreterArgv")).put("path",executionPath).put("dependencies",dependencies).put("dependencyPreparationAllowed",deployment.authority.prepareDependencies);
            byte[] metadata=SkillExecution.canonicalBytes(job);if(metadata.length>65536)throw new SkillExecution.Unavailable("本次Skill原生输入清单超过64KiB，请收窄文件范围");files.put(".native-skill.json",metadata);if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建本机Skill临时副本");File[] retained=root.listFiles(File::isDirectory);if(retained==null||retained.length>=MAX_PENDING_COPIES)throw new SkillExecution.Unavailable("已保留16份原Skill待核副本，请先核对原任务和存储；没有驱逐未知操作");if(!directory.mkdir())throw new SecurityException("原Skill操作已有副本，只能查询原编号，不覆盖或重建");
            Files.createDirectory(new File(directory,"workspace").toPath());Files.createDirectory(new File(directory,"skill-package").toPath());for(var file:files.entrySet()){live(dispatch.plan,dispatch.grant,control);Path target=within(directory.toPath(),file.getKey());Files.createDirectories(target.getParent());try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){out.write(file.getValue());}}
            // Best effort read-only Unix modes are not advertised as an OS sandbox.
            try(var paths=Files.walk(new File(directory,"skill-package").toPath())){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())){File file=path.toFile();if(!file.setWritable(false,false))throw new IOException("技能副本无法设置预期只读权限");}}
            if(callbacks!=null){for(String path:List.of(".native-skill-callback.json",".native-skill-tools/client.py",".native-skill-tools/pocketworld"))if(!new File(directory,path).setWritable(false,false))throw new IOException("原回调辅助无法设置只读权限");if(!new File(directory,".native-skill-tools/pocketworld").setExecutable(true,false))throw new IOException("原回调入口无法设置执行权限");}
            String project=inputs.projectId.equals("native_task_copy")?"skill-task-"+ref.taskId:inputs.projectId;JSONArray sourceFiles=new JSONArray();for(var file:files.entrySet())sourceFiles.put(new JSONObject().put("path",file.getKey()).put("binary",true).put("contentBase64",Base64.getEncoder().encodeToString(file.getValue())).put("sha256",SkillExecution.sha256(file.getValue())));JSONObject source=new JSONObject().put("checkout",new JSONObject().put("id",ref.operationId).put("projectId",project)).put("files",sourceFiles);String sourceDigest=ProjectSources.describe(source).getString("sourceDigest");JSONObject payload=new JSONObject().put("checkoutId",ref.operationId).put("expectedSourceDigest",sourceDigest).put("executable","python3").put("argv",new JSONArray().put("-I").put("-c").put(ManagedSkillProgram.source())).put("workingDirectory",".").put("terminalMode","pipes").put("runMode","foreground").put("timeoutMillis",request.getLong("timeoutSeconds")*1000).put("managedSkillProgramDigest",ManagedSkillProgram.identity());JSONObject exact=new JSONObject().put("feature","local").put("command","project.runtime.run").put("projectId",project).put("checkoutId",ref.operationId).put("planDigest",ref.planDigest).put("stepId",STEP).put("intent",INTENT).put("sourceDigest",sourceDigest).put("requestDigest",ProjectSources.digest(payload)).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("runtimeIdentity",tools.getString("runtimeIdentity")).put("payload",payload);JSONObject binding=new JSONObject().put("taskId",ref.taskId).put("runId",ref.runId).put("operationId",ref.operationId).put("coordinatorEpoch",ref.epoch);
            requireProgram(exact);live(dispatch.plan,dispatch.grant,control);tasks.bindManagedSkillExecutionForHost(dispatch.plan,dispatch.grant,exact,binding,control);RuntimeInvocation invocation=new RuntimeInvocation(exact,binding,source,control,()->live(dispatch.plan,dispatch.grant,control),new RuntimeWorkspace(directory,project,ref.operationId));runtimeEntered=true;runtime().dispatch(invocation);JSONObject actual=runtime().query(invocation.executionRef()).json();tasks.recordManagedSkillBackendReceiptForHost(actual);return observeReceipt(ref,actual);
        }catch(Exception failed){stopCallbacks();if(runtimeEntered){try{requestCancel(ref);}catch(Exception unknown){}return SkillExecution.Receipt.unknown(ref.operationId);}return new SkillExecution.Receipt(ref.operationId,"",SkillExecution.Status.REJECTED,null,null,false);}
    }
    public static SkillExecution.Receipt receipt(CompanionSkillBackend.ReceiptRef ref,JSONObject actual)throws Exception {
        JSONObject link=actual.getJSONObject("projectExecution");if(!ref.taskId.equals(link.optString("taskId"))||!ref.runId.equals(link.optString("runId"))||ref.epoch!=link.optInt("coordinatorEpoch")||!ref.operationId.equals(actual.optString("id"))||!ref.planDigest.equals(link.optString("planDigest"))||!INTENT.equals(link.optString("intent")))throw new SecurityException("本机Skill回执不是原任务/运行/计划");byte[] output=(actual.optString("stdout")+actual.optString("stderr")).getBytes(StandardCharsets.UTF_8);boolean cropped=actual.optBoolean("summaryOutputTruncated")||actual.optBoolean("outputTruncated")||output.length>ref.maxOutputBytes;if(output.length>ref.maxOutputBytes)output=Arrays.copyOf(output,ref.maxOutputBytes);SkillExecution.Status status=SkillExecution.Status.UNKNOWN;Integer exit=null;
        if(Boolean.TRUE.equals(actual.opt("receiptReceived"))&&Boolean.TRUE.equals(actual.opt("resourcesReleased"))){if(NativeProcessSupervisor.validatesNoChildReceipt(actual))status=SkillExecution.Status.REJECTED;else if(Boolean.TRUE.equals(actual.opt("processExited"))&&NativeProcessSupervisor.knownOrdinaryExitContract(actual.optString("nativeResultContract"))){Object value=actual.opt("exitCode");if(value instanceof Integer||value instanceof Long)exit=((Number)value).intValue();status=actual.optBoolean("timedOut")?SkillExecution.Status.TIMED_OUT:actual.optBoolean("cancelRequested")?SkillExecution.Status.CANCELLED:Integer.valueOf(0).equals(exit)?SkillExecution.Status.COMPLETED:SkillExecution.Status.FAILED;}}
        else if(Boolean.TRUE.equals(actual.opt("ownedWorkerLive"))&&"owned_worker_live".equals(actual.optString("observation"))){
            if("awaiting_receipt".equals(actual.optString("status"))&&!Boolean.TRUE.equals(actual.opt("nativeStartObserved")))status=SkillExecution.Status.ACCEPTED;
            else if(actual.optString("status").matches("running|awaiting_resource_release")&&Boolean.TRUE.equals(actual.opt("nativeStartObserved"))&&Boolean.TRUE.equals(actual.opt("processLaunched"))&&actual.optString("launchAckIdentity").matches("[a-f0-9]{64}"))status=SkillExecution.Status.RUNNING;
        }
        return new SkillExecution.Receipt(ref.operationId,ref.operationId,status,exit,output,cropped);
    }
    private RuntimeExecutionRef originalJob(CompanionSkillBackend.ReceiptRef ref)throws Exception {JSONObject actual=tasks.managedSkillExecutionForHost(ref);return RuntimeExecutionRef.fromHost(actual.getJSONObject("exact"),actual.getJSONObject("binding"));}
    private SkillExecution.Receipt observeReceipt(CompanionSkillBackend.ReceiptRef ref,JSONObject actual)throws Exception {SkillExecution.Receipt receipt=receipt(ref,actual);if(receipt.status!=SkillExecution.Status.ACCEPTED&&receipt.status!=SkillExecution.Status.RUNNING)stopCallbacks();return receipt;}
    @Override public SkillExecution.Receipt query(CompanionSkillBackend.ReceiptRef ref)throws Exception {if(closed)throw new IOException("原Skill观察已关闭");last=ref;try{JSONObject actual=runtime().query(originalJob(ref)).json();tasks.recordManagedSkillBackendReceiptForHost(actual);return observeReceipt(ref,actual);}catch(Exception unavailable){stopCallbacks();return SkillExecution.Receipt.unknown(ref.operationId);}}
    @Override public void requestCancel(CompanionSkillBackend.ReceiptRef ref)throws Exception {stopCallbacks();runtime().requestCancel(originalJob(ref));}
    private void callbackCurrent(SkillExecution.Dispatch dispatch,CompanionSkillBackend.ReceiptRef ref)throws Exception {
        long until=System.nanoTime()+2_000_000_000L;
        while(true){
            live(dispatch.plan,dispatch.grant,original);ManagedProjectRuntime runtime=runtime();if(!runtime.identity().equals(dispatch.plan.review().getJSONObject("backend").getString("managedRuntimeIdentity")))throw new SecurityException("script_callback_runtime_changed");
            JSONObject actual=runtime.query(originalJob(ref)).json();
            boolean owner=Boolean.TRUE.equals(actual.opt("ownedWorkerLive"))&&"owned_worker_live".equals(actual.optString("observation"));
            if(owner&&"running".equals(actual.optString("status"))&&Boolean.TRUE.equals(actual.opt("nativeStartObserved"))&&Boolean.TRUE.equals(actual.opt("processLaunched"))
                    &&!Boolean.TRUE.equals(actual.opt("processExited"))&&!Boolean.TRUE.equals(actual.opt("resourcesReleased"))&&!Boolean.TRUE.equals(actual.opt("cancelRequested"))
                    &&"pre_exec_manifest_verified".equals(actual.optString("sourceCheckStatus"))&&actual.optString("launchAckIdentity").matches("[a-f0-9]{64}"))return;
            // A genuine child can reach the callback before the original JNI start ACK is
            // published. Wait only for that SAME original job's observation, never launch/retry.
            if(!owner||!"awaiting_receipt".equals(actual.optString("status"))||actual.optBoolean("nativeStartObserved")||System.nanoTime()>until)throw new SecurityException("script_callback_original_job_not_running");
            Thread.sleep(10);
        }
    }
    private JSONObject publishPrivateOutput(SkillExecution.Dispatch dispatch,CompanionSkillBackend.ReceiptRef ref,String path)throws Exception {
        ManagedScriptCallbacks session=callbacks;if(session==null)return ManagedScriptCallbackWire.rejected();session.checkCurrent();
        JSONObject request=dispatch.plan.review().getJSONObject("request");if(!ManagedSkillPolicy.strings(request.getJSONArray("outputPaths")).contains(path))return ManagedScriptCallbackWire.rejected();
        File workspace=new File(originalRoot(ref.operationId),"workspace");Path candidate=within(workspace.toPath(),path);long limit=request.getLong("maxOutputBytes");
        if(!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)||Files.size(candidate)>limit)return ManagedScriptCallbackWire.rejected();
        // Reuse the actual native generated-output reader: no links/special files,
        // stable identity/size/mtime and a second full SHA check, not a script claim.
        byte[] bytes=ProjectManagedOutput.bytes(new RuntimeWorkspace(workspace,"skill-output-"+ref.taskId,ref.operationId),path,original);
        try{
            if(bytes.length>limit)return ManagedScriptCallbackWire.rejected();session.checkCurrent();
            ManagedScriptArtifactPublisher publisher=new ManagedScriptArtifactPublisher(callbackBook,(approved,sha,size)->{
                session.checkCurrent();var publication=tasks.claimScriptArtifactPublication(dispatch.plan,dispatch.grant,approved,sha,size,original);
                return guardedPublication(session,publication);
            },original);
            try{return publisher.share(path,bytes);}finally{try{callbackChanged.accept(ref.taskId);}catch(RuntimeException noticeFailed){/* An invalidation hint is not a publication fact. */}}
        }finally{Arrays.fill(bytes,(byte)0);}
    }
    /** Keep native job/pause/source currentness at every original Publisher stage,
     * without a new claim/store or holding a socket lock over SQL/Book work. */
    private static ManagedScriptArtifactPublisher.OriginalPublication guardedPublication(ManagedScriptCallbacks session,ManagedScriptArtifactPublisher.OriginalPublication original){
        return new ManagedScriptArtifactPublisher.OriginalPublication(){
            public String taskId(){return original.taskId();}public String runId(){return original.runId();}public String operationId(){return original.operationId();}public String planDigest(){return original.planDigest();}
            public String path(){return original.path();}public String role(){return original.role();}public String sha256(){return original.sha256();}public String attachmentId(){return original.attachmentId();}public long bytes(){return original.bytes();}
            public boolean mayImport(){return original.mayImport();}
            public void checkCurrent()throws Exception{session.checkCurrent();original.checkCurrent();}
            public JSONObject finish(AttachmentBook.VerifiedMetadata actual)throws Exception{session.checkCurrent();return original.finish(actual);}
        };
    }
    private void stopCallbacks(){ManagedScriptCallbacks current=callbacks;if(current!=null)current.close();}
    @Override public SkillExecution.Receipt cancelOriginal(CompanionSkillBackend.ReceiptRef ref)throws Exception {requestCancel(ref);long until=System.nanoTime()+5_000_000_000L;SkillExecution.Receipt actual;do{actual=query(ref);if(SkillExecutionRecords.terminal(actual.status))return actual;Thread.sleep(50);}while(System.nanoTime()<until);return actual;}
    @Override public CompanionSkillBackend.ArtifactInventory artifacts(CompanionSkillBackend.ReceiptRef ref)throws Exception {
        JSONObject original=tasks.readSkillExecution(ref.taskId,ref.operationId);SkillExecution.Receipt state=query(ref);if(!Set.of(SkillExecution.Status.COMPLETED,SkillExecution.Status.FAILED,SkillExecution.Status.TIMED_OUT).contains(state.status))throw new SecurityException("没有原本机Skill已释放的终态文件结果");JSONArray requested=original.getJSONObject("review").getJSONObject("request").getJSONArray("outputPaths");File directory=originalRoot(ref.operationId);List<CompanionSkillBackend.Artifact> files=new ArrayList<>();List<String> missing=new ArrayList<>();long total=0;boolean truncated=false;
        for(int i=0;i<requested.length();i++){String path=requested.getString(i);Path file=within(new File(directory,"workspace").toPath(),path);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){missing.add(path);continue;}BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!before.isRegularFile()||before.size()>ref.maxOutputBytes||total+before.size()>ref.maxOutputBytes){missing.add(path);truncated=true;continue;}byte[] bytes;try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] page=new byte[8192];for(int count;(count=input.read(page))>=0;){if(count==0)continue;if(buffer.size()+count>ref.maxOutputBytes)throw new SecurityException("原Skill结果超过精确输出预算");buffer.write(page,0,count);}bytes=buffer.toByteArray();}BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(bytes.length!=before.size()||!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||before.size()!=after.size())throw new SecurityException("原Skill结果文件在捕获中变化，未采用半份文件");total+=bytes.length;files.add(new CompanionSkillBackend.Artifact(path,SkillExecution.sha256(bytes),bytes));}
        return new CompanionSkillBackend.ArtifactInventory(files,missing,truncated);
    }
    public JSONObject readLog(CompanionSkillBackend.ReceiptRef ref,long cursor,int maxBytes)throws Exception{return runtime().readLog(originalJob(ref),cursor,maxBytes).put("executionLocation","this_phone").put("originalOperationId",ref.operationId);}
    private static Path within(Path root,String relative)throws Exception {dev.pocketengine.features.workspace.VersionedWorkspace.path(relative);StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(relative));Path current=root;if(Files.isSymbolicLink(root))throw new SecurityException("原Skill副本根已变化");for(String part:relative.split("/")){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new SecurityException("原Skill文件路径含链接，未跟随读取或覆盖");}if(!current.normalize().startsWith(root)||!current.getParent().toFile().getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath()))throw new SecurityException("原Skill文件越出工作副本");return current;}
    @Override public void close(){closed=true;stopCallbacks();/* Original managed runtime/job/receipts remain App-owned. No unknown process or directory is silently removed. */}
}
