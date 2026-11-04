package dev.pocketengine.features.devices;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDesktopViewer;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Actual original TaskStore/SQLite, signed outgoing grant and loopback-mTLS peer.
 * The exchange below is an explicit peer-result fixture after the actual native
 * write ticket, not Windows input, media transport or a physical Android test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class NativeDesktopViewerSqlTest {
    private DeviceIngressNativeTest fixture;
    private TaskStore tasks;
    private SQLiteDatabase sql;
    private DeviceIdentity.LocalIdentity local;
    private DeviceIdentity.AuthenticatedPeer remote;
    private PairingCoordinator.OutgoingGrant grant;
    private JSONObject binding;
    private final AtomicBoolean page = new AtomicBoolean(true);
    private final List<Runnable> cleanup = new ArrayList<>();

    private static Object field(Object source, String name) throws Exception {
        Field field = source.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(source);
    }
    private static Object call(Object source, String name) throws Exception {
        Method method = source.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(source);
    }
    @Before public void open() throws Exception {
        fixture = new DeviceIngressNativeTest(); fixture.open();
        tasks = (TaskStore) field(fixture, "tasks"); sql = (SQLiteDatabase) field(fixture, "sql");
        remote = (DeviceIdentity.AuthenticatedPeer) field(fixture, "caller");
        local = (DeviceIdentity.LocalIdentity) call(fixture, "syntheticLocalIdentity");
        PairingCoordinator pairing = (PairingCoordinator) field(fixture, "pairing");
        binding = new JSONObject().put("kind", "desktop").put("sessionId", 1).put("displayId", "primary");
        long now = System.currentTimeMillis();
        JSONObject scope = new JSONObject().put("grantId", "fixture-desktop-owner").put("peerDeviceId", local.deviceId())
                .put("localDeviceId", remote.deviceId()).put("authorizationEpoch", 1).put("issuedAt", now - 1000).put("expiresAt", now + 300000)
                .put("capabilities", new JSONArray().put("desktop.viewer")).put("toolNames", new JSONArray())
                .put("resources", new JSONArray().put(binding)).put("allowedOrigins", new JSONArray().put(local.deviceId()))
                .put("allowDelegation", false).put("maxDelegationDepth", 0).put("toolBudget", 0).put("modelBudget", 0);
        JSONObject signed = new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile", DeviceProtocol.PROFILE)
                .put("messageType", "PairGrantReceipt").put("issuerDeviceId", remote.deviceId()).put("issuerCertificateFingerprint", remote.certificateFingerprint())
                .put("body", new JSONObject().put("grant", scope).put("targetInstanceId", "caller-fixture").put("issuedAt", now));
        KeyPair keys = (KeyPair) call(field(fixture, "callerMaterial"), "keys");
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(keys.getPrivate()); signature.update(DeviceProtocol.canonicalBytes(signed));
        signed.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        grant = pairing.acceptOutgoingGrant(remote, signed, now);
    }
    @After public void close() { for (Runnable pending : cleanup) pending.run(); cleanup.clear(); if (fixture != null) fixture.close(); }
    private void current() { if (!page.get()) throw new SecurityException("fixture owner page closed"); }
    private TaskStoreDesktopViewer.Session session() throws Exception { return tasks.deviceTasks().openDesktopViewer(local, grant, binding, this::current); }
    private JSONObject outcome(TaskStoreDesktopViewer.Send send, String kind) throws Exception {
        String id = send.request().arguments().getString("viewerSessionId");
        JSONObject value = new JSONObject().put("viewerSessionId", id).put("operationId", send.request().operationId());
        if ("closed".equals(kind)) return value.put("status", "closed");
        value.put("kind", "desktopSession").put("deviceId", remote.deviceId()).put("sessionId", id)
                .put("resourceBinding", binding).put("expiresAt", send.request().deadline()).put("width", 800).put("height", 600)
                .put("control", new JSONObject().put("owner", "none").put("revision", 0).put("needsReconcile", false))
                .put("iceServers", new JSONArray());
        if ("negotiate".equals(send.request().arguments().optString("action")))
            value.put("streamId", "fixture-stream").put("answer", new JSONObject().put("type", "answer").put("sdp", "v=0\r\n"));
        return value;
    }
    private JSONObject response(DeviceTaskEnvelope request, JSONObject result) throws Exception {
        return new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile", DeviceProtocol.PROFILE)
                .put("messageType", "DeviceResponse").put("requestId", request.requestId()).put("method", request.method())
                .put("executorDeviceId", remote.deviceId()).put("instanceId", "caller-fixture").put("result", result);
    }
    private long rows(String table) {
        try (Cursor cursor = sql.rawQuery("SELECT count(*) FROM " + table, null)) { cursor.moveToFirst(); return cursor.getLong(0); }
    }
    private JSONObject record(TaskStoreDesktopViewer.Send send) throws Exception {
        try (Cursor cursor = sql.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{"desktop_viewer_send:" + send.request().operationId()})) {
            assertTrue(cursor.moveToFirst()); return new JSONObject(cursor.getString(0));
        }
    }

    @Test public void ownerSendCreatesNoAgentTaskAndIsDurablySingleUseWithoutPrivatePayload() throws Exception {
        long taskRows = rows("tasks"), runRows = rows("runs"), operationRows = rows("operations");
        var session = session();
        var send = session.prepare(new JSONObject().put("action", "negotiate").put("offer", new JSONObject().put("type", "offer").put("sdp", "private-test-sdp")));
        var first = tasks.deviceTasks().desktopViewerTicket(send); var duplicate = tasks.deviceTasks().desktopViewerTicket(send);
        assertFalse(first.isClaimed()); assertFalse(send.request().json().has("runId")); assertFalse(send.request().json().has("budget"));
        assertFalse(record(send).toString().contains("private-test-sdp"));
        assertArrayEquals(DeviceProtocol.canonicalBytes(send.request().json()), first.claimForWrite(remote));
        assertTrue(first.isClaimed()); assertTrue(duplicate.isClaimed()); assertThrows(SecurityException.class, () -> duplicate.claimForWrite(remote));
        send.accept(remote, response(send.request(), outcome(send, "ready")));
        assertEquals("receipt", record(send).getString("state"));
        assertEquals(taskRows, rows("tasks")); assertEquals(runRows, rows("runs")); assertEquals(operationRows, rows("operations"));
    }
    @Test public void closeInvalidatesQueuedInputsButAllowsOneExactCleanupAfterPageCloses() throws Exception {
        var session = session(); var old = session.prepare(new JSONObject().put("action", "status"));
        page.set(false); var closing = session.prepareClose();
        assertEquals("close", closing.request().arguments().getString("action"));
        assertEquals(session.id(), closing.request().arguments().getString("viewerSessionId"));
        assertThrows(SecurityException.class, () -> tasks.deviceTasks().desktopViewerTicket(old).claimForWrite(remote));
        var ticket = tasks.deviceTasks().desktopViewerTicket(closing); ticket.claimForWrite(remote);
        closing.accept(remote, response(closing.request(), outcome(closing, "closed")));
        assertThrows(SecurityException.class, session::prepareClose);
        assertThrows(SecurityException.class, () -> session.prepare(new JSONObject().put("action", "status")));
    }
    @Test public void grantRevocationAndMissingOriginalRecordBothRefuseTheActualWrite() throws Exception {
        var session = session(); var send = session.prepare(new JSONObject().put("action", "configuration"));
        sql.delete("meta", "key=?", new String[]{"desktop_viewer_send:" + send.request().operationId()});
        var ticket = tasks.deviceTasks().desktopViewerTicket(send);
        assertTrue(ticket.isClaimed()); assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
        var next = session.prepare(new JSONObject().put("action", "configuration"));
        new TaskStoreDeviceTrust(sql).revoke(remote.deviceId(), System.currentTimeMillis());
        assertThrows(SecurityException.class, () -> tasks.deviceTasks().desktopViewerTicket(next).claimForWrite(remote));
        assertEquals("not_claimed", record(next).getString("state"));
        assertThrows(SecurityException.class, session::prepareClose);
    }
    @Test public void onlyCorrelatedPeerErrorSettlesReceiptAndNoErrorTextIsJournaled() throws Exception {
        var session = session(); var send = session.prepare(new JSONObject().put("action", "configuration"));
        tasks.deviceTasks().desktopViewerTicket(send).claimForWrite(remote);
        JSONObject error = new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile", DeviceProtocol.PROFILE)
                .put("messageType", "DeviceError").put("requestId", "wrong-original-request")
                .put("error", new JSONObject().put("code", "native_tool_unavailable").put("message", "private diagnostic fixture")
                        .put("retryable", false).put("effectState", "unknown"));
        assertThrows(SecurityException.class, () -> send.accept(remote, error)); assertEquals("write_claimed", record(send).getString("state"));
        error.put("requestId", send.request().requestId());
        assertThrows(java.io.IOException.class, () -> send.accept(remote, error));
        assertEquals("unknown", record(send).getString("state")); assertFalse(record(send).toString().contains("private diagnostic fixture"));
        assertThrows(SecurityException.class, () -> tasks.deviceTasks().desktopViewerTicket(send).claimForWrite(remote));
    }

    private final class Exchange implements DeviceNativeRoute.Exchange {
        int calls, inputCalls; long revision; String owner = "none"; JSONObject lease; boolean invalidateAfterWrite, needsReconcile;
        public DeviceCapabilityPort.Reply once(String target, DeviceTaskEnvelope request, RunControl io) { throw new AssertionError("Owner effects need the original SQL ticket"); }
        public DeviceCapabilityPort.Reply once(String target, DeviceTaskEnvelope request, dev.pocketengine.store.TaskStoreDeviceTasks.OutboundSendTicket ticket, RunControl io) throws Exception {
            assertNull(io.hostRunIdentity()); assertEquals(remote.deviceId(), target); ticket.claimForWrite(remote); calls++;
            String action = request.arguments().getString("action"), id = request.arguments().getString("viewerSessionId");
            if (invalidateAfterWrite) page.set(false);
            JSONObject value = new JSONObject().put("viewerSessionId", id).put("sessionId", id).put("operationId", request.operationId());
            if (action.equals("close")) return new DeviceCapabilityPort.Reply(remote, response(request, value.put("status", "closed")));
            if (action.equals("take")) {
                owner = "human"; revision++;
                lease = new JSONObject().put("id", "a".repeat(32)).put("fence", revision).put("resource", "windows-session:1:primary").put("expiresAt", request.deadline());
            } else if (action.equals("release")) { owner = "none"; revision++; lease = null; }
            if (action.equals("input")) needsReconcile = true;
            JSONObject control = new JSONObject().put("owner", owner).put("revision", revision).put("needsReconcile", needsReconcile);
            if (lease != null && owner.equals("human")) control.put("lease", lease);
            value.put("kind", "desktopSession").put("deviceId", remote.deviceId()).put("resourceBinding", binding).put("expiresAt", request.deadline())
                    .put("width", 800).put("height", 600).put("iceServers", new JSONArray()).put("control", control);
            if (!action.equals("configuration")) value.put("streamId", "fixture-stream");
            if (action.equals("negotiate")) value.put("answer", new JSONObject().put("type", "answer").put("sdp", "v=0\r\n"));
            if (action.equals("input")) { inputCalls++; value.put("inputReceipt", new JSONObject().put("status", "unknown").put("inputDispatched", false).put("inputDispatchAttempted", true)); }
            return new DeviceCapabilityPort.Reply(remote, response(request, value));
        }
    }
    private JSONObject input(JSONObject lease) throws Exception {
        long now = System.currentTimeMillis();
        return new JSONObject().put("controlLease", lease).put("frameRef", new JSONObject().put("streamId", "fixture-stream").put("frameId", "fixture-frame")
                        .put("geometryRevision", "fixture-geometry").put("width", 800).put("height", 600).put("capturedAt", now).put("expiresAt", now + 2000).put("rtpTimestamp", 0))
                .put("input", new JSONObject().put("kind", "click").put("x", 25).put("y", 30).put("button", "left").put("count", 1));
    }
    @Test public void unknownInputCannotBeReplayedOrUnlockedByStatusAndCloseIsOriginalCleanup() throws Exception {
        Exchange peer = new Exchange(); NativeDesktopViewer viewer = new NativeDesktopViewer(tasks, local, grant, binding, peer, cleanup::add, this::current);
        viewer.view(); viewer.request("negotiate", new JSONObject().put("offer", new JSONObject().put("type", "offer").put("sdp", "v=0\r\n")));
        JSONObject taken = viewer.request("take", new JSONObject().put("expectedRevision", 0));
        JSONObject click = input(taken.getJSONObject("control").getJSONObject("lease"));
        assertEquals("unknown", viewer.request("input", click).getJSONObject("inputReceipt").getString("status"));
        viewer.request("status", new JSONObject()); int before = peer.calls;
        assertThrows(SecurityException.class, () -> viewer.request("input", click));
        assertThrows(SecurityException.class, () -> viewer.request("take", new JSONObject().put("expectedRevision", 1)));
        assertEquals(before, peer.calls); assertEquals(1, peer.inputCalls);
        viewer.request("release", new JSONObject().put("expectedRevision", 1));
        viewer.close(); viewer.close(); assertEquals(1, cleanup.size());
        cleanup.remove(0).run(); assertThrows(SecurityException.class, viewer::view);
    }
    @Test public void anotherHumanIsVisibleWithoutLeakingTheirLeaseOrDispatchingTake() throws Exception {
        Exchange peer = new Exchange(); peer.owner = "other_human";
        peer.lease = new JSONObject().put("id", "b".repeat(32)).put("privateFixture", true);
        NativeDesktopViewer viewer = new NativeDesktopViewer(tasks, local, grant, binding, peer, cleanup::add, this::current);
        JSONObject view = viewer.view(); assertEquals("other_human", view.getJSONObject("control").getString("owner"));
        assertFalse(view.getJSONObject("control").has("lease"));
        assertThrows(SecurityException.class, () -> viewer.request("take", new JSONObject().put("expectedRevision", 0)));
        assertThrows(SecurityException.class, () -> viewer.request("release", new JSONObject().put("expectedRevision", 0)));
        assertEquals(1, peer.calls); viewer.close();
    }

    @Test public void reconcileFenceAllowsOnlyExactHumanReleaseAndSurvivesThatRelease() throws Exception {
        Exchange peer = new Exchange();
        NativeDesktopViewer viewer = new NativeDesktopViewer(tasks, local, grant, binding, peer, cleanup::add, this::current);
        viewer.view(); viewer.request("negotiate", new JSONObject().put("offer", new JSONObject().put("type", "offer").put("sdp", "v=0\r\n")));
        JSONObject taken = viewer.request("take", new JSONObject().put("expectedRevision", 0));
        JSONObject click = input(taken.getJSONObject("control").getJSONObject("lease"));
        peer.needsReconcile = true;
        JSONObject status = viewer.request("status", new JSONObject());
        assertTrue(status.getJSONObject("control").getBoolean("needsReconcile"));
        assertEquals("human", status.getJSONObject("control").getString("owner"));
        int before = peer.calls;
        assertThrows(SecurityException.class, () -> viewer.request("input", click));
        assertThrows(SecurityException.class, () -> viewer.request("take", new JSONObject().put("expectedRevision", 1)));
        assertEquals(before, peer.calls); assertEquals(0, peer.inputCalls);
        JSONObject released = viewer.request("release", new JSONObject().put("expectedRevision", 1));
        assertEquals("none", released.getJSONObject("control").getString("owner"));
        assertTrue(released.getJSONObject("control").getBoolean("needsReconcile"));
        before = peer.calls;
        assertThrows(SecurityException.class, () -> viewer.request("take", new JSONObject().put("expectedRevision", 2)));
        assertThrows(SecurityException.class, () -> viewer.request("release", new JSONObject().put("expectedRevision", 2)));
        assertEquals(before, peer.calls);
        viewer.close();
    }

    @Test public void lateResultCannotReviveClosedOwnerPageButOriginalCleanupStillRuns() throws Exception {
        Exchange peer = new Exchange(); peer.invalidateAfterWrite = true;
        NativeDesktopViewer viewer = new NativeDesktopViewer(tasks, local, grant, binding, peer, cleanup::add, this::current);
        assertThrows(SecurityException.class, viewer::view); assertEquals(1, peer.calls);
        viewer.close(); assertEquals(1, cleanup.size()); cleanup.remove(0).run(); assertEquals(2, peer.calls);
        assertThrows(SecurityException.class, viewer::view);
    }
}
