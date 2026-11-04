package dev.pocketengine.tools;

import dev.pocketengine.features.updates.ApkDelta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Read-only, real-artifact verification through the production delta adapter. */
public final class ApkDeltaVerifier {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected OLD_APK NEW_APK PATCH_GZIP");
        Path oldApk = Path.of(args[0]);
        Path newApk = Path.of(args[1]);
        Path patch = Path.of(args[2]);
        long targetBytes = Files.size(newApk);
        if (targetBytes <= 0 || targetBytes > ApkDelta.MAX_APK_BYTES) throw new IOException("Target size exceeds delta limit");
        if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).getUsableSpace()
                < targetBytes + 16 * 1024 * 1024L) throw new IOException("Insufficient temporary disk for verification");
        Path directory = Files.createTempDirectory("pocketworld-delta-verify-");
        Path rebuilt = directory.resolve("rebuilt.apk");
        try {
            ApkDelta.apply(oldApk.toFile(), patch.toFile(), rebuilt.toFile(), targetBytes, () -> {});
            if (!MessageDigest.isEqual(digest(newApk), digest(rebuilt))) {
                throw new IOException("Patch does not reconstruct the provided exact target APK");
            }
            System.out.println("reconstructedByteExact=true");
        } finally {
            Files.deleteIfExists(rebuilt);
            Files.deleteIfExists(directory);
        }
    }

    private static byte[] digest(Path path) throws Exception {
        MessageDigest result = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) result.update(buffer, 0, count);
        }
        return result.digest();
    }
}
