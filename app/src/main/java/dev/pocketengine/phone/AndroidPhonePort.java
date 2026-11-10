package dev.pocketengine.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.ObservationReport;
import dev.pocketengine.phone.observation.AccessibilityForest;
import dev.pocketengine.phone.observation.ObservationSafety;
import dev.pocketengine.phone.observation.NativeSensitiveStep;
import dev.pocketengine.phone.observation.NativeOcrTapPort;
import dev.pocketengine.phone.observation.OcrTextTarget;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import dev.pocketengine.phone.observation.NativeNodeActionReceipt;
import dev.pocketengine.phone.observation.NativeKeyFocus;
import dev.pocketengine.phone.observation.PhoneReadGate;
import dev.pocketengine.displayhelper.PhoneKeyCodes;

/** A single foreground-display executor. References are data, never retained Android nodes. */
public final class AndroidPhonePort implements PhonePort,NativeOcrTapPort,dev.pocketengine.phone.observation.NativeTreeScrollPort,dev.pocketengine.phone.observation.NativePhoneSignals,dev.pocketengine.phone.observation.NativePhoneVisualSignals,dev.pocketengine.phone.observation.NativePhoneExtensions {
    private static final int MAX_TEXT = 400;
    private static final long MAIN_WAIT_MS = 5_000;
    private static final ThreadLocal<Long> MAIN_DEADLINE = new ThreadLocal<>();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Snapshot> reference = new AtomicReference<>();
    private final PhoneReadGate reads = new PhoneReadGate();
    private volatile long lastScreenshotRequest;
    private final dev.pocketengine.displayhelper.FrameVisualEvidence visualFrames=new dev.pocketengine.displayhelper.FrameVisualEvidence();
    private volatile boolean lastIncludeImage;
    private final dev.pocketengine.phone.setup.AndroidAppCopies appCopies;
    private final dev.pocketengine.phone.access.PhoneOwnerPackages.Probe clipboardOwners;
    private dev.pocketengine.phone.diagnostics.PhonePracticeTarget practiceTarget;

    public AndroidPhonePort(Context context) { this(context,null); }
    public AndroidPhonePort(Context context,dev.pocketengine.phone.access.PhoneOwnerPackages.Probe clipboardOwners) {
        this.context=context.getApplicationContext();this.clipboardOwners=clipboardOwners;
        appCopies=new dev.pocketengine.phone.setup.AndroidAppCopies(this.context,Display.DEFAULT_DISPLAY);
    }
    @Override public PhoneUiSignals uiSignals(RunControl control)throws Exception {control.check();if(control.isPaused())return null;PhoneAccessibilityService service=requireService();requireUnlocked(service);return service.uiSignals();}
    @Override public synchronized JSONObject readVisualEvidence(RunControl control)throws Exception {
        workerOnly();long revision=control.pauseRevision();PhoneAccessibilityService service=requireService();
        guard(service,control,revision);
        try{return onTreeRead(()->guard(service,control,revision),()->{
            AccessibilityServiceInfo info=service.getServiceInfo();
            if(info==null||(info.getCapabilities()&AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)==0)
                return new JSONObject().put("status","unavailable").put("reason","screenshot_permission_denied");
            long spacing=1000-(SystemClock.uptimeMillis()-lastScreenshotRequest);
            if(spacing>0)return new JSONObject().put("status","deferred").put("reason","capture_spacing").put("nextSampleAfterMs",spacing);
            Screen before=screen(service);
            CapturedImage captured=capture(service,()->guard(service,control,revision),false);
            if(!before.same(screen(service))||captured.fullWidth!=before.width||captured.fullHeight!=before.height)
                throw new PhoneFailure("screen_changed","主屏在画面采样期间变化，请重新观察。");
            guard(service,control,revision);
            return new JSONObject(captured.json.getJSONObject("visualEvidence").toString())
                    .put("displayId",Display.DEFAULT_DISPLAY).put("sessionId",service.observationSessionId()).put("pauseRevision",revision)
                    .put("sourceFrameFresh",true).put("framePending",false).put("observedAtNanos",System.nanoTime()).put("source","accessibility_screenshot")
                    .put("nextSampleAfterMs",Math.max(1,1000-(SystemClock.uptimeMillis()-lastScreenshotRequest)));
        });}catch(PhoneFailure unavailable){
            if(!ObservationReport.isCaptureUnavailable(unavailable.code))throw unavailable;
            return new JSONObject().put("status","unavailable").put("reason",unavailable.code).put("sourceFrameFresh",false);
        }
    }
    @Override public boolean isConnected() { return PhoneAccessibilityService.get() != null; }
    @Override public void clearReferences() { reference.set(null); }

    /** Ends this physical port's run lifetime; per-observation invalidation must not close it. */
    public synchronized void closeExtensions() {
        clearReferences();appCopies.close();
        if(practiceTarget!=null){practiceTarget.close();practiceTarget=null;}
    }

