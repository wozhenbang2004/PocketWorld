package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceIdentity;
import java.io.FileInputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.json.JSONObject;

/** Localhost TEST process. The caller supplies temporary fixture keys and native-signed
 * offer/finalize bytes; this does not provision Android identity or install a Java grant. */
public final class PairingTlsInteropProcess {
    public static void main(String[] args) throws Exception {
        String mode = args[0]; byte[] targetDer = Files.readAllBytes(Path.of(args[4]));
        byte[] request = Files.readAllBytes(Path.of(args[5])); long expiry = Long.parseLong(args[6]);
        SSLContext context;
        if ("candidate".equals(mode)) context = DeviceTlsContexts.pairingCandidateClient(targetDer);
        else if ("completion".equals(mode) || "recovery".equals(mode)) {
            KeyStore keys = KeyStore.getInstance("PKCS12"); char[] password = "localhost-test-only".toCharArray();
            try (FileInputStream input = new FileInputStream(args[3])) { keys.load(input, password); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, password);
            context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), new TrustManager[]{DeviceIdentity.pinnedTrustManager(targetDer)}, null);
        } else throw new IllegalArgumentException("Unknown test mode");
        int[] factoryCalls = {0}, accepts = {0}; String[] targetId = {null}; byte[] response;
        try (Socket socket = new Socket(args[1], Integer.parseInt(args[2]))) {
            if ("candidate".equals(mode)) response = PairingCandidateTransport.exchange(context, targetDer, new SocketByteChannel(socket), expiry, target -> {
                factoryCalls[0]++; targetId[0] = target.deviceId(); return request;
            });
            else {
                PairingCompletionTransport.Client owner = new PairingCompletionTransport.Client() {
                public byte[] request(DeviceIdentity.AuthenticatedPeer target) { factoryCalls[0]++; targetId[0] = target.deviceId(); return request; }
                public void accept(DeviceIdentity.AuthenticatedPeer target, byte[] receipt) throws Exception {
                    if (!target.deviceId().equals(targetId[0]) || !new JSONObject(new String(receipt, java.nio.charset.StandardCharsets.UTF_8)).getString("messageType").equals("PairingCompletion")) throw new AssertionError("Wrong native fixture completion");
                    accepts[0]++;
                }
                };
                response = "recovery".equals(mode) ? PairingCompletionTransport.exchangeRecovery(context, targetDer, new SocketByteChannel(socket), expiry, owner)
                    : PairingCompletionTransport.exchange(context, targetDer, new SocketByteChannel(socket), expiry, owner);
            }
        }
        JSONObject receipt = new JSONObject(new String(response, java.nio.charset.StandardCharsets.UTF_8));
        if (factoryCalls[0] != 1 || !receipt.getString("messageType").equals("candidate".equals(mode) ? "PairingCandidateReceipt" : "PairingCompletion")) throw new AssertionError("Wrong isolated fixture receipt");
        System.out.println(new JSONObject().put("mode", mode).put("provider", context.getProvider().getName()).put("targetDeviceId", targetId[0])
            .put("factoryCallsAfterTls", factoryCalls[0]).put("nativeCompletionCallbacks", accepts[0]).put("javaGrantInstalled", false).toString());
    }
}
