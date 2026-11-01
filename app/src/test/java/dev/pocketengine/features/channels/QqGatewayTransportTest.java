package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QqGatewayTransportTest {
    @Test public void actualWebSocketHandshakeIdentifyDmHeartbeatAndResumeUseOneSession()throws Exception {
        AtomicInteger received=new AtomicInteger();QqGatewaySession.Resume resume=new QqGatewaySession.Resume();
        try(QqGatewayFixture fixture=new QqGatewayFixture(peer->{
            assertFalse(peer.handshake.toLowerCase(java.util.Locale.ROOT).contains("authorization:"));assertFalse(peer.handshake.contains("fixture-token"));
            peer.send(QqGatewaySessionTest.hello());JSONObject identify=peer.read();assertEquals(2,identify.getInt("op"));assertEquals("QQBot fixture-token",identify.getJSONObject("d").getString("token"));
            peer.send(QqGatewaySessionTest.ready(1));peer.send(QqGatewaySessionTest.message(2,"m1","你好"));peer.send(new JSONObject().put("op",1));JSONObject heartbeat=peer.read();assertEquals(1,heartbeat.getInt("op"));assertEquals(2,heartbeat.getLong("d"));peer.send(new JSONObject().put("op",11));peer.send(new JSONObject().put("op",7));
        },peer->{
            peer.send(QqGatewaySessionTest.hello());JSONObject request=peer.read();assertEquals(6,request.getInt("op"));assertEquals(2,request.getJSONObject("d").getLong("seq"));assertEquals("fixture-session",request.getJSONObject("d").getString("session_id"));
            peer.send(QqGatewaySessionTest.message(2,"m1","你好"));peer.send(QqGatewaySessionTest.message(3,"m2","断线期间消息"));peer.send(new JSONObject().put("op",0).put("s",4).put("t","RESUMED").put("d",""));peer.close(4914);
        });QqGatewayClient client=new QqGatewayClient(true)){
            QqGatewaySession.Events events=new QqGatewaySession.Events(){public void message(JSONObject payload){received.incrementAndGet();}public void ready(boolean ignored){}};
            ChannelHttp.GatewayAccess access=new ChannelHttp.GatewayAccess(fixture.url(),"fixture-token");QqGatewayClient.Exit first=client.run(access,resume,new RunControl(),()->{},events);assertFalse(first.fatal);
            QqGatewayClient.Exit last=client.run(access,resume,new RunControl(),()->{},events);assertTrue(last.fatal);assertEquals(4914,last.code);assertEquals(2,received.get());fixture.awaitFinished();
        }
    }
    @Test public void cancellingAnActualSocketStopsPromptlyAndLateFramesCannotReachConsumer()throws Exception {
        CountDownLatch identified=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger received=new AtomicInteger();RunControl control=new RunControl();java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(QqGatewayFixture fixture=new QqGatewayFixture(peer->{peer.send(QqGatewaySessionTest.hello());peer.read();peer.send(QqGatewaySessionTest.ready(1));identified.countDown();release.await(5,TimeUnit.SECONDS);});QqGatewayClient client=new QqGatewayClient(true)){
            java.util.concurrent.Future<QqGatewayClient.Exit> result=worker.submit(()->client.run(new ChannelHttp.GatewayAccess(fixture.url(),"token"),new QqGatewaySession.Resume(),control,()->{},new QqGatewaySession.Events(){public void message(JSONObject ignored){received.incrementAndGet();}public void ready(boolean ignored){}}));
            assertTrue(identified.await(3,TimeUnit.SECONDS));long start=System.nanoTime();control.cancel();assertThrows(java.util.concurrent.ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1800);assertEquals(0,received.get());
        }finally{release.countDown();worker.shutdownNow();}
    }
    @Test public void actualHttpBootstrapReusesTokenAndRepliesOnlyToOriginalOfficialBotOpenId()throws Exception {
        AtomicInteger tokens=new AtomicInteger(),gateways=new AtomicInteger(),sends=new AtomicInteger();AtomicReference<JSONObject> sent=new AtomicReference<>();HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();JSONObject response;
            if(path.equals("/app/getAppAccessToken")){assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestHeaders().getFirst("Authorization"));JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("12345",body.getString("appId"));assertEquals("fixture-secret",body.getString("clientSecret"));tokens.incrementAndGet();response=new JSONObject().put("access_token","fixture-token").put("expires_in","7200");}
            else{assertEquals("QQBot fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));if(path.equals("/gateway")){assertEquals("GET",exchange.getRequestMethod());assertEquals(0,exchange.getRequestBody().readAllBytes().length);gateways.incrementAndGet();response=new JSONObject().put("url","wss://api.bot.qq.com/websocket/");}
                else{assertEquals("/v2/users/original-openid/messages",path);sends.incrementAndGet();sent.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));response=new JSONObject().put("id","provider-receipt");}}
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable failure){throw new java.io.IOException("fixture endpoint failure",failure);}finally{exchange.close();}});http.start();
        try{
            ChannelHttp delivery=new ChannelHttp("http://127.0.0.1:"+http.getAddress().getPort());JSONObject profile=new JSONObject().put("id","profile-1").put("appId","12345");ChannelHttp.GatewayAccess access=delivery.qqGateway(profile,"fixture-secret",new RunControl(),()->{});assertEquals("fixture-token",access.token);
            delivery.qqGateway(profile,"fixture-secret",new RunControl(),()->{});assertEquals(1,tokens.get());assertEquals(2,gateways.get());
            JSONObject receipt=delivery.sendQq(profile,"fixture-secret",new JSONObject().put("kind","dm").put("senderId","original-openid").put("messageId","original-message"),new JSONObject().put("text","结果").put("sequence",1),new RunControl(),()->{});
            assertEquals("provider-receipt",receipt.getString("id"));assertEquals("original-message",sent.get().getString("msg_id"));assertEquals(1,sent.get().getInt("msg_seq"));assertEquals(1,sends.get());assertEquals(1,tokens.get());delivery.forgetQqToken("profile-1");delivery.qqGateway(profile,"fixture-secret",new RunControl(),()->{});assertEquals(2,tokens.get());
        }finally{http.stop(0);}
    }
    @Test public void adapterAuthenticatesIntoExistingCoreWithoutAcceptingRemoteOwnerClaims()throws Exception {
        java.nio.file.Path path=Files.createTempDirectory("qq-wire-adapter-");CountDownLatch allowSecond=new CountDownLatch(1),received=new CountDownLatch(2);AtomicInteger accepted=new AtomicInteger();AtomicReference<String> endpoint=new AtomicReference<>();AtomicBoolean revoked=new AtomicBoolean();
        try(QqGatewayFixture fixture=new QqGatewayFixture(peer->{
            peer.send(QqGatewaySessionTest.hello());peer.read();peer.send(QqGatewaySessionTest.ready(1));peer.send(QqGatewaySessionTest.message(2,"bind","绑定").put("principalId","owner").put("spaceId","private"));assertTrue(allowSecond.await(4,TimeUnit.SECONDS));peer.send(QqGatewaySessionTest.message(3,"owner-task","/task 真实入口"));peer.send(QqGatewaySessionTest.message(4,"owner-task","/task 真实入口"));peer.close(4914);
        })){
            endpoint.set(fixture.url());ChannelCore core=new ChannelCore(path.toFile());String id=core.saveProfile(new JSONObject().put("name","QQ官方Bot").put("provider","qq").put("appId","12345").put("enabled",true)).getString("id");
            HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();JSONObject response=exchange.getRequestURI().getPath().equals("/gateway")?new JSONObject().put("url",endpoint.get()):new JSONObject().put("access_token","token").put("expires_in",7200);byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failed){throw new java.io.IOException(failed);}finally{exchange.close();}});http.start();
            try(QqGatewayAdapter adapter=new QqGatewayAdapter(core,new ChannelHttp("http://127.0.0.1:"+http.getAddress().getPort()),ignored->revoked.get()?"replaced":"secret",(profile,incoming)->{accepted.incrementAndGet();assertEquals(id,profile.getString("id"));assertEquals("qq_gateway",incoming.getJSONObject("event").getString("authenticatedBy"));received.countDown();},ignored->{},new QqGatewayClient(true))){
                adapter.handle("qq.start",new JSONObject().put("id",id));await(()->core.snapshot().getJSONArray("inbox").length()==1);JSONObject bind=core.snapshot().getJSONArray("inbox").getJSONObject(0);assertFalse(bind.getJSONObject("route").getBoolean("privateOwner"));assertEquals(0,core.snapshot().getJSONArray("bindings").length());core.bindOwner(id,bind.getString("id"),true);allowSecond.countDown();assertTrue(received.await(4,TimeUnit.SECONDS));fixture.awaitFinished();await(()->!adapter.snapshot().getJSONArray("connections").getJSONObject(0).getBoolean("monitorRunning"));
                assertEquals(2,accepted.get());assertEquals(2,core.snapshot().getJSONArray("inbox").length());assertEquals("platform_blocked",adapter.snapshot().getJSONArray("connections").getJSONObject(0).getString("status"));String serialized=adapter.snapshot().toString();assertFalse(serialized.contains("fixture-token"));assertFalse(serialized.contains("session_id"));assertFalse(serialized.contains("secret"));core.removeProfile(id);adapter.forget(id);assertEquals(0,adapter.snapshot().getJSONArray("connections").length());
            }finally{http.stop(0);}
        }finally{allowSecond.countDown();try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(path)){files.sorted(java.util.Comparator.reverseOrder()).forEach(file->{try{Files.delete(file);}catch(Exception ignored){}});}}
    }
    @Test public void credentialRotationFencesActualSocketBeforeAnyLaterInboundEvent()throws Exception {
        java.nio.file.Path path=Files.createTempDirectory("qq-revoke-adapter-");CountDownLatch ready=new CountDownLatch(1),late=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicReference<String> credential=new AtomicReference<>("old-secret");AtomicInteger consumed=new AtomicInteger();
        try(QqGatewayFixture fixture=new QqGatewayFixture(peer->{
            peer.send(QqGatewaySessionTest.hello());peer.read();peer.send(QqGatewaySessionTest.ready(1));ready.countDown();assertTrue(late.await(4,TimeUnit.SECONDS));try{peer.send(QqGatewaySessionTest.message(2,"late","/task 不得从旧连接启动"));}catch(java.net.SocketException stopped){}finish.await(4,TimeUnit.SECONDS);
        })){
            ChannelCore core=new ChannelCore(path.toFile());String id=core.saveProfile(new JSONObject().put("name","QQ revoke fixture").put("provider","qq").put("appId","12345").put("enabled",true)).getString("id");
            HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();JSONObject response=exchange.getRequestURI().getPath().equals("/gateway")?new JSONObject().put("url",fixture.url()):new JSONObject().put("access_token","old-token").put("expires_in",7200);byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failed){throw new java.io.IOException(failed);}finally{exchange.close();}});http.start();
            try(QqGatewayAdapter adapter=new QqGatewayAdapter(core,new ChannelHttp("http://127.0.0.1:"+http.getAddress().getPort()),ignored->credential.get(),(profile,incoming)->consumed.incrementAndGet(),ignored->{},new QqGatewayClient(true))){
                adapter.handle("qq.start",new JSONObject().put("id",id));assertTrue(ready.await(3,TimeUnit.SECONDS));await(()->adapter.snapshot().getJSONArray("connections").getJSONObject(0).optString("status").equals("listening"));credential.set("new-secret");late.countDown();await(()->!adapter.snapshot().getJSONArray("connections").getJSONObject(0).getBoolean("monitorRunning"));
                assertEquals("configuration_changed",adapter.snapshot().getJSONArray("connections").getJSONObject(0).getString("status"));assertEquals(0,consumed.get());assertEquals(0,core.snapshot().getJSONArray("inbox").length());assertEquals(0,core.snapshot().getJSONArray("taskRequests").length());
            }finally{http.stop(0);}
            finish.countDown();fixture.awaitFinished();
        }finally{late.countDown();finish.countDown();try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(path)){files.sorted(java.util.Comparator.reverseOrder()).forEach(file->{try{Files.delete(file);}catch(Exception ignored){}});}}
    }
    interface Check {boolean get()throws Exception;}
    static void await(Check check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(check.get())return;Thread.sleep(20);}assertTrue("Timed out waiting for focused fixture",check.get());}
}
