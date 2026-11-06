package dev.pocketengine.features.assistant;

import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One bounded scan of the existing UI outbox per worker tick. This owns scheduling,
 * not delivery state, semantic receipt claims, work execution or retry authority. */
public final class OwnerWorkDeliveryPump {
    public interface Host {
        boolean current(TaskStore original,long generation);
        void deliver(TaskStore original,TaskStore.OwnerWorkDeliveryRef expected)throws Exception;
    }
    private enum Phase { IDLE, QUEUED, RUNNING, DRAINED }
    private final Object scheduling=new Object();
    private final Executor workers;
    private final Host host;
    private Pass active;
    /** workers must enqueue, not execute on the submitting caller's stack. */
    public OwnerWorkDeliveryPump(Executor workers,Host host){this.workers=Objects.requireNonNull(workers);this.host=Objects.requireNonNull(host);}

    public void request(TaskStore original,long generation){
        if(original==null)return;
        while(true){
            Pass observed;
            synchronized(scheduling){observed=active;}
            if(!host.current(original,generation))return;
            if(observed!=null&&(observed.original!=original||observed.generation!=generation)&&host.current(observed.original,observed.generation))return;
            Pass submit;
            synchronized(scheduling){
                if(active!=observed)continue;
                if(active==null||active.original!=original||active.generation!=generation)active=new Pass(original,generation);
                active.dirty=true;
                if(active.phase!=Phase.IDLE)return;
                active.phase=Phase.QUEUED;submit=active;
            }
            enqueue(submit);return;
        }
    }
    private void enqueue(Pass pass){
        try{workers.execute(()->tick(pass));}
        catch(RejectedExecutionException stopped){pause(pass);}
        catch(RuntimeException|Error failure){pause(pass);throw failure;}
    }
    private void pause(Pass pass){synchronized(scheduling){if(active==pass&&pass.phase!=Phase.DRAINED)pass.phase=Phase.IDLE;}}
    private boolean current(Pass pass){
        synchronized(scheduling){if(active!=pass)return false;}
        return !Thread.currentThread().isInterrupted()&&host.current(pass.original,pass.generation);
    }
    private void retire(Pass pass){synchronized(scheduling){pass.phase=Phase.DRAINED;if(active==pass)active=null;}}
    private void tick(Pass pass){
        synchronized(scheduling){
            if(active!=pass||pass.phase!=Phase.QUEUED)return;
            pass.phase=Phase.RUNNING;
            // Requests already present before a fresh scan are covered by that scan.
            if(pass.cursor==null)pass.dirty=false;
        }
        try{
            if(!current(pass)){retire(pass);return;}
            TaskStore.OwnerWorkDeliveryPage page=pass.original.pendingOwnerWorkDeliveryPage(pass.cursor,100);
            for(TaskStore.OwnerWorkDeliveryRef entry:page.entries()){
                if(!current(pass)){retire(pass);return;}
                try{host.deliver(pass.original,entry);}
                catch(Exception unavailable){/* Durable exact result remains authoritative, even if source sync failed after commit. */}
            }
            // Advance by inspected records, not successful deliveries. Busy or failed
            // conversations cannot hide unrelated later work in the same pass.
            pass.cursor=page.next();
            if(!current(pass)){retire(pass);return;}
            synchronized(scheduling){
                if(active!=pass)return;
                if(!page.hasMore()){
                    if(!pass.dirty){pass.phase=Phase.DRAINED;active=null;return;}
                    pass.cursor=null;
                }
                pass.phase=Phase.QUEUED;
            }
            enqueue(pass);
        }catch(Exception unavailable){pause(pass);}
        catch(Error failure){pause(pass);throw failure;}
    }
    private static final class Pass {
        final TaskStore original;final long generation;
        Phase phase=Phase.IDLE;boolean dirty;
        // Only the unique RUNNING tick uses the cursor; handoff is under scheduling.
        TaskStore.OwnerWorkDeliveryCursor cursor;
        Pass(TaskStore original,long generation){this.original=original;this.generation=generation;}
    }
}
