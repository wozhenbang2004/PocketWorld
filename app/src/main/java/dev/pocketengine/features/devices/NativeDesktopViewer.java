package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDesktopViewer;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONObject;

/** An owner-page resource on the original paired transport and TaskStore. The Web view
 * selects a retained native session; it cannot provide an envelope, grant or input ticket. */
final class NativeDesktopViewer implements NativeDeviceWorkspaceHost.DesktopAction {
    private final TaskStoreDeviceTasks tasks;
    private final TaskStoreDesktopViewer.Session session;
    private final DeviceNativeRoute.Exchange exchange;
    private final Executor cleanupWorker;
    private final RunControl io = new RunControl();
    private final Object calls = new Object();
    private volatile boolean closed;
    private boolean uncertain;
    private JSONObject last;

    NativeDesktopViewer(TaskStore store, DeviceIdentity.LocalIdentity identity,
            PairingCoordinator.OutgoingGrant grant, JSONObject binding,
            DeviceNativeRoute.Exchange transport, Executor cleanupWorker,
            NativeDeviceWorkspaceHost.DesktopCurrent current) throws Exception {
        tasks = store.deviceTasks(); exchange = transport; this.cleanupWorker = cleanupWorker;
        session = tasks.openDesktopViewer(identity, grant, binding, current::require);
    }
    public String id() { return session.id(); }
    private void require() throws Exception {
        if (closed) throw new SecurityException("桌面窗口已关闭，请重新打开");
        io.check(); session.require();
    }
    public JSONObject view() throws Exception {
        synchronized (calls) {
            require();
            if (last == null) return send(new JSONObject().put("action", "configuration"));
            return new JSONObject(last.toString());
        }
    }
    public JSONObject request(String action, JSONObject selected) throws Exception {
        synchronized (calls) {
            require();
            if (last == null) throw new SecurityException("桌面会话尚未打开");
            JSONObject args = new JSONObject(selected.toString()).put("action", action);
            if (action.equals("negotiate")) {
                fields(selected, Set.of("offer"));
                if (last.has("streamId")) throw new SecurityException("此桌面已协商，请关闭后重新连接");
                sdp(selected.getJSONObject("offer"), "offer");
            } else if (action.equals("status")) {
                fields(selected, Set.of());
            } else if (action.equals("take") || action.equals("release")) {
                fields(selected, Set.of("expectedRevision"));
                long revision = integer(selected.opt("expectedRevision"), 0, Long.MAX_VALUE);
                if (revision != last.getJSONObject("control").getLong("revision"))
                    throw new SecurityException("电脑控制权已变化，请刷新后再操作");
                if (action.equals("take") && uncertain) throw new SecurityException("上一项输入尚未确认，请交还控制权或关闭观看后核对");
                if (action.equals("take") && last.getJSONObject("control").optBoolean("needsReconcile"))
                    throw new SecurityException("电脑有尚未核对的输入，请先核对原结果");
                if (action.equals("take") && "other_human".equals(last.getJSONObject("control").optString("owner")))
                    throw new SecurityException("另一窗口正在控制，请等待对方交还控制权");
                if (action.equals("release") && !"human".equals(last.getJSONObject("control").optString("owner")))
                    throw new SecurityException("当前窗口没有可交还的原人工控制权");
            } else if (action.equals("input")) {
                fields(selected, Set.of("controlLease", "frameRef", "input"));
                JSONObject control = last.getJSONObject("control");
                if (uncertain || control.optBoolean("needsReconcile") || !"human".equals(control.optString("owner"))
                        || !DeviceProtocol.canonical(control.opt("lease")).equals(DeviceProtocol.canonical(selected.opt("controlLease"))))
                    throw new SecurityException("没有当前人工控制权，或上一项输入尚未确认");
                frame(selected.getJSONObject("frameRef"), last.getString("streamId"));
                input(selected.getJSONObject("input"));
            } else throw new SecurityException("不支持的桌面操作");
            if (last.has("streamId")) args.put("streamId", last.getString("streamId"));
            boolean mutation = Set.of("take", "release", "input").contains(action);
            if (mutation) uncertain = true;
            JSONObject value = send(args);
            if (action.equals("input")) {
                JSONObject receipt = value.optJSONObject("inputReceipt");
                uncertain = receipt == null || !"completed".equals(receipt.optString("status")) || !Boolean.TRUE.equals(receipt.opt("inputDispatched"));
            } else if (action.equals("take") || action.equals("release")) {
                JSONObject control = value.getJSONObject("control");
                if (control.getLong("revision") <= selected.getLong("expectedRevision")
                        || !control.getString("owner").equals(action.equals("take") ? "human" : "none"))
                    throw new SecurityException("电脑尚未确认本次控制权变更");
                uncertain = false;
            }
            return value;
        }
    }
    private JSONObject send(JSONObject args) throws Exception {
        require();
        TaskStoreDesktopViewer.Send send = session.prepare(args);
        TaskStoreDeviceTasks.OutboundSendTicket ticket = tasks.desktopViewerTicket(send);
        ticket.installNativeWriteGuard(this::require);
        DeviceCapabilityPort.Reply reply;
        try { reply = exchange.once(session.deviceId(), send.request(), ticket, io); }
        catch (Exception failure) { if (!ticket.isClaimed()) ticket.abandonBeforeWrite(); throw failure; }
        require(); send.accept(reply.peer(), reply.response());
        JSONObject value = checkedView(reply.response().getJSONObject("result"), session, last);
        if (args.optString("action").equals("negotiate")) {
            if (!value.has("streamId") || !value.has("answer")) throw new SecurityException("电脑未返回完整的视频协商结果");
        }
        last = value;
        return new JSONObject(value.toString());
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        io.cancel();
        final TaskStoreDesktopViewer.Send send;
        try { send = session.prepareClose(); }
        catch (Exception expired) { session.close(); return; }
        try {
            cleanupWorker.execute(() -> {
                TaskStoreDeviceTasks.OutboundSendTicket ticket = tasks.desktopViewerTicket(send);
                try {
                    DeviceCapabilityPort.Reply reply = exchange.once(session.deviceId(), send.request(), ticket, new RunControl());
                    send.accept(reply.peer(), reply.response());
                } catch (Exception unavailable) {
                    try { if (!ticket.isClaimed()) ticket.abandonBeforeWrite(); } catch (Exception ignored) { }
                    // The original single-shot ticket remains consumed/unknown. Peer/stream
                    // expiry closes media; no replacement input or unbounded retry is scheduled.
                } finally { session.close(); }
            });
        } catch (RuntimeException unavailable) { session.close(); }
    }
    private static JSONObject checkedView(JSONObject value, TaskStoreDesktopViewer.Session session, JSONObject previous) throws Exception {
        if (!"desktopSession".equals(value.optString("kind")) || !session.id().equals(value.optString("sessionId"))
                || !session.deviceId().equals(value.optString("deviceId"))
                || !DeviceProtocol.resourceEquals(session.resourceBinding(), value.optJSONObject("resourceBinding")))
            throw new SecurityException("桌面回执不属于原设备与授权范围");
        long expires = integer(value.opt("expiresAt"), System.currentTimeMillis() + 1, session.expiresAt());
        long width = integer(value.opt("width"), 1, 16384), height = integer(value.opt("height"), 1, 16384);
        JSONObject control = value.getJSONObject("control"); fields(control, Set.of("owner", "revision", "lease", "needsReconcile"));
        String owner = control.getString("owner");
        if (!Set.of("none", "human", "agent", "other_human").contains(owner)) throw new SecurityException("电脑控制状态无效");
        long revision = integer(control.opt("revision"), 0, Long.MAX_VALUE);
        if (previous != null && revision < previous.getJSONObject("control").getLong("revision"))
            throw new SecurityException("电脑返回了过时的控制权状态");
        if (control.has("needsReconcile") && !(control.opt("needsReconcile") instanceof Boolean))
            throw new SecurityException("电脑输入核对状态无效");
        JSONObject safeControl = new JSONObject().put("owner", owner).put("revision", revision)
                .put("needsReconcile", control.optBoolean("needsReconcile", false));
        if (owner.equals("human")) {
            JSONObject lease = control.getJSONObject("lease"); fields(lease, Set.of("id", "fence", "expiresAt", "resource"));
            if (!text(lease.opt("id"), 32).matches("[a-f0-9]{32}")) throw new SecurityException("人工控制身份无效");
            integer(lease.opt("fence"), 1, Long.MAX_VALUE); integer(lease.opt("expiresAt"), 1, expires);
            text(lease.opt("resource"), 512); safeControl.put("lease", new JSONObject(lease.toString()));
        }
        JSONObject safe = new JSONObject().put("kind", "desktopSession").put("sessionId", session.id()).put("deviceId", session.deviceId())
                .put("resourceBinding", session.resourceBinding()).put("expiresAt", expires).put("width", width).put("height", height)
                .put("control", safeControl).put("iceServers", ice(value.optJSONArray("iceServers")));
        String stream = value.has("streamId") ? text(value.opt("streamId"), 240) : null;
        if (previous != null && previous.has("streamId") && !previous.getString("streamId").equals(stream))
            throw new SecurityException("桌面视频身份已改变，请重新连接");
        if (stream != null) safe.put("streamId", stream);
        if (value.has("answer")) safe.put("answer", sdp(value.getJSONObject("answer"), "answer"));
        if (value.has("inputReceipt")) {
            JSONObject receipt = value.getJSONObject("inputReceipt");
            String status = text(receipt.opt("status"), 32);
            if (!Set.of("completed", "unknown", "failed", "rejected").contains(status) || !(receipt.opt("inputDispatched") instanceof Boolean))
                throw new SecurityException("人工输入回执不完整");
            JSONObject projected = new JSONObject().put("status", status).put("inputDispatched", receipt.getBoolean("inputDispatched"));
            for (String field : new String[]{"inputDispatchAttempted", "dispatchConfirmed"})
                if (receipt.opt(field) instanceof Boolean) projected.put(field, receipt.getBoolean(field));
            safe.put("inputReceipt", projected);
        }
        return safe;
    }
    private static JSONArray ice(JSONArray values) throws Exception {
        JSONArray safe = new JSONArray(); if (values == null) return safe;
        if (values.length() > 4) throw new SecurityException("桌面连接配置超出范围");
        for (int i=0; i<values.length(); i++) {
            JSONObject server = values.getJSONObject(i); fields(server, Set.of("urls", "username", "credential"));
            Object urls = server.opt("urls"); JSONArray list = urls instanceof String ? new JSONArray().put(urls) : (urls instanceof JSONArray ? (JSONArray) urls : null);
            if (list == null || list.length() < 1 || list.length() > 4) throw new SecurityException("桌面连接地址无效");
            JSONArray addresses = new JSONArray();
            for (int u=0; u<list.length(); u++) {
                String address = text(list.get(u), 512);
                if (!address.matches("(?:stun|turn|turns):[^\\s]+")) throw new SecurityException("桌面连接地址无效");
                addresses.put(address);
            }
            JSONObject item = new JSONObject().put("urls", addresses);
            for (String field : new String[]{"username", "credential"}) if (server.has(field)) item.put(field, text(server.get(field), 1024));
            safe.put(item);
        }
        return safe;
    }
    private static JSONObject sdp(JSONObject value, String type) throws Exception {
        fields(value, Set.of("type", "sdp"));
        if (!type.equals(value.optString("type"))) throw new SecurityException("桌面协商类型无效");
        String sdp = text(value.opt("sdp"), 65536);
        if (sdp.getBytes(StandardCharsets.UTF_8).length > 65536) throw new SecurityException("桌面协商过大");
        return new JSONObject().put("type", type).put("sdp", sdp);
    }
    private static void frame(JSONObject value, String stream) throws Exception {
        fields(value, Set.of("streamId", "frameId", "width", "height", "geometryRevision", "capturedAt", "expiresAt", "rtpTimestamp"));
        if (!stream.equals(value.optString("streamId"))) throw new SecurityException("输入不是当前视频画面");
        text(value.opt("frameId"), 240); text(value.opt("geometryRevision"), 240);
        integer(value.opt("rtpTimestamp"), 0, 0xffffffffL);
        integer(value.opt("width"), 1, 16384); integer(value.opt("height"), 1, 16384);
        long captured = integer(value.opt("capturedAt"), 1, 9007199254710991L);
        integer(value.opt("expiresAt"), captured + 1, captured + 30000);
    }
    private static void input(JSONObject value) throws Exception {
        String kind = text(value.opt("kind"), 32);
        switch (kind) {
            case "click":
                fields(value, Set.of("kind", "x", "y", "button", "count"));
                integer(value.opt("x"), 0, 16383); integer(value.opt("y"), 0, 16383); integer(value.opt("count"), 1, 2);
                if (!Set.of("left", "right").contains(value.optString("button"))) throw new SecurityException("不支持的鼠标按键"); break;
            case "scroll":
                fields(value, Set.of("kind", "direction", "amount", "by")); integer(value.opt("amount"), 1, 20);
                if (!Set.of("up", "down", "left", "right").contains(value.optString("direction")) || !"line".equals(value.optString("by"))) throw new SecurityException("滚动参数无效"); break;
            case "insert_text":
                fields(value, Set.of("kind", "text"));
                if (text(value.opt("text"), 12000).getBytes(StandardCharsets.UTF_8).length > 12000) throw new SecurityException("输入内容过长"); break;
            case "press_key":
                fields(value, Set.of("kind", "key"));
                if (!Set.of("enter", "escape", "tab").contains(value.optString("key"))) throw new SecurityException("不支持的按键"); break;
            case "hotkey":
                fields(value, Set.of("kind", "keys")); JSONArray keys = value.getJSONArray("keys");
                if (keys.length() < 1 || keys.length() > 4) throw new SecurityException("组合键无效");
                for (int i=0;i<keys.length();i++) if (!text(keys.get(i), 32).matches("[a-zA-Z0-9_]+")) throw new SecurityException("组合键无效"); break;
            default: throw new SecurityException("不支持的人工桌面输入");
        }
    }
    private static void fields(JSONObject value, Set<String> allowed) {
        if (!allowed.containsAll(DeviceProtocol.objectKeys(value))) throw new SecurityException("桌面操作包含未开放字段");
    }
    private static String text(Object value, int maximum) {
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > maximum || ((String) value).indexOf('\0') >= 0)
            throw new SecurityException("桌面文字字段无效");
        return (String) value;
    }
    private static long integer(Object value, long minimum, long maximum) {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new SecurityException("桌面整数参数无效");
        long number = ((Number) value).longValue(); if (number < minimum || number > maximum) throw new SecurityException("桌面数值超出范围"); return number;
    }
}
