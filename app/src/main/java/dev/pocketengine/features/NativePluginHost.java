package dev.pocketengine.features;

import android.content.Context;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.catalog.ExecutableExtensionSource;
import dev.pocketengine.features.plugins.catalog.ExecutablePluginCatalog;
import dev.pocketengine.features.plugins.catalog.PluginCatalog;
import dev.pocketengine.features.plugins.catalog.PluginCatalogSession;
import dev.pocketengine.features.plugins.catalog.TaskExtensionSelection;
import dev.pocketengine.features.plugins.distribution.FunctionalPackHost;
import dev.pocketengine.features.skills.SkillsModule;
import org.json.JSONArray;
import dev.pocketengine.features.skills.AndroidJavascriptSandboxRuntime;
import dev.pocketengine.runtime.extensions.ExtensionHost;
import dev.pocketengine.runtime.extensions.ExtensionProgram;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Application composition only: one real catalog, one warm sandbox, existing task journal/runner. */
public final class NativePluginHost implements AutoCloseable {
    @FunctionalInterface public interface LiveRun { void check(ExtensionRuntime.Binding binding,RunControl control)throws Exception; }
    private final TaskStore tasks;
    private final File privateDirectory;
    private final EngineModule plugins;
    private final Consumer<String> changedTask;
    private final LiveRun live;
    private final AndroidJavascriptSandboxRuntime sandbox;
    private final PluginCatalog catalog;
    private final ExecutablePluginCatalog executable;
    private final ExtensionHost extensions;
    private volatile FunctionalPackHost functionalPacks;
    private final ConcurrentHashMap<RunControl,Scope> runs=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile String runtimeState="not_prepared",runtimeMessage="尚未准备本机JS沙箱；未执行任何扩展。";

