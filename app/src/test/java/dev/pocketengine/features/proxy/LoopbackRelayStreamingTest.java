package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real TCP/HTTP fixtures only. These are not Android or real-provider acceptance tests. */
public class LoopbackRelayStreamingTest {
    private static final String TOKEN="unit-test-local-token-123456789";
    private static final String KEY="upstream-key";
    private static final String BODY="{\"model\":\"configured-model\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"stream\":true}";
    private static String chunk(String text)throws Exception{return "{\"object\":\"chat.completion.chunk\",\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"+JSONObject.quote(text)+"},\"finish_reason\":null}]}";}
    private static String event(String text)throws Exception{return "data: "+chunk(text)+"\n\n";}
    private static String finish(){return "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";}
    private static ModelConfig config(String base){return new ModelConfig(base,KEY,"configured-model",false,20,10);}

    @Test public void firstEventArrivesBeforeUpstreamTailWithChunkedUtf8Transport()throws Exception {
        CountDownLatch tail=new CountDownLatch(1);
        AtomicReference<String> request=new AtomicReference<>();
        try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
            request.set(observed);head(peer,200,"text/event-stream",true,"X-Private: "+KEY+"\r\n");
            // One-byte HTTP chunks split JSON and the bytes of the emoji/Chinese characters.
            byte[] first=event("你好🙂 first").getBytes(StandardCharsets.UTF_8);
            for(byte value:first)httpChunk(peer,new byte[]{value});
            assertTrue("client should receive first event before tail is released",tail.await(3,TimeUnit.SECONDS));
            httpChunk(peer,(event(" second")+finish()+"data: {\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"total_tokens\":3}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            peer.getOutputStream().write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));peer.getOutputStream().flush();
        });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()));Socket client=send(proxy.port(),BODY)){
            BufferedReader reader=reader(client);String headers=headers(reader);assertTrue(headers.startsWith("HTTP/1.1 200"));assertTrue(headers.contains("text/event-stream"));assertFalse(headers.contains(KEY));
            String first=data(reader);assertEquals("你好🙂 first",new JSONObject(first).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("content"));
            tail.countDown();assertTrue(data(reader).contains("second"));assertTrue(data(reader).contains("finish_reason"));assertTrue(data(reader).contains("usage"));assertEquals("[DONE]",data(reader));assertEquals("",reader.readLine());assertNull(reader.readLine());
            assertTrue(request.get().contains("Authorization: Bearer "+KEY));assertTrue(request.get().contains("Accept: text/event-stream"));
            assertFalse(request.get().contains(TOKEN));assertTrue(request.get().contains("\"model\":\"configured-model\""));assertTrue(request.get().contains("\"stream\":true"));assertTrue(request.get().startsWith("POST /v1/chat/completions HTTP/1.1"));
            upstream.healthy();
        }finally{tail.countDown();}
    }
    @Test public void consentIsCheckedBeforeConnectionAndExactTargetConsentPermitsIt()throws Exception {
        try(Fixture upstream=new Fixture("127.0.0.2",(peer,observed)->{
            head(peer,200,"text/event-stream",false,"");peer.getOutputStream().write((event("allowed")+finish()+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        })){
            AtomicReference<ModelConfig> current=new AtomicReference<>(config(upstream.base()));
            try(LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,current::get)){
                try(Socket client=send(proxy.port(),BODY)){String denied=readAll(client);assertTrue(denied.startsWith("HTTP/1.1 503"));assertFalse(denied.contains(KEY));}
                assertFalse(upstream.accepted.await(150,TimeUnit.MILLISECONDS));assertEquals(0,proxy.requests());
                current.set(current.get().withCleartextConsent(ModelConfig.cleartextConsentFor(ModelConfig.DEFAULT_PROVIDER,"http://127.0.0.2:1")));
                try(Socket client=send(proxy.port(),BODY)){assertTrue(readAll(client).startsWith("HTTP/1.1 503"));}assertEquals(0,proxy.requests());
                current.set(current.get().withCleartextConsent(ModelConfig.cleartextConsentFor(ModelConfig.DEFAULT_PROVIDER,upstream.base())));
                // Changing consent cannot resurrect a route invalidated by the original native gate.
                try(Socket client=send(proxy.port(),BODY)){assertTrue(readAll(client).startsWith("HTTP/1.1 503"));}
                assertEquals(0,proxy.requests());assertFalse(upstream.accepted.await(150,TimeUnit.MILLISECONDS));proxy.close();
                try(LoopbackRelay restarted=new LoopbackRelay(0,TOKEN,current::get)){
                    try(Socket client=send(restarted.port(),BODY)){String allowed=readAll(client);assertTrue(allowed.startsWith("HTTP/1.1 200"));assertTrue(allowed.contains("allowed"));}
                    assertEquals(1,restarted.requests());upstream.healthy();
                }
            }
        }
    }
    @Test public void downstreamCancellationClosesStalledUpstreamPromptly()throws Exception {
        CountDownLatch closed=new CountDownLatch(1);
        try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
            head(peer,200,"text/event-stream",true,"");httpChunk(peer,event("first").getBytes(StandardCharsets.UTF_8));
            assertEquals(-1,peer.getInputStream().read());closed.countDown();
        });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()))){
            Socket client=send(proxy.port(),BODY);BufferedReader reader=reader(client);headers(reader);assertTrue(data(reader).contains("first"));client.close();
            assertTrue("disconnect should cancel a stalled upstream without waiting 10s",closed.await(2,TimeUnit.SECONDS));upstream.healthy();
        }
    }
    @Test public void stopClosesActiveStreamAndListenerPromptly()throws Exception {
        CountDownLatch closed=new CountDownLatch(1);
        try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
            head(peer,200,"text/event-stream",true,"");httpChunk(peer,event("first").getBytes(StandardCharsets.UTF_8));
            assertEquals(-1,peer.getInputStream().read());closed.countDown();
        });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()));Socket client=send(proxy.port(),BODY)){
            BufferedReader reader=reader(client);headers(reader);data(reader);assertEquals("",reader.readLine());long start=System.nanoTime();proxy.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);assertFalse(proxy.running());assertNull(reader.readLine());assertTrue(closed.await(2,TimeUnit.SECONDS));upstream.healthy();
        }
    }
    @Test public void cancelBeforeUpstreamHeadersClosesPendingRequest()throws Exception {
        CountDownLatch received=new CountDownLatch(1),closed=new CountDownLatch(1);
        try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{received.countDown();assertEquals(-1,peer.getInputStream().read());closed.countDown();});
            LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()))){
            Socket client=send(proxy.port(),BODY);assertTrue(received.await(2,TimeUnit.SECONDS));client.close();assertTrue(closed.await(2,TimeUnit.SECONDS));upstream.healthy();
        }
    }
    @Test public void upstreamStatusRedirectAndWrongContentTypeNeverExposeHeadersOrBodies()throws Exception {
        for(int status:new int[]{302,401,429,500,200}){
            try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
                head(peer,status,"application/json",false,"Location: http://127.0.0.1:1/"+KEY+"\r\nSet-Cookie: "+KEY+"\r\n");peer.getOutputStream().write(("{\"error\":\""+KEY+"\"}").getBytes(StandardCharsets.UTF_8));
            });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()));Socket client=send(proxy.port(),BODY)){
                String response=readAll(client);int expected=status==302||status==200?502:status;assertTrue(response.startsWith("HTTP/1.1 "+expected));assertFalse(response.contains(KEY));assertFalse(response.contains("Location:"));assertFalse(response.substring(0,response.indexOf("\r\n\r\n")).contains("text/event-stream"));upstream.healthy();
            }
        }
    }
    @Test public void malformedOrTruncatedStreamHasOneHttpResponseErrorAndNoDone()throws Exception {
        for(String suffix:new String[]{"data: not-json\n\n","data: {\"error\":{\"message\":\""+KEY+"\"}}\n\n","data: "+"x".repeat(65536)+"\n\n",""}){
            try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
                head(peer,200,"text/event-stream",false,"");peer.getOutputStream().write((event("first")+suffix).getBytes(StandardCharsets.UTF_8));
            });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()));Socket client=send(proxy.port(),BODY)){
                String response=readAll(client);assertTrue(response.startsWith("HTTP/1.1 200"));assertEquals(1,response.split("HTTP/1.1",-1).length-1);assertTrue(response.contains("pocketengine_proxy_error"));assertFalse(response.contains("[DONE]"));assertFalse(response.contains(KEY));upstream.healthy();
            }
        }
    }
    @Test public void retryAfterZeroDoesNotReplayTheBillablePost()throws Exception {
        try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
            head(peer,503,"application/json",false,"Retry-After: 0\r\n");peer.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()));Socket client=send(proxy.port(),BODY)){
            assertTrue(readAll(client).startsWith("HTTP/1.1 503"));upstream.healthy();
            upstream.server.setSoTimeout(300);
            try(Socket duplicate=upstream.server.accept()){fail("billable POST was retried");}catch(java.net.SocketTimeoutException expected){}
            assertEquals(1,proxy.requests());
        }
    }
    @Test public void absoluteDeadlineStopsAnActiveStreamWithoutFakeDone()throws Exception {
        CountDownLatch ended=new CountDownLatch(1);
        try(Fixture upstream=new Fixture("127.0.0.1",(peer,observed)->{
            head(peer,200,"text/event-stream",true,"");
            try{for(int i=0;i<80;i++){httpChunk(peer,event("tick").getBytes(StandardCharsets.UTF_8));Thread.sleep(250);}}
            catch(java.io.IOException cancelled){ended.countDown();}
        });LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config(upstream.base()));Socket client=send(proxy.port(),BODY)){
            client.setSoTimeout(14000);long start=System.nanoTime();String response=readAll(client);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertTrue("deadline elapsed="+elapsed,elapsed>=9000&&elapsed<12500);assertTrue(response.contains("tick"));assertFalse(response.contains("[DONE]"));
            assertTrue(ended.await(2,TimeUnit.SECONDS));upstream.healthy();
        }
    }
    @Test public void unsupportedProtocolPairDoesNotSilentlyUseChatAgainstGemini()throws Exception {
        AtomicReference<ModelConfig> current=new AtomicReference<>(new ModelConfig("http://127.0.0.1:1",KEY,"model",false,20,10,"gemini",4096));
        try(LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,current::get);Socket client=send(proxy.port(),BODY)){
            assertTrue(readAll(client).startsWith("HTTP/1.1 400"));assertEquals(0,proxy.requests());
        }
    }
    @Test public void modelsRemainLocalAndDoNotRequireRemoteTransportConsent()throws Exception {
        try(LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config("http://192.0.2.1"));Socket client=new Socket("127.0.0.1",proxy.port())){
            client.setSoTimeout(2000);client.getOutputStream().write(("GET /v1/models HTTP/1.1\r\nAuthorization: Bearer "+TOKEN+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response=readAll(client);assertTrue(response.startsWith("HTTP/1.1 200"));assertTrue(response.contains("configured-model"));assertFalse(response.contains(KEY));assertEquals(0,proxy.requests());
        }
    }
    private interface Script {void run(Socket peer,String request)throws Exception;}
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread thread;final CountDownLatch accepted=new CountDownLatch(1),done=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicReference<Socket> peer=new AtomicReference<>();final String host;
        Fixture(String host,Script script)throws Exception{
            this.host=host;server.bind(new InetSocketAddress(host,0));thread=new Thread(()->{
                try(Socket socket=server.accept()){peer.set(socket);accepted.countDown();socket.setSoTimeout(3000);script.run(socket,request(socket));}
                catch(Throwable error){if(!server.isClosed())failure.set(error);}finally{done.countDown();}
            },"proxy-local-upstream-fixture");thread.setDaemon(true);thread.start();
        }
        String base(){return "http://"+host+":"+server.getLocalPort()+"/v1";}
        void healthy()throws Exception{assertTrue("fixture must finish",done.await(4,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError("fixture failed",failure.get());}
        public void close()throws Exception{server.close();Socket socket=peer.get();if(socket!=null)socket.close();thread.join(1000);}
    }
    private static Socket send(int port,String body)throws Exception{
        Socket client=new Socket("127.0.0.1",port);client.setSoTimeout(3000);byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        String headers="POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+TOKEN+"\r\nContent-Type: application/json\r\nContent-Length: "+payload.length+"\r\n\r\n";
        client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(payload);client.getOutputStream().flush();return client;
    }
    private static BufferedReader reader(Socket socket)throws Exception{return new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));}
    private static String headers(BufferedReader reader)throws Exception{StringBuilder out=new StringBuilder();String line;while((line=reader.readLine())!=null&&!line.isEmpty())out.append(line).append('\n');return out.toString();}
    private static String data(BufferedReader reader)throws Exception{String line;while((line=reader.readLine())!=null){if(line.startsWith("data: "))return line.substring(6);}throw new AssertionError("missing SSE data");}
    private static String readAll(Socket socket)throws Exception{return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
    private static String request(Socket socket)throws Exception{
        InputStream in=socket.getInputStream();StringBuilder headers=new StringBuilder();int length=0;
        while(true){String line=line(in);headers.append(line).append('\n');if(line.isEmpty())break;if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());}
        byte[] body=in.readNBytes(length);assertEquals(length,body.length);return headers+new String(body,StandardCharsets.UTF_8);
    }
    private static String line(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();int last=-1;while(true){int next=in.read();if(next<0)throw new java.io.EOFException();if(last=='\r'&&next=='\n'){byte[] bytes=out.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}out.write(next);last=next;}}
    private static void head(Socket peer,int status,String type,boolean chunked,String extra)throws Exception{
        String headers="HTTP/1.1 "+status+" Reply\r\nContent-Type: "+type+"\r\nConnection: close\r\n"+(chunked?"Transfer-Encoding: chunked\r\n":"")+extra+"\r\n";
        peer.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));peer.getOutputStream().flush();
    }
    private static void httpChunk(Socket peer,byte[] bytes)throws Exception{OutputStream out=peer.getOutputStream();out.write((Integer.toHexString(bytes.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();}
}
