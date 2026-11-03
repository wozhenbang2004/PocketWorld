package dev.pocketengine.features.plugins;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
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
            assertEquals("actual output",result.getString("value"));assertFalse(result.toString().contains(token));assertEquals("[凭据已隐藏]",result.getString("token"));assertTrue(request.get().contains("Authorization: Bearer "+token));assertTrue(request.get().contains("\"tool\":\"summarize\""));assertTrue(request.get().contains("hello"));assertTrue(request.get().contains("12345678-1234-1234-1234-123456789abc"));thread.join(1000);
        }
    }
    @Test public void realHttpResultPreservesBusinessShapeAndMasksUnrelatedCredentialValuesEvenWithoutBoundToken()throws Exception {
        JSONObject response=new JSONObject().put("apiKey","UNRELATED_HTTP_KEY")
            .put("token",new JSONArray().put("UNRELATED_HTTP_TOKEN").put(123456).put(JSONObject.NULL).put(new JSONObject().put("value","WRAPPED_HTTP_SECRET")))
            .put("authorization",new JSONObject().put("state","ambiguous business state").put("allowed",true).put("empty",new JSONArray()))
            .put("nested",new JSONArray().put(new JSONObject().put("aPi_KeY","NESTED_HTTP_KEY").put("Refresh-Token","NESTED_HTTP_REFRESH")))
            .put("private",true).put("tokenCount",17).put("usage",new JSONObject().put("input_tokens",23).put("output_tokens",7))
            .put("_nativeMcpJob",new JSONObject().put("business","remote business data"))
            .put("outputProjection",new JSONObject().put("policy","remote business policy").put("allSecretsDetected",true))
            .put("rows",new JSONArray().put("ordinary row").put(7).put(JSONObject.NULL));
        String unchanged=response.toString();
        for(String bound:new String[]{"","CURRENT_BOUND_HTTP_TOKEN"}){
            JSONObject result=roundTrip(response,bound);assertEquals(response.length(),result.length());
            assertEquals("[凭据已隐藏]",result.getString("apiKey"));assertEquals(4,result.getJSONArray("token").length());
            assertEquals("[凭据已隐藏]",result.getJSONArray("token").getString(0));assertEquals("[凭据已隐藏]",result.getJSONArray("token").getString(1));assertTrue(result.getJSONArray("token").isNull(2));
            assertEquals("[凭据已隐藏]",result.getJSONArray("token").getJSONObject(3).getString("value"));
            assertEquals("[凭据已隐藏]",result.getJSONObject("authorization").getString("state"));assertTrue(result.getJSONObject("authorization").getBoolean("allowed"));assertEquals(0,result.getJSONObject("authorization").getJSONArray("empty").length());
            assertEquals("[凭据已隐藏]",result.getJSONArray("nested").getJSONObject(0).getString("aPi_KeY"));assertEquals("[凭据已隐藏]",result.getJSONArray("nested").getJSONObject(0).getString("Refresh-Token"));
            for(String key:new String[]{"private","tokenCount","usage","_nativeMcpJob","outputProjection","rows"})assertEquals(response.get(key).toString(),result.get(key).toString());
            for(String secret:new String[]{"UNRELATED_HTTP_KEY","UNRELATED_HTTP_TOKEN","WRAPPED_HTTP_SECRET","NESTED_HTTP_KEY","NESTED_HTTP_REFRESH"})assertFalse(result.toString().contains(secret));
        }
        assertEquals(unchanged,response.toString());
    }
    @Test public void realHttpResultMasksObviousCredentialTextAndKnownNumericEcho()throws Exception {
        JSONObject response=new JSONObject().put("text","ordinary before\nAuthorization: bEaReR UNRELATED_HTTP_BEARER\nBasic dW5yZWxhdGVkOmh0dHA=\n-----BEGIN PRIVATE KEY-----\nHTTP_PRIVATE_BODY\n-----END PRIVATE KEY-----\nordinary after")
            .put("truncated","-----BEGIN RSA PRIVATE KEY-----\nTRUNCATED_HTTP_PRIVATE_BODY")
            .put("numberEcho",713531137L).put("literalEcho","bound 713531137").put("count",17);
        JSONObject result=roundTrip(response,"713531137");String text=result.toString();
        for(String secret:new String[]{"UNRELATED_HTTP_BEARER","dW5yZWxhdGVkOmh0dHA=","HTTP_PRIVATE_BODY","TRUNCATED_HTTP_PRIVATE_BODY","713531137"})assertFalse(text.contains(secret));
        assertTrue(result.getString("text").contains("ordinary before"));assertTrue(result.getString("text").contains("ordinary after"));
        assertEquals("[凭据已隐藏]",result.getString("numberEcho"));assertEquals(17,result.getInt("count"));
        JSONObject emptyBound=roundTrip(new JSONObject().put("message","Bearer HTTP_EMPTY_BOUND_SECRET"),"");assertFalse(emptyBound.toString().contains("HTTP_EMPTY_BOUND_SECRET"));
    }
    @Test public void realHttpResultRejectsCredentialKeyCollisionWithoutReturningAnOverwrittenObject()throws Exception {
        JSONObject response=new JSONObject().put("known-http-secret","first business value").put("[凭据已隐藏]","second business value");
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->roundTrip(response,"known-http-secret"));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);assertTrue(failure.getMessage().contains("检查服务端结果"));
    }
    private static JSONObject roundTrip(JSONObject response,String token)throws Exception {
        AtomicInteger sends=new AtomicInteger();AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,2,java.net.InetAddress.getByName("127.0.0.1"))){
            Thread thread=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
                socket.setSoTimeout(3000);InputStream in=socket.getInputStream();int length=0;String header;
                while(!(header=line(in)).isEmpty())if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());
                for(int i=0;i<length;i++)if(in.read()<0)throw new java.io.IOException();sends.incrementAndGet();
                byte[] reply=response.toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(reply);
            }catch(Exception failure){if(!server.isClosed())serverFailure.set(failure);}},"actual-http-output-projection");thread.setDaemon(true);thread.start();
            try{return HttpToolClient.invoke("http://127.0.0.1:"+server.getLocalPort()+"/invoke",token,"lookup",new JSONObject().put("query","original business result"),"12345678-1234-1234-1234-123456789abc");}
            finally{server.close();thread.join(1000);assertFalse(thread.isAlive());assertNull(serverFailure.get());assertEquals("one original POST, no replay",1,sends.get());}
        }
    }
    private static String line(InputStream in)throws Exception {ByteArrayOutputStream result=new ByteArrayOutputStream();int before=-1;while(true){int value=in.read();if(value<0)throw new java.io.IOException();if(before=='\r'&&value=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}result.write(value);before=value;}}
}
