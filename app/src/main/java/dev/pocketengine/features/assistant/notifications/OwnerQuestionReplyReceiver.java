package dev.pocketengine.features.assistant.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Explicit, non-exported RemoteInput receiver. Native TaskStore consumes the original wait once. */
public final class OwnerQuestionReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!OwnerQuestionReply.ACTION.equals(intent.getAction()))return;
        PendingResult pending=goAsync();
        java.util.concurrent.CompletableFuture.runAsync(()->{
            boolean accepted=false;
            try{
                if(!(context.getApplicationContext() instanceof OwnerQuestionReply.Host host))throw new IllegalStateException("原问题宿主未挂载");
                accepted=OwnerQuestionReply.dispatch(host,intent).optBoolean("accepted");
            }catch(Exception unavailable){/* No reply body, credentials or task content enter diagnostics. */}
            try{
                JSONObject view=new JSONObject(intent.getStringExtra("noticeView"));NotificationManager manager=context.getSystemService(NotificationManager.class);
                // The original phone Service observes the committed task and updates its own
                // foreground notice. Its existing phone navigation remains the owner route.
                if("phone_hold".equals(view.optString("noticeKind")))return;
                if(manager!=null){
                    PendingIntent open=PendingIntent.getActivity(context,0,AssistantTaskNotifications.open(context,view),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    Context localized=dev.pocketengine.features.operations.AppLanguage.wrap(context);
                    Notification notification=new Notification.Builder(context,AssistantTaskNotifications.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(localized.getString(accepted?dev.pocketengine.R.string.w8_notice_reply_saved:dev.pocketengine.R.string.w8_notice_reply_expired))
                            .setContentText(localized.getString(accepted?dev.pocketengine.R.string.w8_notice_reply_saved_note:dev.pocketengine.R.string.w8_notice_reply_expired_note)).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build();
                    manager.notify("assistant-task:"+view.getString("taskId")+":"+view.getString("token"),1906,notification);
                }
            }catch(Exception notificationUnavailable){/* A notification failure cannot undo or repeat a committed answer. */}
            finally{pending.finish();}
        });
    }
}
