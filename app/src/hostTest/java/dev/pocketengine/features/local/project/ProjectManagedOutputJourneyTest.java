package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Original native Node build -> original plan/approval -> immutable binary Book output.
 * Self-created project only; no external dependencies/provider or fake workspace version. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectManagedOutputJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private TaskStore tasks;private LocalModule local;private ProjectCapabilityPort port;private RunControl control;private RunJournal journal;private String task,run,project,checkout;private int epoch;private Path root;
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{var m=factory.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(factory,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}}
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception{var f=factory.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(factory);}
    @Before public void setup()throws Exception{
        factory=new ProjectManagedRunnerJourneyTest();factory.create();call("selectedProject",new Class<?>[]{String.class},"Actual managed binary build output");call("file",new Class<?>[]{String.class,String.class},"package.json","{\"name\":\"own-managed-output\",\"version\":\"1.0.0\",\"type\":\"module\",\"scripts\":{\"build\":\"node tools/build.mjs\",\"test\":\"node -e 0\"}}\n");call("file",new Class<?>[]{String.class,String.class},"tools/build.mjs","import{mkdirSync,writeFileSync}from'node:fs';mkdirSync('dist',{recursive:true});writeFileSync('dist/output.bin',Buffer.from([0,1,255]));writeFileSync('dist/page.html','<h1>40.00</h1>');console.log('own build complete');\n");call("startTask",new Class<?>[]{String.class},"Build same source, review original generated binary and keep exact immutable output");tasks=field("tasks");local=field("local");task=field("taskId");project=field("project");checkout=field("checkout");root=(Path)call("active",new Class<?>[0]);attempt();
    }
    private void attempt()throws Exception{JSONObject admitted=(JSONObject)call("startFixtureAttempt",new Class<?>[0]);run=admitted.getString("runId");epoch=admitted.getInt("coordinatorEpoch");control=((AtomicReference<RunControl>)this.<AtomicReference<RunControl>>field("live")).get();var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,names);journal=tasks.journal(task,run,epoch,control);}
    @After public void close()throws Exception{try{if(port!=null)port.close();}finally{if(factory!=null)factory.close();}}
    private JSONObject tool(String name,JSONObject args)throws Exception{String op=port.sideEffecting(name)?journal.beginOperation(name,args):null;JSONObject input=new JSONObject(args.toString());if(op!=null)input.put("_hostOperationId",op);JSONObject value=port.execute(name,input,control);if(op!=null)journal.finishOperation(op,value);return value;}
    private JSONObject execute(JSONObject ref)throws Exception{JSONObject args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId",ref.getJSONArray("steps").getJSONObject(0).getString("id"));JSONObject result=tool("project_execute",args);if("approval_required".equals(result.optString("code"))){tasks.decideCapabilityApproval(task,result.getString("approvalId"),true);result=tool("project_execute",args);}assertNotEquals(result.toString(),"approval_required",result.optString("code"));return result;}
    private JSONObject build()throws Exception{String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");JSONObject ref=tool("project_plan",new JSONObject().put("intent","build").put("ecosystem","node").put("expectedSourceDigest",digest)),result=execute(ref);assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(0,result.getInt("exitCode"));assertTrue(result.getBoolean("resourcesReleased"));return result;}
    @Test public void actualBinaryOutputRejectsDriftThenFreshPlanPublishesOriginalAndColdQueryNeverResaves()throws Exception{
        JSONObject built=build();String build=built.getString("operationId");JSONObject stale=tool("project_collect_plan",new JSONObject().put("executionId",build).put("path","dist/output.bin"));assertEquals("project_collect_plan_ready",stale.getString("code"));assertFalse(stale.getBoolean("executionAuthorized"));assertEquals(0,AttachmentRepository.get(RuntimeEnvironment.getApplication()).book.list().length());Files.write(root.resolve("dist/output.bin"),new byte[]{4,5,(byte)255});JSONObject failed=execute(stale);assertEquals(failed.toString(),"failed",failed.getString("status"));assertFalse(failed.getBoolean("nativePublicationEntered"));assertEquals(0,AttachmentRepository.get(RuntimeEnvironment.getApplication()).book.list().length());assertFalse(tasks.hasUnresolved(task));
        JSONObject fresh=tool("project_collect_plan",new JSONObject().put("executionId",build).put("path","dist/output.bin")),saved=execute(fresh);assertEquals(saved.toString(),"completed",saved.getString("status"));assertTrue(saved.getBoolean("nativeFileOperation"));assertTrue(saved.isNull("processExitCode"));JSONObject original=tool("project_generated_artifact",new JSONObject().put("executionId",saved.getString("operationId")));assertEquals(build,original.getString("buildJobId"));assertTrue(original.getBoolean("generatedArtifact"));assertEquals(3,original.getLong("size"));assertArrayEquals(new byte[]{4,5,(byte)255},AttachmentRepository.get(RuntimeEnvironment.getApplication()).book.readVerifiedSnapshot(original.getString("id"),original.getString("sha256"),control));assertEquals("generated result is not falsely labeled owner-selected input",0,tasks.ownerAttachmentReferences(task).length());assertEquals(task,original.getJSONObject("source").getString("taskId"));
        Files.write(root.resolve("dist/output.bin"),new byte[]{8,9});JSONObject same=tool("project_generated_artifact",new JSONObject().put("executionId",saved.getString("operationId")));assertEquals(original.getString("id"),same.getString("id"));assertEquals(original.getString("sha256"),same.getString("sha256"));assertEquals(1,AttachmentRepository.get(RuntimeEnvironment.getApplication()).book.list().length());
        String oldRun=run;tasks.endAttempt(task,run,"awaiting_user","explicitly reopen original result",new JSONObject());port.close();attempt();assertNotEquals(oldRun,run);JSONObject reopened=tool("project_generated_artifact",new JSONObject().put("executionId",saved.getString("operationId")));assertEquals(original.getString("id"),reopened.getString("id"));assertFalse(tasks.hasUnresolved(task));
    }
    @Test public void nativeMonotoneJobOrderInvalidatesOldBuildAndOriginalSourceCannotBeRelabeledAsGenerated()throws Exception{
        JSONObject built=build();String operation=built.getString("operationId");assertThrows(SecurityException.class,()->tool("project_collect_plan",new JSONObject().put("executionId",operation).put("path","tools/build.mjs")));assertThrows(SecurityException.class,()->tool("project_collect_plan",new JSONObject().put("executionId",operation).put("path",".npmrc")));JSONObject selected=tool("project_collect_plan",new JSONObject().put("executionId",operation).put("path","dist/output.bin"));
        String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");JSONObject test=tool("project_plan",new JSONObject().put("intent","test").put("ecosystem","node").put("expectedSourceDigest",digest));assertEquals("completed",execute(test).getString("status"));assertThrows(SecurityException.class,()->tool("project_collect_plan",new JSONObject().put("executionId",operation).put("path","dist/output.bin")));JSONObject stale=execute(selected);assertEquals(stale.toString(),"failed",stale.getString("status"));assertFalse(stale.getBoolean("nativePublicationEntered"));assertEquals(0,AttachmentRepository.get(RuntimeEnvironment.getApplication()).book.list().length());assertFalse(tasks.hasUnresolved(task));
    }
}
