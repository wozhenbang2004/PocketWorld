package dev.pocketengine.features.devices.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLContext;

/** Short-lived separate LAN onboarding listener, never the business device dispatcher. */
public final class PairingCandidateListener implements AutoCloseable {
    private final ServerSocket server;
    private final java.util.concurrent.ThreadPoolExecutor workers = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.SynchronousQueue<>(), runnable -> { Thread thread = new Thread(runnable, "pairing-candidate"); thread.setDaemon(true); return thread; }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;
    public PairingCandidateListener(boolean ownerEnabled, long invitationExpiresAt, InetSocketAddress address, SSLContext ownServerKeys,
            PairingCandidateTransport.CandidateHandler nativeValidator) throws IOException {
        this(ownerEnabled, invitationExpiresAt, address, null, ownServerKeys, nativeValidator);
    }
    PairingCandidateListener(boolean ownerEnabled, long invitationExpiresAt, ServerSocket reserved, SSLContext ownServerKeys,
            PairingCandidateTransport.CandidateHandler nativeValidator) throws IOException {
        this(ownerEnabled, invitationExpiresAt, null, reserved, ownServerKeys, nativeValidator);
    }
    private PairingCandidateListener(boolean ownerEnabled, long invitationExpiresAt, InetSocketAddress address, ServerSocket reserved, SSLContext ownServerKeys,
            PairingCandidateTransport.CandidateHandler nativeValidator) throws IOException {
        if (!ownerEnabled || invitationExpiresAt <= System.currentTimeMillis() || invitationExpiresAt - System.currentTimeMillis() > 300_000) throw new SecurityException("A native active owner invitation is required");
        java.util.Objects.requireNonNull(ownServerKeys); java.util.Objects.requireNonNull(nativeValidator);
        if (reserved != null && (reserved.isClosed() || !reserved.isBound())) throw new IOException("Reserved candidate endpoint unavailable");
        server = reserved == null ? new ServerSocket() : reserved;
        if (reserved == null) { server.setReuseAddress(false); server.bind(address, 2); }
        server.setSoTimeout(250);
        acceptor = new Thread(() -> {
            try {
                while (!closed && System.currentTimeMillis() < invitationExpiresAt) {
                    Socket socket;
                    try { socket = server.accept(); } catch (SocketTimeoutException timeout) { continue; }
                    active.add(socket);
                    try {
                        workers.execute(() -> {
                            try { PairingCandidateTransport.serve(!closed, ownServerKeys, new SocketByteChannel(socket), invitationExpiresAt, nativeValidator); }
                            catch (Exception rejected) { /* No candidate token/certificate/body diagnostics. */ }
                            finally { active.remove(socket); try { socket.close(); } catch (IOException ignored) { } }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException full) { active.remove(socket); socket.close(); }
                }
            } catch (IOException unavailable) { }
            finally { try { close(); } catch (IOException ignored) { } }
        }, "pairing-invitation-listener"); acceptor.setDaemon(true); acceptor.start();
    }
    public int port() { return server.getLocalPort(); }
    public boolean isListening() { return !closed && !server.isClosed() && server.isBound(); }
    @Override public void close() throws IOException { closed = true; server.close(); for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { } workers.shutdownNow(); acceptor.interrupt(); }
}
