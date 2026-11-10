package dev.pocketengine.features.updates;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import dev.pocketengine.ui.UiAppearance;
import dev.pocketengine.ui.web.PocketWebHost;

/** Native owner-only entry: no remote web page or model response can click through the installer. */
public final class AppUpdateActivity extends ComponentActivity {
    private AppUpdateManager manager;
    private PocketWebHost webHost;
    private Runnable surfaceRefresh;
    private AutoCloseable ownerWindow;
    private AutoCloseable systemWindow;
    private boolean awaitingSystem;
    private static final int SYSTEM_HANDOFF = 1944;
    private final Runnable refresh = this::render;

    @Override public void onCreate(Bundle state) {
        setTheme(UiAppearance.theme(this));
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        UiAppearance.window(this, "workspace");
        awaitingSystem = state != null && state.getBoolean("awaitingUpdateSystemHandoff", false);
        if (awaitingSystem) systemWindow = dev.pocketengine.phone.observation.NativeSensitiveStep.openOwnerUpdateWindow();
        manager = AppUpdateManager.get(this);
        webHost = PocketWebHost.appUpdates(this, manager);
        setContentView(webHost.getView());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                webHost.handleBack(() -> { finish(); return kotlin.Unit.INSTANCE; });
            }
        });
        manager.listen(refresh);
        webHost.start();
        manager.recover();
    }

    @Override public void onResume() {
        super.onResume();
        UiAppearance.window(this, "workspace");
        if (webHost != null) webHost.resume();
        if (manager != null) { render(); manager.recover(); }
    }
    @Override public void onPause() {
        if (webHost != null) webHost.pause();
        super.onPause();
    }
    @Override public void onStart() {
        super.onStart();
        if (ownerWindow == null) ownerWindow = dev.pocketengine.phone.observation.NativeSensitiveStep.openOwnerUpdateWindow();
    }
    @Override public void onStop() { closeOwnerWindow(); super.onStop(); }
    @Override public void onDestroy() {
        if (manager != null) manager.unlisten(refresh);
        if (webHost != null) webHost.close();
        closeOwnerWindow();
        closeSystemWindow();
        super.onDestroy();
    }
    /** Only the owner's Install action opens this exact system flow. Backgrounding the ordinary page does not retain a global hold. */
    void openSystemHandoff(android.content.Intent intent) {
        if (awaitingSystem) return;
        awaitingSystem = true; systemWindow = dev.pocketengine.phone.observation.NativeSensitiveStep.openOwnerUpdateWindow();
        try { startActivityForResult(intent, SYSTEM_HANDOFF); }
        catch (RuntimeException unavailable) { awaitingSystem = false; closeSystemWindow(); throw unavailable; }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SYSTEM_HANDOFF) { awaitingSystem = false; closeSystemWindow(); render(); }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1943) render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaitingUpdateSystemHandoff", awaitingSystem); super.onSaveInstanceState(state);
    }
    private void closeSystemWindow() {
        if (systemWindow != null) { try { systemWindow.close(); } catch (Exception ignored) { } finally { systemWindow = null; } }
    }
    private void closeOwnerWindow() {
        if (ownerWindow != null) { try { ownerWindow.close(); } catch (Exception ignored) { } finally { ownerWindow = null; } }
    }

    /** The bundled React surface observes this same owner Activity; it cannot mint the sensitive window. */
    public boolean updateSurfaceAvailable() {
        return ownerWindow != null && !awaitingSystem && !isFinishing() && !isDestroyed();
    }
    public boolean awaitingUpdateSystemHandoff() { return awaitingSystem; }
    public boolean updateNotificationsPermissionRequired() {
        return Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }
    public void requestUpdateNotifications() {
        if (!updateSurfaceAvailable()) throw new IllegalStateException("请在前台更新页面设置通知");
        if (updateNotificationsPermissionRequired()) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1943);
    }
    public void bindUpdateSurface(Runnable listener) { surfaceRefresh = listener; }
    public void unbindUpdateSurface(Runnable listener) { if (surfaceRefresh == listener) surfaceRefresh = null; }
    private void render() {
        if (!isFinishing() && !isDestroyed() && surfaceRefresh != null) surfaceRefresh.run();
    }
}
