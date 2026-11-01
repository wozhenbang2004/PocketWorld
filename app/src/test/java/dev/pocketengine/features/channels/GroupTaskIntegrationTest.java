package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.groups.GroupsModule;
import dev.pocketengine.features.groups.host.*;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real QQ WSS wire fixture -> native registry -> actual SQLite/AgentRunner -> original HTTP outbox.
 * Synthetic endpoints/keystore do not establish live QQ authorization or Android device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupTaskIntegrationTest {
    @Before public void keyFixture(){java.security.Security.addProvider(new java.security.Provider("GroupIntegrationKeyStore","1","Synthetic local group fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeFixture(){java.security.Security.removeProvider("GroupIntegrationKeyStore");}
    @Test public void authenticatedQqGroupExecutesOriginalAgentRunnerAndReturnsActualOutboxReceipt()throws Exception {
        CountDownLatch finish=new CountDownLatch(1);AtomicInteger modelCalls=new AtomicInteger();CopyOnWriteArrayList<JSONObject> replies=new CopyOnWriteArrayList<>();
        HttpServer api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(QqGatewayFixture gateway=new QqGatewayFixture(peer->{peer.send(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",45000)));assertEquals(2,peer.read().getInt("op"));peer.send(QqGatewaySessionTest.ready(1));
            JSONObject event=new JSONObject().put("op",0).put("s",2).put("t","GROUP_AT_MESSAGE_CREATE").put("d",new JSONObject().put("id","original-group-message").put("group_openid","room-a").put("content","/task --tokens=12000 Explain the public question")
                    .put("timestamp",Instant.now().toString()).put("author",new JSONObject().put("member_openid","member-a").put("role","owner")).put("owner",true).put("spaceId","private"));peer.send(event);peer.send(event);while(!finish.await(100,TimeUnit.MILLISECONDS)){}
        })){
            api.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();byte[] raw=exchange.getRequestBody().readAllBytes();JSONObject body=raw.length==0?new JSONObject():new JSONObject(new String(raw,StandardCharsets.UTF_8)),response;
                if(path.equals("/app/getAppAccessToken"))response=new JSONObject().put("access_token","group-fixture-token").put("expires_in",7200);
                else if(path.equals("/gateway"))response=new JSONObject().put("url",gateway.url());
                else if(path.equals("/v2/groups/room-a/messages")){assertEquals("QQBot group-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));replies.add(body);response=new JSONObject().put("id","actual-group-platform-ack");}
                else throw new AssertionError(path);byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});api.start();
            model.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String message=request.getJSONArray("messages").toString();assertTrue(message.contains("Explain the public question"));assertFalse(message.contains("private-memory-sentinel"));JSONArray tools=request.optJSONArray("tools");assertTrue(tools==null||tools.length()==0);modelCalls.incrementAndGet();byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","Actual bounded group answer from loopback model.")).put("finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().put("Content-Type",java.util.List.of("application/json"));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});model.start();
            Context android=RuntimeEnvironment.getApplication();SettingsStore settings=new SettingsStore(android);FeatureContext context=new FeatureContext(android,settings,new SecretVault(android),event->{});TaskStore tasks=new TaskStore(android);
            try(ChannelsModule channels=new ChannelsModule(context,new ChannelHttp("http://127.0.0.1:"+api.getAddress().getPort()),new QqGatewayClient(true),new WeixinClient())){
                String profile=channels.handle("profile.save",new JSONObject().put("name","Native QQ group").put("provider","qq").put("appId","group-bot").put("secret","qq-local-group-secret").put("enabled",true)).getString("id");
                GroupChannelBridge bridge=channels.groupBridge();GroupArtifactStore artifacts=new GroupArtifactStore(new File(android.getFilesDir(),"native-groups"));LinkedBlockingQueue<Runnable> queue=new LinkedBlockingQueue<>(16);ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();
                ModelConfig config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort()+"/v1","","synthetic-group-model",false,8,10,"openai_chat",256);
                GroupTaskHost host=new GroupTaskHost(tasks,command->{if(!queue.offer(command))throw new java.util.concurrent.RejectedExecutionException();},controls,()->config,new GroupSafeCapabilities.Services(artifacts,null,null),id->{},message->{});
                try(GroupsModule groups=new GroupsModule(new File(android.getFilesDir(),"native-groups"),bridge,host,bridge,()->{})){
                    bridge.bind(groups,command->{if(!queue.offer(command))throw new java.util.concurrent.RejectedExecutionException();});bridge.bindArtifacts(artifacts);host.setResultSink((request,receipt)->{if(receipt.status.equals("completed"))for(dev.pocketengine.features.groups.GroupHostPort.Output output:receipt.outputs)groups.sendOutput(request.requestId,output.outputId);});
                    JSONObject group=groups.ownerCommand("group.create",new JSONObject().put("name","Native actual group").put("provider","qq").put("accountId",profile).put("roomId","room-a"));group=groups.ownerCommand("member.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("senderId","member-a").put("displayName","Member").put("role","member").put("enabled",true));
                    JSONObject quota=group.getJSONObject("quota");quota.put("maxTokensPerTask",12000);group=groups.ownerCommand("policy.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","requester").put("quota",quota).put("grants",new JSONArray().put(new JSONObject().put("capability","public.answer").put("resource","public").put("expiresAt",System.currentTimeMillis()+3_600_000).put("allowGroupAudience",true))));
                    channels.handle("qq.start",new JSONObject().put("id",profile));Runnable ingress=queue.poll(6,TimeUnit.SECONDS);assertNotNull("native authenticated ingress never reached group bridge",ingress);ingress.run();Runnable run=queue.poll(2,TimeUnit.SECONDS);assertNotNull("group bridge stored metadata without scheduling original runner",run);run.run();
                    assertEquals(tasks.list().toString(),1,tasks.list().length());JSONObject task=tasks.list().getJSONObject(0);assertEquals(task.toString(),"completed",task.getString("status"));assertEquals(1,modelCalls.get());assertNotEquals("owner",task.getString("actorId"));assertEquals(group.getString("spaceId"),task.getString("spaceId"));assertEquals("group",task.getJSONObject("origin").getString("kind"));assertEquals("member-a",task.getJSONObject("origin").getString("senderId"));assertEquals(1,task.getInt("coordinatorEpoch"));assertTrue(task.getLong("groupReservedTokens")>0);assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());assertTrue(controls.isEmpty());
                    assertEquals("real original QQ group HTTP reply",1,replies.size());assertEquals("original-group-message",replies.get(0).getString("msg_id"));assertTrue(replies.get(0).getString("content").contains("Actual bounded group answer"));JSONArray outbox=channels.snapshot().getJSONArray("outbox");assertEquals(1,outbox.length());assertEquals("actual-group-platform-ack",outbox.getJSONObject(0).getString("receiptId"));assertTrue(outbox.getJSONObject(0).has("groupDeliveryId"));assertEquals(0,queue.size());
                    String request=task.getJSONObject("origin").getString("requestId");JSONObject known=groups.ownerCommand("task.inspect",new JSONObject().put("requestId",request));groups.sendOutput(request,known.getJSONObject("task").getJSONArray("outputs").getJSONObject(0).getString("outputId"));assertEquals(1,replies.size());assertEquals(1,modelCalls.get());
                }finally{host.close();}
            }finish.countDown();gateway.awaitFinished();
        }finally{finish.countDown();api.stop(0);model.stop(0);}
    }
}
