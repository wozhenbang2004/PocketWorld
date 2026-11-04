package dev.pocketengine.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContracts;
import dev.pocketengine.features.attachments.OwnerAttachmentImport.Target;

/** One original picker pin. Restoring a registration never reopens or replays a document selection. */
public final class AttachmentImportPicker {
    public enum Launch {REQUESTED,BUSY,NOT_STARTED,UNKNOWN}
    public interface Receiver {void receive(Intent files,Target original);}
    public interface End {void ended(boolean cancelled);}
    private static final String SAVED="ownerAttachmentImport";
    private final ActivityResultRegistry registry;
    private final Receiver receiver;
    private final End end;
    private Target pending;
    private ActivityResultLauncher<Intent> launcher;
    public AttachmentImportPicker(ActivityResultRegistry registry,Receiver receiver){this(registry,receiver,cancelled->{});}
    public AttachmentImportPicker(ActivityResultRegistry registry,Receiver receiver,End end){this.registry=registry;this.receiver=receiver;this.end=end;}

    private ActivityResultLauncher<Intent> register(Target original){
        boolean[] delivered={false};
        ActivityResultLauncher<Intent> registered=registry.register("owner_attachment_import_"+original.requestId,new ActivityResultContracts.StartActivityForResult(),result->{
            delivered[0]=true;
            if(pending!=original)return;
            pending=null;ActivityResultLauncher<Intent> old=launcher;launcher=null;if(old!=null)old.unregister();
            if(result.getResultCode()==Activity.RESULT_OK&&result.getData()!=null)receiver.receive(new Intent(result.getData()),original);
            else end.ended(result.getResultCode()==Activity.RESULT_CANCELED);
        });
        if(delivered[0]){registered.unregister();return null;}
        launcher=registered;return registered;
    }
    public Launch launch(String taskId,String projectId){
        if(pending!=null)return Launch.BUSY;
        Target original=new Target(taskId,projectId);pending=original;
        try{
            ActivityResultLauncher<Intent> registered=register(original);if(registered==null)return Launch.REQUESTED;
            registered.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
            return Launch.REQUESTED;
        }catch(ActivityNotFoundException absent){
            if(pending!=original)return Launch.REQUESTED;
            pending=null;close();return Launch.NOT_STARTED;
        }catch(RuntimeException uncertain){
            // AndroidX may already have dispatched; retain this original key, never borrow a later target.
            return pending==original?Launch.UNKNOWN:Launch.REQUESTED;
        }
    }
    public void save(Bundle state){if(pending!=null)state.putBundle(SAVED,pending.save());else state.remove(SAVED);}
    public boolean restore(Bundle state){
        if(state==null||!state.containsKey(SAVED))return true;
        Bundle saved=state.getBundle(SAVED);if(saved==null)return false;
        Target original=Target.restore(saved);if(original==null)return false;
        pending=original;resume();return true;
    }
    public void resume(){if(pending!=null)try{register(pending);}catch(RuntimeException uncertain){/* Keep the original pin. No launch or import is replayed. */}}
    public void close(){if(launcher!=null){launcher.unregister();launcher=null;}}
}
