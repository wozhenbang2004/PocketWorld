package dev.pocketengine.features.proxy.providers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Extends only the synthetic fixture's Responses endpoint; never a production endpoint seam. */
public final class RelayDelayedFixture {
    private RelayDelayedFixture(){}
    public static void install(FakeChatGpt fake,String head,String tail,CountDownLatch sent,CountDownLatch release){
        fake.server.createContext("/v1/responses",call->{
            try{
                fake.responses.incrementAndGet();fake.lastAuthorization=call.getRequestHeaders().getFirst("Authorization");
                fake.lastRequest=new org.json.JSONObject(new String(call.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));fake.responseEntered.countDown();
                byte[] first=head.getBytes(StandardCharsets.UTF_8),last=tail.getBytes(StandardCharsets.UTF_8);
                call.getResponseHeaders().add("Content-Type","text/event-stream");call.sendResponseHeaders(200,first.length+last.length);
                // Deliberately split UTF-8 and SSE fields into transport writes.
                for(byte value:first){call.getResponseBody().write(value);call.getResponseBody().flush();}sent.countDown();
                release.await(12,TimeUnit.SECONDS);call.getResponseBody().write(last);call.getResponseBody().flush();
            }finally{call.close();}
        });
    }
}
