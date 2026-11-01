package dev.pocketengine.features.channels;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WeixinClientTest {
    private JSONObject message()throws Exception{return new JSONObject().put("message_id",12345).put("from_user_id","human@im.wechat").put("message_type",1).put("message_state",2)
            .put("create_time_ms",System.currentTimeMillis()).put("context_token","private-context")
            .put("item_list",new JSONArray().put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","你好"))));}
    @Test public void canonicalTextDropsOwnerClaimsAndContextCredentials()throws Exception{
        JSONObject input=message().put("principalId","owner").put("spaceId","private").put("role","owner");JSONObject canonical=WeixinClient.canonical(input);
        assertEquals("wx-12345",canonical.getString("messageId"));assertEquals("human@im.wechat",canonical.getString("senderId"));assertEquals("你好",canonical.getString("text"));
        assertEquals("dm",canonical.getString("kind"));assertFalse(canonical.has("context_token"));assertFalse(canonical.has("principalId"));assertFalse(canonical.has("spaceId"));
    }
    @Test public void groupsMediaAndPartialBotMessagesAreNotPretendedAsPrivateText()throws Exception{
        assertNull(WeixinClient.canonical(message().put("group_id","group-1")));
        assertNull(WeixinClient.canonical(message().put("message_type",2)));assertNull(WeixinClient.canonical(message().put("message_state",1)));
        assertNull(WeixinClient.canonical(message().put("message_type",1.5)));
        assertNull(WeixinClient.canonical(message().put("item_list",new JSONArray().put(new JSONObject().put("type",2)))));
        JSONObject noId=message();noId.remove("message_id");assertThrows(IllegalArgumentException.class,()->WeixinClient.canonical(noId));
    }
    @Test public void officialRootValidationNeverAcceptsRedirectCredentialExfiltration()throws Exception{
        assertEquals("https://ilinkai.weixin.qq.com",WeixinClient.validateApiBase("https://ilinkai.weixin.qq.com/"));
        assertThrows(SecurityException.class,()->WeixinClient.validateApiBase("https://weixin.qq.com.attacker.example"));
        assertThrows(SecurityException.class,()->WeixinClient.validateApiBase("https://attacker@ilinkai.weixin.qq.com"));
        assertThrows(SecurityException.class,()->WeixinClient.validateApiBase("http://ilinkai.weixin.qq.com"));
        assertThrows(SecurityException.class,()->WeixinClient.validateApiBase("https://ilinkai.weixin.qq.com/path"));
        assertThrows(SecurityException.class,()->WeixinClient.validateApiBase("https://ilinkai.weixin.qq.com?token=x"));
    }
    @Test public void realPollCarriesOfficialHeadersOpaqueCursorAndOwnClientIdentity()throws Exception{
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("ret",0).put("msgs",new JSONArray().put(message())).put("get_updates_buf","next-opaque"))){
            JSONObject reply=new WeixinClient(endpoint.base()).poll(endpoint.base(),"bot-token","prior-opaque",new RunControl(),()->{});
            assertEquals("/ilink/bot/getupdates",endpoint.path.get());assertEquals("Bearer bot-token",endpoint.auth.get());assertEquals("ilink_bot_token",endpoint.authType.get());
            JSONObject body=endpoint.request.get();assertEquals("prior-opaque",body.getString("get_updates_buf"));assertEquals("PocketWorld/0.3.0",body.getJSONObject("base_info").getString("bot_agent"));
            assertEquals("next-opaque",reply.getString("get_updates_buf"));assertEquals(1,reply.getJSONArray("msgs").length());
        }
    }
    @Test public void sendEchoesExactContextAndClientIdWithRetZeroAcceptance()throws Exception{
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("ret",0))){
            JSONObject event=new JSONObject().put("senderId","human@im.wechat"),draft=new JSONObject().put("id","stable-outbox-id").put("text","真实回答");
            JSONObject response=new WeixinClient(endpoint.base()).send(endpoint.base(),"bot-token",event,draft,"event-context",new RunControl(),()->{});
            JSONObject sent=endpoint.request.get().getJSONObject("msg");assertEquals("human@im.wechat",sent.getString("to_user_id"));assertEquals("stable-outbox-id",sent.getString("client_id"));assertEquals("event-context",sent.getString("context_token"));
            assertEquals("真实回答",sent.getJSONArray("item_list").getJSONObject(0).getJSONObject("text_item").getString("text"));assertEquals("stable-outbox-id",response.getString("id"));assertTrue(response.getBoolean("accepted"));
        }
    }
    @Test public void missingContextOrRevokedGuardSendsNoMessageBytes()throws Exception{
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("ret",0))){
            WeixinClient client=new WeixinClient(endpoint.base());JSONObject event=new JSONObject().put("senderId","u"),draft=new JSONObject().put("id","id").put("text","回答");
            assertThrows(IllegalStateException.class,()->client.send(endpoint.base(),"token",event,draft,"",new RunControl(),()->{}));
            assertThrows(SecurityException.class,()->client.send(endpoint.base(),"token",event,draft,"context",new RunControl(),()->{throw new SecurityException("grant revoked");}));
            assertEquals(0,endpoint.requests.get());
        }
    }
    @Test public void expiredSessionAndMissingPositiveReceiptNeverBecomeSuccess()throws Exception{
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("ret",-14).put("msgs",new JSONArray()))){
            assertThrows(WeixinClient.SessionExpired.class,()->new WeixinClient(endpoint.base()).poll(endpoint.base(),"token","",new RunControl(),()->{}));
        }
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("errmsg","missing receipt"))){
            JSONObject event=new JSONObject().put("senderId","u"),draft=new JSONObject().put("id","id").put("text","回答");
            assertThrows(IllegalStateException.class,()->new WeixinClient(endpoint.base()).send(endpoint.base(),"token",event,draft,"context",new RunControl(),()->{}));
        }
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("ret",0.5).put("msgs",new JSONArray()))){
            assertThrows(IllegalStateException.class,()->new WeixinClient(endpoint.base()).poll(endpoint.base(),"token","",new RunControl(),()->{}));
        }
    }
    @Test public void qrLoginPostAndStatusGetOmitBotAuthorizationAndEscapeOpaqueCode()throws Exception{
        try(Endpoint endpoint=new Endpoint(new JSONObject().put("status","wait").put("qrcode","opaque").put("qrcode_img_content","qr-content"))){
            WeixinClient client=new WeixinClient(endpoint.base());client.startLogin(new RunControl());assertEquals("POST",endpoint.method.get());assertNull(endpoint.auth.get());assertEquals("ilink_bot_token",endpoint.authType.get());
            assertEquals(0,endpoint.request.get().getJSONArray("local_token_list").length());client.pollLogin(endpoint.base(),"opaque/?+&","1234",new RunControl());
            assertEquals("GET",endpoint.method.get());assertNull(endpoint.auth.get());assertNull(endpoint.authType.get());assertTrue(endpoint.path.get().contains("qrcode=opaque%2F%3F%2B%26"));
        }
    }
    @Test public void weixinSessionHasItsOwnIdentityAndCannotBeForgedThroughRelay()throws Exception{
        File directory=Files.createTempDirectory("weixin-core-").toFile();try{
            ChannelCore core=new ChannelCore(directory);String profile=core.saveProfile(new JSONObject().put("name","微信").put("provider","weixin").put("appId","bot@im.bot").put("enabled",true)).getString("id");
            JSONObject canonical=WeixinClient.canonical(message());assertThrows(SecurityException.class,()->core.accept(profile,canonical,"trusted_relay"));
            JSONObject event=core.accept(profile,canonical,"weixin_session").getJSONObject("event");assertFalse(event.getJSONObject("route").getBoolean("privateOwner"));
            core.bindOwner(profile,event.getString("id"),true);assertTrue(core.route(event).getBoolean("privateOwner"));assertTrue(new ChannelCore(directory).accept(profile,canonical,"weixin_session").getBoolean("duplicate"));
        }finally{try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception ignored){}});}}
    }
    private static final class Endpoint implements AutoCloseable{
        final HttpServer server;final AtomicReference<String> path=new AtomicReference<>(),method=new AtomicReference<>(),auth=new AtomicReference<>(),authType=new AtomicReference<>();
        final AtomicReference<JSONObject> request=new AtomicReference<>();final AtomicInteger requests=new AtomicInteger();
        Endpoint(JSONObject response)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
            requests.incrementAndGet();path.set(exchange.getRequestURI().toString());method.set(exchange.getRequestMethod());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));authType.set(exchange.getRequestHeaders().getFirst("AuthorizationType"));
            byte[] bytes=exchange.getRequestBody().readAllBytes();request.set(bytes.length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
            byte[] result=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
        }catch(Exception failure){throw new java.io.IOException("测试微信端点处理失败",failure);}finally{exchange.close();}});server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        @Override public void close(){server.stop(0);}
    }
}
