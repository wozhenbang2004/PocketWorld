package dev.pocketengine.features;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real NotificationManager calls under Robolectric; not device delivery or user-reading evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HostAutomationCoordinatorTest {
    private Application app;
    private NotificationManager notifications;
    private TaskStore tasks;
    private final List<FeatureHost> hosts=new ArrayList<>();
    @Before public void prepare(){
        app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notifications=app.getSystemService(NotificationManager.class);tasks=new TaskStore(app);
    }
    @After public void close(){for(FeatureHost host:hosts)host.close();Thread.interrupted();}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private Fixture fixture(AtomicInteger posts,boolean failAfterPost)throws Exception {
        AutomationModule automation=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        FeatureHost features=new FeatureHost(event->{});features.register(automation);hosts.add(features);
        HostAutomationCoordinator coordinator=new HostAutomationCoordinator(app,features,tasks){
            @Override protected void post(String tag,int id,Notification notification)throws Exception {
                posts.incrementAndGet();super.post(tag,id,notification);
                if(failAfterPost)throw new IOException("Injected loss of caller acknowledgement AFTER actual notify");
            }
        };return new Fixture(automation,coordinator);
    }
    private JSONObject create(Fixture fixture,long due,String title)throws Exception {
        JSONObject source=new JSONObject(scope().toString()).put("kind","user_message").put("id","user-action-"+title);
        return fixture.module.handle("createCommitment",new JSONObject().put("scope",scope()).put("commitment",new JSONObject()
                .put("kind","explicit_user").put("title",title).put("dueAt",due).put("sourceRef",source))).getJSONObject("commitment");
    }
    private void enable(Fixture fixture)throws Exception {fixture.module.setFollowUpPolicy(scope(),0,new JSONObject().put("enabled",true).put("zoneId","UTC"));}
    private JSONObject onlyReceipt(Fixture fixture,JSONObject commitment)throws Exception {
        JSONArray receipts=fixture.module.listFollowUpReceipts(scope(),commitment.getString("id"));assertEquals(1,receipts.length());return receipts.getJSONObject(0);
    }
    @Test public void defaultDisabledAndPermissionLossNeverClaimOrPost()throws Exception {
        AtomicInteger posts=new AtomicInteger();Fixture fixture=fixture(posts,false);long now=System.currentTimeMillis();JSONObject commitment=create(fixture,now-1000,"默认关闭");
        assertEquals(-1,fixture.host.nextAutomationAt(now));fixture.host.onAutomationDue(now,20);
        assertEquals(0,posts.get());assertEquals(0,fixture.module.listFollowUpReceipts(scope(),commitment.getString("id")).length());
        enable(fixture);shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals(-1,fixture.host.nextAutomationAt(now));fixture.host.onAutomationDue(now,20);assertEquals(0,posts.get());
        assertEquals(0,fixture.module.listFollowUpReceipts(scope(),commitment.getString("id")).length());
    }
    @Test public void successfulSystemSubmissionOccursOnceAcrossNewHostInstances()throws Exception {
        AtomicInteger posts=new AtomicInteger();Fixture fixture=fixture(posts,false);long now=System.currentTimeMillis();JSONObject commitment=create(fixture,now-1000,"只提交一次");enable(fixture);
        assertTrue(fixture.host.nextAutomationAt(now)<=now);fixture.host.onAutomationDue(now,20);
        assertEquals(1,posts.get());assertEquals(1,notifications.getActiveNotifications().length);assertEquals("notified",onlyReceipt(fixture,commitment).getString("status"));
        assertNull(shadowOf(app).getNextStartedActivity());
        Fixture restarted=fixture(posts,false);restarted.host.onAutomationDue(System.currentTimeMillis(),20);assertEquals(1,posts.get());
        assertEquals(-1,restarted.host.nextAutomationAt(System.currentTimeMillis()));
        StatusBarNotification actual=notifications.getActiveNotifications()[0];
        android.content.Intent destination=shadowOf(actual.getNotification().contentIntent).getSavedIntent();
        assertEquals("dev.pocketengine.ui.ComposeActivity",destination.getComponent().getClassName());
        assertEquals(commitment.getString("id"),destination.getStringExtra("commitmentId"));assertNotNull(destination.getStringExtra("followUpClaimId"));
        assertFalse(destination.hasExtra("automationGoal"));assertFalse(destination.hasExtra("startRun"));
    }
    @Test public void exceptionAfterActualPostStaysUnknownAndRestartDoesNotResend()throws Exception {
        AtomicInteger posts=new AtomicInteger();Fixture fixture=fixture(posts,true);long now=System.currentTimeMillis();JSONObject commitment=create(fixture,now-1000,"回执丢失");enable(fixture);
        assertThrows(IOException.class,()->fixture.host.onAutomationDue(now,20));JSONObject receipt=onlyReceipt(fixture,commitment);String claim=receipt.getString("claimId");
        assertEquals("delivery_unknown",receipt.getString("status"));assertNotNull(tasks.followUpNotificationLink(claim));assertEquals(1,notifications.getActiveNotifications().length);
        Fixture restarted=fixture(posts,false);restarted.host.onAutomationDue(System.currentTimeMillis(),20);assertEquals(1,posts.get());assertEquals("delivery_unknown",onlyReceipt(restarted,commitment).getString("status"));
        JSONObject reconciled=restarted.host.reconcileNotification(claim);assertTrue(reconciled.getBoolean("reconciled"));assertEquals("active_system_notification",reconciled.getString("source"));assertEquals("notified",reconciled.getJSONObject("receipt").getString("status"));
    }
    @Test public void dismissedNotificationCannotBeReconciledAsNeverSent()throws Exception {
        Fixture fixture=fixture(new AtomicInteger(),true);long now=System.currentTimeMillis();JSONObject commitment=create(fixture,now-1000,"消失不是未发");enable(fixture);
        assertThrows(IOException.class,()->fixture.host.onAutomationDue(now,20));String claim=onlyReceipt(fixture,commitment).getString("claimId");
        JSONObject link=tasks.followUpNotificationLink(claim);notifications.cancel(link.getString("tag"),link.getInt("notificationId"));
        JSONObject result=fixture.host.reconcileNotification(claim);assertFalse(result.getBoolean("reconciled"));assertEquals("delivery_unknown",result.getJSONObject("receipt").getString("status"));
    }
    @Test public void futureTimeAndDisabledChannelAreReadOnlyAndDispatchBudgetIsBounded()throws Exception {
        AtomicInteger posts=new AtomicInteger();Fixture fixture=fixture(posts,false);long now=System.currentTimeMillis();JSONObject future=create(fixture,now+3600000,"未来承诺");create(fixture,now+7200000,"更晚承诺");enable(fixture);
        assertEquals(future.getLong("followUpAt"),fixture.host.nextAutomationAt(now));assertEquals(0,posts.get());
        create(fixture,now-1000,"到期一");create(fixture,now-1000,"到期二");create(fixture,now-1000,"到期三");fixture.host.onAutomationDue(now,1);assertEquals(1,posts.get());
        notifications.deleteNotificationChannel("personal_reminders");notifications.createNotificationChannel(new NotificationChannel("personal_reminders","关闭",NotificationManager.IMPORTANCE_NONE));
        assertEquals(NotificationManager.IMPORTANCE_NONE,notifications.getNotificationChannel("personal_reminders").getImportance());
        assertEquals(-1,fixture.host.nextAutomationAt(now));fixture.host.onAutomationDue(now,20);assertEquals(1,posts.get());
    }
    @Test public void interruptedAlarmPassDoesNotBeginANewDispatch()throws Exception {
        AtomicInteger posts=new AtomicInteger();Fixture fixture=fixture(posts,false);long now=System.currentTimeMillis();JSONObject commitment=create(fixture,now-1000,"尊重停止");enable(fixture);
        Thread.currentThread().interrupt();try{fixture.host.onAutomationDue(now,20);}finally{Thread.interrupted();}
        assertEquals(0,posts.get());assertEquals(0,fixture.module.listFollowUpReceipts(scope(),commitment.getString("id")).length());
    }
    @Test public void consumedCandidatesDoNotHideLaterEligibleWorkOrSpendDispatchBudget()throws Exception {
        AtomicInteger posts=new AtomicInteger();Fixture fixture=fixture(posts,false);long now=System.currentTimeMillis();
        for(int i=0;i<20;i++)create(fixture,now-2000,"已提交的旧承诺"+i);enable(fixture);
        fixture.host.onAutomationDue(now,100);assertEquals(20,posts.get());
        JSONObject later=create(fixture,now-1000,"仍需跟进的新承诺");
        assertTrue(fixture.host.nextAutomationAt(now)<=now);
        fixture.host.onAutomationDue(now,1);assertEquals(21,posts.get());
        assertEquals("notified",onlyReceipt(fixture,later).getString("status"));
    }
    private static final class Fixture{final AutomationModule module;final HostAutomationCoordinator host;Fixture(AutomationModule module,HostAutomationCoordinator host){this.module=module;this.host=host;}}
}
