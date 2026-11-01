package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded work-item values for the ORIGINAL team/TaskStore. This owns no executor or store.
 * A captured Plan is a native review proposal, NOT file permission or a budget grant. */
public final class TeamWorkItems {
    public static final String FORMAT="pocketworld.team.work-items.v1";
    public static final int MAX_ITEMS=8,MAX_FILES=8;
    private TeamWorkItems(){}
    public static final class Plan {
        private final TeamCoordinator.WorkPlanSource source;private final String captured;private final TeamProjectWorkspaces.Selection project;
        private Plan(TeamCoordinator.WorkPlanSource source,JSONObject captured)throws Exception {this(source,captured,null);}
        private Plan(TeamCoordinator.WorkPlanSource source,JSONObject captured,TeamProjectWorkspaces.Selection project)throws Exception {this.source=source;this.captured=captured.toString();this.project=project;}
        public TeamProjectWorkspaces.Selection projectSelection(){return project;}
        public JSONObject view()throws Exception{return new JSONObject(captured);}
        public void checkCurrent()throws Exception{source.checkCurrent();}
        public TeamCoordinator.WorkPlanSource source(){return source;}
    }
    public static Plan withProject(Plan original,TeamProjectWorkspaces.Selection project)throws Exception {original.checkCurrent();project.checkCurrent();if(!original.view().getString("inventoryHash").equals(project.view().getString("inventoryHash")))throw new SecurityException("完整项目选择不属于本工作项清单");return new Plan(original.source,original.view(),project);}
    /** Called by the native owner page. SQL must independently recheck the original parent/project/grant. */
    public static Plan capture(TeamWorkFiles.Workspace workspace,TeamCoordinator.WorkPlanSource source,JSONObject nativeParent,JSONArray drafts)throws Exception {
        source.checkCurrent();JSONObject run=source.view();
        for(String field:new String[]{"parentTaskId","parentRunId","targetProjectId"})uuid(nativeParent.getString(field));
        if(!run.getString("id").equals(nativeParent.getString("teamRunId")))throw new SecurityException("工作项不属于原讨论及项目任务");
        if(drafts.length()<1||drafts.length()>MAX_ITEMS)throw new IllegalArgumentException("本次工作计划需1至8项明确工作");
        JSONArray items=new JSONArray();Set<String> itemIds=new HashSet<>(),allPaths=new HashSet<>();
        for(int i=0;i<drafts.length();i++){
            JSONObject draft=drafts.getJSONObject(i);fields(draft,"workItemId","title","instruction","acceptance","memberId","paths","dependsOn");String itemId=uuid(draft.getString("workItemId"));
            if(!itemIds.add(itemId))throw new IllegalArgumentException("工作项ID重复");String memberId=draft.getString("memberId");JSONObject member=TeamWorkPlan.find(run.getJSONArray("members"),memberId);
            if(!member.optString("runner","model").equals("model")||!"phone".equals(member.opt("device")))throw new SecurityException("本次动态文件工作项先接原本机模型作者；不会把未接远端制作当本机作者");
            JSONArray paths=draft.getJSONArray("paths");if(paths.length()<1||paths.length()>MAX_FILES)throw new IllegalArgumentException("每项需1至8个明确项目文件");
            for(int p=0;p<paths.length();p++){String path=VersionedWorkspace.path(paths.getString(p));if(!allPaths.add(path)||allPaths.size()>MAX_FILES)throw new SecurityException("本片工作项需互斥所选文件，总计不超过8个；同路径多作者/串行覆盖须单独协调，不能隐式合并");}
            JSONArray files=TeamWorkFiles.captureFiles(workspace,nativeParent.getString("targetProjectId"),paths),dependencies=identifiers(draft.getJSONArray("dependsOn"));
            items.put(new JSONObject().put("workItemId",itemId).put("itemRevision",1).put("ownerGeneration",0).put("memberId",memberId)
                    .put("assignmentId",UUID.randomUUID().toString()).put("title",text(draft,"title",120,true)).put("instruction",text(draft,"instruction",2000,true)).put("acceptance",text(draft,"acceptance",1200,false))
                    .put("files",files).put("dependsOn",dependencies).put("status","queued").put("assignmentHistory",new JSONArray()).put("artifacts",new JSONArray()));
        }
        validate(items,run.getJSONArray("members"));source.checkCurrent();
        JSONObject decision=run.getJSONObject("discussion").getJSONObject("decision"),plan=new JSONObject().put("format",FORMAT).put("teamRunId",run.getString("id")).put("runGeneration",run.getInt("generation")).put("sourceRevision",run.getLong("revision"))
                .put("decisionInputVersion",decision.getString("inputVersion")).put("decisionHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(decision))).put("decision",JsonFiles.copy(decision))
                .put("parentTaskId",nativeParent.getString("parentTaskId")).put("parentRunId",nativeParent.getString("parentRunId")).put("parentEpoch",nativeParent.getInt("parentEpoch")).put("targetProjectId",nativeParent.getString("targetProjectId"))
                .put("items",items).put("inventoryHash",inventoryHash(items)).put("fileCollisionPolicy","unique_selected_paths_v1").put("executionAuthorized",false).put("originalProjectWriteAuthorized",false);
        return new Plan(source,plan);
    }
    public static void validate(JSONArray items,JSONArray members)throws Exception {
        if(items.length()<1||items.length()>MAX_ITEMS)throw new IllegalArgumentException("工作项数量无效");Set<String> ids=new HashSet<>();
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);String id=uuid(item.getString("workItemId"));if(!ids.add(id))throw new IllegalArgumentException("工作项ID重复");TeamWorkPlan.find(members,item.getString("memberId"));uuid(item.getString("assignmentId"));if(item.getInt("itemRevision")<1||item.getInt("ownerGeneration")<0)throw new SecurityException("工作项原修订无效");}
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);JSONArray dependencies=item.getJSONArray("dependsOn");Set<String> seen=new HashSet<>();for(int d=0;d<dependencies.length();d++){String id=dependencies.getString(d);if(!ids.contains(id)||id.equals(item.getString("workItemId"))||!seen.add(id))throw new IllegalArgumentException("前置工作项不存在、自依赖或重复");}visit(items,item,new HashSet<>(),new HashSet<>());}
    }
    private static void visit(JSONArray items,JSONObject item,Set<String> active,Set<String> visited)throws Exception {String id=item.getString("workItemId");if(visited.contains(id))return;if(!active.add(id))throw new IllegalArgumentException("工作项依赖成环，未建立执行计划");JSONArray deps=item.getJSONArray("dependsOn");for(int i=0;i<deps.length();i++)visit(items,find(items,deps.getString(i)),active,visited);active.remove(id);visited.add(id);}
    public static JSONObject find(JSONArray items,String id)throws Exception {for(int i=0;i<items.length();i++)if(id.equals(items.getJSONObject(i).optString("workItemId")))return items.getJSONObject(i);throw new SecurityException("没有该原工作项，不能猜最近assignment");}
    public static JSONArray blockers(JSONArray items,JSONObject item)throws Exception {JSONArray blocked=new JSONArray(),dependencies=item.getJSONArray("dependsOn");for(int i=0;i<dependencies.length();i++){JSONObject prior=find(items,dependencies.getString(i));if(!"completed".equals(prior.optString("status"))||prior.optJSONArray("artifacts")==null||prior.getJSONArray("artifacts").length()==0)blocked.put(new JSONObject().put("workItemId",prior.getString("workItemId")).put("status",prior.optString("status")));}return blocked;}
    /** Only original native completed receipts may be supplied by the TaskStore adapter. */
    public static JSONArray dependencyArtifacts(JSONArray items,JSONObject item)throws Exception {if(blockers(items,item).length()>0)throw new SecurityException("前置工作项没有原宿主真实冻结成果");JSONArray refs=new JSONArray(),dependencies=item.getJSONArray("dependsOn");for(int i=0;i<dependencies.length();i++){JSONObject prior=find(items,dependencies.getString(i));JSONArray artifacts=prior.getJSONArray("artifacts");for(int a=0;a<artifacts.length();a++){JSONObject ref=artifacts.getJSONObject(a);if(!prior.getString("assignmentId").equals(ref.getString("assignmentId"))||!prior.getString("memberId").equals(ref.getString("memberId")))throw new SecurityException("前置成果不是原工作项当前assignment");refs.put(JsonFiles.copy(ref));}}return refs;}
    /** Stable inventory does not change when the owner explicitly transfers an unstarted item. */
    public static String inventoryHash(JSONArray items)throws Exception {JSONArray inventory=new JSONArray();for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);inventory.put(new JSONObject().put("workItemId",item.getString("workItemId")).put("files",new JSONArray(item.getJSONArray("files").toString())).put("dependsOn",new JSONArray(item.getJSONArray("dependsOn").toString())));}return TeamWorkFiles.hash(TeamWorkFiles.canonical(inventory));}
    /** Revision invalidation follows real work-item edges, never all tasks of the same member. */
    public static JSONArray affected(JSONArray items,String changed)throws Exception {
        find(items,changed);Set<String> selected=new HashSet<>();selected.add(changed);boolean added;
        do{added=false;for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(selected.contains(item.getString("workItemId")))continue;JSONArray deps=item.getJSONArray("dependsOn");for(int d=0;d<deps.length();d++)if(selected.contains(deps.getString(d))){selected.add(item.getString("workItemId"));added=true;break;}}}while(added);
        JSONArray result=new JSONArray();for(int i=0;i<items.length();i++)if(selected.contains(items.getJSONObject(i).getString("workItemId")))result.put(JsonFiles.copy(items.getJSONObject(i)));return result;
    }
    public static JSONObject exactIdentity(JSONObject item)throws Exception {return new JSONObject().put("workItemId",item.getString("workItemId")).put("itemRevision",item.getInt("itemRevision")).put("ownerGeneration",item.getInt("ownerGeneration")).put("memberId",item.getString("memberId")).put("assignmentId",item.getString("assignmentId"));}
    public static boolean sameIdentity(JSONObject a,JSONObject b)throws Exception {return TeamWorkFiles.canonical(exactIdentity(a)).equals(TeamWorkFiles.canonical(exactIdentity(b)));}
    private static JSONArray identifiers(JSONArray values)throws Exception {if(values.length()>MAX_ITEMS-1)throw new IllegalArgumentException("前置工作项过多");JSONArray copy=new JSONArray();for(int i=0;i<values.length();i++)copy.put(uuid(values.getString(i)));return copy;}
    private static String uuid(String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原工作项/任务/项目ID无效");return id;}
    private static String text(JSONObject value,String key,int max,boolean required)throws Exception {String text=value.getString(key);if(text.length()>max||required&&text.codePoints().allMatch(Character::isWhitespace))throw new IllegalArgumentException("工作项文字为空或过长："+key);return text;}
    private static void fields(JSONObject object,String... keys)throws Exception {Set<String> expected=new HashSet<>(),actual=new HashSet<>();java.util.Collections.addAll(expected,keys);object.keys().forEachRemaining(actual::add);if(!actual.equals(expected))throw new SecurityException("工作项字段不能夹带执行、权限或预算");}
}
