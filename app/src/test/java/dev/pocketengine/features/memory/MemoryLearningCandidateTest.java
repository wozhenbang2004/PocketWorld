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

public final class MemoryLearningCandidateTest {
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
    private JSONObject own()throws Exception{return scope("owner","private");}
    private SourceRef ref(String id,long revision){return new SourceRef("message",id,revision,"owner","private");}
    private JSONObject draft(String layer,String text)throws Exception {
        return new JSONObject().put("layer",layer).put("text",text).put("reason","从实际来源提炼，仍须机主审阅")
                .put("applicability","procedural".equals(layer)?"仅在相同任务与环境下作为待验证经验":"");
    }
    private JSONObject record(String text,SourceRef ref)throws Exception{return new JSONObject().put("text",text).put("sourceRefs",new JSONArray().put(ref.toJson()));}
    private File file(){return new File(temp.getRoot(),"records.json");}
    private String bytes(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}

    @Test public void pendingAndRejectedCandidatesNeverLeakIntoFormalMemoryOrContext()throws Exception {
        Host host=new Host();SourceRef source=ref("message:one",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject s=own();
        JSONObject candidate=book.proposeLearning(s,draft("preference","秘密候选：偏好咖啡"),1,source);
        assertEquals("pending",candidate.getString("status"));assertEquals("unverified",candidate.getString("validationStatus"));
        assertEquals(0,book.list(s).length());assertEquals(0,book.search(s,"咖啡",10).length());
        assertEquals("",book.recall(s,"咖啡",10).getString("context"));assertEquals("",book.contextSnapshot(s,"咖啡",16,12000).getString("context"));
        rejected(()->book.get(s,candidate.getString("id")));rejected(()->book.setCore(s,candidate.getString("id"),1,true,2));
        book.rejectCandidate(s,candidate.getString("id"),1,"把单次选择误认为长期偏好",2);
        assertEquals(0,book.list(s).length());assertEquals("",book.contextSnapshot(s,"",16,12000).getString("context"));
        JSONObject view=book.getCandidate(s,candidate.getString("id"));assertEquals("rejected",view.getString("status"));
        assertEquals("把单次选择误认为长期偏好",view.getString("rejectionReason"));assertEquals(source.toJson().toString(),view.getJSONArray("sourceRefs").getJSONObject(0).toString());
    }

    @Test public void acceptanceAtomicallyCreatesOneNonCoreRecordForEverySupportedLayer()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        for(String layer:new String[]{"fact","preference","episodic","procedural"}){
            JSONObject candidate=book.proposeLearning(s,draft(layer,"咖啡经验-"+layer),1,source);String id=candidate.getString("id");
            JSONObject accepted=book.acceptCandidate(s,id,1,2),saved=accepted.getJSONObject("record");
            assertEquals("accepted",accepted.getJSONObject("candidate").getString("status"));assertEquals(2,accepted.getJSONObject("candidate").getInt("revision"));
            assertEquals("reviewed_learning",saved.getString("formation"));assertFalse(saved.getBoolean("core"));assertEquals("unverified",saved.getString("validationStatus"));
            assertEquals(layer,saved.getString("layer"));assertEquals(id,saved.getString("learningCandidateId"));
            assertEquals(saved.getString("id"),book.acceptCandidate(s,id,1,3).getJSONObject("record").getString("id"));
            assertEquals(saved.getString("id"),new MemoryBook(file,host).acceptCandidate(s,id,2,4).getJSONObject("record").getString("id"));
            rejected(()->book.editCandidate(s,id,2,new JSONObject().put("text","改终态"),5));
            rejected(()->book.rejectCandidate(s,id,2,"改终态",5));
        }
        assertEquals(4,book.list(s).length());assertEquals(4,new MemoryBook(file,host).listCandidates(s).length());
        assertEquals(0,book.contextSnapshot(s,"",16,12000).getInt("coreAvailable"));
        String recalled=book.recall(s,"咖啡",16).getString("context");assertTrue(recalled.contains("unverified"));assertTrue(recalled.contains("相同任务与环境"));
        assertFalse(book.contextSnapshot(s,"咖啡",16,12000).getBoolean("executionAuthorized"));
    }

