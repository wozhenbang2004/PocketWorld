package dev.pocketengine.phone.observation;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.setup.AndroidClipboardRecovery;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Explicit ordinary-field compatibility input, with original-node checks and no clipboard content in its receipt. */
public final class AccessibilityPasteInput {
    private AccessibilityPasteInput() { }
    public static JSONObject paste(Context context,PhoneAccessibilityService service,int display,AccessibilityForest.Ref ref,String text,RunControl control,long revision,
            NativePhoneEntry.Admission admission,NativePhoneEntry.Admission originalReference,Runnable invalidate)throws Exception {
        Handler main=new Handler(Looper.getMainLooper());AtomicReference<AccessibilityNodeInfo> retained=new AtomicReference<>();AtomicReference<JSONObject> readback=new AtomicReference<>();
        if(!AndroidClipboardRecovery.allowed(context))return new JSONObject().put("ok",false).put("accepted",false).put("error","clipboard_owner_permission_required").put("automaticFallback",false);
        try{
            JSONObject result=AndroidClipboardRecovery.create(context,control,revision,()->{if(!AndroidClipboardRecovery.allowed(context))throw new SecurityException("clipboard_owner_permission_revoked");originalReference.check();admission.check();}).paste(text,control,()->{
                boolean accepted=onMain(main,()->{
                    originalReference.check();AccessibilityNodeInfo node=AccessibilityForest.resolve(service,display,ref);if(node==null)throw new SecurityException("paste_node_unavailable");
                    try{
                        if(!node.refresh()||node.isPassword()||!node.isEditable()||!node.isEnabled()||!node.isVisibleToUser()||!ref.identity.equals(AccessibilityForest.identity(node)))throw new SecurityException("paste_original_field_changed");
                        retained.set(AccessibilityNodeInfo.obtain(node));String old=node.getText()==null?"":node.getText().toString();
                        return NativePhoneEntry.call(control,revision,()->{if(!AndroidClipboardRecovery.allowed(context))throw new SecurityException("clipboard_owner_permission_revoked");originalReference.check();admission.check();},()->{
                            invalidate.run();if(!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))return false;
                            Bundle range=new Bundle();range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,old.length());
                            if(!old.isEmpty()&&!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,range))return false;
                            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        });
                    }finally{node.recycle();}
                });
                if(accepted){long until=SystemClock.uptimeMillis()+750;for(int i=0;i<16;i++){
                    control.check();if(control.isPaused()||control.pauseRevision()!=revision)throw new SecurityException("paste_original_control_changed");
                    JSONObject value=onMain(main,()->read(service,display,ref,retained.get(),text,control,revision));readback.set(value);
                    if("exact_match".equals(value.optString("status"))||SystemClock.uptimeMillis()>=until)break;Thread.sleep(50);
                }}return accepted;
            });
            if(readback.get()!=null)result.put("nativeTextReadback",readback.get());return result;
        }finally{AccessibilityNodeInfo node=retained.get();if(node!=null)node.recycle();}
    }
    private static JSONObject read(PhoneAccessibilityService service,int display,AccessibilityForest.Ref ref,AccessibilityNodeInfo original,String text,RunControl control,long revision)throws Exception {
        control.check();if(control.isPaused()||control.pauseRevision()!=revision||PhoneAccessibilityService.get()!=service||service.observationSafetyFailure()!=null)return PhoneActionEffects.unavailableReadback("paste_source_changed");
        AccessibilityNodeInfo current=AccessibilityForest.resolve(service,display,ref);try{
            if(current==null||original==null||!current.refresh()||!current.equals(original)||current.isPassword()||!current.isEditable()||current.getWindowId()!=ref.windowId||!java.util.Objects.equals(current.getViewIdResourceName(),original.getViewIdResourceName())||!java.util.Objects.equals(String.valueOf(current.getClassName()),String.valueOf(original.getClassName()))||!java.util.Objects.equals(String.valueOf(current.getPackageName()),String.valueOf(original.getPackageName())))return PhoneActionEffects.unavailableReadback("paste_original_node_unavailable");
            return PhoneActionEffects.inputReadback(current.getText(),text,false).put("mechanism","same_native_node_after_clipboard_paste").put("displayId",display).put("windowId",ref.windowId).put("evidenceOnly",true);
        }finally{if(current!=null)current.recycle();}
    }
    private static <T>T onMain(Handler main,java.util.concurrent.Callable<T> action)throws Exception {
        if(Looper.myLooper()==main.getLooper())return action.call();long deadline=SystemClock.uptimeMillis()+3000;AtomicInteger entry=new AtomicInteger();FutureTask<T> future=new FutureTask<>(()->{if(SystemClock.uptimeMillis()>=deadline||!entry.compareAndSet(0,1))throw new SecurityException("paste_queue_expired");return action.call();});main.post(future);
        try{return future.get(3,TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;throw failed;}catch(Exception failed){entry.compareAndSet(0,2);throw failed;}finally{future.cancel(false);}
    }
}
