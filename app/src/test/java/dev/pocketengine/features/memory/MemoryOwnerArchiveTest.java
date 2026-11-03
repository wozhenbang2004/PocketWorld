package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real original atomic Book files. Archives are data, never revived source/execution authority. */
public final class MemoryOwnerArchiveTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private interface Checked {void run()throws Exception;}
    private static JSONObject own()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject record(String text)throws Exception{return new JSONObject().put("layer","fact").put("text",text).put("source","主人明确保存");}
    private static JSONObject draft(String text)throws Exception{return new JSONObject().put("layer","fact").put("text",text).put("reason","来自原生实际选定材料，仍需主人审阅").put("applicability","");}
    private static String bytes(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static void refused(Checked call)throws Exception{try{call.run();fail("archive scope/version/content must fail closed");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}}
    private JSONObject one(String text)throws Exception{MemoryBook book=new MemoryBook(new File(temp.newFolder(),"source.json"));book.save(own(),record(text).put("core",true),1);return book.ownerArchive(own());}
    private static final class Host implements SourceRef.Resolver {
        final Map<String,SourceRef.State> states=new HashMap<>();
        void state(SourceRef ref,boolean deleted){states.put(ref.key(),new SourceRef.State(ref,deleted));}
        @Override public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String id){return states.get(new SourceRef(kind,id,1,scope.ownerId,scope.spaceId).key());}
    }

    @Test public void ownerArchiveRetainsScopedBodiesAndNegativeEvidenceButRestoresOnlyInactiveNewIds()throws Exception {
        Host host=new Host();SourceRef live=new SourceRef("message","archive-live",1,"owner","private"),dead=new SourceRef("message","archive-dead",1,"owner","private");host.state(live,false);host.state(dead,false);
        MemoryBook source=new MemoryBook(new File(temp.newFolder(),"source.json"),host);
        JSONObject parent=source.save(own(),record("咖啡手动原文").put("core",true),1);
        source.save(own(),record("咖啡实际外部原文").put("sourceRefs",new JSONArray().put(live.toJson())),2);
        JSONObject child=source.save(own(),record("曾经的派生记录").put("sourceIds",new JSONArray().put(parent.getString("id"))),3);source.forget(own(),child.getString("id"));
        source.proposeLearning(own(),draft("咖啡待审候选正文"),4,live);
        source.proposeLearning(own(),draft("应退休的候选正文"),5,dead);host.state(dead,true);source.sourceDeleted(own(),dead);
        JSONObject terminal=source.proposeLearning(own(),draft("已经拒绝并清理的候选"),6,live);source.rejectCandidate(own(),terminal.getString("id"),1,"非长期结论",7);
        source.archiveCandidates(own(),new JSONArray().put(new JSONObject().put("id",terminal.getString("id")).put("revision",2)),8);
        source.save(new JSONObject().put("ownerId","owner").put("spaceId","group:one"),record("群空间不应导出"),9);
        source.save(new JSONObject().put("ownerId","another").put("spaceId","private"),record("另一个主体不应导出"),10);
        JSONObject archive=source.ownerArchive(own()),counts=archive.getJSONObject("counts");
        // The surviving ancestor and the exact removed child both retain body-free version fences.
        assertEquals(2,counts.getInt("records"));assertEquals(2,counts.getInt("learningCandidates"));assertEquals(1,counts.getInt("pendingCandidates"));assertEquals(1,counts.getInt("sourceTombstones"));assertEquals(2,counts.getInt("recordTombstones"));assertEquals(1,counts.getInt("candidateHistory"));assertEquals(3,counts.getInt("restoreRecordSlots"));
        assertFalse(archive.toString().contains("群空间不应导出"));assertFalse(archive.toString().contains("另一个主体不应导出"));assertFalse(archive.toString().contains("应退休的候选正文"));
        AtomicInteger resolverCalls=new AtomicInteger();File targetFile=new File(temp.newFolder(),"target.json");MemoryBook target=new MemoryBook(targetFile,(s,k,id)->{resolverCalls.incrementAndGet();throw new AssertionError("archive must not resolve or revive old sources");});
        JSONObject receipt=target.restoreOwnerArchive(own(),archive,"reviewed:archive-one");assertEquals(3,receipt.getInt("restoredRecordCount"));assertEquals(0,receipt.getInt("formalMemoryActivated"));assertEquals(0,receipt.getInt("pendingCandidatesActivated"));assertEquals(0,receipt.getInt("currentSourcesRestored"));assertFalse(receipt.getBoolean("executionAuthorized"));
        Set<String> oldIds=new HashSet<>();for(int i=0;i<archive.getJSONArray("records").length();i++)oldIds.add(archive.getJSONArray("records").getJSONObject(i).getString("id"));
        JSONArray restored=target.list(own());assertEquals(3,restored.length());for(int i=0;i<restored.length();i++){JSONObject r=restored.getJSONObject(i);assertFalse(oldIds.contains(r.getString("id")));assertEquals(1,r.getLong("revision"));assertTrue(r.getBoolean("archiveOnly"));assertFalse(r.getBoolean("active"));assertFalse(r.getBoolean("core"));assertEquals(0,r.getJSONArray("sourceRefs").length());assertEquals(0,r.getJSONArray("sourceIds").length());assertFalse(r.getJSONObject("archiveEvidence").getJSONObject("original").has("text"));}
        assertEquals(0,target.listCandidates(own()).length());assertEquals(0,target.sourceRefsForSynchronization(own()).length());assertEquals(0,target.search(own(),"咖啡",50).length());assertEquals("",target.contextSnapshot(own(),"咖啡",16,12000).getString("context"));assertEquals(0,resolverCalls.get());
        JSONObject stored=new JSONObject(bytes(targetFile));assertEquals(7,stored.getInt("version"));assertEquals(0,stored.getJSONArray("sources").length());assertEquals(0,stored.getJSONArray("learningCandidates").length());assertEquals(2,stored.getJSONArray("archiveImports").getJSONObject(0).getJSONArray("sourceEvidence").length());
        assertEquals(3,new MemoryBook(targetFile).list(own()).length());
    }

    @Test public void coldExactImportQueryAndReplayNeverAllocateAgainOrReviveAForgottenImportedRecord()throws Exception {
        JSONObject archive=one("这条档案正文只保留一次");String digest=archive.getString("sectionDigest");File file=new File(temp.newFolder(),"target.json");MemoryBook target=new MemoryBook(file);
        JSONObject unknown=target.queryOwnerArchiveRestore(own(),digest,"cold:one");assertEquals("unknown",unknown.getString("status"));assertFalse(unknown.getBoolean("found"));assertFalse(file.exists());
        JSONObject result=target.restoreOwnerArchive(own(),archive,"cold:one");String id=result.getJSONArray("records").getJSONObject(0).getString("newId"),saved=bytes(file);result.getJSONArray("records").getJSONObject(0).put("newId","foreign-json-id");result.getJSONObject("originalCounts").put("records",999);
        MemoryBook cold=new MemoryBook(file);JSONObject query=cold.queryOwnerArchiveRestore(own(),digest,"cold:one");assertTrue(query.getBoolean("found"));assertEquals(id,query.getJSONArray("records").getJSONObject(0).getString("newId"));assertEquals(1,query.getJSONObject("originalCounts").getInt("records"));assertEquals(saved,bytes(file));
        assertTrue(cold.restoreOwnerArchive(own(),archive,"cold:one").getBoolean("alreadyRestored"));assertEquals(saved,bytes(file));
        assertEquals(1,cold.forget(own(),id));String afterForget=bytes(file);assertFalse(afterForget.contains("这条档案正文只保留一次"));
        JSONObject replay=new MemoryBook(file).restoreOwnerArchive(own(),archive,"cold:one");assertEquals(0,replay.getInt("currentlyReadableRecords"));assertEquals("locally_forgotten_or_removed",replay.getJSONArray("records").getJSONObject(0).getString("state"));assertEquals(afterForget,bytes(file));
        JSONObject changed=one("同名导入不能换这条正文");refused(()->cold.restoreOwnerArchive(own(),changed,"cold:one"));refused(()->cold.queryOwnerArchiveRestore(own(),changed.getString("sectionDigest"),"cold:one"));assertEquals(afterForget,bytes(file));
    }

    @Test public void ordinaryEditCannotActivateArchiveOrStripItsDurableImportFence()throws Exception {
        File file=new File(temp.newFolder(),"target.json");MemoryBook target=new MemoryBook(file);JSONObject result=target.restoreOwnerArchive(own(),one("需重新核来源的档案"),"edit:one");String id=result.getJSONArray("records").getJSONObject(0).getString("newId");
        JSONObject edited=target.save(own(),target.get(own(),id).put("text","主人编辑后的档案原文"),2);assertTrue(edited.getBoolean("archiveOnly"));assertEquals(2,edited.getLong("revision"));refused(()->target.setCore(own(),id,2,true,3));refused(()->target.save(own(),target.get(own(),id).put("core",true),3));
        MemoryBook cold=new MemoryBook(file);assertFalse(cold.get(own(),id).getBoolean("active"));assertEquals("",cold.recall(own(),"档案",16).getString("context"));String good=bytes(file);JSONObject tampered=new JSONObject(good),row=tampered.getJSONArray("records").getJSONObject(0);row.remove("archiveOnly");row.remove("importedFrom");row.remove("archiveEvidence");row.put("formation","explicit_save");Files.write(file.toPath(),tampered.toString().getBytes(StandardCharsets.UTF_8));
        refused(()->new MemoryBook(file));assertEquals(tampered.toString(),bytes(file));Files.write(file.toPath(),good.getBytes(StandardCharsets.UTF_8));
        JSONObject exportAgain=cold.ownerArchive(own());MemoryBook next=new MemoryBook(new File(temp.newFolder(),"next.json"));next.restoreOwnerArchive(own(),exportAgain,"edit:next-install");assertEquals(0,next.search(own(),"档案",10).length());assertEquals(1,next.ownerArchive(own()).getJSONArray("priorImports").length());
    }

    @Test public void closedSchemaCountsHashAndOwnerScopeRejectBeforeAnyPartialWrite()throws Exception {
        JSONObject archive=one("原范围只读内容");File file=new File(temp.newFolder(),"target.json");MemoryBook target=new MemoryBook(file);target.save(own(),record("已有合法内容"),1);String before=bytes(file);
        JSONObject[] invalid={copy(archive).put("grant",new JSONObject()),copy(archive),copy(archive),copy(archive),copy(archive)};
        invalid[1].getJSONObject("counts").put("records",2);invalid[1].put("sectionDigest",MemoryArchive.sectionDigest(invalid[1]));
        invalid[2].getJSONArray("records").getJSONObject(0).put("ownerId","another");invalid[2].put("sectionDigest",MemoryArchive.sectionDigest(invalid[2]));
        invalid[3].getJSONArray("records").getJSONObject(0).put("text","没有同hash的新正文");
        invalid[4].put("activeAuthorityIncluded",true);invalid[4].put("sectionDigest",MemoryArchive.sectionDigest(invalid[4]));
        for(int i=0;i<invalid.length;i++){JSONObject input=invalid[i];String importId="bad:"+i;refused(()->target.restoreOwnerArchive(own(),input,importId));assertEquals(before,bytes(file));assertEquals("unknown",target.queryOwnerArchiveRestore(own(),archive.getString("sectionDigest"),importId).getString("status"));}
        JSONObject group=new JSONObject().put("ownerId","owner").put("spaceId","group:one");refused(()->target.ownerArchive(group));refused(()->target.restoreOwnerArchive(group,archive,"bad:group"));refused(()->target.restoreOwnerArchive(own(),archive,"../foreign"));assertEquals(before,bytes(file));
    }

    @Test public void originalAtomicWriterFailureRollsBackBothRowsAndAckWithoutASecondLedger()throws Exception {
        JSONObject archive=one("等待原子恢复的正文");File file=new File(temp.newFolder(),"target.json");MemoryBook target=new MemoryBook(file);JSONObject existing=target.save(own(),record("原有记录"),1);String before=bytes(file);File blocked=new File(file.getPath()+".new");assertTrue(blocked.mkdir());
        try{target.restoreOwnerArchive(own(),archive,"atomic:one");fail("real original atomic writer should fail");}catch(IOException expected){}
        assertEquals(before,bytes(file));assertEquals(1,target.list(own()).length());assertEquals(existing.getString("id"),target.list(own()).getJSONObject(0).getString("id"));assertEquals("unknown",target.queryOwnerArchiveRestore(own(),archive.getString("sectionDigest"),"atomic:one").getString("status"));assertEquals(1,new MemoryBook(file).list(own()).length());
        Files.delete(blocked.toPath());JSONObject result=target.restoreOwnerArchive(own(),archive,"atomic:one");assertEquals(1,result.getInt("restoredRecordCount"));assertEquals(2,new MemoryBook(file).list(own()).length());assertTrue(new MemoryBook(file).queryOwnerArchiveRestore(own(),archive.getString("sectionDigest"),"atomic:one").getBoolean("found"));
    }

    @Test public void capacityCheckDoesNotEvictOrPartiallyImportIntoTheOriginalThousandRecordBook()throws Exception {
        JSONObject archive=one("超过剩余容量的档案");File file=new File(temp.newFolder(),"target.json");JSONArray rows=new JSONArray();JSONObject template=archive.getJSONArray("records").getJSONObject(0);
        for(int i=0;i<1000;i++)rows.put(copy(template).put("id",UUID.randomUUID().toString()).put("text","原本的第"+i+"条记录").put("core",false));
        JSONObject precondition=new JSONObject().put("version",2).put("records",rows).put("sources",new JSONArray());Files.write(file.toPath(),precondition.toString().getBytes(StandardCharsets.UTF_8));MemoryBook target=new MemoryBook(file);String before=bytes(file);
        assertEquals(1000,target.list(own()).length());refused(()->target.restoreOwnerArchive(own(),archive,"capacity:one"));assertEquals(before,bytes(file));assertEquals(1000,new MemoryBook(file).list(own()).length());assertEquals("unknown",target.queryOwnerArchiveRestore(own(),archive.getString("sectionDigest"),"capacity:one").getString("status"));
    }
}
