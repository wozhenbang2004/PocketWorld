package dev.pocketengine.features.devices;

import java.util.Objects;
import org.json.JSONObject;

/** Native owner UI adapter. Diagnostics are display data, never a Permission, Peer or Admission.
 * All producers come from the original application/TaskStore and retained transport. No database,
 * executor, key creation, network connection or receiver opt-in is created by opening a page. */
public final class NativeDeviceWorkspaceHost implements AutoCloseable {
    public static final String FORMAT = "pocketworld.device-workspace.v1";
    public static final class Availability {
        public final boolean available;
        public final String reason;
        private Availability(boolean available, String reason) { this.available = available; this.reason = reason; }
        public static Availability ready() { return new Availability(true, ""); }
        public static Availability blocked(String reason) {
            if (blank(reason)) throw new IllegalArgumentException("Blocked native action needs its actual reason");
            return new Availability(false, reason);
        }
        void require() { if (!available) throw new IllegalStateException(reason); }
    }
    @FunctionalInterface public interface Diagnostics {
        /** Current native local identity (read-only, no loadOrCreate), original trust overview,
         * connection snapshots and short-lived capability facts. No DER, tokens or credentials.
         * Required: format, capturedAt, local, peers[], pendingPairings[]. Empty means this read
         * window has no rows, not proof of connectivity, permission or execution readiness. */
        JSONObject read() throws Exception;
    }
    public interface ReceiverOwner {
        Availability enableAvailability();
        Availability disableAvailability();
        /** Explicit owner click. Disable is not evidence that an accepted remote task stopped. */
        void setEnabled(boolean enabled) throws Exception;
    }
    public interface PairingOwner {
        default org.json.JSONArray relayLeases() throws Exception { return new org.json.JSONArray(); }
        default RelayLeaseAction reviewRelayLease(String raw) throws Exception { throw new SecurityException("本机中继配置尚未接线"); }
        default void removeRelayLease(String serviceBase, String inviterDeviceId) throws Exception { throw new SecurityException("本机中继配置尚未接线"); }
        default ConnectionReviewAction reviewRelayConnection(String peerId, String serviceBase) throws Exception { throw new SecurityException("本机中继连接尚未接线"); }
        default LanDiscovery openLanDiscovery(boolean advertise) throws Exception { throw new SecurityException("本机尚未接通局域网发现"); }
        Availability inviteAvailability();
        /** Explicit click only. Return the one-use short-lived invitation for display, not logs. */
        java.util.List<String> localAddresses() throws Exception;
        String createInvitation(String selectedLocalAddress) throws Exception;
        void advancePairing(String candidateId) throws Exception;
        java.util.List<String> recoveryAddresses() throws Exception;
        RecoveryEndpointAction openCommittedRecovery(String originalInviteId, String selectedLocalAddress) throws Exception;
        void recoverPairing(String originalInviteId, String literalHost, int port) throws Exception;
        AbandonAction reviewPairingAbandonment(String originalInviteId) throws Exception;
        Availability joinAvailability();
        /** Isolated exact-pin server-auth bootstrap only; not business TLS or a Permission. */
        PairingReviewAction inspectInvitation(String invitation) throws Exception;
        /** Resolve an opaque native Candidate retained by the original PairingCoordinator. */
        PairingReviewAction reviewCandidate(String candidateId) throws Exception;
        Availability connectAvailability(String peerId) throws Exception;
        Availability disconnectAvailability(String peerId) throws Exception;
        void connect(String peerId) throws Exception;
        void disconnect(String peerId) throws Exception;
        void refreshPeerFacts(String peerId) throws Exception;
        ConnectionReviewAction reviewConnectionUpdate(String peerId, String uniqueInvitation) throws Exception;
        GrantReviewAction reviewPeerGrant(String peerId) throws Exception;
        Availability revokeAvailability(String peerId) throws Exception;
        void revoke(String peerId) throws Exception;
    }
    /** A selection is untrusted owner input, NOT a grant, signature or native approval. The source
     * resolves optionId against its current local capability/resource allowlist and revalidates all
     * ceilings, then returns a NEW opaque action bound to that exact native-reviewed grant digest. */
    public static final class GrantChoice {
        public final String optionId;
        public final int toolBudget, modelBudget, maxDelegationDepth;
        public final long expiresAt;
        public final boolean allowDelegation;
        public final long historyReadExpiresAt;
        public final java.util.List<String> historyReadMethods;
        public final java.util.List<String> additionalOriginOptionIds, forwardingOptionIds;
        public final int forwardingToolBudget, forwardingModelBudget;
        public final boolean wholeDisplayCapture;
        public GrantChoice(String optionId, int toolBudget, int modelBudget, long expiresAt, boolean allowDelegation, int maxDelegationDepth) {
            this(optionId, toolBudget, modelBudget, expiresAt, allowDelegation, maxDelegationDepth, 0, java.util.Collections.emptyList());
        }
        public GrantChoice(String optionId, int toolBudget, int modelBudget, long expiresAt, boolean allowDelegation, int maxDelegationDepth, long historyReadExpiresAt, java.util.List<String> historyReadMethods) {
            this(optionId, toolBudget, modelBudget, expiresAt, allowDelegation, maxDelegationDepth, historyReadExpiresAt, historyReadMethods, java.util.Collections.emptyList(), java.util.Collections.emptyList(), 0, 0);
        }
        public GrantChoice(String optionId, int toolBudget, int modelBudget, long expiresAt, boolean allowDelegation, int maxDelegationDepth, long historyReadExpiresAt, java.util.List<String> historyReadMethods,
                java.util.List<String> additionalOriginOptionIds, java.util.List<String> forwardingOptionIds, int forwardingToolBudget, int forwardingModelBudget) {
            this(optionId, toolBudget, modelBudget, expiresAt, allowDelegation, maxDelegationDepth, historyReadExpiresAt, historyReadMethods, additionalOriginOptionIds, forwardingOptionIds, forwardingToolBudget, forwardingModelBudget, false);
        }
        public GrantChoice(String optionId, int toolBudget, int modelBudget, long expiresAt, boolean allowDelegation, int maxDelegationDepth, long historyReadExpiresAt, java.util.List<String> historyReadMethods,
                java.util.List<String> additionalOriginOptionIds, java.util.List<String> forwardingOptionIds, int forwardingToolBudget, int forwardingModelBudget, boolean wholeDisplayCapture) {
            if (blank(optionId) || optionId.length() > 128 || toolBudget < 0 || toolBudget > 400 || modelBudget < 0 || modelBudget > 1000
                    || expiresAt < 1 || maxDelegationDepth < 0 || maxDelegationDepth > 2 || !allowDelegation && maxDelegationDepth != 0)
                throw new IllegalArgumentException("配对授权选择不在本机有限范围");
            this.optionId = optionId; this.toolBudget = toolBudget; this.modelBudget = modelBudget; this.expiresAt = expiresAt;
            this.allowDelegation = allowDelegation; this.maxDelegationDepth = maxDelegationDepth;
            java.util.List<String> methods = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(historyReadMethods)));
            if (historyReadExpiresAt < 0 || methods.size() > 5 || (historyReadExpiresAt == 0) != methods.isEmpty() || new java.util.HashSet<>(methods).size() != methods.size()) throw new IllegalArgumentException("结果读取范围选择不完整");
            for (String method : methods) if (blank(method) || method.length() > 64) throw new IllegalArgumentException("结果读取方法无效");
            this.historyReadExpiresAt = historyReadExpiresAt; this.historyReadMethods = methods;
            this.additionalOriginOptionIds = choiceIds(additionalOriginOptionIds); this.forwardingOptionIds = choiceIds(forwardingOptionIds);
            if (forwardingToolBudget < 0 || forwardingToolBudget > toolBudget || forwardingModelBudget < 0 || forwardingModelBudget > modelBudget
                    || allowDelegation != (!this.forwardingOptionIds.isEmpty() || !this.additionalOriginOptionIds.isEmpty()) || this.forwardingOptionIds.isEmpty() && (forwardingToolBudget != 0 || forwardingModelBudget != 0)) throw new IllegalArgumentException("继续委派的目标和有限次数必须明确选择");
            this.forwardingToolBudget = forwardingToolBudget; this.forwardingModelBudget = forwardingModelBudget; this.wholeDisplayCapture = wholeDisplayCapture;
        }
    }
    private static java.util.List<String> choiceIds(java.util.List<String> source) {
        java.util.List<String> ids = new java.util.ArrayList<>(Objects.requireNonNull(source));
        if (ids.size() > 8 || new java.util.HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("设备范围选项超出本机有限选择");
        for (String id : ids) if (blank(id) || id.length() > 128) throw new IllegalArgumentException("设备范围选项身份无效");
        return java.util.Collections.unmodifiableList(ids);
    }
    public interface AbandonAction {
        JSONObject view() throws Exception;
        void confirm() throws Exception;
    }
    public interface RecoveryEndpointAction extends AutoCloseable {
        JSONObject view() throws Exception;
        void close();
    }
    public interface GrantReviewAction {
        JSONObject view() throws Exception;
        GrantReviewAction reviewGrant(GrantChoice choice) throws Exception;
        void confirm() throws Exception;
    }
    public interface PairingReviewAction {
        /** Must include inviteId, inviter/joiner device IDs, both complete fingerprints, expiresAt.
         * Initial view also supplies native grantOptions[] with optionId/label/exact capabilities,
         * toolNames/resources/allowedOrigins and toolBudgetMax/modelBudgetMax/expiresAtMax,
         * allowDelegation/maxDelegationDepth. No implicit full-permission default.
         * A reviewed view supplies grantReview with exact digest/capabilities/resources/budgets/
         * expiresAt/delegation. It must not include invite bearer credentials. */
        JSONObject view() throws Exception;
        PairingReviewAction reviewGrant(GrantChoice selection) throws Exception;
        /** Adapter carries the SAME native Candidate/confirmation, not a reconstructed JSON proof.
         * This only confirms both fingerprints for subsequent exact-pin mutual TLS. */
        void confirmBothFingerprints() throws Exception;
        void reject() throws Exception;
    }
    public interface LanDiscovery extends AutoCloseable {
        JSONObject view() throws Exception;
        ConnectionReviewAction reviewConnection(String peerId, String candidateId) throws Exception;
        void close();
    }
    public interface ConnectionReviewAction {
        /** Safe original paired identity and address summary only; no invitation token or grant. */
        JSONObject view() throws Exception;
        void confirm() throws Exception;
    }
    public interface RelayLeaseAction extends AutoCloseable {
        JSONObject view() throws Exception;
        void confirm() throws Exception;
        void close();
    }
    public interface GoalOwner {
        Availability reviewAvailability(String originalTaskId) throws Exception;
        /** Carry the original TaskStoreDeviceTasks.GoalModelReview object, never JSON approval. */
        GoalReviewAction review(String originalTaskId) throws Exception;
    }
    public interface GoalReviewAction {
        /** Native review's safe view(): model/source, exact origin/grant/revision, scope and limits. */
        JSONObject view() throws Exception;
        void confirm(boolean useForFutureGoals) throws Exception;
    }
    public interface TaskControl {
        Availability stopAvailability(String originalTaskId) throws Exception;
        Availability pauseAvailability(String originalTaskId) throws Exception;
        Availability resumeAvailability(String originalTaskId) throws Exception;
        Availability pendingCancelAvailability(String originalTaskId) throws Exception;
        Availability resultReviewAvailability(String originalTaskId) throws Exception;
        void cancelPendingGoal(String originalTaskId) throws Exception;
        ResultAction reviewResult(String originalTaskId) throws Exception;
        JSONObject historyLinks(String originalTaskId) throws Exception;
        HistoryAction openHistory(String originalTaskId, String originalOperationId) throws Exception;
        void pause(String originalTaskId) throws Exception;
        void resume(String originalTaskId) throws Exception;
        default JSONObject resumeRequest(String originalTaskId)throws Exception{return null;}
        default void decideResumeRequest(String originalTaskId,String requestId,long reviewRevision,boolean approved)throws Exception{throw new SecurityException("原设备恢复确认入口未配置");}
        ApprovalAction reviewApproval(String originalTaskId, String originalApprovalId) throws Exception;
        /** Original task/controller only. A sent request must not be labelled as stopped. */
        void requestStop(String originalTaskId) throws Exception;
    }

    public interface HistoryAction extends AutoCloseable {
        JSONObject view() throws Exception;
        JSONObject query() throws Exception;
        default JSONObject requestStop() throws Exception { throw new SecurityException("这个历史入口不支持原操作停止"); }
        void close();
    }
    public interface ResultAction {
        JSONObject view() throws Exception;
        void confirm(boolean completed) throws Exception;
    }
    public interface ApprovalAction {
        JSONObject view() throws Exception;
        void decide(boolean approved) throws Exception;
    }
    @FunctionalInterface public interface DesktopCurrent { void require() throws Exception; }
    @FunctionalInterface public interface DesktopOwner {
        DesktopAction open(String deviceId, String grantId, JSONObject resourceBinding,
                DesktopCurrent current) throws Exception;
    }
    public interface DesktopAction extends AutoCloseable {
        String id();
        JSONObject view() throws Exception;
        JSONObject request(String action, JSONObject arguments) throws Exception;
        void close();
    }
    private final java.util.Set<Window> windows = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final Diagnostics diagnostics;
    private final ReceiverOwner receiver;
    private final PairingOwner pairing;
    private final GoalOwner goals;
    private final TaskControl control;
    private final DesktopOwner desktopOwner;
    public NativeDeviceWorkspaceHost(Diagnostics diagnostics, ReceiverOwner receiver, PairingOwner pairing,
            GoalOwner goals, TaskControl control) {
        this(diagnostics, receiver, pairing, goals, control, (device, grant, binding, current) -> {
            throw new SecurityException("桌面观看尚未接入原生设备通道");
        });
    }
    public NativeDeviceWorkspaceHost(Diagnostics diagnostics, ReceiverOwner receiver, PairingOwner pairing,
            GoalOwner goals, TaskControl control, DesktopOwner desktopOwner) {
        this.diagnostics = Objects.requireNonNull(diagnostics); this.receiver = Objects.requireNonNull(receiver);
        this.pairing = Objects.requireNonNull(pairing); this.goals = Objects.requireNonNull(goals);
        this.control = Objects.requireNonNull(control); this.desktopOwner = Objects.requireNonNull(desktopOwner);
    }
    public synchronized Window openOwnerWindow() { if (closed) throw new IllegalStateException("设备工作区已关闭，请重新打开应用核对"); Window window = new Window(); windows.add(window); return window; }
    @Override public synchronized void close() { if (closed) return; closed = true; for (Window window : new java.util.ArrayList<>(windows)) window.close(); windows.clear(); }

    /** A review is scoped to its native owner page. Closing/back invalidates unconsumed handles;
     * completion of a mutation already dispatched still has to be read from the actual producers. */
    public final class Window implements AutoCloseable {
        private volatile boolean closed;
        private volatile HistoryWindow history;
        private volatile DesktopWindow desktop;
        private final java.util.concurrent.atomic.AtomicLong desktopGeneration = new java.util.concurrent.atomic.AtomicLong();
        private volatile RecoveryEndpoint recoveryEndpoint;
        private volatile LanDiscovery lanDiscovery;
        private volatile RelayLeaseReview relayReview;
        private final java.util.concurrent.atomic.AtomicLong reviewGeneration = new java.util.concurrent.atomic.AtomicLong();
        private Window() {}
        private long nextReview() { HistoryWindow old = history; history = null; if (old != null) old.close(); RecoveryEndpoint endpoint = recoveryEndpoint; recoveryEndpoint = null; if (endpoint != null) endpoint.close(); RelayLeaseReview relay = relayReview; relayReview = null; if (relay != null) relay.source.close(); return reviewGeneration.incrementAndGet(); }
        private void current() { if (closed || NativeDeviceWorkspaceHost.this.closed) throw new IllegalStateException("原设备页面已关闭，请重新打开并审阅"); }
        public JSONObject snapshot() throws Exception { return snapshot(new String[0]); }
        public JSONObject snapshot(String[] originalTaskIds) throws Exception {
            current(); JSONObject value = Objects.requireNonNull(diagnostics.read());
            if (!FORMAT.equals(value.optString("format")) || value.optLong("capturedAt") < 1
                    || value.optJSONObject("local") == null || value.optJSONArray("peers") == null
                    || value.optJSONArray("pendingPairings") == null
                    || value.getJSONArray("peers").length() > 64 || value.getJSONArray("pendingPairings").length() > 8)
                throw new IllegalStateException("原生设备状态尚未完整读取，未推断已连接或已授权");
            value = copy(value);
            org.json.JSONArray peers = value.getJSONArray("peers");
            for (int i = 0; i < peers.length(); i++) {
                JSONObject peer = peers.getJSONObject(i);
                String id = peer.getString("deviceId");
                peer.put("actions", new JSONObject().put("revoke", availability(pairing.revokeAvailability(id)))
                        .put("connect", availability(pairing.connectAvailability(id))).put("disconnect", availability(pairing.disconnectAvailability(id))));
            }
            Availability invitationAction = pairing.inviteAvailability();
            JSONObject actions = new JSONObject().put("enable", availability(receiver.enableAvailability()))
                    .put("disable", availability(receiver.disableAvailability())).put("invite", availability(invitationAction))
                    .put("join", availability(pairing.joinAvailability())).put("localAddresses", new org.json.JSONArray(invitationAction.available ? pairing.localAddresses() : java.util.Collections.emptyList()))
                    .put("relayLeases", pairing.relayLeases());
            JSONObject taskActions = new JSONObject();
            if (originalTaskIds != null) for (int i = 0; i < Math.min(40, originalTaskIds.length); i++) {
                String id = originalTaskIds[i]; if (blank(id)) continue;
                taskActions.put(id, new JSONObject().put("stop", availability(control.stopAvailability(id)))
                        .put("goalReview", availability(goals.reviewAvailability(id))).put("pause", availability(control.pauseAvailability(id))).put("resume", availability(control.resumeAvailability(id)))
                        .put("pendingCancel", availability(control.pendingCancelAvailability(id))).put("resultReview", availability(control.resultReviewAvailability(id))));
            }
            value = copy(value).put("actions", actions).put("taskActions", taskActions)
                    .put("taskActionCount", taskActions.length()).put("hasMoreTaskActions", originalTaskIds != null && originalTaskIds.length > 40);
            LanDiscovery discovery = lanDiscovery;
            if (discovery != null) value.put("lanDiscovery", discovery.view());
            return value;
        }
        public synchronized void startLanDiscovery(boolean advertise) throws Exception {
            current(); stopLanDiscovery();
            LanDiscovery opened = pairing.openLanDiscovery(advertise);
            try { current(); lanDiscovery = opened; } catch (Exception closed) { opened.close(); throw closed; }
        }
        public synchronized void stopLanDiscovery() { LanDiscovery old = lanDiscovery; if (old != null) old.close(); }
        public ConnectionReview reviewDiscoveredConnection(String peerId, String candidateId) throws Exception {
            current(); LanDiscovery original = lanDiscovery; if (original == null) throw new SecurityException("请先开启本次局域网发现");
            return connectionReview(peerId, original.reviewConnection(peerId, candidateId));
        }
        public Availability receiverAvailability(boolean enabled) { current(); return enabled ? receiver.enableAvailability() : receiver.disableAvailability(); }
        public void setReceiverEnabled(boolean enabled) throws Exception { current(); receiverAvailability(enabled).require(); receiver.setEnabled(enabled); }
        public Availability inviteAvailability() { current(); return pairing.inviteAvailability(); }
        public Availability joinAvailability() { current(); return pairing.joinAvailability(); }
        public RelayLeaseReview reviewRelayLease(String raw) throws Exception {
            current(); if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("中继租约为空或超过4 KiB");
            RelayLeaseAction source = Objects.requireNonNull(pairing.reviewRelayLease(raw));
            try { current(); JSONObject safe = source.view();
                if (safe.has("routeToken") || safe.has("routeId") || !safe.optBoolean("memoryOnly") || safe.optBoolean("devicePermissionGranted") || safe.optLong("expiresAt") <= System.currentTimeMillis()) throw new SecurityException("中继配置必须为无设备权限的临时原生租约");
                RelayLeaseReview review = new RelayLeaseReview(this, nextReview(), source, copy(safe)); relayReview = review; return review;
            } catch (Exception rejected) { source.close(); throw rejected; }
        }
        public void confirmRelayLease(RelayLeaseReview review) throws Exception {
            current(); if (review == null || review.window != this || review.generation != reviewGeneration.get() || relayReview != review) throw new SecurityException("中继配置审阅已关闭或被替换");
            consume(review.generation); relayReview = null; try { review.source.confirm(); } finally { review.source.close(); }
        }
        public void removeRelayLease(String serviceBase, String inviterDeviceId) throws Exception { current(); nextReview(); pairing.removeRelayLease(serviceBase, inviterDeviceId); }
        public String createInvitation(String address) throws Exception { current(); inviteAvailability().require(); return Objects.requireNonNull(pairing.createInvitation(address)); }
        public void advancePairing(String id) throws Exception { current(); pairing.advancePairing(id); }
        public java.util.List<String> recoveryAddresses() throws Exception { current(); return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(pairing.recoveryAddresses())); }
        public RecoveryEndpoint openCommittedRecovery(String id, String address) throws Exception {
            current(); RecoveryEndpointAction source = Objects.requireNonNull(pairing.openCommittedRecovery(id, address));
            try { current(); JSONObject safe = source.view(); boolean relay = "relay".equals(safe.optString("kind")); if (!id.equals(safe.optString("inviteId")) || (!relay && (safe.optInt("port") < 1 || safe.optInt("port") > 65535)) || relay && !safe.optString("serviceBase").matches("(?:wss|https)://[^\\s]+") || safe.optLong("expiresAt") <= System.currentTimeMillis() || !safe.optBoolean("listening")) throw new SecurityException("原配对结果恢复端口尚未就绪");
                RecoveryEndpoint opened = new RecoveryEndpoint(this, nextReview(), source, copy(safe)); recoveryEndpoint = opened; return opened;
            } catch (Exception invalid) { source.close(); throw invalid; }
        }
        public void recoverPairing(String id, String literalHost, int port) throws Exception { current(); boolean relay = literalHost != null && literalHost.matches("(?:wss|https)://[^\\s]+"); if (blank(id) || blank(literalHost) || (relay ? literalHost.length() > 512 || port != 0 : literalHost.length() > 64 || port < 1 || port > 65535)) throw new IllegalArgumentException("请输入另一台设备显示的准确地址和恢复端口，或已配置中继服务"); pairing.recoverPairing(id, literalHost, port); }
        public AbandonReview reviewPairingAbandonment(String id) throws Exception {
            current(); AbandonAction source = Objects.requireNonNull(pairing.reviewPairingAbandonment(id)); JSONObject safe = source.view();
            if (!id.equals(safe.optString("inviteId")) || !safe.optBoolean("localOnly") || safe.optBoolean("remoteRevocationAcknowledged") || !safe.optString("candidateDigest").matches("[a-f0-9]{64}")) throw new SecurityException("没有这次本机待配对记录的准确来源");
            return new AbandonReview(this, nextReview(), source, copy(safe));
        }
        public void confirmPairingAbandonment(AbandonReview review) throws Exception {
            current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("放弃本机待配对的核对窗口已关闭");
            consume(review.generation); review.source.confirm();
        }
        public PairReview inspectInvitation(String raw) throws Exception {
            current(); joinAvailability().require();
            if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384 || blank(raw)) throw new IllegalArgumentException("邀请为空或超过16 KiB，请重新复制另一台设备的原邀请");
            return pairReview(pairing.inspectInvitation(raw));
        }
        public PairReview reviewCandidate(String id) throws Exception { current(); return pairReview(pairing.reviewCandidate(id)); }
        private PairReview pairReview(PairingReviewAction source) throws Exception {
            Objects.requireNonNull(source); JSONObject value = source.view();
            for (String key : new String[]{"inviterFingerprint", "joinerFingerprint"})
                if (!value.optString(key).matches("[a-f0-9]{64}")) throw new IllegalStateException("缺少完整双端指纹，不提供配对确认");
            if (blank(value.optString("inviteId")) || value.optLong("expiresAt") <= System.currentTimeMillis()) throw new IllegalStateException("原配对邀请已失效，请在另一台设备重新发起");
            return new PairReview(this, nextReview(), source, copy(value));
        }
        public PairReview reviewPairingGrant(PairReview review, GrantChoice selection) throws Exception {
            require(review); consume(review.generation); return pairReview(review.source.reviewGrant(Objects.requireNonNull(selection)));
        }
        public void confirmPairing(PairReview review) throws Exception {
            require(review); JSONObject grant = review.value.optJSONObject("grantReview");
            if (grant == null || !grant.optString("digest").matches("[a-f0-9]{64}") || grant.optJSONArray("capabilities") == null
                    || grant.optJSONArray("resources") == null || grant.optLong("expiresAt") <= System.currentTimeMillis())
                throw new SecurityException("准确授权范围尚未完成本机原生审阅，不确认或签署配对");
            consume(review.generation); review.source.confirmBothFingerprints();
        }
        public void rejectPairing(PairReview review) throws Exception { require(review); consume(review.generation); review.source.reject(); }
        private void consume(long expected) { if (!reviewGeneration.compareAndSet(expected, expected + 1)) throw new SecurityException("该审阅已提交或已关闭，请读取原回执后重新审阅"); }
        private void require(PairReview review) { current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("配对审阅不属于当前原生页面，请重新核对双端指纹"); }
        public GrantReview reviewPeerGrant(String peerId) throws Exception { current(); return grantReview(pairing.reviewPeerGrant(peerId)); }
        private GrantReview grantReview(GrantReviewAction source) throws Exception {
            JSONObject value = Objects.requireNonNull(source).view();
            if (!"grant_revision".equals(value.optString("reviewKind")) || blank(value.optString("deviceId")) || !value.optString("certificateFingerprint").matches("[a-f0-9]{64}")) throw new SecurityException("缺少原设备权限修改来源");
            return new GrantReview(this, nextReview(), source, copy(value));
        }
        public GrantReview reviewGrantRevision(GrantReview review, GrantChoice choice) throws Exception {
            requireGrant(review); consume(review.generation); return grantReview(review.source.reviewGrant(Objects.requireNonNull(choice)));
        }
        private void requireGrant(GrantReview review) { current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("权限修改核对不属于当前设备窗口"); }
        public void confirmGrantRevision(GrantReview review) throws Exception {
            requireGrant(review); JSONObject grant = review.value.optJSONObject("grantReview");
            if (grant == null || !grant.optString("digest").matches("[a-f0-9]{64}") || grant.optLong("expiresAt") <= System.currentTimeMillis()) throw new SecurityException("尚未核对准确权限修改");
            consume(review.generation); review.source.confirm();
        }
        public ConnectionReview reviewConnectionUpdate(String peerId, String raw) throws Exception {
            current(); if (raw == null || blank(raw) || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384) throw new IllegalArgumentException("请复制这台设备的有效邀请");
            return connectionReview(peerId, pairing.reviewConnectionUpdate(peerId, raw));
        }
        public ConnectionReview reviewRelayConnection(String peerId, String serviceBase) throws Exception {
            current(); if (blank(serviceBase) || serviceBase.length() > 512) throw new IllegalArgumentException("请选择主人已配置的准确中继服务");
            return connectionReview(peerId, pairing.reviewRelayConnection(peerId, serviceBase));
        }
        private ConnectionReview connectionReview(String peerId, ConnectionReviewAction source) throws Exception {
            ConnectionReviewAction action = Objects.requireNonNull(source); JSONObject safe = action.view();
            if (!peerId.equals(safe.optString("deviceId")) || !safe.optString("certificateFingerprint").matches("[a-f0-9]{64}")) throw new SecurityException("连接地址不属于原配对身份");
            return new ConnectionReview(this, nextReview(), action, copy(safe));
        }
        public void confirmConnectionUpdate(ConnectionReview review) throws Exception {
            current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("连接地址核对已关闭，请重新打开");
            consume(review.generation); review.source.confirm();
        }
        public void refreshPeerFacts(String peerId) throws Exception { current(); pairing.refreshPeerFacts(peerId); }
        public void connectPeer(String peerId) throws Exception { current(); pairing.connectAvailability(peerId).require(); pairing.connect(peerId); }
        public void disconnectPeer(String peerId) throws Exception { current(); pairing.disconnectAvailability(peerId).require(); pairing.disconnect(peerId); }
        public Availability revokeAvailability(String peerId) throws Exception { current(); return pairing.revokeAvailability(peerId); }
        public void revoke(String peerId) throws Exception { current(); revokeAvailability(peerId).require(); pairing.revoke(peerId); nextReview(); }
        public Availability goalReviewAvailability(String id) throws Exception { current(); return goals.reviewAvailability(id); }
        public GoalReview reviewGoal(String id) throws Exception {
            current(); goalReviewAvailability(id).require(); GoalReviewAction action = Objects.requireNonNull(goals.review(id));
            JSONObject value = action.view(); if (!id.equals(value.optString("taskId"))) throw new SecurityException("模型审阅不属于该原设备任务");
            return new GoalReview(this, nextReview(), action, copy(value));
        }
        public void confirmGoal(GoalReview review, boolean useForFutureGoals) throws Exception {
            current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("模型审阅已离开原生页面，请重新读取原任务");
            consume(review.generation); review.source.confirm(useForFutureGoals);
        }
        public JSONObject taskActions(String id) throws Exception { current(); return new JSONObject().put("capturedAt", System.currentTimeMillis()).put("stop", availability(control.stopAvailability(id))).put("pause", availability(control.pauseAvailability(id))).put("resume", availability(control.resumeAvailability(id))).put("resumeRequest",control.resumeRequest(id)).put("goalReview", availability(goals.reviewAvailability(id))).put("pendingCancel", availability(control.pendingCancelAvailability(id))).put("resultReview", availability(control.resultReviewAvailability(id))); }
        public void cancelPendingGoal(String id) throws Exception { current(); control.pendingCancelAvailability(id).require(); control.cancelPendingGoal(id); }
        public ResultReview reviewResult(String id) throws Exception {
            current(); control.resultReviewAvailability(id).require(); ResultAction source = Objects.requireNonNull(control.reviewResult(id)); JSONObject safe = source.view();
            if (!id.equals(safe.optString("taskId")) || blank(safe.optString("runId")) || safe.optInt("executorEpoch") < 1 || !safe.optString("resultDigest").matches("[a-f0-9]{64}") || !safe.optBoolean("historicalReviewOnly") || !safe.optBoolean("nativeRunnerExited") || !safe.optBoolean("nativeDispatchClosed")) throw new SecurityException("没有这次原执行已结束的准确结果");
            return new ResultReview(this, nextReview(), source, copy(safe));
        }
        public void confirmResult(ResultReview review, boolean completed) throws Exception {
            current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("结果核对已离开当前任务窗口");
            consume(review.generation); review.source.confirm(completed);
        }
        public void pauseTask(String id) throws Exception { current(); control.pauseAvailability(id).require(); control.pause(id); }
        public void resumeTask(String id) throws Exception { current(); control.resumeAvailability(id).require(); control.resume(id); }
        public void decideResumeRequest(String id,String requestId,long reviewRevision,boolean approved)throws Exception{current();control.decideResumeRequest(id,requestId,reviewRevision,approved);}
        public ApprovalReview reviewApproval(String id, String approvalId) throws Exception {
            current(); ApprovalAction source = Objects.requireNonNull(control.reviewApproval(id, approvalId)); JSONObject safe = source.view();
            if (!id.equals(safe.optString("taskId")) || !approvalId.equals(safe.optString("approvalId")) || !safe.optString("argumentsHash").matches("[a-f0-9]{64}") || blank(safe.optString("runId")) || safe.optInt("executorEpoch") < 1) throw new SecurityException("审批不属于这个原任务和准确参数");
            return new ApprovalReview(this, nextReview(), source, copy(safe));
        }
        public void decideApproval(ApprovalReview review, boolean approved) throws Exception {
            current(); if (review == null || review.window != this || review.generation != reviewGeneration.get()) throw new SecurityException("操作审批已离开当前原任务窗口");
            consume(review.generation); review.source.decide(approved);
        }
        public DesktopWindow openDesktop(String deviceId, String grantId, JSONObject binding) throws Exception {
            return openDesktop(deviceId, grantId, binding, () -> {});
        }
        public DesktopWindow openDesktop(String deviceId, String grantId, JSONObject binding, DesktopCurrent callerCurrent) throws Exception {
            Objects.requireNonNull(callerCurrent);
            current(); callerCurrent.require(); DeviceProtocol.deviceId(deviceId, "deviceId"); DeviceProtocol.identifier(grantId, "grantId");
            DeviceProtocol.validateResourceBinding("desktop-v2", binding);
            final long generation; final DesktopWindow old;
            synchronized (this) { current(); callerCurrent.require(); generation = desktopGeneration.incrementAndGet(); old = desktop; desktop = null; }
            if (old != null) old.close();
            DesktopCurrent guard = () -> {
                current(); callerCurrent.require(); if (desktopGeneration.get() != generation) throw new SecurityException("原桌面窗口已被替换");
            };
            DesktopAction source = desktopOwner.open(deviceId, grantId, copy(binding), guard);
            try {
                JSONObject view = source.view(); guard.require();
                if (!source.id().equals(view.optString("sessionId")) || !deviceId.equals(view.optString("deviceId"))
                        || !DeviceProtocol.resourceEquals(binding, view.optJSONObject("resourceBinding")))
                    throw new SecurityException("桌面窗口不是所选原设备与范围");
                DesktopWindow opened = new DesktopWindow(this, generation, source);
                synchronized (this) { guard.require(); desktop = opened; }
                return opened;
            } catch (Exception invalid) { source.close(); throw invalid; }
        }
        public void closeDesktop() {
            DesktopWindow old;
            synchronized (this) { desktopGeneration.incrementAndGet(); old = desktop; desktop = null; }
            if (old != null) old.close();
        }
        /** Exact-handle authority reduction, independent of a slow input/read lane.
         * A local fence does not certify that the remote cleanup has completed. */
        public JSONObject closeDesktop(String sessionId) throws Exception {
            final DesktopWindow old;
            synchronized (this) {
                current();
                old = desktop;
                if (old == null || !old.id().equals(sessionId))
                    throw new SecurityException("这项桌面会话已经关闭，未关闭其他新会话");
                desktopGeneration.incrementAndGet(); desktop = null;
            }
            old.close();
            return new JSONObject().put("sessionId", sessionId).put("closed", true).put("remoteCloseConfirmed", false);
        }
        public JSONObject historyLinks(String id) throws Exception { current(); return copy(control.historyLinks(id)); }
        public HistoryWindow openHistory(String id, String operationId) throws Exception {
            current(); HistoryAction source = Objects.requireNonNull(control.openHistory(id, operationId)); JSONObject safe;
            try { safe = source.view(); if (!id.equals(safe.optString("taskId")) || !operationId.equals(safe.optString("operationId"))) throw new SecurityException("不是这项原任务操作的历史窗口"); }
            catch (Exception invalid) { source.close(); throw invalid; }
            HistoryWindow opened = new HistoryWindow(this, nextReview(), source, copy(safe)); history = opened; return opened;
        }
        public Availability stopAvailability(String id) throws Exception { current(); return control.stopAvailability(id); }
        public void requestStop(String id) throws Exception { current(); stopAvailability(id).require(); control.requestStop(id); }
        public void discardReview() { if (!closed) nextReview(); }
        @Override public void close() { closed = true; windows.remove(this); stopLanDiscovery(); closeDesktop(); nextReview(); }
    }
    public static final class DesktopWindow implements AutoCloseable {
        private final Window window; private final long generation; private final DesktopAction source;
        private volatile boolean closed;
        private DesktopWindow(Window window, long generation, DesktopAction source) {
            this.window = window; this.generation = generation; this.source = source;
        }
        public String id() { return source.id(); }
        private void require() {
            window.current();
            if (closed || window.desktopGeneration.get() != generation) throw new SecurityException("原桌面观看窗口已关闭");
        }
        public JSONObject view() throws Exception { require(); JSONObject value = source.view(); require(); return copy(value); }
        public JSONObject request(String action, JSONObject arguments) throws Exception {
            require(); JSONObject value = source.request(action, copy(arguments)); require(); return copy(value);
        }
        @Override public void close() {
            synchronized (this) { if (closed) return; closed = true; }
            source.close();
        }
    }
    public static final class PairReview {
        private final Window window; private final long generation; private final PairingReviewAction source; private final JSONObject value;
        private PairReview(Window window, long generation, PairingReviewAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class GrantReview {
        private final Window window; private final long generation; private final GrantReviewAction source; private final JSONObject value;
        private GrantReview(Window window, long generation, GrantReviewAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class ConnectionReview {
        private final Window window; private final long generation; private final ConnectionReviewAction source; private final JSONObject value;
        private ConnectionReview(Window window, long generation, ConnectionReviewAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class RelayLeaseReview {
        private final Window window; private final long generation; private final RelayLeaseAction source; private final JSONObject value;
        private RelayLeaseReview(Window window, long generation, RelayLeaseAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class AbandonReview {
        private final Window window; private final long generation; private final AbandonAction source; private final JSONObject value;
        private AbandonReview(Window window, long generation, AbandonAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class RecoveryEndpoint implements AutoCloseable {
        private final Window window; private final long generation; private final RecoveryEndpointAction source; private final JSONObject value; private volatile boolean closed;
        private RecoveryEndpoint(Window window, long generation, RecoveryEndpointAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { window.current(); if (closed || generation != window.reviewGeneration.get()) throw new SecurityException("恢复端口窗口已关闭"); return copy(value); }
        public void close() { closed = true; source.close(); }
    }
    public static final class HistoryWindow implements AutoCloseable {
        private final Window window; private final long generation; private final HistoryAction source; private final JSONObject value; private volatile boolean closed;
        private HistoryWindow(Window window, long generation, HistoryAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        private void require() { window.current(); if (closed || generation != window.reviewGeneration.get()) throw new SecurityException("原结果读取窗口已关闭"); }
        public JSONObject view() throws Exception { require(); return copy(value); }
        public JSONObject query() throws Exception { require(); JSONObject next = source.query(); require(); return copy(next); }
        public JSONObject requestStop() throws Exception { require(); JSONObject next = source.requestStop(); require(); return copy(next); }
        public void close() { closed = true; source.close(); }
    }
    public static final class ResultReview {
        private final Window window; private final long generation; private final ResultAction source; private final JSONObject value;
        private ResultReview(Window window, long generation, ResultAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class ApprovalReview {
        private final Window window; private final long generation; private final ApprovalAction source; private final JSONObject value;
        private ApprovalReview(Window window, long generation, ApprovalAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    public static final class GoalReview {
        private final Window window; private final long generation; private final GoalReviewAction source; private final JSONObject value;
        private GoalReview(Window window, long generation, GoalReviewAction source, JSONObject value) { this.window = window; this.generation = generation; this.source = source; this.value = value; }
        public JSONObject view() throws Exception { return copy(value); }
    }
    // Java String.isBlank is not available on every supported Android API30 runtime.
    private static boolean blank(String value) {
        if (value == null || value.isEmpty()) return true;
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i); if (!Character.isWhitespace(point)) return false; i += Character.charCount(point);
        }
        return true;
    }
    private static JSONObject availability(Availability value) throws Exception { Objects.requireNonNull(value); return new JSONObject().put("available", value.available).put("reason", value.reason); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
}
