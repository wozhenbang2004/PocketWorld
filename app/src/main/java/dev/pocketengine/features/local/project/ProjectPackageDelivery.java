package dev.pocketengine.features.local.project;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/** Exact immutable package facts, not a grant, filesystem path, freezer or second catalog.
 * Callers must first prove their original private/team operation in the original TaskStore. */
public final class ProjectPackageDelivery {
    public static final String EVIDENCE_FORMAT="pocketworld.project-package-evidence.v1";
    private ProjectPackageDelivery(){}
    public static String actionId(String operation) {return UUID.nameUUIDFromBytes(("project-package-operation:"+ProjectBuildPlan.identity(operation)).getBytes(StandardCharsets.UTF_8)).toString();}
    public static JSONObject archiveIdentity(ProjectPackageArchive.Frozen frozen)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long[] count={0};
        OutputStream sink=new OutputStream(){@Override public void write(int value)throws java.io.IOException{if(++count[0]>ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new java.io.IOException("项目包超过20MiB");digest.update((byte)value);}@Override public void write(byte[] bytes,int offset,int length)throws java.io.IOException{count[0]+=length;if(count[0]>ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new java.io.IOException("项目包超过20MiB");digest.update(bytes,offset,length);}};
        ProjectPackageArchive.write(frozen,sink);StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return new JSONObject().put("archiveSha256",hash.toString()).put("archiveBytes",count[0]);
    }
    public static JSONObject selectionSummary(JSONObject plan)throws Exception {
        JSONObject manifest=plan.getJSONObject("packageManifest"),payload=ProjectBuildPlan.step(plan,"freeze_package").getJSONObject("payload");
        return new JSONObject().put("mode",manifest.getString("mode")).put("entryPath",manifest.getString("entryPath")).put("manifestIdentity",payload.getString("expectedManifestIdentity")).put("archiveSha256",payload.getString("expectedArchiveSha256")).put("archiveBytes",payload.getLong("expectedArchiveBytes")).put("fileCount",manifest.getJSONArray("files").length()).put("directoryCount",manifest.getJSONArray("directories").length()).put("expandedBytes",manifest.getLong("bytes")).put("omittedCount",manifest.getJSONArray("omitted").length()).put("fullManifestInOriginalPlan",true).put("automaticallyExported",false).put("businessVerified",false);
    }
    /** Called with an original native backend result, never a model's claimed status. */
    public static boolean terminalReceipt(JSONObject exact,JSONObject backend)throws Exception {
        if(!"freezeProjectPackage".equals(exact.optString("command"))||!"freeze_package".equals(exact.optString("intent"))||!"project_package_freeze".equals(backend.optString("kind"))||!"app_private_checkout".equals(backend.optString("provider"))||!Boolean.TRUE.equals(backend.opt("nativeFileOperation"))||!Boolean.TRUE.equals(backend.opt("receiptReceived"))||!backend.has("processExitCode")||!backend.isNull("processExitCode"))return false;
        Object code=backend.opt("nativeOperationCode"),exit=backend.opt("exitCode");if(!(code instanceof Integer||code instanceof Long)||!(exit instanceof Integer||exit instanceof Long)||((Number)code).longValue()!=((Number)exit).longValue())return false;
        if("failed".equals(backend.optString("status")))return ((Number)code).longValue()!=0&&Boolean.FALSE.equals(backend.opt("nativePublicationEntered"))&&"not_dispatched".equals(backend.optString("effectStatus"))&&!backend.has("packageArtifact");
        if(!"completed".equals(backend.optString("status"))||((Number)code).longValue()!=0||!Boolean.TRUE.equals(backend.opt("nativePublicationEntered"))||!Boolean.TRUE.equals(backend.opt("artifactReady"))||!"native_checkout_verified".equals(backend.optString("sourceCheckStatus")))return false;
        JSONObject payload=exact.getJSONObject("payload"),artifact=backend.optJSONObject("packageArtifact");if(artifact==null)return false;
        if(!exact.getString("projectId").equals(artifact.optString("projectId"))||!exact.getString("checkoutId").equals(artifact.optString("checkoutId"))||!exact.getString("sourceDigest").equals(artifact.optString("sourceDigest"))||!payload.getString("expectedManifestIdentity").equals(artifact.optString("manifestIdentity"))||!payload.getString("expectedArchiveSha256").equals(artifact.optString("sha256"))||payload.getLong("expectedArchiveBytes")!=artifact.optLong("sizeBytes",-1)||!actionId(backend.getString("operationId")).equals(artifact.optString("ownerActionId"))||!"project_bundle".equals(artifact.optString("kind")))return false;
        String id=UUID.nameUUIDFromBytes(("project-package:"+actionId(backend.getString("operationId"))).getBytes(StandardCharsets.UTF_8)).toString();return id.equals(artifact.optString("id"))&&id.equals(artifact.optString("versionId"))&&Boolean.TRUE.equals(artifact.opt("persisted"));
    }
    /** Pure original-row projection shared by separately authorized private and team consumers.
     * This validates evidence only: it cannot establish task origin, current grants or team scope. */
    public static JSONObject evidence(JSONObject fullPlan,JSONObject operation)throws Exception {
        JSONObject plan=ProjectBuildPlan.verified(fullPlan);if(!"freeze_package".equals(plan.optString("intent"))||!"project_execute".equals(operation.optString("tool"))||!"receipt".equals(operation.optString("state")))throw new SecurityException("原操作不是已确定的项目包保存");
        JSONObject args=operation.getJSONObject("arguments"),step=ProjectBuildPlan.step(plan,args.getString("stepId")),exact=ProjectDevelopment.exact(plan,step),envelope=operation.getJSONObject("receipt"),route=envelope.getJSONObject("projectExecution"),backend=envelope.getJSONObject("executionReceipt"),actual=backend.getJSONObject("projectExecution");
        for(String key:new String[]{"command","projectId","checkoutId","sourceDigest","planDigest","stepId","intent","requestDigest"})if(!ProjectSources.canonical(exact.get(key)).equals(ProjectSources.canonical(route.opt(key))))throw new SecurityException("原项目包计划与操作路由不同");
        for(String key:new String[]{"authority","command","taskId","runId","operationId","coordinatorEpoch","projectId","checkoutId","sourceDigest","planDigest","stepId","intent","requestDigest"})if(!ProjectSources.canonical(route.get(key)).equals(ProjectSources.canonical(actual.opt(key))))throw new SecurityException("原项目包回执属于其他操作");
        if(!"completed".equals(envelope.optString("status"))||!Boolean.TRUE.equals(envelope.opt("accepted"))||!"project_file_receipt".equals(envelope.optString("code"))||!"native_host_operation".equals(route.optString("authority"))||!operation.getString("operationId").equals(route.optString("operationId"))||!operation.getString("runId").equals(route.optString("runId"))||!operation.getString("operationId").equals(backend.optString("operationId"))||!operation.getString("operationId").equals(backend.optString("id"))||!terminalReceipt(exact,backend)||!"completed".equals(backend.optString("status")))throw new SecurityException("原项目包没有完整原生保存回执");
        JSONObject payload=step.getJSONObject("payload");return new JSONObject().put("format",EVIDENCE_FORMAT).put("taskId",route.getString("taskId")).put("operationId",operation.getString("operationId")).put("runId",route.getString("runId")).put("coordinatorEpoch",route.getInt("coordinatorEpoch")).put("planDigest",plan.getString("planDigest")).put("projectId",plan.getString("projectId")).put("checkoutId",plan.getString("checkoutId")).put("sourceDigest",plan.getString("sourceDigest")).put("manifestIdentity",payload.getString("expectedManifestIdentity")).put("archiveSha256",payload.getString("expectedArchiveSha256")).put("archiveBytes",payload.getLong("expectedArchiveBytes")).put("packageArtifact",metadata(backend.getJSONObject("packageArtifact"))).put("authorityAdded",false);
    }
    public static final class Reference {
        public final String artifactId,archiveSha256,manifestIdentity,projectId,checkoutId,sourceDigest,taskId,operationId,planDigest;
        public final long archiveBytes;private final JSONObject metadata;
        private Reference(JSONObject evidence,JSONObject actual)throws Exception {artifactId=actual.getString("id");archiveSha256=evidence.getString("archiveSha256");archiveBytes=evidence.getLong("archiveBytes");manifestIdentity=evidence.getString("manifestIdentity");projectId=evidence.getString("projectId");checkoutId=evidence.getString("checkoutId");sourceDigest=evidence.getString("sourceDigest");taskId=evidence.getString("taskId");operationId=evidence.getString("operationId");planDigest=evidence.getString("planDigest");metadata=metadata(actual);}
        public JSONObject json()throws Exception {return ProjectSources.copy(metadata).put("artifactId",artifactId).put("taskId",taskId).put("operationId",operationId).put("planDigest",planDigest).put("archiveSha256",archiveSha256).put("archiveBytes",archiveBytes).put("immutable",true).put("authorityAdded",false).put("automaticallyExported",false);}
    }
    public static Reference requireArtifact(JSONObject nativeEvidence,JSONObject actualCatalogMetadata)throws Exception {
        if(!EVIDENCE_FORMAT.equals(nativeEvidence.optString("format")))throw new SecurityException("缺少原操作的项目包凭证");JSONObject recorded=nativeEvidence.getJSONObject("packageArtifact"),actual=actualCatalogMetadata;
        for(String key:new String[]{"id","kind","projectId","checkoutId","sourceDigest","manifestIdentity","sha256","sizeBytes","ownerActionId","versionId","versionSequence"})if(!ProjectSources.canonical(recorded.get(key)).equals(ProjectSources.canonical(actual.opt(key))))throw new SecurityException("当前原成果与操作固定版本不同");
        for(String key:new String[]{"projectId","checkoutId","sourceDigest","manifestIdentity"})if(!nativeEvidence.getString(key).equals(actual.optString(key)))throw new SecurityException("项目包引用归属不同");
        if(!"project_bundle".equals(actual.optString("kind"))||!Boolean.TRUE.equals(actual.opt("persisted"))||"retired".equals(actual.optString("availability"))||!nativeEvidence.getString("archiveSha256").equals(actual.getString("sha256"))||nativeEvidence.getLong("archiveBytes")!=actual.getLong("sizeBytes")||!actionId(nativeEvidence.getString("operationId")).equals(actual.getString("ownerActionId")))throw new SecurityException("原固定包的字节或保存动作不一致");
        JSONObject manifest=actual.getJSONObject("manifest");if(!nativeEvidence.getString("manifestIdentity").equals(ProjectSources.digest(manifest)))throw new SecurityException("原项目包清单身份不同");return new Reference(nativeEvidence,actual);
    }
    public static JSONObject metadata(JSONObject artifact)throws Exception {
        JSONObject result=new JSONObject();for(String key:new String[]{"id","kind","projectId","checkoutId","sourceDigest","manifestIdentity","sha256","sizeBytes","ownerActionId","versionId","versionSequence","mode","entryPath","previewRoot","name","mimeType","createdAt","fileCount","expandedBytes","omittedCount","persisted"})if(artifact.has(key))result.put(key,artifact.get(key));return result;
    }
}
