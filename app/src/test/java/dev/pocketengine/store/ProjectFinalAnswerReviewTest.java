package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.context.CompactionPlan;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native SQL final-answer proof, without HTTP or a model claiming execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectFinalAnswerReviewTest {
    @Test public void projectPlanOnlyAnswerKeepsOriginalOwnerReview()throws Exception {
        settle(true,"needs_confirmation");
    }
    @Test public void ordinaryChatFinalAnswerStillCompletesWithoutEffects()throws Exception {
        settle(false,"completed");
    }
    private void settle(boolean project,String expected)throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);
        AtomicBoolean ready=new AtomicBoolean();TaskStore store=new TaskStore(app,ready::get,(stage,schema)->{});
        RunControl original=new RunControl();
        try {
            store.verifyBootstrapReadback();ready.set(true);store.conversationRepository().initializeEmpty();
            String conversation=UUID.randomUUID().toString();
            store.conversationRepository().transaction(()->{store.conversationRepository().saveConversation(new JSONObject()
                    .put("conversationId",conversation).put("title","QA final-answer review").put("messages",new JSONArray()));return null;});
            String id=store.createCapabilityTask("Create and read back the QA file",conversation).getString("id");
            JSONObject attempt=store.startAttempt(id,"capabilities",8);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");
            original.bindHostRun(id,run,epoch);
            if(project)store.bindDevelopmentContext(id,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1")
                    .put("projectId",UUID.randomUUID().toString()).put("checkoutId",UUID.randomUUID().toString()).put("runtime","managed_process_v1")
                    .put("initialSourceDigest","a".repeat(64)).put("clientMessageId",UUID.randomUUID().toString()));
            store.status(id,"running","Native SQL fixture");
            String text="Only the file plan is prepared; execution still requires owner review.";
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","Native policy"))
                    .put(new JSONObject().put("role","user").put("content","Create and read back the QA file"))
                    .put(new JSONObject().put("role","assistant").put("content",text));
            JSONObject proof=new JSONObject().put("format","pocketworld.runner-final-answer.v1").put("taskId",id).put("runId",run)
                    .put("coordinatorEpoch",epoch).put("messageIndex",2).put("historyLength",3)
                    .put("answerFingerprint",CompactionPlan.fingerprint(text)).put("inputBoundaryEmpty",true);
            JSONObject evidence=new JSONObject().put("modelSummary",text).put("nativeFinalAnswer",proof);
            store.journal(id,run,epoch,original).checkpoint(messages,0,evidence);
            store.finishCapabilityAttempt(id,run,epoch,original,new RunOutcome("needs_confirmation",text,evidence));
            JSONObject settled=store.get(id,false);assertEquals(expected,settled.getString("status"));
            assertEquals(run,settled.getString("runId"));assertEquals(epoch,settled.getInt("coordinatorEpoch"));
            Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase sql=(SQLiteDatabase)field.get(store);
            try(Cursor row=sql.rawQuery("SELECT status FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());assertEquals(expected,row.getString(0));}
            try(Cursor row=sql.rawQuery("SELECT count(*) FROM operations WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
        } finally {original.cancel();ready.set(false);if(!store.closedForMaintenance())store.closeForMaintenance();}
    }
}
