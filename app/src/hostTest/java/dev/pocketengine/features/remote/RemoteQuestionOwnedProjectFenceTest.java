package dev.pocketengine.features.remote;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectCapabilityPort;
import dev.pocketengine.features.local.project.ProjectManagedRunnerJourneyTest;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** One original native-service prefix, not the existing twenty-stage development journey.
 * Fixed existing C2 factory is linked for real SQLite/managed host-JNI/checkout ownership;
 * only fault rows are inserted atomically, never model-supplied dispatch authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class RemoteQuestionOwnedProjectFenceTest {
    private static Object call(Object fixture,String method,Class<?>[] types,Object... args)throws Exception {
        Method m=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(method,types);m.setAccessible(true);
        try{return m.invoke(fixture,args);}catch(java.lang.reflect.InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}
    }
    private static Object field(Object fixture,String name)throws Exception {var f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return f.get(fixture);}
    @Test public void originalOwnedServiceAloneMayRestoreButEveryQuestionPendingStateBlocksRestoreAndModelDispatch()throws Exception {
        ProjectManagedRunnerJourneyTest fixture=new ProjectManagedRunnerJourneyTest();ProjectCapabilityPort port=null;fixture.create();
        try {
            call(fixture,"selectedProject",new Class<?>[]{String.class},"Original owned service with a question barrier");
            String script=(String)call(null,"serviceScript",new Class<?>[]{String.class},UUID.randomUUID().toString());
            call(fixture,"file",new Class<?>[]{String.class,String.class},"main.mjs",script);
            call(fixture,"file",new Class<?>[]{String.class,String.class},"message.mjs","export const title = 'First service result';\n");
            call(fixture,"startTask",new Class<?>[]{String.class},"Only launch the original selected owned service and verify unrelated question barriers");
            JSONObject task=(JSONObject)call(fixture,"startFixtureAttempt",new Class<?>[]{});
            TaskStore tasks=(TaskStore)field(fixture,"tasks");LocalModule local=(LocalModule)field(fixture,"local");
            @SuppressWarnings("unchecked") RunControl original=((AtomicReference<RunControl>)field(fixture,"live")).get();
            String id=task.getString("id"),run=task.getString("runId"),project=(String)field(fixture,"project"),checkout=(String)field(fixture,"checkout");int epoch=task.getInt("coordinatorEpoch");
            var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,original,true,project);
            port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);
            JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,run,names);
            JSONObject inspection=port.execute("project_inspect",new JSONObject(),original);
            JSONObject planned=port.execute("project_plan",new JSONObject().put("intent","run").put("runMode","owned_service").put("ecosystem","node").put("expectedSourceDigest",inspection.getString("sourceDigest")),original);
            JSONObject plan=planned.getJSONObject("plan");call(fixture,"approveControlledOwnerPlan",new Class<?>[]{JSONObject.class},plan);
            JSONObject args=new JSONObject().put("plan",plan).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));RunJournal journal=tasks.journal(id,run,epoch,original);
            String op=journal.beginOperation("project_execute",args);JSONObject ack=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",op),original);journal.finishOperation(op,ack);
            assertEquals("project_running",ack.getString("code"));assertTrue(journal.hasNativeProjectRunningAck(op,ack));
            call(fixture,"probeControlledService",new Class<?>[]{String.class},"First service result");
            assertTrue(tasks.hasUnresolved(id));assertNotNull("only the exact owned-service exception is legal",journal.restore().getJSONArray("operationResults"));
            synchronized(tasks){
                var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)f.get(tasks);
                for(String state:new String[]{"remote_question_prepared","remote_question_sending","remote_question_unknown"}){
                    String unknown=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("operation_id",unknown);row.put("task_id",id);row.put("run_id",run);row.put("tool","remote_question_answer");row.put("arguments","{}");row.put("state",state);row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);
                    try {
                        assertTrue(tasks.hasUnresolved(id));assertThrows(state+" must not disappear in owned exception",IllegalStateException.class,journal::restore);
                        InferenceCapture capture=new InferenceCapture(new ModelConfig("http://127.0.0.1:1/v1","fixture-not-a-real-key","fixture",false,8,5),original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));
                        assertThrows(state+" must stop before native model start receipt/network",IllegalStateException.class,capture::dispatch);
                    } finally {db.delete("operations","operation_id=?",new String[]{unknown});}
                    assertNotNull(journal.restore().getJSONArray("operationResults"));
                }
            }
            port.close();port=null;assertFalse("original service is actually drained, no other job launched",tasks.hasUnresolved(id));
            JSONObject exits=journal.projectServiceExitEvidence(new JSONArray().put(op));assertNotNull(exits);assertTrue(exits.getBoolean("allReleased"));
            System.out.println("C3_QUESTION_OWNED_FENCE real original SQLite and native host-JNI service+HTTP=true; single owned restore=true; question prepared/sending/unknown each block restore+native model start=true; transient faults atomically removed; original one-job process group+lease drained=true; no real provider/ARM/UI");
        } finally {if(port!=null)port.close();fixture.close();}
    }
}
