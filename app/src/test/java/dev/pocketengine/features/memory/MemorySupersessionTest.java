package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemorySupersessionTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final Map<String,SourceRef.State> evidence=new HashMap<>();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private File file(){return new File(temp.getRoot(),"records.json");}
    private MemoryBook open()throws Exception{return new MemoryBook(file(),(s,k,id)->evidence.get(k+":"+id));}
    private JSONObject record(String text)throws Exception{return new JSONObject().put("text",text);}
    private JSONArray refs(JSONObject... rows)throws Exception{
        JSONArray refs=new JSONArray();for(JSONObject row:rows)refs.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));return refs;
    }
    private JSONObject draft(String text)throws Exception{return record(text).put("layer","fact").put("reason","审阅前的推断");}
    private JSONObject replace(MemoryBook book,JSONObject kept,JSONObject old,MemoryBook.BeforeForget receipt)throws Exception{
        return book.supersedeReviewed(scope(),kept.getString("id"),kept.getLong("revision"),old.getString("id"),old.getLong("revision"),receipt,100);
    }
    private interface Checked {void run()throws Exception;}
    private void rejected(Checked action)throws Exception{try{action.run();fail("unsafe replacement admitted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private void ioFailed(Checked action)throws Exception{try{action.run();fail("write unexpectedly succeeded");}catch(IOException expected){}}

    @Test public void explicitReplacementRemovesConflictingCoreAndDescendantsWithExactReceiptAndRestart()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),record("过时地址甲").put("core",true),1),kept=book.save(scope(),record("正确地址乙").put("core",true),2);
        JSONObject other=book.save(scope(),record("无关偏好"),3);
        JSONObject child=book.save(scope(),record("含旧地址的摘要").put("sourceIds",new JSONArray().put(old.getString("id"))),4);
        book.save(scope(),record("孙摘要").put("sourceIds",new JSONArray().put(child.getString("id"))),5);
        JSONObject pending=book.proposeConsolidation(scope(),draft("旧事实待归纳"),refs(old,other),6);
        assertEquals(1,book.search(scope(),"过时",10).length());final JSONArray[] receipt={null};
        JSONObject result=replace(book,kept,old,r->receipt[0]=r),current=result.getJSONObject("record");
        assertEquals(3,result.getInt("deleted"));assertEquals(3,receipt[0].length());assertFalse(result.getBoolean("executionAuthorized"));
        assertEquals(2,current.getLong("revision"));assertTrue(current.getBoolean("core"));assertEquals("正确地址乙",current.getString("text"));
        assertEquals(old.getString("id"),current.getJSONArray("supersedes").getJSONObject(0).getString("id"));
        assertEquals(1,current.getJSONArray("supersedes").getJSONObject(0).getLong("revision"));
        assertEquals("invalidated",book.getCandidate(scope(),pending.getString("id")).getString("status"));
        assertEquals(0,book.search(scope(),"过时",10).length());assertEquals(1,book.contextSnapshot(scope(),"",16,12000).getInt("coreAvailable"));
        String bytes=Files.readString(file().toPath());assertFalse(bytes.contains("含旧地址"));assertFalse(bytes.contains("过时地址甲"));assertFalse(bytes.contains("旧事实待归纳"));
        assertEquals(6,new JSONObject(bytes).getInt("version"));assertEquals(2,open().list(scope()).length());
        assertEquals(current.getJSONArray("supersedes").toString(),open().get(scope(),kept.getString("id")).getJSONArray("supersedes").toString());
        rejected(()->replace(book,kept,old,r->fail("stale replay must not record again")));
    }

    @Test public void staleForeignSelfMissingReceiptAndExpiredKeeperNeverChangeBook()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),record("旧事实"),1),kept=book.save(scope(),record("新事实"),2),expired=book.save(scope(),record("过期事实").put("expiresAt",50),3);
        String before=Files.readString(file().toPath());int[] calls={0};MemoryBook.BeforeForget receipt=r->calls[0]++;
        rejected(()->book.supersedeReviewed(scope(),kept.getString("id"),2,old.getString("id"),1,receipt,100));
        rejected(()->book.supersedeReviewed(scope(),kept.getString("id"),1,old.getString("id"),2,receipt,100));
        rejected(()->book.supersedeReviewed(new JSONObject().put("ownerId","other").put("spaceId","private"),kept.getString("id"),1,old.getString("id"),1,receipt,100));
        rejected(()->book.supersedeReviewed(null,kept.getString("id"),1,old.getString("id"),1,receipt,100));
        rejected(()->replace(book,old,old,receipt));rejected(()->replace(book,kept,old,null));rejected(()->replace(book,expired,old,receipt));
        assertEquals(0,calls[0]);assertEquals(before,Files.readString(file().toPath()));
    }

    @Test public void keeperCannotDependOnForgottenParentOrSharedExternalEvidence()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),record("原始依据"),1),child=book.save(scope(),record("仍依赖旧依据").put("sourceIds",new JSONArray().put(old.getString("id"))),2);
        rejected(()->replace(book,child,old,r->fail("dependent keeper cannot be reviewed")));
        SourceRef source=new SourceRef("message","same-source",1,"owner","private");evidence.put("message:same-source",new SourceRef.State(source,false));
        JSONObject a=book.importFromSources(scope(),record("同源旧事实").put("sourceRefs",new JSONArray().put(source.toJson())),3);
        JSONObject b=book.importFromSources(scope(),record("同源新推断").put("sourceRefs",new JSONArray().put(source.toJson())),4);
        rejected(()->replace(book,b,a,r->fail("shared-evidence keeper cannot be reviewed")));assertEquals(4,open().list(scope()).length());
    }

    @Test public void sourceBackedOldEvidenceCannotReappearOrLateCandidateBeAccepted()throws Exception{
        MemoryBook book=open();SourceRef source=new SourceRef("message","wrong-message",4,"owner","private");evidence.put("message:wrong-message",new SourceRef.State(source,false));
        JSONObject old=book.importFromSources(scope(),record("来源错误事实").put("sourceRefs",new JSONArray().put(source.toJson())),1),kept=book.save(scope(),record("机主纠正事实"),2);
        JSONObject sibling=book.importFromSources(scope(),record("相同错误来源另摘要").put("sourceRefs",new JSONArray().put(source.toJson())),3);
        JSONObject candidate=book.proposeLearning(scope(),draft("迟到错误推断"),4,source);final Set<String> removed=new HashSet<>();
        replace(book,kept,old,r->{for(int i=0;i<r.length();i++)removed.add(r.getJSONObject(i).getString("recordId"));});
        assertTrue(removed.contains(old.getString("id")));assertTrue(removed.contains(sibling.getString("id")));assertTrue(open().isSourceForgotten(scope(),source));
        rejected(()->open().importFromSources(scope(),record("旧材料改写").put("sourceRefs",new JSONArray().put(source.toJson())),110));
        rejected(()->open().acceptCandidate(scope(),candidate.getString("id"),1,110));assertEquals(0,open().search(scope(),"错误",10).length());
    }

    @Test public void receiptAndAtomicWriteFailuresLeaveOriginalFactsCandidatesAndBarriersIntact()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),record("保留到提交的旧文"),1),kept=book.save(scope(),record("新事实"),2);
        JSONObject pending=book.proposeConsolidation(scope(),draft("未提交前仍待审"),refs(old,kept),3);String before=Files.readString(file().toPath());
        ioFailed(()->replace(book,kept,old,r->{throw new IOException("receipt unavailable");}));assertEquals(before,Files.readString(file().toPath()));
        File blocked=new File(file().getPath()+".new");assertTrue(blocked.mkdir());int[] calls={0};
        ioFailed(()->replace(book,kept,old,r->calls[0]++));assertEquals(1,calls[0]);assertEquals(before,Files.readString(file().toPath()));
        assertEquals(1,book.get(scope(),kept.getString("id")).getLong("revision"));assertEquals("pending",book.getCandidate(scope(),pending.getString("id")).getString("status"));
        assertEquals(2,book.list(scope()).length());assertTrue(blocked.delete());assertEquals(2,replace(book,kept,old,r->{}).getJSONObject("record").getLong("revision"));
    }

    @Test public void metadataLinksCannotReopenForgottenManualParentsOrBeForgedBySave()throws Exception{
        MemoryBook book=open();JSONObject kept=book.save(scope(),record("保留的手工依据"),1),other=book.save(scope(),record("另一个依据"),2);
        JSONObject summary=book.acceptCandidate(scope(),book.proposeConsolidation(scope(),draft("先前被忘的摘要"),refs(kept,other),3).getString("id"),1,4).getJSONObject("record");
        book.forgetReviewed(scope(),summary.getString("id"),1);assertTrue(book.get(scope(),kept.getString("id")).getBoolean("consolidationBlocked"));
        JSONObject wrong=book.save(scope(),record("独立冲突事实"),5),current=replace(book,kept,wrong,r->{}).getJSONObject("record");
        assertTrue(current.getBoolean("consolidationBlocked"));MemoryBook re=open();assertTrue(re.get(scope(),kept.getString("id")).getBoolean("consolidationBlocked"));
        rejected(()->re.proposeConsolidation(scope(),draft("改写旧摘要"),refs(current,other),110));
        JSONObject edited=re.save(scope(),new JSONObject(current.toString()).put("confidence",0.4),111);
        assertEquals(current.getJSONArray("supersedes").toString(),edited.getJSONArray("supersedes").toString());assertTrue(open().get(scope(),kept.getString("id")).getBoolean("consolidationBlocked"));
        rejected(()->re.save(scope(),record("伪造替代").put("supersedes",refs(other)).put("supersededAt",1),112));
        rejected(()->re.save(scope(),new JSONObject(edited.toString()).put("supersedes",refs(other)),112));
    }

    @Test public void supersessionChainIsBoundedReadableAndMalformedRestoreDoesNotOverwrite()throws Exception{
        MemoryBook book=open();JSONObject a=book.save(scope(),record("第一旧事实"),1),b=book.save(scope(),record("第二事实"),2),c=book.save(scope(),record("最新事实"),3);
        b=replace(book,b,a,r->{}).getJSONObject("record");JSONObject current=replace(book,c,b,r->{}).getJSONObject("record");
        assertEquals(2,current.getJSONArray("supersedes").length());assertFalse(Files.readString(file().toPath()).contains("第一旧事实"));
        String valid=Files.readString(file().toPath());JSONObject bad=new JSONObject(valid);bad.getJSONArray("records").getJSONObject(0).getJSONArray("supersedes").getJSONObject(0).put("text","old secret");
        Files.writeString(file().toPath(),bad.toString());String before=Files.readString(file().toPath());rejected(()->open());assertEquals(before,Files.readString(file().toPath()));
        Files.writeString(file().toPath(),valid);assertEquals(1,open().list(scope()).length());
    }

    @Test public void keeperLiveSourceAndReentrantRevisionAreRecheckedBeforeMutation()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),record("待替代事实"),1);
        SourceRef source=new SourceRef("message","keeper",1,"owner","private");evidence.put("message:keeper",new SourceRef.State(source,false));
        JSONObject kept=book.importFromSources(scope(),record("有时效来源的新事实").put("sourceRefs",new JSONArray().put(source.toJson())),2);
        SourceRef newer=new SourceRef("message","keeper",2,"owner","private");evidence.put("message:keeper",new SourceRef.State(newer,false));
        rejected(()->replace(book,kept,old,r->fail("stale source must not reach receipt")));evidence.put("message:keeper",new SourceRef.State(source,false));
        String before=Files.readString(file().toPath());
        rejected(()->replace(book,kept,old,r->evidence.put("message:keeper",new SourceRef.State(newer,false))));assertEquals(before,Files.readString(file().toPath()));
        evidence.put("message:keeper",new SourceRef.State(source,false));
        rejected(()->replace(book,kept,old,r->book.save(scope(),new JSONObject(old.toString()).put("text","回执期间显式修改"),20)));
        assertEquals("回执期间显式修改",book.get(scope(),old.getString("id")).getString("text"));assertFalse(book.get(scope(),kept.getString("id")).has("supersedes"));
    }

    @Test public void fullLinkHistoryRejectsGrowthWithoutEvictionAndOlderFormatCannotHideLinks()throws Exception{
        MemoryBook book=open();JSONObject old=book.save(scope(),record("旧记录"),1),kept=book.save(scope(),record("保留记录"),2);
        JSONObject state=new JSONObject(Files.readString(file().toPath()));JSONArray links=new JSONArray();
        for(int i=0;i<MemoryBook.MAX_SUPERSESSION_REFS;i++)links.put(new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("revision",1));
        for(int i=0;i<state.getJSONArray("records").length();i++){
            JSONObject row=state.getJSONArray("records").getJSONObject(i);if(row.getString("id").equals(kept.getString("id")))row.put("supersedes",links).put("supersededAt",10);
        }
        state.put("version",6).put("recordForgetBarriers",new JSONArray()).put("learningVersion",2).put("learningCandidates",new JSONArray()).put("candidateHistory",new JSONArray());
        Files.writeString(file().toPath(),state.toString());String before=Files.readString(file().toPath());MemoryBook full=open();
        rejected(()->replace(full,kept,old,r->fail("capacity must fail before receipt")));assertEquals(before,Files.readString(file().toPath()));
        assertEquals(MemoryBook.MAX_SUPERSESSION_REFS,open().get(scope(),kept.getString("id")).getJSONArray("supersedes").length());
        state.put("version",5);Files.writeString(file().toPath(),state.toString());String legacy=Files.readString(file().toPath());rejected(()->open());assertEquals(legacy,Files.readString(file().toPath()));
    }
}
