package dev.pocketengine.media.voice;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real GA adapter and playback queue; wire events are fixtures, not remote ASR or microphone quality. */
public final class OpenAiRealtimeTaskTranscriptTest {
    private static final class Fixture {
        final List<JSONObject> sent=new ArrayList<>(),captions=new ArrayList<>();final OpenAiRealtimeProtocol protocol;boolean ready;
        Fixture()throws Exception{protocol=new OpenAiRealtimeProtocol(new RealtimeVoiceProfile("wss://voice.example/realtime","voice-model","voice","chosen-transcription",1),new PcmPlaybackQueueTest.Fixture().queue,new OpenAiRealtimeProtocol.Output(){
            public void send(JSONObject event){sent.add(event);}public void ready(){ready=true;}public void changed(String state){}public void transcription(JSONObject event){captions.add(event);}
        });protocol.opened();}
        void configured()throws Exception{protocol.receive(new JSONObject().put("type","session.updated").put("session",sent.get(0).getJSONObject("session")).toString());}
        void commit(String item)throws Exception{protocol.receive(new JSONObject().put("type","input_audio_buffer.committed").put("item_id",item).toString());}
        void complete(String item,String text)throws Exception{protocol.receive(new JSONObject().put("type","conversation.item.input_audio_transcription.completed").put("item_id",item).put("content_index",0).put("transcript",text).toString());}
    }
    @Test public void selectedInputAsrMustBeConfirmedBeforeVoiceReady()throws Exception{
        Fixture f=new Fixture();assertEquals("chosen-transcription",f.sent.get(0).getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription").getString("model"));
        f.sent.get(0).getJSONObject("session").getJSONObject("audio").getJSONObject("input").remove("transcription");assertThrows(IllegalStateException.class,f::configured);assertFalse(f.ready);
    }
    @Test public void finalTextsAreBoundToCommittedLocalInputItemsDespiteCompletionOrder()throws Exception{
        Fixture f=new Fixture();f.configured();f.protocol.appendInput(new byte[960]);f.commit("input-a");f.commit("input-b");
        f.complete("input-b","第二项原话");f.complete("input-a","第一项原话");assertEquals(4,f.captions.size());
        assertEquals("input-b",f.captions.get(2).getString("segmentId"));assertEquals(2,f.captions.get(2).getLong("sequence"));assertEquals("第二项原话",f.captions.get(2).getString("text"));assertTrue(f.captions.get(2).getBoolean("isFinal"));
        assertEquals("input-a",f.captions.get(3).getString("segmentId"));assertEquals(1,f.captions.get(3).getLong("sequence"));
        f.complete("uncommitted","伪目标");f.complete("input-a","迟到改写");assertEquals(4,f.captions.size());
    }
    @Test public void receiveOnlyAndRemoteRepliesCannotProduceLocalTaskText()throws Exception{
        Fixture f=new Fixture();f.configured();f.commit("no-microphone");f.complete("no-microphone","不能当本机目标");
        f.protocol.receive("{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}");
        f.protocol.receive("{\"type\":\"response.output_audio_transcript.delta\",\"response_id\":\"r\",\"delta\":\"远端回复\"}");
        assertEquals("远端回复",f.protocol.transcript());assertTrue(f.captions.isEmpty());
    }
    @Test public void clearedOrFailedInputRemainsIncompleteAndLateFinalCannotReviveIt()throws Exception{
        Fixture f=new Fixture();f.configured();f.protocol.appendInput(new byte[960]);f.commit("old");f.protocol.clearInput();f.complete("old","迟到目标");
        assertEquals(2,f.captions.size());assertEquals("failed",f.captions.get(1).getString("phase"));assertFalse(f.captions.get(1).getBoolean("isFinal"));
        f.commit("after-clear-no-input");assertEquals(2,f.captions.size());f.protocol.appendInput(new byte[960]);f.commit("new");
        f.protocol.receive("{\"type\":\"conversation.item.input_audio_transcription.failed\",\"item_id\":\"new\",\"content_index\":0}");f.complete("new","迟到成功");assertEquals(4,f.captions.size());assertEquals("failed",f.captions.get(3).getString("phase"));
        f.protocol.close();f.complete("unknown","关闭后");assertEquals(4,f.captions.size());
    }
}
