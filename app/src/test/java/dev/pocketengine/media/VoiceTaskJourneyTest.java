package dev.pocketengine.media;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.voice.tasks.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native-host/audio protocol fixtures only. These do not certify App/Runner or device execution. */
public final class VoiceTaskJourneyTest {
    private static final class Host implements VoiceTaskHostPort {
        TaskState current;Listener listener;int starts,cancels,unsubscribes;boolean startUnknown;Command cancelled;
        Host(String task){current=new TaskState(task,"",0,1,"awaiting_user","labelled original-record fixture");}
        public TaskState inspectOriginal(Reference reference,Authority authority)throws Exception{authority.check();return current;}
        public TaskState startOriginal(Command command,Authority authority)throws Exception{
            authority.check();starts++;if(startUnknown)throw new IllegalStateException("fixture lost admission acknowledgement");
            current=new TaskState(command.reference.taskId,UUID.randomUUID().toString(),1,2,"preparing","labelled host admission fixture");return current;
        }
        public TaskState cancelExactRun(Command command,Authority authority)throws Exception{
            authority.check();if(!command.expectedRunId.equals(current.runId)||command.expectedEpoch!=current.epoch)throw new SecurityException("fixture run changed");
            cancelled=command;cancels++;current=new TaskState(current.taskId,current.runId,current.epoch,current.revision+1,"stopping","labelled cancellation receipt");return current;
        }
        public Subscription observeOriginal(Reference reference,Authority authority,Listener listener){this.listener=listener;return ()->unsubscribes++;}
    }
    private static final class Audio implements VoiceTaskReadout.Audio {
        final CompletableFuture<String> acquisition=new CompletableFuture<>();final CompletableFuture<Void> speaking=new CompletableFuture<>();
        boolean delayed,stopFails,unknown,releasedConfirmed;int acquisitions,speaks,stops,releases;String owner;
        public CompletableFuture<String> acquire(String owner){this.owner=owner;acquisitions++;if(!delayed)acquisition.complete(owner);return acquisition;}
        public CompletableFuture<Void> speak(String owner,String text,RunControl authority){speaks++;try{authority.check();}catch(Exception failure){CompletableFuture<Void> rejected=new CompletableFuture<>();rejected.completeExceptionally(failure);return rejected;}return speaking;}
        public CompletableFuture<Void> stop(String owner){stops++;CompletableFuture<Void> result=new CompletableFuture<>();if(stopFails)result.completeExceptionally(new IllegalStateException("fixture stop unknown"));else result.complete(null);return result;}
        public void release(String owner,boolean confirmed){releases++;releasedConfirmed=confirmed;}
        public boolean releaseUnknown(){return unknown;}
    }
    private static RealtimeReviewedTurn reviewed(){return new RealtimeReviewedTurn(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"labelled reviewed voice goal","segment-fixture","owner-reported","track-fixture","text_stream");}
    private static VoiceTaskJourney attach(RealtimeReviewedTurn reviewed,Host host)throws Exception{VoiceTaskJourney journey=new VoiceTaskJourney(()->{});journey.install(host);journey.attachSaved(reviewed,()->{});return journey;}
    private static String binding(VoiceTaskJourney j)throws Exception{return j.snapshot().getString("bindingId");}
    private static String version(VoiceTaskJourney j)throws Exception{return j.snapshot().getString("viewToken");}
    private static String cancelVersion(VoiceTaskJourney j)throws Exception{return j.snapshot().getString("cancelToken");}

