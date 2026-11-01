package dev.pocketengine.ui;
import android.app.Application;
import dev.pocketengine.EngineApplication;
import java.lang.reflect.*;
import java.util.Map;
import kotlin.Pair;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RealtimeTaskControllerReceiptTest {
 private void result(PocketWorldController controller, String command, JSONObject result)throws Exception{
  Field pending=PocketWorldController.class.getDeclaredField("pending");pending.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Pair<String,JSONObject>> rows=(Map<String,Pair<String,JSONObject>>)pending.get(controller);
  rows.put("native-request",new Pair<>("realtime."+command,new JSONObject().put("sessionId","session-one").put("reviewId","review-one")));
  Method receive=PocketWorldController.class.getDeclaredMethod("featureResult",JSONObject.class);receive.setAccessible(true);receive.invoke(controller,new JSONObject().put("requestId","native-request").put("feature","realtime").put("command",command).put("ok",true).put("result",result));
 }
 @Test public void savedTaskMergesWithoutOpeningOrExecutingAndKeepsExactReviewBody()throws Exception{
  PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());
  try{
   JSONObject review=new JSONObject().put("sessionId","session-one").put("reviewId","review-one").put("text","真实最终字幕").put("revision",2L);result(controller,"reviewTurn",review);assertEquals(review.toString(),controller.getState().getModuleResults().get("realtime.reviewTurn").toString());assertEquals("session-one",controller.getState().getModuleResultInputs().get("realtime.reviewTurn").getString("sessionId"));
   JSONObject task=new JSONObject().put("id","task-one").put("goal","真实最终字幕").put("status","awaiting_user").put("kind","assistant").put("executionMode","capabilities").put("origin",new JSONObject().put("kind","owner_reviewed_realtime"));
   result(controller,"commitTurn",new JSONObject().put("task",task).put("status","saved_waiting_for_owner").put("automaticallyExecutes",false));assertEquals(1,controller.getState().getTasks().size());assertNull(controller.getState().getRequestedTaskId());assertNull(controller.getState().getActiveTaskId());assertTrue(controller.getState().getNotice().contains("尚未自动执行"));
  }finally{controller.detach();}
 }
 @Test public void missingLookupNeverInventsTaskOrSavedSuccess()throws Exception{
  PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{result(controller,"reconcileTurn",new JSONObject().put("status","unconfirmed").put("message","原提交未查到，不会重建"));assertTrue(controller.getState().getTasks().isEmpty());assertEquals("原提交未查到，不会重建",controller.getState().getNotice());assertNull(controller.getState().getRequestedTaskId());}finally{controller.detach();}
 }
 @Test public void reconciliationOfAlreadyRunningTaskDoesNotRegressItsStateOrClaimItStillWaits()throws Exception{
  PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{
   JSONObject task=new JSONObject().put("id","task-one").put("goal","原目标").put("status","running").put("coordinatorEpoch",1).put("runId","already-running").put("kind","assistant").put("executionMode","capabilities");
   result(controller,"reconcileTurn",new JSONObject().put("task",task).put("status","saved_waiting_for_owner"));assertEquals("running",controller.getState().getTasks().get(0).getStatus());assertTrue(controller.getState().getNotice().contains("当前状态"));assertFalse(controller.getState().getNotice().contains("明确继续"));assertNull(controller.getState().getRequestedTaskId());
  }finally{controller.detach();}
 }
}
