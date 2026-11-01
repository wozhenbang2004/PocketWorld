package dev.pocketengine.features.remote;
import android.app.Application;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.features.remote.RemoteOwnerWorkflowIntegrationTest.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOriginReadAdmissionTest {
 @Test public void preparedAndClaimedAttemptsWithoutTransportReservationStayPinnedAcrossRestart()throws Exception{
  for(boolean claim:new boolean[]{false,true})try(Fixture f=new Fixture("claude");Server replacement=new Server()){
   JSONObject source=f.open(UUID.randomUUID().toString()).getJSONObject("source");String operation=f.tasks.prepareRemoteContinuation(source,"only query registered original").getString("operationId");if(claim)f.tasks.claimRemoteContinuation(f.task,operation);
   f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","replacement").put("baseUrl",replacement.base()));TaskStore recovered=new TaskStore(RuntimeEnvironment.getApplication());RemoteOwnerWorkflows workflow=new RemoteOwnerWorkflows(f.host,recovered,event->{});JSONObject snapshot=workflow.handle("open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",f.task)).getJSONObject("snapshot");assertFalse(snapshot.getBoolean("fresh"));assertFalse(snapshot.getBoolean("canContinue"));assertEquals(operation,snapshot.getJSONObject("source").getString("jobOperationId"));assertEquals(f.server.base(),snapshot.getJSONObject("source").getString("serviceBase"));assertEquals(0,replacement.queries.get());assertEquals(0,replacement.submits.get());assertEquals(1,recovered.list().length());
  }
 }
 @Test public void notFoundWithoutOperationOrJobIdentityCannotOverwriteNativeEvidence()throws Exception{try(Fixture f=new Fixture("claude","running");Server replacement=new Server()){
  String before=TaskStore.capabilityFingerprint(f.tasks.get(f.task,false));f.tasks.updateExternal(f.task,new JSONObject().put("status","not_found").put("profileId","computer").put("serviceBase",replacement.base()));assertEquals(before,TaskStore.capabilityFingerprint(f.tasks.get(f.task,false)));f.tasks.updateExternal(f.task,new JSONObject().put("id","job-1").put("profileId","computer").put("serviceBase",f.server.base()).put("status","completed"));assertEquals(before,TaskStore.capabilityFingerprint(f.tasks.get(f.task,false)));
 }}
 @Test public void aCurrentJobConnectionLossStillRecordsExactOriginalUncertainty()throws Exception{try(Fixture f=new Fixture("claude","running")){
  f.server.http.stop(0);f.remote.handle("cancelJob",new JSONObject().put("profileId","computer").put("jobId","job-1").put("serviceBase",f.server.base()).put("jobOperationId",f.run));JSONObject current=f.tasks.get(f.task,false);assertEquals("unknown",current.getString("executionStatus"));assertEquals("reconciling",current.getString("status"));assertEquals(f.run,current.getJSONObject("evidence").getString("operationId"));assertEquals(f.server.base(),current.getJSONObject("evidence").getString("serviceBase"));assertTrue(f.tasks.hasUnresolved(f.task));
 }}
 @Test public void initialReceiptOriginCannotBeRepointedEvenWithItsOperationId()throws Exception{try(Fixture f=new Fixture("claude","running");Server replacement=new Server()){
  JSONObject forged=new JSONObject(f.server.jobs.get("job-1").toString()).put("profileId","computer").put("serviceBase",replacement.base());String before=TaskStore.capabilityFingerprint(f.tasks.get(f.task,false));assertThrows(SecurityException.class,()->f.tasks.updateExternal(f.task,forged));assertEquals(before,TaskStore.capabilityFingerprint(f.tasks.get(f.task,false)));
 }}
 @Test public void registeredNativeOperationCanQueryPinnedOriginalNotFoundWithoutASecondPost()throws Exception{try(Fixture f=new Fixture("claude")){
  JSONObject source=f.open(UUID.randomUUID().toString()).getJSONObject("source");String operation=f.tasks.prepareRemoteContinuation(source,"query-only unsent operation").getString("operationId");f.tasks.claimRemoteContinuation(f.task,operation);JSONObject snapshot=f.open(UUID.randomUUID().toString());assertEquals("not_found",snapshot.getJSONObject("source").getString("executorStatus"));assertEquals(operation,snapshot.getJSONObject("source").getString("jobOperationId"));assertEquals(f.server.base(),snapshot.getJSONObject("source").getString("serviceBase"));assertFalse(snapshot.getBoolean("canContinue"));assertEquals(1,f.server.submits.get());assertEquals(2,f.tasks.get(f.task,false).getJSONArray("operations").length());assertTrue(f.tasks.hasUnresolved(f.task));
 }}
}
