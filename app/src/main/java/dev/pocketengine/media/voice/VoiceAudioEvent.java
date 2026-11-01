package dev.pocketengine.media.voice;

/** Native-only audio evidence. PCM is a copy of an interval the renderer already consumed. */
public final class VoiceAudioEvent {
    public enum Kind { STARTED, PCM, PROGRESS, PAUSED, INTERRUPTED, ENDED, ERROR, CLOCK_UNAVAILABLE }
    public enum Source { RECEIVED, PLAYBACK }
    public enum ClockKind { AUDIO_TRACK_HEAD, UNAVAILABLE }
    public final String sessionId,turnId;
    public final long generation,sequence,startFrame,playedFramePosition,playbackMonotonicNanos;
    public final int sampleRateHz,channels;
    public final String encoding="PCM16LE";
    public final Kind kind;
    public final Source source;
    public final ClockKind clockKind;
    private final byte[] samples;
    public VoiceAudioEvent(String sessionId,String turnId,long generation,long sequence,Kind kind,Source source,
            int sampleRateHz,int channels,long startFrame,long playedFramePosition,long playbackMonotonicNanos,ClockKind clockKind,byte[] pcm16le){
        this.sessionId=sessionId;this.turnId=turnId;this.generation=generation;this.sequence=sequence;this.kind=kind;this.source=source;
        this.sampleRateHz=sampleRateHz;this.channels=channels;this.startFrame=startFrame;this.playedFramePosition=playedFramePosition;
        this.playbackMonotonicNanos=playbackMonotonicNanos;this.clockKind=clockKind;this.samples=pcm16le==null?new byte[0]:pcm16le.clone();
    }
    public byte[] pcm16le(){return samples.clone();}
}
