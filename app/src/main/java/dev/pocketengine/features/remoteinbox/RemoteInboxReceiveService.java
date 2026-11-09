package dev.pocketengine.features.remoteinbox;

import android.Manifest;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicit owner opt-in foreground HTTPS receiver, no public phone IP or third-party SDK. */
public final class RemoteInboxReceiveService extends Service {
    public static final String START = "dev.pocketengine.remoteinbox.START";
    public static final String STOP = "dev.pocketengine.remoteinbox.STOP";
    private static final int SERVICE_ID = 30218;
    private ScheduledExecutorService scheduler;
    private static volatile boolean running;
    public static boolean isRunning() { return running; }
    @Override public int onStartCommand(Intent request, int flags, int startId) {
        if (request == null || STOP.equals(request.getAction())) {
            try { RemoteInboxStore.get(this).live(false); }
            catch (Exception failure) { Log.w("PocketRemoteInbox", "Could not persist receiver stop", failure); }
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!START.equals(request.getAction())) { stopSelfResult(startId); return START_NOT_STICKY; }
        try {
            if (!RemoteInboxStore.get(this).configured() || !RemoteInboxStore.get(this).live()) throw new SecurityException("接收授权不存在");
            NotificationManager notifications = getSystemService(NotificationManager.class);
            if (notifications == null || !notifications.areNotificationsEnabled()
                || Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("需要允许通知才能启动后台接收");
            if (Build.VERSION.SDK_INT >= 34) startForeground(SERVICE_ID, RemoteInboxNotifications.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(SERVICE_ID, RemoteInboxNotifications.ongoing(this));
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        if (RemoteInboxStore.get(this).live()) RemoteInboxSync.run(this);
                    } catch (Exception failure) {
                        Log.w("PocketRemoteInbox", "Foreground receiver polling failed", failure);
                    }
                }, 0, 50, TimeUnit.SECONDS);
            }
            running = true;
            return START_NOT_STICKY;
        } catch (Exception failure) {
            Log.w("PocketRemoteInbox", "Could not start foreground receiver", failure);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
    }
    @Override public void onDestroy() {
        running = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
