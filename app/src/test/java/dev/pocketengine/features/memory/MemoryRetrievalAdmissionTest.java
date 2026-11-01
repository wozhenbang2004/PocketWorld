package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemoryRetrievalAdmissionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject row(String text)throws Exception{return new JSONObject().put("text",text);}
    private File file(){return new File(temp.getRoot(),"records.json");}
    private interface Action{void run()throws Exception;}
    private static class Backend implements MemoryRetrievalBackend {
        List<Document> documents=new ArrayList<>();List<Match> hits=new ArrayList<>();int invalidations;boolean failFind,failInvalidate;Action duringFind;
        public void synchronize(List<Document> rows){documents=new ArrayList<>(rows);}
        public List<Match> find(String owner,String space,String query,int limit)throws Exception{if(duringFind!=null)duringFind.run();if(failFind)throw new IOException("unavailable");return hits;}
        public void invalidate()throws Exception{invalidations++;documents.clear();if(failInvalidate)throw new IOException("clear failed");}
        public JSONObject status()throws Exception{return new JSONObject().put("backend","test_index");}
        public void close(){documents.clear();}
    }
    @Test public void indexCannotAdmitForeignPendingMissingExpiredOrWrongRevisionRecords()throws Exception {
        Backend backend=new Backend();MemoryBook book=new MemoryBook(file(),null,backend);JSONObject own=book.save(scope(),row("咖啡正确记录"),1),foreignScope=new JSONObject().put("ownerId","owner").put("spaceId","qq:group:1"),foreign=book.save(foreignScope,row("咖啡群资料"),2),expired=book.save(scope(),row("咖啡过期记录").put("expiresAt",1),3);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(foreign.getString("id"),1),new MemoryRetrievalBackend.Match(own.getString("id"),999),new MemoryRetrievalBackend.Match(expired.getString("id"),1),new MemoryRetrievalBackend.Match("missing",1));
        assertEquals(0,book.search(scope(),"咖啡",10).length());assertEquals(1,backend.documents.size());assertEquals(own.getString("id"),backend.documents.get(0).id);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(own.getString("id"),1),new MemoryRetrievalBackend.Match(own.getString("id"),1));assertEquals(1,book.search(scope(),"咖啡",10).length());
        assertEquals(0,book.search(scope(),"索引伪造命中",10).length());
    }
    @Test public void externalSourceAdvanceWithoutBridgeStopsSearchRecallAndCoreContext()throws Exception {
        SourceRef one=new SourceRef("message","m",1,"owner","private"),two=new SourceRef("message","m",2,"owner","private");SourceRef.State[] actual={new SourceRef.State(one,false)};
        MemoryBook book=new MemoryBook(file(),(s,k,id)->actual[0]);JSONObject saved=book.importFromSources(scope(),row("核心咖啡旧事实").put("sourceRefs",new JSONArray().put(one.toJson())),1);book.setCore(scope(),saved.getString("id"),1,true,2);
        assertEquals(1,book.search(scope(),"咖啡",10).length());actual[0]=new SourceRef.State(two,false);
        assertEquals(0,book.search(scope(),"咖啡",10).length());assertEquals("",book.recall(scope(),"咖啡",10).getString("context"));assertEquals("",book.contextSnapshot(scope(),"",16,12000).getString("context"));
    }
    @Test public void liveSourceIsCheckedAgainAfterIndexReturns()throws Exception {
        Backend backend=new Backend();SourceRef one=new SourceRef("message","m",1,"owner","private");SourceRef.State[] actual={new SourceRef.State(one,false)};
        MemoryBook book=new MemoryBook(file(),(s,k,id)->actual[0],backend);JSONObject saved=book.importFromSources(scope(),row("咖啡源事实").put("sourceRefs",new JSONArray().put(one.toJson())),1);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(saved.getString("id"),1));backend.duringFind=()->actual[0]=new SourceRef.State(one,true);
        assertEquals(0,book.search(scope(),"咖啡",10).length());
    }
    @Test public void coreCapturedBeforeBackendCallCannotReturnAnOldRevision()throws Exception {
        Backend backend=new Backend();MemoryBook book=new MemoryBook(file(),null,backend);JSONObject core=book.save(scope(),row("旧咖啡秘密").put("core",true),1);
        backend.hits=List.of(new MemoryRetrievalBackend.Match(core.getString("id"),1));backend.duringFind=()->{backend.duringFind=null;book.save(scope(),new JSONObject(core.toString()).put("text","新茶偏好"),2);};
        String context=book.contextSnapshot(scope(),"咖啡",16,12000).getString("context");assertFalse(context.contains("旧咖啡秘密"));assertTrue(book.contextSnapshot(scope(),"",16,12000).getString("context").contains("新茶偏好"));
    }
    @Test public void failedIndexAndFailedInvalidationNeverRollBackOrResurrectAuthoritativeForget()throws Exception {
        Backend backend=new Backend();MemoryBook book=new MemoryBook(file(),null,backend);JSONObject saved=book.save(scope(),row("咖啡秘密"),1);backend.failFind=true;
        assertEquals(1,book.search(scope(),"咖啡",10).length());assertEquals("lexical_cjk_pairs",book.retrievalStatus().getString("backend"));assertTrue(book.retrievalStatus().getString("fallbackReason").contains("IOException"));
        Backend broken=new Backend();MemoryBook another=new MemoryBook(new File(temp.getRoot(),"other.json"),null,broken);JSONObject old=another.save(scope(),row("忘记的咖啡秘密"),1);broken.failInvalidate=true;another.forgetReviewed(scope(),old.getString("id"),1);
        assertEquals(0,another.search(scope(),"咖啡",10).length());assertEquals(0,new MemoryBook(new File(temp.getRoot(),"other.json")).list(scope()).length());
    }
    @Test public void onlyFormalAdmissionReachesIndexAndARejectedHypothesisNeverDoes()throws Exception {
        Backend backend=new Backend();SourceRef source=new SourceRef("message","m",1,"owner","private");MemoryBook book=new MemoryBook(file(),(s,k,id)->new SourceRef.State(source,false),backend);
        JSONObject c=book.proposeLearning(scope(),row("候选独有咖啡").put("layer","fact").put("reason","待审"),1,source);backend.hits=List.of(new MemoryRetrievalBackend.Match(c.getString("id"),1));
        assertEquals(0,book.search(scope(),"咖啡",10).length());assertEquals(0,backend.documents.size());book.rejectCandidate(scope(),c.getString("id"),1,"非事实",2);assertEquals(0,book.search(scope(),"咖啡",10).length());
        JSONObject c2=book.proposeLearning(scope(),row("已接受咖啡").put("layer","fact").put("reason","待审"),3,source),formal=book.acceptCandidate(scope(),c2.getString("id"),1,4).getJSONObject("record");backend.hits=List.of(new MemoryRetrievalBackend.Match(formal.getString("id"),1));assertEquals(1,book.search(scope(),"咖啡",10).length());
    }
}
