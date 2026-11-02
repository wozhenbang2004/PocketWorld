package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.PairingCoordinator;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;

/** A native invitation owns either its real LAN sockets or its explicitly imported WSS slot. */
public interface DevicePairingEndpoint extends AutoCloseable {
    AutoCloseable startCandidate(SSLContext ownServerKeys, PairingCandidateTransport.CandidateHandler validator) throws Exception;
    AutoCloseable startCompletion(SSLContext exactConfirmedContext, PairingCoordinator.PendingPairing pending, PairingCompletionTransport.Server owner) throws Exception;
    JSONObject connectionHint() throws Exception;
    boolean isCandidateListening();
    boolean isCompletionListening();
    void close() throws Exception;
}
