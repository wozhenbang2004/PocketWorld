package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoopbackRelayTest {
    private static final String TOKEN="unit-test-local-token-123456789";
    private static final String BODY="{\"model\":\"configured-model\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],\"stream\":false}";
    @Test public void authorizationIsRequiredBeforeAnyUpstreamRequest()throws Exception {
        AtomicInteger upstream=new AtomicInteger();
        try(LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->{upstream.incrementAndGet();return config("http://127.0.0.1:1");})){
            Reply denied=request(proxy.port(),null,null,BODY);assertEquals(401,denied.status);assertEquals(0,upstream.get());
            Reply crossOrigin=request(proxy.port(),TOKEN,"https://untrusted.example",BODY);assertEquals(403,crossOrigin.status);assertEquals(0,upstream.get());
        }
    }
    @Test public void forwardsOnlyConfiguredModelAndCredentialAndReturnsActualResult()throws Exception {
        AtomicReference<String> observed=new AtomicReference<>();
        try(Upstream upstream=new Upstream(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"real upstream-key result\"}}]}",observed);
            LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config("http://127.0.0.1:"+upstream.port()+"/v1"))){
            Reply result=request(proxy.port(),TOKEN,null,BODY);assertEquals(200,result.status);assertTrue(result.body.contains("real"));assertFalse(result.body.contains("upstream-key"));assertTrue(observed.get().contains("Authorization: Bearer upstream-key"));assertFalse(observed.get().contains(TOKEN));assertTrue(observed.get().contains("\"model\":\"configured-model\""));assertEquals(1,proxy.requests());
        }
    }
    @Test public void rejectsInvalidStreamFlagAndOversizedBodyBeforeForwarding()throws Exception {
        try(LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config("http://127.0.0.1:1"))){
            assertEquals(400,request(proxy.port(),TOKEN,null,BODY.replace("false","\"true\"")).status);
            try(Socket client=new Socket("127.0.0.1",proxy.port())){client.setSoTimeout(2000);client.getOutputStream().write(("POST /v1/chat/completions HTTP/1.1\r\nAuthorization: Bearer "+TOKEN+"\r\nContent-Type: application/json\r\nContent-Length: 3000000\r\n\r\n").getBytes(StandardCharsets.US_ASCII));assertTrue(read(client.getInputStream()).startsWith("HTTP/1.1 413"));}
            assertEquals(0,proxy.requests());
        }
    }
    @Test public void redirectsAreNotFollowedAndStopClosesTheListeningSocket()throws Exception {
        AtomicReference<String> observed=new AtomicReference<>();
        try(Upstream upstream=new Upstream(302,"{}",observed)){
            LoopbackRelay proxy=new LoopbackRelay(0,TOKEN,()->config("http://127.0.0.1:"+upstream.port()+"/v1"));int port=proxy.port();assertEquals(502,request(port,TOKEN,null,BODY).status);proxy.close();assertFalse(proxy.running());
            try(Socket stopped=new Socket("127.0.0.1",port)){stopped.setSoTimeout(500);stopped.getOutputStream().write(("GET /v1/models HTTP/1.1\r\nAuthorization: Bearer "+TOKEN+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));assertEquals("stopped HTTP listener must not serve a response",-1,stopped.getInputStream().read());}catch(java.io.IOException expected){}
        }
    }
    private static ModelConfig config(String base){return new ModelConfig(base,"upstream-key","configured-model",false,20,10);}
    private static Reply request(int port,String token,String origin,String body)throws Exception {if(origin!=null){try(Socket client=new Socket("127.0.0.1",port)){client.setSoTimeout(3000);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);String headers="POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nOrigin: "+origin+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\n\r\n";client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(bytes);String response=read(client.getInputStream());return new Reply(Integer.parseInt(response.split(" ")[1]),response.substring(response.indexOf("\r\n\r\n")+4));}}HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/v1/chat/completions").openConnection();c.setConnectTimeout(2000);c.setReadTimeout(3000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);if(origin!=null)c.setRequestProperty("Origin",origin);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}try{int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();return new Reply(status,in==null?"":read(in));}finally{c.disconnect();}}
    private static String read(InputStream in)throws Exception {ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){result.write(buffer,0,n);if(result.size()>100000)throw new AssertionError("too much");}return new String(result.toByteArray(),StandardCharsets.UTF_8);}
    private static String line(InputStream in)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();int last=-1,n;while((n=in.read())!=-1){if(last=='\r'&&n=='\n'){byte[] b=bytes.toByteArray();return new String(b,0,b.length-1,StandardCharsets.US_ASCII);}bytes.write(n);last=n;}throw new java.io.IOException("EOF");}
    private static final class Reply {final int status;final String body;Reply(int s,String b){status=s;body=b;}}
    private static final class Upstream implements AutoCloseable {
        final ServerSocket server;final Thread thread;
        Upstream(int status,String response,AtomicReference<String> observed)throws Exception {server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0));thread=new Thread(()->{try(Socket client=server.accept()){client.setSoTimeout(3000);InputStream in=client.getInputStream();StringBuilder headers=new StringBuilder();int length=0;while(true){String header=line(in);headers.append(header).append('\n');if(header.isEmpty())break;if(header.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());}byte[] body=new byte[length];int at=0;while(at<length){int n=in.read(body,at,length-at);if(n<0)throw new java.io.IOException();at+=n;}observed.set(headers+new String(body,StandardCharsets.UTF_8));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);String head="HTTP/1.1 "+status+" Reply\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n"+(status==302?"Location: http://127.0.0.1:1/steal\r\n":"")+"\r\n";client.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(bytes);}catch(Exception ignored){}});thread.setDaemon(true);thread.start();}
        int port(){return server.getLocalPort();}
        @Override public void close()throws Exception {server.close();thread.join(1000);}
    }
}
