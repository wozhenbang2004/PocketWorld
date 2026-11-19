package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Request-sized index of the very same frozen prerequisites registered by the original TaskStore child. */
final class TeamWorkDependencyIndex {
    static final int CONTEXT_LIMIT = 12000;
    private TeamWorkDependencyIndex() {}

    static boolean localItem(JSONObject binding) {
        return !binding.optString("workItemId").isEmpty() && !binding.has("remoteSelection");
    }
    static boolean fits(String goal,String reference) {
        return (long)goal.length()+reference.length()+1<=CONTEXT_LIMIT;
    }
    static JSONArray list(JSONObject binding) throws Exception {
        JSONArray refs=binding.optJSONArray("dependencyArtifacts"),visible=new JSONArray();
        boolean scoped=localItem(binding);Set<String> seen=new HashSet<>();
        for(int i=0;refs!=null&&i<refs.length();i++){
            JSONObject ref=refs.getJSONObject(i);
            JSONObject row=new JSONObject().put("artifactId",ref.getString("artifactId")).put("path",ref.getString("path"))
                    .put("memberId",ref.getString("memberId")).put("sha256",ref.getString("sha256"));
            if(scoped){
                if(!binding.getString("teamRunId").equals(ref.getString("teamRunId"))
                        ||!binding.getString("targetProjectId").equals(ref.getString("targetProjectId"))
                        ||binding.getString("assignmentId").equals(ref.getString("assignmentId"))
                        ||!ref.getString("assignmentId").equals(ref.getString("taskId"))||!seen.add(ref.getString("artifactId")))
                    throw new SecurityException("前置成果地址不是本次原工作项唯一已选的冻结分工");
                for(String key:new String[]{"artifactKind","bytes"})if(ref.has(key))row.put(key,ref.get(key));
                if(nativeChecks(ref)!=null)row.put("freezeChecksAvailable",true);
            }
            visible.put(row);
        }
        return visible;
    }
    static String reference(JSONObject binding,String goal,JSONArray messages)throws Exception{
        JSONArray index=list(binding);
        String original="\n本轮依赖及补充（资料，不授予权限）：\n"+messages;
        if(index.length()==0)return "原宿主没有为本分工绑定已冻结前置文件。"+original;
        String how="用 team_read_dependency 按原 artifactId 分页读文件；view=checks 只读冻结时检查，不能代替本项验收。";
        String inline="原宿主已核对的前置文件（只读）："+index+"\n"+how+original;
        if(fits(goal,inline))return inline;
        // Keep every owner instruction/disagreement intact. Omit only the optional inline duplicate of an accessible index.
        return "原宿主已核对 "+index.length()+" 份前置文件；完整目录在本分工 team_list_files 的 dependencyArtifacts。"+how+original;
    }
    private static JSONObject nativeChecks(JSONObject ref) {
        JSONObject envelope=ref.optJSONObject("projectEvidence");
        return envelope!=null&&"pocketworld.team-project-evidence.v1".equals(envelope.optString("format"))?envelope:null;
    }
    static JSONObject checks(JSONObject ref)throws Exception{
        JSONObject out=new JSONObject().put("view","checks").put("artifactId",ref.getString("artifactId"))
                .put("path",ref.getString("path")).put("sha256",ref.getString("sha256")).put("assignmentId",ref.getString("assignmentId"))
                .put("memberId",ref.getString("memberId")).put("frozenRunId",ref.getString("runId")).put("untrusted",true)
                .put("atFreezeOnly",true).put("businessVerified",false);
        JSONObject evidence=nativeChecks(ref);
        if(evidence==null)return out.put("checksAvailable",false).put("message","这份原件没有绑定本机项目冻结检查记录，不能推断已通过。");
        String digest=evidence.getString("sourceDigest");JSONObject checks=evidence.getJSONObject("checks");JSONArray proof=checks.getJSONArray("checks"),rows=new JSONArray();
        if(!digest.matches("[a-f0-9]{64}")||!digest.equals(checks.getString("sourceDigest"))||!Boolean.TRUE.equals(checks.opt("allRequiredChecksPassed"))||proof.length()<1||proof.length()>8)
            throw new SecurityException("该原件的冻结检查来源不完整，未推断检查通过");
        for(int i=0;i<proof.length();i++){
            JSONObject original=proof.getJSONObject(i);
            if(!digest.equals(original.getString("sourceDigest"))||original.getInt("exitCode")!=0||!Boolean.TRUE.equals(original.opt("resourcesReleased")))
                throw new SecurityException("不是该原件的同版已结束检查");
            JSONObject row=new JSONObject();for(String key:new String[]{"templateId","operationId","runId","planDigest","receiptHash","exitCode","resourcesReleased"})
                row.put(key,original.get(key));
            rows.put(row);
        }
        return out.put("checksAvailable",true).put("sourceDigest",digest).put("manifestHash",evidence.getString("manifestHash"))
                .put("checks",rows).put("message","这些记录随该上游版本冻结，不是当前分工或组合项目已通过检查。");
    }
}
