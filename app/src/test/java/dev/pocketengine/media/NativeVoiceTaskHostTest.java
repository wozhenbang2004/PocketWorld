package dev.pocketengine.media;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.voice.tasks.NativeVoiceTaskHost;
import dev.pocketengine.media.voice.tasks.VoiceTaskHostPort;
import dev.pocketengine.media.voice.tasks.VoiceTaskJourney;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SQLite/task source fences and native host projection. Actions are explicit admission fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class NativeVoiceTaskHostTest {
    private TaskStore tasks;private NativeVoiceTaskHost host;private RealtimeReviewedTurn reviewed;private RunControl control;private Runnable event;private int starts,cancels;
    @Before public void setup()throws Exception{
        tasks=new TaskStore(RuntimeEnvironment.getApplication());reviewed=new RealtimeReviewedTurn(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),2,"整理明天安排","input-1","reported-local-microphone","audio-session","openai_realtime_ga");
        tasks.createReviewedRealtimeTask(reviewed.commitId,reviewed.text,reviewed.source());host=createHost();
    }
    private NativeVoiceTaskHost createHost(){return new NativeVoiceTaskHost(tasks,new NativeVoiceTaskHost.Actions(){
        public JSONObject startOriginal(VoiceTaskHostPort.Command c,VoiceTaskHostPort.Authority a)throws Exception{a.check();starts++;control=new RunControl();return tasks.startReviewedRealtimeAttempt(c.reference.taskId,c.reference.text,c.reference.source(),c.expectedRunId,c.expectedEpoch,c.expectedRevision,4,control);}
        public JSONObject cancelExactRun(VoiceTaskHostPort.Command c,VoiceTaskHostPort.Authority a)throws Exception{a.check();cancels++;return tasks.cancelReviewedRealtimeRun(c.reference.taskId,c.reference.text,c.reference.source(),c.expectedRunId,c.expectedEpoch,c.expectedRevision,control);}
    },(id,changed)->{event=changed;return ()->{if(event==changed)event=null;};});}
    private SQLiteDatabase db()throws Exception{Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    @After public void close()throws Exception{if(db().isOpen())db().close();}
    @Test public void sameSavedTaskStartsOnceAndPersistentRecoveryOnlyRestoresOriginalRecord()throws Exception{
        VoiceTaskJourney journey=new VoiceTaskJourney(()->{});journey.install(host);JSONObject view=journey.attachSaved(reviewed,()->{});String binding=view.getString("bindingId");
        view=journey.startOriginal(binding,view.getString("viewToken"));assertEquals(1,starts);assertEquals(reviewed.commitId,view.getJSONObject("task").getString("taskId"));assertEquals(1,view.getJSONObject("task").getInt("epoch"));
        JSONObject original=tasks.get(reviewed.commitId,false);assertTrue(control.matchesHostRun(reviewed.commitId,original.getString("runId"),1));assertThrows(IllegalStateException.class,()->journey.startOriginal(binding,journey.snapshot().getString("viewToken")));
        tasks.endAttempt(reviewed.commitId,original.getString("runId"),"completed","原执行结果：安排已整理，请核验",new JSONObject());event.run();assertEquals("completed",journey.snapshot().getJSONObject("task").getString("status"));journey.detach();assertFalse(control.isCancelled());assertEquals(0,cancels);
        db().close();tasks=new TaskStore(RuntimeEnvironment.getApplication());host=createHost();VoiceTaskJourney restored=new VoiceTaskJourney(()->{});restored.install(host);
        assertEquals(1,restored.listSaved(()->{}).getJSONArray("recentTasks").length());JSONObject recovered=restored.recoverSaved(reviewed.commitId,()->{});
        assertEquals("completed",recovered.getJSONObject("task").getString("status"));assertEquals(reviewed.text,recovered.getString("goal"));assertEquals(1,starts);assertEquals(1,tasks.list().length());assertFalse(recovered.getBoolean("canStart"));restored.close();
    }
    @Test public void futureVersionReplacementRunAndTerminalRecordCannotBeCancelled()throws Exception{
        JSONObject first=tasks.get(reviewed.commitId,false);long version=first.getLong("voiceStateRevision");control=new RunControl();JSONObject admitted=tasks.startReviewedRealtimeAttempt(reviewed.commitId,reviewed.text,reviewed.source(),"",0,version,4,control);String run=admitted.getString("runId");long admittedRevision=admitted.getLong("voiceStateRevision");
        tasks.status(reviewed.commitId,"running","新的进度");assertTrue(tasks.get(reviewed.commitId,false).getLong("voiceStateRevision")>admittedRevision);
        assertThrows(SecurityException.class,()->tasks.cancelReviewedRealtimeRun(reviewed.commitId,reviewed.text,reviewed.source(),run,1,Long.MAX_VALUE,control));assertFalse(control.isCancelled());
        tasks.endAttempt(reviewed.commitId,run,"completed","已完成的真实结果",new JSONObject());JSONObject complete=tasks.get(reviewed.commitId,false);
        assertThrows(IllegalStateException.class,()->tasks.cancelReviewedRealtimeRun(reviewed.commitId,reviewed.text,reviewed.source(),run,1,complete.getLong("voiceStateRevision"),control));assertEquals("completed",tasks.get(reviewed.commitId,false).getString("status"));assertFalse(control.isCancelled());
        tasks.status(reviewed.commitId,"awaiting_user","主人要求继续原任务");JSONObject next=tasks.startAttempt(reviewed.commitId,"capabilities",4);RunControl replacement=new RunControl();replacement.bindHostRun(reviewed.commitId,next.getString("runId"),2);
        assertThrows(SecurityException.class,()->tasks.cancelReviewedRealtimeRun(reviewed.commitId,reviewed.text,reviewed.source(),run,1,complete.getLong("voiceStateRevision"),replacement));assertFalse(replacement.isCancelled());
    }
    @Test public void exactNativeCancellationRetainsOriginalTaskAndSourceAndClosesOnlyObserver()throws Exception{
        VoiceTaskJourney journey=new VoiceTaskJourney(()->{});journey.install(host);JSONObject view=journey.attachSaved(reviewed,()->{});String binding=view.getString("bindingId");view=journey.startOriginal(binding,view.getString("viewToken"));
        view=journey.cancelExactRun(binding,view.getString("cancelToken"));assertEquals(1,cancels);assertTrue(control.isCancelled());assertEquals("stopping",view.getJSONObject("task").getString("status"));
        assertEquals(reviewed.contentHash,tasks.get(reviewed.commitId,false).getJSONObject("origin").getString("contentHash"));journey.close();assertNull(event);assertEquals(1,cancels);assertEquals(1,tasks.list().length());
    }
    @Test public void progressWithinExactRunDoesNotBlockUrgentCancellation()throws Exception{
        JSONObject waiting=tasks.get(reviewed.commitId,false);control=new RunControl();JSONObject admitted=tasks.startReviewedRealtimeAttempt(reviewed.commitId,reviewed.text,reviewed.source(),"",0,waiting.getLong("voiceStateRevision"),4,control);
        tasks.status(reviewed.commitId,"running","原轮次有新进度");JSONObject stopped=tasks.cancelReviewedRealtimeRun(reviewed.commitId,reviewed.text,reviewed.source(),admitted.getString("runId"),1,admitted.getLong("voiceStateRevision"),control);
        assertTrue(control.isCancelled());assertTrue(stopped.getBoolean("cancelRequested"));assertEquals(admitted.getString("runId"),stopped.getString("runId"));
    }
}
