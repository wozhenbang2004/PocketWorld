package dev.pocketengine.features;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.remote.HarnessCapabilityObservation;
import dev.pocketengine.features.teams.TeamsModule;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner page only. Transport modules and model JSON cannot create these ephemeral reviews. */
public final class RemoteOwnerWorkflows {
    private static final class Window {dev.pocketengine.features.remote.NativeHarnessGatewayOwner.Page gateway;volatile long gatewayRevision;dev.pocketengine.features.remote.NativeHarnessGatewayOwner.ParentAccess gatewayParentAccess;JSONObject gatewayParentReview;JSONArray gatewayProfileChoices;volatile String gatewayParentDigest;String lastGatewayParentDigest;long gatewayParentReviewExpires;String gatewayPreparedReview,gatewayPreparedRequest,gatewayPreparedOperation,lastGatewayDispatchDigest,lastGatewayDispatchOperation;volatile String gatewayExecutionDigest;JSONObject gatewayExecutionReview;TaskStore.HarnessGatewayConfiguration gatewayExecutionConfiguration;long gatewayExecutionExpires;final String id,task;final Object nativeOwner;JSONObject source;volatile String review;String profileBinding,clipboardReadReview;JSONObject result,artifact,snapshotFile;long reviewedAt,readGeneration,lastReadAttemptAt,lastSuccessfulReadAt;String queryStatus="not_queried",eventReadStatus="not_queried";volatile long lifecycleRevision;volatile boolean closed;boolean fresh,savingResult;JSONObject directory,conversationResult,projectFirst,projectEnvironment,projectReview,projectPage,projectDiff,projectOperation,projectReadSource;String projectReadOperation;volatile String projectDigest;long projectExpires;boolean projectBusy;String conversationDigest;long conversationExpiresAt;dev.pocketengine.features.channels.ChannelsModule.PrivateInputSource conversationSource;final Map<String,JSONObject> selectedFiles=new LinkedHashMap<>();TaskStore.TeamRemoteOwnerWindow teamWindow;final Map<String,TaskStore.TeamRemoteOwnerReview> teamReviews=new LinkedHashMap<>();Window(String id,String task,Object nativeOwner){this.id=id;this.task=task;this.nativeOwner=nativeOwner;}}
    /** Native owner approval for a real original-task RELAY-only run; never JSON-restorable. */
    public static final class GatewayParentRunApproval {
        private final RemoteOwnerWorkflows host;private final Window window;private final dev.pocketengine.features.remote.NativeHarnessGatewayOwner.ParentAccess access;private final String digest;private final long lifecycle,expires;
        private GatewayParentRunApproval(RemoteOwnerWorkflows host,Window window){this.host=host;this.window=window;access=window.gatewayParentAccess;digest=window.gatewayParentDigest;lifecycle=window.lifecycleRevision;expires=window.gatewayParentReviewExpires;}
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        public boolean issuedFor(dev.pocketengine.features.remote.NativeHarnessGatewayOwner.ParentAccess candidate){return access==candidate;}
        public TaskStore.GatewayParentRunReview review(){return access.review();}
        public String digest(){return digest;}
        public void checkWindowCurrent()throws Exception {if(window.closed||host.foregroundOwner!=window.nativeOwner||window.nativeOwner==null||window.lifecycleRevision!=lifecycle||!digest.equals(window.gatewayParentDigest)||System.currentTimeMillis()>=expires)throw new SecurityException("本次原父任务网关恢复批准已失效");}
        public void checkCurrent()throws Exception {checkWindowCurrent();access.checkCurrent();}
    }
    /** One foreground choice on this original settled session. It grants no tool or budget. */
    public static final class ModelSelectionApproval {
        private final RemoteOwnerWorkflows host;private final Window window;private final String review,value;private final long lifecycle;
        private ModelSelectionApproval(RemoteOwnerWorkflows host,Window window,JSONObject selection)throws Exception {this.host=host;this.window=window;review=window.review;lifecycle=window.lifecycleRevision;JSONObject checked=dev.pocketengine.features.remote.RemoteModelOptions.select(window.source,selection);value=new JSONObject().put("source",new JSONObject(window.source.toString())).put("selection",checked).toString();}
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public void checkCurrent()throws Exception {synchronized(host){if(window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.closed||lifecycle!=window.lifecycleRevision||window.teamWindow!=null)throw new SecurityException("模型/档位选择须原生当前前台私人会话页");host.checkReview(window,review);}}
    }
    /** Explicit owner preparation only. This is not an execution or a model budget grant. */
    public static final class GatewayContinuationPreparation {
        private final RemoteOwnerWorkflows host;private final Window window;private final dev.pocketengine.features.remote.NativeHarnessGatewayOwner.Access admission;
        private final String value,review;private final long lifecycle,expires,reviewedUptime;
        private GatewayContinuationPreparation(RemoteOwnerWorkflows host,Window window,String prompt,JSONObject snapshot,dev.pocketengine.features.remote.NativeHarnessGatewayOwner.Access admission)throws Exception {
            this.host=host;this.window=window;this.admission=admission;review=window.review;reviewedUptime=window.reviewedAt;lifecycle=window.lifecycleRevision;JSONObject nativeView=admission.view(),budget=nativeView.getJSONObject("budget");if(!budget.optBoolean("scopePresent")||budget.getInt("modelCallsRemaining")<1)throw new SecurityException("原任务尚无已确认模型执行范围或额度耗尽");expires=Math.min(budget.getLong("deadline"),System.currentTimeMillis()+600_000L);
            JSONObject scope=new JSONObject().put("source",new JSONObject(window.source.toString())).put("prompt",prompt).put("profileBinding",window.profileBinding).put("expiresAt",expires).put("parent",host.tasks.gatewayParentTarget(window.task)).put("parentEpoch",nativeView.getInt("epoch")).put("scopeRevision",budget.getJSONObject("scope").getLong("revision")).put("ownerReviewId",review);if(snapshot!=null)scope.put("snapshot",new JSONObject(snapshot.toString()));value=scope.toString();
        }
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public void checkCurrent()throws Exception {if(window.closed||window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle||!review.equals(window.review)||System.currentTimeMillis()>=expires||android.os.SystemClock.uptimeMillis()-reviewedUptime>60_000L)throw new SecurityException("本次原生新轮准备批准已失效");JSONObject accepted=view();if(!accepted.getString("profileBinding").equals(host.module().profileBindingHash(accepted.getJSONObject("source").getString("profileId"))))throw new SecurityException("原电脑连接代次改变");host.tasks.checkRemoteContinuationSource(accepted.getJSONObject("source"));admission.checkCurrent();JSONObject current=admission.view().getJSONObject("budget");if(!current.optBoolean("scopePresent")||current.getInt("modelCallsRemaining")<1||current.getJSONObject("scope").getLong("revision")!=view().getLong("scopeRevision"))throw new SecurityException("原父任务执行范围或剩余额度改变，请重新审阅");}
    }
    /** Read-only cancellation observation, tied to the exact local original prepared operation. */
    public static final class GatewayContinuationCancellation {
        private final RemoteOwnerWorkflows host;private final Window window;private final String value;private final long lifecycle;
        private GatewayContinuationCancellation(RemoteOwnerWorkflows host,Window window,JSONObject facts){this.host=host;this.window=window;value=facts.toString();lifecycle=window.lifecycleRevision;}
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public void checkCurrent()throws Exception{if(window.closed||window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle)throw new SecurityException("取消核对已离开原前台；已撤销的权限不恢复");JSONObject scope=view();if(!scope.getString("profileBinding").equals(host.module().profileBindingHash(scope.getString("profileId"))))throw new SecurityException("原连接代次改变，只保留取消意图，不伪称未派发");}
    }
    /** Final single native dispatch approval; configuration facts alone cannot create it. */
    public static final class GatewayContinuationExecutionApproval {
        private final RemoteOwnerWorkflows host;private final Window window;private final String value,digest;private final long lifecycle,expires;private final TaskStore.HarnessGatewayConfiguration configuration;
        private GatewayContinuationExecutionApproval(RemoteOwnerWorkflows host,Window window){this.host=host;this.window=window;value=window.gatewayExecutionReview.toString();digest=window.gatewayExecutionDigest;lifecycle=window.lifecycleRevision;expires=window.gatewayExecutionExpires;configuration=window.gatewayExecutionConfiguration;}
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        public boolean issuedBy(RemoteModule module){return host.module()==module;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public TaskStore.HarnessGatewayConfiguration configuration(){return configuration;}
        public void checkCurrent()throws Exception {if(window.closed||window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle||!digest.equals(window.gatewayExecutionDigest)||System.currentTimeMillis()>=expires)throw new SecurityException("本轮最终执行批准已关闭、过期或改变");if(!view().getString("profileBinding").equals(host.module().profileBindingHash(view().getString("profileId"))))throw new SecurityException("原电脑配置代次改变，不派发新轮");host.tasks.checkGatewayContinuationExecutionScope(view(),configuration);}
    }
    /** Private constructor: a review JSON cannot become an owner-approved external message. */
    public static final class ConversationResultApproval {
        private final RemoteOwnerWorkflows host;private final Window window;private final String value,digest;private final long lifecycle,expires;
        private final dev.pocketengine.features.channels.ChannelsModule issuer;private final dev.pocketengine.features.channels.ChannelsModule.PrivateInputSource inputSource;
        private ConversationResultApproval(RemoteOwnerWorkflows host,Window window,dev.pocketengine.features.channels.ChannelsModule issuer)throws Exception {
            this.host=host;this.window=window;this.issuer=issuer;value=window.conversationResult.toString();digest=window.conversationDigest;lifecycle=window.lifecycleRevision;expires=window.conversationExpiresAt;inputSource=window.conversationSource;
        }
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public boolean issuedBy(dev.pocketengine.features.channels.ChannelsModule module){return issuer==module&&inputSource.issuedBy(module);}
        public void checkCurrent()throws Exception {
            synchronized(host){if(window.closed||host.windows.get(window.id)!=window||window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle||window.conversationSource!=inputSource||!digest.equals(window.conversationDigest)||System.currentTimeMillis()>expires)throw new SecurityException("本次原会话回发确认已失效，没有发送消息");}
            JSONObject scope=new JSONObject(value),expected=scope.getJSONObject("originalTarget"),current=host.tasks.remoteOwnerTarget(window.task);if(!scope.getString("profileGeneration").equals(host.module().profileBindingHash(scope.getString("profileId")))||!sameReadTarget(expected,current,false))throw new SecurityException("原电脑配置或task/run/job已变化，未排入回发");
            for(String key:new String[]{"environmentId","worker","protocol","threadId","sessionId","promptRequestId"})if(expected.has(key)&&!expected.get(key).equals(current.opt(key)))throw new SecurityException("原成果会话或执行环境已变化");
            var metadata=host.module().resultAttachmentBook().readVerifiedMetadata(scope.getString("attachmentId"),scope.getString("sha256"),host.resultReadControl(window,scope.getString("profileId"),scope.getString("profileGeneration"),lifecycle));
            if(metadata.size!=scope.getLong("bytes"))throw new SecurityException("原已阅成果字节数变化");inputSource.checkCurrent();
        }
    }
    /** Native single-action evidence. No JSON, model tool, or old team grant constructs one. */
    public static final class ProjectApproval {
        private final RemoteOwnerWorkflows host;private final Window window;private final String scope,digest;private final long lifecycle,expires;
        private ProjectApproval(RemoteOwnerWorkflows host,Window window)throws Exception{this.host=host;this.window=window;scope=window.projectReview.toString();digest=window.projectDigest;lifecycle=window.lifecycleRevision;expires=window.projectExpires;}
        public JSONObject view()throws Exception{return new JSONObject(scope);}
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        /** Original SQL cancellation fact only; never reconstitutes a dispatch or grants retry. */
        public JSONObject originalLocalCancellation()throws Exception{JSONObject value=view(),row=host.tasks.originalRemoteProjectAction(value.getString("taskId"),value.getString("operationId")),receipt=row.optJSONObject("receipt"),proof=receipt==null?null:receipt.optJSONObject("nativeLocalDispatchProof");return receipt!=null&&"cancelled".equals(receipt.optString("status"))&&proof!=null&&proof.optBoolean("originalPreparedClaimNotConsumed")&&TaskStore.capabilityFingerprint(value).equals(proof.optString("scopeHash"))?new JSONObject(receipt.toString()):null;}
        /** Pure lifecycle/expiry check, safe inside the original TaskStore transaction. */
        public void checkCurrent()throws Exception{if(window.closed||window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle||!digest.equals(window.projectDigest)||System.currentTimeMillis()>expires)throw new SecurityException("本次项目版本/恢复/分支的原生单次审阅已失效");}
    }
    /** Native current-member authority only; JSON/private-session callers cannot manufacture it. */
    public static final class TeamMemberInteractionApproval {
        private final RemoteOwnerWorkflows host;private final Window window;private final String source,member,requestId,requestHash,kind,binding;private final long lifecycle,expires;
        private TeamMemberInteractionApproval(RemoteOwnerWorkflows host,Window window,String kind,String requestId,String requestHash)throws Exception {
            this.host=host;this.window=window;this.kind=kind;this.requestId=requestId;this.requestHash=requestHash;source=window.source.toString();binding=window.profileBinding;lifecycle=window.lifecycleRevision;expires=System.currentTimeMillis()+30_000L;member=TaskStore.capabilityFingerprint(host.checkTeamMemberInteraction(window.source));
        }
        public boolean issuedBy(TaskStore store){return host.tasks==store;}
        /** Called outside the SQL monitor; preserve Coordinator/RemoteModule → TaskStore lock order. */
        public void checkCoordinatorCurrent()throws Exception {JSONObject captured=new JSONObject(source);if(!binding.equals(host.module().profileBindingHash(captured.getString("profileId"))))throw new SecurityException("原电脑连接代次已改变");host.checkTeamMemberInteraction(captured);}
        public void checkCurrent(JSONObject expected,String expectedKind,String id,String hash)throws Exception {
            if(!kind.equals(expectedKind)||!requestId.equals(id)||!requestHash.equals(hash)||window.closed||window.nativeOwner==null||host.foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle||System.currentTimeMillis()>=expires)throw new SecurityException("原成员单次询问/审批已关闭、过期或改变");
            JSONObject captured=new JSONObject(source);for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker","protocol","threadId","turnId","sessionId","promptRequestId"})if(!java.util.Objects.equals(captured.opt(key),expected.opt(key)))throw new SecurityException("不是原成员的原会话请求");
            if(!member.equals(TaskStore.capabilityFingerprint(memberInteractionIdentity(host.tasks.requireTeamMemberInteraction(expected)))))throw new SecurityException("原成员/电脑/协调器代次已改变");
        }
    }
    private static boolean isTeamMember(JSONObject task){JSONObject origin=task.optJSONObject("origin");return task.has("teamRunId")||task.has("memberId")||task.has("round")||task.has("generation")||origin!=null&&origin.optString("kind").startsWith("team");}
    private JSONObject checkTeamMemberInteraction(JSONObject source)throws Exception {
        JSONObject task=tasks.requireTeamMemberInteraction(source);EngineModule team=host.module("teams");
        if(!(team instanceof TeamsModule)||!((TeamsModule)team).ownsMemberExecution(task.getString("teamRunId"),task.getString("memberId"),task.getInt("round"),task.getInt("generation"),task.getString("operationId")))throw new SecurityException("原成员执行已被停止、替换或换代");
        return memberInteractionIdentity(task);
    }
    private static JSONObject memberInteractionIdentity(JSONObject task)throws Exception {JSONObject identity=new JSONObject();for(String key:new String[]{"id","runId","coordinatorEpoch","parentTaskId","parentRunId","teamRunId","memberId","round","generation","operationId","worker","readOnly","teamReadOnlyTools"})if(task.has(key))identity.put(key,task.get(key));return identity;
    }
    private final FeatureHost host;private final TaskStore tasks;private final EventSink events;
    private final dev.pocketengine.features.remote.RemoteInputAttachments inputs;
    private final dev.pocketengine.features.remote.NativeHarnessConfigs harnessConfigs;
    private final Map<String,Window> windows=new LinkedHashMap<>();
    private dev.pocketengine.features.remote.NativeHarnessGatewayOwner gatewayOwner;
    public synchronized void bindGatewayOwner(dev.pocketengine.features.remote.NativeHarnessGatewayOwner.Host owner){if(gatewayOwner!=null)throw new IllegalStateException("原gateway owner不能替换");gatewayOwner=new dev.pocketengine.features.remote.NativeHarnessGatewayOwner(tasks,this::module,java.util.Objects.requireNonNull(owner));}
    private volatile Object foregroundOwner;
    /** Actual Activity resume/pause only; foregrounding never restores an old review. */
    public void setForeground(Object nativeOwner,boolean value){
        java.util.Objects.requireNonNull(nativeOwner);synchronized(this){Object next=value?nativeOwner:foregroundOwner==nativeOwner?null:foregroundOwner;if(next!=foregroundOwner){foregroundOwner=next;for(Window window:windows.values()){window.lifecycleRevision++;if(window.gatewayParentAccess!=null)window.gatewayParentAccess.ownerWindowLost();window.readGeneration++;window.queryStatus="paused";window.eventReadStatus="paused";window.fresh=false;window.review=null;window.conversationResult=null;window.conversationDigest=null;window.conversationSource=null;window.projectDigest=null;window.projectReview=null;window.gatewayExecutionDigest=null;window.gatewayExecutionReview=null;window.gatewayExecutionConfiguration=null;window.gatewayParentDigest=null;window.gatewayParentReview=null;window.teamReviews.clear();if(window.teamWindow!=null)window.teamWindow.setForeground(window.nativeOwner==foregroundOwner);}}}
        inputs.setForeground(nativeOwner,value);
        harnessConfigs.setForeground(nativeOwner,value);
        if(value)try{module().requestReadRefresh();}catch(Exception unavailable){/* Read recovery only; explicit original query remains available. */}
    }

