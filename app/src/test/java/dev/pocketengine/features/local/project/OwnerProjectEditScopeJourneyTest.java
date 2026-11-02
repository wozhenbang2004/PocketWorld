package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Real Runner -> SQLite -> production file host -> physical bytes -> original receipt.
 * Only the loopback model and explicit native owner consent are fixtures, not phone/provider claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class OwnerProjectEditScopeJourneyTest {
    private TaskStore tasks;private WorkspaceModule workspace;private LocalModule local;private FeatureHost features;private RunControl control;private CapabilityPort port;private OwnerProjectEditScope.Page page;
    private String id,run,project,checkout;private int epoch;private JSONObject task;private RunJournal journal;private final AtomicReference<RunControl> live=new AtomicReference<>();
    private SQLiteDatabase db()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    @Before public void create()throws Exception {
        var android=RuntimeEnvironment.getApplication();var context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);tasks=new TaskStore(android);features=new FeatureHost(event->{},tasks,()->false);features.register(workspace);
        project=workspace.handle("create",new JSONObject().put("name","Original owner bounded edits")).getString("id");
        seed("a.txt","A1");seed("b.txt","B1");seed("outside.txt","OUTSIDE");id=tasks.createCapabilityTask("Modify a twice and b once; create two explicitly authorized new files in the same project","").getString("id");
        tasks.initializeExecutionScope(id,40);
    }
    private void seed(String path,String text)throws Exception{workspace.handle("write",new JSONObject().put("projectId",project).put("path",path).put("content",text).put("expectedVersion",0));}
    private void start(boolean physical)throws Exception {
        task=tasks.startAttempt(id,"capabilities",40);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control=new RunControl();control.bindHostRun(id,run,epoch);live.set(control);
        if(physical){if(checkout==null)checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");tasks.bindDevelopmentContext(id,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
            local.setProjectHost(new TaskStoreProjectHost(tasks,local,key->key.equals(id)?live.get():null,event->{}));var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,"termux");
        }else port=new HostToolSupervisor(features,tasks,id,run,epoch,true);
        JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,run,names);journal=tasks.journal(id,run,epoch,control);
        page=new OwnerProjectEditScope.Page(tasks,local,workspace,key->key.equals(id)?live.get():null,id);page.setForeground(true);
    }
    private JSONObject approve(String paths,boolean create,int count)throws Exception{var selected=page.prepare(project,paths,create,30,count);return page.approve(selected,selected.view().getString("digest"));}
    private JSONObject write(String path,String content,int version)throws Exception{return new JSONObject().put("projectId",project).put("path",path).put("content",content).put("expectedVersion",version);}
    private JSONObject call(String name,JSONObject args)throws Exception{String operation=port.sideEffecting(name)?journal.beginOperation(name,args):null;JSONObject input=new JSONObject(args.toString());if(operation!=null)input.put("_hostOperationId",operation);JSONObject result=port.execute(name,input,control);if(operation!=null)journal.finishOperation(operation,result);return result;}
    private void assertSingleScope(int spent)throws Exception{JSONArray rows=tasks.get(id,false).getJSONArray("approvals");assertEquals("one bounded scope must replace per-edit prompts",1,rows.length());JSONObject scope=rows.getJSONObject(0);assertEquals(OwnerProjectEditScope.TOOL,scope.getString("tool"));assertEquals(spent,scope.getJSONArray("operationIds").length());assertEquals(0,scope.getInt("remainingOperations"));assertEquals(spent,tasks.get(id,false).getJSONObject("executionScope").getInt("toolsUsed"));assertFalse(tasks.hasUnresolved(id));}
    @Test public void workspaceOriginalModelMakesFiveCasEditsIncludingTwoNewFilesUnderOneVisibleScope()throws Exception {
        start(false);approve("a.txt\nb.txt\nnew/",true,5);
        try(Model model=new Model(false)){RunOutcome outcome=new AgentRunner(null,port).run(id,task.getString("goal"),model.config(),control,event->{},journal);assertNull(model.failure);assertTrue(outcome.summary,model.finished);assertEquals("needs_confirmation",outcome.status);}
        assertEquals("A3",workspace.handle("read",new JSONObject().put("projectId",project).put("path","a.txt")).getString("content"));assertEquals(3,workspace.fileSnapshotForHost(project,"a.txt",0).version);assertEquals(2,workspace.fileSnapshotForHost(project,"b.txt",0).version);assertEquals(1,workspace.fileSnapshotForHost(project,"new/one.txt",0).version);assertEquals(1,workspace.fileSnapshotForHost(project,"new/two.txt",0).version);assertSingleScope(5);
        JSONArray operations=tasks.get(id,false).getJSONArray("operations");assertEquals(5,operations.length());for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);assertEquals(run,row.getString("runId"));assertEquals("receipt",row.getString("state"));assertTrue(row.getJSONObject("receipt").has("ownerEditScopePermit"));}
        System.out.println("R3_AUTH02_WORKSPACE original_loopback_model_Runner_SQL_real_versions=5 edits; a:v1->v2->v3,b:v1->v2,new/one+new/two:v1; one scope; no per-edit approvals; no Android/provider claim");
    }
    @Test public void checkoutOriginalModelEditsAndPatchesExactSourceThenCreatesTwoFilesWithoutPerEditPrompts()throws Exception {
        start(true);approve("a.txt\nb.txt\nnew/",true,5);
        try(Model model=new Model(true)){RunOutcome outcome=new AgentRunner(null,port).run(id,task.getString("goal"),model.config(),control,event->{},journal);assertNull(model.failure);assertTrue(outcome.summary,model.finished);assertEquals("needs_confirmation",outcome.status);}
        Path root=RuntimeEnvironment.getApplication().getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files");assertEquals("A3",Files.readString(root.resolve("a.txt")));assertEquals("B2",Files.readString(root.resolve("b.txt")));assertEquals("ONE",Files.readString(root.resolve("new/one.txt")));assertEquals("TWO",Files.readString(root.resolve("new/two.txt")));assertSingleScope(5);
        JSONArray operations=tasks.get(id,false).getJSONArray("operations");assertEquals(5,operations.length());for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);assertEquals("receipt",row.getString("state"));assertTrue(row.getJSONObject("receipt").getJSONObject("projectExecution").has("ownerEditScopePermit"));assertEquals(run,row.getString("runId"));}
        System.out.println("R3_AUTH02_CHECKOUT original_loopback_model_Runner_SQL_ProjectCapability_Host_physical_edit_patch_CAS=5; two new explicit directory files; five original receipts, one scope; command boundary unchanged");
    }
    @Test public void checkoutScopedClaimUsesOriginalPreparedPlanAndNativeReceipt()throws Exception {
        start(true);approve("a.txt",false,1);JSONObject inspect=call("project_inspect",new JSONObject()),read=call("project_read_file",new JSONObject().put("path","a.txt"));JSONObject ref=call("project_plan",new JSONObject().put("intent","edit").put("path","a.txt").put("content","A2").put("expectedHash",read.getString("sha256")).put("expectedSourceDigest",inspect.getString("sourceDigest")));
        JSONObject result=call("project_execute",new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId",ref.getJSONArray("steps").getJSONObject(0).getString("id")));assertEquals(result.toString(),"completed",result.getString("status"));assertSingleScope(1);
    }
    @Test public void outsidePathProjectBudgetAndSensitiveActionsRemainExactAndNewFilesNeedCreationConsent()throws Exception {
        start(false);approve("a.txt\nnew/",false,1);assertEquals("approval_required",call("workspace_write",write("new/not-approved.txt","NO",0)).getString("code"));assertEquals("approval_required",call("workspace_write",write("outside.txt","NO",1)).getString("code"));
        String other=workspace.handle("create",new JSONObject().put("name","Different project")).getString("id");assertEquals("approval_required",call("workspace_write",write("a.txt","NO",0).put("projectId",other)).getString("code"));
        assertTrue(call("workspace_write",write("a.txt","A2",1)).has("ownerEditScopePermit"));assertEquals("approval_required",call("workspace_write",write("a.txt","NO",2)).getString("code"));assertEquals("approval_required",call("workspace_create_artifact",new JSONObject().put("projectId",project).put("path","a.txt")).getString("code"));assertEquals("A2",new String(workspace.fileSnapshotForHost(project,"a.txt",0).bytes(),StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,()->OwnerProjectEditScope.boundaries("src/../outside"));assertThrows(IllegalArgumentException.class,()->OwnerProjectEditScope.boundaries("src//x"));assertThrows(SecurityException.class,()->OwnerProjectEditScope.boundaries("src/*"));
    }
    @Test public void nativeReviewIsForegroundExactSingleUseAndRechecksTaskScopeRevision()throws Exception {
        start(false);var review=page.prepare(project,"a.txt",false,30,2);page.setForeground(false);assertThrows(SecurityException.class,()->page.approve(review,review.view().getString("digest")));page.setForeground(true);assertThrows(SecurityException.class,()->page.approve(review,"changed"));page.approve(review,review.view().getString("digest"));assertThrows(SecurityException.class,()->page.approve(review,review.view().getString("digest")));
        var changed=page.prepare(project,"b.txt",false,30,2);control.pause();control.resume();assertThrows(SecurityException.class,()->page.approve(changed,changed.view().getString("digest")));assertEquals("approval_required",call("workspace_write",write("a.txt","NO",1)).getString("code"));
    }
    @Test public void revocationExpiryCancellationAndStaleFileCasCannotUseRetainedPermit()throws Exception {
        start(false);JSONObject scope=approve("a.txt",false,3),args=write("a.txt","NO",1);String op=journal.beginOperation("workspace_write",args);JSONObject permit=tasks.admitOwnerWorkspaceEditScope(id,run,epoch,control,op,args,OwnerProjectEditScope.workspaceTarget(workspace,project),false);assertNotNull(permit);page.revoke(scope.getString("id"));assertThrows(SecurityException.class,()->tasks.checkOwnerEditScopePermit(permit,control));journal.finishOperation(op,new JSONObject().put("status","not_dispatched").put("accepted",false));
        scope=approve("a.txt",false,3);JSONArray approvals=tasks.get(id,false).getJSONArray("approvals");for(int i=0;i<approvals.length();i++)if(scope.getString("id").equals(approvals.getJSONObject(i).getString("id")))approvals.getJSONObject(i).put("expiresAt",System.currentTimeMillis()-1);JSONObject raw=tasks.get(id,false);raw.put("approvals",approvals);ContentValues values=new ContentValues();values.put("document",raw.toString());db().update("tasks",values,"task_id=?",new String[]{id});assertEquals("approval_required",call("workspace_write",write("a.txt","NO",1)).getString("code"));
        approve("a.txt",false,2);workspace.handle("write",write("a.txt","OWNER_NEWER",1));assertThrows(IllegalStateException.class,()->call("workspace_write",write("a.txt","OLD",1)));assertEquals("OWNER_NEWER",new String(workspace.fileSnapshotForHost(project,"a.txt",0).bytes(),StandardCharsets.UTF_8));
    }
    @Test public void realWriteWithLostOriginalReceiptStaysUnknownConsumesBudgetAndIsNeverReplayed()throws Exception {
        start(false);approve("a.txt",false,3);JSONObject args=write("a.txt","ACTUAL_ONCE",1);String operation=journal.beginOperation("workspace_write",args);JSONObject actual=port.execute("workspace_write",new JSONObject(args.toString()).put("_hostOperationId",operation),control);assertTrue(actual.has("ownerEditScopePermit"));
        // Fault at the real journal boundary: bytes were changed; host ACK is deliberately lost.
        journal.finishOperation(operation,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","fixture_receipt_lost"));assertTrue(tasks.hasUnresolved(id));assertEquals(2,tasks.ownerEditScopeSnapshot(id).getInt("remainingOperations"));assertThrows(Exception.class,()->journal.beginOperation("workspace_write",write("a.txt","REPLAY",2)));assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());assertEquals("ACTUAL_ONCE",new String(workspace.fileSnapshotForHost(project,"a.txt",0).bytes(),StandardCharsets.UTF_8));assertEquals(2,workspace.fileSnapshotForHost(project,"a.txt",0).version);
    }
    @Test public void waitingExactPromptIsReplacedOnlyByExplicitNativeScopeAndOneExplicitOriginalResume()throws Exception {
        start(false);assertEquals("approval_required",call("workspace_write",write("a.txt","A2",1)).getString("code"));tasks.endAttempt(id,run,"awaiting_user","original owner scope decision",new JSONObject());port.close();page.close();live.set(null);String oldRun=run;
        page=new OwnerProjectEditScope.Page(tasks,local,workspace,key->live.get(),id);page.setForeground(true);JSONObject scope=approve("a.txt\nb.txt",false,2);assertEquals("scope_waiting_run",scope.getString("state"));assertEquals(oldRun,tasks.get(id,false).getString("runId"));assertFalse(scope.getBoolean("automaticallyContinued"));JSONArray approvals=tasks.get(id,false).getJSONArray("approvals");assertEquals("invalidated",approvals.getJSONObject(0).getString("state"));
        page.close();start(false);assertNotEquals(oldRun,run);assertEquals("scope_active",tasks.ownerEditScopeSnapshot(id).getString("state"));assertTrue(call("workspace_write",write("a.txt","A2",1)).has("ownerEditScopePermit"));assertTrue(call("workspace_write",write("b.txt","B2",1)).has("ownerEditScopePermit"));assertEquals(3,tasks.get(id,false).getJSONObject("executionScope").getInt("toolsUsed"));
        tasks.endAttempt(id,run,"needs_confirmation","explicit run ended",new JSONObject());port.close();page.close();live.set(null);start(false);assertEquals("invalidated",tasks.ownerEditScopeSnapshot(id).getString("state"));assertEquals("approval_required",call("workspace_write",write("a.txt","NO_REGRANT",2)).getString("code"));
    }
    @Test public void taskScopeRevisionAndCancellationInvalidateAlreadyPreparedNativePermitsWithoutRefund()throws Exception {
        start(false);approve("a.txt",false,3);JSONObject args=write("a.txt","NO",1);String op=journal.beginOperation("workspace_write",args);JSONObject permit=tasks.admitOwnerWorkspaceEditScope(id,run,epoch,control,op,args,OwnerProjectEditScope.workspaceTarget(workspace,project),false);assertNotNull(permit);
        tasks.configureExecutionScope(id,1,0,40,48);assertThrows(SecurityException.class,()->tasks.checkOwnerEditScopePermit(permit,control));journal.finishOperation(op,new JSONObject().put("status","not_dispatched").put("accepted",false));assertEquals(1,tasks.get(id,false).getJSONObject("executionScope").getInt("toolsUsed"));
        approve("a.txt",false,2);String second=journal.beginOperation("workspace_write",args);JSONObject next=tasks.admitOwnerWorkspaceEditScope(id,run,epoch,control,second,args,OwnerProjectEditScope.workspaceTarget(workspace,project),false);tasks.requestCancel(id,run);control.cancel();assertThrows(Exception.class,()->tasks.checkOwnerEditScopePermit(next,control));assertEquals(2,tasks.get(id,false).getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(1,workspace.fileSnapshotForHost(project,"a.txt",0).version);
    }
    @Test public void checkoutSourceChangeAfterPlanAndSymbolicLinksNeverBecomeScopedWrites()throws Exception {
        start(true);approve("a.txt\nnew/",true,3);JSONObject inspect=call("project_inspect",new JSONObject()),read=call("project_read_file",new JSONObject().put("path","a.txt"));JSONObject ref=call("project_plan",new JSONObject().put("intent","edit").put("path","a.txt").put("content","OLD").put("expectedHash",read.getString("sha256")).put("expectedSourceDigest",inspect.getString("sourceDigest")));
        local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","a.txt").put("expectedHash",read.getString("sha256")).put("content","OWNER_NEWER"));assertThrows(SecurityException.class,()->call("project_execute",new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId",ref.getJSONArray("steps").getJSONObject(0).getString("id"))));assertEquals("OWNER_NEWER",local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","a.txt")).getString("content"));
        Path root=RuntimeEnvironment.getApplication().getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files"),outside=Files.createTempDirectory("owner-scope-no-symlink-");try{Files.createDirectories(root.resolve("new"));Files.createSymbolicLink(root.resolve("new/alias"),outside);assertThrows(SecurityException.class,()->local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","new/alias/leak.txt").put("expectedHash","absent").put("content","NO")));assertFalse(Files.exists(outside.resolve("leak.txt")));}finally{Files.deleteIfExists(root.resolve("new/alias"));Files.deleteIfExists(outside);}
    }
    @After public void close()throws Exception{if(page!=null)page.close();if(port!=null)port.close();if(features!=null)features.close();if(local!=null)local.close();if(tasks!=null)db().close();}
    private final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread worker;final boolean physical;volatile Throwable failure;int phase;boolean finished;JSONObject lastRead,inspection,proposal;String emitted="",processed="";
        Model(boolean physical)throws Exception{this.physical=physical;server.bind(new InetSocketAddress("127.0.0.1",0));worker=new Thread(this::serve,"owner-edit-scope-model-fixture");worker.setDaemon(true);worker.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-secret","bounded-edit-fixture",false,40,20).withStepCeiling(40);}
        JSONObject next(JSONObject request)throws Exception {
            JSONArray history=request.getJSONArray("messages");JSONObject last=null;for(int i=0;i<history.length();i++)if("tool".equals(history.getJSONObject(i).optString("role")))last=history.getJSONObject(i);
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))&&!emitted.equals(processed)){processed=emitted;JSONObject receipt=new JSONObject(last.getString("content"));assertFalse("scope must not request another exact approval: "+receipt,"approval_required".equals(receipt.optString("code")));assertFalse(receipt.toString(),receipt.has("error"));if(receipt.has("sourceDigest"))inspection=receipt;if(receipt.has("updatedSourceDigest"))inspection.put("sourceDigest",receipt.getString("updatedSourceDigest"));if(receipt.has("content"))lastRead=receipt;if(receipt.has("planRef"))proposal=receipt;phase++;}
            String name;JSONObject args=new JSONObject();
            if(!physical){switch(phase){case 0:case 2:case 4:name="workspace_read";args.put("projectId",project).put("path",phase==4?"b.txt":"a.txt");break;case 1:case 3:case 5:name="workspace_write";args=write(phase==5?"b.txt":"a.txt",phase==1?"A2":phase==3?"A3":"B2",lastRead.getInt("version"));break;case 6:case 7:name="workspace_write";args=write(phase==6?"new/one.txt":"new/two.txt",phase==6?"ONE":"TWO",0);break;default:finished=true;return new JSONObject().put("content","原项目五次实际文件修改和原回执已完成，未执行命令或外发。");}}
            else {switch(phase){case 0:name="project_inspect";break;case 1:case 4:case 7:name="project_read_file";args.put("path",phase==7?"b.txt":"a.txt");break;case 2:case 5:case 8:case 10:case 12:name="project_plan";args.put("intent",phase==5?"patch":"edit").put("expectedSourceDigest",inspection.getString("sourceDigest"));if(phase==5)args.put("edits",new JSONArray().put(new JSONObject().put("path","a.txt").put("expectedHash",lastRead.getString("sha256")).put("replacements",new JSONArray().put(new JSONObject().put("oldText","A2").put("newText","A3")))));else args.put("path",phase==2?"a.txt":phase==8?"b.txt":phase==10?"new/one.txt":"new/two.txt").put("content",phase==2?"A2":phase==8?"B2":phase==10?"ONE":"TWO").put("expectedHash",phase>=10?"absent":lastRead.getString("sha256"));break;case 3:case 6:case 9:case 11:case 13:name="project_execute";args.put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId",proposal.getJSONArray("steps").getJSONObject(0).getString("id"));break;default:finished=true;return new JSONObject().put("content","原副本五次实际edit/patch创建修改已完成，源码CAS和原操作回执均存在。");}}
            emitted="native-owner-edit-"+phase;return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(10000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int count=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))count=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(count<1||count>3*1024*1024)throw new IllegalArgumentException("bounded fixture request");JSONObject response=next(new JSONObject(new String(input.readNBytes(count),StandardCharsets.UTF_8))).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",response).put("finish_reason",response.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable problem){if(!server.isClosed()){failure=problem;try{server.close();}catch(Exception ignored){}}}}
        String line(InputStream input)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int value;(value=input.read())>=0;){if(value=='\n')return bytes.toString(StandardCharsets.US_ASCII).replace("\r","");if(bytes.size()>8192)throw new IllegalArgumentException("fixture header");bytes.write(value);}throw new EOFException();}
        @Override public void close()throws Exception{server.close();worker.join(2000);if(failure!=null)throw new AssertionError("actual model fixture failed",failure);}
    }
}
