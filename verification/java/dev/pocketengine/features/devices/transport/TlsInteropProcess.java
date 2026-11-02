package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import java.io.FileInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/** Localhost test process using the exact product SSLEngine, never Android verification by claim. */
public final class TlsInteropProcess {
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        char[] password = "localhost-test-only".toCharArray();
        try (FileInputStream input = new FileInputStream(args[2])) { keys.load(input, password); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), new TrustManager[]{DeviceIdentity.pinnedTrustManager(Files.readAllBytes(Path.of(args[3])))}, null);
        var engine = context.createSSLEngine(); engine.setUseClientMode(true);
        try (var socket = new Socket(args[0], Integer.parseInt(args[1])); var tls = new PlatformTlsEngine(engine, new SocketByteChannel(socket))) {
            DeviceIdentity.AuthenticatedPeer peer = DeviceIdentity.authenticatedPeer(tls.handshake(System.currentTimeMillis() + 10_000));
            byte[] request = "{\"fixture\":\"JVM-SSLEngine-to-Python-MemoryBIO\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            tls.write(ByteBuffer.wrap(DeviceFrameCodec.control(request)));
            var decoder = new DeviceFrameCodec.Decoder(); boolean[] response = {false};
            var sink = new DeviceFrameCodec.Listener() {
                public void control(byte[] json) throws Exception { if (!java.util.Arrays.equals(request, json)) throw new AssertionError("Wrong original bytes"); response[0] = true; }
                public void chunk(java.util.UUID id, long offset, byte[] raw) { throw new AssertionError("Unexpected binary"); }
            };
            long end = System.currentTimeMillis() + 5000;
            while (!response[0] && System.currentTimeMillis() < end) tls.poll(100, plain -> decoder.accept(plain, sink));
            if (!response[0]) throw new AssertionError("Missing authenticated echo");
            System.out.println("{\"provider\":\"" + context.getProvider().getName() + "\",\"peer\":\"" + peer.deviceId() + "\",\"verifiedEcho\":true}");
        }
    }
}
