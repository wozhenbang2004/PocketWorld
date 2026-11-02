package dev.pocketengine.features.devices;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ModelConfig;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real App factory and two original AgentRunner waits. TLS peer/SQL fixtures are shared with
 * DeviceIngressNativeTest; transport latency and physical phone effects are not claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class DeviceControlDispatchIsolationTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ControlDispatchKeyStore","1","Synthetic fixture"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});}
    @After public void removeKeys(){deviceKeys=null;java.security.Security.removeProvider("ControlDispatchKeyStore");}
    private static java.security.KeyStore deviceKeys;
    public static final class FixtureKeyStore extends java.security.KeyStoreSpi {
        private final dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore secrets=new dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore();
        public java.security.Key engineGetKey(String alias,char[] password){try{return deviceKeys!=null&&deviceKeys.containsAlias(alias)?deviceKeys.getKey(alias,null):secrets.engineGetKey(alias,password);}catch(Exception e){throw new IllegalStateException(e);}}
        public java.security.cert.Certificate engineGetCertificate(String alias){try{return deviceKeys==null?null:deviceKeys.getCertificate(alias);}catch(Exception e){throw new IllegalStateException(e);}}
        public java.security.cert.Certificate[] engineGetCertificateChain(String alias){java.security.cert.Certificate c=engineGetCertificate(alias);return c==null?null:new java.security.cert.Certificate[]{c};}
        public boolean engineContainsAlias(String alias){try{return secrets.engineContainsAlias(alias)||deviceKeys!=null&&deviceKeys.containsAlias(alias);}catch(Exception e){throw new IllegalStateException(e);}}
        public java.util.Date engineGetCreationDate(String alias){return new java.util.Date(0);}
        public void engineSetKeyEntry(String a,java.security.Key k,char[] p,java.security.cert.Certificate[] c){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String a,byte[] k,java.security.cert.Certificate[] c){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String a,java.security.cert.Certificate c){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String a){throw new UnsupportedOperationException();}
        public java.util.Enumeration<String> engineAliases(){return secrets.engineAliases();}
        public int engineSize(){return deviceKeys==null?1:2;}
        public boolean engineIsKeyEntry(String a){return engineContainsAlias(a);}
        public boolean engineIsCertificateEntry(String a){return false;}
        public String engineGetCertificateAlias(java.security.cert.Certificate c){return null;}
        public void engineStore(java.io.OutputStream out,char[] p){}
        public void engineLoad(java.io.InputStream in,char[] p){}
    }
    static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(owner,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
    static ExecutorService ingress(EngineApplication app)throws Exception {Object owner=field(app,"deviceWorkspaceOwner");return (ExecutorService)field(field(owner,"network"),"originalIngressWorker");}
    @Test public void originalControlAndStopDispatchWhileBothOriginalRunnersWait()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        ExecutorService runners=(ExecutorService)field(app,"capabilityWorkers");
        DeviceIngressNativeTest fixture=new DeviceIngressNativeTest();fixture.open(app.tasks);
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        ExecutorService http=Executors.newFixedThreadPool(2);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(http);
        server.createContext("/",e->{try{e.getRequestBody().readAllBytes();entered.countDown();release.await();byte[] reply="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,reply.length);e.getResponseBody().write(reply);}catch(Exception ignored){}finally{e.close();}});server.start();
        List<RunControl> controls=new ArrayList<>();List<Future<?>> attempts=new ArrayList<>();
        try {
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","fixture").putString("provider","openai_chat").putInt("maxSteps",8).commit();
            for(int i=0;i<2;i++){JSONObject task=app.tasks.createCapabilityTask("original blocking runner "+i,"");RunControl control=new RunControl();controls.add(control);ModelConfig model=app.settings.get();attempts.add(runners.submit(()->{try{return call(app,"runCapabilityAttempt",new Class<?>[]{String.class,JSONArray.class,ModelConfig.class,RunControl.class},task.getString("id"),null,model,control);}catch(Exception e){throw new RuntimeException(e);}}));}
            assertTrue("Both real original AgentRunner model calls must be blocked",entered.await(8,TimeUnit.SECONDS));
            String target=(String)field(fixture,"target");PairingCoordinator pairing=(PairingCoordinator)field(fixture,"pairing");DeviceIdentity.AuthenticatedPeer peer=(DeviceIdentity.AuthenticatedPeer)field(fixture,"caller");
            JSONObject goal=(JSONObject)call(fixture,"goalWire",new Class<?>[]{String.class,int.class},"control-isolation-goal",2);
            DeviceExecutionAdmission admission=(DeviceExecutionAdmission)call(fixture,"admission",new Class<?>[]{JSONObject.class},goal);
            String id=app.tasks.deviceTasks().accept(admission).getString("localTaskId");
            JSONObject grant=(JSONObject)field(fixture,"nativeGrant");grant.getJSONArray("capabilities").put("operation.cancel");grant.put("authorizationEpoch",2).put("historyRead",new JSONObject().put("expiresAt",System.currentTimeMillis()+60000).put("methods",new JSONArray().put("task.get")));pairing.replaceGrantLocally(grant,System.currentTimeMillis());
            // Install only synthetic key material, then use the real retained App owner factory.
            DeviceIdentity.LocalIdentity identity=(DeviceIdentity.LocalIdentity)call(fixture,"syntheticLocalIdentity",new Class<?>[]{});deviceKeys=(java.security.KeyStore)field(identity,"store");
            java.nio.file.Files.writeString(new java.io.File(app.getNoBackupFilesDir(),"device-instance-v1").toPath(),identity.instanceId());
            DeviceWorkspaceNativeOwner owner=(DeviceWorkspaceNativeOwner)field(app,"deviceWorkspaceOwner");DeviceIngress original=owner.ingress(null);
            ExecutorService controlLane=ingress(app);
            DeviceTaskEnvelope query=(DeviceTaskEnvelope)call(fixture,"historyQuery",new Class<?>[]{String.class,String.class,long.class},"task.get",id,2L);
            JSONObject read=controlLane.submit(()->original.receive(peer,DeviceProtocol.canonicalBytes(query.json()))).get(2,TimeUnit.SECONDS);
            assertEquals(id,read.getJSONObject("result").getString("taskId"));
            JSONObject cancel=(JSONObject)call(fixture,"managementWire",new Class<?>[]{JSONObject.class,String.class,long.class},goal,"original-cancel",2L);
            JSONObject result=controlLane.submit(()->original.receive(peer,DeviceProtocol.canonicalBytes(cancel))).get(2,TimeUnit.SECONDS);
            assertTrue(result.getJSONObject("result").getJSONObject("cancellation").getBoolean("requested"));assertEquals("cancelled",app.tasks.get(id,false).getString("status"));
            JSONObject stale=(JSONObject)call(fixture,"managementWire",new Class<?>[]{JSONObject.class,String.class,long.class},goal,"stale-cancel",1L);
            ExecutionException rejected=assertThrows(ExecutionException.class,()->controlLane.submit(()->original.receive(peer,DeviceProtocol.canonicalBytes(stale))).get(2,TimeUnit.SECONDS));assertTrue(rejected.getCause() instanceof SecurityException);
            // The production OriginalDeviceChildStops closure (not an injected callback) reaches
            // its dedicated worker even while unrelated runners remain blocked.
            Object source=call(fixture,"outbound",new Class<?>[]{});
            String parent=(String)field(source,"id"),run=(String)field(source,"run"),operation=(String)field(source,"operation");int epoch=(Integer)field(source,"epoch");
            RunControl cancelled=(RunControl)field(source,"control");JSONObject draft=(JSONObject)field(source,"draft");PairingCoordinator.OutgoingGrant outgoing=(PairingCoordinator.OutgoingGrant)field(source,"grant");
            var held=app.tasks.deviceTasks().reserveChildNative(parent,run,epoch,cancelled,operation,draft);
            app.tasks.deviceTasks().bindReservedWire(held,DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(draft)),outgoing).claimForWrite(peer);
            app.tasks.requestCancel(parent,run);cancelled.cancel();
            EngineApplication.OriginalDeviceChildStops stops=(EngineApplication.OriginalDeviceChildStops)field(app,"originalDeviceChildStops");stops.enqueue(parent,cancelled);
            ExecutorService stopLane=(ExecutorService)field(app,"deviceStopWorkers");stopLane.submit(()->{}).get(2,TimeUnit.SECONDS);assertTrue(((Set<?>)field(app,"deviceChildStopPending")).isEmpty());
            // No live peer is installed: the real stop path must preserve unknown and quota,
            // never reinterpret successful local scheduling as remote completion.
            assertEquals(1,app.tasks.deviceTasks().originalStopCandidates(parent,cancelled).size());assertEquals(4,app.tasks.deviceTasks().heldBudget(parent,run,true));
            assertEquals(1,release.getCount());assertEquals(2,((ThreadPoolExecutor)runners).getMaximumPoolSize());
        }finally {release.countDown();for(RunControl c:controls)c.cancel();for(Future<?> a:attempts)try{a.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}server.stop(0);http.shutdownNow();app.onTerminate();}
    }
    @Test public void boundedQueueRejectionKeepsOriginalCancelForExplicitRetryAndCloseRejects()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
        ThreadPoolExecutor stopLane=(ThreadPoolExecutor)field(app,"deviceStopWorkers");
        try {
            stopLane.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(2,TimeUnit.SECONDS));
            while(stopLane.getQueue().remainingCapacity()>0)stopLane.execute(()->{});
            JSONObject task=app.tasks.createCapabilityTask("retry original stop","");String id=task.getString("id");JSONObject run=app.tasks.startAttempt(id,"capabilities",8);RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));
            @SuppressWarnings("unchecked") Map<String,RunControl> controls=(Map<String,RunControl>)field(app,"capabilityControls");controls.put(id,control);
            Exception failure=assertThrows(IllegalStateException.class,()->call(app,"cancelCapabilityAttempt",new Class<?>[]{RunControl.class},control));assertTrue(failure.getMessage().contains("不能视为远端已停止"));assertTrue(control.isCancelled());assertTrue(app.tasks.get(id,false).getBoolean("cancelRequested"));assertSame(control,((Map<?,?>)field(app,"pendingCapabilityStops")).get(id));
            assertEquals(1,stopLane.getLargestPoolSize());assertEquals(16,stopLane.getQueue().size());release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!stopLane.getQueue().isEmpty()&&System.nanoTime()<until)Thread.sleep(5);stopLane.submit(()->{}).get(2,TimeUnit.SECONDS);
            call(app,"cancelCapabilityAttempt",new Class<?>[]{RunControl.class},control);stopLane.submit(()->{}).get(2,TimeUnit.SECONDS);assertFalse(((Map<?,?>)field(app,"pendingCapabilityStops")).containsKey(id));
            ThreadPoolExecutor ingress=(ThreadPoolExecutor)ingress(app);CountDownLatch ingressEntered=new CountDownLatch(2),ingressRelease=new CountDownLatch(1);
            try{for(int i=0;i<2;i++)ingress.execute(()->{ingressEntered.countDown();try{ingressRelease.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(ingressEntered.await(2,TimeUnit.SECONDS));while(ingress.getQueue().remainingCapacity()>0)ingress.execute(()->{});assertThrows(RejectedExecutionException.class,()->ingress.execute(()->fail("must never run on the caller")));assertEquals(2,ingress.getLargestPoolSize());assertEquals(8,ingress.getQueue().size());}finally{ingressRelease.countDown();}
            app.onTerminate();assertTrue(ingress.isShutdown());assertTrue(stopLane.isShutdown());EngineApplication.OriginalDeviceChildStops stops=(EngineApplication.OriginalDeviceChildStops)field(app,"originalDeviceChildStops");assertThrows(RejectedExecutionException.class,()->stops.enqueue(id,control));assertThrows(RejectedExecutionException.class,()->ingress.execute(()->fail("must not run inline")));assertThrows(RejectedExecutionException.class,()->stopLane.execute(()->fail("must not run inline")));
        }finally {release.countDown();if(!stopLane.isShutdown())app.onTerminate();}
    }
}
