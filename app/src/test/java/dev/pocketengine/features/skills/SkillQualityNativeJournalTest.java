package dev.pocketengine.features.skills;
import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SkillQualityNativeJournalTest {
 @Test public void originalNativeExecutionFeedbackPersistsAndCrashReplayDoesNotExecuteAgain()throws Exception {
  try(var f=new NativeSkillExecutionHostTest.Fixture()){
   var work=f.execute();var review=f.review();f.host.decide(f.window,review,review.plan.digest,true);JSONObject actual=work.get(10,TimeUnit.SECONDS);
   String task=f.task.getString("id"),op=f.input.getString("_hostOperationId"),id=f.installed.getString("id"),digest=f.installed.getString("digest");
   f.tasks.journal(task,f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).finishOperation(op,actual);
   assertFalse(actual.getBoolean("businessVerified"));assertEquals(1,f.tasks.skillQualityApplications(id,1,digest).getJSONArray("applications").length());
   var feedback=f.tasks.reviewSkillQuality(task,op,id,1,digest,"verified","Owner checked real fixture output");
   // Simulated interruption after original journal commit, before registry application.
   TaskStore reopened=new TaskStore(f.android);assertEquals("verified",reopened.readSkillExecution(task,op).getJSONObject("qualityFeedback").getString("outcome"));
   var replay=reopened.reviewSkillQuality(task,op,id,1,digest,"verified","Owner checked real fixture output");
   JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
   assertEquals("active",f.skills.recordQuality(scope,replay).getJSONObject("skill").getString("qualityState"));
   SkillsModule restored=new SkillsModule(new FeatureContext(f.android,null,new SecretVault(f.android),event->{}));
   assertEquals(1,restored.recordQuality(scope,feedback).getJSONObject("skill").getJSONArray("qualityHistory").length());
   assertThrows(SecurityException.class,()->reopened.reviewSkillQuality(task,op,id,1,digest,"skill_failed","Different claim"));
   assertEquals(1,f.posts.get());assertEquals(1,f.markers());
  }
 }
}
