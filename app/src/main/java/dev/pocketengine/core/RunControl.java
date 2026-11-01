package dev.pocketengine.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** One run's cancellation and pause authority; never reused by a later run. */
public final class RunControl {
    public interface AuthorityCheck {void check()throws Exception;}
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
    private volatile AuthorityCheck authorityCheck;
    private final CopyOnWriteArrayList<AuthorityCheck> retainedAuthorityChecks=new CopyOnWriteArrayList<>();
    private volatile String boundTask;
    private volatile HostRun hostRun;
    /** Native only. A retained live check, never a model-supplied grant or a cached boolean. */
    public void setAuthorityCheck(AuthorityCheck check){authorityCheck=java.util.Objects.requireNonNull(check);}
    /** Native invocation scope adds live authority without replacing the original source's check. */
    public AutoCloseable retainAuthorityCheck(AuthorityCheck check){AuthorityCheck retained=java.util.Objects.requireNonNull(check);retainedAuthorityChecks.add(retained);return ()->retainedAuthorityChecks.remove(retained);}
    public synchronized void bindTask(String id){
        if(id==null||id.isEmpty())throw new IllegalArgumentException("Task identity required");
        if(boundTask!=null&&!boundTask.equals(id))throw new SecurityException("Control already belongs to another task");boundTask=id;
    }
    public synchronized void bindHostRun(String id,String runId,int epoch){
        bindTask(id);if(runId==null||runId.isEmpty()||epoch<1)throw new IllegalArgumentException("Run identity required");
        if(hostRun!=null&&(!hostRun.runId.equals(runId)||hostRun.epoch!=epoch))throw new SecurityException("Control cannot be reused by a new run");hostRun=new HostRun(runId,epoch);
    }
    /** Read-only native identity. Holding this value never replaces this original control object. */
    public record HostRunIdentity(String taskId,String runId,int epoch){}
    public HostRunIdentity hostRunIdentity(){HostRun run=hostRun;return run==null?null:new HostRunIdentity(boundTask,run.runId,run.epoch);}
    public boolean hasHostRun(){return hostRun!=null;}
    public boolean matchesHostRun(String id,String runId,int epoch){HostRun run=hostRun;return id!=null&&id.equals(boundTask)&&(run==null?runId.isEmpty()&&epoch==0:run.runId.equals(runId)&&run.epoch==epoch);}
    private static final class HostRun {final String runId;final int epoch;HostRun(String runId,int epoch){this.runId=runId;this.epoch=epoch;}}
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
        java.util.Objects.requireNonNull(entry);
        // Authority may acquire TaskStore locks or invoke pause. Never call it while
        // holding this monitor: cancellation also signals the monitor after callbacks.
        check();
        synchronized(monitor) {
            if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");
            if(paused!=ownerEntry||pauseRevision.get()!=expectedPauseRevision)throw new SecurityException("执行暂停状态已变化，旧派发决定失效");
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
