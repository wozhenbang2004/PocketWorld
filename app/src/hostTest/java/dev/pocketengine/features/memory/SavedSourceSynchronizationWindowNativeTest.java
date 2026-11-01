package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.browser.BrowserSemanticRead;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.attachments.documents.*;
import dev.pocketengine.store.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/** Actual original SQL/MemoryBook/AttachmentBook byte checks. Browser rows are explicit saved
 * native-event fixtures, not a website or a claim that this fixture exercises retention approval. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SavedSourceSynchronizationWindowNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return(SQLiteDatabase)f.get(tasks);}
    private static final class Doc {
        final SourceRef ref;final String id,sha,generation;
        Doc(SourceRef ref,JSONObject event)throws Exception{this.ref=ref;id=event.getString("attachmentId");sha=event.getString("sha256");generation=event.getString("attachmentGeneration");}
    }
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final FeatureContext context;
        final AttachmentRepository repository;final ExecutorService worker=Executors.newSingleThreadExecutor();
        FeatureHost host;MemorySourceBridge bridge;MemoryModule memory;AttachmentsModule documents;AttachmentBook eligibility;
        Fixture()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);
            context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
            repository=AttachmentRepository.get(android);open(repository.book);
        }
        void open(AttachmentBook originalBook)throws Exception{
            host=new FeatureHost(event->{});documents=new AttachmentsModule(context,new DocumentReader(null,null));host.register(documents);
            bridge=new MemorySourceBridge(host,tasks);eligibility=originalBook;bridge.bindAttachments(documents,originalBook);
            memory=new MemoryModule(context,bridge::resolve);host.register(memory);
        }
        void cold()throws Exception{host.close();open(new AttachmentBook(new java.io.File(android.getFilesDir(),"attachments")));}
        <T>T io(Callable<T> action)throws Exception{try{return worker.submit(action).get(30,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause()instanceof Exception)throw(Exception)failed.getCause();if(failed.getCause()instanceof Error)throw(Error)failed.getCause();throw failed;}}
        void sync()throws Exception{io(()->{bridge.synchronize(scope());return null;});}
        JSONObject remember(SourceRef ref,int index)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","同步原来源 "+index).put("sourceRefs",new JSONArray().put(ref.toJson())))).getJSONObject("record");}
        Doc document(int index)throws Exception{
            String text="native original document "+index+"; no model or network";
            JSONObject file=repository.book.importBytes(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),"source-"+index+".txt","text/plain",new JSONObject());
            AttachmentOutcomeHost outcomes=new AttachmentOutcomeHost(tasks,repository,documents,(a,b,c)->{throw new AssertionError("No media or outbound call");});
            String task=tasks.createCapabilityTask("原文档范围 "+index,"synchronization-window-fixture").getString("id");outcomes.selectForTask(task,file.getString("id"),file.getString("sha256"),new RunControl());
            JSONObject attempt=tasks.startAttempt(task,"capabilities",8);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(task,run,epoch);
            tasks.authorizeTools(task,run,new JSONArray().put("attachments_read_document"));tasks.noteOwnerDocumentReference(task,run,epoch,file,original);
            outcomes.readTaskDocument(task,run,epoch,file.getString("id"),file.getString("sha256"),new DocumentContent.Request(1,0,1,2048,8,"text"),original);
            long seq;JSONObject event;try(Cursor rows=db(tasks).rawQuery("SELECT native_seq,event_json FROM task_events WHERE task_id=? AND event_type='attachment_document_read' ORDER BY event_order DESC LIMIT 1",new String[]{task})){assertTrue(rows.moveToFirst());seq=rows.getLong(0);event=new JSONObject(rows.getString(1));}
            SourceRef ref=tasks.selectSavedMemorySource(task,run,seq,0,24000);remember(ref,index);tasks.endAttempt(task,run,"needs_confirmation","原文档范围已读取",new JSONObject());return new Doc(ref,event);
        }
        SourceRef browser(int index)throws Exception{
            String task=tasks.createCapabilityTask("已保存浏览来源fixture "+index,"synchronization-window-fixture").getString("id");JSONObject attempt=tasks.startAttempt(task,"capabilities",1);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");
            JSONObject source=new JSONObject().put("kind",BrowserSemanticRead.KIND).put("taskId",task).put("runId",run).put("epoch",epoch).put("sourceId",UUID.randomUUID().toString())
                    .put("profileId","fixture-profile").put("profileRevision",1).put("browserSessionId","fixture-session").put("browserSpaceId","fixture-space").put("tabId","fixture-tab")
                    .put("tabGeneration",1).put("navigation",1).put("documentId","fixture-document").put("frameId","main").put("observationId","fixture-observation-"+index).put("excerpt","已明确保存的安全摘录fixture "+index);
            JSONObject subset=new JSONObject().put("kind","browser_semantic_retained_delivered_subset").put("scope","explicitly_retained_model_delivered_excerpt_and_fields_only").put("wholePageIncluded",false).put("untrustedPageContent",true).put("verified",false);
            for(String key:new String[]{"taskId","runId","epoch","sourceId","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId","frameId","observationId","excerpt"})subset.put(key,source.get(key));
            JSONObject event=new JSONObject().put("type","browser_source_retained").put("taskId",task).put("runId",run).put("epoch",epoch).put("sourceId",source.getString("sourceId")).put("source",source)
                    .put("sourceFingerprint",TaskStore.capabilityFingerprint(source)).put("safeProjectionFormat","pocketworld.browser-retained-safe-delivery.v1").put("safeProjectionFingerprint",TaskStore.capabilityFingerprint(subset));
            tasks.event(task,event);long seq=TaskAuditStore.lastSequence(db(tasks),task);SourceRef ref=tasks.selectSavedMemorySource(task,run,seq,0,24000);
            JSONObject candidate=memory.proposeLearning(scope(),new JSONObject().put("layer","fact").put("text","未采用的浏览候选 "+index).put("reason","来源窗口fixture").put("applicability","仅测试原历史同步").put("confidence",0.5),ref).getJSONObject("candidate");
            JSONObject rejected=memory.handle("rejectCandidate",new JSONObject().put("scope",scope()).put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("reason","主人明确不采用fixture")).getJSONObject("candidate");
            memory.archiveCandidates(scope(),new JSONArray().put(new JSONObject().put("id",rejected.getString("id")).put("revision",rejected.getLong("revision"))));tasks.endAttempt(task,run,"needs_confirmation","fixture安全保存事件",new JSONObject());return ref;
        }
        public void close()throws Exception{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);host.close();db(tasks).close();}
    }
    @Test public void sixtyFiveColdDocumentsContinueOnlyAfterAllOriginalWarmupsAreActuallyComplete()throws Exception{
        try(Fixture f=new Fixture()){
            List<Doc> docs=new ArrayList<>();for(int i=0;i<65;i++)docs.add(f.document(i));f.cold();
            assertThrows(IllegalStateException.class,f::sync);for(int i=0;i<64;i++)assertTrue(f.eligibility.outputSourceCurrent(docs.get(i).id,docs.get(i).sha,docs.get(i).generation));assertFalse(f.eligibility.outputSourceCurrent(docs.get(64).id,docs.get(64).sha,docs.get(64).generation));
            f.sync();for(Doc doc:docs)assertTrue(f.eligibility.outputSourceCurrent(doc.id,doc.sha,doc.generation));assertEquals(65,f.memory.sourceRefsForSynchronization(scope()).length());
            f.cold();for(Doc doc:docs)assertFalse(f.eligibility.outputSourceCurrent(doc.id,doc.sha,doc.generation));
            assertThrows(IllegalStateException.class,f::sync);f.sync();assertEquals(65,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
        }
    }
    @Test public void sixtyFourAlreadyWarmSourcesDoNotConsumeTheLastColdDocumentsPreparationBudget()throws Exception{
        try(Fixture f=new Fixture()){
            List<Doc> docs=new ArrayList<>();for(int i=0;i<65;i++)docs.add(f.document(i));f.cold();
            f.io(()->{for(int i=0;i<64;i++)f.eligibility.readVerifiedMetadata(docs.get(i).id,docs.get(i).sha,new RunControl());return null;});
            assertFalse(f.eligibility.outputSourceCurrent(docs.get(64).id,docs.get(64).sha,docs.get(64).generation));f.sync();
            assertTrue(f.eligibility.outputSourceCurrent(docs.get(64).id,docs.get(64).sha,docs.get(64).generation));assertEquals(65,f.memory.sourceRefsForSynchronization(scope()).length());
        }
    }
    @Test public void rejectedArchivedBrowserAndDeletedHistoryDoNotExhaustUnrelatedDocumentWarmups()throws Exception{
        try(Fixture f=new Fixture()){
            List<SourceRef> browser=new ArrayList<>();for(int i=0;i<65;i++)browser.add(f.browser(i));Doc doc=f.document(100);
            SourceRef removed=browser.get(0);TaskSavedEvidence.Address address=TaskSavedEvidence.address(removed.sourceId);assertEquals(1,db(f.tasks).delete("task_events","task_id=? AND native_seq=?",new String[]{address.taskId,Long.toString(address.eventSeq)}));
            f.cold();assertEquals(66,f.memory.sourceRefsForSynchronization(scope()).length());assertTrue(f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),removed.kind,removed.sourceId).deleted);f.sync();
            assertTrue(f.eligibility.outputSourceCurrent(doc.id,doc.sha,doc.generation));
            // Original Book drops this one body-free dedup marker only after its durable source
            // tombstone makes it redundant. The remaining 64 histories and deletion barrier stay.
            assertEquals(64,f.memory.listCandidateHistory(scope()).getJSONArray("history").length());assertTrue(f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),removed.kind,removed.sourceId).deleted);
            assertThrows(Exception.class,()->f.memory.proposeLearning(scope(),new JSONObject().put("layer","fact").put("text","不得复活已删旧来源").put("reason","反例").put("applicability","").put("confidence",.5),removed));
            assertEquals(1,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());f.sync();
        }
    }
}
