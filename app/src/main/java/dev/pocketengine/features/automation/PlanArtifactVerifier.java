package dev.pocketengine.features.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded native completion standard: an actual task-owned frozen workspace text file. */
public final class PlanArtifactVerifier {
    public interface Source {JSONObject read(String artifactId)throws Exception;}
    private final Source source;
    public PlanArtifactVerifier(Source source){this.source=java.util.Objects.requireNonNull(source);}
    public static JSONObject standard(JSONObject input)throws Exception {
        if(input==null)return new JSONObject().put("kind","owner_review");
        String kind=input.optString("kind");
        if(kind.equals("owner_review")){if(input.length()!=1)throw new IllegalArgumentException("人工核验不接受自证字段");return new JSONObject().put("kind",kind);}
        if(!kind.equals("workspace_artifact"))throw new IllegalArgumentException("当前核验标准不受支持");
        for(Iterator<String> keys=input.keys();keys.hasNext();)if(!keys.next().matches("kind|projectId|path|requiredHeading|minBytes"))throw new IllegalArgumentException("核验标准包含不支持的字段");
        String project=PlanExecutionPolicy.text(input,"projectId",80),path=PlanExecutionPolicy.text(input,"path",240);
        if(!project.matches("[a-f0-9-]{36}")||path.startsWith("/")||path.contains("\\")||path.matches(".*[\\p{Cntrl}].*")||path.matches("(^|.*/)\\.\\.?(/.*|$)"))throw new IllegalArgumentException("请选择现有项目内的有效相对文件路径");
        return new JSONObject().put("kind",kind).put("projectId",project).put("path",path)
                .put("requiredHeading",PlanExecutionPolicy.text(input,"requiredHeading",200))
                .put("minBytes",input.has("minBytes")?AutomationPolicy.integer(input,"minBytes",1,1_000_000):1);
    }
    /** Task/operations are supplied only by the native TaskStore, never a feature payload. */
    public JSONObject verify(JSONObject task,JSONObject criterion)throws Exception {
        JSONObject standard=standard(criterion);if(!standard.getString("kind").equals("workspace_artifact"))return null;
        String id=task.getString("id"),run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");
        if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||run.isEmpty()||epoch<1||!task.optString("status").matches("needs_confirmation|awaiting_user|completed"))return null;
        JSONArray operations=task.optJSONArray("operations");for(int i=operations==null?-1:operations.length()-1;i>=0;i--){
            JSONObject operation=operations.getJSONObject(i),receipt=operation.optJSONObject("receipt"),args=operation.optJSONObject("arguments");
            if(!run.equals(operation.optString("runId"))||!"workspace_create_artifact".equals(operation.optString("tool"))||!"receipt".equals(operation.optString("state"))||receipt==null||args==null)continue;
            if(!standard.getString("projectId").equals(args.optString("projectId"))||!standard.getString("path").equals(args.optString("path")))continue;
            String artifactId=receipt.optString("id");if(!artifactId.matches("[a-f0-9-]{36}"))continue;
            final JSONObject actual;try{actual=source.read(artifactId);}catch(Exception unavailable){continue;}
            if(actual==null||!artifactId.equals(actual.optString("id"))||!id.equals(actual.optString("taskId"))||!run.equals(actual.optString("runId"))||!"task_workspace".equals(actual.optString("source")))continue;
            if(!standard.getString("projectId").equals(actual.optString("projectId"))||!standard.getString("path").equals(actual.optString("path"))||actual.optInt("sourceVersion")<1||actual.optString("sourceRevisionId").isEmpty())continue;
            Object raw=actual.opt("content");if(!(raw instanceof String))continue;String content=(String)raw;byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
            int lineEnd=content.indexOf('\n');String firstLine=lineEnd<0?content:content.substring(0,lineEnd);if(firstLine.endsWith("\r"))firstLine=firstLine.substring(0,firstLine.length()-1);
            if(bytes.length<standard.getInt("minBytes")||bytes.length>1_000_000||!firstLine.equals(standard.getString("requiredHeading")))continue;
            String hash=sha256(bytes);if(!hash.equals(actual.optString("sha256"))||!hash.equals(receipt.optString("sha256"))||!id.equals(receipt.optString("taskId"))||!run.equals(receipt.optString("runId")))continue;
            return new JSONObject().put("kind","workspace_artifact").put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch)
                    .put("operationId",operation.getString("operationId")).put("artifactId",artifactId).put("sha256",hash).put("sizeBytes",bytes.length)
                    .put("projectId",actual.getString("projectId")).put("path",actual.getString("path")).put("sourceVersion",actual.getInt("sourceVersion")).put("sourceRevisionId",actual.getString("sourceRevisionId"))
                    .put("standard",standard).put("verificationScope","file_identity_readability_and_reviewed_heading_only");
        }return null;
    }
    private static String sha256(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
