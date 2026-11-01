package dev.pocketengine.features.notifications;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** A21 fixed-head counterexamples migrated to intended safety behavior; assertions fail before A24B. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationReviewRegressionTest {
    private static final String PKG="example.messages";
    private Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();new NotificationConsentStore(context.getNoBackupFilesDir()).erase();
        ComponentName component=new ComponentName(context,OwnerNotificationListenerService.class);
        ServiceInfo service=new ServiceInfo();service.name=component.getClassName();service.packageName=context.getPackageName();service.applicationInfo=context.getApplicationInfo();service.exported=false;service.enabled=true;service.permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE";
        Shadows.shadowOf(context.getPackageManager()).addOrUpdateService(service);
        PackageInfo pkg=new PackageInfo();pkg.packageName=PKG;pkg.applicationInfo=new ApplicationInfo();pkg.applicationInfo.packageName=PKG;Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        Shadows.shadowOf(context.getSystemService(NotificationManager.class)).setNotificationListenerAccessGranted(component,true);
    }
    private StatusBarNotification notice(String text,int flags) {
        Notification n=new Notification.Builder(context,"incoming").setContentTitle("fixture-title").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_chat).build();n.flags|=flags;
        return new StatusBarNotification(PKG,PKG,1,"same-tag",Process.myUid(),0,0,n,Process.myUserHandle(),System.currentTimeMillis()+1000);
    }
    @Test public void filteredSameKeyUpdateInvalidatesOldBodyAndOwnerReview()throws Exception {
        try(NotificationsModule module=new NotificationsModule(context,event->{});ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true)){
            String revision=module.enableFromVisibleActivity(owner.get(),module.snapshot().getString("consentRevision"),Set.of(PKG),true).getString("consentRevision");
            Fixture.module=module;ServiceController<Fixture> controller=Robolectric.buildService(Fixture.class).create();try{
                Fixture service=controller.get();service.onListenerConnected();service.onNotificationPosted(notice("fixture-old-body",0));
                JSONObject before=module.readFromVisibleActivity(owner.get(),revision).getJSONArray("notifications").getJSONObject(0);
                service.onNotificationPosted(notice("fixture-new-filtered-summary",Notification.FLAG_GROUP_SUMMARY));
                assertEquals(0,module.readFromVisibleActivity(owner.get(),revision).getJSONArray("notifications").length());
                AtomicInteger saved=new AtomicInteger();assertThrows(SecurityException.class,()->module.withReviewedOwnerEvidence(owner.get(),revision,before.getString("notificationRef"),before.getLong("version"),evidence->{saved.incrementAndGet();return new JSONObject();}));assertEquals(0,saved.get());
            }finally{controller.destroy();}
        }
    }
    @Test public void unreadableSameKeyUpdateInvalidatesOldOwnerEvidence()throws Exception {
        try(NotificationsModule module=new NotificationsModule(context,event->{});ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true)){
            String revision=module.enableFromVisibleActivity(owner.get(),module.snapshot().getString("consentRevision"),Set.of(PKG),true).getString("consentRevision");
            Fixture.module=module;ServiceController<Fixture> controller=Robolectric.buildService(Fixture.class).create();try{
                Fixture service=controller.get();service.onListenerConnected();service.onNotificationPosted(notice("fixture-old-body",0));JSONObject before=module.readFromVisibleActivity(owner.get(),revision).getJSONArray("notifications").getJSONObject(0);
                StatusBarNotification unreadable=notice("unreadable",0);unreadable.getNotification().extras.putCharSequence(Notification.EXTRA_TITLE,new CharSequence(){public int length(){return 12;}public char charAt(int at){throw new IllegalArgumentException("synthetic unreadable preview");}public CharSequence subSequence(int a,int b){throw new IllegalArgumentException("synthetic unreadable preview");}});
                service.onNotificationPosted(unreadable);assertEquals(0,module.readFromVisibleActivity(owner.get(),revision).getJSONArray("notifications").length());
                AtomicInteger saved=new AtomicInteger();assertThrows(SecurityException.class,()->module.withReviewedOwnerEvidence(owner.get(),revision,before.getString("notificationRef"),before.getLong("version"),evidence->{saved.incrementAndGet();return new JSONObject();}));assertEquals(0,saved.get());
            }finally{controller.destroy();}
        }
    }
    @Test public void atomicRenameFailureCannotEnableReadingOrClaimHealthyPersistence()throws Exception {
        Path base=context.getNoBackupFilesDir().toPath().resolve("owner-notification-access.json");Files.createDirectory(base);Files.writeString(base.resolve("rename-blocker"),"fixture");
        try(NotificationsModule module=new NotificationsModule(context,event->{});ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true)){
            assertFalse(module.snapshot().getBoolean("policyPersistenceHealthy"));String revision=module.snapshot().getString("consentRevision");
            assertThrows(IllegalStateException.class,()->module.enableFromVisibleActivity(owner.get(),revision,Set.of(PKG),true));
            assertFalse(module.snapshot().getBoolean("enabled"));assertFalse(module.snapshot().getBoolean("policyPersistenceHealthy"));assertTrue(Files.isDirectory(base));assertEquals("fixture",Files.readString(base.resolve("rename-blocker")));
            Fixture.module=module;Fixture.enumerations=0;ServiceController<Fixture> controller=Robolectric.buildService(Fixture.class).create();try{
                controller.get().onListenerConnected();controller.get().onNotificationPosted(notice("fixture-unpersisted-preview",0));assertEquals(0,Fixture.enumerations);
                String current=module.snapshot().getString("consentRevision");assertEquals(0,module.readFromVisibleActivity(owner.get(),current).getJSONArray("notifications").length());
            }finally{controller.destroy();}
            try(NotificationsModule restarted=new NotificationsModule(context,event->{})){assertFalse(restarted.snapshot().getBoolean("enabled"));assertFalse(restarted.snapshot().getBoolean("policyPersistenceHealthy"));}
        }finally{Files.deleteIfExists(base.resolve("rename-blocker"));Files.deleteIfExists(base);Files.deleteIfExists(base.resolveSibling(base.getFileName()+".new"));}
    }
    @Test public void atomicRenameFailureOnDisableClearsMemoryAndReportsPersistenceFailure()throws Exception {
        Path base=context.getNoBackupFilesDir().toPath().resolve("owner-notification-access.json");
        try(NotificationsModule module=new NotificationsModule(context,event->{});ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true)){
            String revision=module.enableFromVisibleActivity(owner.get(),module.snapshot().getString("consentRevision"),Set.of(PKG),true).getString("consentRevision");
            Fixture.module=module;ServiceController<Fixture> controller=Robolectric.buildService(Fixture.class).create();try{
                controller.get().onListenerConnected();controller.get().onNotificationPosted(notice("fixture-old-body",0));JSONObject before=module.readFromVisibleActivity(owner.get(),revision).getJSONArray("notifications").getJSONObject(0);
                Files.delete(base);Files.createDirectory(base);Files.writeString(base.resolve("rename-blocker"),"fixture");
                assertThrows(IllegalStateException.class,()->module.disableFromVisibleActivity(owner.get(),revision));assertFalse(module.snapshot().getBoolean("enabled"));assertFalse(module.snapshot().getBoolean("policyPersistenceHealthy"));assertTrue(Files.exists(base.resolve("rename-blocker")));
                AtomicInteger saved=new AtomicInteger();assertThrows(SecurityException.class,()->module.withReviewedOwnerEvidence(owner.get(),revision,before.getString("notificationRef"),before.getLong("version"),evidence->{saved.incrementAndGet();return new JSONObject();}));assertEquals(0,saved.get());
            }finally{controller.destroy();}
        }finally{Files.deleteIfExists(base.resolve("rename-blocker"));Files.deleteIfExists(base);Files.deleteIfExists(base.resolveSibling(base.getFileName()+".new"));}
    }
    public static class Fixture extends OwnerNotificationListenerService {
        static NotificationsModule module;static int enumerations;
        @Override protected NotificationsModule resolveModule(){return module;}
        @Override public StatusBarNotification[] getActiveNotifications(){enumerations++;return new StatusBarNotification[0];}
    }
}
