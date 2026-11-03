package dev.pocketengine.ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Faulting streams exercise the production writer, not an Android document-provider claim. */
public final class WorkspaceExportOutputTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] ORIGINAL = "原工作区内容\nsecond line\n".getBytes(StandardCharsets.UTF_8);

    private File source() throws Exception {
        File file = temporary.newFile("owner-export-original.bin");
        Files.write(file.toPath(), ORIGINAL);
        return file;
    }

    private static IOException unknown(Throwing action) throws Exception {
        try { action.run(); fail("must not report successful save"); }
        catch (IOException failure) {
            String visible = failure.getMessage().substring(0, Math.min(300, failure.getMessage().length()));
            assertTrue(visible, visible.contains("目标文件可能已被截断或仅写入部分内容"));
            assertTrue(visible, visible.contains("请检查目标文件"));
            assertTrue(visible, visible.contains("原工作区内容未修改"));
            assertTrue(visible, visible.contains("未自动重试"));
            return failure;
        }
        throw new AssertionError("unreachable");
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    private static final class RecordingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final IOException failure = new IOException("fixture failure");
        int failAfter = Integer.MAX_VALUE;
        boolean failFlush, failClose;
        int flushes, closes;
        @Override public void write(int value) throws IOException {
            if (bytes.size() >= failAfter) throw failure;
            bytes.write(value);
        }
        @Override public void flush() throws IOException { flushes++; if (failFlush) throw failure; }
        @Override public void close() throws IOException { closes++; if (failClose) throw failure; }
    }

    @Test public void partialWriteWarnsAndPreservesOriginalWithoutRetry() throws Exception {
        File source = source();
        RecordingOutput output = new RecordingOutput(); output.failAfter = 5;
        AtomicInteger opens = new AtomicInteger();
        IOException failure = unknown(() -> WorkspaceExportOutput.write(source, () -> { opens.incrementAndGet(); return output; }));
        assertSame(output.failure, failure.getCause());
        assertArrayEquals(java.util.Arrays.copyOf(ORIGINAL, 5), output.bytes.toByteArray());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(source.toPath()));
        assertEquals(1, opens.get()); assertEquals(1, output.closes); assertEquals(0, output.flushes);
    }

    @Test public void flushFailureIsUnknownEvenWhenAllBytesWereWritten() throws Exception {
        File source = source();
        RecordingOutput output = new RecordingOutput(); output.failFlush = true;
        AtomicInteger opens = new AtomicInteger();
        IOException failure = unknown(() -> WorkspaceExportOutput.write(source, () -> { opens.incrementAndGet(); return output; }));
        assertSame(output.failure, failure.getCause());
        assertArrayEquals(ORIGINAL, output.bytes.toByteArray());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(source.toPath()));
        assertEquals(1, opens.get()); assertEquals(1, output.flushes); assertEquals(1, output.closes);
    }

    @Test public void closeFailureIsUnknownWithoutReopeningDestination() throws Exception {
        File source = source();
        RecordingOutput output = new RecordingOutput(); output.failClose = true;
        AtomicInteger opens = new AtomicInteger();
        IOException failure = unknown(() -> WorkspaceExportOutput.write(source, () -> { opens.incrementAndGet(); return output; }));
        assertSame(output.failure, failure.getCause());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(source.toPath()));
        assertEquals(1, opens.get()); assertEquals(1, output.closes);
    }

    @Test public void providerCanTruncateThenThrowBeforeReturningAStream() throws Exception {
        File source = source();
        File target = temporary.newFile("target.bin"); Files.write(target.toPath(), ORIGINAL);
        AtomicInteger opens = new AtomicInteger();
        IOException cause = new IOException("provider opened wt then failed");
        IOException failure = unknown(() -> WorkspaceExportOutput.write(source, () -> {
            opens.incrementAndGet(); Files.write(target.toPath(), new byte[0]); throw cause;
        }));
        assertSame(cause, failure.getCause());
        assertEquals(0, target.length()); assertEquals(1, opens.get());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(source.toPath()));
    }

    @Test public void nullProviderStreamDoesNotProveAnUntouchedTarget() throws Exception {
        File source = source(); AtomicInteger opens = new AtomicInteger();
        unknown(() -> WorkspaceExportOutput.write(source, () -> { opens.incrementAndGet(); return null; }));
        assertEquals(1, opens.get()); assertArrayEquals(ORIGINAL, Files.readAllBytes(source.toPath()));
    }

    @Test public void sourceRejectionBeforeProviderDoesNotClaimPossiblePartialOutput() throws Exception {
        File missing = new File(temporary.getRoot(), "missing.bin"); AtomicInteger opens = new AtomicInteger();
        try {
            WorkspaceExportOutput.write(missing, () -> { opens.incrementAndGet(); return new RecordingOutput(); });
            fail("missing prepared source must be rejected");
        } catch (FileNotFoundException expected) {
            assertFalse(expected.getMessage().contains("目标文件可能"));
        }
        assertEquals(0, opens.get());
    }

    @Test public void successfulSaveCopiesExactOriginalOnce() throws Exception {
        File source = source(); RecordingOutput output = new RecordingOutput(); AtomicInteger opens = new AtomicInteger();
        WorkspaceExportOutput.write(source, () -> { opens.incrementAndGet(); return output; });
        assertArrayEquals(ORIGINAL, output.bytes.toByteArray()); assertArrayEquals(ORIGINAL, Files.readAllBytes(source.toPath()));
        assertEquals(1, opens.get()); assertEquals(1, output.flushes); assertEquals(1, output.closes);
    }

    @Test public void warningRemainsVisibleBeforeControllersThreeHundredCharacterLimit() throws Exception {
        File source = source();
        IOException cause = new IOException("very long provider message ".repeat(100));
        IOException failure = unknown(() -> WorkspaceExportOutput.write(source, () -> { throw cause; }));
        assertSame(cause, failure.getCause());
    }
}
