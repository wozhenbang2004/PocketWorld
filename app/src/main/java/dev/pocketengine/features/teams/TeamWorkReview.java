package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only review of original TaskStore receipt references and actual immutable artifact bytes. */
public final class TeamWorkReview {
    private TeamWorkReview(){}
    /** Native proof of an actual read, not an approval and never deserialized from model JSON. */
    public static final class Prepared {
        private final JSONObject value;private Prepared(JSONObject value)throws Exception{this.value=new JSONObject(value.toString());}
        public JSONObject snapshot()throws Exception{return new JSONObject(value.toString());}
    }
    public static Prepared prepare(TeamWorkFiles.Workspace workspace,String teamRun,String target,JSONArray nativeAssignments)throws Exception{return new Prepared(read(workspace,teamRun,target,nativeAssignments));}
    /** Explicit owner re-review; context must be read from the original native integration task. */
    public static Prepared prepareRecovery(TeamWorkFiles.Workspace workspace,String teamRun,String target,JSONArray nativeAssignments,JSONObject nativeIntegration)throws Exception {
        JSONObject context=new JSONObject(nativeIntegration.toString());
        if(!teamRun.equals(context.getString("teamRunId"))||!target.equals(context.getString("targetProjectId"))||!context.getString("taskId").matches("[a-f0-9-]{36}")||!context.getString("reviewId").matches("[a-f0-9-]{36}")||!context.getString("planHash").matches("[a-f0-9]{64}"))throw new SecurityException("重新审查必须定位原唯一整合任务及原审查");
        JSONArray receipts=context.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){JSONObject applied=receipts.getJSONObject(i).optJSONObject("teamIntegrated");if(applied!=null&&(!context.getString("taskId").equals(applied.optString("integrationTaskId"))||!teamRun.equals(applied.optString("teamRunId"))||!target.equals(applied.optString("projectId"))))throw new SecurityException("整合历史不属于原唯一整合任务");}
        JSONObject review=read(workspace,teamRun,target,nativeAssignments),plan=review.getJSONObject("plan");JSONArray files=plan.getJSONArray("files"),diffs=review.getJSONArray("diffs"),retained=new JSONArray();boolean complete=files.length()>0&&review.getJSONArray("incompleteAssignments").length()==0;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i),diff=diffs.getJSONObject(i);file.put("expectedVersion",diff.getInt("currentVersion")).put("expectedHash",diff.getString("currentHash"));JSONObject kept=null;
            for(int r=0;r<receipts.length();r++){JSONObject receipt=receipts.getJSONObject(r),applied=receipt.optJSONObject("teamIntegrated");if(!receipt.optBoolean("ok")||applied==null||!sameArtifact(file,applied,context.getString("taskId"),target))continue;
                if(diff.getBoolean("currentExists")!=isDeletion(file)&&diff.getInt("currentVersion")==applied.getInt("version")&&diff.getString("currentRevisionId").equals(applied.getString("revisionId"))&&diff.getString("currentHash").equals(applied.getString("sha256")))kept=applied;
            }
            if(kept!=null)retained.put(new JSONObject(kept.toString()));diff.put("disposition",kept==null?"apply_reviewed":"retain_applied");if(diff.getBoolean("truncated"))complete=false;
        }
        plan.put("retained",retained).put("recovery",new JSONObject().put("taskId",context.getString("taskId")).put("reviewId",context.getString("reviewId")).put("planHash",context.getString("planHash")).put("receiptsHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(receipts))));
        boolean active=context.getBoolean("activeAttempt"),unknown=context.getBoolean("hasUnresolved");
        review.put("planHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(plan))).put("canIntegrate",complete&&!active&&!unknown).put("recovery",true).put("integrationStatus",context.getString("status")).put("blockedReason",unknown?"原整合操作尚有未知效果，请先核对原操作":active?"原整合执行尚未结束，请先停止并等待原执行结束":"");return new Prepared(review);
    }
    /** assignments must come from the native TaskStore provider, never model-supplied artifact JSON. */
    public static JSONObject read(TeamWorkFiles.Workspace workspace,String teamRun,String target,JSONArray assignments)throws Exception {
        JSONArray files=new JSONArray(),diffs=new JSONArray(),incomplete=new JSONArray();Set<String> paths=new HashSet<>();boolean current=true;
        for(int a=0;a<assignments.length();a++){JSONObject assignment=assignments.getJSONObject(a);if(!assignment.optString("status").equals("completed")){current=false;incomplete.put(new JSONObject().put("memberId",assignment.getString("memberId")).put("assignmentId",assignment.getString("assignmentId")).put("status",assignment.optString("status")));}JSONArray artifacts=assignment.getJSONArray("artifacts");
            for(int i=0;i<artifacts.length();i++){
                JSONObject ref=artifacts.getJSONObject(i);for(String key:new String[]{"assignmentId","memberId","taskId"})if(!assignment.getString(key).equals(ref.getString(key)))throw new SecurityException("成果不属于原成员assignment");
                if(!teamRun.equals(ref.getString("teamRunId"))||!target.equals(ref.getString("targetProjectId")))throw new SecurityException("成果不属于原团队项目");String path=VersionedWorkspace.path(ref.getString("path"));if(!paths.add(path)||paths.size()>32)throw new SecurityException("整合文件范围冲突或超出原团队额度");
                JSONObject artifact=artifact(workspace,ref),before=TeamWorkFiles.snapshot(workspace,target,path);String hash=TeamWorkFiles.snapshotHash(before);
                boolean matches=ref.getInt("baseVersion")==before.getInt("version")&&ref.getString("baseHash").equals(hash);JSONObject diff;
                if(artifact.has("content")&&before.has("content")&&!isDeletion(ref))diff=VersionedWorkspace.compare(path,before.getString("content"),artifact.getString("content"),"当前项目v"+before.getInt("version"),"原成员成果");
                else diff=new JSONObject().put("path",path).put("kind",isDeletion(ref)?"deletion":"binary_or_large_text").put("beforeHash",hash).put("afterHash",ref.getString("sha256")).put("beforeBytes",before.optLong("sizeBytes",before.optString("content").getBytes(java.nio.charset.StandardCharsets.UTF_8).length)).put("afterBytes",artifact.optLong("sizeBytes")).put("hasTextDiff",false).put("truncated",false).put("originalBytesRequireIndependentReview",true);
                if(!matches||diff.getBoolean("truncated"))current=false;
                files.put(new JSONObject(ref.toString()).put("expectedVersion",ref.getInt("baseVersion")).put("expectedHash",ref.getString("baseHash")));
                diffs.put(diff.put("artifactId",ref.getString("artifactId")).put("assignmentId",ref.getString("assignmentId")).put("memberId",ref.getString("memberId")).put("currentVersion",before.getInt("version")).put("currentRevisionId",before.optString("revisionId")).put("currentExists",before.getBoolean("exists")).put("currentHash",hash).put("matchesOwnerBase",matches));
            }
        }
        JSONObject plan=new JSONObject().put("format","pocketworld.team.integration.v1").put("teamRunId",teamRun).put("targetProjectId",target).put("files",files);
        return new JSONObject().put("plan",plan).put("planHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(plan))).put("diffs",diffs).put("incompleteAssignments",incomplete).put("canIntegrate",current&&files.length()>0).put("requiresOwnerReview",true).put("perFileCas",true).put("atomicBatch",false).put("businessVerified",false);
    }
    static boolean sameArtifact(JSONObject file,JSONObject applied,String integrationTask,String target)throws Exception {
        if(!integrationTask.equals(applied.optString("integrationTaskId"))||!target.equals(applied.optString("projectId"))||!applied.optString("planHash").matches("[a-f0-9]{64}")||!applied.optString("reviewId").matches("[a-f0-9-]{36}"))return false;
        java.util.Iterator<String> keys=file.keys();while(keys.hasNext()){String key=keys.next();if(!key.equals("expectedVersion")&&!key.equals("expectedHash")&&(!applied.has(key)||!TeamWorkFiles.canonical(file.get(key)).equals(TeamWorkFiles.canonical(applied.get(key)))))return false;}return true;
    }
    static boolean sameHead(JSONObject actual,JSONObject applied)throws Exception{return actual.optBoolean("exists")!=isDeletion(applied)&&actual.getInt("version")==applied.getInt("version")&&actual.optString("revisionId").equals(applied.getString("revisionId"))&&TeamWorkFiles.snapshotHash(actual).equals(applied.getString("sha256"));}
    static boolean isDeletion(JSONObject ref){return "file_tombstone".equals(ref.optString("artifactKind"));}
    static WorkspaceArtifacts.ArtifactRef artifactRef(TeamWorkFiles.Workspace workspace,JSONObject ref)throws Exception {
        WorkspaceArtifacts.ArtifactRef nativeRef=workspace.nativeArtifact(ref.getString("artifactId"),ref.getString("sha256"));JSONObject artifact=nativeRef.json();
        verifyArtifactIdentity(artifact,ref);
        if(!nativeRef.kind.equals(ref.optString("artifactKind",nativeRef.kind))||ref.has("bytes")&&nativeRef.sizeBytes!=ref.getLong("bytes")||ref.has("mimeType")&&!nativeRef.mimeType.equals(ref.getString("mimeType")))throw new SecurityException("原成果类型/大小/MIME不属于精确冻结回执");
        if(nativeRef.tombstone!=isDeletion(ref))throw new SecurityException("删除成果必须来自真实tombstone，不把空文件当删除");
        if(!nativeRef.tombstone&&!TeamWorkFiles.hash(workspace.artifactBytes(nativeRef)).equals(ref.getString("sha256")))throw new SecurityException("原成果实际字节不符");return nativeRef;
    }
    static byte[] artifactBytes(TeamWorkFiles.Workspace workspace,JSONObject ref)throws Exception {
        if(workspace.byteFilesSupported())return workspace.artifactBytes(artifactRef(workspace,ref));
        return artifact(workspace,ref).getString("content").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void verifyArtifactIdentity(JSONObject artifact,JSONObject ref)throws Exception {
        for(String[] pair:new String[][]{{"id","artifactId"},{"projectId","draftProjectId"},{"path","path"},{"sourceRevisionId","draftRevisionId"},{"sha256","sha256"},{"taskId","taskId"},{"runId","runId"}})if(!artifact.getString(pair[0]).equals(ref.getString(pair[1])))throw new SecurityException("原成果身份、版本或执行来源不符");
        if(artifact.getInt("sourceVersion")!=ref.getInt("draftVersion"))throw new SecurityException("原成果版本不符");
    }
    static JSONObject artifact(TeamWorkFiles.Workspace workspace,JSONObject ref)throws Exception {
        if(workspace.byteFilesSupported()){
            WorkspaceArtifacts.ArtifactRef nativeRef=artifactRef(workspace,ref);JSONObject actual=nativeRef.json();
            if(nativeRef.tombstone)return actual;
            if("text_file".equals(nativeRef.kind)){byte[] bytes=workspace.artifactBytes(nativeRef);String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.length()>65536)throw new SecurityException("原文字成果视图超限，未截断");actual.put("content",text);}
            return actual;
        }
        JSONObject artifact=workspace.call("readArtifact",new JSONObject().put("artifactId",ref.getString("artifactId")));
        verifyArtifactIdentity(artifact,ref);
        if(artifact.getInt("sourceVersion")!=ref.getInt("draftVersion")||!TeamWorkFiles.hash(artifact.getString("content")).equals(ref.getString("sha256")))throw new SecurityException("原成果字节或版本不符");return artifact;
    }
}
