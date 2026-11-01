package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native owner + original SQLite and original HTTP peer; not real accounts/CLIs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteTaskDraftQueueTest {
    private static void configured(RemoteOwnerWorkflowIntegrationTest.Fixture f)throws Exception {
        var app=org.robolectric.RuntimeEnvironment.getApplication();var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
        app.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,"computer",f.server.base()),"explicit-fixture-presence-not-a-ciphertext").commit();
    }
    private static JSONObject open(RemoteOwnerWorkflows owner,Object activity,String task)throws Exception{return owner.handle(activity,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
    private static JSONObject action(JSONObject view)throws Exception{return new JSONObject().put("windowId",view.getString("windowId")).put("taskId",view.getString("taskId")).put("reviewId",view.getString("reviewId"));}
    @Test public void codexAndClaudeDurableQueueSurvivesWindowReopenAndConsumesOneOriginalContinuation()throws Exception {
        for(String worker:new String[]{"codex_app_server","claude"})try(RemoteOwnerWorkflowIntegrationTest.Fixture f=new RemoteOwnerWorkflowIntegrationTest.Fixture(worker,"running")){
            configured(f);Object activity=new Object();RemoteOwnerWorkflows owner=new RemoteOwnerWorkflows(f.host,f.tasks,e->{});owner.setForeground(activity,true);JSONObject first=open(owner,activity,f.task);String draft=UUID.randomUUID().toString();
            owner.handle(activity,"saveDraft",action(first).put("draftId",draft).put("draftRevision",0).put("mode","next_turn").put("text","precise owner next input").put("queued",true));assertEquals(1,f.server.submits.get());
            owner.close(first.getString("windowId"));owner.setForeground(activity,false);RemoteOwnerWorkflows reopened=new RemoteOwnerWorkflows(f.host,f.tasks,e->{});reopened.setForeground(activity,true);JSONObject view=open(reopened,activity,f.task);
            assertEquals(draft,view.getJSONArray("inputDrafts").getJSONObject(0).getString("id"));assertEquals("queued",view.getJSONArray("inputDrafts").getJSONObject(0).getString("state"));
            JSONObject running=view;assertThrows(SecurityException.class,()->reopened.handle(activity,"sendDraft",action(running).put("draftId",draft).put("draftRevision",1)));assertEquals(1,f.server.submits.get());
            f.server.jobs.get("job-1").put("status","completed");view=reopened.handle(activity,"refresh",action(view)).getJSONObject("snapshot");assertTrue(view.getBoolean("canContinue"));
            JSONObject ready=view;reopened.handle(activity,"sendDraft",action(view).put("draftId",draft).put("draftRevision",1));assertEquals(2,f.server.submits.get());assertEquals(1,f.tasks.list().length());assertEquals(f.task,f.tasks.get(f.task,false).getString("id"));
            assertEquals(1,f.tasks.originalRemoteDrafts(f.task).length());assertEquals("dispatched",f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("state"));
            assertThrows(SecurityException.class,()->reopened.handle(activity,"sendDraft",action(ready).put("draftId",draft).put("draftRevision",1)));assertEquals(2,f.server.submits.get());reopened.setForeground(activity,false);
        }
    }
    @Test public void editsAreCasWithdrawDoesNotSendAndBackgroundOrChangedGenerationCannotConsume()throws Exception {
        try(RemoteOwnerWorkflowIntegrationTest.Fixture f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude")){
            configured(f);Object activity=new Object();RemoteOwnerWorkflows owner=new RemoteOwnerWorkflows(f.host,f.tasks,e->{});owner.setForeground(activity,true);JSONObject view=open(owner,activity,f.task);String draft=UUID.randomUUID().toString();
            owner.handle(activity,"saveDraft",action(view).put("draftId",draft).put("draftRevision",0).put("mode","next_turn").put("text","first").put("queued",false));view=owner.handle(activity,"refresh",action(view)).getJSONObject("snapshot");
            owner.handle(activity,"saveDraft",action(view).put("draftId",draft).put("draftRevision",1).put("mode","next_turn").put("text","edited").put("queued",true));view=owner.handle(activity,"refresh",action(view)).getJSONObject("snapshot");
            JSONObject current=view;assertThrows(SecurityException.class,()->owner.handle(activity,"saveDraft",action(current).put("draftId",draft).put("draftRevision",1).put("mode","next_turn").put("text","stale edit").put("queued",true)));
            owner.setForeground(activity,false);assertThrows(SecurityException.class,()->owner.handle(activity,"sendDraft",action(current).put("draftId",draft).put("draftRevision",2)));assertEquals(1,f.server.submits.get());
            owner.setForeground(activity,true);view=owner.handle(activity,"refresh",action(view)).getJSONObject("snapshot");owner.handle(activity,"withdrawDraft",action(view).put("draftId",draft).put("draftRevision",2));assertEquals("withdrawn",f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("state"));assertEquals(1,f.server.submits.get());
            view=owner.handle(activity,"refresh",action(view)).getJSONObject("snapshot");String another=UUID.randomUUID().toString();owner.handle(activity,"saveDraft",action(view).put("draftId",another).put("draftRevision",0).put("mode","next_turn").put("text","bound old profile").put("queued",true));view=owner.handle(activity,"refresh",action(view)).getJSONObject("snapshot");
            f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","new native generation").put("baseUrl",f.server.base()));JSONObject old=view;
            assertThrows(SecurityException.class,()->owner.handle(activity,"sendDraft",action(old).put("draftId",another).put("draftRevision",1)));assertEquals(1,f.server.submits.get());owner.setForeground(activity,false);
        }
    }
}
