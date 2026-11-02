package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceCapabilityPort;
import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.json.JSONObject;

/** Explicit temporary localhost TEST identity/current fixture; not Android/SQL audience or
 * model HTTP input proof. Real product control+bulk SSLEngine/frame/fetch/cache are exercised. */
public final class ArtifactFetchInteropProcess {
    public static void main(String[] args) throws Exception {
        String mode = args[7]; byte[] target = Files.readAllBytes(Path.of(args[4]));
        JSONObject template = new JSONObject(Files.readString(Path.of(args[5]))), descriptor = new JSONObject(Files.readString(Path.of(args[6])));
        KeyStore keys = KeyStore.getInstance("PKCS12"); char[] password = "localhost-test-only".toCharArray();
        try (FileInputStream in = new FileInputStream(args[3])) { keys.load(in, password); }
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(manager.getKeyManagers(), new TrustManager[]{DeviceIdentity.pinnedTrustManager(target)}, null);
        Path root = Files.createTempDirectory("pw-fetch-only-");
        var cache = new DeviceArtifactTransfer(root.toFile()); var session = new DeviceArtifactSession(cache);
        var nativePeer = new AtomicReference<DeviceIdentity.AuthenticatedPeer>(); var fetchRef = new AtomicReference<DeviceArtifactFetch>();
        var control = new AtomicReference<DeviceConnection>(); var futures = new ConcurrentHashMap<String, CompletableFuture<DeviceCapabilityPort.Reply>>();
        var online = new CountDownLatch(2); var live = new AtomicBoolean(true); var calls = new AtomicInteger(); var checks = new AtomicInteger();
        var actualBulkChunks = new AtomicInteger();
        var oldAttempt = new AtomicReference<Thread>(); var cleanupArmed = new AtomicBoolean(mode.equals("same-transfer-cleanup"));
        var finalCurrentReached = new CountDownLatch(1); var releaseFinalCurrent = new CountDownLatch(1);
        DeviceArtifactTransfer.Current current = () -> {
            checks.incrementAndGet(); if (!live.get()) throw new SecurityException("Native fixture receive approval withdrawn");
            if (cleanupArmed.get() && Thread.currentThread() == oldAttempt.get() && Files.exists(root.resolve(descriptor.getString("transferId") + ".artifact"))
                    && StackWalker.getInstance().walk(stack -> stack.noneMatch(frame -> frame.getClassName().startsWith(DeviceArtifactTransfer.class.getName()) || frame.getClassName().equals(DeviceArtifactSession.class.getName())))
                    && cleanupArmed.compareAndSet(true, false)) {
                finalCurrentReached.countDown(); if (!releaseFinalCurrent.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture did not release final native check");
            }
        };
        var fetch = new DeviceArtifactFetch(cache, session, (peer, exact, offset, length, deadline) -> {
            int call = calls.incrementAndGet();
            if (mode.equals("resume") && call == 2) throw new IOException("Explicit fixture read unavailable before second request; product must not retry it");
            if (mode.equals("revoke") && call == 2) live.set(false);
            JSONObject raw = new JSONObject(template.toString()).put("requestId", UUID.randomUUID().toString()).put("deadline", deadline)
                .put("arguments", new JSONObject().put("artifactId", exact.artifactId).put("contentDigest", exact.sha256).put("offset", offset).put("length", length));
            DeviceTaskEnvelope query = DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(raw));
            var future = new CompletableFuture<DeviceCapabilityPort.Reply>(); futures.put(query.requestId(), future);
            try {
                if (!control.get().sendControl(DeviceProtocol.canonicalBytes(query.json()))) throw new IOException("Fixture control backpressure");
                var reply = future.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                return new DeviceArtifactFetch.ReadReply(query, reply);
            } finally { futures.remove(query.requestId()); }
        });
        fetchRef.set(fetch);
        DeviceConnection.Listener controlListener = new DeviceConnection.Listener() {
            public void onConnection(DeviceConnection conn, DeviceIdentity.AuthenticatedPeer peer) { control.set(conn); nativePeer.set(peer); }
            public void onOnline(DeviceIdentity.AuthenticatedPeer peer) { online.countDown(); }
            public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] raw) throws Exception {
                JSONObject response = DeviceProtocol.validateMessage(new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8)));
                var future = futures.get(response.optString("requestId")); if (future == null) throw new SecurityException("Reply was not bound to this exact pending request");
                future.complete(new DeviceCapabilityPort.Reply(peer, response));
            }
            public void onChunk(DeviceIdentity.AuthenticatedPeer peer, UUID id, long offset, byte[] raw) { throw new AssertionError("Bulk on control"); }
            public void onClosed(DeviceConnection.State state, boolean diagnosticOnly) {
                fetchRef.get().disconnected(nativePeer.get()); for (var future : futures.values()) future.completeExceptionally(new IOException("Original control channel closed"));
            }
        };
        DeviceConnection.Listener bulkListener = new DeviceConnection.Listener() {
            DeviceIdentity.AuthenticatedPeer actual;
            public void onConnection(DeviceConnection conn, DeviceIdentity.AuthenticatedPeer peer) { actual = peer; }
            public void onOnline(DeviceIdentity.AuthenticatedPeer peer) { online.countDown(); }
            public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] raw) { throw new AssertionError("Control on bulk"); }
            public void onChunk(DeviceIdentity.AuthenticatedPeer peer, UUID id, long offset, byte[] raw) throws Exception { actualBulkChunks.incrementAndGet(); fetch.onChunk(peer, id, offset, raw); }
            public void onClosed(DeviceConnection.State state, boolean diagnosticOnly) { fetch.disconnected(actual); }
        };
        var controlConn = new DeviceConnection(context, true, DeviceConnection.Lane.CONTROL, new SocketByteChannel(new Socket(args[0], Integer.parseInt(args[1]))), controlListener);
        var bulkConn = new DeviceConnection(context, true, DeviceConnection.Lane.BULK, new SocketByteChannel(new Socket(args[0], Integer.parseInt(args[2]))), bulkListener);
        Thread first = new Thread(controlConn, "fixture-control-io"), second = new Thread(bulkConn, "fixture-bulk-io");
        boolean verified = false; long size = descriptor.getLong("size");
        try {
            first.start(); second.start(); if (!online.await(10, TimeUnit.SECONDS)) throw new AssertionError("Missing actual two-lane mTLS");
            DeviceArtifactFetch.Verified result = null;
            try {
                if (mode.equals("same-transfer-cleanup")) {
                    var original = new CompletableFuture<DeviceArtifactFetch.Verified>(); var competing = new CompletableFuture<DeviceArtifactFetch.Verified>();
                    Thread old = new Thread(() -> { try { original.complete(fetch.fetch(nativePeer.get(), descriptor, System.currentTimeMillis() + 15_000, current)); } catch (Throwable error) { original.completeExceptionally(error); } }, "fixture-old-fetch");
                    Thread next = new Thread(() -> { try { competing.complete(fetch.fetch(nativePeer.get(), descriptor, System.currentTimeMillis() + 15_000, current)); } catch (Throwable error) { competing.completeExceptionally(error); } }, "fixture-competing-fetch");
                    oldAttempt.set(old); old.start();
                    try {
                        if (!finalCurrentReached.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old fetch did not reach final verified-byte check");
                        synchronized (session) {
                            releaseFinalCurrent.countDown(); long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                            while (System.nanoTime() < stop && !(old.getState() == Thread.State.BLOCKED && java.util.Arrays.stream(old.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("releaseIncoming")))) Thread.sleep(1);
                            if (old.getState() != Thread.State.BLOCKED) throw new AssertionError("Old finally was not held at exact Session cleanup");
                            next.start();
                            try { competing.get(500, TimeUnit.MILLISECONDS); throw new AssertionError("A new same-transfer attempt completed while old Session cleanup was held"); }
                            catch (java.util.concurrent.ExecutionException refusal) {
                                if (!(refusal.getCause() instanceof IOException) || !refusal.getCause().getMessage().contains("already active")) throw refusal;
                            } catch (java.util.concurrent.TimeoutException admitted) {
                                throw new AssertionError("New same-transfer attempt was admitted before old Session binding release", admitted);
                            }
                        }
                        original.get(5, TimeUnit.SECONDS);
                        result = fetch.fetch(nativePeer.get(), descriptor, System.currentTimeMillis() + 15_000, current);
                        if (calls.get() != 4) throw new AssertionError("Verified same-transfer cache reuse issued a new network read");
                    } finally { releaseFinalCurrent.countDown(); old.join(3000); if (next.getState() != Thread.State.NEW) next.join(3000); }
                } else result = fetch.fetch(nativePeer.get(), descriptor, System.currentTimeMillis() + 15_000, current);
            }
            catch (Exception expected) {
                if (mode.equals("resume")) {
                    var exact = DeviceArtifactTransfer.fromWireDescriptor(descriptor);
                    if (cache.begin(exact, current) != DeviceArtifactTransfer.CHUNK_BYTES || calls.get() != 2) throw new AssertionError("Original checkpoint was not retained without automatic retry", expected);
                    result = fetch.fetch(nativePeer.get(), descriptor, System.currentTimeMillis() + 15_000, current);
                } else if (!java.util.Set.of("bad-ready", "wrong-request", "corrupt", "revoke", "wrong-offset", "bulk-gate-revoke").contains(mode)) throw expected;
            }
            boolean positive = java.util.Set.of("normal", "resume", "image", "same-transfer-cleanup").contains(mode);
            if ((result != null) != positive) throw new AssertionError("Native verified result truth differs from expected test outcome");
            if (result != null) {
                byte[] raw;
                if (mode.equals("image")) raw = result.readImageBytes(6 * 1024 * 1024);
                else try (var in = result.openVerified(); var out = new java.io.ByteArrayOutputStream()) { byte[] part = new byte[262144]; int n; while ((n = in.read(part)) != -1) out.write(part, 0, n); raw = out.toByteArray(); }
                if (raw.length != size || !DeviceProtocol.sha256(raw).equals(descriptor.getString("contentDigest"))) throw new AssertionError("Actual fetched bytes differ");
                java.util.Arrays.fill(raw, (byte) 0); verified = true;
                if (mode.equals("image")) {
                    JSONObject otherRaw = result.reference().json().put("transferId", UUID.randomUUID().toString()).put("artifactId", "native-other-transfer")
                        .put("kind", "file").put("mimeType", "application/octet-stream").put("name", "keep-native-file.bin").put("bytes", 4).put("sha256", DeviceProtocol.sha256(new byte[]{1,2,3,4}));
                    for (String key : new String[]{"observationRef", "resourceBinding", "expiresAt"}) otherRaw.remove(key);
                    var other = new DeviceArtifactTransfer.Reference(otherRaw); cache.freeze(other, out -> out.write(new byte[]{1,2,3,4}), current);
                    live.set(false); // Deleting this exact private image must NOT require read authority.
                    result.discardTemporaryImage();
                    if (Files.exists(root.resolve(result.reference().transferId + ".artifact")) || Files.exists(root.resolve(result.reference().transferId + ".json"))) throw new AssertionError("Revoked screenshot bytes were not actually purged");
                    if (!Files.exists(root.resolve(other.transferId + ".artifact")) || !Files.exists(root.resolve(other.transferId + ".json"))) throw new AssertionError("Screenshot cleanup removed another native file transfer");
                    try { cache.openVerified(other, current).close(); throw new AssertionError("Cleanup restored revoked data reads"); } catch (SecurityException stillRevoked) { }
                    try { result.openVerified().close(); throw new AssertionError("Consumed native screenshot cache remained readable"); } catch (IOException | SecurityException removed) { }
                } else {
                    try { result.discardTemporaryImage(); throw new AssertionError("Normal native file was silently discarded"); } catch (SecurityException protectedFile) { }
                }
            }
            if (!positive && mode.equals("revoke") && live.get()) throw new AssertionError("Native fixture approval never withdrawn");
            if (mode.equals("bulk-gate-revoke") && actualBulkChunks.get() != 0) throw new AssertionError("A queued original read sent new chunk bytes after owner revocation");
            System.out.println(new JSONObject().put("mode", mode).put("provider", context.getProvider().getName()).put("bytesVerified", verified)
                .put("readCalls", calls.get()).put("nativeCurrentChecks", checks.get()).put("modelInputDelivered", false).put("androidProviderVerified", false)
                .put("actualBulkChunks", actualBulkChunks.get())
                .put("concurrentSameTransferCleanupVerified", mode.equals("same-transfer-cleanup")).put("revokedImageCleanupProtectsOtherTransfer", mode.equals("image")));
        } finally {
            fetch.close(); controlConn.close(); bulkConn.close(); first.join(3000); second.join(3000);
            if (first.isAlive() || second.isAlive()) throw new AssertionError("Fixture IO worker did not terminate");
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}
