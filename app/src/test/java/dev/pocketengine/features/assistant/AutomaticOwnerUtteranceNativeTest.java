package dev.pocketengine.features.assistant;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.memory.SourceRef;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original Application/TaskStore/Runner and local model bytes; never a paid endpoint. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AutomaticOwnerUtteranceNativeTest {
    @org.junit.Before public void nativeKeyFixture(){java.security.Security.addProvider(new java.security.Provider("AutomaticLearningFixtureKeys",1.0d,"Test-only AndroidKeyStore provider"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @org.junit.After public void removeNativeKeyFixture(){java.security.Security.removeProvider("AutomaticLearningFixtureKeys");}
    @Test public void automaticReaderRequiresActuallyAdoptedCurrentUserRevision()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        AssistantModule assistant=(AssistantModule)app.features.module("assistant");
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"FIXTURE-ASSISTANT-UNTRUSTED\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
        http.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).putString("provider","openai_chat").putString("model","controlled-fixture").putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            String text="FIXTURE-REAL-OWNER-UTTERANCE";
            JSONObject ack=assistant.handle("send",new JSONObject().put("message",text).put("clientMessageId",UUID.randomUUID().toString()));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(assistant.snapshot().optBoolean("running")&&System.nanoTime()<until)Thread.sleep(10);
            assertFalse("original task did not settle",assistant.snapshot().optBoolean("running"));
            var controlsField=EngineApplication.class.getDeclaredField("capabilityControls");controlsField.setAccessible(true);
            while(((java.util.Map<?,?>)controlsField.get(app)).containsKey(ack.getString("taskId"))&&System.nanoTime()<until)Thread.sleep(10);
            // The model's ordinary final answer is not its own success proof.
            // Exercise the real native owner-confirmation action in this controlled fixture.
            JSONObject displayed=app.tasks.get(ack.getString("taskId"),false);
            assertTrue("original result did not reach review: "+displayed.optString("status")+" / "+displayed.optString("summary"),displayed.optString("status").matches("completed|needs_confirmation"));
            if(!"completed".equals(displayed.optString("status")))app.dispatchOwnerAction("confirmResult",new JSONObject().put("taskId",ack.getString("taskId")).put("resultReviewDigest",displayed.getString("resultReviewDigest")).put("completed",true).put("feedback",""));
            JSONObject task=app.tasks.get(ack.getString("taskId"),false);assertEquals("completed",task.getString("status"));
            String taskId=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
            SourceRef ref=new SourceRef("message",ack.getString("messageId"),1,"owner","private");
            assertEquals(text,assistant.readAutomaticOwnerUtterance(ref,taskId,run,epoch).text);
            assertThrows(SecurityException.class,()->assistant.readAutomaticOwnerUtterance(ref,taskId,"different-native-run",epoch));
            JSONArray messages=assistant.handle("get",new JSONObject().put("conversationId",ack.getString("conversationId"))).getJSONArray("messages");
            JSONObject reply=messages.getJSONObject(messages.length()-1);assertEquals("assistant",reply.getString("role"));
            SourceRef notUser=new SourceRef("message",reply.getString("id"),reply.getLong("revision"),"owner","private");
            assertThrows(SecurityException.class,()->assistant.readAutomaticOwnerUtterance(notUser,taskId,run,epoch));
            assistant.handle("editMessage",new JSONObject().put("conversationId",ack.getString("conversationId")).put("messageId",ref.sourceId).put("revision",1).put("content","changed owner revision"));
            assertThrows(Exception.class,()->assistant.readAutomaticOwnerUtterance(ref,taskId,run,epoch));
        }finally{assistant.handle("cancel",new JSONObject());assistant.close();http.stop(0);}
    }
    @Test public void successfulOriginalOwnerTurnAutomaticallyStagesOneBoundedQuotedCandidate()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();AssistantModule assistant=(AssistantModule)app.features.module("assistant");
        var memory=(dev.pocketengine.features.memory.MemoryModule)app.features.module("memory");JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        String words="我希望先看真实证据再下结论。";java.util.concurrent.atomic.AtomicInteger mainCalls=new java.util.concurrent.atomic.AtomicInteger(),learningCalls=new java.util.concurrent.atomic.AtomicInteger();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));boolean learning=request.getJSONArray("messages").getJSONObject(0).getString("content").contains("记忆候选提炼器");String content;
            if(learning){learningCalls.incrementAndGet();JSONObject actual=new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));assertEquals(words,actual.getString("text"));content=new JSONObject().put("layer","preference").put("text","未经过主人确认的模型改写").put("quote",words).put("reason","只根据本次真实原话提出").put("applicability","").put("confidence",0.5).toString();}
            else{mainCalls.incrementAndGet();content="已收到你的原话；这不是自动成功证明。";}
            JSONObject response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",content)).put("finish_reason","stop")));byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});http.start();
        try{
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).putString("provider","openai_chat").putString("model","controlled-automatic-fixture").putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            JSONObject policy=app.automaticUserLearningPolicy();assertFalse(policy.getBoolean("enabled"));app.setAutomaticUserLearningPolicy(policy.getLong("revision"),true,1,true);
            JSONObject ack=assistant.handle("send",new JSONObject().put("message",words).put("clientMessageId",UUID.randomUUID().toString()));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            var controls=EngineApplication.class.getDeclaredField("capabilityControls");controls.setAccessible(true);
            while((assistant.snapshot().optBoolean("running")||((java.util.Map<?,?>)controls.get(app)).containsKey(ack.getString("taskId")))&&System.nanoTime()<until)Thread.sleep(10);
            JSONObject displayed=app.tasks.get(ack.getString("taskId"),false);assertTrue(displayed.optString("summary"),displayed.getString("status").matches("completed|needs_confirmation"));
            if("needs_confirmation".equals(displayed.getString("status")))app.dispatchOwnerAction("confirmResult",new JSONObject().put("taskId",ack.getString("taskId")).put("resultReviewDigest",displayed.getString("resultReviewDigest")).put("completed",true).put("feedback",""));
            JSONObject job=null;JSONArray candidates=new JSONArray();
            while(System.nanoTime()<until){JSONArray jobs=app.tasks.listSourceLearningJobs("",20).getJSONArray("jobs");if(jobs.length()>0)job=jobs.getJSONObject(0);candidates=memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates");if(job!=null&&"completed".equals(job.optString("taskStatus"))&&candidates.length()==1)break;Thread.sleep(10);}
            assertNotNull("original event did not register its original learning job",job);assertEquals(job.toString(),"completed",job.getString("taskStatus"));assertEquals(1,mainCalls.get());assertEquals(1,learningCalls.get());assertEquals(1,candidates.length());assertEquals(0,memory.handle("list",new JSONObject().put("scope",scope)).getJSONArray("records").length());
            JSONObject candidate=candidates.getJSONObject(0);assertEquals(words,candidate.getString("text"));assertEquals("pending",candidate.getString("status"));assertEquals(new SourceRef("message",ack.getString("messageId"),1,"owner","private"),SourceRef.fromJson(candidate.getJSONArray("sourceRefs").getJSONObject(0)));
            assertEquals(1,job.getJSONObject("job").getInt("nextIndex"));assertEquals("awaiting_review",job.getJSONObject("job").getJSONArray("results").getJSONObject(0).getString("outcome"));assertEquals("completed",app.tasks.get(ack.getString("taskId"),false).getString("status"));
        }finally{JSONObject policy=app.automaticUserLearningPolicy();app.setAutomaticUserLearningPolicy(policy.getLong("revision"),false,1,false);assistant.handle("cancel",new JSONObject());assistant.close();http.stop(0);}
    }
}
