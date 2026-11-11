package dev.pocketengine.features.devices.transport;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android DNS-SD is an address hint only. No identities, grants, invitation tokens,
 * certificate trust, connection attempts or business requests are published or inferred. */
public final class LanDeviceDiscovery implements AutoCloseable {
    public static final String SERVICE_TYPE = "_pocketworld._tcp.";
    private final NsdManager manager;
    private final WifiManager wifi;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final BooleanSupplier receiverCurrent;
    private final long deadline = SystemClock.elapsedRealtime() + 60_000;
    private final long expiresAt = System.currentTimeMillis() + 60_000;
    private final String ownName = "PocketWorld-" + UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private final Map<String, NsdServiceInfo> seen = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private final Runnable expiry = this::close;
    private WifiManager.MulticastLock multicast;
    private boolean started, closed, discovering, advertising, resolveBusy, discoveryRequested, registrationRequested;
    private String registeredName = ownName, notice = "正在查找局域网服务；找到地址不代表已认证或已连接。";
    private int skipped, unregisterAttempts, stopAttempts;

    public static final class Candidate {
        public final String handle = UUID.randomUUID().toString(), host;
        public final int controlPort, bulkPort;
        private Candidate(String host, int controlPort, int bulkPort) { this.host = host; this.controlPort = controlPort; this.bulkPort = bulkPort; }
        public JSONObject address() throws Exception {
            return new JSONObject().put("host", host).put("controlPort", controlPort).put("bulkPort", bulkPort);
        }
    }
    /** Prepare only: the owner must retain this object before any system request is made. */
    public LanDeviceDiscovery(Context context, BooleanSupplier receiverCurrent) throws Exception {
        this.receiverCurrent = receiverCurrent;
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) throw new IllegalStateException("本机没有 DNS-SD 服务；仍可使用原设备邀请。");
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }
    public synchronized void start(boolean advertise, int controlPort, int bulkPort) {
        if (started || closed) throw new IllegalStateException("本次局域网查找不能重复启动。");
        started = true;
        try {
            if (wifi != null) { multicast = wifi.createMulticastLock("pocketworld-owner-discovery"); multicast.setReferenceCounted(false); multicast.acquire(); }
            discoveryRequested = true;
            try { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
            catch (RuntimeException unavailable) { discoveryRequested = false; throw unavailable; }
            if (advertise) {
                if (!receiverCurrent.getAsBoolean() || controlPort < 1 || bulkPort < 1) throw new SecurityException("请先启用本机设备接收，再临时公开地址。");
                NsdServiceInfo service = new NsdServiceInfo(); service.setServiceName(ownName); service.setServiceType(SERVICE_TYPE);
                service.setPort(controlPort); service.setAttribute("v", "1"); service.setAttribute("bulk", String.valueOf(bulkPort));
                registrationRequested = true;
                try { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration); }
                catch (RuntimeException unavailable) { registrationRequested = false; throw unavailable; }
            }
            timer.postDelayed(expiry, 60_000);
        } catch (Exception failure) {
            close();
            // Return the retained, closed session so the original Window can expose stopPending.
            // A failed start must not hide an in-flight system discovery/registration behind an exception.
            notice = "本次局域网查找启动未完成；" + notice;
        }
    }
    private boolean current() { return started && !closed && SystemClock.elapsedRealtime() < deadline; }
    private static String key(NsdServiceInfo service) { return service.getServiceName(); }
    private static boolean ours(NsdServiceInfo service) { return SERVICE_TYPE.equals(service.getServiceType()) || SERVICE_TYPE.substring(0, SERVICE_TYPE.length() - 1).equals(service.getServiceType()); }
    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) { synchronized (LanDeviceDiscovery.this) { if (!current()) { stopDiscovery(); return; } discovering = true; } }
        public void onStartDiscoveryFailed(String type, int code) { synchronized (LanDeviceDiscovery.this) { discoveryRequested = false; discovering = false; notice = "局域网发现未能启动（" + code + "）；可继续使用短期邀请。"; } }
        public void onStopDiscoveryFailed(String type, int code) { synchronized (LanDeviceDiscovery.this) {
            notice = "停止发现回执尚未确认（" + code + "）；本页候选已失效。";
            if (stopAttempts < 3) timer.postDelayed(() -> { synchronized (LanDeviceDiscovery.this) { stopDiscovery(); } }, 500);
        } }
        public void onDiscoveryStopped(String type) { synchronized (LanDeviceDiscovery.this) { discoveryRequested = false; discovering = false; } }
        public void onServiceFound(NsdServiceInfo service) { synchronized (LanDeviceDiscovery.this) {
            if (!current() || !ours(service) || registeredName.equals(key(service)) || ownName.equals(key(service)) || seen.containsKey(key(service))) return;
            if (seen.size() >= 32) { skipped++; return; }
            seen.put(key(service), service); queue.addLast(service); resolveNext();
        } }
        public void onServiceLost(NsdServiceInfo service) { synchronized (LanDeviceDiscovery.this) { seen.remove(key(service)); candidates.remove(key(service)); queue.removeIf(row -> key(row).equals(key(service))); } }
    };
    @SuppressWarnings("deprecation") private void resolveNext() {
        if (!current() || resolveBusy || queue.isEmpty()) return;
        NsdServiceInfo expected = queue.removeFirst(); resolveBusy = true;
        try { manager.resolveService(expected, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo service, int code) { synchronized (LanDeviceDiscovery.this) { resolveBusy = false; skipped++; resolveNext(); } }
            public void onServiceResolved(NsdServiceInfo service) { synchronized (LanDeviceDiscovery.this) {
                resolveBusy = false;
                if (current() && seen.get(key(expected)) == expected) try {
                    InetAddress address = service.getHost(); Map<String, byte[]> attributes = service.getAttributes();
                    String version = attribute(attributes, "v"), bulk = attribute(attributes, "bulk");
                    if (!"1".equals(version) || !bulk.matches("[0-9]{1,5}") || address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                            || address.isMulticastAddress() || address.isLinkLocalAddress() || address.getHostAddress().contains("%")) throw new IllegalArgumentException();
                    int controlPort = service.getPort(), bulkPort = Integer.parseInt(bulk);
                    if (controlPort < 1 || controlPort > 65535 || bulkPort < 1 || bulkPort > 65535 || controlPort == bulkPort) throw new IllegalArgumentException();
                    if (candidates.size() < 16 && candidates.values().stream().noneMatch(row -> row.host.equals(address.getHostAddress()) && row.controlPort == controlPort && row.bulkPort == bulkPort))
                        candidates.put(key(expected), new Candidate(address.getHostAddress(), controlPort, bulkPort));
                } catch (Exception invalid) { skipped++; }
                resolveNext();
            } }
        }); } catch (RuntimeException unavailable) { resolveBusy = false; skipped++; resolveNext(); }
    }
    private static String attribute(Map<String, byte[]> values, String key) {
        byte[] bytes = values.get(key); return bytes == null || bytes.length > 8 ? "" : new String(bytes, StandardCharsets.US_ASCII);
    }
    private final NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
        public void onServiceRegistered(NsdServiceInfo service) { synchronized (LanDeviceDiscovery.this) {
            registeredName = service.getServiceName(); advertising = true;
            seen.remove(registeredName); candidates.remove(registeredName); queue.removeIf(row -> registeredName.equals(key(row)));
            if (!current() || !receiverCurrent.getAsBoolean()) unregister();
        } }
        public void onRegistrationFailed(NsdServiceInfo service, int code) { synchronized (LanDeviceDiscovery.this) { registrationRequested = false; advertising = false; notice = "本机地址未能广播（" + code + "），查找功能单独核对。"; } }
        public void onServiceUnregistered(NsdServiceInfo service) { synchronized (LanDeviceDiscovery.this) { registrationRequested = false; advertising = false; } }
        public void onUnregistrationFailed(NsdServiceInfo service, int code) { synchronized (LanDeviceDiscovery.this) {
            notice = "系统尚未确认撤回本机地址广播（" + code + "）。";
            if (unregisterAttempts < 3) timer.postDelayed(() -> { synchronized (LanDeviceDiscovery.this) { unregister(); } }, 500);
        } }
    };
    private void unregister() { if (registrationRequested && unregisterAttempts++ < 3) try { manager.unregisterService(registration); } catch (RuntimeException unavailable) { notice = "系统尚未确认撤回本机地址广播。"; } }
    private void stopDiscovery() { if (discoveryRequested && stopAttempts++ < 3) try { manager.stopServiceDiscovery(discovery); } catch (RuntimeException unavailable) { notice = "系统尚未确认停止查找；本页不再接受候选。"; } }
    public synchronized Candidate select(String handle) {
        if (!current()) throw new SecurityException("本次局域网发现已结束，请重新查找。");
        return candidates.values().stream().filter(row -> row.handle.equals(handle)).findFirst().orElseThrow(() -> new SecurityException("该地址已离开本次发现结果。"));
    }
    public synchronized void requireCandidate(Candidate expected) { if (select(expected.handle) != expected) throw new SecurityException("原局域网地址候选已变化。"); }
    public synchronized JSONObject view() throws Exception {
        if (!current() && !closed) close();
        if (advertising && !receiverCurrent.getAsBoolean()) unregister();
        JSONArray rows = new JSONArray();
        if (current()) for (Candidate candidate : candidates.values()) rows.put(new JSONObject().put("candidateId", candidate.handle).put("host", candidate.host)
                .put("controlPort", candidate.controlPort).put("bulkPort", candidate.bulkPort).put("authenticated", false));
        return new JSONObject().put("active", current()).put("discovering", discovering && current()).put("advertising", advertising)
                .put("stopPending", closed && (discoveryRequested || registrationRequested)).put("expiresAt", expiresAt).put("notice", notice).put("candidates", rows).put("skipped", skipped).put("serviceType", SERVICE_TYPE);
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; notice = "本次查找已结束；旧候选不再用于连接。";
        timer.removeCallbacks(expiry); candidates.clear(); seen.clear(); queue.clear(); stopDiscovery(); unregister();
        if (multicast != null) { try { if (multicast.isHeld()) multicast.release(); } catch (RuntimeException unavailable) { notice += " 系统组播资源释放未确认。"; } finally { multicast = null; } }
    }
}
