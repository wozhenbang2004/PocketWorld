package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Real SQLite approval/plan/journal -> same LocalModule CAS writer -> actual private files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectExactFileOperationsNativeTest {
    private TaskStore tasks;private WorkspaceModule workspace;private LocalModule local;private ProjectCapabilityPort port;
    private RunControl original;private RunJournal journal;private String task,run,project,checkout;private int epoch;private Path files;
    private static final class NoProcess implements ProjectRuntime {
        public JSONObject describe()throws Exception{return new JSONObject().put("runtimeId","unused_file_operation_fixture").put("canExecute",false).put("available",false).put("phases",new JSONArray());}
        public RuntimeReceipt dispatch(RuntimeInvocation ignored){throw new AssertionError("File patch must not launch a process");}
        public RuntimeReceipt query(RuntimeExecutionRef ignored){throw new AssertionError("File patch must not query a process");}
        public RuntimeReceipt requestCancel(RuntimeExecutionRef ignored){throw new AssertionError("File patch must not cancel a process");}
    }
    @Before public void create()throws Exception{
        Application android=RuntimeEnvironment.getApplication();android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,new NoProcess());
        project=workspace.handle("create",new JSONObject().put("name","Exact original file operations")).getString("id");
        workspace.handle("write",new JSONObject().put("projectId",project).put("path","source.txt").put("expectedVersion",0).put("content","source 中文🙂\n"));
        workspace.handle("write",new JSONObject().put("projectId",project).put("path","target.txt").put("expectedVersion",0).put("content","before\n"));
        checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");files=new File(android.getFilesDir(),"local-development/checkouts/"+checkout+"/files").toPath();
        task=tasks.createCapabilityTask("Copy and append in the original selected checkout","").getString("id");JSONObject attempt=tasks.startAttempt(task,"capabilities",20);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(task,run,epoch);
        tasks.bindDevelopmentContext(task,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",digest()).put("clientMessageId",UUID.randomUUID().toString()));journal=tasks.journal(task,run,epoch,original);
        local.setProjectHost(new TaskStoreProjectHost(tasks,local,id->task.equals(id)?original:null,event->{}));
        var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,"termux");
        tasks.authorizeTools(task,run,new JSONArray().put("project_inspect").put("project_read_file").put("project_search").put("project_plan").put("project_execute"));
    }
    @After public void close()throws Exception{
        try{if(port!=null)port.close();}
        finally{try{if(local!=null)local.close();}finally{if(workspace!=null)workspace.close();if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}}}
    }
    private String sha(String text)throws Exception{return ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private String digest()throws Exception{return ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");}
    private JSONObject copy()throws Exception{return new JSONObject().put("action","copy").put("fromPath","source.txt").put("expectedSourceHash",sha("source 中文🙂\n")).put("path","nested/copied.txt").put("expectedHash","absent");}
    private JSONObject append()throws Exception{return new JSONObject().put("action","append").put("path","target.txt").put("expectedHash",sha("before\n")).put("content","after🙂\n");}
    private JSONObject propose(JSONArray edits)throws Exception{return port.execute("project_plan",new JSONObject().put("intent","patch").put("expectedSourceDigest",digest()).put("edits",edits),original);}
    private JSONObject arguments(JSONObject proposal)throws Exception{return new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId","patch");}
    private void approve(JSONObject args)throws Exception{
        String operation=journal.beginOperation("project_execute",args);JSONObject pending=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),original);journal.finishOperation(operation,pending);assertEquals("approval_required",pending.getString("code"));
        assertFalse(Files.exists(files.resolve("nested/copied.txt")));assertEquals("before\n",Files.readString(files.resolve("target.txt")));tasks.decideCapabilityApproval(task,pending.getString("approvalId"),true);
    }
    @Test public void copyAndAppendUseTheOriginalReviewedOperationAndActualAfterShaReceipts()throws Exception{
        int initialTasks=tasks.list().length();JSONObject proposal=propose(new JSONArray().put(copy()).put(append())),args=arguments(proposal);assertTrue(proposal.has("planRef"));approve(args);
        String operation=journal.beginOperation("project_execute",args);JSONObject receipt=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),original);journal.finishOperation(operation,receipt);
        assertEquals("completed",receipt.getString("status"));assertTrue(receipt.getBoolean("receiptReceived"));assertEquals(2,receipt.getInt("appliedFiles"));assertFalse(receipt.getBoolean("crossFileAtomic"));assertFalse(receipt.getBoolean("businessVerified"));
        assertEquals("source 中文🙂\n",Files.readString(files.resolve("source.txt")));assertEquals("source 中文🙂\n",Files.readString(files.resolve("nested/copied.txt")));assertEquals("before\nafter🙂\n",Files.readString(files.resolve("target.txt")));
        for(int i=0;i<receipt.getJSONArray("files").length();i++){JSONObject row=receipt.getJSONArray("files").getJSONObject(i);assertEquals("applied",row.getString("state"));assertEquals(sha(Files.readString(files.resolve(row.getString("path")))),row.getString("actualAfterHash"));}
        assertEquals(initialTasks,tasks.list().length());assertFalse(tasks.hasUnresolved(task));assertEquals(2,tasks.get(task,false).getJSONArray("operations").length());assertEquals("before\n",workspace.handle("read",new JSONObject().put("projectId",project).put("path","target.txt")).getString("content"));
    }
    @Test public void changedFrozenSourceAfterApprovalCannotCreateTheCopy()throws Exception{
        JSONObject args=arguments(propose(new JSONArray().put(copy())));approve(args);Files.writeString(files.resolve("source.txt"),"owner changed source\n");String operation=journal.beginOperation("project_execute",args);
        assertThrows(SecurityException.class,()->port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),original));assertFalse(Files.exists(files.resolve("nested/copied.txt")));assertEquals("owner changed source\n",Files.readString(files.resolve("source.txt")));
    }
    @Test public void cancellationBeforeDispatchCannotAppendOrBecomeACompletedReceipt()throws Exception{
        JSONObject args=arguments(propose(new JSONArray().put(append())));approve(args);String operation=journal.beginOperation("project_execute",args);original.cancel();
        assertThrows(Exception.class,()->port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),original));assertEquals("before\n",Files.readString(files.resolve("target.txt")));assertFalse(Files.exists(files.resolve("nested/copied.txt")));
    }
    @Test public void regexAndContextAreAdvertisedValidatedAndReadThroughTheActualOriginalPort()throws Exception{
        JSONObject schema=null;JSONArray tools=port.tools();for(int i=0;i<tools.length();i++){JSONObject function=tools.getJSONObject(i).getJSONObject("function");if("project_search".equals(function.getString("name")))schema=function.getJSONObject("parameters");}
        assertNotNull(schema);assertTrue(schema.getJSONObject("properties").has("syntax"));assertEquals(20,schema.getJSONObject("properties").getJSONObject("contextAfter").getInt("maximum"));
        JSONObject input=new JSONObject().put("mode","text").put("syntax","regex").put("query","source\\s+中文.").put("contextBefore",2).put("contextAfter",2);port.validate("project_search",input);JSONObject result=port.execute("project_search",input,original);
        assertEquals("RE2J_1.8",result.getString("regexEngine"));assertEquals(1,result.getJSONArray("matches").length());assertTrue(result.getBoolean("contextComplete"));JSONObject block=result.getJSONArray("contextBlocks").getJSONObject(0);assertEquals("source.txt",block.getString("path"));assertEquals(sha("source 中文🙂\n"),block.getString("sha256"));assertEquals("source 中文🙂",block.getJSONArray("lines").getJSONObject(0).getString("text"));assertEquals(0,tasks.get(task,false).getJSONArray("operations").length());
    }
    @Test public void repeatedTextAndEofInsertionExecuteOnlyAtTheReviewedExactOffsets()throws Exception{
        Files.writeString(files.resolve("source.txt"),"same\nsame\n");JSONArray edits=new JSONArray().put(new JSONObject().put("path","source.txt").put("expectedHash",sha("same\nsame\n")).put("replacements",new JSONArray().put(new JSONObject().put("oldText","same").put("newText","chosen").put("startOffset",5))))
            .put(new JSONObject().put("path","target.txt").put("expectedHash",sha("before\n")).put("replacements",new JSONArray().put(new JSONObject().put("oldText","").put("newText","tail🙂\n").put("startOffset",7))));
        JSONObject args=arguments(propose(edits));approve(args);String operation=journal.beginOperation("project_execute",args);JSONObject receipt=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),original);journal.finishOperation(operation,receipt);
        assertEquals("completed",receipt.getString("status"));assertEquals("same\nchosen\n",Files.readString(files.resolve("source.txt")));assertEquals("before\ntail🙂\n",Files.readString(files.resolve("target.txt")));assertFalse(tasks.hasUnresolved(task));
    }
    @Test public void halfEmojiOffsetIsRejectedAtPlanningWithoutAnyFileOperation()throws Exception{
        JSONObject edit=new JSONObject().put("path","source.txt").put("expectedHash",sha("source 中文🙂\n")).put("replacements",new JSONArray().put(new JSONObject().put("oldText","").put("newText","x").put("startOffset",10)));
        JSONObject result=propose(new JSONArray().put(edit));assertEquals("project_plan_rejected",result.getString("code"));assertEquals("source 中文🙂\n",Files.readString(files.resolve("source.txt")));assertEquals(0,tasks.get(task,false).getJSONArray("operations").length());
    }
}