    @Test public void confirmedVoiceStartsOnlyOriginalTaskAndStopReadingDoesNotCancelIt()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);Audio audio=new Audio();
        try{
            assertEquals(turn.text,journey.snapshot().getString("goal"));journey.startOriginal(binding(journey),version(journey));assertEquals(1,host.starts);assertEquals(turn.commitId,journey.snapshot().getJSONObject("task").getString("taskId"));
            journey.readResult(binding(journey),version(journey),audio);assertEquals(1,audio.speaks);journey.stopReading(journey.snapshot().getJSONObject("readout").getString("readoutId"));
            assertEquals(1,audio.stops);assertEquals(1,audio.releases);assertTrue(audio.releasedConfirmed);assertEquals(0,host.cancels);assertEquals("preparing",host.current.status);
            audio.speaking.complete(null);assertEquals(1,audio.releases);
        }finally{journey.close();}
    }
    @Test public void unknownAdmissionNeverAutomaticallyStartsAgainEvenIfReadStillShowsWaiting()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);host.startUnknown=true;VoiceTaskJourney journey=attach(turn,host);
        try{journey.startOriginal(binding(journey),version(journey));assertEquals("start_unconfirmed",journey.snapshot().getString("phase"));journey.inspect(binding(journey));assertFalse(journey.snapshot().getBoolean("canStart"));assertThrows(IllegalStateException.class,()->journey.startOriginal(binding(journey),version(journey)));assertEquals(1,host.starts);}
        finally{journey.close();}
    }
    @Test public void oldVisibleRunCannotCancelReplacementAndOldEventsCannotOverwriteIt()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);
        try{
            journey.startOriginal(binding(journey),version(journey));String oldVersion=cancelVersion(journey);VoiceTaskHostPort.TaskState old=host.current;
            host.current=new VoiceTaskHostPort.TaskState(turn.commitId,UUID.randomUUID().toString(),2,3,"running","labelled replacement run");host.listener.changed(host.current);assertFalse(journey.snapshot().getBoolean("needsRefresh"));
            journey.inspect(binding(journey));String newRun=journey.snapshot().getJSONObject("task").getString("runId");assertThrows(IllegalStateException.class,()->journey.cancelExactRun(binding(journey),oldVersion));assertEquals(0,host.cancels);
            host.listener.changed(new VoiceTaskHostPort.TaskState(turn.commitId,old.runId,1,99,"completed","late old-run fixture"));assertEquals(newRun,journey.snapshot().getJSONObject("task").getString("runId"));
            journey.cancelExactRun(binding(journey),cancelVersion(journey));assertEquals(newRun,host.cancelled.expectedRunId);assertEquals(2,host.cancelled.expectedEpoch);assertEquals("stopping",journey.snapshot().getJSONObject("task").getString("status"));
        }finally{journey.close();}
    }
    @Test public void sameRunProgressKeepsUrgentCancellationBoundToThatRun()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);
        try{journey.startOriginal(binding(journey),version(journey));String expected=cancelVersion(journey);VoiceTaskHostPort.TaskState original=host.current;
            host.current=new VoiceTaskHostPort.TaskState(turn.commitId,original.runId,original.epoch,original.revision+1,"running","new progress, same run");host.listener.changed(host.current);
            assertEquals(expected,cancelVersion(journey));journey.cancelExactRun(binding(journey),expected);assertEquals(1,host.cancels);assertEquals(original.runId,host.cancelled.expectedRunId);
        }finally{journey.close();}
    }
    @Test public void oldObserverCannotAttachResultsToNewVoiceTask()throws Exception{
        RealtimeReviewedTurn old=reviewed(),next=reviewed();Host host=new Host(old.commitId);VoiceTaskJourney journey=attach(old,host);VoiceTaskHostPort.Listener stale=host.listener;
        try{host.current=new VoiceTaskHostPort.TaskState(next.commitId,"",0,1,"awaiting_user","new voice task fixture");journey.attachSaved(next,()->{});stale.changed(new VoiceTaskHostPort.TaskState(old.commitId,UUID.randomUUID().toString(),1,10,"completed","late other-session result"));assertEquals(next.commitId,journey.snapshot().getJSONObject("task").getString("taskId"));assertTrue(host.unsubscribes>=1);assertEquals(0,host.cancels);}
        finally{journey.close();}
    }
    @Test public void canonicalReadCannotEraseASeenHigherRun()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);
        try{journey.startOriginal(binding(journey),version(journey));VoiceTaskHostPort.TaskState old=host.current;host.listener.changed(new VoiceTaskHostPort.TaskState(turn.commitId,UUID.randomUUID().toString(),2,3,"running","higher run fixture"));host.current=old;journey.inspect(binding(journey));assertTrue(journey.snapshot().getBoolean("needsRefresh"));assertFalse(journey.snapshot().getBoolean("busy"));}
        finally{journey.close();}
    }
    @Test public void synchronousOwnerDetachDuringAdmissionAnnouncementCannotDispatch()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);AtomicReference<VoiceTaskJourney> ref=new AtomicReference<>();AtomicBoolean detach=new AtomicBoolean();
        VoiceTaskJourney journey=new VoiceTaskJourney(()->{try{if(detach.get()&&ref.get().snapshot().optString("phase").equals("starting")&&detach.compareAndSet(true,false))ref.get().detach();}catch(Exception failure){throw new AssertionError(failure);}});ref.set(journey);journey.install(host);journey.attachSaved(turn,()->{});
        try{String binding=binding(journey),view=version(journey);detach.set(true);assertThrows(IllegalStateException.class,()->journey.startOriginal(binding,view));assertEquals(0,host.starts);assertEquals("",journey.snapshot().getString("bindingId"));}
        finally{journey.close();}
    }
    @Test public void stopBeforeAudioAcquisitionSettlesLateLeaseWithoutSpeaking()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);Audio audio=new Audio();audio.delayed=true;
        try{journey.readResult(binding(journey),version(journey),audio);String readout=journey.snapshot().getJSONObject("readout").getString("readoutId");journey.stopReading(readout);assertEquals(0,audio.stops);audio.acquisition.complete(audio.owner);assertEquals(0,audio.speaks);assertEquals(1,audio.stops);assertEquals(1,audio.releases);assertTrue(audio.releasedConfirmed);assertEquals(0,host.cancels);}
        finally{journey.close();}
    }
    @Test public void resultChangeFencesDelayedReadoutWithoutCancellingNewTaskRun()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);Audio audio=new Audio();audio.delayed=true;
        try{journey.startOriginal(binding(journey),version(journey));journey.readResult(binding(journey),version(journey),audio);host.current=new VoiceTaskHostPort.TaskState(turn.commitId,host.current.runId,1,3,"completed","updated original result fixture");host.listener.changed(host.current);audio.acquisition.complete(audio.owner);assertEquals(0,audio.speaks);assertEquals(1,audio.releases);assertEquals(0,host.cancels);assertEquals("completed",journey.snapshot().getJSONObject("task").getString("status"));}
        finally{journey.close();}
    }
    @Test public void uncertainAudioCleanupStaysBlockedAndNeverClaimsPlayedClock()throws Exception{
        RealtimeReviewedTurn turn=reviewed();Host host=new Host(turn.commitId);VoiceTaskJourney journey=attach(turn,host);Audio audio=new Audio();audio.stopFails=true;
        try{journey.readResult(binding(journey),version(journey),audio);journey.stopReading(journey.snapshot().getJSONObject("readout").getString("readoutId"));JSONObject state=journey.snapshot().getJSONObject("readout");assertTrue(state.getBoolean("resourceReleaseUnknown"));assertTrue(state.getBoolean("leaseHeld"));assertFalse(state.getBoolean("playbackClockAvailable"));assertFalse(audio.releasedConfirmed);assertThrows(IllegalStateException.class,()->journey.readResult(binding(journey),version(journey),new Audio()));assertEquals(0,host.cancels);}
        finally{journey.close();}
    }
}
