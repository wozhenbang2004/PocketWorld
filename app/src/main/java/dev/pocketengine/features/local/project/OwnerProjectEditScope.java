package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner review of routine file edits. It confers no command, delete, publish or phone authority. */
public final class OwnerProjectEditScope {
    public static final String TOOL="owner_project_edit_scope",FORMAT="pocketworld.owner-project-edit-scope.v1";
    private OwnerProjectEditScope(){}
    public static JSONArray boundaries(String lines)throws Exception {
        if(lines==null||lines.length()>8000)throw new IllegalArgumentException("请逐行列出1至32个具体文件或目录/");
        Set<String> selected=new LinkedHashSet<>();
        for(String line:lines.split("\\r?\\n")){String raw=line.trim();if(raw.isEmpty())continue;boolean directory=raw.endsWith("/");String path=directory?raw.substring(0,raw.length()-1):raw;
            if(!path.equals(VersionedWorkspace.path(path))||path.contains("*")||path.contains("?")||!ProjectSources.sourcePath(path))throw new SecurityException("只接受规范项目相对文件或目录/，不接受通配符、依赖和生成目录");
            if(!selected.add(path+(directory?"/":"")))throw new IllegalArgumentException("范围路径重复");
        }
        if(selected.isEmpty()||selected.size()>32)throw new IllegalArgumentException("每次范围需为1至32个明确文件或目录/");return new JSONArray(selected);
    }
    private static boolean contains(JSONArray paths,String raw)throws Exception {
        String path=VersionedWorkspace.path(raw);if(!raw.equals(path)||!ProjectSources.sourcePath(path))return false;
        for(int i=0;i<paths.length();i++){String boundary=paths.getString(i);if(boundary.endsWith("/")?path.startsWith(boundary):path.equals(boundary))return true;}return false;
    }
    public static JSONObject workspaceTarget(WorkspaceModule workspace,String project)throws Exception {
        JSONObject definition=workspace.projectSnapshotForHost(project).definition();
        return new JSONObject().put("kind","workspace").put("projectId",project).put("projectIdentity",TaskStore.capabilityFingerprint(definition));
    }
    public static JSONObject projectTarget(JSONObject value)throws Exception {
        JSONObject target=new JSONObject().put("kind","checkout");for(String key:new String[]{"projectId","checkoutId","runtimeId","runtimeIdentity"})target.put(key,value.getString(key));return target;
    }
    public static boolean workspaceEligible(JSONObject policy,JSONObject target,JSONObject args,boolean creating)throws Exception {
        return TaskStore.capabilityFingerprint(policy.getJSONObject("target")).equals(TaskStore.capabilityFingerprint(target))
                &&contains(policy.getJSONArray("paths"),args.getString("path"))&&(!creating||policy.getBoolean("allowCreate"));
    }
    public static boolean projectEligible(JSONObject policy,JSONObject exact,JSONObject originalPlan)throws Exception {
        if(!Set.of("edit","patch").contains(exact.optString("intent"))||!Set.of("writeFile","patchFiles").contains(exact.optString("command"))
                ||!TaskStore.capabilityFingerprint(policy.getJSONObject("target")).equals(TaskStore.capabilityFingerprint(projectTarget(originalPlan))))return false;
        JSONObject payload=exact.getJSONObject("payload");if(payload.has("liveEditServiceExecutionId")||payload.has("revertsOperationId"))return false;
        if(exact.getString("command").equals("writeFile"))return contains(policy.getJSONArray("paths"),payload.getString("path"))&&(!"absent".equals(payload.optString("expectedHash"))||policy.getBoolean("allowCreate"));
        JSONArray files=payload.getJSONArray("files");if(files.length()<1||files.length()>ProjectExactPatch.MAX_FILES)return false;
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String action=file.getString("action");if(!Set.of("replace","create").contains(action)||action.equals("create")&&!policy.getBoolean("allowCreate")||!contains(policy.getJSONArray("paths"),file.getString("path")))return false;}return true;
    }
    public static final class Review {
        private final Page page;private final String facts,policy,digest,reviewId=java.util.UUID.randomUUID().toString();private final RunControl control;private boolean consumed;
        private Review(Page page,JSONObject facts,JSONObject policy,RunControl control)throws Exception{this.page=page;this.facts=facts.toString();this.policy=policy.toString();this.control=control;digest=TaskStore.capabilityFingerprint(viewCore());}
        private JSONObject viewCore()throws Exception{return new JSONObject().put("reviewId",reviewId).put("binding",new JSONObject(facts)).put("policy",new JSONObject(policy));}
        public JSONObject view()throws Exception{return viewCore().put("digest",digest).put("newRunCreated",false).put("commandsAuthorized",false);}
        /** Store boundary checks only native foreground and immutable review identity, never module I/O under SQL. */
        public JSONObject requireNative(TaskStore original,String expected)throws Exception{page.require();if(page.tasks!=original||consumed||!digest.equals(expected))throw new SecurityException("不是当前原生前台的这份范围审阅");return viewCore();}
        public RunControl originalControl(){return control;}
    }
    public static final class Page implements AutoCloseable {
        private final TaskStore tasks;private final LocalModule local;private final WorkspaceModule workspace;private final TaskStoreProjectHost.CurrentControl controls;private final String taskId;private final Runnable changed;
        private volatile boolean foreground,closed;
        public Page(TaskStore tasks,LocalModule local,WorkspaceModule workspace,TaskStoreProjectHost.CurrentControl controls,String taskId){this(tasks,local,workspace,controls,taskId,()->{});}
        public Page(TaskStore tasks,LocalModule local,WorkspaceModule workspace,TaskStoreProjectHost.CurrentControl controls,String taskId,Runnable changed){this.tasks=tasks;this.local=local;this.workspace=workspace;this.controls=controls;this.taskId=taskId;this.changed=java.util.Objects.requireNonNull(changed);}
        public void setForeground(boolean value){foreground=value&&!closed;}
        private void require(){if(closed||!foreground)throw new SecurityException("请回原任务前台审阅；后台和旧窗口不能授予编辑范围");}
        @Override public void close(){closed=true;foreground=false;}
        public JSONObject snapshot()throws Exception{require();RunControl original=controls.get(taskId);JSONObject facts=tasks.ownerEditScopeFacts(taskId,original),scope=tasks.ownerEditScopeSnapshot(taskId);if("scope_active".equals(scope.optString("state"))&&(original==null||scope.getLong("pauseRevision")!=original.pauseRevision()))scope.put("state","invalidated").put("reason","original_control_or_pause_revision_changed");return new JSONObject().put("binding",facts).put("scope",scope).put("projects",workspace.snapshot().getJSONArray("projects"));}
        private JSONObject target(JSONObject facts,String project)throws Exception {
            JSONObject context=facts.optJSONObject("developmentContext");if(context==null)return workspaceTarget(workspace,project);
            if(!project.equals(context.getString("projectId")))throw new SecurityException("只能审阅此原任务已选择的项目副本");
            JSONObject inspected=local.handle("project.inspect",new JSONObject().put("checkoutId",context.getString("checkoutId")).put("runtimeId",context.getString("runtime")));
            // File edits execute only in app_private_checkout. The existing Termux plan contract
            // can have no executable identity while unavailable; that does not grant execution.
            JSONObject value=new JSONObject().put("projectId",project).put("checkoutId",context.getString("checkoutId")).put("runtimeId",context.getString("runtime")).put("runtimeIdentity",inspected.getJSONObject("runtime").optString("identity"));return projectTarget(value);
        }
        public Review prepare(String project,String paths,boolean allowCreate,int minutes,int operations)throws Exception {
            require();if(minutes<1||minutes>120||operations<1||operations>100)throw new IllegalArgumentException("编辑范围为1至120分钟、1至100次原操作，不增加任务总预算");
            RunControl control=controls.get(taskId);JSONObject facts=tasks.ownerEditScopeFacts(taskId,control),policy=new JSONObject().put("format",FORMAT).put("target",target(facts,project)).put("paths",boundaries(paths)).put("allowCreate",allowCreate).put("allowModify",true).put("allowDelete",false).put("operationLimit",operations).put("expiresAt",Math.min(facts.getLong("expiresAt"),System.currentTimeMillis()+minutes*60_000L)).put("lifetime","current_run_or_next_explicit_run").put("autoRefill",false);
            require();if(control!=controls.get(taskId))throw new SecurityException("原执行控制器已变化，请重新审阅");return new Review(this,facts,policy,control);
        }
        public JSONObject approve(Review review,String digest)throws Exception {
            require();if(review==null||review.page!=this||controls.get(taskId)!=review.control)throw new SecurityException("原任务窗口或控制器已变化");JSONObject shown=review.view();
            if(!TaskStore.capabilityFingerprint(target(shown.getJSONObject("binding"),shown.getJSONObject("policy").getJSONObject("target").getString("projectId"))).equals(TaskStore.capabilityFingerprint(shown.getJSONObject("policy").getJSONObject("target"))))throw new SecurityException("原项目或运行环境身份已变化");
            JSONObject result=tasks.approveOwnerEditScope(taskId,review,digest);review.consumed=true;changed.run();return result;
        }
        public JSONObject revoke(String id)throws Exception{require();JSONObject result=tasks.revokeOwnerEditScope(taskId,id);changed.run();return result;}
    }
}
