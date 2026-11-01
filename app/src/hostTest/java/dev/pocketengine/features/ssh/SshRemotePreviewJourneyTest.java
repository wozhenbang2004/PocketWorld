package dev.pocketengine.features.ssh;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.forward.ForwardingFilter;
import org.apache.sshd.server.forward.TcpForwardingFilter;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Actual production JSch↔MINA+HTTP/WS. Source job uses an explicit existing HTTP fixture,
 * not a real CLI, user credential, Android WebView, HMR build or deployed project. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class SshRemotePreviewJourneyTest {
    @Before public void key(){java.security.Security.addProvider(new java.security.Provider("SshPreviewFixtureKeys","1","Synthetic loopback only"){{put("KeyStore.AndroidKeyStore",SshBinaryOwnerJourneyTest.FixtureKeyStore.class.getName());}});}
    @After public void unkey(){java.security.Security.removeProvider("SshPreviewFixtureKeys");}
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static final class Fixture implements AutoCloseable {
        final Path directory=Files.createTempDirectory("c3-preview-owned-");final Context app=RuntimeEnvironment.getApplication();
        final MockWebServer dev=new MockWebServer();final SshServer server=SshServer.setUpDefaultServer();final AtomicInteger authentications=new AtomicInteger();
        final AtomicInteger remotePosts=new AtomicInteger();final Object original;final TaskStore tasks;final FeatureHost host;final String sourceTask;
        SshModule ssh;SshPreviewWorkflows workflow;final ExecutorService worker=Executors.newSingleThreadExecutor();String profileId,binding;
        Fixture()throws Exception {
            Class<?> type=Class.forName("dev.pocketengine.features.remote.RemoteOwnerWorkflowIntegrationTest$Fixture");Constructor<?> constructor=type.getDeclaredConstructor(String.class);constructor.setAccessible(true);original=constructor.newInstance("claude");tasks=(TaskStore)field(original,"tasks");host=(FeatureHost)field(original,"host");sourceTask=(String)field(original,"task");
            try{
            dev.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){if("websocket".equalsIgnoreCase(request.getHeader("Upgrade")))return new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String text){socket.send("echo:"+text);}});return new MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("<h1>Actual selected development server</h1>");}});dev.start(InetAddress.getByName("127.0.0.1"),0);
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();ByteArrayOutputStream pem=new ByteArrayOutputStream();OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair,"synthetic preview fixture",null,pem);
            SimpleGeneratorHostKeyProvider keys=new SimpleGeneratorHostKeyProvider(directory.resolve("host-key.ser"));keys.setAlgorithm("RSA");keys.setKeySize(2048);server.setKeyPairProvider(keys);server.setHost("127.0.0.1");server.setPort(0);
            server.setPasswordAuthenticator((user,password,session)->false);server.setPublickeyAuthenticator((user,key,session)->{authentications.incrementAndGet();return user.equals("synthetic-preview")&&Arrays.equals(key.getEncoded(),pair.getPublic().getEncoded());});
            server.setForwardingFilter(ForwardingFilter.asForwardingFilter(null,null,new TcpForwardingFilter(){public boolean canListen(SshdSocketAddress address,Session session){return false;}public boolean canConnect(Type type,SshdSocketAddress address,Session session){return address.getHostName().equals("127.0.0.1")&&address.getPort()==dev.getPort();}}));server.start();
            FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),host::recordEvent);ssh=new SshModule(context);host.register(ssh);ssh.setNativeExecutionAuthority(tasks::requireSshNativeRequest);
            JSONObject profile=ssh.handle("saveProfile",new JSONObject().put("name","Selected loopback project").put("host","127.0.0.1").put("port",server.getPort()).put("username","synthetic-preview").put("workspace",directory.toString()).put("authType","key").put("privateKey",pem.toString(StandardCharsets.UTF_8)));profileId=profile.getString("id");
            JSONObject probe=ssh.handle("probeHostKey",new JSONObject().put("profileId",profileId));profile=ssh.handle("approveHostKey",new JSONObject().put("profileId",profileId).put("probeId",probe.getString("probeId")).put("fingerprint",probe.getString("fingerprint")));binding=profile.getString("bindingHash");
            workflow=new SshPreviewWorkflows(ssh,tasks,worker,id->{});
            }catch(Exception|Error failed){try{close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        }
        @Override public void close()throws Exception {if(ssh!=null)ssh.close();worker.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS);host.close();((AutoCloseable)original).close();server.stop(true);dev.shutdown();var db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);synchronized(tasks){((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();}assertFalse(server.isStarted());assertTrue(ssh==null||ssh.snapshot().getJSONArray("previews").length()==0);}
    }
    @Test public void originalNativeLeaseForwardsActualHttpAndWebSocketAndBackgroundClosesBothWithoutRemoteRestart()throws Exception {
        try(Fixture f=new Fixture()){
            var page=f.workflow.newPage(f.sourceTask);page.setForeground(true);var review=page.review(f.profileId,f.binding,f.dev.getPort(),30);CountDownLatch opened=new CountDownLatch(1);AtomicReference<JSONObject> receipt=new AtomicReference<>();page.open(review,review.view().getString("digest"),true,value->{receipt.set(value);opened.countDown();});assertTrue(opened.await(6,TimeUnit.SECONDS));assertTrue(receipt.get().getBoolean("forwardingEstablished"));var mount=page.mount();mount.requireCurrent();String address=mount.baseUrl();assertTrue(address.startsWith("http://127.0.0.1:"));
            OkHttpClient client=new OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(4,TimeUnit.SECONDS).build();try(Response response=client.newCall(new Request.Builder().url(address).build()).execute()){assertEquals(200,response.code());assertTrue(response.body().string().contains("Actual selected development server"));}
            CountDownLatch echo=new CountDownLatch(1),ended=new CountDownLatch(1);AtomicReference<String> text=new AtomicReference<>();WebSocket socket=client.newWebSocket(new Request.Builder().url(address.replace("http:","ws:")).build(),new WebSocketListener(){@Override public void onOpen(WebSocket webSocket,Response response){webSocket.send("same-selected-port");}@Override public void onMessage(WebSocket webSocket,String value){text.set(value);echo.countDown();}@Override public void onClosed(WebSocket webSocket,int code,String reason){ended.countDown();}@Override public void onFailure(WebSocket webSocket,Throwable error,Response response){ended.countDown();}});
            assertTrue(echo.await(4,TimeUnit.SECONDS));assertEquals("echo:same-selected-port",text.get());assertEquals(2,f.dev.getRequestCount());page.setForeground(false);Exception revoked=assertThrows(Exception.class,mount::requireCurrent);assertTrue(revoked instanceof SecurityException||revoked instanceof InterruptedException);assertTrue(ended.await(4,TimeUnit.SECONDS));
            URI local=new URI(address);assertThrows(java.io.IOException.class,()->{try(Socket ignored=new Socket(local.getHost(),local.getPort())){}});page.close();socket.cancel();client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();
            assertEquals("job-1",f.tasks.get(f.sourceTask,false).getString("externalId"));assertFalse(f.tasks.get(f.sourceTask,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void queuedCloseConnectsNowhereAndChangedSourceCannotReuseAnOldApprovedMount()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicReference<Runnable> queued=new AtomicReference<>();SshPreviewWorkflows delayed=new SshPreviewWorkflows(f.ssh,f.tasks,queued::set,id->{});var page=delayed.newPage(f.sourceTask);page.setForeground(true);var review=page.review(f.profileId,f.binding,f.dev.getPort(),30);int before=f.authentications.get();String task=page.open(review,review.view().getString("digest"),true,result->{});assertNotNull(f.tasks.get(task,false));page.close();queued.get().run();assertEquals(before,f.authentications.get());assertEquals(0,f.dev.getRequestCount());assertEquals(0,f.ssh.snapshot().getJSONArray("previews").length());
            var second=f.workflow.newPage(f.sourceTask);second.setForeground(true);var old=second.review(f.profileId,f.binding,f.dev.getPort(),30);JSONObject source=f.tasks.remoteOwnerTarget(f.sourceTask);f.tasks.prepareRemoteContinuation(source,"new explicit source attempt, not submitted by this test");
            assertThrows(SecurityException.class,()->second.open(old,old.view().getString("digest"),true,result->{}));assertEquals(before,f.authentications.get());second.close();
            assertThrows(IllegalArgumentException.class,()->f.ssh.handle("openPreviewForward",new JSONObject()));
        }
    }
}
