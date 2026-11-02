package dev.pocketengine.features.devices.transport;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LanDeviceListenerTest {
    @Test public void currentNativePinsAreResampledForEveryAcceptWithoutRebinding() throws Exception {
        AtomicInteger reads = new AtomicInteger(); CountDownLatch sampled = new CountDownLatch(2);
        LanDeviceListener.CurrentContext pins = () -> {
            reads.incrementAndGet(); sampled.countDown();
            // No paired native pins -> reject THIS connection, not a trust-all fallback.
            throw new SecurityException("No currently paired peers");
        };
        try (LanDeviceListener listener = new LanDeviceListener(true, new InetSocketAddress("127.0.0.1", 0), pins, DeviceConnection.Lane.CONTROL,
                socket -> { throw new AssertionError("No business listener before current pins"); })) {
            int port = listener.port();
            try (Socket first = new Socket("127.0.0.1", port); Socket second = new Socket("127.0.0.1", port)) {
                assertTrue(sampled.await(3, TimeUnit.SECONDS)); assertEquals(2, reads.get());
                assertTrue(listener.isListening()); assertEquals(port, listener.port());
            }
        }
    }    @Test public void nativeReservationKeepsCompletionPortAndDoesNotInventReadyHint() throws Exception {
        LanDeviceListener.CurrentContext noPeers = () -> { throw new SecurityException("No paired peers"); };
        try (LanDeviceListener control = new LanDeviceListener(true, new InetSocketAddress("127.0.0.1", 0), noPeers, DeviceConnection.Lane.CONTROL, socket -> null);
             LanDeviceListener bulk = new LanDeviceListener(true, new InetSocketAddress("127.0.0.1", 0), noPeers, DeviceConnection.Lane.BULK, socket -> null);
             DeviceEndpointResolver.LocalReservation endpoints = DeviceEndpointResolver.reserveLan(true, "127.0.0.1", control, bulk, System.currentTimeMillis() + 60_000)) {
            try { endpoints.connectionHint(); fail("Unstarted candidate advertised as ready"); } catch (java.io.IOException expected) { }
            // Socket/lifecycle component only; no TLS handshake or identity is claimed here.
            endpoints.startCandidate(javax.net.ssl.SSLContext.getDefault(), bytes -> new byte[]{1});
            var hint = endpoints.connectionHint(); assertEquals("lan", hint.getString("kind"));
            assertTrue(endpoints.isCandidateListening()); assertFalse(endpoints.isCompletionListening());
            assertEquals(control.port(), hint.getInt("controlPort")); assertEquals(bulk.port(), hint.getInt("bulkPort"));
            try (java.net.ServerSocket collision = new java.net.ServerSocket()) {
                try { collision.bind(new InetSocketAddress("127.0.0.1", hint.getInt("completionPort"))); fail("Completion port was not retained"); }
                catch (java.net.BindException expected) { }
            }
            var uniqueInvitation = new org.json.JSONObject().put("inviterDeviceId", "pw1_" + "a".repeat(64)).put("connectionHint", hint);
            var resolved = DeviceEndpointResolver.resolveInvitation(uniqueInvitation, java.util.Set.of());
            assertEquals(hint.getInt("candidatePort"), resolved.candidateAddress().getPort());
            assertEquals(hint.getInt("completionPort"), resolved.completionAddress().getPort());
        }
    }
    @Test public void relayHintOnlyReferencesExactNativeConfiguredService() throws Exception {
        String peer = "pw1_" + "a".repeat(64);
        var invitation = new org.json.JSONObject().put("inviterDeviceId", peer).put("connectionHint", new org.json.JSONObject()
            .put("kind", "relay").put("serviceBase", "wss://relay.example.invalid").put("inviterDeviceId", peer));
        try { DeviceEndpointResolver.resolveInvitation(invitation, java.util.Set.of("wss://different.example.invalid")); fail("QR expanded relay trust"); }
        catch (SecurityException expected) { }
        var resolved = DeviceEndpointResolver.resolveInvitation(invitation, java.util.Set.of("wss://relay.example.invalid"));
        assertEquals("relay", resolved.kind); assertEquals("wss://relay.example.invalid", resolved.serviceBase);
        try { resolved.candidateAddress(); fail("Resolver invented a public route/token"); } catch (IllegalStateException expected) { }
    }

}
