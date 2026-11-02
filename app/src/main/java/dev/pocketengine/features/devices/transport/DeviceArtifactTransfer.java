package dev.pocketengine.features.devices.transport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/** Bounded private byte staging, not a task store, grant or project importer.
 * Native callers resolve an original Artifact/ProjectPackage and exact approval first.
 * Metadata/path/EOF alone never produces a delivery receipt. No old run/grant is restored. */
public final class DeviceArtifactTransfer {
    public static final long MAX_BYTES = 20L * 1024 * 1024, MAX_CACHE_BYTES = 80L * 1024 * 1024;
    public static final int MAX_TRANSFERS = 4, CHUNK_BYTES = DeviceFrameCodec.MAX_CHUNK;
    private final File root;
    private record Progress(long offset, MessageDigest digest, Object fileKey, String contentIdentity) { }
    private record PublishedSnapshot(Object fileKey, String contentIdentity) { }
    private final java.util.Map<String, Progress> progress = new java.util.HashMap<>();
    private final java.util.Map<String, PublishedSnapshot> verifiedPublished = new java.util.HashMap<>();
    private final Object pinLock = new Object();
    private final java.util.Map<String, Integer> readPins = new java.util.HashMap<>();
    private final java.util.Set<String> retiring = new java.util.HashSet<>();
    interface Superseded { void removed(Reference exact) throws Exception; }
    final class ReadPin implements AutoCloseable {
        private final String transfer; private boolean closed;
        private ReadPin(String transfer) { this.transfer = transfer; }
        @Override public void close() { synchronized (pinLock) { if (closed) return; closed = true; int count = readPins.getOrDefault(transfer, 0) - 1; if (count > 0) readPins.put(transfer, count); else readPins.remove(transfer); } }
    }
    /** A pin is resource lifecycle only, never data authority; each actual read still checks Current. */
    ReadPin pinQueuedRead(Reference exact) throws Exception {
        live(exact);
        synchronized (pinLock) {
            if (retiring.contains(exact.transferId)) throw new IOException("Original temporary screenshot was superseded");
            if (!readPins.containsKey(exact.transferId) && readPins.size() >= MAX_TRANSFERS) throw new IOException("Too many native pinned artifact reads");
            readPins.put(exact.transferId, readPins.getOrDefault(exact.transferId, 0) + 1); return new ReadPin(exact.transferId);
        }
    }
    boolean isReadPinned(String transfer) { synchronized (pinLock) { return readPins.getOrDefault(transfer, 0) > 0; } }
    public interface Current { void require() throws Exception; }
    public interface Exporter { void write(OutputStream destination) throws Exception; }
    public static final class Reference {
        private final JSONObject value;
        public final String transferId, sourceDeviceId, artifactId, versionId, taskId, runId, operationId, sha256;
        public final long bytes;
        public Reference(JSONObject fixed) throws Exception {
            java.util.Set<String> baseKeys = java.util.Set.of("transferId", "sourceDeviceId", "artifactId", "versionId", "taskId", "operationId", "sha256", "bytes", "kind", "mimeType", "name");
            java.util.Set<String> keys = new java.util.HashSet<>(baseKeys);
            if ("screenshot".equals(fixed.optString("kind"))) keys.addAll(java.util.Set.of("observationRef", "resourceBinding", "expiresAt"));
            java.util.Set<String> actual = dev.pocketengine.features.devices.DeviceProtocol.objectKeys(fixed);
            if (!actual.containsAll(keys)) throw new SecurityException("Artifact reference is missing fixed content/provenance");
            keys.addAll(java.util.Set.of("runId", "executorEpoch", "requestRunId", "requestExecutorEpoch"));
            if (!keys.containsAll(actual) || fixed.has("executorEpoch") && !fixed.has("runId") || fixed.has("requestRunId") != fixed.has("requestExecutorEpoch")) throw new SecurityException("Artifact run roles or fixed metadata invalid");
            for (String key : new String[]{"executorEpoch", "requestExecutorEpoch"}) if (fixed.has(key)) dev.pocketengine.features.devices.DeviceProtocol.integer(fixed.get(key), key, 1, dev.pocketengine.features.devices.DeviceProtocol.MAX_SAFE_INTEGER);
            if (fixed.has("requestRunId")) dev.pocketengine.features.devices.DeviceProtocol.identifier(fixed.get("requestRunId"), "requestRunId");
            transferId = identity(fixed.getString("transferId")); sourceDeviceId = fixed.getString("sourceDeviceId");
            if (!sourceDeviceId.matches("pw1_[a-f0-9]{64}")) throw new SecurityException("Artifact source device invalid");
            artifactId = dev.pocketengine.features.devices.DeviceProtocol.identifier(fixed.getString("artifactId"), "artifactId"); versionId = token(fixed.getString("versionId"));
            taskId = dev.pocketengine.features.devices.DeviceProtocol.identifier(fixed.getString("taskId"), "taskId"); runId = fixed.has("runId") ? dev.pocketengine.features.devices.DeviceProtocol.identifier(fixed.getString("runId"), "runId") : null; operationId = dev.pocketengine.features.devices.DeviceProtocol.identifier(fixed.getString("operationId"), "operationId");
            sha256 = hash(fixed.getString("sha256")); Object size = fixed.get("bytes");
            if (!(size instanceof Integer || size instanceof Long) || ((Number) size).longValue() < 0 || ((Number) size).longValue() > MAX_BYTES) throw new SecurityException("Artifact size outside bound");
            bytes = ((Number) size).longValue();
            if (!java.util.Set.of("file", "project_bundle", "screenshot").contains(fixed.getString("kind"))) throw new SecurityException("Unsupported artifact kind");
            String name = fixed.getString("name"), mime = fixed.getString("mimeType");
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 160 || name.equals(".") || name.equals("..") || name.codePoints().anyMatch(c -> c < 32 || c == '/' || c == '\\' || c == ':' || c >= 0xd800 && c <= 0xdfff)
                || !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") || mime.length() > 200) throw new SecurityException("Artifact display metadata invalid");
            if ("screenshot".equals(fixed.getString("kind"))) {
                JSONObject observation = fixed.getJSONObject("observationRef"); fixed.getJSONObject("resourceBinding");
                Object captured = observation.get("capturedAt"); if (!(captured instanceof Long || captured instanceof Integer) || fixed.getLong("expiresAt") - ((Number) captured).longValue() <= 0 || fixed.getLong("expiresAt") - ((Number) captured).longValue() > 600_000) throw new SecurityException("Screenshot expiry differs from original observation");
                if (!(fixed.get("expiresAt") instanceof Long || fixed.get("expiresAt") instanceof Integer) || fixed.getLong("expiresAt") <= 0 || fixed.getLong("expiresAt") > System.currentTimeMillis() + 600_000 || dev.pocketengine.features.devices.DeviceProtocol.canonical(fixed).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) throw new SecurityException("Screenshot provenance invalid");
            }
            value = new JSONObject(fixed.toString());
        }
        public JSONObject json() throws Exception { return new JSONObject(value.toString()); }
        public String contentIdentity() throws Exception { return digest(canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
    /** One wire shape; this is only a native cache-facts mapping, never a permission grant. */
    public static JSONObject toWireDescriptor(Reference reference, String nativeInstanceId, long revision, long executorEpoch) throws Exception {
        return toWireDescriptor(reference, nativeInstanceId, revision, reference.runId, executorEpoch, null, null);
    }
    /** Explicit REAL target native run and ORIGINAL request run pairs; null omits an unknown
     * role. Neither a source request epoch nor a constant 1 may invent a target runner. */
    public static JSONObject toWireDescriptor(Reference reference, String nativeInstanceId, long revision, String nativeRunId, Long nativeEpoch, String requestRunId, Long requestEpoch) throws Exception {
        JSONObject raw = reference.json();
        if (nativeRunId == null && nativeEpoch != null || nativeRunId != null && nativeEpoch == null) throw new SecurityException("Real target run and epoch must be paired");
        if (requestRunId == null && raw.has("requestRunId")) { requestRunId = raw.getString("requestRunId"); requestEpoch = raw.getLong("requestExecutorEpoch"); }
        if (requestRunId == null && requestEpoch != null || requestRunId != null && requestEpoch == null) throw new SecurityException("Original request run and epoch must be paired");
        if (raw.has("runId") && nativeRunId != null && !raw.getString("runId").equals(nativeRunId) || raw.has("executorEpoch") && nativeEpoch != null && raw.getLong("executorEpoch") != nativeEpoch
            || raw.has("requestRunId") && requestRunId != null && !raw.getString("requestRunId").equals(requestRunId) || raw.has("requestExecutorEpoch") && requestEpoch != null && raw.getLong("requestExecutorEpoch") != requestEpoch) throw new SecurityException("Wire mapping changed fixed native run-role facts");
        JSONObject wire = new JSONObject().put("protocolVersion", dev.pocketengine.features.devices.DeviceProtocol.VERSION)
            .put("protocolProfile", dev.pocketengine.features.devices.DeviceProtocol.PROFILE).put("messageType", "ArtifactDescriptor")
            .put("artifactId", reference.artifactId).put("taskId", reference.taskId).put("executorDeviceId", reference.sourceDeviceId).put("instanceId", nativeInstanceId)
            .put("contentDigest", reference.sha256).put("size", reference.bytes).put("mediaType", raw.getString("mimeType")).put("name", raw.getString("name")).put("revision", revision)
            .put("transferId", reference.transferId).put("versionId", reference.versionId).put("kind", raw.getString("kind")).put("operationId", reference.operationId);
        if (nativeRunId != null) wire.put("runId", nativeRunId).put("executorEpoch", nativeEpoch);
        if (requestRunId != null) wire.put("requestRunId", requestRunId).put("requestExecutorEpoch", requestEpoch);
        for (String key : new String[]{"observationRef", "resourceBinding", "expiresAt"}) if (raw.has(key)) wire.put(key, raw.get(key));
        return dev.pocketengine.features.devices.DeviceProtocol.validateMessage(wire);
    }
    public static Reference fromWireDescriptor(JSONObject descriptor) throws Exception {
        JSONObject wire = dev.pocketengine.features.devices.DeviceProtocol.validateMessage(descriptor);
        if (!"ArtifactDescriptor".equals(wire.optString("messageType"))) throw new SecurityException("Not an artifact descriptor");
        JSONObject raw = new JSONObject().put("transferId", wire.getString("transferId")).put("sourceDeviceId", wire.getString("executorDeviceId")).put("artifactId", wire.getString("artifactId"))
            .put("versionId", wire.getString("versionId")).put("taskId", wire.getString("taskId")).put("operationId", wire.getString("operationId"))
            .put("sha256", wire.getString("contentDigest")).put("bytes", wire.getLong("size")).put("kind", wire.getString("kind")).put("mimeType", wire.getString("mediaType")).put("name", wire.getString("name"));
        for (String key : new String[]{"observationRef", "resourceBinding", "expiresAt", "runId", "executorEpoch", "requestRunId", "requestExecutorEpoch"}) if (wire.has(key)) raw.put(key, wire.get(key));
        return new Reference(raw);
    }
    public static final class Chunk {
        public final byte[] bytes; public final long offset; public final String sha256;
        private Chunk(long offset, byte[] bytes) throws Exception { this.offset = offset; this.bytes = bytes; sha256 = digest(bytes); }
    }
    public DeviceArtifactTransfer(File appPrivateDirectory) throws Exception {
        root = appPrivateDirectory.getAbsoluteFile();
        if (!root.isDirectory() || Files.isSymbolicLink(root.toPath()) || !root.equals(root.getCanonicalFile())) throw new SecurityException("Native private artifact cache required");
    }
    /** The original native exporter provides bytes, never a remotely supplied path. */
    public synchronized JSONObject freeze(Reference reference, Exporter exporter, Current current) throws Exception {
        return freeze(reference, exporter, current, null);
    }
    /** Only the original source endpoint supplies this native catalog cleanup callback. */
    synchronized JSONObject freeze(Reference reference, Exporter exporter, Current current, Superseded superseded) throws Exception {
        current.require(); live(reference); begin(reference, current, superseded);
        File pending = file(reference.transferId, ".part");
        if (Files.exists(file(reference.transferId, ".artifact").toPath(), LinkOption.NOFOLLOW_LINKS)) return finish(reference, current);
        JSONObject state = state(reference); if (state.getLong("verifiedOffset") != 0) throw new SecurityException("Original artifact is already partially staged; do not export again");
        try (FileOutputStream output = new FileOutputStream(pending, false)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256"); long[] count = {0};
            exporter.write(new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    try { current.require(); } catch (Exception revoked) { throw new IOException("Artifact export authorization changed", revoked); }
                    if (length < 0 || count[0] + length > reference.bytes) throw new IOException("Native artifact export exceeds selected bytes");
                    output.write(bytes, offset, length); sha.update(bytes, offset, length); count[0] += length;
                }
            });
            output.getFD().sync();
            if (count[0] != reference.bytes || !hex(sha.digest()).equals(reference.sha256)) throw new SecurityException("Native artifact bytes no longer match selected version");
        } catch (Exception failure) { try (FileChannel reset = channel(pending, true)) { reset.truncate(0); reset.force(true); } throw failure; }
        writeState(reference, reference.bytes, reference.sha256); return finish(reference, current);
    }
    /** Resuming is safe for exact file data only; this API cannot retry a tool or create a job. */
    public synchronized long begin(Reference reference, Current current) throws Exception {
        return begin(reference, current, null);
    }
    private long begin(Reference reference, Current current, Superseded superseded) throws Exception {
        current.require(); live(reference); expireScreenshots(System.currentTimeMillis()); File metadata = file(reference.transferId, ".json");
        if (Files.exists(metadata.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            JSONObject state = state(reference); File complete = file(reference.transferId, ".artifact");
            if (Files.exists(complete.toPath(), LinkOption.NOFOLLOW_LINKS)) { verify(reference, complete, current); return reference.bytes; }
            File pending = file(reference.transferId, ".part"); long offset = state.getLong("verifiedOffset");
            if ("initializing".equals(state.optString("phase"))) {
                if (offset != 0 || !digest(new byte[0]).equals(state.getString("prefixSha256")) || !state.has("ownershipNonce")) throw new SecurityException("Invalid owned initialization checkpoint");
                identity(state.getString("ownershipNonce"));
                if (!Files.exists(pending.toPath(), LinkOption.NOFOLLOW_LINKS)) createEmptyPart(pending);
                regular(pending); if (pending.length() != 0) throw new SecurityException("Owned initialization part is not empty");
                writeState(reference, 0, digest(new byte[0]));
            }
            regular(pending); if (pending.length() < offset || offset > reference.bytes) throw new SecurityException("Original partial artifact is incomplete");
            try (FileChannel data = channel(pending, true)) { data.truncate(offset); data.force(true); }
            MessageDigest sha = fileHasher(pending, current);
            if (!digestSnapshot(sha).equals(state.getString("prefixSha256"))) throw new SecurityException("Original partial artifact changed; no mixed-version resume");
            progress.put(reference.transferId, new Progress(offset, sha, fileKey(pending), reference.contentIdentity()));
            return offset;
        }
        for (;;) {
            File[] entries = root.listFiles((folder, name) -> name.endsWith(".json"));
            if (entries == null || entries.length > MAX_TRANSFERS) throw new IOException("Artifact cache index unavailable or oversized");
            long reserved = reference.bytes; Reference oldest = null;
            for (File entry : entries) {
                regular(entry); Reference old = new Reference(readJson(entry).getJSONObject("reference"));
                if (!entry.getName().equals(old.transferId + ".json")) throw new SecurityException("Native cache index name differs from its exact transfer");
                reserved += old.bytes; JSONObject value = old.json();
                if (superseded != null && reference.sourceDeviceId.equals(old.sourceDeviceId) && "screenshot".equals(value.getString("kind")) && !isReadPinned(old.transferId)
                    && (oldest == null || value.getJSONObject("observationRef").getLong("capturedAt") < oldest.json().getJSONObject("observationRef").getLong("capturedAt")
                        || value.getJSONObject("observationRef").getLong("capturedAt") == oldest.json().getJSONObject("observationRef").getLong("capturedAt") && old.transferId.compareTo(oldest.transferId) < 0)) oldest = old;
            }
            if (entries.length < MAX_TRANSFERS && reserved <= MAX_CACHE_BYTES) break;
            if (oldest == null) throw new IOException("Artifact cache full; native files and active reads cannot be evicted");
            synchronized (pinLock) {
                if (readPins.getOrDefault(oldest.transferId, 0) > 0) continue;
                retiring.add(oldest.transferId);
                try { removeExactOwned(oldest); } catch (Exception failure) { retiring.remove(oldest.transferId); throw failure; }
            }
            try { superseded.removed(oldest); } // Local temporary retention, never a delivered-byte acknowledgement.
            finally { synchronized (pinLock) { retiring.remove(oldest.transferId); } }
        }
        File pending = file(reference.transferId, ".part");
        if (Files.exists(pending.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(file(reference.transferId, ".artifact").toPath(), LinkOption.NOFOLLOW_LINKS)) throw new SecurityException("Unclaimed cache collision; no foreign object removed");
        writeState(reference, 0, digest(new byte[0]), "initializing", UUID.randomUUID().toString());
        createEmptyPart(pending);
        writeState(reference, 0, digest(new byte[0]));
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); digestSnapshot(sha); // Platform clone support is required; no home crypto/fallback.
        progress.put(reference.transferId, new Progress(0, sha, fileKey(pending), reference.contentIdentity()));
        return 0;
    }
    public synchronized long accept(Reference reference, long offset, byte[] raw, String chunkSha256, Current current) throws Exception {
        current.require(); live(reference); if (raw == null || raw.length > CHUNK_BYTES || !digest(raw).equals(hash(chunkSha256))) throw new SecurityException("Artifact chunk checksum mismatch");
        JSONObject state = state(reference); long verified = state.getLong("verifiedOffset");
        if (offset != verified || offset + raw.length > reference.bytes || raw.length == 0 && offset != reference.bytes) throw new SecurityException("Artifact chunk does not extend this exact version/cursor");
        File pending = file(reference.transferId, ".part"); regular(pending);
        Progress previous = progress.get(reference.transferId);
        if (previous == null || previous.offset != offset || !previous.contentIdentity.equals(reference.contentIdentity())) { begin(reference, current); previous = progress.get(reference.transferId); }
        if (previous == null || pending.length() != verified || !java.util.Objects.equals(previous.fileKey, fileKey(pending))) throw new SecurityException("Owned artifact part/cursor changed; resume the original transfer");
        try {
            MessageDigest sha = cloneDigest(previous.digest); sha.update(raw);
            try (FileChannel channel = channel(pending, true)) { channel.position(offset); java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(raw); while (input.hasRemaining()) if (channel.write(input) <= 0) throw new IOException("Artifact write made no progress"); channel.force(true); }
            current.require(); writeState(reference, offset + raw.length, digestSnapshot(sha));
            progress.put(reference.transferId, new Progress(offset + raw.length, sha, previous.fileKey, previous.contentIdentity));
            // Expected authenticated stream prefix; the final full DISK hash is mandatory.
            return offset + raw.length;
        } catch (Exception failed) { progress.remove(reference.transferId); throw failed; }
    }
    public Chunk readChunk(Reference reference, long offset, Current current) throws Exception { return readChunk(reference, offset, CHUNK_BYTES, current); }
    public synchronized Chunk readChunk(Reference reference, long offset, int requestedLength, Current current) throws Exception {
        current.require(); if (requestedLength < 1 || requestedLength > CHUNK_BYTES) throw new SecurityException("Artifact requested chunk length outside bound"); File complete = file(reference.transferId, ".artifact"); requirePublishedSnapshot(reference, complete, current);
        if (offset < 0 || offset > reference.bytes) throw new SecurityException("Artifact chunk cursor outside fixed version");
        byte[] raw = new byte[(int) Math.min(requestedLength, reference.bytes - offset)];
        try (FileChannel channel = channel(complete, false)) { channel.position(offset); java.nio.ByteBuffer output = java.nio.ByteBuffer.wrap(raw); while (output.hasRemaining()) if (channel.read(output) < 0) throw new IOException("Fixed artifact truncated"); }
        current.require(); return new Chunk(offset, raw);
    }
    public synchronized JSONObject finish(Reference reference, Current current) throws Exception {
        current.require(); JSONObject state = state(reference); File target = file(reference.transferId, ".artifact");
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (state.getLong("verifiedOffset") != reference.bytes) throw new SecurityException("Partial bytes are not an artifact delivery");
            File pending = file(reference.transferId, ".part"); verify(reference, pending, current); current.require();
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel directory = FileChannel.open(root.toPath(), StandardOpenOption.READ)) { directory.force(true); }
        }
        verify(reference, target, current);
        freezePublishedSnapshot(reference, target); progress.remove(reference.transferId);
        return new JSONObject().put("reference", reference.json()).put("contentVerified", true).put("savedBytes", reference.bytes).put("sha256", reference.sha256)
            .put("readable", true).put("atomicPublication", true).put("businessVerified", false).put("executionAuthorityRestored", false).put("taskOrGrantRestored", false);
    }
    /** Only pass this native stream to the EXISTING owner review/import path; no auto execution. */
    public synchronized InputStream openVerified(Reference reference, Current current) throws Exception {
        current.require(); File target = file(reference.transferId, ".artifact"); state(reference); verify(reference, target, current);
        ReadPin retained = pinQueuedRead(reference);
        try { return new java.io.FilterInputStream(Files.newInputStream(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            private void check() throws IOException { try { current.require(); live(reference); } catch (Exception revoked) { throw new IOException("Artifact read no longer authorized", revoked); } }
            @Override public int read() throws IOException { check(); return super.read(); }
            @Override public int read(byte[] raw, int offset, int length) throws IOException { check(); return super.read(raw, offset, Math.min(length, CHUNK_BYTES)); }
            @Override public void close() throws IOException { try { super.close(); } finally { retained.close(); } }
        }; } catch (Exception failure) { retained.close(); throw failure; }
    }
    public synchronized void remove(Reference reference, Current current) throws Exception {
        current.require(); removeExactOwned(reference);
    }
    /** Package-private purge for the opaque, already verified screenshot consumer. Data
     * access/authority is NOT restored by cleanup, including after expiry or revocation.
     * Exact fixed metadata and private-cache regular-file checks still protect every name. */
    synchronized void discardVerifiedScreenshot(Reference reference) throws Exception {
        if (!"screenshot".equals(reference.json().getString("kind"))) throw new SecurityException("Only a private received screenshot may be purged without read authority");
        removeExactOwned(reference);
    }
    /** Source-retention only. Missing metadata never authorizes deleting an unclaimed file. */
    synchronized boolean discardSupersededScreenshotIfPresent(Reference reference) throws Exception {
        if (!"screenshot".equals(reference.json().getString("kind"))) throw new SecurityException("Only temporary source screenshots can roll");
        if (isReadPinned(reference.transferId) || !Files.exists(file(reference.transferId, ".json").toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        removeExactOwned(reference); return true;
    }
    private void removeExactOwned(Reference reference) throws Exception {
        state(reference); progress.remove(reference.transferId); verifiedPublished.remove(reference.transferId);
        for (String suffix : new String[]{".part", ".artifact", ".json"}) { File value = file(reference.transferId, suffix); if (Files.exists(value.toPath(), LinkOption.NOFOLLOW_LINKS)) { regular(value); Files.delete(value.toPath()); } }
    }
    /** Native cache retention only. Never deletes user files/projects or changes a task state.
     * Expired bytes are unreadable immediately; physical cleanup is best-effort while the app runs. */
    public synchronized void expireScreenshots(long now) throws Exception {
        File[] entries = root.listFiles((folder, name) -> name.matches("[a-f0-9-]{36}\\.json"));
        if (entries == null) throw new IOException("Native artifact cache unavailable");
        if (entries.length > MAX_TRANSFERS) throw new SecurityException("Artifact cache index exceeds bound");
        for (File entry : entries) {
            JSONObject metadata = readJson(entry), descriptor = metadata.getJSONObject("reference");
            if (!"screenshot".equals(descriptor.optString("kind")) || descriptor.optLong("expiresAt", Long.MAX_VALUE) > now || isReadPinned(descriptor.optString("transferId"))) continue;
            Reference expired = new Reference(descriptor); progress.remove(expired.transferId); verifiedPublished.remove(expired.transferId);
            for (String suffix : new String[]{".part", ".artifact", ".json"}) {
                File value = file(expired.transferId, suffix);
                if (Files.exists(value.toPath(), LinkOption.NOFOLLOW_LINKS)) { regular(value); Files.delete(value.toPath()); }
            }
        }
    }
    private JSONObject state(Reference reference) throws Exception {
        JSONObject value = readJson(file(reference.transferId, ".json"));
        if (!canonical(value.getJSONObject("reference")).equals(canonical(reference.value))) throw new SecurityException("Transfer identity is already bound to different content/provenance");
        return value;
    }
    private void createEmptyPart(File pending) throws Exception {
        try (FileChannel channel = FileChannel.open(pending.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        try (FileChannel directory = FileChannel.open(root.toPath(), StandardOpenOption.READ)) { directory.force(true); }
    }
    private void writeState(Reference reference, long offset, String prefix) throws Exception {
        JSONObject previous = state(reference);
        writeState(reference, offset, prefix, "receiving", previous.optString("ownershipNonce", ""));
    }
    private void writeState(Reference reference, long offset, String prefix, String phase, String nonce) throws Exception {
        File pending = File.createTempFile("artifact-meta-", ".pending", root);
        try {
            byte[] bytes = new JSONObject().put("reference", reference.json()).put("verifiedOffset", offset).put("prefixSha256", prefix).put("phase", phase).put("ownershipNonce", nonce).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(pending)) { output.write(bytes); output.getFD().sync(); }
            Files.move(pending.toPath(), file(reference.transferId, ".json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(root.toPath(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
    private File file(String transfer, String suffix) throws Exception {
        File value = new File(root, identity(transfer) + suffix);
        if (!root.equals(root.getCanonicalFile()) || Files.isSymbolicLink(value.toPath()) || !value.getAbsoluteFile().equals(value.getCanonicalFile())) throw new SecurityException("Artifact cache path changed");
        return value;
    }
    private static FileChannel channel(File file, boolean write) throws Exception { regular(file); return FileChannel.open(file.toPath(), write ? StandardOpenOption.WRITE : StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); }
    private static void regular(File file) throws Exception { if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || !file.getAbsoluteFile().equals(file.getCanonicalFile()) || ((Number) Files.getAttribute(file.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw new SecurityException("Artifact is not a private regular file"); }
    private static JSONObject readJson(File file) throws Exception {
        regular(file); if (file.length() > 16_384) throw new SecurityException("Artifact cache metadata exceeds bound");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096];
        try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            for (int count; (count = input.read(buffer)) != -1;) { if (bytes.size() + count > 16_384) throw new SecurityException("Artifact cache metadata exceeds bound"); bytes.write(buffer, 0, count); }
        }
        return new JSONObject(bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
    }
    private static void verify(Reference reference, File file, Current current) throws Exception { live(reference); regular(file); if (file.length() != reference.bytes || !fileDigest(file, current).equals(reference.sha256)) throw new SecurityException("Fixed artifact bytes/hash differ"); }
    private static Object fileKey(File file) throws Exception { Object key = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey(); if (key == null) throw new SecurityException("Native cache file identity unavailable"); return key; }
    private void freezePublishedSnapshot(Reference reference, File target) throws Exception {
        if (!target.setReadOnly()) throw new IOException("Cannot freeze native artifact snapshot");
        verifiedPublished.put(reference.transferId, new PublishedSnapshot(fileKey(target), reference.contentIdentity()));
    }
    private void requirePublishedSnapshot(Reference reference, File target, Current current) throws Exception {
        current.require(); live(reference); state(reference); regular(target);
        PublishedSnapshot known = verifiedPublished.get(reference.transferId);
        if (known == null) { verify(reference, target, current); freezePublishedSnapshot(reference, target); return; }
        if (target.length() != reference.bytes || !known.contentIdentity.equals(reference.contentIdentity()) || !known.fileKey.equals(fileKey(target))) throw new SecurityException("Frozen native artifact snapshot changed");
        // Only this private cache creates .artifact and never opens it for writes. Each sent
        // chunk has its own SHA; recipient final verification still scans the full disk bytes.
        for (java.nio.file.attribute.PosixFilePermission permission : Files.getPosixFilePermissions(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            if (permission.name().endsWith("WRITE")) throw new SecurityException("Native immutable snapshot became writable");
    }
    private static MessageDigest cloneDigest(MessageDigest digest) throws IOException {
        try { return (MessageDigest) digest.clone(); } catch (CloneNotSupportedException unsupported) { throw new IOException("Platform SHA-256 incremental checkpoints unsupported", unsupported); }
    }
    private static String digestSnapshot(MessageDigest digest) throws IOException { return hex(cloneDigest(digest).digest()); }
    private static String fileDigest(File file, Current current) throws Exception { return hex(fileHasher(file, current).digest()); }
    private static MessageDigest fileHasher(File file, Current current) throws Exception {
        regular(file); MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] raw = new byte[32_768]; long total = 0;
        try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { for (int n; (n = input.read(raw)) != -1;) { current.require(); total += n; if (total > MAX_BYTES) throw new SecurityException("Artifact file exceeds bound"); hash.update(raw, 0, n); } }
        finally { java.util.Arrays.fill(raw, (byte) 0); }
        return hash;
    }
    private static String identity(String value) { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Canonical UUID required"); return value; }
    private static String token(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Fixed artifact version required"); return value; }
    private static String hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("SHA-256 required"); return value; }
    private static String digest(byte[] value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String hex(byte[] value) { StringBuilder result = new StringBuilder(); for (byte b : value) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }
    private static void live(Reference reference) throws Exception {
        if ("screenshot".equals(reference.value.optString("kind")) && reference.value.getLong("expiresAt") <= System.currentTimeMillis()) throw new SecurityException("Original observed image expired; observe again");
    }
    private static String canonical(JSONObject object) throws Exception { return dev.pocketengine.features.devices.DeviceProtocol.canonical(object); }
}
