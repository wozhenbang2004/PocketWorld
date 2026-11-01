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
public final class RemoteSteerControllerReceiptTest {
 @Test public void actualControllerPreservesExactPreflightBindingWithoutKeepingDraftText()throws Exception{
  PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());
  try{
   Field pending=PocketWorldController.class.getDeclaredField("pending");pending.setAccessible(true);
   @SuppressWarnings("unchecked") Map<String,Pair<String,JSONObject>> rows=(Map<String,Pair<String,JSONObject>>)pending.get(controller);
   rows.put("native-request",new Pair<>("remote.steerJob",new JSONObject().put("taskId","task-one").put("messageId","message-one").put("text","private-draft")));
   JSONObject receipt=new JSONObject().put("messageId","message-one").put("status","rejected").put("dispatched",false).put("receiptSource","native_preflight");
   Method receive=PocketWorldController.class.getDeclaredMethod("featureResult",JSONObject.class);receive.setAccessible(true);receive.invoke(controller,new JSONObject().put("requestId","native-request").put("feature","remote").put("command","steerJob").put("ok",true).put("result",receipt));
   JSONObject input=controller.getState().getModuleResultInputs().get("remote.steerJob");
   assertTrue(RemoteSteerGuard.preflightRejected("task-one","message-one",false,controller.getState().getModuleResults().get("remote.steerJob"),input));
   assertFalse(RemoteSteerGuard.preflightRejected("task-two","message-one",false,receipt,input));assertFalse(input.toString().contains("private-draft"));
  }finally{controller.detach();}
 }
}
