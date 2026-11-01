package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.preview.ProjectBundleWebView;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeTool;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Real SQLite, original AgentRunner/history and host JNI processes. Model responses are a
 * loopback fixture; this is explicitly not installed Android tools or WebView JS evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectManagedRunnerJourneyTest {
    private Context android;private LocalModule local;private WorkspaceModule workspace;
    private ManagedProjectRuntime runtime;private TaskStore tasks;private Path temporary;
    private final AtomicReference<RunControl> live=new AtomicReference<>();
    private final AtomicReference<Exception> callbackFailure=new AtomicReference<>();
    private final List<String> runs=new ArrayList<>();
    private String project,checkout,taskId;private TaskStoreProjectHost host;
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1kAAAAASUVORK5CYII=";
    @Before public void create()throws Exception {
        System.out.println("A26_JOURNEY real SHA256 provider="+java.security.MessageDigest.getInstance("SHA-256").getProvider().getName()+"; host executable hashes still fully verified");
        android=RuntimeEnvironment.getApplication();temporary=Files.createTempDirectory("a26-original-runner-");
        byte[] data="independent A26 execution fixture; not a phone tool package\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("share/fixture.txt"));zip.write(data);zip.closeEntry();}
        byte[] archive=bytes.toByteArray();var pack=new dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package(new JSONObject().put("id","a26-host-fixture").put("revision","1").put("license","CC0-1.0 own fixture, tools not redistributed").put("abi","desktop-host-only").put("archiveSha256",ProjectSources.sha256(archive)).put("archiveBytes",archive.length).put("files",new JSONArray().put(new JSONObject().put("path","share/fixture.txt").put("bytes",data.length).put("sha256",ProjectSources.sha256(data)))));
        File node=new File(System.getProperty("a25.node")).getCanonicalFile(),python=new File(System.getProperty("a25.python")).getCanonicalFile();
        Map<String,RuntimeTool> tools=new LinkedHashMap<>();tools.put("sh",tool("sh",new File("/bin/sh"),List.of(),List.of("-c","printf shell-ready")));tools.put("node",tool("node",node,List.of(),List.of("--version")));tools.put("python3",tool("python3",python,List.of(),List.of("--version")));tools.put("npm",tool("npm",node,List.of(System.getProperty("a26.npmCli")),List.of("--version")));
        runtime=ManagedProjectRuntime.create(temporary.resolve("runtime").toFile(),pack,tools);
        // Install before any job. A25 F's separately reported concurrent env/job lock defect
        // is not closed by this sequential business-journey fixture.
        runtime.environmentManager().install(pack,new ByteArrayInputStream(archive),()->{},(phase,done,total)->{});
        FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,runtime);tasks=new TaskStore(android);
        host=new TaskStoreProjectHost(tasks,local,id->taskId!=null&&taskId.equals(id)?live.get():null,event->{});local.setProjectHost(host);local.setProjectReceiptSink(receipt->{try{host.recordBackendReceipt(receipt);}catch(Exception failure){callbackFailure.compareAndSet(null,failure);}});
    }
    private static RuntimeTool tool(String name,File file,List<String> prefix,List<String> probe)throws Exception {file=file.getCanonicalFile();return new RuntimeTool(name,file,ProjectSources.sha256(Files.readAllBytes(file.toPath())),"explicit-host-fixture","host-tool-not-redistributed",prefix,probe);}
    @After public void close()throws Exception {try{if(local!=null)local.close();if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}}finally{if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
    private void file(String path,String content)throws Exception {workspace.handle("write",new JSONObject().put("projectId",project).put("path",path).put("expectedVersion",0).put("content",content));}
    private void selectedProject(String title)throws Exception {project=workspace.handle("create",new JSONObject().put("name",title)).getString("id");}
    private Path active(){return android.getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files");}
    private void startTask(String goal)throws Exception {checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");taskId=tasks.createCapabilityTask(goal,"").getString("id");}
    private void runWithOwnerApprovals(FixtureModel model)throws Exception {
        for(int attempt=0;attempt<16;attempt++){
            JSONObject task=tasks.startAttempt(taskId,"capabilities",120);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");runs.add(run);RunControl original=new RunControl();original.bindHostRun(taskId,run,epoch);live.set(original);
            if(attempt==0)tasks.bindDevelopmentContext(taskId,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
            var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);
            JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
            RunOutcome outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(taskId,run,epoch,original));
            System.out.println("A26_JOURNEY attempt="+attempt+" stage="+model.stage+" status="+outcome.status+" summary="+outcome.summary);
            tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);port.close();
            assertFalse("No uncertainty may be silently replayed: "+outcome.summary,tasks.hasUnresolved(taskId));
            JSONArray approvals=tasks.get(taskId,false).optJSONArray("approvals");boolean pending=false;if(approvals!=null)for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(approval.getString("state").equals("pending")){tasks.decideCapabilityApproval(taskId,approval.getString("id"),true);pending=true;}}
            if(!pending){assertTrue("journey stopped early: "+outcome.summary+"; model stage="+model.stage,model.finished);assertEquals("needs_confirmation",outcome.status);return;}
            assertEquals("awaiting_user",outcome.status);
        }
        fail("owner approval continuation did not converge");
    }
    private static String buildScript(){return "import{mkdirSync,writeFileSync,copyFileSync}from'node:fs';import{title}from'../src/message.mjs';import{suffix}from'fixture-dep';mkdirSync('dist/assets',{recursive:true});writeFileSync('dist/index.html','<!doctype html><meta charset=utf-8><link rel=stylesheet href=assets/site.css><h1>'+title+' '+suffix+'</h1><img src=assets/pixel.png><script type=module src=assets/client.js></script>');for(const name of ['site.css','client.js','message.mjs'])copyFileSync('src/'+name,'dist/assets/'+name);writeFileSync('dist/assets/pixel.png',Buffer.from('"+PNG+"','base64'));console.log('BUILT '+title+' '+suffix);\n";}
    @Test public void originalRunnerReceivesRealNpmFailureRepairsPreviewsAndModifiesSameProject()throws Exception {
        selectedProject("Web managed original-runner journey");file("package.json","{\"type\":\"module\",\"scripts\":{\"build\":\"node tools/build.mjs\",\"test\":\"node --test tests/project.test.mjs\"},\"dependencies\":{\"fixture-dep\":\"file:vendor/fixture-dep\"}}\n");file("vendor/fixture-dep/package.json","{\"name\":\"fixture-dep\",\"version\":\"1.0.0\",\"type\":\"module\",\"exports\":\"./index.mjs\"}\n");file("vendor/fixture-dep/index.mjs","export const suffix='REAL_LOCAL_DEPENDENCY';\n");file("src/message.mjs","export const title='First preview';\n");file("src/site.css","body{color:navy;background:#fff}\n");file("src/client.js","import{title}from'./message.mjs';document.body.dataset.actualModule=title;\n");file("tests/project.test.mjs","import test from'node:test';import assert from'node:assert/strict';import{suffix}from'fixture-dep';test('actual local dependency',()=>assert.equal(suffix,'REAL_LOCAL_DEPENDENCY'));\n");file("tools/build.mjs","import broken SYNTAX\n");startTask("Build the selected Web project; read the real error, fix it, preview all resources, then update the same project.");
        try(FixtureModel model=new FixtureModel(false)){runWithOwnerApprovals(model);assertTrue("Actual SyntaxError must return to the model",model.sawActualFailure);assertTrue("Actual npm receipt and installed local dependency",model.sawActualDependency);assertTrue("Actual declared Node test must pass",model.sawActualTests);assertEquals(2,model.previews.size());assertTrue(runs.size()>1);assertNull(callbackFailure.get());assertTrue(Files.exists(active().resolve("node_modules/fixture-dep/package.json")));assertTrue(Files.readString(active().resolve("dist/index.html")).contains("Continued SAME project"));
            JSONObject first=model.previews.get(0),second=model.previews.get(1);assertNotEquals(first.getString("previewId"),second.getString("previewId"));ProjectPreviewBundle.Verified bundle=local.projectPreviewForOwner(second.getString("previewId"),checkout);assertEquals(5,bundle.manifest().length());assertEquals("text/css",ProjectBundleWebView.response(bundle,bundle.origin+"assets/site.css","GET").getMimeType());assertEquals("text/javascript",ProjectBundleWebView.response(bundle,bundle.origin+"assets/client.js","GET").getMimeType());assertArrayEquals(Base64.getDecoder().decode(PNG),ProjectBundleWebView.response(bundle,bundle.origin+"assets/pixel.png","GET").getData().readAllBytes());assertTrue(Files.readString(active().resolve("tools/build.mjs")).equals(buildScript()));assertTrue(local.handle("reviewFile",new JSONObject().put("checkoutId",checkout).put("path","tools/build.mjs")).getString("diff").contains("mkdirSync"));assertTrue(tasks.get(taskId,false).getJSONArray("developmentPreviews").length()>0);
            System.out.println("A26D actualOriginalAgentRunner=true; realSQLite=true; realHostJNI_NpmDependency=true; realSyntaxErrorInNextModelRequest=true; approvedCASRepair=true; sameTaskHistoryAndActiveTree=true; secondPreviewAfterEdit=true; CSS_JS_PNG_exactBytes=true; AndroidToolAssetsOrWebViewJSVerified=false; model=loopback-fixture");}
    }
    @Test public void pythonActualUnittestFailureReturnsToOriginalModelAndIsRepaired()throws Exception {
        selectedProject("Python original-runner repair");file("main.py","from message import title\nprint(title)\n");file("message.py","title = 'WRONG'\n");file("test_project.py","import unittest\nfrom message import title\nclass TestProject(unittest.TestCase):\n def test_title(self): self.assertEqual(title, 'PYTHON_REPAIRED')\n");startTask("Run Python tests, read the real failing assertion, repair this selected project, then run its real entry point.");try(FixtureModel model=new FixtureModel(true)){runWithOwnerApprovals(model);assertTrue(model.sawActualFailure);assertTrue(model.sawActualTests);assertTrue(model.sawPythonOutput);assertNull(callbackFailure.get());assertEquals("title = 'PYTHON_REPAIRED'\n",Files.readString(active().resolve("message.py")));System.out.println("A26D actualPythonUnittestFailure=true; failedToolResultInOriginalHistory=true; ownerApprovedCASFix=true; actualPythonEntryOutput=true; AndroidDevice=false");}
    }
    private static String webTemplateFile(String path)throws Exception {
        JSONArray files=ProjectTemplates.files("web");for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))return files.getJSONObject(i).getString("content");throw new AssertionError("missing formal template file "+path);
    }
    private static String pythonTemplateFile(String path)throws Exception {
        JSONArray files=ProjectTemplates.files("python");for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))return files.getJSONObject(i).getString("content");throw new AssertionError("missing formal Python template file "+path);
    }
    @Test public void hashedPythonVenvTestFailureRepairsInOriginalTaskAndReportsRemainExactVersions()throws Exception {
        selectedProject("Normal hashed Python original-runner journey");JSONArray files=ProjectTemplates.files("python");
        Path wheels=Path.of(java.util.Objects.requireNonNull(System.getProperty("c2.pythonWheels"),"provide verified official Python wheels")).toRealPath();
        for(int i=0;i<files.length();i++){JSONObject source=files.getJSONObject(i);String path=source.getString("path"),content=source.getString("content");if(path.equals("schedule.py"))content=content.replace("    return candidate\n","    return anchor\n");if(path.equals("requirements.txt"))content+="--no-index\n--find-links "+wheels+"\n";file(path,content);}
        startTask("Install the selected Python project's hash-pinned dependencies into its independent venv, return a real test failure to this same task, repair and verify it, run the actual CLI, then continue and open the observed report version.");
        try(FixtureModel model=new FixtureModel(true,true)){
            runWithOwnerApprovals(model);assertTrue(model.sawActualDependency);assertTrue(model.sawActualFailure);assertTrue(model.sawActualTests);assertTrue(model.sawPythonOutput);assertTrue(model.sawPythonReportRead);assertEquals(2,model.previews.size());assertNull(callbackFailure.get());assertFalse(tasks.hasUnresolved(taskId));
            assertEquals(pythonTemplateFile("schedule.py"),Files.readString(active().resolve("schedule.py")));assertTrue(Files.readString(active().resolve(".venv/pyvenv.cfg")).contains("include-system-site-packages = false"));
            boolean persistedFailure=false;JSONArray operations=tasks.get(taskId,false).getJSONArray("operations");
            for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i),receipt=operation.optJSONObject("receipt"),execution=receipt==null?null:receipt.optJSONObject("executionReceipt");if(execution==null||!"failed".equals(execution.optString("status")))continue;assertEquals("receipt",operation.getString("state"));assertNotEquals(0,execution.getInt("exitCode"));assertTrue(execution.getString("stderr").contains("AssertionError"));assertEquals(taskId,execution.getJSONObject("projectExecution").getString("taskId"));persistedFailure=true;}
            assertTrue(persistedFailure);JSONObject first=model.previews.get(0),second=model.previews.get(1);assertNotEquals(first.getString("previewId"),second.getString("previewId"));
            ProjectPreviewBundle.Verified before=local.projectPreviewForOwner(first.getString("previewId"),checkout),after=local.projectPreviewForOwner(second.getString("previewId"),checkout);assertEquals(3,after.manifest().length());
            String oldReport=new String(before.resource(before.origin+"report.json").bytes(),StandardCharsets.UTF_8),newReport=new String(after.resource(after.origin+"report.json").bytes(),StandardCharsets.UTF_8);assertTrue(oldReport.contains("整理本月账单"));assertFalse(oldReport.contains("继续同一个Python项目"));assertTrue(newReport.contains("继续同一个Python项目"));
            for(int i=0;i<after.manifest().length();i++){String path=after.manifest().getJSONObject(i).getString("path");assertArrayEquals(Files.readAllBytes(active().resolve("dist").resolve(path)),ProjectBundleWebView.response(after,after.origin+path,"GET").getData().readAllBytes());}
            assertTrue(tasks.get(taskId,false).getJSONArray("developmentPreviews").length()>=2);System.out.println("C2_PYTHON originalAgentRunnerAndTaskStoreHost=true; realHostJNI=true; officialHashPinnedWheels=true; isolatedCheckoutVenv=true; realFiveUnittests=true; realNonzeroAssertionAndStderrInOriginalOperationAndNextModelRequest=true; ownerApprovedCASRepair=true; sameTaskCliAndTwoImmutableHtmlCssJsonReports=true; model=loopback-fixture; ARM_device_or_realProvider=false");
        }
    }
    @Test public void lockedBundlerFailureUsesOriginalReceiptHistoryAndProducesTwoRealResourceBundles()throws Exception {
        selectedProject("Normal locked bundler original-runner journey");JSONArray files=ProjectTemplates.files("web");
        for(int i=0;i<files.length();i++){JSONObject source=files.getJSONObject(i);String path=source.getString("path"),content=source.getString("content");if(path.equals("src/main.js"))content=content.replace("'./view.js'","'./missing-view.js'");file(path,content);}
        // Test-only offline registry cache of the official lock-pinned packages. No
        // install script or network fallback is permitted by this host fixture.
        Path cache=Path.of(java.util.Objects.requireNonNull(System.getProperty("c2.npmCache"),"provide verified official npm cache")).toRealPath();file(".npmrc","cache="+cache+"\noffline=true\n");
        startTask("Build and test the selected normal Web project; return the real import error to this same task, repair it, open the actual bundled resources, then continue the same project.");
        try(FixtureModel model=new FixtureModel(false,true)){
            runWithOwnerApprovals(model);assertTrue(model.sawActualFailure);assertTrue(model.sawActualDependency);assertTrue(model.sawActualTests);assertEquals(2,model.previews.size());assertNull(callbackFailure.get());assertFalse(tasks.hasUnresolved(taskId));
            assertEquals("0.25.12",new JSONObject(Files.readString(active().resolve("node_modules/esbuild/package.json"))).getString("version"));
            boolean persistedFailure=false;JSONArray operations=tasks.get(taskId,false).getJSONArray("operations");
            for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i),receipt=operation.optJSONObject("receipt"),execution=receipt==null?null:receipt.optJSONObject("executionReceipt");if(execution==null||!"failed".equals(execution.optString("status")))continue;
                assertEquals("receipt",operation.getString("state"));assertNotEquals(0,execution.getInt("exitCode"));assertTrue(execution.getString("stderr").contains("missing-view.js"));assertEquals(taskId,execution.getJSONObject("projectExecution").getString("taskId"));persistedFailure=true;
            }
            assertTrue("the real compiler stderr/exit must be persisted on the original operation",persistedFailure);
            JSONObject first=model.previews.get(0),second=model.previews.get(1);assertNotEquals(first.getString("previewId"),second.getString("previewId"));ProjectPreviewBundle.Verified bundle=local.projectPreviewForOwner(second.getString("previewId"),checkout);JSONArray manifest=bundle.manifest();assertEquals(4,manifest.length());
            boolean css=false,js=false,svg=false;for(int i=0;i<manifest.length();i++){JSONObject item=manifest.getJSONObject(i);String path=item.getString("path"),mime=item.getString("mimeType");assertArrayEquals(Files.readAllBytes(active().resolve("dist").resolve(path)),ProjectBundleWebView.response(bundle,bundle.origin+path,"GET").getData().readAllBytes());css|=mime.equals("text/css");js|=mime.equals("text/javascript");svg|=mime.equals("image/svg+xml");}
            assertTrue(css&&js&&svg);assertTrue(Files.readString(active().resolve("src/main.js")).contains("继续同一个项目"));assertTrue(tasks.get(taskId,false).getJSONArray("developmentPreviews").length()>=2);
            System.out.println("C2_WEB originalAgentRunner=true; originalTaskStoreHost=true; realHostJNI_NpmCi=true; esbuild=0.25.12; missingImportStderrAndNonzeroExitPersistedAndVisibleInNextModelRequest=true; ownerApprovedCASRepair=true; sameTaskTwoActualHtmlJsCssSvgBundles=true; model=loopback-fixture; ARM_device_or_realProvider=false");
        }
    }
    private static String serviceScript(String token){return """
        import { createServer } from 'node:http';
        import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
        import { title } from './message.mjs';
        const token = '%s';
        mkdirSync('dist', { recursive: true });
        mkdirSync('.pocketworld', { recursive: true });
        const html = '<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="style.css"><h1>' + title + '</h1><button id="check">Check result</button><output id="result"></output><script src="client.js"></script>';
        writeFileSync('dist/index.html', html);
        writeFileSync('dist/style.css', 'body { color: #17343c; background: #fff; }');
        writeFileSync('dist/client.js', "document.querySelector('#check').onclick = () => { document.querySelector('#result').textContent = 'Observed result'; };\\n");
        const resources = new Map([['/', ['index.html', 'text/html; charset=utf-8']], ['/style.css', ['style.css', 'text/css']], ['/client.js', ['client.js', 'text/javascript']]]);
        const server = createServer((request, response) => {
          if (request.url === '/status') {
            response.setHeader('Content-Type', 'application/json');
            response.end(JSON.stringify({ token, title }));
          } else {
            const resource = resources.get(request.url);
            response.statusCode = resource ? 200 : 404;
            response.setHeader('Content-Type', resource ? resource[1] : 'text/plain');
            response.end(resource ? readFileSync('dist/' + resource[0]) : 'Not found');
          }
          console.log('REQUEST ' + request.url + ' ' + title);
        });
        server.listen(0, '127.0.0.1', () => {
          writeFileSync('.pocketworld/service.json', JSON.stringify({ host: '127.0.0.1', port: server.address().port, token, title }));
          console.log('SERVICE_OUTPUT ' + title + ' http://127.0.0.1:' + server.address().port);
          console.error('SERVICE_STDERR ' + title);
        });
        process.on('SIGTERM', () => server.close(() => { console.log('OWNER_STOP ' + title); process.exit(0); }));
        """.formatted(token);}
    private JSONObject originalOperation(String run)throws Exception {
        JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.getJSONObject(i);if(run.equals(row.optString("runId"))&&"project_execute".equals(row.optString("tool"))&&"run".equals(row.getJSONObject("arguments").optString("stepId"))&&row.optJSONObject("receipt")!=null&&row.getJSONObject("receipt").optJSONObject("projectExecution")!=null)return row;}return null;
    }
    private JSONObject startFixtureAttempt()throws Exception {
        JSONObject task=tasks.startAttempt(taskId,"capabilities",120);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(taskId,run,epoch);live.set(original);
        if(task.optJSONObject("developmentContext")==null)tasks.bindDevelopmentContext(taskId,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
        tasks.authorizeTools(taskId,run,new JSONArray().put("project_execute"));return task;
    }
    /** SQL protocol fixture only: this claims the original approved ticket but does not launch. */
    private JSONObject claimFixtureOperation(JSONObject plan,String stepId)throws Exception {
        JSONObject task=tasks.get(taskId,false);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");JSONObject args=new JSONObject().put("plan",plan).put("stepId",stepId),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,stepId));
        String id=tasks.journal(taskId,run,epoch,live.get()).beginOperation("project_execute",args);JSONObject binding=new JSONObject().put("taskId",taskId).put("runId",run).put("coordinatorEpoch",epoch).put("operationId",id),capability=local.projectCapabilityForHost(exact);
        JSONObject approval=tasks.requireCapabilityApproval(taskId,run,"project_execute",args,capability);tasks.decideCapabilityApproval(taskId,approval.getString("approvalId"),true);JSONObject claimed=tasks.claimProjectOperation(binding,exact,capability,live.get());assertTrue(claimed.getBoolean("fresh"));
        return new JSONObject().put("binding",binding).put("exact",exact).put("route",claimed.getJSONObject("projectExecution"));
    }
    private JSONObject currentPlan(String intent)throws Exception {return local.handle("project.plan",new JSONObject().put("checkoutId",checkout).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("intent",intent).put("expectedSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")));}
    @Test public void managedSqlSettlementRequiresRealBooleanExitAndReleaseProofAndCannotReviveAnActiveRun()throws Exception {
        selectedProject("Managed terminal ownership SQL protocol");file("main.mjs","console.log('protocol-only source');\n");startTask("SQL receipt validation, not a provider execution");JSONObject attempt=startFixtureAttempt(),claim=claimFixtureOperation(currentPlan("run"),"run"),binding=claim.getJSONObject("binding"),exact=claim.getJSONObject("exact");
        JSONObject backend=new JSONObject().put("id",binding.getString("operationId")).put("operationId",binding.getString("operationId")).put("checkoutId",checkout).put("sourceDigest",exact.getString("sourceDigest")).put("projectExecution",claim.getJSONObject("route")).put("provider","managed_process").put("kind","command").put("status","completed").put("receiptReceived",true).put("exitCode",0).put("processExited",true).put("processGroupAbsent",true).put("resourcesReleased",true);
        for(String key:new String[]{"receiptReceived","processExited","processGroupAbsent","resourcesReleased"})for(Object missing:new Object[]{JSONObject.NULL,Boolean.FALSE,"true"}){
            JSONObject incomplete=new JSONObject(backend.toString());if(missing==JSONObject.NULL)incomplete.remove(key);else incomplete.put(key,missing);tasks.recordProjectOperation(binding,exact,incomplete);assertTrue(key+"="+missing,tasks.hasUnresolved(taskId));
        }
        for(String key:new String[]{"kind","provider"}){JSONObject wrongType=new JSONObject(backend.toString()).put(key,"model_claimed");tasks.recordProjectOperation(binding,exact,wrongType);assertTrue(tasks.hasUnresolved(taskId));}
        JSONObject wrong=new JSONObject(backend.toString());wrong.getJSONObject("projectExecution").put("coordinatorEpoch",binding.getInt("coordinatorEpoch")+1);assertThrows(SecurityException.class,()->tasks.recordProjectOperation(binding,exact,wrong));
        tasks.status(taskId,"reconciling","controlled active run: do not resume from a callback");tasks.recordProjectOperation(binding,exact,backend);assertFalse(tasks.hasUnresolved(taskId));assertEquals("reconciling",tasks.get(taskId,false).getString("status"));
        tasks.endAttempt(taskId,attempt.getString("runId"),"awaiting_user","explicit end",new JSONObject());JSONObject next=startFixtureAttempt();tasks.recordProjectOperation(binding,exact,new JSONObject(backend.toString()).put("stdout","late original observation, not the replacement run"));assertEquals(next.getString("runId"),tasks.get(taskId,false).getString("runId"));assertEquals(next.getInt("coordinatorEpoch"),tasks.get(taskId,false).getInt("coordinatorEpoch"));assertEquals("preparing",tasks.get(taskId,false).getString("status"));
        tasks.endAttempt(taskId,next.getString("runId"),"awaiting_user","end protocol fixture",new JSONObject());
    }
    @Test public void actualPreparationFailureBeforeNativeDispatchCanBeEditedAndExplicitlyRetried()throws Exception {
        selectedProject("Preparation not-dispatched retry");file("main.mjs","console.log('preparation source');\n");startTask("Retain a real pre-process preparation failure, explicitly edit and retry the same original task");JSONObject first=startFixtureAttempt(),claim=claimFixtureOperation(currentPlan("prepare"),"prepare");
        var jobs=new dev.pocketengine.features.local.runtime.ManagedProcessJobs(temporary.resolve("preparation-jobs").toFile(),new dev.pocketengine.features.local.runtime.NativeProcessSupervisor());
        try{
            jobs.setReceiptSink(receipt->{try{host.recordBackendReceipt(receipt);}catch(Exception failure){callbackFailure.compareAndSet(null,failure);}});
            JSONObject source=local.handle("export",new JSONObject().put("checkoutId",checkout));var call=new dev.pocketengine.features.local.runtime.RuntimeInvocation(claim.getJSONObject("exact"),claim.getJSONObject("binding"),source,live.get(),()->{},new dev.pocketengine.features.local.runtime.RuntimeWorkspace(active().toFile(),project,checkout));
            jobs.startPreparation(call,"controlled-host-preparation",context->{throw new java.io.IOException("controlled download failure before native exec");},()->{});
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(tasks.hasUnresolved(taskId)&&System.nanoTime()<until)Thread.sleep(5);assertFalse(tasks.hasUnresolved(taskId));JSONObject failed=jobs.query(call.executionRef()).json();assertEquals("failed",failed.getString("status"));assertEquals("not_dispatched",failed.getString("effectStatus"));assertFalse(failed.getBoolean("processDispatchAttempted"));assertFalse(failed.getBoolean("processLaunched"));assertTrue(failed.getBoolean("resourcesReleased"));assertFalse(failed.getBoolean("processExited"));
            tasks.endAttempt(taskId,first.getString("runId"),"awaiting_user","owner may explicitly repair",new JSONObject());tasks.feedback(taskId,"I explicitly want to edit the selected project and retry preparation.");JSONObject second=startFixtureAttempt();
            JSONObject before=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.mjs"));JSONObject edit=local.handle("project.plan",new JSONObject().put("checkoutId",checkout).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("intent","edit").put("expectedSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("path","main.mjs").put("content","console.log('owner explicitly repaired');\n").put("expectedHash",before.getString("sha256")));
            JSONObject editClaim=claimFixtureOperation(edit,"edit");JSONObject written=local.executeProjectForHost(editClaim.getJSONObject("exact"),editClaim.getJSONObject("route"),live.get(),()->{});tasks.recordProjectOperation(editClaim.getJSONObject("binding"),editClaim.getJSONObject("exact"),written);assertFalse(tasks.hasUnresolved(taskId));
            JSONObject retry=claimFixtureOperation(currentPlan("prepare"),"prepare");var again=new dev.pocketengine.features.local.runtime.RuntimeInvocation(retry.getJSONObject("exact"),retry.getJSONObject("binding"),local.handle("export",new JSONObject().put("checkoutId",checkout)),live.get(),()->{},new dev.pocketengine.features.local.runtime.RuntimeWorkspace(active().toFile(),project,checkout));
            jobs.startPreparation(again,"controlled-host-preparation",context->{var result=context.execute(List.of("/bin/sh","-c","printf 'actual retry probe\\n'"),Map.of(),active().toFile(),3000);assertEquals(0,result.exitCode);return new JSONObject();},()->{});
            until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(tasks.hasUnresolved(taskId)&&System.nanoTime()<until)Thread.sleep(5);assertFalse(tasks.hasUnresolved(taskId));JSONObject actual=jobs.query(again.executionRef()).json();assertEquals("completed",actual.getString("status"));assertTrue(actual.getBoolean("processExited"));assertTrue(actual.getBoolean("processGroupAbsent"));assertTrue(actual.getBoolean("resourcesReleased"));assertTrue(actual.getString("stdout").contains("actual retry probe"));assertNull(callbackFailure.get());assertTrue(Files.readString(active().resolve("main.mjs")).contains("owner explicitly repaired"));tasks.endAttempt(taskId,second.getString("runId"),"awaiting_user","explicit retry observed",new JSONObject());
        }finally{jobs.close();}
    }
    @Test public void aLateProjectTerminalCannotClearAnotherStoredUnresolvedEffect()throws Exception {
        selectedProject("Other unresolved original effect");file("main.mjs","console.log('SQL sibling protocol');\n");startTask("SQL recovery protocol, not two dispatched processes");JSONObject attempt=startFixtureAttempt(),claim=claimFixtureOperation(currentPlan("run"),"run"),binding=claim.getJSONObject("binding"),exact=claim.getJSONObject("exact");tasks.endAttempt(taskId,attempt.getString("runId"),"awaiting_user","controlled late receipt",new JSONObject());
        // Persist a pre-existing unresolved sibling as a recovery fixture. No second
        // operation is launched or given dispatch authority by this direct SQL seed.
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)field.get(tasks);android.content.ContentValues sibling=new android.content.ContentValues();sibling.put("operation_id","controlled-sibling");sibling.put("task_id",taskId);sibling.put("run_id",attempt.getString("runId"));sibling.put("tool","controlled_recovery_fixture");sibling.put("arguments","{}");sibling.put("state","unknown");sibling.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,sibling);
        JSONObject backend=new JSONObject().put("id",binding.getString("operationId")).put("operationId",binding.getString("operationId")).put("checkoutId",checkout).put("sourceDigest",exact.getString("sourceDigest")).put("projectExecution",claim.getJSONObject("route")).put("provider","managed_process").put("kind","command").put("status","failed").put("receiptReceived",true).put("exitCode",-1).put("processExited",true).put("processGroupAbsent",true).put("resourcesReleased",true).put("cancelRequested",true);
        tasks.recordProjectOperation(binding,exact,backend);assertTrue(tasks.hasUnresolved(taskId));assertEquals("reconciling",tasks.get(taskId,false).getString("status"));tasks.resolveOperation(taskId,"controlled-sibling","not_occurred");assertFalse(tasks.hasUnresolved(taskId));assertEquals("paused",tasks.get(taskId,false).getString("status"));
    }
    @Test public void actualNativeNoChildFailureSettlesOnlyWithCompleteProofAndExplicitOwnerRetry()throws Exception {
        selectedProject("Actual native no-child retry");file("main.mjs","console.log('must not run before entry cancellation');\n");startTask("Keep a definite native no-child startup failure in this original task, then explicitly modify and retry");JSONObject first=startFixtureAttempt(),claim=claimFixtureOperation(currentPlan("run"),"run");
        var jobs=new dev.pocketengine.features.local.runtime.ManagedProcessJobs(temporary.resolve("no-child-jobs").toFile(),new dev.pocketengine.features.local.runtime.NativeProcessSupervisor());var actualReceipt=new AtomicReference<JSONObject>();var observed=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try{
            jobs.setReceiptSink(receipt->{try{actualReceipt.set(new JSONObject(receipt.toString()));observed.countDown();if(!release.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("controlled no-child delivery gate timeout");host.recordBackendReceipt(receipt);}catch(Exception failure){callbackFailure.compareAndSet(null,failure);}});
            RunControl original=live.get();JSONObject source=local.handle("export",new JSONObject().put("checkoutId",checkout));
            var call=new dev.pocketengine.features.local.runtime.RuntimeInvocation(claim.getJSONObject("exact"),claim.getJSONObject("binding"),source,original,()->{
                if(Thread.currentThread().getName().startsWith("project-process-")){tasks.requestCancel(taskId,first.getString("runId"));tasks.cancelProjectOperation(claim.getJSONObject("binding"),claim.getJSONObject("exact"));original.cancel();throw new SecurityException("controlled authority withdrawal at actual native observer entry");}
            },new dev.pocketengine.features.local.runtime.RuntimeWorkspace(active().toFile(),project,checkout));
            String node=Path.of(System.getProperty("a25.node")).toRealPath().toString();jobs.start(call,List.of(node,"./main.mjs"),Map.of(),active().toFile(),3000,()->{});assertTrue(observed.await(4,java.util.concurrent.TimeUnit.SECONDS));JSONObject actual=actualReceipt.get();
            assertTrue(actual.toString(),actual.getBoolean("noChildCreated"));assertEquals("no_child_created",actual.getString("launchPhase"));assertEquals(-1,actual.getInt("exitCode"));assertEquals(0,actual.getInt("terminationSignal"));assertTrue(actual.getInt("launchErrno")>0);assertFalse(actual.getBoolean("processLaunched"));assertFalse(actual.getBoolean("processExited"));assertTrue(actual.getBoolean("processGroupAbsent"));assertTrue(actual.getBoolean("resourcesReleased"));assertTrue(actual.getBoolean("cancelRequested"));assertEquals("",actual.getString("stdout"));
            for(String key:new String[]{"nativeResultContract","launchPhase","noChildCreated","processLaunched","processExited","exitCode","terminationSignal","launchErrno","timedOut","processGroupAbsent","outputComplete","resourcesReleased"}){
                JSONObject incomplete=new JSONObject(actual.toString());incomplete.remove(key);tasks.recordProjectOperation(claim.getJSONObject("binding"),claim.getJSONObject("exact"),incomplete);assertTrue("no-child proof missing "+key,tasks.hasUnresolved(taskId));
            }
            JSONObject wrong=new JSONObject(actual.toString()).put("launchErrno","2");tasks.recordProjectOperation(claim.getJSONObject("binding"),claim.getJSONObject("exact"),wrong);assertTrue(tasks.hasUnresolved(taskId));
            tasks.endAttempt(taskId,first.getString("runId"),"cancelled","native entry was cancelled, not a business exit",new JSONObject());assertEquals("reconciling",tasks.get(taskId,false).getString("status"));release.countDown();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);while(tasks.hasUnresolved(taskId)&&System.nanoTime()<until)Thread.sleep(5);
            assertFalse(tasks.hasUnresolved(taskId));JSONObject settled=tasks.get(taskId,false);assertEquals("paused",settled.getString("status"));assertTrue(settled.getBoolean("cancelRequested"));JSONObject normalized=ProjectDevelopment.recordedReceipt(settled,originalOperation(first.getString("runId")));assertTrue(normalized.getBoolean("noChildCreated"));assertTrue(normalized.getBoolean("stopConfirmed"));
            tasks.feedback(taskId,"I explicitly want to continue this original task, modify main.mjs and retry after the confirmed no-child failure.");JSONObject second=startFixtureAttempt(),before=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.mjs"));
            JSONObject edit=local.handle("project.plan",new JSONObject().put("checkoutId",checkout).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("intent","edit").put("expectedSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("path","main.mjs").put("content","console.log('repaired after definite no child');\n").put("expectedHash",before.getString("sha256")));JSONObject editClaim=claimFixtureOperation(edit,"edit");
            JSONObject written=local.executeProjectForHost(editClaim.getJSONObject("exact"),editClaim.getJSONObject("route"),live.get(),()->{});tasks.recordProjectOperation(editClaim.getJSONObject("binding"),editClaim.getJSONObject("exact"),written);JSONObject retry=claimFixtureOperation(currentPlan("run"),"run");
            jobs.setReceiptSink(receipt->{try{host.recordBackendReceipt(receipt);}catch(Exception failure){callbackFailure.compareAndSet(null,failure);}});var again=new dev.pocketengine.features.local.runtime.RuntimeInvocation(retry.getJSONObject("exact"),retry.getJSONObject("binding"),local.handle("export",new JSONObject().put("checkoutId",checkout)),live.get(),()->{},new dev.pocketengine.features.local.runtime.RuntimeWorkspace(active().toFile(),project,checkout));
            jobs.start(again,List.of(node,"./main.mjs"),Map.of(),active().toFile(),3000,()->{});until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);while(tasks.hasUnresolved(taskId)&&System.nanoTime()<until)Thread.sleep(5);assertFalse(tasks.hasUnresolved(taskId));JSONObject retried=jobs.query(again.executionRef()).json();assertFalse(retried.getBoolean("noChildCreated"));assertEquals("completed",retried.getString("status"));assertTrue(retried.getString("stdout").contains("repaired after definite no child"));assertNull(callbackFailure.get());tasks.endAttempt(taskId,second.getString("runId"),"awaiting_user","owner retry observed",new JSONObject());
            System.out.println("C2_NO_CHILD realHostJNIEntryCancellation=true; actualMissingExecutable127IsSeparateNativeTest=true; originalSqlRejectsIncompleteOrLegacyTuple=true; cancelPreserved=true; explicitSameTaskOwnerCasAndRealRetry=true; ARM_or_realProvider=false");
        }finally{release.countDown();jobs.close();}
    }
    @Test public void ownedHttpServiceLogsStopLateReceiptAndExplicitSameTaskModificationRestart()throws Exception {
        String token=UUID.randomUUID().toString();selectedProject("Controlled local HTTP project lifecycle");file("main.mjs",serviceScript(token));file("message.mjs","export const title = 'First service result';\n");startTask("Run this selected local HTTP project, inspect actual logs, stop this original execution, then explicitly continue the same task to modify, restart and inspect the corresponding real output files.");
        var gate=new AtomicReference<java.util.concurrent.CountDownLatch>();
        local.setProjectReceiptSink(receipt->{try{var held=gate.get();if(held!=null&&receipt.optBoolean("cancelRequested"))if(!held.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("controlled late receipt gate");host.recordBackendReceipt(receipt);}catch(Exception failure){callbackFailure.compareAndSet(null,failure);}});
        try(ServiceModel model=new ServiceModel()){
            int stopped=0;for(int attempt=0;attempt<10&&!model.finished;attempt++){
                JSONObject task=tasks.startAttempt(taskId,"capabilities",120);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");runs.add(run);RunControl original=new RunControl();original.bindHostRun(taskId,run,epoch);live.set(original);
                if(attempt==0)tasks.bindDevelopmentContext(taskId,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
                var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);
                JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
                var held=new java.util.concurrent.CountDownLatch(1);gate.set(held);var done=new java.util.concurrent.atomic.AtomicBoolean();var ownerFailure=new AtomicReference<Throwable>();var stoppedOperation=new AtomicReference<String>();String expected=stopped==0?"First service result":"Continued service result";
                Thread owner=new Thread(()->{try{
                    long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);while(!done.get()&&System.nanoTime()<until){
                        JSONObject operation=originalOperation(run);Path marker=active().resolve(".pocketworld/service.json");
                        if(operation!=null&&Files.isRegularFile(marker)){
                            JSONObject endpoint=new JSONObject(Files.readString(marker));if(expected.equals(endpoint.optString("title"))){
                                assertEquals("127.0.0.1",endpoint.getString("host"));assertEquals(token,endpoint.getString("token"));int number=endpoint.getInt("port");assertTrue(number>0&&number<=65535);
                                // This controlled sample is probed by a real host GET. stdout is
                                // not parsed as a URL and no public endpoint or WebView is opened.
                                java.net.HttpURLConnection http=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+number+"/status").openConnection();http.setConnectTimeout(1000);http.setReadTimeout(1000);try{assertEquals(200,http.getResponseCode());JSONObject actual=new JSONObject(new String(http.getInputStream().readAllBytes(),StandardCharsets.UTF_8));assertEquals(token,actual.getString("token"));assertEquals(expected,actual.getString("title"));}finally{http.disconnect();}
                                for(String path:new String[]{"index.html","style.css","client.js"}){java.net.HttpURLConnection resource=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+number+(path.equals("index.html")?"/":"/"+path)).openConnection();resource.setConnectTimeout(1000);resource.setReadTimeout(1000);try{assertEquals(200,resource.getResponseCode());assertArrayEquals(Files.readAllBytes(active().resolve("dist").resolve(path)),resource.getInputStream().readAllBytes());}finally{resource.disconnect();}}
                                JSONObject poll=ProjectDevelopment.originalPollRequest(tasks.get(taskId,false),operation),observed=local.handle("project.poll",poll);assertTrue(observed.toString(),observed.getBoolean("ownedWorkerLive"));assertFalse(observed.getBoolean("receiptReceived"));assertTrue(observed.getString("stdout").contains("SERVICE_OUTPUT "+expected));assertTrue(observed.getString("stderr").contains("SERVICE_STDERR "+expected));
                                JSONObject journal=ProjectDevelopment.recordedReceipt(tasks.get(taskId,false),originalOperation(run));assertEquals(observed.getString("stdout"),journal.getString("stdout"));assertFalse(journal.getBoolean("stopConfirmed"));
                                stoppedOperation.set(operation.getString("operationId"));tasks.requestCancel(taskId,run);original.cancel();return;
                            }
                        }
                        Thread.sleep(10);
                    }
                    if(!done.get())throw new AssertionError("owned local service did not become probeable");
                }catch(Throwable failure){ownerFailure.set(failure);original.cancel();}},"c2-explicit-owner-service-stop");owner.start();
                RunOutcome outcome;
                try{outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(taskId,run,epoch,original));tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);}
                finally{done.set(true);owner.join(2000);port.close();}
                if(ownerFailure.get()!=null)throw new AssertionError("controlled owner lifecycle",ownerFailure.get());
                if(stoppedOperation.get()!=null){
                    try{assertEquals("cancelled",outcome.status);assertTrue(tasks.hasUnresolved(taskId));assertEquals("reconciling",tasks.get(taskId,false).getString("status"));}
                    finally{held.countDown();}
                    long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(tasks.hasUnresolved(taskId)&&System.nanoTime()<until)Thread.sleep(5);
                    assertFalse(tasks.hasUnresolved(taskId));JSONObject settled=tasks.get(taskId,false);assertEquals("paused",settled.getString("status"));assertTrue("late receipt must not clear owner cancellation",settled.getBoolean("cancelRequested"));
                    JSONObject actual=ProjectDevelopment.recordedReceipt(settled,originalOperation(run));assertTrue(actual.toString(),actual.getBoolean("stopConfirmed"));assertTrue(actual.getBoolean("processGroupAbsent"));assertTrue(actual.getBoolean("resourcesReleased"));
                    stopped++;model.continueAfterOwnerStop(stoppedOperation.get());tasks.feedback(taskId,stopped==1?"I explicitly want to continue this same task: inspect the stopped execution, open its actual result, change message.mjs to Continued service result, then restart and stop the owned service again.":"I explicitly want to continue this same task: inspect the second stopped execution and open its corresponding real output files.");
                }else{
                    held.countDown();assertFalse(tasks.hasUnresolved(taskId));boolean pending=false;JSONArray approvals=tasks.get(taskId,false).optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if("pending".equals(approval.optString("state"))){tasks.decideCapabilityApproval(taskId,approval.getString("id"),true);pending=true;}}
                    if(pending)assertEquals("awaiting_user",outcome.status);else assertTrue(outcome.summary,model.finished);
                }
            }
            assertEquals(2,stopped);assertTrue(model.finished);assertTrue(model.sawStoppedHistory);assertEquals(2,model.previews.size());assertNull(callbackFailure.get());assertEquals("export const title = 'Continued service result';\n",Files.readString(active().resolve("message.mjs")));
            JSONObject first=model.previews.get(0),second=model.previews.get(1);assertNotEquals(first.getString("previewId"),second.getString("previewId"));ProjectPreviewBundle.Verified before=local.projectPreviewForOwner(first.getString("previewId"),checkout),after=local.projectPreviewForOwner(second.getString("previewId"),checkout);assertEquals(3,after.manifest().length());
            assertTrue(new String(before.resource(before.origin+"index.html").bytes(),StandardCharsets.UTF_8).contains("First service result"));assertTrue(new String(after.resource(after.origin+"index.html").bytes(),StandardCharsets.UTF_8).contains("Continued service result"));for(int i=0;i<after.manifest().length();i++){String path=after.manifest().getJSONObject(i).getString("path");assertArrayEquals(Files.readAllBytes(active().resolve("dist").resolve(path)),ProjectBundleWebView.response(after,after.origin+path,"GET").getData().readAllBytes());}
            System.out.println("C2_SERVICE realOwnedHostJNI=true; realLoopbackHttpGet=true; liveOriginalOperationLogs=true; ownerStoppedWholeOriginalRun=true; controlledLateTerminalReceiptResumesOnlyOwnerAdmission=true; sameTaskApprovedCASModifyRestartStop=true; twoImmutableHtmlCssJsResults=true; liveServiceModelContinuation=false; publicServer=false; fixtureModel=true; ARM_device_realProvider_WebView=false");
        }finally{var held=gate.get();if(held!=null)held.countDown();}
    }
    @Test public void nativeOwnedServiceContinuesOriginalModelReadsStopsEditsRestartsAndDrainsOnRunEnd()throws Exception {
        selectedProject("Native owned service original model continuation");file("main.mjs",serviceScript(UUID.randomUUID().toString()));file("message.mjs","export const title = 'First service result';\n");startTask("Explicit owned service: continue reading in this original model run, stop only its job before editing, restart, and drain on normal run end");
        JSONObject task=startFixtureAttempt();String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=live.get();var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);
        ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
        try(ServiceModel model=new ServiceModel(true)){
            dev.pocketengine.core.CapabilityPort traced=(dev.pocketengine.core.CapabilityPort)java.lang.reflect.Proxy.newProxyInstance(dev.pocketengine.core.CapabilityPort.class.getClassLoader(),new Class<?>[]{dev.pocketengine.core.CapabilityPort.class},(proxy,method,arguments)->{try{return method.invoke(port,arguments);}catch(java.lang.reflect.InvocationTargetException failed){System.out.println("C2_OWNED_REAL_PORT_EXCEPTION method="+method.getName());failed.getCause().printStackTrace(System.out);throw failed.getCause();}});
            RunOutcome outcome=new AgentRunner(null,traced).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(taskId,run,epoch,original));
            System.out.println("C2_OWNED_STAGE stage="+model.stage+" status="+outcome.status+" launches="+model.launches.length()+" modelFailure="+model.failure+" evidence="+outcome.evidence);
            if(model.failure!=null)throw new AssertionError("real HTTP fixture model failed at stage "+model.stage,model.failure);
            assertTrue("original model must reach all same-run read/stop/edit/restart stages, stage="+model.stage+": "+outcome.summary,model.finished);assertEquals(2,model.launches.length());assertFalse("project_stop must never cancel the original run",original.isCancelled());assertFalse(tasks.hasUnresolved(taskId));assertNull(callbackFailure.get());
            JSONObject exits=tasks.journal(taskId,run,epoch,original).projectServiceExitEvidence(model.launches);assertNotNull(exits);assertTrue(exits.getBoolean("allReleased"));assertEquals(2,exits.getJSONArray("executions").length());for(int i=0;i<2;i++){JSONObject actual=exits.getJSONArray("executions").getJSONObject(i);assertTrue(actual.getBoolean("processExited"));assertTrue(actual.getBoolean("processGroupAbsent"));assertTrue(actual.getBoolean("resourcesReleased"));}
            assertEquals("export const title = 'Continued service result';\n",Files.readString(active().resolve("message.mjs")));assertEquals(1,model.previews.size());JSONObject preview=model.previews.get(0);ProjectPreviewBundle.Verified bundle=local.projectPreviewForOwner(preview.getString("previewId"),checkout);assertEquals(3,bundle.manifest().length());for(int i=0;i<bundle.manifest().length();i++){String path=bundle.manifest().getJSONObject(i).getString("path");assertArrayEquals(Files.readAllBytes(active().resolve("dist").resolve(path)),bundle.resource(bundle.origin+path).bytes());}
            tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);assertFalse("normal end cannot hide an owned service",tasks.hasUnresolved(taskId));assertNotEquals("reconciling",tasks.get(taskId,false).getString("status"));assertEquals(run,tasks.get(taskId,false).getString("runId"));
            System.out.println("C2_OWNED_SERVICE actualAgentRunner_TaskStore_nativeAck=true; launchAckNotReadiness=true; sameRunNextModelAndStatusLog=true; forgedBindingRejected=true; sameCheckoutWriteBeforeIntentAndDirectWriteDenied=true; projectStopOriginalJobNotRunCancel=true; sameRunApprovedCASModifyRestart=true; normalEndRealExitGroupAndLeaseDrain=true; correspondingImmutableThreeResourcePreview=true; fixtureModel=true; Android_ARM_realProvider_WebView=false");
        }finally{port.close();}
    }
    @Test public void postDispatchReceiptExceptionStillDrainsOriginalNativeJobBeforeFinalResult()throws Exception {
        selectedProject("Owned service lost return cleanup");file("main.mjs",serviceScript(UUID.randomUUID().toString()));file("message.mjs","export const title = 'First service result';\n");startTask("Keep original process ownership even if the original execute receipt does not return");JSONObject task=startFixtureAttempt();String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=live.get();var started=new java.util.concurrent.atomic.AtomicBoolean();
        local.setProjectHost(new ProjectDevelopment.HostPort(){
            public ProjectDevelopment.Claim claim(JSONObject binding,JSONObject exact)throws Exception{return host.claim(binding,exact);}
            public void dispatch(JSONObject binding,JSONObject exact)throws Exception{host.dispatch(binding,exact);}
            public JSONObject operation(JSONObject binding)throws Exception{
                host.operation(binding);JSONObject row=originalOperation(run),args=row.getJSONObject("arguments"),plan=args.getJSONObject("plan"),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId")));var reference=dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,binding);JSONObject actual=runtime.query(reference).json();
                // This is the real reachable post-dispatch point: the original worker
                // was registered and owns its lease. The synchronized module may still
                // prevent its first exec; do not release that lock to fake an earlier ACK.
                assertTrue(actual.toString(),actual.getBoolean("ownedWorkerLive"));assertFalse(actual.getBoolean("resourcesReleased"));started.set(true);throw new java.io.IOException("controlled receipt loss AFTER native owned worker registration");
            }
            public JSONObject stopService(JSONObject binding,String execution)throws Exception{return host.stopService(binding,execution);}
        });
        var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
        try(ServiceModel model=new ServiceModel(true)){
            RunOutcome outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),original,event->{try{
                if("result".equals(event.optString("type"))){JSONObject row=originalOperation(run),request=ProjectDevelopment.originalPollRequest(tasks.get(taskId,false),row),plan=request.getJSONObject("plan"),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,request.getString("stepId")));JSONObject actual=runtime.query(dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,request.getJSONObject("binding"))).json();assertTrue("final result must follow original lease drain, not cause it later: "+actual,actual.getBoolean("resourcesReleased"));assertTrue(actual.toString(),actual.getBoolean("processExited")||dev.pocketengine.features.local.runtime.NativeProcessSupervisor.validatesNoChildReceipt(actual));assertTrue(actual.getBoolean("processGroupAbsent"));}
                tasks.event(taskId,run,epoch,event);
            }catch(Exception unavailable){throw new IllegalStateException(unavailable);}},tasks.journal(taskId,run,epoch,original));
            assertTrue(started.get());assertEquals(2,model.stage);assertEquals("awaiting_user",outcome.status);assertNull(model.failure);assertFalse(original.isCancelled());assertFalse(tasks.hasUnresolved(taskId));JSONObject actual=ProjectDevelopment.recordedReceipt(tasks.get(taskId,false),originalOperation(run));assertTrue(actual.getBoolean("stopConfirmed"));assertTrue(actual.getBoolean("resourcesReleased"));assertFalse(actual.getBoolean("ownedWorkerLive"));assertTrue(tasks.journal(taskId,run,epoch,original).restore().getJSONArray("messages").toString().contains("receiptDeliveryUnavailable"));assertTrue(tasks.journal(taskId,run,epoch,original).restore().getJSONArray("messages").toString().contains("controlled receipt loss AFTER native owned worker registration"));
            tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);assertEquals("awaiting_user",tasks.get(taskId,false).getString("status"));
            System.out.println("C2_OWNED_LOST_RETURN actualNativeOwnedWorkerAndLeaseBeforeInjectedReceiptException=true; originalExactRefDrainBeforeResultEmission=true; noSecondDispatch=true; unknownToolResultPairedInOriginalHistory=true; nativeStopAndLeaseRelease=true; realProvider_Android=false");
        }finally{port.close();}
    }
    @Test public void ownedServiceWaitingForOriginalApprovalDoesNotBecomePendingCleanup()throws Exception {
        selectedProject("Owned service ordinary approval wait");file("main.mjs",serviceScript(UUID.randomUUID().toString()));file("message.mjs","export const title = 'First service result';\n");startTask("Ask approval before starting owned service");JSONObject task=startFixtureAttempt();String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=live.get();var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
        try(ServiceModel model=new ServiceModel(true,false)){
            RunOutcome outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception unavailable){throw new IllegalStateException(unavailable);}},tasks.journal(taskId,run,epoch,original));assertEquals("awaiting_user",outcome.status);assertEquals(2,model.stage);assertNull(model.failure);assertFalse(tasks.hasUnresolved(taskId));assertFalse(outcome.evidence.optBoolean("projectServiceDrainPending"));assertFalse(outcome.summary.contains("收尾"));assertNull(originalOperation(run));JSONArray approvals=tasks.get(taskId,false).getJSONArray("approvals");assertEquals(1,approvals.length());assertEquals("pending",approvals.getJSONObject(0).getString("state"));
        }finally{port.close();}
    }
    private void approveControlledOwnerPlan(JSONObject plan)throws Exception {
        JSONObject task=tasks.get(taskId,false),args=new JSONObject().put("plan",plan).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id")),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId")));
        JSONObject approval=tasks.requireCapabilityApproval(taskId,task.getString("runId"),"project_execute",args,local.projectCapabilityForHost(exact));assertFalse(approval.optBoolean("approved"));tasks.decideCapabilityApproval(taskId,approval.getString("approvalId"),true);
    }
    private void probeControlledService(String expected)throws Exception {
        Path marker=active().resolve(".pocketworld/service.json");long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);JSONObject endpoint=null;
        while(System.nanoTime()<until){if(Files.isRegularFile(marker)){JSONObject observed=new JSONObject(Files.readString(marker));if(expected.equals(observed.optString("title"))){endpoint=observed;break;}}Thread.sleep(5);}assertNotNull("native start alone is not HTTP readiness",endpoint);assertEquals("127.0.0.1",endpoint.getString("host"));
        java.net.HttpURLConnection http=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+endpoint.getInt("port")+"/status").openConnection();http.setConnectTimeout(1000);http.setReadTimeout(1000);try{assertEquals(200,http.getResponseCode());assertEquals(expected,new JSONObject(new String(http.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).getString("title"));}finally{http.disconnect();}
    }
    private final class ServiceModel implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread thread;final List<JSONObject> previews=new ArrayList<>();final boolean ownedJourney,autoApprovals;final JSONArray launches=new JSONArray();int operationsBeforeConflict;volatile Throwable failure;volatile int stage;boolean finished,sawStoppedHistory;String emitted="",stoppedOperation="";JSONObject inspection,plan,read;int calls;
        ServiceModel()throws Exception {this(false);}
        ServiceModel(boolean ownedJourney)throws Exception {this(ownedJourney,true);}
        ServiceModel(boolean ownedJourney,boolean autoApprovals)throws Exception {this.ownedJourney=ownedJourney;this.autoApprovals=autoApprovals;server.bind(new InetSocketAddress("127.0.0.1",0));thread=new Thread(this::serve,"c2-service-fixture-model");thread.setDaemon(true);thread.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-a-real-key","fixture-service-model",false,40,30).withStepCeiling(120);}
        void continueAfterOwnerStop(String id){stoppedOperation=id;stage=stage==2?3:13;emitted="";}
        private JSONObject next(JSONObject request)throws Exception {
            if(ownedJourney)return nextOwned(request);
            JSONArray messages=request.getJSONArray("messages");JSONObject last=null;StringBuilder history=new StringBuilder();for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);history.append(message.optString("content"));if("tool".equals(message.optString("role")))last=message;}
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))){JSONObject result=new JSONObject(last.getString("content"));if(!"approval_required".equals(result.optString("code"))){if(result.has("ecosystems"))inspection=result;if(result.has("plan"))plan=result.getJSONObject("plan");if(result.has("sha256")&&result.has("content"))read=result;if(result.has("previewId"))previews.add(result);if(stage==3||stage==13){assertTrue(result.toString(),result.getBoolean("stopConfirmed"));assertTrue(result.getBoolean("resourcesReleased"));}stage++;}emitted="";}
            if(stage==3||stage==13){assertTrue(history.toString(),history.toString().contains(stoppedOperation));assertTrue(history.toString(),history.toString().contains("\"processGroupAbsent\":true"));assertTrue(history.toString(),history.toString().contains("SERVICE_OUTPUT"));sawStoppedHistory=true;}
            String name;JSONObject args=new JSONObject();switch(stage){
                case 0,4,7,10,14:name="project_inspect";break;
                case 1,11:name="project_plan";args.put("intent","run").put("ecosystem","node").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                case 2,9,12:name="project_execute";args.put("plan",plan).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));break;
                case 3,13:name="project_status";args.put("executionId",stoppedOperation);break;
                case 5,15:name="project_preview";args.put("entryPath","dist/index.html").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                case 6:name="project_read_file";args.put("path","message.mjs");break;
                case 8:name="project_plan";args.put("intent","edit").put("expectedSourceDigest",inspection.getString("sourceDigest")).put("path","message.mjs").put("content","export const title = 'Continued service result';\n").put("expectedHash",read.getString("sha256"));break;
                default:finished=true;return new JSONObject().put("content","The two explicitly stopped original project executions and corresponding immutable outputs are available in this same task; no active service readiness or Android device verification is claimed.");
            }
            emitted="c2-service-"+stage+"-"+(++calls);return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        private JSONObject nextOwned(JSONObject request)throws Exception {
            JSONArray messages=request.getJSONArray("messages");JSONObject last=null;for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if("tool".equals(message.optString("role")))last=message;}
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))){
                JSONObject result=new JSONObject(last.getString("content"));assertNotEquals("controlled owner approvals must use the exact original Store API before dispatch","approval_required",result.optString("code"));
                if(result.has("ecosystems"))inspection=result;if(result.has("plan")){plan=result.getJSONObject("plan");if(stage!=6&&autoApprovals)approveControlledOwnerPlan(plan);}if(result.has("sha256")&&result.has("content"))read=result;if(result.has("previewId"))previews.add(result);
                JSONObject current=tasks.get(taskId,false);String run=current.getString("runId");int epoch=current.getInt("coordinatorEpoch");RunControl control=live.get();
                if(stage==2||stage==15){
                    assertEquals("project_running",result.getString("code"));assertEquals("running",result.getString("status"));assertTrue(result.getBoolean("accepted"));assertFalse(result.getBoolean("receiptReceived"));assertFalse(result.getBoolean("terminal"));assertFalse(result.getBoolean("serviceReady"));stoppedOperation=result.getString("operationId");launches.put(stoppedOperation);assertTrue(tasks.journal(taskId,run,epoch,control).hasNativeProjectRunningAck(stoppedOperation,result));
                    JSONObject forged=new JSONObject(result.toString());forged.getJSONObject("binding").put("coordinatorEpoch",epoch+1);assertFalse(tasks.journal(taskId,run,epoch,control).hasNativeProjectRunningAck(stoppedOperation,forged));assertThrows(IllegalStateException.class,()->tasks.startAttempt(taskId,"capabilities",120));assertNull(tasks.journal(taskId,run,epoch,control).projectServiceExitEvidence(new JSONArray().put(stoppedOperation)));probeControlledService(stage==2?"First service result":"Continued service result");assertNotNull(tasks.journal(taskId,run,epoch,control).restore().getJSONArray("operationResults"));
                    // A real service ACK does not excuse any other unknown operation. This
                    // test-only SQL row models an independently unresolved native effect;
                    // no production API, model marker or alternate execution is granted.
                    // Isolate this test-only SQL negative atomically: a real worker must
                    // never observe it as a genuine concurrent authority withdrawal.
                    if(stage==2){synchronized(tasks){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var database=(android.database.sqlite.SQLiteDatabase)field.get(tasks);String unknown=UUID.randomUUID().toString();var value=new android.content.ContentValues();value.put("operation_id",unknown);value.put("task_id",taskId);value.put("run_id",run);value.put("tool","controlled_unrelated_effect");value.put("arguments","{}");value.put("state","unknown");value.put("created_at",System.currentTimeMillis());database.insertOrThrow("operations",null,value);try{assertThrows(IllegalStateException.class,()->tasks.journal(taskId,run,epoch,control).restore());}finally{database.delete("operations","operation_id=?",new String[]{unknown});}}}

                }
                if(stage==3||stage==16){assertEquals("project_service_observation",result.getString("code"));assertEquals("observed",result.getString("status"));assertEquals("running",result.getString("serviceStatus"));assertFalse(result.getBoolean("processTerminal"));}
                if(stage==4||stage==17){assertEquals("project_service_log_observation",result.getString("code"));StringBuilder output=new StringBuilder();JSONArray chunks=result.getJSONArray("chunks");for(int i=0;i<chunks.length();i++)output.append(new String(Base64.getDecoder().decode(chunks.getJSONObject(i).getString("dataBase64")),StandardCharsets.UTF_8));assertTrue(output.toString(),output.toString().contains("SERVICE_OUTPUT"));}
                if(stage==6){operationsBeforeConflict=current.getJSONArray("operations").length();assertThrows("direct checkout write remains held",IllegalStateException.class,()->local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","message.mjs").put("expectedHash",read.getString("sha256")).put("content","unapproved mutation")));assertThrows(IllegalStateException.class,()->tasks.journal(taskId,run,epoch,control).beginOperation("project_execute",new JSONObject().put("plan",plan).put("stepId","edit")));}
                if(stage==7){assertEquals("project_service_conflict",result.getString("code"));assertEquals("not_dispatched",result.getString("status"));assertFalse(result.getBoolean("accepted"));assertEquals(operationsBeforeConflict,tasks.get(taskId,false).getJSONArray("operations").length());assertTrue(Files.readString(active().resolve("message.mjs")).contains("First service result"));}
                if(stage==8){assertEquals("project_stop_confirmed",result.getString("code"));assertTrue(result.getBoolean("processTerminal"));assertTrue(result.getBoolean("resourcesReleased"));assertFalse(control.isCancelled());assertNotNull(tasks.journal(taskId,run,epoch,control).projectServiceExitEvidence(new JSONArray().put(stoppedOperation)));}
                if(stage==9){assertTrue(result.toString(),result.getBoolean("stopConfirmed"));assertTrue(result.getBoolean("resourcesReleased"));}
                stage++;emitted="";
            }
            String name;JSONObject args=new JSONObject();switch(stage){
                case 0,10,13,18:name="project_inspect";break;
                case 1,14:name="project_plan";args.put("intent","run").put("runMode","owned_service").put("ecosystem","node").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                case 2,7,12,15:name="project_execute";args.put("plan",plan).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));break;
                case 3,9,16:name="project_status";args.put("executionId",stoppedOperation);break;
                case 4,17:name="project_log";args.put("executionId",stoppedOperation).put("cursor",0);break;
                case 5:name="project_read_file";args.put("path","message.mjs");break;
                case 6,11:name="project_plan";args.put("intent","edit").put("expectedSourceDigest",inspection.getString("sourceDigest")).put("path","message.mjs").put("content","export const title = 'Continued service result';\n").put("expectedHash",read.getString("sha256"));break;
                case 8:name="project_stop";args.put("executionId",stoppedOperation);break;
                case 19:name="project_preview";args.put("entryPath","dist/index.html").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                default:finished=true;return new JSONObject().put("content","服务仍在运行，已在同一轮读取实际状态日志、停止原job、修改再启动并查看对应成果；这句话是在原HostRun收尾前生成，不是关闭后的服务事实。");
            }
            emitted="c2-owned-service-"+stage+"-"+(++calls);return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        private void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(30000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IllegalArgumentException("fixture request bound");JSONObject message=next(new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8))).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed()){failure=error;try{server.close();}catch(Exception ignored){}}}}
        private String line(InputStream input)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int value;(value=input.read())!=-1;){if(value=='\n')return out.toString(StandardCharsets.US_ASCII).replace("\r","");if(out.size()>8192)throw new IllegalArgumentException("fixture header bound");out.write(value);}throw new IllegalStateException("fixture EOF");}
        @Override public void close()throws Exception {server.close();thread.join(2000);if(failure!=null)throw new AssertionError("fixture service model",failure);}
    }
    private final class FixtureModel implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread thread;final boolean python,formalProject;final List<JSONObject> requests=new CopyOnWriteArrayList<>(),previews=new ArrayList<>();
        volatile Throwable failure;int stage;String emitted="";JSONObject inspection,plan,read;boolean finished,sawActualFailure,sawActualDependency,sawActualTests,sawPythonOutput,sawPythonReportRead;
        FixtureModel(boolean python)throws Exception {this(python,false);}
        FixtureModel(boolean python,boolean formalProject)throws Exception {this.python=python;this.formalProject=formalProject;server.bind(new InetSocketAddress("127.0.0.1",0));thread=new Thread(this::serve,"a26-fixture-model");thread.setDaemon(true);thread.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-a-real-key","fixture-project-model",false,40,30).withStepCeiling(120);}
        private JSONObject next(JSONObject request)throws Exception {
            JSONArray history=request.getJSONArray("messages");JSONObject last=null;for(int i=history.length()-1;i>=0;i--)if("tool".equals(history.getJSONObject(i).optString("role"))){last=history.getJSONObject(i);break;}
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))){JSONObject result=new JSONObject(last.getString("content"));if(!result.optString("code").equals("approval_required")){accept(result);stage++;}emitted="";}
            String name;JSONObject args=new JSONObject();
            if(python&&formalProject){switch(stage){
                case 0,3,6,10,13,20,25,30:name="project_inspect";break;
                case 1:name="project_plan";args=planArgs("probe");break;
                case 2,5,8,12,15,17,19,24,27,29:name="project_execute";args=executeArgs();break;
                case 4:name="project_plan";args=planArgs("dependencies");break;
                case 7,14,26:name="project_plan";args=planArgs("test");break;
                case 9:name="project_read_file";args.put("path","schedule.py");break;
                case 11:name="project_plan";args=editArgs("schedule.py",pythonTemplateFile("schedule.py"));break;
                case 16:name="project_plan";args=planArgs("build");break;
                case 18,28:name="project_plan";args=planArgs("run");break;
                case 21,31:name="project_preview";args.put("entryPath","dist/report.html").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                case 22:name="project_read_file";args.put("path","plans.json");break;
                case 23:name="project_plan";args=editArgs("plans.json",pythonTemplateFile("plans.json").replace("整理本月账单","继续同一个Python项目"));break;
                case 32:name="project_changes";break;
                case 33:name="project_read_file";args.put("path","dist/report.json");break;
                default:finished=true;return new JSONObject().put("content","Actual hashed Python dependencies installed in the original checkout venv; the real failed test was repaired, and the same task produced two observed reports.");
            }}else if(python){switch(stage){case 0,3,7,11:name="project_inspect";break;case 1:name="project_plan";args=planArgs("probe");break;case 2,5,9,13,15:name="project_execute";args=executeArgs();break;case 4,12:name="project_plan";args=planArgs("test");break;case 6:name="project_read_file";args.put("path","message.py");break;case 8:name="project_plan";args=editArgs("message.py","title = 'PYTHON_REPAIRED'\n");break;case 10:name="project_changes";break;case 14:name="project_plan";args=planArgs("run");break;default:finished=true;return new JSONObject().put("content","Actual Python failure repaired and entry point run in the original selected project.");}}
            else{switch(stage){
                case 0,3,6,10,13,18,23,26:name="project_inspect";break;
                case 1:name="project_plan";args=planArgs("probe");break;
                case 2,5,8,12,15,17,22,25:name="project_execute";args=executeArgs();break;
                case 4:name="project_plan";args=planArgs("dependencies");break;
                case 7,16,24:name="project_plan";args=planArgs("build");break;
                case 9:name="project_read_file";args.put("path",formalProject?"src/main.js":"tools/build.mjs");break;
                case 11:name="project_plan";args=formalProject?editArgs("src/main.js",webTemplateFile("src/main.js")):editArgs("tools/build.mjs",buildScript());break;
                case 14:name="project_plan";args=planArgs("test");break;
                case 19,27:name="project_preview";args.put("entryPath","dist/index.html").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                case 20:name="project_read_file";args.put("path",formalProject?"src/main.js":"src/message.mjs");break;
                case 21:name="project_plan";args=formalProject?editArgs("src/main.js",webTemplateFile("src/main.js").replace("点一下复选框，试试真实交互","继续同一个项目")):editArgs("src/message.mjs","export const title='Continued SAME project';\n");break;
                case 28:name="project_changes";break;
                default:finished=true;return new JSONObject().put("content","Real dependency and build failure were repaired; both actual previews and the same project's follow-up edit are available.");}}
            emitted="journey-"+stage+"-"+requests.size();return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        private JSONObject planArgs(String intent)throws Exception {return new JSONObject().put("intent",intent).put("expectedSourceDigest",inspection.getString("sourceDigest")).put("ecosystem",python?"python":"node");}
        private JSONObject editArgs(String path,String content)throws Exception {return planArgs("edit").put("path",path).put("content",content).put("expectedHash",read.getString("sha256"));}
        private JSONObject executeArgs()throws Exception {return new JSONObject().put("plan",plan).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));}
        private void accept(JSONObject result)throws Exception {
            if(result.has("plan"))plan=result.getJSONObject("plan");if(result.has("ecosystems"))inspection=result;if(result.has("content")&&result.has("sha256"))read=result;if(result.has("previewId"))previews.add(result);
            if(result.optString("status").equals("failed")){assertNotEquals(result.toString(),0,result.getInt("exitCode"));assertTrue(result.toString(),result.optString("stderr").contains(python?"AssertionError":formalProject?"missing-view.js":"SyntaxError"));sawActualFailure=true;}
            if(!python&&stage==5){assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(0,result.getInt("exitCode"));assertTrue(Files.exists(active().resolve(formalProject?"node_modules/esbuild/package.json":"node_modules/fixture-dep/package.json")));sawActualDependency=true;}
            if(result.optString("stderr").contains("Ran 1 test")&&result.optString("stderr").contains("OK"))sawActualTests=true;
            if(python&&formalProject&&stage==5){assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(0,result.getInt("exitCode"));assertTrue(Files.isExecutable(active().resolve(".venv/bin/python")));assertTrue(Files.readString(active().resolve(".venv/pyvenv.cfg")).contains("include-system-site-packages = false"));sawActualDependency=true;}
            if(python&&formalProject&&(stage==15||stage==27)){assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(0,result.getInt("exitCode"));assertTrue(result.toString(),result.optString("stderr").contains("Ran 5 tests")&&result.optString("stderr").contains("OK"));sawActualTests=true;}
            if(python&&formalProject&&(stage==19||stage==29)){assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(0,result.getInt("exitCode"));assertTrue(result.optString("stdout").contains("实际运行报告：dist/report.html"));sawPythonOutput=true;}
            if(python&&formalProject&&stage==33){assertTrue(result.getString("content").contains("继续同一个Python项目"));sawPythonReportRead=true;}
            if(!python&&stage==15){assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(0,result.getInt("exitCode"));assertTrue(result.toString(),result.getString("stdout").contains(formalProject?"toggle and filters describe the same selected item":"actual local dependency"));sawActualTests=true;}
            if(result.optString("stdout").contains("PYTHON_REPAIRED"))sawPythonOutput=true;
            if(result.optBoolean("ok",true)==false&&!result.optString("status").equals("failed"))throw new AssertionError("Unexpected real tool failure at stage "+stage+": "+result);
        }
        private void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(30000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IllegalArgumentException("fixture request bound");JSONObject request=new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8));requests.add(request);JSONObject message=next(request).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed()){failure=error;try{server.close();}catch(Exception ignored){}}}}
        @Override public void close()throws Exception {server.close();thread.join(2000);if(failure!=null)throw new AssertionError("fixture model",failure);}
        private String line(InputStream input)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int value;(value=input.read())!=-1;){if(value=='\n')return out.toString(StandardCharsets.US_ASCII).replace("\r","");if(out.size()>8192)throw new IllegalArgumentException("fixture header bound");out.write(value);}throw new IllegalStateException("fixture EOF");}
    }
}
