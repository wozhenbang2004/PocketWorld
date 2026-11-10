package dev.pocketengine.phone;

import dev.pocketengine.core.RunControl;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One explicit, local main-screen preview. It owns no task, runner, port lease or model request. */
public final class OwnerScreenContext implements AutoCloseable {
    private static final long MAX_AGE_MS = 120_000;
    static final int MAX_TEXT_CHARS = 9_000;

    /** The destination's execution identity is independent of the main-screen capture source. */
    public static final class Target {
        public final String taskId, runId, executionMode, executionSessionId;
        public final int epoch, executionDisplayId;
        public Target(String taskId, String runId, int epoch, String executionMode,
                      String executionSessionId, int executionDisplayId) {
            this.taskId = required(taskId, 100); this.runId = required(runId, 100);
            if (epoch < 1) throw new IllegalArgumentException("缺少原任务执行代次");
            this.epoch = epoch; this.executionMode = required(executionMode, 30);
            if ("capabilities".equals(executionMode)) {
                if (executionSessionId != null && !executionSessionId.isEmpty() || executionDisplayId != -1)
                    throw new IllegalArgumentException("普通助手任务不具有手机显示身份");
                this.executionSessionId = null;
            } else {
                if (!"main_screen".equals(executionMode) && !"background".equals(executionMode)
                        || "main_screen".equals(executionMode) && executionDisplayId != 0
                        || "background".equals(executionMode) && executionDisplayId <= 0)
                    throw new IllegalArgumentException("原手机任务执行位置无效");
                this.executionSessionId = required(executionSessionId, 100);
            }
            this.executionDisplayId = executionDisplayId;
        }
    }

    public static final class SourceIdentity {
        public final String sessionId;
        public final int displayId;
        public SourceIdentity(String sessionId, int displayId) {
            this.sessionId = required(sessionId, 100);
            if (displayId != 0) throw new IllegalArgumentException("补充画面只能来自明确选择的手机主屏");
            this.displayId = displayId;
        }
        private boolean same(SourceIdentity other) {
            return other != null && displayId == other.displayId && sessionId.equals(other.sessionId);
        }
    }

    @FunctionalInterface public interface BindingCheck { void check(Target target, RunControl original) throws Exception; }
    @FunctionalInterface public interface ReadCheck { void check() throws Exception; }
    public interface Source {
        /** Connection metadata only: this must never capture an image or enumerate screen contents. */
        SourceIdentity identity() throws Exception;
        Captured capture(boolean includeScreenshot, ReadCheck check) throws Exception;
    }

    /** Native source result; screenshot bytes are copied, bounded, and released after preview admission. */
    public static final class Captured implements AutoCloseable {
        public final SourceIdentity source;
        public final String packageName, appLabel, text, textStatus, screenshotStatus, screenshotReason, imageMimeType;
        public final int windowId, imageWidth, imageHeight;
        public final long observedAtUptimeMs;
        public final boolean truncated;
        private byte[] image;
        public Captured(SourceIdentity source, String packageName, String appLabel, int windowId,
                        long observedAtUptimeMs, String text, String textStatus, boolean truncated,
                        String screenshotStatus, String screenshotReason, String imageMimeType,
                        int imageWidth, int imageHeight, byte[] image) {
            this.source = Objects.requireNonNull(source);
            this.packageName = bounded(packageName, 250); this.appLabel = bounded(appLabel, 250);
            this.windowId = windowId; this.observedAtUptimeMs = observedAtUptimeMs;
            this.text = bounded(text, MAX_TEXT_CHARS); this.textStatus = required(textStatus, 80);
            this.truncated = truncated || text != null && text.length() > MAX_TEXT_CHARS;
            this.screenshotStatus = required(screenshotStatus, 80);
            this.screenshotReason = bounded(screenshotReason, 100); this.imageMimeType = bounded(imageMimeType, 100);
            this.imageWidth = imageWidth; this.imageHeight = imageHeight;
            if (image != null && (image.length == 0 || image.length > 4 * 1024 * 1024
                    || !"available".equals(screenshotStatus) || !"image/jpeg".equals(imageMimeType) && !"image/png".equals(imageMimeType)
                    || !imageHeader(image, imageMimeType) || imageWidth <= 0 || imageHeight <= 0 || imageWidth > 8192 || imageHeight > 8192))
                throw new IllegalArgumentException("主屏截图回执无效");
            if (image == null && ("available".equals(screenshotStatus) || imageWidth != 0 || imageHeight != 0))
                throw new IllegalArgumentException("主屏截图没有真实图片字节");
            this.image = image == null ? null : image.clone();
        }
        @Override public synchronized void close() { if (image != null) Arrays.fill(image, (byte) 0); image = null; }
        private synchronized byte[] imageCopy() { return image == null ? null : image.clone(); }
    }

