package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectOwnerWorkflows;
import dev.pocketengine.features.local.project.ProjectPackageDelivery;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native fixed-result -> original new private parent admission. No Runner, grant cache or package codec here. */
public final class TeamProjectRoundHost {
    public static final class NotAccepted extends IllegalStateException {private NotAccepted(Throwable cause){super("原生确认尚无该输入或复制尝试；可保留草稿重新审阅",cause);}}
    private final TaskStore tasks;private final LocalModule local;private final TeamWorkFiles.Workspace workspace;private final ProjectOwnerWorkflows owner;
    private final TaskStoreTeamWriteHost.CurrentModel models;private final java.util.function.Consumer<String> changed;
    public TeamProjectRoundHost(TaskStore tasks,LocalModule local,TeamWorkFiles.Workspace workspace,ProjectOwnerWorkflows owner,TaskStoreTeamWriteHost.CurrentModel models,java.util.function.Consumer<String> changed){this.tasks=java.util.Objects.requireNonNull(tasks);this.local=java.util.Objects.requireNonNull(local);this.workspace=java.util.Objects.requireNonNull(workspace);this.owner=java.util.Objects.requireNonNull(owner);this.models=java.util.Objects.requireNonNull(models);this.changed=java.util.Objects.requireNonNull(changed);}
    public Page open(TeamCoordinator coordinator,String teamRun)throws Exception{return open(coordinator,teamRun,null);}
    public Page open(TeamCoordinator coordinator,String teamRun,String failedWorkItem)throws Exception{return new Page(coordinator,teamRun,failedWorkItem,tasks.openTeamWorkWindow(teamRun));}
    private void requireHeads(TaskStore.TeamProjectRoundSource source)throws Exception {source.checkCurrent();if(source.view().optString("sourceKind").equals("failed_work_copy"))return;JSONObject integration=source.view().getJSONObject("integration");if(!TeamIntegrationFiles.progress(workspace,integration.getJSONObject("binding"),integration.getJSONArray("receipts")).getBoolean("complete"))throw new SecurityException("原项目当前head已离开所选成果；可打开固定版本，但不能沿旧基线暗盖新文件");source.checkCurrent();}
    private static final class Captured {final LocalModule.TeamProjectSource fixed;final JSONObject reference;Captured(LocalModule.TeamProjectSource fixed,JSONObject reference){this.fixed=fixed;this.reference=reference;}}
    private Captured capture(TaskStore.TeamProjectRoundSource original)throws Exception {
        original.checkCurrent();JSONObject facts=original.view();if(facts.optString("sourceKind").equals("failed_work_copy")){
            LocalModule.TeamProjectSource fixed=TeamProjectWorkspaces.captureKnownFailedCopy(local,facts.getJSONObject("projectContext"),facts.getJSONArray("selectedFiles"));JSONObject pin=fixed.view();JSONObject reference=new JSONObject().put("kind","known_failed_work_copy").put("taskId",facts.getString("oldTaskId")).put("runId",facts.getString("oldRunId")).put("coordinatorEpoch",facts.getInt("oldEpoch")).put("workItem",facts.getJSONObject("failedWorkItem")).put("checkoutId",facts.getJSONObject("projectContext").getString("checkoutId")).put("snapshotIdentity",pin.getString("snapshotIdentity")).put("sourceDigest",pin.getString("sourceDigest")).put("completeDeliveryVerified",false);original.checkCurrent();return new Captured(fixed,reference);
        }
        JSONObject evidence=facts.getJSONObject("projectResult").getJSONObject("evidence"),artifact=evidence.getJSONObject("packageArtifact"),catalog=local.handle("project.package.get",new JSONObject().put("artifactId",artifact.getString("id")).put("sha256",artifact.getString("sha256")));ProjectPackageDelivery.Reference reference=ProjectPackageDelivery.requireArtifact(evidence,catalog);LocalModule.TeamProjectSource fixed=local.captureTeamProjectPackageForHost(reference.artifactId,reference.archiveSha256);JSONObject captured=fixed.view();if(!captured.getString("sourceDigest").equals(evidence.getString("sourceDigest")))throw new SecurityException("固定完整包并非所选同版检查的准确源码");for(String key:new String[]{"files","directories"})if(!TeamWorkFiles.canonical(captured.getJSONObject("manifest").get(key)).equals(TeamWorkFiles.canonical(catalog.getJSONObject("manifest").get(key))))throw new SecurityException("固定包字节/目录/执行位不符");original.checkCurrent();return new Captured(fixed,reference.json());
    }
    public static final class Review {
        private final TeamProjectRoundHost issuer;private final TaskStore.TeamProjectRoundSource source;private final LocalModule.TeamProjectSource fixed;private final ModelConfig model;private final String value,digest;
        private Review(TeamProjectRoundHost issuer,TaskStore.TeamProjectRoundSource source,LocalModule.TeamProjectSource fixed,ModelConfig model,JSONObject value)throws Exception{this.issuer=issuer;this.source=source;this.fixed=fixed;this.model=model;this.value=value.toString();digest=TaskStore.capabilityFingerprint(value);}
        public TaskStore.TeamProjectRoundSource source(){return source;}
        public boolean belongsTo(TaskStore expected){return issuer.tasks==expected&&source.belongsTo(expected);}
        public JSONObject view()throws Exception{return new JSONObject(value).put("digest",digest);}
        private void filesCurrent()throws Exception {
            JSONObject shown=new JSONObject(value),facts=source.view(),now;
            if(facts.optString("sourceKind").equals("failed_work_copy"))now=TeamProjectWorkspaces.captureKnownFailedCopy(issuer.local,facts.getJSONObject("projectContext"),facts.getJSONArray("selectedFiles")).view();
            else{JSONObject pin=shown.getJSONObject("sourceReference");now=issuer.local.captureTeamProjectPackageForHost(pin.getString("id"),pin.getString("sha256")).view();}
            if(!shown.getString("sourceSnapshotIdentity").equals(now.getString("snapshotIdentity"))||!shown.getString("expectedSourceDigest").equals(now.getString("sourceDigest")))throw new SecurityException("原固定完整源版本不符");
            JSONArray formal=shown.getJSONArray("originalFormalFiles"),paths=new JSONArray();for(int i=0;i<formal.length();i++)paths.put(formal.getJSONObject(i).getString("path"));if(!TeamWorkFiles.canonical(formal).equals(TeamWorkFiles.canonical(TeamWorkFiles.captureFiles(issuer.workspace,shown.getString("projectId"),paths))))throw new SecurityException("目标原项目当前文件已改变，不能沿旧审阅暗盖新文件");
            ModelConfig current=issuer.models.get();if(!TeamWorkFiles.canonical(model.publicJson()).equals(TeamWorkFiles.canonical(current.publicJson()))||!model.sameCredentials(current))throw new SecurityException("新一轮模型配置已变化，请重新审阅");
            if(!shown.getString("runtimeIdentity").equals(issuer.local.projectRuntimeForHost(shown.getString("runtimeId")).getString("identity")))throw new SecurityException("本机原运行环境身份已变，请重新选择");
        }
        private void requireModel(ModelConfig actual)throws Exception{if(actual==null||!TeamWorkFiles.canonical(model.publicJson()).equals(TeamWorkFiles.canonical(actual.publicJson()))||!model.sameCredentials(actual))throw new SecurityException("原新attempt实际模型不是本次明确选择，请重新审阅");}
        public void checkCurrent()throws Exception{source.checkCurrent();filesCurrent();issuer.requireHeads(source);source.checkCurrent();}
    }
    public final class Page implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String run,failedItem;private final TaskStore.TeamWorkWindow window;private volatile boolean closed;private Review pending;private Admission started;
        private Page(TeamCoordinator coordinator,String run,String failedItem,TaskStore.TeamWorkWindow window){this.coordinator=coordinator;this.run=run;this.failedItem=failedItem;this.window=window;}
        private TaskStore.TeamProjectRoundSource selectedSource()throws Exception{return failedItem==null?tasks.teamProjectRoundSource(window,coordinator.projectRoundSource(run)):tasks.teamProjectRoundSource(window,coordinator.projectRoundSource(run),failedItem);}
        public void setForeground(boolean foreground){window.setForeground(!closed&&foreground);}
        private JSONObject inspectSource()throws Exception{
            if(closed)throw new SecurityException("原新一轮窗口已关闭");TaskStore.TeamProjectRoundSource selected=selectedSource();requireHeads(selected);JSONObject facts=selected.view();Captured captured=capture(selected);int files=0;JSONArray items=facts.getJSONObject("workPlan").getJSONArray("items");for(int i=0;i<items.length();i++)files+=items.getJSONObject(i).getJSONArray("files").length();int estimate=Math.min(128,Math.max(12,6*files+10+items.length()*4));selected.checkCurrent();return new JSONObject().put("source",facts).put("sourceReference",captured.reference).put("proposedNewModelRequestLimit",estimate).put("proposedNewToolOperationLimit",estimate).put("strictMinimum",false).put("complexFlowGuaranteed",false).put("oldGrantRestored",false).put("codeExecutionAuthorized",false);
        }
        public JSONObject inspect()throws Exception{if(closed)throw new SecurityException("原新一轮窗口已关闭");JSONArray history=tasks.teamProjectRoundSubmissionsForOwner(window);try{return inspectSource().put("ready",true).put("previousSubmissions",history);}catch(SecurityException|IllegalStateException blocked){return new JSONObject().put("ready",false).put("blockedReason",blocked.getMessage()).put("previousSubmissions",history).put("executionAuthorized",false);}}
        public synchronized Review review(String goal,int newModelLimit,int newToolLimit)throws Exception {
            if(closed||started!=null)throw new SecurityException("本窗口已有原提交，请查原输入/复制操作");goal=goal.trim();if(goal.isEmpty()||goal.length()>12000||newModelLimit<3||newModelLimit>TeamParentBudget.MAX_PROJECT_MODEL_REQUESTS||newToolLimit<3||newToolLimit>TeamParentBudget.MAX_PROJECT_TOOL_OPERATIONS)throw new IllegalArgumentException("请填写1至12000字新目标与3至128的明确新模型/工具上限");TeamWorkFiles.hash(goal);
            TaskStore.TeamProjectRoundSource source=selectedSource();requireHeads(source);JSONObject facts=source.view();Captured original=capture(source);LocalModule.TeamProjectSource fixed=original.fixed;JSONObject captured=fixed.view();String project=facts.getJSONObject("workPlan").getString("targetProjectId");JSONArray paths=new JSONArray();if(failedItem!=null){JSONArray selected=facts.getJSONArray("selectedFiles");for(int i=0;i<selected.length();i++)paths.put(selected.getJSONObject(i).getString("path"));}else{java.util.Set<String> seen=new java.util.HashSet<>();JSONArray items=facts.getJSONObject("workPlan").getJSONArray("items");for(int i=0;i<items.length();i++){JSONArray files=items.getJSONObject(i).getJSONArray("files");for(int f=0;f<files.length();f++)if(seen.add(files.getJSONObject(f).getString("path")))paths.put(files.getJSONObject(f).getString("path"));}}
            JSONArray formal=TeamWorkFiles.captureFiles(workspace,project,paths);String runtime=facts.getJSONObject("scope").getJSONObject("projectSelection").getString("runtimeId");JSONObject runtimeFacts=local.projectRuntimeForHost(runtime);ModelConfig selected=models.get();String client=UUID.randomUUID().toString();JSONObject value=new JSONObject().put("format","pocketworld.team-project-round-review.v1").put("sourceKind",facts.getString("sourceKind")).put("sourceTeamRunId",run).put("clientMessageId",client).put("goal",goal).put("projectId",project).put("sourceSnapshotIdentity",captured.getString("snapshotIdentity")).put("expectedSourceDigest",captured.getString("sourceDigest")).put("sourceReference",original.reference).put("originalFormalFiles",formal).put("manifest",captured.getJSONObject("manifest")).put("runtimeId",runtime).put("runtimeIdentity",runtimeFacts.getString("identity")).put("model",selected.publicJson()).put("priorBudget",facts.getJSONObject("priorBudget")).put("priorProjectUsage",facts.getJSONObject("priorProjectUsage")).put("newModelRequestLimit",newModelLimit).put("newToolOperationLimit",newToolLimit).put("nativeCopyConsumesOneToolUnit",true).put("copyOnlyNewPrivateCheckout",true).put("oldGrantRestored",false).put("originalProjectWriteAuthorized",false).put("codeExecutionAuthorized",false).put("newTeamScopeAndEachCommandPlanRequireNewApproval",true).put("completeDeliveryVerified",failedItem==null).put("tokenCostKnown",false).put("strictMinimum",false).put("complexFlowGuaranteed",false);
            pending=new Review(TeamProjectRoundHost.this,source,fixed,selected,value);pending.checkCurrent();return pending;
        }
        public synchronized JSONObject start(Review exact,String digest)throws Exception {
            if(closed||pending==null||pending!=exact||!exact.digest.equals(digest))throw new SecurityException("请确认本前台刚审阅的准确新一轮结果与预算");if(started!=null)return owner.startTeamRound(started);exact.checkCurrent();TaskStore.TeamProjectRoundAdmission approved=tasks.approveTeamProjectRound(exact,digest);started=new Admission(TeamProjectRoundHost.this,exact,approved);pending=null;
            try{return owner.startTeamRound(started);}catch(Exception failure){if(!started.binding.get())try{owner.teamRoundSubmission(started);}catch(IllegalArgumentException missing){started=null;pending=exact;throw new NotAccepted(failure);}throw failure;}
        }
        public synchronized JSONObject querySubmission()throws Exception{if(started==null)throw new SecurityException("本窗口没有已提交的新一轮");return owner.teamRoundSubmission(started);}
        @Override public void close(){closed=true;window.close();pending=null;}
    }
    /** Only this native host can hold the fixed bytes + original SQL approval together. */
    public static final class Admission {
        private final TeamProjectRoundHost issuer;private final Review review;private final TaskStore.TeamProjectRoundAdmission approved;private final AtomicBoolean binding=new AtomicBoolean();
        private Admission(TeamProjectRoundHost issuer,Review review,TaskStore.TeamProjectRoundAdmission approved){this.issuer=issuer;this.review=review;this.approved=approved;}
        public JSONObject ownerRequest()throws Exception {JSONObject shown=review.view();return new JSONObject().put("clientMessageId",shown.getString("clientMessageId")).put("goal",shown.getString("goal"));}
        public void checkCurrent()throws Exception{approved.checkCurrent();review.checkCurrent();}
        public void verifyAcknowledgement(JSONObject acknowledgement)throws Exception {if(!acknowledgement.has("taskId"))return;JSONObject task=issuer.tasks.get(acknowledgement.getString("taskId"),false),round=task==null?null:task.optJSONObject("teamProjectRound");if(round==null||!round.getJSONObject("review").getString("digest").equals(review.digest)||!round.getJSONObject("review").getString("clientMessageId").equals(review.view().getString("clientMessageId")))throw new SecurityException("输入回执并非本次准确结果的新一轮");}
        /** Synchronous original NativeInputHost.start, after its real startAttempt/control, before Runner pickup. */
        public void bindNewAttempt(JSONObject task,RunControl original,ModelConfig actualModel)throws Exception {
            review.requireModel(actualModel);
            if(!binding.compareAndSet(false,true))throw new SecurityException("该原新一轮输入已有绑定，只可核对原操作，不能重发复制");review.checkCurrent();TaskStore.TeamProjectRoundCopy copy=issuer.tasks.prepareTeamProjectRoundCopy(approved,task,original);boolean settled=false;
            try{issuer.tasks.enterTeamProjectRoundCopy(copy);JSONObject nativeBinding=copy.view();LocalModule.TeamProjectCheckout actual=issuer.local.cloneTeamProjectForHost(review.fixed,review.view().getString("projectId"),nativeBinding,java.util.List.<LocalModule.TeamProjectOverlay>of(),copy::checkCurrent);JSONObject receipt=issuer.tasks.finishTeamProjectRoundCopy(copy,actual);settled=true;issuer.changed.accept(task.getString("id"));if(!receipt.getBoolean("executionAdmitted"))throw new InterruptedException("原副本已确定保存，但新执行已停止；只保留成果，不复活Runner");}
            catch(Exception failure){if(!settled)issuer.tasks.unknownTeamProjectRoundCopy(copy);issuer.changed.accept(task.getString("id"));throw failure;}
        }
    }
    public ContinuationPage openContinuation(String originalTaskId)throws Exception{return new ContinuationPage(originalTaskId,tasks.openTeamRoundContinuationWindow(originalTaskId));}
    private LocalModule.TeamProjectSource continuationSource(String taskId)throws Exception {
        JSONObject task=tasks.get(taskId,false),context=task.getJSONObject("developmentContext");JSONObject observed=dev.pocketengine.features.local.project.ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",context.getString("checkoutId"))));if(!context.getString("projectId").equals(observed.getString("projectId")))throw new SecurityException("原round副本项目已变");return local.captureTeamProjectSourceForHost(context.getString("checkoutId"),observed.getString("sourceDigest"));
    }
    private static boolean sameModel(ModelConfig a,ModelConfig b)throws Exception{return a!=null&&b!=null&&TeamWorkFiles.canonical(a.publicJson()).equals(TeamWorkFiles.canonical(b.publicJson()))&&a.sameCredentials(b);}
    /** Private native foreground review of the existing pool, NOT another budget or task authority. */
    public final class ContinuationPage implements AutoCloseable {
        private final String taskId;private final TaskStore.TeamRoundContinuationWindow window;private boolean closed;private ContinuationReview pending;private Continuation submitted;
        private ContinuationPage(String taskId,TaskStore.TeamRoundContinuationWindow window){this.taskId=taskId;this.window=window;}
        public void setForeground(boolean value){window.setForeground(!closed&&value);}
        public JSONObject inspect()throws Exception{if(closed)throw new SecurityException("原续审窗口已关闭");JSONObject current=tasks.get(taskId,false),pool=current.getJSONObject("teamReservationPool");return new JSONObject().put("taskId",taskId).put("status",current.getString("status")).put("runId",current.getString("runId")).put("epoch",current.getInt("coordinatorEpoch")).put("scopeId",pool.getString("parentRunId")).put("parentBudget",TeamParentBudget.snapshot(pool)).put("newBudgetUnits",0).put("extendsGrantExpiry",false).put("addsFileOrCommandAuthority",false);}
        public synchronized ContinuationReview review(String feedback)throws Exception {
            if(closed||submitted!=null)throw new SecurityException("原续接已有提交，只查询本任务，不能重放");if(feedback==null)feedback="";feedback=feedback.trim();if(feedback.length()>4000)throw new IllegalArgumentException("明确补充最多4000字符");TeamWorkFiles.hash(feedback);LocalModule.TeamProjectSource fixed=continuationSource(taskId);TaskStore.TeamRoundContinuationReview exact=tasks.prepareTeamRoundContinuation(window,fixed);ModelConfig selected=models.get();JSONObject value=exact.view();value.remove("digest");value.put("feedback",feedback).put("model",selected.publicJson()).put("sourceSnapshotIdentity",fixed.view().getString("snapshotIdentity"));pending=new ContinuationReview(TeamProjectRoundHost.this,exact,selected,value);return pending;
        }
        public synchronized JSONObject start(ContinuationReview exact,String digest)throws Exception {
            if(closed||pending==null||exact!=pending||!exact.digest.equals(digest)||submitted!=null)throw new SecurityException("请在原前台确认本次准确原余额及新执行代次");exact.checkCurrent();submitted=new Continuation(exact);pending=null;return owner.continueTeamRound(submitted);
        }
        public JSONObject query()throws Exception{if(closed)throw new SecurityException("原续审窗口已关闭");return inspect().put("queryOnly",true).put("submitted",submitted!=null);}
        public void close(){closed=true;window.close();pending=null;}
    }
    public static final class ContinuationReview {
        private final TeamProjectRoundHost issuer;private final TaskStore.TeamRoundContinuationReview sql;private final ModelConfig selected;private final String value,digest;
        private ContinuationReview(TeamProjectRoundHost issuer,TaskStore.TeamRoundContinuationReview sql,ModelConfig selected,JSONObject value)throws Exception{this.issuer=issuer;this.sql=sql;this.selected=selected;this.value=value.toString();digest=TaskStore.capabilityFingerprint(value);}
        public JSONObject view()throws Exception{return new JSONObject(value).put("digest",digest);}
        private void checkCurrent()throws Exception {sql.checkCurrent();JSONObject shown=new JSONObject(value);LocalModule.TeamProjectSource now=issuer.continuationSource(shown.getString("taskId"));if(!shown.getString("sourceSnapshotIdentity").equals(now.view().getString("snapshotIdentity"))||!shown.getString("sourceDigest").equals(now.view().getString("parentSourceDigest"))||!sameModel(selected,issuer.models.get()))throw new SecurityException("原准确源码/运行配置已变，请重新审阅；预算口不重签源码/命令");sql.checkCurrent();}
    }
    /** Only this host can combine current native source + SQL review + captured model. */
    public static final class Continuation {
        private final ContinuationReview exact;private final AtomicBoolean entered=new AtomicBoolean();
        private Continuation(ContinuationReview exact){this.exact=exact;}
        public String taskId()throws Exception{return exact.view().getString("taskId");}
        public String feedback()throws Exception{return exact.view().getString("feedback");}
        public ModelConfig model(){return exact.selected;}
        public JSONObject startOriginalAttempt(RunControl actualNewControl)throws Exception{if(!entered.compareAndSet(false,true))throw new SecurityException("该原native续接已消费，只查当前任务，不新建第二run");exact.checkCurrent();JSONObject shown=exact.sql.view();return exact.issuer.tasks.startReviewedTeamRoundContinuation(exact.sql,shown.getString("digest"),actualNewControl);}
    }

}
