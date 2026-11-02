package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real authentication/ingress/book/outbox/HTTP. HostTaskPort is an explicit admission fixture only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelTaskHostTest {
    private static final String SECRET="fixture-relay-authority-at-least-32-bytes";
    // Robolectric has no AndroidKeyStore. This explicit test-only key provider lets the real
    // SecretVault encrypt/decrypt persisted fixture credentials; it proves no device keystore behavior.
    @Before public void keyFixture(){java.security.Security.addProvider(new java.security.Provider("ChannelTestKeyStore",1.0d,"Explicit channel test fixture"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});}
    @After public void removeKeyFixture(){java.security.Security.removeProvider("ChannelTestKeyStore");}
    private FeatureContext context(){Context app=RuntimeEnvironment.getApplication();return new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});}
    @Test public void authenticatedOwnerTaskUsesOneHostIdentityAndRealReceiptWithoutDmApproval()throws Exception{
        CopyOnWriteArrayList<JSONObject> delivered=new CopyOnWriteArrayList<>();AtomicInteger admissions=new AtomicInteger(),cancellations=new AtomicInteger();AtomicReference<HostTaskPort.Request> submitted=new AtomicReference<>();AtomicReference<HostTaskPort.Authority> live=new AtomicReference<>();
        ChannelServer receiver=new ChannelServer((profile,headers,bytes)->{assertEquals("Bearer "+SECRET,headers.get("authorization"));delivered.add(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));return new JSONObject().put("id","actual-loopback-receipt-"+delivered.size());});int reply=receiver.start(0);ChannelsModule module=new ChannelsModule(context());
        try{
            String profile=profile(module,reply);int ingress=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");
            module.setHostTaskPort(new HostTaskPort(){public TaskState admit(Request request,Authority authority)throws Exception{authority.check();admissions.incrementAndGet();submitted.set(request);live.set(authority);return new TaskState(request.taskId,request.taskId,1,1,"awaiting_user","approval_required：仍需在本机批准具体写入参数");}public TaskState inspect(Request request,Authority authority)throws Exception{authority.check();return null;}public TaskState cancel(Request request,Authority authority)throws Exception{authority.check();assertEquals(submitted.get().taskId,request.taskId);assertEquals(request.taskId,request.expectedRunId);assertEquals(1,request.expectedCoordinatorEpoch);cancellations.incrementAndGet();return new TaskState(request.taskId,request.taskId,1,2,"stopping","已请求宿主停止；已有动作仍需核对");}});
            assertTrue(post(ingress,profile,SECRET+"wrong",message("bad","owner","dm","/task 不应到达宿主")).startsWith("HTTP/1.1 401"));assertEquals(0,module.snapshot().getJSONArray("inbox").length());
            String binding=inbound(module,ingress,profile,message("bind","owner","dm","绑定依据"));bind(module,profile,binding);
            inbound(module,ingress,profile,message("disabled","owner","dm","/task 默认不允许"));assertEquals(0,admissions.get());assertEquals(0,module.snapshot().getJSONArray("taskRequests").length());awaitNativeFeedback(module,1);assertEquals(1,delivered.size());
            enable(module,profile);inbound(module,ingress,profile,message("group","owner","group","/task 群不能调用"));inbound(module,ingress,profile,message("stranger","stranger","dm","/task 声称主人").put("ownerTasksEnabled",true).put("principalId","owner").put("spaceId","private"));assertEquals(0,admissions.get());awaitNativeFeedback(module,2);assertEquals(2,delivered.size());java.util.Set<String> feedbackSources=new java.util.HashSet<>();for(JSONObject row:delivered){feedbackSources.add(row.getString("replyTo"));assertFalse(row.toString().contains("approval_required"));}assertEquals(java.util.Set.of("disabled","stranger"),feedbackSources);
            JSONObject command=message("task","owner","dm","/task 整理已授权工作区").put("command","approveCapability").put("approved",true);String event=inbound(module,ingress,profile,command);assertEquals(1,admissions.get());assertEquals("整理已授权工作区",submitted.get().goal);assertEquals(event,submitted.get().origin().getString("eventId"));assertEquals("owner",submitted.get().origin().getString("ownerId"));assertEquals("private",submitted.get().origin().getString("spaceId"));assertEquals(3,delivered.size());
            JSONObject outbox=null;JSONArray actualRows=module.snapshot().getJSONArray("outbox");for(int i=0;i<actualRows.length();i++)if(actualRows.getJSONObject(i).has("hostRevision"))outbox=actualRows.getJSONObject(i);assertNotNull(outbox);assertEquals("sent",outbox.getString("status"));assertEquals("actual-loopback-receipt-3",outbox.getString("receiptId"));assertEquals(submitted.get().taskId,outbox.getString("hostTaskId"));assertTrue(delivered.get(2).getString("text").contains("approval_required"));
            assertTrue(post(ingress,profile,SECRET,command).contains("\"duplicate\":true"));assertTrue(module.handle("task.submit",new JSONObject().put("eventId",event)).getBoolean("known"));inbound(module,ingress,profile,message("agreement","owner","dm","同意，批准所有操作"));assertEquals(1,admissions.get());assertEquals(3,delivered.size());assertFalse(module.snapshot().getJSONObject("capabilities").getBoolean("dmApprovals"));
            inbound(module,ingress,profile,message("group-cancel","owner","group","/task-cancel "+submitted.get().requestId));assertEquals(0,cancellations.get());assertEquals(3,delivered.size());inbound(module,ingress,profile,message("cancel","owner","dm","/task-cancel "+submitted.get().requestId));assertEquals(1,cancellations.get());assertEquals(4,delivered.size());inbound(module,ingress,profile,message("cancel-again","owner","dm","/task-cancel "+submitted.get().requestId));assertEquals(1,cancellations.get());assertEquals(4,delivered.size());
            module.handle("owner.unbind",new JSONObject().put("profileId",profile));assertThrows(SecurityException.class,()->live.get().check());assertThrows(SecurityException.class,()->module.recordHostTaskUpdate(submitted.get().requestId,new HostTaskPort.TaskState(submitted.get().taskId,submitted.get().taskId,1,3,"completed","迟到的私人结果"),true));assertEquals(4,delivered.size());
        }finally{module.close();receiver.close();}
    }
    @Test public void lostAdmissionReceiptAndRestartOnlyInspectTheSameTaskNeverReadmit()throws Exception{
        AtomicInteger admissions=new AtomicInteger(),inspections=new AtomicInteger(),cancellations=new AtomicInteger();AtomicReference<HostTaskPort.Request> actual=new AtomicReference<>();HostTaskPort fixture=new HostTaskPort(){public TaskState admit(Request request,Authority authority)throws Exception{authority.check();admissions.incrementAndGet();actual.set(request);throw new java.io.IOException("fixture response lost after host admission");}public TaskState inspect(Request request,Authority authority)throws Exception{authority.check();inspections.incrementAndGet();assertEquals(actual.get().taskId,request.taskId);return new TaskState(request.taskId,request.taskId,1,1,"awaiting_user","宿主原任务仍等待精确审批");}public TaskState cancel(Request request,Authority authority)throws Exception{authority.check();cancellations.incrementAndGet();throw new java.io.IOException("fixture lost cancellation receipt");}};
        ChannelsModule first=new ChannelsModule(context());String profile=profile(first,0),event;JSONObject command=message("uncertain","owner","dm","/task 不得重复准入");
        try{first.setHostTaskPort(fixture);int port=first.handle("server.start",new JSONObject().put("port",0)).getInt("port");bind(first,profile,inbound(first,port,profile,message("bind","owner","dm","绑定")));enable(first,profile);event=inbound(first,port,profile,command);assertEquals(1,admissions.get());assertEquals("admission_unknown",first.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("admission"));assertEquals(1,first.snapshot().getJSONArray("outbox").length());assertEquals("owner_task_unconfirmed",first.snapshot().getJSONArray("outbox").getJSONObject(0).getString("nativeControlCode"));assertTrue(first.snapshot().getJSONArray("outbox").getJSONObject(0).getString("text").contains("/task-status "+actual.get().requestId));}finally{first.close();}
        ChannelsModule restored=new ChannelsModule(context());try{restored.setHostTaskPort(fixture);int port=restored.handle("server.start",new JSONObject().put("port",0)).getInt("port");assertTrue(post(port,profile,SECRET,command).contains("\"duplicate\":true"));assertTrue(restored.handle("task.submit",new JSONObject().put("eventId",event)).getBoolean("known"));assertEquals(1,admissions.get());
            JSONObject state=restored.handle("task.inspect",new JSONObject().put("requestId",actual.get().requestId));assertTrue(state.getBoolean("found"));assertEquals(actual.get().taskId,state.getString("taskId"));assertEquals("admitted",state.getString("admission"));assertEquals(1,inspections.get());assertEquals(2,restored.snapshot().getJSONArray("outbox").length());restored.handle("task.inspect",new JSONObject().put("requestId",actual.get().requestId));assertEquals(2,restored.snapshot().getJSONArray("outbox").length());assertEquals(1,admissions.get());assertThrows(IllegalStateException.class,()->restored.handle("task.cancel",new JSONObject().put("requestId",actual.get().requestId)));assertEquals(1,cancellations.get());
        }finally{restored.close();}
        ChannelsModule again=new ChannelsModule(context());try{again.setHostTaskPort(fixture);assertTrue(again.handle("task.cancel",new JSONObject().put("requestId",actual.get().requestId)).getBoolean("known"));assertEquals("cancellation_unknown",again.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("cancellation"));assertEquals(1,cancellations.get());assertEquals(1,admissions.get());}finally{again.close();}
    }
    @Test public void earlyHostCompletionCannotBeOverwrittenByItsOlderAdmissionReceipt()throws Exception{
        ChannelsModule module=new ChannelsModule(context());AtomicReference<HostTaskPort.Request> actual=new AtomicReference<>();try{
            String profile=profile(module,0);int port=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");bind(module,profile,inbound(module,port,profile,message("bind","owner","dm","绑定")));enable(module,profile);
            module.setHostTaskPort(new HostTaskPort(){public TaskState admit(Request request,Authority authority)throws Exception{authority.check();actual.set(request);module.recordHostTaskUpdate(request.requestId,new TaskState(request.taskId,request.taskId,1,2,"needs_confirmation","宿主真实结果等待主人核对"),false);return new TaskState(request.taskId,request.taskId,1,1,"queued","较早的登记回执");}public TaskState inspect(Request request,Authority authority){return null;}public TaskState cancel(Request request,Authority authority){throw new UnsupportedOperationException("not called by this test");}});
            inbound(module,port,profile,message("task","owner","dm","/task 快速返回"));JSONArray outbox=module.snapshot().getJSONArray("outbox");assertEquals(1,outbox.length());assertEquals(2,outbox.getJSONObject(0).getLong("hostRevision"));assertTrue(outbox.getJSONObject(0).getString("text").contains("等待主人核对"));assertFalse(outbox.getJSONObject(0).getString("text").contains("较早的登记回执"));assertEquals("admitted",module.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("admission"));
            assertThrows(SecurityException.class,()->module.recordHostTaskUpdate(actual.get().requestId,new HostTaskPort.TaskState(java.util.UUID.randomUUID().toString(),"",0,3,"completed","错误任务结果"),false));assertEquals(1,module.snapshot().getJSONArray("outbox").length());
        }finally{module.close();}
    }
    @Test public void lateOldRunCancellationCannotStopOrOverwriteAResumedRun()throws Exception{
        ChannelsModule module=new ChannelsModule(context());java.util.concurrent.ExecutorService thread=java.util.concurrent.Executors.newSingleThreadExecutor();java.util.concurrent.CountDownLatch oldEntered=new java.util.concurrent.CountDownLatch(1),releaseOld=new java.util.concurrent.CountDownLatch(1);AtomicReference<HostTaskPort.Request> origin=new AtomicReference<>();AtomicReference<HostTaskPort.TaskState> current=new AtomicReference<>();AtomicInteger actualStops=new AtomicInteger();String firstRun=java.util.UUID.randomUUID().toString(),secondRun=java.util.UUID.randomUUID().toString();
        try{
            String profile=profile(module,0);int port=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");bind(module,profile,inbound(module,port,profile,message("bind","owner","dm","绑定")));enable(module,profile);String event=inbound(module,port,profile,message("task","owner","dm","/task 同任务恢复"));
            module.setHostTaskPort(new HostTaskPort(){public TaskState admit(Request request,Authority authority)throws Exception{authority.check();origin.set(request);TaskState state=new TaskState(request.taskId,firstRun,1,1,"running","原run执行");current.set(state);return state;}public TaskState inspect(Request request,Authority authority)throws Exception{authority.check();return current.get();}public TaskState cancel(Request request,Authority authority)throws Exception{
                authority.check();if(request.expectedCoordinatorEpoch==1){oldEntered.countDown();assertTrue(releaseOld.await(4,java.util.concurrent.TimeUnit.SECONDS));}
                TaskState state=current.get();if(!request.expectedRunId.equals(state.runId)||request.expectedCoordinatorEpoch!=state.coordinatorEpoch)throw new SecurityException("fixture root enforces the exact requested run");actualStops.incrementAndGet();TaskState stopped=new TaskState(request.taskId,state.runId,state.coordinatorEpoch,3,"stopping","只停止当前run");current.set(stopped);return stopped;
            }});
            module.handle("task.submit",new JSONObject().put("eventId",event));String request=origin.get().requestId;java.util.concurrent.Future<JSONObject> old=thread.submit(()->module.handle("task.cancel",new JSONObject().put("requestId",request)));assertTrue(oldEntered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            HostTaskPort.TaskState resumed=new HostTaskPort.TaskState(origin.get().taskId,secondRun,2,2,"running","主人恢复同一task的新run");current.set(resumed);module.recordHostTaskUpdate(request,resumed,false);module.handle("task.cancel",new JSONObject().put("requestId",request));assertEquals(1,actualStops.get());releaseOld.countDown();assertThrows(java.util.concurrent.ExecutionException.class,()->old.get(3,java.util.concurrent.TimeUnit.SECONDS));
            JSONObject link=module.snapshot().getJSONArray("taskRequests").getJSONObject(0);assertEquals("requested",link.getString("cancellation"));assertEquals(secondRun,link.getString("hostRunId"));assertEquals(2,link.getInt("hostCoordinatorEpoch"));assertEquals(3,link.getLong("lastUpdateRevision"));assertEquals(1,actualStops.get());
        }finally{releaseOld.countDown();thread.shutdownNow();module.close();}
    }
    private static void awaitNativeFeedback(ChannelsModule module,int expected)throws Exception {
        long until=System.nanoTime()+5_000_000_000L;while(true){int sent=0;JSONArray rows=module.snapshot().getJSONArray("outbox");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.has("nativeControlCode")&&row.optString("status").equals("sent"))sent++;}if(sent==expected)return;if(System.nanoTime()>until)fail("fixed native feedback did not settle before fixture deadline");Thread.sleep(10);}
    }
    private String profile(ChannelsModule module,int reply)throws Exception{return module.handle("profile.save",new JSONObject().put("name","真实HTTP渠道夹具").put("provider","webhook").put("secret",SECRET).put("enabled",true).put("autoReply",false).put("replyUrl",reply==0?"":"http://127.0.0.1:"+reply+"/events/result")).getString("id");}
    private void bind(ChannelsModule module,String profile,String event)throws Exception{module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",event).put("confirmed",true));}
    private void enable(ChannelsModule module,String profile)throws Exception{module.handle("owner.tasks.set",taskPermission(module,profile,true));}
    static JSONObject taskPermission(ChannelsModule module,String profile,boolean enabled)throws Exception{
        JSONArray bindings=module.snapshot().getJSONArray("bindings");for(int i=0;i<bindings.length();i++){JSONObject binding=bindings.getJSONObject(i);if(profile.equals(binding.optString("profileId")))return new JSONObject().put("profileId",profile).put("enabled",enabled).put("confirmed",true).put("expectedBindingEpoch",binding.getString("bindingEpoch")).put("expectedTaskGrantEpoch",binding.getString("taskGrantEpoch"));}throw new IllegalArgumentException("fixture binding missing");
    }
    private String inbound(ChannelsModule module,int port,String profile,JSONObject body)throws Exception{String response=post(port,profile,SECRET,body);assertTrue(response,response.startsWith("HTTP/1.1 200"));return new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4)).getString("eventId");}
    private JSONObject message(String id,String sender,String kind,String text)throws Exception{return ChannelSecurityTest.message(id,sender,kind,kind.equals("group")?"room":"").put("text",text);}
    private String post(int port,String profile,String secret,JSONObject json)throws Exception{
        byte[] body=json.toString().getBytes(StandardCharsets.UTF_8);try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(7000);String head="POST /events/"+profile+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+secret+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();ByteArrayOutputStream response=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;while((n=socket.getInputStream().read(buffer))!=-1)response.write(buffer,0,n);return new String(response.toByteArray(),StandardCharsets.UTF_8);}
    }
    public static final class FixtureKeyStore extends java.security.KeyStoreSpi {
        private static final javax.crypto.SecretKey KEY=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        public java.security.Key engineGetKey(String alias,char[] password){return KEY;}
        public java.security.cert.Certificate[] engineGetCertificateChain(String alias){return null;}
        public java.security.cert.Certificate engineGetCertificate(String alias){return null;}
        public java.util.Date engineGetCreationDate(String alias){return new java.util.Date(0);}
        public void engineSetKeyEntry(String alias,java.security.Key key,char[] password,java.security.cert.Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String alias,byte[] key,java.security.cert.Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String alias,java.security.cert.Certificate cert){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String alias){throw new UnsupportedOperationException();}
        public java.util.Enumeration<String> engineAliases(){return java.util.Collections.enumeration(java.util.Collections.singleton("pocketengine.feature-secrets.v1"));}
        public boolean engineContainsAlias(String alias){return alias.equals("pocketengine.feature-secrets.v1");}
        public int engineSize(){return 1;}
        public boolean engineIsKeyEntry(String alias){return engineContainsAlias(alias);}
        public boolean engineIsCertificateEntry(String alias){return false;}
        public String engineGetCertificateAlias(java.security.cert.Certificate cert){return null;}
        public void engineStore(java.io.OutputStream out,char[] password){}
        public void engineLoad(java.io.InputStream in,char[] password){}
    }
}
