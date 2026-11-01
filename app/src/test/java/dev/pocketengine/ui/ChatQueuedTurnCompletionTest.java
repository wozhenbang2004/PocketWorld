package dev.pocketengine.ui;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real native Controller -> original Task/Runner -> loopback HTTP -> durable conversation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ChatQueuedTurnCompletionTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("QueueCompletionKeyStore","1","Synthetic local fixture key"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeKeys(){java.security.Security.removeProvider("QueueCompletionKeyStore");}
    private interface Check {boolean done()throws Exception;}
    private static void await(Check check)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(!check.done()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue("native condition not reached",check.done());
    }
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final AssistantModule module=(AssistantModule)app.features.module("assistant");
        final List<JSONObject> bodies=new CopyOnWriteArrayList<>();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final HttpServer server;
        final PocketWorldController controller;
        Fixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                int n=bodies.size();bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                if(n==0){entered.countDown();if(!release.await(9,TimeUnit.SECONDS))throw new AssertionError("fixture gate timed out");}
                byte[] reply=("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""+(n<2?"SAME_COMPLETE_ANSWER":"NEW_COMPLETE_ANSWER")+"\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
            }catch(Exception failure){if(!moduleRunningQuietly())return;throw new IllegalStateException(failure);}finally{exchange.close();}});
            server.start();app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","fixture").putString("provider","openai_chat").putInt("maxSteps",8).commit();
            controller=new PocketWorldController(new ComposeActivity(),app);controller.attach();await(()->controller.getState().feature("assistant").optBoolean("sendAcknowledgements"));
        }
        private boolean moduleRunningQuietly(){try{return module.snapshot().getBoolean("running");}catch(Exception ignored){return false;}}
        JSONObject queue()throws Exception {
            controller.sendChat("FIRST-OWNER-INPUT");assertTrue(entered.await(5,TimeUnit.SECONDS));await(()->!controller.getState().getChatSubmitting()&&controller.getState().getChatActiveTurn()!=null);
            JSONObject original=new JSONObject(controller.getState().getChatActiveTurn().toString());controller.sendChat("QUEUED-OWNER-INPUT");await(()->!controller.getState().getChatSubmitting()&&controller.getState().getChatReceipts().size()==2);
            assertEquals(original.getString("taskId"),controller.getState().getChatActiveTurn().getString("taskId"));assertEquals(1,app.tasks.list().length());return original;
        }
        void idle()throws Exception {await(()->!module.snapshot().getBoolean("running")&&!controller.getState().getChatBusy());}
        JSONObject conversation(String id)throws Exception {return module.handle("get",new JSONObject().put("conversationId",id));}
        public void close(){release.countDown();controller.detach();module.close();server.stop(0);}
    }
    private static JSONObject message(JSONArray rows,String id)throws Exception {for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("id")))return rows.getJSONObject(i);throw new AssertionError("missing durable reply "+id);}

    @Test public void identicalAnswersAreDistinctFormalRoundsAndBothReceiptsBecomeDurablyConsumed()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.queue();f.release.countDown();f.idle();assertEquals(2,f.bodies.size());
            await(()->f.controller.getState().getChatReceipts().values().stream().allMatch(r->"consumed".equals(r.optString("status"))));
            JSONArray messages=f.conversation(original.getString("conversationId")).getJSONArray("messages");Set<String> ids=new HashSet<>();int answerCount=0;
            for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if("assistant".equals(row.getString("role"))){assertEquals("SAME_COMPLETE_ANSWER",row.getString("content"));assertEquals(original.getString("taskId"),row.getString("taskId"));assertEquals(original.getString("runId"),row.getString("nativeRunId"));assertTrue(ids.add(row.getString("id")));answerCount++;}}
            assertEquals("Two actual completed responses must survive even when their text is equal",2,answerCount);
            JSONObject disk=new JSONObject(Files.readString(new java.io.File(f.app.getFilesDir(),"private_conversations.json").toPath()));JSONArray stored=disk.getJSONArray("conversations").getJSONObject(0).getJSONArray("messages");
            for(JSONObject ui:f.controller.getState().getChatReceipts().values()){
                JSONObject receipt=f.module.handle("getSubmission",new JSONObject().put("clientMessageId",ui.getString("clientMessageId")));assertEquals("consumed",receipt.getString("status"));assertEquals(original.getString("runId"),receipt.getString("runId"));assertTrue(receipt.getLong("consumedAt")>0);
                JSONObject reply=message(stored,receipt.getString("replyMessageId"));assertTrue(reply.getJSONArray("replyToMessageIds").toString().contains(receipt.getString("messageId")));assertEquals("consumed",message(stored,receipt.getString("messageId")).getJSONObject("submission").getString("status"));
                f.controller.queryChatSubmission(receipt.getString("clientMessageId"));
            }
            await(()->!f.controller.getState().getChatSubmitting());assertFalse("Querying consumed receipts must not reactivate an ended Run",f.controller.getState().getChatBusy());
            f.controller.sendChat("NEW-ORDINARY-INPUT");await(()->f.bodies.size()==3&&!f.controller.getState().getChatBusy());String request=f.bodies.get(2).toString();assertTrue(request.contains("FIRST-OWNER-INPUT"));assertTrue(request.contains("QUEUED-OWNER-INPUT"));assertEquals(2,request.split("SAME_COMPLETE_ANSWER",-1).length-1);
        }
    }
    @Test public void cancelBeforeCompletedAnswerLeavesBothInputsUnconsumedAndNewTaskDoesNotReplayThem()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.queue();f.controller.cancelChat();f.idle();f.release.countDown();assertEquals("cancelled",f.app.tasks.get(original.getString("taskId"),false).getString("status"));assertEquals(1,f.bodies.size());
            for(JSONObject receipt:f.controller.getState().getChatReceipts().values())assertNotEquals("consumed",f.module.handle("getSubmission",new JSONObject().put("clientMessageId",receipt.getString("clientMessageId"))).getString("status"));
            assertEquals(2,f.conversation(original.getString("conversationId")).getJSONArray("messages").length());f.controller.sendChat("AFTER-EXPLICIT-CANCEL");await(()->f.bodies.size()==2&&!f.controller.getState().getChatBusy());String request=f.bodies.get(1).toString();assertFalse(request.contains("FIRST-OWNER-INPUT"));assertFalse(request.contains("QUEUED-OWNER-INPUT"));assertTrue(request.contains("AFTER-EXPLICIT-CANCEL"));assertEquals(2,f.app.tasks.list().length());
        }
    }
    @Test public void failedConversationCommitDoesNotClaimConsumptionOrStartQueuedModelRound()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.queue();Path blocked=new java.io.File(f.app.getFilesDir(),"private_conversations.json.new").toPath();Files.createDirectory(blocked);Path marker=blocked.resolve("unknown-existing-child");Files.writeString(marker,"preserve fixture child");
            try{f.release.countDown();f.idle();assertEquals("failed",f.app.tasks.get(original.getString("taskId"),false).getString("status"));assertEquals("A failed durable reply must stop before queued work",1,f.bodies.size());assertTrue(Files.exists(marker));
                for(JSONObject receipt:f.controller.getState().getChatReceipts().values())assertNotEquals("consumed",f.module.handle("getSubmission",new JSONObject().put("clientMessageId",receipt.getString("clientMessageId"))).getString("status"));
                assertEquals(2,f.conversation(original.getString("conversationId")).getJSONArray("messages").length());
            }finally{Files.deleteIfExists(marker);Files.deleteIfExists(blocked);}
        }
    }
    @Test public void consumedReceiptCannotRegressToLateQueueOrUnconfirmedAndRejectsOtherRun()throws Exception {
        JSONObject consumed=new JSONObject().put("clientMessageId","client").put("messageId","message").put("conversationId","conversation").put("taskId","task").put("runId","original").put("epoch",1).put("status","consumed").put("replyMessageId","reply").put("consumedAt",1L);
        assertEquals("consumed",ChatInputRules.INSTANCE.mergeReceipt(consumed,new JSONObject(consumed.toString()).put("status","queued")).getString("status"));
        assertEquals("consumed",ChatInputRules.INSTANCE.mergeReceipt(consumed,new JSONObject().put("clientMessageId","client").put("status","unconfirmed")).getString("status"));
        assertNull(ChatInputRules.INSTANCE.mergeReceipt(consumed,new JSONObject(consumed.toString()).put("runId","replacement")));
        JSONObject incomplete=new JSONObject(consumed.toString());incomplete.remove("replyMessageId");assertNull(ChatInputRules.INSTANCE.mergeReceipt(null,incomplete));
    }
}
