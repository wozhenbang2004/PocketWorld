package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** An explicitly imported, already-provisioned native relay slot, NOT device authority.
 * The bearer is memory-only and omitted from every view. Close clears our owned char buffer;
 * immutable UI/library String copies remain subject to JVM collection, not an erasure claim.
 * Invitations reference only serviceBase; they cannot install a route or expand relay trust. */
public final class DeviceRelayLease implements AutoCloseable {
    public static final String FORMAT = "pocketworld.relay-lease.v1";
    private static final ScheduledThreadPoolExecutor DEADLINES;
    static {
        DEADLINES = new ScheduledThreadPoolExecutor(1, r -> { Thread t = new Thread(r, "device-relay-lease-deadline"); t.setDaemon(true); return t; });
        DEADLINES.setRemoveOnCancelPolicy(true);
    }
    public final String serviceBase, inviterDeviceId, localDeviceId, localInstanceId;
    public final long expiresAt;
    private final String routeId, side, wssOrigin;
    private final char[] token;
    @FunctionalInterface interface Connector { DeviceByteChannel open(String endpoint, String token) throws Exception; }
    private final Connector connector;
    private final Map<String, DeviceByteChannel> lanes = new HashMap<>();
    private final java.util.concurrent.ScheduledFuture<?> expiry;
    private String peerDeviceId;
    private boolean closed;

    public static DeviceRelayLease review(String raw, String actualLocalDeviceId, String actualLocalInstanceId, long now) throws Exception {
        return review(raw, actualLocalDeviceId, actualLocalInstanceId, now, RelayByteChannel::new);
    }
    static DeviceRelayLease review(String raw, String actualLocalDeviceId, String actualLocalInstanceId, long now, Connector connector) throws Exception {
        if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("中继租约为空或超过4 KiB");
        JSONObject value;
        try { value = DeviceProtocol.parseObject(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception invalid) { throw new IllegalArgumentException("中继租约JSON无法读取；未显示或记录其私密内容"); }
        Set<String> fields = Set.of("format", "serviceBase", "inviterDeviceId", "routeId", "side", "routeToken", "expiresAt");
        if (value.length() != fields.size()) throw new IllegalArgumentException("中继租约字段不完整");
        for (var keys = value.keys(); keys.hasNext();) if (!fields.contains(keys.next())) throw new IllegalArgumentException("中继租约含额外字段");
        if (!FORMAT.equals(value.getString("format"))) throw new IllegalArgumentException("请选择已有的原生短时中继租约");
        DeviceProtocol.deviceId(actualLocalDeviceId, "localDeviceId"); DeviceProtocol.identifier(actualLocalInstanceId, "localInstanceId");
        String service = value.getString("serviceBase"), inviter = DeviceProtocol.deviceId(value.getString("inviterDeviceId"), "inviterDeviceId");
        URI uri; try { uri = new URI(service); } catch (Exception invalid) { throw new IllegalArgumentException("中继服务地址格式无效"); }
        if (service.length() > 512 || service.chars().anyMatch(c -> c <= 32 || c >= 127) || !Set.of("https", "wss").contains(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !Set.of("", "/").contains(uri.getRawPath()) || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("中继服务须为无凭据、参数或额外路径的明确TLS服务地址");
        String route = value.getString("routeId"), side = value.getString("side"), token = value.getString("routeToken");
        if (!route.matches("[A-Za-z0-9_-]{22,80}") || !Set.of("a", "b").contains(side) || !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("中继路由、端点槽位或短时凭据格式无效");
        long deadline = DeviceProtocol.integer(value.get("expiresAt"), "expiresAt", 1, DeviceProtocol.MAX_SAFE_INTEGER);
        if (deadline <= now || deadline - now > 3_600_000) throw new IllegalArgumentException("已有中继租约的剩余期限须在一小时内");
        return new DeviceRelayLease(service, inviter, actualLocalDeviceId, actualLocalInstanceId, route, side, token, deadline,
                new URI("wss", null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString(), connector);
    }
    private DeviceRelayLease(String service, String inviter, String local, String instance, String route, String side, String token, long deadline, String origin, Connector connector) {
        serviceBase = service; inviterDeviceId = inviter; localDeviceId = local; localInstanceId = instance; routeId = route; this.side = side;
        this.token = token.toCharArray(); expiresAt = deadline; wssOrigin = origin; this.connector = java.util.Objects.requireNonNull(connector);
        expiry = DEADLINES.schedule(this::close, Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    }
    public synchronized void requireCurrent(String actualLocal, String actualInstance, long now) {
        if (closed || now >= expiresAt || !localDeviceId.equals(actualLocal) || !localInstanceId.equals(actualInstance)) throw new SecurityException("中继租约已过期、已移除或不属于当前本机身份");
    }
    public synchronized boolean current(String actualLocal, String actualInstance, long now) {
        try { requireCurrent(actualLocal, actualInstance, now); return true; } catch (SecurityException unavailable) { return false; }
    }
    /** Only the existing native pairing completion/recovery callback binds this route to a peer. */
    public synchronized void bindPeer(String actualPeer) {
        requireCurrent(localDeviceId, localInstanceId, System.currentTimeMillis()); DeviceProtocol.deviceId(actualPeer, "peerDeviceId");
        if (actualPeer.equals(localDeviceId) || !inviterDeviceId.equals(localDeviceId) && !inviterDeviceId.equals(actualPeer)
                || peerDeviceId != null && !peerDeviceId.equals(actualPeer)) throw new SecurityException("中继槽位不属于这个原配对设备");
        peerDeviceId = actualPeer;
    }
    public synchronized boolean boundTo(String actualPeer) { return actualPeer.equals(peerDeviceId); }
    public synchronized boolean canBindPeer(String actualPeer) { return !localDeviceId.equals(actualPeer) && (peerDeviceId == null || peerDeviceId.equals(actualPeer)) && (inviterDeviceId.equals(localDeviceId) || inviterDeviceId.equals(actualPeer)); }
    public synchronized JSONObject view() throws Exception {
        requireCurrent(localDeviceId, localInstanceId, System.currentTimeMillis());
        return new JSONObject().put("serviceBase", serviceBase).put("inviterDeviceId", inviterDeviceId).put("localDeviceId", localDeviceId)
                .put("side", side).put("expiresAt", expiresAt).put("memoryOnly", true).put("devicePermissionGranted", false)
                .put("peerBound", peerDeviceId != null).put("peerDeviceId", peerDeviceId);
    }
    public synchronized DeviceByteChannel open(String lane) throws Exception {
        requireCurrent(localDeviceId, localInstanceId, System.currentTimeMillis());
        if (!Set.of("pairing", "pairing-complete", "control", "bulk").contains(lane)) throw new IllegalArgumentException("未知中继通道");
        DeviceByteChannel old = lanes.remove(lane); if (old != null) old.close();
        DeviceByteChannel next = connector.open(wssOrigin + "/v1/tunnel/" + routeId + "/" + side + "/" + lane, new String(token));
        lanes.put(lane, next); return next;
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; if (expiry != null) expiry.cancel(false); Arrays.fill(token, '\0');
        for (DeviceByteChannel channel : lanes.values()) try { channel.close(); } catch (IOException ignored) { }
        lanes.clear();
    }
    @Override public String toString() { return "Native relay lease (private route omitted)"; }
}
