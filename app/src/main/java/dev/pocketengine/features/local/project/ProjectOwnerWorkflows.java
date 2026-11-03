package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import org.json.JSONObject;

/** Native owner selection narrows the existing assistant admission; it creates no runner or grant. */
public final class ProjectOwnerWorkflows {
    private final LocalModule local;private final TaskStore tasks;private final AssistantModule assistant;
    private final ThreadLocal<JSONObject> selection=new ThreadLocal<>();
    private final ThreadLocal<dev.pocketengine.features.teams.TeamProjectRoundHost.Admission> teamRound=new ThreadLocal<>();
    public ProjectOwnerWorkflows(LocalModule local,TaskStore tasks,AssistantModule assistant){this.local=java.util.Objects.requireNonNull(local);this.tasks=java.util.Objects.requireNonNull(tasks);this.assistant=java.util.Objects.requireNonNull(assistant);}
    @FunctionalInterface public interface RoundContinuationExecutor {JSONObject resume(dev.pocketengine.features.teams.TeamProjectRoundHost.Continuation original)throws Exception;}
    private volatile RoundContinuationExecutor roundContinuationExecutor;
    public synchronized void setRoundContinuationExecutor(RoundContinuationExecutor original){if(roundContinuationExecutor!=null)throw new IllegalStateException("原round继续宿主已挂载");roundContinuationExecutor=java.util.Objects.requireNonNull(original);}
    public JSONObject continueTeamRound(dev.pocketengine.features.teams.TeamProjectRoundHost.Continuation original)throws Exception{RoundContinuationExecutor nativeHost=roundContinuationExecutor;if(nativeHost==null)throw new IllegalStateException("原native继续入口尚未挂载；没有新建run或更换预算");return nativeHost.resume(java.util.Objects.requireNonNull(original));}
    public JSONObject createProject(JSONObject input)throws Exception{return local.createProjectForOwner(input.getString("name"),input.getString("template"));}
    public JSONObject openProject(JSONObject input)throws Exception{return local.openProjectForOwner(input.getString("projectId"));}
    public JSONObject start(JSONObject ownerInput)throws Exception {
        String checkout=ProjectBuildPlan.identity(ownerInput.getString("checkoutId")),project=ProjectBuildPlan.identity(ownerInput.getString("projectId"));
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=ownerInput.getString("goal").trim();
        String runtime=ownerInput.getString("runtime");local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.codePoints().allMatch(Character::isWhitespace)||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目、运行位置和1至12000字目标");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",runtime)
                .put("initialSourceDigest",digest).put("clientMessageId",client).put("filesAreUntrusted",true).put("authorityAdded",false);
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
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=ownerInput.getString("goal").trim(),runtime=ownerInput.getString("runtime");local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.isEmpty()||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目及有界目标");
        JSONObject consent=memory.metadata();if(!project.equals(consent.getString("projectId"))||!checkout.equals(consent.getString("checkoutId")))throw new SecurityException("原生记忆同意不属于此实际项目副本");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",runtime).put("initialSourceDigest",digest).put("clientMessageId",client).put("filesAreUntrusted",true).put("authorityAdded",false);
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
        JSONObject context=selection.get();if(context!=null)tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,new JSONObject(context.toString()));
    }
    public JSONObject requireTask(String id)throws Exception {
        JSONObject task=tasks.get(id,false),context=task==null?null:task.optJSONObject("developmentContext");
        if(task==null||!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||context==null)throw new SecurityException("请选择本机主人原开发任务");
        JSONObject checkout=local.projectCheckoutForHost(context.getString("checkoutId"));if(!context.getString("projectId").equals(checkout.getString("projectId")))throw new SecurityException("原项目副本已不可用");return task;
    }
    private void verifyAcknowledgement(JSONObject ack,JSONObject expected)throws Exception {
        if(!ack.has("taskId"))return;JSONObject task=requireTask(ack.getString("taskId"));
        if(!ProjectSources.canonical(expected).equals(ProjectSources.canonical(task.getJSONObject("developmentContext"))))throw new SecurityException("这条输入回执属于另一个已选项目，请核对原任务");
    }
}
