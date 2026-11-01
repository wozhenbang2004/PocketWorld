package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual versioned target/artifact files. The original operation journal and owner decisions are explicit fixtures, not SQL acceptance. */
public final class TeamIntegrationRecoveryTest {
    private static final class Fixture {
        final TeamWorkFilesTest.Fixture files=new TeamWorkFilesTest.Fixture();final JSONArray refs;final JSONObject review,binding;
        final TeamWorkFilesTest.PreparedHost journal=new TeamWorkFilesTest.PreparedHost();final RunControl control=new RunControl();
        int targetWrites;String operation;
        Fixture()throws Exception {this(false);}
        Fixture(boolean twoFiles)throws Exception {
            files.binding.put("assignmentId",files.binding.getString("taskId"));
            try(TeamWorkFiles member=files.port()){
                files.prepare(member);files.execute(member,"team_write_file",new JSONObject().put("path","src/a.txt").put("content","成员冻结成果\n").put("expectedVersion",1),true);
                if(twoFiles)files.execute(member,"team_write_file",new JSONObject().put("path","new.txt").put("content","第二份真实成果\n").put("expectedVersion",0),true);
                refs=files.execute(member,"team_freeze_outputs",new JSONObject(),true).getJSONArray("teamArtifacts");
            }
            review=TeamWorkReview.read(files.workspace,files.binding.getString("teamRunId"),files.project,assignments());String task=TeamWorkFilesTest.id();
            binding=new JSONObject().put("taskId",task).put("assignmentId",task).put("runId",TeamWorkFilesTest.id()).put("coordinatorEpoch",1).put("teamRunId",files.binding.getString("teamRunId")).put("memberId","integrator").put("targetProjectId",files.project).put("reviewId",TeamWorkFilesTest.id()).put("plan",review.getJSONObject("plan")).put("planHash",review.getString("planHash"));
            control.bindHostRun(task,binding.getString("runId"),1);
        }
        JSONArray assignments()throws Exception{return new JSONArray().put(new JSONObject().put("memberId",files.binding.getString("memberId")).put("assignmentId",files.binding.getString("assignmentId")).put("taskId",files.binding.getString("taskId")).put("status","completed").put("artifacts",refs));}
        JSONObject context()throws Exception{return new JSONObject().put("teamRunId",binding.getString("teamRunId")).put("targetProjectId",files.project).put("taskId",binding.getString("taskId")).put("reviewId",binding.getString("reviewId")).put("planHash",binding.getString("planHash")).put("activeAttempt",false).put("hasUnresolved",false).put("status","interrupted").put("receipts",journal.receipts("team_integrate_file"));}
        JSONObject recovery(JSONObject context)throws Exception{return TeamWorkReview.prepareRecovery(files.workspace,binding.getString("teamRunId"),files.project,assignments(),context).snapshot();}
        TeamIntegrationFiles port()throws Exception{return port(binding,control);}
        TeamIntegrationFiles port(JSONObject identity,RunControl live)throws Exception{return new TeamIntegrationFiles((command,args)->{if(command.equals("write")&&files.project.equals(args.optString("projectId")))targetWrites++;return files.workspace.call(command,args);},journal,live,identity);}
        JSONObject continuedBinding(JSONObject review)throws Exception{return new JSONObject(binding.toString()).put("runId",TeamWorkFilesTest.id()).put("coordinatorEpoch",2).put("reviewId",TeamWorkFilesTest.id()).put("plan",review.getJSONObject("plan")).put("planHash",review.getString("planHash"));}
        JSONObject invoke(TeamIntegrationFiles integration)throws Exception {
            return invoke(integration,control,refs.getJSONObject(0),true);
        }
        JSONObject invoke(TeamIntegrationFiles integration,RunControl live,JSONObject ref,boolean approved)throws Exception {JSONObject args=new JSONObject().put("path",ref.getString("path")).put("artifactId",ref.getString("artifactId"));operation=journal.prepare("team_integrate_file",args,approved);JSONObject result=integration.execute("team_integrate_file",new JSONObject(args.toString()).put("_hostOperationId",operation),live);journal.settle(operation,result);return result;}
    }
    @Test public void successfulHistoryDoesNotClaimTheCurrentTargetAfterTheOwnerChangedItsHead()throws Exception {
        Fixture f=new Fixture();try(TeamIntegrationFiles integration=f.port()){
            assertTrue(f.invoke(integration).getBoolean("ok"));JSONObject edited=f.files.files.write(f.files.project,"src/a.txt","主人之后修改的实际内容\n",2,false);
            JSONObject replay=f.invoke(integration);assertEquals("team_integration_conflict",replay.optString("code"));assertFalse(replay.optBoolean("accepted",true));assertTrue(replay.optBoolean("requiresPause"));assertFalse(replay.optBoolean("known"));
            assertEquals(1,f.targetWrites);assertFalse(f.journal.operations.get(f.operation).has("capability"));assertEquals(edited.getInt("version"),f.files.files.read(f.files.project,"src/a.txt",0).getInt("version"));assertEquals("主人之后修改的实际内容\n",f.files.files.read(f.files.project,"src/a.txt",0).getString("content"));
        }
    }
    @Test public void evenIdenticalBytesAtANewerHeadRequireANewReviewRatherThanAnOldSuccessReceipt()throws Exception {
        Fixture f=new Fixture();try(TeamIntegrationFiles integration=f.port()){
            assertTrue(f.invoke(integration).getBoolean("ok"));f.files.files.write(f.files.project,"src/a.txt","成员冻结成果\n",2,false);JSONObject replay=f.invoke(integration);
            assertEquals("team_integration_conflict",replay.optString("code"));assertEquals(1,f.targetWrites);assertEquals(3,f.files.files.read(f.files.project,"src/a.txt",0).getInt("version"));
        }
    }
    @Test public void explicitRecoveryReviewsPartialSuccessAndOnlyTheRemainingFileAtItsCurrentCas()throws Exception {
        Fixture f=new Fixture(true);JSONObject applied;try(TeamIntegrationFiles integration=f.port()){applied=f.invoke(integration).getJSONObject("teamIntegrated");}
        assertFalse(TeamWorkReview.prepare(f.files.workspace,f.binding.getString("teamRunId"),f.files.project,f.assignments()).snapshot().getBoolean("canIntegrate"));
        JSONObject recovery=f.recovery(f.context());assertTrue("partial success must be re-reviewable",recovery.getBoolean("canIntegrate"));assertNotEquals(f.review.getString("planHash"),recovery.getString("planHash"));
        JSONArray retained=recovery.getJSONObject("plan").getJSONArray("retained");assertEquals(1,retained.length());assertEquals(TeamWorkFiles.canonical(applied),TeamWorkFiles.canonical(retained.getJSONObject(0)));
        assertEquals(2,recovery.getJSONObject("plan").getJSONArray("files").getJSONObject(0).getInt("expectedVersion"));assertEquals(0,recovery.getJSONObject("plan").getJSONArray("files").getJSONObject(1).getInt("expectedVersion"));
        assertEquals("retain_applied",recovery.getJSONArray("diffs").getJSONObject(0).getString("disposition"));assertEquals("apply_reviewed",recovery.getJSONArray("diffs").getJSONObject(1).getString("disposition"));assertEquals(1,f.targetWrites);
    }
    @Test public void explicitRecoveryShowsCurrentOwnerChangesAndDoesNotRetainAnObsoleteAppliedHead()throws Exception {
        Fixture f=new Fixture();try(TeamIntegrationFiles integration=f.port()){assertTrue(f.invoke(integration).getBoolean("ok"));}f.files.files.write(f.files.project,"src/a.txt","主人后来保留的内容\n",2,false);
        JSONObject recovery=f.recovery(f.context());assertTrue("owner must be able to review the new actual diff",recovery.getBoolean("canIntegrate"));assertEquals(0,recovery.getJSONObject("plan").getJSONArray("retained").length());
        JSONObject file=recovery.getJSONObject("plan").getJSONArray("files").getJSONObject(0);assertEquals(3,file.getInt("expectedVersion"));assertEquals(TeamWorkFiles.hash("主人后来保留的内容\n"),file.getString("expectedHash"));assertTrue(recovery.getJSONArray("diffs").getJSONObject(0).getString("diff").contains("-主人后来保留的内容"));assertEquals(1,f.targetWrites);
    }
    @Test public void stoppedUniqueIntegratorContinuesTheSameTaskAndRetainsSuccessfulFilesWithoutReplayingThem()throws Exception {
        Fixture f=new Fixture(true);JSONObject original;try(TeamIntegrationFiles first=f.port()){original=f.invoke(first).getJSONObject("teamIntegrated");f.control.cancel();assertThrows(InterruptedException.class,()->f.invoke(first));}
        JSONObject review=f.recovery(f.context().put("status","cancelled")),next=f.continuedBinding(review);RunControl resumed=new RunControl();resumed.bindHostRun(next.getString("taskId"),next.getString("runId"),2);assertEquals(f.binding.getString("taskId"),next.getString("taskId"));assertNotEquals(f.binding.getString("runId"),next.getString("runId"));
        try(TeamIntegrationFiles integration=f.port(next,resumed)){
            JSONObject retained=f.invoke(integration,resumed,f.refs.getJSONObject(0),false);assertTrue(retained.getBoolean("known"));assertTrue(retained.has("teamRetained"));assertEquals(TeamWorkFiles.canonical(original),TeamWorkFiles.canonical(retained.getJSONObject("teamRetained").getJSONObject("teamIntegrated")));assertFalse(f.journal.operations.get(f.operation).has("capability"));assertEquals(1,f.targetWrites);
            JSONObject partial=TeamIntegrationFiles.progress(f.files.workspace,next,f.journal.receipts("team_integrate_file"));assertFalse(partial.getBoolean("complete"));assertEquals(1,partial.getInt("retained"));assertEquals("new.txt",partial.getJSONArray("remaining").getString(0));
            assertEquals("approval_required",f.invoke(integration,resumed,f.refs.getJSONObject(1),false).getString("code"));assertEquals(1,f.targetWrites);assertTrue(f.invoke(integration,resumed,f.refs.getJSONObject(1),true).getBoolean("ok"));assertEquals(2,f.targetWrites);assertEquals(2,f.files.files.read(f.files.project,"src/a.txt",0).getInt("version"));assertEquals("第二份真实成果\n",f.files.files.read(f.files.project,"new.txt",0).getString("content"));
            JSONObject complete=TeamIntegrationFiles.progress(f.files.workspace,next,f.journal.receipts("team_integrate_file"));assertTrue(complete.getBoolean("complete"));assertEquals(1,complete.getInt("applied"));assertEquals(1,complete.getInt("retained"));assertFalse(complete.getBoolean("businessVerified"));
        }
    }
    @Test public void reviewedNewCasMayReplaceChangedTargetButOldSuccessRemainsHistorical()throws Exception {
        Fixture f=new Fixture();JSONObject original;try(TeamIntegrationFiles first=f.port()){original=f.invoke(first).getJSONObject("teamIntegrated");}f.files.files.write(f.files.project,"src/a.txt","主人重新编辑\n",2,false);
        JSONObject next=f.continuedBinding(f.recovery(f.context()));RunControl resumed=new RunControl();resumed.bindHostRun(next.getString("taskId"),next.getString("runId"),2);
        try(TeamIntegrationFiles integration=f.port(next,resumed)){
            assertEquals("approval_required",f.invoke(integration,resumed,f.refs.getJSONObject(0),false).getString("code"));assertEquals("主人重新编辑\n",f.files.files.read(f.files.project,"src/a.txt",0).getString("content"));
            JSONObject result=f.invoke(integration,resumed,f.refs.getJSONObject(0),true);assertTrue(result.getBoolean("ok"));assertEquals(4,result.getJSONObject("teamIntegrated").getInt("version"));assertEquals(next.getString("planHash"),result.getJSONObject("teamIntegrated").getString("planHash"));assertEquals(2,original.getInt("version"));assertEquals(f.binding.getString("planHash"),original.getString("planHash"));assertEquals(2,f.targetWrites);
        }
    }
    @Test public void unresolvedOrActiveOriginalAttemptNeverBecomesRecoverableFromMatchingTargetBytes()throws Exception {
        Fixture f=new Fixture();TeamWorkFiles.Workspace actual=f.files.workspace;f.files.workspace=(command,args)->{JSONObject value=actual.call(command,args);if(command.equals("write")&&f.files.project.equals(args.optString("projectId")))throw new java.io.IOException("actual write committed but original receipt lost");return value;};
        try(TeamIntegrationFiles first=f.port()){assertEquals("unknown",f.invoke(first).getString("status"));}assertEquals("成员冻结成果\n",f.files.files.read(f.files.project,"src/a.txt",0).getString("content"));
        JSONObject blocked=f.recovery(f.context().put("hasUnresolved",true).put("status","blocked_unknown"));assertFalse(blocked.getBoolean("canIntegrate"));assertEquals(0,blocked.getJSONObject("plan").getJSONArray("retained").length());assertTrue(blocked.getString("blockedReason").contains("未知"));
        JSONObject active=f.recovery(f.context().put("activeAttempt",true).put("status","paused"));assertFalse(active.getBoolean("canIntegrate"));assertTrue(active.getString("blockedReason").contains("尚未结束"));assertEquals(1,f.targetWrites);
    }
    @Test public void readbackProgressDoesNotCountHistoricalSuccessOrMatchingBytesAsCurrentCompletion()throws Exception {
        Fixture f=new Fixture();try(TeamIntegrationFiles integration=f.port()){assertTrue(f.invoke(integration).getBoolean("ok"));}assertTrue(TeamIntegrationFiles.progress(f.files.workspace,f.binding,f.journal.receipts("team_integrate_file")).getBoolean("complete"));
        f.files.files.write(f.files.project,"src/a.txt","成员冻结成果\n",2,false);JSONObject current=TeamIntegrationFiles.progress(f.files.workspace,f.binding,f.journal.receipts("team_integrate_file"));assertFalse(current.getBoolean("complete"));assertEquals(0,current.getInt("applied"));assertEquals("src/a.txt",current.getJSONArray("conflicts").getString(0));
    }
    @Test public void retainedProofCannotBeUsedForAnotherTaskOrWithoutTheOriginalNativeReceipt()throws Exception {
        Fixture f=new Fixture();try(TeamIntegrationFiles integration=f.port()){assertTrue(f.invoke(integration).getBoolean("ok"));}JSONObject next=f.continuedBinding(f.recovery(f.context()));RunControl resumed=new RunControl();resumed.bindHostRun(next.getString("taskId"),next.getString("runId"),2);
        JSONObject foreign=new JSONObject(next.toString()).put("taskId",TeamWorkFilesTest.id());RunControl other=new RunControl();other.bindHostRun(foreign.getString("taskId"),foreign.getString("runId"),2);assertThrows(SecurityException.class,()->f.port(foreign,other));
        f.journal.operations.clear();try(TeamIntegrationFiles integration=f.port(next,resumed)){assertThrows(SecurityException.class,()->f.invoke(integration,resumed,f.refs.getJSONObject(0),true));}assertEquals(1,f.targetWrites);assertFalse(TeamIntegrationFiles.progress(f.files.workspace,next,new JSONArray()).getBoolean("complete"));
    }
    @Test public void targetChangingAfterRecoveryReviewStillConflictsBeforeClaim()throws Exception {
        Fixture f=new Fixture();f.files.files.write(f.files.project,"src/a.txt","审查前的主人版本",1,false);JSONObject next=f.continuedBinding(f.recovery(f.context()));RunControl resumed=new RunControl();resumed.bindHostRun(next.getString("taskId"),next.getString("runId"),2);f.files.files.write(f.files.project,"src/a.txt","审查后的主人新版本",2,false);
        try(TeamIntegrationFiles integration=f.port(next,resumed)){assertEquals("team_integration_conflict",f.invoke(integration,resumed,f.refs.getJSONObject(0),true).getString("code"));assertFalse(f.journal.operations.get(f.operation).has("capability"));assertEquals(0,f.targetWrites);assertEquals("审查后的主人新版本",f.files.files.read(f.files.project,"src/a.txt",0).getString("content"));}
    }
}
