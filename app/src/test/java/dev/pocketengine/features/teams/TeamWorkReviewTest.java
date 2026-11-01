package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual persisted workspace/artifact bytes and CAS. Journal and owner decisions are explicit fixtures. */
public final class TeamWorkReviewTest {
    private JSONObject freeze(TeamWorkFilesTest.Fixture f)throws Exception {try(TeamWorkFiles member=f.port()){f.prepare(member);f.execute(member,"team_write_file",new JSONObject().put("path","src/a.txt").put("content","实际成员修改\n").put("expectedVersion",1),true);return f.execute(member,"team_freeze_outputs",new JSONObject(),true).getJSONArray("teamArtifacts").getJSONObject(0);}}
    private JSONObject review(TeamWorkFilesTest.Fixture f,JSONObject ref)throws Exception {return TeamWorkReview.read(f.workspace,f.binding.getString("teamRunId"),f.project,new JSONArray().put(new JSONObject().put("memberId",f.binding.getString("memberId")).put("assignmentId",f.binding.getString("assignmentId")).put("taskId",f.binding.getString("taskId")).put("status","completed").put("artifacts",new JSONArray().put(ref))));}
    private JSONObject binding(TeamWorkFilesTest.Fixture f,JSONObject review)throws Exception {return new JSONObject().put("taskId",TeamWorkFilesTest.id()).put("runId",TeamWorkFilesTest.id()).put("coordinatorEpoch",1).put("teamRunId",f.binding.getString("teamRunId")).put("assignmentId",TeamWorkFilesTest.id()).put("memberId","integrator").put("targetProjectId",f.project).put("reviewId",TeamWorkFilesTest.id()).put("plan",review.getJSONObject("plan")).put("planHash",review.getString("planHash"));}
    private JSONObject invoke(TeamIntegrationFiles port,TeamWorkFilesTest.PreparedHost host,RunControl control,JSONObject ref,boolean approved)throws Exception {JSONObject args=new JSONObject().put("path",ref.getString("path")).put("artifactId",ref.getString("artifactId"));String operation=host.prepare("team_integrate_file",args,approved);JSONObject result=port.execute("team_integrate_file",new JSONObject(args.toString()).put("_hostOperationId",operation),control);host.settle(operation,result);return result;}
    @Test public void actualDiffThenReviewedOriginalArtifactCanBeIntegratedOnceByOriginalPreparedOperation()throws Exception {
        TeamWorkFilesTest.Fixture f=new TeamWorkFilesTest.Fixture();JSONObject ref=freeze(f),review=review(f,ref);assertTrue(review.getBoolean("canIntegrate"));String diff=review.getJSONArray("diffs").getJSONObject(0).getString("diff");assertTrue(diff.contains("-原文件"));assertTrue(diff.contains("+实际成员修改"));assertFalse(review.getBoolean("atomicBatch"));
        JSONObject binding=binding(f,review);RunControl control=new RunControl();control.bindHostRun(binding.getString("taskId"),binding.getString("runId"),1);TeamWorkFilesTest.PreparedHost host=new TeamWorkFilesTest.PreparedHost();
        try(TeamIntegrationFiles integration=new TeamIntegrationFiles(f.workspace,host,control,binding)){
            assertEquals("approval_required",invoke(integration,host,control,ref,false).getString("code"));assertEquals(1,f.files.read(f.project,"src/a.txt",0).getInt("version"));
            JSONObject applied=invoke(integration,host,control,ref,true);assertTrue(applied.getBoolean("ok"));assertEquals("实际成员修改\n",f.files.read(f.project,"src/a.txt",0).getString("content"));assertEquals(2,f.files.read(f.project,"src/a.txt",0).getInt("version"));
            assertTrue(invoke(integration,host,control,ref,true).getBoolean("known"));assertEquals(2,f.files.read(f.project,"src/a.txt",0).getInt("version"));assertEquals("其他成员文件\n",f.files.read(f.project,"src/b.txt",0).getString("content"));
            assertThrows(SecurityException.class,()->integration.validate("team_integrate_file",new JSONObject().put("path","src/b.txt").put("artifactId",ref.getString("artifactId"))));
        }
    }
    @Test public void currentCanonicalChangeAfterReviewIsAConflictWithoutFileDispatch()throws Exception {
        TeamWorkFilesTest.Fixture f=new TeamWorkFilesTest.Fixture();JSONObject ref=freeze(f),review=review(f,ref),binding=binding(f,review);f.files.write(f.project,"src/a.txt","主人后来编辑\n",1,false);RunControl control=new RunControl();control.bindHostRun(binding.getString("taskId"),binding.getString("runId"),1);TeamWorkFilesTest.PreparedHost host=new TeamWorkFilesTest.PreparedHost();
        try(TeamIntegrationFiles integration=new TeamIntegrationFiles(f.workspace,host,control,binding)){JSONObject result=invoke(integration,host,control,ref,true);assertEquals("team_integration_conflict",result.getString("code"));assertFalse(result.getBoolean("accepted"));assertTrue(result.getBoolean("requiresPause"));assertEquals("主人后来编辑\n",f.files.read(f.project,"src/a.txt",0).getString("content"));}
        assertFalse(review(f,ref).getBoolean("canIntegrate"));
    }
    @Test public void artifactBytesAreReadAndVerifiedRatherThanTrustingTheManifest()throws Exception {
        TeamWorkFilesTest.Fixture f=new TeamWorkFilesTest.Fixture();JSONObject ref=freeze(f);java.nio.file.Path file=f.root.toPath().resolve("artifacts").resolve(ref.getString("artifactId")+".json");JSONObject raw=new JSONObject(Files.readString(file));raw.put("content","篡改内容");Files.writeString(file,raw.toString());assertThrows(IllegalStateException.class,()->review(f,ref));
    }
    @Test public void lostCanonicalWriteReceiptStaysUnknownAndDoesNotClaimBatchSuccess()throws Exception {
        TeamWorkFilesTest.Fixture f=new TeamWorkFilesTest.Fixture();JSONObject ref=freeze(f),review=review(f,ref),binding=binding(f,review);RunControl control=new RunControl();control.bindHostRun(binding.getString("taskId"),binding.getString("runId"),1);TeamWorkFilesTest.PreparedHost host=new TeamWorkFilesTest.PreparedHost();TeamWorkFiles.Workspace actual=f.workspace;
        try(TeamIntegrationFiles integration=new TeamIntegrationFiles((name,args)->{JSONObject result=actual.call(name,args);if(name.equals("write")&&args.getString("projectId").equals(f.project))throw new java.io.IOException("fixture reply lost after actual CAS commit");return result;},host,control,binding)){
            JSONObject result=invoke(integration,host,control,ref,true);assertEquals("unknown",result.getString("status"));assertTrue(result.isNull("accepted"));assertEquals("实际成员修改\n",f.files.read(f.project,"src/a.txt",0).getString("content"));assertEquals(2,f.files.read(f.project,"src/a.txt",0).getInt("version"));
        }
    }
}
