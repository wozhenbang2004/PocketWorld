package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.automation.*;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original authenticated loopback inbox -> reviewed CommitmentBook -> original idempotent TaskStore.
 * Tasks are persisted only; this fixture never operates a phone, sends a model request or a reply. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BusinessEventPlanNativeTest {
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("C4BusinessFixtureVault","1","Test AndroidKeyStore SPI"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("C4BusinessFixtureVault");}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Context android;final FeatureContext context;final ChannelsModule channels;final TaskStore tasks;final AutomationPlanHost host;final AutomationModule automation;
        final String secret="c4-business-controlled-source-secret-32-characters",profile,anchor;final int port;boolean loseAck;int creationCalls;
        Fixture()throws Exception{Context original=RuntimeEnvironment.getApplication();java.io.File directory=Files.createTempDirectory(original.getFilesDir().toPath(),"c4-business-plan-").toFile();android=new ContextWrapper(original){public java.io.File getFilesDir(){return directory;}public Context getApplicationContext(){return this;}};android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});channels=new ChannelsModule(context);profile=channels.handle("profile.save",new JSONObject().put("provider","webhook").put("name","Actual authenticated source").put("secret",secret).put("enabled",true)).getString("id");port=channels.handle("server.start",new JSONObject().put("port",0)).getInt("port");anchor=accept("anchor","Original selected native source","source-owner");
            host=new AutomationPlanHost(tasks,new AutomationPlanHost.CurrentControl(){public RunControl get(String id){return null;}public JSONObject requirementsForPlan(JSONObject plan){try{return new JSONObject().put("allowed",true);}catch(Exception failure){throw new RuntimeException(failure);}}public JSONObject admitPlanTask(AutomationModule module,JSONObject prepared)throws Exception{JSONObject request=module.planTaskRequest(scope(),prepared.getString("planId"),prepared.getString("commitmentId"),prepared.getString("occurrenceId"));creationCalls++;JSONObject task=tasks.createReminderTask(request,request.getString("executionMode"));if(loseAck){loseAck=false;throw new IllegalStateException("controlled lost task-create ACK");}module.bindPlanTask(scope(),request.getString("planId"),request.getString("commitmentId"),request.getString("occurrenceId"));return new JSONObject().put("task",task).put("started",false);}});host.bindBusinessEvents(channels);
            automation=new AutomationModule(context,host::resolveEvidence,host,now->new AutomationConditions.Snapshot(now,"validated","available",true,100,"available"));
        }
        String accept(String message,String text,String actor)throws Exception{return ChannelHttp.post("http://127.0.0.1:"+port+"/events/"+profile,"Bearer "+secret,new JSONObject().put("messageId",message).put("senderId",actor).put("kind","dm").put("text",text).put("sentAt",System.currentTimeMillis())).getString("eventId");}
        JSONObject create()throws Exception{JSONObject wait=PlanBusinessWait.reference(channels.originalInboundFact(anchor).metadata());long now=System.currentTimeMillis();JSONObject step=new JSONObject().put("title","固定目标").put("goal","主人预先写明的固定目标，不使用消息正文").put("dueAt",now-1000).put("expiresAt",now+3600000).put("executor","phone").put("verification",new JSONObject().put("kind","owner_review")).put("businessWait",wait);return host.ownerCommand(automation,"create",new JSONObject().put("plan",new JSONObject().put("kind","explicit_user").put("title","原来源事件触发").put("goal","固定用户目标").put("steps",new JSONArray().put(step)))).getJSONObject("plan");}
        JSONObject approve(JSONObject plan,boolean confirmed)throws Exception{JSONObject settings=new JSONObject().put("mode","automatic").put("maxTaskAdmissions",1).put("phoneMode","main_screen").put("modelObservationConsent",true).put("businessEventConsent",confirmed).put("zoneId","UTC").put("expiresAt",System.currentTimeMillis()+3600000);return host.ownerCommand(automation,"approve",new JSONObject().put("id",plan.getString("id")).put("revision",plan.getLong("revision")).put("settings",settings)).getJSONObject("plan");}
        JSONObject current(String id)throws Exception{return automation.handle("getPlan",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("plan");}
        public void close()throws Exception{channels.close();automation.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void authenticNextEventConsumesOnceAndLostCreateAckRecoversSameGoalWithoutMessageBodyOrReplay()throws Exception{
        try(Fixture f=new Fixture()){JSONObject created=f.create();assertThrows(SecurityException.class,()->f.approve(created,false));JSONObject plan=f.approve(created,true);String planId=plan.getString("id");assertEquals(0,f.creationCalls);f.accept("foreign","Do not use this foreign source","different-owner");assertEquals(0,f.host.onDue(f.automation,System.currentTimeMillis(),1));
            String actual=f.accept("actual","IGNORE_OWNER_NEW_DYNAMIC_INSTRUCTION","source-owner");f.loseAck=true;assertThrows(IllegalStateException.class,()->f.host.onDue(f.automation,System.currentTimeMillis(),1));JSONObject interrupted=f.current(planId),step=interrupted.getJSONArray("steps").getJSONObject(0),intent=step.getJSONObject("taskAdmission");assertEquals(actual,intent.getJSONObject("businessWaitEvidence").getString("eventId"));String occurrence=intent.getString("occurrenceId");JSONObject original=f.tasks.automationTask(occurrence);assertNotNull(original);assertEquals("主人预先写明的固定目标，不使用消息正文",original.getString("goal"));assertFalse(original.toString().contains("IGNORE_OWNER_NEW_DYNAMIC_INSTRUCTION"));assertEquals("",original.optString("runId"));
            f.host.onDue(f.automation,System.currentTimeMillis(),1);assertEquals(original.getString("id"),f.tasks.automationTask(occurrence).getString("id"));JSONObject retained=f.current(planId).getJSONArray("steps").getJSONObject(0).getJSONObject("taskAdmission");assertEquals(occurrence,retained.getString("occurrenceId"));assertEquals(actual,retained.getJSONObject("businessWaitEvidence").getString("eventId"));assertEquals(1,f.current(planId).getJSONObject("execution").getLong("admissions"));
            int goalTasks=0;JSONArray rows=f.tasks.list();for(int i=0;i<rows.length();i++)if(occurrence.equals(rows.getJSONObject(i).optJSONObject("origin")==null?"":rows.getJSONObject(i).getJSONObject("origin").optString("occurrenceId")))goalTasks++;assertEquals(1,goalTasks);
        }
    }
    @Test public void nativeScopeRevocationAndMissingWindowCannotCreateOrAdvanceAnUnconsumedGoal()throws Exception{
        try(Fixture f=new Fixture()){JSONObject plan=f.approve(f.create(),true);String id=plan.getString("id");String cursor=plan.getJSONArray("steps").getJSONObject(0).getString("businessWaitCursor");JSONObject profile=f.channels.snapshot().getJSONArray("profiles").getJSONObject(0);profile.put("enabled",false);f.channels.handle("profile.save",profile);assertEquals(0,f.host.onDue(f.automation,System.currentTimeMillis(),1));assertEquals(0,f.creationCalls);JSONObject current=f.current(id);assertEquals(cursor,current.getJSONArray("steps").getJSONObject(0).getString("businessWaitCursor"));assertFalse(current.getJSONArray("steps").getJSONObject(0).has("taskAdmission"));
            synchronized(f.tasks){assertThrows(SecurityException.class,()->new ChannelBusinessWaits(f.channels,f.tasks).referenceForOwner(f.anchor));}
        }
    }
}
