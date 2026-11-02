package dev.pocketengine.features.devices.transport;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/** Single-thread-owned Android/JVM platform SSLEngine. No custom cryptography or trust fallback. */
public final class PlatformTlsEngine implements AutoCloseable {
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    private final SSLEngine engine;
    private final DeviceByteChannel channel;
    // An arbitrary channel packet can end midway through a legal TLS record. Preserve
    // that <=MAX_PACKET carry tail PLUS the next <=MAX_PACKET packet; this is bounded
    // TLS stream reassembly, not a larger wire frame, application queue or trust fallback.
    private final ByteBuffer network = ByteBuffer.allocate(2 * DeviceByteChannel.MAX_PACKET);
    private final ByteBuffer outbound = ByteBuffer.allocate(DeviceByteChannel.MAX_PACKET);
    private final ByteBuffer plaintext = ByteBuffer.allocate(DeviceByteChannel.MAX_PACKET);
    private boolean opened, closed;
    private int internalUnwrapSteps;
    private final boolean mutualPeerRequired;
    public PlatformTlsEngine(SSLEngine engine, DeviceByteChannel channel) throws SSLException { this(engine, channel, true); }
    /** Package-only bootstrap use: server-auth TLS never issues a business AuthenticatedPeer. */
    PlatformTlsEngine(SSLEngine engine, DeviceByteChannel channel, boolean mutualPeerRequired) throws SSLException {
        this.mutualPeerRequired = mutualPeerRequired;
        this.engine = java.util.Objects.requireNonNull(engine); this.channel = java.util.Objects.requireNonNull(channel);
        String[] protocols = java.util.Arrays.stream(engine.getSupportedProtocols()).filter(p -> p.equals("TLSv1.3") || p.equals("TLSv1.2")).toArray(String[]::new);
        if (protocols.length == 0) throw new SSLException("TLS 1.2+ unavailable");
        engine.setEnabledProtocols(protocols); if (!engine.getUseClientMode()) { engine.setWantClientAuth(false); engine.setNeedClientAuth(mutualPeerRequired); }
        if (engine.getSession().getPacketBufferSize() > DeviceByteChannel.MAX_PACKET || engine.getSession().getApplicationBufferSize() > plaintext.capacity()) throw new SSLException("TLS provider buffer exceeds bound");
        engine.beginHandshake();
    }
    public SSLSession handshake(long deadlineMillis) throws Exception {
        while (!opened) {
            if (System.currentTimeMillis() >= deadlineMillis) throw new java.net.SocketTimeoutException("Device TLS handshake timed out");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Device TLS interrupted");
            switch (engine.getHandshakeStatus()) {
                case NEED_TASK: tasks(); break;
                case NEED_WRAP: wrap(EMPTY.duplicate()); break;
                case NEED_UNWRAP:
                    if (!unwrap(null)) readNetwork((int) Math.min(250, Math.max(1, deadlineMillis - System.currentTimeMillis())));
                    break;
                case FINISHED: case NOT_HANDSHAKING: opened = true; break;
                default:
                    // JDK providers can expose this newer enum member. Android's enum has
                    // no such field: compare its NAME, never link the missing constant.
                    if ("NEED_UNWRAP_AGAIN".equals(engine.getHandshakeStatus().name())) unwrap(null);
                    else throw new SSLException("Unexpected TLS handshake state");
            }
        }
        // getPeerCertificates throws unless mutual authentication actually completed.
        if (mutualPeerRequired || engine.getUseClientMode()) engine.getSession().getPeerCertificates(); return engine.getSession();
    }
    public void write(ByteBuffer bytes) throws Exception {
        requireOpen();
        while (bytes.hasRemaining()) {
            int before = bytes.remaining(); wrap(bytes); tasks();
            if (bytes.remaining() == before) throw new SSLException("TLS write made no progress; no replay attempted");
        }
    }
    public interface Plaintext { void accept(ByteBuffer bytes) throws Exception; }
    /** Bounded polling allows control sends and cancellation between network reads. */
    public void poll(int timeoutMillis, Plaintext sink) throws Exception {
        requireOpen();
        boolean[] delivered = {false};
        Plaintext current = bytes -> { delivered[0] = true; sink.accept(bytes); };
        while (unwrap(current) && !delivered[0]) { tasks(); if (!network.hasRemaining() && network.position() == 0) break; }
        // Return each plaintext chunk before unwrapping a following record, including a legal
        // close_notify in this SAME packet. Only the caller's frame decoder decides whether
        // this is a complete receipt; a partial frame still fails on the next close/EOF poll.
        if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap(EMPTY.duplicate());
        if (delivered[0]) return;
        readNetwork(timeoutMillis);
        while (unwrap(current) && !delivered[0]) { tasks(); }
        // Preserve mandatory provider protocol traffic before the caller's next write, but
        // never unwrap another record after delivering this chunk just to find close_notify.
        if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap(EMPTY.duplicate());
    }
    private void readNetwork(int timeoutMillis) throws Exception {
        byte[] bytes = channel.receive(timeoutMillis); if (bytes == null) return;
        if (bytes.length == 0 || bytes.length > DeviceByteChannel.MAX_PACKET || bytes.length > network.remaining()) throw new SSLException("TLS input exceeds bounded record buffer");
        network.put(bytes);
    }
    private boolean unwrap(Plaintext sink) throws Exception {
        network.flip(); plaintext.clear();
        SSLEngineResult result;
        try { result = engine.unwrap(network, plaintext); } finally { network.compact(); }
        if (result.getStatus() == SSLEngineResult.Status.CLOSED) { closed = true; throw new EOFException("Authenticated device closed TLS"); }
        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) throw new SSLException("TLS application record exceeds bound");
        plaintext.flip();
        if (plaintext.hasRemaining()) { if (sink == null) throw new SSLException("Application data before authenticated handshake"); sink.accept(plaintext.asReadOnlyBuffer()); }
        tasks();
        boolean again = "NEED_UNWRAP_AGAIN".equals(engine.getHandshakeStatus().name());
        if (again && result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
            if (++internalUnwrapSteps > 64) throw new SSLException("TLS provider internal unwrap made no bounded progress");
        } else internalUnwrapSteps = 0;
        // AGAIN consumes provider-buffered handshake data without waiting for a network read.
        return again || result.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW && (result.bytesConsumed() > 0 || result.bytesProduced() > 0);
    }
    private void wrap(ByteBuffer bytes) throws Exception {
        outbound.clear(); SSLEngineResult result = engine.wrap(bytes, outbound);
        if (result.getStatus() != SSLEngineResult.Status.OK) throw new SSLException("TLS wrap rejected: " + result.getStatus());
        outbound.flip(); if (outbound.hasRemaining()) { byte[] packet = new byte[outbound.remaining()]; outbound.get(packet); channel.send(packet); }
    }
    private void tasks() { Runnable task; while ((task = engine.getDelegatedTask()) != null) task.run(); }
    private void requireOpen() throws SSLException { if (!opened || closed) throw new SSLException("No authenticated device TLS connection"); }
    public SSLSession session() { if (!opened || closed) throw new IllegalStateException("TLS not authenticated"); return engine.getSession(); }
    @Override public void close() throws IOException { closed = true; engine.closeOutbound(); channel.close(); java.util.Arrays.fill(network.array(), (byte) 0); java.util.Arrays.fill(plaintext.array(), (byte) 0); }
}
