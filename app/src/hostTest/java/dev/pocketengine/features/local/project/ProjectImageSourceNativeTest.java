package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Real native codec/SQLite/catalog/checkout bytes. No model, permission or image decoder stub. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ProjectImageSourceNativeTest {
    static byte[] png(int color)throws Exception{Bitmap bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);ByteArrayOutputStream out=new ByteArrayOutputStream();try{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));return out.toByteArray();}finally{bitmap.recycle();}}
    static final class NoProcess implements ProjectRuntime {
        public JSONObject describe()throws Exception{return new JSONObject().put("runtimeId","image_read_fixture").put("identity","explicit_no_process_image_fixture").put("canExecute",false).put("available",false).put("phases",new JSONArray());}
        public RuntimeReceipt dispatch(RuntimeInvocation ignored){throw new AssertionError("Image reads must not launch a process");}
        public RuntimeReceipt query(RuntimeExecutionRef ignored){throw new AssertionError("Image reads must not query a process");}
        public RuntimeReceipt requestCancel(RuntimeExecutionRef ignored){throw new AssertionError("Image reads must not cancel a process");}
    }
    static final class F implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final WorkspaceModule workspace;final LocalModule local;final ProjectCapabilityPort port;
        final String project,checkout,task,run;final int epoch;final RunControl control;final RunJournal journal;final Path files;final byte[] pixels;final String sha;final JSONArray grants=new JSONArray();
        F()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,new NoProcess());
            project=workspace.handle("create",new JSONObject().put("name","Original project image")).getString("id");pixels=png(Color.RED);sha=ProjectSources.sha256(pixels);workspace.writeBytesForHost(workspace.fileSnapshotForHost(project,"asset.png",0),pixels);
            checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");files=android.getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files");
            task=tasks.createCapabilityTask("Read the exact selected project image","").getString("id");tasks.initializeExecutionScope(task,20);JSONObject attempt=tasks.startAttempt(task,"capabilities",20);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control=new RunControl();control.bindHostRun(task,run,epoch);
            String source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");tasks.bindDevelopmentContext(task,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",source).put("clientMessageId",UUID.randomUUID().toString()));
            journal=tasks.journal(task,run,epoch,control);local.setProjectHost(new TaskStoreProjectHost(tasks,local,id->task.equals(id)?control:null,event->{}));var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,"termux");
            JSONArray tools=port.tools();for(int i=0;i<tools.length();i++)grants.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,grants);
        }
        JSONObject args()throws Exception{return new JSONObject().put("checkout",checkout).put("path","asset.png").put("expectedSha",sha);}
        JSONObject artifact()throws Exception{return workspace.freezeFileForHost(workspace.fileSnapshotForHost(project,"asset.png",0),UUID.randomUUID().toString(),null,null).json();}
        NativeToolObservation.Image read(JSONObject args)throws Exception{String operation=UUID.randomUUID().toString();JSONObject receipt=port.execute("project_view_image",new JSONObject(args.toString()).put("_hostOperationId",operation),control);assertEquals("captured",receipt.getString("status"));assertFalse(receipt.getBoolean("actualImageAttached"));assertFalse(receipt.toString().contains("base64"));return port.takeToolObservation("project_view_image",operation,control).consume("project_view_image",operation,control);}
        @Override public void close()throws Exception{control.cancel();try{port.close();}finally{try{local.close();}finally{workspace.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}}}
    }
    @Test public void planningClassifiesOriginalReadsAndRejectsProposalAndPreviewWrites()throws Exception{
        try(F f=new F()){
            for(String name:new String[]{"project_inspect","project_read_file","project_view_image","project_search","project_list_files","project_plan_manifest","project_status","project_log","project_changes","project_review_file"})assertEquals(name,ExecutionPlanning.Access.READ,f.port.planningAccess(name,null));
            assertEquals(ExecutionPlanning.Access.READ,f.port.planningAccess("project_view_image",f.args()));
            assertThrows(SecurityException.class,()->f.port.planningAccess("project_view_image",f.args().put("checkout",UUID.randomUUID().toString())));
            for(String name:new String[]{"project_plan","project_collect_plan","project_preview","project_live_preview","project_execute","project_stop","project_process_input","project_generated_artifact"})assertEquals(name,ExecutionPlanning.Access.DENY,f.port.planningAccess(name,null));
            assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());
        }
    }
    @Test public void originalCheckoutAndFixedArtifactDeliverExactPixelsWithoutNewEffect()throws Exception{
        try(F f=new F()){assertEquals(NativeToolObservation.Requirement.IMAGE,f.port.observationRequirement("project_view_image",f.args(),f.control));assertEquals(NativeToolObservation.Requirement.NONE,f.port.observationRequirement("project_preview",new JSONObject(),f.control));assertFalse(f.port.sideEffecting("project_view_image"));
            try(var image=f.read(f.args())){assertArrayEquals(f.pixels,Base64.getDecoder().decode(image.base64(f.control)));assertEquals(3,image.metadata().getInt("imageWidthPx"));assertEquals(2,image.metadata().getInt("imageHeightPx"));}
            JSONObject fixed=f.artifact();Files.write(f.files.resolve("asset.png"),png(Color.BLUE));try(var image=f.read(new JSONObject().put("artifactId",fixed.getString("id")).put("sourceSha",fixed.getString("sha256")))){assertArrayEquals(f.pixels,Base64.getDecoder().decode(image.base64(f.control)));assertEquals("workspace_artifact",image.metadata().getString("sourceKind"));}
            assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());
        }
    }
    @Test public void staleShaFakeExtensionMalformedImageAndOversizeRejectRealBytes()throws Exception{
        try(F f=new F()){
            assertThrows(SecurityException.class,()->f.read(f.args().put("expectedSha","0".repeat(64))));Files.write(f.files.resolve("fake.jpg"),f.pixels);assertThrows(SecurityException.class,()->f.read(f.args().put("path","fake.jpg")));
            byte[] fake="not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(f.files.resolve("fake.png"),fake);assertThrows(Exception.class,()->f.read(f.args().put("path","fake.png").put("expectedSha",ProjectSources.sha256(fake))));
            byte[] huge=new byte[ProjectSources.MAX_FILE_BYTES+1];Files.write(f.files.resolve("huge.png"),huge);assertThrows(Exception.class,()->f.read(f.args().put("path","huge.png").put("expectedSha",ProjectSources.sha256(huge))));
        }
    }
    @Test public void wrongCheckoutEscapingPathLinkAndMixedShapeNeverProduceImage()throws Exception{
        try(F f=new F()){
            assertThrows(SecurityException.class,()->f.read(f.args().put("checkout",UUID.randomUUID().toString())));
            assertThrows(Exception.class,()->f.read(f.args().put("path","../asset.png")));assertThrows(Exception.class,()->f.read(f.args().put("path",f.files.resolve("asset.png").toString())));
            Files.createSymbolicLink(f.files.resolve("link.png"),f.files.resolve("asset.png"));assertThrows(Exception.class,()->f.read(f.args().put("path","link.png")));
            Files.createLink(f.files.resolve("hardlink.png"),f.files.resolve("asset.png"));assertThrows(SecurityException.class,()->f.read(f.args().put("path","hardlink.png")));
            assertThrows(IllegalArgumentException.class,()->f.read(f.args().put("artifactId",UUID.randomUUID().toString())));assertThrows(Exception.class,()->f.port.validate("project_view_image",f.args().put("imageBase64",Base64.getEncoder().encodeToString(f.pixels))));
        }
    }
    @Test public void postDeliverySourceDriftRevocationAndPauseCannotReuseTheNativePin()throws Exception{
        try(F f=new F();var image=f.read(f.args());var pin=image.retainProjectRequest(f.control,image.base64(f.control))){image.close();pin.requireCurrent(f.control);Files.write(f.files.resolve("asset.png"),png(Color.BLUE));assertThrows(SecurityException.class,()->pin.requireCurrent(f.control));Files.write(f.files.resolve("asset.png"),f.pixels);f.tasks.authorizeTools(f.task,f.run,new JSONArray().put("project_inspect"));assertThrows(SecurityException.class,()->pin.requireCurrent(f.control));}
        try(F f=new F();var image=f.read(f.args());var pin=image.retainProjectRequest(f.control,image.base64(f.control))){f.control.pause();f.control.resume();assertThrows(SecurityException.class,()->pin.requireCurrent(f.control));}
    }
    @Test public void unrelatedAttachmentAndClaimedManagedOutputWithoutOriginalSqlAreRejected()throws Exception{
        try(F f=new F()){
            var book=AttachmentRepository.get(f.android).book;JSONObject unrelated=book.importBytes(new ByteArrayInputStream(f.pixels),"private.png","image/png",new JSONObject().put("kind","created_text"));assertThrows(SecurityException.class,()->f.read(new JSONObject().put("artifactId",unrelated.getString("id")).put("sourceSha",f.sha)));
            JSONObject claimed=book.importNativeResult(new ByteArrayInputStream(f.pixels),"output.png","image/png",new JSONObject().put("kind",ProjectManagedOutput.ROLE).put("projectId",f.project).put("checkoutId",f.checkout).put("path","output.png").put("taskId",f.task).put("runId",f.run).put("operationId",UUID.randomUUID().toString()).put("artifactPlanDigest","a".repeat(64)),UUID.randomUUID().toString(),ProjectManagedOutput.ROLE,f.task,f.run,"a".repeat(64),f.control);
            assertThrows(SecurityException.class,()->f.read(new JSONObject().put("artifactId",claimed.getString("id")).put("sourceSha",f.sha)));assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());
        }
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception exception)throw exception;throw failed;}}
    @SuppressWarnings("unchecked") private static <T>T field(Object target,String name)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(target);}
    private static String realNpmCli()throws Exception{
        String configured=System.getProperty("a26.npmCli");if(configured!=null&&!configured.isBlank()){Path selected=Path.of(configured).toRealPath();if(!Files.isRegularFile(selected))throw new IllegalStateException("Actual npm CLI is unavailable");return selected.toString();}
        Path node=Path.of(Objects.requireNonNull(System.getProperty("a25.node"),"Original native host group must supply actual Node")).toRealPath(),npm=node.getParent().resolve("npm");
        if(!Files.isRegularFile(npm))throw new IllegalStateException("This actual managed build needs installed npm beside Node or explicit a26.npmCli; no substituted tool");return npm.toRealPath().toString();
    }
    private static JSONObject execute(ProjectCapabilityPort port,TaskStore tasks,RunJournal journal,RunControl control,String task,JSONObject proposal)throws Exception{
        JSONObject args=new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId",proposal.getJSONArray("steps").getJSONObject(0).getString("id"));
        for(int n=0;n<2;n++){String op=journal.beginOperation("project_execute",args);JSONObject receipt=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",op),control);journal.finishOperation(op,receipt);if(!"approval_required".equals(receipt.optString("code")))return receipt;tasks.decideCapabilityApproval(task,receipt.getString("approvalId"),true);}throw new AssertionError("Original approved operation did not dispatch");
    }
    @Test public void actualManagedBuildCollectionUsesOriginalSqlBookBytesAndWithdrawal()throws Exception{
        ProjectManagedRunnerJourneyTest factory=new ProjectManagedRunnerJourneyTest();ProjectCapabilityPort port=null;String originalNpm=System.getProperty("a26.npmCli");
        try{System.setProperty("a26.npmCli",realNpmCli());factory.create();byte[] png=png(Color.RED);invoke(factory,"selectedProject",new Class<?>[]{String.class},"Actual project image output");invoke(factory,"file",new Class<?>[]{String.class,String.class},"package.json","{\"type\":\"module\",\"scripts\":{\"build\":\"node build.mjs\"}}");invoke(factory,"file",new Class<?>[]{String.class,String.class},"build.mjs","import{mkdirSync,writeFileSync}from'node:fs';mkdirSync('dist',{recursive:true});writeFileSync('dist/actual.png',Buffer.from('"+Base64.getEncoder().encodeToString(png)+"','base64'));\n");invoke(factory,"startTask",new Class<?>[]{String.class},"Inspect the actual original generated image");TaskStore tasks=field(factory,"tasks");String task=field(factory,"taskId");tasks.initializeExecutionScope(task,120);JSONObject attempt=(JSONObject)invoke(factory,"startFixtureAttempt",new Class<?>[0]);LocalModule local=field(factory,"local");String project=field(factory,"project"),checkout=field(factory,"checkout"),run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=ProjectImageSourceNativeTest.<AtomicReference<RunControl>>field(factory,"live").get();var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray grants=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)grants.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,grants);RunJournal journal=tasks.journal(task,run,epoch,control);
            String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");JSONObject built=execute(port,tasks,journal,control,task,port.execute("project_plan",new JSONObject().put("intent","build").put("ecosystem","node").put("expectedSourceDigest",digest),control));assertEquals(built.toString(),"completed",built.optString("status"));
            JSONObject saved=execute(port,tasks,journal,control,task,port.execute("project_collect_plan",new JSONObject().put("executionId",built.getString("operationId")).put("path","dist/actual.png"),control));assertEquals(saved.toString(),"completed",saved.optString("status"));JSONObject artifact=saved.getJSONObject("attachment");String op=UUID.randomUUID().toString();port.execute("project_view_image",new JSONObject().put("artifactId",artifact.getString("id")).put("sourceSha",artifact.getString("sha256")).put("_hostOperationId",op),control);
            try(var image=port.takeToolObservation("project_view_image",op,control).consume("project_view_image",op,control);var pin=image.retainProjectRequest(control,image.base64(control))){assertArrayEquals(png,Base64.getDecoder().decode(image.base64(control)));assertEquals("managed_build_output",image.metadata().getString("sourceKind"));image.close();pin.requireCurrent(control);AttachmentRepository.get(RuntimeEnvironment.getApplication()).book.remove(artifact.getString("id"));assertThrows(Exception.class,()->pin.requireCurrent(control));}
        }finally{try{try{if(port!=null)port.close();}finally{factory.close();}}finally{if(originalNpm==null)System.clearProperty("a26.npmCli");else System.setProperty("a26.npmCli",originalNpm);}}
    }
}
