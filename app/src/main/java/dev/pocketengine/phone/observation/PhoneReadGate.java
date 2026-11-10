package dev.pocketengine.phone.observation;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide Binder containment, not task authority. A timed-out Binder keeps its
 * slot until it really returns: no replacement for a stuck call or cached result. The
 * two permits include running and queued work; a bounded handoff queue only
 * bridges a completed worker's return to its executor loop.
 * Each original port has at most one in-flight read/action, and all ports together
 * have at most two workers (including owner previews and delayed readback).
 */
public final class PhoneReadGate {
    @FunctionalInterface public interface Check { void check() throws Exception; }
    public static final class Unavailable extends Exception {
        public final String code;
        private Unavailable(String code) { this(code,null); }
        private Unavailable(String code,Throwable cause) { super(code,cause);this.code=code; }
    }
    private static final int MAX_WORKERS=2;
    private static final AtomicInteger sequence=new AtomicInteger();
    private static final Semaphore BUDGET=new Semaphore(MAX_WORKERS);
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(MAX_WORKERS,MAX_WORKERS,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_WORKERS),action->{Thread thread=new Thread(action,"phone-binder-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.AbortPolicy());
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private static final ThreadLocal<Ticket> ACTIVE=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CHECKING=new ThreadLocal<>();
    private final AtomicBoolean inFlight=new AtomicBoolean();
    private final long timeoutMs;
    public PhoneReadGate() { this(5000); }
    // A shorter budget is useful for deterministic blocking-call fixtures.
    public PhoneReadGate(long timeoutMs) { if(timeoutMs<1||timeoutMs>5000)throw new IllegalArgumentException("read deadline");this.timeoutMs=timeoutMs; }

    public <T>T call(Check check,Callable<T> read)throws Exception {
        check.check();Ticket ticket=new Ticket(check,timeoutMs);CompletableFuture<T> result=new CompletableFuture<>();Outcome<T> outcome=new Outcome<>();
        submit(ticket,()->{
            try { checkCurrent();outcome.value=read.call();checkCurrent(); }
            catch(Throwable failed) { outcome.error=failed; }
        },()->{
            if(outcome.error!=null)result.completeExceptionally(outcome.error);
            else if(!ticket.abandoned.get())result.complete(outcome.value);
        });
        try {
            while(true) {
                check.check();long remaining=ticket.remainingMs();
                if(remaining<=0)throw new Unavailable("tree_read_timeout");
                try { T value=result.get(Math.min(50,remaining),TimeUnit.MILLISECONDS);check.check();return value; }
                catch(TimeoutException waiting) { }
            }
        }catch(ExecutionException failed) {
            Throwable cause=failed.getCause();if(ticket.abandon())throw new Unavailable("native_effect_unknown",cause);
            if(cause instanceof Exception)throw(Exception)cause;
            if(cause instanceof Error)throw(Error)cause;throw new IllegalStateException(cause);
        }catch(Exception stopped) {
            if(ticket.abandon())throw new Unavailable("native_effect_unknown",stopped);throw stopped;
        }finally { ticket.abandon();result.cancel(false); }
    }
    /** Non-blocking timer handoff. The supplied job owns releasing its retained node even on rejection. */
    public Executor executor() {
        return action->{try{submit(new Ticket(()->{},timeoutMs),action,()->{});}catch(Unavailable full){throw new RejectedExecutionException(full);}};
    }
    private void submit(Ticket ticket,Runnable action,Runnable completed)throws Unavailable {
        if(!inFlight.compareAndSet(false,true))throw new Unavailable("tree_read_busy");
        if(!BUDGET.tryAcquire()){inFlight.set(false);throw new Unavailable("tree_read_busy");}
        try { WORKERS.execute(()->{
            ACTIVE.set(ticket);try{action.run();}finally{ACTIVE.remove();inFlight.set(false);BUDGET.release();completed.run();}
        }); }
        catch(RejectedExecutionException full) { inFlight.set(false);BUDGET.release();throw new Unavailable("tree_read_busy"); }
    }
    private static final class Outcome<T> { T value;Throwable error; }
    /** Inside the original NativePhoneEntry only: diagnostics, not admission or a second authority. */
    public static void markEffectEntered()throws Unavailable {
        Ticket ticket=ACTIVE.get();if(ticket==null)return;
        // Serialize only local admission diagnostics with abandonment. This lock
        // never calls back into RunControl or Android, and never spans the effect.
        synchronized(ticket) {
            if(ticket.abandoned.get()||ticket.remainingMs()<=0)throw new Unavailable("tree_read_timeout");
            ticket.effectEntered.set(true);
        }
    }
    /** Called before/after every bounded traversal and immediately before native dispatch. */
    public static void checkCurrent()throws Exception {
        Ticket ticket=ACTIVE.get();if(ticket==null)return;
        if(!Boolean.TRUE.equals(CHECKING.get())){CHECKING.set(true);try{ticket.check.check();}finally{CHECKING.remove();}}
        if(ticket.abandoned.get()||ticket.remainingMs()<=0)throw new Unavailable("tree_read_timeout");
    }
    private static final class Ticket {
        final Check check;final long deadline;final AtomicBoolean abandoned=new AtomicBoolean(),effectEntered=new AtomicBoolean();
        Ticket(Check check,long timeoutMs){this.check=check;deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);}
        synchronized boolean abandon(){abandoned.set(true);return effectEntered.get();}
        long remainingMs(){long left=deadline-System.nanoTime();return left<=0?0:Math.max(1,TimeUnit.NANOSECONDS.toMillis(left));}
    }
}
