package dev.pocketengine.features.updates;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Native owner-only entry: no remote web page or model response can click through the installer. */
public final class AppUpdateActivity extends Activity {
    private AppUpdateManager manager;
    private TextView status, details, transfer;
    private Button check, download, cancel, install, notifications;
    private ProgressBar progress;
    private Switch automatic;
    private AutoCloseable ownerWindow;
    private AutoCloseable systemWindow;
    private boolean awaitingSystem;
    private static final int SYSTEM_HANDOFF = 1944;
    private final Runnable refresh = this::render;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        awaitingSystem = state != null && state.getBoolean("awaitingUpdateSystemHandoff", false);
        if (awaitingSystem) systemWindow = dev.pocketengine.phone.observation.NativeSensitiveStep.openOwnerUpdateWindow();
        manager = AppUpdateManager.get(this);
        ScrollView scroll = new ScrollView(this); scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24); content.setPadding(padding, dp(36), padding, padding); scroll.addView(content);
        button(content, "返回", () -> finish());
        TextView title = text(content, "应用更新", 26); title.setAccessibilityHeading(true);
        text(content, "当前版本 " + manager.installedVersion(), 14);
        status = text(content, "", 17);
        details = text(content, "", 15);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); content.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        transfer = text(content, "", 14);
        check = button(content, "检查新版本", () -> manager.check(true, null));
        download = button(content, "下载更新", () -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifications.setVisibility(View.VISIBLE);
            manager.download();
        });
        download.setId(dev.pocketengine.R.id.owner_app_update_download);
        cancel = button(content, "暂停下载", () -> manager.cancel());
        install = button(content, "安装新版本", () -> manager.install(this));
        install.setId(dev.pocketengine.R.id.owner_app_update_install);
        automatic = new Switch(this); automatic.setText("自动检查新版本"); automatic.setChecked(manager.automatic());
        automatic.setOnCheckedChangeListener((view, enabled) -> manager.automatic(enabled)); content.addView(automatic);
        text(content, "自动检查在应用启动时及 Android 允许的约 6 小时间隔运行，发现新版本后通知你。这是定期检查，不是实时服务端推送。不会自动下载或安装。", 14);
        notifications = button(content, "允许更新通知", () -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1943);
        });
        text(content, "更新包先验证发布清单签名、文件完整性、应用包名、版本和当前安装签名。增量不可用时改用完整包；最终安装由 Android 提示确认。不会为绕过签名而卸载应用。", 14);
        text(content, "此页面和系统安装确认请本人操作，模型手机任务会在敏感步骤交还给你。", 14);
        setContentView(scroll);
        manager.listen(refresh); render(); manager.recover();
    }

    @Override public void onResume() { super.onResume(); if (manager != null) { render(); manager.recover(); } }
    @Override public void onStart() {
        super.onStart();
        if (ownerWindow == null) ownerWindow = dev.pocketengine.phone.observation.NativeSensitiveStep.openOwnerUpdateWindow();
    }
    @Override public void onStop() { closeOwnerWindow(); super.onStop(); }
    @Override public void onDestroy() {
        if (manager != null) manager.unlisten(refresh);
        closeOwnerWindow();
        closeSystemWindow();
        super.onDestroy();
    }
    /** Only the native Install click opens this exact system flow. Backgrounding the ordinary page does not retain a global hold. */
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
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaitingUpdateSystemHandoff", awaitingSystem); super.onSaveInstanceState(state);
    }
    private void closeSystemWindow() {
        if (systemWindow != null) { try { systemWindow.close(); } catch (Exception ignored) { } finally { systemWindow = null; } }
    }
    private void closeOwnerWindow() {
        if (ownerWindow != null) { try { ownerWindow.close(); } catch (Exception ignored) { } finally { ownerWindow = null; } }
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        status.setText(manager.message());
        UpdateManifest release = manager.release();
        details.setText(release == null ? "" : "新版本 " + release.versionName + "\n" + release.notes);
        details.setVisibility(release == null ? View.GONE : View.VISIBLE);
        boolean running = manager.busy();
        long total = manager.total(), received = manager.downloaded();
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(total <= 0 || !"downloading".equals(manager.phase()));
        if (total > 0) progress.setProgress((int) Math.min(100, received * 100 / total));
        transfer.setText(manager.delivery() + (total > 0 ? String.format(java.util.Locale.ROOT, " · %.1f / %.1f MB", received / 1048576.0, total / 1048576.0) : ""));
        transfer.setVisibility(manager.delivery().isEmpty() ? View.GONE : View.VISIBLE);
        check.setEnabled(manager.configured() && !running);
        download.setVisibility(release != null && !manager.ready() ? View.VISIBLE : View.GONE);
        download.setEnabled(!running); download.setText("paused".equals(manager.phase()) || "failed".equals(manager.phase()) ? "继续下载" : "下载更新");
        cancel.setVisibility(running && ("downloading".equals(manager.phase()) || "applying".equals(manager.phase()) || "verifying".equals(manager.phase())) ? View.VISIBLE : View.GONE);
        install.setVisibility(manager.ready() ? View.VISIBLE : View.GONE); install.setEnabled(!running);
        automatic.setEnabled(manager.configured());
        notifications.setVisibility(manager.configured() && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ? View.VISIBLE : View.GONE);
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(12), 0, dp(12));
        parent.addView(view, params); return view;
    }
    private Button button(LinearLayout parent, String value, Runnable action) {
        Button view = new Button(this); view.setText(value); view.setOnClickListener(clicked -> action.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
