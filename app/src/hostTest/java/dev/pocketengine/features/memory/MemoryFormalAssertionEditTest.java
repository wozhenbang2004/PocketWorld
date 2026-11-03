package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original Book edit/correction contracts, not a source-author, external model or device proof. */
public final class MemoryFormalAssertionEditTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject fact(String city,long time)throws Exception{return new JSONObject().put("text","记载住在"+city).put("layer","fact").put("assertion",new JSONObject().put("version",1).put("kind","inferred").put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","居住城市").put("object",city).put("eventTime",time).put("validFrom",time).put("evidenceSpans",new JSONArray()));}
    private static String current(MemoryBook book)throws Exception{return book.cognitionProjection(scope(),"owner",1000).getJSONArray("current").getJSONObject(0).getJSONObject("assertion").getString("object");}
    @Test public void aPartialTextOverwriteCannotLeaveAnOldStructuredFactBehindOrResurrectOlderHistory()throws Exception {
        File file=new File(temporary.getRoot(),"partial.json");MemoryBook book=new MemoryBook(file);book.save(scope(),fact("A城",100),100);JSONObject latest=book.save(scope(),fact("B城",200),200);byte[] before=Files.readAllBytes(file.toPath());
        assertThrows(IllegalArgumentException.class,()->book.save(scope(),new JSONObject().put("id",latest.getString("id")).put("revision",1).put("text","改成同事的C城资料，不是主人住所"),300));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals("B城",current(book));assertEquals(0,book.search(scope(),"A城",10).length());assertEquals("B城",current(new MemoryBook(file)));
    }
    @Test public void roundTrippingTheUnchangedTypedObjectWithNewProseIsNotARecheckedSemanticEdit()throws Exception {
        File file=new File(temporary.getRoot(),"roundtrip.json");MemoryBook book=new MemoryBook(file);JSONObject old=book.save(scope(),fact("A城",100),100);byte[] before=Files.readAllBytes(file.toPath());
        JSONObject copy=new JSONObject(old.toString()).put("text","新正文说的是B城");assertThrows(IllegalArgumentException.class,()->book.save(scope(),copy,200));assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(old.getString("text"),book.get(scope(),old.getString("id")).getString("text"));
        JSONObject reordered=new JSONObject();java.util.List<String> keys=new java.util.ArrayList<>();old.getJSONObject("assertion").keys().forEachRemaining(keys::add);java.util.Collections.reverse(keys);for(String key:keys)reordered.put(key,old.getJSONObject("assertion").get(key));assertThrows(IllegalArgumentException.class,()->book.save(scope(),new JSONObject(copy.toString()).put("assertion",reordered),200));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void originalNativeCorrectionStillRetiresTheOldVersionAndKeepsHistoryNonCurrent()throws Exception {
        File file=new File(temporary.getRoot(),"native.json");MemoryBook book=new MemoryBook(file);book.save(scope(),fact("A城",100),100);JSONObject latest=book.save(scope(),fact("B城",200),200),dependency=book.contextDependency(book.contextSnapshot(scope(),"B城",16,12000));int[] receipts={0};
        JSONObject corrected=book.correctReviewed(scope(),latest.getString("id"),1,"现在改为C城",refs->{receipts[0]++;assertEquals(latest.getString("id"),refs.getJSONObject(0).getString("recordId"));assertEquals(1,refs.getJSONObject(0).getLong("revision"));},400).getJSONObject("record");
        assertEquals(1,receipts[0]);assertEquals("inferred",corrected.getJSONObject("assertion").getString("kind"));assertEquals("现在改为C城",current(book));assertThrows(SecurityException.class,()->book.requireContextDependency(dependency));assertEquals(0,book.search(scope(),"A城",10).length());assertEquals("现在改为C城",current(new MemoryBook(file)));
    }
    @Test public void metadataOnlySavesKeepTheirExactAssertionAndExplicitNewTypedDraftsRemainSupported()throws Exception {
        MemoryBook book=new MemoryBook(new File(temporary.getRoot(),"explicit.json"));JSONObject old=book.save(scope(),fact("A城",100),100);JSONObject pin=book.save(scope(),new JSONObject().put("id",old.getString("id")).put("revision",1).put("text",old.getString("text")).put("core",true),200);assertEquals(old.getJSONObject("assertion").toString(),pin.getJSONObject("assertion").toString());
        JSONObject update=fact("B城",300).put("id",old.getString("id")).put("revision",pin.getLong("revision"));assertEquals("B城",book.save(scope(),update,300).getJSONObject("assertion").getString("object"));assertEquals("B城",current(book));
    }
    @Test public void oldUntypedMemoryEditsStayCompatibleAndStaleCasStillCannotWrite()throws Exception {
        File file=new File(temporary.getRoot(),"legacy.json");MemoryBook book=new MemoryBook(file);JSONObject old=book.save(scope(),new JSONObject().put("text","原无主体条目"),100);JSONObject update=new JSONObject().put("id",old.getString("id")).put("revision",1).put("text","主人改了旧文本");assertEquals("主人改了旧文本",book.save(scope(),update,200).getString("text"));byte[] before=Files.readAllBytes(file.toPath());assertThrows(IllegalStateException.class,()->book.save(scope(),update.put("text","旧revision"),300));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void failedNativeRetirementReceiptKeepsTheOriginalTypedBodyAndCurrentness()throws Exception {
        File file=new File(temporary.getRoot(),"receipt.json");MemoryBook book=new MemoryBook(file);JSONObject old=book.save(scope(),fact("A城",100),100);byte[] before=Files.readAllBytes(file.toPath());assertThrows(java.io.IOException.class,()->book.correctReviewed(scope(),old.getString("id"),1,"不能越过未保存清理回执",refs->{throw new java.io.IOException("original native receipt unavailable");},200));assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals("A城",current(book));
    }
}
