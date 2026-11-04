package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-operated desktop sessions use the ORIGINAL TaskStore metadata and transport
 * write gate. They are not Agent tasks, do not invent runs, and cannot be restored
 * from JSON. Only a current native outgoing grant can create a session. */
public final class TaskStoreDesktopViewer {
    private static final String PREFIX = "desktop_viewer_send:";
    @FunctionalInterface public interface Current { void require() throws Exception; }
    private final SQLiteDatabase db;
    private final TaskStore owner;
    TaskStoreDesktopViewer(SQLiteDatabase db, TaskStore owner) { this.db = db; this.owner = owner; }

    public final class Session implements AutoCloseable {
        private final DeviceIdentity.LocalIdentity local;
        private final PairingCoordinator.OutgoingGrant grant;
        private final String id = UUID.randomUUID().toString(), binding;
        private final long expires;
        private final Current ownerCurrent;
        private volatile boolean closed;
        private int operations;
        private Session(DeviceIdentity.LocalIdentity local, PairingCoordinator.OutgoingGrant grant,
                JSONObject binding, Current current) throws Exception {
            this.local = local; this.grant = grant; this.binding = DeviceProtocol.canonical(binding);
            ownerCurrent = current;
            expires = Math.min(System.currentTimeMillis() + 300000, grant.grant().getLong("expiresAt"));
            require();
        }
        public String id() { return id; }
        public String deviceId() { return grant.targetDeviceId(); }
        public long expiresAt() { return expires; }
        public JSONObject resourceBinding() throws Exception { return new JSONObject(binding); }
        public void require() throws Exception {
            require(false);
        }
        private void require(boolean cleanup) throws Exception {
            if (!cleanup) ownerCurrent.require();
            grant.checkCurrent(System.currentTimeMillis());
            if (closed || System.currentTimeMillis() >= expires || !local.deviceId().equals(grant.grant().getString("peerDeviceId")))
                throw new SecurityException("原桌面观看会话已关闭或过期");
            JSONObject actual = grant.grant();
            if (!contains(actual.getJSONArray("allowedOrigins"), local.deviceId())
                    || !contains(actual.getJSONArray("capabilities"), "desktop.viewer") || !containsResource(actual.getJSONArray("resources"), binding))
                throw new SecurityException("电脑未授予当前准确范围的桌面观看与接管权限");
        }
        public synchronized Send prepare(JSONObject input) throws Exception {
            return prepare(input, false);
        }
        /** Only authority-reducing cleanup may outlive its original owner page. */
        public synchronized Send prepareClose() throws Exception {
            return prepare(new JSONObject().put("action", "close"), true);
        }
        private Send prepare(JSONObject input, boolean cleanup) throws Exception {
            require(cleanup);
            if (++operations > 1200) throw new SecurityException("桌面会话操作窗口已满，请关闭后重新进入");
            String operation = UUID.randomUUID().toString();
            JSONObject args = new JSONObject(input.toString()).put("viewerSessionId", id);
            JSONObject wire = new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile", DeviceProtocol.PROFILE)
                    .put("messageType", "DeviceRequest").put("method", "desktop.viewer").put("requestId", UUID.randomUUID().toString())
                    .put("operationId", operation).put("originDeviceId", local.deviceId()).put("callerDeviceId", local.deviceId())
                    .put("targetDeviceId", grant.targetDeviceId()).put("instanceId", grant.targetInstanceId())
                    .put("grantId", grant.grantId()).put("authorizationEpoch", grant.authorizationEpoch())
                    .put("capabilityVersion", "desktop-v2").put("resourceBinding", resourceBinding())
                    .put("deadline", expires).put("arguments", args);
            wire.put("argumentsDigest", DeviceProtocol.argumentsDigest(wire));
            DeviceTaskEnvelope request = DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(wire));
            Send send = new Send(this, request, cleanup);
            synchronized (owner) {
                require(cleanup);
                // Bounded metadata only: SDP, TURN credentials, input text and frames are never logged.
                if (operations == 1 || operations % 64 == 0) {
                    try (Cursor expired = db.rawQuery("SELECT key,value FROM meta WHERE key LIKE ? LIMIT 4096", new String[]{PREFIX + "%"})) {
                        while (expired.moveToNext()) if (new JSONObject(expired.getString(1)).optLong("expiresAt") < System.currentTimeMillis() - 86400000)
                            db.delete("meta", "key=?", new String[]{expired.getString(0)});
                    }
                }
                try (Cursor rows = db.rawQuery("SELECT count(*) FROM meta WHERE key LIKE ?", new String[]{PREFIX + "%"})) {
                    if (rows.moveToFirst() && rows.getLong(0) >= 4096) throw new SecurityException("桌面操作记录已达上限");
                }
                write(send.key, new JSONObject().put("sessionId", id).put("operationId", operation).put("wireHash", send.hash)
                        .put("state", "not_claimed").put("expiresAt", expires));
            }
            return send;
        }
        @Override public void close() { closed = true; }
    }
    public final class Send {
        private final Session session;
        private final DeviceTaskEnvelope request;
        private final byte[] wire;
        private final String hash, key;
        private final boolean cleanup;
        private Send(Session session, DeviceTaskEnvelope request, boolean cleanup) {
            this.cleanup = cleanup;
            this.session = session; this.request = request; wire = DeviceProtocol.canonicalBytes(request.json());
            hash = DeviceProtocol.sha256(wire); key = PREFIX + request.operationId();
        }
        public DeviceTaskEnvelope request() { return request; }
        boolean belongsTo(TaskStore expected) { return owner == expected; }
        PairingCoordinator.OutgoingGrant grant() { return session.grant; }
        byte[] bytes() { return wire.clone(); }
        byte[] claim(DeviceIdentity.AuthenticatedPeer actual) throws Exception {
            synchronized (owner) {
                session.require(cleanup); session.grant.requireTargetPeer(actual);
                db.beginTransaction();
                try {
                    JSONObject record = read(key);
                    if (!hash.equals(record.optString("wireHash")) || !"not_claimed".equals(record.optString("state")))
                        throw new SecurityException("桌面发送票据已领取或改变，不能重复派发");
                    write(key, record.put("state", "write_claimed"));
                    db.setTransactionSuccessful();
                    return wire.clone();
                } finally { db.endTransaction(); }
            }
        }
        boolean claimed() {
            synchronized (owner) { try { return !"not_claimed".equals(read(key).optString("state")); } catch (Exception unavailable) { return true; } }
        }
        void abandon() throws Exception {
            synchronized (owner) {
                JSONObject record = read(key);
                if ("not_claimed".equals(record.optString("state"))) write(key, record.put("state", "not_dispatched"));
            }
        }
        public void accept(DeviceIdentity.AuthenticatedPeer actual, JSONObject response) throws Exception {
            session.require(cleanup); session.grant.requireTargetPeer(actual); DeviceProtocol.validateMessage(response);
            if (!"DeviceResponse".equals(response.optString("messageType")) || !request.requestId().equals(response.optString("requestId"))
                    || !request.method().equals(response.optString("method")) || !session.deviceId().equals(response.optString("executorDeviceId"))
                    || !session.grant.targetInstanceId().equals(response.optString("instanceId"))) throw new SecurityException("桌面回执不是原请求/设备实例");
            JSONObject result = response.getJSONObject("result");
            if (!session.id.equals(result.optString("viewerSessionId")) || !request.operationId().equals(result.optString("operationId")))
                throw new SecurityException("桌面回执不属于原会话与操作");
            synchronized (owner) {
                JSONObject record = read(key);
                if (!"write_claimed".equals(record.optString("state"))) throw new SecurityException("桌面回执缺少原发送记录");
                write(key, record.put("state", "receipt").put("status", result.optString("status", "reported")));
            }
        }
    }
    Session open(DeviceIdentity.LocalIdentity local, PairingCoordinator.OutgoingGrant grant, JSONObject binding, Current current) throws Exception {
        DeviceProtocol.validateResourceBinding("desktop-v2", binding);
        return new Session(local, grant, binding, current);
    }
    private JSONObject read(String key) throws Exception {
        try (Cursor row = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            if (!row.moveToFirst()) throw new SecurityException("原桌面发送记录不存在");
            return new JSONObject(row.getString(0));
        }
    }
    private void write(String key, JSONObject value) {
        ContentValues row = new ContentValues(); row.put("key", key); row.put("value", value.toString());
        db.insertWithOnConflict("meta", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static boolean contains(JSONArray list, String wanted) { for (int i=0;i<list.length();i++) if (wanted.equals(list.optString(i))) return true; return false; }
    private static boolean containsResource(JSONArray list, String binding) { for (int i=0;i<list.length();i++) if (binding.equals(DeviceProtocol.canonical(list.opt(i)))) return true; return false; }
}
