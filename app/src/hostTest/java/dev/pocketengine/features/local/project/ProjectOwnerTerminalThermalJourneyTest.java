package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.PowerManager;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.NativeProcessSupervisor;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.android.AndroidRuntimeThermalObservation;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Actual original approval/SQLite/Node JNI PTY/log/input/lease chain with the
 * production native thermal adapter under simulated PowerManager. Not Android
 * tool execution, VT pixels, OEM thermal readings or measured energy savings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectOwnerTerminalThermalJourneyTest {
    private ProjectOwnerTerminalJourneyTest fixture;
    private TaskStore tasks;private LocalModule local;private ManagedProjectRuntime runtime;private RunControl original;
    private String task,run,checkout;private int epoch;
    private ProjectOwnerTerminal.Session session;
    private AndroidRuntimeThermalObservation observer;
    private static <T>T field(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);@SuppressWarnings("unchecked")T value=(T)f.get(object);return value;}
    private JSONObject tool(String name,JSONObject args)throws Exception {var m=fixture.getClass().getDeclaredMethod("tool",String.class,JSONObject.class);m.setAccessible(true);try{return (JSONObject)m.invoke(fixture,name,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void memory(boolean low){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();info.lowMemory=low;info.availMem=low?50:600;info.totalMem=1000;info.threshold=100;Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(ActivityManager.class)).setMemoryInfo(info);}
    private JSONObject memoryAfter(long previous)throws Exception {
        long until=System.nanoTime()+3_000_000_000L;JSONObject facts;
        do{runtime.automaticLogObservationPolicyForHost();idle();facts=runtime.automaticLogObservationPolicyForHost();if(facts.getLong("memoryObservationSequence")>previous&&memoryLaneIdle())return facts;Thread.sleep(5);}while(System.nanoTime()<until);
        throw new AssertionError("Original native memory observation did not return: "+facts);
    }
    // A published sample precedes the zero-queue executor worker's actual exit.
    // Settle that fixture lane before jumping the paused Android clock again.
    private boolean memoryLaneIdle()throws Exception{return this.<java.util.concurrent.ThreadPoolExecutor>field(observer,"memoryReads").getPoolSize()==0;}
    private void awaitMemoryLaneIdle()throws Exception{long until=System.nanoTime()+3_000_000_000L;do{idle();if(memoryLaneIdle())return;Thread.sleep(5);}while(System.nanoTime()<until);throw new AssertionError("Original memory reader did not exit before advancing the paused clock");}
    private void bindObserver(Context context){observer=AndroidRuntimeThermalObservation.open(context);runtime.bindLogObservationForHost(observer);}
    private void freshCooldown()throws Exception{for(int i=0;i<6;i++){runtime.automaticLogObservationPolicyForHost();awaitMemoryLaneIdle();long previous=runtime.automaticLogObservationPolicyForHost().getLong("memoryObservationSequence");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));memoryAfter(previous);}}
    @Before public void setup()throws Exception {
        assertTrue(new NativeProcessSupervisor().available());fixture=new ProjectOwnerTerminalJourneyTest();fixture.setup();
        tasks=field(fixture,"tasks");local=field(fixture,"local");original=field(fixture,"original");task=field(fixture,"task");run=field(fixture,"run");checkout=field(fixture,"checkout");epoch=field(fixture,"epoch");runtime=field(field(fixture,"factory"),"runtime");
        memory(false);
    }
    @After public void closeOnlyThisOriginalFixture()throws Exception {try{if(session!=null)session.close();}finally{try{if(fixture!=null)fixture.close();}finally{idle();}}}
    private static int bytes(JSONObject page)throws Exception {int total=0;JSONArray chunks=page.getJSONArray("chunks");for(int i=0;i<chunks.length();i++)total+=Base64.getDecoder().decode(chunks.getJSONObject(i).getString("dataBase64")).length;return total;}
    private static String text(JSONObject page)throws Exception {var bytes=new java.io.ByteArrayOutputStream();JSONArray chunks=page.getJSONArray("chunks");for(int i=0;i<chunks.length();i++)bytes.write(Base64.getDecoder().decode(chunks.getJSONObject(i).getString("dataBase64")));return bytes.toString(StandardCharsets.UTF_8.name());}
    private JSONObject originalRequest;
    private JSONObject exact;
    private void launchOneOriginalService()throws Exception {
        String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");
        String program="process.stdout.write('BEGIN_LOG'+('x'.repeat(65536))+'END_LOG\\n');setInterval(()=>{},1000);";
        JSONObject ref=tool("project_plan",new JSONObject().put("intent","command").put("executable","node").put("argv",new JSONArray().put("-e").put(program)).put("cwd",".").put("runMode","owned_service").put("terminal","pty").put("inputByteLimit",4096).put("timeoutMillis",20000).put("columns",80).put("rows",24).put("expectedSourceDigest",digest));
        JSONObject args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId","command"),approval=tool("project_execute",args);assertEquals(approval.toString(),"approval_required",approval.getString("code"));tasks.decideCapabilityApproval(task,approval.getString("approvalId"),true);
        JSONObject launched=tool("project_execute",args);assertEquals(launched.toString(),"project_running",launched.getString("code"));String execution=launched.getString("operationId");
        var target=tasks.ownerProjectTerminalForOwner(task,execution);originalRequest=target.originalRequest();JSONObject plan=originalRequest.getJSONObject("plan");exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,originalRequest.getString("stepId")));
        session=this.<TaskStoreProjectHost>field(field(fixture,"factory"),"host").ownerTerminals().open(task,execution,new RunControl());
        long end=System.nanoTime()+5_000_000_000L;JSONObject output=null;
        while(System.nanoTime()<end){output=local.projectLogForHost(exact,originalRequest.getJSONObject("binding"),0,262144);if(text(output).contains("END_LOG"))break;Thread.sleep(10);}
        assertNotNull(output);assertTrue("Actual owned Node must produce its own fixed output",text(output).contains("END_LOG"));assertTrue(bytes(output)>32768);
    }
    private void unchangedOriginal(int operationCount,String identity,String metadata)throws Exception {
        assertEquals(identity,runtime.identity());assertEquals(metadata,session.metadata().toString());assertEquals(operationCount,tasks.get(task,false).getJSONArray("operations").length());assertEquals(run,tasks.get(task,false).getString("runId"));assertEquals(epoch,tasks.get(task,false).getInt("coordinatorEpoch"));assertFalse(original.isCancelled());
        JSONObject actual=local.projectReceiptForHost(exact,originalRequest.getJSONObject("binding"),false);assertFalse(actual.getBoolean("resourcesReleased"));assertFalse(actual.optBoolean("processExited"));assertTrue(actual.getBoolean("ownedWorkerLive"));assertFalse(runtime.describe().has("logObservationPolicy"));
    }
    private void stopAndConfirmOnlyOriginalService()throws Exception {
        assertNotNull(session.stop());this.<ProjectCapabilityPort>field(fixture,"port").close();
        JSONObject actual=runtime.query(RuntimeExecutionRef.fromHost(exact,originalRequest.getJSONObject("binding"))).json();
        assertTrue(actual.toString(),actual.getBoolean("processExited"));assertTrue(actual.getBoolean("processGroupAbsent"));assertTrue(actual.getBoolean("processSessionAbsent"));assertTrue(actual.getBoolean("resourcesReleased"));assertFalse(original.isCancelled());assertNotEquals("completed",tasks.get(task,false).getString("status"));
    }
    @Test public void realOriginalTerminalReducesOnlyAutomaticPagesAndRestoresAfterFreshColdProof()throws Exception {
        PowerManager power=RuntimeEnvironment.getApplication().getSystemService(PowerManager.class);var shadow=Shadows.shadowOf(power);shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE);String identity=runtime.identity();
        bindObserver(RuntimeEnvironment.getApplication());idle();launchOneOriginalService();int count=tasks.get(task,false).getJSONArray("operations").length();String metadata=session.metadata().toString();
        JSONObject hot=session.read(0);assertEquals(8192,hot.getJSONObject("logObservationPolicy").getInt("automaticLogPageBytes"));assertTrue(bytes(hot)>0);assertTrue(bytes(hot)<=8192);assertTrue(hot.getBoolean("hasMore"));assertTrue(hot.getLong("nextCursor")>0);
        JSONObject explicit=local.projectLogForHost(exact,originalRequest.getJSONObject("binding"),0,32768);assertTrue(bytes(explicit)>8192);assertTrue(bytes(explicit)<=32768);unchangedOriginal(count,identity,metadata);
        shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE);idle();assertEquals(8192,session.read(0).getJSONObject("logObservationPolicy").getInt("automaticLogPageBytes"));
        freshCooldown();JSONObject cold=session.read(0);assertEquals(32768,cold.getJSONObject("logObservationPolicy").getInt("automaticLogPageBytes"));assertTrue(bytes(cold)>8192);assertTrue(bytes(cold)<=32768);unchangedOriginal(count,identity,metadata);
        JSONObject next=session.read(hot.getLong("nextCursor"));assertTrue(next.getLong("nextCursor")>hot.getLong("nextCursor"));assertNotEquals(hot.getJSONArray("chunks").getJSONObject(0).getLong("sequence"),next.getJSONArray("chunks").getJSONObject(0).getLong("sequence"));stopAndConfirmOnlyOriginalService();
    }
    @Test public void unavailableSourceNeverClaimsColdOrStopsOriginalAndClosingViewKeepsItsRealJob()throws Exception {
        Context noThermal=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Object getSystemService(String name){return Context.POWER_SERVICE.equals(name)?null:super.getSystemService(name);}};
        bindObserver(noThermal);idle();String identity=runtime.identity();launchOneOriginalService();int count=tasks.get(task,false).getJSONArray("operations").length();String metadata=session.metadata().toString();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));JSONObject page=session.read(0),policy=page.getJSONObject("logObservationPolicy");assertTrue(policy.isNull("thermalStatus"));assertEquals(8192,policy.getInt("automaticLogPageBytes"));assertFalse(policy.getBoolean("resourceReleaseProved"));assertFalse(policy.getBoolean("energySavingsMeasured"));assertTrue(bytes(page)>0);assertTrue(bytes(page)<=8192);unchangedOriginal(count,identity,metadata);
        session.close();assertFalse(original.isCancelled());session=null;
        String id=originalRequest.getJSONObject("binding").getString("operationId");var host=this.<TaskStoreProjectHost>field(field(fixture,"factory"),"host");session=host.ownerTerminals().open(task,id,new RunControl());assertEquals(metadata,session.metadata().toString());stopAndConfirmOnlyOriginalService();
    }
    @Test public void memoryPressureAndDiagnosticCacheRemainBoundToOriginalLiveExecution()throws Exception {
        PowerManager power=RuntimeEnvironment.getApplication().getSystemService(PowerManager.class);Shadows.shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE);
        bindObserver(RuntimeEnvironment.getApplication());memoryAfter(0);freshCooldown();assertEquals(32768,runtime.automaticLogObservationPolicyForHost().getInt("automaticLogPageBytes"));
        String identity=runtime.identity();launchOneOriginalService();int count=tasks.get(task,false).getJSONArray("operations").length();String metadata=session.metadata().toString();
        long previous=runtime.automaticLogObservationPolicyForHost().getLong("memoryObservationSequence");memory(true);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));JSONObject low=memoryAfter(previous);assertEquals(0,low.getInt("thermalStatus"));assertTrue(low.getBoolean("memoryLow"));assertEquals(8192,low.getInt("automaticLogPageBytes"));
        JSONObject automatic=session.read(0);assertTrue(bytes(automatic)>0);assertTrue(bytes(automatic)<=8192);assertTrue(bytes(local.projectLogForHost(exact,originalRequest.getJSONObject("binding"),0,32768))>8192);unchangedOriginal(count,identity,metadata);
        long sampled=observer.cachedSnapshotForHost().getLong("memoryObservationSequence");var preview=local.diagnosticMetadataForOwner(new RunControl());JSONObject diagnostic=new JSONObject(preview.textForOwner()).getJSONObject("sources").getJSONObject("runtime").getJSONObject("facts");
        assertTrue(diagnostic.getBoolean("memoryLow"));assertTrue(diagnostic.getBoolean("memoryFresh"));assertEquals(50,diagnostic.getLong("memoryAvailableBytes"));assertEquals(1000,diagnostic.getLong("memoryTotalBytes"));assertEquals(100,diagnostic.getLong("memoryThresholdBytes"));assertEquals(8192,diagnostic.getInt("automaticLogPageBytes"));assertEquals(sampled,observer.cachedSnapshotForHost().getLong("memoryObservationSequence"));assertFalse(diagnostic.has("generation"));assertFalse(diagnostic.has("taskId"));assertFalse(diagnostic.has("source"));
        memory(false);previous=sampled;Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));memoryAfter(previous);assertEquals(8192,session.read(0).getJSONObject("logObservationPolicy").getInt("automaticLogPageBytes"));freshCooldown();assertTrue(bytes(session.read(0))>8192);unchangedOriginal(count,identity,metadata);
        session.close();assertFalse(original.isCancelled());var host=this.<TaskStoreProjectHost>field(field(fixture,"factory"),"host");session=host.ownerTerminals().open(task,originalRequest.getJSONObject("binding").getString("operationId"),new RunControl());assertEquals(metadata,session.metadata().toString());stopAndConfirmOnlyOriginalService();
    }
}
