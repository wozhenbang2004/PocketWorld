package dev.pocketengine.features.channels;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QqGatewaySessionTest {
    static JSONObject hello()throws Exception{return new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",1000));}
    static JSONObject ready(long sequence)throws Exception{return new JSONObject().put("op",0).put("s",sequence).put("t","READY").put("d",new JSONObject().put("session_id","fixture-session").put("user",new JSONObject().put("id","bot-id").put("bot",true)));}
    static JSONObject message(long sequence,String id,String text)throws Exception{return new JSONObject().put("op",0).put("s",sequence).put("t","C2C_MESSAGE_CREATE").put("d",new JSONObject().put("id",id).put("author",new JSONObject().put("user_openid","fixture-owner")).put("content",text).put("timestamp",java.time.Instant.now().toString()));}
    private static QqGatewaySession.Events events(AtomicInteger messages){return new QqGatewaySession.Events(){public void message(JSONObject payload){messages.incrementAndGet();}public void ready(boolean resumed){}};}
    @Test public void freshSessionUsesCurrentOfficialBotTokenIntentAndCannotAcceptPreReadyDm()throws Exception {
        List<JSONObject> sent=new ArrayList<>();AtomicInteger received=new AtomicInteger();QqGatewaySession session=new QqGatewaySession(new QqGatewaySession.Resume(),"fixture-token",sent::add,events(received),()->{},0);
        session.receive(hello().toString(),0);JSONObject identify=sent.get(0);assertEquals(2,identify.getInt("op"));assertEquals("QQBot fixture-token",identify.getJSONObject("d").getString("token"));
        assertEquals(1<<25,identify.getJSONObject("d").getInt("intents"));assertEquals("[0,1]",identify.getJSONObject("d").getJSONArray("shard").toString());
        assertThrows(SecurityException.class,()->session.receive(message(2,"early","/task 不得执行").toString(),10));assertEquals(0,received.get());
        session.receive(ready(1).toString(),20);session.receive(message(2,"m1","普通文字").toString(),30);assertEquals(1,received.get());
    }
    @Test public void resumeCommitsOnlyHandledSequenceAndSupportsReplayBeforeResumed()throws Exception {
        QqGatewaySession.Resume resume=new QqGatewaySession.Resume();List<JSONObject> firstSent=new ArrayList<>();AtomicInteger received=new AtomicInteger();
        QqGatewaySession first=new QqGatewaySession(resume,"old-token",firstSent::add,new QqGatewaySession.Events(){public void ready(boolean ignored){}public void message(JSONObject ignored)throws Exception{throw new java.io.IOException("fixture disk failure");}},()->{},0);
        first.receive(hello().toString(),0);first.receive(ready(1).toString(),1);assertThrows(java.io.IOException.class,()->first.receive(message(2,"m1","消息").toString(),2));first.retire();
        List<JSONObject> sent=new ArrayList<>();AtomicBoolean resumed=new AtomicBoolean();QqGatewaySession next=new QqGatewaySession(resume,"new-token",sent::add,new QqGatewaySession.Events(){public void ready(boolean value){resumed.set(value);}public void message(JSONObject ignored){received.incrementAndGet();}},()->{},100);
        next.receive(hello().toString(),100);JSONObject request=sent.get(0);assertEquals(6,request.getInt("op"));assertEquals("QQBot new-token",request.getJSONObject("d").getString("token"));assertEquals(1,request.getJSONObject("d").getLong("seq"));
        next.receive(message(2,"m1","消息").toString(),101);next.receive(message(2,"m1","消息").toString(),102);assertEquals(1,received.get());
        next.receive(new JSONObject().put("op",0).put("t","RESUMED").put("s",3).put("d","").toString(),103);assertTrue(resumed.get());
        next.tick(1100);assertEquals(3,sent.get(1).getLong("d"));
    }
    @Test public void heartbeatEchoesLastSequenceRequiresAckAndSupportsServerRequests()throws Exception {
        List<JSONObject> sent=new ArrayList<>();QqGatewaySession session=new QqGatewaySession(new QqGatewaySession.Resume(),"fixture",sent::add,events(new AtomicInteger()),()->{},0);
        session.receive(hello().toString(),0);session.tick(1000);assertEquals(1,sent.get(1).getInt("op"));assertTrue(sent.get(1).isNull("d"));
        session.receive(new JSONObject().put("op",11).toString(),1100);session.receive(ready(1).toString(),1200);session.receive(new JSONObject().put("op",1).toString(),1300);assertEquals(1,sent.get(2).getLong("d"));
        assertThrows(QqGatewaySession.Reconnect.class,()->session.tick(2000));
    }
    @Test public void revokedOrRetiredSessionCannotSendTokenOrConsumeLateMessages()throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);List<JSONObject> sent=new ArrayList<>();AtomicInteger messages=new AtomicInteger();
        QqGatewaySession session=new QqGatewaySession(new QqGatewaySession.Resume(),"not-in-logs",sent::add,events(messages),()->{if(!allowed.get())throw new SecurityException("revoked");},0);
        allowed.set(false);assertThrows(SecurityException.class,()->session.receive(hello().toString(),0));assertEquals(0,sent.size());allowed.set(true);session.receive(hello().toString(),1);session.receive(ready(1).toString(),2);
        allowed.set(false);assertThrows(SecurityException.class,()->session.receive(message(2,"late","/task 不得执行").toString(),3));assertEquals(0,messages.get());
        allowed.set(true);session.retire();assertThrows(InterruptedException.class,()->session.receive(message(2,"late","消息").toString(),4));assertThrows(InterruptedException.class,()->session.tick(1000));
    }
    @Test public void invalidSessionReidentifiesAndMalformedNumbersNeverBecomeEvents()throws Exception {
        QqGatewaySession.Resume resume=new QqGatewaySession.Resume();QqGatewaySession first=new QqGatewaySession(resume,"token",ignored->{},events(new AtomicInteger()),()->{},0);first.receive(hello().toString(),0);first.receive(ready(1).toString(),1);
        assertThrows(IllegalArgumentException.class,()->first.receive(message(2,"float","消息").put("s",2.5).toString(),2));
        assertThrows(QqGatewaySession.Reconnect.class,()->first.receive(new JSONObject().put("op",9).put("d",false).toString(),3));
        List<JSONObject> sent=new ArrayList<>();new QqGatewaySession(resume,"token",sent::add,events(new AtomicInteger()),()->{},10).receive(hello().toString(),10);assertEquals(2,sent.get(0).getInt("op"));
        assertThrows(IllegalArgumentException.class,()->new QqGatewaySession(new QqGatewaySession.Resume(),"token",ignored->{},events(new AtomicInteger()),()->{},0).receive(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",1)).toString(),0));
    }
    @Test public void officialGatewayValidationNeverForwardsBotTokenToArbitraryOrInsecureHosts()throws Exception {
        assertEquals("wss://api.bot.qq.com/websocket/",QqGatewayClient.validateGateway("wss://api.bot.qq.com/websocket/"));assertEquals("wss://api.sgroup.qq.com/websocket",QqGatewayClient.validateGateway("wss://api.sgroup.qq.com/websocket"));
        for(String address:new String[]{"ws://api.bot.qq.com/websocket","wss://api.bot.qq.com.evil.example/websocket","wss://evil@api.bot.qq.com/websocket","wss://api.bot.qq.com:444/websocket","wss://api.bot.qq.com/websocket?token=x","wss://api.bot.qq.com/other","ws://127.0.0.1:8000/websocket"})assertThrows(SecurityException.class,()->QqGatewayClient.validateGateway(address));
        assertTrue(QqGatewayClient.Exit.closed(4914).fatal);assertTrue(QqGatewayClient.Exit.closed(4915).fatal);assertTrue(QqGatewayClient.Exit.closed(4004).refreshToken);assertTrue(QqGatewayClient.Exit.closed(4006).clearSession);assertEquals(60_000,QqGatewayClient.Exit.closed(4008).minimumDelay);
    }
    @Test public void gatewayAuthenticationIsScopedToQqAndKeepsNativeOwnerBinding()throws Exception {
        java.nio.file.Path path=Files.createTempDirectory("qq-gateway-core-");try{
            ChannelCore core=new ChannelCore(path.toFile());String qq=core.saveProfile(new JSONObject().put("name","QQ官方机器人").put("provider","qq").put("appId","12345").put("enabled",true)).getString("id");
            String relay=core.saveProfile(new JSONObject().put("name","bridge").put("provider","webhook").put("enabled",true)).getString("id");JSONObject canonical=ChannelProtocol.canonicalQq(message(2,"m1","绑定").put("principalId","owner"));
            assertThrows(SecurityException.class,()->core.accept(relay,canonical,"qq_gateway"));assertThrows(SecurityException.class,()->core.accept(qq,canonical,"trusted_relay"));
            JSONObject event=core.accept(qq,canonical,"qq_gateway").getJSONObject("event");assertEquals("qq_gateway",event.getString("authenticatedBy"));assertFalse(event.getJSONObject("route").getBoolean("privateOwner"));
            core.bindOwner(qq,event.getString("id"),true);assertTrue(core.route(event).getBoolean("privateOwner"));assertFalse(core.route(event).getBoolean("ownerTasksEnabled"));assertTrue(new ChannelCore(path.toFile()).accept(qq,canonical,"qq_signature").getBoolean("duplicate"));
        }finally{try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(path)){files.sorted(java.util.Comparator.reverseOrder()).forEach(file->{try{Files.delete(file);}catch(Exception ignored){}});}}
    }
}
