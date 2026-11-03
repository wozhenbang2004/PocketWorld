package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Current TaskStore and FeatureHost, native tickets, real SQLite and loopback HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOriginalStopJourneyTest {
    static final class Fixture implements AutoCloseable {
        final RemoteOriginalCancellationTest.Server server=new RemoteOriginalCancellationTest.Server();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        final TaskStore tasks;final FeatureHost host;final RemoteModule remote;final String id=UUID.randomUUID().toString();
        Fixture()throws Exception {
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");
            tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),events::add),(profile,base)->"fixture-token-longer-than-32-characters",path->{});host.register(remote);
            java.lang.reflect.Field field=RemoteModule.class.getDeclaredField("poller");field.setAccessible(true);((java.util.concurrent.ScheduledExecutorService)field.get(remote)).shutdownNow();
            remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","computer").put("baseUrl",server.base()));server.operation=id;server.responseOperation=id;
            tasks.beginExternal("remote","submitJob",id,new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","command").put("prompt","Original work"));
            tasks.updateExternal(id,RemoteOriginalCancellationTest.receipt(id,"running").put("profileId","computer").put("serviceBase",server.base()));
        }
        JSONObject request(String operation)throws Exception {
            String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command","cancelJob").put("requestId",request).put("payload",new JSONObject().put("profileId","computer").put("serviceBase",server.base()).put("jobId","original-job").put("jobOperationId",operation)));
            long deadline=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<deadline){for(JSONObject event:events)if(request.equals(event.optString("requestId")))return event;Thread.sleep(5);}throw new AssertionError(events.toString());
        }
        SQLiteDatabase db()throws Exception {java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
        int epoch()throws Exception{return tasks.get(id,false).getInt("coordinatorEpoch");}
        public void close()throws Exception {host.close();remote.close();server.close();db().close();}
    }
    @Test public void nativeOwnerRequestUsesOriginalSqlTicketAndRepeatsOnlyRead()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject first=f.request(f.id);assertTrue(first.toString(),first.getBoolean("ok"));assertEquals("stopping",first.getJSONObject("result").getString("status"));assertEquals(1,f.server.original.get());assertEquals(0,f.server.legacy.get());assertEquals(f.id,f.tasks.get(f.id,false).getString("runId"));assertTrue(f.tasks.hasUnresolved(f.id));
            JSONObject next=f.request(f.id);assertTrue(next.toString(),next.getBoolean("ok"));assertTrue(next.getJSONObject("result").getBoolean("queryOnly"));assertEquals(1,f.server.original.get());assertTrue(f.tasks.hasUnresolved(f.id));assertNotEquals("cancelled",f.tasks.get(f.id,false).getString("status"));
        }
    }
    @Test public void staleUiOperationCannotBeReboundToTheCurrentNativeRun()throws Exception {
        try(Fixture f=new Fixture()){JSONObject refused=f.request("old-operation");assertFalse(refused.toString(),refused.getBoolean("ok"));assertEquals(0,f.server.original.get());assertEquals(0,f.server.legacy.get());assertFalse(f.tasks.get(f.id,false).optBoolean("cancelRequested"));}
    }
    @Test public void changedNativeEpochDuringCapabilityReadPreventsThePost()throws Exception {
        try(Fixture f=new Fixture()){
            int originalEpoch=f.epoch();f.server.duringStatus=()->{try{JSONObject task=f.tasks.get(f.id,false);task.put("coordinatorEpoch",originalEpoch+1);ContentValues row=new ContentValues();row.put("document",task.toString());f.db().update("tasks",row,"task_id=?",new String[]{f.id});f.db().execSQL("UPDATE runs SET epoch=? WHERE task_id=?",new Object[]{originalEpoch+1,f.id});}catch(Exception error){throw new RuntimeException(error);}};
            JSONObject refused=f.request(f.id);assertFalse(refused.toString(),refused.getBoolean("ok"));assertEquals(0,f.server.original.get());assertEquals(0,f.server.legacy.get());
        }
    }
    @Test public void originalFinalPollingFactsSettlePendingStopWithoutAnotherPost()throws Exception {
        try(Fixture f=new Fixture()){
            assertTrue(f.request(f.id).getBoolean("ok"));assertTrue(f.tasks.hasUnresolved(f.id));
            JSONObject terminal=RemoteOriginalCancellationTest.receipt(f.id,"cancelled").put("profileId","computer").put("serviceBase",f.server.base()).put("dispatchFenceFinal",true).put("resourcesHeld",false);f.tasks.updateExternal(f.id,terminal);
            assertFalse(f.tasks.hasUnresolved(f.id));assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));assertEquals(1,f.server.original.get());
            try(Cursor row=f.db().rawQuery("SELECT state FROM operations WHERE task_id=? AND tool='remote_original_stop'",new String[]{f.id})){assertTrue(row.moveToFirst());assertEquals("receipt",row.getString(0));}
        }
    }
}
