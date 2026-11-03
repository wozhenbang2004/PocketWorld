package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native orchestration glue; original TaskStore, RunControl, AgentRunner and workspace remain authoritative. */
public final class TaskStoreTeamWriteHost implements TeamCoordinator.BudgetHost,TeamCoordinator.AssignmentFactory,TeamCoordinator.AssignmentAnswers,TeamCoordinator.AssignmentMessages,AutoCloseable {
    private final TaskStore tasks;private final TeamWorkFiles.Workspace workspace;private final TaskStoreTeamAssignments originalTasks;private final TeamAssignmentHost assignments;
    private volatile TeamProjectWorkspaces projects;
    public synchronized void setProjectProduction(TeamProjectWorkspaces projects){if(this.projects!=null)throw new IllegalStateException("完整项目团队宿主已挂载");this.projects=java.util.Objects.requireNonNull(projects);assignments.setProjects(projects);}
    public boolean projectProductionConfigured(){return projects!=null;}
    private final Consumer<String> changed;private final ConcurrentHashMap<String,RunControl> integrations=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,RunControl> integrationTasks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,RunControl> reviews=new ConcurrentHashMap<>();
    private static void closeFence(java.util.concurrent.atomic.AtomicReference<AutoCloseable> fence)throws Exception {AutoCloseable retained=fence.getAndSet(null);if(retained!=null)retained.close();}
    public TaskStoreTeamWriteHost(TaskStore tasks,TeamWorkFiles.Workspace workspace,Consumer<String> changed){this.tasks=java.util.Objects.requireNonNull(tasks);this.workspace=java.util.Objects.requireNonNull(workspace);this.changed=java.util.Objects.requireNonNull(changed);originalTasks=new TaskStoreTeamAssignments(tasks,changed);assignments=new TeamAssignmentHost(originalTasks,workspace);}
    @Override public JSONObject execute(TeamCoordinator.AssignmentCall call,ModelConfig model,RunControl original,Consumer<JSONObject> progress)throws Exception {return assignments.execute(call,model,original,progress);}
    @Override public void initialize(TeamCoordinator.InitialBudgetPlan plan)throws Exception{tasks.bindTeamCoordinatorBudget(plan);}
    @Override public JSONObject reserveInference(TeamCoordinator.InferenceCall call)throws Exception{return tasks.reserveTeamCoordinatorInference(call);}
    @Override public void releaseUnstarted(TeamCoordinator.InferenceCall call)throws Exception{tasks.releaseTeamCoordinatorInference(call);}
    @Override public JSONObject snapshot(String teamRun)throws Exception{return tasks.teamBudgetSnapshot(teamRun);}
    @Override public JSONObject beginRead(TeamCoordinator.InferenceCall call,String tool,JSONObject args)throws Exception{return tasks.beginTeamCoordinatorRead(call,tool,args);}
    @Override public void finishRead(JSONObject original,JSONObject receipt,boolean unknown)throws Exception{tasks.finishTeamNativeRead(original,receipt,unknown);}
    private Consumer<Runnable> parentCleanup;
    public void setParentCleanup(Consumer<Runnable> originalQueue){if(parentCleanup!=null)throw new SecurityException("原父协作停止队列已经接入");parentCleanup=java.util.Objects.requireNonNull(originalQueue);}
    TeamParentHost parentHost(TeamCoordinator coordinator,CurrentModel model,TeamParentHost.Definitions definitions){if(parentCleanup==null)return null;return new TeamParentHost(tasks,workspace,coordinator,this,model,definitions,changed,parentCleanup);}
    public void setRemoteProduction(TeamAssignmentHost.RemoteProduction production){assignments.setRemoteProduction(production);}
    public boolean remoteProductionConfigured(){return assignments.remoteProductionConfigured();}
    public TeamWriteScope captureScope(String parent,String parentRun,String project,JSONObject reviewedDefinition)throws Exception{return TeamWriteScope.capture(workspace,parent,parentRun,project,reviewedDefinition,assignments.remoteSelections());}
    @Override public void ownerBudgetChanged(JSONObject currentNativePlan)throws Exception {tasks.updateTeamBudgetFromNativeResume(currentNativePlan);}
    @Override public boolean hasActiveWork(String teamRunId){return integrations.containsKey(teamRunId)||reviews.containsKey(teamRunId)||assignments.hasActiveRemote(teamRunId);}
    @Override public void stopAssignments(String teamRunId,String memberId)throws Exception{assignments.stopRemote(teamRunId,memberId);}
    public RunControl currentControl(String taskId){RunControl member=assignments.currentControl(taskId);return member==null?integrationTasks.get(taskId):member;}
    /** Native owner page, not EngineModule.handle/model JSON. Closing the page never revives a task. */
    public DraftScopePage newDraftScopePage(TeamCoordinator coordinator,String runId)throws Exception{return new DraftScopePage(coordinator,runId);}
    @Override public JSONObject workPlan(String run)throws Exception{return tasks.teamWorkPlanSnapshot(run);}
    @Override public JSONObject finishWorkItem(TeamCoordinator.AssignmentCall call)throws Exception{return tasks.finishTeamWorkItem(call);}
    public interface CurrentModel {ModelConfig get()throws Exception;}
    public WorkPlanPage newWorkPlanPage(TeamCoordinator coordinator,String run,CurrentModel models)throws Exception{return new WorkPlanPage(coordinator,run,models);}
    /** The original conversation owns this page. Native foreground review, never a model command. */
    public final class WorkPlanPage implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String run;private final TaskStore.TeamWorkWindow window;private final CurrentModel models;private volatile boolean alive=true;
        private TeamCoordinator.WorkPlanSource source;private TaskStore.TeamWorkReview pending;private TeamWorkItems.Plan projectPlan;private TaskStore.TeamWakePolicyReview pendingWake;
        private TeamCoordinator.WorkMutationSource mutation;private TaskStore.TeamWorkRevisionReview pendingRevision;private JSONObject revisionOptions;private TeamWorkHandoff.Plan handoffPlan;private TaskStore.TeamIntegrationBudgetReview pendingIntegrationBudget;private TaskStore.TeamIntegratorScopeReview pendingIntegratorScope;private TeamProjectWorkspaces.IntegratorScopeCapture pendingIntegratorCapture;
        private WorkPlanPage(TeamCoordinator coordinator,String run,CurrentModel models)throws Exception{this.coordinator=coordinator;this.run=run;this.models=java.util.Objects.requireNonNull(models);window=tasks.openTeamWorkWindow(run);}
        public void setForeground(boolean foreground){window.setForeground(alive&&foreground);}
        private void require(){if(!alive)throw new SecurityException("原工作计划窗口已关闭");}
        public JSONObject snapshot()throws Exception{require();return tasks.teamWorkPlanSnapshot(run).put("executionTree",tasks.teamExecutionTreeForOwner(run,16));}
        public JSONObject parent()throws Exception{require();return tasks.teamWorkPlanParent(run);}
        public synchronized JSONObject wakePolicyStatus()throws Exception{require();return tasks.teamWakeStateForOwner(window);}
        public synchronized TaskStore.TeamWakePolicyReview reviewWakePolicy(long expectedRevision,boolean enabled,int perRound,int cumulative,long expiresAt)throws Exception{
            require();pendingWake=null;pendingWake=tasks.prepareTeamWakePolicy(window,expectedRevision,new TeamWakeQueue.OwnerPolicy(enabled,java.util.Set.of(TeamWakeQueue.SourceKind.AUTHORIZED_CONTROL),perRound,cumulative,expiresAt));return pendingWake;
        }
        /** Saving changes only this original policy, never starts a child or refills the parent pool. */
        public synchronized JSONObject approveWakePolicy(TaskStore.TeamWakePolicyReview exact,String digest)throws Exception{
            require();if(exact==null||exact!=pendingWake)throw new SecurityException("请先审阅本页原父消息唤醒策略");JSONObject saved=tasks.approveTeamWakePolicy(exact,digest);pendingWake=null;changed.accept(run);return saved;
        }
        public synchronized JSONObject integrationBudgetStatus()throws Exception{require();return tasks.teamIntegrationBudgetStatus(window,coordinator.workMutationSource(run));}
        public synchronized TaskStore.TeamIntegrationBudgetReview reviewIntegrationBudget(int newModelLimit)throws Exception{require();pendingIntegrationBudget=tasks.reviewTeamIntegrationBudget(window,coordinator.workMutationSource(run),newModelLimit);return pendingIntegrationBudget;}
        public synchronized JSONObject approveIntegrationBudget(TaskStore.TeamIntegrationBudgetReview exact,String digest)throws Exception {
            require();if(exact==null||pendingIntegrationBudget!=exact)throw new SecurityException("请先审阅本页原整合者准确pool预算");JSONObject saved=tasks.approveTeamIntegrationBudget(exact,digest);pendingIntegrationBudget=null;
            try{coordinator.synchronizeIntegrationBudget(run);}catch(Exception failure){throw new IllegalStateException("原SQL预算已保存，尚未启动；协调快照同步失败。请只刷新原预算，不重复批准或执行。",failure);}changed.accept(run);return saved;
        }
        public synchronized JSONObject integratorScopeStatus()throws Exception{require();return tasks.teamIntegratorScopeSource(window,coordinator.workMutationSource(run)).view();}
        public synchronized TaskStore.TeamIntegratorScopeReview reviewIntegratorScope(long deadlineAt)throws Exception {
            require();if(projects==null)throw new SecurityException("原完整项目宿主尚未挂载");pendingIntegratorScope=null;TaskStore.TeamIntegratorScopeSource exact=tasks.teamIntegratorScopeSource(window,coordinator.workMutationSource(run));pendingIntegratorCapture=projects.captureIntegratorScope(exact);pendingIntegratorScope=tasks.prepareTeamIntegratorScope(exact,pendingIntegratorCapture,deadlineAt);return pendingIntegratorScope;
        }
        public synchronized JSONObject approveIntegratorScope(TaskStore.TeamIntegratorScopeReview exact,String digest)throws Exception {
            require();if(exact==null||exact!=pendingIntegratorScope||pendingIntegratorCapture==null)throw new SecurityException("请先审阅本页准确整合者范围");pendingIntegratorCapture.checkCurrent();JSONObject saved=tasks.approveTeamIntegratorScope(exact,digest);pendingIntegratorScope=null;pendingIntegratorCapture=null;changed.accept(run);return saved;
        }
        public synchronized JSONObject synchronizeIntegrationBudget()throws Exception{require();tasks.teamIntegrationBudgetStatus(window,coordinator.workMutationSource(run));coordinator.synchronizeIntegrationBudget(run);return integrationBudgetStatus();}
        public synchronized TaskStore.TeamWorkReview review(JSONArray items)throws Exception{require();projectPlan=null;pendingRevision=null;mutation=null;source=coordinator.workPlanSource(run);TeamWorkItems.Plan captured=TeamWorkItems.capture(workspace,source,tasks.teamWorkPlanParent(run),items);pending=tasks.prepareTeamWorkPlan(window,captured);return pending;}
        public synchronized JSONObject approve(TaskStore.TeamWorkReview review,String digest)throws Exception{require();if(review==null||pending!=review||source==null)throw new SecurityException("请先审阅此窗口的精确工作项/文件/预算");if(projectPlan!=null)projects.checkSelectionCurrent(projectPlan.projectSelection());JSONObject continued=coordinator.startWorkItems(source,()->tasks.approveTeamWorkPlan(review,digest));pending=null;tasks.updateExternal(run,continued);changed.accept(run);return continued;}
        public synchronized JSONObject projectAvailability()throws Exception{require();return new JSONObject().put("available",projects!=null).put("parent",tasks.teamProjectParentSelectionFacts(run)).put("toolEngineeringLimit",TeamParentBudget.MAX_PROJECT_TOOL_OPERATIONS).put("sameUidExecutionRequired",true).put("osSandboxVerified",false);}
        public synchronized JSONArray projectCommandSuggestions()throws Exception{require();if(projects==null)throw new SecurityException("原完整项目宿主尚未挂载");return projects.commandSuggestions(tasks.teamProjectParentSelectionFacts(run));}
        public synchronized TaskStore.TeamWorkReview reviewProject(JSONArray items,JSONObject options)throws Exception {
            require();if(projects==null)throw new SecurityException("原完整项目能力未挂载，不降级成文字制作");pending=null;pendingRevision=null;mutation=null;source=coordinator.workPlanSource(run);TeamWorkItems.Plan captured=TeamWorkItems.capture(workspace,source,tasks.teamWorkPlanParent(run),items);TeamProjectWorkspaces.Selection selection=projects.capture(captured,tasks.teamProjectParentSelectionFacts(run),options);projectPlan=TeamWorkItems.withProject(captured,selection);pending=tasks.prepareTeamWorkPlan(window,projectPlan);return pending;
        }
        public synchronized TaskStore.TeamWorkReview approveParentBudget(TaskStore.TeamWorkReview exact,String digest)throws Exception {
            require();if(exact==null||pending!=exact)throw new SecurityException("不是本窗口完整项目的预算审阅");if(handoffPlan!=null){TeamWorkHandoff.checkDraft(workspace,handoffPlan);if(handoffPlan.projectSelection()!=null)projects.checkSelectionCurrent(handoffPlan.projectSelection());JSONObject shown=handoffPlan.view(),item=shown.getJSONObject("workItem");tasks.approveTeamWorkHandoffParentBudget(exact,digest);return reviewTransfer(item.getString("workItemId"),shown.getString("newMemberId"),item.getInt("itemRevision"),item.getInt("ownerGeneration"));}if(projectPlan==null)throw new SecurityException("没有原完整项目范围");projects.checkSelectionCurrent(projectPlan.projectSelection());pending=tasks.approveTeamProjectParentBudget(exact,digest);return pending;
        }
        public synchronized TaskStore.TeamWorkReview reviewTransfer(String item,String member,int itemRevision,int ownerGeneration)throws Exception{
            require();pendingRevision=null;source=null;handoffPlan=null;JSONObject current=TeamWorkItems.find(tasks.teamWorkPlanSnapshot(run).getJSONObject("workPlan").getJSONArray("items"),item),task=current.optJSONObject("assignmentTask");
            mutation=coordinator.workMutationSource(run);TaskStore.TeamWorkHandoffSource nativeSource=tasks.teamWorkHandoffSource(window,mutation,item,member,itemRevision,ownerGeneration);handoffPlan=TeamWorkHandoff.capture(workspace,nativeSource);if(nativeSource.view().has("projectContext")){if(projects==null)throw new SecurityException("原完整副本宿主未挂载，不能冒文字接手");handoffPlan=TeamWorkHandoff.withProject(handoffPlan,projects.captureHandoff(handoffPlan));}pending=tasks.prepareTeamWorkHandoff(handoffPlan);return pending;
        }
        public synchronized JSONObject approveTransfer(TaskStore.TeamWorkReview review,String digest)throws Exception{require();if(review==null||pending!=review||source!=null)throw new SecurityException("请先审阅此窗口的原工作项交接");JSONObject continued;if(handoffPlan!=null){TeamWorkHandoff.checkDraft(workspace,handoffPlan);if(handoffPlan.projectSelection()!=null)projects.checkSelectionCurrent(handoffPlan.projectSelection());continued=coordinator.approveWorkHandoff(mutation,()->{require();TeamWorkHandoff.checkDraft(workspace,handoffPlan);if(handoffPlan.projectSelection()!=null)projects.checkSelectionCurrent(handoffPlan.projectSelection());return tasks.approveTeamWorkHandoff(review,digest);},models.get());handoffPlan=null;mutation=null;}else{JSONObject committed=tasks.approveTeamWorkTransfer(review,digest);continued=coordinator.refreshWorkItems(run,committed);}pending=null;tasks.updateExternal(run,continued);changed.accept(run);return continued;}
        public synchronized TaskStore.TeamWorkReview reviewContinuation(String item,int itemRevision,int ownerGeneration)throws Exception{require();pendingRevision=null;mutation=null;source=null;pending=tasks.prepareTeamWorkContinuation(window,item,itemRevision,ownerGeneration);return pending;}
        public synchronized JSONObject approveContinuation(TaskStore.TeamWorkReview review,String digest)throws Exception{require();if(review==null||pending!=review||source!=null)throw new SecurityException("请先审阅本窗口原工作项继续");JSONObject shown=review.view();JSONObject continued=coordinator.continueWorkItem(run,shown.getJSONObject("workItem").getString("workItemId"),(runGeneration,memberGeneration)->tasks.approveTeamWorkContinuation(review,digest,runGeneration,memberGeneration),models.get());pending=null;tasks.updateExternal(run,continued);changed.accept(run);return continued;}
        private void requireRevisionBase(TaskStore.TeamWorkRevisionSource source)throws Exception {
            source.checkCurrent();JSONObject original=source.view().getJSONObject("integration");if(integrations.containsKey(run)||reviews.containsKey(run)||!TeamIntegrationFiles.progress(workspace,original.getJSONObject("binding"),original.getJSONArray("receipts")).getBoolean("complete"))throw new SecurityException("原成果当前文件已变化，或原独审/整合仍在执行；请重新核对真实差异");source.checkCurrent();
        }
        public synchronized TaskStore.TeamWorkRevisionReview reviewRevision(String item,int itemRevision,int ownerGeneration,String text)throws Exception {
            require();if(coordinator.get(run).getJSONObject("productionScope").has("projectSelection"))throw new SecurityException("完整项目V2必须重新显选准确全基线和命令/源码范围；此文字修订入口尚未接通，原授权不变");pending=null;source=null;pendingRevision=null;mutation=coordinator.workMutationSource(run);TaskStore.TeamWorkRevisionSource selected=tasks.teamWorkRevisionSource(window,mutation,item,itemRevision,ownerGeneration,text);requireRevisionBase(selected);TeamWorkItemRevision.Plan captured=TeamWorkItemRevision.capture(workspace,selected);pendingRevision=tasks.prepareTeamWorkRevision(captured);return pendingRevision;
        }
        public synchronized JSONArray projectRevisionCommandSuggestions()throws Exception {require();if(projects==null)throw new SecurityException("原完整项目宿主未挂载");TaskStore.TeamProjectPackageSelection result=tasks.teamProjectPackageForOwner(run);result.checkCurrent();String checkout=result.view().getJSONObject("evidence").getString("checkoutId"),runtime=coordinator.get(run).getJSONObject("productionScope").getJSONObject("projectSelection").getString("runtimeId");return projects.commandSuggestions(new JSONObject().put("developmentContext",new JSONObject().put("checkoutId",checkout).put("runtime",runtime)));}
        public synchronized TaskStore.TeamWorkRevisionReview reviewProjectRevision(String item,int itemRevision,int ownerGeneration,String text,JSONObject options)throws Exception {
            require();if(projects==null)throw new SecurityException("完整项目宿主尚未挂载");pending=null;source=null;pendingRevision=null;mutation=coordinator.workMutationSource(run);TaskStore.TeamWorkRevisionSource selected=tasks.teamWorkRevisionSource(window,mutation,item,itemRevision,ownerGeneration,text);requireRevisionBase(selected);TeamWorkItemRevision.Plan base=TeamWorkItemRevision.capture(workspace,selected);TeamProjectWorkspaces.Selection nativeSelection=projects.captureRevision(base,options);TeamWorkItemRevision.Plan captured=TeamWorkItemRevision.withProject(base,nativeSelection);JSONArray affected=base.view().getJSONArray("affected");for(int i=0;i<affected.length();i++){JSONObject affectedItem=affected.getJSONObject(i);if(affectedItem.has("remoteSelection"))captured=TeamWorkItemRevision.withRemote(captured,affectedItem.getString("workItemId"),assignments.captureRemoteWorkRevision(selected,affectedItem.getJSONArray("newFiles")));}projects.checkSelectionCurrent(nativeSelection);requireRevisionBase(selected);revisionOptions=new JSONObject(options.toString());pendingRevision=tasks.prepareTeamWorkRevision(captured);return pendingRevision;
        }
        public synchronized TaskStore.TeamWorkRevisionReview approveRevisionParentBudget(TaskStore.TeamWorkRevisionReview exact,String digest)throws Exception {
            require();if(exact==null||pendingRevision!=exact||revisionOptions==null)throw new SecurityException("请先审阅本窗口完整项目修订预算");JSONObject prior=exact.source().view(),item=TeamWorkItems.find(prior.getJSONObject("workPlan").getJSONArray("items"),prior.getString("workItemId")),options=new JSONObject(revisionOptions.toString());tasks.approveTeamWorkRevisionParentBudget(exact,digest);pendingRevision=null;return reviewProjectRevision(item.getString("workItemId"),item.getInt("itemRevision"),item.getInt("ownerGeneration"),prior.getString("text"),options);
        }
        public synchronized JSONObject approveRevision(TaskStore.TeamWorkRevisionReview exact,String digest)throws Exception {
            require();if(exact==null||exact!=pendingRevision||mutation==null)throw new SecurityException("请先审阅此窗口准确工作项及受影响依赖的新基线与预算");JSONObject continued=coordinator.reviseWorkItems(mutation,()->{require();requireRevisionBase(exact.source());if(exact.projectSelection()!=null)projects.checkSelectionCurrent(exact.projectSelection());return tasks.approveTeamWorkRevision(exact,digest);},models.get());pendingRevision=null;mutation=null;tasks.updateExternal(run,continued);changed.accept(run);return continued;
        }
        public void close(){alive=false;window.close();}
    }
    public RemoteRevisionPage newRemoteRevisionPage(TeamCoordinator coordinator,String runId,String assignmentId)throws Exception{return new RemoteRevisionPage(coordinator,runId,assignmentId);}
    /** Native foreground owner window; no EngineModule/model command accepts revision authority. */
    public final class RemoteRevisionPage implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String run,assignment;private final TaskStore.TeamRemoteOwnerWindow window;
        private volatile boolean alive=true,foreground;private TeamCoordinator.DraftPlan plan;private TaskStore.TeamRemoteRevisionReview pending;
        private RemoteRevisionPage(TeamCoordinator coordinator,String run,String assignment){this.coordinator=coordinator;this.run=run;this.assignment=assignment;window=tasks.openTeamRemoteOwnerWindow();}
        public void setForeground(boolean value){foreground=alive&&value;window.setForeground(foreground);}
        private void require(){if(!alive||!foreground)throw new SecurityException("请回到原远端成果的前台审阅窗口");}
        public synchronized JSONObject snapshot()throws Exception{if(!alive)throw new SecurityException("原成果窗口已关闭");return tasks.originalTeamRemoteRevision(assignment);}
        private void currentHeads(TaskStore.TeamRemoteRevisionSource source)throws Exception{source.checkCurrent();JSONObject original=source.view().getJSONObject("integration");if(hasActiveWork(run)||!TeamIntegrationFiles.progress(workspace,original.getJSONObject("binding"),original.getJSONArray("receipts")).getBoolean("complete"))throw new SecurityException("当前成果或原独审/整合已变化；未改变原scope，不沿旧base覆盖");source.checkCurrent();}
        public synchronized TaskStore.TeamRemoteRevisionReview review(JSONObject exactRequest)throws Exception {
            require();if(!run.equals(exactRequest.getString("runId"))||!assignment.equals(exactRequest.getString("assignmentId")))throw new SecurityException("不是此窗口的原成果");pending=null;plan=coordinator.draftPlan(run);TaskStore.TeamRemoteRevisionSource source=tasks.reviewTeamRemoteRevision(window,plan,exactRequest);currentHeads(source);JSONObject facts=source.view();JSONArray paths=new JSONArray(),old=facts.getJSONArray("appliedHeads");for(int i=0;i<old.length();i++)paths.put(old.getJSONObject(i).getString("path"));JSONArray actual=TeamWorkFiles.captureFiles(workspace,facts.getJSONObject("originalScope").getString("targetProjectId"),paths);
            dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.Selection selection=assignments.captureRemoteRevision(source,actual);currentHeads(source);TeamWriteScope scope=TeamWriteScope.afterIntegrated(workspace,facts.getJSONObject("originalScope"),facts.getString("memberId"),facts.getJSONObject("integration").getString("reviewId"),facts.getJSONObject("integration").getString("planHash"),selection);pending=tasks.prepareTeamRemoteRevision(source,selection,scope);return pending;
        }
        public synchronized JSONObject approve(TaskStore.TeamRemoteRevisionReview review,String digest)throws Exception {
            require();if(review==null||review!=pending||plan==null)throw new SecurityException("请先显示并确认本窗口精确远端修订/预算");JSONObject shown=review.view();
            JSONObject result=coordinator.approveRemoteOutcomeRevision(plan,()->{require();currentHeads(review.source());return tasks.approveTeamRemoteRevision(review,digest,shown.getInt("proposedModelRequestLimit"),shown.getInt("proposedToolOperationLimit"));});pending=null;tasks.updateExternal(run,result);changed.accept(run);changed.accept(assignment);return result;
        }
        public synchronized JSONObject recoverRecorded()throws Exception {
            require();TaskStore.TeamRemoteRevisionRecord original=tasks.teamRemoteRevisionRecord(window,assignment);JSONObject result=coordinator.adoptRecordedRemoteRevision(run,original);tasks.updateExternal(run,result);changed.accept(run);return result;
        }
        @Override public void close(){alive=false;foreground=false;window.close();}
    }
    public PhoneIntegratorPage newPhoneIntegratorPage(TeamCoordinator coordinator,String run)throws Exception{return new PhoneIntegratorPage(coordinator,run);}
    public final class PhoneIntegratorPage implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String run;private final TaskStore.TeamWorkWindow window;private boolean closed;private TeamCoordinator.DraftPlan plan;private TaskStore.TeamPhoneIntegratorReview pending;private JSONObject options;
        private PhoneIntegratorPage(TeamCoordinator coordinator,String run)throws Exception{this.coordinator=coordinator;this.run=run;window=tasks.openTeamWorkWindow(run);}
        public void setForeground(boolean foreground){window.setForeground(!closed&&foreground);}
        private void require(){if(closed||projects==null)throw new SecurityException("原手机完整项目整合者宿主不可用");}
        public synchronized JSONObject snapshot()throws Exception{require();try{return tasks.teamPhoneIntegratorRecord(window).view().put("approved",true);}catch(org.json.JSONException notRecorded){/* No grant is inferred from an absent original record. */}plan=coordinator.draftPlan(run);return tasks.teamPhoneIntegratorSource(window,plan).view().put("approved",false);}
        public synchronized JSONArray commandSuggestions()throws Exception{require();return projects.commandSuggestions(tasks.teamProjectParentSelectionFacts(run));}
        public synchronized TaskStore.TeamPhoneIntegratorReview review(JSONObject nativeOptions)throws Exception{require();pending=null;plan=coordinator.draftPlan(run);TaskStore.TeamPhoneIntegratorSource source=tasks.teamPhoneIntegratorSource(window,plan);TeamProjectWorkspaces.Selection selection=projects.captureRemoteIntegrator(source,nativeOptions);projects.checkSelectionCurrent(selection);options=new JSONObject(nativeOptions.toString());pending=tasks.prepareTeamPhoneIntegrator(source,selection);return pending;}
        public synchronized TaskStore.TeamPhoneIntegratorReview approveParentBudget(TaskStore.TeamPhoneIntegratorReview exact,String digest)throws Exception{require();if(exact==null||pending!=exact||options==null)throw new SecurityException("请先审阅本窗口准确本机整合计划及预算缺口");projects.checkSelectionCurrent(exact.selection());JSONObject captured=new JSONObject(options.toString());tasks.approveTeamPhoneIntegratorParentBudget(exact,digest);pending=null;return review(captured);}
        public synchronized JSONObject approve(TaskStore.TeamPhoneIntegratorReview exact,String digest)throws Exception{require();if(exact==null||pending!=exact||plan==null)throw new SecurityException("不是本前台刚才的准确手机整合者审阅");projects.checkSelectionCurrent(exact.selection());JSONObject result=coordinator.approvePhoneIntegrator(plan,()->tasks.approveTeamPhoneIntegrator(exact,digest));pending=null;tasks.updateExternal(run,result);changed.accept(run);return result;}
        public synchronized JSONObject synchronizeRecorded()throws Exception{require();JSONObject result=coordinator.adoptRecordedPhoneIntegrator(run,tasks.teamPhoneIntegratorRecord(window));tasks.updateExternal(run,result);changed.accept(run);return result;}
        public synchronized void close(){closed=true;pending=null;window.close();}
    }
    public RemoteScopePage newRemoteScopePage(TeamCoordinator coordinator,String runId,String assignmentId)throws Exception{return new RemoteScopePage(coordinator,runId,assignmentId);}
    public final class RemoteScopePage implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String runId,assignmentId;private final TaskStore.TeamRemoteOwnerWindow window;
        private volatile boolean alive=true,foreground;private TeamCoordinator.DraftPlan plan;private TaskStore.TeamRemoteScopeReview pending;
        private RemoteScopePage(TeamCoordinator coordinator,String runId,String assignmentId)throws Exception{this.coordinator=coordinator;this.runId=runId;this.assignmentId=assignmentId;window=tasks.openTeamRemoteOwnerWindow();window.setForeground(false);}
        public void setForeground(boolean value){foreground=alive&&value;window.setForeground(foreground);}
        private void require(){if(!alive||!foreground)throw new SecurityException("需要当前前台主人远端scope审阅窗口");}
        public synchronized JSONObject snapshot()throws Exception{if(!alive)throw new SecurityException("原远端scope窗口已关闭");return tasks.teamRemoteScopeSnapshot(assignmentId);}
        public synchronized TaskStore.TeamRemoteScopeReview review()throws Exception {require();plan=coordinator.draftPlan(runId);JSONObject origin=tasks.get(assignmentId,false).getJSONObject("teamAssignment").getJSONObject("remoteSelection"),scope=plan.view().getJSONObject("productionScope");if(dev.pocketengine.features.remote.RemoteProjectAssignments.full(origin)&&(scope.optJSONObject("projectSelection")==null||!scope.getJSONObject("projectSelection").optBoolean("remoteProjectIntegratorOnly")))throw new SecurityException("先单独审阅手机唯一整合者完整基线、命令风险和预算；远端选择不授本机项目执行");pending=tasks.reviewTeamRemoteScope(window,assignmentId);plan.checkCurrent();return pending;}
        public synchronized JSONObject approve(TaskStore.TeamRemoteScopeReview exact,String digest)throws Exception {
            require();if(exact==null||pending!=exact)throw new SecurityException("请确认此原生窗口显示的准确远端scope");JSONObject shown=exact.view();if(!assignmentId.equals(shown.getString("assignmentId"))||!runId.equals(shown.getString("teamRunId")))throw new SecurityException("远端scope不属于当前团队分工");
            JSONObject continued=coordinator.continueDrafts(plan,new TeamCoordinator.DraftStarter(){
                public JSONObject approve()throws Exception{require();return tasks.approveTeamRemoteScope(exact,digest,1,shown.getInt("proposedParentToolLimit"),shown.getInt("proposedParentModelLimit"));}
                public JSONObject claim(JSONObject target)throws Exception{return assignmentId.equals(target.getString("assignmentId"))?tasks.claimTeamRemoteScopeContinuation(exact,target):new JSONObject().put("claimed",false);}
            });pending=null;tasks.updateExternal(runId,continued);changed.accept(runId);return tasks.teamRemoteScopeSnapshot(assignmentId).put("continuationsQueued",continued.getInt("draftContinuationsQueued"));
        }
        public synchronized JSONObject revoke(String scopeId,int revision)throws Exception {require();tasks.revokeTeamRemoteScope(assignmentId,scopeId,revision);JSONObject task=tasks.get(assignmentId,false);assignments.stopRemote(runId,task.getJSONObject("teamAssignment").getString("memberId"));RunControl actual=assignments.currentControl(assignmentId);if(actual!=null&&actual.matchesHostRun(assignmentId,task.getString("runId"),task.getInt("coordinatorEpoch")))actual.cancel();pending=null;changed.accept(assignmentId);return tasks.teamRemoteScopeSnapshot(assignmentId);}
        @Override public void close(){alive=false;foreground=false;window.close();}
    }
    public final class DraftScopePage implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String runId;private final TaskStore.TeamDraftWindow window;
        private volatile boolean alive=true;private TeamCoordinator.DraftPlan plan;private TaskStore.TeamDraftReview pending;
        private DraftScopePage(TeamCoordinator coordinator,String runId)throws Exception{this.coordinator=java.util.Objects.requireNonNull(coordinator);this.runId=runId;window=tasks.openTeamDraftWindow(runId);}
        public void setForeground(boolean foreground){window.setForeground(alive&&foreground);}
        public synchronized JSONObject snapshot()throws Exception{if(!alive)throw new SecurityException("原草稿审阅窗口已关闭");return tasks.teamDraftScopeSnapshot(runId);}
        public synchronized TaskStore.TeamDraftReview review()throws Exception{if(!alive)throw new SecurityException("原草稿审阅窗口已关闭");plan=coordinator.draftPlan(runId);pending=tasks.prepareTeamDraftScope(window,plan);return pending;}
        public synchronized JSONObject approve(TaskStore.TeamDraftReview review,String digest)throws Exception {
            if(!alive||review==null||review!=pending)throw new SecurityException("需要此窗口刚才显示的准确草稿与预算审阅");
            java.util.concurrent.atomic.AtomicReference<TaskStore.TeamDraftApproval> approval=new java.util.concurrent.atomic.AtomicReference<>();
            JSONObject result=coordinator.continueDrafts(plan,new TeamCoordinator.DraftStarter(){
                public JSONObject approve()throws Exception{TaskStore.TeamDraftApproval actual=tasks.approveTeamDraftScope(review,digest);approval.set(actual);return actual.view();}
                public JSONObject claim(JSONObject target)throws Exception{JSONArray selected=approval.get().view().getJSONArray("targets");for(int i=0;i<selected.length();i++)if(target.getString("assignmentId").equals(selected.getJSONObject(i).getString("assignmentId")))return tasks.claimTeamDraftContinuation(approval.get(),target);return new JSONObject().put("claimed",false);}
            });pending=null;tasks.updateExternal(runId,result);changed.accept(runId);return tasks.teamDraftScopeSnapshot(runId).put("continuationsQueued",result.getInt("draftContinuationsQueued"));
        }
        public synchronized JSONObject revoke(String expectedGrantId)throws Exception{if(!alive)throw new SecurityException("原草稿审阅窗口已关闭");pending=null;JSONObject result=tasks.revokeTeamDraftScope(window,expectedGrantId);JSONArray targets=result.getJSONArray("targets");for(int i=0;i<targets.length();i++){JSONObject target=targets.getJSONObject(i),task=tasks.get(target.getString("assignmentId"),false);if(task==null||task.getJSONObject("teamAssignment").optInt("assignmentRevision",1)!=target.getInt("assignmentRevision"))continue;RunControl actual=assignments.currentControl(task.getString("id"));if(actual!=null&&actual.matchesHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch")))actual.cancel();}changed.accept(runId);return result;}
        @Override public void close(){alive=false;window.close();}
    }
    public TeamWorkReview.Prepared review(String run,RunControl read)throws Exception {
        read.check();JSONObject scope=tasks.teamWriteScopeForOwner(run,read),context=tasks.teamIntegrationContext(run,read);JSONArray assignments=tasks.teamAssignmentsForReview(run,read);
        if(context!=null&&integrations.containsKey(run))context.put("activeAttempt",true);
        TeamWorkReview.Prepared review=context==null?TeamWorkReview.prepare(workspace,run,scope.getString("targetProjectId"),assignments):TeamWorkReview.prepareRecovery(workspace,run,scope.getString("targetProjectId"),assignments,context);read.check();return review;
    }
    /** Reopen reads native existing decisions and progress; never re-approves, re-runs or replays them. */
    public JSONObject reviewSnapshot(String run,RunControl read)throws Exception {
        JSONObject result=review(run,read).snapshot(),state=tasks.teamReviewState(run);for(String key:new String[]{"independentReview","approvedReview"})if(state.has(key))result.put(key,state.get(key));return result.put("integration",integrationState(run));
    }
    private JSONObject integrationState(String run)throws Exception {
        JSONObject state,context;synchronized(tasks){state=tasks.inspectTeamIntegration(run);context=tasks.teamIntegrationContext(run,new RunControl());}if(context==null)return state;
        JSONObject progress=TeamIntegrationFiles.progress(workspace,context.getJSONObject("binding"),context.getJSONArray("receipts"));boolean active=context.getBoolean("activeAttempt")||integrations.containsKey(run),unknown=context.getBoolean("hasUnresolved");
        state.put("progress",progress).put("activeAttempt",active).put("hasUnresolved",unknown).put("currentFilesComplete",progress.getBoolean("complete")&&!active&&!unknown);
        if(!active&&!unknown&&state.optString("status").equals("completed")&&!progress.getBoolean("complete"))state.put("historicalStatus","completed").put("status","awaiting_user").put("requiresOwnerReview",true).put("message","原成功回执已保留，但当前项目版本已变化；请重新审查实际差异，不会重放旧操作");return state.put("parentBudget",tasks.teamBudgetSnapshot(run));
    }
    /** Same exact head/progress check used by the original owner review, not a summary assertion. */
    JSONObject parentIntegrationState(String run)throws Exception {return integrationState(run);}
    public JSONObject answer(JSONObject input)throws Exception {JSONObject result=tasks.answerTeamAssignment(input.getString("runId"),input.getString("assignmentId"),input.getString("questionId"),input.getString("answerId"),input.getString("answer"));changed.accept(input.getString("assignmentId"));return result;}
    @Override public JSONObject queue(JSONObject input)throws Exception {JSONObject result=tasks.queueTeamAssignmentInput(input);changed.accept(input.getString("assignmentId"));return result;}
    @Override public JSONObject save(JSONObject input)throws Exception {return answer(input);}
    @Override public JSONObject claimContinuation(JSONObject input)throws Exception {return tasks.claimTeamAnswerContinuation(input);}
    private void requireRevisionBaseReady(JSONObject input)throws Exception {JSONObject selected=tasks.get(input.getString("assignmentId"),false);if(selected!=null&&!selected.getJSONObject("teamAssignment").optString("workItemId").isEmpty())throw new IllegalStateException("动态工作项的新修订需要对该workItem及受影响依赖重新审阅准确文件基线；不能按成员最近任务修改整份scope。原交付/固定版本保留，此入口尚未接动态修订。");if(selected!=null&&selected.getJSONObject("teamAssignment").has("remoteSelection"))throw new IllegalStateException("远端新修订还需从原冻结贡献重新显选准确基线与独立job范围；此接缝尚未完成，原分工、成果和审批未改变。可继续查询或停止原job，不会复用旧快照重提交。");}
    public JSONObject revise(JSONObject input)throws Exception {requireRevisionBaseReady(input);JSONObject result=tasks.reviseTeamAssignment(input);changed.accept(input.getString("assignmentId"));return result;}
    public static final class OutcomeContinuation {public final TeamWriteScope scope;public final JSONObject receipt;private OutcomeContinuation(TeamWriteScope scope,JSONObject receipt){this.scope=scope;this.receipt=receipt;}}
    public OutcomeContinuation continueOutcome(JSONObject input)throws Exception {
        requireRevisionBaseReady(input);
        String run=input.getString("runId");RunControl read=new RunControl();JSONObject context=tasks.teamIntegrationContext(run,read);
        if(context==null||context.optBoolean("activeAttempt")||context.optBoolean("hasUnresolved")||!context.optString("status").equals("completed")||!input.getString("expectedIntegrationReviewId").equals(context.getString("reviewId"))||!input.getString("expectedIntegrationPlanHash").equals(context.getString("planHash")))throw new SecurityException("原成果整合尚未确定完成或版本已变化");
        if(!TeamIntegrationFiles.progress(workspace,context.getJSONObject("binding"),context.getJSONArray("receipts")).getBoolean("complete"))throw new SecurityException("当前成果已改变，请先重审真实差异，不会覆盖新内容");
        TeamWriteScope scope=TeamWriteScope.afterIntegrated(workspace,tasks.teamWriteScopeForOwner(run,read),input.getString("memberId"),context.getString("reviewId"),context.getString("planHash"));
        JSONObject result=tasks.reviseTeamAssignmentFromOutcome(input,scope);changed.accept(input.getString("assignmentId"));return new OutcomeContinuation(scope,result);
    }
    public JSONObject approve(String run,String expectedHash,RunControl read)throws Exception {JSONObject result=tasks.approveTeamOutputs(run,review(run,read),expectedHash,read);changed.accept(run);return result;}
    /** A separate original child/run/context, never a producer impersonating its own reviewer. */
    public JSONObject independentlyReview(String run,String expectedPlanHash,JSONObject nativeTeamRun,ModelConfig model)throws Exception {
        if(!run.equals(nativeTeamRun.getString("id"))||!nativeTeamRun.optString("mode").equals("produce"))throw new SecurityException("独审目标不是原制作团队");RunControl control=new RunControl();if(reviews.putIfAbsent(run,control)!=null)throw new IllegalStateException("原独审仍在运行，请查看原任务");JSONObject binding=null;java.util.concurrent.atomic.AtomicReference<AutoCloseable> liveFence=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            TeamWorkReview.Prepared actual=review(run,control);binding=tasks.startTeamReview(run,actual,expectedPlanHash,control,Math.min(24,model.maxSteps));if(binding.optBoolean("inspectOnly"))return binding;
            JSONObject original=new JSONObject(binding.toString());integrationTasks.put(original.getString("taskId"),control);liveFence.set(control.retainAuthorityCheck(()->tasks.checkTeamAssignmentCurrent(original,"team_review_manifest")));
            TeamFrozenReview files=new TeamFrozenReview(workspace,original,control,tasks::checkTeamAssignment);int[] round={0};java.util.concurrent.atomic.AtomicReference<JSONObject> terminalEvent=new java.util.concurrent.atomic.AtomicReference<>();
            AgentRunner.RequestProjection projection=(history,config,live)->{if(live!=control)throw new SecurityException("不是原独审控制器");live.check();tasks.reserveTeamInference(original,"review",original.getString("runId")+":"+round[0]++);JSONArray request=new JSONArray(history.toString());request.getJSONObject(0).put("content","你是独立文件审查者，使用单独的原任务和上下文。先读team_review_manifest，再连续读取每份冻结成果的实际完整正文。按原目标与验收指出真实问题、缺口和建议；不能只相信作者自述。实际读完后必须调用team_submit_review给出no_blocking_findings、changes_requested或inconclusive，并写明依据，然后才给最终答复。不得写文件、批准、整合或冒充作者。最终给出明确的问题或无已发现问题，阅读完成不等于业务验证通过。");return request;};
            JSONArray acceptance=new JSONArray(),members=nativeTeamRun.getJSONArray("members");if(nativeTeamRun.optJSONObject("workPlan")!=null){JSONArray items=nativeTeamRun.getJSONObject("workPlan").getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);acceptance.put(new JSONObject().put("workItemId",item.getString("workItemId")).put("assignmentId",item.getString("assignmentId")).put("memberId",item.getString("memberId")).put("assignment",item.getString("instruction")).put("acceptance",item.optString("acceptance")).put("dependsOn",item.getJSONArray("dependsOn")));}}else for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);acceptance.put(new JSONObject().put("memberId",member.getString("id")).put("assignment",member.getString("assignment")).put("acceptance",member.optString("acceptance")));}
            RunOutcome outcome=new AgentRunner(null,originalTasks.meter(files,original),ContextBudgetPolicy.currentHostSource(),projection,null).run(original.getString("taskId"),"原共同目标："+nativeTeamRun.getString("goal")+"\n原分工与验收："+acceptance+"\n本次精确冻结计划及实际差异："+actual.snapshot().toString(),model.withStepCeiling(original.getInt("stepCeiling")),control,event->{try{if("result".equals(event.optString("type"))){terminalEvent.set(new JSONObject(event.toString()));return;}originalTasks.event(original,event);}catch(Exception failure){control.cancel();throw new IllegalStateException(failure);}},originalTasks.journal(original,control));
            closeFence(liveFence);control.check();if(terminalEvent.get()!=null)originalTasks.event(original,terminalEvent.get());JSONObject result=tasks.finishTeamReview(original,outcome,files.proof(),control);files.close();changed.accept(original.getString("taskId"));return result;
        }catch(Exception failure){if(binding!=null&&!binding.optBoolean("inspectOnly"))tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),control.isCancelled()?"cancelled":"interrupted","独立审查未取得确定终态；原阅读与模型回执保留",new JSONObject());throw failure;}
        finally{closeFence(liveFence);reviews.remove(run,control);if(binding!=null)integrationTasks.remove(binding.optString("taskId"),control);}
    }
    /** Called only by the native owner request worker after an exact existing review, never by synthesis. */
    public JSONObject integrate(String run,String reviewId,ModelConfig model)throws Exception {
        RunControl control=new RunControl();RunControl active=integrations.putIfAbsent(run,control);if(active!=null){JSONObject actual=integrationState(run);if(!reviewId.equals(actual.optString("reviewId")))throw new SecurityException("不是原唯一整合者审查");if(active.isPaused()){active.resume();return actual.put("continuationRequested",true);}throw new IllegalStateException("原唯一整合者仍在执行，请查看原任务");}
        JSONObject binding=null;java.util.concurrent.atomic.AtomicReference<AutoCloseable> liveFence=new java.util.concurrent.atomic.AtomicReference<>();try {
            binding=tasks.startTeamIntegration(run,reviewId,control,Math.min(24,model.maxSteps));if(binding.optBoolean("inspectOnly")){integrations.remove(run,control);return integrationState(run);}
            integrationTasks.put(binding.getString("taskId"),control);
            JSONObject original=new JSONObject(binding.toString());TeamIntegrationFiles filePort=new TeamIntegrationFiles(workspace,originalTasks.fileHost(original,control),control,original);TeamProjectWorkspaces projectHost=projects;boolean fullProject=tasks.teamProjectIntegrationSelection(original,control)!=null;if(fullProject&&projectHost==null)throw new SecurityException("原完整项目宿主未挂载，不能把仅文件CAS当完整项目交付");dev.pocketengine.core.CapabilityPort port=fullProject?projectHost.integratorPort(original,control,filePort):filePort;
            TeamIntegrationCompaction historySession=TeamIntegrationCompaction.open(tasks,original,control,model,port);liveFence.set(control.retainAuthorityCheck(historySession::checkCurrent));
            java.util.concurrent.atomic.AtomicReference<JSONObject> terminalEvent=new java.util.concurrent.atomic.AtomicReference<>();AgentRunner.RequestProjection projection=(history,config,live)->{if(live!=control||!live.matchesHostRun(original.getString("taskId"),original.getString("runId"),original.getInt("coordinatorEpoch")))throw new SecurityException("唯一整合者执行身份已变化");live.check();JSONArray request=new JSONArray(history.toString());request.getJSONObject(0).put("content",(fullProject?"本次是完整项目交付。先按下列规则完成全部原成果CAS，再调用team_prepare_integrated_project准备主人完整固定基线与准确已审输出的唯一结果副本；不得从成员目录引入额外文件。之后使用原project_inspect/search/read和原native commandTemplates的精确command方案；每个方案须主人批准当前sourceDigest。运行所有requiredForFreeze同版检查并确认真实退出/资源释放，再用intent=freeze_package/mode=source_project/明确entryPath生成并执行原生项目包方案，最后project_package按原executionId回读固定成果。不可编辑该组合副本、切换环境、用任意命令模板或覆盖父活跃checkout；命令同应用UID执行，不是OS沙箱。完整包是固定基线结果，不得声称当前主项目已测试。只有这些真实证据齐备才能收尾。\n":"")+"你是机主审查后的唯一文件整合者。本次原生planHash="+original.getString("planHash")+"；旧计划的读取和应用回执不能替代本计划。只能使用本次team_read_integration和team_integrate_file。先读原审查计划，只能逐项选择其中的原artifactId和path。team_read_integration默认是本轮有界索引；hasMore时按nextOffset/snapshotHash继续，完整冻结证据按view=evidence和原path/artifactId范围读取，旧审计按view=history再用其精确operation引用分块读取。页或来源变化须从第一页重读；旧内容不授权新操作。本child当前执行原上下文可用memory_raw_query(section=messages或operations)检索，再memory_raw_read按准确sourceId/revision分页回读；不能查询其他任务、私人记忆或outputs。历史整理是单独计同父池的SUMMARY请求，整理内容仍是低信任资料，不替代原工具事实或批准。project_plan以原planRef/planDigest/stepId执行，完整清单由project_plan_manifest按需读取，不依赖legacy inline副本。不得生成、修改、拼接或自动合并文件内容。所有文件副作用仍须原prepared操作、主人精确批准及当前版本CAS；暂停、冲突或未知效果时等待主人核对，不重放。只有实际逐文件回执齐备才说明整合完成，不代表业务验证通过。");return request;};
            RunOutcome outcome=new AgentRunner(null,originalTasks.meter(historySession.port(),original),ContextBudgetPolicy.currentHostSource(),projection,null).run(original.getString("taskId"),"执行主人审查的原团队计划 "+original.getString("planHash")+"。请先读取原整合计划；每项只提交原path/artifactId，不产生新内容。",model.withStepCeiling(original.getInt("stepCeiling")),control,event->{try{if("result".equals(event.optString("type"))){terminalEvent.set(new JSONObject(event.toString()));return;}originalTasks.event(original,event);}catch(Exception failure){control.cancel();throw new IllegalStateException(failure);}},historySession.journal());
            closeFence(liveFence);control.check();if(terminalEvent.get()!=null)originalTasks.event(original,terminalEvent.get());String status=outcome.status;JSONObject evidence=new JSONObject(outcome.evidence.toString());
            // Runner has closed its tools and emitted the real terminal event. Only read-only settlement follows.
            TeamProjectWorkspaces.Delivery delivery=status.equals("needs_confirmation")&&fullProject?projectHost.integrationCompletion(original,control):null;if(delivery!=null)evidence.put("teamProjectDelivery",delivery.view());
            synchronized(tasks){
                if(status.equals("needs_confirmation")){
                    tasks.checkTeamAssignmentSettlement(original,control);JSONObject current=tasks.teamIntegrationContext(run,control);
                    if(!original.getString("reviewId").equals(current.getString("reviewId"))||!original.getString("planHash").equals(current.getString("planHash"))||!original.getString("runId").equals(current.getJSONObject("binding").getString("runId")))throw new SecurityException("原唯一整合者结算身份已变化");
                    JSONObject progress=TeamIntegrationFiles.progress(workspace,original,current.getJSONArray("receipts"));tasks.checkTeamAssignmentSettlement(original,control);
                    status=!tasks.hasUnresolved(original.getString("taskId"))&&progress.getBoolean("complete")&&(!fullProject||delivery!=null&&delivery.complete())?"completed":"awaiting_user";evidence.put("teamIntegrationProgress",progress);
                }
                tasks.endAttempt(original.getString("taskId"),original.getString("runId"),status,outcome.summary,evidence);
            }
            integrations.remove(run,control);integrationTasks.remove(original.getString("taskId"),control);changed.accept(original.getString("taskId"));return integrationState(run);
        }catch(Exception failure){if(binding!=null&&!binding.optBoolean("inspectOnly"))try{tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),control.isCancelled()?"cancelled":"interrupted","原唯一整合者未取得确定终态，实际逐文件回执保留；未知效果只核对、不重放",new JSONObject());changed.accept(binding.getString("taskId"));}catch(Exception persistence){failure.addSuppressed(persistence);}throw failure;}
        finally{closeFence(liveFence);integrations.remove(run,control);if(binding!=null&&!binding.optString("taskId").isEmpty())integrationTasks.remove(binding.optString("taskId"),control);}
    }
    public void cancel(String run){RunControl control=integrations.get(run);if(control!=null)control.cancel();control=reviews.get(run);if(control!=null)control.cancel();}
    public void pause(String run){RunControl control=integrations.get(run);if(control!=null)control.pause();control=reviews.get(run);if(control!=null)control.pause();}
    public void resume(String run){RunControl control=integrations.get(run);if(control!=null)control.resume();control=reviews.get(run);if(control!=null)control.resume();}
    @Override public void close(){for(RunControl control:integrations.values())control.cancel();for(RunControl control:reviews.values())control.cancel();}
}
