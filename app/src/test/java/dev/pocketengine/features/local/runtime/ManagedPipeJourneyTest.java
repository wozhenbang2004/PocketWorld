package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.json.*;
import org.junit.Test;

/** Actual original installed managed environment/source/Job/JNI. Explicit fake native
 * approval sink only: does not claim the still-separate MCP owner UI/TaskStore factory. */
public final class ManagedPipeJourneyTest {
    private static final String SERVER="import sys\nprint('OWN_READY',flush=True)\nprint('OWN_DIAGNOSTIC',file=sys.stderr,flush=True)\nfor line in sys.stdin: print('OWN:'+line.rstrip(),flush=True)\nprint('OWN_EOF',flush=True)\n";
    private static final class Approval implements NativePipeAdmission.Authorization {
        final RuntimeInvocation original;final AtomicBoolean live=new AtomicBoolean(true),claimed=new AtomicBoolean();final List<JSONObject> receipts=new CopyOnWriteArrayList<>();final CountDownLatch finalReceipt=new CountDownLatch(1);
        Approval(RuntimeInvocation original){this.original=original;}
        public void requireCurrent(RuntimeInvocation invocation,JSONObject binding)throws Exception{if(invocation!=original||!live.get())throw new SecurityException("own fixture native approval revoked");invocation.checkCurrent();assertEquals(original.exact().getString("intent"),binding.getString("purpose"));}
        public void claimBeforeDispatch(RuntimeInvocation invocation,JSONObject binding)throws Exception{requireCurrent(invocation,binding);if(!claimed.compareAndSet(false,true))throw new SecurityException("own fixture original dispatch fence already consumed");}
        public void recordOriginalReceipt(RuntimeReceipt actual)throws Exception{original.executionRef().requireReceipt(actual.json());if(!claimed.get())throw new SecurityException("receipt before original claim");receipts.add(actual.json());if(actual.json().optBoolean("resourcesReleased"))finalReceipt.countDown();}
    }
    private static final class Run {
        final RuntimeInvocation invocation;final RunControl control;final JSONObject identity;final Approval approval;final ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();NativePipeAdmission admission;ManagedProcessJobs.PipeHandle handle;
        Run(Fixture f,int epoch,String purpose)throws Exception {
            control=new RunControl();control.bindHostRun("original-task","run-"+epoch,epoch);JSONObject binding=new JSONObject().put("taskId","original-task").put("runId","run-"+epoch).put("coordinatorEpoch",epoch).put("operationId",UUID.randomUUID().toString());
            identity=new JSONObject().put("purpose",purpose).put("configDigest","b".repeat(64)).put("runtimeGeneration",f.runtime.identity());
            if(NativePipeAdmission.INVOKE.equals(purpose))identity.put("installationId",f.installation).put("stateRevision",0);
            else identity.put("configurationId",UUID.randomUUID().toString()).put("expectedInstallationId","absent").put("expectedStateRevision",JSONObject.NULL);
            String digest=ProjectSources.describe(f.source).getString("sourceDigest");
            JSONArray argv=new JSONArray();if("python3".equals(f.executable))argv.put("-u");argv.put(f.entry);
            JSONObject payload=ProjectCommandSpec.normalize(new JSONObject().put("executable",f.executable).put("argv",argv).put("terminal","pipes").put("runMode","owned_service").put("timeoutMillis",10000)).put("checkoutId",f.checkout).put("expectedSourceDigest",digest).put("nativePipeContract",NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT).put("nativePipeWriteBudget",65536).put("nativePipeBindingDigest",ProjectSources.digest(NativePipeAdmission.bindingForHost(identity,binding,digest,65536)));
            JSONObject exact=new JSONObject().put("feature","local").put("command","project.runtime.run").put("projectId","actual-own-source").put("checkoutId",f.checkout).put("sourceDigest",digest).put("planDigest","a".repeat(64)).put("stepId","native-pipe").put("intent",purpose).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("runtimeIdentity",f.runtime.identity()).put("payload",payload).put("requestDigest",ProjectSources.digest(payload));
            invocation=new RuntimeInvocation(exact,binding,f.source,control,()->{},new RuntimeWorkspace(f.active.toFile(),"actual-own-source",f.checkout));approval=new Approval(invocation);
        }
        void admit(Fixture f)throws Exception{admission=f.runtime.admitPipesForHost(invocation,identity,approval,(stream,bytes)->{synchronized(this){(stream==1?stdout:stderr).write(bytes);}return true;});}
        void start(Fixture f)throws Exception{if(admission==null)admit(f);handle=f.runtime.dispatchPipesForHost(invocation,admission);}
        synchronized String text(){return new String(stdout.toByteArray(),StandardCharsets.UTF_8);}
        JSONObject until(java.util.function.Predicate<JSONObject> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);JSONObject receipt=null;do{receipt=handle.query();if(condition.test(receipt))return receipt;Thread.sleep(10);}while(System.nanoTime()<end);throw new AssertionError("same original pipe did not settle: "+receipt);}
        void ready()throws Exception{until(r->text().contains("OWN_READY"));}
        JSONObject finished()throws Exception{JSONObject observed=until(r->r.optBoolean("resourcesReleased")&&!r.optBoolean("ownedWorkerLive"));assertTrue("Original native receipt callback must actually settle",approval.finalReceipt.await(3,TimeUnit.SECONDS));return observed;}
    }
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("pw-original-managed-pipe-"),active=Files.createDirectory(root.resolve("real-frozen-workspace"));final String checkout=UUID.randomUUID().toString(),installation=UUID.randomUUID().toString();
        final String executable,entry;final Map<String,RuntimeTool> tools=new LinkedHashMap<>();final RuntimeEnvironment.Package pack;final ManagedProjectRuntime runtime;final JSONObject source;final List<Run> runs=new ArrayList<>();boolean expectedUnresolved;
        Fixture()throws Exception {this("python3","server.py",SERVER);}
        Fixture(String executable,String entry,String program)throws Exception {
            this.executable=executable;this.entry=entry;
            byte[] marker="own marker, no interpreter redistribution\n".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(buffer)){zip.putNextEntry(new ZipEntry("share/marker"));zip.write(marker);zip.closeEntry();}byte[] archive=buffer.toByteArray();
            pack=new RuntimeEnvironment.Package(new JSONObject().put("id","own-native-pipe-fixture").put("revision","1").put("license","CC0-1.0 own marker; host binary not redistributed").put("abi","linux-host-only").put("archiveBytes",archive.length).put("archiveSha256",ProjectSources.sha256(archive)).put("files",new JSONArray().put(new JSONObject().put("path","share/marker").put("bytes",marker.length).put("sha256",ProjectSources.sha256(marker)))));
            tools.put(executable,tool(executable,Path.of(Objects.requireNonNull(System.getProperty(executable.equals("node")?"a25.node":"a25.python"))).toRealPath().toFile()));tools.put("sh",tool("sh",new File("/bin/sh").getCanonicalFile()));
            runtime=ManagedProjectRuntime.create(root.resolve("runtime").toFile(),pack,tools);runtime.environmentManager().install(pack,new ByteArrayInputStream(archive),()->{},(phase,done,total)->{});
            runtime.setReceiptSink(value->{throw new AssertionError("MCP must use its original native TaskStore receipt sink, never project/Skill receipt routing");});
            Files.write(active.resolve(entry),program.getBytes(StandardCharsets.UTF_8));source=new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId","actual-own-source")).put("files",new JSONArray().put(new JSONObject().put("path",entry).put("content",program)));
        }
        static RuntimeTool tool(String name,File binary)throws Exception{return new RuntimeTool(name,binary,ProjectSources.sha256(Files.readAllBytes(binary.toPath())),"own exact host fixture","host binary not redistributed",List.of(),List.of("--version"));}
        Run run(int epoch,String purpose)throws Exception{Run run=new Run(this,epoch,purpose);runs.add(run);return run;}
        long leases()throws Exception{return runtime.environmentManager().describe(pack).getLong("activeLeases");}
        @Override public void close()throws Exception {for(Run run:runs)if(run.handle!=null)run.handle.requestCancel(run.control);if(expectedUnresolved){assertThrows(IllegalStateException.class,runtime::close);return;}runtime.close();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
    @Test public void realSourceOriginalJobAndOpaqueHandleDeliverOnceThenOrderedEofReleasesSameLease()throws Exception {
        try(Fixture f=new Fixture()){
            Run run=f.run(1,NativePipeAdmission.INVOKE);run.start(f);run.ready();assertEquals(1,f.leases());assertTrue(run.approval.claimed.get());
            byte[] line="literal $(not a shell) 中文😀\n".getBytes(StandardCharsets.UTF_8);
            JSONObject one=run.handle.write(run.control,"original-rpc",line,()->{},2000);assertEquals("delivered",one.getString("status"));
            JSONObject same=run.handle.write(run.control,"original-rpc",line,()->{},2000);assertEquals(one.getLong("inputSequence"),same.getLong("inputSequence"));
            run.until(r->run.text().contains("OWN:literal"));assertEquals(1,run.text().split("OWN:literal",-1).length-1);
            assertThrows(IllegalStateException.class,()->f.runtime.requireCheckoutWritable(f.checkout));
            assertTrue(run.handle.eof(run.control,"original-eof",()->{},2000).getBoolean("stdinClosed"));JSONObject exit=run.finished();
            assertEquals("completed",exit.getString("status"));assertTrue(exit.getBoolean("processSessionAbsent"));assertEquals(0,f.leases());
            assertEquals("this was never a second project/Skill authority",run.invocation.executionRef().operationId(),exit.getString("operationId"));
            assertTrue(run.approval.receipts.stream().anyMatch(r->r.optBoolean("resourcesReleased")));assertFalse(exit.has("argv"));assertFalse(exit.has("requestedArgv"));
            assertEquals(run.invocation.executionRef().operationId(),run.handle.identity().getString("operationId"));assertFalse(run.handle.identity().toString().contains("literal"));
            assertThrows(SecurityException.class,()->f.runtime.dispatchPipesForHost(run.invocation,run.admission));f.runtime.requireCheckoutWritable(f.checkout);
        }
    }
    @Test public void realRegisteredNodeUsesItsFrozenScriptAndRawPipesNotPythonOrATerminalBridge()throws Exception {
        String program="process.stdout.write('OWN_READY\\n');process.stderr.write('OWN_DIAGNOSTIC\\n');let pending='';process.stdin.setEncoding('utf8');process.stdin.on('data',value=>{pending+=value;let at;while((at=pending.indexOf('\\n'))>=0){process.stdout.write('OWN:'+pending.slice(0,at)+'\\n');pending=pending.slice(at+1);}});process.stdin.on('end',()=>process.stdout.write('OWN_EOF\\n'));";
        try(Fixture f=new Fixture("node","server.js",program)){
            Run run=f.run(1,NativePipeAdmission.INVOKE);run.start(f);run.ready();
            assertEquals("delivered",run.handle.write(run.control,"node-line","Node原字节😀\n".getBytes(StandardCharsets.UTF_8),()->{},2000).getString("status"));
            run.until(r->run.text().contains("OWN:Node原字节😀"));run.handle.eof(run.control,"node-eof",()->{},2000);
            assertEquals("completed",run.finished().getString("status"));assertEquals(0,f.leases());
        }
    }
    @Test public void invalidWaitBudgetCannotQueueBytesBeforeThrowingAParameterError()throws Exception {
        try(Fixture f=new Fixture()){
            Run run=f.run(1,NativePipeAdmission.INVOKE);run.start(f);run.ready();
            assertThrows(IllegalArgumentException.class,()->run.handle.write(run.control,"invalid-wait","NEVER_QUEUE\n".getBytes(StandardCharsets.UTF_8),()->{},0));
            assertThrows(IllegalArgumentException.class,()->run.handle.originalInput("invalid-wait"));
            assertThrows(IllegalArgumentException.class,()->run.handle.eof(run.control,"invalid-eof",()->{},20001));
            assertThrows(IllegalArgumentException.class,()->run.handle.originalInput("invalid-eof"));
            run.handle.write(run.control,"valid-after-invalid","still original\n".getBytes(StandardCharsets.UTF_8),()->{},2000);
            run.until(r->run.text().contains("OWN:still original"));assertFalse(run.text().contains("NEVER_QUEUE"));
            run.handle.eof(run.control,"valid-eof",()->{},2000);run.finished();
        }
    }
    @Test public void installedRevisionZeroAndAbsentDiscoveryAreDistinctPurposesNotInterchangeableGrants()throws Exception {
        try(Fixture f=new Fixture()){
            Run invocation=f.run(1,NativePipeAdmission.INVOKE);assertEquals(0,invocation.identity.getInt("stateRevision"));invocation.admit(f);
            Run discovery=f.run(2,NativePipeAdmission.DISCOVERY);discovery.admit(f);assertEquals("absent",discovery.admission.binding().getString("expectedInstallationId"));
            assertThrows(SecurityException.class,()->f.runtime.dispatchPipesForHost(discovery.invocation,invocation.admission));
            assertFalse(discovery.approval.claimed.get());assertFalse(invocation.approval.claimed.get());assertEquals(0,f.leases());
            discovery.start(f);discovery.ready();discovery.handle.eof(discovery.control,"discovery-eof",()->{},2000);assertTrue(discovery.finished().getBoolean("resourcesReleased"));
        }
    }
    @Test public void ordinaryJsonDispatchOrMissingRealWorkspaceCannotTurnIntoNativePipes()throws Exception {
        try(Fixture f=new Fixture()){
            Run run=f.run(1,NativePipeAdmission.INVOKE);assertThrows(SecurityException.class,()->f.runtime.dispatch(run.invocation));
            RuntimeInvocation noWorkspace=new RuntimeInvocation(run.invocation.exact(),run.invocation.binding(),f.source,run.control,()->{});
            assertThrows(SecurityException.class,()->f.runtime.admitPipesForHost(noWorkspace,run.identity,run.approval,(s,b)->true));assertFalse(run.approval.claimed.get());assertEquals(0,f.leases());
            Files.write(f.active.resolve("server.py"),"print('changed')".getBytes(StandardCharsets.UTF_8));
            assertThrows(SecurityException.class,()->run.admit(f));assertEquals(0,f.leases());
        }
    }
    @Test public void revocationStopsOnlyOriginalServerAndLateExitStillRecordsItsOriginalReceipt()throws Exception {
        try(Fixture f=new Fixture()){
            Run run=f.run(1,NativePipeAdmission.INVOKE);run.start(f);run.ready();run.approval.live.set(false);
            assertThrows(SecurityException.class,()->run.handle.write(run.control,"stale","not sent\n".getBytes(StandardCharsets.UTF_8),()->{},2000));
            JSONObject exit=run.finished();assertTrue(exit.getBoolean("cancelRequested"));assertEquals(0,f.leases());
            assertTrue(run.approval.receipts.stream().anyMatch(r->r.optBoolean("resourcesReleased")));assertFalse(run.text().contains("not sent"));
        }
    }
    @Test public void oldControlOrOldOpaqueHandleCannotWriteOrCloseANewerRunStdin()throws Exception {
        try(Fixture f=new Fixture()){
            Run old=f.run(1,NativePipeAdmission.INVOKE);old.start(f);old.ready();old.handle.eof(old.control,"old-eof",()->{},2000);old.finished();
            Run current=f.run(2,NativePipeAdmission.INVOKE);current.start(f);current.ready();
            assertNotEquals(old.handle.identity().getString("pipeInstanceId"),current.handle.identity().getString("pipeInstanceId"));
            assertThrows(SecurityException.class,()->current.handle.write(old.control,"wrong-control",new byte[]{1},()->{},1000));
            assertThrows(SecurityException.class,()->old.handle.write(current.control,"wrong-handle",new byte[]{1},()->{},1000));
            old.handle.requestCancel(old.control);assertTrue(current.handle.query().getBoolean("ownedWorkerLive"));
            assertEquals("delivered",current.handle.write(current.control,"current-line","new run\n".getBytes(StandardCharsets.UTF_8),()->{},2000).getString("status"));current.until(r->current.text().contains("OWN:new run"));
            current.handle.eof(current.control,"current-eof",()->{},2000);current.finished();assertEquals(0,f.leases());
        }
    }
    @Test public void observedSessionEscapeCannotReleaseTheActualOriginalEnvironmentLease()throws Exception {
        String program="import os,sys,signal,time\nchild=os.fork()\nif child==0:\n while not os.path.exists('escape.now'): time.sleep(.01)\n os.setsid()\n time.sleep(60)\n os._exit(0)\ndef stop(*args):\n os.waitpid(child,0)\n sys.exit(0)\nsignal.signal(signal.SIGTERM,stop)\nprint('OWN_READY CHILD='+str(child),flush=True)\nsys.stdin.readline()\nopen('escape.now','w').close()\ntime.sleep(60)\n";
        try(Fixture f=new Fixture("python3","server.py",program)){
            Run run=f.run(1,NativePipeAdmission.INVOKE);run.start(f);run.ready();
            String ready=run.text().trim();long child=Long.parseLong(ready.substring(ready.indexOf("CHILD=")+6));
            assertTrue(Files.exists(Path.of("/proc/"+child)));
            assertEquals("delivered",run.handle.write(run.control,"escape-after-observation","escape\n".getBytes(StandardCharsets.UTF_8),()->{},2000).getString("status"));
            JSONObject unknown=run.until(r->r.has("finishedAt")&&!r.optBoolean("ownedWorkerLive"));f.expectedUnresolved=true;
            assertEquals("unknown",unknown.getString("status"));assertFalse(unknown.getBoolean("processSessionAbsent"));
            assertFalse(unknown.getBoolean("resourcesReleased"));assertFalse(unknown.getBoolean("allDescendantsTerminated"));
            assertEquals(1,f.leases());assertFalse("The known fixture child was actually reaped, not left running",Files.exists(Path.of("/proc/"+child)));
            assertThrows(IllegalStateException.class,()->f.runtime.requireCheckoutWritable(f.checkout));
            assertThrows(SecurityException.class,()->run.handle.write(run.control,"after-exit",new byte[]{1},()->{},1000));
            // Unknown original scope/cache is intentionally not deleted or declared released.
        }
    }
    @Test public void coldRuntimeCanQueryUnknownButCannotReuseOriginalAdmissionOrRecoverFd()throws Exception {
        try(Fixture f=new Fixture()){
            Run run=f.run(1,NativePipeAdmission.INVOKE);run.start(f);run.ready();
            try(ManagedProjectRuntime cold=ManagedProjectRuntime.create(f.root.resolve("runtime").toFile(),f.pack,f.tools)){
                JSONObject unknown=cold.query(run.handle.reference()).json();assertEquals("unknown",unknown.getString("status"));assertFalse(unknown.getBoolean("ownedWorkerLive"));assertFalse(unknown.getBoolean("resourcesReleased"));
                assertThrows(SecurityException.class,()->cold.dispatchPipesForHost(run.invocation,run.admission));assertTrue(run.handle.query().getBoolean("ownedWorkerLive"));
            }
            run.handle.eof(run.control,"only-original-eof",()->{},2000);run.finished();
        }
    }
}