    public NativePluginHost(Context context,EngineModule plugins,TaskStore tasks,LiveRun live,Consumer<String> changedTask){
        this.tasks=java.util.Objects.requireNonNull(tasks);this.plugins=java.util.Objects.requireNonNull(plugins);
        this.live=java.util.Objects.requireNonNull(live);this.changedTask=java.util.Objects.requireNonNull(changedTask);
        privateDirectory=context.getFilesDir();
        sandbox=new AndroidJavascriptSandboxRuntime(context);
        catalog=new PluginCatalog(plugins,new File(context.getFilesDir(),"plugin-catalog-sources.json"),android.os.Build.VERSION.SDK_INT);
        executable=new ExecutablePluginCatalog(catalog,new File(context.getFilesDir(),"executable-plugin-catalog.json"));
        extensions=new ExtensionHost(sandbox,new ExtensionHost.Catalogue(){
            @Override public List<ExtensionProgram> approved(ExtensionRuntime.Binding binding,RunControl control)throws Exception{
                List<ExtensionProgram> programs=scope(binding,control).source.approved(binding,control);
                if(!programs.isEmpty())warm(); // Runner's existing worker, never the Application constructor/main.
                return programs;
            }
            @Override public void checkCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl control)throws Exception{
                scope(binding,control).source.checkCurrent(program,binding,control);
            }
            @Override public void checkSourceCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl control)throws Exception{
                scope(binding,control).source.checkSourceCurrent(program,binding,control);
            }
        },tasks);
    }
    /** App composition after the real SkillsModule is mounted. No package/model supplies an authority. */
    public synchronized void mountFunctionalPacks(SkillsModule skills){open();if(functionalPacks!=null)throw new IllegalStateException("功能包原生authority已挂载");functionalPacks=new FunctionalPackHost(plugins,skills,executable,tasks,new File(privateDirectory,"functional-pack-catalog.json"),changedTask,extensions::invalidate);}
    public FunctionalPackHost.Page newFunctionalPackPage(){open();FunctionalPackHost host=functionalPacks;if(host==null)throw new IllegalStateException("功能包需当前真实SkillBook与原插件模块；尚未挂载，未替代执行");return host.newOwnerPage();}
    /** The same app-owned instance is also the sole sandbox available to native Skills backends. */
    public AndroidJavascriptSandboxRuntime sharedSandbox(){return sandbox;}
    /** Original native owner facade; model tools cannot construct an OAuth approval page. */
    public dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Page newMcpOAuthPage(){open();if(!(plugins instanceof dev.pocketengine.features.plugins.PluginsModule))throw new IllegalStateException("原MCP插件模块不可用");return ((dev.pocketengine.features.plugins.PluginsModule)plugins).newMcpOAuthPage();}
    private void open(){if(closed.get())throw new IllegalStateException("插件宿主已关闭");}
    private void check(ExtensionRuntime.Binding binding,RunControl control)throws Exception{open();live.check(binding,control);tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,control);live.check(binding,control);}
    private Scope scope(ExtensionRuntime.Binding binding,RunControl control)throws Exception{
        open();Scope scope=runs.get(control);
        if(scope==null||!scope.binding.taskId.equals(binding.taskId)||!scope.binding.runId.equals(binding.runId)||scope.binding.epoch!=binding.epoch)throw new SecurityException("没有该原生执行代次的扩展来源");
        return scope;
    }
    public CapabilityPort bind(CapabilityPort combined,ExtensionRuntime.Binding binding,RunControl original)throws Exception{
        check(binding,original);
        TaskExtensionSelection selected=new TaskExtensionSelection(tasks.extensionSelectionTarget(binding.taskId),(activeRun,revision,control)->tasks.checkExtensionSelection(activeRun.taskId,activeRun.runId,activeRun.epoch,revision));
        Scope fresh=new Scope(binding,new ExecutableExtensionSource(executable,this::check,200,selected));
        Scope previous=runs.putIfAbsent(original,fresh);if(previous!=null){fresh.source.close();scope(binding,original);}
        return extensions.bind(combined,binding,original);
    }
    /** Pause/owner handoff does not call this: the fixed issued-set belongs to the whole native attempt. */
    public void release(String taskId,RunControl original){
        Scope scope=runs.get(original);if(scope!=null&&scope.binding.taskId.equals(taskId)&&runs.remove(original,scope))scope.source.close();
    }
    public JSONObject warm()throws Exception{
        open();if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("请在原生工作队列准备JS沙箱");
        runtimeState="preparing";runtimeMessage="正在核对已安装WebView和隔离能力。";
        try{JSONObject identity=sandbox.connect().review();runtimeState="ready";runtimeMessage="本机隔离JS运行时已核对；这不等于任何扩展已批准或执行。";return identity;}
        catch(Exception failure){runtimeState="unavailable";runtimeMessage="本机JS沙箱不可用；没有改用应用进程或其他解释器。";throw failure;}
    }
    public JSONObject runtimeSnapshot()throws Exception{
        JSONObject state=new JSONObject().put("status",runtimeState).put("message",runtimeMessage).put("maxExtensionsPerRun",ExtensionRuntime.MAX_EXTENSIONS).put("callbackMaxMillis",200).put("prepared",runtimeState.equals("ready"));
        if(runtimeState.equals("ready"))try{state.put("identity",sandbox.describe().review());}catch(Exception changed){runtimeState="unavailable";runtimeMessage="本机WebView身份已变化，请停止旧任务并重新准备。";state.put("status",runtimeState).put("prepared",false).put("message",runtimeMessage);}
        return state;
    }
    public TaskSelection newTaskSelection(String taskId) {open();if(taskId==null||!taskId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("需要原任务身份");return new TaskSelection(taskId);}
    /** Native same-task page; JSON/model routes cannot manufacture a selection confirmation. */
    public final class TaskSelection implements AutoCloseable {
        private final String taskId;
        private boolean alive=true,foreground;
        private SelectionReview pending;
        private TaskSelection(String taskId){this.taskId=taskId;}
        private void checkPage(){open();if(!alive)throw new SecurityException("原任务选用页面已关闭");}
        public synchronized void setForeground(boolean foreground){if(alive)this.foreground=foreground;}
        public synchronized JSONObject snapshot()throws Exception {
            checkPage();JSONObject target=tasks.extensionSelectionTarget(taskId),selection=target.getJSONObject("selection");JSONArray choices=new JSONArray(),selected=selection.getJSONArray("programs");
            List<ExecutablePluginCatalog.ApprovedSource> available=executable.approvedSources(new RunControl());
            for(ExecutablePluginCatalog.ApprovedSource source:available){JSONObject row=TaskExtensionSelection.reference(source);boolean chosen=selection.getString("mode").equals("inherit_enabled"),exact=chosen;
                for(int i=0;i<selected.length();i++)if(source.id.equals(selected.getJSONObject(i).getString("id"))){chosen=true;exact=TaskExtensionSelection.matches(selected.getJSONObject(i),source);}
                choices.put(row.put("selected",chosen).put("selectionCurrent",exact));}
            return target.put("available",choices).put("maxSelected",ExtensionRuntime.MAX_EXTENSIONS).put("executed",false);
        }
        public synchronized SelectionReview review(java.util.Set<String> ids)throws Exception {
            checkPage();if(!foreground)throw new SecurityException("请在前台原任务页面审阅选用");
            if(ids==null||ids.size()>ExtensionRuntime.MAX_EXTENSIONS)throw new IllegalArgumentException("每任务最多选用16个JS扩展");
            JSONObject target=tasks.extensionSelectionTarget(taskId);if(!target.getBoolean("editable"))throw new IllegalStateException("请先暂停原任务并核对未决动作，再选用扩展");
            List<ExecutablePluginCatalog.ApprovedSource> selected=new java.util.ArrayList<>();java.util.Set<String> remaining=new java.util.HashSet<>(ids);
            for(ExecutablePluginCatalog.ApprovedSource source:executable.approvedSources(new RunControl()))if(remaining.remove(source.id))selected.add(source);
            if(!remaining.isEmpty())throw new SecurityException("所选源码已停用或变化，请重新读取原任务选用");
            pending=new SelectionReview(this,target,selected);return pending;
        }
        public synchronized JSONObject save(SelectionReview review,String digest)throws Exception {
            checkPage();if(!foreground||review==null||review.page!=this||review!=pending||review.consumed||!review.digest.equals(digest))throw new SecurityException("需要此原任务当前原生选用审阅");
            JSONObject saved;synchronized(executable){for(ExecutablePluginCatalog.ApprovedSource source:review.sources)executable.checkCurrent(source,new RunControl());
                review.consumed=true;pending=null;saved=tasks.saveExtensionSelection(new JSONObject(review.target),new JSONArray(review.references));}
            changedTask.accept(taskId);return saved.put("selectionSaved",true).put("executionAuthorized",false).put("executed",false);
        }
        @Override public synchronized void close(){alive=false;foreground=false;pending=null;}
    }
    public static final class SelectionReview {
        public final String digest;
        private final TaskSelection page;
        private final String target,references,json;
        private final List<ExecutablePluginCatalog.ApprovedSource> sources;
        private boolean consumed;
        private SelectionReview(TaskSelection page,JSONObject target,List<ExecutablePluginCatalog.ApprovedSource> sources)throws Exception {
            this.page=page;this.target=target.toString();this.sources=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sources));JSONArray refs=new JSONArray();for(ExecutablePluginCatalog.ApprovedSource source:sources)refs.put(TaskExtensionSelection.reference(source));references=refs.toString();
            JSONObject data=new JSONObject().put("task",new JSONObject(this.target)).put("programs",refs).put("executionAuthorized",false).put("permissionsChanged",false);
            digest=TaskStore.capabilityFingerprint(data);json=data.put("digest",digest).toString();
        }
        public JSONObject view()throws Exception{return new JSONObject(json);}
    }
    public Page newOwnerPage(){open();return new Page();}
    private static final class Scope {final ExtensionRuntime.Binding binding;final ExecutableExtensionSource source;Scope(ExtensionRuntime.Binding binding,ExecutableExtensionSource source){this.binding=binding;this.source=source;}}

    /** Opaque native page owner. Neither featureRequest nor model JSON can create an install approval. */
    public final class Page implements AutoCloseable {
        private final Object owner=new Object();
        private final AtomicBoolean alive=new AtomicBoolean(true);
        private final ConcurrentHashMap<PluginCatalogSession.Request,Operation> operations=new ConcurrentHashMap<>();
        public final PluginCatalogSession session=new PluginCatalogSession(catalog,executable,plugins,owner,UUID.randomUUID().toString(),()->{open();if(!alive.get())throw new SecurityException("原插件目录页面已离开");});
        public Object nativeOwner(){return owner;}
        /** Called on the existing worker before Request.execute, using the View fixed at UI admission. */
        public void beforeDispatch(PluginCatalogSession.Request request,PluginCatalogSession.View pending,JSONObject nativeInput)throws Exception{
            if(!alive.get()||!session.accepts(owner,pending)||!request.requestId.equals(pending.data().optString("requestId")))throw new SecurityException("插件目录请求已失效，尚未派发");
            if(!request.command.matches("configure|submit|jsChange"))return;
            Operation operation=new Operation();if(operations.putIfAbsent(request,operation)!=null)throw new SecurityException("该目录操作已经登记，不能再次派发");
            JSONObject review=pending.data().optJSONObject("review");if(review==null)review=new JSONObject();
            operation.oldInstallation=review.optString("installationId");if(operation.oldInstallation.isEmpty()&&review.optJSONObject("current")!=null)operation.oldInstallation=review.getJSONObject("current").optString("installationId");
            JSONObject identity=new JSONObject().put("goal","插件目录原生审阅："+request.command).put("pageRequestId",pending.pageRequestId).put("pageRevision",pending.revision).put("requestId",request.requestId).put("command",request.command).put("reviewDigest",review.optString("reviewDigest")).put("pluginId",review.optString("id")).put("oldInstallationId",operation.oldInstallation).put("inputSha256",TaskStore.capabilityFingerprint(nativeInput)).put("action",nativeInput.optString("action")).put("expectedRevision",nativeInput.optString("expectedRevision"));
            try{synchronized(tasks){
                JSONObject unresolved=tasks.unresolvedCatalogOperation();
                if(unresolved!=null)throw new IllegalStateException("原目录写入仍待核对，请先查看任务 "+unresolved.getString("taskId")+"；只可读取当前安装，不能重提写入");
                JSONObject task=tasks.beginExternal("plugins","catalog_"+request.command,request.requestId,identity);operation.taskId=task.getString("id");tasks.status(operation.taskId,"running","原生目录操作已登记，等待实际回执");operation.operationId=tasks.beginCapabilityOperation(operation.taskId,request.requestId,"plugins","catalog_"+request.command,identity);
            }}catch(Exception failure){operations.remove(request,operation);throw failure;}
            changedTask.accept(operation.taskId);
        }
        /** Even an abandoned page must retain its original actual receipt; it never retries a write. */
        public void settled(PluginCatalogSession.Request request,PluginCatalogSession.View result,JSONObject actual)throws Exception{
            Operation operation=operations.remove(request);if(operation==null)return;
            JSONObject receipt=actual==null?new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("message","未取得确定目录写入回执，请查询当前安装；没有自动重提").put("replayAllowed",false):new JSONObject(actual.toString());
            try{tasks.finishCapabilityOperation(operation.taskId,operation.operationId,receipt);tasks.updateExternal(operation.taskId,receipt);changedTask.accept(operation.taskId);}
            finally{if(!operation.oldInstallation.isEmpty())extensions.invalidate(operation.oldInstallation);}
        }
        @Override public void close(){if(alive.compareAndSet(true,false))session.close();}
    }
    private static final class Operation {String taskId,operationId,oldInstallation="";}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        try{FunctionalPackHost packs=functionalPacks;if(packs!=null)packs.close();extensions.close();}finally{for(Scope scope:runs.values())scope.source.close();runs.clear();try{executable.close();}finally{try{catalog.close();}finally{sandbox.close();}}}
    }
}
