package dev.pocketengine.features.assistant.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import dev.pocketengine.features.assistant.OwnerPhoneQuestions;
import org.json.JSONObject;

/** An ordinary answer on the existing phone foreground notification, never an approval. */
public final class OwnerPhoneQuestionNotification {
    private OwnerPhoneQuestionNotification(){}
    public static void add(Notification.Builder builder,Context context,JSONObject task)throws Exception{
        JSONObject view=OwnerPhoneQuestions.noticeView(task);if(view==null)return;
        JSONObject notice=task.optJSONObject("ownerPhoneQuestionNotice");
        if(notice==null||!"available".equals(notice.optString("phase"))||!view.getString("token").equals(notice.optString("token")))return;
        PendingIntent answer=PendingIntent.getBroadcast(context,0,OwnerQuestionReply.intent(context,OwnerQuestionReply.ACTION,view).putExtra("phoneOwnerQuestion",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        Notification.Action.Builder action=new Notification.Action.Builder(android.R.drawable.ic_menu_send,"回答问题",answer)
            .addRemoteInput(new RemoteInput.Builder(OwnerQuestionReply.KEY).setLabel("回答原手机任务").build())
            .setAllowGeneratedReplies(false);
        if(android.os.Build.VERSION.SDK_INT>=31)action.setAuthenticationRequired(true);
        builder.addAction(action.build());
    }
}
