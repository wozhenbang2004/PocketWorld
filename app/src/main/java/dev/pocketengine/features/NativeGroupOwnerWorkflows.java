package dev.pocketengine.features;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import dev.pocketengine.features.groups.GroupOwnerAuthority;
import dev.pocketengine.features.groups.GroupsModule;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/**
 * The native Activity owner route, not an EngineModule or a feature/model command.
 * One current native page owns the window. Its worker call is the only place where
 * GroupsModule's owner gate is open; knowing a group/admin identity grants nothing.
 */
public final class NativeGroupOwnerWorkflows implements GroupOwnerAuthority, AutoCloseable {
    @FunctionalInterface public interface OwnerCall { JSONObject run() throws Exception; }

    private static final class Window {
        final String id=UUID.randomUUID().toString();
        final WeakReference<Activity> activity;
        final Lifecycle lifecycle;
        LifecycleEventObserver observer;
        volatile boolean revoked;
        volatile MaterialReview materialReview;volatile ReturnReview returnReview;volatile dev.pocketengine.core.RunControl materialControl,completionControl;volatile dev.pocketengine.features.channels.ChannelsModule.CompletionDeliveryReview completionReview;volatile long materialGeneration,completionGeneration;
        volatile dev.pocketengine.features.remote.RemoteGroupInputs remoteInputs;volatile long remoteInputGeneration;
        Window(Activity activity,Lifecycle lifecycle){this.activity=new WeakReference<>(activity);this.lifecycle=lifecycle;}
    }

    private final Application application;
    private final Class<? extends Activity> activityClass;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadLocal<Window> invocation=new ThreadLocal<>();
    private volatile Window active;
    private volatile GroupsModule groups;
    private volatile boolean closed;
    private volatile dev.pocketengine.features.remote.RemoteModule remoteInputModule;private volatile RemoteOwnerWorkflows remoteInputOwner;private volatile dev.pocketengine.features.groups.host.GroupArtifactStore remoteInputArtifacts;

    public NativeGroupOwnerWorkflows(Application application,Class<? extends Activity> activityClass){
        this.application=Objects.requireNonNull(application);this.activityClass=Objects.requireNonNull(activityClass);
    }

    /** Application construction only. The FeatureHost, not this gate, owns module shutdown. */
    public synchronized void bind(GroupsModule module){
        if(closed||groups!=null)throw new IllegalStateException("原生群任务宿主已经绑定或关闭");
        groups=Objects.requireNonNull(module);
    }

