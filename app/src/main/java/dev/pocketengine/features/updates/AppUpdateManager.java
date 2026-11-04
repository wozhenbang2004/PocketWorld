package dev.pocketengine.features.updates;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AtomicFile;
import dev.pocketengine.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-facing self-update only. No model tool can configure the trust root, download or install. */
public final class AppUpdateManager {
    private static final int JOB_ID = 19431, NOTICE_ID = 19432;
    private static final long CHECK_INTERVAL = 6L * 60 * 60 * 1000;
    private static volatile AppUpdateManager instance;
    private final Context context;
    private final File root;
    private final SharedPreferences preferences;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile UpdateDownloads downloads;
    private volatile UpdateManifest release;
    private volatile String phase = "idle", message = "", delivery = "";
    private volatile long downloaded, total;
    private volatile long lastProgressNotice;

    public static AppUpdateManager get(Context context) {
        AppUpdateManager found = instance;
        if (found == null) synchronized (AppUpdateManager.class) {
            if (instance == null) instance = new AppUpdateManager(context.getApplicationContext());
            found = instance;
        }
        return found;
    }

    private AppUpdateManager(Context context) {
        this.context = context;
        root = new File(context.getNoBackupFilesDir(), "app-updates");
        preferences = context.getSharedPreferences("app-update-preferences", Context.MODE_PRIVATE);
        if (!configured()) { phase = "unconfigured"; message = "私有更新服务尚未配置。当前不会联网检查，也不会上传应用或个人数据。"; }
        else { message = "可检查新版本。下载和安装都由你决定。"; }
    }

    public static void start(Context context) {
        AppUpdateManager manager = get(context);
        manager.schedule();
        if (manager.configured() && manager.automatic() && System.currentTimeMillis() - manager.preferences.getLong("lastCheck", 0) >= CHECK_INTERVAL)
            manager.check(false, null);
    }

    public boolean configured() {
        return !context.getString(R.string.pocketworld_update_manifest_url).trim().isEmpty()
                && !context.getString(R.string.pocketworld_update_public_key).trim().isEmpty();
    }
    public boolean automatic() { return preferences.getBoolean("automatic", true); }
    public boolean busy() { return busy.get(); }
    public String phase() { return phase; }
    public String message() { return message; }
    public String delivery() { return delivery; }
    public long downloaded() { return downloaded; }
    public long total() { return total; }
    public UpdateManifest release() { return release; }
    public boolean ready() { return "ready".equals(phase) && release != null; }
    public void listen(Runnable listener) { listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    public void automatic(boolean enabled) { preferences.edit().putBoolean("automatic", enabled).apply(); schedule(); changed(); }
    public void recover() {
        if (!configured() || release != null || !new File(root, "release.json").isFile() || !busy.compareAndSet(false, true)) return;
        phase = "recovering"; message = "正在恢复上次的更新…"; changed();
        worker.execute(() -> {
            try {
                UpdateManifest target = cachedRelease();
                if (target.versionCode <= installed().getLongVersionCode()) { phase = "current"; message = "已是最新版本"; return; }
                release = target;
                if (reusableReady(target)) { phase = "ready"; message = "上次下载的更新已通过重新验证，可继续安装"; }
                else { phase = "available"; message = "有可用更新，可继续上次未完成的下载"; }
            } catch (Exception invalidCache) { release = null; phase = "idle"; message = "请重新检查新版本，旧清单已失效或无法验证"; }
            finally { busy.set(false); changed(); }
        });
    }
    public String installedVersion() {
        try { PackageInfo info = installed(); return info.versionName + " · " + info.getLongVersionCode(); }
        catch (Exception unavailable) { return "当前版本暂不可读取"; }
    }

    private void schedule() {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!configured() || !automatic()) { scheduler.cancel(JOB_ID); return; }
        JobInfo existing = scheduler.getPendingJob(JOB_ID);
        if (existing != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, AppUpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(CHECK_INTERVAL).setPersisted(true).build());
    }

