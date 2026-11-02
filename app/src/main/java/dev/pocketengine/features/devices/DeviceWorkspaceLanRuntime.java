package dev.pocketengine.features.devices;

import android.content.Context;
import android.content.SharedPreferences;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.devices.transport.*;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-native LAN/explicit WSS lifecycle over retained transport primitives. No task store, Runner, account,
 * alternate retry scheduler, trust-all context, automatic opt-in or business-payload replay. */
public final class DeviceWorkspaceLanRuntime implements AutoCloseable, DeviceTransportStatus.Provider {
    private final DeviceWorkspaceNativeOwner owner;
    private final SharedPreferences preferences;
    private final Executor originalIngressWorker;
    private final Semaphore inboundSlots = new Semaphore(8);
    private final Map<String, PeerChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, PeerChannel> outboundChannels = new ConcurrentHashMap<>();
    private final Map<String, PeerChannel> capabilityChannels = new ConcurrentHashMap<>();
    private final Map<String, ReconnectingDeviceClient> clients = new ConcurrentHashMap<>();
    private final Map<String, ReconnectingDeviceClient> bulkClients = new ConcurrentHashMap<>();
    private final Map<String, PeerChannel> bulkChannels = new ConcurrentHashMap<>();
    private final Map<String, PeerChannel> outboundBulkChannels = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> capabilities = new ConcurrentHashMap<>();
    private final Map<String, PendingRead> descriptions = new ConcurrentHashMap<>();
    private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();
    private final Map<String, DevicePairingEndpoint> invitations = new ConcurrentHashMap<>();
    private final Map<String, PairingCompletionListener> recoveries = new ConcurrentHashMap<>();
    private final Map<String, RelayPairingEndpoint> relayRecoveries = new ConcurrentHashMap<>();
    private final Map<String, DeviceRelayLease> relayLeases = new ConcurrentHashMap<>();
    // Resource identity, not peer identity: an owner may replace a receiver-created client
    // with a separately approved source client for the SAME peer before receiver opt-out.
    private final Map<ReconnectingDeviceClient, ClientOwnership> clientOwners = new ConcurrentHashMap<>();
    private final Map<String, Outgoing> outgoing = new ConcurrentHashMap<>();
    // Opaque handles issued by the original PairingCoordinator, never reconstructed from UI JSON.
    private final Map<String, PairingCoordinator.OutgoingGrant> outgoingGrants = new ConcurrentHashMap<>();
    private volatile boolean outgoingGrantLimitReached;
    private volatile LanDeviceListener control, bulk;
    private volatile String networkOwnerToken = "";
    private volatile DeviceReceiverServiceResource receiver;
    private volatile DeviceIngress ingress;
    DeviceWorkspaceLanRuntime(DeviceWorkspaceNativeOwner owner, Executor originalIngressWorker) {
        this.owner = java.util.Objects.requireNonNull(owner); this.originalIngressWorker = java.util.Objects.requireNonNull(originalIngressWorker);
        // Configuration only: no tasks, credentials, certificates or invitation tokens are stored here.
        preferences = owner.app.getSharedPreferences("device-workspace-owner-config-v1", Context.MODE_PRIVATE);
    }
    public boolean ownerEnabled() { DeviceReceiverServiceResource resource = receiver; return preferences.getBoolean("receiverEnabled", false) && resource != null && !resource.stopped(); }
    public boolean isListening() { return ownerEnabled() && control != null && control.isListening() && bulk != null && bulk.isListening(); }
    private static String relayKey(String service, String inviter) { return service + "\n" + inviter; }
    private DeviceIdentity.LocalIdentity requireRelayIdentity() throws Exception {
        DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) throw new SecurityException("原设备身份不存在，请先启用设备功能"); return local;
    }
    private void currentRelay(DeviceRelayLease lease) throws Exception {
        DeviceIdentity.LocalIdentity local = requireRelayIdentity(); lease.requireCurrent(local.deviceId(), local.instanceId(), System.currentTimeMillis());
        if (relayLeases.get(relayKey(lease.serviceBase, lease.inviterDeviceId)) != lease) throw new SecurityException("原中继租约已替换");
    }
    public synchronized NativeDeviceWorkspaceHost.RelayLeaseAction reviewRelayLease(String raw) throws Exception {
        DeviceIdentity.LocalIdentity local = requireRelayIdentity();
        DeviceRelayLease reviewed = DeviceRelayLease.review(raw, local.deviceId(), local.instanceId(), System.currentTimeMillis());
        String key = relayKey(reviewed.serviceBase, reviewed.inviterDeviceId); DeviceRelayLease expected = relayLeases.get(key);
        return new NativeDeviceWorkspaceHost.RelayLeaseAction() {
            private boolean installed, consumed;
            public JSONObject view() throws Exception { reviewed.requireCurrent(local.deviceId(), local.instanceId(), System.currentTimeMillis()); return reviewed.view(); }
            public void confirm() throws Exception {
                synchronized (DeviceWorkspaceLanRuntime.this) {
                    if (consumed || relayLeases.get(key) != expected) throw new SecurityException("中继配置已变化，请重新核对"); consumed = true;
                    DeviceIdentity.LocalIdentity actual = requireRelayIdentity(); reviewed.requireCurrent(actual.deviceId(), actual.instanceId(), System.currentTimeMillis());
                    relayLeases.entrySet().removeIf(row -> { if (row.getValue().current(actual.deviceId(), actual.instanceId(), System.currentTimeMillis())) return false; row.getValue().close(); return true; });
                    if (!relayLeases.containsKey(key) && relayLeases.size() >= 4) throw new SecurityException("本机最多保留四份临时中继租约");
                    if (expected != null) try { stopClientsForLease(expected); } finally { expected.close(); }
                    relayLeases.put(key, reviewed); installed = true; owner.changed();
                }
            }
            public void close() { if (!installed) reviewed.close(); }
        };
    }
    public synchronized void removeRelayLease(String service, String inviter) throws Exception {
        DeviceRelayLease old = relayLeases.remove(relayKey(service, inviter)); if (old == null) throw new SecurityException("原临时中继租约已不存在");
        try { stopClientsForLease(old); } finally { old.close(); }
        owner.changed();
    }
    public JSONArray relayLeases() throws Exception {
        JSONArray views = new JSONArray(); DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) return views;
        for (DeviceRelayLease lease : relayLeases.values()) if (lease.current(local.deviceId(), local.instanceId(), System.currentTimeMillis())) views.put(lease.view());
        return views;
    }
    public List<String> relayInvitationAddresses() throws Exception {
        List<String> services = new ArrayList<>(); DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) return services;
        for (DeviceRelayLease lease : relayLeases.values()) if (lease.inviterDeviceId.equals(local.deviceId()) && lease.current(local.deviceId(), local.instanceId(), System.currentTimeMillis())) services.add(lease.serviceBase);
        return services;
    }
    private Set<String> trustedRelays() throws Exception {
        Set<String> services = new java.util.HashSet<>(); DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) return services;
        for (DeviceRelayLease lease : relayLeases.values()) if (lease.current(local.deviceId(), local.instanceId(), System.currentTimeMillis())) services.add(lease.serviceBase);
        return services;
    }
    private DeviceRelayLease invitationRelay(String service, String inviter) throws Exception {
        DeviceRelayLease lease = relayLeases.get(relayKey(service, inviter)); if (lease == null) throw new SecurityException("此服务/邀请设备没有主人已配置的准确中继租约"); currentRelay(lease); return lease;
    }
    private DeviceRelayLease peerRelay(String service, String peer, boolean allowReviewedBinding) throws Exception {
        DeviceRelayLease selected = null, bound = null; DeviceIdentity.LocalIdentity local = requireRelayIdentity();
        for (DeviceRelayLease lease : relayLeases.values()) {
            if (!lease.serviceBase.equals(service) || !lease.current(local.deviceId(), local.instanceId(), System.currentTimeMillis())) continue;
            if (lease.boundTo(peer)) { if (bound != null) throw new SecurityException("此设备有多个已绑定中继槽位，请移除未使用的租约"); bound = lease; continue; }
            if (allowReviewedBinding && lease.canBindPeer(peer)) {
                if (selected != null) throw new SecurityException("中继路由有多个候选，请移除未使用的租约后重新核对"); selected = lease;
            }
        }
        if (bound != null) return bound; if (selected == null) throw new SecurityException("此设备没有当前准确绑定的原生中继租约"); return selected;
    }
    private DeviceByteChannel openPairing(DeviceEndpointResolver.Resolved endpoint, String inviter, boolean completion) throws Exception {
        if ("lan".equals(endpoint.kind)) return new SocketByteChannel(socket(completion ? endpoint.completionAddress() : endpoint.candidateAddress()));
        DeviceRelayLease lease = invitationRelay(endpoint.serviceBase, inviter); return lease.open(completion ? "pairing-complete" : "pairing");
    }
    private void persistEnabled(boolean enabled) throws IOException { if (!preferences.edit().putBoolean("receiverEnabled", enabled).commit()) throw new IOException("设备接收设置尚未保存，请重试"); }
    public synchronized void enable() throws Exception {
        if (ownerEnabled()) throw new IllegalStateException("设备接收已经启用");
        DeviceIdentity.LocalIdentity local = owner.enableIdentity(); persistEnabled(true);
        String token = UUID.randomUUID().toString(); networkOwnerToken = token;
        DeviceReceiverServiceResource resource = new DeviceReceiverServiceResource(new DeviceReceiverServiceResource.Backend() {
            public void start() throws Exception { if (!token.equals(networkOwnerToken)) throw new SecurityException("原接收资源已替换"); startListeners(local); }
            public boolean isListening() { return token.equals(networkOwnerToken) && control != null && control.isListening() && bulk != null && bulk.isListening(); }
            public void close() throws Exception { closeReceiverNetwork(token); }
        }, new DeviceReceiverServiceResource.OwnerPolicy() {
            public void requireEnabledCurrent() throws Exception { DeviceIdentity.LocalIdentity actual = owner.existingIdentity(); if (!preferences.getBoolean("receiverEnabled", false) || actual == null || !actual.deviceId().equals(local.deviceId()) || !actual.instanceId().equals(local.instanceId())) throw new SecurityException("设备接收已关闭或本机身份已变化"); }
            public void disableByOwner() throws Exception { persistEnabled(false); }
        });
        receiver = resource; ingress = owner.ingress(resource);
        try { owner.app.enableDeviceReceiver(resource); }
        catch (Exception unavailable) { persistEnabled(false); receiver = null; ingress = null; throw unavailable; }
    }
    public void disable() throws Exception { DeviceReceiverServiceResource resource = receiver; if (resource != null) owner.app.disableDeviceReceiver(resource.resourceId()); else persistEnabled(false); }
    private synchronized void startListeners(DeviceIdentity.LocalIdentity local) throws Exception {
        if (control != null || bulk != null) throw new SecurityException("先前设备通道尚未释放");
        LanDeviceListener.CurrentContext pins = () -> DeviceTlsContexts.forPairedPeers(local, currentPins());
        try {
            control = new LanDeviceListener(true, new InetSocketAddress(0), pins, DeviceConnection.Lane.CONTROL, socket -> listener(DeviceConnection.Lane.CONTROL, false));
            bulk = new LanDeviceListener(true, new InetSocketAddress(0), pins, DeviceConnection.Lane.BULK, socket -> listener(DeviceConnection.Lane.BULK, false));
        } catch (Exception unavailable) { closeReceiverNetwork(networkOwnerToken); throw unavailable; }
        owner.changed();
    }
    private List<byte[]> currentPins() throws Exception {
        JSONArray rows = owner.trust.peers(64); List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject peer = owner.trust.peer(rows.getJSONObject(i).getString("deviceId")); if (peer == null || peer.optBoolean("revoked")) continue;
            byte[] der = Base64.getDecoder().decode(peer.getString("certificateDer"));
            try { DeviceIdentity.certificate(der).checkValidity(); result.add(der); } catch (java.security.cert.CertificateException expired) { }
        }
        if (result.isEmpty()) throw new SecurityException("当前没有可接受的已配对证书"); return result;
    }
    private static final class ClientOwnership {
        final String peerId, receiverToken; final DeviceRelayLease relay;
        ClientOwnership(String peerId, DeviceRelayLease relay, String receiverToken) { this.peerId = peerId; this.relay = relay; this.receiverToken = receiverToken; }
    }
    private static final class PeerChannel {
        final DeviceConnection connection; final DeviceIdentity.AuthenticatedPeer peer; final ClientOwnership ownership;
        PeerChannel(DeviceConnection connection, DeviceIdentity.AuthenticatedPeer peer, ClientOwnership ownership) { this.connection = connection; this.peer = peer; this.ownership = ownership; }
    }
    private DeviceConnection.Listener listener(DeviceConnection.Lane lane, boolean sourceClient) {
        return listener(lane, sourceClient, null);
    }
    private DeviceConnection.Listener listener(DeviceConnection.Lane lane, boolean sourceClient, ClientOwnership ownership) {
        return new DeviceConnection.Listener() {
            private PeerChannel retained;
            public void onConnection(DeviceConnection connection, DeviceIdentity.AuthenticatedPeer peer) throws Exception {
                synchronized (DeviceWorkspaceLanRuntime.this) {
                    JSONObject trusted = owner.trust.peer(peer.deviceId());
                    if (trusted == null || trusted.optBoolean("revoked") || !peer.certificateFingerprint().equals(trusted.optString("fingerprint"))) throw new SecurityException("当前设备信任已变化");
                    if (sourceClient && (ownership == null || !clientOwners.containsValue(ownership) || !ownership.peerId.equals(peer.deviceId()))) throw new SecurityException("原主动连接资源已替换");
                    retained = new PeerChannel(connection, peer, ownership);
                    (lane == DeviceConnection.Lane.CONTROL ? sourceClient ? outboundChannels : channels : sourceClient ? outboundBulkChannels : bulkChannels).put(peer.deviceId(), retained);
                }
            }
            public void onOnline(DeviceIdentity.AuthenticatedPeer peer) throws Exception { if (sourceClient && (ownership == null || !clientOwners.containsValue(ownership))) throw new SecurityException("原主动连接资源已替换"); owner.changed(); if (lane == DeviceConnection.Lane.CONTROL) describe(retained); }
            public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] bytes) throws Exception {
                JSONObject header = DeviceProtocol.parseObject(bytes);
                if (!DeviceProtocol.VERSION.equals(header.optString("protocolVersion"))) { retained.connection.close(DeviceConnection.State.INCOMPATIBLE); return; }
                if ("DeviceResponse".equals(header.optString("messageType")) || "DeviceError".equals(header.optString("messageType"))) {
                    try { header = DeviceProtocol.validateMessage(header); }
                    catch (DeviceProtocol.ProtocolException invalid) { if (invalid.code.startsWith("incompatible")) { retained.connection.close(DeviceConnection.State.INCOMPATIBLE); return; } throw invalid; }
                    if ("DeviceResponse".equals(header.optString("messageType")) && !peer.deviceId().equals(header.optString("executorDeviceId"))) throw new SecurityException("回执不属于已验证的目标设备");
                    PendingCall call = pending.get(header.optString("requestId"));
                    if (call != null && call.channel == retained && call.target.equals(peer.deviceId())) {
                        call.result.complete(new DeviceCapabilityPort.Reply(peer, header)); return;
                    }
                    PendingRead read = descriptions.get(header.optString("requestId"));
                    if (read != null && read.target.equals(peer.deviceId()) && read.channel == retained) {
                        descriptions.remove(header.optString("requestId"), read);
                        JSONObject fact = header.optJSONObject("result");
                        try {
                            if (!"DeviceResponse".equals(header.optString("messageType")) || !"device.describe".equals(header.optString("method"))) throw new IOException("目标设备当前不能提供能力回执");
                            read.proof.requireTargetPeer(peer, System.currentTimeMillis());
                            if (fact == null || !peer.deviceId().equals(fact.optString("deviceId")) || !header.optString("instanceId").equals(fact.optString("instanceId"))
                                    || fact.optLong("capturedAt") <= 0 || fact.optLong("capturedAt") > System.currentTimeMillis() || fact.optLong("validUntil") <= System.currentTimeMillis()) throw new SecurityException("目标能力回执已过期或身份不一致");
                            JSONArray receipts = fact.optJSONArray("grantReceipts");
                            if (receipts != null) {
                                if (receipts.length() > 16) throw new SecurityException("目标授权回执超出读取边界");
                                for (int i = 0; i < receipts.length(); i++) {
                                    JSONObject receipt = receipts.getJSONObject(i);
                                    try { retainOutgoingGrant(owner.pairing().acceptOutgoingGrant(peer, receipt, System.currentTimeMillis())); }
                                    catch (SecurityException notExecution) { owner.pairing().acceptHistoryGrantReceipt(peer, receipt, System.currentTimeMillis()); }
                                }
                            }
                            JSONObject displayFacts = new JSONObject(fact.toString()); displayFacts.remove("grantReceipts");
                            capabilities.put(peer.deviceId(), displayFacts); capabilityChannels.put(peer.deviceId(), retained);
                            read.result.complete(new JSONObject(displayFacts.toString())); owner.changed();
                        } catch (Exception rejected) { read.result.completeExceptionally(rejected); throw rejected; }
                    }
                    return; // A response is never routed into DeviceIngress as a new request.
                }
                // The original ingress authorizes identity/status/history/existing-task controls.
                // Only a NEW submission uses the exact receiver's atomic SQL acceptance gate.
                // A client-only A can answer describe without enabling incoming execution; a missing
                // receiver is an explicit deny gate, never the coordinator's permissive default.
                final DeviceIngress original = ingress == null ? owner.ingress(null) : ingress;
                if (!inboundSlots.tryAcquire()) throw new IOException("设备请求处理繁忙，请核对原请求后再继续");
                final PeerChannel exact = retained; final byte[] owned = bytes.clone();
                try { originalIngressWorker.execute(() -> {
                    try {
                        JSONObject response = original.receive(peer, owned);
                        // Queued response is NOT proof of task completion or remote delivery.
                        if (!exact.connection.sendControl(DeviceProtocol.canonicalBytes(response))) exact.connection.close();
                    } catch (Exception rejected) { try { exact.connection.close(rejected instanceof DeviceProtocol.ProtocolException && ((DeviceProtocol.ProtocolException) rejected).code.startsWith("incompatible") ? DeviceConnection.State.INCOMPATIBLE : DeviceConnection.State.OFFLINE); } catch (IOException ignored) { } }
                    finally { java.util.Arrays.fill(owned, (byte) 0); inboundSlots.release(); owner.changed(); }
                }); } catch (RuntimeException unavailable) { java.util.Arrays.fill(owned, (byte) 0); inboundSlots.release(); throw unavailable; }
            }
            public void onChunk(DeviceIdentity.AuthenticatedPeer peer, UUID transfer, long offset, byte[] bytes) throws Exception {
                DeviceWorkspaceArtifacts bytesOwner = owner.existingArtifacts();
                if (lane != DeviceConnection.Lane.BULK || bytesOwner == null) throw new SecurityException("这项成果传输尚未注册，未读取或写入任何路径");
                bytesOwner.chunk(peer, transfer, offset, bytes);
            }
            public void onSendGateFailed(TaskStoreDeviceTasks.OutboundSendTicket ticket) {
                for (PendingCall call : pending.values()) if (call.channel == retained && call.ticket == ticket)
                    call.result.completeExceptionally(new IOException("原发送票据未获准写入，请核对原操作账本"));
                owner.changed(); // No assumption about delivery, terminal state or refundable budget.
            }
            public void onClosed(DeviceConnection.State state, boolean transportWriteMayBeUnknown) {
                PeerChannel old = retained; if (old != null) {
                    channels.remove(old.peer.deviceId(), old); outboundChannels.remove(old.peer.deviceId(), old);
                    bulkChannels.remove(old.peer.deviceId(), old); outboundBulkChannels.remove(old.peer.deviceId(), old);
                    DeviceWorkspaceArtifacts bytesOwner = owner.existingArtifacts(); if (bytesOwner != null) bytesOwner.disconnected(old.peer);
                    descriptions.entrySet().removeIf(entry -> { if (entry.getValue().channel != old) return false; entry.getValue().result.completeExceptionally(new IOException("能力核对通道已关闭")); return true; });
                    for (PendingCall call : pending.values()) if (call.channel == old) call.result.completeExceptionally(new IOException("连接中断，请核对原操作结果"));
                }
                owner.changed(); // Never updates a task status, grant budget or execution result.
            }
        };
    }
    private static final class PendingRead {
        final String target; final PeerChannel channel; final long expiresAt; final PairingCoordinator.DiscoveryQuery proof;
        final CompletableFuture<JSONObject> result = new CompletableFuture<>();
        PendingRead(String target, PeerChannel channel, long expiry, PairingCoordinator.DiscoveryQuery proof) { this.target = target; this.channel = channel; expiresAt = expiry; this.proof = proof; }
    }
    private PendingRead describe(PeerChannel channel) throws Exception {
        long now = System.currentTimeMillis(); descriptions.entrySet().removeIf(row -> { if (row.getValue().expiresAt > now) return false; row.getValue().result.completeExceptionally(new IOException("能力读取期限已到")); return true; });
        for (PendingRead existing : descriptions.values()) if (existing.channel == channel) return existing;
        if (descriptions.size() >= 16) throw new IOException("正在核对其他设备，请稍后刷新");
        PairingCoordinator.DiscoveryQuery proof = owner.pairing().discoveryQuery(owner.existingIdentity(), channel.peer, now, 10000);
        proof.requireTargetPeer(channel.peer, now); DeviceTaskEnvelope request = proof.envelope();
        PendingRead read = new PendingRead(channel.peer.deviceId(), channel, request.deadline(), proof); descriptions.put(request.requestId(), read);
        if (!channel.connection.sendControl(DeviceProtocol.canonicalBytes(request.json()))) {
            descriptions.remove(request.requestId(), read); read.result.completeExceptionally(new IOException("能力读取尚未进入发送队列"));
        }
        return read;
    }
    /** An explicit owner scope action may refresh READ-ONLY metadata on an already authenticated
     * channel. It never opens a channel, creates an identity, submits work or replays a task. */
    public JSONObject readFreshAuthenticatedCapabilities(String id) throws Exception {
        JSONObject fresh = authenticatedCapabilities(id); if (fresh != null) return fresh;
        PeerChannel actual = controlChannel(id); if (!channelOnline(actual)) return null;
        PendingRead read = describe(actual);
        try { read.result.get(Math.min(750, Math.max(1, read.expiresAt - System.currentTimeMillis())), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException pendingRead) { return null; }
        return authenticatedCapabilities(id);
    }
    public JSONObject refreshAuthenticatedCapabilities(String id) throws Exception {
        PeerChannel actual = controlChannel(id); if (!channelOnline(actual)) throw new IOException("设备尚未连接，请连接后刷新");
        PendingRead read = describe(actual);
        try { read.result.get(Math.min(750, Math.max(1, read.expiresAt - System.currentTimeMillis())), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException waiting) { throw new IOException("已请求更新，回执尚未返回，请稍后刷新"); }
        return authenticatedCapabilities(id);
    }
    @Override public DeviceTransportStatus.Snapshot current(String id) {
        PeerChannel channel = controlChannel(id);
        return DeviceTransportStatus.current(id, selectedClient(id, clients), channel == null ? null : channel.connection, control, pending.values().stream().anyMatch(call -> call.target.equals(id) && call.ticket != null && call.ticket.isClaimed()));
    }
    public JSONObject capabilities(String id) throws Exception { JSONObject value = capabilities.get(id); return value == null ? null : new JSONObject(value.toString()); }
    /** Scope candidates use only fresh facts on the same currently authenticated channel. Even
     * these facts are not a grant: callers still need the original opaque OutgoingGrant. */
    public JSONObject authenticatedCapabilities(String id) throws Exception {
        PeerChannel channel = controlChannel(id); JSONObject value = capabilities(id); long now = System.currentTimeMillis();
        if (channel == null || channel.connection.state() != DeviceConnection.State.ONLINE && channel.connection.state() != DeviceConnection.State.DEGRADED
                || value == null || capabilityChannels.get(id) != channel || !channel.peer.deviceId().equals(value.optString("deviceId")) || value.optLong("capturedAt") > now
                || value.optLong("validUntil") <= now) return null;
        return value;
    }
    private synchronized void retainOutgoingGrant(PairingCoordinator.OutgoingGrant grant) throws Exception {
        grant.checkCurrent(System.currentTimeMillis()); String key = grant.targetDeviceId() + "\n" + grant.grantId();
        nativeOutgoingGrants();
        if (!outgoingGrants.containsKey(key) && outgoingGrants.size() >= 64) { outgoingGrantLimitReached = true; owner.changed(); return; }
        outgoingGrants.put(key, grant);
    }
    public synchronized List<PairingCoordinator.OutgoingGrant> nativeOutgoingGrants() throws Exception {
        List<PairingCoordinator.OutgoingGrant> result = new ArrayList<>(); long now = System.currentTimeMillis();
        for (Map.Entry<String, PairingCoordinator.OutgoingGrant> entry : outgoingGrants.entrySet()) {
            try { entry.getValue().checkCurrent(now); result.add(entry.getValue()); }
            catch (SecurityException expiredOrChanged) { outgoingGrants.remove(entry.getKey(), entry.getValue()); }
        }
        result.sort(java.util.Comparator.comparing(PairingCoordinator.OutgoingGrant::targetDeviceId).thenComparing(PairingCoordinator.OutgoingGrant::grantId));
        return java.util.Collections.unmodifiableList(result);
    }
    public boolean hasMoreOutgoingGrants() { return outgoingGrantLimitReached; }
    public synchronized String createInvitation(DeviceIdentity.LocalIdentity local, String selectedAddress) throws Exception {
        if (!isListening()) throw new IllegalStateException("请先开启设备接收");
        invitations.entrySet().removeIf(entry -> !entry.getValue().isCandidateListening());
        if (invitations.size() >= 4) throw new IllegalStateException("还有未到期的配对邀请，请稍后再创建");
        long now = System.currentTimeMillis(), expiresAt = now + 120000;
        DeviceRelayLease relay = relayLeases.get(relayKey(selectedAddress, local.deviceId()));
        if (relay != null) { currentRelay(relay); expiresAt = Math.min(expiresAt, relay.expiresAt); }
        final long deadline = expiresAt;
        DevicePairingEndpoint reservation = relay == null ? DeviceEndpointResolver.reserveLan(true, selectedAddress, control, bulk, deadline)
                : new RelayPairingEndpoint(relay, deadline, () -> { currentRelay(relay); if (!ownerEnabled()) throw new SecurityException("原设备接收已关闭"); });
        try {
            JSONObject invitation = owner.pairing().createInvitation(local, null, now, deadline - now); String id = invitation.getString("inviteId");
            reservation.startCandidate(DeviceTlsContexts.pairingCandidateServer(local), raw -> {
                PairingCoordinator.Candidate candidate = owner.pairing().inspectCandidate(local, raw, System.currentTimeMillis()); owner.changed();
                return DeviceProtocol.canonicalBytes(owner.pairing().candidateReceipt(candidate, System.currentTimeMillis()));
            });
            invitation.put("connectionHint", reservation.connectionHint());
            invitations.put(id, reservation); owner.changed(); return DeviceProtocol.canonical(invitation);
        } catch (Exception unavailable) { reservation.close(); throw unavailable; }
    }
    synchronized void confirmCandidate(PairingCoordinator.Candidate candidate, JSONObject exactGrant) throws Exception {
        DevicePairingEndpoint reservation = invitations.get(candidate.inviteId());
        if (reservation == null || !reservation.isCandidateListening()) throw new SecurityException("原邀请通道已关闭，请重新创建邀请");
        DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        final JSONObject confirmedHint = reservation.connectionHint();
        final String receiverToken = networkOwnerToken;
        PairingCoordinator.PendingPairing proof = owner.pairing().confirmCandidateLocally(local, candidate, exactGrant, true, System.currentTimeMillis());
        if (!proof.reviewedGrantFingerprint().equals(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(exactGrant)))) throw new SecurityException("准确授权摘要已变化");
        reservation.startCompletion(DeviceTlsContexts.forPeer(local, proof.peerCertificateDer()), proof, (peer, raw) -> {
            JSONObject completion = owner.pairing().finalizeCandidatePairing(proof, peer, raw, exactGrant, System.currentTimeMillis());
            if ("relay".equals(confirmedHint.optString("kind"))) try {
                DeviceRelayLease lease = invitationRelay(confirmedHint.getString("serviceBase"), local.deviceId()); lease.bindPeer(peer.deviceId());
                connectCommittedReceiverRelay(local, peer, lease, receiverToken);
            } catch (Exception routeUnavailable) { /* Original pairing is already committed. Return its receipt; never label connection readiness or replay it. */ }
            owner.changed(); return DeviceProtocol.canonicalBytes(completion);
        }); owner.changed();
    }
    private synchronized void connectCommittedReceiverRelay(DeviceIdentity.LocalIdentity local, DeviceIdentity.AuthenticatedPeer peer, DeviceRelayLease lease, String receiverToken) throws Exception {
        if (!receiverToken.equals(networkOwnerToken) || !ownerEnabled()) throw new SecurityException("原设备接收资源已关闭或替换");
        // A late immutable pairing callback may not overwrite an intervening owner route choice.
        if (preferences.contains(endpointKey(peer.deviceId()))) return;
        saveRelayPeerEndpoint(peer.deviceId(), lease);
        connectInviter(local, peer.deviceId(), peer.certificateDer(), DeviceEndpointResolver.resolveInvitation(new JSONObject().put("inviterDeviceId", peer.deviceId()).put("connectionHint", new JSONObject().put("kind", "relay").put("serviceBase", lease.serviceBase).put("inviterDeviceId", peer.deviceId())), trustedRelays()), receiverToken);
    }
    NativeDeviceWorkspaceHost.PairingReviewAction inspectInvitation(String raw) throws Exception {
        JSONObject invitation;
        try { invitation = DeviceProtocol.parseObject(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception invalid) { throw new IllegalArgumentException("邀请内容无法读取，请重新复制"); }
        invitation = PairingCoordinator.validateInvitationForPreview(invitation, System.currentTimeMillis());
        DeviceEndpointResolver.Resolved endpoint = DeviceEndpointResolver.resolveInvitation(invitation, trustedRelays());
        if ("relay".equals(endpoint.kind)) invitationRelay(endpoint.serviceBase, invitation.getString("inviterDeviceId"));
        DeviceIdentity.LocalIdentity local = owner.enableIdentity(); if (local.deviceId().equals(invitation.getString("inviterDeviceId"))) throw new SecurityException("不能与这台设备自己配对");
        if (owner.trust.peer(invitation.getString("inviterDeviceId")) != null) throw new SecurityException("这台设备已有配对记录，请在设备详情管理原授权");
        return new Outgoing(invitation, endpoint, null);
    }
    private final class Outgoing implements NativeDeviceWorkspaceHost.PairingReviewAction {
        private final JSONObject invitation, grant;
        private final DeviceWorkspaceGrantOwner.Review nativeGrantReview;
        private final DeviceEndpointResolver.Resolved endpoint;
        private volatile PairingCoordinator.CandidateOffer offer;
        private volatile String state = "pending_local_review";
        Outgoing(JSONObject invitation, DeviceEndpointResolver.Resolved endpoint, DeviceWorkspaceGrantOwner.Review grant) throws Exception { this.invitation = new JSONObject(invitation.toString()); this.endpoint = endpoint; this.nativeGrantReview = grant; this.grant = grant == null ? null : grant.grant(); }
        public JSONObject view() throws Exception {
            DeviceIdentity.LocalIdentity local = owner.existingIdentity();
            JSONObject view = new JSONObject().put("inviteId", invitation.getString("inviteId")).put("inviterDeviceId", invitation.getString("inviterDeviceId")).put("inviterFingerprint", invitation.getString("certificateFingerprint"))
                    .put("joinerDeviceId", local.deviceId()).put("joinerFingerprint", local.certificateFingerprint()).put("expiresAt", invitation.getLong("expiresAt")).put("state", state);
            if (grant == null) owner.decorateGrantInputs(view, invitation.getString("inviterDeviceId")); else view.put("grantReview", owner.grantView(nativeGrantReview)); return view;
        }
        public NativeDeviceWorkspaceHost.PairingReviewAction reviewGrant(NativeDeviceWorkspaceHost.GrantChoice choice) throws Exception { return new Outgoing(invitation, endpoint, owner.reviewGrantFor(invitation.getString("inviterDeviceId"), choice)); }
        public void confirmBothFingerprints() throws Exception {
            if (grant == null) throw new SecurityException("请先选择允许的权限范围"); nativeGrantReview.requireCurrent();
            if (outgoing.size() >= 4) throw new IllegalStateException("还有正在配对的设备，请先完成它们");
            Outgoing known = outgoing.putIfAbsent(invitation.getString("inviteId"), this); if (known != null && known != this) throw new SecurityException("这次邀请已经开始配对，请核对原状态");
            advance();
        }
        public void reject() { outgoing.remove(invitation.optString("inviteId"), this); state = "rejected"; }
        void advance() throws Exception {
            if (System.currentTimeMillis() >= invitation.getLong("expiresAt")) throw new SecurityException("配对邀请已到期，请重新发起"); nativeGrantReview.requireCurrent();
            DeviceIdentity.LocalIdentity local = owner.existingIdentity(); byte[] der = Base64.getDecoder().decode(invitation.getString("certificateDer"));
            try (DeviceByteChannel raw = openPairing(endpoint, invitation.getString("inviterDeviceId"), false)) {
                byte[] response = PairingCandidateTransport.exchange(DeviceTlsContexts.pairingCandidateClient(der), der, raw, invitation.getLong("expiresAt"), verified -> {
                    if (offer == null) offer = owner.pairing().prepareCandidateOffer(local, invitation, verified, grant, true, System.currentTimeMillis());
                    return offer.wireBytes();
                });
                JSONObject receipt = PairingCoordinator.verifyCandidateReceipt(offer, response); state = receipt.getString("state");
            }
            if (!"confirmed_for_mtls".equals(state) && !"paired".equals(state)) { state = "waiting_remote_confirmation"; owner.changed(); return; }
            try (DeviceByteChannel raw = openPairing(endpoint, invitation.getString("inviterDeviceId"), true)) {
                PairingCompletionTransport.exchange(DeviceTlsContexts.forPeer(local, der), der, raw, invitation.getLong("expiresAt"), new PairingCompletionTransport.Client() {
                    public byte[] request(DeviceIdentity.AuthenticatedPeer peer) throws Exception { return DeviceProtocol.canonicalBytes(PairingCoordinator.candidateFinalizeRequest(offer)); }
                    public void accept(DeviceIdentity.AuthenticatedPeer peer, byte[] completion) throws Exception { retainOutgoingGrant(owner.pairing().acceptCandidateCompletion(offer, peer, completion, grant, System.currentTimeMillis())); if ("relay".equals(endpoint.kind)) invitationRelay(endpoint.serviceBase, peer.deviceId()).bindPeer(peer.deviceId()); saveBoundEndpoint(peer.deviceId(), invitation); }
                });
            }
            state = "paired"; connectInviter(local, invitation.getString("inviterDeviceId"), der, endpoint); outgoing.remove(invitation.getString("inviteId"), this); owner.changed();
        }
    }
    NativeDeviceWorkspaceHost.RecoveryEndpointAction openCommittedRecovery(String inviteId, String selectedAddress) throws Exception {
        DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) throw new SecurityException("原设备身份不存在，不能建立恢复入口");
        JSONObject selected = null; long now = System.currentTimeMillis(); JSONArray choices = owner.pairing().committedPairingRecoveryCandidates(now, 8);
        for (int i = 0; i < choices.length(); i++) if (inviteId.equals(choices.getJSONObject(i).optString("inviteId"))) selected = choices.getJSONObject(i);
        if (selected == null || !"available".equals(selected.optString("recoveryState"))) throw new SecurityException("原配对已经撤销、改变或到期，不能恢复旧权限");
        PairingCoordinator.CommittedPairingRecovery original = owner.pairing().committedPairingRecovery(local, inviteId, selected.getString("candidateDigest"), now, 30000);
        if (selectedAddress.startsWith("wss://") || selectedAddress.startsWith("https://")) {
            DeviceRelayLease lease = peerRelay(selectedAddress, original.expectedDeviceId(), true); currentRelay(lease); lease.bindPeer(original.expectedDeviceId());
            synchronized (relayRecoveries) {
                relayRecoveries.entrySet().removeIf(row -> { if (row.getValue().isCompletionListening()) return false; row.getValue().close(); return true; });
                if (relayRecoveries.size() >= 2 && !relayRecoveries.containsKey(inviteId)) throw new IOException("配对结果恢复窗口已满");
                DevicePairingEndpoint invitation = invitations.remove(inviteId); if (invitation != null) invitation.close();
                RelayPairingEndpoint old = relayRecoveries.remove(inviteId); if (old != null) old.close();
                RelayPairingEndpoint relay = new RelayPairingEndpoint(lease, original.expiresAt(), () -> { currentRelay(lease); original.checkCurrent(System.currentTimeMillis()); });
                relay.startRecovery(DeviceTlsContexts.forPeer(local, original.peerCertificateDer()), original,
                        (actual, request) -> DeviceProtocol.canonicalBytes(owner.pairing().recoverCandidatePairing(original, actual, request, System.currentTimeMillis())));
                relayRecoveries.put(inviteId, relay); final JSONObject expected = selected;
                return new NativeDeviceWorkspaceHost.RecoveryEndpointAction() {
                    public JSONObject view() throws Exception { original.checkCurrent(System.currentTimeMillis()); currentRelay(lease); return new JSONObject().put("inviteId", inviteId).put("kind", "relay").put("serviceBase", lease.serviceBase).put("listening", relay.isCompletionListening()).put("capturedAt", System.currentTimeMillis()).put("expiresAt", original.expiresAt()).put("peerDeviceId", original.expectedDeviceId()).put("peerFingerprint", expected.getString("peerFingerprint")).put("localFingerprint", local.certificateFingerprint()); }
                    public void close() { relayRecoveries.remove(inviteId, relay); relay.close(); }
                };
            }
        }
        if (!DeviceEndpointResolver.localLanAddresses().contains(selectedAddress)) throw new SecurityException("请选择本机实际局域网地址或已配置的中继服务");
        synchronized (recoveries) {
            recoveries.entrySet().removeIf(row -> !row.getValue().isListening());
            if (recoveries.size() >= 2 && !recoveries.containsKey(inviteId)) throw new IOException("配对结果恢复窗口已满");
            PairingCompletionListener previous = recoveries.remove(inviteId); if (previous != null) previous.close();
            PairingCompletionListener listener = new PairingCompletionListener(DeviceTlsContexts.forPeer(local, original.peerCertificateDer()), original, new InetSocketAddress(selectedAddress, 0),
                    (actual, request) -> DeviceProtocol.canonicalBytes(owner.pairing().recoverCandidatePairing(original, actual, request, System.currentTimeMillis())));
            recoveries.put(inviteId, listener); final JSONObject expected = selected;
            return new NativeDeviceWorkspaceHost.RecoveryEndpointAction() {
                public JSONObject view() throws Exception { original.checkCurrent(System.currentTimeMillis()); return new JSONObject().put("inviteId", inviteId).put("host", selectedAddress).put("port", listener.port()).put("listening", listener.isListening()).put("capturedAt", System.currentTimeMillis()).put("expiresAt", original.expiresAt()).put("peerDeviceId", original.expectedDeviceId()).put("peerFingerprint", expected.getString("peerFingerprint")).put("localFingerprint", local.certificateFingerprint()); }
                public void close() { recoveries.remove(inviteId, listener); try { listener.close(); } catch (IOException ignored) { } }
            };
        }
    }
    void recoverPairing(String inviteId, String literalHost, int port) throws Exception {
        // This is address input only. No DNS, caller identity, new invitation token or grant is
        // trusted from it; exact original B certificate and native offer bind the whole read.
        DeviceIdentity.LocalIdentity local = owner.existingIdentity(); PairingCoordinator.RecoveryOffer original = owner.pairing().restorePairingOffer(local, inviteId, System.currentTimeMillis()); byte[] der = original.targetCertificateDer();
        long deadline = System.currentTimeMillis() + 20000;
        final DeviceRelayLease relay;
        final DeviceByteChannel channel;
        if ((literalHost.startsWith("wss://") || literalHost.startsWith("https://")) && port == 0) {
            relay = invitationRelay(literalHost, original.inviterDeviceId()); deadline = Math.min(deadline, relay.expiresAt); channel = relay.open("pairing-complete");
        } else {
            relay = null;
            if (!literalHost.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9A-Fa-f:]+") || literalHost.indexOf(':') < 0 && literalHost.indexOf('.') < 0 || port < 1 || port > 65535) throw new SecurityException("请输入准确局域网数字地址，或已配置的原生中继服务");
            java.net.InetAddress address = java.net.InetAddress.getByName(literalHost); if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) throw new SecurityException("不能使用回环、通配或组播地址恢复设备结果");
            channel = new SocketByteChannel(socket(new InetSocketAddress(address, port)));
        }
        try (DeviceByteChannel raw = channel) {
            PairingCompletionTransport.exchangeRecovery(DeviceTlsContexts.forPeer(local, der), der, raw, deadline, new PairingCompletionTransport.Client() {
                public byte[] request(DeviceIdentity.AuthenticatedPeer actual) throws Exception { original.requireTargetPeer(actual, System.currentTimeMillis()); return DeviceProtocol.canonicalBytes(PairingCoordinator.candidateRecoveryRequest(original)); }
                public void accept(DeviceIdentity.AuthenticatedPeer actual, byte[] receipt) throws Exception { retainOutgoingGrant(owner.pairing().acceptRecoveredCandidateCompletion(original, actual, receipt, System.currentTimeMillis())); if (relay != null) { currentRelay(relay); relay.bindPeer(actual.deviceId()); saveRelayPeerEndpoint(actual.deviceId(), relay); } }
            });
        }
        owner.changed(); // Original trust may now exist; no business endpoint or online state is invented.
    }
    void forgetPendingPairing(String id) { Outgoing old = outgoing.remove(id); if (old != null) old.state = "abandoned_locally"; }
    public void advancePairing(String id) throws Exception { Outgoing value = outgoing.get(id); if (value == null) throw new SecurityException("没有这次配对的原生确认，请重新发起"); value.advance(); }
    public int appendOutgoingPairings(JSONArray result) throws Exception {
        long now = System.currentTimeMillis(); outgoing.entrySet().removeIf(row -> row.getValue().invitation.optLong("expiresAt") <= now);
        int hidden = 0;
        for (Outgoing entry : outgoing.values()) {
            if (result.length() >= 8) { hidden++; continue; }
            JSONObject safe = entry.view(); safe.remove("grantOptions"); safe.remove("grantReview");
            result.put(safe.put("candidateId", entry.invitation.getString("inviteId")).put("reviewAvailable", false).put("advanceAvailable", true));
        }
        return hidden;
    }
    private static String endpointKey(String id) { return "endpoint_" + DeviceProtocol.sha256(id.getBytes(StandardCharsets.UTF_8)); }
    private JSONObject currentPeer(String id) throws Exception {
        DeviceProtocol.deviceId(id, "deviceId"); JSONObject peer = owner.trust.peer(id);
        if (peer == null || peer.optBoolean("revoked")) throw new SecurityException("这台设备的配对信任已失效");
        if (owner.existingIdentity() == null) throw new SecurityException("本机设备身份不存在，请重新核对配对");
        return peer;
    }
    /** Non-secret address configuration only. It cannot replace the original SQL identity, grant,
     * receipt, certificate or any native Permission, and is never transmitted as an invitation. */
    private JSONObject boundEndpoint(String id) throws Exception {
        JSONObject peer = currentPeer(id); String raw = preferences.getString(endpointKey(id), null);
        if (raw == null) return null;
        JSONObject config = DeviceProtocol.parseObject(raw.getBytes(StandardCharsets.UTF_8));
        DeviceProtocol.keys(config, DeviceProtocol.set("deviceId instanceId certificateFingerprint localDeviceId localInstanceId connectionHint"), Set.of());
        DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        if (!id.equals(config.optString("deviceId")) || !peer.optString("instanceId").equals(config.optString("instanceId"))
                || !peer.optString("fingerprint").equals(config.optString("certificateFingerprint")) || !local.deviceId().equals(config.optString("localDeviceId"))
                || !local.instanceId().equals(config.optString("localInstanceId"))) throw new SecurityException("连接地址属于旧身份，请重新核对并更新");
        DeviceProtocol.validateConnectionHint(config.getJSONObject("connectionHint")); return config;
    }
    private void saveBoundEndpoint(String id, JSONObject uniqueInvitation) throws Exception {
        JSONObject peer = currentPeer(id); DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        if (!id.equals(uniqueInvitation.optString("inviterDeviceId")) || !peer.optString("instanceId").equals(uniqueInvitation.optString("instanceId"))
                || !peer.optString("fingerprint").equals(uniqueInvitation.optString("certificateFingerprint"))) throw new SecurityException("新地址与原配对设备身份不一致");
        DeviceEndpointResolver.Resolved resolved = DeviceEndpointResolver.resolveInvitation(uniqueInvitation, trustedRelays());
        if ("relay".equals(resolved.kind)) currentRelay(peerRelay(resolved.serviceBase, id, false));
        if (!preferences.contains(endpointKey(id)) && preferences.getAll().keySet().stream().filter(key -> key.startsWith("endpoint_")).count() >= 64)
            throw new IllegalStateException("已达到本机保存设备地址的上限");
        JSONObject config = new JSONObject().put("deviceId", id).put("instanceId", peer.getString("instanceId")).put("certificateFingerprint", peer.getString("fingerprint"))
                .put("localDeviceId", local.deviceId()).put("localInstanceId", local.instanceId()).put("connectionHint", uniqueInvitation.getJSONObject("connectionHint"));
        if (!preferences.edit().putString(endpointKey(id), DeviceProtocol.canonical(config)).commit()) throw new IOException("配对已保存，但连接地址尚未保存，请重新更新地址");
    }
    private void saveRelayPeerEndpoint(String id, DeviceRelayLease lease) throws Exception {
        currentRelay(lease); if (!lease.boundTo(id)) throw new SecurityException("中继路由未绑定原配对设备"); JSONObject peer = currentPeer(id);
        saveBoundEndpoint(id, new JSONObject().put("inviterDeviceId", id).put("instanceId", peer.getString("instanceId")).put("certificateFingerprint", peer.getString("fingerprint"))
                .put("connectionHint", new JSONObject().put("kind", "relay").put("serviceBase", lease.serviceBase).put("inviterDeviceId", id)));
    }
    public NativeDeviceWorkspaceHost.Availability connectAvailability(String id) throws Exception {
        try {
            currentPeer(id); if (channelOnline(controlChannel(id))) return NativeDeviceWorkspaceHost.Availability.blocked("设备已连接");
            ReconnectingDeviceClient client = selectedClient(id, clients); if (client != null && client.isRunning()) return NativeDeviceWorkspaceHost.Availability.blocked("正在连接，请稍后核对");
            JSONObject config = boundEndpoint(id); if (config == null) return NativeDeviceWorkspaceHost.Availability.blocked("还没有保存连接地址，请用这台设备的新邀请更新");
            if ("relay".equals(config.getJSONObject("connectionHint").optString("kind"))) currentRelay(peerRelay(config.getJSONObject("connectionHint").getString("serviceBase"), id, false));
            return NativeDeviceWorkspaceHost.Availability.ready();
        } catch (SecurityException changed) { return NativeDeviceWorkspaceHost.Availability.blocked(changed.getMessage()); }
        catch (IllegalArgumentException | org.json.JSONException unreadable) { return NativeDeviceWorkspaceHost.Availability.blocked("保存的连接地址无法读取，请重新更新"); }
    }
    public NativeDeviceWorkspaceHost.Availability disconnectAvailability(String id) throws Exception {
        try { currentPeer(id); return clients.containsKey(id) ? NativeDeviceWorkspaceHost.Availability.ready() : NativeDeviceWorkspaceHost.Availability.blocked("没有本机主动连接"); }
        catch (SecurityException changed) { return NativeDeviceWorkspaceHost.Availability.blocked(changed.getMessage()); }
    }
    public synchronized void connectPeer(String id) throws Exception {
        connectAvailability(id).require(); JSONObject config = boundEndpoint(id), peer = currentPeer(id);
        DeviceEndpointResolver.Resolved endpoint = DeviceEndpointResolver.resolveInvitation(new JSONObject().put("inviterDeviceId", id).put("connectionHint", config.getJSONObject("connectionHint")), trustedRelays());
        connectInviter(owner.existingIdentity(), id, Base64.getDecoder().decode(peer.getString("certificateDer")), endpoint);
    }
    public synchronized void disconnectPeer(String id) throws Exception {
        disconnectAvailability(id).require(); closeBulkForPeer(id); closeClientExact(clients.get(id));
        PeerChannel active = outboundChannels.remove(id); if (active != null) active.connection.close(); owner.changed();
    }
    public NativeDeviceWorkspaceHost.ConnectionReviewAction reviewConnectionUpdate(String id, String raw) throws Exception {
        currentPeer(id);
        JSONObject invitation = PairingCoordinator.validateInvitationForPreview(DeviceProtocol.parseObject(raw.getBytes(StandardCharsets.UTF_8)), System.currentTimeMillis());
        JSONObject peer = currentPeer(id); DeviceEndpointResolver.Resolved endpoint = DeviceEndpointResolver.resolveInvitation(invitation, trustedRelays());
        if (!id.equals(invitation.optString("inviterDeviceId")) || !peer.optString("instanceId").equals(invitation.optString("instanceId"))
                || !peer.optString("fingerprint").equals(invitation.optString("certificateFingerprint"))) throw new SecurityException("邀请与原配对设备身份不一致，未更新地址");
        final DeviceRelayLease relay = "relay".equals(endpoint.kind) ? peerRelay(endpoint.serviceBase, id, true) : null;
        return new NativeDeviceWorkspaceHost.ConnectionReviewAction() {
            public JSONObject view() throws Exception { return new JSONObject().put("deviceId", id).put("instanceId", peer.getString("instanceId")).put("certificateFingerprint", peer.getString("fingerprint"))
                    .put("kind", endpoint.kind).put("serviceBase", endpoint.serviceBase).put("host", endpoint.host).put("controlPort", endpoint.controlPort).put("bulkPort", endpoint.bulkPort).put("expiresAt", invitation.getLong("expiresAt")); }
            public void confirm() throws Exception {
                PairingCoordinator.validateInvitationForPreview(invitation, System.currentTimeMillis()); currentPeer(id);
                if (relay != null) { currentRelay(relay); relay.bindPeer(id); } saveBoundEndpoint(id, invitation);
                stopOutboundPeer(id); if (!channelOnline(controlChannel(id))) connectPeer(id); owner.changed();
            }
        };
    }
    /** Owner reselects transport for an ALREADY paired SQL identity. This is not a new pairing,
     * grant or remote operation. In particular cold lease import never guesses a former peer. */
    public NativeDeviceWorkspaceHost.ConnectionReviewAction reviewRelayConnection(String id, String service) throws Exception {
        JSONObject expected = currentPeer(id); DeviceRelayLease lease = peerRelay(service, id, true); currentRelay(lease);
        return new NativeDeviceWorkspaceHost.ConnectionReviewAction() {
            public JSONObject view() throws Exception { currentRelay(lease); return new JSONObject().put("deviceId", id).put("instanceId", expected.getString("instanceId"))
                    .put("certificateFingerprint", expected.getString("fingerprint")).put("kind", "relay").put("serviceBase", lease.serviceBase).put("expiresAt", lease.expiresAt); }
            public void confirm() throws Exception {
                JSONObject actual = currentPeer(id); currentRelay(lease);
                for (String field : new String[]{"fingerprint", "instanceId", "authorizationEpoch"}) if (!expected.get(field).equals(actual.get(field))) throw new SecurityException("原配对身份或权限版本已变化，请重新核对");
                synchronized (DeviceWorkspaceLanRuntime.this) { lease.bindPeer(id); saveRelayPeerEndpoint(id, lease); stopOutboundPeer(id); connectPeer(id); } owner.changed();
            }
        };
    }
    private void connectInviter(DeviceIdentity.LocalIdentity local, String id, byte[] der, DeviceEndpointResolver.Resolved endpoint) throws Exception { connectInviter(local, id, der, endpoint, null); }
    private synchronized void connectInviter(DeviceIdentity.LocalIdentity local, String id, byte[] der, DeviceEndpointResolver.Resolved endpoint, String receiverToken) throws Exception {
        if (clients.size() >= 8 && !clients.containsKey(id)) throw new IllegalStateException("已达到本机设备连接上限，配对已保存但未开启更多连接");
        DeviceRelayLease relay = "relay".equals(endpoint.kind) ? peerRelay(endpoint.serviceBase, id, false) : null;
        if (relay != null) currentRelay(relay);
        if (receiverToken != null && (!receiverToken.equals(networkOwnerToken) || !ownerEnabled())) throw new SecurityException("原设备接收资源已关闭或替换");
        closeClientExact(clients.get(id));
        ClientOwnership ownership = new ClientOwnership(id, relay, receiverToken);
        ReconnectingDeviceClient client = new ReconnectingDeviceClient(true, relay == null ? System.currentTimeMillis() + 30L * 86400_000 : relay.expiresAt, () -> {
            JSONObject peer = owner.trust.peer(id); if (peer == null || peer.optBoolean("revoked")) throw new SecurityException("设备信任已撤销");
            DeviceIdentity.LocalIdentity actual = owner.existingIdentity(); if (actual == null || !local.deviceId().equals(actual.deviceId()) || !local.instanceId().equals(actual.instanceId())) throw new SecurityException("原本机设备身份已变化");
            if (relay != null) currentRelay(relay);
            return new DeviceConnection(DeviceTlsContexts.forPeer(actual, Base64.getDecoder().decode(peer.getString("certificateDer"))), relay == null || actual.deviceId().compareTo(id) < 0, DeviceConnection.Lane.CONTROL,
                    relay == null ? new SocketByteChannel(socket(endpoint.controlAddress())) : openOwnedRelay(relay, ownership, "control"), listener(DeviceConnection.Lane.CONTROL, true, ownership));
        });
        clients.put(id, client); clientOwners.put(client, ownership); Thread worker = new Thread(client, "device-paired-channel"); worker.setDaemon(true); worker.start();
        // Both WSS peers dial out. The independent bulk lane cannot occupy CONTROL/cancel IO.
        if (relay != null) startBulkClient(id, owner.trust.peer(id).getString("fingerprint"), endpoint, relay.expiresAt, receiverToken);
    }
    private synchronized DeviceByteChannel openOwnedRelay(DeviceRelayLease relay, ClientOwnership ownership, String lane) throws Exception {
        if (!clientOwners.containsValue(ownership)) throw new SecurityException("原主动连接资源已替换");
        currentRelay(relay); return relay.open(lane);
    }
    private static Socket socket(InetSocketAddress address) throws IOException { Socket socket = new Socket(); try { socket.connect(address, 10000); return socket; } catch (IOException unavailable) { socket.close(); throw unavailable; } }
    private static final class PendingCall {
        final String target; final PeerChannel channel; final TaskStoreDeviceTasks.OutboundSendTicket ticket; final CompletableFuture<DeviceCapabilityPort.Reply> result = new CompletableFuture<>();
        PendingCall(String target, PeerChannel channel, TaskStoreDeviceTasks.OutboundSendTicket ticket) { this.target = target; this.channel = channel; this.ticket = ticket; }
    }
    /** The original per-op SQL ticket is the ONLY side-effect send path. Native frame claim is
     * performed immediately before TLS write by DeviceConnection, never by queueing here. */
    public DeviceCapabilityPort.Reply once(String target, DeviceTaskEnvelope request, TaskStoreDeviceTasks.OutboundSendTicket ticket, RunControl original) throws Exception {
        java.util.Objects.requireNonNull(ticket); return exchange(target, request, ticket, original::check, request.deadline());
    }
    public DeviceCapabilityPort.Reply once(String target, DeviceTaskEnvelope request, RunControl original) throws Exception {
        if (!Set.of("device.describe", "operation.get", "task.get", "task.events").contains(request.method())) throw new SecurityException("写操作必须使用原任务的准确发送票据");
        return exchange(target, request, null, original::check, request.deadline());
    }
    private DeviceCapabilityPort.Reply exchange(String target, DeviceTaskEnvelope request, TaskStoreDeviceTasks.OutboundSendTicket ticket, DeviceArtifactTransfer.Current originalCurrent, long nativeDeadline) throws Exception {
        originalCurrent.require(); PeerChannel exact = controlChannel(target);
        if (exact == null || exact.connection.state() != DeviceConnection.State.ONLINE && exact.connection.state() != DeviceConnection.State.DEGRADED) throw new IOException("设备尚未连接，未派发请求");
        if (pending.size() >= 32) throw new IOException("设备请求等待窗口已满，未派发请求");
        String id = request.requestId(); PendingCall call = new PendingCall(target, exact, ticket);
        if (pending.putIfAbsent(id, call) != null) throw new SecurityException("同一原请求仍在等待，不能重复派发");
        try {
            if (!(ticket != null ? exact.connection.sendControl(ticket) : exact.connection.sendControl(DeviceProtocol.canonicalBytes(request.json())))) throw new IOException("发送队列已满，未派发新请求");
            long until = Math.min(nativeDeadline, Math.min(request.deadline(), System.currentTimeMillis() + 15000));
            while (System.currentTimeMillis() < until) {
                originalCurrent.require(); try { return call.result.get(Math.min(1000, Math.max(1, until - System.currentTimeMillis())), TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException waiting) { }
            }
            throw new IOException("设备回执尚未核对，请查询原操作，勿重提任务");
        } finally { pending.remove(id, call); }
    }
    /** Fixed-result reads use a native HistoryQuery, not an execution RunControl. Expired
     * execution/scope withdrawal cannot be silently converted into a new run or send ticket. */
    PairingCoordinator.OutgoingGrant currentManagementGrant(String target, String originalGrantId) throws Exception {
        PeerChannel actual = controlChannel(target); if (!channelOnline(actual)) throw new IOException("下游设备尚未连接，停止状态未知");
        JSONObject original = owner.trust.outgoingGrant(target, originalGrantId); if (original == null) throw new SecurityException("没有原设备签发的管理授权回执");
        PairingCoordinator.OutgoingGrant current = owner.pairing().acceptOutgoingGrant(actual.peer, original, System.currentTimeMillis());
        if (!originalGrantId.equals(current.grantId())) throw new SecurityException("管理授权不是原任务的准确grant"); return current;
    }
    public PairingCoordinator.HistoryRoute currentHistoryRoute(String target, String originalGrantId) throws Exception {
        PeerChannel exact = controlChannel(target); if (!channelOnline(exact)) throw new IOException("设备尚未连接，请连接后核对结果");
        return owner.pairing().historyRoute(exact.peer, originalGrantId, System.currentTimeMillis());
    }
    public DeviceCapabilityPort.Reply readHistory(PairingCoordinator.HistoryQuery query, DeviceArtifactTransfer.Current ioCancellation) throws Exception {
        java.util.Objects.requireNonNull(query); java.util.Objects.requireNonNull(ioCancellation).require();
        DeviceTaskEnvelope request = query.envelope(); PeerChannel exact = controlChannel(request.targetDeviceId());
        if (!channelOnline(exact)) throw new IOException("设备尚未连接，未读取结果");
        query.requireTargetPeer(exact.peer, System.currentTimeMillis());
        return exchange(request.targetDeviceId(), request, null, () -> { ioCancellation.require(); query.requireTargetPeer(exact.peer, System.currentTimeMillis()); if (controlChannel(request.targetDeviceId()) != exact) throw new IOException("原结果读取通道已变化"); }, request.deadline());
    }
    /** Only the original native HistoryHost owns this read-only envelope/cancellation pair.
     * Its SQL evidence and opaque HistoryRoute mint the request; no UI or model wire is accepted. */
    DeviceCapabilityPort.Reply readOwnerHistory(DeviceTaskEnvelope request, RunControl originalReadCancellation) throws Exception {
        if (!Set.of("operation.get", "task.get", "task.events").contains(request.method()) || request.sideEffecting()) throw new SecurityException("历史窗口只读取原执行结果");
        return exchange(request.targetDeviceId(), request, null, originalReadCancellation::check, request.deadline());
    }
    DeviceArtifactFetch.ReadReply readImageChunk(TaskStoreDeviceTasks.DeviceReceiveEvidence evidence, PairingCoordinator.HistoryQuery query,
            DeviceIdentity.AuthenticatedPeer expected, long nativeDeadline) throws Exception {
        evidence.requireCurrent(); PeerChannel exact = controlChannel(expected.deviceId());
        if (!channelOnline(exact) || !samePeer(expected, exact.peer) || query.envelope().deadline() > nativeDeadline) throw new SecurityException("图像读取不是原证据的准确通道和期限");
        return new DeviceArtifactFetch.ReadReply(query.envelope(), readHistory(query, evidence::requireCurrent));
    }
    private static boolean samePeer(DeviceIdentity.AuthenticatedPeer expected, DeviceIdentity.AuthenticatedPeer actual) {
        return expected != null && actual != null && expected.deviceId().equals(actual.deviceId()) && expected.certificateFingerprint().equals(actual.certificateFingerprint());
    }
    private PeerChannel bulkChannel(String id) { return selectedChannel(id, outboundBulkChannels, bulkChannels); }
    /** Only a privately issued SQL receive evidence calls this. A channel carries no authority
     * by itself; every chunk still needs exact registered content, caller certificate and Current. */
    void prepareArtifactReceive(DeviceIdentity.AuthenticatedPeer expected, long deadline, DeviceArtifactTransfer.Current originalReceive) throws Exception {
        originalReceive.require(); if (deadline <= System.currentTimeMillis()) throw new SecurityException("原图像接收期限已到");
        synchronized (this) {
            PeerChannel online = bulkChannel(expected.deviceId());
            if (channelOnline(online)) { if (!samePeer(expected, online.peer)) throw new SecurityException("成果通道身份已变化"); return; }
            ReconnectingDeviceClient selected = selectedClient(expected.deviceId(), bulkClients);
            if (selected == null || !selected.isRunning()) {
                if (bulkClients.size() >= 8 && !bulkClients.containsKey(expected.deviceId())) throw new IOException("设备成果连接窗口已满");
                JSONObject config = boundEndpoint(expected.deviceId()); if (config == null) throw new IOException("未保存这台设备的成果地址，请更新连接地址");
                JSONObject known = currentPeer(expected.deviceId()); if (!expected.certificateFingerprint().equals(known.optString("fingerprint"))) throw new SecurityException("原成果目标身份已变化");
                DeviceEndpointResolver.Resolved address = DeviceEndpointResolver.resolveInvitation(new JSONObject().put("inviterDeviceId", expected.deviceId()).put("connectionHint", config.getJSONObject("connectionHint")), trustedRelays());
                startBulkClient(expected.deviceId(), expected.certificateFingerprint(), address, deadline);
            }
        }
        long until = Math.min(deadline, System.currentTimeMillis() + 15000);
        while (System.currentTimeMillis() < until) { originalReceive.require(); PeerChannel exact = bulkChannel(expected.deviceId()); if (channelOnline(exact)) { if (!samePeer(expected, exact.peer)) throw new SecurityException("成果通道身份已变化"); return; } Thread.sleep(Math.min(50, Math.max(1, until - System.currentTimeMillis()))); }
        throw new IOException("成果通道尚未连接，未宣称图像已送达");
    }
    private void startBulkClient(String id, String fingerprint, DeviceEndpointResolver.Resolved address, long deadline) throws Exception { startBulkClient(id, fingerprint, address, deadline, null); }
    private synchronized void startBulkClient(String id, String fingerprint, DeviceEndpointResolver.Resolved address, long deadline, String receiverToken) throws Exception {
        ReconnectingDeviceClient selected = selectedClient(id, bulkClients);
        ClientOwnership selectedOwnership = selected == null ? null : clientOwners.get(selected);
        if (selectedOwnership != null && selected.isRunning() && java.util.Objects.equals(selectedOwnership.receiverToken, receiverToken)) return;
        if (bulkClients.size() >= 8 && !bulkClients.containsKey(id)) throw new IOException("设备成果连接窗口已满");
        DeviceIdentity.LocalIdentity local = requireRelayIdentity(); DeviceRelayLease relay = "relay".equals(address.kind) ? peerRelay(address.serviceBase, id, false) : null;
        if (relay != null) { currentRelay(relay); deadline = Math.min(deadline, relay.expiresAt); }
        if (receiverToken != null && (!receiverToken.equals(networkOwnerToken) || !ownerEnabled())) throw new SecurityException("原设备接收资源已关闭或替换");
        closeClientExact(bulkClients.get(id));
        ClientOwnership ownership = new ClientOwnership(id, relay, receiverToken);
        ReconnectingDeviceClient client = new ReconnectingDeviceClient(true, deadline, () -> {
            JSONObject peer = currentPeer(id); if (!fingerprint.equals(peer.optString("fingerprint"))) throw new SecurityException("成果目标信任已变化");
            DeviceIdentity.LocalIdentity actual = requireRelayIdentity(); if (!local.deviceId().equals(actual.deviceId()) || !local.instanceId().equals(actual.instanceId())) throw new SecurityException("原本机设备身份已变化");
            if (relay != null) currentRelay(relay);
            return new DeviceConnection(DeviceTlsContexts.forPeer(actual, Base64.getDecoder().decode(peer.getString("certificateDer"))), relay == null || actual.deviceId().compareTo(id) < 0, DeviceConnection.Lane.BULK,
                    relay == null ? new SocketByteChannel(socket(address.bulkAddress())) : openOwnedRelay(relay, ownership, "bulk"), listener(DeviceConnection.Lane.BULK, true, ownership));
        });
        bulkClients.put(id, client); clientOwners.put(client, ownership); Thread worker = new Thread(client, "device-paired-bulk"); worker.setDaemon(true); worker.start();
    }
    boolean queueArtifactChunk(String target, String fingerprint, UUID transfer, long offset, byte[] raw, long deadline, DeviceArtifactTransfer.Current originalRead) throws Exception {
        originalRead.require(); if (System.currentTimeMillis() >= deadline) throw new SecurityException("原成果读取期限已到");
        PeerChannel exact = bulkChannel(target); if (!channelOnline(exact) || !fingerprint.equals(exact.peer.certificateFingerprint())) throw new IOException("原成果接收通道不可用");
        return exact.connection.sendChunk(transfer, offset, raw, actualTlsTarget -> {
            originalRead.require();
            if (System.currentTimeMillis() >= deadline || !samePeer(exact.peer, actualTlsTarget)) throw new SecurityException("原成果受众、期限或实际发送通道已变化");
        }); // QUEUED only. Full original SQL/TTL/scope gate runs again immediately before TLS write.
    }
    synchronized void closeBulkForPeer(String id) {
        try { closeClientExact(bulkClients.get(id)); } catch (IOException ignored) { }
        PeerChannel in = bulkChannels.remove(id), out = outboundBulkChannels.remove(id);
        if (in != null) try { in.connection.close(); } catch (IOException ignored) { }
        if (out != null) try { out.connection.close(); } catch (IOException ignored) { }
    }
    private PeerChannel controlChannel(String id) {
        return selectedChannel(id, outboundChannels, channels);
    }
    private PeerChannel selectedChannel(String id, Map<String, PeerChannel> outgoing, Map<String, PeerChannel> incomingChannels) {
        PeerChannel outbound = outgoing.get(id), incoming = incomingChannels.get(id);
        if (outbound != null && !matchesSelectedRoute(id, outbound.connection.path(), outbound.ownership)) outbound = null;
        if (incoming != null && !matchesSelectedRoute(id, incoming.connection.path(), incoming.ownership)) incoming = null;
        if (channelOnline(outbound)) return outbound;
        return incoming != null ? incoming : outbound;
    }
    private ReconnectingDeviceClient selectedClient(String id, Map<String, ReconnectingDeviceClient> candidates) {
        ReconnectingDeviceClient client = candidates.get(id); ClientOwnership ownership = client == null ? null : clientOwners.get(client);
        if (ownership == null) return null;
        DeviceConnection active = client.active();
        DeviceTransportStatus.Path path = active != null ? active.path() : ownership.relay == null ? DeviceTransportStatus.Path.DIRECT_TLS : DeviceTransportStatus.Path.RELAY_WSS;
        return matchesSelectedRoute(id, path, ownership) ? client : null;
    }
    /** A saved explicit relay choice is not permission to silently use an old LAN channel.
     * Filtering is reachability policy only; the original TLS/SQL gates remain authoritative. */
    private boolean matchesSelectedRoute(String id, DeviceTransportStatus.Path actual, ClientOwnership ownership) {
        if (ownership != null && (!id.equals(ownership.peerId) || !clientOwners.containsValue(ownership))) return false;
        try {
            String raw = preferences.getString(endpointKey(id), null);
            if (raw == null) return actual == DeviceTransportStatus.Path.DIRECT_TLS;
            JSONObject hint = DeviceProtocol.parseObject(raw.getBytes(StandardCharsets.UTF_8)).getJSONObject("connectionHint");
            if ("lan".equals(hint.optString("kind"))) return actual == DeviceTransportStatus.Path.DIRECT_TLS;
            if (!"relay".equals(hint.optString("kind")) || actual != DeviceTransportStatus.Path.RELAY_WSS || ownership == null || ownership.relay == null) return false;
            DeviceRelayLease relay = ownership.relay;
            return relay.serviceBase.equals(hint.optString("serviceBase")) && relay.boundTo(id)
                    && relayLeases.get(relayKey(relay.serviceBase, relay.inviterDeviceId)) == relay
                    && relay.current(relay.localDeviceId, relay.localInstanceId, System.currentTimeMillis());
        } catch (Exception unreadable) { return false; }
    }
    private void closeClientExact(ReconnectingDeviceClient client) throws IOException {
        if (client == null) return;
        ClientOwnership ownership = clientOwners.remove(client);
        clients.entrySet().removeIf(row -> row.getValue() == client); bulkClients.entrySet().removeIf(row -> row.getValue() == client);
        IOException failure = null; try { client.close(); } catch (IOException unknown) { failure = unknown; }
        if (ownership != null) for (Map<String, PeerChannel> lane : List.of(outboundChannels, outboundBulkChannels)) {
            for (Map.Entry<String, PeerChannel> row : lane.entrySet()) if (row.getValue().ownership == ownership && lane.remove(row.getKey(), row.getValue()))
                try { row.getValue().connection.close(); } catch (IOException unknown) { failure = unknown; }
        }
        if (failure != null) throw failure;
    }
    private synchronized void stopOutboundPeer(String id) throws IOException {
        IOException failure = null;
        for (ReconnectingDeviceClient client : new ReconnectingDeviceClient[]{clients.get(id), bulkClients.get(id)})
            if (client != null) try { closeClientExact(client); } catch (IOException unknown) { failure = unknown; }
        if (failure != null) throw failure;
    }
    private synchronized void stopClientsForLease(DeviceRelayLease exact) throws IOException {
        IOException failure = null;
        for (Map.Entry<ReconnectingDeviceClient, ClientOwnership> row : clientOwners.entrySet()) if (row.getValue().relay == exact)
            try { closeClientExact(row.getKey()); } catch (IOException unknown) { failure = unknown; }
        if (failure != null) throw failure;
    }
    private static boolean channelOnline(PeerChannel channel) { return channel != null && (channel.connection.state() == DeviceConnection.State.ONLINE || channel.connection.state() == DeviceConnection.State.DEGRADED); }
    public synchronized void revoke(String id) throws Exception {
        closeBulkForPeer(id);
        closeClientExact(clients.get(id));
        PeerChannel channel = channels.remove(id), outbound = outboundChannels.remove(id);
        if (channel != null) channel.connection.close(DeviceConnection.State.REVOKED);
        if (outbound != null) outbound.connection.close(DeviceConnection.State.REVOKED);
        capabilities.remove(id); capabilityChannels.remove(id);
        if (!preferences.edit().remove(endpointKey(id)).commit()) throw new IOException("信任已撤销，但旧连接地址清理尚未确认");
        outgoingGrants.entrySet().removeIf(row -> row.getValue().targetDeviceId().equals(id));
        relayLeases.entrySet().removeIf(row -> { if (!row.getValue().boundTo(id)) return false; row.getValue().close(); return true; });
    }
    /** Receiver opt-out closes only the exact inbound resources it owns. It neither cancels an
     * accepted task nor borrows that switch to stop independently owner-approved source clients. */
    private synchronized void closeReceiverNetwork(String token) throws Exception {
        if (!token.equals(networkOwnerToken)) return;
        Exception failure = null;
        for (DevicePairingEndpoint invitation : invitations.values()) try { invitation.close(); } catch (Exception unknown) { failure = unknown; }
        invitations.clear();
        for (Map.Entry<ReconnectingDeviceClient, ClientOwnership> row : clientOwners.entrySet()) if (token.equals(row.getValue().receiverToken))
            try { closeClientExact(row.getKey()); } catch (Exception unknown) { failure = unknown; }
        if (control != null) try { control.close(); } catch (Exception unknown) { failure = unknown; } finally { control = null; }
        if (bulk != null) try { bulk.close(); } catch (Exception unknown) { failure = unknown; } finally { bulk = null; }
        owner.changed(); if (failure != null) throw failure;
    }
    @Override public synchronized void close() throws Exception {
        DeviceReceiverServiceResource owned = receiver; if (owned != null) owned.requestStop(); Exception failure = null;
        try { closeReceiverNetwork(networkOwnerToken); } catch (Exception unknown) { failure = unknown; }
        for (ReconnectingDeviceClient client : new java.util.HashSet<>(clientOwners.keySet())) try { closeClientExact(client); } catch (Exception unknown) { failure = unknown; }
        clients.clear();
        for (PairingCompletionListener recovery : recoveries.values()) try { recovery.close(); } catch (IOException unknown) { failure = unknown; }
        recoveries.clear();
        for (RelayPairingEndpoint recovery : relayRecoveries.values()) recovery.close(); relayRecoveries.clear();
        for (String id : new java.util.HashSet<>(bulkClients.keySet())) closeBulkForPeer(id);
        for (String id : new java.util.HashSet<>(bulkChannels.keySet())) closeBulkForPeer(id);
        outgoing.clear(); descriptions.clear();
        for (PeerChannel channel : channels.values()) try { channel.connection.close(); } catch (Exception unknown) { failure = unknown; }
        for (PeerChannel channel : outboundChannels.values()) try { channel.connection.close(); } catch (Exception unknown) { failure = unknown; }
        channels.clear(); outboundChannels.clear(); capabilities.clear(); capabilityChannels.clear(); outgoingGrants.clear(); outgoingGrantLimitReached = false;
        for (DeviceRelayLease lease : relayLeases.values()) lease.close(); relayLeases.clear();
        owner.changed(); if (failure != null) throw failure;
    }
}
