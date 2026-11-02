package dev.pocketengine.features.devices;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real NativeDeviceWorkspaceHost review lifetime; lease producer is an explicit owner fixture. */
public final class NativeRelayOwnerWindowTest {
    static final class Review implements NativeDeviceWorkspaceHost.RelayLeaseAction {
        int commits, closes;
        public JSONObject view() throws Exception { return new JSONObject().put("serviceBase", "wss://relay.example.invalid").put("memoryOnly", true).put("devicePermissionGranted", false).put("expiresAt", System.currentTimeMillis() + 60_000); }
        public void confirm() { commits++; }
        public void close() { closes++; }
    }
    @SuppressWarnings("unchecked") static <T> T fixture(Class<T> type, java.lang.reflect.InvocationHandler handler) { return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler); }
    static NativeDeviceWorkspaceHost host(List<Review> reviews) {
        return host(reviews, new ArrayList<>());
    }
    static NativeDeviceWorkspaceHost host(List<Review> reviews, List<String> connections) {
        var pairing = fixture(NativeDeviceWorkspaceHost.PairingOwner.class, (proxy, method, args) -> {
            if (method.getName().equals("reviewRelayLease")) { Review review = new Review(); reviews.add(review); return review; }
            if (method.getName().equals("reviewRelayConnection")) {
                String peer = (String) args[0], service = (String) args[1]; connections.add("review:" + peer);
                return new NativeDeviceWorkspaceHost.ConnectionReviewAction() {
                    public JSONObject view() throws Exception { return new JSONObject().put("deviceId", peer).put("certificateFingerprint", "a".repeat(64)).put("kind", "relay").put("serviceBase", service); }
                    public void confirm() { connections.add("confirmed:" + peer); }
                };
            }
            if (method.getName().equals("relayLeases")) return new JSONArray();
            if (method.getReturnType() == NativeDeviceWorkspaceHost.Availability.class) return NativeDeviceWorkspaceHost.Availability.ready();
            if (method.getReturnType() == List.class) return List.of(); return null;
        });
        return new NativeDeviceWorkspaceHost(() -> new JSONObject().put("format", NativeDeviceWorkspaceHost.FORMAT).put("capturedAt", System.currentTimeMillis()).put("local", new JSONObject()).put("peers", new JSONArray()).put("pendingPairings", new JSONArray()),
                fixture(NativeDeviceWorkspaceHost.ReceiverOwner.class, (p, m, a) -> NativeDeviceWorkspaceHost.Availability.ready()), pairing,
                fixture(NativeDeviceWorkspaceHost.GoalOwner.class, (p, m, a) -> null), fixture(NativeDeviceWorkspaceHost.TaskControl.class, (p, m, a) -> null));
    }
    @Test public void closingReplacingOrSubmittingOwnerReviewNeverReusesItsEphemeralSecretHandle() throws Exception {
        List<Review> reviews = new ArrayList<>();
        try (var host = host(reviews); var window = host.openOwnerWindow()) {
            var first = window.reviewRelayLease("private fixture input"); var second = window.reviewRelayLease("replacement fixture input");
            assertEquals(1, reviews.get(0).closes); assertThrows(SecurityException.class, () -> window.confirmRelayLease(first));
            window.confirmRelayLease(second); assertEquals(1, reviews.get(1).commits); assertEquals(1, reviews.get(1).closes);
            assertThrows(SecurityException.class, () -> window.confirmRelayLease(second));
            window.reviewRelayLease("discarded fixture input"); window.discardReview(); assertEquals(1, reviews.get(2).closes); assertEquals(0, reviews.get(2).commits);
            window.reviewRelayLease("closed fixture input"); window.close(); assertEquals(1, reviews.get(3).closes);
            assertThrows(IllegalStateException.class, () -> window.reviewRelayLease("closed")); assertEquals(4, reviews.size());
        }
    }
    @Test public void staleOtherOwnerWindowCannotConfirmLeaseAndSnapshotsDoNotIncludeRawInput() throws Exception {
        List<Review> reviews = new ArrayList<>(); try (var host = host(reviews); var first = host.openOwnerWindow(); var second = host.openOwnerWindow()) {
            var nativeReview = first.reviewRelayLease("private-bearer-never-in-diagnostics"); assertThrows(SecurityException.class, () -> second.confirmRelayLease(nativeReview));
            assertFalse(first.snapshot().toString().contains("private-bearer")); assertFalse(nativeReview.view().has("routeToken")); first.confirmRelayLease(nativeReview); assertEquals(1, reviews.get(0).commits);
        }
    }
    @Test public void coldPairedTransportSelectionStillNeedsTheOriginalCurrentOwnerReviewAndDoesNotRePair() throws Exception {
        List<Review> leases = new ArrayList<>(); List<String> connections = new ArrayList<>(); String peer = "pw1_" + "b".repeat(64);
        try (var host = host(leases, connections); var window = host.openOwnerWindow(); var other = host.openOwnerWindow()) {
            var stale = window.reviewRelayConnection(peer, "wss://relay.example.invalid"); window.discardReview(); assertThrows(SecurityException.class, () -> window.confirmConnectionUpdate(stale));
            var current = window.reviewRelayConnection(peer, "wss://relay.example.invalid"); assertThrows(SecurityException.class, () -> other.confirmConnectionUpdate(current));
            window.confirmConnectionUpdate(current); assertThrows(SecurityException.class, () -> window.confirmConnectionUpdate(current));
            assertEquals(List.of("review:" + peer, "review:" + peer, "confirmed:" + peer), connections); assertTrue("No new pairing/lease producer invoked", leases.isEmpty());
        }
    }
}
