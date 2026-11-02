package dev.pocketengine.features.notifications;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Production Application Host -> actual listener callbacks -> visible owner -> original
 * AttachmentBook + SQLite TaskStore. Android notifications/OS grants remain test fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NotificationOwnerTaskJourneyTest {
    private static final String A="example.owner.messages",B="example.other.messages";
    private EngineApplication app;
    private NotificationsModule module;
    private AttachmentBook book;
    private ComponentName component;
    @Before public void setup(){
        app=(EngineApplication)RuntimeEnvironment.getApplication();module=app.notificationsModule();book=AttachmentRepository.get(app).book;component=new ComponentName(app,OwnerNotificationListenerService.class);
        ServiceInfo service=new ServiceInfo();service.name=component.getClassName();service.packageName=app.getPackageName();service.applicationInfo=app.getApplicationInfo();service.exported=false;service.enabled=true;service.permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE";Shadows.shadowOf(app.getPackageManager()).addOrUpdateService(service);
        for(String name:new String[]{A,B}){PackageInfo pkg=new PackageInfo();pkg.packageName=name;pkg.applicationInfo=new ApplicationInfo();pkg.applicationInfo.packageName=name;Shadows.shadowOf(app.getPackageManager()).installPackage(pkg);}
        permission(true);FixtureService.initial=new StatusBarNotification[0];FixtureService.enumerations=0;
    }
    private void permission(boolean granted){Shadows.shadowOf(app.getSystemService(NotificationManager.class)).setNotificationListenerAccessGranted(component,granted);}
    private ActivityController<Activity> owner(){return Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true);}
    private NotificationOwnerWorkflows workflow(Activity activity){return new NotificationOwnerWorkflows(activity,module,app.tasks,book);}
    private String enable(NotificationOwnerWorkflows owner)throws Exception{return owner.enable(owner.state().getString("consentRevision"),Set.of(A),true).getString("consentRevision");}
    private StatusBarNotification posted(String pkg,int id,String body,long at){Notification n=new Notification.Builder(app,"incoming").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("private owner title").setContentText(body).build();return new StatusBarNotification(pkg,pkg,id,"never-export-raw-tag",Process.myUid(),0,0,n,Process.myUserHandle(),at);}
    private JSONObject one(NotificationOwnerWorkflows owner,String revision)throws Exception{return owner.read(revision).getJSONArray("notifications").getJSONObject(0);}
    private NotificationOwnerWorkflows.Review review(NotificationOwnerWorkflows owner,String revision,String task)throws Exception{JSONObject notice=one(owner,revision);return owner.review(revision,notice.getString("notificationRef"),notice.getLong("version"),task);}
    private void mutateTask(String id,java.util.function.Consumer<JSONObject> mutation)throws Exception{Field field=app.tasks.getClass().getDeclaredField("db");field.setAccessible(true);JSONObject task=app.tasks.get(id,false);mutation.accept(task);ContentValues values=new ContentValues();values.put("document",task.toString());((SQLiteDatabase)field.get(app.tasks)).update("tasks",values,"task_id=?",new String[]{id});}
    private final class Connected implements AutoCloseable {
        final ServiceController<FixtureService> controller=Robolectric.buildService(FixtureService.class).create();
        Connected(){controller.get().onListenerConnected();}
        void post(StatusBarNotification notice){controller.get().onNotificationPosted(notice);}
        public void close(){controller.destroy();}
    }
    @Test public void realHostFiltersPackagesBaselineAndSavesOneExactImmutableFileWithoutExecuting()throws Exception{
        try(var activity=owner()){
            var owner=workflow(activity.get());String revision=enable(owner),task=app.tasks.create("原主人目标").getString("id");
            FixtureService.initial=new StatusBarNotification[]{posted(A,1,"baseline private body",System.currentTimeMillis()-1000),posted(B,2,"excluded body",System.currentTimeMillis()-1000)};
            try(var connected=new Connected()){
                assertSame(module,connected.controller.get().resolveModule());JSONObject baseline=one(owner,revision);assertEquals("baseline",baseline.getString("kind"));assertFalse(baseline.getBoolean("triggerCandidate"));assertEquals(1,owner.read(revision).getJSONArray("notifications").length());
                var exact=review(owner,revision,task);assertEquals(0,book.list().length());assertThrows(SecurityException.class,()->owner.save(exact,false));
                JSONObject first=owner.save(exact,true),again=owner.save(exact,true);assertEquals(first.getJSONObject("attachment").toString(),again.getJSONObject("attachment").toString());assertEquals(1,book.list().length());assertEquals(1,app.tasks.ownerAttachmentReferences(task).length());
                assertFalse(first.getBoolean("modelDispatched"));assertFalse(first.getBoolean("executionAuthorized"));JSONObject unchanged=app.tasks.get(task,false);assertTrue(unchanged.optString("runId").isEmpty());assertEquals("preparing",unchanged.getString("status"));assertFalse(unchanged.has("ownerInputs"));assertEquals(1,app.tasks.list().length());
                String saved=book.readText(first.getJSONObject("attachment").getString("id")).getString("text");JSONObject evidence=new JSONObject(saved);assertEquals("low",evidence.getString("trust"));assertEquals("none",evidence.getString("authority"));assertFalse(evidence.getBoolean("mayExecute"));assertFalse(evidence.getBoolean("identityVerifiedFromContent"));assertFalse(saved.contains("excluded body"));assertFalse(saved.contains("never-export-raw-tag"));
                String policy=Files.readString(app.getNoBackupFilesDir().toPath().resolve("owner-notification-access.json"));assertFalse(policy.contains("baseline private body"));assertFalse(module.snapshot().toString().contains("private owner title"));
                owner.disable(revision);assertEquals(1,book.list().length());assertEquals(1,app.tasks.ownerAttachmentReferences(task).length());assertThrows(SecurityException.class,()->owner.save(exact,true));
            }
        }
    }
    @Test public void missingSystemGrantEmptyScopeHiddenWindowAndGenericCommandsCannotGrant()throws Exception{
        try(var activity=owner()){
            var owner=workflow(activity.get());String revision=owner.state().getString("consentRevision");permission(false);assertThrows(SecurityException.class,()->owner.enable(revision,Set.of(A),true));permission(true);assertThrows(IllegalArgumentException.class,()->owner.enable(revision,Set.of(),true));
            activity.windowFocusChanged(false);assertThrows(SecurityException.class,()->owner.enable(revision,Set.of(A),true));assertThrows(SecurityException.class,()->owner.targets(""));assertThrows(SecurityException.class,()->owner.packages());activity.windowFocusChanged(true);
            assertThrows(SecurityException.class,()->owner.enable(revision,Set.of(A),false));assertThrows(SecurityException.class,()->module.handle("read",new JSONObject().put("owner",true).put("confirmed",true)));assertFalse(owner.state().getBoolean("enabled"));
        }
    }
    @Test public void changedRemovedExpiredAndRevokedSourcesCannotReachTaskAttachments()throws Exception{
        try(var activity=owner()){
            var owner=workflow(activity.get());String revision=enable(owner),task=app.tasks.create("原任务").getString("id");
            try(var connected=new Connected()){
                StatusBarNotification initial=posted(A,1,"before",System.currentTimeMillis()+1000);connected.post(initial);var stale=review(owner,revision,task);connected.post(posted(A,1,"after",System.currentTimeMillis()+2000));assertThrows(SecurityException.class,()->owner.save(stale,true));
                var removed=review(owner,revision,task);connected.controller.get().onNotificationRemoved(initial);assertThrows(SecurityException.class,()->owner.save(removed,true));
                connected.post(posted(A,1,"expires",System.currentTimeMillis()+3000));var expired=review(owner,revision,task);ShadowSystemClock.advanceBy(Duration.ofMinutes(16));assertThrows(SecurityException.class,()->owner.save(expired,true));
                connected.post(posted(A,1,"revoked",System.currentTimeMillis()+4000));var revoked=review(owner,revision,task);permission(false);assertThrows(SecurityException.class,()->owner.save(revoked,true));assertFalse(module.snapshot().getBoolean("enabled"));assertEquals(0,book.list().length());assertEquals(0,app.tasks.ownerAttachmentReferences(task).length());
            }
        }
    }
    @Test public void taskOwnerOriginRunAndOwnerWindowAreRecheckedAtSave()throws Exception{
        try(var activity=owner()){
            var owner=workflow(activity.get());String revision=enable(owner),task=app.tasks.create("原任务").getString("id");try(var connected=new Connected()){
                connected.post(posted(A,1,"source",System.currentTimeMillis()+1000));var exact=review(owner,revision,task);activity.windowFocusChanged(false);assertThrows(SecurityException.class,()->owner.save(exact,true));activity.windowFocusChanged(true);
                mutateTask(task,t->{try{t.put("actorId","other");}catch(Exception error){throw new RuntimeException(error);}});assertThrows(SecurityException.class,()->owner.save(exact,true));
                mutateTask(task,t->{try{t.put("actorId","owner").put("origin",new JSONObject().put("kind","group"));}catch(Exception error){throw new RuntimeException(error);}});assertThrows(SecurityException.class,()->owner.review(revision,one(owner,revision).getString("notificationRef"),one(owner,revision).getLong("version"),task));
                mutateTask(task,t->t.remove("origin"));var beforeRun=review(owner,revision,task);app.tasks.startAttempt(task,"main_screen",10);assertThrows(SecurityException.class,()->owner.save(beforeRun,true));assertEquals(0,book.list().length());assertEquals(0,app.tasks.ownerAttachmentReferences(task).length());
            }
        }
    }
    @Test public void reconnectReplaysOnlyBaselineWithNewRefsAndCannotSaveOldSelection()throws Exception{
        try(var activity=owner()){
            var owner=workflow(activity.get());String revision=enable(owner),task=app.tasks.create("原任务").getString("id");var source=posted(A,1,"existing",System.currentTimeMillis()+1000);NotificationOwnerWorkflows.Review old;
            try(var connected=new Connected()){connected.post(source);old=review(owner,revision,task);}
            FixtureService.initial=new StatusBarNotification[]{source};try(var reconnected=new Connected()){
                JSONObject current=one(owner,revision);assertFalse(current.getBoolean("triggerCandidate"));assertEquals("baseline",current.getString("kind"));assertEquals(0,owner.read(revision).getJSONArray("changes").length());assertThrows(SecurityException.class,()->owner.save(old,true));
                reconnected.post(source);assertEquals(1,owner.read(revision).getJSONArray("notifications").length());assertEquals(0,owner.read(revision).getJSONArray("changes").length());assertEquals(0,book.list().length());
            }
        }
    }
    public static final class FixtureService extends OwnerNotificationListenerService {
        static StatusBarNotification[] initial;static int enumerations;
        @Override public StatusBarNotification[] getActiveNotifications(){enumerations++;return initial;}
    }
}
