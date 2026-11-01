package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeamFrozenReviewTest {
    @Test public void manifestOrSkippedTailNeverCountsAsCompleteFrozenContentRead()throws Exception {
        TeamWorkFilesTest.Fixture f=new TeamWorkFilesTest.Fixture();JSONArray refs;try(TeamWorkFiles writer=f.port()){f.prepare(writer);f.execute(writer,"team_write_file",new JSONObject().put("path","src/a.txt").put("content","实际冻结正文与尾部反证").put("expectedVersion",1),true);refs=f.execute(writer,"team_freeze_outputs",new JSONObject(),true).getJSONArray("teamArtifacts");}
        JSONObject review=TeamWorkReview.read(f.workspace,f.binding.getString("teamRunId"),f.project,new JSONArray().put(new JSONObject().put("memberId",f.binding.getString("memberId")).put("assignmentId",f.binding.getString("assignmentId")).put("taskId",f.binding.getString("taskId")).put("status","completed").put("artifacts",refs)));
        JSONObject binding=new JSONObject().put("taskId",TeamWorkFilesTest.id()).put("runId",TeamWorkFilesTest.id()).put("coordinatorEpoch",1).put("memberId","reviewer").put("teamRunId",f.binding.getString("teamRunId")).put("plan",review.getJSONObject("plan")).put("planHash",review.getString("planHash"));RunControl original=new RunControl();original.bindHostRun(binding.getString("taskId"),binding.getString("runId"),1);
        try(TeamFrozenReview port=new TeamFrozenReview(f.workspace,binding,original,(b,t,c)->c.check())){
            port.execute("team_review_manifest",new JSONObject(),original);assertThrows(SecurityException.class,()->port.execute("team_submit_review",new JSONObject().put("verdict","no_blocking_findings").put("summary","未读不能判定"),original));assertFalse(port.proof().snapshot().getBoolean("completeOriginalFilesRead"));String artifact=refs.getJSONObject(0).getString("artifactId");
            port.execute("team_read_review_file",new JSONObject().put("artifactId",artifact).put("offset",5),original);assertFalse(port.proof().snapshot().getBoolean("completeOriginalFilesRead"));
            JSONObject read=port.execute("team_read_review_file",new JSONObject().put("artifactId",artifact).put("length",5),original);assertEquals(refs.getJSONObject(0).getString("sha256"),read.getString("sha256"));assertFalse(port.proof().snapshot().getBoolean("completeOriginalFilesRead"));
            read=port.execute("team_read_review_file",new JSONObject().put("artifactId",artifact).put("offset",5),original);assertTrue(read.getString("body").contains("尾部反证"));assertTrue(port.proof().snapshot().getBoolean("completeOriginalFilesRead"));assertFalse(port.proof().snapshot().getBoolean("verdictSubmitted"));port.execute("team_submit_review",new JSONObject().put("verdict","changes_requested").put("summary","尾部反证应由原作者处理"),original);assertEquals("changes_requested",port.proof().snapshot().getString("verdict"));assertTrue(port.proof().snapshot().getBoolean("verdictSubmitted"));assertFalse(port.proof().snapshot().getBoolean("businessVerified"));
            assertThrows(SecurityException.class,()->port.validate("team_write_file",new JSONObject()));assertThrows(SecurityException.class,()->port.execute("team_read_review_file",new JSONObject().put("artifactId",TeamWorkFilesTest.id()),original));original.cancel();assertThrows(InterruptedException.class,()->port.execute("team_review_manifest",new JSONObject(),original));
        }
    }
}
