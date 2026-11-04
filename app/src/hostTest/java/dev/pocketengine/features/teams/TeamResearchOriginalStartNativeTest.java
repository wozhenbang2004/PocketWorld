package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual approved HostToolSupervisor -> FeatureHost -> Module -> Coordinator/SQLite start.
 * The native research adapter is a controlled physical worker, not a model/remote execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamResearchOriginalStartNativeTest {
    private static Object field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue("original native worker did not reach its bounded checkpoint",ready.getAsBoolean());}
    private static final class Child {
        final RunControl control;final TaskStore.TeamResearchBinding binding;final CountDownLatch release=new CountDownLatch(1);
        Child(RunControl control,TaskStore.TeamResearchBinding binding){this.control=control;this.binding=binding;}
    }
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final FeatureHost features;final TeamsModule teams;final TeamCoordinator coordinator;final WorkspaceModule workspace;final SQLiteDatabase db;
        final RunControl parent=new RunControl();final HostToolSupervisor port;final RunJournal journal;final String parentTask,parentRun,teamId;final int epoch;final JSONObject args;
        final BlockingQueue<Child> entered=new LinkedBlockingQueue<>();final java.util.List<Child> children=new CopyOnWriteArrayList<>();
        final CountDownLatch allowStart=new CountDownLatch(1),startHeld=new CountDownLatch(1);String operation;
        Fixture(boolean holdStart)throws Exception {
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.getSharedPreferences("private_settings",0).edit().clear().commit();
            tasks=new TaskStore(app);db=(SQLiteDatabase)field(tasks,"db");var settings=new SettingsStore(app);settings.save(new JSONObject().put("baseUrl","https://example.invalid/v1").put("apiKey","").put("model","controlled-native-research").put("sendScreenshots",false));
            features=new FeatureHost(event->{},tasks,()->false);var context=new FeatureContext(app,settings,new SecretVault(app),features::recordEvent);workspace=new WorkspaceModule(context);features.register(workspace);teams=new TeamsModule(context);features.register(teams);coordinator=(TeamCoordinator)field(teams,"coordinator");
            var materials=new TeamMemberEvidenceHost(features,tasks,ref->{throw new AssertionError("no source body");},ref->{throw new AssertionError("no source access");});teams.setResearchHost(new TeamResearchHost(tasks,materials));
            coordinator.setResearchFactory((call,model,control,progress)->{
                var binding=tasks.beginTeamResearch(call,control);Child child=new Child(control,binding);children.add(child);entered.add(child);
                try{assertTrue("release controlled physical resource",child.release.await(15,TimeUnit.SECONDS));tasks.interruptTeamResearch(binding,control.isCancelled()?"cancelled":"interrupted");return tasks.teamResearchResult(binding);}
                finally{child.release.countDown();}
            });
            if(holdStart)((ExecutorService)field(coordinator,"coordinator")).execute(()->{startHeld.countDown();try{allowStart.await(15,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}});else allowStart.countDown();
            teamId=teams.handle("save",new JSONObject().put("name","原代取消研究").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","研究者").put("role","只读研究")))).getString("id");
            JSONObject owner=tasks.startAttempt(tasks.createCapabilityTask("原主人研究启动","").getString("id"),"capabilities",16);parentTask=owner.getString("id");parentRun=owner.getString("runId");epoch=owner.getInt("coordinatorEpoch");parent.bindHostRun(parentTask,parentRun,epoch);
            port=new HostToolSupervisor(features,tasks,parentTask,parentRun,epoch,true);JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(parentTask,parentRun,names);journal=tasks.journal(parentTask,parentRun,epoch,parent);
            args=new JSONObject().put("teamId",teamId).put("goal","只请求停止原物理研究代次，不结算未知效果").put("rounds",1);
        }
        JSONObject start()throws Exception {
            JSONObject approval=tasks.requireCapabilityApproval(parentTask,parentRun,"teams_start",args,new JSONObject().put("feature","teams").put("command","start").put("teamDefinition",features.teamDefinition(teamId)));assertFalse(approval.getBoolean("approved"));tasks.decideCapabilityApproval(parentTask,approval.getString("approvalId"),true);
            port.validate("teams_start",args);operation=journal.beginOperation("teams_start",args);JSONObject started=port.execute("teams_start",new JSONObject(args.toString()).put("_hostOperationId",operation),parent);journal.finishOperation(operation,started);assertEquals(operation,started.getString("id"));assertTrue(tasks.get(operation,false).has("teamResearchParentScope"));return started;
        }
        Child child()throws Exception{Child child=entered.poll(8,TimeUnit.SECONDS);assertNotNull("real Coordinator child reached native SQL binding",child);assertTrue(child.control.hasHostRun());return child;}
        void drainCleanup()throws Exception{CountDownLatch drained=new CountDownLatch(1);features.nativeOwnerCleanup(drained::countDown);assertTrue(drained.await(8,TimeUnit.SECONDS));}
        JSONObject state()throws Exception{return coordinator.get(operation);}
        boolean released(){try{return coordinator.parentExecutorState(operation).getBoolean("resourcesReleased");}catch(Exception failure){throw new IllegalStateException(failure);}}
        public void close()throws Exception{allowStart.countDown();for(Child child:children)child.release.countDown();parent.cancel();drainCleanup();port.close();teams.close();for(String name:new String[]{"coordinator","members"})assertTrue(((ExecutorService)field(coordinator,name)).awaitTermination(8,TimeUnit.SECONDS));features.close();workspace.close();db.close();}
    }
    @Test public void actualApprovedStartRegistersOriginalTicketAndLateCallbackCannotCancelResumedGeneration()throws Exception {
        try(Fixture f=new Fixture(false)){
            JSONObject accepted=f.start();Child first=f.child();first.release.countDown();await(f::released);f.coordinator.pause(f.operation,null);
            f.teams.handle("resume",new JSONObject().put("runId",f.operation));Child replacement=f.child();assertNotSame(first.control,replacement.control);JSONObject before=f.state();assertTrue(before.getInt("generation")>accepted.getInt("generation"));String budget=f.tasks.teamBudgetSnapshot(f.operation).toString();
            CountDownLatch hold=new CountDownLatch(1),queued=new CountDownLatch(1);f.features.nativeOwnerCleanup(()->{queued.countDown();try{hold.await(8,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}});assertTrue(queued.await(8,TimeUnit.SECONDS));f.parent.cancel();assertFalse(replacement.control.isCancelled());hold.countDown();f.drainCleanup();
            assertFalse("old parent callback must not cancel the replacement child control",replacement.control.isCancelled());JSONObject after=f.state();assertEquals("late original parent cleanup leaves the replacement JSON intact",before.toString(),after.toString());assertEquals(budget,f.tasks.teamBudgetSnapshot(f.operation).toString());assertFalse(f.released());
        }
    }
    @Test public void actualStartCapturesBeforeDynamicChildAndCancelRetainsPhysicalAndUnknownFacts()throws Exception {
        try(Fixture f=new Fixture(true)){
            assertTrue(f.startHeld.await(8,TimeUnit.SECONDS));f.start();assertEquals(0,f.children.size());f.allowStart.countDown();Child child=f.child();
            String unknown=UUID.randomUUID().toString();f.db.execSQL("INSERT INTO operations(operation_id,task_id,run_id,tool,arguments,state,created_at) VALUES(?,?,?,?,?,'unknown',?)",new Object[]{unknown,child.binding.taskId(),child.binding.runId(),"team_send_message","{}",System.currentTimeMillis()});String budget=f.tasks.teamBudgetSnapshot(f.operation).toString();
            f.parent.cancel();f.drainCleanup();assertTrue("child created after the native start ticket follows its original owner",child.control.isCancelled());assertFalse("requested cancel is not physical release",f.released());assertTrue(f.tasks.hasUnresolved(child.binding.taskId()));assertEquals(budget,f.tasks.teamBudgetSnapshot(f.operation).toString());
            child.release.countDown();await(f::released);JSONObject persisted=f.tasks.get(child.binding.taskId(),false);assertEquals("reconciling",persisted.getString("status"));assertTrue(f.tasks.hasUnresolved(child.binding.taskId()));assertEquals(child.binding.runId(),persisted.getString("runId"));assertEquals(budget,f.tasks.teamBudgetSnapshot(f.operation).toString());
            try(var row=f.db.rawQuery("SELECT state FROM operations WHERE task_id=? AND operation_id=?",new String[]{child.binding.taskId(),unknown})){assertTrue(row.moveToFirst());assertEquals("unknown",row.getString(0));}
        }
    }
}
