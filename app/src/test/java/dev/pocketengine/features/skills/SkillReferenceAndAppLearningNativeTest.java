package dev.pocketengine.features.skills;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.store.*;
import java.util.Set;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SkillReferenceAndAppLearningNativeTest {
 static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
 static final class Fixture {
  final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks;final RunControl control=new RunControl();final JSONObject task;final SkillsModule skills;
  Fixture()throws Exception{this(null);}
  Fixture(TaskStore existing)throws Exception{tasks=existing==null?new TaskStore(android):existing;task=tasks.startAttempt(tasks.createCapabilityTask("Original private phone task","").getString("id"),"capabilities",20);control.bindHostRun(id(),run(),epoch());tasks.authorizeTools(id(),run(),new JSONArray().put("skills_read").put("skills_reference_decision").put("memory_method_decision").put("phone_tap"));skills=new SkillsModule(new FeatureContext(android,null,new SecretVault(android),e->{}));}
  String id()throws Exception{return task.getString("id");}String run()throws Exception{return task.getString("runId");}int epoch()throws Exception{return task.getInt("coordinatorEpoch");}
  dev.pocketengine.core.RunJournal journal()throws Exception{return tasks.journal(id(),run(),epoch(),control);}
  JSONObject install()throws Exception{JSONObject skill=skills.handle("import",new JSONObject().put("source","test:reference:"+id()).put("manifest","---\nname: reference-"+id()+"\ndescription: Read current UI before tapping\n---\nInspect then tap the target; verify independently.")).getJSONObject("skill");skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",skill.getString("digest")));return skill;}
  String step(String obs)throws Exception{String op=journal().beginOperation("phone_tap",new JSONObject().put("observationId",obs).put("x",20).put("y",20));journal().finishOperation(op,new JSONObject().put("ok",true).put("accepted",true));return op;}
  JSONObject decision(JSONObject pack,String step)throws Exception{return new JSONObject().put("id",pack.getString("id")).put("revision",pack.getInt("revision")).put("digest",pack.getString("digest")).put("reason","Followed the exact read reference for this original step").put("stepOperationIds",new JSONArray().put(step));}
  JSONObject observe()throws Exception{PackageInfo p=new PackageInfo();p.packageName="com.example.learning";p.versionCode=7;p.versionName="7.0";p.lastUpdateTime=1234;((org.robolectric.shadows.ShadowPackageManager)org.robolectric.shadow.api.Shadow.extract(android.getPackageManager())).installPackage(p);JSONObject observation=new JSONObject().put("ok",true).put("observationId","native-observation").put("packageName",p.packageName);dev.pocketengine.phone.observation.InstalledAppEvidence.attach(android,observation);journal().checkpoint(new JSONArray(),0,observation);return observation;}
  void consent()throws Exception{tasks.setAppLearningPolicy(id(),0,true,new JSONArray().put("com.example.learning"),1,true);}
  void terminal(boolean verified)throws Exception{tasks.endAttempt(id(),run(),"needs_confirmation","Model reports done, not proof",new JSONObject());if(verified){tasks.event(id(),new JSONObject().put("type","user_verification").put("completed",true));tasks.status(id(),"completed","Owner inspected result");}}
 }
 @Test public void referenceReadOriginalStepAndNativeReviewSurviveRestart()throws Exception{
  Fixture f=new Fixture();JSONObject pack=f.install();var selection=f.skills.selectReferenceForHost(scope(),pack.getString("id"),1,pack.getString("digest"),Set.of("skills_read","skills_reference_decision","phone_tap"));
  f.tasks.recordSkillReferenceRead(f.id(),f.run(),f.epoch(),selection,"SKILL.md",0,100,f.control);String step=f.step("observation");JSONObject args=f.decision(pack,step);String op=f.journal().beginOperation("skills_reference_decision",args);
  JSONObject receipt=f.tasks.recordSkillReferenceDecision(f.id(),f.run(),f.epoch(),op,selection,f.control);assertFalse(receipt.getBoolean("businessVerified"));f.journal().finishOperation(op,receipt);
  TaskStore reopened=new TaskStore(f.android);assertEquals(1,reopened.skillQualityApplications(pack.getString("id"),1,pack.getString("digest")).getJSONArray("applications").length());
  var proof=reopened.reviewSkillQuality(f.id(),op,pack.getString("id"),1,pack.getString("digest"),"verified","Owner checked actual final result");
  assertEquals("active",f.skills.recordQuality(scope(),proof).getJSONObject("skill").getString("qualityState"));assertThrows(SecurityException.class,()->reopened.reviewSkillQuality(f.id(),op,pack.getString("id"),1,pack.getString("digest"),"skill_failed","Different conclusion"));
 }
 @Test public void claimingReferenceWithoutNativeReadCannotBecomeUseEvidence()throws Exception{
  Fixture f=new Fixture();JSONObject pack=f.install();var selection=f.skills.selectReferenceForHost(scope(),pack.getString("id"),1,pack.getString("digest"),Set.of("skills_read","skills_reference_decision","phone_tap"));String step=f.step("observation");String op=f.journal().beginOperation("skills_reference_decision",f.decision(pack,step));
  assertThrows(SecurityException.class,()->f.tasks.recordSkillReferenceDecision(f.id(),f.run(),f.epoch(),op,selection,f.control));
 }
 @Test public void nativePackageProofConsentAndOwnerOutcomeAreRequired()throws Exception{
  Fixture f=new Fixture();JSONObject observation=f.observe();assertEquals(7,observation.getJSONObject("installedApp").getLong("versionCode"));for(int i=0;i<5;i++)f.step("native-observation");f.terminal(false);
  assertEquals(0,f.tasks.appLearningInputs(f.id()).length());f.consent();assertEquals(0,f.tasks.appLearningInputs(f.id()).length()); // model completion remains unknown
  f.tasks.event(f.id(),new JSONObject().put("type","user_verification").put("completed",true));f.tasks.status(f.id(),"completed","owner confirmed");
  assertEquals("7@1234",f.tasks.appLearningInputs(f.id()).getJSONObject(0).getJSONObject("app").getString("appVersion"));
  f.tasks.status(f.id(),"cancelled","cancelled");assertEquals(0,f.tasks.appLearningInputs(f.id()).length());
 }
 @Test public void originalSourceJobIsIdempotentAcrossRestartAndRevocationStopsAdmission()throws Exception{
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<5;i++)f.step("native-observation");f.terminal(true);
  JSONObject input=f.tasks.appLearningInputs(f.id()).getJSONObject(0);SourceRef source=f.tasks.appLearningEvidence(f.id(),input,new JSONObject());JSONObject event=f.tasks.savedMemorySourceEvent(source);String text=TaskSavedEvidence.appLearningText(event);
  var document=new KnowledgeLearningWorkflow.SourceDocument(source,"Original App evidence",text,event.getJSONObject("context"));JSONObject plan=new JSONObject().put("sourceRef",source.toJson()).put("sourceFingerprint",KnowledgeLearningWorkflow.sourceFingerprint(document)).put("title",document.title);
  JSONObject job=f.tasks.createAppLearningJob(f.id(),source,plan);TaskStore reopened=new TaskStore(f.android);assertEquals(job.getString("taskId"),reopened.createAppLearningJob(f.id(),source,plan).getString("taskId"));assertFalse(reopened.sourceLearningJob(job.getString("taskId")).getJSONObject("job").getBoolean("started"));
  assertEquals(source,reopened.appLearningEvidence(f.id(),input,new JSONObject()));assertTrue(reopened.appLearningRecoveryTasks().toString().contains(f.id()));
  reopened.checkAppLearningConsent(job.getString("taskId"));reopened.setAppLearningPolicy(f.id(),1,false,new JSONArray(),1,false);assertThrows(SecurityException.class,()->reopened.checkAppLearningConsent(job.getString("taskId")));assertThrows(SecurityException.class,()->reopened.startSourceLearningJob(job.getString("taskId"),1,new RunControl(),1));
  assertEquals(1,reopened.sourceLearningJob(job.getString("taskId")).getJSONObject("job").getJSONArray("inputs").length());
 }
 @Test public void selectedAppWithoutObservedVersionAndCrossObservationStepsCannotLearn()throws Exception{
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<5;i++)f.step("fabricated-observation");f.terminal(true);assertEquals(0,f.tasks.appLearningInputs(f.id()).length());
  JSONObject missing=new JSONObject().put("packageName","com.nonexistent.app");dev.pocketengine.phone.observation.InstalledAppEvidence.attach(f.android,missing);assertFalse(missing.has("installedApp"));
 }
 @Test public void actualAppSourceDraftStaysCandidateAndNeedsOriginalReviewAfterRestart()throws Exception{
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<5;i++)f.step("native-observation");f.terminal(true);
  SourceRef source=f.tasks.appLearningEvidence(f.id(),f.tasks.appLearningInputs(f.id()).getJSONObject(0),new JSONObject());
  var memory=new MemoryModule(new FeatureContext(f.android,null,new SecretVault(f.android),e->{}),f.tasks::resolveMemorySource);
  KnowledgeLearningWorkflow.SourceReader reader=ref->{JSONObject event=f.tasks.savedMemorySourceEvent(ref);return new KnowledgeLearningWorkflow.SourceDocument(ref,"Native App source",TaskSavedEvidence.appLearningText(event),event.getJSONObject("context"));};
  var fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission a)throws Exception{return a.run();}};
  JSONObject draft=new JSONObject().put("layer","procedural").put("text","- Inspect the current screen before tapping; the route is not independently verified.").put("reason","Original observed App actions, not model success").put("applicability","com.example.learning version 7@1234 only").put("confidence",0.5);
  java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
  var workflow=new KnowledgeLearningWorkflow(memory,f.skills,reader,(messages,control)->{calls.incrementAndGet();assertTrue(messages.getJSONObject(0).getString("content").contains("候选"));return new JSONObject().put("content",draft.toString());},fence);
  RunControl original=new RunControl();JSONObject recorded=workflow.extractRecordedLearning(scope(),source,KnowledgeLearningWorkflow.sourceFingerprint(reader.read(source)),original);
  JSONObject result=workflow.publishRecordedLearning(scope(),recorded,original);assertFalse(result.getBoolean("formalMemorySaved"));assertTrue(result.getBoolean("reviewRequired"));assertEquals(0,memory.snapshot().getInt("recordCount"));
  var reopened=new MemoryModule(new FeatureContext(f.android,null,new SecretVault(f.android),e->{}),f.tasks::resolveMemorySource);JSONObject candidate=result.getJSONObject("candidate");assertEquals("draft",candidate.getJSONObject("appKnowledge").getString("stage"));
  JSONObject accepted=reopened.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");assertEquals("trial",accepted.getJSONObject("appKnowledge").getString("stage"));assertEquals("7@1234",accepted.getJSONObject("appKnowledge").getString("appVersion"));assertEquals(1,calls.get());
  Fixture applied=new Fixture(f.tasks);String step=applied.step("actual-app-observation");var selected=reopened.selectProcedureForNativeTask(scope(),accepted.getString("id"),accepted.getLong("revision"));
  JSONObject decision=new JSONObject().put("recordId",accepted.getString("id")).put("revision",accepted.getLong("revision")).put("decision","apply").put("reason","Used original approved App procedure").put("stepOperationIds",new JSONArray().put(step));
  String op=applied.journal().beginOperation("memory_method_decision",decision);JSONObject actual=applied.tasks.recordLearnedMethodDecision(applied.id(),applied.run(),applied.epoch(),op,selected,applied.control);applied.journal().finishOperation(op,actual);
  var receipt=applied.tasks.reviewAppQuality(applied.id(),op,accepted.getString("id"),accepted.getLong("revision"),"verified","Owner inspected real result");assertEquals("verified",reopened.recordAppQuality(scope(),receipt).getJSONObject("record").getJSONObject("appKnowledge").getString("stage"));
  TaskStore restarted=new TaskStore(f.android);var replay=restarted.reviewAppQuality(applied.id(),op,accepted.getString("id"),accepted.getLong("revision"),"verified","Owner inspected real result");assertEquals(1,reopened.recordAppQuality(scope(),replay).getJSONObject("record").getJSONObject("appKnowledge").getJSONArray("outcomes").length());
  f.tasks.event(f.id(),new JSONObject().put("type","user_verification").put("completed",false));assertThrows(Exception.class,()->reopened.reviewedProcedure(scope(),accepted.getString("id"),accepted.getLong("revision")));

 }

 @Test public void manualAppSelectionCannotUseAnObservedButUntouchedPackage()throws Exception{
  Fixture f=new Fixture();f.observe();f.terminal(true);SourceRef source=f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),"task_evidence","task:"+f.id()+":verified_result").ref;assertEquals(0,f.tasks.appIdentitiesForSource(source).length());
 }

 @Test public void nativeStepLimitCanDraftLessonButBareModelFailureCannot()throws Exception{
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<3;i++)f.step("native-observation");
  f.tasks.endAttempt(f.id(),f.run(),"failed","Model connection error",new JSONObject());assertEquals(0,f.tasks.appLearningInputs(f.id()).length());
  // The runner's own stop-reason, not an LLM-supplied answer or a status label.
  f.tasks.finish(f.id(),"needs_confirmation","Original operation budget ended",new JSONObject().put("nativeStopReason","step_limit").put("nativeStopRunId",f.run()).put("nativeStopEpoch",f.epoch()));assertEquals("not_done",f.tasks.appLearningInputs(f.id()).getJSONObject(0).getString("learningKind"));
 }

 @Test public void twoNativeNoChangeReceiptsMakeThreeStepOwnerResultABumpyLesson()throws Exception{
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<3;i++){String op=f.journal().beginOperation("phone_tap",new JSONObject().put("observationId","native-observation").put("x",20).put("y",20));f.journal().finishOperation(op,new JSONObject().put("ok",true).put("accepted",true).put("effect","no_change_observed"));}f.terminal(true);
  assertEquals("bumpy",f.tasks.appLearningInputs(f.id()).getJSONObject(0).getString("learningKind"));
 }

 static JSONObject appPlan(Fixture f,SourceRef source)throws Exception {
  JSONObject event=f.tasks.savedMemorySourceEvent(source);var doc=new KnowledgeLearningWorkflow.SourceDocument(source,"Original App evidence",TaskSavedEvidence.appLearningText(event),event.getJSONObject("context"));return new JSONObject().put("sourceRef",source.toJson()).put("sourceFingerprint",KnowledgeLearningWorkflow.sourceFingerprint(doc)).put("title",doc.title);
 }
 @Test public void explicitReenableRebindsOnlySameNeverStartedJobAndPersistsAcrossRestart()throws Exception {
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<5;i++)f.step("native-observation");f.terminal(true);
  SourceRef source=f.tasks.appLearningEvidence(f.id(),f.tasks.appLearningInputs(f.id()).getJSONObject(0),new JSONObject());JSONObject plan=appPlan(f,source),before=f.tasks.createAppLearningJob(f.id(),source,plan);
  f.tasks.setAppLearningPolicy(f.id(),1,false,new JSONArray(),1,false);f.tasks.setAppLearningPolicy(f.id(),2,true,new JSONArray().put("com.example.learning"),1,true);
  assertEquals(source,f.tasks.appLearningEvidence(f.id(),f.tasks.appLearningInputs(f.id()).getJSONObject(0),new JSONObject()));JSONObject rebound=f.tasks.createAppLearningJob(f.id(),source,plan);
  assertEquals(before.getString("taskId"),rebound.getString("taskId"));assertEquals(before.getJSONObject("job").getString("manifestHash"),rebound.getJSONObject("job").getString("manifestHash"));assertEquals(2,rebound.getJSONObject("job").getLong("revision"));assertEquals(0,rebound.getJSONObject("job").getInt("reservedModelAttempts"));
  TaskStore restarted=new TaskStore(f.android);restarted.checkAppLearningConsent(rebound.getString("taskId"));assertEquals(3,restarted.sourceLearningJob(rebound.getString("taskId")).getJSONObject("appLearningOrigin").getLong("consentRevision"));
  assertEquals(2,restarted.createAppLearningJob(f.id(),source,plan).getJSONObject("job").getLong("revision"));
 }
 @Test public void reenableCannotReplaceManifestOrAutomaticallyRetryStartedUnknownJob()throws Exception {
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<5;i++)f.step("native-observation");f.terminal(true);SourceRef source=f.tasks.appLearningEvidence(f.id(),f.tasks.appLearningInputs(f.id()).getJSONObject(0),new JSONObject());JSONObject plan=appPlan(f,source),job=f.tasks.createAppLearningJob(f.id(),source,plan);String id=job.getString("taskId");
  f.tasks.setAppLearningPolicy(f.id(),1,false,new JSONArray(),1,false);f.tasks.setAppLearningPolicy(f.id(),2,true,new JSONArray().put("com.example.learning"),1,true);
  JSONObject changed=new JSONObject(plan.toString()).put("sourceFingerprint","sha256:"+"0".repeat(64));assertThrows(SecurityException.class,()->f.tasks.createAppLearningJob(f.id(),source,changed));assertEquals(1,f.tasks.sourceLearningJob(id).getJSONObject("appLearningOrigin").getLong("consentRevision"));
  JSONObject rebound=f.tasks.createAppLearningJob(f.id(),source,plan);RunControl control=new RunControl();JSONObject attempt=f.tasks.startSourceLearningJob(id,rebound.getJSONObject("job").getLong("revision"),control,1);JSONObject active=f.tasks.beginSourceLearningBatch(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),0,control);f.tasks.markSourceLearningUnknown(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),active.getString("operationId"));
  f.tasks.setAppLearningPolicy(f.id(),3,false,new JSONArray(),1,false);f.tasks.setAppLearningPolicy(f.id(),4,true,new JSONArray().put("com.example.learning"),1,true);
  assertThrows(SecurityException.class,()->f.tasks.createAppLearningJob(f.id(),source,plan));JSONObject after=f.tasks.sourceLearningJob(id);assertEquals(3,after.getJSONObject("appLearningOrigin").getLong("consentRevision"));assertEquals(1,after.getJSONObject("job").getInt("reservedModelAttempts"));assertEquals("unknown",after.getJSONObject("active").getString("stage"));assertEquals("prior_call_requires_review",f.tasks.appLearningPolicy(f.id()).getJSONArray("jobs").getJSONObject(0).getString("authorizationStatus"));
 }
 @Test public void reenableForDifferentPackageCannotRebindPriorSource()throws Exception {
  Fixture f=new Fixture();f.consent();f.observe();for(int i=0;i<5;i++)f.step("native-observation");f.terminal(true);SourceRef source=f.tasks.appLearningEvidence(f.id(),f.tasks.appLearningInputs(f.id()).getJSONObject(0),new JSONObject());JSONObject plan=appPlan(f,source),job=f.tasks.createAppLearningJob(f.id(),source,plan);
  f.tasks.setAppLearningPolicy(f.id(),1,false,new JSONArray(),1,false);f.tasks.setAppLearningPolicy(f.id(),2,true,new JSONArray().put("com.example.other"),1,true);assertThrows(SecurityException.class,()->f.tasks.createAppLearningJob(f.id(),source,plan));assertEquals(1,f.tasks.sourceLearningJob(job.getString("taskId")).getJSONObject("appLearningOrigin").getLong("consentRevision"));
 }

}
