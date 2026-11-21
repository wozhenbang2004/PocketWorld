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
    TeamParentHost parentHost(TeamCoordinator coordinator,CurrentModel model,TeamParentHost.Definitions definitions){return parentHost(coordinator,model,definitions,null);}
    TeamParentHost parentHost(TeamCoordinator coordinator,CurrentModel model,TeamParentHost.Definitions definitions,dev.pocketengine.features.FeatureContext context){if(parentCleanup==null)return null;return new TeamParentHost(tasks,workspace,coordinator,this,model,definitions,changed,parentCleanup,context);}
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
        /** Existing TeamWorkHandoff seals the actual old draft. This narrower UI path cannot buy a new budget or route. */
        public synchronized TaskStore.TeamWorkReview reviewSoleLocalHandoff(String item,String member,int itemRevision,int ownerGeneration)throws Exception{
            require();pendingRevision=null;source=null;handoffPlan=null;pending=null;mutation=coordinator.workMutationSource(run);
            coordinator.requireSoleLocalHandoff(mutation,item,member);
            TaskStore.TeamWorkHandoffSource bound=tasks.localRemainderHandoffSource(window,mutation,item,member,itemRevision,ownerGeneration);
            handoffPlan=TeamWorkHandoff.capture(workspace,bound);pending=tasks.prepareTeamWorkHandoff(handoffPlan);return pending;
        }
        public synchronized JSONObject approveSoleLocalHandoff(TaskStore.TeamWorkReview exact)throws Exception{
            require();if(exact==null||pending!=exact||handoffPlan==null||handoffPlan.projectSelection()!=null)throw new SecurityException("不是原主人的剩余分工交接审阅");
            JSONObject shown=exact.view();if(!shown.getBoolean("carryLocalRemainder")||shown.getBoolean("budgetChanged")||shown.getBoolean("parentBudgetIncreaseRequired"))throw new SecurityException("不能借交接补额度");
            String item=shown.getJSONObject("workItem").getString("workItemId"),next=shown.getString("newMemberId");
            JSONObject updated=coordinator.approveSoleLocalHandoff(mutation,item,next,()->{require();TeamWorkHandoff.checkDraft(workspace,handoffPlan);return tasks.approveTeamWorkHandoff(exact,shown.getString("digest"));});
            pending=null;mutation=null;handoffPlan=null;tasks.updateExternal(run,updated);changed.accept(run);return updated;
        }
        public synchronized TaskStore.TeamWorkReview reviewTransfer(String item,String member,int itemRevision,int ownerGeneration)throws Exception{
            require();pendingRevision=null;source=null;handoffPlan=null;JSONObject current=TeamWorkItems.find(tasks.teamWorkPlanSnapshot(run).getJSONObject("workPlan").getJSONArray("items"),item),task=current.optJSONObject("assignmentTask");
            mutation=coordinator.workMutationSource(run);TaskStore.TeamWorkHandoffSource nativeSource=tasks.teamWorkHandoffSource(window,mutation,item,member,itemRevision,ownerGeneration);handoffPlan=TeamWorkHandoff.capture(workspace,nativeSource);if(nativeSource.view().has("projectContext")){if(projects==null)throw new SecurityException("原完整副本宿主未挂载，不能冒文字接手");handoffPlan=TeamWorkHandoff.withProject(handoffPlan,projects.captureHandoff(handoffPlan));}pending=tasks.prepareTeamWorkHandoff(handoffPlan);return pending;
        }
        public synchronized JSONObject approveTransfer(TaskStore.TeamWorkReview review,String digest)throws Exception{require();if(review==null||pending!=review||source!=null||review.view().optBoolean("carryLocalRemainder"))throw new SecurityException("请使用这次原工作项的准确交接审阅");JSONObject continued;if(handoffPlan!=null){TeamWorkHandoff.checkDraft(workspace,handoffPlan);if(handoffPlan.projectSelection()!=null)projects.checkSelectionCurrent(handoffPlan.projectSelection());continued=coordinator.approveWorkHandoff(mutation,()->{require();TeamWorkHandoff.checkDraft(workspace,handoffPlan);if(handoffPlan.projectSelection()!=null)projects.checkSelectionCurrent(handoffPlan.projectSelection());return tasks.approveTeamWorkHandoff(review,digest);},models.get());handoffPlan=null;mutation=null;}else{JSONObject committed=tasks.approveTeamWorkTransfer(review,digest);continued=coordinator.refreshWorkItems(run,committed);}pending=null;tasks.updateExternal(run,continued);changed.accept(run);return continued;}
        public synchronized TaskStore.TeamWorkReview reviewContinuation(String item,int itemRevision,int ownerGeneration)throws Exception{require();pendingRevision=null;mutation=null;source=null;pending=tasks.prepareTeamWorkContinuation(window,item,itemRevision,ownerGeneration);return pending;}
        public synchronized TaskStore.TeamWorkReview reviewLocalContinuation(String item,int itemRevision,int ownerGeneration)throws Exception {
            require();pendingRevision=null;mutation=null;source=null;pending=tasks.prepareLocalTeamWorkContinuation(window,item,itemRevision,ownerGeneration);return pending;
        }
        /** Recheck the shown local draft's exact member and stopped run under the same lock as native admission. */
        public synchronized JSONObject approveShownLocalContinuation(TaskStore.TeamWorkReview review,String digest,int teamGeneration,int memberGeneration,int identityRevision)throws Exception {
            require();if(review==null||pending!=review||source!=null)throw new SecurityException("请重新审阅原分工");
            synchronized(coordinator){
                JSONObject original=coordinator.get(run),shown=review.view(),work=shown.getJSONObject("workItem"),plan=original.getJSONObject("workPlan");
                JSONObject item=TeamWorkItems.find(plan.getJSONArray("items"),work.getString("workItemId")),member=TeamWorkPlan.find(original.getJSONArray("members"),work.getString("memberId")),child=item.optJSONObject("assignmentTask");
                int unfrozenEpoch=shown.optInt("finalWithoutFrozenOutputEpoch");
                boolean stopped=child!=null&&(child.optString("status").matches("paused|interrupted|failed")||
                        "awaiting_user".equals(child.opt("status"))&&unfrozenEpoch>0&&unfrozenEpoch==child.optInt("finalWithoutFrozenOutputEpoch"));
                if(!shown.optBoolean("localDraftOnly")||original.optInt("generation",-1)!=teamGeneration||member.optInt("generation",-1)!=memberGeneration||member.optInt("identityRevision",1)!=identityRevision
                        ||member.optBoolean("exited")||!member.optString("runner","model").equals("model")||!"phone".equals(member.opt("device"))
                        ||original.getJSONObject("productionScope").has("projectSelection")||member.has("remoteProduction")||item.has("projectSelection")||item.has("remoteSelection")
                        ||!plan.getString("planId").equals(shown.getString("planId"))||!TeamWorkItems.sameIdentity(item,work)||child==null
                        ||!shown.getString("runId").equals(child.optString("runId"))||!stopped)
                    throw new SecurityException("原成员、分工或停点已改变，未按旧审阅继续");
                return approveContinuation(review,digest);
            }
        }
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
        public final class ParentProposalReview implements AutoCloseable {
            private final TaskStore.TeamWorkRevisionReview exact;private final JSONObject proposal,display;private boolean consumed;
            private ParentProposalReview(TaskStore.TeamWorkRevisionReview review,JSONObject original,JSONObject shown){exact=review;proposal=original;display=shown;}
            public JSONObject preview()throws Exception{return new JSONObject(display.toString());}
            public synchronized JSONObject confirm()throws Exception {
                if(consumed)throw new SecurityException("这次修订确认已使用，请核对原分工");consumed=true;
                return approveRevision(exact,exact.view().getString("digest"),proposal);
            }
            @Override public void close(){WorkPlanPage.this.close();}
        }
        public synchronized ParentProposalReview reviewParentProposal(JSONObject retained,String operation)throws Exception {
            require();synchronized(coordinator){JSONObject current=TeamParentRevisionOffer.exact(coordinator.get(run),operation),seen=TeamParentRevisionOffer.exact(retained,operation);
                if(!TeamWorkFiles.canonical(current).equals(TeamWorkFiles.canonical(seen)))throw new SecurityException("已读的原修订提案发生变化，请重新核对");
                TaskStore.TeamWorkRevisionReview exact=reviewRevision(current.getString("workItemId"),current.getInt("itemRevision"),current.getInt("ownerGeneration"),current.getString("text"));
                return new ParentProposalReview(exact,current,TeamParentRevisionOffer.preview(current,exact.view()));
            }
        }
        public synchronized JSONObject approveRevision(TaskStore.TeamWorkRevisionReview exact,String digest)throws Exception {return approveRevision(exact,digest,null);}
        private synchronized JSONObject approveRevision(TaskStore.TeamWorkRevisionReview exact,String digest,JSONObject proposal)throws Exception {
            require();if(exact==null||exact!=pendingRevision||mutation==null)throw new SecurityException("请先审阅此窗口准确工作项及受影响依赖的新基线与预算");
            JSONObject continued=coordinator.reviseWorkItems(mutation,()->{require();requireRevisionBase(exact.source());if(exact.projectSelection()!=null)projects.checkSelectionCurrent(exact.projectSelection());
                if(proposal!=null){JSONObject current=TeamParentRevisionOffer.exact(coordinator.get(run),proposal.getString("operationId"));
                    if(!TeamWorkFiles.canonical(current).equals(TeamWorkFiles.canonical(proposal)))throw new SecurityException("原提案身份已改变，不开始修订");
                    TeamParentRevisionOffer.preview(current,exact.view());}
                JSONObject committed=tasks.approveTeamWorkRevision(exact,digest);if(proposal!=null)committed.put("appliedParentProposal",proposal.getString("operationId"));return committed;
            },models.get());pendingRevision=null;mutation=null;tasks.updateExternal(run,continued);changed.accept(run);return continued;
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
    TeamOwnerFileReading readOwnerFrozenFiles(JSONObject run,RunControl reader)throws Exception{
        String id=run.getString("id");JSONObject captured=review(id,reader).snapshot();String warning="";TeamOwnerRevisionFiles revision=null;
        try{revision=TeamOwnerRevisionFiles.capture(tasks,workspace,run,captured,reader);}
        catch(Exception unavailable){reader.check();warning="此次未能锁定上版写回与本次冻结的同一组原件。下方只是打开时项目对照，不替代修订前后对照。";}
        reader.check();return new TeamOwnerFileReading(id,captured,revision,warning);
    }
    /** Exact selected file, never the team's other files. Reading cannot approve, consume budget or dispatch. */
    JSONObject readOwnerFileApprovalPage(String id,String run,int epoch,String approval,String proof,String expectedDiff,int offset,RunControl reader)throws Exception{
        JSONObject source=tasks.ownerFileApprovalSource(id,run,epoch,approval,reader);
        if(!proof.equals(source.getJSONObject("locator").getString("proof")))throw new SecurityException("这笔原文件或用途已变");
        JSONObject page=TeamOwnerApprovalFileDiff.page(workspace,source,expectedDiff,offset,reader);
        JSONObject fresh=tasks.ownerFileApprovalSource(id,run,epoch,approval,reader);
        if(!TeamWorkFiles.canonical(source).equals(TeamWorkFiles.canonical(fresh)))throw new SecurityException("原审批在读取中已变化，未返回差异");
        reader.check();return page;
    }
    /** Reopen reads native existing decisions and progress; never re-approves, re-runs or replays them. */
    public JSONObject reviewSnapshot(String run,RunControl read)throws Exception {
        JSONObject result=review(run,read).snapshot(),state=tasks.teamReviewState(run);for(String key:new String[]{"independentReview","approvedReview"})if(state.has(key))result.put(key,state.get(key));return result.put("integration",integrationState(run));
    }
    private JSONObject integrationState(String run)throws Exception {
        JSONObject state,context;synchronized(tasks){state=tasks.inspectTeamIntegration(run);context=tasks.teamIntegrationContext(run,new RunControl());}if(context==null)return state;
        JSONObject progress=TeamIntegrationFiles.progress(workspace,context.getJSONObject("binding"),context.getJSONArray("receipts"));boolean active=context.getBoolean("activeAttempt")||integrations.containsKey(run),unknown=context.getBoolean("hasUnresolved");
        state.put("progress",progress).put("coordinatorEpoch",context.getJSONObject("binding").getInt("coordinatorEpoch")).put("activeAttempt",active).put("hasUnresolved",unknown).put("currentFilesComplete",progress.getBoolean("complete")&&!active&&!unknown);
        if(!active&&!unknown&&state.optString("status").equals("completed")&&!progress.getBoolean("complete"))state.put("historicalStatus","completed").put("status","awaiting_user").put("requiresOwnerReview",true).put("message","原成功回执已保留，但当前项目版本已变化；请重新审查实际差异，不会重放旧操作");return state.put("parentBudget",tasks.teamBudgetSnapshot(run));
    }
    /** Same exact head/progress check used by the original owner review, not a summary assertion. */
    JSONObject parentIntegrationState(String run)throws Exception {return integrationState(run);}
    public JSONObject answer(JSONObject input)throws Exception {JSONObject result=tasks.answerTeamAssignment(input.getString("runId"),input.getString("assignmentId"),input.getString("questionId"),input.getString("answerId"),input.getString("answer"));changed.accept(input.getString("assignmentId"));return result;}
    @Override public JSONObject queue(JSONObject input)throws Exception {JSONObject result=tasks.queueTeamAssignmentInput(input);changed.accept(input.getString("assignmentId"));return result;}
    @Override public JSONObject save(JSONObject input)throws Exception {return answer(input);}
    @Override public JSONObject claimContinuation(JSONObject input)throws Exception {return tasks.claimTeamAnswerContinuation(input);}
    private JSONObject requireRevisionBaseReady(JSONObject input)throws Exception {
        JSONObject selected=tasks.get(input.getString("assignmentId"),false),identity=selected==null?null:selected.optJSONObject("teamAssignment");
        if(identity==null)throw new SecurityException("原assignment不存在或不是文件分工");
        if(!identity.optString("workItemId").isEmpty())throw new IllegalStateException("动态工作项的新修订需要对该workItem及受影响依赖重新审阅准确文件基线；不能按成员最近任务修改整份scope。原交付/固定版本保留，此入口尚未接动态修订。");
        if(identity.has("remoteSelection"))throw new IllegalStateException("远端新修订还需从原冻结贡献重新显选准确基线与独立job范围；此接缝尚未完成，原分工、成果和审批未改变。可继续查询或停止原job，不会复用旧快照重提交。");
        if(identity.has("projectSelection"))throw new SecurityException("完整项目继续必须经原前台私构成果修订及新的命令/基线审阅，普通JSON不能升级旧授权");
        return selected;
    }
    /** Read-only admission before feedback. The original SQL transaction still rechecks every write condition. */
    public TeamWriteScope checkRevision(JSONObject input,boolean outcome)throws Exception {
        synchronized(tasks){
            String run=input.getString("runId"),assignment=input.getString("assignmentId"),member=input.getString("memberId"),request=input.getString("revisionRequestId"),text=input.getString("text");
            if(!request.matches("[a-f0-9-]{36}")||text.codePoints().allMatch(Character::isWhitespace)||text.length()>4000)throw new SecurityException("修订要求无效");
            String teamTask=tasks.requireReviewedTeamControl(input);JSONObject selected=requireRevisionBaseReady(input),identity=selected.getJSONObject("teamAssignment"),origin=selected.getJSONObject("origin"),team=tasks.get(teamTask,false);
            if(!origin.optString("kind").equals("team_assignment")||!run.equals(origin.optString("teamRunId"))||!run.equals(identity.optString("teamRunId"))||!assignment.equals(identity.optString("assignmentId"))||!assignment.equals(origin.optString("assignmentId"))||!member.equals(identity.optString("memberId"))||!member.equals(origin.optString("memberId")))throw new SecurityException("修订不是原主人选择的assignment");
            if(request.equals(identity.optString("revisionRequestId")))throw new IllegalStateException("本次原修订已保存，只能查询原assignment，不重送反馈");
            boolean workingContinuation=team.optJSONObject("teamOutputReview")!=null&&identity.optString("outcomeContinuationReviewId").equals(team.getJSONObject("teamOutputReview").optString("reviewId"));
            if(team.has("teamOutputReview")&&!outcome&&!workingContinuation||tasks.hasUnresolved(assignment)||!selected.optString("status").matches("completed|failed|awaiting_user|needs_confirmation|interrupted"))throw new SecurityException("原分工仍在运行、待核对或已批准整合，不能另开修订");
            JSONArray questions=selected.optJSONArray("teamQuestions");for(int i=0;questions!=null&&i<questions.length();i++)if(questions.getJSONObject(i).optString("state").equals("pending"))throw new SecurityException("原分工仍有未回答问题，不能另开修订");
            RunControl live=currentControl(assignment);if(live!=null)throw new SecurityException("原assignment执行尝试尚未结束");
            if(identity.optInt("assignmentRevision",1)>=8)throw new IllegalStateException("原assignment已达8次修订上限，请完成并核对已有工作");
            // This legacy entry admits only local frozen text assignments, never remote IMPORT or dynamic work items.
            tasks.teamAssignmentReceipts(new JSONObject(identity.toString()).put("taskId",assignment),"team_freeze_outputs");
            JSONArray operations=selected.getJSONArray("operations"),frozen=null;String frozenRun="";
            for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i),receipt=operation.optJSONObject("receipt");if(operation.optString("state").equals("receipt")&&operation.optString("tool").equals("team_freeze_outputs")&&receipt!=null&&receipt.optInt("assignmentRevision",1)==identity.optInt("assignmentRevision",1)&&receipt.optJSONArray("teamArtifacts")!=null){frozen=receipt.getJSONArray("teamArtifacts");frozenRun=operation.getString("runId");}}
            if(frozen==null||!frozenRun.equals(input.getString("expectedFrozenRunId"))||!TeamWorkFiles.hash(TeamWorkFiles.canonical(new JSONObject().put("artifacts",frozen))).equals(input.getString("expectedArtifactsHash")))throw new SecurityException("原冻结成果已改变，请重新查看后请求修订");
        }
        return outcome?outcomeScope(input):null;
    }
    /** Readback only after a failed/previous request; a saved revision never authorizes feedback or execution replay. */
    public JSONObject revisionState(JSONObject input)throws Exception {
        JSONObject selected=tasks.get(input.getString("assignmentId"),false),identity=selected==null?null:selected.optJSONObject("teamAssignment");
        if(identity==null||!input.getString("runId").equals(identity.optString("teamRunId"))||!input.getString("memberId").equals(identity.optString("memberId")))throw new SecurityException("原修订查询身份已改变");
        boolean saved=input.getString("revisionRequestId").equals(identity.optString("revisionRequestId"));return new JSONObject().put("state",saved?"saved":"not_confirmed").put("assignmentId",input.getString("assignmentId")).put("assignmentRevision",identity.optInt("assignmentRevision",1)).put("revisedAssignment",saved?new JSONObject(identity.toString()):JSONObject.NULL);
    }
    public JSONObject revise(JSONObject input)throws Exception {requireRevisionBaseReady(input);JSONObject result=tasks.reviseTeamAssignment(input);changed.accept(input.getString("assignmentId"));return result;}
    public static final class OutcomeContinuation {public final TeamWriteScope scope;public final JSONObject receipt;private OutcomeContinuation(TeamWriteScope scope,JSONObject receipt){this.scope=scope;this.receipt=receipt;}}
    public OutcomeContinuation continueOutcome(JSONObject input)throws Exception {
        requireRevisionBaseReady(input);
        TeamWriteScope scope=outcomeScope(input);
        JSONObject result=tasks.reviseTeamAssignmentFromOutcome(input,scope);changed.accept(input.getString("assignmentId"));return new OutcomeContinuation(scope,result);
    }
    private TeamWriteScope outcomeScope(JSONObject input)throws Exception {
        String run=input.getString("runId");RunControl read=new RunControl();JSONObject context=tasks.teamIntegrationContext(run,read);
        if(context==null||context.optBoolean("activeAttempt")||context.optBoolean("hasUnresolved")||!context.optString("status").equals("completed")||!input.getString("expectedIntegrationReviewId").equals(context.getString("reviewId"))||!input.getString("expectedIntegrationPlanHash").equals(context.getString("planHash")))throw new SecurityException("原成果整合尚未确定完成或版本已变化");
        if(!TeamIntegrationFiles.progress(workspace,context.getJSONObject("binding"),context.getJSONArray("receipts")).getBoolean("complete"))throw new SecurityException("当前成果已改变，请先重审真实差异，不会覆盖新内容");
        return TeamWriteScope.afterIntegrated(workspace,tasks.teamWriteScopeForOwner(run,read),input.getString("memberId"),context.getString("reviewId"),context.getString("planHash"));
    }
    JSONObject projectFirstReviewOrigin(JSONObject requested,String team,RunControl read)throws Exception{
        JSONObject mark=tasks.requireProjectTeamFirstReturn(requested,team,read);
        if(projects==null||reviews.containsKey(team)||integrations.containsKey(team))throw new SecurityException("原协作仍在执行或本机项目宿主不可读");
        projects.checkFirstOutputBaseline(new JSONObject(mark.toString()).put("targetProjectId",mark.getString("projectId")),read);
        read.check();return mark;
    }
    JSONObject ownerTextDecision(TeamOwnerFileReading shown,RunControl reader)throws Exception{
        reader.check();if(!shown.hasCompleteTextComparison())return new JSONObject().put("state","read_only");
        try{
            JSONObject facts=tasks.firstLocalTeamOutputDecision(shown.team(),shown.plan(),reader);
            if("independent_review_required".equals(facts.opt("state"))){JSONObject finding=tasks.ownerBlockedTeamReviewFinding(shown.team(),shown.plan(),reader);
                if(finding!=null)return finding;}
            JSONObject display=new JSONObject().put("state",facts.getString("state"));if(facts.has("reviewId"))display.put("reviewId",facts.getString("reviewId"));
            if(TeamFirstProjectReview.registrationOnly(facts))display.put("registrationKind",TeamFirstProjectReview.REGISTRATION).put("separateIntegratorStartRequired",true);return display;
        }catch(Exception notLocal){
            reader.check();
            try{JSONObject facts=tasks.firstProjectTeamOutputFacts(shown.team(),shown.plan(),reader);return new JSONObject().put("state",facts.getString("state"));}
            catch(Exception unavailable){reader.check();return new JSONObject().put("state","read_only");}
        }
    }
    /** New, short-lived owner confirmation. The file-reading handle alone cannot approve anything. */
    public final class TextOutputApproval implements AutoCloseable {
        private final TeamOwnerFileReading shown;private final RunControl authority=new RunControl();
        private final String reviewerTask,reviewerRun,project,projectFacts;private final boolean firstProject;
        private volatile boolean registering;
        private final long expires=android.os.SystemClock.elapsedRealtime()+120_000;
        private final java.util.concurrent.atomic.AtomicBoolean spent=new java.util.concurrent.atomic.AtomicBoolean();
        private TextOutputApproval(TeamOwnerFileReading seen,boolean firstProject)throws Exception{
            shown=seen;this.firstProject=firstProject;if(!shown.shownAllOriginalDiffs())throw new SecurityException("请先逐份加载并核对本次原始文本差异");
            verifiedOriginal();
            JSONObject facts=firstProject?tasks.firstProjectTeamOutputFacts(shown.team(),shown.plan(),authority):tasks.firstLocalTeamOutputDecision(shown.team(),shown.plan(),authority);
            if(!(firstProject?"eligible_project_first":"eligible").equals(facts.getString("state")))throw new SecurityException("原计划不再需要或不允许这次批准，请重读原成果");
            if(firstProject)checkProject(facts);projectFacts=firstProject?TeamWorkFiles.canonical(facts):null;
            reviewerTask=facts.getString("reviewerTaskId");reviewerRun=facts.getString("reviewerRunId");project=facts.getString("targetProjectId");
        }
        private void checkProject(JSONObject facts)throws Exception{
            if(projects==null||reviews.containsKey(shown.team())||integrations.containsKey(shown.team()))throw new SecurityException("原独审或整合尚未退出，或项目宿主不可用");
            projects.checkFirstOutputBaseline(facts,authority);
        }
        public boolean firstProject(){return firstProject;}
        public boolean approvesFirstProject(TaskStore issuer,RunControl owner,JSONObject facts)throws Exception{
            return firstProject&&registering&&tasks==issuer&&authority==owner&&!authority.isCancelled()&&android.os.SystemClock.elapsedRealtime()<expires&&
                    !reviews.containsKey(shown.team())&&!integrations.containsKey(shown.team())&&projectFacts.equals(TeamWorkFiles.canonical(facts));
        }
        private TeamWorkReview.Prepared verifiedOriginal()throws Exception{
            authority.check();TeamWorkReview.Prepared fresh=review(shown.team(),authority);
            if(!shown.sameTextComparison(new TeamOwnerFileReading(shown.team(),fresh.snapshot())))throw new SecurityException("原冻结文件、文本或项目基线已变，请重新打开并核对差异");
            return fresh;
        }
        public JSONObject preview()throws Exception{
            authority.check();JSONArray paths=new JSONArray(),files=shown.index().getJSONArray("files");
            for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i),row=new JSONObject().put("path",file.getString("path")).put("existing",file.getBoolean("currentExists"));
                if(firstProject)row.put("version",file.getInt("currentVersion")).put("sha256",file.getString("sha256"));paths.put(row);}
            JSONObject value=new JSONObject().put("teamRunId",shown.team()).put("planHash",shown.plan()).put("projectId",project).put("files",paths).put("startsIntegration",false);
            if(firstProject){JSONObject facts=new JSONObject(projectFacts);value.put("firstProject",true).put("parentCheckoutId",facts.getString("checkoutId")).put("sourceDigest",facts.getString("sourceDigest"))
                    .put("snapshotIdentity",facts.getString("snapshotIdentity")).put("reviewRouteCaptured",facts.optBoolean("reviewRouteCaptured")).put("reviewerTaskId",reviewerTask).put("reviewerRunId",reviewerRun).put("startsChecks",false).put("packagesProject",false);}
            return value;
        }
        public JSONObject confirm()throws Exception{
            if(!spent.compareAndSet(false,true)||android.os.SystemClock.elapsedRealtime()>=expires)throw new SecurityException("这份写回许可审阅已用过或过期，请查询原登记");
            TeamWorkReview.Prepared fresh=verifiedOriginal();JSONObject receipt;
            if(firstProject){checkProject(new JSONObject(projectFacts));registering=true;
                try{receipt=tasks.approveFirstProjectTeamOutputs(shown.team(),fresh,shown.plan(),authority,this);}finally{registering=false;}}
            else receipt=tasks.approveFirstLocalTeamOutputs(shown.team(),fresh,shown.plan(),authority,reviewerTask,reviewerRun);
            String id=receipt.getString("reviewId");if((firstProject&&!TeamFirstProjectReview.registrationOnly(receipt))||id.isEmpty()||!shown.plan().equals(receipt.getString("planHash"))||!"owner".equals(receipt.optString("actorId"))||!"private".equals(receipt.optString("spaceId")))throw new SecurityException("原批准回执身份不符，请刷新核对，不重复提交");
            changed.accept(shown.team());JSONObject saved=new JSONObject().put("teamRunId",shown.team()).put("planHash",shown.plan()).put("reviewId",id).put("state","approved_current").put("startedByThisAction",false);
            if(firstProject)saved.put("registrationKind",TeamFirstProjectReview.REGISTRATION).put("separateIntegratorStartRequired",true);return saved;
        }
        @Override public void close(){authority.cancel();}
    }
    TextOutputApproval reviewOwnerTextApproval(TeamOwnerFileReading seen)throws Exception{return new TextOutputApproval(seen,false);}
    TextOutputApproval reviewFirstProjectTextApproval(TeamOwnerFileReading seen)throws Exception{return new TextOutputApproval(seen,true);}
    public JSONObject approve(String run,String expectedHash,RunControl read)throws Exception {JSONObject result=tasks.approveTeamOutputs(run,review(run,read),expectedHash,read);changed.accept(run);return result;}
    /** A separate original child/run/context, never a producer impersonating its own reviewer. */
    public JSONObject independentlyReview(String run,String expectedPlanHash,JSONObject nativeTeamRun,ModelConfig model)throws Exception {
        if(!run.equals(nativeTeamRun.getString("id"))||!nativeTeamRun.optString("mode").equals("produce"))throw new SecurityException("独审目标不是原制作团队");RunControl control=new RunControl();synchronized(integrations){if(integrations.containsKey(run)||reviews.putIfAbsent(run,control)!=null)throw new IllegalStateException("原独审或整合仍在运行，请查看原任务");}JSONObject binding=null;boolean settled=false;java.util.concurrent.atomic.AtomicReference<AutoCloseable> liveFence=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            TeamWorkReview.Prepared actual=review(run,control);binding=tasks.startTeamReview(run,actual,expectedPlanHash,control,Math.min(24,model.maxSteps));if(binding.optBoolean("inspectOnly"))return binding;
            JSONObject original=new JSONObject(binding.toString());integrationTasks.put(original.getString("taskId"),control);liveFence.set(control.retainAuthorityCheck(()->tasks.checkTeamAssignmentCurrent(original,"team_review_manifest")));
            TeamReviewerRouteCapture route=TeamReviewerRouteRecord.firstLocal(nativeTeamRun.optJSONObject("productionScope"),nativeTeamRun.optJSONObject("workPlan"))
                    ?TeamReviewerRouteCapture.capture(tasks,control,original,model):null;
            TeamFrozenReview files=new TeamFrozenReview(workspace,original,control,tasks::checkTeamAssignment);int[] round={0};java.util.concurrent.atomic.AtomicReference<JSONObject> terminalEvent=new java.util.concurrent.atomic.AtomicReference<>();
            AgentRunner.RequestProjection projection=(history,config,live)->{if(live!=control)throw new SecurityException("不是原独审控制器");live.check();tasks.reserveTeamInference(original,"review",original.getString("runId")+":"+round[0]++);JSONArray request=new JSONArray(history.toString());request.getJSONObject(0).put("content","你是独立文件审查者，使用单独的原任务和上下文。先读team_review_manifest，再连续读取每份冻结成果的实际完整正文。按原目标与验收指出真实问题、缺口和建议；不能只相信作者自述。实际读完后必须调用team_submit_review给出no_blocking_findings、changes_requested或inconclusive，并写明依据，然后才给最终答复。不得写文件、批准、整合或冒充作者。最终给出明确的问题或无已发现问题，阅读完成不等于业务验证通过。");return request;};
            JSONArray acceptance=new JSONArray(),members=nativeTeamRun.getJSONArray("members");if(nativeTeamRun.optJSONObject("workPlan")!=null){JSONArray items=nativeTeamRun.getJSONObject("workPlan").getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);acceptance.put(new JSONObject().put("workItemId",item.getString("workItemId")).put("assignmentId",item.getString("assignmentId")).put("memberId",item.getString("memberId")).put("assignment",item.getString("instruction")).put("acceptance",item.optString("acceptance")).put("dependsOn",item.getJSONArray("dependsOn")));}}else for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);acceptance.put(new JSONObject().put("memberId",member.getString("id")).put("assignment",member.getString("assignment")).put("acceptance",member.optString("acceptance")));}
            RunOutcome outcome=new AgentRunner(null,originalTasks.meter(files,original),ContextBudgetPolicy.currentHostSource(),projection,null).run(original.getString("taskId"),"原共同目标："+nativeTeamRun.getString("goal")+"\n原分工与验收："+acceptance+"\n本次精确冻结计划及实际差异："+actual.snapshot().toString(),route==null?model.withStepCeiling(original.getInt("stepCeiling")):route.forRunner(),control,event->{try{if("result".equals(event.optString("type"))){terminalEvent.set(new JSONObject(event.toString()));return;}originalTasks.event(original,event);}catch(Exception failure){control.cancel();throw new IllegalStateException(failure);}},originalTasks.journal(original,control));
            closeFence(liveFence);control.check();if(terminalEvent.get()!=null)originalTasks.event(original,terminalEvent.get());JSONObject result=tasks.finishTeamReview(original,outcome,files.proof(),control,route);
            // The SQL result is authoritative once settlement returns. Notification or
            // display refresh failure must not rewrite that result as an interrupted run.
            settled=true;files.close();changed.accept(original.getString("taskId"));return result;
        }catch(Exception failure){if(!settled&&binding!=null&&!binding.optBoolean("inspectOnly"))tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),control.isCancelled()?"cancelled":"interrupted","独立审查未取得确定终态；原阅读与模型回执保留",new JSONObject());throw failure;}
        finally{closeFence(liveFence);reviews.remove(run,control);if(binding!=null)integrationTasks.remove(binding.optString("taskId"),control);}
    }
    /** One original private-window review. Only this host may arm it inside serialized admission. */
    public final class RemainderReview {
        private final String id,run,team,review,hash,route,shown;
        private final int epoch;private final long until;
        private final TeamCoordinator coordinator;private final TeamCoordinator.WorkMutationSource source;
        private final java.util.function.BooleanSupplier window;private boolean used,armed;
        private RemainderReview(TeamCoordinator coordinator,TeamCoordinator.WorkMutationSource source,JSONObject facts,String route,java.util.function.BooleanSupplier window)throws Exception{
            this.coordinator=coordinator;this.source=source;this.window=window;this.route=route;shown=TeamWorkFiles.canonical(facts);
            id=facts.getString("taskId");run=facts.getString("runId");epoch=facts.getInt("epoch");team=facts.getString("teamRunId");review=facts.getString("reviewId");hash=facts.getString("planHash");
            until=android.os.SystemClock.elapsedRealtime()+120000;
        }
        private TaskStoreTeamWriteHost issuer(){return TaskStoreTeamWriteHost.this;}
        public JSONObject view()throws Exception{return new JSONObject(shown);}
        public JSONArray approvedFiles()throws Exception{return view().getJSONArray("approvedFiles");}
        public void requireWindowBeforeCommit(){current();if(!used||armed)throw new SecurityException("原主人审阅尚未消费。");}
        private void current(){if(!window.getAsBoolean()||android.os.SystemClock.elapsedRealtime()>=until)throw new SecurityException("原私人窗口已离开或审阅已到期，请重新核对剩余文件。");}
        /** Caller is the native store, never handle() or a model/JSON caller. */
        public void requireAdmission(String id,String run,int epoch,String team,String review,String hash,String route){
            current();if(!armed||!used||!this.id.equals(id)||!this.run.equals(run)||this.epoch!=epoch||!this.team.equals(team)||!this.review.equals(review)||!this.hash.equals(hash)||!this.route.equals(route))
                throw new SecurityException("这不是主人刚刚核对的原执行、文件或模型。");
            armed=false; // consume before startAttempt, including failed admission
        }
    }
    private JSONObject remainingFacts(JSONObject facts)throws Exception{
        JSONObject ctx=facts.getJSONObject("context"),b=ctx.getJSONObject("binding"),files=TeamFileRemainder.files(workspace,ctx,facts.getJSONArray("approved").length()>0);
        JSONArray remaining=files.getJSONArray("remaining"),approved=facts.getJSONArray("approved");int n=approved.length()>0?approved.length():remaining.length();JSONObject parent=facts.getJSONObject("parent"),pool=facts.getJSONObject("budget");
        if(facts.getInt("stepCeiling")-facts.getInt("stepsUsed")<n||facts.getInt("operationsLeft")<n||pool.getInt("remainingToolOperations")<n
                ||pool.getInt("remainingModelRequests")<1||parent.getInt("remainingOperations")<n)throw new SecurityException("原父任务或整合者的可用额度不足，未重置额度或调用模型。");
        for(int i=0;i<approved.length();i++){JSONObject a=approved.getJSONObject(i);
            if(!TeamRemainingSelection.contains(remaining,a.getString("path"),a.getString("artifactId")))throw new SecurityException("有一项已有许可不属于本次剩余原文件，未挪用。");}
        return files.put("approvedFiles",approved).put("continuationKind",approved.length()>0?"approved_original_files":"recovered_remainder").put("taskId",b.getString("taskId")).put("runId",b.getString("runId")).put("epoch",b.getInt("coordinatorEpoch"))
                .put("teamRunId",b.getString("teamRunId")).put("targetProjectId",b.getString("targetProjectId")).put("planHash",b.getString("planHash")).put("reviewId",b.getString("reviewId"))
                .put("parentTaskId",parent.getString("parentTaskId")).put("parentRunId",parent.getString("parentRunId")).put("activeParentRunId",parent.getString("activeParentRunId"))
                .put("activeParentEpoch",parent.getInt("activeParentEpoch")).put("parentOperationsLeft",parent.getInt("remainingOperations")).put("grantExpiresAt",facts.getLong("expiresAt"))
                .put("parentExpiresAt",parent.getLong("expiresAt")).put("stepsUsed",facts.getInt("stepsUsed")).put("stepCeiling",facts.getInt("stepCeiling")).put("operationsLeft",facts.getInt("operationsLeft"))
                .put("poolOperationsLeft",pool.getInt("remainingToolOperations")).put("poolModelsLeft",pool.getInt("remainingModelRequests"))
                .put("approvalsDigest",TeamWorkFiles.hash(TeamWorkFiles.canonical(approved))).put("executionAuthorized",false);
    }
    RemainderReview reviewRemaining(TeamCoordinator coordinator,String id,String run,int epoch,ModelConfig model,java.util.function.BooleanSupplier window)throws Exception {
        if(!window.getAsBoolean())throw new SecurityException("请进入原私人整合任务。");String route=TeamFileRemainder.route(model);
        JSONObject original=tasks.originalTeamRemainder(id,run,epoch,route);String team=original.getJSONObject("context").getString("teamRunId");
        synchronized(coordinator){
            TeamCoordinator.WorkMutationSource source=coordinator.workMutationSource(team);source.checkFileRemainder();if(!team.equals(source.view().opt("id")))throw new SecurityException("原团队身份改变。");
            JSONObject current=remainingFacts(tasks.originalTeamRemainder(id,run,epoch,route));source.checkFileRemainder();
            if(!window.getAsBoolean()||integrationTasks.containsKey(id))throw new SecurityException("原私人窗口或整合控制器已改变。");
            return new RemainderReview(coordinator,source,current,route,window);
        }
    }
    private static JSONArray approvedFileNames(JSONArray approvals)throws Exception{
        JSONArray names=new JSONArray();for(int i=0;i<approvals.length();i++){JSONObject a=approvals.getJSONObject(i);names.put(new JSONObject().put("path",a.getString("path")).put("artifactId",a.getString("artifactId")));}return names;
    }
    JSONObject continueRemaining(RemainderReview exact,ModelConfig model,java.util.function.Consumer<JSONObject> admitted)throws Exception{
        if(exact==null||exact.issuer()!=this)throw new SecurityException("请重新查看原文件清单。");
        JSONObject result=integrate(exact.team,exact.review,model,exact,java.util.Objects.requireNonNull(admitted));
        return exact.coordinator.recordIntegration(exact.team,result);
    }
    /** Called only by the native owner request worker after an exact existing review, never by synthesis. */
    public JSONObject integrate(String run,String reviewId,ModelConfig model)throws Exception {
        return integrate(run,reviewId,model,null,null);
    }
    private JSONObject integrate(String run,String reviewId,ModelConfig model,RemainderReview remainder,java.util.function.Consumer<JSONObject> admitted)throws Exception {
        String route=TeamFileRemainder.route(model);RunControl control=new RunControl();RunControl active;
        if(remainder!=null){
            synchronized(remainder.coordinator){
                synchronized(remainder){remainder.current();if(remainder.used)throw new SecurityException("这份续接审阅已使用。");remainder.used=true;}
                remainder.source.checkFileRemainder();remainder.coordinator.workMutationSource(run);
                synchronized(integrations){if(reviews.containsKey(run))throw new SecurityException("原团队正在独审，未接管其他执行。");active=integrations.putIfAbsent(run,control);}
            }
        }else synchronized(integrations){active=integrations.putIfAbsent(run,control);}
        if(active!=null){if(remainder!=null)throw new SecurityException("原整合者已有控制器，未附着、恢复或重复派发。");JSONObject actual=integrationState(run);if(!reviewId.equals(actual.optString("reviewId")))throw new SecurityException("不是原唯一整合者审查");if(active.isPaused()){if(tasks.requiresTeamFileRemainderReview(run,reviewId))throw new SecurityException("原部分写回已有暂停控制器，请在原任务处理，不能从整合命令代为恢复。");active.resume();return actual.put("continuationRequested",true);}throw new IllegalStateException("原唯一整合者仍在执行，请查看原任务");}
        JSONObject binding=null;boolean settled=false;java.util.concurrent.atomic.AtomicReference<AutoCloseable> liveFence=new java.util.concurrent.atomic.AtomicReference<>();try {
            if(remainder==null)binding=tasks.startTeamIntegration(run,reviewId,control,Math.min(24,model.maxSteps),route,null);
            else synchronized(remainder.coordinator){
                remainder.source.checkFileRemainder();synchronized(tasks){
                    JSONObject now=remainingFacts(tasks.originalTeamRemainder(remainder.id,remainder.run,remainder.epoch,route));
                    if(!route.equals(remainder.route)||!remainder.shown.equals(TeamWorkFiles.canonical(now))||reviews.containsKey(run)||integrationTasks.containsKey(remainder.id))throw new SecurityException("原文件、批准、预算或执行已改变，请重新核对。");
                    remainder.current();remainder.armed=true;
                    try{binding=tasks.startTeamIntegration(run,reviewId,control,Math.min(24,model.maxSteps),route,remainder);}finally{remainder.armed=false;}
                    if(binding.optBoolean("inspectOnly"))throw new SecurityException("原整合者不再可续，没有执行。");
                    binding.put("onlyRemainingFiles",now.getJSONArray("remaining"));
                    if(now.getJSONArray("approvedFiles").length()>0)binding.put("ownerApprovedOriginalFiles",now.getJSONArray("approvedFiles"));
                }
            }
            if(binding.optBoolean("inspectOnly")){integrations.remove(run,control);return integrationState(run);}
            integrationTasks.put(binding.getString("taskId"),control);
            if(remainder!=null){JSONObject started=new JSONObject().put("taskId",remainder.id).put("fromRunId",remainder.run).put("runId",binding.getString("runId")).put("epoch",binding.getInt("coordinatorEpoch")).put("started",true).put("filesWrittenVerified",false);
                try{admitted.accept(started);}catch(RuntimeException ignored){/* presentation cannot revoke committed admission */}}
            JSONObject original=new JSONObject(binding.toString());TeamIntegrationFiles filePort=new TeamIntegrationFiles(workspace,originalTasks.fileHost(original,control),control,original);TeamProjectWorkspaces projectHost=projects;boolean fullProject=tasks.teamProjectIntegrationSelection(original,control)!=null;if(fullProject&&projectHost==null)throw new SecurityException("原完整项目宿主未挂载，不能把仅文件CAS当完整项目交付");dev.pocketengine.core.CapabilityPort port=fullProject?projectHost.integratorPort(original,control,filePort):filePort;
            TeamIntegrationCompaction historySession=TeamIntegrationCompaction.open(tasks,original,control,model,port);liveFence.set(control.retainAuthorityCheck(historySession::checkCurrent));
            java.util.concurrent.atomic.AtomicReference<JSONObject> terminalEvent=new java.util.concurrent.atomic.AtomicReference<>();AgentRunner.RequestProjection projection=(history,config,live)->{if(live!=control||!live.matchesHostRun(original.getString("taskId"),original.getString("runId"),original.getInt("coordinatorEpoch")))throw new SecurityException("唯一整合者执行身份已变化");live.check();JSONArray request=new JSONArray(history.toString());request.getJSONObject(0).put("content",(fullProject?"本次是完整项目交付。先按下列规则完成全部原成果CAS，再调用team_prepare_integrated_project准备主人完整固定基线与准确已审输出的唯一结果副本；不得从成员目录引入额外文件。之后使用原project_inspect/search/read。按此唯一整合者的teamProjectChecks，用project_plan的intent=command、实际当前expectedSourceDigest和对应teamCheckRef生成精确方案，不猜或重写命令；每个方案须主人批准当前sourceDigest。运行所有requiredForFreeze同版检查并确认真实退出/资源释放，再用intent=freeze_package/mode=source_project/明确entryPath生成并执行原生项目包方案，最后project_package按原executionId回读固定成果。不可编辑该组合副本、切换环境、用任意命令模板或覆盖父活跃checkout；命令同应用UID执行，不是OS沙箱。完整包是固定基线结果，不得声称当前主项目已测试。只有这些真实证据齐备才能收尾。\n":"")+"你是机主审查后的唯一文件整合者。本次原生planHash="+original.getString("planHash")+"；旧计划的读取和应用回执不能替代本计划。只能使用本次team_read_integration和team_integrate_file。先读原审查计划，只能逐项选择其中的原artifactId和path。team_read_integration默认是本轮有界索引；hasMore时按nextOffset/snapshotHash继续，完整冻结证据按view=evidence和原path/artifactId范围读取，旧审计按view=history再用其精确operation引用分块读取。页或来源变化须从第一页重读；旧内容不授权新操作。本child当前执行原上下文可用memory_raw_query(section=messages或operations)检索，再memory_raw_read按准确sourceId/revision分页回读；不能查询其他任务、私人记忆或outputs。历史整理是单独计同父池的SUMMARY请求，整理内容仍是低信任资料，不替代原工具事实或批准。project_plan以原planRef/planDigest/stepId执行，完整清单由project_plan_manifest按需读取，不依赖legacy inline副本。不得生成、修改、拼接或自动合并文件内容。所有文件副作用仍须原prepared操作、主人精确批准及当前版本CAS；暂停、冲突或未知效果时等待主人核对，不重放。只有实际逐文件回执齐备才说明整合完成，不代表业务验证通过。");return request;};
            RunOutcome outcome=new AgentRunner(null,originalTasks.meter(historySession.port(),original),ContextBudgetPolicy.currentHostSource(),projection,null).run(original.getString("taskId"),"执行主人审查的原团队计划 "+original.getString("planHash")+"。请先读取原整合计划；每项只提交原path/artifactId，不产生新内容。"+(original.has("onlyRemainingFiles")?"本次主人只选择了剩余清单："+original.getJSONArray("onlyRemainingFiles")+"。跳过所有已写回文件；不要再为它们登记工具操作。":"")+(original.has("ownerApprovedOriginalFiles")?"先处理已获原机主准确批准的文件（path/artifactId）："+approvedFileNames(original.getJSONArray("ownerApprovedOriginalFiles"))+"。其他文件若需写回，须单独向机主申请；本轮不得派发它们。":""),model.withStepCeiling(original.getInt("stepCeiling")),control,event->{try{if("result".equals(event.optString("type"))){terminalEvent.set(new JSONObject(event.toString()));return;}originalTasks.event(original,event);}catch(Exception failure){control.cancel();throw new IllegalStateException(failure);}},historySession.journal());
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
                // Set only after the original SQL settlement has returned successfully;
                // later map cleanup, notification and readback do not own terminal state.
                settled=true;
            }
            integrations.remove(run,control);integrationTasks.remove(original.getString("taskId"),control);changed.accept(original.getString("taskId"));return integrationState(run);
        }catch(Exception failure){if(!settled&&binding!=null&&!binding.optBoolean("inspectOnly"))try{tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),control.isCancelled()?"cancelled":"interrupted","原唯一整合者未取得确定终态，实际逐文件回执保留；未知效果只核对、不重放",new JSONObject());changed.accept(binding.getString("taskId"));}catch(Exception persistence){failure.addSuppressed(persistence);}throw failure;}
        finally{closeFence(liveFence);integrations.remove(run,control);if(binding!=null&&!binding.optString("taskId").isEmpty())integrationTasks.remove(binding.optString("taskId"),control);}
    }
    JSONObject recoverOriginalFile(TeamCoordinator coordinator,String id,String run,int epoch,String operation,java.util.function.BooleanSupplier ownerWindow)throws Exception{
        JSONObject selection=tasks.pendingTeamFileEffect(id,run,epoch,operation);String team=selection.getJSONObject("binding").getString("teamRunId");
        java.util.function.BooleanSupplier stopped=()->ownerWindow.getAsBoolean()&&!integrations.containsKey(team)&&!integrationTasks.containsKey(id);
        if(!stopped.getAsBoolean())throw new SecurityException("原整合尚未退出或私人窗口已关闭");
        TeamIntegrationFileRecovery.Observed proof=TeamIntegrationFiles.recoverOriginal(workspace,selection);
        JSONObject receipt=tasks.recoverTeamFileReceipt(proof,stopped);
        if(receipt.optBoolean("receiptRestored")){
            try{
                JSONObject pointer=tasks.recoveredOriginalTeamFileSource(team);
                if(pointer!=null&&operation.equals(pointer.opt("operationId"))&&run.equals(pointer.opt("runId"))&&ownerWindow.getAsBoolean()&&TeamIntegrationDeliveryNote.readableRecovery(coordinator.retainedOwnerReading(team))){
                    JSONObject actual=integrationState(team);
                    if(ownerWindow.getAsBoolean())receipt.put("originalTeamObservationSaved",coordinator.recordRecoveredFiles(team,actual,pointer));
                }
            }catch(Exception laterDisplayFailure){receipt.put("originalTeamObservationSaved",false);} // SQL recovery already committed; original views reread it.
        }
        try{changed.accept(id);}catch(RuntimeException stalePresentation){receipt.put("refreshRequired",true);}
        return receipt; // A notification failure cannot undo the already committed original receipt.
    }
    /** Original SQL remains the source if a restart split receipt settlement from team presentation. */
    JSONObject recoveredFilesForReading(TeamCoordinator coordinator,String team,JSONObject knownState)throws Exception {
        JSONObject pointer=tasks.recoveredOriginalTeamFileSource(team);if(pointer==null)return null;
        JSONObject run=coordinator.retainedOwnerReading(team);if(!TeamIntegrationDeliveryNote.readableRecovery(run))return null;
        JSONObject actual=knownState==null?integrationState(team):knownState;
        return TeamIntegrationDeliveryNote.recovered(run,actual,pointer);
    }
    TeamParentDeliveryWait parentDeliveryWait(JSONObject checkedPage)throws Exception {
        String team=checkedPage.getString("teamRunId");
        return TeamParentDeliveryWait.capture(checkedPage,finalWorkers(team),integrationTasks::get,
                role->"reviewer".equals(role)?reviews.get(team):"integrator".equals(role)?integrations.get(team):null);
    }
    JSONObject finalWorkers(String team)throws Exception {
        return finalWorkers(team,null);
    }
    JSONObject finalWorkers(String team,JSONObject ownerReading)throws Exception {
        RunControl review=reviews.get(team),integration=integrations.get(team);JSONObject sql;
        try{sql=tasks.teamFinalWorkerTasks(team);}catch(Exception incomplete){sql=TeamDeliveryWorkers.unavailable(team);}
        // Include controls seen on either side of the SQL read; a retiring fence must not be reported drained.
        RunControl nowReview=reviews.get(team),nowIntegration=integrations.get(team);
        JSONObject settled=ownerReading!=null&&team.equals(ownerReading.opt("id"))?
                TeamIntegrationDeliveryNote.settledOriginalWorker(ownerReading,sql.getJSONArray("workers")):null;
        JSONObject observed=TeamDeliveryWorkers.observed(sql,review!=null||nowReview!=null,integration!=null||nowIntegration!=null,row->{
            RunControl c=integrationTasks.get(row.optString("taskId"));
            return c!=null&&(c==review||c==nowReview||c==integration||c==nowIntegration)&&c.matchesHostRun(row.optString("taskId"),row.optString("runId"),row.optInt("epoch",-1));
        });
        // Keep completed originals out of the pending-roles count and out of the model/tool path.
        if(settled!=null&&observed.getBoolean("resourcesReleased"))observed.put("settledOriginal",settled);
        return observed;
    }
    public void cancel(String run){RunControl control=integrations.get(run);if(control!=null)control.cancel();control=reviews.get(run);if(control!=null)control.cancel();}
    public void pause(String run){RunControl control=integrations.get(run);if(control!=null)control.pause();control=reviews.get(run);if(control!=null)control.pause();}
    public void resume(String run){RunControl control=integrations.get(run);if(control!=null)control.resume();control=reviews.get(run);if(control!=null)control.resume();}
    @Override public void close(){for(RunControl control:integrations.values())control.cancel();for(RunControl control:reviews.values())control.cancel();}
}
