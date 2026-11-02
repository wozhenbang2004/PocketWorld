package dev.pocketengine.features.devices;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.devices.transport.DeviceEndpointResolver;
import dev.pocketengine.features.devices.transport.DeviceTransportStatus;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production assembler for the existing App, SQL authority, phone resources and TLS primitives.
 * Construction and snapshot are read-only: neither creates a key, listener, task, model nor Runner.
 * The App retains this owner through host closures; only its explicit owner actions enable I/O. */
public final class DeviceWorkspaceNativeOwner implements AutoCloseable {
    final EngineApplication app;
    final TaskStore tasks;
    final TaskStoreDeviceTrust trust;
    final DeviceWorkspaceLanRuntime network;
    private final NativeDeviceWorkspaceHost host;
    private final NativeDeviceTaskScopeHost taskScopeHost;
    private final DeviceWorkspaceGrantOwner grants;
    private volatile DeviceIdentity.LocalIdentity identity;
    private volatile PairingCoordinator coordinator;
    private volatile DeviceWorkspaceArtifacts artifacts;
    private volatile boolean closed;

    public DeviceWorkspaceNativeOwner(EngineApplication originalApp, TaskStoreDeviceTrust originalSameDatabaseTrust, java.util.concurrent.Executor originalIngressWorker) {
        app = java.util.Objects.requireNonNull(originalApp); tasks = java.util.Objects.requireNonNull(app.tasks);
        trust = java.util.Objects.requireNonNull(originalSameDatabaseTrust); network = new DeviceWorkspaceLanRuntime(this, originalIngressWorker); grants = new DeviceWorkspaceGrantOwner(this);
        host = new NativeDeviceWorkspaceHost(this::snapshot, new Receiver(), new Pairing(), new Goals(), new Controls());
        taskScopeHost = new NativeDeviceTaskScopeHost(tasks, new NativeDeviceTaskScopeHost.NativeOptionSource() {
            public Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants() throws Exception { return nativeOutgoingGrants(); }
            public JSONObject authenticatedCapabilities(String id) throws Exception { return DeviceWorkspaceNativeOwner.this.authenticatedCapabilities(id); }
            public boolean hasMoreOutgoingGrants() { return DeviceWorkspaceNativeOwner.this.hasMoreOutgoingGrants(); }
        }, originalTaskHost(), evidence -> { evidence.requireCurrent(); return artifactsForNativeWork().receive(evidence); });
        app.setDeviceObservedImageSink(this::publishObservedImage);
    }
    DeviceWorkspaceArtifacts existingArtifacts() { return artifacts; }
    private synchronized DeviceWorkspaceArtifacts artifactsForNativeWork() throws Exception {
        if (closed) throw new IllegalStateException("原设备工作区已关闭");
        if (artifacts == null) artifacts = new DeviceWorkspaceArtifacts(this); return artifacts;
    }
    private JSONObject publishObservedImage(String id, String run, int epoch, String operation, JSONObject observation, JSONObject actualImage) throws Exception {
        DeviceToolsSession original = app.originalDeviceToolsSession(id);
        if (original == null || !original.originalControl().matchesHostRun(id, run, epoch)) throw new SecurityException("没有原设备工具执行，不创建截图缓存");
        original.originalControl().check(); original.firstAdmission().checkCurrent();
        return artifactsForNativeWork().freeze(id, run, epoch, operation, observation, actualImage);
    }
    private DeviceIngress.Artifacts originalArtifacts() { return new DeviceIngress.Artifacts() {
        private DeviceWorkspaceArtifacts current() { DeviceWorkspaceArtifacts value = artifacts; if (closed || value == null) throw new SecurityException("这项成果当前不可用，未创建缓存或读取路径"); return value; }
        public JSONObject receive(PairingCoordinator.Permission permission) throws Exception { return current().ingress().receive(permission); }
        public JSONObject receiveHistory(PairingCoordinator.HistoryPermission history) throws Exception { return current().ingress().receiveHistory(history); }
        public JSONObject acceptedHistoryAudience(DeviceTaskEnvelope request) throws Exception { return current().ingress().acceptedHistoryAudience(request); }
    }; }
    public NativeDeviceWorkspaceHost host() { return host; }
    public NativeDeviceTaskScopeHost taskScopeHost() { return taskScopeHost; }
    /** Owner route sources consume actual native grant handles and fresh authenticated facts.
     * Neither method accepts a UI/model permission document or creates an authorization. */
    public List<PairingCoordinator.OutgoingGrant> nativeOutgoingGrants() throws Exception { return network.nativeOutgoingGrants(); }
    public JSONObject authenticatedCapabilities(String targetDeviceId) throws Exception { return network.readFreshAuthenticatedCapabilities(targetDeviceId); }
    public boolean hasMoreOutgoingGrants() { return network.hasMoreOutgoingGrants(); }
    public dev.pocketengine.core.CapabilityPort originalTaskDeviceTools(RunControl original) throws Exception {
        RunControl.HostRunIdentity run = java.util.Objects.requireNonNull(original).hostRunIdentity(); if (run == null) return null;
        JSONObject task = tasks.get(run.taskId(), false);
        if (!original.matchesHostRun(task.getString("id"), task.optString("runId"), task.optInt("coordinatorEpoch"))) throw new SecurityException("设备范围不属于这个原任务执行");
        if (TaskStoreDeviceTasks.deviceTask(task)) {
            DeviceGoalSession session = app.originalDeviceGoalSession(run.taskId());
            if (session == null || session.originalControl() != original) return null;
            session.checkCurrent(); DeviceExecutionAdmission admission = session.admission();
            if (admission.request().arguments().optJSONObject("delegationAuthorization") == null) return null;
            DeviceIdentity.LocalIdentity local = existingIdentity(); if (local == null) throw new SecurityException("原设备身份已丢失，未继承旧委派范围");
            return taskScopeHost.forwardedHostTools(admission, original, local, exchange());
        }
        JSONObject scope = task.optJSONObject("deviceSourceScope");
        if (scope == null || scope.optJSONArray("routes") == null || scope.getJSONArray("routes").length() == 0) return null;
        DeviceIdentity.LocalIdentity local = existingIdentity(); if (local == null) throw new SecurityException("原设备身份已丢失，未继承旧范围");
        return taskScopeHost.actualHostTools(original, local, exchange());
    }
    /** Called only by the original App stop hook on an existing bounded worker. App must
     * cancel its original control first and only enqueue this IO; never await it under App lock. */
    public JSONObject stopOriginalChildren(String id, RunControl originalCancelled) throws Exception {
        JSONObject links = tasks.deviceTasks().childOverview(id, 1);
        if (links.getJSONArray("links").length() == 0 && !links.optBoolean("hasMore")) return new JSONObject().put("taskId", id).put("managementOnly", true).put("noDeviceChildren", true).put("allStopped", true);
        DeviceIdentity.LocalIdentity local = existingIdentity(); if (local == null) throw new SecurityException("本机设备身份不存在，下游停止结果仍未确认");
        NativeDeviceChildStopHost original = new NativeDeviceChildStopHost(tasks, local, new NativeDeviceChildStopHost.NativeStops() {
            public PairingCoordinator.OutgoingGrant currentManagementGrant(String target, String grant) throws Exception { return network.currentManagementGrant(target, grant); }
            public DeviceCapabilityPort.Reply sendManagement(DeviceTaskEnvelope request, TaskStoreDeviceTasks.OutboundSendTicket ticket, RunControl ioCancellation) throws Exception { return network.once(request.targetDeviceId(), request, ticket, ioCancellation); }
        });
        try { return original.stopOriginalChildren(id, originalCancelled); } finally { app.pushTask(id); changed(); }
    }
    private DeviceTaskCoordinator.NativeHost originalTaskHost() { return new DeviceTaskCoordinator.NativeHost() {
        public void acceptTools(String id, DeviceExecutionAdmission admission) throws Exception { app.acceptDeviceTools(id, admission); }
        public void acceptGoal(String id, DeviceExecutionAdmission admission) throws Exception { app.acceptDeviceGoal(id, admission); }
        public void controlRequested(String id, String action) throws Exception { app.deviceControlRequested(id, action); }
        public DeviceOwnerInputs ordinaryInputs(){return app.deviceOwnerInputs();}
        public void ordinaryInputAccepted(DeviceOwnerInputs.Scope original,JSONObject accepted)throws Exception{app.deviceOwnerInputAccepted(original,accepted);}
    }; }
    public DeviceNativeRoute.Exchange exchange() { return new DeviceNativeRoute.Exchange() {
        public DeviceCapabilityPort.Reply once(String target, DeviceTaskEnvelope request, RunControl original) throws Exception { return network.once(target, request, original); }
        public DeviceCapabilityPort.Reply once(String target, DeviceTaskEnvelope request, TaskStoreDeviceTasks.OutboundSendTicket ticket, RunControl original) throws Exception { return network.once(target, request, ticket, original); }
    }; }
    void changed() { try { app.publish(new JSONObject().put("type", "device_workspace_changed")); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); } }
    synchronized DeviceIdentity.LocalIdentity existingIdentity() throws Exception {
        if (closed) throw new IllegalStateException("原设备工作区已关闭");
        DeviceIdentity.LocalIdentity current = DeviceIdentity.loadExisting(app);
        if (current == null) {
            if (identity != null) throw new SecurityException("本机设备身份已变化，请重新打开应用核对");
            return null;
        }
        if (identity != null && (!identity.deviceId().equals(current.deviceId()) || !identity.instanceId().equals(current.instanceId())
                || !identity.certificateFingerprint().equals(current.certificateFingerprint())))
            throw new SecurityException("本机设备身份已变化，请重新打开应用核对");
        if (identity == null) { identity = current; coordinator = new PairingCoordinator(trust, current.deviceId(), current.instanceId()); }
        return identity;
    }
    /** Called ONLY by explicit enable/start-pairing actions, never diagnostics or an inbound JSON. */
    synchronized DeviceIdentity.LocalIdentity enableIdentity() throws Exception {
        DeviceIdentity.LocalIdentity current = existingIdentity();
        if (current == null) { identity = DeviceIdentity.loadOrCreate(app); coordinator = new PairingCoordinator(trust, identity.deviceId(), identity.instanceId()); }
        return identity;
    }
    PairingCoordinator pairing() throws Exception {
        if (existingIdentity() == null || coordinator == null) throw new IllegalStateException("请先启用设备功能或开始配对");
        return coordinator;
    }
    private JSONObject snapshot() throws Exception {
        long now = System.currentTimeMillis(); DeviceIdentity.LocalIdentity local = existingIdentity();
        JSONObject original = trust.workspaceSnapshot(now), localView = new JSONObject(); JSONObject receiver = app.deviceReceiverState();
        localView.put("receiverEnabled", network.ownerEnabled()).put("listenerReady", receiver.optBoolean("listening"))
                .put("receiverReason", receiverReason(receiver));
        if (local != null) localView.put("deviceId", local.deviceId()).put("instanceId", local.instanceId()).put("certificateFingerprint", local.certificateFingerprint());
        JSONArray peers = original.getJSONArray("devices"); List<PairingCoordinator.OutgoingGrant> receivedGrants = network.nativeOutgoingGrants();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i); String id = peer.getString("deviceId");
            peer.put("label", "设备 " + id.substring(Math.max(0, id.length() - 4)));
            DeviceTransportStatus.Snapshot connection = network.current(id);
            peer.put("connection", new JSONObject().put("state", connection.state().name()).put("path", connection.path().name())
                    .put("localListenerReady", connection.localListenerReady()).put("reconnectClientRunning", connection.reconnectClientRunning())
                    .put("unconfirmedTransportWrite", connection.unconfirmedTransportWrite()).put("observedAt", connection.observedAt()));
            JSONObject capability = network.capabilities(id); if (capability != null) peer.put("capabilitySnapshot", capability);
            JSONArray received = new JSONArray(); boolean moreReceived = false;
            for (PairingCoordinator.OutgoingGrant nativeGrant : receivedGrants) if (id.equals(nativeGrant.targetDeviceId())) {
                if (received.length() >= 32) { moreReceived = true; continue; }
                JSONObject scope = nativeGrant.grant(), safe = new JSONObject();
                for (String field : new String[]{"grantId", "authorizationEpoch", "issuedAt", "expiresAt", "capabilities", "toolNames", "resources", "allowedOrigins", "allowDelegation", "maxDelegationDepth", "toolBudget", "modelBudget", "forwardingScope", "historyRead"}) if (scope.has(field)) safe.put(field, scope.get(field));
                received.put(safe.put("receivedScope", true));
            }
            peer.put("receivedGrantScopes", received).put("hasMoreReceivedGrantScopes", moreReceived);
            JSONArray scopes = peer.optJSONArray("grantScopes");
            for (int j = 0; scopes != null && j < scopes.length(); j++) {
                JSONObject scope = scopes.getJSONObject(j), originalGrant = trust.grant(scope.getString("grantId"));
                if (originalGrant != null && originalGrant.optJSONArray("allowedOrigins") != null) scope.put("allowedOrigins", new JSONArray(originalGrant.getJSONArray("allowedOrigins").toString()));
                if (local == null || originalGrant == null || !local.deviceId().equals(originalGrant.optString("localDeviceId"))) {
                    scope.put("state", "identity_changed").put("historyState", "identity_changed").put("budgetState", "unavailable"); continue;
                }
                try { scope.put("budgetStatus", tasks.deviceTasks().grantBudgetStatus(id, scope.getString("grantId"))); }
                catch (SecurityException changed) { scope.put("budgetState", "unavailable"); }
            }
        }
        JSONArray pending = new JSONArray();
        if (local != null) for (PairingCoordinator.Candidate candidate : pairing().pendingCandidates(now)) {
            JSONObject safe = candidate.display(), receipt = pairing().candidateReceipt(candidate, now);
            safe.put("candidateId", candidate.inviteId()).put("state", receipt.getString("state"))
                    .put("reviewAvailable", "pending_owner_confirmation".equals(receipt.getString("state")));
            pending.put(safe);
        }
        int hiddenPending = network.appendOutgoingPairings(pending);
        return new JSONObject().put("format", NativeDeviceWorkspaceHost.FORMAT).put("capturedAt", now).put("local", localView)
                .put("peers", peers).put("pendingPairings", pending)
                .put("pairingOffersToRecover", local == null ? new JSONArray() : pairing().pendingLocalPairingOffers(now)).put("committedPairingsToRecover", local == null ? new JSONArray() : pairing().committedPairingRecoveryCandidates(now, 8)).put("hasMorePeers", original.optBoolean("hasMore"))
                .put("hasMorePendingPairings", hiddenPending > 0).put("hiddenPendingPairingCount", hiddenPending)
                .put("hasMoreOutgoingGrants", network.hasMoreOutgoingGrants()).put("taskLinks", tasks.deviceTasks().workspaceTaskLinks(0, 100));
    }
    private static String receiverReason(JSONObject state) {
        switch (state.optString("state")) {
            case "starting": return "正在开启接收";
            case "stopping": return "正在关闭接收，不再接受新任务";
            case "stop_unconfirmed": return "接收通道关闭结果待确认，请刷新核对";
            case "not_started": return "已申请启用，正在等待前台服务";
            default: return "";
        }
    }
    private final class Receiver implements NativeDeviceWorkspaceHost.ReceiverOwner {
        public NativeDeviceWorkspaceHost.Availability enableAvailability() {
            try {
                JSONObject state = app.deviceReceiverState(); String status = state.optString("state");
                if (network.ownerEnabled() && !"disabled".equals(status) && !"stopped".equals(status)) return NativeDeviceWorkspaceHost.Availability.blocked("设备接收已启用");
                if (!"disabled".equals(status) && !"stopped".equals(status)) return NativeDeviceWorkspaceHost.Availability.blocked("请先确认上一次接收通道已经关闭");
                JSONObject notifications = app.phoneReadiness().optJSONObject("taskNotificationsForStart");
                if (notifications == null || !notifications.optBoolean("ready")) return NativeDeviceWorkspaceHost.Availability.blocked("请先允许任务通知，以便随时停止设备接收");
                return NativeDeviceWorkspaceHost.Availability.ready();
            } catch (Exception unavailable) { return NativeDeviceWorkspaceHost.Availability.blocked("本机接收条件暂不可用，请检查通知与设备设置"); }
        }
        public NativeDeviceWorkspaceHost.Availability disableAvailability() { return network.ownerEnabled() ? NativeDeviceWorkspaceHost.Availability.ready() : NativeDeviceWorkspaceHost.Availability.blocked("当前未启用设备接收"); }
        public void setEnabled(boolean enabled) throws Exception { if (enabled) network.enable(); else network.disable(); changed(); }
    }
    private final class Pairing implements NativeDeviceWorkspaceHost.PairingOwner {
        public NativeDeviceWorkspaceHost.Availability inviteAvailability() {
            if (!network.isListening()) return NativeDeviceWorkspaceHost.Availability.blocked("请先启用设备接收，再创建邀请");
            try { return DeviceEndpointResolver.localLanAddresses().isEmpty() ? NativeDeviceWorkspaceHost.Availability.blocked("没有可用的本机网络地址，请连接局域网") : NativeDeviceWorkspaceHost.Availability.ready(); }
            catch (Exception unavailable) { return NativeDeviceWorkspaceHost.Availability.blocked("本机网络地址暂不可用"); }
        }
        public List<String> recoveryAddresses() throws Exception { return DeviceEndpointResolver.localLanAddresses(); }
        public NativeDeviceWorkspaceHost.RecoveryEndpointAction openCommittedRecovery(String id, String address) throws Exception { return network.openCommittedRecovery(id, address); }
        public void recoverPairing(String id, String host, int port) throws Exception { network.recoverPairing(id, host, port); changed(); }
        public NativeDeviceWorkspaceHost.AbandonAction reviewPairingAbandonment(String id) throws Exception {
            JSONObject selected = null; JSONArray rows = pairing().pendingLocalPairingOffers(System.currentTimeMillis());
            for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).optString("inviteId"))) selected = rows.getJSONObject(i);
            if (selected == null) throw new SecurityException("这次待配对已改变，请核对当前设备；已完成配对应单独撤销信任");
            PairingCoordinator.LocalOfferAbandonmentReview original = pairing().reviewLocalPairingOfferAbandonment(id, selected.getString("candidateDigest"), System.currentTimeMillis());
            return new NativeDeviceWorkspaceHost.AbandonAction() {
                public JSONObject view() throws Exception { return original.display(); }
                public void confirm() throws Exception { pairing().abandonLocalPairingOffer(original, true, System.currentTimeMillis()); network.forgetPendingPairing(id); changed(); }
            };
        }
        public List<String> localAddresses() throws Exception { return DeviceEndpointResolver.localLanAddresses(); }
        public NativeDeviceWorkspaceHost.Availability joinAvailability() { return NativeDeviceWorkspaceHost.Availability.ready(); }
        public String createInvitation(String selectedAddress) throws Exception { return network.createInvitation(enableIdentity(), selectedAddress); }
        public NativeDeviceWorkspaceHost.PairingReviewAction inspectInvitation(String invitation) throws Exception { return network.inspectInvitation(invitation); }
        public void advancePairing(String id) throws Exception { network.advancePairing(id); changed(); }
        public NativeDeviceWorkspaceHost.PairingReviewAction reviewCandidate(String id) throws Exception {
            for (PairingCoordinator.Candidate candidate : pairing().pendingCandidates(System.currentTimeMillis())) if (id.equals(candidate.inviteId())) return candidateReview(candidate, null);
            throw new SecurityException("这次配对已失效，请让另一台设备重新发起");
        }
        public NativeDeviceWorkspaceHost.Availability connectAvailability(String peerId) throws Exception { return network.connectAvailability(peerId); }
        public NativeDeviceWorkspaceHost.Availability disconnectAvailability(String peerId) throws Exception { return network.disconnectAvailability(peerId); }
        public void connect(String peerId) throws Exception { network.connectPeer(peerId); changed(); }
        public void disconnect(String peerId) throws Exception { network.disconnectPeer(peerId); changed(); }
        public void refreshPeerFacts(String peerId) throws Exception { network.refreshAuthenticatedCapabilities(peerId); }
        public NativeDeviceWorkspaceHost.ConnectionReviewAction reviewConnectionUpdate(String peerId, String invitation) throws Exception { return network.reviewConnectionUpdate(peerId, invitation); }
        public NativeDeviceWorkspaceHost.GrantReviewAction reviewPeerGrant(String peerId) throws Exception { return grants.edit(peerId); }
        public NativeDeviceWorkspaceHost.Availability revokeAvailability(String peerId) throws Exception {
            JSONObject peer = trust.peer(peerId);
            return peer == null || peer.optBoolean("revoked") ? NativeDeviceWorkspaceHost.Availability.blocked("这台设备当前不在受信任列表") : NativeDeviceWorkspaceHost.Availability.ready();
        }
        public void revoke(String peerId) throws Exception { pairing().revokeLocally(peerId, System.currentTimeMillis()); network.revoke(peerId); changed(); }
    }
    NativeDeviceWorkspaceHost.PairingReviewAction candidateReview(PairingCoordinator.Candidate candidate, DeviceWorkspaceGrantOwner.Review reviewed) {
        return new NativeDeviceWorkspaceHost.PairingReviewAction() {
            public JSONObject view() throws Exception { JSONObject value = candidate.display(); if (reviewed == null) decorateGrantInputs(value, candidate.deviceId()); else value.put("grantReview", grants.view(reviewed)); return value; }
            public NativeDeviceWorkspaceHost.PairingReviewAction reviewGrant(NativeDeviceWorkspaceHost.GrantChoice selection) throws Exception { return candidateReview(candidate, reviewGrantFor(candidate.deviceId(), selection)); }
            public void confirmBothFingerprints() throws Exception { if (reviewed == null) throw new SecurityException("请先选择权限范围"); reviewed.requireCurrent(); network.confirmCandidate(candidate, reviewed.grant()); changed(); }
            public void reject() throws Exception { pairing().rejectCandidateLocally(candidate, System.currentTimeMillis()); changed(); }
        };
    }
    JSONObject decorateGrantInputs(JSONObject safe, String peerId) throws Exception { return grants.decorate(safe, peerId); }
    DeviceWorkspaceGrantOwner.Review reviewGrantFor(String peerId, NativeDeviceWorkspaceHost.GrantChoice choice) throws Exception { return grants.review(peerId, choice); }
    JSONObject grantView(DeviceWorkspaceGrantOwner.Review review) throws Exception { return grants.view(review); }
    private final class Goals implements NativeDeviceWorkspaceHost.GoalOwner {
        public NativeDeviceWorkspaceHost.Availability reviewAvailability(String id) throws Exception {
            JSONObject task = tasks.get(id, false);
            if (!TaskStoreDeviceTasks.deviceTask(task) || !"GOAL".equals(task.optString("deviceExecutionKind")) || !app.hasPendingDeviceGoal(id)) return NativeDeviceWorkspaceHost.Availability.blocked("当前没有待确认的设备目标");
            if (!task.optString("runId").isEmpty() || task.optBoolean("cancelRequested")) return NativeDeviceWorkspaceHost.Availability.blocked("任务状态已变化，请查看执行结果");
            if (app.activeId() != null) return NativeDeviceWorkspaceHost.Availability.blocked("这台手机正在执行另一项任务，请先完成或停止它");
            return NativeDeviceWorkspaceHost.Availability.ready();
        }
        public NativeDeviceWorkspaceHost.GoalReviewAction review(String id) throws Exception {
            reviewAvailability(id).require(); TaskStoreDeviceTasks.GoalModelReview review = app.reviewDeviceGoalModel(id);
            return new NativeDeviceWorkspaceHost.GoalReviewAction() {
                public JSONObject view() throws Exception {
                    JSONObject view = review.view(), task = tasks.get(id, false), context = task.optJSONObject("deviceContextSnapshot");
                    view.put("goal", task.optString("goal"));
                    if (context != null) view.put("contextSummary", context.optString("summary")).put("contextArtifactCount", context.optJSONArray("artifacts") == null ? 0 : context.getJSONArray("artifacts").length()).put("contextRetention", context.optString("retention"));
                    return view;
                }
                public void confirm(boolean future) throws Exception { app.confirmDeviceGoalModel(review, future); changed(); }
            };
        }
    }
    private final class Controls implements NativeDeviceWorkspaceHost.TaskControl {
        public NativeDeviceWorkspaceHost.Availability stopAvailability(String id) throws Exception {
            JSONObject task = tasks.get(id, false); RunControl control = app.control(id);
            if (pendingCancelAvailability(id).available) return NativeDeviceWorkspaceHost.Availability.ready();
            if (!TaskStoreDeviceTasks.deviceTask(task) || !id.equals(app.activeId()) || control == null || !control.matchesHostRun(id, task.optString("runId"), task.optInt("coordinatorEpoch"))) return NativeDeviceWorkspaceHost.Availability.blocked("这项任务当前没有可停止的本机执行");
            if (control.isCancelled()) return NativeDeviceWorkspaceHost.Availability.blocked("停止请求已发出，等待执行端确认");
            return NativeDeviceWorkspaceHost.Availability.ready();
        }
        public NativeDeviceWorkspaceHost.Availability pendingCancelAvailability(String id) throws Exception {
            return app.hasPendingDeviceGoalCancellation(id) ? NativeDeviceWorkspaceHost.Availability.ready()
                    : NativeDeviceWorkspaceHost.Availability.blocked("没有本机已接收但从未开始的可取消目标");
        }
        public void cancelPendingGoal(String id) throws Exception {
            // Explicit local click only. Original SQL supplies the opaque same-revision proof;
            // no retained Admission, current peer grant, key or JSON reconstruction is needed.
            pendingCancelAvailability(id).require();
            TaskStoreDeviceTasks.PendingGoalCancellation original = app.reviewPendingDeviceGoalCancellation(id);
            if (!id.equals(original.taskId())) throw new SecurityException("未启动目标取消审阅不属于这项原任务");
            app.confirmPendingDeviceGoalCancellation(original); changed();
        }
        public NativeDeviceWorkspaceHost.Availability resultReviewAvailability(String id) throws Exception {
            JSONObject task = tasks.get(id, false), result = task.optJSONObject("deviceGoalResult");
            return TaskStoreDeviceTasks.deviceTask(task) && "GOAL".equals(task.optString("deviceExecutionKind")) && result != null && !result.optBoolean("closed") && result.optBoolean("nativeRunnerExited") && result.optBoolean("nativeDispatchClosed") && task.optString("runId").equals(result.optString("runId")) && task.optInt("coordinatorEpoch") == result.optInt("executorEpoch")
                    ? NativeDeviceWorkspaceHost.Availability.ready() : NativeDeviceWorkspaceHost.Availability.blocked("当前没有已结束执行的待确认成果");
        }
        public NativeDeviceWorkspaceHost.ResultAction reviewResult(String id) throws Exception {
            resultReviewAvailability(id).require(); JSONObject task = tasks.get(id, false);
            TaskStoreDeviceTasks.GoalResultReview nativeReview = app.reviewDeviceGoalResult(id, task.getString("runId"), task.getInt("coordinatorEpoch"));
            return new NativeDeviceWorkspaceHost.ResultAction() {
                public JSONObject view() throws Exception { return nativeReview.view(); }
                public void confirm(boolean completed) throws Exception { app.confirmDeviceGoalResult(nativeReview, completed); changed(); }
            };
        }
        public JSONObject historyLinks(String id) throws Exception { tasks.get(id, false); return tasks.deviceTasks().childOverview(id, 24); }
        public NativeDeviceWorkspaceHost.HistoryAction openHistory(String id, String operation) throws Exception {
            DeviceIdentity.LocalIdentity local = existingIdentity(); if (local == null) throw new SecurityException("本机设备身份不存在，不能继承原结果读取范围");
            NativeDeviceTaskHistoryHost original = new NativeDeviceTaskHistoryHost(tasks, local, new NativeDeviceTaskHistoryHost.NativeReads() {
                public PairingCoordinator.HistoryRoute currentHistoryRoute(String target, String grant) throws Exception { return network.currentHistoryRoute(target, grant); }
                public DeviceCapabilityPort.Reply readHistory(DeviceTaskEnvelope request, RunControl ioCancellation) throws Exception { return network.readOwnerHistory(request, ioCancellation); }
            });
            NativeDeviceTaskHistoryHost.Window nativeWindow = original.open(id, operation);
            return new NativeDeviceWorkspaceHost.HistoryAction() {
                public JSONObject view() throws Exception { return nativeWindow.view(); }
                public JSONObject query() throws Exception { JSONObject result = nativeWindow.query(); app.pushTask(id); changed(); return result; }
                public void close() { nativeWindow.close(); }
            };
        }
        private RunControl exact(String id) throws Exception {
            JSONObject task = tasks.get(id, false); RunControl original = app.control(id);
            if (!TaskStoreDeviceTasks.deviceTask(task) || !id.equals(app.activeId()) || original == null || !original.matchesHostRun(id, task.optString("runId"), task.optInt("coordinatorEpoch"))) throw new SecurityException("没有这个设备任务的当前执行，不会新建执行");
            return original;
        }
        public NativeDeviceWorkspaceHost.Availability pauseAvailability(String id) throws Exception {
            try { RunControl original = exact(id); if (original.isCancelled()) return NativeDeviceWorkspaceHost.Availability.blocked("已请求停止，不能暂停或恢复"); return original.isPaused() ? NativeDeviceWorkspaceHost.Availability.blocked("当前已暂停") : NativeDeviceWorkspaceHost.Availability.ready(); }
            catch (SecurityException absent) { return NativeDeviceWorkspaceHost.Availability.blocked(absent.getMessage()); }
        }
        public NativeDeviceWorkspaceHost.Availability resumeAvailability(String id) throws Exception {
            try {
                RunControl original = exact(id); if (original.isCancelled() || !original.isPaused()) return NativeDeviceWorkspaceHost.Availability.blocked("当前没有可继续的暂停执行");
                if (tasks.hasUnresolved(id)) return NativeDeviceWorkspaceHost.Availability.blocked("先核对未确认的操作结果");
                JSONObject task = tasks.get(id, false); JSONArray approvals = task.optJSONArray("approvals");
                for (int i = 0; approvals != null && i < approvals.length(); i++) if ("pending".equals(approvals.getJSONObject(i).optString("state"))) return NativeDeviceWorkspaceHost.Availability.blocked("先处理这次执行的操作审批");
                DeviceGoalSession goal = app.originalDeviceGoalSession(id); if (goal != null) { if (goal.originalControl() != original) throw new SecurityException("原设备模型执行已变化"); goal.modelApproval().checkCurrent(original); }
                return NativeDeviceWorkspaceHost.Availability.ready();
            } catch (SecurityException absent) { return NativeDeviceWorkspaceHost.Availability.blocked(absent.getMessage()); }
        }
        private void control(String id, String action) throws Exception { exact(id); JSONObject task = tasks.get(id, false); app.controlDeviceExecution(id, task.getString("runId"), task.getInt("coordinatorEpoch"), action); changed(); }
        public void pause(String id) throws Exception { pauseAvailability(id).require(); control(id, "pause"); }
        public void resume(String id) throws Exception { resumeAvailability(id).require(); control(id, "resume"); }
        public NativeDeviceWorkspaceHost.ApprovalAction reviewApproval(String id, String approvalId) throws Exception {
            RunControl original = exact(id); DeviceGoalSession goal = app.originalDeviceGoalSession(id);
            if (goal == null || goal.originalControl() != original) throw new SecurityException("这项审批没有原设备目标执行器"); goal.modelApproval().checkCurrent(original);
            JSONObject task = tasks.get(id, false), selected = null; JSONArray rows = task.optJSONArray("approvals");
            for (int i = 0; rows != null && i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i); if (approvalId.equals(row.optString("id")) && task.getString("runId").equals(row.optString("runId")) && "pending".equals(row.optString("state")) && row.optLong("expiresAt") > System.currentTimeMillis()) selected = new JSONObject(row.toString()); }
            if (selected == null || !selected.optString("argumentsHash").matches("[a-f0-9]{64}")) throw new SecurityException("这项准确操作审批已失效，请重新读取任务");
            final JSONObject approval = selected; final String run = task.getString("runId"); final int epoch = task.getInt("coordinatorEpoch");
            return new NativeDeviceWorkspaceHost.ApprovalAction() {
                public JSONObject view() throws Exception { return new JSONObject(approval.toString()).put("taskId", id).put("approvalId", approvalId).put("runId", run).put("executorEpoch", epoch).put("resourceBinding", goal.admission().request().resourceBinding()).put("origin", goal.admission().origin()); }
                public void decide(boolean approved) throws Exception { if (app.originalDeviceGoalSession(id) != goal || exact(id) != original) throw new SecurityException("原设备执行已经变化"); goal.modelApproval().checkCurrent(original); app.decideDeviceApproval(id, run, epoch, approvalId, approval.getString("argumentsHash"), approved); changed(); }
            };
        }
        public void requestStop(String id) throws Exception { stopAvailability(id).require(); if (pendingCancelAvailability(id).available) { cancelPendingGoal(id); return; } JSONObject task = tasks.get(id, false); app.controlDeviceExecution(id, task.getString("runId"), task.getInt("coordinatorEpoch"), "cancel"); changed(); }
    }
    /** All options derive from actual local launchable apps and original private checkouts. A
     * model/peer cannot provide this list. Selecting an option never authorizes the whole workspace. */
    JSONArray localGrantOptions() throws Exception {
        JSONArray result = new JSONArray(); long expiry = System.currentTimeMillis() + 30L * 86400_000;
        JSONObject readiness = app.phoneReadiness();
        boolean mainReady = readiness.optJSONObject("mainObservation") != null && readiness.getJSONObject("mainObservation").optBoolean("ready");
        boolean backgroundReady = app.features.module("display") instanceof dev.pocketengine.phone.display.DisplayModule && ((dev.pocketengine.phone.display.DisplayModule) app.features.module("display")).isConnected() && readiness.optJSONObject("backgroundExecution") != null && readiness.getJSONObject("backgroundExecution").optBoolean("ready");
        boolean backgroundCapture = backgroundReady && readiness.optJSONObject("backgroundCapture") != null && readiness.getJSONObject("backgroundCapture").optBoolean("ready");
        if (mainReady || backgroundReady) {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = new ArrayList<>(app.getPackageManager().queryIntentActivities(launcher, 0));
            apps.sort(Comparator.comparing(row -> row.loadLabel(app.getPackageManager()).toString())); Set<String> seen = new HashSet<>();
            for (ResolveInfo row : apps) {
                if (row.activityInfo == null || !seen.add(row.activityInfo.packageName)) continue;
                String packageName = row.activityInfo.packageName, label = row.loadLabel(app.getPackageManager()).toString();
                JSONObject resource = new JSONObject().put("screenMode", "main_screen").put("packageNames", new JSONArray().put(packageName)).put("allowWholeDisplayCapture", false).put("allowWholeDisplayInput", false);
                JSONArray tools = new JSONArray(), declarations = DeviceToolsRuntime.toolDeclarations(); for (int i = 0; i < declarations.length(); i++) { String name = declarations.getJSONObject(i).getString("toolName"); if (!name.matches("phone_(tap|swipe|back|home)")) tools.put(name); }
                if (mainReady) result.put(option("app_" + DeviceProtocol.sha256(packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "指定应用节点 · " + label, resource, tools, false, expiry));
                JSONObject execution = readiness.optJSONObject("mainExecution");
                if (mainReady && execution != null && execution.optBoolean("ready")) {
                    JSONObject screen = new JSONObject(resource.toString()).put("allowWholeDisplayInput", true);
                    JSONArray screenTools = new JSONArray(); for (int i = 0; i < declarations.length(); i++) screenTools.put(declarations.getJSONObject(i).getString("toolName"));
                    JSONObject whole = option("screen_" + DeviceProtocol.sha256(packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "整屏输入 · " + label, screen, screenTools, false, expiry);
                    whole.put("effectSummary", "允许点击坐标、滑动和系统返回，可能影响其他可见应用；整屏截图另行授权。"); result.put(whole);
                }
                if (backgroundReady) {
                    JSONObject background = new JSONObject(resource.toString()).put("screenMode", "background");
                    result.put(option("background_app_" + DeviceProtocol.sha256(packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "后台应用节点 · " + label, background, tools, false, expiry).put("canGrantWholeDisplayCapture", backgroundCapture));
                    if (readiness.optJSONObject("backgroundInput") != null && readiness.getJSONObject("backgroundInput").optBoolean("ready")) {
                        JSONArray wholeTools = new JSONArray(); for (int i = 0; i < declarations.length(); i++) wholeTools.put(declarations.getJSONObject(i).getString("toolName"));
                        JSONObject whole = option("background_screen_" + DeviceProtocol.sha256(packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "后台整屏输入 · " + label, new JSONObject(background.toString()).put("allowWholeDisplayInput", true), wholeTools, false, expiry)
                                .put("canGrantWholeDisplayCapture", backgroundCapture).put("effectSummary", "允许在独立后台屏点击坐标、滑动和系统返回；不会改用主屏，截图另行授权。"); result.put(whole);
                    }
                }
                if (seen.size() >= 256) break;
            }
        }
        result.put(option("context_goal", "处理目标，不访问本机项目", new JSONObject().put("screenMode", "capabilities"), new JSONArray().put("phone_request_user").put("phone_finish"), true, expiry));
        if (app.features.module("local") instanceof LocalModule) {
            LocalModule local = (LocalModule) app.features.module("local"); JSONArray checkouts = local.ownerArchiveCheckoutsForHost(), runtimes = local.projectRuntimesForOwner();
            for (int i = 0; i < Math.min(64, checkouts.length()); i++) for (int j = 0; j < runtimes.length(); j++) {
                JSONObject checkout = checkouts.getJSONObject(i), runtime = runtimes.getJSONObject(j); String runtimeId = runtime.getString("runtimeId");
                JSONObject resource = new JSONObject().put("screenMode", "capabilities").put("projectId", checkout.getString("projectId")).put("checkoutId", checkout.getString("checkoutId")).put("runtimeId", runtimeId);
                JSONArray tools = DeviceCapabilityRegistry.supportedProjectToolNames().put("phone_request_user").put("phone_finish");
                JSONObject option = option("project_" + DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(resource)), "开发 " + checkout.getString("name") + " · " + runtime.optString("name", runtimeId), resource, tools, true, expiry);
                option.put("isolation", false); result.put(option);
            }
        }
        return result;
    }
    private JSONObject option(String id, String label, JSONObject resource, JSONArray tools, boolean goal, long expiry) throws Exception {
        JSONArray capabilities = new JSONArray().put("device.describe").put("operation.get").put("task.get").put("task.events").put("task.acquireControl").put("task.pause").put("task.cancel").put("operation.cancel").put("task.respond").put(goal ? "task.submitGoal" : "tool.invoke");
        if(goal)capabilities.put("task.answerQuestion").put("task.steer");
        return new JSONObject().put("optionId", id).put("label", label).put("capabilities", capabilities).put("toolNames", tools).put("resources", new JSONArray().put(resource))
                .put("canGrantWholeDisplayCapture", "main_screen".equals(resource.optString("screenMode")) && canCaptureMain()).put("toolBudgetMin", goal ? 0 : 1).put("modelBudgetMin", goal ? 1 : 0).put("toolBudgetMax", 400).put("modelBudgetMax", goal ? 1000 : 0).put("expiresAtMax", expiry).put("allowDelegation", false).put("maxDelegationDepth", 0)
                .put("historyReadExpiresAtMax", expiry).put("historyReadMethods", new JSONArray().put("operation.get").put("task.get").put("task.events"));
    }
    private static boolean canCaptureMain() {
        dev.pocketengine.phone.PhoneAccessibilityService service = dev.pocketengine.phone.PhoneAccessibilityService.get();
        if (service == null) return false;
        android.accessibilityservice.AccessibilityServiceInfo info = service.getServiceInfo();
        return info != null && (info.getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
    }
    JSONObject reviewLocalGrantFor(String peerId, NativeDeviceWorkspaceHost.GrantChoice selected) throws Exception {
        DeviceIdentity.LocalIdentity local = existingIdentity(); if (local == null) throw new SecurityException("本机设备身份不存在，请先明确开始配对"); JSONObject source = null; JSONArray options = localGrantOptions();
        for (int i = 0; i < options.length(); i++) if (selected.optionId.equals(options.getJSONObject(i).getString("optionId"))) source = options.getJSONObject(i);
        long now = System.currentTimeMillis();
        if (source == null || selected.toolBudget < source.getInt("toolBudgetMin") || selected.modelBudget < source.getInt("modelBudgetMin") || selected.toolBudget > source.getInt("toolBudgetMax") || selected.modelBudget > source.getInt("modelBudgetMax")
                || selected.expiresAt <= now || selected.expiresAt > source.getLong("expiresAtMax") || selected.allowDelegation)
            throw new SecurityException("所选权限、资源或次数已经变化，请重新选择");
        JSONArray resources = new JSONArray(source.getJSONArray("resources").toString());
        if (selected.wholeDisplayCapture) {
            if (!source.optBoolean("canGrantWholeDisplayCapture") || resources.length() != 1 || !PairingCoordinator.contains(source.getJSONArray("toolNames"), "phone_observe")) throw new SecurityException("本机当前不能为这个范围授予整屏截图");
            resources.getJSONObject(0).put("allowWholeDisplayCapture", true);
            source.getJSONArray("capabilities").put("artifact.describe").put("artifact.readChunk");
        }
        JSONObject grant = new JSONObject().put("grantId", UUID.randomUUID().toString()).put("peerDeviceId", peerId).put("localDeviceId", local.deviceId()).put("authorizationEpoch", 1)
                .put("issuedAt", now).put("expiresAt", selected.expiresAt).put("capabilities", source.getJSONArray("capabilities")).put("toolNames", source.getJSONArray("toolNames"))
                .put("resources", resources).put("allowedOrigins", new JSONArray().put(peerId)).put("allowDelegation", false).put("maxDelegationDepth", 0)
                .put("toolBudget", selected.toolBudget).put("modelBudget", selected.modelBudget);
        if (selected.historyReadExpiresAt > 0) {
            if (selected.historyReadExpiresAt <= now || selected.historyReadExpiresAt > source.getLong("historyReadExpiresAtMax")) throw new SecurityException("结果读取期限超过本机允许范围");
            for (String method : selected.historyReadMethods) if (!PairingCoordinator.contains(source.getJSONArray("historyReadMethods"), method)) throw new SecurityException("本机没有授予这项结果读取方法");
            grant.put("historyRead", new JSONObject().put("expiresAt", selected.historyReadExpiresAt).put("methods", new JSONArray(selected.historyReadMethods)));
        }
        PairingCoordinator.validateGrant(grant, local.deviceId(), peerId, now); return new JSONObject(grant.toString());
    }
    JSONObject localGrantView(JSONObject grant) throws Exception {
        JSONObject view = new JSONObject(grant.toString()).put("digest", DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(grant))).put("allowedOriginSummary", "来源：本次配对的设备");
        JSONArray options = localGrantOptions();
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.getJSONObject(i);
            if (DeviceProtocol.canonical(option.getJSONArray("resources")).equals(DeviceProtocol.canonical(grant.getJSONArray("resources")))) {
                view.put("label", option.getString("label")); if (option.has("isolation")) view.put("isolation", option.getBoolean("isolation")); break;
            }
        }
        return view;
    }

    DeviceIngress ingress(DeviceReceiverServiceResource receiver) throws Exception {
        DeviceIdentity.LocalIdentity local = existingIdentity(); if (local == null) throw new SecurityException("设备身份未启用");
        DeviceTaskCoordinator coordinator = new DeviceTaskCoordinator(tasks, originalTaskHost(), local.deviceId(), local.instanceId());
        DeviceTaskCoordinator.AcceptanceGate accept = receivingGate(receiver);
        return new DeviceIngress(local.deviceId(), local.instanceId(), pairing(), this::checkAcceptedPolicy,
                new DeviceCapabilityRegistry(local.deviceId(), local.instanceId(), (DeviceCapabilityRegistry.ScopedNativeHealth) this::health), coordinator, originalArtifacts(), accept);
    }
    static DeviceTaskCoordinator.AcceptanceGate receivingGate(DeviceReceiverServiceResource exactReceiver) {
        return exactReceiver == null ? originalAcceptance -> { throw new SecurityException("这台手机未开启新的设备任务接收"); }
                : exactReceiver::acceptNewRequest;
    }
    private void checkAcceptedPolicy(DeviceTaskEnvelope request, DeviceExecutionAdmission.Kind kind) throws Exception {
        JSONObject resource = request.resourceBinding(); String mode = resource.optString("screenMode");
        if (kind == DeviceExecutionAdmission.Kind.DEVICE_TOOLS) {
            JSONObject readiness = app.phoneReadiness(); String field = "background".equals(mode) ? "backgroundExecution" : "read".equals(request.toolInvocation().effect()) ? "mainObservation" : "mainExecution";
            JSONObject state = readiness.optJSONObject(field); if (state == null || !state.optBoolean("ready")) throw new SecurityException("这台手机的操作权限或指定屏幕暂未就绪");
        } else {
            if (!"capabilities".equals(mode)) throw new SecurityException("目标只能在你选择的能力范围执行");
            if (resource.has("checkoutId")) {
                if (!(app.features.module("local") instanceof LocalModule)) throw new SecurityException("本机项目环境未就绪");
                LocalModule local = (LocalModule) app.features.module("local"); JSONObject checkout = local.projectCheckoutForHost(resource.getString("checkoutId"));
                if (!resource.getString("projectId").equals(checkout.optString("projectId")) || checkout.has("teamProjectCopy")) throw new SecurityException("所选项目范围已经变化");
                local.projectRuntimeForHost(resource.getString("runtimeId"));
            }
        }
    }
    private JSONObject health(PairingCoordinator.DiscoveryPermission permission) throws Exception {
        JSONObject readiness = app.phoneReadiness(), result = new JSONObject().put("receiverEnabled", network.ownerEnabled()).put("busy", app.activeId() != null)
                .put("goalOwnerReviewAvailable", true).put("scopedProjectPortInstalled", app.features.module("local") instanceof LocalModule).put("nativeDeviceRoutesReady", app.hasOriginalDeviceSourceRoutes());
        JSONObject exactCurrentGrant = null;
        if (permission != null) {
            JSONArray peers = trust.workspaceSnapshot(System.currentTimeMillis()).getJSONArray("devices");
            for (int i = 0; i < peers.length(); i++) if (permission.callerDeviceId().equals(peers.getJSONObject(i).getString("deviceId"))) {
                JSONArray scopes = peers.getJSONObject(i).optJSONArray("grantScopes");
                for (int j = 0; scopes != null && j < scopes.length(); j++) {
                    JSONObject scope = scopes.getJSONObject(j);
                    if ("active".equals(scope.optString("state"))) {
                        JSONObject candidate = trust.grant(scope.getString("grantId"));
                        if (candidate != null && PairingCoordinator.contains(candidate.optJSONArray("capabilities"), "tool.invoke")) {
                            if (exactCurrentGrant != null) throw new SecurityException("本机当前应用授权存在多个范围，请分别核对");
                            exactCurrentGrant = candidate;
                        }
                    }
                }
            }
        }
        for (String mode : new String[]{"main_screen", "background"}) {
            JSONObject state = readiness.optJSONObject(mode.equals("main_screen") ? "mainExecution" : "backgroundExecution"), exactResource = null;
            if (exactCurrentGrant != null) { JSONArray resources = exactCurrentGrant.getJSONArray("resources"); for (int i = 0; i < resources.length(); i++) if (mode.equals(resources.getJSONObject(i).optString("screenMode"))) exactResource = resources.getJSONObject(i); }
            JSONObject nativeState = new JSONObject().put("installed", state != null).put("authorized", exactResource != null)
                    .put("ready", state != null && state.optBoolean("ready")).put("reason", state != null && state.optBoolean("ready") ? "" : "permission_or_resource_unavailable");
            if (exactResource != null) nativeState.put("resourceBinding", exactResource).put("toolNames", exactCurrentGrant.getJSONArray("toolNames"));
            result.put(mode, nativeState);
        }
        if (permission != null) {
            result.put("grantReceipts", pairing().discoveryGrantReceipts(existingIdentity(), permission, System.currentTimeMillis()));
            JSONObject status = tasks.deviceTasks().goalModelPolicyStatus(permission); boolean ready = false; JSONArray policies = status.optJSONArray("policies");
            for (int i = 0; policies != null && i < policies.length(); i++) {
                JSONObject policy = policies.getJSONObject(i), binding = policy.optJSONObject("modelBinding");
                if (binding == null) continue; ModelConfig metadata = app.settings.apiMetadataForPricing(binding.optString("connectionId"), policy.optString("model"));
                if (metadata != null && !metadata.usesAccount() && ModelRoutePlan.configurationDigest(metadata).equals(policy.optString("configurationDigest"))) ready = true;
            }
            result.put("goalPolicy", new JSONObject().put("callerDeviceId", permission.callerDeviceId()).put("configured", status.optBoolean("configured")).put("apiModelReady", ready));
        }
        return result;
    }
    @Override public void close() throws Exception { closed = true; host.close(); try { network.close(); } finally { DeviceWorkspaceArtifacts bytesOwner = artifacts; if (bytesOwner != null) bytesOwner.close(); } }
}
