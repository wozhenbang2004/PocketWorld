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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual loopback deliveries and credentials; only the AndroidKeyStore credential source is a fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOriginBoundaryTest {
    private static final String TOKEN_A="fixture-only-origin-a-token-more-than-32-chars",TOKEN_B="fixture-only-origin-b-token-more-than-32-chars";
    private FeatureContext context(){Context android=RuntimeEnvironment.getApplication();android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");return new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});}
    private JSONObject job()throws Exception{return new JSONObject().put("profileId","computer").put("environmentId","workspace-a").put("operationId","original-operation").put("worker","command").put("argv",new JSONArray().put("git").put("status")).put("prompt","private prompt for A");}
    private JSONObject file()throws Exception{return new JSONObject().put("profileId","computer").put("environmentId","workspace-a").put("operationId","original-operation").put("path","private.txt").put("content","private file for A").put("expectedVersion","original-version");}
    private void profile(RemoteModule module,String id,Server server)throws Exception{module.handle("saveProfile",new JSONObject().put("id",id).put("name",id).put("baseUrl",server.base()));}
    private void switchAfterIntent(String command)throws Exception {
        try(Server a=new Server();Server b=new Server()){
            CountDownLatch saved=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
            RemoteModule module=new RemoteModule(context(),(id,base)->base.equals(a.base())?TOKEN_A:TOKEN_B,path->{saved.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture dispatch gate timed out");});
            try{
                profile(module,"computer",a);JSONObject input=command.equals("submitJob")?job():file();
                Future<?> sending=pool.submit(()->{try{return module.handle(command,input);}catch(Exception result){return result;}});
                assertTrue(saved.await(3,TimeUnit.SECONDS));profile(module,"computer",b);
                input.put("environmentId","workspace-b").put("content","replacement");if(command.equals("submitJob"))input.getJSONArray("argv").put(0,"replacement");
                release.countDown();sending.get(4,TimeUnit.SECONDS);
                assertEquals("Old payload must never reach B",0,b.requests.get());assertEquals(1,a.posts.get());assertEquals("Bearer "+TOKEN_A,a.authorization);
                if(command.equals("submitJob")){assertEquals("private prompt for A",a.submitted.getString("prompt"));assertEquals("git",a.submitted.getJSONArray("argv").getString(0));}
                else{assertEquals("private file for A",a.submitted.getString("content"));assertEquals("original-version",a.submitted.getString("expectedVersion"));assertTrue(a.requestLine.contains("/workspaces/workspace-a/file"));}
            }finally{release.countDown();module.close();pool.shutdownNow();}
        }
    }
    @Test public void submitKeepsOriginalOriginCredentialAndArgumentSnapshot()throws Exception{switchAfterIntent("submitJob");}
    @Test public void fileWriteKeepsOriginalOriginCredentialAndWorkspaceSnapshot()throws Exception{switchAfterIntent("writeFile");}

    @Test public void conflictingReservationCannotPassTheCheckBeforeTheFirstIntentIsSaved()throws Exception {
        try(Server a=new Server();Server b=new Server()){
            CountDownLatch aReading=new CountDownLatch(1),release=new CountDownLatch(1),bReading=new CountDownLatch(1),bStarted=new CountDownLatch(1);AtomicReference<Thread> secondThread=new AtomicReference<>();ExecutorService pool=Executors.newFixedThreadPool(2);
            RemoteModule module=new RemoteModule(context(),(id,base)->{if(base.equals(a.base())){aReading.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture reservation gate timed out");return TOKEN_A;}bReading.countDown();return TOKEN_B;},path->{});
            try{
                profile(module,"computer",a);profile(module,"other",b);
                Future<?> first=pool.submit(()->{try{return module.handle("submitJob",job());}catch(Exception result){return result;}});assertTrue(aReading.await(3,TimeUnit.SECONDS));
                Future<?> second=pool.submit(()->{secondThread.set(Thread.currentThread());bStarted.countDown();try{return module.handle("submitJob",job().put("profileId","other").put("prompt","conflicting B instructions"));}catch(Exception result){return result;}});
                assertTrue(bStarted.await(3,TimeUnit.SECONDS));long until=System.nanoTime()+2_000_000_000L;
                while(bReading.getCount()!=0&&!second.isDone()&&secondThread.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<until)Thread.sleep(5);
                release.countDown();first.get(4,TimeUnit.SECONDS);Object conflict=second.get(4,TimeUnit.SECONDS);
                assertEquals("Conflicting reservation must never POST to B",0,b.requests.get());assertTrue(conflict instanceof IllegalArgumentException);assertEquals(1,a.posts.get());
            }finally{release.countDown();module.close();pool.shutdownNow();}
        }
    }
    @Test public void oldJobControlsAndReadsRefuseAReboundProfileBeforeAnyBRequest()throws Exception {
        try(Server a=new Server();Server b=new Server()){
            RemoteModule module=new RemoteModule(context(),(id,base)->base.equals(a.base())?TOKEN_A:TOKEN_B,path->{});
            try{
                profile(module,"computer",a);module.handle("submitJob",job());profile(module,"computer",b);
                for(String command:new String[]{"getJob","cancelJob","approveJob","readArtifact"}){
                    JSONObject input=new JSONObject().put("profileId","computer").put("jobId","accepted-job").put("approvalRequestId","approval-1").put("decision","accept").put("path","private.txt");
                    try{module.handle(command,input);fail("Old job reached rebound profile: "+command);}catch(IllegalStateException expected){}
                }
                assertEquals(0,b.requests.get());assertEquals(1,a.posts.get());
            }finally{module.close();}
        }
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket listener;final Thread worker;final AtomicInteger requests=new AtomicInteger(),posts=new AtomicInteger();
        volatile boolean closed;volatile String authorization="",requestLine="";volatile JSONObject submitted;
        Server()throws Exception{listener=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));worker=new Thread(this::serve,"remote-origin-fixture");worker.setDaemon(true);worker.start();}
        String base(){return "http://127.0.0.1:"+listener.getLocalPort();}
        private void serve(){while(!closed)try(Socket socket=listener.accept()){
            socket.setSoTimeout(3000);InputStream in=socket.getInputStream();requestLine=line(in);int length=0;String header;
            while(!(header=line(in)).isEmpty()){int colon=header.indexOf(':');if(colon>0){String name=header.substring(0,colon).trim(),value=header.substring(colon+1).trim();if(name.equalsIgnoreCase("Content-Length"))length=Integer.parseInt(value);if(name.equalsIgnoreCase("Authorization"))authorization=value;}}
            byte[] bytes=new byte[length];int offset=0;while(offset<length){int n=in.read(bytes,offset,length-offset);if(n<0)throw new IllegalStateException("Incomplete fixture body");offset+=n;}
            requests.incrementAndGet();JSONObject receipt=new JSONObject().put("status","not_found");
            if(requestLine.startsWith("POST ")){submitted=new JSONObject(new String(bytes,StandardCharsets.UTF_8));posts.incrementAndGet();receipt.put("id","accepted-job").put("operationId",submitted.optString("operationId")).put("environmentId",submitted.optString("environmentId")).put("worker",submitted.optString("worker")).put("status","completed");}
            byte[] body=new JSONObject().put("ok",true).put("result",receipt).toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(Exception failure){if(!closed)throw new RuntimeException(failure);}}
        private static String line(InputStream in)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1,c;while((c=in.read())!=-1){if(previous=='\r'&&c=='\n'){byte[] raw=bytes.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);}bytes.write(c);previous=c;if(bytes.size()>8192)throw new IllegalStateException("Fixture line limit");}throw new IllegalStateException("Incomplete fixture headers");}
        @Override public void close()throws Exception{closed=true;listener.close();worker.join(1000);}
    }
}