    @Test public void retriesDeduplicateAcrossSourceOrderRejectionEditsAcceptanceAndRestart()throws Exception {
        Host host=new Host();SourceRef a=ref("a",1),b=ref("b",1);host.live(a);host.live(b);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject original=draft("fact","原候选"),candidate=book.proposeLearning(s,original,1,a,b);String id=candidate.getString("id");
        assertEquals(id,book.proposeLearning(s,new JSONObject(original.toString()).put("reason","重新生成的说明").put("confidence",0.9),2,b,a,a).getString("id"));
        JSONObject edited=book.editCandidate(s,id,1,new JSONObject().put("text","机主编辑后的候选"),3);assertEquals(2,edited.getLong("revision"));
        JSONObject retry=book.proposeLearning(s,original,4,a,b);assertEquals("机主编辑后的候选",retry.getString("text"));assertEquals(2,retry.getLong("revision"));
        assertEquals(id,book.proposeLearning(s,draft("fact","机主编辑后的候选"),5,b,a).getString("id"));
        JSONObject rejected=book.rejectCandidate(s,id,2,"不是事实",6);
        assertEquals(3,rejected.getLong("revision"));assertEquals(3,book.rejectCandidate(s,id,2,"不是事实",7).getLong("revision"));
        assertEquals("rejected",new MemoryBook(file,host).proposeLearning(s,original,8,a,b).getString("status"));
        rejected(()->book.acceptCandidate(s,id,3,9));assertEquals(1,book.listCandidates(s).length());
        SourceRef fresh=ref("a",2);host.live(fresh);
        JSONObject newEvidence=book.proposeLearning(s,original,10,fresh,b);assertNotEquals(id,newEvidence.getString("id"));
        assertEquals("invalidated",book.getCandidate(s,id).getString("status"));
        book.acceptCandidate(s,newEvidence.getString("id"),1,11);
        assertEquals("accepted",new MemoryBook(file,host).proposeLearning(s,original,12,b,fresh).getString("status"));assertEquals(1,book.list(s).length());
    }

