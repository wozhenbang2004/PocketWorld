package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original TaskStore/foreground owner/HTTP/Companion/Popen, not a new session registry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteManagedSessionsOwnerJourneyTest {
    static JSONObject first(JSONObject snapshot)throws Exception{return snapshot.getJSONArray("managedConnections").getJSONObject(0).getJSONArray("contexts").getJSONObject(0).getJSONArray("sessions").getJSONObject(0);}
    static SQLiteDatabase db(RemotePiOwnerJourneyTest.Fixture f)throws Exception {var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(f.tasks);}
    static void insert(RemotePiOwnerJourneyTest.Fixture f,JSONObject task)throws Exception {ContentValues value=new ContentValues();value.put("task_id",task.getString("id"));value.put("document",task.toString());value.put("updated_at",task.optLong("updatedAt"));db(f).insertOrThrow("tasks",null,value);}
    @Test public void refreshAndExactNavigationAreLocalReadsWithoutChangingOriginalReviewRunOrSubmission()throws Exception {
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject review=f.open(),before=f.tasks.get(f.task,false),source=f.source();String original=before.toString();int events=f.events.size();
            JSONObject index=f.owner.managedSessions(f.nativeOwner),row=first(index);assertEquals(f.task,row.getString("taskId"));assertEquals(source.getString("runId"),row.getString("runId"));assertEquals(source.getString("jobId"),row.getString("jobId"));assertEquals("native_protocol_observed",row.getString("sessionEvidence"));
            assertEquals(f.task,f.owner.managedSessionTask(f.nativeOwner,row.getString("taskId"),row.getString("rowIdentity")));assertEquals(row.getString("rowIdentity"),first(f.owner.managedSessions(f.nativeOwner)).getString("rowIdentity"));
            assertEquals(original,f.tasks.get(f.task,false).toString());assertEquals(events,f.events.size());assertEquals(1,f.count("submit-count"));assertFalse(index.toString().contains("pi-sessions"));assertFalse(index.toString().contains("progress"));assertFalse(index.toString().contains("reviewId"));
            // The same original approval remains usable; the index did not replace its selection/review.
            f.owner.handle(f.nativeOwner,"continue",f.action(review).put("prompt","explicit original owner next turn after local index"));f.settled("completed");assertEquals(2,f.count("submit-count"));assertEquals(source.getInt("coordinatorEpoch")+1,f.source().getInt("coordinatorEpoch"));
            assertThrows(SecurityException.class,()->f.owner.managedSessionTask(f.nativeOwner,row.getString("taskId"),row.getString("rowIdentity")));assertEquals(f.task,f.owner.managedSessionTask(f.nativeOwner,f.task,first(f.owner.managedSessions(f.nativeOwner)).getString("rowIdentity")));
        }
    }
    @Test public void wrongOwnerBackgroundAndJsonFeaturePathsCannotBecomeNativeIndexAccess()throws Exception {
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject row=first(f.owner.managedSessions(f.nativeOwner));assertThrows(SecurityException.class,()->f.owner.managedSessions(null));assertThrows(SecurityException.class,()->f.owner.managedSessions(new Object()));assertThrows(SecurityException.class,()->f.owner.managedSessionTask(new Object(),f.task,row.getString("rowIdentity")));
            assertThrows(Exception.class,()->f.owner.handle(f.nativeOwner,"managedSessions",new JSONObject()));f.owner.setForeground(f.nativeOwner,false);assertThrows(SecurityException.class,()->f.owner.managedSessions(f.nativeOwner));assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void originalChannelGroupTeamAndUnresolvableParentRowsDoNotLeakIntoPrivateIndex()throws Exception {
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject original=f.tasks.get(f.task,false);
            for(String kind:new String[]{"channel","group","reviewed_external_group","team_remote_assignment"}){JSONObject copy=new JSONObject(original.toString()).put("id",UUID.randomUUID().toString()).put("origin",new JSONObject().put("kind",kind));insert(f,copy);}
            insert(f,new JSONObject(original.toString()).put("id",UUID.randomUUID().toString()).put("actorId","someone_else"));insert(f,new JSONObject(original.toString()).put("id",UUID.randomUUID().toString()).put("parentTaskId",UUID.randomUUID().toString()));
            JSONObject snapshot=f.owner.managedSessions(f.nativeOwner);assertEquals(1,snapshot.getJSONArray("managedConnections").length());assertEquals(1,snapshot.getJSONArray("managedConnections").getJSONObject(0).getJSONArray("contexts").getJSONObject(0).getJSONArray("sessions").length());assertEquals(f.task,first(snapshot).getString("taskId"));assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void lastStoredIndexStillReadsOfflineAndDoesNotPretendItQueriedCurrentRemoteState()throws Exception {
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            f.server.destroy();assertTrue(f.server.waitFor(6,TimeUnit.SECONDS));String run=f.source().getString("runId");
            JSONObject index=f.owner.managedSessions(f.nativeOwner),row=first(index);assertEquals("last_stored_native_metadata_not_live",index.getString("observation"));assertTrue(row.getBoolean("requiresOriginalRequery"));assertEquals(run,row.getString("runId"));assertEquals(1,f.count("submit-count"));assertFalse(index.getBoolean("authorizesExecution"));
        }
    }
    @Test public void recentLocalTaskWindowDoesNotSilentlyClaimToContainAllRemoteHistory()throws Exception {
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject original=f.tasks.get(f.task,false);long newer=original.getLong("updatedAt")+1000;
            for(int i=0;i<200;i++)insert(f,new JSONObject().put("id",UUID.randomUUID().toString()).put("feature","not_remote").put("actorId","owner").put("spaceId","private").put("updatedAt",newer+i));
            JSONObject index=f.owner.managedSessions(f.nativeOwner);assertEquals(200,index.getInt("localTasksScanned"));assertTrue(index.getBoolean("olderLocalTasksNotScanned"));assertEquals(0,index.getJSONArray("managedConnections").length());assertEquals(1,f.count("submit-count"));assertEquals(original.getString("runId"),f.source().getString("runId"));
        }
    }
}
