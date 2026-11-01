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
import dev.pocketengine.phone.observation.NativeOcrTapPort;
import dev.pocketengine.phone.observation.OcrTextTarget;
import dev.pocketengine.phone.observation.NativePhoneEntry;

/** A single foreground-display executor. References are data, never retained Android nodes. */
public final class AndroidPhonePort implements PhonePort,NativeOcrTapPort,dev.pocketengine.phone.observation.NativeTreeScrollPort,dev.pocketengine.phone.observation.NativePhoneSignals {
    private static final int MAX_TEXT = 400;
    private static final long REFERENCE_LIFETIME_MS = 20_000;
    private static final long MAIN_WAIT_MS = 5_000;
    private static final ThreadLocal<Long> MAIN_DEADLINE = new ThreadLocal<>();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Snapshot> reference = new AtomicReference<>();
    private volatile long lastScreenshotRequest;
    private volatile boolean lastIncludeImage;

    public AndroidPhonePort(Context context) { this.context = context.getApplicationContext(); }
    @Override public PhoneUiSignals uiSignals(RunControl control)throws Exception {control.check();if(control.isPaused())return null;PhoneAccessibilityService service=requireService();requireUnlocked(service);return service.uiSignals();}
    @Override public boolean isConnected() { return PhoneAccessibilityService.get() != null; }
    @Override public void clearReferences() { reference.set(null); }

