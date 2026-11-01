package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** The unique integration child's original AgentRunner surface; applies one reviewed file per journal operation. */
public final class TeamIntegrationFiles implements CapabilityPort {
    private final TeamWorkFiles.Workspace workspace;private final TeamWorkFiles.Host host;private final RunControl original;
    private final JSONObject binding,plan;private final String digest;private boolean closed;
    public TeamIntegrationFiles(TeamWorkFiles.Workspace workspace,TeamWorkFiles.Host host,RunControl original,JSONObject nativeBinding)throws Exception {
        this.workspace=java.util.Objects.requireNonNull(workspace);this.host=java.util.Objects.requireNonNull(host);this.original=java.util.Objects.requireNonNull(original);binding=new JSONObject(nativeBinding.toString());plan=binding.getJSONObject("plan");digest=TeamWorkFiles.hash(TeamWorkFiles.canonical(plan));
        if(!digest.equals(binding.getString("planHash"))||!plan.getString("teamRunId").equals(binding.getString("teamRunId"))||!plan.getString("targetProjectId").equals(binding.getString("targetProjectId"))||!binding.getString("reviewId").matches("[a-f0-9-]{36}"))throw new SecurityException("唯一整合者缺少主人审查的原成果计划");
        JSONObject recovery=plan.optJSONObject("recovery");if(recovery!=null&&(!binding.getString("taskId").equals(recovery.getString("taskId"))||binding.getString("reviewId").equals(recovery.getString("reviewId"))||!recovery.getString("planHash").matches("[a-f0-9]{64}")||!recovery.getString("receiptsHash").matches("[a-f0-9]{64}")))throw new SecurityException("重新审查不能替换原唯一整合任务");
        JSONArray retained=plan.optJSONArray("retained");java.util.Set<String> retainedPaths=new java.util.HashSet<>();for(int i=0;retained!=null&&i<retained.length();i++){JSONObject applied=retained.getJSONObject(i),file=selected(applied.getString("path"),applied.getString("artifactId"));if(recovery==null||!retainedPaths.add(file.getString("path"))||!TeamWorkReview.sameArtifact(file,applied,binding.getString("taskId"),binding.getString("targetProjectId"))||file.getInt("expectedVersion")!=applied.getInt("version")||!file.getString("expectedHash").equals(applied.getString("sha256")))throw new SecurityException("保留文件不是原成功回执对应的实际审查版本");}check("team_read_integration");
    }
    @Override public JSONArray tools()throws Exception {
        JSONObject empty=new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false);
        JSONObject fields=new JSONObject().put("path",new JSONObject().put("type","string").put("maxLength",240)).put("artifactId",new JSONObject().put("type","string").put("maxLength",36));
        return new JSONArray().put(tool("team_read_integration","只读主人已审查的原成果计划和实际整合回执；不会重新批准或合并文件。",empty))
                .put(tool("team_integrate_file","唯一整合者按主人审查的原成果逐文件CAS保存原项目；每文件仍需原prepared记录和明确批准。",new JSONObject().put("type","object").put("properties",fields).put("required",new JSONArray().put("path").put("artifactId")).put("additionalProperties",false)));
    }
    private static JSONObject tool(String name,String description,JSONObject parameters)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",parameters));}
    @Override public boolean supports(String name){return "team_read_integration".equals(name)||"team_integrate_file".equals(name);}
    @Override public boolean sideEffecting(String name){return "team_integrate_file".equals(name);}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!supports(name)||args.length()!=(sideEffecting(name)?2:0))throw new SecurityException("整合参数不能改变原任务、计划或主人审查");if(sideEffecting(name)){if(!args.has("path")||!args.has("artifactId"))throw new SecurityException("需选择主人审查的原成果");selected(args.getString("path"),args.getString("artifactId"));}
    }
    private JSONObject selected(String path,String artifact)throws Exception {JSONArray files=plan.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(path.equals(file.getString("path"))&&artifact.equals(file.getString("artifactId")))return file;}throw new SecurityException("文件或成果不在主人审查的原整合计划");}
    private void check(String tool)throws Exception {if(closed||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("唯一整合者原执行身份改变");original.check();if(original.isPaused())throw new SecurityException("原整合者已暂停");host.check(tool,original);}
    @Override public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception {
        if(control!=original)throw new SecurityException("整合没有原RunControl");check(name);JSONObject args=new JSONObject(input.toString());Object operation=args.opt("_hostOperationId");args.remove("_hostOperationId");validate(name,args);
        if(!sideEffecting(name)){if(operation!=null)throw new SecurityException("只读整合不能提供prepared操作");return new JSONObject().put("plan",new JSONObject(plan.toString())).put("planHash",digest).put("reviewId",binding.getString("reviewId")).put("receipts",host.receipts("team_integrate_file")).put("atomicBatch",false);}
        if(!(operation instanceof String)||!((String)operation).matches("[a-f0-9-]{36}"))throw new SecurityException("整合必须由原AgentRunner先登记prepared操作");
        JSONObject ref=selected(args.getString("path"),args.getString("artifactId")),artifact=TeamWorkReview.artifact(workspace,ref);JSONArray receipts=host.receipts(name);JSONObject retained=retained(ref);
        if(retained!=null){
            if(!containsApplied(receipts,retained))throw new SecurityException("原宿主没有保留文件对应的成功操作回执");
            if(!TeamWorkReview.sameHead(TeamWorkFiles.snapshot(workspace,binding.getString("targetProjectId"),ref.getString("path")),retained))return conflict("已保留文件在重新审查后发生变化，请再次查看实际差异；不会重放原成功操作");
            check(name);return new JSONObject().put("ok",true).put("status","completed").put("known",true).put("teamRetained",new JSONObject().put("planHash",digest).put("reviewId",binding.getString("reviewId")).put("integrationTaskId",binding.getString("taskId")).put("teamIntegrated",new JSONObject(retained.toString()))).put("businessVerified",false).put("message","本文件仍是原成功回执对应的实际版本，已保留；没有再次写入");
        }
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i),applied=receipt.optJSONObject("teamIntegrated");if(receipt.optBoolean("ok")&&applied!=null&&args.getString("path").equals(applied.optString("path"))){
            if(!binding.getString("taskId").equals(applied.optString("integrationTaskId"))||!binding.getString("teamRunId").equals(applied.optString("teamRunId"))||!binding.getString("targetProjectId").equals(applied.optString("projectId")))throw new SecurityException("原整合回执不属于本唯一整合任务");
            if(!digest.equals(applied.optString("planHash"))){if(plan.optJSONObject("recovery")==null)throw new SecurityException("原整合回执冲突，必须核对");continue;}
            if(!TeamWorkReview.sameArtifact(ref,applied,binding.getString("taskId"),binding.getString("targetProjectId")))throw new SecurityException("原整合回执的文件或成果与当前审查不符");
            if(!TeamWorkReview.sameHead(TeamWorkFiles.snapshot(workspace,binding.getString("targetProjectId"),ref.getString("path")),applied))return conflict("本文件已有成功历史，但当前目标版本已变化；原回执保留，请重新审查，不会重放旧操作");
            check(name);return new JSONObject(receipt.toString()).put("known",true);
        }}
        JSONObject before=TeamWorkFiles.snapshot(workspace,binding.getString("targetProjectId"),ref.getString("path"));String hash=before.optBoolean("exists")?TeamWorkFiles.hash(before.getString("content")):"absent";
        if(before.getInt("version")!=ref.getInt("expectedVersion")||!hash.equals(ref.getString("expectedHash")))return conflict("原项目在主人审查后发生变化，本文件未派发；请查看当前文件和已有整合回执");
        JSONObject capability=new JSONObject().put("teamRunId",binding.getString("teamRunId")).put("assignmentId",binding.getString("assignmentId")).put("memberId",binding.getString("memberId")).put("targetProjectId",binding.getString("targetProjectId")).put("planHash",digest).put("reviewId",binding.getString("reviewId")).put("file",ref).put("requiresOwnerApproval",true);
        long pause=original.pauseRevision();JSONObject claim=host.claim((String)operation,name,args,capability,original);if(!claim.optBoolean("dispatch")){if(claim.optString("code").isEmpty())throw new SecurityException("整合没有明确原生回执");return claim;}check(name);
        try{JSONObject[] written={null};original.dispatchAtPauseRevision(pause,()->written[0]=workspace.call("write",new JSONObject().put("projectId",binding.getString("targetProjectId")).put("path",ref.getString("path")).put("content",artifact.getString("content")).put("expectedVersion",ref.getInt("expectedVersion"))));
            JSONObject actual=workspace.call("read",new JSONObject().put("projectId",binding.getString("targetProjectId")).put("path",ref.getString("path")).put("version",written[0].getInt("version")));
            if(!artifact.getString("content").equals(actual.getString("content"))||!written[0].getString("revisionId").equals(actual.getString("revisionId")))throw new IllegalStateException("actual integrated bytes differ");
            return new JSONObject().put("ok",true).put("status","completed").put("teamIntegrated",new JSONObject(ref.toString()).put("projectId",binding.getString("targetProjectId")).put("version",actual.getInt("version")).put("revisionId",actual.getString("revisionId")).put("planHash",digest).put("reviewId",binding.getString("reviewId")).put("integrationTaskId",binding.getString("taskId")).put("integrationRunId",binding.getString("runId"))).put("businessVerified",false).put("message","本文件已按主人审查的原成果实际整合；其他文件以各自原回执为准");
        }catch(Exception unknown){return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","team_integration_unknown").put("operationId",operation).put("message","原文件整合没有确定回执；可能已有部分文件保存，只核对原操作，不会重放");}
    }
    private JSONObject retained(JSONObject ref)throws Exception {JSONArray retained=plan.optJSONArray("retained");for(int i=0;retained!=null&&i<retained.length();i++){JSONObject value=retained.getJSONObject(i);if(ref.getString("path").equals(value.getString("path"))&&ref.getString("artifactId").equals(value.getString("artifactId")))return value;}return null;}
    private static boolean containsApplied(JSONArray receipts,JSONObject expected)throws Exception {String exact=TeamWorkFiles.canonical(expected);for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i),actual=receipt.optJSONObject("teamIntegrated");if(receipt.optBoolean("ok")&&actual!=null&&exact.equals(TeamWorkFiles.canonical(actual)))return true;}return false;}
    /** Actual per-file readback only. Native SQL unresolved operations and current review remain separate mandatory fences. */
    public static JSONObject progress(TeamWorkFiles.Workspace workspace,JSONObject nativeBinding,JSONArray nativeReceipts)throws Exception {
        JSONObject binding=new JSONObject(nativeBinding.toString()),plan=binding.getJSONObject("plan");String digest=TeamWorkFiles.hash(TeamWorkFiles.canonical(plan));if(!digest.equals(binding.getString("planHash"))||!plan.getString("teamRunId").equals(binding.getString("teamRunId"))||!plan.getString("targetProjectId").equals(binding.getString("targetProjectId")))throw new SecurityException("整合进度没有原主人审查计划");
        JSONArray files=plan.getJSONArray("files"),results=new JSONArray(),remaining=new JSONArray(),conflicts=new JSONArray(),kept=plan.optJSONArray("retained");int appliedCount=0,retainedCount=0;
        for(int i=0;i<files.length();i++){
            JSONObject ref=files.getJSONObject(i);TeamWorkReview.artifact(workspace,ref);JSONObject actual=TeamWorkFiles.snapshot(workspace,binding.getString("targetProjectId"),ref.getString("path")),applied=null;boolean retained=false;
            for(int r=0;r<nativeReceipts.length();r++){JSONObject receipt=nativeReceipts.getJSONObject(r),saved=receipt.optJSONObject("teamIntegrated");if(receipt.optBoolean("ok")&&saved!=null&&digest.equals(saved.optString("planHash"))&&binding.getString("reviewId").equals(saved.optString("reviewId"))&&TeamWorkReview.sameArtifact(ref,saved,binding.getString("taskId"),binding.getString("targetProjectId"))&&TeamWorkReview.sameHead(actual,saved))applied=saved;}
            if(applied==null)for(int r=0;kept!=null&&r<kept.length();r++){JSONObject saved=kept.getJSONObject(r);if(TeamWorkReview.sameArtifact(ref,saved,binding.getString("taskId"),binding.getString("targetProjectId"))&&containsApplied(nativeReceipts,saved)&&TeamWorkReview.sameHead(actual,saved)){applied=saved;retained=true;}}
            JSONObject result=new JSONObject().put("path",ref.getString("path")).put("artifactId",ref.getString("artifactId")).put("currentVersion",actual.getInt("version")).put("currentRevisionId",actual.optString("revisionId"));
            if(applied!=null){result.put("state",retained?"retained":"applied").put("teamIntegrated",new JSONObject(applied.toString()));if(retained)retainedCount++;else appliedCount++;}
            else {String hash=actual.optBoolean("exists")?TeamWorkFiles.hash(actual.getString("content")):"absent";boolean cas=actual.getInt("version")==ref.getInt("expectedVersion")&&hash.equals(ref.getString("expectedHash"));result.put("state",cas?"pending":"conflict");remaining.put(ref.getString("path"));if(!cas)conflicts.put(ref.getString("path"));}results.put(result);
        }
        return new JSONObject().put("planHash",digest).put("reviewId",binding.getString("reviewId")).put("taskId",binding.getString("taskId")).put("files",results).put("applied",appliedCount).put("retained",retainedCount).put("remaining",remaining).put("conflicts",conflicts).put("complete",files.length()>0&&remaining.length()==0).put("atomicBatch",false).put("businessVerified",false);
    }
    private static JSONObject conflict(String message)throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("code","team_integration_conflict").put("requiresPause",true).put("message",message);}
    @Override public void close(){closed=true;}
}