    /** Native foreground local index only; not registered in the feature/JSON command dispatcher. */
    public JSONObject managedSessions(Object nativeOwner)throws Exception {
        checkManagedSessionsOwner(nativeOwner);JSONObject value=tasks.remoteManagedSessions();checkManagedSessionsOwner(nativeOwner);return value;
    }
    public String managedSessionTask(Object nativeOwner,String task,String identity)throws Exception {
        checkManagedSessionsOwner(nativeOwner);String id=tasks.remoteManagedSessionTask(task,identity);checkManagedSessionsOwner(nativeOwner);return id;
    }
    private synchronized void checkManagedSessionsOwner(Object nativeOwner){if(nativeOwner==null||foregroundOwner!=nativeOwner)throw new SecurityException("请在原生前台私人电脑页读取原任务清单");}

    public RemoteOwnerWorkflows(FeatureHost host,TaskStore tasks,EventSink events){this.host=host;this.tasks=tasks;this.events=events;inputs=new dev.pocketengine.features.remote.RemoteInputAttachments(host,tasks,events);harnessConfigs=new dev.pocketengine.features.remote.NativeHarnessConfigs(host,tasks,events);}
    /** Original native GroupOwner resolver only; opaque source cannot be restored from JSON. */
    public JSONObject openGroupInputs(Object nativeOwner,String window,String task,JSONObject request,dev.pocketengine.features.groups.GroupsModule.RemoteInputSource source)throws Exception{return inputs.openGroup(nativeOwner,window,task,request,source);}
    /** In-memory invalidation only; safe at native page disposal, before any queued worker can claim. */
    public synchronized void close(String id){inputs.close(id);harnessConfigs.close(id);Window window=windows.remove(id);if(window!=null){window.closed=true;window.lifecycleRevision++;if(window.gatewayParentAccess!=null)window.gatewayParentAccess.ownerWindowLost();if(window.teamWindow!=null)window.teamWindow.close();}}
    public JSONObject handle(String command,JSONObject input)throws Exception{return handle(null,command,input);}
    public JSONObject handle(Object nativeOwner,String command,JSONObject input)throws Exception{
        if(java.util.Set.of("gateway.reviewParentRun","gateway.reviewParentProfile","gateway.saveProfileTarget","gateway.profileChoices","gateway.resumeParentRun","gateway.parentStatus","gateway.stopParentRun").contains(command))return gatewayParentCommand(nativeOwner,command,input);
        if(java.util.Set.of("gateway.reviewRun","gateway.run").contains(command))return gatewayExecutionCommand(nativeOwner,command,input);
        if(command.equals("gateway.prepareContinuation"))return prepareGatewayContinuation(nativeOwner,input);
        if(command.equals("gateway.cancelPrepared"))return cancelGatewayContinuation(nativeOwner,input);
        if(dev.pocketengine.features.remote.NativeHarnessGatewayOwner.command(command))return gatewayCommand(nativeOwner,command,input);
        if(dev.pocketengine.features.remote.NativeHarnessConfigs.command(command)){JSONObject result=harnessConfigs.handle(nativeOwner,command,input);Window original; synchronized(this){original=windows.get(input.getString("windowId"));}if(original!=null){JSONObject snapshot=result.getJSONObject("snapshot");if(gatewayOwner!=null)snapshot.put("harnessGateway",original.gateway==null?gatewayOwner.summary(original.task):gatewayOwner.snapshot(original.gateway));JSONObject stage=tasks.originalGatewayContinuation(original.task);if(stage!=null)snapshot.put("gatewayContinuation",stage);}return result;}
        if(dev.pocketengine.features.remote.RemoteInputAttachments.command(command))return inputs.handle(nativeOwner,command,input);
        if(command.startsWith("project."))return projectCommand(nativeOwner,command,input);
        java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Set.of("windowId","taskId","reviewId"));
        if(command.equals("continue")){allowed.add("modelSelection");allowed.add("prompt");allowed.add("snapshotId");allowed.add("snapshotVersion");allowed.add("timeoutSeconds");allowed.add("recoveryOwnerAcknowledged");}if(command.equals("captureSnapshot"))allowed.add("paths");if(command.equals("readSnapshot")){allowed.add("snapshotId");allowed.add("path");}if(command.equals("readArtifact"))allowed.add("path");if(command.equals("approve")){allowed.add("requestId");allowed.add("decision");}if(command.equals("answerQuestion")){allowed.add("requestId");allowed.add("requestHash");allowed.add("answers");}
        if(command.equals("listResultFiles")){allowed.add("path");allowed.add("offset");}if(command.equals("selectResultFile"))allowed.add("path");if(command.equals("saveSnapshot")){allowed.add("snapshotId");allowed.add("path");}
        if(command.equals("setAttention"))allowed.add("enabled");
        if(command.equals("saveDraft")){allowed.add("draftId");allowed.add("draftRevision");allowed.add("mode");allowed.add("text");allowed.add("queued");}if(command.equals("withdrawDraft")||command.equals("sendDraft")){allowed.add("draftId");allowed.add("draftRevision");}
        if(command.equals("reviewConversationResult")||command.equals("reviewConversationAttachment")){allowed.add("snapshotId");allowed.add("path");allowed.add("inputAttachmentId");}if(command.equals("confirmConversationResult"))allowed.add("digest");
        java.util.Iterator<String> fields=input.keys();while(fields.hasNext())if(!allowed.contains(fields.next()))throw new SecurityException("原生审阅不接受外部提供的来源或执行身份");
        String id=input.getString("windowId");
        if(command.equals("open"))synchronized(this){if(!id.matches("[a-f0-9-]{36}")||windows.containsKey(id)||windows.size()>=4)throw new SecurityException("请重新打开原任务审阅页");Window opened=new Window(id,input.getString("taskId"),nativeOwner);JSONObject task=tasks.get(opened.task,false);if(task!=null&&task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").equals("team_remote_assignment")){if(nativeOwner==null||foregroundOwner!=nativeOwner)throw new SecurityException("原team远端审批只允许真实前台Activity创建窗口");opened.teamWindow=tasks.openTeamRemoteOwnerWindow();opened.teamWindow.setForeground(true);}windows.put(id,opened);}
        Window window; synchronized(this){window=required(id);if(!window.task.equals(input.getString("taskId"))||window.nativeOwner!=nativeOwner)throw new SecurityException("页面不属于该原任务/Activity");}
        if(command.equals("open")||command.equals("refresh"))return refresh(window);
        if(command.equals("inspectRecovery")){
            synchronized(this){checkReview(window,input.getString("reviewId"));if(window.teamWindow!=null||window.nativeOwner==null||foregroundOwner!=window.nativeOwner)throw new SecurityException("冷会话核对只允许原生前台私人任务页");tasks.checkRemoteRecoveryReadSource(window.source);}
            return refresh(window,true);
        }
        if(window.teamWindow!=null&&(command.equals("continue")||command.equals("captureSnapshot")))throw new SecurityException("团队远端制作必须回原assignment宿主处理续接/贡献冻结；此页只查原job与单次请求");
        if(command.equals("setAttention")){synchronized(this){checkReview(window,input.getString("reviewId"));if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner)throw new SecurityException("只允许原生前台设置本机电脑提醒");tasks.setRemoteAttention(window.task,input.getBoolean("enabled"));}emitTask(window.task);return response(window,false,null);}
        if(command.equals("saveDraft")||command.equals("withdrawDraft")||command.equals("sendDraft"))return draftAction(window,command,input);
        if(command.equals("continue"))return continueSession(window,input);
        if(command.equals("approve"))return approve(window,input);
        if(command.equals("answerQuestion"))return answerQuestion(window,input);
        if(command.equals("readArtifact"))return readArtifact(window,input);
        if(command.equals("captureSnapshot"))return captureSnapshot(window,input);
        if(command.equals("readSnapshot"))return readSnapshot(window,input);
        if(command.equals("listResultFiles"))return listResultFiles(window,input);
        if(command.equals("selectResultFile"))return selectResultFile(window,input);
        if(command.equals("captureSelectedSnapshot"))return captureSelectedSnapshot(window,input);
        if(command.equals("saveSnapshot"))return saveSnapshot(window,input);
        if(command.equals("reviewConversationResult")||command.equals("reviewConversationAttachment"))return reviewConversationResult(window,input,command.equals("reviewConversationAttachment"));
        if(command.equals("confirmConversationResult"))return confirmConversationResult(window,input);
        throw new IllegalArgumentException("未知原生电脑任务操作");
    }
    private RemoteModule module(){EngineModule module=host.module("remote");if(!(module instanceof RemoteModule))throw new IllegalStateException("原电脑执行模块不可用");return (RemoteModule)module;}
    private dev.pocketengine.features.channels.ChannelsModule channels(){EngineModule module=host.module("channels");if(!(module instanceof dev.pocketengine.features.channels.ChannelsModule))throw new IllegalStateException("原消息渠道不可用");return (dev.pocketengine.features.channels.ChannelsModule)module;}
    /** Page observation only. No execution RunControl, new task, or retry authority is created. */
    private synchronized void checkReadCurrent(Window window,long lifecycle,long generation)throws Exception {
        if(required(window.id)!=window||window.closed||window.lifecycleRevision!=lifecycle||window.readGeneration!=generation
                ||window.nativeOwner!=null&&foregroundOwner!=window.nativeOwner)throw new SecurityException("原读取页面已关闭、切后台或被新查询替代；迟到GET没有成为新审阅");
    }
    private static boolean sameReadTarget(JSONObject before,JSONObject after,boolean acceptedJob)throws Exception {
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobOperationId"})if(!before.get(key).equals(after.opt(key)))return false;
        if(before.has("backendSelection")||after.has("backendSelection")){
            HarnessCapabilityObservation.requireSameBackendSelection(before.optJSONObject("backendSelection"),after.optJSONObject("backendSelection"));
            for(String key:new String[]{"profileBinding","environmentId","environmentBinding","worker"})if(!before.get(key).equals(after.opt(key)))return false;
        }
        return before.optString("jobId").equals(after.optString("jobId"))||acceptedJob&&before.optString("jobId").isEmpty();
    }
    private JSONObject refresh(Window window)throws Exception{return refresh(window,false);}
    private JSONObject refresh(Window window,boolean inspectRecovery)throws Exception{
        JSONObject gatewayStage=tasks.originalGatewayContinuation(window.task);if(gatewayStage!=null&&gatewayStage.optBoolean("locallyPrepared")){synchronized(this){window.queryStatus="local_prepared_not_dispatched";window.fresh=false;window.review=null;window.source=tasks.remoteOwnerTarget(window.task);}return response(window,false,null);}

        long lifecycle,generation;synchronized(this){lifecycle=window.lifecycleRevision;generation=++window.readGeneration;checkReadCurrent(window,lifecycle,generation);window.fresh=false;window.review=null;window.queryStatus="querying";window.lastReadAttemptAt=System.currentTimeMillis();}
        JSONObject target=tasks.remoteOwnerTarget(window.task),payload=new JSONObject().put("profileId",target.getString("profileId")).put("serviceBase",target.getString("serviceBase"));String job=target.optString("jobId");
        String capturedBinding;JSONObject result;try{
            capturedBinding=target.has("backendSelection")?target.getString("profileBinding"):module().profileBindingHash(target.getString("profileId"));
            if(!capturedBinding.equals(module().profileBindingHash(target.getString("profileId"))))throw new SecurityException("原后端连接代次已变化，不能改用新配置查询旧任务");payload.put("_profileBinding",capturedBinding);if(target.has("backendSelection"))payload.put("backendSelection",new JSONObject(target.getJSONObject("backendSelection").toString()));
            checkReadCurrent(window,lifecycle,generation);
            if(!sameReadTarget(target,tasks.remoteOwnerTarget(window.task),false))throw new SecurityException("读取前原task/run/job已变化");
            if(target.has("backendSelection")){if(inspectRecovery)throw new SecurityException("此后端没有冷会话续接协议");result=module().readBackendJob(target,()->checkReadCurrent(window,lifecycle,generation));}
            else if(job.isEmpty())result=module().handle("reconcileOperation",payload.put("operationId",target.getString("jobOperationId")));
            else result=module().handle(inspectRecovery?"readSessionRecovery":"getJob",payload.put("jobId",job).put("jobOperationId",target.getString("jobOperationId")));
            // The original HTTP timeout remains bounded. A dispatched GET may still finish;
            // leaving this page invalidates its observation, not the remote execution.
            checkReadCurrent(window,lifecycle,generation);
            if(!capturedBinding.equals(module().profileBindingHash(target.getString("profileId"))))throw new SecurityException("查询期间电脑配置或凭据已变化");
            if(!sameReadTarget(target,tasks.remoteOwnerTarget(window.task),true))throw new SecurityException("读取期间原task/run/job已变化");
        }catch(Exception unavailable){
            synchronized(this){checkReadCurrent(window,lifecycle,generation);window.queryStatus="unconfirmed";}
            JSONObject unchanged=response(window,false,null,lifecycle,generation);
            unchanged.getJSONObject("snapshot").put("queryStatus","unconfirmed").put("reason","未能按原电脑地址核对；没有改用新地址、重提任务或授予续接。请恢复原连接后查询");
            return unchanged;
        }
        checkReadCurrent(window,lifecycle,generation);
        tasks.updateExternal(window.task,result);if(result.has("inputState")&&tasks.get(window.task,false).has("remoteInputScope"))tasks.reconcileRemoteInputs(window.task,result);emitTask(window.task);
        // A committed cursor acknowledges observations only, never an approval/answer or job dispatch.
        if(dev.pocketengine.features.remote.RemoteJobEvents.supported(result)&&!result.optString("id").isEmpty()){
            try{
                JSONObject original=tasks.remoteOwnerTarget(window.task),previous=tasks.originalRemoteEventRead(window.task);
                dev.pocketengine.features.remote.RemoteJobEvents.Page page=module().readOriginalJobEvents(original,capturedBinding,previous,()->checkReadCurrent(window,lifecycle,generation));
                synchronized(this){checkReadCurrent(window,lifecycle,generation);if(!capturedBinding.equals(module().profileBindingHash(original.getString("profileId"))))throw new SecurityException("事件读取期间电脑代次变化");tasks.recordOriginalRemoteEventPage(window.task,page);window.eventReadStatus="not_available".equals(page.view().optString("status"))?"not_available":"confirmed";}
            }catch(Exception unavailable){synchronized(this){checkReadCurrent(window,lifecycle,generation);window.eventReadStatus="unconfirmed";}}
        }else synchronized(this){checkReadCurrent(window,lifecycle,generation);window.eventReadStatus="unsupported";}
        JSONArray pendingSnapshots=tasks.pendingRemoteResultSnapshots(window.task);for(int i=0;i<pendingSnapshots.length();i++){
            checkReadCurrent(window,lifecycle,generation);
            JSONObject pending=pendingSnapshots.getJSONObject(i),source=pending.getJSONObject("source");JSONObject query=new JSONObject(source.toString()).put("operationId",pending.getString("operationId")).put("_profileBinding",capturedBinding);
            try{JSONObject actual=module().reconcileResultSnapshot(query);checkReadCurrent(window,lifecycle,generation);tasks.recordRemoteResultSnapshot(window.task,pending.getString("operationId"),actual);}catch(Exception unavailable){checkReadCurrent(window,lifecycle,generation);/* Original unknown stays query-only, never capture again. */}
        }
        if(pendingSnapshots.length()>0)emitTask(window.task);
        JSONObject current=tasks.remoteOwnerTarget(window.task);boolean same=true;
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobOperationId"})if(!target.get(key).equals(current.opt(key)))same=false;
        if(!job.isEmpty()&&!job.equals(current.optString("jobId")))same=false;
        if(!result.optString("status").equals(current.optString("executorStatus")))same=false;
        synchronized(this){checkReadCurrent(window,lifecycle,generation);if(!capturedBinding.equals(module().profileBindingHash(target.getString("profileId")))){window.queryStatus="changed";window.result=null;window.artifact=null;window.snapshotFile=null;window.projectPage=null;window.projectDiff=null;window.projectReadSource=null;window.projectReadOperation=null;window.projectDigest=null;window.projectReview=null;window.lastSuccessfulReadAt=0;return response(window,false,null,lifecycle,generation);}window.result=same?dev.pocketengine.features.remote.RemoteResultSnapshot.job(result,current):null;window.artifact=null;window.snapshotFile=null;if(window.profileBinding!=null&&!window.profileBinding.equals(capturedBinding)){window.directory=null;window.selectedFiles.clear();}window.profileBinding=capturedBinding;window.queryStatus=same?"confirmed":"changed";if(same){window.lastSuccessfulReadAt=System.currentTimeMillis();window.source=new JSONObject(current.toString());}}
        if(window.teamWindow!=null)prepareTeamReviews(window,current);
        return response(window,same,null,lifecycle,generation);
    }
    private synchronized Window required(String id){Window window=windows.get(id);if(window==null)throw new SecurityException("原生审阅页已关闭，本次没有派发；请重新打开核对");return window;}
    private void checkReview(Window window,String review)throws Exception{
        if(required(window.id)!=window||!window.fresh||window.source==null||!review.equals(window.review)||android.os.SystemClock.uptimeMillis()-window.reviewedAt>60_000L)throw new SecurityException("本次原生审阅已过期，请查询原任务后重新确认");
        if(window.teamWindow!=null&&foregroundOwner!=window.nativeOwner)throw new SecurityException("原审批Activity已不在前台");
        if(window.profileBinding==null||!window.profileBinding.equals(module().profileBindingHash(window.source.getString("profileId"))))throw new SecurityException("审阅后的电脑配置或凭据已变化，请查询原任务后重新确认；未派发旧决定");
    }
    @FunctionalInterface public interface ClipboardDraftReader {android.content.ClipData read(ClipboardDraftReadAccess approval)throws Exception;}
    /** Private native owner read permit, process-local and one-use. Not a task/run grant. */
    public static final class ClipboardDraftReadAccess {
        private final RemoteOwnerWorkflows host;private final Window window;private final String review,binding,draft,mode,scope;
        private final long lifecycle,generation,revision;private volatile boolean closed;private boolean claimed;
        private ClipboardDraftReadAccess(RemoteOwnerWorkflows host,Window window,JSONObject input,JSONObject scope)throws Exception {
            this.host=host;this.window=window;review=window.review;binding=window.profileBinding;lifecycle=window.lifecycleRevision;generation=window.readGeneration;
            draft=input.getString("draftId");mode=input.getString("mode");revision=input.getLong("draftRevision");this.scope=scope.toString();
        }
        public void checkCurrent()throws Exception {if(closed)throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();host.checkClipboardRead(this);if(closed)throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();}
        public void claimNativeRead(Object actualOwner)throws Exception {
            checkCurrent();if(actualOwner!=window.nativeOwner)throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();
            synchronized(this){if(closed||claimed)throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();claimed=true;}
            checkCurrent();
        }
        private synchronized boolean claimed(){return claimed;}
    }
    /** Native Compose caller only, deliberately absent from the JSON command dispatcher.
     * Clipboard/service work is always outside both host and TaskStore monitors. */
    public String clipboardDraftText(Object nativeOwner,JSONObject input,ClipboardDraftReader reader)throws Exception {
        if(Thread.holdsLock(this)||Thread.holdsLock(tasks))throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();
        ClipboardDraftReadAccess approval;
        try{synchronized(this){
            if(input.length()!=6)throw new SecurityException("原读取字段不符");
            for(var key=input.keys();key.hasNext();)if(!java.util.Set.of("windowId","taskId","reviewId","draftId","draftRevision","mode").contains(key.next()))throw new SecurityException("不是原读取目标");
            Window window=required(input.getString("windowId"));checkReview(window,input.getString("reviewId"));
            if(nativeOwner==null||window.nativeOwner!=nativeOwner||foregroundOwner!=nativeOwner||window.teamWindow!=null||!window.task.equals(input.getString("taskId"))||reader==null||window.review.equals(window.clipboardReadReview))throw new SecurityException("原前台读取已失效或已使用");
            JSONObject scope=clipboardDraftTarget(window,input.getString("draftId"),input.getLong("draftRevision"),input.getString("mode"),input.opt("draftRevision"));
            approval=new ClipboardDraftReadAccess(this,window,input,scope);window.clipboardReadReview=window.review;
        }}catch(Exception invalid){throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();}
        try{
            android.content.ClipData sample;
            try{sample=reader.read(approval);}catch(dev.pocketengine.features.remote.RemoteDraftClipboard.Failure safe){throw safe;}catch(Exception unavailable){throw dev.pocketengine.features.remote.RemoteDraftClipboard.unavailableFailure();}
            if(!approval.claimed())throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();
            String text=dev.pocketengine.features.remote.RemoteDraftClipboard.text(sample);
            try{approval.checkCurrent();}catch(Exception changed){throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure();}
            return text;
        }finally{approval.closed=true;}
    }
    private synchronized void checkClipboardRead(ClipboardDraftReadAccess approval)throws Exception {
        Window window=approval.window;checkReadCurrent(window,approval.lifecycle,approval.generation);checkReview(window,approval.review);
        if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.teamWindow!=null||!approval.binding.equals(window.profileBinding)||!approval.review.equals(window.clipboardReadReview))throw new SecurityException("原读取身份已变化");
        JSONObject actual=clipboardDraftTarget(window,approval.draft,approval.revision,approval.mode,approval.revision);
        if(!dev.pocketengine.features.remote.RemoteTaskDrafts.same(new JSONObject(approval.scope),actual))throw new SecurityException("原草稿目标已变化");
    }
    private JSONObject clipboardDraftTarget(Window window,String draft,long revision,String mode,Object revisionValue)throws Exception {
        if(!draft.matches("[a-f0-9-]{36}")||revision<0||!(revisionValue instanceof Integer||revisionValue instanceof Long))throw new SecurityException("原草稿游标无效");
        synchronized(tasks){
            JSONObject current=tasks.remoteOwnerTarget(window.task),task=tasks.get(window.task,false),origin=task.optJSONObject("origin");
            if(task.has("teamRunId")||task.has("memberId")||origin!=null&&origin.optString("kind").startsWith("team")||current.opt("cancelRequested")!=Boolean.FALSE||task.optString("status").matches("stopping|cancelled|completed"))throw new SecurityException("不是当前私人草稿目标");
            if(task.has("parentTaskId")){tasks.requirePrivateRemoteChild(window.task);JSONObject parent=tasks.get(task.getString("parentTaskId"),false);if(parent==null||parent.optBoolean("cancelRequested")||parent.optString("status").matches("stopping|cancelled|completed"))throw new SecurityException("原私人父任务已停止");}
            if(current.opt("protocolVerified")!=Boolean.TRUE)throw new SecurityException("原协议身份未确认");
            for(String key:new String[]{"taskId","runId","profileId","serviceBase","jobId","jobOperationId","environmentId","worker","protocol","threadId"})if(!(current.opt(key) instanceof String)||current.getString(key).trim().isEmpty())throw new SecurityException("原会话字段未确认");
            JSONObject scope=dev.pocketengine.features.remote.RemoteTaskDrafts.scope(current,window.profileBinding,mode);
            if(!dev.pocketengine.features.remote.RemoteTaskDrafts.same(scope,dev.pocketengine.features.remote.RemoteTaskDrafts.scope(window.source,window.profileBinding,mode)))throw new SecurityException("原会话已换代");
            JSONArray rows=tasks.originalRemoteDrafts(window.task);JSONObject found=null;for(int i=0;i<rows.length();i++)if(draft.equals(rows.getJSONObject(i).optString("id")))found=rows.getJSONObject(i);
            if(found==null){if(revision!=0)throw new SecurityException("原草稿不存在");}
            else if(found.getLong("revision")!=revision||!found.optString("state").matches("draft|queued")||!mode.equals(found.optString("mode"))||!dev.pocketengine.features.remote.RemoteTaskDrafts.same(scope,found.getJSONObject("scope")))throw new SecurityException("原草稿已变化或领取");
            return scope;
        }
    }
    private JSONObject draftAction(Window window,String command,JSONObject input)throws Exception {
        TaskStore.RemoteDraftDispatch dispatch=null;String binding;long lifecycle;JSONObject local;
        synchronized(this){
            checkReview(window,input.getString("reviewId"));if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.teamWindow!=null)throw new SecurityException("草稿只能在原生前台私人会话管理，团队仍回原assignment");
            binding=window.profileBinding;lifecycle=window.lifecycleRevision;
            if(command.equals("saveDraft"))local=tasks.saveRemoteDraft(window.source,binding,input.getString("draftId"),input.getLong("draftRevision"),input.getString("mode"),input.getString("text"),input.getBoolean("queued"));
            else if(command.equals("withdrawDraft"))local=tasks.withdrawRemoteDraft(window.task,input.getString("draftId"),input.getLong("draftRevision"));
            else{dispatch=tasks.prepareRemoteDraftDispatch(window.source,binding,input.getString("draftId"),input.getLong("draftRevision"));local=new JSONObject().put("operationId",dispatch.operationId());window.fresh=false;window.review=null;}
        }
        if(dispatch==null){emitTask(window.task);return response(window,false,local);}
        JSONObject result;
        synchronized(this){if(required(window.id)!=window||foregroundOwner!=window.nativeOwner||window.lifecycleRevision!=lifecycle)throw new SecurityException("原草稿页面已关闭，已登记条目仅查询原操作");module().requireProfileBinding(dispatch.profileId(),binding);}
        if(dispatch.mode().equals("steer"))result=host.sendRemoteDraftMessage(dispatch);
        else{
            JSONObject payload=tasks.claimRemoteDraftContinuation(dispatch).put("_profileBinding",binding);emitTask(window.task);
            try{result=module().handle("submitJob",payload);}catch(Exception unknown){result=new JSONObject().put("operationId",dispatch.operationId()).put("profileId",dispatch.profileId()).put("serviceBase",dispatch.serviceBase()).put("status","unknown").put("message","原队列条目续接未确认，只查询原登记操作；未重发");}
            tasks.updateExternal(window.task,result);
        }
        emitTask(window.task);return responseIfOpen(window,result);
    }
    private JSONObject continueSession(Window window,JSONObject input)throws Exception{
        JSONObject selected=null;String capturedBinding;
        synchronized(this){checkReview(window,input.getString("reviewId"));capturedBinding=window.profileBinding;if(input.has("snapshotId")||input.has("snapshotVersion")){selected=tasks.remoteResultSnapshot(window.task,input.getString("snapshotId"));if(!selected.getString("snapshotVersion").equals(input.getString("snapshotVersion"))||!window.source.getString("jobId").equals(selected.getString("jobId")))throw new SecurityException("选择的不是原任务当前job的同一快照版本");}}
        if(selected!=null){JSONObject source=selected.getJSONObject("source"),check=module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",selected.getString("snapshotId")).put("snapshotVersion",selected.getString("snapshotVersion")).put("verifyCurrent",true).put("_profileBinding",capturedBinding));if(!check.optBoolean("matchesCurrent")||!selected.getString("snapshotId").equals(check.optString("snapshotId"))||!selected.getString("snapshotVersion").equals(check.optString("snapshotVersion")))throw new SecurityException("工作区已不是所审查快照版本，未登记或提交新续接");}
        JSONObject operation,payload; synchronized(this){checkReview(window,input.getString("reviewId"));
            ModelSelectionApproval choice=null;if(input.has("modelSelection")){if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.teamWindow!=null||dev.pocketengine.features.remote.RemoteColdRecovery.source(window.source))throw new SecurityException("本次模型/档位只可由前台主人对原已结算私人会话选择，不导入冷恢复/团队或网关范围");choice=new ModelSelectionApproval(this,window,input.getJSONObject("modelSelection"));}
            synchronized(tasks){JSONObject cold=dev.pocketengine.features.remote.RemoteColdRecovery.source(window.source)?new JSONObject().put("timeoutSeconds",input.opt("timeoutSeconds")).put("recoveryOwnerAcknowledged",input.opt("recoveryOwnerAcknowledged")):null;operation=choice==null?tasks.prepareRemoteContinuation(window.source,input.getString("prompt"),selected,cold):tasks.prepareRemoteModelContinuation(choice,input.getString("prompt"),selected);payload=tasks.claimRemoteContinuation(window.task,operation.getString("operationId")).put("_profileBinding",capturedBinding);}
            window.result=null;window.artifact=null;window.snapshotFile=null;window.fresh=false;window.review=null;
        }
        emitTask(window.task);JSONObject result;
        try{result=module().handle("submitJob",payload);}catch(Exception unknown){result=new JSONObject().put("operationId",payload.getString("operationId")).put("profileId",payload.getString("profileId")).put("serviceBase",payload.getString("serviceBase")).put("status","unknown").put("message","续接回执未确认；只查询原登记操作，未再次提交");}
        tasks.updateExternal(window.task,result);emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation.getString("operationId")));
    }
    private JSONObject approve(Window window,JSONObject input)throws Exception{
        if(window.teamWindow!=null)return approveTeam(window,input);
        JSONObject claim,source;String capturedBinding;TeamMemberInteractionApproval memberApproval;String request=input.getString("requestId"),decision=input.getString("decision");boolean backend;long lifecycle;
        synchronized(this){checkReview(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());capturedBinding=window.profileBinding;checkApprovalContext(source,request);backend=source.has("backendSelection");lifecycle=window.lifecycleRevision;
            if(backend){requirePrivateBackendOwner(window,source);JSONObject permission=HarnessCapabilityObservation.checkedBackendApproval(source,pendingApproval(source,request));if(decision.equals("accept")&&!permission.getBoolean("canAccept"))throw new SecurityException("此原权限请求没有唯一allow_once选项");}
            memberApproval=isTeamMember(tasks.get(window.task,false))?new TeamMemberInteractionApproval(this,window,"approval",request,TaskStore.capabilityFingerprint(pendingApproval(source,request))):null;window.fresh=false;window.review=null;claim=tasks.claimRemoteApproval(source,request,decision,memberApproval);}
        if(claim.optBoolean("knownDecision"))return refresh(window); // Original GET only; never send a second decision.
        JSONObject payload=new JSONObject().put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("jobId",source.getString("jobId")).put("jobOperationId",source.getString("jobOperationId")).put("approvalRequestId",request).put("decision",decision).put("_profileBinding",capturedBinding);
        if(backend){JSONObject permission=pendingApproval(source,request);for(String key:new String[]{"sessionId","promptRequestId","requestHash"})payload.put(key,permission.get(key));for(String key:new String[]{"backendSelection","profileBinding","environmentId","environmentBinding","worker","readOnly"})payload.put(key,source.get(key));payload.put("model",source.getJSONObject("backendSelection").getString("model"));}
        try{JSONObject result=backend?module().approveBackendJob(payload,()->requireBackendApprovalCurrent(window,lifecycle,source,request)):memberApproval==null?module().handle("approveJob",payload):module().approveAssignmentJob(payload,()->{memberApproval.checkCoordinatorCurrent();tasks.beforeTeamMemberApprovalDispatch(window.task,claim.getString("operationId"),memberApproval);});tasks.recordRemoteApprovalResult(window.task,claim.getString("operationId"),result);}catch(Exception unknown){/* Durable sending/unknown claim is query-only, including after page exit/restart. */}
        emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,claim.getString("operationId")));
    }
    private void requirePrivateBackendOwner(Window window,JSONObject source)throws Exception {
        if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.teamWindow!=null)throw new SecurityException("Gemini权限须由原前台私人任务窗口核对");
        JSONObject task=tasks.get(window.task,false);if(task==null||task.optJSONObject("origin")!=null||!task.optString("parentTaskId").isEmpty()||task.has("teamReservationPool")||task.has("teamProjectRoundOrigin")||task.has("teamRunId")||task.optBoolean("cancelRequested"))throw new SecurityException("此后端不接受团队、外部来源或已撤回的任务权限");
        if(!source.getString("profileBinding").equals(module().profileBindingHash(source.getString("profileId"))))throw new SecurityException("原后端连接代次已变化");
    }
    private synchronized void requireBackendApprovalCurrent(Window window,long lifecycle,JSONObject source,String request)throws Exception {
        if(windows.get(window.id)!=window||window.closed||window.lifecycleRevision!=lifecycle)throw new SecurityException("原权限窗口已关闭");requirePrivateBackendOwner(window,source);
        JSONObject current=tasks.remoteOwnerTarget(window.task);if(!sameReadTarget(source,current,false)||!TaskStore.capabilityFingerprint(pendingApproval(source,request)).equals(TaskStore.capabilityFingerprint(pendingApproval(current,request))))throw new SecurityException("原任务或完整权限请求已变化");
        HarnessCapabilityObservation.checkedBackendApproval(current,pendingApproval(current,request));
    }
    private JSONObject answerQuestion(Window window,JSONObject input)throws Exception {
        JSONObject claim,source;String binding;long revision;TeamMemberInteractionApproval memberApproval;
        synchronized(this){checkReview(window,input.getString("reviewId"));if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.teamWindow!=null)throw new SecurityException("正常回答须原生前台窗口；制作assignment不能借普通成员入口继续");source=new JSONObject(window.source.toString());if(source.has("backendSelection")||HarnessCapabilityObservation.GEMINI_PROTOCOL.equals(source.optString("protocol")))throw new SecurityException("此Gemini后端未支持结构化问题，不转换为其它协议");binding=window.profileBinding;revision=window.lifecycleRevision;memberApproval=isTeamMember(tasks.get(window.task,false))?new TeamMemberInteractionApproval(this,window,"question",input.getString("requestId"),input.getString("requestHash")):null;claim=tasks.claimRemoteQuestionAnswer(source,input.getString("requestId"),input.getString("requestHash"),input.getJSONObject("answers"),memberApproval);window.fresh=false;window.review=null;}
        if(claim.optBoolean("knownAnswer"))return refresh(window);String operation=claim.getString("operationId");JSONObject payload=new JSONObject(source.toString());JSONObject body=claim.getJSONObject("request");for(java.util.Iterator<String> keys=body.keys();keys.hasNext();){String key=keys.next();payload.put(key,body.get(key));}payload.put("_profileBinding",binding);
        try{JSONObject actual=module().answerQuestionJob(payload,()->{synchronized(RemoteOwnerWorkflows.this){if(windows.get(window.id)!=window||foregroundOwner!=window.nativeOwner||revision!=window.lifecycleRevision)throw new SecurityException("原正常问答窗口已关闭或切后台");if(memberApproval!=null)memberApproval.checkCoordinatorCurrent();tasks.beforeRemoteQuestionAnswerDispatch(window.task,operation,memberApproval);}});tasks.recordRemoteQuestionAnswerResult(window.task,operation,actual);}catch(Exception unconfirmed){tasks.remoteQuestionAnswerUnconfirmed(window.task,operation);}
        emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation));
    }
    private void prepareTeamReviews(Window window,JSONObject source)throws Exception {
        synchronized(this){window.teamReviews.clear();JSONArray pending=source.optJSONArray("pendingApprovals");for(int i=0;pending!=null&&i<pending.length();i++){JSONObject request=pending.getJSONObject(i);try{TaskStore.TeamRemoteOwnerReview review=tasks.reviewTeamRemotePending(window.teamWindow,window.task,source.getString("runId"),source.getInt("coordinatorEpoch"),request.getString("requestId"),TaskStore.capabilityFingerprint(request));window.teamReviews.put(request.getString("requestId"),review);}catch(SecurityException stale){/* Data remains visible; no owner authority is silently reconstructed. */}}}
    }
    private JSONObject approveTeam(Window window,JSONObject input)throws Exception {
        TaskStore.TeamRemoteClaim claim;JSONObject source;String binding;String decision=input.getString("decision");if(!decision.matches("accept|decline"))throw new SecurityException("此团队scope只提供单次允许或拒绝；停止原job请回原团队");
        synchronized(this){checkReview(window,input.getString("reviewId"));TaskStore.TeamRemoteOwnerReview review=window.teamReviews.get(input.getString("requestId"));if(review==null)throw new SecurityException("原team/parent/请求scope已变化或不在前台，请重新查询原任务");JSONObject reviewed=review.view();source=new JSONObject(window.source.toString());binding=window.profileBinding;if(!binding.equals(reviewed.getJSONObject("scope").getString("profileGeneration")))throw new SecurityException("原制作的电脑凭据generation已变，旧scope不能授予新审批；回原团队重新审阅");claim=tasks.claimTeamRemoteOwnerApproval(review,TaskStore.capabilityFingerprint(reviewed),decision.equals("accept")?TaskStore.TeamRemoteOwnerDecision.APPROVE_ONCE:TaskStore.TeamRemoteOwnerDecision.REJECT);window.fresh=false;window.review=null;window.teamReviews.clear();}
        if(!claim.canDispatch())return refresh(window);JSONObject payload=new JSONObject(source.toString()).put("approvalRequestId",input.getString("requestId")).put("decision",claim.view().getString("decision")).put("_profileBinding",binding);
        try{JSONObject actual=module().approveAssignmentJob(payload,()->tasks.beforeTeamRemoteOwnerDispatch(claim));tasks.recordTeamRemoteReceipt(claim,actual);}catch(Exception unknown){/* Original durable claim is query-only; never resend or wake a model. */}emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,claim.view().getString("operationId")));
    }
    private JSONObject readArtifact(Window window,JSONObject input)throws Exception {
        JSONObject source;String capturedBinding;synchronized(this){checkReview(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());capturedBinding=window.profileBinding;}
        String path=input.getString("path");if(window.teamWindow!=null){JSONArray files=tasks.originalTeamRemoteContext(window.task).view().getJSONArray("files");boolean allowed=false;for(int n=0;n<files.length();n++)if(path.equals(files.getJSONObject(n).getString("path")))allowed=true;if(!allowed)throw new SecurityException("只能查看原selected-file scope内文件");}if(path.trim().isEmpty()||path.length()>2048||path.indexOf('\0')>=0)throw new IllegalArgumentException("请选择原工作区内的相对文件路径");
        JSONObject bytes=module().handle("readArtifact",new JSONObject().put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("jobId",source.getString("jobId")).put("jobOperationId",source.getString("jobOperationId")).put("path",path).put("_profileBinding",capturedBinding));
        JSONObject actual=dev.pocketengine.features.remote.RemoteResultSnapshot.artifact(bytes,source,path);
        synchronized(this){checkReview(window,input.getString("reviewId"));JSONObject current=tasks.remoteOwnerTarget(window.task);for(String key:new String[]{"jobId","jobOperationId","serviceBase","environmentId"})if(!source.optString(key).equals(current.optString(key)))throw new SecurityException("文件读取时原任务已改变，请重新核对");window.artifact=actual;}
        return response(window,true,null);
    }
    private JSONObject captureSnapshot(Window window,JSONObject input)throws Exception {
        JSONObject operation,source;String capturedBinding;JSONArray paths=dev.pocketengine.features.remote.RemoteResultSnapshots.paths(input.getJSONArray("paths"));
        synchronized(this){checkReview(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());capturedBinding=window.profileBinding;operation=tasks.claimRemoteResultSnapshot(source,paths);window.fresh=false;window.review=null;window.snapshotFile=null;}
        emitTask(window.task);JSONObject request=new JSONObject(source.toString()).put("operationId",operation.getString("operationId")).put("paths",paths).put("_profileBinding",capturedBinding),result;
        try{result=module().captureResultSnapshot(request);}catch(Exception unavailable){result=new JSONObject().put("status","unknown").put("operationId",operation.getString("operationId"));for(String key:new String[]{"profileId","serviceBase","jobId","jobOperationId","environmentId","worker"})result.put(key,source.get(key));}
        tasks.recordRemoteResultSnapshot(window.task,operation.getString("operationId"),result);emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation.getString("operationId")));
    }
    private JSONObject readSnapshot(Window window,JSONObject input)throws Exception {
        JSONObject selected;String capturedBinding;synchronized(this){checkReview(window,input.getString("reviewId"));selected=tasks.remoteResultSnapshot(window.task,input.getString("snapshotId"));capturedBinding=window.profileBinding;}
        String path=input.getString("path");boolean allowed=false;JSONArray manifest=selected.getJSONArray("files");for(int i=0;i<manifest.length();i++)if(path.equals(manifest.getJSONObject(i).getString("path")))allowed=true;if(!allowed)throw new SecurityException("文件不在原快照明确选择范围，未向远端查询");JSONObject source=selected.getJSONObject("source"),bytes=module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",selected.getString("snapshotId")).put("snapshotVersion",selected.getString("snapshotVersion")).put("path",path).put("_profileBinding",capturedBinding));
        JSONObject file=dev.pocketengine.features.remote.RemoteResultSnapshots.file(bytes,selected,path);synchronized(this){checkReview(window,input.getString("reviewId"));window.snapshotFile=file;}return response(window,true,null);
    }
    private void checkResultRead(Window window,String review)throws Exception {
        checkReview(window,review);
        if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.closed||window.teamWindow!=null)throw new SecurityException("请从真实前台私人任务页面核对成果");
    }
    private dev.pocketengine.core.RunControl resultReadControl(Window window,String profile,String binding,long revision)throws Exception {
        dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();
        control.retainAuthorityCheck(()->{
            // No SQL or nested control.check: AttachmentBook may hold its own monitor here.
            if(window.closed||window.lifecycleRevision!=revision||foregroundOwner!=window.nativeOwner||window.nativeOwner==null)throw new SecurityException("成果页面已关闭或离开前台");
            if(!binding.equals(module().profileBindingHash(profile)))throw new SecurityException("成果读取期间电脑配置已改变");
        });return control;
    }
    private JSONObject listResultFiles(Window window,JSONObject input)throws Exception {
        JSONObject source;String binding,version="";int offset=input.optInt("offset",0);String path=input.optString("path","");
        synchronized(this){checkResultRead(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());binding=window.profileBinding;
            if(offset>0){JSONObject directory=window.directory;if(directory==null||!path.equals(directory.optString("path"))||directory.isNull("nextOffset")||offset!=directory.optInt("nextOffset",-1))throw new SecurityException("只能沿刚核对的目录页继续");version=directory.getString("directoryVersion");}}
        JSONObject actual=module().readResultDirectory(new JSONObject(source.toString()).put("_profileBinding",binding),path,offset,version);
        synchronized(this){checkResultRead(window,input.getString("reviewId"));window.directory=actual;}
        return response(window,true,null);
    }
    private JSONObject selectResultFile(Window window,JSONObject input)throws Exception {
        JSONObject source;String binding,path=input.getString("path"),review=input.getString("reviewId");
        synchronized(this){checkResultRead(window,review);if(window.selectedFiles.remove(path)!=null)return response(window,true,null);
            boolean selectable=false;JSONArray entries=window.directory==null?null:window.directory.optJSONArray("files");for(int i=0;entries!=null&&i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(path.equals(entry.optString("path"))&&entry.optBoolean("selectable"))selectable=true;}
            if(!selectable||window.selectedFiles.size()>=8)throw new SecurityException("请在当前目录明确选择最多8个可保存文件");source=new JSONObject(window.source.toString());for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"})if(!source.getString(key).equals(window.directory.optString(key)))throw new SecurityException("目录不属于当前原job，请重新核对");binding=window.profileBinding;}
        JSONObject bytes=module().handle("readArtifact",new JSONObject(source.toString()).put("path",path).put("_profileBinding",binding));
        dev.pocketengine.features.remote.RemoteResultSnapshot.artifact(bytes,source,path);
        if(!bytes.optString("fileVersion").matches("[a-f0-9]{64}"))throw new SecurityException("原文件没有真实版本回执");
        JSONObject selected=new JSONObject().put("path",path).put("bytes",bytes.getLong("bytes")).put("sha256",bytes.getString("sha256")).put("fileVersion",bytes.getString("fileVersion"));
        synchronized(this){checkResultRead(window,review);long total=selected.getLong("bytes");for(JSONObject file:window.selectedFiles.values())total=Math.addExact(total,file.getLong("bytes"));if(window.selectedFiles.size()>=8||total>524288)throw new SecurityException("本次所选文件超过8个或512KiB");window.selectedFiles.put(path,selected);}
        return response(window,true,null);
    }
    private JSONObject captureSelectedSnapshot(Window window,JSONObject input)throws Exception {
        JSONObject operation,source;String binding;JSONArray paths=new JSONArray(),expected=new JSONArray();long revision;
        synchronized(this){checkResultRead(window,input.getString("reviewId"));for(JSONObject file:window.selectedFiles.values()){paths.put(file.getString("path"));expected.put(new JSONObject(file.toString()));}
            source=new JSONObject(window.source.toString());binding=window.profileBinding;revision=window.lifecycleRevision;operation=tasks.claimRemoteResultSnapshot(source,paths,expected);window.fresh=false;window.review=null;}
        JSONObject payload=new JSONObject(source.toString()).put("operationId",operation.getString("operationId")).put("paths",paths).put("expectedFiles",expected).put("_profileBinding",binding),actual;
        dev.pocketengine.core.RunControl current=resultReadControl(window,source.getString("profileId"),binding,revision);
        try{actual=module().captureResultSnapshot(payload,()->current.check());}
        catch(Exception unavailable){actual=new JSONObject().put("status","unknown").put("operationId",operation.getString("operationId"));for(String key:new String[]{"profileId","serviceBase","jobId","jobOperationId","environmentId","worker"})actual.put(key,source.get(key));}
        tasks.recordRemoteResultSnapshot(window.task,operation.getString("operationId"),actual);emitTask(window.task);
        synchronized(this){window.selectedFiles.clear();}return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation.getString("operationId")));
    }
    private JSONObject saveSnapshot(Window window,JSONObject input)throws Exception {
        String binding,profile;long revision;String snapshot=input.getString("snapshotId"),path=input.getString("path");
        synchronized(this){checkResultRead(window,input.getString("reviewId"));if(window.savingResult)throw new IllegalStateException("正在保存本次原件，请等待或离开页面停止");window.savingResult=true;binding=window.profileBinding;profile=window.source.getString("profileId");revision=window.lifecycleRevision;}
        try{dev.pocketengine.core.RunControl current=resultReadControl(window,profile,binding,revision);
        dev.pocketengine.features.remote.RemoteResultFiles.Saved saved=dev.pocketengine.features.remote.RemoteResultFiles.save(tasks,module().resultAttachmentBook(),window.task,snapshot,path,
            (original,selected)->{JSONObject source=original.getJSONObject("source");return module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",original.getString("snapshotId")).put("snapshotVersion",original.getString("snapshotVersion")).put("path",selected).put("_profileBinding",binding));},current);
        current.check();tasks.recordRemoteSnapshotAttachment(window.task,saved);emitTask(window.task);return response(window,true,null);
        }finally{synchronized(this){window.savingResult=false;}}
    }
    private JSONObject reviewConversationResult(Window window,JSONObject input,boolean attachment)throws Exception {
        String binding;long lifecycle;JSONObject target;
        synchronized(this){checkResultRead(window,input.getString("reviewId"));if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner)throw new SecurityException("请在前台核对原会话收件人与完整成果正文");binding=window.profileBinding;lifecycle=window.lifecycleRevision;target=tasks.remoteOwnerTarget(window.task);}
        JSONObject inputScope=tasks.originalRemoteInputScope(window.task),selected=null;JSONArray inputs=inputScope.getJSONArray("files");String inputId=input.getString("inputAttachmentId");
        for(int i=0;i<inputs.length();i++)if(inputId.equals(inputs.getJSONObject(i).getString("attachmentId")))selected=inputs.getJSONObject(i);
        if(selected==null)throw new SecurityException("此原任务没有显选该渠道输入，不能推断回发对象");
        var channel=channels();var originalSource=channel.capturePrivateRemoteInput(inputId,selected.getString("sha256"));
        JSONObject snapshot=tasks.remoteResultSnapshot(window.task,input.getString("snapshotId"));
        if(!inputScope.getString("jobOperationId").equals(snapshot.getString("jobOperationId"))||!target.getString("jobId").equals(snapshot.getString("jobId")))throw new SecurityException("此成果不是这次显选输入的原job快照");
        JSONObject saved=null;JSONArray savedFiles=tasks.remoteSnapshotAttachments(window.task);
        for(int i=0;i<savedFiles.length();i++){JSONObject item=savedFiles.getJSONObject(i);if(item.getString("snapshotId").equals(snapshot.getString("snapshotId"))&&item.getString("path").equals(input.getString("path")))saved=item;}
        if(saved==null)throw new SecurityException("请先将此原快照的准确文件保存为本机成果，再核对回发");
        JSONObject local=saved.getJSONObject("attachment");long size=saved.getLong("bytes");if(size<0||size>(attachment?dev.pocketengine.features.channels.ChannelOutboundMediaFormats.MAX_BYTES:12000)||!attachment&&size==0)throw new SecurityException(attachment?"原会话附件最多8MiB，未分割或隐式压缩":"本入口只回发不超过12000字节、完整显示的UTF-8文字成果");
        dev.pocketengine.core.RunControl read=resultReadControl(window,target.getString("profileId"),binding,lifecycle);byte[] bytes=module().resultAttachmentBook().readVerifiedSnapshot(local.getString("id"),saved.getString("sha256"),read);String text;JSONObject media=null;
        try{
            if(bytes.length!=size)throw new SecurityException("原成果字节数变化");
            if(attachment){media=dev.pocketengine.features.channels.ChannelOutboundMediaFormats.describe(bytes,local.getString("name"));text="远端冻结成果附件 · "+local.getString("name")+" · "+size+" bytes";}
            else {String body=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            for(int n=0;n<body.length();n++)if(body.charAt(n)<32&&body.charAt(n)!='\n'&&body.charAt(n)!='\r'&&body.charAt(n)!='\t')throw new SecurityException("此文件含非文字控制字节，未当作消息回发");
            text="远端任务成果 · "+saved.getString("path")+"\n所选工作区冻结内容，未核作者；不是整个项目。\n\n"+body;
            if(body.trim().isEmpty()||text.length()>4000)throw new SecurityException("完整文字超过此原会话4000字上限；未截断、部分回发或把文件名当成果");}
        }catch(java.nio.charset.CharacterCodingException binary){throw new SecurityException("此成果不是完整UTF-8文字；请使用本机原文件查看，不发送二进制或替代文字",binary);}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
        originalSource.checkCurrent();read.check();JSONObject currentSnapshot=tasks.remoteResultSnapshot(window.task,snapshot.getString("snapshotId"));if(!TaskStore.capabilityFingerprint(snapshot).equals(TaskStore.capabilityFingerprint(currentSnapshot)))throw new SecurityException("原成果登记已变化");
        JSONObject targetIdentity=new JSONObject();for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker","protocol","threadId","sessionId","promptRequestId"})if(target.has(key))targetIdentity.put(key,target.get(key));
        JSONObject exact=new JSONObject().put("taskId",window.task).put("profileId",target.getString("profileId")).put("profileGeneration",binding).put("originalTarget",targetIdentity)
                .put("inputSource",originalSource.view()).put("snapshotId",snapshot.getString("snapshotId")).put("snapshotVersion",snapshot.getString("snapshotVersion"))
                .put("path",saved.getString("path")).put("sha256",saved.getString("sha256")).put("bytes",size).put("fileVersion",saved.getString("fileVersion"))
                .put("attachmentId",local.getString("id")).put("text",text).put("resultKind","complete_utf8_file_as_text_message_not_binary_attachment").put("authorshipVerified",false).put("automaticSending",false);
        if(attachment){exact.put("resultKind","exact_binary_attachment_not_text_substitution").put("media",media);}
        // Original IDs and exact frozen content define one outbox identity; expiry/lifecycle never rekey a send.
        JSONObject stable=new JSONObject().put("taskId",window.task);for(String key:new String[]{"snapshotId","snapshotVersion","path","sha256","fileVersion"})stable.put(key,exact.get(key));
        JSONObject destination=new JSONObject(),inputOrigin=exact.getJSONObject("inputSource");for(String key:new String[]{"eventId","profileId","messageId","senderId","transportEpoch","bindingEpoch","attachmentId","sha256","itemIndex"})destination.put(key,inputOrigin.get(key));stable.put("destination",destination);
        if(attachment)stable.put("deliveryFormat","exact_binary_attachment_v1");
        String stableHash=TaskStore.capabilityFingerprint(stable);exact.put("deliveryScopeHash",stableHash).put("deliveryId",UUID.nameUUIDFromBytes(("pocketworld:original-channel-remote-result:v1\n"+stableHash).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        synchronized(this){if(required(window.id)!=window||window.closed||window.nativeOwner!=foregroundOwner||window.lifecycleRevision!=lifecycle||!binding.equals(module().profileBindingHash(target.getString("profileId")))||!sameReadTarget(target,tasks.remoteOwnerTarget(window.task),false))throw new SecurityException("成果回发审阅期间页面或原目标变化");window.conversationResult=exact;window.conversationSource=originalSource;window.conversationDigest=TaskStore.capabilityFingerprint(exact);window.conversationExpiresAt=System.currentTimeMillis()+60_000L;}
        return response(window,window.fresh,null);
    }
    private JSONObject confirmConversationResult(Window window,JSONObject input)throws Exception {
        ConversationResultApproval approval;synchronized(this){if(window.conversationResult==null||window.conversationSource==null||window.conversationDigest==null||!window.conversationDigest.equals(input.getString("digest")))throw new SecurityException("请重新核对原收件人与准确成果内容");approval=new ConversationResultApproval(this,window,channels());}
        approval.checkCurrent();JSONObject queued=channels().queueRemoteResult(approval);
        synchronized(this){window.conversationDigest=null;window.conversationSource=null;window.conversationResult=null;}
        return response(window,window.fresh,null);
    }
    private static JSONObject pendingApproval(JSONObject source,String id)throws Exception {JSONArray pending=source.optJSONArray("pendingApprovals");for(int i=0;pending!=null&&i<pending.length();i++)if(id.equals(pending.getJSONObject(i).optString("requestId")))return pending.getJSONObject(i);throw new SecurityException("原审批请求已消失");}
    private void checkApprovalContext(JSONObject source,String request)throws Exception{
        JSONObject approval=null;JSONArray entries=source.optJSONArray("pendingApprovals");for(int i=0;entries!=null&&i<entries.length();i++)if(request.equals(entries.getJSONObject(i).optString("requestId"))){if(approval!=null&&source.has("backendSelection"))throw new SecurityException("原Gemini权限请求身份重复");approval=entries.getJSONObject(i);}
        if(approval==null||!source.optBoolean("protocolVerified"))throw new SecurityException("未取得原协议的当前审批请求");
        if(source.optString("protocol").equals("claude_stream_json")){
            if(source.optString("sessionId").isEmpty()||source.optString("promptRequestId").isEmpty()||!source.optBoolean("controlProtocolVerified")||!source.optBoolean("sessionIdentityConfirmed")||!source.optString("sessionId").equals(approval.optString("sessionId"))||!source.optString("promptRequestId").equals(approval.optString("promptRequestId"))||approval.optString("toolUseId").isEmpty())throw new SecurityException("Claude本次工具请求身份未确认");
        }else if(source.optString("protocol").equals("codex_app_server")){
            if(source.optString("threadId").isEmpty())throw new SecurityException("Codex原会话身份缺失");
            for(String key:new String[]{"threadId","turnId"})if(approval.has(key)&&!source.optString(key).equals(approval.optString(key)))throw new SecurityException("Codex审批已不属于原thread/turn");
        }else if(HarnessCapabilityObservation.GEMINI_PROTOCOL.equals(source.optString("protocol"))){
            HarnessCapabilityObservation.checkedBackendApproval(source,approval);
        }else throw new SecurityException("此执行协议未接入原生审批");
        JSONObject task=tasks.get(source.getString("taskId"),false);if(task!=null&&task.has("parentTaskId")){
            JSONObject parent=tasks.get(task.getString("parentTaskId"),false);if(parent==null)throw new SecurityException("原父任务不存在");
            boolean teamChild="teams".equals(parent.optString("feature"));for(String key:new String[]{"teamRunId","memberId","round","generation"})teamChild|=task.has(key);
            if(teamChild){
                checkTeamMemberInteraction(source);
            }else {tasks.requirePrivateRemoteChild(task.getString("id"));if(parent.optBoolean("cancelRequested"))throw new SecurityException("原私人父任务已停止，不能批准旧子任务");}
        }
    }
    private JSONObject responseIfOpen(Window window,JSONObject operation)throws Exception{ synchronized(this){if(windows.get(window.id)!=window)return new JSONObject().put("operation",operation).put("pageClosed",true);}return response(window,false,operation);}
    private JSONObject response(Window window,boolean fresh,JSONObject operation)throws Exception{
        return response(window,fresh,operation,-1,-1);
    }
    private JSONObject response(Window window,boolean fresh,JSONObject operation,long lifecycle,long generation)throws Exception{
        JSONObject source=tasks.remoteOwnerTarget(window.task),snapshot; synchronized(this){if(lifecycle>=0)checkReadCurrent(window,lifecycle,generation);if(required(window.id)!=window)throw new SecurityException("页面已经关闭，迟到读取未成为审阅凭据");if(window.source!=null&&!sameReadTarget(window.source,source,false)){window.directory=null;window.selectedFiles.clear();window.result=null;window.artifact=null;window.snapshotFile=null;window.projectPage=null;window.projectDiff=null;window.projectDigest=null;window.projectReview=null;window.lastSuccessfulReadAt=0;window.queryStatus="changed";fresh=false;}window.source=new JSONObject(source.toString());window.review=UUID.randomUUID().toString();window.reviewedAt=android.os.SystemClock.uptimeMillis();window.fresh=fresh;
            boolean canContinue=false;String reason="请先查询原电脑任务，核对当前会话与控制";if(fresh)try{tasks.checkRemoteContinuationSource(source);canContinue=true;reason="";}catch(Exception blocked){reason=blocked.getMessage();}
            snapshot=new JSONObject().put("windowId",window.id).put("taskId",window.task).put("reviewId",window.review).put("fresh",fresh).put("canContinue",canContinue).put("reason",reason).put("source",source).put("task",tasks.get(window.task,false)).put("profileBindingHash",window.profileBinding)
                    .put("queryStatus",window.queryStatus).put("lastReadAttemptAt",window.lastReadAttemptAt).put("lastSuccessfulReadAt",window.lastSuccessfulReadAt).put("readObservationOnly",true);
            if(window.gatewayExecutionReview!=null&&window.gatewayExecutionDigest!=null&&foregroundOwner==window.nativeOwner&&System.currentTimeMillis()<window.gatewayExecutionExpires)snapshot.put("gatewayExecutionReview",new JSONObject().put("scope",new JSONObject(window.gatewayExecutionReview.toString())).put("digest",window.gatewayExecutionDigest).put("expiresAt",window.gatewayExecutionExpires));
            if(window.gatewayProfileChoices!=null)snapshot.put("gatewayProfileChoices",new JSONArray(window.gatewayProfileChoices.toString()));
            if(window.gatewayParentReview!=null&&window.gatewayParentDigest!=null&&foregroundOwner==window.nativeOwner&&System.currentTimeMillis()<window.gatewayParentReviewExpires)snapshot.put("gatewayParentRunReview",new JSONObject().put("scope",new JSONObject(window.gatewayParentReview.toString())).put("digest",window.gatewayParentDigest).put("expiresAt",window.gatewayParentReviewExpires));
            snapshot.put("attention",tasks.remoteAttentionState(window.task));JSONObject gatewayStage=tasks.originalGatewayContinuation(window.task);if(gatewayStage!=null)snapshot.put("gatewayContinuation",gatewayStage);
            snapshot.put("inputDrafts",tasks.originalRemoteDrafts(window.task));
            snapshot.put("eventReadStatus",window.eventReadStatus);JSONObject committedEvents=tasks.originalRemoteEventRead(window.task);
            boolean currentEventScope=committedEvents!=null&&window.profileBinding!=null&&source.has("environmentId")&&source.has("worker")&&dev.pocketengine.features.remote.RemoteJobEvents.same(committedEvents.optJSONObject("scope"),dev.pocketengine.features.remote.RemoteJobEvents.scope(source,window.profileBinding));
            if(currentEventScope)snapshot.put("eventRead",committedEvents);else if(committedEvents!=null&&"confirmed".equals(window.eventReadStatus))snapshot.put("eventReadStatus","unconfirmed");
            JSONObject task=snapshot.getJSONObject("task"),origin=task.optJSONObject("origin");boolean teamQuestion=window.teamWindow!=null||task.has("teamRunId")||task.has("memberId")||origin!=null&&origin.optString("kind").startsWith("team");
            boolean memberQuestion=false;if(teamQuestion&&window.teamWindow==null)try{checkTeamMemberInteraction(source);memberQuestion=true;}catch(SecurityException stale){/* Visible original data is not current-member authority. */}
            snapshot.put("canAnswerQuestions",fresh&&window.nativeOwner!=null&&foregroundOwner==window.nativeOwner&&(!teamQuestion||memberQuestion)&&!task.optBoolean("cancelRequested")&&!tasks.hasUnresolved(window.task)&&source.optJSONObject("controls")!=null&&source.getJSONObject("controls").optBoolean("answerQuestion")).put("questionAnswerScope",memberQuestion?"current_team_member":teamQuestion?"unavailable":"private_session_only").put("questionAnswerReason",teamQuestion&&!memberQuestion?"原成员代次/协调器已失效或属于制作assignment；不能借私人问答继续":"只回答此原成员/会话的原问题一次，不授予文件/命令权限；未知只查询原job");
            if(teamQuestion&&window.teamWindow==null){JSONArray acceptIds=new JSONArray();JSONArray pending=source.optJSONArray("pendingApprovals");for(int i=0;memberQuestion&&pending!=null&&i<pending.length();i++){JSONObject request=pending.getJSONObject(i);if(source.optString("protocol").equals("claude_stream_json")&&request.optString("method").equals("claude/can_use_tool")&&request.optString("tool").matches("Read|Glob|Grep")&&request.optBoolean("canAccept"))acceptIds.put(request.getString("requestId"));}snapshot.put("teamMemberApprovalAcceptIds",acceptIds);}
            if(window.teamWindow!=null)snapshot.put("teamRemoteWriter",true).put("teamRemoteScope",tasks.originalTeamRemoteContext(window.task).view()).put("canContinue",false).put("reason","所选文件副本制作：不代表Git全项目或OS隔离。需要命令的完整build/test仍未闭合；继续模型/冻结贡献请回原团队宿主，不能从此私人会话页续接。");
            snapshot.put("canCaptureSnapshot",!source.optString("worker").equals("pi")&&window.teamWindow==null&&fresh&&!source.optString("jobId").isEmpty()&&source.optString("executorStatus").matches("completed|failed|cancelled")&&!tasks.hasUnresolved(window.task));
            if(window.directory!=null)snapshot.put("resultDirectory",new JSONObject(window.directory.toString()));JSONArray selectedFiles=new JSONArray();for(JSONObject selected:window.selectedFiles.values())selectedFiles.put(new JSONObject(selected.toString()));snapshot.put("selectedResultFiles",selectedFiles).put("snapshotAttachments",tasks.remoteSnapshotAttachments(window.task));
            if(window.conversationResult!=null&&window.conversationDigest!=null&&foregroundOwner==window.nativeOwner&&System.currentTimeMillis()<=window.conversationExpiresAt)snapshot.put("conversationResultReview",new JSONObject().put("scope",new JSONObject(window.conversationResult.toString())).put("digest",window.conversationDigest).put("expiresAt",window.conversationExpiresAt));
        }
        JSONArray conversationInputs=new JSONArray();JSONObject inputScope=snapshot.getJSONObject("task").optJSONObject("remoteInputScope");
        if(inputScope!=null){JSONArray files=inputScope.optJSONArray("files");for(int i=0;files!=null&&i<files.length();i++){JSONObject file=files.getJSONObject(i);try{conversationInputs.put(channels().capturePrivateRemoteInput(file.getString("attachmentId"),file.getString("sha256")).view());}catch(IllegalStateException|SecurityException unavailable){/* Old/other sources cannot become a return destination. */}}}
        synchronized(this){if(window.projectPage!=null)snapshot.put("projectHistoryPage",new JSONObject(window.projectPage.toString()));if(window.projectDiff!=null)snapshot.put("projectHistoryDiff",new JSONObject(window.projectDiff.toString()));if(window.projectOperation!=null)snapshot.put("projectHistoryOperation",new JSONObject(window.projectOperation.toString()));if(window.projectReview!=null&&window.projectDigest!=null)snapshot.put("projectHistoryReview",new JSONObject().put("scope",new JSONObject(window.projectReview.toString())).put("digest",window.projectDigest).put("expiresAt",window.projectExpires));snapshot.put("projectBusy",window.projectBusy);}
        snapshot.put("projectHistorySources",tasks.originalRemoteProjectHistorySources(window.task));if(window.projectReadSource!=null)snapshot.put("projectHistoryReadSource",new JSONObject(window.projectReadSource.toString()));
        if(gatewayOwner!=null)snapshot.put("harnessGateway",window.gateway==null?gatewayOwner.summary(window.task):gatewayOwner.snapshot(window.gateway));
        snapshot.put("conversationInputSources",conversationInputs);try{snapshot.put("conversationResultDeliveries",channels().originalRemoteResults(window.task));}catch(IllegalStateException|SecurityException unavailable){snapshot.put("conversationDeliveryRead","unavailable_not_empty_or_delivered");}
        synchronized(this){if(window.result!=null)snapshot.put("result",new JSONObject(window.result.toString()));if(window.artifact!=null)snapshot.put("artifact",new JSONObject(window.artifact.toString()));}
        synchronized(this){if(window.snapshotFile!=null)snapshot.put("snapshotFile",new JSONObject(window.snapshotFile.toString()));}
        synchronized(this){if(lifecycle>=0)checkReadCurrent(window,lifecycle,generation);}
        JSONObject result=new JSONObject().put("snapshot",snapshot);if(operation!=null)result.put("operation",operation);return result;
    }
    private JSONObject gatewayParentCommand(Object owner,String command,JSONObject input)throws Exception {
        java.util.Set<String> allowed=command.equals("gateway.resumeParentRun")?java.util.Set.of("windowId","taskId","acceptedDigest"):(command.equals("gateway.reviewParentProfile")||command.equals("gateway.saveProfileTarget"))?java.util.Set.of("windowId","taskId","profileId","profileRevision"):java.util.Set.of("windowId","taskId");for(var keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("恢复原父任务只接受本页准确原生审阅，不接预算/身份payload");Window window;long lifecycle;
        synchronized(this){window=required(input.getString("windowId"));if(owner==null||foregroundOwner!=owner||window.nativeOwner!=owner||!window.task.equals(input.getString("taskId"))||window.teamWindow!=null||gatewayOwner==null)throw new SecurityException("请在原私人任务的前台主人页确认");lifecycle=window.lifecycleRevision;}
        if(command.equals("gateway.profileChoices")){JSONArray choices=gatewayOwner.parentProfileChoices(window.task);synchronized(this){if(window.closed||window.lifecycleRevision!=lifecycle||foregroundOwner!=window.nativeOwner)throw new SecurityException("原配置选择页面已失效");window.gatewayProfileChoices=choices;}return response(window,false,null);}
        if(command.equals("gateway.parentStatus"))return response(window,false,null);
        if(command.equals("gateway.stopParentRun")){synchronized(this){window.gatewayExecutionDigest=null;window.gatewayParentDigest=null;window.gatewayRevision++;}gatewayOwner.stopContinuation(window.task);return response(window,false,null);}
        if((command.equals("gateway.reviewParentProfile")||command.equals("gateway.saveProfileTarget"))&&(!(input.opt("profileId") instanceof String)||!(input.opt("profileRevision") instanceof Integer||input.opt("profileRevision") instanceof Long)||input.getLong("profileRevision")<1))throw new SecurityException("只接受保存工作配置的准确id与整数修订");
        if(command.equals("gateway.saveProfileTarget")){
            JSONObject saved=gatewayOwner.saveCurrentProfileTarget(window.task,input.getString("profileId"),input.getLong("profileRevision"),()->{synchronized(RemoteOwnerWorkflows.this){if(window.closed||windows.get(window.id)!=window||window.lifecycleRevision!=lifecycle||foregroundOwner!=window.nativeOwner)throw new SecurityException("原主人页已失效，未保存目标引用");}});
            synchronized(this){window.gatewayParentReview=null;window.gatewayParentDigest=null;JSONArray choices=window.gatewayProfileChoices;if(choices!=null)for(int i=0;i<choices.length();i++)if(saved.getString("profileId").equals(choices.getJSONObject(i).optString("profileId")))choices.getJSONObject(i).put("revision",saved.getLong("revision"));}
            JSONObject result=response(window,false,null);result.getJSONObject("snapshot").put("gatewayProfileTargetSaved",new JSONObject().put("profileId",saved.getString("profileId")).put("profileRevision",saved.getLong("revision")).put("referenceOnly",true).put("configurationApplied",false).put("executionAuthorized",false));return result;
        }
        if(command.equals("gateway.reviewParentRun")||command.equals("gateway.reviewParentProfile")){var access=command.equals("gateway.reviewParentProfile")?gatewayOwner.parentResumeProfile(window.task,input.getString("profileId"),input.getLong("profileRevision")):gatewayOwner.parentResume(window.task);JSONObject view=access.view();synchronized(this){if(window.closed||window.lifecycleRevision!=lifecycle||foregroundOwner!=window.nativeOwner)throw new SecurityException("恢复审阅已离开前台");window.gatewayParentAccess=access;window.gatewayParentReview=view;window.gatewayParentDigest=TaskStore.capabilityFingerprint(view);window.gatewayParentReviewExpires=Math.min(view.getLong("expiresAt"),System.currentTimeMillis()+60_000L);}return response(window,false,null);}
        GatewayParentRunApproval approval;dev.pocketengine.features.remote.NativeHarnessGatewayOwner.ParentAccess access;
        synchronized(this){String accepted=input.getString("acceptedDigest");if(accepted.equals(window.lastGatewayParentDigest))return response(window,false,null);if(window.gatewayParentReview==null||window.gatewayParentDigest==null||!accepted.equals(window.gatewayParentDigest))throw new SecurityException("只可确认屏幕上的原父任务/累计预算/用途与期限");approval=new GatewayParentRunApproval(this,window);access=window.gatewayParentAccess;approval.checkCurrent();}
        JSONObject status=access.start(approval);synchronized(this){window.lastGatewayParentDigest=approval.digest();window.gatewayParentReview=null;}JSONObject result=response(window,false,null);result.getJSONObject("snapshot").put("gatewayParentAdmission",status);return result;
    }
    private JSONObject gatewayExecutionCommand(Object owner,String command,JSONObject input)throws Exception {
        java.util.Set<String> allowed=command.equals("gateway.reviewRun")?java.util.Set.of("windowId","taskId","leaseId"):java.util.Set.of("windowId","taskId","acceptedDigest");for(var keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("本轮最终执行只接受屏幕上原lease/digest引用");Window window;long lifecycle;
        synchronized(this){window=required(input.getString("windowId"));if(owner==null||foregroundOwner!=owner||window.nativeOwner!=owner||!window.task.equals(input.getString("taskId"))||window.teamWindow!=null||gatewayOwner==null)throw new SecurityException("本轮最终确认须由前台原私人主人进行");lifecycle=window.lifecycleRevision;}
        if(command.equals("gateway.reviewRun")){
            synchronized(this){if(window.gateway==null||!gatewayOwner.windowCurrent(window.gateway)){long revision=++window.gatewayRevision;window.gateway=gatewayOwner.page(window.task,()->{synchronized(RemoteOwnerWorkflows.this){if(window.closed||windows.get(window.id)!=window||window.lifecycleRevision!=lifecycle||window.gatewayRevision!=revision||foregroundOwner!=window.nativeOwner)throw new SecurityException("本轮页面已改变");}});gatewayOwner.observe(window.gateway);}}
            TaskStore.HarnessGatewayConfiguration configuration=gatewayOwner.configuration(window.gateway,input);JSONObject source=tasks.remoteOwnerTarget(window.task);String binding=module().profileBindingHash(source.getString("profileId"));JSONObject facts=tasks.reviewGatewayContinuationExecution(configuration,binding);
            synchronized(this){if(window.closed||window.lifecycleRevision!=lifecycle||foregroundOwner!=window.nativeOwner)throw new SecurityException("原最终审阅已离开前台");window.gatewayExecutionConfiguration=configuration;window.gatewayExecutionReview=facts;window.gatewayExecutionDigest=TaskStore.capabilityFingerprint(facts);window.gatewayExecutionExpires=Math.min(facts.getLong("expiresAt"),System.currentTimeMillis()+60_000L);}return response(window,false,null);
        }
        GatewayContinuationExecutionApproval approval;TaskStore.GatewayContinuationDispatch claim;
        synchronized(this){String accepted=input.getString("acceptedDigest");if(accepted.equals(window.lastGatewayDispatchDigest)&&window.lastGatewayDispatchOperation!=null)return refresh(window);if(window.gatewayExecutionReview==null||window.gatewayExecutionDigest==null||!accepted.equals(window.gatewayExecutionDigest))throw new SecurityException("只可确认屏幕上本轮准确scope，不恢复旧批准");approval=new GatewayContinuationExecutionApproval(this,window);approval.checkCurrent();claim=tasks.claimGatewayContinuationExecution(approval);window.lastGatewayDispatchDigest=accepted;window.lastGatewayDispatchOperation=approval.view().getString("operationId");window.gatewayExecutionReview=null;}
        JSONObject actual;try{try{actual=module().submitGatewayContinuation(claim);}catch(Exception unknown){JSONObject scope=approval.view();actual=new JSONObject().put("operationId",scope.getString("operationId")).put("profileId",scope.getString("profileId")).put("serviceBase",scope.getString("serviceBase")).put("status","unknown").put("message","本轮创建回执未确认；只查询原operationId，不重POST或补发模型");}tasks.updateExternal(window.task,actual);emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,approval.view().getString("operationId")));}finally{claim.endObservation();synchronized(this){window.gatewayExecutionDigest=null;window.gatewayExecutionConfiguration=null;}}
    }
    private JSONObject cancelGatewayContinuation(Object owner,JSONObject input)throws Exception {
        for(var keys=input.keys();keys.hasNext();)if(!java.util.Set.of("windowId","taskId","operationId").contains(keys.next()))throw new SecurityException("取消只接受本页原operation引用");Window window;GatewayContinuationCancellation cancellation;
        synchronized(this){window=required(input.getString("windowId"));if(owner==null||owner!=foregroundOwner||window.nativeOwner!=owner||!window.task.equals(input.getString("taskId"))||window.teamWindow!=null||gatewayOwner==null)throw new SecurityException("取消须由本原私人任务前台主人发起");JSONObject facts=tasks.originalGatewayContinuation(window.task);if(facts==null||!input.getString("operationId").equals(facts.getString("operationId")))throw new SecurityException("不是当前原prepared操作");JSONObject source=tasks.remoteOwnerTarget(window.task);facts.put("profileBinding",module().profileBindingHash(source.getString("profileId"))).put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase"));cancellation=new GatewayContinuationCancellation(this,window,facts);window.gatewayExecutionDigest=null;window.gatewayExecutionReview=null;window.gatewayExecutionConfiguration=null;window.gatewayRevision++;tasks.requestGatewayContinuationCancellation(cancellation);}
        // Revocation precedes any remote read. A failed read can never restore dispatch authority.
        gatewayOwner.stopContinuation(window.task);JSONObject scope=cancellation.view();try{cancellation.checkCurrent();JSONObject actual=module().handle("reconcileOperation",new JSONObject().put("profileId",scope.getString("profileId")).put("serviceBase",scope.getString("serviceBase")).put("operationId",scope.getString("operationId")).put("_profileBinding",scope.getString("profileBinding")));cancellation.checkCurrent();tasks.observeGatewayContinuationCancellation(cancellation,actual);}catch(Exception unknown){/* Original cancellation remains pending; no POST, new epoch or assumed no-dispatch. */}emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,scope.getString("operationId")));
    }
    private JSONObject prepareGatewayContinuation(Object owner,JSONObject input)throws Exception {
        java.util.Set<String> allowed=java.util.Set.of("windowId","taskId","reviewId","prompt","snapshotId","snapshotVersion");for(var keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("本轮准备不接收目标、预算、凭据或执行身份");
        Window window;String request=TaskStore.capabilityFingerprint(new JSONObject().put("prompt",input.getString("prompt")).put("snapshotId",input.opt("snapshotId")).put("snapshotVersion",input.opt("snapshotVersion")));JSONObject selected=null;
        synchronized(this){window=required(input.getString("windowId"));if(owner==null||foregroundOwner!=owner||window.nativeOwner!=owner||!window.task.equals(input.getString("taskId"))||window.teamWindow!=null||gatewayOwner==null)throw new SecurityException("只允许前台原私人任务主人准备新轮");
            if(window.gatewayPreparedOperation!=null&&input.getString("reviewId").equals(window.gatewayPreparedReview)){if(!request.equals(window.gatewayPreparedRequest))throw new SecurityException("同一次批准不能改变新轮要求");return response(window,false,tasks.remoteOwnerOperation(window.task,window.gatewayPreparedOperation));}
            JSONObject existingStage=tasks.originalGatewayContinuation(window.task);if(existingStage!=null&&!existingStage.optString("operationState").equals("remote_job_receipt"))throw new SecurityException("此原任务已有网关新轮事实；只核对原操作，不另建epoch");checkReview(window,input.getString("reviewId"));if(dev.pocketengine.features.remote.RemoteColdRecovery.source(window.source))throw new SecurityException("本轮网关只用于已结算的原会话；冷恢复仍走原明确恢复范围");tasks.checkRemoteContinuationSource(window.source);
            if(input.has("snapshotId")||input.has("snapshotVersion")){selected=tasks.remoteResultSnapshot(window.task,input.getString("snapshotId"));if(!selected.getString("snapshotVersion").equals(input.getString("snapshotVersion"))||!window.source.getString("jobId").equals(selected.getString("jobId")))throw new SecurityException("不是原会话的准确成果版本");}}
        if(selected!=null){JSONObject source=selected.getJSONObject("source"),actual=module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",selected.getString("snapshotId")).put("snapshotVersion",selected.getString("snapshotVersion")).put("verifyCurrent",true).put("_profileBinding",window.profileBinding));if(!actual.optBoolean("matchesCurrent"))throw new SecurityException("原成果版本变化，未准备新轮");}
        JSONObject operation;synchronized(this){checkReview(window,input.getString("reviewId"));var admission=gatewayOwner.captureContinuation(window.task);GatewayContinuationPreparation preparation=new GatewayContinuationPreparation(this,window,input.getString("prompt"),selected,admission);operation=tasks.prepareGatewayContinuation(preparation);window.gatewayPreparedReview=window.review;window.gatewayPreparedRequest=request;window.gatewayPreparedOperation=operation.getString("operationId");window.review=null;window.fresh=false;window.gateway=null;window.gatewayRevision++;}
        emitTask(window.task);return response(window,false,operation);
    }
    private JSONObject gatewayCommand(Object owner,String command,JSONObject input)throws Exception {
        Window window;long lifecycle;
        synchronized(this){window=required(input.getString("windowId"));if(owner==null||foregroundOwner!=owner||window.nativeOwner!=owner||!window.task.equals(input.getString("taskId"))||window.teamWindow!=null||gatewayOwner==null)throw new SecurityException("只允许当前原生前台私人原任务页管理网关");lifecycle=window.lifecycleRevision;
            if(command.equals("gateway.inspect")||window.gateway==null||!gatewayOwner.windowCurrent(window.gateway)&&(command.equals("gateway.query")||command.equals("gateway.revoke")||command.equals("gateway.configure"))){long gatewayRevision=++window.gatewayRevision;window.gateway=gatewayOwner.page(window.task,()->{synchronized(RemoteOwnerWorkflows.this){if(window.closed||windows.get(window.id)!=window||window.lifecycleRevision!=lifecycle||window.gatewayRevision!=gatewayRevision||foregroundOwner!=window.nativeOwner)throw new SecurityException("网关范围审阅已关闭、过期或退出前台");}});if(!command.equals("gateway.inspect"))gatewayOwner.observe(window.gateway);}}
        if(command.equals("gateway.configure")){JSONObject result=harnessConfigs.openGateway(owner,window.id,window.task,gatewayOwner.configurationSource(window.gateway,input));result.getJSONObject("snapshot").put("harnessGateway",gatewayOwner.snapshot(window.gateway));JSONObject stage=tasks.originalGatewayContinuation(window.task);if(stage!=null)result.getJSONObject("snapshot").put("gatewayContinuation",stage);return result;}
        JSONObject gateway=gatewayOwner.handle(window.gateway,command,input),result=response(window,window.fresh,null);result.getJSONObject("snapshot").put("harnessGateway",gateway);return result;
    }
    private JSONObject projectCommand(Object owner,String command,JSONObject input)throws Exception {
        java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Set.of("windowId","taskId","reviewId"));
        switch(command){case "project.open":allowed.add("request");break;case "project.reviewStart":allowed.add("projectRoot");break;case "project.list":allowed.addAll(java.util.Set.of("offset","operationId"));break;case "project.diff":allowed.add("path");break;case "project.reviewRestore":allowed.addAll(java.util.Set.of("snapshotId","snapshotVersion","path","reviewId"));break;case "project.reviewFork":allowed.addAll(java.util.Set.of("prompt","timeoutSeconds","reviewId"));break;case "project.confirm":allowed.addAll(java.util.Set.of("digest","scopeAcknowledged"));break;case "project.queryRestore":allowed.add("operationId");break;default:throw new SecurityException("未知原生项目版本动作");}
        for(java.util.Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("原生项目版本动作不接收凭据、任意URL或模型提供的权限");
        String id=input.getString("windowId"),task=input.getString("taskId");Window window;
        if(command.equals("project.open")){
            JSONObject first=dev.pocketengine.features.remote.RemoteTaskRequest.first(input.getJSONObject("request"));
            if(!first.getString("worker").matches("codex_app_server|claude")||!id.matches("[a-f0-9-]{36}")||!task.matches("[a-f0-9-]{36}"))throw new SecurityException("项目基线只用于本次明确新建的编码任务");
            window=new Window(id,task,owner);window.projectFirst=first;
            synchronized(this){projectForeground(window);if(windows.containsKey(id)||windows.size()>=6||tasks.get(task,false)!=null)throw new SecurityException("不能覆盖已有任务或旧页面");windows.put(id,window);}
            try{window.projectEnvironment=projectEnvironmentCurrent(window,first);}catch(Exception unavailable){close(id);throw unavailable;}return projectResponse(window);
        }
        synchronized(this){window=required(id);if(owner!=window.nativeOwner||!task.equals(window.task))throw new SecurityException("项目版本页面不是此原生任务");projectForeground(window);if(window.projectBusy)throw new IllegalStateException("正在处理这次原操作，请先查看其结果");}
        if(command.equals("project.reviewStart")){
            if(window.projectFirst==null||window.projectOperation!=null||tasks.get(window.task,false)!=null)throw new SecurityException("本次新项目动作已登记，只查原编号；另建工作需重新打开原生页面");long life=window.lifecycleRevision;JSONObject environment=projectEnvironmentCurrent(window,window.projectFirst);dev.pocketengine.features.remote.RemoteProjectHistory.requireCapability(environment);window.projectEnvironment=environment;
            JSONObject scope=projectStartScope(window,"START",new JSONObject(window.projectFirst.toString()));scope.getJSONObject("request").put("projectHistory",dev.pocketengine.features.remote.RemoteProjectHistory.selection(input.optString("projectRoot")));
            return projectReview(window,scope,life);
        }
        if(command.equals("project.list")||command.equals("project.diff")){
            JSONObject query=new JSONObject();if(command.equals("project.list")){int offset=input.optInt("offset",0);if(offset==0){window.projectReadOperation=input.optString("operationId").isEmpty()?null:input.getString("operationId");if(window.projectReadOperation!=null)tasks.originalRemoteProjectHistorySource(window.task,window.projectReadOperation);}query.put("offset",offset);if(offset>0){if(window.projectPage==null||offset!=window.projectPage.getInt("nextOffset"))throw new SecurityException("项目分页必须从上页准确位置继续");query.put("pageVersion",window.projectPage.getString("pageVersion"));}}else query.put("diffPath",dev.pocketengine.features.remote.RemoteProjectHistory.safePath(input.getString("path")));
            JSONObject actual=projectRead(window,query);if(command.equals("project.list"))window.projectPage=dev.pocketengine.features.remote.RemoteProjectHistory.page(actual,window.projectReadSource);else{dev.pocketengine.features.remote.RemoteProjectHistory.original(actual,window.projectReadSource);window.projectDiff=actual;}return projectResponse(window);
        }
        if(command.equals("project.reviewRestore"))return projectRestoreReview(window,input);
        if(command.equals("project.reviewFork"))return projectForkReview(window,input);
        if(command.equals("project.queryRestore")){
            JSONObject original=tasks.originalRemoteProjectAction(window.task,input.getString("operationId"));if(original.getString("state").equals("receipt")){window.projectOperation=original;return projectResponse(window);}JSONObject actual=module().queryProjectFileRestore(original.getJSONObject("arguments").getJSONObject("scope"),window.profileBinding);tasks.recordRemoteProjectAction(window.task,input.getString("operationId"),actual);window.projectOperation=tasks.originalRemoteProjectAction(window.task,input.getString("operationId"));return projectResponse(window);
        }
        ProjectApproval approval;synchronized(this){projectForeground(window);if(input.opt("scopeAcknowledged")!=Boolean.TRUE||window.projectReview==null||!input.getString("digest").equals(window.projectDigest))throw new SecurityException("请逐项核对准确目标、源码/文件范围和这次新预算后确认");approval=new ProjectApproval(this,window);approval.checkCurrent();window.projectBusy=true;}
        JSONObject scope=approval.view();String affected=scope.getString("taskId"),operation=scope.getString("operationId");
        try{
            module().requireProfileBinding(scope.getString("profileId"),scope.getString("profileGeneration"));tasks.prepareRemoteProjectAction(approval);emitTask(affected);
            JSONObject actual;
            try{actual=module().dispatchProjectAction(approval,()->tasks.beforeRemoteProjectAction(approval));}
            catch(Exception unknown){actual=new JSONObject().put("operationId",operation).put("profileId",scope.getString("profileId")).put("serviceBase",scope.getString("serviceBase")).put("status","unknown").put("message","本次原操作回执未确认；只查询原编号，不重建工作或重发文件");}
            tasks.recordRemoteProjectAction(affected,operation,actual);module().settleProjectCancellation(approval);emitTask(affected);window.projectOperation=tasks.originalRemoteProjectAction(affected,operation);
        }finally{synchronized(this){window.projectBusy=false;window.projectDigest=null;window.projectReview=null;}}
        return projectResponse(window);
    }
    private void projectForeground(Window window)throws Exception{if(window.closed||window.nativeOwner==null||foregroundOwner!=window.nativeOwner)throw new SecurityException("请在真实前台原生页面审阅项目版本；后台不会自动同意");}
    private JSONObject projectEnvironment(JSONObject request)throws Exception {
        String profile=request.getString("profileId"),binding=request.getString("_profileBinding");module().requireProfileBinding(profile,binding);
        JSONObject env=dev.pocketengine.features.remote.RemoteTaskRequest.environment(module().handle("environments",new JSONObject().put("profileId",profile).put("_profileBinding",binding)),request.getString("environmentId"),request.getString("worker"));
        if(request.has("environmentBinding")&&!request.getString("environmentBinding").equals(env.optString("bindingHash")))throw new SecurityException("项目环境代次已改变，请重新读取并审阅");return env;
    }
    private JSONObject projectEnvironmentCurrent(Window window,JSONObject request)throws Exception {
        long revision; synchronized(this){projectForeground(window);revision=window.lifecycleRevision;}JSONObject environment=projectEnvironment(request);synchronized(this){projectForeground(window);if(revision!=window.lifecycleRevision)throw new SecurityException("原环境读取跨过后台/旧页面，迟答没有成为新审阅");}return environment;
    }
    private JSONObject projectStartScope(Window window,String action,JSONObject request)throws Exception {
        JSONObject profile=module().requireProfileBinding(request.getString("profileId"),request.getString("_profileBinding"));
        String task=action.equals("START")?window.task:UUID.randomUUID().toString();return new JSONObject().put("action",action).put("taskId",task).put("operationId",task).put("profileId",request.getString("profileId")).put("profileGeneration",request.getString("_profileBinding")).put("serviceBase",profile.getString("serviceBase")).put("request",request).put("workspace",window.projectEnvironment.optString("workspace")).put("modelCallsHardLimited",false).put("internalTokenAndCostKnown",false).put("oldApprovalOrBudgetInherited",false);
    }
    private JSONObject projectReview(Window window,JSONObject scope,long originalLifecycle)throws Exception {
        synchronized(this){projectForeground(window);if(window.lifecycleRevision!=originalLifecycle)throw new SecurityException("原项目审阅已跨过前台代次，未创建新批准");window.projectExpires=System.currentTimeMillis()+120000;scope.put("approvalExpiresAt",window.projectExpires);window.projectReview=new JSONObject(scope.toString());window.projectDigest=TaskStore.capabilityFingerprint(scope);}return projectResponse(window);
    }
    private JSONObject projectRead(Window window,JSONObject query)throws Exception {
        JSONObject source=tasks.remoteOwnerTarget(window.task);long lifecycle,generation;synchronized(this){projectForeground(window);if(window.projectFirst!=null)throw new SecurityException("尚未创建此原任务");lifecycle=window.lifecycleRevision;generation=++window.readGeneration;}
        String historical=window.projectReadOperation;JSONObject selected=historical==null?source:tasks.originalRemoteProjectHistorySource(window.task,historical);String binding=module().profileBindingHash(selected.getString("profileId"));JSONObject actual=module().readProjectHistory(selected,binding,query,()->{synchronized(RemoteOwnerWorkflows.this){checkReadCurrent(window,lifecycle,generation);}JSONObject current=tasks.remoteOwnerTarget(window.task);if(!sameReadTarget(source,current,false))throw new SecurityException("读取期间原task/run/job改变");if(historical!=null&&!TaskStore.capabilityFingerprint(selected).equals(TaskStore.capabilityFingerprint(tasks.originalRemoteProjectHistorySource(window.task,historical))))throw new SecurityException("历史项目原SQL来源发生变化，迟读未接入");});
        synchronized(this){checkReadCurrent(window,lifecycle,generation);window.source=source;window.projectReadSource=selected;window.profileBinding=binding;}return actual;
    }
    private JSONObject projectRestoreReview(Window window,JSONObject input)throws Exception {
        synchronized(this){checkReview(window,input.getString("reviewId"));projectForeground(window);}if(window.teamWindow!=null)throw new SecurityException("团队文件恢复只能回原唯一整合/版本流程，不能继承私人恢复权限");
        long life=window.lifecycleRevision;String original=TaskStore.capabilityFingerprint(window.source);String path=dev.pocketengine.features.remote.RemoteProjectHistory.safePath(input.getString("path")),snapshot=input.getString("snapshotId"),version=input.getString("snapshotVersion");
        JSONObject file=projectRead(window,new JSONObject().put("snapshotId",snapshot).put("snapshotVersion",version).put("path",path));JSONObject versionSource=new JSONObject(window.projectReadSource.toString());JSONObject desired=dev.pocketengine.features.remote.RemoteProjectHistory.exactFile(file,versionSource,snapshot,version,path);
        JSONObject current=projectRead(window,new JSONObject().put("currentPath",path));dev.pocketengine.features.remote.RemoteProjectHistory.original(current,versionSource);dev.pocketengine.features.remote.RemoteProjectHistory.metadata(current,true);
        JSONObject first=new JSONObject().put("profileId",window.source.getString("profileId")).put("_profileBinding",window.profileBinding).put("environmentId",window.source.getString("environmentId")).put("worker",window.source.getString("worker"));JSONObject environment=projectEnvironmentCurrent(window,first);if(!original.equals(TaskStore.capabilityFingerprint(window.source)))throw new SecurityException("读取期间原task/run/job来源已变化");dev.pocketengine.features.remote.RemoteProjectHistory.requireCapability(environment);if(!environment.optBoolean("writeAllowed"))throw new SecurityException("此环境不允许恢复文件");
        JSONObject request=new JSONObject().put("jobOperationId",versionSource.getString("jobOperationId")).put("snapshotId",snapshot).put("snapshotVersion",version).put("path",path).put("expectedVersion",current.getString("contentVersion")).put("expectedFileVersion",current.getString("fileVersion")).put("environmentBinding",environment.getString("bindingHash"));
        return projectReview(window,new JSONObject().put("action","RESTORE").put("taskId",window.task).put("operationId",UUID.randomUUID().toString()).put("profileId",window.source.getString("profileId")).put("profileGeneration",window.profileBinding).put("serviceBase",window.source.getString("serviceBase")).put("source",new JSONObject(window.source.toString())).put("versionSource",tasks.originalRemoteProjectHistorySource(window.task,versionSource.getString("jobOperationId"))).put("request",request).put("desired",desired).put("current",dev.pocketengine.features.remote.RemoteProjectHistory.metadata(current,true)).put("restoresConversation",false).put("atomicWorkspaceTransaction",false),life);
    }
    private JSONObject projectForkReview(Window window,JSONObject input)throws Exception {
        synchronized(this){checkReview(window,input.getString("reviewId"));projectForeground(window);}if(window.teamWindow!=null||!window.source.optString("worker").equals("codex_app_server")||(window.source.optJSONObject("controls")==null||!window.source.getJSONObject("controls").optBoolean("threadFork")))throw new SecurityException("此原job/已装协议未提供可核的会话分支；Claude旧端不伪造新会话，不退回thread/start");
        long life=window.lifecycleRevision;JSONObject source=new JSONObject(window.source.toString()),first=new JSONObject().put("profileId",source.getString("profileId")).put("bindingHash",window.profileBinding).put("environmentId",source.getString("environmentId")).put("environmentBinding","").put("worker",source.getString("worker")).put("prompt",input.getString("prompt")).put("readOnly",source.optBoolean("readOnly")).put("timeoutSeconds",input.getInt("timeoutSeconds"));if(source.has("model")&&!source.isNull("model"))first.put("model",source.getString("model"));
        JSONObject env=projectEnvironmentCurrent(window,new JSONObject().put("profileId",source.getString("profileId")).put("_profileBinding",window.profileBinding).put("environmentId",source.getString("environmentId")).put("worker",source.getString("worker")));first.put("environmentBinding",env.getString("bindingHash"));first=dev.pocketengine.features.remote.RemoteTaskRequest.first(first);window.projectEnvironment=env;
        JSONObject scope=projectStartScope(window,"FORK",first).put("source",source).put("filesRestored",false);scope.getJSONObject("request").put("forkSource",new JSONObject().put("jobId",source.getString("jobId")).put("jobOperationId",source.getString("jobOperationId")).put("threadId",source.getString("threadId")).put("turnId",source.getString("turnId")));return projectReview(window,scope,life);
    }
    private JSONObject projectResponse(Window window)throws Exception {
        synchronized(this){if(window.closed||windows.get(window.id)!=window)return new JSONObject().put("pageClosed",true).put("operation",window.projectOperation);projectForeground(window);JSONObject snapshot=window.projectFirst!=null?new JSONObject().put("windowId",window.id).put("taskId",window.task).put("projectFirstRequest",window.projectFirst).put("projectEnvironment",window.projectEnvironment):response(window,window.fresh,null).getJSONObject("snapshot");
            if(window.projectPage!=null)snapshot.put("projectHistoryPage",new JSONObject(window.projectPage.toString()));if(window.projectDiff!=null)snapshot.put("projectHistoryDiff",new JSONObject(window.projectDiff.toString()));if(window.projectOperation!=null)snapshot.put("projectHistoryOperation",new JSONObject(window.projectOperation.toString()));if(window.projectReview!=null&&window.projectDigest!=null)snapshot.put("projectHistoryReview",new JSONObject().put("scope",new JSONObject(window.projectReview.toString())).put("digest",window.projectDigest).put("expiresAt",window.projectExpires));snapshot.put("projectBusy",window.projectBusy);return new JSONObject().put("snapshot",snapshot);}
    }
    private void emitTask(String id)throws Exception{events.emit(new JSONObject().put("type","task").put("task",tasks.get(id,false)));}
}
