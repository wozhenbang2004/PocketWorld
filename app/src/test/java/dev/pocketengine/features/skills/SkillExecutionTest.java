package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Native host/backend protocol fixtures only; this test never launches or claims to sandbox a script. */
public final class SkillExecutionTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private final long[] now={10000};
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222";
    private final String script="print('真实字节😀')\r\n";
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject input()throws Exception{return new JSONObject().put("manifest","---\nname: reviewed-check\ndescription: 主人逐次审阅脚本\nallowed-tools: Bash(*)\n---\n先核对结果。")
            .put("source","local:fixture").put("resources",new JSONObject().put("scripts/check.py",script).put("references/input.txt","精确资源\r\n"));}
    private SkillExecution.Run run(){return new SkillExecution.Run("owner","private",TASK,RUN,1,Set.of());}
    private RunControl control(){RunControl value=new RunControl();value.bindHostRun(TASK,RUN,1);return value;}
    private SkillExecution.Request request(){return request(List.of("a b","$(never-run)","参数"),List.of("input/"),List.of("results/"),List.of("results/report.txt"),4096,2048);}
    private SkillExecution.Request request(List<String> args,List<String> reads,List<String> writes,List<String> outputs,int bytes,int chars){return new SkillExecution.Request("scripts/check.py","python3","workspace-1",".",args,reads,writes,outputs,10,bytes,chars);}
    private SkillExecution.Environment environment(String revision,Set<SkillExecution.Fence> fences){return new SkillExecution.Environment("companion-fixture","installation-1","python3","fixture-1","a".repeat(64),List.of("/usr/bin/python3","-I"),"workspace-1",revision,"/workspace/files","/skill/reviewed-check","isolation-1",fences);}
    private final class Backend implements SkillExecution.Backend {
        SkillExecution.Environment current=environment("revision-1",Set.of(SkillExecution.Fence.values()));
        int submitted,described;
        SkillExecution.Dispatch dispatched;
        boolean fail,wrongReceipt,cancelOnSubmit;
        Runnable onDescribe;
        Runnable onSubmit;
        SkillExecution.Status status=SkillExecution.Status.COMPLETED;
        byte[] output="actual fixture receipt".getBytes(StandardCharsets.UTF_8);
        @Override public SkillExecution.Environment describe(SkillExecution.Run run,SkillExecution.Request request){described++;if(onDescribe!=null)onDescribe.run();return current;}
        @Override public SkillExecution.Receipt submit(SkillExecution.Dispatch dispatch,RunControl control)throws Exception {
            submitted++;dispatched=dispatch;if(onSubmit!=null)onSubmit.run();if(cancelOnSubmit)control.cancel();if(fail)throw new java.io.IOException("untrusted server detail / secret must not leak");
            return new SkillExecution.Receipt(wrongReceipt?UUID.randomUUID().toString():dispatch.grant.operationId,"executor-receipt-1",status,status==SkillExecution.Status.COMPLETED?0:null,output,false);
        }
    }
    private final class Host implements SkillExecution.HostPort {
        boolean approved=true,revoked,failAfterSubmit,allowReceiptRead=true;
        int reserved,checked,reconciled;
        Runnable beforeSubmission;
        final Map<String,SkillExecution.Receipt> operations=new HashMap<>();
        @Override public void checkCurrent(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control)throws Exception {checked++;control.check();if(!approved||revoked)throw new SecurityException("owner approval/backend grant not current");}
        @Override public SkillExecution.Receipt dispatchOnce(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control,SkillExecution.Submission submission)throws Exception {
            checkCurrent(plan,grant,control);
            synchronized(this){SkillExecution.Receipt old=operations.get(grant.operationId);if(old!=null)return old;
                // Commit/release the reservation before entering SkillBook, matching the host contract.
                if(operations.values().stream().anyMatch(receipt->receipt.status==SkillExecution.Status.UNKNOWN))throw new SecurityException("unknown effect blocks a new operation");
                reserved++;operations.put(grant.operationId,SkillExecution.Receipt.unknown(grant.operationId));}
            if(beforeSubmission!=null)beforeSubmission.run();SkillExecution.Receipt receipt=submission.submit();synchronized(this){operations.put(grant.operationId,receipt);}
            if(failAfterSubmit)throw new java.io.IOException("receipt commit/connection interrupted");return receipt;
        }
        @Override public SkillExecution.Receipt reconcile(SkillExecution.Plan plan,SkillExecution.Grant grant){reconciled++;if(!allowReceiptRead)throw new SecurityException("receipt reader not authorized");return operations.get(grant.operationId);}
    }
    private final class Fixture {
        final File root;
        final SkillBook book;
        final JSONObject row;
        final Backend backend=new Backend();
        final Host host=new Host();
        final RunControl control=control();
        final SkillExecution execution;
        Fixture()throws Exception {root=temp.newFolder();book=new SkillBook(root);row=book.install(scope(),input(),now[0]);book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,now[0]);execution=new SkillExecution(book,backend,host,()->now[0]);}
        SkillExecution.Plan plan()throws Exception{return execution.prepare(run(),row.getString("id"),row.getInt("revision"),row.getString("digest"),request(),control);}
        SkillExecution.Grant grant(SkillExecution.Plan plan){return new SkillExecution.Grant(UUID.randomUUID().toString(),"native-owner-approval","backend-grant-1",plan.digest,TASK,RUN,1,SkillExecution.environmentBinding(plan),plan.expiresAt);}
        void disable()throws Exception{book.setEnabled(scope(),row.getString("id"),false,"",false,now[0]);}
    }
    @Test public void planPinsExactWholePackageBytesRuntimeArgvAndOwnerVisibleRestrictions()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();JSONObject review=plan.review();
        assertEquals(SkillExecution.sha256(script.getBytes(StandardCharsets.UTF_8)),review.getString("scriptSha256"));assertEquals(3,review.getJSONArray("resources").length());
        assertEquals(List.of("/usr/bin/python3","-I","/skill/reviewed-check/scripts/check.py","a b","$(never-run)","参数"),plan.argv());
        assertEquals("/workspace/files",plan.cwd());assertEquals("deny",review.getJSONObject("request").getString("network"));assertTrue(review.getBoolean("packageReadOnly"));assertEquals(0,review.getJSONArray("grantsAdded").length());
        String digest=plan.digest;review.getJSONObject("request").put("network","allow");review.getJSONArray("argv").put(0,"/bin/sh");assertEquals(digest,plan.review().getString("planDigest"));assertEquals("deny",plan.review().getJSONObject("request").getString("network"));
        SkillExecution.Grant grant=f.grant(plan);JSONObject result=f.execution.dispatch(plan,grant,f.control);assertEquals("completed",result.getString("status"));assertEquals(1,f.backend.submitted);
        assertArrayEquals(script.getBytes(StandardCharsets.UTF_8),f.backend.dispatched.packageFiles().get("scripts/check.py"));
        Map<String,byte[]> exposed=f.backend.dispatched.packageFiles();exposed.get("scripts/check.py")[0]=0;assertEquals('p',f.backend.dispatched.packageFiles().get("scripts/check.py")[0]);
        assertTrue(result.getBoolean("untrustedOutput"));assertFalse(result.getBoolean("businessVerified"));assertFalse(result.getBoolean("replayAllowed"));
    }
    @Test public void missingNativeHostBackendOrEnforcementNeverBecomesASandbox()throws Exception {
        Fixture f=new Fixture();assertThrows(SkillExecution.Unavailable.class,()->new SkillExecution(f.book,null,f.host,()->now[0]).prepare(run(),f.row.getString("id"),1,f.row.getString("digest"),request(),f.control));
        assertThrows(SkillExecution.Unavailable.class,()->new SkillExecution(f.book,f.backend,null,()->now[0]).prepare(run(),f.row.getString("id"),1,f.row.getString("digest"),request(),f.control));
        f.backend.current=environment("revision-1",Set.of(SkillExecution.Fence.EXACT_ARGV,SkillExecution.Fence.EXACT_BYTES));assertThrows(SkillExecution.Unavailable.class,f::plan);assertEquals(0,f.backend.submitted);
        f.backend.current=null;assertThrows(SkillExecution.Unavailable.class,f::plan);
    }
    @Test public void executionCopyAndImageIdentityEnterTheCanonicalApprovalDigest()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan direct=f.plan();
        f.backend.current=new SkillExecution.Environment("companion-fixture","installation-1","python3","fixture-1","a".repeat(64),List.of("/usr/bin/python3","-I"),
                "workspace-1","revision-1","/workspace/files","/skill/reviewed-check","isolation-1",Set.of(SkillExecution.Fence.values()),"isolated_copy","b".repeat(64));
        SkillExecution.Plan copy=f.plan();assertNotEquals(direct.digest,copy.digest);assertNotEquals(SkillExecution.environmentBinding(direct),SkillExecution.environmentBinding(copy));
        JSONObject b=copy.review().getJSONObject("backend");assertEquals("isolated_copy",b.getString("workspaceMode"));assertTrue(b.getBoolean("fileResultsRequireImport"));
        assertEquals("a".repeat(64),b.getString("runtimeSha256"));assertEquals("b".repeat(64),b.getString("runtimeImageSha256"));
        assertEquals(copy.digest,SkillExecution.sha256(copy.canonicalReviewBytes()));
        assertThrows(SecurityException.class,()->f.execution.dispatch(direct,f.grant(direct),f.control));assertEquals(0,f.backend.submitted);
    }
    @Test public void enablementAllowedToolsAndRequiredCapabilitiesDoNotConferExecutionApproval()throws Exception {
        Fixture f=new Fixture();f.disable();assertThrows(SecurityException.class,f::plan);f.book.setEnabled(scope(),f.row.getString("id"),true,f.row.getString("digest"),true,now[0]);
        SkillExecution.Plan plan=f.plan();f.host.approved=false;assertThrows(SecurityException.class,()->f.execution.dispatch(plan,f.grant(plan),f.control));assertEquals(0,f.host.reserved);assertEquals(0,f.backend.submitted);
        f.disable();JSONObject row=f.book.install(scope(),input().put("requiredCapabilities",new JSONArray().put("workspace_write")),now[0]);f.book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,now[0]);
        assertThrows(SecurityException.class,()->f.execution.prepare(run(),row.getString("id"),1,row.getString("digest"),request(),f.control));
    }
    @Test public void invalidPathsOutputTargetsArgumentsAndEvaluationModesAreRejected()throws Exception {
        for(String path:List.of("../scripts/a.py","scripts/../../x","/scripts/a.py","scripts//a.py","scripts/a\\b.py","scripts/a:stream","SKILL.md"))assertThrows(IllegalArgumentException.class,()->new SkillExecution.Request(path,"python3","workspace-1",".",List.of(),List.of(),List.of(),List.of(),10,1,2048));
        assertThrows(SecurityException.class,()->request(List.of(),List.of(),List.of("results/"),List.of("results-else/report.txt"),10,2048));
        assertThrows(IllegalArgumentException.class,()->request(List.of("x\0y"),List.of(),List.of(),List.of(),10,2048));
        assertThrows(IllegalArgumentException.class,()->request(List.of(),List.of("../input/"),List.of(),List.of(),10,2048));
        assertThrows(SecurityException.class,()->new SkillExecution.Environment("b","i","python3","v","a".repeat(64),List.of("/bin/sh","-c"),"w","r","/work","/skill","f",Set.of(SkillExecution.Fence.values())));
        assertThrows(SecurityException.class,()->new SkillExecution.Environment("b","i","python3","v","a".repeat(64),List.of("/bin/sh"),"w","r","/work","/work/skill","f",Set.of(SkillExecution.Fence.values())));
    }
    @Test public void grantCannotChangePlanRunEpochBackendOrExpiry()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant good=f.grant(plan);
        for(SkillExecution.Grant grant:List.of(
                new SkillExecution.Grant(good.operationId,good.approvalId,good.backendGrantId,"b".repeat(64),TASK,RUN,1,good.environmentBinding,good.expiresAt),
                new SkillExecution.Grant(good.operationId,good.approvalId,good.backendGrantId,plan.digest,TASK,RUN,2,good.environmentBinding,good.expiresAt),
                new SkillExecution.Grant(good.operationId,good.approvalId,good.backendGrantId,plan.digest,TASK,TASK,1,good.environmentBinding,good.expiresAt),
                new SkillExecution.Grant(good.operationId,good.approvalId,good.backendGrantId,plan.digest,TASK,RUN,1,"b".repeat(64),good.expiresAt),
                new SkillExecution.Grant(good.operationId,good.approvalId,good.backendGrantId,plan.digest,TASK,RUN,1,good.environmentBinding,good.expiresAt+1)))assertThrows(SecurityException.class,()->f.execution.dispatch(plan,grant,f.control));
        assertEquals(0,f.host.reserved);assertEquals(0,f.backend.submitted);
    }
    @Test public void sameDigestNewRevisionInvalidatesThePlanEvenAfterFreshEnablement()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan old=f.plan();JSONObject update=input().put("id",f.row.getString("id")).put("revision",1);JSONObject current=f.book.install(scope(),update,now[0]);assertEquals(f.row.getString("digest"),current.getString("digest"));
        f.book.setEnabled(scope(),current.getString("id"),true,current.getString("digest"),true,now[0]);assertThrows(SecurityException.class,()->f.execution.dispatch(old,f.grant(old),f.control));assertEquals(0,f.backend.submitted);
    }
    @Test public void disableAndReenableInSameMillisecondInvalidatesPriorReviewAcrossBookInstances()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan old=f.plan();SkillBook second=new SkillBook(f.root);second.setEnabled(scope(),f.row.getString("id"),false,"",false,now[0]);second.setEnabled(scope(),f.row.getString("id"),true,f.row.getString("digest"),true,now[0]);
        assertThrows(SecurityException.class,()->f.execution.dispatch(old,f.grant(old),f.control));assertNotEquals(old.digest,f.plan().digest);assertEquals(0,f.backend.submitted);
    }
    @Test public void deletedTamperedOrExpiredPackagesNeverDispatch()throws Exception {
        Fixture removed=new Fixture();SkillExecution.Plan rp=removed.plan();removed.book.delete(scope(),removed.row.getString("id"));assertThrows(IllegalArgumentException.class,()->removed.execution.dispatch(rp,removed.grant(rp),removed.control));
        Fixture damaged=new Fixture();SkillExecution.Plan dp=damaged.plan();Files.write(new File(damaged.root,damaged.row.getString("id")+"/1/reviewed-check/scripts/check.py").toPath(),"changed".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalStateException.class,()->damaged.execution.dispatch(dp,damaged.grant(dp),damaged.control));
        Fixture expired=new Fixture();SkillExecution.Plan ep=expired.plan();SkillExecution.Grant grant=expired.grant(ep);now[0]=ep.expiresAt;assertThrows(SecurityException.class,()->expired.execution.dispatch(ep,grant,expired.control));assertEquals(0,expired.backend.submitted);
    }
    @Test public void packageExpiryBoundsReviewAndGrantLifetime()throws Exception {
        Fixture f=new Fixture();f.disable();JSONObject row=f.book.install(scope(),input().put("expiresAt",11000),now[0]);f.book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,now[0]);
        SkillExecution.Plan plan=f.execution.prepare(run(),row.getString("id"),1,row.getString("digest"),request(),f.control);assertEquals(11000,plan.expiresAt);SkillExecution.Grant grant=f.grant(plan);now[0]=11000;assertThrows(SecurityException.class,()->f.execution.dispatch(plan,grant,f.control));
    }
    @Test public void changedRuntimeWorkspaceOrRevokedNativeGrantRequiresNewReview()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();f.backend.current=environment("revision-2",Set.of(SkillExecution.Fence.values()));assertThrows(SecurityException.class,()->f.execution.dispatch(plan,f.grant(plan),f.control));
        f.backend.current=environment("revision-1",Set.of(SkillExecution.Fence.values()));f.host.revoked=true;assertThrows(SecurityException.class,()->f.execution.dispatch(plan,f.grant(plan),f.control));assertEquals(0,f.backend.submitted);
    }
    @Test public void pausedCancelledUnboundAndWrongEpochControlsAreRejected()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant grant=f.grant(plan);f.control.pause();assertThrows(SecurityException.class,()->f.execution.dispatch(plan,grant,f.control));f.control.resume();
        assertThrows(SecurityException.class,()->f.execution.dispatch(plan,grant,new RunControl()));RunControl wrong=new RunControl();wrong.bindHostRun(TASK,RUN,2);assertThrows(SecurityException.class,()->f.execution.dispatch(plan,grant,wrong));
        f.control.cancel();assertThrows(InterruptedException.class,()->f.execution.dispatch(plan,grant,f.control));assertEquals(0,f.backend.submitted);
    }
    @Test public void revalidationAfterJournalReservationRejectsDisableAndCancelRaces()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();f.host.beforeSubmission=()->{try{f.disable();}catch(Exception error){throw new RuntimeException(error);}};
        assertThrows(SecurityException.class,()->f.execution.dispatch(plan,f.grant(plan),f.control));assertEquals(0,f.backend.submitted);
        Fixture g=new Fixture();SkillExecution.Plan gp=g.plan();g.host.beforeSubmission=g.control::cancel;assertThrows(InterruptedException.class,()->g.execution.dispatch(gp,g.grant(gp),g.control));assertEquals(0,g.backend.submitted);
    }
    @Test public void cancelOrExpiryDuringBackendProbeCannotReachSubmit()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();f.backend.onDescribe=f.control::cancel;assertThrows(InterruptedException.class,()->f.execution.dispatch(plan,f.grant(plan),f.control));assertEquals(0,f.backend.submitted);
        Fixture g=new Fixture();SkillExecution.Plan gp=g.plan();g.backend.onDescribe=()->now[0]=gp.expiresAt;assertThrows(SecurityException.class,()->g.execution.dispatch(gp,g.grant(gp),g.control));assertEquals(0,g.backend.submitted);
    }
    @Test public void unknownSubmissionAndLostReceiptNeverReplayTheOperation()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant grant=f.grant(plan);f.backend.fail=true;JSONObject first=f.execution.dispatch(plan,grant,f.control);assertEquals("unknown",first.getString("status"));assertFalse(first.toString().contains("secret"));
        assertEquals("unknown",f.execution.dispatch(plan,grant,f.control).getString("status"));assertEquals(1,f.backend.submitted);assertThrows(SecurityException.class,()->f.execution.dispatch(plan,f.grant(plan),f.control));
        assertEquals("unknown",f.execution.reconcile(plan,grant).getString("status"));assertEquals(1,f.backend.submitted);
        Fixture lost=new Fixture();SkillExecution.Plan lp=lost.plan();SkillExecution.Grant lg=lost.grant(lp);lost.host.failAfterSubmit=true;assertEquals("unknown",lost.execution.dispatch(lp,lg,lost.control).getString("status"));lost.host.failAfterSubmit=false;
        assertEquals("completed",lost.execution.reconcile(lp,lg).getString("status"));assertEquals(1,lost.backend.submitted);
    }
    @Test public void duplicateKnownOperationReturnsItsReceiptWithoutSubmittingAgain()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant grant=f.grant(plan);f.backend.status=SkillExecution.Status.ACCEPTED;
        assertEquals("accepted",f.execution.dispatch(plan,grant,f.control).getString("status"));assertEquals("accepted",f.execution.dispatch(plan,grant,f.control).getString("status"));assertEquals(1,f.backend.submitted);assertEquals(1,f.host.reserved);
    }
    @Test public void boundedEscapedUnicodeOutputRetainsIdentitySafetyAndTimeoutReceipt()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant grant=f.grant(plan);f.backend.status=SkillExecution.Status.TIMED_OUT;f.backend.output=("😀\"\\\n".repeat(20000)).getBytes(StandardCharsets.UTF_8);
        JSONObject result=f.execution.dispatch(plan,grant,f.control);assertEquals("timed_out",result.getString("status"));assertTrue(result.toString().length()<=2048);assertTrue(result.getBoolean("outputTruncated"));assertEquals(grant.operationId,result.getString("operationId"));assertEquals(plan.digest,result.getString("planDigest"));assertTrue(result.getBoolean("untrustedOutput"));assertFalse(result.getBoolean("businessVerified"));
        assertFalse(Character.isHighSurrogate(result.getString("output").charAt(result.getString("output").length()-1)));
    }
    @Test public void mismatchedExecutorReceiptBecomesUnknownWithoutPromotingItsOutput()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant grant=f.grant(plan);f.backend.wrongReceipt=true;JSONObject result=f.execution.dispatch(plan,grant,f.control);assertEquals("unknown",result.getString("status"));assertEquals("",result.getString("output"));assertEquals(SkillExecution.Status.UNKNOWN,f.host.operations.get(grant.operationId).status);
        assertThrows(IllegalArgumentException.class,()->new SkillExecution.Receipt(UUID.randomUUID().toString(),"job",SkillExecution.Status.COMPLETED,null,null,false));
    }
    @Test public void queryOnlyReconciliationRemainsPossibleAfterDisableCancelAndExpiry()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();SkillExecution.Grant grant=f.grant(plan);f.execution.dispatch(plan,grant,f.control);f.disable();f.control.cancel();now[0]=plan.expiresAt+1;
        assertEquals("completed",f.execution.reconcile(plan,grant).getString("status"));assertEquals(1,f.backend.submitted);assertEquals(1,f.host.reconciled);
        f.host.allowReceiptRead=false;assertEquals("unknown",f.execution.reconcile(plan,grant).getString("status"));assertEquals(1,f.backend.submitted);
    }
    @Test public void argumentAndCapabilityCollectionsAreCopiedBeforeReview()throws Exception {
        Fixture f=new Fixture();List<String> args=new ArrayList<>(List.of("original"));SkillExecution.Request request=request(args,List.of(),List.of(),List.of(),32,2048);args.set(0,"mutated");
        SkillExecution.Plan plan=f.execution.prepare(run(),f.row.getString("id"),1,f.row.getString("digest"),request,f.control);assertEquals("original",plan.argv().get(plan.argv().size()-1));
        assertThrows(UnsupportedOperationException.class,()->plan.argv().set(0,"/bin/sh"));
    }
    @Test public void reviewValidationDoesNotApproveReserveOrDispatchAndDetectsRevocation()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();assertEquals(plan.digest,f.execution.validateForReview(plan,f.control));assertEquals(0,f.host.reserved);assertEquals(0,f.backend.submitted);
        f.disable();assertThrows(SecurityException.class,()->f.execution.validateForReview(plan,f.control));
    }
    @Test public void acceptedProcedureSourceChangeInvalidatesExecutionBeforeCleanupHook()throws Exception {
        ExperienceOrigin.Ref ref=new ExperienceOrigin.Ref("procedure-1",1,"owner","private");ExperienceOrigin.State[] source={new ExperienceOrigin.State(ref,ExperienceOrigin.Status.ACTIVE,true)};
        SkillBook book=new SkillBook(temp.newFolder(),(owner,space,id)->source[0]);ExperienceOrigin origin=new ExperienceOrigin(ref,ExperienceOrigin.DraftAuthor.MODEL);
        JSONObject draft=new JSONObject().put("manifest",input().getString("manifest")).put("resources",input().getJSONObject("resources"));
        JSONObject inspection=book.inspectExperience(scope(),origin,draft,new JSONObject(),now[0]);JSONObject row=book.importExperience(scope(),origin,draft,new JSONObject().put("inspectedDigest",inspection.getString("inspectedDigest")),now[0]);book.approveExperience(scope(),row.getString("id"),1,row.getString("digest"),now[0]);
        Backend backend=new Backend();Host host=new Host();RunControl control=control();SkillExecution execution=new SkillExecution(book,backend,host,()->now[0]);SkillExecution.Plan plan=execution.prepare(run(),row.getString("id"),1,row.getString("digest"),request(),control);
        SkillExecution.Grant grant=new SkillExecution.Grant(UUID.randomUUID().toString(),"owner-approval","backend-grant",plan.digest,TASK,RUN,1,SkillExecution.environmentBinding(plan),plan.expiresAt);
        source[0]=new ExperienceOrigin.State(new ExperienceOrigin.Ref("procedure-1",2,"owner","private"),ExperienceOrigin.Status.ACTIVE,true);assertThrows(SecurityException.class,()->execution.dispatch(plan,grant,control));assertEquals(0,backend.submitted);
        source[0]=new ExperienceOrigin.State(ref,ExperienceOrigin.Status.ACTIVE,true);
        backend.onDescribe=()->{if(backend.described==3)source[0]=new ExperienceOrigin.State(new ExperienceOrigin.Ref("procedure-1",2,"owner","private"),ExperienceOrigin.Status.ACTIVE,true);};
        assertThrows(SecurityException.class,()->execution.dispatch(plan,grant,control));assertEquals(0,backend.submitted);
    }
    @Test public void hostReservationLockIsReleasedBeforePackageValidationAndSubmission()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();f.backend.onSubmit=()->assertFalse("Host lock must not span SkillBook callback",Thread.holdsLock(f.host));
        f.execution.dispatch(plan,f.grant(plan),f.control);assertEquals(1,f.backend.submitted);
    }
    @Test public void cancellationDuringSubmissionRetainsReceiptAndDoesNotClaimReversal()throws Exception {
        Fixture f=new Fixture();SkillExecution.Plan plan=f.plan();f.backend.status=SkillExecution.Status.ACCEPTED;f.backend.cancelOnSubmit=true;SkillExecution.Grant grant=f.grant(plan);
        JSONObject result=f.execution.dispatch(plan,grant,f.control);assertEquals("accepted",result.getString("status"));assertTrue(result.getBoolean("cancelRequested"));assertFalse(result.getBoolean("businessVerified"));assertEquals(1,f.backend.submitted);
        assertEquals("accepted",f.execution.reconcile(plan,grant).getString("status"));assertEquals(1,f.backend.submitted);
    }
}
