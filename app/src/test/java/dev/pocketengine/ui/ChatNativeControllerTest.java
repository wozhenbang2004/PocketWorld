package dev.pocketengine.ui;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.pocketengine.testutil.HttpServer;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ChatNativeControllerTest {
 @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ChatUiKeyStore","1","Synthetic UI fixture key"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
 interface Check {boolean done()throws Exception;}
 static void await(Check check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!check.done()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue("native UI condition not reached",check.done());}
 @Test public void actualControllerSelectsAnAttachmentAndAcceptsASecondInputWhileOriginalModelIsBusy()throws Exception{
  EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();List<JSONObject> bodies=new CopyOnWriteArrayList<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",x->{try{int n=bodies.size();bodies.add(new JSONObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));if(n==0){entered.countDown();release.await(8,TimeUnit.SECONDS);}byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"actual reply\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().add("Content-Type","application/json");x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);}catch(Exception e){throw new IllegalStateException(e);}finally{x.close();}});server.start();PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
  try{app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","fixture").putString("provider","openai_chat").putBoolean("supportsImageInput",true).putInt("maxSteps",8).commit();String image="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";JSONObject file=AttachmentRepository.get(app).book.importBytes(new ByteArrayInputStream(Base64.getDecoder().decode(image)),"selected.png","image/png",new JSONObject());controller.attach();await(()->controller.getState().feature("assistant").optBoolean("sendAcknowledgements"));assertTrue(controller.getState().getSelectedChatAttachments().isEmpty());controller.toggleChatAttachment(file);assertEquals(file.getString("id"),controller.getState().getSelectedChatAttachments().get(0).getId());controller.sendChat("read this selected image");assertTrue(entered.await(5,TimeUnit.SECONDS));await(()->!controller.getState().getChatSubmitting()&&controller.getState().getChatActiveTurn()!=null);assertTrue(controller.getState().getChatBusy());assertTrue(controller.getState().getSelectedChatAttachments().isEmpty());assertTrue(ChatInputRules.INSTANCE.canSend(controller.getState(),false,"UI-QUEUE-FOLLOW-UP"));String original=controller.getState().getChatActiveTurn().getString("taskId");controller.sendChat("UI-QUEUE-FOLLOW-UP");await(()->!controller.getState().getChatSubmitting()&&controller.getState().getChatReceipts().size()==2);assertEquals(original,controller.getState().getChatActiveTurn().getString("taskId"));assertEquals(1,app.tasks.list().length());release.countDown();AssistantModule module=(AssistantModule)app.features.module("assistant");await(()->!module.snapshot().getBoolean("running")&&!controller.getState().getChatBusy());assertEquals(2,bodies.size());assertTrue(bodies.get(0).toString().replace("\\/","/").contains("data:image/png;base64,"+image));assertTrue(bodies.get(1).toString().contains("UI-QUEUE-FOLLOW-UP"));assertFalse(app.tasks.get(original,false).toString().replace("\\/","/").contains(image));
  }finally{release.countDown();controller.detach();app.features.module("assistant").close();server.stop(0);java.security.Security.removeProvider("ChatUiKeyStore");}
 }
}
