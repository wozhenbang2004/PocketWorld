package dev.pocketengine.features.devices;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.transport.DeviceConnection;
import dev.pocketengine.features.devices.transport.SocketByteChannel;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDesktopViewer;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
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

/** Original TaskStore SQLite, signed outgoing grants and real loopback mutual TLS.
 * Certificates are explicit host fixtures. This does not exercise Windows GUI input,
 * Android WebView/WebRTC rendering, or a real user's device credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class DeviceDesktopViewerNativeTest {
    private static final String META_PREFIX = "desktop_viewer_send:";
    private static final String INPUT_SECRET = "viewer-input-must-not-enter-task-history";
    private static final String OFFER_SECRET = "v=0\r\na=ice-pwd:viewer-offer-secret\r\n";
    private static final String ANSWER_SECRET = "v=0\r\na=ice-pwd:viewer-answer-secret\r\n";
    private static final String TURN_SECRET = "viewer-turn-credential-secret";

    private DeviceIngressNativeTest fixture;
    private TaskStore tasks;
    private SQLiteDatabase sql;
    private PairingCoordinator pairing;
    private DeviceIdentity.AuthenticatedPeer remote;
    private DeviceIdentity.LocalIdentity local;
    private Object remoteMaterial, localMaterial;
    private PairingCoordinator.OutgoingGrant grant;
    private JSONObject binding;
    private final AtomicBoolean pageCurrent = new AtomicBoolean(true);

    @Before public void open() throws Exception {
        // Reuse only native SQL/TLS fixture setup, never its execution/task helpers.
        fixture = new DeviceIngressNativeTest();
        fixture.open();
        tasks = (TaskStore) field(fixture, "tasks");
        sql = (SQLiteDatabase) field(fixture, "sql");
        pairing = (PairingCoordinator) field(fixture, "pairing");
        remote = (DeviceIdentity.AuthenticatedPeer) field(fixture, "caller");
        remoteMaterial = field(fixture, "callerMaterial");
        localMaterial = field(fixture, "targetMaterial");
        Method identity = DeviceIngressNativeTest.class.getDeclaredMethod("syntheticLocalIdentity");
        identity.setAccessible(true);
        local = (DeviceIdentity.LocalIdentity) identity.invoke(fixture);
        binding = new JSONObject().put("kind", "desktop").put("sessionId", 1)
                .put("displayId", "primary").put("windowId", 99);
        grant = signedGrant("viewer-native-fixture", 1, binding,
                new JSONArray().put("desktop.viewer"), new JSONArray().put(local.deviceId()));
        assertNoExecutionRecords();
    }

    @After public void close() { if (fixture != null) fixture.close(); }

    @Test public void viewerUsesOnlyBoundedOriginalMetadataWithoutTaskRunOrSensitivePayloads() throws Exception {
        try (var session = session(grant, binding)) {
            assertEquals(remote.deviceId(), session.deviceId());
            assertEquals(DeviceProtocol.canonical(binding), DeviceProtocol.canonical(session.resourceBinding()));
            assertTrue(session.expiresAt() <= grant.deadline());

            // The signed resource supplies the exact HWND; only the PC resolves
            // its process. A caller-supplied second target identity is forbidden.
            assertThrows(DeviceProtocol.ProtocolException.class, () -> session.prepare(new JSONObject().put("action", "configuration")
                    .put("target", new JSONObject().put("pid", 77).put("windowId", 99))));
            var configuration = session.prepare(new JSONObject().put("action", "configuration"));
            var negotiation = session.prepare(new JSONObject().put("action", "negotiate")
                    .put("offer", new JSONObject().put("type", "offer").put("sdp", OFFER_SECRET)));
            var input = session.prepare(input());
            for (var send : new TaskStoreDesktopViewer.Send[]{configuration, negotiation, input}) {
                DeviceTaskEnvelope request = send.request();
                assertEquals("desktop.viewer", request.method());
                assertEquals("DeviceRequest", request.messageType());
                assertEquals("desktop-v2", request.json().getString("capabilityVersion"));
                assertEquals(local.deviceId(), request.originDeviceId());
                assertEquals(local.deviceId(), request.callerDeviceId());
                assertEquals(remote.deviceId(), request.targetDeviceId());
                assertEquals(grant.targetInstanceId(), request.instanceId());
                assertEquals(grant.grantId(), request.grantId());
                assertEquals(grant.authorizationEpoch(), request.authorizationEpoch());
                assertEquals(session.id(), request.arguments().getString("viewerSessionId"));
                assertEquals(session.expiresAt(), request.deadline());
                assertTrue(request.sideEffecting());
                for (String field : new String[]{"rootTaskId", "parentTaskId", "localTaskId", "runId", "budget", "attempt", "executorEpoch"})
                    assertFalse("owner viewer must not invent execution identity: " + field, request.json().has(field));
                assertEquals(DeviceProtocol.argumentsDigest(request.json()), request.argumentsDigest());

                var ticket = tasks.deviceTasks().desktopViewerTicket(send);
                assertFalse(ticket.isClaimed());
                assertMetadata(send, "not_claimed");
                assertArrayEquals(DeviceProtocol.canonicalBytes(request.json()), ticket.claimForWrite(remote));
                JSONObject receipt = response(send).getJSONObject("result");
                receipt.put("answer", new JSONObject().put("type", "answer").put("sdp", ANSWER_SECRET))
                        .put("iceServers", new JSONArray().put(new JSONObject()
                                .put("urls", "turn:fixture.invalid").put("username", "fixture").put("credential", TURN_SECRET)))
                        .put("inputReceipt", new JSONObject().put("status", "completed").put("inputDispatched", true));
                JSONObject reply = response(send).put("result", receipt);
                DeviceProtocol.validateMessage(reply);
                send.accept(remote, reply);
                assertMetadata(send, "receipt");
            }
            assertEquals(3, metadataCount());
            assertNoExecutionRecords();
        }
    }

    @Test public void onlyExactSignedResourceCapabilityAndDirectOriginCanOpenViewer() throws Exception {
        JSONObject otherWindow = copy(binding).put("windowId", 100);
        JSONObject wholeDesktop = copy(binding); wholeDesktop.remove("windowId");
        JSONObject otherSession = copy(binding).put("sessionId", 2);
        for (JSONObject different : new JSONObject[]{otherWindow, wholeDesktop, otherSession})
            assertThrows(SecurityException.class, () -> session(grant, different));

        var noViewer = signedGrant("viewer-missing-method", 1, binding,
                new JSONArray().put("device.describe"), new JSONArray().put(local.deviceId()));
        assertThrows(SecurityException.class, () -> session(noViewer, binding));
        var otherOrigin = signedGrant("viewer-other-origin", 1, binding,
                new JSONArray().put("desktop.viewer"), new JSONArray().put(remote.deviceId()));
        assertThrows(SecurityException.class, () -> session(otherOrigin, binding));

        // Mutating public JSON copies cannot widen the actual signed scope.
        grant.grant().getJSONArray("resources").put(wholeDesktop);
        assertThrows(SecurityException.class, () -> session(grant, wholeDesktop));
        try (var valid = session(grant, binding)) {
            valid.resourceBinding().put("windowId", 100);
            assertEquals(99, valid.resourceBinding().getInt("windowId"));
        }
        assertEquals(0, metadataCount());
        assertNoExecutionRecords();
    }

    @Test public void mergedViewerProfileRestoresOnlyItsOriginalSignedSqlScopeAndRejectsOldWire() throws Exception {
        assertOriginalSignedSqlScopeAndRejectsOldWire(
                "pwp1_37a2c7990f46ec2f9ea27ac7223cd2a845eb8b7ff491a7ede3f2d31a626e6675");
    }

    @Test public void mergedAndroidUserProfileRestoresOnlyItsOriginalSignedSqlScopeAndRejectsOldWire() throws Exception {
        assertOriginalSignedSqlScopeAndRejectsOldWire(PairingCoordinator.PERSISTED_ANDROID_USER_GRANT_PROFILE);
    }

    private void assertOriginalSignedSqlScopeAndRejectsOldWire(String previous) throws Exception {
        long now = System.currentTimeMillis();
        TaskStoreDeviceTrust nativeTrust = new TaskStoreDeviceTrust(sql);
        JSONObject legacy = nativeTrust.outgoingGrant(remote.deviceId(), grant.grantId()).put("protocolProfile", previous);
        signFixtureRecord(legacy);
        // A valid old signature is not accepted on today's live pairing wire.
        assertEquals("incompatible_profile", assertThrows(DeviceProtocol.ProtocolException.class,
                () -> pairing.acceptOutgoingGrant(remote, legacy, now)).code);
        nativeTrust.putOutgoingGrant(remote.deviceId(), grant.grantId(), legacy, now);
        String originalStoredReceipt = DeviceProtocol.canonical(legacy);
        var restored = pairing.restoreOutgoingGrant(remote, grant.grantId(), now);
        assertEquals(DeviceProtocol.canonical(grant.grant()), DeviceProtocol.canonical(restored.grant()));
        restored.grant().getJSONArray("capabilities").put("tool.invoke");
        restored.grant().getJSONArray("resources").put(copy(binding).put("windowId", 100));
        assertEquals(DeviceProtocol.canonical(grant.grant()), DeviceProtocol.canonical(restored.grant()));
        assertThrows(SecurityException.class, () -> session(restored, copy(binding).put("windowId", 100)));
        assertEquals(originalStoredReceipt, DeviceProtocol.canonical(nativeTrust.outgoingGrant(remote.deviceId(), grant.grantId())));
        try (var opened = session(restored, binding)) {
            var prepared = opened.prepare(new JSONObject().put("action", "configuration"));
            assertEquals(DeviceProtocol.PROFILE, prepared.request().json().getString("protocolProfile"));
            JSONObject oldWire = prepared.request().json().put("protocolProfile", previous);
            oldWire.put("argumentsDigest", DeviceProtocol.argumentsDigest(oldWire));
            assertEquals("incompatible_profile", assertThrows(DeviceProtocol.ProtocolException.class,
                    () -> DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(oldWire))).code);

            JSONObject outsideFrozenProfile = copy(legacy);
            outsideFrozenProfile.getJSONObject("body").getJSONObject("grant").put("grantId", "viewer-future-method")
                    .getJSONArray("capabilities").put("future.desktop.input");
            signFixtureRecord(outsideFrozenProfile);
            nativeTrust.putOutgoingGrant(remote.deviceId(), "viewer-future-method", outsideFrozenProfile, now);
            assertThrows(SecurityException.class, () -> pairing.restoreOutgoingGrant(remote, "viewer-future-method", now));

            // Controlled native SQL successor: neither an old opaque handle nor
            // restoring the same historic signature may skip its original epoch.
            sql.execSQL("UPDATE device_peers SET authorization_epoch=authorization_epoch+1 WHERE device_id=?", new Object[]{remote.deviceId()});
            assertThrows(SecurityException.class, () -> restored.checkCurrent(System.currentTimeMillis()));
            assertThrows(SecurityException.class, () -> pairing.restoreOutgoingGrant(remote, grant.grantId(), System.currentTimeMillis()));
            assertThrows(SecurityException.class, () -> tasks.deviceTasks().desktopViewerTicket(prepared).claimForWrite(remote));
            assertMetadata(prepared, "not_claimed");
        }
        assertNoExecutionRecords();
    }

    @Test public void nativeSqlWriteClaimIsOneUseAcrossTicketAliasesAndFreezesCallerJson() throws Exception {
        try (var session = session(grant, binding)) {
            JSONObject supplied = input().put("viewerSessionId", "caller-cannot-select-this-session");
            var send = session.prepare(supplied);
            byte[] original = DeviceProtocol.canonicalBytes(send.request().json());
            supplied.getJSONObject("input").put("text", "changed after prepare");
            send.request().json().put("targetDeviceId", local.deviceId());
            send.request().arguments().put("action", "close");
            assertEquals(session.id(), send.request().arguments().getString("viewerSessionId"));

            var first = tasks.deviceTasks().desktopViewerTicket(send);
            var alias = tasks.deviceTasks().desktopViewerTicket(send);
            assertThrows(SecurityException.class, () -> first.claimForWrite(unrelatedPeer()));
            assertThrows(SecurityException.class, () -> first.claimForWrite(null));
            assertFalse(first.isClaimed());
            assertMetadata(send, "not_claimed");
            assertEquals(original.length, first.size());
            assertArrayEquals(original, first.claimForWrite(remote));
            assertTrue(alias.isClaimed());
            assertMetadata(send, "write_claimed");
            assertThrows(SecurityException.class, () -> first.claimForWrite(remote));
            assertThrows(SecurityException.class, () -> alias.claimForWrite(remote));
            alias.abandonBeforeWrite();
            assertMetadata(send, "write_claimed");
            send.accept(remote, response(send));
            assertThrows(SecurityException.class, () -> send.accept(remote, response(send)));
            assertThrows(SecurityException.class, () -> alias.claimForWrite(remote));
            assertMetadata(send, "receipt");
            assertNoExecutionRecords();
        }
    }

    @Test public void ownerPageClosureAfterQueueingRefusesAtActualTlsWrite() throws Exception {
        assertRejectedAtTlsWrite(() -> pageCurrent.set(false));
    }

    @Test public void actualTlsWriterSendsFrozenViewerOnceAndRefusesAnAliasedTicket() throws Exception {
        try (var session = session(grant, binding); var tls = new TlsLink()) {
            var send = session.prepare(input());
            var ticket = tasks.deviceTasks().desktopViewerTicket(send);
            var alias = tasks.deviceTasks().desktopViewerTicket(send);
            assertTrue(tls.senderReady.await(10, TimeUnit.SECONDS));
            assertTrue(tls.receiverReady.await(10, TimeUnit.SECONDS));
            assertTrue(tls.sender.sendControl(ticket));
            assertTrue(tls.sender.sendControl(alias));
            assertFalse(ticket.isClaimed());
            tls.allowWrites.countDown();
            assertArrayEquals(DeviceProtocol.canonicalBytes(send.request().json()), tls.received.poll(10, TimeUnit.SECONDS));
            assertSame(alias, tls.rejected.poll(10, TimeUnit.SECONDS));
            byte[] barrier = "{\"hostFixtureBarrier\":true}".getBytes(StandardCharsets.UTF_8);
            assertTrue(tls.sender.sendControl(barrier));
            assertArrayEquals(barrier, tls.received.poll(10, TimeUnit.SECONDS));
            assertTrue(ticket.isClaimed());
            assertTrue(alias.isClaimed());
            send.accept(tls.senderPeer.get(), response(send));
            assertMetadata(send, "receipt");
            assertNoExecutionRecords();
        }
    }

    @Test public void nativeRevocationAfterQueueingRefusesAtActualTlsWrite() throws Exception {
        assertRejectedAtTlsWrite(() -> pairing.revokeLocally(remote.deviceId(), System.currentTimeMillis()));
    }

    @Test public void signedGrantReplacementFencesExistingSessionAndEveryQueuedTicket() throws Exception {
        try (var session = session(grant, binding)) {
            var pending = session.prepare(input());
            var ticket = tasks.deviceTasks().desktopViewerTicket(pending);
            JSONObject replacementScope = copy(binding).put("windowId", 100);
            var replacement = signedGrant(grant.grantId(), 2, replacementScope,
                    new JSONArray().put("desktop.viewer"), new JSONArray().put(local.deviceId()));
            assertThrows(SecurityException.class, session::require);
            assertThrows(SecurityException.class, () -> session.prepare(input()));
            assertThrows(SecurityException.class, session::prepareClose);
            assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
            assertFalse(ticket.isClaimed());
            assertMetadata(pending, "not_claimed");
            assertThrows(SecurityException.class, () -> session(replacement, binding));
            try (var current = session(replacement, replacementScope)) { current.require(); }
            assertNoExecutionRecords();
        }
    }

    @Test public void cleanupCanOnlySendFrozenCloseAndCannotReviveEarlierInputTicket() throws Exception {
        try (var session = session(grant, binding)) {
            var pending = session.prepare(input());
            var pendingTicket = tasks.deviceTasks().desktopViewerTicket(pending);
            pageCurrent.set(false);
            assertThrows(SecurityException.class, () -> session.prepare(input()));
            assertThrows(SecurityException.class, () -> session.prepare(new JSONObject().put("action", "close")));

            var cleanup = session.prepareClose();
            var closeTicket = tasks.deviceTasks().desktopViewerTicket(cleanup);
            JSONObject exposed = cleanup.request().arguments();
            assertEquals(Set.of("action", "viewerSessionId"), DeviceProtocol.objectKeys(exposed));
            assertEquals("close", exposed.getString("action"));
            exposed.put("action", "input").put("input", new JSONObject().put("kind", "insert_text").put("text", INPUT_SECRET));
            JSONObject actual = DeviceProtocol.parseRequest(closeTicket.claimForWrite(remote)).arguments();
            assertEquals(Set.of("action", "viewerSessionId"), DeviceProtocol.objectKeys(actual));
            assertEquals("close", actual.getString("action"));
            assertEquals(session.id(), actual.getString("viewerSessionId"));
            cleanup.accept(remote, response(cleanup));
            assertThrows(SecurityException.class, () -> pendingTicket.claimForWrite(remote));
            assertFalse(pendingTicket.isClaimed());
            assertMetadata(pending, "not_claimed");
            assertMetadata(cleanup, "receipt");

            assertThrows(SecurityException.class, session::prepareClose);
            session.close();
            assertThrows(SecurityException.class, session::prepareClose);
            assertNoExecutionRecords();
        }
    }

    @Test public void fullyReleasedSessionCannotClaimItsPreviouslyPreparedCleanup() throws Exception {
        var session = session(grant, binding);
        var cleanup = session.prepareClose();
        var ticket = tasks.deviceTasks().desktopViewerTicket(cleanup);
        session.close();
        assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
        assertThrows(SecurityException.class, session::prepareClose);
        assertFalse(ticket.isClaimed());
        assertMetadata(cleanup, "not_claimed");
        assertNoExecutionRecords();
    }

    @Test public void cleanupStillRequiresCurrentGrantAndCannotBypassNativeRevocation() throws Exception {
        try (var session = session(grant, binding)) {
            pageCurrent.set(false);
            var cleanup = session.prepareClose();
            var ticket = tasks.deviceTasks().desktopViewerTicket(cleanup);
            pairing.revokeLocally(remote.deviceId(), System.currentTimeMillis());
            assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
            assertThrows(SecurityException.class, session::prepareClose);
            assertFalse(ticket.isClaimed());
            assertMetadata(cleanup, "not_claimed");
        }
    }

    @Test public void receiptRequiresClaimedRequestOperationSessionInstanceAndActualTlsPeer() throws Exception {
        try (var session = session(grant, binding)) {
            var send = session.prepare(input());
            JSONObject valid = response(send);
            assertThrows(SecurityException.class, () -> send.accept(remote, valid));
            var ticket = tasks.deviceTasks().desktopViewerTicket(send);
            ticket.claimForWrite(remote);
            assertThrows(SecurityException.class, () -> send.accept(unrelatedPeer(), valid));
            for (String field : new String[]{"requestId", "executorDeviceId", "instanceId"}) {
                JSONObject substituted = copy(valid).put(field,
                        "executorDeviceId".equals(field) ? local.deviceId() : "another-native-" + field);
                if ("executorDeviceId".equals(field)) substituted.getJSONObject("result").put("deviceId", local.deviceId());
                DeviceProtocol.validateMessage(substituted); // A valid message, but not this request's receipt.
                assertThrows("response substitution: " + field, SecurityException.class,
                        () -> send.accept(remote, substituted));
                assertMetadata(send, "write_claimed");
            }
            for (String field : new String[]{"viewerSessionId", "operationId"}) {
                JSONObject substituted = copy(valid);
                substituted.getJSONObject("result").put(field, "another-native-" + field);
                if ("viewerSessionId".equals(field)) substituted.getJSONObject("result").put("sessionId", "another-native-" + field);
                DeviceProtocol.validateMessage(substituted);
                assertThrows("result substitution: " + field, SecurityException.class,
                        () -> send.accept(remote, substituted));
                assertMetadata(send, "write_claimed");
            }
            send.accept(remote, valid);
            assertMetadata(send, "receipt");
            assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
            assertNoExecutionRecords();
        }
    }

    @Test public void lateReceiptAfterClosureOrRevocationKeepsUncertainClaimAndNeverReplays() throws Exception {
        try (var closedSession = session(grant, binding)) {
            var send = closedSession.prepare(input());
            var ticket = tasks.deviceTasks().desktopViewerTicket(send);
            ticket.claimForWrite(remote);
            closedSession.close();
            assertThrows(SecurityException.class, () -> send.accept(remote, response(send)));
            assertMetadata(send, "write_claimed");
            assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
        }
        try (var revokedSession = session(grant, binding)) {
            var send = revokedSession.prepare(input());
            var ticket = tasks.deviceTasks().desktopViewerTicket(send);
            ticket.claimForWrite(remote);
            pairing.revokeLocally(remote.deviceId(), System.currentTimeMillis());
            assertThrows(SecurityException.class, () -> send.accept(remote, response(send)));
            assertMetadata(send, "write_claimed");
            assertThrows(SecurityException.class, () -> ticket.claimForWrite(remote));
            assertTrue(ticket.isClaimed());
        }
        assertNoExecutionRecords();
    }

    private void assertRejectedAtTlsWrite(Checked invalidate) throws Exception {
        try (var session = session(grant, binding); var tls = new TlsLink()) {
            var send = session.prepare(input());
            var ticket = tasks.deviceTasks().desktopViewerTicket(send);
            assertTrue(tls.senderReady.await(10, TimeUnit.SECONDS));
            assertTrue(tls.receiverReady.await(10, TimeUnit.SECONDS));
            assertEquals(remote.deviceId(), tls.senderPeer.get().deviceId());
            assertTrue(tls.sender.sendControl(ticket));
            assertFalse(ticket.isClaimed()); // Queue admission is not a TLS write.
            invalidate.run();
            tls.allowWrites.countDown();
            assertSame(ticket, tls.rejected.poll(10, TimeUnit.SECONDS));

            // Ordered application-frame barrier: if the forbidden viewer had escaped,
            // it would arrive before this frame on the same authenticated TCP/TLS lane.
            byte[] barrier = "{\"hostFixtureBarrier\":true}".getBytes(StandardCharsets.UTF_8);
            assertTrue(tls.sender.sendControl(barrier));
            assertArrayEquals(barrier, tls.received.poll(10, TimeUnit.SECONDS));
            assertFalse(ticket.isClaimed());
            assertMetadata(send, "not_claimed");
            assertNoExecutionRecords();
        }
    }

    private TaskStoreDesktopViewer.Session session(PairingCoordinator.OutgoingGrant route, JSONObject scope) throws Exception {
        return tasks.deviceTasks().openDesktopViewer(local, route, scope, () -> {
            if (!pageCurrent.get()) throw new SecurityException("Original owner page is no longer current");
        });
    }

    private PairingCoordinator.OutgoingGrant signedGrant(String id, long epoch, JSONObject scope,
            JSONArray capabilities, JSONArray origins) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject policy = new JSONObject().put("grantId", id).put("peerDeviceId", local.deviceId())
                .put("localDeviceId", remote.deviceId()).put("authorizationEpoch", epoch)
                .put("issuedAt", now - 1000).put("expiresAt", now + 300000)
                .put("capabilities", capabilities).put("toolNames", new JSONArray())
                .put("resources", new JSONArray().put(copy(scope))).put("allowedOrigins", origins)
                .put("allowDelegation", false).put("maxDelegationDepth", 0).put("toolBudget", 0).put("modelBudget", 0);
        JSONObject receipt = new JSONObject().put("protocolVersion", DeviceProtocol.VERSION)
                .put("protocolProfile", DeviceProtocol.PROFILE).put("messageType", "PairGrantReceipt")
                .put("issuerDeviceId", remote.deviceId()).put("issuerCertificateFingerprint", remote.certificateFingerprint())
                .put("body", new JSONObject().put("grant", policy).put("targetInstanceId", "caller-fixture").put("issuedAt", now));
        KeyPair keys = (KeyPair) recordValue(remoteMaterial, "keys");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(DeviceProtocol.canonicalBytes(receipt));
        receipt.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        // Production signature verification and native outgoing-grant persistence.
        return pairing.acceptOutgoingGrant(remote, receipt, System.currentTimeMillis());
    }

    private void signFixtureRecord(JSONObject record) throws Exception {
        record.remove("signature");
        KeyPair keys = (KeyPair) recordValue(remoteMaterial, "keys");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate()); signer.update(DeviceProtocol.canonicalBytes(record));
        record.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static JSONObject input() throws Exception {
        long captured = System.currentTimeMillis();
        return new JSONObject().put("action", "input").put("streamId", "native-viewer-stream")
                .put("controlLease", new JSONObject().put("id", "1".repeat(32)).put("fence", 1)
                        .put("expiresAt", captured + 30000).put("resource", "native-fixture-desktop"))
                .put("frameRef", new JSONObject().put("frameId", "fixture-frame").put("streamId", "native-viewer-stream")
                        .put("geometryRevision", "fixture-geometry").put("width", 800).put("height", 600)
                        .put("capturedAt", captured).put("expiresAt", captured + 30000).put("rtpTimestamp", 0))
                .put("input", new JSONObject().put("kind", "insert_text").put("text", INPUT_SECRET));
    }

    private JSONObject response(TaskStoreDesktopViewer.Send send) throws Exception {
        JSONObject args = send.request().arguments();
        String id = args.getString("viewerSessionId"), action = args.getString("action");
        JSONObject result = new JSONObject().put("viewerSessionId", id).put("operationId", send.request().operationId());
        if ("close".equals(action)) result.put("status", "closed");
        else {
            JSONObject control = new JSONObject().put("owner", "input".equals(action) ? "human" : "none")
                    .put("revision", "input".equals(action) ? 1 : 0).put("needsReconcile", false);
            if ("input".equals(action)) control.put("lease", args.getJSONObject("controlLease"));
            result.put("kind", "desktopSession").put("deviceId", remote.deviceId()).put("sessionId", id)
                    .put("resourceBinding", binding).put("expiresAt", send.request().deadline()).put("width", 800).put("height", 600)
                    .put("control", control).put("iceServers", new JSONArray());
            if (args.has("streamId")) result.put("streamId", args.getString("streamId"));
            if ("negotiate".equals(action)) result.put("streamId", "native-viewer-stream")
                    .put("answer", new JSONObject().put("type", "answer").put("sdp", ANSWER_SECRET));
            if ("input".equals(action)) result.put("inputReceipt", new JSONObject().put("status", "completed").put("inputDispatched", true));
        }
        return new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile", DeviceProtocol.PROFILE)
                .put("messageType", "DeviceResponse").put("requestId", send.request().requestId())
                .put("method", "desktop.viewer").put("executorDeviceId", remote.deviceId()).put("instanceId", "caller-fixture")
                .put("result", result);
    }

    private void assertNoExecutionRecords() {
        for (String table : new String[]{"tasks", "runs", "operations", "device_inbound", "device_child_budget", "device_child_links"}) {
            try (Cursor row = sql.rawQuery("SELECT count(*) FROM " + table, null)) {
                assertTrue(row.moveToFirst());
                assertEquals("Owner viewer must not create " + table, 0, row.getLong(0));
            }
        }
    }

    private long metadataCount() {
        try (Cursor row = sql.rawQuery("SELECT count(*) FROM meta WHERE key LIKE ?", new String[]{META_PREFIX + "%"})) {
            assertTrue(row.moveToFirst()); return row.getLong(0);
        }
    }

    private void assertMetadata(TaskStoreDesktopViewer.Send send, String expectedState) throws Exception {
        try (Cursor row = sql.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{META_PREFIX + send.request().operationId()})) {
            assertTrue(row.moveToFirst());
            String stored = row.getString(0);
            JSONObject metadata = new JSONObject(stored);
            Set<String> permitted = "receipt".equals(expectedState)
                    ? Set.of("sessionId", "operationId", "wireHash", "state", "expiresAt", "status")
                    : Set.of("sessionId", "operationId", "wireHash", "state", "expiresAt");
            assertEquals(permitted, DeviceProtocol.objectKeys(metadata));
            assertEquals(expectedState, metadata.getString("state"));
            assertEquals(send.request().arguments().getString("viewerSessionId"), metadata.getString("sessionId"));
            assertEquals(send.request().operationId(), metadata.getString("operationId"));
            assertEquals(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(send.request().json())), metadata.getString("wireHash"));
            assertEquals(send.request().deadline(), metadata.getLong("expiresAt"));
            for (String secret : new String[]{INPUT_SECRET, "viewer-offer-secret", "viewer-answer-secret", TURN_SECRET})
                assertFalse("Viewer plaintext must not enter persisted metadata", stored.contains(secret));
        }
    }

    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static Object field(Object source, String name) throws Exception {
        Field field = source.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(source);
    }
    private static Object recordValue(Object source, String name) throws Exception {
        Method accessor = source.getClass().getDeclaredMethod(name); accessor.setAccessible(true); return accessor.invoke(source);
    }
    private static DeviceIdentity.AuthenticatedPeer unrelatedPeer() throws Exception {
        Method tls = DeviceIngressNativeTest.class.getDeclaredMethod("tls"); tls.setAccessible(true);
        return (DeviceIdentity.AuthenticatedPeer) recordValue(tls.invoke(null), "caller");
    }
    private static SSLContext context(Object identity, Object trusted) throws Exception {
        Method context = DeviceIngressNativeTest.class.getDeclaredMethod("context", identity.getClass(), trusted.getClass());
        context.setAccessible(true); return (SSLContext) context.invoke(null, identity, trusted);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }

    /** Real production DeviceConnection with a deterministic queue-to-write gap. */
    private final class TlsLink implements AutoCloseable {
        final CountDownLatch senderReady = new CountDownLatch(1), receiverReady = new CountDownLatch(1);
        final CountDownLatch allowWrites = new CountDownLatch(1);
        final AtomicReference<DeviceIdentity.AuthenticatedPeer> senderPeer = new AtomicReference<>();
        final BlockingQueue<TaskStoreDeviceTasks.OutboundSendTicket> rejected = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final DeviceConnection sender, receiver;

        TlsLink() throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                Socket server = listener.accept();
                sender = new DeviceConnection(context(localMaterial, remoteMaterial), true, DeviceConnection.Lane.CONTROL,
                        new SocketByteChannel(client), new Listener() {
                            @Override public void onConnection(DeviceConnection connection, DeviceIdentity.AuthenticatedPeer peer) throws Exception {
                                senderPeer.set(peer); senderReady.countDown();
                                if (!allowWrites.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Host write barrier was not released");
                            }
                            @Override public void onSendGateFailed(TaskStoreDeviceTasks.OutboundSendTicket ticket) { rejected.add(ticket); }
                        });
                receiver = new DeviceConnection(context(remoteMaterial, localMaterial), false, DeviceConnection.Lane.CONTROL,
                        new SocketByteChannel(server), new Listener() {
                            @Override public void onConnection(DeviceConnection connection, DeviceIdentity.AuthenticatedPeer peer) {
                                assertEquals(local.deviceId(), peer.deviceId()); receiverReady.countDown();
                            }
                            @Override public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] json) { received.add(json.clone()); }
                        });
            }
            workers.submit(receiver); workers.submit(sender);
        }
        @Override public void close() throws Exception {
            allowWrites.countDown();
            try { sender.close(); } finally { try { receiver.close(); } finally { workers.shutdownNow(); } }
            assertTrue("Native TLS fixture threads must stop", workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    private abstract static class Listener implements DeviceConnection.Listener {
        @Override public void onOnline(DeviceIdentity.AuthenticatedPeer peer) { }
        @Override public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] json) { }
        @Override public void onChunk(DeviceIdentity.AuthenticatedPeer peer, UUID transfer, long offset, byte[] bytes) {
            throw new AssertionError("Viewer control must not use the bulk lane");
        }
        @Override public void onClosed(DeviceConnection.State state, boolean deliveryMayBeUnknown) { }
    }
}
