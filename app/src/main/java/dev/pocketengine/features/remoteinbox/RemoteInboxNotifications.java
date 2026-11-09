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

/** Presentation only. A tap selects the inbox; it cannot create, approve or claim tasks. */
public final class RemoteInboxNotifications {
    public static final String OPEN = "dev.pocketengine.REMOTE_INBOX_OPEN";
    public static final String ATTENTION = "remote_inbox_attention";
    public static final String NORMAL = "remote_inbox_normal";
    public static final String SERVICE = "remote_inbox_service";
    private RemoteInboxNotifications() { }
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
    public static Notification ongoing(Context context) {
        channels(context);
        PendingIntent open = PendingIntent.getActivity(context, 3081, open(context), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(context, 3082,
            new Intent(context, RemoteInboxReceiveService.class).setAction(RemoteInboxReceiveService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, SERVICE).setSmallIcon(R.drawable.ic_task_notice)
            .setContentTitle("PocketWorld 消息接收").setContentText("已开启主动检测 · 点击查看通知")
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    public static void post(Context context, JSONObject event) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        channels(context);
        String channel = "attention".equals(event.optString("priority")) ? ATTENTION : NORMAL;
        PendingIntent open = PendingIntent.getActivity(context, 3081, open(context), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = event.optString("title", "远程通知");
        String body = event.optString("body", "点击查看这条通知");
        Notification notification = new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_task_notice)
            .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS).build();
        long seq = event.optLong("seq", 0);
        manager.notify("remote-inbox:" + seq, 3080, notification);
    }
}
