package dev.pocketengine.core;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** Sole process-local owner of executor pickup for an already authorized native
 * attempt. Replaces per-entrypoint pickup flags; TaskStore remains the durable
 * authority. No retry, replacement control, run creation or tool dispatch here. */
public final class CapabilityAttemptAdmission {
    public enum Phase { RESERVED, QUEUED, RUNNING, CLOSED }
    @FunctionalInterface public interface Preparation { void prepare() throws Exception; }
    private final RunControl original;
    private final Runnable settleUnstarted,release;
    private final AtomicReference<Phase> phase=new AtomicReference<>(Phase.RESERVED);
    private final Runnable cancellation=this::abandon;

    public CapabilityAttemptAdmission(RunControl original,Runnable settleUnstarted,Runnable release) {
        this.original=Objects.requireNonNull(original);
        this.settleUnstarted=Objects.requireNonNull(settleUnstarted);
        this.release=Objects.requireNonNull(release);
    }
    public Phase phase(){return phase.get();}

    /** Register cancellation before domain preparation and executor submission.
     * Preparation retains its own source/scope checks and creates no new grant. */
    public void submit(Executor executor,Preparation preparation,Runnable work)throws Exception {
        Objects.requireNonNull(executor);Objects.requireNonNull(preparation);Objects.requireNonNull(work);
        if(!phase.compareAndSet(Phase.RESERVED,Phase.QUEUED))throw new IllegalStateException("Original admission was already submitted");
        try {
            original.onCancel(cancellation);
            original.check();preparation.prepare();
            if(phase.get()!=Phase.QUEUED)return;
            executor.execute(()->{
                if(!phase.compareAndSet(Phase.QUEUED,Phase.RUNNING))return;
                original.removeCancelAction(cancellation);
                complete(work,()->{phase.set(Phase.CLOSED);release.run();});
            });
        } catch(Exception|Error failure) {
            try{abandon();}catch(RuntimeException|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    /** Setup failure, cancellation and executor rejection compete with pickup.
     * Only the winner may settle an unstarted run and release its ownership. */
    public void abandon() {
        for(;;){
            Phase current=phase.get();
            if(current!=Phase.RESERVED&&current!=Phase.QUEUED)return;
            if(!phase.compareAndSet(current,Phase.CLOSED))continue;
            original.removeCancelAction(cancellation);
            complete(settleUnstarted,release);
            return;
        }
    }
    private static void complete(Runnable action,Runnable cleanup) {
        Throwable primary=null;
        try{action.run();}
        catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{try{cleanup.run();}catch(RuntimeException|Error failure){if(primary==null)throw failure;if(primary!=failure)primary.addSuppressed(failure);}}
    }

}
