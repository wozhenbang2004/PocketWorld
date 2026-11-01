package dev.pocketengine.features.plugins;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpToolClientTest {
    @Test public void cancellationDisconnectsPendingToolAndRejectsLateReply()throws Exception {
        dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();
        java.util.concurrent.CountDownLatch accepted=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            Thread thread=new Thread(()->{try(Socket socket=server.accept()){
                socket.setSoTimeout(2000);InputStream in=socket.getInputStream();int length=0;
                while(true){String header=line(in);if(header.isEmpty())break;if(header.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());}
                for(int i=0;i<length;i++)if(in.read()<0)throw new java.io.IOException();accepted.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);
                byte[] reply="{\"result\":\"late success\"}".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(reply);
            }catch(Exception ignored){}});thread.setDaemon(true);thread.start();
            java.util.concurrent.Future<JSONObject> response=worker.submit(()->HttpToolClient.invoke("http://127.0.0.1:"+server.getLocalPort()+"/invoke","","write",new JSONObject(),java.util.UUID.randomUUID().toString(),control));
            assertTrue(accepted.await(1,java.util.concurrent.TimeUnit.SECONDS));control.cancel();release.countDown();
            try{response.get(2,java.util.concurrent.TimeUnit.SECONDS);fail("A cancelled tool must not publish a late response");}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
            thread.join(1000);
        }finally{release.countDown();worker.shutdownNow();}
    }
    @Test public void realHttpToolCarriesArgumentsAndCredentialButRedactsReturnedSecrets()throws Exception {
        String token="tool-secret-credential";AtomicReference<String> request=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            Thread thread=new Thread(()->{try(Socket socket=server.accept()){
                socket.setSoTimeout(3000);InputStream in=socket.getInputStream();StringBuilder headers=new StringBuilder();int length=0;
                while(true){String line=line(in);headers.append(line).append('\n');if(line.isEmpty())break;if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());}
                byte[] bytes=new byte[length];int at=0;while(at<length){int n=in.read(bytes,at,length-at);if(n<0)throw new java.io.IOException();at+=n;}
                request.set(headers+new String(bytes,StandardCharsets.UTF_8));byte[] reply=("{\"ok\":true,\"token\":\""+token+"\",\"nested\":{\"text\":\"Bearer "+token+"\"},\"value\":\"actual output\"}").getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(reply);
            }catch(Exception ignored){}});thread.setDaemon(true);thread.start();
            JSONObject result=HttpToolClient.invoke("http://127.0.0.1:"+server.getLocalPort()+"/invoke",token,"summarize",new JSONObject().put("text","hello"),"12345678-1234-1234-1234-123456789abc");
            assertEquals("actual output",result.getString("value"));assertFalse(result.toString().contains(token));assertFalse(result.has("token"));assertTrue(request.get().contains("Authorization: Bearer "+token));assertTrue(request.get().contains("\"tool\":\"summarize\""));assertTrue(request.get().contains("hello"));assertTrue(request.get().contains("12345678-1234-1234-1234-123456789abc"));thread.join(1000);
        }
    }
    private static String line(InputStream in)throws Exception {ByteArrayOutputStream result=new ByteArrayOutputStream();int before=-1;while(true){int value=in.read();if(value<0)throw new java.io.IOException();if(before=='\r'&&value=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}result.write(value);before=value;}}
}
