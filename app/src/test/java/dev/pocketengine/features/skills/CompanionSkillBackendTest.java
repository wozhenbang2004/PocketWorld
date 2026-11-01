package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.remote.RemoteTransport;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual production Companion HTTP/process fixtures plus real canonical SkillBook/Plan/Grant.
 * The wire EngineModule, native Store and readiness are explicit fixtures, never a sandbox proof. */
public final class CompanionSkillBackendTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222";
    private static final class Store implements CompanionSkillHostPort.Store {
        final AtomicBoolean cancelled=new AtomicBoolean(),claimed=new AtomicBoolean();boolean reader=true,approved=true,failRecord;
        int records;CompanionSkillBackend.ReceiptRef saved;
        @Override public void checkCurrent(SkillExecution.Plan p,SkillExecution.Grant g,RunControl c){if(!approved)throw new SecurityException("Exact native approval revoked");}
        @Override public boolean claimFresh(SkillExecution.Plan p,SkillExecution.Grant g,CompanionSkillBackend.ReceiptRef r,RunControl c){saved=r;return !cancelled.get()&&!c.isCancelled()&&!c.isPaused()&&claimed.compareAndSet(false,true);}
        @Override public void record(CompanionSkillBackend.ReceiptRef r,SkillExecution.Receipt receipt)throws Exception {assertEquals(r.operationId,receipt.operationId);records++;if(failRecord)throw new java.io.IOException("Fixture receipt commit failed");}
        @Override public void checkReceiptReader(CompanionSkillBackend.ReceiptRef r){if(!reader)throw new SecurityException("Reader not authorized");}
        @Override public void cancelRequested(CompanionSkillBackend.ReceiptRef r){cancelled.set(true);}
    }
    private static final class Fixture implements AutoCloseable {
        final Process process;final JSONObject info;final HttpRemote remote;final Store store=new Store();final CompanionSkillBackend backend;
        final CompanionSkillHostPort host;final SkillExecution execution;final SkillExecution.Plan plan;final SkillExecution.Grant grant;final SkillBook book;
        final JSONObject installed;final File source;final RunControl control=new RunControl();
        Fixture(String... args)throws Exception {
            Path root=Path.of("").toAbsolutePath();process=new ProcessBuilder("python3","-B","app/src/test/java/dev/pocketengine/features/skills/companion/companion_fixture.py",root.toString()).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String line=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)).readLine();if(line==null)throw new IllegalStateException("Companion fixture did not start");
            info=new JSONObject(line);remote=new HttpRemote(info.getString("base"),info.getString("token"));
            backend=new CompanionSkillBackend(remote,new CompanionSkillBackend.Deployment("profile1",remote.base,"computer","fixture-install","skill_launcher",info.getString("policySha"),info.getString("readySha"),info.getString("helperSha")),System::currentTimeMillis);
            host=new CompanionSkillHostPort(store,backend);source=Files.createTempDirectory("canonical-skill-book-").toFile();book=new SkillBook(source);long now=System.currentTimeMillis();
            installed=book.install(scope(),new JSONObject().put("source","test:canonical-owner").put("manifest","---\nname: reviewed-fixture\ndescription: canonical exact-byte script\n---\nUntrusted.")
                    .put("resources",new JSONObject().put("scripts/main.py","raise RuntimeError('NEVER EXECUTE THIS USER SCRIPT IN A TEST')\r\n").put("references/input.txt","中文🙂\r\n</script>\u0000")),now);
            book.setEnabled(scope(),installed.getString("id"),true,installed.getString("digest"),true,now);
            execution=new SkillExecution(book,backend,host,System::currentTimeMillis);control.bindHostRun(TASK,RUN,1);
            plan=execution.prepare(new SkillExecution.Run("owner","private",TASK,RUN,1,Set.of()),installed.getString("id"),1,installed.getString("digest"),request(List.of(args)),control);
            grant=new SkillExecution.Grant(UUID.randomUUID().toString(),"owner-approval","fixture-grant",plan.digest,TASK,RUN,1,SkillExecution.environmentBinding(plan),plan.expiresAt);
        }
        JSONObject dispatch()throws Exception{return execution.dispatch(plan,grant,control);}
        CompanionSkillBackend.ReceiptRef ref()throws Exception{return CompanionSkillBackend.ReceiptRef.from(plan,grant);}
        JSONObject action(String name)throws Exception{return RemoteTransport.request(remote.base,remote.token,"/__fixture/"+name,null,3);}
        SkillExecution.Receipt terminal()throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<until){SkillExecution.Receipt r=host.reconcile(ref());if(r.status!=SkillExecution.Status.ACCEPTED&&r.status!=SkillExecution.Status.RUNNING&&r.status!=SkillExecution.Status.UNKNOWN)return r;Thread.sleep(20);}throw new AssertionError("No actual terminal receipt");}
        int markers()throws Exception{return action("marker").getInt("count");}
        void awaitMarker()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(markers()<1&&System.nanoTime()<until)Thread.sleep(20);assertEquals(1,markers());}
        @Override public void close()throws Exception {backend.close();process.getOutputStream().close();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}try(java.util.stream.Stream<Path> paths=Files.walk(source.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static SkillExecution.Request request(List<String> args){return new SkillExecution.Request("scripts/main.py","python3","workspace-1",".",args,List.of("input/data.txt"),List.of("output/result.bin"),List.of("output/result.bin"),10,8192,2048);}
    private static final class HttpRemote implements EngineModule {
        final String base,token;String currentBase;int jobPosts;Runnable afterWrite;
        HttpRemote(String base,String token){this.base=base;this.token=token;currentBase=base;}
        @Override public String id(){return "remote";}
        @Override public synchronized JSONObject snapshot()throws Exception{return new JSONObject().put("profiles",new JSONArray().put(new JSONObject().put("id","profile1").put("baseUrl",currentBase)));}
        @Override public JSONObject handle(String command,JSONObject in)throws Exception {
            String path;JSONObject body=null;
            switch(command){
                case "environments":path="/v1/environments";break;
                case "readFile":path="/v1/workspaces/"+in.getString("environmentId")+"/file?path="+java.net.URLEncoder.encode(in.getString("path"),"UTF-8");break;
                case "writeFile":path="/v1/workspaces/"+in.getString("environmentId")+"/file";body=new JSONObject();for(String k:List.of("path","content","expectedVersion","operationId"))body.put(k,in.get(k));break;
                case "reconcileFileWrite":path="/v1/file-operations/"+in.getString("operationId");break;
                case "reconcileOperation":path="/v1/operations/"+in.getString("operationId");break;
                case "submitJob":path="/v1/jobs";body=new JSONObject();for(String k:List.of("environmentId","operationId","worker","argv","timeoutSeconds"))body.put(k,in.get(k));jobPosts++;break;
                case "getJob":path="/v1/jobs/"+in.getString("jobId");break;
                case "cancelJob":path="/v1/jobs/"+in.getString("jobId")+"/cancel";body=new JSONObject();break;
                default:throw new IllegalArgumentException(command);
            }
            JSONObject result=RemoteTransport.request(base,token,path,body,10).put("serviceBase",base).put("profileId","profile1");
            if(command.equals("writeFile")&&afterWrite!=null){Runnable callback=afterWrite;afterWrite=null;callback.run();}return result;
        }
    }
    @Test public void canonicalNestedReviewAndExactGrantRoundTripThroughRealCompanion()throws Exception {
        try(Fixture f=new Fixture("a b","$(no-shell)","中文🙂","</script>","\u2028","")){
            assertEquals("accepted",f.dispatch().getString("status"));SkillExecution.Receipt nativeReceipt=f.terminal();assertEquals(SkillExecution.Status.COMPLETED,nativeReceipt.status);
            assertEquals((byte)0xff,nativeReceipt.outputBytes()[nativeReceipt.outputBytes().length-1]);
            JSONObject b=f.action("bundle"),p=b.getJSONObject("review");assertEquals("pocketworld.skill-execution.v1",p.getString("format"));
            assertArrayEquals(f.plan.canonicalReviewBytes(),Base64.getDecoder().decode(b.getString("canonicalReviewBase64")));
            assertEquals(f.plan.digest,SkillExecution.sha256(Base64.getDecoder().decode(b.getString("canonicalReviewBase64"))));
            assertEquals(f.grant.operationId,b.getJSONObject("grant").getString("operationId"));assertEquals("b".repeat(64),p.getJSONObject("backend").getString("runtimeSha256"));
            assertEquals("a".repeat(64),p.getJSONObject("backend").getString("runtimeImageSha256"));
            JSONObject result=f.execution.reconcile(f.plan,f.grant);assertTrue(result.toString().length()<=2048);assertEquals("isolated_copy",result.getString("workspaceMode"));
            assertEquals("utf8_lossy",result.getString("outputEncoding"));assertFalse(result.getBoolean("businessVerified"));assertFalse(result.getBoolean("replayAllowed"));
            assertFalse(result.getBoolean("outputBase64Omitted"));assertArrayEquals(nativeReceipt.outputBytes(),Base64.getDecoder().decode(result.getString("outputBase64")));
            assertEquals(SkillExecution.sha256(nativeReceipt.outputBytes()),result.getString("outputSha256"));
            CompanionSkillBackend.ArtifactInventory artifacts=f.backend.artifacts(f.ref());assertEquals(1,artifacts.files.size());assertEquals("output/result.bin",artifacts.files.get(0).path);
            assertArrayEquals(new byte[]{(byte)0x89,'P','N','G',0,(byte)0xff},artifacts.files.get(0).bytes());assertFalse(artifacts.canonicalWorkspaceModified());assertFalse(artifacts.files.get(0).imported());
            f.dispatch();assertEquals(1,f.remote.jobPosts);assertEquals(1,f.markers());
        }
    }
    @Test public void lostAckAndRestartRefStayQueryOnlyAfterPackageDeletion()throws Exception {
        try(Fixture f=new Fixture()){
            f.action("lose_ack");assertEquals("unknown",f.dispatch().getString("status"));assertEquals(SkillExecution.Status.COMPLETED,f.terminal().status);
            CompanionSkillBackend.ReceiptRef original=f.ref();f.book.delete(scope(),f.installed.getString("id"));f.control.cancel();
            CompanionSkillBackend.ReceiptRef restored=new CompanionSkillBackend.ReceiptRef(original.ownerId,original.spaceId,original.taskId,original.runId,original.epoch,original.operationId,original.planDigest,original.environmentBinding,original.installationId,original.workspaceId,original.workspaceRevision,original.maxOutputBytes,original.maxResultChars);
            assertEquals(SkillExecution.Status.COMPLETED,f.host.reconcile(restored).status);assertEquals(1,f.markers());assertEquals(1,f.remote.jobPosts);
            f.store.reader=false;assertThrows(SecurityException.class,()->f.host.reconcile(restored));
        }
    }
    @Test public void nativeReceiptCommitFailureRemainsUnknownAndNeverResubmits()throws Exception {
        try(Fixture f=new Fixture()){
            f.store.failRecord=true;assertEquals("unknown",f.dispatch().getString("status"));f.store.failRecord=false;
            assertEquals(SkillExecution.Status.COMPLETED,f.terminal().status);f.dispatch();assertEquals(1,f.remote.jobPosts);assertEquals(1,f.markers());
        }
    }
    @Test public void cancelIsFastAndStopsTheOriginalRealProcess()throws Exception {
        try(Fixture f=new Fixture("--block")){f.dispatch();f.awaitMarker();long start=System.nanoTime();f.control.cancel();assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(200));assertTrue(f.store.cancelled.get());assertEquals(SkillExecution.Status.CANCELLED,f.terminal().status);assertEquals(1,f.markers());}
    }
    @Test public void latePostAfterCancelAndLostAckNeverAllocatesAnotherJob()throws Exception {
        try(Fixture f=new Fixture("--block")){
            f.action("late_post");f.action("lose_ack");CompletableFuture<JSONObject> sent=CompletableFuture.supplyAsync(()->{try{return f.dispatch();}catch(Exception e){throw new RuntimeException(e);}});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!f.action("marker").getBoolean("entered")&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue(f.action("marker").getBoolean("entered"));f.control.cancel();f.action("release");assertEquals("unknown",sent.get(6,TimeUnit.SECONDS).getString("status"));
            assertEquals(SkillExecution.Status.CANCELLED,f.terminal().status);assertEquals(1,f.remote.jobPosts);assertTrue(f.markers()<=1);
        }
    }
    @Test public void verifiedFalseAndChangedReadbackCannotReachJobPost()throws Exception {
        for(String mode:List.of("verified_false","tamper_read"))try(Fixture f=new Fixture()){f.action(mode);assertEquals("unknown",f.dispatch().getString("status"));assertEquals(0,f.remote.jobPosts);assertEquals(0,f.markers());}
    }
    @Test public void nativeGrantRevokedDuringStagingCannotReachJobPost()throws Exception {
        try(Fixture f=new Fixture()){
            f.remote.afterWrite=()->f.store.approved=false;
            assertEquals("unknown",f.dispatch().getString("status"));assertEquals(0,f.remote.jobPosts);assertEquals(0,f.markers());
        }
    }
    @Test public void exactPathLayoutCannotBePromotedToDirectoryOrAnotherOutput()throws Exception {
        try(Fixture f=new Fixture()){
            SkillExecution.Run run=f.plan.run();
            assertThrows(SkillExecution.Unavailable.class,()->f.backend.describe(run,new SkillExecution.Request("scripts/main.py","python3","workspace-1",".",List.of(),List.of("input/"),List.of("output/"),List.of("output/other.bin"),10,8192,2048)));
            f.action("delete_ready");assertThrows(SkillExecution.Unavailable.class,()->f.backend.describe(run,request(List.of())));assertEquals(0,f.remote.jobPosts);
        }
    }
    @Test public void replacedProfileAndRevokedNativeApprovalBlockDispatch()throws Exception {
        try(Fixture f=new Fixture()){
            f.store.approved=false;assertThrows(SecurityException.class,f::dispatch);assertEquals(0,f.remote.jobPosts);f.store.approved=true;
            synchronized(f.remote){f.remote.currentBase="http://127.0.0.1:1";}
            assertThrows(SkillExecution.Unavailable.class,f::dispatch);assertEquals(SkillExecution.Status.UNKNOWN,f.backend.query(f.ref()).status);
        }
    }
    @Test public void dispatchHandshakeTimesOutWithinFiveSecondsAndDoesNotRetry()throws Exception {
        try(Fixture f=new Fixture("--block")){
            f.action("late_post");long started=System.nanoTime();assertEquals("unknown",f.dispatch().getString("status"));
            assertTrue(System.nanoTime()-started<TimeUnit.MILLISECONDS.toNanos(5800));f.control.cancel();f.action("release");assertEquals(SkillExecution.Status.CANCELLED,f.terminal().status);assertEquals(1,f.remote.jobPosts);
        }
    }
}
