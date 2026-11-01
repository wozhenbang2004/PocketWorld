package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
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

public final class MemoryConsolidationTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final Map<String,SourceRef.State> evidence=new HashMap<>();
    private File file(){return new File(temp.getRoot(),"records.json");}
    private MemoryBook open()throws Exception{return new MemoryBook(file(),(s,k,id)->evidence.get(k+":"+id));}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject record(String text)throws Exception{return new JSONObject().put("text",text).put("layer","fact");}
    private JSONObject draft(String text)throws Exception{return record(text).put("reason","合并共同依据，待机主审阅").put("confidence",0.6);}
    private JSONArray refs(JSONObject... rows)throws Exception {
        JSONArray refs=new JSONArray();for(JSONObject row:rows)refs.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));return refs;
    }
    private interface Checked {void run()throws Exception;}
    private void rejected(Checked action)throws Exception {try{action.run();fail("unsafe input admitted");}catch(IllegalStateException|IllegalArgumentException expected){}}

    @Test public void exactParentsBecomePendingOnlyThenOneApprovedNonCoreDerivedRecord()throws Exception {
        MemoryBook book=open();long now=System.currentTimeMillis();JSONObject a=book.save(scope(),record("项目使用Java"),now),b=book.save(scope(),record("项目使用Android"),now);
        JSONArray selection=refs(a,b);JSONObject c=book.proposeConsolidation(scope(),draft("归纳独有正文"),selection,now);
        assertEquals(2,book.list(scope()).length());assertEquals(0,book.search(scope(),"归纳独有正文",10).length());assertEquals("pending",c.getString("status"));
        assertEquals(2,c.getJSONArray("recordRefs").length());assertEquals(0,c.getJSONArray("sourceRefs").length());
        JSONObject accepted=book.acceptCandidate(scope(),c.getString("id"),1,now),r=accepted.getJSONObject("record");
        assertFalse(r.getBoolean("core"));assertEquals("unverified",r.getString("validationStatus"));assertEquals("reviewed_consolidation",r.getString("formation"));
        assertEquals(2,r.getJSONArray("sourceIds").length());assertEquals(2,r.getJSONArray("parentRevisions").length());
        assertEquals(r.getString("id"),open().acceptCandidate(scope(),c.getString("id"),1,now).getJSONObject("record").getString("id"));
        assertEquals(4,new JSONObject(Files.readString(file().toPath())).getInt("version"));
    }

    @Test public void parentSelectionsRejectAmbiguousRevisionForeignScopeAndOverBudgetWithoutWrites()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("本地一"),1),b=book.save(scope(),record("本地二"),2);
        JSONObject group=new JSONObject().put("ownerId","owner").put("spaceId","qq:group:1"),foreign=book.save(group,record("群秘密"),3);
        rejected(()->book.consolidationSnapshot(scope(),refs(a,a),4));rejected(()->book.consolidationSnapshot(scope(),refs(a,foreign),4));
        for(Object wrong:new Object[]{1.0,1.2,"1",0,-1,Long.MAX_VALUE}){
            JSONArray bad=refs(a,b);bad.getJSONObject(0).put("revision",wrong);rejected(()->book.proposeConsolidation(scope(),draft("不能保存"),bad,4));
        }
        rejected(()->book.proposeConsolidation(scope(),draft("不能自己指定父来源").put("recordRefs",refs(a,b)),refs(a,b),4));
        JSONArray large=new JSONArray();for(int i=0;i<8;i++)large.put(book.save(scope(),record("字".repeat(3900)+i),10+i));
        JSONObject[] rows=new JSONObject[large.length()];for(int i=0;i<rows.length;i++)rows[i]=large.getJSONObject(i);
        rejected(()->book.consolidationSnapshot(scope(),refs(rows),20));assertEquals(0,book.listCandidates(scope()).length());
    }

    @Test public void correctionScrubsPendingDraftAndStaleModelResultCannotCreateCandidate()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("旧地址"),1),b=book.save(scope(),record("另一个事实"),2);JSONArray selection=refs(a,b);
        JSONObject c=book.proposeConsolidation(scope(),draft("不应保留的推断"),selection,3);
        book.save(scope(),new JSONObject(a.toString()).put("text","新地址"),4);
        JSONObject invalid=book.getCandidate(scope(),c.getString("id"));assertEquals("invalidated",invalid.getString("status"));assertFalse(invalid.has("text"));assertFalse(invalid.has("reason"));
        assertFalse(Files.readString(file().toPath()).contains("不应保留的推断"));
        rejected(()->book.proposeConsolidation(scope(),draft("迟到输出"),selection,5));rejected(()->book.acceptCandidate(scope(),c.getString("id"),1,5));
        assertEquals("invalidated",open().getCandidate(scope(),c.getString("id")).getString("status"));
    }

    @Test public void acceptedSummaryIsInvalidatedTransitivelyOnParentCorrection()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("旧偏好"),1),b=book.save(scope(),record("有效环境"),2);
        JSONObject c=book.proposeConsolidation(scope(),draft("已批准但应删除的结论"),refs(a,b),3),summary=book.acceptCandidate(scope(),c.getString("id"),1,4).getJSONObject("record");
        book.save(scope(),record("下游也应删除").put("sourceIds",new JSONArray().put(summary.getString("id"))),5);
        book.save(scope(),new JSONObject(a.toString()).put("text","纠正偏好"),6);
        assertEquals(2,book.list(scope()).length());assertFalse(Files.readString(file().toPath()).contains("应删除"));assertEquals("invalidated",open().getCandidate(scope(),c.getString("id")).getString("status"));
    }

    @Test public void coreIsExplicitAndMetadataChangeFencesPendingButPreservesAcceptedContent()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("主人身份只是认知").put("layer","preference"),1),b=book.save(scope(),record("环境"),2);
        assertEquals("semantic",book.get(scope(),a.getString("id")).getString("tier"));
        JSONObject c=book.proposeConsolidation(scope(),draft("接受的语义摘要"),refs(a,b),3);JSONObject summary=book.acceptCandidate(scope(),c.getString("id"),1,4).getJSONObject("record");
        JSONObject pending=book.proposeConsolidation(scope(),draft("另一待审"),refs(a,b),5);book.setCore(scope(),a.getString("id"),1,true,6);
        assertEquals("core",book.get(scope(),a.getString("id")).getString("tier"));assertTrue(book.get(scope(),summary.getString("id")).getBoolean("active"));
        assertEquals("accepted",open().getCandidate(scope(),c.getString("id")).getString("status"));assertEquals("invalidated",book.getCandidate(scope(),pending.getString("id")).getString("status"));
        assertFalse(book.contextSnapshot(scope(),"摘要",16,12000).getBoolean("executionAuthorized"));
    }

    @Test public void oldestAncestorExpiryBoundsSummaryAndExpiredCandidateCannotBeAccepted()throws Exception {
        MemoryBook book=open();long now=System.currentTimeMillis(),expiry=now+60000;
        JSONObject ancestor=book.save(scope(),record("时效源").put("expiresAt",expiry),now),a=book.save(scope(),record("未自行设到期的派生").put("sourceIds",new JSONArray().put(ancestor.getString("id"))),now),b=book.save(scope(),record("另源"),now);
        JSONObject c=book.proposeConsolidation(scope(),draft("限时结论"),refs(a,b),now);assertEquals(expiry,c.getLong("expiresAt"));
        rejected(()->open().acceptCandidate(scope(),c.getString("id"),1,expiry));
        JSONObject result=open().acceptCandidate(scope(),c.getString("id"),1,now).getJSONObject("record");assertEquals(expiry,result.getLong("expiresAt"));
    }

    @Test public void externalVersionsAreRecheckedBeforeProposalAndBeforeAcceptance()throws Exception {
        MemoryBook book=open();SourceRef source=new SourceRef("message","source",1,"owner","private");evidence.put("message:source",new SourceRef.State(source,false));
        JSONObject a=book.importFromSources(scope(),record("外部原文").put("sourceRefs",new JSONArray().put(source.toJson())),1),b=book.save(scope(),record("手工依据"),2);
        JSONObject c=book.proposeConsolidation(scope(),draft("来源推断"),refs(a,b),3);assertEquals(1,c.getJSONArray("sourceRefs").length());
        SourceRef newer=new SourceRef("message","source",2,"owner","private");evidence.put("message:source",new SourceRef.State(newer,false));
        rejected(()->book.proposeConsolidation(scope(),draft("迟到推断"),refs(a,b),4));rejected(()->book.acceptCandidate(scope(),c.getString("id"),1,4));
        book.sourceUpdated(scope(),newer);assertEquals(1,book.list(scope()).length());assertEquals("invalidated",open().getCandidate(scope(),c.getString("id")).getString("status"));
    }

    @Test public void forgettingManualSummaryCannotBeUndoneByReextractingSameParentsEvenAfterArchive()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("手工一"),1),b=book.save(scope(),record("手工二"),2);JSONArray selection=refs(a,b);JSONObject d=draft("遗忘的唯一摘要");
        JSONObject c=book.proposeConsolidation(scope(),d,selection,3),r=book.acceptCandidate(scope(),c.getString("id"),1,4).getJSONObject("record");book.forgetReviewed(scope(),r.getString("id"),1);
        MemoryBook reopened=open();rejected(()->reopened.proposeConsolidation(scope(),d,selection,5));JSONObject duplicate=reopened.getCandidate(scope(),c.getString("id"));assertEquals("invalidated",duplicate.getString("status"));assertFalse(duplicate.has("text"));
        reopened.archiveCandidates(scope(),refs(duplicate),6);rejected(()->open().proposeConsolidation(scope(),d,selection,7));
        assertEquals(0,open().listCandidateHistory(scope()).length());assertEquals(5,new JSONObject(Files.readString(file().toPath())).getInt("version"));assertFalse(Files.readString(file().toPath()).contains("遗忘的唯一摘要"));
    }

    @Test public void forgettingAlreadyArchivedManualSummaryRetainsOnlyDurableParentVersionBarriers()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("手工一"),1),b=book.save(scope(),record("手工二"),2),d=draft("已归档摘要");JSONArray selection=refs(a,b);
        JSONObject c=book.proposeConsolidation(scope(),d,selection,3),accepted=book.acceptCandidate(scope(),c.getString("id"),1,4),r=accepted.getJSONObject("record");
        book.archiveCandidates(scope(),refs(accepted.getJSONObject("candidate")),5);book.forgetReviewed(scope(),r.getString("id"),1);
        rejected(()->open().proposeConsolidation(scope(),d,selection,6));assertEquals(0,open().listCandidateHistory(scope()).length());assertFalse(Files.readString(file().toPath()).contains("已归档摘要"));
    }

    @Test public void rejectedManualConsolidationKeepsExactContentBarrierAndCanBeExplicitlyReconsidered()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("事实一"),1),b=book.save(scope(),record("事实二"),2),d=draft("未证实结论");JSONArray selection=refs(a,b);
        JSONObject c=book.proposeConsolidation(scope(),d,selection,3),edited=book.editCandidate(scope(),c.getString("id"),1,new JSONObject().put("text","修正后结论"),4);
        JSONObject rejected=book.rejectCandidate(scope(),c.getString("id"),edited.getLong("revision"),"证据不充分",5);book.archiveCandidates(scope(),refs(rejected),6);
        MemoryBook re=open();JSONObject marker=re.proposeConsolidation(scope(),d,selection,7);assertEquals("rejected",marker.getString("outcome"));
        rejected(()->re.reconsiderRejected(scope(),c.getString("id"),marker.getLong("revision"),d,8));
        JSONObject reopened=re.reconsiderRejected(scope(),c.getString("id"),marker.getLong("revision"),draft("修正后结论"),8);assertEquals("pending",reopened.getString("status"));
        assertEquals(c.getString("id"),re.proposeConsolidation(scope(),d,refs(b,a),9).getString("id"));
    }

    @Test public void atomicWriteFailuresRollBackCandidateFormatAcceptanceAndParentInvalidation()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("父一"),1),b=book.save(scope(),record("父二"),2);JSONArray selection=refs(a,b);byte[] before=Files.readAllBytes(file().toPath());File block=new File(file()+".new");assertTrue(block.mkdir());
        try{book.proposeConsolidation(scope(),draft("失败候选"),selection,3);fail();}catch(IOException expected){}
        assertEquals(0,book.listCandidates(scope()).length());assertArrayEquals(before,Files.readAllBytes(file().toPath()));assertTrue(block.delete());
        book.save(scope(),record("普通保存"),4);assertEquals(2,new JSONObject(Files.readString(file().toPath())).getInt("version"));
        JSONObject c=book.proposeConsolidation(scope(),draft("成功候选"),selection,5);assertTrue(block.mkdir());
        try{book.acceptCandidate(scope(),c.getString("id"),1,6);fail();}catch(IOException expected){}
        assertEquals("pending",book.getCandidate(scope(),c.getString("id")).getString("status"));assertEquals(3,book.list(scope()).length());assertTrue(block.delete());
        JSONObject summary=book.acceptCandidate(scope(),c.getString("id"),1,7).getJSONObject("record");assertTrue(block.mkdir());
        try{book.save(scope(),new JSONObject(a.toString()).put("text","父变化"),8);fail();}catch(IOException expected){}
        assertEquals("accepted",book.getCandidate(scope(),c.getString("id")).getString("status"));assertEquals(summary.getString("id"),book.get(scope(),summary.getString("id")).getString("id"));
        assertTrue(block.delete());assertEquals("accepted",open().getCandidate(scope(),c.getString("id")).getString("status"));
    }

    @Test public void existingRecordSaveNeverCoercesFractionalOrTruncatedRevision()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("当前事实"),1);
        for(Object wrong:new Object[]{1.0,1.25,"1",4294967297L})rejected(()->book.save(scope(),new JSONObject(a.toString()).put("revision",wrong).put("text","错误覆盖"),2));
        assertEquals("当前事实",book.get(scope(),a.getString("id")).getString("text"));
    }

    @Test public void concurrentCorrectionAndAcceptanceNeverLeaveAnActiveStaleSummary()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("旧事实"),1),b=book.save(scope(),record("另源"),2),c=book.proposeConsolidation(scope(),draft("过时的综合推断"),refs(a,b),3);
        CountDownLatch start=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<?> approve=workers.submit(()->{start.await();try{book.acceptCandidate(scope(),c.getString("id"),1,4);}catch(IllegalStateException stale){}return null;});
            Future<?> correct=workers.submit(()->{start.await();book.save(scope(),new JSONObject(a.toString()).put("text","纠正后的事实"),5);return null;});
            start.countDown();approve.get(5,TimeUnit.SECONDS);correct.get(5,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();}
        assertEquals(2,book.list(scope()).length());assertEquals(0,book.search(scope(),"过时的综合推断",10).length());assertEquals("invalidated",book.getCandidate(scope(),c.getString("id")).getString("status"));
        assertFalse(Files.readString(file().toPath()).contains("过时的综合推断"));assertEquals(2,open().list(scope()).length());
    }

    @Test public void malformedHierarchicalFormatFailsClosedWithoutOverwritingOriginalFile()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("父一"),1),b=book.save(scope(),record("父二"),2);book.proposeConsolidation(scope(),draft("待审"),refs(a,b),3);
        JSONObject saved=new JSONObject(Files.readString(file().toPath()));saved.put("learningVersion",1);saved.remove("candidateHistory");Files.writeString(file().toPath(),saved.toString());byte[] before=Files.readAllBytes(file().toPath());
        rejected(this::open);assertArrayEquals(before,Files.readAllBytes(file().toPath()));
    }

    @Test public void forgottenManualEvidenceCannotReturnThroughRewordingOrChangedSelection()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("证据甲"),1),b=book.save(scope(),record("证据乙"),2),other=book.save(scope(),record("额外证据"),3);
        JSONObject c=book.proposeConsolidation(scope(),draft("要忘记的推断"),refs(a,b),4),summary=book.acceptCandidate(scope(),c.getString("id"),1,5).getJSONObject("record");
        book.forgetReviewed(scope(),summary.getString("id"),1);
        rejected(()->open().proposeConsolidation(scope(),draft("换种说法复活推断"),refs(a,b),6));
        rejected(()->open().proposeConsolidation(scope(),draft("增加一条来源逃避遗忘"),refs(a,b,other),6));
        rejected(()->open().proposeConsolidation(scope(),draft("更换一个来源逃避遗忘"),refs(a,other),6));
        assertEquals(3,book.list(scope()).length());
    }

    @Test public void recordForgetBarriersFollowAncestorsAndCoreMetadataButExplicitCorrectionsMayCreateNewEvidence()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("原始甲"),1),b=book.save(scope(),record("原始乙"),2),other=book.save(scope(),record("独立事实"),3);
        JSONObject draft=book.proposeConsolidation(scope(),draft("待忘结论"),refs(a,b),4),summary=book.acceptCandidate(scope(),draft.getString("id"),1,5).getJSONObject("record");
        JSONObject sibling=book.save(scope(),record("不应保留的同源派生").put("sourceIds",new JSONArray().put(a.getString("id"))),6);
        JSONObject pending=book.proposeConsolidation(scope(),draft("同源待审改写"),refs(sibling,other),7);
        final JSONArray[] removed={null};book.forgetReviewed(scope(),summary.getString("id"),1,refs->removed[0]=refs);
        assertEquals(2,removed[0].length());assertEquals("invalidated",book.getCandidate(scope(),pending.getString("id")).getString("status"));assertFalse(Files.readString(file().toPath()).contains("不应保留的同源派生"));
        JSONObject core=book.setCore(scope(),a.getString("id"),1,true,8);assertTrue(book.get(scope(),a.getString("id")).getBoolean("consolidationBlocked"));
        rejected(()->open().proposeConsolidation(scope(),draft("换core逃避"),refs(core,other),9));
        JSONObject explicitDerived=book.save(scope(),record("机主手工保留的派生").put("sourceIds",new JSONArray().put(core.getString("id"))),9);
        rejected(()->open().proposeConsolidation(scope(),draft("通过后造派生逃避"),refs(explicitDerived,other),10));
        JSONObject corrected=book.save(scope(),new JSONObject(core.toString()).put("text","机主明确修订甲"),11);
        assertFalse(book.get(scope(),a.getString("id")).getBoolean("consolidationBlocked"));
        assertEquals("pending",open().proposeConsolidation(scope(),draft("采用新证据"),refs(corrected,other),12).getString("status"));
    }

    @Test public void failedForgetAndFailedCoreToggleDoNotLoseOrInventParentVersionBarriers()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("甲"),1),b=book.save(scope(),record("乙"),2),c=book.proposeConsolidation(scope(),draft("摘要"),refs(a,b),3),summary=book.acceptCandidate(scope(),c.getString("id"),1,4).getJSONObject("record");
        File blocked=new File(file()+".new");assertTrue(blocked.mkdir());try{book.forgetReviewed(scope(),summary.getString("id"),1);fail();}catch(IOException expected){}
        assertTrue(book.get(scope(),summary.getString("id")).getBoolean("active"));assertFalse(book.get(scope(),a.getString("id")).getBoolean("consolidationBlocked"));assertTrue(blocked.delete());
        book.forgetReviewed(scope(),summary.getString("id"),1);assertTrue(blocked.mkdir());try{book.setCore(scope(),a.getString("id"),1,true,5);fail();}catch(IOException expected){}
        assertFalse(book.get(scope(),a.getString("id")).getBoolean("core"));assertEquals(1,book.get(scope(),a.getString("id")).getLong("revision"));assertTrue(blocked.delete());
        rejected(()->open().proposeConsolidation(scope(),draft("改写摘要"),refs(a,b),6));
    }

    @Test public void legacyExplicitConsolidationUpgradesOnForgetAndDoesNotFenceAnotherSpace()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("私人事实甲"),1),b=book.save(scope(),record("私人事实乙"),2);
        JSONObject group=new JSONObject().put("ownerId","owner").put("spaceId","qq:group:1"),g1=book.save(group,record("群事实甲"),3),g2=book.save(group,record("群事实乙"),4);
        JSONObject summary=book.consolidate(scope(),record("旧入口手工摘要").put("sourceIds",new JSONArray().put(a.getString("id")).put(b.getString("id"))),5);
        assertEquals(2,new JSONObject(Files.readString(file().toPath())).getInt("version"));book.forgetReviewed(scope(),summary.getString("id"),1);
        MemoryBook reopened=open();assertEquals(5,new JSONObject(Files.readString(file().toPath())).getInt("version"));rejected(()->reopened.proposeConsolidation(scope(),draft("旧材料的改写"),refs(a,b),6));
        assertEquals("pending",reopened.proposeConsolidation(group,draft("群自己的知识"),refs(g1,g2),6).getString("status"));assertFalse(reopened.get(group,g1.getString("id")).getBoolean("consolidationBlocked"));
    }

    @Test public void ordinarySaveMetadataAndFormattingCannotReopenForgottenEvidence()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("Java 项目"),1),b=book.save(scope(),record("父乙"),2),other=book.save(scope(),record("独立事实"),3);
        JSONObject c=book.proposeConsolidation(scope(),draft("遗忘摘要"),refs(a,b),4),summary=book.acceptCandidate(scope(),c.getString("id"),1,5).getJSONObject("record");book.forgetReviewed(scope(),summary.getString("id"),1);
        JSONObject current=a;JSONObject[] changes={new JSONObject().put("core",true),new JSONObject().put("confidence",0.2),new JSONObject().put("expiresAt",System.currentTimeMillis()+60000),new JSONObject().put("source","只改标签"),new JSONObject().put("layer","preference"),new JSONObject().put("text","ＪＡＶＡ   项目")};
        for(JSONObject change:changes){
            JSONObject input=new JSONObject(current.toString());for(java.util.Iterator<String> keys=change.keys();keys.hasNext();){String key=keys.next();input.put(key,change.get(key));}
            current=book.save(scope(),input,6);MemoryBook reopened=open();assertTrue(reopened.get(scope(),a.getString("id")).getBoolean("consolidationBlocked"));
            JSONArray selected=refs(current,other);rejected(()->reopened.proposeConsolidation(scope(),draft("不得改写恢复"),selected,7));
        }
    }

    @Test public void failedOrdinaryMetadataSaveRollsBackAdvancedForgetFloor()throws Exception {
        MemoryBook book=open();JSONObject a=book.save(scope(),record("甲"),1),b=book.save(scope(),record("乙"),2),c=book.proposeConsolidation(scope(),draft("摘要"),refs(a,b),3),summary=book.acceptCandidate(scope(),c.getString("id"),1,4).getJSONObject("record");book.forgetReviewed(scope(),summary.getString("id"),1);
        byte[] before=Files.readAllBytes(file().toPath());File blocked=new File(file()+".new");assertTrue(blocked.mkdir());
        try{book.save(scope(),new JSONObject(a.toString()).put("core",true),5);fail();}catch(IOException expected){}
        assertFalse(book.get(scope(),a.getString("id")).getBoolean("core"));assertEquals(1,book.get(scope(),a.getString("id")).getLong("revision"));assertArrayEquals(before,Files.readAllBytes(file().toPath()));assertTrue(blocked.delete());
        assertTrue(open().get(scope(),a.getString("id")).getBoolean("consolidationBlocked"));
    }
}
