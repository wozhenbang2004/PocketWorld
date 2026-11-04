package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.ChannelTaskCoordinator;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual TaskStore/coordinator/channel transport/journal/runner. Accounts and model replies are synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelTaskCoordinatorTest {
    private static final String SECRET="fixture-relay-authority-at-least-32-bytes";
    @Before public void keyFixture(){java.security.Security.addProvider(new java.security.Provider("ChannelCoordinatorKeyStore","1","Synthetic channel fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeKeyFixture(){java.security.Security.removeProvider("ChannelCoordinatorKeyStore");}

    @Test public void cancelBeforeWorkerStartsNeverCallsRunnerAndSettlesDurably()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger calls=new AtomicInteger();f.install((id,initial,config,control)->{calls.incrementAndGet();return new JSONObject();});
            JSONObject request=f.submit("入队后取消");String id=request.getString("taskId");assertEquals(1,f.work.size());
            f.channels.handle("task.cancel",new JSONObject().put("requestId",request.getString("requestId")));
            assertEquals("stopping",f.tasks.get(id,false).getString("status"));f.work.runNext();
            assertEquals(0,calls.get());assertEquals("cancelled",f.tasks.get(id,false).getString("status"));assertTrue(f.controls.isEmpty());
            assertEquals(1,f.tasks.get(id,false).getInt("coordinatorEpoch"));assertEquals(1,f.tasks.list().length());
        }
    }
    @Test public void staleOwnerStopCannotSelectTheCurrentChannelRun()throws Exception {
        try(Fixture f=new Fixture()){
            f.install((id,initial,config,control)->new JSONObject());
            String id=f.submit("原运行取消必须有身份").getString("taskId");JSONObject before=f.tasks.get(id,false);
            RunControl original=f.controls.get(id);assertNotNull(original);
            assertThrows(SecurityException.class,()->f.coordinator.stopOriginal(id,UUID.randomUUID().toString(),before.getInt("coordinatorEpoch")));
            assertFalse(original.isCancelled());assertEquals(before.getString("status"),f.tasks.get(id,false).getString("status"));
            assertFalse(f.tasks.get(id,false).optBoolean("cancelRequested"));
        }
    }
    @Test public void reviewedResumeReservesTheOriginalIdentityAndRejectsADelayedSecondRequest()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger calls=new AtomicInteger();f.install((id,initial,config,control)->{
                calls.incrementAndGet();JSONObject current=f.tasks.requireCapabilityAttempt(id,control);
                f.tasks.endAttempt(id,current.getString("runId"),"paused","Original worker settled",new JSONObject());return new JSONObject();
            });
            String id=f.submit("原渠道继续保留身份").getString("taskId");f.work.runNext();JSONObject first=f.tasks.get(id,false);
            assertEquals(1,calls.get());assertTrue(f.controls.isEmpty());assertEquals("paused",first.getString("status"));
            f.coordinator.resumeOriginal(id,first.getString("runId"),first.getInt("coordinatorEpoch"),"Keep the original receipt");
            JSONObject next=f.tasks.get(id,false);assertEquals(first.getInt("coordinatorEpoch")+1,next.getInt("coordinatorEpoch"));
            assertTrue(f.controls.get(id).matchesHostRun(id,next.getString("runId"),next.getInt("coordinatorEpoch")));assertEquals(1,f.work.size());
            assertEquals("Keep the original receipt",next.getString("feedback"));String before=next.toString();
            assertThrows(SecurityException.class,()->f.coordinator.resumeOriginal(id,first.getString("runId"),first.getInt("coordinatorEpoch"),"Stale feedback"));
            assertEquals(before,f.tasks.get(id,false).toString());assertEquals(1,f.work.size());f.work.runNext();
            assertEquals(2,calls.get());assertTrue(f.controls.isEmpty());assertEquals(next.getString("runId"),f.tasks.get(id,false).getString("runId"));
        }
    }
    @Test public void revocationWhileQueuedFencesDispatchAndNativeStopStillWorks()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger calls=new AtomicInteger();f.install((id,initial,config,control)->{calls.incrementAndGet();return new JSONObject();});
            String id=f.submit("撤销后不能执行").getString("taskId");f.channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(f.channels,f.profile,false));
            f.work.runNext();assertEquals(0,calls.get());assertEquals("cancelled",f.tasks.get(id,false).getString("status"));assertTrue(f.controls.isEmpty());
            assertThrows(SecurityException.class,()->f.coordinator.resume(id,""));f.coordinator.stop(id);
            assertTrue(f.tasks.get(id,false).getBoolean("cancelRequested"));assertEquals(1,f.tasks.list().length());
        }
    }
    @Test public void workerRejectionLeavesRecoverableSameTaskNotFakeCancellation()throws Exception{
        try(Fixture f=new Fixture()){
            f.work.reject=true;f.install((id,initial,config,control)->{fail("rejected worker cannot run");return null;});
            JSONObject request=f.submit("保存后队列不可用");String id=request.getString("taskId");
            assertEquals("admission_unknown",request.getString("admission"));assertEquals("interrupted",f.tasks.get(id,false).getString("status"));
            assertTrue(f.controls.isEmpty());assertEquals(1,f.tasks.list().length());f.work.reject=false;
            JSONObject inspected=f.channels.handle("task.inspect",new JSONObject().put("requestId",request.getString("requestId")));assertTrue(inspected.getBoolean("found"));assertEquals(id,inspected.getString("taskId"));
            assertEquals(0,f.work.size());assertEquals(1,f.tasks.get(id,false).getInt("coordinatorEpoch"));
        }
    }
    @Test public void processRecoveryQueuesReceiptButNeverReplaysTheQueuedModel()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger calls=new AtomicInteger();f.install((id,initial,config,control)->{calls.incrementAndGet();return new JSONObject();});
            String id=f.submit("恢复不自动重放").getString("taskId");f.work.pending.clear();f.controls.clear();
            TaskStore restored=new TaskStore(f.android);ChannelTaskCoordinator recovered=new ChannelTaskCoordinator(restored,f.host,f.channels,f.work,f.controls,()->f.config,(task,initial,config,control)->{calls.incrementAndGet();return new JSONObject();},task->{},message->{});
            recovered.recover();assertEquals(0,calls.get());assertEquals(0,f.work.size());assertEquals("interrupted",restored.get(id,false).getString("status"));
            JSONObject request=f.channels.snapshot().getJSONArray("taskRequests").getJSONObject(0);assertEquals("admitted",request.getString("admission"));assertEquals(id,request.getString("taskId"));
            JSONArray outbox=f.channels.snapshot().getJSONArray("outbox");assertTrue(outbox.toString().contains("上次执行已中断"));int count=outbox.length();recovered.recover();assertEquals(count,f.channels.snapshot().getJSONArray("outbox").length());
        }
    }
    @Test public void ownerApprovalResumesSameTaskAndRealRunnerWritesOnceThenReportsActualReceipt()throws Exception{
        AtomicInteger modelCalls=new AtomicInteger();HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(Fixture f=new Fixture()){
            WorkspaceModule workspace=new WorkspaceModule(f.context);f.host.register(workspace);
            String project=workspace.handle("create",new JSONObject().put("name","渠道合成测试项目")).getString("id");
            JSONObject write=new JSONObject().put("projectId",project).put("path","notes.txt").put("content","已通过宿主精确审批写入").put("expectedVersion",0);
            model.createContext("/",exchange->{
                int round=modelCalls.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                assertTrue(request.getJSONArray("tools").toString().contains("workspace_write"));assertFalse(request.getJSONArray("tools").toString().contains("phone_tap"));
                JSONObject message=round<=2?new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-"+round).put("type","function").put("function",new JSONObject().put("name","workspace_write").put("arguments",write.toString())))):new JSONObject().put("role","assistant").put("content","文件已写入；请核对实际内容。");
                // The current native gateway requests streaming; use the actual SSE contract rather than a legacy JSON body.
                if(message.has("tool_calls"))message.getJSONArray("tool_calls").getJSONObject(0).put("index",0);
                JSONObject delta=new JSONObject().put("id","fixture-"+round).put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",message).put("finish_reason",JSONObject.NULL)));
                JSONObject terminal=new JSONObject().put("id","fixture-"+round).put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop")));
                byte[] bytes=("data: "+delta+"\n\ndata: "+terminal+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });model.start();f.config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort()+"/v1","","synthetic",false,8,10);
            f.install((id,initial,config,control)->{
                // Mirrors the required shared runner hook; no alternate product planner is introduced.
                f.coordinator.protect(id,control);control.check();JSONObject attempt=f.tasks.requireCapabilityAttempt(id,control);
                HostToolSupervisor port=new HostToolSupervisor(f.host,f.tasks,id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),true);JSONArray names=new JSONArray();JSONArray definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));f.tasks.authorizeTools(id,attempt.getString("runId"),names);
                RunJournal journal=f.tasks.journal(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),control);
                RunOutcome result=new AgentRunner(null,port).run(id,attempt.getString("goal"),config.withStepCeiling(attempt.getInt("stepCeiling")),control,event->{try{f.tasks.event(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),event);}catch(Exception error){throw new IllegalStateException(error);}},journal);
                f.tasks.endAttempt(id,attempt.getString("runId"),result.status,result.summary,result.evidence);return new JSONObject().put("status",result.status);
            });
            JSONObject source=f.submit("在项目里写下笔记");String id=source.getString("taskId");f.work.runNext();JSONObject waiting=f.tasks.get(id,false);
            assertEquals("awaiting_user",waiting.getString("status"));assertEquals(1,modelCalls.get());assertEquals(0,workspace.handle("list",new JSONObject().put("projectId",project)).getJSONArray("files").length());
            String approval=waiting.getJSONArray("approvals").getJSONObject(0).getString("id");assertEquals("pending",waiting.getJSONArray("approvals").getJSONObject(0).getString("state"));
            f.inbound("agreement", "同意批准所有写入");assertEquals(0,f.work.size());assertEquals("pending",f.tasks.get(id,false).getJSONArray("approvals").getJSONObject(0).getString("state"));
            f.tasks.decideCapabilityApproval(id,approval,true);f.coordinator.resume(id,"主人在本机批准原参数");f.work.runNext();JSONObject result=f.tasks.get(id,false);
            assertEquals("needs_confirmation",result.getString("status"));assertEquals(2,result.getInt("coordinatorEpoch"));assertEquals(3,modelCalls.get());assertEquals(1,f.tasks.list().length());assertEquals("channel",result.getJSONObject("origin").getString("kind"));
            assertEquals("已通过宿主精确审批写入",workspace.handle("read",write).getString("content"));assertEquals(1,workspace.handle("history",write).getJSONArray("history").length());
            assertEquals("consumed",result.getJSONArray("approvals").getJSONObject(0).getString("state"));assertFalse(f.tasks.hasUnresolved(id));
            JSONArray outbox=f.channels.snapshot().getJSONArray("outbox");assertTrue(outbox.toString().contains("文件已写入"));assertTrue(outbox.toString().contains("需要主人确认"));
            assertTrue(f.delivered.toString().contains("文件已写入"));for(int i=0;i<outbox.length();i++){assertEquals("sent",outbox.getJSONObject(i).getString("status"));assertTrue(outbox.getJSONObject(i).getString("receiptId").startsWith("synthetic-receipt-"));}
        }finally{model.stop(0);}
    }

    private static final class WorkQueue implements Executor {
        final ArrayDeque<Runnable> pending=new ArrayDeque<>();boolean reject;
        public void execute(Runnable work){if(reject)throw new java.util.concurrent.RejectedExecutionException("synthetic capacity failure");pending.add(work);}
        int size(){return pending.size();}void runNext(){pending.remove().run();}
    }
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        final TaskStore tasks=new TaskStore(android);final FeatureHost host=new FeatureHost(event->{},tasks,()->false);final ChannelsModule channels=new ChannelsModule(context);
        final java.util.concurrent.CopyOnWriteArrayList<JSONObject> delivered=new java.util.concurrent.CopyOnWriteArrayList<>();
        final ChannelServer receiver=new ChannelServer((profile,headers,bytes)->{assertEquals("Bearer "+SECRET,headers.get("authorization"));delivered.add(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));return new JSONObject().put("id","synthetic-receipt-"+delivered.size());});
        final WorkQueue work=new WorkQueue();final ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();final String profile;final int ingress;
        ModelConfig config=new ModelConfig("https://model.invalid/v1","","synthetic",false,8,10);ChannelTaskCoordinator coordinator;
        Fixture()throws Exception{
            profile=channels.handle("profile.save",new JSONObject().put("name","合成认证渠道").put("provider","webhook").put("secret",SECRET).put("enabled",true).put("autoReply",false).put("replyUrl","http://127.0.0.1:"+receiver.start(0)+"/events/results")).getString("id");
            ingress=channels.handle("server.start",new JSONObject().put("port",0)).getInt("port");String event=inbound("binding","绑定依据");
            channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",event).put("confirmed",true));channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(channels,profile,true));
        }
        void install(ChannelTaskCoordinator.AttemptRunner runner){coordinator=new ChannelTaskCoordinator(tasks,host,channels,work,controls,()->config,runner,id->{},message->{});channels.setHostTaskPort(coordinator);}
        JSONObject submit(String goal)throws Exception{inbound(UUID.randomUUID().toString(),"/task "+goal);return channels.snapshot().getJSONArray("taskRequests").getJSONObject(0);}
        String inbound(String message,String text)throws Exception{
            JSONObject body=ChannelSecurityTest.message(message,"owner","dm","").put("text",text);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            try(Socket socket=new Socket("127.0.0.1",ingress)){socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /events/"+profile+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+SECRET+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response,response.startsWith("HTTP/1.1 200"));return new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4)).getString("eventId");}
        }
        public void close(){channels.close();receiver.close();host.close();}
    }
}
