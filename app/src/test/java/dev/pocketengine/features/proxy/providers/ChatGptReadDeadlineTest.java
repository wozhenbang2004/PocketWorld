package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local HTTP with an intentionally quiet response body; no live inference or accounts. */
public final class ChatGptReadDeadlineTest {
    private static final byte[] SSE=("data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\",\"sequence_number\":0}\n\n"
            +"data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n").getBytes(StandardCharsets.UTF_8);
    private static final class Rig implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger requests=new AtomicInteger();final ChatGptProvider provider;
        Rig()throws Exception {
            server.createContext("/",exchange->{try{
                requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,SSE.length);entered.countDown();
                release.await(40,TimeUnit.SECONDS);exchange.getResponseBody().write(SSE);
            }catch(Exception ignored){}finally{exchange.close();}});server.start();
            String base="http://127.0.0.1:"+server.getAddress().getPort();provider=new ChatGptProvider(base,base+"/v1",true);
        }
        void read(GatewayCall call,JSONArray events)throws Exception {
            Credentials credentials=new Credentials("fixture-access","fixture-refresh","fixture-id",ChatGptProvider.SCOPES,System.currentTimeMillis()+3600000);
            provider.stream(credentials,"fixture-model","",ChatGptProviderTest.input(),call,events::put);
        }
        public void close(){release.countDown();provider.close();server.stop(0);}
    }
    @Test public void quietReasoningWithinTheOriginalDeadlineIsNotCutOffAtThirtySeconds()throws Exception {
        try(Rig rig=new Rig()){
            var worker=Executors.newSingleThreadExecutor();JSONArray events=new JSONArray();
            try{var pending=worker.submit(()->{rig.read(new GatewayCall(45000),events);return true;});
                assertTrue(rig.entered.await(2,TimeUnit.SECONDS));Thread.sleep(31000);rig.release.countDown();
                assertTrue(pending.get(5,TimeUnit.SECONDS));assertEquals("completed",events.getJSONObject(events.length()-1).getString("type"));assertEquals(1,rig.requests.get());
            }finally{rig.release.countDown();worker.shutdownNow();}
        }
    }
    @Test public void originalWholeCallDeadlineStillClosesAQuietResponse()throws Exception {
        try(Rig rig=new Rig()){
            long started=System.nanoTime();GatewayFailure failure=assertThrows(GatewayFailure.class,()->rig.read(new GatewayCall(150),new JSONArray()));
            assertEquals("DEADLINE_EXCEEDED",failure.code);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000);assertEquals(1,rig.requests.get());
        }
    }
    @Test public void cancellationStillStopsAQuietResponseWithoutReplay()throws Exception {
        try(Rig rig=new Rig()){
            var worker=Executors.newSingleThreadExecutor();GatewayCall call=new GatewayCall(45000);
            try{var pending=worker.submit(()->{try{rig.read(call,new JSONArray());return "unexpected";}catch(GatewayFailure failure){return failure.code;}});
                assertTrue(rig.entered.await(2,TimeUnit.SECONDS));call.cancel();assertEquals("CANCELLED",pending.get(2,TimeUnit.SECONDS));assertEquals(1,rig.requests.get());
            }finally{rig.release.countDown();worker.shutdownNow();}
        }
    }
}
