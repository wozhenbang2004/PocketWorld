package dev.pocketengine.features.devices.transport;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.PairingCoordinator;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static dev.pocketengine.features.devices.transport.DeviceRelayTlsJourneyTest.*;
import static org.junit.Assert.*;

/** Original PairingCoordinator + actual TaskStoreDeviceTrust SQLite adapter over real local
 * TLS WSS. Two isolated test SQLite connections/native synthetic identities, not a new product
 * store/Runner or evidence of real Android keys, WAN, UI, desktop execution or device grants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
// The synthetic localhost bridge uses host JSSE; Robolectric's injected Conscrypt is not an
// Android-provider interop environment (its EC parameter shim breaks JSSE initialization).
// Physical Android/Conscrypt acceptance remains separate, not inferred from this SQL journey.
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class DeviceRelayPairingRecoverySqlTest {
    private static DeviceIdentity.LocalIdentity identity(Material material) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null);
        store.setKeyEntry("pocketworld.device.identity.v1", material.keys().getPrivate(), null, new java.security.cert.Certificate[]{material.cert()});
        var constructor = DeviceIdentity.LocalIdentity.class.getDeclaredConstructor(KeyStore.class, java.security.cert.X509Certificate.class, String.class); constructor.setAccessible(true);
        return constructor.newInstance(store, material.cert(), "synthetic-instance");
    }
    private static JSONObject grant(String local, String peer, String id) throws Exception {
        long now = System.currentTimeMillis(); return new JSONObject().put("grantId", id).put("peerDeviceId", peer).put("localDeviceId", local).put("authorizationEpoch", 1).put("issuedAt", now).put("expiresAt", now + 60_000)
                .put("capabilities", new JSONArray().put("device.describe").put("tool.invoke").put("operation.get")).put("toolNames", new JSONArray().put("phone_observe"))
                .put("resources", new JSONArray().put(new JSONObject().put("screenMode", "main_screen").put("packageNames", new JSONArray().put("synthetic.fixture.app"))))
                .put("allowedOrigins", new JSONArray().put(peer)).put("allowDelegation", false).put("maxDelegationDepth", 0).put("toolBudget", 4).put("modelBudget", 0);
    }
    private static long rows(SQLiteDatabase db, String table) { try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) { cursor.moveToFirst(); return cursor.getLong(0); } }
    @Test public void originalNativeFingerprintsScopeCommitAndReadOnlyRecoveryUseTheSameSqlRecordsAcrossWss() throws Exception {
        Material a = material("Synthetic SQL joiner"), b = material("Synthetic SQL inviter"), outer = material("Synthetic SQL relay"); var nativeA = identity(a); var nativeB = identity(b);
        OkHttpClient outerTls = platformClient(outer);
        try (SQLiteDatabase dbA = SQLiteDatabase.create(null); SQLiteDatabase dbB = SQLiteDatabase.create(null); Bridge relay = new Bridge(outer);
             DeviceRelayLease la = lease(relay, nativeA.deviceId(), nativeB.deviceId(), "a", outerTls); DeviceRelayLease lb = lease(relay, nativeB.deviceId(), nativeB.deviceId(), "b", outerTls)) {
            TaskStoreDeviceTrust.createSchema(dbA); TaskStoreDeviceTrust.createSchema(dbB); TaskStoreDeviceTrust storeA = new TaskStoreDeviceTrust(dbA), storeB = new TaskStoreDeviceTrust(dbB);
            var ca = new PairingCoordinator(storeA, nativeA.deviceId(), nativeA.instanceId()); var cb = new PairingCoordinator(storeB, nativeB.deviceId(), nativeB.instanceId());
            JSONObject ga = grant(nativeA.deviceId(), nativeB.deviceId(), "synthetic-a-owner"), gb = grant(nativeB.deviceId(), nativeA.deviceId(), "synthetic-b-owner");
            JSONObject invitation = cb.createInvitation(nativeB, null, System.currentTimeMillis(), 25_000, new JSONObject().put("kind", "relay").put("serviceBase", relay.service()).put("inviterDeviceId", nativeB.deviceId()));
            AtomicReference<PairingCoordinator.Candidate> candidate = new AtomicReference<>(); AtomicReference<PairingCoordinator.CandidateOffer> offer = new AtomicReference<>();
            java.util.List<Thread> priorInvitationWorkers = new java.util.ArrayList<>();
            try (RelayPairingEndpoint endpoint = new RelayPairingEndpoint(lb, invitation.getLong("expiresAt"), () -> {})) {
                endpoint.startCandidate(context(b, a), bytes -> { var selected = cb.inspectCandidate(nativeB, bytes, System.currentTimeMillis()); candidate.set(selected); return DeviceProtocol.canonicalBytes(cb.candidateReceipt(selected, System.currentTimeMillis())); });
                byte[] receipt;
                try { receipt = PairingCandidateTransport.exchange(context(a, b), nativeB.certificateDer(), la.open("pairing"), invitation.getLong("expiresAt"), verified -> {
                    var selected = ca.prepareCandidateOffer(nativeA, invitation, verified, ga, true, System.currentTimeMillis()); offer.set(selected); return selected.wireBytes();
                }); } catch (Exception failure) { failure.addSuppressed(new java.io.IOException("Synthetic relay upgrades=" + relay.upgrades.get() + ", lanes=" + relay.lanesSeen)); for (Throwable detail : relay.failures) failure.addSuppressed(detail); throw failure; }
                assertEquals("pending_owner_confirmation", PairingCoordinator.verifyCandidateReceipt(offer.get(), receipt).getString("state")); assertEquals(0, rows(dbA, "device_peers")); assertEquals(0, rows(dbB, "device_peers"));
                var pending = cb.confirmCandidateLocally(nativeB, candidate.get(), gb, true, System.currentTimeMillis());
                endpoint.startCompletion(context(b, a), pending, (actual, raw) -> DeviceProtocol.canonicalBytes(cb.finalizeCandidatePairing(pending, actual, raw, gb, System.currentTimeMillis())));
                PairingCompletionTransport.exchange(context(a, b), nativeB.certificateDer(), la.open("pairing-complete"), invitation.getLong("expiresAt"), new PairingCompletionTransport.Client() {
                    public byte[] request(DeviceIdentity.AuthenticatedPeer actual) throws Exception { assertEquals(nativeB.deviceId(), actual.deviceId()); return DeviceProtocol.canonicalBytes(PairingCoordinator.candidateFinalizeRequest(offer.get())); }
                    public void accept(DeviceIdentity.AuthenticatedPeer actual, byte[] completion) throws Exception { ca.acceptCandidateCompletion(offer.get(), actual, completion, ga, System.currentTimeMillis()); }
                });
                var workerField = RelayPairingEndpoint.class.getDeclaredField("workers"); workerField.setAccessible(true);
                for (Object thread : (java.util.Set<?>) workerField.get(endpoint)) priorInvitationWorkers.add((Thread) thread);
            }
            // Opening a distinct native read window is not overlapping/retrying its just-closed
            // isolated onboarding admission. Establish actual old worker release, not a sleep.
            for (Thread thread : priorInvitationWorkers) { thread.join(2000); assertFalse("Original invitation worker must release its bounded admission", thread.isAlive()); }
            assertEquals(1, rows(dbA, "device_peers")); assertEquals(1, rows(dbB, "device_peers")); assertEquals(1, rows(dbA, "device_outgoing_grants"));
            assertEquals(4, storeB.grant("synthetic-b-owner").getInt("toolBudget")); assertFalse(storeB.grant("synthetic-b-owner").getBoolean("allowDelegation"));
            String id = invitation.getString("inviteId"), exactCommitted = DeviceProtocol.canonical(storeB.committedCompletion(id));
            var originalOffer = ca.restorePairingOffer(nativeA, id, System.currentTimeMillis()); var originalRead = cb.committedPairingRecovery(nativeB, id, candidate.get().candidateDigest(), System.currentTimeMillis(), 10_000);
            relay.closeLane("pairing-complete");
            try (RelayPairingEndpoint recovery = new RelayPairingEndpoint(lb, originalRead.expiresAt(), () -> originalRead.checkCurrent(System.currentTimeMillis()))) {
                recovery.startRecovery(context(b, a), originalRead, (actual, raw) -> DeviceProtocol.canonicalBytes(cb.recoverCandidatePairing(originalRead, actual, raw, System.currentTimeMillis())));
                PairingCompletionTransport.exchangeRecovery(context(a, b), nativeB.certificateDer(), la.open("pairing-complete"), System.currentTimeMillis() + 8000, new PairingCompletionTransport.Client() {
                    public byte[] request(DeviceIdentity.AuthenticatedPeer actual) throws Exception { originalOffer.requireTargetPeer(actual, System.currentTimeMillis()); return DeviceProtocol.canonicalBytes(PairingCoordinator.candidateRecoveryRequest(originalOffer)); }
                    public void accept(DeviceIdentity.AuthenticatedPeer actual, byte[] completion) throws Exception { ca.acceptRecoveredCandidateCompletion(originalOffer, actual, completion, System.currentTimeMillis()); }
                });
                assertEquals(exactCommitted, DeviceProtocol.canonical(storeB.committedCompletion(id))); assertEquals(1, rows(dbA, "device_peers")); assertEquals(1, rows(dbB, "device_grants"));
                storeB.revoke(nativeA.deviceId(), System.currentTimeMillis()); assertThrows(SecurityException.class, () -> originalRead.checkCurrent(System.currentTimeMillis()));
            }
            assertTrue(relay.lanesSeen.containsAll(java.util.Set.of("pairing", "pairing-complete")));
        } finally { outerTls.dispatcher().executorService().shutdownNow(); outerTls.connectionPool().evictAll(); }
    }
}
