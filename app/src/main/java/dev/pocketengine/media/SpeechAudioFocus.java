package dev.pocketengine.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/** Speech owns transient OS audio focus; losing it stops speech rather than secretly resuming later. */
public final class SpeechAudioFocus implements AutoCloseable {
    public static final AudioAttributes ATTRIBUTES=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AudioManager audio;
    private final AudioFocusRequest request;
    private final Runnable lost;
    private boolean held,closed;
    public SpeechAudioFocus(Context context,Runnable lost){this.audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.lost=lost;request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTRIBUTES).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(this::changed,new Handler(Looper.getMainLooper())).build();}
    public boolean acquire(){if(closed||audio==null)return false;if(held)return true;held=audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;return held;}
    public boolean isHeld(){return held;}
    public void release(){boolean owned=held;held=false;if(owned&&audio!=null)audio.abandonAudioFocusRequest(request);}
    private void changed(int change){if(held&&(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)){release();lost.run();}}
    @Override public void close(){closed=true;release();}
}
