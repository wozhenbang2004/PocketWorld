package dev.pocketengine.features.automation;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Explicit user commitments, not tasks, inferred interests, grants or a scheduler.
 * Callers must derive scope and explicit-user provenance from their authenticated host context.
 * One Android process is supported; instances share a per-file lock and reload before each access.
 */
public final class CommitmentBook {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private static final long MAX_TIME=253402300799999L;
    private final File file;
    private final Object lock;
    private final EvidenceResolver evidenceResolver;
    private final AutomationConditions.Source conditions;
    private final AutomationBook.GoalTaskResolver goalTasks;

    /**
     * Read-only host adapter. Resolve a real, durable owner confirmation or task result, never
     * echo caller/model assertions. Return null when missing or not established as completed.
     * The returned record must contain kind,id,ownerId,spaceId,commitmentId,commitmentRevision,
     * outcome=completed, recordedAt and, for task_result, taskId. It must establish completion
     * of this revision's promise, not merely that an old associated task stopped running.
     * This is evidence lookup only: it must not start a task, request consent or perform effects.
     * Native execution plans may additionally query kind=task_state for an already-bound task.
     * That optional projection must include taskId, actorId, spaceId, commitmentId/revision,
     * status and current runId/coordinatorEpoch. terminalVerified means current failed/cancelled
     * with no unresolved effects. Unsupported lookups return null and remain unavailable.
     */
    public interface EvidenceResolver {
        JSONObject resolve(JSONObject scope,JSONObject commitment,String kind,String id)throws Exception;
    }

    public CommitmentBook(File file)throws Exception {this(file,null);}
    public CommitmentBook(File file,EvidenceResolver resolver)throws Exception {
        this(file,resolver,null);
    }
    public CommitmentBook(File file,EvidenceResolver resolver,AutomationConditions.Source conditions)throws Exception {
        this(file,resolver,conditions,null);
    }
    public CommitmentBook(File file,EvidenceResolver resolver,AutomationConditions.Source conditions,AutomationBook.GoalTaskResolver goalTasks)throws Exception {
        this.file=file;this.evidenceResolver=resolver;this.conditions=conditions;this.goalTasks=goalTasks;
        lock=LOCKS.computeIfAbsent(file.getCanonicalPath(),key->new Object());
    }
    public boolean completionEvidenceAvailable(){return evidenceResolver!=null;}

    /** Creating is an explicit host/user action; sourceRef is immutable provenance, not authority. */
    public JSONObject create(JSONObject scope,JSONObject input,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        if(!"explicit_user".equals(input.optString("kind")))throw new IllegalArgumentException("本接口仅接受明确的用户委托");
        rejectFields(input,"id","revision","status","completionEvidence","planId","planIndex");
        JSONObject source=source(identity,input.getJSONObject("sourceRef"));
        synchronized(lock){
            JSONObject state=read();JSONArray records=state.getJSONArray("commitments");
            if(records.length()>=1000)throw new IllegalStateException("承诺记录已达到1000条容量上限");
            JSONObject record=new JSONObject().put("id",UUID.randomUUID().toString()).put("revision",1L)
                    .put("ownerId",identity.getString("ownerId")).put("spaceId",identity.getString("spaceId"))
                    .put("kind","explicit_user").put("title",requiredText(input,"title",200))
                    .put("details",optionalText(input,"details",4000,""))
                    .put("sourceRef",source).put("taskId",optionalText(input,"taskId",200,""))
                    .put("status","pending").put("dueAt",time(input,"dueAt"))
                    .put("expiresAt",expiry(input,time(input,"dueAt")))
                    .put("snoozedUntil",JSONObject.NULL).put("completionEvidence",JSONObject.NULL)
                    .put("completionEvent",JSONObject.NULL).put("createdAt",now).put("updatedAt",now);
            records.put(record);write(state);return view(record,now);
        }
    }

