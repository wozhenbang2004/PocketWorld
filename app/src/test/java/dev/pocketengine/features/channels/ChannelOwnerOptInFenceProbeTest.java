package dev.pocketengine.features.channels;
import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Isolated real command handler, synthetic native sender binding; no account, device or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelOwnerOptInFenceProbeTest {
 @Test public void reviewedAccountAConsentCannotEnableReplacementAccountB() throws Exception {
  Application app=RuntimeEnvironment.getApplication();
  ChannelsModule module=new ChannelsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),e->{}));
  try {
   java.lang.reflect.Field field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);ChannelCore core=(ChannelCore)field.get(module);
   String profile=ChannelSecurityTest.addProfile(core,"binding-probe","webhook");
   String a=core.accept(profile,ChannelSecurityTest.message("binding-a","account-a","dm",""),"trusted_relay").getJSONObject("event").getString("id");
   JSONObject reviewed=core.bindOwner(profile,a,true);
   JSONObject confirmation=new JSONObject().put("profileId",profile).put("enabled",true).put("confirmed",true)
      .put("expectedBindingEpoch",reviewed.getString("bindingEpoch")).put("expectedTaskGrantEpoch",reviewed.getString("taskGrantEpoch"));
   // A native update wins after the dialog was reviewed, before its queued confirmation runs.
   String b=core.accept(profile,ChannelSecurityTest.message("binding-b","account-b","dm",""),"trusted_relay").getJSONObject("event").getString("id");
   core.bindOwner(profile,b,true);
   assertThrows("Stale opt-in must not authorize a different bound account",SecurityException.class,()->module.handle("owner.tasks.set",confirmation));
   assertFalse(core.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));
   assertThrows(IllegalArgumentException.class,()->module.handle("owner.tasks.set",new JSONObject().put("profileId",profile).put("enabled",true).put("confirmed",true)));
   JSONObject current=core.snapshot().getJSONArray("bindings").getJSONObject(0);
   JSONObject fresh=new JSONObject().put("profileId",profile).put("enabled",true).put("confirmed",true)
      .put("expectedBindingEpoch",current.getString("bindingEpoch")).put("expectedTaskGrantEpoch",current.getString("taskGrantEpoch"));
   module.handle("owner.tasks.set",fresh);
   assertTrue(core.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));
   assertThrows("The same reviewed policy cannot be applied twice",SecurityException.class,()->module.handle("owner.tasks.set",fresh));
  } finally {module.close();}
 }
 @Test public void inspectionReturnsLatestHostIdentityEvenWhenQqReplyQuotaIsFull() throws Exception {
  Application app=RuntimeEnvironment.getApplication();ChannelsModule module=new ChannelsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),e->{}));
  try {
   java.lang.reflect.Field field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);ChannelCore core=(ChannelCore)field.get(module);
   String profile=ChannelSecurityTest.addProfile(core,"inspect-quota","qq");
   String binding=core.accept(profile,ChannelSecurityTest.message("bind","owner","dm",""),"qq_signature").getJSONObject("event").getString("id");
   core.bindOwner(profile,binding,true);core.setOwnerTasks(profile,true,true);
   String event=core.accept(profile,ChannelSecurityTest.message("task","owner","dm","").put("text","/task preserve inspect"),"qq_signature").getJSONObject("event").getString("id");
   String request=core.prepareHostTask(event).getJSONObject("request").getString("requestId"),task=core.hostTask(request).getString("taskId");
   for(int revision=1;revision<=4;revision++){
    HostTaskPort.TaskState receipt=new HostTaskPort.TaskState(task,task,1,revision,"running","step "+revision);
    core.hostTaskAdmitted(request,receipt);core.queueHostTaskUpdate(request,receipt);
   }
   String run=java.util.UUID.randomUUID().toString();HostTaskPort.TaskState latest=new HostTaskPort.TaskState(task,run,2,5,"awaiting_user","native approval needed");
   module.setHostTaskPort(new HostTaskPort(){public TaskState admit(Request r,Authority a){throw new AssertionError("inspection must not admit");}public TaskState inspect(Request r,Authority a)throws Exception{a.check();return latest;}public TaskState cancel(Request r,Authority a){throw new AssertionError("inspection must not cancel");}});
   JSONObject inspected=module.handle("task.inspect",new JSONObject().put("requestId",request));
   assertTrue(inspected.getBoolean("found"));assertEquals(run,inspected.getString("hostRunId"));assertEquals(2,inspected.getInt("hostCoordinatorEpoch"));assertEquals(5,inspected.getLong("hostReceiptRevision"));assertEquals("not_queued",inspected.getString("deliveryStatus"));assertEquals(4,core.snapshot().getJSONArray("outbox").length());
  }finally{module.close();}
 }
}
