package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.project.ProjectPackageArchive;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual typed Frozen/RemoteModule/HTTP/Git/binary bytes, explicit synthetic CLI only.
 * The callback is a controlled dispatch fence, NOT proof of TaskStore parent/owner admission;
 * that positive authority and team independent review/CAS are separate original-host journeys. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteProjectPackageTransportJourneyTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-project-package-native-");final AtomicInteger fences=new AtomicInteger();
        Process peer;BufferedReader output;RemoteModule module;ProjectPackageArchive.Frozen frozen;JSONObject selection,input;RunControl control;
        Fixture()throws Exception {
            try{
                Path repo=Path.of(System.getProperty("user.dir"));peer=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/assignment_project_fixture_server.py").toString(),root.resolve("peer").toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("peer-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(peer.getInputStream(),StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!output.ready()&&peer.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("peer-stderr.log")),output.ready());String base=new JSONObject(output.readLine()).getString("baseUrl");
                var app=RuntimeEnvironment.getApplication();app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");module=RemoteAssignmentFixtureModule.open(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));module.handle("saveProfile",new JSONObject().put("id","computer").put("name","explicit full-project loopback fixture").put("baseUrl",base));RemoteAssignmentFixtureModule.markConfigured(app,"computer",base);
                Path source=root.resolve("phone-frozen-source");Files.createDirectories(source.resolve("assets"));Files.createDirectories(source.resolve("empty"));Files.writeString(source.resolve("proof.txt"),"phone original immutable package\n");Files.write(source.resolve("remove.bin"),new byte[]{0,(byte)255,7});byte[] binary=new byte[307200];for(int i=0;i<binary.length;i++)binary[i]=(byte)i;Files.write(source.resolve("assets/keep.bin"),binary);Files.writeString(source.resolve("assets/中文😀.txt"),"原件\n",StandardCharsets.UTF_8);
                String project=id();frozen=ProjectPackageArchive.capture(source.toFile(),new JSONObject().put("projectId",project).put("checkoutId",id()).put("sourceDigest","a".repeat(64)).put("name","native \"项目\" / \\ \b\f\n\r\t"+((char)1)+" 😀"),"source_project","",false);
                Files.writeString(root.resolve("native-manifest.json"),frozen.manifest().toString(),StandardCharsets.UTF_8);Files.write(root.resolve("native-manifest-canonical.utf8"),ProjectSources.canonical(frozen.manifest()).getBytes(StandardCharsets.UTF_8));Files.writeString(root.resolve("native-manifest-identity.txt"),frozen.identity,StandardCharsets.UTF_8);
                StringBuilder controls=new StringBuilder();for(int character=0;character<32;character++)controls.append((char)character);JSONObject vector=new JSONObject().put("z",Long.MAX_VALUE).put("a",new JSONArray().put(Long.MIN_VALUE).put(0).put(true).put(false).put(JSONObject.NULL)).put("😀",controls+"雪é😀/\\\"\u2028\u2029").put("￿","UTF16 property ordering");Files.writeString(root.resolve("native-encoding-vector.json"),vector.toString(),StandardCharsets.UTF_8);Files.write(root.resolve("native-encoding-vector-canonical.utf8"),ProjectSources.canonical(vector).getBytes(StandardCharsets.UTF_8));Files.writeString(root.resolve("native-encoding-vector-identity.txt"),ProjectSources.digest(vector),StandardCharsets.UTF_8);
                JSONArray approved=new JSONArray().put(new JSONObject().put("path","proof.txt").put("baseHash",ProjectSources.sha256(Files.readAllBytes(source.resolve("proof.txt")))).put("baseVersion",3)).put(new JSONObject().put("path","remove.bin").put("baseHash",ProjectSources.sha256(Files.readAllBytes(source.resolve("remove.bin")))).put("baseVersion",4)).put(new JSONObject().put("path","new.bin").put("baseHash","absent").put("baseVersion",5));
                JSONObject commandScope=new JSONObject().put("packageReference",new JSONObject().put("id",id()).put("sha256","b".repeat(64)).put("manifestIdentity",frozen.identity)).put("files",approved).put("limits",new JSONObject().put("maxWallTimeSeconds",10).put("maxOutputBytes",8192).put("maxContributionBytes",4194304));for(String key:new String[]{"allowCommands","sameUserExecutionRiskAcknowledged","privateSourceTransferAcknowledged","internalInferenceBillingUnboundedAcknowledged"})commandScope.put(key,true);
                selection=new RemoteAssignmentWorkspaces(module).captureProjectSelection(new JSONObject().put("device",new JSONObject().put("profileId","computer").put("environmentId","workspace")).put("model","fixture-model"),frozen,commandScope).view();
                JSONObject tuple=new JSONObject();for(String key:new String[]{"taskId","runId","parentTaskId","parentRunId","teamRunId","assignmentId","attemptId","workItemId"})tuple.put(key,id());for(String key:new String[]{"coordinatorEpoch","parentEpoch","teamGeneration","assignmentRevision","memberGeneration","itemRevision","ownerGeneration"})tuple.put(key,1);tuple.put("memberId","actual-native-member");
                input=new JSONObject(selection.toString()).put("operationId",id()).put("nativeAssignment",tuple).put("_profileBinding",selection.getString("profileGeneration")).put("approvalRevision",1).put("approvalExpiresAt",System.currentTimeMillis()+300000);control=new RunControl();control.bindHostRun(tuple.getString("taskId"),tuple.getString("runId"),1);
            }catch(Exception|Error error){try{close();}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        JSONObject prepare()throws Exception{return module.prepareProjectAssignment(input,frozen,control,()->{control.check();fences.incrementAndGet();});}
        @Override public void close()throws Exception {if(module!=null)module.close();if(peer!=null){peer.destroy();if(!peer.waitFor(4,TimeUnit.SECONDS)){peer.destroyForcibly();assertTrue(peer.waitFor(2,TimeUnit.SECONDS));}assertFalse(peer.isAlive());}if(output!=null)output.close();if(Files.exists(root.resolve("peer/resource-release.json"))){JSONObject release=new JSONObject(Files.readString(root.resolve("peer/resource-release.json")));assertTrue(release.getBoolean("listenerClosed"));assertEquals(0,release.getInt("activeProcesses"));assertEquals(0,release.getInt("activeDrivers"));}}
    }
    @Test public void actualFrozenPackageRawUploadsGitAndOriginalJobFreezeBinaryAndDeletedVersionStayExact()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject prepared=f.prepare();RemoteProjectAssignments.preparation(prepared,f.input,true);assertEquals(1,f.fences.get());assertTrue(Files.isDirectory(Path.of(prepared.getString("workspace")).resolve(".git")));assertEquals(f.frozen.identity,prepared.getString("packageIdentity"));
            JSONObject reference=new JSONObject();for(String key:new String[]{"mode","prepareOperationId","checkoutId","checkoutVersion","nativeAssignment"})reference.put(key,prepared.get(key));reference.put("preparationNativeAssignment",prepared.getJSONObject("nativeAssignment")).put("reservationId",id()).put("approvalRevision",2).put("approvalExpiresAt",System.currentTimeMillis()+300000);
            JSONObject execute=new JSONObject(f.input.toString()).put("operationId",id()).put("assignmentWorkspace",reference).put("prompt","Run the exact explicit synthetic contribution command");JSONObject job=f.module.submitAssignmentJob(execute,()->f.fences.incrementAndGet());String jobId=job.getString("id"),jobOperation=job.getString("operationId");
            JSONObject query=new JSONObject(f.input.toString()).put("jobId",jobId).put("jobOperationId",jobOperation);JSONObject actual=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){actual=f.module.handle("getJob",query);if(actual.optJSONArray("pendingApprovals")!=null&&actual.getJSONArray("pendingApprovals").length()>0)break;Thread.sleep(10);}assertNotNull(actual);JSONObject request=actual.getJSONArray("pendingApprovals").getJSONObject(0);assertTrue(request.getBoolean("projectCommandRisk"));assertTrue(request.getBoolean("canAccept"));f.module.approveAssignmentJob(new JSONObject(query.toString()).put("approvalRequestId",request.getString("requestId")).put("decision","accept"),()->f.fences.incrementAndGet());
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){actual=f.module.handle("getJob",query);if(actual.optString("status").equals("completed")&&actual.optBoolean("dispatchFenceFinal"))break;Thread.sleep(10);}assertEquals(actual.toString(),"completed",actual.getString("status"));assertTrue(actual.getJSONObject("inferenceUsage").isNull("cost"));
            JSONObject source=new JSONObject(actual.toString()).put("jobId",jobId).put("jobOperationId",jobOperation),scope=new JSONObject(query.toString()).put("operationId",id()).put("jobSource",source).put("preparation",prepared).put("assignmentWorkspace",reference);JSONObject frozen=f.module.captureProjectContribution(scope,()->f.fences.incrementAndGet());JSONObject validated=RemoteProjectAssignments.contribution(frozen,scope);assertTrue(validated.getBoolean("immutableArtifact"));assertFalse(validated.getBoolean("authorshipVerified"));
            JSONObject added=null,deleted=null;JSONArray files=validated.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(file.getString("path").equals("new.bin"))added=file;if(file.getString("path").equals("remove.bin"))deleted=file;}assertNotNull(added);assertNotNull(deleted);assertEquals("absent",deleted.getString("sha256"));assertTrue(deleted.getBoolean("tombstone"));
            byte[] bytes=f.module.readProjectContributionFile(new JSONObject(query.toString()).put("snapshotId",validated.getString("snapshotId")).put("snapshotVersion",validated.getString("snapshotVersion")),added,()->{});assertArrayEquals(new byte[]{(byte)255,0,(byte)128,7},bytes);assertArrayEquals(f.frozen.file("assets/keep.bin"),Files.readAllBytes(Path.of(prepared.getString("workspace")).resolve("assets/keep.bin")));
            assertEquals(prepared.getString("checkoutId"),f.module.reconcileProjectAssignment(f.input).getString("checkoutId"));assertEquals(4,f.fences.get());
        }
    }
    @Test public void lostRawAcknowledgementQueriesOriginalSlotAndCancelledControlNeverSendsFirstPost()throws Exception {
        try(Fixture f=new Fixture()){
            Files.writeString(f.root.resolve("peer/drop-next-file-ack"),"controlled lost original raw ACK");JSONObject prepared=f.prepare();assertEquals("ready",prepared.getString("status"));assertEquals(1,f.fences.get());JSONArray files=prepared.getJSONArray("inputFiles");String requests=Files.readString(f.root.resolve("peer/requests.jsonl"));for(int i=0;i<files.length();i++){String path="/v1/assignment-projects/"+f.input.getString("operationId")+"/files/"+i;assertEquals(1,requests.lines().filter(line->{try{return new JSONObject(line).optString("path").equals(path);}catch(Exception invalid){throw new AssertionError(invalid);}}).count());}
            JSONObject cancel=f.module.cancelProjectPreparation(f.input,()->f.fences.incrementAndGet());assertEquals("cancelled",cancel.getString("status"));assertFalse(cancel.getBoolean("cleanupConfirmed"));assertTrue(cancel.getBoolean("copiesMayRemain"));
        }
        try(Fixture f=new Fixture()){
            f.control.cancel();assertThrows(InterruptedException.class,f::prepare);String requests=Files.readString(f.root.resolve("peer/requests.jsonl"));assertEquals(0,requests.lines().filter(line->{try{return new JSONObject(line).optString("path").equals("/v1/assignment-projects");}catch(Exception invalid){throw new AssertionError(invalid);}}).count());assertEquals(0,f.fences.get());
        }
    }
}
