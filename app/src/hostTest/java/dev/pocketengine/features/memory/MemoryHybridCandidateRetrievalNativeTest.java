package dev.pocketengine.features.memory;

import java.io.File;
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

/** Actual native FTS and the already existing lexical channel, not semantic embeddings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryHybridCandidateRetrievalNativeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private File file()throws Exception{return new File(temp.newFolder(),"records.json");}
    private static JSONObject row(String text)throws Exception{return new JSONObject().put("text",text);}
    private static boolean contains(JSONArray rows,String id)throws Exception{for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return true;return false;}
    @Test public void nativeCandidateUnionDoesNotLoseCodeSymbolSubwordsAdmittedByTheOriginalLexicalGate()throws Exception {
        MemoryBook lexical=new MemoryBook(file());lexical.save(scope(),row("NativeWorkProfileHost 管理工作配置"),1);assertEquals(1,lexical.search(scope(),"work profile",16).length());
        for(boolean preferFts5:List.of(true,false))try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(preferFts5)){
            assertTrue(index.status().getString("backend").startsWith("sqlite_"));MemoryBook book=new MemoryBook(file(),null,index);JSONObject record=book.save(scope(),row("NativeWorkProfileHost 管理工作配置"),1);
            JSONArray hits=book.search(scope(),"work profile",16);assertEquals("whole-token FTS must not discard the complementary lexical candidate",1,hits.length());assertEquals(record.getString("id"),hits.getJSONObject(0).getString("id"));
        }
    }
    @Test public void literalSubstringAndMixedVersionQueriesRecallTheSameOriginalTextWithoutPretendingToBeSemantic()throws Exception {
        try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false)){
            MemoryBook book=new MemoryBook(file(),null,index);JSONObject record=book.save(scope(),row("已核对ProtocolDriverV27的重试回执"),1);
            for(String query:List.of("driver","V27","protocol driver")){JSONArray rows=book.search(scope(),query,16);assertEquals(query,1,rows.length());assertEquals(record.getString("id"),rows.getJSONObject(0).getString("id"));assertEquals(record.getString("text"),rows.getJSONObject(0).getString("text"));}
            assertEquals(0,book.search(scope(),"不存在的同义改述",16).length());assertFalse(index.status().getBoolean("semantic"));
        }
    }
    @Test public void fusionFiltersTheExactOwnerSpaceAndPreservesVersionAndResultLimit()throws Exception {
        try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false)){
            index.synchronize(List.of(new MemoryRetrievalBackend.Document("owner","private","allowed",7,"NativeWorkProfileHost",1,.5),new MemoryRetrievalBackend.Document("owner","qq:other","foreign-space",9,"work profile",99,1),new MemoryRetrievalBackend.Document("other","private","foreign-owner",11,"work profile",99,1)));
            List<MemoryRetrievalBackend.Match> hits=index.find("owner","private","work",1);assertEquals(1,hits.size());assertEquals("allowed",hits.get(0).id);assertEquals(7,hits.get(0).revision);
        }
    }
    @Test public void reciprocalRanksDeduplicateChannelsAndRetainNativeBm25AsTheTieBreaker()throws Exception {
        try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false)){
            index.synchronize(List.of(new MemoryRetrievalBackend.Document("owner","private","short",1,"coffee",1,1),new MemoryRetrievalBackend.Document("owner","private","long",1,"coffee "+"unrelated ".repeat(300),999,1),new MemoryRetrievalBackend.Document("owner","private","partial",1,"coffeemaker",1000,1)));
            List<MemoryRetrievalBackend.Match> all=index.find("owner","private","coffee",16);assertEquals(3,all.size());assertEquals(3,all.stream().map(hit->hit.id).distinct().count());assertEquals("short",all.get(0).id);assertEquals("short",index.find("owner","private","coffee",1).get(0).id);
            assertEquals("rrf_native_fts_lexical_v1",index.status().getString("candidateFusion"));assertEquals("native_channel_before_fusion",index.status().getString("rankScope"));
        }
    }
    @Test public void originalAudienceCurrentSourceForgetAndColdFencesApplyBeforeBothCandidateChannels()throws Exception {
        File path=file();SourceRef ref=new SourceRef("message","native-symbol",1,"owner","private");SourceRef.State[] source={new SourceRef.State(ref,false)};
        try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false)){
            MemoryBook book=new MemoryBook(path,(s,k,id)->source[0],index);JSONObject selected=book.save(scope(),row("NativeWorkProfileHost").put("sourceRefs",new JSONArray().put(ref.toJson())),1);book.save(scope(),row("work profile unselected private record").put("core",true),2);
            ReadAudience audience=new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "native-exact-one";}public long revision(){return 1;}public void checkCurrent(){}public JSONArray allowedSources(){return new JSONArray();}public JSONArray allowedRecords()throws Exception{return new JSONArray().put(new JSONObject().put("id",selected.getString("id")).put("revision",selected.getLong("revision")));}};
            JSONArray hits=book.search(scope(),"work profile",16,audience);assertEquals(1,hits.length());assertEquals(selected.getString("id"),hits.getJSONObject(0).getString("id"));assertEquals(1,index.status().getInt("indexedCount"));
            source[0]=new SourceRef.State(new SourceRef("message",ref.sourceId,2,"owner","private"),false);assertEquals(0,book.search(scope(),"work profile",16,audience).length());source[0]=new SourceRef.State(ref,false);book.forgetReviewed(scope(),selected.getString("id"),selected.getLong("revision"));assertFalse(contains(book.search(scope(),"work",16),selected.getString("id")));
        }
        try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false)){MemoryBook cold=new MemoryBook(path,(s,k,id)->source[0],index);assertEquals(0,cold.search(scope(),"NativeWorkProfileHost",16).length());}
    }
    @Test public void aBrokenFtsChannelStillSurfacesTheOriginalVisibleBackendFallback()throws Exception {
        try(MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false)){
            MemoryBook book=new MemoryBook(file(),null,index);JSONObject record=book.save(scope(),row("NativeWorkProfileHost"),1);assertEquals(1,book.search(scope(),"work",16).length());
            var field=SQLiteMemoryRetrievalBackend.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(index)).execSQL("DROP TABLE memory_fts");
            JSONArray rows=book.search(scope(),"work",16);assertEquals(record.getString("id"),rows.getJSONObject(0).getString("id"));assertEquals("lexical_cjk_pairs",book.retrievalStatus().getString("backend"));assertTrue(book.retrievalStatus().getString("fallbackReason").startsWith("backend_failure_"));
        }
    }
}
