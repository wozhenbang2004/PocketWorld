package dev.pocketengine.features.automation;

import dev.pocketengine.store.TaskStore;
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
    private static final String NATIVE_FILE="native_workspace_artifact_standard";
    private static final String FILE_SCOPE="file_identity_readability_and_reviewed_heading_only";
    public static final int ORIGINAL_TEXT_LIMIT=65_536;

    /** The chosen Book receipt, not a capability or a path supplied by the renderer. */
    public record OriginalFile(String planId,String stepId,String occurrenceId,long ruleRevision,long commitmentRevision,
                               String taskId,String runId,int epoch,String evidenceId,long recordedAt,String operationId,
                               String artifactId,String projectId,String path,int sourceVersion,String sourceRevisionId,
                               String sha256,int sizeBytes,String standardDigest){
        private boolean sameFile(JSONObject file){
            return projectId.equals(file.opt("projectId"))&&path.equals(file.opt("path"))&&
                   number(file,"sourceVersion")==sourceVersion&&sourceRevisionId.equals(file.opt("sourceRevisionId"))&&sha256.equals(file.opt("sha256"));
        }
        public boolean matchesFrozenArtifact(JSONObject file){
            return "text_file".equals(file.opt("kind"))&&"task_workspace".equals(file.opt("source"))&&
                   artifactId.equals(file.opt("id"))&&taskId.equals(file.opt("taskId"))&&runId.equals(file.opt("runId"))&&sameFile(file)&&
                   number(file,"characters")>=1&&number(file,"characters")<=ORIGINAL_TEXT_LIMIT;
        }
        public boolean matchesVerification(JSONObject proof)throws Exception{
            return proof!=null&&"workspace_artifact".equals(proof.opt("kind"))&&taskId.equals(proof.opt("taskId"))&&
                   runId.equals(proof.opt("runId"))&&number(proof,"coordinatorEpoch")==epoch&&operationId.equals(proof.opt("operationId"))&&
                   artifactId.equals(proof.opt("artifactId"))&&sameFile(proof)&&number(proof,"sizeBytes")==sizeBytes&&
                   FILE_SCOPE.equals(proof.opt("verificationScope"))&&proof.optJSONObject("standard")!=null&&
                   standardDigest.equals(TaskStore.capabilityFingerprint(proof.getJSONObject("standard")));
        }
    }

    /** Initial UI entry only from the native held recent result that was actually presented. */
    public static OriginalFile heldOriginalFile(JSONObject plan,String stepId)throws Exception{
        JSONObject progress=plan.optJSONObject("progress"),last=progress==null?null:progress.optJSONObject("lastResult");
        if(last==null||!"pocketworld.plan-progress.v1".equals(progress.opt("format"))||!plan.optString("id").equals(progress.opt("planId"))||
                !Boolean.FALSE.equals(progress.opt("executionAuthorized"))||!stepId.equals(last.opt("stepId"))||
                !"current".equals(last.opt("readState"))||!"completed".equals(last.opt("status"))||
                !Boolean.TRUE.equals(last.opt("resultVerified"))||!Boolean.TRUE.equals(last.opt("terminalVerified")))return null;
        OriginalFile file=originalFile(plan,stepId);
        return file!=null&&file.taskId.equals(last.opt("taskId"))&&file.runId.equals(last.opt("runId"))&&
                file.epoch==number(last,"epoch")&&file.occurrenceId.equals(last.opt("occurrenceId"))&&
                last.optJSONObject("resultBasis")!=null&&NATIVE_FILE.equals(last.getJSONObject("resultBasis").opt("kind"))?file:null;
    }

    /** Compare the same selected step's decisive Book witness, not all Plan progress or a current path. */
    public static OriginalFile originalFile(JSONObject plan,String stepId)throws Exception{
        if(!uuid(stepId)||!privateScope(plan)||!Boolean.TRUE.equals(plan.opt("referenceOnly"))||
                !Boolean.FALSE.equals(plan.opt("executionAuthorized"))||plan.optJSONObject("execution")==null)return null;
        String planId=plan.optString("id");JSONArray steps=plan.optJSONArray("steps");JSONObject step=null;
        if(!uuid(planId)||steps==null||steps.length()==0||steps.length()>50)return null;
        for(int i=0;i<steps.length();i++){
            JSONObject row=steps.getJSONObject(i);
            if(stepId.equals(row.opt("id"))){if(step!=null||i!=number(row,"planIndex"))return null;step=row;}
        }
        if(step==null)return null;
        Object task=step.opt("taskId");
        for(int i=0;i<steps.length();i++)if(steps.getJSONObject(i)!=step&&task!=null&&task.equals(steps.getJSONObject(i).opt("taskId")))return null;
        return originalBookFile(planId,stepId,step);
    }

    /** Same native finished-step validator used on the held view and the Book's scoped raw projection. */
    static OriginalFile originalBookFile(String planId,String stepId,JSONObject step)throws Exception{
        if(!privateScope(step)||!stepId.equals(step.opt("id"))||!planId.equals(step.opt("planId"))||
                !"workspace".equals(step.opt("executor"))||!"completed".equals(step.opt("status"))||
                step.has("remoteWait")||step.has("businessWait"))return null;
        JSONObject evidence=step.optJSONObject("completionEvidence"),event=step.optJSONObject("completionEvent"),
                admission=step.optJSONObject("taskAdmission"),observation=step.optJSONObject("taskObservation");
        if(evidence==null||event==null||admission==null||observation==null||!privateScope(evidence)||
                !"task_result".equals(evidence.opt("kind"))||!"completed".equals(evidence.opt("outcome"))||
                !NATIVE_FILE.equals(evidence.opt("verificationSource"))||!stepId.equals(evidence.opt("commitmentId")))return null;
        JSONObject artifact=evidence.optJSONObject("artifact");if(artifact==null||!FILE_SCOPE.equals(artifact.opt("verificationScope")))return null;
        String task=step.optString("taskId"),run=artifact.optString("runId"),occurrence=admission.optString("occurrenceId"),id=evidence.optString("id"),
                operation=artifact.optString("operationId"),file=artifact.optString("artifactId"),project=artifact.optString("projectId"),
                path=artifact.optString("path"),revision=artifact.optString("sourceRevisionId"),sha=artifact.optString("sha256");
        long epoch=number(artifact,"coordinatorEpoch"),rule=number(admission,"ruleRevision"),bound=number(evidence,"commitmentRevision"),
                recorded=number(evidence,"recordedAt"),version=number(artifact,"sourceVersion"),bytes=number(artifact,"sizeBytes");
        if(!uuid(task)||!uuid(run)||!uuid(occurrence)||!uuid(operation)||!uuid(file)||!uuid(project)||!uuid(revision)||
                !id.matches("automation-[a-f0-9]{64}")||!sha.matches("[a-f0-9]{64}")||epoch<1||epoch>Integer.MAX_VALUE||
                version<1||version>Integer.MAX_VALUE||bytes<1||bytes>4L*ORIGINAL_TEXT_LIMIT||recorded<1||rule<1||
                bound<1||bound==Long.MAX_VALUE||number(step,"revision")!=bound+1||number(admission,"commitmentRevision")!=bound||
                !"bound".equals(admission.opt("state"))||!"capabilities".equals(admission.opt("executionMode"))||
                !planId.equals(admission.opt("ruleId"))||!task.equals(evidence.opt("taskId"))||
                !task.equals(event.opt("taskId"))||!id.equals(event.opt("id"))||!id.equals(event.opt("evidenceId"))||
                !"TaskStore".equals(observation.opt("authority"))||!"completed".equals(observation.opt("status"))||
                !Boolean.TRUE.equals(observation.opt("resultVerified"))||!task.equals(observation.opt("taskId"))||
                !occurrence.equals(observation.opt("occurrenceId"))||!run.equals(observation.opt("runId"))||
                epoch!=number(observation,"coordinatorEpoch"))return null;
        JSONObject standard=standard(step.optJSONObject("verification"));
        if(!"workspace_artifact".equals(standard.opt("kind"))||!project.equals(standard.opt("projectId"))||
                !path.equals(standard.opt("path"))||bytes<number(standard,"minBytes"))return null;
        return new OriginalFile(planId,stepId,occurrence,rule,bound,task,run,(int)epoch,id,recorded,operation,file,project,path,
                (int)version,revision,sha,(int)bytes,TaskStore.capabilityFingerprint(standard));
    }
    private static boolean privateScope(JSONObject value){return "owner".equals(value.opt("ownerId"))&&"private".equals(value.opt("spaceId"));}
    private static boolean uuid(String value){return value!=null&&value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");}
    private static long number(JSONObject row,String key){
        Object value=row.opt(key);return value instanceof Integer||value instanceof Long?((Number)value).longValue():-1;
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
