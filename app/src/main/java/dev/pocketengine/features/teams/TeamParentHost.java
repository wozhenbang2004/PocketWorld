package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native parent-model facade over the ORIGINAL coordinator, children, workspace and SQL authority.
 * No Agent loop, task store, scheduler or independent budget is introduced here. */
public final class TeamParentHost {
    public interface Definitions {JSONObject get(String teamId)throws Exception;}
    private final TaskStore tasks;private final TeamWorkFiles.Workspace workspace;private final TeamCoordinator coordinator;private final TaskStoreTeamWriteHost writes;private final TaskStoreTeamWriteHost.CurrentModel models;private final Definitions definitions;private final Consumer<String> changed;private final Consumer<Runnable> cleanup;private final dev.pocketengine.features.FeatureContext context;
    TeamParentHost(TaskStore tasks,TeamWorkFiles.Workspace workspace,TeamCoordinator coordinator,TaskStoreTeamWriteHost writes,TaskStoreTeamWriteHost.CurrentModel models,Definitions definitions,Consumer<String> changed,Consumer<Runnable> cleanup,dev.pocketengine.features.FeatureContext context){this.tasks=tasks;this.workspace=workspace;this.coordinator=coordinator;this.writes=writes;this.models=models;this.definitions=definitions;this.changed=changed;this.cleanup=cleanup;this.context=context;}
    public JSONObject execute(String task,String run,int epoch,String tool,String operation,JSONObject arguments,RunControl original)throws Exception {
        TaskStore.TeamParentOrigin source=tasks.teamParentOrigin(task,run,epoch,tool,operation,arguments,original);
        JSONObject result;
        switch(tool){
            case "teams_plan":{JSONObject selected=definitions.get(arguments.getString("teamId"));TeamParentPlan captured=TeamParentPlan.capture(source,workspace,selected,arguments);if(context!=null&&context.teamProfileModels()!=null)captured=context.teamProfileModels().plan(captured,original);result=tasks.recordTeamParentPlan(source,captured);break;}
            case "teams_start":result=start(source,arguments,original);break;
            case "teams_get":result=read(source,arguments,original,false);break;
            case "teams_wait":result=read(source,arguments,original,true);break;
            case "teams_message":result=message(source,arguments);break;
            case "teams_control":result=control(source,arguments,original);break;
            default:throw new SecurityException("团队门面没有这个固定动作");
        }
        // Original Runner consumes an explicit native receipt, not JSON object truthiness.
        // Pending approvals/denials keep their existing false value; a successful observation
        // is not a failed phone operation, nor proof that the team's business goal is complete.
        if(!result.has("ok"))result.put("ok",true);return result;
    }
    private ModelConfig parentStartModel(TaskStore.TeamParentStart approved,RunControl original)throws Exception {
        ModelConfig captured=context==null||context.teamProfileModels()==null?null:context.teamProfileModels().start(approved,original);
        return captured==null?models.get():captured;
    }
    private JSONObject start(TaskStore.TeamParentOrigin source,JSONObject args,RunControl original)throws Exception {
        JSONObject row=tasks.teamParentPlan(source,args.getString("planRef"),args.getString("planHash")),plan=row.getJSONObject("plan");
        if(row.has("teamRunId"))return status(source,row.getString("teamRunId")).put("knownPlan",true).put("queryOnly",true);
        coordinator.requireParentStartable();if(!plan.getString("selectedDefinitionHash").equals(TaskStore.capabilityFingerprint(definitions.get(plan.getJSONObject("definition").getString("id")))))throw new SecurityException("原成员配置已经变化，旧提案不可启动");TeamParentPlan.checkBases(workspace,plan);
        TaskStore.TeamParentStart approved=tasks.claimTeamParentStart(source,args.getString("planRef"),args.getString("planHash"));if(!approved.dispatch()){JSONObject receipt=approved.view();return receipt.has("teamRunId")?status(source,receipt.getString("teamRunId")).put("knownPlan",true).put("queryOnly",true):receipt;}
        JSONObject actual=coordinator.startParentPlan(approved,parentStartModel(approved,original),null,null);tasks.completeTeamParentStart(approved,actual);String originalTeam=actual.getString("id");original.onCancel(()->{try{cleanup.accept(()->{try{writes.cancel(originalTeam);coordinator.cancel(originalTeam);changed.accept(originalTeam);}catch(Exception ignored){}});}catch(RuntimeException queueUnavailable){/* Original task cancellation still revokes all child SQL/currentness gates; native stop remains queryable. */}});changed.accept(originalTeam);return status(source,actual.getString("id")).put("planRef",args.getString("planRef")).put("planHash",args.getString("planHash")).put("started",true).put("businessVerified",false);
    }
    private JSONObject status(TaskStore.TeamParentOrigin source,String id)throws Exception {
        JSONObject task=tasks.teamParentAccess(source,id,false),run=coordinator.get(id),state=tasks.teamParentExecutionState(source,id),nativeState=coordinator.parentExecutorState(id);
        boolean released=state.getBoolean("resourcesReleased")&&nativeState.getBoolean("resourcesReleased");JSONObject result=new JSONObject().put("teamRunId",id).put("hostTaskId",task.getString("id")).put("parentTaskId",source.view().getString("parentTaskId")).put("status",state.getBoolean("hasUnresolvedEffects")?"blocked_unknown":run.optString("status")).put("coordinatorStatus",run.optString("status")).put("phase",run.optString("phase")).put("generation",run.getInt("generation")).put("teamRevision",run.getLong("revision")).put("eventCursor",run.getLong("revision")).put("resourcesReleased",released).put("activeNativeExecutors",nativeState.getBoolean("activeNativeExecutors")).put("hasUnresolvedEffects",state.getBoolean("hasUnresolvedEffects")).put("businessVerified",false);
        JSONObject scope=task.optJSONObject("teamWriteScope");if(scope!=null)result.put("parentBudget",tasks.teamBudgetSnapshot(id)).put("scopeExpiresAt",task.optJSONObject("teamWorkPlan")==null?0:task.getJSONObject("teamWorkPlan").getLong("expiresAt")).put("projectId",scope.getString("targetProjectId"));
        JSONArray members=new JSONArray(),slots=run.getJSONArray("members");for(int i=0;i<slots.length();i++){JSONObject slot=slots.getJSONObject(i);members.put(new JSONObject().put("memberId",slot.getString("id")).put("name",slot.optString("name")).put("role",slot.optString("role")).put("generation",slot.getInt("generation")).put("identityRevision",slot.optInt("identityRevision",1)).put("exited",slot.optBoolean("exited")).put("status",slot.optString("status")).put("paused",slot.optBoolean("paused")));}result.put("members",members);
        if(task.optJSONObject("teamWorkPlan")!=null){JSONObject current=tasks.teamWorkPlanSnapshot(id);JSONArray index=new JSONArray(),items=current.getJSONObject("workPlan").getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);JSONObject shown=TeamWorkItems.exactIdentity(item).put("title",item.getString("title")).put("status",item.optString("status")).put("blockedBy",item.getJSONArray("blockedBy")).put("artifacts",item.getJSONArray("artifacts"));JSONObject assignment=item.optJSONObject("assignmentTask");if(assignment!=null){shown.put("childRunId",assignment.optString("runId")).put("assignmentStatus",assignment.optString("status"));JSONArray questions=assignment.getJSONArray("questions"),small=new JSONArray();for(int q=Math.max(0,questions.length()-2);q<questions.length();q++){JSONObject question=questions.getJSONObject(q);small.put(new JSONObject().put("questionId",question.getString("questionId")).put("state",question.getString("state")).put("questionPreview",preview(question.optString("question"),160)).put("questionChars",question.optString("question").length()).put("previewComplete",question.optString("question").length()<=160).put("assignmentRevision",question.optInt("assignmentRevision",1)));}shown.put("questions",small).put("questionCount",questions.length()).put("questionsComplete",questions.length()<=2);}index.put(shown);}result.put("workItems",index);}
        result.put("children",state.getJSONArray("children")).put("activeChildAttempts",state.getInt("activeChildAttempts")).put("unknownChildCount",state.getInt("unknownChildCount"));
        if(TeamCoordinator.RESEARCH_FORMAT.equals(run.optString("researchExecutionFormat"))){
            result.put("parentBudget",tasks.teamBudgetSnapshot(id)).put("researchReadViews",new JSONArray().put("research_summary").put("member_output")).put("outputRangeLimit",8000).put("researchOutputs",coordinator.parentResearchOutputIndex(id));return result;
        }
        JSONObject review=tasks.teamReviewState(id);if(review.optJSONObject("independentReview")!=null)result.put("independentReview",smallReview(review.getJSONObject("independentReview")));if(review.optJSONObject("approvedReview")!=null)result.put("approvedReview",smallReview(review.getJSONObject("approvedReview")));result.put("integration",integration(id));return result;
    }
    private static JSONObject smallReview(JSONObject source)throws Exception{JSONObject out=new JSONObject();for(String key:new String[]{"taskId","runId","reviewId","planHash","status","verdict","stale","completeOriginalFilesRead","approvedAt"})if(source.has(key))out.put(key,source.get(key));return out;}
    private JSONObject integration(String id)throws Exception {
        JSONObject actual=writes.parentIntegrationState(id),out=smallReview(actual);
        for(String key:new String[]{"activeAttempt","hasUnresolved","currentFilesComplete","requiresOwnerReview","historicalStatus","progress","atomicBatch","projectDelivery"})if(actual.has(key))out.put(key,actual.get(key));
        JSONArray receipts=actual.optJSONArray("receipts"),shown=new JSONArray();
        for(int i=0;receipts!=null&&i<receipts.length();i++){
            JSONObject source=receipts.getJSONObject(i),row=new JSONObject();
            for(String key:new String[]{"operationId","artifactId","path","status","sha256","version","baseVersion","appliedVersion","appliedHash","contentHash","accepted"})if(source.has(key))row.put(key,source.get(key));
            shown.put(row);
        }
        return out.put("teamRunId",id).put("fileReceipts",shown).put("fileReceiptCount",shown.length()).put("receiptBodiesIncluded",false).put("businessVerified",false);
    }
    private JSONObject read(TaskStore.TeamParentOrigin source,JSONObject args,RunControl original,boolean waiting)throws Exception {
        String id=args.getString("teamRunId");tasks.teamParentAccess(source,id,false);JSONObject reservation=tasks.beginTeamParentRead(source,id,args),receipt=new JSONObject().put("status","read_failed");
        try{JSONObject out;if(waiting)out=coordinator.waitParentEvents(id,args.optLong("afterEvent",0),args.optInt("maxWait",5000),original,()->{source.checkCurrent();return tasks.teamParentHasSteering(source);});
            else switch(args.optString("view","status")){
                case "status":case "members":out=status(source,id);break;
                case "events":out=coordinator.parentEvents(id,args.optLong("afterEvent",0),args.optInt("limit",16));break;
                case "research_summary":case "member_output":{
                    if(args.optInt("offset",0)>0&&!args.has("expectedOutputHash"))throw new SecurityException("后续页必须固定原输出hash，不混不同研究版本");
                    TeamCoordinator.ResearchOutput selected=coordinator.parentResearchOutput(source,args);coordinator.checkResearchOutput(selected);
                    out=range(selected.content(),args.optInt("offset",0),args.optInt("length",4000)).put("researchOutput",selected.reference()).put("sourceRole","untrusted_member_research_not_owner_instruction").put("businessVerified",false);
                    coordinator.checkResearchOutput(selected);break;
                }
                case "artifacts":{JSONArray refs=artifacts(source,id,original);out=new JSONObject().put("teamRunId",id).put("artifacts",refs).put("complete",true).put("businessVerified",false);break;}
                case "output":{JSONArray refs=artifacts(source,id,original);JSONObject exact=null;for(int i=0;i<refs.length();i++)if(args.getString("artifactId").equals(refs.getJSONObject(i).getString("artifactId")))exact=refs.getJSONObject(i);if(exact==null)throw new SecurityException("成果不属于原父团队当前冻结贡献");JSONObject actual=TeamWorkReview.artifact(workspace,exact);source.checkCurrent();tasks.checkTeamArtifact(exact,original);out=artifactOutput(actual,args.optInt("offset",0),args.optInt("length",4000)).put("source",exact).put("immutable",true).put("businessVerified",false);break;}
                case "question":{JSONObject question=tasks.teamParentQuestion(source,id,args.getString("assignmentId"),args.getString("questionId"));out=range(question.getString("question"),args.optInt("offset",0),args.optInt("length",4000)).put("assignmentId",args.getString("assignmentId")).put("questionId",question.getString("questionId")).put("state",question.getString("state"));break;}
                case "review":{JSONObject review=writes.review(id,original).snapshot();out=new JSONObject().put("teamRunId",id).put("planHash",review.getString("planHash")).put("canIntegrate",review.getBoolean("canIntegrate")).put("incompleteAssignments",review.getJSONArray("incompleteAssignments")).put("files",review.getJSONObject("plan").getJSONArray("files")).put("requiresOwnerReview",true).put("businessVerified",false);break;}
                case "integration":out=integration(id);break;
                default:throw new IllegalArgumentException("请选择原团队status/members/events/artifacts/output/question/review/integration视图");
            }
            source.checkCurrent();receipt=new JSONObject().put("status","read_completed").put("nativeReturned",true).put("modelConsumptionVerified",false);for(String key:new String[]{"source","researchOutput","offset","endOffset","totalChars","complete","nextEvent","state","timedOut"})if(out.has(key))receipt.put(key,out.get(key));return out.put("queryOnly",true);
        }finally{tasks.finishTeamNativeRead(reservation,receipt,false);}
    }
    private JSONArray artifacts(TaskStore.TeamParentOrigin source,String id,RunControl original)throws Exception{tasks.teamParentAccess(source,id,false);JSONArray rows=tasks.teamAssignmentsForReview(id,original),refs=new JSONArray();for(int i=0;i<rows.length();i++){JSONArray artifacts=rows.getJSONObject(i).getJSONArray("artifacts");for(int a=0;a<artifacts.length();a++)refs.put(artifacts.getJSONObject(a));}return refs;}
    private JSONObject message(TaskStore.TeamParentOrigin source,JSONObject args)throws Exception {
        if(args.has("recipients"))return broadcast(source,args);
        String id=args.getString("teamRunId");tasks.teamParentAccess(source,id,true);JSONObject nativeInput=envelope(args);coordinator.parentSnapshot(source,nativeInput);tasks.claimTeamParentAction(source,id);
        if(args.has("questionId")){
            if(!args.has("assignmentId")||!args.has("answerId")||!args.has("answer"))throw new IllegalArgumentException("回答必须明确原assignment/question/answer ID与答案");nativeInput.put("mode","reply").put("quoteMessageId","question:"+args.getString("questionId")).put("continueSafely",args.optString("delivery","queue").equals("continue"));
            JSONObject result=coordinator.answerAssignment(nativeInput,new TeamCoordinator.AssignmentAnswers(){public JSONObject save(JSONObject input)throws Exception{return tasks.answerTeamParentQuestion(source,input);}public JSONObject claimContinuation(JSONObject input)throws Exception{source.checkCurrent();tasks.teamParentAccess(source,id,true);return writes.claimContinuation(input);}},models.get(),null,null);
            JSONObject receipt=new JSONObject();for(String key:new String[]{"questionId","answerId","state","continuationState","awaitingContinuation","duplicate","reservationId","continuationReason","executionAuthorized"})if(result.has(key))receipt.put(key,result.get(key));
            changed.accept(id);return status(source,id).put("answerReceipt",receipt).put("delivery",args.optString("delivery","queue"));
        }
        boolean wakePolicy=args.optString("delivery","queue").equals("queue")&&args.has("workItemId")&&tasks.teamWakePolicyConfigured(source,id);
        boolean researchWake=args.optString("delivery","queue").equals("queue")&&args.has("researchRound")&&!args.has("workItemId")&&tasks.teamWakePolicyConfigured(source,id);
        nativeInput.put("mode","queue");JSONObject sent=coordinator.sendParentMessage(source,nativeInput,input->tasks.queueTeamParentInput(source,input,wakePolicy?coordinator.wakeSource(id,args.getString("memberId"),args.getString("workItemId")):researchWake?coordinator.researchWakeSource(id,args.getString("memberId"),args.getInt("researchRound")):null));
        if(args.optString("delivery","queue").equals("continue")){
            if(!args.has("workItemId")&&!args.has("researchRound"))throw new IllegalArgumentException("显式continue需原workItem或准确研究阶段，普通queue不唤醒");JSONObject now=coordinator.get(id);nativeInput.put("expectedRevision",now.getLong("revision")).put("expectedGeneration",now.getInt("generation"));JSONObject slot=TeamWorkPlan.find(now.getJSONArray("members"),args.getString("memberId"));nativeInput.put("expectedMemberGeneration",slot.getInt("generation"));
            // A native continuation may already have queued its original worker before a later
            // persistence failure. Never turn an arbitrary exception into "waiting / not started".
            // The saved message stays in its original inbox; the original prepared operation and
            // parent reservation remain unresolved for reconciliation instead of being replayed.
            if(args.has("researchRound"))coordinator.continueResearchForParent(source,nativeInput,()->{tasks.admitTeamResearchParentContinuation(source,id,args.getString("memberId"),args.getInt("researchRound"));return new JSONObject();},models.get(),true);
            else coordinator.continueForParent(source,nativeInput,(nextRun,nextMember)->tasks.continueTeamParentItem(source,id,args.getString("workItemId"),args.getInt("itemRevision"),args.getInt("ownerGeneration"),nextRun,nextMember),models.get(),true);
            sent.getJSONObject("messageReceipt").put("continuationState","queued").put("budgetIncreased",false);
        }
        if(wakePolicy)sent.getJSONObject("messageReceipt").put("automaticWake",false);
        if(wakePolicy&&!sent.getJSONObject("messageReceipt").optBoolean("duplicate")){
            var nativeWake=coordinator.wakeSource(id,args.getString("memberId"),args.getString("workItemId"));var admission=tasks.prepareTeamParentWake(source,nativeWake);
            if(admission!=null){sent.getJSONObject("messageReceipt").put("wake",admission.view()).put("automaticWake",false);if(admission.hasCandidate()){
                JSONObject now=coordinator.get(id);nativeInput.put("expectedRevision",now.getLong("revision")).put("expectedGeneration",now.getInt("generation")).put("expectedMemberGeneration",TeamWorkPlan.find(now.getJSONArray("members"),args.getString("memberId")).getInt("generation"));
                coordinator.continueForWake(admission,nativeInput,models.get());sent.getJSONObject("messageReceipt").put("automaticWake",true).put("continuationState","queued").put("budgetIncreased",false);
            }}
        }
        if(researchWake){sent.getJSONObject("messageReceipt").put("automaticWake",false);if(!sent.getJSONObject("messageReceipt").optBoolean("duplicate")){
            var nativeWake=coordinator.researchWakeSource(id,args.getString("memberId"),args.getInt("researchRound"));var admission=tasks.prepareTeamParentResearchWake(source,nativeWake);
            if(admission!=null){sent.getJSONObject("messageReceipt").put("wake",admission.view());if(admission.hasCandidate()){
                JSONObject now=coordinator.get(id);nativeInput.put("expectedRevision",now.getLong("revision")).put("expectedGeneration",now.getInt("generation")).put("expectedMemberGeneration",TeamWorkPlan.find(now.getJSONArray("members"),args.getString("memberId")).getInt("generation"));
                coordinator.continueResearchForWake(source,admission,nativeInput,models.get());sent.getJSONObject("messageReceipt").put("wakeScheduling","queued_not_completion").put("continuationState","queued_not_claimed").put("budgetIncreased",false).put("wake",admission.view());
            }}
        }}
        changed.accept(id);return status(source,id).put("messageReceipt",sent.getJSONObject("messageReceipt")).put("delivery",args.optString("delivery","queue"));
    }
    private JSONObject broadcast(TaskStore.TeamParentOrigin source,JSONObject args)throws Exception {
        TeamParentBroadcast.validate(args);String id=args.getString("teamRunId");tasks.teamParentAccess(source,id,true);
        JSONArray operations=tasks.get(source.view().getString("parentTaskId"),false).getJSONArray("operations");
        tasks.claimTeamParentAction(source,id);JSONObject receipt;
        synchronized(coordinator){receipt=TeamParentBroadcast.deliver(source.view(),args,operations,new TeamParentBroadcast.Mailbox(){
            public void checkCurrent()throws Exception{source.checkCurrent();tasks.teamParentAccess(source,id,true);}
            public JSONObject snapshot()throws Exception{return coordinator.get(id);}
            public JSONObject send(JSONObject input)throws Exception{return coordinator.sendParentMessage(source,input,next->tasks.queueTeamParentInput(source,next));}
        });}
        changed.accept(id);return receipt.put("broadcastReceipt",new JSONObject(receipt.toString()));
    }
    private JSONObject control(TaskStore.TeamParentOrigin source,JSONObject args,RunControl original)throws Exception {
        String id=args.getString("teamRunId"),action=args.getString("action");tasks.teamParentAccess(source,id,true);JSONObject input=envelope(args);coordinator.parentSnapshot(source,input);
        if(action.equals("reassign")||action.equals("resume")&&!args.has("researchRound")){
            try{tasks.checkTeamParentItemWriterBeforeAction(source,id,args.getString("workItemId"),args.getInt("itemRevision"),args.getInt("ownerGeneration"));}
            catch(TaskStore.TeamParentActionDenied denied){return denied.receipt();}
        }
        switch(action){
            case "pause":case "cancel":tasks.claimTeamParentAction(source,id);JSONObject stopped=coordinator.pauseForParent(source,input,action.equals("cancel"));if(input.optString("memberId").isEmpty()){if(action.equals("cancel")){writes.cancel(id);tasks.requestCancel(id,id);}else writes.pause(id);}tasks.updateExternal(id,stopped);changed.accept(id);JSONObject stoppedState=status(source,id);return stoppedState.put("stopRequested",true).put("stopState",stoppedState.getBoolean("resourcesReleased")?"drained":"stopping");
            case "resume":if(args.has("researchRound")){
                JSONObject continued=coordinator.continueResearchForParent(source,input,()->{tasks.admitTeamResearchParentContinuation(source,id,args.getString("memberId"),args.getInt("researchRound"));return new JSONObject();},models.get(),false);tasks.updateExternal(id,continued);changed.accept(id);return status(source,id).put("budgetIncreased",false);
            }if(!args.has("workItemId"))throw new IllegalArgumentException("按原workItem逐项继续，不恢复整队cancelled或猜最新成员任务");JSONObject continued=coordinator.continueForParent(source,input,(nextRun,nextMember)->tasks.continueTeamParentItem(source,id,args.getString("workItemId"),args.getInt("itemRevision"),args.getInt("ownerGeneration"),nextRun,nextMember),models.get(),false);tasks.updateExternal(id,continued);changed.accept(id);return status(source,id).put("budgetIncreased",false);
            case "reassign":JSONObject transferred=tasks.transferTeamParentItem(source,id,args.getString("workItemId"),args.getString("newMemberId"),args.getInt("itemRevision"),args.getInt("ownerGeneration"));JSONObject refreshed=coordinator.refreshWorkItems(id,transferred);tasks.updateExternal(id,refreshed);changed.accept(id);return status(source,id).put("budgetIncreased",false);
            case "request_review":{
                tasks.claimTeamParentAction(source,id);JSONObject receipt=writes.independentlyReview(id,args.getString("expectedPlanHash"),coordinator.get(id),models.get());coordinator.recordIndependentReview(id,receipt);tasks.updateExternal(id,coordinator.get(id));changed.accept(id);return status(source,id).put("independentReview",smallReview(receipt));}
            case "request_integration":{
                tasks.claimTeamParentAction(source,id);JSONObject state=tasks.teamReviewState(id),approved=state.optJSONObject("approvedReview");if(approved==null||!approved.optString("planHash").equals(args.getString("expectedPlanHash")))return status(source,id).put("state","awaiting_owner_exact_plan_approval").put("requiresOwnerReview",true).put("message","已请求原成果审阅；模型不能批准。请主人在原团队成果页核对实际diff与planHash后明确批准，未整合");
                JSONObject previous=tasks.inspectTeamIntegration(id);if(!previous.optString("status").equals("not_started"))return status(source,id).put("state","original_integrator_already_registered").put("queryOnly",true).put("message","原唯一整合者已登记；部分/未知/暂停恢复回原生审阅，不由父模型再启");JSONObject receipt=writes.integrate(id,approved.getString("reviewId"),models.get());coordinator.recordIntegration(id,receipt);changed.accept(id);return status(source,id);}
            case "revise":{
                tasks.claimTeamParentAction(source,id);coordinator.recordParentRevision(source,input);tasks.updateExternal(id,coordinator.get(id));changed.accept(id);return status(source,id).put("state","awaiting_native_revision_review").put("workItemId",args.getString("workItemId")).put("requiresOwnerReview",true).put("message","修订只是原团队中可见的提案；请主人在原工作项审阅当前冻结版本、依赖闭包与预算，旧审查不授权新内容");}
            default:throw new IllegalArgumentException("此团队阶段控制未开放");
        }
    }
    private static JSONObject envelope(JSONObject args)throws Exception {JSONObject input=new JSONObject(args.toString()).put("runId",args.getString("teamRunId")).put("expectedRevision",args.getLong("expectedTeamRevision")).put("expectedGeneration",args.getInt("expectedGeneration"));if(args.has("memberId"))input.put("recipientMemberId",args.getString("memberId"));return input;}
    public static JSONObject originalTextRange(String text,int offset,int length)throws Exception{return range(text,offset,length);}
    /** Project only the actual verified kind; a native byte artifact is not an empty text page. */
    static JSONObject artifactOutput(JSONObject actual,int offset,int length)throws Exception {
        String kind=actual.getString("kind");
        if("text_file".equals(kind))return range(actual.getString("content"),offset,length).put("kind",kind).put("contentAvailable",true);
        if(!"binary_file".equals(kind)&&!"file_tombstone".equals(kind))throw new SecurityException("原冻结成果类型不支持此只读视图");
        boolean tombstone="file_tombstone".equals(kind);
        return new JSONObject().put("kind",kind).put("sizeBytes",actual.getLong("sizeBytes")).put("mimeType",actual.getString("mimeType"))
                .put("contentAvailable",false).put("tombstone",tombstone).put("requiresNativeByteRead",!tombstone)
                .put("message",tombstone?"这是原冻结成果的删除修订标记，无正文；不代表目标项目文件已删除":"所选成果保留完整原字节，此视图没有文字正文；请使用原生查看或导出入口");
    }
    private static JSONObject range(String text,int offset,int length)throws Exception{if(offset<0||offset>text.length()||length<1||length>8000||offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1)))throw new IllegalArgumentException("原成果范围无效或切断Unicode字符");int end=Math.min(text.length(),offset+length);if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;if(end==offset&&offset<text.length())throw new IllegalArgumentException("此范围不能容纳一个完整Unicode字符，请增加length");return new JSONObject().put("content",text.substring(offset,end)).put("offset",offset).put("endOffset",end).put("totalChars",text.length()).put("complete",offset==0&&end==text.length()).put("hasMore",end<text.length()).put("rangeUnit","UTF16_code_units_no_split_surrogate");}
    private static String preview(String text,int length){int end=Math.min(length,text.length());if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);}
}