    /** Called directly by the native page on main, never from supplied JSON. */
    public String open(Activity activity){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new SecurityException("只能从当前原生页面建立主人窗口");
        if(activity==null||activity.getClass()!=activityClass||activity.getApplication()!=application
                ||activity.isFinishing()||activity.isDestroyed()||!(activity instanceof LifecycleOwner))
            throw new SecurityException("不是本应用当前的原生主人页面");
        Lifecycle lifecycle=((LifecycleOwner)activity).getLifecycle();
        if(lifecycle.getCurrentState()!=Lifecycle.State.RESUMED)throw new SecurityException("请回到前台原生页面后重新核对");
        Window window=new Window(activity,lifecycle);
        window.observer=(source,event)->{
            if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP||event==Lifecycle.Event.ON_DESTROY)close(window.id);
        };
        Window previous;
        synchronized(this){
            if(closed||groups==null)throw new IllegalStateException("原生群任务宿主尚未就绪或已经关闭");
            previous=active;if(previous!=null)previous.revoked=true;active=window;
        }
        cancelMaterial(previous);detach(previous);
        try{
            lifecycle.addObserver(window.observer);
            if(lifecycle.getCurrentState()!=Lifecycle.State.RESUMED||window.revoked)throw new SecurityException("原生页面已离开前台");
        }catch(RuntimeException failure){close(window.id);throw failure;}
        return window.id;
    }

    /** Immediate revocation is safe from page disposal, pause, detach, or a worker. */
    public void close(String windowId){
        Window removed;
        synchronized(this){
            removed=active;if(removed==null||!removed.id.equals(windowId))return;
            removed.revoked=true;active=null;
        }
        cancelMaterial(removed);detach(removed);
    }

    public JSONObject handle(String windowId,String command,JSONObject payload)throws Exception{
        JSONObject input=new JSONObject(Objects.requireNonNull(payload).toString());
        return call(windowId,()->module().ownerCommand(command,input));
    }

    public JSONObject snapshot(String windowId)throws Exception{return call(windowId,()->module().ownerSnapshot());}

    /**
     * Native-only original-task approval/resume/stop/verification uses this same gate.
     * The Controller must capture its exact task and page window before queueing the call.
     * No window lock is held across storage, network, model, or executor work.
     */
    public JSONObject call(String windowId,OwnerCall action)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("原生群任务操作必须在现有工作队列执行");
        if(invocation.get()!=null)throw new SecurityException("不能嵌套或替换当前主人窗口");
        Window window=active;
        if(window==null||!window.id.equals(windowId))throw new SecurityException("原生主人页面已关闭，请重新打开核对");
        invocation.set(window);
        try{check();JSONObject result=Objects.requireNonNull(action).run();check();return result;}
        finally{invocation.remove();}
    }

    /** GroupsModule and native task dispatch call this before every owner-only operation. */
    @Override public void check(){
        Window window=invocation.get();
        Activity activity=window==null?null:window.activity.get();
        if(closed||window==null||window!=active||window.revoked||activity==null
                ||activity.getClass()!=activityClass||activity.getApplication()!=application
                ||activity.isFinishing()||activity.isDestroyed())
            throw new SecurityException("原生主人窗口已失效，本次没有获得主人操作权限");
    }

    /** Native material review, distinct from a provider event or a real group binding. */
    public static final class MaterialReview {
        private final NativeGroupOwnerWorkflows issuer; private final Window window;
        private final JSONObject facts; private final dev.pocketengine.core.ModelConfig selected; private final long expiresAt,pageGeneration;
        private MaterialReview(NativeGroupOwnerWorkflows issuer,Window window,JSONObject facts,dev.pocketengine.core.ModelConfig selected)throws Exception {
            this.issuer=issuer;this.window=window;this.selected=selected;pageGeneration=window.materialGeneration;expiresAt=System.currentTimeMillis()+120_000L;
            this.facts=new JSONObject(facts.toString()).put("taskId",UUID.randomUUID().toString()).put("nativeModel",selected.publicJson()).put("maxOutputTokens",Math.min(1024,selected.maxOutputTokens)).put("expiresAt",expiresAt)
                    .put("sourceTrust","owner_supplied_unverified_material").put("modelAudience","EXTERNAL_GROUP").put("privateDataAllowed",false).put("tools",new org.json.JSONArray()).put("automaticSendAllowed",false).put("tokenCostHardCap",false);
            this.facts.put("digest",dev.pocketengine.store.TaskStore.capabilityFingerprint(this.facts));
        }
        public JSONObject metadata(){try{return new JSONObject(facts.toString());}catch(Exception impossible){throw new IllegalStateException(impossible);}}
        public void checkWindowCurrent(){issuer.requireWindow(window);if(pageGeneration!=window.materialGeneration)throw new SecurityException("原材料子页已关闭，旧审阅不可继续");}
        public void checkOwnerCurrent(){issuer.check();if(issuer.invocation.get()!=window||System.currentTimeMillis()>expiresAt)throw new SecurityException("材料审阅已离页或到期，请重新核对");checkWindowCurrent();}
        public void bindOriginalControl(dev.pocketengine.core.RunControl original){checkOwnerCurrent();if(window.materialControl!=null&&window.materialControl!=original)throw new SecurityException("本页已有原材料执行");window.materialControl=original;try{checkWindowCurrent();}catch(RuntimeException closed){original.cancel();throw closed;}}
        public void releaseOriginalControl(dev.pocketengine.core.RunControl original){if(window.materialControl==original)window.materialControl=null;}
        public dev.pocketengine.core.ModelConfig selectedModel(){return selected;}
        public void checkModel(dev.pocketengine.core.ModelConfig actual)throws Exception {if(actual.usesAccount()||!selected.sameCredentials(actual)||!dev.pocketengine.store.TaskStore.capabilityFingerprint(selected.publicJson()).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(actual.publicJson())))throw new SecurityException("材料处理的原API模型连接已变化，未自动切换或使用账号套餐");}
    }
    private static final class ReturnReview {
        final dev.pocketengine.store.TaskStore.ReviewedExternalReturn original;final String digest;final long expiresAt=System.currentTimeMillis()+120_000L;String outboxId;
        ReturnReview(dev.pocketengine.store.TaskStore.ReviewedExternalReturn value)throws Exception{original=value;digest=dev.pocketengine.store.TaskStore.capabilityFingerprint(value.metadata());}
    }
    private volatile dev.pocketengine.features.groups.host.GroupTaskHost materialHost;
    private volatile dev.pocketengine.store.TaskStore materialTasks;
    private volatile dev.pocketengine.features.channels.ChannelsModule materialChannels;
    private volatile dev.pocketengine.features.groups.host.GroupTaskHost.ConfigSource materialModels;
    /** Same Application construction as bind(); no model/feature command can install a host. */
    public synchronized void bindMaterials(dev.pocketengine.features.groups.host.GroupTaskHost host,dev.pocketengine.store.TaskStore tasks,
            dev.pocketengine.features.channels.ChannelsModule channels,dev.pocketengine.features.groups.host.GroupTaskHost.ConfigSource models){
        if(closed||materialHost!=null)throw new IllegalStateException("材料原宿主已经绑定或关闭");materialHost=Objects.requireNonNull(host);materialTasks=Objects.requireNonNull(tasks);materialChannels=Objects.requireNonNull(channels);materialModels=Objects.requireNonNull(models);channels.bindCompletionFacts(tasks::originalCompletionFact,tasks::completionOutboxLink,this);
    }
    /** Same original modules, store and native Activity window; no new task/runner/transport. */
    public synchronized void bindRemoteInputs(dev.pocketengine.features.remote.RemoteModule remote,RemoteOwnerWorkflows owner,dev.pocketengine.features.groups.host.GroupArtifactStore artifacts){if(closed||remoteInputModule!=null)throw new IllegalStateException("原群远端端口已绑定或关闭");remoteInputModule=Objects.requireNonNull(remote);remoteInputOwner=Objects.requireNonNull(owner);remoteInputArtifacts=Objects.requireNonNull(artifacts);}
    public JSONObject remoteInputs(String command,JSONObject supplied)throws Exception {
        check();Window window=invocation.get();requireWindow(window);if(remoteInputModule==null||remoteInputOwner==null||materialTasks==null||materialChannels==null)throw new IllegalStateException("原群/电脑原生端口尚未接入");JSONObject input=new JSONObject(supplied.toString());
        if(command.equals("remoteInputs.openPage")){exactKeys(input,"requestId");var previous=window.remoteInputs;if(previous!=null)previous.close();long generation=++window.remoteInputGeneration;var page=new dev.pocketengine.features.remote.RemoteGroupInputs(module(),remoteInputArtifacts,materialTasks,remoteInputOwner,remoteInputModule,materialChannels,window.activity.get(),()->{requireWindow(window);if(window.remoteInputGeneration!=generation)throw new SecurityException("原群远端子页已更换");},input.getString("requestId"));window.remoteInputs=page;return page.snapshot();}
        var page=window.remoteInputs;if(page==null||!page.identity().equals(input.optString("pageId")))throw new SecurityException("请重新打开当前原群远端子页");input.remove("pageId");if(!command.startsWith("remoteInputs."))throw new SecurityException("未知原群远端入口");return page.handle(command.substring("remoteInputs.".length()),input);
    }
    public void leaveRemoteInputs(String windowId,String pageId){dev.pocketengine.features.remote.RemoteGroupInputs page;synchronized(this){Window window=active;if(window==null||!window.id.equals(windowId)||window.remoteInputs==null||!window.remoteInputs.identity().equals(pageId))return;page=window.remoteInputs;window.remoteInputs=null;window.remoteInputGeneration++;}page.close();}
    private void requireWindow(Window window){
        Activity activity=window==null?null:window.activity.get();
        if(closed||window==null||window!=active||window.revoked||activity==null||activity.getClass()!=activityClass||activity.getApplication()!=application||activity.isFinishing()||activity.isDestroyed()||window.lifecycle.getCurrentState()!=Lifecycle.State.RESUMED)throw new SecurityException("原生材料页面已离开前台，旧审阅不能继续派发");
    }
    /** Native Controller's copy boundary. A window ID cannot manufacture a real foreground Activity. */
    public void checkWindow(String windowId){Window window=active;if(window==null||!window.id.equals(windowId))throw new SecurityException("主人页面已经关闭");requireWindow(window);}
    /** Native subpage disposal only. Pure revocation under the window lock, cancellation outside it. */
    public void leaveMaterials(String windowId){Window window;dev.pocketengine.core.RunControl original;
        synchronized(this){window=active;if(window==null||!window.id.equals(windowId))return;window.materialGeneration++;window.materialReview=null;window.returnReview=null;original=window.materialControl;}
        if(original!=null)original.cancel();
    }
    /** Native invocation identity, never a JSON-restorable owner window. */
    public String currentWindowId(){check();Window window=invocation.get();requireWindow(window);return window.id;}
    public String currentCompletionPageIdentity(){check();Window window=invocation.get();requireWindow(window);return window.id+":"+window.completionGeneration;}
    public void checkCompletionPageIdentity(String identity){Window window=active;requireWindow(window);if(!identity.equals(window.id+":"+window.completionGeneration))throw new SecurityException("原完成投递子页已关闭，旧审阅不可继续");}
    public JSONObject completions(String command,JSONObject input)throws Exception {
        check();Window window=invocation.get();requireWindow(window);if(materialChannels==null)throw new IllegalStateException("原完成结果端口尚未就绪");
        switch(command){
            case "completion.list":{
                exactKeys(input);org.json.JSONArray candidates=new org.json.JSONArray(),recent=materialTasks.taskSummaryPage("",40).getJSONArray("tasks");for(int i=0;i<recent.length();i++){JSONObject row=recent.getJSONObject(i);if("completed".equals(row.optString("status"))&&"owner".equals(row.optString("actorId"))&&"private".equals(row.optString("spaceId"))&&!row.optBoolean("hasUnresolvedOperations"))candidates.put(row);}
                return new JSONObject().put("tasks",candidates).put("targets",materialChannels.completionTargetsForOwner()).put("deliveries",materialChannels.completionDeliveriesForOwner()).put("coverage","recent40_original_tasks_and_retained_current_owner_dm_only").put("artifactBytesSelected",false);
            }
            case "completion.review":{
                exactKeys(input,"taskId","eventId");var fact=materialTasks.completionFactForOwner(input.getString("taskId"));var reviewed=materialChannels.reviewCompletionDelivery(fact,input.getString("eventId"));window.completionReview=reviewed;JSONObject scope=reviewed.metadata();JSONObject link=materialTasks.completionOutboxLink(fact.factId);JSONObject existing=link==null?materialChannels.originalCompletionDeliveryForFact(fact):materialChannels.originalCompletionDelivery(fact,link.getString("outboxId"));return new JSONObject().put("review",scope).put("digest",dev.pocketengine.store.TaskStore.capabilityFingerprint(scope)).put("existing",existing==null?JSONObject.NULL:existing);
            }
            case "completion.confirm":{
                exactKeys(input,"digest");var review=completionReview(window,input.getString("digest"));JSONObject scope=review.metadata();var fact=materialTasks.originalCompletionFact(scope.getString("factId"));JSONObject old=materialTasks.completionOutboxLink(fact.factId);
                if(old!=null&&(!scope.getString("outboxId").equals(old.optString("outboxId"))||!scope.getString("payloadSha256").equals(old.optString("payloadSha256"))||!scope.getString("targetSha256").equals(old.optString("targetSha256"))))throw new SecurityException("原完成事实已关联另一准确内容/目标，只核对原outbox，不重建发送");
                JSONObject saved=materialChannels.queueCompletionDelivery(review,input.getString("digest"));materialTasks.recordCompletionOutbox(fact,saved.getString("id"),scope.getString("payloadSha256"),scope.getString("targetSha256"));return saved;
            }
            case "completion.send":{
                exactKeys(input,"digest");var review=completionReview(window,input.getString("digest"));JSONObject scope=review.metadata();JSONObject link=materialTasks.completionOutboxLink(scope.getString("factId"));if(link==null||!scope.getString("outboxId").equals(link.optString("outboxId"))||!scope.getString("payloadSha256").equals(link.optString("payloadSha256"))||!scope.getString("targetSha256").equals(link.optString("targetSha256")))throw new SecurityException("先取得原SQL和outbox的准确耐久关联，不在send时补登记");
                dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();if(window.completionControl!=null)throw new SecurityException("这份原投递仍在途");window.completionControl=original;original.setAuthorityCheck(()->{requireWindow(window);review.checkCurrent();});try{original.check();materialChannels.sendCompletionDelivery(review,input.getString("digest"),original);return materialChannels.originalCompletionDelivery(materialTasks.originalCompletionFact(scope.getString("factId")),scope.getString("outboxId"));}finally{if(window.completionControl==original)window.completionControl=null;}
            }
            case "completion.recoverLink":{
                exactKeys(input,"factId");var fact=materialTasks.originalCompletionFact(input.getString("factId"));JSONObject actual=materialChannels.originalCompletionDeliveryForFact(fact);if(actual==null)throw new SecurityException("原outbox尚未知，不创建或补发");JSONObject scope=actual.getJSONObject("nativeCompletionResult");if(!fact.completionFingerprint.equals(scope.getString("completionFingerprint"))||!fact.taskId.equals(scope.getString("taskId"))||!fact.runId.equals(scope.getString("runId"))||fact.epoch!=scope.getInt("epoch"))throw new SecurityException("原完成事实与耐久outbox身份不对应");materialTasks.recordCompletionOutbox(fact,actual.getString("id"),scope.getString("payloadSha256"),scope.getString("targetSha256"));return new JSONObject().put("delivery",actual).put("associationRecovered",true).put("businessRerun",false).put("messageSent",false);
            }
            case "completion.inspect":{
                exactKeys(input,"factId");var fact=materialTasks.originalCompletionFact(input.getString("factId"));JSONObject link=materialTasks.completionOutboxLink(fact.factId);JSONObject actual=link==null?materialChannels.originalCompletionDeliveryForFact(fact):materialChannels.originalCompletionDelivery(fact,link.getString("outboxId"));return new JSONObject().put("fact",fact.metadata()).put("link",link==null?JSONObject.NULL:link).put("delivery",actual==null?JSONObject.NULL:actual).put("associationPending",link==null&&actual!=null).put("businessRerun",false);
            }
            default:throw new IllegalArgumentException("未知原完成结果操作");
        }
    }
    private dev.pocketengine.features.channels.ChannelsModule.CompletionDeliveryReview completionReview(Window window,String digest)throws Exception {var review=window.completionReview;if(review==null||!digest.equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(review.metadata())))throw new SecurityException("请先在本页核对原结果与准确私聊接收方");review.checkCurrent();return review;}
    public void leaveCompletions(String windowId){Window window;dev.pocketengine.core.RunControl original;synchronized(this){window=active;if(window==null||!window.id.equals(windowId))return;window.completionGeneration++;window.completionReview=null;original=window.completionControl;}if(original!=null)original.cancel();}
    public JSONObject materials(String command,JSONObject input)throws Exception {
        check();Window window=invocation.get();requireWindow(window);if(materialHost==null)throw new IllegalStateException("原材料任务宿主尚未就绪");
        switch(command){
            case "material.review":{
                if(window.materialControl!=null)throw new SecurityException("本页原材料仍在执行，请先停止并等原回执");exactKeys(input,"sourceLabel","recipientLabel","material","goal","maxModelRequests");String source=materialText(input,"sourceLabel",160),recipient=materialText(input,"recipientLabel",160),body=materialText(input,"material",12000),goal=materialText(input,"goal",4000);Object raw=input.opt("maxModelRequests");if(!(raw instanceof Integer)||((Integer)raw)<1||((Integer)raw)>8)throw new IllegalArgumentException("本次模型请求额度必须为1至8，未知调用不退款");
                dev.pocketengine.core.ModelConfig selected=materialModels.get();dev.pocketengine.store.SettingsStore.validate(selected);if(selected.usesAccount())throw new SecurityException("外部材料只允许已配置API计费模型；不使用主人账号套餐");
                window.materialReview=new MaterialReview(this,window,new JSONObject().put("sourceLabel",source).put("recipientLabel",recipient).put("material",body).put("goal",goal).put("maxModelRequests",raw),selected);return window.materialReview.metadata();
            }
            case "material.approve":{
                exactKeys(input,"digest");MaterialReview review=window.materialReview;if(review==null||!review.metadata().getString("digest").equals(input.getString("digest")))throw new SecurityException("请先核对本页完整材料、受众和预算");review.checkOwnerCurrent();review.checkModel(materialModels.get());return materialHost.admitReviewedMaterial(review);
            }
            case "material.list":exactKeys(input);return new JSONObject().put("tasks",materialTasks.reviewedExternalMaterialsForOwner()).put("manualReturnOnly",true);
            case "material.inspect":exactKeys(input,"taskId");return materialTasks.reviewedExternalMaterialForOwner(input.getString("taskId"));
            case "material.cancel":exactKeys(input,"taskId","runId","epoch");return materialHost.cancelReviewedMaterial(input.getString("taskId"),input.getString("runId"),input.getInt("epoch"));
            case "material.return.review":{
                exactKeys(input,"taskId");ReturnReview review=new ReturnReview(materialTasks.reviewExternalMaterialReturn(input.getString("taskId")));window.returnReview=review;return review.original.metadata().put("digest",review.digest).put("expiresAt",review.expiresAt);
            }
            case "material.return.confirm":{
                exactKeys(input,"digest");ReturnReview review=returnReview(window,input.getString("digest"));review.original.checkCurrent();JSONObject saved=materialChannels.queueReviewedManualReturn(review.original);review.original.checkCurrent();materialTasks.recordReviewedMaterialOutbox(review.original,saved.getString("id"));review.outboxId=saved.getString("id");return saved;
            }
            case "material.return.copy":{
                exactKeys(input,"digest");ReturnReview review=returnReview(window,input.getString("digest"));if(review.outboxId==null)throw new SecurityException("请先第二次确认这份完整结果和人工接收方");review.original.checkCurrent();return materialChannels.reviewedManualReturnForCopy(review.original,review.outboxId);
            }
            default:throw new IllegalArgumentException("未知材料原生操作");
        }
    }
    private ReturnReview returnReview(Window window,String digest)throws Exception {ReturnReview review=window.returnReview;if(review==null||!review.digest.equals(digest)||System.currentTimeMillis()>review.expiresAt)throw new SecurityException("原结果审阅已变化或过期，请重新核对");review.original.checkCurrent();return review;}
    private static void exactKeys(JSONObject value,String...keys){java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList(keys));if(value.length()!=allowed.size())throw new SecurityException("参数不能带身份、连接、工具或发送授权");java.util.Iterator<String> names=value.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new SecurityException("不是本页材料参数");}
    private static String materialText(JSONObject value,String name,int max)throws Exception {Object raw=value.opt(name);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>max||((String)raw).indexOf('\0')>=0)throw new IllegalArgumentException(name+"为空或超过本次完整材料边界");return(String)raw;}

    private GroupsModule module(){GroupsModule module=groups;if(module==null)throw new IllegalStateException("原生群任务宿主尚未绑定");return module;}

    private static void cancelMaterial(Window window){if(window!=null){if(window.remoteInputs!=null)window.remoteInputs.close();dev.pocketengine.core.RunControl original=window.materialControl;if(original!=null)original.cancel();dev.pocketengine.core.RunControl completion=window.completionControl;if(completion!=null)completion.cancel();}}

    private void detach(Window window){
        if(window==null)return;
        Runnable remove=()->window.lifecycle.removeObserver(window.observer);
        if(Looper.myLooper()==Looper.getMainLooper())remove.run();else main.post(remove);
    }

    @Override public void close(){
        Window removed;
        synchronized(this){closed=true;removed=active;if(removed!=null)removed.revoked=true;active=null;}
        cancelMaterial(removed);detach(removed);
    }
}
