package dev.pocketengine.features.memory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Confidence is an unverified review estimate. Repeating one native source is not new evidence. */
public final class MemoryConsolidationConfidenceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final Map<String,SourceRef.State> sources=new HashMap<>();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryBook book()throws Exception{return new MemoryBook(new File(temp.getRoot(),"records.json"),(identity,kind,id)->sources.get(id));}
    private SourceRef source(String id){SourceRef ref=new SourceRef("message",id,1,"owner","private");sources.put(id,new SourceRef.State(ref,false));return ref;}
    private JSONObject fact(MemoryBook book,String label,double confidence,SourceRef source)throws Exception {
        JSONObject row=new JSONObject().put("text",label).put("confidence",confidence).put("assertion",new JSONObject().put("version",1).put("kind","inferred").put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate",label).put("object",label));
        if(source!=null)row.put("sourceRefs",new JSONArray().put(source.toJson()));return book.save(scope(),row,1);
    }
    private static JSONArray refs(JSONObject... rows)throws Exception {JSONArray selected=new JSONArray();for(JSONObject row:rows)selected.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));return selected;}
    private static JSONObject draft(String label,double confidence)throws Exception{return new JSONObject().put("layer","fact").put("text",label).put("reason","所选原证据的受控归纳，仍须主人审核").put("applicability","").put("confidence",confidence);}
    @Test public void twoSummariesOfOneMessageCannotRaiseConfidenceAboveTheirStrongestParent()throws Exception {
        MemoryBook book=book();SourceRef same=source("same-original");JSONObject a=fact(book,"原来源描述甲",0.4,same),b=fact(book,"原来源描述乙",0.4,same);
        JSONObject candidate=book.proposeConsolidation(scope(),draft("模型不能把重复描述提升到接近确定",0.99),refs(a,b),2);assertEquals(0.4,candidate.getDouble("confidence"),0.0);assertEquals("pending",candidate.getString("status"));
        JSONObject accepted=book.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"),3).getJSONObject("record");assertEquals(0.4,accepted.getDouble("confidence"),0.0);assertEquals("unverified",accepted.getString("validationStatus"));assertEquals(1,book.cognitionProjection(scope(),"owner",10).getInt("independentSourceCount"));
    }
    @Test public void repeatedNestedSummariesDoNotAccumulateConfidenceAcrossColdOpen()throws Exception {
        MemoryBook book=book();SourceRef same=source("one-raw-message");JSONObject a=fact(book,"最初甲",0.4,same),b=fact(book,"最初乙",0.4,same),candidate=book.proposeConsolidation(scope(),draft("第一层归纳",0.95),refs(a,b),2);
        JSONObject first=book.acceptCandidate(scope(),candidate.getString("id"),1,3).getJSONObject("record");book=book();JSONObject second=book.proposeConsolidation(scope(),draft("第二层重复归纳",0.99),refs(first,a),4);assertEquals(0.4,second.getDouble("confidence"),0.0);
    }
    @Test public void repeatingTheSameMultiSourceSetCannotManufactureAdditionalCorroboration()throws Exception {
        MemoryBook book=book();JSONObject a=fact(book,"原始甲",0.4,source("source-a")),b=fact(book,"原始乙",0.4,source("source-b"));
        JSONObject one=book.proposeConsolidation(scope(),draft("第一份双源归纳",0.7),refs(a,b),2),first=book.acceptCandidate(scope(),one.getString("id"),1,3).getJSONObject("record");
        JSONObject two=book.proposeConsolidation(scope(),draft("相同双源另一摘要",0.7),refs(a,b),4),second=book.acceptCandidate(scope(),two.getString("id"),1,5).getJSONObject("record");
        assertEquals(0.7,book.proposeConsolidation(scope(),draft("重复双源不是第三份证据",0.99),refs(first,second),6).getDouble("confidence"),0.0);
    }
    @Test public void unknownIndependentOriginsOfManualRecordsCannotBeCountedAsCorroboration()throws Exception {
        MemoryBook book=book();JSONObject a=fact(book,"无原件的手工甲",0.3,null),b=fact(book,"无原件的手工乙",0.5,null);assertEquals(0.5,book.proposeConsolidation(scope(),draft("来源独立性未知",0.9),refs(a,b),2).getDouble("confidence"),0.0);
    }
    @Test public void lowerEstimateIsNotAutomaticallyIncreasedAndDistinctNativeSourcesKeepOwnerReview()throws Exception {
        MemoryBook book=book();SourceRef same=source("same");JSONObject a=fact(book,"甲",0.4,same),b=fact(book,"乙",0.6,same);assertEquals(0.2,book.proposeConsolidation(scope(),draft("更保守的估计",0.2),refs(a,b),2).getDouble("confidence"),0.0);
        JSONObject c=fact(book,"独立原件",0.4,source("different"));JSONObject candidate=book.proposeConsolidation(scope(),draft("不同原件只允许候选估计，不代表已验证",0.8),refs(a,c),3);assertEquals(0.8,candidate.getDouble("confidence"),0.0);assertEquals("pending",candidate.getString("status"));assertEquals("unverified",candidate.getString("validationStatus"));
    }
    @Test public void twoRangesOfOneWorkspaceFileAreNotIndependentEvidenceFamilies()throws Exception {
        MemoryBook book=book();String project=UUID.randomUUID().toString(),revision=UUID.randomUUID().toString();SourceRef a=SourceRef.workspaceFile(project,"notes.txt",1,revision,"a".repeat(64),0,10,"owner","private"),b=SourceRef.workspaceFile(project,"notes.txt",1,revision,"a".repeat(64),10,10,"owner","private");sources.put(a.sourceId,new SourceRef.State(a,false));sources.put(b.sourceId,new SourceRef.State(b,false));
        JSONObject one=fact(book,"同一文件片段甲",0.4,a),two=fact(book,"同一文件片段乙",0.4,b);assertEquals(0.4,book.proposeConsolidation(scope(),draft("同文件不因切片倍增置信",0.9),refs(one,two),2).getDouble("confidence"),0.0);assertEquals(1,book.cognitionProjection(scope(),"owner",10).getInt("independentSourceCount"));
    }
    @Test public void savedRangesAndVerifiedResultOfOneOriginalTaskDoNotBecomeIndependentEvidence()throws Exception {
        MemoryBook book=book();String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();SourceRef saved=new SourceRef("task_evidence","task:"+task+":run:"+run+":saved:1:"+"a".repeat(64)+":range:0:10",1,"owner","private"),result=new SourceRef("task_evidence","task:"+task+":verified_result",1,"owner","private");sources.put(saved.sourceId,new SourceRef.State(saved,false));sources.put(result.sourceId,new SourceRef.State(result,false));
        JSONObject a=fact(book,"原任务片段",0.4,saved),b=fact(book,"同任务结果",0.5,result);assertEquals(0.5,book.proposeConsolidation(scope(),draft("原任务不同投影不是独立执行",0.95),refs(a,b),2).getDouble("confidence"),0.0);assertEquals(1,book.cognitionProjection(scope(),"owner",10).getInt("independentSourceCount"));
    }
    @Test public void explicitNativeOwnerEditStillRepresentsTheOwnersJudgmentNotAutomaticReinforcement()throws Exception {
        MemoryBook book=book();SourceRef same=source("native-owner-reviewed");JSONObject a=fact(book,"原证据甲",0.4,same),b=fact(book,"原证据乙",0.4,same),candidate=book.proposeConsolidation(scope(),draft("待审原归纳",0.9),refs(a,b),2);
        JSONObject edited=book.editCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"),draft("主人明确重估的待审归纳",0.8),3);assertEquals(0.8,edited.getDouble("confidence"),0.0);assertEquals("unverified",edited.getString("validationStatus"));
    }
}
