package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual localhost TLS WebSockets, real RelayByteChannel/platform inner mTLS and reconnect.
 * Synthetic in-memory certs/slots; tiny byte-relay fixture is NOT deployed product service,
 * TaskStore permission acceptance, real Android/Windows or different-NAT/WAN acceptance. */
public final class DeviceRelayTlsJourneyTest {
    private static final String ROUTE = "synthetic_fixture_route_" + "x".repeat(22), TA = "a".repeat(48), TB = "b".repeat(48);
    record Material(KeyPair keys, X509Certificate cert) { }
    static Material material(String label) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); KeyPair keys = generator.generateKeyPair(); long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=" + label); var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(120, new SecureRandom()).add(BigInteger.ONE), new Date(now - 60_000), new Date(now + 3_600_000), name, keys.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        return new Material(keys, new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate()))));
    }
    private static TrustManagerFactory trust(Material peer) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null); store.setCertificateEntry("exact-synthetic-peer", peer.cert);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(store); return managers;
    }
    static SSLContext context(Material local, Material peer) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12"); keys.load(null); keys.setKeyEntry("synthetic-only", local.keys.getPrivate(), "test-only".toCharArray(), new java.security.cert.Certificate[]{local.cert});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, "test-only".toCharArray());
        SSLContext context = SSLContext.getInstance("TLSv1.3"); context.init(managers.getKeyManagers(), trust(peer).getTrustManagers(), new SecureRandom()); return context;
    }
    static OkHttpClient platformClient(Material exactOuterServer) throws Exception {
        X509TrustManager manager = (X509TrustManager) trust(exactOuterServer).getTrustManagers()[0]; SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, new TrustManager[]{manager}, new SecureRandom());
        return new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(), manager).build(); // Normal hostname verifier remains enabled.
    }
    static DeviceRelayLease lease(Bridge relay, String local, String inviter, String side, OkHttpClient tls) throws Exception {
        JSONObject config = new JSONObject().put("format", DeviceRelayLease.FORMAT).put("serviceBase", relay.service()).put("inviterDeviceId", inviter).put("routeId", ROUTE).put("side", side).put("routeToken", side.equals("a") ? TA : TB).put("expiresAt", System.currentTimeMillis() + 60_000);
        return DeviceRelayLease.review(config.toString(), local, "synthetic-instance", System.currentTimeMillis(), (endpoint, bearer) -> new RelayByteChannel(tls, endpoint, bearer));
    }
    private static final class Received implements DeviceConnection.Listener {
        final String expected; final AtomicInteger online = new AtomicInteger(); final BlockingQueue<byte[]> controls = new LinkedBlockingQueue<>(), chunks = new LinkedBlockingQueue<>();
        Received(String expected) { this.expected = expected; }
        public void onConnection(DeviceConnection connection, DeviceIdentity.AuthenticatedPeer actual) { assertEquals(expected, actual.deviceId()); assertEquals(DeviceTransportStatus.Path.RELAY_WSS, connection.path()); }
        public void onOnline(DeviceIdentity.AuthenticatedPeer actual) { assertEquals(expected, actual.deviceId()); online.incrementAndGet(); }
        public void onControl(DeviceIdentity.AuthenticatedPeer actual, byte[] bytes) { assertEquals(expected, actual.deviceId()); controls.add(bytes.clone()); }
        public void onChunk(DeviceIdentity.AuthenticatedPeer actual, UUID transfer, long offset, byte[] bytes) { assertEquals(expected, actual.deviceId()); chunks.add(bytes.clone()); }
        public void onClosed(DeviceConnection.State state, boolean unknown) { }
    }
    private static void until(BooleanSupplier ready) throws Exception { long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10); assertTrue("native transport condition did not become true", ready.getAsBoolean()); }

    @Test public void remoteCloseDrainsAlreadyReceivedCiphertextButExplicitLocalCloseDiscardsIt() throws Exception {
        Material outer = material("Synthetic relay close fixture"); OkHttpClient tls = platformClient(outer);
        try (Bridge relay = new Bridge(outer); RelayByteChannel channel = new RelayByteChannel(tls, relay.service() + "/v1/tunnel/" + ROUTE + "/a/control", TA)) {
            until(() -> relay.upgrades.get() == 1);
            // Deliberate WebSocket-listener stimuli, not a claimed device TLS/app receipt.
            channel.onMessage(null, "ready"); channel.onMessage(null, okio.ByteString.of(new byte[]{1, 2, 3})); channel.onClosed(null, 1000, "synthetic EOF");
            assertArrayEquals(new byte[]{1, 2, 3}, channel.receive(1)); assertThrows(EOFException.class, () -> channel.receive(1));
        } finally { tls.dispatcher().executorService().shutdownNow(); tls.connectionPool().evictAll(); }
        OkHttpClient localTls = platformClient(outer);
        try (Bridge relay = new Bridge(outer); RelayByteChannel channel = new RelayByteChannel(localTls, relay.service() + "/v1/tunnel/" + ROUTE + "/a/control", TA)) {
            until(() -> relay.upgrades.get() == 1); channel.onMessage(null, "ready"); channel.onMessage(null, okio.ByteString.of(new byte[]{4})); channel.close();
            assertThrows(EOFException.class, () -> channel.receive(1));
        } finally { localTls.dispatcher().executorService().shutdownNow(); localTls.connectionPool().evictAll(); }
    }

    @Test public void realWssInnerMtlsSeparateBulkAndReconnectOnlyQueriesTheSameOriginalId() throws Exception {
        Material a = material("Synthetic A"), b = material("Synthetic B"), outer = material("Synthetic relay"); String aid = DeviceIdentity.id(a.cert), bid = DeviceIdentity.id(b.cert);
        OkHttpClient outerTls = platformClient(outer); ExecutorService workers = Executors.newFixedThreadPool(4);
        try (Bridge relay = new Bridge(outer); DeviceRelayLease la = lease(relay, aid, bid, "a", outerTls); DeviceRelayLease lb = lease(relay, bid, bid, "b", outerTls)) {
            la.bindPeer(bid); lb.bindPeer(aid); Received atA = new Received(bid), atB = new Received(aid), bulkA = new Received(bid), bulkB = new Received(aid);
            SSLContext ac = context(a, b), bc = context(b, a);
            try (ReconnectingDeviceClient ca = new ReconnectingDeviceClient(true, la.expiresAt, () -> new DeviceConnection(ac, true, DeviceConnection.Lane.CONTROL, la.open("control"), atA));
                 ReconnectingDeviceClient cb = new ReconnectingDeviceClient(true, lb.expiresAt, () -> new DeviceConnection(bc, false, DeviceConnection.Lane.CONTROL, lb.open("control"), atB))) {
                workers.submit(ca); workers.submit(cb); until(() -> atA.online.get() == 1 && atB.online.get() == 1);
                try (DeviceConnection ba = new DeviceConnection(ac, true, DeviceConnection.Lane.BULK, la.open("bulk"), bulkA);
                     DeviceConnection bb = new DeviceConnection(bc, false, DeviceConnection.Lane.BULK, lb.open("bulk"), bulkB)) {
                    workers.submit(ba); workers.submit(bb); until(() -> bulkA.online.get() == 1 && bulkB.online.get() == 1);
                    byte[] actual = new byte[50_000]; new Random(9).nextBytes(actual); relay.holdBulk = true;
                    assertTrue(ba.sendChunk(UUID.randomUUID(), 0, actual, peer -> { if (!bid.equals(peer.deviceId())) throw new SecurityException("Exact native bulk peer changed"); }));
                    assertTrue(relay.bulkHeld.await(2, TimeUnit.SECONDS));
                    byte[] query = "{\"method\":\"operation.get\",\"operationId\":\"original-op-fixture\",\"read\":1}".getBytes(StandardCharsets.UTF_8);
                    assertTrue(ca.active().sendControl(query)); assertArrayEquals(query, atB.controls.poll(2, TimeUnit.SECONDS));
                    relay.releaseBulk.countDown(); assertArrayEquals(actual, bulkB.chunks.poll(3, TimeUnit.SECONDS));
                    relay.closeLane("control"); until(() -> atA.online.get() >= 2 && atB.online.get() >= 2);
                    assertNull("Reconnect cannot invent/replay an application request", atB.controls.poll(200, TimeUnit.MILLISECONDS));
                    byte[] originalAgain = "{\"method\":\"operation.get\",\"operationId\":\"original-op-fixture\",\"read\":2}".getBytes(StandardCharsets.UTF_8);
                    assertTrue(ca.active().sendControl(originalAgain)); assertArrayEquals(originalAgain, atB.controls.poll(2, TimeUnit.SECONDS)); assertNull(atB.controls.poll(200, TimeUnit.MILLISECONDS));
                    assertTrue(relay.lanesSeen.containsAll(Set.of("control", "bulk")));
                }
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS)); outerTls.dispatcher().executorService().shutdownNow(); outerTls.connectionPool().evictAll(); }
    }
    @Test public void isolatedCandidateUsesActualInviterPinBeforeCallingTheNativeOfferAndCloseStopsItsWindow() throws Exception {
        Material a = material("Synthetic joiner"), b = material("Synthetic inviter"), outer = material("Synthetic relay"); String aid = DeviceIdentity.id(a.cert), bid = DeviceIdentity.id(b.cert); OkHttpClient outerTls = platformClient(outer);
        AtomicInteger nativeOffer = new AtomicInteger(), inspected = new AtomicInteger();
        try (Bridge relay = new Bridge(outer); DeviceRelayLease la = lease(relay, aid, bid, "a", outerTls); DeviceRelayLease lb = lease(relay, bid, bid, "b", outerTls);
             RelayPairingEndpoint invitation = new RelayPairingEndpoint(lb, System.currentTimeMillis() + 10_000, () -> {})) {
            byte[] offer = "synthetic-untrusted-candidate".getBytes(StandardCharsets.UTF_8), receipt = "synthetic-native-review-pending".getBytes(StandardCharsets.UTF_8);
            invitation.startCandidate(context(b, a), bytes -> { assertArrayEquals(offer, bytes); inspected.incrementAndGet(); return receipt; });
            try (DeviceByteChannel bytes = la.open("pairing")) {
                assertArrayEquals(receipt, PairingCandidateTransport.exchange(context(a, b), b.cert.getEncoded(), bytes, System.currentTimeMillis() + 9000, verified -> {
                    assertEquals(bid, verified.deviceId()); nativeOffer.incrementAndGet(); return offer;
                }));
            }
            assertEquals(1, nativeOffer.get()); assertEquals(1, inspected.get()); assertTrue(relay.lanesSeen.contains("pairing")); invitation.close(); assertFalse(invitation.isCandidateListening());
        } finally { outerTls.dispatcher().executorService().shutdownNow(); outerTls.connectionPool().evictAll(); }
    }
    @Test public void publicChannelDoesNotTrustASyntheticOuterCertificateAndWrongInnerPinNeverAuthenticates() throws Exception {
        Material a = material("Synthetic A"), b = material("Synthetic B"), wrong = material("Not B"), outer = material("Synthetic relay");
        try (Bridge relay = new Bridge(outer); RelayByteChannel rejected = new RelayByteChannel(relay.service() + "/v1/tunnel/" + ROUTE + "/a/control", TA)) {
            assertThrows(IOException.class, () -> rejected.awaitReady(3000)); assertEquals(0, relay.upgrades.get());
        }
        String aid = DeviceIdentity.id(a.cert), bid = DeviceIdentity.id(b.cert); OkHttpClient tls = platformClient(outer); ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Bridge relay = new Bridge(outer); DeviceRelayLease la = lease(relay, aid, bid, "a", tls); DeviceRelayLease lb = lease(relay, bid, bid, "b", tls);
             DeviceConnection ca = new DeviceConnection(context(a, wrong), true, DeviceConnection.Lane.CONTROL, la.open("control"), new Received(bid));
             DeviceConnection cb = new DeviceConnection(context(b, a), false, DeviceConnection.Lane.CONTROL, lb.open("control"), new Received(aid))) {
            Future<?> fa = workers.submit(ca), fb = workers.submit(cb); fa.get(5, TimeUnit.SECONDS); fb.get(5, TimeUnit.SECONDS);
            assertFalse(ca.wasAuthenticated()); assertFalse(cb.wasAuthenticated()); assertThrows(IOException.class, () -> ca.sendControl(new byte[]{1}));
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS)); tls.dispatcher().executorService().shutdownNow(); tls.connectionPool().evictAll(); }
    }

    /** Test-only minimal TLS WebSocket byte rendezvous; no task/model/protocol acceptance. */
    static final class Bridge implements AutoCloseable {
        final SSLServerSocket server; final ExecutorService io; final Thread acceptor; final ConcurrentMap<String, Pair> rooms = new ConcurrentHashMap<>();
        final Set<String> lanesSeen = ConcurrentHashMap.newKeySet(); final AtomicInteger upgrades = new AtomicInteger();
        final Set<SSLSocket> accepted = ConcurrentHashMap.newKeySet();
        final java.util.Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        volatile boolean closed, holdBulk; final CountDownLatch bulkHeld = new CountDownLatch(1), releaseBulk = new CountDownLatch(1);
        Bridge(Material outer) throws Exception {
            server = (SSLServerSocket) context(outer, outer).getServerSocketFactory().createServerSocket(0, 8, InetAddress.getLoopbackAddress());
            io = Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "synthetic-wss-relay-slot"); t.setDaemon(true); return t; });
            acceptor = new Thread(() -> { while (!closed) try { SocketAcceptor(); } catch (Exception unavailable) { if (closed) break; } }, "synthetic-wss-relay-accept"); acceptor.setDaemon(true); acceptor.start();
        }
        String service() { return "wss://localhost:" + server.getLocalPort(); }
        private void SocketAcceptor() throws Exception { SSLSocket socket = (SSLSocket) server.accept(); socket.setSoTimeout(8000); accepted.add(socket); io.submit(() -> handle(socket)); }
        private void handle(SSLSocket socket) {
            Pair room = null; String lane = null;
            try (socket) {
                socket.startHandshake(); InputStream input = socket.getInputStream(); ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(); int n;
                while (headerBytes.size() < 8192 && (n = input.read()) >= 0) { headerBytes.write(n); byte[] bytes = headerBytes.toByteArray(); int l = bytes.length; if (l >= 4 && bytes[l-4] == 13 && bytes[l-3] == 10 && bytes[l-2] == 13 && bytes[l-1] == 10) break; }
                String[] lines = headerBytes.toString(StandardCharsets.ISO_8859_1).split("\r\n"); String[] request = lines[0].split(" "); String[] path = request[1].split("/");
                if (path.length != 6 || !path[1].equals("v1") || !path[2].equals("tunnel") || !path[3].equals(ROUTE) || !Set.of("a", "b").contains(path[4]) || !Set.of("control", "bulk", "pairing", "pairing-complete").contains(path[5])) throw new IOException("Synthetic route mismatch");
                Map<String, String> headers = new HashMap<>(); for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0) headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon+1).trim()); }
                if (!("Bearer " + (path[4].equals("a") ? TA : TB)).equals(headers.get("authorization")) || !RelayByteChannel.SUBPROTOCOL.equals(headers.get("sec-websocket-protocol"))) throw new IOException("Synthetic slot not authorized");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\nSec-WebSocket-Protocol: " + RelayByteChannel.SUBPROTOCOL + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); output.flush(); upgrades.incrementAndGet();
                lane = path[5]; lanesSeen.add(lane); room = rooms.computeIfAbsent(lane, ignored -> new Pair()); Slot slot = new Slot(socket, output); room.add(path[4], slot); if (!room.ready.await(6, TimeUnit.SECONDS)) throw new IOException("Synthetic peer absent");
                while (!closed && !room.closed) {
                    DataInputStream data = new DataInputStream(input); int first = data.readUnsignedByte(), second = data.readUnsignedByte(); if ((first & 128) == 0 || (second & 128) == 0) throw new IOException("Synthetic fixture expects bounded masked client frames");
                    int opcode = first & 15; long size = second & 127; if (size == 126) size = data.readUnsignedShort(); else if (size == 127) size = data.readLong(); if (size < 0 || size > 65536) throw new IOException("Frame outside byte bound");
                    byte[] mask = data.readNBytes(4), bytes = data.readNBytes((int) size); if (mask.length != 4 || bytes.length != size) throw new EOFException(); for (int i = 0; i < bytes.length; i++) bytes[i] ^= mask[i % 4];
                    if (opcode == 8) break; if (opcode == 9) { slot.write(10, bytes); continue; } if (opcode == 10) continue; if (opcode != 2) throw new IOException("No page/application text at byte relay");
                    if (lane.equals("bulk") && holdBulk) { bulkHeld.countDown(); releaseBulk.await(6, TimeUnit.SECONDS); }
                    room.other(path[4]).write(2, bytes);
                }
            } catch (Exception disconnected) { failures.add(disconnected); /* Synthetic test diagnostics only; never bearer/header/body. */ }
            finally { accepted.remove(socket); if (room != null) { room.close(); rooms.remove(lane, room); } }
        }
        void closeLane(String lane) { Pair room = rooms.remove(lane); if (room != null) room.close(); }
        public void close() throws Exception { closed = true; releaseBulk.countDown(); server.close(); for (SSLSocket socket : accepted) try { socket.close(); } catch (IOException ignored) { } for (Pair room : rooms.values()) room.close(); rooms.clear(); io.shutdownNow(); acceptor.interrupt(); acceptor.join(1000); assertTrue(io.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    private static final class Slot {
        final SSLSocket socket; final OutputStream out; Slot(SSLSocket socket, OutputStream out) { this.socket = socket; this.out = out; }
        synchronized void write(int opcode, byte[] bytes) throws IOException { out.write(128 | opcode); if (bytes.length < 126) out.write(bytes.length); else if (bytes.length <= 65535) { out.write(126); out.write(bytes.length >>> 8); out.write(bytes.length); } else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int)((long)bytes.length >>> (i*8))); } out.write(bytes); out.flush(); }
    }
    private static final class Pair {
        final Map<String, Slot> slots = new HashMap<>(); final CountDownLatch ready = new CountDownLatch(1); volatile boolean closed;
        synchronized void add(String side, Slot slot) throws Exception { if (closed || slots.putIfAbsent(side, slot) != null) throw new IOException("Occupied synthetic slot"); if (slots.size() == 2) { for (Slot s : slots.values()) s.write(1, "ready".getBytes(StandardCharsets.UTF_8)); ready.countDown(); } }
        synchronized Slot other(String side) throws IOException { Slot other = slots.get(side.equals("a") ? "b" : "a"); if (closed || other == null) throw new IOException("Synthetic peer unavailable"); return other; }
        synchronized void close() { closed = true; ready.countDown(); for (Slot slot : slots.values()) try { slot.socket.close(); } catch (IOException ignored) { } }
    }
}
