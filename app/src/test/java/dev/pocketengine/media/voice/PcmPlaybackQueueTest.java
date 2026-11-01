package dev.pocketengine.media.voice;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PcmPlaybackQueueTest {
    static final class Device implements PcmPlaybackQueue.Sink {
        long head;int bytes;boolean paused,closed;int maxWrite=960;
        @Override public int write(byte[] pcm,int offset,int length){int count=Math.min(maxWrite,length);bytes+=count;return count;}
        @Override public long playedFrames(){return head;}
        @Override public void pause(){paused=true;}
        @Override public void close(){closed=true;}
    }
    static final class Fixture {
        final List<VoiceAudioEvent> events=new ArrayList<>();final List<Device> devices=new ArrayList<>();
        final PcmPlaybackQueue queue=new PcmPlaybackQueue("s",()->{Device d=new Device();devices.add(d);return d;},events::add);
        Device device(){return devices.get(devices.size()-1);}
    }
    @Test public void receivedAndWrittenBytesAreNotPlayed(){
        Fixture f=new Fixture();long g=f.queue.begin("t");assertEquals(1,g);f.queue.enqueue(g,new byte[1920]);f.queue.pump(100);
        assertEquals(960,f.device().bytes);assertEquals(0,f.queue.playedFrames());assertTrue(f.events.isEmpty());
    }
    @Test public void pcmContainsOnlyRendererConsumedIntervalAndIsImmutable(){
        Fixture f=new Fixture();long g=f.queue.begin("t");byte[] pcm=new byte[1920];for(int i=0;i<pcm.length;i++)pcm[i]=(byte)i;
        f.queue.enqueue(g,pcm);pcm[0]=99;f.queue.pump(100);f.device().head=120;f.queue.pump(200);
        VoiceAudioEvent audio=f.events.stream().filter(e->e.kind==VoiceAudioEvent.Kind.PCM).findFirst().orElseThrow();
        assertEquals(0,audio.startFrame);assertEquals(120,audio.playedFramePosition);assertEquals(240,audio.pcm16le().length);assertEquals(0,audio.pcm16le()[0]);
        byte[] copy=audio.pcm16le();copy[0]=7;assertEquals(0,audio.pcm16le()[0]);assertEquals(200,audio.playbackMonotonicNanos);
        assertEquals(VoiceAudioEvent.ClockKind.AUDIO_TRACK_HEAD,audio.clockKind);assertEquals(VoiceAudioEvent.Kind.STARTED,f.events.get(0).kind);
    }
    @Test public void interruptionPausesThenDiscardsAndRejectsOldGeneration(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.queue.enqueue(g,new byte[1920]);f.queue.pump(100);f.device().head=120;
        assertEquals(120,f.queue.interrupt());assertTrue(f.device().paused);assertTrue(f.device().closed);assertEquals(0,f.queue.queuedFrames());
        assertFalse(f.queue.enqueue(g,new byte[100]));long next=f.queue.begin("b");assertTrue(next>g);assertEquals(0,f.queue.playedFrames());
        assertEquals(VoiceAudioEvent.Kind.INTERRUPTED,f.events.get(f.events.size()-1).kind);
    }
    @Test public void networkCompletionWaitsForActualPlaybackDrain(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.queue.enqueue(g,new byte[960]);f.queue.finish(g);f.queue.pump(1);
        assertTrue(f.queue.active());assertTrue(f.events.isEmpty());f.device().head=480;f.queue.pump(2);
        assertFalse(f.queue.active());assertEquals(VoiceAudioEvent.Kind.ENDED,f.events.get(f.events.size()-1).kind);
    }
    @Test public void missingRendererClockDoesNotEmitPcmAndCannotPretendHeard(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.device().head=-1;f.queue.enqueue(g,new byte[960]);f.queue.pump(1);
        assertFalse(f.queue.clockAvailable());assertEquals(VoiceAudioEvent.Kind.CLOCK_UNAVAILABLE,f.events.get(0).kind);assertEquals(-1,f.queue.interrupt());
        assertTrue(f.events.stream().noneMatch(e->e.kind==VoiceAudioEvent.Kind.PCM));
    }
    @Test public void frameClockCannotMoveBackward(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.queue.enqueue(g,new byte[1920]);f.queue.pump(1);f.device().head=100;f.queue.pump(2);f.device().head=99;
        assertThrows(IllegalStateException.class,()->f.queue.pump(3));
    }
    @Test public void rendererCannotConfirmUnwrittenFrames(){
        Fixture f=new Fixture();f.queue.begin("a");f.device().head=1;assertThrows(IllegalStateException.class,()->f.queue.pump(1));
    }
    @Test public void boundedQueueNeverDropsAudioAndContinuesSilently(){
        Fixture f=new Fixture();long g=f.queue.begin("a");for(int i=0;i<8;i++)f.queue.enqueue(g,new byte[48_000]);
        assertThrows(IllegalStateException.class,()->f.queue.enqueue(g,new byte[2]));assertEquals(192_000,f.queue.queuedFrames());
    }
    @Test public void nonblockingBackpressurePreservesUnwrittenBytes(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.queue.enqueue(g,new byte[960]);f.device().maxWrite=0;f.queue.pump(1);assertEquals(0,f.device().bytes);
        f.device().maxWrite=240;f.queue.pump(2);assertEquals(240,f.device().bytes);assertEquals(0,f.queue.playedFrames());
    }
    @Test public void sequenceNeverResetsAcrossTurns(){
        Fixture f=new Fixture();for(int i=0;i<2;i++){long g=f.queue.begin("t"+i);f.queue.enqueue(g,new byte[960]);f.queue.pump(1);f.device().head=100;f.queue.pump(2);f.queue.interrupt();}
        long sequence=0;for(VoiceAudioEvent e:f.events){assertTrue(e.sequence>sequence);sequence=e.sequence;}
    }
    @Test public void invalidPcmAndChunkLengthsAreRejected(){
        Fixture f=new Fixture();long g=f.queue.begin("a");assertThrows(IllegalArgumentException.class,()->f.queue.enqueue(g,new byte[3]));
        assertThrows(IllegalArgumentException.class,()->f.queue.enqueue(g,new byte[48002]));assertEquals(0,f.device().bytes);
    }
    @Test public void closeCannotBeFollowedByAnotherTurn(){Fixture f=new Fixture();f.queue.begin("a");f.queue.close();assertThrows(IllegalStateException.class,()->f.queue.begin("b"));}
    @Test public void latePresenterBindsWithObservedHeadAndOriginalObservationTime(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.queue.enqueue(g,new byte[960]);f.queue.pump(100);f.device().head=120;f.queue.pump(200);f.events.clear();
        f.queue.announceCurrent();assertEquals(1,f.events.size());VoiceAudioEvent e=f.events.get(0);assertEquals(VoiceAudioEvent.Kind.STARTED,e.kind);assertEquals(120,e.playedFramePosition);assertEquals(200,e.playbackMonotonicNanos);
    }
    @Test public void invalidClockDuringInterruptStillClosesSinkAndPublishesError(){
        Fixture f=new Fixture();long g=f.queue.begin("a");f.queue.enqueue(g,new byte[960]);f.queue.pump(100);f.device().head=120;f.queue.pump(200);f.device().head=119;
        assertThrows(IllegalStateException.class,f.queue::interrupt);assertTrue(f.device().closed);assertEquals(VoiceAudioEvent.Kind.ERROR,f.events.get(f.events.size()-1).kind);
    }
}
