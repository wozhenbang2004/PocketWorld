package dev.pocketengine.features.automation;

import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationModuleTest {
    private AutomationModule module(Context context)throws Exception{return new AutomationModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));}
    private JSONObject rule(long at)throws Exception{return new JSONObject().put("title","检查目标").put("kind","phone_goal").put("goal","打开计算器").put("schedule",new JSONObject().put("type","once").put("at",at));}
    @Test public void savingSchedulesAnInexactAlarmAndPauseRemovesIt()throws Exception {
        Context context=RuntimeEnvironment.getApplication();AutomationModule module=module(context);AlarmManager alarms=context.getSystemService(AlarmManager.class);
        JSONObject saved=module.handle("save",new JSONObject().put("rule",rule(System.currentTimeMillis()+60_000))).getJSONObject("rule");
        assertEquals(1,shadowOf(alarms).getScheduledAlarms().size());
        module.handle("setEnabled",new JSONObject().put("id",saved.getString("id")).put("enabled",false));
        assertEquals(0,shadowOf(alarms).getScheduledAlarms().size());assertFalse(module.snapshot().getBoolean("autonomousPhoneExecution"));
    }
    @Test public void duePhoneGoalOnlyPostsNotificationAndDoesNotLaunchAnActivity()throws Exception {
        Application application=RuntimeEnvironment.getApplication();AutomationModule module=module(application);
        module.handle("save",new JSONObject().put("rule",rule(System.currentTimeMillis()-1000)));
        JSONObject result=module.handle("checkDue",new JSONObject());assertEquals("awaiting_user",result.getJSONArray("receipts").getJSONObject(0).getString("status"));
        assertEquals(1,application.getSystemService(NotificationManager.class).getActiveNotifications().length);
        assertNull(shadowOf(application).getNextStartedActivity());
    }
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject commitment()throws Exception {
        return new JSONObject().put("kind","explicit_user").put("title","跟进订单结果").put("dueAt",System.currentTimeMillis()-1000)
                .put("sourceRef",new JSONObject(scope().toString()).put("kind","user_message").put("id","message-1"));
    }
    @Test public void commitmentCommandsPersistButDoNotScheduleNotifyOrExecute()throws Exception {
        Application application=RuntimeEnvironment.getApplication();AutomationModule module=module(application);
        JSONObject created=module.handle("createCommitment",new JSONObject().put("scope",scope()).put("commitment",commitment())).getJSONObject("commitment");
        assertEquals("pending",created.getString("status"));assertFalse(created.getBoolean("executionAuthorized"));
        JSONObject due=module.handle("dueCommitments",new JSONObject().put("scope",scope()));
        assertEquals(1,due.getJSONArray("commitments").length());assertEquals("not_dispatched",due.getString("delivery"));
        assertEquals(0,shadowOf(application.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
        assertEquals(0,application.getSystemService(NotificationManager.class).getActiveNotifications().length);
        assertNull(shadowOf(application).getNextStartedActivity());
        assertEquals(0,module.handle("list",new JSONObject()).getJSONArray("rules").length());
        assertEquals(0,module.handle("receipts",new JSONObject()).getJSONArray("receipts").length());
        assertEquals("read_only_candidates",module.snapshot().getString("commitmentFollowUpMode"));
        assertFalse(module.snapshot().getBoolean("completionEvidenceAvailable"));
        assertEquals(created.getString("id"),module(application).handle("listCommitments",new JSONObject().put("scope",scope())).getJSONArray("commitments").getJSONObject(0).getString("id"));
    }
    @Test public void commandBoundaryRequiresScopeRevisionAndHostEvidence()throws Exception {
        AutomationModule module=module(RuntimeEnvironment.getApplication());
        assertThrows(Exception.class,()->module.handle("createCommitment",new JSONObject().put("commitment",commitment())));
        JSONObject created=module.handle("createCommitment",new JSONObject().put("scope",scope()).put("commitment",commitment())).getJSONObject("commitment");
        String id=created.getString("id");
        assertThrows(IllegalArgumentException.class,()->module.handle("cancelCommitment",new JSONObject().put("scope",scope()).put("id",id)));
        assertThrows(IllegalArgumentException.class,()->module.handle("cancelCommitment",new JSONObject().put("scope",scope()).put("id",id).put("revision",1.5)));
        assertThrows(IllegalStateException.class,()->module.handle("completeCommitment",new JSONObject().put("scope",scope()).put("id",id).put("revision",1)
                .put("evidenceRef",new JSONObject().put("kind","owner_confirmation").put("id","invented").put("verified",true))));
        assertThrows(IllegalArgumentException.class,()->module.handle("onCommitmentTaskCompleted",new JSONObject()));
        JSONObject cancelled=module.handle("cancelCommitment",new JSONObject().put("scope",scope()).put("id",id).put("revision",1)).getJSONObject("commitment");
        assertEquals("cancelled",cancelled.getString("status"));assertEquals(0,module.dueCommitments(scope(),10).getJSONArray("commitments").length());
    }
    @Test public void followUpNativeAdmissionNeverPostsAndMutationsAreNotFeatureCommands()throws Exception {
        Application application=RuntimeEnvironment.getApplication();AutomationModule module=module(application);
        JSONObject created=module.handle("createCommitment",new JSONObject().put("scope",scope()).put("commitment",commitment())).getJSONObject("commitment");
        JSONObject candidate=new JSONObject().put("scope",scope()).put("id",created.getString("id")).put("revision",created.getLong("revision"))
                .put("candidateKey",created.getString("candidateKey"));
        assertEquals("disabled",module.handle("evaluateFollowUp",candidate).getString("reason"));
        module.setFollowUpPolicy(scope(),0,new JSONObject().put("enabled",true).put("zoneId","UTC"));
        assertTrue(module.handle("evaluateFollowUp",candidate).getBoolean("eligible"));
        for(String forbidden:new String[]{"setFollowUpPolicy","claimFollowUp","beginFollowUpDelivery","recordFollowUpDelivery"})
            assertThrows(IllegalArgumentException.class,()->module.handle(forbidden,candidate));
        JSONObject claimed=module.claimFollowUp(scope(),created.getString("id"),created.getLong("revision"),created.getString("candidateKey"),300000);
        String claimId=claimed.getJSONObject("receipt").getString("claimId");
        assertFalse(claimed.getBoolean("dispatchGranted"));assertTrue(module.beginFollowUpDelivery(scope(),claimId).getBoolean("dispatchGranted"));
        assertEquals("delivery_unknown",module(application).getFollowUpReceipt(scope(),claimId).getString("status"));
        assertEquals(claimId,module(application).listFollowUpReceipts(scope(),created.getString("id")).getJSONObject(0).getString("claimId"));
        assertFalse(module.beginFollowUpDelivery(scope(),claimId).getBoolean("dispatchGranted"));
        assertEquals(0,shadowOf(application.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
        assertEquals(0,application.getSystemService(NotificationManager.class).getActiveNotifications().length);
        assertNull(shadowOf(application).getNextStartedActivity());
        assertEquals(0,module.handle("list",new JSONObject()).getJSONArray("rules").length());
        assertEquals(0,module.handle("receipts",new JSONObject()).getJSONArray("receipts").length());
        assertEquals("host_only",module.snapshot().getString("followUpDeliveryDriver"));
    }

}
