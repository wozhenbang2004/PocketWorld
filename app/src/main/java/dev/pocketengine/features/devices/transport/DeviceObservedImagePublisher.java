package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.json.JSONObject;

/** Method-reference adapter for DeviceToolsRuntime.ObservedImageSink. The image came from the
 * actual native PhonePort under its original RunControl; no URL/path/image JSON from a peer
 * is accepted here. The returned value is the unique wire ArtifactDescriptor, NOT vision. */
public final class DeviceObservedImagePublisher {
    private final DeviceArtifactEndpoint endpoint;
    private final String deviceId;
    private final JSONObject nativeResourceBinding;
    private final DeviceArtifactTransfer.Current current;
    public DeviceObservedImagePublisher(DeviceArtifactEndpoint endpoint, String nativeDeviceId, JSONObject originalApprovedResourceBinding, DeviceArtifactTransfer.Current originalAdmissionAndRunCurrent) throws Exception {
        this.endpoint = java.util.Objects.requireNonNull(endpoint); deviceId = DeviceProtocol.deviceId(nativeDeviceId, "deviceId");
        nativeResourceBinding = new JSONObject(java.util.Objects.requireNonNull(originalApprovedResourceBinding).toString());
        current = java.util.Objects.requireNonNull(originalAdmissionAndRunCurrent);
    }
    public JSONObject freeze(String taskId, String runId, int epoch, String operationId, JSONObject originalObservation, JSONObject actualImage) throws Exception {
        current.require();
        if (!deviceId.equals(originalObservation.optString("deviceId")) || !taskId.equals(originalObservation.optString("taskId"))
            || !runId.equals(originalObservation.optString("runId")) || originalObservation.optInt("executorEpoch", -1) != epoch || epoch < 1) throw new SecurityException("Observed image differs from the original native task/run/device");
        long capturedAt = originalObservation.getLong("capturedAt");
        if (capturedAt > System.currentTimeMillis() || capturedAt + 120_000 <= System.currentTimeMillis()) throw new SecurityException("Original observed image expired; observe again");
        if (!DeviceProtocol.objectKeys(actualImage).equals(java.util.Set.of("imageBase64", "imageMimeType", "imageWidth", "imageHeight"))) throw new SecurityException("Only actual native image bytes and dimensions are accepted");
        String mime = actualImage.getString("imageMimeType"), encoded = actualImage.getString("imageBase64");
        if (!java.util.Set.of("image/png", "image/jpeg").contains(mime) || encoded.length() > ((DeviceArtifactTransfer.MAX_BYTES + 2) / 3) * 4) throw new SecurityException("Observed image outside bounded MIME/byte scope");
        DeviceProtocol.integer(actualImage.get("imageWidth"), "imageWidth", 1, 16384); DeviceProtocol.integer(actualImage.get("imageHeight"), "imageHeight", 1, 16384);
        byte[] raw = Base64.getDecoder().decode(encoded);
        if (raw.length == 0 || raw.length > DeviceArtifactTransfer.MAX_BYTES || (mime.equals("image/png") ? !png(raw) : !jpeg(raw))) throw new SecurityException("Actual observed image bytes invalid");
        String seed = deviceId + ":" + taskId + ":" + runId + ":" + epoch + ":" + operationId + ":" + originalObservation.getString("frameId");
        String transfer = UUID.nameUUIDFromBytes(("device-image-transfer:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
        String artifact = UUID.nameUUIDFromBytes(("device-image-artifact:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
        String sha = DeviceProtocol.sha256(raw);
        var reference = new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId", transfer).put("sourceDeviceId", deviceId).put("artifactId", artifact).put("versionId", sha)
            .put("taskId", taskId).put("runId", runId).put("operationId", operationId).put("sha256", sha).put("bytes", raw.length).put("kind", "screenshot").put("mimeType", mime)
            .put("name", mime.equals("image/png") ? "observed-screen.png" : "observed-screen.jpg").put("observationRef", new JSONObject(originalObservation.toString()))
            .put("resourceBinding", new JSONObject(nativeResourceBinding.toString())).put("expiresAt", capturedAt + 120_000));
        try { return endpoint.publishNative(reference, 1, epoch, output -> output.write(raw), current); }
        finally { java.util.Arrays.fill(raw, (byte) 0); }
    }
    private static boolean png(byte[] raw) { byte[] signature = {(byte)137,80,78,71,13,10,26,10}; if (raw.length < 24) return false; for (int i=0;i<8;i++) if (raw[i]!=signature[i]) return false; return true; }
    private static boolean jpeg(byte[] raw) { return raw.length >= 4 && raw[0] == (byte)0xff && raw[1] == (byte)0xd8 && raw[raw.length-2] == (byte)0xff && raw[raw.length-1] == (byte)0xd9; }
}
