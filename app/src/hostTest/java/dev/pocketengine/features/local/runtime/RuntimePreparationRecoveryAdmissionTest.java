package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

/** Real original Linux JNI job and cold-observer receipt admission, not a device
 * or real Android package preparation. Synthetic engine bytes NEVER execute:
 * the declared download seam stops admitted preparation before network/exec/probes. */
public final class RuntimePreparationRecoveryAdmissionTest {
    private static final String ORIGINAL_SCRIPT="from pathlib import Path\nimport time\nPath('owned-ready').write_text('own original process')\nwhile True: time.sleep(.02)\n";
    private static final String OLD_CHECKOUT="12345678-1234-1234-1234-123456789abc",NEW_CHECKOUT="22345678-1234-1234-1234-123456789abc";
    private static final class Fixture implements AutoCloseable {
        final Path temporary=Files.createTempDirectory("own-recovery-admission-"),outer=temporary.resolve("runtime"),managed=outer.resolve("managed-process"),oldWorkspace=Files.createDirectory(temporary.resolve("old-project")),newWorkspace=Files.createDirectory(temporary.resolve("new-project")),nativeLib=Files.createDirectory(temporary.resolve("native"));
        final ManagedProcessJobs originalOwner;final ManagedProjectRuntime coldObserver;final ManagedProcessJobs coldJobs;final AndroidRuntimePreparation profile;
        final AtomicInteger downloads=new AtomicInteger(),released=new AtomicInteger();final String oldIdentity;
        RuntimeInvocation oldInvocation;final RunControl oldControl=new RunControl();
        Fixture(boolean catalogResource)throws Exception {this(catalogResource,false);}
        Fixture(boolean catalogResource,boolean knownActive)throws Exception {
            assertTrue("Actual host JNI required; never skipped",new NativeProcessSupervisor().available());
            Path proot=Files.write(nativeLib.resolve("libproot.so"),"own synthetic engine identity only; never executed".getBytes(StandardCharsets.UTF_8)),loader=Files.write(nativeLib.resolve("libloader.so"),"own synthetic loader identity only; never executed".getBytes(StandardCharsets.UTF_8));assertTrue(proot.toFile().setExecutable(true));assertTrue(loader.toFile().setExecutable(true));
            JSONObject descriptor=AndroidRuntimePreparationTest.catalog();descriptor.getJSONObject("engine").put("prootSha256",RuntimeFiles.sha256(proot.toFile())).put("loaderSha256",RuntimeFiles.sha256(loader.toFile()));AndroidRuntimeCatalog approved=new AndroidRuntimeCatalog(descriptor);oldIdentity=catalogResource?approved.identity:ProjectSources.sha256("own prior runtime generation identity".getBytes(StandardCharsets.UTF_8));
            originalOwner=new ManagedProcessJobs(managed.toFile(),new NativeProcessSupervisor());coldObserver=ManagedProjectRuntime.createAndroid(outer.toFile(),nativeLib.toFile(),approved);
            if(knownActive)seedRetainedIdentity(approved);
            profile=new AndroidRuntimePreparation(managed.toFile(),nativeLib.toFile(),approved,coldObserver.environmentManager(),managed.resolve("home").toFile(),managed.resolve("tmp").toFile(),request->{downloads.incrementAndGet();throw new IllegalStateException("own_fixture_download_not_opened");});
            Field field=ManagedProjectRuntime.class.getDeclaredField("androidRuntime");field.setAccessible(true);field.set(coldObserver,profile);field=ManagedProjectRuntime.class.getDeclaredField("jobs");field.setAccessible(true);coldJobs=(ManagedProcessJobs)field.get(coldObserver);
            assertEquals(knownActive,profile.ready());assertTrue(profile.describe(true).getBoolean("preparationAvailable"));
        }
        void seedRetainedIdentity(AndroidRuntimeCatalog release)throws Exception {
            // A valid prior seal tests branch selection only, not package preparation.
            // Its catalog differs from the reviewed release, so no fake cached
            // "prepared" result can escape the injected pre-network stop.
            JSONObject descriptor=release.descriptor().put("revision","owned-prior-fixture");AndroidRuntimeCatalog prior=new AndroidRuntimeCatalog(descriptor);RuntimeLinkStorage links=new RuntimeLinkStorage(managed.resolve("android-runtime").toFile());JSONObject binding=links.allocate(prior.identity);Path backing=links.require(binding,prior.identity).toPath();JSONArray tools=new JSONArray();
            for(String name:List.of("sh","node","npm","python3","git")){Path file=Files.write(backing.resolve(".l2s."+name+"0001.0001"),("synthetic metadata only "+name).getBytes(StandardCharsets.UTF_8));tools.put(new JSONObject().put("name",name).put("path","/usr/bin/"+name).put("sha256",RuntimeFiles.sha256(file.toFile())).put("bytes",Files.size(file)).put("modifiedAt",file.toFile().lastModified()));}
            JSONObject seal=new JSONObject().put("schema","pocketworld.runtime-generation.v1").put("catalogIdentity",prior.identity).put("catalog",descriptor).put("linkStorage",binding).put("tools",tools).put("syntheticFixture",true);String id=ProjectSources.digest(seal);seal.put("generationIdentity",id);Path generation=Files.createDirectory(coldObserver.environmentManager().generationStorage().toPath().resolve(id)),bin=Files.createDirectories(generation.resolve("rootfs/usr/bin"));
            for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getString("name");Files.createSymbolicLink(bin.resolve(name),backing.resolve(".l2s."+name+"0001.0001"));}RuntimeFiles.write(generation.resolve("generation.json").toFile(),seal);RuntimeFiles.write(managed.resolve("android-runtime/active.json").toFile(),new JSONObject().put("generationIdentity",id).put("catalogIdentity",prior.identity));
        }
        RuntimeInvocation invocation(Path workspace,String checkout,String project,String operation,boolean stage,String identity,RunControl control)throws Exception {
            byte[] content="class Main {}\n".getBytes(StandardCharsets.UTF_8);Files.write(workspace.resolve("Main.java"),content);JSONObject source=new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId",project)).put("files",new JSONArray().put(new JSONObject().put("path","Main.java").put("content",new String(content,StandardCharsets.UTF_8))));String digest=ProjectSources.describe(source).getString("sourceDigest");JSONObject payload=new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",digest);if(stage)payload.put("preparationCatalog",profile.describe(true).getJSONObject("preparationEffects"));else{payload.put("executable","python3").put("argv",new JSONArray().put("-I").put("-c").put(ORIGINAL_SCRIPT)).put("workingDirectory",".").put("environment",new JSONObject()).put("timeoutMillis",15000).put("runMode","owned_service").put("terminalMode","pipes");dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(payload);}
            JSONObject exact=new JSONObject().put("feature","local").put("command",stage?"project.runtime.prepare":"project.runtime.run").put("projectId",project).put("checkoutId",checkout).put("sourceDigest",digest).put("planDigest","a".repeat(64)).put("stepId",stage?"prepare":"command").put("intent",stage?"prepare":"command").put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("runtimeIdentity",identity).put("payload",payload).put("requestDigest",ProjectSources.digest(payload));JSONObject binding=new JSONObject().put("taskId","own-task-"+project).put("runId","own-run-"+project).put("coordinatorEpoch",1).put("operationId",operation);control.bindHostRun(binding.getString("taskId"),binding.getString("runId"),1);return new RuntimeInvocation(exact,binding,source,control,()->{},new RuntimeWorkspace(workspace.toFile(),project,checkout));
        }
        void startOriginalProcess()throws Exception {
            oldInvocation=invocation(oldWorkspace,OLD_CHECKOUT,"old","old-original-process",false,oldIdentity,oldControl);String python=Path.of(Objects.requireNonNull(System.getProperty("a25.python"))).toRealPath().toString();String source=ORIGINAL_SCRIPT;
            originalOwner.start(oldInvocation,List.of(python,"-I","-c",source),Map.of("POCKETWORLD_ENVIRONMENT_IDENTITY",oldIdentity),oldWorkspace.toFile(),15000,released::incrementAndGet);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!Files.isRegularFile(oldWorkspace.resolve("owned-ready"))&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.isRegularFile(oldWorkspace.resolve("owned-ready")));JSONObject live=originalOwner.query(oldInvocation.executionRef()).json();assertTrue(live.toString(),live.getBoolean("ownedWorkerLive"));assertFalse(live.getBoolean("resourcesReleased"));assertEquals(0,released.get());JSONObject cold=coldObserver.query(oldInvocation.executionRef()).json();assertEquals("unknown",cold.getString("status"));assertEquals("owner_process_restarted_no_replay",cold.getString("observation"));assertFalse(cold.getBoolean("resourcesReleased"));
        }
        RuntimeInvocation next()throws Exception{return invocation(newWorkspace,NEW_CHECKOUT,"new","new-original-prepare",true,coldObserver.identity(),new RunControl());}
        public void close()throws Exception {
            // Only the real original owner/controller can stop its own process;
            // the cold observer never signals a PID reconstructed from JSON.
            if(oldInvocation!=null){originalOwner.requestCancel(oldInvocation.executionRef());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!originalOwner.query(oldInvocation.executionRef()).json().optBoolean("resourcesReleased")&&System.nanoTime()<until)Thread.sleep(10);assertTrue(originalOwner.query(oldInvocation.executionRef()).json().getBoolean("resourcesReleased"));assertEquals(1,released.get());}
            coldObserver.close();originalOwner.close();try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}
        }
    }
    @Test public void unknownPriorGenerationOnAnotherCheckoutBlocksNewPreparationBeforeAnyWorkerOrDownload()throws Exception {
        try(var f=new Fixture(false)){f.startOriginalProcess();RuntimeInvocation next=f.next();assertNotEquals(f.oldInvocation.executionRef().checkoutId(),next.executionRef().checkoutId());assertNotEquals(f.oldIdentity,f.profile.catalogIdentity());assertThrows(Exception.class,()->f.coldObserver.requireCheckoutWritable(OLD_CHECKOUT));f.coldObserver.requireCheckoutWritable(NEW_CHECKOUT);
            Exception denied=null;RuntimeReceipt admitted=null;try{admitted=f.coldObserver.dispatch(next);}catch(Exception refused){denied=refused;}
            assertNotNull("Unknown old generation must block before a new worker; actual="+(admitted==null?null:admitted.json()),denied);assertTrue(denied.getMessage().contains("live_or_unresolved_job"));assertNull(f.coldJobs.existing(next.executionRef()));assertEquals(0,f.downloads.get());assertEquals(0,f.released.get());assertFalse(f.oldControl.isCancelled());assertTrue(f.originalOwner.query(f.oldInvocation.executionRef()).json().getBoolean("ownedWorkerLive"));
        }
    }
    @Test public void originalMatchingCatalogBarrierStillRefusesWithoutCancellingItsOldOwner()throws Exception {
        try(var f=new Fixture(true)){f.startOriginalProcess();RuntimeInvocation next=f.next();assertThrows(IllegalStateException.class,()->f.coldObserver.dispatch(next));assertNull(f.coldJobs.existing(next.executionRef()));assertEquals(0,f.downloads.get());assertFalse(f.oldControl.isCancelled());assertEquals(0,f.released.get());}
    }
    @Test public void noOldUnknownJobStillAllowsTheOriginalWorkerButNeverExecutesSyntheticEngineBytes()throws Exception {
        try(var f=new Fixture(false)){RuntimeInvocation next=f.next();f.coldObserver.dispatch(next);RuntimeReceipt result=await(f.coldJobs,next);assertEquals("failed",result.status());assertEquals(1,f.downloads.get());assertFalse(result.json().getBoolean("processDispatchAttempted"));assertFalse(result.json().getBoolean("processLaunched"));assertTrue(result.json().getBoolean("resourcesReleased"));assertFalse(f.profile.ready());}
    }
    @Test public void knownActiveProfileRetainsItsExistingCatalogScopedCandidateAdmissionPolicy()throws Exception {
        try(var f=new Fixture(false,true)){f.startOriginalProcess();String original=f.coldObserver.identity();RuntimeInvocation next=f.next();f.coldObserver.dispatch(next);RuntimeReceipt result=await(f.coldJobs,next);assertEquals("failed",result.status());assertEquals(1,f.downloads.get());assertFalse(result.json().getBoolean("processDispatchAttempted"));assertFalse(result.json().getBoolean("processLaunched"));assertEquals(original,f.coldObserver.identity());assertTrue(f.profile.ready());assertEquals(0,f.released.get());assertTrue(f.originalOwner.query(f.oldInvocation.executionRef()).json().getBoolean("ownedWorkerLive"));}
    }
    @Test public void originalProvenReleasedJobDoesNotPermanentlyBlockASeparateNewPreparation()throws Exception {
        try(var f=new Fixture(false)){f.startOriginalProcess();f.originalOwner.requestCancel(f.oldInvocation.executionRef());RuntimeReceipt stopped=await(f.originalOwner,f.oldInvocation);assertTrue(stopped.json().getBoolean("resourcesReleased"));assertEquals(1,f.released.get());RuntimeInvocation next=f.next();f.coldObserver.dispatch(next);assertEquals("failed",await(f.coldJobs,next).status());assertEquals(1,f.downloads.get());}
    }
    private static RuntimeReceipt await(ManagedProcessJobs jobs,RuntimeInvocation original)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);RuntimeReceipt result;do{result=jobs.query(original.executionRef());if(!result.json().optBoolean("ownedWorkerLive")&&result.json().optBoolean("resourcesReleased"))return result;Thread.sleep(10);}while(System.nanoTime()<until);throw new AssertionError("Own original job did not settle: "+result.json());}
}
