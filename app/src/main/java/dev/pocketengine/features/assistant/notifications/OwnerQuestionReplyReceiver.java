package dev.pocketengine.features.assistant.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Explicit, non-exported receiver. RemoteInput is ordinary data, never operation approval. */
public final class OwnerQuestionReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!OwnerQuestionReply.ACTION.equals(intent.getAction())&&!OwnerQuestionReply.STOP.equals(intent.getAction()))return;
        PendingResult pending=goAsync();
        java.util.concurrent.CompletableFuture.runAsync(()->{
            boolean accepted=false;
            try{if(!(context.getApplicationContext() instanceof OwnerQuestionReply.Host host))throw new IllegalStateException("原问题宿主未挂载");accepted=OwnerQuestionReply.dispatch(host,intent).optBoolean("accepted");}
            catch(Exception unavailable){/* No answer body or task content in diagnostics; no retry after an uncertain commit. */}
            try{
                JSONObject view=new JSONObject(intent.getStringExtra("noticeView"));NotificationManager manager=context.getSystemService(NotificationManager.class);
                if(manager!=null){PendingIntent open=PendingIntent.getActivity(context,0,AssistantTaskNotifications.open(context,view),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    Notification notice=new Notification.Builder(context,AssistantTaskNotifications.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(accepted?"原任务已收到这次操作":"这条通知已到期或暂不可核对")
                        .setContentText(accepted?"点此查看原任务的实际状态与已有结果":"请核对原记录；不会自动重发、继续或授权动作")
                        .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build();
                    manager.notify("assistant-task:"+view.getString("taskId")+":"+view.getString("token"),1906,notice);}
            }catch(Exception noticeUnavailable){/* OS notice acceptance is not visual delivery proof. */}
            finally{pending.finish();}
        });
    }
}
