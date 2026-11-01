package dev.pocketengine.features.local.project;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.TermuxBridge;
import dev.pocketengine.features.local.TermuxWorkspaceTransfer;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real npm/Node output -> selected script -> real nonce callback -> native read-only facade. */
public final class GeneratedArtifactsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private interface Checked {void run()throws Exception;}
    private static void denied(Checked action)throws Exception {try{action.run();fail("unsafe generated output accepted");}catch(SecurityException|IllegalStateException|IllegalArgumentException expected){}}
    private static final String CHECKOUT="12345678-1234-1234-1234-123456789abc";
    private final class Fixture implements EngineModule {
        final Path root=temp.newFolder().toPath(),source=Files.createDirectory(root.resolve("source")),remote=Files.createDirectory(root.resolve("remote"));final Context context;
        final TermuxBridge bridge;final ProjectDevelopment facade=new ProjectDevelopment(this);long sequence;int executed;
        Fixture()throws Exception {
            context=new ContextWrapper(null){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return root.toFile();}};
            bridge=new TermuxBridge(new FeatureContext(context,null,null,event->{}));
            write("package.json","{\"name\":\"offline-artifact-fixture\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node build.js\"}}");
            write("build.js","const f=require('fs');f.mkdirSync('dist',{recursive:true});f.writeFileSync('dist/index.html',\"<!doctype html><meta charset='utf-8'><style>body{color:#222}</style><h1>真实生成产物</h1><button onclick='this.textContent=2'>1</button>\");f.mkdirSync('node_modules',{recursive:true});for(let i=0;i<110;i++)f.writeFileSync('node_modules/dep'+i,'x'.repeat(1000));console.log('built-output');\n");
        }
        void write(String path,String text)throws Exception{Path file=source.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);}
        JSONObject export()throws Exception {
            JSONArray files=new JSONArray();try(var tree=Files.walk(source)){for(Path file:(Iterable<Path>)tree.filter(Files::isRegularFile).sorted()::iterator){String content=Files.readString(file);files.put(new JSONObject().put("path",source.relativize(file).toString()).put("content",content).put("sha256",ProjectSources.sha256(content.getBytes(StandardCharsets.UTF_8))));}}
            return new JSONObject().put("checkout",new JSONObject().put("id",CHECKOUT).put("projectId","project")).put("files",files);
        }
        public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject input)throws Exception {
            switch(command){case "export":if(!CHECKOUT.equals(input.getString("checkoutId")))throw new SecurityException();return export();
                case "probe":return new JSONObject().put("termux",new JSONObject());case "listJobs":return new JSONObject().put("termuxJobs",bridge.list());
                case "getJob":return bridge.get(input.getString("jobId"));default:throw new AssertionError(command);}
        }
        JSONObject plan(String intent)throws Exception{return ProjectBuildPlan.create(this,new JSONObject().put("checkoutId",CHECKOUT).put("expectedSourceDigest",ProjectSources.describe(export()).getString("sourceDigest")).put("intent",intent).put("ecosystem","node"));}
        Path revision(JSONObject plan)throws Exception{return remote.resolve(plan.getString("sourceDigest"));}
        File record(String id){return root.resolve("termux-receipts/"+id+".json").toFile();}
        JSONObject base(String id,String kind,JSONObject plan)throws Exception {
            return new JSONObject().put("id",id).put("operationId",id).put("kind",kind).put("checkoutId",CHECKOUT).put("sourceDigest",plan.getString("sourceDigest")).put("nonce","nonce_"+id)
                    .put("status","awaiting_result").put("checkoutSequence",++sequence).put("createdAt",System.currentTimeMillis());
        }
        JSONObject callback(String id,Result result,boolean truncated)throws Exception {
            JSONObject raw=JsonFiles.read(record(id));Bundle values=new Bundle();values.putInt("err",-1);values.putInt("exitCode",result.exit);values.putString("stdout",result.stdout);values.putString("stderr",result.stderr);if(truncated)values.putLong("stdout_original_length",result.stdout.length()+100L);
            return TermuxBridge.receive(context,new Intent().setData(Uri.parse("pocketworld-local://result/"+id+"/"+raw.getString("nonce"))).putExtra("result",values));
        }
        JSONObject execute(JSONObject plan,String stepId,String id)throws Exception {
            JSONObject step=ProjectBuildPlan.step(plan,stepId),input=ProjectSources.copy(step.getJSONObject("payload")).put("projectPlan",plan).put("projectStepId",stepId).put("_taskId","task_"+id).put("_runId",id).put("operationId",id);
            JSONObject raw=base(id,stepId.equals("prepare")?"project_stage":"command",plan).put("projectExecution",ProjectBuildPlan.ownerLink(input,step.getString("localCommand")));
            String script;Path cwd;
            if(stepId.equals("prepare")){script=ProjectSources.stageScript(export(),revision(plan).toString(),remote.toString());cwd=root;}
            else {raw.put("sourceGuardRequired",true).put("requestedExecutable",input.getString("executable")).put("requestedArgv",input.getJSONArray("argv"));script=ProjectSources.launchGuard(plan.getJSONArray("sourceManifest"),plan.getString("sourceDigest"),input.getString("executable"),input.getJSONArray("argv"));cwd=revision(plan);}
            JsonFiles.write(record(id),raw);executed++;return callback(id,process(cwd,script,root),false);
        }
        JSONObject build()throws Exception{JSONObject plan=plan("build");execute(plan,"prepare","stage");JSONObject build=execute(plan,"build","build");assertEquals(build.toString(),0,build.getInt("exitCode"));assertTrue(build.getString("stdout").contains("built-output"));return plan;}
        JSONObject selection(JSONObject plan,String path)throws Exception{return facade.handle("project.generatedArtifactPlan",new JSONObject().put("plan",plan).put("stepId","build").put("jobId","build").put("path",path));}
        JSONObject collect(JSONObject supplied,String id,boolean truncated,boolean cancel)throws Exception {
            JSONObject selection=GeneratedArtifacts.verified(supplied),input=ProjectSources.copy(supplied.getJSONObject("hostAction").getJSONObject("payload")).put("_taskId","task_"+id).put("_runId",id).put("operationId",id);
            JSONObject raw=base(id,"workspace_collect",selection.getJSONObject("buildPlan")).put("collectionMode","selected_text_artifact").put("sourceGuardRequired",true).put("artifactCollection",GeneratedArtifacts.ownerCollection(selection,input)).put("sourceCheckout",new JSONObject().put("artifactSelection",selection));if(cancel)raw.put("cancelRequested",true);
            JsonFiles.write(record(id),raw);String selected=TermuxWorkspaceTransfer.selectedScript(selection.getString("path"),selection.getString("artifactPlanDigest"));String script=ProjectSources.launchGuard(selection.getJSONObject("buildPlan").getJSONArray("sourceManifest"),selection.getString("sourceDigest"),"/bin/sh",new JSONArray().put("-c").put(selected));executed++;return callback(id,process(revision(selection.getJSONObject("buildPlan")),script,root),truncated);
        }
        JSONObject read(JSONObject selection,String id)throws Exception{return facade.handle("project.generatedArtifact",new JSONObject().put("collectionPlan",selection).put("collectionJobId",id));}
    }
    private static final class Result {final String stdout,stderr;final int exit;Result(String out,String err,int exit){stdout=out;stderr=err;this.exit=exit;}}
    private Result process(Path cwd,String script,Path root)throws Exception {
        Path out=root.resolve(UUID.randomUUID()+".stdout"),err=root.resolve(UUID.randomUUID()+".stderr");ProcessBuilder launch=new ProcessBuilder("/bin/sh").directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());launch.environment().put("npm_config_cache",root.resolve("npm-cache").toString());launch.environment().put("npm_config_offline","true");launch.environment().put("npm_config_update_notifier","false");Process child=launch.start();try{child.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));child.getOutputStream().close();if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("host fixture timeout");}return new Result(Files.readString(out),Files.readString(err),child.exitValue());}finally{child.destroyForcibly();Files.deleteIfExists(out);Files.deleteIfExists(err);}
    }
    @Test public void actualBuildSelectedCollectionCallbackAndPreviewRetainBothReceipts()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build(),selection=f.selection(plan,"dist/index.html");assertEquals("termux.collect",selection.getJSONObject("hostAction").getString("command"));assertEquals(32000,selection.getInt("maxBytes"));JSONObject raw=f.collect(selection,"collect",false,false);assertTrue(raw.toString(),raw.getBoolean("artifactReady"));assertFalse(raw.has("stdout"));JSONObject artifact=f.read(selection,"collect");
        assertTrue(artifact.getBoolean("generatedArtifact"));assertEquals("termux_selected_output",artifact.getString("artifactOrigin"));assertEquals("build",artifact.getString("jobId"));assertEquals("collect",artifact.getString("collectionJobId"));assertNotEquals(artifact.getString("buildReceiptIdentity"),artifact.getString("collectionReceiptIdentity"));assertEquals("artifact_collect",artifact.getJSONObject("collectionReceipt").getString("intent"));assertEquals("build",artifact.getJSONObject("buildReceipt").getString("intent"));assertEquals(ProjectSources.sha256(artifact.getString("content").getBytes(StandardCharsets.UTF_8)),artifact.getString("sha256"));assertTrue(artifact.getString("content").contains("真实生成产物"));assertFalse(Files.exists(f.source.resolve("dist/index.html")));assertEquals(2,f.export().getJSONArray("files").length());
        JSONArray jobs=f.bridge.list();for(int i=0;i<jobs.length();i++)if(jobs.getJSONObject(i).has("collectedArtifact"))assertFalse(jobs.getJSONObject(i).getJSONObject("collectedArtifact").has("content"));
        String examples=System.getProperty("w10b.exampleDir","");if(!examples.isEmpty()){Path dir=Path.of(examples);Files.createDirectories(dir);Files.writeString(dir.resolve("collection-plan.json"),selection.toString(2));Files.writeString(dir.resolve("generated-artifact.json"),artifact.toString(2));}
    }
    @Test public void originalSourceBinaryEscapeAndSelectionTamperingAreRefused()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build();for(String path:new String[]{"build.js","../dist/index.html","/dist/index.html","dist/app.apk","dist/app.class"})denied(()->f.selection(plan,path));JSONObject selection=f.selection(plan,"dist/index.html"),altered=ProjectSources.copy(selection);altered.put("path","dist/other.html");denied(()->GeneratedArtifacts.verified(altered));JSONObject input=ProjectSources.copy(selection.getJSONObject("hostAction").getJSONObject("payload"));denied(()->GeneratedArtifacts.ownerCollection(selection,input));input.put("_taskId","owner_task").put("_runId","run").put("operationId","other");denied(()->GeneratedArtifacts.ownerCollection(selection,input));
    }
    @Test public void laterActualRunAndNewSourceInvalidateOldBuildAndCapturedOutput()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build(),selection=f.selection(plan,"dist/index.html");f.collect(selection,"collect",false,false);f.execute(f.plan("run"),"run","later_run");denied(()->f.selection(plan,"dist/index.html"));denied(()->f.read(selection,"collect"));GeneratedArtifacts.requireBuild(GeneratedArtifacts.verified(selection),GeneratedArtifacts.ownerBuild(plan,f.bridge.get("build")));denied(()->GeneratedArtifacts.requireLatest(GeneratedArtifacts.ownerBuild(plan,f.bridge.get("build")),f.bridge.list()));
        Fixture changed=new Fixture();JSONObject p=changed.build(),s=changed.selection(p,"dist/index.html");changed.collect(s,"collected",false,false);changed.write("build.js",Files.readString(changed.source.resolve("build.js"))+"// changed source\n");denied(()->changed.read(s,"collected"));denied(()->changed.selection(p,"dist/index.html"));
    }
    @Test public void failureUnknownLegacyFractionalOrTruncatedBuildCannotGrantCollection()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build(),raw=JsonFiles.read(f.record("build"));int count=f.executed;
        for(JSONObject bad:new JSONObject[]{new JSONObject(raw.toString()).put("status","failed").put("exitCode",1),new JSONObject(raw.toString()).put("status","unknown"),new JSONObject(raw.toString()).put("exitCode",0.5),new JSONObject(raw.toString()).put("outputTruncated",true),new JSONObject(raw.toString()).put("cancelRequested",true)}){JsonFiles.write(f.record("build"),bad);denied(()->f.selection(plan,"dist/index.html"));}
        JSONObject legacy=ProjectSources.copy(raw);legacy.remove("checkoutSequence");JsonFiles.write(f.record("build"),legacy);denied(()->f.selection(plan,"dist/index.html"));assertEquals(count,f.executed);
    }
    @Test public void realFailedBuildAndRealFailedCollectionNeverBecomeSuccessfulThroughUiJson()throws Exception {
        Fixture failed=new Fixture();failed.write("build.js","throw new Error('actual-build-failure')\n");JSONObject p=failed.plan("build");failed.execute(p,"prepare","stage");JSONObject result=failed.execute(p,"build","build");assertNotEquals(0,result.getInt("exitCode"));assertTrue(result.getString("stderr").contains("actual-build-failure"));denied(()->failed.facade.handle("project.generatedArtifactPlan",new JSONObject().put("plan",p).put("stepId","build").put("jobId","build").put("path","dist/index.html").put("receipt",new JSONObject().put("status","completed").put("exitCode",0))));
        Fixture f=new Fixture();JSONObject plan=f.build(),selection=f.selection(plan,"dist/missing.html");JSONObject bad=f.collect(selection,"missing",false,false);assertEquals("failed",bad.getString("status"));assertFalse(bad.getBoolean("artifactReady"));int count=f.executed;denied(()->f.read(selection,"missing"));assertEquals(count,f.executed);
    }
    @Test public void truncatedCancelledAndOversizedCollectionKeepOriginalJobWithoutPreviewOrReplay()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build(),selection=f.selection(plan,"dist/index.html");for(String id:new String[]{"truncated","cancelled"}){JSONObject result=f.collect(selection,id,id.equals("truncated"),id.equals("cancelled"));assertFalse(result.getBoolean("artifactReady"));int count=f.executed;denied(()->f.read(selection,id));assertEquals(count,f.executed);}
        Files.writeString(f.revision(plan).resolve("dist/index.html"),"x".repeat(32001));JSONObject large=f.collect(selection,"large",false,false);assertEquals("failed",large.getString("status"));assertFalse(large.getBoolean("artifactReady"));denied(()->f.read(selection,"large"));
    }
    @Test public void foreignCollectionAndMutatedCachedBytesAreNotAdopted()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build(),selection=f.selection(plan,"dist/index.html");f.collect(selection,"collect",false,false);JSONObject other=f.selection(plan,"dist/other.html");denied(()->f.read(other,"collect"));JSONObject raw=JsonFiles.read(f.record("collect"));raw.getJSONObject("collectedArtifact").put("content","forged UI/cache bytes");JsonFiles.write(f.record("collect"),raw);assertFalse(f.bridge.get("collect").getBoolean("artifactReady"));denied(()->f.read(selection,"collect"));
    }
    @Test public void nonceFailureAndUnknownCollectionCannotCreateAnArtifact()throws Exception {
        Fixture f=new Fixture();JSONObject plan=f.build(),selection=f.selection(plan,"dist/index.html"),clean=GeneratedArtifacts.verified(selection);JSONObject input=ProjectSources.copy(selection.getJSONObject("hostAction").getJSONObject("payload")).put("_taskId","task_unknown").put("_runId","unknown").put("operationId","unknown");JSONObject raw=f.base("unknown","workspace_collect",plan).put("collectionMode","selected_text_artifact").put("sourceGuardRequired",true).put("artifactCollection",GeneratedArtifacts.ownerCollection(clean,input)).put("sourceCheckout",new JSONObject().put("artifactSelection",clean));JsonFiles.write(f.record("unknown"),raw);
        Bundle values=new Bundle();values.putInt("err",-1);values.putInt("exitCode",0);assertNull(TermuxBridge.receive(f.context,new Intent().setData(Uri.parse("pocketworld-local://result/unknown/wrong")).putExtra("result",values)));denied(()->f.read(selection,"unknown"));assertEquals("awaiting_result",f.bridge.get("unknown").getString("status"));
    }
}
