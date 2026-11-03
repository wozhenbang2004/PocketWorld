package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

/** Actual original Book/native FTS; labels are aliases only for the same explicit typed ID.
 * No inferred identity, embeddings, extra model call or cross-audience lookup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryEntityAliasRetrievalNativeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private File file()throws Exception{return new File(temp.newFolder(),"records.json");}
    private static JSONObject fact(String id,String type,String label,String predicate,String object)throws Exception {
        return new JSONObject().put("layer","fact").put("text","原文逐字保留，不含主体称呼")
            .put("assertion",new JSONObject().put("version",1).put("kind","told").put("subjectId",id).put("subjectType",type).put("subjectLabel",label).put("predicate",predicate).put("object",object).put("eventTime",100).put("evidenceSpans",new JSONArray()));
    }
    private static boolean contains(JSONArray rows,String id)throws Exception{for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return true;return false;}
    private static JSONObject saved(MemoryBook book,String label,String predicate,String object)throws Exception{return book.save(scope(),fact("entity:project-a","entity",label,predicate,object),1);}
    @Test public void oldReviewedNameFindsOtherCurrentFactsOfTheSameExplicitSubjectWithoutChangingFormalText()throws Exception {
        for(MemoryRetrievalBackend backend:List.of(new LexicalMemoryRetrievalBackend(),SQLiteMemoryRetrievalBackend.create(false)))try{
            File path=file();MemoryBook book=new MemoryBook(path,null,backend);saved(book,"银杉","所在城市","杭州");JSONObject target=saved(book,"白桦","构建方式","Gradle");
            assertTrue("old name must recall the separate current fact",contains(book.search(scope(),"银杉",16),target.getString("id")));
            assertTrue(contains(book.contextSnapshot(scope(),"银杉",16,12000).getJSONArray("records"),target.getString("id")));
            assertEquals("原文逐字保留，不含主体称呼",book.get(scope(),target.getString("id")).getString("text"));
            String stored=new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8);assertFalse(stored.contains("entityAliases"));assertFalse(stored.contains("retrievalText"));
        }finally{backend.close();}
    }
    @Test public void stableSubjectAndObjectIdsAreSearchableReferencesRatherThanNewIdentityAuthority()throws Exception {
        MemoryBook book=new MemoryBook(file());JSONObject one=book.save(scope(),fact("entity:unique-saffron","entity","白桦","构建方式","Gradle"),1);
        book.save(scope(),fact("entity:other","entity","梧桐","构建方式","Make"),2);
        JSONArray exact=book.search(scope(),"entity:unique-saffron",1);assertEquals("explicit stable ID must be indexed",1,exact.length());assertEquals(one.getString("id"),exact.getJSONObject(0).getString("id"));
        JSONObject relation=fact("owner","owner","主人","维护项目","白桦");relation.getJSONObject("assertion").put("objectId","entity:unique-saffron").put("objectType","entity");JSONObject linked=book.save(scope(),relation,3);
        assertTrue(contains(book.search(scope(),"entity:unique-saffron",16),linked.getString("id")));
    }
    @Test public void equalLabelsNeverMergeDifferentIdsOrSubjectTypes()throws Exception {
        MemoryBook book=new MemoryBook(file());JSONObject old=book.save(scope(),fact("person:one","person","小王","所在城市","杭州"),1);
        book.save(scope(),fact("person:one","person","王一","常用饮品","咖啡"),2);
        JSONObject other=book.save(scope(),fact("person:two","person","小王","工作城市","苏州"),3),differentType=book.save(scope(),fact("person:one","entity","小王","组织类型","研究组"),4);
        JSONArray rows=book.search(scope(),"王一",16);assertTrue(contains(rows,old.getString("id")));assertFalse(contains(rows,other.getString("id")));assertFalse(contains(rows,differentType.getString("id")));
    }
    @Test public void unselectedAudienceAndAnotherOwnerCannotDonateAliasesToAnAllowedFact()throws Exception {
        MemoryBook book=new MemoryBook(file());JSONObject donor=saved(book,"银杉","所在城市","杭州"),target=saved(book,"白桦","构建方式","Gradle");
        ReadAudience selected=new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "exact-target-only";}public long revision(){return 1;}public void checkCurrent(){}public JSONArray allowedSources(){return new JSONArray();}public JSONArray allowedRecords()throws Exception{return new JSONArray().put(new JSONObject().put("id",target.getString("id")).put("revision",target.getLong("revision")));}};
        assertEquals(0,book.search(scope(),"银杉",16,selected).length());assertEquals(1,book.search(scope(),"白桦",16,selected).length());
        book.forgetReviewed(scope(),donor.getString("id"),donor.getLong("revision"));JSONObject otherScope=new JSONObject().put("ownerId","another").put("spaceId","private");book.save(otherScope,fact("entity:project-a","entity","银杉","所在城市","杭州"),3);
        assertEquals(0,book.search(scope(),"银杉",16).length());
    }
    @Test public void imaginedHistoricalFutureAndConflictingAliasesAreNeverActiveDonors()throws Exception {
        MemoryBook book=new MemoryBook(file());JSONObject target=saved(book,"白桦","构建方式","Gradle");long now=System.currentTimeMillis();
        String[] labels={"银杉","雪松","紫檀","红杉"};
        JSONObject imagined=fact("entity:project-a","entity",labels[0],"想象代号","想象");imagined.getJSONObject("assertion").put("kind","imagined");book.save(scope(),imagined,2);
        JSONObject historical=fact("entity:project-a","entity",labels[1],"历史代号","旧称");historical.getJSONObject("assertion").put("validTo",now-1);book.save(scope(),historical,3);
        JSONObject future=fact("entity:project-a","entity",labels[2],"未来代号","暂未生效");future.getJSONObject("assertion").put("validFrom",now+60000);book.save(scope(),future,4);
        book.save(scope(),fact("entity:project-a","entity",labels[3],"冲突代号","甲"),5);book.save(scope(),fact("entity:project-a","entity","冲突另一标签","冲突代号","乙"),6);
        for(String label:labels)assertFalse(label,contains(book.search(scope(),label,16),target.getString("id")));
    }
    @Test public void sourceAdvanceAndCanonicalForgetWithdrawAliasEvenAcrossColdRebuild()throws Exception {
        File path=file();SourceRef ref=new SourceRef("message","original-alias",1,"owner","private");SourceRef.State[] current={new SourceRef.State(ref,false)};
        MemoryBook book=new MemoryBook(path,(s,k,id)->current[0]);JSONObject raw=fact("entity:project-a","entity","银杉","所在城市","杭州").put("sourceRefs",new JSONArray().put(ref.toJson()));JSONObject donor=book.save(scope(),raw,1),target=saved(book,"白桦","构建方式","Gradle");
        assertTrue(contains(book.search(scope(),"银杉",16),target.getString("id")));current[0]=new SourceRef.State(new SourceRef("message",ref.sourceId,2,"owner","private"),false);assertEquals(0,book.search(scope(),"银杉",16).length());
        current[0]=new SourceRef.State(ref,false);book.forgetReviewed(scope(),donor.getString("id"),donor.getLong("revision"));assertEquals(0,book.search(scope(),"银杉",16).length());
        MemoryBook cold=new MemoryBook(path,(s,k,id)->current[0]);assertEquals(0,cold.search(scope(),"银杉",16).length());assertEquals(target.getString("id"),cold.search(scope(),"白桦",16).getJSONObject(0).getString("id"));
    }
    @Test public void nativeProjectSelectionCannotBorrowGlobalAliasesOrOverrideUnsupportedTypedManualInputs()throws Exception {
        MemoryBook book=new MemoryBook(file());saved(book,"银杉","所在城市","杭州");
        var context=new dev.pocketengine.features.FeatureContext(org.robolectric.RuntimeEnvironment.getApplication(),null,null,event->{});
        var workspace=new dev.pocketengine.features.workspace.WorkspaceModule(context);var local=new dev.pocketengine.features.local.LocalModule(context,workspace);
        try{
            String project=workspace.handle("create",new JSONObject().put("name","alias-scope-fixture")).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","readme.txt").put("content","原项目").put("expectedVersion",0));JSONObject checkout=local.handle("prepare",new JSONObject().put("projectId",project));
            ProjectMemoryNamespaces spaces=new ProjectMemoryNamespaces(book,local,workspace);JSONObject choice=null;JSONArray choices=spaces.choicesForOwner();for(int i=0;i<choices.length();i++)if(checkout.getString("id").equals(choices.getJSONObject(i).getString("checkoutId")))choice=choices.getJSONObject(i);assertNotNull(choice);
            var selected=spaces.selectForOwner(choice.getString("checkoutId"),choice.getString("projectId"),choice.getString("revision"));
            JSONObject target=spaces.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("layer","fact").put("text","紫檀项目手写记录"))).getJSONObject("record");
            assertEquals(0,spaces.handleForOwner(selected,"search",new JSONObject().put("query","银杉")).getJSONArray("records").length());
            // Existing native project manual-input contract does not accept typed assertions.
            // Alias retrieval is not a reason to mint a new source or bypass that restriction.
            assertThrows(UnsupportedOperationException.class,()->spaces.handleForOwner(selected,"save",new JSONObject().put("record",fact("entity:project-a","entity","紫檀","维护方式","原生审批"))));
            assertEquals(0,book.search(scope(),"紫檀",16).length());assertEquals(target.getString("id"),spaces.handleForOwner(selected,"search",new JSONObject().put("query","紫檀")).getJSONArray("records").getJSONObject(0).getString("id"));
            assertThrows(SecurityException.class,()->book.search(selected.metadata(),"紫檀",16));spaces.clearForOwner();assertThrows(SecurityException.class,()->spaces.handleForOwner(selected,"search",new JSONObject().put("query","紫檀")));
        }finally{book.closeRetrieval();local.close();workspace.close();}
    }
    @Test public void labelFanoutIsExplicitlyBoundedWithoutChangingTheSavedRows()throws Exception {
        MemoryBook book=new MemoryBook(file());JSONObject target=saved(book,"target-label","构建方式","Gradle");for(int i=0;i<9;i++)saved(book,"alias"+i,"记录"+i,"已审阅");
        assertTrue(contains(book.search(scope(),"alias0",16),target.getString("id")));assertFalse(contains(book.search(scope(),"alias8",16),target.getString("id")));
        assertEquals(10,book.list(scope()).length());assertEquals(8,book.retrievalStatus().getInt("entityAliasLabelsPerIdentity"));assertFalse(book.retrievalStatus().getBoolean("entityAliasesPersisted"));
    }
    @Test public void aliasProjectionAddsNoUnrelatedSourceReadsToTheOriginalCurrentnessScan()throws Exception {
        SourceRef alias=new SourceRef("message","alias-donor",1,"owner","private"),unrelated=new SourceRef("message","unrelated-donor",1,"owner","private");boolean[] found={false};int[] after={0,0};LexicalMemoryRetrievalBackend delegate=new LexicalMemoryRetrievalBackend();
        MemoryRetrievalBackend index=new MemoryRetrievalBackend(){public void synchronize(List<Document> rows)throws Exception{delegate.synchronize(rows);}public List<Match> find(String o,String s,String q,int limit)throws Exception{List<Match> hits=delegate.find(o,s,q,limit);found[0]=true;return hits;}public void invalidate()throws Exception{delegate.invalidate();}public JSONObject status()throws Exception{return delegate.status();}public void close()throws Exception{delegate.close();}};
        MemoryBook book=new MemoryBook(file(),(s,k,id)->{if(found[0])after[id.equals(alias.sourceId)?0:1]++;return new SourceRef.State(id.equals(alias.sourceId)?alias:unrelated,false);},index);
        book.save(scope(),fact("entity:project-a","entity","银杉","所在城市","杭州").put("sourceRefs",new JSONArray().put(alias.toJson())),1);saved(book,"白桦","构建方式","Gradle");
        book.save(scope(),fact("entity:unrelated","entity","橡树","所在城市","苏州").put("sourceRefs",new JSONArray().put(unrelated.toJson())),2);
        // The original post-index activeIds scan already rechecks each source once.
        found[0]=false;book.search(scope(),"Gradle",16);assertArrayEquals(new int[]{1,1},after);
        found[0]=false;after[0]=after[1]=0;book.search(scope(),"银杉",16);assertEquals("alias expansion adds no second read of an unrelated group",1,after[1]);
    }
    @Test public void backendCannotKeepAnAliasWhoseOriginalSourceChangesDuringTheSearch()throws Exception {
        SourceRef ref=new SourceRef("message","original-alias",1,"owner","private");SourceRef.State[] current={new SourceRef.State(ref,false)};LexicalMemoryRetrievalBackend delegate=new LexicalMemoryRetrievalBackend();
        MemoryRetrievalBackend index=new MemoryRetrievalBackend(){public void synchronize(List<Document> rows)throws Exception{delegate.synchronize(rows);}public List<Match> find(String o,String s,String q,int limit)throws Exception{List<Match> hits=delegate.find(o,s,q,limit);current[0]=new SourceRef.State(new SourceRef("message",ref.sourceId,2,"owner","private"),false);return hits;}public void invalidate()throws Exception{delegate.invalidate();}public JSONObject status()throws Exception{return delegate.status();}public void close()throws Exception{delegate.close();}};
        MemoryBook book=new MemoryBook(file(),(s,k,id)->current[0],index);book.save(scope(),fact("entity:project-a","entity","银杉","所在城市","杭州").put("sourceRefs",new JSONArray().put(ref.toJson())),1);saved(book,"白桦","构建方式","Gradle");
        assertEquals(0,book.search(scope(),"银杉",16).length());
    }
}
