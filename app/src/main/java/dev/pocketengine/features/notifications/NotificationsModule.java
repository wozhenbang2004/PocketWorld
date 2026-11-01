package dev.pocketengine.features.notifications;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private owner preview ingress. Generic commands cannot grant, read, export or execute it. */
public final class NotificationsModule implements EngineModule {
    /** Implemented by the one Application; the listener never constructs a second feature host. */
    public interface Host { NotificationsModule notificationsModule(); }
    /** The host may attach reviewed evidence to its existing TaskStore, never start a run here. */
    public interface ReviewedEvidenceAction { JSONObject save(JSONObject lowTrustEvidence)throws Exception; }
    private final Context app;
    private final Consumer<JSONObject> events;
    private final NotificationInbox inbox=new NotificationInbox();
    private final NotificationConsentStore consent;
    private boolean policyPersistenceHealthy=true, restoredConsent;
    private OwnerNotificationListenerService listener;
    private NotificationInbox.Lease lease;
    private String state="disabled";
    private boolean closed, cleanupUnconfirmed;
    private long rejectedPreviews;

    public NotificationsModule(FeatureContext context){this(context.android,payload->context.emit("notifications",payload));}
    NotificationsModule(Context context,Consumer<JSONObject> events){
        this.app=context.getApplicationContext();this.events=events;consent=new NotificationConsentStore(app.getNoBackupFilesDir());
        try{inbox.restore(consent.load());restoredConsent=inbox.enabled();if(restoredConsent)state="waiting_connection";}
        catch(Exception invalid){policyPersistenceHealthy=false;state="policy_unavailable";}
        reconcilePlatform();
        if(inbox.enabled()&&declared())try{NotificationListenerService.requestRebind(component());}catch(RuntimeException unavailable){state="disconnected";}
    }
    @Override public String id(){return "notifications";}
    @Override public synchronized JSONObject snapshot()throws Exception {
        reconcilePlatform();return metadata();
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        // No JSON owner=true/private scope/grant field can cross this boundary.
        throw new SecurityException("通知仅可在本机可见的主人界面审阅；群聊、模型和通用功能命令不能读取或授权");
    }
    public synchronized JSONObject ownerState(Activity activity)throws Exception {
        requireVisibleOwner(activity);reconcilePlatform();return metadata().put("packages",new JSONArray(inbox.packages()));
    }
    /** OS approval only opens its setting. It never enables this app's separate reading grant. */
    public void openSystemSettingsFromVisibleActivity(Activity activity){
        requireVisibleOwner(activity);Intent details=new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,component().flattenToString());
        if(details.resolveActivity(app.getPackageManager())!=null)activity.startActivity(details);
        else activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }
    /** Call from a real owner confirmation after dismissing its dialog, while Activity has focus. */
    public synchronized JSONObject enableFromVisibleActivity(Activity activity,String expectedRevision,Set<String> packages,boolean confirmed)throws Exception {
        requireVisibleOwner(activity);if(closed)throw new IllegalStateException("通知模块已关闭");
        if(!confirmed)throw new SecurityException("请明确确认允许所选应用的通知预览");
        Set<String> selected=NotificationInbox.validatePackages(packages);if(selected.contains(app.getPackageName()))throw new IllegalArgumentException("本应用的任务通知不能作为通知输入");
        if(!declared())throw new IllegalStateException("此版本尚未安装系统通知监听入口");
        if(!accessGranted())throw new SecurityException("请先在系统设置允许通知访问，再返回本机确认读取范围");
        if(inbox.enabled())throw new IllegalStateException("请先关闭当前通知读取，再审阅新的应用范围");
        inbox.enable(expectedRevision,selected);
        try{persistPolicy();}catch(Exception failed){revoke("policy_unavailable");throw new IllegalStateException("通知授权无法保存，未开启读取",failed);}
        state="waiting_connection";rejectedPreviews=0;changed();
        try{NotificationListenerService.requestRebind(component());}
        catch(RuntimeException rejected){revoke("bind_failed");throw new IllegalStateException("系统未确认通知监听连接，已关闭读取，请返回本机重试",rejected);}
        return metadata();
    }
    public synchronized JSONObject disableFromVisibleActivity(Activity activity,String expectedRevision)throws Exception {
        requireVisibleOwner(activity);inbox.disable(expectedRevision);releaseListener();state="disabled";
        persistRevocation();changed();if(!policyPersistenceHealthy)throw new IllegalStateException("已停止当前进程的读取，但重启后的撤销尚未确认，请在系统设置撤销通知访问");return metadata();
    }
    /** Retry an existing stored owner grant without asking the owner to re-authorize it. */
    public synchronized JSONObject reconnectFromVisibleActivity(Activity activity,String expectedRevision)throws Exception {
        requireVisibleOwner(activity);reconcilePlatform();if(!inbox.enabled()||!inbox.revision().equals(expectedRevision))throw new SecurityException("通知授权已失效，请刷新状态");
        if(!inbox.connected())try{NotificationListenerService.requestRebind(component());state="waiting_connection";}catch(RuntimeException unavailable){state="disconnected";throw new IllegalStateException("系统未确认重新连接",unavailable);}return metadata();
    }
    public synchronized JSONObject readFromVisibleActivity(Activity activity,String expectedRevision)throws Exception {
        requireVisibleOwner(activity);reconcilePlatform();if(!inbox.revision().equals(expectedRevision))throw new SecurityException("通知授权版本已变化，请刷新后读取");
        JSONArray notices=new JSONArray(),changes=new JSONArray();long now=android.os.SystemClock.elapsedRealtime();
        for(NotificationInbox.Notice notice:inbox.active(now))notices.put(envelope(notice));
        for(NotificationInbox.Change change:inbox.changes(now))changes.put(new JSONObject().put("notificationRef",change.ref).put("kind",change.kind).put("version",change.version).put("observedElapsedMillis",change.at).put("reason",change.reason).put("triggerCandidate",false));
        return metadata().put("notifications",notices).put("changes",changes).put("sourceIsHistory",false);
    }
    /** Snapshot/version recheck and the host's evidence write share the module's revocation lock. */
    public synchronized JSONObject withReviewedOwnerEvidence(Activity activity,String expectedRevision,String notificationRef,long version,ReviewedEvidenceAction action)throws Exception {
        requireVisibleOwner(activity);reconcilePlatform();NotificationInbox.Notice notice=inbox.reviewed(expectedRevision,notificationRef,version,android.os.SystemClock.elapsedRealtime());
        JSONObject evidence=envelope(notice).put("consentRevision",inbox.revision()).put("reviewedBy","native_visible_owner");
        return action.save(evidence);
    }
    synchronized NotificationInbox.Lease connected(OwnerNotificationListenerService service){
        reconcilePlatform();if(closed||!inbox.enabled()||!declared()||!accessGranted())return null;
        if(listener==service&&inbox.current(lease))return lease;
        releaseListener();listener=service;lease=inbox.connect();state="connected";changed();return lease;
    }
    synchronized boolean connectionCurrent(NotificationInbox.Lease expected){reconcilePlatform();long sequence=inbox.sequence();inbox.expire(android.os.SystemClock.elapsedRealtime());if(sequence!=inbox.sequence())changed();return inbox.current(expected);}
    synchronized void disconnected(NotificationInbox.Lease expected){
        if(!inbox.disconnect(expected))return;listener=null;lease=null;
        if(!accessGranted())revoke("access_revoked");else{state="disconnected";changed();}
    }
    synchronized void baselineFailed(NotificationInbox.Lease expected){if(inbox.current(expected))revoke("snapshot_failed");}
    synchronized void posted(NotificationInbox.Lease expected,StatusBarNotification sbn,boolean baseline){
        reconcilePlatform();if(sbn==null||!inbox.current(expected))return;String key=null;
        try{
            // The key identifies a previously readable revision even when its replacement
            // fails today's filter. Never leave that prior body available for owner review.
            key=sbn.getKey();String pkg=sbn.getPackageName();
            if(!inbox.accepts(expected,pkg)||pkg.equals(app.getPackageName())||!Process.myUserHandle().equals(sbn.getUser())){unavailable(expected,key);return;}
            Notification n=sbn.getNotification();if(n==null||(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0){unavailable(expected,key);return;}
            if(app.getPackageManager().isPackageSuspended(pkg)){unavailable(expected,key);return;}
            // Never touch extras before source/profile/consent checks. Sender text proves no identity.
            NotificationInbox.Content content=NotificationProjection.preview(sbn);
            if(!accessGranted()){revoke("access_revoked");return;}
            if(content.title.isEmpty()&&content.text.isEmpty()){unavailable(expected,key);return;}
            if(inbox.posted(expected,key,pkg,content,baseline||sbn.getPostTime()<=expected.connectedAtWall,android.os.SystemClock.elapsedRealtime()))changed();
        }catch(PackageManager.NameNotFoundException unavailable){rejectedPreviews++;unavailable(expected,key);}
        catch(RuntimeException malformed){rejectedPreviews++;unavailable(expected,key);}
    }
    private void unavailable(NotificationInbox.Lease expected,String key){if(inbox.unavailable(expected,key,android.os.SystemClock.elapsedRealtime()))changed();}
    synchronized void removed(NotificationInbox.Lease expected,StatusBarNotification sbn,int reason){
        reconcilePlatform();if(sbn==null||!inbox.current(expected))return;
        try{if(!inbox.accepts(expected,sbn.getPackageName())||!Process.myUserHandle().equals(sbn.getUser()))return;
            if(inbox.removed(expected,sbn.getKey(),reason,android.os.SystemClock.elapsedRealtime()))changed();
        }catch(RuntimeException malformed){rejectedPreviews++;}
    }
    synchronized void cleanupUnconfirmed(){cleanupUnconfirmed=true;changed();}
    private void reconcilePlatform(){if(inbox.enabled()&&!accessGranted())revoke("access_revoked");}
    private void revoke(String reason){inbox.revoke();releaseListener();state=reason;persistRevocation();changed();}
    private void persistPolicy()throws Exception {consent.save(inbox.policy());policyPersistenceHealthy=true;}
    private void persistRevocation(){try{persistPolicy();}catch(Exception failed){try{policyPersistenceHealthy=consent.erase();}catch(RuntimeException unavailable){policyPersistenceHealthy=false;}}}
    private void releaseListener(){OwnerNotificationListenerService previous=listener;NotificationInbox.Lease previousLease=lease;listener=null;lease=null;if(previous!=null)previous.releaseFromModule(previousLease);}
    private boolean accessGranted(){
        try{NotificationManager manager=app.getSystemService(NotificationManager.class);return manager!=null&&manager.isNotificationListenerAccessGranted(component());}catch(RuntimeException unavailable){return false;}
    }
    private boolean declared(){
        try{ServiceInfo info=app.getPackageManager().getServiceInfo(component(),0);return !info.exported&&"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(info.permission);}catch(PackageManager.NameNotFoundException|RuntimeException unavailable){return false;}
    }
    private ComponentName component(){return new ComponentName(app,OwnerNotificationListenerService.class);}
    private void requireVisibleOwner(Activity activity){
        if(closed)throw new IllegalStateException("通知模块已关闭");
        if(activity==null||activity.getApplication()!=app||activity.isFinishing()||activity.isDestroyed()||!activity.hasWindowFocus()||!activity.getWindow().getDecorView().isShown())throw new SecurityException("请返回可见且有焦点的本机主人界面");
    }
    private JSONObject metadata()throws Exception {
        return new JSONObject().put("id",id()).put("status",state).put("enabled",inbox.enabled()).put("listenerConnected",inbox.connected()).put("systemAccessGranted",accessGranted()).put("serviceDeclared",declared())
                .put("consentRevision",inbox.revision()).put("sequence",inbox.sequence()).put("evicted",inbox.evicted()).put("rejectedPreviews",rejectedPreviews)
                .put("cleanupUnconfirmed",cleanupUnconfirmed).put("defaultEnabled",false).put("restartRequiresOwnerConfirmation",false).put("restoredConsent",restoredConsent).put("policyPersistenceHealthy",policyPersistenceHealthy).put("fullMessageHistory",false).put("automaticExecution",false).put("groupAccess",false)
                .put("maxActive",NotificationInbox.MAX_ACTIVE).put("retentionMillis",NotificationInbox.RETENTION_MILLIS).put("commands",new JSONArray());
    }
    private static JSONObject envelope(NotificationInbox.Notice notice)throws Exception {
        return new JSONObject().put("source","android_notification_preview").put("trust","low").put("authority","none").put("audience","owner_private").put("notificationRef",notice.ref).put("version",notice.version)
                .put("kind",notice.kind).put("packageName",notice.packageName).put("postedAt",notice.content.postedAt).put("observedElapsedMillis",notice.observedAt).put("triggerCandidate",notice.triggerCandidate)
                .put("preview",new JSONObject().put("title",notice.content.title).put("text",notice.content.text).put("channelId",notice.content.channel).put("category",notice.content.category).put("ongoing",notice.content.ongoing))
                .put("isMessageHistory",false).put("identityVerifiedFromContent",false).put("mayExecute",false);
    }
    private void changed(){try{events.accept(new JSONObject().put("kind","notification_state_changed").put("consentRevision",inbox.revision()).put("sequence",inbox.sequence()).put("status",state));}catch(RuntimeException|org.json.JSONException ignored){}}
    @Override public synchronized void close(){if(closed)return;closed=true;inbox.disconnect(lease);releaseListener();state="closed";changed();}
}
