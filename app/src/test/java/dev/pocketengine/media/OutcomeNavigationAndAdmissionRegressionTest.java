package dev.pocketengine.media;

import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.media.jobs.results.GroupMediaResults;
import dev.pocketengine.media.jobs.results.GroupResultAccess;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Headless locator/projection and real SQLite admission checks; not Compose navigation/device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OutcomeNavigationAndAdmissionRegressionTest {
    @Test public void groupBackLocatorRetainsScopeAndOriginalRunButCannotReopenTheArtifact()throws Exception {
        GroupResultAccess.Selection selected=new GroupResultAccess.Selection("request-1","task-1","group:scope-1","output-1","artifact-1","requester","members","operation-1","source-run-1");
        GroupMediaResults results=new GroupMediaResults(new GroupResultAccess(){
            public Context inspect(Selection actual,RunControl control){return new Context(actual,"current-run-2",2,3,"needs_confirmation","not_required","operation-1","source-run-1",1,"",false,false);}
            public void checkRead(Context expected,RunControl control){assertEquals("requester",expected.actual.audience);assertEquals("group:scope-1",expected.actual.spaceId);}
            public JSONObject mediaReceipt(Context expected,RunControl control){return null;}
            public Artifact artifact(Context expected,RunControl control){return new Artifact("artifact-1","group:scope-1","task-1","output-1","requester","text/plain",4,"a".repeat(64),"","operation-1","source-run-1","");}
            public InputStream openArtifact(Context expected,Artifact file,RunControl control){throw new AssertionError("Navigating a locator must not open bytes");}
            public Delivery delivery(Context expected,RunControl control){return null;}
            public void refreshOriginalMedia(Context expected,Refresh refresh,RunControl control){throw new AssertionError("Back must not query, submit or send");}
        });
        assertTrue(results.inspect(selected,new RunControl()).has("artifact"));
        GroupResultAccess.Selection list=selected.withoutArtifact();
        assertEquals(selected.requestId,list.requestId);assertEquals(selected.taskId,list.taskId);assertEquals(selected.spaceId,list.spaceId);
        assertEquals(selected.audience,list.audience);assertEquals(selected.visibility,list.visibility);
        assertEquals(selected.sourceOperationHint,list.sourceOperationHint);assertEquals(selected.sourceRunHint,list.sourceRunHint);
        assertEquals("",list.outputId);assertEquals("",list.artifactId);assertEquals("artifact-1",selected.artifactId);
        JSONObject refreshed=results.inspect(list,new RunControl());assertFalse(refreshed.has("artifact"));assertFalse(refreshed.getBoolean("artifactReady"));
        assertEquals("current-run-2",refreshed.getString("taskRunId"));assertEquals("source-run-1",refreshed.getString("sourceTaskRunId"));
    }
    @Test public void mediaWrapperForwardsTheSameFinalSnapshotOnlyForItsOriginalLiveControl()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);
        MediaModule media=new MediaModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        String task=tasks.createCapabilityTask("Read the retained native surface","admission-regression").getString("id");JSONObject attempt=tasks.startAttempt(task,"capabilities",4);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");
        RunControl live=new RunControl();live.bindHostRun(task,run,epoch);tasks.authorizeTools(task,run,new JSONArray().put("media_read_original"));
        AtomicInteger calls=new AtomicInteger();Set<String> names=Set.of("media_read_original","workspace_read","skill_run");
        CapabilitySnapshot snapshot=new CapabilitySnapshot(task,run,epoch,live,names,()->{});
        CapabilityPort recorder=new CapabilityPort(){
            public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}
            public JSONObject execute(String name,JSONObject input,RunControl supplied){throw new AssertionError("Admission is not execution");}
            public void admitCapabilities(CapabilitySnapshot actual,RunControl supplied)throws Exception{assertSame(snapshot,actual);assertSame(live,supplied);assertEquals(names,actual.current(task,run,epoch,supplied));calls.incrementAndGet();}
        };
        MediaTaskCapabilities port=new MediaTaskCapabilities(recorder,tasks,media,(command,input,control)->{throw new AssertionError("No provider call");},task,run,epoch,live);
        try {
            port.admitCapabilities(snapshot,live);assertEquals("Final capability admission must pass through the media wrapper",1,calls.get());
            RunControl other=new RunControl();other.bindHostRun(task,run,epoch);assertThrows(SecurityException.class,()->port.admitCapabilities(snapshot,other));assertEquals(1,calls.get());
            port.close();assertThrows(SecurityException.class,()->port.admitCapabilities(snapshot,live));assertEquals(1,calls.get());
            assertEquals(1,tasks.list().length());assertEquals(0,tasks.get(task,false).getJSONArray("operations").length());
        } finally {port.close();media.close();}
    }
}
