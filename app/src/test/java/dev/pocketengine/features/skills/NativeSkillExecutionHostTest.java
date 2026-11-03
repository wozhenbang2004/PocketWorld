package dev.pocketengine.features.skills;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.remote.RemoteTransport;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual SkillsModule/TaskStore/native approval and canonical backend over loopback Companion.
 * Readiness and command are the existing explicit protocol fixture: it never executes user Skill code.
 * This is not a positive bubblewrap/device/runtime-enforcement claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeSkillExecutionHostTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    public static final class Fixture implements AutoCloseable {
        public final Context android=RuntimeEnvironment.getApplication();
        final Process companion;
        public final JSONObject info,installed,task,input;
        public final AtomicInteger posts=new AtomicInteger();
        public volatile boolean pauseBeforeClaim;
        public volatile boolean failArtifactReads;
        private final AtomicInteger readyReads=new AtomicInteger();
        public final EngineModule wire;
        public final TaskStore tasks=new TaskStore(android);
        public final SkillsModule skills=new SkillsModule(new FeatureContext(android,null,new SecretVault(android),event->{}));
        public final RunControl control=new RunControl();
        public final NativeSkillExecutionHost host;
        final NativeSkillExecutionHost.OwnerWindow window;
        public Fixture(String... arguments)throws Exception {
            Path root=Path.of(System.getProperty("pocket.gateway.source",".")).toAbsolutePath();companion=new ProcessBuilder("python3","-B",root.resolve("app/src/test/java/dev/pocketengine/features/skills/companion/companion_fixture.py").toString(),root.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            info=new JSONObject(new BufferedReader(new InputStreamReader(companion.getInputStream(),StandardCharsets.UTF_8)).readLine());String base=info.getString("base"),token=info.getString("token");
            wire=new EngineModule(){
                public String id(){return "remote";}
                public JSONObject snapshot()throws Exception{return new JSONObject().put("profiles",new JSONArray().put(new JSONObject().put("id","profile1").put("baseUrl",base)));}
                public JSONObject handle(String command,JSONObject input)throws Exception {
                    String path;JSONObject body=null;
                    switch(command){
                        case "environments":path="/v1/environments";break;
                        case "readFile":
                            if(failArtifactReads&&input.getString("path").startsWith(".pwskill-result-"))throw new java.io.IOException("Fixture artifact transport unavailable");
                            path="/v1/workspaces/"+input.getString("environmentId")+"/file?path="+java.net.URLEncoder.encode(input.getString("path"),"UTF-8");break;
                        case "writeFile":path="/v1/workspaces/"+input.getString("environmentId")+"/file";body=new JSONObject();for(String key:List.of("path","content","expectedVersion","operationId"))body.put(key,input.get(key));break;
                        case "reconcileFileWrite":path="/v1/file-operations/"+input.getString("operationId");break;
                        case "reconcileOperation":path="/v1/operations/"+input.getString("operationId");break;
                        case "submitJob":path="/v1/jobs";body=new JSONObject();for(String key:List.of("environmentId","operationId","worker","argv","timeoutSeconds"))body.put(key,input.get(key));posts.incrementAndGet();break;
                        case "getJob":path="/v1/jobs/"+input.getString("jobId");break;
                        case "cancelJob":path="/v1/jobs/"+input.getString("jobId")+"/cancel";body=new JSONObject();break;
                        default:throw new AssertionError(command);
                    }JSONObject result=RemoteTransport.request(base,token,path,body,10).put("serviceBase",base).put("profileId","profile1");
                    if(command.equals("readFile")&&input.optString("path").equals(".pwskill-ready.json")&&readyReads.incrementAndGet()==3&&pauseBeforeClaim)control.pause();return result;
                }
            };
            installed=skills.handle("import",new JSONObject().put("source","test:native-review").put("manifest","---\nname: native-review\ndescription: 当前任务的精确脚本审阅\n---\n参考不是授权。")
                    .put("resources",new JSONObject().put("scripts/main.py","raise RuntimeError('NEVER EXECUTE USER SKILL IN PROTOCOL FIXTURE')\n"))).getJSONObject("skill");
            skills.handle("setEnabled",new JSONObject().put("id",installed.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",installed.getString("digest")));
            task=tasks.startAttempt(tasks.createCapabilityTask("核对这个技能脚本生成的原任务成果","").getString("id"),"capabilities",20);control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
            tasks.authorizeTools(task.getString("id"),task.getString("runId"),new JSONArray().put("skills_execute"));
            host=new NativeSkillExecutionHost(android,skills,wire,tasks,(id,run,epoch,original)->{assertSame(control,original);if(!control.matchesHostRun(id,run,epoch))throw new SecurityException("old control");control.check();},id->{});
            JSONObject deployment=new JSONObject().put("profileId","profile1").put("serviceBase",base).put("environmentId","computer").put("installationId","fixture-install").put("commandAlias","skill_launcher")
                    .put("policySha256",info.getString("policySha")).put("readySha256",info.getString("readySha")).put("helperSha256",info.getString("helperSha"));
            JSONObject configuration=new JSONObject().put("format",SkillExecutionConfiguration.FORMAT).put("deployment",deployment).put("backendGrantId","fixture-grant")
                    .put("request",new JSONObject().put("runtimeId","python3").put("workspaceId","workspace-1").put("workingDirectory",".").put("readPaths",new JSONArray().put("input/data.txt"))
                            .put("writePaths",new JSONArray().put("output/result.bin")).put("outputPaths",new JSONArray().put("output/result.bin")).put("timeoutSeconds",10).put("maxOutputBytes",8192).put("maxResultChars",2048));
            window=host.openOwnerWindow();window.setForeground(true);
            var review=host.inspectConfiguration(configuration.toString());host.installConfiguration(window,review,review.digest);
            input=new JSONObject().put("id",installed.getString("id")).put("revision",installed.getInt("revision")).put("digest",installed.getString("digest")).put("scriptPath","scripts/main.py").put("arguments",new JSONArray(arguments));
            String operation=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control).beginOperation("skills_execute",input);input.put("_hostOperationId",operation);
        }
        public CompletableFuture<JSONObject> execute(){return CompletableFuture.supplyAsync(()->{try{return host.execute(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),input,Set.of("skills_execute"),control);}catch(Exception e){throw new RuntimeException(e);}});}
        public NativeSkillExecutionHost.Review review()throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){var review=host.pendingReview(task.getString("id"));if(review!=null)return review;Thread.sleep(20);}throw new AssertionError("native review did not open");}
        public int markers()throws Exception{return RemoteTransport.request(info.getString("base"),info.getString("token"),"/__fixture/marker",null,3).getInt("count");}
        @Override public void close()throws Exception {host.close();companion.getOutputStream().close();if(!companion.waitFor(5,TimeUnit.SECONDS)){companion.destroyForcibly();companion.waitFor(2,TimeUnit.SECONDS);}}
    }
    @Test public void originalTaskReviewGatesDispatchAndRealReceiptArtifactsRemainQueryOnlyAfterRestart()throws Exception {
        try(Fixture f=new Fixture("中文 空格","$(literal)")){
            CompletableFuture<JSONObject> work=f.execute();var review=f.review();assertEquals(0,f.markers());assertEquals(0,f.posts.get());
            assertThrows(SecurityException.class,()->f.host.decide(f.window,review,"f".repeat(64),true));assertEquals(0,f.posts.get());
            assertEquals(review.plan.digest,f.host.validateReview(review));f.host.decide(f.window,review,review.plan.digest,true);JSONObject result=work.get(10,TimeUnit.SECONDS);
            assertEquals("completed",result.getString("status"));assertFalse(result.getBoolean("businessVerified"));assertEquals(1,f.posts.get());assertEquals(1,f.markers());
            f.tasks.journal(f.task.getString("id"),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).finishOperation(f.input.getString("_hostOperationId"),result);
            assertTrue(result.toString().length()<=2048);assertEquals(1,result.getInt("artifactCount"));
            JSONObject artifact=f.tasks.readSkillExecution(f.task.getString("id"),f.input.getString("_hostOperationId")).getJSONObject("receipt").getJSONArray("artifacts").getJSONObject(0);byte[] raw=f.tasks.readSkillArtifact(f.task.getString("id"),f.input.getString("_hostOperationId"),artifact.getString("artifactId"),artifact.getString("sha256"));assertArrayEquals(new byte[]{(byte)0x89,'P','N','G',0,(byte)0xff},raw);
            assertThrows(SecurityException.class,()->f.tasks.readSkillArtifact(f.task.getString("id"),f.input.getString("_hostOperationId"),artifact.getString("artifactId"),"0".repeat(64)));
            f.skills.handle("delete",new JSONObject().put("id",f.installed.getString("id")));f.control.cancel();assertNull(f.host.pendingReview(f.task.getString("id")));
            TaskStore reopened=new TaskStore(f.android);try(NativeSkillExecutionHost restart=new NativeSkillExecutionHost(f.android,f.skills,f.wire,reopened,(id,run,epoch,c)->{throw new SecurityException("no restored live executor");},id->{})){
                JSONObject actual=restart.reconcile(f.task.getString("id"),f.input.getString("_hostOperationId"),false);assertEquals("completed",actual.getJSONObject("receipt").getString("status"));assertEquals(1,f.posts.get());assertEquals(1,f.markers());
            }
        }
    }
    @Test public void aCompletedScriptWithFailedArtifactRetrievalDoesNotReportAnEmptySuccessfulResult()throws Exception {
        try(Fixture f=new Fixture()){
            f.failArtifactReads=true;var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);
            JSONObject result=work.get(10,TimeUnit.SECONDS);assertEquals("completed",result.getString("status"));assertEquals(0,result.getInt("artifactCount"));
            assertEquals("unavailable",result.optString("artifactStatus"));assertTrue(result.getBoolean("requiresPause"));
            assertEquals(1,f.posts.get());assertEquals(1,f.markers());
        }
    }
    @Test public void reopeningOriginalTaskCanRetrieveArtifactBytesWithoutConfigurationPackageOrNewExecution()throws Exception {
        try(Fixture f=new Fixture()){
            f.failArtifactReads=true;var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);
            JSONObject result=work.get(10,TimeUnit.SECONDS);assertEquals("unavailable",result.getString("artifactStatus"));
            String task=f.task.getString("id"),operation=f.input.getString("_hostOperationId");
            assertEquals("not_retrieved",f.host.taskSnapshot(task,Set.of("skills_execute"),new JSONObject()).getJSONArray("executions").getJSONObject(0).getJSONObject("artifactAvailability").getString("state"));
            f.tasks.journal(task,f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).finishOperation(operation,result);
            f.host.removeConfiguration(f.window,f.host.configuration().getLong("revision"),f.host.configuration().getString("stateFingerprint"));f.skills.handle("delete",new JSONObject().put("id",f.installed.getString("id")));f.control.cancel();f.failArtifactReads=false;
            try(NativeSkillExecutionHost reopened=new NativeSkillExecutionHost(f.android,f.skills,f.wire,f.tasks,(id,run,epoch,c)->{throw new SecurityException("no restarted live executor");},id->{})){
                assertFalse(reopened.configuration().getBoolean("configured"));assertNull(reopened.pendingReview(task));
                JSONObject actual=reopened.retrieveArtifacts(task,operation);assertEquals("available",actual.getJSONObject("artifactAvailability").getString("state"));assertFalse(actual.getBoolean("replayAllowed"));
                JSONObject file=actual.getJSONObject("receipt").getJSONArray("artifacts").getJSONObject(0);
                assertArrayEquals(new byte[]{(byte)0x89,'P','N','G',0,(byte)0xff},f.tasks.readSkillArtifact(task,operation,file.getString("artifactId"),file.getString("sha256")));
                assertEquals(1,f.posts.get());assertEquals(1,f.markers());
                assertThrows(SecurityException.class,()->reopened.decide(f.window,review,review.plan.digest,true));
            }
        }
    }
    @Test public void artifactRetrievalFailurePreservesPreviouslySavedBytesAndUnknownDoesNotFetch()throws Exception {
        try(Fixture f=new Fixture()){
            var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);assertEquals("completed",work.get(10,TimeUnit.SECONDS).getString("status"));
            String task=f.task.getString("id"),operation=f.input.getString("_hostOperationId");JSONObject before=f.tasks.readSkillExecution(task,operation);
            JSONObject file=before.getJSONObject("receipt").getJSONArray("artifacts").getJSONObject(0);f.failArtifactReads=true;
            assertThrows(IllegalStateException.class,()->f.host.retrieveArtifacts(task,operation));
            assertEquals(before.toString(),f.tasks.readSkillExecution(task,operation).toString());
            assertArrayEquals(new byte[]{(byte)0x89,'P','N','G',0,(byte)0xff},f.tasks.readSkillArtifact(task,operation,file.getString("artifactId"),file.getString("sha256")));
            assertEquals(1,f.posts.get());assertEquals(1,f.markers());
        }
        try(Fixture f=new Fixture("--block")){
            var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(f.markers()==0&&System.nanoTime()<until)Thread.sleep(20);
            assertThrows(IllegalStateException.class,()->f.host.retrieveArtifacts(f.task.getString("id"),f.input.getString("_hostOperationId")));
            f.control.cancel();assertEquals("cancelled",work.get(8,TimeUnit.SECONDS).getString("status"));assertEquals(1,f.posts.get());
        }
    }
    @Test public void laterStepInTheOriginalTaskRequiresANewNativeReviewAndKeepsEarlierArtifacts()throws Exception {
        try(Fixture f=new Fixture()){
            var firstWork=f.execute();var firstReview=f.review();f.host.decide(f.window,firstReview,firstReview.plan.digest,true);JSONObject first=firstWork.get(10,TimeUnit.SECONDS);
            String task=f.task.getString("id"),firstOperation=f.input.getString("_hostOperationId");
            f.tasks.journal(task,f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).finishOperation(firstOperation,first);
            JSONObject originalFile=f.tasks.readSkillExecution(task,firstOperation).getJSONObject("receipt").getJSONArray("artifacts").getJSONObject(0);
            f.tasks.endAttempt(task,f.task.getString("runId"),"paused","主人查看原成果后继续",new JSONObject());
            JSONObject attempt=f.tasks.startAttempt(task,"capabilities",20);RunControl nextControl=new RunControl();nextControl.bindHostRun(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
            f.tasks.authorizeTools(task,attempt.getString("runId"),new JSONArray().put("skills_execute"));
            try(NativeSkillExecutionHost next=new NativeSkillExecutionHost(f.android,f.skills,f.wire,f.tasks,(id,run,epoch,c)->{if(c!=nextControl||!c.matchesHostRun(id,run,epoch))throw new SecurityException("not the new original-task control");c.check();},id->{})){
                JSONObject input=new JSONObject(f.input.toString());input.remove("_hostOperationId");input.put("arguments",new JSONArray().put("explicit subsequent step"));
                String operation=f.tasks.journal(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),nextControl).beginOperation("skills_execute",input);input.put("_hostOperationId",operation);
                var work=CompletableFuture.supplyAsync(()->{try{return next.execute(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),input,Set.of("skills_execute"),nextControl);}catch(Exception error){throw new RuntimeException(error);}});
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(next.pendingReview(task)==null&&System.nanoTime()<until)Thread.sleep(20);
                var review=next.pendingReview(task);assertNotNull(review);assertNotEquals(firstReview.plan.digest,review.plan.digest);assertEquals(1,f.posts.get());
                var window=next.openOwnerWindow();window.setForeground(true);
                assertThrows(SecurityException.class,()->next.decide(window,firstReview,firstReview.plan.digest,true));assertEquals(1,f.posts.get());
                next.decide(window,review,review.plan.digest,true);assertEquals("completed",work.get(10,TimeUnit.SECONDS).getString("status"));window.close();
                assertEquals(2,f.posts.get());assertEquals(2,f.markers());assertNotEquals(firstOperation,operation);
                assertArrayEquals(new byte[]{(byte)0x89,'P','N','G',0,(byte)0xff},f.tasks.readSkillArtifact(task,firstOperation,originalFile.getString("artifactId"),originalFile.getString("sha256")));
            }
        }
    }
    @Test public void deniedOrReplacedNativeConfigurationCannotDispatchOrReviveAnOldReview()throws Exception {
        try(Fixture f=new Fixture()){
            var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,false);assertEquals("rejected",work.get(5,TimeUnit.SECONDS).getString("status"));
            assertEquals(0,f.posts.get());assertEquals(0,f.markers());assertThrows(SecurityException.class,()->f.host.decide(f.window,review,review.plan.digest,true));
            assertEquals("rejected",f.host.reconcile(f.task.getString("id"),f.input.getString("_hostOperationId"),false).getJSONObject("receipt").getString("status"));assertEquals(0,f.posts.get());
        }
    }
    @Test public void configurationRemovalWhileWaitingRejectsBeforeRemoteStaging()throws Exception {
        try(Fixture f=new Fixture()){
            var work=f.execute();var review=f.review();long revision=f.host.configuration().getLong("revision");f.host.removeConfiguration(f.window,revision,f.host.configuration().getString("stateFingerprint"));
            assertEquals("rejected",work.get(5,TimeUnit.SECONDS).getString("status"));assertEquals(0,f.posts.get());assertEquals(0,f.markers());assertFalse(f.host.configuration().getBoolean("configured"));
            assertThrows(SecurityException.class,()->f.host.decide(f.window,review,review.plan.digest,true));
        }
    }
    @Test public void restartOfUnclaimedReviewCanOnlyClearItsOriginalRegistrationAndLeavesTaskPaused()throws Exception {
        try(Fixture f=new Fixture()){
            var work=f.execute();var oldReview=f.review();TaskStore reopened=new TaskStore(f.android);
            try(NativeSkillExecutionHost restart=new NativeSkillExecutionHost(f.android,f.skills,f.wire,reopened,(id,run,epoch,c)->{throw new SecurityException("no restored live control");},id->{})){
                assertNull(restart.pendingReview(f.task.getString("id")));JSONObject original=restart.reconcile(f.task.getString("id"),f.input.getString("_hostOperationId"),false);
                assertEquals("rejected",original.getJSONObject("receipt").getString("status"));assertEquals("paused",reopened.get(f.task.getString("id"),false).getString("status"));assertEquals(0,f.posts.get());
                assertThrows(SecurityException.class,()->f.host.decide(f.window,oldReview,oldReview.plan.digest,true));
            }
            f.host.close();assertEquals("rejected",work.get(5,TimeUnit.SECONDS).getString("status"));assertEquals(0,f.markers());
        }
    }
    @Test public void pauseDuringFinalEnvironmentCheckBeforeClaimIsRejectedWithoutAnUnknownEffect()throws Exception {
        try(Fixture f=new Fixture()){
            f.pauseBeforeClaim=true;var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);
            JSONObject result=work.get(6,TimeUnit.SECONDS);assertEquals("rejected",result.getString("status"));assertTrue(f.control.isPaused());assertEquals(0,f.posts.get());assertEquals(0,f.markers());
            JSONObject original=f.tasks.readSkillExecution(f.task.getString("id"),f.input.getString("_hostOperationId"));assertFalse(original.has("claimedAt"));assertEquals("receipt",original.getString("operationState"));assertFalse(f.tasks.hasUnresolved(f.task.getString("id")));
        }
    }
    @Test public void stoppingAnActiveOriginalJobCollectsItsTerminalReceiptAndRestartNeverResubmits()throws Exception {
        try(Fixture f=new Fixture("--block")){
            var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(f.markers()==0&&System.nanoTime()<until)Thread.sleep(20);assertEquals(1,f.markers());
            String task=f.task.getString("id"),run=f.task.getString("runId"),operation=f.input.getString("_hostOperationId");
            f.tasks.requestCancel(task,run);f.tasks.endAttempt(task,run,"cancelled","主人停止原任务",new JSONObject());assertEquals("reconciling",f.tasks.get(task,false).getString("status"));
            long before=System.nanoTime();f.control.cancel();assertTrue("stop callback must not wait on network",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<500);
            JSONObject result=work.get(8,TimeUnit.SECONDS);assertEquals("cancelled",result.getString("status"));assertTrue(result.getBoolean("requiresPause"));assertFalse(f.tasks.hasUnresolved(task));assertEquals("cancelled",f.tasks.get(task,false).getString("status"));assertEquals(1,f.posts.get());
            try(NativeSkillExecutionHost restart=new NativeSkillExecutionHost(f.android,f.skills,f.wire,new TaskStore(f.android),(id,r,e,c)->{throw new SecurityException("no restarted executor");},id->{})){
                assertEquals("cancelled",restart.reconcile(task,operation,true).getJSONObject("receipt").getString("status"));assertEquals(1,f.posts.get());assertEquals(1,f.markers());
            }
        }
    }
    @Test public void nativeDependencyRevocationWhileReviewingRejectsTheOriginalPlanBeforeSubmission()throws Exception {
        try(Fixture f=new Fixture()){
            java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
            var work=CompletableFuture.supplyAsync(()->{try{return f.host.execute(f.task.getString("id"),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.input,Set.of("skills_execute"),f.control,()->{if(!current.get())throw new SecurityException("native dependency revoked");});}catch(Exception e){throw new RuntimeException(e);}});
            var review=f.review();current.set(false);assertThrows(SecurityException.class,()->f.host.decide(f.window,review,review.plan.digest,true));
            assertEquals("rejected",work.get(5,TimeUnit.SECONDS).getString("status"));assertEquals(0,f.posts.get());assertEquals(0,f.markers());
        }
    }
    @Test public void nativeDependencyRevocationDuringExecutionCancelsOnlyTheOriginalJob()throws Exception {
        try(Fixture f=new Fixture("--block")){
            java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
            var work=CompletableFuture.supplyAsync(()->{try{return f.host.execute(f.task.getString("id"),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.input,Set.of("skills_execute"),f.control,()->{if(!current.get())throw new SecurityException("native dependency revoked");});}catch(Exception e){throw new RuntimeException(e);}});
            var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(f.markers()==0&&System.nanoTime()<until)Thread.sleep(20);assertEquals(1,f.markers());
            current.set(false);JSONObject actual=work.get(8,TimeUnit.SECONDS);assertEquals("cancelled",actual.getString("status"));assertTrue(actual.getBoolean("requiresPause"));assertEquals(1,f.posts.get());assertEquals(1,f.markers());assertFalse(f.tasks.hasUnresolved(f.task.getString("id")));
        }
    }
}
