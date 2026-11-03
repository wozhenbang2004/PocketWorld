package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original Book and real native SQLite, not semantic embeddings or a second fact index.
 * Typed fields are already reviewed formal data; every hit still crosses Book currentness. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryAssertionRetrievalNativeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject assertion(String subject,String type,String kind,String object,long time)throws Exception {
        return new JSONObject().put("version",1).put("kind",kind).put("subjectId",subject).put("subjectType",type).put("subjectLabel","owner".equals(subject)?"主人":"同名小王")
                .put("predicate","居住城市").put("object",object).put("condition","工作日").put("eventTime",time).put("evidenceSpans",new JSONArray());
    }
    private static JSONObject row(JSONObject assertion)throws Exception{return new JSONObject().put("layer","fact").put("text","保留的原句").put("assertion",assertion);}
    private File file()throws Exception{return new File(temp.newFolder(),"records.json");}
    private static void hit(MemoryBook book,String query,String id)throws Exception {JSONArray rows=book.search(scope(),query,16);assertEquals(query,1,rows.length());assertEquals(id,rows.getJSONObject(0).getString("id"));assertEquals("保留的原句",rows.getJSONObject(0).getString("text"));}
    @Test public void bothExistingBackendsRecallReviewedSubjectPredicateObjectAndConditionWithoutChangingOriginalText()throws Exception {
        for(MemoryRetrievalBackend index:List.of(new LexicalMemoryRetrievalBackend(),SQLiteMemoryRetrievalBackend.create(false)))try{
            File file=file();MemoryBook book=new MemoryBook(file,null,index);JSONObject saved=book.save(scope(),row(assertion("owner","owner","inferred","B城",100)),1);String id=saved.getString("id");
            for(String query:List.of("主人","居住城市","B城","工作日"))hit(book,query,id);
            JSONObject snapshot=book.contextSnapshot(scope(),"居住城市",16,12000);assertEquals(id,snapshot.getJSONArray("records").getJSONObject(0).getString("id"));assertTrue(snapshot.getString("context").contains("居住城市"));assertFalse(snapshot.getBoolean("executionAuthorized"));
            assertEquals("保留的原句",new MemoryBook(file).get(scope(),id).getString("text"));assertFalse(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("retrievalText"));
        }finally{index.close();}
    }
    @Test public void predicateMatchCannotPromoteHistoryImaginedPendingConflictsOrAnotherScope()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(file(),null,index);book.save(scope(),row(assertion("owner","owner","inferred","A城",100)),1);JSONObject current=book.save(scope(),row(assertion("owner","owner","inferred","B城",200)),2);
            book.save(scope(),row(assertion("person:fiction","person","imagined","虚构城",300)).put("core",true),3);JSONObject group=new JSONObject().put("ownerId","owner").put("spaceId","group:other");book.save(group,row(assertion("owner","owner","inferred","私域之外",300)),4);
            hit(book,"居住城市",current.getString("id"));assertEquals(1,index.status().getInt("indexedCount"));
            book.save(scope(),row(assertion("owner","owner","told","C城",200)),5);assertEquals(0,book.search(scope(),"居住城市",16).length());assertEquals(0,index.status().getInt("indexedCount"));
        }finally{index.close();}
    }
    @Test public void pendingTypedCandidateIsNotIndexedUntilOriginalNativeAcceptance()throws Exception {
        SourceRef source=new SourceRef("message","native-current",1,"owner","private");MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(file(),(identity,kind,id)->new SourceRef.State(source,false),index);JSONObject candidate=book.proposeLearning(scope(),row(assertion("owner","owner","inferred","B城",100)).put("reason","原来源仍须主人审核"),1,source);
            assertEquals(0,book.search(scope(),"居住城市",16).length());assertEquals(0,index.status().getInt("indexedCount"));JSONObject formal=book.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"),2).getJSONObject("record");hit(book,"居住城市",formal.getString("id"));
        }finally{index.close();}
    }
    @Test public void sourceAdvanceWithdrawsTypedTokensWithoutWaitingForBridgeOrIndexInvalidation()throws Exception {
        SourceRef source=new SourceRef("message","native-current",1,"owner","private");SourceRef.State[] current={new SourceRef.State(source,false)};MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(file(),(identity,kind,id)->current[0],index);JSONObject formal=book.save(scope(),row(assertion("owner","owner","inferred","B城",100)).put("sourceRefs",new JSONArray().put(source.toJson())),1);hit(book,"居住城市",formal.getString("id"));
            current[0]=new SourceRef.State(new SourceRef("message",source.sourceId,2,"owner","private"),false);assertEquals(0,book.search(scope(),"居住城市",16).length());assertEquals(0,book.contextSnapshot(scope(),"工作日",16,12000).getJSONArray("records").length());assertEquals(0,index.status().getInt("indexedCount"));
        }finally{index.close();}
    }
    @Test public void sameNamePeopleKeepDistinctIdentitiesAndNeverBecomeOwnerProfile()throws Exception {
        MemoryBook book=new MemoryBook(file());JSONObject one=book.save(scope(),row(assertion("person:one","person","told","甲城",100)),1),two=book.save(scope(),row(assertion("person:two","person","told","乙城",100)),2);
        JSONArray hits=book.search(scope(),"同名小王",16);assertEquals(2,hits.length());assertEquals(java.util.Set.of(one.getString("id"),two.getString("id")),java.util.Set.of(hits.getJSONObject(0).getString("id"),hits.getJSONObject(1).getString("id")));
        assertEquals(0,book.cognitionProjection(scope(),"owner",200).getJSONArray("current").length());assertEquals("甲城",book.cognitionProjection(scope(),"person:one",200).getJSONArray("current").getJSONObject(0).getJSONObject("assertion").getString("object"));
    }
    @Test public void exactNativeAudienceStillFiltersBeforeTypedFieldIndexingAndChecksRevocation()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(file(),null,index);JSONObject selected=book.save(scope(),row(assertion("person:selected","person","told","甲城",100)),1);book.save(scope(),row(assertion("person:private","person","told","乙城",100)).put("core",true),2);boolean[] current={true};
            ReadAudience audience=new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "native-selected-only";}public long revision(){return 1;}public JSONArray allowedRecords()throws Exception{return new JSONArray().put(new JSONObject().put("id",selected.getString("id")).put("revision",selected.getLong("revision")));}public JSONArray allowedSources(){return new JSONArray();}public void checkCurrent(){if(!current[0])throw new SecurityException("native audience withdrawn");}};
            JSONArray hits=book.search(scope(),"居住城市",16,audience);assertEquals(1,hits.length());assertEquals(selected.getString("id"),hits.getJSONObject(0).getString("id"));assertEquals(1,index.status().getInt("indexedCount"));current[0]=false;assertThrows(SecurityException.class,()->book.search(scope(),"同名小王",16,audience));
        }finally{index.close();}
    }
    @Test public void forgetAndColdRebuildNeverResurrectTypedCurrentOrOlderFacts()throws Exception {
        File file=file();MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(file,null,index);book.save(scope(),row(assertion("owner","owner","inferred","A城",100)),1);JSONObject current=book.save(scope(),row(assertion("owner","owner","inferred","B城",200)),2);hit(book,"居住城市",current.getString("id"));book.forgetReviewed(scope(),current.getString("id"),current.getLong("revision"));assertEquals(0,book.search(scope(),"居住城市",16).length());
        }finally{index.close();}
        MemoryRetrievalBackend coldIndex=SQLiteMemoryRetrievalBackend.create(false);try{MemoryBook cold=new MemoryBook(file,null,coldIndex);assertEquals(0,cold.search(scope(),"工作日",16).length());assertEquals(0,coldIndex.status().getInt("indexedCount"));}finally{coldIndex.close();}
    }
    @Test public void failedNativeFtsFallsBackWithTypedFieldsButNeverReturnsAnIndexedBody()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(file(),null,index);JSONObject formal=book.save(scope(),row(assertion("owner","owner","inferred","B城",100)),1);hit(book,"居住城市",formal.getString("id"));
            var field=SQLiteMemoryRetrievalBackend.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(index)).execSQL("DROP TABLE memory_fts");
            hit(book,"工作日",formal.getString("id"));assertEquals("lexical_cjk_pairs",book.retrievalStatus().getString("backend"));assertTrue(book.retrievalStatus().getString("fallbackReason").startsWith("backend_failure_"));
        }finally{index.close();}
    }
}
