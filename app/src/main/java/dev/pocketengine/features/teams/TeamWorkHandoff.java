package dev.pocketengine.features.teams;

import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-reviewed continuation of actual known draft bytes. No effect is replayed or refunded. */
public final class TeamWorkHandoff {
    private TeamWorkHandoff(){}
    public static final class Plan {
        private final TaskStore.TeamWorkHandoffSource source;
        private final String captured;
        private final TeamProjectWorkspaces.Selection project;
        private Plan(TaskStore.TeamWorkHandoffSource source,JSONObject value,TeamProjectWorkspaces.Selection project){this.source=source;captured=value.toString();this.project=project;}
        public TaskStore.TeamWorkHandoffSource source(){return source;}
        public void checkCurrent()throws Exception{source.checkCurrent();if(project!=null)project.checkCurrent();}
        public JSONObject view()throws Exception{JSONObject value=new JSONObject(captured);if(project!=null)value.put("projectSelection",project.view());return value;}
        public TeamProjectWorkspaces.Selection projectSelection(){return project;}
    }
    public static Plan capture(TeamWorkFiles.Workspace workspace,TaskStore.TeamWorkHandoffSource source)throws Exception {
        source.checkCurrent();JSONObject value=source.view(),draft=value.optJSONObject("draft");JSONArray files=new JSONArray();
        if(draft!=null)for(int i=0;i<value.getJSONArray("files").length();i++){
            String path=value.getJSONArray("files").getJSONObject(i).getString("path");JSONObject actual=TeamWorkFiles.snapshot(workspace,draft.getString("draftProjectId"),path);boolean exists=actual.getBoolean("exists");
            files.put(new JSONObject().put("path",path).put("version",actual.getInt("version")).put("exists",exists).put("sha256",TeamWorkFiles.snapshotHash(actual)));
        }
        value.put("preservedDraftFiles",files);source.checkCurrent();return new Plan(source,value,null);
    }
    public static Plan withProject(Plan original,TeamProjectWorkspaces.Selection selected)throws Exception {original.checkCurrent();selected.checkCurrent();if(original.project!=null||!original.view().has("projectContext"))throw new SecurityException("不是原完整副本交接来源");return new Plan(original.source,original.view(),selected);}
    public static void checkDraft(TeamWorkFiles.Workspace workspace,Plan plan)throws Exception {
        plan.checkCurrent();JSONObject value=plan.view(),draft=value.optJSONObject("draft");if(draft!=null){JSONArray expected=value.getJSONArray("preservedDraftFiles");for(int i=0;i<expected.length();i++){JSONObject file=expected.getJSONObject(i),now=TeamWorkFiles.snapshot(workspace,draft.getString("draftProjectId"),file.getString("path"));if(file.getInt("version")!=now.getInt("version")||file.getBoolean("exists")!=now.getBoolean("exists")||!file.getString("sha256").equals(TeamWorkFiles.snapshotHash(now)))throw new SecurityException("旧作者草稿在交接审阅后发生变化，未按旧内容授权新成员");}}plan.checkCurrent();
    }
}
