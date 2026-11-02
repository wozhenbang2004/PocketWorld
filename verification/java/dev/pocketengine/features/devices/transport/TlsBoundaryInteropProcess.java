package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import java.io.FileInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/** Only synthetic localhost TEST bytes/keys. Reports JDK provider, never Android. */
public final class TlsBoundaryInteropProcess {
    public static void main(String[] args) throws Exception {
        char[] password = "localhost-test-only".toCharArray(); KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(args[2])) { keys.load(input, password); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
        SSLContext inner = SSLContext.getInstance("TLS"); inner.init(km.getKeyManagers(), new TrustManager[]{DeviceIdentity.pinnedTrustManager(Files.readAllBytes(Path.of(args[3])))}, null);
        boolean relay = args[0].startsWith("wss://"), bulk = args[4].equals("bulk"); int size = Integer.parseInt(args[5]);
        byte[] raw = new byte[size]; new java.util.Random(2928661).nextBytes(raw);
        UUID transfer = UUID.fromString("12345678-1234-4234-8234-123456789abc");
        byte[] frame = bulk ? DeviceFrameCodec.chunk(transfer, 0, raw) : DeviceFrameCodec.control(raw);
        DeviceByteChannel channel; OkHttpClient fixtureClient = null;
        if (relay) {
            var outerTrust = DeviceIdentity.pinnedTrustManager(Files.readAllBytes(Path.of(args[6])));
            SSLContext outer = SSLContext.getInstance("TLS"); outer.init(null, new TrustManager[]{outerTrust}, null);
            // Package-only TEST seam; still standard verification + default hostname check.
            fixtureClient = new OkHttpClient.Builder().sslSocketFactory(outer.getSocketFactory(), outerTrust).build();
            channel = new RelayByteChannel(fixtureClient, args[0], args[1]);
        } else channel = new SocketByteChannel(new Socket(args[0], Integer.parseInt(args[1])));
        var engine = inner.createSSLEngine(); engine.setUseClientMode(true); boolean[] received = {false};
        try (var tls = new PlatformTlsEngine(engine, channel)) {
            DeviceIdentity.authenticatedPeer(tls.handshake(System.currentTimeMillis() + 12_000));
            tls.write(ByteBuffer.wrap(frame)); var decoder = new DeviceFrameCodec.Decoder();
            var sink = new DeviceFrameCodec.Listener() {
                public void control(byte[] bytes) { if (bulk || !java.util.Arrays.equals(raw, bytes)) throw new AssertionError("CONTROL byte mismatch"); received[0] = true; }
                public void chunk(UUID id, long offset, byte[] bytes) { if (!bulk || !transfer.equals(id) || offset != 0 || !java.util.Arrays.equals(raw, bytes)) throw new AssertionError("BULK byte/checksum mismatch"); received[0] = true; }
            };
            long until = System.currentTimeMillis() + 10_000;
            while (!received[0] && System.currentTimeMillis() < until) tls.poll(100, plain -> decoder.accept(plain, sink));
            if (!received[0]) throw new AssertionError("Missing boundary echo");
        } finally { if (fixtureClient != null) { fixtureClient.dispatcher().executorService().shutdown(); fixtureClient.connectionPool().evictAll(); } }
        System.out.println(new JSONObject().put("provider", inner.getProvider().getName()).put("path", relay ? "wss" : "lan").put("lane", args[4]).put("rawBytes", size).put("verifiedEcho", true));
    }
}
