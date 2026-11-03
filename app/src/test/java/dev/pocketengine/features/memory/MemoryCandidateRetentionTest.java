package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemoryCandidateRetentionTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static final class Host implements SourceRef.Resolver {
        final Map<String,SourceRef.State> states=new HashMap<>();
        void live(SourceRef ref){states.put(ref.key(),new SourceRef.State(ref,false));}
        void deleted(SourceRef ref){states.put(ref.key(),new SourceRef.State(ref,true));}
        @Override public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String id){return states.get(new SourceRef(kind,id,1,scope.ownerId,scope.spaceId).key());}
    }
    private interface Checked {void run()throws Exception;}
    private void refused(Checked call)throws Exception {
        try{call.run();fail("expected refusal");}catch(IllegalArgumentException|IllegalStateException expected){}
    }
    private void ioFailed(Checked call)throws Exception {try{call.run();fail("expected I/O failure");}catch(IOException expected){}}
    private JSONObject scope(String owner,String space)throws Exception{return new JSONObject().put("ownerId",owner).put("spaceId",space);}
    private JSONObject own()throws Exception{return scope("owner","private");}
    private SourceRef ref(String id,long revision){return new SourceRef("message",id,revision,"owner","private");}
    private JSONObject draft(String text)throws Exception{return new JSONObject().put("layer","fact").put("text",text).put("reason","待审理由");}
    private JSONArray selection(JSONObject... rows)throws Exception {
        JSONArray result=new JSONArray();for(JSONObject row:rows)result.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));return result;
    }
    private JSONObject reject(MemoryBook book,SourceRef ref,String text)throws Exception {
        JSONObject c=book.proposeLearning(own(),draft(text),1,ref);return book.rejectCandidate(own(),c.getString("id"),c.getLong("revision"),"不采用",2);
    }
    private File file(){return new File(temp.getRoot(),"records.json");}
    private String bytes()throws Exception{return new String(Files.readAllBytes(file().toPath()),StandardCharsets.UTF_8);}
    private void write(JSONObject state)throws Exception{Files.write(file().toPath(),state.toString().getBytes(StandardCharsets.UTF_8));}

    @Test public void fullTerminalHistoryCanBeExplicitlyClearedWithoutRevivingOldProposals()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONArray selected=new JSONArray();String firstId="";
        for(int i=0;i<200;i++){
            JSONObject c=reject(book,source,"正文不能留在标记"+i);selected.put(selection(c).getJSONObject(0));if(i==0)firstId=c.getString("id");
        }
        refused(()->book.proposeLearning(own(),draft("超额新候选"),3,source));
        assertEquals(0,book.candidateCapacity(own()).getInt("candidateSlotsAvailable"));
        JSONObject result=book.archiveCandidates(own(),selected,4);
        assertEquals(200,result.getInt("removedCandidates"));assertEquals(200,result.getInt("retainedMarkers"));
        assertEquals(200,result.getJSONObject("capacity").getInt("candidateSlotsAvailable"));
        assertFalse(bytes().contains("正文不能留在标记"));assertFalse(bytes().contains("待审理由"));assertFalse(bytes().contains("不采用"));
        MemoryBook reopened=new MemoryBook(file(),host);
        JSONObject duplicate=reopened.proposeLearning(own(),draft("正文不能留在标记0"),5,source);
        assertEquals(firstId,duplicate.getString("id"));assertEquals("archived",duplicate.getString("status"));assertEquals("rejected",duplicate.getString("outcome"));
        assertFalse(duplicate.has("text"));assertEquals(0,reopened.listCandidates(own()).length());
        assertEquals("pending",reopened.proposeLearning(own(),draft("真正新候选"),6,source).getString("status"));
        assertEquals(200,reopened.listCandidateHistory(own()).length());assertEquals(0,reopened.list(own()).length());
        assertEquals("",reopened.contextSnapshot(own(),"",16,12000).getString("context"));
    }

    @Test public void acceptedCleanupKeepsFormalMemoryAndItsCorrectionsAndProvenance()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject c=book.proposeLearning(own(),draft("已接受正文"),1,source),accepted=book.acceptCandidate(own(),c.getString("id"),1,2);
        JSONObject record=accepted.getJSONObject("record");
        assertThrows(SecurityException.class,()->book.save(own(),new JSONObject(record.toString()).put("text","机主纠正的正式记忆"),3));
        book.save(own(),new JSONObject().put("id",record.getString("id")).put("revision",record.getLong("revision")).put("text","机主纠正的正式记忆"),3);
        String recordsBefore=new JSONObject(bytes()).getJSONArray("records").toString(),sourcesBefore=new JSONObject(bytes()).getJSONArray("sources").toString();
        book.archiveCandidates(own(),selection(accepted.getJSONObject("candidate")),4);
        assertEquals(recordsBefore,new JSONObject(bytes()).getJSONArray("records").toString());assertEquals(sourcesBefore,new JSONObject(bytes()).getJSONArray("sources").toString());
        MemoryBook reopened=new MemoryBook(file(),host);JSONObject marker=reopened.listCandidateHistory(own()).getJSONObject(0);
        assertEquals(record.getString("id"),marker.getString("acceptedRecordId"));assertEquals("accepted",marker.getString("outcome"));assertFalse(marker.has("text"));
        assertEquals("机主纠正的正式记忆",reopened.get(own(),record.getString("id")).getString("text"));
        assertEquals(c.getString("id"),reopened.get(own(),record.getString("id")).getString("learningCandidateId"));
        assertEquals("archived",reopened.proposeLearning(own(),draft("已接受正文"),5,source).getString("status"));
        refused(()->reopened.acceptCandidate(own(),c.getString("id"),1,5));
        refused(()->reopened.reconsiderRejected(own(),marker.getString("id"),marker.getLong("revision"),draft("已接受正文"),5));
    }

    @Test public void cleanupPreflightsWholeSelectionAndProtectsPendingScopeAndRevision()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject rejected=reject(book,source,"终态"),pending=book.proposeLearning(own(),draft("待审"),3,source);String before=bytes();
        refused(()->book.archiveCandidates(own(),selection(rejected,pending),4));assertEquals(before,bytes());
        refused(()->book.archiveCandidates(scope("owner","other"),selection(rejected),4));
        refused(()->book.archiveCandidates(scope("other","private"),selection(rejected),4));
        refused(()->book.archiveCandidates(own(),selection(rejected,rejected),4));
        refused(()->book.archiveCandidates(own(),selection(rejected).put(new JSONObject().put("id",pending.getString("id")).put("revision",1.5)),4));
        refused(()->book.archiveCandidates(own(),new JSONArray(),4));refused(()->book.archiveCandidates(null,selection(rejected),4));
        refused(()->book.archiveCandidates(own(),new JSONArray().put(selection(rejected).getJSONObject(0).put("approved",true)),4));
        assertEquals(before,bytes());
        JSONObject changed=book.reconsiderRejected(own(),rejected.getString("id"),2,draft("终态"),5);
        refused(()->book.archiveCandidates(own(),selection(rejected),6));assertEquals("pending",changed.getString("status"));
        assertEquals(2,book.candidateCapacity(own()).getJSONObject("scopeCounts").getInt("pending"));
    }

    @Test public void forgetPrunesOnlyRedundantMarkersAndCleanupNeverReleasesItsSourceFloor()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject rejected=reject(book,source,"拒绝秘密"),pending=book.proposeLearning(own(),draft("待审秘密"),3,source);
        JSONObject accepted=book.acceptCandidate(own(),book.proposeLearning(own(),draft("接受秘密"),3,source).getString("id"),1,4);
        book.archiveCandidates(own(),selection(rejected,accepted.getJSONObject("candidate")),5);
        book.forget(own(),accepted.getJSONObject("record").getString("id"));
        assertEquals(0,book.listCandidateHistory(own()).length());JSONObject invalid=book.getCandidate(own(),pending.getString("id"));
        assertEquals("forgotten",invalid.getString("invalidationReason"));String sources=new JSONObject(bytes()).getJSONArray("sources").toString();
        book.archiveCandidates(own(),selection(invalid),6);assertEquals(sources,new JSONObject(bytes()).getJSONArray("sources").toString());
        assertEquals(0,book.listCandidates(own()).length());assertFalse(bytes().contains("秘密"));
        MemoryBook reopened=new MemoryBook(file(),host);refused(()->reopened.proposeLearning(own(),draft("拒绝秘密"),7,source));
        reopened.resaveForgotten(own(),new JSONObject().put("text","明确恢复").put("sourceRefs",new JSONArray().put(source.toJson())),8);
        refused(()->reopened.proposeLearning(own(),draft("拒绝秘密"),9,source));
        SourceRef fresh=ref("source",2);host.live(fresh);
        assertEquals("pending",reopened.proposeLearning(own(),draft("拒绝秘密"),10,fresh).getString("status"));
        assertEquals(2,new JSONObject(bytes()).getInt("learningVersion"));
    }

    @Test public void changedAndDeletedSourcesRetireMarkersButCannotRestoreOldVersions()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1),next=ref("source",2);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject rejected=reject(book,source,"旧候选");book.archiveCandidates(own(),selection(rejected),3);
        host.live(next);book.sourceUpdated(own(),next);assertEquals(0,book.listCandidateHistory(own()).length());
        JSONObject newer=reject(book,next,"旧候选");book.archiveCandidates(own(),selection(newer),4);
        host.deleted(next);book.sourceDeleted(own(),next);assertEquals(0,new MemoryBook(file(),host).listCandidateHistory(own()).length());
        host.live(next);refused(()->book.proposeLearning(own(),draft("旧候选"),5,next));
        host.live(source);refused(()->book.proposeLearning(own(),draft("旧候选"),6,source));
        SourceRef newest=ref("source",3);host.live(newest);assertEquals("pending",book.proposeLearning(own(),draft("旧候选"),7,newest).getString("status"));
    }

    @Test public void explicitReconsiderationIsOneScopedCasAndPreservesOriginalAndEditedRetryKeys()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject c=book.proposeLearning(own(),draft("模型原文"),1,source);String id=c.getString("id");
        book.editCandidate(own(),id,1,new JSONObject().put("text","机主最后审阅正文"),2);
        JSONObject rejected=book.rejectCandidate(own(),id,2,"不采用",3);book.archiveCandidates(own(),selection(rejected),4);
        JSONObject marker=book.listCandidateHistory(own()).getJSONObject(0);long revision=marker.getLong("revision");String before=bytes();
        assertEquals("archived",book.proposeLearning(own(),draft("模型原文"),5,source).getString("status"));
        assertEquals("archived",book.proposeLearning(own(),draft("机主最后审阅正文"),5,source).getString("status"));
        refused(()->book.reconsiderRejected(own(),id,revision,draft("模型原文"),6));
        refused(()->book.reconsiderRejected(own(),id,revision,draft("其他正文"),6));
        refused(()->book.reconsiderRejected(scope("owner","other"),id,revision,draft("机主最后审阅正文"),6));
        refused(()->book.proposeLearning(own(),draft("机主最后审阅正文").put("reconsider",true),6,source));assertEquals(before,bytes());
        JSONObject pending=book.reconsiderRejected(own(),id,revision,draft("机主最后审阅正文").put("reason","现在重新看一下"),7);
        assertEquals("pending",pending.getString("status"));assertEquals(revision+1,pending.getLong("revision"));assertEquals(0,book.listCandidateHistory(own()).length());
        refused(()->book.reconsiderRejected(own(),id,revision,draft("机主最后审阅正文"),8));
        assertEquals(id,book.proposeLearning(own(),draft("模型原文"),8,source).getString("id"));assertEquals("机主最后审阅正文",book.proposeLearning(own(),draft("模型原文"),8,source).getString("text"));
        assertEquals("pending",new MemoryBook(file(),host).getCandidate(own(),id).getString("status"));assertEquals(0,book.list(own()).length());
    }

    @Test public void markerLimitFailsHonestlyAndOnlyDurableSourceChangesMakeRoom()throws Exception {
        Host host=new Host();SourceRef stable=ref("stable",1),other=ref("other",1);host.live(stable);host.live(other);
        MemoryBook seed=new MemoryBook(file(),host);JSONObject c=reject(seed,stable,"种子候选");seed.archiveCandidates(own(),selection(c),3);
        JSONObject state=new JSONObject(bytes()),template=state.getJSONArray("candidateHistory").getJSONObject(0);JSONArray markers=new JSONArray();
        // Persisted capacity fixture: 1,000 distinct metadata-only keys, sharing one real source identity.
        for(int i=0;i<1000;i++)markers.put(new JSONObject(template.toString()).put("id",UUID.randomUUID().toString()).put("dedupKey",String.format("%064x",i)).put("contentKey",String.format("%064x",i)));
        state.put("candidateHistory",markers);JSONObject oversized=new JSONObject(state.toString());
        oversized.getJSONArray("candidateHistory").put(new JSONObject(template.toString()).put("id",UUID.randomUUID().toString()));
        write(oversized);refused(()->new MemoryBook(file(),host));write(state);MemoryBook book=new MemoryBook(file(),host);
        JSONObject rejected=reject(book,other,"需要标记空间");String before=bytes();refused(()->book.archiveCandidates(own(),selection(rejected),4));assertEquals(before,bytes());
        JSONObject capacity=book.candidateCapacity(own());assertEquals(0,capacity.getInt("markerSlotsAvailable"));assertEquals(0,capacity.getInt("scopeArchivableNow"));
        host.deleted(other);book.sourceDeleted(own(),other);JSONObject invalid=book.getCandidate(own(),rejected.getString("id"));
        assertEquals(1,book.candidateCapacity(own()).getInt("scopeArchivableNow"));book.archiveCandidates(own(),selection(invalid),5);
        assertEquals(1000,book.listCandidateHistory(own()).length());
        SourceRef fresh=ref("stable",2);host.live(fresh);book.sourceUpdated(own(),fresh);assertEquals(1000,book.candidateCapacity(own()).getInt("markerSlotsAvailable"));
        JSONObject freshCandidate=reject(book,fresh,"现在能继续清理");book.archiveCandidates(own(),selection(freshCandidate),6);assertEquals(1,new MemoryBook(file(),host).listCandidateHistory(own()).length());
    }

    @Test public void failedCleanupAndReconsiderationRollBackBytesMapsAndSchemaVersion()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject c=reject(book,source,"需要原子清理");
        String before=bytes();File block=new File(file().getPath()+".new");assertTrue(block.mkdir());
        ioFailed(()->book.archiveCandidates(own(),selection(c),3));assertEquals(before,bytes());assertEquals(0,book.listCandidateHistory(own()).length());
        assertEquals("rejected",book.getCandidate(own(),c.getString("id")).getString("status"));assertEquals(1,new JSONObject(bytes()).getInt("learningVersion"));
        assertTrue(block.delete());book.save(own(),new JSONObject().put("text","普通写入不意外升级"),4);assertEquals(1,new JSONObject(bytes()).getInt("learningVersion"));
        book.archiveCandidates(own(),selection(c),5);JSONObject marker=book.listCandidateHistory(own()).getJSONObject(0);before=bytes();assertTrue(block.mkdir());
        ioFailed(()->book.reconsiderRejected(own(),marker.getString("id"),marker.getLong("revision"),draft("需要原子清理"),6));
        assertEquals(before,bytes());assertEquals(0,book.listCandidates(own()).length());assertEquals(1,book.listCandidateHistory(own()).length());
        assertEquals("archived",new MemoryBook(file(),host).proposeLearning(own(),draft("需要原子清理"),7,source).getString("status"));assertTrue(block.delete());
    }

    @Test public void failedSourceChangeOrForgetKeepsMarkersAndFormalMemoryTogether()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1),next=ref("source",2);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject accepted=book.acceptCandidate(own(),book.proposeLearning(own(),draft("正式记忆"),1,source).getString("id"),1,2);
        book.archiveCandidates(own(),selection(accepted.getJSONObject("candidate")),3);String before=bytes();
        File block=new File(file().getPath()+".new");assertTrue(block.mkdir());
        ioFailed(()->book.forget(own(),accepted.getJSONObject("record").getString("id")));host.live(next);ioFailed(()->book.sourceUpdated(own(),next));
        ioFailed(()->book.proposeLearning(own(),draft("新来源待审"),4,next));
        assertEquals(before,bytes());assertEquals(1,book.listCandidateHistory(own()).length());assertEquals(1,book.list(own()).length());
        assertEquals(1,new MemoryBook(file(),host).listCandidateHistory(own()).length());assertTrue(block.delete());
        book.sourceUpdated(own(),next);assertEquals(0,book.listCandidateHistory(own()).length());assertEquals(0,book.list(own()).length());
    }

    @Test public void renameFailureDoesNotArchiveOrChangeTheLearningVersion()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject c=reject(book,source,"重命名失败");byte[] original=Files.readAllBytes(file().toPath());
        File saved=new File(temp.getRoot(),"saved.json");Files.move(file().toPath(),saved.toPath());assertTrue(file().mkdir());File child=new File(file(),"block");assertTrue(child.createNewFile());
        ioFailed(()->book.archiveCandidates(own(),selection(c),3));assertEquals(0,book.listCandidateHistory(own()).length());assertEquals("rejected",book.getCandidate(own(),c.getString("id")).getString("status"));
        assertArrayEquals(original,Files.readAllBytes(saved.toPath()));assertTrue(child.delete());assertTrue(file().delete());Files.move(saved.toPath(),file().toPath());
        book.save(own(),new JSONObject().put("text","旧schema仍可写"),4);assertEquals(1,new JSONObject(bytes()).getInt("learningVersion"));
        book.archiveCandidates(own(),selection(c),5);assertEquals(2,new JSONObject(bytes()).getInt("learningVersion"));
    }

    @Test public void archiveAndReconsiderRaceHasExactlyOneWinnerAtTheDisplayedRevision()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject c=reject(book,source,"并发审阅");
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Boolean> archive=workers.submit(()->{start.await();try{book.archiveCandidates(own(),selection(c),3);return true;}catch(IllegalStateException|IllegalArgumentException expected){return false;}});
            Future<Boolean> reconsider=workers.submit(()->{start.await();try{book.reconsiderRejected(own(),c.getString("id"),c.getLong("revision"),draft("并发审阅"),3);return true;}catch(IllegalStateException|IllegalArgumentException expected){return false;}});
            start.countDown();assertNotEquals(archive.get(5,TimeUnit.SECONDS),reconsider.get(5,TimeUnit.SECONDS));
            MemoryBook reopened=new MemoryBook(file(),host);assertEquals(1,reopened.listCandidateHistory(own()).length()+reopened.listCandidates(own()).length());
            assertEquals(0,reopened.list(own()).length());
        }finally{start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test public void markerReadsAreScopedDefensiveAndDoNotPollOrTrustModelEvidence()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject c=reject(book,source,"不能由模型恢复");book.archiveCandidates(own(),selection(c),3);
        JSONArray rows=book.listCandidateHistory(own());JSONObject marker=rows.getJSONObject(0);long revision=marker.getLong("revision");
        marker.put("outcome","accepted");marker.getJSONArray("sourceRefs").getJSONObject(0).put("revision",999);
        assertEquals("rejected",book.listCandidateHistory(own()).getJSONObject(0).getString("outcome"));assertEquals(0,book.listCandidateHistory(scope("owner","other")).length());
        assertEquals(0,book.candidateCapacity(scope("owner","other")).getJSONObject("scopeCounts").getInt("archived"));
        host.live(ref("source",2));String before=bytes();
        refused(()->book.reconsiderRejected(own(),c.getString("id"),revision,draft("不能由模型恢复"),4));assertEquals(before,bytes());
        assertEquals(1,book.listCandidateHistory(own()).length()); // Only a durable event/write can retire the marker.
    }

    @Test public void fullPendingQueueCannotBeSilentlyEvictedToReconsiderAnArchivedRejection()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);
        JSONObject rejected=reject(book,source,"曾拒绝");book.archiveCandidates(own(),selection(rejected),3);JSONObject marker=book.listCandidateHistory(own()).getJSONObject(0);
        for(int i=0;i<200;i++)book.proposeLearning(own(),draft("待审"+i),4,source);
        String before=bytes();refused(()->book.reconsiderRejected(own(),marker.getString("id"),marker.getLong("revision"),draft("曾拒绝"),5));
        assertEquals(before,bytes());assertEquals(200,book.listCandidates(own()).length());assertEquals(1,book.listCandidateHistory(own()).length());
        assertEquals(0,book.candidateCapacity(own()).getInt("scopeArchivableNow"));
    }

    @Test public void sourceEventFencesStaleCleanupSelectionsWithoutRewritingOtherScopes()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1),other=new SourceRef("message","source",1,"owner","other");host.live(source);host.live(other);
        MemoryBook book=new MemoryBook(file(),host);JSONObject mine=reject(book,source,"本空间"),otherScope=scope("owner","other");
        JSONObject foreign=book.proposeLearning(otherScope,draft("其他空间"),1,other);foreign=book.rejectCandidate(otherScope,foreign.getString("id"),1,"不采用",2);
        book.archiveCandidates(otherScope,selection(foreign),3);String foreignHistory=book.listCandidateHistory(otherScope).toString();
        SourceRef next=ref("source",2);host.live(next);book.sourceUpdated(own(),next);String before=bytes();
        refused(()->book.archiveCandidates(own(),selection(mine),4));assertEquals(before,bytes());
        JSONObject invalid=book.getCandidate(own(),mine.getString("id"));book.archiveCandidates(own(),selection(invalid),5);
        assertEquals(foreignHistory,book.listCandidateHistory(otherScope).toString());assertEquals(1,new MemoryBook(file(),host).listCandidateHistory(otherScope).length());
        assertEquals(0,book.listCandidateHistory(own()).length());
    }

    @Test public void learningVersionTwoIsStickyAndMalformedHistoryNeverGetsDropped()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject c=reject(book,source,"已清正文");String versionOne=bytes();
        new MemoryBook(file(),host);assertEquals(versionOne,bytes());book.archiveCandidates(own(),selection(c),3);String valid=bytes();
        for(String field:new String[]{"candidateHistory","learningVersion"}){
            JSONObject malformed=new JSONObject(valid);malformed.remove(field);write(malformed);String before=bytes();refused(()->new MemoryBook(file(),host));assertEquals(before,bytes());
        }
        for(JSONObject malformed:new JSONObject[]{new JSONObject(valid).put("learningVersion",3),new JSONObject(valid).put("learningVersion",1),new JSONObject(valid).put("version",2)}){
            write(malformed);refused(()->new MemoryBook(file(),host));
        }
        for(String field:new String[]{"text","reason","rejectionReason","applicability","unexpected"}){
            JSONObject malformed=new JSONObject(valid);malformed.getJSONArray("candidateHistory").getJSONObject(0).put(field,"不能残留");write(malformed);refused(()->new MemoryBook(file(),host));
        }
        JSONObject malformed=new JSONObject(valid);malformed.getJSONArray("candidateHistory").put(malformed.getJSONArray("candidateHistory").getJSONObject(0));write(malformed);refused(()->new MemoryBook(file(),host));
        malformed=new JSONObject(valid);malformed.getJSONArray("candidateHistory").getJSONObject(0).put("ownerId","other");write(malformed);refused(()->new MemoryBook(file(),host));
        malformed=new JSONObject(valid);malformed.getJSONArray("candidateHistory").getJSONObject(0).put("dedupKey","not-a-key");write(malformed);refused(()->new MemoryBook(file(),host));
        write(new JSONObject(valid));MemoryBook reopened=new MemoryBook(file(),host);SourceRef next=ref("source",2);host.live(next);reopened.sourceUpdated(own(),next);
        assertEquals(0,reopened.listCandidateHistory(own()).length());reopened.save(own(),new JSONObject().put("text","空历史仍不降级"),5);
        assertEquals(3,new JSONObject(bytes()).getInt("version"));assertEquals(2,new JSONObject(bytes()).getInt("learningVersion"));assertEquals(0,new JSONObject(bytes()).getJSONArray("candidateHistory").length());
    }
}
