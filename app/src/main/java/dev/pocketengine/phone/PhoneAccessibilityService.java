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
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.EngineApplication;

/** Owner controls and access to the foreground display; no application-overlay permission. */
public final class PhoneAccessibilityService extends AccessibilityService {
    private static final Object INSTANCE_LOCK = new Object();
    private static final long WINDOW_MAP_REFRESH_MS = 250;
    private static final long WINDOW_MAP_TTL_MS = 1_000;
    private static final int MAX_MAPPED_WINDOWS = 128;
    // A stuck Binder read keeps this single process-wide slot; reconnecting must not spawn
    // replacement workers that accumulate behind uninterruptible platform calls.
    private static final java.util.concurrent.ScheduledThreadPoolExecutor WINDOW_READER = windowReader();
    private static java.util.concurrent.ScheduledThreadPoolExecutor windowReader() {
        var reader=new java.util.concurrent.ScheduledThreadPoolExecutor(1,work->{
            Thread thread=new Thread(work,"pocket-accessibility-window-map");thread.setDaemon(true);return thread;
        });
        reader.setRemoveOnCancelPolicy(true);return reader;
    }
    private static volatile PhoneAccessibilityService instance;
    private final String observationSessionId = java.util.UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicLong keyFocusRevision = new java.util.concurrent.atomic.AtomicLong();
    private volatile PhoneInputMethod phoneInputMethod;
    private final Handler main = new Handler(Looper.getMainLooper());
    // INSTANCE_LOCK guards feed publication and ownership; platform reads stay outside it.
    private final Map<Integer,PhoneUiSignals> displaySignals = new LinkedHashMap<>();
    private final Map<Integer,Integer> windowDisplays = new LinkedHashMap<>();
    private boolean signalsClosed;
    private boolean windowMapReadScheduled;
    private long signalSerial,nextWindowMapRead,windowMapExpires,windowMapRevision;
    private java.util.concurrent.ScheduledFuture<?> windowMapRead;
    private DisplayManager signalDisplays;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayChanged(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) {
            synchronized (INSTANCE_LOCK) {
                if (instance != PhoneAccessibilityService.this || signalsClosed) return;
                PhoneUiSignals old = displaySignals.remove(displayId);
                if (old != null) old.close();
                windowDisplays.entrySet().removeIf(entry -> entry.getValue() == displayId);
                windowMapExpires = 0;++windowMapRevision;
            }
        }
    };
    private final Set<Object> captureHides = new HashSet<>();
    private LinearLayout controls;
    private TextView status;
    private Button pause;
    private Button controlGrip;
    private LinearLayout controlDetails;
    private PhoneControlOverlayPosition controlPosition;
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
    public long keyFocusRevision(){return keyFocusRevision.get();}
    PhoneInputMethod phoneInputMethod(){return phoneInputMethod;}
    @Override public android.accessibilityservice.InputMethod onCreateInputMethod(){
        phoneInputMethod=new PhoneInputMethod(this);keyFocusRevision.incrementAndGet();return phoneInputMethod;
    }
    public PhoneUiSignals uiSignals(){return uiSignals(Display.DEFAULT_DISPLAY);}
    /** Stable per-display hints; only unattributed changes conservatively invalidate every feed. */
    public PhoneUiSignals uiSignals(int displayId) {
        if (displayId < 0) throw new IllegalArgumentException("A native display id is required");
        synchronized (INSTANCE_LOCK) {
            PhoneUiSignals feed = displaySignals.get(displayId);
            if (feed == null) {
                String session = displayId == Display.DEFAULT_DISPLAY ? observationSessionId
                        : observationSessionId + ":display:" + displayId + ":" + ++signalSerial;
                feed = new PhoneUiSignals(session,SystemClock::uptimeMillis);
                if (instance == this && !signalsClosed) displaySignals.put(displayId,feed);
            }
            if (instance != this || signalsClosed) feed.close();
            return feed;
        }
    }

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

    @Override protected void onServiceConnected() {
        PhoneAccessibilityService previous;
        synchronized (INSTANCE_LOCK) {
            if (signalsClosed) return;
            previous = instance;
            if (previous != null && previous != this) previous.closeSignalFeedsLocked();
            instance = this;
            uiSignals();
        }
        if (previous != null && previous != this) previous.unregisterSignalDisplays();
        if (signalDisplays == null) try {
            DisplayManager displays = getSystemService(DisplayManager.class);
            if (displays != null) {
                displays.registerDisplayListener(displayListener,main);
                signalDisplays = displays;
            }
        } catch (RuntimeException unavailable) { /* Service teardown still closes every feed. */ }
        dev.pocketengine.phone.setup.PhoneSetupModule.onAccessibilityConnected();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || instance != this) return;
        int type=event.getEventType();
        if(type==AccessibilityEvent.TYPE_VIEW_FOCUSED||type==AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED||type==AccessibilityEvent.TYPE_WINDOWS_CHANGED
                ||type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)keyFocusRevision.incrementAndGet();
        int displayId = eventDisplay(event);
        synchronized (INSTANCE_LOCK) {
            if (instance != this || signalsClosed) return;
            // Only native consumers create feeds. Unrelated display traffic retains no state.
            if (displayId >= 0) {
                PhoneUiSignals feed = displaySignals.get(displayId);
                if (feed != null) feed.record(event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED?-1:event.getWindowId());
            } else {
                // Unattributed change is uncertainty, not evidence of any display's ownership.
                // Fence every existing observation until a later fresh read resolves its scene.
                for (PhoneUiSignals feed : displaySignals.values()) feed.record(-1);
            }
        }
    }
    @Override public void onInterrupt() { }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (controls != null && controlPosition != null) {
            PhoneControlOverlayPosition position = controlPosition;
            controls.post(position::keepVisible);
        }
    }

    /** API 33 carries display identity. Earlier/unknown events require a known window mapping. */
    private int eventDisplay(AccessibilityEvent event) {
        int directDisplay = Build.VERSION.SDK_INT >= 33 ? event.getDisplayId() : Display.INVALID_DISPLAY;
        int windowId = event.getWindowId();
        long now = SystemClock.uptimeMillis();
        synchronized (INSTANCE_LOCK) {
            if (instance != this || signalsClosed) return Display.INVALID_DISPLAY;
            Integer known = now < windowMapExpires ? windowDisplays.get(windowId) : null;
            boolean windowsChanged = event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            if (windowsChanged) {
                windowDisplays.clear();
                windowMapExpires = 0;++windowMapRevision;
            }
            if (directDisplay >= 0) return directDisplay;
            if (windowId < 0) return Display.INVALID_DISPLAY;
            if (windowsChanged && (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0)
                return known == null ? Display.INVALID_DISPLAY : known;
            if (!windowsChanged && known != null) return known;
            scheduleWindowMapReadLocked(now);
            return Display.INVALID_DISPLAY;
        }
    }

    private void scheduleWindowMapReadLocked(long now) {
        if (windowMapReadScheduled || signalsClosed || instance != this) return;
        windowMapReadScheduled = true;
        try {
            windowMapRead=WINDOW_READER.schedule(this::refreshWindowMap,Math.max(0,nextWindowMapRead-now),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closed) { windowMapReadScheduled = false; }
    }

    private void refreshWindowMap() {
        final long revision;
        synchronized (INSTANCE_LOCK) {
            if (instance != this || signalsClosed) {windowMapReadScheduled=false;return;}
            revision=windowMapRevision;nextWindowMapRead=SystemClock.uptimeMillis()+WINDOW_MAP_REFRESH_MS;
        }
        Map<Integer,Integer> mapped = new LinkedHashMap<>();
        SparseArray<List<AccessibilityWindowInfo>> windows = null;
        try {
            // Metadata only, at most four refreshes per second on systems lacking event display ids.
            windows = getWindowsOnAllDisplays();
            if (windows != null) for (int i=0;i<windows.size();i++) {
                int displayId = windows.keyAt(i);
                List<AccessibilityWindowInfo> group = windows.valueAt(i);
                if (displayId < 0 || group == null) continue;
                for (AccessibilityWindowInfo window : group) {
                    if (window == null || window.getId() < 0) continue;
                    int id = window.getId();
                    Integer previous = mapped.get(id);
                    if (previous != null && previous != displayId) mapped.put(id,Display.INVALID_DISPLAY);
                    else if (previous == null && mapped.size() < MAX_MAPPED_WINDOWS) mapped.put(id,displayId);
                }
            }
        } catch (RuntimeException unavailable) { mapped.clear(); }
        finally {
            if (windows != null) for (int i=0;i<windows.size();i++) {
                List<AccessibilityWindowInfo> group = windows.valueAt(i);
                if (group != null) for (AccessibilityWindowInfo window : group) if (window != null) try {window.recycle();}
                    catch (RuntimeException recycled) { /* Do not leave the bounded worker permanently pending. */ }
            }
        }
        synchronized (INSTANCE_LOCK) {
            windowMapReadScheduled=false;
            if (instance != this || signalsClosed || revision != windowMapRevision) return;
            windowDisplays.clear();windowDisplays.putAll(mapped);
            windowMapExpires = SystemClock.uptimeMillis()+WINDOW_MAP_TTL_MS;
        }
    }

    private void closeSignalFeedsLocked() {
        signalsClosed = true;
        for (PhoneUiSignals feed : displaySignals.values()) feed.close();
        windowDisplays.clear();windowMapExpires = 0;++windowMapRevision;
        if(windowMapRead!=null){windowMapRead.cancel(true);windowMapRead=null;}
    }

    private void unregisterSignalDisplays() {
        DisplayManager displays = signalDisplays;signalDisplays = null;
        if (displays != null) try { displays.unregisterDisplayListener(displayListener); }
        catch (RuntimeException unavailable) { /* Closed feeds reject late callbacks. */ }
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        boolean wasCurrent;
        synchronized (INSTANCE_LOCK) {
            wasCurrent = instance == this;
            if (wasCurrent) instance = null;
            closeSignalFeedsLocked();
        }
        unregisterSignalDisplays();
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
            panel.setPadding(dp(4), dp(4), dp(4), dp(4));
            GradientDrawable background = new GradientDrawable();
            background.setColor(dev.pocketengine.ui.UiAppearance.surface(this));
            background.setCornerRadius(dp(16));
            background.setStroke(dp(1), dev.pocketengine.ui.UiAppearance.outline(this));
            panel.setBackground(background);
            panel.setElevation(dp(4));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(8), dp(4), dp(8), dp(4));
            details.setVisibility(View.GONE);
            controlDetails = details;
            status = new TextView(this);
            status.setTextColor(dev.pocketengine.ui.UiAppearance.foreground(this));
            status.setTextSize(14);
            status.setMaxLines(3);
            status.setMaxWidth(Math.max(1, Math.min(dp(280), getResources().getDisplayMetrics().widthPixels - dp(32))));
            status.setText(runControl.isPaused() ? "已请求暂停，返回应用查看状态" : shortText(goal, 100));
            details.addView(status);
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            Button grip = button("任务 ▾");
            controlGrip = grip;
            grip.setContentDescription("拖动可移动手机任务控制条；点击展开操作");
            grip.setOnClickListener(v -> {
                boolean expand = details.getVisibility() != View.VISIBLE;
                details.setVisibility(expand ? View.VISIBLE : View.GONE);
                grip.setText(expand ? "任务 ▴" : "任务 ▾");
            });
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
            actions.addView(grip);
            actions.addView(pause);
            actions.addView(stop);
            panel.addView(actions);
            LinearLayout more = new LinearLayout(this);
            more.addView(back);
            Button supplement = button("补充任务");
            supplement.setOnClickListener(v -> {
                try {
                    if (!bound.pause()) return;
                    refreshOwnerControls(bound,goal);
                    var original = runControl.hostRunIdentity();
                    if (original == null) throw new IllegalStateException("原任务暂不可核对");
                    if (showQuickPanel(new org.json.JSONObject().put("taskId", taskId).put("runId", original.runId()).put("epoch", original.epoch()))) {
                        details.setVisibility(View.GONE); grip.setText("任务 ▾");
                    }
                } catch (Exception unavailable) { app.error(unavailable.getMessage() == null ? "请回原任务重新打开快捷补充" : unavailable.getMessage()); }
            });
            more.addView(supplement); details.addView(more); panel.addView(details);
            controls = panel;
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.LEFT;
            layout.x = dp(8);
            layout.y = dp(48);
            layout.setTitle("PocketEngine task controls");
            PhoneControlOverlayPosition position = new PhoneControlOverlayPosition(panel, layout);
            controlPosition = position;
            grip.setOnTouchListener(position);
            try {
                getSystemService(WindowManager.class).addView(panel, layout);
                panel.post(position::keepVisible);
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
        if(controlGrip!=null)controlGrip.setContentDescription("拖动可移动手机任务控制条；点击"+(controlDetails!=null&&controlDetails.getVisibility()==View.VISIBLE?"收起":"展开")+"操作。"+state.message);
    }

    public void hideControls() { runOnMain(this::removeControls); }
    public void hideControls(RunControl original) { runOnMain(()->{if(original!=null&&control==original)removeControls();}); }
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
        b.setTextColor(dev.pocketengine.ui.UiAppearance.foreground(this));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dev.pocketengine.ui.UiAppearance.surfaceVariant(this)));
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
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
        controlGrip = null;
        controlDetails = null;
        controlPosition = null;
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
