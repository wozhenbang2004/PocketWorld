package dev.pocketengine.features.assistant.notifications;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import dev.pocketengine.store.TaskStore;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Android is only the delivery surface. The original TaskStore and current owner action still decide. */
public final class AssistantTaskNotifications {
    public static final String OPEN="dev.pocketengine.ASSISTANT_OWNER_REVIEW",CHANNEL="assistant_task_results";
    private static final int ID=1906;
    private final Context context;private final TaskStore tasks;private final NotificationManager manager;
    public AssistantTaskNotifications(Context context,TaskStore tasks){this.context=context.getApplicationContext();this.tasks=tasks;manager=this.context.getSystemService(NotificationManager.class);}
    private static String tag(JSONObject view)throws Exception{return "assistant-task:"+view.getString("taskId")+":"+view.getString("token");}
    public void update(String task)throws Exception{
        Context localized=dev.pocketengine.features.operations.AppLanguage.wrap(context);
        TaskStore.OwnerTaskNotice claim=tasks.ownerTaskNotice(task);if(claim==null||manager==null)return;JSONObject view=claim.view();String tag=tag(view);
        if(view.optBoolean("cancelOnly")){if(tasks.ownerTaskNoticeCurrent(claim)){manager.cancel(tag,ID);tasks.ownerTaskNoticePhase(claim,"obsolete");}return;}
        if(!tasks.ownerTaskNoticeCurrent(claim))return;String replaced=view.optString("replacesToken");if(replaced.matches("[a-f0-9]{64}"))manager.cancel("assistant-task:"+view.getString("taskId")+":"+replaced,ID);
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED||!manager.areNotificationsEnabled()){tasks.ownerTaskNoticePhase(claim,"permission_required");return;}
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,localized.getString(dev.pocketengine.R.string.w8_notice_channel),NotificationManager.IMPORTANCE_DEFAULT));NotificationChannel channel=manager.getNotificationChannel(CHANNEL);if(channel==null||channel.getImportance()==NotificationManager.IMPORTANCE_NONE){tasks.ownerTaskNoticePhase(claim,"permission_required");return;}
        // Mark before the external binder call. A crash/uncertain return never authorizes a repost.
        if(!tasks.beginOwnerTaskNoticePosting(claim)||!tasks.ownerTaskNoticeCurrent(claim))return;
        Intent target=open(context,view);PendingIntent pending=PendingIntent.getActivity(context,0,target,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(TaskNotificationState.title(localized,view.getString("status"))).setContentText(TaskNotificationState.summary(localized,view.getString("status")))
            .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(pending);
        if(view.optJSONObject("ownerQuestion")!=null){
            Intent reply=new Intent(context,OwnerQuestionReplyReceiver.class).setAction(OwnerQuestionReply.ACTION).setData(target.getData()).putExtra("questionIdentity",view.getJSONObject("ownerQuestion").toString()).putExtra("noticeToken",view.getString("token")).putExtra("noticeView",view.toString());
            PendingIntent answer=PendingIntent.getBroadcast(context,0,reply,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send,localized.getString(dev.pocketengine.R.string.w8_notice_reply),answer).addRemoteInput(new RemoteInput.Builder(OwnerQuestionReply.KEY).setLabel(localized.getString(dev.pocketengine.R.string.w8_notice_reply_label)).build()).setAllowGeneratedReplies(false).build());
        }
        Notification notification=builder.build();
        try{manager.notify(tag,ID,notification);if(tasks.ownerTaskNoticeCurrent(claim))tasks.ownerTaskNoticePhase(claim,"posted");else manager.cancel(tag,ID);}
        catch(RuntimeException unconfirmed){tasks.ownerTaskNoticePhase(claim,"unconfirmed");throw unconfirmed;}
    }
    public static Intent open(Context context,JSONObject nativeView)throws Exception{
        String task=nativeView.getString("taskId"),run=nativeView.getString("runId"),token=nativeView.getString("token");int epoch=nativeView.getInt("epoch");
        if(!task.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||!token.matches("[a-f0-9]{64}")||epoch<1)throw new IllegalArgumentException("原生通知定位无效");
        return new Intent(context,dev.pocketengine.ui.ComposeActivity.class).setAction(OPEN).setData(new Uri.Builder().scheme("pocketworld-task").authority("owner").appendPath(task).appendPath(run).appendPath(Integer.toString(epoch)).appendPath(token).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("taskId",task).putExtra("noticeRun",run).putExtra("noticeEpoch",epoch).putExtra("noticeToken",token);
    }
    /** Exported Activity input is navigation, never a resume/approval/cancel command. */
    public static boolean openRequestedTask(TaskStore tasks,Intent intent,Consumer<JSONObject> focus,Consumer<String> notice){
        if(intent==null||!OPEN.equals(intent.getAction()))return false;
        try{
            String task=intent.getStringExtra("taskId"),run=intent.getStringExtra("noticeRun"),token=intent.getStringExtra("noticeToken");int epoch=intent.getIntExtra("noticeEpoch",-1);Uri uri=intent.getData();
            JSONObject identity=new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("token",token);
            if(uri==null||!uri.equals(openUri(identity)))throw new SecurityException("通知定位已变化");JSONObject current=tasks.openOwnerTaskNotice(task,run,epoch,token);focus.accept(current);if(current.getBoolean("expired"))notice.accept("这条通知的状态已变化，已打开当前任务记录；没有重跑或重复审批。");
        }catch(Exception unavailable){notice.accept("这条通知的记录暂不可核对，请在任务列表查看已有结果。");}
        finally{intent.setAction(null);for(String key:new String[]{"taskId","noticeRun","noticeEpoch","noticeToken"})intent.removeExtra(key);}
        return true;
    }
    private static Uri openUri(JSONObject value)throws Exception{return new Uri.Builder().scheme("pocketworld-task").authority("owner").appendPath(value.getString("taskId")).appendPath(value.getString("runId")).appendPath(Integer.toString(value.getInt("epoch"))).appendPath(value.getString("token")).build();}
}
