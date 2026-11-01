package dev.pocketengine.features.local.project;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.local.TermuxWorkspaceTransfer;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Selected output bytes are observations after a build, not a claim of exclusive build authorship. */
public final class GeneratedArtifacts {
    private GeneratedArtifacts(){}
    public static JSONObject prepare(EngineModule local,JSONObject input)throws Exception {
        JSONObject plan=ProjectBuildPlan.verified(input.getJSONObject("plan"));
        if(!"build".equals(input.getString("stepId")))throw new SecurityException("生成产物必须关联真实build步骤");
        JSONObject build;
        if(input.has("binding")){
            JSONObject bound=input.getJSONObject("binding");
            local.handle("project.poll",ProjectSources.copy(input));
            build=nativeBuild(plan,job(local,bound.getString("operationId")),bound);
        }else build=ownerBuild(plan,job(local,input.getString("jobId")));
        String path=path(input.getString("path"));
        for(int i=0;i<plan.getJSONArray("sourceManifest").length();i++)if(path.equals(plan.getJSONArray("sourceManifest").getJSONObject(i).getString("path")))throw new SecurityException("源码文件请使用源文件预览，不能改标为生成产物");
        currentSource(local,plan);requireLatest(build,local.handle("listJobs",new JSONObject()).getJSONArray("termuxJobs"));
        JSONObject core=new JSONObject().put("format","pocketworld.generated-artifact-plan.v1").put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest"))
                .put("buildPlan",plan).put("buildJobId",build.getString("jobId")).put("buildReceiptIdentity",build.getString("receiptIdentity"))
                .put("buildCheckoutSequence",build.getLong("checkoutSequence")).put("buildReceipt",build).put("path",path).put("maxBytes",TermuxWorkspaceTransfer.MAX_BYTES)
                .put("atomicArtifactFence",false).put("buildOutputAttribution","selected_output_observed_after_build")
                .put("warnings",new JSONArray().put("单一UTF-8文本最多32000字节；不会拉取依赖树或HTML外部资源").put("核验收集时实际字节；同Termux账号在构建后或校验后的写入不具备原子隔离"));
        String digest=ProjectSources.digest(core);core.put("artifactPlanId",digest).put("artifactPlanDigest",digest);JSONObject selection=ProjectSources.copy(core);
        return core.put("hostAction",new JSONObject().put("feature","local").put("command","termux.collect").put("payload",new JSONObject()
                .put("checkoutId",core.getString("checkoutId")).put("expectedSourceDigest",core.getString("sourceDigest")).put("artifactSelection",selection)));
    }
    public static JSONObject verified(JSONObject supplied)throws Exception {
        JSONObject core=ProjectSources.copy(supplied);core.remove("hostAction");core.remove("artifactPlanId");core.remove("artifactPlanDigest");
        String digest=supplied.getString("artifactPlanDigest");
        if(!"pocketworld.generated-artifact-plan.v1".equals(core.getString("format"))||!digest.matches("[a-f0-9]{64}")||!digest.equals(supplied.getString("artifactPlanId"))||!digest.equals(ProjectSources.digest(core)))throw new SecurityException("生成产物选择已变化，请重新核对");
        JSONObject plan=ProjectBuildPlan.verified(core.getJSONObject("buildPlan"));String path=path(core.getString("path"));
        if(!plan.getString("checkoutId").equals(core.getString("checkoutId"))||!plan.getString("sourceDigest").equals(core.getString("sourceDigest"))||!integer(core.opt("maxBytes"),TermuxWorkspaceTransfer.MAX_BYTES)||positive(core.opt("buildCheckoutSequence"))<1||!Boolean.FALSE.equals(core.opt("atomicArtifactFence"))||!"selected_output_observed_after_build".equals(core.optString("buildOutputAttribution")))throw new SecurityException("生成产物来源或上限无效");
        for(int i=0;i<plan.getJSONArray("sourceManifest").length();i++)if(path.equals(plan.getJSONArray("sourceManifest").getJSONObject(i).getString("path")))throw new SecurityException("不能把源码回收为生成产物");
        return core.put("artifactPlanId",digest).put("artifactPlanDigest",digest);
    }
    /** Validates the real original owner job, not the buildReceipt JSON in a supplied selection. */
    public static JSONObject ownerBuild(JSONObject plan,JSONObject raw)throws Exception {
        plan=ProjectBuildPlan.verified(plan);JSONObject step=ProjectBuildPlan.step(plan,"build"),payload=step.getJSONObject("payload"),link=raw.optJSONObject("projectExecution");
        successful(raw);String operation=ProjectBuildPlan.identity(raw.getString("id"));
        if(!"termux.run".equals(step.getString("localCommand"))||!"command".equals(raw.optString("kind"))||!plan.getString("checkoutId").equals(raw.optString("checkoutId"))||!plan.getString("sourceDigest").equals(raw.optString("sourceDigest"))
                ||!"pre_exec_manifest_verified".equals(raw.optString("sourceCheckStatus"))||!payload.getString("executable").equals(raw.optString("requestedExecutable"))||!ProjectSources.canonical(payload.getJSONArray("argv")).equals(ProjectSources.canonical(raw.optJSONArray("requestedArgv")))
                ||link==null||!"owner_tracked_job".equals(link.optString("authority"))||!"build".equals(link.optString("stepId"))||!"build".equals(link.optString("intent"))||!plan.getString("planDigest").equals(link.optString("planDigest"))
                ||!plan.getString("sourceDigest").equals(link.optString("sourceDigest"))||!ProjectSources.digest(payload).equals(link.optString("requestDigest"))||!operation.equals(raw.optString("operationId"))||!operation.equals(link.optString("runId"))||!operation.equals(link.optString("operationId")))throw new SecurityException("需要同一源码与精确原生主人build回执");
        JSONObject binding=new JSONObject().put("authority","owner_tracked_job").put("taskId",ProjectBuildPlan.identity(link.getString("hostTaskId"))).put("runId",operation).put("operationId",operation);
        String identity=ProjectSources.digest(new JSONObject().put("planDigest",plan.getString("planDigest")).put("stepId","build").put("jobId",operation).put("binding",binding));
        return new JSONObject().put("receiptIdentity",identity).put("intent","build").put("stepId","build").put("jobId",operation).put("binding",binding)
                .put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest")).put("planDigest",plan.getString("planDigest"))
                .put("checkoutSequence",positive(raw.opt("checkoutSequence"))).put("status","completed").put("exitCode",0).put("receiptReceived",true)
                .put("cancelRequested",false).put("outputTruncated",false).put("sourceVerifiedBeforeExec",true).put("atomicSourceFence",false);
    }
    /** The selection cannot substitute its embedded receipt for the actual original backend job. */
    public static JSONObject originalBuild(JSONObject selection,JSONObject actual)throws Exception {
        selection=verified(selection);JSONObject binding=selection.getJSONObject("buildReceipt").getJSONObject("binding");
        return "native_host_operation".equals(binding.optString("authority"))?nativeBuild(selection.getJSONObject("buildPlan"),actual,binding):ownerBuild(selection.getJSONObject("buildPlan"),actual);
    }
    private static JSONObject nativeBuild(JSONObject plan,JSONObject raw,JSONObject supplied)throws Exception {
        plan=ProjectBuildPlan.verified(plan);JSONObject step=ProjectBuildPlan.step(plan,"build"),exact=ProjectDevelopment.exact(plan,step),link=raw.getJSONObject("projectExecution");
        successful(raw);String operation=ProjectBuildPlan.identity(raw.getString("id"));
        if(!"termux.run".equals(exact.getString("command"))||!"command".equals(raw.optString("kind"))||!operation.equals(raw.optString("operationId"))||!operation.equals(supplied.getString("operationId"))
                ||!plan.getString("checkoutId").equals(raw.optString("checkoutId"))||!plan.getString("sourceDigest").equals(raw.optString("sourceDigest"))||!"pre_exec_manifest_verified".equals(raw.optString("sourceCheckStatus"))
                ||!"native_host_operation".equals(link.optString("authority"))||!step.getJSONObject("payload").getString("executable").equals(raw.optString("requestedExecutable"))
                ||!ProjectSources.canonical(step.getJSONObject("payload").getJSONArray("argv")).equals(ProjectSources.canonical(raw.optJSONArray("requestedArgv"))))throw new SecurityException("需要此原任务实际成功build回执");
        JSONObject binding=new JSONObject().put("authority","native_host_operation");
        for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"}){
            if(!ProjectSources.canonical(supplied.get(key)).equals(ProjectSources.canonical(link.get(key))))throw new SecurityException("构建回执不属于原task/run");binding.put(key,link.get(key));
        }
        for(String key:new String[]{"command","projectId","checkoutId","planDigest","stepId","intent","sourceDigest","requestDigest"})if(!ProjectSources.canonical(exact.get(key)).equals(ProjectSources.canonical(link.get(key))))throw new SecurityException("构建回执与精确项目步骤不同");
        String identity=ProjectSources.digest(new JSONObject().put("planDigest",plan.getString("planDigest")).put("stepId","build").put("jobId",operation).put("binding",binding));
        return new JSONObject().put("receiptIdentity",identity).put("intent","build").put("stepId","build").put("jobId",operation).put("binding",binding)
                .put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest")).put("planDigest",plan.getString("planDigest")).put("checkoutSequence",positive(raw.opt("checkoutSequence")))
                .put("status","completed").put("exitCode",0).put("receiptReceived",true).put("cancelRequested",false).put("outputTruncated",false).put("sourceVerifiedBeforeExec",true).put("atomicSourceFence",false);
    }
    /** Native host supplies this live link after claiming the existing collection operation. */
    public static JSONObject taskCollection(JSONObject selection,JSONObject nativeLink)throws Exception {
        selection=verified(selection);JSONObject build=selection.getJSONObject("buildReceipt").getJSONObject("binding");
        if(!"native_host_operation".equals(build.optString("authority"))||!"native_host_operation".equals(nativeLink.optString("authority"))||!"termux.collect".equals(nativeLink.optString("command")))throw new SecurityException("回收必须沿原任务执行位置");
        if(!build.getString("taskId").equals(nativeLink.getString("taskId")))throw new SecurityException("不能跨任务回收构建产物");
        if(build.getString("operationId").equals(nativeLink.getString("operationId"))||!selection.getString("checkoutId").equals(nativeLink.getString("checkoutId"))||!selection.getString("sourceDigest").equals(nativeLink.getString("sourceDigest")))throw new SecurityException("回收操作/源码身份无效");
        return ProjectSources.copy(nativeLink).put("artifactPlanDigest",selection.getString("artifactPlanDigest")).put("buildJobId",selection.getString("buildJobId"))
                .put("buildReceiptIdentity",selection.getString("buildReceiptIdentity")).put("buildCheckoutSequence",selection.getLong("buildCheckoutSequence")).put("path",selection.getString("path"));
    }
    public static void requireBuild(JSONObject selection,JSONObject build)throws Exception {
        if(!selection.getString("buildJobId").equals(build.getString("jobId"))||!selection.getString("buildReceiptIdentity").equals(build.getString("receiptIdentity"))||selection.getLong("buildCheckoutSequence")!=build.getLong("checkoutSequence"))throw new SecurityException("原构建执行身份已变化");
    }
    public static void requireLatest(JSONObject build,JSONArray jobs)throws Exception {
        long sequence=positive(build.opt("checkoutSequence"));
        for(int i=0;i<jobs.length();i++){
            JSONObject other=jobs.getJSONObject(i);if(!build.getString("checkoutId").equals(other.optString("checkoutId"))||build.getString("jobId").equals(other.optString("id"))||!java.util.Set.of("command","project_stage").contains(other.optString("kind")))continue;
            // Legacy jobs predate the first sequence-bearing execution. A legacy build itself is refused.
            if(other.has("checkoutSequence")&&positive(other.opt("checkoutSequence"))>=sequence)throw new SecurityException("此副本已有后续构建/运行/版本准备，旧build产物不可再采纳");
        }
    }
    public static JSONObject ownerCollection(JSONObject selection,JSONObject input)throws Exception {
        selection=verified(selection);
        if(!selection.getString("checkoutId").equals(input.optString("checkoutId"))||!selection.getString("sourceDigest").equals(input.optString("expectedSourceDigest")))throw new SecurityException("收集请求与冻结源码选择不同");
        String task=ProjectBuildPlan.identity(input.optString("_taskId")),run=ProjectBuildPlan.identity(input.optString("_runId")),operation=ProjectBuildPlan.identity(input.optString("operationId"));
        if(!run.equals(operation))throw new SecurityException("收集必须经过原生主人tracked路径");
        return new JSONObject().put("authority","owner_tracked_job").put("hostTaskId",task).put("runId",run).put("operationId",operation)
                .put("artifactPlanDigest",selection.getString("artifactPlanDigest")).put("checkoutId",selection.getString("checkoutId")).put("sourceDigest",selection.getString("sourceDigest"))
                .put("buildJobId",selection.getString("buildJobId")).put("buildReceiptIdentity",selection.getString("buildReceiptIdentity")).put("buildCheckoutSequence",selection.getLong("buildCheckoutSequence")).put("path",selection.getString("path"));
    }
    public static JSONObject read(EngineModule local,JSONObject input)throws Exception {
        JSONObject selection=verified(input.getJSONObject("collectionPlan")),plan=selection.getJSONObject("buildPlan"),build=originalBuild(selection,job(local,selection.getString("buildJobId")));
        requireBuild(selection,build);currentSource(local,plan);requireLatest(build,local.handle("listJobs",new JSONObject()).getJSONArray("termuxJobs"));
        JSONObject raw=job(local,input.getString("collectionJobId"));successful(raw);JSONObject link=raw.optJSONObject("artifactCollection");
        if(!"workspace_collect".equals(raw.optString("kind"))||!"selected_text_artifact".equals(raw.optString("collectionMode"))||!Boolean.TRUE.equals(raw.opt("artifactReady"))||!"pre_exec_manifest_verified".equals(raw.optString("sourceCheckStatus"))||link==null)throw new SecurityException("需完整成功的原生选定文本产物回执");
        for(String key:new String[]{"checkoutId","sourceDigest","artifactPlanDigest","buildJobId","buildReceiptIdentity","path","buildCheckoutSequence"})if(!ProjectSources.canonical(selection.get(key)).equals(ProjectSources.canonical(link.opt(key))))throw new SecurityException("收集回执不属于此选定产物/构建");
        String id=ProjectBuildPlan.identity(raw.getString("id"));
        if(!id.equals(raw.optString("operationId"))||!id.equals(link.optString("operationId"))||!selection.getString("checkoutId").equals(raw.optString("checkoutId"))||!selection.getString("sourceDigest").equals(raw.optString("sourceDigest")))throw new SecurityException("收集原生task/run关联无效");
        JSONObject binding;
        if(input.has("binding")){
            JSONObject supplied=input.getJSONObject("binding"),expected=taskCollection(selection,raw.getJSONObject("projectExecution"));
            for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"})if(!ProjectSources.canonical(supplied.get(key)).equals(ProjectSources.canonical(expected.get(key))))throw new SecurityException("收集回执来自其他原操作");
            if(!ProjectSources.canonical(expected).equals(ProjectSources.canonical(link)))throw new SecurityException("实际回收来源不一致");
            binding=new JSONObject().put("authority","native_host_operation");for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"})binding.put(key,expected.get(key));
        }else{
            if(!"owner_tracked_job".equals(link.optString("authority"))||!id.equals(link.optString("runId")))throw new SecurityException("需要原生主人tracked收集回执");
            binding=new JSONObject().put("authority","owner_tracked_job").put("taskId",ProjectBuildPlan.identity(link.getString("hostTaskId"))).put("runId",id).put("operationId",id);
        }
        String identity=ProjectSources.digest(new JSONObject().put("artifactPlanDigest",selection.getString("artifactPlanDigest")).put("jobId",id).put("binding",binding));
        JSONObject artifact=raw.getJSONObject("collectedArtifact");String content=artifact.getString("content");byte[] bytes=content.getBytes(StandardCharsets.UTF_8);String sha=ProjectSources.sha256(bytes);
        if(!selection.getString("path").equals(artifact.getString("path"))||bytes.length>TermuxWorkspaceTransfer.MAX_BYTES||content.indexOf('\0')>=0||!integer(artifact.opt("bytes"),bytes.length)||!sha.equals(artifact.getString("sha256")))throw new SecurityException("实际收集的文件字节/摘要不一致");
        JSONObject receipt=new JSONObject().put("jobId",id).put("receiptIdentity",identity).put("intent","artifact_collect").put("binding",binding).put("status","completed").put("exitCode",0).put("receiptReceived",true).put("cancelRequested",false).put("outputTruncated",false)
                .put("checkoutId",selection.getString("checkoutId")).put("sourceDigest",selection.getString("sourceDigest")).put("collectionPlanDigest",selection.getString("artifactPlanDigest")).put("buildJobId",build.getString("jobId")).put("buildReceiptIdentity",build.getString("receiptIdentity")).put("path",selection.getString("path")).put("sha256",sha).put("bytes",bytes.length);
        return new JSONObject().put("checkoutId",selection.getString("checkoutId")).put("sourceDigest",selection.getString("sourceDigest")).put("jobId",build.getString("jobId")).put("buildJobId",build.getString("jobId")).put("buildReceiptIdentity",build.getString("receiptIdentity"))
                .put("collectionJobId",id).put("collectionReceiptIdentity",identity).put("path",selection.getString("path")).put("sha256",sha).put("bytes",bytes.length).put("content",content).put("mimeType",mime(selection.getString("path"))).put("truncated",false)
                .put("artifactOrigin","termux_selected_output").put("generatedArtifact",true).put("atomicArtifactFence",false).put("buildOutputAttribution","selected_output_observed_after_build").put("buildReceipt",build).put("collectionReceipt",receipt);
    }
    private static void currentSource(EngineModule local,JSONObject plan)throws Exception {JSONObject current=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",plan.getString("checkoutId"))));if(!plan.getString("sourceDigest").equals(current.getString("sourceDigest")))throw new SecurityException("本地源码已变化，旧build产物不能采纳");}
    private static JSONObject job(EngineModule local,String id)throws Exception{return local.handle("getJob",new JSONObject().put("jobId",ProjectBuildPlan.identity(id)).put("termux",true));}
    private static void successful(JSONObject raw){if(!"completed".equals(raw.optString("status"))||!integer(raw.opt("exitCode"),0)||!Boolean.TRUE.equals(raw.opt("receiptReceived"))||raw.has("cancelRequested")&&!Boolean.FALSE.equals(raw.opt("cancelRequested"))||raw.has("outputTruncated")&&!Boolean.FALSE.equals(raw.opt("outputTruncated")))throw new SecurityException("需完整成功且未取消/截断的真实回执；不重派未知操作");}
    private static boolean integer(Object value,long exact){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==exact;}
    private static long positive(Object value){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw new SecurityException("此历史执行没有可核对的原生顺序，请重新明确批准构建");return ((Number)value).longValue();}
    private static String path(String value){String path=VersionedWorkspace.path(value);if(!path.equals(path.trim())||!path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(html?|css|[mc]?js|json|txt|md|log|svg|xml|map|csv|ya?ml|toml)$"))throw new IllegalArgumentException("请选择单一UTF-8文本产物；不接收二进制/APK/通配符");return path;}
    private static String mime(String path){String lower=path.toLowerCase(java.util.Locale.ROOT);return lower.endsWith(".html")||lower.endsWith(".htm")?"text/html":lower.endsWith(".css")?"text/css":lower.endsWith(".js")||lower.endsWith(".mjs")||lower.endsWith(".cjs")?"text/javascript":lower.endsWith(".json")?"application/json":"text/plain";}
}
