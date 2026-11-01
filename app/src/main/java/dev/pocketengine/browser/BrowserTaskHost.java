package dev.pocketengine.browser;

import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner journey over the existing task launcher, runner, journal and SQLite authority. */
public final class BrowserTaskHost {
    public interface OriginalTasks {
        void checkModelConfigured()throws Exception;
        void launch(String existingTaskId)throws Exception;
        RunControl currentControl(String taskId);
        default JSONObject modelForOwner()throws Exception{return new JSONObject();}
        default JSONArray currentTasksForOwner()throws Exception{return new JSONArray();}
    }
    private static final class Plan {
        final String taskId;final JSONObject identity;final RunControl selectedControl;final BrowserReadScope.Consent readConsent;final BrowserFrameOrigins.Consent frameOrigins;final JSONObject recoveryPages;volatile boolean cancelled;
        Plan(String taskId,JSONObject identity)throws Exception{this(taskId,identity,null);}
        Plan(String taskId,JSONObject identity,RunControl selectedControl)throws Exception{this(taskId,identity,selectedControl,BrowserReadScope.disabled());}
        Plan(String taskId,JSONObject identity,RunControl selectedControl,BrowserReadScope.Consent readConsent)throws Exception{this(taskId,identity,selectedControl,readConsent,BrowserFrameOrigins.sameOriginOnly());}
        Plan(String taskId,JSONObject identity,RunControl selectedControl,BrowserReadScope.Consent readConsent,BrowserFrameOrigins.Consent frameOrigins)throws Exception {this(taskId,identity,selectedControl,readConsent,frameOrigins,null);}
        Plan(String taskId,JSONObject identity,RunControl selectedControl,BrowserReadScope.Consent readConsent,BrowserFrameOrigins.Consent frameOrigins,JSONObject recovery)throws Exception {this.taskId=taskId;this.identity=identity;this.selectedControl=selectedControl;this.readConsent=readConsent==null?BrowserReadScope.disabled():readConsent;this.frameOrigins=Objects.requireNonNull(frameOrigins);recoveryPages=recovery==null?null:new JSONObject(recovery.toString());}
    }
    private static final class Scope {
        final Plan plan;final ModelCapabilityAdapters.Binding binding;final RunControl control;final String token;
        volatile BrowserTaskCapabilityPort port;volatile BrowserTaskApprovalPort approvals;volatile boolean closed;volatile String readScopeId="";
        Scope(Plan plan,ModelCapabilityAdapters.Binding binding,RunControl control){this.plan=plan;this.binding=binding;this.control=control;token=binding.taskId+":"+binding.runId+":"+binding.epoch;}
    }
    private final BrowserModule browser;
    private final TaskStore tasks;
    private final ExecutionResources resources;
    private final OriginalTasks originals;
    private final EventSink events;private final BrowserReadRecipeCatalog recipes;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,Plan> plans=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Scope> active=new ConcurrentHashMap<>();
    private volatile String latestTask="";
    public BrowserTaskHost(BrowserModule browser,TaskStore tasks,ExecutionResources resources,OriginalTasks originals,EventSink events){this(browser,tasks,resources,originals,events,null);}
    public BrowserTaskHost(BrowserModule browser,TaskStore tasks,ExecutionResources resources,OriginalTasks originals,EventSink events,BrowserReadRecipeCatalog recipes){this.browser=Objects.requireNonNull(browser);this.tasks=Objects.requireNonNull(tasks);this.resources=Objects.requireNonNull(resources);this.originals=Objects.requireNonNull(originals);this.events=Objects.requireNonNull(events);this.recipes=recipes;}

