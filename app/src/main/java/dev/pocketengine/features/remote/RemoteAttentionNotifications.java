package dev.pocketengine.features.remote;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.store.TaskStore;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Best-effort local attention while original job observations are available; not remote push delivery. */
public final class RemoteAttentionNotifications {
    public static final String ACTION="dev.pocketengine.REMOTE_JOB_REVIEW";
    private static final String CHANNEL="remote_job_attention",TAG="remote_job_attention";
    private final Context context;private final TaskStore tasks;private final Class<?> activity;
    public RemoteAttentionNotifications(Context context,TaskStore tasks,Class<?> activity){this.context=context;this.tasks=tasks;this.activity=activity;}
    public void refresh(){try{JSONArray rows=tasks.remoteAttentionCandidates();for(int i=0;i<rows.length();i++)observe(rows.getString(i));}catch(Exception unavailable){/* No task, execution, approval or retry is created. */}}
    public synchronized void observe(String taskId){
        TaskStore.RemoteAttentionClaim claim=null;
        try{
            NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return;
            if(!tasks.remoteAttentionState(taskId).optBoolean("enabled")){manager.cancel(TAG,taskId.hashCode());return;}
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"电脑任务进度与待处理请求",NotificationManager.IMPORTANCE_DEFAULT));
            NotificationChannel channel=manager.getNotificationChannel(CHANNEL);if(!manager.areNotificationsEnabled()||channel==null||channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return;
            claim=tasks.claimRemoteAttention(taskId);if(claim==null)return;JSONObject locator=claim.view();String kind=locator.getString("kind");
            Intent intent=new Intent(context,activity).setAction(ACTION).setData(new Uri.Builder().scheme("pocketengine").authority("remote-attention").appendPath(taskId).appendPath(locator.getString("fingerprint")).build())
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("remoteAttentionTask",taskId).putExtra("remoteAttentionFingerprint",locator.getString("fingerprint"));
            PendingIntent tap=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            String title=kind.equals("input_required")?"电脑任务需要你的处理":kind.equals("completed_reported")?"电脑报告任务已结束":kind.equals("execution_unconfirmed")?"电脑执行状态尚未确认":"电脑执行已停止或中断";
            Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(context.getApplicationInfo().icon).setContentTitle(title).setContentText("点击读取原任务的当前进度、问题或审批。旧通知不能直接批准或重发。")
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(tap).build();
            manager.notify(TAG,taskId.hashCode(),notification);tasks.recordRemoteAttention(claim,"posted_to_system");
        }catch(SecurityException blocked){if(claim!=null)try{tasks.recordRemoteAttention(claim,"blocked");}catch(Exception ignored){}}
        catch(Exception unknown){if(claim!=null)try{tasks.recordRemoteAttention(claim,"unknown");}catch(Exception ignored){}}
    }
    /** Exported Activity extras navigate only after original SQL validation; no callback/control is revived. */
    public static boolean openRequestedTask(TaskStore tasks,Intent intent,Consumer<JSONObject> open){
        return openRequestedTask(tasks,intent,open, ignored -> { });
    }
    public static boolean openRequestedTask(TaskStore tasks,Intent intent,Consumer<JSONObject> open,Consumer<String> unavailable){
        if(intent==null||!ACTION.equals(intent.getAction()))return false;String task=intent.getStringExtra("remoteAttentionTask"),fingerprint=intent.getStringExtra("remoteAttentionFingerprint");
        intent.setAction(null);intent.removeExtra("remoteAttentionTask");intent.removeExtra("remoteAttentionFingerprint");
        try{if(task==null||!task.matches("[A-Za-z0-9_-]{1,80}")||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();open.accept(tasks.remoteAttentionLocator(task,fingerprint));}catch(Exception missing){unavailable.accept("这条电脑任务提醒已过期、任务已删除或暂不可读取。没有批准或重发任何操作，请从任务列表重新查看。");}return true;
    }
}
