package dev.pocketengine.features.teams;
import dev.pocketengine.core.ModelConfig;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeamCoordinatorTest {
    private JSONObject team()throws Exception{return new JSONObject().put("id","team-1").put("name","讨论组").put("members",new JSONArray().put(new JSONObject().put("name","方案").put("role","提出方案")).put(new JSONObject().put("name","审查").put("role","审查错误")));}
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","test-secret","model",false,20,10);}
    private JSONObject waitDone(TeamCoordinator c,String id)throws Exception {long deadline=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<deadline){JSONObject run=c.get(id);String s=run.getString("status");if(!s.equals("queued")&&!s.equals("running")&&!s.equals("stopping"))return run;Thread.sleep(10);}throw new AssertionError("team timeout");}
    @Test public void membersReallyRunConcurrentlyAndDebateHasABoundedCallBudget()throws Exception {
        File state=new File(Files.createTempDirectory("pocket-team-test").toFile(),"runs.json");AtomicInteger calls=new AtomicInteger(),concurrent=new AtomicInteger(),peak=new AtomicInteger();CountDownLatch pair=new CountDownLatch(2);
        try(TeamCoordinator c=new TeamCoordinator(state,(config,messages,control)->{int call=calls.incrementAndGet(),active=concurrent.incrementAndGet();peak.accumulateAndGet(active,Math::max);try{if(call<=2){pair.countDown();assertTrue(pair.await(1,TimeUnit.SECONDS));}control.check();return new JSONObject().put("content","真实fixture产出 "+call+" test-secret");}finally{concurrent.decrementAndGet();}},event->{})){
            String operationId=java.util.UUID.randomUUID().toString();String id=c.start(team(),"审查方案 test-secret",2,config(),operationId).getString("id");assertEquals(operationId,id);JSONObject run=waitDone(c,id);assertEquals(operationId,run.getString("operationId"));
            assertEquals("completed",run.getString("status"));assertEquals(5,calls.get());assertEquals(5,run.getInt("callsUsed"));assertEquals(2,peak.get());assertFalse(run.toString().contains("test-secret"));assertEquals("completed",run.getJSONArray("members").getJSONObject(0).getString("status"));try{c.start(team(),"repeat",1,config(),operationId);fail("operation IDs cannot replay");}catch(IllegalStateException duplicate){}assertEquals(5,calls.get());
        }
        try(TeamCoordinator restored=new TeamCoordinator(state,(c,m,t)->{throw new AssertionError("must not replay");},e->{})){assertEquals("completed",restored.list().getJSONObject(0).getString("status"));}
    }
    @Test public void cancelStopsQueuedCallsAndRetainsRealPartialOutcomes()throws Exception {
        File state=new File(Files.createTempDirectory("pocket-team-cancel").toFile(),"runs.json");CountDownLatch started=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        try(TeamCoordinator c=new TeamCoordinator(state,(config,messages,control)->{calls.incrementAndGet();started.countDown();while(true){control.check();Thread.sleep(10);}},event->{})){
            String id=c.start(team(),"等待",2,config()).getString("id");assertTrue(started.await(1,TimeUnit.SECONDS));c.cancel(id);JSONObject run=waitDone(c,id);assertEquals("cancelled",run.getString("status"));assertTrue(calls.get()<=2);
        }
    }
    @Test public void restartDoesNotRequeueAnInterruptedTeam()throws Exception {
        File state=new File(Files.createTempDirectory("pocket-team-restart").toFile(),"runs.json");dev.pocketengine.features.workspace.JsonFiles.write(state,new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("id","x").put("status","stopping"))));
        try(TeamCoordinator c=new TeamCoordinator(state,(m,p,t)->{throw new AssertionError();},event->{})){assertEquals("interrupted",c.get("x").getString("status"));}
    }
    @Test public void aGatewayThatReturnsAfterCancellationCannotOverwriteTaskState()throws Exception {
        File state=new File(Files.createTempDirectory("pocket-team-late").toFile(),"runs.json");CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        try(TeamCoordinator c=new TeamCoordinator(state,(config,messages,control)->{calls.incrementAndGet();started.countDown();release.await(2,TimeUnit.SECONDS);return new JSONObject().put("content","late result must not commit");},event->{})){
            String id=c.start(team(),"检查迟到回复",1,config()).getString("id");assertTrue(started.await(1,TimeUnit.SECONDS));c.cancel(id);assertEquals("cancelled",waitDone(c,id).getString("status"));
            release.countDown();Thread.sleep(80);JSONObject saved=c.get(id);assertEquals("cancelled",saved.getString("status"));assertFalse(saved.toString().contains("late result must not commit"));assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
}
