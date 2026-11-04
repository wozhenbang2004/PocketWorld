package dev.pocketengine.tools;

import com.google.archivepatcher.generator.bsdiff.BsDiffDeltaGenerator;
import dev.pocketengine.features.updates.ApkDelta;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/** Offline release preparation only. Does not sign, upload, publish or install anything. */
public final class ApkDeltaTool {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected OLD_APK NEW_APK OUTPUT_PATCH");
        File oldApk = new File(args[0]).getCanonicalFile();
        File newApk = new File(args[1]).getCanonicalFile();
        Path output = new File(args[2]).getCanonicalFile().toPath();
        if (!oldApk.isFile() || !newApk.isFile() || oldApk.length() <= 0 || newApk.length() <= 0
                || oldApk.length() > ApkDelta.MAX_APK_BYTES || newApk.length() > ApkDelta.MAX_APK_BYTES) {
            throw new IOException("Inputs must be nonempty APK files within 512 MiB");
        }
        if (Files.exists(output)) throw new IOException("Refusing to overwrite output");
        Path parent = output.getParent();
        // Upstream's file API mmap sorter needs a 4-byte suffix entry per old byte.
        long scratchBytes = 4L * (oldApk.length() + 1) + newApk.length()
                + Math.min(newApk.length() + 24_000_023L, ApkDelta.MAX_PATCH_BYTES) + 32 * 1024 * 1024;
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        if (Files.getFileStore(tempRoot).getUsableSpace() < scratchBytes
                || Files.getFileStore(parent).getUsableSpace() < newApk.length()) {
            throw new IOException("Insufficient temporary/output disk for bounded patch generation");
        }
        Path scratch = Files.createTempDirectory("pocketworld-apk-delta-");
        Path partial = Files.createTempFile(parent, ".apk-delta-", ".part");
        Path rebuilt = scratch.resolve("rebuilt.apk");
        try {
            try (OutputStream file = Files.newOutputStream(partial);
                    OutputStream bounded = new FilterOutputStream(file) {
                        private long written;
                        @Override public void write(int value) throws IOException {
                            if (++written > ApkDelta.MAX_PATCH_BYTES) throw new IOException("Patch exceeds 256 MiB");
                            out.write(value);
                        }
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            if (length > ApkDelta.MAX_PATCH_BYTES - written) throw new IOException("Patch exceeds 256 MiB");
                            out.write(bytes, offset, length);
                            written += length;
                        }
                    };
                    GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(bounded))) {
                new BsDiffDeltaGenerator().generateDelta(oldApk, newApk, gzip);
            }
            ApkDelta.apply(oldApk, partial.toFile(), rebuilt.toFile(), newApk.length(), () -> {});
            if (!Arrays.equals(digest(newApk.toPath()), digest(rebuilt))) {
                throw new IOException("Generated patch did not reconstruct target bytes");
            }
            // Same-directory rename; never replace an existing patch or any APK input.
            Files.move(partial, output);
            System.out.println("format=bsdiff43-gzip-v1");
            System.out.println("baseBytes=" + oldApk.length());
            System.out.println("baseSha256=" + hex(digest(oldApk.toPath())));
            System.out.println("targetBytes=" + newApk.length());
            System.out.println("targetSha256=" + hex(digest(newApk.toPath())));
            System.out.println("patchBytes=" + Files.size(output));
            System.out.println("patchSha256=" + hex(digest(output)));
            System.out.println("smallerThanFullApk=" + (Files.size(output) < newApk.length()));
            System.out.println("reconstructedByteExact=true");
        } finally {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(rebuilt);
            Files.deleteIfExists(scratch);
        }
    }

    private static byte[] digest(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[64 * 1024];
            for (int count; (count = input.read(bytes)) != -1;) hash.update(bytes, 0, count);
        }
        return hash.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
