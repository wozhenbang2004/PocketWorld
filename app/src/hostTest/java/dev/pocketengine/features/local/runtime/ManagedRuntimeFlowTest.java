package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Actual desktop JNI/tools/files; explicitly not Android ABI or installed-phone evidence. */
public final class ManagedRuntimeFlowTest {
    private Path directory,active;private ManagedProjectRuntime runtime;
    private RuntimeEnvironment.Package pack;private byte[] archive;private Map<String,RuntimeTool> tools;
    private RunControl original;private JSONObject snapshot;
    @Before public void create() throws Exception {
        directory=Files.createTempDirectory("a25f-managed-");active=Files.createDirectory(directory.resolve("active"));
        byte[] data="independently authored runtime fixture data\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {zip.putNextEntry(new ZipEntry("share/runtime.txt"));zip.write(data);zip.closeEntry();}
        archive=bytes.toByteArray();
        pack=new RuntimeEnvironment.Package(new JSONObject().put("id","desktop-fixture").put("revision","1")
                .put("license","CC0-1.0; independently authored fixture, no third-party runtime redistributed")
                .put("abi","desktop-host-only").put("archiveSha256",ProjectSources.sha256(archive)).put("archiveBytes",archive.length)
                .put("files",new JSONArray().put(new JSONObject().put("path","share/runtime.txt").put("bytes",data.length).put("sha256",ProjectSources.sha256(data)))));
        File shell=new File("/bin/sh").getCanonicalFile(),python=new File(System.getProperty("a25.python")).getCanonicalFile(),node=new File(System.getProperty("a25.node")).getCanonicalFile();
        tools=Map.of("sh",tool("sh",shell,List.of("-c","printf '%s\\n' shell-ready")),
                "python3",tool("python3",python,List.of("--version")),"node",tool("node",node,List.of("--version")));
        runtime=ManagedProjectRuntime.create(directory.toFile(),pack,tools);
        runtime.environmentManager().install(pack,new ByteArrayInputStream(archive),()->{},(phase,done,total)->{});
        original=new RunControl();original.bindHostRun("task","run",1);
        snapshot=new JSONObject().put("checkout",new JSONObject().put("id","11111111-1111-1111-1111-111111111111").put("projectId","project"))
                .put("files",new JSONArray());
    }
    private static RuntimeTool tool(String name,File file,List<String> probe) throws Exception {
        return new RuntimeTool(name,file,RuntimeFiles.sha256(file),"host-validation-only","host-tool-not-redistributed",List.of(),probe);
    }
    @After public void close() throws Exception {
        if(runtime!=null)runtime.close();
        if(directory!=null)try(var paths=Files.walk(directory)) {for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private void source(String path,String value) throws Exception {
        Path file=active.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,value);
        JSONArray files=snapshot.getJSONArray("files");
        for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path"))){files.getJSONObject(i).put("content",value);return;}
        files.put(new JSONObject().put("path",path).put("content",value));
    }
    private RuntimeInvocation invocation(String operation,String command,String executable,JSONArray argv,ProjectRuntime.LiveGate gate) throws Exception {
        JSONObject source=ProjectSources.describe(snapshot),payload=new JSONObject().put("checkoutId",source.getString("checkoutId"));
        if(!command.endsWith("probe"))payload.put("expectedSourceDigest",source.getString("sourceDigest"));
        if(executable!=null)payload.put("executable",executable).put("argv",argv);
        JSONObject exact=new JSONObject().put("feature","local").put("command",command).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID)
                .put("runtimeIdentity",runtime.describe().getString("identity")).put("projectId","project").put("checkoutId",source.getString("checkoutId"))
                .put("sourceDigest",source.getString("sourceDigest")).put("planDigest","a".repeat(64)).put("stepId",operation).put("intent","build")
                .put("payload",payload).put("requestDigest",ProjectSources.digest(payload));
        JSONObject binding=new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",1).put("operationId",operation);
        return new RuntimeInvocation(exact,binding,snapshot,original,gate,new RuntimeWorkspace(active.toFile(),"project",source.getString("checkoutId")));
    }
    private RuntimeReceipt waitFor(RuntimeExecutionRef ref) throws Exception {
        long until=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<until) {RuntimeReceipt receipt=runtime.query(ref);if(receipt.json().optBoolean("receiptReceived"))return receipt;Thread.sleep(20);}
        throw new AssertionError("actual process did not finish in validation budget");
    }
    @Test public void actualNodeBuildFailureRepairAndFilesUseOneActiveTree() throws Exception {
        source("styles.css","body{color:navy}\n");source("client.js","console.log('actual source asset');\n");
        source("helper.mjs","export const title='First';\n");
        source("build.mjs","import broken SYNTAX\n");
        RuntimeInvocation probe=invocation("probe","project.runtime.probe",null,null,()->{});runtime.dispatch(probe);
        RuntimeReceipt probed=waitFor(probe.executionRef());assertEquals(probed.json().toString(),0,probed.json().getInt("exitCode"));
        RuntimeInvocation bad=invocation("bad","project.runtime.run","node",new JSONArray().put("build.mjs"),()->{});
        runtime.dispatch(bad);RuntimeReceipt failed=waitFor(bad.executionRef());assertNotEquals(0,failed.json().getInt("exitCode"));
        assertTrue(failed.json().getString("stderr").contains("SyntaxError"));assertEquals("failed",failed.status());
        source("build.mjs","import{mkdirSync,writeFileSync,copyFileSync}from'node:fs';import{title}from'./helper.mjs';mkdirSync('dist',{recursive:true});writeFileSync('dist/index.html','<!doctype html><link rel=stylesheet href=styles.css><h1>'+title+'</h1><script src=client.js></script>');copyFileSync('styles.css','dist/styles.css');copyFileSync('client.js','dist/client.js');console.log('BUILT '+title);\n");
        RuntimeInvocation fixed=invocation("fixed","project.runtime.run","node",new JSONArray().put("build.mjs"),()->{});
        runtime.dispatch(fixed);RuntimeReceipt built=waitFor(fixed.executionRef());assertEquals(built.json().toString(),0,built.json().getInt("exitCode"));
        assertEquals("BUILT First\n",built.json().getString("stdout"));assertTrue(Files.readString(active.resolve("dist/index.html")).contains("First"));
        assertEquals(Files.readString(active.resolve("styles.css")),Files.readString(active.resolve("dist/styles.css")));
        source("helper.mjs","export const title='Modified in the SAME project';\n");
        RuntimeInvocation next=invocation("continued","project.runtime.run","node",new JSONArray().put("build.mjs"),()->{});
        runtime.dispatch(next);assertEquals(0,waitFor(next.executionRef()).json().getInt("exitCode"));
        assertTrue(Files.readString(active.resolve("dist/index.html")).contains("Modified in the SAME project"));
        runtime.close();runtime=ManagedProjectRuntime.create(directory.toFile(),pack,tools);
        assertEquals(0,runtime.query(fixed.executionRef()).json().getInt("exitCode"));
        System.out.println("A25F actualDesktopJNI=true; NodeBuildFailureThenRepair=true; sameActiveTree=true; persistedOriginalReceipt=true; AndroidDevice=false");
    }
    @Test public void rawLogArrivesBeforeCompletionAndOriginalCancelActuallyEndsPython() throws Exception {
        java.util.concurrent.CountDownLatch delivered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger deliveries=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<JSONObject> finalReceipt=new java.util.concurrent.atomic.AtomicReference<>();
        runtime.setReceiptSink(receipt->{deliveries.incrementAndGet();finalReceipt.set(receipt);delivered.countDown();});
        source("main.py","import sys,time\nprint('STREAM_BEFORE_EXIT',flush=True)\nprint('REAL_STDERR',file=sys.stderr,flush=True)\ntime.sleep(30)\n");
        RuntimeInvocation invocation=invocation("stream","project.runtime.run","python3",new JSONArray().put("-u").put("main.py"),()->{});
        runtime.dispatch(invocation);long until=System.nanoTime()+5_000_000_000L;JSONObject page=null;
        while(System.nanoTime()<until) {page=runtime.readLog(invocation.executionRef(),0,65536);if(page.getJSONArray("chunks").length()>0)break;Thread.sleep(20);}
        assertNotNull(page);assertTrue(page.toString(),page.getJSONArray("chunks").length()>0);
        assertFalse(runtime.query(invocation.executionRef()).json().getBoolean("receiptReceived"));
        RuntimeReceipt stopping=runtime.requestCancel(invocation.executionRef());assertTrue(stopping.json().getBoolean("cancelRequested"));
        RuntimeReceipt stopped=waitFor(invocation.executionRef());assertTrue(stopped.json().getBoolean("processExited"));
        assertTrue(stopped.json().getBoolean("resourcesReleased"));assertNotEquals(0,stopped.json().getInt("exitCode"));
        assertTrue(stopped.json().getInt("terminationSignal")>0);assertEquals("failed",stopped.status());
        assertTrue(delivered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,deliveries.get());
        assertTrue(finalReceipt.get().getBoolean("processExited"));assertTrue(finalReceipt.get().getString("stdout").contains("STREAM_BEFORE_EXIT"));
        assertEquals("python3",finalReceipt.get().getString("requestedExecutable"));
    }
    @Test public void pauseOrActiveSourceChangePreventsLaunchAndChangedEnvironmentNeedsReview() throws Exception {
        source("main.py","from pathlib import Path\nPath('SHOULD_NOT_EXIST').write_text('bad')\n");
        RuntimeInvocation changed=invocation("changed","project.runtime.run","python3",new JSONArray().put("main.py"),()->{});
        Files.writeString(active.resolve("main.py"),"different actual bytes");
        assertThrows(SecurityException.class,()->runtime.dispatch(changed));assertFalse(Files.exists(active.resolve("SHOULD_NOT_EXIST")));
        source("main.py","print('valid')\n");RuntimeInvocation paused=invocation("paused","project.runtime.run","python3",new JSONArray().put("main.py"),()->{});
        original.pause();original.resume();assertThrows(SecurityException.class,()->runtime.dispatch(paused));
        RuntimeInvocation approved=invocation("old_review","project.runtime.run","python3",new JSONArray().put("main.py"),()->{});
        JSONObject next=pack.descriptor().put("revision","new-license-revision").put("license","CHANGED");
        runtime.close();runtime=ManagedProjectRuntime.create(directory.toFile(),new RuntimeEnvironment.Package(next),tools);
        assertThrows(SecurityException.class,()->runtime.dispatch(approved));assertFalse(runtime.describe().getBoolean("canExecute"));
    }
    @Test public void corruptPackageAndCancelledInstallDoNotBecomeInstalledOrDeleteProject() throws Exception {
        RuntimeEnvironment manager=new RuntimeEnvironment(Files.createDirectory(directory.resolve("other")).toFile(),identity->{});
        byte[] wrong=archive.clone();wrong[wrong.length/2]^=1;
        assertThrows(SecurityException.class,()->manager.install(pack,new ByteArrayInputStream(wrong),()->{},(phase,done,total)->{}));
        assertFalse(manager.describe(pack).getBoolean("installed"));
        AtomicBoolean live=new AtomicBoolean(true);
        assertThrows(SecurityException.class,()->manager.install(pack,new ByteArrayInputStream(archive),()->{if(!live.get())throw new SecurityException("cancelled");},(phase,done,total)->{if(phase.equals("receiving")&&done>0)live.set(false);}));
        assertFalse(manager.describe(pack).getBoolean("installed"));
        manager.install(pack,new ByteArrayInputStream(archive),()->{},(phase,done,total)->{});
        AutoCloseable lease=manager.acquire(pack);assertThrows(IllegalStateException.class,()->manager.uninstall(pack,()->{}));lease.close();
        source("retained.py","print('retained project')\n");assertEquals("uninstalled",manager.uninstall(pack,()->{}).getString("status"));
        assertTrue(Files.exists(active.resolve("retained.py")));assertFalse(manager.describe(pack).getBoolean("installed"));
    }
    @Test public void coldObserverReportsPendingAsUnknownAndDoesNotRelaunchIt() throws Exception {
        source("pending.py","import time\nfrom pathlib import Path\np=Path('launch-count');p.write_text(p.read_text()+'x' if p.exists() else 'x')\nprint('started',flush=True)\ntime.sleep(30)\n");
        RuntimeInvocation call=invocation("pending","project.runtime.run","python3",new JSONArray().put("-u").put("pending.py"),()->{});
        runtime.dispatch(call);long until=System.nanoTime()+5_000_000_000L;
        while(!Files.exists(active.resolve("launch-count"))&&System.nanoTime()<until)Thread.sleep(20);
        assertTrue(Files.exists(active.resolve("launch-count")));
        try(ManagedProjectRuntime cold=ManagedProjectRuntime.create(directory.toFile(),pack,tools)) {
            RuntimeReceipt unknown=cold.query(call.executionRef());assertEquals("unknown",unknown.status());
            assertFalse(unknown.json().getBoolean("receiptReceived"));
            assertEquals("unknown",cold.dispatch(call).status());
            assertEquals("x",Files.readString(active.resolve("launch-count")));
        }
        runtime.requestCancel(call.executionRef());assertTrue(waitFor(call.executionRef()).json().getBoolean("processExited"));
    }
    @Test public void nativeEntryVetoNeverStartsAProcessAndMissingExecutableIsNotExitZero() throws Exception {
        NativeProcessSupervisor nativeProcess=new NativeProcessSupervisor();
        File marker=active.resolve("entry-was-launched").toFile();
        NativeProcessSupervisor.Observer cancelled=new NativeProcessSupervisor.Observer(){
            @Override public boolean output(int stream,byte[] bytes){return true;}
            @Override public boolean cancelled(){return true;}
        };
        NativeProcessSupervisor.Exit veto=nativeProcess.execute(List.of("/bin/sh","-c","touch entry-was-launched"),Map.of(),active.toFile(),5000,100,cancelled);
        assertTrue(veto.cancelled);assertNotEquals(0,veto.launchErrno);assertFalse(marker.exists());assertTrue(veto.groupAbsent);
        NativeProcessSupervisor.Exit missing=nativeProcess.execute(List.of("/pocketworld-no-such-executable"),Map.of(),active.toFile(),5000,100,
                new NativeProcessSupervisor.Observer(){@Override public boolean output(int stream,byte[] bytes){return true;}@Override public boolean cancelled(){return false;}});
        assertNotEquals(0,missing.launchErrno);assertEquals(127,missing.exitCode);assertTrue(missing.groupAbsent);
    }
}
