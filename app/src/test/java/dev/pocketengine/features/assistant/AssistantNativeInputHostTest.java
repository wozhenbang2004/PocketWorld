package dev.pocketengine.features.assistant;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.pocketengine.testutil.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual Application admission + TaskStore mailbox + existing AgentRunner, real loopback model bytes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AssistantNativeInputHostTest {
    @org.junit.Before public void nativeKeyFixture(){java.security.Security.addProvider(new java.security.Provider("ChatInputKeyStore","1","Explicit test-only AndroidKeyStore provider"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    private static final String IMAGE="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";
    private static JSONObject j(Object... values)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o;}
    @Test public void actualApplicationQueuesOneOriginalTaskAndProjectsSelectedBytesWithoutPersistingThem()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AssistantModule assistant=(AssistantModule)app.features.module("assistant");assertNotNull(assistant);assertTrue(app.features.module("realtime").snapshot().getBoolean("naturalVoiceSupported"));assertTrue("native admission host is not mounted",assistant.snapshot().optBoolean("sendAcknowledgements"));
        List<JSONObject> bodies=new CopyOnWriteArrayList<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{int n=bodies.size();bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));if(n==0){entered.countDown();if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"已读取当前输入，请主人核对\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","fixture").putBoolean("supportsImageInput",true).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            JSONObject image=AttachmentRepository.get(app).book.importBytes(new ByteArrayInputStream(Base64.getDecoder().decode(IMAGE)),"one.png","image/png",new JSONObject());JSONObject document=AttachmentRepository.get(app).book.importBytes(new ByteArrayInputStream("NATIVE-PRIVATE-DOCUMENT".getBytes(StandardCharsets.UTF_8)),"chat.txt","text/plain",new JSONObject());JSONObject first=assistant.handle("send",j("message","主人明确选择图片","clientMessageId",UUID.randomUUID().toString(),"attachments",new JSONArray().put(j("id",image.getString("id"),"sha256",image.getString("sha256"))).put(j("id",document.getString("id"),"sha256",document.getString("sha256")))));
            assertEquals("accepted",first.getString("status"));assertTrue("model request did not start",entered.await(5,TimeUnit.SECONDS));JSONObject active=assistant.snapshot().getJSONObject("activeTurn");assertEquals(first.getString("taskId"),active.getString("taskId"));JSONObject input=j("message","NATIVE-OWNER-QUEUE-ONCE","clientMessageId",UUID.randomUUID().toString(),"conversationId",first.getString("conversationId"),"taskId",active.getString("taskId"),"runId",active.getString("runId"),"epoch",active.getInt("epoch"),"mode","queue");
            JSONObject queued=assistant.handle("send",input),duplicate=assistant.handle("send",input);assertEquals("queued",queued.getString("status"));assertEquals(queued.getString("messageId"),duplicate.getString("messageId"));assertTrue(duplicate.getBoolean("duplicate"));release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(assistant.snapshot().getBoolean("running")&&System.nanoTime()<until)Thread.sleep(10);assertFalse("runner did not settle",assistant.snapshot().getBoolean("running"));
            assertEquals(2,bodies.size());assertTrue(bodies.get(0).toString().contains("NATIVE-PRIVATE-DOCUMENT"));assertTrue(bodies.get(0).toString().replace("\\/","/").contains("data:image/png;base64,"+IMAGE));String second=bodies.get(1).toString();assertEquals(1,(second.length()-second.replace("NATIVE-OWNER-QUEUE-ONCE","").length())/"NATIVE-OWNER-QUEUE-ONCE".length());assertEquals(1,app.tasks.list().length());JSONObject task=app.tasks.get(first.getString("taskId"),false);assertEquals(first.getString("runId"),task.getString("runId"));assertFalse(task.toString().contains("NATIVE-PRIVATE-DOCUMENT"));assertFalse(task.toString().replace("\\/","/").contains(IMAGE));assertFalse(Files.readString(new java.io.File(app.getFilesDir(),"private_conversations.json").toPath()).replace("\\/","/").contains(IMAGE));
        }finally{release.countDown();assistant.handle("cancel",new JSONObject());assistant.close();server.stop(0);}
    }
    @org.junit.After public void removeNativeKeyFixture(){java.security.Security.removeProvider("ChatInputKeyStore");}
    private static final class ModelFixture implements AutoCloseable{
        final HttpServer http;final List<JSONObject> bodies=new CopyOnWriteArrayList<>();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ModelFixture()throws Exception{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",e->{try{int n=bodies.size();bodies.add(new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));if(n==0){entered.countDown();if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("model gate timeout");}byte[] b="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"实际fixture答复，需主人核对\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);}catch(Exception failure){throw new RuntimeException(failure);}finally{e.close();}});http.start();}
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort();}public void close(){release.countDown();http.stop(0);}
    }
    private static void configure(EngineApplication app,String base){app.getSharedPreferences("private_settings",0).edit().putString("baseUrl",base).putString("provider","openai_chat").putString("model","fixture").putBoolean("supportsImageInput",true).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();}
    private static void idle(AssistantModule module)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(module.snapshot().getBoolean("running")&&System.nanoTime()<until)Thread.sleep(10);assertFalse("original chat did not settle",module.snapshot().getBoolean("running"));}
    private static JSONObject resumed(EngineApplication app,String id,String previousRun,String finalStatus)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);JSONObject task;do{task=app.tasks.get(id,false);if(!previousRun.equals(task.optString("runId"))&&finalStatus.equals(task.optString("status")))return task;Thread.sleep(10);}while(System.nanoTime()<until);throw new AssertionError("original task resume did not settle: "+task.optString("status"));}
    private static JSONObject image(EngineApplication app)throws Exception{return AttachmentRepository.get(app).book.importBytes(new ByteArrayInputStream(Base64.getDecoder().decode(IMAGE)),"restore.png","image/png",new JSONObject());}
    private static JSONObject submit(AssistantModule module,JSONObject image)throws Exception{return module.handle("send",j("message","只检查这份主人选择的图片","clientMessageId",UUID.randomUUID().toString(),"attachments",new JSONArray().put(j("id",image.getString("id"),"sha256",image.getString("sha256")))));}
    @Test public void explicitOriginalTaskResumeRebuildsRefsAndRetainsCancelledQueuedInputWithoutAutoRetry()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AssistantModule module=(AssistantModule)app.features.module("assistant");try(ModelFixture model=new ModelFixture()){
            configure(app,model.base());JSONObject file=image(app),first=submit(module,file);assertTrue(model.entered.await(5,TimeUnit.SECONDS));JSONObject extra=j("message","PENDING-OWNER-ONLY-AFTER-RESUME","clientMessageId",UUID.randomUUID().toString(),"conversationId",first.getString("conversationId"),"taskId",first.getString("taskId"),"runId",first.getString("runId"),"epoch",first.getInt("epoch"),"mode","queue");assertEquals("queued",module.handle("send",extra).getString("status"));module.handle("cancel",new JSONObject());model.release.countDown();idle(module);assertEquals(1,model.bodies.size());String id=first.getString("taskId");assertEquals("cancelled",app.tasks.get(id,false).getString("status"));
            app.dispatchOwnerAction("resumeTask",j("taskId",id,"feedback","主人明确继续原任务；不重放任何旧动作"));JSONObject task=resumed(app,id,first.getString("runId"),"completed");assertEquals("completed",task.getString("status"));assertEquals(1,app.tasks.list().length());assertEquals(3,model.bodies.size());assertTrue(model.bodies.get(1).toString().replace("\\/","/").contains(IMAGE));assertFalse(model.bodies.get(1).toString().contains("PENDING-OWNER-ONLY-AFTER-RESUME"));assertTrue(model.bodies.get(2).toString().contains("PENDING-OWNER-ONLY-AFTER-RESUME"));assertFalse(task.toString().replace("\\/","/").contains(IMAGE));
        }finally{module.close();}
    }
    @Test public void deletedAttachmentDuringStoppedOriginalTaskBlocksResumeBeforeAnySecondModelRequest()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AssistantModule module=(AssistantModule)app.features.module("assistant");try(ModelFixture model=new ModelFixture()){
            configure(app,model.base());JSONObject file=image(app),first=submit(module,file);assertTrue(model.entered.await(5,TimeUnit.SECONDS));module.handle("cancel",new JSONObject());model.release.countDown();idle(module);AttachmentRepository.get(app).remove(file.getString("id"));String id=first.getString("taskId");app.dispatchOwnerAction("resumeTask",j("taskId",id));JSONObject task=resumed(app,id,first.getString("runId"),"failed");assertEquals("failed",task.getString("status"));assertEquals(1,model.bodies.size());assertEquals(1,app.tasks.list().length());
        }finally{module.close();}
    }
    @Test public void changedAttachmentDestinationNeverReceivesOldSelectedBytesOnResume()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AssistantModule module=(AssistantModule)app.features.module("assistant");try(ModelFixture original=new ModelFixture();ModelFixture replacement=new ModelFixture()){
            configure(app,original.base());JSONObject first=submit(module,image(app));assertTrue(original.entered.await(5,TimeUnit.SECONDS));module.handle("cancel",new JSONObject());original.release.countDown();idle(module);configure(app,replacement.base());String id=first.getString("taskId");app.dispatchOwnerAction("resumeTask",j("taskId",id));JSONObject task=resumed(app,id,first.getString("runId"),"failed");assertEquals("failed",task.getString("status"));assertEquals(1,original.bodies.size());assertEquals(0,replacement.bodies.size());assertEquals(1,app.tasks.list().length());
        }finally{module.close();}
    }

}
