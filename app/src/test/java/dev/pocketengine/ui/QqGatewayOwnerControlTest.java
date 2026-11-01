package dev.pocketengine.ui;
import android.app.Application;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class QqGatewayOwnerControlTest {
 @Test public void stopUsesUrgentNativeLaneWithExactProfileDespiteBlockedOrdinaryUiWorker()throws Exception{
  CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),stopped=new CountDownLatch(1);EngineApplication app=new EngineApplication();app.features=new FeatureHost(event->{});app.features.register(new EngineModule(){public String id(){return "channels";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{assertEquals("qq.stop",command);assertEquals("qq-owner-profile",input.getString("id"));stopped.countDown();return new JSONObject().put("requested",true);}});PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
  try{Field field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);((ExecutorService)field.get(controller)).execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(blocked.await(1,TimeUnit.SECONDS));controller.requestFeature("channels","qq.stop",new JSONObject().put("id","qq-owner-profile"));assertTrue("QQ stop must not wait behind ordinary UI work",stopped.await(500,TimeUnit.MILLISECONDS));}finally{release.countDown();controller.detach();app.features.close();}
 }
}
