package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.runtime.AgentRunner;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Actual host Git, JNI job and original SQLite/Runner. Not ARM/PRoot/visual or real-model evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectGitIsolationNativeJourneyTest {
    private ProjectGitNativeJourneyTest.Fixture f;
    @Before public void create()throws Exception {f=new ProjectGitNativeJourneyTest.Fixture();f.seed();}
    @After public void close()throws Exception {if(f!=null)f.close();}
    private static String text(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static void write(Path path,String value)throws Exception{Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    private JSONObject isolationRequest(String branch)throws Exception{return new JSONObject().put("gitAction","isolate").put("gitPin",f.status().getJSONObject("pin")).put("baseCommit",f.git("rev-parse","HEAD")).put("branch",branch);}
    private JSONObject isolate(String branch)throws Exception{return f.finish(f.features.submitProjectGitForOwner(f.local.reviewGitForOwner(f.checkout,isolationRequest(branch)).confirm()));}
    private Path pool(String id){return f.root.getParent().getParent().resolve(id);}
    @Test public void actualPrivateCommonWorktreePreservesSourceWipRefsAndSensitiveConfiguration()throws Exception {
        Files.createDirectories(f.root.resolve("docs"));write(f.root.resolve("docs/\uE001.py"),"private-use UTF8 filename\n");write(f.root.resolve("docs/😀.py"),"supplementary UTF8 filename\n");f.stage("docs/\uE001.py","docs/😀.py");f.commit("docs/\uE001.py","docs/😀.py");
        f.write("main.py","print('source WIP must survive')\n");f.write("other.py","print('source staged must survive')\n");f.stage("other.py");
        f.git("config","remote.origin.url","https://fixture-secret@example.invalid/not-contacted");
        Path hook=f.root.resolve(".git/hooks/post-checkout");Files.createDirectories(hook.getParent());write(hook,"#!/bin/sh\necho UNSAFE > must-not-run\n");hook.toFile().setExecutable(true);
        String head=f.git("rev-parse","HEAD"),refs=f.git("show-ref"),index=ProjectSources.sha256(Files.readAllBytes(f.root.resolve(".git/index"))),source=f.digest();
        JSONObject receipt=isolate("feature/private-copy");assertTrue(ProjectGitNativeJourneyTest.safe(receipt),receipt.getJSONObject("git").getBoolean("ok"));JSONObject created=receipt.getJSONObject("git").getJSONObject("isolatedCheckout");String id=created.getString("id");
        Path root=pool(id).resolve("files");assertTrue(Files.isRegularFile(root.resolve(".git")));assertEquals("print('base')\n",text(root.resolve("main.py")));assertEquals("print('other')\n",text(root.resolve("other.py")));assertEquals("private-use UTF8 filename\n",text(root.resolve("docs/\uE001.py")));assertEquals("supplementary UTF8 filename\n",text(root.resolve("docs/😀.py")));
        assertEquals(head,f.git("rev-parse","HEAD"));assertEquals(refs,f.git("show-ref"));assertEquals(index,ProjectSources.sha256(Files.readAllBytes(f.root.resolve(".git/index"))));assertEquals(source,f.digest());
        assertFalse(text(pool(id).resolve("common/config")).contains("remote"));assertFalse(text(pool(id).resolve("common/config")).contains("fixture-secret"));assertFalse(Files.exists(pool(id).resolve("common/hooks/post-checkout")));assertFalse(Files.exists(root.resolve("must-not-run")));
        JSONObject owner=created.getJSONObject("isolatedGit");assertEquals(receipt.getString("taskId"),owner.getString("ownerTaskId"));assertEquals(f.checkout,owner.getString("sourceCheckoutId"));assertEquals(head,owner.getString("baseCommit"));
        assertEquals(id,f.local.relatedGitCheckoutForOwner(f.checkout,id).getString("id"));assertEquals(f.checkout,f.local.relatedGitCheckoutForOwner(id,f.checkout).getString("id"));
        JSONObject childStatus=f.finish(f.features.submitProjectGitForOwner(f.local.reviewGitForOwner(id,new JSONObject().put("gitAction","status")).confirm()));assertTrue(ProjectGitNativeJourneyTest.safe(childStatus),childStatus.getJSONObject("git").getBoolean("ok"));assertEquals(head,childStatus.getJSONObject("git").getJSONObject("data").getJSONObject("pin").getString("head"));assertNull(f.callbackFailure.get());
    }
    @Test public void staleReviewExistingTargetAndMaliciousBranchNeverOverwriteOriginalOrFailedPool()throws Exception {
        assertThrows(Exception.class,()->f.local.reviewGitForOwner(f.checkout,isolationRequest("--upload-pack=bad")));
        var reviewed=f.local.reviewGitForOwner(f.checkout,isolationRequest("feature/preserved"));String target=reviewed.review().getString("targetCheckoutId");Files.createDirectory(pool(target));write(pool(target).resolve("keep.txt"),"failed pool belongs to another attempt");
        JSONObject rejected=f.features.submitProjectGitForOwner(reviewed.confirm());assertFalse(rejected.getJSONObject("git").getBoolean("ok"));assertEquals("project_git_preflight_rejected",rejected.getJSONObject("git").getString("code"));assertEquals("failed pool belongs to another attempt",text(pool(target).resolve("keep.txt")));assertFalse(Files.exists(pool(target).resolve("checkout.json")));
        var stale=f.local.reviewGitForOwner(f.checkout,isolationRequest("feature/stale"));f.write("main.py","print('new source after review')\n");JSONObject noDispatch=f.features.submitProjectGitForOwner(stale.confirm());assertEquals("project_git_preflight_rejected",noDispatch.getJSONObject("git").getString("code"));assertFalse(Files.exists(pool(stale.review().getString("targetCheckoutId"))));assertTrue(text(f.root.resolve("main.py")).contains("new source"));
    }
    @Test public void onlyOwnedGitfileIsAcceptedAndFailedTreeKeepsOriginalSelectionAndPool()throws Exception {
        f.git("symbolic-ref","HEAD","refs/heads/main");f.git("update-index","--add","--cacheinfo","120000,"+f.git("hash-object","-w","--","main.py")+",bad-link");
        // Use real Git to create an unsafe tracked symlink commit; no link is followed by the adapter.
        f.git("commit","-m","unsafe symlink fixture");JSONObject reviewed=isolationRequest("feature/no-links");var selection=f.local.reviewGitForOwner(f.checkout,reviewed);String id=selection.review().getString("targetCheckoutId");JSONObject result=f.finish(f.features.submitProjectGitForOwner(selection.confirm()));assertFalse(result.getJSONObject("git").getBoolean("ok"));assertEquals("git_isolation_tree_unsupported",result.getJSONObject("git").getString("code"));assertTrue(Files.exists(pool(id).resolve(ProjectGitIsolation.OWNER_FILE)));assertFalse(Files.exists(pool(id).resolve("checkout.json")));assertFalse(Files.exists(pool(id).resolve("common")));
        Path metadata=f.root.resolve(".git"),saved=f.root.resolve(".pocketworld/unowned-metadata");Files.createDirectories(saved.getParent());Files.move(metadata,saved);write(metadata,"gitdir: "+saved+"\n");JSONObject external=f.owner(new JSONObject().put("gitAction","status"));assertFalse(external.getJSONObject("git").getBoolean("ok"));assertEquals("git_repository_layout_unsupported",external.getJSONObject("git").getString("code"));
    }
    @Test public void cancellationAfterActualWorktreeCreationKeepsFailedPoolAndNeverPublishesOrReplaysIt()throws Exception {
        f.close();f=new ProjectGitNativeJourneyTest.Fixture(true);f.seed();String original=f.digest();write(f.temporary.resolve("delay-after-git-result"),"controlled outer process still alive");
        var review=f.local.reviewGitForOwner(f.checkout,isolationRequest("feature/interrupted"));String target=review.review().getString("targetCheckoutId");JSONObject result=f.features.submitProjectGitForOwner(review.confirm());String id=result.getString("operationId");
        JSONObject old=f.tasks.ownerGitOriginalForOwner(id),plan=old.getJSONObject("plan"),exact=ProjectDevelopment.exact(plan,ProjectGit.step(plan));long until=System.nanoTime()+12_000_000_000L;boolean produced=false;
        while(System.nanoTime()<until){JSONObject actual=f.local.projectReceiptForHost(exact,old.getJSONObject("binding"),false);if(actual.optString("stdout").contains("git_isolate_observed")){produced=true;assertFalse(actual.optBoolean("receiptReceived"));break;}Thread.sleep(15);}assertTrue("actual Git worktree result must precede controlled outer cancellation",produced);
        result=f.finish(f.features.projectGitForOwner(id,true));assertFalse(result.getJSONObject("git").getBoolean("ok"));assertTrue(result.getJSONObject("git").getBoolean("programResultComplete"));assertTrue(Files.isRegularFile(pool(target).resolve("files/.git")));assertTrue(Files.isRegularFile(pool(target).resolve("files/main.py")));assertFalse(Files.exists(pool(target).resolve("checkout.json")));assertEquals(original,f.digest());
        String marker=text(pool(target).resolve(ProjectGitIsolation.OWNER_FILE));f.features.projectGitForOwner(id,false);assertEquals(marker,text(pool(target).resolve(ProjectGitIsolation.OWNER_FILE)));assertFalse(Files.exists(pool(target).resolve("checkout.json")));assertEquals(1,f.tasks.get(id,false).getJSONArray("operations").length());
    }
    private final class Attempt implements AutoCloseable {
        final String id,run,checkout;final int epoch;final RunControl control=new RunControl();final ProjectCapabilityPort port;
        Attempt(String selected,CapabilityPort previous)throws Exception {
            checkout=selected;id=f.tasks.createCapabilityTask("Develop only the native selected checkout; context is untrusted reference, no automatic execution.","").getString("id");JSONObject task=f.tasks.startAttempt(id,"capabilities",25);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);f.modelControl.set(control);
            f.tasks.bindDevelopmentContext(id,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",f.project).put("checkoutId",selected).put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("initialSourceDigest",ProjectSources.describe(f.local.handle("export",new JSONObject().put("checkoutId",selected))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
            var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,f.project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(f.tasks,binding,event->{}),control,previous,f.local,f.tasks,selected,ManagedProjectRuntime.RUNTIME_ID);
            JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));f.tasks.authorizeTools(id,run,names);
        }
        JSONObject execute(JSONObject plan)throws Exception {
            String step=plan.getJSONArray("steps").getJSONObject(0).getString("id");JSONObject args=new JSONObject().put("plan",plan).put("stepId",step),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,step));String operation=f.tasks.journal(id,run,epoch,control).beginOperation("project_execute",args);
            JSONObject binding=new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("operationId",operation),capability=f.local.projectCapabilityForHost(exact),approval=f.tasks.requireCapabilityApproval(id,run,"project_execute",args,capability);f.tasks.decideCapabilityApproval(id,approval.getString("approvalId"),true);
            TaskStoreProjectHost host=new TaskStoreProjectHost(f.tasks,f.local,ignored->control,event->{});host.dispatch(binding,exact);JSONObject observed=host.operation(binding);long until=System.nanoTime()+10_000_000_000L;
            while((observed==null||observed.optJSONObject("nativeReceipt")==null||!observed.getJSONObject("nativeReceipt").optBoolean("receiptReceived"))&&System.nanoTime()<until){Thread.sleep(15);observed=host.operation(binding);}assertNotNull(observed);assertTrue(observed.toString(),observed.getJSONObject("nativeReceipt").getBoolean("receiptReceived"));return observed.getJSONObject("nativeReceipt");
        }
        public void close()throws Exception{port.close();f.tasks.endAttempt(id,run,"paused","Original native fixture ended; no process replay",new JSONObject());control.cancel();}
    }
    @Test public void selectedIsolationUsesOriginalApprovedTaskEditsRunsAndFinalModelRequestContext()throws Exception {
        write(f.root.resolve("index.html"),"<html><body>Private checkout preview</body></html>");f.stage("index.html");f.commit("index.html");
        JSONObject created=isolate("feature/actual-task").getJSONObject("git").getJSONObject("isolatedCheckout");String selected=created.getString("id");
        CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new SecurityException();}public JSONObject contextSnapshot(String query)throws Exception{return new JSONObject().put("context","EXISTING_LOW_TRUST_REFERENCE");}};
        try(Attempt a=new Attempt(selected,existing)){
            String digest=ProjectSources.describe(f.local.handle("export",new JSONObject().put("checkoutId",selected))).getString("sourceDigest"),before=f.local.handle("readFile",new JSONObject().put("checkoutId",selected).put("path","main.py")).getString("sha256");
            JSONObject edit=f.local.handle("project.plan",new JSONObject().put("intent","edit").put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("checkoutId",selected).put("expectedSourceDigest",digest).put("path","main.py").put("content","from pathlib import Path\nPath('isolated-result.txt').write_text('original-task-result')\nprint(Path.cwd())\n").put("expectedHash",before));assertEquals("completed",a.execute(edit).getString("status"));
            digest=ProjectSources.describe(f.local.handle("export",new JSONObject().put("checkoutId",selected))).getString("sourceDigest");JSONObject command=f.local.handle("project.plan",new JSONObject().put("intent","command").put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("checkoutId",selected).put("expectedSourceDigest",digest).put("executable","python3").put("argv",new JSONArray().put("main.py")));JSONObject ran=a.execute(command);assertEquals(0,ran.getInt("exitCode"));assertTrue(ran.getString("stdout").contains(pool(selected).resolve("files").toString()));assertEquals("original-task-result",text(pool(selected).resolve("files/isolated-result.txt")));assertEquals("print('base')\n",text(f.root.resolve("main.py")));assertFalse(Files.exists(f.root.resolve("isolated-result.txt")));
            JSONObject preview=a.port.execute("project_preview",new JSONObject().put("entryPath","index.html").put("expectedSourceDigest",ProjectSources.describe(f.local.handle("export",new JSONObject().put("checkoutId",selected))).getString("sourceDigest")),a.control);
            try(FixtureModel model=new FixtureModel()){
                new AgentRunner(null,a.port).run(a.id,"Describe the current selected project only.",model.config(),a.control,event->{},f.tasks.journal(a.id,a.run,a.epoch,a.control));model.thread.join(2000);assertNull(model.failure);assertNotNull(model.request);
                String messages=model.request.getJSONArray("messages").toString();assertTrue(messages,messages.contains("EXISTING_LOW_TRUST_REFERENCE"));assertTrue(messages,messages.contains(selected));assertTrue(messages,messages.contains(a.run));assertTrue(messages,messages.contains("native_selected_checkout"));assertTrue(messages,messages.contains("private_common_worktree"));assertTrue(messages,messages.contains("先inspect"));assertTrue(messages,messages.contains("checkout_snapshot"));assertTrue(messages,messages.contains(preview.getString("previewId")));assertFalse(messages,messages.contains(pool(selected).toString()));
            }
            assertEquals(selected,f.tasks.get(a.id,false).getJSONObject("developmentContext").getString("checkoutId"));assertEquals(2,f.tasks.get(a.id,false).getJSONArray("operations").length());assertNull(f.callbackFailure.get());
        }
        JSONObject reviewed=f.local.handle("reviewFile",new JSONObject().put("checkoutId",selected).put("path","main.py"));int version=reviewed.getInt("expectedVersion");JSONObject input=new JSONObject().put("checkoutId",selected).put("path","main.py").put("expectedHash",reviewed.getString("expectedHash")).put("expectedVersion",version);
        assertThrows(Exception.class,()->f.local.handle("importToProject",new JSONObject(input.toString()).put("expectedVersion",version-1)));assertEquals("print('base')\n",f.workspace.handle("read",new JSONObject().put("projectId",f.project).put("path","main.py")).getString("content"));
        JSONObject imported=f.local.handle("importToProject",input);assertEquals(version+1,imported.getInt("version"));assertTrue(f.workspace.handle("read",new JSONObject().put("projectId",f.project).put("path","main.py")).getString("content").contains("original-task-result"));assertEquals("print('base')\n",text(f.root.resolve("main.py")));assertTrue(Files.isRegularFile(pool(selected).resolve("files/isolated-result.txt")));
    }
    @Test public void oldControlPausedTaskAndReplacedCheckoutCannotProvideOldReference()throws Exception {
        try(Attempt a=new Attempt(f.checkout,null)){
            a.port.prepareTurn("current",0,0,a.control);assertThrows(Exception.class,()->a.port.beforeModelRequest(UUID.randomUUID().toString(),new JSONArray(),new RunControl()));a.control.pause();assertThrows(Exception.class,()->a.port.prepareTurn("paused",1,0,a.control));assertFalse(a.port.responseCurrent(a.control));a.control.resume();
            Path metadata=f.root.getParent().resolve("checkout.json");JSONObject original=new JSONObject(text(metadata));write(metadata,new JSONObject(original.toString()).put("createdAt",original.getLong("createdAt")+1).toString());assertThrows(Exception.class,()->a.port.contextSnapshot("replaced"));assertFalse(a.port.responseCurrent(a.control));write(metadata,original.toString());
        }
    }
    @Test public void generationOrPauseChangedAfterPrepareRejectsOldRequestAndAnswer()throws Exception {
        try(Attempt a=new Attempt(f.checkout,null)){
            // DeviceGoal/PlanWorkspace wrappers already project contextSnapshot themselves.
            a.port.contextSnapshot("original wrapper reference");a.port.beforeModelRequest(UUID.randomUUID().toString(),new JSONArray(),a.control);
            a.port.prepareTurn("current",0,0,a.control);a.control.pause();a.control.resume();assertThrows(Exception.class,()->a.port.beforeModelRequest(UUID.randomUUID().toString(),new JSONArray(),a.control));assertFalse(a.port.responseCurrent(a.control));
            a.port.prepareTurn("fresh",1,0,a.control);assertTrue(a.port.responseCurrent(a.control));
            var field=dev.pocketengine.features.local.LocalModule.class.getDeclaredField("runtimes");field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,dev.pocketengine.features.local.runtime.ProjectRuntime> runtimes=(Map<String,dev.pocketengine.features.local.runtime.ProjectRuntime>)field.get(f.local);
            var actual=runtimes.get(ManagedProjectRuntime.RUNTIME_ID);runtimes.put(ManagedProjectRuntime.RUNTIME_ID,new dev.pocketengine.features.local.runtime.ProjectRuntime(){public JSONObject describe()throws Exception{return new JSONObject(actual.describe().toString()).put("identity","e".repeat(64));}public dev.pocketengine.features.local.runtime.RuntimeReceipt dispatch(dev.pocketengine.features.local.runtime.RuntimeInvocation request){throw new AssertionError("read-only runtime-change fixture cannot dispatch");}public dev.pocketengine.features.local.runtime.RuntimeReceipt query(dev.pocketengine.features.local.runtime.RuntimeExecutionRef ref)throws Exception{return actual.query(ref);}public dev.pocketengine.features.local.runtime.RuntimeReceipt requestCancel(dev.pocketengine.features.local.runtime.RuntimeExecutionRef ref)throws Exception{return actual.requestCancel(ref);}});
            try{assertThrows(Exception.class,()->a.port.beforeModelRequest(UUID.randomUUID().toString(),new JSONArray(),a.control));assertFalse(a.port.responseCurrent(a.control));JSONObject fresh=a.port.prepareTurn("new metadata only",2,0,a.control);assertEquals("e".repeat(64),fresh.getJSONObject("reference").getJSONObject("developmentProject").getString("runtimeIdentity"));}finally{runtimes.put(ManagedProjectRuntime.RUNTIME_ID,actual);}
        }
    }
    @Test public void evictedActualPreviewIsNotForwardedAsCurrentProjectReference()throws Exception {
        write(f.root.resolve("index.html"),"<html><body>old owned snapshot</body></html>");
        try(Attempt a=new Attempt(f.checkout,null)){
            JSONObject input=new JSONObject().put("checkoutId",f.checkout).put("entryPath","index.html").put("expectedSourceDigest",f.digest());JSONObject args=new JSONObject(input.toString());args.remove("checkoutId");JSONObject original=a.port.execute("project_preview",args,a.control);
            assertEquals(original.getString("previewId"),a.port.prepareTurn("current preview",0,0,a.control).getJSONObject("reference").getJSONObject("developmentProject").getJSONObject("preview").getString("previewId"));
            for(int i=0;i<3;i++){write(f.root.resolve("index.html"),"<html><body>another actual snapshot "+i+"</body></html>");f.local.handle("project.preview",new JSONObject().put("checkoutId",f.checkout).put("entryPath","index.html").put("expectedSourceDigest",f.digest()));}
            assertThrows(IllegalStateException.class,()->f.local.projectPreviewForOwner(original.getString("previewId"),f.checkout));assertFalse(a.port.prepareTurn("cache evicted",1,0,a.control).getJSONObject("reference").getJSONObject("developmentProject").has("preview"));
        }
    }
    private static final class FixtureModel implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());final Thread thread;volatile JSONObject request;volatile Throwable failure;
        FixtureModel()throws Exception{thread=new Thread(()->{try(Socket socket=server.accept()){InputStream in=new BufferedInputStream(socket.getInputStream());line(in);int length=0;for(String header;!(header=line(in)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IOException("fixture request bound");request=new JSONObject(new String(in.readNBytes(length),StandardCharsets.UTF_8));byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","Native selected checkout observed; no new execution.")).put("finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed())failure=error;}},"owned-project-context-model-fixture");thread.setDaemon(true);thread.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","local-fixture-not-a-secret","project-context-fixture",false,4,10);}
        private static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int next;(next=in.read())!=-1;){if(next=='\n')break;if(next!='\r')out.write(next);}return out.toString(StandardCharsets.US_ASCII);}
        public void close()throws Exception{server.close();thread.join(2000);}
    }

}
