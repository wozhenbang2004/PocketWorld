package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** One native owner's ephemeral preview/picker window. No task, durable ledger,
 * artifact store or restorable grant. A JSON hash alone cannot create a Ticket. */
public final class NativeDiagnosticWindow {
    @FunctionalInterface public interface Destination {OutputStream open()throws Exception;}
    public static final class Ticket {
        private final NativeDiagnosticWindow owner;private final NativeDiagnosticCollector.Preview preview;private final long revision;
        private boolean claimed;
        private Ticket(NativeDiagnosticWindow owner,NativeDiagnosticCollector.Preview preview,long revision){this.owner=owner;this.preview=preview;this.revision=revision;}
    }
    private final BooleanSupplier privateOwner;
    private boolean visible,foreground=true,reviewed;
    private long revision;
    private NativeDiagnosticCollector.Preview preview;
    private Ticket pending;
    public NativeDiagnosticWindow(BooleanSupplier privateOwner){this.privateOwner=Objects.requireNonNull(privateOwner);}
    private void current(){if(!visible||!foreground||!privateOwner.getAsBoolean())throw new SecurityException("diagnostic_private_owner_window_not_current");}
    public synchronized void open(){if(!privateOwner.getAsBoolean())throw new SecurityException("diagnostic_private_owner_required");visible=true;}
    public synchronized void foreground(boolean value){foreground=value;}
    public synchronized void close(){visible=false;revision++;preview=null;pending=null;reviewed=false;}
    public synchronized void replace(NativeDiagnosticCollector.Preview original){current();revision++;preview=Objects.requireNonNull(original);pending=null;reviewed=false;}
    public synchronized void review(String exactShownSha256,boolean accepted){current();if(preview==null||!preview.sha256().equals(exactShownSha256)||pending!=null||preview.exportEntered())throw new SecurityException("diagnostic_exact_preview_changed");reviewed=accepted;}
    public synchronized Ticket prepare(){current();if(preview==null||!reviewed||pending!=null||preview.exportEntered())throw new SecurityException("diagnostic_exact_preview_review_required");return pending=new Ticket(this,preview,revision);}
    public synchronized void cancel(Ticket original){if(original!=null&&original==pending&&!original.claimed)pending=null;}
    private synchronized void check(Ticket original){current();if(original==null||original.owner!=this||original!=pending||original.revision!=revision||original.preview!=preview||!reviewed)throw new SecurityException("diagnostic_old_picker_or_preview_rejected");}
    public JSONObject write(Ticket original,Destination destination,RunControl observation)throws Exception {
        Objects.requireNonNull(observation).check();if(observation.hasHostRun())throw new SecurityException("diagnostic_export_does_not_borrow_task_authority");
        synchronized(this){check(original);if(original.claimed)throw new SecurityException("diagnostic_export_already_entered");original.claimed=true;}
        RunControl guarded=new RunControl();Runnable cancel=guarded::cancel;observation.onCancel(cancel);
        guarded.setAuthorityCheck(()->{observation.check();check(original);});
        try{
            guarded.check();try(OutputStream opened=Objects.requireNonNull(destination.open())){guarded.check();return original.preview.writeOnce(opened,guarded);}
        }finally{
            observation.removeCancelAction(cancel);guarded.cancel();synchronized(this){if(pending==original){pending=null;reviewed=false;}}
        }
    }
}
