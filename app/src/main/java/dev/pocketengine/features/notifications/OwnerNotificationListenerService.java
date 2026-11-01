package dev.pocketengine.features.notifications;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** System-bound, owner-authorized preview ingress; no started service, task runner or reply API. */
public class OwnerNotificationListenerService extends NotificationListenerService {
    private final Handler main=new Handler(Looper.getMainLooper());
    private NotificationsModule module;
    private NotificationInbox.Lease lease;
    private boolean connected;
    protected NotificationsModule resolveModule(){
        return getApplication() instanceof NotificationsModule.Host?((NotificationsModule.Host)getApplication()).notificationsModule():null;
    }
    @Override public void onListenerConnected(){
        connected=true;module=resolveModule();lease=module==null?null:module.connected(this);
        if(lease==null){unbindConnected();return;}
        NotificationInbox.Lease expected=lease;
        try{
            StatusBarNotification[] existing=getActiveNotifications();
            if(existing==null)throw new IllegalStateException("当前通知快照不可读取");
            // The API may replay outstanding notifications when reconnecting. Baselines are
            // viewable evidence only, never new trigger events. No worker retains raw objects.
            for(int i=0;i<Math.min(existing.length,512);i++){if(!module.connectionCurrent(expected))return;module.posted(expected,existing[i],true);}
            main.postDelayed(permissionCheck,1500);
        }catch(RuntimeException unavailable){module.baselineFailed(expected);releaseFromModule(expected);}
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn){NotificationInbox.Lease expected=lease;if(module!=null&&expected!=null)module.posted(expected,sbn,false);}
    @Override public void onNotificationRemoved(StatusBarNotification sbn,RankingMap rankingMap,int reason){NotificationInbox.Lease expected=lease;if(module!=null&&expected!=null)module.removed(expected,sbn,reason);}
    @Override public void onNotificationRemoved(StatusBarNotification sbn){NotificationInbox.Lease expected=lease;if(module!=null&&expected!=null)module.removed(expected,sbn,0);}
    private final Runnable permissionCheck=new Runnable(){@Override public void run(){
        NotificationInbox.Lease expected=lease;if(module==null||expected==null||!connected)return;
        if(!module.connectionCurrent(expected)){releaseFromModule(expected);return;}main.postDelayed(this,1500);
    }};
    /** Exact lease also fences a delayed main-thread release after a new connection. */
    void releaseFromModule(NotificationInbox.Lease expected){
        Runnable release=()->{if(lease!=expected)return;lease=null;main.removeCallbacks(permissionCheck);unbindConnected();};
        if(Looper.myLooper()==Looper.getMainLooper())release.run();else main.post(release);
    }
    private void unbindConnected(){if(!connected)return;connected=false;try{requestUnbind();}catch(RuntimeException ignored){if(module!=null)module.cleanupUnconfirmed();}}
    @Override public void onListenerDisconnected(){
        connected=false;main.removeCallbacks(permissionCheck);NotificationInbox.Lease old=lease;lease=null;if(module!=null&&old!=null)module.disconnected(old);
        // Rebinding after a temporary disconnect is left to the system or explicit owner enable.
    }
    @Override public void onDestroy(){onListenerDisconnected();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