    public void check(boolean ownerRequested, Runnable finished) {
        if (!configured() || !busy.compareAndSet(false, true)) { if (finished != null) main.post(finished); return; }
        phase = "checking"; message = "正在核对新版本…"; changed();
        worker.execute(() -> {
            downloads = new UpdateDownloads();
            try {
                ensureRoot();
                byte[] envelope = downloads.manifest(context.getString(R.string.pocketworld_update_manifest_url));
                UpdateManifest next = verify(envelope);
                PackageInfo installed = installed();
                if (next.minSdk > Build.VERSION.SDK_INT) throw new IOException("新版本需要更高的 Android 版本，当前应用仍可使用");
                if (next.versionCode <= installed.getLongVersionCode()) {
                    release = null; phase = "current"; message = "已是最新版本";
                    new AtomicFile(new File(root, "release.json")).delete();
                } else {
                    // Persist only authenticated bytes. A killed process can recover without trusting a status flag.
                    AtomicFile file = new AtomicFile(new File(root, "release.json")); FileOutputStream out = file.startWrite();
                    try { out.write(envelope); file.finishWrite(out); } catch (Exception failure) { file.failWrite(out); throw failure; }
                    release = next;
                    cleanOtherArtifacts(next);
                    if (reusableReady(next)) { phase = "ready"; message = "新版本已下载并通过校验，点击安装继续"; }
                    else { phase = "available"; message = "发现新版本 " + next.versionName; }
                    if (!ownerRequested) notifyAvailable(next);
                }
                preferences.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            } catch (Exception failure) {
                fail(failure, "检查更新失败，请检查网络或稍后重试");
                String checkFailure = message;
                try {
                    UpdateManifest cached = cachedRelease();
                    if (cached.versionCode > installed().getLongVersionCode() && cached.minSdk <= Build.VERSION.SDK_INT) {
                        if (reusableReady(cached)) { release = cached; phase = "ready"; message = checkFailure + "。此前下载的版本仍已验证，可继续安装。"; }
                        else { release = cached; phase = "available"; message = checkFailure + "。可继续此前已验证清单中的下载。"; }
                    } else release = null;
                } catch (Exception invalidCache) { release = null; }
            }
            finally {
                downloads = null; busy.set(false); changed();
                if (finished != null) main.post(finished);
            }
        });
    }

