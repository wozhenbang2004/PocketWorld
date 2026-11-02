package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Platform TLS contexts from NATIVE paired certificate pins, never message/address trust.
 * A successful TLS handshake establishes a peer identity only. Current grant/epoch and
 * exact per-request permission checks remain mandatory, including on already-open sessions. */
public final class DeviceTlsContexts {
    private DeviceTlsContexts() { }
    public static SSLContext forPeer(DeviceIdentity.LocalIdentity local, byte[] pairedCertificateDer) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(DeviceIdentity.keyManagers(local), new TrustManager[]{DeviceIdentity.pinnedTrustManager(pairedCertificateDer.clone())}, null);
        return context;
    }
    /** Isolated onboarding CLIENT: exact invitation B pin, no A client identity admission. */
    public static SSLContext pairingCandidateClient(byte[] exactInvitationCertificateDer) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{DeviceIdentity.pinnedTrustManager(exactInvitationCertificateDer.clone())}, null); return context;
    }
    /** Isolated onboarding SERVER keys only: unknown clients are NOT authenticated peers.
     * Use only with PairingCandidateTransport, never DeviceConnection / business ingress. */
    public static SSLContext pairingCandidateServer(DeviceIdentity.LocalIdentity local) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS"); context.init(DeviceIdentity.keyManagers(local), null, null); return context;
    }
    /** Incoming LAN accepts ONLY a bounded snapshot of already-paired native pins. */
    public static SSLContext forPairedPeers(DeviceIdentity.LocalIdentity local, List<byte[]> pairedNativeCertificates) throws Exception {
        if (pairedNativeCertificates == null || pairedNativeCertificates.isEmpty() || pairedNativeCertificates.size() > 64) throw new SecurityException("One to 64 paired certificates required");
        List<X509TrustManager> validators = new ArrayList<>();
        for (byte[] certificate : pairedNativeCertificates) validators.add(DeviceIdentity.pinnedTrustManager(certificate.clone()));
        X509TrustManager pins = new X509TrustManager() {
            private X509TrustManager selected(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length != 1) throw new CertificateException("One paired leaf required");
                byte[] actual = chain[0].getEncoded();
                for (X509TrustManager validator : validators) if (java.security.MessageDigest.isEqual(actual, validator.getAcceptedIssuers()[0].getEncoded())) return validator;
                throw new CertificateException("Unknown device certificate; pairing is required");
            }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { selected(chain).checkClientTrusted(chain, auth); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { selected(chain).checkServerTrusted(chain, auth); }
            public X509Certificate[] getAcceptedIssuers() { return validators.stream().map(v -> v.getAcceptedIssuers()[0]).toArray(X509Certificate[]::new); }
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(DeviceIdentity.keyManagers(local), new TrustManager[]{pins}, null); return context;
    }
}