    /** Called by the native confirmation button, never a model/feature/channel JSON route. */
    public JSONObject startForOwner(String goal,JSONObject expectedProfile,boolean consent)throws Exception {
        return startForOwner(goal,expectedProfile,consent,BrowserReadScope.disabled());
    }
    public JSONObject startForOwner(String goal,JSONObject expectedProfile,boolean consent,BrowserReadScope.Consent readConsent)throws Exception {return startForOwner(goal,expectedProfile,consent,readConsent,BrowserFrameOrigins.sameOriginOnly());}
    public JSONObject startForOwner(String goal,JSONObject expectedProfile,boolean consent,BrowserReadScope.Consent readConsent,BrowserFrameOrigins.Consent frameOrigins)throws Exception {
        worker();if(!consent)throw new SecurityException("请先确认将研究网页观察发送给当前模型");if(goal==null||goal.codePoints().allMatch(Character::isWhitespace)||goal.length()>4000)throw new IllegalArgumentException("请填写1–4000字的研究目标");originals.checkModelConfigured();
        JSONObject selected=onMain(()->{JSONObject current=browser.snapshot();requireProfile(current,expectedProfile);JSONObject tabs=browser.nativeTabs();if(tabs.optJSONObject("taskSpace")!=null&&!tabs.getJSONObject("taskSpace").optString("status").equals("ended"))throw new IllegalStateException("请先结束或接管当前浏览研究");return profileIdentity(current);});
        JSONObject task=tasks.createCapabilityTask(goal.trim(),"");String id=task.getString("id");Plan plan=new Plan(id,selected,null,readConsent,frameOrigins);plans.put(id,plan);latestTask=id;
        try{tasks.event(id,new JSONObject().put("type","browser_research_requested").put("profile",selected).put("extraFrameOrigins",frameOrigins.summary()).put("frameOriginPolicy","same_origin_plus_explicit_native_task_origins").put("maxTaskTabs",BrowserTaskSpace.MAX_TASK_TABS).put("sourceTrust","untrusted_page_content"));originals.launch(id);}catch(Exception failed){plans.remove(id,plan);tasks.status(id,"failed","研究已登记但原任务执行器未启动，请核对原任务");throw failed;}
        emit(id,new JSONObject().put("type","browser_research_started").put("taskId",id));return tasks.get(id,false);
    }
    /** Actual App factory calls this after original task/run/control admission, before plugin wrapping. */
    public CapabilityPort wrapIfRequested(ModelCapabilityAdapters.Binding binding,RunControl original,CapabilityPort existing)throws Exception {return wrapIfRequested(binding,original,existing,null);}
    public CapabilityPort wrapIfRequested(ModelCapabilityAdapters.Binding binding,RunControl original,CapabilityPort existing,dev.pocketengine.features.memory.ToolOutputCapture.Factory outputCapture)throws Exception {
        if(!plans.containsKey(binding.taskId))return new PendingPort(binding,original,existing,outputCapture);
        return attachRequested(binding,original,existing,outputCapture);
    }
    private CapabilityPort attachRequested(ModelCapabilityAdapters.Binding binding,RunControl original,CapabilityPort existing,dev.pocketengine.features.memory.ToolOutputCapture.Factory outputCapture)throws Exception {
        worker();Plan plan=plans.get(binding.taskId);if(plan==null)return existing;
        if(plan.cancelled){plans.remove(binding.taskId,plan);if(plan.selectedControl==null||plan.selectedControl==original)original.cancel();throw new SecurityException("主人已结束此浏览请求");}
        if(plan.selectedControl!=null&&plan.selectedControl!=original)throw new SecurityException("浏览授权只适用于主人选定的原执行");
        if(originals.currentControl(binding.taskId)!=original||!original.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("研究没有原任务当前控制器");
        Scope scope=new Scope(plan,binding,original);if(active.putIfAbsent(binding.taskId,scope)!=null)throw new SecurityException("原任务已有浏览空间");boolean attached=false;
        try{
            if(!resources.acquireBrowser(scope.token,System.currentTimeMillis()+600_000L))throw new SecurityException("手机或浏览器资源正在被其他执行占用");
            JSONArray names=new JSONArray();if(existing!=null){JSONArray definitions=existing.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));}JSONArray own=BrowserTaskCapabilityPort.taskToolNames(true);for(int i=0;i<own.length();i++)names.put(own.getString(i));tasks.authorizeTools(binding.taskId,binding.runId,names);
            ModelCapabilityAdapters.ResourceCheck resource=()->{if(scope.closed||originals.currentControl(binding.taskId)!=original||!resources.ownsBrowser(scope.token))throw new SecurityException("原研究控制或浏览资源已失效");};
            TaskStoreCapabilityAuthority authority=new TaskStoreCapabilityAuthority(tasks,binding,events);scope.approvals=new BrowserTaskApprovalPort(tasks,binding,original,authority,resource,events);
            BrowserTaskSourcePort sources=new BrowserTaskSourcePort(tasks,binding,original,authority,resource,events,outputCapture);
            BrowserTaskSpace.NativeEvents lifecycle=new BrowserTaskSpace.NativeEvents(){
                public java.util.Set<String> frameReadOrigins(){return plan.frameOrigins.origins();}
                public BrowserReadRecipeCatalog readRecipes(){return recipes;}
                public JSONObject recoveryPages(){return plan.recoveryPages;}
                public boolean pageManifest(ModelCapabilityAdapters.Binding b,RunControl c,JSONObject manifest){
                    if(scope.closed||active.get(b.taskId)!=scope||b!=scope.binding||c!=scope.control||originals.currentControl(b.taskId)!=c)return false;
                    try{BrowserResearchPages.validate(manifest);if(!b.taskId.equals(manifest.getString("taskId"))||!b.runId.equals(manifest.getString("runId"))||b.epoch!=manifest.getInt("epoch"))throw new SecurityException("逻辑页清单属于旧执行");tasks.event(b.taskId,b.runId,b.epoch,new JSONObject().put("type",BrowserResearchPages.EVENT).put("manifest",new JSONObject(manifest.toString())).put("manifestFingerprint",TaskStore.capabilityFingerprint(manifest)));return true;}catch(Exception unavailable){return false;}
                }
                public void takenOver(ModelCapabilityAdapters.Binding b,RunControl c,String space,String tab){ownerControl(scope,"browser_task_taken_over",space,tab);}
                public void ownerBrowsing(ModelCapabilityAdapters.Binding b,RunControl c,String space,String tab){ownerControl(scope,"browser_owner_browsing",space,tab);}
                public void ended(ModelCapabilityAdapters.Binding b,RunControl c,String space,JSONObject receipt){closeScope(scope,receipt);}
                public boolean pageDialogCurrent(ModelCapabilityAdapters.Binding b,RunControl c){
                    if(scope.closed||active.get(b.taskId)!=scope||c!=scope.control||b!=scope.binding||originals.currentControl(b.taskId)!=c)return false;
                    try{synchronized(tasks){JSONObject task=tasks.get(b.taskId,false);return task!=null&&b.runId.equals(task.optString("runId"))&&b.epoch==task.optInt("coordinatorEpoch")&&!task.optBoolean("cancelRequested")&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&task.optString("status").matches("running|paused");}}catch(Exception unavailable){return false;}
                }
                public void siteRequirement(ModelCapabilityAdapters.Binding b,RunControl c,String space,JSONObject notice){
                    if(scope.closed||active.get(b.taskId)!=scope||c!=scope.control||b!=scope.binding||originals.currentControl(b.taskId)!=c)return;
                    try{if(!space.equals(notice.getString("browserSpaceId"))||!b.taskId.equals(notice.getString("taskId"))||!b.runId.equals(notice.getString("runId"))||b.epoch!=notice.getInt("epoch"))throw new SecurityException("站点反馈属于旧执行");JSONObject event=new JSONObject(notice.toString()).put("type","browser_site_requirement");tasks.event(b.taskId,b.runId,b.epoch,event);emit(b.taskId,event);}catch(Exception unavailable){/* Denial does not depend on persistence or grant any fallback privilege. */}
                }
                public void pageDialog(ModelCapabilityAdapters.Binding b,RunControl c,String space,JSONObject receipt){
                    if(scope.closed||active.get(b.taskId)!=scope||c!=scope.control||b!=scope.binding||originals.currentControl(b.taskId)!=c)return;
                    try{JSONObject event=new JSONObject(receipt.toString()).put("type",receipt.optBoolean("operationReceiptObserved")?"browser_page_dialog_operation_observed":receipt.optString("status").equals("pending")?"browser_page_dialog_pending":"browser_page_dialog_resolved");
                        for(String key:new String[]{"taskId","runId","epoch"})if(!String.valueOf(receipt.get(key)).equals(String.valueOf(key.equals("taskId")?b.taskId:key.equals("runId")?b.runId:b.epoch)))throw new SecurityException("网页提示事件不属于原执行");
                        if(!space.equals(receipt.getString("browserSpaceId")))throw new SecurityException("网页提示空间已变化");
                        tasks.event(b.taskId,b.runId,b.epoch,event);emit(b.taskId,event);
                    }catch(Exception unavailable){/* Native callback and currentness fencing never depend on a log consumer. */}
                }
            };
            scope.port=onMain(()->{requireProfile(browser.snapshot(),plan.identity);return browser.nativeAttachTaskSpace(binding,original,authority,resource,scope.approvals,sources,lifecycle,existing,plan.identity.getString("browserSessionId"));});
            if(plan.readConsent.enabled())grantReadScope(scope,scope.port.readScopeIdentityForOwner(),plan.readConsent);
            if(scope.closed||active.get(binding.taskId)!=scope)throw new SecurityException("空间建立时原任务已停止");attached=true;return scope.port;
        }finally{if(!attached)try{if(scope.port!=null)scope.port.endForOwner("admission_failed");}finally{closeScope(scope,new JSONObject().put("status","not_attached"));}}
    }
    /** Existing assistant task opts in natively; the next original turn acquires pages, without a new run. */
    public JSONObject allowExistingForOwner(String taskId,JSONObject expectedProfile,boolean consent)throws Exception {
        return allowExistingForOwner(taskId,expectedProfile,consent,BrowserReadScope.disabled());
    }
    public JSONObject allowExistingForOwner(String taskId,JSONObject expectedProfile,boolean consent,BrowserReadScope.Consent readConsent)throws Exception {return allowExistingForOwner(taskId,expectedProfile,consent,readConsent,BrowserFrameOrigins.sameOriginOnly());}
    public JSONObject allowExistingForOwner(String taskId,JSONObject expectedProfile,boolean consent,BrowserReadScope.Consent readConsent,BrowserFrameOrigins.Consent frameOrigins)throws Exception {
        worker();BrowserTaskSpace.uuid(taskId);if(!consent)throw new SecurityException("请先确认将本任务网页观察交给原模型");RunControl current=originals.currentControl(taskId);if(current==null)throw new SecurityException("原助手任务已不在运行");current.check();if(current.isPaused())throw new SecurityException("请先处理原任务暂停状态");
        synchronized(tasks){JSONObject task=tasks.get(taskId,false);JSONObject origin=task==null?null:task.optJSONObject("origin");if(task==null||!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"capabilities".equals(task.optString("executionMode"))||origin!=null&&origin.optString("kind").matches("channel|group")||!current.matchesHostRun(taskId,task.optString("runId"),task.optInt("coordinatorEpoch"))||originals.currentControl(taskId)!=current)throw new SecurityException("只能接入当前主人私人助手的原执行");if(tasks.hasUnresolved(taskId))throw new IllegalStateException("请等当前动作取得回执，先核对未知效果后再接入浏览");}
        JSONObject selected=onMain(()->{JSONObject state=browser.snapshot();requireProfile(state,expectedProfile);JSONObject space=browser.nativeTabs().optJSONObject("taskSpace");if(space!=null&&!space.optString("status").equals("ended"))throw new IllegalStateException("请先结束当前浏览空间");return profileIdentity(state);});
        if(!plans.isEmpty()||!active.isEmpty())throw new IllegalStateException("已有浏览请求，请先结束或核对");Plan plan=new Plan(taskId,selected,current,readConsent,frameOrigins);if(plans.putIfAbsent(taskId,plan)!=null)throw new IllegalStateException("此原任务已有浏览请求");latestTask=taskId;
        tasks.event(taskId,new JSONObject().put("type","browser_research_requested").put("profile",selected).put("extraFrameOrigins",frameOrigins.summary()).put("frameOriginPolicy","same_origin_plus_explicit_native_task_origins").put("phase","pending_original_turn").put("sameRun",true));return new JSONObject().put("taskId",taskId).put("status","pending_original_turn").put("message","已允许原任务在下一轮取得浏览页；未新建任务或替换执行控制");
    }
    public JSONArray currentTasksForOwner()throws Exception {return originals.currentTasksForOwner();}
    /** Explicit visible native-owner renewal; never a model command and never a new task/run. */
    public JSONObject grantReadScopeForOwner(String taskId,JSONObject expectedNativeIdentity,BrowserReadScope.Consent consent)throws Exception {
        worker();Scope scope=active.get(taskId);if(scope==null||scope.closed||scope.port==null||consent==null||!consent.enabled())throw new SecurityException("请在当前原任务明确选择新的只读范围");return grantReadScope(scope,expectedNativeIdentity,consent);
    }
    private JSONObject grantReadScope(Scope scope,JSONObject expected,BrowserReadScope.Consent consent)throws Exception {
        if(!scope.binding.allowWrites)throw new SecurityException("原任务未获得此浏览工具入口；只读范围不会增加原有写权限");
        scope.control.check();if(scope.closed||originals.currentControl(scope.binding.taskId)!=scope.control||!resources.ownsBrowser(scope.token))throw new SecurityException("原浏览资源或控制器已变化");
        JSONObject actual=scope.port.readScopeIdentityForOwner();if(!TaskStore.capabilityFingerprint(actual).equals(TaskStore.capabilityFingerprint(expected)))throw new SecurityException("确认时的原任务/资料/会话已变化");
        JSONObject granted=tasks.grantBrowserReadScope(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,scope.control,actual,consent);String id=granted.getString("scopeId");
        try{scope.control.check();if(scope.closed||originals.currentControl(scope.binding.taskId)!=scope.control||!resources.ownsBrowser(scope.token)||!TaskStore.capabilityFingerprint(actual).equals(TaskStore.capabilityFingerprint(scope.port.readScopeIdentityForOwner())))throw new SecurityException("授权后原生外部复核已变化");scope.readScopeId=id;}
        catch(Exception changed){tasks.revokeBrowserReadScope(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,id,"native_identity_changed_during_grant");throw changed;}
        try{emit(scope.binding.taskId,new JSONObject().put("type","browser_read_scope_granted").put("taskId",scope.binding.taskId).put("scopeId",id));}catch(Exception lostNotice){/* The native consent is already durable and visible to the owner projection. */}return granted;
    }
    public JSONObject revokeReadScopeForOwner(String taskId,String expectedScopeId)throws Exception {
        worker();Scope scope=active.get(taskId);if(scope==null||scope.closed||scope.port==null||originals.currentControl(taskId)!=scope.control)throw new SecurityException("原浏览任务已变化");
        JSONObject result=tasks.revokeBrowserReadScope(taskId,scope.binding.runId,scope.binding.epoch,expectedScopeId,"owner_revoked");scope.port.cancelReadScopeNative(expectedScopeId);try{emit(taskId,new JSONObject().put("type","browser_read_scope_revoked").put("taskId",taskId).put("scopeId",expectedScopeId));}catch(Exception lostNotice){/* The revocation remains durable. */}return result;
    }
    private void revokeScope(Scope scope,String reason){String id=scope.readScopeId;if(id.isEmpty())return;try{tasks.revokeBrowserReadScope(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,id,reason);}catch(Exception staleRun){/* Closing an old scope must never revoke a newer original run. */}finally{if(scope.port!=null)scope.port.cancelReadScopeNative(id);}}
    /** Precise native approval branch; null means this is another capability's existing workflow. */
    public JSONObject decideIfWaiting(String taskId,String approvalId,boolean approved)throws Exception {
        Scope scope=active.get(taskId);RunControl current=originals.currentControl(taskId);if(scope==null||scope.approvals==null||!scope.approvals.isWaiting(taskId,approvalId,current))return null;
        JSONObject task=tasks.decideCapabilityApproval(taskId,approvalId,approved);scope.approvals.nativeDecisionChanged(taskId,approvalId,current);emit(taskId,new JSONObject().put("type","browser_approval_decided").put("taskId",taskId).put("approvalId",approvalId));return task;
    }
    public JSONObject snapshotForOwner()throws Exception {
        worker();JSONObject result=onMain(()->{JSONObject out=browser.snapshot();out.put("profiles",browser.nativeProfiles()).put("pages",browser.nativeTabs()).put("downloads",browser.nativeDownloads());return out;});
        for(Scope scope:active.values())if(!scope.closed){JSONObject task=tasks.get(scope.binding.taskId,false);if(task!=null)result.put("activeResearchTask",task);result.put("researchControl",new JSONObject().put("taskId",scope.binding.taskId).put("paused",scope.control.isPaused()).put("cancelRequested",scope.control.isCancelled()).put("resourceHeld",resources.ownsBrowser(scope.token)).put("unresolved",tasks.hasUnresolved(scope.binding.taskId)));try{result.put("readScope",tasks.browserReadScopeForOwner(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,scope.control.pauseRevision()));if(!scope.control.isPaused())result.put("readScopeIdentity",scope.port.readScopeIdentityForOwner());}catch(Exception unavailable){result.put("readScope",new JSONObject().put("state","unavailable"));}break;}
        if(active.isEmpty())for(Plan plan:plans.values()){JSONObject pending=tasks.get(plan.taskId,false);if(pending!=null)result.put("pendingBrowserTask",pending);break;}
        result.put("model",originals.modelForOwner()).put("currentAssistantTasks",currentTasksForOwner());String id=latestTask;if(!id.isEmpty()){JSONObject task=tasks.get(id,false);if(task!=null){result.put("researchTask",task).put("sources",BrowserTaskSourcePort.sourcesForOwner(tasks,id)).put("downloads",onMain(()->browser.nativeDownloadsForTask(id)));JSONObject pages=BrowserResearchPages.fromTask(task);if(pages!=null)result.put("researchPages",pages).put("researchPagesFingerprint",TaskStore.capabilityFingerprint(pages)).put("researchRecoveryUnresolved",tasks.hasUnresolved(id)).put("researchRecoveryAvailable",originals.currentControl(id)==null&&!tasks.hasUnresolved(id)&&!task.optString("status").equals("completed")&&!task.has("origin"));}}return result;
    }
    public JSONObject taskForOwner(String taskId)throws Exception {BrowserTaskSpace.uuid(taskId);JSONObject task=tasks.get(taskId,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("请从主人原任务打开研究成果");JSONObject result=new JSONObject().put("task",task).put("sources",BrowserTaskSourcePort.sourcesForOwner(tasks,taskId));JSONObject pages=BrowserResearchPages.fromTask(task);if(pages!=null)result.put("researchPages",pages).put("researchPagesFingerprint",TaskStore.capabilityFingerprint(pages));return result;}
    /** View-only draft identity. No task/run/lease/approval is persisted or restored by these APIs. */
    private void requireDraftContext(String profileId,String taskId)throws Exception {
        worker();BrowserResearchDraftStore.context(profileId,taskId);if(!taskId.isEmpty())taskForOwner(taskId);
        onMain(()->{JSONObject profiles=browser.nativeProfiles();if(!profileId.equals(profiles.optString("selectedProfileId")))throw new SecurityException("研究草稿所属资料已变化，未把旧输入存入新资料");JSONArray rows=profiles.getJSONArray("profiles");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(profileId.equals(row.getString("id"))&&row.optString("status").equals("active")&&row.optBoolean("available"))return new JSONObject();}throw new SecurityException("原浏览资料已不可用，未恢复草稿或授权");});
    }
    public JSONObject researchDraftForOwner(String profileId,String taskId)throws Exception {requireDraftContext(profileId,taskId);return browser.nativeResearchDraft(profileId,taskId);}
    public JSONObject saveResearchDraftForOwner(String profileId,String taskId,long expectedRevision,String goal)throws Exception {requireDraftContext(profileId,taskId);return browser.nativeSaveResearchDraft(profileId,taskId,expectedRevision,goal);}
    public JSONObject savedSourceForOwner(String taskId,String sourceId)throws Exception {worker();return BrowserResearchResult.source(taskId,sourceId,taskForOwner(taskId).getJSONArray("sources"));}
    /** Native owner click reads the saved immutable cell, never today's DOM or a model-provided replacement. */
    public JSONObject prepareRecipeForOwner(String taskId,String sourceId,String query)throws Exception {worker();if(recipes==null)throw new UnsupportedOperationException("原技能配方入口尚未接入");return BrowserReadRecipe.fromSource(savedSourceForOwner(taskId,sourceId),query);}
    public JSONObject saveRecipeForOwner(String taskId,String sourceId,String query,String expectedFingerprint,boolean enable)throws Exception {worker();if(recipes==null)throw new UnsupportedOperationException("原技能配方入口尚未接入");JSONObject source=savedSourceForOwner(taskId,sourceId),recipe=BrowserReadRecipe.fromSource(source,query);if(!expectedFingerprint.equals(TaskStore.capabilityFingerprint(recipe)))throw new SecurityException("读取配方预览已变化，请重新核对");return recipes.saveForOwner(source,query,enable);}
    public JSONObject savedFieldForOwner(String taskId,String fieldRef)throws Exception {worker();String sourceId=BrowserStructuredSource.sourceId(taskId,fieldRef);return BrowserStructuredSource.field(taskId,fieldRef,savedSourceForOwner(taskId,sourceId));}
    /** Explicit owner click only: original task/receipt/version first, original canonical bytes second. */
    public android.content.Intent openDownloadedAttachmentForOwner(String taskId,String downloadId,String attachmentId,String expectedSha,RunControl ownerRead)throws Exception {
        worker();Objects.requireNonNull(ownerRead);ownerRead.check();taskForOwner(taskId);
        JSONObject receipt=onMain(()->browser.nativeDownloadReceipt(downloadId));BrowserResearchResult.completedAttachment(taskId,downloadId,receipt,attachmentId,expectedSha);
        android.content.Intent prepared=browser.nativeOpenResearchAttachment(taskId,attachmentId,expectedSha,ownerRead);
        try{ownerRead.check();taskForOwner(taskId);JSONObject current=onMain(()->browser.nativeDownloadReceipt(downloadId));BrowserResearchResult.completedAttachment(taskId,downloadId,current,attachmentId,expectedSha);ownerRead.check();return prepared;}
        catch(Exception stopped){try{browser.nativeDiscardResearchView(prepared);}catch(Exception cleanup){stopped.addSuppressed(cleanup);}throw stopped;}
    }
    public void discardResearchViewForOwner(android.content.Intent prepared)throws Exception {browser.nativeDiscardResearchView(prepared);}
    public JSONArray historyForOwner()throws Exception {JSONArray history=new JSONArray(),rows=tasks.list();for(int i=0;i<rows.length()&&history.length()<20;i++){JSONObject task=rows.getJSONObject(i);JSONArray recorded=task.optJSONArray("events");for(int j=0;recorded!=null&&j<recorded.length();j++)if("browser_research_requested".equals(recorded.getJSONObject(j).optString("type"))){history.put(new JSONObject().put("taskId",task.getString("id")).put("goal",task.optString("goal")).put("status",task.optString("status")).put("updatedAt",task.optLong("updatedAt")));break;}}return history;}
    /** Explicit owner re-open, not a permit restore. Uses only the saved sanitized page address in its original Profile. */
    public JSONObject reopenResearchPageForOwner(String taskId,String pageRecordId,String expectedManifest)throws Exception {
        worker();JSONObject selected=taskForOwner(taskId),manifest=selected.getJSONObject("researchPages");if(!TaskStore.capabilityFingerprint(manifest).equals(expectedManifest))throw new SecurityException("研究页清单已变化，请重新查看");JSONObject page=BrowserResearchPages.required(manifest,pageRecordId);if(page.getString("url").isEmpty())throw new IllegalStateException("此页没有可重开的HTTP地址，请回原任务手动选择");
        return onMain(()->{JSONObject profile=browser.snapshot();if(!manifest.getString("profileId").equals(profile.optString("profileId")))throw new SecurityException("请先选回原研究的登录资料，未改用其他账号");return browser.nativeOpenOwnerTab(page.getString("url"));});
    }
    /** Same durable task, new original attempt/session refs. Unknown operations must be reconciled by the original TaskStore first. */
    public JSONObject recoverResearchForOwner(String taskId,String expectedManifest,JSONObject expectedProfile,boolean consent)throws Exception {
        worker();if(!consent)throw new SecurityException("请先确认重新把当前网页观察交给模型");originals.checkModelConfigured();JSONObject task=taskForOwner(taskId).getJSONObject("task"),manifest=BrowserResearchPages.fromTask(task);
        if(manifest==null||!TaskStore.capabilityFingerprint(manifest).equals(expectedManifest)||!manifest.getString("profileId").equals(expectedProfile.optString("profileId")))throw new SecurityException("原研究清单或登录资料已变化");
        if(task.has("origin"))throw new SecurityException("此研究属于其他原任务入口，请回原任务继续；没有替换其执行授权");
        if(task.optString("status").equals("completed")||originals.currentControl(taskId)!=null||tasks.hasUnresolved(taskId))throw new IllegalStateException("原任务仍在执行、已完成或有动作结果待核对，请先回原任务处理");
        JSONObject selected=onMain(()->{JSONObject current=browser.snapshot();requireProfile(current,expectedProfile);JSONObject space=browser.nativeTabs().optJSONObject("taskSpace");if(space!=null&&!space.optString("status").equals("ended"))throw new SecurityException("请先结束当前浏览研究再恢复原任务");return profileIdentity(current);});
        if(!plans.isEmpty()||!active.isEmpty())throw new IllegalStateException("已有浏览请求，请先处理");Plan plan=new Plan(taskId,selected,null,BrowserReadScope.disabled(),BrowserFrameOrigins.sameOriginOnly(),manifest);if(plans.putIfAbsent(taskId,plan)!=null)throw new IllegalStateException("此研究已经在恢复");latestTask=taskId;
        try{JSONObject fresh=tasks.get(taskId,false);if(tasks.hasUnresolved(taskId)||!expectedManifest.equals(TaskStore.capabilityFingerprint(BrowserResearchPages.fromTask(fresh))))throw new SecurityException("恢复前原任务出现了新的结果，请重新核对");tasks.event(taskId,new JSONObject().put("type","browser_recovery_requested").put("previousRunId",manifest.getString("runId")).put("previousEpoch",manifest.getInt("epoch")).put("manifestFingerprint",expectedManifest).put("newNativeConsent",true).put("restoredGrant",false).put("restoredActionRefs",false));originals.launch(taskId);return new JSONObject().put("taskId",taskId).put("status","recovery_requested").put("message","正在原任务中新建执行并重新读取网页；每次重开仍经原工具确认，未恢复旧授权或重发未知动作。");}
        catch(Exception failure){plans.remove(taskId,plan);throw failure;}
    }
    public JSONObject selectHistoryForOwner(String taskId)throws Exception {JSONObject task=taskForOwner(taskId);latestTask=taskId;return task;}
    public void openUiForOwner()throws Exception {onMain(()->{browser.nativeOpenResearchUi();return new JSONObject();});}
    public void openUiForOwner(String taskId)throws Exception {if(taskId.isEmpty()){openUiForOwner();return;}worker();taskForOwner(taskId);latestTask=taskId;onMain(()->{browser.nativeOpenResearchUi(taskId);return new JSONObject();});}
    public JSONObject selectProfileForOwner(String profileId,long revision)throws Exception {endForOwner();return onMain(()->browser.nativeSelectProfile(profileId,revision));}
    public JSONObject createProfileForOwner(String label,long revision)throws Exception {return onMain(()->browser.nativeCreateProfile(label,revision));}
    public JSONObject deleteProfileForOwner(String profileId,long revision)throws Exception {if(profileId.equals(onMain(()->browser.snapshot()).optString("profileId")))endForOwner();JSONObject deleted=onMain(()->browser.nativeDeleteProfile(profileId,revision));try{browser.nativeClearResearchDraftProfile(profileId);deleted.put("researchDraftsCleared",true);}catch(Exception cleanup){deleted.put("researchDraftsCleared",false).put("researchDraftCleanupMessage","资料已停用；本机文字草稿清理未确认，未恢复任何授权。");}return deleted;}
    public void selectOwnerPage(String tabId)throws Exception {onMain(()->{browser.nativeSelectOwnerTab(tabId);return new JSONObject();});}
    public void closeOwnerPage(String tabId)throws Exception {onMain(()->{browser.nativeCloseOwnerTab(tabId);return new JSONObject();});}
    public void takeOverPage(String tabId)throws Exception {onMain(()->{browser.nativeTakeOverTaskTab(tabId);return new JSONObject();});}
    /** The native return button resumes this retained run; it never launches a replacement task. */
    public JSONObject returnForOwner(JSONObject expected)throws Exception {
        worker();JSONObject pin=new JSONObject(expected.toString());BrowserTaskSpace.uuid(pin.getString("taskId"));Scope scope=active.get(pin.getString("taskId"));
        if(scope==null||scope.closed||scope.port==null||!scope.binding.runId.equals(pin.optString("runId"))||scope.binding.epoch!=pin.optInt("epoch")||originals.currentControl(scope.binding.taskId)!=scope.control||!scope.control.matchesHostRun(scope.binding.taskId,scope.binding.runId,scope.binding.epoch)||!scope.control.isPaused()||scope.control.isCancelled()||scope.control.pauseRevision()!=pin.getLong("pauseRevision"))throw new SecurityException("只能交还仍暂停的原浏览任务与执行");
        scope.control.check();scope.port.requireOwnerReturnReady();String previousStatus;
        synchronized(tasks){JSONObject task=tasks.get(scope.binding.taskId,false);if(task==null||!scope.binding.runId.equals(task.optString("runId"))||scope.binding.epoch!=task.optInt("coordinatorEpoch")||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optBoolean("cancelRequested")||!task.optString("status").matches("running|paused"))throw new SecurityException("原任务已结束或执行身份变化");if(tasks.hasUnresolved(scope.binding.taskId))throw new IllegalStateException("请先核对原动作结果，未决操作不能交还或重试");previousStatus=task.getString("status");}
        if(!resources.acquireBrowser(scope.token,System.currentTimeMillis()+600_000L))throw new IllegalStateException("手机或浏览器正由其他任务使用，请稍后交还");
        String returnRead="";try{
            synchronized(tasks){tasks.status(scope.binding.taskId,"running","正在把原页面交还同一任务");tasks.checkCapabilityCurrent(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,"browser_task_open");}
            returnRead=scope.port.expectOwnerReturnObservation(pin,()->{scope.control.check();if(scope.closed||originals.currentControl(scope.binding.taskId)!=scope.control||!scope.control.matchesHostRun(scope.binding.taskId,scope.binding.runId,scope.binding.epoch))throw new SecurityException("交还观察的原执行控制已失效");synchronized(tasks){tasks.checkCapabilityCurrent(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,"browser_task_observe");if(tasks.hasUnresolved(scope.binding.taskId))throw new SecurityException("原任务出现未决动作，交还观察已拒绝");}});
            JSONObject receipt=onMain(()->browser.nativeReturnTaskSpace(pin,scope.control,()->{if(scope.closed||originals.currentControl(scope.binding.taskId)!=scope.control||!resources.ownsBrowser(scope.token))throw new SecurityException("原浏览任务控制已变化");scope.control.resumeAtPauseRevision(pin.optLong("pauseRevision",-1));}));
            try{JSONObject event=new JSONObject(receipt.toString()).put("type","browser_owner_returned");tasks.event(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,event);emit(scope.binding.taskId,event);}catch(Exception unavailable){/* Native return has an actual receipt; a lost observer must not replay it. */}return receipt;
        }catch(Exception rejected){scope.port.abandonOwnerReturnObservation(returnRead);resources.releaseBrowser(scope.token);if(scope.control.isPaused()&&!scope.control.isCancelled()&&originals.currentControl(scope.binding.taskId)==scope.control)try{tasks.status(scope.binding.taskId,previousStatus,"交还未完成，原任务仍暂停，请核对当前页面");}catch(Exception unavailable){}throw rejected;}
    }
    public void adoptPage(String tabId)throws Exception {onMain(()->{browser.nativeAdoptOwnerTab(tabId);return new JSONObject();});}
    public JSONObject endForOwner()throws Exception {
        Scope selected=null;for(Scope scope:active.values())if(!scope.closed){selected=scope;break;}if(selected==null){boolean pending=!plans.isEmpty();cancelForOwner();return new JSONObject().put("status",pending?"admission_cancelled":"not_active");}selected.control.cancel();return selected.port==null?new JSONObject().put("status","admission_cancelled"):selected.port.endForOwner("owner_ended_research");
    }
    public void cancelForOwner(){for(Scope scope:active.values())if(!scope.closed)scope.control.cancel();for(Plan plan:plans.values()){plan.cancelled=true;RunControl current=originals.currentControl(plan.taskId);if(current!=null&&(plan.selectedControl==null||plan.selectedControl==current))current.cancel();}}
    /** Reopen the exact saved private URL in its original selected Profile, in a new owner page. */
    public JSONObject openSourceForOwner(String taskId,String sourceId)throws Exception {
        BrowserTaskSpace.uuid(sourceId);JSONArray sources=taskForOwner(taskId).getJSONArray("sources");JSONObject found=null;for(int i=0;i<sources.length();i++)if(sourceId.equals(sources.getJSONObject(i).getString("sourceId")))found=sources.getJSONObject(i);if(found==null)throw new IllegalArgumentException("原任务没有这个来源");final JSONObject source=found;
        return onMain(()->{JSONObject selected=browser.snapshot();if(!source.getString("profileId").equals(selected.optString("profileId")))throw new SecurityException("请先明确选回来源原登录资料，再打开原出处");return browser.nativeOpenOwnerTab(source.getString("privateUrl"));});
    }
    private void ownerControl(Scope scope,String type,String space,String tab){scope.control.pause();revokeScope(scope,"owner_pause_or_takeover");resources.releaseBrowser(scope.token);try{JSONObject event=new JSONObject().put("type",type).put("taskId",scope.binding.taskId).put("runId",scope.binding.runId).put("epoch",scope.binding.epoch).put("browserSpaceId",space).put("tabId",tab);tasks.event(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,event);emit(scope.binding.taskId,event);}catch(Exception unavailable){/* Original cancellation and resource fencing do not depend on an observer. */}}
    private void closeScope(Scope scope,JSONObject receipt){synchronized(scope){if(scope.closed)return;scope.closed=true;}revokeScope(scope,"browser_scope_closed");if("profile_closed".equals(receipt.optString("reason")))scope.control.cancel();active.remove(scope.binding.taskId,scope);plans.remove(scope.binding.taskId,scope.plan);resources.releaseBrowser(scope.token);if(scope.approvals!=null)scope.approvals.close();try{JSONObject event=new JSONObject().put("type","browser_space_closed").put("taskId",scope.binding.taskId).put("runId",scope.binding.runId).put("epoch",scope.binding.epoch).put("cleanup",receipt);tasks.event(scope.binding.taskId,scope.binding.runId,scope.binding.epoch,event);emit(scope.binding.taskId,event);}catch(Exception unavailable){/* Cleanup remains scoped even when persistence is unavailable. */}}
    private void emit(String taskId,JSONObject event)throws Exception {events.emit(event);}
    private static JSONObject profileIdentity(JSONObject snapshot)throws Exception {JSONObject out=new JSONObject();for(String key:new String[]{"profileId","profileRevision","browserSessionId","profileIsolation"})out.put(key,snapshot.get(key));return out;}
    private static void requireProfile(JSONObject current,JSONObject expected)throws Exception {if(!current.optBoolean("open"))throw new IllegalStateException("请先打开浏览网页并选定登录资料");for(String key:new String[]{"profileId","profileRevision","browserSessionId"})if(!String.valueOf(current.opt(key)).equals(String.valueOf(expected.opt(key))))throw new SecurityException("主人确认的登录资料或浏览会话已变化，请刷新");}
    private interface MainRead<T>{T get()throws Exception;}
    private <T>T onMain(MainRead<T> read)throws Exception {if(Looper.myLooper()==Looper.getMainLooper())return read.get();CompletableFuture<T> value=new CompletableFuture<>();if(!main.post(()->{if(value.isDone())return;try{value.complete(read.get());}catch(Exception failure){value.completeExceptionally(failure);}}))throw new IllegalStateException("浏览器主线程不可用");try{return value.get(3,TimeUnit.SECONDS);}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(failure);}finally{value.cancel(false);}}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("研究持久化与任务装配必须使用现有有界worker");}
    /** Turn-boundary adapter. Native opt-in never injects a new browser into a prepared old call. */
    private final class PendingPort implements CapabilityPort {
        private final ModelCapabilityAdapters.Binding binding;private final RunControl original;private final CapabilityPort base;private final dev.pocketengine.features.memory.ToolOutputCapture.Factory outputCapture;private volatile CapabilityPort selected;private volatile boolean closed;
        PendingPort(ModelCapabilityAdapters.Binding binding,RunControl original,CapabilityPort base,dev.pocketengine.features.memory.ToolOutputCapture.Factory outputCapture){this.binding=binding;this.original=original;this.base=Objects.requireNonNull(base);this.outputCapture=outputCapture;selected=base;}
        public JSONArray tools()throws Exception{return selected.tools();}public boolean supports(String name){return selected.supports(name);}public boolean sideEffecting(String name){return selected.sideEffecting(name);}public void validate(String name,JSONObject args)throws Exception{selected.validate(name,args);}
        public JSONObject contextSnapshot(String query)throws Exception{return selected.contextSnapshot(query);}public JSONObject execute(String name,JSONObject args,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原助手浏览适配器已失效");return selected.execute(name,args,supplied);}
        public void beginRun(String goal,RunControl supplied)throws Exception {if(supplied!=original)throw new SecurityException("不能更换原助手控制器");selected.beginRun(goal,supplied);}
        public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原助手浏览适配器已失效");selected.admitCapabilities(snapshot,supplied);}
        public void prepareTurnSurface(RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原助手浏览适配器已失效");if(selected==base&&plans.containsKey(binding.taskId))selected=attachRequested(binding,original,base,outputCapture);selected.prepareTurnSurface(supplied);}
        public JSONObject prepareTurn(String query,int round,int steps,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原助手浏览适配器已失效");return selected.prepareTurn(query,round,steps,supplied);}
        public boolean responseCurrent(RunControl supplied)throws Exception{return supplied==original&&!closed&&selected.responseCurrent(supplied);}public JSONObject beforeTool(String name,JSONObject args,RunControl supplied)throws Exception{return selected.beforeTool(name,args,supplied);}public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl supplied)throws Exception{selected.afterTool(name,args,receipt,supplied);}public void endRun(RunControl supplied)throws Exception{selected.endRun(supplied);}
        public void beforeModelRequest(String requestId,JSONArray messages,RunControl supplied)throws Exception{if(supplied!=original||closed)throw new SecurityException("原助手浏览请求已失效");selected.beforeModelRequest(requestId,messages,supplied);}
        public JSONObject afterModelResponse(String requestId,String answerId,RunControl supplied)throws Exception{if(supplied!=original||closed)throw new SecurityException("原助手浏览请求已失效");return selected.afterModelResponse(requestId,answerId,supplied);}
        public void modelRequestAbandoned(String requestId)throws Exception{selected.modelRequestAbandoned(requestId);}
        public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String tool,JSONObject args,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原浏览适配器已失效");return selected.observationRequirement(tool,args,supplied);}
        public dev.pocketengine.core.NativeToolObservation takeToolObservation(String tool,String operation,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原浏览适配器已失效");return selected.takeToolObservation(tool,operation,supplied);}
        public dev.pocketengine.core.NativeToolPlan takeToolPlan(String tool,String operation,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原浏览适配器已失效");return selected.takeToolPlan(tool,operation,supplied);}
        public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl supplied)throws Exception {if(supplied!=original||closed)throw new SecurityException("原浏览适配器已失效");return selected.nativeReadContextRepair(messages,supplied);}
        public void close(){closed=true;selected.close();if(selected==base){Plan pending=plans.get(binding.taskId);if(pending!=null)plans.remove(binding.taskId,pending);}}
    }
}
