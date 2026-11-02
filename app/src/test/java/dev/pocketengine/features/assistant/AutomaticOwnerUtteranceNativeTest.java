package dev.pocketengine.features.assistant;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.memory.SourceRef;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original Application/TaskStore/Runner and local model bytes; never a paid endpoint. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AutomaticOwnerUtteranceNativeTest {
    @org.junit.Before public void nativeKeyFixture(){java.security.Security.addProvider(new java.security.Provider("AutomaticLearningFixtureKeys","1","Test-only AndroidKeyStore provider"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @org.junit.After public void removeNativeKeyFixture(){java.security.Security.removeProvider("AutomaticLearningFixtureKeys");}
    @Test public void automaticReaderRequiresActuallyAdoptedCurrentUserRevision()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        AssistantModule assistant=(AssistantModule)app.features.module("assistant");
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"FIXTURE-ASSISTANT-UNTRUSTED\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
        http.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).putString("provider","openai_chat").putString("model","controlled-fixture").putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            String text="FIXTURE-REAL-OWNER-UTTERANCE";
            JSONObject ack=assistant.handle("send",new JSONObject().put("message",text).put("clientMessageId",UUID.randomUUID().toString()));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(assistant.snapshot().optBoolean("running")&&System.nanoTime()<until)Thread.sleep(10);
            assertFalse("original task did not settle",assistant.snapshot().optBoolean("running"));
            JSONObject task=app.tasks.get(ack.getString("taskId"),false);assertEquals("completed",task.getString("status"));
            String taskId=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
            SourceRef ref=new SourceRef("message",ack.getString("messageId"),1,"owner","private");
            assertEquals(text,assistant.readAutomaticOwnerUtterance(ref,taskId,run,epoch).text);
            assertThrows(SecurityException.class,()->assistant.readAutomaticOwnerUtterance(ref,taskId,"different-native-run",epoch));
            JSONArray messages=assistant.handle("get",new JSONObject().put("conversationId",ack.getString("conversationId"))).getJSONArray("messages");
            JSONObject reply=messages.getJSONObject(messages.length()-1);assertEquals("assistant",reply.getString("role"));
            SourceRef notUser=new SourceRef("message",reply.getString("id"),reply.getLong("revision"),"owner","private");
            assertThrows(SecurityException.class,()->assistant.readAutomaticOwnerUtterance(notUser,taskId,run,epoch));
            assistant.handle("editMessage",new JSONObject().put("conversationId",ack.getString("conversationId")).put("messageId",ref.sourceId).put("revision",1).put("content","changed owner revision"));
            assertThrows(Exception.class,()->assistant.readAutomaticOwnerUtterance(ref,taskId,run,epoch));
        }finally{assistant.handle("cancel",new JSONObject());assistant.close();http.stop(0);}
    }
}
