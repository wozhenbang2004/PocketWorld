package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.extensions.ExtensionProgram;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native source admission for one real run. Constructs data only; never initializes/describes/evaluates JS. */
public final class ExecutableExtensionSource implements AutoCloseable {
    @FunctionalInterface public interface NativeRunAuthority {
        /** Read-only current TaskStore/task/run/epoch/live-control admission for the actual operation phase. */
        void check(ExtensionRuntime.Binding binding, RunControl control) throws Exception;
    }
    public interface SelectionPolicy {
        List<ExecutablePluginCatalog.ApprovedSource> select(ExtensionRuntime.Binding binding, List<ExecutablePluginCatalog.ApprovedSource> available, RunControl control) throws Exception;
        /** Read-only identity/currentness, not tool dispatch admission (also valid during original prepared settlement). */
        void checkCurrent(ExtensionRuntime.Binding binding, RunControl control) throws Exception;
    }
    private final SelectionPolicy selection;
    private final ExecutablePluginCatalog catalog;
    private final NativeRunAuthority authority;
    private final int maxMillis;
    private final AtomicBoolean listing = new AtomicBoolean();
    private final Map<ExtensionProgram, Issued> issued = new IdentityHashMap<>();
    private ExtensionRuntime.Binding run;
    private RunControl liveControl;
    private List<ExtensionProgram> programs;
    private boolean closed;

    /** maxMillis is a fixed native owner policy, never read from package/catalog/model JSON. */
    public ExecutableExtensionSource(ExecutablePluginCatalog catalog, NativeRunAuthority authority, int maxMillis) {
        this(catalog, authority, maxMillis, new SelectionPolicy() {
            @Override public List<ExecutablePluginCatalog.ApprovedSource> select(ExtensionRuntime.Binding binding, List<ExecutablePluginCatalog.ApprovedSource> available, RunControl control) { return available; }
            @Override public void checkCurrent(ExtensionRuntime.Binding binding, RunControl control) { }
        });
    }
    public ExecutableExtensionSource(ExecutablePluginCatalog catalog, NativeRunAuthority authority, int maxMillis, SelectionPolicy selection) {
        this.catalog = Objects.requireNonNull(catalog); this.authority = Objects.requireNonNull(authority); this.selection = Objects.requireNonNull(selection);
        if (maxMillis < 50 || maxMillis > 2000) throw new IllegalArgumentException("扩展单次上限需为50至2000ms的原生策略");
        this.maxMillis = maxMillis;
    }

    /** First successful admission freezes this run's exact set. Re-reading never refreshes old approvals. */
    public List<ExtensionProgram> approved(ExtensionRuntime.Binding binding, RunControl control) throws Exception {
        Objects.requireNonNull(binding); Objects.requireNonNull(control);
        if (!listing.compareAndSet(false, true)) throw new IllegalStateException("当前扩展来源仍在核验");
        try {
            final List<Issued> selected = new ArrayList<>(); final boolean initial;
            synchronized (this) {
                open(); initial = programs == null;
                if (!initial) { sameRun(binding, control); for (ExtensionProgram program : programs) selected.add(issued.get(program)); }
            }
            long pause = control.pauseRevision(); runFence(binding, control, pause);
            if (initial) {
                List<ExecutablePluginCatalog.ApprovedSource> sources = selection.select(binding, catalog.approvedSources(control), control);
                if (sources.size() > ExtensionRuntime.MAX_EXTENSIONS) throw new IllegalStateException("本任务选用的JS扩展超过16个上限，请到原任务的技能与工具中明确选用");
                for (ExecutablePluginCatalog.ApprovedSource source : sources) {
                    // The real constructor rejects invalid UTF-8/NUL/identity/budget; no repair, trim or re-hash.
                    ExtensionProgram program = new ExtensionProgram(source.id, source.version, source.installationId,
                            source.approvalRevision, source.exactSourceBytes(), source.sourceSha256, maxMillis);
                    selected.add(new Issued(program, source));
                }
            }
            for (Issued entry : selected) currentSource(entry, control);
            runFence(binding, control, pause);
            // A permission change during the native run callback must also be observed before publication.
            for (Issued entry : selected) currentSource(entry, control);
            localFence(binding, control, pause);
            synchronized (this) {
                open();
                if (initial) {
                    run = binding; liveControl = control; List<ExtensionProgram> admitted = new ArrayList<>();
                    for (Issued entry : selected) { issued.put(entry.program, entry); admitted.add(entry.program); }
                    programs = List.copyOf(admitted);
                } else sameRun(binding, control);
                return programs;
            }
        } finally { listing.set(false); }
    }

