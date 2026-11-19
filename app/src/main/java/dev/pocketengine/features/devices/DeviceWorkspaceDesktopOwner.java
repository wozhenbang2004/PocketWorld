package dev.pocketengine.features.devices;

import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Selects the original native grant and authenticated capability facts for an
 * owner page. NativeDesktopViewer is the sole retained session/send implementation. */
final class DeviceWorkspaceDesktopOwner implements NativeDeviceWorkspaceHost.DesktopOwner {
    private final DeviceWorkspaceNativeOwner owner;
    private final Executor cleanupWorker;

    DeviceWorkspaceDesktopOwner(DeviceWorkspaceNativeOwner owner, Executor cleanupWorker) {
        this.owner = owner;
        this.cleanupWorker = cleanupWorker;
    }

    @Override public NativeDeviceWorkspaceHost.DesktopAction open(String deviceId, String grantId,
            JSONObject binding, NativeDeviceWorkspaceHost.DesktopCurrent current) throws Exception {
        return openReviewed(deviceId, null, grantId, 0, binding, current);
    }
    @Override public NativeDeviceWorkspaceHost.DesktopAction openReviewed(String deviceId, String instanceId, String grantId,
            long grantEpoch, JSONObject binding, NativeDeviceWorkspaceHost.DesktopCurrent current) throws Exception {
        current.require();
        DeviceProtocol.validateResourceBinding("desktop-v2", binding);
        PairingCoordinator.OutgoingGrant selected = null;
        for (PairingCoordinator.OutgoingGrant candidate : owner.nativeOutgoingGrants()) {
            if (deviceId.equals(candidate.targetDeviceId()) && grantId.equals(candidate.grantId())) {
                if (selected != null) throw new SecurityException("原桌面授权身份有歧义");
                selected = candidate;
            }
        }
        if (selected == null) throw new SecurityException("当前电脑未提供这项原生桌面授权，请刷新或在电脑上更新授权");
        if (instanceId != null && (!instanceId.equals(selected.targetInstanceId()) || grantEpoch != selected.authorizationEpoch()))
            throw new SecurityException("电脑实例或这项授权已变化，请重新选择范围");
        JSONObject facts = owner.authenticatedCapabilities(deviceId);
        if (facts == null) throw new IllegalStateException("未收到原连接的有效能力回执。请核对设备连接后重新读取，不需要因此重新安装伴侣。");
        if (!deviceId.equals(facts.opt("deviceId")) || !selected.targetInstanceId().equals(facts.opt("instanceId")))
            throw new SecurityException("电脑实例与这次授权不一致，请返回核对原设备");
        JSONObject preparation = WindowsDesktopPreparation.from(deviceId, selected.targetInstanceId(), facts, true);
        if (preparation != null) {
            if (!preparation.getBoolean("mediaCandidate")) throw new IllegalStateException(preparation.getString("detail"));
            if (preparation.has("sessionId") && preparation.getLong("sessionId") != binding.getLong("sessionId"))
                throw new SecurityException("所选授权属于另一个 Windows 会话。请返回原会话，或在电脑上重新授权所需范围。");
        }
        JSONObject metadata = facts.optJSONObject("metadata");
        JSONObject desktop = metadata == null ? null : metadata.optJSONObject("desktop");
        JSONObject streaming = desktop == null ? null : desktop.optJSONObject("streaming");
        if (streaming == null || !Boolean.TRUE.equals(streaming.opt("available")))
            throw new IllegalStateException("这台设备未报告可用的桌面视频，请在原设备核对伴侣与授权。");
        DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        if (local == null) throw new SecurityException("原本机设备身份不存在");
        current.require();
        // The exact signed resource supplies the HWND. The PC resolves its own PID;
        // Web JSON cannot introduce a second, separately chosen target identity.
        return new NativeDesktopViewer(owner.tasks, local, selected, binding, owner.exchange(), cleanupWorker, current,
                NativeDesktopViewer.INPUT_RECEIPT_PROFILE.equals(streaming.opt("inputReceiptProfile")));
    }
}
