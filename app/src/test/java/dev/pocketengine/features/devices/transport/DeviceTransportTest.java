package dev.pocketengine.features.devices.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DeviceTransportTest {
    private static DeviceArtifactTransfer.Reference reference(byte[] raw) throws Exception {
        return new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId", UUID.randomUUID().toString()).put("sourceDeviceId", "pw1_" + "a".repeat(64))
            .put("artifactId", "artifact-original").put("versionId", "v-original").put("taskId", "task-original").put("runId", "run-original").put("operationId", "op-original")
            .put("sha256", dev.pocketengine.features.devices.DeviceProtocol.sha256(raw)).put("bytes", raw.length).put("kind", "file").put("mimeType", "application/octet-stream").put("name", "result.bin"));
    }
    @Test public void sharedArtifactNamesUseUnicodeScalarsNotUtf16Units() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("pocketworld.repository", ".")).toAbsolutePath();
        for (int i = 0; i < 5 && !Files.exists(root.resolve("protocol/device-v1/artifact-name-fixtures.json")); i++) root = root.getParent();
        var fixtures = new JSONObject(Files.readString(root.resolve("protocol/device-v1/artifact-name-fixtures.json"))).getJSONArray("fixtures");
        for (int i = 0; i < fixtures.length(); i++) {
            var item = fixtures.getJSONObject(i);
            try { new DeviceArtifactTransfer.Reference(reference(new byte[]{1}).json().put("name", item.getString("name"))); assertTrue(item.getString("id"), item.getBoolean("valid")); }
            catch (SecurityException rejected) { assertFalse(item.getString("id"), item.getBoolean("valid")); }
        }
    }
    @Test public void durableInitializationRecoversSameTransferAndNeverDeletesUnclaimedObjects() throws Exception {
        var root = Files.createTempDirectory("device-initial-artifacts-");
        try {
            var cache = new DeviceArtifactTransfer(root.toFile());
            for (boolean partAlreadyCreated : new boolean[]{false, true}) {
                var exact = reference(new byte[]{1});
                var metadata = new JSONObject().put("reference", exact.json()).put("verifiedOffset", 0)
                    .put("prefixSha256", dev.pocketengine.features.devices.DeviceProtocol.sha256(new byte[0]))
                    .put("phase", "initializing").put("ownershipNonce", UUID.randomUUID().toString());
                Files.writeString(root.resolve(exact.transferId + ".json"), metadata.toString());
                if (partAlreadyCreated) Files.createFile(root.resolve(exact.transferId + ".part"));
                assertEquals(0, cache.begin(exact, () -> { }));
                try { cache.finish(exact, () -> { }); fail("Initialization counted as byte delivery"); } catch (SecurityException expected) { }
                cache.remove(exact, () -> { });
            }
            var foreign = reference(new byte[]{2}); var path = root.resolve(foreign.transferId + ".part");
            byte[] sentinel = new byte[]{6, 7, 8}; Files.write(path, sentinel);
            try { cache.begin(foreign, () -> { }); fail("Unclaimed collision adopted"); } catch (SecurityException expected) { }
            assertArrayEquals(sentinel, Files.readAllBytes(path));
        } finally { try (var paths = Files.walk(root)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
    @Test public void framesAreBoundedAndFragmentationIndependent() throws Exception {
        byte[] control = "真实控制".getBytes(java.nio.charset.StandardCharsets.UTF_8), raw = new byte[DeviceFrameCodec.MAX_CHUNK];
        new java.util.Random(13).nextBytes(raw); UUID transfer = UUID.randomUUID();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(DeviceFrameCodec.control(control)); bytes.write(DeviceFrameCodec.chunk(transfer, 1L << 40, raw));
        var events = new ArrayList<String>(); var decoder = new DeviceFrameCodec.Decoder();
        var listener = new DeviceFrameCodec.Listener() {
            public void control(byte[] value) { assertArrayEquals(control, value); events.add("control"); }
            public void chunk(UUID id, long offset, byte[] value) { assertEquals(transfer, id); assertEquals(1L << 40, offset); assertArrayEquals(raw, value); events.add("chunk"); }
        };
        byte[] stream = bytes.toByteArray(); for (int start = 0; start < stream.length; start += 173) decoder.accept(ByteBuffer.wrap(stream, start, Math.min(173, stream.length - start)), listener);
        assertEquals(java.util.List.of("control", "chunk"), events); assertFalse(decoder.incomplete());
        for (int value : new int[]{0, 65537, 0x80000017, 0xffffffff}) {
            try { new DeviceFrameCodec.Decoder().accept(ByteBuffer.allocate(4).putInt(value).flip(), listener); fail("Oversized/invalid frame accepted"); } catch (java.io.IOException expected) { }
        }
    }
    @Test public void sharedFramingFixturePinsExactBytesAndChecksum() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("pocketworld.repository", ".")).toAbsolutePath();
        for (int i = 0; i < 5 && !Files.exists(root.resolve("protocol/device-v1/framing-fixtures.json")); i++) root = root.getParent();
        JSONObject all = new JSONObject(Files.readString(root.resolve("protocol/device-v1/framing-fixtures.json")));
        assertEquals(DeviceFrameCodec.MAX_BINARY, all.getJSONObject("limits").getInt("binaryPayloadBytes"));
        for (int i = 0; i < all.getJSONArray("fixtures").length(); i++) {
            JSONObject item = all.getJSONArray("fixtures").getJSONObject(i); byte[] raw = java.util.HexFormat.of().parseHex(item.getString("wireHex"));
            var decoder = new DeviceFrameCodec.Decoder(); int[] count = {0};
            var sink = new DeviceFrameCodec.Listener() {
                public void control(byte[] bytes) throws Exception { assertEquals(item.getString("controlUtf8"), new String(bytes, java.nio.charset.StandardCharsets.UTF_8)); assertArrayEquals(raw, DeviceFrameCodec.control(bytes)); count[0]++; }
                public void chunk(UUID id, long offset, byte[] bytes) throws Exception { assertEquals(item.getString("transferId"), id.toString()); assertEquals(item.getLong("offset"), offset); assertArrayEquals(java.util.HexFormat.of().parseHex(item.getString("dataHex")), bytes); assertArrayEquals(raw, DeviceFrameCodec.chunk(id, offset, bytes)); count[0]++; }
            };
            try { decoder.accept(ByteBuffer.wrap(raw), sink); if (!item.getBoolean("valid")) fail("Invalid shared frame reached decoder"); assertEquals(1, count[0]); }
            catch (java.io.IOException rejected) { assertFalse(item.getBoolean("valid")); assertEquals(0, count[0]); }
        }
    }
    @Test public void artifactUsesTheOneSharedWireDescriptor() throws Exception {
        var exact = reference(new byte[]{1,2,3});
        JSONObject wire = DeviceArtifactTransfer.toWireDescriptor(exact, "native-instance", 2, 3);
        assertEquals(dev.pocketengine.features.devices.DeviceProtocol.PROFILE, wire.getString("protocolProfile"));
        assertEquals("ArtifactDescriptor", wire.getString("messageType")); assertEquals(exact.sha256, wire.getString("contentDigest")); assertFalse(wire.has("sha256"));
        assertEquals(new DeviceArtifactTransfer.Reference(exact.json().put("executorEpoch", 3)).contentIdentity(), DeviceArtifactTransfer.fromWireDescriptor(wire).contentIdentity());
        for (String stale : new String[]{null, "pwp1_" + "0".repeat(64)}) {
            JSONObject changed = new JSONObject(wire.toString());
            if (stale == null) changed.remove("protocolProfile"); else changed.put("protocolProfile", stale);
            try { DeviceArtifactTransfer.fromWireDescriptor(changed); fail("Artifact bytes must not downgrade an unknown profile"); }
            catch (dev.pocketengine.features.devices.DeviceProtocol.ProtocolException rejected) { assertEquals("incompatible_profile", rejected.code); }
        }
    }
    @Test public void sourceRequestRunNeverBecomesAnInventedTargetRun() throws Exception {
        JSONObject raw = reference(new byte[]{1, 2}).json(); raw.remove("runId"); raw.put("requestRunId", "actual-source-run").put("requestExecutorEpoch", 7);
        var exact = new DeviceArtifactTransfer.Reference(raw);
        JSONObject wire = DeviceArtifactTransfer.toWireDescriptor(exact, "native-pc-instance", 1, null, null, null, null);
        assertFalse(wire.has("runId")); assertFalse(wire.has("executorEpoch"));
        assertEquals("actual-source-run", wire.getString("requestRunId")); assertEquals(7, wire.getInt("requestExecutorEpoch"));
        assertEquals(exact.contentIdentity(), DeviceArtifactTransfer.fromWireDescriptor(wire).contentIdentity());
        try { DeviceArtifactTransfer.toWireDescriptor(exact, "native-pc-instance", 1, 1); fail("Constant target epoch invented from source metadata"); } catch (SecurityException expected) { }
    }
    @Test public void realBytesResumeChecksumAndAtomicLandingNotBusinessSuccess() throws Exception {
        var root = Files.createTempDirectory("device-artifacts-");
        try {
            byte[] raw = new byte[DeviceFrameCodec.MAX_CHUNK + 137]; new java.util.Random(14).nextBytes(raw);
            var reference = reference(raw); var cache = new DeviceArtifactTransfer(root.toFile()); int[] checks = {0};
            DeviceArtifactTransfer.Current current = () -> checks[0]++;
            assertEquals(0, cache.begin(reference, current));
            byte[] first = java.util.Arrays.copyOf(raw, DeviceFrameCodec.MAX_CHUNK);
            assertEquals(first.length, cache.accept(reference, 0, first, dev.pocketengine.features.devices.DeviceProtocol.sha256(first), current));
            try { cache.finish(reference, current); fail("Partial file counted as delivery"); } catch (SecurityException expected) { }
            Files.write(root.resolve(reference.transferId + ".part"), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
            cache = new DeviceArtifactTransfer(root.toFile()); assertEquals(first.length, cache.begin(reference, current));
            byte[] last = java.util.Arrays.copyOfRange(raw, first.length, raw.length);
            cache.accept(reference, first.length, last, dev.pocketengine.features.devices.DeviceProtocol.sha256(last), current);
            JSONObject receipt = cache.finish(reference, current); assertTrue(receipt.getBoolean("readable")); assertFalse(receipt.getBoolean("businessVerified")); assertFalse(receipt.getBoolean("taskOrGrantRestored"));
            try (var input = cache.openVerified(reference, current)) { assertArrayEquals(raw, input.readAllBytes()); }
            var chunk = cache.readChunk(reference, first.length, current); assertArrayEquals(last, chunk.bytes);
            assertTrue(checks[0] > 10);
        } finally { try (var paths = Files.walk(root)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
    @Test public void exportUsesOriginalNativeBytesAndHashNeverPathMetadata() throws Exception {
        var root = Files.createTempDirectory("device-export-");
        try {
            byte[] raw = "original-package-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8); var reference = reference(raw);
            var cache = new DeviceArtifactTransfer(root.toFile());
            JSONObject result = cache.freeze(reference, out -> out.write(raw), () -> { });
            assertTrue(result.getBoolean("contentVerified"));
            JSONObject same = cache.freeze(reference, out -> { throw new AssertionError("Native exporter replayed"); }, () -> { });
            assertEquals(result.getString("sha256"), same.getString("sha256"));
            try { cache.openVerified(reference, () -> { throw new SecurityException("revoked"); }); fail("Revocation ignored"); } catch (SecurityException expected) { }
            var wrong = reference(raw); cache.begin(wrong, () -> { });
            try { cache.accept(wrong, 0, raw, "0".repeat(64), () -> { }); fail("Chunk hash ignored"); } catch (SecurityException expected) { }
            try { cache.finish(wrong, () -> { }); fail("Empty staging counted as delivery"); } catch (SecurityException expected) { }
        } finally { try (var paths = Files.walk(root)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
    @Test public void symlinksAndHardlinksAndChangedVersionAreRejected() throws Exception {
        var root = Files.createTempDirectory("device-links-");
        try {
            var reference = reference(new byte[]{1}); var cache = new DeviceArtifactTransfer(root.toFile()); cache.begin(reference, () -> { });
            var part = root.resolve(reference.transferId + ".part"); Files.createLink(root.resolve("foreign-link"), part);
            try { cache.begin(reference, () -> { }); fail("Hardlink accepted"); } catch (SecurityException expected) { }
            Files.delete(root.resolve("foreign-link")); Files.delete(part); Files.createSymbolicLink(part, root.resolve("foreign"));
            try { cache.begin(reference, () -> { }); fail("Symbolic link accepted"); } catch (SecurityException expected) { }
            JSONObject changed = reference.json().put("versionId", "v-changed");
            try { cache.begin(new DeviceArtifactTransfer.Reference(changed), () -> { }); fail("Mixed content versions"); } catch (SecurityException expected) { }
        } finally { try (var paths = Files.walk(root)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
}
