package dev.pocketengine.phone.setup;

import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** One private compatibility input, with a durable original and compare-before-restore. */
public final class ClipboardRestoration {
    public interface Storage {String read()throws Exception;void write(String record)throws Exception;void clear()throws Exception;}
    public interface Clipboard {String snapshot()throws Exception;String replacement(String text)throws Exception;void install(String encoded)throws Exception;default void installTemporary(String encoded)throws Exception{install(encoded);}default String fingerprint(String encoded)throws Exception{return hash(encoded);}}
    @FunctionalInterface public interface Paste {boolean run()throws Exception;}
    private static final ReentrantLock CLIPBOARD=new ReentrantLock(true);
    private final Storage storage;private final Clipboard clipboard;
    public ClipboardRestoration(Storage storage,Clipboard clipboard){this.storage=storage;this.clipboard=clipboard;}
    public JSONObject paste(String text,RunControl control,Paste action)throws Exception {
        if(text==null||text.length()>12000)throw new IllegalArgumentException("clipboard_text_invalid");
        acquire(control);boolean accepted=false,entered=false,restored=false;
        try{
            control.check();if(control.isPaused())throw new SecurityException("clipboard_original_run_paused");
            if(storage.read()!=null)return refusal("clipboard_recovery_pending");
            String original=clipboard.snapshot(),replacement=clipboard.replacement(text);
            if(original==null||replacement==null||original.length()>48000||replacement.length()>48000)return refusal("clipboard_unavailable");
            storage.write(new JSONObject().put("schema",1).put("original",original).put("replacementHash",clipboard.fingerprint(replacement)).toString());
            try{
                control.check();clipboard.installTemporary(replacement);entered=true;
                if(!clipboard.fingerprint(replacement).equals(clipboard.fingerprint(clipboard.snapshot())))return refusal("clipboard_write_unverified");
                control.check();if(control.isPaused())throw new SecurityException("clipboard_original_run_paused");accepted=action.run();
            }finally{try{restored=recoverLocked();}catch(Exception unavailable){restored=false;}}
            return new JSONObject().put("ok",accepted).put("accepted",accepted).put("verified",false).put("clipboardRestored",restored)
                    .put("clipboardRecoveryPending",!restored).put("mechanism","owner_allowed_same_field_paste");
        }catch(InterruptedException stopped){throw stopped;}
        catch(Exception failure){
            Throwable cause=failure;while(cause!=null){if(cause instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired)throw(dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired)cause;cause=cause.getCause();}
            return new JSONObject().put("ok",false).put("accepted",entered?JSONObject.NULL:false).put("error",entered?"clipboard_input_unknown":"clipboard_unavailable").put("clipboardRestored",restored).put("clipboardRecoveryPending",storage.read()!=null).put("requiresReconciliation",entered).put("automaticReplayAllowed",false);
        }
        finally{CLIPBOARD.unlock();}
    }
    /** Recovery never pastes again and never returns either clipboard value. */
    public boolean recover()throws Exception {CLIPBOARD.lockInterruptibly();try{return recoverLocked();}finally{CLIPBOARD.unlock();}}
    public boolean pending()throws Exception{return storage.read()!=null;}
    private static void acquire(RunControl control)throws Exception {
        for(;;){control.check();dev.pocketengine.phone.observation.PhoneReadGate.checkCurrent();if(control.isPaused())throw new SecurityException("clipboard_original_run_paused");if(CLIPBOARD.tryLock(50,java.util.concurrent.TimeUnit.MILLISECONDS))return;}
    }
    private boolean recoverLocked()throws Exception {
        String encoded=storage.read();if(encoded==null)return true;JSONObject record=new JSONObject(encoded);String original=record.getString("original"),ours=record.getString("replacementHash");
        String current=clipboard.snapshot();if(current==null)return false;String digest=clipboard.fingerprint(current);
        if(digest.equals(clipboard.fingerprint(original))){storage.clear();return true;}
        if(!digest.equals(ours)){storage.clear();return true;} // A later owner/app clipboard replaces our temporary value; do not overwrite it.
        clipboard.install(original);String restored=clipboard.snapshot();if(restored==null||!clipboard.fingerprint(original).equals(clipboard.fingerprint(restored)))return false;storage.clear();return true;
    }
    private static JSONObject refusal(String code)throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("error",code).put("automaticFallback",false);}
    private static String hash(String value)throws Exception {if(value==null)throw new IllegalStateException("clipboard_unreadable");byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}

