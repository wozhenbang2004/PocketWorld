package dev.pocketengine.runtime.models;

import dev.pocketengine.core.RunControl;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One diagnostic's shrinking elapsed-time budget, attached to its original cancellation control. */
public final class ModelProbeDeadline implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "model-probe-deadline"); thread.setDaemon(true); return thread;
    });
    public static final class Exceeded extends InterruptedException {
        public final String stage;
        private Exceeded(String stage) { super("能力诊断整体期限已到（" + stage + "）；已停止当前连接及后续阶段，原配置保留。"); this.stage = stage; }
    }
    private final RunControl original;
    private final LongSupplier nanos;
    private final long start, duration, deadlineAtMillis;
    private ScheduledFuture<?> timer;
    private boolean closed, expired;
    private String stage = "text";

    public ModelProbeDeadline(RunControl original, long totalMillis) { this(original, totalMillis, System::nanoTime, true); }
    ModelProbeDeadline(RunControl original, long totalMillis, LongSupplier nanos, boolean schedule) {
        if (totalMillis < 1 || totalMillis > 120000) throw new IllegalArgumentException("invalid_model_probe_deadline");
        this.original = Objects.requireNonNull(original); this.nanos = Objects.requireNonNull(nanos);
        start = nanos.getAsLong(); duration = TimeUnit.MILLISECONDS.toNanos(totalMillis); deadlineAtMillis = Math.addExact(System.currentTimeMillis(),totalMillis);
        if (schedule) timer = TIMER.schedule(this::expire, totalMillis, TimeUnit.MILLISECONDS);
    }
    public synchronized void stage(String value) throws InterruptedException {
        if (!java.util.Set.of("text", "tools", "image").contains(value)) throw new IllegalArgumentException("invalid_probe_stage");
        check(); stage = value;
    }
    public synchronized long remainingMillis() throws InterruptedException {
        check(); return Math.max(1, TimeUnit.NANOSECONDS.toMillis(duration - (nanos.getAsLong() - start)));
    }
    public long deadlineAtMillis() { return deadlineAtMillis; }
    public synchronized void check() throws InterruptedException {
        if (closed) throw new IllegalStateException("model_probe_deadline_closed");
        if (expired || nanos.getAsLong() - start >= duration) { expired = true; original.cancel(); throw new Exceeded(stage); }
        original.check();
    }
    private synchronized void expire() {
        if (closed || original.isCancelled()) return;
        long remaining = duration - (nanos.getAsLong() - start);
        if (remaining > 0) { timer = TIMER.schedule(this::expire, remaining, TimeUnit.NANOSECONDS); return; }
        expired = true; original.cancel();
    }
    @Override public synchronized void close() { closed = true; if (timer != null) timer.cancel(false); }
}
