package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.PairingCoordinator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLContext;

/** A distinct short-lived mTLS completion port. Never reuses the untrusted candidate listener. */
public final class PairingCompletionListener implements AutoCloseable {
    private final ServerSocket server;
    private final java.util.concurrent.ThreadPoolExecutor workers = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.SynchronousQueue<>(), runnable -> { Thread thread = new Thread(runnable, "pairing-completion"); thread.setDaemon(true); return thread; }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;
    public PairingCompletionListener(SSLContext exactConfirmedMutualContext, PairingCoordinator.PendingPairing nativePending,
            InetSocketAddress address, PairingCompletionTransport.Server nativeReviewedGrantOwner) throws Exception {
        this(exactConfirmedMutualContext, nativePending, address, null, nativeReviewedGrantOwner);
    }
    PairingCompletionListener(SSLContext exactConfirmedMutualContext, PairingCoordinator.PendingPairing nativePending,
            ServerSocket reserved, PairingCompletionTransport.Server nativeReviewedGrantOwner) throws Exception {
        this(exactConfirmedMutualContext, nativePending, null, reserved, nativeReviewedGrantOwner);
    }
    /** Fresh native committed-result read; does not revive an expired invitation/Pending. */
    public PairingCompletionListener(SSLContext exactMutualContext, PairingCoordinator.CommittedPairingRecovery recovery,
            InetSocketAddress address, PairingCompletionTransport.Server originalCommittedOwner) throws Exception {
        this(exactMutualContext, recoveryExpiry(recovery), recovery::checkCurrent,
            recoveryServer(exactMutualContext, recovery, originalCommittedOwner), address, null);
    }
    private interface Current { void require(long now) throws Exception; }
    private interface Serve { void run(Socket socket) throws Exception; }
    private static Serve recoveryServer(SSLContext context, PairingCoordinator.CommittedPairingRecovery recovery, PairingCompletionTransport.Server owner) {
        java.util.Objects.requireNonNull(owner); return socket -> PairingCompletionTransport.serveRecovery(context, recovery, new SocketByteChannel(socket), owner);
    }
    private static Serve commitServer(SSLContext context, PairingCoordinator.PendingPairing pending, PairingCompletionTransport.Server owner) {
        java.util.Objects.requireNonNull(owner); return socket -> PairingCompletionTransport.serve(context, pending, new SocketByteChannel(socket), owner);
    }
    private static long pendingExpiry(PairingCoordinator.PendingPairing pending) throws Exception {
        java.util.Objects.requireNonNull(pending).checkCurrent(System.currentTimeMillis()); return pending.expiresAt();
    }
    private static long recoveryExpiry(PairingCoordinator.CommittedPairingRecovery recovery) throws Exception {
        java.util.Objects.requireNonNull(recovery).checkCurrent(System.currentTimeMillis());
        long remaining = recovery.expiresAt() - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 30_000) throw new SecurityException("Native committed recovery read exceeds its short lifetime");
        return recovery.expiresAt();
    }
    private PairingCompletionListener(SSLContext context, PairingCoordinator.PendingPairing pending,
            InetSocketAddress address, ServerSocket reserved, PairingCompletionTransport.Server owner) throws Exception {
        this(context, pendingExpiry(pending), pending::checkCurrent,
            commitServer(context, pending, owner), address, reserved);
    }
    private PairingCompletionListener(SSLContext context, long expiresAt, Current current, Serve serve,
            InetSocketAddress address, ServerSocket reserved) throws Exception {
        java.util.Objects.requireNonNull(context); java.util.Objects.requireNonNull(current); java.util.Objects.requireNonNull(serve);
        current.require(System.currentTimeMillis()); PairingCandidateTransport.deadline(expiresAt);
        if (reserved != null && (reserved.isClosed() || !reserved.isBound())) throw new IOException("Reserved completion endpoint unavailable");
        server = reserved == null ? new ServerSocket() : reserved;
        if (reserved == null) { server.setReuseAddress(false); server.bind(address, 2); }
        server.setSoTimeout(250);
        acceptor = new Thread(() -> {
            try {
                while (!closed && System.currentTimeMillis() < expiresAt) {
                    try { current.require(System.currentTimeMillis()); } catch (Exception revoked) { break; }
                    Socket socket;
                    try { socket = server.accept(); } catch (SocketTimeoutException timeout) { continue; }
                    active.add(socket);
                    try {
                        workers.execute(() -> {
                            try { serve.run(socket); }
                            catch (Exception rejected) { }
                            finally { active.remove(socket); try { socket.close(); } catch (IOException ignored) { } }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException full) { active.remove(socket); socket.close(); }
                }
            } catch (IOException unavailable) { }
            finally { try { close(); } catch (IOException ignored) { } }
        }, "pairing-completion-listener"); acceptor.setDaemon(true); acceptor.start();
    }
    public int port() { return server.getLocalPort(); }
    public boolean isListening() { return !closed && !server.isClosed() && server.isBound(); }
    @Override public void close() throws IOException { closed = true; server.close(); for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { } workers.shutdownNow(); acceptor.interrupt(); }
}
