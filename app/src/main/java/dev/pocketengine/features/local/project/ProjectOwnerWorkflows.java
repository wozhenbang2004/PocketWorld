package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.OwnerWorkTask;
import org.json.JSONObject;
import org.json.JSONArray;

/** Native owner selection narrows the existing assistant admission; it creates no runner or grant. */
public final class ProjectOwnerWorkflows {
    private final LocalModule local;private final TaskStore tasks;private final AssistantModule assistant;
    private final ThreadLocal<JSONObject> selection=new ThreadLocal<>();
    private final ThreadLocal<java.util.function.BooleanSupplier> ownerStartFence=new ThreadLocal<>();
    private final ThreadLocal<dev.pocketengine.features.teams.TeamProjectRoundHost.Admission> teamRound=new ThreadLocal<>();
    public ProjectOwnerWorkflows(LocalModule local,TaskStore tasks,AssistantModule assistant){this.local=java.util.Objects.requireNonNull(local);this.tasks=java.util.Objects.requireNonNull(tasks);this.assistant=java.util.Objects.requireNonNull(assistant);}
    @FunctionalInterface public interface RoundContinuationExecutor {JSONObject resume(dev.pocketengine.features.teams.TeamProjectRoundHost.Continuation original)throws Exception;}
    private volatile RoundContinuationExecutor roundContinuationExecutor;
    private final ThreadLocal<TaskStore.OwnerWorkPreparation> butlerPreparation=new ThreadLocal<>();
    public synchronized void setRoundContinuationExecutor(RoundContinuationExecutor original){if(roundContinuationExecutor!=null)throw new IllegalStateException("原round继续宿主已挂载");roundContinuationExecutor=java.util.Objects.requireNonNull(original);}
    public JSONObject continueTeamRound(dev.pocketengine.features.teams.TeamProjectRoundHost.Continuation original)throws Exception{RoundContinuationExecutor nativeHost=roundContinuationExecutor;if(nativeHost==null)throw new IllegalStateException("原native继续入口尚未挂载；没有新建run或更换预算");return nativeHost.resume(java.util.Objects.requireNonNull(original));}
    public JSONObject createProject(JSONObject input)throws Exception{return local.createProjectForOwner(input.getString("name"),input.getString("template"));}
    public JSONObject openProject(JSONObject input)throws Exception{return local.openProjectForOwner(input.getString("projectId"));}
    /** Read-only private choices. A team, Skill or unpublished Git copy is not
     * silently adopted as the owner's ordinary development workspace. */
    public JSONObject inspectForOwner(String project)throws Exception {
        ProjectBuildPlan.identity(project);JSONArray available=new JSONArray(),rows=local.snapshot().getJSONArray("checkouts");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!project.equals(row.optString("projectId"))||!ordinaryCheckout(row))continue;
            available.put(new JSONObject().put("checkoutId",row.getString("id")).put("projectId",project).put("name",row.optString("name")).put("createdAt",row.optLong("createdAt")));}
        return new JSONObject().put("projectId",project).put("checkouts",available).put("runtime",local.projectRuntimeForHost("managed_process_v1")).put("executionAuthorized",false);
    }
    private static boolean ordinaryCheckout(JSONObject value){return !value.has("teamProjectCopy")&&!value.has("skillExecutionCopy")&&!value.has("isolatedGit")&&!value.has("nativeGitImportPending");}
    public JSONObject sourceForOwner(String project,String checkout)throws Exception {
        ProjectBuildPlan.identity(project);ProjectBuildPlan.identity(checkout);JSONObject row=local.projectCheckoutForHost(checkout);
        if(!project.equals(row.getString("projectId"))||!ordinaryCheckout(row))throw new SecurityException("请选择此项目的普通私人副本");
        JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));source.remove("files");return source;
    }
    public JSONObject prepareCheckoutForOwner(String project,String request)throws Exception{return local.prepareDevelopmentCheckoutForOwner(project,request);}
    private void requireOrdinaryFileOwner(String project,String checkout)throws Exception {
        ProjectBuildPlan.identity(project);ProjectBuildPlan.identity(checkout);JSONObject row=local.projectCheckoutForHost(checkout);
        if(!project.equals(row.getString("projectId"))||!ordinaryCheckout(row))throw new SecurityException("请选择此项目的普通私人副本");
    }
    public JSONObject filesForOwner(String project,String checkout)throws Exception {requireOrdinaryFileOwner(project,checkout);return local.handle("changes",new JSONObject().put("checkoutId",checkout));}
    public JSONObject fileForOwner(String project,String checkout,String path)throws Exception {requireOrdinaryFileOwner(project,checkout);return local.readDevelopmentFileForOwner(project,checkout,path);}
    public JSONObject saveFileForOwner(ProjectFileDraft.SaveIntent intent,java.util.function.BooleanSupplier current)throws Exception {requireOrdinaryFileOwner(intent.projectId,intent.checkoutId);return local.saveDevelopmentFileForOwner(intent,current);}
    public JSONObject reviewFileImportForOwner(String project,String checkout,String path)throws Exception {
        requireOrdinaryFileOwner(project,checkout);JSONObject result=local.handle("reviewFile",new JSONObject().put("checkoutId",checkout).put("path",path));
        if(!ProjectTextImport.REVIEW.equals(result.optString("format")))throw new IllegalArgumentException("这份变更包含二进制文件，请使用原固定成果包审阅，未改成文字采纳");return result;
    }
    public JSONObject importFileForOwner(String project,String checkout,String path,JSONObject reviewed,java.util.function.BooleanSupplier current)throws Exception {requireOrdinaryFileOwner(project,checkout);return local.importDevelopmentFileForOwner(project,checkout,path,reviewed,current);}
    public JSONObject queryCheckoutForOwner(String project,String request)throws Exception{return local.queryDevelopmentCheckoutForOwner(project,request);}
    public JSONObject submissionForOwner(String project,String checkout,String client)throws Exception {
        ProjectBuildPlan.identity(project);ProjectBuildPlan.identity(checkout);ProjectBuildPlan.identity(client);
        JSONObject original=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));
        if(!client.equals(original.optString("clientMessageId")))throw new SecurityException("不是原项目输入的回执");
        if(!original.optString("taskId").isEmpty()){
            JSONObject task=tasks.get(original.getString("taskId"),false),context=task==null?null:task.optJSONObject("developmentContext");
            if(task==null||context==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null
                    ||!project.equals(context.optString("projectId"))||!checkout.equals(context.optString("checkoutId"))||!client.equals(context.optString("clientMessageId")))throw new SecurityException("原输入属于另一项目任务，不采纳这份回执");
        }
        return new JSONObject(original.toString());
    }
    /** Exact native owner selection for the existing Profile admission. Reading/exporting an
     * already selected checkout creates no task, command, grant or alternate project registry. */
    public ProfileProjectSelection reviewWorkProfileProject(String projectId,String checkoutId,String runtime)throws Exception {
        String project=ProjectBuildPlan.identity(projectId),checkout=ProjectBuildPlan.identity(checkoutId);
        local.projectRuntimeForHost(runtime);
        JSONObject actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));
        if(!project.equals(actual.getString("projectId")))throw new SecurityException("原项目副本不属于所选项目");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1")
            .put("projectId",project).put("checkoutId",checkout).put("runtime",runtime)
            .put("initialSourceDigest",actual.getString("sourceDigest")).put("clientMessageId",java.util.UUID.randomUUID().toString())
            .put("filesAreUntrusted",true).put("authorityAdded",false);
        return new ProfileProjectSelection(this,context);
    }
    public static final class ProfileProjectSelection {
        private final ProjectOwnerWorkflows issuer;private final String context;private String boundTask;
        private ProfileProjectSelection(ProjectOwnerWorkflows issuer,JSONObject context){this.issuer=issuer;this.context=context.toString();}
        public void checkCurrent()throws Exception {
            JSONObject selected=new JSONObject(context);issuer.local.projectRuntimeForHost(selected.getString("runtime"));
            JSONObject actual=ProjectSources.describe(issuer.local.handle("export",new JSONObject().put("checkoutId",selected.getString("checkoutId"))));
            if(!selected.getString("projectId").equals(actual.getString("projectId"))||!selected.getString("initialSourceDigest").equals(actual.getString("sourceDigest")))throw new SecurityException("原项目或源码已变化，请重新核对项目选择；没有启动");
        }
        public JSONObject view()throws Exception {checkCurrent();return new JSONObject(context).put("executionAuthorized",false).put("commandsAuthorized",false);}
    }
    public void requireWorkProfileProject(ProfileProjectSelection selected)throws Exception {
        if(selected==null||selected.issuer!=this)throw new SecurityException("original_work_profile_project_selection_required");selected.checkCurrent();
    }
    /** One use, original preparing/zero-operation binding. The original SQL method remains the
     * only authority and rejects changing an existing task's project or renewing its budget. */
    public void bindWorkProfileProject(ProfileProjectSelection selected,JSONObject task,RunControl original)throws Exception {
        requireWorkProfileProject(selected);original.check();
        synchronized(selected){if(selected.boundTask!=null)throw new SecurityException("原项目选择已用于一项任务，请重新核对");selected.boundTask=task.getString("id");}
        tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,new JSONObject(selected.context));
    }
    /** Native presentation fence is checked again after the original Assistant
     * captures its model, before Engine binds/schedules this project's attempt. */
    public JSONObject startReviewedForOwner(JSONObject ownerInput,java.util.function.BooleanSupplier current)throws Exception {
        java.util.Objects.requireNonNull(current);if(ownerStartFence.get()!=null)throw new IllegalStateException("不能嵌套项目审阅");
        if(!current.getAsBoolean())throw new SecurityException("原项目或模型审阅已变化，没有进入启动");
        ownerStartFence.set(current);try{return start(ownerInput);}finally{ownerStartFence.remove();}
    }
    /** The saved proposal supplies provenance only. The actual owner still selects and reviews
     * the original checkout, source digest and runtime through the ordinary project admission. */
    public JSONObject startReviewedForOwner(JSONObject ownerInput,java.util.function.BooleanSupplier current,TaskStore.OwnerWorkPreparation preparation)throws Exception {
        java.util.Objects.requireNonNull(preparation).checkCurrent();JSONObject shown=preparation.view();
        if(!"project".equals(shown.getString("kind"))||!shown.getString("clientMessageId").equals(ownerInput.getString("clientMessageId"))
                ||!shown.getJSONObject("contract").getString("goal").equals(ownerInput.getString("goal")))throw new SecurityException("项目输入不属于这份原管家提案");
        if(butlerPreparation.get()!=null)throw new IllegalStateException("不能嵌套管家项目交接");
        butlerPreparation.set(preparation);
        try{return startReviewedForOwner(ownerInput,()->{try{preparation.checkCurrent();return current.getAsBoolean();}catch(Exception changed){return false;}});}
        finally{butlerPreparation.remove();}
    }
    public void checkStartFenceForHost(){var fence=ownerStartFence.get();if(fence!=null&&!fence.getAsBoolean())throw new SecurityException("原项目窗口或模型已变化，没有分配项目执行");}
    /** Native owner text only. These requirements describe the requested result, not grants. */
    public static JSONObject deliveryContractForOwner(JSONObject input)throws Exception {
        Object value=input.opt("goal");
        if(!(value instanceof String)||((String)value).length()>12000||((String)value).indexOf('\u0000')>=0||((String)value).codePoints().allMatch(Character::isWhitespace))throw new IllegalArgumentException("请填写1至12000字的项目目标");
        JSONObject contract=new JSONObject().put("format",OwnerWorkTask.CONTRACT_FORMAT).put("revision",1).put("goal",((String)value).trim());
        for(String key:new String[]{"constraints","deliverable","completionCriteria"}){
            if(!input.has(key))continue;Object field=input.get(key);
            if(!(field instanceof String)||((String)field).length()>4000||((String)field).indexOf('\u0000')>=0)throw new IllegalArgumentException("项目交付要求每项最多4000字，且必须为文字");
            if(!((String)field).codePoints().allMatch(Character::isWhitespace))contract.put(key,field);
        }
        return contract;
    }
    private JSONObject selectedDeliveryContract(JSONObject input)throws Exception {
        var preparation=butlerPreparation.get();
        if(preparation==null)return deliveryContractForOwner(input);
        JSONObject original=preparation.view().getJSONObject("contract");
        for(String key:new String[]{"goal","constraints","deliverable","completionCriteria"})
            if(input.has(key)&&!ProjectSources.canonical(input.get(key)).equals(ProjectSources.canonical(original.opt(key))))throw new SecurityException("项目交付要求与原管家提案不同，请回到原提案修改");
        // A proposal keeps its own revision and full contract. It is not replaced
        // by the ordinary page's revision-one requirements.
        return new JSONObject(original.toString());
    }
    public JSONObject start(JSONObject ownerInput)throws Exception {
        String checkout=ProjectBuildPlan.identity(ownerInput.getString("checkoutId")),project=ProjectBuildPlan.identity(ownerInput.getString("projectId"));
        JSONObject contract=selectedDeliveryContract(ownerInput);
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=contract.getString("goal");
        String runtime=ownerInput.getString("runtime");local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.codePoints().allMatch(Character::isWhitespace)||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目、运行位置和1至12000字目标");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",runtime)
                .put("initialSourceDigest",digest).put("clientMessageId",client).put("filesAreUntrusted",true).put("authorityAdded",false);
        if(butlerPreparation.get()==null)context.put("deliveryContract",contract);
        JSONObject prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));}catch(IllegalArgumentException absent){}
        if(prior!=null){verifyAcknowledgement(prior,context);return prior;}
        JSONObject actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));
        if(!project.equals(actual.getString("projectId"))||!digest.equals(actual.getString("sourceDigest")))throw new SecurityException("选择后的项目源码已变化，请重新核对再开始");
        if(selection.get()!=null)throw new IllegalStateException("不能嵌套启动项目任务");selection.set(context);
        try{
            // sendNative synchronously calls the existing NativeInputHost before scheduling its sole runner.
            JSONObject ack=assistant.handle("send",new JSONObject().put("clientMessageId",client).put("message",goal).put("mode","queue"));
            if(ack.optString("status").matches("accepted|queued"))verifyAcknowledgement(ack,context);
            return ack;
        }finally{selection.remove();}
    }
    /** Typed native owner submission, not JSON or a ThreadLocal authorization carrier. */
    public static final class NativeStart {
        private final ProjectOwnerWorkflows issuer;private final String context;
        private final dev.pocketengine.features.memory.ProjectMemoryAccess.Consent memory;
        private NativeStart(ProjectOwnerWorkflows issuer,JSONObject context,dev.pocketengine.features.memory.ProjectMemoryAccess.Consent memory){this.issuer=issuer;this.context=context.toString();this.memory=memory;}
    }
    public JSONObject start(JSONObject ownerInput,dev.pocketengine.features.memory.ProjectMemoryAccess.Consent memory)throws Exception{
        java.util.Objects.requireNonNull(memory);String checkout=ProjectBuildPlan.identity(ownerInput.getString("checkoutId")),project=ProjectBuildPlan.identity(ownerInput.getString("projectId"));
        JSONObject contract=selectedDeliveryContract(ownerInput);
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=contract.getString("goal"),runtime=ownerInput.getString("runtime");local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.isEmpty()||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目及有界目标");
        JSONObject consent=memory.metadata();if(!project.equals(consent.getString("projectId"))||!checkout.equals(consent.getString("checkoutId")))throw new SecurityException("原生记忆同意不属于此实际项目副本");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",runtime).put("initialSourceDigest",digest).put("clientMessageId",client).put("filesAreUntrusted",true).put("authorityAdded",false);
        if(butlerPreparation.get()==null)context.put("deliveryContract",contract);
        JSONObject prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));}catch(IllegalArgumentException absent){}
        if(prior!=null){verifyAcknowledgement(prior,context);JSONObject task=requireTask(prior.getString("taskId")),original=task.optJSONObject("projectMemoryRead");if(original==null||!consent.getString("consentId").equals(original.optString("consentId")))throw new SecurityException("原提交的记忆选择不同；只核对原任务，未恢复或替换旧权限");return prior;}
        JSONObject actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));if(!project.equals(actual.getString("projectId"))||!digest.equals(actual.getString("sourceDigest")))throw new SecurityException("项目源码已变化，请重新核对开始");
        JSONObject ack=assistant.sendProjectForOwner(new JSONObject().put("clientMessageId",client).put("message",goal).put("mode","queue"),new NativeStart(this,context,memory));if(ack.optString("status").matches("accepted|queued"))verifyAcknowledgement(ack,context);return ack;
    }
    public dev.pocketengine.features.memory.ProjectMemoryAccess bindMemoryAttempt(JSONObject task,RunControl original,dev.pocketengine.core.ModelConfig model,NativeStart selected)throws Exception{
        if(selected==null||selected.issuer!=this)throw new SecurityException("项目启动不是此原生主人选择");JSONObject context=new JSONObject(selected.context);tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,context);return selected.memory.bindForRun(tasks,task.getString("id"),original);
    }
    /** Native opaque result review only. JSON owner/model actions cannot mint this selection. */
    public JSONObject startTeamRound(dev.pocketengine.features.teams.TeamProjectRoundHost.Admission admitted)throws Exception {
        java.util.Objects.requireNonNull(admitted);JSONObject input=admitted.ownerRequest(),prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",input.getString("clientMessageId")));}catch(IllegalArgumentException absent){}
        if(prior!=null){admitted.verifyAcknowledgement(prior);return prior;}admitted.checkCurrent();if(selection.get()!=null||teamRound.get()!=null)throw new IllegalStateException("不能嵌套新建项目执行");teamRound.set(admitted);
        try{JSONObject ack=assistant.handle("send",new JSONObject().put("clientMessageId",input.getString("clientMessageId")).put("message",input.getString("goal")).put("mode","queue"));if(ack.optString("status").matches("accepted|queued"))admitted.verifyAcknowledgement(ack);return ack;}finally{teamRound.remove();}
    }
    public JSONObject teamRoundSubmission(dev.pocketengine.features.teams.TeamProjectRoundHost.Admission admitted)throws Exception{JSONObject input=admitted.ownerRequest(),ack=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",input.getString("clientMessageId")));admitted.verifyAcknowledgement(ack);return ack;}
    /** Called only inside the existing native assistant admission, before any model turn is scheduled. */
    public void bindNewAttempt(JSONObject task,RunControl original)throws Exception {bindNewAttempt(task,original,null);}
    public void bindNewAttempt(JSONObject task,RunControl original,dev.pocketengine.core.ModelConfig actualModel)throws Exception {
        var round=teamRound.get();if(round!=null){if(selection.get()!=null)throw new SecurityException("新一轮与其他项目选择不能混合");round.bindNewAttempt(task,original,actualModel);return;}
        JSONObject context=selection.get();if(context!=null){
            tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,new JSONObject(context.toString()));
            var preparation=butlerPreparation.get();if(preparation!=null)preparation.bindPreparedTask(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        }
    }
    public JSONObject requireTask(String id)throws Exception {
        JSONObject task=tasks.get(id,false),context=task==null?null:task.optJSONObject("developmentContext");
        if(task==null||!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||context==null)throw new SecurityException("请选择本机主人原开发任务");
        JSONObject checkout=local.projectCheckoutForHost(context.getString("checkoutId"));if(!context.getString("projectId").equals(checkout.getString("projectId")))throw new SecurityException("原项目副本已不可用");return task;
    }
    private void verifyAcknowledgement(JSONObject ack,JSONObject expected)throws Exception {
        if(!ack.has("taskId"))return;JSONObject task=requireTask(ack.getString("taskId"));
        JSONObject actual=task.getJSONObject("developmentContext");
        if(ProjectSources.canonical(expected).equals(ProjectSources.canonical(actual)))return;
        // Old saved starts had only a goal. They can be queried, but cannot
        // acquire additional requirements under the same client identity.
        JSONObject contract=expected.optJSONObject("deliveryContract");
        if(!actual.has("deliveryContract")&&contract!=null&&contract.length()==3&&contract.optInt("revision")==1&&contract.getString("goal").equals(task.optString("goal"))){
            JSONObject legacy=new JSONObject(expected.toString());legacy.remove("deliveryContract");
            if(ProjectSources.canonical(legacy).equals(ProjectSources.canonical(actual)))return;
        }
        throw new SecurityException("这条输入回执的项目或交付要求不同，请核对原任务；没有替换原要求");
    }
}