    @Test public void reviewUsesExactRevisionAndCannotEditEvidenceOrMergeOtherCandidates()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject s=own();
        JSONObject one=book.proposeLearning(s,draft("fact","第一候选"),1,source),two=book.proposeLearning(s,draft("fact","第二候选"),1,source);
        String id=one.getString("id");book.editCandidate(s,id,1,new JSONObject().put("text","新的第一候选"),2);
        rejected(()->book.editCandidate(s,id,1,new JSONObject().put("text","旧写"),3));rejected(()->book.acceptCandidate(s,id,1,3));
        rejected(()->book.rejectCandidate(s,id,1,"旧拒绝",3));rejected(()->book.editCandidate(s,id,2,new JSONObject().put("sourceRefs",new JSONArray()),3));
        rejected(()->book.editCandidate(s,id,2,new JSONObject().put("text",two.getString("text")),3));
        assertEquals(2,book.getCandidate(s,id).getLong("revision"));assertEquals("新的第一候选",book.acceptCandidate(s,id,2,4).getJSONObject("record").getString("text"));
    }

    @Test public void candidateScopeIsExactAndCallerCannotChooseAuthorityInTheModelDraft()throws Exception {
        Host host=new Host();SourceRef own=ref("same",1),group=new SourceRef("message","same",1,"owner","group:1"),other=new SourceRef("message","same",1,"other","private");
        host.live(own);host.live(group);host.live(other);MemoryBook book=new MemoryBook(file(),host);JSONObject s=own(),g=scope("owner","group:1"),o=scope("other","private");
        JSONObject mine=book.proposeLearning(s,draft("fact","相同正文"),1,own);
        book.proposeLearning(g,draft("fact","相同正文"),1,group);book.proposeLearning(o,draft("fact","相同正文"),1,other);
        assertEquals(1,book.listCandidates(s).length());assertEquals(1,book.listCandidates(g).length());assertEquals(1,book.listCandidates(o).length());
        rejected(()->book.getCandidate(g,mine.getString("id")));rejected(()->book.acceptCandidate(o,mine.getString("id"),1,2));
        rejected(()->book.proposeLearning(s,draft("fact","跨空间"),2,group));rejected(()->book.proposeLearning(null,draft("fact","无身份"),2,own));
        for(String field:new String[]{"ownerId","scope","sourceRefs","sourceIds","core","status","validationStatus","verified","executionAuthorized","id","formation"})
            rejected(()->book.proposeLearning(s,draft("fact","不能设置权限").put(field,true),2,own));
        rejected(()->book.proposeLearning(s,draft("fact","无来源"),2));
        rejected(()->new MemoryBook(new File(temp.getRoot(),"no-resolver.json")).proposeLearning(s,draft("fact","无resolver"),2,own));
        MemoryBook forged=new MemoryBook(new File(temp.getRoot(),"forged.json"),(scope,kind,id)->new SourceRef.State(other,false));
        rejected(()->forged.proposeLearning(s,draft("fact","resolver返回其他身份"),2,own));
    }

    @Test public void acceptanceRechecksLiveEvidenceEvenBeforeAHostInvalidationEventArrives()throws Exception {
        Host host=new Host();SourceRef first=ref("source",1),next=ref("source",2);host.live(first);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject candidate=book.proposeLearning(s,draft("fact","旧版本候选"),1,first);String id=candidate.getString("id"),before=bytes(file);
        host.live(next);rejected(()->book.acceptCandidate(s,id,1,2));rejected(()->book.editCandidate(s,id,1,new JSONObject().put("text","旧结果"),2));
        rejected(()->book.proposeLearning(s,draft("fact","延迟旧模型结果"),2,first));assertEquals(before,bytes(file));assertEquals(0,book.list(s).length());
        host.deleted(first);rejected(()->book.acceptCandidate(s,id,1,3));host.states.clear();rejected(()->book.acceptCandidate(s,id,1,4));
        assertEquals("pending",book.getCandidate(s,id).getString("status")); // A failed lookup alone is not an authoritative deletion event.
    }

    @Test public void sourceEventsScrubPendingRejectedAcceptedCandidateBodiesAndDerivedMemory()throws Exception {
        Host host=new Host();SourceRef first=ref("source",1),next=ref("source",2);host.live(first);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject pending=book.proposeLearning(s,draft("fact","删掉敏感-待审"),1,first);
        JSONObject reject=book.proposeLearning(s,draft("fact","删掉敏感-拒绝"),1,first);book.rejectCandidate(s,reject.getString("id"),1,"删掉敏感-拒绝原因",2);
        JSONObject accept=book.proposeLearning(s,draft("procedural","删掉敏感-已接受").put("reason","删掉敏感-理由").put("applicability","删掉敏感-条件"),1,first);
        JSONObject saved=book.acceptCandidate(s,accept.getString("id"),1,2).getJSONObject("record");book.setCore(s,saved.getString("id"),1,true,3);
        book.save(s,new JSONObject().put("text","删掉敏感-派生").put("sourceIds",new JSONArray().put(saved.getString("id"))).put("core",true),4);
        host.live(next);JSONObject event=book.sourceUpdated(s,next);assertEquals(2,event.getInt("invalidated"));assertEquals(3,event.getInt("invalidatedCandidates"));
        assertFalse(bytes(file).contains("删掉敏感"));assertEquals(0,book.list(s).length());
        for(JSONObject old:new JSONObject[]{pending,reject,accept}){
            JSONObject current=book.getCandidate(s,old.getString("id"));assertEquals("invalidated",current.getString("status"));assertEquals("source_changed",current.getString("invalidationReason"));
            assertFalse(current.has("text"));assertFalse(current.has("reason"));assertFalse(current.has("rejectionReason"));
            rejected(()->book.acceptCandidate(s,current.getString("id"),current.getLong("revision"),5));
        }
        assertEquals(3,new MemoryBook(file,host).listCandidates(s).length());
        JSONObject fresh=book.proposeLearning(s,draft("episodic","新的敏感经历"),6,next);host.deleted(next);
        assertEquals(1,book.sourceDeleted(s,next).getInt("invalidatedCandidates"));assertFalse(bytes(file).contains("新的敏感经历"));
        host.live(next);rejected(()->book.proposeLearning(s,draft("episodic","删除旧证据复活"),7,next));
        assertEquals("source_deleted",book.getCandidate(s,fresh.getString("id")).getString("invalidationReason"));
    }

    @Test public void forgettingMemoryScrubsAllCandidateStatesAndRejectsLateResultsAfterRestart()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject sourceRecord=book.save(s,record("遗忘秘密-源记忆",source),1);
        JSONObject pending=book.proposeLearning(s,draft("fact","遗忘秘密-候选"),2,source);
        JSONObject rejectedCandidate=book.proposeLearning(s,draft("fact","遗忘秘密-拒绝候选"),2,source);book.rejectCandidate(s,rejectedCandidate.getString("id"),1,"遗忘秘密-原因",3);
        JSONObject acceptedCandidate=book.proposeLearning(s,draft("fact","遗忘秘密-接受候选"),2,source);book.acceptCandidate(s,acceptedCandidate.getString("id"),1,3);
        assertEquals(2,book.forget(s,sourceRecord.getString("id")));assertFalse(bytes(file).contains("遗忘秘密"));
        MemoryBook reopened=new MemoryBook(file,host);assertEquals("forgotten",reopened.getCandidate(s,pending.getString("id")).getString("invalidationReason"));
        rejected(()->reopened.proposeLearning(s,draft("fact","迟到模型结果"),4,source));
        rejected(()->reopened.acceptCandidate(s,pending.getString("id"),2,4));
        reopened.resaveForgotten(s,record("机主明确恢复仍可保存",source),5);
        rejected(()->reopened.proposeLearning(s,draft("fact","不能借明确恢复绕过遗忘"),6,source));
        SourceRef fresh=ref("different-event",1);host.live(fresh);
        assertEquals("pending",reopened.proposeLearning(s,draft("fact","遗忘秘密-候选"),7,fresh).getString("status"));
    }

    @Test public void directRememberAndCorrectionsRemainImmediateAndPreserveLearnedValidationMetadata()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject s=own();
        JSONObject direct=book.save(s,new JSONObject().put("text","用户直接说记住").put("core",true),1);
        assertEquals(0,book.listCandidates(s).length());assertEquals(1,book.list(s).length());assertTrue(direct.getBoolean("core"));
        JSONObject candidate=book.proposeLearning(s,draft("procedural","先核对再操作"),2,source);
        JSONObject accepted=book.acceptCandidate(s,candidate.getString("id"),1,3).getJSONObject("record");
        assertThrows(SecurityException.class,()->book.save(s,new JSONObject(accepted.toString()).put("text","机主直接纠正流程"),4));
        JSONObject corrected=book.save(s,new JSONObject().put("id",accepted.getString("id")).put("revision",accepted.getLong("revision")).put("text","机主直接纠正流程"),4);
        assertEquals("unverified",corrected.getString("validationStatus"));assertEquals(accepted.getString("applicability"),corrected.getString("applicability"));
        assertFalse(corrected.has("learningRationale"));
        assertEquals(1,book.listCandidates(s).length());
        assertEquals("机主直接纠正流程",new MemoryBook(file(),host).acceptCandidate(s,candidate.getString("id"),1,5).getJSONObject("record").getString("text"));
    }

    @Test public void draftBoundsUnknownFieldsAndDefensiveCopiesCannotChangeStoredReviewState()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject s=own();
        for(JSONObject malformed:new JSONObject[]{draft("invalid","bad"),draft("fact"," "),draft("fact","x".repeat(4001)),draft("fact","bad").put("reason","x".repeat(1001)),
                draft("fact","bad").put("applicability","x".repeat(1001)),draft("procedural","bad").put("applicability",""),draft("fact","bad").put("confidence","1"),
                draft("fact","bad").put("confidence",2),draft("fact","bad").put("reason",JSONObject.NULL)})rejected(()->book.proposeLearning(s,malformed,1,source));
        rejected(()->book.proposeLearning(s,draft("fact","bad"),-1,source));
        SourceRef[] tooMany=new SourceRef[21];java.util.Arrays.fill(tooMany,source);rejected(()->book.proposeLearning(s,draft("fact","bad"),1,tooMany));
        JSONObject input=draft("fact","真实候选"),candidate=book.proposeLearning(s,input,1,source);String id=candidate.getString("id");
        input.put("text","篡改输入");candidate.put("status","accepted");candidate.getJSONArray("sourceRefs").getJSONObject(0).put("revision",99);
        JSONObject inspected=book.listCandidates(s).getJSONObject(0);inspected.put("text","篡改输出");
        JSONObject stored=book.getCandidate(s,id);assertEquals("真实候选",stored.getString("text"));assertEquals("pending",stored.getString("status"));assertEquals(1,stored.getJSONArray("sourceRefs").getJSONObject(0).getLong("revision"));
        rejected(()->book.rejectCandidate(s,id,1," ",2));assertEquals(1,book.listCandidates(s).length());
    }

    @Test public void capacityIsBoundedAndDuplicatesStillReturnTheirTerminalStateAtCapacity()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();String first="";
        for(int i=0;i<MemoryBook.MAX_LEARNING_CANDIDATES;i++){
            JSONObject candidate=book.proposeLearning(s,draft("fact","候选"+i),i,source);if(i==0)first=candidate.getString("id");
        }
        book.rejectCandidate(s,first,1,"不采用",201);String before=bytes(file);
        rejected(()->book.proposeLearning(s,draft("fact","超额候选"),202,source));assertEquals(before,bytes(file));
        assertEquals("rejected",book.proposeLearning(s,draft("fact","候选0"),203,source).getString("status"));
        assertEquals(MemoryBook.MAX_LEARNING_CANDIDATES,new MemoryBook(file,host).listCandidates(s).length());
    }

    @Test public void acceptanceFailureNeverCommitsOnlyTheRecordOrOnlyTheReviewTransition()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject candidate=book.proposeLearning(s,draft("fact","失败不能成为事实"),1,source);String id=candidate.getString("id"),before=bytes(file);
        File block=new File(file.getPath()+".new");assertTrue(block.mkdir());
        ioFailed(()->book.acceptCandidate(s,id,1,2));ioFailed(()->book.rejectCandidate(s,id,1,"落盘失败的拒绝",2));
        ioFailed(()->book.editCandidate(s,id,1,new JSONObject().put("text","落盘失败的编辑"),2));
        ioFailed(()->book.proposeLearning(s,draft("fact","落盘失败的新候选"),2,source));
        assertEquals(before,bytes(file));assertEquals(0,book.list(s).length());assertEquals(1,book.listCandidates(s).length());assertEquals("pending",book.getCandidate(s,id).getString("status"));
        MemoryBook reopened=new MemoryBook(file,host);assertEquals(0,reopened.list(s).length());assertEquals("pending",reopened.getCandidate(s,id).getString("status"));
        assertTrue(block.delete());book.acceptCandidate(s,id,1,3);assertEquals(1,new MemoryBook(file,host).list(s).length());
    }

    @Test public void failedForgetAndNewSourceTransactionsRestoreCandidateBodiesAndAllPriorState()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1),next=ref("source",2);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject candidate=book.proposeLearning(s,draft("fact","原候选正文"),1,source);String id=candidate.getString("id");
        JSONObject saved=book.acceptCandidate(s,id,1,2).getJSONObject("record");String before=bytes(file);File block=new File(file.getPath()+".new");assertTrue(block.mkdir());
        ioFailed(()->book.forget(s,saved.getString("id")));host.live(next);ioFailed(()->book.sourceUpdated(s,next));
        ioFailed(()->book.proposeLearning(s,draft("fact","新的候选"),3,next));
        ioFailed(()->book.importFromSources(s,record("新的正式记录",next),3));host.deleted(next);ioFailed(()->book.sourceDeleted(s,next));
        assertEquals(before,bytes(file));assertEquals("accepted",book.getCandidate(s,id).getString("status"));assertEquals("原候选正文",book.getCandidate(s,id).getString("text"));
        assertEquals(1,book.list(s).length());assertEquals(1,new MemoryBook(file,host).list(s).length());assertTrue(block.delete());host.live(source);
        assertEquals("accepted",book.proposeLearning(s,draft("fact","原候选正文"),4,source).getString("status"));
    }

    @Test public void lateRenameFailureDoesNotLeaveAcceptedStateOrLoseForgetBarriers()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        JSONObject candidate=book.proposeLearning(s,draft("fact","原始候选"),1,source);String id=candidate.getString("id");byte[] before=Files.readAllBytes(file.toPath());
        File moved=new File(temp.getRoot(),"original.json");Files.move(file.toPath(),moved.toPath());assertTrue(file.mkdir());File child=new File(file,"block");assertTrue(child.createNewFile());
        ioFailed(()->book.acceptCandidate(s,id,1,2));assertEquals(0,book.list(s).length());assertEquals("pending",book.getCandidate(s,id).getString("status"));
        assertArrayEquals(before,Files.readAllBytes(moved.toPath()));assertTrue(child.delete());assertTrue(file.delete());Files.move(moved.toPath(),file.toPath());
        JSONObject saved=book.acceptCandidate(s,id,1,3).getJSONObject("record");book.forget(s,saved.getString("id"));
        assertEquals("forgotten",new MemoryBook(file,host).getCandidate(s,id).getString("invalidationReason"));rejected(()->book.proposeLearning(s,draft("fact","复活"),4,source));
    }

    @Test public void oldVersionOneAndTwoMigrateLazilyWithoutChangingFormalRecordsOrBarriers()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);JSONObject s=own();
        for(int version:new int[]{1,2}){
            File file=new File(temp.getRoot(),"v"+version+".json");MemoryBook seed=new MemoryBook(file,host);JSONObject old=seed.save(s,new JSONObject().put("text","旧记录"),1);
            JSONObject legacy=new JSONObject(bytes(file));legacy.put("version",version);legacy.remove("learningVersion");legacy.remove("learningCandidates");if(version==1)legacy.remove("sources");
            String original=legacy.toString();Files.write(file.toPath(),original.getBytes(StandardCharsets.UTF_8));MemoryBook reopened=new MemoryBook(file,host);
            assertEquals(original,bytes(file));assertEquals(0,reopened.listCandidates(s).length());assertEquals("旧记录",reopened.get(s,old.getString("id")).getString("text"));
            JSONObject candidate=reopened.proposeLearning(s,draft("fact","升级后候选"),2,source);JSONObject state=new JSONObject(bytes(file));
            assertEquals(3,state.getInt("version"));assertEquals(1,state.getInt("learningVersion"));assertEquals(1,state.getJSONArray("learningCandidates").length());
            assertEquals(candidate.getString("id"),new MemoryBook(file,host).listCandidates(s).getJSONObject(0).getString("id"));
        }
        File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject old=book.save(s,record("旧版已遗忘",source),1);book.forget(s,old.getString("id"));
        JSONObject legacy=new JSONObject(bytes(file));legacy.remove("learningVersion");legacy.remove("learningCandidates");Files.write(file.toPath(),legacy.toString().getBytes(StandardCharsets.UTF_8));
        rejected(()->new MemoryBook(file,host).proposeLearning(s,draft("fact","升级不能复活"),3,source));
    }

    @Test public void corruptCandidateStateCannotBeSilentlyDroppedOrRestoredAsFacts()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        book.proposeLearning(s,draft("fact","候选"),1,source);String valid=bytes(file);
        for(String field:new String[]{"learningVersion","learningCandidates"}){
            JSONObject corrupt=new JSONObject(valid);corrupt.remove(field);String original=corrupt.toString();Files.write(file.toPath(),original.getBytes(StandardCharsets.UTF_8));
            rejected(()->new MemoryBook(file,host));assertEquals(original,bytes(file));
        }
        for(String field:new String[]{"status","validationStatus","contentKey"}){
            JSONObject corrupt=new JSONObject(valid);corrupt.getJSONArray("learningCandidates").getJSONObject(0).put(field,"forged");String original=corrupt.toString();
            Files.write(file.toPath(),original.getBytes(StandardCharsets.UTF_8));rejected(()->new MemoryBook(file,host));assertEquals(original,bytes(file));
        }
        JSONObject corrupt=new JSONObject(valid);corrupt.put("sources",new JSONArray());Files.write(file.toPath(),corrupt.toString().getBytes(StandardCharsets.UTF_8));rejected(()->new MemoryBook(file,host));
    }

    @Test public void failedFirstProposalLeavesLegacyFormatAndSourceRegistryUntouched()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);File file=file();MemoryBook book=new MemoryBook(file,host);JSONObject s=own();
        book.save(s,new JSONObject().put("text","老用户数据"),1);String before=bytes(file);assertEquals(2,new JSONObject(before).getInt("version"));
        File block=new File(file.getPath()+".new");assertTrue(block.mkdir());ioFailed(()->book.proposeLearning(s,draft("fact","未持久的新候选"),2,source));
        assertEquals(before,bytes(file));assertEquals(0,book.listCandidates(s).length());assertEquals(0,new MemoryBook(file,host).listCandidates(s).length());
        assertTrue(block.delete());book.save(s,new JSONObject().put("text","旧路径仍可保存"),3);
        JSONObject stillLegacy=new JSONObject(bytes(file));assertEquals(2,stillLegacy.getInt("version"));assertEquals(0,stillLegacy.getJSONArray("sources").length());
        book.proposeLearning(s,draft("fact","真正持久的新候选"),4,source);JSONObject migrated=new JSONObject(bytes(file));assertEquals(3,migrated.getInt("version"));
        migrated.remove("learningVersion");migrated.remove("learningCandidates");String corrupt=migrated.toString();Files.write(file.toPath(),corrupt.getBytes(StandardCharsets.UTF_8));
        rejected(()->new MemoryBook(file,host));assertEquals(corrupt,bytes(file));
    }

    @Test public void simultaneousReviewsUseRevisionCompareAndOnlyOneEditWins()throws Exception {
        Host host=new Host();SourceRef source=ref("source",1);host.live(source);MemoryBook book=new MemoryBook(file(),host);JSONObject s=own();
        String id=book.proposeLearning(s,draft("fact","原候选"),1,source).getString("id");ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Boolean> a=workers.submit(()->{start.await();try{book.editCandidate(s,id,1,new JSONObject().put("text","编辑A"),2);return true;}catch(IllegalStateException expected){return false;}});
            Future<Boolean> b=workers.submit(()->{start.await();try{book.editCandidate(s,id,1,new JSONObject().put("text","编辑B"),2);return true;}catch(IllegalStateException expected){return false;}});
            start.countDown();assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertEquals(2,book.getCandidate(s,id).getLong("revision"));
        }finally{start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test public void concurrentAcceptanceAndForgetCannotResurrectEvidence()throws Exception {
        SourceRef source=ref("source",1);Host host=new Host();host.live(source);JSONObject s=own();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);boolean[] block={false};
        MemoryBook book=new MemoryBook(file(),(scope,kind,id)->{
            if(block[0]){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}return host.resolve(scope,kind,id);
        });
        JSONObject root=book.save(s,record("源记忆",source),1);String id=book.proposeLearning(s,draft("fact","同时接受的候选"),2,source).getString("id");block[0]=true;
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<JSONObject> accepted=workers.submit(()->book.acceptCandidate(s,id,1,3));assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<Integer> forgotten=workers.submit(()->book.forget(s,root.getString("id")));release.countDown();
            accepted.get(5,TimeUnit.SECONDS);assertEquals(2,(int)forgotten.get(5,TimeUnit.SECONDS));assertEquals(0,book.list(s).length());
            assertEquals("invalidated",book.getCandidate(s,id).getString("status"));rejected(()->book.acceptCandidate(s,id,2,4));
            rejected(()->book.proposeLearning(s,draft("fact","旧异步结果"),4,source));
        }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
