package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Exact artifact methods for the existing DeviceIngress switch, no second request envelope.
 * Publication is native-only and must originate in the original Artifact/ProjectPackage flow.
 * Access verifies the original task/run/operation, source audience and exact receive/export
 * approval. This byte catalog cannot provide that authority. Do not call while holding Tasks. */
public final class DeviceArtifactEndpoint implements AutoCloseable {
    public interface Access { void require(PairingCoordinator.Permission permission, DeviceArtifactTransfer.Reference exact) throws Exception; }
    public interface BulkSender {
        /** Resolve the current authenticated bulk lane by this certificate-bound permission.
         * true means QUEUED ONLY; false means explicit backpressure, never delivery. */
        boolean queue(PairingCoordinator.Permission permission, DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current originalRead,
            UUID transfer, long offset, byte[] raw) throws Exception;
        /** Native host closes/marks the affected BULK lane unavailable; never edits task success. */
        default void unavailable(PairingCoordinator.Permission permission) { }
    }
    public interface HistoryAccess {
        /** Read only the ORIGINAL accepted-artifact audience from native SQL, using this
         * exact catalog object; never reconstruct authority from query strings. */
        JSONObject acceptedAudience(DeviceTaskEnvelope query, DeviceArtifactTransfer.Reference exact) throws Exception;
        void require(PairingCoordinator.HistoryPermission history, DeviceArtifactTransfer.Reference exact) throws Exception;
    }
    public interface HistoryBulkSender {
        boolean queue(PairingCoordinator.HistoryPermission history, DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current originalRead,
            UUID transfer, long offset, byte[] raw) throws Exception;
        default void unavailable(PairingCoordinator.HistoryPermission history) { }
    }
    private interface QueueChunk { boolean queue(UUID transfer, long offset, byte[] raw) throws Exception; }
    private record Published(DeviceArtifactTransfer.Reference reference, long revision, Long executorEpoch) { }
    private final DeviceArtifactTransfer cache;
    private final String deviceId, instanceId;
    private final Access access;
    private final BulkSender sender;
    private final HistoryAccess historyAccess;
    private final HistoryBulkSender historySender;
    private final Map<String, Published> published = new java.util.LinkedHashMap<>();
    private final Map<String, Published> publishing = new HashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor reads = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(2), runnable -> { Thread thread = new Thread(runnable, "device-artifact-read"); thread.setDaemon(true); return thread; }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public DeviceArtifactEndpoint(DeviceArtifactTransfer cache, String nativeDeviceId, String nativeInstanceId, Access access, BulkSender sender) {
        this(cache, nativeDeviceId, nativeInstanceId, access, sender, null, null);
    }
    public DeviceArtifactEndpoint(DeviceArtifactTransfer cache, String nativeDeviceId, String nativeInstanceId, Access access, BulkSender sender, HistoryAccess historyAccess, HistoryBulkSender historySender) {
        this.historyAccess = historyAccess; this.historySender = historySender;
        if ((historyAccess == null) != (historySender == null)) throw new IllegalArgumentException("Both native artifact history adapters required");
        this.cache = java.util.Objects.requireNonNull(cache); this.deviceId = DeviceProtocol.deviceId(nativeDeviceId, "deviceId"); this.instanceId = DeviceProtocol.identifier(nativeInstanceId, "instanceId");
        this.access = java.util.Objects.requireNonNull(access); this.sender = java.util.Objects.requireNonNull(sender); reads.allowCoreThreadTimeOut(true);
    }
    public JSONObject publishNative(DeviceArtifactTransfer.Reference exact, long revision, long executorEpoch, DeviceArtifactTransfer.Exporter originalExporter, DeviceArtifactTransfer.Current nativeApproval) throws Exception {
        return publishNative(exact, revision, Long.valueOf(executorEpoch), originalExporter, nativeApproval);
    }
    /** Null target epoch is valid ONLY when no actual target native run exists. Request
     * correlation remains separately fixed on Reference and cannot become a target runner. */
    public JSONObject publishNative(DeviceArtifactTransfer.Reference exact, long revision, Long executorEpoch, DeviceArtifactTransfer.Exporter originalExporter, DeviceArtifactTransfer.Current nativeApproval) throws Exception {
        nativeApproval.require();
        if (!deviceId.equals(exact.sourceDeviceId)) throw new SecurityException("Artifact source is not this native device");
        JSONObject wire = DeviceArtifactTransfer.toWireDescriptor(exact, instanceId, revision, exact.runId, executorEpoch, null, null);
        cache.expireScreenshots(System.currentTimeMillis());
        Published reservation = new Published(exact, revision, executorEpoch);
        java.util.List<DeviceArtifactTransfer.Reference> retired = new java.util.ArrayList<>();
        synchronized (published) {
            published.entrySet().removeIf(entry -> {
                try { JSONObject ref = entry.getValue().reference.json(); return "screenshot".equals(ref.optString("kind")) && ref.optLong("expiresAt", Long.MAX_VALUE) <= System.currentTimeMillis(); }
                catch (Exception invalid) { return false; }
            });
            Published prior = published.get(exact.artifactId);
            if (prior != null) {
                if (!prior.reference.contentIdentity().equals(exact.contentIdentity()) || prior.revision != revision || !java.util.Objects.equals(prior.executorEpoch, executorEpoch)) throw new SecurityException("Artifact already refers to a different fixed version; publish a new artifact");
                return wire; // Same native publication is idempotent; do not run the exporter again.
            }
            if (publishing.containsKey(exact.artifactId)) throw new java.io.IOException("Original artifact publication in progress");
            while (published.size() + publishing.size() >= DeviceArtifactTransfer.MAX_TRANSFERS) {
                Published victim = null;
                for (Published candidate : published.values()) if ("screenshot".equals(candidate.reference.json().getString("kind")) && !cache.isReadPinned(candidate.reference.transferId)) { victim = candidate; break; }
                if (victim == null) throw new java.io.IOException("Native artifact catalog full; files and active reads cannot be evicted");
                published.remove(victim.reference.artifactId); retired.add(victim.reference);
            }
            publishing.put(exact.artifactId, reservation);
        }
        try {
            for (var old : retired) cache.discardSupersededScreenshotIfPresent(old);
            cache.freeze(exact, originalExporter, nativeApproval, old -> {
                synchronized (published) {
                    Published previous = published.get(old.artifactId);
                    if (previous != null && previous.reference.contentIdentity().equals(old.contentIdentity())) published.remove(old.artifactId);
                }
            });
            synchronized (published) {
                if (publishing.get(exact.artifactId) != reservation) throw new SecurityException("Native publication reservation changed");
                published.put(exact.artifactId, reservation); publishing.remove(exact.artifactId);
            }
            return wire;
        } catch (Exception failed) {
            synchronized (published) { if (publishing.get(exact.artifactId) == reservation) publishing.remove(exact.artifactId); }
            throw failed;
        }
    }
    private Published resolve(String artifactId) {
        synchronized (published) { Published artifact = published.get(artifactId); if (artifact == null) throw new SecurityException("Original fixed artifact is unavailable"); return artifact; }
    }
    /** Native catalog lookup for original audience resolution, not wire admission or IO. */
    public JSONObject acceptedHistoryAudience(DeviceTaskEnvelope query) throws Exception {
        if (historyAccess == null || !(query.method().equals("artifact.describe") || query.method().equals("artifact.readChunk"))) throw new SecurityException("Original artifact history adapter unavailable");
        Published artifact = resolve(query.arguments().getString("artifactId"));
        return historyAccess.acceptedAudience(query, artifact.reference);
    }
    /** Called AFTER DeviceIngress obtained the unique typed execution Permission. */
    public JSONObject receive(PairingCoordinator.Permission permission) throws Exception {
        permission.checkCurrent(System.currentTimeMillis()); DeviceTaskEnvelope request = permission.envelope();
        Published artifact = resolve(request.arguments().getString("artifactId")); DeviceArtifactTransfer.Reference exact = artifact.reference;
        DeviceArtifactTransfer.Current current = () -> { permission.checkCurrent(System.currentTimeMillis()); access.require(permission, exact); requireLive(exact); };
        return receiveFixed(request, artifact, current, (id, offset, raw) -> sender.queue(permission, exact, current, id, offset, raw), () -> sender.unavailable(permission));
    }
    /** Deliberately NOT an execution Permission, grant or invocation path. */
    public JSONObject receiveHistory(PairingCoordinator.HistoryPermission history) throws Exception {
        if (historyAccess == null || historySender == null) throw new SecurityException("Independent native artifact history adapter unavailable");
        history.checkCurrent(System.currentTimeMillis()); DeviceTaskEnvelope request = history.envelope();
        Published artifact = resolve(request.arguments().getString("artifactId")); DeviceArtifactTransfer.Reference exact = artifact.reference;
        DeviceArtifactTransfer.Current current = () -> { history.checkCurrent(System.currentTimeMillis()); historyAccess.require(history, exact); requireLive(exact); };
        return receiveFixed(request, artifact, current, (id, offset, raw) -> historySender.queue(history, exact, current, id, offset, raw), () -> historySender.unavailable(history));
    }
    private static void requireLive(DeviceArtifactTransfer.Reference exact) throws Exception {
        JSONObject fixed = exact.json(); if ("screenshot".equals(fixed.getString("kind")) && fixed.getLong("expiresAt") <= System.currentTimeMillis()) throw new SecurityException("Original observed screenshot expired");
    }
    private JSONObject receiveFixed(DeviceTaskEnvelope request, Published artifact, DeviceArtifactTransfer.Current current, QueueChunk queue, Runnable unavailable) throws Exception {
        if (!request.method().equals("artifact.describe") && !request.method().equals("artifact.readChunk")) throw new SecurityException("Not a fixed artifact read");
        JSONObject arguments = request.arguments(); DeviceArtifactTransfer.Reference exact = artifact.reference;
        current.require();
        synchronized (published) { if (published.get(exact.artifactId) != artifact) throw new SecurityException("Original temporary artifact was superseded and is unavailable"); }
        if (request.method().equals("artifact.describe")) {
            // The native publication already froze this exact manifest. A descriptor is
            // expected content identity, NOT current byte availability or delivery success.
            // Do not hash up to 20MiB on the control lane: readChunk's bounded IO worker
            // verifies actual bytes/current permission; finish alone reports byte delivery.
            return DeviceArtifactTransfer.toWireDescriptor(exact, instanceId, artifact.revision, exact.runId, artifact.executorEpoch, null, null);
        }
        if (!exact.sha256.equals(arguments.getString("contentDigest"))) throw new SecurityException("Chunk request changed the fixed content version");
        long offset = arguments.getLong("offset"); int length = arguments.getInt("length");
        if (offset >= exact.bytes) throw new SecurityException("No artifact bytes at this fixed content cursor");
        int plannedLength = (int) Math.min(length, exact.bytes - offset);
        // File/hash IO never monopolizes the small control lane. Queue slots are bounded;
        // ready means the exact READ was prepared, not that any file/chunk was delivered.
        DeviceArtifactTransfer.ReadPin retained;
        synchronized (published) {
            if (published.get(exact.artifactId) != artifact) throw new SecurityException("Original temporary artifact was superseded and is unavailable");
            retained = cache.pinQueuedRead(exact); // Before queueing: even queued native reads protect the old fixed snapshot.
        }
        ReadWork work = new ReadWork(retained, () -> {
            current.require(); var chunk = cache.readChunk(exact, offset, length, current); current.require();
            if (!queue.queue(UUID.fromString(exact.transferId), offset, chunk.bytes)) unavailable.run();
        }, unavailable);
        try { reads.execute(work); }
        catch (java.util.concurrent.RejectedExecutionException full) { work.cancel(); throw new java.io.IOException("Artifact read queue full; no bytes claimed delivered"); }
        return new JSONObject().put("artifactId", exact.artifactId).put("transferId", exact.transferId).put("contentDigest", exact.sha256)
            .put("offset", offset).put("length", plannedLength).put("status", "ready");
    }
    private interface Read { void run() throws Exception; }
    private static final class ReadWork implements Runnable {
        private final DeviceArtifactTransfer.ReadPin pin; private final Read read; private final Runnable unavailable;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        ReadWork(DeviceArtifactTransfer.ReadPin pin, Read read, Runnable unavailable) { this.pin = pin; this.read = read; this.unavailable = unavailable; }
        public void run() { if (!started.compareAndSet(false, true)) return; try { read.run(); } catch (Exception failure) { unavailable.run(); } finally { pin.close(); } }
        void cancel() { if (started.compareAndSet(false, true)) { pin.close(); unavailable.run(); } }
    }
    @Override public void close() { for (Runnable queued : reads.shutdownNow()) if (queued instanceof ReadWork work) work.cancel(); }
}
