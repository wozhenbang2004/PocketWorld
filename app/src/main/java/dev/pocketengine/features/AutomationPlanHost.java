package dev.pocketengine.features;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.automation.AutomationBook;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.features.automation.PlanArtifactVerifier;
import dev.pocketengine.features.automation.PlanExecutionPolicy;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner glue. Commitments are promises; TaskStore remains the sole execution authority. */
public final class AutomationPlanHost implements AutomationBook.GoalTaskResolver {
    /** Read only: implementations return the retained original controller, never allocate/start one. */
    public interface CurrentControl {
        RunControl get(String taskId);
        /** Native read-side grant/consent/resource check. Missing checks must remain conservative. */
        default boolean authorizationValid(JSONObject task)throws Exception{return false;}
        /** Actual existing Application admission/controller; never a replacement Runner. */
        default JSONObject admitPlanTask(AutomationModule module,JSONObject prepared)throws Exception{throw new IllegalStateException("计划原任务准入未接入");}
        default JSONObject controlPlanTask(AutomationModule module,JSONObject expected,String action)throws Exception{throw new IllegalStateException("计划原任务控制未接入");}
        default JSONObject requirementsForPlan(JSONObject plan)throws Exception{return new JSONObject().put("allowed",false).put("reason","native_host_unavailable");}
    }
    private static final Set<String> COMMANDS=Set.of("createPlan","listPlans","getPlan","cancelPlan","getProactivityState",
            "createCommitment","updateCommitment","getCommitment","listCommitments","dueCommitments",
            "completeCommitment","cancelCommitment","snoozeCommitment","getFollowUpPolicy","evaluateFollowUp");
    private final TaskStore tasks;
    private final CurrentControl controls;
    private final PlanArtifactVerifier artifactVerifier;
    @FunctionalInterface public interface ProposalSources {JSONObject call(JSONArray nativeUserRefs,java.util.concurrent.Callable<JSONObject> action)throws Exception;}
    private volatile ProposalSources proposalSources;
    public synchronized void bindProposalSources(ProposalSources original){if(proposalSources!=null&&proposalSources!=original)throw new SecurityException("原计划来源桥不能替换");proposalSources=java.util.Objects.requireNonNull(original);}
    private volatile dev.pocketengine.features.channels.ChannelsModule businessModule;private volatile dev.pocketengine.features.automation.ChannelBusinessWaits businessEvents;
    private final java.util.concurrent.atomic.AtomicInteger planCursor=new java.util.concurrent.atomic.AtomicInteger();
    private final String snapshotProcess=java.util.UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicLong snapshotSequence=new java.util.concurrent.atomic.AtomicLong();
    public AutomationPlanHost(TaskStore tasks,CurrentControl controls){this(tasks,controls,null);}
    public AutomationPlanHost(TaskStore tasks,CurrentControl controls,PlanArtifactVerifier.Source artifacts){this.tasks=java.util.Objects.requireNonNull(tasks);this.controls=controls;this.artifactVerifier=artifacts==null?null:new PlanArtifactVerifier(artifacts);}
    public AutomationPlanHost(TaskStore tasks){this(tasks,null);}
    public synchronized void bindBusinessEvents(dev.pocketengine.features.channels.ChannelsModule module){if(module==null||businessModule!=null&&businessModule!=module)throw new SecurityException("必须绑定唯一原认证渠道");businessModule=module;businessEvents=new dev.pocketengine.features.automation.ChannelBusinessWaits(module,tasks);}
    private JSONArray businessWaitCandidates(){try{return businessEvents==null?new JSONArray():businessEvents.candidates();}catch(Exception unavailable){return new JSONArray();}}
    public static boolean handles(String command){return COMMANDS.contains(command);}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}

    private JSONObject withProposalSource(JSONObject source,java.util.concurrent.Callable<JSONObject> action)throws Exception {
        if(dev.pocketengine.features.automation.DeviceScheduleOrigin.isDevice(source))return tasks.withDeviceScheduleSourceCurrent(source,action);
        ProposalSources guard=proposalSources;if(guard==null)throw new dev.pocketengine.features.automation.ScheduleSourceState.Unavailable();String task=source.getString("taskId"),run=source.getString("runId"),conversation=source.getString("conversationId");int epoch=source.getInt("epoch");JSONArray refs=tasks.scheduleProposalUserRefs(task,run,epoch,conversation);
        return guard.call(refs,()->tasks.withScheduleProposalSourceCurrent(task,run,epoch,conversation,refs,action));
    }
    @Override public JSONObject withOriginalScheduleSource(JSONObject origin,java.util.concurrent.Callable<JSONObject> originalBookAction)throws Exception {return withProposalSource(origin,originalBookAction);}
    /** The model can only write an inert draft under its original prepared operation. */
    public JSONObject proposeScheduleForTask(AutomationModule module,String task,String run,int epoch,String operation,JSONObject draft,RunControl original)throws Exception {
        tasks.requireScheduleProposalPrepared(task,run,epoch,operation,draft,original);JSONObject current=tasks.get(task,false);privateOwner(current);if(current.optJSONObject("origin")!=null||current.optString("conversationId").isBlank())throw new SecurityException("仅原私人对话能提出计划");
        JSONObject origin=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",task).put("conversationId",current.getString("conversationId")).put("runId",run).put("epoch",epoch);
        JSONObject result=withProposalSource(origin,()->{tasks.requireScheduleProposalPrepared(task,run,epoch,operation,draft,original);return new JSONObject().put("proposal",module.proposeScheduleForTask(origin,operation,draft)).put("reviewRequired",true).put("scheduled",false).put("executionAuthorized",false);});tasks.requireScheduleProposalPrepared(task,run,epoch,operation,draft,original);return result;
    }
    public JSONObject scheduleProposalsForTask(AutomationModule module,String task,String run,int epoch,RunControl original)throws Exception {
        tasks.checkCapability(task,run,epoch,"schedule_list",original);JSONObject current=tasks.get(task,false);privateOwner(current);JSONObject origin=new JSONObject().put("taskId",task).put("conversationId",current.getString("conversationId")).put("runId",run).put("epoch",epoch);
        return withProposalSource(origin,()->{JSONArray rows=module.scheduleProposalsForTask(task),result=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),from=row.getJSONObject("origin");if(run.equals(from.optString("runId"))&&epoch==from.optInt("epoch")&&origin.getString("conversationId").equals(from.optString("conversationId")))result.put(row);}return new JSONObject().put("proposals",result).put("executionAuthorized",false);});
    }
    /** Native owner surface only; these operations are deliberately absent from handles/feature tools. */
    public JSONObject ownerCommand(AutomationModule module,String command,JSONObject input)throws Exception{
        JSONObject result=new JSONObject().put("command",command);String id=input.optString("id");
        switch(command){
            case "snapshot":break;
            case "approveSchedule":{
                JSONObject proposal=null;JSONArray rows=module.scheduleProposalsForTask(null);for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))proposal=rows.getJSONObject(i);if(proposal==null)throw new IllegalArgumentException("原计划草案不存在");AutomationBook.reviewedProposalForDisplay(proposal);JSONObject source=proposal.getJSONObject("origin");
                result.put("schedule",module.approveScheduleProposalForOwner(id,input.getString("digest"),bookAction->withProposalSource(source,bookAction)));break;
            }
            case "create":result.put("plan",handle(module,"createPlan",new JSONObject().put("plan",input.getJSONObject("plan")),true).getJSONObject("plan"));break;
            case "approve":{JSONObject plan=module.handle("getPlan",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("plan");validateRemoteWaitPlan(plan);validateBusinessWaitPlan(plan);result.put("plan",module.approvePlanExecution(scope(),id,input.getLong("revision"),input.getJSONObject("settings")));break;}
            case "start":result.put("plan",module.startPlanExecution(scope(),id,input.getLong("revision")));break;
            case "pause":case "resume":{
                JSONObject plan=module.setPlanPaused(scope(),id,input.getLong("revision"),command.equals("pause"));result.put("plan",plan).put("controls",applyControls(module,plan,command));break;
            }
            case "cancel":{JSONObject current=module.handle("getPlan",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("plan");JSONObject plan=current.optJSONObject("execution")==null?handle(module,"cancelPlan",input).getJSONObject("plan"):module.cancelPlanExecution(scope(),id,input.getLong("revision"));result.put("plan",plan).put("controls",applyControls(module,plan,"cancel"));break;}
            case "editStep":{JSONObject patch=input.getJSONObject("patch");validateRemoteWait(patch);validateBusinessWait(patch);result.put("plan",module.editPlanStep(scope(),id,input.getLong("revision"),input.getString("stepId"),input.getLong("stepRevision"),patch));break;}
            case "retry":result.put("plan",module.retryPlanStep(scope(),id,input.getLong("revision"),input.getString("stepId"),input.getLong("stepRevision")));break;
            case "inspect":case "refresh":inspectOriginalPlan(module,id);break;
            default:throw new IllegalArgumentException("未知原生计划操作");
        }
        if(command.matches("start|resume|approve|refresh"))onDue(module,System.currentTimeMillis(),1,id);
        JSONObject snapshot=ownerSnapshot(module),changed=result.optJSONObject("plan");
        if(changed!=null){JSONArray plans=snapshot.getJSONArray("plans");for(int i=0;i<plans.length();i++)if(changed.getString("id").equals(plans.getJSONObject(i).getString("id"))){result.put("plan",plans.getJSONObject(i));break;}}
        return result.put("snapshot",snapshot);
    }
    /** Reopen native identity/evidence only. Never start/resume a task while inspecting recovery. */
    private void inspectOriginalPlan(AutomationModule module,String id)throws Exception{
        JSONObject plan=module.refreshPlanTasks(scope(),id);JSONArray steps=plan.getJSONArray("steps");
        for(int i=0;i<steps.length();i++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("原任务核对已停止");String task=steps.getJSONObject(i).optString("taskId");if(!task.isEmpty())onTaskChanged(module,task);}
    }
    public JSONObject ownerSnapshot(AutomationModule module)throws Exception{
        JSONArray plans=handle(module,"listPlans",new JSONObject()).getJSONArray("plans");
        for(int i=0;i<plans.length();i++){JSONObject plan=executionView(module,plans.getJSONObject(i));plans.put(i,plan);plan.put("requirements",controls==null?new JSONObject().put("allowed",false).put("reason","native_host_unavailable"):controls.requirementsForPlan(plan));
            JSONObject next=plan.optJSONObject("nextStep");if(plan.optJSONObject("execution")!=null&&next!=null)plan.put("runtimeDecision",module.inspectPlanTask(scope(),plan.getString("id"),next.getString("id")));
        }
        return new JSONObject().put("plans",plans).put("scheduleProposals",proposalSnapshot(module)).put("remoteWaitCandidates",tasks.automationRemoteWaitCandidates(20)).put("businessWaitCandidates",businessWaitCandidates()).put("businessWaitSourceAvailable",businessEvents!=null).put("conditions",module.conditionsSnapshot()).put("taskAuthority","TaskStore").put("workerState","not_inferred_from_plan_state").put("snapshotProcess",snapshotProcess).put("snapshotSequence",snapshotSequence.incrementAndGet());
    }
    private JSONArray proposalSnapshot(AutomationModule module)throws Exception {
        JSONArray rows=module.scheduleProposalsForTask(null),result=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),checked=null;
            try{checked=AutomationBook.reviewedProposalForDisplay(row);JSONObject shown=withProposalSource(row.getJSONObject("origin"),()->new JSONObject(row.toString()).put("sourceAvailable",true));if(dev.pocketengine.features.automation.DeviceScheduleOrigin.isDevice(row.getJSONObject("origin")))shown.put("sourceLabel","已配对设备提交，等待本机审阅");result.put(shown);}
            catch(Exception unavailable){
                // Credential withdrawal is not a request to forget the owner's retained draft.
                if(checked!=null&&dev.pocketengine.features.automation.DeviceScheduleOrigin.isDevice(checked.getJSONObject("origin"))){result.put(checked.put("sourceAvailable",false).put("sourceCheckPending",true).put("approvalBlocked",true).put("sourceLabel","原设备授权已失效或暂不可核对；草稿保留，安排已暂停").put("executionAuthorized",false));continue;}
                boolean invalidated=row.optBoolean("sourceInvalidated")||unavailable instanceof dev.pocketengine.features.automation.ScheduleSourceState.Invalidated;result.put(new JSONObject().put("id",row.optString("id")).put("status",invalidated?"source_invalidated":"source_check_pending").put("sourceInvalidated",invalidated).put("sourceAvailable",false).put("executionAuthorized",false));
            }
        }return result;
    }
    private JSONObject executionView(AutomationModule module,JSONObject plan)throws Exception{
        if(plan.optJSONObject("execution")==null)return plan;JSONArray steps=plan.getJSONArray("steps");
        for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(!step.optString("status").matches("completed|failed|cancelled|skipped")&&(step.optJSONObject("taskAdmission")!=null||!step.optString("taskId").isEmpty()))return PlanExecutionPolicy.executionStep(plan,step.getString("id"));}
        if(!plan.optBoolean("approvalValid")||!plan.getJSONObject("execution").optString("state").equals("active")||!plan.getJSONObject("execution").optBoolean("started"))return plan;
        JSONArray ready=module.readyPlanTasks(scope(),plan.getString("id"),50).getJSONArray("candidates");JSONObject first=null;
        for(int i=0;i<ready.length();i++){JSONObject target=PlanExecutionPolicy.executionStep(plan,ready.getJSONObject(i).getString("stepId"));if(first==null)first=target;if(controls!=null&&controls.requirementsForPlan(target).optBoolean("allowed"))return target;}
        return first==null?plan:first;
    }
    private JSONArray applyControls(AutomationModule module,JSONObject plan,String action)throws Exception{
        JSONArray refs=plan.optJSONArray("taskControls"),results=new JSONArray();for(int i=0;refs!=null&&i<refs.length();i++){
            JSONObject expected=refs.getJSONObject(i);try{if(controls==null)throw new IllegalStateException("原生控制不可用");results.put(controls.controlPlanTask(module,expected,action));}
            catch(Exception unavailable){results.put(new JSONObject().put("taskId",expected.optString("taskId")).put("occurrenceId",expected.opt("occurrenceId")).put("requestedAction",action).put("controlApplied",false).put("status","requires_native_reconciliation").put("message",unavailable.getMessage()));}
        }return results;
    }
    /** Same AlarmHost pass, bounded and query-first. No model/network work takes place here. */
    public int onDue(AutomationModule module,long now,int maxCandidates)throws Exception{
        return onDue(module,now,maxCandidates,null);
    }
    /** An owner action targets its selected plan; the ordinary alarm retains its global rotation. */
    private int onDue(AutomationModule module,long now,int maxCandidates,String selectedPlan)throws Exception{
        if(controls==null||maxCandidates<=0||Thread.currentThread().isInterrupted())return 0;
        JSONArray all=module.handle("listPlans",new JSONObject().put("scope",scope())).getJSONArray("plans"),plans=all;
        if(selectedPlan!=null){plans=new JSONArray();for(int i=0;i<all.length();i++)if(selectedPlan.equals(all.getJSONObject(i).getString("id")))plans.put(all.getJSONObject(i));}
        if(plans.length()==0)return 0;
        int start=selectedPlan==null?Math.floorMod(planCursor.get(),plans.length()):0,scanned=0,admitted=0,limit=Math.min(4,maxCandidates);long stop=System.nanoTime()+1_000_000_000L;
        try{for(;scanned<Math.min(20,plans.length())&&admitted<limit;scanned++){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>=stop)break;JSONObject plan=plans.getJSONObject((start+scanned)%plans.length());if(plan.optJSONObject("execution")==null)continue;
            String id=plan.getString("id");JSONObject refreshed=module.refreshPlanTasks(scope(),id);JSONArray steps=refreshed.getJSONArray("steps");
            for(int j=0;j<steps.length();j++){String task=steps.getJSONObject(j).optString("taskId");if(!task.isEmpty())onTaskChanged(module,task);}
            plan=module.handle("getPlan",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("plan");
            if(!plan.optBoolean("approvalValid")||!plan.getJSONObject("execution").optString("state").equals("active")||!plan.getJSONObject("execution").optBoolean("started"))continue;
            steps=plan.getJSONArray("steps");boolean existing=false;
            for(int j=0;j<steps.length();j++){JSONObject step=steps.getJSONObject(j),intent=step.optJSONObject("taskAdmission");if(intent==null||step.optString("status").matches("completed|failed|cancelled|skipped"))continue;existing=true;
                if(!controls.requirementsForPlan(PlanExecutionPolicy.executionStep(plan,step.getString("id"))).optBoolean("allowed"))break;
                JSONObject request;try{request=module.planTaskRequest(scope(),id,step.getString("id"),intent.getString("occurrenceId"));}catch(IllegalStateException blocked){break;}
                JSONObject original=tasks.automationTask(intent.getString("occurrenceId"));
                if(original==null||original.optString("runId").isEmpty()){controls.admitPlanTask(module,request);admitted++;}
                else if(original.optString("status").matches("paused|interrupted")&&(selectedPlan!=null||tasks.automationConditionWaiting(original.getString("id")))){
                    JSONObject expected=new JSONObject().put("taskId",original.getString("id")).put("occurrenceId",intent.getString("occurrenceId"))
                            .put("commitmentId",step.getString("id")).put("runId",original.getString("runId")).put("coordinatorEpoch",original.getInt("coordinatorEpoch"));
                    controls.controlPlanTask(module,expected,"resume");admitted++;
                }
                break;
            }
            if(existing)continue;
            JSONArray ready=module.readyPlanTasks(scope(),id,50).getJSONArray("candidates");for(int j=0;j<ready.length();j++){JSONObject candidate=ready.getJSONObject(j);
                if(!controls.requirementsForPlan(PlanExecutionPolicy.executionStep(plan,candidate.getString("stepId"))).optBoolean("allowed"))continue;
                JSONObject prepared=module.preparePlanTask(scope(),id,candidate.getLong("planRevision"),candidate.getString("stepId"));if(prepared.optBoolean("eligible")||prepared.optBoolean("lookupOnly")){controls.admitPlanTask(module,prepared);admitted++;}break;
            }
        }}finally{if(selectedPlan==null)planCursor.set((start+Math.max(1,scanned))%plans.length());}
        return admitted;
    }

    /** Caller is the native local-owner surface, not a model/channel-supplied identity. */
    public JSONObject handle(AutomationModule module,String command,JSONObject payload)throws Exception{
        return handle(module,command,payload,false);
    }
    private JSONObject handle(AutomationModule module,String command,JSONObject payload,boolean nativeOwner)throws Exception{
        if(!handles(command))throw new IllegalArgumentException("不是承诺或计划宿主命令");
        JSONObject input=new JSONObject(payload.toString()).put("scope",scope());
        if(command.equals("createPlan")){
            JSONObject plan=input.getJSONObject("plan");JSONArray steps=plan.getJSONArray("steps");
            for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);validateTask(step.optString("taskId"));if(step.has("remoteWait")&&!step.isNull("remoteWait")){if(!nativeOwner)throw new SecurityException("等待私人电脑任务须在原生机主目标页明确选择");validateRemoteWait(step);}if(step.has("businessWait")&&!step.isNull("businessWait")){if(!nativeOwner)throw new SecurityException("认证入站来源须由主人在原生目标页明确选择并审阅");validateBusinessWait(step);}}
            plan.put("sourceRef",tasks.recordCommitmentDelegation(plan));
        }else if(command.equals("createCommitment")){
            JSONObject declaration=input.getJSONObject("commitment");validateTask(declaration.optString("taskId"));
            declaration.put("sourceRef",tasks.recordCommitmentDelegation(declaration));
        }else if(command.equals("updateCommitment")&&input.optJSONObject("patch")!=null&&input.getJSONObject("patch").has("taskId")){
            validateTask(input.getJSONObject("patch").getString("taskId"));
        }else if(command.equals("completeCommitment")){
            JSONObject current=module.handle("getCommitment",new JSONObject().put("scope",scope()).put("id",input.getString("id"))).getJSONObject("commitment");
            if(input.getLong("revision")!=current.getLong("revision"))throw new IllegalStateException("承诺版本已变化，请刷新后确认");
            input.put("evidenceRef",tasks.recordOwnerCommitmentConfirmation(current));
        }
        JSONObject result=module.handle(command,input);
        if(command.equals("createPlan")){
            JSONArray steps=result.getJSONObject("plan").getJSONArray("steps");for(int i=0;i<steps.length();i++)tasks.bindCommitment(steps.getJSONObject(i));
        }else if(command.equals("createCommitment")||command.equals("updateCommitment")&&input.getJSONObject("patch").has("taskId")){
            tasks.bindCommitment(result.getJSONObject("commitment"));
        }
        return projectResult(result);
    }
    private void validateRemoteWait(JSONObject step)throws Exception{
        if(!step.has("remoteWait")||step.isNull("remoteWait"))return;
        JSONObject reference=dev.pocketengine.features.automation.PlanRemoteWait.standard(step.getJSONObject("remoteWait"));
        if(!dev.pocketengine.features.automation.PlanRemoteWait.sameIdentity(reference,tasks.automationRemoteWaitReference(reference.getString("taskId"))))throw new SecurityException("原电脑执行身份已变化，请明确重选并审阅");
    }
    private void validateBusinessWait(JSONObject step)throws Exception{if(step.optJSONObject("businessWait")==null)return;var nativeSource=businessEvents;if(nativeSource==null)throw new SecurityException("原认证渠道来源尚不可核对");nativeSource.requireCurrent(step.getJSONObject("businessWait"));}
    private void validateBusinessWaitPlan(JSONObject plan)throws Exception{JSONArray steps=plan.getJSONArray("steps");for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(!step.optString("status").matches("completed|failed|cancelled|skipped")&&step.optString("taskId").isEmpty()&&step.optJSONObject("taskAdmission")==null)validateBusinessWait(step);}}
    private void validateRemoteWaitPlan(JSONObject plan)throws Exception{
        JSONArray steps=plan.getJSONArray("steps");for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(!step.optString("status").matches("completed|failed|cancelled|skipped")&&step.optString("taskId").isEmpty()&&step.optJSONObject("taskAdmission")==null)validateRemoteWait(step);}
    }

    /** Called after the original native task changes. Failures/stops never manufacture completion. */
    public void onTaskChanged(AutomationModule module,String taskId)throws Exception{
        verifyArtifactResult(module,taskId);
        JSONObject originalTask=tasks.get(taskId,false);JSONObject origin=originalTask==null?null:originalTask.optJSONObject("origin");
        if(originalTask!=null&&"remote".equals(originalTask.optString("feature")))module.remoteTaskChanged(scope(),taskId);
        if(origin!=null&&origin.optString("kind").equals("automation")&&!origin.optString("planId").isEmpty())module.refreshPlanTasks(scope(),origin.getString("planId"));
        final JSONArray bindings;
        synchronized(tasks){JSONObject task=tasks.get(taskId,false);if(task==null||!verified(task))return;bindings=task.optJSONArray("commitmentBindings");}
        // CommitmentBook resolves evidence while holding its own lock. Never enter it with the
        // TaskStore monitor held: domain -> TaskStore is the single evidence lock order.
        for(int i=0;bindings!=null&&i<bindings.length();i++){
            JSONObject binding=bindings.getJSONObject(i);
            JSONObject current=module.handle("getCommitment",new JSONObject().put("scope",scope()).put("id",binding.getString("commitmentId"))).getJSONObject("commitment");
            if(current.optString("status").matches("completed|cancelled|failed|skipped"))continue;
            if(current.getLong("revision")!=binding.getLong("commitmentRevision")||!taskId.equals(current.optString("taskId")))continue;
            JSONObject ref=tasks.recordTaskCommitmentCompletion(taskId,binding);
            module.onCommitmentTaskCompleted(scope(),current.getString("id"),current.getLong("revision"),ref.getString("id"),taskId,ref);
        }
    }
    /** Read Book and real file outside the TaskStore monitor, then fence the original commit. */
    private void verifyArtifactResult(AutomationModule module,String taskId)throws Exception{
        if(artifactVerifier==null)return;JSONObject task=tasks.get(taskId,false);
        if(task==null||!task.optString("status").matches("needs_confirmation|awaiting_user|completed")||tasks.hasUnresolved(taskId)||!tasks.automationRunSettled(taskId))return;privateOwner(task);
        JSONArray bindings=task.optJSONArray("commitmentBindings");for(int i=0;bindings!=null&&i<bindings.length();i++){
            JSONObject binding=bindings.getJSONObject(i),promise=module.handle("getCommitment",new JSONObject().put("scope",scope()).put("id",binding.getString("commitmentId"))).getJSONObject("commitment");
            if(!taskId.equals(promise.optString("taskId"))||binding.getLong("commitmentRevision")!=promise.getLong("revision")||promise.optString("status").matches("completed|failed|cancelled|skipped"))continue;
            JSONObject standard=promise.optJSONObject("verification");if(standard==null||!standard.optString("kind").equals("workspace_artifact"))continue;
            JSONObject proof=artifactVerifier.verify(task,standard);if(proof!=null)tasks.recordAutomationArtifactResult(taskId,binding,standard,proof);
        }
    }

    /** Domain evidence lookup rechecks the current original task at the actual completion fence. */
    public JSONObject resolveEvidence(JSONObject scope,JSONObject commitment,String kind,String id)throws Exception{
        if("business_wait_condition".equals(kind)){if(Thread.holdsLock(tasks))throw new SecurityException("原认证入站事实必须在Tasks锁外核验");var nativeSource=businessEvents;JSONObject reference=commitment.optJSONObject("businessWait");if(nativeSource==null||!"owner".equals(scope.optString("ownerId"))||!"private".equals(scope.optString("spaceId"))||reference==null||!id.equals(dev.pocketengine.features.automation.PlanBusinessWait.anchorId(reference)))return null;JSONObject intent=commitment.optJSONObject("taskAdmission");return nativeSource.observe(reference,commitment.optString("businessWaitCursor"),intent==null?null:intent.optJSONObject("businessWaitEvidence"));}
        synchronized(tasks){
            if("remote_wait_condition".equals(kind)){
                JSONObject reference=commitment.optJSONObject("remoteWait");if(!"owner".equals(scope.optString("ownerId"))||!"private".equals(scope.optString("spaceId"))||reference==null||!id.equals(reference.optString("taskId")))return null;
                return tasks.automationRemoteWaitObservation(reference);
            }
            if("task_state".equals(kind)){
                if(!"owner".equals(scope.optString("ownerId"))||!"private".equals(scope.optString("spaceId"))||!id.equals(commitment.optString("taskId")))return null;
                JSONObject task=tasks.get(id,false);if(task==null)return null;privateOwner(task);boolean bound=false;
                JSONArray bindings=task.optJSONArray("commitmentBindings");for(int i=0;bindings!=null&&i<bindings.length();i++)if(commitment.optString("id").equals(bindings.getJSONObject(i).optString("commitmentId")))bound=true;
                if(!bound)return null;
                return taskState(task).put("commitmentId",commitment.getString("id")).put("commitmentRevision",commitment.getLong("revision"));
            }
            JSONObject evidence=tasks.resolveCommitmentEvidence(scope,commitment,kind,id);if(evidence==null)return null;
            if("task_result".equals(kind)){
                JSONObject task=tasks.get(evidence.optString("taskId"),false);
                if(task==null||!commitment.optString("taskId").equals(task.optString("id")))return null;
                privateOwner(task);if(!verified(task))return null;
                if("native_workspace_artifact_standard".equals(evidence.optString("source"))){
                    if(artifactVerifier==null)return null;JSONObject actual=artifactVerifier.verify(task,commitment.optJSONObject("verification"));
                    if(actual==null||!TaskStore.capabilityFingerprint(actual).equals(TaskStore.capabilityFingerprint(evidence.getJSONObject("verification"))))return null;
                }
            }
            return evidence;
        }
    }

    /** Whole-SQLite original-occurrence lookup; not the recent-200 task-list projection. No writes. */
    @Override public JSONObject resolve(String occurrenceId)throws Exception{
        synchronized(tasks){
            JSONObject task=tasks.automationTask(occurrenceId);if(task==null)return null;privateOwner(task);
            JSONObject origin=task.getJSONObject("origin");
            if(!"automation".equals(origin.optString("kind"))||!occurrenceId.equals(origin.optString("occurrenceId")))throw new SecurityException("提醒原任务身份不一致");
            long revision=task.optLong("automationRuleRevision",task.optLong("automationRevision",0));
            if(origin.has("ruleRevision"))revision=origin.getLong("ruleRevision");
            String rule=task.optString("automationRuleId",origin.optString("ruleId"));
            boolean checked=verified(task);RunControl control=controls==null?null:controls.get(task.getString("id"));
            boolean live=control!=null&&control.hasHostRun()&&control.matchesHostRun(task.getString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"))
                    &&!control.isCancelled()&&!control.isPaused()&&!task.optBoolean("cancelRequested")
                    &&task.optString("status").matches("preparing|running|observing|planning|acting")&&!tasks.hasUnresolved(task.getString("id"));
            if(live)try{live=controls.authorizationValid(new JSONObject(task.toString()));}catch(Exception unavailable){live=false;}
            JSONObject result=new JSONObject().put("taskId",task.getString("id")).put("occurrenceId",occurrenceId).put("ruleId",rule).put("ruleRevision",revision)
                    .put("actorId","owner").put("spaceId","private").put("goal",task.getString("goal")).put("status",task.getString("status"))
                    .put("authorizationValid",live).put("resultVerified",checked).put("runId",task.optString("runId")).put("coordinatorEpoch",task.optInt("coordinatorEpoch"))
                    .put("terminalVerified",task.optString("status").matches("failed|cancelled")&&settled(task,new java.util.HashSet<>()));
            if(checked){JSONObject confirmation=latestConfirmation(task);result.put("verificationSource",tasks.automationArtifactVerified(task.getString("id"))?"native_workspace_artifact_standard":"owner_confirmation");
                if(confirmation!=null)result.put("evidenceRef",new JSONObject().put("kind","owner_confirmation").put("id",task.getString("id")+":"+confirmation.getLong("seq")));
            }
            return result;
        }
    }
    private JSONObject taskState(JSONObject task)throws Exception{
        return new JSONObject().put("taskId",task.getString("id")).put("actorId","owner").put("spaceId","private").put("status",task.getString("status"))
                .put("runId",task.optString("runId")).put("coordinatorEpoch",task.optInt("coordinatorEpoch"))
                .put("resultVerified",verified(task)).put("terminalVerified",task.optString("status").matches("failed|cancelled")&&settled(task,new java.util.HashSet<>()));
    }
    private boolean settled(JSONObject task,java.util.Set<String> seen)throws Exception{
        String id=task.getString("id");if(seen.size()>=100||!seen.add(id)||tasks.hasUnresolved(id))return false;
        if(!task.optString("runId").isEmpty()&&!tasks.automationRunSettled(id))return false;
        JSONArray operations=task.optJSONArray("operations");for(int i=0;operations!=null&&i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);if("remote_message".equals(operation.optString("tool"))&&!"message_receipt".equals(operation.optString("state")))return false;}
        JSONArray related=task.optJSONArray("relatedTasks");for(int i=0;related!=null&&i<related.length();i++){
            JSONObject child=tasks.get(related.getJSONObject(i).optString("taskId"),false);if(child==null||!child.optString("status").matches("completed|failed|cancelled")||!settled(child,seen))return false;
        }return true;
    }
    private void validateTask(String id)throws Exception{if(!id.isEmpty()){JSONObject task=tasks.get(id,false);if(task==null)throw new IllegalArgumentException("关联任务不存在");privateOwner(task);}}
    private static void privateOwner(JSONObject task){if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("不能关联其他身份或空间的任务");}
    private boolean verified(JSONObject task)throws Exception{JSONObject confirmation=latestConfirmation(task);return "completed".equals(task.optString("status"))&&!tasks.hasUnresolved(task.getString("id"))&&(tasks.automationArtifactVerified(task.getString("id"))||confirmation!=null&&Boolean.TRUE.equals(confirmation.opt("completed"))&&confirmation.optLong("seq")>0);}
    private static JSONObject latestConfirmation(JSONObject task){JSONArray events=task.optJSONArray("events");for(int i=events==null?-1:events.length()-1;i>=0;i--){JSONObject event=events.optJSONObject(i);if(event!=null&&"user_verification".equals(event.optString("type")))return event;}return null;}
    private JSONObject projectResult(JSONObject result)throws Exception{
        if(result.optJSONObject("plan")!=null)projectPlan(result.getJSONObject("plan"));
        JSONArray plans=result.optJSONArray("plans");for(int i=0;plans!=null&&i<plans.length();i++)projectPlan(plans.getJSONObject(i));
        if(result.optJSONObject("commitment")!=null)projectStep(result.getJSONObject("commitment"));
        JSONArray steps=result.optJSONArray("commitments");for(int i=0;steps!=null&&i<steps.length();i++)projectStep(steps.getJSONObject(i));
        return result;
    }
    private void projectPlan(JSONObject plan)throws Exception{
        JSONArray steps=plan.getJSONArray("steps");String first="completed";boolean found=false;
        for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);projectStep(step);if(!found&&!step.optString("status").equals("completed")){first=step.getString("taskState");found=true;}}
        if(plan.optJSONObject("nextStep")!=null)projectStep(plan.getJSONObject("nextStep"));
        plan.put("taskState",first).put("taskStateAuthority","TaskStore").put("executionAuthorized",false);
    }
    private void projectStep(JSONObject step)throws Exception{
        String id=step.optString("taskId"),state;
        if(id.isEmpty()&&step.optString("status").equals("cancelled"))state="cancelled";
        else if(id.isEmpty()&&step.optString("lifecycleState").equals("expired"))state="expired";
        else if(id.isEmpty())state=step.optString("status").equals("completed")?"completed":"unbound";
        else{
            JSONObject task=tasks.get(id,false);
            if(task==null)state="unavailable";
            else{
                privateOwner(task);String status=task.optString("status");
                step.put("taskStatus",status).put("taskRunId",task.optString("runId")).put("taskEpoch",task.optInt("coordinatorEpoch"));
                boolean pendingApproval=false;JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if("pending".equals(approval.optString("state"))&&approval.optLong("expiresAt")>System.currentTimeMillis())pendingApproval=true;}
                step.put("taskNeedsApproval",pendingApproval);
                if(tasks.hasUnresolved(id))state="unknown";
                else if(task.optBoolean("cancelRequested"))state="revoked";
                else if(task.optString("runId").isEmpty()&&status.matches("preparing|interrupted")&&task.optJSONObject("origin")!=null&&!task.getJSONObject("origin").optString("planId").isEmpty())state="registered";
                else if(status.equals("completed"))state=verified(task)?"completed":"unverified_result";
                else if(status.matches("failed|cancelled|paused|stopping|reconciling|awaiting_user|interrupted"))state=status;
                else state=status.matches("preparing|running|observing|planning|acting")?"running":"awaiting_user";
            }
        }
        step.put("taskState",state).put("taskStateAuthority","TaskStore").put("executionAuthorized",false);
    }
}
