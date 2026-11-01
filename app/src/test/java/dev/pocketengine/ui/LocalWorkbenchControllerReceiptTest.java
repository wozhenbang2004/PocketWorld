package dev.pocketengine.ui;
import android.app.Application;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import kotlin.Pair;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class LocalWorkbenchControllerReceiptTest {
 @Test public void realControllerPreservesReviewedCheckoutPathHashVersionAndRequestIdentity()throws Exception{
  PocketWorldController controller=new PocketWorldController(new ComposeActivity(),new EngineApplication());try{
   Field pending=PocketWorldController.class.getDeclaredField("pending");pending.setAccessible(true);@SuppressWarnings("unchecked") Map<String,Pair<String,JSONObject>> rows=(Map<String,Pair<String,JSONObject>>)pending.get(controller);
   JSONObject input=new JSONObject().put("checkoutId","copy-one").put("path","main.py").put("expectedHash","a".repeat(64)).put("expectedVersion",7).put("termux",true).put("content","not-metadata");rows.put("request-one",new Pair<>("local.reviewFile",input));
   JSONObject result=new JSONObject().put("checkoutId","copy-one").put("path","main.py").put("expectedHash","a".repeat(64)).put("expectedVersion",7).put("importable",true).put("truncated",false);Method receive=PocketWorldController.class.getDeclaredMethod("featureResult",JSONObject.class);receive.setAccessible(true);receive.invoke(controller,new JSONObject().put("requestId","request-one").put("feature","local").put("command","reviewFile").put("ok",true).put("result",result));
   JSONObject metadata=controller.getState().getModuleResultInputs().get("local.reviewFile");assertNotNull(LocalWorkbenchGuard.result(controller.getState().getModuleResults().get("local.reviewFile"),metadata,"request-one","copy-one","main.py"));assertNull(LocalWorkbenchGuard.result(result,metadata,"request-one","other-copy","main.py"));assertEquals("a".repeat(64),metadata.getString("expectedHash"));assertEquals(7,metadata.getInt("expectedVersion"));assertTrue(metadata.getBoolean("termux"));assertFalse(metadata.has("content"));
  }finally{controller.detach();}
 }
 @Test public void termuxCancelReachesControlLaneEvenWhileOrdinaryOwnerWorkIsBlocked()throws Exception{
  EngineApplication app=new EngineApplication();CountDownLatch cancelled=new CountDownLatch(1),blocked=new CountDownLatch(1),release=new CountDownLatch(1);
  app.features=new FeatureHost(event->{});app.features.register(new EngineModule(){public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{assertEquals("termux.cancel",command);assertEquals("job-one",input.getString("jobId"));cancelled.countDown();return new JSONObject().put("status","unknown");}});
  PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);try{
   Field io=PocketWorldController.class.getDeclaredField("io");io.setAccessible(true);((ExecutorService)io.get(controller)).execute(()->{blocked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});assertTrue(blocked.await(1,TimeUnit.SECONDS));controller.requestFeature("local","termux.cancel",new JSONObject().put("jobId","job-one"));assertTrue("cancel must not wait for ordinary UI work",cancelled.await(2,TimeUnit.SECONDS));
  }finally{release.countDown();controller.detach();app.features.close();}
 }
}
