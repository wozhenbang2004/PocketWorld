package dev.pocketengine.media;

import android.app.Application;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class SpeechAudioFocusTest {
    private AudioManager audio(){return (AudioManager)RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);}
    @Test public void deniedAudioFocusCannotBeReportedAsOwned(){Shadows.shadowOf(audio()).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);try(SpeechAudioFocus focus=new SpeechAudioFocus(RuntimeEnvironment.getApplication(),()->fail("Denied focus never belonged to speech"))){assertFalse(focus.acquire());assertFalse(focus.isHeld());}}
    @Test public void transientLossStopsSpeechOnceAndNeverAutomaticallyResumes()throws Exception{
        Shadows.shadowOf(audio()).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);AtomicInteger stopped=new AtomicInteger();try(SpeechAudioFocus focus=new SpeechAudioFocus(RuntimeEnvironment.getApplication(),stopped::incrementAndGet)){assertTrue(focus.acquire());Field field=SpeechAudioFocus.class.getDeclaredField("request");field.setAccessible(true);AudioFocusRequest request=(AudioFocusRequest)field.get(focus);AudioManager.OnAudioFocusChangeListener listener=(AudioManager.OnAudioFocusChangeListener)AudioFocusRequest.class.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request);listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);assertFalse(focus.isHeld());assertEquals(1,stopped.get());listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);assertFalse(focus.isHeld());assertEquals(1,stopped.get());}
    }
    @Test public void closedSpeechCannotReacquireFocus(){Shadows.shadowOf(audio()).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);SpeechAudioFocus focus=new SpeechAudioFocus(RuntimeEnvironment.getApplication(),()->{});assertTrue(focus.acquire());focus.close();assertFalse(focus.isHeld());assertFalse(focus.acquire());}
}
