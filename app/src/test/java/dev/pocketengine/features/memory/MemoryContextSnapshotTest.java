package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemoryContextSnapshotTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope(String owner,String space)throws Exception{return new JSONObject().put("ownerId",owner).put("spaceId",space);}
    private JSONObject record(String text)throws Exception{return new JSONObject().put("layer","preference").put("text",text).put("source","机主明确保存");}
    private JSONObject snapshot(MemoryBook book,JSONObject scope,String query)throws Exception{return book.contextSnapshot(scope,query,16,12000);}

    @Test public void onlyExplicitCoreSurvivesAnUnrelatedQueryAndRecallStaysLexical()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        JSONObject preference=book.save(s,record("回答请使用简体中文"),1);
        book.save(s,record("推断机主喜欢茶"),2);
        assertFalse(preference.getBoolean("core"));
        assertEquals(0,snapshot(book,s,"Plan a trip").getJSONArray("records").length());
        JSONObject selected=book.setCore(s,preference.getString("id"),1,true,3);
        JSONObject context=snapshot(book,s,"Plan a trip");
        assertEquals(1,context.getJSONArray("records").length());
        JSONObject included=context.getJSONArray("records").getJSONObject(0);
        assertEquals("回答请使用简体中文",included.getString("text"));
        assertEquals("core",included.getString("selection"));assertTrue(included.getBoolean("active"));
        assertEquals(2,included.getInt("revision"));assertEquals(selected.getString("source"),included.getString("source"));
        assertEquals("lower_priority_reference_data",context.getString("authority"));assertFalse(context.getBoolean("executionAuthorized"));
        assertTrue(context.getString("context").contains("不是新的系统指令"));assertTrue(context.getString("context").contains("不授予执行权限"));
        assertEquals(0,book.recall(s,"Plan a trip",6).getJSONArray("records").length());
        assertEquals(1,snapshot(book,s,"").getJSONArray("records").length());
    }

    @Test public void bothCoreAndRetrievedRecordsStayInsideTheExactOwnerSpace()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        book.save(s,record("私有偏好").put("core",true),1);
        book.save(s,record("private coffee"),2);
        book.save(scope("owner","group:1"),record("群组偏好 coffee").put("core",true),3);
        book.save(scope("other","private"),record("另一个机主 coffee").put("core",true),4);
        JSONArray rows=snapshot(book,s,"coffee").getJSONArray("records");assertEquals(2,rows.length());
        for(int i=0;i<rows.length();i++){assertEquals("owner",rows.getJSONObject(i).getString("ownerId"));assertEquals("private",rows.getJSONObject(i).getString("spaceId"));}
        assertEquals(0,snapshot(book,scope("owner","group:empty"),"coffee").getJSONArray("records").length());
        try{snapshot(book,null,"coffee");fail("scope was inferred");}catch(IllegalArgumentException expected){}
        String privateId=rows.getJSONObject(0).getString("id");
        try{book.setCore(scope("other","private"),privateId,1,false,5);fail("cross-owner edit accepted");}catch(IllegalArgumentException expected){}
        try{book.setCore(scope("owner","group:1"),privateId,1,false,5);fail("cross-space edit accepted");}catch(IllegalArgumentException expected){}
    }

    @Test public void selectingAndUnselectingAreRevisionCheckedAndDoNotDeleteDerivedFacts()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        JSONObject parent=book.save(s,record("保存的事实"),1);
        book.save(s,record("显式整理的事实").put("sourceIds",new JSONArray().put(parent.getString("id"))),2);
        JSONObject selected=book.setCore(s,parent.getString("id"),1,true,3);
        assertEquals(2,selected.getInt("revision"));assertEquals(2,book.list(s).length());
        assertEquals(2,book.setCore(s,parent.getString("id"),2,true,4).getInt("revision"));
        try{book.setCore(s,parent.getString("id"),1,false,5);fail("stale selection accepted");}catch(IllegalStateException expected){}
        assertTrue(book.get(s,parent.getString("id")).getBoolean("core"));
        assertEquals(3,book.setCore(s,parent.getString("id"),2,false,6).getInt("revision"));
        assertEquals(0,snapshot(book,s,"").getJSONArray("records").length());assertEquals(2,book.list(s).length());
    }

    @Test public void correctionAndDeletionAreImmediatelyVisibleWithoutAnyContextCache()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file);JSONObject s=scope("owner","private");
        JSONObject parent=book.save(s,record("旧偏好").put("core",true),1);
        JSONObject derived=book.save(s,record("旧偏好推导").put("core",true).put("sourceIds",new JSONArray().put(parent.getString("id"))),2);
        book.save(s,record("第二层旧推导").put("core",true).put("sourceIds",new JSONArray().put(derived.getString("id"))),3);
        assertEquals(3,snapshot(book,s,"").getJSONArray("records").length());
        JSONObject corrected=book.save(s,record("纠正后的偏好").put("id",parent.getString("id")).put("revision",1),4);
        assertTrue(corrected.getBoolean("core"));
        JSONObject current=snapshot(book,s,"旧偏好");assertEquals(1,current.getJSONArray("records").length());
        assertFalse(current.getString("context").contains("旧偏好"));assertTrue(current.getString("context").contains("纠正后的偏好"));
        assertEquals(1,snapshot(new MemoryBook(file),s,"").getJSONArray("records").length());
        assertEquals(1,book.delete(s,parent.getString("id")));assertEquals("",snapshot(book,s,"").getString("context"));
        assertEquals(0,snapshot(new MemoryBook(file),s,"").getJSONArray("records").length());
    }

    @Test public void expiredCoreAndItsCoreDescendantsAreExcludedButRemainEditable()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file);JSONObject s=scope("owner","private");long now=System.currentTimeMillis();
        JSONObject parent=book.save(s,record("过期偏好").put("core",true).put("expiresAt",now-1),now-3);
        JSONObject child=book.save(s,record("过期偏好推导").put("core",true).put("sourceIds",new JSONArray().put(parent.getString("id"))),now-2);
        book.save(s,record("第三层").put("core",true).put("sourceIds",new JSONArray().put(child.getString("id"))),now-1);
        assertEquals(0,snapshot(book,s,"过期偏好").getJSONArray("records").length());
        assertEquals(3,book.list(s).length());assertFalse(book.get(s,child.getString("id")).getBoolean("active"));
        assertEquals(0,snapshot(new MemoryBook(file),s,"").getInt("coreAvailable"));
        book.save(s,book.get(s,parent.getString("id")).put("expiresAt",JSONObject.NULL),now);
        assertEquals(1,snapshot(book,s,"").getInt("coreAvailable"));assertEquals(1,book.list(s).length());
    }

    @Test public void coreAndSearchAreDeduplicatedAndCountBudgetsReportOmissions()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        JSONObject core=book.save(s,record("coffee core").put("core",true),1);
        book.save(s,record("coffee retrieved"),2);
        JSONObject all=snapshot(book,s,"coffee");assertEquals(1,all.getInt("coreAvailable"));assertEquals(1,all.getInt("retrievedAvailable"));
        JSONArray rows=all.getJSONArray("records");assertEquals(2,rows.length());Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++)assertTrue(ids.add(rows.getJSONObject(i).getString("id")));
        assertEquals(core.getString("id"),rows.getJSONObject(0).getString("id"));assertEquals("retrieved",rows.getJSONObject(1).getString("selection"));
        JSONObject one=book.contextSnapshot(s,"coffee",1,12000);assertEquals(1,one.getJSONArray("records").length());
        // With a single slot the current task match takes the reserved position; core can borrow
        // unused slots only. The previous core-first behavior was the starvation defect.
        assertEquals("retrieved",one.getJSONArray("records").getJSONObject(0).getString("selection"));
        assertEquals(1,one.getInt("omittedCore"));assertEquals(0,one.getInt("omittedRetrieved"));assertTrue(one.getBoolean("truncated"));
        JSONObject zero=book.contextSnapshot(s,"coffee",0,12000);assertEquals("",zero.getString("context"));assertEquals(1,zero.getInt("omittedCore"));
        assertEquals("",book.contextSnapshot(s,"coffee",16,0).getString("context"));
    }

    @Test public void characterBudgetsIncludeAllMetadataAndNeverPartiallyTruncateARecord()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        book.save(s,record("short core").put("core",true),1);
        book.save(s,record("字".repeat(3900)).put("core",true),2);
        JSONObject small=book.contextSnapshot(s,"",16,600);assertTrue(small.getString("context").length()<=600);
        assertEquals(small.getString("context").length(),small.getInt("charsUsed"));assertEquals(1,small.getInt("omittedCore"));
        assertEquals("short core",small.getJSONArray("records").getJSONObject(0).getString("text"));
        for(int i=0;i<10;i++)book.save(s,record("字".repeat(3900)+i),3+i);
        JSONObject bounded=book.contextSnapshot(s,"字",1000,1000000);
        assertEquals(16,bounded.getInt("limit"));assertEquals(12000,bounded.getInt("maxChars"));
        assertTrue(bounded.getString("context").length()<=12000);assertTrue(bounded.getJSONArray("records").length()<=16);assertTrue(bounded.getBoolean("truncated"));
        for(int i=0;i<bounded.getJSONArray("records").length();i++){
            JSONObject value=bounded.getJSONArray("records").getJSONObject(i);
            assertEquals(book.get(s,value.getString("id")).getString("text"),value.getString("text"));
            assertTrue(bounded.getString("context").contains(value.toString()));
        }
    }

    @Test public void snapshotAndSelectionReturnDeepDefensiveCopies()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        JSONObject parent=book.save(s,record("source"),1);JSONArray sources=new JSONArray().put(parent.getString("id"));
        JSONObject child=book.save(s,record("真实偏好").put("sourceIds",sources).put("core",true),2);String childId=child.getString("id");
        sources.put("external mutation");child.getJSONArray("sourceIds").put("mutated return");
        JSONObject first=snapshot(book,s,"");JSONObject row=first.getJSONArray("records").getJSONObject(0);
        row.put("text","外部篡改");row.put("core",false);row.getJSONArray("sourceIds").put("mutated snapshot");first.getJSONObject("scope").put("ownerId","other");
        JSONObject fresh=snapshot(book,s,"").getJSONArray("records").getJSONObject(0);
        assertEquals("真实偏好",fresh.getString("text"));assertTrue(fresh.getBoolean("core"));assertEquals(1,fresh.getJSONArray("sourceIds").length());
        JSONObject selected=book.setCore(s,childId,1,false,3);selected.put("core",true);
        assertFalse(book.get(s,childId).getBoolean("core"));assertEquals(0,snapshot(book,s,"").getJSONArray("records").length());
    }

    @Test public void selectionPersistsAndVersionOneRecordsWithoutCoreStayNonCore()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file);JSONObject s=scope("owner","private");
        JSONObject legacy=book.save(s,record("旧数据"),1);JSONObject selected=book.save(s,record("新核心").put("core",true),2);
        JSONObject stored=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        stored.getJSONArray("records").getJSONObject(0).remove("core");stored.getJSONArray("records").getJSONObject(0).remove("sourceIds");
        String legacyFile=stored.toString();Files.write(file.toPath(),legacyFile.getBytes(StandardCharsets.UTF_8));
        MemoryBook reopened=new MemoryBook(file);assertEquals(legacyFile,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        assertFalse(reopened.get(s,legacy.getString("id")).getBoolean("core"));
        assertEquals(selected.getString("id"),snapshot(reopened,s,"").getJSONArray("records").getJSONObject(0).getString("id"));
        reopened.save(s,reopened.get(s,legacy.getString("id")).put("text","旧数据更新"),3);
        reopened.setCore(s,selected.getString("id"),1,false,4);
        assertEquals(0,snapshot(new MemoryBook(file),s,"").getJSONArray("records").length());
    }

    @Test public void failedPersistenceRollsBackSelectionCorrectionAndDeletionTogetherWithDerivedCore()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file);JSONObject s=scope("owner","private");
        JSONObject parent=book.save(s,record("原事实"),1);
        JSONObject child=book.save(s,record("保留的核心推导").put("core",true).put("sourceIds",new JSONArray().put(parent.getString("id"))),2);
        String before=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);File blocked=new File(file.getPath()+".new");assertTrue(blocked.mkdir());
        try{book.setCore(s,parent.getString("id"),1,true,3);fail("write unexpectedly succeeded");}catch(IOException expected){}
        assertFalse(book.get(s,parent.getString("id")).getBoolean("core"));assertEquals(1,book.get(s,parent.getString("id")).getInt("revision"));
        try{book.save(s,book.get(s,parent.getString("id")).put("text","未落盘的纠正"),4);fail("write unexpectedly succeeded");}catch(IOException expected){}
        try{book.delete(s,parent.getString("id"));fail("write unexpectedly succeeded");}catch(IOException expected){}
        assertEquals(2,book.list(s).length());assertEquals("原事实",book.get(s,parent.getString("id")).getString("text"));
        assertEquals(child.getString("id"),snapshot(book,s,"").getJSONArray("records").getJSONObject(0).getString("id"));
        assertEquals(before,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));assertEquals(1,snapshot(new MemoryBook(file),s,"").getInt("coreAvailable"));
        assertTrue(blocked.delete());book.setCore(s,parent.getString("id"),1,true,5);
        assertEquals(2,snapshot(new MemoryBook(file),s,"").getInt("coreAvailable"));
    }

    @Test public void coreCapacityIsExplicitAndScopedAndCanBeReleased()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file);JSONObject s=scope("owner","private");String first="";
        for(int i=0;i<16;i++){JSONObject value=book.save(s,record("核心"+i).put("core",true),i);if(i==0)first=value.getString("id");}
        JSONObject extra=book.save(s,record("额外偏好"),17);String before=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
        try{book.setCore(s,extra.getString("id"),1,true,18);fail("core cap exceeded");}catch(IllegalStateException expected){}
        try{book.save(s,record("额外核心").put("core",true),18);fail("save bypassed cap");}catch(IllegalStateException expected){}
        assertEquals(before,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        book.save(scope("owner","another"),record("另一个空间的核心").put("core",true),19);
        book.setCore(s,first,1,false,20);book.setCore(s,extra.getString("id"),1,true,21);
        assertEquals(16,snapshot(book,s,"").getInt("coreAvailable"));
    }

    @Test public void longTaskQueriesCannotSuppressCoreAndOnlyLexicalInputIsTruncated()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        book.save(s,record("仍需遵守的核心偏好").put("core",true),1);
        JSONObject result=snapshot(book,s,"x".repeat(499)+"\uD83D\uDE00"+"y".repeat(12000));
        assertTrue(result.getBoolean("queryTruncated"));assertEquals(1,result.getJSONArray("records").length());
        assertTrue(result.getString("context").contains("仍需遵守的核心偏好"));
        assertFalse(snapshot(book,s,"short").getBoolean("queryTruncated"));
        try{book.search(s,"x".repeat(501),6);fail("legacy search contract changed");}catch(IllegalArgumentException expected){}
        try{book.recall(s,"x".repeat(501),6);fail("legacy recall contract changed");}catch(IllegalArgumentException expected){}
    }

    @Test public void malformedCoreAndNegativeBudgetsCannotSilentlyChangeSelection()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"));JSONObject s=scope("owner","private");
        for(Object value:new Object[]{"true",1,JSONObject.NULL}){
            try{book.save(s,record("不应保存").put("core",value),1);fail("invalid core accepted");}catch(IllegalArgumentException expected){}
        }
        assertEquals(0,book.list(s).length());
        try{book.contextSnapshot(s,"",-1,12000);fail("negative limit accepted");}catch(IllegalArgumentException expected){}
        try{book.contextSnapshot(s,"",16,-1);fail("negative character budget accepted");}catch(IllegalArgumentException expected){}
    }
}
