package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQL/current-run gate: absent service is not missing project authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RetainedDevelopmentAdmissionTest {
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase db;final String id,run;final int epoch;final RunControl control=new RunControl();
        Fixture()throws Exception{
            var android=RuntimeEnvironment.getApplication();android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            id=tasks.createCapabilityTask("原主人普通对话",UUID.randomUUID().toString()).getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",4);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray());
        }
        void candidate(String candidateRun)throws Exception{
            ContentValues row=new ContentValues();row.put("operation_id",UUID.randomUUID().toString());row.put("task_id",id);row.put("run_id",candidateRun);row.put("tool","project_execute");row.put("arguments","{}");row.put("receipt","{}");row.put("state","project_running");row.put("created_at",1);db.insertOrThrow("operations",null,row);
        }
        public void close(){db.close();}
    }
    @Test public void ordinaryOwnerConversationNeedsNoProjectGrantWhenThisRunHasNoService()throws Exception {try(Fixture f=new Fixture()){
        assertEquals(0,f.tasks.retainedDevelopmentServices(f.id,f.control).length());f.candidate(UUID.randomUUID().toString());assertEquals(0,f.tasks.retainedDevelopmentServices(f.id,f.control).length());
        RunControl unrelated=new RunControl();unrelated.bindHostRun(f.id,UUID.randomUUID().toString(),f.epoch);assertThrows(SecurityException.class,()->f.tasks.retainedDevelopmentServices(f.id,unrelated));
    }}
    @Test public void existingServiceStillRequiresCurrentProjectInspectionGrant()throws Exception {try(Fixture f=new Fixture()){
        f.candidate(f.run);assertThrows(SecurityException.class,()->f.tasks.retainedDevelopmentServices(f.id,f.control));
    }}
    @Test public void grantedInspectionDoesNotHideUnknownOrMalformedOwnedServiceEvidence()throws Exception {try(Fixture f=new Fixture()){
        f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("project_inspect"));f.candidate(f.run);assertThrows(Exception.class,()->f.tasks.retainedDevelopmentServices(f.id,f.control));
    }}
}
