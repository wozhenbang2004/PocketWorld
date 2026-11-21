package dev.pocketengine.phone;

import android.graphics.Region;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.core.RunControl;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One recent physical owner gesture. The initial check uses only Android window metadata. */
public final class FirstMainScreenWindow {
    private static final long MAX_WAIT_MS = 4_000L;
    private record Window(int id, int layer, Region touchable) { }
    private final PhoneAccessibilityService service;
    private final RunControl control;
    private final RunControl.HostRunIdentity run;
    private final Window window;
    private final int ownerOverlayId;
    private final PhoneUiSignals feed;
    private final PhoneUiSignals.Stamp stamp;
    private final long focusRevision, pauseRevision, surfaceRevision, capturedAt;
    private final BooleanSupplier visible;
    private final AtomicBoolean spent = new AtomicBoolean();

    private FirstMainScreenWindow(PhoneAccessibilityService service, RunControl control, Window window, int ownerOverlayId,
                                  long focusRevision, BooleanSupplier visible) {
        this.service = service; this.control = control; this.window = window; this.visible = visible;
        this.run = Objects.requireNonNull(control.hostRunIdentity());
        this.ownerOverlayId=ownerOverlayId;
        this.feed = service.uiSignals(); this.stamp = feed.snapshot(Set.of(window.id()));
        this.focusRevision = focusRevision; this.pauseRevision = control.pauseRevision();
        this.surfaceRevision = control.surfaceRevision(); this.capturedAt = SystemClock.uptimeMillis();
    }

    static FirstMainScreenWindow fromOverlay(PhoneAccessibilityService service, RunControl control,View ownOverlay,
                                              BooleanSupplier visible) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33 || Looper.myLooper() != Looper.getMainLooper() || !visible.getAsBoolean()
                || service != PhoneAccessibilityService.get() || service.observationSafetyFailure() != null||
                ownOverlay==null||!ownOverlay.isAttachedToWindow()||ownOverlay.getDisplay()==null||ownOverlay.getDisplay().getDisplayId()!=0)
            throw unavailable();
        // This is our mounted native View's own accessibility id, not a read of an external root or an overlay-type guess.
        AccessibilityNodeInfo node=ownOverlay.createAccessibilityNodeInfo();
        if(node==null)throw unavailable();
        int overlayId;
        try{overlayId=node.getWindowId();}finally{node.recycle();}
        if(overlayId<0)throw unavailable();
        long focus = service.keyFocusRevision();
        FirstMainScreenWindow page = new FirstMainScreenWindow(service,control,focusedApplication(service,overlayId),overlayId,focus,visible);
        page.check(control);
        return page;
    }

    public int windowId() { return window.id(); }

    /** Cheap check for the bounded native tree worker; it does not request any root. */
    public void check(RunControl original) throws Exception {
        if(!stillCurrent(original)||service.observationSafetyFailure()!=null)throw unavailable();
        original.check();
    }

    /** Lock-free owner predicate for the original SQL claim: no Android service or TaskStore callback. */
    public boolean stillCurrent(RunControl original) {
        long now = SystemClock.uptimeMillis();
        return original==control&&original.matchesHostRun(run.taskId(),run.runId(),run.epoch())&&!original.isCancelled()&&
                !original.isPaused()&&original.phoneSurfaceActive()&&original.pauseRevision()==pauseRevision&&
                original.surfaceRevision()==surfaceRevision&&service==PhoneAccessibilityService.get()&&
                service.keyFocusRevision()==focusRevision&&now>=capturedAt&&now-capturedAt<=MAX_WAIT_MS&&
                !stamp.closed&&stamp.same(feed.snapshot(Set.of(window.id())))&&visible.getAsBoolean();
    }

    void beginRead(RunControl original) throws Exception {
        if (!spent.compareAndSet(false, true)) throw unavailable();
        checkWindow(original);
    }

    public void checkWindow(RunControl original) throws Exception {
        check(original);
        if (!window.equals(focusedApplication(service,ownerOverlayId))) throw unavailable();
        check(original);
    }

    /** Verify the very window inventory whose sole approved root will be read. The caller owns its objects. */
    public void requireCaptureSnapshot(PhoneAccessibilityService actual,SparseArray<List<AccessibilityWindowInfo>> all)throws Exception{
        if(actual!=service||!window.equals(focusedApplication(all,ownerOverlayId)))throw unavailable();
    }

    private static Window focusedApplication(PhoneAccessibilityService service,int ownerOverlayId) throws Exception {
        SparseArray<List<AccessibilityWindowInfo>> all = service.getWindowsOnAllDisplays();
        if (all == null) throw unavailable();
        try {
            return focusedApplication(all,ownerOverlayId);
        } finally {
            for (int i = 0; i < all.size(); i++) {
                List<AccessibilityWindowInfo> rows = all.valueAt(i);
                if (rows != null) for (AccessibilityWindowInfo row : rows) if (row != null) row.recycle();
            }
        }
    }

    private static Window focusedApplication(SparseArray<List<AccessibilityWindowInfo>> all,int ownerOverlayId)throws Exception{
        List<AccessibilityWindowInfo> rows=all==null?null:all.get(Display.DEFAULT_DISPLAY);
        if(rows==null||rows.size()>32)throw unavailable();
        Window found=null;boolean ownVisible=false;
        for(AccessibilityWindowInfo row:rows){
            if(row==null||row.getDisplayId()!=Display.DEFAULT_DISPLAY)throw unavailable();
            int type=row.getType();
            if(row.getId()==ownerOverlayId){
                if(ownVisible||type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY||row.isFocused())throw unavailable();
                ownVisible=true;continue;
            }
            if(!row.isFocused()){if(row.isActive())throw unavailable();continue;}
            if(found!=null||type!=AccessibilityWindowInfo.TYPE_APPLICATION||row.isInPictureInPictureMode()||row.getId()<0)throw unavailable();
            Region touchable=new Region();row.getRegionInScreen(touchable);
            if(touchable.isEmpty())throw unavailable();
            found=new Window(row.getId(),row.getLayer(),touchable);
        }
        if(found==null||!ownVisible)throw unavailable();
        for(AccessibilityWindowInfo row:rows){
            if(row.getId()==ownerOverlayId||row.getId()==found.id())continue;
            // Focus can be reported underneath a modal. Never read through an overlapping higher
            // window, and do not infer that two application windows are one selected page.
            if(row.getType()==AccessibilityWindowInfo.TYPE_APPLICATION)throw unavailable();
            if(row.getLayer()>=found.layer()){
                Region other=new Region();row.getRegionInScreen(other);
                if(other.isEmpty()||other.op(found.touchable(),Region.Op.INTERSECT))throw unavailable();
            }
        }
        return found;
    }

    private static SecurityException unavailable() {
        return new SecurityException("没有核对到眼前稳定的主屏应用页面，未开始原任务。");
    }
}
