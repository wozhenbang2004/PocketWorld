package dev.pocketengine.phone.observation;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.setup.AndroidClipboardRecovery;
import org.json.JSONObject;

/** Explicit compatibility input through the original native field, with no clipboard values in receipts. */
public final class AccessibilityPasteInput {
    private static final PhoneReadGate READS=new PhoneReadGate();
    private AccessibilityPasteInput() { }

    public static JSONObject paste(Context context,PhoneAccessibilityService service,int display,AccessibilityForest.Ref ref,
            String text,RunControl control,long revision,NativePhoneEntry.Admission admission,
            NativePhoneEntry.Admission originalReference,Runnable invalidate)throws Exception {
        return paste(context,service,display,ref,text,control,revision,admission,originalReference,invalidate,()->{});
    }
    public static JSONObject paste(Context context,PhoneAccessibilityService service,int display,AccessibilityForest.Ref ref,
            String text,RunControl control,long revision,NativePhoneEntry.Admission admission,
            NativePhoneEntry.Admission originalReference,Runnable invalidate,Runnable inputEntered)throws Exception {
        final long clipboardRevision=AndroidClipboardRecovery.policyRevision(context);
        if(!AndroidClipboardRecovery.allowed(context))return refusal("clipboard_owner_permission_required");
        AndroidClipboardRecovery.requireOwnerAllowed(context,clipboardRevision);
        java.util.concurrent.atomic.AtomicBoolean pasteAccepted=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> receipt=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            // The complete borrow/paste/restore lives in one bounded Binder worker. If a Binder
            // outlives its caller, it keeps the clipboard lock until its own finally has restored
            // it; a timed-out queued paste never runs against an already-restored clipboard.
            return READS.call(()->current(context,service,control,revision,clipboardRevision),
                    ()->pasteOnWorker(context,service,display,ref,text,control,revision,clipboardRevision,admission,originalReference,invalidate,inputEntered,pasteAccepted,receipt));
        }catch(dev.pocketengine.phone.setup.ClipboardProfileScope.Unavailable unavailable){
            invalidate.run();boolean pending=true;try{pending=AndroidClipboardRecovery.create(context).pending();}catch(Exception ignored){}
            if(pasteAccepted.get())return acceptedAfterReadFailure(context,receipt.get(),unavailable.code);
            return refusal(unavailable.code).put("supported",false).put("clipboardRecoveryPending",pending).put("automaticReplayAllowed",false);
        }catch(PhoneReadGate.Unavailable unavailable){
            invalidate.run();
            if(unavailable.getCause() instanceof InterruptedException)Thread.currentThread().interrupt();
            if(pasteAccepted.get())return acceptedAfterReadFailure(context,receipt.get(),unavailable.code);
            if("tree_read_busy".equals(unavailable.code))return refusal("clipboard_native_input_busy");
            boolean pending=true;try{pending=AndroidClipboardRecovery.create(context).pending();}catch(Exception ignored){}
            return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","clipboard_input_unknown")
                    .put("clipboardRecoveryPending",pending).put("cleanupMayStillBeRunning",true)
                    .put("requiresReconciliation",true).put("automaticReplayAllowed",false);
        }catch(Exception unavailable){
            if(!pasteAccepted.get())throw unavailable;
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            invalidate.run();return acceptedAfterReadFailure(context,receipt.get(),"paste_followup_unavailable");
        }
    }

    private static JSONObject pasteOnWorker(Context context,PhoneAccessibilityService service,int display,AccessibilityForest.Ref ref,
            String text,RunControl control,long revision,long clipboardRevision,NativePhoneEntry.Admission admission,
            NativePhoneEntry.Admission originalReference,Runnable invalidate,Runnable inputEntered,
            java.util.concurrent.atomic.AtomicBoolean pasteAccepted,java.util.concurrent.atomic.AtomicReference<String> receipt)throws Exception {
        originalReference.check();PhoneReadGate.checkCurrent();
        AccessibilityNodeInfo node=AccessibilityForest.resolve(service,display,ref),identity=null;
        try {
            if(node==null||!node.refresh()||node.isPassword()||!node.isEditable()||!node.isEnabled()||!node.isVisibleToUser()
                    ||!AccessibilityForest.matches(ref,node))return refusal("paste_original_field_changed");
            identity=AccessibilityNodeInfo.obtain(node);final String before=node.getText()==null?"":node.getText().toString();
            final AccessibilityNodeInfo target=node;
            JSONObject result=AndroidClipboardRecovery.create(context,control,revision,()->{
                current(context,service,control,revision,clipboardRevision);originalReference.check();admission.check();
            }).paste(text,control,()->{
                originalReference.check();PhoneReadGate.checkCurrent();
                if(!target.refresh()||target.isPassword()||!target.isEditable()||!AccessibilityForest.matches(ref,target))
                    throw new SecurityException("paste_original_field_changed");
                return NativePhoneEntry.call(control,revision,()->{
                    current(context,service,control,revision,clipboardRevision);originalReference.check();admission.check();
                },()->{
                    AndroidClipboardRecovery.requireOwnerAllowed(context,clipboardRevision);
                    PhoneReadGate.markEffectEntered();inputEntered.run();invalidate.run();
                    // A false node-action result may be a missing remote callback. Stop
                    // this sequence and let the existing recovery path retain uncertainty.
                    if(!target.isFocused()&&!target.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                        throw new IllegalStateException("clipboard_focus_result_unknown");
                    Bundle range=new Bundle();range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);
                    range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,before.length());
                    if(!before.isEmpty()){
                        inputEntered.run();
                        if(!target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,range))
                            throw new IllegalStateException("clipboard_selection_result_unknown");
                    }
                    inputEntered.run();
                    if(!target.performAction(AccessibilityNodeInfo.ACTION_PASTE))
                        throw new IllegalStateException("clipboard_paste_result_unknown");
                    pasteAccepted.set(true);
                    return true;
                });
            });
            if(Boolean.TRUE.equals(result.opt("accepted"))){
                receipt.set(result.toString());
                try{
                    result.put("nativeTextReadback",read(service,display,ref,identity,text,control,revision).put("phase","immediate_after_dispatch"));
                    long until=SystemClock.uptimeMillis()+750;
                    while(SystemClock.uptimeMillis()<until){PhoneReadGate.checkCurrent();current(context,service,control,revision,clipboardRevision);Thread.sleep(Math.max(1,Math.min(50,until-SystemClock.uptimeMillis())));}
                    // A later rollback remains visible even if the first read matched exactly.
                    result.put("delayedTextReadback",read(service,display,ref,identity,text,control,revision).put("phase","delayed_after_dispatch"));
                }catch(Exception unavailable){
                    if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
                    result.put("delayedTextReadback",PhoneActionEffects.unavailableReadback("paste_followup_unavailable"));
                    if(!result.has("nativeTextReadback"))result.put("nativeTextReadback",PhoneActionEffects.unavailableReadback("paste_followup_unavailable"));
                }
                receipt.set(result.toString());
            }
            return result.put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
        }finally{if(identity!=null)identity.recycle();if(node!=null)node.recycle();}
    }

    /** A later read/control failure cannot erase a completed ACTION_PASTE acknowledgement. */
    private static JSONObject acceptedAfterReadFailure(Context context,String original,String reason)throws Exception {
        boolean pending=true;try{pending=AndroidClipboardRecovery.create(context).pending();}catch(Exception ignored){}
        JSONObject result=original==null?new JSONObject().put("ok",false).put("accepted",true).put("error","paste_followup_unavailable"):new JSONObject(original);
        if(!result.has("nativeTextReadback"))result.put("nativeTextReadback",PhoneActionEffects.unavailableReadback(reason));
        if(!result.has("delayedTextReadback"))result.put("delayedTextReadback",PhoneActionEffects.unavailableReadback(reason));
        boolean cleanupUnconfirmed=original==null||pending;
        return result.put("clipboardRecoveryPending",pending).put("cleanupMayStillBeRunning",original==null)
                .put("requiresReconciliation",cleanupUnconfirmed).put("requiresPause",true)
                .put("nextObservationAction",cleanupUnconfirmed?"reconcile":"pause")
                .put("postActionObservation",new JSONObject().put("status","unavailable_after_dispatch").put("reason",reason)
                        .put("dispatchReceiptPreserved",true).put("automaticReplayAllowed",false))
                .put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
    }

    private static JSONObject read(PhoneAccessibilityService service,int display,AccessibilityForest.Ref ref,AccessibilityNodeInfo original,
            String text,RunControl control,long revision)throws Exception {
        control.check();PhoneReadGate.checkCurrent();
        if(control.isPaused()||control.pauseRevision()!=revision||PhoneAccessibilityService.get()!=service||service.observationSafetyFailure()!=null)
            return PhoneActionEffects.unavailableReadback("paste_source_changed");
        AccessibilityNodeInfo actual=AccessibilityForest.resolve(service,display,ref);
        try {
            if(actual==null||original==null||!actual.refresh()||!actual.equals(original)||actual.isPassword()||!actual.isEditable()
                    ||actual.getWindowId()!=ref.windowId||!java.util.Objects.equals(actual.getViewIdResourceName(),original.getViewIdResourceName())
                    ||!java.util.Objects.equals(String.valueOf(actual.getClassName()),String.valueOf(original.getClassName()))
                    ||!java.util.Objects.equals(String.valueOf(actual.getPackageName()),String.valueOf(original.getPackageName())))
                return PhoneActionEffects.unavailableReadback("paste_original_node_unavailable");
            JSONObject result=PhoneActionEffects.inputReadback(actual.getText(),text,false).put("mechanism","same_native_node_after_clipboard_paste")
                    .put("displayId",display).put("windowId",ref.windowId).put("evidenceOnly",true);
            control.check();PhoneReadGate.checkCurrent();return result;
        }finally{if(actual!=null)actual.recycle();}
    }
    private static void current(Context context,PhoneAccessibilityService service,RunControl control,long revision,long clipboardRevision)throws Exception {
        AndroidClipboardRecovery.requireOwnerAllowed(context,clipboardRevision);
        control.check();if(control.isPaused()||control.pauseRevision()!=revision||PhoneAccessibilityService.get()!=service
                ||service.observationSafetyFailure()!=null)
            throw new SecurityException("paste_original_control_or_source_changed");
    }
    private static JSONObject refusal(String code)throws Exception {
        return new JSONObject().put("ok",false).put("accepted",false).put("error",code).put("automaticFallback",false);
    }
}
