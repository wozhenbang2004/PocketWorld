package dev.pocketengine.features.updates;

import com.google.archivepatcher.applier.bsdiff.BsPatch;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/** Bounded GZIP transport adapter for Google's unmodified ENDSLEY/BSDIFF43 implementation. */
public final class ApkDelta {
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    public static final long MAX_PATCH_BYTES = 256L * 1024 * 1024;
    private static final int MAX_DIRECTIVES = 1_000_000;
    private static final byte[] SIGNATURE = "ENDSLEY/BSDIFF43".getBytes(StandardCharsets.US_ASCII);

    private ApkDelta() {}

    /**
     * Reconstructs bytes into a new, private output file, deleting it on any failure.
     * The patch is standard GZIP containing the official raw ENDSLEY/BSDIFF43 stream.
     * Inputs must be caller-owned immutable files. The caller must verify the authenticated
     * patch/base hashes before calling, and the entire target hash and APK signer afterwards.
     * This adapter does not authenticate a release or make it installable.
     */
    public static void apply(File oldApk, File patch, File output, long expectedTargetBytes,
            Runnable cancellationCheck) throws IOException {
        Objects.requireNonNull(cancellationCheck, "cancellationCheck");
        checkCancelled(cancellationCheck);
        if (expectedTargetBytes <= 0 || expectedTargetBytes > MAX_APK_BYTES) {
            throw new IOException("APK target size exceeds delta limit");
        }
        if (!oldApk.isFile() || !patch.isFile()) throw new IOException("Delta input is not a file");
        if (output.getCanonicalFile().equals(oldApk.getCanonicalFile())
                || output.getCanonicalFile().equals(patch.getCanonicalFile())) {
            throw new IOException("Delta output aliases an input");
        }
        boolean created = false;
        try (RandomAccessFile old = new RandomAccessFile(oldApk, "r");
                FileInputStream input = new FileInputStream(patch)) {
            long oldBytes = old.length();
            long patchBytes = input.getChannel().size();
            if (oldBytes <= 0 || oldBytes > MAX_APK_BYTES
                    || patchBytes < SIGNATURE.length + 8 || patchBytes > MAX_PATCH_BYTES) {
                throw new IOException("Delta input size exceeds limit");
            }
            // Upstream intentionally leaves framing to its caller and buffers reads ahead.
            // Validate its standard wire format first so truncation/trailing data are rejected.
            long decodedLimit = expectedTargetBytes + 24L * MAX_DIRECTIVES + SIGNATURE.length + 8;
            try (InputStream decoded = openPatch(input, patchBytes, decodedLimit, cancellationCheck)) {
                preflight(new BufferedInputStream(decoded), oldBytes, expectedTargetBytes,
                        cancellationCheck);
            }
            input.getChannel().position(0);
            checkCancelled(cancellationCheck);
            try (FileChannel file = FileChannel.open(output.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                created = true;
                CheckedOutput target = new CheckedOutput(Channels.newOutputStream(file),
                        expectedTargetBytes, cancellationCheck);
                try (InputStream decoded = openPatch(input, patchBytes, decodedLimit, cancellationCheck)) {
                    BsPatch.applyPatch(old, target, decoded, expectedTargetBytes);
                }
                if (target.written != expectedTargetBytes || file.size() != expectedTargetBytes) {
                    throw new IOException("Delta produced an incorrect APK length");
                }
                checkCancelled(cancellationCheck);
                file.force(true);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (created) {
                try { Files.deleteIfExists(output.toPath()); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static InputStream openPatch(FileInputStream input, long compressedLimit,
            long decodedLimit, Runnable cancellationCheck) throws IOException {
        CheckedInput compressed = new CheckedInput(input, compressedLimit, cancellationCheck) {
            // The owning FileInputStream stays open between validation and application passes.
            @Override public void close() {}
        };
        return new CheckedInput(new SingleMemberGzip(compressed, compressedLimit), decodedLimit,
                cancellationCheck);
    }

    private static final class SingleMemberGzip extends GZIPInputStream {
        private final long headerBytes;
        private final long compressedBytes;

        SingleMemberGzip(CheckedInput input, long compressedBytes) throws IOException {
            super(input, 16 * 1024);
            this.headerBytes = input.consumed;
            this.compressedBytes = compressedBytes;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            // Standard GZIPInputStream may silently accept trailing garbage or extra members.
            // Count the header, actual DEFLATE input and RFC1952's 8-byte trailer instead.
            if (count == -1 && headerBytes + inf.getBytesRead() + 8 != compressedBytes) {
                throw new IOException("Trailing or concatenated GZIP data");
            }
            return count;
        }
    }

    private static void preflight(InputStream input, long oldBytes, long targetBytes,
            Runnable cancellationCheck) throws IOException {
        byte[] signature = new byte[SIGNATURE.length];
        readFully(input, signature, signature.length);
        if (!Arrays.equals(signature, SIGNATURE) || readLong(input) != targetBytes) {
            throw new IOException("Unsupported delta format or target size");
        }
        long oldOffset = 0;
        long produced = 0;
        int directives = 0;
        byte[] scratch = new byte[64 * 1024];
        while (produced < targetBytes) {
            checkCancelled(cancellationCheck);
            if (++directives > MAX_DIRECTIVES) throw new IOException("Too many delta instructions");
            long diff = readLong(input);
            long extra = readLong(input);
            long seek = readLong(input);
            // Values are bounded before addition; no attacker-controlled long can overflow.
            if (diff < 0 || diff > targetBytes - produced || extra < 0
                    || extra > targetBytes - produced - diff
                    || seek < Integer.MIN_VALUE || seek > Integer.MAX_VALUE
                    || diff > oldBytes - oldOffset) {
                throw new IOException("Delta instruction exceeds file bounds");
            }
            long nextOld = oldOffset + diff + seek;
            if (nextOld < 0 || nextOld > oldBytes) throw new IOException("Delta seek out of bounds");
            long remaining = diff + extra;
            while (remaining > 0) {
                checkCancelled(cancellationCheck);
                int amount = (int) Math.min(remaining, scratch.length);
                readFully(input, scratch, amount);
                remaining -= amount;
            }
            produced += diff + extra;
            oldOffset = nextOld;
        }
        if (input.read() != -1) throw new IOException("Trailing delta data");
    }

    private static long readLong(InputStream input) throws IOException {
        long bits = 0;
        for (int shift = 0; shift < 64; shift += 8) {
            int value = input.read();
            if (value == -1) throw new EOFException("Truncated delta integer");
            bits |= (long) value << shift;
        }
        if (bits == Long.MIN_VALUE) throw new IOException("Negative zero in delta");
        return bits < 0 ? -(bits & Long.MAX_VALUE) : bits;
    }

    private static void readFully(InputStream input, byte[] buffer, int length) throws IOException {
        int position = 0;
        while (position < length) {
            int count = input.read(buffer, position, length - position);
            if (count < 0) throw new EOFException("Truncated delta data");
            if (count == 0) throw new IOException("Delta input made no progress");
            position += count;
        }
    }

    private static void checkCancelled(Runnable cancellationCheck) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Delta cancelled");
        cancellationCheck.run();
    }

    private static class CheckedInput extends FilterInputStream {
        private final long limit;
        private final Runnable cancellationCheck;
        private long consumed;

        CheckedInput(InputStream input, long limit, Runnable cancellationCheck) {
            super(input);
            this.limit = limit;
            this.cancellationCheck = cancellationCheck;
        }

        @Override public int read() throws IOException {
            checkCancelled(cancellationCheck);
            int value = in.read();
            if (value >= 0 && ++consumed > limit) throw new IOException("Delta input grew");
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(cancellationCheck);
            // Limit each I/O to a bounded cancellation interval.
            int count = in.read(bytes, offset, Math.min(length, 64 * 1024));
            if (count > 0 && (consumed += count) > limit) throw new IOException("Delta input grew");
            return count;
        }
    }

    private static final class CheckedOutput extends OutputStream {
        private final OutputStream output;
        private final long limit;
        private final Runnable cancellationCheck;
        private long written;

        CheckedOutput(OutputStream output, long limit, Runnable cancellationCheck) {
            this.output = output;
            this.limit = limit;
            this.cancellationCheck = cancellationCheck;
        }

        @Override public void write(int value) throws IOException {
            checkCancelled(cancellationCheck);
            if (written >= limit) throw new IOException("Delta output exceeds target size");
            output.write(value);
            written++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(cancellationCheck);
            if (length > limit - written) throw new IOException("Delta output exceeds target size");
            output.write(bytes, offset, length);
            written += length;
        }

        @Override public void flush() throws IOException { output.flush(); }
    }
}
