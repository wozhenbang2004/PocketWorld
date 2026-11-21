package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A dated statement about the original file receipts, not a transition of the team or an acceptance. */
final class TeamIntegrationDeliveryNote {
    private TeamIntegrationDeliveryNote() {}
    static final String AWAITING = "作者已交回冻结文件。请在原成果页核对实际差异与原项目写回情况。";
    static String savedForCurrentPlan(JSONObject run){
        JSONObject note=currentPlanObservation(run);
        return note==null?"":note.optString("message");
    }
    private static JSONObject currentPlanObservation(JSONObject run){
        JSONObject note=run.optJSONObject("fileDeliveryObservation"),work=run.optJSONObject("workPlan"),review=run.optJSONObject("independentReview");
        if(note==null||work==null||review==null||review.optBoolean("stale")||!run.optString("id").equals(note.optString("teamRunId"))
                ||run.optInt("generation",-1)!=note.optInt("generation",-2)||!work.optString("planId").equals(note.optString("workPlanId"))
                ||work.optInt("revision",-1)!=note.optInt("workPlanRevision",-2)||!review.optString("planHash").equals(note.optString("planHash")))return null;
        return note;
    }
    /** Navigation to a successful original write-back, not a fresh file-head or business verdict. */
    static JSONObject settledOriginalWorker(JSONObject run,JSONArray workers){
        JSONObject scope=run.optJSONObject("productionScope"),note=currentPlanObservation(run);
        if(scope==null||scope.has("projectSelection")||note==null||note.has("source")
                ||!Boolean.TRUE.equals(note.opt("settledAtObservation"))||!Boolean.TRUE.equals(note.opt("allFileHeadsMatched"))
                ||!Boolean.FALSE.equals(note.opt("businessVerified"))||!"completed".equals(note.opt("workerStatusAtObservation"))
                ||note.optLong("observedAt")<=0)return null;
        int total=note.optInt("total");if(total<1||total>8||note.optInt("matched")!=total)return null;
        JSONObject selected=null;
        for(int i=0;i<workers.length();i++){
            JSONObject row=workers.optJSONObject(i);
            if(row==null||!"integrator".equals(row.opt("role"))||!Boolean.TRUE.equals(row.opt("selectedVersion"))
                    ||!Boolean.TRUE.equals(row.opt("canOpen"))||!"completed".equals(row.opt("taskStatus"))
                    ||!Boolean.FALSE.equals(row.opt("activeAttempt"))||!Boolean.FALSE.equals(row.opt("hasUnresolvedEffects"))
                    ||row.optInt("epoch")<=0)continue;
            for(String key:new String[]{"teamRunId","taskId","runId","reviewId","planHash"}){
                String exact=note.optString(key);if(exact.isEmpty()||!exact.equals(row.opt(key)))return null;
            }
            if(selected!=null)return null;
            selected=row;
        }
        return selected;
    }
    static JSONObject observe(JSONObject run,JSONObject actual)throws org.json.JSONException{
        String team=run.optString("id");JSONObject scope=run.optJSONObject("productionScope"),work=run.optJSONObject("workPlan"),review=run.optJSONObject("independentReview"),p=actual.optJSONObject("progress");
        if(scope==null||work==null||review==null||p==null||work.optString("planId").isEmpty()||work.optInt("revision",-1)<0||run.optInt("generation",-1)<0
                ||!(actual.opt("hasUnresolved") instanceof Boolean)||!(actual.opt("activeAttempt") instanceof Boolean)||!(actual.opt("currentFilesComplete") instanceof Boolean)
                ||!(p.opt("applied") instanceof Number)||!(p.opt("retained") instanceof Number)||scope.has("projectSelection")||!team.equals(actual.optString("teamRunId"))
                ||review.optBoolean("stale")||!"completed".equals(review.optString("status"))||!"no_blocking_findings".equals(review.optString("verdict"))
                ||!review.optBoolean("completeOriginalFilesRead")||!review.optBoolean("verdictSubmitted")||!team.equals(review.optString("teamRunId")))return null;
        String task=actual.optString("taskId"),reviewId=actual.optString("reviewId"),hash=actual.optString("planHash"),project=scope.optString("targetProjectId");
        if(!UUID.nameUUIDFromBytes(("team-integration:"+team).getBytes(StandardCharsets.UTF_8)).toString().equals(task)||!task.equals(p.optString("taskId"))
                ||!actual.optString("runId").matches("[a-f0-9-]{36}")||!reviewId.matches("[a-f0-9-]{36}")||!reviewId.equals(p.optString("reviewId"))
                ||!hash.matches("[a-f0-9]{64}")||!hash.equals(p.optString("planHash"))||!hash.equals(review.optString("planHash"))||project.isEmpty()||!project.equals(work.optString("targetProjectId")))return null;
        JSONArray assignments=scope.optJSONArray("assignments"),rows=p.optJSONArray("files"),originals=review.optJSONArray("files");
        if(assignments==null||rows==null||originals==null||rows.length()<1||rows.length()>8||rows.length()!=originals.length())return null;
        for(int i=0;i<assignments.length();i++){JSONObject a=assignments.optJSONObject(i);if(a==null||a.has("remoteSelection")||a.has("projectSelection"))return null;}
        Map<String,JSONObject> refs=new HashMap<>();
        for(int i=0;i<originals.length();i++){JSONObject f=originals.optJSONObject(i);if(f==null||!team.equals(f.optString("teamRunId"))||!project.equals(f.optString("targetProjectId"))||refs.put(f.optString("artifactId"),f)!=null)return null;}
        JSONArray items=work.optJSONArray("items");Set<String> currentIds=new HashSet<>();
        if(items==null||items.length()<1||items.length()>8)return null;
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null||!"completed".equals(item.optString("status")))return null;
            JSONArray artifacts=item.optJSONArray("artifacts");if(artifacts==null||artifacts.length()<1||artifacts.length()>8)return null;
            for(int a=0;a<artifacts.length();a++){JSONObject raw=artifacts.optJSONObject(a);if(raw==null)return null;String id=raw.optString("artifactId");JSONObject reviewed=refs.get(id);
                if(reviewed==null||!currentIds.add(id)||!item.optString("assignmentId").equals(reviewed.optString("assignmentId"))||!item.optString("memberId").equals(reviewed.optString("memberId"))
                        ||!raw.optString("sha256").equals(reviewed.optString("sha256"))||!raw.optString("path").equals(reviewed.optString("path")))return null;
            }
        }
        if(currentIds.size()!=refs.size())return null;
        int matched=0,conflicts=0;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)return null;JSONObject f=refs.remove(row.optString("artifactId"));if(f==null||!f.optString("path").equals(row.optString("path")))return null;
            String state=row.optString("state");
            if(state.equals("applied")||state.equals("retained")){
                JSONObject saved=row.optJSONObject("teamIntegrated");if(saved==null||!task.equals(saved.optString("integrationTaskId"))||!project.equals(saved.optString("projectId"))
                        ||!row.optString("currentRevisionId").equals(saved.optString("revisionId"))||row.optInt("currentVersion",-1)!=saved.optInt("version",-2))return null;
                for(String key:new String[]{"artifactId","path","assignmentId","memberId","sha256"})if(f.optString(key).isEmpty()||!f.optString(key).equals(saved.optString(key)))return null;
                matched++;
            }else if(state.equals("conflict"))conflicts++;else if(!state.equals("pending"))return null;
        }
        if(!refs.isEmpty()||matched!=p.getInt("applied")+p.getInt("retained"))return null;
        boolean unknown=actual.getBoolean("hasUnresolved"),active=actual.getBoolean("activeAttempt");
        boolean filesComplete=actual.getBoolean("currentFilesComplete")&&matched==rows.length()&&!active&&!unknown;
        boolean complete=filesComplete&&"completed".equals(actual.optString("status"));
        String message="上次核对，"+rows.length()+" 份原团队文件中有 "+matched+" 份与成功写回回执和当时的项目版本一致。";
        message+=unknown?"尚有未确定效果，请查原整合操作，不要重放。":active?"原整合者仍在处理中。":complete?"文件已写回，业务结果仍由主人验收。"
                :filesComplete?"原清单已全部写回；原整合执行没有正常收尾，不代表业务验收。":conflicts>0?"另有 "+conflicts+" 份的项目基线不一致，请核对原文件。":"尚未确认这份计划全部写回；没有重跑分工。";
        return new JSONObject().put("teamRunId",team).put("generation",run.getInt("generation")).put("workPlanId",work.getString("planId")).put("workPlanRevision",work.getInt("revision"))
                .put("taskId",task).put("runId",actual.getString("runId")).put("reviewId",reviewId).put("planHash",hash).put("observedAt",System.currentTimeMillis())
                .put("matched",matched).put("total",rows.length()).put("allFileHeadsMatched",filesComplete).put("workerStatusAtObservation",actual.getString("status")).put("settledAtObservation",complete).put("businessVerified",false).put("message",message);
    }
    static boolean readableRecovery(JSONObject run){
        JSONObject review=run.optJSONObject("independentReview"),scope=run.optJSONObject("productionScope");JSONArray files=review==null?null:review.optJSONArray("files");
        return scope!=null&&!scope.has("projectSelection")&&run.optJSONObject("workPlan")!=null&&review!=null&&!review.optBoolean("stale")
                &&"completed".equals(review.opt("status"))&&files!=null&&files.length()>0&&files.length()<=8;
    }
    /** A genuine positive recovery in this exact stopped child, not a prepared/no-dispatch or similar-name receipt. */
    static JSONObject recovered(JSONObject run,JSONObject actual,JSONObject source)throws org.json.JSONException{
        if(source==null||!"team_file_receipt_recovered".equals(source.opt("type"))||!source.optBoolean("receiptRestored")||source.optBoolean("notDispatchedConfirmed")
                ||!actual.optString("taskId").equals(source.opt("taskId"))||!actual.optString("runId").equals(source.opt("runId"))
                ||source.optInt("epoch",-1)<1||source.getInt("epoch")!=actual.optInt("coordinatorEpoch",-1)
                ||actual.optBoolean("activeAttempt")||!source.optString("operationId").matches("[a-f0-9-]{36}")||actual.optJSONObject("progress")==null)return null;
        JSONObject note=observe(run,actual);if(note==null)return null;JSONArray receipts=actual.optJSONArray("receipts");JSONObject match=null;
        for(int i=0;receipts!=null&&i<receipts.length();i++){JSONObject receipt=receipts.optJSONObject(i);
            if(receipt==null||!receipt.optBoolean("recoveredFromOriginalVersion")||!receipt.optBoolean("ok")||!source.getString("operationId").equals(receipt.opt("operationId")))continue;
            JSONObject w=receipt.optJSONObject("teamIntegrated");
            if(match!=null||w==null||!actual.getString("taskId").equals(w.opt("integrationTaskId"))||!actual.getString("runId").equals(w.opt("integrationRunId"))
                    ||!run.getString("id").equals(w.opt("teamRunId"))||!note.getString("reviewId").equals(w.opt("reviewId"))||!note.getString("planHash").equals(w.opt("planHash"))
                    ||!run.getJSONObject("productionScope").getString("targetProjectId").equals(w.opt("projectId"))||!source.optString("path").equals(w.opt("path"))
                    ||source.optInt("version",-1)<1||source.getInt("version")!=w.optInt("version",-1)||!source.optString("revisionId").matches("[a-f0-9-]{36}")||!source.getString("revisionId").equals(w.opt("revisionId")))return null;
            match=w;
        }
        if(match==null)return null;
        boolean inCurrentPlan=false;JSONArray rows=actual.getJSONObject("progress").getJSONArray("files");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(match.getString("artifactId").equals(row.opt("artifactId"))&&match.getString("path").equals(row.opt("path")))inCurrentPlan=true;}
        return !inCurrentPlan?null:note.put("source","recovered_original_file_operation").put("operationId",source.getString("operationId")).put("epoch",source.getInt("epoch"))
                .put("executionAuthorized",false).put("workerStatusChanged",false);
    }

}
