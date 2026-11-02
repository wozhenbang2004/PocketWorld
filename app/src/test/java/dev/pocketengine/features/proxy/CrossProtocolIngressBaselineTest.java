package dev.pocketengine.features.proxy;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** This same production-entry test fails against fixed 3158e35's diagonal-only relay. */
public final class CrossProtocolIngressBaselineTest {
    @Test public void messagesClientReachesResponsesProvider()throws Exception{check("openai_responses","/v1/messages","{\"model\":\"bound\",\"max_tokens\":128,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}","{\"id\":\"r\",\"status\":\"completed\",\"output\":[{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"actual result\"}]}]}");}
    @Test public void chatClientReachesMessagesProvider()throws Exception{check("anthropic","/v1/chat/completions","{\"model\":\"bound\",\"max_tokens\":128,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}","{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"actual result\"}]}");}
    private static void check(String egress,String path,String body,String response)throws Exception {
        HttpServer upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();upstream.createContext("/",exchange->{try{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] data=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);}finally{exchange.close();}});upstream.start();
        ModelConfig config=new ModelConfig("http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1","local-fixture-key","bound",false,4,10,egress,512);
        try(LoopbackRelay relay=new LoopbackRelay(0,"baseline-local-client-token-1234",()->config)){
            HttpURLConnection client=(HttpURLConnection)new URL("http://127.0.0.1:"+relay.port()+path).openConnection();client.setReadTimeout(5000);client.setRequestMethod("POST");client.setDoOutput(true);client.setRequestProperty("Content-Type","application/json");client.setRequestProperty("Authorization","Bearer baseline-local-client-token-1234");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);client.setFixedLengthStreamingMode(bytes.length);
            try{try(var out=client.getOutputStream()){out.write(bytes);}assertEquals("same input must traverse the original relay",200,client.getResponseCode());assertTrue(new String(client.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("actual result"));assertEquals(1,calls.get());}finally{client.disconnect();}
        }finally{upstream.stop(0);}
    }
}
