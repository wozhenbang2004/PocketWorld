package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;

/** ISOLATED onboarding channel: one short-lived offer, not a device business connection.
 * A verifies the exact B invitation pin using standard server-auth TLS. B does not authenticate
 * an unknown client certificate: it passes UNTRUSTED candidate JSON to the native invitation /
 * signature validator. No AuthenticatedPeer, Permission, DeviceIngress or task API is exposed.
 * Only a later native-confirmed exact-pin MUTUAL TLS channel can complete pairing. */
public final class PairingCandidateTransport {
    public static final int MAX_CANDIDATE_BYTES = dev.pocketengine.features.devices.DeviceProtocol.MAX_PAIRING_BYTES, MAX_TLS_BYTES = 131_072;
    private static final Semaphore ACTIVE = new Semaphore(2);
    private static final ArrayDeque<Long> STARTS = new ArrayDeque<>();
    private static final java.util.concurrent.ScheduledThreadPoolExecutor TIMEOUTS;
    static {
        TIMEOUTS = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> { Thread thread = new Thread(runnable, "pairing-channel-deadline"); thread.setDaemon(true); return thread; });
        TIMEOUTS.setRemoveOnCancelPolicy(true);
    }
    private PairingCandidateTransport() { }
    public interface OfferFactory { byte[] create(DeviceIdentity.VerifiedPairingTarget verifiedInvitationTarget) throws Exception; }
    public interface CandidateHandler { byte[] inspect(byte[] untrustedCandidate) throws Exception; }
    /** No retry. The native offer factory is called only AFTER verifying the actual B TLS pin. */
    public static byte[] exchange(SSLContext exactPinnedServerContext, byte[] exactInvitationCertificateDer, DeviceByteChannel raw,
            long invitationExpiresAt, OfferFactory nativeOffer) throws Exception {
        try (DeviceByteChannel channel = new Metered(raw)) {
            long deadline = deadline(invitationExpiresAt);
            var engine = exactPinnedServerContext.createSSLEngine(); engine.setUseClientMode(true);
            try (PlatformTlsEngine tls = new PlatformTlsEngine(engine, channel, false)) {
                var session = tls.handshake(deadline);
                DeviceIdentity.VerifiedPairingTarget target = DeviceIdentity.verifiedPairingTarget(session, exactInvitationCertificateDer);
                byte[] offer = java.util.Objects.requireNonNull(nativeOffer).create(target); bounded(offer);
                if (System.currentTimeMillis() >= deadline) throw new IOException("Pairing offer expired before send");
                tls.write(ByteBuffer.wrap(DeviceFrameCodec.control(offer))); channel.flush();
                return receiveOne(tls, deadline);
            }
        }
    }
    /** Call only while the native owner has explicitly opened a still-live invitation.
     * ownServerKeysContext has B's native key/certificate, no trust-all verifier and NO business
     * client-auth admission. Unknown A identity is established later by the candidate validator. */
    public static void serve(boolean ownerEnabled, SSLContext ownServerKeysContext, DeviceByteChannel raw,
            long invitationExpiresAt, CandidateHandler nativeValidator) throws Exception {
        boolean entered = false;
        try (DeviceByteChannel channel = new Metered(raw)) {
            long deadline = deadline(invitationExpiresAt);
            if (!ownerEnabled || !enter()) throw new SecurityException("Pairing invitation disabled or onboarding rate limit reached");
            entered = true;
            var engine = ownServerKeysContext.createSSLEngine(); engine.setUseClientMode(false);
            try (PlatformTlsEngine tls = new PlatformTlsEngine(engine, channel, false)) {
                tls.handshake(deadline); // No peer certificate / AuthenticatedPeer is requested or created.
                byte[] offer = receiveOne(tls, deadline);
                byte[] receipt = java.util.Objects.requireNonNull(nativeValidator).inspect(offer); bounded(receipt);
                if (System.currentTimeMillis() >= deadline) throw new IOException("Pairing inspection expired");
                tls.write(ByteBuffer.wrap(DeviceFrameCodec.control(receipt))); channel.flush();
            }
        } finally { if (entered) leave(); }
    }
    static long deadline(long expiry) throws IOException {
        long now = System.currentTimeMillis();
        if (expiry <= now || expiry - now > 300_000) throw new IOException("Native pairing invitation is absent, expired or too long-lived");
        return Math.min(expiry, now + 15_000);
    }
    static synchronized boolean enter() {
        long now = System.nanoTime(), minute = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
        while (!STARTS.isEmpty() && now - STARTS.peekFirst() >= minute) STARTS.removeFirst();
        if (STARTS.size() >= 12 || !ACTIVE.tryAcquire()) return false;
        STARTS.addLast(now); return true;
    }
    static void bounded(byte[] body) throws IOException { if (body == null || body.length == 0 || body.length > MAX_CANDIDATE_BYTES) throw new IOException("Pairing candidate/receipt outside isolated bound"); }
    static void leave() { ACTIVE.release(); }
    static java.util.concurrent.ScheduledFuture<?> closeAt(AutoCloseable resource, long expiresAt) throws IOException {
        deadline(expiresAt);
        return TIMEOUTS.schedule(() -> { try { resource.close(); } catch (Exception ignored) { } }, Math.max(1, expiresAt - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    static DeviceByteChannel boundedChannel(DeviceByteChannel raw) { return new Metered(raw); }
    private static byte[] receiveOne(PlatformTlsEngine tls, long until) throws Exception { return receiveOne(tls, until, MAX_CANDIDATE_BYTES); }
    static byte[] receiveOne(PlatformTlsEngine tls, long until, int maximumBytes) throws Exception {
        byte[][] result = {null};
        var decoder = new DeviceFrameCodec.Decoder(maximumBytes, false);
        var sink = new DeviceFrameCodec.Listener() {
            public void control(byte[] body) throws IOException { if (result[0] != null) throw new IOException("Only one pairing candidate/receipt allowed"); bounded(body); result[0] = body; }
            public void chunk(java.util.UUID transfer, long offset, byte[] raw) throws IOException { throw new IOException("Binary data is forbidden on onboarding channel"); }
        };
        while (result[0] == null) {
            if (System.currentTimeMillis() >= until) throw new IOException("Pairing channel timed out");
            tls.poll((int) Math.min(100, Math.max(1, until - System.currentTimeMillis())), bytes -> decoder.accept(bytes, sink));
        }
        if (decoder.incomplete()) throw new IOException("Extra incomplete frame on isolated pairing channel");
        return result[0];
    }
    private static final class Metered implements DeviceByteChannel {
        private final DeviceByteChannel original; private long transferred;
        private final java.util.concurrent.ScheduledFuture<?> timeout;
        Metered(DeviceByteChannel original) {
            this.original = java.util.Objects.requireNonNull(original);
            timeout = TIMEOUTS.schedule(() -> { try { original.close(); } catch (IOException ignored) { } }, 15, java.util.concurrent.TimeUnit.SECONDS);
        }
        private void count(byte[] raw) throws IOException { if (raw != null && (raw.length > MAX_PACKET || (transferred += raw.length) > MAX_TLS_BYTES)) throw new IOException("Pairing TLS byte budget exceeded"); }
        public byte[] receive(int timeout) throws IOException, InterruptedException { byte[] raw = original.receive(timeout); count(raw); return raw; }
        public void send(byte[] raw) throws IOException, InterruptedException { count(raw); original.send(raw); }
        public void flush() throws IOException, InterruptedException { original.flush(); }
        public void close() throws IOException { timeout.cancel(false); original.close(); }
    }
}
