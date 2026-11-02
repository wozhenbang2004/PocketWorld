package dev.pocketengine.features.devices.transport;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/** Raw TCP for the inner platform TLS engine; LAN is never plaintext application data. */
public final class SocketByteChannel implements DeviceByteChannel {
    private final Socket socket;
    private final okio.BufferedSink sink;
    private final byte[] readBuffer = new byte[32_768];
    public SocketByteChannel(Socket socket) throws IOException { this.socket = java.util.Objects.requireNonNull(socket); socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        sink = okio.Okio.buffer(okio.Okio.sink(socket)); sink.timeout().timeout(10, java.util.concurrent.TimeUnit.SECONDS); }
    @Override public byte[] receive(int timeoutMillis) throws IOException {
        socket.setSoTimeout(Math.max(1, timeoutMillis));
        try { int count = socket.getInputStream().read(readBuffer); if (count < 0) throw new EOFException("Device disconnected"); return Arrays.copyOf(readBuffer, count); }
        catch (SocketTimeoutException timeout) { return null; }
    }
    @Override public void send(byte[] encrypted) throws IOException {
        if (encrypted.length > MAX_PACKET) throw new IOException("TLS packet exceeds bound");
        sink.write(encrypted); sink.flush();
    }
    @Override public void close() throws IOException { socket.close(); Arrays.fill(readBuffer, (byte) 0); }
}
