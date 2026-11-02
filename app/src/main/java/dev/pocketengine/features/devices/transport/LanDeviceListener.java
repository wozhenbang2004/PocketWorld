package dev.pocketengine.features.devices.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;

/** Explicitly enabled, bounded LAN ingress. Discovery/address possession never creates trust. */
public final class LanDeviceListener implements AutoCloseable {
    public interface Accepted { DeviceConnection.Listener listener(Socket socket); }
    /** Native current TrustStore pins only. Empty/revoked trust must throw, never trust-all.
     * Called separately for each accepted socket, not a permanent startup snapshot. */
    public interface CurrentContext { SSLContext current() throws Exception; }
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final Semaphore capacity = new Semaphore(8);
    private final java.util.Set<DeviceConnection> connections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final Thread acceptor;
    public LanDeviceListener(boolean ownerEnabled, InetSocketAddress address, SSLContext pinnedMutualTlsContext, DeviceConnection.Lane lane, Accepted accepted) throws IOException {
        this(ownerEnabled, address, fixedContext(pinnedMutualTlsContext), lane, accepted);
    }
    /** Newly paired/revoked pins affect new handshakes without rebinding the port or
     * closing accepted Goals. Existing channels still recheck each native grant/epoch. */
    public LanDeviceListener(boolean ownerEnabled, InetSocketAddress address, CurrentContext currentPinnedMutualTlsContext, DeviceConnection.Lane lane, Accepted accepted) throws IOException {
        if (!ownerEnabled) throw new SecurityException("Device access is disabled; listener was not started");
        java.util.Objects.requireNonNull(currentPinnedMutualTlsContext); java.util.Objects.requireNonNull(lane); java.util.Objects.requireNonNull(accepted);
        server = new ServerSocket(); server.setReuseAddress(false); server.bind(address, 8); server.setSoTimeout(500);
        acceptor = new Thread(() -> {
            while (!closed) {
                Socket socket = null;
                try {
                    socket = server.accept();
                    if (!capacity.tryAcquire()) { socket.close(); continue; }
                    DeviceConnection connection;
                    try { connection = new DeviceConnection(java.util.Objects.requireNonNull(currentPinnedMutualTlsContext.current()), false, lane, new SocketByteChannel(socket), accepted.listener(socket)); }
                    catch (Exception rejected) { capacity.release(); socket.close(); continue; }
                    connections.add(connection);
                    workers.execute(() -> { try { connection.run(); } finally { connections.remove(connection); capacity.release(); } });
                } catch (SocketTimeoutException timeout) { }
                catch (Exception unavailable) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
            }
        }, "device-lan-listener"); acceptor.setDaemon(true); acceptor.start();
    }
    private static CurrentContext fixedContext(SSLContext context) {
        java.util.Objects.requireNonNull(context); return () -> context;
    }
    public java.net.InetAddress localAddress() { return server.getInetAddress(); }
    public int port() { return server.getLocalPort(); }
    public boolean isListening() { return !closed && !server.isClosed() && server.isBound(); }
    @Override public void close() throws IOException {
        closed = true; server.close(); for (DeviceConnection connection : connections) try { connection.close(); } catch (IOException ignored) { }
        workers.shutdownNow(); acceptor.interrupt();
    }
}