    /** Read-only owner preview. It never installs action references or touches the task's physical port. */
    OwnerScreenContext.Captured captureForContext(boolean includeScreenshot, OwnerScreenContext.ReadCheck owner) throws Exception {
        workerOnly(); owner.check();
        PhoneAccessibilityService service = requireService();
        OwnerScreenContext.ReadCheck check = () -> { owner.check(); requireUnlocked(service); };
        Tree before = onMainRead(check, () -> readTree(service));
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
        Tree after = onMainRead(check, () -> readTree(service));
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
        try{arguments=target.tapArguments(expected.tree.screen.width,expected.tree.screen.height);}
        catch(IllegalArgumentException invalid){return failure("coordinate_unverifiable",invalid.getMessage());}
        // The normal gesture path re-captures and compares the full-resolution pixel digest,
        // current tree/service/control/revision and overlay bounds before its one native action.
        try {
            control.checkpoint();PhoneAccessibilityService service=requireService();
            if(requireReference(arguments,control)!=expected)return failure("stale_observation","OCR目标已失效，请重新观察。");
            return gesture(service,expected,arguments,false,control,admission);
        }catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
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
            Tree before = onMain(control, revision, () -> readTree(service));
            CapturedImage image = includeImage ? capture(service, control, revision) : null;
            Tree after = includeImage ? onMain(control, revision, () -> readTree(service)) : before;
            if (!before.sameScreen(after)) throw new PhoneFailure("screen_changed", "页面在观察期间发生变化，请重新观察。");
            if (image != null && (image.fullWidth != after.screen.width || image.fullHeight != after.screen.height))
                throw new PhoneFailure("screen_changed", "截图与当前屏幕尺寸不一致，请重新观察。");
            control.check();
            if (control.isPaused() || control.pauseRevision() != revision || service != PhoneAccessibilityService.get())
                throw new PhoneFailure("observation_expired", "任务状态已改变，请重新观察。");
            Snapshot snapshot = new Snapshot(service, after, control, revision, image);
            JSONObject result = new JSONObject().put("ok", true).put("observationId", snapshot.id)
                    .put("packageName", after.packageName).put("screenWidth", after.screen.width)
                    .put("screenHeight", after.screen.height).put("nodes", after.jsonNodes)
                    .put("truncated", after.incomplete).put("displayId", Display.DEFAULT_DISPLAY)
                    .put("sessionId", service.observationSessionId()).put("executionMode", "main")
                    .put("observedAtUptimeMs", snapshot.created).put("pauseRevision", revision);
            if (image != null) {
                for (String key : new String[]{"imageBase64", "imageMimeType", "imageWidth", "imageHeight"})
                    result.put(key, image.json.get(key));
            }
            after.forest.attach(result);
            reference.set(snapshot);
            return ObservationReport.attach(result, after.available, after.available ? "accessibility_tree" : "no_accessible_root",
                    image != null, image != null ? "accessibility_screenshot" : "not_requested");
        } catch (PhoneFailure failure) { return failure(failure.code, failure.getMessage()); }
        catch (SecurityException failure) { return failure("permission_denied", "系统未允许读取当前页面。"); }
        catch (RuntimeException failure) { return failure("platform_error", "Android 未能读取当前页面，请重新观察。"); }
    }

    @Override public synchronized JSONObject scrollNode(JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        workerOnly();dev.pocketengine.phone.observation.NativeTreeScrollPort.validate(args);if(admission==null)return failure("host_authority_required","节点滚动必须由原prepared操作准入。");control.check();
        if(control.isPaused())return failure("task_paused","原执行已暂停，未滚动。");
        try{Snapshot expected=requireReference(args,control);PhoneAccessibilityService service=requireService();
            return onMain(control,expected.revision,()->nodeAction(service,expected,control,"phone_scroll_node",args.getString("nodeId"),null,admission,args.getString("direction")));
        }catch(NativePhoneEntry.Refused refused){clearReferences();return failure("paused_or_changed",refused.getMessage());}
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
                        clearReferences();
                        service.startActivity(intent);
                        return receipt(true, "launch_requested");
                    });
                }
                case "phone_back":
                case "phone_home": {
                    Snapshot expected = requireReference(args, control);
                    return onMain(control, expected.revision, () -> {
                        validate(service, expected, control);
                        requireUnlocked(service);
                        guard(service, control, expected.revision);
                        clearReferences();
                        boolean accepted = service.performGlobalAction("phone_back".equals(name)
                                ? AccessibilityService.GLOBAL_ACTION_BACK : AccessibilityService.GLOBAL_ACTION_HOME);
                        return receipt(accepted, "global_action_requested");
                    });
                }
                case "phone_click_node":
                case "phone_type": {
                    Snapshot expected = requireReference(args, control);
                    String nodeId = "phone_click_node".equals(name)
                            ? string(args, "nodeId", 100, false) : optionalString(args, "nodeId", 100);
                    String text = "phone_type".equals(name) ? string(args, "text", 12_000, true) : null;
                    dev.pocketengine.phone.observation.DelayedInputReadback delayed=new dev.pocketengine.phone.observation.DelayedInputReadback();
                    JSONObject receipt=onMain(control, expected.revision, () -> nodeAction(service, expected, control, name, nodeId, text,null,null,delayed));
                    return delayed.attach(receipt);
                }
                case "phone_tap":
                case "phone_swipe": {
                    Snapshot expected = requireReference(args, control);
                    return gesture(service, expected, parameters, "phone_swipe".equals(name), control);
                }
                default: throw new PhoneFailure("unknown_tool", "不支持的手机工具。");
            }
        } catch (NativePhoneEntry.Refused refused) {
            clearReferences();return failure("paused_or_changed",refused.getMessage());
        } catch (PhoneFailure failure) {
            clearReferences();
            return failure(failure.code, failure.getMessage());
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
            if (++visited > 600 || apps.length() >= 200) break;
            if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.exported) continue;
            String packageName = info.activityInfo.packageName;
            if (!seen.add(packageName)) continue;
            apps.put(new JSONObject().put("packageName", packageName).put("label", limited(info.loadLabel(packages), MAX_TEXT)));
        }
        return new JSONObject().put("ok", true).put("apps", apps).put("scope", "visible_launcher_apps")
                .put("truncated", visited < found.size());
    }

    private Snapshot requireReference(JSONObject args, RunControl control) throws Exception {
        String id = string(args, "observationId", 100, false);
        Snapshot current = reference.get();
        if (current == null || !current.id.equals(id) || current.control != control
                || SystemClock.uptimeMillis() - current.created > REFERENCE_LIFETIME_MS
                || current.revision != control.pauseRevision())
            throw new PhoneFailure("stale_observation", "页面引用已过期，请重新观察。");
        return current;
    }

    private void validate(PhoneAccessibilityService service, Snapshot expected, RunControl control) throws Exception {
        validate(service, expected, control, true);
    }

    private void validate(PhoneAccessibilityService service, Snapshot expected, RunControl control, boolean needsTree) throws Exception {
        guard(service, control, expected.revision);
        requireUnlocked(service);
        if (reference.get() != expected || expected.service != service || expected.control != control
                || SystemClock.uptimeMillis() - expected.created > REFERENCE_LIFETIME_MS)
            throw new PhoneFailure("stale_observation", "页面引用已过期，请重新观察。");
        Tree fresh = readTree(service);
        if ((needsTree && (!fresh.available || fresh.incomplete)) || !expected.tree.sameScreen(fresh))
            throw new PhoneFailure("stale_observation", "当前页面与观察不一致，请重新观察。");
    }

    private JSONObject nodeAction(PhoneAccessibilityService service, Snapshot expected, RunControl control,
                                  String tool, String id, String text) throws Exception {
        return nodeAction(service,expected,control,tool,id,text,null,null,null);
    }
    private JSONObject nodeAction(PhoneAccessibilityService service,Snapshot expected,RunControl control,String tool,String id,String text,NativePhoneEntry.Admission admission,String direction)throws Exception {
        return nodeAction(service,expected,control,tool,id,text,admission,direction,null);
    }
    private JSONObject nodeAction(PhoneAccessibilityService service,Snapshot expected,RunControl control,String tool,String id,String text,NativePhoneEntry.Admission admission,String direction,dev.pocketengine.phone.observation.DelayedInputReadback delayed)throws Exception {
        validate(service, expected, control);
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
        AccessibilityNodeInfo node = AccessibilityForest.resolve(service, Display.DEFAULT_DISPLAY, selected);
        if (node == null) throw new PhoneFailure("stale_node", "目标节点已失效，请重新观察。");
        AccessibilityNodeInfo readbackIdentity=null;
        try {
            if (!node.refresh() || !selected.identity.equals(AccessibilityForest.identity(node)))
                throw new PhoneFailure("stale_node", "目标节点发生变化，请重新观察。");
            if (!node.isVisibleToUser() || !node.isEnabled()) throw new PhoneFailure("node_unavailable", "目标节点当前不可操作。");
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (Rect.intersects(bounds, service.controlsBounds()))
                throw new PhoneFailure("controls_overlap", "目标被任务控制条遮挡，请手动移动页面后重新观察。");
            if ("phone_type".equals(tool)) {
                if (node.isPassword()) throw new PhoneFailure("password_input_refused", "密码输入请由用户手动完成。");
                if (!node.isEditable()) throw new PhoneFailure("not_editable", "目标不是可编辑输入框。");
            } else if("phone_scroll_node".equals(tool)) {
                if(node.isPassword())throw new PhoneFailure("requires_user","不自动操作密码节点。");
                int action="forward".equals(direction)?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                if(!node.isScrollable()||node.getActionList().stream().noneMatch(a->a.getId()==action))throw new PhoneFailure("not_scrollable","目标未声明此方向的原生滚动动作。");
            } else if (!node.isClickable()) throw new PhoneFailure("not_clickable", "目标未声明可点击，请重新观察并选择其他目标。");
            // Recheck the complete tree after resolving/refreshing, then check pause immediately at dispatch.
            validate(service, expected, control);
            if (!node.refresh() || !selected.identity.equals(AccessibilityForest.identity(node)))
                throw new PhoneFailure("stale_node", "目标节点发生变化，请重新观察。");
            requireUnlocked(service);
            boolean accepted;
            if("phone_scroll_node".equals(tool)) {
                final AccessibilityNodeInfo selectedNode=node;int action="forward".equals(direction)?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                guard(service,control,expected.revision);
                accepted=NativePhoneEntry.call(control,expected.revision,java.util.Objects.requireNonNull(admission),()->{clearReferences();return selectedNode.performAction(action);});
            } else if ("phone_type".equals(tool)) {
                if (node.isPassword()) throw new PhoneFailure("password_input_refused", "密码输入请由用户手动完成。");
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                readbackIdentity=AccessibilityNodeInfo.obtain(node);
                guard(service, control, expected.revision);
                accepted = NativePhoneEntry.call(control,expected.revision,()->guard(service,control,expected.revision),()->{clearReferences();return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);});
            } else {
                guard(service, control, expected.revision);
                accepted = NativePhoneEntry.call(control,expected.revision,()->guard(service,control,expected.revision),()->{clearReferences();return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);});
            }
            JSONObject returned=receipt(accepted, "node_action_requested");
            if(accepted&&"phone_type".equals(tool))returned.put("nativeTextReadback",readTextAfterInput(service,node,readbackIdentity,selected,text,control,expected.revision));
            if(accepted&&"phone_type".equals(tool)&&delayed!=null){
                final AccessibilityNodeInfo retained=AccessibilityNodeInfo.obtain(node),identity=AccessibilityNodeInfo.obtain(readbackIdentity);final AccessibilityForest.Ref original=selected;
                delayed.schedule(main,()->readTextAfterInput(service,retained,identity,original,text,control,expected.revision),()->{retained.recycle();identity.recycle();});
            }
            return returned;
        } finally { if(readbackIdentity!=null)readbackIdentity.recycle();node.recycle(); }
    }

    private JSONObject readTextAfterInput(PhoneAccessibilityService service,AccessibilityNodeInfo node,AccessibilityNodeInfo identity,AccessibilityForest.Ref ref,String wanted,RunControl control,long revision)throws Exception {
        try{guard(service,control,revision);requireUnlocked(service);
            if(identity==null||!node.refresh()||!node.equals(identity)||node.getWindowId()!=ref.windowId||!node.isEditable()||!java.util.Objects.equals(node.getViewIdResourceName(),identity.getViewIdResourceName())||!String.valueOf(node.getClassName()).equals(String.valueOf(identity.getClassName()))||!String.valueOf(node.getPackageName()).equals(String.valueOf(identity.getPackageName())))return dev.pocketengine.phone.observation.PhoneActionEffects.unavailableReadback("original_native_node_unavailable");
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
        CompletableFuture<String> completion = new CompletableFuture<>();
        boolean accepted = onMain(control, expected.revision, () -> {
            validate(service, expected, control, false);
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
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            requireUnlocked(service);
            if (!screen.same(screen(service))) throw new PhoneFailure("stale_observation", "屏幕尺寸发生变化，请重新观察。");
            guard(service, control, expected.revision);
            return NativePhoneEntry.call(control,expected.revision,admission,()->{
                Long deadline=MAIN_DEADLINE.get();
                if(deadline!=null&&SystemClock.uptimeMillis()>deadline)throw new PhoneFailure("dispatch_unknown","手机请求超时，请核对原操作。");
                if(PhoneAccessibilityService.get()!=service)throw new PhoneFailure("service_disconnected","无障碍服务已变化。");
                clearReferences();
                return service.dispatchGesture(description, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) { completion.complete("completed"); }
                    @Override public void onCancelled(GestureDescription gesture) { completion.complete("cancelled"); }
                }, main);
            });
        });
        if (!accepted) return receipt(false, "gesture_rejected");
        try {
            String state = await(completion, control, duration + 3500L);
            if ("cancelled".equals(state)) return ObservationReport.annotateFailure(new JSONObject().put("ok", false).put("accepted", true)
                    .put("verified", false).put("state", "gesture_cancelled").put("error", "gesture_cancelled")
                    .put("message", "系统已接收手势，但手势已取消。请重新观察。"));
            return receipt(true, "gesture_" + state);
        } catch (TimeoutException timeout) {
            return ObservationReport.annotateFailure(new JSONObject().put("ok", false).put("accepted", true).put("verified", false)
                    .put("state", "gesture_completion_unknown").put("error", "gesture_completion_unknown")
                    .put("message", "系统已接收手势，但未收到完成回调。请重新观察，勿盲目重试。"));
        }
    }

    private CapturedImage capture(PhoneAccessibilityService service, RunControl control, long revision) throws Exception {
        return capture(service, () -> guard(service, control, revision));
    }

    private CapturedImage capture(PhoneAccessibilityService service, OwnerScreenContext.ReadCheck check) throws Exception {
        // The platform may throttle screenshots. Space our requests; never retry a gesture on capture failure.
        long spacing = 1_000 - (SystemClock.uptimeMillis() - lastScreenshotRequest);
        if (spacing > 0) sleepRead(check, spacing);
        Object hideToken = new Object();
        CompletableFuture<AccessibilityService.ScreenshotResult> result = new CompletableFuture<>();
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
            return encode(screenshot, check);
        } finally {
            // Cancel before draining: a late callback then owns and closes its own HardwareBuffer.
            if (!consumed && !result.cancel(false) && !result.isCompletedExceptionally()) {
                AccessibilityService.ScreenshotResult unused = result.getNow(null);
                if (unused != null) unused.getHardwareBuffer().close();
            }
            main.post(restore);
        }
    }

    private CapturedImage encode(AccessibilityService.ScreenshotResult screenshot, OwnerScreenContext.ReadCheck check) throws Exception {
        Bitmap hardware = null, software = null, scaled = null;
        try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
            check.check();
            hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (hardware == null) throw new PhoneFailure("screenshot_unavailable", "无法读取系统截图。");
            int fullWidth = hardware.getWidth(), fullHeight = hardware.getHeight();
            if (fullWidth > 8192 || fullHeight > 8192 || (long) fullWidth * fullHeight > 16_000_000)
                throw new PhoneFailure("screenshot_too_large", "屏幕图像超过安全处理范围。");
            software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) throw new PhoneFailure("screenshot_unavailable", "无法转换系统截图。");
            MessageDigest comparison = MessageDigest.getInstance("SHA-256");
            int[] pixels = new int[fullWidth];
            byte[] row = new byte[fullWidth * 4];
            for (int y = 0; y < fullHeight; y++) {
                check.check();
                software.getPixels(pixels, 0, fullWidth, 0, y, fullWidth, 1);
                for (int x = 0; x < fullWidth; x++) {
                    int pixel = pixels[x], offset = x * 4;
                    row[offset] = (byte) (pixel >>> 24);
                    row[offset + 1] = (byte) (pixel >>> 16);
                    row[offset + 2] = (byte) (pixel >>> 8);
                    row[offset + 3] = (byte) pixel;
                }
                comparison.update(row);
            }
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
                    .put("imageMimeType", "image/jpeg").put("imageWidth", width).put("imageHeight", height);
            return new CapturedImage(image, digest, fullWidth, fullHeight);
        } finally {
            if (scaled != null && scaled != software) scaled.recycle();
            if (software != null) software.recycle();
            if (hardware != null) hardware.recycle();
        }
    }

    private Tree readTree(PhoneAccessibilityService service) throws Exception {
        if (service != PhoneAccessibilityService.get()) throw new PhoneFailure("service_disconnected", "请先启用 PocketEngine 无障碍服务。");
        requireUnlocked(service);
        Screen screen = screen(service);
        AccessibilityForest forest=AccessibilityForest.read(service,Display.DEFAULT_DISPLAY,screen.width,screen.height,
                () -> { if(service!=PhoneAccessibilityService.get())throw new PhoneFailure("service_disconnected","无障碍服务已变化。"); requireUnlocked(service); });
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
        control.check();
        Long deadline = MAIN_DEADLINE.get();
        if (deadline != null && SystemClock.uptimeMillis() > deadline)
            throw new PhoneFailure("dispatch_unknown", "手机请求超时，执行状态未知。请重新观察，勿盲目重试。");
        if (control.isPaused() || control.pauseRevision() != revision) throw new PhoneFailure("paused_or_changed", "任务曾暂停，请重新观察。");
        if (PhoneAccessibilityService.get() != service) throw new PhoneFailure("service_disconnected", "无障碍服务已断开。");
    }

    private <T> T onMain(RunControl control, long revision, MainCall<T> call) throws Exception {
        return onMainRead(() -> {
            control.check();
            if (control.isPaused() || revision != control.pauseRevision())
                throw new PhoneFailure("paused_or_changed", "任务曾暂停，请重新观察。");
        }, call);
    }

    private <T> T onMainRead(OwnerScreenContext.ReadCheck check, MainCall<T> call) throws Exception {
        long deadline = SystemClock.uptimeMillis() + MAIN_WAIT_MS;
        FutureTask<T> task = new FutureTask<>(() -> {
            check.check();
            if (SystemClock.uptimeMillis() > deadline) throw new PhoneFailure("main_timeout", "手机操作调度超时。");
            MAIN_DEADLINE.set(deadline);
            try { return call.run(); } finally { MAIN_DEADLINE.remove(); }
        });
        main.post(task);
        try {
            while (true) {
                check.check();
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) throw new PhoneFailure("dispatch_unknown", "手机请求超时，执行状态未知。请重新观察，勿盲目重试。");
                try { return task.get(Math.min(100, left), TimeUnit.MILLISECONDS); }
                catch (TimeoutException retryWait) { }
            }
        } catch (ExecutionException error) { throw unwrap(error); }
        finally { task.cancel(false); }
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
        PhoneFailure(String code, String message) { super(message); this.code = code; }
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
    }
    private static final class Snapshot {
        final String id = UUID.randomUUID().toString();
        final long created = SystemClock.uptimeMillis();
        final PhoneAccessibilityService service;
        final Tree tree;
        final RunControl control;
        final long revision;
        final String imageDigest;
        final int imageWidth,imageHeight;
        Snapshot(PhoneAccessibilityService service, Tree tree, RunControl control, long revision, CapturedImage image) {
            this.service = service; this.tree = tree; this.control = control; this.revision = revision;
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
