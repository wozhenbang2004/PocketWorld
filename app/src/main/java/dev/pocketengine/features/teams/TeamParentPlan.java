package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.store.TaskStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A model proposal captured against the original native parent/project. Not a scope or a grant.
 * Implements the parent-facing plan boundary independently; execution stays in the original team. */
public final class TeamParentPlan {
    public static final String FORMAT="pocketworld.team.parent-plan.v1";
    private final TaskStore.TeamParentOrigin origin;
    private final String captured;
    private TeamParentPlan(TaskStore.TeamParentOrigin origin,JSONObject captured){this.origin=origin;this.captured=captured.toString();}
    public TaskStore.TeamParentOrigin origin(){return origin;}
    public void checkCurrent()throws Exception{origin.checkCurrent();}
    public JSONObject view()throws Exception{return new JSONObject(captured);}
    public static TeamParentPlan capture(TaskStore.TeamParentOrigin original,TeamWorkFiles.Workspace workspace,JSONObject selected,JSONObject args)throws Exception {
        original.checkCurrent();JSONObject parent=original.view();if(!parent.getString("targetProjectId").matches("[a-f0-9-]{36}"))throw new SecurityException("请先在原项目工作台选定本父任务的真实项目；工具JSON不能自行取其他项目权限");
        if(!selected.getString("id").equals(args.getString("teamId")))throw new SecurityException("不是原父任务选择的团队");
        String goal=text(args,"goal",8000,true);JSONArray drafts=args.getJSONArray("workItems");
        if(drafts.length()<1||drafts.length()>TeamWorkItems.MAX_ITEMS)throw new IllegalArgumentException("需1至8项明确文件工作");
        Map<String,String> keys=new LinkedHashMap<>();Set<String> paths=new HashSet<>();Map<String,JSONArray> memberPaths=new HashMap<>();
        for(int i=0;i<drafts.length();i++){
            JSONObject draft=drafts.getJSONObject(i);fields(draft,"key","title","instruction","acceptance","memberId","paths","dependsOn");
            String key=draft.getString("key");if(!key.matches("[A-Za-z][A-Za-z0-9_-]{0,39}")||keys.put(key,UUID.randomUUID().toString())!=null)throw new IllegalArgumentException("工作项key需稳定且互不重复");
            JSONObject member=TeamWorkPlan.find(selected.getJSONArray("members"),draft.getString("memberId"));
            if(!"model".equals(member.optString("runner","model"))||!"phone".equals(member.opt("device")))throw new SecurityException("本片只委派原本机模型成员；远端与完整编程工具需各自原生scope");
            JSONArray files=draft.getJSONArray("paths");if(files.length()<1||files.length()>8)throw new IllegalArgumentException("每项需1至8个明确路径");
            JSONArray owned=memberPaths.computeIfAbsent(member.getString("id"),ignored->new JSONArray());
            for(int f=0;f<files.length();f++){String path=VersionedWorkspace.path(files.getString(f));if(!paths.add(path)||paths.size()>8)throw new SecurityException("本计划最多8个互斥所选文字文件；不可让不同作者抢写同一文件");owned.put(path);}
        }
        JSONArray members=new JSONArray();for(int m=0;m<selected.getJSONArray("members").length();m++){
            JSONObject member=selected.getJSONArray("members").getJSONObject(m);if(!memberPaths.containsKey(member.getString("id")))continue;
            JSONObject copy=JsonFiles.copy(member);copy.put("writeFiles",memberPaths.get(member.getString("id"))).put("dependsOn",new JSONArray()).put("assignment","按此原父计划的准确工作项执行；文字不授予额外权限");members.put(AgentSlot.normalize(copy,members.length()));
        }
        if(members.length()<1||members.length()>4)throw new SecurityException("本次只能选择原目录中的1至4名本机成员");
        JSONArray items=new JSONArray();int dependencyReads=0;
        for(int i=0;i<drafts.length();i++){
            JSONObject draft=drafts.getJSONObject(i);JSONArray dependencies=new JSONArray(),supplied=draft.getJSONArray("dependsOn");Set<String> seen=new HashSet<>();
            for(int d=0;d<supplied.length();d++){String key=supplied.getString(d);if(!keys.containsKey(key)||!seen.add(key)||key.equals(draft.getString("key")))throw new IllegalArgumentException("前置key不存在、自依赖或重复");dependencies.put(keys.get(key));}
            JSONArray files=TeamWorkFiles.captureFiles(workspace,parent.getString("targetProjectId"),draft.getJSONArray("paths"));
            items.put(new JSONObject().put("workItemId",keys.get(draft.getString("key"))).put("proposalKey",draft.getString("key")).put("itemRevision",1).put("ownerGeneration",0)
                .put("memberId",draft.getString("memberId")).put("assignmentId",UUID.randomUUID().toString()).put("title",text(draft,"title",120,true)).put("instruction",text(draft,"instruction",2000,true)).put("acceptance",text(draft,"acceptance",1200,false))
                .put("files",files).put("dependsOn",dependencies).put("status","queued").put("assignmentHistory",new JSONArray()).put("artifacts",new JSONArray()));
        }
        TeamWorkItems.validate(items,members);for(int i=0;i<items.length();i++){JSONArray deps=items.getJSONObject(i).getJSONArray("dependsOn");for(int d=0;d<deps.length();d++)dependencyReads+=TeamWorkItems.find(items,deps.getString(d)).getJSONArray("files").length();}
        // Concrete text-file chain, not a guarantee for arbitrary programs or a token/cost hard cap.
        int least=4*paths.size()+2*items.length()+3+dependencyReads,orchestration=5,models=3*items.length()+5*paths.size()+6+dependencyReads;
        JSONObject previous=parent.optJSONObject("parentBudget");int usedModels=previous==null?0:previous.getInt("modelRequestsReservedOrUsed"),usedTools=previous==null?0:previous.getInt("toolOperationsReservedOrUsed");
        int priorModels=parent.optBoolean("priorParentModelRequestsInPool")?0:parent.optInt("priorParentModelRequests"),parentModelEstimate=6;
        int priorTools=parent.optInt("priorParentTeamToolsNotInPool")+1; // Original already-prepared planning operations plus the future real start intent.
        int modelLimit=args.optInt("modelRequestLimit",Math.min(64,usedModels+priorModels+models+parentModelEstimate+2)),toolLimit=args.optInt("toolOperationLimit",Math.min(32,usedTools+priorTools+least+orchestration+2));
        if(modelLimit<usedModels+priorModels+models+parentModelEstimate||modelLimit>64||toolLimit<usedTools+priorTools+least+orchestration||toolLimit>32||parent.getInt("remainingOperations")<least+orchestration+1)throw new IllegalStateException("原剩余预算或所提模型/工具额度不足以完成明确文字文件链；先在原生窗口调整，不启动必然耗尽的半条链");
        JSONObject definition=JsonFiles.copy(selected).put("members",members).put("leaderId",members.getJSONObject(0).getString("id")).put("mode","produce").put("discussionFormat","classic");
        JSONObject plan=new JSONObject().put("format",FORMAT).put("goal",goal).put("sourceParent",parent).put("selectedDefinitionHash",TaskStore.capabilityFingerprint(selected)).put("definition",definition).put("items",items)
            .put("inventoryHash",TeamWorkItems.inventoryHash(items)).put("modelRequestLimit",modelLimit).put("toolOperationLimit",toolLimit).put("externalJobLimit",0)
            .put("budgetPlan",new JSONObject().put("selectedFileToolSteps",least).put("parentOrchestrationToolSteps",orchestration).put("priorParentTeamToolOperationsNotYetInPool",priorTools).put("toolRetryMargin",toolLimit-usedTools-priorTools-least-orchestration).put("estimatedModelRequests",models).put("priorParentModelRequestsNotYetInPool",priorModels).put("estimatedParentModelRequests",parentModelEstimate).put("modelRetryMargin",modelLimit-usedModels-priorModels-models-parentModelEstimate).put("modelEstimateNotGuarantee",true).put("autoRefill",false).put("tokenCostKnown",false))
            .put("coordinationScope",new JSONArray(new String[]{"queue_message","continue_original_question","continue_idle_assignment_within_existing_scope","pause","cancel","resume_parent_paused_without_increase","transfer_definitely_unstarted_within_selected_members","request_independent_review","request_owner_exact_integration","request_native_revision"}))
            .put("expiresAt",Math.min(parent.getLong("expiresAt"),System.currentTimeMillis()+10*60_000L)).put("executionAuthorized",false).put("originalProjectWriteAuthorized",false).put("commandExecutionAvailable",false).put("osSandboxVerified",false);
        original.checkCurrent();return new TeamParentPlan(original,plan);
    }
    /** The native source is bound to this exact opaque proposal. Generic team JSON still loses
     * pins in AgentSlot.normalize; only this original plan includes them in its owner planHash. */
    public static TeamParentPlan withWorkProfileModels(TeamParentPlan original,
            dev.pocketengine.features.controlplane.NativeWorkProfileHost.MemberPlanModels source)throws Exception {
        source.requirePlan(original);JSONObject plan=original.view(),definition=plan.getJSONObject("definition");
        JSONArray members=definition.getJSONArray("members");
        for(int i=0;i<members.length();i++){
            JSONObject member=members.getJSONObject(i);TeamMemberModels.Selection selected=source.captureMember(member);
            member.put("memberModelSelection",selected.publicIdentity()).put("model",selected.config.model);
        }
        definition.put("nativeWorkProfileModels",source.view());source.requirePlan(original);
        return new TeamParentPlan(original.origin,plan);
    }
    /** Workspace reads are outside SQL. Recheck every selected base before the atomic original start. */
    public static void checkBases(TeamWorkFiles.Workspace workspace,JSONObject plan)throws Exception {
        JSONObject source=plan.getJSONObject("sourceParent");JSONArray items=plan.getJSONArray("items");for(int i=0;i<items.length();i++){
            JSONArray frozen=items.getJSONObject(i).getJSONArray("files"),paths=new JSONArray();for(int f=0;f<frozen.length();f++)paths.put(frozen.getJSONObject(f).getString("path"));
            if(!TeamWorkFiles.canonical(frozen).equals(TeamWorkFiles.canonical(TeamWorkFiles.captureFiles(workspace,source.getString("targetProjectId"),paths))))throw new SecurityException("原项目基线已变化，请重新提出并审阅计划；不会覆盖或刷新原提案");
        }
    }
    private static String text(JSONObject input,String key,int max,boolean required)throws Exception {String value=input.getString(key);if(value.length()>max||required&&value.codePoints().allMatch(Character::isWhitespace))throw new IllegalArgumentException("工作文字为空或过长："+key);return value;}
    private static void fields(JSONObject object,String... allowed)throws Exception{Set<String> actual=new HashSet<>(),expected=new HashSet<>();object.keys().forEachRemaining(actual::add);java.util.Collections.addAll(expected,allowed);if(!actual.equals(expected))throw new SecurityException("工作项提案不能夹带权限、宿主身份或执行入口");}
}
