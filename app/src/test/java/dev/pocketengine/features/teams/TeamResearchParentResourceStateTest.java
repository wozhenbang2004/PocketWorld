package dev.pocketengine.features.teams;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** SQL status compatibility only: controlled persisted research rows, no research/model journey. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TeamResearchParentResourceStateTest {
    @Test public void researchChildrenKeepActiveAndUnknownStateWithoutProductionScope()throws Exception {
        var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
        try {
            JSONObject parent=tasks.startAttempt(tasks.createCapabilityTask("research parent","").getString("id"),"capabilities",8);
            String task=parent.getString("id"),run=parent.getString("runId"),teamRun=UUID.randomUUID().toString();int epoch=parent.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(task,run,epoch);
            tasks.authorizeTools(task,run,new JSONArray().put("teams_get"));tasks.beginRelatedExternal(task,run,"teams","start",teamRun,new JSONObject().put("goal","research"));
            JSONObject team=tasks.get(teamRun,false),child=tasks.startAttempt(tasks.createCapabilityTask("research child","").getString("id"),"capabilities",8);String childId=child.getString("id"),childRun=child.getString("runId");
            // Seed the persisted status shape, not an execution grant or parent approval path.
            child.put("parentTaskId",teamRun).put("origin",new JSONObject().put("kind","team_research").put("teamRunId",teamRun).put("memberId","a"));
            child.put("teamResearch",new JSONObject().put("teamRunId",teamRun).put("memberId","a").put("round",1).put("identityRevision",1));
            team.put("teamResearchParentScope",new JSONObject()).put("relatedTasks",new JSONArray().put(new JSONObject().put("taskId",childId).put("feature","team_research")));
            var write=TaskStore.class.getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(tasks,child);write.invoke(tasks,team);
            assertFalse(team.has("teamWriteScope"));assertFalse(team.has("teamWorkPlan"));
            var origin=tasks.teamParentOrigin(task,run,epoch,"teams_get","",new JSONObject().put("teamRunId",teamRun),control);
            assertEquals(0,tasks.teamRemoteStopTasks(teamRun,null).length());
            JSONObject active=tasks.teamParentExecutionState(origin,teamRun);assertEquals(1,active.getInt("activeChildAttempts"));assertFalse(active.getBoolean("resourcesReleased"));
            ContentValues settled=new ContentValues();settled.put("status","completed");assertEquals(1,db.update("runs",settled,"run_id=?",new String[]{childRun}));child.put("status","completed");write.invoke(tasks,child);
            assertTrue(tasks.teamParentExecutionState(origin,teamRun).getBoolean("resourcesReleased"));
            ContentValues row=new ContentValues();row.put("operation_id",UUID.randomUUID().toString());row.put("task_id",childId);row.put("run_id",childRun);row.put("tool","team_send_message");row.put("arguments","{}");row.put("state","unknown");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);
            JSONObject unknown=tasks.teamParentExecutionState(origin,teamRun);assertEquals(0,unknown.getInt("activeChildAttempts"));assertEquals(1,unknown.getInt("unknownChildCount"));assertTrue(unknown.getBoolean("hasUnresolvedEffects"));assertFalse(unknown.getBoolean("resourcesReleased"));
            control.cancel();
        } finally {db.close();}
    }
}
