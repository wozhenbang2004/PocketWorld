package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.devices.DevicePhoneGoalReadiness;
import dev.pocketengine.features.devices.DeviceWorkspaceNativeOwner;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real Module/Page/Coordinator/TaskStore/SQLite path. Native candidate facts and the final
 * physical-service callback are explicit fixtures. No model, screen, ADB or paired-network claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamPhoneOriginalHostJourneyTest {
    private static Object field(Object owner,String name)throws Exception {var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static final class Secrets implements SettingsStore.ApiSecrets {
        private final java.util.Map<String,String> values=new java.util.HashMap<>();public String get(String key){return values.getOrDefault(key,"");}public void put(String key,String value){values.put(key,value);}
    }
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase db;final TeamsModule teams;final TeamCoordinator coordinator;final DeviceWorkspaceNativeOwner devices;final EngineApplication app;
        final List<JSONObject> entered=new CopyOnWriteArrayList<>();final CountDownLatch both=new CountDownLatch(2);final AtomicInteger optionReads=new AtomicInteger();
        final CountDownLatch confirmReading=new CountDownLatch(1),releaseOptions=new CountDownLatch(1);volatile boolean holdConfirm;
        final String teamId;
        Fixture()throws Exception {
            Application context=RuntimeEnvironment.getApplication();context.deleteDatabase("pocket-world.db");tasks=new TaskStore(context);db=(SQLiteDatabase)field(tasks,"db");
            // Attached original application object; onCreate/real services are not started by this fixture.
            app=Robolectric.buildApplication(EngineApplication.class).get();app.tasks=tasks;
            var constructor=SettingsStore.class.getDeclaredConstructor(android.content.SharedPreferences.class,SettingsStore.ApiSecrets.class);constructor.setAccessible(true);
            app.settings=constructor.newInstance(context.getSharedPreferences("phone-team-native-model",0),new Secrets());
            var catalog=new dev.pocketengine.runtime.models.ModelConnectionService(app.settings,Files.createTempDirectory("phone-team-model").resolve("catalog"),java.time.Clock.systemUTC());catalog.install();catalog.connections();
            JSONObject connection=app.settings.saveApiConnection("",0,"Native review fixture",new JSONObject().put("baseUrl","https://example.invalid/v1").put("model","native-phone-test").put("apiKey","synthetic-not-a-real-key"));app.settings.selectApiConnection(connection.getString("id"),1);
            FeatureHost features=new FeatureHost(event->{},tasks,()->false);app.features=features;FeatureContext hostContext=new FeatureContext(context,app.settings,new SecretVault(context),features::recordEvent);
            features.register(new WorkspaceModule(hostContext));teams=new TeamsModule(hostContext);features.register(teams);coordinator=(TeamCoordinator)field(teams,"coordinator");
            devices=new DeviceWorkspaceNativeOwner(app,tasks.deviceTrust(),Runnable::run);
            TeamPhoneHost host=new TeamPhoneHost(app,devices,()->{
                if(optionReads.incrementAndGet()>1&&holdConfirm){confirmReading.countDown();if(!releaseOptions.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture option wait expired");}
                JSONObject readiness=new JSONObject().put("backgroundPreparation",new JSONObject().put("ready",true)).put("backgroundExecution",new JSONObject().put("ready",false));
                JSONObject helper=new JSONObject().put("helperConnected",true).put("sessionActive",false).put("parallelPhone",new JSONObject().put("helperSupportsParallel",true));
                if(!DevicePhoneGoalReadiness.canPrepareBackground(readiness,helper))return new JSONArray();
                JSONObject resource=new JSONObject().put("screenMode","background").put("androidUserId",0).put("packageNames",new JSONArray().put("dev.fixture.target")).put("allowWholeDisplayInput",false).put("allowWholeDisplayCapture",false);
                return new JSONArray().put(new JSONObject().put("optionId","goal_background_app_fixture").put("label","Explicit native candidate fixture").put("resources",new JSONArray().put(resource)).put("toolNames",new JSONArray().put("phone_observe").put("phone_launch_app").put("phone_click_node")).put("expiresAtMax",System.currentTimeMillis()+300000));
            },new TeamPhoneHost.OriginalPhoneRuns(){
                public JSONObject start(TeamPhoneHost.Admission admission)throws Exception {
                    admission.checkCurrent();JSONObject child=tasks.get(admission.taskId(),false);entered.add(child);both.countDown();
                    assertTrue("two original children must reach the original physical boundary concurrently",both.await(5,TimeUnit.SECONDS));
                    tasks.endAttempt(child.getString("id"),child.getString("runId"),"needs_confirmation","Physical fixture stops here; no phone effect is asserted",new JSONObject().put("physicalFixture",true));return child;
                }
                public boolean active(String task,RunControl original){return false;}
                public void pause(String task,String run,int epoch){throw new AssertionError("not this journey");}
                public void resume(String task,String run,int epoch){throw new AssertionError("not this journey");}
                public void stop(String task,String run,int epoch){throw new AssertionError("no physical fixture resource was created");}
            });teams.setPhoneHost(host);
            JSONArray members=new JSONArray();for(String member:new String[]{"one","two"})members.put(new JSONObject().put("id",member).put("name",member).put("role","phone work").put("runner","phone_agent").put("assignment","执行该成员明确分工 "+member).put("dependsOn",new JSONArray()).put("device",new JSONObject().put("kind","local_phone").put("screenMode","background")));
            teamId=teams.handle("save",new JSONObject().put("name","Native phone team").put("mode","collaborate").put("members",members)).getString("id");
        }
        TeamPhoneHost.Page page()throws Exception{return teams.newPhonePage(teamId,"本次主人要求：分别核对本机手机目标",1,"");}
        JSONObject selection()throws Exception {JSONArray members=new JSONArray();for(String id:new String[]{"one","two"})members.put(new JSONObject().put("memberId",id).put("optionId","goal_background_app_fixture").put("toolBudget",5).put("modelBudget",2).put("expiresAt",System.currentTimeMillis()+60000));return new JSONObject().put("members",members);}
        void waitIdle(String run)throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do{synchronized(coordinator){Object state=((java.util.Map<?,?>)field(coordinator,"runs")).get(run);if(state!=null&&((java.util.Map<?,?>)field(state,"children")).isEmpty())return;}Thread.sleep(10);}while(System.nanoTime()<until);
            throw new AssertionError("original member workers did not release their controls");
        }
        public void close()throws Exception {releaseOptions.countDown();coordinator.close();for(String name:new String[]{"coordinator","members"})assertTrue(((java.util.concurrent.ExecutorService)field(coordinator,name)).awaitTermination(5,TimeUnit.SECONDS));app.features.close();devices.close();db.close();}
    }
    @Test public void nativePageCreatesTwoOriginalPhoneChildrenAndDistinctLeasesWithoutPrecreatedDisplay()throws Exception {
        try(Fixture fixture=new Fixture();TeamPhoneHost.Page page=fixture.page()){
            JSONObject options=page.view();assertEquals(2,options.getJSONArray("members").length());assertEquals(1,options.getJSONArray("members").getJSONObject(0).getJSONArray("options").length());
            TeamPhoneHost.Review review=page.review(fixture.selection());JSONObject run=page.confirm(review);assertThrows(SecurityException.class,()->page.confirm(review));
            assertTrue(fixture.both.await(8,TimeUnit.SECONDS));assertEquals(2,fixture.entered.size());JSONObject a=fixture.entered.get(0),b=fixture.entered.get(1);
            assertNotEquals(a.getString("id"),b.getString("id"));assertNotEquals(a.getString("runId"),b.getString("runId"));assertNotEquals(a.getString("leaseResource"),b.getString("leaseResource"));
            for(JSONObject child:fixture.entered){assertEquals("phone",child.getString("kind"));assertEquals("background",child.getString("executionMode"));assertEquals("team_phone",child.getJSONObject("origin").getString("kind"));assertEquals(run.getString("id"),child.getJSONObject("origin").getString("teamRunId"));assertTrue(child.getString("goal").contains("本次主人要求"));assertTrue(child.getString("goal").contains("执行该成员明确分工"));assertTrue(child.getJSONObject("executionScope").getBoolean("toolsPinned"));}
            JSONObject parent=fixture.tasks.get(run.getString("id"),false);assertEquals(2,parent.getJSONObject("teamPhoneChildren").length());assertEquals(14,parent.getJSONObject("teamReservationPool").getJSONArray("reservations").length());
            fixture.waitIdle(run.getString("id"));
        }
    }
    @Test public void closingDuringNativeCandidateRecheckIsNonblockingAndCannotStartStaleReview()throws Exception {
        try(Fixture fixture=new Fixture();TeamPhoneHost.Page page=fixture.page()){
            TeamPhoneHost.Review review=page.review(fixture.selection());fixture.holdConfirm=true;AtomicReference<Throwable> failure=new AtomicReference<>();Thread worker=new Thread(()->{try{page.confirm(review);}catch(Throwable rejected){failure.set(rejected);}});worker.start();assertTrue(fixture.confirmReading.await(5,TimeUnit.SECONDS));
            long start=System.nanoTime();page.close();assertTrue("close must not wait for native I/O",System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(250));fixture.releaseOptions.countDown();worker.join(5000);assertFalse(worker.isAlive());assertTrue(failure.get() instanceof SecurityException);assertEquals(0,fixture.tasks.list().length());assertTrue(fixture.entered.isEmpty());
        }
    }
}
