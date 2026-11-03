package dev.pocketengine.features.memory;

import java.io.File;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SQLiteMemoryRetrievalBackendTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject row(String text)throws Exception{return new JSONObject().put("text",text);}
    @Test public void actualNativeFtsProbeIndexesCjkAndKeepsScopeLocal()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create();System.out.println("ACTUAL_NATIVE_SQLITE="+index.status());assertTrue(index.status().getString("backend").startsWith("sqlite_"));
        try{
            MemoryBook book=new MemoryBook(new File(temp.getRoot(),"records.json"),null,index);JSONObject a=book.save(scope(),row("我喜欢无糖咖啡"),1);JSONObject group=new JSONObject().put("ownerId","owner").put("spaceId","qq:group:1");book.save(group,row("群友加糖咖啡"),2);
            assertEquals(a.getString("id"),book.search(scope(),"咖啡",10).getJSONObject(0).getString("id"));assertEquals(1,index.status().getInt("indexedCount"));assertEquals(1,book.search(scope(),"糖",10).length());
            assertEquals(1,book.search(group,"咖啡",10).length());assertEquals(1,index.status().getInt("indexedCount"));assertFalse(index.status().getBoolean("persisted"));
        }finally{index.close();}
    }
    @Test public void fts4PolicyActuallyRunsAndReportsMatchinfoRankingWithoutClaimingFts5()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            assertEquals("sqlite_fts4",index.status().getString("backend"));assertFalse(index.status().getBoolean("fts5Probed"));
            assertEquals("bm25_from_matchinfo",index.status().getString("rank"));
            index.synchronize(List.of(new MemoryRetrievalBackend.Document("owner","private","id",1,"Java Android",1,1)));
            assertEquals(1,index.find("owner","private","java",10).size());assertEquals(0,index.find("owner","private","\" OR 1=1 --",10).size());
        }finally{index.close();}
    }
    @Test public void authoritativeWritesClearThenRebuildIndexAndForgetNeverReturnsOldTokens()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create();try{
            MemoryBook book=new MemoryBook(new File(temp.getRoot(),"records.json"),null,index);JSONObject a=book.save(scope(),row("旧咖啡地址"),1);assertEquals(1,book.search(scope(),"咖啡",10).length());
            JSONObject updated=book.save(scope(),new JSONObject(a.toString()).put("text","新茶地址"),2);assertEquals(0,index.status().getInt("indexedCount"));assertEquals(0,book.search(scope(),"咖啡",10).length());assertEquals(1,book.search(scope(),"茶",10).length());
            book.forgetReviewed(scope(),updated.getString("id"),updated.getLong("revision"));assertEquals(0,index.status().getInt("indexedCount"));assertEquals(0,book.search(scope(),"茶",10).length());
            MemoryRetrievalBackend cold=SQLiteMemoryRetrievalBackend.create();try{
                assertEquals(0,new MemoryBook(new File(temp.getRoot(),"records.json"),null,cold).search(scope(),"茶",10).length());
            }finally{cold.close();}
        }finally{index.close();}
    }
    @Test public void nativeFts4Bm25LengthNormalizationOutranksARecentLongDocumentBeforeLimit()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            index.synchronize(List.of(new MemoryRetrievalBackend.Document("owner","private","short",1,"coffee",1,1),new MemoryRetrievalBackend.Document("owner","private","long",1,"coffee "+"unrelated ".repeat(300),999,1)));
            assertEquals("short",index.find("owner","private","coffee",1).get(0).id);
        }finally{index.close();}
    }
    @Test public void deletedIndexRowsRebuildAndDroppedIndexCannotResurrectForgottenText()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(new File(temp.getRoot(),"records.json"),null,index);JSONObject a=book.save(scope(),row("咖啡地址"),1);assertEquals(1,book.search(scope(),"咖啡",10).length());
            java.lang.reflect.Field field=SQLiteMemoryRetrievalBackend.class.getDeclaredField("db");field.setAccessible(true);android.database.sqlite.SQLiteDatabase database=(android.database.sqlite.SQLiteDatabase)field.get(index);
            database.execSQL("DELETE FROM memory_fts");assertEquals(1,book.search(scope(),"咖啡",10).length());database.execSQL("DROP TABLE memory_fts");book.forgetReviewed(scope(),a.getString("id"),1);
            assertEquals(0,book.search(scope(),"咖啡",10).length());assertEquals("lexical_cjk_pairs",book.retrievalStatus().getString("backend"));assertTrue(book.retrievalStatus().getString("fallbackReason").startsWith("backend_failure_"));
        }finally{index.close();}
    }
    @Test public void latinRunsInsideChineseTextRemainSearchableWithoutSpaces()throws Exception {
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);try{
            MemoryBook book=new MemoryBook(new File(temp.getRoot(),"records.json"),null,index);book.save(scope(),row("项目使用Java17开发"),1);
            assertEquals(1,book.search(scope(),"Java17",10).length());assertEquals(1,book.search(scope(),"开发",10).length());
        }finally{index.close();}
    }
}
