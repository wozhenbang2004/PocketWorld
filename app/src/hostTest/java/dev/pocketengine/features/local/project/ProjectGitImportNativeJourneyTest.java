package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeTool;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Exact production fixed helper + real Git/smart-HTTP/TLS + original SQLite,
 * FeatureHost and JNI jobs. Only self-authored loopback repositories are used.
 * The fixture-only package-private transport maps a reviewed public-looking
 * target to this owned listener; production DNS/public-IP routing is NOT proved
 * by that mapping. Fixture CA trust is confined to its hash-pinned RuntimeTool
 * wrapper, not the system/Android CA store or the production trust policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectGitImportNativeJourneyTest {
    private static final String USER="fixture-only-user",TOKEN="fixture-only-token-not-a-real-secret";
    static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final Path temporary=Files.createTempDirectory("c2-git-import-owned-");
        final Path serverRoot=temporary.resolve("https");final Process server;final JSONObject ready;final String url;
        final AtomicReference<Exception> callbackFailure=new AtomicReference<>();final List<ProjectGitImportTunnel.Lease> leases=new CopyOnWriteArrayList<>();
        final List<String> connections=new CopyOnWriteArrayList<>();final byte[] archive;final dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package pack;
        final Map<String,RuntimeTool> tools;WorkspaceModule workspace;LocalModule local;TaskStore tasks;FeatureHost features;ManagedProjectRuntime runtime;
        Fixture(String mode)throws Exception {
            File python=Path.of(Objects.requireNonNull(System.getProperty("a25.python"))).toRealPath().toFile();String realGit=Path.of(System.getProperty("c2.git","/usr/bin/git")).toRealPath().toString();
            Path source=Path.of(Objects.requireNonNull(System.getProperty("c2.sourceRoot"))).resolve("app/src/hostTest/python/git_https_fixture.py");
            server=new ProcessBuilder(python.getPath(),"-I",source.toString(),serverRoot.toString(),realGit,mode).redirectError(temporary.resolve("fixture-server-stderr.log").toFile()).start();
            ExecutorService lineReader=Executors.newSingleThreadExecutor();String first;try{Future<String> line=lineReader.submit(()->new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8)).readLine());first=line.get(20,TimeUnit.SECONDS);}finally{lineReader.shutdownNow();}
            if(first==null||first.length()>8192)throw new AssertionError("Self-owned HTTPS fixture did not become ready; exit="+(server.isAlive()?"alive":server.exitValue()));ready=new JSONObject(first);url="https://pw-owned-fixture.invalid:"+ready.getInt("port")+"/repository.git";
            byte[] data="self-authored native runtime marker; host tools not redistributed\n".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("share/fixture.txt"));zip.write(data);zip.closeEntry();}archive=bytes.toByteArray();
            pack=new dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package(new JSONObject().put("id","c2-intake-host-fixture").put("revision","1").put("license","CC0-1.0 own marker; native host tools not redistributed").put("abi","linux-x64-host-only").put("archiveSha256",ProjectSources.sha256(archive)).put("archiveBytes",archive.length).put("files",new JSONArray().put(new JSONObject().put("path","share/fixture.txt").put("bytes",data.length).put("sha256",ProjectSources.sha256(data)))));
            tools=Map.of("git",tool("git",new File(ready.getString("gitWrapper")),ready.getString("gitVersion"),List.of("--version")),"python3",tool("python3",python,"host fixture Python 3",List.of("--version")),"sh",tool("sh",new File("/bin/sh"),"host fixture shell",List.of("-c","printf shell-ready")));
            openNative();
        }
        static RuntimeTool tool(String name,File path,String version,List<String> probe)throws Exception{File actual=path.getCanonicalFile();return new RuntimeTool(name,actual,ProjectSources.sha256(Files.readAllBytes(actual.toPath())),version,"self-owned host fixture; no redistributed executable",List.of(),probe);}
        void openNative()throws Exception {
            runtime=ManagedProjectRuntime.create(temporary.resolve("runtime").toFile(),pack,tools);runtime.environmentManager().install(pack,new ByteArrayInputStream(archive),()->{},(phase,done,total)->{});
            FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,runtime);tasks=new TaskStore(android);features=new FeatureHost(event->{},tasks,()->false);features.register(workspace);features.register(local);
            var host=new TaskStoreProjectHost(tasks,local,id->null,event->{});local.setProjectHost(host);features.setProjectHost(host);local.setProjectReceiptSink(receipt->{try{host.recordBackendReceipt(receipt);}catch(Exception failed){callbackFailure.compareAndSet(null,failed);}});
            ProjectGitImport.Network owned=new ProjectGitImport.Network(){
                @Override public ProjectGitImportTunnel.Target review(String selected,RunControl original)throws Exception{original.checkpoint();assertEquals(url,selected);var constructor=ProjectGitImportTunnel.Target.class.getDeclaredConstructor(String.class,String.class,int.class,InetAddress.class);constructor.setAccessible(true);return constructor.newInstance(url,"pw-owned-fixture.invalid",ready.getInt("port"),InetAddress.getByAddress(new byte[]{93,(byte)184,(byte)216,34}));}
                @Override public ProjectGitImportTunnel.Lease open(ProjectGitImportTunnel.Target target,RunControl original,dev.pocketengine.features.local.runtime.ProjectRuntime.LiveGate gate,char[] user,char[] token)throws Exception{
                    var lease=new ProjectGitImportTunnel.Lease(target,original,gate,(socket,address,port)->{assertEquals("93.184.216.34",address.getHostAddress());assertEquals(ready.getInt("port"),port);connections.add(target.host+":"+port);socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port),5000);},user,token);leases.add(lease);return lease;
                }
            };
            var field=LocalModule.class.getDeclaredField("nativeGitImport");field.setAccessible(true);field.set(local,new ProjectGitImport(android.getFilesDir().toPath().resolve("local-development/git-source-intakes").toFile(),runtime,owned));
        }
        void closeNative()throws Exception{if(features!=null){features.close();features=null;}if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();tasks=null;}}
        ProjectGitImport.Confirmed confirmation(boolean auth)throws Exception{return local.gitImportsForOwner().review(url,"refs/heads/mobile","从自建Git开始",auth?USER.toCharArray():null,auth?TOKEN.toCharArray():null,new RunControl()).confirm();}
        JSONObject awaitFetch(String id)throws Exception {long until=System.nanoTime()+30_000_000_000L;JSONObject last=null;while(System.nanoTime()<until){last=features.gitImportForOwner(id,false);if(last.optString("status").matches("completed|failed"))return last;Thread.sleep(40);}throw new AssertionError("Original fetch did not settle: "+safe(id,last));}
        JSONObject awaitPublication(String id)throws Exception{long until=System.nanoTime()+10_000_000_000L;JSONObject last=null;while(System.nanoTime()<until){last=features.gitPublicationForOwner(id,false);if(last.optBoolean("found"))return last;Thread.sleep(30);}throw new AssertionError("Original publication did not settle: "+safe(id,last));}
        String safe(String id,JSONObject last)throws Exception{JSONObject task=tasks.get(id,false),summary=new JSONObject().put("taskId",id).put("taskStatus",task.optString("status")).put("httpRequestCount",requests().length()).put("nativeConnections",connections.size()).put("callbackFailure",callbackFailure.get()==null?JSONObject.NULL:callbackFailure.get().getClass().getSimpleName());if(last!=null){summary.put("viewStatus",last.optString("status")).put("code",last.optString("code"));JSONObject receipt=last.optJSONObject("processReceipt");if(receipt!=null)for(String k:List.of("receiptReceived","resourcesReleased","processExited","processGroupAbsent","exitCode","terminationSignal","noChildCreated","nativeResultContract","ownedWorkerLive","launchErrno"))if(receipt.has(k))summary.put(k,receipt.get(k));JSONArray facts=new JSONArray();for(String line:receipt.optString("stdout").split("\\R")){try{JSONObject nativeFact=new JSONObject(line);if(!"pocketworld.native-git-import-result.v1".equals(nativeFact.optString("format")))continue;JSONObject safeFact=new JSONObject();for(String key:List.of("phase","status","code")){String value=nativeFact.optString(key);if(value.matches("[a-z0-9_]{1,100}"))safeFact.put(key,value);}if(safeFact.length()>0&&facts.length()<12)facts.put(safeFact);}catch(JSONException ignored){}}summary.put("fixedProgramFacts",facts);}return summary.toString();}
        JSONArray requests()throws Exception{Path p=serverRoot.resolve("observations.json");if(!Files.exists(p))return new JSONArray();return new JSONObject(Files.readString(p)).getJSONArray("requests");}
        Path intake(String id){return android.getFilesDir().toPath().resolve("local-development/git-source-intakes").resolve(id);}
        String git(Path cwd,String...args)throws Exception{List<String> argv=new ArrayList<>();argv.add(System.getProperty("c2.git","/usr/bin/git"));Collections.addAll(argv,args);ProcessBuilder builder=new ProcessBuilder(argv).directory(cwd.toFile());builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL","/dev/null");Process child=builder.start();byte[] result=child.getInputStream().readAllBytes(),error=child.getErrorStream().readAllBytes();assertEquals("Self-owned Git inspection failed",0,child.waitFor());return new String(result,StandardCharsets.UTF_8).trim();}
        void noDurableCredential(String id)throws Exception{String auth="Basic "+Base64.getEncoder().encodeToString((USER+":"+TOKEN).getBytes(StandardCharsets.UTF_8));try(var paths=Files.walk(intake(id))){for(Path path:paths.filter(Files::isRegularFile).toList()){byte[] raw=Files.readAllBytes(path);String text=new String(raw,StandardCharsets.ISO_8859_1);assertFalse("Credential leaked into owned intake "+path.getFileName(),text.contains(TOKEN)||text.contains(auth));}}String task=tasks.get(id,true).toString();assertFalse(task.contains(TOKEN)||task.contains(auth));}
        @Override public void close()throws Exception{
            Exception failure=null;try{closeNative();}catch(Exception failed){failure=failed;}
            for(var lease:leases)try{lease.close();}catch(Exception failed){if(failure==null)failure=failed;}
            try{server.getOutputStream().close();if(!server.waitFor(8,TimeUnit.SECONDS)){server.destroy();if(!server.waitFor(3,TimeUnit.SECONDS))throw new IllegalStateException("Owned fixture HTTPS server did not exit");}assertEquals("Owned fixture HTTPS server failed",0,server.exitValue());}finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
            if(failure!=null)throw failure;
        }
    }
    @Test public void ownerHttpsAuthPublishesExactHistoryAndBinaryOnlyAfterSeparateNativeReview()throws Exception {
        try(Fixture f=new Fixture("auth")){
            var selected=f.confirmation(true);String id=selected.actionId(),plan=selected.metadata().getString("planDigest");f.features.submitGitImportForOwner(selected);JSONObject done=f.awaitFetch(id);assertEquals(f.safe(id,done),"completed",done.getString("status"));assertTrue(done.getBoolean("sourceAvailable"));assertFalse(done.getBoolean("projectPublished"));assertEquals(plan,done.getString("planDigest"));assertNull(f.callbackFailure.get());
            JSONObject source=done.getJSONObject("result");assertEquals(f.ready.getString("headCommit"),source.getString("commitId"));assertEquals(4,source.getJSONArray("files").length());assertEquals(1,f.tasks.get(id,false).getJSONArray("operations").length());assertFalse(f.tasks.hasUnresolved(id));assertThrows(Exception.class,()->f.features.submitGitImportForOwner(selected));assertThrows(Exception.class,()->f.tasks.startAttempt(id,"background",10));assertThrows(Exception.class,()->f.tasks.resolveOperation(id,id,"not_occurred"));
            assertTrue(f.requests().length()>=2);for(int i=0;i<f.requests().length();i++)assertTrue(f.requests().getJSONObject(i).getBoolean("authenticated"));f.noDurableCredential(id);
            boolean sealed=false;for(String line:Files.readAllLines(f.serverRoot.resolve("git-environment-facts.jsonl"))){JSONObject fact=new JSONObject(line);assertFalse(fact.getBoolean("secretInEnvironment"));assertFalse(fact.getBoolean("secretInArgv"));if(fact.optBoolean("credentialFd")){assertTrue(fact.getBoolean("credentialFdReadOnly"));assertTrue(fact.getBoolean("credentialFdSealed"));sealed=true;}}assertTrue("Actual fetch did not consume the sealed credential FD",sealed);
            var beforeDrift=f.features.gitImportResultForOwner(id,new RunControl());Path index=f.intake(id).resolve("repository/.git/index");byte[] originalIndex=Files.readAllBytes(index);byte[] changed=originalIndex.clone();changed[changed.length-1]^=1;Files.write(index,changed);assertThrows(Exception.class,()->f.local.reviewGitPublicationForOwner(beforeDrift,new RunControl()));Files.write(index,originalIndex);var exact=f.features.gitImportResultForOwner(id,new RunControl());
            var review=f.local.reviewGitPublicationForOwner(exact,new RunControl());JSONObject reviewed=review.metadata();assertFalse(reviewed.getBoolean("overwritesExistingProject"));assertFalse(reviewed.getBoolean("taskOrGrantRestored"));var publishing=review.confirm();String publication=publishing.actionId();f.features.submitGitPublicationForOwner(publishing);JSONObject imported=f.awaitPublication(publication);assertTrue(imported.getBoolean("found"));assertEquals(id,imported.getJSONObject("sourceImport").getJSONObject("gitOriginal").getString("actionId"));assertTrue(imported.getJSONObject("sourceImport").getBoolean("gitHistoryCopied"));assertFalse(imported.optBoolean("executionAuthorityRestored"));assertEquals(1,f.tasks.get(publication,false).getJSONArray("operations").length());assertFalse(f.tasks.hasUnresolved(publication));
            Path tree=f.android.getFilesDir().toPath().resolve("local-development/checkouts").resolve(imported.getString("id")).resolve("files");assertArrayEquals(new byte[]{0,(byte)255,(byte)128,10,0,2},Files.readAllBytes(tree.resolve("binary.dat")));assertTrue(Files.isExecutable(tree.resolve("bin/check.sh")));String history=f.git(tree,"log","--format=%H");assertEquals(f.ready.getString("headCommit")+"\n"+f.ready.getString("firstCommit"),history);assertEquals("",f.git(tree,"status","--porcelain=v1"));assertEquals("",f.git(tree,"remote"));
            JSONObject before=f.local.handle("readFile",new JSONObject().put("checkoutId",imported.getString("id")).put("path","main.py"));f.local.handle("writeFile",new JSONObject().put("checkoutId",imported.getString("id")).put("path","main.py").put("expectedHash",before.getString("sha256")).put("content","print('owner continues same imported project')\n"));assertTrue(f.git(tree,"status","--porcelain=v1").contains("main.py"));
            int requests=f.requests().length();f.closeNative();f.openNative();JSONObject cold=f.features.gitImportForOwner(id,false);assertEquals("completed",cold.getString("status"));assertTrue(cold.getBoolean("sourceAvailable"));JSONObject same=f.features.gitPublicationForOwner(publication,false);assertEquals(imported.getString("id"),same.getString("id"));assertEquals(requests,f.requests().length());assertThrows(Exception.class,()->f.tasks.startAttempt(publication,"background",10));
            Files.delete(f.intake(id).resolve(".native-git-import-result.json"));JSONObject historical=f.features.gitImportForOwner(id,false);assertEquals("completed",historical.getString("status"));assertFalse(historical.getBoolean("sourceAvailable"));assertTrue(historical.getBoolean("historicalCompletionPreserved"));assertEquals("completed",f.tasks.get(id,false).getString("status"));assertEquals(requests,f.requests().length());assertNull(f.callbackFailure.get());
            System.out.println("C2_GIT_INTAKE actual fixed helper/Git/smart-HTTP/TLS/auth sealed readonly FD; same original native task/job and exact full-history/binary/executable pins -> separate native reviewed C03 publication -> original source CAS -> cold query no fetch/secret replay; source cache loss preserves historical completion, not current source authority. Own loopback fixture/CA mapping only; not Android/real public DNS/provider.");
        }
    }
    @Test public void redirectsAndUnsupportedTrackedLinksEndWithoutPublishingOrReplaying()throws Exception{
        for(String mode:List.of("redirect","symlink","reject-reflect"))try(Fixture f=new Fixture(mode)){
            var selected=f.confirmation(mode.equals("reject-reflect"));String id=selected.actionId();f.features.submitGitImportForOwner(selected);JSONObject done=f.awaitFetch(id);assertEquals(f.safe(id,done),"failed",done.getString("status"));assertFalse(done.getBoolean("projectPublished"));assertFalse(f.tasks.hasUnresolved(id));assertEquals(1,f.tasks.get(id,false).getJSONArray("operations").length());assertFalse(f.features.gitPublicationForSourceForOwner(id).getBoolean("found"));int count=f.requests().length();assertTrue("The refusal fixture never reached its actual original HTTPS server: "+mode+" "+f.safe(id,done),count>0);if(mode.equals("reject-reflect"))for(int request=0;request<count;request++)assertTrue("The reflected-token refusal did not actually authenticate its original request",f.requests().getJSONObject(request).getBoolean("authenticated"));f.features.gitImportForOwner(id,false);assertEquals(count,f.requests().length());for(String authority:f.connections)assertEquals("pw-owned-fixture.invalid:"+f.ready.getInt("port"),authority);f.noDurableCredential(id);assertNull(f.callbackFailure.get());
        }
    }
    @Test public void originalStopClosesNativeTunnelAndDoesNotRepeatFetchOrReplayAuthentication()throws Exception{
        try(Fixture f=new Fixture("slow")){
            var selected=f.confirmation(false);String id=selected.actionId();f.features.submitGitImportForOwner(selected);long until=System.nanoTime()+15_000_000_000L;while(!Files.exists(f.serverRoot.resolve("upload-waiting"))&&System.nanoTime()<until)Thread.sleep(30);assertTrue("Original own upload-pack request did not arrive",Files.exists(f.serverRoot.resolve("upload-waiting")));f.features.gitImportForOwner(id,true);JSONObject done=f.awaitFetch(id);assertEquals(f.safe(id,done),"failed",done.getString("status"));JSONObject receipt=done.getJSONObject("processReceipt");assertTrue(receipt.getBoolean("resourcesReleased"));assertTrue(receipt.getBoolean("processGroupAbsent"));assertTrue(receipt.getBoolean("processExited"));assertFalse(f.tasks.hasUnresolved(id));int count=f.requests().length();f.features.gitImportForOwner(id,false);assertEquals(count,f.requests().length());assertFalse(f.features.gitPublicationForSourceForOwner(id).getBoolean("found"));for(var lease:f.leases){int port=URI.create(lease.proxyUrl()).getPort();assertThrows(IOException.class,()->{try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),500);}});}assertNull(f.callbackFailure.get());
        }
    }
}
