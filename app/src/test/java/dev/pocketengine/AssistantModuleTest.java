package dev.pocketengine;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AssistantModuleTest {
    @Test public void actualMultiTurnRequestUsesPrivateRecallAndPersistsVisibleMessages()throws Exception{
        Context app=RuntimeEnvironment.getApplication();List<JSONObject> requests=new ArrayList<>();List<JSONObject> recalls=new ArrayList<>();
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getLocalPort()).putString("model","test-model").commit();
            serverThread.submit(()->{try{for(int round=0;round<2;round++)try(Socket socket=server.accept()){
                socket.setSoTimeout(4000);InputStream input=socket.getInputStream();StringBuilder headers=new StringBuilder();int b;while((b=input.read())>=0){headers.append((char)b);if(headers.toString().endsWith("\r\n\r\n"))break;}
                int length=0;for(String line:headers.toString().split("\r\n"))if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                ByteArrayOutputStream body=new ByteArrayOutputStream();for(int i=0;i<length;i++)body.write(input.read());requests.add(new JSONObject(body.toString("UTF-8")));
                byte[] reply=("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"实际回复"+round+"\"}}]}").getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(reply);socket.getOutputStream().flush();
            }}catch(Exception e){throw new RuntimeException(e);}});
            EngineModule memory=new EngineModule(){public String id(){return "memory";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject in)throws Exception{assertEquals("contextSnapshot",command);assertEquals(16,in.getInt("limit"));assertEquals(12000,in.getInt("maxChars"));recalls.add(in);return new JSONObject().put("context","主人喜欢简洁的回答");}};
            AssistantModule module=new AssistantModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),memory);
            JSONObject first=module.handle("send",new JSONObject().put("message","你好"));String id=first.getString("conversationId");assertEquals("实际回复0",first.getJSONObject("message").getString("content"));
            module.handle("send",new JSONObject().put("conversationId",id).put("message","继续聊"));
            assertEquals(2,requests.size());assertTrue(requests.get(1).toString().contains("实际回复0"));assertTrue(requests.get(1).toString().contains("主人喜欢简洁的回答"));
            assertEquals("owner",recalls.get(0).getJSONObject("scope").getString("ownerId"));assertEquals("private",recalls.get(0).getJSONObject("scope").getString("spaceId"));
            assertFalse(requests.get(0).has("tools"));
            AssistantModule reloaded=new AssistantModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),memory);
            assertEquals(4,reloaded.handle("get",new JSONObject().put("conversationId",id)).getJSONArray("messages").length());
            reloaded.handle("delete",new JSONObject().put("conversationId",id));assertEquals(0,reloaded.handle("list",new JSONObject()).getJSONArray("conversations").length());
        }finally{serverThread.shutdownNow();serverThread.awaitTermination(2,TimeUnit.SECONDS);}
    }
}
