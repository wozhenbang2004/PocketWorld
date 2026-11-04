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
        JSONObject facts = owner.authenticatedCapabilities(deviceId);
        JSONObject metadata = facts == null ? null : facts.optJSONObject("metadata");
        JSONObject desktop = metadata == null ? null : metadata.optJSONObject("desktop");
        JSONObject streaming = desktop == null ? null : desktop.optJSONObject("streaming");
        if (streaming == null || !Boolean.TRUE.equals(streaming.opt("available")))
            throw new IllegalStateException("电脑桌面流尚未就绪，请安装当前 Windows Companion 并刷新设备");
        DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        if (local == null) throw new SecurityException("原本机设备身份不存在");
        current.require();
        // The exact signed resource supplies the HWND. The PC resolves its own PID;
        // Web JSON cannot introduce a second, separately chosen target identity.
        return new NativeDesktopViewer(owner.tasks, local, selected, binding, owner.exchange(), cleanupWorker, current);
    }
}
