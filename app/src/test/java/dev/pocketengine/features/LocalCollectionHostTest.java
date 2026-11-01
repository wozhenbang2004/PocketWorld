package dev.pocketengine.features;
import android.app.Application;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class LocalCollectionHostTest {
 private JSONObject waitResult(BlockingQueue<JSONObject> events,String request)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){JSONObject event=events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&request.equals(event.optString("requestId")))return event;}throw new AssertionError("missing host reply");}
 @Test public void collectionIsTrackedBeforeDispatchAndLateReceiptUpdatesSameTaskWhileMaterializeStaysLocal()throws Exception{
  TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();AtomicReference<JSONObject> received=new AtomicReference<>();
  try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){
   host.register(new EngineModule(){public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{
    received.set(new JSONObject(input.toString()));if(command.equals("termux.materialize"))return new JSONObject().put("id","new-local-copy");
    assertEquals("termux.collect",command);String task=input.getString("_taskId");assertNotNull(tasks.get(task,false));assertEquals(task,input.getString("operationId"));assertEquals(input.getString("_runId"),input.getString("_operationId"));
    return new JSONObject().put("id",input.getString("operationId")).put("operationId",input.getString("operationId")).put("checkoutId",input.getString("checkoutId")).put("kind","workspace_collect").put("status","unknown");
   }});
   String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","local").put("command","termux.collect").put("requestId",request).put("payload",new JSONObject().put("checkoutId","copy-one")));
   JSONObject result=waitResult(events,request);assertTrue(result.getBoolean("ok"));String id=result.getJSONObject("result").getString("hostTaskId");assertEquals(1,tasks.list().length());assertEquals("termux.collect",tasks.get(id,false).getString("command"));assertEquals("reconciling",tasks.get(id,false).getString("status"));
   JSONObject receipt=new JSONObject().put("id",id).put("operationId",id).put("checkoutId","copy-one").put("status","completed");host.recordEvent(new JSONObject().put("type","feature_event").put("feature","local").put("payload",new JSONObject().put("kind","termux_job").put("job",receipt)));
   assertEquals(1,tasks.list().length());assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));
   request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","local").put("command","termux.materialize").put("requestId",request).put("payload",new JSONObject().put("checkoutId","copy-one").put("jobId",id)));assertTrue(waitResult(events,request).getBoolean("ok"));assertFalse(received.get().has("_taskId"));assertEquals(1,tasks.list().length());
  }
 }
}
