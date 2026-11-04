package dev.pocketengine.browser;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Controlled cross-caller Host/Space scheduling, real SDK35/SQLite; not a single-panel/device repro. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=BrowserTaskHostAdmissionTest.NoUnrelatedStartup.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserOriginalStopTest {
    static Object read(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(BrowserTaskHost host,String name)throws Exception {return (Map<String,Object>)read(host,name);}
    static <T>T pump(BrowserTaskHostAdmissionTest.F f,Future<T> value)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!value.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}
        Shadows.shadowOf(Looper.getMainLooper()).idle();return value.get(1,TimeUnit.SECONDS);
    }
    static BrowserTaskHost.OwnerSnapshot snapshot(BrowserTaskHostAdmissionTest.F f)throws Exception {return pump(f,f.workers.submit(f.host::snapshotWithStopForOwner));}
    static void attach(BrowserTaskHostAdmissionTest.F f,String task,String run,int epoch,RunControl control)throws Exception {
        pump(f,f.workers.submit(()->f.host.wrapIfRequested(new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,""),control,null)));
    }
    static void admit(BrowserTaskHostAdmissionTest.F f)throws Exception {assertEquals("pending_original_turn",f.pump(f.consent()).getString("status"));}
    static RunControl successor(BrowserTaskHostAdmissionTest.F f)throws Exception {
        assertTrue("A's original executor has stopped",f.control.isCancelled());f.tasks.endAttempt(f.task,f.run,"cancelled","original A executor settled",new JSONObject());
        JSONObject next=f.tasks.create("另一个原任务","main_screen"),run=f.tasks.startAttempt(next.getString("id"),"main_screen",40);String task=next.getString("id");
        RunControl control=new RunControl();control.bindHostRun(task,run.getString("runId"),run.getInt("coordinatorEpoch"));f.tasks.status(task,"running","successor original executor");
        BrowserTaskHostAdmissionTest.field(f.app,"activeTaskId",task);BrowserTaskHostAdmissionTest.field(f.app,"activeControl",control);
        pump(f,f.workers.submit(()->f.host.allowExistingForOwner(task,f.profile,true)));attach(f,task,run.getString("runId"),run.getInt("coordinatorEpoch"),control);return control;
    }
    @Test public void delayedOriginalCleanupCannotCancelTheNextNativeTask()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);attach(f,f.task,f.run,f.epoch,f.control);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);
            assertEquals(f.task,displayed.state.getJSONObject("activeResearchTask").getString("id"));assertNotNull(displayed.stopTicket);
            f.host.cancelForOwner(displayed.stopTicket);assertTrue(f.control.isCancelled());Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(map(f.host,"active").isEmpty());
            RunControl next=successor(f);try{
                var identity=next.hostRunIdentity();JSONObject before=f.tasks.get(identity.taskId(),false);Object scope=map(f.host,"active").get(identity.taskId()),plan=map(f.host,"plans").get(identity.taskId());
                JSONObject cleanup=pump(f,f.workers.submit(()->f.host.endForOwner(displayed.stopTicket)));assertEquals(f.task,cleanup.getString("taskId"));
                assertFalse("old A callback must not stop B",next.isCancelled());assertSame(scope,map(f.host,"active").get(identity.taskId()));assertSame(plan,map(f.host,"plans").get(identity.taskId()));
                assertEquals(before.toString(),f.tasks.get(identity.taskId(),false).toString());assertEquals(identity.runId(),before.getString("runId"));assertEquals(identity.epoch(),before.getInt("coordinatorEpoch"));
            }finally{next.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();}
        }
    }
    @Test public void oldTicketRemainsHarmlessAcrossANewRunOfTheSameTaskAndRepeatedCleanup()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);attach(f,f.task,f.run,f.epoch,f.control);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);f.host.cancelForOwner(displayed.stopTicket);Shadows.shadowOf(Looper.getMainLooper()).idle();
            f.tasks.endAttempt(f.task,f.run,"cancelled","original A executor settled",new JSONObject());JSONObject run=f.tasks.startAttempt(f.task,"main_screen",40);RunControl next=new RunControl();next.bindHostRun(f.task,run.getString("runId"),run.getInt("coordinatorEpoch"));f.tasks.status(f.task,"running","same task, new original run");BrowserTaskHostAdmissionTest.field(f.app,"activeControl",next);
            try{
                pump(f,f.workers.submit(()->f.host.allowExistingForOwner(f.task,f.profile,true)));attach(f,f.task,run.getString("runId"),run.getInt("coordinatorEpoch"),next);JSONObject before=f.tasks.get(f.task,false);Object scope=map(f.host,"active").get(f.task),plan=map(f.host,"plans").get(f.task);
                assertNotEquals(f.run,run.getString("runId"));assertTrue(run.getInt("coordinatorEpoch")>f.epoch);
                for(int i=0;i<2;i++){JSONObject receipt=pump(f,f.workers.submit(()->f.host.endForOwner(displayed.stopTicket)));assertEquals(f.run,receipt.getString("runId"));assertEquals(f.epoch,receipt.getInt("epoch"));assertFalse(next.isCancelled());}
                assertThrows(SecurityException.class,()->f.host.cancelForOwner(displayed.stopTicket));assertSame(scope,map(f.host,"active").get(f.task));assertSame(plan,map(f.host,"plans").get(f.task));assertFalse((Boolean)read(plan,"cancelled"));assertEquals(before.toString(),f.tasks.get(f.task,false).toString());
            }finally{next.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();}
        }
    }
    @Test public void staleDisplayedActiveTicketCannotSynchronouslyStopItsReplacement()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);attach(f,f.task,f.run,f.epoch,f.control);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);
            f.control.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();RunControl next=successor(f);try{
                assertThrows(SecurityException.class,()->f.host.cancelForOwner(displayed.stopTicket));assertFalse(next.isCancelled());assertFalse((Boolean)read(map(f.host,"plans").get(next.hostRunIdentity().taskId()),"cancelled"));
            }finally{next.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();}
        }
    }
    @Test public void pendingTicketCannotStopANewPlanOrDifferentControlWithTheSameRunTuple()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);Object original=map(f.host,"plans").get(f.task);RunControl next=new RunControl();next.bindHostRun(f.task,f.run,f.epoch);
            Constructor<?> create=original.getClass().getDeclaredConstructor(String.class,JSONObject.class,RunControl.class);create.setAccessible(true);Object plan=create.newInstance(f.task,f.profile,next);map(f.host,"plans").put(f.task,plan);BrowserTaskHostAdmissionTest.field(f.app,"activeControl",next);
            assertThrows(SecurityException.class,()->f.host.cancelForOwner(displayed.stopTicket));assertFalse(next.isCancelled());assertFalse(f.control.isCancelled());assertFalse((Boolean)read(plan,"cancelled"));
        }
    }
    @Test public void samePlanWithAReplacementControlRejectsTheStalePendingTicket()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);Object original=map(f.host,"plans").get(f.task);RunControl next=new RunControl();next.bindHostRun(f.task,f.run,f.epoch);BrowserTaskHostAdmissionTest.field(f.app,"activeControl",next);
            assertThrows(SecurityException.class,()->f.host.cancelForOwner(displayed.stopTicket));assertFalse(next.isCancelled());assertFalse(f.control.isCancelled());assertFalse((Boolean)read(original,"cancelled"));
        }
    }
    @Test public void pendingCancellationKeepsOriginalUnknownEffectAndRunWithoutRelaunch()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            admit(f);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);String operation=java.util.UUID.randomUUID().toString();android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)read(f.tasks,"db");
            db.execSQL("INSERT INTO operations(operation_id,task_id,run_id,tool,arguments,state,receipt,created_at) VALUES(?,?,?,?,?,?,?,?)",new Object[]{operation,f.task,f.run,"phone_click","{}","unknown","{\"status\":\"unknown\",\"accepted\":null}",System.currentTimeMillis()});
            JSONObject before=f.tasks.get(f.task,false);assertTrue(f.tasks.hasUnresolved(f.task));f.host.cancelForOwner(displayed.stopTicket);assertTrue(f.control.isCancelled());
            JSONObject result=pump(f,f.workers.submit(()->f.host.endForOwner(displayed.stopTicket)));assertEquals("admission_cancelled",result.getString("status"));assertEquals(f.task,result.getString("taskId"));assertEquals(f.run,result.getString("runId"));assertEquals(f.epoch,result.getInt("epoch"));
            assertEquals(before.toString(),f.tasks.get(f.task,false).toString());assertTrue(f.tasks.hasUnresolved(f.task));assertEquals(1,f.tasks.list().length());
        }
    }
    @Test public void noDisplayedTicketAndCrossHostTicketNeverFallBackToAnyCurrentTask()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            assertNull(snapshot(f).stopTicket);admit(f);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);
            assertThrows(SecurityException.class,()->f.host.cancelForOwner(null));assertThrows(SecurityException.class,()->f.host.endForOwner(displayed.stopTicket));
            BrowserTaskHost other=BrowserTaskHostAdmissionTest.starting(f,(task,choice)->{},task->{});
            assertThrows(SecurityException.class,()->other.cancelForOwner(displayed.stopTicket));assertFalse(f.control.isCancelled());assertFalse((Boolean)read(map(f.host,"plans").get(f.task),"cancelled"));
        }
    }
    @Test public void unboundPendingStopNeverCancelsOrGrantsBrowserAuthorityToALaterOriginalRun()throws Exception {
        try(BrowserTaskHostAdmissionTest.F f=new BrowserTaskHostAdmissionTest.F()){
            JSONObject task=f.tasks.createCapabilityTask("尚未准入的原浏览请求","");String id=task.getString("id");Class<?> type=Class.forName(BrowserTaskHost.class.getName()+"$Plan");Constructor<?> create=type.getDeclaredConstructor(String.class,JSONObject.class,RunControl.class);create.setAccessible(true);Object plan=create.newInstance(id,f.profile,null);map(f.host,"plans").put(id,plan);BrowserTaskHost.OwnerSnapshot displayed=snapshot(f);assertNotNull(displayed.stopTicket);
            JSONObject run=f.tasks.startAttempt(id,"capabilities",40);RunControl next=new RunControl();next.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));f.tasks.status(id,"running","later unpinned original run");BrowserTaskHostAdmissionTest.field(f.app,"activeTaskId",id);BrowserTaskHostAdmissionTest.field(f.app,"activeControl",next);
            dev.pocketengine.core.CapabilityPort base=new dev.pocketengine.core.CapabilityPort(){public org.json.JSONArray tools(){return new org.json.JSONArray();}public boolean supports(String name){return name.equals("original_only");}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("no effect dispatch");}};
            try{
                f.host.cancelForOwner(displayed.stopTicket);assertTrue((Boolean)read(plan,"cancelled"));assertFalse(map(f.host,"plans").containsKey(id));assertFalse(next.isCancelled());assertFalse(f.control.isCancelled());assertEquals("admission_cancelled",f.host.endForOwner(displayed.stopTicket).getString("status"));
                var binding=new ModelCapabilityAdapters.Binding(id,run.getString("runId"),run.getInt("coordinatorEpoch"),next,true,"");dev.pocketengine.core.CapabilityPort port=pump(f,f.workers.submit(()->f.host.wrapIfRequested(binding,next,base)));port.prepareTurnSurface(next);assertTrue(port.supports("original_only"));assertFalse(port.supports("browser_task_open"));assertFalse(next.isCancelled());assertTrue(map(f.host,"active").isEmpty());
                // Exercise the cancelled-plan admission branch as if its old map value was already read.
                map(f.host,"plans").put(id,plan);assertSame(base,pump(f,f.workers.submit(()->f.host.wrapIfRequested(binding,next,base))));assertFalse(next.isCancelled());assertTrue(map(f.host,"plans").isEmpty());assertTrue(map(f.host,"active").isEmpty());
                assertEquals(run.getString("runId"),f.tasks.get(id,false).getString("runId"));assertEquals(run.getInt("coordinatorEpoch"),f.tasks.get(id,false).getInt("coordinatorEpoch"));
            }finally{next.cancel();}
        }
    }
}
