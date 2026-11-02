package dev.pocketengine.features.assistant.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.features.assistant.OwnerPhoneQuestions;
import org.json.JSONObject;

/** Adds an ordinary answer only to the original normal-phone foreground notification. */
public final class OwnerPhoneQuestionNotification {
    private OwnerPhoneQuestionNotification(){}
    public static void add(Notification.Builder builder,Context context,JSONObject task)throws Exception{
        JSONObject view=OwnerPhoneQuestions.noticeView(task);if(view==null)return;JSONObject notice=task.optJSONObject("ownerPhoneQuestionNotice");
        if(notice==null||!"available".equals(notice.optString("phase"))||!view.getString("token").equals(notice.optString("token")))return;
        Context localized=dev.pocketengine.features.operations.AppLanguage.wrap(context);
        Intent intent=new Intent(context,OwnerQuestionReplyReceiver.class).setAction(OwnerQuestionReply.ACTION)
            .setData(new Uri.Builder().scheme("pocketworld-phone-question").authority("owner").appendPath(view.getString("taskId")).appendPath(view.getString("runId")).appendPath(Integer.toString(view.getInt("epoch"))).appendPath(view.getString("token")).build())
            .putExtra("questionIdentity",view.getJSONObject("ownerQuestion").toString()).putExtra("noticeToken",view.getString("token")).putExtra("noticeView",view.toString());
        PendingIntent answer=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send,localized.getString(dev.pocketengine.R.string.w8_notice_reply),answer)
            .addRemoteInput(new RemoteInput.Builder(OwnerQuestionReply.KEY).setLabel(localized.getString(dev.pocketengine.R.string.w8_notice_reply_label)).build()).setAllowGeneratedReplies(false).build());
    }
}
