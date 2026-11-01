package dev.pocketengine.vision.ocr;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** At most one native job, including one whose caller stopped waiting. No unbounded retry queue. */
final class OcrJobRunner implements AutoCloseable {
    interface Work { OcrPage run(StopSignal signal) throws Exception; }
    static final class StopSignal {
        private final RunControl control;
        private final long deadline;
        private boolean stopped;
        private Runnable nativeStop;
        StopSignal(RunControl control, long timeoutMs) {
            this.control = control;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }
        synchronized void stop() {
            stopped = true;
            if (nativeStop != null) try { nativeStop.run(); } catch (RuntimeException | LinkageError ignored) { /* Still suppress all delivery. */ }
        }
        synchronized void installStop(Runnable stop) {
            nativeStop = stop;
            if (stopped) try { nativeStop.run(); } catch (RuntimeException | LinkageError ignored) { /* Worker owns final cleanup. */ }
        }
        /** Cleanup and cross-thread native stop cannot race a recycled JNI handle. */
        synchronized void release(Runnable cleanup) { nativeStop = null; cleanup.run(); }
        synchronized boolean stopped() { return stopped; }
        boolean expired() { return System.nanoTime() - deadline >= 0; }
        void check() throws InterruptedException, DocumentReader.EngineUnavailableException {
            while (true) {
                control.check();
                if (stopped()) throw new InterruptedException("OCR result was abandoned");
                if (expired()) { stop(); throw new DocumentReader.EngineUnavailableException("Local OCR timed out; no recognized result was delivered"); }
                if (!control.isPaused()) return;
                // A close/timeout must also release a job paused at a checkpoint, without cancelling the host run.
                Thread.sleep(50);
            }
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "pocket-local-ocr"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private StopSignal active;

    synchronized boolean open() { return !closed; }

    OcrPage run(Work work, RunControl control, long timeoutMs) throws Exception {
        if (control == null) throw new IllegalArgumentException("Live RunControl required");
        if (timeoutMs < 1 || timeoutMs > 120_000) throw new IllegalArgumentException("Invalid OCR time budget");
        control.check();
        StopSignal signal = new StopSignal(control, timeoutMs);
        Future<OcrPage> future;
        synchronized (this) {
            if (closed) throw new DocumentReader.EngineUnavailableException("Local OCR engine is closed");
            if (active != null) throw new DocumentReader.EngineUnavailableException("Local OCR is busy; a previous native image job is still finishing");
            active = signal;
            try {
                future = worker.submit(() -> {
                    try { signal.check(); return work.run(signal); }
                    finally { synchronized (OcrJobRunner.this) { if (active == signal) active = null; } }
                });
            } catch (RejectedExecutionException unavailable) {
                active = null;
                throw new DocumentReader.EngineUnavailableException("Local OCR worker is unavailable");
            }
        }
        Runnable cancellation = signal::stop;
        control.onCancel(cancellation);
        boolean delivered = false;
        try {
            while (true) {
                signal.check();
                if (!open() || signal.stopped()) throw new InterruptedException("Local OCR stopped");
                if (signal.expired()) throw new DocumentReader.EngineUnavailableException("Local OCR timed out; no recognized result was delivered");
                try {
                    OcrPage page = future.get(75, TimeUnit.MILLISECONDS);
                    signal.check();
                    if (!open() || signal.stopped()) throw new InterruptedException("Local OCR stopped");
                    if (page == null) throw new DocumentReader.EngineUnavailableException("Local OCR returned no result");
                    delivered = true; return page;
                } catch (TimeoutException waiting) {
                    // Check live cancellation/authority rather than waiting indefinitely inside JNI.
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new DocumentReader.EngineUnavailableException("Local OCR native worker failed");
                }
            }
        } finally {
            control.removeCancelAction(cancellation);
            if (!delivered) signal.stop();
            // Never Future.cancel/recycle on this thread: the native worker owns its live image/API.
        }
    }

    @Override public void close() {
        StopSignal pending;
        synchronized (this) { if (closed) return; closed = true; pending = active; worker.shutdown(); }
        if (pending != null) pending.stop();
    }
}
