package dev.pocketengine.features.channels;

import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression for the independently reproduced quota bookkeeping/receiver fence defect. */
public final class QqAutoReplyConfigurationFenceTest {
    @Test public void twoOrdinaryAutoRepliesKeepSameAuthenticatedReceiverAfterQuotaWrites()throws Exception {
        java.nio.file.Path path=Files.createTempDirectory("qq-auto-reply-quota-");CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger consumed=new AtomicInteger(),identifies=new AtomicInteger();
        try(QqGatewayFixture fixture=new QqGatewayFixture(peer->{
            peer.send(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",45000)));
            assertEquals(2,peer.read().getInt("op"));identifies.incrementAndGet();peer.send(QqGatewaySessionTest.ready(1));peer.send(QqGatewaySessionTest.message(2,"chat-1","第一个普通问题"));
            assertTrue(first.await(4,TimeUnit.SECONDS));peer.send(QqGatewaySessionTest.message(3,"chat-2","第二个普通问题"));assertTrue(second.await(4,TimeUnit.SECONDS));finish.await(4,TimeUnit.SECONDS);
        })){
            ChannelCore core=new ChannelCore(path.toFile());String id=core.saveProfile(new JSONObject().put("name","QQ autoReply fixture").put("provider","qq").put("appId","12345").put("enabled",true).put("autoReply",true)).getString("id");long setupVersion=core.profile(id).getLong("updatedAt");
            HttpServer http=bootstrap(fixture);try(QqGatewayAdapter adapter=new QqGatewayAdapter(core,new ChannelHttp("http://127.0.0.1:"+http.getAddress().getPort()),ignored->"secret",(profile,incoming)->{
                assertTrue(profile.getBoolean("autoReply"));core.beginAnswer(incoming.getJSONObject("event").getString("id"));if(consumed.incrementAndGet()==1)first.countDown();else second.countDown();
            },ignored->{},new QqGatewayClient(true))){
                adapter.handle("qq.start",new JSONObject().put("id",id));assertTrue(second.await(4,TimeUnit.SECONDS));JSONObject state=adapter.snapshot().getJSONArray("connections").getJSONObject(0);
                assertEquals(2,consumed.get());assertEquals(2,core.profile(id).getInt("quotaUsed"));assertTrue(core.profile(id).getLong("quotaWindowStart")>0);assertEquals(setupVersion,core.profile(id).getLong("updatedAt"));
                assertEquals(2,core.snapshot().getJSONArray("inbox").length());assertEquals(1,identifies.get());assertEquals("listening",state.getString("status"));assertTrue(state.getBoolean("monitorRunning"));
            }finally{finish.countDown();http.stop(0);}fixture.awaitFinished();
        }finally{first.countDown();second.countDown();finish.countDown();remove(path);}
    }
    @Test public void actualSavedConfigurationChangeStillFencesOldReceiverBeforeNextMessage()throws Exception {
        for(String field:new String[]{"name","autoReply","enabled"})checkConfigurationChange(field);
    }
    private void checkConfigurationChange(String field)throws Exception {
        java.nio.file.Path path=Files.createTempDirectory("qq-config-fence-");CountDownLatch ready=new CountDownLatch(1),late=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger consumed=new AtomicInteger();
        try(QqGatewayFixture fixture=new QqGatewayFixture(peer->{
            peer.send(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",45000)));peer.read();peer.send(QqGatewaySessionTest.ready(1));ready.countDown();assertTrue(late.await(4,TimeUnit.SECONDS));
            try{peer.send(QqGatewaySessionTest.message(2,"late","不得从旧配置继续接收"));}catch(java.net.SocketException stopped){}finish.await(4,TimeUnit.SECONDS);
        })){
            ChannelCore core=new ChannelCore(path.toFile());String id=core.saveProfile(new JSONObject().put("name","before").put("provider","qq").put("appId","12345").put("enabled",true).put("autoReply",true)).getString("id");HttpServer http=bootstrap(fixture);
            try(QqGatewayAdapter adapter=new QqGatewayAdapter(core,new ChannelHttp("http://127.0.0.1:"+http.getAddress().getPort()),ignored->"secret",(profile,incoming)->consumed.incrementAndGet(),ignored->{},new QqGatewayClient(true))){
                adapter.handle("qq.start",new JSONObject().put("id",id));assertTrue(ready.await(3,TimeUnit.SECONDS));QqGatewayTransportTest.await(()->adapter.snapshot().getJSONArray("connections").getJSONObject(0).optString("status").equals("listening"));
                JSONObject changed=core.profile(id);changed.put(field,field.equals("name")?"after":false);core.saveProfile(changed);late.countDown();QqGatewayTransportTest.await(()->!adapter.snapshot().getJSONArray("connections").getJSONObject(0).getBoolean("monitorRunning"));
                assertEquals(field,"configuration_changed",adapter.snapshot().getJSONArray("connections").getJSONObject(0).getString("status"));assertEquals(field,0,consumed.get());assertEquals(0,core.snapshot().getJSONArray("inbox").length());
            }finally{finish.countDown();http.stop(0);}fixture.awaitFinished();
        }finally{late.countDown();finish.countDown();remove(path);}
    }
    private static HttpServer bootstrap(QqGatewayFixture fixture)throws Exception {
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();JSONObject response=exchange.getRequestURI().getPath().equals("/gateway")?new JSONObject().put("url",fixture.url()):new JSONObject().put("access_token","token").put("expires_in",7200);byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failed){throw new java.io.IOException(failed);}finally{exchange.close();}});http.start();return http;
    }
    private static void remove(java.nio.file.Path path)throws Exception {try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(path)){files.sorted(java.util.Comparator.reverseOrder()).forEach(file->{try{Files.delete(file);}catch(Exception ignored){}});}}
}
