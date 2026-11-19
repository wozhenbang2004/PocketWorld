package dev.pocketengine.features.local.project;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Receipt of one owner-prepared private task package. No checkout path, task grant or transport. */
public final class ProjectTaskSourceOrigin {
    public static final String KIND="task_source_package";
    private ProjectTaskSourceOrigin(){}
    public static boolean is(JSONObject evidence){return evidence!=null&&KIND.equals(evidence.opt("sourceKind"));}
    public static JSONObject pin(JSONObject source)throws Exception {
        JSONObject artifact=source.getJSONObject("packageArtifact");
        if(!"fixed_workspace_project_artifact".equals(source.opt("sourceKind"))||!"source_project".equals(artifact.opt("mode"))||
                !Boolean.FALSE.equals(source.opt("authorityAdded"))||!source.getString("operationId").equals(source.getString("packageOperationId"))||
                !source.getString("artifactId").equals(artifact.getString("id"))||!source.getString("artifactSha256").equals(source.getString("archiveSha256"))||
                !source.getString("artifactSha256").equals(artifact.getString("sha256"))||source.getInt("epoch")<1||
                source.getInt("epoch")!=source.getInt("coordinatorEpoch")||source.getLong("archiveBytes")!=artifact.getLong("sizeBytes"))
            throw new SecurityException("未能核对原私人任务的源码包交付回执");
        JSONObject result=new JSONObject();
        for(String field:new String[]{"taskId","operationId","runId","conversationId","projectId","checkoutId","artifactId","archiveSha256","manifestIdentity","sourceDigest","planDigest"})
            result.put(field,source.getString(field));
        return result.put("epoch",source.getInt("epoch")).put("archiveBytes",source.getLong("archiveBytes"));
    }
    /** A new project may contain the already-authorized frozen files, never a current checkout or old runtime. */
    public static JSONObject evidence(JSONObject source,ProjectPackageArchive.Frozen frozen)throws Exception {
        JSONObject pin=pin(source),m=frozen.manifest(),stored=m.getJSONObject("source");
        if(!"source_project".equals(m.opt("mode"))||!frozen.identity.equals(pin.opt("manifestIdentity"))||
                !stored.getString("projectId").equals(pin.opt("projectId"))||!stored.getString("checkoutId").equals(pin.opt("checkoutId"))||
                !stored.getString("sourceDigest").equals(pin.opt("sourceDigest")))throw new SecurityException("该固定清单不是原保存操作的源码");
        return new JSONObject().put("format","pocketworld.project-source-selection.v1")
            .put("sourceKind",ProjectTaskSourceOrigin.KIND).put("sourceLabel",m.getJSONObject("project").getString("name"))
            .put("selectionId",ProjectTaskSourceOrigin.selectionId(pin)).put("manifestIdentity",frozen.identity).put("sourceDigest",stored.getString("sourceDigest"))
            .put("files",m.getJSONArray("files").length()).put("expandedBytes",m.getLong("bytes")).put("omittedCount",m.getJSONArray("omitted").length())
            .put("includeGeneratedRoots",m.getJSONObject("policy").getBoolean("generatedRootsIncluded")).put("completeSelection",true)
            .put("executionAuthorityRestored",false).put("sourcePermissionPersisted",false).put("original",pin);
    }
    public static String selectionId(JSONObject pin)throws Exception {
        return UUID.nameUUIDFromBytes(("private-task-package-selection:"+ProjectSources.digest(pin)).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static void requireStage(JSONObject evidence,JSONObject expected,String sha,String manifest)throws Exception {
        if(!is(evidence)||!ProjectSources.canonical(expected).equals(ProjectSources.canonical(evidence.getJSONObject("original")))||
                !selectionId(expected).equals(evidence.opt("selectionId"))||!expected.getString("archiveSha256").equals(sha)||
                !expected.getString("manifestIdentity").equals(manifest)||!manifest.equals(evidence.opt("manifestIdentity"))||
                !Boolean.FALSE.equals(evidence.opt("executionAuthorityRestored"))||!Boolean.FALSE.equals(evidence.opt("sourcePermissionPersisted")))
            throw new SecurityException("原工作、保存操作或确切包版本已不同，未打开或发布其他源码");
    }
}
