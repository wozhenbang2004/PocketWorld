package dev.pocketengine.media.voice;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/** Bounded streaming playback. Writing or receiving bytes never advances the played clock. */
public final class PcmPlaybackQueue implements AutoCloseable {
    public static final int SAMPLE_RATE=24_000,CHANNELS=1,MAX_QUEUED_FRAMES=SAMPLE_RATE*8,MAX_CHUNK_BYTES=48_000;
    public interface Sink extends AutoCloseable {
        /** Nonblocking write; a result of zero means try on a later audio tick. */
        int write(byte[] pcm,int offset,int length);
        /** Actual renderer consumption since this sink was opened, or -1 if unavailable. */
        long playedFrames();
        void pause();
        @Override void close();
    }
    public interface SinkFactory { Sink open(); }
    private static final class Chunk {
        final byte[] pcm;final long start;
        Chunk(byte[] pcm,long start){this.pcm=pcm;this.start=start;}
        long end(){return start+pcm.length/2;}
    }
    private final String sessionId;
    private final SinkFactory factory;
    private final VoiceAudioListener listener;
    private final ArrayDeque<Chunk> chunks=new ArrayDeque<>();
    private Sink sink;
    private String turnId="";
    private long generation,sequence,received,written,played,lastObservedNanos;
    private boolean started,done,closed,clockUnavailable,releaseUnknown;
    public PcmPlaybackQueue(String sessionId,SinkFactory factory,VoiceAudioListener listener){
        this.sessionId=Objects.requireNonNull(sessionId);this.factory=Objects.requireNonNull(factory);this.listener=Objects.requireNonNull(listener);
    }
    public synchronized long begin(String turn){
        if(closed||releaseUnknown||turn==null||turn.isBlank())throw new IllegalStateException("播放会话已关闭、释放未确认或轮次无效");
        if(sink!=null)interrupt();
        generation++;turnId=turn;received=written=played=0;started=done=clockUnavailable=false;chunks.clear();
        sink=Objects.requireNonNull(factory.open());return generation;
    }
    public synchronized boolean enqueue(long expectedGeneration,byte[] bytes){
        if(closed||sink==null||done||expectedGeneration!=generation)return false;
        if(bytes==null||bytes.length==0||bytes.length%2!=0||bytes.length>MAX_CHUNK_BYTES)throw new IllegalArgumentException("实时音频帧格式或长度无效");
        if(received-played+bytes.length/2>MAX_QUEUED_FRAMES)throw new IllegalStateException("实时语音播放缓冲已满，未丢字续播");
        byte[] owned=bytes.clone();chunks.addLast(new Chunk(owned,received));received+=owned.length/2;return true;
    }
    public synchronized void finish(long expectedGeneration){if(sink!=null&&expectedGeneration==generation)done=true;}
    /** Owner schedules this around every 10ms. No clock is extrapolated between ticks. */
    public synchronized void pump(long monotonicNanos){
        if(closed||sink==null)return;
        observe(monotonicNanos);
        int budget=960; // At most 20ms per write tick; never block interruption behind a device write.
        for(Chunk chunk:chunks){
            if(written>=chunk.end())continue;
            int offset=(int)(written-chunk.start)*2;
            int size=Math.min(chunk.pcm.length-offset,budget);
            int accepted=sink.write(chunk.pcm,offset,size);
            if(accepted<0||accepted>size||accepted%2!=0)throw new IllegalStateException("音频输出写入失败");
            written+=accepted/2;budget-=accepted;
            if(accepted<size||budget==0)break;
        }
        if(done&&played==received&&written==received){
            emit(VoiceAudioEvent.Kind.ENDED,played,played,monotonicNanos,null);
            Sink owned=sink;sink=null;chunks.clear();closeSink(owned);
        }
    }
    private void observe(long nanos){
        long head=sink.playedFrames();
        if(head<0){
            if(!clockUnavailable){clockUnavailable=true;emit(VoiceAudioEvent.Kind.CLOCK_UNAVAILABLE,-1,-1,-1,null);}
            return;
        }
        if(head<played||head>written)throw new IllegalStateException("音频播放位置失效，停止本轮");
        clockUnavailable=false;
        lastObservedNanos=nanos;
        if(head==played)return;
        if(!started){started=true;emit(VoiceAudioEvent.Kind.STARTED,0,0,nanos,null);}
        long before=played;
        for(Chunk chunk:chunks){
            long from=Math.max(before,chunk.start),to=Math.min(head,chunk.end());
            if(to<=from)continue;
            emit(VoiceAudioEvent.Kind.PCM,from,to,nanos,Arrays.copyOfRange(chunk.pcm,(int)(from-chunk.start)*2,(int)(to-chunk.start)*2));
        }
        played=head;
        while(!chunks.isEmpty()&&chunks.peekFirst().end()<=played)chunks.removeFirst();
        emit(VoiceAudioEvent.Kind.PROGRESS,played,played,nanos,null);
    }
    /** Pauses before sampling, then discards queued audio. The return value is renderer evidence. */
    public synchronized long interrupt(){
        if(sink==null){if(generation>0)generation++;return played;}
        Sink owned=sink;
        try{
            owned.pause();observe(System.nanoTime());
            emit(VoiceAudioEvent.Kind.INTERRUPTED,played,clockUnavailable?-1:played,clockUnavailable?-1:System.nanoTime(),null);
        }catch(RuntimeException failure){clockUnavailable=true;emit(VoiceAudioEvent.Kind.ERROR,-1,-1,-1,null);throw failure;}
        finally{sink=null;chunks.clear();generation++;closeSink(owned);}
        return clockUnavailable?-1:played;
    }
    private void emit(VoiceAudioEvent.Kind kind,long from,long to,long nanos,byte[] pcm){
        VoiceAudioEvent.ClockKind clock=to<0?VoiceAudioEvent.ClockKind.UNAVAILABLE:VoiceAudioEvent.ClockKind.AUDIO_TRACK_HEAD;
        VoiceAudioEvent event=new VoiceAudioEvent(sessionId,turnId,generation,++sequence,kind,VoiceAudioEvent.Source.PLAYBACK,SAMPLE_RATE,CHANNELS,from,to,nanos,clock,pcm);
        try{listener.onAudioEvent(event);}catch(RuntimeException ignored){/* Presentation cannot retain audio ownership. */}
    }
    public synchronized long generation(){return generation;}
    public synchronized long playedFrames(){return played;}
    public synchronized long queuedFrames(){return sink==null?0:received-played;}
    public synchronized boolean active(){return sink!=null;}
    public synchronized boolean clockAvailable(){return sink!=null&&!clockUnavailable;}
    /** Sticky for this owner: losing the device reference cannot turn a failed release into success. */
    public synchronized boolean resourceReleaseUnknown(){return releaseUnknown;}
    private void closeSink(Sink owned){try{owned.close();}catch(RuntimeException failure){releaseUnknown=true;throw failure;}}
    /** A late native presenter can bind the current generation using previously observed evidence. */
    public synchronized void announceCurrent(){if(sink!=null&&started&&!clockUnavailable)emit(VoiceAudioEvent.Kind.STARTED,played,played,lastObservedNanos,null);}
    @Override public synchronized void close(){if(closed)return;try{interrupt();}finally{closed=true;}}
}
