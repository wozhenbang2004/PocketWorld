package dev.pocketengine.features.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Bounded process-local ownership of original runners. TaskStore owns every durable input and run. */
public final class ConversationTurnSlots<T> {
    private enum Phase { QUEUED, STARTING, RUNNING, RELEASED }

    public static final class Lease<T> {
        private final String conversation;
        private final T original;
        private volatile Phase phase = Phase.QUEUED;
        private Runnable action, rejected, scheduled;
        private Lease(String conversation, T original) { this.conversation = conversation; this.original = original; }
        public String conversationId() { return conversation; }
        public T original() { return original; }
        public String resourceState() { return phase == Phase.RUNNING ? "running" : phase == Phase.RELEASED ? "released" : "queued"; }
    }

    private final int parallelism, queueLimit;
    private final Executor executor;
    private final LinkedHashMap<String, Lease<T>> leases = new LinkedHashMap<>();
    private int occupied;
    private boolean closed;

    public ConversationTurnSlots(int parallelism, int queueLimit, Executor executor) {
        if (parallelism < 1 || queueLimit < 0) throw new IllegalArgumentException("Invalid conversation slot limits");
        this.parallelism = parallelism; this.queueLimit = queueLimit; this.executor = Objects.requireNonNull(executor);
    }

    /** Reserve before saving a new message, so an over-capacity request remains the owner's draft. */
    public synchronized Lease<T> reserve(String conversation, T original) {
        if (closed) throw new IllegalStateException("聊天执行器已关闭，未接收新消息");
        if (conversation == null || conversation.isBlank()) throw new IllegalArgumentException("原对话身份缺失");
        if (leases.containsKey(conversation)) throw new IllegalStateException("原对话仍在执行，请追加到原任务");
        if (leases.size() >= parallelism + queueLimit) throw new IllegalStateException("聊天执行与等待位置已满，请保留草稿，稍后再发送");
        Lease<T> lease = new Lease<>(conversation, Objects.requireNonNull(original));
        leases.put(conversation, lease);
        return lease;
    }

    /** Submit only after the original message, task binding and ACK are durable. */
    public void submit(Lease<T> lease, Runnable action, Runnable rejected) {
        synchronized (this) {
            requireCurrent(lease);
            if (lease.action != null || lease.phase != Phase.QUEUED) throw new IllegalStateException("原聊天已交给执行器");
            lease.action = Objects.requireNonNull(action); lease.rejected = Objects.requireNonNull(rejected);
        }
        dispatch();
    }

    /** Compatibility for a synchronous legacy host; it cannot create an unjournaled wait queue. */
    public synchronized void beginInline(Lease<T> lease) {
        requireCurrent(lease);
        if (lease.action != null || lease.phase != Phase.QUEUED || occupied >= parallelism)
            throw new IllegalStateException("聊天执行位置繁忙，请保留草稿，稍后再发送");
        lease.phase = Phase.RUNNING; occupied++;
    }

    public synchronized Lease<T> get(String conversation) { return leases.get(conversation); }
    public synchronized boolean current(Lease<T> lease) { return lease != null && leases.get(lease.conversation) == lease; }
    public synchronized List<Lease<T>> snapshot() { return List.copyOf(leases.values()); }
    public synchronized int activeCount() { return occupied; }
    public synchronized int queuedCount() { return leases.size() - occupied; }
    public synchronized int availableCount() { return closed ? 0 : parallelism + queueLimit - leases.size(); }
    public int parallelism() { return parallelism; }
    public int queueLimit() { return queueLimit; }

    /** Cancellation releases a not-yet-started worker only; running work keeps its slot until finally. */
    public boolean discardQueued(Lease<T> lease) {
        synchronized (this) {
            if (!current(lease) || lease.phase == Phase.RUNNING) return false;
            remove(lease);
        }
        dispatch();
        return true;
    }

    /** Exact object identity prevents an old finally from releasing a replacement in the same conversation. */
    public boolean release(Lease<T> lease) {
        synchronized (this) {
            if (!current(lease)) return false;
            remove(lease);
        }
        dispatch();
        return true;
    }

    /** Caller cancels these same original controls. Queued callbacks cannot start after this boundary. */
    public synchronized List<T> close() {
        if (closed) return List.of();
        closed = true;
        List<T> originals = new ArrayList<>();
        for (Lease<T> lease : new ArrayList<>(leases.values())) { originals.add(lease.original); remove(lease); }
        return List.copyOf(originals);
    }

    private void requireCurrent(Lease<T> lease) {
        if (closed || !current(lease)) throw new IllegalStateException("原聊天位置已释放，未启动旧回调");
    }

    private void remove(Lease<T> lease) {
        leases.remove(lease.conversation, lease);
        if (lease.phase == Phase.STARTING || lease.phase == Phase.RUNNING) occupied--;
        lease.phase = Phase.RELEASED;
        if (lease.scheduled != null && executor instanceof ThreadPoolExecutor pool) pool.remove(lease.scheduled);
    }

    private void dispatch() {
        List<Lease<T>> ready = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            for (Lease<T> lease : leases.values()) {
                if (occupied >= parallelism) break;
                if (lease.phase != Phase.QUEUED || lease.action == null) continue;
                occupied++; lease.phase = Phase.STARTING;
                lease.scheduled = () -> execute(lease);
                ready.add(lease);
            }
        }
        for (Lease<T> lease : ready) {
            try { executor.execute(lease.scheduled); }
            catch (RuntimeException unavailable) {
                Runnable rejected = null;
                synchronized (this) { if (current(lease)) { remove(lease); rejected = lease.rejected; } }
                try { if (rejected != null) rejected.run(); }
                catch (RuntimeException failureToReport) { /* The original durable admission remains available for reconciliation. */ }
                finally { dispatch(); }
            }
        }
    }

    private void execute(Lease<T> lease) {
        synchronized (this) {
            if (closed || !current(lease) || lease.phase != Phase.STARTING) return;
            lease.phase = Phase.RUNNING;
        }
        try { lease.action.run(); }
        finally { release(lease); }
    }
}
