package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Binds bulk TLS bytes to an EXACT manifest approved on an authenticated control channel.
 * Current callbacks must re-check the original native PairingCoordinator Permission and exact
 * receive approval on every call. This class cannot mint a permission or accept a task. */
public final class DeviceArtifactSession {
    private final DeviceArtifactTransfer cache;
    private final Map<UUID, Incoming> incoming = new HashMap<>();
    private record Incoming(DeviceArtifactTransfer.Reference reference, String deviceId, String certificateFingerprint, DeviceArtifactTransfer.Current current) { }
    public DeviceArtifactSession(DeviceArtifactTransfer cache) { this.cache = java.util.Objects.requireNonNull(cache); }
    public synchronized long prepareIncoming(DeviceIdentity.AuthenticatedPeer controlPeer, DeviceArtifactTransfer.Reference exact, DeviceArtifactTransfer.Current current) throws Exception {
        java.util.Objects.requireNonNull(controlPeer); java.util.Objects.requireNonNull(current).require();
        if (!controlPeer.deviceId().equals(exact.sourceDeviceId)) throw new SecurityException("Artifact executor differs from the authenticated manifest peer");
        UUID transfer = UUID.fromString(exact.transferId); Incoming previous = incoming.get(transfer);
        if (previous == null && incoming.size() >= DeviceArtifactTransfer.MAX_TRANSFERS) throw new SecurityException("Too many active artifact manifests");
        if (previous != null && (!previous.reference.contentIdentity().equals(exact.contentIdentity()) || !previous.deviceId.equals(controlPeer.deviceId()) || !previous.certificateFingerprint.equals(controlPeer.certificateFingerprint()))) throw new SecurityException("Transfer already belongs to another manifest/peer");
        long offset = cache.begin(exact, current);
        incoming.put(transfer, new Incoming(exact, controlPeer.deviceId(), controlPeer.certificateFingerprint(), current));
        return offset;
    }
    public synchronized long accept(DeviceIdentity.AuthenticatedPeer bulkPeer, UUID transfer, long offset, byte[] checksumVerifiedFrameBytes) throws Exception {
        Incoming bound = require(bulkPeer, transfer); bound.current.require();
        return cache.accept(bound.reference, offset, checksumVerifiedFrameBytes, DeviceProtocol.sha256(checksumVerifiedFrameBytes), bound.current);
    }
    public synchronized JSONObject finish(DeviceIdentity.AuthenticatedPeer peer, UUID transfer) throws Exception {
        Incoming bound = require(peer, transfer); return cache.finish(bound.reference, bound.current);
    }
    /** Closing a network session discards only ephemeral bindings, not the staged byte checkpoint. */
    public synchronized void disconnected(DeviceIdentity.AuthenticatedPeer peer) {
        incoming.entrySet().removeIf(entry -> entry.getValue().deviceId.equals(peer.deviceId()) && entry.getValue().certificateFingerprint.equals(peer.certificateFingerprint()));
    }
    /** Native lifecycle cleanup only: remove one ephemeral binding after fetch/abort while
     * retaining the original fixed checkpoint. This does not revoke/cancel a task or grant. */
    public synchronized void releaseIncoming(DeviceIdentity.AuthenticatedPeer peer, UUID transfer) {
        Incoming bound = incoming.get(transfer);
        if (bound != null && peer != null && bound.deviceId.equals(peer.deviceId()) && bound.certificateFingerprint.equals(peer.certificateFingerprint())) incoming.remove(transfer);
    }
    private Incoming require(DeviceIdentity.AuthenticatedPeer peer, UUID transfer) {
        Incoming bound = incoming.get(transfer);
        if (peer == null || bound == null || !bound.deviceId.equals(peer.deviceId()) || !bound.certificateFingerprint.equals(peer.certificateFingerprint())) throw new SecurityException("Bulk bytes have no exact currently authorized control-channel manifest");
        return bound;
    }
}
