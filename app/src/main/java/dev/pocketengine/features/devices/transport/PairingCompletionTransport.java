package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;

/** Second isolated onboarding phase: exact-pin MUTUAL TLS plus native reviewed PendingPairing.
 * A real peer is given only to the dedicated native finalize/accept callback. It is not routed
 * through DeviceIngress and JSON cannot supply the native PairGrant or a Permission. */
public final class PairingCompletionTransport {
    public static final int MAX_FINALIZE_BYTES = 4096;
    private PairingCompletionTransport() { }
    public interface Client {
        byte[] request(DeviceIdentity.AuthenticatedPeer exactInvitationTarget) throws Exception;
        void accept(DeviceIdentity.AuthenticatedPeer exactInvitationTarget, byte[] completionReceipt) throws Exception;
    }
    public interface Server { byte[] complete(DeviceIdentity.AuthenticatedPeer exactConfirmedCandidate, byte[] finalizeRequest) throws Exception; }
    public static byte[] exchange(SSLContext pinnedMutualContext, byte[] exactInvitationCertificateDer, DeviceByteChannel raw,
            long invitationExpiresAt, Client nativeOfferOwner) throws Exception {
        return exchange(pinnedMutualContext, exactInvitationCertificateDer, raw, invitationExpiresAt, nativeOfferOwner, "commit");
    }
    /** Native RecoveryOffer only. Reads the original immutable commit, never a new pairing. */
    public static byte[] exchangeRecovery(SSLContext pinnedMutualContext, byte[] exactKnownCertificateDer, DeviceByteChannel raw,
            long nativeReadExpiresAt, Client nativeRecoveryOfferOwner) throws Exception {
        long remaining = nativeReadExpiresAt - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 30_000) { raw.close(); throw new SecurityException("Native short-lived original recovery read required"); }
        return exchange(pinnedMutualContext, exactKnownCertificateDer, raw, nativeReadExpiresAt, nativeRecoveryOfferOwner, "recover");
    }
    private static byte[] exchange(SSLContext pinnedMutualContext, byte[] exactInvitationCertificateDer, DeviceByteChannel raw,
            long invitationExpiresAt, Client nativeOfferOwner, String purpose) throws Exception {
        try (DeviceByteChannel channel = PairingCandidateTransport.boundedChannel(raw)) {
            long deadline = PairingCandidateTransport.deadline(invitationExpiresAt);
            var engine = pinnedMutualContext.createSSLEngine(); engine.setUseClientMode(true);
            try (PlatformTlsEngine tls = new PlatformTlsEngine(engine, channel)) {
                DeviceIdentity.AuthenticatedPeer target = DeviceIdentity.authenticatedPeer(tls.handshake(deadline));
                if (!java.security.MessageDigest.isEqual(target.certificateDer(), exactInvitationCertificateDer)) throw new SecurityException("Pairing completion target changed");
                byte[] request = java.util.Objects.requireNonNull(nativeOfferOwner).request(target);
                if (request == null || request.length == 0 || request.length > MAX_FINALIZE_BYTES) throw new IOException("Finalize request outside isolated bound");
                requirePurpose(request, purpose);
                tls.write(ByteBuffer.wrap(DeviceFrameCodec.control(request))); channel.flush();
                byte[] receipt = PairingCandidateTransport.receiveOne(tls, deadline, PairingCandidateTransport.MAX_CANDIDATE_BYTES);
                nativeOfferOwner.accept(target, receipt); return receipt;
            }
        }
    }
    public static void serve(SSLContext exactConfirmedMutualContext, PairingCoordinator.PendingPairing nativePending,
            DeviceByteChannel raw, Server nativeReviewedGrantOwner) throws Exception {
        if (nativePending == null) { raw.close(); throw new SecurityException("Native PendingPairing required"); }
        serve(exactConfirmedMutualContext, raw, nativePending.expectedDeviceId(), nativePending.certificateFingerprint(), nativePending.expiresAt(),
            nativePending::checkCurrent, nativeReviewedGrantOwner, "commit");
    }
    /** A distinct opaque SQL-issued read ticket, NOT a relaxed/renewed PendingPairing. */
    public static void serveRecovery(SSLContext exactConfirmedMutualContext, PairingCoordinator.CommittedPairingRecovery nativeRecovery,
            DeviceByteChannel raw, Server nativeOriginalCommittedOwner) throws Exception {
        try {
            java.util.Objects.requireNonNull(nativeRecovery).checkCurrent(System.currentTimeMillis());
            long remaining = nativeRecovery.expiresAt() - System.currentTimeMillis();
            if (remaining <= 0 || remaining > 30_000) throw new SecurityException("Native committed recovery read exceeds its short lifetime");
        } catch (Exception rejected) { try { raw.close(); } catch (IOException ignored) { } throw rejected; }
        serve(exactConfirmedMutualContext, raw, nativeRecovery.expectedDeviceId(), nativeRecovery.certificateFingerprint(), nativeRecovery.expiresAt(),
            nativeRecovery::checkCurrent, nativeOriginalCommittedOwner, "recover");
    }
    private interface Current { void require(long now) throws Exception; }
    private static void requirePurpose(byte[] raw, String purpose) {
        if (!purpose.equals(PairingCoordinator.parsePairingFinalize(raw).optString("purpose"))) throw new SecurityException("Completion request belongs to another native commit/recovery lane");
    }
    private static void serve(SSLContext exactConfirmedMutualContext, DeviceByteChannel raw, String expectedDevice, String expectedFingerprint,
            long expiresAt, Current current, Server nativeOwner, String purpose) throws Exception {
        boolean entered = false;
        try (DeviceByteChannel channel = PairingCandidateTransport.boundedChannel(raw)) {
            current.require(System.currentTimeMillis());
            long deadline = PairingCandidateTransport.deadline(expiresAt);
            if (!PairingCandidateTransport.enter()) throw new IOException("Onboarding completion rate limited"); entered = true;
            var engine = exactConfirmedMutualContext.createSSLEngine(); engine.setUseClientMode(false);
            try (PlatformTlsEngine tls = new PlatformTlsEngine(engine, channel)) {
                DeviceIdentity.AuthenticatedPeer peer = DeviceIdentity.authenticatedPeer(tls.handshake(deadline));
                if (!expectedDevice.equals(peer.deviceId()) || !expectedFingerprint.equals(peer.certificateFingerprint())) throw new SecurityException("Completion client differs from the native confirmed original peer");
                byte[] request = PairingCandidateTransport.receiveOne(tls, deadline, MAX_FINALIZE_BYTES);
                requirePurpose(request, purpose); current.require(System.currentTimeMillis());
                byte[] receipt = java.util.Objects.requireNonNull(nativeOwner).complete(peer, request); PairingCandidateTransport.bounded(receipt);
                current.require(System.currentTimeMillis());
                if (System.currentTimeMillis() >= deadline) throw new IOException("Native pairing completion response expired");
                tls.write(ByteBuffer.wrap(DeviceFrameCodec.control(receipt))); channel.flush();
            }
        } finally { if (entered) PairingCandidateTransport.leave(); }
    }
}
