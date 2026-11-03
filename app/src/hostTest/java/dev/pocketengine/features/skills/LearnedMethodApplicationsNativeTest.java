package dev.pocketengine.features.skills;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real SkillBook import/approval/opaque Plan and original SQL claim/receipt lifecycle. Backend
 * describe/receipt values are explicit native fixtures; no user script or external server runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class LearnedMethodApplicationsNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SkillBook book;
        final String record=UUID.randomUUID().toString(),task,run,op;final int epoch;final RunControl control=new RunControl();
        final SkillExecution.Plan plan;final SkillExecution.Grant grant;final CompanionSkillBackend.ReceiptRef ref;
        Fixture()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);JSONObject attempt=tasks.startAttempt(tasks.createCapabilityTask("核对方法应用原回执","").getString("id"),"capabilities",6);task=attempt.getString("id");run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);tasks.authorizeTools(task,run,new JSONArray().put("skills_execute"));
            ExperienceOrigin.Ref accepted=new ExperienceOrigin.Ref(record,3,"owner","private");book=new SkillBook(new File(android.getFilesDir(),"method-audit-"+UUID.randomUUID()),(owner,space,id)->new ExperienceOrigin.State(accepted,ExperienceOrigin.Status.ACTIVE,true));
            ExperienceOrigin origin=new ExperienceOrigin(accepted,ExperienceOrigin.DraftAuthor.MODEL);JSONObject draft=new JSONObject().put("manifest","---\nname: method-audit\ndescription: 原方法应用记录的准确来源\n---\n只作为当前范围参考。\n").put("resources",new JSONObject().put("scripts/check.py","raise RuntimeError('not executed by this fixture')\n"));long now=System.currentTimeMillis();
            JSONObject inspect=book.inspectExperience(scope(),origin,draft,new JSONObject(),now);JSONObject skill=book.importExperience(scope(),origin,draft,new JSONObject().put("inspectedDigest",inspect.getString("inspectedDigest")),now);book.approveExperience(scope(),skill.getString("id"),1,skill.getString("digest"),now);
            SkillExecution.Backend backend=new SkillExecution.Backend(){public SkillExecution.Environment describe(SkillExecution.Run run,SkillExecution.Request request){return new SkillExecution.Environment(CompanionSkillBackend.BACKEND,"native-fixture-install","python3","native-fixture-version","a".repeat(64),List.of("/usr/bin/python3","-I"),"workspace-1","snapshot-1","/workspace/input","/skill/package","native-fixture-policy",Set.of(SkillExecution.Fence.values()),"isolated_copy","b".repeat(64));}public SkillExecution.Receipt submit(SkillExecution.Dispatch dispatch,RunControl original){throw new AssertionError("No external dispatch in audit fixture");}};
            SkillExecution.HostPort noDispatch=new SkillExecution.HostPort(){public void checkCurrent(SkillExecution.Plan p,SkillExecution.Grant g,RunControl c){}public SkillExecution.Receipt dispatchOnce(SkillExecution.Plan p,SkillExecution.Grant g,RunControl c,SkillExecution.Submission s){throw new AssertionError("No fake script execution");}public SkillExecution.Receipt reconcile(SkillExecution.Plan p,SkillExecution.Grant g){throw new AssertionError();}};
            SkillExecution execution=new SkillExecution(book,backend,noDispatch,System::currentTimeMillis);plan=execution.prepare(new SkillExecution.Run("owner","private",task,run,epoch,Set.of("skills_execute")),skill.getString("id"),1,skill.getString("digest"),new SkillExecution.Request("scripts/check.py","python3","workspace-1",".",List.of(),List.of(),List.of(),List.of(),10,4096,2048),control);
            op=tasks.journal(task,run,epoch,control).beginOperation("skills_execute",new JSONObject().put("id",skill.getString("id")));grant=new SkillExecution.Grant(op,"native-approval","native-backend-grant",plan.digest,task,run,epoch,SkillExecution.environmentBinding(plan),plan.expiresAt);ref=CompanionSkillBackend.ReceiptRef.from(plan,grant);tasks.reserveSkillExecution(plan,grant,new JSONObject(),control);
        }
        JSONObject page()throws Exception{return tasks.learnedMethodApplications(record,3,"",20);}
        void receipt(SkillExecution.Status status)throws Exception{tasks.recordSkillExecution(ref,new SkillExecution.Receipt(op,"explicit-native-backend-receipt",status,status==SkillExecution.Status.COMPLETED?0:null,"PRIVATE_BACKEND_OUTPUT_NOT_AUDIT_BODY".getBytes(StandardCharsets.UTF_8),false));}
        void claim()throws Exception{tasks.decideSkillExecution(plan,grant,control,true);assertTrue(tasks.claimSkillExecution(plan,grant,ref,control));}
        public void close()throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);((SQLiteDatabase)f.get(tasks)).close();}
    }
    @Test public void originalReviewClaimAndBackendReceiptAreDistinctAndColdAuditNeverInfersBusinessSuccess()throws Exception{
        try(Fixture f=new Fixture()){
            assertEquals("not_applied",f.page().getJSONArray("applications").getJSONObject(0).getString("applicationState"));f.claim();assertEquals("dispatch_claimed",f.page().getJSONArray("applications").getJSONObject(0).getString("applicationState"));f.receipt(SkillExecution.Status.ACCEPTED);assertEquals("backend_accepted",f.page().getJSONArray("applications").getJSONObject(0).getString("applicationState"));f.receipt(SkillExecution.Status.COMPLETED);
            JSONObject row=f.page().getJSONArray("applications").getJSONObject(0);assertEquals("applied_backend_attempt",row.getString("applicationState"));assertFalse(row.getBoolean("methodBusinessSuccessVerified"));assertFalse(row.getBoolean("executionAuthorized"));assertFalse(row.toString().contains("PRIVATE_BACKEND_OUTPUT_NOT_AUDIT_BODY"));assertEquals(f.op,row.getString("operationId"));assertEquals(0,f.tasks.learnedMethodApplications(f.record,4,"",20).getJSONArray("applications").length());
            f.control.cancel();TaskStore cold=new TaskStore(f.android);JSONObject restored=cold.learnedMethodApplications(f.record,3,"",20).getJSONArray("applications").getJSONObject(0);assertEquals(f.op,restored.getString("operationId"));assertEquals("completed",restored.getString("backendOutcome"));assertFalse(restored.getBoolean("replayAllowed"));
        }
    }
    @Test public void oldNonNativeSkillRowsAreExplicitlyUnverifiableNotAppliedOrCompleteHistory()throws Exception{
        try(Fixture f=new Fixture()){
            f.claim();f.receipt(SkillExecution.Status.COMPLETED);var journal=f.tasks.journal(f.task,f.run,f.epoch,f.control);String legacy=journal.beginOperation("skills_execute",new JSONObject().put("id","old-unsupported-row"));journal.finishOperation(legacy,new JSONObject().put("status","not_dispatched"));
            JSONObject page=f.page();assertEquals(1,page.getJSONArray("applications").length());assertEquals(1,page.getInt("unavailableNativeProofs"));assertFalse(page.getBoolean("completeHistory"));assertEquals(f.op,page.getJSONArray("applications").getJSONObject(0).getString("operationId"));assertThrows(IllegalArgumentException.class,()->f.tasks.learnedMethodApplications(f.record,3,"a".repeat(2049),20));assertEquals(2,f.tasks.get(f.task,false).getJSONArray("operations").length());
        }
    }

    @Test public void effectEvidenceUsesFrozenRequestAndEnvironmentAndIgnoresLateBackendCallbacks()throws Exception{
        try(Fixture f=new Fixture()){
            assertEquals(0,f.page().getJSONObject("effectComparison").getInt("eligibleApplications"));
            f.claim();f.receipt(SkillExecution.Status.COMPLETED);
            JSONObject page=f.page(),row=page.getJSONArray("applications").getJSONObject(0),effect=row.getJSONObject("effectEvidence");
            assertTrue(effect.getBoolean("inputComparable"));assertTrue(effect.getBoolean("environmentComparable"));
            assertEquals("frozen_skill_request",effect.getString("inputCoverage"));
            assertEquals("probed_backend_and_workspace_revision",effect.getString("environmentCoverage"));
            assertEquals(1,page.getJSONObject("effectComparison").getInt("eligibleApplications"));
            assertEquals(1,page.getJSONObject("effectComparison").getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0).getInt("unreviewed"));
            JSONObject pack=f.plan.review().getJSONObject("package");
            String note="Owner checked the original output";
            JSONObject saved=f.tasks.reviewSkillQuality(f.task,f.op,pack.getString("id"),pack.getInt("revision"),pack.getString("digest"),"verified",note).snapshot();
            assertEquals(effect.getString("originalReceiptFingerprint"),saved.getString("originalReceiptFingerprint"));
            assertEquals(saved.getString("originalReceiptFingerprint"),f.tasks.reviewSkillQuality(f.task,f.op,pack.getString("id"),pack.getInt("revision"),pack.getString("digest"),"verified",note).snapshot().getString("originalReceiptFingerprint"));
            assertEquals(1,f.page().getJSONObject("effectComparison").getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0).getInt("ownerVerifiedResults"));
            JSONObject legacyOriginal=f.tasks.readSkillExecution(f.task,f.op),legacyFeedback=legacyOriginal.getJSONObject("qualityFeedback");legacyFeedback.remove("originalReceiptFingerprint");
            assertFalse(SkillQualityReceipt.fromRecordedOriginal(legacyOriginal,legacyFeedback).snapshot().has("originalReceiptFingerprint"));
            JSONObject legacyProjection=dev.pocketengine.features.memory.LearnedMethodReceipt.project(legacyOriginal,f.record,3,"owner","private");
            JSONObject legacyComparison=dev.pocketengine.features.memory.MethodEffectComparison.summarize(new JSONArray().put(legacyProjection),false).getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0);
            assertEquals(0,legacyComparison.getInt("ownerVerifiedResults"));assertEquals(1,legacyComparison.getInt("unboundFeedback"));
            JSONObject wrongImplementation=f.tasks.readSkillExecution(f.task,f.op);wrongImplementation.getJSONObject("qualityFeedback").put("digest","f".repeat(64));
            JSONObject wrongReview=dev.pocketengine.features.memory.LearnedMethodReceipt.project(wrongImplementation,f.record,3,"owner","private");
            JSONObject rejectedReview=dev.pocketengine.features.memory.MethodEffectComparison.summarize(new JSONArray().put(wrongReview),false).getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0);
            assertEquals(0,rejectedReview.getInt("ownerVerifiedResults"));assertEquals(1,rejectedReview.getInt("invalidFeedback"));
            JSONObject changedPlan=f.tasks.readSkillExecution(f.task,f.op);changedPlan.getJSONObject("review").getJSONObject("request").put("workingDirectory","changed-input");
            assertThrows(SecurityException.class,()->dev.pocketengine.features.memory.LearnedMethodReceipt.project(changedPlan,f.record,3,"owner","private"));
            f.receipt(SkillExecution.Status.RUNNING);f.receipt(SkillExecution.Status.FAILED);
            JSONObject unchanged=f.page().getJSONArray("applications").getJSONObject(0).getJSONObject("effectEvidence");
            assertEquals(effect.getString("evidenceFingerprint"),unchanged.getString("evidenceFingerprint"));
            assertFalse(unchanged.getBoolean("businessSuccessVerified"));assertFalse(unchanged.getBoolean("replayAllowed"));
            // The existing host may receive updated output under the same terminal status. The
            // original owner judgement remains readable but cannot verify these different bytes.
            f.tasks.recordSkillExecution(f.ref,new SkillExecution.Receipt(f.op,"explicit-native-backend-receipt",SkillExecution.Status.COMPLETED,0,"changed original terminal output".getBytes(StandardCharsets.UTF_8),false));
            JSONObject changed=f.page();assertNotEquals(effect.getString("evidenceFingerprint"),changed.getJSONArray("applications").getJSONObject(0).getJSONObject("effectEvidence").getString("evidenceFingerprint"));
            JSONObject changedComparison=changed.getJSONObject("effectComparison").getJSONArray("groups").getJSONObject(0).getJSONArray("methods").getJSONObject(0);
            assertEquals(0,changedComparison.getInt("ownerVerifiedResults"));assertEquals(1,changedComparison.getInt("invalidFeedback"));
            assertThrows(SecurityException.class,()->f.tasks.reviewSkillQuality(f.task,f.op,pack.getString("id"),pack.getInt("revision"),pack.getString("digest"),"verified",note));
        }
    }
}
