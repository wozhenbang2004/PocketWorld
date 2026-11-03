package dev.pocketengine.features.evaluation;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent effect/readback scoring. Never executes a tool or accepts model success as proof. */
public final class EvaluationEvidence {
    private EvaluationEvidence(){}
    public static JSONObject probe(Path root,JSONObject task,String hostFailure)throws Exception{
        JSONObject evaluation=task.getJSONObject("evaluation"),trial=evaluation.getJSONObject("trial");EvaluationDataset.validate(trial);
        String id=task.getString("id"),run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");Path dir=SyntheticEvaluationPort.directory(root,id);
        JSONObject out=new JSONObject().put("format","pocketworld.evaluation-probe.v1").put("taskId",id).put("runId",run).put("epoch",epoch)
                .put("datasetSha256",trial.getString("datasetSha256")).put("sourceSha256",trial.getString("sourceSha256"))
                .put("sourceChecked",false).put("grounded",false).put("effectMatches",JSONObject.NULL).put("reportedComplete",JSONObject.NULL)
                .put("effectProbe","native_file_readback").put("modelSelfReportIsProof",false).put("autoReplay",false).put("observedAt",System.currentTimeMillis())
                .put("taskStatus",task.optString("status")).put("nativeExecutionFailure",hostFailure==null?"":hostFailure);
        JSONArray operations=task.optJSONArray("operations"),events=task.optJSONArray("events");boolean unknown=false;
        Set<String> physical=new HashSet<>();for(int i=0;events!=null&&i<events.length();i++){JSONObject e=events.getJSONObject(i);if("inference_receipt".equals(e.optString("type"))){JSONObject receipt=e.optJSONObject("receipt");String call=receipt==null?e.optString("callId"):receipt.optString("callId");if(!call.isEmpty())physical.add(call);}}
        for(int i=0;operations!=null&&i<operations.length();i++){
            JSONObject op=operations.getJSONObject(i);if(!run.equals(op.optString("runId")))continue;
            JSONObject receipt=op.optJSONObject("receipt");
            // A row label never strengthens an explicitly uncertain physical receipt.
            // accepted=false is a known non-dispatch refusal; missing/null acceptance is not.
            if(!"receipt".equals(op.optString("state"))||receipt==null||!receipt.has("accepted")||receipt.isNull("accepted")
                    ||receipt.optString("status").matches("unknown|unconfirmed|reconciling")||receipt.optString("state").contains("unknown")
                    ||receipt.optString("code").contains("unknown")||receipt.optBoolean("requiresReconciliation"))unknown=true;
        }
        out.put("physicalRequestsObserved",physical.size()).put("unknownOperation",unknown);
        boolean sourceCurrent;
        try{sourceCurrent=trial.getString("sourceSha256").equals(EvaluationDataset.sha(SyntheticEvaluationPort.read(dir.resolve("source.json"))));}
        catch(Exception unavailable){sourceCurrent=false;}
        out.put("sourceChecked",sourceCurrent);
        JSONObject result=null,claim=null;String resultHash="",claimHash="";boolean artifactUnavailable=false;
        try{if(Files.exists(dir.resolve("result.json"),LinkOption.NOFOLLOW_LINKS)){String bytes=SyntheticEvaluationPort.read(dir.resolve("result.json"));result=new JSONObject(bytes);resultHash=EvaluationDataset.sha(bytes);}
            if(Files.exists(dir.resolve("claim.json"),LinkOption.NOFOLLOW_LINKS)){String bytes=SyntheticEvaluationPort.read(dir.resolve("claim.json"));claim=new JSONObject(bytes);claimHash=EvaluationDataset.sha(bytes);}}
        catch(Exception unavailable){artifactUnavailable=true;}
        boolean resultBound=bound(result,id,run,epoch),claimBound=bound(claim,id,run,epoch);
        boolean resultReceipt=resultBound&&confirmed(operations,result.getString("operationId"),run,SyntheticEvaluationPort.WRITE,resultHash),claimReceipt=claimBound&&confirmed(operations,claim.getString("operationId"),run,SyntheticEvaluationPort.REPORT,claimHash);
        if(claimBound&&claim.opt("completed") instanceof Boolean)out.put("reportedComplete",claim.getBoolean("completed"));
        JSONObject sourceRead=evaluation.optJSONObject("sourceRead");boolean delivered=sourceRead!=null&&run.equals(sourceRead.optString("runId"))&&epoch==sourceRead.optInt("epoch")&&trial.getString("sourceSha256").equals(sourceRead.optString("sourceSha256"));
        boolean grounded=resultBound&&sourceCurrent&&delivered&&trial.getString("sourceId").equals(result.optString("sourceId"))&&result.optLong("sourceRevision",-1)==trial.getLong("sourceRevision")&&trial.getString("sourceSha256").equals(result.optString("sourceSha256"));
        boolean effect=false;if(resultBound){JSONObject expected=EvaluationDataset.expected(trial.getString("caseId"));effect=exactLong(result,"count",expected.getLong("count"))&&exactLong(result,"totalCents",expected.getLong("totalCents"));out.put("effectMatches",effect);}
        else if(!artifactUnavailable&&result==null)out.put("effectMatches",false);
        out.put("grounded",grounded).put("resultReceiptConfirmed",resultReceipt).put("claimReceiptConfirmed",claimReceipt);
        if(resultBound)out.put("resultOperationId",result.getString("operationId"));if(claimBound)out.put("claimOperationId",claim.getString("operationId"));
        String state;
        if(task.optBoolean("cancelRequested")||"cancelled".equals(task.optString("status")))state="cancelled";
        else if(unknown)state="unknown_effect";
        else if(!sourceCurrent)state="source_changed";
        else if(artifactUnavailable||result!=null&&!resultReceipt||claim!=null&&!claimReceipt)state="harness_error";
        else if(hostFailure!=null&&!hostFailure.isEmpty())state=physical.isEmpty()&&(operations==null||operations.length()==0)?"skipped":"harness_error";
        else if(task.optJSONObject("evidence")!=null&&task.getJSONObject("evidence").optJSONObject("ownerQuestion")!=null)state="handover";
        else if(effect&&grounded&&resultReceipt)state="success";
        else if(claimReceipt&&claim.getBoolean("completed"))state="false_success";
        else state="actual_failure";
        out.put("classification",state).put("passed","success".equals(state));
        if("skipped".equals(state)||"harness_error".equals(state))out.put("reason",hostFailure==null||hostFailure.isEmpty()?"native_artifact_unavailable":"native_execution_unavailable");
        return out;
    }
    private static boolean bound(JSONObject row,String task,String run,int epoch){return row!=null&&task.equals(row.optString("taskId"))&&run.equals(row.optString("runId"))&&epoch==row.optInt("epoch",-1)&&row.optString("operationId").matches("[a-f0-9-]{36}");}
    private static boolean exactLong(JSONObject object,String key,long expected){Object v=object.opt(key);return (v instanceof Integer||v instanceof Long)&&((Number)v).longValue()==expected;}
    private static boolean confirmed(JSONArray rows,String operation,String run,String tool,String artifactHash)throws Exception{
        for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i),receipt=row.optJSONObject("receipt");if(operation.equals(row.optString("operationId"))&&run.equals(row.optString("runId"))&&tool.equals(row.optString("tool"))&&"receipt".equals(row.optString("state"))&&receipt!=null&&receipt.optBoolean("ok")&&Boolean.TRUE.equals(receipt.opt("accepted"))&&operation.equals(receipt.optString("operationId"))&&artifactHash.equals(receipt.optString("artifactSha256")))return true;}return false;
    }
    /** Only tune records participate; holdout is never feedback for choosing a strategy. */
    public static JSONObject tuning(JSONArray tasks)throws Exception{
        JSONArray differences=new JSONArray();int base=0,candidate=0,baseFalse=0,candidateFalse=0,complete=0;
        for(String caseId:EvaluationDataset.TUNE){JSONObject a=find(tasks,caseId,"baseline"),b=find(tasks,caseId,"verify-first");
            JSONObject pa=probe(a),pb=probe(b);if(pa!=null)complete++;if(pb!=null)complete++;
            if(pa!=null&&pa.optBoolean("passed"))base++;if(pb!=null&&pb.optBoolean("passed"))candidate++;
            if(pa!=null&&"false_success".equals(pa.optString("classification")))baseFalse++;if(pb!=null&&"false_success".equals(pb.optString("classification")))candidateFalse++;
            if(pa!=null&&pb!=null)differences.put(new JSONObject().put("caseId",caseId).put("baseline",pa.getString("classification")).put("candidate",pb.getString("classification")).put("regression",pa.optBoolean("passed")&&!pb.optBoolean("passed")));
        }
        String recommendation=complete<4?"":candidate>base||candidate==base&&candidateFalse<baseFalse?"verify-first":"baseline";
        return new JSONObject().put("pool","tune").put("completed",complete).put("denominator",4).put("baselinePassed",base).put("candidatePassed",candidate).put("baselineFalseSuccess",baseFalse).put("candidateFalseSuccess",candidateFalse).put("differences",differences).put("recommendation",recommendation).put("holdoutUsedForTuning",false).put("globalSettingsChanged",false);
    }
    private static JSONObject find(JSONArray tasks,String id,String strategy)throws Exception{for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i),trial=task.getJSONObject("evaluation").getJSONObject("trial");if("tune".equals(trial.optString("pool"))&&id.equals(trial.optString("caseId"))&&strategy.equals(trial.optString("strategy")))return task;}return null;}
    private static JSONObject probe(JSONObject task)throws Exception{return task==null?null:task.getJSONObject("evaluation").optJSONObject("probe");}
}