    /** Immutable capture metadata. Image access is local and explicit, and becomes unavailable on close. */
    public static final class Preview implements AutoCloseable {
        public final String id = UUID.randomUUID().toString();
        public final Target target;
        public final SourceIdentity source;
        public final String packageName, appLabel, text, textStatus, screenshotStatus, screenshotReason, imageMimeType;
        public final int windowId, imageWidth, imageHeight;
        public final long observedAtUptimeMs, pauseRevision;
        public final boolean truncated;
        private byte[] image;
        private boolean closed;
        private Preview(Target target, Captured captured, long revision) {
            this.target = target; this.source = captured.source; this.pauseRevision = revision;
            this.packageName = captured.packageName; this.appLabel = captured.appLabel;
            this.windowId = captured.windowId; this.observedAtUptimeMs = captured.observedAtUptimeMs;
            this.text = captured.text; this.textStatus = captured.textStatus; this.truncated = captured.truncated;
            this.screenshotStatus = captured.screenshotStatus; this.screenshotReason = captured.screenshotReason;
            this.imageMimeType = captured.imageMimeType; this.imageWidth = captured.imageWidth; this.imageHeight = captured.imageHeight;
            this.image = captured.imageCopy();
        }
        public synchronized byte[] imageBytes() {
            if (closed) throw new IllegalStateException("画面预览已关闭");
            return image == null ? null : image.clone();
        }
        public synchronized boolean hasScreenshot() { return !closed && image != null; }
        public synchronized boolean isClosed() { return closed; }
        @Override public synchronized void close() {
            closed = true; if (image != null) Arrays.fill(image, (byte) 0); image = null;
        }
    }

    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code, String message) { super(message); this.code = code; }
    }

    private final Target target;
    private final RunControl original;
    private final BindingCheck binding;
    private final Source source;
    private final SourceIdentity sourceIdentity;
    private final LongSupplier uptime;
    private final long revision;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final Runnable cancelled = this::close;
    private volatile Preview preview;

    /** Binding only. The caller invokes capture on its worker after the owner's explicit action. */
    public OwnerScreenContext(Target target, RunControl original, BindingCheck binding, Source source, LongSupplier uptime) throws Exception {
        this.target = Objects.requireNonNull(target); this.original = Objects.requireNonNull(original);
        this.binding = Objects.requireNonNull(binding); this.source = Objects.requireNonNull(source);
        this.uptime = Objects.requireNonNull(uptime); this.revision = original.pauseRevision();
        checkTarget(); this.sourceIdentity = Objects.requireNonNull(source.identity());
        if ("main_screen".equals(target.executionMode) && !target.executionSessionId.equals(sourceIdentity.sessionId))
            throw new Failure("source_changed", "原主屏任务与当前读取连接不一致");
        original.onCancel(cancelled);
        try { check(); } catch (Exception failure) { close(); throw failure; }
    }

    public Preview capture(boolean includeScreenshot) throws Exception {
        if (!started.compareAndSet(false, true)) throw new Failure("capture_already_requested", "本次画面已请求，请关闭后重新选择");
        try { check(); } catch (Exception failure) { close(); throw failure; }
        try (Captured captured = source.capture(includeScreenshot, this::check)) {
            if (captured == null) throw new Failure("empty_capture", "主屏没有返回画面内容");
            check();
            long age = uptime.getAsLong() - captured.observedAtUptimeMs;
            if (!sourceIdentity.same(captured.source) || age < 0 || age > MAX_AGE_MS)
                throw new Failure("stale_capture", "画面不属于本次主屏连接，请重新选择");
            if (!includeScreenshot && (!"not_requested".equals(captured.screenshotStatus) || captured.image != null))
                throw new Failure("unexpected_screenshot", "本次没有请求截图，已阻止接收图片");
            Preview next = new Preview(target, captured, revision);
            synchronized (this) {
                if (closed.get()) { next.close(); throw new Failure("capture_cancelled", "已取消本次画面"); }
                preview = next;
            }
            try { validate(next); return next; } catch (Exception failure) { close(); throw failure; }
        } catch (Exception failure) { close(); throw failure; }
    }

    /** Recheck immediately before the native host's separately fenced durable input write. */
    public void validate(Preview selected) throws Exception {
        if (closed.get()) throw new Failure("capture_cancelled", "已取消本次画面");
        checkTarget();
        if (selected == null || selected != preview || selected.target != target || selected.isClosed()
                || !sourceIdentity.same(selected.source) || selected.pauseRevision != revision)
            throw new Failure("stale_capture", "预览不属于当前原任务，请重新选择");
        // This is fixed owner-selected reference material, not an actionable live
        // observation. Reading/editing it does not expire its pixels or text.
        // Target authority, sharing policy and cancellation are still checked.
    }

    private void checkTarget() throws Exception {
        original.check();
        if (!original.hasHostRun() || !original.matchesHostRun(target.taskId, target.runId, target.epoch))
            throw new Failure("target_changed", "原任务执行身份已变化");
        binding.check(target, original);
        original.check();
        if ("main_screen".equals(target.executionMode) && !original.isPaused())
            throw new Failure("owner_pause_required", "主屏任务已不在主人暂停接管状态，请在原任务暂停后重新选择画面");
        if (original.pauseRevision() != revision) throw new Failure("target_changed", "原任务经历了暂停，请重新选择画面");
    }
    private void check() throws Exception {
        if (closed.get()) throw new Failure("capture_cancelled", "已取消本次画面");
        checkTarget();
        if (!sourceIdentity.same(source.identity())) throw new Failure("source_changed", "主屏读取连接已变化，请重新选择画面");
        if (closed.get()) throw new Failure("capture_cancelled", "已取消本次画面");
    }
    @Override public void close() {
        closed.set(true); original.removeCancelAction(cancelled);
        synchronized (this) { Preview held = preview; preview = null; if (held != null) held.close(); }
    }
    private static String required(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("缺少有效的原生身份或状态");
        return value;
    }
    private static String bounded(String value, int maximum) { return value == null ? "" : value.substring(0, Math.min(value.length(), maximum)); }
    private static boolean imageHeader(byte[] bytes, String mime) {
        if ("image/jpeg".equals(mime)) return bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
        return bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
    }
}
