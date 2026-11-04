package dev.pocketengine.tools;

import dev.pocketengine.features.updates.ApkDelta;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free regression of the actual production adapter and official generator. */
public final class ApkDeltaRegression {
    private static int assertions;
    private static final Runnable RUNNING = () -> {};
    private static Path directory;
    private static Path base;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("delta-regression-");
        try {
            Path fixture = Path.of(args[0], "applier/src/test/resources/com/google/archivepatcher/applier/bsdiff/testdata");
            base = write("base.apk", Files.readAllBytes(fixture.resolve("bsdifftest_minimal_blob_a.bin")));
            byte[] canonicalTarget = Files.readAllBytes(fixture.resolve("bsdifftest_minimal_blob_b.bin"));
            byte[] canonicalPatch = Files.readAllBytes(fixture.resolve("bsdifftest_minimal_patch_a_to_b.bin"));
            Path canonicalGzip = write("canonical.gz", gzip(canonicalPatch));
            Path canonicalOut = nextOutput();
            ApkDelta.apply(base.toFile(), canonicalGzip.toFile(), canonicalOut.toFile(), canonicalTarget.length, RUNNING);
            check(Arrays.equals(canonicalTarget, Files.readAllBytes(canonicalOut)), "official canonical patch reconstructs exact bytes");

            // APK-shaped ZIP fixture, not a signed or installable Android application.
            byte[] payload = new byte[512 * 1024];
            new Random(6843).nextBytes(payload);
            Path oldApk = write("old.apk", apk(payload));
            for (int i = 20000; i < 20032; i++) payload[i] ^= 77;
            byte[] targetBytes = apk(payload);
            Path newApk = write("new.apk", targetBytes);
            Path generated = directory.resolve("generated.patch.gz");
            ApkDeltaTool.main(new String[] {oldApk.toString(), newApk.toString(), generated.toString()});
            check(Files.size(generated) < targetBytes.length / 100, "gzip delta saves bandwidth on representative small change");
            Path regenerated = nextOutput();
            ApkDelta.apply(oldApk.toFile(), generated.toFile(), regenerated.toFile(), targetBytes.length, RUNNING);
            check(Arrays.equals(targetBytes, Files.readAllBytes(regenerated)), "generated APK-shaped ZIP reconstructs byte exact");
            ApkDeltaVerifier.main(new String[] {oldApk.toString(), newApk.toString(), generated.toString()});
            check(true, "existing patch verifier uses production adapter");
            byte[] wrongTarget = targetBytes.clone(); wrongTarget[wrongTarget.length - 1] ^= 1;
            Path wrongApk = write("wrong-target.apk", wrongTarget);
            expectFailure(() -> ApkDeltaVerifier.main(new String[] {oldApk.toString(), wrongApk.toString(), generated.toString()}),
                    "existing patch verifier rejects same-length wrong target");
            byte[] oldBefore = Files.readAllBytes(base);

            byte[] minimal = raw(1, new long[] {0, 1, 0}, new byte[] {42});
            Path valid = write("one.gz", gzip(minimal));
            Path one = nextOutput();
            ApkDelta.apply(base.toFile(), valid.toFile(), one.toFile(), 1, RUNNING);
            check(Arrays.equals(new byte[] {42}, Files.readAllBytes(one)), "literal-only standard directive");
            reject("unsupported raw transport", minimal, 1);
            reject("empty transport", new byte[0], 1);
            reject("truncated gzip header", Arrays.copyOf(gzip(minimal), 5), 1);
            reject("truncated gzip trailer", Arrays.copyOf(gzip(minimal), gzip(minimal).length - 1), 1);
            byte[] crc = gzip(minimal); crc[crc.length - 8] ^= 1;
            reject("gzip CRC mismatch", crc, 1);
            byte[] compressedTrailing = Arrays.copyOf(gzip(minimal), gzip(minimal).length + 7);
            reject("trailing compressed bytes", compressedTrailing, 1);
            ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
            concatenated.write(gzip(minimal)); concatenated.write(gzip(new byte[0]));
            reject("extra empty gzip member", concatenated.toByteArray(), 1);
            reject("wrong target length", gzip(minimal), 2);
            reject("zero target", gzip(minimal), 0);
            reject("oversized target", gzip(minimal), ApkDelta.MAX_APK_BYTES + 1);
            byte[] badSignature = minimal.clone(); badSignature[0] ^= 1;
            reject("bad official magic", gzip(badSignature), 1);
            reject("truncated raw size", gzip(Arrays.copyOf(minimal, 18)), 1);
            reject("truncated directive", gzip(Arrays.copyOf(minimal, 35)), 1);
            reject("truncated payload", gzip(Arrays.copyOf(minimal, minimal.length - 1)), 1);
            byte[] trailing = Arrays.copyOf(minimal, minimal.length + 1);
            reject("trailing decoded data", gzip(trailing), 1);
            reject("negative diff", gzip(raw(1, new long[] {-1, 1, 0}, new byte[] {42})), 1);
            reject("negative extra", gzip(raw(1, new long[] {0, -1, 0}, new byte[] {42})), 1);
            reject("target overrun", gzip(raw(1, new long[] {0, 2, 0}, new byte[] {42, 43})), 1);
            reject("old read overrun", gzip(raw(oldBefore.length + 1L,
                    new long[] {oldBefore.length + 1L, 0, -1}, new byte[oldBefore.length + 1])), oldBefore.length + 1L);
            reject("negative seek", gzip(raw(1, new long[] {0, 1, -1}, new byte[] {42})), 1);
            reject("seek past old", gzip(raw(1, new long[] {0, 1, oldBefore.length + 1L}, new byte[] {42})), 1);
            reject("huge seek", gzip(raw(1, new long[] {0, 1, Long.MAX_VALUE}, new byte[] {42})), 1);
            byte[] negativeZero = minimal.clone(); Arrays.fill(negativeZero, 23, 31, (byte) 0); negativeZero[30] = (byte) 128;
            reject("negative-zero directive", gzip(negativeZero), 1);
            reject("directive budget including zero-progress", directiveBomb(), 1);
            reject("compressed non-delta bomb rejected early", gzip(new byte[24_000_026]), 1);

            Path cancelled = nextOutput();
            expectFailure(() -> ApkDelta.apply(base.toFile(), canonicalGzip.toFile(), cancelled.toFile(),
                    canonicalTarget.length, () -> { throw new CancellationException(); }), "cancel before creating output");
            check(!Files.exists(cancelled), "preflight cancel leaves no output");
            Path during = nextOutput();
            expectFailure(() -> ApkDelta.apply(oldApk.toFile(), generated.toFile(), during.toFile(),
                    targetBytes.length, () -> { if (during.toFile().length() > 0) throw new CancellationException(); }),
                    "cancel during apply");
            check(!Files.exists(during), "apply cancellation removes partial output");
            Thread.currentThread().interrupt();
            try {
                expectFailure(() -> ApkDelta.apply(base.toFile(), valid.toFile(), nextOutput().toFile(), 1, RUNNING),
                        "thread interrupt cancels");
                check(Thread.currentThread().isInterrupted(), "interrupt flag is preserved");
            } finally { Thread.interrupted(); }
            Path existing = write("existing.apk", new byte[] {7, 8, 9});
            expectFailure(() -> ApkDelta.apply(base.toFile(), valid.toFile(), existing.toFile(), 1, RUNNING),
                    "existing output rejected");
            check(Arrays.equals(new byte[] {7, 8, 9}, Files.readAllBytes(existing)), "existing output untouched");
            expectFailure(() -> ApkDelta.apply(base.toFile(), valid.toFile(), base.toFile(), 1, RUNNING), "input alias rejected");
            check(Arrays.equals(oldBefore, Files.readAllBytes(base)), "old input never changed");
            Path sparse = directory.resolve("sparse.bin");
            try (RandomAccessFile file = new RandomAccessFile(sparse.toFile(), "rw")) { file.setLength(ApkDelta.MAX_APK_BYTES + 1); }
            expectFailure(() -> ApkDelta.apply(sparse.toFile(), valid.toFile(), nextOutput().toFile(), 1, RUNNING), "old size bound");
            try (RandomAccessFile file = new RandomAccessFile(sparse.toFile(), "rw")) { file.setLength(ApkDelta.MAX_PATCH_BYTES + 1); }
            expectFailure(() -> ApkDelta.apply(base.toFile(), sparse.toFile(), nextOutput().toFile(), 1, RUNNING), "patch size bound");
            System.out.println("PASS: " + assertions + " delta assertions (no Android SDK, signing or installation)");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static byte[] directiveBomb() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write("ENDSLEY/BSDIFF43".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            number(gzip, 1);
            byte[] zeros = new byte[24 * 1000];
            for (int i = 0; i < 1001; i++) gzip.write(zeros);
        }
        return bytes.toByteArray();
    }

