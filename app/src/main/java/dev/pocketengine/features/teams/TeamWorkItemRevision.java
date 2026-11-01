package dev.pocketengine.features.teams;

import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact native review of the ORIGINAL completed outcome. No new store, Runner, or file permission. */
public final class TeamWorkItemRevision {
    private TeamWorkItemRevision(){}
    public static final class Plan {
        private final TaskStore.TeamWorkRevisionSource source;private final String captured;
        private Plan(TaskStore.TeamWorkRevisionSource source,JSONObject value){this.source=source;captured=value.toString();}
        public JSONObject view()throws Exception{return new JSONObject(captured);}
        public TaskStore.TeamWorkRevisionSource source(){return source;}
        public void checkCurrent()throws Exception{source.checkCurrent();}
        /** The original SQL transaction applies one exact item at a time, retaining all other selected files. */
        public TeamWriteScope scopeForItem(JSONObject previous,String itemId)throws Exception{
            checkCurrent();JSONObject approved=view(),scope=new JSONObject(previous.toString()),original=approved.getJSONObject("originalScope");
            for(String key:new String[]{"parentTaskId","parentRunId","targetProjectId","definitionHash","initialBudget"})if(!TeamWorkFiles.canonical(scope.get(key)).equals(TeamWorkFiles.canonical(original.get(key))))throw new SecurityException("原成果继续不能更换父授权、项目、定义或初始预算");
            JSONObject selected=null;JSONArray affected=approved.getJSONArray("affected");for(int i=0;i<affected.length();i++)if(itemId.equals(affected.getJSONObject(i).getString("workItemId")))selected=affected.getJSONObject(i);if(selected==null)throw new SecurityException("不是此原生审阅的受影响工作项");
            JSONArray assignments=scope.getJSONArray("assignments");boolean found=false;for(int i=0;i<assignments.length();i++){JSONObject row=assignments.getJSONObject(i);if(!itemId.equals(row.optString("workItemId")))continue;JSONArray prior=row.getJSONArray("files"),next=selected.getJSONArray("newFiles");if(prior.length()!=next.length())throw new SecurityException("工作项修订不能扩文件范围");for(int f=0;f<prior.length();f++)if(!prior.getJSONObject(f).getString("path").equals(next.getJSONObject(f).getString("path")))throw new SecurityException("工作项修订不能换写入路径");row.put("files",new JSONArray(next.toString()));found=true;}
            if(!found)throw new SecurityException("原scope没有该工作项，不按负责人猜文件");JSONObject integration=approved.getJSONObject("integration");scope.put("outcomeContinuation",new JSONObject().put("memberId",selected.getString("memberId")).put("workItemId",itemId).put("reviewId",integration.getString("reviewId")).put("planHash",integration.getString("planHash")));return TeamWriteScope.restored(scope);
        }
    }
    /** Bytes/heads are fetched through the original native workspace, never supplied by a model or ordinary JSON. */
    public static Plan capture(TeamWorkFiles.Workspace workspace,TaskStore.TeamWorkRevisionSource source)throws Exception {
        source.checkCurrent();JSONObject facts=source.view(),value=new JSONObject(facts.toString());JSONArray affected=value.getJSONArray("affected");String project=value.getJSONObject("originalScope").getString("targetProjectId");
        for(int i=0;i<affected.length();i++){JSONObject item=affected.getJSONObject(i);JSONArray original=item.getJSONArray("files"),paths=new JSONArray();for(int f=0;f<original.length();f++)paths.put(original.getJSONObject(f).getString("path"));JSONArray files=TeamWorkFiles.captureFiles(workspace,project,paths),expected=item.getJSONArray("appliedHeads");
            for(int f=0;f<files.length();f++){JSONObject file=files.getJSONObject(f),head=null;for(int e=0;e<expected.length();e++)if(file.getString("path").equals(expected.getJSONObject(e).getString("path")))head=expected.getJSONObject(e);if(head==null||head.getInt("version")!=file.getInt("baseVersion")||!head.getString("sha256").equals(file.getString("baseHash")))throw new SecurityException("当前项目已离开原已整合成果，请重审实际差异，不能沿旧成果覆盖");}item.put("newFiles",files);
        }source.checkCurrent();return new Plan(source,value);
    }
}
