package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native identity of a single already admitted file CAS. It is neither a grant nor a dispatch API. */
public final class TeamIntegrationFileRecovery {
    public static final String TOOL="team_integrate_file", KEY="originalTeamFileEffects";
    private TeamIntegrationFileRecovery(){}

    static UUID revisionId(JSONObject binding,String operation,JSONObject file)throws Exception {
        if(!UUID.fromString(operation).toString().equals(operation))throw new IllegalArgumentException("缺少原文件操作身份");
        JSONArray parts=new JSONArray().put("pocketworld.team-file-cas.v1");
        for(String key:new String[]{"teamRunId","taskId","runId","coordinatorEpoch","targetProjectId","planHash","reviewId"})parts.put(binding.get(key));
        parts.put(operation).put(file.getString("path")).put(file.getString("artifactId")).put(file.getInt("expectedVersion")).put(file.getString("expectedHash")).put(file.getString("sha256"));
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(parts.toString().getBytes(StandardCharsets.UTF_8));
        // Opaque, private-domain UUIDv8; the public file history never exposes the operation ID.
        bytes[6]=(byte)((bytes[6]&15)|128);bytes[8]=(byte)((bytes[8]&63)|128);
        ByteBuffer hash=ByteBuffer.wrap(bytes);return new UUID(hash.getLong(),hash.getLong());
    }

    public static final class Observed {
        private final String selection,receipt;
        private Observed(JSONObject selected,JSONObject actual){selection=selected.toString();receipt=actual.toString();}
        public JSONObject selected()throws Exception{return new JSONObject(selection);}
        public JSONObject receipt()throws Exception{return new JSONObject(receipt);}
    }
    /** Only the retained revision at the exact CAS successor version is admissible. A same-hash
     * file written by another task, the latest version, and a missing revision prove nothing. */
    static Observed original(JSONObject selection,VersionedWorkspace.FileSnapshot revision,dev.pocketengine.features.workspace.WorkspaceArtifacts.ArtifactRef artifact)throws Exception {
        JSONObject binding=selection.getJSONObject("binding"),file=selection.getJSONObject("file");
        String operation=selection.getString("operationId"),project=binding.getString("targetProjectId"),path=file.getString("path");
        if("prepared".equals(selection.getString("operationState")))return new Observed(selection,new JSONObject().put("ok",false).put("accepted",false)
                .put("status","not_dispatched").put("code","original_team_file_never_claimed").put("notDispatchedConfirmed",true)
                .put("automaticReplay",false).put("operationId",operation).put("businessVerified",false));
        int wanted=Math.addExact(file.getInt("expectedVersion"),1);
        boolean deleted="file_tombstone".equals(file.optString("artifactKind"));
        if(artifact==null||!file.getString("artifactId").equals(artifact.artifactId)||!file.getString("sha256").equals(artifact.sha256)||
                !file.getString("draftProjectId").equals(artifact.projectId)||!file.getString("draftRevisionId").equals(artifact.revisionId)||
                !path.equals(artifact.path)||artifact.tombstone!=deleted||revision==null)throw new SecurityException("原冻结文件与版本证据不一致");
        if(!revision.projectId.equals(project)||!revision.path.equals(path)||revision.version!=wanted||!revision.revisionId.equals(revisionId(binding,operation,file).toString())||
                !revision.sha256.equals(file.getString("sha256"))||revision.tombstone!=deleted)
            throw new IllegalStateException("原版本未能证明是这笔操作写入的。旧版写回或相同内容都不能代替原回执；未确认，也未重写文件。");
        JSONObject actual=applied(binding,file,revision.version,revision.revisionId)
                .put("recoveredFromOriginalVersion",true).put("operationId",operation);
        return new Observed(selection,actual);
    }

    static JSONObject applied(JSONObject binding,JSONObject file,int version,String revision)throws Exception{
        return new JSONObject().put("ok",true).put("status","completed").put("teamIntegrated",new JSONObject(file.toString())
                .put("projectId",binding.getString("targetProjectId")).put("version",version).put("revisionId",revision)
                .put("planHash",binding.getString("planHash")).put("reviewId",binding.getString("reviewId"))
                .put("integrationTaskId",binding.getString("taskId")).put("integrationRunId",binding.getString("runId")))
                .put("businessVerified",false).put("message","本文件的原写回版本已核实；不代表当前文件未被后续修改，也不代表整个项目已完成");
    }
}
