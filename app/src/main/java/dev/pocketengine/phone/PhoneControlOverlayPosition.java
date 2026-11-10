package dev.pocketengine.phone;

import android.graphics.Insets;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Owner-only overlay positioning. Moving the surface does not control the phone task. */
final class PhoneControlOverlayPosition implements View.OnTouchListener {
    private final View panel;
    private final WindowManager windows;
    private final WindowManager.LayoutParams layout;
    private final int margin, slop;
    private float downX, downY;
    private int startX, startY;
    private boolean dragging, placed;

    PhoneControlOverlayPosition(View panel, WindowManager.LayoutParams layout) {
        this.panel = panel;
        this.windows = panel.getContext().getSystemService(WindowManager.class);
        this.layout = layout;
        margin = Math.round(8 * panel.getResources().getDisplayMetrics().density);
        slop = ViewConfiguration.get(panel.getContext()).getScaledTouchSlop();
        panel.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> keepVisible());
        panel.setOnApplyWindowInsetsListener((view, insets) -> { view.post(this::keepVisible); return insets; });
    }

    void keepVisible() {
        if (!panel.isAttachedToWindow() || panel.getWidth() <= 0 || panel.getHeight() <= 0) return;
        int x = layout.x;
        if (!placed) {
            x = panel.getResources().getDisplayMetrics().widthPixels - panel.getWidth() - margin;
            placed = true;
        }
        moveTo(x, layout.y);
    }

    private void moveTo(int x, int y) {
        if (!panel.isAttachedToWindow()) return;
        var metrics = panel.getResources().getDisplayMetrics();
        Insets blocked = Insets.NONE;
        WindowInsets insets = panel.getRootWindowInsets();
        if (insets != null) blocked = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        int minX = blocked.left + margin, minY = blocked.top + margin;
        int maxX = Math.max(minX, metrics.widthPixels - blocked.right - panel.getWidth() - margin);
        int maxY = Math.max(minY, metrics.heightPixels - blocked.bottom - panel.getHeight() - margin);
        int nextX = Math.max(minX, Math.min(maxX, x)), nextY = Math.max(minY, Math.min(maxY, y));
        if (layout.x == nextX && layout.y == nextY) return;
        layout.x = nextX; layout.y = nextY;
        try { windows.updateViewLayout(panel, layout); }
        catch (IllegalArgumentException detached) { /* The original overlay was removed during its UI lifecycle. */ }
    }

    @Override public boolean onTouch(View handle, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY();
                startX = layout.x; startY = layout.y; dragging = false;
                handle.setPressed(true); return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && dx * dx + dy * dy >= slop * slop) {
                    dragging = true; handle.setPressed(false);
                }
                if (dragging) moveTo(startX + Math.round(dx), startY + Math.round(dy));
                return true;
            case MotionEvent.ACTION_UP:
                handle.setPressed(false);
                if (!dragging) handle.performClick();
                dragging = false; return true;
            case MotionEvent.ACTION_CANCEL:
                handle.setPressed(false); dragging = false; return true;
            default: return true;
        }
    }
}
