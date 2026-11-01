package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native reviewed execution boundary. No process launcher, inferred grant, task store or scheduler. */
public final class SkillExecution {
    public enum Fence { EXACT_BYTES, EXACT_ARGV, EMPTY_ENVIRONMENT, FILE_READS, FILE_WRITES, NETWORK_DENY, OUTPUT_LIMIT, TIMEOUT, CANCELLATION }
    public enum Status { ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT, REJECTED, UNKNOWN }
    public interface Clock { long now(); }
    /** Registered by the native host only. describe must probe actual enforcement, not package claims. */
    public interface Backend {
        Environment describe(Run run,Request request)throws Exception;
        Receipt submit(Dispatch dispatch,RunControl control)throws Exception;
    }
    public interface Submission { Receipt submit()throws Exception; }
    /**
     * The existing TaskStore adapter owns exact approval, durable once-only dispatch, leases and cancel.
     * It must commit an operation before invoking Submission, and never invoke it for an existing ID,
     * an unknown effect, or an unknown conflicting write. It retains asynchronous cancellation watches.
     */
    public interface HostPort {
        void checkCurrent(Plan plan,Grant grant,RunControl control)throws Exception;
        Receipt dispatchOnce(Plan plan,Grant grant,RunControl control,Submission submission)throws Exception;
        Receipt reconcile(Plan plan,Grant grant)throws Exception;
    }
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String message){super(message);}
    }
    /** Host-owned task identity and current capability names. Never construct from tool-selected scope. */
    public static final class Run {
        public final String ownerId,spaceId,taskId,runId;
        public final int epoch;
        private final Set<String> capabilities;
        public Run(String ownerId,String spaceId,String taskId,String runId,int epoch,Set<String> capabilities){
            this.ownerId=identity(ownerId,160);this.spaceId=identity(spaceId,200);this.taskId=uuid(taskId);this.runId=uuid(runId);
            if(epoch<1||capabilities==null||capabilities.size()>256)throw new IllegalArgumentException("执行身份或能力集合无效");
            this.epoch=epoch;TreeSet<String> copy=new TreeSet<>();for(String capability:capabilities){if(capability==null||!capability.matches("[a-z][a-z0-9_.:-]{0,79}"))throw new IllegalArgumentException("能力名称无效");copy.add(capability);}this.capabilities=Collections.unmodifiableSet(copy);
        }
        JSONObject scope()throws Exception{return new JSONObject().put("ownerId",ownerId).put("spaceId",spaceId);}
        JSONObject json()throws Exception{return scope().put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("capabilities",new JSONArray(capabilities));}
    }
    /** Literal workspace-relative rules: trailing '/' means a subtree, otherwise one exact file. */
    public static final class Request {
        public final String scriptPath,runtimeId,workspaceId,workingDirectory;
        public final int timeoutSeconds,maxOutputBytes,maxResultChars;
        private final List<String> arguments,readPaths,writePaths,outputPaths;
        private ManagedSkillPolicy.Authority managedAuthority;
        public Request(String scriptPath,String runtimeId,String workspaceId,String workingDirectory,List<String> arguments,
                       List<String> readPaths,List<String> writePaths,List<String> outputPaths,int timeoutSeconds,int maxOutputBytes,int maxResultChars){
            this.scriptPath=relative(scriptPath,false);if(!this.scriptPath.startsWith("scripts/"))throw new IllegalArgumentException("请选择scripts目录中的具体资源");
            this.runtimeId=identifier(runtimeId);this.workspaceId=identifier(workspaceId);this.workingDirectory=".".equals(workingDirectory)?".":relative(workingDirectory,false);
            this.arguments=arguments(arguments,40,8192);this.readPaths=rules(readPaths);this.writePaths=rules(writePaths);this.outputPaths=paths(outputPaths,8);
            for(String output:this.outputPaths)if(!contains(this.writePaths,output))throw new SecurityException("输出目标必须处于明确批准的写入范围");
            if(timeoutSeconds<1||timeoutSeconds>120||maxOutputBytes<1||maxOutputBytes>65536||maxResultChars<2048||maxResultChars>32000)throw new IllegalArgumentException("执行、输出或返回预算超限");
            this.timeoutSeconds=timeoutSeconds;this.maxOutputBytes=maxOutputBytes;this.maxResultChars=maxResultChars;
        }
        static Request managed(ManagedSkillPolicy.Authority nativeAuthority,String script,String runtime,String workspace,String cwd,List<String> arguments,List<String> reads,List<String> writes,List<String> outputs,int seconds,int bytes,int chars){Request request=new Request(script,runtime,workspace,cwd,arguments,reads,writes,outputs,seconds,bytes,chars);request.managedAuthority=java.util.Objects.requireNonNull(nativeAuthority);return request;}
        JSONObject json()throws Exception{JSONObject value=new JSONObject().put("scriptPath",scriptPath).put("runtimeId",runtimeId).put("workspaceId",workspaceId).put("workingDirectory",workingDirectory)
                .put("arguments",new JSONArray(arguments)).put("readPaths",new JSONArray(readPaths)).put("writePaths",new JSONArray(writePaths)).put("outputPaths",new JSONArray(outputPaths))
                .put("network",managedAuthority==null?"deny":"possible_same_uid").put("stdin","closed").put("environment",managedAuthority==null?"empty":"native_controlled_minimal").put("timeoutSeconds",timeoutSeconds).put("maxOutputBytes",maxOutputBytes).put("maxResultChars",maxResultChars);
            if(managedAuthority!=null)value.put("nativeExecutionContract",managedAuthority.risks()).put("requestedPathRulesOsEnforced",false);return value;}

    }
    /** Exact probed native/Companion identity, binary and immutable mount layout for one request. */
    public static final class Environment {
        public final String backendId,installationId,runtimeId,runtimeVersion,runtimeSha256,workspaceId,workspaceRevision,workspaceRoot,packageRoot,enforcementId,workspaceMode,runtimeImageSha256,processPolicy;
        private final List<String> runtimePrefix;
        private final Set<Fence> fences;
        private ManagedSkillPolicy.Authority managedAuthority;private String managedRuntimeIdentity="";private JSONObject managedInputs;
        public Environment(String backendId,String installationId,String runtimeId,String runtimeVersion,String runtimeSha256,List<String> runtimePrefix,
                           String workspaceId,String workspaceRevision,String workspaceRoot,String packageRoot,String enforcementId,Set<Fence> fences){
            this(backendId,installationId,runtimeId,runtimeVersion,runtimeSha256,runtimePrefix,workspaceId,workspaceRevision,workspaceRoot,packageRoot,enforcementId,fences,"direct_mount","");
        }
        public Environment(String backendId,String installationId,String runtimeId,String runtimeVersion,String runtimeSha256,List<String> runtimePrefix,
                           String workspaceId,String workspaceRevision,String workspaceRoot,String packageRoot,String enforcementId,Set<Fence> fences,String workspaceMode,String runtimeImageSha256){
            this(backendId,installationId,runtimeId,runtimeVersion,runtimeSha256,runtimePrefix,workspaceId,workspaceRevision,workspaceRoot,packageRoot,enforcementId,fences,workspaceMode,runtimeImageSha256,"executor_defined");
        }
        public Environment(String backendId,String installationId,String runtimeId,String runtimeVersion,String runtimeSha256,List<String> runtimePrefix,
                           String workspaceId,String workspaceRevision,String workspaceRoot,String packageRoot,String enforcementId,Set<Fence> fences,String workspaceMode,String runtimeImageSha256,String processPolicy){
            this.backendId=identifier(backendId);this.installationId=identifier(installationId);this.runtimeId=identifier(runtimeId);this.runtimeVersion=plain(runtimeVersion,1,120);
            this.runtimeSha256=hash(runtimeSha256);this.runtimePrefix=arguments(runtimePrefix,12,2048);if(this.runtimePrefix.isEmpty())throw new IllegalArgumentException("缺少实际运行时argv");
            absolute(this.runtimePrefix.get(0));for(String argument:this.runtimePrefix)if(Set.of("-c","-e","--eval","--command","/c","-Command","-EncodedCommand").contains(argument))throw new SecurityException("运行时不能以命令字符串或eval代替已批准的脚本字节");
            this.workspaceId=identifier(workspaceId);this.workspaceRevision=identifier(workspaceRevision);
            this.workspaceRoot=absolute(workspaceRoot);this.packageRoot=absolute(packageRoot);if(under(this.workspaceRoot,this.packageRoot)||under(this.packageRoot,this.workspaceRoot))throw new SecurityException("只读技能挂载与工作区必须分离");
            this.enforcementId=identifier(enforcementId);this.fences=fences==null?Set.of():Set.copyOf(fences);
            if(!Set.of("direct_mount","isolated_copy").contains(workspaceMode))throw new IllegalArgumentException("工作区执行语义无效");
            this.workspaceMode=workspaceMode;this.runtimeImageSha256=runtimeImageSha256.isEmpty()?"":hash(runtimeImageSha256);
            if(!Set.of("executor_defined","single_process").contains(processPolicy))throw new IllegalArgumentException("进程运行限制无效");this.processPolicy=processPolicy;
        }
        static Environment managed(ManagedSkillPolicy.Authority authority,String installation,String runtime,String version,String sha,List<String> prefix,String workspace,String revision,String workspaceRoot,String packageRoot,String runtimeIdentity,JSONObject exactInputs)throws Exception {
            Environment environment=new Environment(ManagedSkillBackend.BACKEND,installation,runtime,version,sha,prefix,workspace,revision,workspaceRoot,packageRoot,"native-managed:"+ManagedSkillProgram.identity(),Set.of(Fence.EXACT_BYTES,Fence.EXACT_ARGV,Fence.TIMEOUT),"isolated_copy","","executor_defined");environment.managedAuthority=java.util.Objects.requireNonNull(authority);environment.managedRuntimeIdentity=hash(runtimeIdentity);environment.managedInputs=new JSONObject(exactInputs.toString());return environment;
        }
        JSONObject json()throws Exception{JSONObject result=new JSONObject().put("backendId",backendId).put("installationId",installationId).put("runtimeId",runtimeId).put("runtimeVersion",runtimeVersion)
                .put("runtimeSha256",runtimeSha256).put("runtimePrefix",new JSONArray(runtimePrefix)).put("workspaceId",workspaceId).put("workspaceRevision",workspaceRevision)
                .put("workspaceRoot",workspaceRoot).put("packageRoot",packageRoot).put("enforcementId",enforcementId).put("fences",new JSONArray(fences.stream().map(Enum::name).sorted().toArray(String[]::new)))
                .put("workspaceMode",workspaceMode).put("fileResultsRequireImport",workspaceMode.equals("isolated_copy")).put("runtimeImageSha256",runtimeImageSha256).put("processPolicy",processPolicy);if(managedAuthority!=null)result.put("nativeExecutionContract",managedAuthority.risks()).put("managedRuntimeIdentity",managedRuntimeIdentity).put("managedInputSnapshot",new JSONObject(managedInputs.toString())).put("processPolicy","owned_process_group_observed_descendants").put("packageCopyReadOnlyAttempted",true);return result;}
        void require(Request request){
            if(!runtimeId.equals(request.runtimeId)||!workspaceId.equals(request.workspaceId))throw new Unavailable("执行端无法提供选定的运行时或工作区");
            if(managedAuthority!=null){if(request.managedAuthority!=managedAuthority||!ManagedSkillBackend.BACKEND.equals(backendId)||!fences.containsAll(Set.of(Fence.EXACT_BYTES,Fence.EXACT_ARGV,Fence.TIMEOUT)))throw new Unavailable("缺少本机主人原生选择的真实能力合同，不能由模型参数降级执行限制");return;}
            if(request.managedAuthority!=null||!fences.containsAll(Set.of(Fence.values())))throw new Unavailable("执行端不能验证本次文件、网络、输出、超时及取消限制");
        }
    }
    /** In-memory immutable plan. No public JSON decoder; editing review JSON cannot edit execution. */
    public static final class Plan {
        private final Run run;
        private final Request request;
        private final Environment environment;
        private final String skillId,packageDigest,packageBinding,environmentBinding,reviewJson;
        private final int revision;
        public final String digest;
        public final long expiresAt;
        private Plan(Run run,Request request,Environment environment,SkillBook.ExecutionSnapshot snapshot,long now)throws Exception {
            this.run=run;this.request=request;this.environment=environment;this.skillId=snapshot.binding.getString("id");this.revision=snapshot.binding.getInt("revision");this.packageDigest=snapshot.binding.getString("digest");
            this.packageBinding=digestJson(snapshot.binding);this.environmentBinding=digestJson(environment.json());long packageExpiry=snapshot.binding.getLong("expiresAt");
            this.expiresAt=packageExpiry==0?Math.addExact(now,120000):Math.min(packageExpiry,Math.addExact(now,120000));
            byte[] script=snapshot.files.get(request.scriptPath);if(script==null)throw new IllegalArgumentException("当前技能不存在所选脚本资源");
            JSONArray files=new JSONArray();for(Map.Entry<String,byte[]> file:snapshot.files.entrySet())files.put(new JSONObject().put("path",file.getKey()).put("sha256",sha256(file.getValue())).put("bytes",file.getValue().length));
            JSONObject review=new JSONObject().put("format","pocketworld.skill-execution.v1").put("run",run.json()).put("package",new JSONObject(snapshot.binding.toString()))
                    .put("scriptSha256",sha256(script)).put("resources",files).put("request",request.json()).put("backend",environment.json())
                    .put("argv",new JSONArray(argv())).put("cwd",cwd()).put("expiresAt",expiresAt).put("packageReadOnly",true)
                    .put("untrustedReference",true).put("requiresOwnerApproval",true).put("businessVerified",false).put("grantsAdded",new JSONArray());
            if(request.managedAuthority!=null){review.put("packageReadOnly",false).put("packageCopyReadOnlyAttempted",true).put("packageReadOnlyOsEnforced",false).put("executionLocation","this_phone").put("dependencies",ManagedSkillDependencies.inspect(snapshot.files,request.runtimeId,request.managedAuthority.prepareDependencies));}
            this.digest=digestJson(review);this.reviewJson=review.put("planDigest",digest).toString();
        }
        public JSONObject review()throws Exception{return new JSONObject(reviewJson);}
        /** Exact canonical UTF-8 bytes hashed by this canonical Plan, without its derived planDigest. */
        public byte[] canonicalReviewBytes()throws Exception{JSONObject value=review();value.remove("planDigest");return canonicalBytes(value);}
        public List<String> argv(){List<String> argv=new ArrayList<>(environment.runtimePrefix);argv.add(environment.packageRoot+"/"+request.scriptPath);argv.addAll(request.arguments);return Collections.unmodifiableList(argv);}
        public String cwd(){return environment.workspaceRoot+(request.workingDirectory.equals(".")?"":"/"+request.workingDirectory);}
        public Run run(){return run;}
        boolean managedPhone(){return request.managedAuthority!=null&&environment.managedAuthority==request.managedAuthority;}
        ManagedSkillPolicy.Authority nativeManagedAuthority(){if(!managedPhone())throw new SecurityException("不是原生手机Skill方案");return request.managedAuthority;}
    }
    /** A native TaskStore/backend approval reference, never accepted from feature or model JSON. */
    public static final class Grant {
        public final String operationId,approvalId,backendGrantId,planDigest,taskId,runId,environmentBinding;
        public final int epoch;
        public final long expiresAt;
        public Grant(String operationId,String approvalId,String backendGrantId,String planDigest,String taskId,String runId,int epoch,String environmentBinding,long expiresAt){
            this.operationId=uuid(operationId);this.approvalId=identifier(approvalId);this.backendGrantId=identifier(backendGrantId);this.planDigest=hash(planDigest);
            this.taskId=uuid(taskId);this.runId=uuid(runId);if(epoch<1||expiresAt<1)throw new IllegalArgumentException("执行授权无效");this.epoch=epoch;this.environmentBinding=hash(environmentBinding);this.expiresAt=expiresAt;
        }
    }
    /** Immutable exact bytes copied once more for staging; callers cannot mutate a plan's package. */
    public static final class Dispatch {
        public final Plan plan;
        public final Grant grant;
        private final Map<String,byte[]> files;
        private Dispatch(Plan plan,Grant grant,SkillBook.ExecutionSnapshot snapshot){this.plan=plan;this.grant=grant;this.files=copyBytes(snapshot.files);}
        public Map<String,byte[]> packageFiles(){return copyBytes(files);}
    }
    /** A bounded executor receipt, not business success or a new product task state. */
    public static final class Receipt {
        public final String operationId,backendReceiptId;
        public final Status status;
        public final Integer exitCode;
        private final byte[] output;
        private final boolean truncated;
        public Receipt(String operationId,String backendReceiptId,Status status,Integer exitCode,byte[] output,boolean truncated){
            this.operationId=uuid(operationId);this.backendReceiptId=backendReceiptId==null?"":plain(backendReceiptId,0,200);this.status=java.util.Objects.requireNonNull(status);this.exitCode=exitCode;
            if(status==Status.COMPLETED&&!Integer.valueOf(0).equals(exitCode))throw new IllegalArgumentException("完成回执需要实际零退出码");
            byte[] source=output==null?new byte[0]:output;this.output=Arrays.copyOf(source,Math.min(65536,source.length));this.truncated=truncated||source.length>65536;
        }
        public static Receipt unknown(String operationId){return new Receipt(operationId,"",Status.UNKNOWN,null,null,false);}
        public byte[] outputBytes(){return output.clone();}
        public boolean outputTruncated(){return truncated;}
    }

    private final SkillBook book;
    private final Backend backend;
    private final HostPort host;
    private final Clock clock;
    SkillExecution(SkillBook book,Backend backend,HostPort host,Clock clock){this.book=java.util.Objects.requireNonNull(book);this.backend=backend;this.host=host;this.clock=java.util.Objects.requireNonNull(clock);}
    public Plan prepare(Run run,String skillId,int revision,String packageDigest,Request request,RunControl control)throws Exception {
        checkRun(run,control);if(backend==null||host==null)throw new Unavailable("当前没有可验证隔离且接入任务审批的脚本执行端");
        long now=clock.now();SkillBook.ExecutionSnapshot snapshot=book.executionSnapshot(run.scope(),skillId,revision,hash(packageDigest),run.capabilities,now);
        if(!snapshot.files.containsKey(request.scriptPath))throw new IllegalArgumentException("当前技能不存在所选脚本资源");
        Environment environment=backend.describe(run,request);if(environment==null)throw new Unavailable("脚本运行时或隔离执行端不可用");environment.require(request);
        checkRun(run,control);now=clock.now();snapshot=book.executionSnapshot(run.scope(),skillId,revision,packageDigest,run.capabilities,now);return new Plan(run,request,environment,snapshot,now);
    }
    /** Exposes the exact environment fingerprint for the host's backend grant, not a grant itself. */
    public static String environmentBinding(Plan plan){return plan.environmentBinding;}
    public static boolean isManagedPhonePlan(Plan plan){return plan!=null&&plan.managedPhone();}
    /** Refresh the native review page's validity; this does not approve or reserve an operation. */
    public String validateForReview(Plan plan,RunControl control)throws Exception {
        if(backend==null||host==null)throw new Unavailable("脚本执行端不可用");checkRun(plan.run,control);
        return book.withExecutionSnapshot(plan.run.scope(),plan.skillId,plan.revision,plan.packageDigest,plan.run.capabilities,clock.now(),snapshot->{
            requirePackage(plan,snapshot);requireEnvironment(plan);checkRun(plan.run,control);if(clock.now()>=plan.expiresAt)throw new SecurityException("执行审阅已过期");return plan.digest;
        });
    }
    public JSONObject dispatch(Plan plan,Grant grant,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(plan);java.util.Objects.requireNonNull(grant);if(backend==null||host==null)throw new Unavailable("脚本执行端不可用");validateGrant(plan,grant,control);host.checkCurrent(plan,grant,control);
        // Validate before reserving; validate again in the once-only callback after journal preparation.
        validateCurrent(plan,grant,control);AtomicBoolean called=new AtomicBoolean(),attempted=new AtomicBoolean();Receipt receipt;
        try{receipt=host.dispatchOnce(plan,grant,control,()->{
            if(!called.compareAndSet(false,true))throw new SecurityException("同一操作不能重复派发");
            return book.withExecutionSnapshot(plan.run.scope(),plan.skillId,plan.revision,plan.packageDigest,plan.run.capabilities,clock.now(),snapshot->{
                validateGrant(plan,grant,control);host.checkCurrent(plan,grant,control);requirePackage(plan,snapshot);requireEnvironment(plan);
                // A read-only origin resolver is another authority; a probe may outlive its revision.
                SkillBook.ExecutionSnapshot current=book.executionSnapshot(plan.run.scope(),plan.skillId,plan.revision,plan.packageDigest,plan.run.capabilities,clock.now());
                requirePackage(plan,current);
                validateGrant(plan,grant,control);host.checkCurrent(plan,grant,control);
                attempted.set(true);try{return safeReceipt(grant,backend.submit(new Dispatch(plan,grant,current),control));}
                catch(Exception uncertain){return Receipt.unknown(grant.operationId);}
            });
        });}catch(Exception failure){if(!attempted.get())throw failure;receipt=Receipt.unknown(grant.operationId);}
        return result(plan,grant,receipt,control.isCancelled());
    }
    /** Query only. Remains available to authorized native receipt readers after expiry/disable/cancel. */
    public JSONObject reconcile(Plan plan,Grant grant)throws Exception {
        checkIdentity(plan,grant);if(host==null)throw new Unavailable("任务回执宿主不可用");Receipt receipt;
        try{receipt=host.reconcile(plan,grant);}catch(Exception unavailable){receipt=Receipt.unknown(grant.operationId);}return result(plan,grant,receipt,false);
    }
    private void validateCurrent(Plan plan,Grant grant,RunControl control)throws Exception {
        if(backend==null||host==null)throw new Unavailable("脚本执行端不可用");
        book.withExecutionSnapshot(plan.run.scope(),plan.skillId,plan.revision,plan.packageDigest,plan.run.capabilities,clock.now(),snapshot->{validateGrant(plan,grant,control);requirePackage(plan,snapshot);requireEnvironment(plan);return null;});
    }
    private void requireEnvironment(Plan plan)throws Exception {Environment current=backend.describe(plan.run,plan.request);if(current==null)throw new Unavailable("脚本执行端已不可用");current.require(plan.request);if(!plan.environmentBinding.equals(digestJson(current.json())))throw new SecurityException("运行时、工作区或执行端已改变，请重新审阅");}
    private static void requirePackage(Plan plan,SkillBook.ExecutionSnapshot snapshot)throws Exception {if(!plan.packageBinding.equals(digestJson(snapshot.binding)))throw new SecurityException("技能批准或来源已改变，请重新审阅");}
    private void validateGrant(Plan plan,Grant grant,RunControl control)throws Exception {checkIdentity(plan,grant);checkRun(plan.run,control);long now=clock.now();if(now>=plan.expiresAt||now>=grant.expiresAt||grant.expiresAt>plan.expiresAt)throw new SecurityException("执行审阅或授权已过期");}
    private static void checkIdentity(Plan plan,Grant grant){if(!plan.digest.equals(grant.planDigest)||!plan.run.taskId.equals(grant.taskId)||!plan.run.runId.equals(grant.runId)||plan.run.epoch!=grant.epoch||!plan.environmentBinding.equals(grant.environmentBinding))throw new SecurityException("执行授权与具体计划、任务或执行端不一致");}
    private static void checkRun(Run run,RunControl control)throws Exception {java.util.Objects.requireNonNull(run);java.util.Objects.requireNonNull(control);control.check();if(!control.hasHostRun()||!control.matchesHostRun(run.taskId,run.runId,run.epoch)||control.isPaused())throw new SecurityException("原任务运行身份无效或已暂停");}
    private static Receipt safeReceipt(Grant grant,Receipt receipt){return receipt==null||!grant.operationId.equals(receipt.operationId)?Receipt.unknown(grant.operationId):receipt;}
    private static JSONObject result(Plan plan,Grant grant,Receipt receipt,boolean cancelRequested)throws Exception {
        receipt=safeReceipt(grant,receipt);
        int bytes=Math.min(plan.request.maxOutputBytes,receipt.output.length);String output=new String(receipt.output,0,bytes,StandardCharsets.UTF_8);boolean truncated=receipt.truncated||bytes<receipt.output.length;
        JSONObject value=new JSONObject().put("operationId",grant.operationId).put("planDigest",plan.digest).put("taskId",plan.run.taskId).put("runId",plan.run.runId).put("epoch",plan.run.epoch)
                .put("backendReceiptId",receipt.backendReceiptId).put("status",receipt.status.name().toLowerCase(java.util.Locale.ROOT)).put("exitCode",receipt.exitCode==null?JSONObject.NULL:receipt.exitCode)
                .put("output","").put("outputTruncated",truncated).put("cancelRequested",cancelRequested).put("untrustedOutput",true).put("businessVerified",false).put("replayAllowed",false)
                .put("message",receipt.status==Status.UNKNOWN?"效果尚未确认；仅查询原操作回执，不会重跑脚本":"执行端回执不等于任务目标完成；请核对实际结果");
        value.put("workspaceMode",plan.environment.workspaceMode).put("fileResultsRequireImport",plan.environment.workspaceMode.equals("isolated_copy"))
                .put("outputEncoding","utf8_lossy").put("outputBytes",bytes).put("outputSha256",sha256(Arrays.copyOf(receipt.output,bytes)));
        String binary=java.util.Base64.getEncoder().encodeToString(Arrays.copyOf(receipt.output,bytes));
        value.put("outputBase64",binary).put("outputBase64Omitted",false);
        if(value.toString().length()>plan.request.maxResultChars)value.put("outputBase64",JSONObject.NULL).put("outputBase64Omitted",true);
        int low=0,high=output.length();while(low<high){int middle=(low+high+1)/2;value.put("output",output.substring(0,middle));if(value.toString().length()<=plan.request.maxResultChars)low=middle;else high=middle-1;}
        if(low>0&&low<output.length()&&Character.isHighSurrogate(output.charAt(low-1)))low--;
        return value.put("output",output.substring(0,low)).put("outputTruncated",truncated||low<output.length());
    }
    static String digestJson(JSONObject value)throws Exception{return sha256(canonical(value).getBytes(StandardCharsets.UTF_8));}
    static byte[] canonicalBytes(JSONObject value)throws Exception{return canonical(value).getBytes(StandardCharsets.UTF_8);}
    static String sha256(byte[] value)throws Exception {StringBuilder hex=new StringBuilder();for(byte item:MessageDigest.getInstance("SHA-256").digest(value))hex.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return hex.toString();}
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();object.keys().forEachRemaining(keys::add);List<String> parts=new ArrayList<>();for(String key:keys)parts.add(JSONObject.quote(key)+":"+canonical(object.get(key)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;List<String> parts=new ArrayList<>();for(int i=0;i<array.length();i++)parts.add(canonical(array.get(i)));return "["+String.join(",",parts)+"]";}
        return value==null||value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):value.toString();
    }
    private static Map<String,byte[]> copyBytes(Map<String,byte[]> files){Map<String,byte[]> out=new TreeMap<>();files.forEach((name,bytes)->out.put(name,bytes.clone()));return Collections.unmodifiableMap(out);}
    private static String uuid(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("任务或操作编号无效");return value;}
    private static String identity(String value,int max){if(value==null||!value.matches("[\\p{L}\\p{N}_.:/@-]{1,"+max+"}"))throw new IllegalArgumentException("认证空间无效");return value;}
    private static String identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException("执行端标识无效");return value;}
    private static String hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SHA-256无效");return value;}
    private static String plain(String value,int min,int max){if(value==null||value.length()<min||value.length()>max)throw new IllegalArgumentException("文字参数长度无效");for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isISOControl(c))throw new IllegalArgumentException("文字参数含控制字符");if(Character.isSurrogate(c)){if(!Character.isHighSurrogate(c)||i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw new IllegalArgumentException("文字参数编码无效");}}return value;}
    private static List<String> arguments(List<String> values,int max,int chars){if(values==null||values.size()>max)throw new IllegalArgumentException("argv数量无效");List<String> copy=new ArrayList<>();int count=0;for(String value:values){copy.add(plain(value,0,2048));count+=value.length();}if(count>chars)throw new IllegalArgumentException("argv过长");return Collections.unmodifiableList(copy);}
    private static String relative(String value,boolean rule){plain(value,1,240);String checked=rule&&value.endsWith("/")?value.substring(0,value.length()-1):value;if(checked.startsWith("/")||checked.contains("\\")||checked.contains(":")||checked.contains("*")||checked.contains("?"))throw new IllegalArgumentException("只允许明确的相对路径");for(String part:checked.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("路径不能逃逸或包含空段");return value;}
    private static String absolute(String value){plain(value,2,1024);if(!value.startsWith("/")||value.endsWith("/"))throw new IllegalArgumentException("执行端需要规范的绝对POSIX路径");relative(value.substring(1),false);return value;}
    private static List<String> paths(List<String> values,int max){if(values==null||values.size()>max)throw new IllegalArgumentException("路径数量无效");TreeSet<String> copy=new TreeSet<>();for(String value:values){relative(value,false);if(!copy.add(value))throw new IllegalArgumentException("路径不能重复");}return List.copyOf(copy);}
    private static List<String> rules(List<String> values){if(values==null||values.size()>16)throw new IllegalArgumentException("读写范围数量无效");TreeSet<String> copy=new TreeSet<>();for(String value:values){relative(value,true);if(!copy.add(value))throw new IllegalArgumentException("读写范围不能重复");}return List.copyOf(copy);}
    private static boolean contains(List<String> rules,String path){for(String rule:rules)if(rule.equals(path)||rule.endsWith("/")&&path.startsWith(rule))return true;return false;}
    private static boolean under(String root,String child){return child.equals(root)||child.startsWith(root+"/");}
}
