package dev.pocketengine.features.remote;
import android.app.Application;
import dev.pocketengine.features.remote.RemoteOwnerWorkflowIntegrationTest.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOwnerReviewExtraTest {
 @Test public void replacementOriginDoesNotReceiveUnknownNativeOperationQuery() throws Exception {
  try(Fixture f=new Fixture("claude");Server replacement=new Server()) {
   JSONObject snapshot=f.open(UUID.randomUUID().toString());
   f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","changed").put("baseUrl",replacement.base()));
   f.workflow.handle("continue",f.action("continue",snapshot).put("prompt","exact original session"));
   assertEquals(0,replacement.submits.get());
   assertEquals("reconciling",f.tasks.get(f.task,false).getString("status"));
   JSONObject result=f.workflow.handle("refresh",f.action("refresh",snapshot));
   System.out.println("REVIEW_ORIGIN_RESULT original="+f.server.base()+" replacement="+replacement.base()+" replacementQueries="+replacement.queries.get()+" source="+result.getJSONObject("snapshot").getJSONObject("source"));
   assertEquals("Reconciliation must not GET a replacement origin",0,replacement.queries.get());
   assertEquals(f.server.base(),f.tasks.get(f.task,false).getJSONObject("evidence").getString("serviceBase"));
  }
 }
 @Test public void oldJobConnectionLossCannotOverwriteCurrentContinuation() throws Exception {
  try(Fixture f=new Fixture("claude")) {
   JSONObject snapshot=f.open(UUID.randomUUID().toString());
   f.workflow.handle("continue",f.action("continue",snapshot).put("prompt","new explicit owner prompt"));
   JSONObject before=f.tasks.get(f.task,false);
   assertEquals("job-2",before.getString("externalId"));
   assertEquals("completed",before.getString("executionStatus"));
   f.server.http.stop(0);
   f.remote.handle("cancelJob",new JSONObject().put("profileId","computer").put("jobId","job-1").put("serviceBase",f.server.base()).put("jobOperationId",f.run));
   JSONObject after=f.tasks.get(f.task,false);
   System.out.println("REVIEW_OLD_JOB_LOSS run="+after.getString("runId")+" currentJob="+after.getString("externalId")+" before="+before.getString("executionStatus")+" after="+after.getString("executionStatus")+" status="+after.getString("status"));
   assertEquals("Old job connection loss must not change the newer run's receipt",before.getString("executionStatus"),after.getString("executionStatus"));
  }
 }

}