    public JSONArray list(JSONObject scope,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read();JSONArray result=new JSONArray(),records=state.getJSONArray("commitments");
            for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(matches(identity,record))result.put(scopedView(state,identity,record,now));}
            return result;
        }
    }
    public JSONObject get(JSONObject scope,String id,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read();return scopedView(state,identity,find(state,identity,id),now);}
    }

    /** Changing dueAt explicitly ends a snooze. Expiry never establishes completion. */
    public JSONObject update(JSONObject scope,String id,long revision,JSONObject patch,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        for(Iterator<String> keys=patch.keys();keys.hasNext();){String key=keys.next();
            if(!key.equals("title")&&!key.equals("details")&&!key.equals("dueAt")&&!key.equals("taskId")&&!key.equals("expiresAt"))
                throw new IllegalArgumentException("不支持修改承诺字段："+key);
        }
        if(patch.length()==0)throw new IllegalArgumentException("请指定要修改的承诺字段");
        synchronized(lock){JSONObject state=read(),record=find(state,identity,id);expectRevision(record,revision);requireOpen(record);
            requireOrdinaryCommitment(state,identity,record);
            if(patch.has("title"))record.put("title",requiredText(patch,"title",200));
            if(patch.has("details"))record.put("details",optionalText(patch,"details",4000,""));
            if(patch.has("taskId"))record.put("taskId",optionalText(patch,"taskId",200,""));
            if(patch.has("dueAt"))record.put("dueAt",time(patch,"dueAt")).put("snoozedUntil",JSONObject.NULL).put("status","pending");
            Object expires=patch.has("expiresAt")?expiry(patch,record.getLong("dueAt")):expiry(record,record.getLong("dueAt"));
            record.put("expiresAt",expires);
            if(expires instanceof Number&&!record.isNull("snoozedUntil")&&record.getLong("snoozedUntil")>=((Number)expires).longValue())throw new IllegalArgumentException("稍后时间不能超出承诺有效窗口");
            advance(record,now);write(state);return view(record,now);
        }
    }
    public JSONObject snooze(JSONObject scope,String id,long revision,long until,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        if(until<=now||until>MAX_TIME)throw new IllegalArgumentException("稍后跟进时间必须在未来");
        synchronized(lock){JSONObject state=read(),record=find(state,identity,id);expectRevision(record,revision);requireOpen(record);
            requireOrdinaryCommitment(state,identity,record);
            if(expired(record,until))throw new IllegalArgumentException("稍后时间不能超出承诺有效窗口");
            record.put("status","snoozed").put("snoozedUntil",until);advance(record,now);write(state);return view(record,now);
        }
    }
    public JSONObject cancel(JSONObject scope,String id,long revision,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read(),record=find(state,identity,id);expectRevision(record,revision);
            requireOrdinaryCommitment(state,identity,record);
            if("cancelled".equals(record.getString("status")))return view(record,now);
            requireOpen(record);record.put("status","cancelled").put("snoozedUntil",JSONObject.NULL);
            advance(record,now);write(state);return view(record,now);
        }
    }
    public JSONObject complete(JSONObject scope,String id,long revision,JSONObject evidenceRef,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read(),record=find(state,identity,id);expectRevision(record,revision);requireOpen(record);
            requireOrdinaryCommitment(state,identity,record);
            JSONObject evidence=verifiedEvidence(identity,record,evidenceRef,now);
            finish(record,evidence,now);write(state);return view(record,now);
        }
    }

    /**
     * Native host hook, intentionally not exposed as a feature command. Only an authoritative
     * completed task with verified result evidence may complete its associated commitment.
     * A replay returns the existing result without revising it; cancelled/completed records
     * never reopen. Failed/cancelled/running tasks do not establish this promise's completion.
     */
    public JSONObject onTaskCompleted(JSONObject scope,String id,long revision,String eventId,String taskId,
                                      JSONObject evidenceRef,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        String event=identifier(eventId,"任务事件"),task=identifier(taskId,"任务");
        referenceScope(identity,evidenceRef);
        String evidenceId=requiredText(evidenceRef,"id",200);
        if(!"task_result".equals(evidenceRef.optString("kind")))throw new IllegalArgumentException("任务完成事件必须引用任务结果凭证");
        synchronized(lock){JSONObject state=read(),record=find(state,identity,id);
            if(!task.equals(record.getString("taskId")))throw new IllegalArgumentException("任务不属于此承诺");
            JSONObject previous=record.optJSONObject("completionEvent");
            if(previous!=null&&event.equals(previous.getString("id"))){
                if(!task.equals(previous.getString("taskId"))||!evidenceId.equals(previous.getString("evidenceId")))
                    throw new IllegalStateException("同一任务事件不能更换凭证");
                return eventResult(record,now,false,"duplicate");
            }
            if(terminal(record))return eventResult(record,now,false,"terminal");
            expectRevision(record,revision);
            JSONObject evidence=verifiedEvidence(identity,record,evidenceRef,now);
            finish(record,evidence,now);
            record.put("completionEvent",new JSONObject().put("id",event).put("taskId",task).put("evidenceId",evidenceId));
            write(state);return eventResult(record,now,true,"completed");
        }
    }

    /**
     * Read-only candidates. This does not claim, dispatch, notify or run anything. A snoozed
     * record becomes eligible at snoozedUntil without rewriting history during a read.
     * The host must deduplicate candidateKey and re-read the current revision before acting.
     */
    public JSONObject due(JSONObject scope,long now,int limit)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        if(limit<1||limit>100)throw new IllegalArgumentException("跟进候选数量应为1至100");
        synchronized(lock){JSONObject state=read();JSONArray records=state.getJSONArray("commitments");List<JSONObject> candidates=new ArrayList<>();long next=-1;
            for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);
                if(!matches(identity,record)||terminal(record)||expired(record,now)||dependency(state,identity,record,now)!=null)continue;
                if(record.has("planId")&&findPlan(state,identity,record.getString("planId")).optJSONObject("execution")!=null)continue;
                long at=followUpAt(record);if(next<0||at<next)next=at;
                if(at<=now)candidates.add(view(record,now));
            }
            candidates.sort(Comparator.comparingLong((JSONObject record)->record.optLong("followUpAt")).thenComparing(record->record.optString("id")));
            JSONArray result=new JSONArray();for(int i=0;i<Math.min(limit,candidates.size());i++)result.put(candidates.get(i));
            return new JSONObject().put("scope",identity).put("commitments",result).put("nextFollowUpAt",next<0?JSONObject.NULL:next)
                    .put("hasMore",candidates.size()>limit).put("delivery","not_dispatched")
                    .put("referenceOnly",true).put("requiresHostAdmission",true).put("executionAuthorized",false);
        }
    }

    /** An explicit ordered promise, backed by normal commitments and their existing evidence. */
    public JSONObject createPlan(JSONObject scope,JSONObject input,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        if(!"explicit_user".equals(input.optString("kind")))throw new IllegalArgumentException("计划必须来自明确用户委托");
        rejectFields(input,"id","revision","status","completionEvidence","execution","approval");
        JSONObject source=source(identity,input.getJSONObject("sourceRef"));String title=requiredText(input,"title",200);
        JSONArray steps=input.getJSONArray("steps");if(steps.length()<1||steps.length()>50)throw new IllegalArgumentException("计划应包含1至50步");
        synchronized(lock){JSONObject state=read();JSONArray records=state.getJSONArray("commitments"),plans=array(state,"plans");
            if(records.length()+steps.length()>1000||plans.length()>=200)throw new IllegalStateException("计划或承诺容量已满");
            String planId=UUID.randomUUID().toString();JSONArray ids=new JSONArray();long previousDue=0;
            for(int i=0;i<steps.length();i++){
                JSONObject step=steps.getJSONObject(i);
                for(Iterator<String> keys=step.keys();keys.hasNext();){String key=keys.next();if(!key.matches("title|details|dueAt|expiresAt|taskId|goal|executor|verification|dependsOn|remoteWait|businessWait"))throw new IllegalArgumentException("不支持的计划步骤字段："+key);}
                long due=time(step,"dueAt");if(due<previousDue)throw new IllegalArgumentException("计划步骤的跟进时间必须按顺序排列");previousDue=due;
                String id=UUID.randomUUID().toString();JSONArray dependencies=PlanExecutionPolicy.dependencies(step,ids);ids.put(id);
                JSONObject record=copy(identity).put("id",id).put("revision",1L).put("kind","explicit_user")
                        .put("title",requiredText(step,"title",200)).put("details",optionalText(step,"details",4000,""))
                        .put("goal",optionalText(step,"goal",4000,"")).put("dependencies",dependencies)
                        .put("executor",optionalText(step,"executor",20,"phone")).put("verification",PlanArtifactVerifier.standard(step.optJSONObject("verification")))
                        .put("sourceRef",copy(source)).put("taskId",optionalText(step,"taskId",200,""))
                        .put("status","pending").put("dueAt",due).put("expiresAt",expiry(step,due))
                        .put("snoozedUntil",JSONObject.NULL).put("completionEvidence",JSONObject.NULL).put("completionEvent",JSONObject.NULL)
                        .put("planId",planId).put("planIndex",i).put("createdAt",now).put("updatedAt",now);
                if(step.has("remoteWait")&&!step.isNull("remoteWait"))record.put("remoteWait",PlanRemoteWait.standard(step.getJSONObject("remoteWait")));
                if(step.has("businessWait")&&!step.isNull("businessWait")){JSONObject wait=PlanBusinessWait.standard(step.getJSONObject("businessWait"));record.put("businessWait",wait).put("businessWaitCursor",PlanBusinessWait.anchorId(wait));}
                records.put(record);
            }
            JSONObject plan=copy(identity).put("id",planId).put("revision",1L).put("title",title).put("sourceRef",source)
                    .put("goal",optionalText(input,"goal",4000,""))
                    .put("stepIds",ids).put("createdAt",now).put("updatedAt",now);plans.put(plan);
            write(state);return planView(state,identity,plan,now);
        }
    }
    public JSONObject getPlan(JSONObject scope,String id,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read();return planView(state,identity,findPlan(state,identity,id),now);}
    }
    public JSONArray listPlans(JSONObject scope,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read();JSONArray values=array(state,"plans"),result=new JSONArray();
            for(int i=0;i<values.length();i++){JSONObject plan=values.getJSONObject(i);if(matches(identity,plan))result.put(planView(state,identity,plan,now));}return result;
        }
    }
    /** Atomic cancellation of every open step. Existing completion evidence is retained. */
    public JSONObject cancelPlan(JSONObject scope,String id,long revision,long now)throws Exception {
        return cancelPlan(scope,id,revision,now,false);
    }
    /** Native owner control; the generic feature command cannot cancel an execution plan. */
    public JSONObject cancelPlanExecution(JSONObject scope,String id,long revision,long now)throws Exception {
        privatePlanScope(scope(scope));return cancelPlan(scope,id,revision,now,true);
    }
    private JSONObject cancelPlan(JSONObject scope,String id,long revision,long now,boolean executionControl)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,id);
            if(plan.optJSONObject("execution")!=null&&!executionControl)throw new IllegalStateException("执行计划取消须由原生机主控制入口确认");
            if(executionControl)execution(plan);
            if(planView(state,identity,plan,now).getLong("revision")!=revision)throw new IllegalStateException("计划或步骤已修改，请刷新后重试");
            JSONArray ids=plan.getJSONArray("stepIds");boolean changed=false;
            for(int i=0;i<ids.length();i++){JSONObject record=find(state,identity,ids.getString(i));if(!terminal(record)){
                record.put("status","cancelled").put("snoozedUntil",JSONObject.NULL);advance(record,now);changed=true;
            }}
            if(plan.optJSONObject("execution")!=null){plan.getJSONObject("execution").put("state","cancelled");changed=true;}
            if(changed){plan.put("revision",Math.addExact(plan.getLong("revision"),1)).put("updatedAt",now);write(state);}
            return planView(state,identity,plan,now);
        }
    }
    /** Read-only product state; availability of a due item never means a worker is alive. */
    public JSONObject proactivityState(JSONObject scope,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read();JSONArray records=state.getJSONArray("commitments"),reasons=new JSONArray();
            int open=0,due=0,expired=0,blocked=0,unknown=0;long next=-1;
            JSONArray receipts=array(state,"followUpReceipts");for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);if(matches(identity,receipt)&&"delivery_unknown".equals(receipt.optString("status")))unknown++;}
            for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(!matches(identity,record)||terminal(record))continue;
                if(expired(record,now)){expired++;continue;}open++;
                String dependency=dependency(state,identity,record,now);if(dependency!=null){blocked++;continue;}
                JSONObject decision=evaluate(state,identity,record.getString("id"),record.getLong("revision"),candidateKey(record),now,null);
                if(decision.getBoolean("eligible"))due++;
                if(!decision.isNull("nextEligibleAt")){long at=decision.getLong("nextEligibleAt");next=next<0?at:Math.min(next,at);}
                if(reasons.length()<100)reasons.put(new JSONObject().put("commitmentId",record.getString("id")).put("reason",decision.getString("reason")));
            }
            return copy(identity).put("open",open).put("eligibleNow",due).put("expired",expired).put("blockedSteps",blocked).put("unknownDeliveries",unknown)
                    .put("nextEligibleAt",next<0?JSONObject.NULL:next).put("decisions",reasons).put("decisionsTruncated",open-blocked>100)
                    .put("policy",copy(policy(state,identity))).put("backgroundExecution","host_os_best_effort")
                    .put("executionAuthorized",false).put("referenceOnly",true);
        }
    }
    private static JSONObject findPlan(JSONObject state,JSONObject identity,String id)throws Exception {
        identifier(id,"计划");JSONArray plans=array(state,"plans");
        for(int i=0;i<plans.length();i++){JSONObject plan=plans.getJSONObject(i);if(id.equals(plan.optString("id"))&&matches(identity,plan))return plan;}
        throw new IllegalArgumentException("此空间中不存在该计划");
    }
    private static JSONObject planView(JSONObject state,JSONObject identity,JSONObject plan,long now)throws Exception {
        JSONObject result=copy(plan);JSONArray ids=plan.getJSONArray("stepIds"),steps=new JSONArray();long revision=plan.getLong("revision");
        int completed=0,cancelled=0,expired=0,failed=0,skipped=0;JSONObject next=null;
        for(int i=0;i<ids.length();i++){
            JSONObject record=find(state,identity,ids.getString(i)),step=scopedView(state,identity,record,now);steps.put(step);
            revision=Math.addExact(revision,record.getLong("revision")-1);
            if("completed".equals(record.getString("status")))completed++;
            else if("cancelled".equals(record.getString("status")))cancelled++;
            else if("failed".equals(record.getString("status")))failed++;
            else if("skipped".equals(record.getString("status")))skipped++;
            else if(expired(record,now))expired++;
            if(next==null&&!terminal(record)&&!expired(record,now)&&dependency(state,identity,record,now)==null)next=step;
        }
        String status=completed==ids.length()?"completed":cancelled==ids.length()-completed?"cancelled":cancelled>0||expired>0?"blocked":"pending";
        JSONObject execution=plan.optJSONObject("execution");if(execution!=null){
            boolean approved=executionApproved(state,identity,plan);String control=execution.getString("state");
            if(control.equals("cancelled"))status="cancelled";
            else if(completed+failed+cancelled+skipped==ids.length())status=failed>0?"finished_with_failures":cancelled>0?"cancelled":"completed";
            else if(control.equals("paused"))status="paused";
            else if(!approved)status="review_required";
            else if(!execution.getBoolean("started"))status="waiting_for_owner_start";
            else if(planInFlight(state,identity,plan))status="waiting_for_original_task";
            else status=next==null?"blocked":next.getLong("dueAt")>now?"scheduled":"ready_for_host_check";
            result.put("approvalValid",approved).put("failedSteps",failed).put("skippedSteps",skipped).put("taskControlApplied",false)
                    .put("admissionState",control).put("originalTasksUnsettled",planInFlight(state,identity,plan))
                    .put("taskControls",taskControls(state,identity,plan,control.equals("paused")?"pause":control.equals("cancelled")?"cancel":"inspect"))
                    .put("taskStateAuthority","TaskStore").put("workerLiveness","not_established_by_plan_state");
        }
        return result.put("revision",revision).put("steps",steps).put("state",status).put("completedSteps",completed).put("totalSteps",ids.length())
                .put("nextStep",next==null?JSONObject.NULL:next).put("executionAuthorized",false).put("referenceOnly",true);
    }
    private static JSONObject scopedView(JSONObject state,JSONObject identity,JSONObject record,long now)throws Exception {
        JSONObject value=view(record,now);String blocked=terminal(record)?null:dependency(state,identity,record,now);
        if(record.has("planId")&&findPlan(state,identity,record.getString("planId")).optJSONObject("execution")!=null)value.put("followUpDue",false).put("followUpAt",JSONObject.NULL).put("followUpDriver","execution_plan_native_host");
        if(blocked!=null)value.put("blockedBy",blocked).put("followUpDue",false).put("followUpAt",JSONObject.NULL);
        return value;
    }
    private static String dependency(JSONObject state,JSONObject identity,JSONObject record,long now)throws Exception {
        if(!record.has("planId"))return null;
        JSONObject plan=findPlan(state,identity,record.getString("planId"));JSONArray ids=plan.getJSONArray("stepIds");
        if(plan.optJSONObject("execution")!=null){
            JSONObject execution=plan.getJSONObject("execution");if(!execution.optString("state").equals("active"))return "plan_"+execution.optString("state");
            if(!executionApproved(state,identity,plan))return "plan_review_required";
            return taskDependency(state,identity,record,now);
        }
        for(int i=0;i<ids.length();i++){
            String id=ids.getString(i);if(id.equals(record.getString("id")))return null;
            JSONObject previous=find(state,identity,id);String status=previous.getString("status");
            if("cancelled".equals(status))return "dependency_cancelled";
            if(!"completed".equals(status)){if(expired(previous,now))return "dependency_expired";return "dependency_pending";}
        }
        return "plan_membership_invalid";
    }

    /** Native owner review only. This records a bounded plan, never phone/tool authorization. */
    public JSONObject approvePlanExecution(JSONObject scope,String id,long revision,JSONObject settings,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);JSONObject approved=PlanExecutionPolicy.settings(settings);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,id);expectPlanRevision(state,identity,plan,revision);
            JSONArray steps=planSteps(state,identity,plan);String signature=PlanExecutionPolicy.signature(plan,steps);JSONObject previous=plan.optJSONObject("execution");
            for(int i=0;i<steps.length();i++)if(steps.getJSONObject(i).optJSONObject("businessWait")!=null&&!approved.optBoolean("businessEventConsent"))throw new SecurityException("须由主人明确确认原认证来源可触发此固定目标");
            if(previous!=null&&planInFlight(state,identity,plan)&&!"paused".equals(previous.optString("state")))throw new IllegalStateException("原任务仍有关联，请先暂停后修改执行范围");
            boolean open=false;for(int i=0;i<steps.length();i++)if(!terminal(steps.getJSONObject(i)))open=true;if(!open)throw new IllegalStateException("已结束的计划没有可批准的步骤");
            long used=previous==null?0:previous.optLong("admissions"),sequence=previous==null?1:Math.addExact(previous.getLong("approvalSequence"),1);
            JSONObject policy=approved.getJSONObject("policy");if(!policy.isNull("expiresAt")&&policy.getLong("expiresAt")<=now)throw new IllegalArgumentException("计划批准窗口必须在未来");
            JSONObject execution=approved.put("approvalKey",signature).put("approvalSequence",sequence).put("approvedAt",now).put("admissions",used)
                    .put("state",previous!=null&&"paused".equals(previous.optString("state"))?"paused":"active")
                    .put("started",approved.getString("mode").equals("automatic")||previous!=null&&previous.optBoolean("started"));
            if(previous!=null&&"cancelled".equals(previous.optString("state")))throw new IllegalStateException("已结束的计划不能重新授权");
            if(previous!=null&&planInFlight(state,identity,plan)&&!previous.optString("phoneMode","background").equals(approved.getString("phoneMode")))throw new IllegalStateException("原任务仍有关联，不能改换手机执行位置");
            plan.put("execution",execution);state.put("version",2);touchPlan(plan,now);write(state);return planView(state,identity,plan,now);
        }
    }
    public JSONObject startPlanExecution(JSONObject scope,String id,long revision,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,id);expectPlanRevision(state,identity,plan,revision);JSONObject execution=execution(plan);
            if(!executionApproved(state,identity,plan)||!"active".equals(execution.getString("state")))throw new IllegalStateException("计划须先由机主审阅并恢复");
            if(!execution.getBoolean("started")){execution.put("started",true);touchPlan(plan,now);write(state);}return planView(state,identity,plan,now);
        }
    }
    /** Pausing admission is immediate; stopping an already-running Task remains a host action. */
    public JSONObject setPlanPaused(JSONObject scope,String id,long revision,boolean paused,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,id);expectPlanRevision(state,identity,plan,revision);JSONObject execution=execution(plan);
            if(execution.getString("state").equals("cancelled"))throw new IllegalStateException("已结束的计划不能恢复");
            if(!paused&&!executionApproved(state,identity,plan))throw new IllegalStateException("计划已修改，须重新审阅后恢复");
            execution.put("state",paused?"paused":"active");touchPlan(plan,now);write(state);
            return planView(state,identity,plan,now).put("taskControls",taskControls(state,identity,plan,paused?"pause":"resume")).put("taskControlApplied",false);
        }
    }
    /** Future unbound work only. Original in-flight task feedback remains in TaskStore. */
    public JSONObject editPlanStep(JSONObject scope,String planId,long planRevision,String stepId,long stepRevision,JSONObject patch,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId);expectPlanRevision(state,identity,plan,planRevision);JSONObject execution=execution(plan);
            if(!"paused".equals(execution.getString("state")))throw new IllegalStateException("修改计划前须先暂停");
            JSONObject step=planStep(state,identity,plan,stepId);expectRevision(step,stepRevision);requireOpen(step);
            if(!step.optString("taskId").isEmpty()||step.has("taskAdmission"))throw new IllegalStateException("原任务或未知准入仍有关联，请在原任务反馈；不能借编辑重派");
            for(Iterator<String> keys=patch.keys();keys.hasNext();){String key=keys.next();if(!key.matches("title|details|goal|executor|verification|dueAt|expiresAt|dependsOn|remoteWait|businessWait"))throw new IllegalArgumentException("不支持的计划步骤修改："+key);}
            if(patch.length()==0)throw new IllegalArgumentException("请指定计划修改");
            if(patch.has("title"))step.put("title",requiredText(patch,"title",200));if(patch.has("details"))step.put("details",optionalText(patch,"details",4000,""));
            if(patch.has("goal"))step.put("goal",PlanExecutionPolicy.text(patch,"goal",4000));
            if(patch.has("executor"))step.put("executor",PlanExecutionPolicy.text(patch,"executor",20));if(patch.has("verification"))step.put("verification",PlanArtifactVerifier.standard(patch.getJSONObject("verification")));
            if(patch.has("businessWait")){
                JSONObject previous=step.optJSONObject("businessWait"),next=patch.isNull("businessWait")?null:PlanBusinessWait.standard(patch.getJSONObject("businessWait"));
                if(previous!=null&&!PlanBusinessWait.same(previous,next)){JSONArray history=step.optJSONArray("priorBusinessWaitRefs");if(history==null)history=new JSONArray();if(history.length()>=100)throw new IllegalStateException("先前入站等待来源已满，不能丢弃旧关联");history.put(new JSONObject().put("reference",copy(previous)).put("cursor",step.optString("businessWaitCursor")).put("changedAt",now));step.put("priorBusinessWaitRefs",history);}
                if(next==null){step.remove("businessWait");step.remove("businessWaitCursor");}else step.put("businessWait",next).put("businessWaitCursor",PlanBusinessWait.anchorId(next));step.remove("businessWaitObservation");
            }
            if(patch.has("remoteWait")){
                JSONObject previous=step.optJSONObject("remoteWait"),next=patch.isNull("remoteWait")?null:PlanRemoteWait.standard(patch.getJSONObject("remoteWait"));
                if(previous!=null&&!PlanRemoteWait.sameIdentity(previous,next)){JSONArray history=step.optJSONArray("priorRemoteWaitRefs");if(history==null)history=new JSONArray();if(history.length()>=100)throw new IllegalStateException("先前电脑等待身份已满，不能丢弃旧关联");history.put(new JSONObject().put("reference",copy(previous)).put("changedAt",now).put("stepRevision",step.getLong("revision")));step.put("priorRemoteWaitRefs",history);}
                if(next==null)step.remove("remoteWait");else step.put("remoteWait",next);
            }
            if(patch.has("dueAt"))step.put("dueAt",time(patch,"dueAt")).put("snoozedUntil",JSONObject.NULL);
            if(patch.has("expiresAt"))step.put("expiresAt",expiry(patch,step.getLong("dueAt")));else expiry(step,step.getLong("dueAt"));
            if(patch.has("dependsOn")){JSONArray previous=new JSONArray(),ids=plan.getJSONArray("stepIds");for(int i=0;i<ids.length()&&!ids.getString(i).equals(stepId);i++)previous.put(ids.getString(i));step.put("dependencies",PlanExecutionPolicy.dependencies(patch,previous));}
            advance(step,now);execution.put("approvalKey",JSONObject.NULL);touchPlan(plan,now);write(state);return planView(state,identity,plan,now);
        }
    }
    /** Explicit retry of a proven closed task only. Never replaces an unknown admission/effect. */
    public JSONObject retryPlanStep(JSONObject scope,String planId,long planRevision,String stepId,long stepRevision,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId);expectPlanRevision(state,identity,plan,planRevision);JSONObject execution=execution(plan),step=planStep(state,identity,plan,stepId);expectRevision(step,stepRevision);
            JSONObject observed=step.optJSONObject("taskObservation");
            if(!"paused".equals(execution.getString("state"))||!step.getString("status").matches("failed|cancelled")||observed==null||!observed.optBoolean("terminalVerified"))throw new IllegalStateException("须暂停并核实原任务终态和无未知效果，才能重新审阅重试");
            JSONObject original=resolvePlanTask(identity,step);if(original==null||!planTaskMatches(identity,step,step.optJSONObject("taskAdmission"),original)||!step.getString("status").equals(original.optString("status"))||!Boolean.TRUE.equals(original.opt("terminalVerified")))throw new IllegalStateException("原任务终态目前无法核实，不能更换准入身份");
            JSONArray allSteps=planSteps(state,identity,plan);for(int i=0;i<allSteps.length();i++){JSONObject other=allSteps.getJSONObject(i);if(!other.getString("id").equals(stepId)&&hasUnsettledOriginalTask(other))throw new IllegalStateException("其他原任务仍未核实结束，不能重写依赖历史");}
            JSONArray history=step.optJSONArray("priorTaskRefs");if(history==null)history=new JSONArray();if(history.length()>=100)throw new IllegalStateException("重试历史已满，不能丢弃旧任务关联");history.put(copy(observed));step.put("priorTaskRefs",history);
            JSONObject oldAdmission=step.optJSONObject("taskAdmission");if(oldAdmission!=null&&oldAdmission.optJSONObject("businessWaitEvidence")!=null){JSONArray businessHistory=step.optJSONArray("priorBusinessConsumptions");if(businessHistory==null)businessHistory=new JSONArray();if(businessHistory.length()>=100)throw new IllegalStateException("原事件消费历史已满，不能丢旧事实");JSONObject consumed=oldAdmission.getJSONObject("businessWaitEvidence");businessHistory.put(copy(consumed));step.put("priorBusinessConsumptions",businessHistory).put("businessWaitCursor",consumed.getString("eventId"));}
            step.remove("taskAdmission");step.remove("taskObservation");step.put("taskId","").put("status","pending").put("completionEvidence",JSONObject.NULL).put("completionEvent",JSONObject.NULL);advance(step,now);
            for(int i=0;i<plan.getJSONArray("stepIds").length();i++){JSONObject other=find(state,identity,plan.getJSONArray("stepIds").getString(i));if("skipped".equals(other.optString("status"))&&!other.has("taskAdmission")&&other.optString("taskId").isEmpty()){other.put("status","pending");other.remove("skipReason");advance(other,now);}}
            execution.put("approvalKey",JSONObject.NULL);touchPlan(plan,now);write(state);return planView(state,identity,plan,now);
        }
    }

    /** Read-only candidates for the existing alarm host. One unresolved task per plan is retained. */
    public JSONObject readyPlanTasks(JSONObject scope,long now,int limit)throws Exception {
        return readyPlanTasks(scope,null,now,limit);
    }
    /** A selected plan must be filtered before applying the bounded candidate window. */
    public JSONObject readyPlanTasks(JSONObject scope,String planId,long now,int limit)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);int maximum=planId==null?20:50;if(limit<1||limit>maximum)throw new IllegalArgumentException("计划候选数量应为1至"+maximum);
        synchronized(lock){JSONObject state=read();JSONArray result=new JSONArray(),plans=planId==null?array(state,"plans"):new JSONArray().put(findPlan(state,identity,planId));
            for(int i=0;i<plans.length()&&result.length()<limit;i++){JSONObject plan=plans.getJSONObject(i);if(!matches(identity,plan)||plan.optJSONObject("execution")==null||planInFlight(state,identity,plan))continue;
                JSONArray ids=plan.getJSONArray("stepIds");for(int j=0;j<ids.length();j++){JSONObject step=find(state,identity,ids.getString(j)),decision=planTaskDecision(state,identity,plan,step,now);if(decision.optBoolean("eligible")){result.put(decision);if(planId==null||result.length()>=limit)break;}}
            }
            return new JSONObject().put("candidates",result).put("executionAuthorized",false).put("requiresHostAdmission",true);
        }
    }
    public JSONObject inspectPlanTask(JSONObject scope,String planId,String stepId,long now)throws Exception{
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId);return planTaskDecision(state,identity,plan,planStep(state,identity,plan,stepId),now);}
    }
    /** Existing task events only re-arm the existing alarm; no reverse index or second worker. */
    public boolean waitsForRemoteTask(JSONObject scope,String taskId)throws Exception{
        JSONObject identity=scope(scope);privatePlanScope(identity);
        synchronized(lock){JSONObject state=read();JSONArray plans=array(state,"plans");for(int i=0;i<plans.length();i++){
            JSONObject plan=plans.getJSONObject(i);if(!matches(identity,plan)||plan.optJSONObject("execution")==null||"cancelled".equals(plan.getJSONObject("execution").optString("state")))continue;
            JSONArray steps=planSteps(state,identity,plan);for(int j=0;j<steps.length();j++){JSONObject step=steps.getJSONObject(j),wait=step.optJSONObject("remoteWait");if(!terminal(step)&&wait!=null&&taskId.equals(wait.optString("taskId")))return true;}
        }return false;}
    }
    public boolean waitsForBusinessEvents(JSONObject scope)throws Exception{JSONObject identity=scope(scope);privatePlanScope(identity);synchronized(lock){JSONObject state=read();JSONArray plans=array(state,"plans");for(int i=0;i<plans.length();i++){JSONObject plan=plans.getJSONObject(i);if(!matches(identity,plan)||plan.optJSONObject("execution")==null||!"active".equals(plan.getJSONObject("execution").optString("state")))continue;JSONArray steps=planSteps(state,identity,plan);for(int j=0;j<steps.length();j++)if(!terminal(steps.getJSONObject(j))&&steps.getJSONObject(j).optJSONObject("businessWait")!=null)return true;}return false;}}
    /** Durable one-shot admission intent, not a task or permission to start a Runner. */
    public JSONObject preparePlanTask(JSONObject scope,String planId,long planRevision,String stepId,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId);expectPlanRevision(state,identity,plan,planRevision);JSONObject step=planStep(state,identity,plan,stepId);return preparePlanTaskLocked(state,identity,plan,step,now);}
    }
    private JSONObject preparePlanTaskLocked(JSONObject state,JSONObject identity,JSONObject plan,JSONObject step,long now)throws Exception {
        if(step.has("taskAdmission"))return taskRequestView(plan,step).put("lookupOnly",true);
        if(planInFlight(state,identity,plan))throw new IllegalStateException("计划仍有关联任务或未知准入，请核对原任务");
        JSONObject decision=planTaskDecision(state,identity,plan,step,now);if(!decision.getBoolean("eligible"))return decision;
        JSONObject execution=execution(plan),intent=new JSONObject().put("occurrenceId",UUID.randomUUID().toString()).put("ruleId",plan.getString("id"))
                .put("ruleRevision",execution.getLong("approvalSequence")).put("commitmentRevision",step.getLong("revision")).put("goal",step.getString("goal"))
                .put("executionMode",step.optString("executor","phone").equals("workspace")?"capabilities":execution.optString("phoneMode","background"))
                .put("validUntil",Math.min(expiryLimit(step),execution.getJSONObject("policy").isNull("expiresAt")?MAX_TIME:execution.getJSONObject("policy").getLong("expiresAt")))
                .put("createdAt",now).put("admissionNumber",Math.addExact(execution.getLong("admissions"),1)).put("state","task_creation_unknown");step.put("taskAdmission",intent);
        if(decision.optJSONObject("remoteWaitStatus")!=null)intent.put("remoteWaitEvidence",copy(decision.getJSONObject("remoteWaitStatus").getJSONObject("evidence")));
        if(decision.optJSONObject("businessWaitStatus")!=null){JSONObject proof=decision.getJSONObject("businessWaitStatus").getJSONObject("evidence");intent.put("businessWaitEvidence",copy(proof));step.put("businessWaitConsumedEventId",proof.getString("eventId"));}
        execution.put("admissions",Math.addExact(execution.getLong("admissions"),1));touchPlan(plan,now);write(state);return taskRequestView(plan,step).put("lookupOnly",false).put("eligible",true);
    }
    /** Native owner selected an ALREADY approved exact step. This changes no grant or budget. */
    public JSONObject bindSemanticPlanGate(JSONObject scope,String planId,long shownRevision,String stepId,JSONObject rule,long now)throws Exception{
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId),step=planStep(state,identity,plan,stepId),execution=execution(plan),prior=step.optJSONObject("semanticConditionGate");
            if(!executionApproved(state,identity,plan)||!execution.getString("state").equals("active")||!execution.optBoolean("modelObservationConsent")||terminal(step)||step.has("taskAdmission")||!step.optString("taskId").isEmpty())throw new SecurityException("条件只能绑定当前已批且尚未派发的原计划步骤");
            JSONObject definition=rule.getJSONObject("semanticCondition");
            if(prior!=null){if(prior.getLong("shownPlanRevision")!=shownRevision||!rule.getString("id").equals(prior.optString("ruleId"))||rule.getLong("revision")!=prior.optLong("ruleRevision")||!execution.getString("approvalKey").equals(prior.optString("approvalKey"))||execution.getLong("approvalSequence")!=prior.optLong("approvalSequence"))throw new SecurityException("原步骤已绑定不同条件或新审批，不能覆盖");}
            else{expectPlanRevision(state,identity,plan,shownRevision);prior=new JSONObject().put("ruleId",rule.getString("id")).put("ruleRevision",rule.getLong("revision")).put("sourceKey",definition.getString("sourceKey")).put("sourceRevision",definition.getLong("sourceRevision")).put("approvalKey",execution.getString("approvalKey")).put("approvalSequence",execution.getLong("approvalSequence")).put("stepRevision",step.getLong("revision")).put("shownPlanRevision",shownRevision).put("status","waiting");step.put("semanticConditionGate",prior);touchPlan(plan,now);write(state);}
            return new JSONObject(prior.toString()).put("planId",planId).put("stepId",stepId);
        }
    }
    /** Native rising edge releases the gate AND persists the original admission in one Book write. */
    public JSONObject prepareSemanticPlanTask(JSONObject scope,JSONObject occurrence,JSONObject binding,long now)throws Exception{
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,binding.getString("planId")),step=planStep(state,identity,plan,binding.getString("stepId")),gate=step.optJSONObject("semanticConditionGate"),execution=execution(plan);
            if(gate==null||!occurrence.getString("ruleId").equals(gate.optString("ruleId"))||occurrence.getLong("revision")!=gate.optLong("ruleRevision")||!execution.getString("approvalKey").equals(gate.optString("approvalKey"))||execution.getLong("approvalSequence")!=gate.optLong("approvalSequence")||step.getLong("revision")!=gate.optLong("stepRevision")||!occurrence.optString("status").matches("condition_action_pending|condition_action_unknown|condition_action_registered"))throw new SecurityException("原条件步骤或批准已变化，未授新权限");
            String selected=gate.optString("conditionOccurrenceId");if(!selected.isEmpty()&&!selected.equals(occurrence.getString("id")))throw new SecurityException("原计划步骤不能借另一次边沿重复执行");
            if(step.has("taskAdmission")){if(selected.isEmpty())throw new SecurityException("原计划准入没有本条件边沿");return taskRequestView(plan,step).put("lookupOnly",true);}
            if(!"condition_action_pending".equals(occurrence.optString("status")))return new JSONObject().put("eligible",false).put("reason","condition_unknown_query_only");
            gate.put("status","released").put("conditionOccurrenceId",occurrence.getString("id")).put("conditionTaskId",occurrence.getString("conditionTaskId"));
            return preparePlanTaskLocked(state,identity,plan,step,now);
        }
    }
    /** Recovery is a query of that same original admission; it never creates a replacement. */
    public JSONObject semanticPlanTaskRequest(JSONObject scope,JSONObject occurrence,JSONObject binding,long now)throws Exception{
        JSONObject identity=scope(scope);privatePlanScope(identity);synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,binding.getString("planId")),step=planStep(state,identity,plan,binding.getString("stepId")),gate=step.optJSONObject("semanticConditionGate");if(gate==null||!occurrence.getString("id").equals(gate.optString("conditionOccurrenceId"))||!step.has("taskAdmission"))return null;return taskRequestView(plan,step).put("lookupOnly",true);}
    }
    /** Final native recheck under the host's existing admission lock, before create/start/resume. */
    public JSONObject planTaskRequest(JSONObject scope,String planId,String stepId,String occurrenceId,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId),step=planStep(state,identity,plan,stepId),intent=step.getJSONObject("taskAdmission");
            if(!occurrenceId.equals(intent.getString("occurrenceId"))||step.getLong("revision")!=intent.getLong("commitmentRevision")||!step.getString("goal").equals(intent.getString("goal")))throw new IllegalStateException("计划准入身份已变化，不能重派");
            JSONObject decision=planTaskDecision(state,identity,plan,step,now);String reason=decision.getString("reason");
            if(!decision.getBoolean("eligible")&&!reason.equals("admission_unknown")&&!reason.equals("bound_task"))throw new IllegalStateException("计划当前未获准执行："+reason);
            return taskRequestView(plan,step).put("lookupOnly",true);
        }
    }
    /** Query the existing original-task resolver; never accepts caller-asserted task state. */
    public JSONObject refreshPlanTasks(JSONObject scope,String planId,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);JSONArray lookups;
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId);execution(plan);lookups=copyArray(planSteps(state,identity,plan));}
        JSONArray business=new JSONArray();for(int i=0;i<lookups.length();i++){JSONObject step=lookups.getJSONObject(i);if(!terminal(step)&&step.optJSONObject("businessWait")!=null&&step.optJSONObject("taskAdmission")==null&&step.optString("taskId").isEmpty())business.put(new JSONObject().put("stepId",step.getString("id")).put("revision",step.getLong("revision")).put("reference",copy(step.getJSONObject("businessWait"))).put("cursor",step.optString("businessWaitCursor")).put("observation",businessWaitStatus(identity,step)));}
        JSONArray observations=new JSONArray();for(int i=0;i<lookups.length();i++){
            JSONObject step=lookups.getJSONObject(i),intent=step.optJSONObject("taskAdmission");if(intent==null&&step.optString("taskId").isEmpty())continue;
            JSONObject task=null;try{
                if(intent!=null&&goalTasks!=null)task=goalTasks.resolve(intent.getString("occurrenceId"));
                else if(evidenceResolver!=null)task=evidenceResolver.resolve(copy(identity),copy(step),"task_state",step.getString("taskId"));
            }catch(Exception unavailable){}
            observations.put(new JSONObject().put("stepId",step.getString("id")).put("intent",intent==null?JSONObject.NULL:copy(intent)).put("task",task==null?JSONObject.NULL:task));
        }
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId);boolean changed=false;JSONArray needsCompletion=new JSONArray();
            for(int i=0;i<business.length();i++){JSONObject row=business.getJSONObject(i),step=planStep(state,identity,plan,row.getString("stepId")),observed=row.getJSONObject("observation");if(terminal(step)||step.has("taskAdmission")||!step.optString("taskId").isEmpty()||step.getLong("revision")!=row.getLong("revision")||!row.getString("cursor").equals(step.optString("businessWaitCursor"))||!PlanBusinessWait.same(row.getJSONObject("reference"),step.optJSONObject("businessWait")))continue;
                JSONObject previous=step.optJSONObject("businessWaitObservation");if(previous==null||!previous.toString().equals(observed.toString())){step.put("businessWaitObservation",copy(observed));changed=true;}
                // Only a proven page containing NO matching event advances a local scan position.
                // A matching event is consumed once together with the original task-admission intent.
                if(!observed.optBoolean("allowed")&&"business_wait_pending".equals(observed.optString("reason"))&&!observed.optBoolean("gap")&&!observed.optString("nextEventId").isEmpty()&&!step.optString("businessWaitCursor").equals(observed.getString("nextEventId"))){step.put("businessWaitCursor",observed.getString("nextEventId"));changed=true;}
            }
            for(int i=0;i<observations.length();i++){
                JSONObject entry=observations.getJSONObject(i),step=planStep(state,identity,plan,entry.getString("stepId")),intent=step.optJSONObject("taskAdmission"),savedIntent=entry.optJSONObject("intent"),task=entry.optJSONObject("task");
                if(intent!=null&&(savedIntent==null||!intent.getString("occurrenceId").equals(savedIntent.getString("occurrenceId"))))continue;
                if(task==null||!planTaskMatches(identity,step,intent,task))continue;
                String taskId=task.getString("taskId"),status=task.getString("status");if(!step.optString("taskId").isEmpty()&&!step.getString("taskId").equals(taskId))continue;
                if(!taskId.equals(step.optString("taskId")))changed=true;step.put("taskId",taskId);if(intent!=null){if(!"bound".equals(intent.optString("state")))changed=true;intent.put("state","bound");}
                JSONObject observed=new JSONObject().put("taskId",taskId).put("status",status).put("terminalVerified",Boolean.TRUE.equals(task.opt("terminalVerified")))
                        .put("resultVerified",Boolean.TRUE.equals(task.opt("resultVerified"))).put("observedAt",now).put("authority","TaskStore")
                        .put("runId",task.optString("runId",task.optString("taskRunId"))).put("coordinatorEpoch",task.optLong("coordinatorEpoch",task.optLong("taskEpoch")));
                if(intent!=null)observed.put("occurrenceId",intent.getString("occurrenceId"));
                JSONObject previous=step.optJSONObject("taskObservation");boolean same=previous!=null;
                for(String key:new String[]{"taskId","status","terminalVerified","resultVerified","authority","runId","coordinatorEpoch","occurrenceId"})if(!String.valueOf(observed.opt(key)).equals(String.valueOf(previous==null?null:previous.opt(key))))same=false;
                if(!same){step.put("taskObservation",observed);changed=true;}
                if(!terminal(step)&&status.matches("failed|cancelled")&&observed.getBoolean("terminalVerified")){step.put("status",status);advance(step,now);}
                else if(!terminal(step)&&status.equals("completed")&&observed.getBoolean("resultVerified"))needsCompletion.put(new JSONObject().put("taskId",taskId).put("commitmentId",step.getString("id")).put("commitmentRevision",step.getLong("revision")));
            }
            boolean skipped;do{skipped=false;JSONArray ids=plan.getJSONArray("stepIds");for(int i=0;i<ids.length();i++){
                JSONObject step=find(state,identity,ids.getString(i));if(terminal(step)||step.has("taskAdmission")||!step.optString("taskId").isEmpty())continue;
                if("dependency_outcome_mismatch".equals(taskDependency(state,identity,step,now))){step.put("status","skipped").put("skipReason","dependency_outcome_mismatch");advance(step,now);skipped=true;changed=true;}
            }}while(skipped);
            if(changed){touchPlan(plan,now);write(state);}return planView(state,identity,plan,now).put("needsCompletionEvidence",needsCompletion);
        }
    }
    public JSONObject bindPlanTask(JSONObject scope,String planId,String stepId,String occurrenceId,long now)throws Exception {
        refreshPlanTasks(scope,planId,now);JSONObject identity=scope(scope);
        synchronized(lock){JSONObject state=read(),plan=findPlan(state,identity,planId),step=planStep(state,identity,plan,stepId);
            if(!occurrenceId.equals(step.getJSONObject("taskAdmission").getString("occurrenceId"))||step.optString("taskId").isEmpty())throw new IllegalStateException("原任务创建状态尚未核实，只能查询同一发生身份");
            return scopedView(state,identity,step,now).put("requiresHostAdmission",true).put("executionAuthorized",false);
        }
    }
    /** Same existing alarm. Poll only as a fallback when original-task events are unavailable. */
    public long nextPlanTaskAt(JSONObject scope,long now)throws Exception {
        JSONObject identity=scope(scope);privatePlanScope(identity);validNow(now);
        synchronized(lock){JSONObject state=read();long next=-1;JSONArray plans=array(state,"plans");for(int i=0;i<plans.length();i++){
            JSONObject plan=plans.getJSONObject(i);if(!matches(identity,plan)||plan.optJSONObject("execution")==null)continue;
            if(planInFlight(state,identity,plan)){long poll=AutomationConditions.later(now);next=next<0?poll:Math.min(next,poll);continue;}
            JSONArray ids=plan.getJSONArray("stepIds");for(int j=0;j<ids.length();j++){JSONObject decision=planTaskDecision(state,identity,plan,find(state,identity,ids.getString(j)),now);if(!decision.isNull("nextAt")){long at=decision.getLong("nextAt");next=next<0?at:Math.min(next,at);}}
        }return next;}
    }
    private JSONObject planTaskDecision(JSONObject state,JSONObject identity,JSONObject plan,JSONObject step,long now)throws Exception {
        JSONObject result=copy(identity).put("planId",plan.getString("id")).put("planRevision",aggregatePlanRevision(state,identity,plan)).put("stepId",step.getString("id"))
                .put("stepRevision",step.getLong("revision")).put("eligible",false).put("reason","not_approved").put("nextAt",JSONObject.NULL).put("executionAuthorized",false).put("requiresHostAdmission",true);
        JSONObject execution=plan.optJSONObject("execution");if(execution==null||!executionApproved(state,identity,plan))return result;
        if(!execution.getString("state").equals("active"))return result.put("reason","plan_"+execution.getString("state"));
        if(!execution.getBoolean("started"))return result.put("reason","manual_start_required");
        if(!execution.optBoolean("modelObservationConsent"))return result.put("reason","model_consent_required");
        if(step.optJSONObject("businessWait")!=null&&!execution.optBoolean("businessEventConsent"))return result.put("reason","business_source_consent_required");
        JSONObject semantic=step.optJSONObject("semanticConditionGate");if(semantic!=null){if(!execution.getString("approvalKey").equals(semantic.optString("approvalKey"))||execution.getLong("approvalSequence")!=semantic.optLong("approvalSequence")||step.getLong("revision")!=semantic.optLong("stepRevision"))return result.put("reason","semantic_condition_gate_stale");if(!"released".equals(semantic.optString("status")))return result.put("reason","semantic_condition_waiting");}

        if(terminal(step))return result.put("reason",step.getString("status"));if(expired(step,now))return result.put("reason","expired");
        String dependency=taskDependency(state,identity,step,now);if(dependency!=null)return result.put("reason",dependency);
        String liveDependency=liveFailureDependencies(state,identity,step);if(liveDependency!=null)return result.put("reason",liveDependency).put("nextAt",AutomationConditions.later(now));
        String unsettled=liveOtherOriginalTasks(state,identity,plan,step);if(unsettled!=null)return result.put("reason",unsettled).put("nextAt",AutomationConditions.later(now));
        JSONObject policy=execution.getJSONObject("policy");long deadline=Math.min(expiryLimit(step),policy.isNull("expiresAt")?MAX_TIME:policy.getLong("expiresAt"));
        if(step.has("taskAdmission"))deadline=Math.min(deadline,step.getJSONObject("taskAdmission").getLong("validUntil"));
        if(now>=deadline)return result.put("reason","approval_expired");long next=AutomationPolicy.quietEnd(policy,Math.max(now,followUpAt(step)));if(next>=deadline)return result.put("reason","expires_before_eligible");
        if(next>now)return result.put("reason",next>followUpAt(step)?"quiet_or_not_due":"not_due").put("nextAt",next);
        JSONObject condition=AutomationConditions.evaluate(policy,conditions,now);result.put("conditionStatus",condition);if(!condition.getBoolean("allowed"))return result.put("reason","conditions_blocked").put("nextAt",Math.min(deadline,condition.getLong("nextCheckAt")));
        JSONObject remote=remoteWaitStatus(identity,step);if(remote!=null){result.put("remoteWaitStatus",remote);if(!remote.optBoolean("allowed"))return result.put("reason",remote.getString("reason")).put("nextAt",Math.min(deadline,AutomationConditions.later(now)));}
        JSONObject business=businessWaitStatus(identity,step);if(business!=null){result.put("businessWaitStatus",business);if(!business.optBoolean("allowed"))return result.put("reason",business.getString("reason")).put("nextAt",Math.min(deadline,AutomationConditions.later(now)));}
        if(goalTasks==null)return result.put("reason","task_bridge_unavailable");
        if(step.has("taskAdmission")){
            if(step.getJSONObject("taskAdmission").getLong("admissionNumber")>execution.getLong("maxTaskAdmissions"))return result.put("reason","task_admission_budget_exhausted");
            return result.put("reason",step.optString("taskId").isEmpty()?"admission_unknown":"bound_task");
        }
        if(!step.optString("taskId").isEmpty())return result.put("reason","existing_task_requires_owner_continue");
        if(execution.getLong("admissions")>=execution.getLong("maxTaskAdmissions"))return result.put("reason","task_admission_budget_exhausted");
        return result.put("eligible",true).put("reason","ready").put("nextAt",now);
    }
    private JSONObject businessWaitStatus(JSONObject identity,JSONObject step)throws Exception{
        JSONObject reference=step.optJSONObject("businessWait");if(reference==null)return null;
        JSONObject intent=step.optJSONObject("taskAdmission"),task=step.optJSONObject("taskObservation");
        if(intent!=null&&intent.optJSONObject("businessWaitEvidence")!=null&&task!=null&&!task.optString("runId").isEmpty())return new JSONObject().put("allowed",true).put("reason","business_wait_consumed_for_original_task").put("evidence",copy(intent.getJSONObject("businessWaitEvidence"))).put("authority","original_plan_consumption").put("triggerScope","one_native_checked_event_then_independent_owner_goal");
        JSONObject blocked=new JSONObject().put("allowed",false).put("reason","business_wait_unavailable").put("gap",false).put("nextEventId",step.optString("businessWaitCursor",PlanBusinessWait.anchorId(reference))).put("localWindowOnly",true);if(evidenceResolver==null)return blocked;
        JSONObject observed;try{observed=evidenceResolver.resolve(copy(identity),copy(step),"business_wait_condition",PlanBusinessWait.anchorId(reference));}catch(Exception unavailable){return blocked;}
        if(observed==null||!"ChannelsModule".equals(observed.optString("authority"))||!observed.optString("reason").matches("business_wait_(pending|retention_gap|consumed_event_changed|satisfied)"))return blocked;
        if(observed.optBoolean("allowed")){JSONObject proof=observed.optJSONObject("evidence");if(proof==null||!"authenticated_channel_inbound".equals(proof.optString("kind"))||!PlanBusinessWait.same(reference,proof.optJSONObject("reference"))||!"original_authenticated_event_presence_only".equals(proof.optString("verificationScope"))||!proof.optString("fingerprint").matches("[0-9a-f]{64}")||!proof.optString("payloadSha256").matches("[0-9a-f]{64}")||proof.optString("eventId").isEmpty())return blocked;}
        return copy(observed);
    }
    private JSONObject remoteWaitStatus(JSONObject identity,JSONObject step)throws Exception{
        JSONObject reference=step.optJSONObject("remoteWait");if(reference==null)return null;
        JSONObject blocked=new JSONObject().put("allowed",false).put("reason","remote_wait_unavailable");if(evidenceResolver==null)return blocked;
        JSONObject observed;try{observed=evidenceResolver.resolve(copy(identity),copy(step),"remote_wait_condition",reference.getString("taskId"));}catch(Exception unavailable){return blocked;}
        if(observed==null||!"TaskStore".equals(observed.optString("authority")))return blocked;
        if(!observed.optString("reason").matches("remote_wait_(unavailable|identity_changed|cancelled|unknown|pending|exit_unavailable|failed|satisfied)"))return blocked;
        if(observed.optBoolean("allowed")){
            JSONObject proof=observed.optJSONObject("evidence");if(proof==null||!"remote_command_exit".equals(proof.optString("kind"))||!PlanRemoteWait.sameIdentity(reference,proof.optJSONObject("reference"))||proof.optInt("exitCode",-1)!=0||!"original_command_process_exit_only".equals(proof.optString("verificationScope")))return blocked;
        }
        return copy(observed);
    }
    private static boolean planTaskMatches(JSONObject identity,JSONObject step,JSONObject intent,JSONObject task)throws Exception {
        if(!"owner".equals(task.optString("actorId",task.optString("ownerId")))||!"private".equals(task.optString("spaceId"))||task.optString("taskId").isEmpty()||task.optString("status").isEmpty())return false;
        if(intent==null)return step.getString("taskId").equals(task.getString("taskId"))&&step.getString("id").equals(task.optString("commitmentId"))&&sameInteger(task.opt("commitmentRevision"),step.getLong("revision"));
        return intent.getString("occurrenceId").equals(task.optString("occurrenceId"))&&intent.getString("ruleId").equals(task.optString("ruleId"))&&sameInteger(task.opt("ruleRevision"),intent.getLong("ruleRevision"))&&intent.getString("goal").equals(task.optString("goal"));
    }
    private static JSONObject taskRequestView(JSONObject plan,JSONObject step)throws Exception {
        JSONObject intent=step.getJSONObject("taskAdmission"),execution=execution(plan);long deadline=Math.min(expiryLimit(step),execution.getJSONObject("policy").isNull("expiresAt")?MAX_TIME:execution.getJSONObject("policy").getLong("expiresAt"));
        deadline=Math.min(deadline,intent.getLong("validUntil"));
        JSONObject request=new JSONObject().put("occurrenceId",intent.getString("occurrenceId")).put("ruleId",intent.getString("ruleId")).put("ruleRevision",intent.getLong("ruleRevision"))
                .put("revision",intent.getLong("ruleRevision")).put("planId",plan.getString("id")).put("commitmentId",step.getString("id")).put("commitmentRevision",intent.getLong("commitmentRevision"))
                .put("goal",intent.getString("goal")).put("title",step.getString("title")).put("validUntil",deadline).put("taskId",step.optString("taskId"))
                .put("executionMode",intent.getString("executionMode")).put("executor",step.optString("executor","phone")).put("verification",PlanArtifactVerifier.standard(step.optJSONObject("verification")))
                .put("modelObservationConsent",execution.optBoolean("modelObservationConsent"))
                .put("allowPrivateArtifactFreeze",execution.optBoolean("allowPrivateArtifactFreeze"))
                .put("scheduledAt",step.getLong("dueAt")).put("runPolicy",copy(execution.getJSONObject("policy")))
                .put("actorId","owner").put("spaceId","private").put("requiresHostAdmission",true).put("executionAuthorized",false)
                .put("origin",new JSONObject().put("kind","automation").put("occurrenceId",intent.getString("occurrenceId")).put("planId",plan.getString("id")).put("commitmentId",step.getString("id")));
        if(step.optJSONObject("semanticConditionGate")!=null)request.put("semanticConditionGate",copy(step.getJSONObject("semanticConditionGate")));
        if(step.optJSONObject("remoteWait")!=null)request.put("remoteWait",copy(step.getJSONObject("remoteWait")));
        if(step.optJSONObject("businessWait")!=null)request.put("businessWait",copy(step.getJSONObject("businessWait"))).put("businessWaitCursor",step.optString("businessWaitCursor")).put("businessWaitEvidence",copy(intent.getJSONObject("businessWaitEvidence"))).put("businessEventConsent",execution.optBoolean("businessEventConsent"));
        return request;
    }
    private static String taskDependency(JSONObject state,JSONObject identity,JSONObject step,long now)throws Exception {
        JSONArray refs=step.optJSONArray("dependencies");if(refs==null)return "dependency_definition_missing";
        for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i),parent=find(state,identity,ref.getString("stepId"));
            if(!step.optString("planId").equals(parent.optString("planId")))return "dependency_membership_invalid";
            if(!terminal(parent))return expired(parent,now)?"dependency_expired":"dependency_pending";
            if(!PlanExecutionPolicy.matchesOutcome(parent.getString("status"),ref.getString("outcome")))return "dependency_outcome_mismatch";
        }return null;
    }
    private static JSONArray planSteps(JSONObject state,JSONObject identity,JSONObject plan)throws Exception {JSONArray result=new JSONArray(),ids=plan.getJSONArray("stepIds");for(int i=0;i<ids.length();i++)result.put(find(state,identity,ids.getString(i)));return result;}
    private static JSONObject planStep(JSONObject state,JSONObject identity,JSONObject plan,String id)throws Exception {JSONObject step=find(state,identity,id);if(!plan.getString("id").equals(step.optString("planId")))throw new IllegalArgumentException("步骤不属于此计划");return step;}
    private static JSONObject execution(JSONObject plan)throws Exception {JSONObject result=plan.optJSONObject("execution");if(result==null)throw new IllegalStateException("计划尚未经机主批准执行");return result;}
    private static boolean executionApproved(JSONObject state,JSONObject identity,JSONObject plan)throws Exception {JSONObject e=plan.optJSONObject("execution");return e!=null&&e.opt("approvalKey") instanceof String&&e.getString("approvalKey").equals(PlanExecutionPolicy.signature(plan,planSteps(state,identity,plan)));}
    private static long aggregatePlanRevision(JSONObject state,JSONObject identity,JSONObject plan)throws Exception {long value=plan.getLong("revision");JSONArray steps=planSteps(state,identity,plan);for(int i=0;i<steps.length();i++)value=Math.addExact(value,steps.getJSONObject(i).getLong("revision")-1);return value;}
    private static void expectPlanRevision(JSONObject state,JSONObject identity,JSONObject plan,long expected)throws Exception {if(expected<1||aggregatePlanRevision(state,identity,plan)!=expected)throw new IllegalStateException("计划或步骤已改变，请刷新后重试");}
    private static void touchPlan(JSONObject plan,long now)throws Exception {plan.put("revision",Math.addExact(plan.getLong("revision"),1)).put("updatedAt",now);}
    private static long expiryLimit(JSONObject step){return step.isNull("expiresAt")?MAX_TIME:step.optLong("expiresAt",MAX_TIME);}
    private static boolean hasUnsettledOriginalTask(JSONObject step){
        if(!step.has("taskAdmission")&&step.optString("taskId").isEmpty())return false;
        JSONObject observed=step.optJSONObject("taskObservation");
        if("completed".equals(step.optString("status"))&&step.optJSONObject("completionEvidence")!=null)return false;
        return observed==null||!observed.optBoolean("terminalVerified")||!observed.optString("status").matches("failed|cancelled");
    }
    private static boolean planInFlight(JSONObject state,JSONObject identity,JSONObject plan)throws Exception {JSONArray steps=planSteps(state,identity,plan);for(int i=0;i<steps.length();i++)if(hasUnsettledOriginalTask(steps.getJSONObject(i)))return true;return false;}
    /** A cached failed promise is insufficient when its original Task has since reopened. */
    private String liveFailureDependencies(JSONObject state,JSONObject identity,JSONObject step)throws Exception {
        JSONArray refs=step.getJSONArray("dependencies");for(int i=0;i<refs.length();i++){
            JSONObject parent=find(state,identity,refs.getJSONObject(i).getString("stepId"));if(!parent.optString("status").matches("failed|cancelled"))continue;
            JSONObject task=resolvePlanTask(identity,parent),intent=parent.optJSONObject("taskAdmission");
            if(task==null||!planTaskMatches(identity,parent,intent,task))return "dependency_task_state_unavailable";
            if(!parent.getString("status").equals(task.optString("status"))||!Boolean.TRUE.equals(task.opt("terminalVerified")))return "dependency_task_not_settled";
        }return null;
    }
    private JSONObject resolvePlanTask(JSONObject identity,JSONObject step)throws Exception {
        JSONObject intent=step.optJSONObject("taskAdmission");try{
            if(intent!=null&&goalTasks!=null)return goalTasks.resolve(intent.getString("occurrenceId"));
            if(evidenceResolver!=null&&!step.optString("taskId").isEmpty())return evidenceResolver.resolve(copy(identity),copy(step),"task_state",step.getString("taskId"));
        }catch(Exception unavailable){}return null;
    }
    /** A previously settled original Task can be reopened outside this plan; query it again. */
    private String liveOtherOriginalTasks(JSONObject state,JSONObject identity,JSONObject plan,JSONObject candidate)throws Exception {
        JSONArray steps=planSteps(state,identity,plan);for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i);if(step.getString("id").equals(candidate.getString("id"))||(!step.has("taskAdmission")&&step.optString("taskId").isEmpty()))continue;
            if(hasUnsettledOriginalTask(step))return "other_original_task_unsettled";
            JSONObject task=resolvePlanTask(identity,step);if(task==null||!planTaskMatches(identity,step,step.optJSONObject("taskAdmission"),task))return "other_original_task_state_unavailable";
            if("completed".equals(step.optString("status"))){if(!"completed".equals(task.optString("status"))||!Boolean.TRUE.equals(task.opt("resultVerified")))return "other_original_task_unsettled";}
            else if(!step.getString("status").equals(task.optString("status"))||!Boolean.TRUE.equals(task.opt("terminalVerified")))return "other_original_task_unsettled";
        }return null;
    }
    private static void privatePlanScope(JSONObject identity){if(!"owner".equals(identity.optString("ownerId"))||!"private".equals(identity.optString("spaceId")))throw new SecurityException("执行计划目前仅支持原生机主的私人空间");}
    private static void requireOrdinaryCommitment(JSONObject state,JSONObject identity,JSONObject record)throws Exception {if(record.has("planId")&&findPlan(state,identity,record.getString("planId")).optJSONObject("execution")!=null)throw new IllegalStateException("此步骤由执行计划管理，请先暂停并使用计划操作");}
    private static JSONArray copyArray(JSONArray value)throws Exception {return new JSONArray(value.toString());}
    private static JSONArray taskControls(JSONObject state,JSONObject identity,JSONObject plan,String action)throws Exception {JSONArray result=new JSONArray(),steps=planSteps(state,identity,plan);for(int i=0;i<steps.length();i++){
        JSONObject step=steps.getJSONObject(i);if(!hasUnsettledOriginalTask(step))continue;JSONObject observed=step.optJSONObject("taskObservation"),intent=step.optJSONObject("taskAdmission");
        result.put(new JSONObject().put("taskId",step.optString("taskId")).put("occurrenceId",intent==null?JSONObject.NULL:intent.getString("occurrenceId")).put("commitmentId",step.getString("id")).put("action",action)
                .put("runId",observed==null?"":observed.optString("runId")).put("coordinatorEpoch",observed==null?0:observed.optLong("coordinatorEpoch"))
                .put("lastObservedState",observed==null?"unavailable":observed.optString("status")).put("nativeLookupRequired",true).put("controlApplied",false));
    }return result;}

    /** Read-only scope policy. No existing user is silently opted into proactive delivery. */
    public JSONObject getFollowUpPolicy(JSONObject scope)throws Exception {
        JSONObject identity=scope(scope);
        synchronized(lock){return copy(policy(read(),identity));}
    }

    /** Native user-settings hook, not a model command. This replaces the explicit policy. */
    public JSONObject setFollowUpPolicy(JSONObject scope,long revision,JSONObject input,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        for(Iterator<String> keys=input.keys();keys.hasNext();){String key=keys.next();
            if(!key.equals("enabled")&&!key.equals("zoneId")&&!key.equals("quietHours")&&!key.equals("pausedUntil")&&!key.equals("cooldownMillis")&&!key.equals("maxNotificationsPerDay")&&!key.equals("requiresNetwork")&&!key.equals("requiresCharging")&&!key.equals("minBatteryPercent"))
                throw new IllegalArgumentException("不支持的跟进设置："+key);
        }
        if(!(input.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("必须明确选择是否启用跟进");
        String zone=requiredText(input,"zoneId",100);ZoneId.of(zone);
        long cooldown=input.has("cooldownMillis")?nonnegativeInteger(input,"cooldownMillis"):0;
        if(cooldown>31L*24*60*60*1000)throw new IllegalArgumentException("跟进冷却不能超过31天");
        Object paused=JSONObject.NULL;
        if(input.has("pausedUntil")&&!input.isNull("pausedUntil")){
            long until=time(input,"pausedUntil");if(until<=now)throw new IllegalArgumentException("暂停截止时间必须在未来");paused=until;
        }
        Object quiet=JSONObject.NULL;
        if(input.has("quietHours")&&!input.isNull("quietHours")){
            JSONObject hours=input.getJSONObject("quietHours");
            if(hours.length()!=2)throw new IllegalArgumentException("安静时段只接受start和end");
            String start=clockTime(hours,"start"),end=clockTime(hours,"end");
            if(start.equals(end))throw new IllegalArgumentException("安静时段起止不能相同；全天停止请停用跟进");
            quiet=new JSONObject().put("start",start).put("end",end);
        }
        Object dailyBudget=input.has("maxNotificationsPerDay")&&!input.isNull("maxNotificationsPerDay")?
                AutomationPolicy.integer(input,"maxNotificationsPerDay",0,1000):JSONObject.NULL;
        synchronized(lock){JSONObject state=read(),previous=policy(state,identity);
            if(revision<0||previous.getLong("revision")!=revision)throw new IllegalStateException("跟进设置已变化，请刷新后重试");
            JSONObject saved=copy(identity).put("revision",Math.addExact(revision,1)).put("enabled",input.getBoolean("enabled"))
                    .put("zoneId",zone).put("quietHours",quiet).put("pausedUntil",paused).put("cooldownMillis",cooldown).put("maxNotificationsPerDay",dailyBudget).put("updatedAt",now);
            AutomationConditions.appendRequirements(saved,input);
            JSONArray policies=array(state,"followUpPolicies");
            for(int i=policies.length()-1;i>=0;i--)if(matches(identity,policies.getJSONObject(i)))policies.remove(i);
            policies.put(saved);write(state);return copy(saved);
        }
    }

    /** Read-only eligibility, never a reservation or permission to send. */
    public JSONObject evaluateFollowUp(JSONObject scope,String id,long revision,String candidateKey,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);identifier(candidateKey,"候选");
        synchronized(lock){return evaluate(read(),identity,id,revision,candidateKey,now,null);}
    }

    /**
     * Native host reservation only. An unexpired token must pass beginFollowUpDelivery before
     * any effect. Expired reservations are safe to replace because they never granted dispatch.
     */
    public JSONObject claimFollowUp(JSONObject scope,String id,long revision,String candidateKey,long leaseMillis,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);identifier(candidateKey,"候选");
        if(leaseMillis<1000||leaseMillis>300000||now>MAX_TIME-leaseMillis)throw new IllegalArgumentException("领取租约应为1秒至5分钟");
        synchronized(lock){JSONObject state=read(),decision=evaluate(state,identity,id,revision,candidateKey,now,null);
            if(!decision.getBoolean("eligible"))return decision.put("claimed",false);
            JSONArray receipts=array(state,"followUpReceipts");
            // Only this candidate's never-dispatched reservation may be replaced. Old tokens die.
            for(int i=receipts.length()-1;i>=0;i--){JSONObject old=receipts.getJSONObject(i);
                if(matches(identity,old)&&candidateKey.equals(old.optString("candidateKey")))receipts.remove(i);
            }
            if(receipts.length()>=10000)throw new IllegalStateException("跟进收据已达到容量上限，未领取");
            JSONObject receipt=copy(identity).put("claimId",UUID.randomUUID().toString()).put("commitmentId",id)
                    .put("commitmentRevision",revision).put("candidateKey",candidateKey).put("policyRevision",policy(state,identity).getLong("revision"))
                    .put("status","reserved").put("claimedAt",now).put("leaseUntil",now+leaseMillis)
                    .put("dispatchStartedAt",JSONObject.NULL).put("recordedAt",JSONObject.NULL);
            receipts.put(receipt);write(state);
            return decision.put("claimed",true).put("receipt",copy(receipt)).put("dispatchGranted",false);
        }
    }

    /**
     * The sole one-shot transition before the host sends. Rechecks under the SAME state lock.
     * Persist unknown before returning dispatchGranted; a missing result can never be retried
     * by lease expiry. This is dedup admission, NOT notification/channel/user authorization.
     * Cancellation after this transition cannot recall an already admitted external effect.
     */
    public JSONObject beginFollowUpDelivery(JSONObject scope,String claimId,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        synchronized(lock){JSONObject state=read(),receipt=receipt(state,identity,claimId);
            if(!"reserved".equals(receipt.getString("status")))
                return deliveryResult(receipt,false,"already_started");
            if(receipt.getLong("leaseUntil")<=now)return deliveryResult(receipt,false,"lease_expired");
            if(receipt.getLong("policyRevision")!=policy(state,identity).getLong("revision"))return deliveryResult(receipt,false,"policy_changed");
            JSONObject decision=evaluate(state,identity,receipt.getString("commitmentId"),receipt.getLong("commitmentRevision"),
                    receipt.getString("candidateKey"),now,claimId);
            if(!decision.getBoolean("eligible"))return decision.put("dispatchGranted",false).put("receipt",copy(receipt));
            receipt.put("status","delivery_unknown").put("dispatchStartedAt",now);write(state);
            return deliveryResult(receipt,true,"host_dispatch_admitted");
        }
    }

    /**
     * Native result/reconciliation hook. notified means host submission, NOT user reading.
     * not_sent requires host proof that no effect occurred; timeout/error alone is unknown.
     * Both known outcomes consume this candidate; this module performs no automatic retries.
     * Recording is allowed after edits/cancellation so actual effects are not erased.
     */
    public JSONObject recordFollowUpDelivery(JSONObject scope,String claimId,String outcome,long now)throws Exception {
        JSONObject identity=scope(scope);validNow(now);
        if(!"notified".equals(outcome)&&!"not_sent".equals(outcome)&&!"delivery_unknown".equals(outcome))
            throw new IllegalArgumentException("跟进结果仅接受notified、not_sent或delivery_unknown");
        synchronized(lock){JSONObject state=read(),receipt=receipt(state,identity,claimId);String status=receipt.getString("status");
            if("reserved".equals(status))throw new IllegalStateException("尚未准入投递，不能记录已投递结果");
            if(status.equals(outcome))return copy(receipt);
            if(!"delivery_unknown".equals(status))throw new IllegalStateException("已确认的跟进结果不能被覆盖");
            if(now<receipt.getLong("dispatchStartedAt"))throw new IllegalArgumentException("结果时间早于投递准入");
            receipt.put("status",outcome).put("recordedAt",now);write(state);return copy(receipt);
        }
    }

    public JSONObject getFollowUpReceipt(JSONObject scope,String claimId)throws Exception {
        JSONObject identity=scope(scope);
        synchronized(lock){return copy(receipt(read(),identity,claimId));}
    }

    /** Native read-only recovery: discover unknown outcomes after restart without a second log. */
    public JSONArray listFollowUpReceipts(JSONObject scope,String commitmentId)throws Exception {
        JSONObject identity=scope(scope);
        synchronized(lock){JSONObject state=read();find(state,identity,commitmentId);
            JSONArray result=new JSONArray(),receipts=array(state,"followUpReceipts");
            for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);
                if(matches(identity,receipt)&&commitmentId.equals(receipt.getString("commitmentId")))result.put(copy(receipt));
            }
            return result;
        }
    }

    private JSONObject evaluate(JSONObject state,JSONObject identity,String id,long revision,String key,long now,String ownClaim)throws Exception {
        JSONObject record=find(state,identity,id),policy=policy(state,identity);
        JSONObject result=copy(identity).put("commitmentId",id).put("commitmentRevision",revision).put("candidateKey",key)
                .put("policyRevision",policy.getLong("revision")).put("evaluatedAt",now).put("dispatchGranted",false)
                .put("referenceOnly",true).put("requiresHostAdmission",true).put("executionAuthorized",false);
        if(terminal(record))return decision(result,false,record.getString("status"),-1);
        if(record.has("planId")&&findPlan(state,identity,record.getString("planId")).optJSONObject("execution")!=null)return decision(result,false,"execution_plan_managed",-1);
        if(revision!=record.getLong("revision")||!key.equals(candidateKey(record)))return decision(result,false,"stale_candidate",-1);
        if(expired(record,now))return decision(result,false,"expired",-1);
        String dependency=dependency(state,identity,record,now);
        if(dependency!=null)return decision(result,false,dependency,-1);
        long next=Math.max(now,followUpAt(record)),cooldown=policy.getLong("cooldownMillis");String reason=next>now?"not_due":"eligible";
        JSONArray receipts=array(state,"followUpReceipts");
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);if(!matches(identity,receipt))continue;
            String status=receipt.getString("status");boolean same=key.equals(receipt.getString("candidateKey"));
            if(id.equals(receipt.getString("commitmentId"))&&status.equals("delivery_unknown"))return decision(result,false,"delivery_unknown",-1);
            if(same&&(status.equals("notified")||status.equals("not_sent")))return decision(result,false,status.equals("notified")?"already_notified":"not_sent",-1);
            if(status.equals("reserved")&&!receipt.getString("claimId").equals(ownClaim)&&reservationActive(state,identity,receipt,policy,now)){
                if(same)return decision(result,false,"claimed",receipt.getLong("leaseUntil"));
                if(cooldown>0&&receipt.getLong("leaseUntil")>next){next=receipt.getLong("leaseUntil");reason="scope_reserved";}
            }
            if(!receipt.isNull("dispatchStartedAt")&&cooldown>0){
                long until=Math.min(MAX_TIME,receipt.getLong("dispatchStartedAt")+cooldown);
                if(until>next){next=until;reason="cooldown";}
            }
        }
        if(!policy.getBoolean("enabled"))return decision(result,false,"disabled",-1);
        if(!policy.isNull("pausedUntil")&&policy.getLong("pausedUntil")>next){next=policy.getLong("pausedUntil");reason="paused";}
        long quietEnd=quietEnd(policy,next);
        if(quietEnd>next){next=quietEnd;reason="quiet_hours";}
        long budgetAt=budgetNext(state,identity,policy,next,ownClaim);
        if(budgetAt<0)return decision(result,false,"daily_budget_exhausted",-1);
        if(budgetAt>next){next=quietEnd(policy,budgetAt);reason="daily_budget_exhausted";}
        if(expired(record,next))return decision(result,false,"expires_before_eligible",-1);
        if(next<=now){JSONObject condition=AutomationConditions.evaluate(policy,conditions,now);result.put("conditionStatus",condition);
            if(!condition.getBoolean("allowed")){next=quietEnd(policy,condition.getLong("nextCheckAt"));if(expired(record,next))return decision(result,false,"expires_before_eligible",-1);
                return decision(result,false,"conditions_blocked",next);}
        }
        return decision(result,next<=now,reason,next);
    }
    private static boolean reservationActive(JSONObject state,JSONObject identity,JSONObject receipt,JSONObject policy,long now)throws Exception {
        if(receipt.getLong("leaseUntil")<=now||receipt.getLong("policyRevision")!=policy.getLong("revision"))return false;
        JSONObject record=find(state,identity,receipt.getString("commitmentId"));
        return !terminal(record)&&!expired(record,now)&&dependency(state,identity,record,now)==null&&receipt.getLong("commitmentRevision")==record.getLong("revision")&&receipt.getString("candidateKey").equals(candidateKey(record));
    }
    private static JSONObject decision(JSONObject result,boolean eligible,String reason,long next)throws Exception {
        return result.put("eligible",eligible).put("decision",eligible?"allow_follow_up":"silent").put("reason",reason)
                .put("nextEligibleAt",next<0?JSONObject.NULL:next);
    }
    private static JSONObject deliveryResult(JSONObject receipt,boolean granted,String reason)throws Exception {
        return new JSONObject().put("receipt",copy(receipt)).put("dispatchGranted",granted).put("reason",reason)
                .put("requiresHostAdmission",true).put("executionAuthorized",false);
    }
    private static JSONObject policy(JSONObject state,JSONObject identity)throws Exception {
        JSONArray policies=array(state,"followUpPolicies");
        for(int i=0;i<policies.length();i++){JSONObject policy=policies.getJSONObject(i);if(matches(identity,policy)){if(!policy.has("maxNotificationsPerDay"))policy.put("maxNotificationsPerDay",JSONObject.NULL);return policy;}}
        return copy(identity).put("revision",0L).put("enabled",false).put("zoneId","UTC").put("quietHours",JSONObject.NULL)
                .put("pausedUntil",JSONObject.NULL).put("cooldownMillis",0L).put("maxNotificationsPerDay",JSONObject.NULL).put("updatedAt",JSONObject.NULL);
    }
    private static long budgetNext(JSONObject state,JSONObject identity,JSONObject policy,long at,String ownClaim)throws Exception {
        if(policy.isNull("maxNotificationsPerDay"))return at;
        long maximum=policy.getLong("maxNotificationsPerDay");if(maximum==0)return -1;
        long start=AutomationPolicy.dayStart(policy,at),end=AutomationPolicy.nextDay(policy,at),used=0,reserved=0,lease=end;
        JSONArray receipts=array(state,"followUpReceipts");
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);if(!matches(identity,receipt))continue;
            if(!receipt.isNull("dispatchStartedAt")){long dispatched=receipt.getLong("dispatchStartedAt");if(dispatched>=start&&dispatched<end)used++;}
            else if("reserved".equals(receipt.optString("status"))&&!receipt.getString("claimId").equals(ownClaim)&&reservationActive(state,identity,receipt,policy,at)){
                reserved++;lease=Math.min(lease,receipt.getLong("leaseUntil"));
            }
        }
        if(used>=maximum)return end;
        return used+reserved>=maximum?lease:at;
    }
    private static JSONObject receipt(JSONObject state,JSONObject identity,String claimId)throws Exception {
        identifier(claimId,"跟进领取");JSONArray receipts=array(state,"followUpReceipts");
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);
            if(claimId.equals(receipt.optString("claimId"))&&matches(identity,receipt))return receipt;
        }
        throw new IllegalArgumentException("此空间中不存在该跟进领取");
    }
    private static JSONArray array(JSONObject state,String key)throws Exception {
        if(!state.has(key))state.put(key,new JSONArray());
        return state.getJSONArray(key);
    }
    private static String candidateKey(JSONObject record)throws Exception {
        return record.getString("id")+":"+record.getLong("revision")+":"+followUpAt(record);
    }
    private static String clockTime(JSONObject input,String key)throws Exception {
        String text=requiredText(input,key,5);
        if(!text.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("安静时段使用HH:mm");
        return text;
    }
    /**
     * Compare complete zoned intervals, not just wall-clock strings. Overlaps start at the
     * earlier offset and end at the later offset, keeping repeated quiet minutes silent.
     * Gap endpoints follow java.time's forward-by-gap resolution. Start inclusive/end exclusive.
     */
    private static long quietEnd(JSONObject policy,long at)throws Exception {
        JSONObject hours=policy.optJSONObject("quietHours");if(hours==null)return at;
        ZoneId zone=ZoneId.of(policy.getString("zoneId"));LocalTime start=LocalTime.parse(hours.getString("start")),end=LocalTime.parse(hours.getString("end"));
        LocalDate today=Instant.ofEpochMilli(at).atZone(zone).toLocalDate();long result=at;
        for(int day=-1;day<=0;day++){
            LocalDate date=today.plusDays(day);LocalDate endDate=start.isBefore(end)?date:date.plusDays(1);
            long from=date.atTime(start).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
            long until=endDate.atTime(end).atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli();
            if(at>=from&&at<until)result=Math.max(result,until);
        }
        return result;
    }
    private static long nonnegativeInteger(JSONObject input,String key){
        Object value=input.opt(key);
        if(!(value instanceof Number)||((Number)value).longValue()<0||((Number)value).doubleValue()!=((Number)value).longValue())
            throw new IllegalArgumentException(key+"必须为非负整数");
        return ((Number)value).longValue();
    }

    private JSONObject verifiedEvidence(JSONObject identity,JSONObject record,JSONObject ref,long now)throws Exception {
        if(evidenceResolver==null)throw new IllegalStateException("宿主尚未连接真实完成凭证查询，不能标记完成");
        String kind=requiredText(ref,"kind",40),id=requiredText(ref,"id",200);
        if(!kind.equals("task_result")&&!kind.equals("owner_confirmation"))throw new IllegalArgumentException("不支持的完成凭证类型");
        // Do not let a supplied scope override the authenticated identity, even if the resolver
        // could resolve an identically named local record.
        referenceScope(identity,ref);
        JSONObject resolved=evidenceResolver.resolve(copy(identity),copy(record),kind,id);
        if(resolved==null||!matches(identity,resolved)||!kind.equals(resolved.optString("kind"))||!id.equals(resolved.optString("id"))||
                !record.getString("id").equals(resolved.optString("commitmentId"))||
                !sameInteger(resolved.opt("commitmentRevision"),record.getLong("revision"))||!"completed".equals(resolved.optString("outcome")))
            throw new IllegalStateException("未找到属于此承诺和空间的真实完成凭证");
        long recordedAt=time(resolved,"recordedAt");if(recordedAt>now)throw new IllegalStateException("完成凭证时间在未来");
        JSONObject evidence=new JSONObject().put("kind",kind).put("id",id).put("ownerId",identity.getString("ownerId"))
                .put("spaceId",identity.getString("spaceId")).put("commitmentId",record.getString("id")).put("commitmentRevision",record.getLong("revision"))
                .put("outcome","completed").put("recordedAt",recordedAt);
        if(kind.equals("task_result")){
            String task=record.getString("taskId");
            if(task.isEmpty()||!task.equals(resolved.optString("taskId")))throw new IllegalStateException("完成凭证与关联任务不一致");
            evidence.put("taskId",task);
        }
        if("native_workspace_artifact_standard".equals(resolved.optString("source"))){
            JSONObject proof=resolved.getJSONObject("verification"),artifact=new JSONObject();for(String key:new String[]{"artifactId","sha256","sizeBytes","projectId","path","sourceVersion","sourceRevisionId","operationId","runId","coordinatorEpoch","verificationScope"})if(proof.has(key))artifact.put(key,proof.get(key));
            evidence.put("verificationSource","native_workspace_artifact_standard").put("artifact",artifact);
        }else if("native_owner_confirmation".equals(resolved.optString("source")))evidence.put("verificationSource","owner_confirmation");
        return evidence;
    }
    private static JSONObject eventResult(JSONObject record,long now,boolean applied,String reason)throws Exception {
        return new JSONObject().put("commitment",view(record,now)).put("applied",applied).put("reason",reason);
    }
    private static void finish(JSONObject record,JSONObject evidence,long now)throws Exception {
        record.put("status","completed").put("snoozedUntil",JSONObject.NULL).put("completionEvidence",evidence);advance(record,now);
    }
    private static void advance(JSONObject record,long now)throws Exception {
        record.put("revision",Math.addExact(record.getLong("revision"),1L)).put("updatedAt",now);
    }
    private static void expectRevision(JSONObject record,long revision)throws Exception {
        if(revision<1||record.getLong("revision")!=revision)throw new IllegalStateException("承诺已被修改，请刷新后重试");
    }
    private static void requireOpen(JSONObject record)throws Exception {
        if(terminal(record))throw new IllegalStateException("承诺已完成或取消，不能重新激活");
    }
    private static boolean terminal(JSONObject record)throws Exception {
        return PlanExecutionPolicy.terminal(record);
    }
    private static long followUpAt(JSONObject record)throws Exception {
        return "snoozed".equals(record.getString("status"))?record.getLong("snoozedUntil"):record.getLong("dueAt");
    }
    private static JSONObject view(JSONObject record,long now)throws Exception {
        JSONObject result=copy(record);boolean isExpired=!terminal(record)&&expired(record,now),open=!terminal(record)&&!isExpired;long at=open?followUpAt(record):-1;
        return result.put("followUpAt",open?at:JSONObject.NULL).put("followUpDue",open&&at<=now)
                .put("lifecycleState",isExpired?"expired":record.getString("status"))
                .put("candidateKey",open?candidateKey(record):JSONObject.NULL)
                .put("referenceOnly",true).put("executionAuthorized",false);
    }
    private static Object expiry(JSONObject input,long due)throws Exception {
        if(!input.has("expiresAt")||input.isNull("expiresAt"))return JSONObject.NULL;
        long until=time(input,"expiresAt");if(until<=due)throw new IllegalArgumentException("承诺有效截止必须晚于跟进时间");return until;
    }
    private static boolean expired(JSONObject record,long now){return !record.isNull("expiresAt")&&now>=record.optLong("expiresAt",MAX_TIME);}
    private static JSONObject source(JSONObject identity,JSONObject input)throws Exception {
        if(!matches(identity,input))throw new IllegalArgumentException("委托来源必须属于同一用户和空间");
        String kind=requiredText(input,"kind",40);
        if(!kind.equals("user_message")&&!kind.equals("user_action"))throw new IllegalArgumentException("委托必须来自明确的用户消息或操作");
        return new JSONObject().put("ownerId",identity.getString("ownerId")).put("spaceId",identity.getString("spaceId"))
                .put("kind",kind).put("id",requiredText(input,"id",200));
    }
    private static JSONObject scope(JSONObject input)throws Exception {
        if(input==null)throw new IllegalArgumentException("必须指定承诺所属用户和空间");
        Object owner=input.opt("ownerId"),space=input.opt("spaceId");
        if(!(owner instanceof String)||!(space instanceof String)||!((String)owner).matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||
                !((String)space).matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))throw new IllegalArgumentException("承诺空间标识无效");
        return new JSONObject().put("ownerId",owner).put("spaceId",space);
    }
    private static boolean matches(JSONObject scope,JSONObject value){
        return scope.optString("ownerId").equals(value.opt("ownerId"))&&scope.optString("spaceId").equals(value.opt("spaceId"));
    }
    private static void referenceScope(JSONObject identity,JSONObject ref){
        if((ref.has("ownerId")&&!identity.optString("ownerId").equals(ref.opt("ownerId")))||
                (ref.has("spaceId")&&!identity.optString("spaceId").equals(ref.opt("spaceId"))))
            throw new IllegalArgumentException("完成凭证不能跨空间");
    }
    private static boolean sameInteger(Object value,long expected){
        return value instanceof Number&&((Number)value).longValue()==expected&&((Number)value).doubleValue()==expected;
    }
    private static JSONObject find(JSONObject state,JSONObject scope,String id)throws Exception {
        identifier(id,"承诺");JSONArray records=state.getJSONArray("commitments");
        for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(id.equals(record.optString("id"))&&matches(scope,record))return record;}
        throw new IllegalArgumentException("此空间中不存在该承诺");
    }
    private JSONObject read()throws Exception {
        if(!file.exists())return new JSONObject().put("version",1).put("commitments",new JSONArray());
        if(!file.isFile()||file.length()>8_000_000)throw new IllegalStateException("承诺文件无效或过大，未覆盖原数据");
        JSONObject state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        if((state.optInt("version",-1)!=1&&state.optInt("version",-1)!=2)||state.optJSONArray("commitments")==null)throw new IllegalStateException("不支持的承诺文件格式，未覆盖原数据");
        return state;
    }
    private void write(JSONObject state)throws Exception {
        File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("无法建立承诺目录");
        byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>8_000_000)throw new IllegalStateException("承诺文件已达到容量上限");
        File temporary=new File(file.getPath()+".new");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
        try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){
            // Failing closed preserves the previous file. A non-atomic replace could delete it
            // before an unsuccessful move and would violate failed-save rollback semantics.
            throw new IllegalStateException("此存储不支持原子保存，原承诺记录未更新",unsupported);
        }
    }
    private static void rejectFields(JSONObject input,String... keys){
        for(String key:keys)if(input.has(key))throw new IllegalArgumentException("创建承诺不能指定字段："+key);
    }
    private static long time(JSONObject input,String key)throws Exception {
        Object value=input.opt(key);
        if(!(value instanceof Number))throw new IllegalArgumentException(key+"必须为毫秒时间戳");
        Number number=(Number)value;long time=number.longValue();
        if(time<=0||time>MAX_TIME||number.doubleValue()!=time)throw new IllegalArgumentException(key+"不是有效时间戳");
        return time;
    }
    private static void validNow(long now){if(now<=0||now>MAX_TIME)throw new IllegalArgumentException("当前时间无效");}
    private static String requiredText(JSONObject input,String key,int max)throws Exception {
        Object value=input.opt(key);if(!(value instanceof String))throw new IllegalArgumentException(key+"必须为文字");
        String text=((String)value).trim();if(text.isEmpty()||text.length()>max)throw new IllegalArgumentException(key+"长度无效");return text;
    }
    private static String optionalText(JSONObject input,String key,int max,String fallback)throws Exception {
        if(!input.has(key))return fallback;Object value=input.opt(key);if(!(value instanceof String))throw new IllegalArgumentException(key+"必须为文字");
        String text=((String)value).trim();if(text.length()>max)throw new IllegalArgumentException(key+"过长");return text;
    }
    private static String identifier(String value,String label){
        if(value==null||value.trim().isEmpty()||value.length()>200||!value.equals(value.trim()))throw new IllegalArgumentException(label+"标识无效");return value;
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
}
