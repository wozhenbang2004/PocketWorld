package dev.pocketengine.media.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;

/** Actual PCM devices, scoped to one foreground audio lease. No output is inferred from wall time. */
final class AndroidVoiceAudio implements AutoCloseable {
    static final AudioAttributes ATTRIBUTES=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final Context context;
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private AudioRecord recorder;
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;
    private boolean focused,closed;
    private int previousMode;
    private final byte[] capture=new byte[960];
    AndroidVoiceAudio(Context context,Runnable focusLost){
        this.context=context;this.manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(ATTRIBUTES)
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(value->{if(value==AudioManager.AUDIOFOCUS_LOSS||value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)focusLost.run();},new Handler(Looper.getMainLooper())).build();
    }
    void acquire(){
        if(closed||manager==null||manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("暂时无法取得语音通话音频焦点");
        focused=true;previousMode=manager.getMode();manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    }
    void microphone(boolean enabled){
        if(!enabled){stopCapture();return;}
        if(recorder!=null)return;
        if(closed||!focused||context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("麦克风权限或音频归属已失效");
        int minimum=AudioRecord.getMinBufferSize(24_000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IllegalStateException("设备不支持此实时语音采样格式");
        AudioRecord created=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION).setPrivacySensitive(true)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(minimum*2,9600)).build();
        recorder=created;
        try{
            if(created.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("麦克风未能初始化");
            if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(created.getAudioSessionId());if(echo!=null)echo.setEnabled(true);}
            if(NoiseSuppressor.isAvailable()){noise=NoiseSuppressor.create(created.getAudioSessionId());if(noise!=null)noise.setEnabled(true);}
            created.startRecording();
            if(created.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("麦克风未开始采集");
        }catch(RuntimeException failure){stopCapture();throw failure;}
    }
    byte[] read(){
        if(recorder==null)return null;
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("麦克风权限已撤销");
        int size=recorder.read(capture,0,capture.length,AudioRecord.READ_NON_BLOCKING);
        if(size<0||size%2!=0)throw new IllegalStateException("麦克风采集已失效");
        return size==0?null:Arrays.copyOf(capture,size);
    }
    PcmPlaybackQueue.Sink output(){
        if(closed||!focused)throw new IllegalStateException("音频播放归属已失效");
        int minimum=AudioTrack.getMinBufferSize(24_000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IllegalStateException("设备不支持此实时播放格式");
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum,4800)).build();
        try{if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("音频播放未初始化");track.play();}
        catch(RuntimeException failure){track.release();throw failure;}
        return new PcmPlaybackQueue.Sink(){
            private boolean released;
            private long lastRaw,wrap;
            @Override public int write(byte[] pcm,int offset,int length){if(released)return AudioTrack.ERROR_INVALID_OPERATION;return track.write(pcm,offset,length,AudioTrack.WRITE_NON_BLOCKING);}
            @Override public long playedFrames(){
                if(released)return -1;
                long raw=Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                if(raw<lastRaw&&lastRaw-raw>0x80000000L)wrap+=0x100000000L;
                lastRaw=raw;return wrap+raw;
            }
            @Override public void pause(){if(!released)track.pause();}
            @Override public void close(){if(released)return;released=true;try{track.pause();track.flush();}finally{track.release();}}
        };
    }
    boolean microphoneEnabled(){return recorder!=null;}
    boolean echoCancellationEnabled(){try{return echo!=null&&echo.getEnabled();}catch(RuntimeException unavailable){return false;}}
    String routedInput(){try{AudioDeviceInfo device=recorder==null?null:recorder.getRoutedDevice();return device==null?"system_default":Integer.toString(device.getType());}catch(RuntimeException unavailable){return "unavailable";}}
    private void stopCapture(){
        RuntimeException failure=null;AudioRecord owned=recorder;recorder=null;
        if(owned!=null){try{owned.stop();}catch(RuntimeException error){failure=error;}finally{try{owned.release();}catch(RuntimeException error){failure=error;}}}
        if(echo!=null){try{echo.release();}catch(RuntimeException error){failure=error;}finally{echo=null;}}
        if(noise!=null){try{noise.release();}catch(RuntimeException error){failure=error;}finally{noise=null;}}
        Arrays.fill(capture,(byte)0);if(failure!=null)throw failure;
    }
    @Override public void close(){
        if(closed)return;closed=true;
        try{stopCapture();}finally{if(focused&&manager!=null){focused=false;try{if(manager.getMode()==AudioManager.MODE_IN_COMMUNICATION)manager.setMode(previousMode);}finally{manager.abandonAudioFocusRequest(focus);}}}
    }
}
