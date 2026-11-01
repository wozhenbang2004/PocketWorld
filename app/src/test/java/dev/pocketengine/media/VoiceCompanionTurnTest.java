package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic audio/port contracts. These tests never open a microphone, model endpoint or TTS device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class VoiceCompanionTurnTest {
    private ActivityController<Activity> owner;
    private VoiceCompanionTurn turn;
    private final ControlledAudio audio=new ControlledAudio();
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<String> input=new AtomicReference<>();
    @Before public void setup(){Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);owner=Robolectric.buildActivity(Activity.class).setup();turn=new VoiceCompanionTurn(()->{});}
    @After public void close(){turn.close();idle();owner.pause().stop().destroy();}
    private void install(String reply){turn.install(()->(text,control)->{control.check();input.set(text);calls.incrementAndGet();return reply;});}
    private void start()throws Exception{turn.start(owner.get(),audio);idle();}
    private JSONObject snapshot(){return turn.snapshot();}
    private String id(){return snapshot().optString("id");}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void await(BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<until){idle();Thread.sleep(2);}idle();assertTrue("Expected state; actual "+snapshot(),condition.getAsBoolean());}
    @Test public void oneFinalTextUsesOnePreparedRouteThenTtsAndDoesNotRelisten()throws Exception{
        install("真实模型回复fixture");start();assertEquals("listening",snapshot().getString("status"));assertEquals(0,calls.get());audio.recognition.complete("准确的最终一句");await(()->audio.speaks==1);assertEquals("准确的最终一句",input.get());assertEquals(1,calls.get());assertEquals("speaking",snapshot().getString("status"));audio.speech.complete(null);idle();assertEquals("complete",snapshot().getString("status"));assertFalse(turn.active());assertEquals(1,audio.listens);assertEquals(1,audio.releases);assertEquals("真实模型回复fixture",snapshot().getString("replyText"));assertFalse(snapshot().getBoolean("alwaysListening"));assertFalse(snapshot().getBoolean("automaticallyExecutesTasks"));
    }
    @Test public void localPreparationFailureOccursBeforeAudioAcquisition()throws Exception{
        turn.install(()->{throw new IllegalStateException("model not configured");});try{start();fail();}catch(IllegalStateException expected){}assertEquals(0,audio.acquires);assertFalse(turn.active());
    }
    @Test public void microphoneDenialNeverPreparesOrAcquires()throws Exception{
        AtomicInteger prepared=new AtomicInteger();turn.install(()->{prepared.incrementAndGet();return (text,control)->"none";});Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);try{start();fail();}catch(SecurityException expected){}assertEquals(0,prepared.get());assertEquals(0,audio.acquires);
    }
    @Test public void cancellationOfRecognitionDropsLateFinalAndNeverCallsModel()throws Exception{
        install("reply");start();turn.cancel(id());idle();audio.recognition.complete("迟到识别");idle();assertEquals(0,calls.get());assertEquals(0,audio.speaks);assertEquals("cancelled",snapshot().getString("status"));assertEquals(1,audio.releases);
    }
    @Test public void cancellationDuringModelSignalsSameControlAndDropsLateReply()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1),unblock=new CountDownLatch(1);turn.install(()->(text,control)->{calls.incrementAndGet();control.onCancel(cancelled::countDown);entered.countDown();while(unblock.getCount()>0)try{unblock.await();}catch(InterruptedException ignored){}return "late reply";});
        start();audio.recognition.complete("owner text");idle();assertTrue(entered.await(3,TimeUnit.SECONDS));turn.cancel(id());idle();assertTrue(cancelled.await(1,TimeUnit.SECONDS));unblock.countDown();await(()->!turn.active());Thread.sleep(10);idle();assertEquals(0,audio.speaks);assertEquals("cancelled",snapshot().getString("status"));assertEquals(1,calls.get());
    }
    @Test public void cancelledPendingAcquisitionStillCleansItsExactLateLease()throws Exception{
        install("reply");audio.acquire=new CompletableFuture<>();start();String original=id();turn.cancel(original);assertTrue(turn.active());assertEquals(0,audio.listens);audio.owner=original;audio.acquire.complete(original);idle();assertFalse(turn.active());assertEquals(0,audio.listens);assertEquals(original,audio.releasedOwner);assertEquals(1,audio.releases);
    }
    @Test public void failedAcquireDoesNotStopOrReleaseSomeoneElsesAudio()throws Exception{
        install("reply");audio.acquire=new CompletableFuture<>();audio.acquire.completeExceptionally(new IllegalStateException("other RTC owns audio"));start();assertEquals("failed",snapshot().getString("status"));assertFalse(turn.active());assertEquals(0,audio.stops);assertEquals(0,audio.releases);
    }
    @Test public void staleFinalAndStaleStopCannotChangeNewRound()throws Exception{
        install("reply");start();String old=id();CompletableFuture<String> oldFinal=audio.recognition;turn.cancel(old);idle();audio.recognition=new CompletableFuture<>();start();String current=id();assertNotEquals(old,current);oldFinal.complete("stale");idle();assertEquals(0,calls.get());try{turn.cancel(old);fail();}catch(IllegalStateException expected){}assertEquals(current,id());assertEquals("listening",snapshot().getString("status"));
    }
    @Test public void permissionLossBeforeFinalNeverDispatchesText()throws Exception{
        install("reply");start();Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);audio.recognition.complete("should not leave phone");idle();assertEquals(0,calls.get());assertEquals(0,audio.speaks);assertEquals("cancelled",snapshot().getString("status"));
    }
    @Test public void lifecycleCancellationWhileSpeakingStopsWithoutAutoResume()throws Exception{
        install("reply");start();audio.recognition.complete("owner");await(()->audio.speaks==1);turn.cancelForLifecycle("owner_backgrounded");idle();audio.speech.complete(null);idle();assertFalse(turn.active());assertEquals("cancelled",snapshot().getString("status"));assertEquals(1,audio.listens);assertEquals(1,audio.stops);
    }
    @Test public void audioOwnerLossBeforeFinalCancelsWithoutModel()throws Exception{
        install("reply");start();audio.owner="other";audio.recognition.complete("not current");idle();assertEquals(0,calls.get());assertEquals(0,audio.speaks);assertFalse(turn.active());
    }
    @Test public void recognitionTimeoutStopsWithoutAutomaticRestart()throws Exception{
        install("reply");start();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));idle();assertEquals("recognition_timeout",snapshot().getString("errorCode"));assertFalse(turn.active());assertEquals(1,audio.listens);assertEquals(0,calls.get());
    }
    @Test public void oversizedRecognitionIsNotTruncatedOrSent()throws Exception{
        install("reply");start();audio.recognition.complete("x".repeat(4001));idle();assertEquals("recognition_invalid",snapshot().getString("errorCode"));assertEquals(0,calls.get());assertEquals("",snapshot().getString("recognizedText"));
    }
    @Test public void oversizedModelReplyIsRejectedNotTruncatedForSpeech()throws Exception{
        String reply="x".repeat(android.speech.tts.TextToSpeech.getMaxSpeechInputLength()+1);install(reply);start();audio.recognition.complete("owner");await(()->!turn.active());assertEquals("",snapshot().getString("replyText"));assertEquals("reply_invalid",snapshot().getString("errorCode"));assertEquals(0,audio.speaks);
    }
    @Test public void releaseFailureStaysExplicitAndPreventsNewRound()throws Exception{
        install("reply");audio.stop=new CompletableFuture<>();audio.stop.completeExceptionally(new IllegalStateException("unknown"));start();turn.cancel(id());idle();assertTrue(snapshot().getBoolean("resourceReleaseUnknown"));assertFalse(audio.confirmedRelease);try{start();fail();}catch(IllegalStateException expected){}assertEquals(1,audio.acquires);
    }
    @Test public void synchronousStopFailureSettlesAndKeepsUnknownLeaseVisible()throws Exception{
        install("reply");start();audio.stopThrows=true;turn.cancel(id());idle();assertFalse(turn.active());assertTrue(turn.leaseHeld());assertTrue(snapshot().getBoolean("resourceReleaseUnknown"));assertEquals("audio_release_unknown",snapshot().getString("errorCode"));assertFalse(audio.confirmedRelease);assertThrows(IllegalStateException.class,this::start);audio.recognition.complete("迟到原话");idle();assertEquals(0,calls.get());assertEquals(1,audio.acquires);
    }
    @Test public void synchronousReleaseFailureDoesNotLeaveRoundStuckStopping()throws Exception{
        install("reply");start();audio.releaseThrows=true;turn.cancel(id());idle();assertFalse(turn.active());assertTrue(turn.leaseHeld());assertEquals("failed",snapshot().getString("status"));assertEquals("audio_release_unknown",snapshot().getString("errorCode"));assertEquals(1,audio.releases);assertThrows(IllegalStateException.class,this::start);
    }
    @Test public void stickyOwnerUncertaintyCannotBePromotedToConfirmedRelease()throws Exception{
        install("reply");start();audio.unknown=true;turn.cancel(id());idle();assertFalse(audio.confirmedRelease);assertTrue(snapshot().getBoolean("resourceReleaseUnknown"));assertTrue(turn.leaseHeld());assertFalse(turn.active());assertThrows(IllegalStateException.class,this::start);
    }
    @Test public void cleanupUncertaintyKeepsActualTextsAndDropsLatePlaybackCompletion()throws Exception{
        install("保留的回复");start();audio.recognition.complete("已识别的原话");await(()->audio.speaks==1);audio.unknownOnRelease=true;turn.cancel(id());idle();assertFalse(turn.active());assertTrue(snapshot().getBoolean("resourceReleaseUnknown"));assertTrue(turn.leaseHeld());assertEquals("已识别的原话",snapshot().getString("recognizedText"));assertEquals("保留的回复",snapshot().getString("replyText"));audio.speech.complete(null);idle();assertEquals("failed",snapshot().getString("status"));assertEquals(1,audio.listens);assertEquals(1,audio.speaks);assertEquals(1,calls.get());
    }
    @Test public void ttsFailureKeepsActualReplyAndDoesNotRelisten()throws Exception{
        install("keep reply");start();audio.recognition.complete("owner");await(()->audio.speaks==1);audio.speech.completeExceptionally(new MediaController.SpeechFailure("audio_focus_denied"));idle();assertEquals("keep reply",snapshot().getString("replyText"));assertEquals("audio_focus_denied",snapshot().getString("errorCode"));assertEquals(1,audio.listens);
    }
    @Test public void preparedRequestIsNotReplacedWhileRecognizing()throws Exception{
        AtomicReference<String> selected=new AtomicReference<>("route-at-start");turn.install(()->{String captured=selected.get();return (text,control)->captured;});start();selected.set("changed-route");audio.recognition.complete("owner");await(()->audio.speaks==1);assertEquals("route-at-start",snapshot().getString("replyText"));
    }
    @Test public void acquisitionCleanupUnknownIsNotReportedAsMereBusy()throws Exception{
        install("reply");audio.unknown=true;audio.acquire=new CompletableFuture<>();audio.acquire.completeExceptionally(new IllegalStateException("system cleanup unknown"));start();assertTrue(snapshot().getBoolean("resourceReleaseUnknown"));assertEquals("audio_release_unknown",snapshot().getString("errorCode"));assertEquals(0,audio.listens);assertEquals(0,audio.releases);
    }
    @Test public void modelTimeoutCancelsCurrentCallAndDoesNotRetryOrSpeak()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1);turn.install(()->(text,control)->{control.onCancel(cancelled::countDown);entered.countDown();while(!control.isCancelled())control.sleep(5);control.check();return "not spoken";});start();audio.recognition.complete("owner");idle();assertTrue(entered.await(3,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(91));idle();assertTrue(cancelled.await(1,TimeUnit.SECONDS));assertEquals("model_timeout",snapshot().getString("errorCode"));assertFalse(turn.active());assertEquals(0,audio.speaks);assertEquals(1,audio.listens);
    }
    @Test public void playbackTimeoutPreservesTextButDoesNotRestartMicrophone()throws Exception{
        install("preserved reply");start();audio.recognition.complete("owner");await(()->audio.speaks==1);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121));idle();assertEquals("speech_timeout",snapshot().getString("errorCode"));assertEquals("preserved reply",snapshot().getString("replyText"));assertFalse(turn.active());assertEquals(1,audio.listens);
    }
    private static final class ControlledAudio implements VoiceCompanionTurn.Audio {
        CompletableFuture<String> acquire;
        CompletableFuture<String> recognition=new CompletableFuture<>();
        CompletableFuture<Void> speech=new CompletableFuture<>(),stop=CompletableFuture.completedFuture(null);
        String owner,releasedOwner;int acquires,listens,speaks,stops,releases;boolean confirmedRelease,unknown,stopThrows,releaseThrows,unknownOnRelease;
        @Override public CompletableFuture<String> acquireRealtimeAudio(String owner){acquires++;if(acquire!=null)return acquire;this.owner=owner;return CompletableFuture.completedFuture(owner);}
        @Override public CompletableFuture<String> listenRealtimeTurn(String owner){assertEquals(this.owner,owner);listens++;return recognition;}
        @Override public CompletableFuture<Void> speakRealtimeTurn(String owner,String text){assertEquals(this.owner,owner);speaks++;return speech;}
        @Override public CompletableFuture<Void> stopRealtimeTurn(String owner){stops++;if(stopThrows)throw new IllegalStateException("fixture stop failed");return stop;}
        @Override public void releaseRealtimeAudio(String owner,boolean confirmed){releases++;releasedOwner=owner;confirmedRelease=confirmed;if(releaseThrows)throw new IllegalStateException("fixture release failed");if(unknownOnRelease)unknown=true;if(owner.equals(this.owner)&&confirmed&&!unknown)this.owner=null;}
        @Override public boolean ownsRealtimeAudio(String owner){return owner.equals(this.owner);}
        @Override public boolean audioReleaseUnknown(){return unknown;}
    }
}
