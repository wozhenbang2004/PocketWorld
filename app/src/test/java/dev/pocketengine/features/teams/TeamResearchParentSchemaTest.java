package dev.pocketengine.features.teams;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Only schemas/selectors. Native approval and source/SQL proof are covered by the real parent journey. */
public final class TeamResearchParentSchemaTest {
    @Test public void researchOutputHasBoundedOriginalPhaseAndVersionedPages()throws Exception {
        JSONObject args=new JSONObject().put("teamRunId","original").put("view","member_output").put("memberId","a").put("researchRound",1).put("offset",0).put("length",8000);
        TeamParentTools.validate("teams_get",args);assertEquals(8000,TeamParentTools.parameters("teams_get").getJSONObject("properties").getJSONObject("length").getInt("maximum"));
        args.put("offset",1);assertThrows(IllegalArgumentException.class,()->TeamParentTools.validate("teams_get",args));args.put("expectedOutputHash","0".repeat(64));TeamParentTools.validate("teams_get",args);
    }
    @Test public void researchSummaryCannotPickAnotherTaskOrMember()throws Exception {
        JSONObject args=new JSONObject().put("teamRunId","original").put("view","research_summary").put("taskId","foreign");assertThrows(SecurityException.class,()->TeamParentTools.validate("teams_get",args));args.remove("taskId");args.put("memberId","foreign");assertThrows(SecurityException.class,()->TeamParentTools.validate("teams_get",args));
    }
    @Test public void researchResumeIsExplicitAndCannotCarryAWriterTuple()throws Exception {
        JSONObject args=new JSONObject().put("teamRunId","original").put("action","resume").put("expectedTeamRevision",1).put("expectedGeneration",0).put("memberId","a").put("expectedMemberGeneration",0).put("researchRound",1);TeamParentTools.validate("teams_control",args);args.put("workItemId","writer");assertThrows(SecurityException.class,()->TeamParentTools.validate("teams_control",args));
    }
    @Test public void originalProjectResumeStillRequiresTheExactWorkItemTuple()throws Exception {
        JSONObject args=new JSONObject().put("teamRunId","original").put("action","resume").put("expectedTeamRevision",1).put("expectedGeneration",0).put("memberId","a").put("expectedMemberGeneration",0);assertThrows(IllegalArgumentException.class,()->TeamParentTools.validate("teams_control",args));args.put("workItemId","original-item").put("itemRevision",1).put("ownerGeneration",0);TeamParentTools.validate("teams_control",args);
    }
    @Test public void researchContinueCannotBorrowQuestionOrAssignmentAuthority()throws Exception {
        JSONObject args=new JSONObject().put("teamRunId","original").put("memberId","a").put("expectedTeamRevision",1).put("expectedGeneration",0).put("expectedMemberGeneration",0).put("delivery","continue").put("messageId","stable").put("text","public target only").put("researchRound",1);TeamParentTools.validate("teams_message",args);args.put("assignmentId","foreign");assertThrows(SecurityException.class,()->TeamParentTools.validate("teams_message",args));
    }
    @Test public void sourceHistoryWrapperForwardsEveryOriginalJournalHook()throws Exception {
        final int[] calls={0};dev.pocketengine.core.RunJournal original=new dev.pocketengine.core.RunJournal(){
            public JSONObject restore()throws Exception{return new JSONObject().put("original",true);}public void checkpoint(org.json.JSONArray m,int s,JSONObject o){calls[0]++;}public String beginOperation(String t,JSONObject a){return "original-op";}public void finishOperation(String o,JSONObject r){calls[0]++;}
            public boolean reserveModelRecovery(String call,long wait){return true;}public int executionStepCeiling(int captured){return 17;}public boolean hasNativeProjectRunningAck(String o,JSONObject r){return true;}public JSONObject projectServiceExitEvidence(org.json.JSONArray ids)throws Exception{return new JSONObject().put("original",true);}
        };
        var same=TeamParentResearchHistory.journal(original,()->{});assertTrue(same.restore().getBoolean("original"));assertEquals("original-op",same.beginOperation("x",new JSONObject()));same.checkpoint(new org.json.JSONArray(),0,new JSONObject());same.finishOperation("x",new JSONObject());assertEquals(2,calls[0]);assertTrue(same.reserveModelRecovery("call",1));assertEquals(17,same.executionStepCeiling(1));assertTrue(same.hasNativeProjectRunningAck("x",new JSONObject()));assertTrue(same.projectServiceExitEvidence(new org.json.JSONArray()).getBoolean("original"));
    }
}
