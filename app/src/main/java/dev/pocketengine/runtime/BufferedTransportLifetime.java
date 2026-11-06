package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import java.net.HttpURLConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation registration and watchdog owned by one buffered HTTP invocation.
 * The gateway retains dispatch, response interpretation and cancellation/error
 * precedence. This owner only disconnects transport and reports deadline expiry.
 */
final class BufferedTransportLifetime implements AutoCloseable {
    private final HttpURLConnection connection;
    private final RunControl control;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean expired = new AtomicBoolean();
    private final CountDownLatch cancellationWake = new CountDownLatch(1);
    private final Runnable disconnect = cancellationWake::countDown;
    private final Thread cancellation;

    private BufferedTransportLifetime(HttpURLConnection connection, RunControl control, long deadline) {
        this.connection = connection;
        this.control = control;
        Thread caller = Thread.currentThread();
        cancellation = new Thread(() -> {
            try {
                while (!finished.get()) {
                    if (control.isCancelled() || caller.isInterrupted() || System.nanoTime() >= deadline) {
                        expired.set(System.nanoTime() >= deadline);
                        connection.disconnect();
                        return;
                    }
                    cancellationWake.await(75, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ignored) { /* Request has already finished. */ }
        }, "pocket-model-cancel");
    }

    static BufferedTransportLifetime start(HttpURLConnection connection, RunControl control, long deadline) {
        BufferedTransportLifetime lifetime = new BufferedTransportLifetime(connection, control, deadline);
        try {
            // All deadline evaluation is complete before installing the callback.
            // A failed thread start must unwind this registration too.
            control.onCancel(lifetime.disconnect);
            lifetime.cancellation.setDaemon(true);
            lifetime.cancellation.start();
            return lifetime;
        } catch (RuntimeException | Error unavailable) {
            try { lifetime.close(); }
            catch (RuntimeException cleanup) { if (cleanup != unavailable) unavailable.addSuppressed(cleanup); }
            throw unavailable;
        }
    }

    boolean expired() { return expired.get(); }

    @Override public void close() {
        // Preserve the existing shutdown order: stop polling, unregister, wake
        // the watchdog by interruption, then disconnect the actual connection.
        finished.set(true);
        control.removeCancelAction(disconnect);
        cancellation.interrupt();
        connection.disconnect();
    }
}
