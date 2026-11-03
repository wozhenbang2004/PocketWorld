package dev.pocketengine.media.voice.tasks;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;

public final class OwnerTaskAnnouncementsTest {
    private static final String TASK="00000000-0000-0000-0000-000000000001",RUN="00000000-0000-0000-0000-000000000002",NEXT="00000000-0000-0000-0000-000000000003";
    private static JSONObject task(String id,String run,int epoch,String status)throws Exception{
        return new JSONObject().put("id",id).put("runId",run).put("coordinatorEpoch",epoch).put("kind","assistant")
                .put("actorId","owner").put("spaceId","private").put("status",status).put("goal","private goal api-key-fixture")
                .put("summary","private result and fixture secret");
    }
    private static final class Audio implements VoiceTaskReadout.Audio {
        int acquired,stopped,released;boolean holdAcquire,denyAcquire,unknown;String owner;
        final List<String> said=new ArrayList<>();
        CompletableFuture<String> acquisition;
        CompletableFuture<Void> speech;
        RunControl authority;
        Runnable duringAcquire;
        @Override public CompletableFuture<String> acquire(String id){
            acquired++;owner=id;acquisition=new CompletableFuture<>();
            if(duringAcquire!=null)duringAcquire.run();
            if(denyAcquire)acquisition.completeExceptionally(new IllegalStateException("busy"));else if(!holdAcquire)acquisition.complete(id);
            return acquisition;
        }
        @Override public CompletableFuture<Void> speak(String id,String text,RunControl original){
            assertEquals(owner,id);authority=original;
            try{original.check();said.add(text);speech=new CompletableFuture<>();return speech;}catch(Exception refused){return CompletableFuture.failedFuture(refused);}
        }
        @Override public CompletableFuture<Void> stop(String id){
            assertEquals(owner,id);stopped++;
            if(speech!=null&&!speech.isDone())speech.completeExceptionally(new IllegalStateException("stopped"));
            return CompletableFuture.completedFuture(null);
        }
        @Override public void release(String id,boolean confirmed){assertEquals(owner,id);released++;if(confirmed)owner=null;}
        @Override public boolean releaseUnknown(){return unknown;}
        void finish(){assertNotNull(speech);speech.complete(null);}
    }
    private static final class Fixture {
        final Map<String,JSONObject> current=new HashMap<>();final Audio audio=new Audio();boolean allowed=true;
        final OwnerTaskAnnouncements announcements=new OwnerTaskAnnouncements(current::get,id->allowed,audio,()->{});
        void event(JSONObject value)throws Exception{current.put(value.getString("id"),new JSONObject(value.toString()));announcements.observe(value);}
        void enable(){announcements.setForeground(true);announcements.setEnabled(true);}
        void started()throws Exception{event(task(TASK,RUN,1,"running"));}
    }
    @Test public void defaultOffAndFirstSeenTerminalStatesNeverStartSpeech()throws Exception{
        var f=new Fixture();f.announcements.setForeground(true);f.started();f.event(task(TASK,RUN,1,"completed"));assertEquals(0,f.audio.acquired);
        f.announcements.setEnabled(true);f.event(task(TASK,RUN,1,"completed"));assertEquals(0,f.audio.acquired);
        f.event(task(NEXT,RUN,1,"completed"));assertEquals(0,f.audio.acquired);
        assertFalse(f.announcements.snapshot().getBoolean("includesTaskBody"));assertFalse(f.announcements.snapshot().getBoolean("microphone"));
    }
    @Test public void aNewCurrentCompletionSpeaksOnlyTheStatusAndDeduplicates()throws Exception{
        var f=new Fixture();f.enable();f.started();f.event(task(TASK,RUN,1,"completed"));assertEquals(1,f.audio.said.size());
        String spoken=f.audio.said.get(0);assertTrue(spoken.contains("完成"));assertFalse(spoken.contains("private"));assertFalse(spoken.contains("secret"));
        f.audio.finish();f.event(task(TASK,RUN,1,"completed").put("summary","different private summary"));assertEquals(1,f.audio.acquired);
        assertEquals(1,f.audio.released);
    }
    @Test public void backgroundAndRecreatedOwnersDoNotReplayHistory()throws Exception{
        var f=new Fixture();f.enable();f.started();f.announcements.setForeground(false);f.event(task(TASK,RUN,1,"completed"));
        f.announcements.setForeground(true);f.event(task(TASK,RUN,1,"completed"));assertEquals(0,f.audio.acquired);
        var recreated=new OwnerTaskAnnouncements(f.current::get,id->true,f.audio,()->{});recreated.setForeground(true);recreated.setEnabled(true);recreated.observe(f.current.get(TASK));assertEquals(0,f.audio.acquired);recreated.close();
    }
    @Test public void backgroundDuringAcquisitionReleasesWithoutSpeakingOrCancellingTheTask()throws Exception{
        var f=new Fixture();f.audio.holdAcquire=true;f.enable();f.started();f.event(task(TASK,RUN,1,"completed"));
        f.announcements.setForeground(false);f.audio.acquisition.complete(f.audio.owner);
        assertTrue(f.audio.said.isEmpty());assertEquals(1,f.audio.released);assertEquals("completed",f.current.get(TASK).getString("status"));
    }
    @Test public void staleTaskBeforeAudioBeginsCannotAnnounceItsOldResult()throws Exception{
        var f=new Fixture();f.audio.holdAcquire=true;f.enable();f.started();f.event(task(TASK,RUN,1,"awaiting_user"));
        f.current.put(TASK,task(TASK,NEXT,2,"running"));f.audio.acquisition.complete(f.audio.owner);
        assertTrue(f.audio.said.isEmpty());assertEquals(1,f.audio.released);
    }
    @Test public void staleRunEventAndOldStopCannotInterruptTheSuccessorReadout()throws Exception{
        var f=new Fixture();f.enable();f.started();f.event(task(TASK,RUN,1,"completed"));String old=f.announcements.snapshot().getJSONObject("readout").getString("readoutId");f.audio.finish();
        f.event(task(TASK,NEXT,2,"running"));f.event(task(TASK,NEXT,2,"completed"));String current=f.announcements.snapshot().getJSONObject("readout").getString("readoutId");
        assertNotEquals(old,current);int stopped=f.audio.stopped;
        f.announcements.observe(task(TASK,RUN,1,"running"));f.announcements.observe(task(TASK,RUN,1,"running").put("spaceId","group"));
        assertEquals(stopped,f.audio.stopped);assertThrows(IllegalStateException.class,()->f.announcements.stop(old));
        assertTrue(f.announcements.snapshot().getJSONObject("readout").getBoolean("active"));
        f.announcements.stop(current);assertFalse(f.announcements.snapshot().getJSONObject("readout").getBoolean("active"));
    }
    @Test public void busyOrIneligibleAudioIsSkippedWithoutALaterReplay()throws Exception{
        var f=new Fixture();f.audio.denyAcquire=true;f.enable();f.started();f.event(task(TASK,RUN,1,"completed"));assertEquals(1,f.audio.acquired);
        f.audio.denyAcquire=false;f.event(task(TASK,RUN,1,"completed"));assertEquals(1,f.audio.acquired);assertTrue(f.audio.said.isEmpty());
        f.event(task(NEXT,RUN,1,"running"));f.allowed=false;f.event(task(NEXT,RUN,1,"awaiting_user"));f.allowed=true;f.event(task(NEXT,RUN,1,"awaiting_user"));assertEquals(1,f.audio.acquired);
    }
    @Test public void taskProgressInvalidatesOnlyItsOwnStatusSpeech()throws Exception{
        var f=new Fixture();f.enable();f.started();f.event(task(TASK,RUN,1,"awaiting_user"));assertEquals(1,f.audio.said.size());
        f.event(task(NEXT,RUN,1,"running"));assertEquals(0,f.audio.stopped);
        f.event(task(TASK,RUN,1,"running"));assertEquals(1,f.audio.stopped);assertEquals(1,f.audio.released);
    }
    @Test public void distinctOriginalQuestionsAnnounceOnceWithoutReadingQuestionContent()throws Exception{
        var f=new Fixture();f.enable();f.started();
        JSONObject question=new JSONObject().put("taskId",TASK).put("runId",RUN).put("coordinatorEpoch",1).put("requestId",NEXT).put("waitRevision",2).put("question","private question");
        JSONObject asking=task(TASK,RUN,1,"awaiting_user").put("evidence",new JSONObject().put("ownerQuestion",question));
        f.event(asking);f.audio.finish();f.event(asking);assertEquals(1,f.audio.said.size());
        f.event(task(TASK,RUN,1,"running"));question.put("waitRevision",4);f.event(asking);assertEquals(2,f.audio.said.size());
        for(String text:f.audio.said)assertFalse(text.contains("private"));
    }
    @Test public void externalScopesAndClosedOwnersCannotStartAnnouncements()throws Exception{
        var f=new Fixture();f.enable();f.event(task(TASK,RUN,1,"running").put("origin",new JSONObject().put("kind","channel")));
        f.event(task(TASK,RUN,1,"completed").put("origin",new JSONObject().put("kind","channel")));assertEquals(0,f.audio.acquired);
        f.started();f.announcements.close();f.event(task(TASK,RUN,1,"completed"));assertEquals(0,f.audio.acquired);
    }
    @Test public void separatePendingApprovalsAreDistinctEventsButTheirArgumentsAreNeverRead()throws Exception{
        var f=new Fixture();f.enable();f.started();
        JSONObject first=new JSONObject().put("id",TASK).put("runId",RUN).put("state","pending").put("arguments","private credentials");
        f.event(task(TASK,RUN,1,"waiting_approval").put("approvals",new JSONArray().put(first)));f.audio.finish();
        JSONObject second=new JSONObject(first.toString()).put("id",NEXT);
        f.event(task(TASK,RUN,1,"waiting_approval").put("approvals",new JSONArray().put(second)));assertEquals(2,f.audio.said.size());
        for(String text:f.audio.said)assertFalse(text.contains("private"));
    }
    @Test public void unknownAudioReleaseDoesNotPermitAnotherAnnouncement()throws Exception{
        var f=new Fixture();f.enable();f.started();f.event(task(TASK,RUN,1,"completed"));f.audio.unknown=true;f.audio.finish();
        assertTrue(f.announcements.snapshot().getJSONObject("readout").getBoolean("resourceReleaseUnknown"));
        f.event(task(NEXT,RUN,1,"running"));f.event(task(NEXT,RUN,1,"completed"));assertEquals(1,f.audio.acquired);
    }
    @Test public void leavingForegroundInsideAudioAcquisitionRevokesTheJustCreatedReadout()throws Exception{
        var f=new Fixture();f.enable();f.started();f.audio.duringAcquire=()->f.announcements.setForeground(false);
        f.event(task(TASK,RUN,1,"completed"));assertTrue(f.audio.said.isEmpty());assertEquals(1,f.audio.released);
        assertFalse(f.announcements.snapshot().getJSONObject("readout").getBoolean("active"));
    }
    @Test public void delayedLifecycleInvalidationNeverStopsAReplacementReadout()throws Exception{
        Audio audio=new Audio();VoiceTaskReadout readout=new VoiceTaskReadout(()->{});
        readout.start("first",()->{},audio);String first=readout.currentId();audio.finish();
        readout.start("second",()->{},audio);String second=readout.currentId();int stopped=audio.stopped;
        readout.invalidate(first);assertEquals(stopped,audio.stopped);assertEquals(second,readout.currentId());assertTrue(readout.active());
        readout.invalidate(second);assertFalse(readout.active());
    }
}
