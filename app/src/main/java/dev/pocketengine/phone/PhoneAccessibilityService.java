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

import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private final OwnerOverlayReads<PhoneOwnerControls.ViewState> openControlRead=new OwnerOverlayReads<>(main),shownControlRead=new OwnerOverlayReads<>(main);
    private PhoneOwnerControls openingControls;
    private RunControl openingRun;
    private OwnerReturnButton openingReturn;
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
    private android.widget.FrameLayout ownerActionSlot;
    private record RenderedOwnerAction(PhoneOwnerControls owner,PhoneOwnerControls.ViewState state,OwnerReturnButton hand){}
    private volatile RenderedOwnerAction renderedOwnerAction;
    private RenderedOwnerAction submittingOwnerAction;
    private volatile long ownerVisibilityVersion;
    private record FirstMainControl(RunControl control,java.util.function.BooleanSupplier current,
                                    java.util.function.Predicate<FirstMainScreenWindow> confirm){}
    private volatile FirstMainControl firstMainControl;
    // Separate from Pause/Stop/Resume: this worker only registers the same local readback reminder.
    private static final java.util.concurrent.ThreadPoolExecutor OWNER_RESULT_WATCH=new java.util.concurrent.ThreadPoolExecutor(0,1,20,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),r->{Thread t=new Thread(r,"pocket-owner-result-watch");t.setDaemon(true);return t;});
    private record ResultWatchOffer(PhoneOwnerControls owner){}
    private volatile ResultWatchOffer resultWatchOffer;
    private ResultWatchOffer submittingResultWatch;
    private PhoneOwnerControls allowedResultWatch;
    private Button resultWatchButton;
    // At most one explicit visible-owner resume in flight. Never queue a tap for a future screen.
    private static final java.util.concurrent.ThreadPoolExecutor OWNER_RESUME=new java.util.concurrent.ThreadPoolExecutor(0,1,20,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),r->{Thread t=new Thread(r,"pocket-visible-owner-resume");t.setDaemon(true);return t;});
    private record OwnerReturnButton(RunControl control,long pause,String operation,android.app.PendingIntent action,java.util.function.BooleanSupplier current){}
    private volatile OwnerReturnButton ownerReturnButton;
    public void bindOriginalOwnerReturn(RunControl original,long revision,String operation,android.app.PendingIntent action,java.util.function.BooleanSupplier current){
        runOnMain(()->{if(instance!=this||original==null||!current.getAsBoolean())return;
            var requested=new OwnerReturnButton(original,revision,operation,action,current);
            // The already granted hand-back may arrive while its original overlay is still being verified. Do not display or send yet.
            if(openingRun==original)openingReturn=requested;
            if(original!=control||ownerControls==null||pause==null)return;
            ownerReturnButton=requested;refreshOwnerControls(ownerControls,"原手机任务");});
    }
    public boolean ownerReturnVisible(RunControl original,long revision,String operation){
        OwnerReturnButton b=ownerReturnButton;RenderedOwnerAction shown=renderedOwnerAction;
        return visible&&instance==this&&b!=null&&shown!=null&&shown.hand()==b&&b.control()==original&&original.isPaused()&&!original.isCancelled()&&b.pause()==revision&&b.operation().equals(operation)&&returnReady(b);
    }
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
    private Object inAppStopOwner;
    private volatile long inAppStopUntil;
    private final Runnable inAppStopExpiry = () -> {
        clearInAppStopSurface();
        updateVisibility();
    };
    private long nextSystemPairingRead;

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
        inspectOwnerPairingDialog(event, displayId);
    }

    /** Only an explicit two-minute owner request can read the visible system pairing dialog. */
    private void inspectOwnerPairingDialog(AccessibilityEvent event, int displayId) {
        if (!dev.pocketengine.phone.setup.PhoneSetupModule.systemPairingCaptureActive()) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        if (displayId != Display.DEFAULT_DISPLAY && displayId != Display.INVALID_DISPLAY) return;
        CharSequence eventPackage = event.getPackageName();
        if (eventPackage == null || !eventPackage.toString().toLowerCase(java.util.Locale.ROOT).contains("settings")) return;
        long now = SystemClock.uptimeMillis();
        if (now < nextSystemPairingRead) return;
        nextSystemPairingRead = now + 300L;
        AccessibilityNodeInfo root = null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        ArrayList<String> labels = new ArrayList<>();
        try {
            if (observationSafetyFailure() != null) return;
            root = foregroundRoot();
            if (root == null || root.getPackageName() == null ||
                    !eventPackage.toString().contentEquals(root.getPackageName())) return;
            queue.add(root);
            root = null;
            int inspected = 0;
            while (!queue.isEmpty() && inspected++ < 96) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    // No editable, masked or password contents may enter the parser.
                    if (!node.isPassword() && !node.isEditable()) {
                        CharSequence title = node.getText(), description = node.getContentDescription();
                        if (title != null && title.length() <= 240) labels.add(title.toString());
                        if (description != null && description.length() <= 240 &&
                                (title == null || !description.toString().contentEquals(title))) labels.add(description.toString());
                    }
                    for (int i = 0; i < node.getChildCount() && queue.size() < 95; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            // A partial tree or huge dialog is uncertain, never treated as a valid code.
            if (queue.isEmpty() && labels.size() <= 96)
                dev.pocketengine.phone.setup.PhoneSetupModule.offerVisibleSystemPairing(
                        this, eventPackage.toString(), labels);
        } catch (RuntimeException unavailable) {
            // Some OEMs block Settings accessibility. Notification input remains available.
        } finally {
            if (root != null) root.recycle();
            while (!queue.isEmpty()) queue.removeFirst().recycle();
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
        showControls(taskId,goal,runControl,null);
    }
    /** Only the exact waiting native Service holds this one start action. It is not a notification/Web command. */
    public void showFirstMainControls(String taskId,String goal,RunControl runControl,java.util.function.BooleanSupplier current,
                                      java.util.function.Predicate<FirstMainScreenWindow> confirm){
        showControls(taskId,goal,runControl,new FirstMainControl(runControl,current,confirm));
    }
    private void showControls(String taskId, String goal, RunControl runControl,FirstMainControl firstMain) {
        runOnMain(() -> {
            if (instance != this || runControl == null || runControl.isCancelled()) return;
            if(firstMain!=null&&!firstMain.current().getAsBoolean())return;
            if (!(getApplication() instanceof EngineApplication)) return;
            EngineApplication app=(EngineApplication)getApplication();
            PhoneOwnerControls bound=new PhoneOwnerControls(app,taskId,runControl);
            if(!bound.retained())return;
            openingReturn=openingRun==runControl?openingReturn:control==runControl?ownerReturnButton:null;
            openingControls=bound;openingRun=runControl;
            openControlRead.request(()->bound.view(goal),first->{
                if(openingControls!=bound||openingRun!=runControl)return;
                var heldReturn=openingReturn;clearOpeningControls();
                if(instance!=this||first==null||!first.current()||!bound.retained())return;
                if(firstMain!=null&&!firstMain.current().getAsBoolean())return;
                installOwnerControls(taskId,goal,runControl,app,bound,first,heldReturn,firstMain);
            });
        });
    }
    private void installOwnerControls(String taskId,String goal,RunControl runControl,EngineApplication app,PhoneOwnerControls bound,
                                     PhoneOwnerControls.ViewState first,OwnerReturnButton heldReturn,FirstMainControl firstMain){
            removeInstalledControls();
            control = runControl;
            ownerControls=bound;
            firstMainControl=firstMain;
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
            OwnerOverlayActionBar actions = new OwnerOverlayActionBar(this);
            Button grip = button("任务 ▾");
            controlGrip = grip;
            grip.setContentDescription("拖动可移动手机任务控制条；点击展开操作");
            grip.setOnClickListener(v -> {
                boolean expand = details.getVisibility() != View.VISIBLE;
                details.setVisibility(expand ? View.VISIBLE : View.GONE);
                grip.setText(expand ? "任务 ▴" : "任务 ▾");updateOwnerGripLabel();
                renderResultWatch();
            });
            // refreshOwnerControls installs a distinct Android View for each displayed intention.
            ownerActionSlot=new android.widget.FrameLayout(this);
            Button stop = button("停止");
            stop.setOnClickListener(v -> {
                if(ownerControls!=bound||control!=runControl||!visible||instance!=this||!stop.isEnabled())return;
                stop.setEnabled(false);stop.setText("请求中");
                try{bound.stop().whenComplete((receipt,failed)->main.post(()->{
                    if(instance!=this||ownerControls!=bound||control!=runControl)return;
                    if(failed==null&&bound.stopAccepted(receipt)){stop.setText("已请求停止");return;}
                    stop.setText("再请求停止");stop.setEnabled(bound.retained());
                    stop.setContentDescription("停手回执未确认，再次请求停止这一原执行");
                }));}catch(RuntimeException rejected){
                    stop.setText("再请求停止");stop.setEnabled(bound.retained());
                    stop.setContentDescription("本次未提交，重新请求停止这一原执行");
                }
            });
            Button back = button(firstMain==null?"暂停并返回原任务":"查看原任务");
            back.setOnClickListener(v -> {if(ownerControls==bound&&visible&&
                    (firstMain!=null&&firstMain.current().getAsBoolean()||bound.holdForReturn())){
                refreshOwnerControls(bound,goal);openOriginalOwnerReview(bound,app);
            }});
            actions.bind(grip,ownerActionSlot,stop);
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
                        details.setVisibility(View.GONE); grip.setText("任务 ▾");updateOwnerGripLabel();renderResultWatch();
                    }
                } catch (Exception unavailable) { app.error(unavailable.getMessage() == null ? "请回原任务重新打开快捷补充" : unavailable.getMessage()); }
            });
            if(firstMain==null)more.addView(supplement); details.addView(more);
            Button watch = button("这轮有结果时提醒我");
            watch.setVisibility(View.GONE);
            watch.setOnClickListener(v -> watchOriginalResult(watch));
            resultWatchButton=watch;
            details.addView(watch);
            panel.addView(details);
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
                removeInstalledControls();
                return;
            }
            cancellation = () -> main.post(() -> {
                if (control == runControl) removeInstalledControls();
            });
            runControl.onCancel(cancellation);
            ownerApp=app;
            var posted=new java.util.concurrent.atomic.AtomicBoolean();
            ownerObserver=event->{
                org.json.JSONObject task=event.optJSONObject("task");
                if("task".equals(event.optString("type"))&&task!=null&&taskId.equals(task.optString("id"))&&posted.compareAndSet(false,true))
                    main.post(()->{posted.set(false);refreshOwnerControls(bound,goal);});
            };
            app.addObserver(ownerObserver);
            if(heldReturn!=null&&heldReturn.control()==runControl&&returnReady(heldReturn))ownerReturnButton=heldReturn;
            renderOwnerControls(bound,goal,bound.samePause(first)?first:PhoneOwnerControls.reading());
            // Close the gap between the first read and registration. This only reads retained task facts.
            refreshOwnerControls(bound,goal);
    }

    private static boolean returnReady(OwnerReturnButton button){
        try{return button!=null&&button.current().getAsBoolean();}catch(RuntimeException stale){return false;}
    }
    private void openOriginalOwnerReview(PhoneOwnerControls bound,EngineApplication app){
        try{startActivity(bound.originalReview(this));}catch(Exception unavailable){app.error("未能打开这次手机执行，请从原任务记录核对");}
    }
    private void refreshOwnerControls(PhoneOwnerControls expected,String goal) {
        if(expected==null||ownerControls!=expected||ownerActionSlot==null||status==null)return;
        shownControlRead.request(()->expected.view(goal),state->{
            if(instance!=this||ownerControls!=expected||ownerActionSlot==null||status==null)return;
            if(!expected.retained()){removeInstalledControls();return;}
            renderOwnerControls(expected,goal,state!=null&&state.current()&&expected.samePause(state)?state:PhoneOwnerControls.reading());
        });
    }
    private void renderOwnerControls(PhoneOwnerControls expected,String goal,PhoneOwnerControls.ViewState state){
        // A pending source read cannot create a hand-back control; preserve the candidate for a later verified row.
        OwnerReturnButton hand=state.current()&&state.pause()>=0?ownerReturnButton:null;
        if(hand!=null&&(control==null||hand.control()!=control||!control.isPaused()||control.isCancelled()||hand.pause()!=control.pauseRevision()||!returnReady(hand))){ownerReturnButton=null;hand=null;}
        RenderedOwnerAction shown=renderedOwnerAction;
        if(shown==null||shown.owner()!=expected||shown.hand()!=hand||!state.sameInput(shown.state())){
            // Android can deliver click after ACTION_UP. Do not replace a captured Pause listener with
            // Resume/handback on that same View. Retire the old View; unchanged task events retain focus.
            if(pause!=null){pause.setEnabled(false);pause.setOnClickListener(null);}
            var binding=new RenderedOwnerAction(expected,state,hand);renderedOwnerAction=binding;
            String label=hand!=null?"本人已处理，交还":switch(state.action()){
                case PAUSE->"暂停";case RESUME_LOCAL,RESUME_SOURCE->"继续这次暂停";case REVIEW->"核对原暂停";
                case FIRST_MAIN_START->"从眼前页面开始";case NONE->"暂不可控制";};
            Button next=button(label);pause=next;ownerActionSlot.removeAllViews();ownerActionSlot.addView(next);
            next.setOnClickListener(v->performShownOwnerAction(binding,goal,next));
        }
        boolean waiting=hand==null&&submittingOwnerAction!=null&&(state.action()==PhoneOwnerControls.Action.RESUME_LOCAL||state.action()==PhoneOwnerControls.Action.RESUME_SOURCE);
        var first=firstMainControl;
        pause.setEnabled(!waiting&&(hand!=null||state.action()!=PhoneOwnerControls.Action.NONE&&
                (state.action()!=PhoneOwnerControls.Action.FIRST_MAIN_START||first!=null&&first.current().getAsBoolean())));
        status.setText(shortText(state.message(),140));
        allowedResultWatch=state.current()&&state.resultWatch()?expected:null;
        renderResultWatch();
        updateOwnerGripLabel();
    }
    /** No local read on expand: this is the exact owner fact from the existing status read,
     * not a claim that the watch is currently saved or that the phone has produced a result. */
    private void renderResultWatch(){
        var button=resultWatchButton;
        if(button==null)return;
        var owner=allowedResultWatch;
        if(owner==null||controlDetails==null||controlDetails.getVisibility()!=View.VISIBLE)resultWatchOffer=null;
        else if(resultWatchOffer==null||resultWatchOffer.owner()!=owner)resultWatchOffer=new ResultWatchOffer(owner);
        button.setVisibility(owner==null?View.GONE:View.VISIBLE);
        button.setEnabled(resultWatchOffer!=null&&submittingResultWatch==null);
    }
    private void watchOriginalResult(Button source){
        var offer=resultWatchOffer;
        if(offer==null||source!=resultWatchButton||!source.isShown()||!source.isEnabled()||!visible||instance!=this||
                ownerControls!=offer.owner()||submittingResultWatch!=null)return;
        long stamp=ownerVisibilityVersion;
        java.util.function.BooleanSupplier current=()->instance==this&&visible&&ownerVisibilityVersion==stamp&&resultWatchOffer==offer;
        submittingResultWatch=offer;
        source.setEnabled(false);
        try{OWNER_RESULT_WATCH.execute(()->{
            boolean accepted;
            try{accepted=offer.owner().watchResult(current);}catch(Exception unavailable){accepted=false;}
            boolean saved=accepted;
            main.post(()->{
                if(submittingResultWatch==offer)submittingResultWatch=null;
                renderResultWatch();
                if(current.getAsBoolean())android.widget.Toast.makeText(this,saved?"已登记这轮手机工作的本机提醒。":
                        "本次提醒未确认，没有自动重试。",android.widget.Toast.LENGTH_SHORT).show();
            });
        });}catch(java.util.concurrent.RejectedExecutionException busy){
            if(submittingResultWatch==offer)submittingResultWatch=null;
            renderResultWatch();
            if(current.getAsBoolean())android.widget.Toast.makeText(this,"上一请求仍在核对，未重复登记。",android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    private void updateOwnerGripLabel(){
        // The private goal is a separately expanded detail, never the collapsed grip’s spoken name.
        if(controlGrip!=null)controlGrip.setContentDescription("拖动可移动手机任务控制条；点击"+(controlDetails!=null&&controlDetails.getVisibility()==View.VISIBLE?"收起":"展开")+"操作");
    }
    private void performShownOwnerAction(RenderedOwnerAction shown,String goal,Button source){
        if(source!=pause||shown!=renderedOwnerAction||!visible||ownerApp==null||instance!=this)return;
        EngineApplication app=ownerApp;PhoneOwnerControls bound=shown.owner();
        if(shown.hand()!=null){
            var hand=shown.hand();
            if(hand==ownerReturnButton&&ownerReturnVisible(hand.control(),hand.pause(),hand.operation()))try{hand.action().send();}
                catch(android.app.PendingIntent.CanceledException stale){app.error("这次交还按钮已失效，请打开原任务核对");}
            // A stale handback must never turn into a plain Resume or Pause.
        }else switch(shown.state().action()){
            case PAUSE->{if(control!=null&&control.pauseRevision()==shown.state().pause())bound.pause();}
            case REVIEW->openOriginalOwnerReview(bound,app);
            case FIRST_MAIN_START->{
                var offer=firstMainControl;
                if(offer==null||offer.control()!=control||!source.isShown()||!source.isEnabled()||!offer.current().getAsBoolean())return;
                long visibleAt=ownerVisibilityVersion;
                java.util.function.BooleanSupplier current=()->instance==this&&visible&&ownerVisibilityVersion==visibleAt&&firstMainControl==offer&&offer.current().getAsBoolean();
                try{
                    var page=FirstMainScreenWindow.fromOverlay(this,offer.control(),controls,current);
                    if(offer.confirm().test(page))source.setEnabled(false);
                }catch(Exception unavailable){android.widget.Toast.makeText(this,"还没核对到眼前应用页面。关闭弹窗后可再确认，原任务没有开始。",android.widget.Toast.LENGTH_SHORT).show();}
                return;
            }
            case RESUME_LOCAL,RESUME_SOURCE->{
                if(submittingOwnerAction!=null)return;
                long version=ownerVisibilityVersion;submittingOwnerAction=shown;source.setEnabled(false);
                java.util.function.BooleanSupplier current=()->instance==this&&visible&&ownerVisibilityVersion==version&&renderedOwnerAction==shown;
                try{OWNER_RESUME.execute(()->{
                    try{bound.resume(shown.state(),current);}catch(Exception unavailable){if(current.getAsBoolean())app.error("未取得这次暂停的继续回执，请返回原任务核对，不要重复继续");}
                    finally{main.post(()->{if(submittingOwnerAction==shown)submittingOwnerAction=null;if(ownerControls==bound)refreshOwnerControls(bound,goal);});}
                });}catch(java.util.concurrent.RejectedExecutionException busy){submittingOwnerAction=null;app.error("原控制请求仍在处理，未排队或继续新的执行");refreshOwnerControls(bound,goal);}
                return;
            }
            case NONE->{return;}
        }
        refreshOwnerControls(bound,goal);
    }

    public void hideControls() { runOnMain(this::removeControls); }
    public void hideControls(RunControl original) { runOnMain(()->{
        if(original==null)return;
        if(openingRun==original)clearOpeningControls();
        if(control==original)removeInstalledControls();
    }); }
    public boolean hasVisibleControls() { return visible; }
    /** The button already installed for this pause, not a new observation or an input grant.
     * The current in-app Stop can temporarily conceal it while preserving the mounted overlay. */
    public String mountedMainPauseHandle(RunControl original,long revision,long scope) {
        if(Looper.myLooper()!=Looper.getMainLooper()||instance!=this||original==null||control!=original||
                ownerControls==null||!ownerControls.retained()||!original.phoneSurfaceActive()||!original.ownerPausedAt(revision)||
                controls==null||!controls.isAttachedToWindow()||controls.getDisplay()==null||controls.getDisplay().getDisplayId()!=0||
                firstMainControl!=null||quickPanel!=null||!captureHides.isEmpty()||submittingOwnerAction!=null)return null;
        RenderedOwnerAction shown=renderedOwnerAction;Button button=pause;
        if(shown==null||shown.owner()!=ownerControls||shown.hand()!=null||shown.state().action()!=PhoneOwnerControls.Action.RESUME_LOCAL||
                !shown.state().current()||shown.state().pause()!=revision||shown.state().scope()!=scope||
                button==null||ownerActionSlot==null||button.getParent()!=ownerActionSlot||!button.isAttachedToWindow()||
                button.getVisibility()!=View.VISIBLE||!button.isEnabled())return null;
        boolean mountedForReturn=visible&&button.isShown()||!visible&&controls.getVisibility()==View.INVISIBLE&&
                inAppStopOwner!=null&&SystemClock.uptimeMillis()<inAppStopUntil;
        return mountedForReturn&&original.ownerPausedAt(revision)?shown.state().displayHandle():null;
    }
    /** The Web host may replace the overlay only after observing an actionable
     * stop button for this exact run. This identity grants no control authority. */
    public RunControl.HostRunIdentity ownerStopSurfaceIdentity() {
        if (Looper.myLooper() != Looper.getMainLooper() || instance != this || controls == null
                || control == null || control.isCancelled() || ownerApp == null) return null;
        var identity = control.hostRunIdentity();
        return identity != null && ownerApp.control(identity.taskId()) == control ? identity : null;
    }
    public void confirmInAppStopSurface(Object owner, RunControl.HostRunIdentity expected) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Owner stop presentation must run on main");
        var current = ownerStopSurfaceIdentity();
        if (owner == null || expected == null || !expected.equals(current)) { clearInAppStopSurface(owner); return; }
        inAppStopOwner = owner;
        inAppStopUntil = SystemClock.uptimeMillis() + 2_000L;
        main.removeCallbacks(inAppStopExpiry);
        main.postDelayed(inAppStopExpiry, 2_000L);
        updateVisibility();
    }
    public void clearInAppStopSurface(Object owner) {
        runOnMain(() -> {
            if (inAppStopOwner != owner) return;
            clearInAppStopSurface();
            updateVisibility();
        });
    }
    /** Actual visible stop control, either the overlay or the host's short-lived
     * verified Web button. An expired/unresponsive document never counts. */
    public boolean hasVisibleOwnerStopControl() { return visible || SystemClock.uptimeMillis() < inAppStopUntil; }
    private void clearInAppStopSurface() {
        inAppStopUntil = 0;
        inAppStopOwner = null;
        main.removeCallbacks(inAppStopExpiry);
    }

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

    private void clearOpeningControls(){openControlRead.clear();openingControls=null;openingRun=null;openingReturn=null;}
    private void removeControls(){clearOpeningControls();removeInstalledControls();}
    private void removeInstalledControls() {
        shownControlRead.clear();
        ownerReturnButton=null;renderedOwnerAction=null;submittingOwnerAction=null;firstMainControl=null;ownerVisibilityVersion++;
        resultWatchOffer=null;submittingResultWatch=null;allowedResultWatch=null;resultWatchButton=null;
        ownerActionSlot=null;
        clearInAppStopSurface();
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
        boolean show=controls!=null&&captureHides.isEmpty()&&SystemClock.uptimeMillis()>=inAppStopUntil;
        if(show!=visible)ownerVisibilityVersion++;
        visible=show;
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
