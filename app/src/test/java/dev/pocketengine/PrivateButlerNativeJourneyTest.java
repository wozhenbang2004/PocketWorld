package dev.pocketengine;

import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual App -> Assistant -> Runner -> HTTP -> native tool -> TaskStore -> worker ->
 * durable conversation delivery. The model is an explicit loopback fixture; this is
 * not a live provider, phone/device or visual-design acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows={ReadyApplicationFixture.NoUnrelatedStartup.class,PrivateButlerNativeJourneyTest.FailureDiagnostic.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PrivateButlerNativeJourneyTest {
    /** Diagnostic only: keep the actual sanitized gateway result and print the original
     * fixture exception before Runner deliberately hides internal details from the owner. */
    @org.robolectric.annotation.Implements(value=dev.pocketengine.runtime.ModelGateway.class,isInAndroidSdk=false)
    public static final class FailureDiagnostic {
        @org.robolectric.annotation.Implementation protected static String safeMessage(Exception error,dev.pocketengine.core.ModelConfig config){
            System.err.println("PrivateButler original Runner failure:");error.printStackTrace();
            return org.robolectric.shadow.api.Shadow.directlyOn(dev.pocketengine.runtime.ModelGateway.class,"safeMessage",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Exception.class,error),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(dev.pocketengine.core.ModelConfig.class,config));
        }
    }
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ButlerJourneyKeyStore",1.0,"Explicit fixture AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void reset()throws Exception{try{ReadyApplicationFixture.closeHost((EngineApplication)RuntimeEnvironment.getApplication());}finally{java.security.Security.removeProvider("ButlerJourneyKeyStore");}}
    private interface Checked {boolean ready()throws Exception;}
    private interface Diagnostic {String describe()throws Exception;}
    private static void await(String label,Checked condition)throws Exception{await(label,condition,()->"");}
    private static void await(String label,Checked condition,Diagnostic diagnostic)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(condition.ready())return;Thread.sleep(20);}fail(label+diagnostic.describe());
    }
    private static JSONObject send(AssistantModule assistant,String conversation,String text)throws Exception{
        JSONObject input=new JSONObject().put("message",text).put("clientMessageId",UUID.randomUUID().toString()).put("mode","queue");if(conversation!=null)input.put("conversationId",conversation);return assistant.handle("send",input);
    }
    private static boolean retainsCapabilityControl(EngineApplication app,String task)throws Exception{
        var field=EngineApplication.class.getDeclaredField("capabilityControls");field.setAccessible(true);return ((java.util.Map<?,?>)field.get(app)).containsKey(task);
    }
    private static boolean offered(JSONObject request,String name)throws Exception{
        JSONArray tools=request.optJSONArray("tools");for(int i=0;tools!=null&&i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if("function".equals(tool.optString("type"))&&name.equals(tool.getJSONObject("function").optString("name")))return true;}return false;
    }
    private static JSONObject call(String id,String name,JSONObject arguments)throws Exception{
        return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",arguments.toString()));
    }
    private static void respond(HttpServer.Exchange exchange,JSONObject message)throws Exception{
        byte[] body=new JSONObject().put("id","fixture-"+UUID.randomUUID()).put("object","chat.completion").put("model","private-butler-fixture").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop")))
                .put("usage",new JSONObject().put("prompt_tokens",120).put("completion_tokens",30).put("total_tokens",150)).toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    }
    @Test public void oneButlerCanAnswerAnotherQuestionWhileItsAcceptedWorkRunsThenConsumesTheFrozenResultOnce()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase(dev.pocketengine.store.TaskStore.DATABASE_NAME);
        ReadyApplicationFixture.initializeHost(app);
        AssistantModule assistant=(AssistantModule)app.features.module("assistant");
        CountDownLatch workEntered=new CountDownLatch(1),releaseWork=new CountDownLatch(1);AtomicInteger foreground=new AtomicInteger(),workCalls=new AtomicInteger(),discoveries=new AtomicInteger(),workStarts=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);JSONObject request=new JSONObject(body);String system=request.getJSONArray("messages").getJSONObject(0).optString("content");
            JSONObject reply=new JSONObject().put("role","assistant");
            if(system.contains("持久工作任务执行者")){
                int workCall=workCalls.incrementAndGet();assertTrue(body.contains("后台工作A"));if(workCall>1)assertTrue(body.contains("定向补充D"));workEntered.countDown();assertTrue("release background work",releaseWork.await(15,TimeUnit.SECONDS));reply.put("content","后台工作A已经完成：保留原来源并交付工作结果。");
            }else{
                assertTrue("foreground must retain butler role",system.contains("长期私人AI管家"));foreground.incrementAndGet();
                if(body.contains("核对工作C")){assertTrue(body.contains("后台工作A已经完成"));reply.put("content","已结合原请求核对后台工作A的结果。");}
                else if(body.contains("独立问题B"))reply.put("content","独立问题B的回答，不影响后台工作A。");
                else if(workStarts.get()==0){
                    // The real native catalogue defers nonresident tools. Discover and
                    // checkpoint the original schema before asking the model gateway to call it.
                    if(!offered(request,"owner_work_start")){
                        assertTrue("native work must be discoverable in this physical request",offered(request,"tools_search"));assertEquals("one exact native schema discovery",1,discoveries.incrementAndGet());
                        reply.put("tool_calls",new JSONArray().put(call("call_discover_owner_work","tools_search",new JSONObject().put("query","owner_work_start").put("limit",1))));
                    }else{
                        assertEquals("one durable work admission",1,workStarts.incrementAndGet());
                        JSONObject args=new JSONObject().put("goal","完成后台工作A，核对原来源后给出结果").put("title","后台工作A").put("toolLimit",8).put("modelCallLimit",8);
                        reply.put("tool_calls",new JSONArray().put(call("call_work_a","owner_work_start",args)));
                    }
                }else{assertTrue(body.contains("workTaskId"));reply.put("content","后台工作A已接手，您可以继续问我。");}
            }
            JSONArray calls=reply.optJSONArray("tool_calls");for(int i=0;calls!=null&&i<calls.length();i++)assertTrue("fixture may only call a tool offered in this physical request",offered(request,calls.getJSONObject(i).getJSONObject("function").getString("name")));
            respond(exchange,reply);
        }catch(Throwable problem){failure.compareAndSet(null,problem);exchange.close();}});server.start();
        try{
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","private-butler-fixture").put("maxSteps",24).put("sendScreenshots",false).put("requestTimeoutSeconds",20));
            JSONObject accepted=send(assistant,null,"请执行后台工作A：研究给定问题并整理结果，后台继续做，我还要问其他问题。");String conversation=accepted.getString("conversationId"),front=accepted.getString("taskId");
            assertEquals("accepted",accepted.getString("status"));boolean entered=workEntered.await(15,TimeUnit.SECONDS);
            assertTrue("background must enter real Runner HTTP; foreground="+app.tasks.get(front,true)+"; fixture="+failure.get(),entered);
            assertEquals(1,discoveries.get());assertEquals(1,workStarts.get());
            JSONObject originalFront=app.tasks.get(front,false);String frontRun=originalFront.getString("runId");int frontEpoch=originalFront.getInt("coordinatorEpoch");
            // A real delegation operation keeps the existing needs_confirmation result
            // semantics. The short front turn has nevertheless saved its actual reply
            // and released the original control; this does not verify the worker's result.
            await("foreground reply must persist and release its original control independently",()->{
                JSONObject task=app.tasks.get(front,false);
                if(!"needs_confirmation".equals(task.optString("status"))||!frontRun.equals(task.optString("runId"))||frontEpoch!=task.optInt("coordinatorEpoch")||retainsCapabilityControl(app,front)||assistant.snapshot().optBoolean("running"))return false;
                JSONObject receipt=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",accepted.getString("clientMessageId")));
                if(!"consumed".equals(receipt.optString("status"))||receipt.optString("replyMessageId").isEmpty())return false;
                return app.tasks.conversationRepository().read(()->{JSONObject reply=app.tasks.conversationRepository().message(conversation,receipt.getString("replyMessageId"));return reply!=null&&"assistant".equals(reply.optString("role"))&&front.equals(reply.optString("taskId"))&&frontRun.equals(reply.optString("nativeRunId"))&&frontEpoch==reply.optInt("nativeEpoch");});
            },()->{
                JSONObject task=app.tasks.get(front,false),state=assistant.snapshot();
                return "; status="+task.optString("status")+"; summary="+task.optString("summary")+"; run="+task.optString("runId")+"; epoch="+task.optInt("coordinatorEpoch")
                        +"; controlRetained="+retainsCapabilityControl(app,front)+"; activeTurns="+state.optJSONArray("activeTurns")
                        +"; receipt="+assistant.handle("getSubmission",new JSONObject().put("clientMessageId",accepted.getString("clientMessageId")))
                        +"; foregroundCalls="+foreground.get()+"; fixture="+failure.get()+"; operations="+task.optJSONArray("operations");
            });
            JSONObject frontReceipt=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",accepted.getString("clientMessageId")));
            JSONObject frontReply=app.tasks.conversationRepository().read(()->app.tasks.conversationRepository().message(conversation,frontReceipt.getString("replyMessageId")));
            assertEquals("后台工作A已接手，您可以继续问我。",frontReply.getString("content"));assertTrue(frontReply.getJSONArray("replyToMessageIds").toString().contains(accepted.getString("messageId")));assertEquals(front,frontReceipt.getString("taskId"));
            JSONObject work=app.tasks.ownerWorks(conversation).getJSONObject(0);String workId=work.getString("id"),workRun=work.getString("runId");int epoch=work.getInt("coordinatorEpoch");
            assertFalse(work.has("parentTaskId"));assertEquals("running",app.tasks.get(workId,false).getString("status"));
            String supplementClient=UUID.randomUUID().toString();JSONObject supplementInput=new JSONObject().put("taskId",workId).put("runId",workRun).put("epoch",epoch).put("clientMessageId",supplementClient).put("message","定向补充D：结果请保留原来源。").put("mode","queue");
            JSONObject supplement=app.ownerWorkInput(conversation,supplementInput);assertEquals("queued",supplement.getString("status"));assertEquals(workId,supplement.getString("taskId"));assertEquals(workRun,supplement.getString("runId"));assertEquals(epoch,supplement.getInt("epoch"));
            assertTrue(app.ownerWorkInput(conversation,supplementInput).getBoolean("duplicate"));
            JSONObject second=send(assistant,conversation,"独立问题B：一加一等于几？");assertEquals("accepted",second.getString("status"));assertNotEquals(workId,second.getString("taskId"));
            await("second foreground answer",()->"completed".equals(app.tasks.get(second.getString("taskId"),false).optString("status"))&&!assistant.snapshot().optBoolean("running"));
            assertEquals("running",app.tasks.get(workId,false).getString("status"));releaseWork.countDown();
            await("frozen result should be published",()->{JSONArray pending=app.tasks.pendingOwnerWorkReceipts(conversation,10);return pending.length()==1&&"delivered".equals(pending.getJSONObject(0).optString("deliveryState"));});
            JSONObject delivery=app.tasks.pendingOwnerWorkReceipts(conversation,10).getJSONObject(0);assertEquals("pending",delivery.getString("receiptState"));
            JSONObject stored=app.tasks.conversationRepository().read(()->app.tasks.conversationRepository().message(conversation,delivery.getString("messageId")));
            assertEquals(workId,stored.getString("taskId"));assertEquals(workRun,stored.getString("nativeRunId"));assertEquals(epoch,stored.getInt("nativeEpoch"));assertEquals("owner_work",stored.getString("nativeDeliveryKind"));assertTrue(stored.getJSONArray("replyToMessageIds").toString().contains(accepted.getString("messageId")));
            JSONObject duplicate=assistant.appendOwnerWorkResult(workId,workRun,epoch);assertTrue(duplicate.getBoolean("duplicate"));assertEquals(2,workCalls.get());
            JSONObject supplementReceipt=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",supplementClient));assertEquals("consumed",supplementReceipt.getString("status"));assertEquals(delivery.getString("messageId"),supplementReceipt.getString("replyMessageId"));
            JSONObject sourceReceipt=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",accepted.getString("clientMessageId")));assertEquals(front,sourceReceipt.getString("taskId"));
            JSONObject third=send(assistant,conversation,"核对工作C：结合刚才的后台工作A结果告诉我结论。");
            await("result must be provided to a completed butler reply",()->"completed".equals(app.tasks.get(third.getString("taskId"),false).optString("status"))&&app.tasks.pendingOwnerWorkReceipts(conversation,10).length()==0,()->{
                JSONObject task=app.tasks.get(third.getString("taskId"),false);JSONArray pending=app.tasks.pendingOwnerWorkReceipts(conversation,10),states=new JSONArray();
                for(int i=0;i<pending.length();i++){JSONObject row=pending.getJSONObject(i),state=new JSONObject();for(String key:new String[]{"taskId","runId","epoch","deliveryId","deliveryState","receiptState","messageId","butlerClaim"})if(row.has(key))state.put(key,row.get(key));states.put(state);}
                return "; status="+task.optString("status")+"; summary="+task.optString("summary")+"; run="+task.optString("runId")+"; epoch="+task.optInt("coordinatorEpoch")
                        +"; controlRetained="+retainsCapabilityControl(app,third.getString("taskId"))+"; activeTurns="+assistant.snapshot().optJSONArray("activeTurns")
                        +"; receipt="+assistant.handle("getSubmission",new JSONObject().put("clientMessageId",third.getString("clientMessageId")))
                        +"; pendingResults="+states+"; foregroundCalls="+foreground.get()+"; workCalls="+workCalls.get()+"; fixture="+failure.get();
            });
            assertEquals("finished background work must never be replayed to regenerate a reply",2,workCalls.get());
            JSONObject source=assistant.readOriginalReplySource(conversation,delivery.getString("messageId"),accepted.getString("messageId"),0,1000);assertTrue(source.getBoolean("available"));assertTrue(source.getString("text").contains("后台工作A"));
            assertThrows(SecurityException.class,()->assistant.readOriginalReplySource(conversation,delivery.getString("messageId"),second.getString("messageId"),0,1000));
            if(failure.get()!=null)throw new AssertionError(failure.get());
        }finally{releaseWork.countDown();server.stop(0);ReadyApplicationFixture.closeHost(app);}
    }
}
