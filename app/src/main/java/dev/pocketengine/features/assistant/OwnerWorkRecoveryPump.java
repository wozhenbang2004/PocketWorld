package dev.pocketengine.features.assistant;

import dev.pocketengine.store.TaskStore;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Pulls never-started durable work only after an existing worker picks up a tick.
 * Cursor state is process-local bookkeeping, never a claim, grant or retry ledger. */
public final class OwnerWorkRecoveryPump {
    public interface Host {
        boolean current(TaskStore original,long generation);
        void execute(TaskStore original,long generation,String id)throws Exception;
        void failed(String id,Exception failure);
    }
    private enum Phase { IDLE, QUEUED, SCANNING, DRAINED }
    private record Next(String id,boolean more) { }
    private final Executor workers;
    private final Host host;
    private final AtomicReference<Pass> active=new AtomicReference<>();

    /** workers is the host's enqueueing executor, not a caller-runs/inline adapter. */
    public OwnerWorkRecoveryPump(Executor workers,Host host){this.workers=Objects.requireNonNull(workers);this.host=Objects.requireNonNull(host);}

    /** Coalesce native recovery requests. After rejection a later explicit recover
     * call may retry; this installs no timer or additional lifecycle hook. An executing
     * or queued tick never spawns a second scan of the same generation. */
    public void recover(TaskStore original,long generation){
        if(original==null||!host.current(original,generation))return;
        while(true){
            if(!host.current(original,generation))return;
            Pass pass=active.get();
            if(pass==null||pass.original!=original||pass.generation!=generation){
                // A delayed request from the previous host must not evict a newer
                // valid pass between its first readiness check and this CAS.
                if(pass!=null&&pass.current())return;
                Pass replacement=new Pass(original,generation);
                if(!active.compareAndSet(pass,replacement))continue;
                pass=replacement;
            }
            RejectedExecutionException rejected=pass.schedule();
            if(rejected!=null)host.failed(null,rejected);
            return;
        }
    }

    private final class Pass {
        final TaskStore original;final long generation;
        final AtomicReference<Phase> phase=new AtomicReference<>(Phase.IDLE);
        // Only the unique SCANNING token reads or changes these fields. A running
        // business worker retains just its selected ID, never this mutable cursor.
        final ArrayDeque<String> candidates=new ArrayDeque<>();
        String after="",through;boolean moreRows=true;
        Pass(TaskStore original,long generation){this.original=original;this.generation=generation;}
        boolean current(){return active.get()==this&&host.current(original,generation);}
        void retire(){phase.set(Phase.DRAINED);active.compareAndSet(this,null);}

        RejectedExecutionException schedule(){
            if(!current()){retire();return null;}
            if(!phase.compareAndSet(Phase.IDLE,Phase.QUEUED))return null;
            try{workers.execute(this::tick);return null;}
            catch(RejectedExecutionException rejected){
                phase.compareAndSet(Phase.QUEUED,Phase.IDLE);
                return rejected;
            }catch(RuntimeException|Error failure){retire();throw failure;}
        }

        Next next()throws Exception {
            if(candidates.isEmpty()&&moreRows){
                TaskStore.OwnerWorkDispatchPage page=original.pendingOwnerWorkDispatchPage(after,through,100);
                after=page.afterTaskId();through=page.throughTaskId();moreRows=page.hasMore();
                for(int i=0;i<page.entries().length();i++)candidates.addLast(page.entries().getJSONObject(i).getString("id"));
            }
            return new Next(candidates.pollFirst(),!candidates.isEmpty()||moreRows);
        }

        void tick(){
            if(!phase.compareAndSet(Phase.QUEUED,Phase.SCANNING))return;
            Next next;
            try{
                if(!current()){retire();return;}
                next=next();
            }catch(Exception failure){retire();host.failed(null,failure);return;}
            catch(Error failure){retire();throw failure;}
            if(!current()){retire();return;}

            boolean tail=!next.more();RejectedExecutionException rejected=null;
            if(tail)phase.set(Phase.DRAINED);
            else{
                // Advance the one cursor before handing off one successor. It joins
                // the existing pool's tail; other queued work is not bypassed.
                phase.set(Phase.IDLE);
                rejected=schedule();
            }
            try{
                if(next.id()!=null&&host.current(original,generation))host.execute(original,generation,next.id());
            }catch(Exception failure){host.failed(next.id(),failure);}
            finally{
                // Only this tail tick retires its pass. Earlier workers must not
                // clear a successor tick or a replacement host generation.
                if(tail)retire();
                if(rejected!=null)host.failed(null,rejected);
            }
        }
    }
}
