package dev.pocketengine.features.channels;

import android.app.Application;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original official message schemas and durable core, not live QQ qualification or account acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class QqGuildSceneTest {
    private static JSONObject wire(String type,String sender,String parent,String room,String text)throws Exception {
        boolean c2c=type.equals("C2C_MESSAGE_CREATE"),group=type.equals("GROUP_AT_MESSAGE_CREATE");JSONObject author=new JSONObject().put(c2c?"user_openid":group?"member_openid":"id",sender);
        JSONObject body=new JSONObject().put("id","same-message").put("author",author).put("content",text).put("timestamp",Instant.now().toString());if(group)body.put("group_openid",room);if(!c2c&&!group)body.put("guild_id",parent).put("channel_id",room);return new JSONObject().put("op",0).put("s",2).put("t",type).put("d",body);
    }
    private static ChannelCore core()throws Exception {ChannelCore core=new ChannelCore(Files.createTempDirectory("qq-guild-original-core-").toFile());core.saveProfile(new JSONObject().put("id","bot").put("name","QQ fixture").put("provider","qq").put("appId","fixture-app").put("enabled",true).put("qqGuildEnabled",true));return core;}
    @Test public void nativeScenesCannotInheritOwnerAndSameRawIdsRemainFourOriginalMessages()throws Exception {
        ChannelCore core=core();List<JSONObject> accepted=new ArrayList<>();for(String type:new String[]{"C2C_MESSAGE_CREATE","GROUP_AT_MESSAGE_CREATE","AT_MESSAGE_CREATE","DIRECT_MESSAGE_CREATE"})accepted.add(core.accept("bot",ChannelProtocol.canonicalQq(wire(type,"same-user","guild-one","same-room","hello")),"qq_gateway").getJSONObject("event"));
        assertEquals(4,accepted.stream().map(row->row.optString("id")).distinct().count());core.bindOwner("bot",accepted.get(0).getString("id"),true);assertTrue(core.route(accepted.get(0)).getBoolean("privateOwner"));assertFalse(core.route(accepted.get(3)).getBoolean("privateOwner"));assertFalse(core.route(accepted.get(2)).getBoolean("privateOwner"));
        core.bindOwner("bot",accepted.get(3).getString("id"),true);assertTrue(core.route(accepted.get(3)).getBoolean("privateOwner"));assertFalse(core.route(accepted.get(0)).getBoolean("privateOwner"));
        JSONObject another=wire("DIRECT_MESSAGE_CREATE","same-user","guild-two","same-room","hello");another.getJSONObject("d").put("id","other-message");JSONObject event=core.accept("bot",ChannelProtocol.canonicalQq(another),"qq_gateway").getJSONObject("event");assertFalse(core.route(event).getBoolean("privateOwner"));
        assertEquals("/v2/users/same-user/messages",QqConversation.endpoint(accepted.get(0)));assertEquals("/v2/groups/same-room/messages",QqConversation.endpoint(accepted.get(1)));assertEquals("/channels/same-room/messages",QqConversation.endpoint(accepted.get(2)));assertEquals("/dms/guild-one/messages",QqConversation.endpoint(accepted.get(3)));
    }
    @Test public void guildRequiresNativeOptInAndOnlyAuthenticatedBotMentionIsRemoved()throws Exception {
        ChannelCore core=core();JSONObject profile=core.profile("bot");profile.put("qqGuildEnabled",false);core.saveProfile(profile);JSONObject original=wire("AT_MESSAGE_CREATE","member","guild-one","channel-one","<@!verified-bot> /task check source");assertThrows(SecurityException.class,()->core.accept("bot",ChannelProtocol.canonicalQq(original),"qq_gateway"));
        assertEquals("/task check source",ChannelProtocol.canonicalQq(original,"verified-bot").getString("text"));assertTrue(ChannelProtocol.canonicalQq(original,"").getString("text").startsWith("<@!"));
        JSONObject other=wire("AT_MESSAGE_CREATE","member","guild-one","channel-one","<@other-person> /task not a bot prefix");assertTrue(ChannelProtocol.canonicalQq(other,"verified-bot").getString("text").startsWith("<@other-person>"));
        JSONObject bot=wire("AT_MESSAGE_CREATE","verified-bot","guild-one","channel-one","echo");assertThrows(IllegalArgumentException.class,()->ChannelProtocol.canonicalQq(bot,"verified-bot"));
        JSONObject numeric=wire("DIRECT_MESSAGE_CREATE","member","guild-one","channel-one","hi");numeric.getJSONObject("d").getJSONObject("author").put("id",12345);assertThrows(IllegalArgumentException.class,()->ChannelProtocol.canonicalQq(numeric));
    }
    @Test public void pureMediaIsRetainedAsUnreadInsteadOfBeingInventedAsUserText()throws Exception {
        JSONObject value=wire("C2C_MESSAGE_CREATE","owner","","","");value.getJSONObject("d").put("attachments",new JSONArray().put(new JSONObject().put("url","https://multimedia.nt.qq.com.cn/fixture.png").put("content_type","image/png").put("size",1)));JSONObject canonical=ChannelProtocol.canonicalQq(value);assertTrue(canonical.getBoolean("nativeMediaOnly"));assertEquals("",canonical.getString("text"));assertFalse(canonical.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0).getBoolean("contentRead"));assertEquals("",core().accept("bot",canonical,"qq_gateway").getJSONObject("event").getString("text"));
        assertThrows(IllegalArgumentException.class,()->ChannelProtocol.canonicalQq(wire("C2C_MESSAGE_CREATE","owner","","","")));
    }
    @Test public void gatewayIntentsAreExplicitAndGuildDispatchRetainsNativeReadyBotIdentity()throws Exception {
        List<JSONObject> sent=new ArrayList<>();List<String> ids=new ArrayList<>();QqGatewaySession.Events events=new QqGatewaySession.Events(){public void message(JSONObject value){}public void ready(boolean resumed){}public void message(JSONObject value,String nativeBot){ids.add(nativeBot);}};
        QqGatewaySession session=new QqGatewaySession(new QqGatewaySession.Resume(),"fixture-not-an-account",sent::add,events,()->{},0,(1<<25)|(1<<30)|(1<<12));session.receive(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",1000)).toString(),0);assertEquals((1<<25)|(1<<30)|(1<<12),sent.get(0).getJSONObject("d").getInt("intents"));session.receive(new JSONObject().put("op",0).put("s",1).put("t","READY").put("d",new JSONObject().put("session_id","original-session").put("user",new JSONObject().put("id","verified-bot"))).toString(),1);session.receive(wire("AT_MESSAGE_CREATE","member","guild-one","channel-one","hi").toString(),2);assertEquals(List.of("verified-bot"),ids);
        assertThrows(SecurityException.class,()->new QqGatewaySession(new QqGatewaySession.Resume(),"token",sent::add,events,()->{},0,(1<<25)|(1<<1)));
    }
    @Test public void ownerTaskDedupUsesTheNativeGuildDmNamespaceNotOnlyAReusedMessageString()throws Exception {
        ChannelCore core=core();JSONObject first=core.accept("bot",ChannelProtocol.canonicalQq(wire("C2C_MESSAGE_CREATE","same-user","","","binding")),"qq_gateway").getJSONObject("event");core.bindOwner("bot",first.getString("id"),true);core.setOwnerTasks("bot",true,true);
        JSONObject wire1=wire("C2C_MESSAGE_CREATE","same-user","","","/task first controlled task");wire1.getJSONObject("d").put("id","task-same");JSONObject event1=core.accept("bot",ChannelProtocol.canonicalQq(wire1),"qq_gateway").getJSONObject("event");String request1=core.prepareHostTask(event1.getString("id")).getJSONObject("request").getString("requestId");
        JSONObject bind=wire("DIRECT_MESSAGE_CREATE","same-user","guild-one","channel-one","native new binding");bind.getJSONObject("d").put("id","new-binding");JSONObject binding=core.accept("bot",ChannelProtocol.canonicalQq(bind),"qq_gateway").getJSONObject("event");core.bindOwner("bot",binding.getString("id"),true);core.setOwnerTasks("bot",true,true);
        JSONObject wire2=wire("DIRECT_MESSAGE_CREATE","same-user","guild-one","channel-one","/task second controlled task");wire2.getJSONObject("d").put("id","task-same");JSONObject event2=core.accept("bot",ChannelProtocol.canonicalQq(wire2),"qq_gateway").getJSONObject("event");String request2=core.prepareHostTask(event2.getString("id")).getJSONObject("request").getString("requestId");assertNotEquals(request1,request2);
        assertThrows(SecurityException.class,()->core.authorizeHostTaskCommand(request1,event2.getString("id")));
    }
    @Test public void mixedElementsKeepTheirActualPositionsAndDoNotPromoteReferencedTextToTheCurrentCommand()throws Exception {
        JSONObject original=wire("C2C_MESSAGE_CREATE","owner","","","");original.getJSONObject("d").put("message_type",103).put("msg_elements",new JSONArray().put(new JSONObject().put("msg_idx","quote-one").put("content","quoted earlier text")).put(new JSONObject().put("msg_idx","current-one").put("content","current fragment")).put(new JSONObject().put("msg_idx","current-two").put("attachments",new JSONArray().put(new JSONObject().put("url","https://multimedia.nt.qq.com.cn/example.png").put("content_type","image/png").put("size",2)))));
        JSONObject actual=ChannelProtocol.canonicalQq(original),parts=actual.getJSONObject("messageParts");assertEquals("current fragment",actual.getString("text"));assertEquals("quote",parts.getJSONArray("parts").getJSONObject(0).getString("kind"));assertEquals(1,parts.getJSONArray("parts").getJSONObject(1).getJSONObject("source").getInt("position"));assertEquals(1,ChannelMessageParts.qqImages(parts).length());assertFalse(actual.getString("text").contains("quoted"));assertTrue(parts.getString("ordering").contains("cross_field_order_unspecified"));
    }

    @Test public void actualReplyHttpUsesOnlyTheOfficialEndpointAndBodyForEachNativeScene()throws Exception {
        java.util.List<String> paths=new java.util.ArrayList<>();java.util.List<JSONObject> bodies=new java.util.ArrayList<>();dev.pocketengine.testutil.HttpServer server=dev.pocketengine.testutil.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));JSONObject response;
            if(path.equals("/app/getAppAccessToken"))response=new JSONObject().put("access_token","fixture-token").put("expires_in",7200);else{paths.add(path);bodies.add(body);assertEquals("QQBot fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("id","fixture-receipt-"+paths.size());}
            byte[] raw=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);
        }catch(Exception failure){throw new java.io.IOException(failure);}finally{exchange.close();}});server.start();
        try{ChannelHttp http=new ChannelHttp("http://127.0.0.1:"+server.getAddress().getPort());JSONObject profile=new JSONObject().put("id","bot").put("appId","app");int index=0;
            for(String type:new String[]{"C2C_MESSAGE_CREATE","GROUP_AT_MESSAGE_CREATE","AT_MESSAGE_CREATE","DIRECT_MESSAGE_CREATE"}){JSONObject event=ChannelProtocol.canonicalQq(wire(type,"same-user","guild-one","same-room","hello"));http.sendQq(profile,"fixture-not-an-account",event,new JSONObject().put("text","reviewed reply").put("sequence",++index),new dev.pocketengine.core.RunControl(),()->{});}
            assertEquals(List.of("/v2/users/same-user/messages","/v2/groups/same-room/messages","/channels/same-room/messages","/dms/guild-one/messages"),paths);for(int i=0;i<4;i++){assertEquals("same-message",bodies.get(i).getString("msg_id"));assertEquals("reviewed reply",bodies.get(i).getString("content"));if(i<2){assertEquals(0,bodies.get(i).getInt("msg_type"));assertEquals(i+1,bodies.get(i).getInt("msg_seq"));}else{assertFalse(bodies.get(i).has("msg_type"));assertFalse(bodies.get(i).has("msg_seq"));}}
        }finally{server.stop(0);}
    }

}