    /** Call from the actual ExtensionRuntime.Authority before initialize/describe and every later boundary. */
    public void checkCurrent(ExtensionProgram program, ExtensionRuntime.Binding binding, RunControl control) throws Exception {
        Objects.requireNonNull(binding); Objects.requireNonNull(control); final Issued entry;
        synchronized (this) {
            open(); sameRun(binding, control); entry = issued.get(program);
            if (entry == null) throw new SecurityException("扩展程序不是本次原生来源核验返回的对象");
        }
        long pause = control.pauseRevision(); runFence(binding, control, pause);
        currentSource(entry, control); runFence(binding, control, pause); currentSource(entry, control);
        localFence(binding, control, pause);
        synchronized (this) { open(); sameRun(binding, control); }
    }
    /** Source/currentness only, usable while the original Runner owns a prepared effect.
     * This is not task/grant/operation admission; that remains in the original execution host. */
    public void checkSourceCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl control)throws Exception {
        final Issued entry;synchronized(this){open();sameRun(binding,control);entry=issued.get(program);if(entry==null)throw new SecurityException("扩展不是原执行代次的来源");}
        long pause=control.pauseRevision();localFence(binding,control,pause);selection.checkCurrent(binding,control);currentSource(entry,control);selection.checkCurrent(binding,control);localFence(binding,control,pause);
        synchronized(this){open();sameRun(binding,control);}
    }

    private void currentSource(Issued entry, RunControl control) throws Exception {
        ExtensionProgram program = entry.program; ExecutablePluginCatalog.ApprovedSource source = entry.source;
        if (!source.id.equals(program.id) || !source.version.equals(program.version) || !source.installationId.equals(program.installationId)
                || source.approvalRevision != program.approvalRevision || !source.sourceSha256.equals(program.sourceSha256)
                || program.maxMillis != maxMillis || !MessageDigest.isEqual(source.exactSourceBytes(), program.sourceBytes()))
            throw new SecurityException("扩展程序身份、源码或原生策略与原审阅不符");
        catalog.checkCurrent(source, control);
    }
    private void runFence(ExtensionRuntime.Binding binding, RunControl control, long pause) throws Exception {
        localFence(binding, control, pause); authority.check(binding, control); selection.checkCurrent(binding, control); localFence(binding, control, pause);
    }
    private static void localFence(ExtensionRuntime.Binding binding, RunControl control, long pause) throws Exception {
        control.check();
        if (!control.hasHostRun() || !control.matchesHostRun(binding.taskId, binding.runId, binding.epoch)
                || control.isPaused() || control.pauseRevision() != pause) throw new SecurityException("扩展的原任务控制或暂停现场已变化");
    }
    private void sameRun(ExtensionRuntime.Binding binding, RunControl control) {
        if (run == null || liveControl != control || !run.taskId.equals(binding.taskId) || !run.runId.equals(binding.runId) || run.epoch != binding.epoch)
            throw new SecurityException("原扩展程序不能跨任务、执行代次或当前控制使用");
    }
    private void open() { if (closed) throw new SecurityException("本次扩展来源已关闭"); }
    @Override public synchronized void close() { closed = true; issued.clear(); programs = null; liveControl = null; run = null; }
    private static final class Issued {
        final ExtensionProgram program;
        final ExecutablePluginCatalog.ApprovedSource source;
        Issued(ExtensionProgram program, ExecutablePluginCatalog.ApprovedSource source) { this.program = program; this.source = source; }
    }
}
