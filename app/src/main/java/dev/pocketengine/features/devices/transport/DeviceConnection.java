package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/** One connection attempt. Reconnection never retains or replays an application frame.
 * onControl must run DeviceProtocol validation and the CURRENT PairingCoordinator permission gate.
 * online/written are transport facts, never persistent business accepted/succeeded receipts. */
public final class DeviceConnection implements AutoCloseable, Runnable {
    public enum Lane { CONTROL, BULK }
    public enum State { CONNECTING, ONLINE, DEGRADED, OFFLINE, REVOKED, INCOMPATIBLE }
    public interface BulkReadGate { void require(DeviceIdentity.AuthenticatedPeer actualTlsTarget) throws Exception; }
    public interface Listener {
        /** Runs after real mTLS, before onOnline and before ANY application frame.
         * Bind replies to this exact native channel; a peer id may have many channels. */
        default void onConnection(DeviceConnection connection, DeviceIdentity.AuthenticatedPeer peer) throws Exception { }
        /** Reconcile ORIGINAL ids only. Runs on a bounded callback worker while TLS keeps
         * pumping, so awaiting this channel's read-only query cannot self-deadlock. */
        void onOnline(DeviceIdentity.AuthenticatedPeer peer) throws Exception;
        /** Bounded native validation / durable admission / receipt handoff ONLY. Never
         * await an entire Goal/tool here; use the ORIGINAL host worker/AgentRunner. */
        void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] json) throws Exception;
        void onChunk(DeviceIdentity.AuthenticatedPeer peer, UUID transfer, long offset, byte[] bytes) throws Exception;
        /** Query original native op marker; a gate exception may itself be uncertain. */
        default void onSendGateFailed(TaskStoreDeviceTasks.OutboundSendTicket ticket) { }
        void onClosed(State state, boolean deliveryMayBeUnknown);
    }
    private static final java.util.concurrent.ThreadPoolExecutor ONLINE_CALLBACKS = new java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), runnable -> { Thread thread = new Thread(runnable, "device-online-reconcile"); thread.setDaemon(true); return thread; }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private java.util.concurrent.Future<?> onlineCallback;
    private final DeviceByteChannel bytes;
    private final SSLContext context;
    private final boolean client;
    private final Lane lane;
    private final Listener listener;
    private static final class Outbound {
        final byte[] frame; final TaskStoreDeviceTasks.OutboundSendTicket ticket; final BulkReadGate bulkReadGate;
        Outbound(byte[] frame, TaskStoreDeviceTasks.OutboundSendTicket ticket) { this(frame, ticket, null); }
        Outbound(byte[] frame, TaskStoreDeviceTasks.OutboundSendTicket ticket, BulkReadGate gate) { this.frame = frame; this.ticket = ticket; bulkReadGate = gate; }
    }
    private final ArrayBlockingQueue<Outbound> outgoing;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile State state = State.CONNECTING;
    private volatile State terminalState = State.OFFLINE;
    private volatile boolean authenticated;
    private volatile Thread worker;
    private boolean dispatched;
    public DeviceConnection(SSLContext pinnedMutualTlsContext, boolean client, Lane lane, DeviceByteChannel bytes, Listener listener) {
        context = java.util.Objects.requireNonNull(pinnedMutualTlsContext); this.client = client; this.lane = java.util.Objects.requireNonNull(lane);
        this.bytes = java.util.Objects.requireNonNull(bytes); this.listener = java.util.Objects.requireNonNull(listener);
        outgoing = new ArrayBlockingQueue<>(lane == Lane.CONTROL ? 16 : 2);
    }
    /** Protocol query/reply bytes ONLY. Business effects use the native ticket overload.
     * A true result is queued only, never proof of unsent/accepted/executed. */
    public boolean sendControl(byte[] json) throws IOException {
        if (lane != Lane.CONTROL) throw new IOException("Control requires its reserved TLS lane");
        return queue(new Outbound(DeviceFrameCodec.control(json), null));
    }
    /** Queue an opaque original-op ticket without claiming or copying its frozen wire.
     * Current native scope/peer/hash/run/epoch are checked once at the actual write gate. */
    public boolean sendControl(TaskStoreDeviceTasks.OutboundSendTicket ticket) throws IOException {
        if (lane != Lane.CONTROL) throw new IOException("Native business dispatch requires control lane");
        java.util.Objects.requireNonNull(ticket);
        if (ticket.size() < 1 || ticket.size() > DeviceFrameCodec.MAX_CONTROL) throw new IOException("Native frozen control outside frame bound");
        return queue(new Outbound(null, ticket));
    }
    /** Required NATIVE current Permission/History and exact-peer gate, rechecked at actual
     * TLS write. Queued/read bytes are not delivered; previously dispatched bytes cannot be recalled. */
    public boolean sendChunk(UUID transfer, long offset, byte[] raw, BulkReadGate nativeCurrentRead) throws IOException {
        if (lane != Lane.BULK) throw new IOException("Artifact bytes require the separate bulk TLS lane");
        java.util.Objects.requireNonNull(nativeCurrentRead);
        return queue(new Outbound(DeviceFrameCodec.chunk(transfer, offset, raw), null, nativeCurrentRead));
    }
    private synchronized boolean queue(Outbound frame) throws IOException {
        boolean queued = false;
        try {
            if (stopped.get() || state != State.ONLINE && state != State.DEGRADED) throw new IOException("Device offline; no frame retained for reconnect");
            queued = outgoing.offer(frame); return queued;
        } finally { if (!queued && frame.frame != null) java.util.Arrays.fill(frame.frame, (byte) 0); }
    }
    public State state() { return state; }
    public DeviceTransportStatus.Path path() {
        return bytes instanceof RelayByteChannel ? DeviceTransportStatus.Path.RELAY_WSS : bytes instanceof SocketByteChannel ? DeviceTransportStatus.Path.DIRECT_TLS : DeviceTransportStatus.Path.UNKNOWN;
    }
    public boolean wasAuthenticated() { return authenticated; }
    public void markDegraded() { if (state == State.ONLINE) state = State.DEGRADED; }
    @Override public void run() {
        if (worker != null) throw new IllegalStateException("Connection already started"); worker = Thread.currentThread();
        DeviceFrameCodec.Decoder decoder = new DeviceFrameCodec.Decoder();
        try {
            SSLEngine engine = context.createSSLEngine(); engine.setUseClientMode(client);
            try (PlatformTlsEngine tls = new PlatformTlsEngine(engine, bytes)) {
                DeviceIdentity.AuthenticatedPeer peer = DeviceIdentity.authenticatedPeer(tls.handshake(System.currentTimeMillis() + 15_000));
                if (stopped.get()) return; authenticated = true; state = State.ONLINE; listener.onConnection(this, peer);
                onlineCallback = ONLINE_CALLBACKS.submit(() -> {
                    try { if (!stopped.get()) listener.onOnline(peer); }
                    catch (Exception failed) { try { close(); } catch (IOException ignored) { } }
                });
                DeviceFrameCodec.Listener sink = new DeviceFrameCodec.Listener() {
                    @Override public void control(byte[] json) throws Exception { if (lane != Lane.CONTROL) throw new IOException("Control on bulk lane"); listener.onControl(peer, json); }
                    @Override public void chunk(UUID transfer, long offset, byte[] raw) throws Exception { if (lane != Lane.BULK) throw new IOException("Bulk on control lane"); listener.onChunk(peer, transfer, offset, raw); }
                };
                while (!stopped.get()) {
                    Outbound next = outgoing.poll(); byte[] frame = next == null ? null : next.frame;
                    if (next != null && next.ticket != null) {
                        byte[] claimed = null;
                        try {
                            // The sole linearization point is original prepared -> unknown SQL
                            // claim. Never claim at enqueue or replay on a new connection.
                            claimed = next.ticket.claimForWrite(peer);
                            frame = DeviceFrameCodec.control(claimed);
                        } catch (Exception gateFailed) {
                            // A failed claim may have committed. Never abandon/refund here.
                            listener.onSendGateFailed(next.ticket);
                        } finally { if (claimed != null) java.util.Arrays.fill(claimed, (byte) 0); }
                    }
                    if (frame != null) {
                        try {
                            if (next.bulkReadGate != null) next.bulkReadGate.require(peer);
                            dispatched = true; tls.write(ByteBuffer.wrap(frame));
                        } finally { java.util.Arrays.fill(frame, (byte) 0); }
                    }
                    tls.poll(frame == null ? 100 : 1, data -> decoder.accept(data, sink));
                }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception unavailable) { /* No request replay or payload-bearing diagnostics. Business owner reconciles original IDs. */ }
        finally {
            stopped.set(true); state = terminalState;
            if (onlineCallback != null) { onlineCallback.cancel(true); ONLINE_CALLBACKS.purge(); }
            Outbound queued;
            while ((queued = outgoing.poll()) != null) {
                if (queued.frame != null) java.util.Arrays.fill(queued.frame, (byte) 0);
                if (queued.ticket != null) try {
                    // Native CAS must still be prepared. A duplicate alias may have won
                    // claim already; the original ledger, NEVER this connection bool, decides.
                    if (!queued.ticket.isClaimed()) queued.ticket.abandonBeforeWrite();
                } catch (Exception unknown) { /* Preserve original hold; no inferred refund. */ }
            }
            decoder.clear(); try { bytes.close(); } catch (IOException ignored) { }
            listener.onClosed(state, dispatched);
        }
    }
    @Override public void close() throws IOException { close(State.OFFLINE); }
    /** Native owner revocation/version negotiation only; connection state never edits task facts. */
    public void close(State reason) throws IOException {
        if (reason != State.OFFLINE && reason != State.REVOKED && reason != State.INCOMPATIBLE) throw new IllegalArgumentException("Invalid terminal transport state");
        if (!stopped.get() || terminalState == State.OFFLINE) terminalState = reason;
        stopped.set(true); state = terminalState; bytes.close();
    }
}