    @Override public synchronized JSONObject extension(String tool,JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        workerOnly();dev.pocketengine.phone.observation.PhoneExtensionTools.validate(tool,args);
        control.check();if(control.isPaused())return failure("task_paused","原执行已暂停。");
        if(tool.equals("phone_list_app_copies"))return appCopies.list(control);
        if(admission==null)throw new SecurityException("original_prepared_admission_required");
        PhoneAccessibilityService service=requireService();long revision=control.pauseRevision();
        try{
            if(tool.equals("phone_launch_app_copy"))return onMain(control,revision,()->{guard(service,control,revision);JSONObject result=appCopies.launch(args,Display.DEFAULT_DISPLAY,control,admission);clearReferences();return result;});
            if(tool.equals("phone_practice_target")){
                if(!context.getPackageName().equals(args.getString("packageName"))||dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle())!=args.getInt("userId"))throw new SecurityException("practice_target_is_fixed");
                if(practiceTarget==null)practiceTarget=new dev.pocketengine.phone.diagnostics.PhonePracticeTarget(context,Display.DEFAULT_DISPLAY,service.observationSessionId());
                JSONObject result=practiceTarget.launch(control,()->{guard(service,control,revision);admission.check();});clearReferences();return result;
            }
            Snapshot expected=requireReference(args,control);
            if(tool.equals("phone_press_key"))return pressKey(service,expected,args.getString("key"),control,admission);
            if(tool.equals("phone_paste_text")){
                if(clipboardOwners==null)return failure("clipboard_profile_identity_unavailable","无法证明目标与原剪贴板属于同一Android资料，未执行粘贴。").put("supported",false);
                AccessibilityForest.Ref ref=expected.tree.forest.refs.get(args.getString("nodeId"));
                if(ref==null)return failure("stale_node","输入目标已变化。");
                return dev.pocketengine.phone.observation.AccessibilityPasteInput.paste(context,service,Display.DEFAULT_DISPLAY,ref,args.getString("text"),control,expected.revision,admission,()->{
                    Tree fresh=validateTarget(service,expected,control,ref);
                    NativeSensitiveStep.requireNode(fresh.forest,ref,"phone_type",control,expected.revision);
                    Rect bounds=new Rect();AccessibilityNodeInfo node=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,ref);
                    try{if(node==null)throw new PhoneFailure("stale_node","输入目标已变化。");node.getBoundsInScreen(bounds);requireClipboardProfile(String.valueOf(node.getPackageName()),control);}finally{if(node!=null)node.recycle();}
                    Rect controls=onMain(control,expected.revision,service::controlsBounds);
                    if(Rect.intersects(bounds,controls))throw new PhoneFailure("controls_overlap","输入目标被任务控制条遮挡。");
                },this::clearReferences);
            }
            if(tool.equals("phone_gesture"))return gesturePaths(service,expected,args,control,admission);
            return failure("native_extension_unavailable","此原生端口不支持该扩展。");
        }catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(NativeKeyFocus.Unavailable unavailable){clearReferences();return failure(unavailable.code,unavailable.getMessage()).put("supported",false);}
        catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(PhoneFailure refused){clearReferences();JSONObject result=failure(refused.code,refused.getMessage());if(ObservationSafety.unknownEffect(refused.code))result.put("accepted",JSONObject.NULL);return result;}
        catch(InterruptedException stopped){clearReferences();throw stopped;}
    }

    private JSONObject pressKey(PhoneAccessibilityService service,Snapshot expected,String key,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        int code=PhoneKeyCodes.require(key),global=keyGlobalAction(code);
        KeyPreparation prepared=onTreeRead(()->guard(service,control,expected.revision),()->prepareKey(service,expected,code,control));
        if(code==KeyEvent.KEYCODE_TAB||code==KeyEvent.KEYCODE_MOVE_HOME||code==KeyEvent.KEYCODE_MOVE_END){
            JSONObject result=focusedNodeKey(service,expected,key,code,control,admission);
            if(result!=null)return result;
        }
        if(android.os.Build.VERSION.SDK_INT>=33&&PhoneKeyEdit.supports(code)&&prepared.focus.node!=null&&prepared.focus.node.editable){
            JSONObject result=focusedEditorEdit(service,expected,key,code,control,admission);
            if(result!=null)return result;
        }
        if(!prepared.imeEnter&&global<0&&(android.os.Build.VERSION.SDK_INT<33||prepared.focus.node==null||!prepared.focus.node.editable))
            return failure("unsupported_key","此平台或当前焦点没有可定向使用的公开按键入口，未派发。").put("supported",false).put("key",key);
        String backend=prepared.imeEnter?"accessibility_ime_enter":global>=0?"accessibility_global_key":"accessibility_input_connection";
        JSONObject accepted=receipt(true,"key_requested").put("key",key).put("keyCode",code).put("backend",backend)
                .put("ackKind","native_system_acceptance").put("processingConfirmed",false).put("requiresPostconditionCheck",true)
                .put("displayId",Display.DEFAULT_DISPLAY).put("sessionId",service.observationSessionId()).put("automaticReplayAllowed",false);
        JSONObject unknown=failure("action_unknown","按键已尝试派发，但平台没有提供可靠回执；请核对当前页面，不会自动重发。")
                .put("accepted",JSONObject.NULL).put("requestAttempted",true).put("key",key).put("keyCode",code).put("backend",backend)
                .put("ackKind","accessibility_input_connection".equals(backend)?"input_connection_unconfirmed":"native_result_unconfirmed")
                .put("processingConfirmed",false).put("requiresReconciliation",true).put("automaticReplayAllowed",false)
                .put("displayId",Display.DEFAULT_DISPLAY).put("sessionId",service.observationSessionId());
        if("accessibility_input_connection".equals(backend))unknown.put("keyCleanup","up_requested_same_connection_unconfirmed");
        NativeNodeActionReceipt acknowledgement=new NativeNodeActionReceipt(accepted);
        java.util.concurrent.atomic.AtomicBoolean attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            if(prepared.imeEnter)return onNodeWorker(service,control,expected.revision,()->{
                KeyPreparation fresh=prepareKey(service,expected,code,control);
                if(!fresh.imeEnter)throw new NativeKeyFocus.Unavailable("key_action_changed");
                AccessibilityNodeInfo node=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,fresh.focus.node);
                try{
                    if(node==null||!node.refresh()||!node.isFocused()||!AccessibilityForest.matches(fresh.focus.node,node))throw new NativeKeyFocus.Unavailable("key_focus_changed");
                    boolean result=NativePhoneEntry.call(control,expected.revision,admission,()->{
                        guard(service,control,expected.revision);requireUnlocked(service);requireKeyFence(service,fresh);
                        PhoneReadGate.markEffectEntered();attempted.set(true);clearReferences();
                        return acknowledgement.record(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()));
                    });
                    return result?accepted:unknown;
                }finally{if(node!=null)node.recycle();}
            });
            return onMain(control,expected.revision,()->{
                validateReference(service,expected,control);requireKeyFence(service,prepared);
                PhoneInputMethod.Token editor=null;
                if(global<0){
                    prepared.focus.verify(service,Display.DEFAULT_DISPLAY,false,true);
                    PhoneInputMethod method=service.phoneInputMethod();
                    if(method==null||method!=expected.inputMethod||service.getInputMethod()!=method
                            ||(editor=method.current(prepared.focus.packageName,expected.inputRevision))==null)
                        return failure("unsupported_key","当前原生输入法连接不可用或已变化，未派发按键。").put("supported",false).put("key",key);
                }
                final PhoneInputMethod.Token originalEditor=editor;
                return NativePhoneEntry.call(control,expected.revision,admission,()->{
                    guard(service,control,expected.revision);requireUnlocked(service);requireKeyFence(service,prepared);
                    if(originalEditor!=null&&!originalEditor.current())throw new NativeKeyFocus.Unavailable("key_editor_changed");
                    attempted.set(true);clearReferences();
                    if(global>=0)return acknowledgement.record(service.performGlobalAction(global))?accepted:unknown;
                    // The public API hides remote transport errors and has no acceptance callback.
                    originalEditor.send(code);return unknown;
                });
            });
        }catch(Exception failure){
            JSONObject retained=acknowledgement.afterReadFailure("key_followup_unavailable");
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(retained!=null)return retained;
            if(attempted.get())return new JSONObject(unknown.toString()).put("requiresPause",true);
            throw failure;
        }
    }

    /** Use the editor's declared native focus/selection actions instead of an unacknowledged key pair. */
    private JSONObject focusedNodeKey(PhoneAccessibilityService service,Snapshot expected,String key,int code,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        JSONObject accepted=receipt(true,"key_action_accepted").put("key",key).put("keyCode",code)
                .put("backend","accessibility_focused_node").put("ackKind","native_system_acceptance")
                .put("processingConfirmed",false).put("requiresPostconditionCheck",true).put("automaticReplayAllowed",false);
        NativeNodeActionReceipt acknowledgement=new NativeNodeActionReceipt(accepted);
        java.util.concurrent.atomic.AtomicBoolean attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try{return onNodeWorker(service,control,expected.revision,()->{
            KeyPreparation fresh=prepareKey(service,expected,code,control);if(fresh.focus.node==null)return null;
            Tree tree=validateTarget(service,expected,control,fresh.focus.node);
            AccessibilityNodeInfo focused=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,fresh.focus.node),target=null;
            try{
                if(focused==null||!focused.refresh()||!focused.isFocused()||!AccessibilityForest.matches(fresh.focus.node,focused))throw new NativeKeyFocus.Unavailable("key_focus_changed");
                AccessibilityForest.Ref targetRef=fresh.focus.node;Bundle arguments=null;int action;int wanted=-1;
                if(code==KeyEvent.KEYCODE_TAB){
                    target=focused.focusSearch(android.view.View.FOCUS_FORWARD);
                    if(target==null||target.equals(focused)||target.getWindowId()!=fresh.focus.windowId||!target.isFocusable()
                            ||!target.isVisibleToUser()||!target.isEnabled()||!hasAction(target,AccessibilityNodeInfo.ACTION_FOCUS))return null;
                    targetRef=null;
                    for(AccessibilityForest.Ref candidate:tree.forest.refs.values())if(candidate.windowId==fresh.focus.windowId&&AccessibilityForest.matches(candidate,target)){
                        if(targetRef!=null)throw new NativeKeyFocus.Unavailable("key_focus_ambiguous");targetRef=candidate;
                    }
                    if(targetRef==null)return null;
                    validateTarget(service,expected,control,targetRef);action=AccessibilityNodeInfo.ACTION_FOCUS;
                }else{
                    target=focused;
                    if(!target.isEditable()||target.isPassword()||!hasAction(target,AccessibilityNodeInfo.ACTION_SET_SELECTION))return null;
                    CharSequence text=target.isShowingHintText()?"":target.getText();if(text==null)text="";
                    // Single-line editors have an exact Home/End position; wrapped/multiline behavior stays with the actual key path.
                    if(target.isMultiLine()||text.length()>PhoneKeyEdit.MAX_TEXT||text.toString().indexOf('\n')>=0||text.toString().indexOf('\r')>=0)return null;
                    wanted=code==KeyEvent.KEYCODE_MOVE_HOME?0:text.length();
                    if(target.getTextSelectionStart()==wanted&&target.getTextSelectionEnd()==wanted)
                        return new JSONObject().put("ok",true).put("status","completed").put("terminal",true).put("actionDispatched",false)
                                .put("postconditionAlreadySatisfied",true).put("key",key).put("processingConfirmed",true).put("automaticReplayAllowed",false);
                    arguments=new Bundle();arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,wanted);arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,wanted);
                    action=AccessibilityNodeInfo.ACTION_SET_SELECTION;
                }
                Rect bounds=new Rect();target.getBoundsInScreen(bounds);
                if(Rect.intersects(bounds,onMain(control,expected.revision,service::controlsBounds)))throw new PhoneFailure("controls_overlap","按键目标被任务控制条遮挡，请重新观察。");
                if(!target.refresh()||!AccessibilityForest.matches(targetRef,target))throw new NativeKeyFocus.Unavailable("key_focus_changed");
                final AccessibilityNodeInfo destination=target;final Bundle input=arguments;
                boolean result=NativePhoneEntry.call(control,expected.revision,admission,()->{
                    guard(service,control,expected.revision);requireUnlocked(service);requireKeyFence(service,fresh);
                    PhoneReadGate.markEffectEntered();attempted.set(true);clearReferences();return acknowledgement.record(destination.performAction(action,input));
                });
                if(!result)return keyEditUnknown(key,"accessibility_focused_node");
                boolean observed=target.refresh()&&target.isFocused()&&(code==KeyEvent.KEYCODE_TAB
                        ||target.getTextSelectionStart()==wanted&&target.getTextSelectionEnd()==wanted);
                JSONObject receipt=new JSONObject(accepted.toString()).put("processingConfirmed",observed)
                        .put("postcondition",code==KeyEvent.KEYCODE_TAB?"native_next_input_focus":"single_line_cursor_boundary")
                        .put("postconditionObserved",observed);
                acknowledgement.update(receipt);return receipt;
            }finally{if(target!=null&&target!=focused)target.recycle();if(focused!=null)focused.recycle();}
        });}catch(Exception unavailable){
            JSONObject retained=acknowledgement.afterReadFailure("key_followup_unavailable");
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            if(retained!=null)return retained;if(attempted.get())return keyEditUnknown(key,"accessibility_focused_node");throw unavailable;
        }
    }
    private static boolean hasAction(AccessibilityNodeInfo node,int action){return node.getActionList().stream().anyMatch(value->value.getId()==action);}

    /** Editing APIs have no transport ACK; confirm the exact text AND cursor through the same native editor. */
    private JSONObject focusedEditorEdit(PhoneAccessibilityService service,Snapshot expected,String key,int code,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        JSONObject confirmed=receipt(true,"key_edit_observed").put("key",key).put("keyCode",code).put("backend","accessibility_editor_edit")
                .put("ackKind","exact_editor_postcondition").put("processingConfirmed",true).put("postconditionObserved",true)
                .put("keyPairSent",false).put("automaticReplayAllowed",false);
        NativeNodeActionReceipt acknowledgement=new NativeNodeActionReceipt(confirmed);
        java.util.concurrent.atomic.AtomicBoolean attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try{return onNodeWorker(service,control,expected.revision,()->{
            KeyPreparation fresh=prepareKey(service,expected,code,control);if(fresh.focus.node==null)return null;
            PhoneInputMethod.Token connection=onMain(control,expected.revision,()->{
                PhoneInputMethod method=service.phoneInputMethod();
                return method==null||method!=expected.inputMethod||service.getInputMethod()!=method?null:method.current(fresh.focus.packageName,expected.inputRevision);
            });
            if(connection==null)return null;
            AccessibilityNodeInfo node=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,fresh.focus.node),identity=null;
            try{
                if(node==null||!node.refresh()||!AccessibilityForest.matches(fresh.focus.node,node))throw new NativeKeyFocus.Unavailable("key_focus_changed");
                PhoneKeyEdit edit=PhoneKeyEdit.prepare(code,node,connection);if(edit==null)return null;
                if(edit.unchanged())return new JSONObject().put("ok",true).put("status","completed").put("terminal",true).put("actionDispatched",false)
                        .put("postconditionAlreadySatisfied",true).put("key",key).put("processingConfirmed",true).put("automaticReplayAllowed",false);
                validateTarget(service,expected,control,fresh.focus.node);requireKeyFence(service,fresh);
                if(!node.refresh()||!edit.beforeMatches(node)||!connection.current())throw new NativeKeyFocus.Unavailable("key_editor_changed");
                identity=AccessibilityNodeInfo.obtain(node);
                NativePhoneEntry.call(control,expected.revision,admission,()->{
                    guard(service,control,expected.revision);requireUnlocked(service);requireKeyFence(service,fresh);
                    if(!edit.canDispatch(connection))throw new NativeKeyFocus.Unavailable("key_editor_changed");
                    PhoneReadGate.markEffectEntered();attempted.set(true);clearReferences();edit.perform(connection);return null;
                });
                for(int read=0;read<2;read++){
                    guard(service,control,expected.revision);requireUnlocked(service);
                    if(!connection.current()||!node.refresh()||!node.equals(identity)||!node.isFocused()||node.isPassword())break;
                    AccessibilityNodeInfo scoped=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,fresh.focus.node);
                    boolean same;try{same=scoped!=null&&scoped.equals(node);}finally{if(scoped!=null)scoped.recycle();}
                    if(!same)break;
                    if(edit.confirmed(connection,node)){
                        guard(service,control,expected.revision);acknowledgement.record(true);
                        return new JSONObject(confirmed.toString()).put("selectionStart",edit.cursor).put("selectionEnd",edit.cursor)
                                .put("displayId",Display.DEFAULT_DISPLAY).put("sessionId",service.observationSessionId());
                    }
                    if(read==0)Thread.sleep(80);
                }
                return keyEditUnknown(key,"accessibility_editor_edit").put("keyPairSent",false);
            }finally{if(identity!=null)identity.recycle();if(node!=null)node.recycle();}
        });}catch(Exception unavailable){
            JSONObject retained=acknowledgement.afterReadFailure("key_followup_unavailable");
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            if(retained!=null)return retained;if(attempted.get())return keyEditUnknown(key,"accessibility_editor_edit").put("keyPairSent",false);throw unavailable;
        }
    }
    private static JSONObject keyEditUnknown(String key,String backend)throws Exception {
        return failure("action_unknown","未确认原输入操作的准确结果，请核对当前编辑器；不会自动重发。")
                .put("accepted",JSONObject.NULL).put("requestAttempted",true).put("key",key).put("backend",backend)
                .put("processingConfirmed",false).put("requiresReconciliation",true).put("automaticReplayAllowed",false);
    }

    private KeyPreparation prepareKey(PhoneAccessibilityService service,Snapshot expected,int code,RunControl control)throws Exception {
        validateReference(service,expected,control);
        PhoneUiSignals.Stamp stamp=service.uiSignals().snapshot(java.util.Collections.emptySet());long focusRevision=service.keyFocusRevision();
        NativeKeyFocus focus=NativeKeyFocus.from(expected.tree.forest,Display.DEFAULT_DISPLAY,code);
        Tree fresh=focus.node==null?readTree(service):validateTarget(service,expected,control,focus.node);
        if(!expected.tree.sameWindowLayout(fresh))throw new NativeKeyFocus.Unavailable("key_focus_changed");
        NativeSensitiveStep.requireKey(fresh.forest,focus.node,code,control,expected.revision);
        boolean imeEnter=false;
        if(focus.node!=null){
            AccessibilityNodeInfo node=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,focus.node);
            try{
                if(node==null||!node.refresh()||!node.isFocused()||!AccessibilityForest.matches(focus.node,node))throw new NativeKeyFocus.Unavailable("key_focus_changed");
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                if(Rect.intersects(bounds,onMain(control,expected.revision,service::controlsBounds)))throw new PhoneFailure("controls_overlap","当前焦点被任务控制条遮挡，请重新观察。");
                imeEnter=(code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER)&&focus.node.editable
                        &&node.getActionList().stream().anyMatch(action->action.getId()==AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }finally{if(node!=null)node.recycle();}
        }
        boolean global=keyGlobalAction(code)>=0&&!imeEnter;
        boolean nodeKey=code==KeyEvent.KEYCODE_TAB||code==KeyEvent.KEYCODE_MOVE_HOME||code==KeyEvent.KEYCODE_MOVE_END;
        focus.verify(service,Display.DEFAULT_DISPLAY,global,!global&&!imeEnter&&!nodeKey&&android.os.Build.VERSION.SDK_INT>=33);
        validateReference(service,expected,control);KeyPreparation prepared=new KeyPreparation(focus,stamp,focusRevision,imeEnter);
        requireKeyFence(service,prepared);return prepared;
    }
    private static void requireKeyFence(PhoneAccessibilityService service,KeyPreparation prepared)throws Exception {
        requireUiUnchanged(service,prepared.ui);
        if(service.keyFocusRevision()!=prepared.focusRevision)throw new NativeKeyFocus.Unavailable("key_focus_changed");
    }
    private static int keyGlobalAction(int code){
        if(code==KeyEvent.KEYCODE_BACK)return AccessibilityService.GLOBAL_ACTION_BACK;
        if(android.os.Build.VERSION.SDK_INT<33)return -1;
        return switch(code){
            case KeyEvent.KEYCODE_DPAD_UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER;
            default -> -1;
        };
    }
    private record KeyPreparation(NativeKeyFocus focus,PhoneUiSignals.Stamp ui,long focusRevision,boolean imeEnter) { }

    private void requireClipboardProfile(String packageName,RunControl control)throws Exception {
        dev.pocketengine.phone.access.PhoneOwnerPackages owners;
        try{owners=clipboardOwners.read(control);}
        catch(InterruptedException stopped){throw stopped;}
        catch(Exception unavailable){throw new dev.pocketengine.phone.setup.ClipboardProfileScope.Unavailable("clipboard_profile_identity_unavailable",unavailable);}
        dev.pocketengine.phone.setup.ClipboardProfileScope.require(owners,packageName,context.getApplicationInfo().uid/100000);
    }

    /** Accessibility owns an accepted main-screen gesture; it exposes no cancellation-by-ID API. */
    private JSONObject gesturePaths(PhoneAccessibilityService service,Snapshot expected,JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        if(expected.imageDigest==null)throw new PhoneFailure("coordinate_unverifiable","多指手势需要原真实图片。");
        CapturedImage image=capture(service,control,expected.revision);
        if(!expected.imageDigest.equals(image.digest)||expected.tree.screen.width!=image.fullWidth||expected.tree.screen.height!=image.fullHeight)throw new PhoneFailure("stale_observation","图片已变化，未派发多指手势。");
        final JSONArray paths=args.getJSONArray("paths");final int duration=args.getInt("durationMs");
        PhoneUiSignals.Stamp validated=onTreeRead(()->guard(service,control,expected.revision),()->{
            PhoneUiSignals.Stamp before=service.uiSignals().snapshot(java.util.Collections.emptySet());
            Tree fresh=validate(service,expected,control,false);
            for(int i=0;i<paths.length();i++){JSONArray points=paths.getJSONArray(i);for(int j=1;j<points.length();j++){JSONArray a=points.getJSONArray(j-1),b=points.getJSONArray(j);NativeSensitiveStep.requireCoordinate(fresh.forest,a.getInt(0),a.getInt(1),b.getInt(0),b.getInt(1),true,control,expected.revision);}}
            requireUiUnchanged(service,before);return before;
        });
        CompletableFuture<String> completion=new CompletableFuture<>();java.util.concurrent.atomic.AtomicBoolean listening=new java.util.concurrent.atomic.AtomicBoolean(true),dispatchAccepted=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            boolean accepted=onMain(control,expected.revision,()->{
                validateReference(service,expected,control);GestureDescription.Builder builder=new GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY);
                for(int i=0;i<paths.length();i++){
                    JSONArray points=paths.getJSONArray(i);Path path=new Path();float previousX=0,previousY=0;
                    for(int j=0;j<points.length();j++){
                        JSONArray pair=points.getJSONArray(j);float x=pixel(pair.getInt(0),image.fullWidth),y=pixel(pair.getInt(1),image.fullHeight);
                        if(j==0)path.moveTo(x,y);
                        else{Rect swept=new Rect((int)Math.floor(Math.min(previousX,x)),(int)Math.floor(Math.min(previousY,y)),(int)Math.ceil(Math.max(previousX,x))+1,(int)Math.ceil(Math.max(previousY,y))+1);if(Rect.intersects(swept,service.controlsBounds()))throw new PhoneFailure("controls_overlap","手势经过原任务停止控件。");path.lineTo(x,y);}
                        previousX=x;previousY=y;
                    }
                    builder.addStroke(new GestureDescription.StrokeDescription(path,0,duration));
                }
                requireUnlocked(service);if(!expected.tree.screen.same(screen(service)))throw new PhoneFailure("stale_observation","屏幕尺寸发生变化。");
                guard(service,control,expected.revision);
                return NativePhoneEntry.call(control,expected.revision,admission,()->{
                    Long deadline=MAIN_DEADLINE.get();if(deadline!=null&&SystemClock.uptimeMillis()>deadline)throw new PhoneFailure("dispatch_unknown","原生手势入场超时。");
                    requireUiUnchanged(service,validated);clearReferences();
                    boolean dispatched=dispatchGesture(service,builder.build(),new AccessibilityService.GestureResultCallback(){
                        @Override public void onCompleted(GestureDescription gesture){if(listening.get())completion.complete("completed");}
                        @Override public void onCancelled(GestureDescription gesture){if(listening.get())completion.complete("cancelled");}
                    });
                    if(dispatched)dispatchAccepted.set(true);return dispatched;
                });
            });
            if(!accepted)return receipt(false,"gesture_rejected").put("cancelSupported",false);
            String state;
            try{state=await(completion,()->guard(service,control,expected.revision),duration+3500L);}
            catch(Exception unknown){
                return acceptedGestureReadFailure(completion,unknown);
            }
            if(!"completed".equals(state))return ObservationReport.annotateFailure(new JSONObject().put("ok",false).put("accepted",true).put("error","gesture_cancelled").put("state","gesture_cancelled").put("cancelSupported",false).put("requiresPostconditionCheck",true).put("automaticReplayAllowed",false));
            return receipt(true,"gesture_completed").put("cancelSupported",false).put("requiresPostconditionCheck",true);
        }catch(Exception unavailable){if(dispatchAccepted.get())return acceptedGestureReadFailure(completion,unavailable);throw unavailable;}
        finally{listening.set(false);completion.cancel(false);}
    }

    /** Read-only owner preview. It never installs action references or touches the task's physical port. */
    OwnerScreenContext.Captured captureForContext(boolean includeScreenshot, OwnerScreenContext.ReadCheck owner) throws Exception {
        workerOnly(); owner.check();
        PhoneAccessibilityService service = requireService();
        OwnerScreenContext.ReadCheck check = () -> { owner.check(); requireUnlocked(service); };
        Tree before = onTreeRead(check, () -> readTree(service));
        CapturedImage image = null;
        String imageStatus = "not_requested", imageReason = "";
        if (includeScreenshot) {
            AccessibilityServiceInfo info = service.getServiceInfo();
            if (info == null || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0) {
                imageStatus = "unavailable"; imageReason = "screenshot_permission_denied";
            } else {
                try { image = capture(service, check); imageStatus = "available"; }
                catch (PhoneFailure unavailable) {
                    if (!unavailable.code.startsWith("screenshot_") && !"permission_denied".equals(unavailable.code)
                            && !"display_unavailable".equals(unavailable.code)) throw unavailable;
                    imageStatus = "unavailable"; imageReason = unavailable.code;
                } catch (SecurityException denied) { imageStatus = "unavailable"; imageReason = "screenshot_permission_denied"; }
            }
        }
        Tree after = onTreeRead(check, () -> readTree(service));
        if (!before.sameScreen(after) || image != null && (image.fullWidth != after.screen.width || image.fullHeight != after.screen.height))
            throw new OwnerScreenContext.Failure("screen_changed", "主屏在读取期间变化，请重新选择画面");
        check.check();
        StringBuilder text = new StringBuilder(); Set<String> seen = new LinkedHashSet<>();
        boolean truncated = after.incomplete;
        for (int i = 0; i < after.jsonNodes.length(); i++) {
            JSONObject node = after.jsonNodes.getJSONObject(i);
            if (node.optBoolean("password")) continue;
            for (String key : new String[]{"text", "description"}) {
                String value = node.optString(key).trim();
                if (value.isEmpty() || !seen.add(value)) continue;
                if (text.length() > 0) text.append('\n');
                int remaining = OwnerScreenContext.MAX_TEXT_CHARS - text.length();
                if (value.length() > remaining) { text.append(value, 0, Math.max(0, remaining)); truncated = true; break; }
                text.append(value);
            }
            if (text.length() >= OwnerScreenContext.MAX_TEXT_CHARS) { truncated = true; break; }
        }
        String label = after.packageName;
        try { label = limited(context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(after.packageName, 0)), 250); }
        catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { /* Package identity remains available. */ }
        byte[] bytes = image == null ? null : Base64.decode(image.json.getString("imageBase64"), Base64.DEFAULT);
        try {
            return new OwnerScreenContext.Captured(new OwnerScreenContext.SourceIdentity(service.observationSessionId(), Display.DEFAULT_DISPLAY),
                    after.packageName, label, after.windowId, SystemClock.uptimeMillis(), text.toString(),
                    !after.available ? "no_accessible_root" : text.length() == 0 ? "no_visible_text" : "available", truncated,
                    imageStatus, imageReason, image == null ? "" : image.json.getString("imageMimeType"),
                    image == null ? 0 : image.json.getInt("imageWidth"), image == null ? 0 : image.json.getInt("imageHeight"), bytes);
        } finally { if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0); }
    }

    @Override public synchronized JSONObject tapOcrText(OcrTextTarget target,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        workerOnly();Snapshot expected=reference.get();
        if(expected==null||!target.matches(control,expected.id,expected.revision)||expected.imageDigest==null
                ||target.sourceWidth!=expected.imageWidth||target.sourceHeight!=expected.imageHeight)
            return failure("stale_observation","OCR目标不属于当前完整截图，请重新观察。");
        final JSONObject arguments;
        try{target.requireNonSensitive(control,expected.revision);arguments=target.tapArguments(expected.tree.screen.width,expected.tree.screen.height);}
        catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(IllegalArgumentException invalid){return failure("coordinate_unverifiable",invalid.getMessage());}
        // The normal gesture path re-captures and compares the full-resolution pixel digest,
        // current tree/service/control/revision and overlay bounds before its one native action.
        try {
            control.checkpoint();PhoneAccessibilityService service=requireService();
            if(requireReference(arguments,control)!=expected)return failure("stale_observation","OCR目标已失效，请重新观察。");
            return gesture(service,expected,arguments,false,control,admission);
        }catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(PhoneFailure refused){clearReferences();return failure(refused.code,refused.getMessage());}
    }

    @Override public synchronized JSONObject observe(boolean includeImage, RunControl control) throws Exception {
        workerOnly();
        clearReferences();
        lastIncludeImage = includeImage;
        control.check();
        if(control.isPaused())return failure("task_paused","任务已暂停，旧观察未派发。");
        long revision = control.pauseRevision();
        try {
            PhoneAccessibilityService service = requireService();
            Tree after = null;
            CapturedImage image = null;
            boolean changedDuringRead = false;
            String imageReason = "not_requested";
            JSONObject visualEvidence=null;
            // Retry a window/geometry transition once. Text updates may retain the
            // latest tree and contextual pixels, but never authorize coordinates.
            for (int attempt = 0; attempt < (includeImage ? 2 : 1); attempt++) {
                if (attempt > 0) sleepRead(() -> guard(service, control, revision), 180);
                Tree before = onTreeRead(() -> guard(service,control,revision), () -> readTree(service));
                image = null;
                visualEvidence=null;
                if (includeImage) {
                    try { image = capture(service, control, revision); imageReason = "accessibility_screenshot"; }
                    catch (PhoneFailure unavailable) {
                        if (!ObservationReport.isCaptureUnavailable(unavailable.code)) throw unavailable;
                        imageReason = unavailable.code;
                        visualEvidence=unavailable.visualEvidence;
                    }
                }
                after = includeImage ? onTreeRead(() -> guard(service,control,revision), () -> readTree(service)) : before;
                changedDuringRead = !before.sameScreen(after);
                boolean mixedWindow = image != null && (!before.sameWindowLayout(after)
                        || image.fullWidth != after.screen.width || image.fullHeight != after.screen.height);
                if (!mixedWindow) break;
                if (attempt == 0 && includeImage) continue;
                throw new PhoneFailure("screen_changed", "页面在截图观察期间持续变化，请等页面稳定后重新观察。");
            }
            control.check();
            if (control.isPaused() || control.pauseRevision() != revision || service != PhoneAccessibilityService.get())
                throw new PhoneFailure("observation_expired", "任务状态已改变，请重新观察。");
            boolean coordinatesAvailable = image != null && !changedDuringRead;
            Snapshot snapshot = new Snapshot(service, after, control, revision, coordinatesAvailable ? image : null);
            JSONObject result = new JSONObject().put("ok", true).put("observationId", snapshot.id)
                    .put("packageName", after.packageName).put("screenWidth", after.screen.width)
                    .put("screenHeight", after.screen.height).put("nodes", after.jsonNodes)
                    .put("truncated", after.incomplete).put("displayId", Display.DEFAULT_DISPLAY)
                    .put("sessionId", service.observationSessionId()).put("executionMode", "main")
                    .put("observedAtUptimeMs", snapshot.created).put("pauseRevision", revision)
                    .put("changedDuringRead", changedDuringRead).put("coordinateActionsAvailable", coordinatesAvailable);
            if (includeImage && image == null) result.put("imageUnavailable", true).put("imageUnavailableReason", imageReason);
            dev.pocketengine.phone.observation.InstalledAppEvidence.attach(context,result);
            if (image != null) {
                for (String key : new String[]{"imageBase64", "imageMimeType", "imageWidth", "imageHeight", "capturedAtUptimeMs", "visualEvidence"})
                    result.put(key, image.json.get(key));
            }else if(visualEvidence!=null)result.put("visualEvidence",visualEvidence);
            after.forest.attach(result);
            reference.set(snapshot);
            return ObservationReport.attach(result, after.available, after.available ? "accessibility_tree" : "no_accessible_root",
                    image != null, imageReason);
        } catch (PhoneFailure failure) { return failure(failure.code, failure.getMessage()); }
        catch (SecurityException failure) { return failure("permission_denied", "系统未允许读取当前页面。"); }
        catch (RuntimeException failure) { return failure("platform_error", "Android 未能读取当前页面，请重新观察。"); }
    }

    @Override public synchronized JSONObject scrollNode(JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        workerOnly();dev.pocketengine.phone.observation.NativeTreeScrollPort.validate(args);if(admission==null)return failure("host_authority_required","节点滚动必须由原prepared操作准入。");control.check();
        if(control.isPaused())return failure("task_paused","原执行已暂停，未滚动。");
        try{Snapshot expected=requireReference(args,control);PhoneAccessibilityService service=requireService();
            return nodeAction(service,expected,control,"phone_scroll_node",args.getString("nodeId"),null,admission,args.getString("direction"));
        }catch(NativeSensitiveStep.OwnerRequired owner){clearReferences();throw owner;}
        catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
        catch(PhoneFailure refused){clearReferences();JSONObject out=failure(refused.code,refused.getMessage());if(ObservationSafety.unknownEffect(refused.code))out.put("accepted",JSONObject.NULL);return out;}
        catch(InterruptedException stopped){clearReferences();throw stopped;}
        catch(Exception unknown){clearReferences();return failure("action_unknown","原生滚动回执未知，请核对；不会自动重试。").put("accepted",JSONObject.NULL);}
    }

    @Override public synchronized JSONObject execute(String name, JSONObject args, RunControl control) throws Exception {
        workerOnly();
        control.checkpoint();
        if (args == null) args = new JSONObject();
        if ("phone_observe".equals(name)) return observe(lastIncludeImage, control);
        try {
            if ("phone_list_apps".equals(name)) return listApps(control);
            long revision = control.pauseRevision();
            PhoneAccessibilityService service = requireService();
            final JSONObject parameters = args;
            switch (name) {
                case "phone_launch_app": {
                    String packageName = string(args, "packageName", 250, false);
                    if (!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                        throw new PhoneFailure("invalid_arguments", "packageName 无效。");
                    return onMain(control, revision, () -> {
                        requireUnlocked(service);
                        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if (intent == null) throw new PhoneFailure("app_unavailable", "没有找到可打开的应用。");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        requireUnlocked(service);
                        guard(service, control, revision);
                        return NativePhoneEntry.call(control,revision,()->guard(service,control,revision),()->{
                            guard(service,control,revision);clearReferences();
                            try{service.startActivity(intent);}
                            catch(RuntimeException unknown){throw new PhoneFailure("action_unknown","启动请求的系统回执未知，请核对当前应用；不会自动重试。");}
                            return receipt(true,"launch_requested");
                        });
                    });
                }
                case "phone_back":
                case "phone_home": {
                    Snapshot expected = requireReference(args, control);
                    PhoneUiSignals.Stamp validated=validateNavigationForUi(service,expected,control);
                    return onMain(control, expected.revision, () -> {
                        validateReference(service,expected,control);
                        requireUnlocked(service);
                        guard(service, control, expected.revision);requireUiUnchanged(service,validated);
                        return NativePhoneEntry.call(control,expected.revision,()->guard(service,control,expected.revision),()->{
                            requireUiUnchanged(service,validated);clearReferences();
                            boolean accepted;
                            try{accepted=service.performGlobalAction("phone_back".equals(name)
                                    ? AccessibilityService.GLOBAL_ACTION_BACK : AccessibilityService.GLOBAL_ACTION_HOME);}
                            catch(RuntimeException unknown){throw new PhoneFailure("action_unknown","导航请求的系统回执未知，请核对当前页面；不会自动重试。");}
                            return receipt(accepted,"global_action_requested");
                        });
                    });
                }
                case "phone_click_node":
                case "phone_type": {
                    Snapshot expected = requireReference(args, control);
                    String nodeId = "phone_click_node".equals(name)
                            ? string(args, "nodeId", 100, false) : optionalString(args, "nodeId", 100);
                    String text = "phone_type".equals(name) ? string(args, "text", 12_000, true) : null;
                    dev.pocketengine.phone.observation.DelayedInputReadback delayed=new dev.pocketengine.phone.observation.DelayedInputReadback();
                    JSONObject receipt=nodeAction(service,expected,control,name,nodeId,text,null,null,delayed);
                    return delayed.attach(receipt);
                }
                case "phone_tap":
                case "phone_swipe": {
                    Snapshot expected = requireReference(args, control);
                    return gesture(service, expected, parameters, "phone_swipe".equals(name), control);
                }
                default: throw new PhoneFailure("unknown_tool", "不支持的手机工具。");
            }
        } catch (NativeSensitiveStep.OwnerRequired owner) {
            clearReferences();throw owner;
        } catch (NativePhoneEntry.Refused refused) {
            clearReferences();return failure("paused_or_changed",refused.getMessage());
        } catch (PhoneFailure failure) {
            clearReferences();
            JSONObject result=failure(failure.code,failure.getMessage());
            if(ObservationSafety.unknownEffect(failure.code))result.put("accepted",JSONObject.NULL);
            return result;
        } catch (SecurityException failure) {
            clearReferences();
            return failure("permission_denied", "系统未允许此操作。");
        } catch (RuntimeException failure) {
            clearReferences();
            return failure("platform_error", "Android 未能完成工具请求，请重新观察。");
        }
    }

    private JSONObject listApps(RunControl control) throws Exception {
        PackageManager packages = context.getPackageManager();
        List<ResolveInfo> found = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        JSONArray apps = new JSONArray();
        Set<String> seen = new HashSet<>();
        int visited = 0;
        for (ResolveInfo info : found) {
            control.checkpoint();
            if (visited >= 600 || apps.length() >= 200) break;
            visited++;
            if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.exported) continue;
            String packageName = info.activityInfo.packageName;
            if (!seen.add(packageName)) continue;
            apps.put(new JSONObject().put("packageName", packageName).put("label", limited(info.loadLabel(packages), MAX_TEXT)));
        }
        return new JSONObject().put("ok", true).put("apps", apps).put("scope", "visible_launcher_apps")
                .put("truncated", visited < found.size());
    }

    // A model turn may legitimately take longer than a screen capture. Keep the
    // latest reference until replacement/pause, and re-read its exact native
    // target or pixels before dispatch instead of expiring it while the model thinks.
    private Snapshot requireReference(JSONObject args, RunControl control) throws Exception {
        String id = string(args, "observationId", 100, false);
        Snapshot current = reference.get();
        if (current == null || !current.id.equals(id) || current.control != control
                || current.revision != control.pauseRevision())
            throw new PhoneFailure("stale_observation", "页面引用已过期，请重新观察。");
        return current;
    }

    private void validate(PhoneAccessibilityService service, Snapshot expected, RunControl control) throws Exception {
        validate(service, expected, control, true);
    }

    private Tree validate(PhoneAccessibilityService service, Snapshot expected, RunControl control, boolean needsTree) throws Exception {
        validateReference(service,expected,control);
        Tree fresh = readTree(service);
        if ((needsTree && (!fresh.available || fresh.incomplete)) || !expected.tree.sameScreen(fresh))
            throw new PhoneFailure("stale_observation", "当前页面与观察不一致，请重新观察。");
        return fresh;
    }

    private void validateReference(PhoneAccessibilityService service,Snapshot expected,RunControl control)throws Exception {
        guard(service,control,expected.revision);requireUnlocked(service);
        if(reference.get()!=expected||expected.service!=service||expected.control!=control)
            throw new PhoneFailure("stale_observation","页面引用已过期，请重新观察。");
    }
    private Tree validateTarget(PhoneAccessibilityService service,Snapshot expected,RunControl control,AccessibilityForest.Ref target)throws Exception {
        validateReference(service,expected,control);Tree fresh=readTree(service,target);
        if(!expected.tree.screen.same(fresh.screen)||!expected.tree.forest.sameTarget(target,fresh.forest))
            throw new PhoneFailure("stale_node","目标、祖先、窗口或遮挡已变化，请重新观察。");
        validateReference(service,expected,control);return fresh;
    }

    private JSONObject nodeAction(PhoneAccessibilityService service, Snapshot expected, RunControl control,
                                  String tool, String id, String text) throws Exception {
        return nodeAction(service,expected,control,tool,id,text,null,null,null);
    }
    private JSONObject nodeAction(PhoneAccessibilityService service,Snapshot expected,RunControl control,String tool,String id,String text,NativePhoneEntry.Admission admission,String direction)throws Exception {
        return nodeAction(service,expected,control,tool,id,text,admission,direction,null);
    }
    private JSONObject nodeAction(PhoneAccessibilityService service,Snapshot expected,RunControl control,String tool,String id,String text,NativePhoneEntry.Admission admission,String direction,dev.pocketengine.phone.observation.DelayedInputReadback delayed)throws Exception {
        JSONObject base=receipt(true,"node_action_requested");
        if("phone_type".equals(tool))base.put("nativeTextReadback",dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("native_readback_unavailable_after_dispatch"))
                .put("delayedTextReadback",dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("delayed_native_readback_unavailable"));
        NativeNodeActionReceipt acknowledgement=new NativeNodeActionReceipt(base);
        try{return onNodeWorker(service,control,expected.revision,()->nodeActionOnWorker(service,expected,control,tool,id,text,admission,direction,delayed,acknowledgement));}
        catch(Exception unavailable){
            JSONObject retained=acknowledgement.afterReadFailure("node_followup_unavailable");
            if(retained==null)throw unavailable;
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            clearReferences();return retained;
        }
    }
    private JSONObject nodeActionOnWorker(PhoneAccessibilityService service,Snapshot expected,RunControl control,String tool,String id,String text,NativePhoneEntry.Admission admission,String direction,dev.pocketengine.phone.observation.DelayedInputReadback delayed,NativeNodeActionReceipt acknowledgement)throws Exception {
        validateReference(service,expected,control);
        AccessibilityForest.Ref selected = null;
        if (id != null) selected = expected.tree.forest.refs.get(id);
        else {
            for (AccessibilityForest.Ref node : expected.tree.forest.refs.values()) {
                if (node.editable && node.focused) {
                    if (selected != null) throw new PhoneFailure("ambiguous_input", "有多个输入目标，请指定 nodeId。");
                    selected = node;
                }
            }
        }
        if (selected == null) throw new PhoneFailure("node_unavailable", "找不到目标节点，请重新观察。");
        AccessibilityForest.Ref requested=selected;
        validateTarget(service,expected,control,requested);
        if("phone_click_node".equals(tool)) {
            selected=expected.tree.forest.clickTarget(requested);
            if(selected==null)throw new PhoneFailure("not_clickable","目标及其原父级没有声明点击操作。");
            if(selected!=requested)validateTarget(service,expected,control,selected);
        }
        AccessibilityNodeInfo node = AccessibilityForest.resolve(service, Display.DEFAULT_DISPLAY, selected);
        if (node == null) throw new PhoneFailure("stale_node", "目标节点已失效，请重新观察。");
        AccessibilityNodeInfo readbackIdentity=null;
        try {
            if (!node.refresh() || !AccessibilityForest.matches(selected,node))
                throw new PhoneFailure("stale_node", "目标节点发生变化，请重新观察。");
            if (!node.isVisibleToUser() || !node.isEnabled()) throw new PhoneFailure("node_unavailable", "目标节点当前不可操作。");
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            Rect controls=onMain(control,expected.revision,service::controlsBounds);
            if (Rect.intersects(bounds, controls))
                throw new PhoneFailure("controls_overlap", "目标被任务控制条遮挡，请手动移动页面后重新观察。");
            if ("phone_type".equals(tool)) {
                if (node.isPassword()) throw new PhoneFailure("password_input_refused", "密码输入请由用户手动完成。");
                if (!AccessibilityForest.supportsSetText(node)) throw new PhoneFailure("not_editable", "目标不是可编辑输入框。");
            } else if("phone_scroll_node".equals(tool)) {
                if(node.isPassword())throw new PhoneFailure("requires_user","不自动操作密码节点。");
                int action="forward".equals(direction)?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                if(!AccessibilityForest.supportsScroll(node,action))throw new PhoneFailure("not_scrollable","目标未声明此方向的原生滚动动作。");
            } else if (!AccessibilityForest.supportsClick(node)) throw new PhoneFailure("not_clickable", "目标未声明可点击，请重新观察并选择其他目标。");
            // Recheck this exact target's relevant context after resolve/refresh, not an unrelated ticker.
            Tree sensitiveContext=validateTarget(service,expected,control,selected);
            if (!node.refresh() || !AccessibilityForest.matches(selected,node))
                throw new PhoneFailure("stale_node", "目标节点发生变化，请重新观察。");
            requireUnlocked(service);
            if(requested!=selected) {
                if(!expected.tree.forest.sameTarget(requested,sensitiveContext.forest))
                    throw new PhoneFailure("stale_node","原点击文字或图标已变化，请重新观察。");
                NativeSensitiveStep.requireNode(sensitiveContext.forest,requested,tool,control,expected.revision);
            }
            NativeSensitiveStep.requireNode(sensitiveContext.forest,selected,tool,control,expected.revision);
            boolean accepted;
            if("phone_scroll_node".equals(tool)) {
                final AccessibilityNodeInfo selectedNode=node;int action="forward".equals(direction)?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                guard(service,control,expected.revision);
                accepted=NativePhoneEntry.call(control,expected.revision,java.util.Objects.requireNonNull(admission),()->{PhoneReadGate.markEffectEntered();clearReferences();return acknowledgement.record(selectedNode.performAction(action));});
            } else if ("phone_type".equals(tool)) {
                if (node.isPassword()) throw new PhoneFailure("password_input_refused", "密码输入请由用户手动完成。");
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                readbackIdentity=AccessibilityNodeInfo.obtain(node);
                guard(service, control, expected.revision);
                accepted = NativePhoneEntry.call(control,expected.revision,()->guard(service,control,expected.revision),()->{PhoneReadGate.markEffectEntered();clearReferences();return acknowledgement.record(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));});
            } else {
                guard(service, control, expected.revision);
                accepted = NativePhoneEntry.call(control,expected.revision,()->guard(service,control,expected.revision),()->{PhoneReadGate.markEffectEntered();clearReferences();return acknowledgement.record(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));});
            }
            // AccessibilityInteractionClient also returns false when the remote action's
            // callback times out or its Binder connection is lost after dispatch.
            if(!accepted)return failure("action_unknown","系统未确认原节点操作结果，请核对原页面；不会自动重试。")
                    .put("accepted",JSONObject.NULL).put("nativeActionResult",false).put("requiresReconciliation",true);
            JSONObject returned=receipt(true, "node_action_requested");
            if(accepted&&"phone_type".equals(tool))returned.put("nativeTextReadback",readTextAfterInput(service,node,readbackIdentity,selected,text,control,expected.revision));
            if(accepted&&"phone_type".equals(tool)&&delayed!=null){
                final AccessibilityNodeInfo retained=AccessibilityNodeInfo.obtain(node),identity=AccessibilityNodeInfo.obtain(readbackIdentity);final AccessibilityForest.Ref original=selected;
                delayed.schedule(main,reads.executor(),()->readTextAfterInput(service,retained,identity,original,text,control,expected.revision),()->{retained.recycle();identity.recycle();});
            }
            acknowledgement.update(returned);return returned;
        } finally { if(readbackIdentity!=null)readbackIdentity.recycle();node.recycle(); }
    }

    private JSONObject readTextAfterInput(PhoneAccessibilityService service,AccessibilityNodeInfo node,AccessibilityNodeInfo identity,AccessibilityForest.Ref ref,String wanted,RunControl control,long revision)throws Exception {
        try{guard(service,control,revision);requireUnlocked(service);
            if(identity==null||!node.refresh()||!node.equals(identity)||node.getWindowId()!=ref.windowId||!AccessibilityForest.supportsSetText(node)||!java.util.Objects.equals(node.getViewIdResourceName(),identity.getViewIdResourceName())||!String.valueOf(node.getClassName()).equals(String.valueOf(identity.getClassName()))||!String.valueOf(node.getPackageName()).equals(String.valueOf(identity.getPackageName())))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_native_node_unavailable");
            AccessibilityNodeInfo scoped=AccessibilityForest.resolve(service,Display.DEFAULT_DISPLAY,ref);try{if(scoped==null||!scoped.equals(node))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_display_window_path_unavailable");}finally{if(scoped!=null&&scoped!=node&&scoped!=identity)scoped.recycle();}
            if(node.isPassword())return dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(null,null,true);
            JSONObject value=dev.pocketengine.phone.observation.PhoneActionEffects.inputReadback(node.getText(),wanted,false);guard(service,control,revision);
            return value.put("phase","immediate_after_dispatch").put("observedAtUptimeMs",SystemClock.uptimeMillis()).put("mechanism","refresh_same_accessibility_node").put("windowId",ref.windowId).put("path",new JSONArray(ref.path)).put("displayId",Display.DEFAULT_DISPLAY).put("serviceSessionId",service.observationSessionId()).put("evidenceOnly",true);
        }catch(Exception unavailable){return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("native_readback_unavailable_after_dispatch");}
    }

    private JSONObject gesture(PhoneAccessibilityService service, Snapshot expected, JSONObject args,
                               boolean swipe, RunControl control) throws Exception {
        return gesture(service,expected,args,swipe,control,()->{});
    }
    private JSONObject gesture(PhoneAccessibilityService service, Snapshot expected, JSONObject args,
                               boolean swipe, RunControl control,NativePhoneEntry.Admission admission) throws Exception {
        int x1 = coordinate(args, swipe ? "x1" : "x"), y1 = coordinate(args, swipe ? "y1" : "y");
        int x2 = swipe ? coordinate(args, "x2") : x1, y2 = swipe ? coordinate(args, "y2") : y1;
        int duration = swipe ? integer(args, "durationMs", 100, 2000, 500) : 60;
        if (expected.imageDigest == null)
            throw new PhoneFailure("coordinate_unverifiable", "坐标操作需要最新的含截图观察。");
        CapturedImage currentImage;
        try { currentImage = capture(service, control, expected.revision); }
        catch (PhoneFailure failure) {
            if (ObservationSafety.requiresPause(failure.code)) throw failure;
            throw new PhoneFailure("coordinate_unverifiable", "无法验证当前画面，未提交手势。请重新观察。原因：" + failure.code);
        }
        if (!expected.imageDigest.equals(currentImage.digest)
                || expected.tree.screen.width != currentImage.fullWidth || expected.tree.screen.height != currentImage.fullHeight)
            throw new PhoneFailure("stale_observation", "当前画面像素已改变，未提交手势。请重新观察。");
        PhoneUiSignals.Stamp validated=validateForUi(service,expected,control,false,args,swipe);
        CompletableFuture<String> completion = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean dispatchAccepted=new java.util.concurrent.atomic.AtomicBoolean();
        final boolean accepted;
        try{accepted = onMain(control, expected.revision, () -> {
            validateReference(service,expected,control);
            Screen screen = expected.tree.screen;
            float startX = pixel(x1, screen.width), startY = pixel(y1, screen.height);
            float endX = pixel(x2, screen.width), endY = pixel(y2, screen.height);
            Rect swept = new Rect((int) Math.floor(Math.min(startX, endX)), (int) Math.floor(Math.min(startY, endY)),
                    (int) Math.ceil(Math.max(startX, endX)) + 1, (int) Math.ceil(Math.max(startY, endY)) + 1);
            if (Rect.intersects(swept, service.controlsBounds()))
                throw new PhoneFailure("controls_overlap", "手势经过任务控制条，请选择其他位置。");
            Path path = new Path();
            path.moveTo(startX, startY);
            if (swipe) path.lineTo(endX, endY);
            GestureDescription description = new GestureDescription.Builder()
                    .setDisplayId(Display.DEFAULT_DISPLAY)
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            requireUnlocked(service);
            if (!screen.same(screen(service))) throw new PhoneFailure("stale_observation", "屏幕尺寸发生变化，请重新观察。");
            guard(service, control, expected.revision);
            return NativePhoneEntry.call(control,expected.revision,admission,()->{
                Long deadline=MAIN_DEADLINE.get();
                if(deadline!=null&&SystemClock.uptimeMillis()>deadline)throw new PhoneFailure("dispatch_unknown","手机请求超时，请核对原操作。");
                if(PhoneAccessibilityService.get()!=service)throw new PhoneFailure("service_disconnected","无障碍服务已变化。");
                requireUiUnchanged(service,validated);clearReferences();
                boolean dispatched=dispatchGesture(service,description,new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) { completion.complete("completed"); }
                    @Override public void onCancelled(GestureDescription gesture) { completion.complete("cancelled"); }
                });
                if(dispatched)dispatchAccepted.set(true);return dispatched;
            });
        });}catch(Exception unavailable){if(dispatchAccepted.get())return acceptedGestureReadFailure(completion,unavailable);throw unavailable;}
        if (!accepted) return receipt(false, "gesture_rejected");
        try {
            String state = await(completion, ()->guard(service,control,expected.revision), duration + 3500L);
            if ("cancelled".equals(state)) return ObservationReport.annotateFailure(new JSONObject().put("ok", false).put("accepted", true)
                    .put("verified", false).put("state", "gesture_cancelled").put("error", "gesture_cancelled")
                    .put("message", "系统已接收手势，但手势已取消。请重新观察。"));
            return receipt(true, "gesture_" + state);
        } catch (Exception unavailable) {
            return acceptedGestureReadFailure(completion,unavailable);
        }
    }

    private JSONObject acceptedGestureReadFailure(CompletableFuture<String> completion,Exception unavailable)throws Exception {
        if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
        if("completed".equals(completion.getNow(null)))return receipt(true,"gesture_completed").put("requiresPause",true)
                .put("postActionObservation","unavailable").put("dispatchReceiptPreserved",true).put("cancelSupported",false).put("automaticReplayAllowed",false);
        return ObservationReport.annotateFailure(new JSONObject().put("ok",false).put("accepted",true).put("verified",false)
                .put("state","gesture_completion_unknown").put("error","gesture_completion_unknown").put("cancelSupported",false)
                .put("subsequentStepsStopped",true).put("dispatchReceiptPreserved",true).put("requiresReconciliation",true).put("automaticReplayAllowed",false)
                .put("message","系统已接收手势，但完成结果尚未确认。请核对原页面，勿重放动作。"));
    }

    private boolean dispatchGesture(PhoneAccessibilityService service,GestureDescription description,AccessibilityService.GestureResultCallback callback)throws PhoneFailure {
        try{return service.dispatchGesture(description,callback,main);}
        catch(RuntimeException unknown){throw new PhoneFailure("action_unknown","手势请求的系统回执未知，请核对原页面；不会自动重试。");}
    }

    private CapturedImage capture(PhoneAccessibilityService service, RunControl control, long revision) throws Exception {
        return capture(service, () -> guard(service, control, revision));
    }

    private CapturedImage capture(PhoneAccessibilityService service, OwnerScreenContext.ReadCheck check) throws Exception {
        return capture(service,check,true);
    }
    private CapturedImage capture(PhoneAccessibilityService service, OwnerScreenContext.ReadCheck check,boolean includeImage) throws Exception {
        // The platform may throttle screenshots. Space our requests; never retry a gesture on capture failure.
        long spacing = 1_000 - (SystemClock.uptimeMillis() - lastScreenshotRequest);
        if (spacing > 0) sleepRead(check, spacing);
        Object hideToken = new Object();
        CompletableFuture<AccessibilityService.ScreenshotResult> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicLong requestedAt=new java.util.concurrent.atomic.AtomicLong();
        Runnable restore = () -> service.restoreAfterCapture(hideToken);
        boolean consumed = false;
        try {
            onMainRead(check, () -> {
                check.check();
                service.hideForCapture(hideToken);
                // Give the overlay surface time to become invisible before requesting the display image.
                main.postDelayed(() -> {
                    if (result.isDone()) { restore.run(); return; }
                    try {
                        requireUnlocked(service);
                        check.check();
                        lastScreenshotRequest = SystemClock.uptimeMillis();
                        requestedAt.set(lastScreenshotRequest);
                        service.takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run,
                                new AccessibilityService.TakeScreenshotCallback() {
                            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                                if (!result.complete(screenshot)) screenshot.getHardwareBuffer().close();
                                main.post(restore);
                            }
                            @Override public void onFailure(int code) {
                                result.completeExceptionally(new PhoneFailure(code == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ? "permission_denied"
                                        : code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ? "display_unavailable"
                                        : code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "screenshot_secure_window"
                                        : code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? "screenshot_throttled" : "screenshot_unavailable", "系统截图失败（" + code + "），请重新观察。"));
                                main.post(restore);
                            }
                        });
                    } catch (Exception error) {
                        result.completeExceptionally(error);
                        restore.run();
                    }
                }, 100);
                return null;
            });
            AccessibilityService.ScreenshotResult screenshot;
            try { screenshot = await(result, check, 5_000); }
            catch (TimeoutException timeout) { throw new PhoneFailure("screenshot_timeout", "截图超时，请重新观察。"); }
            consumed = true;
            return encode(screenshot, check, requestedAt.get(),service.observationSessionId(),includeImage);
        } finally {
            // Cancel before draining: a late callback then owns and closes its own HardwareBuffer.
            if (!consumed && !result.cancel(false) && !result.isCompletedExceptionally()) {
                AccessibilityService.ScreenshotResult unused = result.getNow(null);
                if (unused != null) unused.getHardwareBuffer().close();
            }
            main.post(restore);
        }
    }

    private CapturedImage encode(AccessibilityService.ScreenshotResult screenshot, OwnerScreenContext.ReadCheck check,long requestedAt,String session,boolean includeImage) throws Exception {
        Bitmap hardware = null, software = null, scaled = null;
        try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
            check.check();
            long capturedAt=screenshot.getTimestamp();
            if(requestedAt<=0||capturedAt<requestedAt||capturedAt>SystemClock.uptimeMillis())
                throw new PhoneFailure("screenshot_stale_frame","系统返回的截图不属于当前请求，请重新观察。");
            hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (hardware == null) throw new PhoneFailure("screenshot_unavailable", "无法读取系统截图。");
            int fullWidth = hardware.getWidth(), fullHeight = hardware.getHeight();
            if (fullWidth > 8192 || fullHeight > 8192 || (long) fullWidth * fullHeight > 16_000_000)
                throw new PhoneFailure("screenshot_too_large", "屏幕图像超过安全处理范围。");
            software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) throw new PhoneFailure("screenshot_unavailable", "无法转换系统截图。");
            final Bitmap sampled=software;
            JSONObject visual=visualFrames.record(session,fullWidth,fullHeight,capturedAt*1_000_000L,sampled::getPixel)
                    .put("sourceFrameFresh",true).put("framePending",false).put("source","accessibility_screenshot");
            check.check();
            if(!includeImage)return new CapturedImage(new JSONObject().put("visualEvidence",visual),null,fullWidth,fullHeight);
            MessageDigest comparison = MessageDigest.getInstance("SHA-256");
            int[] pixels = new int[fullWidth];
            byte[] row = new byte[fullWidth * 4];
            boolean visualContent=false;
            for (int y = 0; y < fullHeight; y++) {
                check.check();
                software.getPixels(pixels, 0, fullWidth, 0, y, fullWidth, 1);
                for (int x = 0; x < fullWidth; x++) {
                    int pixel = pixels[x], offset = x * 4;
                    visualContent|=(pixel&0x00ffffff)!=0;
                    row[offset] = (byte) (pixel >>> 24);
                    row[offset + 1] = (byte) (pixel >>> 16);
                    row[offset + 2] = (byte) (pixel >>> 8);
                    row[offset + 3] = (byte) pixel;
                }
                comparison.update(row);
            }
            if(!visualContent)throw new PhoneFailure("screenshot_blank_frame","截图没有可用于视觉定位的内容；保留当前可读节点，请等待页面产生画面。",visual.put("allPixelsBlack",true));
            String digest = Base64.encodeToString(comparison.digest(), Base64.NO_WRAP);
            float factor = Math.min(1f, 1280f / Math.max(software.getWidth(), software.getHeight()));
            int width = Math.max(1, Math.round(software.getWidth() * factor));
            int height = Math.max(1, Math.round(software.getHeight() * factor));
            scaled = factor < 1 ? Bitmap.createScaledBitmap(software, width, height, true) : software;
            check.check();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, bytes))
                throw new PhoneFailure("screenshot_unavailable", "无法编码截图。");
            check.check();
            JSONObject image = new JSONObject().put("imageBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    .put("imageMimeType", "image/jpeg").put("imageWidth", width).put("imageHeight", height).put("capturedAtUptimeMs",capturedAt).put("visualEvidence",visual);
            return new CapturedImage(image, digest, fullWidth, fullHeight);
        } finally {
            if (scaled != null && scaled != software) scaled.recycle();
            if (software != null) software.recycle();
            if (hardware != null) hardware.recycle();
        }
    }

    private Tree readTree(PhoneAccessibilityService service) throws Exception {
        return readTree(service,null);
    }
    private Tree readTree(PhoneAccessibilityService service,AccessibilityForest.Ref target) throws Exception {
        PhoneReadGate.checkCurrent();
        if (service != PhoneAccessibilityService.get()) throw new PhoneFailure("service_disconnected", "请先启用 PocketEngine 无障碍服务。");
        requireUnlocked(service);
        Screen screen = screen(service);
        AccessibilityForest.Check check=() -> { PhoneReadGate.checkCurrent();if(service!=PhoneAccessibilityService.get())throw new PhoneFailure("service_disconnected","无障碍服务已变化。"); requireUnlocked(service); };
        AccessibilityForest forest=target==null?AccessibilityForest.read(service,Display.DEFAULT_DISPLAY,screen.width,screen.height,check)
                :AccessibilityForest.readForTarget(service,Display.DEFAULT_DISPLAY,screen.width,screen.height,target,check);
        return new Tree(screen,forest);
    }

    private Screen screen(PhoneAccessibilityService service) throws PhoneFailure {
        Display display = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new PhoneFailure("display_unavailable", "主屏幕不可用。");
        DisplayMetrics size = new DisplayMetrics();
        display.getRealMetrics(size);
        if (size.widthPixels <= 0 || size.heightPixels <= 0) throw new PhoneFailure("display_unavailable", "无法读取屏幕尺寸。");
        return new Screen(size.widthPixels, size.heightPixels, display.getRotation());
    }

    private static void requireUnlocked(PhoneAccessibilityService service) throws PhoneFailure {
        String failure = service.observationSafetyFailure();
        if (failure != null) throw new PhoneFailure(failure, "screen_locked".equals(failure)
                ? "请手动解锁并保持屏幕亮起。" : "手机操作权限已断开，请恢复后继续原任务。");
    }

    private static PhoneAccessibilityService requireService() throws PhoneFailure {
        PhoneAccessibilityService service = PhoneAccessibilityService.get();
        if (service == null) throw new PhoneFailure("service_disconnected", "请先启用 PocketEngine 无障碍服务。");
        return service;
    }

    private static void guard(PhoneAccessibilityService service, RunControl control, long revision) throws Exception {
        control.check();PhoneReadGate.checkCurrent();
        Long deadline = MAIN_DEADLINE.get();
        if (deadline != null && SystemClock.uptimeMillis() > deadline)
            throw new PhoneFailure("dispatch_unknown", "手机请求超时，执行状态未知。请重新观察，勿盲目重试。");
        if (control.isPaused() || control.pauseRevision() != revision) throw new PhoneFailure("paused_or_changed", "任务曾暂停，请重新观察。");
        if (PhoneAccessibilityService.get() != service) throw new PhoneFailure("service_disconnected", "无障碍服务已断开。");
    }

    /** Back/Home target the current window, not a particular node or its text. */
    private PhoneUiSignals.Stamp validateNavigationForUi(PhoneAccessibilityService service,Snapshot expected,RunControl control)throws Exception {
        return onTreeRead(()->guard(service,control,expected.revision),()->{
            PhoneUiSignals.Stamp before=service.uiSignals().snapshot(java.util.Collections.emptySet());
            validateReference(service,expected,control);
            Tree fresh=readTree(service);
            // Preserve display, focused/active windows and modal/IME topology.
            // A clock, text update or bounded node traversal does not change the
            // navigation destination while this exact window layout stays current.
            if(!expected.tree.sameWindowLayout(fresh))
                throw new PhoneFailure("stale_observation","导航窗口、焦点或屏幕已变化，请重新观察。");
            validateReference(service,expected,control);
            requireUiUnchanged(service,before);return before;
        });
    }

    private PhoneUiSignals.Stamp validateForUi(PhoneAccessibilityService service,Snapshot expected,RunControl control,boolean needsTree)throws Exception {
        return validateForUi(service,expected,control,needsTree,null,false);
    }
    private PhoneUiSignals.Stamp validateForUi(PhoneAccessibilityService service,Snapshot expected,RunControl control,boolean needsTree,JSONObject point,boolean swipe)throws Exception {
        return onTreeRead(()->guard(service,control,expected.revision),()->{
            PhoneUiSignals.Stamp before=service.uiSignals().snapshot(java.util.Collections.emptySet());
            Tree fresh=validate(service,expected,control,needsTree);
            if(point!=null)NativeSensitiveStep.requireCoordinate(fresh.forest,coordinate(point,swipe?"x1":"x"),coordinate(point,swipe?"y1":"y"),swipe?coordinate(point,"x2"):coordinate(point,"x"),swipe?coordinate(point,"y2"):coordinate(point,"y"),swipe,control,expected.revision);
            requireUiUnchanged(service,before);return before;
        });
    }
    /** Events only invalidate the physically validated tree across the UI queue; they never authorize an action. */
    private static void requireUiUnchanged(PhoneAccessibilityService service,PhoneUiSignals.Stamp before)throws PhoneFailure {
        if(before.closed||!before.same(service.uiSignals().snapshot(java.util.Collections.emptySet())))
            throw new PhoneFailure("stale_observation","页面在原生界面调度期间变化，未派发旧动作。");
    }

    private <T>T onTreeRead(OwnerScreenContext.ReadCheck check,MainCall<T> call)throws Exception {
        try{return reads.call(check::check,call::run);}
        catch(PhoneReadGate.Unavailable unavailable){throw new PhoneFailure(unavailable.code,"页面读取超时或仍在等待系统，旧引用未发布；接管和停止仍可使用。");}
    }
    private <T>T onNodeWorker(PhoneAccessibilityService service,RunControl control,long revision,MainCall<T> call)throws Exception {
        try{return reads.call(()->guard(service,control,revision),call::run);}
        catch(PhoneReadGate.Unavailable unavailable){
            if(unavailable.getCause() instanceof InterruptedException)Thread.currentThread().interrupt();
            if("native_effect_unknown".equals(unavailable.code))
                throw new PhoneFailure("dispatch_unknown","手机请求等待超时，已入场动作可能未知；旧引用已停用，不会自动重试。");
            throw new PhoneFailure(unavailable.code,"页面读取尚未完成，未派发动作；请等原读取结束后重新观察。");
        }
    }

    private <T> T onMain(RunControl control, long revision, MainCall<T> call) throws Exception {
        return onMainRead(() -> {
            control.check();
            if (control.isPaused() || revision != control.pauseRevision())
                throw new PhoneFailure("paused_or_changed", "任务曾暂停，请重新观察。");
        }, call);
    }

    private <T> T onMainRead(OwnerScreenContext.ReadCheck check, MainCall<T> call) throws Exception {
        // A queued callback can be withdrawn without an unknown native effect.
        // Once it enters, preserve its receipt or keep the conservative unknown result.
        long deadline = SystemClock.uptimeMillis() + MAIN_WAIT_MS;
        java.util.concurrent.atomic.AtomicInteger entry=new java.util.concurrent.atomic.AtomicInteger();
        FutureTask<T> task = new FutureTask<>(() -> {
            check.check();
            if (SystemClock.uptimeMillis() > deadline || !entry.compareAndSet(0,1))
                throw new PhoneFailure("main_timeout", "手机界面调度已超时，未派发操作；请重新观察。");
            MAIN_DEADLINE.set(deadline);
            try { return call.run(); } finally { MAIN_DEADLINE.remove(); }
        });
        if(!main.post(task)) {
            entry.compareAndSet(0,2);task.cancel(false);
            throw new PhoneFailure("main_timeout","手机界面未接收调度，未派发操作。");
        }
        try {
            while (true) {
                if(task.isDone())return task.get();
                try{check.check();}catch(Exception changed){
                    // Withdrawal and callback entry share one CAS. A callback
                    // cannot start later after a not-dispatched result was returned.
                    boolean withdrawn=entry.compareAndSet(0,2);
                    if(task.isDone())return task.get();
                    if(!withdrawn)throw new PhoneFailure("dispatch_unknown","原生界面请求已入场但回执尚未确认；已停止后续操作，不会自动重放。");
                    throw changed;
                }
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) {
                    boolean withdrawn=entry.compareAndSet(0,2);
                    if(task.isDone())return task.get();
                    if(withdrawn)throw new PhoneFailure("main_timeout","手机界面调度已超时，未派发操作；请等界面恢复后重新观察。");
                    throw new PhoneFailure("dispatch_unknown","手机请求已入场但回执超时，请核对原操作，勿盲目重试。");
                }
                try { return task.get(Math.min(100, left), TimeUnit.MILLISECONDS); }
                catch (TimeoutException retryWait) { }
            }
        } catch (ExecutionException error) { throw unwrap(error); }
        finally { entry.compareAndSet(0,2);task.cancel(false); }
    }

    private static <T> T await(CompletableFuture<T> result, RunControl control, long timeout) throws Exception {
        return await(result, control::check, timeout);
    }

    private static <T> T await(CompletableFuture<T> result, OwnerScreenContext.ReadCheck check, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (true) {
            check.check();
            long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) throw new TimeoutException();
            try { return result.get(Math.min(100, left), TimeUnit.MILLISECONDS); }
            catch (TimeoutException retryWait) { }
            catch (ExecutionException error) { throw unwrap(error); }
        }
    }

    private static void sleepRead(OwnerScreenContext.ReadCheck check, long millis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + millis;
        while (SystemClock.uptimeMillis() < deadline) {
            check.check(); Thread.sleep(Math.min(100, Math.max(1, deadline - SystemClock.uptimeMillis())));
        }
        check.check();
    }

    private static Exception unwrap(ExecutionException error) {
        Throwable cause = error.getCause();
        return cause instanceof Exception ? (Exception) cause : new IllegalStateException("Android callback failed", cause);
    }

    private static void workerOnly() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("PhonePort requires a worker thread");
    }
    private static String string(JSONObject args, String key, int maximum, boolean allowEmpty) throws Exception {
        Object value = args.opt(key);
        if (!(value instanceof String) || ((String) value).length() > maximum || (!allowEmpty && ((String) value).isEmpty()))
            throw new PhoneFailure("invalid_arguments", key + " 必须是有效字符串。");
        return (String) value;
    }
    private static String optionalString(JSONObject args, String key, int maximum) throws Exception {
        return !args.has(key) ? null : string(args, key, maximum, false);
    }
    private static int coordinate(JSONObject args, String key) throws Exception { return integer(args, key, 0, 1000, null); }
    private static int integer(JSONObject args, String key, int minimum, int maximum, Integer fallback) throws Exception {
        if (!args.has(key) && fallback != null) return fallback;
        Object value = args.opt(key);
        if (!(value instanceof Number)) throw new PhoneFailure("invalid_arguments", key + " 必须是整数。");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < minimum || number > maximum)
            throw new PhoneFailure("invalid_arguments", key + " 超出允许范围。");
        return (int) number;
    }
    private static String limited(CharSequence value, int maximum) {
        return value == null ? "" : value.subSequence(0, Math.min(value.length(), maximum)).toString();
    }
    private static int normalized(int value, int size) { return Math.max(0, Math.min(1000, Math.round(value * 1000f / size))); }
    private static float pixel(int value, int size) { return value * (size - 1) / 1000f; }
    private static JSONObject receipt(boolean accepted, String state) throws Exception {
        return new JSONObject().put("ok", accepted).put("accepted", accepted).put("verified", false).put("state", state)
                .put("message", accepted ? "系统已接收请求，尚未验证页面或业务结果。" : "系统未接收请求，请重新观察。")
                .put("error", accepted ? JSONObject.NULL : "action_rejected");
    }
    private static JSONObject failure(String code, String message) throws Exception {
        return ObservationReport.annotateFailure(new JSONObject().put("ok", false).put("error", code).put("message", message).put("verified", false)
                .put("accepted", "dispatch_unknown".equals(code) ? JSONObject.NULL : false));
    }

    private interface MainCall<T> { T run() throws Exception; }
    private static final class PhoneFailure extends Exception {
        final String code;
        final JSONObject visualEvidence;
        PhoneFailure(String code, String message) { this(code,message,null); }
        PhoneFailure(String code, String message,JSONObject visualEvidence) { super(message); this.code = code; this.visualEvidence=visualEvidence; }
    }
    private static final class Screen {
        final int width, height, rotation;
        Screen(int width, int height, int rotation) { this.width = width; this.height = height; this.rotation = rotation; }
        boolean same(Screen other) { return width == other.width && height == other.height && rotation == other.rotation; }
    }
    private static final class Tree {
        final Screen screen;
        final int windowId;
        final String packageName, signature;
        final JSONArray jsonNodes;
        final AccessibilityForest forest;
        final boolean incomplete, available;
        Tree(Screen screen,AccessibilityForest forest){
            this.screen=screen;this.forest=forest;windowId=forest.windowId;packageName=forest.packageName;
            signature=forest.signature;jsonNodes=forest.nodes;incomplete=forest.truncated;available=forest.available;
        }
        boolean sameScreen(Tree other){return available==other.available&&screen.same(other.screen)&&signature.equals(other.signature);}
        boolean sameWindowLayout(Tree other){return screen.same(other.screen)&&forest.sameWindowLayout(other.forest);}
    }
    private static final class Snapshot {
        final String id = UUID.randomUUID().toString();
        final long created = SystemClock.uptimeMillis();
        final PhoneAccessibilityService service;
        final Tree tree;
        final RunControl control;
        final long revision;
        final PhoneInputMethod inputMethod;
        final long inputRevision;
        final String imageDigest;
        final int imageWidth,imageHeight;
        Snapshot(PhoneAccessibilityService service, Tree tree, RunControl control, long revision, CapturedImage image) {
            this.service = service; this.tree = tree; this.control = control; this.revision = revision;
            this.inputMethod=android.os.Build.VERSION.SDK_INT>=33?service.phoneInputMethod():null;
            this.inputRevision=inputMethod==null?-1:inputMethod.revision();
            this.imageDigest = image == null ? null : image.digest;
            this.imageWidth=image==null?0:image.json.optInt("imageWidth");this.imageHeight=image==null?0:image.json.optInt("imageHeight");
        }
    }
    private static final class CapturedImage {
        final JSONObject json;
        final String digest;
        final int fullWidth, fullHeight;
        CapturedImage(JSONObject json, String digest, int fullWidth, int fullHeight) {
            this.json = json; this.digest = digest; this.fullWidth = fullWidth; this.fullHeight = fullHeight;
        }
    }
}

