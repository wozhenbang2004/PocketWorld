package dev.pocketengine.features.teams;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.runtime.ModelGateway;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TeamsModule implements EngineModule {
    /** Read-only owner preview; save still validates the same original member dependency graph. */
    public static void validateDefinitionMembers(JSONArray members)throws Exception {TeamWorkPlan.validate(members);}
    private final FeatureContext context;
    private final File file;
    private final TeamCoordinator coordinator;
    private volatile TeamCoordinator.EvidenceFactory evidenceFactory;
    private volatile ExternalMemberFactory externalFactory;
    private volatile TaskStoreTeamWriteHost writeHost;
    private volatile TeamParentHost parentHost;
    private volatile TeamResearchHost researchHost;
    public interface InferenceFactory {
        dev.pocketengine.runtime.usage.InferenceCapture.Target forCall(TeamCoordinator.InferenceCall call,dev.pocketengine.core.RunControl control)throws Exception;
    }
    private volatile InferenceFactory inferenceFactory;
    public TeamsModule(FeatureContext context)throws Exception {this.context=context;file=new File(context.android.getFilesDir(),"teams/definitions.json");coordinator=new TeamCoordinator(new File(context.android.getFilesDir(),"teams/runs.json"),new TeamCoordinator.ToolGateway(){
        @Override public JSONObject complete(dev.pocketengine.core.ModelConfig config,JSONArray messages,JSONArray tools,dev.pocketengine.core.RunControl control)throws Exception{
            return ModelGateway.complete(config,messages,tools,control);
        }
        @Override public JSONObject complete(dev.pocketengine.core.ModelConfig config,JSONArray messages,JSONArray tools,dev.pocketengine.core.RunControl control,TeamCoordinator.InferenceCall call)throws Exception{
            call.checkCurrent();InferenceFactory factory=inferenceFactory;
            var target=factory==null?dev.pocketengine.runtime.usage.InferenceCapture.unattributed():factory.forCall(call,control);
            return ModelGateway.complete(config,messages,tools,control,dev.pocketengine.runtime.usage.InferenceCapture.withAdmission(target,call::checkSourcesCurrent));
        }
    },event->context.emit("teams",event));}
    /** Native host wiring only. A model or installed declaration cannot supply this factory. */
    public void setMemberModelCapture(TeamMemberModels.Capture capture){coordinator.setMemberModelCapture(capture);}
    public void setEvidenceFactory(TeamCoordinator.EvidenceFactory factory){evidenceFactory=factory;}
    /** Native original TaskStore/Runner only; never supplied by a team definition or tool JSON. */
    public void setResearchHost(TeamResearchHost host){if(researchHost!=null)throw new IllegalStateException("原研究child宿主已挂载");researchHost=java.util.Objects.requireNonNull(host);coordinator.setResearchFactory(host);}
    public TeamResearchHost.Page newResearchBudgetPage(String run)throws Exception{TeamResearchHost host=researchHost;if(host==null)throw new SecurityException("原持续研究宿主尚未挂载");return host.openBudget(coordinator,run);}
    public TeamMemberEvidenceHost.Page newMemberEvidencePage(TeamMemberEvidenceHost host,String run,String member)throws Exception{return host.open(coordinator,run,member);}
    /** Native accounting only; absent host binding means unknown usage, never a fabricated TaskStore run. */
    public void setInferenceFactory(InferenceFactory factory){inferenceFactory=factory;}
    public void setExternalMemberFactory(ExternalMemberFactory factory){externalFactory=factory;}
    public void setWriteHost(TaskStoreTeamWriteHost host){if(writeHost!=null)throw new IllegalStateException("原文件分工宿主已挂载");writeHost=java.util.Objects.requireNonNull(host);coordinator.setAssignmentFactory(host);coordinator.setBudgetHost(host);parentHost=host.parentHost(coordinator,context::model,this::definition,context);}
    /** Only FeatureHost supplies the captured native project scope after original task admission. */
    public synchronized JSONObject startNative(JSONObject input,TeamWriteScope scope)throws Exception {JSONArray all=definitions();for(int i=0;i<all.length();i++)if(input.getString("teamId").equals(all.getJSONObject(i).getString("id")))return coordinator.startWithScope(all.getJSONObject(i),JsonFiles.text(input,"goal",8000),input.optInt("rounds",1),context.model(),input.getString("_operationId"),evidenceFactory,externalFactory,scope);throw new IllegalArgumentException("团队不存在");}
    private synchronized JSONObject definition(String id)throws Exception{JSONArray all=definitions();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return new JSONObject(all.getJSONObject(i).toString());throw new SecurityException("原已配置团队不存在");}
    public boolean parentOrchestrationConfigured(){return parentHost!=null;}
    public boolean continuousResearchConfigured(){return researchHost!=null;}
    public synchronized TeamCoordinator.OriginalResearchStart startOriginalResearch(JSONObject input)throws Exception {
        return coordinator.startOriginalResearch(definition(input.getString("teamId")),JsonFiles.text(input,"goal",8000),input.optInt("rounds",1),context.model(),input.getString("_operationId"),evidenceFactory,externalFactory);
    }
    public TeamCoordinator.OriginalCancellation originalCancellation(String run)throws Exception{return coordinator.originalCancellation(run);}
    public void checkParentResearchInputs(dev.pocketengine.store.TaskStore.TeamParentOrigin original,JSONArray refs)throws Exception{coordinator.checkParentResearchInputs(original,refs);}

    /** Actual parent Runner facade, deliberately absent from EngineModule.handle/generic native commands. */
    public JSONObject parentCall(String task,String run,int epoch,String tool,String operation,JSONObject args,dev.pocketengine.core.RunControl original)throws Exception{TeamParentHost host=parentHost;if(host==null)throw new IllegalStateException("原父协作宿主尚未接入");return host.execute(task,run,epoch,tool,operation,args,original);}
    private TaskStoreTeamWriteHost requireWriteHost(){TaskStoreTeamWriteHost host=writeHost;if(host==null)throw new IllegalStateException("原TaskStore文件分工宿主未接入");return host;}
    public dev.pocketengine.core.RunControl currentWriteControl(String taskId){TaskStoreTeamWriteHost host=writeHost;dev.pocketengine.core.RunControl current=host==null?null:host.currentControl(taskId);TeamResearchHost research=researchHost;return current!=null||research==null?current:research.currentControl(taskId);}
    public TaskStoreTeamWriteHost.DraftScopePage newDraftScopePage(String runId)throws Exception{return requireWriteHost().newDraftScopePage(coordinator,runId);}
    public TeamProjectRoundHost.Page newProjectRoundPage(TeamProjectRoundHost host,String run)throws Exception{return host.open(coordinator,run);}
    public TeamProjectRoundHost.Page newFailedProjectRoundPage(TeamProjectRoundHost host,String run,String item)throws Exception{return host.open(coordinator,run,item);}
    public TaskStoreTeamWriteHost.WorkPlanPage newWorkPlanPage(String runId)throws Exception{return requireWriteHost().newWorkPlanPage(coordinator,runId,context::model);}
    public TeamWriteScope captureWriteScope(String parent,String parentRun,String project,JSONObject reviewedDefinition)throws Exception{return requireWriteHost().captureScope(parent,parentRun,project,reviewedDefinition);}
    public TaskStoreTeamWriteHost.RemoteRevisionPage newRemoteRevisionPage(String runId,String assignmentId)throws Exception{return requireWriteHost().newRemoteRevisionPage(coordinator,runId,assignmentId);}
    public TaskStoreTeamWriteHost.PhoneIntegratorPage newPhoneIntegratorPage(String runId)throws Exception{return requireWriteHost().newPhoneIntegratorPage(coordinator,runId);}
    public TaskStoreTeamWriteHost.RemoteScopePage newRemoteScopePage(String runId,String assignmentId)throws Exception{return requireWriteHost().newRemoteScopePage(coordinator,runId,assignmentId);}
    public boolean ownsMemberExecution(String runId,String memberId,int round,int generation,String operationId)throws Exception{return coordinator.ownsMemberExecution(runId,memberId,round,generation,operationId);}
    @Override public String id(){return "teams";}
    private JSONArray definitions()throws Exception {JSONArray a=JsonFiles.read(file).optJSONArray("teams");return a==null?new JSONArray():a;}
    private void writeDefinitions(JSONArray teams)throws Exception {JSONObject document=JsonFiles.read(file);document.put("teams",teams);JsonFiles.write(file,document);}
    @Override public synchronized JSONObject snapshot()throws Exception{return new JSONObject().put("teams",definitions()).put("runs",coordinator.list()).put("history",coordinator.history()).put("mode",evidenceFactory==null?"bounded_text_analysis":"bounded_read_only_research").put("concurrentMembers",2).put("deviceTools",false).put("evidenceRoutingConfigured",evidenceFactory!=null).put("usageAttributionConfigured",inferenceFactory!=null).put("usageCoverage",inferenceFactory==null?"unknown_unbound":"original_task_receipts_only").put("continuousResearchChildrenConfigured",researchHost!=null).put("continuousResearchFormat",TeamCoordinator.RESEARCH_FORMAT).put("continuousResearchAutoRefill",false).put("memberModelSelection","configured_provider").put("evidenceDiscussionFormat",TeamEvidenceDiscussion.FORMAT).put("evidenceDiscussionStages",new JSONArray(new String[]{"independent","challenge","response","conditional_decision"})).put("workDependencies",true).put("peerMessaging","bounded_host_sender").put("peerMessageLimit",40).put("peerMessagesWakeAutomatically",false).put("peerMessageToolsForExternalRunners",false).put("parentOrchestrationConfigured",parentOrchestrationConfigured()).put("parentOrchestrationTools",parentOrchestrationConfigured()?new JSONArray(new String[]{"teams_plan","teams_start","teams_get","teams_message","teams_wait","teams_control"}):new JSONArray()).put("parentControlAutoIncreasesBudget",false).put("parentProjectToolExecutionAvailable",false).put("writeAssignmentsConfigured",writeHost!=null).put("writeAssignmentMode","native_owner_selected_project").put("commandExecutionAvailable",writeHost!=null&&writeHost.projectProductionConfigured()).put("fullProjectTeamNativeSelectionRequired",true).put("fullProjectCopyIsOsSandbox",false).put("fullProjectWorkItemV2Available",writeHost!=null&&writeHost.projectProductionConfigured()).put("fixedResultNewRoundRequiresNativeReview",true).put("writableExternalMembers",false).put("selectedFileRemoteProductionConfigured",writeHost!=null&&writeHost.remoteProductionConfigured()).put("selectedFileRemoteProductionMode","selected_existing_text_files_only").put("remoteFullProjectBuildChecksAvailable",writeHost!=null&&writeHost.projectProductionConfigured()&&writeHost.remoteProductionConfigured()).put("remoteProjectFinalizationLocation","phone_unique_integrator_with_separate_native_scope").put("remoteFullProjectSelectionRequiresOriginalPackage",true).put("businessVerified",false).put("commands",new JSONArray(new String[]{"roleCatalog","saveRole","archiveRole","removeRole","saveTemplate","archiveTemplate","removeTemplate","getTemplate","save","savePlan","remove","start","get","configureMember","cancel","pause","resume","feedback","continueMessages","sendMessage","probeMember","answerAssignment","reviseAssignment","continueOutcome","reviewOutputs","independentReview","approveOutputs","integrateOutputs"})).put("runnerCapabilities",new JSONArray().put(new JSONObject().put("id","model").put("available",true).put("message","使用已配置模型接口；实际请求仍需连接验证")).put(new JSONObject().put("id","codex_app_server").put("available",false).put("hostAdapterConfigured",externalFactory!=null).put("probeRequired",true).put("message","需对实际电脑环境执行probeMember验证，无结果不标可用")).put(new JSONObject().put("id","claude").put("available",false).put("hostAdapterConfigured",externalFactory!=null).put("probeRequired",true).put("readOnlySandbox",false).put("message","版本探测不证明登录或握手；真实会话受限工具，不是OS沙箱")).put(new JSONObject().put("id","kimi").put("available",false).put("message","尚无真实成员协议适配器")));}
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        // The original runner owns its worker, never the definitions/control monitor. Pause/cancel must remain reachable.
        if(command.equals("independentReview")){JSONObject result=requireWriteHost().independentlyReview(input.getString("runId"),input.getString("expectedPlanHash"),coordinator.get(input.getString("runId")),context.model());return coordinator.recordIndependentReview(input.getString("runId"),result);}
        if(command.equals("integrateOutputs")){JSONObject result=requireWriteHost().integrate(input.getString("runId"),input.getString("reviewId"),context.model());return coordinator.recordIntegration(input.getString("runId"),result);}
        synchronized(this){return handleLocked(command,input);}
    }
    private JSONObject handleLocked(String command,JSONObject input)throws Exception {
        if(command.matches("resume|continueMessages")&&coordinator.get(input.getString("runId")).optString("mode").equals("produce")&&(!input.has("expectedRevision")||!input.has("expectedGeneration")))throw new SecurityException("文件协作继续需要本次主人审阅的原团队快照");
        if(command.matches("pause|resume|feedback|continueMessages|cancel")&&(input.has("expectedRevision")||input.has("expectedGeneration"))){JSONObject result=coordinator.reviewedControl(command,input,context.model(),evidenceFactory,externalFactory);if(writeHost!=null&&input.optString("memberId").isEmpty()){if(command.equals("pause"))writeHost.pause(input.getString("runId"));if(command.equals("resume"))writeHost.resume(input.getString("runId"));if(command.equals("cancel"))writeHost.cancel(input.getString("runId"));}return result;}
        if(input.has("expectedDefinition")&&command.matches("save|remove|start")) {
            String id=input.getString(command.equals("start")?"teamId":"id");JSONObject current=null;JSONArray all=definitions();
            for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))current=all.getJSONObject(i);
            Object expected=input.get("expectedDefinition");
            if(!(expected instanceof String)||!((String)expected).equals(current==null?"":current.toString()))throw new SecurityException("原团队配置已变化，请重新读取后核对");
            if(!command.equals("save")&&current==null)throw new SecurityException("原团队已移除");
        }
        switch(command){
            case "roleCatalog":return TeamRoleCatalog.snapshot(JsonFiles.read(file),snapshot().getJSONArray("runnerCapabilities"),TeamResearchContext.toolNames());
            case "saveRole":case "archiveRole":case "removeRole":case "saveTemplate":case "archiveTemplate":case "removeTemplate":{
                TeamRoleCatalog.Change update=TeamRoleCatalog.change(JsonFiles.read(file),command,input,TeamResearchContext.toolNames());JsonFiles.write(file,update.document());return update.receipt();
            }
            case "getTemplate":return TeamRoleCatalog.template(JsonFiles.read(file),input.getString("id"),input.getInt("version"),input.getString("hash"));
            case "save":{
                String id=input.optString("id");if(id.isEmpty())id=UUID.randomUUID().toString();if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("团队ID无效");
                String name=JsonFiles.text(input,"name",80);JSONArray supplied=input.getJSONArray("members");if(supplied.length()<1||supplied.length()>4)throw new IllegalArgumentException("团队需1至4名真实成员");JSONArray members=new JSONArray();
                JSONObject old=null;JSONArray definitions=definitions();for(int i=0;i<definitions.length();i++)if(id.equals(definitions.getJSONObject(i).optString("id")))old=definitions.getJSONObject(i);
                java.util.Set<String> ids=new java.util.HashSet<>();for(int i=0;i<supplied.length();i++){JSONObject inputSlot=JsonFiles.copy(supplied.getJSONObject(i));if(old!=null){JSONArray prior=old.getJSONArray("members");for(int m=0;m<prior.length();m++){JSONObject previous=prior.getJSONObject(m);if(!previous.optString("id","member-"+m).equals(inputSlot.optString("id","member-"+i)))continue;for(String field:new String[]{"assignment","acceptance","dependsOn","writeFiles"})if(!inputSlot.has(field)&&previous.has(field))inputSlot.put(field,previous.get(field));}}JSONObject slot=AgentSlot.normalize(inputSlot,i);if(!ids.add(slot.getString("id")))throw new IllegalArgumentException("成员ID重复");members.put(slot);}
                TeamWorkPlan.validate(members);String mode=input.optString("mode","debate"),leader=input.optString("leaderId",members.getJSONObject(0).getString("id"));if(!mode.matches("debate|collaborate|produce")||!ids.contains(leader))throw new IllegalArgumentException("团队模式或Leader身份无效");if(mode.equals("produce"))TeamWorkPlan.validateWritable(members,writeHost!=null&&writeHost.remoteProductionConfigured());
                String discussion=mode.equals("debate")?input.optString("discussionFormat",old==null?TeamEvidenceDiscussion.FORMAT:old.optString("discussionFormat","classic")):"classic";if(!discussion.equals("classic")&&!discussion.equals(TeamEvidenceDiscussion.FORMAT))throw new IllegalArgumentException("讨论协议不支持");if(discussion.equals(TeamEvidenceDiscussion.FORMAT)&&members.length()<2)throw new IllegalArgumentException("证据辩论需要至少两名独立成员；单child请用协作/制作模式");if(discussion.equals(TeamEvidenceDiscussion.FORMAT))for(int m=0;m<members.length();m++){JSONObject slot=members.getJSONObject(m);if(!slot.optString("runner","model").equals("model")||slot.getJSONArray("dependsOn").length()>0)throw new IllegalArgumentException("证据讨论首轮需独立模型成员；外部执行器和前置依赖请用原共同分析模式");}
                JSONObject team=new JSONObject().put("id",id).put("name",name).put("mode",mode).put("leaderId",leader).put("members",members).put("discussionFormat",discussion);JSONArray all=definitions(),next=new JSONArray();for(int i=0;i<all.length();i++)if(!id.equals(all.getJSONObject(i).getString("id")))next.put(all.get(i));if(next.length()>=16)throw new IllegalStateException("最多16个团队");next.put(team);writeDefinitions(next);return team;
            }
            case "remove":{String id=input.getString("id");JSONArray all=definitions(),next=new JSONArray();for(int i=0;i<all.length();i++)if(!id.equals(all.getJSONObject(i).getString("id")))next.put(all.get(i));writeDefinitions(next);return new JSONObject().put("removed",true);}
            case "savePlan":{
                JSONArray all=definitions();String id=input.getString("teamId");JSONObject current=null;int selected=-1;for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id"))){current=all.getJSONObject(i);selected=i;}
                if(current==null||!current.toString().equals(input.getString("expectedDefinition")))throw new IllegalStateException("团队配置已改变，请重新载入后编辑分工");
                JSONArray drafts=input.getJSONArray("assignments"),oldMembers=current.getJSONArray("members"),members=new JSONArray();if(drafts.length()!=oldMembers.length())throw new IllegalArgumentException("分工必须包含原团队的全部成员");java.util.Set<String> seen=new java.util.HashSet<>();
                for(int i=0;i<drafts.length();i++){JSONObject draft=drafts.getJSONObject(i);java.util.Set<String> fields=new java.util.HashSet<>();java.util.Iterator<String> keys=draft.keys();while(keys.hasNext())fields.add(keys.next());if(!fields.equals(java.util.Set.of("memberId","assignment","acceptance","dependsOn"))||!seen.add(draft.getString("memberId")))throw new IllegalArgumentException("分工字段或成员身份无效");JSONObject merged=JsonFiles.copy(TeamWorkPlan.find(oldMembers,draft.getString("memberId")));merged.put("assignment",draft.get("assignment")).put("dependsOn",draft.get("dependsOn"));String acceptance=draft.getString("acceptance");if(acceptance.isEmpty())merged.remove("acceptance");else merged.put("acceptance",acceptance);members.put(AgentSlot.normalize(merged,i));}
                TeamWorkPlan.validate(members);if(TeamEvidenceDiscussion.FORMAT.equals(current.optString("discussionFormat")))for(int m=0;m<members.length();m++)if(members.getJSONObject(m).getJSONArray("dependsOn").length()>0)throw new IllegalArgumentException("完整证据讨论首轮需独立观点，不能把另一成员预设为前置；有依赖的共同分析请用原协作模式");JSONObject updated=JsonFiles.copy(current).put("members",members);all.put(selected,updated);writeDefinitions(all);return updated;
            }
            case "start":{String id=input.getString("teamId");JSONArray all=definitions();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return coordinator.start(all.getJSONObject(i),JsonFiles.text(input,"goal",8000),input.optInt("rounds",1),context.model(),input.optString("_operationId",UUID.randomUUID().toString()),evidenceFactory,externalFactory);throw new IllegalArgumentException("团队不存在");}
            case "configureMember":return coordinator.configureMember(input,context.model());
            case "get":return coordinator.get(input.getString("runId"));
            case "cancel":if(writeHost!=null)writeHost.cancel(input.getString("runId"));return coordinator.cancel(input.getString("runId"));
            case "pause":if(writeHost!=null&&input.optString("memberId").isEmpty())writeHost.pause(input.getString("runId"));return coordinator.pause(input.getString("runId"),input.optString("memberId"));
            case "resume":{JSONObject result=coordinator.resume(input.getString("runId"),input.optString("memberId"),context.model(),evidenceFactory,externalFactory);if(writeHost!=null&&input.optString("memberId").isEmpty())writeHost.resume(input.getString("runId"));return result;}
            case "sendMessage":return coordinator.sendMessage(input,writeHost);
            case "answerAssignment":return input.has("expectedRevision")?coordinator.answerAssignment(input,requireWriteHost(),context.model(),evidenceFactory,externalFactory):requireWriteHost().answer(input);
            case "continueOutcome":{coordinator.reviewedControl("feedback",input,context.model(),evidenceFactory,externalFactory);TaskStoreTeamWriteHost.OutcomeContinuation next=requireWriteHost().continueOutcome(input);return coordinator.adoptOutcomeContinuation(input.getString("runId"),next.scope).put("revisedAssignment",next.receipt);}
            case "reviseAssignment":return coordinator.reviseAssignment(input,()->requireWriteHost().revise(input));
            case "reviewOutputs":return requireWriteHost().reviewSnapshot(input.getString("runId"),new dev.pocketengine.core.RunControl());
            case "approveOutputs":return requireWriteHost().approve(input.getString("runId"),input.getString("expectedPlanHash"),new dev.pocketengine.core.RunControl());
            case "feedback":return coordinator.feedback(input.getString("runId"),input.optString("memberId"),input.getString("text"));
            case "continueMessages":{
                Object revision=input.get("expectedRevision"),generation=input.get("expectedMemberGeneration");if(!(revision instanceof Integer||revision instanceof Long)||!(generation instanceof Integer)||((Number)revision).longValue()<1||(Integer)generation<0)throw new IllegalArgumentException("需提供本次审阅的快照与成员版本");
                return coordinator.continueMessages(input.getString("runId"),input.getString("memberId"),((Number)revision).longValue(),(Integer)generation,context.model(),evidenceFactory,externalFactory);
            }
            case "probeMember":{JSONArray all=definitions();for(int i=0;i<all.length();i++)if(input.getString("teamId").equals(all.getJSONObject(i).getString("id"))){JSONArray slots=all.getJSONObject(i).getJSONArray("members");for(int m=0;m<slots.length();m++){JSONObject slot=AgentSlot.normalize(slots.getJSONObject(m),m);if(input.getString("memberId").equals(slot.getString("id"))){if(slot.getString("runner").equals("model"))return new JSONObject().put("available",true).put("runner","model").put("connectionVerified",false);if(!slot.getString("runner").matches("codex_app_server|claude")||externalFactory==null)return new JSONObject().put("available",false).put("runner",slot.getString("runner")).put("message","没有实际接入的成员适配器");return externalFactory.forSlot(slot).probe(slot,new dev.pocketengine.core.RunControl());}}}throw new IllegalArgumentException("团队成员不存在");}
            default:throw new IllegalArgumentException("不支持的团队操作");
        }
    }
    @Override public void close(){TaskStoreTeamWriteHost host=writeHost;if(host!=null)host.close();coordinator.close();}
}
