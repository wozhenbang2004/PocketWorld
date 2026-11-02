package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.PairingCoordinator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;

/** Bounded outbound-only counterpart of LocalReservation. Reconnects only an isolated native
 * invitation/read window; no task, grant or business invocation is retained or replayed here. */
public final class RelayPairingEndpoint implements DevicePairingEndpoint {
    @FunctionalInterface public interface Current { void require() throws Exception; }
    @FunctionalInterface private interface Serve { void run(DeviceByteChannel raw) throws Exception; }
    private final DeviceRelayLease lease;
    private final long deadline;
    private final Current current;
    private final Set<DeviceByteChannel> active = ConcurrentHashMap.newKeySet();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledFuture<?> expiry;
    private volatile boolean closed, candidate, completion;
    public RelayPairingEndpoint(DeviceRelayLease lease, long deadline, Current current) throws Exception {
        this.lease = java.util.Objects.requireNonNull(lease); this.current = java.util.Objects.requireNonNull(current); this.deadline = deadline;
        if (deadline > lease.expiresAt) throw new SecurityException("邀请或恢复窗口不能延长原中继租约");
        check(); expiry = PairingCandidateTransport.closeAt(this, deadline);
    }
    private void check() throws Exception {
        if (closed || System.currentTimeMillis() >= deadline) throw new SecurityException("原中继配对/恢复窗口已关闭");
        lease.requireCurrent(lease.localDeviceId, lease.localInstanceId, System.currentTimeMillis()); current.require();
    }
    private synchronized void start(String lane, Serve serve) throws Exception {
        check();
        if (lane.equals("pairing") ? candidate : completion) throw new SecurityException("原中继配对通道已经开启");
        if (lane.equals("pairing")) candidate = true; else completion = true;
        Thread thread = new Thread(() -> {
            try {
                while (!closed && System.currentTimeMillis() < deadline) {
                    DeviceByteChannel raw = null;
                    try { check(); raw = lease.open(lane); active.add(raw); check(); serve.run(raw); }
                    catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); break; }
                    catch (Exception unavailable) { try { check(); } catch (Exception expiredOrRevoked) { break; } /* No bearer, candidate or grant diagnostics. */ }
                    finally { if (raw != null) { active.remove(raw); try { raw.close(); } catch (Exception ignored) { } } }
                    try { Thread.sleep(300); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
                }
            } finally { workers.remove(Thread.currentThread()); if (lane.equals("pairing")) candidate = false; else completion = false; }
        }, "device-relay-" + lane);
        thread.setDaemon(true); workers.add(thread); thread.start();
    }
    public AutoCloseable startCandidate(SSLContext keys, PairingCandidateTransport.CandidateHandler validator) throws Exception {
        start("pairing", raw -> PairingCandidateTransport.serve(true, keys, raw, deadline, bytes -> { check(); return validator.inspect(bytes); })); return this;
    }
    public AutoCloseable startCompletion(SSLContext context, PairingCoordinator.PendingPairing pending, PairingCompletionTransport.Server owner) throws Exception {
        if (pending.expiresAt() != deadline) throw new SecurityException("中继确认不属于原邀请期限");
        start("pairing-complete", raw -> PairingCompletionTransport.serve(context, pending, raw, (peer, bytes) -> { check(); return owner.complete(peer, bytes); })); return this;
    }
    public void startRecovery(SSLContext context, PairingCoordinator.CommittedPairingRecovery original, PairingCompletionTransport.Server owner) throws Exception {
        if (original.expiresAt() != deadline) throw new SecurityException("中继恢复不属于原生只读窗口");
        start("pairing-complete", raw -> PairingCompletionTransport.serveRecovery(context, original, raw, (peer, bytes) -> { check(); return owner.complete(peer, bytes); }));
    }
    public JSONObject connectionHint() throws Exception { check(); if (!candidate) throw new SecurityException("中继会合窗口尚未开启"); return new JSONObject().put("kind", "relay").put("serviceBase", lease.serviceBase).put("inviterDeviceId", lease.inviterDeviceId); }
    public boolean isCandidateListening() { try { check(); return candidate; } catch (Exception unavailable) { return false; } }
    public boolean isCompletionListening() { try { check(); return completion; } catch (Exception unavailable) { return false; } }
    @Override public synchronized void close() {
        if (closed) return; closed = true; candidate = false; completion = false; if (expiry != null) expiry.cancel(false);
        for (DeviceByteChannel raw : active) try { raw.close(); } catch (Exception ignored) { }
        active.clear(); for (Thread worker : workers) worker.interrupt(); workers.clear();
    }
}
