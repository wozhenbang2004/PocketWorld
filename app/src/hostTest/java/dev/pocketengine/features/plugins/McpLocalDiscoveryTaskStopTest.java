package dev.pocketengine.features.plugins;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Actual TaskDetail owner command -> original task/control/Job; no replacement dispatcher. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpLocalDiscoveryTaskStopTest {
    private static EngineApplication application(McpLocalDiscoveryOwnerTest.Fixture x){EngineApplication app=new EngineApplication();app.tasks=x.f.tasks;app.features=x.host;return app;}
    private static void close(EngineApplication app)throws Exception{var workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);((ExecutorService)workers.get(app)).shutdownNow();}
    @Test public void taskDetailStopReachesTheOriginalRunningProcessAndWaitsForRealCleanup()throws Exception {
        try(var x=new McpLocalDiscoveryOwnerTest.Fixture("  time.sleep(20)\n")){EngineApplication app=application(x);try{
            String id=x.confirm(x.prepare()).getString("taskId");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean entered=false;
            while(System.nanoTime()<until){JSONObject row=x.f.tasks.ownerMcpDiscoveryForOwner(id);if(row.optJSONObject("nativeJob")!=null)try{if(x.f.runtime.query(x.ref(id)).json().optString("stderr").contains("OWN_METHOD:tools/list")){entered=true;break;}}catch(IllegalArgumentException originalRefNotPublishedYet){}Thread.sleep(20);}
            assertTrue("Original child must have entered tools/list before stop",entered);
            JSONObject accepted=app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",id));assertEquals(id,accepted.getString("taskId"));assertTrue(accepted.getBoolean("cancelRequested"));assertFalse(accepted.getString("status").equals("completed"));
            JSONObject finalResult=x.finished(id);assertEquals("failed",finalResult.getString("status"));assertTrue(finalResult.getJSONObject("receipt").getJSONObject("processReceipt").getBoolean("resourcesReleased"));assertFalse(x.f.tasks.hasUnresolved(id));assertEquals(0,x.f.runtime.environmentManager().describe(x.f.pack).getInt("activeLeases"));assertEquals(1,x.f.tasks.list().length());assertEquals(1,x.f.tasks.get(id,false).getJSONArray("operations").length());
        }finally{close(app);}}
    }
    @Test public void coldUnknownWithoutOriginalProcessProofIsNotReportedStoppedOrReplayed()throws Exception {
        try(var x=new McpLocalDiscoveryOwnerTest.Fixture()){EngineApplication app=application(x);try{
            var selected=x.f.confirmed();RunControl original=new RunControl();x.f.tasks.beginOwnerMcpDiscoveryForOwner(selected,original);String id=selected.actionId();x.f.tasks.status(id,"reconciling","Original worker entry is unknown; no runtime proof");
            JSONObject observed=app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",id));assertTrue(observed.getBoolean("cancelRequested"));assertFalse(observed.getString("status").matches("completed|failed"));assertFalse(observed.has("receipt"));assertTrue(x.f.tasks.hasUnresolved(id));assertNull(x.f.tasks.ownerMcpDiscoveryForOwner(id).optJSONObject("nativeJob"));assertEquals(0,x.f.runtime.environmentManager().describe(x.f.pack).getInt("activeLeases"));assertEquals(1,x.f.tasks.list().length());
            assertThrows(SecurityException.class,()->x.f.tasks.startAttempt(id,"capabilities",1));selected.close();
        }finally{close(app);}}
    }
    @Test public void ordinaryNonMcpTaskStillUsesItsOriginalGenericStopBranch()throws Exception {
        try(var x=new McpLocalDiscoveryOwnerTest.Fixture()){EngineApplication app=application(x);try{
            String id=x.f.tasks.createCapabilityTask("Original ordinary task","").getString("id");String before=x.f.tasks.get(id,false).toString();assertFalse(x.host.ownsMcpDiscoveryTask(id));assertNull(app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",id)));JSONObject after=x.f.tasks.get(id,false);assertEquals(new JSONObject(before).getString("status"),after.getString("status"));assertFalse(after.has("ownerMcpDiscovery"));assertEquals(1,x.f.tasks.list().length());assertEquals(0,x.f.runtime.environmentManager().describe(x.f.pack).getInt("activeLeases"));
        }finally{close(app);}}
    }
}
