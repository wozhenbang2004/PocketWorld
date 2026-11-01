package dev.pocketengine.features;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
public final class PluginApprovalIdentityTest {
    @Test public void reinstallCannotCarryAnOldPendingApprovalIntoANewSupervisor()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(context);
        String taskId=tasks.createCapabilityTask("调用已安装的工具","").getString("id");JSONObject first=tasks.startAttempt(taskId,"capabilities",20);
        try(Endpoint a=new Endpoint();Endpoint b=new Endpoint();FeatureHost host=new FeatureHost(event->{})){
            PluginsModule plugins=new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));host.register(plugins);
            install(plugins,a.url());String name=plugins.toolRegistry().getJSONObject(0).getString("name");
            HostToolSupervisor before=new HostToolSupervisor(host,tasks,taskId,first.getString("runId"),first.getInt("coordinatorEpoch"),true);
            tasks.authorizeTools(taskId,first.getString("runId"),new JSONArray().put(name));JSONObject args=new JSONObject().put("message","once");
            JSONObject pending=before.execute(name,args,new RunControl());assertEquals("approval_required",pending.getString("code"));
            tasks.endAttempt(taskId,first.getString("runId"),"awaiting_user","等待授权",new JSONObject());
            install(plugins,b.url()); // The same plugin ID, tool and manifest version, with a new installation/endpoint.
            tasks.decideCapabilityApproval(taskId,pending.getString("approvalId"),true);
            JSONObject next=tasks.startAttempt(taskId,"capabilities",20);
            HostToolSupervisor after=new HostToolSupervisor(host,tasks,taskId,next.getString("runId"),next.getInt("coordinatorEpoch"),true);
            tasks.authorizeTools(taskId,next.getString("runId"),new JSONArray().put(name));
            JSONObject fresh=after.execute(name,args,new RunControl());
            assertEquals("A endpoint must never receive a request",0,a.requests.get());
            assertEquals("B endpoint requires a fresh installation-bound approval",0,b.requests.get());
            assertEquals("approval_required",fresh.getString("code"));assertNotEquals(pending.getString("approvalId"),fresh.getString("approvalId"));
            assertEquals("invalidated",tasks.get(taskId,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
            tasks.decideCapabilityApproval(taskId,fresh.getString("approvalId"),true);
            assertEquals("response_received",after.execute(name,args,new RunControl()).getString("status"));assertEquals(1,b.requests.get());
        }
    }
    private static void install(PluginsModule plugins,String endpoint)throws Exception{
        JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("message",new JSONObject().put("type","string"))).put("required",new JSONArray().put("message")).put("additionalProperties",false);
        JSONObject tool=new JSONObject().put("name","write").put("kind","http").put("endpoint",endpoint).put("effect","write").put("schema",schema);
        plugins.handle("install",new JSONObject().put("manifest",new JSONObject().put("id","approval-test").put("name","审批测试").put("version","1").put("tools",new JSONArray().put(tool))));
        plugins.handle("grant",new JSONObject().put("id","approval-test").put("grants",new JSONArray().put("tool:write")));
        plugins.handle("enable",new JSONObject().put("id","approval-test").put("enabled",true));
    }
    private static final class Endpoint implements AutoCloseable{
        final ServerSocket server;final AtomicInteger requests=new AtomicInteger();final Thread worker;
        Endpoint()throws Exception{server=new ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));worker=new Thread(()->{
            while(!server.isClosed())try(Socket client=server.accept()){
                client.setSoTimeout(2000);InputStream in=client.getInputStream();int length=0;String line;
                while(!(line=line(in)).isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                in.readNBytes(length);requests.incrementAndGet();byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(body);
            }catch(Exception ignored){}
        },"plugin-approval-endpoint");worker.setDaemon(true);worker.start();}
        String url(){return "http://127.0.0.1:"+server.getLocalPort()+"/tool";}
        public void close()throws Exception{server.close();worker.join(1000);}
        private static String line(InputStream in)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;while(true){int value=in.read();if(value<0)throw new java.io.IOException();if(previous=='\r'&&value=='\n'){byte[] found=bytes.toByteArray();return new String(found,0,found.length-1,StandardCharsets.US_ASCII);}bytes.write(value);previous=value;}}
    }
}
