package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceCapabilityPort;
import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Native, bounded READ-only byte fetch on the ORIGINAL capability/artifact worker.
 * No executor, runner, task ledger, authority factory, application retry or new wire shape.
 * The original host must retain its SQL operation/run/audience and receive approval in Current.
 * A descriptor, chunk-ready response or connection never means the model received an image. */
public final class DeviceArtifactFetch implements AutoCloseable {
    public record ReadReply(DeviceTaskEnvelope request, DeviceCapabilityPort.Reply reply) { }
    public interface ReadChunks {
        /** Use the original current native read/history route and exact-connection requestId
         * future; return that ORIGINAL query and its actual TLS reply. Never send an effect.
         * This call must honor nativeDeadline; transport cannot interrupt an arbitrary host. */
        ReadReply readChunk(DeviceIdentity.AuthenticatedPeer expectedControlPeer, DeviceArtifactTransfer.Reference exact,
                long offset, int length, long nativeDeadline) throws Exception;
    }
    private static final class Pending {
        final DeviceIdentity.AuthenticatedPeer control;
        final DeviceArtifactTransfer.Reference exact;
        final DeviceArtifactTransfer.Current current;
        long offset, next; int length; boolean waiting, receiving, received;
        Exception failure;
        DeviceIdentity.AuthenticatedPeer lastBulk;
        Pending(DeviceIdentity.AuthenticatedPeer control, DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current current) {
            this.control = control; this.exact = exact; this.current = current;
        }
    }
    /** Constructible only after actual full bytes/hash/atomic cache verification. This is
     * still not a model-delivery receipt, business outcome or reconstructed permission. */
    public final class Verified {
        private final DeviceArtifactTransfer.Reference exact;
        private final DeviceArtifactTransfer.Current current;
        private Verified(DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current current) { this.exact = exact; this.current = current; }
        public DeviceArtifactTransfer.Reference reference() { return exact; }
        public InputStream openVerified() throws Exception { return cache.openVerified(exact, current); }
        /** Explicit native temporary-cache cleanup AFTER the observation importer has
         * copied the complete image. Never removes a user file, Book result or project. */
        public void discardTemporaryImage() throws Exception {
            if (!"screenshot".equals(exact.json().getString("kind"))) throw new SecurityException("Only a native temporary received screenshot may be discarded here");
            cache.discardVerifiedScreenshot(exact); // Erasing this exact private image must remain possible after read authority is withdrawn.
        }
        /** Native observation importer only. Reads ALL fixed screenshot bytes before return,
         * rechecks current authority and exact hash; never fabricates imageProvidedToModel. */
        public byte[] readImageBytes(int nativeMaxBytes) throws Exception {
            JSONObject fixed = exact.json(); String mime = fixed.getString("mimeType");
            if (!"screenshot".equals(fixed.getString("kind")) || !java.util.Set.of("image/png", "image/jpeg", "image/webp").contains(mime)
                    || nativeMaxBytes < 1 || nativeMaxBytes > DeviceArtifactTransfer.MAX_BYTES || exact.bytes < 1 || exact.bytes > nativeMaxBytes) throw new SecurityException("Native image byte bound or screenshot provenance invalid");
            byte[] raw;
            try (InputStream input = openVerified(); ByteArrayOutputStream out = new ByteArrayOutputStream((int) exact.bytes)) {
                byte[] chunk = new byte[DeviceArtifactTransfer.CHUNK_BYTES]; int count;
                while ((count = input.read(chunk)) != -1) {
                    current.require(); if (out.size() + count > exact.bytes) throw new SecurityException("Verified screenshot grew");
                    out.write(chunk, 0, count);
                }
                raw = out.toByteArray(); java.util.Arrays.fill(chunk, (byte) 0);
            }
            try {
                current.require();
                if (raw.length != exact.bytes || !DeviceProtocol.sha256(raw).equals(exact.sha256)) throw new SecurityException("Actual screenshot bytes differ from their fixed identity");
                return raw;
            } catch (Exception invalid) { java.util.Arrays.fill(raw, (byte) 0); throw invalid; }
        }
    }
    private final DeviceArtifactTransfer cache;
    private final DeviceArtifactSession session;
    private final ReadChunks reads;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private volatile boolean closed;
    public DeviceArtifactFetch(DeviceArtifactTransfer cache, DeviceArtifactSession session, ReadChunks originalReadRoute) {
        this.cache = java.util.Objects.requireNonNull(cache); this.session = java.util.Objects.requireNonNull(session); reads = java.util.Objects.requireNonNull(originalReadRoute);
    }
    /** Blocking only on the original host's bounded IO/capability worker, NEVER onControl.
     * Explicitly fetching the same exact manifest may resume its fixed byte checkpoint;
     * this method does not retry even a read query after any exception/disconnection. */
    public Verified fetch(DeviceIdentity.AuthenticatedPeer actualControlPeer, JSONObject uniqueDescriptor, long nativeDeadline,
            DeviceArtifactTransfer.Current originalSqlAudienceAndReceiveApproval) throws Exception {
        java.util.Objects.requireNonNull(actualControlPeer); java.util.Objects.requireNonNull(originalSqlAudienceAndReceiveApproval);
        JSONObject wire = DeviceProtocol.validateMessage(uniqueDescriptor);
        DeviceArtifactTransfer.Reference exact = DeviceArtifactTransfer.fromWireDescriptor(wire);
        if (!actualControlPeer.deviceId().equals(exact.sourceDeviceId)) throw new SecurityException("Artifact source differs from the actual control TLS peer");
        if (nativeDeadline <= System.currentTimeMillis() || nativeDeadline > DeviceProtocol.MAX_SAFE_INTEGER) throw new SecurityException("Original native receive deadline required");
        DeviceArtifactTransfer.Current current = () -> {
            if (closed) throw new IOException("Native artifact fetch closed");
            if (System.currentTimeMillis() >= nativeDeadline) throw new SecurityException("Original native receive deadline expired");
            originalSqlAudienceAndReceiveApproval.require();
        };
        current.require(); UUID transfer = UUID.fromString(exact.transferId); Pending item = new Pending(actualControlPeer, exact, current);
        synchronized (pending) {
            if (closed || pending.size() >= DeviceArtifactTransfer.MAX_TRANSFERS || pending.containsKey(transfer)) throw new IOException("Native artifact fetch unavailable, full or already active");
            pending.put(transfer, item);
        }
        try {
            long offset = session.prepareIncoming(actualControlPeer, exact, current);
            while (offset < exact.bytes) {
                current.require(); int length = (int) Math.min(DeviceArtifactTransfer.CHUNK_BYTES, exact.bytes - offset);
                long chunkDeadline = Math.min(nativeDeadline, System.currentTimeMillis() + 30_000);
                synchronized (item) {
                    if (item.failure != null) throw item.failure;
                    item.offset = offset; item.length = length; item.waiting = true; item.receiving = item.received = false;
                }
                ReadReply reply = reads.readChunk(actualControlPeer, exact, offset, length, chunkDeadline);
                current.require(); requireReady(reply, item, wire.getString("instanceId"), offset, length, chunkDeadline);
                long waitNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, chunkDeadline - System.currentTimeMillis()));
                for (;;) {
                    current.require();
                    synchronized (item) {
                        if (item.failure != null) throw item.failure;
                        if (item.received) { offset = item.next; item.waiting = false; break; }
                        if (System.nanoTime() >= waitNanos) throw new IOException("Original artifact chunk unavailable before its native deadline");
                        item.wait(100);
                    }
                }
            }
            current.require(); synchronized (item) { if (item.failure != null) throw item.failure; }
            session.finish(item.lastBulk == null ? actualControlPeer : item.lastBulk, transfer); current.require();
            synchronized (item) { if (item.failure != null) throw item.failure; }
            return new Verified(exact, current);
        } finally {
            synchronized (item) { if (item.failure == null) item.failure = new IOException("Native fetch no longer registered"); item.notifyAll(); }
            session.releaseIncoming(actualControlPeer, transfer); // Keep only fixed native byte checkpoints, never an old authorization.
            // Keep the catalog slot reserved until its old Session binding is gone. A
            // concurrent exact-manifest fetch must not install a new binding just before
            // this old cleanup releases it (same transfer id does not mean same attempt).
            synchronized (pending) { if (pending.get(transfer) == item) pending.remove(transfer); }
        }
    }
    private static boolean samePeer(DeviceIdentity.AuthenticatedPeer first, DeviceIdentity.AuthenticatedPeer second) {
        return first != null && second != null && first.deviceId().equals(second.deviceId()) && first.certificateFingerprint().equals(second.certificateFingerprint());
    }
    private static void requireReady(ReadReply value, Pending item, String instance, long offset, int length, long deadline) throws Exception {
        if (value == null || value.request == null || value.reply == null || !samePeer(item.control, value.reply.peer())) throw new SecurityException("Chunk response is not from the exact original control peer");
        DeviceTaskEnvelope query = DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(value.request.json()));
        JSONObject args = query.arguments();
        if (!"artifact.readChunk".equals(query.method()) || query.sideEffecting() || !item.control.deviceId().equals(query.targetDeviceId()) || !instance.equals(query.instanceId()) || query.deadline() > deadline
                || !item.exact.artifactId.equals(args.getString("artifactId")) || !item.exact.sha256.equals(args.getString("contentDigest")) || offset != args.getLong("offset") || length != args.getInt("length")) throw new SecurityException("Original read query changed the fixed artifact cursor or audience");
        JSONObject response = DeviceProtocol.validateMessage(value.reply.response());
        if (!"DeviceResponse".equals(response.getString("messageType")) || !query.requestId().equals(response.getString("requestId")) || !query.method().equals(response.getString("method"))
                || !item.control.deviceId().equals(response.getString("executorDeviceId")) || !instance.equals(response.getString("instanceId"))) throw new SecurityException("Chunk response is not bound to the original exact request/device instance");
        JSONObject ready = response.getJSONObject("result");
        if (!item.exact.artifactId.equals(ready.getString("artifactId")) || !item.exact.transferId.equals(ready.getString("transferId")) || !item.exact.sha256.equals(ready.getString("contentDigest"))
                || offset != ready.getLong("offset") || length != ready.getInt("length") || !"ready".equals(ready.getString("status"))) throw new SecurityException("Chunk-ready metadata differs from the original manifest; it is not delivery");
    }
    /** Actual BULK lane callback only. Unregistered/out-of-order/foreign bytes fail closed.
     * Native disk IO stays on that independent bulk IO path, never the control callback. */
    public void onChunk(DeviceIdentity.AuthenticatedPeer actualBulkPeer, UUID transfer, long offset, byte[] checksumVerifiedRaw) throws Exception {
        Pending item; synchronized (pending) { item = pending.get(transfer); }
        if (item == null || !samePeer(item.control, actualBulkPeer)) throw new SecurityException("Bulk bytes lack a registered exact control-peer manifest");
        synchronized (item) {
            if (item.failure != null || !item.waiting || item.receiving || item.received || offset != item.offset || checksumVerifiedRaw == null || checksumVerifiedRaw.length != item.length) throw new SecurityException("Bulk chunk differs from the one original bounded pending cursor");
            item.receiving = true;
        }
        try {
            item.current.require(); long next = session.accept(actualBulkPeer, transfer, offset, checksumVerifiedRaw); item.current.require();
            synchronized (item) { item.next = next; item.lastBulk = actualBulkPeer; item.received = true; item.receiving = false; item.notifyAll(); }
        } catch (Exception failed) { synchronized (item) { item.failure = failed; item.receiving = false; item.notifyAll(); } throw failed; }
    }
    public void disconnected(DeviceIdentity.AuthenticatedPeer actualPeer) {
        if (actualPeer == null) return;
        java.util.List<Pending> snapshot; synchronized (pending) { snapshot = new java.util.ArrayList<>(pending.values()); }
        for (Pending item : snapshot) if (samePeer(item.control, actualPeer)) synchronized (item) { item.failure = new IOException("Original artifact channel disconnected; checkpoint retained, no automatic replay"); item.notifyAll(); }
        session.disconnected(actualPeer);
    }
    @Override public void close() {
        java.util.List<Pending> snapshot; synchronized (pending) { closed = true; snapshot = new java.util.ArrayList<>(pending.values()); }
        for (Pending item : snapshot) synchronized (item) { item.failure = new IOException("Native artifact fetch closed; no task cancelled or completed"); item.notifyAll(); }
    }
}
