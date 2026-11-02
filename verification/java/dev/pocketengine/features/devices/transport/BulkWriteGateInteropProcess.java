package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.json.JSONObject;

/** Actual localhost TLS enqueue/revoke/write barrier. Native Current is a TEST fixture,
 * not original Android SQL or model-input proof. Legacy red adaptation stays evidence-only. */
public final class BulkWriteGateInteropProcess {
    private static final boolean GATED_API = true;
    private interface NativeFixtureRead { void require(DeviceIdentity.AuthenticatedPeer peer) throws Exception; }
    public static void main(String[] args) throws Exception {
        byte[] expected = Files.readAllBytes(Path.of(args[3])); KeyStore keys = KeyStore.getInstance("PKCS12"); char[] password = "localhost-test-only".toCharArray();
        try (FileInputStream input = new FileInputStream(args[2])) { keys.load(input, password); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), new TrustManager[]{DeviceIdentity.pinnedTrustManager(expected)}, null);
        var gateEntered = new CountDownLatch(1); var wireEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var queued = new CountDownLatch(1); var closed = new CountDownLatch(1); var allowed = new AtomicBoolean(true); var armed = new AtomicBoolean(); var paused = new AtomicBoolean();
        var proof = new AtomicReference<DeviceIdentity.AuthenticatedPeer>(); var connection = new AtomicReference<DeviceConnection>(); var error = new AtomicReference<Throwable>();
        var socket = new SocketByteChannel(new Socket(args[0], Integer.parseInt(args[1])));
        DeviceByteChannel barrier = new DeviceByteChannel() {
            public byte[] receive(int timeout) throws IOException, InterruptedException { return socket.receive(timeout); }
            public void send(byte[] raw) throws IOException {
                if (armed.get() && paused.compareAndSet(false, true)) {
                    wireEntered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture release missing"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                }
                socket.send(raw);
            }
            public void close() throws IOException { socket.close(); }
        };
        NativeFixtureRead nativeRead = actual -> {
            if (actual == null || !DeviceProtocol.sha256(expected).equals(actual.certificateFingerprint())) throw new SecurityException("Exact native TEST read target changed");
            gateEntered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture write gate release missing");
            if (!allowed.get()) throw new SecurityException("Native TEST read withdrawn after enqueue, before TLS write");
        };
        var listener = new DeviceConnection.Listener() {
            public void onConnection(DeviceConnection value, DeviceIdentity.AuthenticatedPeer actual) { connection.set(value); proof.set(actual); }
            public void onOnline(DeviceIdentity.AuthenticatedPeer actual) throws Exception {
                armed.set(true); byte[] payload = new byte[1024]; java.util.Arrays.fill(payload, (byte) 73);
                try {
                    if (!connection.get().sendChunk(UUID.randomUUID(), 0, payload, nativeRead::require)) throw new IOException("First TEST chunk was not queued");
                    if (!connection.get().sendChunk(UUID.randomUUID(), 0, payload, nativeRead::require)) throw new IOException("Second TEST chunk was not queued");
                } catch (Exception failed) { error.set(failed); throw failed; } finally { queued.countDown(); }
            }
            public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] raw) { throw new AssertionError("Control on fixture bulk"); }
            public void onChunk(DeviceIdentity.AuthenticatedPeer peer, UUID id, long offset, byte[] raw) { throw new AssertionError("Unexpected reverse fixture payload"); }
            public void onClosed(DeviceConnection.State state, boolean diagnosticOnly) { closed.countDown(); }
        };
        var active = new DeviceConnection(context, true, DeviceConnection.Lane.BULK, barrier, listener); var worker = new Thread(active, "fixture-native-bulk-writer");
        try {
            worker.start(); if (!queued.await(10, TimeUnit.SECONDS) || error.get() != null) throw new AssertionError("TEST chunks were not both queued", error.get());
            if (!(GATED_API ? gateEntered : wireEntered).await(5, TimeUnit.SECONDS)) throw new AssertionError("Exact enqueue/write barrier not reached");
            allowed.set(false); release.countDown();
            if (!closed.await(7, TimeUnit.SECONDS)) throw new AssertionError("Rejected native bulk writer did not close");
            if (GATED_API && wireEntered.getCount() == 0) throw new AssertionError("New TLS application bytes reached the wire after revoked gate");
            if (!GATED_API && wireEntered.getCount() != 0) throw new AssertionError("Legacy vulnerability was not exercised");
            System.out.println(new JSONObject().put("gatedApi", GATED_API).put("actualPeer", proof.get().deviceId()).put("provider", context.getProvider().getName())
                .put("queuedChunks", 2).put("revokedBeforeWrite", true).put("postRevocationApplicationWriteStarted", wireEntered.getCount() == 0).put("modelInputDelivered", false));
        } finally { release.countDown(); active.close(); worker.join(3000); if (worker.isAlive()) throw new AssertionError("Fixture bulk IO did not terminate"); }
    }
}
