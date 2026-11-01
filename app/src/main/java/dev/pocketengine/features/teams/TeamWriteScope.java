package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner selection, captured by FeatureHost from the original TaskStore project context. */
public final class TeamWriteScope {
    /** Returns only C3's privately-issued current profile/environment/source evidence. Not an execution grant. */
    public interface RemoteSelections {dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.Selection capture(JSONObject reviewedMember,JSONArray nativeFiles)throws Exception;}
    private final JSONObject value;
    private TeamWriteScope(JSONObject value)throws Exception{this.value=JsonFiles.copy(value);}
    public static TeamWriteScope capture(TeamWorkFiles.Workspace workspace,String parentTask,String parentRun,String projectId,JSONObject reviewedDefinition)throws Exception {
        return capture(workspace,parentTask,parentRun,projectId,reviewedDefinition,null);
    }
    public static TeamWriteScope capture(TeamWorkFiles.Workspace workspace,String parentTask,String parentRun,String projectId,JSONObject reviewedDefinition,RemoteSelections nativeRemote)throws Exception {
        for(String id:new String[]{parentTask,parentRun,projectId})if(!id.matches("[a-f0-9-]{36}"))throw new SecurityException("文件协作缺少原任务项目身份");
        if(!reviewedDefinition.optString("mode").equals("produce"))throw new SecurityException("仅主人明确选定的文件协作模式可准备写入");
        JSONArray members=reviewedDefinition.getJSONArray("members"),assignments=new JSONArray();TeamWorkPlan.validateWritable(members,nativeRemote!=null);
        for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);JSONArray files=TeamWorkFiles.captureFiles(workspace,projectId,member.getJSONArray("writeFiles"));JSONObject assignment=new JSONObject().put("memberId",member.getString("id")).put("files",files);
            if(!member.optString("runner","model").equals("model")){if(nativeRemote==null)throw new SecurityException("没有真实远端原生选择宿主");JSONObject selected=nativeRemote.capture(JsonFiles.copy(member),new JSONArray(files.toString())).view();if(!TeamWorkFiles.canonical(files).equals(TeamWorkFiles.canonical(selected.getJSONArray("files"))))throw new SecurityException("远端选择替换了原项目文件与版本");if(dev.pocketengine.features.remote.RemoteProjectAssignments.full(selected)&&!projectId.equals(selected.getJSONObject("packageSource").getString("projectId")))throw new SecurityException("远端完整包不属于原父已选项目");assignment.put("remoteSelection",selected);}assignments.put(assignment);}
        return new TeamWriteScope(new JSONObject().put("parentTaskId",parentTask).put("parentRunId",parentRun).put("targetProjectId",projectId).put("definitionHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(reviewedDefinition))).put("assignments",assignments).put("initialBudget",TeamCoordinator.initialBudget(members.length(),1)).put("externalBudget",TeamCoordinator.initialExternalBudget(members.length(),1)));
    }
    /** Native owner work-plan proposal; SQL still must approve its exact current scope and budgets. */
    public static TeamWriteScope fromWorkPlan(TeamWorkItems.Plan original)throws Exception {
        original.checkCurrent();JSONObject plan=original.view();JSONArray assignments=new JSONArray(),items=plan.getJSONArray("items");
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);JSONObject selected=new JSONObject().put("workItemId",item.getString("workItemId")).put("memberId",item.getString("memberId")).put("files",new JSONArray(item.getJSONArray("files").toString()));if(original.projectSelection()!=null)selected.put("projectSelection",TeamProjectWorkspaces.forWorkItem(original.projectSelection().view(),item));assignments.put(selected);}
        JSONObject source=original.source().view();JSONObject captured=new JSONObject().put("parentTaskId",plan.getString("parentTaskId")).put("parentRunId",plan.getString("parentRunId")).put("targetProjectId",plan.getString("targetProjectId")).put("definitionHash",plan.getString("inventoryHash")).put("workItemInventoryHash",plan.getString("inventoryHash")).put("assignments",assignments).put("initialBudget",TeamCoordinator.initialBudget(source.getJSONArray("members").length(),source.getInt("rounds"))).put("externalBudget",TeamCoordinator.initialExternalBudget(source.getJSONArray("members").length(),source.getInt("rounds")));if(original.projectSelection()!=null)captured.put("projectSelection",original.projectSelection().view());return new TeamWriteScope(captured);
    }
    /** Independent native phone integrator grant; never copied into any author's row. */
    public static TeamWriteScope withPhoneIntegrator(JSONObject original,TeamProjectWorkspaces.Selection selected)throws Exception {
        selected.checkCurrent();JSONObject value=selected.view(),next=JsonFiles.copy(original);if(next.has("projectSelection")||!value.optBoolean("remoteProjectIntegratorOnly")||value.optBoolean("localAuthorExecutionAuthorized"))throw new SecurityException("只能新增主人私构的手机唯一整合者选择，不替换作者授权");for(String key:new String[]{"parentTaskId","parentRunId"})if(!next.getString(key).equals(value.getString(key)))throw new SecurityException("手机整合者不是原父执行");next.put("projectSelection",value);return new TeamWriteScope(next);
    }
    /** Only the original SQL single-start claim can mint this initial parent work-item scope. */
    public static TeamWriteScope fromParentStart(dev.pocketengine.store.TaskStore.TeamParentStart nativeStart)throws Exception{nativeStart.checkCurrent();return new TeamWriteScope(nativeStart.view().getJSONObject("scope"));}
    JSONObject filesForItem(String workItemId)throws Exception {JSONArray assignments=value.getJSONArray("assignments");for(int i=0;i<assignments.length();i++)if(workItemId.equals(assignments.getJSONObject(i).optString("workItemId")))return JsonFiles.copy(assignments.getJSONObject(i));throw new SecurityException("原scope没有该workItem，不按最近成员猜文件");}
    public JSONObject snapshot()throws Exception{return JsonFiles.copy(value);}
    /** Native exact integrated-result continuation. Paths/identity/budget do not change, only actual current heads. */
    static TeamWriteScope afterIntegrated(TeamWorkFiles.Workspace workspace,JSONObject original,String memberId,String reviewId,String planHash)throws Exception {
        JSONObject next=JsonFiles.copy(original);JSONArray assignments=next.getJSONArray("assignments");boolean found=false;
        for(int i=0;i<assignments.length();i++){JSONObject assignment=assignments.getJSONObject(i);if(!memberId.equals(assignment.getString("memberId")))continue;JSONArray paths=new JSONArray(),files=assignment.getJSONArray("files");for(int f=0;f<files.length();f++)paths.put(files.getJSONObject(f).getString("path"));assignment.put("files",TeamWorkFiles.captureFiles(workspace,next.getString("targetProjectId"),paths));found=true;}
        if(!found)throw new SecurityException("成果继续必须选择原成员");next.put("outcomeContinuation",new JSONObject().put("memberId",memberId).put("reviewId",reviewId).put("planHash",planHash));return new TeamWriteScope(next);
    }
    /** Reissued native selection must match the actual integrated heads; the old source job is not reused. */
    static TeamWriteScope afterIntegrated(TeamWorkFiles.Workspace workspace,JSONObject original,String memberId,String reviewId,String planHash,dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.Selection selected)throws Exception {
        TeamWriteScope captured=afterIntegrated(workspace,original,memberId,reviewId,planHash);JSONObject next=captured.snapshot(),selection=selected.view();JSONArray assignments=next.getJSONArray("assignments");boolean found=false;
        for(int i=0;i<assignments.length();i++){JSONObject assignment=assignments.getJSONObject(i);if(!memberId.equals(assignment.getString("memberId")))continue;if(!assignment.has("remoteSelection")||!TeamWorkFiles.canonical(assignment.getJSONArray("files")).equals(TeamWorkFiles.canonical(selection.getJSONArray("files"))))throw new SecurityException("新远端选择不是当前成果的同一路径与精确版本");assignment.put("remoteSelection",selection);found=true;}
        if(!found)throw new SecurityException("原远端成员不存在");return new TeamWriteScope(next);
    }
    static TeamWriteScope restored(JSONObject stored)throws Exception{return new TeamWriteScope(stored);}
    JSONObject filesFor(String member)throws Exception {JSONArray assignments=value.getJSONArray("assignments");for(int i=0;i<assignments.length();i++)if(member.equals(assignments.getJSONObject(i).getString("memberId")))return JsonFiles.copy(assignments.getJSONObject(i));throw new SecurityException("原项目分工不包含该成员");}
    void validateMembers(JSONArray members)throws Exception {TeamWorkPlan.validateWritable(members,true);for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);if(!member.optString("runner","model").equals("model")&&!filesFor(member.getString("id")).has("remoteSelection"))throw new SecurityException("原远端成员没有私构选择的真实快照/环境证据");}}
    void matches(JSONObject definition)throws Exception{if(!value.optString("parentDefinitionHash",value.getString("definitionHash")).equals(TeamWorkFiles.hash(TeamWorkFiles.canonical(definition))))throw new SecurityException("主人审阅后的文件分工已变化");}
}
