package dev.pocketengine.features.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual loopback HTTP; no external computer, model or CLI is contacted. */
public final class RemoteTransportTest {
    private static final String TOKEN="fixture-only-companion-token-long-enough-12345";
    @Test public void connectionUsesBearerAndPreservesJsonAndEncodedArtifactPath()throws Exception {
        try(OnceServer server=new OnceServer(200,"{\"ok\":true,\"result\":{\"name\":\"我的电脑\"}}",null)){
            JSONObject result=RemoteTransport.request(server.base(),TOKEN,"/v1/jobs/job/artifact?path=dir%2Fspace+file.txt",null,2);
            server.finish();assertEquals("我的电脑",result.getString("name"));
            assertEquals("Bearer "+TOKEN,server.headers.get("authorization"));
            assertEquals("GET /v1/jobs/job/artifact?path=dir%2Fspace+file.txt HTTP/1.1",server.requestLine);
        }
        try(OnceServer server=new OnceServer(200,"{\"ok\":true,\"result\":{\"id\":\"real-job\"}}",null)){
            JSONObject body=new JSONObject().put("operationId","op-1").put("prompt","处理中文任务");
            assertEquals("real-job",RemoteTransport.request(server.base(),TOKEN,"/v1/jobs",body,2).getString("id"));
            server.finish();assertEquals("处理中文任务",new JSONObject(server.body).getString("prompt"));
            assertTrue(server.requestLine.startsWith("POST "));
        }
    }
    @Test public void redirectNeverForwardsToken()throws Exception {
        try(ServerSocket destination=new ServerSocket(0);OnceServer server=new OnceServer(302,"", "http://127.0.0.1:"+destination.getLocalPort()+"/secret")){
            destination.setSoTimeout(200);
            try{RemoteTransport.request(server.base(),TOKEN,"/v1/status",null,2);fail("Redirect accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("重定向"));}
            try(Socket unexpected=destination.accept()){fail("Credentials were forwarded");}catch(java.net.SocketTimeoutException expected){}
        }
    }
    @Test public void errorMessageCannotEchoSavedToken()throws Exception {
        try(OnceServer server=new OnceServer(401,new JSONObject().put("ok",false).put("message","rejected "+TOKEN).toString(),null)){
            try{RemoteTransport.request(server.base(),TOKEN,"/v1/status",null,2);fail("Unauthorized response accepted");}catch(IOException expected){assertFalse(expected.getMessage().contains(TOKEN));assertTrue(expected.getMessage().contains("隐藏"));}
        }
    }
    @Test public void networkAuthorityValidationPreventsSilentCredentialMigration()throws Exception {
        assertTrue(RemoteTransport.sameOrigin("https://computer.example/one","https://COMPUTER.example:443/two"));
        assertFalse(RemoteTransport.sameOrigin("https://computer.example","https://other.example"));
        assertFalse(RemoteTransport.sameOrigin("https://computer.example","http://computer.example"));
        for(String bad:new String[]{"http://192.168.1.20:8766","https://user:password@computer.example","https://computer.example/?token=x","file:///etc/passwd"}){
            try{RemoteTransport.validateBase(bad);fail("Accepted "+bad);}catch(IllegalArgumentException|java.net.URISyntaxException expected){}
        }
    }
    private static final class OnceServer implements AutoCloseable {
        final ServerSocket server;final Thread thread;volatile String requestLine,body;final Map<String,String> headers=new LinkedHashMap<>();volatile Exception error;
        OnceServer(int status,String response,String location)throws Exception {
            server=new ServerSocket(0);thread=new Thread(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(3000);java.io.InputStream input=socket.getInputStream();requestLine=line(input);String value;while(!(value=line(input)).isEmpty()){int split=value.indexOf(':');headers.put(value.substring(0,split).toLowerCase(java.util.Locale.ROOT),value.substring(split+1).trim());}
                    int length=Integer.parseInt(headers.getOrDefault("content-length","0"));byte[] bytes=new byte[length];int offset=0;while(offset<length){int read=input.read(bytes,offset,length-offset);if(read<0)throw new IOException("Short request");offset+=read;}body=new String(bytes,StandardCharsets.UTF_8);
                    byte[] payload=response.getBytes(StandardCharsets.UTF_8);String head="HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n"+(location==null?"":"Location: "+location+"\r\n")+"\r\n";
                    socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);socket.getOutputStream().flush();
                }catch(Exception failure){error=failure;}
            });thread.setDaemon(true);thread.start();
        }
        String base(){return "http://127.0.0.1:"+server.getLocalPort();}
        void finish()throws Exception{thread.join(3500);assertFalse(thread.isAlive());if(error!=null)throw error;}
        @Override public void close()throws Exception{server.close();finish();}
        private static String line(java.io.InputStream input)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int prior=-1,current;while((current=input.read())!=-1){if(prior=='\r'&&current=='\n'){byte[] value=bytes.toByteArray();return new String(value,0,value.length-1,StandardCharsets.US_ASCII);}bytes.write(current);prior=current;if(bytes.size()>16384)throw new IOException("Header too long");}throw new IOException("Unexpected EOF");}
    }
}
