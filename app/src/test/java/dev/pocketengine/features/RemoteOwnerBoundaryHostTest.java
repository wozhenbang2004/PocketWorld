package dev.pocketengine.features;
import android.app.Application;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOwnerBoundaryHostTest {
 private JSONObject reply(FeatureHost host,CopyOnWriteArrayList<JSONObject> events,String command,JSONObject payload)throws Exception{String id=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command",command).put("requestId",id).put("payload",payload));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<until){for(JSONObject event:events)if(id.equals(event.optString("requestId")))return event;Thread.sleep(5);}throw new AssertionError("no native response");}
 private EngineModule remote(AtomicInteger calls){return new EngineModule(){public String id(){return "remote";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{calls.incrementAndGet();return new JSONObject().put("id",input.optString("_operationId","job-a")).put("status","running");}};}
 @Test public void genericContinuationCannotBecomeANewTaskOrDispatch()throws Exception{TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AtomicInteger calls=new AtomicInteger();CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(remote(calls));JSONObject result=reply(host,events,"submitJob",new JSONObject().put("profileId","computer").put("resumeJobId","source-job").put("threadId","source-thread").put("jobOperationId","source-op").put("prompt","不可绕过原生审阅"));assertFalse(result.toString(),result.getBoolean("ok"));assertEquals(0,calls.get());assertEquals(0,tasks.list().length());}}
 @Test public void genericApprovalCannotBypassOneShotOwnerJournal()throws Exception{TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AtomicInteger calls=new AtomicInteger();CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){host.register(remote(calls));assertFalse(reply(host,events,"approveJob",new JSONObject().put("profileId","computer").put("jobId","job-a").put("approvalRequestId","request-a").put("decision","accept")).getBoolean("ok"));assertEquals(0,calls.get());}}
 @Test public void actualNativeReceiptRetainsProtocolOriginSessionAndApprovalMetadata()throws Exception{TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();tasks.beginExternal("remote","submitJob",id,new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","claude"));tasks.updateExternal(id,new JSONObject().put("id","job-a").put("profileId","computer").put("operationId",id).put("status","completed").put("serviceBase","https://computer.example").put("environmentId","workspace").put("worker","claude").put("protocol","claude_stream_json").put("protocolVerified",true).put("sessionIdentityConfirmed",true).put("sessionId","session-a").put("threadId","session-a").put("promptRequestId","prompt-a").put("reportedModel","actual-model").put("model","requested-model").put("approvalReceipts",new JSONArray().put(new JSONObject().put("requestId","request-a").put("status","sent"))).put("controls",new JSONObject().put("threadResume",true)));JSONObject actual=tasks.get(id,false).getJSONObject("evidence");assertEquals("https://computer.example",actual.getString("serviceBase"));assertEquals("session-a",actual.getString("sessionId"));assertEquals("actual-model",actual.getString("reportedModel"));assertEquals("sent",actual.getJSONArray("approvalReceipts").getJSONObject(0).getString("status"));assertFalse(actual.getBoolean("automaticallyVerified"));assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));}
}
