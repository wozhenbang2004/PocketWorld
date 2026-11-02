package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;

/** Read-only UI projection of LIVE transport objects, never a grant or task outcome.
 * A stored pin/profile must not be used as evidence that a connection is online.
 * Bootstrap candidate channels are deliberately absent from this business-device projection. */
public final class DeviceTransportStatus {
    public enum Path { DIRECT_TLS, RELAY_WSS, UNKNOWN }
    public record Snapshot(String deviceId, DeviceConnection.State state, Path path, boolean localListenerReady,
            boolean reconnectClientRunning, boolean unconfirmedTransportWrite, long observedAt) {
        public Snapshot {
            DeviceProtocol.deviceId(deviceId, "deviceId"); java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(path);
            if (observedAt < 0) throw new IllegalArgumentException("Invalid native observation time");
        }
        /** Online is channel reachability only: not current permission, GUI readiness or acceptance. */
        public boolean channelOnline() { return state == DeviceConnection.State.ONLINE || state == DeviceConnection.State.DEGRADED; }
        public boolean authorizesExecution() { return false; }
    }
    public interface Provider { Snapshot current(String nativePairedDeviceId); }
    private DeviceTransportStatus() { }
    /** Call from the native host with the CURRENT objects; this method never opens a connection. */
    public static Snapshot current(String nativeDeviceId, ReconnectingDeviceClient client, DeviceConnection directConnection,
            LanDeviceListener localListener, boolean unconfirmedTransportWrite) {
        DeviceConnection connection = directConnection != null ? directConnection : client == null ? null : client.active();
        DeviceConnection.State state = connection != null ? connection.state() : client != null ? client.transportState() : DeviceConnection.State.OFFLINE;
        Path route = connection == null ? Path.UNKNOWN : connection.path();
        return new Snapshot(nativeDeviceId, state, route, localListener != null && localListener.isListening(),
            client != null && client.isRunning(), unconfirmedTransportWrite, System.currentTimeMillis());
    }
}
