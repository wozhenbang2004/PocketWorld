package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Original Book/state/send-dependency paths. No model, background scheduler or second profile. */
public final class MemoryAssertionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject assertion(String subject,String type,String kind,String object,Long event)throws Exception{return new JSONObject().put("version",1).put("kind",kind).put("subjectId",subject).put("subjectType",type).put("subjectLabel",subject.equals("owner")?"主人":"小王").put("predicate","居住城市").put("object",object).put("eventTime",event==null?JSONObject.NULL:event).put("evidenceSpans",new JSONArray());}
    private static JSONObject row(JSONObject assertion)throws Exception{return new JSONObject().put("layer","preference").put("text",assertion.getString("object")).put("assertion",assertion);}
    private MemoryBook book()throws Exception{return new MemoryBook(new File(temp.newFolder(),"records.json"));}
    private static String city(JSONArray rows)throws Exception{return rows.getJSONObject(0).getJSONObject("assertion").getString("object");}
    @Test public void lateHistoricalInsertCannotReplaceCurrentAndBothRemainExplainableAcrossColdOpen()throws Exception {
        File file=new File(temp.newFolder(),"records.json");MemoryBook book=new MemoryBook(file);JSONObject legacy=book.save(scope(),new JSONObject().put("text","legacy未标主体原文"),0);assertEquals(2,new JSONObject(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8)).getInt("version"));JSONObject a=book.save(scope(),row(assertion("owner","owner","inferred","A城",100L)),1);
        JSONObject oldSnapshot=book.contextSnapshot(scope(),"A城",16,12000);JSONObject dependency=book.contextDependency(oldSnapshot);
        book.save(scope(),row(assertion("owner","owner","inferred","B城",200L)),2);book.save(scope(),row(assertion("owner","owner","inferred","A城",100L)),99999);
        assertThrows(SecurityException.class,()->book.requireContextDependency(dependency));
        JSONObject current=book.cognitionProjection(scope(),"owner",System.currentTimeMillis());assertEquals("B城",city(current.getJSONArray("current")));assertEquals(2,current.getJSONArray("history").length());assertEquals(0,book.search(scope(),"A城",16).length());
        MemoryBook cold=new MemoryBook(file);assertEquals("B城",city(cold.cognitionProjection(scope(),"owner",System.currentTimeMillis()).getJSONArray("current")));assertEquals(9,new JSONObject(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8)).getInt("version"));assertFalse(cold.get(scope(),a.getString("id")).getBoolean("active"));assertFalse(cold.get(scope(),legacy.getString("id")).has("assertion"));assertEquals(1,cold.search(scope(),"legacy未标主体原文",20).length());
    }
    @Test public void sameNamePeopleAndImaginedPracticeNeverEnterOwnerProfileOrRealRecall()throws Exception {
        MemoryBook book=book();book.save(scope(),row(assertion("person:one","person","told","甲城",10L)),1);book.save(scope(),row(assertion("person:two","person","told","乙城",10L)),2);
        JSONObject imagined=book.save(scope(),row(assertion("owner","owner","imagined","虚构城",30L)).put("core",true),3);
        assertEquals(0,book.cognitionProjection(scope(),"owner",100).getJSONArray("current").length());assertEquals("甲城",city(book.cognitionProjection(scope(),"person:one",100).getJSONArray("current")));assertEquals("乙城",city(book.cognitionProjection(scope(),"person:two",100).getJSONArray("current")));
        assertFalse(book.get(scope(),imagined.getString("id")).getBoolean("active"));assertEquals(0,book.contextSnapshot(scope(),"虚构城",16,12000).getJSONArray("records").length());repeatedSummariesOfOneOriginalDoNotBecomeIndependentEvidenceOrIncreaseConfidence();
    }
    @Test public void conflictsRequireExactNativeReviewAndReasonWhileKeepingBothOriginals()throws Exception {
        MemoryBook book=book();JSONObject a=book.save(scope(),row(assertion("owner","owner","inferred","A城",10L)),1),b=book.save(scope(),row(assertion("owner","owner","told","B城",10L)),2);
        assertEquals(2,book.cognitionProjection(scope(),"owner",100).getJSONArray("pendingConflicts").length());assertEquals(0,book.search(scope(),"城",20).length());JSONArray refs=new JSONArray().put(new JSONObject().put("id",a.getString("id")).put("revision",1)).put(new JSONObject().put("id",b.getString("id")).put("revision",1));
        assertThrows(IllegalArgumentException.class,()->book.resolveAssertionConflictReviewed(scope(),refs,"replace",b.getString("id"),"",gone->{},100));
        JSONObject resolution=book.resolveAssertionConflictReviewed(scope(),refs,"replace",b.getString("id"),"主人核对两份原证据后选择B",gone->assertEquals(2,gone.length()),100);
        assertTrue(resolution.getBoolean("originalEvidenceRetained"));assertEquals(2,book.list(scope()).length());assertEquals("B城",city(book.cognitionProjection(scope(),"owner",100).getJSONArray("current")));assertEquals(1,book.cognitionProjection(scope(),"owner",100).getJSONArray("history").length());
        assertThrows(IllegalStateException.class,()->book.resolveAssertionConflictReviewed(scope(),refs,"coexist","","旧版本不能代表新审阅",gone->{},101));
    }
    @Test public void coexistIsExplicitAndAddingAnotherConflictInvalidatesTheOldDecision()throws Exception {
        MemoryBook book=book();JSONObject a=book.save(scope(),row(assertion("owner","owner","inferred","A城",10L)),1),b=book.save(scope(),row(assertion("owner","owner","told","B城",10L)),2);JSONArray refs=new JSONArray().put(new JSONObject().put("id",a.getString("id")).put("revision",1)).put(new JSONObject().put("id",b.getString("id")).put("revision",1));
        book.resolveAssertionConflictReviewed(scope(),refs,"coexist","","同时保留不同证据，当前未确定唯一事实",gone->{},100);assertEquals(2,book.cognitionProjection(scope(),"owner",100).getJSONArray("current").length());
        book.save(scope(),row(assertion("owner","owner","told","C城",10L)),101);assertEquals(0,book.cognitionProjection(scope(),"owner",102).getJSONArray("current").length());assertEquals(3,book.cognitionProjection(scope(),"owner",102).getJSONArray("pendingConflicts").length());
    }
    @Test public void forgettingCurrentFactDoesNotResurrectAnOldAliasEvenAfterRestart()throws Exception {
        File file=new File(temp.newFolder(),"records.json");MemoryBook book=new MemoryBook(file);book.save(scope(),row(assertion("owner","owner","inferred","A城",100L)),1);JSONObject b=book.save(scope(),row(assertion("owner","owner","inferred","B城",200L)),2);
        book.forget(scope(),b.getString("id"));book=new MemoryBook(file);assertEquals(0,book.search(scope(),"A城",20).length());assertEquals(0,book.cognitionProjection(scope(),"owner",300).getJSONArray("current").length());
        book.save(scope(),row(assertion("owner","owner","inferred","A城",100L)),400);assertEquals(0,book.search(scope(),"A城",20).length());
    }
    @Test public void futureValidityDoesNotAdvanceCurrentFactBeforeItsEffectiveTime()throws Exception {
        MemoryBook book=book();book.save(scope(),row(assertion("owner","owner","inferred","A城",100L)),1);book.save(scope(),row(assertion("owner","owner","inferred","B城",200L).put("validFrom",1000L)),2);
        assertEquals("A城",city(book.cognitionProjection(scope(),"owner",500).getJSONArray("current")));assertEquals("B城",city(book.cognitionProjection(scope(),"owner",1001).getJSONArray("current")));
    }
    @Test public void correctionInvalidatesDerivedViewsAndKeepsTheOlderHistoryNonCurrent()throws Exception {
        MemoryBook book=book();JSONObject a=book.save(scope(),row(assertion("owner","owner","inferred","A城",100L)),1),b=book.save(scope(),row(assertion("owner","owner","inferred","B城",200L)),2);
        JSONObject derived=book.save(scope(),new JSONObject().put("text","从B派生").put("sourceIds",new JSONArray().put(b.getString("id"))),3);JSONObject snapshot=book.contextSnapshot(scope(),"B城",16,12000),dependency=book.contextDependency(snapshot);
        book.correctReviewed(scope(),b.getString("id"),1,"C城",gone->assertTrue(gone.length()>=2),System.currentTimeMillis());assertThrows(SecurityException.class,()->book.requireContextDependency(dependency));assertThrows(IllegalArgumentException.class,()->book.get(scope(),derived.getString("id")));assertFalse(book.get(scope(),a.getString("id")).getBoolean("active"));
    }
    @Test public void userSaidRequiresRealAuthorAndExactLiteralNotAHashOrModelLabel()throws Exception {
        SourceRef ref=new SourceRef("message","original-author",1,"owner","private");String quote="我现在住B城";String hash=dev.pocketengine.features.local.project.ProjectSources.sha256(quote.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject assertion=assertion("owner","owner","user_said",quote,100L).put("evidenceSpans",new JSONArray().put(new JSONObject().put("sourceRef",ref.toJson()).put("offset",0).put("chars",quote.length()).put("sha256",hash)));
        JSONObject input=row(assertion).put("sourceRefs",new JSONArray().put(ref.toJson()));MemoryBook unproved=new MemoryBook(new File(temp.newFolder(),"records.json"),(scope,kind,id)->new SourceRef.State(ref,false));assertThrows(SecurityException.class,()->unproved.save(scope(),input,1));
        boolean[] author={true};SourceRef.AssertionResolver original=new SourceRef.AssertionResolver(){public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String id){return new SourceRef.State(ref,false);}public void requireUserSaid(JSONObject a,String text)throws Exception{if(!author[0]||!quote.equals(text)||!quote.equals(a.getString("object")))throw new SecurityException("not the original user's literal");}};
        MemoryBook book=new MemoryBook(new File(temp.newFolder(),"records.json"),original);book.save(scope(),input,1);assertEquals(1,book.search(scope(),"B城",20).length());author[0]=false;assertEquals(0,book.search(scope(),"B城",20).length());
    }
    private void repeatedSummariesOfOneOriginalDoNotBecomeIndependentEvidenceOrIncreaseConfidence()throws Exception {
        SourceRef ref=new SourceRef("message","same-original",1,"owner","private");MemoryBook book=new MemoryBook(new File(temp.newFolder(),"records.json"),(scope,kind,id)->new SourceRef.State(ref,false));JSONObject first=book.save(scope(),row(assertion("owner","owner","inferred","B城",100L)).put("confidence",0.4).put("sourceRefs",new JSONArray().put(ref.toJson())),1);
        book.save(scope(),row(assertion("owner","owner","inferred","B城",100L)).put("confidence",0.4).put("sourceIds",new JSONArray().put(first.getString("id"))),2);JSONObject view=book.cognitionProjection(scope(),"owner",200);assertEquals(1,view.getInt("independentSourceCount"));for(int i=0;i<view.getJSONArray("current").length();i++)assertEquals(0.4,view.getJSONArray("current").getJSONObject(i).getDouble("confidence"),0.0);assertEquals("none_same_source_not_reinforcement",view.getString("confidenceAggregation"));
    }
    @Test public void evidenceSpanMustBelongToTheActualNativeSourceAndCannotClaimReadOrApproval()throws Exception {
        MemoryBook book=book();SourceRef absent=new SourceRef("message","not-selected",1,"owner","private");JSONObject a=assertion("owner","owner","inferred","B城",100L).put("evidenceSpans",new JSONArray().put(new JSONObject().put("sourceRef",absent.toJson()).put("offset",0).put("chars",2).put("sha256","a".repeat(64))));
        assertThrows(SecurityException.class,()->book.save(scope(),row(a),1));a.getJSONArray("evidenceSpans").getJSONObject(0).put("read",true);assertThrows(IllegalArgumentException.class,()->MemoryAssertion.checked(a));
    }
    @Test public void oldArchiveRemainsPassiveAndTypedSemanticsSurviveAsHistoricalEvidenceOnly()throws Exception {
        SourceRef ref=new SourceRef("message","typed-review",1,"owner","private");MemoryBook review=new MemoryBook(new File(temp.newFolder(),"review.json"),(scope,kind,id)->new SourceRef.State(ref,false));JSONObject draft=new JSONObject().put("layer","preference").put("text","A城").put("reason","确切原来源，仍需主人审阅").put("applicability","").put("confidence",0.5).put("assertion",assertion("person:one","person","told","A城",100L));JSONObject candidate=review.proposeLearning(scope(),draft,1,ref);review.rejectCandidate(scope(),candidate.getString("id"),1,"暂不采用",2);draft.remove("assertion");JSONObject reopened=review.reconsiderRejected(scope(),candidate.getString("id"),2,draft,3);assertEquals("person:one",reopened.getJSONObject("assertion").getString("subjectId"));
        MemoryBook source=book();source.save(scope(),row(assertion("person:one","person","told","A城",100L)),1);JSONObject archive=source.ownerArchive(scope());MemoryBook target=book();JSONObject restored=target.restoreOwnerArchive(scope(),archive,"typed-archive");assertEquals(0,restored.getInt("formalMemoryActivated"));assertEquals(0,target.search(scope(),"A城",20).length());assertEquals(0,target.cognitionProjection(scope(),"person:one",200).getJSONArray("current").length());
    }
}
