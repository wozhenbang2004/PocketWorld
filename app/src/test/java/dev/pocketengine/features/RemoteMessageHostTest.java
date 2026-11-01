package dev.pocketengine.features;
import android.app.Application;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteMessageHostTest {
 static final class Fixture implements AutoCloseable {
  final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();
  final FeatureHost host=new FeatureHost(this::event,tasks,()->false);final AtomicInteger sends=new AtomicInteger(),queries=new AtomicInteger(),cancels=new AtomicInteger();
  volatile boolean uncertain,foreign,block,cancelAtPreparation;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  final String id=UUID.randomUUID().toString();
  Fixture()throws Exception{
   tasks.beginExternal("remote","submitJob",id,new JSONObject().put("profileId","computer"));tasks.updateExternal(id,job("running"));
   host.register(new EngineModule(){public String id(){return "remote";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{
    if(command.equals("cancelJob")){cancels.incrementAndGet();return job("cancelled");}
    if(command.equals("steerJob")){sends.incrementAndGet();entered.countDown();if(block&&!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");}
    else if(command.equals("getSteerMessage"))queries.incrementAndGet();else throw new IllegalArgumentException(command);
    if(uncertain)throw new java.io.IOException("fixture disconnected after dispatch");
    return new JSONObject(input.toString()).put("operationId",input.getString("messageId")).put("status","accepted").put("turnId",foreign?"foreign-turn":"turn-1");
   }});
  }
  void event(JSONObject value){events.add(value);if(cancelAtPreparation&&value.optString("type").equals("task"))try{
   JSONObject task=value.getJSONObject("task");JSONArray ops=task.optJSONArray("operations");if(ops!=null&&ops.length()>0&&ops.getJSONObject(ops.length()-1).optString("state").equals("message_prepared")){cancelAtPreparation=false;tasks.status(task.getString("id"),"cancelled","fixture stopped before claim");}
  }catch(Exception failure){throw new IllegalStateException(failure);}}
  JSONObject job(String status)throws Exception{return new JSONObject().put("id","job-"+id).put("operationId",id).put("profileId","computer").put("threadId","thread-1").put("turnId","turn-1").put("protocol","codex_app_server").put("status",status).put("controls",new JSONObject().put("inputSteering",true));}
  JSONObject input()throws Exception{return new JSONObject().put("taskId",id).put("runId",id).put("coordinatorEpoch",0).put("profileId","computer").put("jobId","job-"+id).put("jobOperationId",id).put("threadId","thread-1").put("expectedTurnId","turn-1").put("messageId",UUID.randomUUID().toString()).put("text","fixture owner clarification");}
  String request(String command,JSONObject input)throws Exception{String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command",command).put("requestId",request).put("payload",input));return request;}
  JSONObject result(String request)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){JSONObject event=events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&request.equals(event.optString("requestId")))return event;}throw new AssertionError("no native result");}
  public void close(){release.countDown();host.close();}
 }
 @Test public void duplicateNativeSignalOnlyQueriesOriginalMessageAndNeverCreatesSecondTask()throws Exception{
  try(Fixture f=new Fixture()){JSONObject input=f.input();assertEquals("accepted",f.result(f.request("steerJob",input)).getJSONObject("result").getString("status"));
   f.result(f.request("steerJob",input));assertEquals(1,f.sends.get());assertEquals(1,f.queries.get());assertEquals(1,f.tasks.list().length());assertEquals("running",f.tasks.get(f.id,false).getString("status"));assertFalse(f.tasks.get(f.id,false).toString().contains("fixture owner clarification"));}
 }
 @Test public void staleNativeReviewIsExplicitUnsentRejectionWithNoReservation()throws Exception{
  try(Fixture f=new Fixture()){JSONObject input=f.input().put("expectedTurnId","old-turn");JSONObject result=f.result(f.request("steerJob",input)).getJSONObject("result");assertEquals("native_preflight",result.getString("receiptSource"));assertEquals("rejected",result.getString("status"));assertFalse(result.getBoolean("dispatched"));assertEquals(0,f.sends.get());assertEquals(0,f.tasks.get(f.id,false).getJSONArray("operations").length());}
 }
 @Test public void uncertainDispatchStaysQueryOnlyAndLateAckDoesNotCompleteOriginalJob()throws Exception{
  try(Fixture f=new Fixture()){f.uncertain=true;JSONObject input=f.input();assertEquals("unknown",f.result(f.request("steerJob",input)).getJSONObject("result").getString("status"));
   f.tasks.updateExternal(f.id,f.job("cancelled"));f.uncertain=false;assertEquals("accepted",f.result(f.request("getSteerMessage",new JSONObject().put("taskId",f.id).put("messageId",input.getString("messageId")))).getJSONObject("result").getString("status"));assertEquals(1,f.sends.get());assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));}
 }
 @Test public void slowSteerCannotBlockUrgentCancelQueue()throws Exception{
  try(Fixture f=new Fixture()){f.block=true;JSONObject input=f.input();String request=f.request("steerJob",input);assertTrue(f.entered.await(2,TimeUnit.SECONDS));
   JSONObject stopped=f.result(f.request("cancelJob",new JSONObject().put("profileId","computer").put("jobId","job-"+f.id)));assertTrue(stopped.getBoolean("ok"));assertEquals(1,f.cancels.get());assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));f.release.countDown();f.result(request);assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));}
 }
 @Test public void foreignAckAndUnregisteredSteerEventNeverReachGenericJobSetter()throws Exception{
  try(Fixture f=new Fixture()){f.foreign=true;JSONObject input=f.input();assertEquals("unknown",f.result(f.request("steerJob",input)).getJSONObject("result").getString("status"));
   JSONObject forged=f.job("completed").put("messageId","not-registered").put("jobId","job-"+f.id);f.host.recordEvent(new JSONObject().put("type","feature_event").put("feature","remote").put("payload",new JSONObject().put("kind","steer_message").put("receipt",forged)));
   assertEquals("running",f.tasks.get(f.id,false).getString("status"));assertEquals("unknown",f.tasks.remoteMessage(f.id,input.getString("messageId")).getString("status"));}
 }
 @Test public void cancelBetweenPreparationAndClaimIsRecordedAsDefinitelyUnsent()throws Exception{
  try(Fixture f=new Fixture()){f.cancelAtPreparation=true;JSONObject input=f.input();JSONObject result=f.result(f.request("steerJob",input)).getJSONObject("result");assertEquals("rejected",result.getString("status"));assertFalse(result.getJSONObject("receipt").getBoolean("dispatched"));assertEquals(0,f.sends.get());assertEquals("cancelled",f.tasks.get(f.id,false).getString("status"));}
 }
 @Test public void conflictingDuplicateCannotReplaceOriginalAcceptedJournalWithPreflightRejection()throws Exception{
  try(Fixture f=new Fixture()){JSONObject input=f.input();f.result(f.request("steerJob",input));JSONObject result=f.result(f.request("steerJob",new JSONObject(input.toString()).put("text","changed text"))).getJSONObject("result");assertEquals("native_journal",result.getString("receiptSource"));assertEquals("accepted",result.getString("status"));assertEquals(1,f.sends.get());assertEquals(0,f.queries.get());}
 }

}
