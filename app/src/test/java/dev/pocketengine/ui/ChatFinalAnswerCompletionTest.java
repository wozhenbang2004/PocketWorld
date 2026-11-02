package dev.pocketengine.ui;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
public final class ChatFinalAnswerCompletionTest {
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
        final java.util.concurrent.Semaphore release=new java.util.concurrent.Semaphore(0);
        final HttpServer server;
        final PocketWorldController controller;
        Fixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                int n=bodies.size();bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                if(!release.tryAcquire(15,TimeUnit.SECONDS))throw new AssertionError("fixture gate timed out");
                byte[] reply=("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""+(n<2?"SAME_COMPLETE_ANSWER":"NEW_COMPLETE_ANSWER")+"\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
            }catch(Exception failure){if(!moduleRunningQuietly())return;throw new IllegalStateException(failure);}finally{exchange.close();}});
            server.start();app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","fixture").putString("provider","openai_chat").putInt("maxSteps",1).commit();
            controller=new PocketWorldController(new ComposeActivity(),app);controller.attach();await(()->controller.getState().feature("assistant").optBoolean("sendAcknowledgements")&&controller.getComposerDraft()!=null);
        }
        private boolean moduleRunningQuietly(){try{return module.snapshot().getBoolean("running");}catch(Exception ignored){return false;}}
        void send(String text){String key=controller.getComposerDraft().key;int epoch=controller.getComposerViewEpoch();controller.editComposerText(key,epoch,text);controller.sendChat(text,key,epoch);}
        JSONObject start()throws Exception {
            send("FIRST-OWNER-INPUT");await(()->bodies.size()==1&&!controller.getState().getChatSubmitting()&&controller.getState().getChatActiveTurn()!=null);
            return new JSONObject(controller.getState().getChatActiveTurn().toString());
        }
        void input(String mode,String text,int receipts)throws Exception {
            controller.setChatSendMode(mode,controller.getComposerDraft().key,controller.getComposerViewEpoch());
            send(text);await(()->!controller.getState().getChatSubmitting()&&controller.getState().getChatReceipts().size()==receipts);
        }
        JSONObject task(JSONObject original)throws Exception{return app.tasks.get(original.getString("taskId"),false);}
        void idle()throws Exception {await(()->!module.snapshot().getBoolean("running")&&!controller.getState().getChatBusy());}
        JSONObject conversation(String id)throws Exception {return module.handle("get",new JSONObject().put("conversationId",id));}
        public void close(){release.release(20);controller.detach();module.close();server.stop(0);}
    }

    @Test public void currentFinalAnswerClosesTheOrdinaryNativeTask()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.start();f.release.release();f.idle();JSONObject task=f.task(original);
            assertEquals("completed",task.getString("status"));
            JSONObject proof=task.getJSONObject("evidence").getJSONObject("nativeFinalAnswer");
            assertEquals(original.getString("runId"),proof.getString("runId"));assertEquals(original.getInt("epoch"),proof.getInt("coordinatorEpoch"));
            assertEquals(1,f.bodies.size());assertEquals(1,f.app.tasks.list().length());
        }
    }
    @Test public void discardedSteerResponsesAtModelCeilingCannotCompleteTask()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.start();f.input("steer","REPLACE-FIRST-DECISION",2);f.release.release();await(()->f.bodies.size()==2);
            f.input("steer","REPLACE-LAST-DECISION",3);f.release.release();f.idle();JSONObject task=f.task(original);
            assertEquals("needs_confirmation",task.getString("status"));assertFalse(task.getJSONObject("evidence").has("nativeFinalAnswer"));
            for(int i=0;i<task.getJSONArray("operations").length();i++)assertEquals(dev.pocketengine.features.memory.TaskMemoryJournal.TOOL,task.getJSONArray("operations").getJSONObject(i).getString("tool"));assertEquals(2,f.bodies.size());assertEquals(1,f.app.tasks.list().length());
        }
    }
    @Test public void earlierQueuedAnswerIsNotProofForDiscardedLastTurn()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.start();f.input("queue","NEXT-OWNER-TURN",2);f.release.release();await(()->f.bodies.size()==2);
            f.input("steer","REPLACE-LAST-DECISION",3);f.release.release();f.idle();JSONObject task=f.task(original);
            assertEquals("needs_confirmation",task.getString("status"));
            assertEquals("SAME_COMPLETE_ANSWER",task.getJSONObject("evidence").getString("modelSummary"));
            assertFalse(task.getJSONObject("evidence").has("nativeFinalAnswer"));assertEquals(2,f.bodies.size());assertEquals(1,f.app.tasks.list().length());
        }
    }
}
