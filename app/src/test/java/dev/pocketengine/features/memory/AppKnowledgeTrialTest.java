package dev.pocketengine.features.memory;
import dev.pocketengine.features.skills.*;
import java.io.File;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public final class AppKnowledgeTrialTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 File file; MemoryBook book; JSONObject source; long sourceVersion=1;
 JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
 SourceRef ref(){return new SourceRef("task_evidence","task:source",sourceVersion,"owner","private");}
 MemoryBook reopen()throws Exception{return new MemoryBook(file,(s,k,id)->new SourceRef.State(ref(),false));}
 JSONObject draft(String text)throws Exception{return new JSONObject().put("layer","procedural").put("text",text).put("reason","Observed original task steps").put("applicability","com.example.app v1 only");}
 @Before public void setup()throws Exception{file=new File(temp.getRoot(),"memory.json");book=reopen();JSONObject c=book.proposeLearning(scope(),draft("Observed task experience"),1,ref());source=book.acceptCandidate(scope(),c.getString("id"),1,2).getJSONObject("record");}
 JSONObject propose(JSONObject base,boolean lesson,String text)throws Exception{return book.proposeAppRevision(scope(),source.getString("id"),1,"com.example.app","1",base==null?"":base.getString("id"),base==null?0:base.getLong("revision"),lesson,draft(text),3);}
 JSONObject accept(JSONObject c)throws Exception{return book.acceptCandidate(scope(),c.getString("id"),1,4).getJSONObject("record");}
 SkillQualityReceipt evidence(JSONObject record,String op,String outcome)throws Exception{
  JSONObject origin=new JSONObject().put("ref",new JSONObject().put("recordId",record.getString("id")).put("revision",record.getLong("revision")).put("ownerId","owner").put("spaceId","private"));
  JSONObject pack=new JSONObject().put("id","skill").put("revision",1).put("digest","digest").put("origin",origin);
  JSONObject ref=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId","task").put("runId","run").put("epoch",1).put("operationId",op).put("planDigest","plan");
  return SkillQualityReceipt.fromOriginal(new JSONObject().put("reference",ref).put("review",new JSONObject().put("package",pack)).put("receipt",new JSONObject(ref.toString()).put("backendReceiptId","native").put("status","completed")).put("claimedAt",1),outcome,"Owner inspected actual result",10);
 }
 JSONObject quality(JSONObject row,String op,String outcome)throws Exception{return book.recordAppQuality(scope(),evidence(row,op,outcome),11);}
 boolean active(JSONObject row)throws Exception{JSONArray rows=book.list(scope());for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(row.getString("id")))return rows.getJSONObject(i).getBoolean("active");return false;}
 @Test public void pendingNeverOverlaysAndApprovalTrialRollbackSurvivesReopen()throws Exception{
  JSONObject first=accept(propose(null,false,"- Original navigation"));quality(first,"good","verified");
  JSONObject candidate=propose(first,false,"- Corrected navigation");book=reopen();assertTrue(active(first));
  JSONObject trial=accept(candidate);book=reopen();assertFalse(active(first));assertTrue(active(trial));
  assertFalse(quality(trial,"env","environment").getBoolean("appKnowledgeUpdated"));assertFalse(active(first));
  assertTrue(quality(trial,"bad","skill_failed").getBoolean("rolledBack"));book=reopen();assertTrue(active(first));assertFalse(active(trial));
 }
 @Test public void lessonRequiresTwoFailuresAndSuccessArchivesExactOldVersion()throws Exception{
  JSONObject first=accept(propose(null,false,"- Original navigation"));quality(first,"good","verified");
  JSONObject trial=accept(propose(first,true,"- Lesson navigation"));assertFalse(quality(trial,"bad1","skill_failed").getBoolean("rolledBack"));assertFalse(active(first));
  assertTrue(quality(trial,"bad2","skill_failed").getBoolean("rolledBack"));assertTrue(active(first));
  JSONObject next=accept(propose(first,false,"- Next navigation"));quality(next,"good2","verified");book=reopen();assertFalse(active(first));assertTrue(active(next));
 }
 @Test public void staleSourceAndBaselineCannotApproveOrKeepTrial()throws Exception{
  JSONObject first=accept(propose(null,false,"- Original navigation"));quality(first,"good","verified");
  JSONObject candidate=propose(first,false,"- New navigation");sourceVersion=2;
  assertThrows(Exception.class,()->accept(candidate));
  assertThrows(Exception.class,()->book.reviewedProcedure(scope(),first.getString("id"),1));
 }
 @Test public void plainEditCannotBypassAppLifecycleAndRestartShowsState()throws Exception{
  JSONObject first=accept(propose(null,false,"- Original navigation"));book=reopen();
  assertEquals("trial",book.reviewedProcedure(scope(),first.getString("id"),1).getJSONObject("appKnowledge").getString("stage"));
  assertThrows(SecurityException.class,()->book.save(scope(),new JSONObject().put("id",first.getString("id")).put("revision",1).put("text","bypass"),5));
 }
 @Test public void boundedDraftAndSensitiveValuesFailBeforeCandidatePublication()throws Exception{
  assertThrows(SecurityException.class,()->propose(null,false,"- password: not-for-memory"));
  assertThrows(IllegalArgumentException.class,()->propose(null,false,"- line\n".repeat(13)));
  JSONObject pending=propose(null,false,"- Valid first draft");
  assertThrows(IllegalStateException.class,()->propose(null,false,"- A competing pending draft"));
  assertThrows(IllegalArgumentException.class,()->book.editCandidate(scope(),pending.getString("id"),1,new JSONObject().put("layer","fact"),4));
 }
 @Test public void idempotentOutcomeRecoveryDoesNotDoubleCountFailure()throws Exception{
  JSONObject first=accept(propose(null,true,"- Lesson navigation"));
  quality(first,"bad","skill_failed");book=reopen();quality(first,"bad","skill_failed");
  assertTrue(active(first));assertEquals(1,book.reviewedProcedure(scope(),first.getString("id"),1).getJSONObject("appKnowledge").getJSONArray("outcomes").length());
 }
 @Test public void sourceRetirementDisablesTrialBeforeAnyNewRecommendation()throws Exception{
  JSONObject first=accept(propose(null,false,"- Navigation"));
  book.forget(scope(),source.getString("id"));book=reopen();assertFalse(active(first));
 }
 @Test public void rolledBackAppSourceDisablesTransitiveAppDescendantsAfterRestart()throws Exception{
  JSONObject a=accept(propose(null,false,"- A navigation"));
  JSONObject b=accept(book.proposeAppRevision(scope(),a.getString("id"),1,"com.example.second","1","",0,false,draft("- B from A"),5));
  JSONObject c=accept(book.proposeAppRevision(scope(),b.getString("id"),1,"com.example.third","1","",0,false,draft("- C from B"),6));
  assertTrue(active(b));assertTrue(active(c));quality(a,"bad-a","skill_failed");
  book=reopen();assertFalse(active(a));assertFalse(active(b));assertFalse(active(c));
  assertThrows(Exception.class,()->book.reviewedProcedure(scope(),c.getString("id"),1));
 }

}
