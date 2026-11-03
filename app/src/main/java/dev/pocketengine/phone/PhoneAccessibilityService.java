package dev.pocketengine.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.view.Display;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.EngineApplication;

/** Owner controls and access to the foreground display; no application-overlay permission. */
public final class PhoneAccessibilityService extends AccessibilityService {
    private static volatile PhoneAccessibilityService instance;
    private final String observationSessionId = java.util.UUID.randomUUID().toString();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PhoneUiSignals changeSignals=new PhoneUiSignals(observationSessionId,android.os.SystemClock::uptimeMillis);
    private final Set<Object> captureHides = new HashSet<>();
    private LinearLayout controls;
    private TextView status;
    private Button pause;
    private RunControl control;
    private Runnable cancellation;
    private EngineApplication ownerApp;
    private EngineApplication.Observer ownerObserver;
    private PhoneOwnerControls ownerControls;
    private QuickPanelCoordinator quickPanel;
    private volatile boolean visible;

    public static PhoneAccessibilityService get() { return instance; }
    /** A new service instance invalidates all observations from the previous connection. */
    public String observationSessionId() { return observationSessionId; }
    public PhoneUiSignals uiSignals(){return changeSignals;}

    /** An absent root is different from lost permission or a locked device. */
    public String observationSafetyFailure() {
        if (instance != this) return "service_disconnected";
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) == 0
                || (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) return "permission_denied";
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        if (keyguard == null || power == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()
                || !power.isInteractive()) return "screen_locked";
        return null;
    }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { if(event!=null)changeSignals.record(event.getWindowId()); }
    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        boolean wasCurrent=instance==this;
        if (wasCurrent) instance = null;
        changeSignals.close();
        if(wasCurrent)dev.pocketengine.phone.setup.PhoneSetupModule.onAccessibilityDisconnected();
        if (control != null) control.cancel();
        if (quickPanel != null) quickPanel.close();
        removeControls();
        captureHides.clear();
    }

    public void showControls(String taskId, String goal, RunControl runControl) {
        runOnMain(() -> {
            if (instance != this || runControl == null || runControl.isCancelled()) return;
            if (!(getApplication() instanceof EngineApplication)) return;
            EngineApplication app=(EngineApplication)getApplication();
            PhoneOwnerControls bound=new PhoneOwnerControls(app,taskId,runControl);
            if(!bound.view(goal).current)return;
            removeControls();
            control = runControl;
            ownerControls=bound;
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setCornerRadius(dp(16));
            background.setStroke(dp(1), 0xffd8d8d8);
            panel.setBackground(background);
            panel.setElevation(dp(4));
            status = new TextView(this);
            status.setTextColor(0xff202020);
            status.setTextSize(14);
            status.setMaxLines(2);
            status.setText(runControl.isPaused() ? "已请求暂停，返回应用查看状态" : shortText(goal, 100));
            panel.addView(status);
            LinearLayout actions = new LinearLayout(this);
            pause = button(runControl.isPaused() ? "继续" : "暂停");
            pause.setOnClickListener(v -> {
                bound.toggle();
                refreshOwnerControls(bound,goal);
            });
            Button stop = button("停止");
            stop.setOnClickListener(v -> bound.stop());
            Button back = button("返回应用");
            back.setOnClickListener(v -> {
                if(!bound.pause())return;
                refreshOwnerControls(bound,goal);
                Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (launch != null) {
                    launch.putExtra("quickPanelTaskId",taskId); // Existing Compose launcher resolves the exact original task, not the currently viewed conversation.
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try { startActivity(launch); } catch (RuntimeException ignored) { }
                }
            });
            actions.addView(pause);
            actions.addView(stop);
            actions.addView(back);
            panel.addView(actions);
            Button supplement = button("补充其他 App 页面");
            supplement.setOnClickListener(v -> {
                try {
                    if (!bound.view(goal).current || !runControl.isPaused()) throw new IllegalStateException("先暂停原主屏任务，再选择需要的页面");
                    org.json.JSONObject task = app.tasks.get(taskId, false);
                    if (task == null) throw new IllegalStateException("原任务暂不可核对");
                    showQuickPanel(new org.json.JSONObject().put("taskId", taskId).put("runId", task.getString("runId")).put("epoch", task.getInt("coordinatorEpoch")));
                } catch (Exception unavailable) { app.error(unavailable.getMessage() == null ? "请回原任务重新打开快捷补充" : unavailable.getMessage()); }
            });
            panel.addView(supplement);
            controls = panel;
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.END;
            layout.x = dp(8);
            layout.y = dp(48);
            layout.setTitle("PocketEngine task controls");
            try {
                getSystemService(WindowManager.class).addView(panel, layout);
                updateVisibility();
            } catch (RuntimeException unavailable) {
                removeControls();
                return;
            }
            cancellation = () -> main.post(() -> {
                if (control == runControl) removeControls();
            });
            runControl.onCancel(cancellation);
            ownerApp=app;
            ownerObserver=event->{
                org.json.JSONObject task=event.optJSONObject("task");
                if("task".equals(event.optString("type"))&&task!=null&&taskId.equals(task.optString("id")))
                    main.post(()->refreshOwnerControls(bound,goal));
            };
            app.addObserver(ownerObserver);
            refreshOwnerControls(bound,goal);
        });
    }

    private void refreshOwnerControls(PhoneOwnerControls expected,String goal) {
        if(ownerControls!=expected||pause==null||status==null)return;
        PhoneOwnerControls.ViewState state=expected.view(goal);
        pause.setText(state.paused?"继续":"暂停");
        pause.setEnabled(state.canToggle);
        status.setText(shortText(state.message,140));
    }

    public void hideControls() { runOnMain(this::removeControls); }
    public boolean hasVisibleControls() { return visible; }

    /** Only the visible owner UI opens this entry. Opening it never reads the foreground screen. */
    public boolean showQuickPanel(org.json.JSONObject expected) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("请从可见原生入口打开快捷补充");
            try {
                if (instance != this || !(getApplication() instanceof EngineApplication)
                        || !(getApplication() instanceof OwnerScreenContextHost))
                    throw new IllegalStateException("原生快捷补充宿主尚未准备，请回原任务核对");
                EngineApplication app = (EngineApplication) getApplication();
                org.json.JSONObject task = app.tasks.get(expected.getString("taskId"), false);
                if (task == null || !expected.getString("runId").equals(task.optString("runId"))
                        || expected.getInt("epoch") != task.optInt("coordinatorEpoch")
                        || !"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))
                        || task.optJSONObject("origin") != null && task.getJSONObject("origin").optString("kind").matches("channel|group")
                        || !task.optString("status").matches("preparing|running|pausing|paused|awaiting_user"))
                    throw new IllegalStateException("原任务身份或状态已变化，请在原任务重新打开快捷补充");
                if (quickPanel != null) throw new IllegalStateException("已有一份原任务快捷补充，请先关闭它再选择其他任务");
                String mode = task.getString("executionMode");
                if (!("phone".equals(task.optString("kind")) && mode.matches("main_screen|background")
                        || "assistant".equals(task.optString("kind")) && "capabilities".equals(mode)))
                    throw new IllegalStateException("这项任务请回原入口补充，未创建替代任务");
                quickPanel = new QuickPanelCoordinator(this, (OwnerScreenContextHost) getApplication(), task,
                        "background".equals(mode) ? "后台独立屏" : "main_screen".equals(mode) ? "手机主屏" : "助手工具任务");
                try { quickPanel.show(); updateVisibility(); }
                catch (RuntimeException unavailable) { quickPanel.close(); throw unavailable; }
                return true;
            } catch (Exception unavailable) {
                if (getApplication() instanceof EngineApplication) ((EngineApplication) getApplication()).error(
                        unavailable.getMessage() == null ? "快捷补充暂不可打开，请回原任务核对" : unavailable.getMessage());
                return false;
            }
    }

    void quickPanelClosed(QuickPanelCoordinator closed) { if (quickPanel == closed) quickPanel = null; }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xff202020);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private void removeControls() {
        if (control != null && cancellation != null) control.removeCancelAction(cancellation);
        if(ownerApp!=null&&ownerObserver!=null)ownerApp.removeObserver(ownerObserver);
        ownerApp=null;
        ownerObserver=null;
        ownerControls=null;
        cancellation = null;
        control = null;
        visible = false;
        if (controls != null) {
            try { getSystemService(WindowManager.class).removeView(controls); } catch (RuntimeException ignored) { }
        }
        controls = null;
        status = null;
        pause = null;
    }

    // Called only on main. Tokens let timeout/late callback cleanup remain idempotent.
    void hideForCapture(Object token) { captureHides.add(token); updateVisibility(); }
    void restoreAfterCapture(Object token) { captureHides.remove(token); updateVisibility(); }

    private void updateVisibility() {
        visible = controls != null && captureHides.isEmpty();
        if (controls != null) controls.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (quickPanel != null) quickPanel.captureHidden(!captureHides.isEmpty());
    }

    Rect controlsBounds() {
        Rect bounds = new Rect();
        if (controls != null && visible) {
            if (controls.getWidth() == 0 || controls.getHeight() == 0) {
                return new Rect(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
            }
            int[] location = new int[2];
            controls.getLocationOnScreen(location);
            bounds.set(location[0], location[1], location[0] + controls.getWidth(), location[1] + controls.getHeight());
        }
        if (quickPanel != null && quickPanel.visible()) {
            View panel = quickPanel.view();
            if (panel.getWidth() == 0 || panel.getHeight() == 0) return new Rect(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
            int[] location = new int[2]; panel.getLocationOnScreen(location);
            bounds.union(location[0], location[1], location[0] + panel.getWidth(), location[1] + panel.getHeight());
        }
        return bounds;
    }

    /** Caller owns/recycles the returned node. Accessibility overlays are never observed. */
    AccessibilityNodeInfo foregroundRoot() {
        List<AccessibilityWindowInfo> windows = getWindows();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        try {
            if (active != null) {
                boolean overlay = false;
                boolean onDefaultDisplay = false;
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getDisplayId() == Display.DEFAULT_DISPLAY && window.getId() == active.getWindowId()) {
                        onDefaultDisplay = true;
                        if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) overlay = true;
                    }
                }
                if (onDefaultDisplay && !overlay) return active;
                active.recycle();
                active = null;
            }
            AccessibilityWindowInfo selected = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() != Display.DEFAULT_DISPLAY
                        || window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (window.isActive() || window.isFocused()) {
                    if (selected == null || window.getLayer() > selected.getLayer()) selected = window;
                }
            }
            return selected == null ? null : selected.getRoot();
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    Handler mainHandler() { return main; }
    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String shortText(String value, int maximum) {
        if (value == null) return "正在执行任务";
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }
}
