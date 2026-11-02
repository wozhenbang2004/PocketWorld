package dev.pocketengine.features.remote;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** Original binary slots and immutable manifest bytes over real loopback sockets. */
public final class RemoteTransportBinaryTest {
    private static final String TOKEN="fixture-only-original-binary-token-more-than-32-chars";
    @Test public void exactUploadKeepsOriginalNativeGateAndBytes()throws Exception {
        byte[] raw="原始私人字节\u0000\n".getBytes(StandardCharsets.UTF_8);AtomicInteger gates=new AtomicInteger();java.util.concurrent.atomic.AtomicLong written=new java.util.concurrent.atomic.AtomicLong();String operation=UUID.randomUUID().toString();
        try(BinaryServer server=new BinaryServer(new JSONObject().put("ok",true).put("result",new JSONObject().put("verified",true)).toString().getBytes(StandardCharsets.UTF_8),"application/json",null)){
            assertTrue(RemoteTransport.uploadInput(server.base(),TOKEN,"/v1/jobs/original-job/inputs/0",operation,new ByteArrayInputStream(raw),raw.length,new RunControl(),gates::incrementAndGet,written::set).getBoolean("verified"));server.finish();
            assertArrayEquals(raw,server.uploaded);assertEquals(1,gates.get());assertEquals(raw.length,written.get());assertEquals(operation,server.headers.get("x-pocket-job-operation"));assertTrue(server.requestLine.startsWith("PUT "));
        }
    }
    @Test public void cancelledUploadResponseIsUnknownToCallerAndIsNotResent()throws Exception {
        RunControl original=new RunControl();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger gates=new AtomicInteger();
        try(RemoteTransportDeadlineTest.SlowServer server=new RemoteTransportDeadlineTest.SlowServer(false,false,200)){
            Thread worker=new Thread(()->{try{RemoteTransport.uploadInput(server.base(),TOKEN,"/v1/jobs/original-job/inputs/0",UUID.randomUUID().toString(),new ByteArrayInputStream(new byte[]{1,2,3}),3,original,gates::incrementAndGet,null);failure.set(new AssertionError("No remote ACK arrived"));}catch(Throwable stopped){failure.set(stopped);}},"binary-cancel-fixture");worker.setDaemon(true);worker.start();assertTrue(server.received.await(3,TimeUnit.SECONDS));original.cancel();worker.join(1800);assertFalse(worker.isAlive());assertTrue(failure.get() instanceof InterruptedException);assertEquals(1,gates.get());assertEquals(1,server.requests);assertTrue(server.requestLine.startsWith("PUT "));
        }
    }
    @Test public void rejectedNativeUploadGateSendsNoHeadersOrBytes()throws Exception {
        try(ServerSocket server=new ServerSocket(0)){
            assertThrows(SecurityException.class,()->RemoteTransport.uploadInput("http://127.0.0.1:"+server.getLocalPort(),TOKEN,"/v1/jobs/original-job/inputs/0",UUID.randomUUID().toString(),new ByteArrayInputStream(new byte[]{1}),1,new RunControl(),()->{throw new SecurityException("revoked original approval");},null));server.setSoTimeout(150);assertThrows(java.net.SocketTimeoutException.class,server::accept);
        }
    }
    @Test public void immutableProjectFileStillRequiresExactLengthHeaderAndActualHash()throws Exception {
        byte[] raw="固定项目成果\n".getBytes(StandardCharsets.UTF_8);String sha=ProjectSources.sha256(raw),path="/v1/jobs/original-job/project-contributions/"+UUID.randomUUID()+"/file?path=result.txt";
        try(BinaryServer server=new BinaryServer(raw,"application/octet-stream",sha)){
            assertArrayEquals(raw,RemoteTransport.readProjectFile(server.base(),TOKEN,path,raw.length,sha,()->{}));server.finish();assertEquals(0,server.uploaded.length);
        }
        byte[] changed=raw.clone();changed[0]^=1;
        try(BinaryServer server=new BinaryServer(changed,"application/octet-stream",sha)){
            assertThrows(SecurityException.class,()->RemoteTransport.readProjectFile(server.base(),TOKEN,path,raw.length,sha,()->{}));server.finish();
        }
    }
    private static final class BinaryServer implements AutoCloseable {
        final ServerSocket server;final Thread thread;final Map<String,String> headers=new LinkedHashMap<>();volatile byte[] uploaded;volatile String requestLine;volatile Socket active;volatile Throwable failure;
        BinaryServer(byte[] body,String contentType,String hash)throws Exception {
            server=new ServerSocket(0);thread=new Thread(()->{try(Socket socket=server.accept()){active=socket;socket.setSoTimeout(3000);InputStream in=socket.getInputStream();requestLine=line(in);String header;while(!(header=line(in)).isEmpty()){int split=header.indexOf(':');headers.put(header.substring(0,split).toLowerCase(java.util.Locale.ROOT),header.substring(split+1).trim());}int length=Integer.parseInt(headers.getOrDefault("content-length","0"));uploaded=in.readNBytes(length);if(uploaded.length!=length)throw new IOException("Short upload");socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+contentType+"\r\nContent-Length: "+body.length+"\r\n"+(hash==null?"":"X-Pocket-Content-SHA256: "+hash+"\r\n")+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable error){failure=error;}},"binary-original-fixture");thread.setDaemon(true);thread.start();
        }
        String base(){return "http://127.0.0.1:"+server.getLocalPort();}
        void finish()throws Exception {thread.join(3500);assertFalse(thread.isAlive());if(failure!=null)throw new AssertionError(failure);}
        @Override public void close()throws Exception {server.close();if(active!=null)active.close();finish();}
        private static String line(InputStream in)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1,value;while((value=in.read())!=-1){if(previous=='\r'&&value=='\n'){byte[] raw=bytes.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);}bytes.write(value);previous=value;if(bytes.size()>16384)throw new IOException("Header too long");}throw new IOException("Unexpected EOF");}
    }
}
