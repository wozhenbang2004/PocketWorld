package dev.pocketengine;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.devices.DeviceReceiverServiceResource;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowApplication;
import static org.junit.Assert.*;

/** Real Service/notification and original SQLite owner-work admission. Startup and
 * already-admitted executor ownership are fixture boundaries; no model, physical
 * phone, real receiver socket or promise of OS process survival is asserted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=OwnerWorkServiceLifecycleTest.NoUnrelatedStartup.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerWorkServiceLifecycleTest {
    @Implements(EngineApplication.class) public static class NoUnrelatedStartup extends ShadowApplication {@Implementation protected void onCreate(){}}
    private EngineApplication app;private TaskStore tasks;private SQLiteDatabase sql;private ConversationRepository repo;
    private ServiceController<PhoneRunService> controller;private PhoneRunService service;private DeviceReceiverServiceResource receiver;
    private ConcurrentHashMap<String,RunControl> controls;private int startId;
    private record Work(String task,String run,int epoch,RunControl control){}
    @Before @SuppressWarnings("unchecked") public void open()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);
        tasks=new TaskStore(app);app.tasks=tasks;sql=(SQLiteDatabase)get(tasks,"db");repo=tasks.conversationRepository();repo.initializeEmpty();
        dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);app.features=new FeatureHost(event->{});
        controls=(ConcurrentHashMap<String,RunControl>)get(app,"capabilityControls");
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS,android.Manifest.permission.FOREGROUND_SERVICE,android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE);
        createService();
    }
    @After public void close()throws Exception {
        if(receiver!=null)receiver.close();
        if(controller!=null){RunControl phone=(RunControl)get(service,"ownerControl");if(phone!=null)phone.cancel();set(service,"runningId",null);set(service,"runningRunId",null);set(app,"activeTaskId",null);set(app,"activeControl",null);destroyService();}
        if(controls!=null)controls.clear();if(app!=null){app.onTerminate();if(app.features!=null)app.features.close();}
        Shadows.shadowOf(Looper.getMainLooper()).idle();if(sql!=null&&sql.isOpen())sql.close();
    }
    private void createService(){controller=Robolectric.buildService(PhoneRunService.class).create();service=controller.get();}
    private void destroyService(){controller.destroy();controller=null;}
    private static String id(){return UUID.randomUUID().toString();}
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private void call(String name,Class<?>[] types,Object... args)throws Exception{Method method=PhoneRunService.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(service,args);}
    private int held()throws Exception{return ((Map<?,?>)get(service,"ownerWorkLifetimes")).size();}
    private long count(String table){try(Cursor rows=sql.rawQuery("SELECT COUNT(*) FROM "+table,null)){rows.moveToFirst();return rows.getLong(0);}}
    private Notification notice(){return Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification(102);}
    private Intent intent(Work work){return new Intent(app,PhoneRunService.class).setAction(PhoneRunService.OWNER_WORK_LIFETIME).putExtra("taskId",work.task).putExtra("runId",work.run).putExtra("epoch",work.epoch);}
    private void retain(Work work){assertEquals(PhoneRunService.START_NOT_STICKY,service.onStartCommand(intent(work),0,++startId));}
    private void event(JSONObject task)throws Exception{app.publish(new JSONObject().put("type","task").put("task",task));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void event(Work work)throws Exception{event(tasks.getForUi(work.task,false));}
    private Work work(String goal)throws Exception {
        String conversation=id(),message=id(),client=id();repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","私人管家").put("createdAt",1L).put("updatedAt",1L));return null;});
        String source=tasks.createCapabilityTask(goal,conversation).getString("id");tasks.initializeExecutionScope(source,32);JSONObject attempt=tasks.startAttempt(source,"capabilities",32);
        String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(source,run,epoch);
        tasks.authorizeTools(source,run,new JSONArray().put("owner_work_start").put("fixture_read"));tasks.openOwnerInputMailbox(source,run,epoch,conversation,original);
        JSONObject ack=new JSONObject().put("status","accepted").put("taskId",source).put("runId",run).put("epoch",epoch).put("conversationId",conversation).put("messageId",message).put("clientMessageId",client);
        JSONObject row=new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content",goal).put("at",System.currentTimeMillis()).put("conversationId",conversation).put("clientMessageId",client).put("requestFingerprint","a".repeat(64)).put("inputMode","queue").put("attachments",new JSONArray()).put("submission",ack);
        repo.transaction(()->{repo.saveSource(new SourceRef("message",message,1L,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false).put("clientMessageId",client).put("requestFingerprint","a".repeat(64)).put("submission",ack));repo.saveMessage(conversation,row);return null;});
        tasks.appendOwnerGoalInput(source,run,epoch,conversation,row,"queue",original);RunJournal journal=tasks.journal(source,run,epoch,original);
        journal.checkpoint(new JSONArray().put(new JSONObject().put("role","system").put("content","native butler fixture")).put(new JSONObject().put("role","user").put("content",goal).put("ownerMessageId",message).put("ownerMessageRevision",1L)),0,new JSONObject());
        JSONObject args=new JSONObject().put("goal",goal).put("toolLimit",8).put("modelCallLimit",10);String operation=journal.beginOperation("owner_work_start",args);
        String work=tasks.admitOwnerWork(source,run,epoch,operation,args,original).getString("taskId");tasks.endAttempt(source,run,"completed","已安排原工作",new JSONObject());
        RunControl worker=new RunControl();JSONObject claimed=tasks.claimOwnerWork(work,worker);controls.put(work,worker);tasks.status(work,"running","原执行器夹具已接手");
        return new Work(work,claimed.getString("runId"),claimed.getInt("epoch"),worker);
    }
    private void finish(Work work,String status)throws Exception{tasks.endAttempt(work.task,work.run,status,"原执行器已有回执",new JSONObject());controls.remove(work.task,work.control);event(work);}

    @Test public void repeatedExactStartHoldsVisibleOriginalRunWithoutCreatingOrRestartingExecution()throws Exception {
        Work work=work("核对原资料并整理结果");long runs=count("runs"),operations=count("operations"),taskCount=count("tasks");String before=tasks.get(work.task,false).toString();
        retain(work);retain(work);assertEquals(PhoneRunService.START_NOT_STICKY,service.onStartCommand(null,0,++startId));
        assertEquals(1,held());assertEquals(102,Shadows.shadowOf(service).getLastForegroundNotificationId());assertFalse(Shadows.shadowOf(service).isStoppedBySelf());assertFalse(Shadows.shadowOf(service).isForegroundStopped());
        Notification notification=notice();assertNotNull(notification);assertTrue((notification.flags&Notification.FLAG_ONGOING_EVENT)!=0);assertTrue(notification.actions==null||notification.actions.length==0);
        assertEquals("dev.pocketengine.ui.ComposeActivity",Shadows.shadowOf(notification.contentIntent).getSavedIntent().getComponent().getClassName());
        assertNull(get(service,"runningId"));assertNull(get(service,"ownerControl"));assertSame(work.control,controls.get(work.task));assertFalse(work.control.isCancelled());
        assertEquals(before,tasks.get(work.task,false).toString());assertEquals(runs,count("runs"));assertEquals(operations,count("operations"));assertEquals(taskCount,count("tasks"));
    }
    @Test public void taskEventsReadOriginalControlAndDoNotTrustPushedCompletionOrCancelCleanup()throws Exception {
        Work work=work("原后台工作");retain(work);
        event(new JSONObject().put("id",work.task).put("runId",id()).put("coordinatorEpoch",99).put("status","completed"));assertEquals(1,held());assertNotNull(notice());
        tasks.status(work.task,"stopping","原执行器仍在清理");work.control.cancel();event(work);assertEquals(1,held());assertNotNull(notice());assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
        controls.remove(work.task,work.control);event(work);assertEquals(0,held());assertNull(notice());assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertEquals("stopping",tasks.get(work.task,false).getString("status"));
    }
    @Test public void multipleOriginalWorksReleaseOnlyAfterTheirOwnNativeControlOrTerminalStateEnds()throws Exception {
        Work first=work("第一项原工作"),second=work("第二项原工作");retain(first);retain(second);assertEquals(2,held());
        finish(first,"completed");assertEquals(1,held());assertNotNull(notice());assertSame(second.control,controls.get(second.task));assertFalse(second.control.isCancelled());assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
        tasks.endAttempt(second.task,second.run,"awaiting_user","等待主人原问题",new JSONObject());event(second);assertEquals(0,held());assertNull(notice());assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertSame(second.control,controls.get(second.task));
    }
    @Test public void staleIntentCannotAdoptLaterRunAndLateOldTaskPushCannotReleaseExplicitlyRegisteredSuccessor()throws Exception {
        Work first=work("等待明确继续的原工作");retain(first);finish(first,"interrupted");destroyService();
        RunControl successor=new RunControl();JSONObject resumed=tasks.continueOwnerWork(first.task,first.run,first.epoch,successor);Work next=new Work(first.task,resumed.getString("runId"),resumed.getInt("epoch"),successor);controls.put(next.task,successor);tasks.status(next.task,"running","主人明确继续");long runs=count("runs");
        createService();retain(first);assertEquals(0,held());assertNull(notice());assertSame(successor,controls.get(next.task));destroyService();
        createService();retain(next);event(new JSONObject().put("id",first.task).put("runId",first.run).put("coordinatorEpoch",first.epoch).put("status","cancelled"));retain(first);
        assertEquals(1,held());assertNotNull(notice());assertFalse(successor.isCancelled());assertEquals(next.run,tasks.get(next.task,false).getString("runId"));assertEquals(runs,count("runs"));
    }
    @Test public void phoneCompletionTransfersForegroundToWorkThenReceiverWithoutStoppingEitherOwner()throws Exception {
        Work work=work("手机操作期间也在处理的工作");retain(work);
        JSONObject phone=tasks.create("原手机执行","main_screen");String phoneId=phone.getString("id");tasks.configureExecutionScope(phoneId,0,System.currentTimeMillis()+300000,20,20);phone=tasks.startAttempt(phoneId,"main_screen",20);RunControl original=new RunControl();original.bindHostRun(phoneId,phone.getString("runId"),phone.getInt("coordinatorEpoch"));tasks.status(phoneId,"running","原手机执行夹具");
        set(app,"activeTaskId",phoneId);set(app,"activeControl",original);set(service,"runningId",phoneId);set(service,"runningRunId",phone.getString("runId"));set(service,"runningEpoch",phone.getInt("coordinatorEpoch"));set(service,"ownerControl",original);call("startTaskForeground",new Class<?>[]{JSONObject.class},tasks.get(phoneId,false));
        receiver=new DeviceReceiverServiceResource(new DeviceReceiverServiceResource.Backend(){private boolean listening;public void start(){listening=true;}public boolean isListening(){return listening;}public void close(){listening=false;}},new DeviceReceiverServiceResource.OwnerPolicy(){public void requireEnabledCurrent(){}public void disableByOwner(){}});
        set(app,"deviceReceiverResource",receiver);service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.START_DEVICE_RECEIVER).putExtra("deviceReceiverResourceId",receiver.resourceId()),0,++startId);await(()->receiver.snapshot().optBoolean("listening"));
        assertEquals(100,Shadows.shadowOf(service).getLastForegroundNotificationId());assertNotNull(notice());
        call("finishAttempt",new Class<?>[]{String.class,String.class,RunControl.class,RunOutcome.class,int.class},phoneId,phone.getString("runId"),original,new RunOutcome("completed","原手机执行完成",new JSONObject()),startId);
        await(()->get(service,"runningId")==null&&app.activeId()==null);assertEquals(102,Shadows.shadowOf(service).getLastForegroundNotificationId());assertFalse(Shadows.shadowOf(service).isForegroundStopped());assertFalse(work.control.isCancelled());assertTrue(receiver.snapshot().getBoolean("listening"));
        finish(work,"completed");assertEquals(101,Shadows.shadowOf(service).getLastForegroundNotificationId());assertNull(notice());assertFalse(Shadows.shadowOf(service).isStoppedBySelf());assertTrue(receiver.snapshot().getBoolean("listening"));
        service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.STOP_DEVICE_RECEIVER).putExtra("deviceReceiverResourceId",receiver.resourceId()),0,++startId);await(()->receiver.stopped()&&get(service,"receiverOnly")==null);assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertEquals("completed",tasks.get(phoneId,false).getString("status"));
    }
    @Test public void destructionAndNullRestartNeverCancelRecreateOrRediscoverTheCapabilityExecutor()throws Exception {
        Work work=work("关闭界面后仍由原执行器负责");retain(work);String before=tasks.get(work.task,false).toString();long runs=count("runs"),operations=count("operations");destroyService();
        assertNull(notice());assertSame(work.control,controls.get(work.task));assertFalse(work.control.isCancelled());assertEquals(before,tasks.get(work.task,false).toString());
        createService();assertEquals(PhoneRunService.START_NOT_STICKY,service.onStartCommand(null,0,++startId));assertEquals(0,held());assertNull(notice());assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        assertEquals(before,tasks.get(work.task,false).toString());assertEquals(runs,count("runs"));assertEquals(operations,count("operations"));
    }
    private interface Condition{boolean ready()throws Exception;}
    private static void await(Condition condition)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!condition.ready()){Shadows.shadowOf(Looper.getMainLooper()).idle();if(System.nanoTime()>deadline)fail("original service lifecycle did not settle");Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();}
}
