package dev.pocketengine.features.notifications;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/** Reads only the displayed preview after the module admits package/user/consent. */
final class NotificationProjection {
    private NotificationProjection(){}
    static NotificationInbox.Content preview(StatusBarNotification sbn){
        Notification notification=sbn.getNotification();Bundle extras=notification.extras;
        Preview title=text(extras,Notification.EXTRA_TITLE,512),body=text(extras,Notification.EXTRA_BIG_TEXT,2048);
        if(body.text.isEmpty())body=text(extras,Notification.EXTRA_TEXT,2048);
        // Deliberately do not inspect EXTRA_MESSAGES/HISTORIC_MESSAGES, people, actions,
        // RemoteInput, contentIntent, RemoteViews or arbitrary app-provided extras.
        return new NotificationInbox.Content(title.text,body.text,notification.getChannelId(),notification.category,sbn.getPostTime(),(notification.flags&Notification.FLAG_ONGOING_EVENT)!=0,title.truncated,body.truncated);
    }
    private record Preview(String text,boolean truncated){}
    private static Preview text(Bundle extras,String name,int limit){
        if(extras==null)return new Preview("",false);CharSequence value=extras.getCharSequence(name);if(value==null)return new Preview("",false);
        int end=Math.min(value.length(),limit);if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;
        // Copy bounded plain characters. Spans, parcelables and app-owned objects are not retained.
        StringBuilder out=new StringBuilder(end);for(int i=0;i<end;i++)out.append(value.charAt(i));return new Preview(out.toString(),end<value.length());
    }
}
