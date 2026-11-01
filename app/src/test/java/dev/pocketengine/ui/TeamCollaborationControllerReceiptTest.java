package dev.pocketengine.ui;

import android.app.Application;
import dev.pocketengine.EngineApplication;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import kotlin.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamCollaborationControllerReceiptTest {
    @SuppressWarnings("unchecked") private Map<String,Pair<String,JSONObject>> pending(PocketWorldController controller)throws Exception{Field f=PocketWorldController.class.getDeclaredField("pending");f.setAccessible(true);return (Map<String,Pair<String,JSONObject>>)f.get(controller);}
    private JSONObject input(String run)throws Exception{return new JSONObject().put("runId",run).put("memberId","a").put("expectedGeneration",3).put("expectedRevision",12L).put("expectedMemberGeneration",6).put("text","主人尚未保存的补充");}
    private JSONObject event(String request,String feature,String command,boolean ok)throws Exception{return new JSONObject().put("requestId",request).put("feature",feature).put("command",command).put("ok",ok);}
    private JSONObject run(String id)throws Exception{return new JSONObject().put("id",id).put("goal","实际团队目标").put("status","paused").put("generation",3).put("revision",13).put("members",new JSONArray());}
    private void receive(PocketWorldController controller,JSONObject event)throws Exception{Method m=PocketWorldController.class.getDeclaredMethod("featureResult",JSONObject.class);m.setAccessible(true);m.invoke(controller,event);}
    @Test public void failedNativeReplyRetainsExactInputIdentityAndCasMetadata()throws Exception{
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{pending(controller).put("native-a",new Pair<>("teams.feedback",input("run-a")));receive(controller,event("native-a","teams","feedback",false).put("message","快照已改变"));JSONObject actual=controller.getState().getModuleResultInputs().get("teams.feedback");assertNotNull("failure must keep native identity",actual);assertEquals("native-a",actual.getString("_requestId"));assertEquals("run-a",actual.getString("runId"));assertEquals(12L,actual.getLong("expectedRevision"));assertEquals(6,actual.getInt("expectedMemberGeneration"));assertFalse(controller.getState().getModuleResults().containsKey("teams.feedback"));}finally{controller.detach();}
    }
    @Test public void wrongHeaderCannotConsumeActualPendingRequest()throws Exception{
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{pending(controller).put("native-a",new Pair<>("teams.feedback",input("run-a")));receive(controller,event("native-a","teams","resume",true).put("result",run("run-a")));assertTrue(pending(controller).containsKey("native-a"));assertTrue(controller.getState().getTeamRuns().isEmpty());receive(controller,event("native-a","teams","feedback",true).put("result",run("run-a")));assertFalse(pending(controller).containsKey("native-a"));assertTrue(controller.getState().getTeamRuns().containsKey("run-a"));}finally{controller.detach();}
    }
    @Test public void responseForAnotherRunIsNotASuccessOrForeignMerge()throws Exception{
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{pending(controller).put("native-a",new Pair<>("teams.feedback",input("run-a")));receive(controller,event("native-a","teams","feedback",true).put("result",run("run-b")));assertTrue(controller.getState().getTeamRuns().isEmpty());assertFalse(controller.getState().getModuleResults().containsKey("teams.feedback"));assertTrue(controller.getState().getModuleErrors().containsKey("teams.feedback"));}finally{controller.detach();}
    }
    @Test public void exactReceiptsSurviveOutOfOrderCommandsAndPlanFailureKeepsItsSubject()throws Exception{
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{
            pending(controller).put("feedback-a",new Pair<>("teams.feedback",input("run-a")));
            pending(controller).put("resume-a",new Pair<>("teams.resume",input("run-a")));
            pending(controller).put("plan-b",new Pair<>("teams.savePlan",new JSONObject().put("teamId","team-b").put("expectedDefinition","saved exact definition")));
            receive(controller,event("resume-a","teams","resume",true).put("result",run("run-a")));
            receive(controller,event("plan-b","teams","savePlan",false).put("message","定义已更新"));
            receive(controller,event("feedback-a","teams","feedback",true).put("result",run("run-a")));
            Map<String,JSONObject> receipts=controller.getState().getTeamOwnerReceipts();assertEquals(3,receipts.size());assertTrue(receipts.get("feedback-a").getBoolean("ok"));assertEquals("feedback",receipts.get("feedback-a").getString("command"));assertEquals("run-a",receipts.get("feedback-a").getString("runId"));assertFalse(receipts.get("plan-b").getBoolean("ok"));assertEquals("team-b",receipts.get("plan-b").getString("teamId"));assertFalse(receipts.get("plan-b").has("runId"));assertEquals("定义已更新",receipts.get("plan-b").getString("message"));assertEquals("plan-b",controller.getState().getModuleResultInputs().get("teams.savePlan").getString("_requestId"));
        }finally{controller.detach();}
    }
    @Test public void receiptsAreBoundedAndCannotUseModelBodyAsNativeAcknowledgement()throws Exception{
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{
            for(int n=0;n<40;n++){String id="native-"+n;pending(controller).put(id,new Pair<>("teams.feedback",input("run-a")));receive(controller,event(id,"teams","feedback",true).put("result",run("run-a").put("requestId","model-forged-id").put("command","savePlan").put("ok",false).put("message","model-forged-failure")));}
            Map<String,JSONObject> receipts=controller.getState().getTeamOwnerReceipts();assertEquals(32,receipts.size());assertFalse(receipts.containsKey("native-0"));JSONObject actual=receipts.get("native-39");assertTrue(actual.getBoolean("ok"));assertEquals("native-39",actual.getString("requestId"));assertEquals("feedback",actual.getString("command"));assertFalse(actual.has("message"));
        }finally{controller.detach();}
    }

}
