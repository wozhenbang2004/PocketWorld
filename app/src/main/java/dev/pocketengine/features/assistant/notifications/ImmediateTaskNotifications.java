package dev.pocketengine.features.assistant.notifications;

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
import dev.pocketengine.features.assistant.notifications.ImmediateTaskNotice.Notice;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Android delivery only. The original TaskStore operation owns admission, deduplication and receipts. */
public final class ImmediateTaskNotifications {
    public static final String OPEN = "dev.pocketengine.OWNER_TASK_NOTICE";
    private static final String CHANNEL = AssistantTaskNotifications.CHANNEL;
    private static final int ID = 1908;
    private static final String TITLE = "PocketWorld 有一条任务提醒";
    private static final String SUMMARY = "点击查看原任务中的通知与执行记录。";

    @FunctionalInterface public interface Effect { JSONObject run() throws Exception; }
    /** Must persist the original operation as unknown and recheck its run/pause admission before effect.run(). */
    @FunctionalInterface public interface OriginalEntry { JSONObject dispatch(Effect effect) throws Exception; }
    /** Must read the exact original operation; navigation never authorizes execution or notification replay. */
    @FunctionalInterface public interface OpenOriginal {
        JSONObject open(String taskId, String runId, int epoch, String operationId, String token) throws Exception;
    }

    private final Context context;
    private final NotificationManager manager;

    public ImmediateTaskNotifications(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(NotificationManager.class);
    }

    public JSONObject post(Notice notice, OriginalEntry original) throws Exception {
        if (notice == null || original == null) throw new IllegalArgumentException("原通知准入缺失");
        String blocked = blocked();
        if (blocked != null) return notice.receipt(blocked, blocked);
        final Notification notification;
        try {
            PendingIntent open = PendingIntent.getActivity(context, 0, open(context, notice),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification publicVersion = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_task_notice)
                    .setContentTitle(TITLE).setContentText(SUMMARY).build();
            notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_task_notice)
                    .setContentTitle(TITLE).setContentText(SUMMARY).setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                    .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open).build();
        } catch (RuntimeException unavailable) {
            return notice.receipt("unavailable", "notification_construction_failed");
        }
        return original.dispatch(() -> {
            String changed = blocked();
            if (changed != null) return notice.receipt(changed, changed);
            try {
                manager.notify("owner-notice:" + notice.taskId() + ":" + notice.token(), ID, notification);
                return notice.receipt("posted", "notification_api_accepted");
            } catch (RuntimeException unknown) {
                // The binder call may have reached Android. Never turn this into an automatic retry.
                return notice.receipt("unconfirmed", "notification_api_unconfirmed");
            }
        });
    }

    private String blocked() {
        if (manager == null) return "unavailable";
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    || !manager.areNotificationsEnabled()) return "permission_required";
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "助手结果与待处理事项", NotificationManager.IMPORTANCE_DEFAULT));
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            return channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE ? "channel_disabled" : null;
        } catch (RuntimeException unavailable) { return "unavailable"; }
    }

    public static Intent open(Context context, Notice notice) {
        ImmediateTaskNotice.validateNavigation(notice.taskId(), notice.runId(), notice.epoch(), notice.operationId(), notice.token());
        return new Intent(context, dev.pocketengine.ui.ComposeActivity.class).setAction(OPEN)
                .setData(openUri(notice.taskId(), notice.runId(), notice.epoch(), notice.operationId(), notice.token()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("taskId", notice.taskId()).putExtra("noticeRun", notice.runId()).putExtra("noticeEpoch", notice.epoch())
                .putExtra("noticeOperation", notice.operationId()).putExtra("noticeToken", notice.token());
    }

    /** An exported Activity intent selects a record; it cannot resume, approve, stop, or resend anything. */
    public static boolean openRequestedTask(Intent intent, OpenOriginal source, Consumer<JSONObject> focus, Consumer<String> notice) {
        if (intent == null || !OPEN.equals(intent.getAction())) return false;
        try {
            String task = intent.getStringExtra("taskId"), run = intent.getStringExtra("noticeRun");
            String operation = intent.getStringExtra("noticeOperation"), token = intent.getStringExtra("noticeToken");
            int epoch = intent.getIntExtra("noticeEpoch", -1);
            ImmediateTaskNotice.validateNavigation(task, run, epoch, operation, token);
            if (!openUri(task, run, epoch, operation, token).equals(intent.getData())) throw new SecurityException("原通知定位已变化");
            JSONObject current = source.open(task, run, epoch, operation, token);
            focus.accept(current);
            if (current.optBoolean("expired")) notice.accept("这条通知的状态已变化，已打开原任务记录；没有重新执行或重复发送。");
        } catch (Exception unavailable) {
            notice.accept("这条通知的原记录暂不可核对，请在任务列表查看已有结果。");
        } finally {
            intent.setAction(null);
            intent.setData(null);
            for (String key : new String[]{"taskId", "noticeRun", "noticeEpoch", "noticeOperation", "noticeToken"}) intent.removeExtra(key);
        }
        return true;
    }

    private static Uri openUri(String task, String run, int epoch, String operation, String token) {
        return new Uri.Builder().scheme("pocketworld-notice").authority("owner").appendPath(task).appendPath(run)
                .appendPath(Integer.toString(epoch)).appendPath(operation).appendPath(token).build();
    }
}
