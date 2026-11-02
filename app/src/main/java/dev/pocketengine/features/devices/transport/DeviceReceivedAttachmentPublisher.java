package dev.pocketengine.features.devices.transport;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.io.InputStream;
import org.json.JSONObject;

/** Original Book publication of real received file/screenshot bytes. No new task store,
 * auto project import, remote path, permission restoration or business-success claim. */
public final class DeviceReceivedAttachmentPublisher {
    private final DeviceArtifactTransfer cache;
    private final AttachmentBook book;
    public DeviceReceivedAttachmentPublisher(DeviceArtifactTransfer cache, AttachmentBook originalBook) {
        this.cache = java.util.Objects.requireNonNull(cache); book = java.util.Objects.requireNonNull(originalBook);
    }
    /** Called on the original bounded artifact IO worker, never a control/network callback.
     * current must prove original local operation/audience + explicit receive permission.
     * Both stream reads AND the Book's pause-revision commit retain that native check. */
    public JSONObject publish(DeviceArtifactTransfer.Reference exact, String originalLocalOperationId,
            String frozenReceivingTaskId, String frozenReceivingRunId, int frozenReceivingEpoch,
            RunControl original, DeviceArtifactTransfer.Current current) throws Exception {
        // These are the ORIGINAL receiving operation's native SQL fields, never the wire
        // source task or a newly resumed run. They freeze Book idempotency across restarts.
        DeviceProtocol.identifier(frozenReceivingTaskId, "frozenReceivingTaskId");
        DeviceProtocol.identifier(frozenReceivingRunId, "frozenReceivingRunId");
        RunControl.HostRunIdentity local = java.util.Objects.requireNonNull(original).hostRunIdentity();
        if (local == null || !local.taskId().equals(frozenReceivingTaskId) || frozenReceivingEpoch < 1) throw new SecurityException("Original receiving operation/task scope is required");
        java.util.Objects.requireNonNull(current);
        if (!originalLocalOperationId.matches("[a-f0-9-]{36}")) throw new SecurityException("Original local publication operation required");
        JSONObject descriptor = exact.json(); String kind = descriptor.getString("kind");
        if (kind.equals("screenshot") && descriptor.getLong("expiresAt") <= System.currentTimeMillis()) throw new SecurityException("Observed image expired; capture again");
        if (!kind.equals("file") && !kind.equals("screenshot")) throw new SecurityException("Project bundles require the original owner review/import path");
        String role = "device_file_" + DeviceProtocol.sha256((exact.sourceDeviceId + "\0" + exact.artifactId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 24);
        String plan = DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("reference", descriptor).put("receivingTaskId", frozenReceivingTaskId).put("receivingRunId", frozenReceivingRunId).put("receivingEpoch", frozenReceivingEpoch)));
        // The SQL authority also checks this RunControl. Suppress only this callback's
        // recursive invocation on the same thread, not the SQL check or other authorities.
        ThreadLocal<Boolean> checking = new ThreadLocal<>();
        try (AutoCloseable retained = original.retainAuthorityCheck(() -> {
            if (Boolean.TRUE.equals(checking.get())) return;
            checking.set(true);
            try { current.require(); } finally { checking.remove(); }
        })) {
            current.require(); original.checkpoint();
            JSONObject prior = book.findNativeResult(originalLocalOperationId, role, frozenReceivingTaskId, frozenReceivingRunId, plan, original);
            if (prior.getBoolean("found")) return receipt(exact, prior, true);
            cache.finish(exact, current); // Full disk/hash + atomic byte landing, not metadata.
            JSONObject source = new JSONObject().put("kind", "device_received_file").put("sourceDeviceId", exact.sourceDeviceId)
                .put("artifactId", exact.artifactId).put("transferId", exact.transferId).put("versionId", exact.versionId).put("contentDigest", exact.sha256)
                .put("taskId", frozenReceivingTaskId).put("receivingOriginalTaskId", frozenReceivingTaskId).put("receivingOriginalRunId", frozenReceivingRunId).put("receivingOriginalEpoch", frozenReceivingEpoch)
                .put("sourceTaskId", exact.taskId).put("sourceRunId", exact.runId).put("sourceOperationId", exact.operationId).put("descriptorDigest", exact.contentIdentity());
            for (String field : new String[]{"executorEpoch", "requestRunId", "requestExecutorEpoch"}) if (descriptor.has(field)) source.put("source" + Character.toUpperCase(field.charAt(0)) + field.substring(1), descriptor.get(field));
            if (kind.equals("screenshot")) source.put("observationRef", descriptor.getJSONObject("observationRef")).put("resourceBinding", descriptor.getJSONObject("resourceBinding")).put("expiresAt", descriptor.getLong("expiresAt"));
            try (InputStream actualBytes = cache.openVerified(exact, current)) {
                JSONObject published = book.importNativeResult(actualBytes, descriptor.getString("name"), descriptor.getString("mimeType"), source,
                    originalLocalOperationId, role, frozenReceivingTaskId, frozenReceivingRunId, plan, original);
                return receipt(exact, published, false);
            }
        }
    }
    private static JSONObject receipt(DeviceArtifactTransfer.Reference exact, JSONObject attachment, boolean originalPublication) throws Exception {
        if (!exact.sha256.equals(attachment.getString("sha256")) || exact.bytes != attachment.getLong("size")) throw new SecurityException("Original Book byte identity differs from the fixed descriptor");
        return new JSONObject().put("attachment", attachment).put("bytesVerified", true).put("attachmentPublished", true).put("originalPublication", originalPublication)
            .put("previewOpened", false).put("businessVerified", false).put("executionAuthorityRestored", false);
    }
}
