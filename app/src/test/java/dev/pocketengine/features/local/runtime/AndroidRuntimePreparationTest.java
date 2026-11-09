package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Host-only archive/authority/lifecycle tests. These do not execute an Android/ARM64 tool. */
public final class AndroidRuntimePreparationTest {
    static JSONObject catalog() throws Exception {
        JSONObject archive=new JSONObject().put("url","https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz").put("archiveBytes",1).put("archiveSha256","a".repeat(64)).put("unpackedBytes",1000).put("stripComponents",0).put("format","tar.gz");
        JSONObject node=new JSONObject(archive.toString()).put("url","https://nodejs.org/dist/v24.22.2/node-v24.22.2-linux-arm64.tar.xz").put("stripComponents",1).put("format","tar.xz");
        JSONArray tools=new JSONArray();for(String name:List.of("sh","node","npm","python3","git"))tools.put(new JSONObject().put("name",name).put("path","/usr/bin/"+name).put("license","fixture-only").put("versionArguments",new JSONArray().put("--version")));
        return new JSONObject().put("schema","pocketworld.android-runtime.v1").put("profileId","fixture").put("revision","1").put("abi","arm64-v8a").put("libc","glibc").put("distribution","Ubuntu 24.04 fixture")
                .put("engine",new JSONObject().put("prootLibrary","libproot.so").put("loaderLibrary","libloader.so").put("prootSha256","b".repeat(64)).put("loaderSha256","c".repeat(64)))
                .put("rootfs",archive).put("node",node).put("systemPackages",new JSONArray(List.of("ca-certificates","git","python3","python3-venv","python3-pip")))
                .put("aptSources","Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports/\nSuites: noble noble-updates noble-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n")
                .put("dnsServers",new JSONArray().put("1.1.1.1")).put("tools",tools);
    }
    @Test public void releaseIdentityIncludesExactSourcesAndDoesNotAdmitUnknownPins() throws Exception {
        JSONObject fixture=catalog();AndroidRuntimeCatalog approved=new AndroidRuntimeCatalog(fixture);
        fixture.getJSONObject("node").put("archiveSha256","d".repeat(64));assertNotEquals(approved.identity,new AndroidRuntimeCatalog(fixture).identity);
        assertEquals("a".repeat(64),approved.descriptor().getJSONObject("rootfs").getString("archiveSha256"));
        JSONObject invalid=catalog();invalid.getJSONObject("rootfs").put("archiveSha256","");assertThrows(IllegalArgumentException.class,()->new AndroidRuntimeCatalog(invalid));
        JSONObject outside=catalog();outside.getJSONObject("node").put("url","https://example.com/node.tar.xz");assertThrows(SecurityException.class,()->new AndroidRuntimeCatalog(outside));
        JSONObject unsigned=catalog();unsigned.put("aptSources",unsigned.getString("aptSources")+"Trusted: yes\n");assertThrows(SecurityException.class,()->new AndroidRuntimeCatalog(unsigned));
    }
    private interface Entries {void write(TarArchiveOutputStream tar) throws Exception;}
    private static File archive(Path root,Entries entries) throws Exception {
        File file=root.resolve("input.tar.gz").toFile();try(TarArchiveOutputStream tar=new TarArchiveOutputStream(new GzipCompressorOutputStream(new FileOutputStream(file)))){entries.write(tar);tar.finish();}return file;
    }
    private static void file(TarArchiveOutputStream tar,String name,String value,int mode) throws Exception {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);TarArchiveEntry entry=new TarArchiveEntry(name);entry.setSize(bytes.length);entry.setMode(mode);tar.putArchiveEntry(entry);tar.write(bytes);tar.closeArchiveEntry();}
    private static void link(TarArchiveOutputStream tar,String name,String target,boolean hard) throws Exception {TarArchiveEntry entry=new TarArchiveEntry(name,hard?TarConstants.LF_LINK:TarConstants.LF_SYMLINK);entry.setLinkName(target);tar.putArchiveEntry(entry);tar.closeArchiveEntry();}
    private static void cleanup(Path root) throws Exception {if(root==null)return;try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    @Test public void executableModesGuestAbsoluteSymlinkAndForwardHardLinkArePreserved() throws Exception {
        Path root=Files.createTempDirectory("runtime-tar-valid-");try {
            File archive=archive(root,tar->{link(tar,"usr/bin/hard","usr/bin/tool",true);file(tar,"usr/bin/tool","real bytes",0755);link(tar,"bin","/usr/bin",false);});
            Path output=Files.createDirectory(root.resolve("out"));RuntimeTarExtractor.extract(archive,"tar.gz",output.toFile(),0,1000,()->{},(p,n,t)->{});
            assertEquals("real bytes",new String(Files.readAllBytes(output.resolve("bin/tool")),StandardCharsets.UTF_8));assertFalse(Files.readSymbolicLink(output.resolve("bin")).isAbsolute());
            assertTrue(Files.isSameFile(output.resolve("usr/bin/tool"),output.resolve("usr/bin/hard")));assertTrue(Files.getPosixFilePermissions(output.resolve("usr/bin/tool")).contains(PosixFilePermission.OWNER_EXECUTE));
        }finally{cleanup(root);}
    }
    @Test public void delegatedHardLinksWaitForLoaderAndSymbolsAndResolveAllForwardChainsBeforeEffects() throws Exception {
        Path root=Files.createTempDirectory("runtime-tar-deferred-links-");try {
            File archive=archive(root,tar->{link(tar,"usr/bin/alias2","usr/bin/alias1",true);link(tar,"usr/bin/alias1","usr/bin/tool",true);file(tar,"usr/bin/tool","real bytes",0755);file(tar,"usr/lib/loader","loader bytes",0755);link(tar,"lib","/usr/lib",false);});
            Path output=Files.createDirectory(root.resolve("out")),backing=root.resolve("backing");AtomicInteger effects=new AtomicInteger();
            RuntimeTarExtractor.extract(archive,"tar.gz",output.toFile(),0,1000,()->{},(p,n,t)->{},(path,target)->{
                assertEquals("loader bytes",new String(Files.readAllBytes(output.resolve("lib/loader")),StandardCharsets.UTF_8));
                assertEquals(output.resolve("usr/bin/tool"),target);
                // Simulate a trusted backend converting the original to a backing
                // link. All archive validation must already be complete at this point.
                if(effects.getAndIncrement()==0){Files.move(target,backing);Files.createSymbolicLink(target,backing);}
                Files.createSymbolicLink(path,backing);
            });
            assertEquals(2,effects.get());assertEquals("real bytes",new String(Files.readAllBytes(output.resolve("usr/bin/alias2")),StandardCharsets.UTF_8));assertTrue(Files.isSameFile(output.resolve("usr/bin/alias1"),output.resolve("usr/bin/tool")));
        }finally{cleanup(root);}
    }
    @Test public void hardLinkCyclesAndSymbolicTargetsAreRejectedBeforeAnyDelegatedEffect() throws Exception {
        Path root=Files.createTempDirectory("runtime-tar-link-graph-");try {
            AtomicInteger effects=new AtomicInteger();
            File cycle=archive(root,tar->{file(tar,"tool","bytes",0755);link(tar,"valid","tool",true);link(tar,"a","b",true);link(tar,"b","a",true);});Path a=Files.createDirectory(root.resolve("a"));
            assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(cycle,"tar.gz",a.toFile(),0,1000,()->{},(p,n,t)->{},(path,target)->effects.incrementAndGet()));assertEquals(0,effects.get());
            File symbolic=archive(root,tar->{file(tar,"tool","bytes",0755);link(tar,"symbol","tool",false);link(tar,"hard","symbol",true);});Path b=Files.createDirectory(root.resolve("b"));
            assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(symbolic,"tar.gz",b.toFile(),0,1000,()->{},(p,n,t)->{},(path,target)->effects.incrementAndGet()));assertEquals(0,effects.get());
            File escape=archive(root,tar->{link(tar,"hard","../outside",true);});Path c=Files.createDirectory(root.resolve("c"));
            assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(escape,"tar.gz",c.toFile(),0,1000,()->{},(p,n,t)->{},(path,target)->effects.incrementAndGet()));assertEquals(0,effects.get());
        }finally{cleanup(root);}
    }
    @Test public void stableBackingSurvivesGenerationRenameAndRejectsPolicyPathOwnerOrCatalogChanges() throws Exception {
        Path root=Files.createTempDirectory("runtime-link-storage-");try {
            RuntimeLinkStorage storage=new RuntimeLinkStorage(root.toFile());String catalog="a".repeat(64);JSONObject binding=storage.allocate(catalog);Path backing=storage.require(binding,catalog).toPath();
            Path staging=Files.createDirectory(root.resolve("staging")),rootfs=Files.createDirectory(staging.resolve("rootfs")),data=Files.write(backing.resolve(".l2s.fixture0001.0002"),("same data").getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(rootfs.resolve("original"),data);Files.createSymbolicLink(rootfs.resolve("alias"),data);Path generation=root.resolve("generation");Files.move(staging,generation);
            assertEquals(backing,new RuntimeLinkStorage(root.toFile()).require(binding,catalog).toPath());assertEquals("same data",new String(Files.readAllBytes(generation.resolve("rootfs/alias")),StandardCharsets.UTF_8));assertTrue(Files.isSameFile(generation.resolve("rootfs/original"),generation.resolve("rootfs/alias")));
            assertThrows(SecurityException.class,()->storage.require(null,catalog));
            assertThrows(SecurityException.class,()->storage.require(new JSONObject(binding.toString()).put("policy","old"),catalog));
            assertThrows(SecurityException.class,()->storage.require(new JSONObject(binding.toString()).put("hostPath",root.toString()),catalog));
            assertThrows(SecurityException.class,()->storage.require(binding,"b".repeat(64)));
            RuntimeFiles.write(backing.resolve("owner.json").toFile(),new JSONObject().put("policy",RuntimeLinkStorage.POLICY));assertThrows(SecurityException.class,()->storage.require(binding,catalog));
        }finally{cleanup(root);}
    }
    @Test public void backingDirectoryOrOwnerCannotBeReplacedByASymlink() throws Exception {
        Path root=Files.createTempDirectory("runtime-link-owner-");try {
            RuntimeLinkStorage storage=new RuntimeLinkStorage(root.toFile());String catalog="a".repeat(64);JSONObject binding=storage.allocate(catalog);Path backing=storage.require(binding,catalog).toPath(),outside=Files.createDirectory(root.resolve("outside"));
            Path marker=backing.resolve("owner.json");Files.move(marker,outside.resolve("owner.json"));Files.createSymbolicLink(marker,outside.resolve("owner.json"));assertThrows(SecurityException.class,()->storage.require(binding,catalog));
            Files.delete(marker);Files.delete(backing);Files.createSymbolicLink(backing,outside);assertThrows(SecurityException.class,()->storage.require(binding,catalog));
        }finally{cleanup(root);}
    }
    @Test public void preparedLaunchUsesTheSameStableBackingAndLegacyGenerationRequiresNonDestructiveRepair() throws Exception {
        Path root=Files.createTempDirectory("runtime-link-launch-");try {
            Path home=Files.createDirectory(root.resolve("home")),tmp=Files.createDirectory(root.resolve("tmp")),nativeLib=Files.createDirectory(root.resolve("native"));
            Path proot=Files.write(nativeLib.resolve("libproot.so"),("fixture engine").getBytes(StandardCharsets.UTF_8)),loader=Files.write(nativeLib.resolve("libloader.so"),("fixture loader").getBytes(StandardCharsets.UTF_8));proot.toFile().setExecutable(true);loader.toFile().setExecutable(true);
            JSONObject descriptor=catalog();descriptor.getJSONObject("engine").put("prootSha256",RuntimeFiles.sha256(proot.toFile())).put("loaderSha256",RuntimeFiles.sha256(loader.toFile()));AndroidRuntimeCatalog approved=new AndroidRuntimeCatalog(descriptor);
            RuntimeEnvironment environment=new RuntimeEnvironment(root.toFile(),identity->{});Path androidRoot=Files.createDirectory(root.resolve("android-runtime"));RuntimeLinkStorage storage=new RuntimeLinkStorage(androidRoot.toFile());JSONObject binding=storage.allocate(approved.identity);Path backing=storage.require(binding,approved.identity).toPath();
            // A host-only synthetic seal exercises the real loader/launch guards;
            // none of these fixture bytes are passed to execve or called a tool probe.
            JSONObject seal=new JSONObject().put("schema","pocketworld.runtime-generation.v1").put("catalogIdentity",approved.identity).put("catalog",descriptor).put("linkStorage",binding);JSONArray tools=new JSONArray();
            for(String name:List.of("sh","node","npm","python3","git")){Path file=Files.write(backing.resolve(".l2s."+name+"0001.0001"),("fixture "+name).getBytes(StandardCharsets.UTF_8));tools.put(new JSONObject().put("name",name).put("path","/usr/bin/"+name).put("sha256",RuntimeFiles.sha256(file.toFile())).put("bytes",Files.size(file)).put("modifiedAt",file.toFile().lastModified()));}
            seal.put("tools",tools);String identity=ProjectSources.digest(seal);seal.put("generationIdentity",identity);Path generation=Files.createDirectory(environment.generationStorage().toPath().resolve(identity)),rootfs=Files.createDirectories(generation.resolve("rootfs/usr/bin"));
            for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getString("name");Files.createSymbolicLink(rootfs.resolve(name),backing.resolve(".l2s."+name+"0001.0001"));}
            RuntimeFiles.write(generation.resolve("generation.json").toFile(),seal);RuntimeFiles.write(androidRoot.resolve("active.json").toFile(),new JSONObject().put("generationIdentity",identity));
            AndroidRuntimePreparation preparation=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),approved,environment,home.toFile(),tmp.toFile());assertTrue(preparation.describe(true).getBoolean("canExecute"));
            // A damaged optional progress cache must not turn a valid original
            // active generation into an unconfigured runtime or erase evidence.
            Path progress=androidRoot.resolve("preparation.json");Files.write(progress,("{damaged-original-progress").getBytes(StandardCharsets.UTF_8));
            AndroidRuntimePreparation recoveredView=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),approved,environment,home.toFile(),tmp.toFile());
            assertTrue(recoveredView.ready());assertTrue(recoveredView.describe(true).getBoolean("canExecute"));
            assertEquals("observation",recoveredView.describe(true).getJSONObject("health").getJSONObject("preparation").getString("failureCategory"));
            assertEquals("{damaged-original-progress",new String(Files.readAllBytes(progress),StandardCharsets.UTF_8));
            assertEquals(backing.toString(),preparation.hostEnvironment(identity).get("PROOT_L2S_DIR"));List<String> command=preparation.command(null,".",List.of("/usr/bin/sh","-c","true"));assertTrue(command.contains("--link2symlink"));assertTrue(command.contains(backing+":"+backing));
            assertThrows(SecurityException.class,()->preparation.hostEnvironment("b".repeat(64)));
            JSONObject legacy=new JSONObject(seal.toString());legacy.remove("generationIdentity");legacy.remove("linkStorage");String oldIdentity=ProjectSources.digest(legacy);legacy.put("generationIdentity",oldIdentity);Path old=Files.createDirectory(environment.generationStorage().toPath().resolve(oldIdentity));Files.createDirectories(old.resolve("rootfs"));RuntimeFiles.write(old.resolve("generation.json").toFile(),legacy);RuntimeFiles.write(androidRoot.resolve("active.json").toFile(),new JSONObject().put("generationIdentity",oldIdentity));
            AndroidRuntimePreparation repair=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),approved,environment,home.toFile(),tmp.toFile());assertFalse(repair.ready());assertEquals("repair_required",repair.describe(true).getJSONObject("preparation").getString("phase"));assertTrue(Files.isDirectory(old));assertTrue(Files.exists(generation.resolve("rootfs/usr/bin/sh")));assertEquals("fixture sh",new String(Files.readAllBytes(generation.resolve("rootfs/usr/bin/sh")),StandardCharsets.UTF_8));
        }finally{cleanup(root);}
    }
    @Test public void healthySameCatalogRecheckClearsFailedProgressAndRetainsOriginalSealWithoutDownloadOrDispatch() throws Exception {
        Path root=Files.createTempDirectory("runtime-healthy-recheck-");ManagedProcessJobs jobs=null;
        try {
            Path workspace=Files.createDirectory(root.resolve("workspace")),home=Files.createDirectory(root.resolve("home")),tmp=Files.createDirectory(root.resolve("tmp")),nativeLib=Files.createDirectory(root.resolve("native"));
            // Synthetic checksum fixtures only: neither these engines nor the sealed tools may execute.
            Path proot=Files.writeString(nativeLib.resolve("libproot.so"),"synthetic engine, never exec"),loader=Files.writeString(nativeLib.resolve("libloader.so"),"synthetic loader, never exec");
            assertTrue(proot.toFile().setExecutable(true));assertTrue(loader.toFile().setExecutable(true));
            JSONObject descriptor=catalog();descriptor.getJSONObject("engine").put("prootSha256",RuntimeFiles.sha256(proot.toFile())).put("loaderSha256",RuntimeFiles.sha256(loader.toFile()));AndroidRuntimeCatalog approved=new AndroidRuntimeCatalog(descriptor);
            jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor());RuntimeEnvironment environment=new RuntimeEnvironment(root.toFile(),jobs::requireEnvironmentIdle);
            Path androidRoot=Files.createDirectory(root.resolve("android-runtime"));RuntimeLinkStorage storage=new RuntimeLinkStorage(androidRoot.toFile());JSONObject binding=storage.allocate(approved.identity);Path backing=storage.require(binding,approved.identity).toPath();JSONArray tools=new JSONArray();
            for(String name:List.of("sh","node","npm","python3","git")){
                Path file=Files.writeString(backing.resolve(".l2s."+name+"0001.0001"),"synthetic sealed "+name);
                tools.put(new JSONObject().put("name",name).put("path","/usr/bin/"+name).put("sha256",RuntimeFiles.sha256(file.toFile())).put("bytes",Files.size(file)).put("modifiedAt",file.toFile().lastModified()).put("observedVersion","fixture-only saved version "+name));
            }
            JSONObject seal=new JSONObject().put("schema","pocketworld.runtime-generation.v1").put("catalogIdentity",approved.identity).put("catalog",descriptor).put("linkStorage",binding).put("tools",tools).put("syntheticFixture",true);
            String identity=ProjectSources.digest(seal);seal.put("generationIdentity",identity);Path generation=Files.createDirectory(environment.generationStorage().toPath().resolve(identity)),bin=Files.createDirectories(generation.resolve("rootfs/usr/bin"));
            for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getString("name");Files.createSymbolicLink(bin.resolve(name),backing.resolve(".l2s."+name+"0001.0001"));}
            Path sealFile=generation.resolve("generation.json"),pointer=androidRoot.resolve("active.json"),progress=androidRoot.resolve("preparation.json");
            RuntimeFiles.write(sealFile.toFile(),seal);RuntimeFiles.write(pointer.toFile(),new JSONObject().put("generationIdentity",identity).put("catalogIdentity",approved.identity));
            RuntimeFiles.write(progress.toFile(),new JSONObject().put("phase","failed").put("catalogIdentity",approved.identity).put("error","fixture_prior_failure"));
            byte[] originalSeal=Files.readAllBytes(sealFile),originalPointer=Files.readAllBytes(pointer);AtomicInteger downloads=new AtomicInteger();
            AndroidRuntimePreparation preparation=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),approved,environment,home.toFile(),tmp.toFile(),request->{downloads.incrementAndGet();throw new IllegalStateException("healthy_recheck_must_not_download");});
            assertTrue(preparation.ready());assertEquals("failed",preparation.describe(true).getJSONObject("preparation").getString("phase"));
            long startedAt=System.currentTimeMillis();RuntimeInvocation original=invocation(workspace,"healthy-recheck");
            jobs.startPreparation(original,approved.identity,preparation::prepare,preparation.beginPreparation());JSONObject receipt=await(jobs,original).json();
            assertEquals(receipt.toString(),"completed",receipt.getString("status"));assertTrue(receipt.getBoolean("receiptReceived"));assertTrue(receipt.getBoolean("resourcesReleased"));
            assertEquals(0,downloads.get());assertFalse(receipt.getBoolean("processDispatchAttempted"));assertFalse(receipt.getBoolean("processLaunched"));assertFalse(receipt.getBoolean("processExited"));
            assertEquals(identity,preparation.identity());assertEquals(identity,receipt.getString("preparedEnvironmentIdentity"));assertEquals(approved.identity,receipt.getString("catalogIdentity"));
            assertEquals(ProjectSources.canonical(seal),ProjectSources.canonical(receipt.getJSONObject("toolProbeReceipt")));assertFalse(receipt.getBoolean("businessVerified"));
            assertEquals("sealed_tool_hash_recheck",receipt.getString("verificationKind"));long verifiedAt=receipt.getLong("verifiedAt");assertTrue(verifiedAt>=startedAt&&verifiedAt<=System.currentTimeMillis());
            JSONObject saved=RuntimeFiles.read(progress.toFile());assertEquals("ready",saved.getString("phase"));assertEquals(identity,saved.getString("generationIdentity"));assertEquals("sealed_tool_hash_recheck",saved.getString("verificationKind"));assertEquals(verifiedAt,saved.getLong("verifiedAt"));assertFalse(saved.has("error"));
            assertEquals("ready",preparation.describe(true).getJSONObject("preparation").getString("phase"));assertFalse(preparation.preparationInProgressForHost());
            assertArrayEquals(originalSeal,Files.readAllBytes(sealFile));assertArrayEquals(originalPointer,Files.readAllBytes(pointer));
        }finally{if(jobs!=null)jobs.close();cleanup(root);}
    }
    @Test public void traversalSymlinkWritesAndSpecialFilesCannotEscapeStaging() throws Exception {
        Path root=Files.createTempDirectory("runtime-tar-invalid-");try {
            Path sentinel=Files.write(root.resolve("sentinel"),("retained").getBytes(StandardCharsets.UTF_8));
            File traversal=archive(root,tar->file(tar,"../sentinel","changed",0644));Path out=Files.createDirectory(root.resolve("a"));assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(traversal,"tar.gz",out.toFile(),0,1000,()->{},(p,n,t)->{}));
            File linkEscape=archive(root,tar->{link(tar,"escape","../../",false);file(tar,"escape/sentinel","changed",0644);});Path out2=Files.createDirectory(root.resolve("b"));assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(linkEscape,"tar.gz",out2.toFile(),0,1000,()->{},(p,n,t)->{}));
            File throughLink=archive(root,tar->{link(tar,"a","/usr",false);file(tar,"a/x","data",0644);});Path out3=Files.createDirectory(root.resolve("c"));assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(throughLink,"tar.gz",out3.toFile(),0,1000,()->{},(p,n,t)->{}));
            File special=archive(root,tar->{TarArchiveEntry fifo=new TarArchiveEntry("fifo",TarConstants.LF_FIFO);tar.putArchiveEntry(fifo);tar.closeArchiveEntry();});Path out4=Files.createDirectory(root.resolve("d"));assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(special,"tar.gz",out4.toFile(),0,1000,()->{},(p,n,t)->{}));
            assertEquals("retained",new String(Files.readAllBytes(sentinel),StandardCharsets.UTF_8));
        }finally{cleanup(root);}
    }
    @Test public void expansionBudgetCancellationAndStripComponentsAreEnforced() throws Exception {
        Path root=Files.createTempDirectory("runtime-tar-budget-");try {
            File archive=archive(root,tar->file(tar,"node-v24/bin/node","fixture",0755));Path a=Files.createDirectory(root.resolve("a"));
            assertThrows(SecurityException.class,()->RuntimeTarExtractor.extract(archive,"tar.gz",a.toFile(),1,1,()->{},(p,n,t)->{}));
            Path b=Files.createDirectory(root.resolve("b"));assertThrows(InterruptedException.class,()->RuntimeTarExtractor.extract(archive,"tar.gz",b.toFile(),1,100,()->{throw new InterruptedException("stopped");},(p,n,t)->{}));
            Path c=Files.createDirectory(root.resolve("c"));RuntimeTarExtractor.extract(archive,"tar.gz",c.toFile(),1,100,()->{},(p,n,t)->{});assertEquals("fixture",new String(Files.readAllBytes(c.resolve("bin/node")),StandardCharsets.UTF_8));
        }finally{cleanup(root);}
    }
    @Test public void generationLeaseBlocksOnlyThatGenerationAndReleasesExactlyOnce() throws Exception {
        Path root=Files.createTempDirectory("runtime-generation-");try {
            List<String> observed=new ArrayList<>();RuntimeEnvironment environment=new RuntimeEnvironment(root.toFile(),observed::add);AutoCloseable lease=environment.acquireGeneration("a".repeat(64));
            assertThrows(IllegalStateException.class,()->environment.requireGenerationIdle("a".repeat(64)));environment.requireGenerationIdle("b".repeat(64));lease.close();lease.close();environment.requireGenerationIdle("a".repeat(64));assertEquals(List.of("b".repeat(64),"a".repeat(64)),observed);
        }finally{cleanup(root);}
    }
    private static RuntimeInvocation invocation(Path root,String operation) throws Exception {
        JSONObject source=RuntimeContractsTest.source();Files.write(root.resolve("Main.java"),("class Main {}\n").getBytes(StandardCharsets.UTF_8));JSONObject exact=RuntimeContractsTest.exact(source,"termux.prepare",null);
        return new RuntimeInvocation(exact,RuntimeContractsTest.binding(operation),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(root.toFile(),"project",RuntimeContractsTest.CHECKOUT));
    }
    @Test public void preparationFailureAndCancellationUseOriginalReceiptsWithoutNativeDispatch() throws Exception {
        Path root=Files.createTempDirectory("runtime-prepare-job-");ManagedProcessJobs jobs=null;try {
            Path active=Files.createDirectory(root.resolve("active"));jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor());AtomicInteger released=new AtomicInteger();RuntimeInvocation failed=invocation(active,"failed");
            jobs.startPreparation(failed,"a".repeat(64),context->{context.progress("downloading",3,10);throw new java.io.IOException("fixture_download_interrupted");},released::incrementAndGet);
            RuntimeReceipt receipt=await(jobs,failed);assertEquals("failed",receipt.status());assertEquals("not_dispatched",receipt.json().getString("effectStatus"));assertFalse(receipt.json().getBoolean("processLaunched"));assertTrue(receipt.json().getBoolean("resourcesReleased"));assertEquals(1,released.get());
            jobs.startPreparation(failed,"a".repeat(64),context->{throw new AssertionError("duplicate replay");},released::incrementAndGet);assertEquals(2,released.get());
            CountDownLatch entered=new CountDownLatch(1);RuntimeInvocation cancel=invocation(active,"cancel");
            jobs.startPreparation(cancel,"a".repeat(64),context->{entered.countDown();while(true){context.check();Thread.sleep(5);}},released::incrementAndGet);assertTrue(entered.await(2,TimeUnit.SECONDS));jobs.requestCancel(cancel.executionRef());RuntimeReceipt stopped=await(jobs,cancel);assertEquals("failed",stopped.status());assertTrue(stopped.json().getBoolean("cancelRequested"));assertTrue(stopped.json().getBoolean("resourcesReleased"));
        }finally{if(jobs!=null)jobs.close();cleanup(root);}
    }
    @Test public void trustedApplicationRootCanonicalizationAcceptsUserZeroStyleAliasWithoutWeakeningGuards() throws Exception {
        Path root=Files.createTempDirectory("runtime-context-alias-");try {
            Path actual=Files.createDirectory(root.resolve("actual")),alias=Files.createSymbolicLink(root.resolve("user-zero"),actual);
            File contextDirectory=new File(alias.toFile(),"development-runtime");
            assertThrows(SecurityException.class,()->ManagedProjectRuntime.unconfigured(contextDirectory));
            try(ManagedProjectRuntime runtime=ManagedProjectRuntime.unconfigured(contextDirectory.getCanonicalFile())){assertFalse(runtime.describe().getBoolean("canExecute"));assertTrue(Files.isDirectory(actual.resolve("development-runtime/managed-process")));}
        }finally{cleanup(root);}
    }
    private static final class HttpFixture implements okhttp3.Interceptor {
        final byte[] body;final long start,total;final boolean range;okhttp3.Request request;
        HttpFixture(byte[] body,long start,long total,boolean range){this.body=body;this.start=start;this.total=total;this.range=range;}
        @Override public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain){request=chain.request();okhttp3.Response.Builder response=new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(range?206:200).message("fixture").body(okhttp3.ResponseBody.create(body,okhttp3.MediaType.get("application/octet-stream")));if(range)response.header("Content-Range","bytes "+start+"-"+(total-1)+"/"+total);return response.build();}
        okhttp3.Call call(okhttp3.Request request){return new okhttp3.OkHttpClient.Builder().addInterceptor(this).build().newCall(request);}
    }
    @Test public void controlledDownloadResumesBytesVerifiesFullHashAndPreservesUserHomeOnFailure() throws Exception {
        Path root=Files.createTempDirectory("runtime-http-fixture-");ManagedProcessJobs jobs=null;try {
            Path active=Files.createDirectory(root.resolve("active")),home=Files.createDirectory(root.resolve("home")),tmp=Files.createDirectory(root.resolve("tmp")),nativeLib=Files.createDirectory(root.resolve("native"));Files.write(home.resolve("user.txt"),("retained").getBytes(StandardCharsets.UTF_8));
            byte[] bytes="pinned archive bytes".getBytes(StandardCharsets.UTF_8);JSONObject descriptor=catalog();JSONObject artifact=descriptor.getJSONObject("rootfs").put("archiveBytes",bytes.length).put("archiveSha256",ProjectSources.sha256(bytes));
            jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor());RuntimeEnvironment environment=new RuntimeEnvironment(root.toFile(),jobs::requireEnvironmentIdle);
            List<HttpFixture> connections=new ArrayList<>();AndroidRuntimePreparation preparation=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),new AndroidRuntimeCatalog(descriptor),environment,home.toFile(),tmp.toFile(),request->{HttpFixture connection=new HttpFixture(java.util.Arrays.copyOfRange(bytes,6,bytes.length),6,bytes.length,true);connections.add(connection);return connection.call(request);});
            Path cache=root.resolve("android-runtime/downloads");Files.write(cache.resolve(artifact.getString("archiveSha256")+".part"),java.util.Arrays.copyOf(bytes,6));RuntimeInvocation resumed=invocation(active,"resumed");
            jobs.startPreparation(resumed,"a".repeat(64),context->{File downloaded=preparation.download(artifact,context);assertArrayEquals(bytes,Files.readAllBytes(downloaded.toPath()));return new JSONObject();},()->{});assertEquals("completed",await(jobs,resumed).status());assertEquals("bytes=6-",connections.get(0).request.header("Range"));assertFalse(Files.exists(cache.resolve(artifact.getString("archiveSha256")+".part")));
            JSONObject badDescriptor=catalog();JSONObject bad=badDescriptor.getJSONObject("rootfs").put("archiveBytes",bytes.length).put("archiveSha256","f".repeat(64));AndroidRuntimePreparation invalid=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),new AndroidRuntimeCatalog(badDescriptor),environment,home.toFile(),tmp.toFile(),request->new HttpFixture(bytes,0,bytes.length,false).call(request));RuntimeInvocation failed=invocation(active,"bad-hash");
            jobs.startPreparation(failed,"b".repeat(64),context->{invalid.download(bad,context);return new JSONObject();},()->{});RuntimeReceipt receipt=await(jobs,failed);assertEquals("failed",receipt.status());assertEquals("runtime_package_hash_mismatch",receipt.json().getString("error"));assertEquals("not_dispatched",receipt.json().getString("effectStatus"));assertFalse(Files.exists(cache.resolve("f".repeat(64)+".part")));assertEquals("retained",new String(Files.readAllBytes(home.resolve("user.txt")),StandardCharsets.UTF_8));
        }finally{if(jobs!=null)jobs.close();cleanup(root);}
    }
    private static RuntimeReceipt await(ManagedProcessJobs jobs,RuntimeInvocation invocation) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){RuntimeReceipt actual=jobs.query(invocation.executionRef());if(actual.json().optBoolean("receiptReceived")&&actual.json().has("finishedAt"))return actual;Thread.sleep(5);}throw new AssertionError("preparation did not settle");}
}
