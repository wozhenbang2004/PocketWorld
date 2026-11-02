package dev.pocketengine.features.channels;

import android.app.Application;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Actual Gateway SSE and QQ HTTP; only admission/account peers are synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class QqC2CStreamJourneyTest {
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("QqStreamFixtureVault","1","Synthetic owner-keystore"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("QqStreamFixtureVault");}
    private static void accept(QqOwnerImageJourneyTest.Fixture f,JSONObject event)throws Exception {var method=ChannelsModule.class.getDeclaredMethod("handleAccepted",JSONObject.class,JSONObject.class);method.setAccessible(true);try{method.invoke(f.module,f.core.profile("bot"),new JSONObject().put("duplicate",false).put("event",event).put("route",event.getJSONObject("route")));}catch(java.lang.reflect.InvocationTargetException failure){throw(Exception)failure.getCause();}}
    private static final class Transport {
        final CopyOnWriteArrayList<JSONObject> frames=new CopyOnWriteArrayList<>();final AtomicInteger modelCalls=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();final java.util.concurrent.CountDownLatch firstFrameSeen=new java.util.concurrent.CountDownLatch(1);volatile boolean laterDeltaWritten,firstFrameBeforeLaterDelta;Runnable afterFirstAck;
        Transport(QqOwnerImageJourneyTest.Fixture f,boolean dropAck,boolean revoke)throws Exception {
            f.server.createContext("/v1/chat/completions",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertTrue(request.getBoolean("stream"));assertFalse(request.has("tools"));modelCalls.incrementAndGet();String[] parts={"Actual ","streamed ","answer"};java.util.ArrayList<byte[]> chunks=new java.util.ArrayList<>();int length=0;for(String part:parts){JSONObject chunk=new JSONObject().put("id","actual-model-stream").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject().put("content",part)).put("finish_reason",JSONObject.NULL)));byte[] bytes=("data: "+chunk+"\n\n").getBytes(StandardCharsets.UTF_8);chunks.add(bytes);length+=bytes.length;}JSONObject finish=new JSONObject().put("id","actual-model-stream").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","stop")));byte[] terminal=("data: "+finish+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);length+=terminal.length;exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,length);var output=exchange.getResponseBody();for(int n=0;n<chunks.size();n++){output.write(chunks.get(n));output.flush();if(n==0)assertTrue("Original first QQ frame must arrive before later provider deltas are released",firstFrameSeen.await(3,java.util.concurrent.TimeUnit.SECONDS));if(n==0&&afterFirstAck!=null){long until=System.nanoTime()+3_000_000_000L;while(true){JSONArray rows=f.module.snapshot().getJSONArray("outbox");if(rows.length()>0&&rows.getJSONObject(0).getJSONObject("nativeQqStream").optInt("lastAckIndex",-1)==0)break;if(System.nanoTime()>until)throw new AssertionError("first frame not acknowledged");Thread.sleep(5);}afterFirstAck.run();}if(n<chunks.size()-1){Thread.sleep(550);laterDeltaWritten=true;}}output.write(terminal);output.flush();}catch(java.io.IOException expectedWhenOriginalCancelled){}catch(Throwable problem){failure.compareAndSet(null,problem);}finally{exchange.close();}});
            f.server.createContext("/v2/users/owner/stream_messages",exchange->{try{JSONObject frame=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));frames.add(frame);if(frames.size()==1){firstFrameBeforeLaterDelta=!laterDeltaWritten;firstFrameSeen.countDown();}assertEquals("QQBot fixture-qq-token",exchange.getRequestHeaders().getFirst("Authorization"));if(revoke)f.module.handle("owner.unbind",new JSONObject().put("profileId","bot"));if(!dropAck){byte[] response="{\"id\":\"same-provider-stream\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}}catch(java.io.IOException expectedAfterOwnerRevoked){if(!revoke)failure.compareAndSet(null,expectedAfterOwnerRevoked);}catch(Throwable problem){failure.compareAndSet(null,problem);}finally{exchange.close();}});
        }
        void check(){if(failure.get()!=null)throw new AssertionError(failure.get());}
    }
    private static JSONObject start(QqOwnerImageJourneyTest.Fixture f)throws Exception {f.bind();f.module.handle("profile.save",f.core.profile("bot").put("autoReply",true).put("qqStreamRepliesEnabled",true));JSONObject event=f.receive("actual-c2c-stream","owner","Only this original question",null);accept(f,event);return event;}

    @Test public void actualProviderDeltasReplaceOneOriginalQqMessageAndOnlyVerifiedFinalReturnSendsDone()throws Exception {
        try(var f=new QqOwnerImageJourneyTest.Fixture()){
            Transport transport=new Transport(f,false,false);JSONObject event=start(f);f.await(event,"answered");transport.check();assertEquals(1,transport.modelCalls.get());assertTrue(transport.firstFrameBeforeLaterDelta);assertTrue(transport.frames.size()>=2);int sequence=transport.frames.get(0).getInt("msg_seq");
            for(int n=0;n<transport.frames.size();n++){JSONObject frame=transport.frames.get(n);assertEquals(n,frame.getInt("index"));assertEquals(sequence,frame.getInt("msg_seq"));assertEquals("replace",frame.getString("input_mode"));assertEquals(event.getString("messageId"),frame.getString("msg_id"));assertEquals(n==transport.frames.size()-1?10:1,frame.getInt("input_state"));if(n>0)assertEquals("same-provider-stream",frame.getString("stream_msg_id"));}
            assertEquals("Actual streamed answer",transport.frames.get(transport.frames.size()-1).getString("content_raw"));JSONObject out=f.module.snapshot().getJSONArray("outbox").getJSONObject(0);assertEquals("sent",out.getString("status"));assertTrue(out.getJSONObject("nativeQqStream").getBoolean("complete"));assertTrue(out.getJSONObject("nativeQqStream").getBoolean("providerDeltasObserved"));assertEquals(0,f.sent.get());assertThrows(IllegalStateException.class,()->f.module.handle("outbox.send",new JSONObject().put("id",out.getString("id"))));
        }
    }

    @Test public void lostFrameAckOrRevocationStopsSameStreamWithoutSecondPostDoneOrOrdinaryMessageFallback()throws Exception {
        for(boolean revoke:new boolean[]{false,true})try(var f=new QqOwnerImageJourneyTest.Fixture()){
            Transport transport=new Transport(f,!revoke,revoke);JSONObject event=start(f);f.await(event,revoke?"cancelled":"failed");transport.check();assertEquals(1,transport.frames.size());assertEquals(1,transport.frames.get(0).getInt("input_state"));assertEquals(0,f.sent.get());JSONObject out=f.module.snapshot().getJSONArray("outbox").getJSONObject(0);assertEquals("unknown",out.getString("status"));assertFalse(out.getJSONObject("nativeQqStream").getBoolean("complete"));assertTrue(out.getJSONObject("nativeQqStream").getBoolean("partialMayBeVisible"));assertThrows(IllegalStateException.class,()->f.module.handle("outbox.send",new JSONObject().put("id",out.getString("id"))));assertEquals(1,transport.modelCalls.get());
            ChannelCore cold=new ChannelCore(new java.io.File(f.android.getFilesDir(),"channels"));assertEquals("unknown",cold.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));assertThrows(IllegalStateException.class,()->cold.prepareSend(out.getString("id")));assertEquals(1,transport.frames.size());
        }
    }

    @Test public void disabledStreamingPreservesOriginalNormalReplyAndNeverCallsC2cStreamEndpoint()throws Exception {
        try(var f=new QqOwnerImageJourneyTest.Fixture()){
            f.bind();f.module.handle("profile.save",f.core.profile("bot").put("autoReply",true));AtomicInteger stream=new AtomicInteger();f.server.createContext("/v2/users/owner/stream_messages",exchange->{stream.incrementAndGet();exchange.close();});JSONObject event=f.receive("normal-original","owner","Normal old reply",null);accept(f,event);f.await(event,"answered");long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(f.sent.get()==0&&System.nanoTime()<until)Thread.sleep(5);assertEquals(1,f.sent.get());assertEquals(0,stream.get());assertEquals(1,f.modelCalls.get());assertFalse(f.module.snapshot().getJSONArray("outbox").getJSONObject(0).has("nativeQqStream"));
        }
    }
    @Test public void forgottenMemoryAfterFirstAcceptedFrameStopsLaterFramesAndPreservesReceipt()throws Exception {
        try(var f=new QqOwnerImageJourneyTest.Fixture()){
            var memory=new dev.pocketengine.features.memory.MemoryModule(f.context);f.module.setMemory(memory);JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
            JSONObject record=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text","stream remembered fact"))).getJSONObject("record");
            memory.handle("setCore",new JSONObject().put("scope",scope).put("id",record.getString("id")).put("revision",record.getLong("revision")).put("core",true));
            Transport transport=new Transport(f,false,false);transport.afterFirstAck=()->{try{memory.handle("forget",new JSONObject().put("scope",scope).put("id",record.getString("id")));}catch(Exception failure){throw new RuntimeException(failure);}};
            JSONObject event=start(f);f.await(event,"failed");transport.check();assertEquals(1,transport.frames.size());assertEquals(1,transport.frames.get(0).getInt("input_state"));assertEquals(0,f.sent.get());
            JSONObject stream=f.module.snapshot().getJSONArray("outbox").getJSONObject(0).getJSONObject("nativeQqStream");assertEquals("same-provider-stream",stream.getString("streamMessageId"));assertEquals(0,stream.getInt("lastAckIndex"));assertFalse(stream.getBoolean("complete"));assertTrue(stream.getBoolean("partialMayBeVisible"));
        }
    }

}
