package dev.pocketengine.features.assistant;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.conversation.InterruptedAttempts;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual App/Runner/Gateway/HTTP streaming and original SQL audit/source gate. Not physical UI proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=ReadyApplicationFixture.NoUnrelatedStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class ConversationInterruptedNativeJourneyTest {
    @org.junit.Before public void nativeKeyFixture(){java.security.Security.addProvider(new java.security.Provider("InterruptedFixtureKeys",1.0d,"Test-only AndroidKeyStore provider"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @org.junit.After public void removeNativeKeyFixture()throws Exception{try{ReadyApplicationFixture.closeHost((EngineApplication)RuntimeEnvironment.getApplication());}finally{java.security.Security.removeProvider("InterruptedFixtureKeys");}}
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static final String PREFIX="原调用已刷新到界面的片段-"+"visible-original-prefix ".repeat(12);
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final AssistantModule assistant;
        final HttpServer server;
        final CountDownLatch release=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();
        final CopyOnWriteArrayList<JSONObject> visible=new CopyOnWriteArrayList<>();final EngineApplication.Observer observer=event->{if("model_stream".equals(event.optString("type")))visible.add(event);};
        Fixture()throws Exception {
            ReadyApplicationFixture.initializeHost(app);assistant=(AssistantModule)app.features.module("assistant");server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            app.addObserver(observer);
            server.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();calls.incrementAndGet();
                String frame="data: "+new JSONObject().put("id","original-stream-fixture").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject().put("content",PREFIX)).put("finish_reason",JSONObject.NULL)))+"\n\n";
                byte[] bytes=frame.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length+128);exchange.getResponseBody().write(bytes);exchange.getResponseBody().flush();release.await(10,TimeUnit.SECONDS);
            }finally{exchange.close();}});server.start();
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","original-interrupted-fixture").put("supportsImageInput",false).put("sendScreenshots",false).put("maxSteps",6));
        }
        JSONObject cancelled()throws Exception {
            JSONObject ack=assistant.handle("send",new JSONObject().put("message","读取这个有界原会话fixture，不执行工具").put("clientMessageId",UUID.randomUUID().toString()));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);boolean shown=false;
            while(System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();shown=visible.stream().anyMatch(e->{JSONObject stream=e.optJSONObject("stream");return stream!=null&&stream.optString("text").contains("visible-original-prefix");});if(shown)break;Thread.sleep(10);}
            assertTrue("production native presentation emitted the real HTTP prefix; http="+calls.get()+", events="+visible+", task="+app.tasks.get(ack.getString("taskId"),false).optString("summary"),shown);assistant.handle("cancel",new JSONObject());release.countDown();
            JSONObject task=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();task=app.tasks.get(ack.getString("taskId"),false);if(!assistant.snapshot().optBoolean("running")&&!task.optString("status").matches("preparing|running|stopping"))break;Thread.sleep(10);}
            assertNotNull(task);assertNotEquals("completed",task.getString("status"));assertEquals(1,calls.get());return task;
        }
        public void close()throws Exception{release.countDown();app.removeObserver(observer);try{assistant.handle("cancel",new JSONObject());}finally{server.stop(0);ReadyApplicationFixture.closeHost(app);}}
    }
    @Test public void cancelledVisibleBodyRereadsInOriginalConversationWithoutNewAnswerOrRequest()throws Exception{try(Fixture f=new Fixture()){
        JSONObject task=f.cancelled();String id=task.getString("id"),conversation=task.getString("conversationId");int before=f.app.tasks.conversationRepository().window(conversation,"",80).getJSONArray("messages").length();
        JSONObject page=f.app.readConversationInterrupted(id,conversation,"");var attempts=InterruptedAttempts.fromEvents(InterruptedAttempts.eventsFromAuditPage(page),page.optBoolean("hasMore"));
        assertEquals(1,attempts.size());assertEquals(PREFIX,attempts.get(0).text);assertEquals(task.getString("runId"),attempts.get(0).runId);assertEquals(task.getInt("coordinatorEpoch"),attempts.get(0).epoch);assertTrue(attempts.get(0).contiguous);assertFalse(page.getBoolean("assistantAnswer"));
        f.app.readConversationInterrupted(id,conversation,"");assertEquals(1,f.calls.get());assertEquals(before,f.app.tasks.conversationRepository().window(conversation,"",80).getJSONArray("messages").length());
        assertThrows(SecurityException.class,()->f.app.readConversationInterrupted(id,UUID.randomUUID().toString(),""));
        f.app.tasks.conversationRepository().transaction(()->{f.app.tasks.conversationRepository().deleteConversation(conversation,"user_conversation_delete");return null;});assertThrows(Exception.class,()->f.app.readConversationInterrupted(id,conversation,""));assertEquals(1,f.calls.get());
    }}
    @Test public void canonicalForgetBlocksDerivedPrefixButPreservesOriginalAuditWithoutReplay()throws Exception{try(Fixture f=new Fixture()){
        JSONObject task=f.cancelled();String id=task.getString("id"),conversation=task.getString("conversationId");assertTrue(InterruptedAttempts.eventsFromAuditPage(f.app.readConversationInterrupted(id,conversation,"")).length()>0);
        JSONObject ref=f.app.tasks.scheduleProposalUserRefs(id,task.getString("runId"),task.getInt("coordinatorEpoch"),conversation).getJSONObject(0),scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        MemoryModule memory=(MemoryModule)f.app.features.module("memory");JSONObject record=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("layer","episodic").put("text","主人明确选中的原会话fixture").put("source","主人明确保存").put("sourceRefs",new JSONArray().put(ref)))).getJSONObject("record");
        ((MemorySourceBridge)field(f.app,"memorySources")).forgetReviewed(scope,record.getString("id"),record.getLong("revision"));
        assertThrows(Exception.class,()->f.app.readConversationInterrupted(id,conversation,""));JSONArray audit=f.app.tasks.get(id,false).getJSONArray("events");boolean retained=false;for(int i=0;i<audit.length();i++)if("model_attempt_interrupted".equals(audit.getJSONObject(i).optString("type")))retained|=PREFIX.equals(audit.getJSONObject(i).getJSONObject("receipt").optString("text"));assertTrue(retained);assertEquals(1,f.calls.get());assertNotNull(f.app.tasks.get(id,false));
    }}
}
