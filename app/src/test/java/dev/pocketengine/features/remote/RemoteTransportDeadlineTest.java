package dev.pocketengine.features.remote;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** Actual loopback sockets. No external computer, Android provider, model or paid request. */
public final class RemoteTransportDeadlineTest {
    private static final String TOKEN="fixture-only-companion-token-long-enough-12345";
    @Test public void responseTrickleCannotRenewTheTotalRequestDeadline()throws Exception {
        try(SlowServer server=new SlowServer(false,true,200)){
            long start=System.nanoTime();
            assertThrows(IOException.class,()->RemoteTransport.request(server.base(),TOKEN,"/v1/status",null,1));
            assertTrue("A read timeout must not renew on every byte",elapsed(start)<2500);
            assertEquals(1,server.requests);assertFalse(server.requestLine.startsWith("POST"));
        }
    }
    @Test public void cancellationInterruptsBlockedGet()throws Exception {
        assertCancelled(false);
    }
    @Test public void cancellationAfterPostBodyDoesNotReplayTheOriginalOperation()throws Exception {
        assertCancelled(true);
    }
    private static void assertCancelled(boolean post)throws Exception {
        RunControl original=new RunControl();original.bindHostRun("task","run",1);AtomicReference<Throwable> result=new AtomicReference<>();
        try(SlowServer server=new SlowServer(false,false,200)){
            Thread worker=new Thread(()->{try{RemoteTransport.request(server.base(),TOKEN,"/v1/jobs",post?new JSONObject().put("operationId","original-once"):null,20,original);result.set(new AssertionError("Blocked call unexpectedly completed"));}catch(Throwable error){result.set(error);}},"remote-deadline-fixture");
            worker.setDaemon(true);worker.start();assertTrue(server.received.await(3,TimeUnit.SECONDS));long start=System.nanoTime();original.cancel();worker.join(1800);
            assertFalse("Original cancellation must interrupt the socket read",worker.isAlive());assertTrue(result.get() instanceof InterruptedException);assertTrue(elapsed(start)<1800);assertEquals(1,server.requests);
            assertEquals(post,server.requestLine.startsWith("POST"));if(post)assertEquals("original-once",new JSONObject(server.body).getString("operationId"));
        }
    }
    @Test public void retryablePostStatusCannotCreateAnotherNetworkExchange()throws Exception {
        try(SlowServer server=new SlowServer(true,false,408)){
            assertThrows(IOException.class,()->RemoteTransport.request(server.base(),TOKEN,"/v1/jobs",new JSONObject().put("operationId","same-once"),2));
            server.thread.join(2500);assertEquals(1,server.requests);
        }
    }
    @Test public void alreadyCancelledControlNeverSendsTheBody()throws Exception {
        RunControl original=new RunControl();original.cancel();
        try(ServerSocket server=new ServerSocket(0)){
            assertThrows(InterruptedException.class,()->RemoteTransport.request("http://127.0.0.1:"+server.getLocalPort(),TOKEN,"/v1/jobs",new JSONObject().put("operationId","never-sent"),2,original));
            server.setSoTimeout(150);assertThrows(java.net.SocketTimeoutException.class,server::accept);
        }
    }
    @Test public void nonPositiveTimeoutIsNeverAnInfiniteRequest()throws Exception {
        for(int seconds:new int[]{0,-1,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->RemoteTransport.request("http://127.0.0.1:1",TOKEN,"/v1/status",null,seconds));
    }
    private static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    static final class SlowServer implements AutoCloseable {
        final ServerSocket server;final Thread thread;final CountDownLatch received=new CountDownLatch(1);volatile Socket active;volatile boolean closed;volatile int requests;volatile String requestLine,body;
        SlowServer(boolean retryable,boolean trickle,int status)throws Exception {
            server=new ServerSocket(0);server.setSoTimeout(1200);thread=new Thread(()->{
                try{
                    do{
                        try(Socket socket=server.accept()){
                            active=socket;socket.setSoTimeout(3000);InputStream input=socket.getInputStream();requestLine=line(input);requests++;Map<String,String> headers=new LinkedHashMap<>();String value;
                            while(!(value=line(input)).isEmpty()){int split=value.indexOf(':');headers.put(value.substring(0,split).toLowerCase(java.util.Locale.ROOT),value.substring(split+1).trim());}
                            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new IOException("Short fixture request");body=new String(bytes,StandardCharsets.UTF_8);received.countDown();
                            if(retryable){byte[] error="{\"ok\":false,\"message\":\"timeout\"}".getBytes(StandardCharsets.UTF_8);String head="HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+error.length+"\r\nRetry-After: 0\r\nConnection: close\r\n\r\n";socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(error);socket.getOutputStream().flush();}
                            else if(trickle){byte[] response="{\"ok\":true,\"result\":{\"state\":\"actual-response\"}}".getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));for(byte b:response){if(closed)break;socket.getOutputStream().write(b);socket.getOutputStream().flush();Thread.sleep(80);}}
                            else {while(!closed&&input.read()!=-1){/* Client cancel closes this exact connection. */}}
                        }
                    }while(retryable&&!closed);
                }catch(Exception expectedDisconnect){/* Test owns this server; cancellation/timeout deliberately closes the socket. */}
            },"remote-slow-loopback-fixture");thread.setDaemon(true);thread.start();
        }
        String base(){return "http://127.0.0.1:"+server.getLocalPort();}
        @Override public void close()throws Exception {closed=true;server.close();Socket socket=active;if(socket!=null)socket.close();thread.join(3500);assertFalse("Fixture server did not stop",thread.isAlive());}
        private static String line(InputStream input)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();int prior=-1,value;while((value=input.read())!=-1){if(prior=='\r'&&value=='\n'){byte[] raw=out.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);}out.write(value);prior=value;if(out.size()>16384)throw new IOException("Fixture header too long");}throw new IOException("Fixture EOF");}
    }
}
