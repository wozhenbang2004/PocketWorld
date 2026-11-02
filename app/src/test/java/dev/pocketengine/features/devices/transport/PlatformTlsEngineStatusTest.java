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
