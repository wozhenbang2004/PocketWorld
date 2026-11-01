package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real A/B TCP servers. Fixture credentials replace AndroidKeyStore, never the HTTP transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteSteeringBoundaryTest {
    private static final String TOKEN_A="fixture-origin-a-private-token-at-least-32-characters",TOKEN_B="fixture-origin-b-private-token-at-least-32-characters";
    private JSONObject message()throws Exception{return new JSONObject().put("profileId","computer").put("jobId","job-1").put("messageId","owner-message").put("expectedTurnId","turn-1").put("text","Private instructions intended for A");}
    private RemoteModule module(Server a,RemoteModule.DispatchGate gate)throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.deleteFile("remote-profiles.json");context.deleteFile("remote-operations.json");FeatureContext feature=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});
        RemoteModule module=new RemoteModule(feature,(id,base)->base.equals(a.base())?TOKEN_A:TOKEN_B,gate);
        module.handle("saveProfile",new JSONObject().put("id","computer").put("name","A").put("baseUrl",a.base()));return module;
    }
    @Test public void profileSwitchAfterIntentCannotSendOldInstructionsOrCredentialsToB()throws Exception {
        try(Server a=new Server();Server b=new Server()){
            CountDownLatch intentSaved=new CountDownLatch(1),dispatch=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
            RemoteModule module=module(a,path->{intentSaved.countDown();if(!dispatch.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture dispatch gate timed out");});
            try{
                Future<?> sending=pool.submit(()->{try{return module.handle("steerJob",message());}catch(Exception expected){return expected;}});
                assertTrue(intentSaved.await(3,TimeUnit.SECONDS));
                module.handle("saveProfile",new JSONObject().put("id","computer").put("name","B").put("baseUrl",b.base()));dispatch.countDown();
                Object outcome=sending.get(4,TimeUnit.SECONDS);assertTrue(outcome instanceof IllegalStateException);
                assertEquals("Old instructions must never reach B",0,b.requests.get());assertEquals(1,a.posts.get());
                assertEquals("Bearer "+TOKEN_A,a.authorization);
                assertEquals("Private instructions intended for A",a.submitted.getString("text"));
                assertFalse(a.authorization.contains(TOKEN_B));
            }finally{dispatch.countDown();module.close();pool.shutdownNow();}
        }
    }
    @Test public void concurrentSameMessageHasOnlyOnePostEvenBeforeFirstAcknowledgement()throws Exception {
        try(Server a=new Server()){
            CountDownLatch reserved=new CountDownLatch(1),dispatch=new CountDownLatch(1);AtomicBoolean first=new AtomicBoolean(true);ExecutorService pool=Executors.newFixedThreadPool(2);
            RemoteModule module=module(a,path->{if(first.compareAndSet(true,false)){reserved.countDown();if(!dispatch.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture dispatch gate timed out");}});
            try{
                Future<JSONObject> original=pool.submit(()->module.handle("steerJob",message()));assertTrue(reserved.await(3,TimeUnit.SECONDS));
                Future<JSONObject> duplicate=pool.submit(()->module.handle("steerJob",message()));
                assertEquals("not_found",duplicate.get(3,TimeUnit.SECONDS).getString("status"));
                assertEquals(0,a.posts.get());dispatch.countDown();
                assertEquals("accepted",original.get(3,TimeUnit.SECONDS).getString("status"));
                assertEquals(1,a.posts.get());assertEquals(1,a.gets.get());
            }finally{dispatch.countDown();module.close();pool.shutdownNow();}
        }
    }
    @Test public void conflictingMessageCannotReserveAnotherOriginWhileFirstBindingIsUnregistered()throws Exception {
        try(Server a=new Server();Server b=new Server()){
            CountDownLatch aReading=new CountDownLatch(1),release=new CountDownLatch(1),bReading=new CountDownLatch(1),bStarted=new CountDownLatch(1);AtomicReference<Thread> secondThread=new AtomicReference<>();ExecutorService pool=Executors.newFixedThreadPool(2);
            Context android=RuntimeEnvironment.getApplication();android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");FeatureContext feature=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
            RemoteModule module=new RemoteModule(feature,(id,base)->{if(base.equals(a.base())){aReading.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture binding gate timed out");return TOKEN_A;}bReading.countDown();return TOKEN_B;},path->{});
            try{
                module.handle("saveProfile",new JSONObject().put("id","computer").put("name","A").put("baseUrl",a.base()));
                module.handle("saveProfile",new JSONObject().put("id","other").put("name","B").put("baseUrl",b.base()));
                Future<?> first=pool.submit(()->{try{return module.handle("steerJob",message());}catch(Exception result){return result;}});assertTrue(aReading.await(3,TimeUnit.SECONDS));
                Future<?> second=pool.submit(()->{secondThread.set(Thread.currentThread());bStarted.countDown();try{return module.handle("steerJob",message().put("profileId","other").put("text","conflicting B input"));}catch(Exception result){return result;}});
                assertTrue(bStarted.await(3,TimeUnit.SECONDS));long until=System.nanoTime()+2_000_000_000L;
                while(bReading.getCount()!=0&&!second.isDone()&&secondThread.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<until)Thread.sleep(5);
                release.countDown();first.get(4,TimeUnit.SECONDS);Object conflict=second.get(4,TimeUnit.SECONDS);
                assertEquals("Conflicting message must never POST to B",0,b.requests.get());assertTrue(conflict instanceof IllegalArgumentException);assertEquals(1,a.posts.get());
            }finally{release.countDown();module.close();pool.shutdownNow();}
        }
    }
    @Test public void messageReconciliationDoesNotHoldTheProfileMonitorAcrossHttp()throws Exception {
        try(Server a=new Server();Server b=new Server()){
            CountDownLatch querying=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
            RemoteModule module=module(a,path->{if(path.contains("/messages/")){querying.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture query gate timed out");}});
            try{
                assertEquals("accepted",module.handle("steerJob",message()).getString("status"));
                Future<?> query=pool.submit(()->{try{return module.handle("reconcileOperation",new JSONObject().put("profileId","computer").put("operationId","owner-message"));}catch(Exception result){return result;}});
                assertTrue(querying.await(3,TimeUnit.SECONDS));
                Future<JSONObject> changed=pool.submit(()->module.handle("saveProfile",new JSONObject().put("id","computer").put("name","B").put("baseUrl",b.base())));
                assertEquals(b.base(),changed.get(1,TimeUnit.SECONDS).getString("baseUrl"));
                release.countDown();query.get(4,TimeUnit.SECONDS);assertEquals(0,b.requests.get());assertEquals(1,a.gets.get());
            }finally{release.countDown();module.close();pool.shutdownNow();}
        }
    }
    @Test public void profileChangeBeforeNewMessageCannotMoveOriginalJobToNewOrigin()throws Exception {
        try(Server a=new Server();Server b=new Server()){
            RemoteModule module=module(a,path->{});
            try{
                assertEquals("accepted",module.handle("steerJob",message()).getString("status"));
                module.handle("saveProfile",new JSONObject().put("id","computer").put("name","B").put("baseUrl",b.base()));
                boolean rejected=false;
                try{module.handle("steerJob",message().put("messageId","second-message"));}catch(IllegalStateException expected){rejected=true;}
                assertEquals("Existing job must stay on its original service",0,b.requests.get());
                assertTrue(rejected);
            }finally{module.close();}
        }
    }
    @Test public void existingJobReadSettlesQueuedMessageWithoutAnotherPost()throws Exception {
        try(Server a=new Server()){
            java.util.List<JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>();
            Context android=RuntimeEnvironment.getApplication();android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");
            RemoteModule module=new RemoteModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),events::add),(id,base)->TOKEN_A,path->{});
            try{
                module.handle("saveProfile",new JSONObject().put("id","computer").put("name","A").put("baseUrl",a.base()));a.queued=true;
                assertEquals("queued",module.handle("steerJob",message()).getString("status"));
                JSONObject accepted=new JSONObject().put("jobId","job-1").put("jobOperationId","job-op").put("threadId","thread-1").put("messageId","owner-message").put("operationId","owner-message").put("expectedTurnId","turn-1").put("turnId","turn-1").put("status","accepted");
                a.jobReceipt=new JSONObject().put("id","job-1").put("operationId","job-op").put("threadId","thread-1").put("turnId","turn-1").put("status","running").put("lastSteerMessage",accepted);
                module.handle("getJob",new JSONObject().put("profileId","computer").put("jobId","job-1"));
                long acknowledgements=events.stream().map(e->e.optJSONObject("payload")).filter(e->e!=null&&e.optString("kind").equals("steer_message")&&e.optJSONObject("receipt")!=null&&e.optJSONObject("receipt").optString("status").equals("accepted")).count();
                assertEquals("Existing read-side job receipt must update message journal",1,acknowledgements);
                assertEquals(1,a.posts.get());assertEquals("settled",module.snapshot().getJSONArray("operations").getJSONObject(0).getString("status"));
            }finally{module.close();}
        }
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket listener;final Thread worker;final AtomicInteger requests=new AtomicInteger(),posts=new AtomicInteger(),gets=new AtomicInteger();
        volatile boolean closed,queued;volatile JSONObject jobReceipt;volatile String authorization="";volatile JSONObject submitted;
        Server()throws Exception{listener=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));worker=new Thread(this::serve,"steer-boundary-fixture");worker.setDaemon(true);worker.start();}
        String base(){return "http://127.0.0.1:"+listener.getLocalPort();}
        private void serve(){while(!closed)try(Socket socket=listener.accept()){
            socket.setSoTimeout(3000);InputStream in=socket.getInputStream();String request=line(in);int length=0;String header;
            while(!(header=line(in)).isEmpty()){int colon=header.indexOf(':');if(colon>0){String name=header.substring(0,colon).trim(),value=header.substring(colon+1).trim();if(name.equalsIgnoreCase("Content-Length"))length=Integer.parseInt(value);if(name.equalsIgnoreCase("Authorization"))authorization=value;}}
            byte[] bytes=new byte[length];int offset=0;while(offset<length){int n=in.read(bytes,offset,length-offset);if(n<0)throw new IllegalStateException("Incomplete fixture body");offset+=n;}
            requests.incrementAndGet();JSONObject receipt=new JSONObject().put("jobId","job-1").put("messageId","owner-message").put("operationId","owner-message");
            if(request.startsWith("POST ")){submitted=new JSONObject(new String(bytes,StandardCharsets.UTF_8));posts.incrementAndGet();receipt.put("expectedTurnId",submitted.getString("expectedTurnId")).put("turnId",submitted.getString("expectedTurnId")).put("status",queued?"queued":"accepted");}
            else{gets.incrementAndGet();if(jobReceipt!=null&&request.startsWith("GET /v1/jobs/job-1 "))receipt=new JSONObject(jobReceipt.toString());else receipt.put("status","not_found");}
            byte[] body=new JSONObject().put("ok",true).put("result",receipt).toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(Exception failure){if(!closed)throw new RuntimeException(failure);}}
        private static String line(InputStream in)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1,c;while((c=in.read())!=-1){if(previous=='\r'&&c=='\n'){byte[] raw=bytes.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);}bytes.write(c);previous=c;if(bytes.size()>8192)throw new IllegalStateException("Fixture line limit");}throw new IllegalStateException("Incomplete fixture headers");}
        @Override public void close()throws Exception{closed=true;listener.close();worker.join(1000);}
    }
}
