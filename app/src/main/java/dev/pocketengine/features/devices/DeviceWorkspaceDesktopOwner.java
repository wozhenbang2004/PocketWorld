package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStoreDesktopViewer;
import java.util.Set;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Native, retained owner page sessions. JSON chooses an existing grant/resource;
 * it never supplies a peer, request envelope, control permission or send ticket. */
final class DeviceWorkspaceDesktopOwner implements NativeDeviceWorkspaceHost.DesktopOwner {
    private final DeviceWorkspaceNativeOwner owner;
    private final Executor cleanupWorker;
    DeviceWorkspaceDesktopOwner(DeviceWorkspaceNativeOwner owner, Executor worker) {
        this.owner = owner; cleanupWorker = worker;
    }

    @Override public NativeDeviceWorkspaceHost.DesktopSession open(String deviceId, String grantId,
            JSONObject binding, JSONObject target, NativeDeviceWorkspaceHost.OwnerCurrent current) throws Exception {
        current.require(); DeviceProtocol.validateResourceBinding("desktop-v2", binding);
        PairingCoordinator.OutgoingGrant selected = null;
        for (PairingCoordinator.OutgoingGrant candidate : owner.nativeOutgoingGrants()) {
            if (deviceId.equals(candidate.targetDeviceId()) && grantId.equals(candidate.grantId())) { selected = candidate; break; }
        }
        if (selected == null) throw new SecurityException("当前电脑未提供这项原生桌面授权，请刷新或在电脑上更新授权");
        JSONObject facts = owner.authenticatedCapabilities(deviceId);
        JSONObject metadata = facts == null ? null : facts.optJSONObject("metadata");
        JSONObject desktop = metadata == null ? null : metadata.optJSONObject("desktop");
        JSONObject streaming = desktop == null ? null : desktop.optJSONObject("streaming");
        if (streaming == null || !streaming.optBoolean("available")) throw new IllegalStateException("电脑桌面流尚未就绪，请安装当前 Windows Companion 并刷新设备");
        DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        if (local == null) throw new SecurityException("原本机设备身份不存在");
        Session opened = new Session(local, selected, binding, current);
        try {
            JSONObject args = new JSONObject().put("action", "configuration");
            if (target != null) args.put("target", target);
            opened.openedView = opened.exchange(args, false);
            return opened;
        } catch (Exception failure) { opened.close(); throw failure; }
    }

