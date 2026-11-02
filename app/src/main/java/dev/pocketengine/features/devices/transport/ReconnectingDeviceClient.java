package dev.pocketengine.features.devices.transport;

import java.io.IOException;

/** Explicit device-access lifecycle, reconnecting CHANNELS only. Never owns application frames.
 * Factory must create a fresh pinned TLS context/connection. onOnline re-authorizes and queries
 * original operation/task IDs in the caller's existing store; it must not submit them again. */
public final class ReconnectingDeviceClient implements AutoCloseable, Runnable {
    public interface Factory { DeviceConnection connect() throws Exception; }
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES;
    static {
        DEADLINES = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> { Thread thread = new Thread(runnable, "device-access-deadline"); thread.setDaemon(true); return thread; });
        DEADLINES.setRemoveOnCancelPolicy(true);
    }
    private final Factory factory;
    private final long accessDeadline;
    private final DeviceReconnect backoff = new DeviceReconnect();
    private volatile boolean closed;
    private volatile DeviceConnection active;
    private volatile boolean connecting;
    private volatile DeviceConnection.State lastState = DeviceConnection.State.OFFLINE;
    private volatile Thread worker;
    public ReconnectingDeviceClient(boolean ownerEnabled, long accessDeadlineMillis, Factory factory) {
        if (!ownerEnabled || accessDeadlineMillis <= System.currentTimeMillis()) throw new SecurityException("Device access is disabled or expired");
        accessDeadline = accessDeadlineMillis; this.factory = java.util.Objects.requireNonNull(factory);
    }
    @Override public void run() {
        if (worker != null) throw new IllegalStateException("Device client already started"); worker = Thread.currentThread();
        var deadline = DEADLINES.schedule(() -> { try { close(); } catch (IOException ignored) { } }, Math.max(0, accessDeadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
        while (!closed && System.currentTimeMillis() < accessDeadline && !Thread.currentThread().isInterrupted()) {
            try {
                connecting = true;
                DeviceConnection connection = factory.connect(); active = connection; connecting = false;
                if (closed || System.currentTimeMillis() >= accessDeadline) { connection.close(); break; }
                // A connection returns only after closing and clearing all of its outgoing frames.
                connection.run(); lastState = connection.state();
                if (connection.wasAuthenticated()) backoff.authenticated();
                if (connection.state() == DeviceConnection.State.REVOKED || connection.state() == DeviceConnection.State.INCOMPATIBLE) { closed = true; break; }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            catch (Exception unavailable) { lastState = DeviceConnection.State.OFFLINE; /* Retry connection establishment only. No invocation enters this class. */ }
            finally { connecting = false; DeviceConnection old = active; active = null; if (old != null) try { old.close(); } catch (IOException ignored) { } }
            if (closed) break;
            long remaining = accessDeadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try { Thread.sleep(Math.min(remaining, backoff.disconnectedDelayMillis())); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        closed = true;
        } finally { closed = true; connecting = false; deadline.cancel(false); }
    }
    /** Android's current network/lifecycle callback invalidates the OLD channel immediately. */
    public void networkChanged() throws IOException { DeviceConnection old = active; if (old != null) old.close(); }
    public DeviceConnection active() { return active; }
    public boolean isRunning() { Thread thread = worker; return !closed && thread != null && thread.isAlive(); }
    public DeviceConnection.State transportState() { DeviceConnection current = active; return current != null ? current.state() : connecting ? DeviceConnection.State.CONNECTING : lastState; }
    @Override public void close() throws IOException { closed = true; DeviceConnection old = active; if (old != null) old.close(); Thread thread = worker; if (thread != null && thread != Thread.currentThread()) thread.interrupt(); }
}