    private static byte[] raw(long target, long[] controls, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("ENDSLEY/BSDIFF43".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        number(bytes, target);
        for (long value : controls) number(bytes, value);
        bytes.write(payload);
        return bytes.toByteArray();
    }

    private static void number(OutputStream output, long value) throws Exception {
        long bits = value < 0 ? -value | Long.MIN_VALUE : value;
        for (int shift = 0; shift < 64; shift += 8) output.write((int) (bits >>> shift) & 255);
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(data); }
        return bytes.toByteArray();
    }

    private static byte[] apk(byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("classes.dex");
            entry.setTime(0); entry.setMethod(ZipEntry.STORED); entry.setSize(payload.length);
            CRC32 crc = new CRC32(); crc.update(payload); entry.setCrc(crc.getValue());
            zip.putNextEntry(entry); zip.write(payload); zip.closeEntry();
            ZipEntry manifest = new ZipEntry("AndroidManifest.xml"); manifest.setTime(0);
            zip.putNextEntry(manifest); zip.write(new byte[] {1, 2, 3, 4}); zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void reject(String name, byte[] patch, long targetBytes) throws Exception {
        Path input = write("bad-" + sequence++ + ".gz", patch);
        Path output = nextOutput();
        expectFailure(() -> ApkDelta.apply(base.toFile(), input.toFile(), output.toFile(), targetBytes, RUNNING), name);
        check(!Files.exists(output), name + " leaves no output");
    }

    private static Path write(String name, byte[] bytes) throws Exception {
        Path file = directory.resolve(name); Files.write(file, bytes); return file;
    }

    private static Path nextOutput() { return directory.resolve("out-" + sequence++ + ".apk"); }

    private static void expectFailure(Throwing action, String label) throws Exception {
        try { action.run(); }
        catch (IOException | CancellationException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection: " + label);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private interface Throwing { void run() throws Exception; }
}
