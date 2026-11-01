package dev.pocketengine.features.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

public final class MemorySourceLifecycleTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static final class Host implements SourceRef.Resolver {
        final Map<String,SourceRef.State> states=new HashMap<>();
        void live(SourceRef ref){states.put(ref.key(),new SourceRef.State(ref,false));}
        void deleted(SourceRef ref){states.put(ref.key(),new SourceRef.State(ref,true));}
        @Override public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String id){
            return states.get(new SourceRef(kind,id,1,scope.ownerId,scope.spaceId).key());
        }
    }
    private interface Checked {void run()throws Exception;}
    private void rejected(Checked call)throws Exception {
        try{call.run();fail("operation should fail closed");}catch(IllegalArgumentException|IllegalStateException expected){}
    }
    private void ioFailed(Checked call)throws Exception {
        try{call.run();fail("write unexpectedly succeeded");}catch(IOException expected){}
    }
    private JSONObject scope(String owner,String space)throws Exception{return new JSONObject().put("ownerId",owner).put("spaceId",space);}
    private JSONObject privateScope()throws Exception{return scope("owner","private");}
    private SourceRef ref(String id,long revision){return new SourceRef("message",id,revision,"owner","private");}
    private JSONObject record(String text,SourceRef...refs)throws Exception {
        JSONObject value=new JSONObject().put("text",text);JSONArray sources=new JSONArray();
        for(SourceRef ref:refs)sources.put(ref.toJson());return value.put("sourceRefs",sources);
    }
    private JSONObject child(String text,JSONObject parent)throws Exception{return record(text).put("sourceIds",new JSONArray().put(parent.getString("id")));}
    private int coreCount(MemoryBook book,JSONObject scope)throws Exception{return book.contextSnapshot(scope,"unrelated",16,12000).getInt("coreAvailable");}
    private String bytes(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}

    @Test public void reviewedForgetRejectsChangedRecordWithoutTouchingSourceBarriers()throws Exception {
        File file=new File(temp.getRoot(),"reviewed.json");Host host=new Host();SourceRef source=ref("reviewed-source",4);host.live(source);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();JSONObject saved=book.save(s,record("原文",source),1);
        JSONObject current=book.save(s,new JSONObject(saved.toString()).put("text","刚修改的正文"),2);String before=bytes(file);
        rejected(()->book.forgetReviewed(s,saved.getString("id"),saved.getLong("revision")));
        rejected(()->book.forgetReviewed(s,saved.getString("id"),0));
        rejected(()->book.forgetReviewed(scope("other","private"),saved.getString("id"),current.getLong("revision")));
        assertEquals(before,bytes(file));assertEquals("刚修改的正文",book.get(s,saved.getString("id")).getString("text"));
        book.importFromSources(s,record("同源仍可引用，未设置遗忘屏障",source),3);
        assertEquals(2,book.list(s).length());
    }

    @Test public void reviewedForgetUsesOnlyReviewedEvidenceRevisionAndStillRemovesDerivations()throws Exception {
        File file=new File(temp.getRoot(),"reviewed.json");Host host=new Host();SourceRef old=ref("reviewed-source",4),next=ref("reviewed-source",5);host.live(old);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();JSONObject saved=book.save(s,record("要遗忘的事实",old),1);
        book.save(s,child("对应派生",saved),2);
        // The host now has a newer source; forgetting the displayed old record must not fence it.
        host.live(next);assertEquals(2,book.forgetReviewed(s,saved.getString("id"),saved.getLong("revision")));
        MemoryBook reopened=new MemoryBook(file,host);assertEquals(0,reopened.list(s).length());
        assertEquals("真正新来源",reopened.importFromSources(s,record("真正新来源",next),3).getString("text"));
        assertEquals(4,new JSONObject(bytes(file)).getJSONArray("sources").getJSONObject(0).getLong("forgottenThrough"));
    }

    @Test public void queuedReviewedForgetCannotDeleteAConcurrentEdit()throws Exception {
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"concurrent-reviewed.json"));JSONObject s=privateScope();JSONObject saved=book.save(s,record("原文"),1);
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch queued=new CountDownLatch(1);Future<?> result;
        try{
            synchronized(book){
                result=worker.submit(()->{queued.countDown();rejected(()->book.forgetReviewed(s,saved.getString("id"),saved.getLong("revision")));return null;});
                assertTrue(queued.await(2,TimeUnit.SECONDS));book.save(s,new JSONObject(saved.toString()).put("text","并发修改"),2);
            }
            result.get(2,TimeUnit.SECONDS);assertEquals("并发修改",book.get(s,saved.getString("id")).getString("text"));
        }finally{worker.shutdownNow();}
    }

    @Test public void sourceRevisionEventRemovesTransitiveAndCoreRecordsAndKeepsDurableHighWatermark()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");Host host=new Host();SourceRef first=ref("message:one",1),second=ref("message:one",2);host.live(first);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();
        JSONObject root=book.save(s,record("旧地址",first).put("core",true),1);
        JSONObject derived=book.save(s,child("旧地址摘要",root).put("core",true),2);
        book.save(s,child("旧地址操作方法",derived).put("core",true),3);
        book.save(s,record("无关偏好").put("core",true),4);
        host.live(second);assertEquals(3,book.sourceUpdated(s,second).getInt("invalidated"));
        assertEquals(1,coreCount(book,s));assertFalse(bytes(file).contains("旧地址"));
        assertEquals(0,book.sourceUpdated(s,second).getInt("invalidated"));
        MemoryBook reopened=new MemoryBook(file,host);rejected(()->reopened.importFromSources(s,record("旧地址",first),5));
        host.live(first);rejected(()->reopened.importFromSources(s,record("滞后宿主旧地址",first),6));
        host.live(second);assertEquals("新地址",reopened.importFromSources(s,record("新地址",second),7).getString("text"));
        assertEquals(2,reopened.list(s).length());
    }

    @Test public void savingNewRevisionAlsoInvalidatesOldEvidenceInTheSameWrite()throws Exception {
        Host host=new Host();SourceRef first=ref("source",1),second=ref("source",2);host.live(first);
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"),host);JSONObject s=privateScope();
        JSONObject old=book.importFromSources(s,record("旧事实",first),1);book.save(s,child("旧摘要",old).put("core",true),2);
        host.live(second);JSONObject fresh=book.save(s,record("新事实",second).put("core",true),3);
        assertEquals(1,book.list(s).length());assertEquals(fresh.getString("id"),book.contextSnapshot(s,"",16,12000).getJSONArray("records").getJSONObject(0).getString("id"));
    }

    @Test public void forgetSurvivesRestartRejectsOldImportButNotSameTextFromNewEvidence()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");Host host=new Host();SourceRef old=ref("old-message",4);host.live(old);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();
        JSONObject saved=book.importFromSources(s,record("曾经喜欢的咖啡",old),1);
        assertEquals("source_import",saved.getString("formation"));assertTrue(saved.getString("source").contains("未经独立核实"));
        rejected(()->book.save(s,new JSONObject(saved.toString()).put("sourceRefs",new JSONArray()),2));
        book.setCore(s,saved.getString("id"),1,true,2);
        assertEquals(1,book.delete(s,saved.getString("id")));assertEquals(0,coreCount(book,s));assertFalse(bytes(file).contains("曾经喜欢的咖啡"));
        MemoryBook reopened=new MemoryBook(file,host);
        rejected(()->reopened.importFromSources(s,record("曾经喜欢的咖啡",old),3));
        rejected(()->reopened.save(s,record("换个措辞",old).put("formation","explicit_resave").put("explicitUser",true),4));
        rejected(()->reopened.importFromSources(s,record("没有真实证据"),5));
        SourceRef other=ref("new-message",1);host.live(other);
        assertEquals("曾经喜欢的咖啡",reopened.importFromSources(s,record("曾经喜欢的咖啡",other),6).getString("text"));
        SourceRef newer=ref("old-message",5);host.live(newer);
        reopened.importFromSources(s,record("真正的新版本",newer),7);
        assertEquals(2,new MemoryBook(file,host).list(s).length());
    }

    @Test public void explicitResaveIsPerWriteAndNeverClearsTheAutomaticImportBarrier()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");Host host=new Host();SourceRef source=ref("source",1);host.live(source);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();
        JSONObject saved=book.importFromSources(s,record("用户可以重新保存",source),1);book.forget(s,saved.getString("id"));
        JSONObject restored=book.resaveForgotten(s,record("用户可以重新保存",source).put("core",true),2);
        assertEquals("explicit_resave",restored.getString("formation"));assertEquals(1,coreCount(new MemoryBook(file,host),s));
        rejected(()->book.importFromSources(s,record("自动不能跟着复活",source),3));
        rejected(()->book.importFromSources(s,child("经由重新保存的记忆绕过屏障",restored),4));
        SourceRef revised=ref("source",2);host.live(revised);
        rejected(()->book.resaveForgotten(s,record("不能恢复陈旧版本",source),5));
        host.deleted(revised);book.sourceDeleted(s,revised);
        rejected(()->book.resaveForgotten(s,record("不能恢复删除来源",revised),6));
        assertEquals(0,book.list(s).length());
    }

    @Test public void sameSourceIdInDifferentOwnersAndSpacesNeverSharesInvalidationOrBarrier()throws Exception {
        Host host=new Host();SourceRef privateRef=ref("same-id",1);
        SourceRef groupRef=new SourceRef("message","same-id",1,"owner","group:1");
        SourceRef otherRef=new SourceRef("message","same-id",1,"other","private");
        host.live(privateRef);host.live(groupRef);host.live(otherRef);
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"),host);JSONObject s=privateScope(),group=scope("owner","group:1"),other=scope("other","private");
        JSONObject own=book.save(s,record("私有",privateRef).put("core",true),1);
        book.save(group,record("群聊",groupRef).put("core",true),2);book.save(other,record("其他机主",otherRef).put("core",true),3);
        rejected(()->book.save(s,record("跨空间",groupRef),4));rejected(()->book.sourceDeleted(s,groupRef));
        book.forget(s,own.getString("id"));assertEquals(0,coreCount(book,s));assertEquals(1,coreCount(book,group));assertEquals(1,coreCount(book,other));
        book.importFromSources(group,record("群聊再次提炼",groupRef),5);assertEquals(2,book.list(group).length());
        rejected(()->book.importFromSources(s,record("私有旧来源",privateRef),6));
    }

    @Test public void forgettingDerivedRecordFencesItsInheritedSourcesAndSiblingDerivations()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);
        File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();
        JSONObject root=book.importFromSources(s,record("遗忘源正文",source),1);
        JSONObject child=book.importFromSources(s,child("遗忘第一层",root),2);
        book.save(s,child("遗忘第二层",child).put("core",true),3);
        book.importFromSources(s,record("遗忘同源独立结论",source),4);
        book.save(s,record("保留无来源文字"),5);
        assertEquals(4,book.forget(s,child.getString("id")));assertFalse(bytes(file).contains("遗忘"));assertEquals(1,book.list(s).length());
        MemoryBook reopened=new MemoryBook(file,host);rejected(()->reopened.importFromSources(s,record("旧源重提炼",source),6));
    }

    @Test public void deletedSourceCannotReturnAtTheSameRevisionEvenIfHostLaterReportsItLive()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");Host host=new Host();SourceRef source=new SourceRef("task_evidence","task:7:receipt:9",2,"owner","private");host.live(source);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();
        JSONObject saved=book.save(s,record("任务经验",source).put("core",true),1);book.save(s,child("依赖经验",saved),2);
        host.deleted(source);assertEquals(2,book.sourceDeleted(s,source).getInt("invalidated"));
        MemoryBook reopened=new MemoryBook(file,host);assertEquals(0,coreCount(reopened,s));
        rejected(()->reopened.importFromSources(s,record("已删证据",source),3));
        host.live(source);rejected(()->reopened.sourceUpdated(s,source));rejected(()->reopened.resaveForgotten(s,record("旧删除身份",source),4));
        SourceRef restored=new SourceRef("task_evidence",source.sourceId,3,"owner","private");host.live(restored);
        reopened.importFromSources(s,record("新的真实任务证据",restored),5);assertEquals(1,reopened.list(s).length());
    }

    @Test public void absentOrForgedHostAuthorityFailsClosedWhileLegacyMemoryStillWorks()throws Exception {
        JSONObject s=privateScope();SourceRef source=ref("claimed-message",1);
        MemoryBook legacy=new MemoryBook(new File(temp.getRoot(),"legacy.json"));legacy.save(s,record("没有外部来源的原功能").put("core",true),1);
        assertEquals(1,coreCount(legacy,s));rejected(()->legacy.save(s,record("自称有证据",source),2));
        rejected(()->legacy.sourceUpdated(s,source));assertEquals(1,legacy.list(s).length());
        SourceRef foreign=new SourceRef("message",source.sourceId,1,"other","private");
        MemoryBook forged=new MemoryBook(new File(temp.getRoot(),"forged.json"),(scope,kind,id)->new SourceRef.State(foreign,false));
        rejected(()->forged.importFromSources(s,record("伪造归属",source),3));
        Host host=new Host();host.live(source);MemoryBook valid=new MemoryBook(new File(temp.getRoot(),"valid.json"),host);
        rejected(()->valid.importFromSources(s,record("未经用户选择核心",source).put("core",true),4));
        JSONObject saved=valid.save(s,record("机主事实",source),5);
        rejected(()->valid.importFromSources(s,record("自动覆盖用户",source).put("id",saved.getString("id")),6));
    }

    @Test public void allSourceAndForgetChangesRollBackWithRecordsWhenDiskWriteFails()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");Host host=new Host();SourceRef first=ref("source",1),next=ref("source",2);host.live(first);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();
        JSONObject root=book.save(s,record("原正文",first).put("core",true),1);book.save(s,child("原派生",root).put("core",true),2);
        String before=bytes(file);File block=new File(file.getPath()+".new");assertTrue(block.mkdir());
        ioFailed(()->book.forget(s,root.getString("id")));assertEquals(2,coreCount(book,s));
        host.live(next);ioFailed(()->book.sourceUpdated(s,next));ioFailed(()->book.importFromSources(s,record("未持久的新正文",next),3));
        host.deleted(next);ioFailed(()->book.sourceDeleted(s,next));
        assertEquals(before,bytes(file));assertEquals(2,book.list(s).length());assertTrue(book.get(s,root.getString("id")).getBoolean("core"));
        // Disk rollback preserves editable records, while live evidence already changed/deleted.
        // Such old records must not be injected merely because cleanup persistence failed.
        assertEquals(0,coreCount(book,s));assertEquals(0,coreCount(new MemoryBook(file,host),s));
        assertTrue(block.delete());host.live(first);
        book.importFromSources(s,record("证明未留下遗忘或新版屏障",first),4);assertEquals(3,book.list(s).length());
    }

    @Test public void lateRenameFailureAlsoRollsBackBarrierAndSourceRegistry()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");Host host=new Host();SourceRef first=ref("source",1),next=ref("source",2);host.live(first);
        MemoryBook book=new MemoryBook(file,host);JSONObject s=privateScope();JSONObject root=book.save(s,record("真实保存",first).put("core",true),1);
        byte[] before=Files.readAllBytes(file.toPath());File moved=new File(temp.getRoot(),"original.json");Files.move(file.toPath(),moved.toPath());
        assertTrue(file.mkdir());File child=new File(file,"block");assertTrue(child.createNewFile());
        ioFailed(()->book.forget(s,root.getString("id")));host.live(next);ioFailed(()->book.sourceUpdated(s,next));
        assertEquals(1,book.list(s).length());assertTrue(book.get(s,root.getString("id")).getBoolean("core"));
        assertEquals(0,coreCount(book,s));assertArrayEquals(before,Files.readAllBytes(moved.toPath()));
        assertTrue(child.delete());assertTrue(file.delete());Files.move(moved.toPath(),file.toPath());
        host.live(first);book.importFromSources(s,record("仍可引用旧版本",first),2);assertEquals(2,new MemoryBook(file,host).list(s).length());
    }

    @Test public void actualVersionOneLoadsWithoutRewriteAndMigratesOnlyOnSuccessfulWrite()throws Exception {
        File file=new File(temp.getRoot(),"memory.json");JSONObject s=privateScope();MemoryBook initial=new MemoryBook(file);
        JSONObject legacy=initial.save(s,record("老用户记忆"),1);legacy.remove("sourceRefs");legacy.remove("formation");legacy.remove("core");
        String v1=new JSONObject().put("version",1).put("records",new JSONArray().put(legacy)).toString();Files.write(file.toPath(),v1.getBytes(StandardCharsets.UTF_8));
        MemoryBook reopened=new MemoryBook(file);assertEquals(v1,bytes(file));assertEquals(1,reopened.list(s).length());assertEquals(0,coreCount(reopened,s));
        reopened.save(s,reopened.get(s,legacy.getString("id")).put("text","编辑老记忆"),2);
        JSONObject v2=new JSONObject(bytes(file));assertEquals(2,v2.getInt("version"));assertEquals(0,v2.getJSONArray("sources").length());
        assertEquals("编辑老记忆",new MemoryBook(file).get(s,legacy.getString("id")).getString("text"));
    }

    @Test public void malformedReferencesAndMissingPersistedBarriersCannotSilentlyDowngrade()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);JSONObject s=privateScope();File file=new File(temp.getRoot(),"memory.json");MemoryBook book=new MemoryBook(file,host);
        for(Object malformed:new Object[]{JSONObject.NULL,"source",new JSONArray().put(source.toJson().put("revision",1.5)),new JSONArray().put(source.toJson().put("revision","1"))})
            rejected(()->book.save(s,record("错误引用").put("sourceRefs",malformed),1));
        rejected(()->book.save(s,record("混用版本",source,ref("source",2)),1));
        JSONObject saved=book.importFromSources(s,record("保存后遗忘",source),2);book.forget(s,saved.getString("id"));
        JSONObject corrupt=new JSONObject(bytes(file));corrupt.remove("sources");String corruptBytes=corrupt.toString();Files.write(file.toPath(),corruptBytes.getBytes(StandardCharsets.UTF_8));
        rejected(()->new MemoryBook(file,host));assertEquals(corruptBytes,bytes(file));
    }

    @Test public void sourceRefsRemainDefensiveAndExpiredAncestryCannotBeAutomaticallyPromoted()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"),host);JSONObject s=privateScope();long now=System.currentTimeMillis();
        JSONObject input=record("短期来源",source).put("expiresAt",now-1);JSONObject stored=book.save(s,input,now-2);
        input.getJSONArray("sourceRefs").getJSONObject(0).put("sourceId","tampered");stored.getJSONArray("sourceRefs").getJSONObject(0).put("revision",100);
        assertEquals("source",book.get(s,stored.getString("id")).getJSONArray("sourceRefs").getJSONObject(0).getString("sourceId"));
        assertEquals(1,book.get(s,stored.getString("id")).getJSONArray("sourceRefs").getJSONObject(0).getLong("revision"));
        rejected(()->book.importFromSources(s,child("不能从过期来源自动提升",stored),now));
        book.save(s,child("显式旧行为可保留检查记录",stored).put("core",true),now);assertEquals(0,coreCount(book,s));
    }

    @Test public void importAndForgetAreSerializedAcrossTheResolverAndCommitBoundary()throws Exception {
        SourceRef source=ref("source",1);Host host=new Host();host.live(source);JSONObject s=privateScope();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);boolean[] block={false};
        MemoryBook book=new MemoryBook(new File(temp.getRoot(),"memory.json"),(scope,kind,id)->{
            if(block[0]){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
            return host.resolve(scope,kind,id);
        });
        JSONObject root=book.save(s,record("即将遗忘",source),1);block[0]=true;
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<JSONObject> imported=workers.submit(()->book.importFromSources(s,record("并行导入的旧来源",source),2));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<Integer> forgotten=workers.submit(()->book.forget(s,root.getString("id")));release.countDown();
            imported.get(5,TimeUnit.SECONDS);assertEquals(2,(int)forgotten.get(5,TimeUnit.SECONDS));assertEquals(0,book.list(s).length());
            rejected(()->book.importFromSources(s,record("再次自动导入",source),3));
        }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
