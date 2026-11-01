package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectCapabilityPort;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeReceipt;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual VWS -> private checkout -> Local ProjectFileQueries -> original Port -> nonnull Factory
 * -> original SQLite/raw reader. No process, tool install, model, account or network is run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectNativeOutputCaptureTest {
    private static final String SECRET="c4-native-project-fixture-secret";
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class NeverExecute implements ProjectRuntime {
        public JSONObject describe()throws Exception{return new JSONObject().put("runtimeId","c4-unused-read-fixture").put("canExecute",false).put("available",false).put("phases",new JSONArray()).put("fixtureOnly",true);}
        public RuntimeReceipt dispatch(RuntimeInvocation ignored){throw new AssertionError("Reading source must not dispatch a process");}
        public RuntimeReceipt query(RuntimeExecutionRef ignored){throw new AssertionError("Reading source must not query a process");}
        public RuntimeReceipt requestCancel(RuntimeExecutionRef ignored){throw new AssertionError("Reading source must not cancel another process");}
    }
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;
        final WorkspaceModule workspace;final LocalModule local;final RunControl original=new RunControl();
        final String project,checkout,task,run;final int epoch;final ModelCapabilityAdapters.Binding binding;
        final ToolOutputCapture.Factory capture;final ProjectCapabilityPort port;final String body;
        Fixture(String source)throws Exception {
            body=source;android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);db=(SQLiteDatabase)f.get(tasks);
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,new NeverExecute());
            project=workspace.handle("create",new JSONObject().put("name","原生读取输出证据夹具")).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","a.txt").put("expectedVersion",0).put("content",source));
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","b.txt").put("expectedVersion",0).put("content","needle second actual file\n"));
            checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");task=tasks.createCapabilityTask("只读取这个项目的原生范围","c4-project-read-fixture").getString("id");JSONObject attempt=tasks.startAttempt(task,"capabilities",8);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);
            tasks.bindDevelopmentContext(task,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
            binding=new ModelCapabilityAdapters.Binding(task,run,epoch,original,true,project);capture=tasks.toolOutputFactory(task,run,epoch,original,text->ModelGateway.redact(text,SECRET));
            port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,"termux",capture);
            tasks.authorizeTools(task,run,new JSONArray().put("project_read_file").put("project_search"));
        }
        JSONObject read(int max)throws Exception{return port.execute("project_read_file",new JSONObject().put("path","a.txt").put("offset",0).put("maxChars",max),original);}
        JSONObject reference(JSONObject receipt)throws Exception{JSONObject metadata=receipt.getJSONObject("outputCapture");assertFalse(metadata.getBoolean("bodyIncluded"));assertEquals(1,metadata.getJSONArray("outputRefs").length());return metadata.getJSONArray("outputRefs").getJSONObject(0);}
        TaskStoreMemoryReader.RawBinding rawBinding()throws Exception{TaskStoreMemoryReader.RawBinding value=new TaskStoreMemoryReader.RawBinding(task,run,epoch,original,new MemoryHostFence(){public void check()throws Exception{original.check();}public JSONObject admit(Admission action)throws Exception{return action.run();}},()->0);value.bindOutputSources(tasks.toolOutputSources());return value;}
        JSONObject request(JSONObject ref)throws Exception{return new JSONObject().put("sourceId",ref.getString("sourceId")).put("revision",ref.getString("revision")).put("offset",0).put("maxChars",16000);}
        JSONObject raw(JSONObject ref)throws Exception{return TaskMemoryTrace.checkedRawSource(tasks.readMemoryRaw(rawBinding(),request(ref)),task,run,request(ref));}
        JSONObject audit(JSONObject ref)throws Exception{return tasks.readNativeOutputAudit(MemoryBook.Scope.from(scope()),request(ref).put("taskId",task).put("runId",run),new RunControl());}
        long outputs(){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM task_output_sources WHERE task_id=? AND run_id=?",new String[]{task,run})){assertTrue(c.moveToFirst());return c.getLong(0);}}
        long operations(){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM operations WHERE task_id=? AND run_id=?",new String[]{task,run})){assertTrue(c.moveToFirst());return c.getLong(0);}}
        public void close()throws Exception{try{port.close();}finally{try{local.close();}finally{workspace.close();db.close();}}}
    }

    @Test public void realCompleteFileReadArchivesSafeNativeDataWithoutInventingPreparedOperationOrAnotherTask()throws Exception {
        try(Fixture f=new Fixture("ORIGINAL🙂 native project source "+SECRET+"\n")){
            int tasks=f.tasks.list().length();JSONObject result=f.read(8000),ref=f.reference(result);assertTrue(result.getBoolean("complete"));assertEquals("stored",result.getJSONObject("outputCapture").getString("status"));assertEquals(Boolean.FALSE,ref.get("upstreamTruncated"));assertEquals("",ref.getString("operationId"));assertEquals("project_read_file",ref.getString("tool"));assertEquals(0,f.operations());assertEquals(1,f.outputs());
            JSONObject wrapper=new JSONObject(f.raw(ref).getString("body")),nativeCall=wrapper.getJSONObject("nativeReadCall"),retained=wrapper.getJSONObject("data");assertEquals("native_project_read_call",nativeCall.getString("kind"));assertEquals(f.project,nativeCall.getString("projectId"));assertEquals(f.checkout,nativeCall.getString("checkoutId"));assertEquals(f.task,nativeCall.getString("taskId"));assertEquals(f.run,nativeCall.getString("runId"));assertEquals(f.epoch,nativeCall.getInt("epoch"));assertFalse(nativeCall.getBoolean("preparedSideEffectOperation"));assertEquals(ModelGateway.redact(f.body,SECRET),retained.getString("content"));assertEquals(result.getString("sha256"),retained.getString("sha256"));assertEquals(f.checkout,retained.getString("checkoutId"));assertFalse(wrapper.toString().contains(SECRET));assertFalse(retained.has("outputCapture"));assertEquals(tasks,f.tasks.list().length());
            try(Cursor chunks=f.db.rawQuery("SELECT c.body FROM task_output_chunks c JOIN task_output_sources s ON c.output_id=s.output_id WHERE s.task_id=? AND s.run_id=?",new String[]{f.task,f.run})){while(chunks.moveToNext())assertFalse(chunks.getString(0).contains(SECRET));}
            assertEquals(f.body,Files.readString(new File(f.android.getFilesDir(),"local-development/checkouts/"+f.checkout+"/files/a.txt").toPath(),StandardCharsets.UTF_8));
        }
    }

    @Test public void actualReadAndSearchLimitsRemainPartialNotClaimsOfACompleteFileOrSearchCorpus()throws Exception {
        try(Fixture f=new Fixture("needle "+"字".repeat(12000)+" UNREAD_TAIL")){
            JSONObject read=f.read(8000),readRef=f.reference(read),readBody=new JSONObject(f.raw(readRef).getString("body")).getJSONObject("data");assertFalse(read.getBoolean("complete"));assertEquals(8000,readBody.getString("content").length());assertFalse(readBody.getString("content").contains("UNREAD_TAIL"));assertEquals("partial",read.getJSONObject("outputCapture").getString("status"));assertEquals(Boolean.TRUE,readRef.get("upstreamTruncated"));
            JSONObject search=f.port.execute("project_search",new JSONObject().put("mode","path").put("query","*.txt").put("limit",1),f.original),searchRef=f.reference(search),searchBody=new JSONObject(f.raw(searchRef).getString("body")).getJSONObject("data");assertTrue(search.getBoolean("hasMore"));assertEquals(1,searchBody.getJSONArray("matches").length());assertTrue(searchBody.getBoolean("hasMore"));assertEquals("partial",search.getJSONObject("outputCapture").getString("status"));assertEquals(Boolean.TRUE,searchRef.get("upstreamTruncated"));assertEquals("project_search",searchRef.getString("tool"));assertEquals(2,f.outputs());assertEquals(0,f.operations());
        }
    }

    @Test public void exactNativeCheckoutIdentityAndFormalForgetGateRefuseBeforeAnyNewProducerBody()throws Exception {
        try(Fixture f=new Fixture("owner approved project source\n")){
            var foreign=new ModelCapabilityAdapters.Binding(f.task,f.run,f.epoch,f.original,true,UUID.randomUUID().toString());ToolOutputCapture.ReadCall wrong=ToolOutputCapture.ReadCall.project(ToolOutputCapture.ReadTool.PROJECT_READ_FILE,foreign,f.checkout,f.original);assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.capture.beginRead(wrong,f.original));assertEquals(0,f.outputs());
            MemoryBook memory=new MemoryBook(new File(f.android.getFilesDir(),"project-output-memory-"+UUID.randomUUID()+".json"));JSONObject used=memory.save(scope(),new JSONObject().put("text","真实原模型正式依据").put("core",true),1);TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef(f.task,f.run,f.epoch,"owner","private");TaskMemoryUse use=new TaskMemoryUse(memory,new TaskStoreMemoryReader(f.tasks,null),null);
            TaskMemoryUse.Selection selection=use.prepareTaskRecall(scope(),task,"",16,12000,new MemoryHostFence(){public void check()throws Exception{f.original.check();}public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){return action.run();}}});f.tasks.prepareMemoryReference(TaskMemoryJournal.prepare(UUID.randomUUID().toString(),task,"native-project-read-reference",selection),f.original);
            long originalMemoryPreparation=f.operations();assertEquals(1,originalMemoryPreparation);JSONObject ref=f.reference(f.read(8000));assertEquals(originalMemoryPreparation,f.operations());assertEquals(1,f.outputs());memory.forgetReviewed(scope(),used.getString("id"),used.getLong("revision"),gone->f.tasks.invalidateMemoryContexts(MemoryBook.Scope.from(scope()),gone));
            assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.read(8000));assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.port.execute("project_search",new JSONObject().put("mode","path").put("query","*.txt"),f.original));assertEquals(1,f.outputs());assertEquals(originalMemoryPreparation,f.operations());assertFalse(f.raw(ref).has("body"));assertTrue(f.audit(ref).getString("body").contains("owner approved project source"));assertFalse(f.original.isCancelled());
        }
    }
}
