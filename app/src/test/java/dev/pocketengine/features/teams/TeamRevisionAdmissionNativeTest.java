package dev.pocketengine.features.teams;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual module -> Coordinator -> SQLite TaskStore, with real frozen outputs from the existing Runner fixture.
 * Faults use SQLite triggers, not a replacement TaskStore or simulated successful write. No device/provider claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamRevisionAdmissionNativeTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static SQLiteDatabase db(TaskStore tasks)throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static JSONObject document(TaskStore tasks,String assignment)throws Exception {try(Cursor row=db(tasks).rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{assignment})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
    private static void write(TaskStore tasks,String assignment,JSONObject document)throws Exception {db(tasks).execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{document.toString(),assignment});}
    private static JSONObject request(TeamNativeDeliveryJourneyTest.Fixture f)throws Exception {
        JSONObject snapshot=f.settled(),assignment=TeamWorkPlan.find(snapshot.getJSONArray("members"),"a").getJSONObject("assignmentTask");JSONArray artifacts=assignment.getJSONArray("artifacts");
        return f.envelope(snapshot,"a").put("assignmentId",assignment.getString("assignmentId")).put("revisionRequestId",id()).put("text","沿原分工修订，仍保留中文标题").put("expectedFrozenRunId",artifacts.getJSONObject(0).getString("runId")).put("expectedArtifactsHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(new JSONObject().put("artifacts",artifacts))));
    }
    private static TeamsModule module(TeamNativeDeliveryJourneyTest.Fixture f,File files)throws Exception {
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
        TeamsModule module=new TeamsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));var field=TeamsModule.class.getDeclaredField("coordinator");field.setAccessible(true);((TeamCoordinator)field.get(module)).close();field.set(module,f.coordinator);module.setWriteHost(f.host);return module;
    }
    private static JSONObject refreshed(TeamNativeDeliveryJourneyTest.Fixture f,JSONObject request)throws Exception {JSONObject next=new JSONObject(request.toString()),snapshot=f.coordinator.get(f.run),member=TeamWorkPlan.find(snapshot.getJSONArray("members"),"a");return next.put("expectedRevision",snapshot.getLong("revision")).put("expectedGeneration",snapshot.getInt("generation")).put("expectedMemberGeneration",member.getInt("generation"));}
    @Test public void unsupportedAndInvalidRequestsAreRejectedBeforeAnyFeedbackOrAssignmentChange()throws Exception {
        try(TeamNativeDeliveryJourneyTest.Fixture f=new TeamNativeDeliveryJourneyTest.Fixture(false,false)){
            f.driveMembers();TeamsModule module=module(f,Files.createTempDirectory("team-revision-admission-").toFile());JSONObject input=request(f),before=f.coordinator.get(f.run),original=document(f.tasks,input.getString("assignmentId"));String expected=TeamWorkFiles.canonical(before);
            for(String field:new String[]{"workItemId","remoteSelection","projectSelection"}){
                JSONObject unsupported=new JSONObject(original.toString());unsupported.getJSONObject("teamAssignment").put(field,field.equals("workItemId")?"dynamic-item":new JSONObject());write(f.tasks,input.getString("assignmentId"),unsupported);
                for(String command:new String[]{"reviseAssignment","continueOutcome"}){JSONObject attempt=new JSONObject(input.toString()).put("expectedIntegrationReviewId",id()).put("expectedIntegrationPlanHash","b".repeat(64));assertThrows(Exception.class,()->module.handle(command,attempt));assertEquals(expected,TeamWorkFiles.canonical(f.coordinator.get(f.run)));assertEquals(TeamWorkFiles.canonical(unsupported),TeamWorkFiles.canonical(document(f.tasks,input.getString("assignmentId"))));}
                write(f.tasks,input.getString("assignmentId"),original);
            }
            JSONArray invalid=new JSONArray().put(new JSONObject(input.toString()).put("assignmentId",id())).put(new JSONObject(input.toString()).put("memberId","b")).put(new JSONObject(input.toString()).put("revisionRequestId","not-a-request")).put(new JSONObject(input.toString()).put("text","   ")).put(new JSONObject(input.toString()).put("expectedArtifactsHash","0".repeat(64))).put(new JSONObject(input.toString()).put("expectedFrozenRunId",id())).put(new JSONObject(input.toString()).put("expectedMemberGeneration",999));
            for(int i=0;i<invalid.length();i++){JSONObject attempt=invalid.getJSONObject(i);assertThrows(Exception.class,()->module.handle("reviseAssignment",attempt));assertEquals(expected,TeamWorkFiles.canonical(f.coordinator.get(f.run)));}
            assertThrows(Exception.class,()->module.handle("continueOutcome",new JSONObject(input.toString()).put("expectedIntegrationReviewId",id()).put("expectedIntegrationPlanHash","b".repeat(64))));assertEquals(expected,TeamWorkFiles.canonical(f.coordinator.get(f.run)));assertEquals(TeamWorkFiles.canonical(original),TeamWorkFiles.canonical(document(f.tasks,input.getString("assignmentId"))));
        }
    }
    @Test public void successfulLocalRevisionIsSavedOnceAndRefreshingTheSameRequestCannotRequeueFeedback()throws Exception {
        try(TeamNativeDeliveryJourneyTest.Fixture f=new TeamNativeDeliveryJourneyTest.Fixture(false,false)){
            f.driveMembers();TeamsModule module=module(f,Files.createTempDirectory("team-revision-success-").toFile());JSONObject input=request(f),result=module.handle("reviseAssignment",input);assertEquals(2,result.getJSONObject("revisedAssignment").getInt("assignmentRevision"));assertFalse(result.getJSONObject("revisedAssignment").getBoolean("executionAuthorized"));
            JSONObject after=f.coordinator.get(f.run);assertEquals(1,after.getJSONArray("inbox").length());assertEquals(input.getString("revisionRequestId"),after.getJSONArray("inbox").getJSONObject(0).getString("id"));String snapshot=TeamWorkFiles.canonical(after);
            IllegalStateException known=assertThrows(IllegalStateException.class,()->module.handle("reviseAssignment",refreshed(f,input)));assertTrue(known.getMessage().contains("已保存"));assertEquals(snapshot,TeamWorkFiles.canonical(f.coordinator.get(f.run)));assertEquals(2,f.assignment("a").getJSONObject("teamAssignment").getInt("assignmentRevision"));
            assertThrows(SecurityException.class,()->module.handle("reviseAssignment",refreshed(f,input).put("text","same ID changed meaning")));assertEquals(snapshot,TeamWorkFiles.canonical(f.coordinator.get(f.run)));
        }
    }
    @Test public void realSqliteFailureAfterFeedbackReturnsPartialAndColdReplayDoesNotRetryEitherStage()throws Exception {
        try(TeamNativeDeliveryJourneyTest.Fixture f=new TeamNativeDeliveryJourneyTest.Fixture(false,false)){
            f.driveMembers();File files=Files.createTempDirectory("team-revision-sql-fault-").toFile();TeamsModule module=module(f,files);JSONObject input=request(f),original=document(f.tasks,input.getString("assignmentId"));String assignment=input.getString("assignmentId");
            db(f.tasks).execSQL("CREATE TRIGGER revision_write_failure BEFORE UPDATE OF document ON tasks WHEN NEW.task_id='"+assignment+"' BEGIN SELECT RAISE(ABORT,'revision write fixture fault'); END");JSONObject result;
            try{result=module.handle("reviseAssignment",input);}finally{db(f.tasks).execSQL("DROP TRIGGER revision_write_failure");}
            assertFalse(result.getBoolean("ok"));assertTrue(result.getBoolean("partial"));assertFalse(result.getBoolean("replayAllowed"));JSONObject attempt=result.getJSONObject("revisionAttempt");assertEquals("partial",attempt.getString("state"));assertEquals("saved",attempt.getString("feedbackState"));assertEquals("unknown",attempt.getString("assignmentRevisionState"));assertEquals("not_confirmed",attempt.getJSONObject("assignmentReadback").getString("state"));assertEquals(TeamWorkFiles.canonical(original),TeamWorkFiles.canonical(document(f.tasks,assignment)));assertEquals(1,f.coordinator.get(f.run).getJSONArray("inbox").length());
            JSONObject after=f.coordinator.get(f.run);assertThrows(IllegalStateException.class,()->module.handle("reviseAssignment",refreshed(f,input)));assertEquals(TeamWorkFiles.canonical(after),TeamWorkFiles.canonical(f.coordinator.get(f.run)));
            // Reopen the actual durable Coordinator file, not the old in-memory module.
            File coldFiles=Files.createTempDirectory("team-revision-cold-").toFile();File coldState=new File(coldFiles,"teams/runs.json");JsonFiles.write(coldState,JsonFiles.read(new File(f.files.root,"native-team.json")));
            Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return coldFiles;}};
            try(TeamsModule cold=new TeamsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}))){cold.setWriteHost(f.host);assertThrows(IllegalStateException.class,()->cold.handle("reviseAssignment",refreshed(f,input)));JSONObject snapshot=cold.handle("get",new JSONObject().put("runId",f.run));assertEquals(1,snapshot.getJSONArray("inbox").length());assertEquals(1,f.assignment("a").getJSONObject("teamAssignment").getInt("assignmentRevision"));}
        }
    }
    @Test public void featureHostShowsPartialAsFailureAndKeepsTheActualSnapshotAndOriginalAssignment()throws Exception {
        try(TeamNativeDeliveryJourneyTest.Fixture f=new TeamNativeDeliveryJourneyTest.Fixture(false,false)){
            f.driveMembers();TeamsModule module=module(f,Files.createTempDirectory("team-revision-host-").toFile());JSONObject input=request(f),original=document(f.tasks,input.getString("assignmentId"));String assignment=input.getString("assignmentId"),requestId=id();
            AtomicReference<JSONObject> observed=new AtomicReference<>();CountDownLatch replied=new CountDownLatch(1);
            try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result")&&requestId.equals(event.optString("requestId"))){observed.set(event);replied.countDown();}},f.tasks,()->false)){
                // No workspace registration: retain the fixture's original real TaskStore write host.
                host.register(module);db(f.tasks).execSQL("CREATE TRIGGER revision_host_write_failure BEFORE UPDATE OF document ON tasks WHEN NEW.task_id='"+assignment+"' BEGIN SELECT RAISE(ABORT,'revision host write fixture fault'); END");
                try{host.request(new JSONObject().put("feature","teams").put("command","reviseAssignment").put("requestId",requestId).put("payload",input));assertTrue("original request must reply",replied.await(10,TimeUnit.SECONDS));}finally{db(f.tasks).execSQL("DROP TRIGGER revision_host_write_failure");}
                JSONObject event=observed.get();assertFalse("UI consumes this outer ok flag",event.getBoolean("ok"));assertTrue("UI displays this outer error message",event.getString("message").contains("不要重送"));
                JSONObject actual=event.getJSONObject("result"),snapshot=f.coordinator.get(f.run);assertTrue(actual.getBoolean("requiresOwnerReview"));assertFalse(actual.getBoolean("replayAllowed"));assertEquals("paused",actual.getString("status"));assertEquals(snapshot.getString("status"),actual.getString("status"));assertEquals(snapshot.getLong("revision"),actual.getLong("revision"));assertEquals(1,actual.getJSONArray("inbox").length());assertEquals("feedback_applied",actual.getJSONArray("inbox").getJSONObject(0).getString("revisionFeedbackState"));assertEquals(TeamWorkFiles.canonical(original),TeamWorkFiles.canonical(document(f.tasks,assignment)));assertEquals(1,f.assignment("a").getJSONObject("teamAssignment").getInt("assignmentRevision"));
            }
        }
    }
    @Test public void failedParentReceiptWritePreservesTheSavedRevisionAndActualSnapshotWithoutReplay()throws Exception {
        try(TeamNativeDeliveryJourneyTest.Fixture f=new TeamNativeDeliveryJourneyTest.Fixture(false,false)){
            f.driveMembers();TeamsModule module=module(f,Files.createTempDirectory("team-revision-parent-receipt-").toFile());JSONObject input=request(f);String assignment=input.getString("assignmentId"),teamTask=f.tasks.requireReviewedTeamControl(input),requestId=id();JSONObject parentBefore=document(f.tasks,teamTask);
            AtomicReference<JSONObject> observed=new AtomicReference<>();CountDownLatch replied=new CountDownLatch(1);
            try(FeatureHost host=new FeatureHost(event->{if(event.optString("type").equals("feature_result")&&requestId.equals(event.optString("requestId"))){observed.set(event);replied.countDown();}},f.tasks,()->false)){
                host.register(module);db(f.tasks).execSQL("CREATE TRIGGER revision_parent_receipt_failure BEFORE UPDATE OF document ON tasks WHEN NEW.task_id='"+teamTask+"' BEGIN SELECT RAISE(ABORT,'revision parent receipt fixture fault'); END");
                try{host.request(new JSONObject().put("feature","teams").put("command","reviseAssignment").put("requestId",requestId).put("payload",input));assertTrue("original request must reply",replied.await(10,TimeUnit.SECONDS));}finally{db(f.tasks).execSQL("DROP TRIGGER revision_parent_receipt_failure");}
                JSONObject event=observed.get();assertFalse(event.getBoolean("ok"));assertNotNull("failed parent receipt must not discard the actual module result",event.optJSONObject("result"));assertTrue(event.getString("message").contains("不要重送"));JSONObject actual=event.getJSONObject("result"),snapshot=f.coordinator.get(f.run);
                assertEquals("unknown",actual.getString("ownerReceiptState"));assertTrue(actual.getBoolean("requiresOwnerReview"));assertFalse(actual.getBoolean("replayAllowed"));assertEquals("paused",actual.getString("status"));assertEquals(snapshot.getLong("revision"),actual.getLong("revision"));assertEquals(2,actual.getJSONObject("revisedAssignment").getInt("assignmentRevision"));assertEquals(2,document(f.tasks,assignment).getJSONObject("teamAssignment").getInt("assignmentRevision"));assertEquals(TeamWorkFiles.canonical(parentBefore),TeamWorkFiles.canonical(document(f.tasks,teamTask)));assertEquals(1,snapshot.getJSONArray("inbox").length());assertEquals("feedback_applied",snapshot.getJSONArray("inbox").getJSONObject(0).getString("revisionFeedbackState"));
                assertThrows(IllegalStateException.class,()->module.handle("reviseAssignment",refreshed(f,input)));assertEquals(TeamWorkFiles.canonical(snapshot),TeamWorkFiles.canonical(f.coordinator.get(f.run)));assertEquals(2,document(f.tasks,assignment).getJSONObject("teamAssignment").getInt("assignmentRevision"));
            }
        }
    }
}
