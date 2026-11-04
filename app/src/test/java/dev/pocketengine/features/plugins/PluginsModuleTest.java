package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class PluginsModuleTest {
    @Test public void registryOnlyExposesGrantedToolsAndReinstallInvalidatesPinnedDescriptors()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
        JSONObject manifest=new JSONObject().put("id","echoTools").put("name","工具").put("version","1").put("tools",new JSONArray().put(new JSONObject().put("name","show").put("kind","echo")));
        try{
            module.handle("install",new JSONObject().put("manifest",manifest));assertEquals(0,module.toolRegistry().length());
            dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(module,"echoTools",true);assertEquals(0,module.toolRegistry().length());
            module.handle("grant",new JSONObject().put("id","echoTools").put("grants",new JSONArray().put("tool:show")));JSONObject descriptor=module.toolRegistry().getJSONObject(0);
            assertEquals("plugin:echoTools:show",descriptor.getString("id"));assertTrue(descriptor.getString("name").matches("plugin_[a-f0-9]{24}"));assertEquals("read",descriptor.getString("effect"));
            JSONObject invoke=new JSONObject().put("id","echoTools").put("tool","show").put("expectedVersion",descriptor.getString("version")).put("expectedInstallationId",descriptor.getString("installationId")).put("arguments",new JSONObject().put("actual","content"));
            assertEquals("content",module.handle("invoke",invoke).getJSONObject("value").getString("actual"));
            module.handle("install",new JSONObject().put("manifest",manifest));dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(module,"echoTools",true);module.handle("grant",new JSONObject().put("id","echoTools").put("grants",new JSONArray().put("tool:show")));
            try{module.handle("invoke",invoke);fail("reinstallation invalidates the exact tool descriptor even at the same manifest version");}catch(SecurityException expected){}
            module.handle("grant",new JSONObject().put("id","echoTools").put("grants",new JSONArray()));assertEquals(0,module.toolRegistry().length());
        }finally{module.close();}
    }
    @Test public void revokingPermissionDoesNotWaitForNetworkAndBlocksLateSuccess()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
        CountDownLatch accepted=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            Thread responder=new Thread(()->{try(Socket socket=server.accept()){
                socket.setSoTimeout(2000);InputStream in=socket.getInputStream();int length=0;
                while(true){String line=line(in);if(line.isEmpty())break;if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());}
                for(int i=0;i<length;i++)if(in.read()<0)throw new java.io.IOException();accepted.countDown();release.await(3,TimeUnit.SECONDS);
                byte[] body="{\"ok\":true,\"value\":\"late write result\"}".getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            }catch(Exception ignored){}});responder.setDaemon(true);responder.start();
            JSONObject manifest=new JSONObject().put("id","testPlugin").put("name","测试工具").put("version","1").put("tools",new JSONArray().put(new JSONObject().put("name","write").put("kind","http").put("endpoint","http://127.0.0.1:"+server.getLocalPort()+"/tool")));
            module.handle("install",new JSONObject().put("manifest",manifest));module.handle("grant",new JSONObject().put("id","testPlugin").put("grants",new JSONArray().put("tool:write")));dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(module,"testPlugin",true);
            String operation=java.util.UUID.randomUUID().toString();Future<JSONObject> result=executor.submit(()->module.handle("invoke",new JSONObject().put("id","testPlugin").put("tool","write").put("_operationId",operation)));
            assertTrue(accepted.await(2,TimeUnit.SECONDS));long before=System.nanoTime();
            module.handle("grant",new JSONObject().put("id","testPlugin").put("grants",new JSONArray()));assertTrue("revocation must not wait for HTTP timeout",System.nanoTime()-before<1_000_000_000L);
            release.countDown();JSONObject receipt=result.get(2,TimeUnit.SECONDS);assertEquals("unknown",receipt.getString("status"));assertTrue(receipt.getBoolean("cancelRequested"));assertFalse(receipt.has("toolResult"));assertFalse(receipt.getBoolean("verified"));assertEquals(0,module.snapshot().getJSONArray("active").length());
            try{module.handle("invoke",new JSONObject().put("id","testPlugin").put("tool","write"));fail("revoked tool must not dispatch");}catch(SecurityException expected){}
            responder.join(1000);
        }finally{release.countDown();executor.shutdownNow();module.close();}
    }
    private static String line(InputStream in)throws Exception {ByteArrayOutputStream result=new ByteArrayOutputStream();int before=-1;while(true){int value=in.read();if(value<0)throw new java.io.IOException();if(before=='\r'&&value=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}result.write(value);before=value;}}
}
