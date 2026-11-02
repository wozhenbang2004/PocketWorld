package dev.pocketengine.features.devices.transport;

import java.io.Closeable;
import java.io.IOException;

/** Ordered, bounded opaque TLS records. No retry, business receipt or authority. */
public interface DeviceByteChannel extends Closeable {
    int MAX_PACKET = 65_536;
    /** null means timeout, not EOF; EOF must throw. */
    byte[] receive(int timeoutMillis) throws IOException, InterruptedException;
    /** Writes once or fails with an unknown delivery outcome. Never replays. */
    void send(byte[] encrypted) throws IOException, InterruptedException;
    /** Transport flush only; it is not a receiver/business ACK. */
    default void flush() throws IOException, InterruptedException { }
}
