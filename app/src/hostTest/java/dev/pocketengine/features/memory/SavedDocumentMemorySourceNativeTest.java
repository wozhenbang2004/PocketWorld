package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.attachments.documents.*;
import dev.pocketengine.store.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Uses the actual document parser/native producer, original Book bytes and original SQLite rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SavedDocumentMemorySourceNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final FeatureHost host=new FeatureHost(e->{});final MemorySourceBridge bridge;final MemoryModule memory;
        final AttachmentRepository repo;final AttachmentsModule docs;final AttachmentOutcomeHost outcomes;final ExecutorService worker=Executors.newSingleThreadExecutor();
        final String task,run,id,sha;final int epoch;final RunControl original=new RunControl();final String content="PREFIX🙂 selected original text\nSECOND not selected";final long seq;final JSONObject event;
        Fixture()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),e->{});repo=AttachmentRepository.get(android);docs=new AttachmentsModule(context,new DocumentReader(null,null));host.register(docs);bridge=new MemorySourceBridge(host,tasks);bridge.bindAttachments(docs,repo.book);memory=new MemoryModule(context,bridge::resolve);host.register(memory);
            outcomes=new AttachmentOutcomeHost(tasks,repo,docs,(a,b,c)->{throw new AssertionError("No media or outbound call");});JSONObject file=repo.book.importBytes(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),"source.txt","text/plain",new JSONObject());id=file.getString("id");sha=file.getString("sha256");
            task=tasks.createCapabilityTask("明确选择原文档范围","saved-source-native-fixture").getString("id");outcomes.selectForTask(task,id,sha,new RunControl());JSONObject attempt=tasks.startAttempt(task,"capabilities",8);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);tasks.authorizeTools(task,run,new JSONArray().put("attachments_read_document"));tasks.noteOwnerDocumentReference(task,run,epoch,file,original);
            JSONObject result=outcomes.readTaskDocument(task,run,epoch,id,sha,new DocumentContent.Request(1,0,1,2048,8,"text"),original);assertEquals("truncated",result.getString("status"));assertEquals(2,result.getInt("nextUnit"));assertEquals("PREFIX🙂 selected original text",result.getJSONArray("chunks").getJSONObject(0).getString("text"));
            try(Cursor c=db(tasks).rawQuery("SELECT native_seq,event_json FROM task_events WHERE task_id=? AND event_type='attachment_document_read' ORDER BY event_order DESC LIMIT 1",new String[]{task})){assertTrue(c.moveToFirst());seq=c.getLong(0);event=new JSONObject(c.getString(1));}assertTrue(event.has("attachmentGeneration"));
        }
        <T>T io(Callable<T> call)throws Exception{try{return worker.submit(call).get(10,TimeUnit.SECONDS);}catch(ExecutionException e){if(e.getCause()instanceof Exception)throw(Exception)e.getCause();if(e.getCause()instanceof Error)throw(Error)e.getCause();throw e;}}
        SourceRef select(int offset,int chars)throws Exception{return tasks.selectSavedMemorySource(task,run,seq,offset,chars);}
        JSONObject remember(SourceRef ref)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","文档派生结论").put("sourceRefs",new JSONArray().put(ref.toJson())))).getJSONObject("record");}
        public void close()throws Exception{worker.shutdownNow();host.close();db(tasks).close();}
    }
    @Test public void nativeOldReadEventResolvesExactRangeAfterNewRunWithoutRevivingOldControlOrMirroringText()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject page=f.tasks.savedMemorySources(f.task,0,20);assertTrue(page.getBoolean("metadataOnly"));assertEquals("available",page.getJSONArray("entries").getJSONObject(0).getString("status"));assertFalse(page.toString().contains("selected original text"));SourceRef ref=f.select(9,8);
            assertEquals(f.seq,ref.revision);f.tasks.endAttempt(f.task,f.run,"needs_confirmation","旧文档已按范围读取",new JSONObject());f.original.cancel();JSONObject next=f.tasks.startAttempt(f.task,"capabilities",8);assertNotEquals(f.run,next.getString("runId"));int before=f.tasks.list().length();
            var body=f.io(()->{f.bridge.prepareSourceHead(ref.kind,ref.sourceId,new RunControl());return f.bridge.read(ref);});assertEquals("selected",body.text);assertEquals(ref,body.ref);assertFalse(f.event.toString().contains("selected original text"));assertEquals(before,f.tasks.list().length());assertTrue(f.original.isCancelled());assertEquals(f.run,TaskSavedEvidence.address(ref.sourceId).runId);
        }
    }
    @Test public void originalBytesAndParserFingerprintMustStillMatchBeforeAnyDerivedRead()throws Exception{
        try(Fixture f=new Fixture()){SourceRef ref=f.select(0,24000);f.remember(ref);Files.write(f.repo.book.file(f.id).toPath(),"different current bytes".getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->f.io(()->f.bridge.read(ref)));assertEquals(0,f.memory.handle("search",new JSONObject().put("scope",scope()).put("query","派生")).getJSONArray("records").length());}
    }
    @Test public void originalEventDeletionAndSamePayloadReappendCannotReviveItsOldSourceIdentity()throws Exception{
        try(Fixture f=new Fixture()){SourceRef ref=f.select(0,24000);long old=f.seq;assertEquals(1,db(f.tasks).delete("task_events","task_id=? AND event_order=?",new String[]{f.task,Long.toString(old)}));assertTrue(f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),ref.kind,ref.sourceId).deleted);assertThrows(Exception.class,()->f.io(()->f.bridge.read(ref)));
            f.tasks.event(f.task,new JSONObject(f.event.toString()));long next=TaskAuditStore.lastSequence(db(f.tasks),f.task);assertTrue(next>old);assertTrue(f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),ref.kind,ref.sourceId).deleted);assertThrows(Exception.class,()->f.io(()->f.bridge.read(ref)));assertEquals(f.content,new String(f.repo.book.readVerifiedSnapshot(f.id,f.sha,new RunControl()),StandardCharsets.UTF_8));}
    }
    @Test public void sourceForgetAndExplicitMemberScopeAreBothRequiredAndNoPrivateTitleLeaks()throws Exception{
        try(Fixture f=new Fixture()){SourceRef ref=f.select(0,24000);JSONObject record=f.remember(ref);ReadAudience audience=new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "native-member";}public long revision(){return 1;}public JSONArray allowedRecords(){return new JSONArray();}public JSONArray allowedSources()throws Exception{return new JSONArray().put(new JSONObject().put("sourceRef",ref.toJson()).put("offset",9).put("chars",8).put("unit","UTF-16"));}public void checkCurrent(){}};
            TaskMemoryUse use=f.memory.taskMemoryUse(null,r->{var body=f.bridge.read(r);return new TaskMemoryUse.Evidence(body.ref,body.title,body.text);});JSONObject selected=f.io(()->use.readAudienceSource(scope(),ref,9,8,audience));assertEquals("selected",selected.getString("text"));assertEquals("所选原文范围",selected.getString("title"));assertThrows(SecurityException.class,()->f.io(()->use.readAudienceSource(scope(),ref,0,20,audience)));
            f.memory.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertTrue(f.memory.isSourceForgotten(scope(),ref));assertThrows(SecurityException.class,()->f.io(()->use.readAudienceSource(scope(),ref,9,8,audience)));assertNotNull(f.io(()->f.bridge.read(ref)));assertTrue(f.repo.book.file(f.id).isFile());}
    }
}
