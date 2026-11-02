package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.devices.transport.*;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Native temporary-image byte plumbing. Original SQL issues every Evidence/HistoryPermission;
 * this class creates no task/Book/approval, accepts no peer path, and never declares model vision. */
final class DeviceWorkspaceArtifacts implements AutoCloseable {
    private final DeviceWorkspaceNativeOwner owner;
    private final DeviceArtifactTransfer cache;
    private final DeviceArtifactSession session;
    private final DeviceArtifactEndpoint endpoint;
    private final DeviceArtifactFetch fetch;
    private final ConcurrentHashMap<UUID, TaskStoreDeviceTasks.DeviceReceiveEvidence> receives = new ConcurrentHashMap<>();
    DeviceWorkspaceArtifacts(DeviceWorkspaceNativeOwner owner) throws Exception {
        this.owner = owner; DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) throw new SecurityException("没有原设备身份，未创建成果缓存");
        File directory = new File(owner.app.getFilesDir(), "native-device-artifact-bytes-v1").getAbsoluteFile();
        if (!directory.exists()) Files.createDirectory(directory.toPath());
        cache = new DeviceArtifactTransfer(directory); session = new DeviceArtifactSession(cache);
        endpoint = new DeviceArtifactEndpoint(cache, local.deviceId(), local.instanceId(),
            (permission, exact) -> owner.tasks.deviceTasks().requireArtifactAccess(permission, exact.taskId, requiredRun(exact), exact.operationId),
            new DeviceArtifactEndpoint.BulkSender() {
                public boolean queue(PairingCoordinator.Permission permission, DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current originalRead, UUID transfer, long offset, byte[] bytes) throws Exception {
                    if (!transfer.equals(UUID.fromString(exact.transferId))) throw new SecurityException("成果发送不是原native固定传输");
                    return owner.network.queueArtifactChunk(permission.callerDeviceId(), permission.certificateFingerprint(), transfer, offset, bytes, permission.deadline(), originalRead);
                }
                public void unavailable(PairingCoordinator.Permission permission) { owner.network.closeBulkForPeer(permission.callerDeviceId()); }
            }, new DeviceArtifactEndpoint.HistoryAccess() {
                public JSONObject acceptedAudience(DeviceTaskEnvelope query, DeviceArtifactTransfer.Reference exact) throws Exception { return owner.tasks.deviceTasks().historyAudienceForArtifact(query, exact.taskId, requiredRun(exact), exact.operationId); }
                public void require(PairingCoordinator.HistoryPermission history, DeviceArtifactTransfer.Reference exact) throws Exception {
                    history.checkCurrent(System.currentTimeMillis()); JSONObject audience = history.audience();
                    if (!exact.artifactId.equals(audience.optString("objectId")) || !exact.taskId.equals(audience.optString("taskId")) || !requiredRun(exact).equals(audience.optString("runId"))) throw new SecurityException("成果不属于原SQL历史受众和执行");
                }
            }, new DeviceArtifactEndpoint.HistoryBulkSender() {
                public boolean queue(PairingCoordinator.HistoryPermission history, DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current originalRead, UUID transfer, long offset, byte[] bytes) throws Exception {
                    if (!transfer.equals(UUID.fromString(exact.transferId))) throw new SecurityException("成果发送不是原native历史传输");
                    return owner.network.queueArtifactChunk(history.callerDeviceId(), history.certificateFingerprint(), transfer, offset, bytes, history.deadline(), originalRead);
                }
                public void unavailable(PairingCoordinator.HistoryPermission history) { owner.network.closeBulkForPeer(history.callerDeviceId()); }
            });
        fetch = new DeviceArtifactFetch(cache, session, (expected, exact, offset, length, deadline) -> {
            TaskStoreDeviceTasks.DeviceReceiveEvidence original = receives.get(UUID.fromString(exact.transferId));
            if (original == null) throw new SecurityException("没有这项原操作的native图像接收证据");
            original.requireCurrent();
            DeviceArtifactTransfer.Reference selected = DeviceArtifactTransfer.fromWireDescriptor(original.descriptor());
            if (!selected.contentIdentity().equals(exact.contentIdentity()) || !original.actualControlPeer().deviceId().equals(expected.deviceId()) || !original.actualControlPeer().certificateFingerprint().equals(expected.certificateFingerprint())) throw new SecurityException("图像接收对象或实际控制通道已变化");
            PairingCoordinator.HistoryQuery query = original.historyQuery(owner.existingIdentity(), offset, length, System.currentTimeMillis());
            return owner.network.readImageChunk(original, query, expected, deadline);
        });
    }
    DeviceArtifactFetch.Verified receive(TaskStoreDeviceTasks.DeviceReceiveEvidence original) throws Exception {
        java.util.Objects.requireNonNull(original).requireCurrent(); JSONObject descriptor = original.descriptor();
        DeviceArtifactTransfer.Reference exact = DeviceArtifactTransfer.fromWireDescriptor(descriptor); UUID transfer = UUID.fromString(exact.transferId);
        if (receives.size() >= DeviceArtifactTransfer.MAX_TRANSFERS || receives.putIfAbsent(transfer, original) != null) throw new IOException("原图像接收窗口已满或仍在进行");
        try {
            // A pull channel is needed only when no authenticated bulk channel exists. If this
            // device invited its peer, its real listener may accept the peer's response channel.
            owner.network.prepareArtifactReceive(original.actualControlPeer(), original.deadline(), original::requireCurrent);
            return fetch.fetch(original.actualControlPeer(), descriptor, original.deadline(), original::requireCurrent);
        } finally { receives.remove(transfer, original); }
    }
    JSONObject receiveFile(TaskStoreDeviceTasks.DeviceFileEvidence original) throws Exception {
        original.requireCurrent(); receive(original);
        return new DeviceReceivedAttachmentPublisher(cache, dev.pocketengine.features.attachments.AttachmentRepository.get(owner.app).book)
            .publish(DeviceArtifactTransfer.fromWireDescriptor(original.descriptor()), original.operationId(), original.receivingTaskId(),
                original.frozenRunId(), original.frozenEpoch(), original.originalControl(), original::requireCurrent);
    }
    JSONObject freeze(String id, String run, int epoch, String operation, JSONObject observation, JSONObject actualImage) throws Exception {
        DeviceToolsSession original = owner.app.originalDeviceToolsSession(id);
        if (original == null) throw new SecurityException("没有原设备工具执行器，不发布图像");
        RunControl control = original.originalControl(); DeviceExecutionAdmission admission = original.firstAdmission();
        DeviceArtifactTransfer.Current current = () -> {
            if (owner.app.originalDeviceToolsSession(id) != original || !control.matchesHostRun(id, run, epoch)) throw new SecurityException("原图像任务执行已变化");
            control.check(); admission.checkCurrent(); JSONObject task = owner.tasks.get(id, false);
            if (!TaskStoreDeviceTasks.deviceTask(task) || !run.equals(task.optString("runId")) || epoch != task.optInt("coordinatorEpoch") || !admission.request().resourceBinding().optBoolean("allowWholeDisplayCapture")) throw new SecurityException("这次原图像没有明确屏幕读取授权");
        };
        current.require(); DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        return new DeviceObservedImagePublisher(endpoint, local.deviceId(), admission.request().resourceBinding(), current).freeze(id, run, epoch, operation, observation, actualImage);
    }
    DeviceIngress.Artifacts ingress() { return new DeviceIngress.Artifacts() {
        public JSONObject receive(PairingCoordinator.Permission permission) throws Exception { return endpoint.receive(permission); }
        public JSONObject receiveHistory(PairingCoordinator.HistoryPermission history) throws Exception { return endpoint.receiveHistory(history); }
        public JSONObject acceptedHistoryAudience(DeviceTaskEnvelope query) throws Exception { return endpoint.acceptedHistoryAudience(query); }
    }; }
    void chunk(DeviceIdentity.AuthenticatedPeer peer, UUID transfer, long offset, byte[] bytes) throws Exception { fetch.onChunk(peer, transfer, offset, bytes); }
    void disconnected(DeviceIdentity.AuthenticatedPeer peer) { fetch.disconnected(peer); }
    private static String requiredRun(DeviceArtifactTransfer.Reference exact) { if (exact.runId == null) throw new SecurityException("本机图像必须来自原生真实执行"); return exact.runId; }
    @Override public void close() { fetch.close(); endpoint.close(); receives.clear(); }
}
