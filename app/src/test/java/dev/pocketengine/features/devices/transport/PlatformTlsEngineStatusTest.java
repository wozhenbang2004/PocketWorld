package dev.pocketengine.features.devices.transport;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import static org.junit.Assert.*;

/** Provider-state fixture, NOT a cryptographic or Android-provider interop claim. */
public final class PlatformTlsEngineStatusTest {
    @Test public void freshlyReadPlaintextCompletesRequiredProtocolWrapBeforeTheNextApplicationWrite() throws Exception { postHandshakeWrapBeforeWrite(1); }
    @Test public void bufferedPlaintextCompletesRequiredProtocolWrapBeforeTheNextApplicationWrite() throws Exception { postHandshakeWrapBeforeWrite(2); }
    private static void postHandshakeWrapBeforeWrite(int recordRequiringWrap) throws Exception {
        class PostHandshakeEngine extends InternalEngine {
            boolean needsWrap; int records, protocolWraps, applicationBytes;
            PostHandshakeEngine() { super(0); }
            @Override public SSLEngineResult.HandshakeStatus getHandshakeStatus() { return needsWrap ? SSLEngineResult.HandshakeStatus.NEED_WRAP : SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING; }
            @Override public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] outputs, int offset, int length) {
                if (!input.hasRemaining()) return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, getHandshakeStatus(), 0, 0);
                input.get(); outputs[offset].put((byte) 42); needsWrap = ++records == recordRequiringWrap;
                return new SSLEngineResult(SSLEngineResult.Status.OK, getHandshakeStatus(), 1, 1);
            }
            @Override public SSLEngineResult wrap(ByteBuffer[] inputs, int offset, int length, ByteBuffer output) {
                if (needsWrap) { needsWrap = false; protocolWraps++; output.put((byte) 9); return new SSLEngineResult(SSLEngineResult.Status.OK, getHandshakeStatus(), 0, 1); }
                int consumed = inputs[offset].remaining(); applicationBytes += consumed; inputs[offset].position(inputs[offset].limit()); output.put((byte) 10);
                return new SSLEngineResult(SSLEngineResult.Status.OK, getHandshakeStatus(), consumed, 1);
            }
        }
        PostHandshakeEngine engine = new PostHandshakeEngine(); DeviceByteChannel packet = packet(new byte[]{1, 1}); int[] sends = {0}, delivered = {0};
        DeviceByteChannel channel = new DeviceByteChannel() {
            public byte[] receive(int timeout) throws java.io.IOException, InterruptedException { return packet.receive(timeout); }
            public void send(byte[] raw) { sends[0]++; }
            public void close() { }
        };
        try (var tls = new PlatformTlsEngine(engine, channel)) {
            tls.handshake(System.currentTimeMillis() + 1000);
            for (int i = 0; i < 2; i++) {
                tls.poll(1, bytes -> { assertEquals(42, bytes.get()); delivered[0]++; });
                tls.write(ByteBuffer.wrap(new byte[]{7}));
            }
        }
        assertEquals(2, delivered[0]); assertEquals(1, engine.protocolWraps); assertEquals(2, engine.applicationBytes); assertEquals(3, sends[0]);
    }
    @Test public void completeReceiptReturnsBeforeSamePacketTlsCloseNotify() throws Exception {
        byte[] body = {7, 8, 9, 10};
        try (var tls = new PlatformTlsEngine(closingRecords(DeviceFrameCodec.control(body)), packet(new byte[]{1, 2}))) {
            tls.handshake(System.currentTimeMillis() + 1000);
            assertArrayEquals(body, PairingCandidateTransport.receiveOne(tls, System.currentTimeMillis() + 1000, 1024));
            assertThrows(java.io.EOFException.class, () -> tls.poll(1, bytes -> fail("No plaintext after close_notify")));
        }
    }
    @Test public void bufferedCompleteReceiptReturnsBeforeSamePacketTlsCloseNotify() throws Exception {
        byte[] body = {7, 8, 9, 10};
        try (var tls = new PlatformTlsEngine(closingRecords(DeviceFrameCodec.control(body)), packet(new byte[]{1, 1, 2}))) {
            tls.handshake(System.currentTimeMillis() + 1000);
            // First call reads a packet. The next call must stop after plaintext from the
            // already-buffered second record, before unwrapping the following close_notify.
            assertArrayEquals(body, PairingCandidateTransport.receiveOne(tls, System.currentTimeMillis() + 1000, 1024));
            assertArrayEquals(body, PairingCandidateTransport.receiveOne(tls, System.currentTimeMillis() + 1000, 1024));
            assertThrows(java.io.EOFException.class, () -> tls.poll(1, bytes -> fail("No duplicated receipt")));
        }
    }
    @Test public void partialReceiptBeforeSamePacketTlsCloseNotifyCannotBecomeSuccessful() throws Exception {
        byte[] full = DeviceFrameCodec.control(new byte[]{7, 8, 9, 10});
        byte[] partial = java.util.Arrays.copyOf(full, full.length - 2);
        int[] receipts = {0}; DeviceFrameCodec.Decoder decoder = new DeviceFrameCodec.Decoder();
        DeviceFrameCodec.Listener listener = new DeviceFrameCodec.Listener() {
            public void control(byte[] body) { receipts[0]++; }
            public void chunk(java.util.UUID transfer, long offset, byte[] bytes) { fail("No binary receipt"); }
        };
        try (var tls = new PlatformTlsEngine(closingRecords(partial), packet(new byte[]{1, 2}))) {
            tls.handshake(System.currentTimeMillis() + 1000);
            tls.poll(1, bytes -> decoder.accept(bytes, listener));
            assertTrue(decoder.incomplete()); assertEquals(0, receipts[0]);
            assertThrows(java.io.EOFException.class, () -> tls.poll(1, bytes -> decoder.accept(bytes, listener)));
            assertTrue(decoder.incomplete()); assertEquals(0, receipts[0]);
        }
        // Exercise the actual isolated-RPC reader: returning one partial plaintext chunk
        // from poll is not a receipt, and no retry/write is performed after close_notify.
        try (var tls = new PlatformTlsEngine(closingRecords(partial), packet(new byte[]{1, 2}))) {
            tls.handshake(System.currentTimeMillis() + 1000);
            assertThrows(java.io.EOFException.class, () -> PairingCandidateTransport.receiveOne(tls, System.currentTimeMillis() + 1000, 1024));
        }
    }
    @Test public void localCloseInvalidatesEvenCompleteBufferedPlaintextImmediately() throws Exception {
        byte[] body = {7, 8, 9, 10}; int[] closed = {0};
        DeviceByteChannel packet = packet(new byte[]{1, 1, 2});
        DeviceByteChannel cancellable = new DeviceByteChannel() {
            public byte[] receive(int timeout) throws java.io.IOException, InterruptedException { return packet.receive(timeout); }
            public void send(byte[] raw) { fail("No write/replay in cancellation fixture"); }
            public void close() { closed[0]++; }
        };
        try (var tls = new PlatformTlsEngine(closingRecords(DeviceFrameCodec.control(body)), cancellable)) {
            tls.handshake(System.currentTimeMillis() + 1000);
            assertArrayEquals(body, PairingCandidateTransport.receiveOne(tls, System.currentTimeMillis() + 1000, 1024));
            tls.close(); assertEquals(1, closed[0]);
            assertThrows(SSLException.class, () -> tls.poll(1, bytes -> fail("Local close must not drain pending plaintext")));
        }
    }
    private static SSLEngine closingRecords(byte[] plaintext) {
        return new InternalEngine(0) {
            @Override public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] outputs, int offset, int length) {
                if (!input.hasRemaining()) return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
                if (input.get() == 1) {
                    outputs[offset].put(plaintext);
                    return new SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 1, plaintext.length);
                }
                return new SSLEngineResult(SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 1, 0);
            }
        };
    }
    private static DeviceByteChannel packet(byte[] bytes) {
        return new DeviceByteChannel() {
            private boolean read;
            public byte[] receive(int timeout) throws java.io.EOFException {
                if (read) throw new java.io.EOFException("Later transport EOF"); read = true; return bytes;
            }
            public void send(byte[] raw) { fail("No write/replay in provider-state fixture"); }
            public void close() { }
        };
    }
    @Test public void deliveredPlaintextReturnsBeforeFollowingTransportEof() throws Exception {
        class BufferedEngine extends InternalEngine {
            boolean produced;
            BufferedEngine() { super(0); }
            @Override public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] outputs, int offset, int length) {
                if (!produced) { produced = true; outputs[offset].put((byte) 42); return new SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 1); }
                return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
            }
        }
        int[] delivered = {0}; DeviceByteChannel eof = new DeviceByteChannel() {
            public byte[] receive(int timeout) throws java.io.EOFException { throw new java.io.EOFException("Synthetic next-packet EOF after complete plaintext"); }
            public void send(byte[] raw) { fail("No application write in this provider fixture"); }
            public void close() { }
        };
        try (var tls = new PlatformTlsEngine(new BufferedEngine(), eof)) {
            tls.handshake(System.currentTimeMillis() + 1000); tls.poll(1, bytes -> { assertEquals(42, bytes.get()); delivered[0]++; }); assertEquals(1, delivered[0]);
            assertThrows(java.io.EOFException.class, () -> tls.poll(1, bytes -> fail("No duplicated plaintext")));
        }
    }
    @Test public void partialPlaintextBeforeEofCannotProduceACompleteNativeRpcReceipt() throws Exception {
        byte[] full = DeviceFrameCodec.control(new byte[]{7, 8, 9, 10}); byte[] partial = java.util.Arrays.copyOf(full, full.length - 2);
        class PartialEngine extends InternalEngine {
            boolean produced;
            PartialEngine() { super(0); }
            @Override public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] outputs, int offset, int length) {
                if (!produced) { produced = true; outputs[offset].put(partial); return new SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, partial.length); }
                return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
            }
        }
        int[] receipts = {0}; DeviceFrameCodec.Decoder decoder = new DeviceFrameCodec.Decoder(); DeviceFrameCodec.Listener neverComplete = new DeviceFrameCodec.Listener() {
            public void control(byte[] bytes) { receipts[0]++; }
            public void chunk(java.util.UUID transfer, long offset, byte[] bytes) { fail("No binary in isolated receipt fixture"); }
        };
        DeviceByteChannel eof = new DeviceByteChannel() {
            public byte[] receive(int timeout) throws java.io.EOFException { throw new java.io.EOFException("Synthetic truncated RPC"); }
            public void send(byte[] raw) { fail("No replay/write in partial receive fixture"); }
            public void close() { }
        };
        try (var tls = new PlatformTlsEngine(new PartialEngine(), eof)) {
            tls.handshake(System.currentTimeMillis() + 1000); tls.poll(1, bytes -> decoder.accept(bytes, neverComplete)); assertTrue(decoder.incomplete()); assertEquals(0, receipts[0]);
            assertThrows(java.io.EOFException.class, () -> tls.poll(1, bytes -> decoder.accept(bytes, neverComplete))); assertTrue(decoder.incomplete()); assertEquals(0, receipts[0]);
        }
    }
    @Test public void jdkExtraUnwrapStatusDrainsInternalProviderDataWithoutReadingNetwork() throws Exception {
        InternalEngine engine = new InternalEngine(2); int[] reads = {0};
        try (var tls = new PlatformTlsEngine(engine, channel(reads))) {
            tls.handshake(System.currentTimeMillis() + 1000);
            assertEquals(2, engine.unwraps); assertEquals(0, reads[0]);
        }
    }
    @Test public void providerThatNeverMakesInternalProgressIsBoundedAndFailsClosed() throws Exception {
        InternalEngine engine = new InternalEngine(Integer.MAX_VALUE); int[] reads = {0};
        try (var tls = new PlatformTlsEngine(engine, channel(reads))) {
            try { tls.handshake(System.currentTimeMillis() + 1000); fail("Unbounded internal unwrap accepted"); }
            catch (SSLException expected) { assertTrue(expected.getMessage().contains("internal unwrap")); }
            assertEquals(65, engine.unwraps); assertEquals(0, reads[0]);
        }
    }
    @Test public void maximumPacketAfterLegalRecordCarryTailRemainsBoundedAndDecodable() throws Exception {
        int[] reads = {0};
        class CarryEngine extends InternalEngine {
            boolean finished; int nonempty, consumed;
            CarryEngine() { super(0); }
            @Override public SSLEngineResult.HandshakeStatus getHandshakeStatus() { return finished ? SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING : SSLEngineResult.HandshakeStatus.NEED_UNWRAP; }
            @Override public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] outputs, int offset, int length) {
                if (!input.hasRemaining()) return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, getHandshakeStatus(), 0, 0);
                int count = ++nonempty == 1 ? input.remaining() - 16 : input.remaining();
                input.position(input.position() + count); consumed += count;
                if (nonempty == 2) finished = true;
                return new SSLEngineResult(finished ? SSLEngineResult.Status.OK : SSLEngineResult.Status.BUFFER_UNDERFLOW, getHandshakeStatus(), count, 0);
            }
        }
        CarryEngine engine = new CarryEngine();
        DeviceByteChannel stream = new DeviceByteChannel() {
            public byte[] receive(int timeout) { if (++reads[0] > 2) throw new AssertionError("Unexpected network read"); return new byte[MAX_PACKET]; }
            public void send(byte[] bytes) { fail("Unexpected handshake output"); }
            public void close() { }
        };
        try (var tls = new PlatformTlsEngine(engine, stream)) { tls.handshake(System.currentTimeMillis() + 1000); }
        assertEquals(2, reads[0]); assertEquals(2 * DeviceByteChannel.MAX_PACKET, engine.consumed);
    }
    private static DeviceByteChannel channel(int[] reads) {
        return new DeviceByteChannel() {
            public byte[] receive(int timeout) { reads[0]++; throw new AssertionError("Provider requested INTERNAL unwrap, not more network bytes"); }
            public void send(byte[] raw) { throw new AssertionError("No network output in this state fixture"); }
            public void close() { }
        };
    }
    private static class InternalEngine extends SSLEngine {
        private final int doneAt; int unwraps;
        InternalEngine(int doneAt) { this.doneAt = doneAt; }
        // Use valueOf only in a JVM fixture: product code must not link the Android-absent enum field.
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() { return unwraps < doneAt ? SSLEngineResult.HandshakeStatus.valueOf("NEED_UNWRAP_AGAIN") : SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING; }
        public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] outputs, int offset, int length) { unwraps++; return new SSLEngineResult(SSLEngineResult.Status.OK, getHandshakeStatus(), 0, 0); }
        public SSLEngineResult wrap(ByteBuffer[] inputs, int offset, int length, ByteBuffer output) { throw new AssertionError("No wrap expected"); }
        public Runnable getDelegatedTask() { return null; }
        public void beginHandshake() { }
        public void closeInbound() { }
        public void closeOutbound() { }
        public boolean isInboundDone() { return false; }
        public boolean isOutboundDone() { return false; }
        public String[] getSupportedCipherSuites() { return new String[]{"fixture"}; }
        public String[] getEnabledCipherSuites() { return getSupportedCipherSuites(); }
        public void setEnabledCipherSuites(String[] suites) { }
        public String[] getSupportedProtocols() { return new String[]{"TLSv1.2"}; }
        public String[] getEnabledProtocols() { return getSupportedProtocols(); }
        public void setEnabledProtocols(String[] protocols) { }
        public void setUseClientMode(boolean client) { }
        public boolean getUseClientMode() { return true; }
        public void setNeedClientAuth(boolean needed) { }
        public boolean getNeedClientAuth() { return false; }
        public void setWantClientAuth(boolean wanted) { }
        public boolean getWantClientAuth() { return false; }
        public void setEnableSessionCreation(boolean enabled) { }
        public boolean getEnableSessionCreation() { return true; }
        public SSLSession getSession() {
            return (SSLSession) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SSLSession.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getPacketBufferSize", "getApplicationBufferSize" -> 16384;
                    case "getPeerCertificates" -> new java.security.cert.Certificate[0];
                    case "isValid" -> true;
                    case "getId" -> new byte[0];
                    case "getProtocol" -> "TLSv1.2";
                    case "getCipherSuite" -> "fixture";
                    default -> null;
                };
            });
        }
    }
}
