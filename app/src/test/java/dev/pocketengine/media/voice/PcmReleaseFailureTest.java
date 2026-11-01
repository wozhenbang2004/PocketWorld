package dev.pocketengine.media.voice;

import org.junit.Test;
import static org.junit.Assert.*;

/** Releasing a sink is an external effect; losing its handle is not a success receipt. */
public class PcmReleaseFailureTest {
    private static final class Broken implements PcmPlaybackQueue.Sink {
        long head;int closeCalls;
        public int write(byte[] pcm,int offset,int length){return length;}
        public long playedFrames(){return head;}
        public void pause(){}
        public void close(){closeCalls++;throw new IllegalStateException("fixture release unconfirmed");}
    }
    private static PcmPlaybackQueue queue(Broken sink){return new PcmPlaybackQueue("session",()->sink,event->{});}
    @Test public void interruptFailureSurvivesSecondCleanupAndBlocksNewCaptureGeneration(){
        Broken sink=new Broken();PcmPlaybackQueue queue=queue(sink);queue.begin("turn");
        assertThrows(IllegalStateException.class,queue::interrupt);assertTrue(queue.resourceReleaseUnknown());
        assertThrows(IllegalStateException.class,()->queue.begin("next"));queue.close();
        assertTrue(queue.resourceReleaseUnknown());assertEquals(1,sink.closeCalls);
    }
    @Test public void drainedFailureSurvivesSecondCleanupAndBlocksNewCaptureGeneration(){
        Broken sink=new Broken();PcmPlaybackQueue queue=queue(sink);long generation=queue.begin("turn");queue.enqueue(generation,new byte[960]);queue.finish(generation);queue.pump(1);sink.head=480;
        assertThrows(IllegalStateException.class,()->queue.pump(2));assertTrue(queue.resourceReleaseUnknown());
        assertThrows(IllegalStateException.class,()->queue.begin("next"));queue.close();assertTrue(queue.resourceReleaseUnknown());assertEquals(1,sink.closeCalls);
    }
    @Test public void directCloseFailureCannotBecomeConfirmedOnRepeat(){
        Broken sink=new Broken();PcmPlaybackQueue queue=queue(sink);queue.begin("turn");
        assertThrows(IllegalStateException.class,queue::close);queue.close();assertTrue(queue.resourceReleaseUnknown());assertEquals(1,sink.closeCalls);
    }
    @Test public void confirmedCloseDoesNotCreateFalseUnknownState(){
        PcmPlaybackQueue queue=new PcmPlaybackQueue("session",()->new PcmPlaybackQueue.Sink(){public int write(byte[] x,int o,int n){return n;}public long playedFrames(){return 0;}public void pause(){}public void close(){}},event->{});
        queue.begin("turn");queue.interrupt();assertFalse(queue.resourceReleaseUnknown());queue.close();assertFalse(queue.resourceReleaseUnknown());
    }
}
