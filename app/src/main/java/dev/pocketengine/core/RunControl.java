package dev.pocketengine.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** One run's cancellation and pause authority; never reused by a later run. */
public final class RunControl {
    private final boolean recoveryOnly;
    public RunControl(){this(false);}
    private RunControl(boolean recoveryOnly){this.recoveryOnly=recoveryOnly;}
    /** Native identity for an already-recorded operation only. This is never a grant or a
     * new execution admission; callers retain the original source/read/cancel authority. */
    public static RunControl forOriginalRecovery(String task,String run,int epoch){
        if(task==null||task.isEmpty()||run==null||run.isEmpty()||epoch<1)throw new IllegalArgumentException("Original recovery identity required");
        RunControl original=new RunControl(true);original.boundTask=task;original.hostRun=new HostRun(run,epoch);return original;
    }
    public boolean recoveryOnly(){return recoveryOnly;}
    public void requireExecution(){if(recoveryOnly)throw new SecurityException("原操作核对控制不能升级为新执行或重放");}
    public interface AuthorityCheck {void check()throws Exception;}
    /** Native Application hook into its one TaskStore. It only narrows existing run authority. */
    public interface HostAdmission {AuthorityCheck bind(RunControl original,String task,String run,int epoch)throws Exception;}
    private static volatile HostAdmission hostAdmission;
    public static void installHostAdmission(HostAdmission original){hostAdmission=java.util.Objects.requireNonNull(original);}
    private volatile AuthorityCheck executionAdmission;
    /** Native temporary condition, distinct from revoked authority and owner cancellation. */
    public static final class ConditionWaitException extends InterruptedException {
        private final RunControl owner;
        private final String reason;
        private final long validUntil;
        private final boolean automatic;
        private ConditionWaitException(RunControl owner,String reason,long validUntil,boolean automatic){super("原任务等待运行条件");this.owner=owner;this.reason=reason;this.validUntil=validUntil;this.automatic=automatic;}
        public String reason(){return reason;}
        public long validUntil(){return validUntil;}
        public boolean automatic(){return automatic;}
    }
    /** Native effect entry only; the caller still owns its exact operation/resource gate. */
    public interface DispatchEntry {void enter()throws Exception;}
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Object monitor = new Object();
    private volatile boolean paused;
    private volatile boolean ownerPaused;
    private volatile ConditionWaitException conditionWait;
    private final CopyOnWriteArrayList<CancelAction> cancellationActions = new CopyOnWriteArrayList<>();
    private final AtomicLong pauseRevision = new AtomicLong();
    private final AtomicLong modelReplanRevision = new AtomicLong();
    private final CopyOnWriteArrayList<ModelRequest> modelRequests = new CopyOnWriteArrayList<>();
    private volatile AuthorityCheck authorityCheck;
    private final CopyOnWriteArrayList<AuthorityCheck> retainedAuthorityChecks=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AuthorityCheck> retainedDispatchChecks=new CopyOnWriteArrayList<>();
    private volatile String boundTask;
    private volatile HostRun hostRun;
    // Native frozen request only. No setting here can enlarge a grant, budget or host limit.
    private final java.util.concurrent.atomic.AtomicInteger modelConcurrencyLimit=new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
    public int modelConcurrencyLimit(){return modelConcurrencyLimit.get();}
    public void restrictModelConcurrency(int requested)throws InterruptedException {
        if(requested<1||requested>2)throw new IllegalArgumentException("Invalid profile model concurrency");check();
        synchronized(this){if(hostRun!=null||cancelled.get()||paused)throw new SecurityException("Model resource preference must precede the original run admission");modelConcurrencyLimit.accumulateAndGet(requested,Math::min);}
    }
    /** Native only. A retained live check, never a model-supplied grant or a cached boolean. */
    public void setAuthorityCheck(AuthorityCheck check){authorityCheck=java.util.Objects.requireNonNull(check);}
    /** Native invocation scope adds live authority without replacing the original source's check. */
    public AutoCloseable retainAuthorityCheck(AuthorityCheck check){AuthorityCheck retained=java.util.Objects.requireNonNull(check);retainedAuthorityChecks.add(retained);return ()->retainedAuthorityChecks.remove(retained);}
    /** Invocation-scoped native gate. Expensive resource probes belong at dispatch,
     * not in every cancellation checkpoint of a bounded screen traversal. */
    public AutoCloseable retainDispatchCheck(AuthorityCheck check){AuthorityCheck retained=java.util.Objects.requireNonNull(check);retainedDispatchChecks.add(retained);return ()->retainedDispatchChecks.remove(retained);}
    public void bindTask(String id){
        requireExecution();
        if(id==null||id.isEmpty())throw new IllegalArgumentException("Task identity required");
        synchronized(this){if(boundTask!=null){if(!boundTask.equals(id))throw new SecurityException("Control already belongs to another task");return;}}
        AuthorityCheck admitted=admit(id,"",0);
        synchronized(this){if(boundTask!=null&&!boundTask.equals(id))throw new SecurityException("Control already belongs to another task");boundTask=id;if(executionAdmission==null)executionAdmission=admitted;}
    }
    public void bindHostRun(String id,String runId,int epoch){
        requireExecution();
        if(runId==null||runId.isEmpty()||epoch<1)throw new IllegalArgumentException("Run identity required");bindTask(id);
        synchronized(this){if(hostRun!=null&&(!hostRun.runId.equals(runId)||hostRun.epoch!=epoch))throw new SecurityException("Control cannot be reused by a new run");}
        AuthorityCheck admitted=admit(id,runId,epoch);
        synchronized(this){if(hostRun!=null&&(!hostRun.runId.equals(runId)||hostRun.epoch!=epoch))throw new SecurityException("Control cannot be reused by a new run");hostRun=new HostRun(runId,epoch);if(admitted!=null)executionAdmission=admitted;}
    }
    private AuthorityCheck admit(String id,String run,int epoch){HostAdmission gate=hostAdmission;if(gate==null)return null;try{return gate.bind(this,id,run,epoch);}catch(Exception unavailable){throw new SecurityException("原任务被本机总停用门禁拒绝",unavailable);}}
    /** Read-only native identity. Holding this value never replaces this original control object. */
    public record HostRunIdentity(String taskId,String runId,int epoch){}
    public HostRunIdentity hostRunIdentity(){HostRun run=hostRun;return run==null?null:new HostRunIdentity(boundTask,run.runId,run.epoch);}
    public boolean hasHostRun(){return hostRun!=null;}
    public boolean matchesHostRun(String id,String runId,int epoch){HostRun run=hostRun;return id!=null&&id.equals(boundTask)&&(run==null?runId.isEmpty()&&epoch==0:run.runId.equals(runId)&&run.epoch==epoch);}
    private static final class HostRun {final String runId;final int epoch;HostRun(String runId,int epoch){this.runId=runId;this.epoch=epoch;}}
    // Native execution surface fence, not a second task or durable state store.
    private volatile String executionSurface="phone";
    private final AtomicLong surfaceRevision=new AtomicLong();
    public String executionSurface(){return executionSurface;}
    public long surfaceRevision(){return surfaceRevision.get();}
    public boolean phoneSurfaceActive(){return executionSurface.equals("phone");}
    public void changeExecutionSurface(String expected,String next)throws Exception {
        if(!next.matches("phone|browser|transition"))throw new IllegalArgumentException("Invalid surface");
        long pause=pauseRevision();
        dispatchAtPauseRevision(pause,()->{
            if(!executionSurface.equals(expected))throw new SecurityException("执行表面已变化");
            executionSurface=next;surfaceRevision.incrementAndGet();
        });
    }
    public boolean isCancelled() { return cancelled.get(); }
    public boolean isPaused() { return paused; }
    public ConditionWaitException conditionWait(){return cancelled.get()?null:conditionWait;}
    /** Only native authority checks call this; no task or execution budget is created. */
    public void waitForCondition(String reason,long validUntil)throws ConditionWaitException {
        if(!reason.matches("run_conditions|not_due|quiet_hours")||validUntil<=0)throw new IllegalArgumentException("Invalid condition wait");
        throw new ConditionWaitException(this,reason,validUntil,!ownerPaused);
    }
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            conditionWait=null;
            for (CancelAction action : cancellationActions) action.invoke();
            cancellationActions.clear();
        }
        synchronized (monitor) { monitor.notifyAll(); }
    }
    public void onCancel(Runnable action) {
        CancelAction registration = new CancelAction(action);
        if (cancelled.get()) { registration.invoke(); return; }
        cancellationActions.add(registration);
        if (cancelled.get()) {
            cancellationActions.remove(registration);
            registration.invoke();
        }
    }
    public void removeCancelAction(Runnable action) { cancellationActions.removeIf(entry -> entry.action == action); }
    private static final class CancelAction {
        final Runnable action;
        final AtomicBoolean invoked = new AtomicBoolean();
        CancelAction(Runnable action) { this.action = java.util.Objects.requireNonNull(action); }
        void invoke() { if (invoked.compareAndSet(false, true)) try { action.run(); } catch (RuntimeException ignored) { } }
    }
    /** Prepare under the original mailbox's monitor, after a pending steer is durably
     * accepted and before another poll may claim it. Only the revision changes here;
     * invoke the returned wake-up OUTSIDE store/application locks. Null rejects stale
     * identities. Queue/duplicate/withdrawal receipts must not prepare a notification. */
    public Runnable prepareModelReplan(String taskId,String runId,int epoch) {
        if(recoveryOnly||cancelled.get()||!hasHostRun()||!matchesHostRun(taskId,runId,epoch))return null;
        long preparedRevision=modelReplanRevision.incrementAndGet();AtomicBoolean delivered=new AtomicBoolean();
        return ()->{
            if(!delivered.compareAndSet(false,true))return;
            for(ModelRequest request:modelRequests)request.signal(preparedRevision);
            synchronized(monitor){monitor.notifyAll();}
        };
    }
    /** Convenience for native callers with no mailbox/claim race. Store-backed owner
     * inputs use prepareModelReplan and the original store monitor instead. */
    public boolean requestModelReplan(String taskId,String runId,int epoch) {
        Runnable wake=prepareModelReplan(taskId,runId,epoch);if(wake==null)return false;wake.run();return true;
    }
    /** Capture before polling the durable mailbox, so acceptance during poll/projection
     * cannot be missed by a request which has not registered its transport yet. */
    public long modelReplanRevision(){return modelReplanRevision.get();}
    public static final class ModelReplanRequested extends InterruptedException {
        private ModelReplanRequested(){super("Original owner input requires a fresh model request");}
    }
    /** A single logical model request, not a child RunControl. Its callbacks must only
     * stop that request's transport/wait; tool and run cleanup remain on onCancel. */
    public final class ModelRequest implements AutoCloseable {
        private final long revision;
        private final AtomicBoolean closed=new AtomicBoolean();
        private final CopyOnWriteArrayList<ModelCancellation> actions=new CopyOnWriteArrayList<>();
        private ModelRequest(long revision){this.revision=revision;}
        public void check()throws InterruptedException {
            RunControl.this.check();
            current();
        }
        private void current()throws InterruptedException {
            if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");
            if(closed.get())throw new IllegalStateException("Model request scope is closed");
            if(modelReplanRevision.get()!=revision)throw new ModelReplanRequested();
        }
        /** One idempotent registration shared by real Stop and this request's steering. */
        public AutoCloseable onCancel(Runnable action) {
            if(closed.get())throw new IllegalStateException("Model request scope is closed");
            ModelCancellation registration=new ModelCancellation(action);
            actions.add(registration);RunControl.this.onCancel(registration.stop);
            if(closed.get()){registration.close();throw new IllegalStateException("Model request scope is closed");}
            if(modelReplanRevision.get()!=revision)registration.action.invoke();
            return registration;
        }
        private final class ModelCancellation implements AutoCloseable {
            final CancelAction action;
            final Runnable stop;
            ModelCancellation(Runnable action){this.action=new CancelAction(action);stop=this.action::invoke;}
            public void close(){actions.remove(this);RunControl.this.removeCancelAction(stop);}
        }
        private void signal(long preparedRevision){if(!closed.get()&&revision<preparedRevision)for(ModelCancellation action:actions)action.action.invoke();}
        public void checkpoint()throws InterruptedException {
            check();
            synchronized(monitor){current();while(paused){monitor.wait(100);current();}}
            check();
        }
        public void sleep(long millis)throws InterruptedException {
            long until=System.nanoTime()+Math.max(0,millis)*1_000_000L;
            while(System.nanoTime()<until){
                checkpoint();
                synchronized(monitor){
                    current();
                    long remaining=(until-System.nanoTime())/1_000_000L;
                    if(remaining>0)monitor.wait(Math.min(100,remaining));
                }
            }
            checkpoint();
        }
        @Override public void close(){if(closed.compareAndSet(false,true)){modelRequests.remove(this);for(ModelCancellation action:actions)action.close();}}
    }
    public ModelRequest openModelRequest(long expectedRevision)throws InterruptedException {
        requireExecution();
        ModelRequest request=new ModelRequest(expectedRevision);modelRequests.add(request);
        try{request.check();return request;}catch(InterruptedException|RuntimeException refused){request.close();throw refused;}
    }
    public long pauseRevision() { return pauseRevision.get(); }
    public void pause() { synchronized(monitor) { ownerPaused=true;conditionWait=null;paused = true; pauseRevision.incrementAndGet(); } }
    public void resume() { synchronized (monitor) { ownerPaused=false;conditionWait=null;paused = false; monitor.notifyAll(); } }
    /** Native return confirmation only; a later owner pause/cancel must win. */
    public void resumeAtPauseRevision(long expectedPauseRevision) throws InterruptedException {
        // Authority callbacks can themselves pause or acquire TaskStore locks.
        check();
        synchronized(monitor) {
            if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");
            if(!paused||pauseRevision.get()!=expectedPauseRevision)throw new SecurityException("暂停状态已变化，旧交还确认失效");
            ownerPaused=false;conditionWait=null;paused=false;monitor.notifyAll();
        }
    }
    public void check() throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Run stopped");
        AuthorityCheck admission=executionAdmission;
        if(admission!=null)try{admission.check();}catch(Exception disabled){cancel();InterruptedException stopped=new InterruptedException("PocketWorld 已停用或原准入已撤销");stopped.initCause(disabled);throw stopped;}
        AuthorityCheck authority=authorityCheck;ConditionWaitException waiting=null;
        if(authority!=null)try{authority.check();}catch(ConditionWaitException temporary){if(temporary.owner!=this){cancel();throw new InterruptedException("运行条件不属于原控制器");}waiting=temporary;}catch(Exception revoked){cancel();InterruptedException stopped=new InterruptedException("任务来源授权已失效，已停止后续派发");stopped.initCause(revoked);throw stopped;}
        for(AuthorityCheck retained:retainedAuthorityChecks)try{retained.check();}catch(ConditionWaitException temporary){if(temporary.owner!=this){cancel();throw new InterruptedException("运行条件不属于原控制器");}if(waiting==null||temporary.validUntil<waiting.validUntil)waiting=temporary;}catch(Exception revoked){cancel();InterruptedException stopped=new InterruptedException("原任务调用授权已失效，已停止后续派发");stopped.initCause(revoked);throw stopped;}
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");
        if(waiting!=null){conditionWait(waiting);throw waiting;}
    }
    private void conditionWait(ConditionWaitException waiting)throws InterruptedException{
        if(waiting.owner!=this){cancel();throw new InterruptedException("运行条件不属于原控制器");}
        synchronized(monitor){if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");conditionWait=new ConditionWaitException(this,waiting.reason,waiting.validUntil,!ownerPaused);paused=true;pauseRevision.incrementAndGet();monitor.notifyAll();}
    }
    /**
     * Pause and native entry share this monitor. Run the possibly re-entrant authority
     * callback first, then read pause/revision again with no further callback before entry.
     * Once entry wins, later pause/cancel cannot undo its already-started external effect.
     * Entry must be one short native dispatch, never a model/network turn or a wait.
     */
    public void dispatchAtPauseRevision(long expectedPauseRevision,DispatchEntry entry) throws Exception {
        dispatchAtPauseState(expectedPauseRevision,false,entry);
    }
    /** A paused entry additionally requires the native owner permit checked by its caller. */
    public void dispatchAtPauseState(long expectedPauseRevision,boolean ownerEntry,DispatchEntry entry) throws Exception {
        requireExecution();
        java.util.Objects.requireNonNull(entry);
        // Authority may acquire TaskStore locks or invoke pause. Never call it while
        // holding this monitor: cancellation also signals the monitor after callbacks.
        check();
        for(AuthorityCheck retained:retainedDispatchChecks)retained.check();
        synchronized(monitor) {
            if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");
            if(paused!=ownerEntry||pauseRevision.get()!=expectedPauseRevision)throw new SecurityException("执行暂停状态已变化，旧派发决定失效");
            // Host admission is a lock-free immutable policy projection. Recheck after
            // re-entrant authority callbacks, immediately before the native effect.
            AuthorityCheck admitted=executionAdmission;if(admitted!=null)admitted.check();
            entry.enter();
        }
    }
    public void checkpoint() throws InterruptedException {
        check();
        synchronized (monitor) {
            while (paused && !cancelled.get()) monitor.wait(200);
        }
        check();
    }
    public void sleep(long millis) throws InterruptedException {
        long until = System.nanoTime() + Math.max(0, millis) * 1_000_000L;
        while (System.nanoTime() < until) {
            checkpoint();
            synchronized(monitor){if(cancelled.get())throw new InterruptedException("Run stopped");monitor.wait(Math.min(100, Math.max(1, (until-System.nanoTime())/1_000_000L)));}
        }
    }
}
