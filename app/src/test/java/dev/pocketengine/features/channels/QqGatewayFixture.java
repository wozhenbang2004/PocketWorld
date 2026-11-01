package dev.pocketengine.features.channels;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Local RFC6455 test peer only. Production transport is OkHttp. No provider is contacted. */
final class QqGatewayFixture implements AutoCloseable {
    interface Script {void run(Peer peer)throws Exception;}
    private final ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final CountDownLatch finished=new CountDownLatch(1);
    private final Thread thread;
    private volatile boolean closed;
    private volatile Socket active;
    QqGatewayFixture(Script... scripts)throws Exception {
        server.setSoTimeout(10_000);thread=new Thread(()->{
            try{for(Script script:scripts)try(Socket socket=server.accept()){active=socket;socket.setSoTimeout(5000);script.run(new Peer(socket));}}
            catch(Throwable problem){if(!closed)failure.compareAndSet(null,problem);}
            finally{finished.countDown();}
        },"fixture-qq-websocket");thread.setDaemon(true);thread.start();
    }
    String url(){return "ws://127.0.0.1:"+server.getLocalPort()+"/websocket";}
    void awaitFinished()throws Exception {if(!finished.await(8,TimeUnit.SECONDS))throw new AssertionError("Fixture did not finish");assertHealthy();}
    void assertHealthy(){if(failure.get()!=null)throw new AssertionError("WebSocket fixture failed",failure.get());}
    @Override public void close(){closed=true;try{server.close();}catch(Exception ignored){}try{if(active!=null)active.close();}catch(Exception ignored){}try{thread.join(1000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    static final class Peer {
        final String handshake;
        private final InputStream input;private final OutputStream output;
        Peer(Socket socket)throws Exception {
            input=socket.getInputStream();output=socket.getOutputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int state=0,value;
            while(state<4&&(value=input.read())!=-1){if(headers.size()>16_384)throw new IllegalStateException("Oversized fixture handshake");headers.write(value);state=(state==0||state==2)?value=='\r'?state+1:0:value=='\n'?state+1:0;}
            handshake=headers.toString(StandardCharsets.US_ASCII.name());String key=null;
            for(String line:handshake.split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:"))key=line.substring(line.indexOf(':')+1).trim();
            if(key==null)throw new IllegalArgumentException("Missing WebSocket key");String accepted=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accepted+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
        }
        synchronized void send(JSONObject payload)throws Exception {frame(1,payload.toString().getBytes(StandardCharsets.UTF_8));}
        synchronized void close(int code)throws Exception {frame(8,new byte[]{(byte)(code>>>8),(byte)code});}
        private void frame(int opcode,byte[] bytes)throws Exception {
            output.write(0x80|opcode);if(bytes.length<126)output.write(bytes.length);else{if(bytes.length>65_535)throw new IllegalArgumentException("fixture frame too large");output.write(126);output.write(bytes.length>>>8);output.write(bytes.length);}
            output.write(bytes);output.flush();
        }
        JSONObject read()throws Exception {
            int first=input.read(),second=input.read();if(first<0||second<0)throw new EOFException("Fixture client disconnected");int opcode=first&15,length=second&127;
            if(length==127)throw new IllegalArgumentException("Fixture client frame too large");if(length==126)length=readByte()*256+readByte();
            byte[] mask=(second&128)!=0?input.readNBytes(4):new byte[0];if(mask.length!=4)throw new IllegalArgumentException("Client must mask frames");
            byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException("Partial fixture frame");for(int i=0;i<bytes.length;i++)bytes[i]^=mask[i%4];
            if(opcode==8)return null;if(opcode!=1)throw new IllegalArgumentException("Expected text fixture frame");return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        }
        private int readByte()throws Exception{int value=input.read();if(value<0)throw new EOFException();return value;}
    }
}
