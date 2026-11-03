package dev.pocketengine.runtime.models;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local admission and invalidation only. SettingsStore remains the model authority. */
public final class ModelSelectionCoordinator {
    public record Status(long revision, boolean pending) { }
    public record Projection<T>(long revision,T value) { }
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Mutation> executing = new ThreadLocal<>();
    private long revision;
    private Mutation pending;

    public synchronized Status status() { return new Status(revision, pending != null); }

    /** Only local reads belong here. Mutations are executed once, then callers request a fresh projection. */
    public <T> Projection<T> readStable(Callable<T> readOnly)throws Exception {
        for(int attempt=0;attempt<3;attempt++){
            Status before=status();
            if(before.pending())throw new IllegalStateException("模型设置正在保存，请等候当前回执后读取。");
            T value=readOnly.call();Status after=status();
            if(before.equals(after))return new Projection<>(after.revision(),value);
        }
        throw new IllegalStateException("模型设置正在另一界面变化，请重新读取最新选择。");
    }

    /** Observers enqueue a fresh read on their own dispatcher; no configuration or credential is cached here. */
    public AutoCloseable observe(Runnable observer) {
        observers.add(Objects.requireNonNull(observer));
        return () -> observers.remove(observer);
    }

    /** Reserve before submitting to a worker, so another Activity cannot start with the previous selection. */
    public Mutation beginMutation() { return reserve(-1); }
    /** Visible picker rows may additionally fence the exact revision they have actually read. */
    public Mutation beginMutation(long expectedRevision) {
        if(expectedRevision<0)throw new IllegalStateException("请先读取当前模型设置后再保存。");
        return reserve(expectedRevision);
    }
    private Mutation reserve(long expectedRevision) {
        Mutation mutation;
        synchronized (this) {
            if (pending != null) throw new IllegalStateException("模型设置正在保存，请等待原操作回执后再继续。");
            if(expectedRevision>=0&&expectedRevision!=revision)throw new IllegalStateException("模型设置已在另一界面变化，请读取最新选择后再保存。");
            pending = mutation = new Mutation(); revision++;
        }
        notifyObservers();
        return mutation;
    }

    /** A newly admitted task must not capture a value while a prior owner write is still queued. */
    public synchronized void requireAvailable() {
        if (pending != null && executing.get() != pending)
            throw new IllegalStateException("模型设置正在保存，请等待完成后再启动新任务。");
    }

    /** Called after confirmed storage changes, including changes made outside a visible picker. */
    public void changed() {
        synchronized (this) { revision++; }
        notifyObservers();
    }

    private void notifyObservers() {
        for (Runnable observer : observers) try { observer.run(); } catch (RuntimeException ignored) { /* One closed surface cannot hide a commit from the others. */ }
    }

    public final class Mutation implements AutoCloseable {
        private boolean started;
        private boolean finished;
        private Mutation() { }

        /** Single execution at the actual worker boundary; a late or duplicated runnable cannot write. */
        public <T> T run(Callable<T> work) throws Exception {
            Objects.requireNonNull(work);
            synchronized (ModelSelectionCoordinator.this) {
                if (pending != this || started || finished) throw new IllegalStateException("原模型保存操作已结束或未被接受，请读取当前选择。");
                started = true;
            }
            executing.set(this);
            try { return work.call(); }
            finally {
                executing.remove();
                synchronized (ModelSelectionCoordinator.this) { finished = true; if (pending == this) pending = null; revision++; }
                notifyObservers();
            }
        }

        /** Cancel only an unstarted reservation. Already executing storage work must publish its real outcome. */
        @Override public void close() {
            boolean changed = false;
            synchronized (ModelSelectionCoordinator.this) {
                if (!started && !finished) { finished = true; if (pending == this) pending = null; revision++; changed = true; }
            }
            if (changed) notifyObservers();
        }
    }
}
