package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/** Bounded source stream for the original AttachmentBook. EOF is returned only after
 * the bytes actually copied match the reviewed length/SHA and the file is rechecked.
 * No temporary store, publication, callback into Local/Tasks, or execution authority. */
final class ReviewedBuildOutput extends InputStream {
    static final class Observation {
        final long bytes;
        final String sha256;
        Observation(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }
    private final RuntimeWorkspace workspace;
    private final String relative, expectedSha;
    private final Path path;
    private final long limit, expectedBytes;
    private final RunControl original;
    private final BasicFileAttributes before;
    private final MessageDigest digest;
    private final InputStream input;
    private final byte[] one = new byte[1];
    private long count;
    private String actualSha;
    private boolean completed, closed;

    private ReviewedBuildOutput(RuntimeWorkspace workspace, String relative, long limit,
                                long expectedBytes, String expectedSha, RunControl original) throws Exception {
        this.workspace = Objects.requireNonNull(workspace);
        this.relative = ProjectManagedOutput.path(relative);
        this.original = Objects.requireNonNull(original);
        this.limit = limit;
        this.expectedBytes = expectedBytes;
        this.expectedSha = expectedSha;
        if (limit < 1 || expectedBytes < -1 || expectedBytes > limit ||
                (expectedSha != null && (!expectedSha.matches("[a-f0-9]{64}") || expectedBytes < 0)))
            throw new SecurityException("生成文件流缺少准确字节范围");
        original.check();
        path = workspace.resolve(this.relative, true).toPath();
        before = attributes(path, limit);
        if (expectedBytes >= 0 && before.size() != expectedBytes)
            throw new SecurityException("生成文件长度已变化，未复制");
        digest = MessageDigest.getInstance("SHA-256");
        input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }

    static Observation inspect(RuntimeWorkspace root, String path, long limit, RunControl original) throws Exception {
        Observation first;
        try (ReviewedBuildOutput source = new ReviewedBuildOutput(root, path, limit, -1, null, original)) {
            drain(source); first = source.observation();
        }
        // Preserve the former reader's second complete hash check without retaining a byte[].
        try (ReviewedBuildOutput second = open(root, path, limit, first.bytes, first.sha256, original)) { drain(second); }
        return first;
    }

    static ReviewedBuildOutput open(RuntimeWorkspace root, String path, long limit,
                                    long expectedBytes, String sha, RunControl original) throws Exception {
        if (sha == null) throw new SecurityException("发布流必须绑定原审阅SHA");
        return new ReviewedBuildOutput(root, path, limit, expectedBytes, sha, original);
    }

    private static void drain(InputStream input) throws IOException {
        byte[] block = new byte[32768];
        try { while (input.read(block) != -1) { /* Digest and bounds are checked by read. */ } }
        finally { java.util.Arrays.fill(block, (byte) 0); }
    }

    private static BasicFileAttributes attributes(Path path, long limit) throws IOException {
        BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isRegularFile() || value.size() < 0 || value.size() > limit)
            throw new IOException("生成成果必须是所选预算内的普通文件");
        Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(links instanceof Number) || ((Number) links).longValue() != 1)
            throw new IOException("生成成果不是独立普通文件");
        return value;
    }

    private void check() throws IOException {
        if (closed) throw new IOException("生成文件流已经关闭");
        try { original.check(); }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("原生成文件读取已停止");
            failure.initCause(stopped); throw failure;
        }
    }

    @Override public int read() throws IOException {
        int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        check(); if (length == 0) return 0; if (completed) return -1;
        // At most one byte past the reviewed size is consumed, solely to detect growth.
        long ceiling = expectedBytes >= 0 ? expectedBytes : before.size();
        int requested = (int) Math.min(length, Math.min(32768L, ceiling - count + 1));
        int n = input.read(bytes, offset, requested);
        if (n < 0) { finish(); return -1; }
        if (n == 0) return 0;
        if (n > limit - count || n > ceiling - count) throw new IOException("生成成果读取期间增长，未发布");
        count += n; digest.update(bytes, offset, n); return n;
    }

    private void finish() throws IOException {
        if (count != before.size() || expectedBytes >= 0 && count != expectedBytes)
            throw new IOException("生成成果读取期间缩短，未发布");
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        actualSha = hash.toString();
        if (expectedSha != null && !expectedSha.equals(actualSha)) throw new IOException("复制字节与原审阅SHA不同，未发布");
        try {
            original.check();
            Path current = workspace.resolve(relative, true).toPath();
            BasicFileAttributes after = attributes(current, limit);
            if (!path.equals(current) || before.size() != after.size() ||
                    !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
                    !Objects.equals(before.fileKey(), after.fileKey()))
                throw new IOException("生成成果读取期间身份变化，未发布");
        } catch (IOException failure) { throw failure; }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("原生成文件核对已停止");
            failure.initCause(stopped); throw failure;
        } catch (Exception failure) { throw new IOException("原生成文件路径核对失败，未发布", failure); }
        completed = true;
    }

    private Observation observation() throws IOException {
        if (!completed) throw new IOException("生成成果尚未完整读取");
        return new Observation(count, actualSha);
    }

    @Override public boolean markSupported() { return false; }
    @Override public void close() throws IOException {
        if (!closed) { closed = true; java.util.Arrays.fill(one, (byte) 0); input.close(); }
    }
}
