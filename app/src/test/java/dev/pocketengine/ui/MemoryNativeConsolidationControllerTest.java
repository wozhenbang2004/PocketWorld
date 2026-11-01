package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.core.RunControl;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual native owner worker/Application/single MemoryModule/TaskStore/ModelGateway; no fixture host. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class MemoryNativeConsolidationControllerTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("MemoryMountedKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    interface Check{boolean done()throws Exception;}
    static void await(Check check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!check.done()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue("native memory condition missing",check.done());}
    static JSONObject j(Object... pairs)throws Exception{JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();final MemoryModule memory=(MemoryModule)app.features.module("memory");final PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
        final AtomicInteger calls=new AtomicInteger();final AtomicReference<JSONObject> body=new AtomicReference<>();final CountDownLatch arrived=new CountDownLatch(1),release=new CountDownLatch(1);final HttpServer server;
        final JSONObject a,b;final JSONArray selections;volatile boolean blocked;volatile String answer;
        Fixture(boolean blocked)throws Exception{
            this.blocked=blocked;answer=j("layer","fact","text","两条机主保存的事实都涉及明确的项目验收习惯","reason","仅由本次两条选定记忆支持，仍待机主核对","applicability","本次项目与已描述情境，不泛化其他情境","confidence",0.7).toString();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",e->{try{calls.incrementAndGet();JSONObject request=new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));body.set(request);arrived.countDown();if(this.blocked)release.await(8,TimeUnit.SECONDS);byte[] bytes=j("choices",new JSONArray().put(j("message",j("role","assistant","content",answer),"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}catch(Exception failure){throw new IllegalStateException(failure);}finally{e.close();}});server.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","memory-fixture").putInt("maxOutputTokens",1024).putInt("maxSteps",8).putBoolean("sendScreenshots",false).commit();
            a=memory.handle("save",j("scope",scope(),"record",j("layer","fact","text","机主要求任务必须有真实结果证据","source","主人明确保存"))).getJSONObject("record");b=memory.handle("save",j("scope",scope(),"record",j("layer","fact","text","机主要求项目功能达到成熟下限后验收","source","主人明确保存"))).getJSONObject("record");selections=new JSONArray().put(j("id",a.getString("id"),"revision",a.getLong("revision"))).put(j("id",b.getString("id"),"revision",b.getLong("revision")));controller.attach();controller.enterOwnerDomain("memoryReview");
        }
        String propose()throws Exception{return controller.requestOwnerDomain("memoryReview","proposeConsolidation",new JSONObject().put("selections",selections));}
        JSONObject result(String id)throws Exception{await(()->id.equals(controller.getState().ownerResponses("memoryReview").getOrDefault("proposeConsolidation",new JSONObject()).optString("requestId")));return controller.getState().ownerResponses("memoryReview").get("proposeConsolidation");}
        @Override public void close(){release.countDown();controller.detach();app.features.close();server.stop(0);java.security.Security.removeProvider("MemoryMountedKeyStore");}
    }
    @Test public void explicitNativeSelectionProducesOnlyPendingThenOriginalAtomicAcceptSavesFormalMemory()throws Exception{
        try(Fixture f=new Fixture(false)){
            String id=f.propose();JSONObject envelope=f.result(id);assertTrue(envelope.optString("error"),envelope.getBoolean("ok"));JSONObject result=envelope.getJSONObject("result"),candidate=result.getJSONObject("candidate");assertTrue(result.getBoolean("proposed"));assertTrue(result.getBoolean("reviewRequired"));assertFalse(result.getBoolean("formalMemorySaved"));assertEquals("pending",candidate.getString("status"));assertEquals(2,candidate.getJSONArray("recordRefs").length());assertEquals(0,candidate.getJSONArray("sourceRefs").length());assertEquals(2,f.memory.handle("list",j("scope",scope())).getJSONArray("records").length());assertEquals(1,f.calls.get());assertTrue(f.body.get().optJSONArray("tools")==null||f.body.get().getJSONArray("tools").length()==0);
            JSONObject parent=f.app.nativeOwnerWorkflows.memory("getParentRecord",j("id",f.a.getString("id"),"revision",f.a.getLong("revision")));assertEquals(f.a.getString("text"),parent.getJSONObject("record").getString("text"));assertThrows(IllegalStateException.class,()->f.app.nativeOwnerWorkflows.memory("getParentRecord",j("id",f.a.getString("id"),"revision",f.a.getLong("revision")+1)));
            String adopted=f.controller.requestOwnerDomain("memoryReview","acceptCandidate",j("id",candidate.getString("id"),"revision",candidate.getLong("revision")));await(()->adopted.equals(f.controller.getState().ownerResponses("memoryReview").getOrDefault("acceptCandidate",new JSONObject()).optString("requestId")));JSONObject accepted=f.controller.getState().ownerResponses("memoryReview").get("acceptCandidate");assertTrue(accepted.optString("error"),accepted.getBoolean("ok"));JSONObject saved=accepted.getJSONObject("result").getJSONObject("record");assertEquals(candidate.getString("id"),saved.getString("learningCandidateId"));assertEquals(3,f.memory.handle("list",j("scope",scope())).getJSONArray("records").length());assertFalse(saved.optBoolean("core"));assertThrows(SecurityException.class,()->f.app.dispatchOwnerAction("resumeTask",j("taskId",result.getString("hostTaskId"))));assertEquals(1,f.calls.get());
        }
    }
    @Test public void cancellationUsesOriginalControlWhileModelIoIsOutsideAdmissionLock()throws Exception{
        try(Fixture f=new Fixture(true)){String request=f.propose();assertTrue(f.arrived.await(5,TimeUnit.SECONDS));String cancel=f.controller.cancelConsolidation(request);JSONObject envelope=f.controller.getState().ownerResponses("memoryReview").get("cancelConsolidation");assertEquals(cancel,envelope.getString("requestId"));assertTrue(envelope.getBoolean("ok"));assertTrue(envelope.getJSONObject("result").getBoolean("cancelled"));assertEquals("stopped",envelope.getJSONObject("result").getString("outcome"));f.release.countDown();await(()->f.app.tasks.list().length()==1&&f.app.tasks.list().getJSONObject(0).optString("status").equals("cancelled"));assertEquals(0,f.memory.handle("listCandidates",j("scope",scope())).getJSONArray("candidates").length());assertEquals(1,f.calls.get());}
    }
    @Test public void parentRevisionChangeDuringModelResponseCannotSaveOldCandidate()throws Exception{
        try(Fixture f=new Fixture(true)){String request=f.propose();assertTrue(f.arrived.await(5,TimeUnit.SECONDS));f.memory.handle("save",j("scope",scope(),"record",j("id",f.a.getString("id"),"revision",f.a.getLong("revision"),"layer","fact","text","机主已明确修订原来源事实，不能沿用旧结论","source","主人明确修订")));f.release.countDown();assertFalse(f.result(request).getBoolean("ok"));assertEquals(0,f.memory.handle("listCandidates",j("scope",scope())).getJSONArray("candidates").length());assertEquals(1,f.calls.get());}
    }
    @Test public void actualRunnerReceivesEphemeralWorkingNotesButTaskCheckpointNeverPersistsThem()throws Exception{
        try(Fixture f=new Fixture(false)){f.answer="实际本轮答复";AssistantModule assistant=(AssistantModule)f.app.features.module("assistant");JSONObject submitted=assistant.handle("send",j("message","检查本轮临时工作记忆","clientMessageId",java.util.UUID.randomUUID().toString()));await(()->!assistant.snapshot().getBoolean("running")&&f.calls.get()==1);assertTrue(f.body.get().toString().contains("当前任务的临时工作记忆"));JSONObject task=f.app.tasks.get(submitted.getString("taskId"),false);assertFalse(task.toString().contains("当前任务的临时工作记忆"));java.lang.reflect.Field sessions=f.app.features.getClass().getDeclaredField("workingMemory");sessions.setAccessible(true);assertTrue(((java.util.Map<?,?>)sessions.get(f.app.features)).isEmpty());}
    }
    @Test public void completedAtomicCandidateCannotBeReportedAsRolledBackByLateCancellation()throws Exception{
        try(Fixture f=new Fixture(true)){String id=f.propose();assertTrue(f.arrived.await(5,TimeUnit.SECONDS));java.lang.reflect.Field field=EngineApplication.class.getDeclaredField("memoryConsolidations");field.setAccessible(true);java.util.Map<?,?> requests=(java.util.Map<?,?>)field.get(f.app);assertEquals(1,requests.size());RunControl original=(RunControl)requests.keySet().iterator().next();f.release.countDown();assertTrue(f.result(id).getBoolean("ok"));JSONObject late=f.app.cancelMemoryConsolidation(original);assertFalse(late.getBoolean("cancelled"));assertEquals("already_settled",late.getString("outcome"));assertEquals(1,f.memory.handle("listCandidates",j("scope",scope())).getJSONArray("candidates").length());assertEquals(1,f.calls.get());}
    }
    @Test public void modelSelectionChangeCannotAdmitAnOldRouteCandidate()throws Exception{
        try(Fixture f=new Fixture(true)){String request=f.propose();assertTrue(f.arrived.await(5,TimeUnit.SECONDS));f.app.getSharedPreferences("private_settings",0).edit().putString("model","replacement-model").commit();f.release.countDown();assertFalse(f.result(request).getBoolean("ok"));assertEquals(0,f.memory.handle("listCandidates",j("scope",scope())).getJSONArray("candidates").length());assertEquals(1,f.calls.get());}
    }
    @Test public void nativeForgetScrubsWorkingSessionAndWithdrawsAnAnswerAgainstOldMemory()throws Exception{
        try(Fixture f=new Fixture(false)){JSONObject task=f.app.tasks.createCapabilityTask("读取已保存来源","");String id=task.getString("id");JSONObject attempt=f.app.tasks.startAttempt(id,"capabilities",8);RunControl control=new RunControl();control.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));HostToolSupervisor port=new HostToolSupervisor(f.app.features,f.app.tasks,id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),true);port.beginRun("读取已保存来源",control);assertNotNull(port.contextSnapshot("真实证据").optJSONObject("workingMemory"));f.app.nativeOwnerWorkflows.memory("forgetReviewed",j("id",f.a.getString("id"),"expectedRevision",f.a.getLong("revision")));assertFalse(port.responseCurrent(control));assertNull(port.contextSnapshot("真实证据").optJSONObject("workingMemory"));port.close();assertEquals(0,f.calls.get());}
    }
}
