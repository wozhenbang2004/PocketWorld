package dev.pocketengine.media.voice;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenAiRealtimeProtocolTest {
    static final class Fixture {
        final PcmPlaybackQueueTest.Fixture audio=new PcmPlaybackQueueTest.Fixture();
        final List<JSONObject> sent=new ArrayList<>();boolean ready;String status="";
        final OpenAiRealtimeProtocol protocol;
        Fixture()throws Exception{
            protocol=new OpenAiRealtimeProtocol(new RealtimeVoiceProfile("wss://voice.example/realtime","chosen-model","chosen-voice",1),audio.queue,new OpenAiRealtimeProtocol.Output(){
                public void send(JSONObject event){sent.add(event);}public void ready(){ready=true;}public void changed(String value){status=value;}
            });protocol.opened();
        }
        void configure()throws Exception{protocol.receive(new JSONObject().put("type","session.updated").put("session",sent.get(0).getJSONObject("session")).toString());}
        void created(String id)throws Exception{protocol.receive(new JSONObject().put("type","response.created").put("response",new JSONObject().put("id",id)).toString());}
        JSONObject delta(String id)throws Exception{return new JSONObject().put("type","response.output_audio.delta").put("response_id",id).put("item_id","item-"+id).put("content_index",0).put("output_index",0).put("delta",Base64.getEncoder().encodeToString(new byte[1920]));}
        void delta()throws Exception{protocol.receive(delta("r1").toString());}
        void done(String id,String status)throws Exception{protocol.receive(new JSONObject().put("type","response.done").put("response",new JSONObject().put("id",id).put("status",status).put("output",new JSONArray())).toString());}
        JSONObject last(String type){return sent.stream().filter(e->e.optString("type").equals(type)).reduce((a,b)->b).orElseThrow();}
    }
    @Test public void waitsForEffectiveSessionBeforeAcceptingMicrophone()throws Exception{
        Fixture f=new Fixture();f.protocol.appendInput(new byte[960]);assertFalse(f.ready);assertEquals(1,f.sent.size());f.configure();f.protocol.appendInput(new byte[960]);
        assertTrue(f.ready);assertEquals("input_audio_buffer.append",f.sent.get(1).getString("type"));assertEquals(0,f.sent.get(0).getJSONObject("session").getJSONArray("tools").length());
    }
    @Test public void manualInterruptUsesPlayedFramesNotReceivedOrWrittenFrames()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.audio.queue.pump(1);f.audio.device().head=120;
        f.protocol.interrupt(false);assertTrue(f.audio.device().paused);assertTrue(f.audio.device().closed);
        assertEquals("r1",f.last("response.cancel").getString("response_id"));assertEquals(5,f.last("conversation.item.truncate").getInt("audio_end_ms"));
        assertTrue(f.protocol.interruptionPending());assertEquals(0,f.audio.queue.queuedFrames());
    }
    @Test public void serverVadStopsLocalAudioWithoutSendingDuplicateCancellation()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.audio.queue.pump(1);f.audio.device().head=120;
        f.protocol.receive("{\"type\":\"input_audio_buffer.speech_started\"}");
        assertTrue(f.audio.device().paused);assertTrue(f.sent.stream().noneMatch(e->e.optString("type").equals("response.cancel")));
        assertEquals(5,f.last("conversation.item.truncate").getInt("audio_end_ms"));
    }
    @Test public void bothCancelAndExactTruncationNeedAcknowledgment()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.audio.queue.pump(1);f.audio.device().head=120;f.protocol.interrupt(false);
        f.done("r1","cancelled");assertTrue(f.protocol.interruptionPending());
        f.protocol.receive("{\"type\":\"conversation.item.truncated\",\"item_id\":\"item-r1\",\"content_index\":0,\"audio_end_ms\":6}");assertTrue(f.protocol.interruptionPending());
        f.protocol.receive("{\"type\":\"conversation.item.truncated\",\"item_id\":\"item-r1\",\"content_index\":0,\"audio_end_ms\":5}");assertFalse(f.protocol.interruptionPending());
        f.created("r2");assertEquals("thinking",f.status);
    }
    @Test public void staleAudioAndDuplicateSocketEventsCannotRestartInterruptedTurn()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.protocol.interrupt(false);f.delta();assertFalse(f.audio.queue.active());
        f.done("r1","cancelled");f.protocol.receive("{\"type\":\"conversation.item.truncated\",\"item_id\":\"item-r1\",\"content_index\":0,\"audio_end_ms\":0}");f.created("r1");assertFalse(f.audio.queue.active());
    }
    @Test public void duplicateDeltaEventIdDoesNotDuplicatePcm()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");String event=f.delta("r1").put("event_id","e1").toString();f.protocol.receive(event);f.protocol.receive(event);assertEquals(960,f.audio.queue.queuedFrames());
    }
    @Test public void generationFinishedDoesNotMeanSpeakerFinished()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.done("r1","completed");assertTrue(f.audio.queue.active());assertEquals("draining",f.status);
        f.audio.queue.pump(1);f.audio.queue.pump(2);f.audio.device().head=960;f.audio.queue.pump(3);assertFalse(f.audio.queue.active());
    }
    @Test public void cannotMixNextResponseBeforePlaybackOrTruncationSettles()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.done("r1","completed");assertThrows(IllegalStateException.class,()->f.created("r2"));
        f.protocol.interrupt(false);assertThrows(IllegalStateException.class,()->f.created("r2"));
    }
    @Test public void interruptThinkingCancelsWithoutInventingPlaybackPosition()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.protocol.interrupt(false);assertEquals("r1",f.last("response.cancel").getString("response_id"));
        assertTrue(f.sent.stream().noneMatch(e->e.optString("type").equals("conversation.item.truncate")));assertTrue(f.audio.events.isEmpty());
    }
    @Test public void unknownPlaybackClockEndsRatherThanGuessingTruncation()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.delta();f.audio.device().head=-1;f.audio.queue.pump(1);
        assertThrows(IllegalStateException.class,()->f.protocol.interrupt(false));assertTrue(f.audio.device().closed);
        assertTrue(f.sent.stream().noneMatch(e->e.optString("type").equals("conversation.item.truncate")));
    }
    @Test public void unacknowledgedInterruptionHasBoundedLifetime()throws Exception{
        Fixture f=new Fixture();f.configure();f.created("r1");f.protocol.interrupt(false);assertThrows(IllegalStateException.class,()->f.protocol.checkDeadline(System.nanoTime()+6_000_000_000L));
    }
    @Test public void wrongFormatOrToolConfigurationCannotAcquireMicrophone()throws Exception{
        Fixture f=new Fixture();JSONObject session=f.sent.get(0).getJSONObject("session");session.getJSONObject("audio").getJSONObject("output").getJSONObject("format").put("rate",48_000);
        assertThrows(IllegalStateException.class,()->f.configure());assertFalse(f.ready);
        Fixture tools=new Fixture();tools.sent.get(0).getJSONObject("session").getJSONArray("tools").put(new JSONObject().put("type","function"));
        assertThrows(IllegalStateException.class,()->tools.configure());assertFalse(tools.ready);
    }
    @Test public void toolCallsAndMalformedAudioHaveNoExecutionFallback()throws Exception{
        Fixture f=new Fixture();f.configure();assertThrows(IllegalStateException.class,()->f.protocol.receive("{\"type\":\"response.function_call_arguments.done\"}"));
        f.created("r1");assertThrows(IllegalArgumentException.class,()->f.protocol.receive(f.delta("r1").put("delta","not-base64!").toString()));
    }
    @Test public void serviceErrorAndCloseNeverBecomeSuccess()throws Exception{
        Fixture f=new Fixture();f.configure();assertThrows(IllegalStateException.class,()->f.protocol.receive("{\"type\":\"error\",\"error\":{\"message\":\"secret detail\"}}"));
        int sent=f.sent.size();f.protocol.close();f.protocol.appendInput(new byte[960]);f.protocol.receive(f.delta("r1").toString());assertEquals(sent,f.sent.size());assertFalse(f.protocol.configured());
    }
    @Test public void endpointNeverAcceptsCredentialsOrUnreviewedQuery()throws Exception{
        assertThrows(Exception.class,()->new RealtimeVoiceProfile("wss://token@voice.example/rt","m","v",1));
        assertThrows(Exception.class,()->new RealtimeVoiceProfile("wss://voice.example/rt?key=secret","m","v",1));
        assertThrows(Exception.class,()->new RealtimeVoiceProfile("ws://voice.example/rt","m","v",1));
    }
}
