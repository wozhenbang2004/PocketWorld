package dev.pocketengine.features.skills;
import java.io.File;
import java.util.Set;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public final class SkillQualityLifecycleTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
 SkillBook book; JSONObject skill; File root;
 @Before public void setup()throws Exception{
  root=temp.newFolder();book=reopen();ExperienceOrigin origin=new ExperienceOrigin(new ExperienceOrigin.Ref("method",1,"owner","private"),ExperienceOrigin.DraftAuthor.MODEL);
  JSONObject draft=new JSONObject().put("manifest","---\nname: test-method\ndescription: verify task\n---\nCheck actual task result.");
  String digest=book.inspectExperience(scope(),origin,draft,new JSONObject(),1).getString("inspectedDigest");
  skill=book.importExperience(scope(),origin,draft,new JSONObject().put("inspectedDigest",digest),2);
  skill=book.approveExperience(scope(),skill.getString("id"),1,skill.getString("digest"),3);
 }
 SkillBook reopen()throws Exception{return new SkillBook(root,(owner,space,id)->new ExperienceOrigin.State(new ExperienceOrigin.Ref("method",1,"owner","private"),ExperienceOrigin.Status.ACTIVE,true));}
 SkillQualityReceipt evidence(String op,String outcome,String status)throws Exception {
  JSONObject ref=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId","task").put("runId",op.startsWith("same")?"same-run":"run-"+op).put("epoch",1).put("operationId",op).put("planDigest","plan");
  JSONObject receipt=new JSONObject(ref.toString()).put("backendReceiptId","native-"+op).put("status",status);
  return SkillQualityReceipt.fromOriginal(new JSONObject().put("reference",ref).put("review",new JSONObject().put("package",skill)).put("receipt",receipt).put("claimedAt",1),outcome,"Owner checked actual output",10);
 }
 JSONObject apply(String op,String outcome)throws Exception{return book.recordQuality(scope(),evidence(op,outcome,"completed"),20);}
 @Test public void trialPromotesOnlyOnReviewedOriginalReceiptAndPersistsAcrossRestart()throws Exception {
  assertEquals("trial",skill.getString("qualityState"));
  assertEquals("trial",apply("environment","environment").getString("qualityState"));
  assertEquals("active",apply("good","verified").getString("qualityState"));
  assertEquals("active",reopen().get(scope(),skill.getString("id"),30).getString("qualityState"));
  assertEquals(2,reopen().get(scope(),skill.getString("id"),30).getJSONArray("qualityHistory").length());
 }
 @Test public void trialFailureSuspendsAndOriginalReadAndDiscoveryAreBlocked()throws Exception {
  JSONObject stopped=apply("bad","skill_failed");assertEquals("suspended",stopped.getString("qualityState"));assertFalse(stopped.getBoolean("active"));
  assertEquals(0,reopen().catalogueForHost(scope(),30).getJSONArray("skills").length());
  assertThrows(SecurityException.class,()->reopen().loadForHost(scope(),skill.getString("id"),skill.getString("digest"),Set.of(),30));
  assertEquals(1,apply("bad","skill_failed").getJSONArray("qualityHistory").length());
  assertThrows(SecurityException.class,()->apply("bad","verified"));
 }
 @Test public void activeUsesTwoAttributableFailuresInThreeAndEnvironmentDoesNotCount()throws Exception {
  apply("good","verified");assertEquals("active",apply("bad1","skill_failed").getString("qualityState"));
  apply("env","environment");assertEquals("suspended",apply("bad2","skill_failed").getString("qualityState"));
 }
 @Test public void restoreRequiresExactOwnerApprovalAndOldGenerationCannotContaminateNewTrial()throws Exception {
  SkillQualityReceipt late=evidence("late","verified","completed");apply("bad","skill_failed");
  book.approveExperience(scope(),skill.getString("id"),1,skill.getString("digest"),40);
  assertThrows(SecurityException.class,()->book.recordQuality(scope(),late,50));
  assertEquals("trial",reopen().get(scope(),skill.getString("id"),60).getString("qualityState"));
 }
 @Test public void unknownCancellationAndFailedReceiptCannotPretendSuccess()throws Exception {
  assertThrows(SecurityException.class,()->evidence("x","verified","unknown"));
  assertThrows(SecurityException.class,()->evidence("x","verified","failed"));
  assertThrows(SecurityException.class,()->evidence("x","skill_failed","cancelled"));
  assertEquals("trial",book.recordQuality(scope(),evidence("x","cancelled","cancelled"),20).getString("qualityState"));
 }
 @Test public void repeatedUsesOfSameSkillInOneRunDoNotCountAsTwoFailures()throws Exception {
  apply("good","verified");apply("same-first","skill_failed");JSONObject result=apply("same-second","skill_failed");assertEquals("active",result.getString("qualityState"));assertEquals(2,result.getJSONArray("qualityHistory").length());
  assertThrows(SecurityException.class,()->apply("same-third","verified"));
 }

}
