package dev.pocketengine.features.automation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class CommitmentBookTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return scope("owner","private");}
    private JSONObject scope(String owner,String space)throws Exception{return new JSONObject().put("ownerId",owner).put("spaceId",space);}
    private File file(){return new File(temp.getRoot(),"commitments.json");}
    private JSONObject input(JSONObject scope,long due)throws Exception {
        return new JSONObject().put("kind","explicit_user").put("title","检查订单是否送达").put("details","确认真实结果后再完成")
                .put("dueAt",due).put("taskId","task-1")
                .put("sourceRef",new JSONObject(scope.toString()).put("kind","user_message").put("id","message-1"));
    }
    private JSONObject create(CommitmentBook book)throws Exception{return book.create(scope(),input(scope(),2000),1000);}
    private JSONObject ref(String kind,String id)throws Exception{return new JSONObject().put("kind",kind).put("id",id);}
    private JSONObject proof(JSONObject record,String kind,String id)throws Exception {
        JSONObject proof=new JSONObject().put("kind",kind).put("id",id).put("ownerId",record.getString("ownerId"))
                .put("spaceId",record.getString("spaceId")).put("commitmentId",record.getString("id"))
                .put("commitmentRevision",record.getLong("revision")).put("outcome","completed").put("recordedAt",2500);
        if(kind.equals("task_result"))proof.put("taskId",record.getString("taskId"));return proof;
    }
    /** A durable test evidence ledger, not a live TaskStore integration or real business result. */
    private final class EvidenceLedger implements CommitmentBook.EvidenceResolver {
        final File evidence=new File(temp.getRoot(),"evidence.json");final AtomicInteger lookups=new AtomicInteger();
        void save(JSONObject record)throws Exception{Files.write(evidence.toPath(),record.toString().getBytes(StandardCharsets.UTF_8));}
        @Override public JSONObject resolve(JSONObject scope,JSONObject commitment,String kind,String id)throws Exception {
            lookups.incrementAndGet();if(!evidence.isFile())return null;
            JSONObject record=new JSONObject(new String(Files.readAllBytes(evidence.toPath()),StandardCharsets.UTF_8));
            return id.equals(record.optString("id"))&&kind.equals(record.optString("kind"))?record:null;
        }
    }

    @Test public void emptyStoreCreatesAndRestoresExplicitPromiseWithImmutableProvenance()throws Exception {
        CommitmentBook book=new CommitmentBook(file());assertEquals(0,book.list(scope(),1000).length());
        JSONObject saved=create(book);String id=saved.getString("id");saved.put("title","篡改返回值");
        saved.getJSONObject("sourceRef").put("spaceId","other");
        JSONObject recovered=new CommitmentBook(file()).get(scope(),id,1100);
        assertEquals("检查订单是否送达",recovered.getString("title"));assertEquals("private",recovered.getJSONObject("sourceRef").getString("spaceId"));
        assertEquals("explicit_user",recovered.getString("kind"));assertEquals("pending",recovered.getString("status"));assertEquals(1,recovered.getLong("revision"));
        assertEquals("task-1",recovered.getString("taskId"));assertTrue(recovered.isNull("completionEvidence"));
    }
    @Test public void exactOwnerAndSpaceIsolationAppliesToEveryReadAndMutation()throws Exception {
        CommitmentBook book=new CommitmentBook(file());String id=create(book).getString("id");
        for(JSONObject other:new JSONObject[]{scope("other","private"),scope("owner","group")}){
            assertEquals(0,book.list(other,3000).length());assertEquals(0,book.due(other,3000,10).getJSONArray("commitments").length());
            assertThrows(IllegalArgumentException.class,()->book.get(other,id,3000));
            assertThrows(IllegalArgumentException.class,()->book.update(other,id,1,new JSONObject().put("title","跨空间"),3000));
            assertThrows(IllegalArgumentException.class,()->book.snooze(other,id,1,4000,3000));
            assertThrows(IllegalArgumentException.class,()->book.cancel(other,id,1,3000));
            assertThrows(IllegalArgumentException.class,()->book.complete(other,id,1,ref("task_result","r1"),3000));
            assertThrows(IllegalArgumentException.class,()->book.onTaskCompleted(other,id,1,"e1","task-1",ref("task_result","r1"),3000));
        }
        assertThrows(IllegalArgumentException.class,()->book.list(null,3000));
        assertThrows(IllegalArgumentException.class,()->book.list(scope(" owner","private"),3000));
        assertThrows(IllegalArgumentException.class,()->book.list(new JSONObject().put("ownerId",12).put("spaceId","private"),3000));
        assertEquals(1,book.get(scope(),id,3000).getLong("revision"));
    }
    @Test public void rejectsInferredOriginAndCrossScopeSourceWithoutPersistingAnything()throws Exception {
        CommitmentBook book=new CommitmentBook(file());
        assertThrows(IllegalArgumentException.class,()->book.create(scope(),input(scope(),2000).put("kind","inferred_interest"),1000));
        JSONObject other=input(scope(),2000);other.getJSONObject("sourceRef").put("spaceId","group");
        assertThrows(IllegalArgumentException.class,()->book.create(scope(),other,1000));
        JSONObject inferred=input(scope(),2000);inferred.getJSONObject("sourceRef").put("kind","model_guess");
        assertThrows(IllegalArgumentException.class,()->book.create(scope(),inferred,1000));
        assertFalse(file().exists());
    }
    @Test public void editsRequireCurrentRevisionAndCannotRewriteProvenanceOrTerminalState()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject saved=create(book);String id=saved.getString("id");
        JSONObject updated=book.update(scope(),id,1,new JSONObject().put("title","检查退款").put("details","保留退款凭证").put("dueAt",4000),1500);
        assertEquals(2,updated.getLong("revision"));assertEquals(1000,updated.getLong("createdAt"));assertEquals(1500,updated.getLong("updatedAt"));
        assertThrows(IllegalStateException.class,()->book.update(scope(),id,1,new JSONObject().put("title","旧修改"),1600));
        assertThrows(IllegalStateException.class,()->book.snooze(scope(),id,1,5000,1600));
        assertThrows(IllegalStateException.class,()->book.cancel(scope(),id,1,1600));
        assertThrows(IllegalStateException.class,()->book.complete(scope(),id,1,ref("owner_confirmation","m2"),3000));
        assertThrows(IllegalStateException.class,()->book.onTaskCompleted(scope(),id,1,"e1","task-1",ref("task_result","r1"),3000));
        assertThrows(IllegalArgumentException.class,()->book.update(scope(),id,2,new JSONObject().put("status","completed"),1600));
        assertThrows(IllegalArgumentException.class,()->book.update(scope(),id,2,new JSONObject().put("sourceRef",new JSONObject()),1600));
        assertEquals("检查退款",new CommitmentBook(file()).get(scope(),id,3000).getString("title"));
    }
    @Test public void snoozeExpiresIntoReadOnlyDueCandidateAndExplicitRescheduleEndsSnooze()throws Exception {
        CommitmentBook book=new CommitmentBook(file());String id=create(book).getString("id");
        assertEquals(0,book.due(scope(),1999,10).getJSONArray("commitments").length());
        JSONObject snoozed=book.snooze(scope(),id,1,5000,2000);assertEquals("snoozed",snoozed.getString("status"));
        CommitmentBook reopened=new CommitmentBook(file());assertEquals(0,reopened.due(scope(),4999,10).getJSONArray("commitments").length());
        byte[] before=Files.readAllBytes(file().toPath());JSONObject due=reopened.due(scope(),5000,10);
        JSONObject candidate=due.getJSONArray("commitments").getJSONObject(0);assertTrue(candidate.getBoolean("followUpDue"));
        assertEquals("snoozed",candidate.getString("status"));assertEquals(5000,candidate.getLong("followUpAt"));
        assertEquals("not_dispatched",due.getString("delivery"));assertFalse(due.getBoolean("executionAuthorized"));assertTrue(due.getBoolean("requiresHostAdmission"));
        assertEquals(candidate.getString("candidateKey"),reopened.due(scope(),6000,10).getJSONArray("commitments").getJSONObject(0).getString("candidateKey"));
        assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        JSONObject edited=reopened.update(scope(),id,2,new JSONObject().put("dueAt",8000),6000);
        assertEquals("pending",edited.getString("status"));assertTrue(edited.isNull("snoozedUntil"));
        assertEquals(0,reopened.due(scope(),6000,10).getJSONArray("commitments").length());
    }
    @Test public void cancellationIsDurableAndLateTaskEventCannotReviveOrCompleteIt()throws Exception {
        CommitmentBook book=new CommitmentBook(file());String id=create(book).getString("id");book.snooze(scope(),id,1,4000,2000);
        JSONObject cancelled=book.cancel(scope(),id,2,2500);assertEquals("cancelled",cancelled.getString("status"));
        CommitmentBook reopened=new CommitmentBook(file());assertEquals(0,reopened.due(scope(),9000,10).getJSONArray("commitments").length());
        JSONObject event=reopened.onTaskCompleted(scope(),id,1,"late","task-1",ref("task_result","receipt"),9000);
        assertFalse(event.getBoolean("applied"));assertEquals("terminal",event.getString("reason"));
        assertEquals(3,reopened.get(scope(),id,9000).getLong("revision"));
        assertThrows(IllegalStateException.class,()->reopened.snooze(scope(),id,3,12000,9000));
        assertThrows(IllegalStateException.class,()->reopened.update(scope(),id,3,new JSONObject().put("title","复活"),9000));
        assertThrows(IllegalStateException.class,()->reopened.complete(scope(),id,3,ref("owner_confirmation","done"),9000));
        assertEquals(3,reopened.cancel(scope(),id,3,9000).getLong("revision"));
    }
    @Test public void missingResolverOrUnresolvedReferenceNeverClaimsCompletion()throws Exception {
        CommitmentBook book=new CommitmentBook(file());String id=create(book).getString("id");
        assertFalse(book.completionEvidenceAvailable());
        assertThrows(IllegalStateException.class,()->book.complete(scope(),id,1,ref("owner_confirmation","invented").put("verified",true),3000));
        CommitmentBook withLedger=new CommitmentBook(file(),new EvidenceLedger());assertTrue(withLedger.completionEvidenceAvailable());
        assertThrows(IllegalStateException.class,()->withLedger.complete(scope(),id,1,ref("task_result","invented"),3000));
        assertEquals("pending",book.get(scope(),id,3000).getString("status"));assertEquals(1,book.get(scope(),id,3000).getLong("revision"));
    }
    @Test public void actualReferencedOwnerConfirmationCompletesAndSurvivesRestart()throws Exception {
        EvidenceLedger ledger=new EvidenceLedger();CommitmentBook book=new CommitmentBook(file(),ledger);JSONObject record=create(book);String id=record.getString("id");
        ledger.save(proof(record,"owner_confirmation","message-done"));
        JSONObject completed=book.complete(scope(),id,1,ref("owner_confirmation","message-done"),3000);
        assertEquals("completed",completed.getString("status"));assertEquals(2,completed.getLong("revision"));
        JSONObject restored=new CommitmentBook(file()).get(scope(),id,4000);
        assertEquals("message-done",restored.getJSONObject("completionEvidence").getString("id"));assertFalse(restored.getBoolean("followUpDue"));
        assertFalse(restored.getBoolean("executionAuthorized"));assertTrue(restored.isNull("candidateKey"));assertEquals(0,book.due(scope(),10000,10).getJSONArray("commitments").length());
        assertThrows(IllegalStateException.class,()->book.cancel(scope(),id,2,4000));
        assertThrows(IllegalStateException.class,()->book.snooze(scope(),id,2,5000,4000));
    }
    @Test public void resolverMustProveExactScopeCommitmentRevisionTaskAndSuccessfulOutcome()throws Exception {
        EvidenceLedger ledger=new EvidenceLedger();CommitmentBook book=new CommitmentBook(file(),ledger);JSONObject record=create(book);String id=record.getString("id");
        String[] keys={"ownerId","spaceId","commitmentId","taskId","outcome","kind","id"};
        for(String key:keys){ledger.save(proof(record,"task_result","r1").put(key,"wrong"));
            assertThrows(IllegalStateException.class,()->book.complete(scope(),id,1,ref("task_result","r1"),3000));}
        for(Object invalidRevision:new Object[]{2,1.5,"1"}){
            ledger.save(proof(record,"task_result","r1").put("commitmentRevision",invalidRevision));
            assertThrows(IllegalStateException.class,()->book.complete(scope(),id,1,ref("task_result","r1"),3000));
        }
        ledger.save(proof(record,"task_result","r1").put("recordedAt",4000));
        assertThrows(IllegalStateException.class,()->book.complete(scope(),id,1,ref("task_result","r1"),3000));
        ledger.save(proof(record,"task_result","r1"));
        assertThrows(IllegalArgumentException.class,()->book.complete(scope(),id,1,ref("task_result","r1").put("spaceId","group"),3000));
        assertEquals(1,book.get(scope(),id,3000).getLong("revision"));
        assertEquals("completed",book.complete(scope(),id,1,ref("task_result","r1"),3000).getString("status"));
    }
    @Test public void duplicateTaskEventAfterRestartIsIdempotentAndConflictingReplayIsRejected()throws Exception {
        EvidenceLedger ledger=new EvidenceLedger();CommitmentBook book=new CommitmentBook(file(),ledger);JSONObject record=create(book);String id=record.getString("id");ledger.save(proof(record,"task_result","r1"));
        JSONObject first=book.onTaskCompleted(scope(),id,1,"event-1","task-1",ref("task_result","r1"),3000);assertTrue(first.getBoolean("applied"));
        byte[] before=Files.readAllBytes(file().toPath());CommitmentBook reopened=new CommitmentBook(file());
        JSONObject duplicate=reopened.onTaskCompleted(scope(),id,1,"event-1","task-1",ref("task_result","r1"),4000);
        assertFalse(duplicate.getBoolean("applied"));assertEquals("duplicate",duplicate.getString("reason"));assertEquals(1,ledger.lookups.get());
        assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        assertThrows(IllegalStateException.class,()->reopened.onTaskCompleted(scope(),id,1,"event-1","task-1",ref("task_result","different"),4000));
        assertThrows(IllegalArgumentException.class,()->reopened.onTaskCompleted(scope(),id,1,"event-1","task-1",ref("task_result","r1").put("spaceId","group"),4000));
        assertThrows(IllegalArgumentException.class,()->reopened.onTaskCompleted(scope(),id,1,"event-1","another-task",ref("task_result","r1"),4000));
        assertEquals("terminal",reopened.onTaskCompleted(scope(),id,2,"different-event","task-1",ref("task_result","r1"),4000).getString("reason"));
    }
    @Test public void simultaneousInstancesConsumeOneTaskCompletionOnly()throws Exception {
        EvidenceLedger ledger=new EvidenceLedger();CommitmentBook one=new CommitmentBook(file(),ledger),two=new CommitmentBook(file(),ledger);
        JSONObject record=create(one);String id=record.getString("id");ledger.save(proof(record,"task_result","r1"));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{Future<Boolean> a=executor.submit(()->one.onTaskCompleted(scope(),id,1,"e1","task-1",ref("task_result","r1"),3000).getBoolean("applied"));
            Future<Boolean> b=executor.submit(()->two.onTaskCompleted(scope(),id,1,"e1","task-1",ref("task_result","r1"),3000).getBoolean("applied"));
            assertNotEquals(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
        assertEquals(2,one.get(scope(),id,4000).getLong("revision"));assertEquals(1,ledger.lookups.get());
    }
    @Test public void everyFailedWriteLeavesDiskAndNextReadUnchangedAndEventCanRetry()throws Exception {
        EvidenceLedger ledger=new EvidenceLedger();CommitmentBook book=new CommitmentBook(file(),ledger);JSONObject record=create(book);String id=record.getString("id");ledger.save(proof(record,"task_result","r1"));
        byte[] before=Files.readAllBytes(file().toPath());File blocked=new File(file().getPath()+".new");assertTrue(blocked.mkdir());
        assertThrows(IOException.class,()->book.create(scope(),input(scope(),3000),1500));
        assertThrows(IOException.class,()->book.update(scope(),id,1,new JSONObject().put("title","不应保存"),1500));
        assertThrows(IOException.class,()->book.snooze(scope(),id,1,5000,1500));
        assertThrows(IOException.class,()->book.cancel(scope(),id,1,1500));
        assertThrows(IOException.class,()->book.complete(scope(),id,1,ref("task_result","r1"),3000));
        assertThrows(IOException.class,()->book.onTaskCompleted(scope(),id,1,"event-1","task-1",ref("task_result","r1"),3000));
        assertArrayEquals(before,Files.readAllBytes(file().toPath()));assertEquals(1,book.list(scope(),3000).length());
        assertEquals("pending",book.get(scope(),id,3000).getString("status"));assertEquals(1,new CommitmentBook(file()).get(scope(),id,3000).getLong("revision"));
        assertTrue(blocked.delete());assertTrue(book.onTaskCompleted(scope(),id,1,"event-1","task-1",ref("task_result","r1"),3000).getBoolean("applied"));
        assertEquals("completed",new CommitmentBook(file()).get(scope(),id,4000).getString("status"));
    }
    @Test public void failedFirstCreateDoesNotInventSavedPromiseAndCanBeRetried()throws Exception {
        File blocked=new File(file().getPath()+".new");assertTrue(blocked.mkdir());CommitmentBook book=new CommitmentBook(file());
        assertThrows(IOException.class,()->create(book));assertFalse(file().exists());assertEquals(0,book.list(scope(),1000).length());
        assertTrue(blocked.delete());assertEquals(1,create(book).getLong("revision"));assertEquals(1,book.list(scope(),1000).length());
    }
    @Test public void noInputFieldCanCreateExecutionAuthorizationOrModelCompletion()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject request=input(scope(),2000).put("executionAuthorized",true).put("requiresHostAdmission",false);
        JSONObject record=book.create(scope(),request,1000);assertFalse(record.getBoolean("executionAuthorized"));assertTrue(record.getBoolean("referenceOnly"));
        assertThrows(IllegalArgumentException.class,()->book.create(scope(),input(scope(),2000).put("status","completed"),1000));
        assertThrows(IllegalArgumentException.class,()->book.update(scope(),record.getString("id"),1,new JSONObject().put("executionAuthorized",true),2000));
        JSONObject due=book.due(scope(),3000,10);assertFalse(due.getBoolean("executionAuthorized"));assertEquals("not_dispatched",due.getString("delivery"));
    }
    @Test public void sourceOnlyPromiseCanCompleteByOwnerButCannotBorrowTaskEvidence()throws Exception {
        EvidenceLedger ledger=new EvidenceLedger();CommitmentBook book=new CommitmentBook(file(),ledger);
        JSONObject request=input(scope(),2000);request.remove("taskId");request.getJSONObject("sourceRef").put("kind","user_action");
        JSONObject record=book.create(scope(),request,1000);String id=record.getString("id");assertEquals("",record.getString("taskId"));
        ledger.save(proof(record,"task_result","r1").put("taskId","unbound-task"));
        assertThrows(IllegalStateException.class,()->book.complete(scope(),id,1,ref("task_result","r1"),3000));
        ledger.save(proof(record,"owner_confirmation","owner-done"));
        assertEquals("completed",book.complete(scope(),id,1,ref("owner_confirmation","owner-done"),3000).getString("status"));
    }
    @Test public void dueCandidatesAreStableSortedBoundedAndScopeLocal()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject later=book.create(scope(),input(scope(),4000),1000),earlier=book.create(scope(),input(scope(),2000),1000);
        book.create(scope("owner","group"),input(scope("owner","group"),1500),1000);
        JSONObject first=book.due(scope(),5000,1);assertEquals(1,first.getJSONArray("commitments").length());assertTrue(first.getBoolean("hasMore"));
        assertEquals(earlier.getString("id"),first.getJSONArray("commitments").getJSONObject(0).getString("id"));assertEquals(2000,first.getLong("nextFollowUpAt"));
        JSONArray all=book.due(scope(),5000,2).getJSONArray("commitments");assertEquals(later.getString("id"),all.getJSONObject(1).getString("id"));
        assertThrows(IllegalArgumentException.class,()->book.due(scope(),5000,0));assertThrows(IllegalArgumentException.class,()->book.due(scope(),5000,101));
    }
    @Test public void invalidTimesAndStorageFormatDoNotOverwriteExistingData()throws Exception {
        CommitmentBook book=new CommitmentBook(file());
        for(Object bad:new Object[]{0,-1,2000.5,"2000",JSONObject.NULL})
            assertThrows(IllegalArgumentException.class,()->book.create(scope(),input(scope(),2000).put("dueAt",bad),1000));
        String id=create(book).getString("id");assertThrows(IllegalArgumentException.class,()->book.snooze(scope(),id,1,2000,2000));
        assertEquals(1,book.get(scope(),id,2000).getLong("revision"));
        byte[] unsupported="{\"version\":3,\"commitments\":[]}".getBytes(StandardCharsets.UTF_8);Files.write(file().toPath(),unsupported);
        assertThrows(IllegalStateException.class,()->book.create(scope(),input(scope(),2000),1000));assertArrayEquals(unsupported,Files.readAllBytes(file().toPath()));
    }
}
