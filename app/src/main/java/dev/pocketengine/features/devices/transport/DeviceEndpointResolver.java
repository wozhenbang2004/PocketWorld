package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;

/** Native address hints ONLY, never device trust or execution authority.
 * No DNS discovery, public service/account provisioning, VPN changes or hidden fallback.
 * The invitation certificate must still be verified before a token/offer is transmitted. */
public final class DeviceEndpointResolver {
    private DeviceEndpointResolver() { }
    /** Real usable local interface literals. No address or multiple choices requires an
     * explicit native owner selection; do not guess a cloud endpoint or a VPN route. */
    public static List<String> localLanAddresses() throws IOException {
        var result = new java.util.TreeSet<String>();
        var interfaces = NetworkInterface.getNetworkInterfaces(); int interfacesSeen = 0, addressesSeen = 0;
        if (interfaces == null) return List.of();
        while (interfaces.hasMoreElements()) {
            var network = interfaces.nextElement();
            if (++interfacesSeen > 64) throw new IOException("Local interface enumeration exceeds bound");
            if (!network.isUp() || network.isLoopback()) continue;
            var addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (++addressesSeen > 128) throw new IOException("Local address enumeration exceeds bound");
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress() || address.isLinkLocalAddress() || address.getHostAddress().contains("%")) continue;
                result.add(address.getHostAddress());
            }
        }
        return java.util.Collections.unmodifiableList(new ArrayList<>(result));
    }
    private static InetAddress literal(String host) throws IOException {
        if (host == null || host.length() > 64 || host.contains("%") || !(host.matches("[0-9.]+") || host.contains(":") && host.matches("[0-9A-Fa-f:]+"))) throw new IOException("Explicit IP literal required; no hostname or scoped address");
        InetAddress address = InetAddress.getByName(host); // Syntax above cannot perform DNS.
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress() || host.equals("255.255.255.255")) throw new IOException("Address is not a usable device endpoint");
        return address;
    }
    public static Resolved resolveInvitation(JSONObject uniqueInvitation, Set<String> nativeTrustedRelayServiceBases) throws Exception {
        JSONObject hint = uniqueInvitation.getJSONObject("connectionHint"); DeviceProtocol.validateConnectionHint(hint);
        String inviter = DeviceProtocol.deviceId(uniqueInvitation.getString("inviterDeviceId"), "inviterDeviceId");
        if ("lan".equals(hint.getString("kind"))) {
            InetAddress address = literal(hint.getString("host"));
            return new Resolved(address, hint.getInt("candidatePort"), hint.getInt("completionPort"), hint.getInt("controlPort"), hint.getInt("bulkPort"), null);
        }
        String serviceBase = hint.getString("serviceBase"); URI uri = new URI(serviceBase);
        if (!inviter.equals(hint.getString("inviterDeviceId")) || nativeTrustedRelayServiceBases == null || !nativeTrustedRelayServiceBases.contains(serviceBase)
            || !("https".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new SecurityException("Relay hint is not this native configured trusted service");
        return new Resolved(null, 0, 0, 0, 0, serviceBase);
    }
    public static final class Resolved {
        public final String kind, host, serviceBase;
        public final int candidatePort, completionPort, controlPort, bulkPort;
        private final InetAddress address;
        private Resolved(InetAddress address, int candidate, int completion, int control, int bulk, String serviceBase) {
            this.address = address; this.host = address == null ? null : address.getHostAddress(); this.serviceBase = serviceBase;
            kind = address == null ? "relay" : "lan"; candidatePort = candidate; completionPort = completion; controlPort = control; bulkPort = bulk;
        }
        private InetSocketAddress direct(int port) { if (address == null) throw new IllegalStateException("Relay route lease must be resolved by the native configured authority"); return new InetSocketAddress(address, port); }
        public InetSocketAddress candidateAddress() { return direct(candidatePort); }
        public InetSocketAddress completionAddress() { return direct(completionPort); }
        public InetSocketAddress controlAddress() { return direct(controlPort); }
        public InetSocketAddress bulkAddress() { return direct(bulkPort); }
    }
    public static LocalReservation reserveLan(boolean ownerEnabled, String selectedActualLocalAddress, LanDeviceListener actualControl, LanDeviceListener actualBulk, long invitationExpiresAt) throws Exception {
        if (!ownerEnabled || actualControl == null || actualBulk == null || !actualControl.isListening() || !actualBulk.isListening()) throw new SecurityException("Enabled actual control and bulk listeners required");
        InetAddress address = literal(selectedActualLocalAddress);
        if (NetworkInterface.getByInetAddress(address) == null) throw new IOException("Selected address is not currently local");
        for (LanDeviceListener listener : new LanDeviceListener[]{actualControl, actualBulk}) {
            InetAddress bound = listener.localAddress();
            if (!bound.isAnyLocalAddress() && !bound.equals(address)) throw new IOException("Business listener does not serve the selected local address");
        }
        return new LocalReservation(address, actualControl, actualBulk, invitationExpiresAt);
    }
    public static final class LocalReservation implements AutoCloseable {
        private final InetAddress address;
        private final LanDeviceListener control, bulk;
        private final ServerSocket candidateSocket, completionSocket;
        private final long expiresAt;
        private final java.util.concurrent.ScheduledFuture<?> expiry;
        private PairingCandidateListener candidate;
        private PairingCompletionListener completion;
        private boolean closed;
        private static ServerSocket reserve(InetAddress address) throws IOException { var server = new ServerSocket(); try { server.setReuseAddress(false); server.bind(new InetSocketAddress(address, 0), 2); return server; } catch (IOException failed) { server.close(); throw failed; } }
        private LocalReservation(InetAddress address, LanDeviceListener control, LanDeviceListener bulk, long expiresAt) throws Exception {
            PairingCandidateTransport.deadline(expiresAt); this.address = address; this.control = control; this.bulk = bulk; this.expiresAt = expiresAt;
            candidateSocket = reserve(address);
            try { completionSocket = reserve(address); } catch (Exception failed) { candidateSocket.close(); throw failed; }
            expiry = PairingCandidateTransport.closeAt(this, expiresAt); // Reuse the sole transport deadline scheduler.
        }
        private void current() throws IOException { if (closed || System.currentTimeMillis() >= expiresAt || !control.isListening() || !bulk.isListening()) throw new IOException("Native invitation endpoints expired or disabled"); }
        public synchronized PairingCandidateListener startCandidate(SSLContext ownServerKeys, PairingCandidateTransport.CandidateHandler nativeValidator) throws Exception {
            current(); if (candidate != null) throw new IllegalStateException("Candidate listener already started");
            return candidate = new PairingCandidateListener(true, expiresAt, candidateSocket, ownServerKeys, nativeValidator);
        }
        public synchronized PairingCompletionListener startCompletion(SSLContext exactConfirmedContext, PairingCoordinator.PendingPairing nativePending, PairingCompletionTransport.Server nativeReviewedGrantOwner) throws Exception {
            current(); if (completion != null) throw new IllegalStateException("Completion listener already started");
            if (nativePending.expiresAt() != expiresAt) throw new SecurityException("Native confirmation belongs to another invitation expiry");
            return completion = new PairingCompletionListener(exactConfirmedContext, nativePending, completionSocket, nativeReviewedGrantOwner);
        }
        public synchronized JSONObject connectionHint() throws Exception {
            current(); if (candidate == null || !candidate.isListening()) throw new IOException("Actual candidate listener not ready; do not show invitation");
            JSONObject hint = new JSONObject().put("kind", "lan").put("host", address.getHostAddress()).put("candidatePort", candidateSocket.getLocalPort())
                .put("completionPort", completionSocket.getLocalPort()).put("controlPort", control.port()).put("bulkPort", bulk.port());
            DeviceProtocol.validateConnectionHint(hint); return hint;
        }
        public synchronized boolean isCandidateListening() { return !closed && candidate != null && candidate.isListening(); }
        public synchronized boolean isCompletionListening() { return !closed && completion != null && completion.isListening(); }
        @Override public synchronized void close() throws IOException {
            if (closed) return; closed = true; if (expiry != null) expiry.cancel(false);
            if (candidate != null) try { candidate.close(); } catch (IOException ignored) { }
            if (completion != null) try { completion.close(); } catch (IOException ignored) { }
            try { candidateSocket.close(); } finally { completionSocket.close(); }
        }
    }
}