    private final class Session implements NativeDeviceWorkspaceHost.DesktopSession {
        private final TaskStoreDesktopViewer.Session source;
        private final NativeDeviceWorkspaceHost.OwnerCurrent page;
        private volatile boolean active = true, remoteClosed, uncertain;
        private JSONObject state;
        private JSONObject openedView;
        Session(DeviceIdentity.LocalIdentity local, PairingCoordinator.OutgoingGrant grant, JSONObject binding,
                NativeDeviceWorkspaceHost.OwnerCurrent page) throws Exception {
            this.page = page;
            source = owner.tasks.deviceTasks().openDesktopViewer(local, grant, binding, this::requireCurrent);
            state = new JSONObject().put("kind", "desktopSession").put("deviceId", source.deviceId()).put("sessionId", source.id())
                    .put("resourceBinding", binding).put("expiresAt", source.expiresAt()).put("width", 0).put("height", 0)
                    .put("control", new JSONObject().put("owner", "none").put("revision", 0));
        }
        private void requireCurrent() throws Exception { page.require(); if (!active) throw new SecurityException("原桌面查看会话已关闭"); }
        @Override public String id() { return source.id(); }
        @Override public synchronized JSONObject view() throws Exception { requireCurrent(); JSONObject value = openedView == null ? state : openedView; openedView = null; return new JSONObject(value.toString()); }
        @Override public synchronized JSONObject command(String action, JSONObject input) throws Exception {
            requireCurrent(); source.require();
            if (!Set.of("negotiate", "status", "take", "release", "input", "close").contains(action)) throw new SecurityException("桌面操作不在本机范围");
            if (uncertain && !Set.of("status", "close").contains(action)) throw new SecurityException("上次桌面操作结果未知，请关闭后核对电脑，不能自动重发");
            JSONObject args = new JSONObject(input.toString()).put("action", action);
            args.remove("sessionId");
            if (state.has("streamId")) args.put("streamId", state.getString("streamId"));
            if ("input".equals(action)) {
                JSONObject control = state.getJSONObject("control");
                if (!"human".equals(control.optString("owner")) || !DeviceProtocol.canonical(control.optJSONObject("lease")).equals(DeviceProtocol.canonical(args.optJSONObject("controlLease"))))
                    throw new SecurityException("当前页面未持有原电脑确认的人工控制权");
            }
            JSONObject result = exchange(args, false);
            if ("close".equals(action)) remoteClosed = "completed".equals(result.optString("status"));
            return result;
        }
        private JSONObject exchange(JSONObject args, boolean cleanup) throws Exception {
            TaskStoreDesktopViewer.Send send = cleanup ? source.prepareClose() : source.prepare(args);
            var ticket = owner.tasks.deviceTasks().desktopViewerTicket(send);
            try {
                DeviceCapabilityPort.Reply reply = owner.network.once(source.deviceId(), send.request(), ticket, new RunControl());
                send.accept(reply.peer(), reply.response());
                JSONObject remote = reply.response().getJSONObject("result");
                if (!Set.of("completed", "unknown", "failed").contains(remote.optString("status"))) throw new SecurityException("电脑未提供确定的桌面操作回执");
                if (cleanup) return remote;
                if (!"completed".equals(remote.optString("status"))) {
                    uncertain = "unknown".equals(remote.optString("status"));
                    if (!"input".equals(args.optString("action"))) throw new IllegalStateException(uncertain ? "桌面操作结果未知，请核对电脑后重新进入" : "电脑拒绝了这次桌面操作，请核对会话和权限");
                }
                JSONObject value = new JSONObject(state.toString());
                for (String field : new String[]{"streamId", "answer", "iceServers", "width", "height", "control", "frameRef", "state", "status", "inputReceipt", "message"}) {
                    if (remote.has(field)) value.put(field, remote.get(field));
                }
                if (remote.optLong("expiresAt", source.expiresAt()) > source.expiresAt()) throw new SecurityException("电脑桌面回执扩大了原会话期限");
                value.put("expiresAt", Math.min(source.expiresAt(), remote.optLong("expiresAt", source.expiresAt())));
                if (state.has("streamId") && remote.has("streamId") && !state.getString("streamId").equals(remote.getString("streamId"))) throw new SecurityException("电脑流身份已变化");
                JSONObject control = value.optJSONObject("control");
                if (control == null || !Set.of("human", "none").contains(control.optString("owner")) || control.optLong("revision", -1) < state.getJSONObject("control").getLong("revision")) throw new SecurityException("电脑控制权回执失效");
                if (control.optBoolean("needsReconcile")) uncertain = true;
                // SDP/TURN secrets are returned only for this call, never retained
                // in workspace snapshots, TaskStore history or model context.
                state = new JSONObject(value.toString()); state.remove("answer"); state.remove("iceServers"); state.remove("inputReceipt");
                return value;
            } catch (Exception failure) {
                if (ticket.isClaimed()) uncertain = true;
                else ticket.abandonBeforeWrite();
                throw failure;
            }
        }
        @Override public void close() {
            if (!active) return;
            active = false; // Immediately fences all earlier normal send tickets.
            try {
                cleanupWorker.execute(() -> {
                    synchronized (Session.this) {
                        try { if (!remoteClosed) exchange(new JSONObject(), true); }
                        catch (Exception unavailable) { /* PC media/lease lifecycle independently expires. */ }
                        finally { source.close(); }
                    }
                });
            } catch (RuntimeException unavailable) { source.close(); }
        }
    }
}
