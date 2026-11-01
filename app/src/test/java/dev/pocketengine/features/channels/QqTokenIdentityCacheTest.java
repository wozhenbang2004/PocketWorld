package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Keeping cancellation independent of the token lock must not allow a reused id to mix bots. */
public final class QqTokenIdentityCacheTest {
    @Test public void reusedProfileIdStillRequiresMatchingAppIdAndCredentialForCachedToken()throws Exception {
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger tokens=new AtomicInteger();
        http.createContext("/",exchange->{try{
            byte[] request=exchange.getRequestBody().readAllBytes();JSONObject response;
            if(exchange.getRequestURI().getPath().equals("/app/getAppAccessToken")){JSONObject body=new JSONObject(new String(request,StandardCharsets.UTF_8));tokens.incrementAndGet();response=new JSONObject().put("access_token","token-"+body.getString("appId")).put("expires_in",7200);}
            else response=new JSONObject().put("url","wss://api.bot.qq.com/websocket/");
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failed){throw new java.io.IOException(failed);}finally{exchange.close();}});http.start();
        try{
            ChannelHttp delivery=new ChannelHttp("http://127.0.0.1:"+http.getAddress().getPort());JSONObject a=new JSONObject().put("id","reused-id").put("appId","app-A"),b=new JSONObject().put("id","reused-id").put("appId","app-B");
            assertEquals("token-app-A",delivery.qqGateway(a,"fixture-secret",new RunControl(),()->{}).token);assertEquals("token-app-A",delivery.qqGateway(a,"fixture-secret",new RunControl(),()->{}).token);assertEquals(1,tokens.get());
            assertEquals("token-app-B",delivery.qqGateway(b,"fixture-secret",new RunControl(),()->{}).token);assertEquals(2,tokens.get());delivery.qqGateway(b,"changed-fixture-secret",new RunControl(),()->{});assertEquals(3,tokens.get());
        }finally{http.stop(0);}
    }
}
