package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.teams.TeamsModule;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real TaskStore/FeatureHost queue, with transport targets and actual native team generation checks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RelatedTaskCancellationTest {
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final FeatureHost host;final ThreadPoolExecutor workers;
        final List<String> stopped=new CopyOnWriteArrayList<>();final List<JSONObject> events=new CopyOnWriteArrayList<>();
        Fixture()throws Exception{this(true);}
        Fixture(boolean recordTransport)throws Exception{
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);
            if(recordTransport)host.register(new EngineModule(){public String id(){return "teams";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{assertEquals("cancel",command);String run=input.getString("runId");stopped.add(run);return new JSONObject().put("id",run).put("status","cancelled");}});
            var field=FeatureHost.class.getDeclaredField("workers");field.setAccessible(true);workers=(ThreadPoolExecutor)field.get(host);
        }
        JSONObject parent()throws Exception{return tasks.startAttempt(tasks.createCapabilityTask("original parent","").getString("id"),"capabilities",8);}
        String child(JSONObject parent)throws Exception{String id=UUID.randomUUID().toString();tasks.beginRelatedExternal(parent.getString("id"),parent.getString("runId"),"teams","start",id,new JSONObject().put("goal","original child"));return id;}
        FeatureHost.RelatedTaskCancellation capture(JSONObject parent)throws Exception{return host.captureRelatedTaskCancellation(parent.getString("id"),parent.getString("runId"),parent.getInt("coordinatorEpoch"));}
        JSONObject successor(JSONObject parent)throws Exception{tasks.endAttempt(parent.getString("id"),parent.getString("runId"),"cancelled","fixture original settled",new JSONObject());return tasks.startAttempt(parent.getString("id"),"capabilities",8);}
        TeamsModule restoredTeam(String run)throws Exception{
            var app=RuntimeEnvironment.getApplication();
            JSONObject member=new JSONObject().put("id","member-1").put("name","Original member").put("role","Review original work").put("status","awaiting_user");
            JSONObject original=new JSONObject().put("id",run).put("goal","Retain the original generation").put("mode","collaborate").put("rounds",1).put("generation",7).put("revision",1).put("status","awaiting_user").put("phase","awaiting_user").put("members",new JSONArray().put(member));
            JsonFiles.write(new File(app.getFilesDir(),"teams/runs.json"),new JSONObject().put("runs",new JSONArray().put(original)));
            // Real persisted native run; get/pause/cancel do not need a provider or launch model work.
            TeamsModule team=new TeamsModule(new FeatureContext(app,null,null,events::add));host.register(team);return team;
        }
        void write(JSONObject task)throws Exception{Method write=TaskStore.class.getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(tasks,task);}
        void drain()throws Exception{var controls=new java.util.concurrent.CompletableFuture<Void>();host.nativeOwnerCleanup(()->controls.complete(null));controls.get(5,TimeUnit.SECONDS);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while((workers.getActiveCount()!=0||!workers.getQueue().isEmpty())&&System.nanoTime()<end)Thread.sleep(5);assertEquals(0,workers.getActiveCount());assertTrue(workers.getQueue().isEmpty());}
        public void close()throws Exception{host.close();var db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();}
    }
    @Test public void laterSameRunChildIsNotAddedToCapturedCancellation()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();String original=f.child(parent);var captured=f.capture(parent);String later=f.child(parent);
            f.host.cancelRelatedTasks(captured);f.drain();assertEquals(List.of(original),f.stopped);assertTrue(f.tasks.get(original,false).getBoolean("cancelRequested"));assertFalse(f.tasks.get(later,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void delayedOriginalParentTicketDoesNotChooseSuccessorChildren()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();String original=f.child(parent);var captured=f.capture(parent);JSONObject successor=f.successor(parent);String later=f.child(successor);
            f.host.cancelRelatedTasks(captured);f.drain();assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(original,false).optBoolean("cancelRequested"));assertFalse(f.tasks.get(later,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void queuedChildCancellationChecksOriginalParentAgain()throws Exception{
        try(Fixture f=new Fixture()){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            try{
                f.host.nativeOwnerCleanup(()->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
                assertTrue(entered.await(3,TimeUnit.SECONDS));JSONObject parent=f.parent();String original=f.child(parent);var captured=f.capture(parent);f.host.cancelRelatedTasks(captured);
                JSONObject successor=f.successor(parent);String later=f.child(successor);release.countDown();f.drain();assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(original,false).optBoolean("cancelRequested"));assertFalse(f.tasks.get(later,false).optBoolean("cancelRequested"));
            }finally{release.countDown();}
        }
    }
    @Test public void staleParentRunOrEpochCannotCaptureCurrentChildren()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent(),successor=f.successor(parent);String later=f.child(successor);
            assertNull(f.capture(parent));assertNull(f.host.captureRelatedTaskCancellation(successor.getString("id"),successor.getString("runId"),parent.getInt("coordinatorEpoch")));
            f.host.cancelRelatedTasks(null);f.drain();assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(later,false).optBoolean("cancelRequested"));assertFalse(f.events.isEmpty());
        }
    }
    @Test public void changedChildRunOrEpochIsNotCancelled()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();String first=f.child(parent),second=f.child(parent);var captured=f.capture(parent);
            JSONObject firstRow=f.tasks.get(first,false),secondRow=f.tasks.get(second,false);f.write(firstRow.put("runId",UUID.randomUUID().toString()));f.write(secondRow.put("coordinatorEpoch",secondRow.optInt("coordinatorEpoch")+1));
            f.host.cancelRelatedTasks(captured);f.drain();assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(first,false).optBoolean("cancelRequested"));assertFalse(f.tasks.get(second,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void captureNeverReplacesLinkedChildRunWithItsCurrentRun()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();String child=f.child(parent);f.write(f.tasks.get(child,false).put("runId",UUID.randomUUID().toString()));
            f.host.cancelRelatedTasks(f.capture(parent));f.drain();assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(child,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void descendantsUseCapturedTreeRatherThanLaterRelatedTasks()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();String child=f.child(parent);JSONObject childRow=f.tasks.get(child,false);String grandchild=f.child(childRow);var captured=f.capture(parent);String later=f.child(childRow);
            f.host.cancelRelatedTasks(captured);f.drain();assertEquals(2,f.stopped.size());assertTrue(f.stopped.containsAll(List.of(child,grandchild)));assertFalse(f.tasks.get(later,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void anotherHostCannotDispatchCapturedTicket()throws Exception{
        try(Fixture f=new Fixture();FeatureHost other=new FeatureHost(event->{},f.tasks,()->false)){
            JSONObject parent=f.parent();String child=f.child(parent);var captured=f.capture(parent);
            try{other.cancelRelatedTasks(captured);fail("A different host must not consume the ticket");}catch(SecurityException expected){}
            f.drain();assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(child,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void alreadyCancelledParentCannotAcquireAFreshChildTicket()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();String child=f.child(parent);
            f.tasks.requestCancel(parent.getString("id"),parent.getString("runId"));
            assertNull(f.capture(parent));f.host.cancelRelatedTasks((FeatureHost.RelatedTaskCancellation)null);f.drain();
            assertTrue(f.stopped.isEmpty());assertFalse(f.tasks.get(child,false).optBoolean("cancelRequested"));assertFalse(f.events.isEmpty());
        }
    }
    @Test public void captureUnderOuterTaskStoreMonitorFailsFast()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject parent=f.parent();f.child(parent);
            synchronized(f.tasks){
                try{f.capture(parent);fail("Native team capture must not inherit an outer TaskStore lock");}
                catch(IllegalStateException expected){assertEquals("related_task_capture_requires_unlocked_tasks",expected.getMessage());}
            }
            assertTrue("The programming error must not become an unknown-success ticket",f.events.isEmpty());
        }
    }
    @Test public void parentCancelCallbackCannotRetargetCapturedNativeTeamGeneration()throws Exception{
        try(Fixture f=new Fixture(false)){
            JSONObject parent=f.parent();String child=f.child(parent);TeamsModule team=f.restoredTeam(child);
            JSONObject before=f.tasks.get(child,false),nativeBefore=team.handle("get",new JSONObject().put("runId",child));
            var captured=f.capture(parent);assertNotNull(captured);
            RunControl parentControl=new RunControl();parentControl.bindHostRun(parent.getString("id"),parent.getString("runId"),parent.getInt("coordinatorEpoch"));
            AtomicReference<JSONObject> paused=new AtomicReference<>();AtomicReference<Exception> failure=new AtomicReference<>();
            parentControl.onCancel(()->{try{paused.set(team.handle("pause",new JSONObject().put("runId",child)));}catch(Exception unavailable){failure.set(unavailable);}});
            parentControl.cancel();assertNull(failure.get());assertNotNull(paused.get());assertTrue(paused.get().getInt("generation")>nativeBefore.getInt("generation"));
            JSONObject unchanged=f.tasks.get(child,false);assertEquals(before.getString("runId"),unchanged.getString("runId"));assertEquals(before.getInt("coordinatorEpoch"),unchanged.getInt("coordinatorEpoch"));
            f.host.cancelRelatedTasks(captured);f.drain();
            JSONObject retained=team.handle("get",new JSONObject().put("runId",child));assertEquals("paused",retained.getString("status"));assertEquals(paused.get().getInt("generation"),retained.getInt("generation"));
            assertFalse(f.tasks.get(child,false).optBoolean("cancelRequested"));assertTrue(f.stopped.isEmpty());
        }
    }
    @Test public void capturedNativeTeamGenerationStillCancelsWhenCurrent()throws Exception{
        try(Fixture f=new Fixture(false)){
            JSONObject parent=f.parent();String child=f.child(parent);TeamsModule team=f.restoredTeam(child);
            JSONObject before=team.handle("get",new JSONObject().put("runId",child));var captured=f.capture(parent);assertNotNull(captured);
            f.host.cancelRelatedTasks(captured);f.drain();
            JSONObject cancelled=team.handle("get",new JSONObject().put("runId",child));assertEquals("cancelled",cancelled.getString("status"));assertEquals(before.getInt("generation")+1,cancelled.getInt("generation"));
            assertTrue(f.tasks.get(child,false).getBoolean("cancelRequested"));assertTrue(f.stopped.isEmpty());
        }
    }
}
