package dev.pocketengine.phone;

import dev.pocketengine.core.RunControl;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real RunControl and preview lifecycle; the physical screen source is an explicit fixture. */
public final class OwnerScreenContextTest {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlS8AAAAASUVORK5CYII=");
    private final AtomicLong time = new AtomicLong(1000);
    private final AtomicBoolean hostCurrent = new AtomicBoolean(true);
    private final RunControl original = control();
    private static RunControl control() { RunControl c = new RunControl(); c.bindHostRun("task", "run", 1); return c; }
    private static OwnerScreenContext.Target background() { return new OwnerScreenContext.Target("task", "run", 1, "background", "execution-background", 7); }
    private final class Screen implements OwnerScreenContext.Source {
        int reads, images; String session = "source-main",text="真实主屏文字"; boolean unsolicitedImage, imageUnavailable;
        OwnerScreenContext.ReadCheck hook;
        public OwnerScreenContext.SourceIdentity identity() { return new OwnerScreenContext.SourceIdentity(session, 0); }
        public OwnerScreenContext.Captured capture(boolean includeScreenshot, OwnerScreenContext.ReadCheck check) throws Exception {
            check.check(); reads++; if (hook != null) hook.check(); check.check();
            boolean image = includeScreenshot && !imageUnavailable || unsolicitedImage;
            if (image) images++;
            return new OwnerScreenContext.Captured(identity(), "fixture.notes", "便笺", 42, time.get(), text, "available", false,
                    image ? "available" : includeScreenshot ? "unavailable" : "not_requested",
                    includeScreenshot && !image ? "screenshot_secure_window" : "", image ? "image/png" : "", image ? 1 : 0, image ? 1 : 0, image ? PNG : null);
        }
    }
    private OwnerScreenContext open(Screen source) throws Exception {
        return new OwnerScreenContext(background(), original, (target, live) -> {
            assertSame(original, live);
            if (!hostCurrent.get()) throw new SecurityException("original native host target changed");
        }, source, time::get);
    }
    private static OwnerScreenContext.Failure failure(String code, org.junit.function.ThrowingRunnable call) {
        OwnerScreenContext.Failure failed = assertThrows(OwnerScreenContext.Failure.class, call);
        assertEquals(code, failed.code); return failed;
    }

