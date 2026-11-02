package dev.pocketengine.features.channels;

import static org.junit.Assert.*;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** Static official wire contract via loopback only; the fixture ACK deliberately does not validate content. */
public class QqGroupMediaContentContractTest {
    @Test public void mediaPayloadIncludesRequiredContentAndOriginalReplyDedupIdentity()throws Exception {
        AtomicInteger messages=new AtomicInteger();AtomicReference<JSONObject> sent=new AtomicReference<>();AtomicReference<String> authorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),response;
            if(exchange.getRequestURI().getPath().equals("/app/getAppAccessToken"))response=new JSONObject().put("access_token","fixture-token").put("expires_in",7200);
            else {messages.incrementAndGet();sent.set(body);authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("id","fixture-ack-even-if-content-missing");}
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failed){throw new java.io.IOException("QQ contract fixture failed",failed);}finally{exchange.close();}});server.start();
        try {
            ChannelHttp http=new ChannelHttp("http://127.0.0.1:"+server.getAddress().getPort());
            JSONObject profile=new JSONObject().put("id","fixture-bot").put("appId","fixture-app"),event=new JSONObject().put("kind","group").put("roomId","fixture-group").put("messageId","original-message"),outbox=new JSONObject().put("sequence",3);
            JSONObject ack=http.sendGroupMedia(profile,"fixture-secret",event,outbox,"original-upload-handle",new RunControl(),()->{});
            assertEquals("fixture-ack-even-if-content-missing",ack.getString("id"));assertEquals(1,messages.get());assertEquals("QQBot fixture-token",authorization.get());
            JSONObject actual=sent.get();assertEquals(" ",actual.getString("content"));assertEquals(7,actual.getInt("msg_type"));assertEquals("original-message",actual.getString("msg_id"));assertEquals(3,actual.getInt("msg_seq"));assertEquals("original-upload-handle",actual.getJSONObject("media").getString("file_info"));
        }finally{server.stop(0);}
    }
}
