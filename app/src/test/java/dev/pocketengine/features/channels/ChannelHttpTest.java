package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChannelHttpTest {
    @Test public void realLoopbackIngressRequiresAuthAndDoesNotDispatchBodyCommands()throws Exception {
        java.nio.file.Path folder=Files.createTempDirectory("channel-http-");ChannelCore core=new ChannelCore(folder.toFile());
        String id=ChannelSecurityTest.addProfile(core,"local","webhook"),secret="test-credential-with-at-least-32-characters";
        ChannelServer server=new ChannelServer((profile,headers,body)->{
            ChannelProtocol.authenticateRelay(secret,headers);
            return core.accept(profile,ChannelProtocol.canonicalRelay(new JSONObject(new String(body,StandardCharsets.UTF_8))),"trusted_relay");
        });
        try {
            int port=server.start(0);JSONObject body=ChannelSecurityTest.message("m1","untrusted-user","dm","").put("command","owner.bind").put("confirmed",true);
            String denied=post(port,id,"Bearer wrong",body);assertTrue(denied.startsWith("HTTP/1.1 401"));assertEquals(0,core.snapshot().getJSONArray("inbox").length());
            String allowed=post(port,id,"Bearer "+secret,body);assertTrue(allowed.startsWith("HTTP/1.1 200"));
            assertEquals(1,core.snapshot().getJSONArray("inbox").length());assertEquals(0,core.snapshot().getJSONArray("bindings").length());
            assertTrue(post(port,id,"Bearer "+secret,body).contains("\"duplicate\":true"));
            assertEquals(1,core.snapshot().getJSONArray("inbox").length());
        } finally {server.close();try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(folder)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    }
    @Test public void outboundHttpPostsActualBodyAndTreatsBusinessErrorsAsRejected()throws Exception {
        AtomicReference<JSONObject> received=new AtomicReference<>();AtomicReference<String> authorization=new AtomicReference<>();
        ChannelServer server=new ChannelServer((profile,headers,body)->{authorization.set(headers.get("authorization"));received.set(new JSONObject(new String(body,StandardCharsets.UTF_8)));return new JSONObject().put("id","receipt-1");});
        try {
            int port=server.start(0);JSONObject result=ChannelHttp.post("http://127.0.0.1:"+port+"/events/profile-a","Bearer local-test",new JSONObject().put("text","真实出站消息"));
            assertEquals("receipt-1",result.getString("id"));assertEquals("Bearer local-test",authorization.get());assertEquals("真实出站消息",received.get().getString("text"));
        } finally {server.close();}
        ChannelServer error=new ChannelServer((profile,headers,body)->new JSONObject().put("code",100007).put("message","error"));
        try {int port=error.start(0);try{ChannelHttp.post("http://127.0.0.1:"+port+"/events/profile-a","",new JSONObject().put("x",1));fail("expected business rejection");}catch(ChannelHttp.Rejected expected){}}
        finally{error.close();}
    }
    @Test public void revocationAtTheActualWriteBoundaryPreventsMessageBodyDispatch()throws Exception {
        java.nio.file.Path folder=Files.createTempDirectory("channel-revoke-");ChannelCore core=new ChannelCore(folder.toFile());
        String profile=ChannelSecurityTest.addProfile(core,"relay","webhook");
        JSONObject bind=core.accept(profile,ChannelSecurityTest.message("binding","owner-id","dm",""),"trusted_relay").getJSONObject("event");
        core.bindOwner(profile,bind.getString("id"),true);
        JSONObject event=core.accept(profile,ChannelSecurityTest.message("private","owner-id","dm",""),"trusted_relay").getJSONObject("event");
        JSONObject outbox=core.queueReplyExpected(event.getString("id"),"私人答案","private");core.prepareSend(outbox.getString("id"));
        AtomicInteger received=new AtomicInteger(),checks=new AtomicInteger();
        ChannelServer server=new ChannelServer((id,headers,body)->{received.incrementAndGet();return new JSONObject().put("id","should-not-exist");});
        try {
            int port=server.start(0);
            try {
                ChannelHttp.post("http://127.0.0.1:"+port+"/events/target","Bearer local",new JSONObject().put("text","私人答案"),new RunControl(),()->{
                    if(checks.incrementAndGet()==3)core.unbindOwner(profile);
                    core.checkSendAuthority(outbox.getString("id"));
                });fail("expected final authority rejection");
            }catch(SecurityException expected){}
            assertEquals(0,received.get());assertTrue(checks.get()>=3);
        }finally{server.close();try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(folder)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    }
    @Test public void cancellingDisconnectsAnActualBlockedReplyRequest()throws Exception {
        java.util.concurrent.CountDownLatch received=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        ChannelServer server=new ChannelServer((id,headers,body)->{received.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return new JSONObject().put("id","late-receipt");});
        java.util.concurrent.ExecutorService thread=java.util.concurrent.Executors.newSingleThreadExecutor();RunControl control=new RunControl();
        try {
            int port=server.start(0);
            java.util.concurrent.Future<JSONObject> result=thread.submit(()->ChannelHttp.post("http://127.0.0.1:"+port+"/events/target","Bearer local",new JSONObject().put("text","reply"),control,()->{}));
            assertTrue(received.await(2,java.util.concurrent.TimeUnit.SECONDS));long started=System.nanoTime();control.cancel();
            try{result.get(2,java.util.concurrent.TimeUnit.SECONDS);fail("expected cancelled request");}catch(java.util.concurrent.ExecutionException expected){}
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1800);
        }finally{release.countDown();server.close();thread.shutdownNow();}
    }
    private static String post(int port,String id,String authorization,JSONObject json)throws Exception {
        byte[] body=json.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000);String headers="POST /events/"+id+" HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: "+authorization+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
            ByteArrayOutputStream response=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;while((n=socket.getInputStream().read(buffer))!=-1)response.write(buffer,0,n);
            return new String(response.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
