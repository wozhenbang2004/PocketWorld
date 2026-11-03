package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeReceipt;
import dev.pocketengine.features.local.runtime.NativePipeAdmission;
import dev.pocketengine.features.local.runtime.NativeProcessSupervisor;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.plugins.mcp.McpJson;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A native one-use owner review, not HTTP discovery permission or an installed tool grant.
 * No handle/model command accepts a JSON approval flag or restores a Confirmed object. */
public final class McpLocalDiscovery {
    public static final String FORMAT="pocketworld.native-mcp-discovery.v1",INTENT="native_mcp_discovery",STEP="native_mcp_metadata_discovery";
    public static final long TIMEOUT_MILLIS=180000,WRITE_BYTE_LIMIT=1048576;
    private static final Set<String> CONFIG_FIELDS=Set.of("id","name","checkoutId","expectedSourceDigest","runtimeId","executable","argv","workingDirectory","protocolVersion",McpLocalPackage.FIELD);
    private McpLocalDiscovery() {}
    @FunctionalInterface public interface SourceCheck {void check()throws Exception;}
    @FunctionalInterface public interface CopyFactory {RuntimeWorkspace create(String action,JSONObject source,RunControl original,ProjectRuntime.LiveGate current)throws Exception;}
    /** Called by LocalModule with its real export/content workspace. This creates only a review. */
    public static Review reviewForHost(JSONObject input,JSONObject frozenSource,RuntimeWorkspace actualWorkspace,ManagedProjectRuntime runtime,SourceCheck currentSource,CopyFactory copies,RunControl observation)throws Exception {
        return new Review(input,frozenSource,actualWorkspace,runtime,currentSource,copies,observation);
    }
    public static final class Review implements AutoCloseable {
        private final JSONObject config,source,baseScope;private final RuntimeWorkspace workspace;private final ManagedProjectRuntime runtime;private final SourceCheck sourceCheck;private final CopyFactory copies;private final RunControl observation;private final String action=UUID.randomUUID().toString();private final long expiresAt=System.currentTimeMillis()+120000;
        private final McpLocalPackage.Proposal packagePreparation;private boolean preparationApproved;
        private PluginsModule plugins;private JSONObject scope;private boolean used,closed,transferred;private final Runnable cancellation=this::close;
        private Review(JSONObject input,JSONObject frozenSource,RuntimeWorkspace workspace,ManagedProjectRuntime runtime,SourceCheck currentSource,CopyFactory copies,RunControl observation)throws Exception {
            this.copies=Objects.requireNonNull(copies);this.workspace=Objects.requireNonNull(workspace);this.runtime=Objects.requireNonNull(runtime);sourceCheck=Objects.requireNonNull(currentSource);this.observation=Objects.requireNonNull(observation);
            if(observation.hasHostRun())throw new SecurityException("本机MCP发现须从主人原生审阅发起，不能借模型运行身份");observation.check();
            config=configuration(input);source=ProjectSources.copy(frozenSource);JSONObject described=ProjectSources.describe(source);
            if(!config.getString("checkoutId").equals(described.getString("checkoutId"))||!workspace.checkoutId().equals(described.getString("checkoutId"))||!workspace.projectId().equals(described.getString("projectId"))||!config.getString("expectedSourceDigest").equals(described.getString("sourceDigest")))throw new SecurityException("本机MCP审阅必须使用已选真实副本与冻结源SHA");
            JSONObject runtimeFacts=runtime.describe();if(!runtimeFacts.optBoolean("canExecute"))throw new IllegalStateException("请先在原手机开发工具入口准备并核对运行环境；本机MCP发现不会下载依赖");
            JSONObject executable=null;JSONArray tools=runtimeFacts.getJSONArray("tools");for(int i=0;i<tools.length();i++){JSONObject candidate=tools.getJSONObject(i);if(config.getString("executable").equals(candidate.optString("name"))&&(candidate.optBoolean("hashVerified")||candidate.optBoolean("probeVerified")))executable=candidate;}
            if(executable==null||!executable.optString("sha256").matches("[a-f0-9]{64}"))throw new SecurityException("本机MCP解释器尚未由原运行环境实际核对");
            JSONArray argv=config.getJSONArray("argv");String entry=argv.getString(0);JSONObject entryFact=null;JSONArray manifest=described.getJSONArray("files");for(int i=0;i<manifest.length();i++)if(entry.equals(manifest.getJSONObject(i).getString("path")))entryFact=manifest.getJSONObject(i);
            if(entryFact==null)throw new SecurityException("本机MCP入口脚本必须在当前真实冻结源清单内");workspace.resolve(entry,true);sourceCheck.check();packagePreparation=McpLocalPackage.inspect(config,source,runtime);
            baseScope=new JSONObject().put("format",FORMAT).put("purpose",INTENT).put("configurationId",action).put("actionId",action).put("pluginId",config.getString("id")).put("name",config.getString("name"))
                    .put("projectId",described.getString("projectId")).put("checkoutId",described.getString("checkoutId")).put("sourceDigest",described.getString("sourceDigest")).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("runtimeIdentity",runtime.identity())
                    .put("executable",config.getString("executable")).put("executableSha256",executable.getString("sha256")).put("entryPoint",entry).put("entryPointSha256",entryFact.getString("sha256")).put("argvDigest",ProjectSources.digest(new JSONObject().put("argv",argv))).put("argvCount",argv.length()).put("workingDirectory",".")
                    .put("configurationDigest",McpJson.digest(config)).put("protocolVersion",McpStdioClient.VERSION).put("timeoutMillis",TIMEOUT_MILLIS).put("writeByteLimit",WRITE_BYTE_LIMIT).put("reviewExpiresAt",expiresAt)
                    .put("executionWorkspace","per_operation_visible_copy").put("executionCopyId",action).put("sourceManifestScope","visible_source_only").put("hiddenFilesIncluded",false).put("dependencyCachesIncluded",false).put("wholeCheckoutVersion",false).put("executionLocation","this_phone").put("osSandbox",false).put("dependenciesDownloaded",false).put("credentialsSupported",false).put("environmentInputsSupported",false).put("modelInferenceAllowed",false).put("installationAuthorized",false).put("toolInvocationAuthorized",false);
            if(packagePreparation!=null){JSONObject launch=packagePreparation.command();baseScope.put(McpLocalPackage.PLAN,packagePreparation.plan()).put("preparationDigest",packagePreparation.digest()).put("launchExecutable",launch.getString("executable")).put("launchArgvDigest",ProjectSources.digest(new JSONObject().put("argv",launch.getJSONArray("argv")))).put("launchArgvCount",launch.getJSONArray("argv").length());}
            observation.onCancel(cancellation);
        }
        /** Native Plugin owner seals its original absent-or-exact-installation CAS before display. */
        public synchronized Review bindPluginTarget(PluginsModule actual)throws Exception {
            beforeReview();if(plugins!=null)throw new SecurityException("原MCP审阅已固定插件身份");plugins=Objects.requireNonNull(actual);scope=ProjectSources.copy(baseScope);JSONObject target=target(actual,baseScope.getString("pluginId"));scope.put("expectedInstallationId",target.getString("expectedInstallationId")).put("expectedStateRevision",target.get("expectedStateRevision"));scope.put("planDigest",ProjectSources.digest(scope));return this;
        }
        private void beforeReview()throws Exception{observation.check();if(closed||observation.isPaused()||System.currentTimeMillis()>=expiresAt)throw new SecurityException("原本机MCP审阅已取消或过期，请重新读取并明确确认");live(true);}
        private void live(boolean verifySource)throws Exception{
            if(!baseScope.getString("runtimeIdentity").equals(runtime.identity()))throw new SecurityException("原本机MCP运行环境已换代");if(verifySource)sourceCheck.check();
            if(plugins!=null){JSONObject current=target(plugins,baseScope.getString("pluginId"));if(!scope.getString("expectedInstallationId").equals(current.getString("expectedInstallationId"))||!ProjectSources.canonical(scope.get("expectedStateRevision")).equals(ProjectSources.canonical(current.get("expectedStateRevision"))))throw new SecurityException("原MCP安装/配置或absent状态已变化，请重新审阅");}
        }
        public synchronized JSONObject metadata()throws Exception{
            beforeReview();if(scope==null)throw new SecurityException("请先由原插件owner固定安装或absent身份");
            return ProjectSources.copy(scope).put("argv",new JSONArray(config.getJSONArray("argv").toString())).put("executionAuthorized",false).put("preparationApprovalRequired",packagePreparation!=null).put("preparationApproved",preparationApproved).put("risk","将把主人所选普通副本的可见源码复制为本次操作的真实工作副本，再执行其中的确切程序初始化协议并读取工具元数据；不是纯只读网络探测。隐藏配置和node_modules/.venv等依赖缓存不随带；未单独批准固定依赖准备时，缺依赖会明确失败，不自动回读全源或安装。可见源SHA不是整个原目录版本。程序可写本次执行副本、联网，并具有App同UID可达能力；PRoot和工作目录不是OS安全沙箱。不会调用远端工具、启动模型、未经批准下载依赖或授予插件安装/工具权限；发现后必须核对原进程已清场。参数仅支持公开非秘密文字，不能填写令牌、密码或秘密环境变量。若明确选择依赖准备，须另行批准准确锁文件、注册表、包管理器版本和同原进程内安装；普通发现确认不替代该批准。");
        }
        public synchronized void approvePreparation(String expectedPreparationDigest)throws Exception{beforeReview();if(scope==null||used||packagePreparation==null||!packagePreparation.digest().equals(expectedPreparationDigest))throw new SecurityException("请单独确认此原生审阅窗口准确的固定依赖准备；配置JSON不是安装许可");preparationApproved=true;}
        public synchronized Confirmed confirm(String expectedPlanDigest)throws Exception{beforeReview();if(packagePreparation!=null&&!preparationApproved)throw new SecurityException("请先独立审阅并批准本次固定依赖准备，再确认协议发现");if(scope==null||used||!scope.getString("planDigest").equals(expectedPlanDigest))throw new SecurityException("请确认此原生窗口显示的完整本机MCP范围；旧JSON不能批准程序");used=true;return new Confirmed(this);}
        @Override public synchronized void close(){if(transferred)return;closed=true;observation.removeCancelAction(cancellation);}
    }
    public static final class Confirmed implements AutoCloseable {
        private final Review review;private TaskStore store;private RunControl original;private boolean claimed,closed;private RuntimeWorkspace executionCopy;
        private Confirmed(Review review){this.review=review;}
        public String actionId(){return review.action;}
        public boolean runtimeMatchesForOwner(ManagedProjectRuntime actual){return actual==review.runtime;}
        public JSONObject scope()throws Exception{return ProjectSources.copy(review.scope);}
        public synchronized void requireBeforeTask(TaskStore actual,RunControl control)throws Exception {
            if(closed||store!=null||control==null||control.hasHostRun())throw new SecurityException("原本机MCP确认不能借已有任务或重复建立执行");review.beforeReview();Objects.requireNonNull(actual);control.check();
        }
        /** Only the original TaskStore calls this after persisting the real task/run/operation. */
        public synchronized void bindTaskForHost(TaskStore actual,RunControl control)throws Exception {
            if(closed||store!=null||!control.matchesHostRun(actionId(),actionId(),1))throw new SecurityException("本机MCP确认没有绑定原真实任务");store=Objects.requireNonNull(actual);original=control;review.transferred=true;review.observation.removeCancelAction(review.cancellation);
        }
        public synchronized void requireCurrentForHost(TaskStore actual,RunControl control)throws Exception {
            if(closed||store!=actual||original!=control||!control.matchesHostRun(actionId(),actionId(),1))throw new SecurityException("本机MCP需要同一主人原任务、原控制和一次性批准");control.check();if(control.isPaused())throw new SecurityException("原本机MCP任务已暂停");review.live(false);
        }
        public synchronized void claimForHost(TaskStore actual,RunControl control,RuntimeInvocation invocation)throws Exception {
            requireCurrentForHost(actual,control);if(claimed||invocation.originalControl()!=control||!actionId().equals(invocation.executionRef().operationId()))throw new SecurityException("原本机MCP程序已进入，只能核对原操作，不能重派");requireExact(invocation.exact(),scope());if(executionCopy==null||invocation.workspace()!=executionCopy||!scope().getString("executionCopyId").equals(executionCopy.directory().getName()))throw new SecurityException("MCP派发没有使用本次真实可见执行副本");claimed=true;
        }
        public synchronized RuntimeWorkspace workspaceForHost(TaskStore actual,RunControl control)throws Exception{requireCurrentForHost(actual,control);if(executionCopy==null)throw new SecurityException("本次原MCP真实执行副本尚未建立");return executionCopy;}
        public JSONObject sourceForHost(TaskStore actual,RunControl control)throws Exception{requireCurrentForHost(actual,control);return ProjectSources.copy(review.source);}
        public JSONObject commandForHost(TaskStore actual,RunControl control)throws Exception{requireCurrentForHost(actual,control);return review.packagePreparation!=null?review.packagePreparation.command():ProjectCommandSpec.normalize(new JSONObject().put("executable",review.config.getString("executable")).put("argv",review.config.getJSONArray("argv")).put("cwd",".").put("runMode","owned_service").put("terminal","pipes").put("timeoutMillis",TIMEOUT_MILLIS));}
        public ManagedProjectRuntime runtimeForHost(TaskStore actual,RunControl control)throws Exception{requireCurrentForHost(actual,control);return review.runtime;}
        public RuntimeInvocation invocationForHost(TaskStore actual,RunControl control,ProjectRuntime.LiveGate gate)throws Exception {
            requireCurrentForHost(actual,control);synchronized(this){if(executionCopy!=null)throw new SecurityException("本次MCP执行副本已准备，只查询原操作，不重建");review.sourceCheck.check();executionCopy=review.copies.create(actionId(),ProjectSources.copy(review.source),control,()->requireCurrentForHost(actual,control));if(review.packagePreparation!=null)review.packagePreparation.write(executionCopy);review.sourceCheck.check();}
            JSONObject scope=scope(),binding=new JSONObject().put("taskId",actionId()).put("runId",actionId()).put("operationId",actionId()).put("coordinatorEpoch",1);
            JSONObject payload=commandForHost(actual,control).put("checkoutId",scope.getString("checkoutId")).put("expectedSourceDigest",scope.getString("sourceDigest")).put("nativePipeContract",NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT).put("nativePipeWriteBudget",WRITE_BYTE_LIMIT).put("nativeMcpExecutionCopyId",actionId());
            if(review.packagePreparation!=null){review.packagePreparation.addPayload(payload);payload.put("nativeMcpConfiguration",ProjectSources.copy(review.config));}
            JSONObject identity=pipeIdentityForHost(scope);payload.put("nativePipeBindingDigest",ProjectSources.digest(NativePipeAdmission.bindingForHost(identity,binding,scope.getString("sourceDigest"),WRITE_BYTE_LIMIT)));
            JSONObject exact=new JSONObject().put("feature","local").put("command","project.runtime.run").put("intent",INTENT).put("stepId",STEP).put("payload",payload).put("requestDigest",ProjectSources.digest(payload));for(String field:new String[]{"projectId","checkoutId","sourceDigest","planDigest","runtimeId","runtimeIdentity"})exact.put(field,scope.get(field));
            requireExact(exact,scope);return new RuntimeInvocation(exact,binding,sourceForHost(actual,control),control,gate,workspaceForHost(actual,control));
        }
        @Override public synchronized void close(){closed=true;review.close();}
    }
    private static JSONObject configuration(JSONObject input)throws Exception {
        Set<String> fields=new HashSet<>();input.keys().forEachRemaining(fields::add);if(!CONFIG_FIELDS.containsAll(fields))throw new IllegalArgumentException("本机MCP首版不接受环境、凭据、Shell、任意宿主路径或自动安装字段");
        for(String field:new String[]{"id","name","checkoutId","expectedSourceDigest","executable"})if(!(input.opt(field) instanceof String))throw new IllegalArgumentException("本机MCP配置字段必须是明确文字："+field);for(String field:new String[]{"runtimeId","workingDirectory","protocolVersion"})if(input.has(field)&&!(input.opt(field) instanceof String))throw new IllegalArgumentException("本机MCP配置字段必须是明确文字："+field);
        String id=McpPromptData.name(input,"id",80),name=McpPromptData.name(input,"name",80),checkout=input.getString("checkoutId"),sha=input.getString("expectedSourceDigest"),executable=input.getString("executable");if(!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")||name.isEmpty()||name.length()>80||!checkout.matches("[a-f0-9-]{36}")||!sha.matches("[a-f0-9]{64}")||!Set.of("node","python3").contains(executable)||!ManagedProjectRuntime.RUNTIME_ID.equals(input.optString("runtimeId",ManagedProjectRuntime.RUNTIME_ID))||!".".equals(input.optString("workingDirectory","."))||!McpStdioClient.VERSION.equals(input.optString("protocolVersion",McpStdioClient.VERSION)))throw new IllegalArgumentException("本机MCP只支持已有冻结副本、仓根工作目录、原node/python3和明确2025-11-25协议");
        JSONArray argv=input.getJSONArray("argv");if(argv.length()<1||argv.length()>32)throw new IllegalArgumentException("本机MCP需要1至32个公开非秘密参数");String entry=VersionedWorkspace.path(argv.getString(0));if(!(executable.equals("node")?entry.matches(".*\\.(?:js|mjs|cjs)"):entry.endsWith(".py")))throw new IllegalArgumentException("首个参数必须是冻结源清单内的.py/.js/.mjs/.cjs脚本，不支持eval、-m或Shell");
        JSONObject normalized=ProjectCommandSpec.normalize(new JSONObject().put("executable",executable).put("argv",argv));for(int i=1;i<argv.length();i++){String value=argv.getString(i);if(value.matches("(?i).*--?(?:token|password|passwd|secret|api[-_]?key|access[-_]?key|credential)(?:[= :].*)?"))throw new IllegalArgumentException("本机MCP首版没有秘密参数通道；不能把凭据放入公开argv或摘要");}
        JSONObject result=new JSONObject().put("id",id).put("name",name).put("checkoutId",checkout).put("expectedSourceDigest",sha).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("executable",executable).put("argv",normalized.getJSONArray("argv")).put("workingDirectory",".").put("protocolVersion",McpStdioClient.VERSION);JSONObject preparation=McpLocalPackage.request(input);if(preparation!=null)result.put(McpLocalPackage.FIELD,preparation);return result;
    }
    private static JSONObject target(PluginsModule plugins,String id)throws Exception {
        JSONArray rows=plugins.snapshot().getJSONArray("plugins");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("id")))return new JSONObject().put("expectedInstallationId",row.getString("installationId")).put("expectedStateRevision",row.getLong("stateRevision"));}
        return new JSONObject().put("expectedInstallationId","").put("expectedStateRevision",JSONObject.NULL);
    }
    /** Frozen exact validation is necessary, never sufficient: only Confirmed + original ledger admit. */
    public static void requireExact(JSONObject exact,JSONObject scope)throws Exception {
        if(!"local".equals(exact.optString("feature"))||!"project.runtime.run".equals(exact.optString("command"))||!INTENT.equals(exact.optString("intent"))||!STEP.equals(exact.optString("stepId")))throw new SecurityException("不是原主人本机MCP元数据发现步骤");
        for(String field:new String[]{"projectId","checkoutId","sourceDigest","planDigest","runtimeId","runtimeIdentity"})if(!scope.getString(field).equals(exact.getString(field)))throw new SecurityException("本机MCP原计划/源/运行环境身份不同");
        JSONObject payload=exact.getJSONObject("payload");Set<String> fields=new HashSet<>();payload.keys().forEachRemaining(fields::add);Set<String> expected=new HashSet<>(Set.of("executable","argv","workingDirectory","environment","timeoutMillis","runMode","terminalMode","checkoutId","expectedSourceDigest","nativePipeContract","nativePipeBindingDigest","nativePipeWriteBudget","nativeMcpExecutionCopyId"));if(scope.has(McpLocalPackage.PLAN)){expected.add("nativeMcpPackageProgramDigest");expected.add("nativeMcpPackageJobDigest");expected.add("nativeMcpConfiguration");McpLocalPackage.requireProgram(exact);JSONObject original=configuration(payload.getJSONObject("nativeMcpConfiguration"));if(!scope.getString("configurationDigest").equals(McpJson.digest(original))||!scope.getString("argvDigest").equals(ProjectSources.digest(new JSONObject().put("argv",original.getJSONArray("argv"))))||!scope.getString("executable").equals(original.getString("executable")))throw new SecurityException("本机MCP固定包准备必须保留准确原程序配置");}if(!fields.equals(expected)||!NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT.equals(payload.optString("nativePipeContract")))throw new SecurityException("本机MCP管道合同或固定字段范围不同");JSONObject command=ProjectCommandSpec.fromPayload(payload);if(!"per_operation_visible_copy".equals(scope.optString("executionWorkspace"))||!"visible_source_only".equals(scope.optString("sourceManifestScope"))||!scope.getString("executionCopyId").equals(payload.optString("nativeMcpExecutionCopyId"))||!scope.getString("actionId").equals(scope.getString("executionCopyId"))||!scope.optString("launchExecutable",scope.getString("executable")).equals(command.getString("executable"))||!scope.optString("launchArgvDigest",scope.getString("argvDigest")).equals(ProjectSources.digest(new JSONObject().put("argv",command.getJSONArray("argv"))))||!".".equals(command.getString("workingDirectory"))||!"owned_service".equals(command.getString("runMode"))||!"pipes".equals(command.getString("terminalMode"))||command.getJSONObject("environment").length()!=0||command.getLong("timeoutMillis")!=TIMEOUT_MILLIS||!scope.getString("sourceDigest").equals(payload.getString("expectedSourceDigest"))||payload.getLong("nativePipeWriteBudget")!=WRITE_BYTE_LIMIT)throw new SecurityException("本机MCP程序、参数、作用范围或预算已变化");
    }
    public static JSONObject pipeIdentityForHost(JSONObject scope)throws Exception {
        String expected=scope.getString("expectedInstallationId");return new JSONObject().put("purpose",INTENT).put("configurationId",scope.getString("configurationId")).put("expectedInstallationId",expected.isEmpty()?"absent":expected).put("expectedStateRevision",scope.get("expectedStateRevision")).put("configDigest",scope.getString("configurationDigest")).put("runtimeGeneration",scope.getString("runtimeIdentity"));
    }
    /** Native protocol metadata carrier, never an installation/execute grant. */
    public static final class Metadata {
        private final String configurationId,planDigest,value;
        private Metadata(JSONObject scope,McpStdioClient client,JSONArray tools)throws Exception {
            configurationId=scope.getString("configurationId");planDigest=scope.getString("planDigest");JSONObject data=new JSONObject().put("server",client.serverIdentity()).put("serverDigest",client.serverDigest()).put("tools",new JSONArray(tools.toString())).put("protocolVersion",McpStdioClient.VERSION).put("untrusted",true).put("toolExecutionVerified",false).put("installationAuthorized",false).put("configurationDigest",scope.getString("configurationDigest"));
            if(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new SecurityException("本机MCP元数据超过256KiB，未裁成完整目录");value=data.toString();
        }
        public JSONObject data()throws Exception{return new JSONObject(value);}
    }
    static Metadata metadataForHost(Confirmed confirmed,TaskStore store,RunControl original,McpStdioClient client,JSONArray tools)throws Exception{confirmed.requireCurrentForHost(store,original);return new Metadata(confirmed.scope(),client,tools);}
    /** Only an original typed runtime receipt can become a process observation. Late cancellation
     * observations deliberately do not require the revoked dispatch/current gate. */
    public static final class Observation {
        private final String action,plan,receipt,process,metadata;private final boolean settled,protocolFinished;private final String state,originalExact,originalBinding;
        private Observation(JSONObject scope,RuntimeReceipt actual,Metadata found,boolean protocolFinished)throws Exception {
            this.protocolFinished=protocolFinished;originalExact=actual.reference().exact().toString();originalBinding=actual.reference().binding().toString();
            JSONObject value=actual.json();requireExact(actual.reference().exact(),scope);actual.reference().requireReceipt(value);action=scope.getString("actionId");plan=scope.getString("planDigest");
            if(!action.equals(actual.reference().operationId())||!action.equals(actual.reference().binding().getString("taskId"))||!action.equals(actual.reference().binding().getString("runId"))||actual.reference().binding().getInt("coordinatorEpoch")!=1||!Boolean.FALSE.equals(value.opt("argumentValuesIncluded"))||value.optInt("argvCount",-1)!=scope.optInt("launchArgvCount",scope.getInt("argvCount"))||!scope.optString("launchArgvDigest",scope.getString("argvDigest")).equals(value.optString("argvDigest")))throw new SecurityException("本机MCP回执不是原任务和准确程序摘要");
            if(found!=null&&(!scope.getString("configurationId").equals(found.configurationId)||!plan.equals(found.planDigest)))throw new SecurityException("本机MCP元数据不属于原配置审阅");metadata=found==null?null:found.value;
            boolean noChild=NativeProcessSupervisor.validatesNoChildReceipt(value);settled=Boolean.TRUE.equals(value.opt("receiptReceived"))&&Boolean.TRUE.equals(value.opt("resourcesReleased"))&&(noChild||Boolean.TRUE.equals(value.opt("processExited"))&&Boolean.TRUE.equals(value.opt("processGroupAbsent"))&&Boolean.TRUE.equals(value.opt("processSessionAbsent")));
            boolean clean=settled&&!noChild&&NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT.equals(value.optString("nativeResultContract"))&&Boolean.TRUE.equals(value.opt("outputComplete"))&&value.optInt("exitCode",-1)==0&&!value.optBoolean("timedOut")&&!value.optBoolean("cancelRequested");
            state=settled&&protocolFinished?(clean&&found!=null?"completed":"failed"):Boolean.TRUE.equals(value.opt("ownedWorkerLive"))?"running":"unknown";
            JSONObject safe=new JSONObject();for(String key:new String[]{"id","operationId","status","receiptReceived","processExited","resourcesReleased","processGroupAbsent","processSessionAbsent","outputComplete","cleanupScope","allDescendantsTerminated","unobservedDetachedDescendantsPossible","ownedWorkerLive","nativeStartObserved","processLaunched","launchPhase","noChildCreated","launchErrno","terminationSignal","nativeResultContract","nativeResultCode","exitCode","timedOut","cancelRequested","logCursor","storedBytes","receivedBytes","outputTruncated","summaryOutputTruncated","argvDigest","argvCount","argumentValuesIncluded"})if(value.has(key))safe.put(key,value.get(key));process=safe.toString();
            JSONObject durable=new JSONObject().put("actionId",action).put("planDigest",plan).put("status",state).put("processSettled",settled).put("protocolFinished",protocolFinished).put("outcomeSettled",settled&&protocolFinished).put("processReceipt",safe).put("metadataAvailable",found!=null).put("replayAllowed",false).put("installationAuthorized",false).put("toolInvocationAuthorized",false).put("modelInferenceStarted",false);if(found!=null)durable.put("metadata",found.data()).put("metadataDigest",McpJson.digest(found.data()));receipt=durable.toString();
        }
        public String actionId(){return action;}public String planDigest(){return plan;}public String status(){return state;}public boolean processSettled(){return settled;}public boolean outcomeSettled(){return settled&&protocolFinished;}public boolean protocolFinished(){return protocolFinished;}
        public void requireOriginalJobForHost(JSONObject job)throws Exception{if(!ProjectSources.canonical(new JSONObject(originalExact)).equals(ProjectSources.canonical(job.getJSONObject("exact")))||!ProjectSources.canonical(new JSONObject(originalBinding)).equals(ProjectSources.canonical(job.getJSONObject("binding"))))throw new SecurityException("本机MCP回执不是原SQL已绑定的准确进程");}
        public JSONObject durableReceipt()throws Exception{return new JSONObject(receipt);}
        public JSONObject publicSummary()throws Exception{JSONObject result=new JSONObject(receipt);result.remove("metadata");if(metadata!=null){JSONObject data=new JSONObject(metadata);result.put("server",data.getJSONObject("server")).put("toolCount",data.getJSONArray("tools").length());}return result;}
        public JSONObject processSummary()throws Exception{return new JSONObject(process);}
    }
    public static Observation observeForHost(JSONObject originalScope,RuntimeReceipt actual)throws Exception{return new Observation(originalScope,actual,null,false);}
    static Observation observeForHost(Confirmed confirmed,RuntimeReceipt actual,Metadata metadata,boolean protocolFinished)throws Exception{return new Observation(confirmed.scope(),actual,metadata,protocolFinished);}

}
