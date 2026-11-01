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
    public ProjectOwnerWorkflows(LocalModule local,TaskStore tasks,AssistantModule assistant){this.local=java.util.Objects.requireNonNull(local);this.tasks=java.util.Objects.requireNonNull(tasks);this.assistant=java.util.Objects.requireNonNull(assistant);}
    public JSONObject createProject(JSONObject input)throws Exception{return local.createProjectForOwner(input.getString("name"),input.getString("template"));}
    public JSONObject openProject(JSONObject input)throws Exception{return local.openProjectForOwner(input.getString("projectId"));}
    public JSONObject start(JSONObject ownerInput)throws Exception {
        String checkout=ProjectBuildPlan.identity(ownerInput.getString("checkoutId")),project=ProjectBuildPlan.identity(ownerInput.getString("projectId"));
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=ownerInput.getString("goal").trim();
        String runtime=ownerInput.getString("runtime");local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.isBlank()||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目、运行位置和1至12000字目标");
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
    /** Called only inside the existing native assistant admission, before any model turn is scheduled. */
    public void bindNewAttempt(JSONObject task,RunControl original)throws Exception {
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
