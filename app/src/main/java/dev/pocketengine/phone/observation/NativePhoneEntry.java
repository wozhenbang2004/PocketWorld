package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One short native action or bounded local IPC write; no response wait under the control monitor. */
public final class NativePhoneEntry {
    private NativePhoneEntry() { }
    @FunctionalInterface public interface Admission {void check()throws Exception;}
    @FunctionalInterface public interface Effect<T> {T run()throws Exception;}
    public static <T>T call(RunControl control,long revision,Admission admission,Effect<T> effect)throws Exception {
        return call(control,revision,false,admission,effect);
    }
    /** Only the existing native paused-owner permit may choose this overload. */
    public static <T>T call(RunControl control,long revision,boolean owner,Admission admission,Effect<T> effect)throws Exception {
        AtomicInteger state=new AtomicInteger();AtomicReference<T> result=new AtomicReference<>();
        Runnable cancel=()->state.compareAndSet(0,2);control.onCancel(cancel);
        try {
            if (!owner) NativeSensitiveStep.requireOwnerUpdateWindow(control, revision);
            admission.check();
            control.dispatchAtPauseState(revision,owner,()->{
                if (!owner) NativeSensitiveStep.requireOwnerUpdateWindow(control, revision);
                if(!state.compareAndSet(0,1))throw new InterruptedException("原手机派发已停止");
                result.set(effect.run());
            });
            return result.get();
        }catch(NativeSensitiveStep.OwnerRequired ownerRequired){throw ownerRequired;}
        catch(Exception failed){if(state.get()!=1)throw new Refused(failed);throw failed;}
        finally {control.removeCancelAction(cancel);}
    }
    public static final class Refused extends Exception {Refused(Exception cause){super("原任务暂停、停止或准入已改变，尚未派发",cause);}}
}
