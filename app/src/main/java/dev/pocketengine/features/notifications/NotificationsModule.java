package dev.pocketengine.features.notifications;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-selected preview ingress. Model delivery is a separate original-task approval. */
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
    private volatile boolean closed;
    private boolean cleanupUnconfirmed;
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
    /** Mount only on the existing private phone run. This does not enable notification access. */
    public CapabilityPort bind(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl originalControl,EventSink approvalEvents)throws Exception {
        if(closed)throw new IllegalStateException("通知模块已关闭");
        JSONObject task=tasks.get(binding.taskId,false);
        if(!NotificationTaskAuthority.eligible(task,binding.allowWrites))return existing;
        if(binding.originalControl()!=originalControl||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch)
                ||!binding.runId.equals(task.optString("runId"))||binding.epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("通知等待需要原任务执行绑定");
        return new NotificationTaskCapabilityPort(existing,originalControl,new NotificationTaskAuthority(tasks,binding,approvalEvents),this);
    }
    static final class TaskSource {
        final NotificationInbox.Lease lease;
        final String packageName,identity;
        TaskSource(NotificationInbox.Lease lease,String packageName,JSONObject identity){this.lease=lease;this.packageName=packageName;this.identity=identity.toString();}
        JSONObject description()throws Exception{return new JSONObject(identity);}
    }
    static final class TaskSourceUnavailable extends Exception {
        TaskSourceUnavailable(String message){super(message);}
    }
    /** Body-free source identity for the exact owner approval; never a reading grant. */
    synchronized TaskSource taskSource(String packageName)throws Exception {
        reconcilePlatform();
        if(closed||!policyPersistenceHealthy||!inbox.enabled()||!inbox.current(lease)||!inbox.accepts(lease,packageName)||!declared()||!accessGranted())
            throw new TaskSourceUnavailable("请在通知设置允许这个应用的预览并连接系统监听，再审批原任务的单次通知等待");
        if(packageName.equals(app.getPackageName()))throw new TaskSourceUnavailable("本应用的任务通知不能作为通知等待输入");
        try{
            UserManager users=app.getSystemService(UserManager.class);
            int user=dev.pocketengine.phone.setup.AndroidAppCopies.id(Process.myUserHandle());
            long serial=users==null?-1:users.getSerialNumberForUser(Process.myUserHandle());
            if(user<0||serial<0||!users.isUserUnlocked())throw new TaskSourceUnavailable("当前宿主 Android 资料未解锁或身份不可核验");
            PackageManager pm=app.getPackageManager();PackageInfo pkg=pm.getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES);
            if(pkg.applicationInfo==null||!pkg.applicationInfo.enabled||pkg.applicationInfo.uid<0||pkg.applicationInfo.uid/100000!=user||pm.isPackageSuspended(packageName)||pkg.signingInfo==null)
                throw new TaskSourceUnavailable("当前资料的通知应用身份或可用状态不可核验");
            Signature[] signers=pkg.signingInfo.getApkContentsSigners();
            if(signers==null||signers.length==0)throw new TaskSourceUnavailable("通知应用签名不可核验");
            ArrayList<String> certificates=new ArrayList<>();
            for(Signature signer:signers){byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());StringBuilder encoded=new StringBuilder();for(byte value:digest)encoded.append(String.format(java.util.Locale.ROOT,"%02x",value&255));certificates.add(encoded.toString());}
            certificates.sort(String::compareTo);
            JSONObject identity=new JSONObject().put("packageName",packageName).put("uid",pkg.applicationInfo.uid).put("userId",user).put("profileSerial",serial)
                    .put("versionCode",pkg.getLongVersionCode()).put("firstInstallTime",pkg.firstInstallTime).put("lastUpdateTime",pkg.lastUpdateTime).put("certificates",new JSONArray(certificates))
                    .put("consentRevision",inbox.revision()).put("connectionId",lease.connectionId).put("scope","current_host_android_profile");
            return new TaskSource(lease,packageName,identity);
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){throw new TaskSourceUnavailable("通知应用或资料身份不可读取，请在本机确认后重试");}
    }
    synchronized void requireTaskSource(TaskSource expected)throws Exception {
        TaskSource current=taskSource(expected.packageName);
        if(expected.lease!=current.lease||!TaskStore.capabilityFingerprint(expected.description()).equals(TaskStore.capabilityFingerprint(current.description())))
            throw new TaskSourceUnavailable("通知连接、授权范围、应用安装或资料已变化；旧的等待不能继续");
    }
    synchronized NotificationInbox.Wait beginTaskWait(TaskSource source,NotificationWaitRequest request,long taskDeadlineAt)throws Exception {
        requireTaskSource(source);long duration=Math.min(request.timeoutMs,taskDeadlineAt-System.currentTimeMillis());
        if(duration<=0)throw new TaskSourceUnavailable("原任务期限已到，不能开始新的通知等待");
        long now=SystemClock.elapsedRealtime();return inbox.beginWait(source.lease,request,now,now+duration);
    }
    synchronized JSONObject taskWaitMatch(TaskSource source,NotificationInbox.Wait waiting)throws Exception {
        requireTaskSource(source);NotificationInbox.Notice notice=inbox.matched(waiting,SystemClock.elapsedRealtime());
        if(notice==null)return null;
        return envelope(notice).put("audience","original_owner_phone_task").put("consentRevision",source.lease.revision)
                .put("connectionId",source.lease.connectionId).put("observedWhileWaiting",true).put("historyReplay",false);
    }
    synchronized void awaitTaskWaitChange(NotificationInbox.Wait waiting,long millis)throws InterruptedException {
        // Polling bounds cancellation and source checks; no callback may start or resume a run.
        long remaining=waiting.deadline-SystemClock.elapsedRealtime();if(remaining>0)wait(Math.max(1,Math.min(Math.min(millis,remaining),200)));
    }
    synchronized void endTaskWait(NotificationInbox.Wait waiting){inbox.closeWait(waiting);}
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
                .put("taskWaitImplemented",true).put("taskWaitRequiresSeparateOwnerApproval",true).put("taskWaitMaxMillis",NotificationWaitRequest.MAX_TIMEOUT_MS)
                .put("maxActive",NotificationInbox.MAX_ACTIVE).put("retentionMillis",NotificationInbox.RETENTION_MILLIS).put("commands",new JSONArray());
    }
    private static JSONObject envelope(NotificationInbox.Notice notice)throws Exception {
        return new JSONObject().put("source","android_notification_preview").put("trust","low").put("authority","none").put("audience","owner_private").put("notificationRef",notice.ref).put("version",notice.version)
                .put("kind",notice.kind).put("packageName",notice.packageName).put("postedAt",notice.content.postedAt).put("observedElapsedMillis",notice.observedAt).put("triggerCandidate",notice.triggerCandidate)
                .put("preview",new JSONObject().put("title",notice.content.title).put("text",notice.content.text).put("channelId",notice.content.channel).put("category",notice.content.category).put("ongoing",notice.content.ongoing)
                        .put("titleTruncated",notice.content.titleTruncated).put("textTruncated",notice.content.textTruncated).put("channelIdTruncated",notice.content.channelTruncated).put("categoryTruncated",notice.content.categoryTruncated)
                        .put("titleLimitUtf16",512).put("textLimitUtf16",2048).put("isFullMessage",false))
                .put("isMessageHistory",false).put("identityVerifiedFromContent",false).put("mayExecute",false);
    }
    private synchronized void changed(){notifyAll();try{events.accept(new JSONObject().put("kind","notification_state_changed").put("consentRevision",inbox.revision()).put("sequence",inbox.sequence()).put("status",state));}catch(RuntimeException|org.json.JSONException ignored){}}
    @Override public synchronized void close(){if(closed)return;closed=true;inbox.disconnect(lease);releaseListener();state="closed";changed();}
}
