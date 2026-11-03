package dev.pocketengine.features.plugins;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.RuntimeTool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Synthetic package-provider executable over the real original SQLite/managed JNI pipe chain.
 * It proves approval/order/cancel/identity and protocol isolation, not a live registry download. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpLocalPackageNativeTest {
    private static final String SERVER="const fs=require('node:fs'),r=require('node:readline').createInterface({input:process.stdin});r.on('line',line=>{const q=JSON.parse(line);let result;if(q.method==='initialize')result={protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name:'Package fixture',version:'1'}};else if(q.method==='tools/list')result={tools:[{name:'lookup',inputSchema:{type:'object'}}]};else if(q.method==='tools/call'){fs.writeFileSync('RPC_EFFECT',JSON.stringify(q.params.arguments));result={content:[{type:'text',text:'Prepared dependency tool result'}]};}else return;process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:q.id,result})+'\\n');});";
    static final class Fixture implements AutoCloseable {
        final Path manager=Files.createTempFile("mcp-package-provider-",".py");final McpLocalDiscoveryReviewTest.Fixture base;final Path source;
        Fixture(int installExit,boolean tamper)throws Exception {this(installExit,tamper,SERVER);}
        Fixture(int installExit,boolean tamper,String server)throws Exception {
            writeUtf8(manager,"import sys,pathlib\nif sys.argv[1:]==['--version']:\n print('11.9.0')\nelse:\n print('PACKAGE_NON_PROTOCOL_STDOUT')\n print('ARGS:'+repr(sys.argv[1:]),file=sys.stderr,flush=True)\n pathlib.Path('PREPARATION_ATTEMPT').write_text('one')\n"+(tamper?" pathlib.Path('server.cjs').write_text('throw 1;')\n":"")+" sys.exit("+installExit+")\n");
            File python=new File(java.util.Objects.requireNonNull(System.getProperty("a25.python"))).getCanonicalFile();RuntimeTool provider=new RuntimeTool("npm",python,ProjectSources.sha256(Files.readAllBytes(python.toPath())),"11.9.0","Synthetic test provider; no third-party manager redistributed",List.of(manager.toString()),List.of("--version"));
            base=new McpLocalDiscoveryReviewTest.Fixture("node","server.cjs",server,Map.of("npm",provider));source=base.android.getFilesDir().toPath().resolve("local-development/checkouts").resolve(base.checkout).resolve("files");
            writeUtf8(source.resolve("package.json"),new JSONObject().put("name","owned-mcp-fixture").put("version","1.0.0").put("packageManager","npm@11.9.0").toString());
            writeUtf8(source.resolve("package-lock.json"),new JSONObject().put("name","owned-mcp-fixture").put("version","1.0.0").put("lockfileVersion",3).put("packages",new JSONObject().put("",new JSONObject().put("name","owned-mcp-fixture").put("version","1.0.0"))).toString());
            base.config.put("expectedSourceDigest",base.digest()).put(McpLocalPackage.FIELD,new JSONObject().put("manager","npm").put("version","11.9.0"));
        }
        JSONObject run()throws Exception{McpLocalDiscovery.Review review=base.review();JSONObject metadata=review.metadata();review.approvePreparation(metadata.getString("preparationDigest"));McpLocalDiscovery.Confirmed confirmed=review.confirm(metadata.getString("planDigest"));RunControl original=new RunControl();base.tasks.beginOwnerMcpDiscoveryForOwner(confirmed,original);return McpLocalDiscoveryPipe.run(confirmed,base.tasks,original);}
        @Override public void close()throws Exception{try{base.close();}finally{Files.deleteIfExists(manager);}}
    }
    @Test public void discoveryConfirmationCannotGrantDependencyInstallation()throws Exception {
        try(Fixture f=new Fixture(0,false);McpLocalDiscovery.Review review=f.base.review()){
            JSONObject view=review.metadata();assertTrue(view.getBoolean("preparationApprovalRequired"));assertFalse(view.getBoolean("preparationApproved"));assertFalse(Files.exists(f.source.resolve("PREPARATION_ATTEMPT")));
            assertThrows(SecurityException.class,()->review.confirm(view.getString("planDigest")));assertThrows(SecurityException.class,()->review.approvePreparation("0".repeat(64)));assertEquals(0,f.base.tasks.list().length());
            review.approvePreparation(view.getString("preparationDigest"));assertTrue(review.metadata().getBoolean("preparationApproved"));McpLocalDiscovery.Confirmed confirmed=review.confirm(view.getString("planDigest"));confirmed.close();
        }
    }
    @Test public void actualPreparationAndStdioShareOneOriginalJobAndOnlyServerStdoutIsProtocol()throws Exception {
        try(Fixture f=new Fixture(0,false)){
            JSONObject result=f.run();assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(1,f.base.tasks.list().length());assertEquals(1,f.base.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());
            Path copy=f.base.android.getFilesDir().toPath().resolve("local-development/mcp-discovery-copies").resolve(result.getString("taskId"));assertTrue(Files.exists(copy.resolve("PREPARATION_ATTEMPT")));assertFalse(Files.exists(f.source.resolve("PREPARATION_ATTEMPT")));
            assertTrue(result.getJSONObject("receipt").getBoolean("metadataAvailable"));assertTrue(result.getJSONObject("receipt").getJSONObject("processReceipt").getBoolean("resourcesReleased"));assertEquals(0,f.base.plugins.snapshot().getJSONArray("plugins").length());assertEquals(0,f.base.runtime.environmentManager().describe(f.base.pack).getInt("activeLeases"));
            assertFalse(result.toString().contains(managerBody(f)));assertFalse(result.toString().contains("pythonArgv"));
        }
    }
    @Test public void packageInitializationOutlivesOneInputAckBudgetWithoutResendingItsFrame()throws Exception {
        String delayed=SERVER.replace("const q=JSON.parse(line);let result;","const q=JSON.parse(line);if(q.method==='initialize'){fs.appendFileSync('INITIALIZE_REQUESTS','once');Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,21000);}let result;");
        try(Fixture f=new Fixture(0,false,delayed)){
            long started=System.nanoTime();JSONObject result=f.run();
            assertEquals(result.toString(),"completed",result.getString("status"));
            assertTrue("The real initialize reply must arrive after the original 20-second ACK budget",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>20000);
            Path copy=f.base.android.getFilesDir().toPath().resolve("local-development/mcp-discovery-copies").resolve(result.getString("taskId"));
            assertEquals("once",readUtf8(copy.resolve("INITIALIZE_REQUESTS")));
            assertTrue(result.getJSONObject("receipt").getBoolean("metadataAvailable"));
            assertTrue(result.getJSONObject("receipt").getJSONObject("processReceipt").getBoolean("resourcesReleased"));
            assertEquals(1,f.base.tasks.list().length());assertEquals(1,f.base.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());
            assertEquals(0,f.base.runtime.environmentManager().describe(f.base.pack).getInt("activeLeases"));
        }
    }
    private static String managerBody(Fixture f){return f.manager.toString();}
    @Test public void preparationFailureOrChangedSourceNeverBecomesDiscoveredOrInstalled()throws Exception {
        for(boolean tamper:new boolean[]{false,true})try(Fixture f=new Fixture(tamper?0:19,tamper)){
            JSONObject result=f.run();assertEquals(result.toString(),"failed",result.getString("status"));assertFalse(result.getJSONObject("receipt").getBoolean("metadataAvailable"));assertEquals(0,f.base.plugins.snapshot().getJSONArray("plugins").length());assertEquals(1,f.base.tasks.list().length());assertEquals(0,f.base.runtime.environmentManager().describe(f.base.pack).getInt("activeLeases"));
        }
    }
    @Test public void failedPreparationDiagnosticsReadOnlyTheOriginalPagedLogAfterSourceChanges()throws Exception {
        try(Fixture f=new Fixture(19,false);dev.pocketengine.features.FeatureHost host=new dev.pocketengine.features.FeatureHost(e->{},f.base.tasks,()->false)){
            writeUtf8(f.manager,readUtf8(f.manager).replace(" sys.exit(19)"," print('DIAGNOSTIC_START:'+('x'*20000)+':DIAGNOSTIC_END',file=sys.stderr,flush=True)\n sys.exit(19)"));
            host.register(f.base.workspace);host.register(f.base.local);host.register(f.base.plugins);var owner=new dev.pocketengine.features.NativeOwnerWorkflows(host,f.base.context,f.base.tasks,new dev.pocketengine.features.MemorySourceBridge(host,f.base.tasks));
            JSONObject result=f.run();String task=result.getString("taskId");assertEquals("failed",result.getString("status"));assertFalse(result.toString().contains("DIAGNOSTIC_START"));String original=ProjectSources.digest(f.base.tasks.ownerMcpDiscoveryForOwner(task));
            writeUtf8(f.source.resolve("server.cjs"),"throw Error('a different source revision');");
            JSONObject request=new JSONObject().put("taskId",task).put("cursor",0).put("maxBytes",16384),page=owner.mcp("readLocalMcpDiagnostic",request);assertTrue(page.getBoolean("queryOnly"));assertFalse(page.getBoolean("replayAllowed"));assertFalse(page.getBoolean("modelDelivery"));assertTrue(page.getBoolean("hasMore"));assertEquals(original,ProjectSources.digest(f.base.tasks.ownerMcpDiscoveryForOwner(task)));
            JSONObject repeated=owner.mcp("readLocalMcpDiagnostic",request);assertEquals(ProjectSources.digest(page),ProjectSources.digest(repeated));StringBuilder text=new StringBuilder();int pages=0;
            while(true){text.append(dev.pocketengine.ui.McpLocalDiscoveryRules.INSTANCE.diagnosticText(page));pages++;if(!page.getBoolean("hasMore"))break;assertTrue(pages<8);page=owner.mcp("readLocalMcpDiagnostic",new JSONObject().put("taskId",task).put("cursor",page.getLong("nextCursor")).put("maxBytes",16384));}
            assertTrue(text.toString().contains("PACKAGE_NON_PROTOCOL_STDOUT"));assertTrue(text.toString().contains("ARGS:"));assertTrue(text.toString().contains("DIAGNOSTIC_START:"));assertTrue(text.toString().contains(":DIAGNOSTIC_END"));assertEquals(original,ProjectSources.digest(f.base.tasks.ownerMcpDiscoveryForOwner(task)));assertEquals(1,f.base.tasks.list().length());assertEquals(1,f.base.tasks.get(task,false).getJSONArray("operations").length());assertEquals(0,f.base.runtime.environmentManager().describe(f.base.pack).getInt("activeLeases"));
            assertThrows(IllegalArgumentException.class,()->owner.mcp("readLocalMcpDiagnostic",new JSONObject().put("taskId",task).put("cursor",0.5)));assertThrows(IllegalArgumentException.class,()->owner.mcp("readLocalMcpDiagnostic",new JSONObject().put("taskId",task).put("maxBytes",65537)));
            String ordinary=f.base.tasks.createCapabilityTask("An ordinary task has no owner MCP log authority","").getString("id");assertThrows(SecurityException.class,()->owner.mcp("readLocalMcpDiagnostic",new JSONObject().put("taskId",ordinary)));
        }
    }
    @Test public void changedLockCannotReuseTheIndependentlyApprovedPreparation()throws Exception {
        try(Fixture f=new Fixture(0,false);McpLocalDiscovery.Review review=f.base.review()){
            JSONObject view=review.metadata();review.approvePreparation(view.getString("preparationDigest"));writeUtf8(f.source.resolve("package-lock.json"),"{}");assertThrows(Exception.class,()->review.confirm(view.getString("planDigest")));assertEquals(0,f.base.tasks.list().length());
        }
    }
    @Test public void installedPackageToolUsesOriginalAgentApprovalDebitAndOneManagedInvocation()throws Exception {
        try(Fixture prepared=new Fixture(0,false);McpLocalInvocationNativeTest.Fixture x=new McpLocalInvocationNativeTest.Fixture(prepared.base,false);McpLocalInvocationNativeTest.Call call=x.call()){
            JSONObject plan=x.registry.getJSONObject("executionBinding").getJSONObject("localProgram").getJSONObject(McpLocalPackage.PLAN);
            assertEquals("npm",plan.getString("manager"));assertEquals("11.9.0",plan.getString("managerVersion"));assertFalse(plan.getBoolean("toolInvocationAuthorized"));
            JSONObject result=call.invoke();assertEquals(result.toString(),"response_received",result.getString("status"));assertEquals("Prepared dependency tool result",result.getJSONObject("toolResult").getJSONArray("content").getJSONObject(0).getString("text"));
            assertTrue(Files.exists(x.copy(call.operation).resolve("PREPARATION_ATTEMPT")));assertTrue(Files.exists(x.copy(call.operation).resolve("RPC_EFFECT")));assertFalse(Files.exists(prepared.source.resolve("RPC_EFFECT")));
            assertTrue(result.getJSONObject("nativeMcp").getBoolean("processSettled"));assertEquals(1,x.f.tasks.get(call.task,false).getJSONArray("operations").length());assertEquals(1,x.f.tasks.get(call.task,false).getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(2,x.f.tasks.list().length());assertEquals(0,x.f.runtime.environmentManager().describe(x.f.pack).getInt("activeLeases"));
            assertFalse(result.toString().contains("pythonArgv"));assertFalse(result.toString().contains(prepared.manager.toString()));
        }
    }
    @Test public void cancelDuringPackagePreparationStopsOnlyThatOriginalJobAndCannotReplayItsConfirmation()throws Exception {
        try(Fixture f=new Fixture(0,false)){
            writeUtf8(f.manager,"import sys,pathlib,time\nif sys.argv[1:]==['--version']:\n print('11.9.0')\nelse:\n pathlib.Path('PREPARATION_ATTEMPT').write_text('one')\n print('PREPARATION_WAITING',file=sys.stderr,flush=True)\n time.sleep(60)\n");
            McpLocalDiscovery.Review review=f.base.review();JSONObject metadata=review.metadata();review.approvePreparation(metadata.getString("preparationDigest"));McpLocalDiscovery.Confirmed confirmed=review.confirm(metadata.getString("planDigest"));RunControl original=new RunControl();f.base.tasks.beginOwnerMcpDiscoveryForOwner(confirmed,original);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> pending=worker.submit(()->McpLocalDiscoveryPipe.run(confirmed,f.base.tasks,original));Path copy=f.base.android.getFilesDir().toPath().resolve("local-development/mcp-discovery-copies").resolve(confirmed.actionId());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!Files.exists(copy.resolve("PREPARATION_ATTEMPT"))&&System.nanoTime()<until)Thread.sleep(20);assertTrue(Files.exists(copy.resolve("PREPARATION_ATTEMPT")));
                f.base.tasks.cancelOwnerMcpDiscoveryForOwner(confirmed.actionId());original.cancel();JSONObject result=pending.get(12,TimeUnit.SECONDS);assertNotEquals("completed",result.getString("status"));assertFalse(result.getJSONObject("receipt").getBoolean("metadataAvailable"));assertEquals(1,f.base.tasks.list().length());assertEquals(0,f.base.plugins.snapshot().getJSONArray("plugins").length());assertEquals(0,f.base.runtime.environmentManager().describe(f.base.pack).getInt("activeLeases"));
                assertThrows(Exception.class,()->review.confirm(metadata.getString("planDigest")));assertThrows(Exception.class,()->confirmed.invocationForHost(f.base.tasks,original,()->{}));assertEquals("one",readUtf8(copy.resolve("PREPARATION_ATTEMPT")));
            }finally{original.cancel();worker.shutdownNow();}
        }
    }
    @Test public void preparedToolLosingItsBusinessReplyStaysUnknownAfterRealProcessCleanup()throws Exception {
        try(Fixture prepared=new Fixture(0,false,SERVER.replace("result={content:","process.exit(0);result={content:"));McpLocalInvocationNativeTest.Fixture x=new McpLocalInvocationNativeTest.Fixture(prepared.base,false);McpLocalInvocationNativeTest.Call call=x.call()){
            JSONObject result=call.invoke();assertEquals(result.toString(),"unknown",result.getString("status"));assertTrue(result.getBoolean("rpcMayHaveDispatched"));assertFalse(result.getBoolean("replayAllowed"));assertTrue(result.getJSONObject("nativeMcp").getBoolean("processSettled"));assertTrue(Files.exists(x.copy(call.operation).resolve("RPC_EFFECT")));assertEquals(0,x.f.runtime.environmentManager().describe(x.f.pack).getInt("activeLeases"));
            call.journal.finishOperation(call.operation,new JSONObject().put("status","completed").put("ok",true));assertTrue(x.f.tasks.hasUnresolved(call.task));assertEquals("unknown",call.record().publicReceiptForHost().getString("status"));assertEquals(1,x.f.tasks.get(call.task,false).getJSONArray("operations").length());
        }
    }
    @Test public void longActualPreparedToolReplyReachesTheOriginalOutputStoreWithoutAnotherOperation()throws Exception {
        try(Fixture prepared=new Fixture(0,false,SERVER.replace("'Prepared dependency tool result'","'prepared-long-start:'+'x'.repeat(120000)+':prepared-long-end'"));McpLocalInvocationNativeTest.Fixture x=new McpLocalInvocationNativeTest.Fixture(prepared.base,false);McpLocalInvocationNativeTest.Call call=x.capturedCall()){
            JSONObject result=call.invoke();assertEquals("response_received",result.getString("status"));JSONObject reference=result.getJSONObject("outputCapture").getJSONArray("outputRefs").getJSONObject(0);assertEquals(call.operation,reference.getString("operationId"));assertEquals("MCP_RESULT_JSON",reference.getString("captureKind"));
            StringBuilder retained=new StringBuilder();try(android.database.Cursor rows=x.db().rawQuery("SELECT body FROM task_output_chunks",null)){while(rows.moveToNext())retained.append(rows.getString(0));}assertTrue(retained.length()>120000);assertTrue(retained.toString().contains("prepared-long-start:"));assertTrue(retained.toString().contains(":prepared-long-end"));assertEquals(1,x.f.tasks.get(call.task,false).getJSONArray("operations").length());assertEquals(0,x.f.runtime.environmentManager().describe(x.f.pack).getInt("activeLeases"));
        }
    }
    @Test public void pnpmLockRequiresIntegrityForEveryVersionAndRejectsAlternativeSourcesBeforeApproval()throws Exception {
        try(Fixture f=new Fixture(0,false)){
            Files.delete(f.source.resolve("package-lock.json"));writeUtf8(f.source.resolve("package.json"),new JSONObject().put("name","owned-fixture").put("version","1.0.0").put("packageManager","pnpm@10.27.0").put("dependencies",new JSONObject().put("example","1.0.0")).toString());
            String prefix="lockfileVersion: '9.0'\nsettings:\n  autoInstallPeers: true\nimporters:\n  .:\n    dependencies:\n      example:\n        specifier: 1.0.0\n        version: 1.0.0\npackages:\n  example@1.0.0:\n",integrity="    resolution: {integrity: sha512-"+"A".repeat(86)+"==}\n",suffix="snapshots:\n  example@1.0.0: {}\n";
            JSONObject tools=f.base.runtime.mcpPackageToolsForHost("npm").put("managerVersion","10.27.0");f.base.config.put(McpLocalPackage.FIELD,new JSONObject().put("manager","pnpm").put("version","10.27.0"));
            for(String lock:new String[]{prefix+integrity+"  missing@2.0.0:\n    engines: {node: '>=18'}\n"+suffix,prefix+"    resolution: {tarball: https://unapproved.example/package.tgz}\n"+suffix}){
                writeUtf8(f.source.resolve("pnpm-lock.yaml"),lock);JSONObject source=frozen(f);assertThrows(IllegalArgumentException.class,()->McpLocalPackage.inspect(f.base.config,source,tools));
            }
            writeUtf8(f.source.resolve("pnpm-lock.yaml"),prefix+integrity+suffix);assertEquals(1,McpLocalPackage.inspect(f.base.config,frozen(f),tools).plan().getInt("lockedPackageCount"));assertEquals(0,f.base.tasks.list().length());
        }
    }
    private static void writeUtf8(Path path,String text)throws java.io.IOException {Files.write(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String readUtf8(Path path)throws java.io.IOException {return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();}
    private static JSONObject frozen(Fixture f)throws Exception {
        JSONArray files=new JSONArray();try(var paths=Files.list(f.source)){for(Path file:paths.sorted().toList())if(Files.isRegularFile(file)&&!file.getFileName().toString().startsWith("."))files.put(new JSONObject().put("path",file.getFileName().toString()).put("content",readUtf8(file)));}
        return new JSONObject().put("checkout",new JSONObject().put("id",f.base.checkout).put("projectId",f.base.project)).put("files",files);
    }
}
