package dev.pocketengine.bootstrap;

import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real App gates and original SQLite store. Startup scheduling is suppressed, never readiness. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=BootstrapAdmissionNativeTest.NoStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BootstrapAdmissionNativeTest {
    @Implements(EngineApplication.class)
    public static final class NoStartup extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
    }
    private EngineApplication app;
    private RunControl original;
    @Before public void prepare(){app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);}
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private BootstrapStatus status()throws Exception{return (BootstrapStatus)field(app,"bootstrap");}
    private void openStore(){app.tasks=new TaskStore(app,app::businessReady,(stage,schema)->{});}
    private long count(String table)throws Exception{
        SQLiteDatabase db=(SQLiteDatabase)field(app.tasks,"db");
        try(Cursor rows=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(rows.moveToFirst());return rows.getLong(0);}
    }
    @After public void close()throws Exception{
        if(original!=null)original.cancel();status().maintenance("fixture_closed","fixture closed","fixture closed",false);
        if(app.features!=null)app.features.close();
        if(app.tasks!=null&&!app.tasks.closedForMaintenance())app.tasks.closeForMaintenance();
        for(String name:new String[]{"bootstrapWorker","capabilityWorkers","deviceControlWorkers","deviceStopWorkers"})((ExecutorService)field(app,name)).shutdownNow();
    }
    @Test public void unopenedAppRejectsOrdinaryOwnerRequestsBeforeTouchingBusinessModules()throws Exception{
        assertNull(app.tasks);assertFalse(app.businessReady());assertFalse(app.executionAllowed());
        for(String action:new String[]{"getFeatures","startDevelopmentTask","featureRequest","rescheduleAutomation"}){
            SecurityException denied=assertThrows(SecurityException.class,()->app.dispatchOwnerAction(action,new JSONObject()));
            assertTrue(denied.getMessage().contains("启动恢复"));
        }
        assertNull(app.tasks);assertNull(app.features);
    }
    @Test public void realOpenDatabaseAndEnabledOwnerPolicyAreInsufficientForExecution()throws Exception{
        openStore();assertTrue(app.tasks.ownerExecutionState().getBoolean("enabled"));assertFalse(app.businessReady());assertFalse(app.tasks.executionEnabled());
        AtomicInteger entered=new AtomicInteger();app.features=new FeatureHost(event->{},app.tasks,()->false);
        app.features.register(new EngineModule(){public String id(){return "bootstrap_probe";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input){entered.incrementAndGet();return new JSONObject();}});
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("featureRequest",new JSONObject().put("feature","bootstrap_probe").put("command","start")));assertEquals(0,entered.get());
        String id=app.tasks.createCapabilityTask("original queued fixture","").getString("id");String before=app.tasks.get(id,false).toString();
        assertThrows(SecurityException.class,()->app.tasks.startAttempt(id,"capabilities",8));
        assertEquals(before,app.tasks.get(id,false).toString());assertEquals(0,count("runs"));assertEquals(0,count("operations"));assertEquals(0,count("leases"));
    }
    @Test public void maintenanceRevokesOriginalAdmissionWithoutStartingOrReplacingAnyRun()throws Exception{
        openStore();ReadyApplicationFixture.complete(app);assertTrue(app.executionAllowed());
        String id=app.tasks.createCapabilityTask("original admitted fixture","").getString("id");JSONObject attempt=app.tasks.startAttempt(id,"capabilities",8);
        original=new RunControl();original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
        RunControl.AuthorityCheck admission=app.tasks.admitExecutionControl(original,id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));admission.check();
        String next=app.tasks.createCapabilityTask("queued next fixture","").getString("id");String scope=app.tasks.get(id,false).getJSONObject("executionScope").toString();
        status().maintenance("database_maintenance","original maintenance","read original record",false);
        assertFalse(app.businessReady());assertFalse(app.executionAllowed());assertThrows(SecurityException.class,admission::check);
        assertThrows(SecurityException.class,()->app.tasks.startAttempt(next,"capabilities",8));
        assertEquals(attempt.getString("runId"),app.tasks.get(id,false).getString("runId"));assertEquals(scope,app.tasks.get(id,false).getJSONObject("executionScope").toString());assertEquals(1,count("runs"));assertEquals(0,count("operations"));
    }
    @Test public void completedBootstrapDoesNotUndoTheOriginalOwnerStopPolicy()throws Exception{
        openStore();app.tasks.setExecutionEnabledForOwner(1,false);ReadyApplicationFixture.complete(app);
        assertTrue(app.businessReady());assertFalse(app.executionAllowed());
        SecurityException denied=assertThrows(SecurityException.class,app.tasks::requireExecutionEnabled);
        assertTrue(denied.getMessage().contains("PocketWorld 已停用"));assertFalse(denied.getMessage().contains("启动恢复"));
        assertEquals(2,app.tasks.ownerExecutionState().getLong("revision"));assertEquals(0,count("runs"));
    }
    @Test public void earlyServiceStartDoesNotConstructTaskOrReceiverState()throws Exception{
        openStore();assertFalse(app.businessReady());
        var service=Robolectric.buildService(PhoneRunService.class).create();
        try{
            assertEquals(Service.START_NOT_STICKY,service.get().onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.START).putExtra("taskId","not-an-admitted-task"),0,1));
            assertNull(field(service.get(),"ownerControl"));assertNull(field(service.get(),"receiverOnly"));assertNull(field(service.get(),"gatewayParent"));assertEquals(0,app.tasks.list().length());assertFalse(app.businessReady());
        }finally{service.destroy();}
    }
}
