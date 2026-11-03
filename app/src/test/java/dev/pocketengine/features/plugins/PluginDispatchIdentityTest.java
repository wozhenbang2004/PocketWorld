package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
public final class PluginDispatchIdentityTest {
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger sends=new AtomicInteger();
        final Thread responder;
        final PluginsModule module;
        final String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        final RunControl control=new RunControl();
        Fixture()throws Exception {
            Context context=RuntimeEnvironment.getApplication();module=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
            server=new ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));
            responder=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
                socket.setSoTimeout(2000);InputStream input=socket.getInputStream();int length=0;String header;
                while(!(header=line(input)).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());
                input.readNBytes(length);sends.incrementAndGet();byte[] response="{\"actual\":\"received\"}".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(response);
            }catch(Exception closed){}},"plugin-exact-dispatch");responder.setDaemon(true);responder.start();
            JSONObject tool=new JSONObject().put("name","write").put("kind","http").put("effect","write").put("endpoint","http://127.0.0.1:"+server.getLocalPort()+"/tool");
            module.handle("install",new JSONObject().put("manifest",new JSONObject().put("id","dispatch-test").put("name","精确派发").put("version","1").put("tools",new JSONArray().put(tool))));
            dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(module,"dispatch-test",true);
            module.handle("grant",new JSONObject().put("id","dispatch-test").put("grants",new JSONArray().put("tool:write")));control.bindHostRun(task,run,1);
        }
        JSONObject input()throws Exception {
            JSONObject descriptor=module.toolRegistry().getJSONObject(0);
            return new JSONObject().put("id","dispatch-test").put("tool","write").put("expectedVersion",descriptor.getString("version"))
                    .put("expectedInstallationId",descriptor.getString("installationId")).put("expectedExecutionIdentity",TaskStore.capabilityFingerprint(descriptor.getJSONObject("executionBinding")))
                    .put("_taskId",task).put("_runId",run).put("arguments",new JSONObject().put("message","once"));
        }
        void cycle(boolean grant)throws Exception {
            if(grant){module.handle("grant",new JSONObject().put("id","dispatch-test").put("grants",new JSONArray()));module.handle("grant",new JSONObject().put("id","dispatch-test").put("grants",new JSONArray().put("tool:write")));}
            else{JSONObject disabled=PluginOwnerTestReview.enableReviewed(module,PluginOwnerTestReview.current(module,"dispatch-test"),false);PluginOwnerTestReview.enableReviewed(module,disabled,true);}
        }
        @Override public void close()throws Exception {module.close();server.close();responder.join(1000);}
        private static String line(InputStream in)throws Exception {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;
            while(true){int value=in.read();if(value<0)throw new java.io.IOException();if(previous=='\r'&&value=='\n'){byte[] all=bytes.toByteArray();return new String(all,0,all.length-1,StandardCharsets.US_ASCII);}bytes.write(value);previous=value;}
        }
    }
    @Test public void staleNativeDispatchRejectsEnableAndGrantAbaBeforeHttp()throws Exception {
        for(boolean grant:new boolean[]{false,true})try(Fixture f=new Fixture()){
            JSONObject original=f.input();f.cycle(grant);JSONObject current=f.input();
            assertEquals(original.getString("expectedInstallationId"),current.getString("expectedInstallationId"));
            assertNotEquals(original.getString("expectedExecutionIdentity"),current.getString("expectedExecutionIdentity"));
            original.getJSONObject("arguments").put("expectedExecutionIdentity",current.getString("expectedExecutionIdentity"));
            assertThrows(SecurityException.class,()->f.module.invokeForHost(original,f.control));assertEquals(0,f.sends.get());
            JSONObject actual=f.module.invokeForHost(current,f.control);assertEquals("response_received",actual.getString("status"));assertEquals("received",actual.getJSONObject("toolResult").getString("actual"));assertEquals(1,f.sends.get());
        }
    }
    @Test public void boundNativeInvocationCannotTakeItsIdentityFromModelArguments()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject request=f.input();String identity=request.getString("expectedExecutionIdentity");request.remove("expectedExecutionIdentity");request.getJSONObject("arguments").put("expectedExecutionIdentity",identity);
            assertThrows(SecurityException.class,()->f.module.invokeForHost(request,f.control));
            assertThrows(SecurityException.class,()->f.module.invokeForHost(request,new RunControl()));assertEquals(0,f.sends.get());
        }
    }
    @Test public void queuedInvocationRechecksExactBindingWhileHoldingTheGrantLock()throws Exception {
        try(Fixture f=new Fixture()){
            var worker=Executors.newSingleThreadExecutor();CountDownLatch queued=new CountDownLatch(1);
            try{
                JSONObject original=f.input();java.util.concurrent.Future<?> result;
                synchronized(f.module){
                    result=worker.submit(()->{queued.countDown();assertThrows(SecurityException.class,()->f.module.invokeForHost(original,f.control));});
                    assertTrue(queued.await(2,TimeUnit.SECONDS));f.cycle(false);
                }
                result.get(2,TimeUnit.SECONDS);assertEquals(0,f.sends.get());
            }finally{worker.shutdownNow();}
        }
    }
}
