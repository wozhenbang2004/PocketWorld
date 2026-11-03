package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Real original SQLite/Skill approval/JNI Python process/Book, with an explicitly
 * in-memory transport. This isolates durable/live gates from kernel Unix/CLI tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ManagedScriptCallbackNativeGateTest {
    private static final String WAIT="from pathlib import Path\nimport time\np=Path('output');p.mkdir();(p/'result.bin').write_bytes(b'original private result')\n(p/'ready').write_text('ready')\nwhile not (p/'release').exists(): time.sleep(.02)\n";
    private static void ready(ManagedSkillNativeJourneyTest.Fixture f)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.isRegularFile(f.copy().resolve("workspace/output/ready"))&&System.nanoTime()<end){if(f.work.isDone())throw new AssertionError("Original native process ended before its own marker: "+f.work.get());Thread.sleep(10);}assertTrue(Files.isRegularFile(f.copy().resolve("workspace/output/ready")));
    }
    private static JSONObject send(ManagedSkillNativeJourneyTest.Fixture f,ManagedScriptCallbackTestTransport transport,String path)throws Exception {
        JSONObject descriptor=new JSONObject(new String(Files.readAllBytes(f.copy().resolve(".native-skill-callback.json")),StandardCharsets.UTF_8));
        JSONObject request=new JSONObject().put("format",ManagedScriptCallbackWire.REQUEST).put("nonce",descriptor.getString("nonce")).put("operationId",f.operation()).put("requestId",UUID.randomUUID().toString()).put("path",path);
        var channel=transport.offer((request.toString()+"\n").getBytes(StandardCharsets.UTF_8));byte[] bytes=channel.reply();if(bytes.length==0)throw new AssertionError("Original native gate closed without a response; actual="+f.record());return new JSONObject(new String(bytes,StandardCharsets.UTF_8)).getJSONObject("result");
    }
    private static void release(ManagedSkillNativeJourneyTest.Fixture f)throws Exception{Files.write(f.copy().resolve("workspace/output/release"),new byte[]{1});JSONObject result=f.work.get(10,TimeUnit.SECONDS);assertTrue(result.toString(),result.getBoolean("ok"));}
    private static void drained(ManagedScriptCallbackTestTransport transport)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while((transport.servers.get()!=0||transport.clients.get()!=0)&&System.nanoTime()<end)Thread.sleep(5);assertEquals(0,transport.servers.get());assertEquals(0,transport.clients.get());}
    @Test public void aModelArgumentCannotEnableTheCallbackWithoutTheOriginalOwnerConfiguration()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,false,transport)){
            f.input.put("immediatePrivateArtifacts",true);f.execute();var review=f.review();assertFalse(review.plan.review().getJSONObject("request").getJSONObject("nativeExecutionContract").getBoolean("immediatePrivateArtifacts"));f.approve(review);ready(f);assertEquals(0,transport.servers.get());assertFalse(Files.exists(f.copy().resolve(".native-skill-callback.json")));assertFalse(Files.exists(f.copy().resolve(".native-skill-tools")));release(f);assertEquals(0,AttachmentRepository.get(f.android).book.list().length());assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
        }
    }
    @Test public void originalActiveJobPublishesToItsDurableSlotAndBookBeforeExitWithoutTaskCompletion()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
            f.execute();var review=f.review();f.approve(review);ready(f);JSONObject reply=send(f,transport,"output/result.bin");assertEquals(reply.toString(),"published",reply.getString("status"));assertFalse(f.work.isDone());assertTrue(f.host.privateArtifactRevision(f.id())>0);assertFalse(reply.getBoolean("externalTransmission"));
            var book=AttachmentRepository.get(f.android).book;assertEquals(1,book.list().length());JSONObject slot=f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).getJSONObject(0);assertEquals("published",slot.getString("state"));assertArrayEquals("original private result".getBytes(StandardCharsets.UTF_8),f.host.readPublishedArtifact(f.id(),f.operation(),slot.getString("attachmentId"),slot.getString("sha256")));
            JSONObject view=f.host.taskSnapshot(f.id(),Set.of("skills_execute"),new JSONObject());assertEquals("published",view.getJSONArray("executions").getJSONObject(0).getJSONObject("arguments").getJSONObject("nativeSkill").getJSONObject("scriptSharedArtifacts").getJSONObject("script_output_0").getString("state"));
            JSONObject descriptor=new JSONObject(new String(Files.readAllBytes(f.copy().resolve(".native-skill-callback.json")),StandardCharsets.UTF_8));assertFalse(view.toString().contains(descriptor.getString("nonce")));assertFalse(view.toString().contains(descriptor.getJSONObject("endpoint").getString("name")));assertFalse(f.runtime.describe().toString().contains(descriptor.getString("nonce")));assertFalse(f.host.readExecutionLog(f.id(),f.operation(),0,16000).toString().contains(descriptor.getString("nonce")));assertFalse(f.tasks.get(f.id(),false).optBoolean("taskCompleted"));assertNotEquals("completed",f.tasks.get(f.id(),false).optString("status"));assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertEquals("40.00",f.originalInput());release(f);drained(transport);
        }
    }
    @Test public void unapprovedOversizeSymlinkAndHardlinkOutputsCannotObtainAnyPublicationSlot()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
            f.execute();f.approve(f.review());ready(f);Path file=f.copy().resolve("workspace/output/result.bin");
            assertEquals("rejected",send(f,transport,"output/unapproved.bin").getString("status"));assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
            Files.write(file,new byte[65537]);assertEquals("rejected",send(f,transport,"output/result.bin").getString("status"));assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
            Files.delete(file);Files.createSymbolicLink(file,Path.of("../input/data.txt"));assertEquals("unknown",send(f,transport,"output/result.bin").getString("status"));assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
            Files.delete(file);Files.createLink(file,f.copy().resolve("workspace/input/data.txt"));assertEquals("unknown",send(f,transport,"output/result.bin").getString("status"));assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
            assertEquals(0,AttachmentRepository.get(f.android).book.list().length());Files.delete(file);Files.write(file,"fixed after rejected paths".getBytes(StandardCharsets.UTF_8));assertEquals("published",send(f,transport,"output/result.bin").getString("status"));assertEquals("40.00",f.originalInput());release(f);drained(transport);
        }
    }
    @Test public void deletedBookAndChangedBytesCannotReimportOrMintAReplacementUnderTheSameOriginalSlot()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
            f.execute();f.approve(f.review());ready(f);JSONObject first=send(f,transport,"output/result.bin");assertEquals("published",first.getString("status"));JSONObject duplicate=send(f,transport,"output/result.bin");assertEquals("published",duplicate.getString("status"));assertTrue(duplicate.getJSONObject("originalPublication").getBoolean("duplicate"));
            JSONObject slot=f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).getJSONObject(0);var book=AttachmentRepository.get(f.android).book;book.remove(slot.getString("attachmentId"));assertEquals(0,book.list().length());assertThrows(Exception.class,()->f.host.readPublishedArtifact(f.id(),f.operation(),slot.getString("attachmentId"),slot.getString("sha256")));
            assertEquals("unknown",send(f,transport,"output/result.bin").getString("status"));assertEquals(0,book.list().length());Files.write(f.copy().resolve("workspace/output/result.bin"),"different bytes".getBytes(StandardCharsets.UTF_8));assertEquals("unknown",send(f,transport,"output/result.bin").getString("status"));assertEquals(0,book.list().length());assertEquals(1,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());assertEquals(slot.getString("sha256"),f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).getJSONObject(0).getString("sha256"));release(f);drained(transport);
        }
    }
    @Test public void pauseResumeRevokesOnlyTheOriginalCallbackScopeAndDoesNotMintALatePublication()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
            f.execute();f.approve(f.review());ready(f);f.control.pause();f.control.resume();drained(transport);assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());
            // Existing RuntimeInvocation permanently revokes its original execution on
            // a pause generation too; a callback must not undo that process policy.
            Files.write(f.copy().resolve("workspace/output/release"),new byte[]{1});JSONObject result=f.work.get(10,TimeUnit.SECONDS);assertFalse(result.getBoolean("ok"));assertEquals("cancelled",result.getString("status"));assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
        }
    }
    @Test public void aCompletedPrivateFactSurvivesOriginalStopAndColdReadWithoutReopeningTheScope()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
            f.execute();f.approve(f.review());ready(f);assertEquals("published",send(f,transport,"output/result.bin").getString("status"));JSONObject slot=f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).getJSONObject(0);f.control.cancel();assertFalse(f.work.get(10,TimeUnit.SECONDS).getBoolean("ok"));drained(transport);
            byte[] exact="original private result".getBytes(StandardCharsets.UTF_8);assertArrayEquals(exact,f.host.readPublishedArtifact(f.id(),f.operation(),slot.getString("attachmentId"),slot.getString("sha256")));f.reopenForReceiptOnly();assertArrayEquals(exact,f.host.readPublishedArtifact(f.id(),f.operation(),slot.getString("attachmentId"),slot.getString("sha256")));assertEquals(1,AttachmentRepository.get(f.android).book.list().length());assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());drained(transport);assertFalse(f.tasks.get(f.id(),false).optBoolean("taskCompleted"));
        }
    }
    @Test public void originalCancellationClosesTheScopeAndDoesNotPublishFromFilesLeftInItsCopy()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
            f.execute();f.approve(f.review());ready(f);long start=System.nanoTime();f.control.cancel();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);JSONObject stopped=f.work.get(10,TimeUnit.SECONDS);assertFalse(stopped.getBoolean("ok"));drained(transport);assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());assertTrue(Files.isRegularFile(f.copy().resolve("workspace/output/result.bin")));assertEquals("40.00",f.originalInput());
        }
    }
    @Test public void sourceWithdrawalAfterDispatchCancelsOnlyTheOriginalJobAndRetiresItsCallback()throws Exception {
        for(boolean replace:List.of(false,true)){
            var transport=new ManagedScriptCallbackTestTransport();
            try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",WAIT,Map.of(),false,true,transport)){
                f.execute();f.approve(f.review());ready(f);
                if(replace)f.skills.handle("import",new JSONObject().put("id",f.installed.getString("id")).put("revision",f.installed.getInt("revision")).put("source","own:replacement").put("manifest","---\nname: own-phone-skill\ndescription: 已更新的独立版本\n---\nChanged original source\n").put("resources",new JSONObject().put("scripts/main.py","raise RuntimeError('must never be replayed')\n")));
                else f.skills.handle("setEnabled",new JSONObject().put("id",f.installed.getString("id")).put("enabled",false));
                JSONObject stopped=f.work.get(10,TimeUnit.SECONDS);assertFalse(stopped.toString(),stopped.getBoolean("ok"));assertFalse(stopped.getBoolean("replayAllowed"));drained(transport);
                assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertEquals("40.00",f.originalInput());
                f.reopenForReceiptOnly();JSONObject observed=f.host.reconcile(f.id(),f.operation(),false);assertFalse(observed.getJSONObject("receipt").getBoolean("replayAllowed"));assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());
            }
        }
    }
}
