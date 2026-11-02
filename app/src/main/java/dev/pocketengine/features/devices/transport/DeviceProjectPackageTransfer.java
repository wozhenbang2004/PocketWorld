package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectPackageDelivery;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Reuses the original package exporter and OWNER import review. No directory synchronization,
 * second artifact authority, old grant/credential restoration or automatic code execution.
 * Native callers prove the original SQL/package evidence and exact export/receive approval. */
public final class DeviceProjectPackageTransfer {
    private final LocalModule local;
    private final DeviceArtifactTransfer cache;
    private final DeviceArtifactEndpoint outgoing;
    private final String deviceId;
    public DeviceProjectPackageTransfer(LocalModule originalLocal, DeviceArtifactTransfer cache, DeviceArtifactEndpoint outgoing, String nativeDeviceId) {
        local = java.util.Objects.requireNonNull(originalLocal); this.cache = java.util.Objects.requireNonNull(cache);
        this.outgoing = java.util.Objects.requireNonNull(outgoing); deviceId = DeviceProtocol.deviceId(nativeDeviceId, "deviceId");
    }
    /** `original` is issued by ProjectPackageDelivery.requireArtifact, not a remote JSON path. */
    public JSONObject publish(ProjectPackageDelivery.Reference original, String nativeOriginalRunId, long nativeOriginalEpoch,
            DeviceArtifactTransfer.Current originalSqlAndExactExportApproval) throws Exception {
        originalSqlAndExactExportApproval.require(); JSONObject metadata = original.json();
        String transfer = UUID.nameUUIDFromBytes(("device-project-transfer:" + deviceId + ":" + original.artifactId + ":" + original.archiveSha256).getBytes(StandardCharsets.UTF_8)).toString();
        var reference = new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId", transfer).put("sourceDeviceId", deviceId)
            .put("artifactId", original.artifactId).put("versionId", metadata.getString("versionId")).put("taskId", original.taskId).put("runId", nativeOriginalRunId)
            .put("operationId", original.operationId).put("sha256", original.archiveSha256).put("bytes", original.archiveBytes).put("kind", "project_bundle")
            .put("mimeType", "application/zip").put("name", "project-source.zip"));
        return outgoing.publishNative(reference, metadata.getLong("versionSequence"), nativeOriginalEpoch,
                destination -> local.exportProjectPackageForOwner(original.artifactId, original.archiveSha256, destination), originalSqlAndExactExportApproval);
    }
    /** Explicit NATIVE owner selection only. Merely receiving bytes never calls this method.
     * The result is a staged original package, not an imported project or runnable authority.
     * Continue with LocalModule.planProjectPackageImportForOwner / importProjectPackageForOwner
     * from the existing owner UI after its exact plan/conflict/source review. */
    public JSONObject stageReceivedForOwner(DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current currentOwnerReceiveApproval) throws Exception {
        currentOwnerReceiveApproval.require(); JSONObject reference = exact.json();
        if (!"project_bundle".equals(reference.getString("kind"))) throw new SecurityException("Only a fixed source project package can enter project import review");
        String selection = UUID.nameUUIDFromBytes(("received-device-project:" + exact.sourceDeviceId + ":" + exact.transferId + ":" + exact.sha256).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject staged;
        try (var input = cache.openVerified(exact, currentOwnerReceiveApproval)) { local.stageProjectPackageForOwner(input, selection); }
        currentOwnerReceiveApproval.require();
        staged = local.inspectStagedProjectPackageForOwner(selection, exact.sha256);
        if (!exact.sha256.equals(staged.getString("sha256")) || staged.getLong("sizeBytes") != exact.bytes || !staged.getBoolean("complete")) throw new SecurityException("Native staged project differs from the received fixed content");
        return new JSONObject().put("stagedPackage", staged).put("deviceSource", reference).put("contentVerified", true)
            .put("imported", false).put("originalProjectUnchanged", true).put("executionAuthorityRestored", false).put("taskOrGrantRestored", false);
    }
}
