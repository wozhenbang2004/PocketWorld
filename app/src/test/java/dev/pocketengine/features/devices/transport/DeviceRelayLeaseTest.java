package dev.pocketengine.features.devices.transport;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit byte-channel fixtures. No WAN, Android device, actual relay credential or service. */
public final class DeviceRelayLeaseTest {
    static final String LOCAL = "pw1_" + "a".repeat(64), PEER = "pw1_" + "b".repeat(64), OTHER = "pw1_" + "c".repeat(64);
    static final String TOKEN = "test_only_" + "x".repeat(40), ROUTE = "isolated_test_route_" + "x".repeat(22);
    static JSONObject config(long expiry) throws Exception { return new JSONObject().put("format", DeviceRelayLease.FORMAT).put("serviceBase", "https://relay.example.invalid")
            .put("inviterDeviceId", PEER).put("routeId", ROUTE).put("side", "b").put("routeToken", TOKEN).put("expiresAt", expiry); }
    static final class Bytes implements DeviceByteChannel {
        volatile boolean closed; int sends;
        public byte[] receive(int timeout) throws InterruptedException { Thread.sleep(Math.min(10, timeout)); return null; }
        public void send(byte[] raw) throws IOException { if (closed) throw new IOException("closed"); sends++; }
        public void flush() { }
        public void close() { closed = true; }
    }
    @Test public void nativeViewHasNoBearerOrRouteAndCannotAcquireDeviceAuthority() throws Exception {
        try (DeviceRelayLease lease = DeviceRelayLease.review(config(System.currentTimeMillis() + 60_000).toString(), LOCAL, "local-instance", System.currentTimeMillis())) {
            JSONObject view = lease.view(); assertFalse(view.toString().contains(TOKEN)); assertFalse(view.has("routeToken")); assertFalse(view.has("routeId"));
            assertTrue(view.getBoolean("memoryOnly")); assertFalse(view.getBoolean("devicePermissionGranted")); assertFalse(view.getBoolean("peerBound"));
            assertFalse(lease.toString().contains(TOKEN)); assertFalse(lease.boundTo(PEER));
        }
    }
    @Test public void rejectsEndpointCredentialsPathsUnknownFieldsExpiryAndImplicitNativeIdentity() throws Exception {
        for (String endpoint : new String[]{"http://relay.example.invalid", "wss://user:secret@relay.example.invalid", "wss://relay.example.invalid?token=a", "wss://relay.example.invalid#fragment", "wss://relay.example.invalid/unreviewed/path", "wss://relay.example.invalid:0"}) {
            assertThrows(Exception.class, () -> DeviceRelayLease.review(config(System.currentTimeMillis() + 60_000).put("serviceBase", endpoint).toString(), LOCAL, "instance", System.currentTimeMillis()));
        }
        for (long deadline : new long[]{0, System.currentTimeMillis() - 1, System.currentTimeMillis() + 3_700_000})
            assertThrows(Exception.class, () -> DeviceRelayLease.review(config(deadline).toString(), LOCAL, "instance", System.currentTimeMillis()));
        assertThrows(Exception.class, () -> DeviceRelayLease.review(config(System.currentTimeMillis() + 60_000).put("grant", new JSONObject()).toString(), LOCAL, "instance", System.currentTimeMillis()));
        assertThrows(Exception.class, () -> DeviceRelayLease.review(config(System.currentTimeMillis() + 60_000).put("side", "a/control").toString(), LOCAL, "instance", System.currentTimeMillis()));
        assertThrows(Exception.class, () -> DeviceRelayLease.review(config(System.currentTimeMillis() + 60_000).toString(), null, "instance", System.currentTimeMillis()));
    }
    @Test public void originalIdentityAndOneNativePeerRemainBoundAcrossAllFourSeparateLanes() throws Exception {
        List<String> endpoints = new ArrayList<>(); List<Bytes> opened = new ArrayList<>();
        try (DeviceRelayLease lease = DeviceRelayLease.review(config(System.currentTimeMillis() + 60_000).toString(), LOCAL, "original-instance", System.currentTimeMillis(), (endpoint, secret) -> {
            assertEquals(TOKEN, secret); endpoints.add(endpoint); Bytes bytes = new Bytes(); opened.add(bytes); return bytes;
        })) {
            assertThrows(SecurityException.class, () -> lease.requireCurrent(OTHER, "original-instance", System.currentTimeMillis()));
            assertThrows(SecurityException.class, () -> lease.requireCurrent(LOCAL, "rotated-instance", System.currentTimeMillis()));
            assertThrows(SecurityException.class, () -> lease.bindPeer(OTHER)); lease.bindPeer(PEER); assertTrue(lease.boundTo(PEER));
            assertThrows(SecurityException.class, () -> lease.bindPeer(OTHER));
            for (String lane : new String[]{"pairing", "pairing-complete", "control", "bulk"}) lease.open(lane);
            for (int i = 0; i < endpoints.size(); i++) assertEquals("wss://relay.example.invalid/v1/tunnel/" + ROUTE + "/b/" + new String[]{"pairing", "pairing-complete", "control", "bulk"}[i], endpoints.get(i));
            assertEquals(4, opened.size()); assertTrue(opened.stream().noneMatch(b -> b.closed || b.sends != 0));
            lease.open("control"); assertTrue(opened.get(2).closed); assertFalse(opened.get(3).closed); assertEquals(0, opened.get(4).sends);
            assertThrows(IllegalArgumentException.class, () -> lease.open("../../control"));
        }
        assertTrue(opened.stream().allMatch(b -> b.closed));
    }
    @Test public void expiredOrRemovedLeaseClosesItsByteSlotsAndErasesOnlyItsEphemeralBearer() throws Exception {
        Bytes bytes = new Bytes(); DeviceRelayLease lease = DeviceRelayLease.review(config(System.currentTimeMillis() + 250).toString(), LOCAL, "instance", System.currentTimeMillis(), (e, s) -> bytes);
        lease.open("bulk"); long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (!bytes.closed && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(bytes.closed); assertThrows(SecurityException.class, () -> lease.open("control"));
        Field token = DeviceRelayLease.class.getDeclaredField("token"); token.setAccessible(true); for (char c : (char[]) token.get(lease)) assertEquals(0, c);
    }
    @Test public void isolatedRelayInvitationStopsItsBlockedReadOnOwnerCloseWithoutBusinessDispatch() throws Exception {
        CountDownLatch connected = new CountDownLatch(1); Bytes bytes = new Bytes(); long deadline = System.currentTimeMillis() + 3000;
        try (DeviceRelayLease lease = DeviceRelayLease.review(config(deadline + 1000).put("inviterDeviceId", LOCAL).toString(), LOCAL, "instance", System.currentTimeMillis(), (e, s) -> { connected.countDown(); return bytes; })) {
            RelayPairingEndpoint invitation = new RelayPairingEndpoint(lease, deadline, () -> {});
            invitation.startCandidate(SSLContext.getDefault(), raw -> { fail("No complete peer TLS/offer in this byte fixture"); return null; });
            assertTrue(connected.await(2, TimeUnit.SECONDS)); assertEquals("relay", invitation.connectionHint().getString("kind"));
            invitation.close(); assertTrue(bytes.closed); assertFalse(invitation.isCandidateListening()); assertThrows(SecurityException.class, invitation::connectionHint);
        }
    }
}