    @Test public void bindingAndPausedTextPreviewNeverCapturePixelsOrResumeTheOriginalTask() throws Exception {
        Screen screen = new Screen(); original.pause(); long revision = original.pauseRevision();
        try (OwnerScreenContext request = open(screen)) {
            assertEquals(0, screen.reads); assertEquals(0, screen.images);
            OwnerScreenContext.Preview preview = request.capture(false);
            assertEquals(1, screen.reads); assertEquals(0, screen.images); assertFalse(preview.hasScreenshot());
            assertEquals("not_requested", preview.screenshotStatus); assertEquals("真实主屏文字", preview.text);
            assertEquals("background", preview.target.executionMode); assertEquals(7, preview.target.executionDisplayId);
            assertEquals("execution-background", preview.target.executionSessionId);
            assertEquals(0, preview.source.displayId); assertEquals("source-main", preview.source.sessionId);
            request.validate(preview); assertTrue(original.isPaused()); assertEquals(revision, original.pauseRevision()); assertFalse(original.isCancelled());
        }
    }
    @Test public void explicitImagePreviewIsImmutableAndClosingItDoesNotCancelTheOriginalRun() throws Exception {
        Screen screen = new Screen(); OwnerScreenContext request = open(screen);
        OwnerScreenContext.Preview preview = request.capture(true);
        assertEquals(1, screen.images); assertTrue(preview.hasScreenshot());
        byte[] copy = preview.imageBytes(); copy[0] = 0; assertEquals(137, preview.imageBytes()[0] & 255);
        request.close(); assertTrue(preview.isClosed()); assertFalse(preview.hasScreenshot());
        assertThrows(IllegalStateException.class, preview::imageBytes); assertFalse(original.isCancelled()); assertFalse(original.isPaused());
    }
    @Test public void screenshotRefusalKeepsOnlyTheActualTextAndExplicitReason() throws Exception {
        Screen screen = new Screen(); screen.imageUnavailable = true;
        try (OwnerScreenContext request = open(screen)) {
            OwnerScreenContext.Preview preview = request.capture(true);
            assertEquals("unavailable", preview.screenshotStatus); assertEquals("screenshot_secure_window", preview.screenshotReason);
            assertEquals("真实主屏文字", preview.text); assertNull(preview.imageBytes()); assertEquals(0, screen.images);
        }
    }
    @Test public void largeTextPreviewIsBoundedAndExplicitlyMarkedPartialBeforeAnySend() throws Exception {
        Screen screen=new Screen();screen.text="页".repeat(9001);
        try(OwnerScreenContext request=open(screen)) {
            OwnerScreenContext.Preview preview=request.capture(false);
            assertEquals(9000,preview.text.length());assertTrue(preview.truncated);assertEquals(0,screen.images);
        }
    }
    @Test public void destinationReplacementBeforeReadBlocksTheRequestWithoutRetargeting() throws Exception {
        Screen screen = new Screen(); try (OwnerScreenContext request = open(screen)) {
            hostCurrent.set(false); assertThrows(SecurityException.class, () -> request.capture(false));
            assertEquals(0, screen.reads); assertFalse(original.isCancelled());
        }
    }
    @Test public void sourceReconnectionDuringCaptureCannotPublishTheNewScreenAsTheOldSource() throws Exception {
        Screen screen = new Screen(); screen.hook = () -> screen.session = "replacement-service";
        try (OwnerScreenContext request = open(screen)) { failure("source_changed", () -> request.capture(false)); }
        assertFalse(original.isCancelled()); assertEquals(0, screen.images);
    }
    @Test public void aPauseDuringCaptureInvalidatesItWithoutResumingTheOriginal() throws Exception {
        Screen screen = new Screen(); screen.hook = original::pause;
        try (OwnerScreenContext request = open(screen)) { failure("target_changed", () -> request.capture(false)); }
        assertTrue(original.isPaused()); assertFalse(original.isCancelled());
    }
    @Test public void cancellingThePreviewDuringReadDoesNotCancelTheOriginalTask() throws Exception {
        Screen screen = new Screen(); AtomicReference<OwnerScreenContext> current = new AtomicReference<>();
        screen.hook = () -> current.get().close();
        try (OwnerScreenContext request = open(screen)) { current.set(request); failure("capture_cancelled", () -> request.capture(true)); }
        assertFalse(original.isCancelled()); assertFalse(original.isPaused()); assertEquals(0, screen.images);
    }
    @Test public void originalStopDisposesItsPreviewAndBlocksSend() throws Exception {
        Screen screen = new Screen(); try (OwnerScreenContext request = open(screen)) {
            OwnerScreenContext.Preview preview = request.capture(true); original.cancel();
            assertTrue(preview.isClosed()); failure("capture_cancelled", () -> request.validate(preview));
        }
    }
    @Test public void sendRechecksOriginalRunAndMainSourceRatherThanOnlyThePreviewId() throws Exception {
        Screen screen = new Screen(); try (OwnerScreenContext request = open(screen)) {
            OwnerScreenContext.Preview preview = request.capture(false);
            hostCurrent.set(false); assertThrows(SecurityException.class, () -> request.validate(preview));
            hostCurrent.set(true); screen.session = "replacement-service"; failure("source_changed", () -> request.validate(preview));
            assertEquals(1, screen.reads); assertFalse(original.isCancelled());
        }
    }
    @Test public void foreignPreviewExpiryAndDoubleClickCannotReuseOrRecapture() throws Exception {
        Screen first = new Screen(), second = new Screen();
        try (OwnerScreenContext a = open(first); OwnerScreenContext b = open(second)) {
            OwnerScreenContext.Preview originalPreview = a.capture(false), foreign = b.capture(false);
            failure("stale_capture", () -> a.validate(foreign));
            time.addAndGet(120_001); failure("capture_expired", () -> a.validate(originalPreview));
            failure("capture_already_requested", () -> a.capture(true)); assertEquals(1, first.reads); assertEquals(0, first.images);
        }
    }
    @Test public void aSourceCannotSmuggleAnUnrequestedScreenshotIntoTextPreview() throws Exception {
        Screen screen = new Screen(); screen.unsolicitedImage = true;
        try (OwnerScreenContext request = open(screen)) { failure("unexpected_screenshot", () -> request.capture(false)); }
        assertFalse(original.isCancelled());
    }
    @Test public void destinationKindsAndSourceDisplaysCannotExchangeIdentities() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new OwnerScreenContext.Target("task", "run", 1, "background", "main", 0));
        assertThrows(IllegalArgumentException.class, () -> new OwnerScreenContext.Target("task", "run", 1, "capabilities", "invented", 0));
        assertThrows(IllegalArgumentException.class, () -> new OwnerScreenContext.SourceIdentity("background", 7));
        OwnerScreenContext.Target assistant = new OwnerScreenContext.Target("task", "run", 1, "capabilities", null, -1);
        try (OwnerScreenContext request = new OwnerScreenContext(assistant, original, (target, c) -> { }, new Screen(), time::get)) {
            assertEquals(-1, request.capture(false).target.executionDisplayId);
        }
        OwnerScreenContext.Target wrongMain = new OwnerScreenContext.Target("task", "run", 1, "main_screen", "old-service", 0);
        original.pause();
        failure("source_changed", () -> new OwnerScreenContext(wrongMain, original, (target, c) -> { }, new Screen(), time::get));
        RunControl foreign = new RunControl(); foreign.bindHostRun("task", "new-run", 2);
        failure("target_changed", () -> new OwnerScreenContext(background(), foreign, (target, c) -> { }, new Screen(), time::get));
    }

    @Test public void mainScreenRequiresOwnerPauseAtCaptureAndAgainBeforeSend() throws Exception {
        Screen screen = new Screen();
        OwnerScreenContext.Target main = new OwnerScreenContext.Target("task", "run", 1, "main_screen", "source-main", 0);
        failure("owner_pause_required", () -> new OwnerScreenContext(main, original, (target, live) -> { }, screen, time::get));
        assertEquals(0, screen.reads);
        original.pause();
        try (OwnerScreenContext request = new OwnerScreenContext(main, original, (target, live) -> { }, screen, time::get)) {
            long revision = original.pauseRevision(); original.resume();
            assertEquals(revision, original.pauseRevision());
            failure("owner_pause_required", () -> request.capture(false));
            assertEquals(0, screen.reads);
        }
        original.pause();
        try (OwnerScreenContext request = new OwnerScreenContext(main, original, (target, live) -> { }, screen, time::get)) {
            OwnerScreenContext.Preview preview = request.capture(true); original.resume();
            failure("owner_pause_required", () -> request.validate(preview));
            assertEquals(1, screen.reads); assertEquals(1, screen.images);
        }
        assertFalse(original.isCancelled());
    }
}
