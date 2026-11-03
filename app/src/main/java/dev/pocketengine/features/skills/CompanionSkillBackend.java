package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.remote.RemoteTransport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Canonical W04 Backend using the existing RemoteModule file/command/job protocol and a pinned
 * owner-installed Linux launcher. No additional product task, retry, database or model CLI. */
public final class CompanionSkillBackend implements NativeSkillBackend {
    public static final String BACKEND="companion-skill-canonical-v1";
    public static final int MAX_PACKAGE_BYTES=131072;
    private static final long HANDOFF_NANOS=TimeUnit.SECONDS.toNanos(5);
    private static final String POLICY=".pwskill-policy.json",READY=".pwskill-ready.json";

    /** Trusted native installation state. Never bootstrap these pins from a downloaded claim. */
    public static final class Deployment {
        public final String profileId,serviceBase,environmentId,installationId,commandAlias,policySha256,readySha256,helperSha256;
        public Deployment(String profileId,String serviceBase,String environmentId,String installationId,String commandAlias,
                          String policySha256,String readySha256,String helperSha256)throws Exception {
            this.profileId=id(profileId);this.environmentId=id(environmentId);this.commandAlias=id(commandAlias);this.installationId=identifier(installationId);
            this.serviceBase=RemoteTransport.validateBase(serviceBase).toString().replaceAll("/+$","");
            this.policySha256=sha(policySha256);this.readySha256=sha(readySha256);this.helperSha256=sha(helperSha256);
        }
    }
    /** Durable native query/cancel route only; cannot reconstruct or submit a Plan/Dispatch. */
    public static final class ReceiptRef {
        public final String ownerId,spaceId,taskId,runId,operationId,planDigest,environmentBinding,installationId,workspaceId,workspaceRevision;
        public final int epoch,maxOutputBytes,maxResultChars;
        public ReceiptRef(String ownerId,String spaceId,String taskId,String runId,int epoch,String operationId,String planDigest,
                          String environmentBinding,String installationId,String workspaceId,String workspaceRevision,int maxOutputBytes,int maxResultChars){
            this.ownerId=identity(ownerId);this.spaceId=identity(spaceId);this.taskId=uuid(taskId);this.runId=uuid(runId);this.operationId=uuid(operationId);
            this.epoch=epoch;this.planDigest=sha(planDigest);this.environmentBinding=sha(environmentBinding);this.installationId=identifier(installationId);
            this.workspaceId=identifier(workspaceId);this.workspaceRevision=identifier(workspaceRevision);this.maxOutputBytes=maxOutputBytes;this.maxResultChars=maxResultChars;
            if(epoch<1||maxOutputBytes<1||maxOutputBytes>65536||maxResultChars<2048||maxResultChars>32000)throw new IllegalArgumentException("原执行回执预算无效");
        }
        public static ReceiptRef from(SkillExecution.Plan plan,SkillExecution.Grant grant)throws Exception {
            JSONObject p=plan.review(),b=p.getJSONObject("backend"),q=p.getJSONObject("request");SkillExecution.Run run=plan.run();
            if(!plan.digest.equals(grant.planDigest)||!run.taskId.equals(grant.taskId)||!run.runId.equals(grant.runId)||run.epoch!=grant.epoch
                    ||!SkillExecution.environmentBinding(plan).equals(grant.environmentBinding)||!(BACKEND.equals(b.getString("backendId"))||plan.managedPhone()&&ManagedSkillBackend.BACKEND.equals(b.getString("backendId")))
                    ||!"isolated_copy".equals(b.getString("workspaceMode")))throw new SecurityException("回执路由与原计划/授权不一致");
            return new ReceiptRef(run.ownerId,run.spaceId,run.taskId,run.runId,run.epoch,grant.operationId,plan.digest,grant.environmentBinding,
                    b.getString("installationId"),b.getString("workspaceId"),b.getString("workspaceRevision"),q.getInt("maxOutputBytes"),q.getInt("maxResultChars"));
        }
    }
    private final EngineModule remote;
    private final Deployment deployment;
    private final LongSupplier clock;
    interface DispatchCheck extends NativeSkillBackend.DispatchCheck {}
    private volatile NativeSkillBackend.DispatchCheck dispatchCheck;
    private final ThreadPoolExecutor io=executor(2,16,"skill-companion-wire"),cancellations=executor(1,32,"skill-companion-control");
    private volatile boolean closed;
    public CompanionSkillBackend(EngineModule remote,Deployment deployment,LongSupplier clock){
        if(remote==null||!"remote".equals(remote.id()))throw new IllegalArgumentException("需要现有RemoteModule");
        this.remote=remote;this.deployment=Objects.requireNonNull(deployment);this.clock=Objects.requireNonNull(clock);
    }
    /** One native HostPort binds the live TaskStore check; no cached approval flag. */
    public synchronized void bindDispatchCheck(NativeSkillBackend.DispatchCheck check){if(dispatchCheck!=null)throw new IllegalStateException("执行端已绑定其原任务宿主");dispatchCheck=Objects.requireNonNull(check);}
    synchronized void bindDispatchCheck(DispatchCheck check){bindDispatchCheck((NativeSkillBackend.DispatchCheck)check);}
    private static ThreadPoolExecutor executor(int threads,int queued,String name){return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queued),r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());}
    private <T>T bounded(Callable<T> operation,long until)throws Exception {
        if(closed)throw new IOException("脚本连接已关闭");Future<T> future=io.submit(operation);
        try{return future.get(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(Exception uncertain){future.cancel(true);throw uncertain;}
    }
    @Override public SkillExecution.Environment describe(SkillExecution.Run run,SkillExecution.Request request)throws Exception {
        long until=System.nanoTime()+HANDOFF_NANOS;
        try{return bounded(()->describeWire(run,request,until),until);}
        catch(Exception unsupported){Throwable cause=unsupported;while(cause instanceof java.util.concurrent.ExecutionException&&cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof SkillExecution.Unavailable)throw (SkillExecution.Unavailable)cause;
            throw new SkillExecution.Unavailable("原电脑没有可核对的固定隔离部署，或原连接/固定证据已变化");}
    }
    private SkillExecution.Environment describeWire(SkillExecution.Run run,SkillExecution.Request request,long until)throws Exception {
        JSONArray envs=call("environments",base(),until).getJSONArray("environments");boolean supported=false;
        for(int i=0;i<envs.length();i++){JSONObject e=envs.getJSONObject(i);if(!deployment.environmentId.equals(e.optString("id"))||!"local".equals(e.optString("kind"))||!e.optBoolean("writeAllowed"))continue;
            JSONArray workers=e.getJSONArray("workers");for(int j=0;j<workers.length();j++)if("command".equals(workers.getJSONObject(j).optString("id"))&&workers.getJSONObject(j).optBoolean("available"))supported=true;}
        if(!supported)throw new SkillExecution.Unavailable("需要POSIX本地固定命令及传输区写入能力");
        JSONObject policy=checkedFile(POLICY,deployment.policySha256,until),ready=checkedFile(READY,deployment.readySha256,until),q=request.json();
        if(!"pocketworld.skill-canonical-bwrap.v1".equals(ready.optString("format"))||!ready.optBoolean("available")
                ||!deployment.installationId.equals(ready.optString("installationId"))||!deployment.installationId.equals(policy.optString("installationId"))
                ||!deployment.policySha256.equals(ready.optString("policySha256"))||!deployment.helperSha256.equals(ready.optString("helperSha256"))
                ||ready.optLong("expiresAt")<=clock.getAsLong()||policy.optLong("grantExpiresAt")<=clock.getAsLong()
                ||!run.ownerId.equals(policy.optString("ownerId"))||!run.spaceId.equals(policy.optString("spaceId")))throw new SkillExecution.Unavailable("固定隔离检查、安装或授权已变化/过期");
        for(String name:List.of("readPaths","writePaths","outputPaths"))if(!q.getJSONArray(name).toString().equals(policy.getJSONArray(name).toString()))throw new SkillExecution.Unavailable("当前部署只支持其已核对的精确路径布局");
        if(!request.workingDirectory.equals(policy.getString("workingDirectory"))||!request.runtimeId.equals(policy.getString("runtime"))
                ||!request.workspaceId.equals(policy.getString("workspaceId"))||request.timeoutSeconds>policy.getInt("maxSeconds")||request.maxOutputBytes>policy.getInt("maxOutputBytes"))throw new SkillExecution.Unavailable("工作目录、运行时或预算超出固定部署");
        if(!"single_process".equals(policy.getString("processPolicy")))throw new SkillExecution.Unavailable("当前部署必须固定为单进程运行限制");
        for(String name:List.of("runtimeSha256","runtimeVersion","runtimeImageDigest"))if(!policy.get(name).equals(ready.get(name)))throw new SkillExecution.Unavailable("运行时二进制、版本或镜像证据变化");
        return new SkillExecution.Environment(BACKEND,deployment.installationId,request.runtimeId,policy.getString("runtimeVersion"),policy.getString("runtimeSha256"),strings(policy.getJSONArray("launcher")),
                request.workspaceId,ready.getString("workspaceRevision"),"/skill-workspace","/skill-package","sha256:"+deployment.policySha256,Set.of(SkillExecution.Fence.values()),"isolated_copy",policy.getString("runtimeImageDigest"),"single_process");
    }
    @Override public SkillExecution.Receipt submit(SkillExecution.Dispatch dispatch,RunControl control)throws Exception {
        long until=System.nanoTime()+HANDOFF_NANOS;ReceiptRef ref=ReceiptRef.from(dispatch.plan,dispatch.grant);
        try{return bounded(()->submitWire(dispatch,control,until),until);}
        catch(Exception uncertain){return SkillExecution.Receipt.unknown(ref.operationId);}
    }
    private SkillExecution.Receipt submitWire(SkillExecution.Dispatch dispatch,RunControl control,long until)throws Exception {
        ReceiptRef ref=ReceiptRef.from(dispatch.plan,dispatch.grant);original(ref);long pause=control.pauseRevision();checkpoint(dispatch,control,pause,until);
        JSONObject bundle=bundle(dispatch);byte[] raw=bundle.toString().getBytes(StandardCharsets.UTF_8);if(raw.length>262144)throw new IOException("完整执行传输包超过256KiB");
        String key=operationKey(ref),path=stagePath(key),fileOp="sks_"+key;
        JSONObject old=call("reconcileOperation",base().put("operationId","skj_"+key),until);
        if(!"not_found".equals(old.optString("status")))throw new IOException("原任务已存在或未知；仅核对");
        if(!"not_found".equals(call("reconcileFileWrite",base().put("operationId",fileOp),until).optString("status")))throw new IOException("原上传已存在或未知；禁止重传/续发");
        checkpoint(dispatch,control,pause,until);
        JSONObject written=call("writeFile",workspace().put("operationId",fileOp).put("path",path).put("expectedVersion","absent").put("content",new String(raw,StandardCharsets.UTF_8)),until);
        if(!"completed".equals(written.optString("status"))||!written.optBoolean("verified")||!fileOp.equals(written.optString("operationId"))
                ||!deployment.environmentId.equals(written.optString("environmentId"))||!path.equals(written.optString("path"))||!("sha256:"+SkillExecution.sha256(raw)).equals(written.optString("version")))throw new IOException("上传没有精确字节回执");
        checkpoint(dispatch,control,pause,until);readBundle(ref,SkillExecution.sha256(raw),until);checkpoint(dispatch,control,pause,until);
        JSONObject job=call("submitJob",workspace().put("worker","command").put("operationId","skj_"+key).put("timeoutSeconds",dispatch.plan.review().getJSONObject("request").getInt("timeoutSeconds")+5)
                .put("argv",new JSONArray().put(deployment.commandAlias).put("run").put(path).put(SkillExecution.sha256(raw))),until);
        return receipt(ref,job);
    }
    private JSONObject bundle(SkillExecution.Dispatch dispatch)throws Exception {
        JSONObject p=dispatch.plan.review(),resources=new JSONObject();Map<String,byte[]> files=dispatch.packageFiles();JSONArray expected=p.getJSONArray("resources");int total=0;
        if(expected.length()!=files.size())throw new SecurityException("完整包资源集合变化");
        for(int i=0;i<expected.length();i++){JSONObject e=expected.getJSONObject(i);byte[] value=files.get(e.getString("path"));if(value==null||value.length!=e.getInt("bytes")||!SkillExecution.sha256(value).equals(e.getString("sha256")))throw new SecurityException("包资源字节变化");
            total+=value.length;if(total>MAX_PACKAGE_BYTES)throw new SkillExecution.Unavailable("本部署只支持128KiB完整包");resources.put(e.getString("path"),Base64.getEncoder().encodeToString(value));}
        SkillExecution.Grant g=dispatch.grant;
        JSONObject grant=new JSONObject().put("operationId",g.operationId).put("approvalId",g.approvalId).put("backendGrantId",g.backendGrantId).put("planDigest",g.planDigest)
                .put("taskId",g.taskId).put("runId",g.runId).put("epoch",g.epoch).put("environmentBinding",g.environmentBinding).put("expiresAt",g.expiresAt);
        return new JSONObject().put("format","pocketworld.skill-canonical-bundle.v1").put("review",p).put("grant",grant).put("resources",resources)
                .put("canonicalReviewBase64",Base64.getEncoder().encodeToString(dispatch.plan.canonicalReviewBytes()))
                .put("canonicalBackendBase64",Base64.getEncoder().encodeToString(SkillExecution.canonicalBytes(p.getJSONObject("backend"))))
                .put("operationKey",operationKey(ReceiptRef.from(dispatch.plan,g)));
    }
    public SkillExecution.Receipt query(ReceiptRef ref){
        long until=System.nanoTime()+HANDOFF_NANOS;
        try{return bounded(()->{original(ref);readBundle(ref,null,until);return receipt(ref,call("reconcileOperation",base().put("operationId","skj_"+operationKey(ref)),until));},until);}
        catch(Exception uncertain){return SkillExecution.Receipt.unknown(ref.operationId);}
    }
    /** Call after the existing TaskStore durably marks cancelPending. No network on RunControl thread. */
    public void requestCancel(ReceiptRef ref){original(ref);cancellations.execute(()->{
        long until=System.nanoTime()+HANDOFF_NANOS;
        try{cancelWire(ref,until);}catch(Exception unknown){/* Native durable cancelPending remains for original-operation cleanup. */}
    });}
    /** Native worker/restart path, after TaskStore's durable cancelPending mark; never launches a job. */
    public SkillExecution.Receipt cancelOriginal(ReceiptRef ref){
        long until=System.nanoTime()+HANDOFF_NANOS;
        try{return bounded(()->{original(ref);SkillExecution.Receipt actual=cancelWire(ref,until);
            while((actual.status==SkillExecution.Status.ACCEPTED||actual.status==SkillExecution.Status.RUNNING)&&System.nanoTime()<until){
                Thread.sleep(50);actual=receipt(ref,call("getJob",base().put("jobId",actual.backendReceiptId),until));
            }return actual;
        },until);}catch(Exception unknown){return SkillExecution.Receipt.unknown(ref.operationId);}
    }
    private SkillExecution.Receipt cancelWire(ReceiptRef ref,long until)throws Exception {
        String key=operationKey(ref);try{call("writeFile",workspace().put("operationId","skc_"+key).put("path",cancelPath(key)).put("expectedVersion","absent").put("content",ref.planDigest),until);}catch(Exception markerUnknown){/* The original file-write operation is never reissued under a new identity. */}
        JSONObject job=call("reconcileOperation",base().put("operationId","skj_"+key),until);validateJob(ref,job);
        JSONObject target=base().put("jobId",job.getString("id")).put("jobOperationId","skj_"+key);if(job.has("serviceBase"))target.put("serviceBase",job.getString("serviceBase"));call("cancelJob",target,until);return receipt(ref,call("getJob",target,until));
    }
    public static final class Artifact {
        public final String path,sha256;private final byte[] bytes;
        Artifact(String path,String sha256,byte[] bytes){this.path=path;this.sha256=sha256;this.bytes=bytes.clone();}
        public byte[] bytes(){return bytes.clone();}public boolean untrusted(){return true;}public boolean imported(){return false;}
    }
    public static final class ArtifactInventory {
        public final List<Artifact> files;public final List<String> missingPaths;public final boolean truncated;
        ArtifactInventory(List<Artifact> files,List<String> missingPaths,boolean truncated){this.files=List.copyOf(files);this.missingPaths=List.copyOf(missingPaths);this.truncated=truncated;}
        public boolean canonicalWorkspaceModified(){return false;}
    }
    public ArtifactInventory artifacts(ReceiptRef ref)throws Exception {
        long until=System.nanoTime()+HANDOFF_NANOS;return bounded(()->{
            original(ref);JSONObject bundle=readBundle(ref,null,until),job=call("reconcileOperation",base().put("operationId","skj_"+operationKey(ref)),until);
            SkillExecution.Receipt checked=receipt(ref,job);if(!Set.of(SkillExecution.Status.COMPLETED,SkillExecution.Status.FAILED,SkillExecution.Status.TIMED_OUT).contains(checked.status))throw new IOException("没有终态文件成果");
            JSONObject result=new JSONObject(job.getString("output"));String path=".pwskill-result-"+operationKey(ref)+".json";
            if(!path.equals(result.getString("artifactManifestPath")))throw new SecurityException("非原操作文件成果路径");byte[] raw=readFile(path,until);
            if(!SkillExecution.sha256(raw).equals(result.getString("artifactManifestSha256")))throw new SecurityException("文件成果清单被替换");
            JSONObject m=new JSONObject(utf8(raw));checkResultIdentity(ref,m,"pocketworld.skill-artifacts.v1");JSONArray files=m.getJSONArray("files"),outputs=bundle.getJSONObject("review").getJSONObject("request").getJSONArray("outputPaths");
            if(!outputs.toString().equals(m.getJSONArray("outputPaths").toString()))throw new SecurityException("成果不是原批准的精确输出列表");
            if(files.length()>8)throw new SecurityException("输出目标超过原8个精确目标");List<Artifact> out=new ArrayList<>();Set<String> seen=new java.util.HashSet<>();int total=checked.outputBytes().length;
            for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String name=f.getString("path");if(!strings(outputs).contains(name)||!seen.add(name))throw new SecurityException("成果不是原精确输出文件");
                byte[] data=Base64.getDecoder().decode(f.getString("base64"));total+=data.length;if(total>ref.maxOutputBytes||data.length!=f.getInt("bytes")||!SkillExecution.sha256(data).equals(f.getString("sha256")))throw new SecurityException("成果字节/预算变化");out.add(new Artifact(name,f.getString("sha256"),data));}
            List<String> missing=strings(m.getJSONArray("missingPaths"));for(String name:missing)if(!strings(outputs).contains(name)||!seen.add(name))throw new SecurityException("缺失成果列表越界或重复");
            return new ArtifactInventory(out,missing,m.getBoolean("truncated"));
        },until);
    }
    private SkillExecution.Receipt receipt(ReceiptRef ref,JSONObject job)throws Exception {
        validateJob(ref,job);String state=job.optString("status"),id=job.getString("id");
        if(state.equals("queued")||state.equals("running")||state.equals("stopping"))return new SkillExecution.Receipt(ref.operationId,id,state.equals("queued")?SkillExecution.Status.ACCEPTED:SkillExecution.Status.RUNNING,null,null,false);
        if(state.equals("cancelled")&&!job.optBoolean("timedOut"))return new SkillExecution.Receipt(ref.operationId,id,SkillExecution.Status.CANCELLED,null,null,false);
        if(!state.equals("completed")||job.optBoolean("outputTruncated")||job.optBoolean("timedOut")||job.optInt("exitCode",-1)!=0)throw new IOException("启动器完整终态不明");
        JSONObject r=new JSONObject(job.getString("output"));checkResultIdentity(ref,r,"pocketworld.skill-receipt.v1");byte[] output=Base64.getDecoder().decode(r.getString("outputBase64"));
        if(output.length>ref.maxOutputBytes||!SkillExecution.sha256(output).equals(r.getString("outputSha256")))throw new SecurityException("输出原字节/预算不一致");
        SkillExecution.Status status=SkillExecution.Status.valueOf(r.getString("state"));if(Set.of(SkillExecution.Status.ACCEPTED,SkillExecution.Status.RUNNING).contains(status))throw new IOException("非启动器终态");
        return new SkillExecution.Receipt(ref.operationId,id,status,r.isNull("exitCode")?null:r.getInt("exitCode"),output,r.getBoolean("truncated"));
    }
    private void checkResultIdentity(ReceiptRef ref,JSONObject value,String format)throws Exception {
        if(!format.equals(value.optString("format"))||!ref.operationId.equals(value.optString("operationId"))||!ref.planDigest.equals(value.optString("planDigest"))
                ||!ref.environmentBinding.equals(value.optString("environmentBinding"))||!ref.installationId.equals(value.optString("installationId"))
                ||!operationKey(ref).equals(value.optString("operationKey"))||!"isolated_copy".equals(value.optString("workspaceMode"))||value.optBoolean("canonicalWorkspaceModified",true))throw new SecurityException("回执不是原计划/安装/副本");
    }
    private void validateJob(ReceiptRef ref,JSONObject job)throws Exception {
        if(!deployment.profileId.equals(job.optString("profileId"))||!deployment.serviceBase.equals(job.optString("serviceBase"))
                ||!("skj_"+operationKey(ref)).equals(job.optString("operationId"))||!deployment.environmentId.equals(job.optString("environmentId"))
                ||!"command".equals(job.optString("worker"))||!job.optString("id").matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("未取得原操作回执；禁止重发");
    }
    private JSONObject readBundle(ReceiptRef ref,String expected,long until)throws Exception {
        byte[] raw=readFile(stagePath(operationKey(ref)),until);if(expected!=null&&!expected.equals(SkillExecution.sha256(raw)))throw new SecurityException("读回不是原上传字节");
        JSONObject bundle=new JSONObject(utf8(raw)),p=bundle.getJSONObject("review"),g=bundle.getJSONObject("grant"),r=p.getJSONObject("run"),b=p.getJSONObject("backend"),q=p.getJSONObject("request");
        JSONObject unsigned=new JSONObject(p.toString());unsigned.remove("planDigest");
        if(!"pocketworld.skill-canonical-bundle.v1".equals(bundle.optString("format"))||!ref.planDigest.equals(p.optString("planDigest"))||!ref.planDigest.equals(SkillExecution.digestJson(unsigned))
                ||!ref.operationId.equals(g.optString("operationId"))||!ref.environmentBinding.equals(g.optString("environmentBinding"))
                ||!ref.ownerId.equals(r.optString("ownerId"))||!ref.spaceId.equals(r.optString("spaceId"))||!ref.taskId.equals(r.optString("taskId"))||!ref.runId.equals(r.optString("runId"))||ref.epoch!=r.optInt("epoch")
                ||!ref.installationId.equals(b.optString("installationId"))||!ref.workspaceId.equals(b.optString("workspaceId"))||!ref.workspaceRevision.equals(b.optString("workspaceRevision"))
                ||!operationKey(ref).equals(bundle.optString("operationKey"))||ref.maxOutputBytes!=q.getInt("maxOutputBytes")||ref.maxResultChars!=q.getInt("maxResultChars"))throw new SecurityException("上传包与原任务/计划不一致");
        return bundle;
    }
    private JSONObject checkedFile(String path,String hash,long until)throws Exception {byte[] raw=readFile(path,until);if(!hash.equals(SkillExecution.sha256(raw)))throw new SecurityException("固定部署SHA变化");return new JSONObject(utf8(raw));}
    private byte[] readFile(String path,long until)throws Exception {
        JSONObject f=call("readFile",workspace().put("path",path),until);String text=f.getString("content");byte[] raw=text.getBytes(StandardCharsets.UTF_8);
        if(raw.length>262144||!text.equals(utf8(raw))||raw.length!=f.getLong("bytes")||!path.equals(f.getString("path"))||!("sha256:"+SkillExecution.sha256(raw)).equals(f.getString("version")))throw new SecurityException("Companion文件字节回执不一致");return raw;
    }
    private JSONObject call(String command,JSONObject input,long until)throws Exception {
        deadline(until);synchronized(remote){deadline(until);JSONArray profiles=remote.snapshot().getJSONArray("profiles");boolean found=false;
            for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(deployment.profileId.equals(p.optString("id")))found=deployment.serviceBase.equals(p.optString("baseUrl").replaceAll("/+$",""));}
            if(!found)throw new SecurityException("原连接已替换；未向新地址发送");JSONObject result=remote.handle(command,input);
            if(result.has("serviceBase")&&!deployment.serviceBase.equals(result.getString("serviceBase")))throw new SecurityException("回执来源改变");return result;}
    }
    private void checkpoint(SkillExecution.Dispatch d,RunControl control,long pause,long until)throws Exception {deadline(until);control.check();SkillExecution.Grant g=d.grant;
        if(!control.hasHostRun()||!control.matchesHostRun(g.taskId,g.runId,g.epoch)||control.isPaused()||pause!=control.pauseRevision()||clock.getAsLong()>=g.expiresAt||clock.getAsLong()>=d.plan.expiresAt)throw new SecurityException("授权、暂停或任务代次已变化");
        NativeSkillBackend.DispatchCheck current=dispatchCheck;if(current==null)throw new SecurityException("缺少原TaskStore的持续精确授权核对");current.check(d.plan,g,control);deadline(until);control.check();}
    private static void deadline(long until)throws Exception {if(Thread.currentThread().isInterrupted()||System.nanoTime()>=until)throw new IOException("短派发握手截止；仅核对原操作");}
    private void original(ReceiptRef ref){if(!deployment.installationId.equals(ref.installationId))throw new SecurityException("不能转移旧操作到替换安装");}
    private JSONObject base()throws Exception{return new JSONObject().put("profileId",deployment.profileId);}private JSONObject workspace()throws Exception{return base().put("environmentId",deployment.environmentId);}
    static String operationKey(ReceiptRef r)throws Exception{return SkillExecution.sha256(String.join("\n","pwskill-canonical-v1",r.ownerId,r.spaceId,r.taskId,r.runId,Integer.toString(r.epoch),r.operationId,BACKEND,r.installationId).getBytes(StandardCharsets.UTF_8));}
    static String stagePath(String key){return ".pwskill-"+key+".json";}static String cancelPath(String key){return ".pwskill-cancel-"+key;}
    private static List<String> strings(JSONArray a)throws Exception{List<String> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.getString(i));return out;}
    private static String utf8(byte[] raw)throws Exception{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}
    private static String id(String v){if(v==null||!v.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Companion编号无效");return v;}
    private static String identifier(String v){if(v==null||!v.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException("执行端身份无效");return v;}
    private static String identity(String v){if(v==null||!v.matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))throw new IllegalArgumentException("认证范围无效");return v;}
    private static String uuid(String v){if(v==null||!v.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("操作身份无效");return v;}
    private static String sha(String v){if(v==null||!v.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SHA256无效");return v;}
    @Override public void close(){closed=true;io.shutdownNow();cancellations.shutdownNow();}
}