    public void download() {
        if (release == null || !busy.compareAndSet(false, true)) return;
        // Install the cancellation target before advertising a cancellable operation to the owner.
        downloads = new UpdateDownloads();
        phase = "downloading"; downloaded = 0; total = 0; message = "正在准备下载…"; changed();
        worker.execute(() -> {
            File staged = new File(root, "reconstructed.apk");
            try {
                UpdateManifest target = cachedRelease(); release = target;
                if (staged.exists() && !staged.delete()) throw new IOException("无法清理上次未完成的更新合成文件");
                if (target.versionCode <= installed().getLongVersionCode()) throw new IOException("此更新已安装，请重新检查版本");
                if (reusableReady(target)) { phase = "ready"; message = "此前下载的版本已重新验证，无需再次下载，可继续安装。"; return; }
                File oldApk = new File(context.getApplicationInfo().sourceDir);
                UpdateManifest.Delta delta = context.getApplicationInfo().splitSourceDirs == null
                        ? target.chooseDelta(installed().getLongVersionCode(), UpdateDownloads.sha256(oldApk)) : null;
                File result = null;
                if (delta != null) {
                    try {
                        delivery = "增量下载"; message = "正在下载增量包…"; changed();
                        File patch = downloads.download(delta.artifact, root, this::progress);
                        downloads.check(); phase = "applying"; message = "正在合成新版本，原应用和数据不会被修改"; changed();
                        if (root.getUsableSpace() < target.apk.bytes + 32L * 1024 * 1024) throw new IOException("存储空间不足，无法合成新版本");
                        ApkDelta.apply(oldApk, patch, staged, target.apk.bytes, downloads::check);
                        downloads.check(); validateApk(staged, target); result = staged;
                    } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                    catch (Exception deltaFailed) {
                        staged.delete(); downloads.check();
                        delivery = "完整安装包（增量不可用）"; message = "增量包暂不可用，正在改用完整安装包，保留现有数据"; changed();
                    }
                } else { delivery = "完整安装包"; message = "当前版本没有匹配的增量包，正在下载完整安装包"; changed(); }
                if (result == null) {
                    phase = "downloading";
                    result = downloads.download(target.apk, root, this::progress);
                    downloads.check(); phase = "verifying"; message = "正在验证完整性、应用身份与签名…"; changed(); validateApk(result, target);
                }
                downloads.check();
                File ready = readyFile(target);
                if (!result.equals(ready)) Files.move(result.toPath(), ready.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                phase = "ready"; message = "新版本已下载并通过校验。安装将覆盖升级，保留现有数据。";
            } catch (java.util.concurrent.CancellationException cancelled) {
                phase = "paused"; message = "下载已暂停，点击继续下载可接着下载";
            } catch (Exception failure) { fail(failure, "更新未完成，现有应用与数据未改变，可重试"); }
            finally { staged.delete(); downloads = null; busy.set(false); changed(); }
        });
    }

    public void cancel() { UpdateDownloads current = downloads; if (current != null) current.cancel(); }
    private void progress(long bytes, long maximum) {
        downloaded = bytes; total = maximum;
        long now = android.os.SystemClock.elapsedRealtime();
        if (bytes == maximum || now - lastProgressNotice >= 250) { lastProgressNotice = now; changed(); }
    }

    /** Runs expensive validation off the UI thread; opening settings never silently resumes installation. */
    public void install(Activity activity) {
        if (release == null || !ready() || !busy.compareAndSet(false, true)) return;
        message = "正在重新核对安装包…"; changed();
        worker.execute(() -> {
            try {
                UpdateManifest target = cachedRelease(); File apk = readyFile(target); validateApk(apk, target);
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        if (!context.getPackageManager().canRequestPackageInstalls()) {
                            message = "请允许 PocketWorld 安装应用，返回后再次点击安装";
                            openSystemHandoff(activity, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())));
                        } else {
                            Uri uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates").appendPath(target.apk.sha256).build();
                            openSystemHandoff(activity, new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .putExtra(Intent.EXTRA_RETURN_RESULT, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            message = "请在 Android 安装界面确认。取消后仍可从这里继续。";
                        }
                    } catch (Exception unavailable) { message = "系统安装界面暂不可用，请稍后重试"; }
                    changed();
                });
            } catch (Exception failure) { fail(failure, "安装包未通过重新验证，请重新下载"); }
            finally { busy.set(false); changed(); }
        });
    }

    private void openSystemHandoff(Activity activity, Intent intent) {
        if (!(activity instanceof AppUpdateActivity)) throw new SecurityException("请在原生应用更新页安装");
        ((AppUpdateActivity) activity).openSystemHandoff(intent);
    }

    File verifiedForProvider(String hash) throws Exception {
        UpdateManifest target = cachedRelease();
        if (!target.apk.sha256.equals(hash)) throw new SecurityException("更新文件不匹配");
        File apk = readyFile(target); validateApk(apk, target); return apk;
    }

    private UpdateManifest verify(byte[] bytes) throws Exception {
        return UpdateManifest.verify(bytes, context.getString(R.string.pocketworld_update_public_key), context.getPackageName(), System.currentTimeMillis());
    }
    private UpdateManifest cachedRelease() throws Exception {
        ensureRoot();
        AtomicFile file = new AtomicFile(new File(root, "release.json"));
        try (java.io.InputStream input = file.openRead()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > UpdateManifest.MAX_ENVELOPE_BYTES) throw new SecurityException("更新清单过大");
                bytes.write(buffer, 0, count);
            }
            return verify(bytes.toByteArray());
        }
    }
    private PackageInfo installed() throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    }
    private File readyFile(UpdateManifest target) { return new File(root, target.apk.sha256 + ".apk"); }
    private boolean reusableReady(UpdateManifest target) throws Exception {
        File file = readyFile(target);
        if (!file.exists()) return false;
        try { validateApk(file, target); return true; }
        catch (Exception invalidCache) {
            // This is our disposable updater cache, never the installed APK or owner files.
            if (!file.delete()) throw new IOException("损坏的更新缓存无法清理，请检查存储后重试");
            return false;
        }
    }
    private void ensureRoot() throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法准备私有更新目录");
    }
    private void validateApk(File file, UpdateManifest target) throws Exception {
        if (!file.isFile() || file.length() != target.apk.bytes || !UpdateDownloads.sha256(file).equals(target.apk.sha256))
            throw new SecurityException("更新包完整性校验失败，请重新下载");
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current = installed();
        if (archive == null || !context.getPackageName().equals(archive.packageName) || archive.getLongVersionCode() != target.versionCode
                || archive.getLongVersionCode() <= current.getLongVersionCode() || archive.signingInfo == null || current.signingInfo == null)
            throw new SecurityException("更新包的应用或版本不匹配，已停止安装");
        android.content.pm.Signature[] oldSigners = current.signingInfo.getApkContentsSigners();
        android.content.pm.Signature[] newSigners = archive.signingInfo.getApkContentsSigners();
        // Exact signer set only. Key rotation requires a separately reviewed migration; never uninstall to bypass it.
        if (oldSigners.length == 0 || oldSigners.length != newSigners.length || !Arrays.asList(newSigners).containsAll(Arrays.asList(oldSigners)))
            throw new SecurityException("新安装包与当前应用签名不同，无法保留数据覆盖更新。请联系发布者，不要卸载现有应用。");
    }
    private void cleanOtherArtifacts(UpdateManifest target) {
        java.util.HashSet<String> keep = new java.util.HashSet<>(); keep.add(target.apk.sha256);
        for (UpdateManifest.Delta delta : target.deltas) keep.add(delta.artifact.sha256);
        File[] files = root.listFiles(); if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.matches("[a-f0-9]{64}\\.(part|verified|apk)") && !keep.contains(name.substring(0, 64))) file.delete();
        }
    }
    private void notifyAvailable(UpdateManifest target) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || preferences.getLong("notifiedCode", 0) == target.versionCode || !manager.areNotificationsEnabled()
                || Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            manager.createNotificationChannel(new NotificationChannel("app_updates", "应用更新", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(context, NOTICE_ID, new Intent(context, AppUpdateActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Persist before the binder call: an uncertain delivery must not repeatedly alert the owner.
            preferences.edit().putLong("notifiedCode", target.versionCode).commit();
            manager.notify(NOTICE_ID, new Notification.Builder(context, "app_updates").setSmallIcon(R.drawable.ic_app)
                    .setContentTitle("PocketWorld 有新版本").setContentText("点击查看并选择下载更新")
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build());
        } catch (RuntimeException ignored) { /* The update remains visible in the owner update page. */ }
    }
    private void fail(Exception failure, String fallback) {
        phase = "failed";
        // Never display network exception URLs, query tokens, filesystem paths or server response bodies.
        message = failure instanceof SecurityException || failure instanceof java.util.concurrent.CancellationException
                || failure instanceof IOException && failure.getMessage() != null && !failure.getMessage().contains(":")
                ? safeMessage(failure.getMessage(), fallback) : fallback;
    }
    private String safeMessage(String value, String fallback) { return value != null && value.length() <= 180 && !value.contains("/") && !value.contains("\\") ? value : fallback; }
    private void changed() { for (Runnable listener : listeners) main.post(listener); }
}
