package dev.pocketengine.features.teams;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded value operations for the ORIGINAL TaskStore document, never a store or dispatch authority.
 * TaskStore must load, change and persist these values in its existing SQLite transaction. */
public final class TeamParentBudget {
    public static final int MAX_TEXT_TOOL_OPERATIONS=32,MAX_PROJECT_TOOL_OPERATIONS=128,MAX_MODEL_REQUESTS=64,MAX_PROJECT_MODEL_REQUESTS=128;
    private TeamParentBudget(){}
    /** Visible planning allowance, not requests to dispatch or a guarantee of fitting history.
     * Include each affected member and the integrator's command/package approval re-entries. */
    public static int estimatedProjectSummaryRequests(int affectedMembers,int integratorCommands){
        if(affectedMembers<1||affectedMembers>4||integratorCommands<0)throw new IllegalArgumentException("原完整项目摘要估算范围无效");
        return Math.addExact(Math.addExact(affectedMembers,integratorCommands),1);
    }
    /** Issued only by the original value-level pre-reservation check, never after dispatch. */
    public static final class Exhausted extends IllegalStateException {
        public final String parentTaskId,parentRunId,dimension;public final int used,limit;
        private Exhausted(JSONObject pool,String dimension)throws Exception{super("原父任务团队总预算已用尽，回答保留但未派发");this.parentTaskId=pool.getString("parentTaskId");this.parentRunId=pool.getString("parentRunId");this.dimension=dimension;used=used(pool,dimension);limit=pool.getInt(dimension.equals("modelRequests")?"modelRequestLimit":"toolOperationLimit");}
    }
    public static JSONObject create(String parentTask,String parentRun,int modelLimit,int operationLimit,JSONObject nativeSource)throws Exception {
        boolean project=nativeSource.optBoolean("nativeProjectWorkPlan");
        if(modelLimit<0||modelLimit>(project?MAX_PROJECT_MODEL_REQUESTS:MAX_MODEL_REQUESTS)||operationLimit<0||operationLimit>(project?MAX_PROJECT_TOOL_OPERATIONS:MAX_TEXT_TOOL_OPERATIONS))throw new SecurityException("原父预算上限无效");
        return new JSONObject().put("parentTaskId",parentTask).put("parentRunId",parentRun)
                .put("modelRequestLimit",modelLimit).put("toolOperationLimit",operationLimit).put("budgetSource",new JSONObject(nativeSource.toString())).put("reservations",new JSONArray())
                .put("tokenLimitKnown",false).put("costLimitKnown",false);
    }
    public static JSONObject reserve(JSONObject pool,JSONObject binding,String phase,String dedup,int model,int tool)throws Exception {
        if(!phase.matches("member|review|integration|summary|parent_coordination")||dedup==null||dedup.isEmpty()||dedup.length()>240
                ||model<0||tool<0||model+tool!=1)throw new SecurityException("父预算预留身份或单位无效");
        String coordination=binding.optString("parentCoordinationScopeId");
        if(!coordination.isEmpty()||binding.optString("teamRunId").isEmpty()){
            JSONObject source=pool.getJSONObject("budgetSource");
            if(!phase.equals("parent_coordination")||coordination.isEmpty()||!coordination.equals(pool.getString("parentRunId"))||!coordination.equals(source.optString("parentCoordinationScopeId"))||!source.optString("nativeProjectRoundReview").matches("[a-f0-9]{64}")||!source.optBoolean("includeParentInference")||!binding.optString("teamRunId").isEmpty()||!pool.getString("parentTaskId").equals(binding.optString("childTaskId"))||!nativeRoundRunBinding(pool,binding)||!"@parent".equals(binding.optString("memberId"))||!pool.getString("parentTaskId").equals(binding.optString("assignmentId")))throw new SecurityException("空teamRun仅限原native新round父协调范围，不是模型可声明的团队授权");
        }
        JSONArray rows=pool.getJSONArray("reservations");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!dedup.equals(row.getString("dedupKey"))&&!dedup.equals(row.optString("productionDedupKey")))continue;
            for(String key:new String[]{"teamRunId","parentCoordinationScopeId","childTaskId","childRunId","childEpoch","memberId","assignmentId"})
                if(!row.optString(key).equals(binding.optString(key)))throw new SecurityException("父预留去重键属于另一原执行");
            if(!phase.equals(row.getString("phase"))||model!=row.getInt("modelRequests")||tool!=row.getInt("toolOperations")
                    ||row.optString("state").equals("released_unstarted"))throw new SecurityException("原父预留不可重用");
            return row;
        }
        if(remaining(pool,"modelRequests")<model)throw new Exhausted(pool,"modelRequests");
        if(remaining(pool,"toolOperations")<tool)throw new Exhausted(pool,"toolOperations");
        if(rows.length()>=reservationCeiling(pool))throw new IllegalStateException("原父预留记录已满，未丢弃未知或迟到用量");
        JSONObject row=new JSONObject(binding.toString()).put("reservationId",UUID.randomUUID().toString())
                .put("parentTaskId",pool.getString("parentTaskId")).put("parentRunId",pool.getString("parentRunId"))
                .put("phase",phase).put("dedupKey",dedup).put("modelRequests",model).put("toolOperations",tool)
                .put("state","reserved").put("inferenceCallIds",new JSONArray()).put("operationIds",new JSONArray())
                .put("actualReceiptRefs",new JSONArray()).put("reservedAt",System.currentTimeMillis());
        rows.put(row);return row;
    }
    private static boolean nativeRoundRunBinding(JSONObject pool,JSONObject binding)throws Exception {
        if(pool.getString("parentRunId").equals(binding.optString("childRunId")))return true;String digest=binding.optString("nativeRoundContinuationDigest");if(!digest.matches("[a-f0-9]{64}"))return false;JSONArray approved=pool.getJSONObject("budgetSource").optJSONArray("ownerContinuations");for(int i=0;approved!=null&&i<approved.length();i++){JSONObject row=approved.getJSONObject(i);if(digest.equals(row.optString("reviewDigest"))&&pool.getString("parentTaskId").equals(row.optString("taskId"))&&pool.getString("parentRunId").equals(row.optString("scopeId"))&&binding.optString("childRunId").equals(row.optString("toRunId"))&&binding.optInt("childEpoch")==row.optInt("toEpoch"))return true;}return false;
    }
    /** Engineering ceilings only. Neither this marker nor a Reply grants additional units. */
    public static int modelCeiling(JSONObject pool)throws Exception{return pool.getJSONObject("budgetSource").optBoolean("nativeProjectWorkPlan")?MAX_PROJECT_MODEL_REQUESTS:MAX_MODEL_REQUESTS;}
    public static int toolCeiling(JSONObject pool)throws Exception{return pool.getJSONObject("budgetSource").optBoolean("nativeProjectWorkPlan")?MAX_PROJECT_TOOL_OPERATIONS:MAX_TEXT_TOOL_OPERATIONS;}
    public static int reservationCeiling(JSONObject pool)throws Exception{return pool.getJSONObject("budgetSource").optBoolean("nativeProjectWorkPlan")?MAX_PROJECT_MODEL_REQUESTS+MAX_PROJECT_TOOL_OPERATIONS+32:128;}
    private static int used(JSONObject pool,String unit)throws Exception {
        int used=unit.equals("externalJobs")?pool.optInt("externalJobsBeforePool",0):0;JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!row.getString("state").equals("released_unstarted"))used+=row.optInt(unit,0);
        }
        return used;
    }
    public static int remaining(JSONObject pool,String unit)throws Exception {
        String limit=unit.equals("modelRequests")?"modelRequestLimit":unit.equals("toolOperations")?"toolOperationLimit":unit.equals("externalJobs")?"externalJobLimit":null;
        if(limit==null)throw new IllegalArgumentException("不是原父预算维度");return Math.max(0,pool.optInt(limit,0)-used(pool,unit));
    }
    public static JSONObject snapshot(JSONObject pool)throws Exception {
        int models=remaining(pool,"modelRequests"),tools=remaining(pool,"toolOperations");JSONObject states=new JSONObject();JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String state=row.getString("state");JSONObject counts=states.optJSONObject(state);if(counts==null){counts=new JSONObject().put("modelRequests",0).put("toolOperations",0).put("externalJobs",0);states.put(state,counts);}for(String unit:new String[]{"modelRequests","toolOperations","externalJobs"})counts.put(unit,counts.getInt(unit)+row.optInt(unit));}
        JSONObject value=new JSONObject().put("parentTaskId",pool.getString("parentTaskId")).put("parentRunId",pool.getString("parentRunId"))
                .put("modelRequestLimit",pool.getInt("modelRequestLimit")).put("toolOperationLimit",pool.getInt("toolOperationLimit"))
                .put("modelRequestsReservedOrUsed",used(pool,"modelRequests")).put("toolOperationsReservedOrUsed",used(pool,"toolOperations"))
                .put("remainingModelRequests",models).put("remainingToolOperations",tools).put("reservationStates",states).put("priorExternalJobsUnattributed",pool.optInt("externalJobsBeforePool",0)).put("budgetSource",new JSONObject(pool.getJSONObject("budgetSource").toString()))
                .put("externalJobLimit",pool.optInt("externalJobLimit",0)).put("externalJobsReservedOrUsed",used(pool,"externalJobs")).put("remainingExternalJobs",remaining(pool,"externalJobs"))
                .put("externalBudgetSource",pool.has("externalBudgetSource")?new JSONObject(pool.getJSONObject("externalBudgetSource").toString()):JSONObject.NULL)
                .put("externalJobUnit","one_native_owner_approved_remote_job_not_model_request").put("remoteInternalModelRequestsKnown",false)
                .put("remoteInputTokens",JSONObject.NULL).put("remoteOutputTokens",JSONObject.NULL).put("remoteCost",JSONObject.NULL)
                .put("tokenLimitKnown",false).put("costLimitKnown",false);
        JSONObject prior=pool.getJSONObject("budgetSource").optJSONObject("priorProjectUsage");if(prior!=null){JSONObject cumulative=new JSONObject();for(String unit:new String[]{"modelRequests","toolOperations","externalJobs"})cumulative.put(unit+"ReservedOrUsed",Math.addExact(prior.getInt(unit+"ReservedOrUsed"),used(pool,unit)));value.put("priorProjectUsage",new JSONObject(prior.toString())).put("projectCumulativeUsage",cumulative).put("newExecutionBudgetNotProjectLifetimeZero",true);}return value;
    }
    /** Separate external job allowance, sourced only from the original native team plan. No local model/tool transfer. */
    public static void initializeExternalJobs(JSONObject pool,JSONObject nativePlan)throws Exception {
        int limit=nativePlan.getInt("externalTurnLimit"),used=nativePlan.getInt("externalTurnsUsed");
        if(limit<0||limit>32||used<0||used>limit)throw new SecurityException("原远端job预算来源无效");
        if(!pool.has("externalJobLimit")){pool.put("externalJobLimit",limit).put("externalJobsBeforePool",used).put("externalBudgetSource",new JSONObject(nativePlan.toString()));}
    }
    /** Only the existing explicit owner resume path calls this. A Reply/remote receipt never refills a pool. */
    public static void ownerExternalLimitChanged(JSONObject pool,JSONObject nativePlan)throws Exception {
        initializeExternalJobs(pool,nativePlan);int limit=nativePlan.getInt("externalTurnLimit");if(limit<used(pool,"externalJobs"))throw new SecurityException("新远端job上限小于原已预留额度");pool.put("externalJobLimit",limit).put("externalBudgetSource",new JSONObject(nativePlan.toString()));
    }
    public static JSONObject reserveExternalJob(JSONObject pool,JSONObject binding,String preparedOperation,String jobOperation,String reservationId,JSONObject approvedScope)throws Exception {
        if(!preparedOperation.matches("[a-f0-9-]{36}")||!jobOperation.matches("[a-f0-9-]{36}")||!reservationId.matches("[a-f0-9-]{36}"))throw new SecurityException("远端job预留缺少原操作编号");
        String dedup="external_job:"+preparedOperation;JSONArray rows=pool.getJSONArray("reservations");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!dedup.equals(row.optString("dedupKey")))continue;
            for(String key:new String[]{"teamRunId","childTaskId","childRunId","memberId","assignmentId"})if(!row.optString(key).equals(binding.optString(key)))throw new SecurityException("原远端预留属于另一成员或执行");
            if(!reservationId.equals(row.getString("reservationId"))||!jobOperation.equals(row.getString("jobOperationId"))||!TeamWorkFiles.canonical(approvedScope).equals(TeamWorkFiles.canonical(row.getJSONObject("approvedScope"))))throw new SecurityException("原远端job scope不可替换");
            if(row.getString("state").equals("released_unstarted"))throw new SecurityException("已确定未启动释放的原job不能重放");return row;
        }
        if(remaining(pool,"externalJobs")<1)throw new IllegalStateException("原父external job额度已用尽；未挪用本地模型或工具预算");if(rows.length()>=reservationCeiling(pool))throw new IllegalStateException("原父预留记录已满，未丢弃未知用量");
        JSONObject row=new JSONObject(binding.toString()).put("reservationId",reservationId).put("parentTaskId",pool.getString("parentTaskId")).put("parentRunId",pool.getString("parentRunId"))
                .put("phase","external_job").put("dedupKey",dedup).put("modelRequests",0).put("toolOperations",0).put("externalJobs",1).put("jobOperationId",jobOperation)
                .put("approvedScope",new JSONObject(approvedScope.toString())).put("state","reserved").put("inferenceCallIds",new JSONArray()).put("operationIds",new JSONArray().put(preparedOperation)).put("actualReceiptRefs",new JSONArray()).put("reservedAt",System.currentTimeMillis())
                .put("internalModelRequests",JSONObject.NULL).put("inputTokens",JSONObject.NULL).put("outputTokens",JSONObject.NULL).put("cost",JSONObject.NULL).put("internalInferenceHardCapEnforced",false);
        rows.put(row);return row;
    }
    public static void startExternalJob(JSONObject pool,String reservation,String jobOperation)throws Exception {
        JSONObject row=external(pool,reservation,jobOperation);if(!row.getString("state").equals("reserved"))throw new SecurityException("原远端job只可首次进入派发，旧操作只查询");row.put("state","started").put("startedAt",System.currentTimeMillis());
    }
    /** Native verified server receipt only. Completed/cancelled jobs still spend their job unit; unknown is never zero. */
    public static void finishExternalJob(JSONObject pool,String reservation,String jobOperation,JSONObject actual,boolean verifiedNeverStarted)throws Exception {
        JSONObject row=external(pool,reservation,jobOperation);String status=actual.optString("status"),state=row.getString("state"),job=actual.optString("id",actual.optString("jobId"));
        if(!jobOperation.equals(actual.optString("operationId",actual.optString("jobOperationId"))))throw new SecurityException("迟账不属于原job operation");
        JSONObject ref=actual.optJSONObject("assignmentWorkspace");if(ref==null||!reservation.equals(ref.optString("reservationId")))throw new SecurityException("原job回执缺少精确external预留");
        if(!job.isEmpty()){if(row.has("jobId")&&!job.equals(row.getString("jobId")))throw new SecurityException("迟账不属于原远端job");row.put("jobId",job);}
        boolean terminal=status.matches("completed|failed|cancelled|timed_out|blocked|rejected"),never=terminal&&Boolean.FALSE.equals(actual.opt("processStarted"))&&actual.optBoolean("dispatchFenceFinal")&&actual.optBoolean("notStartedConfirmed");
        if(never&&row.optBoolean("processStartedConfirmed"))throw new SecurityException("原job已有实际启动证明，迟到未启动不能退款");row.put("processStartedConfirmed",row.optBoolean("processStartedConfirmed")||actual.optBoolean("processStarted"));
        if(verifiedNeverStarted&&!never)throw new SecurityException("未启动释放必须有原server最终未启动证明，queued/取消/ACK未知不能退款");
        if(state.equals("released_unstarted")){if(!never)throw new SecurityException("原job已依确证释放却收到相反回执，冻结核对，不归到新执行");return;}
        if(state.equals("settled")){
            JSONObject prior=row.getJSONObject("actualJobReceipt");
            if(!terminal||!actual.optBoolean("dispatchFenceFinal"))return; // Late running/unknown cannot undo terminal settlement.
            if(prior.optBoolean("processStarted")&&!actual.optBoolean("processStarted"))throw new SecurityException("原job已启动，不能用迟到未启动证明退款");
            if(!status.equals(prior.optString("status")))throw new SecurityException("原job终态冲突，只能核对原件");return;
        }
        if(never)row.put("state","released_unstarted").put("notStartedProof",new JSONObject(actual.toString()));
        else row.put("state",terminal&&actual.optBoolean("dispatchFenceFinal")?"settled":status.matches("queued|running|awaiting_approval|stopping")?"started":"unknown");
        JSONArray refs=row.getJSONArray("actualReceiptRefs");String receipt=actual.optString("receiptId",jobOperation);if(!has(refs,receipt))refs.put(receipt);row.put("actualJobReceipt",new JSONObject(actual.toString())).put("updatedAt",System.currentTimeMillis());
    }

    private static JSONObject external(JSONObject pool,String reservation,String operation)throws Exception {JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(reservation.equals(row.optString("reservationId"))&&operation.equals(row.optString("jobOperationId"))&&row.optInt("externalJobs")==1)return row;}throw new SecurityException("没有这次原父external job预留");}
    public static JSONObject startInference(JSONObject pool,String task,String run,String callId)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");JSONObject pending=null;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(has(row.getJSONArray("inferenceCallIds"),callId))return row;
            if(task.equals(row.optString("childTaskId"))&&run.equals(row.optString("childRunId"))&&row.getInt("modelRequests")==1&&row.getString("state").equals("reserved")){
                if(pending!=null)throw new SecurityException("原模型调用有多个未绑定父预留");pending=row;
            }
        }
        if(pending==null)throw new SecurityException("模型派发没有原父pool预留");
        pending.getJSONArray("inferenceCallIds").put(callId);pending.put("state","started").put("startedAt",System.currentTimeMillis());return pending;
    }
    /** Exact native coordinator request site; never guess among concurrent members of one document run. */
    public static JSONObject startInferenceExact(JSONObject pool,String task,String run,String member,JSONObject site,String callId)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");JSONObject selected=null;String key=TeamWorkFiles.canonical(site);
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(has(row.getJSONArray("inferenceCallIds"),callId)){
            if(!task.equals(row.optString("childTaskId"))||!run.equals(row.optString("childRunId"))||!member.equals(row.optString("memberId"))||!key.equals(TeamWorkFiles.canonical(row.getJSONObject("nativeCallSite"))))throw new SecurityException("原callId的native site不可替换");return row;}
            if(task.equals(row.optString("childTaskId"))&&run.equals(row.optString("childRunId"))&&member.equals(row.optString("memberId"))&&row.optJSONObject("nativeCallSite")!=null&&key.equals(TeamWorkFiles.canonical(row.getJSONObject("nativeCallSite")))){if(selected!=null)throw new SecurityException("原native call site有重复预留");selected=row;}
        }
        if(selected==null||!selected.optString("state").equals("reserved"))throw new SecurityException("原模型调用缺少精确未消费父预留");selected.getJSONArray("inferenceCallIds").put(callId);selected.put("state","started").put("startedAt",System.currentTimeMillis());return selected;
    }
    public static void releaseInferenceSite(JSONObject pool,String task,String run,JSONObject site)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(task.equals(row.optString("childTaskId"))&&run.equals(row.optString("childRunId"))&&row.optJSONObject("nativeCallSite")!=null&&TeamWorkFiles.canonical(site).equals(TeamWorkFiles.canonical(row.getJSONObject("nativeCallSite")))&&row.getString("state").equals("reserved")&&row.getJSONArray("inferenceCallIds").length()==0)row.put("state","released_unstarted");}
    }
    /** Terminal usage is retained even after cancel. Unknown usage is NOT zero and never refunds units. */
    public static void finishInference(JSONObject pool,String callId,JSONObject actualReceipt)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
            if(!has(row.getJSONArray("inferenceCallIds"),callId))continue;
            JSONObject usage=actualReceipt.optJSONObject("usage");row.put("state",usage!=null&&usage.optBoolean("reported")&&usage.optJSONObject("counters")!=null&&!usage.getJSONObject("counters").getJSONObject("INPUT").isNull("value")&&!usage.getJSONObject("counters").getJSONObject("OUTPUT").isNull("value")?"settled":"unknown")
                    .put("actualUsage",usage==null?JSONObject.NULL:new JSONObject(usage.toString())).put("outcome",actualReceipt.getString("outcome"));
            JSONArray refs=row.getJSONArray("actualReceiptRefs");if(!has(refs,callId))refs.put(callId);return;
        }throw new SecurityException("迟到用量没有原父预留，不能归到新执行");
    }
    public static void releaseUnstarted(JSONObject pool,String task,String run)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
            if(task.equals(row.optString("childTaskId"))&&run.equals(row.optString("childRunId"))&&row.getString("state").equals("reserved")
                    &&row.getJSONArray("inferenceCallIds").length()==0&&row.getJSONArray("operationIds").length()==0)row.put("state","released_unstarted");
        }
    }
    private static boolean has(JSONArray rows,String id){for(int i=0;i<rows.length();i++)if(id.equals(rows.optString(i)))return true;return false;}
}
