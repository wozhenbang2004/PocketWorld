package dev.pocketengine.features.assistant;
import android.app.Application;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.core.RunControl;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OwnerInputPreparedBoundaryTest {
 @Test public void ownerTextCanQueueDuringTheOriginalInflightPreparedEffectWithoutDispatchingAnotherEffect()throws Exception{
  TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=tasks.createCapabilityTask("original","conv").getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",4);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("fixture_effect"));tasks.openOwnerInputMailbox(id,run,epoch,"conv",control);
  String operation=tasks.journal(id,run,epoch,control).beginOperation("fixture_effect",new JSONObject());
  JSONObject message=new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("clientMessageId",java.util.UUID.randomUUID().toString()).put("content","owner text while original effect is inflight").put("inputMode","queue").put("attachments",new JSONArray());
  JSONObject receipt=tasks.appendOwnerInput(id,run,epoch,"conv",message,"queue",control);assertEquals(message.getString("id"),receipt.getString("messageId"));assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());assertEquals(operation,tasks.get(id,false).getJSONArray("operations").getJSONObject(0).getString("operationId"));
 }
}
