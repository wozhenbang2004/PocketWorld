package dev.pocketengine.features.remoteinbox;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import dev.pocketengine.R;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Presentation only. A tap selects the inbox; it cannot create, approve or claim tasks. */
public final class RemoteInboxNotifications {
    public static final String OPEN = "dev.pocketengine.REMOTE_INBOX_OPEN";
    public static final String ATTENTION = "remote_inbox_attention";
    public static final String NORMAL = "remote_inbox_normal";
    public static final String SERVICE = "remote_inbox_service";
    public static final String CONFIG_EXTRA = "remoteInboxConfig", SEQ_EXTRA = "remoteInboxSeq", SOURCE_EXTRA = "remoteInboxSource";
    private static final ExecutorService cachedAlerts = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean recovering = new AtomicBoolean();
    private static final AtomicBoolean recoveryRequested = new AtomicBoolean();
    private RemoteInboxNotifications() { }
    /** Foreground/permission recovery is local only. It does not depend on a live service or network. */
    public static void recoverCached(Context context) {
        Context app = context.getApplicationContext();
        recoveryRequested.set(true);
        if (!recovering.compareAndSet(false, true)) return;
        cachedAlerts.execute(() -> {
            try {
                // A permission grant arriving during an earlier denied attempt must not be lost.
                for (int pass = 0; pass < 2; pass++) {
                    recoveryRequested.set(false);
                    RemoteInboxStore store = RemoteInboxStore.get(app);
                    publishPending(app, store.configId());
                    if (!recoveryRequested.get()) break;
                }
            } catch (Exception unavailable) {
                android.util.Log.w("PocketRemoteInbox", "Cached inbox alerts could not be recovered");
            } finally {
                recovering.set(false);
                if (recoveryRequested.get()) recoverCached(app);
            }
        });
    }
    /** Only OS-accepted notifications are marked alerted; no read, archive or sync state is changed. */
    static void publishPending(Context context, String expectedIdentity) throws Exception {
        RemoteInboxStore store = RemoteInboxStore.get(context);
        synchronized (store) {
            if (expectedIdentity.isEmpty() || !expectedIdentity.equals(store.configId())) return;
            List<JSONObject> pending = store.pendingAlerts();
            List<JSONObject> accepted = new ArrayList<>();
            // A muted important channel must never leak through the ordinary channel.
            for (String priority : new String[]{"normal", "attention"}) {
                List<JSONObject> group = new ArrayList<>();
                for (JSONObject event : pending) if (priority.equals(event.optString("priority"))) group.add(event);
                if (group.size() > 8) {
                    String channel = priority.equals("attention") ? ATTENTION : NORMAL;
                    try {
                        if (postSummary(context, expectedIdentity, group.size(), channel)) accepted.addAll(group);
                    } catch (RuntimeException unavailable) {
                        android.util.Log.w("PocketRemoteInbox", "Inbox summary was not accepted");
                    }
                } else for (JSONObject event : group) {
                    try {
                        if (post(context, expectedIdentity, event)) accepted.add(event);
                    } catch (RuntimeException unavailable) {
                        android.util.Log.w("PocketRemoteInbox", "Native inbox notification was not accepted");
                    }
                }
            }
            // One atomic journal write, not one full-file write per native notification.
            if (!accepted.isEmpty()) store.alertsPosted(accepted, expectedIdentity);
        }
    }
    public static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(ATTENTION, "远程提醒 · 重要", NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(new NotificationChannel(NORMAL, "远程提醒", NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel(SERVICE, "远程消息接收服务", NotificationManager.IMPORTANCE_LOW));
    }
    public static Intent open(Context context) {
        return new Intent(context, dev.pocketengine.ui.ComposeActivity.class)
            .setAction(OPEN).setData(Uri.parse("pocketworld://remote-inbox/open"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private static Intent open(Context context, String identity, JSONObject event) {
        return open(context).setData(new Uri.Builder().scheme("pocketworld").authority("remote-inbox")
            .appendPath(identity).appendPath(Long.toString(event.optLong("seq"))).build())
            .putExtra(CONFIG_EXTRA, identity).putExtra(SEQ_EXTRA, event.optLong("seq"))
            .putExtra(SOURCE_EXTRA, event.optString("source_key"));
    }
    public static Notification ongoing(Context context) {
        return ongoing(context, "已开启主动检测 · 点击查看通知");
    }
    public static Notification ongoing(Context context, String state) {
        channels(context);
        PendingIntent open = PendingIntent.getActivity(context, 3081, open(context), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(context, 3082,
            new Intent(context, RemoteInboxReceiveService.class).setAction(RemoteInboxReceiveService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, SERVICE).setSmallIcon(R.drawable.ic_task_notice)
            .setContentTitle("PocketWorld 消息接收").setContentText(state)
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    private static boolean canNotify(Context context, NotificationManager manager) {
        return manager != null && manager.areNotificationsEnabled()
            && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }
    public static boolean channelEnabled(Context context, String id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!canNotify(context, manager)) return false;
        channels(context);
        NotificationChannel channel = manager.getNotificationChannel(id);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        if (Build.VERSION.SDK_INT >= 28 && channel.getGroup() != null) {
            android.app.NotificationChannelGroup group = manager.getNotificationChannelGroup(channel.getGroup());
            if (group != null && group.isBlocked()) return false;
        }
        return true;
    }
    public static JSONObject permissionState(Context context) throws org.json.JSONException {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return new JSONObject().put("appEnabled", canNotify(context, manager))
            .put("normalEnabled", channelEnabled(context, NORMAL))
            .put("attentionEnabled", channelEnabled(context, ATTENTION));
    }
    public static void dismiss(Context context, String identity, JSONObject event) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.cancel("remote-inbox:" + identity + ":" + event.optLong("seq"), 3080);
            String channel = "attention".equals(event.optString("priority")) ? ATTENTION : NORMAL;
            manager.cancel("remote-inbox:" + identity + ":summary:" + channel, 3080);
        } catch (RuntimeException unavailable) { android.util.Log.w("PocketRemoteInbox", "Read item system notice could not be dismissed", unavailable); }
    }
    public static void dismissConnection(Context context, String identity) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || identity == null || identity.isEmpty()) return;
        try { for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getTag() != null && notification.getTag().startsWith("remote-inbox:" + identity + ":"))
                manager.cancel(notification.getTag(), notification.getId());
        } } catch (RuntimeException unavailable) { android.util.Log.w("PocketRemoteInbox", "Old connection notices could not be dismissed", unavailable); }
    }
    public static boolean post(Context context, String identity, JSONObject event) {
        String channel = "attention".equals(event.optString("priority")) ? ATTENTION : NORMAL;
        if (!channelEnabled(context, channel)) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        PendingIntent open = PendingIntent.getActivity(context, 3081, open(context, identity, event), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = event.optString("title", "远程通知");
        String body = event.optString("body", "点击查看这条通知");
        Notification notification = new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_task_notice)
            .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS).build();
        long seq = event.optLong("seq", 0);
        manager.notify("remote-inbox:" + identity + ":" + seq, 3080, notification);
        return true;
    }
    public static boolean postSummary(Context context, String identity, int count, String channel) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!channelEnabled(context, channel)) return false;
        PendingIntent open = PendingIntent.getActivity(context, 3081, open(context),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_task_notice).setContentTitle("PocketWorld 收到 " + count + " 条新通知")
            .setContentText("点击打开收件箱查看，避免大量提醒打断当前工作")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        manager.notify("remote-inbox:" + identity + ":summary:" + channel, 3080, notice);
        return true;
    }
}
