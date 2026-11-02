package dev.pocketengine.features.devices.transport;

import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual production publisher/endpoint/private-file IO with synthetic native PNG/current
 * fixtures. Not a real PhonePort capture, Android Bitmap/SQL audience or model HTTP proof. */
public final class DeviceObservedImagePublisherTest {
    private static final String DEVICE = "pw1_" + "a".repeat(64), INSTANCE = "native-source-instance";
    private static final JSONObject RESOURCE = json("{\"screenMode\":\"main_screen\",\"packageNames\":[\"test.pocketworld\"]}");
    private static JSONObject json(String value) { try { return new JSONObject(value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static byte[] png(int frame) throws Exception {
        var result = new ByteArrayOutputStream(); result.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        var header = new ByteArrayOutputStream(); var size = new DataOutputStream(header); size.writeInt(1); size.writeInt(1); size.write(new byte[]{8,6,0,0,0});
        chunk(result, "IHDR", header.toByteArray());
        var pixels = new ByteArrayOutputStream(); try (var compressor = new DeflaterOutputStream(pixels)) { compressor.write(new byte[]{0,(byte)frame,(byte)(frame * 7),(byte)(255 - frame),(byte)255}); }
        chunk(result, "IDAT", pixels.toByteArray()); chunk(result, "IEND", new byte[0]); return result.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream result, String type, byte[] raw) throws Exception {
        byte[] kind = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII); var out = new DataOutputStream(result); out.writeInt(raw.length); out.write(kind); out.write(raw);
        var crc = new CRC32(); crc.update(kind); crc.update(raw); out.writeInt((int) crc.getValue());
    }
    private static DeviceArtifactEndpoint endpoint(DeviceArtifactTransfer cache) {
        return new DeviceArtifactEndpoint(cache, DEVICE, INSTANCE, (permission, exact) -> { throw new SecurityException("No execution fixture permission issued"); },
            (permission, exact, originalRead, transfer, offset, raw) -> { throw new SecurityException("No business fixture bulk sender issued"); }, new DeviceArtifactEndpoint.HistoryAccess() {
                // Catalog lookup test only. This is deliberately NOT an accepted-audience schema and cannot mint HistoryPermission.
                public JSONObject acceptedAudience(DeviceTaskEnvelope query, DeviceArtifactTransfer.Reference exact) throws Exception { return exact.json(); }
                public void require(PairingCoordinator.HistoryPermission permission, DeviceArtifactTransfer.Reference exact) { throw new SecurityException("No history fixture permission issued"); }
            }, (permission, exact, originalRead, transfer, offset, raw) -> { throw new SecurityException("No history data permission issued"); });
    }
    private static JSONObject capture(DeviceObservedImagePublisher publisher, int frame) throws Exception {
        byte[] raw = png(frame); String operation = UUID.nameUUIDFromBytes(("native-fixture-op-" + frame).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        JSONObject observed = new JSONObject().put("deviceId", DEVICE).put("taskId", "native-task").put("runId", "native-run").put("executorEpoch", 7)
            .put("capturedAt", System.currentTimeMillis()).put("frameId", "native-frame-" + frame);
        return publisher.freeze("native-task", "native-run", 7, operation, observed,
            new JSONObject().put("imageBase64", Base64.getEncoder().encodeToString(raw)).put("imageMimeType", "image/png").put("imageWidth", 1).put("imageHeight", 1));
    }
    private static DeviceTaskEnvelope describe(JSONObject descriptor) throws Exception {
        return DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile", DeviceProtocol.PROFILE)
            .put("messageType", "DeviceRequest").put("method", "artifact.describe").put("requestId", UUID.randomUUID().toString()).put("originDeviceId", "pw1_" + "b".repeat(64))
            .put("callerDeviceId", "pw1_" + "b".repeat(64)).put("targetDeviceId", DEVICE).put("instanceId", INSTANCE).put("grantId", "native-fixture-only")
            .put("authorizationEpoch", 1).put("resourceBinding", RESOURCE).put("deadline", System.currentTimeMillis() + 15000).put("arguments", new JSONObject().put("artifactId", descriptor.getString("artifactId")))));
    }
    private static DeviceArtifactTransfer.Reference nativeReference(DeviceArtifactEndpoint endpoint, JSONObject descriptor) throws Exception {
        // Source reads use their ORIGINAL native catalog reference. Mapping to wire may
        // add an explicitly known target epoch; it is not the original cache metadata.
        return new DeviceArtifactTransfer.Reference(endpoint.acceptedHistoryAudience(describe(descriptor)));
    }
    private static void clear(Path root) throws Exception { try (var files = Files.walk(root)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } }
    @Test public void sixActualSourceProducerCapturesRollWithoutAnyReceiverCleanup() throws Exception {
        Path root = Files.createTempDirectory("native-producer-real-six-");
        try {
            var cache = new DeviceArtifactTransfer(root.toFile());
            try (var endpoint = endpoint(cache)) {
                var publisher = new DeviceObservedImagePublisher(endpoint, DEVICE, RESOURCE, () -> { }); var snapshots = new ArrayList<JSONObject>(); var references = new ArrayList<DeviceArtifactTransfer.Reference>();
                for (int frame = 0; frame < 6; frame++) {
                    JSONObject descriptor = capture(publisher, frame); snapshots.add(descriptor);
                    assertEquals(DeviceProtocol.sha256(png(frame)), descriptor.getString("contentDigest"));
                    var exact = nativeReference(endpoint, descriptor); references.add(exact);
                    try (InputStream input = cache.openVerified(exact, () -> { })) { assertArrayEquals(png(frame), input.readAllBytes()); }
                    assertEquals("native-frame-" + frame, descriptor.getJSONObject("observationRef").getString("frameId"));
                }
                assertEquals(4, root.toFile().listFiles((folder, name) -> name.endsWith(".json")).length);
                for (int frame = 0; frame < 2; frame++) {
                    try { endpoint.acceptedHistoryAudience(describe(snapshots.get(frame))); fail("Superseded descriptor remained in source catalog"); } catch (SecurityException unavailable) { }
                    try { cache.openVerified(references.get(frame), () -> { }); fail("Evicted source image bytes remained available"); } catch (java.io.IOException | SecurityException unavailable) { }
                }
                assertEquals(snapshots.get(5).getString("contentDigest"), endpoint.acceptedHistoryAudience(describe(snapshots.get(5))).getString("sha256"));
            }
        } finally { clear(root); }
    }
    @Test public void pinnedOriginalStreamAndNormalFileAreNotEvictedByLaterObservations() throws Exception {
        Path root = Files.createTempDirectory("native-producer-pinned-");
        try {
            var cache = new DeviceArtifactTransfer(root.toFile());
            try (var endpoint = endpoint(cache)) {
                var publisher = new DeviceObservedImagePublisher(endpoint, DEVICE, RESOURCE, () -> { }); JSONObject first = capture(publisher, 0);
                var file = new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId", UUID.randomUUID().toString()).put("sourceDeviceId", DEVICE).put("artifactId", "native-file-keep")
                    .put("versionId", "native-v1").put("taskId", "native-task").put("runId", "native-run").put("operationId", "native-file-op").put("sha256", DeviceProtocol.sha256(new byte[]{1,2,3}))
                    .put("bytes", 3).put("kind", "file").put("mimeType", "application/octet-stream").put("name", "keep.bin"));
                endpoint.publishNative(file, 1, 7, out -> out.write(new byte[]{1,2,3}), () -> { });
                Path foreign = root.resolve(UUID.randomUUID() + ".part"); Files.write(foreign, new byte[]{9,8,7});
                try (InputStream held = cache.openVerified(nativeReference(endpoint, first), () -> { })) {
                    for (int frame = 1; frame <= 6; frame++) capture(publisher, frame);
                    assertArrayEquals(png(0), held.readAllBytes());
                    assertEquals(first.getString("contentDigest"), endpoint.acceptedHistoryAudience(describe(first)).getString("sha256"));
                    try (InputStream kept = cache.openVerified(file, () -> { })) { assertArrayEquals(new byte[]{1,2,3}, kept.readAllBytes()); }
                    assertArrayEquals(new byte[]{9,8,7}, Files.readAllBytes(foreign));
                }
                capture(publisher, 7);
                try { endpoint.acceptedHistoryAudience(describe(first)); fail("Closed old stream kept its source pin forever"); } catch (SecurityException unavailable) { }
            }
        } finally { clear(root); }
    }
    @Test public void fourActualPinnedSnapshotsBackpressureUntilOneRealReaderCloses() throws Exception {
        Path root = Files.createTempDirectory("native-producer-all-pinned-"); var streams = new ArrayList<InputStream>();
        try {
            var cache = new DeviceArtifactTransfer(root.toFile());
            try (var endpoint = endpoint(cache)) {
                var publisher = new DeviceObservedImagePublisher(endpoint, DEVICE, RESOURCE, () -> { });
                for (int frame = 0; frame < 4; frame++) streams.add(cache.openVerified(nativeReference(endpoint, capture(publisher, frame)), () -> { }));
                try { capture(publisher, 4); fail("Fifth capture deleted an actively read source image"); } catch (java.io.IOException full) { }
                streams.remove(0).close(); JSONObject fifth = capture(publisher, 5);
                assertEquals(DeviceProtocol.sha256(png(5)), fifth.getString("contentDigest"));
                for (int index = 0; index < streams.size(); index++) assertArrayEquals(png(index + 1), streams.get(index).readAllBytes());
            }
        } finally { for (var stream : streams) stream.close(); clear(root); }
    }
}
