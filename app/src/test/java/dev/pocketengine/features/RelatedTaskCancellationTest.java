package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real TaskStore/FeatureHost queue; the team transport records targets, not physical workers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RelatedTaskCancellationTest {
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final FeatureHost host;final ThreadPoolExecutor workers;
        final List<String> stopped=new CopyOnWriteArrayList<>();final List<JSONObject> events=new CopyOnWriteArrayList<>();
        Fixture()throws Exception{
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);
            host.register(new EngineModule(){public String id(){return "teams";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{assertEquals("cancel",command);String run=input.getString("runId");stopped.add(run);return new JSONObject().put("id",run).put("status","cancelled");}});
            var field=FeatureHost.class.getDeclaredField("workers");field.setAccessible(true);workers=(ThreadPoolExecutor)field.get(host);
        }
        JSONObject parent()throws Exception{return tasks.startAttempt(tasks.createCapabilityTask("original parent","").getString("id"),"capabilities",8);}
        String child(JSONObject parent)throws Exception{String id=UUID.randomUUID().toString();tasks.beginRelatedExternal(parent.getString("id"),parent.getString("runId"),"teams","start",id,new JSONObject().put("goal","original child"));return id;}
        FeatureHost.RelatedTaskCancellation capture(JSONObject parent)throws Exception{return host.captureRelatedTaskCancellation(parent.getString("id"),parent.getString("runId"),parent.getInt("coordinatorEpoch"));}
        JSONObject successor(JSONObject parent)throws Exception{tasks.endAttempt(parent.getString("id"),parent.getString("runId"),"cancelled","fixture original settled",new JSONObject());return tasks.startAttempt(parent.getString("id"),"capabilities",8);}
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
}
